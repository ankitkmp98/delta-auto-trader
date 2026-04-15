import org.json.JSONArray;
import org.json.JSONObject;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * CoinDCX Futures Trader — v3 OPTIMIZED
 *
 * KEY CHANGES FROM V2:
 *
 *  [NEW-1]  MARGIN SYSTEM: Max 80 INR margin per trade (10x lev → max 800 INR position size).
 *           Quantity capped by: (MAX_MARGIN_INR * LEVERAGE) / entry_price
 *
 *  [NEW-2]  SL PLACEMENT: Smart SL that sits between swing low + buffer and liquidation point.
 *           SL uses ADAPTIVE_SL_FACTOR (0.5-0.7x ATR from swing) to avoid tight/loose extremes.
 *           Formula: rawSL = swingLow ± (ADAPTIVE_SL_FACTOR * ATR)
 *           Then clamped between minSL (breathing room) and LIQUIDATION_BUFFER below liq price.
 *
 *  [NEW-3]  TP PLACEMENT: Higher R:R (1:3) for better reward, positioned to maximize hit rate.
 *           TP = entry + (RR_RATIO * actualRisk)
 *           Entry zone filtered to only high-conviction signals (stricter filters).
 *
 *  [NEW-4]  REMOVED: MAX_POSITIONS limit — trade as many as you want.
 *           REMOVED: MAX_SECTOR_POS sector correlation guard.
 *           REMOVED: Daily loss circuit breaker (getDailyPnl checks).
 *
 *  [NEW-5]  LIQUIDATION GUARD: SL computed to never exceed liquidation price.
 *           liquidPrice = entryPrice * (1 ± LIQUIDATION_BUFFER_PCT)
 *           For long:  SL ≥ liquidPrice (stays above liq price)
 *           For short: SL ≤ liquidPrice (stays below liq price)
 *
 *  [NEW-6]  EMA LOGIC TUNED: Stricter entry rules to improve TP hit rate.
 *           H3 now requires EMA9 > EMA21 + (MIN_EMA_DISTANCE * ATR) for stronger trend.
 *           This filters weak/chop zones and increases conviction.
 *
 *  [NEW-7]  VOLUME CONFIRMATION: S3 filter now requires 1.5x (not 1.1x) average volume
 *           to reduce low-confidence entries.
 *
 *  [NEW-8]  SOFT FILTERS: At least 2 of 3 must pass (not 1 of 3) for higher conviction.
 *
 * SIGNAL ARCHITECTURE (4-HARD + 3-SOFT, higher bar):
 *
 *   HARD (ALL must pass):
 *     H1. 1H EMA-50   : macro trend
 *     H2. 4H EMA-21   : mid-tier trend
 *     H3. 15m EMA 9/21: local trend (with min distance buffer)
 *     H4. MACD hist   : bullish/bearish AND histogram building momentum
 *
 *   SOFT (2 of 3 required for higher conviction):
 *     S1. RSI zone     : Long 45-64 | Short 36-56
 *     S2. Candle body  : Previous closed candle matches direction
 *     S3. Volume spike : Entry bar volume > 1.5x 20-bar average
 */
public class CoinDCXFuturesTrader8C_BUY_SELL_NEW_LOGIC_THREE {

    // =========================================================================
    // Configuration — Tuned for margin & SL/TP
    // =========================================================================
    private static final String API_KEY    = System.getenv("DELTA_API_KEY");
    private static final String API_SECRET = System.getenv("DELTA_API_SECRET");
    private static final String BASE_URL       = "https://api.coindcx.com";
    private static final String PUBLIC_API_URL = "https://public.coindcx.com";

    // Capital & risk — NEW: Margin-based instead of risk % based
    private static final double TOTAL_CAPITAL      = 12_00.0;      // your actual INR capital
    private static final double MAX_MARGIN_INR     = 150.0;         // max 80 INR margin per trade
    private static final int    LEVERAGE           = 10;           // 10x leverage
    // Max position size = MAX_MARGIN_INR * LEVERAGE = 800 INR
    private static final double MAX_POSITION_SIZE  = MAX_MARGIN_INR * LEVERAGE; // 800 INR

    // Liquidation protection
    private static final double LIQUIDATION_BUFFER_PCT = 0.05;  // 5% buffer from liquidation
    private static final double LIQUIDATION_CUSHION    = 0.02;  // extra 2% safety margin

    // Execution
    private static final int  MAX_ENTRY_PRICE_CHECKS = 15;
    private static final int  ENTRY_CHECK_DELAY_MS   = 1500;
    private static final long TICK_CACHE_TTL_MS      = 3_600_000L;
    private static final int  SCAN_THREADS           = 10;

    // Indicator parameters
    private static final int EMA_FAST   = 9;
    private static final int EMA_MID    = 21;
    private static final int EMA_MACRO  = 50;
    private static final int MACD_FAST  = 12;
    private static final int MACD_SLOW  = 26;
    private static final int MACD_SIG   = 9;
    private static final int RSI_PERIOD = 14;
    private static final int ATR_PERIOD = 14;
    private static final int SWING_BARS = 15;
    private static final int VOL_PERIOD = 20;

    // NEW: EMA distance buffer for stronger trend confirmation
    private static final double MIN_EMA_DISTANCE = 0.3;  // EMA9 must be 0.3*ATR above EMA21 for bullish

    // RSI zones
    private static final double RSI_LONG_MIN  = 45.0;
    private static final double RSI_LONG_MAX  = 64.0;
    private static final double RSI_SHORT_MIN = 36.0;
    private static final double RSI_SHORT_MAX = 56.0;

