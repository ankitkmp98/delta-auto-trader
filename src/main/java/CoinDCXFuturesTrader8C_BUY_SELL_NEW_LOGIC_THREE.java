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
 * CoinDCX Futures Trader — Improved Signal Engine v2
 *
 * KEY IMPROVEMENTS OVER PREVIOUS VERSION:
 *
 *  [FIX-1]  Swing low/high now correctly excludes the last 2 forming bars.
 *  [FIX-2]  Risk-based position sizing: risk 1% of total capital per trade.
 *           Quantity is derived from SL distance, not a flat margin cap.
 *  [FIX-3]  Tighter SL (1.5–2.5x ATR) + higher R:R (1:2.5).
 *           Break-even win rate drops from 40% → 29%.
 *  [FIX-4]  MACD histogram momentum check: histogram must be INCREASING,
 *           not just positive. Filters entries where trend is already fading.
 *  [FIX-5]  4H EMA-21 middle-tier filter. All 3 TFs (1H/4H/15m) must agree.
 *  [FIX-6]  Daily loss circuit breaker: halts trading if day's PnL < -3%.
 *  [FIX-7]  Max concurrent positions cap (default 5).
 *  [FIX-8]  Correlation guard: max 2 trades in same sector (L1, DeFi, Meme).
 *  [FIX-9]  Volume confirmation: entry bar volume > 20-bar average.
 *  [FIX-10] Parallel coin scanning with ExecutorService (10 threads).
 *  [FIX-11] Scan result CSV logging for performance analysis.
 *  [FIX-12] MACD array length guard prevents wrong seeding on short data.
 *
 * SIGNAL ARCHITECTURE (unchanged 3-HARD + 2-SOFT, but each filter is stronger):
 *
 *   HARD:
 *     H1. 1H EMA-50   : macro trend (price above = bull, below = bear)
 *     H2. 4H EMA-21   : mid-tier trend (NEW — replaces nothing, adds a layer)
 *     H3. 15m EMA 9/21: local trend alignment
 *     H4. MACD hist   : bullish/bearish AND histogram building momentum
 *
 *   SOFT (1 of 2 required):
 *     S1. RSI zone     : Long 45-64 | Short 36-56
 *     S2. Candle body  : Previous closed candle matches direction
 *     S3. Volume spike : Entry bar volume > 1.1x 20-bar average (NEW)
 *
 *   SL/TP (3-bound system):
 *     rawSL  = swing extreme ± SWING_BUFFER × ATR (structural reference)
 *     minSL  = entry ± MIN_ATR × ATR              (breathing room floor)
 *     maxSL  = entry ± MAX_ATR × ATR              (risk cap ceiling)
 *     final  = clamp(rawSL, maxSL, minSL)
 *     TP     = entry ± RR × actualRisk            (1:2.5 R:R)
 */
public class CoinDCXFuturesTrader8C_BUY_SELL_NEW_LOGIC_THREE {

    // =========================================================================
    // Configuration — edit these to tune behaviour
    // =========================================================================
    private static final String API_KEY    = System.getenv("DELTA_API_KEY");
    private static final String API_SECRET = System.getenv("DELTA_API_SECRET");
    private static final String BASE_URL       = "https://api.coindcx.com";
    private static final String PUBLIC_API_URL = "https://public.coindcx.com";

    // Capital & risk
    private static final double TOTAL_CAPITAL   = 12_00.0; // your actual INR capital
    private static final double RISK_PCT        = 0.001;     // 0.1% of capital risked per trade
    private static final double MAX_DAILY_LOSS  = -0.060;    // -3% → circuit breaker
    private static final int    MAX_POSITIONS   = 800000;        // concurrent open positions cap
    private static final int    MAX_SECTOR_POS  = 50;        // max trades in same sector
    private static final int    LEVERAGE        = 10;

    private static final double MAX_MARGIN_INR = 120.0;
private static final double MAX_QTY       = 1200.0;

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

    // RSI zones
    private static final double RSI_LONG_MIN  = 45.0;
    private static final double RSI_LONG_MAX  = 64.0;
    private static final double RSI_SHORT_MIN = 36.0;
    private static final double RSI_SHORT_MAX = 56.0;

    // SL/TP — tightened for better R:R
    private static final double SL_SWING_BUFFER = 0.8;  // ATR buffer beyond swing extreme
    private static final double SL_MIN_ATR      = 1.5;  // minimum SL distance (breathing room)
    private static final double SL_MAX_ATR      = 2.5;  // maximum SL distance (risk cap)
    private static final double RR              = 2.5;  // reward:risk ratio (1:2.5)

    // Candle counts
    private static final int CANDLE_15M = 200;
    private static final int CANDLE_4H  = 60;
    private static final int CANDLE_1H  = 120;

    // Scan log file
    private static final String SCAN_LOG = "scan_log.csv";

    // =========================================================================
    // Caches
    // =========================================================================
    private static final Map<String, JSONObject> instrumentCache  = new ConcurrentHashMap<>();
    private static final Object                  cacheLock        = new Object();
    private static volatile long                 lastCacheUpdate  = 0;

    // Thread-safe sector counters for correlation guard
    private static final Map<String, Integer> sectorCount = new ConcurrentHashMap<>();

    // =========================================================================
    // Sector groupings for correlation guard [FIX-8]
    // =========================================================================
    private static final Map<String, String> SECTOR_MAP = new HashMap<>();
    static {
        // L1 blockchains
        for (String s : new String[]{"SOL","AVAX","NEAR","APT","SUI","ADA","ETH","BNB","TRX","TON","ALGO","ICP","EGLD","FTM"})
            SECTOR_MAP.put(s, "L1");
        // Meme coins
        for (String s : new String[]{"1000SHIB","1000BONK","WIF","BOME","DOGS","BRETT","1000PEPE","TRUMP","DOGE","1000FLOKI","MOODENG","PONKE","POPCAT","TURBO","FARTCOIN","GOAT","1000CHEEMS","1000CAT","1000LUNC","1000RATS","1000000MOG","1MBABYDOGE"})
            SECTOR_MAP.put(s, "MEME");
        // DeFi
        for (String s : new String[]{"UNI","AAVE","CRV","COMP","MKR","SNX","1INCH","SUSHI","YFI","CAKE","PENDLE","JUP","GMX","DYDX","RUNE","LDO","RPL","FXS","CVX","BAL"})
            SECTOR_MAP.put(s, "DEFI");
        // Layer 2 / Scaling
        for (String s : new String[]{"ARB","OP","MATIC","IMX","METIS","STRK","ZK","MANTA","SCROLL","LRC","SKL"})
            SECTOR_MAP.put(s, "L2");
        // Infrastructure / Storage
        for (String s : new String[]{"FIL","AR","STORJ","GRT","ANKR","API3","LINK","BAND","TRB"})
            SECTOR_MAP.put(s, "INFRA");
    }

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
        System.out.println("=== CoinDCX Futures Trader v2 — Improved ===");
        initScanLog();
        initInstrumentCache();