    // NEW: Smart SL/TP system
    private static final double ADAPTIVE_SL_FACTOR = 0.6;  // 0.6x ATR from swing extreme
    private static final double SL_MIN_ATR         = 1.0;  // minimum breathing room
    private static final double SL_MAX_ATR         = 2.5;  // maximum SL distance (risk cap)
    private static final double RR_RATIO           = 3.0;  // 1:3 reward:risk for better TP hits

    // Candle counts
    private static final int CANDLE_15M = 200;
    private static final int CANDLE_4H  = 60;
    private static final int CANDLE_1H  = 120;

    // Scan log file
    private static final String SCAN_LOG = "scan_log_v3.csv";

    // =========================================================================
    // Caches
    // =========================================================================
    private static final Map<String, JSONObject> instrumentCache  = new ConcurrentHashMap<>();
    private static final Object                  cacheLock        = new Object();
    private static volatile long                 lastCacheUpdate  = 0;

    // =========================================================================
    // Coin list
    // =========================================================================
    private static final String[] COIN_SYMBOLS = {
        "SOL", "1000SHIB", "API3", "DOGS", "KAVA", "ARK", "VOXEL", "SXP", "FLOW", "CHESS",
        "AXL", "MANA", "BNB", "1000BONK", "ALPHA", "NEAR", "TRB", "GRT", "WIF", "RSR",
        "QTUM", "AVAX", "BIGTIME", "COTI", "PONKE", "ETHFI", "ICP", "VET", "ACH", "MINA",
        "COMP", "XAI", "JTO", "USTC", "SPELL", "KNC", "INJ", "BLUR", "DYM", "SNX",
        "IMX", "1000WHY", "ALGO", "CRV", "JUP", "ZEN", "BAT", "SAGA", "AAVE", "SEI",
        "KAIA", "NFP", "PEOPLE", "SUI", "ONE", "RENDER", "POLYX", "ENS", "MOVR", "BRETT",
        "ETH", "OMNI", "MKR", "AR", "CFX", "ID", "CELR", "LDO", "UNI", "LTC",
        "TAO", "CKB", "FET", "STX", "SAND", "XLM", "EGLD", "BOME", "HOT", "LUNA2",
        "ADA", "RVN", "GLM", "MASK", "STRK", "GALA", "YFI", "IOST", "OP",
        "1000PEPE", "TRUMP", "ZIL", "RPL", "WLD", "DOGE", "XMR", "ONDO", "APT", "HIVE",
        "FIL", "TIA", "CHZ", "ETC", "LINK", "ORDI", "ATOM", "TON", "TRX", "HBAR",
        "NEO", "IOTA", "GMX", "QNT", "FTM", "VANA", "FLUX", "DASH", "ZRX", "MANTA",
        "CAKE", "PYTH", "ARB", "SFP", "METIS", "LRC", "SKL", "ZEC", "RUNE", "ALICE",
        "ANKR", "XTZ", "GTC", "ROSE", "BCH", "CELO", "BAND", "1INCH", "SUPER", "ILV",
        "SSV", "ARPA", "FXS", "UMA", "MTL", "DEGEN", "XVS", "ACE", "1000FLOKI", "AKT",
        "ASTR", "TWT", "CTSI", "VIRTUAL", "CHR", "EDU", "PROM", "KSM", "BICO", "DENT",
        "ALT", "C98", "RLC", "SUN", "PENDLE", "BANANA", "NMR", "POL", "MAGIC",
        "MOODENG", "WAXP", "ZK", "GAS", "ALPACA", "TNSR", "PHB", "POWR", "LSK", "FIO",
        "DEFI", "KAS", "1000SATS", "ARKM", "PIXEL", "MAV", "REI", "ZRO", "COOKIE",
        "JOE", "BNT", "CYBER", "SCRT", "XRP", "VELODROME", "ONG", "AERO", "HOOK", "AI16Z",
        "KMNO", "LPT", "THETA", "NTRN", "VIC", "RAYSOL", "PARTI", "MELANIA", "MEW", "EIGEN",
        "XVG", "MYRO", "IO", "SHELL", "AUCTION", "STORJ", "SWELL", "COS", "FORTH", "BEL",
        "PNUT", "HIGH", "ENJ", "LISTA", "ZETA", "MORPHO", "WOO", "MLN", "COW", "HEI",
        "DEXE", "OM", "RED", "GHST", "STEEM", "LOKA", "ACT", "KAITO", "DIA", "SUSHI",
        "AGLD", "TLM", "BMT", "MAVIA", "ALCH", "VTHO", "FUN", "POPCAT", "TURBO", "1000CHEEMS",
        "1000CAT", "1000LUNC", "1000RATS", "1000000MOG", "1MBABYDOGE", "1000XEC", "1000X",
        "BTCDOM", "USUAL", "PERP", "LAYER", "NKN", "MUBARAK", "FARTCOIN", "GOAT", "LEVER",
        "SOLV", "S", "ARC", "VINE", "RARE", "GPS", "IP", "AVAAI", "KOMA", "HFT"
    };

    private static final Set<String> INTEGER_QTY_PAIRS = Stream.of(COIN_SYMBOLS)
            .flatMap(s -> Stream.of("B-" + s + "_USDT", s + "_USDT"))
            .collect(Collectors.toCollection(HashSet::new));

    private static final String[] COINS_TO_TRADE = Stream.of(COIN_SYMBOLS)
            .map(s -> "B-" + s + "_USDT")
            .toArray(String[]::new);