        // [FIX-6] Daily circuit breaker check
        double dailyPnl = getDailyPnl();
        double dailyPnlPct = dailyPnl / TOTAL_CAPITAL;
        System.out.printf("Daily PnL: %.2f INR (%.2f%%)%n", dailyPnl, dailyPnlPct * 100);
        if (dailyPnlPct < MAX_DAILY_LOSS) {
            System.out.printf("!!! CIRCUIT BREAKER: daily loss %.2f%% exceeds limit %.0f%% — halting all trading%n",
                    dailyPnlPct * 100, MAX_DAILY_LOSS * 100);
            return;
        }

        // Fetch active positions
        Set<String> active = getActivePositions();
        System.out.println("Active positions: " + active.size() + " → " + active);

        // [FIX-7] Max positions cap
        if (active.size() >= MAX_POSITIONS) {
            System.out.println("Max concurrent positions (" + MAX_POSITIONS + ") reached — halting scan");
            return;
        }

        // [FIX-8] Seed sector counters from existing positions
        for (String p : active) {
            String coin   = baseCoin(p);
            String sector = SECTOR_MAP.getOrDefault(coin, "OTHER");
            sectorCount.merge(sector, 1, Integer::sum);
        }

        // [FIX-10] Parallel scanning
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
    // CORE SCAN LOGIC (per pair)
    // =========================================================================
    private static void scanPair(String pair, Set<String> active) throws Exception {
        // Check position cap again inside thread (another thread may have opened one)
        synchronized (active) {
            if (active.size() >= MAX_POSITIONS) {
                log(pair, "SKIP", "max_positions", "");
                return;
            }
        }

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

        // [FIX-12] MACD data length guard
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

        // ── HARD FILTER H2: 4H Middle Tier [FIX-5] ───────────────────────────
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

        // ── HARD FILTER H3: 15m Local Trend ──────────────────────────────────
        double  ema9      = calcEMA(cl15, EMA_FAST);
        double  ema21     = calcEMA(cl15, EMA_MID);
        boolean localUp   = ema9 > ema21;
        boolean localDown = ema9 < ema21;
        System.out.printf("  [H3] EMA9=%.6f EMA21=%.6f → %s%n",
                ema9, ema21, localUp ? "UP" : localDown ? "DOWN" : "flat");

        boolean trendUp   = macroUp   && mid4hUp   && localUp;
        boolean trendDown = macroDown && mid4hDown && localDown;
        if (!trendUp && !trendDown) {
            System.out.println("  H3 FAIL — 3-TF alignment failed — skip");
            log(pair, "FAIL", "H3_local_misalign", "");
            return;
        }
        System.out.println("  H3 OK — all 3 timeframes aligned: " + (trendUp ? "BULLISH" : "BEARISH"));

        // ── HARD FILTER H4: MACD with histogram momentum [FIX-4] ─────────────
        double[] mv       = calcMACDWithPrev(cl15, MACD_FAST, MACD_SLOW, MACD_SIG);
        double   macdLine = mv[0], macdSigV = mv[1], macdHist = mv[2], prevHist = mv[3];
        System.out.printf("  [H4] MACD=%.6f Sig=%.6f Hist=%.6f PrevHist=%.6f%n",
                macdLine, macdSigV, macdHist, prevHist);

        // Bull: line above signal AND histogram growing (momentum building)
        boolean macdBull = macdLine > macdSigV && macdHist > prevHist;
        // Bear: line below signal AND histogram falling (momentum building short side)
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

        // ── SOFT FILTERS: at least 1 of 3 must pass ──────────────────────────
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

        // S3: Volume spike [FIX-9]
        boolean softVolume = false;
        if (vl15 != null && vl15.length > VOL_PERIOD + 1) {
            double avgVol = calcSMA(vl15, VOL_PERIOD);
            double entryVol = vl15[vl15.length - 2]; // confirmed closed bar
            softVolume = entryVol > avgVol * 1.1;
            System.out.printf("  [S3] Vol=%.2f AvgVol=%.2f → %s%n",
                    entryVol, avgVol, softVolume ? "PASS" : "fail");
        } else {
            System.out.println("  [S3] Volume data unavailable — skip soft filter");
        }

        if (!softRsi && !softCandle && !softVolume) {
            System.out.println("  SOFT FAIL — no confirming filter passed — skip");
            log(pair, "FAIL", "SOFT_none", String.format("RSI=%.2f", rsi));
            return;
        }

        String softPassed = (softRsi ? "RSI " : "") + (softCandle ? "Candle " : "") + (softVolume ? "Volume" : "");
        System.out.println("  SOFT OK — confirmed by: " + softPassed.trim());

        // ── [FIX-8] Correlation / sector guard ───────────────────────────────
        String coin   = baseCoin(pair);
        String sector = SECTOR_MAP.getOrDefault(coin, "OTHER");
        int currentSectorCount = sectorCount.getOrDefault(sector, 0);
        if (currentSectorCount >= MAX_SECTOR_POS) {
            System.out.println("  SECTOR GUARD: already " + currentSectorCount
                    + " positions in sector [" + sector + "] — skip " + pair);
            log(pair, "SKIP", "sector_limit_" + sector, "");
            return;
        }

        // ── All filters passed ────────────────────────────────────────────────
        String side = trendUp ? "buy" : "sell";
        System.out.println("  >>> ALL FILTERS PASSED → " + side.toUpperCase() + " " + pair);

        // ── Get current price ─────────────────────────────────────────────────
        double currentPrice = getLastPrice(pair);
        if (currentPrice <= 0) {
            System.out.println("  Invalid price — skip");
            return;
        }

        // ── Compute SL first so we can size position correctly [FIX-2] ────────
        double slEstimate;
        if ("buy".equalsIgnoreCase(side)) {
            double swLow = swingLow(lo15, SWING_BARS);
            double rawSL = swLow  - SL_SWING_BUFFER * atr;
            double minSL = currentPrice - SL_MIN_ATR * atr;
            double maxSL = currentPrice - SL_MAX_ATR * atr;
            slEstimate   = Math.max(Math.min(rawSL, minSL), maxSL);
        } else {
            double swHigh = swingHigh(hi15, SWING_BARS);
            double rawSL  = swHigh + SL_SWING_BUFFER * atr;
            double minSL  = currentPrice + SL_MIN_ATR * atr;
            double maxSL  = currentPrice + SL_MAX_ATR * atr;
            slEstimate    = Math.min(Math.max(rawSL, minSL), maxSL);
        }
        slEstimate = roundToTick(slEstimate, tickSize);

        // Risk-based quantity [FIX-2]
        double qty = calcQuantityFromRisk(currentPrice, slEstimate, pair);
        if (qty <= 0) {
            System.out.println("  Invalid qty from risk calc — skip");
            return;
        }

        System.out.printf("  Placing %s | price=%.6f | qty=%.4f | lev=%dx | riskAmt=%.2f INR%n",
                side.toUpperCase(), currentPrice, qty, LEVERAGE,
                Math.abs(currentPrice - slEstimate) * qty);

        // ── Place order ───────────────────────────────────────────────────────
        JSONObject resp = placeFuturesMarketOrder(side, pair, qty, LEVERAGE,
                "email_notification", "isolated", "INR");

        if (resp == null || !resp.has("id")) {
            System.out.println("  Order failed: " + resp);
            return;
        }
        System.out.println("  Order placed! id=" + resp.getString("id"));

        // Update sector counter after successful order
        sectorCount.merge(sector, 1, Integer::sum);
        synchronized (active) { active.add(pair); }

        // ── Confirm entry price ───────────────────────────────────────────────
        double entry = getEntryPrice(pair, resp.getString("id"));
        if (entry <= 0) {
            System.out.println("  Could not confirm entry — using estimated price for TP/SL");
            entry = currentPrice;
        }
        System.out.printf("  Entry confirmed: %.6f%n", entry);

        // ── Recompute SL/TP from actual entry [FIX-1] ────────────────────────
        double slPrice, tpPrice;
        if ("buy".equalsIgnoreCase(side)) {
            double swLow = swingLow(lo15, SWING_BARS);       // [FIX-1] excludes last 2 bars
            double rawSL = swLow  - SL_SWING_BUFFER * atr;
            double minSL = entry  - SL_MIN_ATR * atr;
            double maxSL = entry  - SL_MAX_ATR * atr;
            slPrice = Math.max(Math.min(rawSL, minSL), maxSL);
            double risk = entry - slPrice;
            tpPrice = entry + RR * risk;
        } else {
            double swHigh = swingHigh(hi15, SWING_BARS);     // [FIX-1] excludes last 2 bars
            double rawSL  = swHigh + SL_SWING_BUFFER * atr;
            double minSL  = entry  + SL_MIN_ATR * atr;
            double maxSL  = entry  + SL_MAX_ATR * atr;
            slPrice = Math.min(Math.max(rawSL, minSL), maxSL);
            double risk = slPrice - entry;
            tpPrice = entry - RR * risk;
        }

        slPrice = roundToTick(slPrice, tickSize);
        tpPrice = roundToTick(tpPrice, tickSize);

        double actualRisk   = Math.abs(entry - slPrice);
        double actualReward = Math.abs(tpPrice - entry);
        System.out.printf("  SL=%.6f | TP=%.6f | Risk=%.6f | Reward=%.6f | R:R=1:%.1f%n",
                slPrice, tpPrice, actualRisk, actualReward, actualReward / actualRisk);

        // ── Set TP/SL ─────────────────────────────────────────────────────────
        String posId = getPositionId(pair);
        if (posId != null) {
            setTpSl(posId, tpPrice, slPrice, pair);
        } else {
            System.out.println("  Position ID not found — TP/SL not set");
        }

        // ── Log trade ─────────────────────────────────────────────────────────
        log(pair, "TRADE_" + side.toUpperCase(),
                String.format("entry=%.6f,sl=%.6f,tp=%.6f,rr=%.1f,soft=%s",
                        entry, slPrice, tpPrice, actualReward / actualRisk, softPassed.trim()),
                String.format("RSI=%.2f,MACDhist=%.6f", rsi, macdHist));
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
     * MACD with previous histogram for momentum check [FIX-4].
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

    /**
     * Swing low — [FIX-1] excludes the last 2 bars (forming / just-closed).
     * Looks at bars [length-2-SWING_BARS .. length-2].
     */
    private static double swingLow(double[] lo, int bars) {
        int end   = lo.length - 2;                  // exclude current + last forming bar
        int start = Math.max(0, end - bars);
        double min = Double.MAX_VALUE;
        for (int i = start; i < end; i++) min = Math.min(min, lo[i]);
        return min;
    }

    /**
     * Swing high — [FIX-1] excludes the last 2 bars.
     */
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

    /** Returns null-safe volume array; returns null if volume field is absent. */
    private static double[] extractVolumes(JSONArray a) {
        try {
            if (a.length() == 0 || !a.getJSONObject(0).has("volume")) return null;
            double[] o = new double[a.length()];
            for (int i = 0; i < a.length(); i++) o[i] = a.getJSONObject(i).getDouble("volume");
            return o;
        } catch (Exception e) { return null; }
    }

    // =========================================================================
    // POSITION SIZING [FIX-2]
    // =========================================================================

    /**
     * Risk-based sizing: risks exactly RISK_PCT of TOTAL_CAPITAL per trade.
     * qty = riskAmt / slDistancePerUnit
     * The leverage is applied by the exchange — we just size the contract qty.
     */
    // private static double calcQuantityFromRisk(double entry, double slPrice, String pair) {
    //     double riskAmt = TOTAL_CAPITAL * RISK_PCT;
    //     double slDist  = Math.abs(entry - slPrice);
    //     if (slDist <= 0) return 0;
    //     double rawQty = riskAmt / slDist;
    //     if (INTEGER_QTY_PAIRS.contains(pair)) return Math.floor(rawQty);
    //     return Math.floor(rawQty * 100.0) / 100.0;
    // }

    private static double calcQuantityFromRisk(double entry, double slPrice, String pair) {
    double riskAmt = TOTAL_CAPITAL * RISK_PCT;    // existing risk model
    double slDist  = Math.abs(entry - slPrice);
    if (slDist <= 0) return 0;

    // 1) Risk-based qty (max loss = riskAmt)
    double qtyFromRisk = riskAmt / slDist;

    // 2) Size cap
    double qtyFromSizeCap = MAX_QTY;

    // 3) Margin cap: margin ≈ entry * qty / LEVERAGE <= MAX_MARGIN_INR
    double qtyFromMarginCap = (MAX_MARGIN_INR * LEVERAGE) / entry;

    // Final cap
    double rawQty = Math.min(qtyFromRisk, Math.min(qtyFromSizeCap, qtyFromMarginCap));

    if (rawQty <= 0) return 0;

    // Respect integer / 2-decimal precision for each pair
    if (INTEGER_QTY_PAIRS.contains(pair)) return Math.floor(rawQty);
    return Math.floor(rawQty * 100.0) / 100.0;
}

    // =========================================================================
    // DAILY PNL — Circuit Breaker [FIX-6]
    // =========================================================================

    /**
     * Fetches today's realised PnL from closed positions.
     * Returns 0.0 on any error (conservative — won't falsely trip the breaker).
     */
    private static double getDailyPnl() {
        try {
            JSONObject body = new JSONObject();
            body.put("timestamp", Instant.now().toEpochMilli());
            body.put("page",  "1");
            body.put("size",  "50");
            body.put("margin_currency_short_name", new String[]{"INR", "USDT"});
            String resp = authPost(
                    BASE_URL + "/exchange/v1/derivatives/futures/positions/closed", body.toString());
            JSONArray arr = resp.startsWith("[")
                    ? new JSONArray(resp)
                    : new JSONArray().put(new JSONObject(resp));
            String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
            double pnl = 0;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject p = arr.getJSONObject(i);
                String closedAt = p.optString("closed_at", "");
                if (closedAt.startsWith(today)) {
                    pnl += p.optDouble("realized_pnl", 0);
                }
            }
            return pnl;
        } catch (Exception e) {
            System.err.println("getDailyPnl: " + e.getMessage());
            return 0;
        }
    }