    // =========================================================================
    // MAIN
    // =========================================================================
    public static void main(String[] args) {
        System.out.println("=== CoinDCX Futures Trader v3 — Optimized (Margin-Based, No Limits) ===");
        System.out.println("Max margin per trade: " + MAX_MARGIN_INR + " INR");
        System.out.println("Max position size: " + MAX_POSITION_SIZE + " INR (at " + LEVERAGE + "x leverage)");
        System.out.println("TP:SL Ratio: 1:" + RR_RATIO);
        System.out.println("");
        
        initScanLog();
        initInstrumentCache();

        // Fetch active positions (for informational purposes only)
        Set<String> active = getActivePositions();
        System.out.println("Active positions: " + active.size() + " → " + active);
        System.out.println(""); // NEW: No position cap check

        // [NEW-10] Parallel scanning — no limits on concurrent trades
        ExecutorService pool = Executors.newFixedThreadPool(SCAN_THREADS);
        List<Future<?>> futures = new ArrayList<>();

        for (String pair : COINS_TO_TRADE) {
            if (active.contains(pair)) continue;
            futures.add(pool.submit(() -> {
                try { scanPair(pair, active); }
                catch (Exception e) { System.err.println("Error on " + pair + ": " + e.getMessage()); }
            }));
        }

        for (Future<?> f : futures) {
            try { f.get(); } catch (Exception ignored) {}
        }
        pool.shutdown();
        System.out.println("\n=== Scan complete ===");
    }