    // =========================================================================
    // SCAN LOGGING [FIX-11]
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

    /** Extracts base coin from pair like "B-SOL_USDT" → "SOL". */
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
            long from = to - minsPerBar * 60L * (count + 5); // +5 buffer for gaps
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
























// import org.json.JSONArray;
// import org.json.JSONObject;

// import javax.crypto.Mac;
// import javax.crypto.spec.SecretKeySpec;
// import java.io.*;
// import java.net.HttpURLConnection;
// import java.net.URL;
// import java.nio.charset.StandardCharsets;
// import java.time.Instant;
// import java.util.*;
// import java.util.concurrent.ConcurrentHashMap;
// import java.util.concurrent.TimeUnit;
// import java.util.stream.Collectors;
// import java.util.stream.Stream;

// /**
//  * CoinDCX Futures Trader — Balanced Signal Engine
//  *
//  * DESIGN PHILOSOPHY:
//  *   Previous versions either placed too many bad trades (too loose) or no trades at all
//  *   (too strict). This version uses a 3-HARD + 2-SOFT architecture:
//  *
//  *   HARD (all 3 must pass — quality baseline):
//  *     H1. 1H EMA-50 Macro  : Price above = bull, below = bear
//  *     H2. 15m EMA 9 vs 21  : EMA-9 > EMA-21 = bull, < = bear (trend direction)
//  *     H3. MACD alignment   : Line above signal = bull, below = bear
//  *
//  *   SOFT (at least 1 of 2 must pass — quality boost without over-filtering):
//  *     S1. RSI zone         : Long 40-68 | Short 32-60  (excludes extremes)
//  *     S2. Candle body      : Previous closed candle matches direction
//  *
//  *   SL/TP:
//  *     Long  SL = max(swing low - 0.5xATR,  EMA21 - 1.5xATR) capped at 2.5xATR
//  *     Short SL = min(swing high + 0.5xATR, EMA21 + 1.5xATR) capped at 2.5xATR
//  *     TP       = entry +/- 2.0 x actual risk  (1:2 R:R — profitable at 34% win rate)
//  */
// public class CoinDCXFuturesTrader8C_BUY_SELL_NEW_LOGIC_THREE {

//     // =========================================================================
//     // Configuration
//     // =========================================================================
//     private static final String API_KEY    = System.getenv("DELTA_API_KEY");
//     private static final String API_SECRET = System.getenv("DELTA_API_SECRET");
//     private static final String BASE_URL       = "https://api.coindcx.com";
//     private static final String PUBLIC_API_URL = "https://public.coindcx.com";

//     private static final double MAX_MARGIN             = 1200.0;
//     private static final int    LEVERAGE               = 10;
//     private static final int    MAX_ENTRY_PRICE_CHECKS = 15;
//     private static final int    ENTRY_CHECK_DELAY_MS   = 1500;
//     private static final long   TICK_CACHE_TTL_MS      = 3_600_000L;

//     // Indicator parameters
//     private static final int EMA_FAST   = 9;
//     private static final int EMA_MID    = 21;
//     private static final int EMA_MACRO  = 50;
//     private static final int MACD_FAST  = 12;
//     private static final int MACD_SLOW  = 26;
//     private static final int MACD_SIG   = 9;
//     private static final int RSI_PERIOD = 14;
//     private static final int ATR_PERIOD = 14;
//     private static final int SWING_BARS = 15;

//     // RSI — wide enough to fire, tight enough to avoid extremes
//     private static final double RSI_LONG_MIN  = 45.0;
//     private static final double RSI_LONG_MAX  = 64.0;
//     private static final double RSI_SHORT_MIN = 36.0;
//     private static final double RSI_SHORT_MAX = 56.0;

//     // SL parameters — 3-bound system (structure, minimum breathing room, maximum risk)
//     private static final double SL_SWING_BUFFER = 0.8;   // ATR buffer beyond swing low/high (structural)
//     private static final double SL_MIN_ATR      = 1.5;   // MINIMUM distance from entry (breathing room)
//     private static final double SL_MAX_ATR      = 2.5;   // MAXIMUM distance from entry (risk cap)
//     private static final double RR              = 2.5;   // 1:5 R:R

//     private static final int CANDLE_15M = 200;
//     private static final int CANDLE_1H  = 120;

//     private static final Map<String, JSONObject> instrumentCache = new ConcurrentHashMap<>();
//     private static long lastCacheUpdate = 0;

//     // =========================================================================
//     // Coin list
//     // =========================================================================
//     private static final String[] COIN_SYMBOLS = {
//         "SOL", "1000SHIB", "API3", "DOGS", "KAVA", "ARK", "VOXEL", "SXP", "FLOW", "CHESS",
//         "AXL", "MANA", "BNB", "1000BONK", "ALPHA", "NEAR", "TRB", "GRT", "WIF", "RSR",
//         "QTUM", "AVAX", "BIGTIME", "COTI", "PONKE", "ETHFI", "ICP", "VET", "ACH", "MINA",
//         "COMP", "XAI", "JTO", "USTC", "SPELL", "KNC", "INJ", "BLUR", "DYM", "SNX",
//         "IMX", "1000WHY", "ALGO", "CRV", "JUP", "ZEN", "BAT", "SAGA", "AAVE", "SEI",
//         "KAIA", "NFP", "PEOPLE", "SUI", "ONE", "RENDER", "POLYX", "ENS", "MOVR", "BRETT",
//         "ETH", "OMNI", "MKR", "AR", "CFX", "ID", "CELR", "LDO", "UNI", "LTC",
//         "TAO", "CKB", "FET", "STX", "SAND", "XLM", "EGLD", "BOME", "HOT", "LUNA2",
//         "ADA", "RVN", "GLM", "MASK", "STRK", "GALA", "YFI", "IOST", "OP",
//         "1000PEPE", "TRUMP", "ZIL", "RPL", "WLD", "DOGE", "XMR", "ONDO", "APT", "HIVE",
//         "FIL", "TIA", "CHZ", "ETC", "LINK", "ORDI", "ATOM", "TON", "TRX", "HBAR",
//         "NEO", "IOTA", "GMX", "QNT", "FTM", "VANA", "FLUX", "DASH", "ZRX", "MANTA",
//         "CAKE", "PYTH", "ARB", "SFP", "METIS", "LRC", "SKL", "ZEC", "RUNE", "ALICE",
//         "ANKR", "XTZ", "GTC", "ROSE", "BCH", "CELO", "BAND", "1INCH", "SUPER", "ILV",
//         "SSV", "ARPA", "FXS", "UMA", "MTL", "DEGEN", "XVS", "ACE", "1000FLOKI", "AKT",
//         "ASTR", "TWT", "CTSI", "VIRTUAL", "CHR", "EDU", "PROM", "KSM", "BICO", "DENT",
//         "ALT", "C98", "RLC", "SUN", "PENDLE", "BANANA", "NMR", "POL", "MAGIC",
//         "MOODENG", "WAXP", "ZK", "GAS", "ALPACA", "TNSR", "PHB", "POWR", "LSK", "FIO",
//         "DEFI", "KAS", "1000SATS", "ARKM", "PIXEL", "MAV", "REI", "ZRO", "COOKIE",
//         "JOE", "BNT", "CYBER", "SCRT", "XRP", "VELODROME", "ONG", "AERO", "HOOK", "AI16Z",
//         "KMNO", "LPT", "THETA", "NTRN", "VIC", "RAYSOL", "PARTI", "MELANIA", "MEW", "EIGEN",
//         "XVG", "MYRO", "IO", "SHELL", "AUCTION", "STORJ", "SWELL", "COS", "FORTH", "BEL",
//         "PNUT", "HIGH", "ENJ", "LISTA", "ZETA", "MORPHO", "WOO", "MLN", "COW", "HEI",
//         "DEXE", "OM", "RED", "GHST", "STEEM", "LOKA", "ACT", "KAITO", "DIA", "SUSHI",
//         "AGLD", "TLM", "BMT", "MAVIA", "ALCH", "VTHO", "FUN", "POPCAT", "TURBO", "1000CHEEMS",
//         "1000CAT", "1000LUNC", "1000RATS", "1000000MOG", "1MBABYDOGE", "1000XEC", "1000X",
//         "BTCDOM", "USUAL", "PERP", "LAYER", "NKN", "MUBARAK", "FARTCOIN", "GOAT", "LEVER",
//         "SOLV", "S", "ARC", "VINE", "RARE", "GPS", "IP", "AVAAI", "KOMA", "HFT"
//     };

//     private static final Set<String> INTEGER_QTY_PAIRS = Stream.of(COIN_SYMBOLS)
//             .flatMap(s -> Stream.of("B-" + s + "_USDT", s + "_USDT"))
//             .collect(Collectors.toCollection(HashSet::new));

//     private static final String[] COINS_TO_TRADE = Stream.of(COIN_SYMBOLS)
//             .map(s -> "B-" + s + "_USDT")
//             .toArray(String[]::new);

//     // =========================================================================
//     // MAIN
//     // =========================================================================
//     public static void main(String[] args) {
//         initInstrumentCache();
//         Set<String> active = getActivePositions();
//         System.out.println("Active positions: " + active);

//         for (String pair : COINS_TO_TRADE) {
//             try {
//                 if (active.contains(pair)) {
//                     System.out.println("Skip " + pair + " — active position");
//                     continue;
//                 }

//                 System.out.println("\n==== " + pair + " ====");

//                 // ── Fetch candles ─────────────────────────────────────────────
//                 JSONArray raw15m = getCandlestickData(pair, "15", CANDLE_15M);
//                 JSONArray raw1h  = getCandlestickData(pair, "60", CANDLE_1H);

//                 if (raw15m == null || raw15m.length() < 60) {
//                     System.out.println("  Insufficient 15m candles (" +
//                             (raw15m == null ? 0 : raw15m.length()) + ") — skip");
//                     continue;
//                 }
//                 if (raw1h == null || raw1h.length() < EMA_MACRO) {
//                     System.out.println("  Insufficient 1H candles (" +
//                             (raw1h == null ? 0 : raw1h.length()) + ") — skip");
//                     continue;
//                 }

//                 double[] cl15 = extractCloses(raw15m);
//                 double[] op15 = extractOpens(raw15m);
//                 double[] hi15 = extractHighs(raw15m);
//                 double[] lo15 = extractLows(raw15m);
//                 double[] cl1h = extractCloses(raw1h);

//                 double lastClose = cl15[cl15.length - 1];
//                 double prevClose = cl15[cl15.length - 2];
//                 double prevOpen  = op15[op15.length - 2];
//                 double tickSize  = getTickSize(pair);
//                 double atr       = calcATR(hi15, lo15, cl15, ATR_PERIOD);

//                 System.out.printf("  Price=%.6f  ATR=%.6f  Tick=%.8f%n", lastClose, atr, tickSize);

//                 // ── HARD FILTER H1: 1H Macro Trend ───────────────────────────
//                 double  ema1h     = calcEMA(cl1h, EMA_MACRO);
//                 boolean macroUp   = lastClose > ema1h;
//                 boolean macroDown = lastClose < ema1h;
//                 System.out.printf("  [H1] 1H EMA50=%.6f | Price %s EMA -> %s%n",
//                         ema1h, macroUp ? ">" : "<", macroUp ? "BULL" : "BEAR");
//                 if (!macroUp && !macroDown) { System.out.println("  H1 FAIL — skip"); continue; }

//                 // ── HARD FILTER H2: 15m Local Trend ──────────────────────────
//                 double  ema9      = calcEMA(cl15, EMA_FAST);
//                 double  ema21     = calcEMA(cl15, EMA_MID);
//                 boolean localUp   = ema9 > ema21;
//                 boolean localDown = ema9 < ema21;
//                 System.out.printf("  [H2] EMA9=%.6f EMA21=%.6f -> %s%n",
//                         ema9, ema21, localUp ? "UP" : localDown ? "DOWN" : "flat");

//                 boolean trendUp   = macroUp   && localUp;
//                 boolean trendDown = macroDown && localDown;
//                 if (!trendUp && !trendDown) {
//                     System.out.println("  H2 FAIL — macro/local misaligned — skip");
//                     continue;
//                 }
//                 System.out.println("  H2 OK — " + (trendUp ? "BULLISH" : "BEARISH"));

//                 // ── HARD FILTER H3: MACD direction ───────────────────────────
//                 double[] mv       = calcMACD(cl15, MACD_FAST, MACD_SLOW, MACD_SIG);
//                 double   macdLine = mv[0], macdSigV = mv[1], macdHist = mv[2];
//                 System.out.printf("  [H3] MACD=%.6f Sig=%.6f Hist=%.6f%n",
//                         macdLine, macdSigV, macdHist);
//                 boolean macdBull = macdLine > macdSigV;
//                 boolean macdBear = macdLine < macdSigV;
//                 if (trendUp   && !macdBull) { System.out.println("  H3 FAIL — MACD bearish — skip"); continue; }
//                 if (trendDown && !macdBear) { System.out.println("  H3 FAIL — MACD bullish — skip"); continue; }
//                 System.out.println("  H3 OK — MACD aligned");

//                 // ── SOFT FILTERS: at least 1 of 2 must pass ──────────────────
//                 double  rsi        = calcRSI(cl15, RSI_PERIOD);
//                 boolean rsiOkLong  = trendUp   && rsi >= RSI_LONG_MIN  && rsi <= RSI_LONG_MAX;
//                 boolean rsiOkShort = trendDown && rsi >= RSI_SHORT_MIN && rsi <= RSI_SHORT_MAX;
//                 boolean softRsi    = rsiOkLong || rsiOkShort;
//                 System.out.printf("  [S1] RSI=%.2f (Long:%.0f-%.0f | Short:%.0f-%.0f) -> %s%n",
//                         rsi,
//                         RSI_LONG_MIN, RSI_LONG_MAX,
//                         RSI_SHORT_MIN, RSI_SHORT_MAX,
//                         softRsi ? "PASS" : "fail");

//                 boolean prevBull   = prevClose > prevOpen;
//                 boolean prevBear   = prevClose < prevOpen;
//                 boolean softCandle = (trendUp && prevBull) || (trendDown && prevBear);
//                 System.out.printf("  [S2] Prev candle: open=%.6f close=%.6f -> %s -> %s%n",
//                         prevOpen, prevClose,
//                         prevBull ? "BULL" : prevBear ? "BEAR" : "DOJI",
//                         softCandle ? "PASS" : "fail");

//                 if (!softRsi && !softCandle) {
//                     System.out.println("  SOFT FAIL — neither RSI nor candle confirms — skip");
//                     continue;
//                 }
//                 System.out.println("  SOFT OK — " + (softRsi ? "RSI" : "") +
//                         (softRsi && softCandle ? " + " : "") +
//                         (softCandle ? "Candle" : "") + " confirmed");

//                 // ── All filters passed ────────────────────────────────────────
//                 String side = trendUp ? "buy" : "sell";
//                 System.out.println("  >>> ALL FILTERS PASSED -> " + side.toUpperCase() + " " + pair);

//                 // ── Place order ───────────────────────────────────────────────
//                 double currentPrice = getLastPrice(pair);
//                 if (currentPrice <= 0) { System.out.println("  Invalid price — skip"); continue; }

//                 double qty = calcQuantity(currentPrice, pair);
//                 if (qty <= 0) { System.out.println("  Invalid qty — skip"); continue; }

//                 System.out.printf("  Placing %s | price=%.6f | qty=%.4f | lev=%dx%n",
//                         side.toUpperCase(), currentPrice, qty, LEVERAGE);

//                 JSONObject resp = placeFuturesMarketOrder(side, pair, qty, LEVERAGE,
//                         "email_notification", "isolated", "INR");

//                 if (resp == null || !resp.has("id")) {
//                     System.out.println("  Order failed: " + resp);
//                     continue;
//                 }
//                 System.out.println("  Order placed! id=" + resp.getString("id"));

//                 // ── Confirm entry price ───────────────────────────────────────
//                 double entry = getEntryPrice(pair, resp.getString("id"));
//                 if (entry <= 0) {
//                     System.out.println("  Could not confirm entry — TP/SL skipped");
//                     continue;
//                 }
//                 System.out.printf("  Entry confirmed: %.6f%n", entry);

//                 // ── SL: 3-bound system ──────────────────────────────────────
//                 //
//                 //   LONG:
//                 //     rawSL = swing low - 1.0×ATR          (structural reference)
//                 //     minSL = entry - 1.5×ATR              (must be AT LEAST this far = breathing room)
//                 //     maxSL = entry - 3.0×ATR              (can't be MORE than this far = risk cap)
//                 //     final = clamp rawSL between maxSL and minSL
//                 //
//                 //   SHORT: mirror logic (SL above entry)
//                 //
//                 double slPrice, tpPrice;
//                 if ("buy".equalsIgnoreCase(side)) {
//                     double swLow  = swingLow(lo15, SWING_BARS);
//                     double rawSL  = swLow  - SL_SWING_BUFFER * atr;   // 1.0×ATR below swing low
//                     double minSL  = entry  - SL_MIN_ATR * atr;        // never closer than 1.5×ATR
//                     double maxSL  = entry  - SL_MAX_ATR * atr;        // never farther than 3.0×ATR
//                     slPrice = Math.max(Math.min(rawSL, minSL), maxSL);
//                     double risk = entry - slPrice;
//                     tpPrice = entry + RR * risk;
//                 } else {
//                     double swHigh = swingHigh(hi15, SWING_BARS);
//                     double rawSL  = swHigh + SL_SWING_BUFFER * atr;   // 1.0×ATR above swing high
//                     double minSL  = entry  + SL_MIN_ATR * atr;        // never closer than 1.5×ATR
//                     double maxSL  = entry  + SL_MAX_ATR * atr;        // never farther than 3.0×ATR
//                     slPrice = Math.min(Math.max(rawSL, minSL), maxSL);
//                     double risk = slPrice - entry;
//                     tpPrice = entry - RR * risk;
//                 }

//                 slPrice = roundToTick(slPrice, tickSize);
//                 tpPrice = roundToTick(tpPrice, tickSize);

//                 System.out.printf("  SL=%.6f | TP=%.6f | Risk=%.6f | Reward=%.6f | R:R=1:%.1f%n",
//                         slPrice, tpPrice,
//                         Math.abs(entry - slPrice),
//                         Math.abs(tpPrice - entry),
//                         RR);

//                 // ── Set TP/SL ─────────────────────────────────────────────────
//                 String posId = getPositionId(pair);
//                 if (posId != null) {
//                     setTpSl(posId, tpPrice, slPrice, pair);
//                 } else {
//                     System.out.println("  Position ID not found — TP/SL not set");
//                 }

//             } catch (Exception e) {
//                 System.err.println("Error on " + pair + ": " + e.getMessage());
//             }
//         }
//         System.out.println("\n=== Scan complete ===");
//     }

//     // =========================================================================
//     // INDICATOR CALCULATIONS
//     // =========================================================================

//     private static double calcEMA(double[] d, int period) {
//         if (d.length < period) return 0;
//         double k = 2.0 / (period + 1), ema = 0;
//         for (int i = 0; i < period; i++) ema += d[i];
//         ema /= period;
//         for (int i = period; i < d.length; i++) ema = d[i] * k + ema * (1 - k);
//         return ema;
//     }

//     private static double[] calcEMASeries(double[] d, int period) {
//         double[] out = new double[d.length];
//         if (d.length < period) return out;
//         double k = 2.0 / (period + 1), seed = 0;
//         for (int i = 0; i < period; i++) seed += d[i];
//         out[period - 1] = seed / period;
//         for (int i = period; i < d.length; i++) out[i] = d[i] * k + out[i - 1] * (1 - k);
//         return out;
//     }

//     private static double[] calcMACD(double[] cl, int fast, int slow, int sig) {
//         double[] ef = calcEMASeries(cl, fast);
//         double[] es = calcEMASeries(cl, slow);
//         int start = slow - 1;
//         int len   = cl.length - start;
//         if (len <= 0) return new double[]{0, 0, 0};
//         double[] ml = new double[len];
//         for (int i = 0; i < len; i++) ml[i] = ef[start + i] - es[start + i];
//         double[] ss = calcEMASeries(ml, sig);
//         double m = ml[ml.length - 1];
//         double s = ss[ss.length - 1];
//         return new double[]{m, s, m - s};
//     }

//     private static double calcRSI(double[] cl, int period) {
//         if (cl.length < period + 1) return 50;
//         double ag = 0, al = 0;
//         for (int i = 1; i <= period; i++) {
//             double ch = cl[i] - cl[i - 1];
//             if (ch > 0) ag += ch; else al += Math.abs(ch);
//         }
//         ag /= period; al /= period;
//         for (int i = period + 1; i < cl.length; i++) {
//             double ch = cl[i] - cl[i - 1];
//             if (ch > 0) {
//                 ag = (ag * (period - 1) + ch) / period;
//                 al =  al * (period - 1) / period;
//             } else {
//                 al = (al * (period - 1) + Math.abs(ch)) / period;
//                 ag =  ag * (period - 1) / period;
//             }
//         }
//         if (al == 0) return 100;
//         return 100 - (100 / (1 + ag / al));
//     }

//     private static double calcATR(double[] hi, double[] lo, double[] cl, int period) {
//         if (hi.length < period + 1) return 0;
//         double[] tr = new double[hi.length];
//         tr[0] = hi[0] - lo[0];
//         for (int i = 1; i < hi.length; i++)
//             tr[i] = Math.max(hi[i] - lo[i],
//                     Math.max(Math.abs(hi[i] - cl[i - 1]), Math.abs(lo[i] - cl[i - 1])));
//         double atr = 0;
//         for (int i = 0; i < period; i++) atr += tr[i];
//         atr /= period;
//         for (int i = period; i < hi.length; i++) atr = (atr * (period - 1) + tr[i]) / period;
//         return atr;
//     }

//     private static double swingLow(double[] lo, int bars) {
//         double min = Double.MAX_VALUE;
//         for (int i = Math.max(0, lo.length - bars); i < lo.length; i++) min = Math.min(min, lo[i]);
//         return min;
//     }

//     private static double swingHigh(double[] hi, int bars) {
//         double max = -Double.MAX_VALUE;
//         for (int i = Math.max(0, hi.length - bars); i < hi.length; i++) max = Math.max(max, hi[i]);
//         return max;
//     }

//     private static double roundToTick(double price, double tick) {
//         if (tick <= 0) return price;
//         return Math.round(price / tick) * tick;
//     }

//     // =========================================================================
//     // OHLCV EXTRACTION
//     // =========================================================================

//     private static double[] extractCloses(JSONArray a) {
//         double[] o = new double[a.length()];
//         for (int i = 0; i < a.length(); i++) o[i] = a.getJSONObject(i).getDouble("close");
//         return o;
//     }

//     private static double[] extractOpens(JSONArray a) {
//         double[] o = new double[a.length()];
//         for (int i = 0; i < a.length(); i++) o[i] = a.getJSONObject(i).getDouble("open");
//         return o;
//     }

//     private static double[] extractHighs(JSONArray a) {
//         double[] o = new double[a.length()];
//         for (int i = 0; i < a.length(); i++) o[i] = a.getJSONObject(i).getDouble("high");
//         return o;
//     }

//     private static double[] extractLows(JSONArray a) {
//         double[] o = new double[a.length()];
//         for (int i = 0; i < a.length(); i++) o[i] = a.getJSONObject(i).getDouble("low");
//         return o;
//     }

//     // =========================================================================
//     // COINDCX API
//     // =========================================================================

//     private static JSONArray getCandlestickData(String pair, String resolution, int count) {
//         try {
//             long minsPerBar;
//             switch (resolution) {
//                 case "1":  minsPerBar = 1;  break;
//                 case "5":  minsPerBar = 5;  break;
//                 case "15": minsPerBar = 15; break;
//                 case "60": minsPerBar = 60; break;
//                 default:   minsPerBar = 15; break;
//             }
//             long to   = Instant.now().getEpochSecond();
//             long from = to - minsPerBar * 60L * count;
//             String url = PUBLIC_API_URL + "/market_data/candlesticks"
//                     + "?pair=" + pair + "&from=" + from + "&to=" + to
//                     + "&resolution=" + resolution + "&pcode=f";
//             HttpURLConnection conn = openGet(url);
//             int code = conn.getResponseCode();
//             if (code == 200) {
//                 JSONObject r = new JSONObject(readStream(conn.getInputStream()));
//                 if ("ok".equals(r.optString("s"))) return r.getJSONArray("data");
//                 System.err.println("  Candle status=" + r.optString("s") + " for " + pair);
//             } else {
//                 System.err.println("  Candle HTTP " + code + " for " + pair);
//             }
//         } catch (Exception e) {
//             System.err.println("  getCandlestickData(" + pair + "): " + e.getMessage());
//         }
//         return null;
//     }

//     private static void initInstrumentCache() {
//         try {
//             long now = System.currentTimeMillis();
//             if (now - lastCacheUpdate < TICK_CACHE_TTL_MS) return;
//             instrumentCache.clear();
//             System.out.println("Refreshing instrument cache...");
//             JSONArray pairs = new JSONArray(publicGet(
//                     BASE_URL + "/exchange/v1/derivatives/futures/data/active_instruments"));
//             for (int i = 0; i < pairs.length(); i++) {
//                 String p = pairs.getString(i);
//                 try {
//                     String raw = publicGet(
//                             BASE_URL + "/exchange/v1/derivatives/futures/data/instrument?pair=" + p);
//                     instrumentCache.put(p, new JSONObject(raw).getJSONObject("instrument"));
//                 } catch (Exception ignored) {}
//             }
//             lastCacheUpdate = now;
//             System.out.println("Instruments cached: " + instrumentCache.size());
//         } catch (Exception e) {
//             System.err.println("initInstrumentCache: " + e.getMessage());
//         }
//     }

//     private static double getTickSize(String pair) {
//         if (System.currentTimeMillis() - lastCacheUpdate > TICK_CACHE_TTL_MS) initInstrumentCache();
//         JSONObject d = instrumentCache.get(pair);
//         return d != null ? d.optDouble("price_increment", 0.0001) : 0.0001;
//     }

//     private static double getEntryPrice(String pair, String orderId) throws Exception {
//         for (int i = 0; i < MAX_ENTRY_PRICE_CHECKS; i++) {
//             TimeUnit.MILLISECONDS.sleep(ENTRY_CHECK_DELAY_MS);
//             JSONObject pos = findPosition(pair);
//             if (pos != null && pos.optDouble("avg_price", 0) > 0)
//                 return pos.getDouble("avg_price");
//         }
//         return 0;
//     }

//     private static JSONObject findPosition(String pair) throws Exception {
//         JSONObject body = new JSONObject();
//         body.put("timestamp", Instant.now().toEpochMilli());
//         body.put("page", "1");
//         body.put("size", "20");
//         body.put("margin_currency_short_name", new String[]{"INR", "USDT"});
//         String resp = authPost(
//                 BASE_URL + "/exchange/v1/derivatives/futures/positions", body.toString());
//         JSONArray arr = resp.startsWith("[")
//                 ? new JSONArray(resp)
//                 : new JSONArray().put(new JSONObject(resp));
//         for (int i = 0; i < arr.length(); i++) {
//             JSONObject p = arr.getJSONObject(i);
//             if (pair.equals(p.optString("pair"))) return p;
//         }
//         return null;
//     }

//     private static double calcQuantity(double price, String pair) {
//         double qty = MAX_MARGIN / (price * LEVERAGE);
//         return Math.max(
//                 INTEGER_QTY_PAIRS.contains(pair)
//                         ? Math.floor(qty)
//                         : Math.floor(qty * 100) / 100,
//                 0);
//     }

//     public static double getLastPrice(String pair) {
//         try {
//             HttpURLConnection conn = openGet(
//                     PUBLIC_API_URL + "/market_data/trade_history?pair=" + pair + "&limit=1");
//             if (conn.getResponseCode() == 200) {
//                 String r = readStream(conn.getInputStream());
//                 return r.startsWith("[")
//                         ? new JSONArray(r).getJSONObject(0).getDouble("p")
//                         : new JSONObject(r).getDouble("p");
//             }
//         } catch (Exception e) {
//             System.err.println("getLastPrice(" + pair + "): " + e.getMessage());
//         }
//         return 0;
//     }

//     public static JSONObject placeFuturesMarketOrder(String side, String pair, double qty,
//                                                      int lev, String notif,
//                                                      String marginType, String marginCcy) {
//         try {
//             JSONObject order = new JSONObject();
//             order.put("side",                       side.toLowerCase());
//             order.put("pair",                       pair);
//             order.put("order_type",                 "market_order");
//             order.put("total_quantity",             qty);
//             order.put("leverage",                   lev);
//             order.put("notification",               notif);
//             order.put("time_in_force",              "good_till_cancel");
//             order.put("hidden",                     false);
//             order.put("post_only",                  false);
//             order.put("position_margin_type",       marginType);
//             order.put("margin_currency_short_name", marginCcy);
//             JSONObject body = new JSONObject();
//             body.put("timestamp", Instant.now().toEpochMilli());
//             body.put("order", order);
//             String resp = authPost(
//                     BASE_URL + "/exchange/v1/derivatives/futures/orders/create", body.toString());
//             return resp.startsWith("[")
//                     ? new JSONArray(resp).getJSONObject(0)
//                     : new JSONObject(resp);
//         } catch (Exception e) {
//             System.err.println("placeFuturesMarketOrder: " + e.getMessage());
//             return null;
//         }
//     }

//     public static void setTpSl(String posId, double tp, double sl, String pair) {
//         try {
//             double tick = getTickSize(pair);
//             double rtp  = roundToTick(tp, tick);
//             double rsl  = roundToTick(sl, tick);

//             JSONObject tpObj = new JSONObject();
//             tpObj.put("stop_price",  rtp);
//             tpObj.put("limit_price", rtp);
//             tpObj.put("order_type",  "take_profit_market");

//             JSONObject slObj = new JSONObject();
//             slObj.put("stop_price",  rsl);
//             slObj.put("limit_price", rsl);
//             slObj.put("order_type",  "stop_market");

//             JSONObject payload = new JSONObject();
//             payload.put("timestamp",   Instant.now().toEpochMilli());
//             payload.put("id",          posId);
//             payload.put("take_profit", tpObj);
//             payload.put("stop_loss",   slObj);

//             String resp = authPost(
//                     BASE_URL + "/exchange/v1/derivatives/futures/positions/create_tpsl",
//                     payload.toString());
//             JSONObject r = new JSONObject(resp);
//             System.out.println(r.has("err_code_dcx")
//                     ? "  TP/SL error: " + r
//                     : "  TP/SL set successfully!");
//         } catch (Exception e) {
//             System.err.println("setTpSl: " + e.getMessage());
//         }
//     }

//     public static String getPositionId(String pair) {
//         try {
//             JSONObject p = findPosition(pair);
//             return p != null ? p.getString("id") : null;
//         } catch (Exception e) {
//             System.err.println("getPositionId: " + e.getMessage());
//             return null;
//         }
//     }

//     private static Set<String> getActivePositions() {
//         Set<String> active = new HashSet<>();
//         try {
//             JSONObject body = new JSONObject();
//             body.put("timestamp", Instant.now().toEpochMilli());
//             body.put("page", "1");
//             body.put("size", "100");
//             body.put("margin_currency_short_name", new String[]{"INR", "USDT"});
//             String resp = authPost(
//                     BASE_URL + "/exchange/v1/derivatives/futures/positions", body.toString());
//             JSONArray arr = resp.startsWith("[")
//                     ? new JSONArray(resp)
//                     : new JSONArray().put(new JSONObject(resp));
//             System.out.println("=== Open Positions (" + arr.length() + ") ===");
//             for (int i = 0; i < arr.length(); i++) {
//                 JSONObject p    = arr.getJSONObject(i);
//                 String    pair  = p.optString("pair", "");
//                 boolean isActive = p.optDouble("active_pos", 0) > 0
//                         || p.optDouble("locked_margin", 0) > 0
//                         || p.optDouble("avg_price", 0) > 0
//                         || p.optDouble("take_profit_trigger", 0) > 0
//                         || p.optDouble("stop_loss_trigger", 0) > 0;
//                 if (isActive) {
//                     System.out.printf("  %s | qty=%.2f | entry=%.6f | TP=%.4f | SL=%.4f%n",
//                             pair,
//                             p.optDouble("active_pos", 0),
//                             p.optDouble("avg_price", 0),
//                             p.optDouble("take_profit_trigger", 0),
//                             p.optDouble("stop_loss_trigger", 0));
//                     active.add(pair);
//                 }
//             }
//         } catch (Exception e) {
//             System.err.println("getActivePositions: " + e.getMessage());
//         }
//         return active;
//     }

//     // =========================================================================
//     // LOW-LEVEL HTTP + HMAC
//     // =========================================================================

//     private static HttpURLConnection openGet(String url) throws IOException {
//         HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
//         c.setRequestMethod("GET");
//         c.setConnectTimeout(10_000);
//         c.setReadTimeout(10_000);
//         return c;
//     }

//     private static String publicGet(String url) throws IOException {
//         HttpURLConnection c = openGet(url);
//         if (c.getResponseCode() == 200) return readStream(c.getInputStream());
//         throw new IOException("HTTP " + c.getResponseCode() + " — " + url);
//     }

//     private static String authPost(String url, String json) throws IOException {
//         HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
//         c.setRequestMethod("POST");
//         c.setRequestProperty("Content-Type",     "application/json");
//         c.setRequestProperty("X-AUTH-APIKEY",    API_KEY);
//         c.setRequestProperty("X-AUTH-SIGNATURE", sign(json));
//         c.setConnectTimeout(10_000);
//         c.setReadTimeout(10_000);
//         c.setDoOutput(true);
//         try (OutputStream os = c.getOutputStream()) {
//             os.write(json.getBytes(StandardCharsets.UTF_8));
//         }
//         InputStream is = c.getResponseCode() >= 400
//                 ? c.getErrorStream()
//                 : c.getInputStream();
//         return readStream(is);
//     }

//     private static String readStream(InputStream is) throws IOException {
//         return new BufferedReader(new InputStreamReader(is))
//                 .lines().collect(Collectors.joining("\n"));
//     }

//     private static String sign(String payload) {
//         try {
//             Mac mac = Mac.getInstance("HmacSHA256");
//             mac.init(new SecretKeySpec(
//                     API_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
//             byte[] b = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
//             StringBuilder sb = new StringBuilder();
//             for (byte x : b) sb.append(String.format("%02x", x));
//             return sb.toString();
//         } catch (Exception e) {
//             throw new RuntimeException("HMAC sign failed", e);
//         }
//     }

//     public static String generateHmacSHA256(String secret, String payload) {
//         return sign(payload);
//     }
// }