    // =========================================================================
    // CORE SCAN LOGIC (per pair) — Improved SL/TP Logic
    // =========================================================================
    private static void scanPair(String pair, Set<String> active) throws Exception {
        System.out.println("\n==== " + pair + " ====");

        // ── Fetch candles ─────────────────────────────────────────────────────
        JSONArray raw15m = getCandlestickData(pair, "15",  CANDLE_15M);
        JSONArray raw4h  = getCandlestickData(pair, "240", CANDLE_4H);
        JSONArray raw1h  = getCandlestickData(pair, "60",  CANDLE_1H);

        if (raw15m == null || raw15m.length() < 60) {
            System.out.println("  Insufficient 15m candles — skip");
            log(pair, "SKIP", "insufficient_15m", "");
            return;
        }
        if (raw4h == null || raw4h.length() < 25) {
            System.out.println("  Insufficient 4H candles — skip");
            log(pair, "SKIP", "insufficient_4h", "");
            return;
        }
        if (raw1h == null || raw1h.length() < EMA_MACRO) {
            System.out.println("  Insufficient 1H candles — skip");
            log(pair, "SKIP", "insufficient_1h", "");
            return;
        }

        // Guard against insufficient data for MACD
        if (raw15m.length() < MACD_SLOW + MACD_SIG + 5) {
            System.out.println("  Insufficient data for MACD — skip");
            log(pair, "SKIP", "insufficient_macd_data", "");
            return;
        }

        double[] cl15 = extractCloses(raw15m);
        double[] op15 = extractOpens(raw15m);
        double[] hi15 = extractHighs(raw15m);
        double[] lo15 = extractLows(raw15m);
        double[] vl15 = extractVolumes(raw15m);
        double[] cl4h = extractCloses(raw4h);
        double[] cl1h = extractCloses(raw1h);

        double lastClose = cl15[cl15.length - 1];
        double prevClose = cl15[cl15.length - 2];
        double prevOpen  = op15[op15.length - 2];
        double tickSize  = getTickSize(pair);
        double atr       = calcATR(hi15, lo15, cl15, ATR_PERIOD);

        System.out.printf("  Price=%.6f  ATR=%.6f  Tick=%.8f%n", lastClose, atr, tickSize);

        // ── HARD FILTER H1: 1H Macro Trend ───────────────────────────────────
        double  ema1h     = calcEMA(cl1h, EMA_MACRO);
        boolean macroUp   = lastClose > ema1h;
        boolean macroDown = lastClose < ema1h;
        System.out.printf("  [H1] 1H EMA50=%.6f → %s%n", ema1h, macroUp ? "BULL" : "BEAR");
        if (!macroUp && !macroDown) {
            log(pair, "FAIL", "H1_flat", "");
            return;
        }

        // ── HARD FILTER H2: 4H Middle Tier ───────────────────────────────────
        double  ema4h    = calcEMA(cl4h, EMA_MID);
        boolean mid4hUp   = lastClose > ema4h;
        boolean mid4hDown = lastClose < ema4h;
        System.out.printf("  [H2] 4H EMA21=%.6f → %s%n", ema4h, mid4hUp ? "BULL" : "BEAR");
        if ((macroUp && !mid4hUp) || (macroDown && !mid4hDown)) {
            System.out.println("  H2 FAIL — 4H mid-tier disagrees with 1H macro — skip");
            log(pair, "FAIL", "H2_4h_misalign", "");
            return;
        }
        System.out.println("  H2 OK — 4H aligned");

        // ── HARD FILTER H3: 15m Local Trend (NEW: stricter with EMA distance) ──
        double  ema9      = calcEMA(cl15, EMA_FAST);
        double  ema21     = calcEMA(cl15, EMA_MID);
        // NEW: EMA9 must be meaningfully above/below EMA21, not just above/below
        double  emaDistance = Math.abs(ema9 - ema21);
        double  minEmaDistance = MIN_EMA_DISTANCE * atr;
        boolean localUp   = ema9 > ema21 && emaDistance > minEmaDistance;
        boolean localDown = ema9 < ema21 && emaDistance > minEmaDistance;
        System.out.printf("  [H3] EMA9=%.6f EMA21=%.6f Dist=%.6f (min=%.6f) → %s%n",
                ema9, ema21, emaDistance, minEmaDistance, 
                localUp ? "UP" : localDown ? "DOWN" : "weak/flat");

        boolean trendUp   = macroUp   && mid4hUp   && localUp;
        boolean trendDown = macroDown && mid4hDown && localDown;
        if (!trendUp && !trendDown) {
            System.out.println("  H3 FAIL — 3-TF alignment failed or trend too weak — skip");
            log(pair, "FAIL", "H3_local_misalign", String.format("dist=%.6f", emaDistance));
            return;
        }
        System.out.println("  H3 OK — all 3 timeframes aligned: " + (trendUp ? "BULLISH" : "BEARISH"));

        // ── HARD FILTER H4: MACD with histogram momentum ───────────────────────
        double[] mv       = calcMACDWithPrev(cl15, MACD_FAST, MACD_SLOW, MACD_SIG);
        double   macdLine = mv[0], macdSigV = mv[1], macdHist = mv[2], prevHist = mv[3];
        System.out.printf("  [H4] MACD=%.6f Sig=%.6f Hist=%.6f PrevHist=%.6f%n",
                macdLine, macdSigV, macdHist, prevHist);

        boolean macdBull = macdLine > macdSigV && macdHist > prevHist;
        boolean macdBear = macdLine < macdSigV && macdHist < prevHist;

        if (trendUp   && !macdBull) {
            System.out.println("  H4 FAIL — MACD not bullish/building — skip");
            log(pair, "FAIL", "H4_macd_not_bull", String.valueOf(macdHist));
            return;
        }
        if (trendDown && !macdBear) {
            System.out.println("  H4 FAIL — MACD not bearish/building — skip");
            log(pair, "FAIL", "H4_macd_not_bear", String.valueOf(macdHist));
            return;
        }
        System.out.println("  H4 OK — MACD momentum building");

        // ── SOFT FILTERS: at least 2 of 3 must pass (stricter than before) ─────
        // S1: RSI zone
        double  rsi        = calcRSI(cl15, RSI_PERIOD);
        boolean rsiOkLong  = trendUp   && rsi >= RSI_LONG_MIN  && rsi <= RSI_LONG_MAX;
        boolean rsiOkShort = trendDown && rsi >= RSI_SHORT_MIN && rsi <= RSI_SHORT_MAX;
        boolean softRsi    = rsiOkLong || rsiOkShort;
        System.out.printf("  [S1] RSI=%.2f → %s%n", rsi, softRsi ? "PASS" : "fail");

        // S2: Previous candle body direction
        boolean prevBull   = prevClose > prevOpen;
        boolean prevBear   = prevClose < prevOpen;
        boolean softCandle = (trendUp && prevBull) || (trendDown && prevBear);
        System.out.printf("  [S2] Prev candle body → %s%n", softCandle ? "PASS" : "fail");

        // S3: Volume spike (NEW: increased to 1.5x from 1.1x)
        boolean softVolume = false;
        if (vl15 != null && vl15.length > VOL_PERIOD + 1) {
            double avgVol = calcSMA(vl15, VOL_PERIOD);
            double entryVol = vl15[vl15.length - 2];
            softVolume = entryVol > avgVol * 1.5;  // NEW: 1.5x instead of 1.1x
            System.out.printf("  [S3] Vol=%.2f AvgVol=%.2f (1.5x=%.2f) → %s%n",
                    entryVol, avgVol, avgVol * 1.5, softVolume ? "PASS" : "fail");
        } else {
            System.out.println("  [S3] Volume data unavailable — skip soft filter");
        }

        // NEW: Need 2 of 3 soft filters to pass
        int softCount = (softRsi ? 1 : 0) + (softCandle ? 1 : 0) + (softVolume ? 1 : 0);
        if (softCount < 2) {
            System.out.println("  SOFT FAIL — only " + softCount + " of 3 filters passed (need 2) — skip");
            log(pair, "FAIL", "SOFT_count_" + softCount, String.format("RSI=%.2f", rsi));
            return;
        }

        String softPassed = (softRsi ? "RSI " : "") + (softCandle ? "Candle " : "") + (softVolume ? "Volume" : "");
        System.out.println("  SOFT OK (" + softCount + " of 3) — confirmed by: " + softPassed.trim());

        // ── All filters passed ────────────────────────────────────────────────
        String side = trendUp ? "buy" : "sell";
        System.out.println("  >>> ALL FILTERS PASSED → " + side.toUpperCase() + " " + pair);

        // ── Get current price ─────────────────────────────────────────────────
        double currentPrice = getLastPrice(pair);
        if (currentPrice <= 0) {
            System.out.println("  Invalid price — skip");
            return;
        }

        // ── NEW: Compute SL using smart adaptive logic + liquidation guard ─────
        double slPrice = computeSmartStopLoss(side, currentPrice, hi15, lo15, atr, LEVERAGE);
        
        if (slPrice <= 0) {
            System.out.println("  Invalid SL from smart logic — skip");
            return;
        }

        // ── NEW: Risk-based quantity with margin cap ───────────────────────────
        double qty = calcQuantityFromMargin(currentPrice, pair);
        
        if (qty <= 0) {
            System.out.println("  Invalid qty from margin calc — skip");
            return;
        }

        // Verify position size doesn't exceed cap
        double positionSize = qty * currentPrice;
        if (positionSize > MAX_POSITION_SIZE) {
            System.out.println("  Position size " + positionSize + " exceeds max " + MAX_POSITION_SIZE + " — skip");
            return;
        }

        double actualRisk = Math.abs(currentPrice - slPrice);
        double riskAmt = actualRisk * qty;

        System.out.printf("  Placing %s | price=%.6f | qty=%.4f | margin=%.2f INR | size=%.2f INR | risk=%.2f INR%n",
                side.toUpperCase(), currentPrice, qty, (currentPrice * qty / LEVERAGE), positionSize, riskAmt);

        // ── Place order ───────────────────────────────────────────────────────
        JSONObject resp = placeFuturesMarketOrder(side, pair, qty, LEVERAGE,
                "email_notification", "isolated", "INR");

        if (resp == null || !resp.has("id")) {
            System.out.println("  Order failed: " + resp);
            return;
        }
        System.out.println("  Order placed! id=" + resp.getString("id"));
        synchronized (active) { active.add(pair); }

        // ── Confirm entry price ───────────────────────────────────────────────
        double entry = getEntryPrice(pair, resp.getString("id"));
        if (entry <= 0) {
            System.out.println("  Could not confirm entry — using estimated price");
            entry = currentPrice;
        }
        System.out.printf("  Entry confirmed: %.6f%n", entry);

        // ── Recompute SL/TP from actual entry ─────────────────────────────────
        slPrice = computeSmartStopLoss(side, entry, hi15, lo15, atr, LEVERAGE);
        slPrice = roundToTick(slPrice, tickSize);

        double risk = Math.abs(entry - slPrice);
        double tpPrice;
        if ("buy".equalsIgnoreCase(side)) {
            tpPrice = entry + (RR_RATIO * risk);
        } else {
            tpPrice = entry - (RR_RATIO * risk);
        }
        tpPrice = roundToTick(tpPrice, tickSize);

        double actualReward = Math.abs(tpPrice - entry);
        System.out.printf("  Final SL=%.6f | TP=%.6f | Risk=%.6f | Reward=%.6f | R:R=1:%.1f%n",
                slPrice, tpPrice, risk, actualReward, actualReward / risk);

        // ── Set TP/SL ─────────────────────────────────────────────────────────
        String posId = getPositionId(pair);
        if (posId != null) {
            setTpSl(posId, tpPrice, slPrice, pair);
        } else {
            System.out.println("  Position ID not found — TP/SL not set");
        }

        // ── Log trade ─────────────────────────────────────────────────────────
        log(pair, "TRADE_" + side.toUpperCase(),
                String.format("entry=%.6f,sl=%.6f,tp=%.6f,rr=%.1f,margin=%.2f,size=%.2f",
                        entry, slPrice, tpPrice, actualReward / risk, positionSize / LEVERAGE, positionSize),
                String.format("RSI=%.2f,MACDhist=%.6f,soft=%s", rsi, macdHist, softPassed.trim()));
    }

    // =========================================================================
    // NEW: Smart SL Computation with Liquidation Guard
    // =========================================================================

    /**
     * Compute stop loss using:
     * 1. Swing extreme + adaptive ATR buffer (0.6x ATR)
     * 2. Clamp between min breathing room and max risk cap
     * 3. Ensure it doesn't exceed liquidation point (with safety buffer)
     */
    private static double computeSmartStopLoss(String side, double entry, 
                                                double[] hi, double[] lo, 
                                                double atr, int leverage) {
        // Liquidation price: entry ± (LIQUIDATION_BUFFER_PCT * entry)
        // For long: liq = entry * (1 - (1/leverage - LIQUIDATION_BUFFER_PCT))
        // For short: liq = entry * (1 + (1/leverage - LIQUIDATION_BUFFER_PCT))
        double liqBuffer = (1.0 / leverage) + LIQUIDATION_CUSHION;
        double liquidPrice;
        
        if ("buy".equalsIgnoreCase(side)) {
            liquidPrice = entry * (1 - liqBuffer);
            
            // Swing low + buffer
            double swLow = swingLow(lo, SWING_BARS);
            double rawSL = swLow - (ADAPTIVE_SL_FACTOR * atr);
            
            // Min breathing room
            double minSL = entry - (SL_MIN_ATR * atr);
            
            // Max risk cap
            double maxSL = entry - (SL_MAX_ATR * atr);
            
            // Clamp to [maxSL, minSL] range
            double slPrice = Math.max(Math.min(rawSL, minSL), maxSL);
            
            // Guard: ensure SL stays above liquidation (with 0.5% extra buffer)
            double safeLiqPrice = liquidPrice * 1.005;
            if (slPrice < safeLiqPrice) {
                slPrice = safeLiqPrice;
            }
            
            return slPrice;
        } else {
            // SHORT
            liquidPrice = entry * (1 + liqBuffer);
            
            // Swing high + buffer
            double swHigh = swingHigh(hi, SWING_BARS);
            double rawSL = swHigh + (ADAPTIVE_SL_FACTOR * atr);
            
            // Min breathing room
            double minSL = entry + (SL_MIN_ATR * atr);
            
            // Max risk cap
            double maxSL = entry + (SL_MAX_ATR * atr);
            
            // Clamp to [minSL, maxSL] range
            double slPrice = Math.min(Math.max(rawSL, minSL), maxSL);
            
            // Guard: ensure SL stays below liquidation
            double safeLiqPrice = liquidPrice * 0.995;
            if (slPrice > safeLiqPrice) {
                slPrice = safeLiqPrice;
            }
            
            return slPrice;
        }
    }

    // =========================================================================
    // NEW: Margin-Based Position Sizing
    // =========================================================================

    /**
     * Size position based on max margin (80 INR) instead of risk %.
     * qty = (MAX_MARGIN_INR * LEVERAGE) / entry
     * This ensures margin never exceeds 80 INR.
     */
    private static double calcQuantityFromMargin(double entry, String pair) {
        // Max position size at this entry price
        double maxQty = (MAX_MARGIN_INR * LEVERAGE) / entry;
        
        if (maxQty <= 0) return 0;

        // Respect integer / 2-decimal precision for each pair
        if (INTEGER_QTY_PAIRS.contains(pair)) {
            return Math.floor(maxQty);
        }
        return Math.floor(maxQty * 100.0) / 100.0;
    }

    // =========================================================================
    // INDICATOR CALCULATIONS
    // =========================================================================

    /** Single scalar EMA value. */
    private static double calcEMA(double[] d, int period) {
        if (d.length < period) return 0;
        double k = 2.0 / (period + 1);
        double ema = 0;
        for (int i = 0; i < period; i++) ema += d[i];
        ema /= period;
        for (int i = period; i < d.length; i++) ema = d[i] * k + ema * (1 - k);
        return ema;
    }

    /** Full EMA series (same length as input; valid from index period-1). */
    private static double[] calcEMASeries(double[] d, int period) {
        double[] out = new double[d.length];
        if (d.length < period) return out;
        double k = 2.0 / (period + 1);
        double seed = 0;
        for (int i = 0; i < period; i++) seed += d[i];
        out[period - 1] = seed / period;
        for (int i = period; i < d.length; i++)
            out[i] = d[i] * k + out[i - 1] * (1 - k);
        return out;
    }

    /**
     * MACD with previous histogram for momentum check.
     * Returns [macdLine, signalLine, currentHist, prevHist]
     */
    private static double[] calcMACDWithPrev(double[] cl, int fast, int slow, int sig) {
        double[] ef    = calcEMASeries(cl, fast);
        double[] es    = calcEMASeries(cl, slow);
        int start = slow - 1;
        int len   = cl.length - start;
        if (len <= 1) return new double[]{0, 0, 0, 0};
        double[] ml = new double[len];
        for (int i = 0; i < len; i++) ml[i] = ef[start + i] - es[start + i];
        double[] ss = calcEMASeries(ml, sig);
        int last = ml.length - 1;
        double m     = ml[last];
        double mPrev = last > 0 ? ml[last - 1] : m;
        double s     = ss[last];
        double sPrev = last > 0 ? ss[last - 1] : s;
        return new double[]{m, s, m - s, mPrev - sPrev};
    }

    private static double calcRSI(double[] cl, int period) {
        if (cl.length < period + 1) return 50;
        double ag = 0, al = 0;
        for (int i = 1; i <= period; i++) {
            double ch = cl[i] - cl[i - 1];
            if (ch > 0) ag += ch; else al += Math.abs(ch);
        }
        ag /= period;
        al /= period;
        for (int i = period + 1; i < cl.length; i++) {
            double ch = cl[i] - cl[i - 1];
            if (ch > 0) {
                ag = (ag * (period - 1) + ch) / period;
                al = al * (period - 1) / period;
            } else {
                al = (al * (period - 1) + Math.abs(ch)) / period;
                ag = ag * (period - 1) / period;
            }
        }
        if (al == 0) return 100;
        return 100 - (100 / (1 + ag / al));
    }

    private static double calcATR(double[] hi, double[] lo, double[] cl, int period) {
        if (hi.length < period + 1) return 0;
        double[] tr = new double[hi.length];
        tr[0] = hi[0] - lo[0];
        for (int i = 1; i < hi.length; i++)
            tr[i] = Math.max(hi[i] - lo[i],
                    Math.max(Math.abs(hi[i] - cl[i - 1]),
                             Math.abs(lo[i] - cl[i - 1])));
        double atr = 0;
        for (int i = 0; i < period; i++) atr += tr[i];
        atr /= period;
        for (int i = period; i < hi.length; i++)
            atr = (atr * (period - 1) + tr[i]) / period;
        return atr;
    }

    private static double calcSMA(double[] d, int period) {
        if (d.length < period) return 0;
        double sum = 0;
        for (int i = d.length - period; i < d.length; i++) sum += d[i];
        return sum / period;
    }

    /** Swing low — excludes last 2 bars. */
    private static double swingLow(double[] lo, int bars) {
        int end   = lo.length - 2;
        int start = Math.max(0, end - bars);
        double min = Double.MAX_VALUE;
        for (int i = start; i < end; i++) min = Math.min(min, lo[i]);
        return min;
    }

    /** Swing high — excludes last 2 bars. */
    private static double swingHigh(double[] hi, int bars) {
        int end   = hi.length - 2;
        int start = Math.max(0, end - bars);
        double max = -Double.MAX_VALUE;
        for (int i = start; i < end; i++) max = Math.max(max, hi[i]);
        return max;
    }

    private static double roundToTick(double price, double tick) {
        if (tick <= 0) return price;
        return Math.round(price / tick) * tick;
    }

    // =========================================================================
    // OHLCV EXTRACTION
    // =========================================================================

    private static double[] extractCloses(JSONArray a) {
        double[] o = new double[a.length()];
        for (int i = 0; i < a.length(); i++) o[i] = a.getJSONObject(i).getDouble("close");
        return o;
    }

    private static double[] extractOpens(JSONArray a) {
        double[] o = new double[a.length()];
        for (int i = 0; i < a.length(); i++) o[i] = a.getJSONObject(i).getDouble("open");
        return o;
    }

    private static double[] extractHighs(JSONArray a) {
        double[] o = new double[a.length()];
        for (int i = 0; i < a.length(); i++) o[i] = a.getJSONObject(i).getDouble("high");
        return o;
    }

    private static double[] extractLows(JSONArray a) {
        double[] o = new double[a.length()];
        for (int i = 0; i < a.length(); i++) o[i] = a.getJSONObject(i).getDouble("low");
        return o;
    }

    private static double[] extractVolumes(JSONArray a) {
        try {
            if (a.length() == 0 || !a.getJSONObject(0).has("volume")) return null;
            double[] o = new double[a.length()];
            for (int i = 0; i < a.length(); i++) o[i] = a.getJSONObject(i).getDouble("volume");
            return o;
        } catch (Exception e) { return null; }
    }

    // =========================================================================
    // SCAN LOGGING
    // =========================================================================

    private static void initScanLog() {
        File f = new File(SCAN_LOG);
        if (!f.exists()) {
            try (PrintWriter pw = new PrintWriter(new FileWriter(f, true))) {
                pw.println("timestamp,pair,result,reason,details");
            } catch (IOException e) {
                System.err.println("Could not init scan log: " + e.getMessage());
            }
        }
    }

    private static synchronized void log(String pair, String result, String reason, String details) {
        try (PrintWriter pw = new PrintWriter(new FileWriter(SCAN_LOG, true))) {
            pw.printf("%s,%s,%s,%s,%s%n",
                    Instant.now().toString(), pair, result, reason, details);
        } catch (IOException e) {
            System.err.println("log write failed: " + e.getMessage());
        }
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    private static String baseCoin(String pair) {
        String s = pair.startsWith("B-") ? pair.substring(2) : pair;
        int idx = s.indexOf("_USDT");
        return idx >= 0 ? s.substring(0, idx) : s;
    }

    // =========================================================================
    // COINDCX API
    // =========================================================================

    private static JSONArray getCandlestickData(String pair, String resolution, int count) {
        try {
            long minsPerBar;
            switch (resolution) {
                case "1":   minsPerBar = 1;   break;
                case "5":   minsPerBar = 5;   break;
                case "15":  minsPerBar = 15;  break;
                case "60":  minsPerBar = 60;  break;
                case "240": minsPerBar = 240; break;
                default:    minsPerBar = 15;  break;
            }
            long to   = Instant.now().getEpochSecond();
            long from = to - minsPerBar * 60L * (count + 5);
            String url = PUBLIC_API_URL + "/market_data/candlesticks"
                    + "?pair=" + pair + "&from=" + from + "&to=" + to
                    + "&resolution=" + resolution + "&pcode=f";
            HttpURLConnection conn = openGet(url);
            int code = conn.getResponseCode();
            if (code == 200) {
                JSONObject r = new JSONObject(readStream(conn.getInputStream()));
                if ("ok".equals(r.optString("s"))) return r.getJSONArray("data");
                System.err.println("  Candle status=" + r.optString("s") + " for " + pair);
            } else {
                System.err.println("  Candle HTTP " + code + " for " + pair);
            }
        } catch (Exception e) {
            System.err.println("  getCandlestickData(" + pair + "," + resolution + "): " + e.getMessage());
        }
        return null;
    }

    private static void initInstrumentCache() {
        synchronized (cacheLock) {
            try {
                long now = System.currentTimeMillis();
                if (now - lastCacheUpdate < TICK_CACHE_TTL_MS) return;
                instrumentCache.clear();
                System.out.println("Refreshing instrument cache...");
                JSONArray pairs = new JSONArray(publicGet(
                        BASE_URL + "/exchange/v1/derivatives/futures/data/active_instruments"));
                for (int i = 0; i < pairs.length(); i++) {
                    String p = pairs.getString(i);
                    try {
                        String raw = publicGet(
                                BASE_URL + "/exchange/v1/derivatives/futures/data/instrument?pair=" + p);
                        instrumentCache.put(p, new JSONObject(raw).getJSONObject("instrument"));
                    } catch (Exception ignored) {}
                }
                lastCacheUpdate = now;
                System.out.println("Instruments cached: " + instrumentCache.size());
            } catch (Exception e) {
                System.err.println("initInstrumentCache: " + e.getMessage());
            }
        }
    }

    private static double getTickSize(String pair) {
        if (System.currentTimeMillis() - lastCacheUpdate > TICK_CACHE_TTL_MS) initInstrumentCache();
        JSONObject d = instrumentCache.get(pair);
        return d != null ? d.optDouble("price_increment", 0.0001) : 0.0001;
    }

    private static double getEntryPrice(String pair, String orderId) throws Exception {
        for (int i = 0; i < MAX_ENTRY_PRICE_CHECKS; i++) {
            TimeUnit.MILLISECONDS.sleep(ENTRY_CHECK_DELAY_MS);
            JSONObject pos = findPosition(pair);
            if (pos != null && pos.optDouble("avg_price", 0) > 0)
                return pos.getDouble("avg_price");
        }
        return 0;
    }

    private static JSONObject findPosition(String pair) throws Exception {
        JSONObject body = new JSONObject();
        body.put("timestamp", Instant.now().toEpochMilli());
        body.put("page", "1");
        body.put("size", "20");
        body.put("margin_currency_short_name", new String[]{"INR", "USDT"});
        String resp = authPost(
                BASE_URL + "/exchange/v1/derivatives/futures/positions", body.toString());
        JSONArray arr = resp.startsWith("[")
                ? new JSONArray(resp)
                : new JSONArray().put(new JSONObject(resp));
        for (int i = 0; i < arr.length(); i++) {
            JSONObject p = arr.getJSONObject(i);
            if (pair.equals(p.optString("pair"))) return p;
        }
        return null;
    }

    public static double getLastPrice(String pair) {
        try {
            HttpURLConnection conn = openGet(
                    PUBLIC_API_URL + "/market_data/trade_history?pair=" + pair + "&limit=1");
            if (conn.getResponseCode() == 200) {
                String r = readStream(conn.getInputStream());
                return r.startsWith("[")
                        ? new JSONArray(r).getJSONObject(0).getDouble("p")
                        : new JSONObject(r).getDouble("p");
            }
        } catch (Exception e) {
            System.err.println("getLastPrice(" + pair + "): " + e.getMessage());
        }
        return 0;
    }

    public static JSONObject placeFuturesMarketOrder(String side, String pair, double qty,
                                                     int lev, String notif,
                                                     String marginType, String marginCcy) {
        try {
            JSONObject order = new JSONObject();
            order.put("side",                       side.toLowerCase());
            order.put("pair",                       pair);
            order.put("order_type",                 "market_order");
            order.put("total_quantity",             qty);
            order.put("leverage",                   lev);
            order.put("notification",               notif);
            order.put("time_in_force",              "good_till_cancel");
            order.put("hidden",                     false);
            order.put("post_only",                  false);
            order.put("position_margin_type",       marginType);
            order.put("margin_currency_short_name", marginCcy);
            JSONObject body = new JSONObject();
            body.put("timestamp", Instant.now().toEpochMilli());
            body.put("order", order);
            String resp = authPost(
                    BASE_URL + "/exchange/v1/derivatives/futures/orders/create", body.toString());
            return resp.startsWith("[")
                    ? new JSONArray(resp).getJSONObject(0)
                    : new JSONObject(resp);
        } catch (Exception e) {
            System.err.println("placeFuturesMarketOrder: " + e.getMessage());
            return null;
        }
    }

    public static void setTpSl(String posId, double tp, double sl, String pair) {
        try {
            double tick = getTickSize(pair);
            double rtp  = roundToTick(tp, tick);
            double rsl  = roundToTick(sl, tick);

            JSONObject tpObj = new JSONObject();
            tpObj.put("stop_price",  rtp);
            tpObj.put("limit_price", rtp);
            tpObj.put("order_type",  "take_profit_market");

            JSONObject slObj = new JSONObject();
            slObj.put("stop_price",  rsl);
            slObj.put("limit_price", rsl);
            slObj.put("order_type",  "stop_market");

            JSONObject payload = new JSONObject();
            payload.put("timestamp",   Instant.now().toEpochMilli());
            payload.put("id",          posId);
            payload.put("take_profit", tpObj);
            payload.put("stop_loss",   slObj);

            String resp = authPost(
                    BASE_URL + "/exchange/v1/derivatives/futures/positions/create_tpsl",
                    payload.toString());
            JSONObject r = new JSONObject(resp);
            System.out.println(r.has("err_code_dcx")
                    ? "  TP/SL error: " + r
                    : "  TP/SL set successfully!");
        } catch (Exception e) {
            System.err.println("setTpSl: " + e.getMessage());
        }
    }

    public static String getPositionId(String pair) {
        try {
            JSONObject p = findPosition(pair);
            return p != null ? p.getString("id") : null;
        } catch (Exception e) {
            System.err.println("getPositionId: " + e.getMessage());
            return null;
        }
    }

    private static Set<String> getActivePositions() {
        Set<String> active = Collections.synchronizedSet(new HashSet<>());
        try {
            JSONObject body = new JSONObject();
            body.put("timestamp", Instant.now().toEpochMilli());
            body.put("page", "1");
            body.put("size", "100");
            body.put("margin_currency_short_name", new String[]{"INR", "USDT"});
            String resp = authPost(
                    BASE_URL + "/exchange/v1/derivatives/futures/positions", body.toString());
            JSONArray arr = resp.startsWith("[")
                    ? new JSONArray(resp)
                    : new JSONArray().put(new JSONObject(resp));
            System.out.println("=== Open Positions (" + arr.length() + ") ===");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject p   = arr.getJSONObject(i);
                String    pPair = p.optString("pair", "");
                boolean isActive = p.optDouble("active_pos", 0) > 0
                        || p.optDouble("locked_margin", 0) > 0
                        || p.optDouble("avg_price", 0) > 0
                        || p.optDouble("take_profit_trigger", 0) > 0
                        || p.optDouble("stop_loss_trigger", 0) > 0;
                if (isActive) {
                    System.out.printf("  %s | qty=%.4f | entry=%.6f | TP=%.6f | SL=%.6f%n",
                            pPair,
                            p.optDouble("active_pos", 0),
                            p.optDouble("avg_price", 0),
                            p.optDouble("take_profit_trigger", 0),
                            p.optDouble("stop_loss_trigger", 0));
                    active.add(pPair);
                }
            }
        } catch (Exception e) {
            System.err.println("getActivePositions: " + e.getMessage());
        }
        return active;
    }

    // =========================================================================
    // LOW-LEVEL HTTP + HMAC
    // =========================================================================

    private static HttpURLConnection openGet(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(10_000);
        c.setReadTimeout(10_000);
        return c;
    }

    private static String publicGet(String url) throws IOException {
        HttpURLConnection c = openGet(url);
        if (c.getResponseCode() == 200) return readStream(c.getInputStream());
        throw new IOException("HTTP " + c.getResponseCode() + " — " + url);
    }

    private static String authPost(String url, String json) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type",     "application/json");
        c.setRequestProperty("X-AUTH-APIKEY",    API_KEY);
        c.setRequestProperty("X-AUTH-SIGNATURE", sign(json));
        c.setConnectTimeout(10_000);
        c.setReadTimeout(10_000);
        c.setDoOutput(true);
        try (OutputStream os = c.getOutputStream()) {
            os.write(json.getBytes(StandardCharsets.UTF_8));
        }
        InputStream is = c.getResponseCode() >= 400
                ? c.getErrorStream()
                : c.getInputStream();
        return readStream(is);
    }

    private static String readStream(InputStream is) throws IOException {
        return new BufferedReader(new InputStreamReader(is))
                .lines().collect(Collectors.joining("\n"));
    }

    private static String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    API_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] b = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("HMAC sign failed", e);
        }
    }

    public static String generateHmacSHA256(String secret, String payload) {
        return sign(payload);
    }
}
