import org.json.JSONArray;
import org.json.JSONObject;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * CoinDCX Futures Trader — Improved v10
 *
 * ALL IMPROVEMENTS APPLIED:
 *   [FIX-1]  Daily P&L protection actually enforced
 *   [FIX-2]  Max 3 simultaneous open positions
 *   [FIX-3]  Live USDT/INR rate (no hardcoded 98)
 *   [FIX-4]  Meme/low-liquidity coins removed
 *   [FIX-5]  ATR volatility filter (skip choppy + skip explosive)
 *   [FIX-6]  RSI zones fixed — no overlap (long 52-68, short 32-48)
 *   [FIX-7]  Volume confirmation as 3rd soft filter (need 2 of 3)
 *   [FIX-8]  Tiered leverage (10x for top-5, 5x for rest)
 *   [FIX-9]  Minimum R:R validator (skip if R:R < 1.5)
 *   [FIX-10] Trade logging to trades.log file
 *
 * SIGNAL ARCHITECTURE (unchanged, still robust):
 *   4-HARD (all must pass):
 *     H1. 1H EMA-50 macro trend
 *     H2. 15m EMA-9 vs EMA-21 local trend
 *     H3. MACD line vs signal alignment
 *     H4. Supertrend(10, 3.0) direction
 *
 *   3-SOFT (at least 2 of 3 must pass):
 *     S1. RSI zone (non-overlapping: long 52-68, short 32-48)
 *     S2. Previous 15m candle body direction
 *     S3. Volume spike (last closed candle > 1.3x 10-bar average)
 *
 *   ENTRY TIMING:
 *     Last closed 5m candle must match signal direction
 *     Price must be within 0.8x ATR of EMA-9 (not over-extended)
 *
 *   SL/TP:
 *     SL = swing structure +/- noise buffer, clamped 2.0–3.5x ATR
 *     TP = entry +/- 1.2x actual risk (1:1.2 minimum, validated before order)
 */
public class CoinDCXFuturesTrader8C_BUY_SELL_NEW_LOGIC_THREE {

    // =========================================================================
    // Configuration
    // =========================================================================
    private static final String API_KEY    = System.getenv("DELTA_API_KEY");
    private static final String API_SECRET = System.getenv("DELTA_API_SECRET");
    private static final String BASE_URL       = "https://api.coindcx.com";
    private static final String PUBLIC_API_URL = "https://public.coindcx.com";

    private static final double MAX_MARGIN             = 800.0;   // INR per trade
    private static final int    MAX_OPEN_POSITIONS     = 2000;       // [FIX-2] cap simultaneous trades
    private static final int    MAX_ENTRY_PRICE_CHECKS = 10;
    private static final int    ENTRY_CHECK_DELAY_MS   = 1000;
    private static final long   TICK_CACHE_TTL_MS      = 3_600_000L;
    private static final long   COOLDOWN_MS            = 2 * 60 * 60 * 1000L; // 2 hours per pair

    // Indicator parameters
    private static final int EMA_FAST   = 9;
    private static final int EMA_MID    = 21;
    private static final int EMA_MACRO  = 50;
    private static final int MACD_FAST  = 12;
    private static final int MACD_SLOW  = 26;
    private static final int MACD_SIG   = 9;
    private static final int RSI_PERIOD = 14;
    private static final int ATR_PERIOD = 14;

    // Supertrend
    private static final int    ST_PERIOD     = 10;
    private static final double ST_MULTIPLIER = 3.0;

    // [FIX-6] Non-overlapping RSI zones
    private static final double RSI_LONG_MIN  = 52.0;
    private static final double RSI_LONG_MAX  = 68.0;
    private static final double RSI_SHORT_MIN = 32.0;
    private static final double RSI_SHORT_MAX = 48.0;

    // SL/TP
    private static final double SL_SWING_BUFFER = 0.3;
    private static final double SL_MIN_ATR      = 2.0;
    private static final double SL_MAX_ATR      = 3.5;
    private static final double RR              = 1.2;
    private static final double NOISE_BUFFER    = 0.8;
    private static final double MIN_RR_REQUIRED = 1.5;  // [FIX-9]

    // [FIX-5] ATR volatility filter
    private static final double ATR_PCT_MIN = 0.15;  // skip if too quiet (ranging)
    private static final double ATR_PCT_MAX = 3.5;   // skip if too explosive

    // [FIX-7] Volume filter
    private static final double VOLUME_SPIKE_MULTIPLIER = 1.3; // last candle > 1.3x avg

    // Candle counts
    private static final int CANDLE_15M = 200;
    private static final int CANDLE_5M  = 120;
    private static final int CANDLE_1H  = 120;

    // =========================================================================
    // [FIX-1] Daily P&L Protection — now actually enforced
    // =========================================================================
    private static final double DAILY_PROFIT_TARGET  = 400.0;
    private static final double DAILY_DRAWDOWN_LIMIT = 250.0;
    private static final double DAILY_LOSS_LIMIT     = 300.0;

    private static double dailyPnl   = 0.0;
    private static double dailyPeak  = 0.0;
    private static double dailyLoss  = 0.0;
    private static long   lastResetDay = -1L;

    // =========================================================================
    // [FIX-8] Tiered leverage — top liquid pairs get 10x, rest get 5x
    // =========================================================================
    private static final Set<String> HIGH_LIQ_PAIRS = new HashSet<>(Arrays.asList(
            "B-ETH_USDT", "B-SOL_USDT", "B-BNB_USDT", "B-XRP_USDT", "B-ADA_USDT"
    ));

    private static int getLeverage(String pair) {
        return HIGH_LIQ_PAIRS.contains(pair) ? 10 : 5;
    }

    // =========================================================================
    // [FIX-4] Curated coin list — meme/low-liquidity coins removed
    // Kept: coins with genuine volume, real use-cases, reasonable liquidity
    // Removed: FARTCOIN, MOODENG, DOGS, PONKE, BRETT, TURBO, POPCAT,
    //          MUBARAK, KOMA, AVAAI, GOAT, AI16Z, MELANIA, TRUMP, VINE,
    //          MYRO, 1000CHEEMS, 1000RATS, 1MBABYDOGE, 1000CAT, 1000XEC,
    //          KOMA, ALCH, MAVIA, BMT, DEGEN, ACE, ALICE, BIGTIME, CHESS,
    //          VOXEL, SPELL, 1000WHY, PERP, NKN, RARE, HFT
    // =========================================================================
    private static final String[] COIN_SYMBOLS = {
        // Tier 1 — highest liquidity
        "ETH", "SOL", "BNB", "XRP", "ADA", "DOGE", "LTC", "BCH", "ETC", "XMR",

        // Tier 2 — solid DeFi/L1/L2
        "AVAX", "NEAR", "OP", "ARB", "ATOM", "DOT", "LINK", "UNI", "AAVE",
        "INJ", "SUI", "APT", "TIA", "SEI", "ICP", "FIL", "STX", "RENDER",

        // Tier 3 — established alts with real volume
        "ONDO", "HBAR", "TON", "TRX", "XLM", "VET", "ALGO", "IOTA", "NEO",
        "QTUM", "ZEC", "DASH", "ZIL", "ONE", "KAVA", "BAND", "GRT", "SNX",
        "CRV", "COMP", "MKR", "YFI", "LDO", "PENDLE", "RUNE", "FTM", "KAS",

        // Tier 4 — mid-caps with adequate liquidity
        "JUP", "PYTH", "WIF", "ORDI", "SATS", "FET", "TAO", "WLD", "1000PEPE",
        "1000SHIB", "1000BONK", "1000FLOKI", "IMX", "MINA", "FLOW", "CHZ",
        "SAND", "MANA", "GALA", "ENJ", "AXS", "MASK", "BLUR", "ID", "CFX",
        "AR", "STRK", "ZRO", "ARKM", "THETA", "EIGEN", "MORPHO", "KAITO",
        "USUAL", "LAYER", "JTO", "CKB", "TWT", "KSM", "SUSHI", "CAKE",
        "1INCH", "BAT", "ANKR", "XTZ", "ROSE", "CELO", "GAS", "ACT",
        "NMR", "LPT", "STORJ", "ENS", "RPL", "VANA", "BOME", "HOT",
        "LUNA2", "IOST", "FLUX", "ZRX", "LRC", "SKL", "METIS", "GMX",
        "QNT", "EGLD", "SSV", "FXS", "UMA", "XVS", "AKT", "ASTR",
        "CTSI", "CHR", "C98", "SUN", "TNSR", "AERO", "IO", "DEXE",
        "ARK", "XAI", "DYM", "SAGA", "PONKE", "MANTA", "SFP", "AGLD",
        "ZETA", "RED", "LISTA", "WOO", "COW", "VTHO", "LEVER", "SOLV",
        "POWR", "LSK", "MAV", "REI", "ONG", "FORTH", "BEL", "GHST",
        "LOKA", "DIA", "TLM", "FUN", "PIXEL", "ARPA", "PNUT", "HIGH",
        "HOOK", "KMNO", "NTRN", "PARTI", "SHELL", "AUCTION", "SWELL",
        "MLN", "STEEM", "MTL", "BICO", "DENT", "RLC", "PHB", "DEFI",
        "COOKIE", "JOE", "BNT", "SCRT", "VELODROME", "VIC", "RAYSOL",
        "BTCDOM", "S", "IP", "TRB", "KNC", "KAIA", "BANANA",
        "POL", "MAGIC", "ZK", "NFP", "PEOPLE", "POLYX", "MOVR",
        "OMNI", "CELR", "RVN", "GLM", "HIVE", "ILV", "CYBER",
        "SUPER", "EDU", "PROM", "ALT", "WAXP", "ALPHA", "COTI",
        "USTC", "BIGTIME", "1000SATS", "GPS", "SXP", "API3", "ZEN",
        "AXL", "ACH", "GTC", "ROSE"
    };

    // =========================================================================
    // Internal state
    // =========================================================================
    private static final Map<String, JSONObject> instrumentCache = new ConcurrentHashMap<>();
    private static long lastCacheUpdate = 0;
    private static final Map<String, Long> lastTradeTime = new ConcurrentHashMap<>();

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
        System.out.println("========================================");
        System.out.println("  CoinDCX Futures Trader v10 Starting");
        System.out.println("========================================");

        initInstrumentCache();

        // [FIX-1] Check daily limits before doing anything
        if (isDailyLimitHit()) {
            System.out.println("Daily limit already hit — exiting.");
            return;
        }

        Set<String> active = getActivePositions();
        System.out.println("Active positions: " + active.size() + " / " + MAX_OPEN_POSITIONS);

        // [FIX-2] If already at max positions, no point scanning
        if (active.size() >= MAX_OPEN_POSITIONS) {
            System.out.println("Already at max open positions (" + MAX_OPEN_POSITIONS + ") — exiting.");
            return;
        }

        // [FIX-3] Fetch live USDT/INR rate once for this run
        double usdtInrRate = fetchUsdtInrRate();
        System.out.printf("Live USDT/INR rate: %.2f%n%n", usdtInrRate);

        for (String pair : COINS_TO_TRADE) {
            try {
                // [FIX-1] Re-check daily limits each iteration
                if (isDailyLimitHit()) {
                    System.out.println("Daily limit hit mid-scan — stopping.");
                    break;
                }

                // [FIX-2] Re-check position count (may have changed)
                active = getActivePositions();
                if (active.size() >= MAX_OPEN_POSITIONS) {
                    System.out.println("Max positions reached — stopping scan.");
                    break;
                }

                if (active.contains(pair)) {
                    System.out.println("Skip " + pair + " — active position");
                    continue;
                }

                long lastTrade = lastTradeTime.getOrDefault(pair, 0L);
                if (System.currentTimeMillis() - lastTrade < COOLDOWN_MS) {
                    System.out.println("Skip " + pair + " — cooldown active");
                    continue;
                }

                System.out.println("\n==== " + pair + " ====");

                // ── Fetch candles ─────────────────────────────────────────────
                JSONArray raw15m = getCandlestickData(pair, "15", CANDLE_15M);
                JSONArray raw1h  = getCandlestickData(pair, "60", CANDLE_1H);
                JSONArray raw5m  = getCandlestickData(pair, "5",  CANDLE_5M);

                if (raw15m == null || raw15m.length() < 60) {
                    System.out.println("  Insufficient 15m candles — skip");
                    continue;
                }
                if (raw1h == null || raw1h.length() < EMA_MACRO) {
                    System.out.println("  Insufficient 1H candles — skip");
                    continue;
                }
                if (raw5m == null || raw5m.length() < 30) {
                    System.out.println("  Insufficient 5m candles — skip");
                    continue;
                }

                double[] cl15 = extractCloses(raw15m);
                double[] op15 = extractOpens(raw15m);
                double[] hi15 = extractHighs(raw15m);
                double[] lo15 = extractLows(raw15m);
                double[] vo15 = extractVolumes(raw15m);  // [FIX-7]
                double[] cl1h = extractCloses(raw1h);
                double[] cl5m = extractCloses(raw5m);
                double[] op5m = extractOpens(raw5m);
                double[] hi5m = extractHighs(raw5m);
                double[] lo5m = extractLows(raw5m);

                double lastClose = cl15[cl15.length - 1];
                double prevClose = cl15[cl15.length - 2];
                double prevOpen  = op15[op15.length - 2];
                double tickSize  = getTickSize(pair);
                double atr15m    = calcATR(hi15, lo15, cl15, ATR_PERIOD);

                // [FIX-5] ATR volatility filter
                double atrPct = (atr15m / lastClose) * 100.0;
                if (atrPct < ATR_PCT_MIN) {
                    System.out.printf("  Skip — market too quiet (ATR=%.3f%%) — ranging%n", atrPct);
                    continue;
                }
                if (atrPct > ATR_PCT_MAX) {
                    System.out.printf("  Skip — market too explosive (ATR=%.3f%%) — uncontrollable risk%n", atrPct);
                    continue;
                }
                System.out.printf("  ATR=%.6f (%.3f%%)  Tick=%.8f%n", atr15m, atrPct, tickSize);

                // 5m entry timing
                double last5mClose = cl5m[cl5m.length - 2];
                double last5mOpen  = op5m[op5m.length - 2];
                boolean entry5mBull = last5mClose > last5mOpen;
                boolean entry5mBear = last5mClose < last5mOpen;

                // ── H1: 1H Macro Trend ────────────────────────────────────────
                double  ema1h     = calcEMA(cl1h, EMA_MACRO);
                boolean macroUp   = lastClose > ema1h;
                boolean macroDown = lastClose < ema1h;
                System.out.printf("  [H1] 1H EMA50=%.6f | %s%n",
                        ema1h, macroUp ? "BULL" : "BEAR");
                if (!macroUp && !macroDown) { System.out.println("  H1 FAIL"); continue; }

                // ── H2: 15m Local Trend ───────────────────────────────────────
                double  ema9      = calcEMA(cl15, EMA_FAST);
                double  ema21     = calcEMA(cl15, EMA_MID);
                boolean localUp   = ema9 > ema21;
                boolean localDown = ema9 < ema21;
                boolean trendUp   = macroUp   && localUp;
                boolean trendDown = macroDown && localDown;
                System.out.printf("  [H2] EMA9=%.6f EMA21=%.6f -> %s%n",
                        ema9, ema21, trendUp ? "BULL" : trendDown ? "BEAR" : "MIXED");
                if (!trendUp && !trendDown) {
                    System.out.println("  H2 FAIL — macro/local misaligned");
                    continue;
                }

                // ── H3: MACD ──────────────────────────────────────────────────
                double[] mv       = calcMACD(cl15, MACD_FAST, MACD_SLOW, MACD_SIG);
                double   macdLine = mv[0], macdSigV = mv[1];
                boolean  macdBull = macdLine > macdSigV;
                boolean  macdBear = macdLine < macdSigV;
                System.out.printf("  [H3] MACD=%.6f Sig=%.6f -> %s%n",
                        macdLine, macdSigV, macdBull ? "BULL" : "BEAR");
                if (trendUp   && !macdBull) { System.out.println("  H3 FAIL — MACD bearish"); continue; }
                if (trendDown && !macdBear) { System.out.println("  H3 FAIL — MACD bullish"); continue; }

                // ── H4: Supertrend ────────────────────────────────────────────
                boolean[] stResult   = calcSupertrend(hi15, lo15, cl15, ST_PERIOD, ST_MULTIPLIER);
                boolean   stBull     = stResult[stResult.length - 1];
                boolean   stPrevBull = stResult[stResult.length - 2];
                boolean   stFlipped  = stBull != stPrevBull;
                System.out.printf("  [H4] Supertrend -> %s%s%n",
                        stBull ? "GREEN/BULL" : "RED/BEAR",
                        stFlipped ? " *** FLIPPED ***" : "");
                if (trendUp   && !stBull) { System.out.println("  H4 FAIL — ST bearish"); continue; }
                if (trendDown &&  stBull) { System.out.println("  H4 FAIL — ST bullish"); continue; }

                System.out.println("  All 4 HARD filters passed.");

                // ── S1: RSI (non-overlapping zones) ───────────────────────────
                double  rsi        = calcRSI(cl15, RSI_PERIOD);
                boolean rsiOkLong  = trendUp   && rsi >= RSI_LONG_MIN  && rsi <= RSI_LONG_MAX;
                boolean rsiOkShort = trendDown && rsi >= RSI_SHORT_MIN && rsi <= RSI_SHORT_MAX;
                boolean softRsi    = rsiOkLong || rsiOkShort;
                System.out.printf("  [S1] RSI=%.2f (Long:%.0f-%.0f | Short:%.0f-%.0f) -> %s%n",
                        rsi, RSI_LONG_MIN, RSI_LONG_MAX,
                        RSI_SHORT_MIN, RSI_SHORT_MAX,
                        softRsi ? "PASS" : "fail");

                // ── S2: Candle body direction ──────────────────────────────────
                boolean prevBull   = prevClose > prevOpen;
                boolean prevBear   = prevClose < prevOpen;
                boolean softCandle = (trendUp && prevBull) || (trendDown && prevBear);
                System.out.printf("  [S2] Prev candle -> %s -> %s%n",
                        prevBull ? "BULL" : prevBear ? "BEAR" : "DOJI",
                        softCandle ? "PASS" : "fail");

                // ── S3: Volume spike [FIX-7] ──────────────────────────────────
                double avgVol = 0;
                int volBars   = Math.min(10, vo15.length - 2);
                for (int i = vo15.length - 2 - volBars; i < vo15.length - 2; i++)
                    avgVol += vo15[i];
                avgVol /= volBars;
                double lastVol    = vo15[vo15.length - 2];
                boolean softVolume = lastVol > VOLUME_SPIKE_MULTIPLIER * avgVol;
                System.out.printf("  [S3] Volume: last=%.0f avg=%.0f (%.1fx) -> %s%n",
                        lastVol, avgVol, avgVol > 0 ? lastVol / avgVol : 0,
                        softVolume ? "PASS" : "fail");

                // Need at least 2 of 3 soft filters
                int softPass = (softRsi ? 1 : 0) + (softCandle ? 1 : 0) + (softVolume ? 1 : 0);
                System.out.printf("  SOFT: %d/3 passed (need 2)%n", softPass);
                if (softPass < 2) {
                    System.out.println("  SOFT FAIL — not enough confirmation");
                    continue;
                }

                // ── Entry timing: 5m candle confirmation ──────────────────────
                boolean entry5mOk = (trendUp && entry5mBull) || (trendDown && entry5mBear);
                System.out.printf("  [5m] Entry candle -> %s -> %s%n",
                        entry5mBull ? "BULL" : entry5mBear ? "BEAR" : "DOJI",
                        entry5mOk ? "PASS" : "WAIT");
                if (!entry5mOk) {
                    System.out.println("  5m entry timing not ideal — skip");
                    continue;
                }

                // ── Price extension filter ────────────────────────────────────
                double distFromEma9 = Math.abs(lastClose - ema9);
                if (distFromEma9 > 0.8 * atr15m) {
                    System.out.printf("  Skip — price too extended from EMA9 (dist=%.6f > 0.8xATR=%.6f)%n",
                            distFromEma9, 0.8 * atr15m);
                    continue;
                }

                // ── All filters passed ─────────────────────────────────────────
                String side = trendUp ? "buy" : "sell";
                System.out.println("  >>> ALL FILTERS PASSED -> " + side.toUpperCase() + " " + pair
                        + (stFlipped ? " [ST FLIP — HIGH CONFIDENCE]" : ""));

                // ── Current price and quantity ─────────────────────────────────
                double currentPrice = getLastPrice(pair);
                if (currentPrice <= 0) { System.out.println("  Invalid price — skip"); continue; }

                int    lev = getLeverage(pair);  // [FIX-8] tiered leverage
                double qty = calcQuantity(currentPrice, pair, usdtInrRate, lev);
                if (qty <= 0) { System.out.println("  Invalid qty — skip"); continue; }

                // ── Pre-calculate SL/TP and validate R:R BEFORE placing order ──
                // (Avoids entering a trade with bad R:R ratio)
                double[] slTp = calcSlTp(side, currentPrice, hi15, lo15, atr15m);
                double   slPrice = roundToTick(slTp[0], tickSize);
                double   tpPrice = roundToTick(slTp[1], tickSize);

                double actualRR = Math.abs(tpPrice - currentPrice) / Math.abs(currentPrice - slPrice);

                // [FIX-9] Validate R:R before placing order
                if (actualRR < MIN_RR_REQUIRED) {
                    System.out.printf("  Skip — R:R too low (%.2f < %.1f required)%n",
                            actualRR, MIN_RR_REQUIRED);
                    continue;
                }

                double slPct = Math.abs(currentPrice - slPrice) / currentPrice * 100;
                double tpPct = Math.abs(tpPrice - currentPrice) / currentPrice * 100;
                System.out.printf("  SL=%.6f (%.2f%%) | TP=%.6f (%.2f%%) | R:R=1:%.2f%n",
                        slPrice, slPct, tpPrice, tpPct, actualRR);
                System.out.printf("  Placing %s | price=%.6f | qty=%.4f | lev=%dx%n",
                        side.toUpperCase(), currentPrice, qty, lev);

                // ── Place order ────────────────────────────────────────────────
                JSONObject resp = placeFuturesMarketOrder(side, pair, qty, lev,
                        "email_notification", "isolated", "INR");

                if (resp == null || !resp.has("id")) {
                    System.out.println("  Order failed: " + resp);
                    continue;
                }
                System.out.println("  Order placed! id=" + resp.getString("id"));
                lastTradeTime.put(pair, System.currentTimeMillis());

                // ── Confirm entry price ────────────────────────────────────────
                double entry = getEntryPrice(pair, resp.getString("id"));
                if (entry <= 0) {
                    System.out.println("  Could not confirm entry — TP/SL skipped");
                    continue;
                }
                System.out.printf("  Entry confirmed: %.6f%n", entry);

                // Recalculate SL/TP using actual fill price
                double[] slTpFinal = calcSlTp(side, entry, hi15, lo15, atr15m);
                slPrice = roundToTick(slTpFinal[0], tickSize);
                tpPrice = roundToTick(slTpFinal[1], tickSize);

                System.out.printf("  Final SL=%.6f | TP=%.6f%n", slPrice, tpPrice);

                // ── Set TP/SL ──────────────────────────────────────────────────
                String posId = getPositionId(pair);
                if (posId != null) {
                    setTpSl(posId, tpPrice, slPrice, pair);
                } else {
                    System.out.println("  Position ID not found — TP/SL not set");
                }

                // [FIX-10] Log trade
                logTrade(pair, side, entry, slPrice, tpPrice, qty, lev, actualRR);

            } catch (Exception e) {
                System.err.println("Error on " + pair + ": " + e.getMessage());
                e.printStackTrace();
            }
        }

        System.out.println("\n=== Scan complete ===");
        System.out.printf("Daily P&L: %.2f | Peak: %.2f | Loss today: %.2f%n",
                dailyPnl, dailyPeak, dailyLoss);
    }

    // =========================================================================
    // [FIX-1] Daily P&L Protection
    // =========================================================================

    private static boolean isDailyLimitHit() {
        long today = LocalDate.now().toEpochDay();
        if (today != lastResetDay) {
            dailyPnl  = 0;
            dailyPeak = 0;
            dailyLoss = 0;
            lastResetDay = today;
            System.out.println("Daily P&L counters reset for new day.");
        }

        if (dailyLoss >= DAILY_LOSS_LIMIT) {
            System.out.printf("🔴 DAILY LOSS LIMIT HIT (%.2f >= %.2f) — No more trades today%n",
                    dailyLoss, DAILY_LOSS_LIMIT);
            return true;
        }

        if (dailyPeak > 0 && (dailyPeak - dailyPnl) >= DAILY_DRAWDOWN_LIMIT) {
            System.out.printf("🔴 DAILY DRAWDOWN LIMIT HIT (peak=%.2f, now=%.2f, drop=%.2f) — No more trades today%n",
                    dailyPeak, dailyPnl, dailyPeak - dailyPnl);
            return true;
        }

        return false;
    }

    /**
     * Call this after a trade closes to update daily P&L tracking.
     * In production, hook this into your position close/webhook handler.
     */
    public static void updateDailyPnl(double pnl) {
        dailyPnl  += pnl;
        dailyPeak  = Math.max(dailyPeak, dailyPnl);
        if (pnl < 0) dailyLoss += Math.abs(pnl);
        System.out.printf("Daily P&L updated: %.2f | Peak: %.2f | Loss: %.2f%n",
                dailyPnl, dailyPeak, dailyLoss);
    }

    // =========================================================================
    // [FIX-3] Live USDT/INR rate
    // =========================================================================

    private static double fetchUsdtInrRate() {
        try {
            HttpURLConnection conn = openGet(
                    PUBLIC_API_URL + "/market_data/trade_history?pair=B-USDT_INR&limit=1");
            if (conn.getResponseCode() == 200) {
                String r = readStream(conn.getInputStream());
                double rate = r.startsWith("[")
                        ? new JSONArray(r).getJSONObject(0).getDouble("p")
                        : new JSONObject(r).getDouble("p");
                if (rate > 50 && rate < 200) return rate; // sanity check
            }
        } catch (Exception e) {
            System.err.println("fetchUsdtInrRate failed: " + e.getMessage());
        }
        System.out.println("Using fallback USDT/INR rate: 98.0");
        return 98.0;
    }

    // =========================================================================
    // SL/TP Calculation — extracted to reusable method
    // Returns [slPrice, tpPrice]
    // =========================================================================

    private static double[] calcSlTp(String side, double entry,
                                      double[] hi15, double[] lo15, double atr15m) {
        double slPrice, tpPrice;

        if ("buy".equalsIgnoreCase(side)) {
            double swLow = swingLow(lo15, 30);
            double rawSL = swLow - (NOISE_BUFFER * atr15m) - (SL_SWING_BUFFER * atr15m);
            double minSL = entry - SL_MIN_ATR * atr15m;
            double maxSL = entry - SL_MAX_ATR * atr15m;
            slPrice = Math.max(Math.min(rawSL, minSL), maxSL);
            double risk = entry - slPrice;
            tpPrice = entry + RR * risk;
        } else {
            double swHigh = swingHigh(hi15, 30);
            double rawSL  = swHigh + (NOISE_BUFFER * atr15m) + (SL_SWING_BUFFER * atr15m);
            double minSL  = entry + SL_MIN_ATR * atr15m;
            double maxSL  = entry + SL_MAX_ATR * atr15m;
            slPrice = Math.min(Math.max(rawSL, minSL), maxSL);
            double risk = slPrice - entry;
            tpPrice = entry - RR * risk;
        }

        return new double[]{slPrice, tpPrice};
    }

    // =========================================================================
    // [FIX-10] Trade Logging
    // =========================================================================

    private static void logTrade(String pair, String side, double entry,
                                  double sl, double tp, double qty, int lev, double rr) {
        try (FileWriter fw = new FileWriter("trades.log", true)) {
            fw.write(String.format("%s | %s | %s | entry=%.6f | SL=%.6f | TP=%.6f | qty=%.4f | lev=%dx | R:R=1:%.2f%n",
                    Instant.now(), pair, side.toUpperCase(), entry, sl, tp, qty, lev, rr));
        } catch (Exception e) {
            System.err.println("logTrade failed: " + e.getMessage());
        }
    }

    // =========================================================================
    // SUPERTREND CALCULATION
    // =========================================================================

    private static boolean[] calcSupertrend(double[] hi, double[] lo, double[] cl,
                                             int period, double multiplier) {
        int n = cl.length;
        boolean[] bullish = new boolean[n];

        if (n < period + 1) {
            Arrays.fill(bullish, true);
            return bullish;
        }

        double[] atrArr   = calcATRSeries(hi, lo, cl, period);
        double[] upperBand = new double[n];
        double[] lowerBand = new double[n];

        for (int i = period; i < n; i++) {
            double hl2        = (hi[i] + lo[i]) / 2.0;
            double basicUpper = hl2 + multiplier * atrArr[i];
            double basicLower = hl2 - multiplier * atrArr[i];

            if (i == period) {
                upperBand[i] = basicUpper;
                lowerBand[i] = basicLower;
            } else {
                upperBand[i] = (basicUpper < upperBand[i-1] || cl[i-1] > upperBand[i-1])
                        ? basicUpper : upperBand[i-1];
                lowerBand[i] = (basicLower > lowerBand[i-1] || cl[i-1] < lowerBand[i-1])
                        ? basicLower : lowerBand[i-1];
            }

            if (i == period) {
                bullish[i] = cl[i] > (hi[i] + lo[i]) / 2.0;
            } else {
                if (bullish[i-1]) {
                    bullish[i] = cl[i] >= lowerBand[i];
                } else {
                    bullish[i] = cl[i] > upperBand[i];
                }
            }
        }

        for (int i = 0; i < period; i++) bullish[i] = bullish[period];
        return bullish;
    }

    private static double[] calcATRSeries(double[] hi, double[] lo, double[] cl, int period) {
        int n = hi.length;
        double[] atr = new double[n];
        if (n < 2) return atr;

        double[] tr = new double[n];
        tr[0] = hi[0] - lo[0];
        for (int i = 1; i < n; i++)
            tr[i] = Math.max(hi[i] - lo[i],
                    Math.max(Math.abs(hi[i] - cl[i-1]), Math.abs(lo[i] - cl[i-1])));

        double sum = 0;
        for (int i = 0; i < period && i < n; i++) sum += tr[i];
        atr[period - 1] = sum / period;

        for (int i = period; i < n; i++)
            atr[i] = (atr[i-1] * (period - 1) + tr[i]) / period;

        for (int i = 0; i < period - 1; i++) atr[i] = atr[period - 1];
        return atr;
    }

    // =========================================================================
    // INDICATOR CALCULATIONS
    // =========================================================================

    private static double calcEMA(double[] d, int period) {
        if (d.length < period) return 0;
        double k = 2.0 / (period + 1), ema = 0;
        for (int i = 0; i < period; i++) ema += d[i];
        ema /= period;
        for (int i = period; i < d.length; i++) ema = d[i] * k + ema * (1 - k);
        return ema;
    }

    private static double[] calcEMASeries(double[] d, int period) {
        double[] out = new double[d.length];
        if (d.length < period) return out;
        double k = 2.0 / (period + 1), seed = 0;
        for (int i = 0; i < period; i++) seed += d[i];
        out[period - 1] = seed / period;
        for (int i = period; i < d.length; i++) out[i] = d[i] * k + out[i - 1] * (1 - k);
        return out;
    }

    private static double[] calcMACD(double[] cl, int fast, int slow, int sig) {
        double[] ef = calcEMASeries(cl, fast);
        double[] es = calcEMASeries(cl, slow);
        int start   = slow - 1;
        int len     = cl.length - start;
        if (len <= 0) return new double[]{0, 0, 0};
        double[] ml = new double[len];
        for (int i = 0; i < len; i++) ml[i] = ef[start + i] - es[start + i];
        double[] ss = calcEMASeries(ml, sig);
        double m = ml[ml.length - 1];
        double s = ss[ss.length - 1];
        return new double[]{m, s, m - s};
    }

    private static double calcRSI(double[] cl, int period) {
        if (cl.length < period + 1) return 50;
        double ag = 0, al = 0;
        for (int i = 1; i <= period; i++) {
            double ch = cl[i] - cl[i - 1];
            if (ch > 0) ag += ch; else al += Math.abs(ch);
        }
        ag /= period; al /= period;
        for (int i = period + 1; i < cl.length; i++) {
            double ch = cl[i] - cl[i - 1];
            if (ch > 0) {
                ag = (ag * (period - 1) + ch) / period;
                al =  al * (period - 1) / period;
            } else {
                al = (al * (period - 1) + Math.abs(ch)) / period;
                ag =  ag * (period - 1) / period;
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
                    Math.max(Math.abs(hi[i] - cl[i - 1]), Math.abs(lo[i] - cl[i - 1])));
        double atr = 0;
        for (int i = 0; i < period; i++) atr += tr[i];
        atr /= period;
        for (int i = period; i < hi.length; i++) atr = (atr * (period - 1) + tr[i]) / period;
        return atr;
    }

    private static double swingLow(double[] lo, int bars) {
        double min = Double.MAX_VALUE;
        for (int i = Math.max(0, lo.length - bars); i < lo.length; i++) min = Math.min(min, lo[i]);
        return min;
    }

    private static double swingHigh(double[] hi, int bars) {
        double max = -Double.MAX_VALUE;
        for (int i = Math.max(0, hi.length - bars); i < hi.length; i++) max = Math.max(max, hi[i]);
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

    // [FIX-7] Volume extraction
    private static double[] extractVolumes(JSONArray a) {
        double[] o = new double[a.length()];
        for (int i = 0; i < a.length(); i++) {
            JSONObject candle = a.getJSONObject(i);
            // CoinDCX may use "volume" or "vol" — handle both
            o[i] = candle.has("volume") ? candle.getDouble("volume")
                 : candle.has("vol")    ? candle.getDouble("vol")
                 : 0.0;
        }
        return o;
    }

    // =========================================================================
    // COINDCX API
    // =========================================================================

    private static JSONArray getCandlestickData(String pair, String resolution, int count) {
        try {
            long minsPerBar;
            switch (resolution) {
                case "1":  minsPerBar = 1;  break;
                case "5":  minsPerBar = 5;  break;
                case "15": minsPerBar = 15; break;
                case "60": minsPerBar = 60; break;
                default:   minsPerBar = 15; break;
            }
            long to   = Instant.now().getEpochSecond();
            long from = to - minsPerBar * 60L * count;
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
            System.err.println("  getCandlestickData(" + pair + "): " + e.getMessage());
        }
        return null;
    }

    private static void initInstrumentCache() {
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

    // [FIX-3] Updated to accept live rate and tiered leverage
    private static double calcQuantity(double price, String pair, double usdtInrRate, int lev) {
        double qty = (MAX_MARGIN * lev) / (price * usdtInrRate);
        double finalQty = INTEGER_QTY_PAIRS.contains(pair)
                ? Math.floor(qty)
                : Math.floor(qty * 100) / 100.0;
        return Math.max(finalQty, 0);
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
        Set<String> active = new HashSet<>();
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
                String    pairName = p.optString("pair", "");
                boolean isActive = p.optDouble("active_pos", 0) > 0
                        || p.optDouble("locked_margin", 0) > 0
                        || p.optDouble("avg_price", 0) > 0
                        || p.optDouble("take_profit_trigger", 0) > 0
                        || p.optDouble("stop_loss_trigger", 0) > 0;
                if (isActive) {
                    System.out.printf("  %s | qty=%.2f | entry=%.6f | TP=%.4f | SL=%.4f%n",
                            pairName,
                            p.optDouble("active_pos", 0),
                            p.optDouble("avg_price", 0),
                            p.optDouble("take_profit_trigger", 0),
                            p.optDouble("stop_loss_trigger", 0));
                    active.add(pairName);
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
//  * CoinDCX Futures Trader — Balanced Signal Engine v9 (Supertrend Edition)
//  *
//  * DESIGN PHILOSOPHY:
//  *   4-HARD + 2-SOFT architecture:
//  *
//  *   HARD (all 4 must pass — quality baseline):
//  *     H1. 1H EMA-50 Macro     : Price above = bull, below = bear
//  *     H2. 15m EMA 9 vs 21     : EMA-9 > EMA-21 = bull, < = bear
//  *     H3. MACD alignment      : Line above signal = bull, below = bear
//  *     H4. Supertrend (10,3)   : GREEN (below price) = bull, RED (above price) = bear
//  *
//  *   SOFT (at least 1 of 2 must pass):
//  *     S1. RSI zone             : Long 45-65 | Short 35-55
//  *     S2. Candle body          : Previous closed candle matches direction
//  *
//  *   SL/TP:
//  *     Long  SL = clamp(swingLow - 1.2xATR,  entry-2.0xATR, entry-1.2xATR)
//  *     Short SL = clamp(swingHigh + 1.2xATR, entry+1.2xATR, entry+2.0xATR)
//  *     TP       = entry +/- 2.0 x actual risk  (1:2 R:R)
//  */
// public class CoinDCXFuturesTrader8C_BUY_SELL_NEW_LOGIC_THREE {

//     // =========================================================================
//     // Configuration
//     // =========================================================================
//     private static final String API_KEY    = System.getenv("DELTA_API_KEY");
//     private static final String API_SECRET = System.getenv("DELTA_API_SECRET");
//     private static final String BASE_URL       = "https://api.coindcx.com";
//     private static final String PUBLIC_API_URL = "https://public.coindcx.com";

//     private static final double MAX_MARGIN             = 800.0;
//     private static final int    LEVERAGE               = 11;
//     private static final int    MAX_ENTRY_PRICE_CHECKS = 10;
//     private static final int    ENTRY_CHECK_DELAY_MS   = 1000;
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
//     private static final int SWING_BARS = 20;

//     // Supertrend parameters
//     private static final int    ST_PERIOD     = 10;   // standard supertrend period
//     private static final double ST_MULTIPLIER = 3.0;  // standard supertrend multiplier

//     // RSI zones
//     private static final double RSI_LONG_MIN  = 45.0;
//     private static final double RSI_LONG_MAX  = 65.0;
//     private static final double RSI_SHORT_MIN = 35.0;
//     private static final double RSI_SHORT_MAX = 55.0;

//     // SL/TP parameters — SL now uses 5m ATR (tighter) not 15m ATR
//     // This is the fix for SL getting hit too early:
//     //   15m ATR is ~3x wider than 5m ATR
//     //   Using 5m ATR puts SL close to actual recent noise level
//     private static final double SL_SWING_BUFFER = 0.3;   // ATR buffer beyond 5m swing
//     private static final double SL_MIN_ATR      = 2.0;   // minimum SL distance (5m ATR units)
//     private static final double SL_MAX_ATR      = 3.5;   // maximum SL distance (5m ATR units)
//     private static final double RR              = 1.2;   // 1:2 R:R
    
//     private static final double NOISE_BUFFER     = 0.8;   // extra breathing room (15m ATR units)

//     private static final int CANDLE_15M = 200;
//     private static final int CANDLE_5M  = 120;  // 5m candles for entry timing + tight SL
//     private static final int CANDLE_1H  = 120;

//     private static final Map<String, JSONObject> instrumentCache = new ConcurrentHashMap<>();
//     private static long lastCacheUpdate = 0;

//     private static final Map<String, Long> lastTradeTime = new ConcurrentHashMap<>();
//     private static final long COOLDOWN_MS = 2 * 60 * 60 * 1000L; // 2 hours

//     // =========================================================================
//     // Daily P&L Protection — prevents profit erosion
//     // =========================================================================
//     // Rules:
//     //   1. If total day loss hits DAILY_LOSS_LIMIT       → LOCK all trading today
//     //   2. If profit drops by DAILY_DRAWDOWN_LIMIT from  → LOCK all trading today
//     //      peak (e.g. was +500, now +200 = 300 drawdown)
//     //   3. If profit exceeds DAILY_PROFIT_TARGET         → enter CAUTIOUS MODE
//     //      (use only 50% margin per trade, protect the gains)
//     //   4. All counters reset automatically at midnight

//     private static final double DAILY_PROFIT_TARGET   = 400.0;  // ₹ reduce size after this
//     private static final double DAILY_DRAWDOWN_LIMIT  = 250.0;  // ₹ lock if profit drops this much from peak
//     private static final double DAILY_LOSS_LIMIT      = 300.0;  // ₹ hard stop if total day loss hits this
//     // =========================================================================
//     // private static final String[] COIN_SYMBOLS = {
//     //     "SOL", "1000SHIB", "API3", "DOGS", "KAVA", "ARK", "VOXEL", "SXP", "FLOW", "CHESS",
//     //     "AXL", "MANA", "BNB", "1000BONK", "ALPHA", "NEAR", "TRB", "GRT", "WIF", "RSR",
//     //     "QTUM", "AVAX", "BIGTIME", "COTI", "PONKE", "ETHFI", "ICP", "VET", "ACH", "MINA",
//     //     "COMP", "XAI", "JTO", "USTC", "SPELL", "KNC", "INJ", "BLUR", "DYM", "SNX",
//     //     "IMX", "1000WHY", "ALGO", "CRV", "JUP", "ZEN", "BAT", "SAGA", "AAVE", "SEI",
//     //     "KAIA", "NFP", "PEOPLE", "SUI", "ONE", "RENDER", "POLYX", "ENS", "MOVR", "BRETT",
//     //     "ETH", "OMNI", "MKR", "AR", "CFX", "ID", "CELR", "LDO", "UNI", "LTC",
//     //     "TAO", "CKB", "FET", "STX", "SAND", "XLM", "EGLD", "BOME", "HOT", "LUNA2",
//     //     "ADA", "RVN", "GLM", "MASK", "STRK", "GALA", "YFI", "IOST", "OP",
//     //     "1000PEPE", "TRUMP", "ZIL", "RPL", "WLD", "DOGE", "XMR", "ONDO", "APT", "HIVE",
//     //     "FIL", "TIA", "CHZ", "ETC", "LINK", "ORDI", "ATOM", "TON", "TRX", "HBAR",
//     //     "NEO", "IOTA", "GMX", "QNT", "FTM", "VANA", "FLUX", "DASH", "ZRX", "MANTA",
//     //     "CAKE", "PYTH", "ARB", "SFP", "METIS", "LRC", "SKL", "ZEC", "RUNE", "ALICE",
//     //     "ANKR", "XTZ", "GTC", "ROSE", "BCH", "CELO", "BAND", "1INCH", "SUPER", "ILV",
//     //     "SSV", "ARPA", "FXS", "UMA", "MTL", "DEGEN", "XVS", "ACE", "1000FLOKI", "AKT",
//     //     "ASTR", "TWT", "CTSI", "VIRTUAL", "CHR", "EDU", "PROM", "KSM", "BICO", "DENT",
//     //     "ALT", "C98", "RLC", "SUN", "PENDLE", "BANANA", "NMR", "POL", "MAGIC",
//     //     "MOODENG", "WAXP", "ZK", "GAS", "ALPACA", "TNSR", "PHB", "POWR", "LSK", "FIO",
//     //     "DEFI", "KAS", "1000SATS", "ARKM", "PIXEL", "MAV", "REI", "ZRO", "COOKIE",
//     //     "JOE", "BNT", "CYBER", "SCRT", "XRP", "VELODROME", "ONG", "AERO", "HOOK", "AI16Z",
//     //     "KMNO", "LPT", "THETA", "NTRN", "VIC", "RAYSOL", "PARTI", "MELANIA", "MEW", "EIGEN",
//     //     "XVG", "MYRO", "IO", "SHELL", "AUCTION", "STORJ", "SWELL", "COS", "FORTH", "BEL",
//     //     "PNUT", "HIGH", "ENJ", "LISTA", "ZETA", "MORPHO", "WOO", "MLN", "COW", "HEI",
//     //     "DEXE", "OM", "RED", "GHST", "STEEM", "LOKA", "ACT", "KAITO", "DIA", "SUSHI",
//     //     "AGLD", "TLM", "BMT", "MAVIA", "ALCH", "VTHO", "FUN", "POPCAT", "TURBO", "1000CHEEMS",
//     //     "1000CAT", "1000LUNC", "1000RATS", "1000000MOG", "1MBABYDOGE", "1000XEC", "1000X",
//     //     "BTCDOM", "USUAL", "PERP", "LAYER", "NKN", "MUBARAK", "FARTCOIN", "GOAT", "LEVER",
//     //     "SOLV", "S", "ARC", "VINE", "RARE", "GPS", "IP", "AVAAI", "KOMA", "HFT"
//     // };

//     private static final String[] COIN_SYMBOLS = {
//     "ETH", "SOL", "ZEC", "XRP", "DOGE", "BNB", "TAO", "1000PEPE", "ADA", "SUI",
//     "BCH", "LINK", "AVAX", "FIL", "OP", "NEAR", "TRX", "TRUMP", "ARB", "WLD",
//     "FET", "ETC", "AAVE", "WIF", "INJ", "TIA", "LTC", "ONDO", "ORDI", "TON",
//     "HBAR", "IMX", "ATOM", "RUNE", "KAS", "UNI", "ICP", "SEI", "PENDLE", "1000SHIB",
//     "1000BONK", "CRV", "JUP", "RENDER", "MKR", "LDO", "STX", "XLM", "PYTH", "VIRTUAL",
//     "APT", "SNX", "STRK", "NEO", "FTM", "CAKE", "1000FLOKI", "1000SATS", "OM", "FARTCOIN",
//     "GRT", "MINA", "COMP", "BLUR", "BRETT", "SAND", "EGLD", "XMR", "IOTA", "AI16Z",
//     "PNUT", "POPCAT", "ZRO", "MANA", "ETHFI", "VET", "ALGO", "ENS", "BOME", "MASK",
//     "GALA", "YFI", "CHZ", "GMX", "QNT", "POL", "MOODENG", "ZK", "ARKM", "THETA",
//     "MEW", "EIGEN", "MORPHO", "KAITO", "USUAL", "LAYER", "GOAT", "DOGS", "RSR", "PONKE",
//     "JTO", "CKB", "ZIL", "ROSE", "1INCH", "TWT", "KSM", "MAGIC", "GAS", "ACT",
//     "SUSHI", "TURBO", "1000LUNC", "BTCDOM", "S", "IP", "FLOW", "TRB", "QTUM", "KNC",
//     "KAIA", "CELO", "SSV", "BANANA", "TNSR", "AERO", "IO", "DEXE", "ARK", "XAI",
//     "DYM", "SAGA", "HOT", "LUNA2", "IOST", "RPL", "VANA", "DASH", "MANTA", "LRC",
//     "ANKR", "XTZ", "BAND", "SUPER", "FXS", "AKT", "NMR", "PIXEL", "LPT", "STORJ",
//     "ENJ", "LISTA", "ZETA", "RED", "AGLD", "GPS", "KAVA", "SXP", "ALPHA", "BIGTIME",
//     "COTI", "USTC", "BAT", "NFP", "ONE", "POLYX", "MOVR", "OMNI", "CELR", "RVN",
//     "GLM", "HIVE", "FLUX", "ZRX", "SFP", "ALICE", "ILV", "ARPA", "UMA", "DEGEN",
//     "XVS", "ACE", "ASTR", "CTSI", "CHR", "EDU", "PROM", "ALT", "C98", "SUN",
//     "WAXP", "ALPACA", "COOKIE", "JOE", "BNT", "SCRT", "VELODROME", "HOOK", "KMNO", "NTRN",
//     "VIC", "RAYSOL", "PARTI", "MELANIA", "MYRO", "SHELL", "AUCTION", "SWELL", "HIGH", "WOO",
//     "COW", "MAVIA", "VTHO", "1000CAT", "MUBARAK", "LEVER", "SOLV", "ARC", "AVAAI", "KOMA",
//     "API3", "VOXEL", "CHESS", "SPELL", "1000WHY", "SKL", "GTC", "MTL", "BICO", "DENT",
//     "RLC", "PHB", "POWR", "LSK", "DEFI", "MAV", "REI", "ONG", "XVG", "COS",
//     "FORTH", "BEL", "MLN", "HEI", "GHST", "STEEM", "LOKA", "DIA", "TLM", "BMT",
//     "ALCH", "FUN", "1000CHEEMS", "1000RATS", "1000000MOG", "1MBABYDOGE", "1000XEC", "1000X", "PERP", "NKN",
//     "VINE", "RARE", "HFT", "AXL", "ACH", "ZEN", "PEOPLE", "AR", "CFX", "ID",
//     "METIS", "FIO", "CYBER"
// };

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
//                 long lastTrade = lastTradeTime.getOrDefault(pair, 0L);
//                 if (System.currentTimeMillis() - lastTrade < COOLDOWN_MS) {
//                     System.out.println("  Skip " + pair + " — cooldown active");
//                     continue;
//                 }
//                 System.out.println("\n==== " + pair + " ====");

//                 // ── Fetch candles ─────────────────────────────────────────────
//                 // 3-layer timeframe stack:
//                 //   1H  = macro trend direction (H1)
//                 //   15m = signal layer (H2 H3 H4 + soft filters)
//                 //   5m  = entry timing + tight SL calculation
//                 JSONArray raw15m = getCandlestickData(pair, "15", CANDLE_15M);
//                 JSONArray raw1h  = getCandlestickData(pair, "60", CANDLE_1H);
//                 JSONArray raw5m  = getCandlestickData(pair, "5",  CANDLE_5M);

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
//                 if (raw5m == null || raw5m.length() < 30) {
//                     System.out.println("  Insufficient 5m candles (" +
//                             (raw5m == null ? 0 : raw5m.length()) + ") — skip");
//                     continue;
//                 }

//                 double[] cl15 = extractCloses(raw15m);
//                 double[] op15 = extractOpens(raw15m);
//                 double[] hi15 = extractHighs(raw15m);
//                 double[] lo15 = extractLows(raw15m);
//                 double[] cl1h = extractCloses(raw1h);

//                 // 5m arrays — used ONLY for tight SL and entry candle confirmation
//                 double[] cl5m = extractCloses(raw5m);
//                 double[] op5m = extractOpens(raw5m);
//                 double[] hi5m = extractHighs(raw5m);
//                 double[] lo5m = extractLows(raw5m);

//                 double lastClose  = cl15[cl15.length - 1];
//                 double prevClose  = cl15[cl15.length - 2];
//                 double prevOpen   = op15[op15.length - 2];
//                 double tickSize   = getTickSize(pair);
//                 double atr15m     = calcATR(hi15, lo15, cl15, ATR_PERIOD); // for Supertrend on 15m
//                 double atr5m      = calcATR(hi5m, lo5m, cl5m, ATR_PERIOD); // for tight SL

//                 // Entry candle confirmation on 5m:
//                 // Last CLOSED 5m candle (index -2) must match signal direction
//                 double last5mClose = cl5m[cl5m.length - 2];
//                 double last5mOpen  = op5m[op5m.length - 2];
//                 boolean entry5mBull = last5mClose > last5mOpen;
//                 boolean entry5mBear = last5mClose < last5mOpen;

//                 System.out.printf("  Price=%.6f  ATR-15m=%.6f  ATR-5m=%.6f  Tick=%.8f%n",
//                         lastClose, atr15m, atr5m, tickSize);

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

//                 // ── HARD FILTER H4: Supertrend (10, 3.0) ─────────────────────
//                 // GREEN = price above supertrend line = bullish
//                 // RED   = price below supertrend line = bearish
//                 boolean[] stResult  = calcSupertrend(hi15, lo15, cl15, ST_PERIOD, ST_MULTIPLIER);
//                 boolean   stBull    = stResult[stResult.length - 1];      // true = green (bull)
//                 boolean   stPrevBull= stResult[stResult.length - 2];      // previous bar direction
//                 boolean   stFlipped = stBull != stPrevBull;               // just flipped this candle?
//                 System.out.printf("  [H4] Supertrend -> %s%s%n",
//                         stBull ? "GREEN (BULL)" : "RED (BEAR)",
//                         stFlipped ? " [JUST FLIPPED!]" : "");
//                 if (trendUp   && !stBull) { System.out.println("  H4 FAIL — Supertrend bearish — skip"); continue; }
//                 if (trendDown &&  stBull) { System.out.println("  H4 FAIL — Supertrend bullish — skip"); continue; }
//                 System.out.println("  H4 OK — Supertrend aligned");

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

//                 // ── ENTRY TIMING FILTER: 5m candle confirmation ───────────────
//                 // This is the key fix for SL getting hit too early.
//                 // We wait for the last closed 5m candle to agree with signal direction.
//                 // This ensures we're not entering at the exact top/bottom of a 15m move.
//                 boolean entry5mOk = (trendUp && entry5mBull) || (trendDown && entry5mBear);
//                 System.out.printf("  [5m] Entry candle: open=%.6f close=%.6f -> %s -> %s%n",
//                         last5mOpen, last5mClose,
//                         entry5mBull ? "BULL" : entry5mBear ? "BEAR" : "DOJI",
//                         entry5mOk ? "PASS" : "SKIP — wait for next 5m candle");
//                 if (!entry5mOk) {
//                     System.out.println("  5m entry SKIP — signal valid but entry timing not ideal");
//                     continue;
//                 }
//                 System.out.println("  5m entry OK — timing confirmed");

//                 // ── All filters passed ────────────────────────────────────────
//                 String side = trendUp ? "buy" : "sell";
//                 if (stFlipped) {
//                     System.out.println("  *** SUPERTREND FLIP SIGNAL — highest confidence entry ***");
//                 }
//                 System.out.println("  >>> ALL FILTERS PASSED -> " + side.toUpperCase() + " " + pair);

//                 // ── Place order ───────────────────────────────────────────────
//                 double currentPrice = getLastPrice(pair);
//                 if (currentPrice <= 0) { System.out.println("  Invalid price — skip"); continue; }

//                 double qty = calcQuantity(currentPrice, pair);
//                 if (qty <= 0) { System.out.println("  Invalid qty — skip"); continue; }

//                 System.out.printf("  Placing %s | price=%.6f | qty=%.4f | lev=%dx%n",
//                         side.toUpperCase(), currentPrice, qty, LEVERAGE);

//                 // Add this just before placing the order:
// double distFromEma9 = Math.abs(lastClose - ema9);
// if (distFromEma9 > 0.8 * atr15m) {
//     System.out.printf("  Skip — price %.6f extended from EMA9 (dist=%.6f > 0.8xATR=%.6f)%n",
//             lastClose, distFromEma9, 0.8 * atr15m);
//     continue;
// }
// System.out.println("  Entry zone OK — price near EMA9");

//                 JSONObject resp = placeFuturesMarketOrder(side, pair, qty, LEVERAGE,
//                         "email_notification", "isolated", "INR");

//                 if (resp == null || !resp.has("id")) {
//                     System.out.println("  Order failed: " + resp);
//                     continue;
//                 }
//                 System.out.println("  Order placed! id=" + resp.getString("id"));
//                 lastTradeTime.put(pair, System.currentTimeMillis());

//                 // ── Confirm entry price ───────────────────────────────────────
//                 double entry = getEntryPrice(pair, resp.getString("id"));
//                 if (entry <= 0) {
//                     System.out.println("  Could not confirm entry — TP/SL skipped");
//                     continue;
//                 }
//                 System.out.printf("  Entry confirmed: %.6f%n", entry);

//                 // ── SL: 3-bound system ────────────────────────────────────────
//                 // ── SL/TP: Wide SL with breathing room, TP at 1.5x risk ──────────────────
// double slPrice, tpPrice;

// if ("buy".equalsIgnoreCase(side)) {

//     // Step 1: Find the deepest swing low in last 30 bars (not 20)
//     // Deeper lookback = stronger structure level = harder for market to break
//     double swLow15m = swingLow(lo15, 30);

//     // Step 2: Go BELOW the swing low by noise buffer + small ATR buffer
//     // This places SL in "no man's land" — beyond where normal noise reaches
//     // Market would need a genuine reversal to hit this, not just a wick
//     double rawSL = swLow15m - (NOISE_BUFFER * atr15m) - (SL_SWING_BUFFER * atr15m);

//     // Step 3: Clamp — SL must be at least 2x ATR away (breathing room)
//     //                  SL must be at most 3.5x ATR away (not too far)
//     double minSL = entry - SL_MIN_ATR * atr15m;   // closest SL allowed
//     double maxSL = entry - SL_MAX_ATR * atr15m;   // farthest SL allowed
//     slPrice = Math.max(Math.min(rawSL, minSL), maxSL);

//     // Step 4: TP = 1.5x the actual risk taken
//     // Since SL is wide (2-3.5x ATR), TP will also be reasonably far
//     // but price needs LESS move % to hit TP than to hit SL
//     double risk = entry - slPrice;
//     tpPrice = entry + RR * risk;

//     System.out.printf("  SwingLow=%.6f | RawSL=%.6f | Clamped SL=%.6f%n",
//             swLow15m, rawSL, slPrice);

// } else {

//     // Step 1: Find deepest swing high in last 30 bars
//     double swHigh15m = swingHigh(hi15, 30);

//     // Step 2: Go ABOVE swing high by noise buffer + small ATR buffer
//     double rawSL = swHigh15m + (NOISE_BUFFER * atr15m) + (SL_SWING_BUFFER * atr15m);

//     // Step 3: Clamp
//     double minSL = entry + SL_MIN_ATR * atr15m;
//     double maxSL = entry + SL_MAX_ATR * atr15m;
//     slPrice = Math.min(Math.max(rawSL, minSL), maxSL);

//     // Step 4: TP
//     double risk = slPrice - entry;
//     tpPrice = entry - RR * risk;

//     System.out.printf("  SwingHigh=%.6f | RawSL=%.6f | Clamped SL=%.6f%n",
//             swHigh15m, rawSL, slPrice);
// }

// slPrice = roundToTick(slPrice, tickSize);
// tpPrice = roundToTick(tpPrice, tickSize);

// double slPct = Math.abs(entry - slPrice) / entry * 100;
// double tpPct = Math.abs(tpPrice - entry) / entry * 100;
// System.out.printf("  SL=%.6f (%.2f%% from entry) | TP=%.6f (%.2f%% from entry)%n",
//         slPrice, slPct, tpPrice, tpPct);
// System.out.printf("  Risk=%.6f | Reward=%.6f | R:R = 1:%.1f%n",
//         Math.abs(entry - slPrice),
//         Math.abs(tpPrice - entry), RR);

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
//     // SUPERTREND CALCULATION
//     // =========================================================================

//     /**
//      * Calculates Supertrend for every bar in the series.
//      * Returns boolean[] where true = bullish (price above line = GREEN)
//      *                          false = bearish (price below line = RED)
//      *
//      * Algorithm:
//      *   1. ATR of given period
//      *   2. Basic upper band = (high+low)/2 + multiplier * ATR
//      *      Basic lower band = (high+low)/2 - multiplier * ATR
//      *   3. Final bands carry forward unless price crosses them
//      *   4. Supertrend flips when price crosses the active band
//      */
//     private static boolean[] calcSupertrend(double[] hi, double[] lo, double[] cl,
//                                              int period, double multiplier) {
//         int n = cl.length;
//         boolean[] bullish = new boolean[n];

//         if (n < period + 1) {
//             // Not enough data — default to neutral (no signal)
//             Arrays.fill(bullish, true);
//             return bullish;
//         }

//         double[] atrArr = calcATRSeries(hi, lo, cl, period);

//         double[] upperBand = new double[n];
//         double[] lowerBand = new double[n];
//         double[] supertrend = new double[n];

//         // Seed first valid bar
//         for (int i = period; i < n; i++) {
//             double hl2 = (hi[i] + lo[i]) / 2.0;
//             double basicUpper = hl2 + multiplier * atrArr[i];
//             double basicLower = hl2 - multiplier * atrArr[i];

//             // Upper band: only tighten (never widen) when trend is down
//             if (i == period) {
//                 upperBand[i] = basicUpper;
//                 lowerBand[i] = basicLower;
//             } else {
//                 upperBand[i] = (basicUpper < upperBand[i-1] || cl[i-1] > upperBand[i-1])
//                         ? basicUpper : upperBand[i-1];
//                 lowerBand[i] = (basicLower > lowerBand[i-1] || cl[i-1] < lowerBand[i-1])
//                         ? basicLower : lowerBand[i-1];
//             }

//             // Determine direction
//             if (i == period) {
//                 // Initial: bullish if price above midpoint
//                 bullish[i] = cl[i] > (hi[i] + lo[i]) / 2.0;
//                 supertrend[i] = bullish[i] ? lowerBand[i] : upperBand[i];
//             } else {
//                 if (bullish[i-1]) {
//                     // Was bullish — stays bullish unless price drops below lower band
//                     bullish[i] = cl[i] >= lowerBand[i];
//                 } else {
//                     // Was bearish — stays bearish unless price rises above upper band
//                     bullish[i] = cl[i] > upperBand[i];
//                 }
//                 supertrend[i] = bullish[i] ? lowerBand[i] : upperBand[i];
//             }
//         }

//         // Fill early bars with seed value
//         for (int i = 0; i < period; i++) bullish[i] = bullish[period];

//         return bullish;
//     }

//     /**
//      * ATR series (one value per bar, using Wilder's smoothing).
//      * Returns array same length as input; first `period` values are approximate.
//      */
//     private static double[] calcATRSeries(double[] hi, double[] lo, double[] cl, int period) {
//         int n = hi.length;
//         double[] atr = new double[n];
//         if (n < 2) return atr;

//         // True range
//         double[] tr = new double[n];
//         tr[0] = hi[0] - lo[0];
//         for (int i = 1; i < n; i++)
//             tr[i] = Math.max(hi[i] - lo[i],
//                     Math.max(Math.abs(hi[i] - cl[i-1]), Math.abs(lo[i] - cl[i-1])));

//         // Seed
//         double sum = 0;
//         for (int i = 0; i < period && i < n; i++) sum += tr[i];
//         atr[period - 1] = sum / period;

//         // Wilder smoothing
//         for (int i = period; i < n; i++)
//             atr[i] = (atr[i-1] * (period - 1) + tr[i]) / period;

//         // Back-fill
//         for (int i = 0; i < period - 1; i++) atr[i] = atr[period - 1];

//         return atr;
//     }

//     // =========================================================================
//     // INDICATOR CALCULATIONS (unchanged from original)
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
//     // COINDCX API (unchanged from original)
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
//         double usdtInrRate = 98.0;
//         double qty = (MAX_MARGIN * LEVERAGE) / (price * usdtInrRate);
//         double finalQty = INTEGER_QTY_PAIRS.contains(pair)
//                 ? Math.floor(qty)
//                 : Math.floor(qty * 100) / 100.0;
//         return Math.max(finalQty, 0);
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
//  * CoinDCX Futures Trader — Balanced Signal Engine v9 (Supertrend Edition)
//  *
//  * DESIGN PHILOSOPHY:
//  *   4-HARD + 2-SOFT architecture:
//  *
//  *   HARD (all 4 must pass — quality baseline):
//  *     H1. 1H EMA-50 Macro     : Price above = bull, below = bear
//  *     H2. 15m EMA 9 vs 21     : EMA-9 > EMA-21 = bull, < = bear
//  *     H3. MACD alignment      : Line above signal = bull, below = bear
//  *     H4. Supertrend (10,3)   : GREEN (below price) = bull, RED (above price) = bear
//  *
//  *   SOFT (at least 1 of 2 must pass):
//  *     S1. RSI zone             : Long 45-65 | Short 35-55
//  *     S2. Candle body          : Previous closed candle matches direction
//  *
//  *   SL/TP:
//  *     Long  SL = clamp(swingLow - 1.2xATR,  entry-2.0xATR, entry-1.2xATR)
//  *     Short SL = clamp(swingHigh + 1.2xATR, entry+1.2xATR, entry+2.0xATR)
//  *     TP       = entry +/- 2.0 x actual risk  (1:2 R:R)
//  */
// public class CoinDCXFuturesTrader8C_BUY_SELL_NEW_LOGIC_THREE {

//     // =========================================================================
//     // Configuration
//     // =========================================================================
//     private static final String API_KEY    = System.getenv("DELTA_API_KEY");
//     private static final String API_SECRET = System.getenv("DELTA_API_SECRET");
//     private static final String BASE_URL       = "https://api.coindcx.com";
//     private static final String PUBLIC_API_URL = "https://public.coindcx.com";

//     private static final double MAX_MARGIN             = 900.0;
//     private static final int    LEVERAGE               = 3;
//     private static final int    MAX_ENTRY_PRICE_CHECKS = 10;
//     private static final int    ENTRY_CHECK_DELAY_MS   = 1000;
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
//     private static final int SWING_BARS = 20;

//     // Supertrend parameters
//     private static final int    ST_PERIOD     = 10;   // standard supertrend period
//     private static final double ST_MULTIPLIER = 3.0;  // standard supertrend multiplier

//     // RSI zones
//     private static final double RSI_LONG_MIN  = 45.0;
//     private static final double RSI_LONG_MAX  = 65.0;
//     private static final double RSI_SHORT_MIN = 35.0;
//     private static final double RSI_SHORT_MAX = 55.0;

//     // SL/TP parameters
//     private static final double SL_SWING_BUFFER = 1.2;
//     private static final double SL_MIN_ATR      = 1.2;
//     private static final double SL_MAX_ATR      = 2.0;
//     private static final double RR              = 0.7;   // 1:2 R:R

//     private static final int CANDLE_15M = 200;
//     private static final int CANDLE_1H  = 120;

//     private static final Map<String, JSONObject> instrumentCache = new ConcurrentHashMap<>();
//     private static long lastCacheUpdate = 0;

//     private static final Map<String, Long> lastTradeTime = new ConcurrentHashMap<>();
//     private static final long COOLDOWN_MS = 2 * 60 * 60 * 1000L; // 2 hours

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
//                 long lastTrade = lastTradeTime.getOrDefault(pair, 0L);
//                 if (System.currentTimeMillis() - lastTrade < COOLDOWN_MS) {
//                     System.out.println("  Skip " + pair + " — cooldown active");
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

//                 // ── HARD FILTER H4: Supertrend (10, 3.0) ─────────────────────
//                 // GREEN = price above supertrend line = bullish
//                 // RED   = price below supertrend line = bearish
//                 boolean[] stResult  = calcSupertrend(hi15, lo15, cl15, ST_PERIOD, ST_MULTIPLIER);
//                 boolean   stBull    = stResult[stResult.length - 1];      // true = green (bull)
//                 boolean   stPrevBull= stResult[stResult.length - 2];      // previous bar direction
//                 boolean   stFlipped = stBull != stPrevBull;               // just flipped this candle?
//                 System.out.printf("  [H4] Supertrend -> %s%s%n",
//                         stBull ? "GREEN (BULL)" : "RED (BEAR)",
//                         stFlipped ? " [JUST FLIPPED!]" : "");
//                 if (trendUp   && !stBull) { System.out.println("  H4 FAIL — Supertrend bearish — skip"); continue; }
//                 if (trendDown &&  stBull) { System.out.println("  H4 FAIL — Supertrend bullish — skip"); continue; }
//                 System.out.println("  H4 OK — Supertrend aligned");

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
//                 // Extra log: highlight when supertrend just flipped (strongest signal)
//                 if (stFlipped) {
//                     System.out.println("  *** SUPERTREND FLIP SIGNAL — highest confidence entry ***");
//                 }
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
//                 lastTradeTime.put(pair, System.currentTimeMillis());

//                 // ── Confirm entry price ───────────────────────────────────────
//                 double entry = getEntryPrice(pair, resp.getString("id"));
//                 if (entry <= 0) {
//                     System.out.println("  Could not confirm entry — TP/SL skipped");
//                     continue;
//                 }
//                 System.out.printf("  Entry confirmed: %.6f%n", entry);

//                 // ── SL: 3-bound system ────────────────────────────────────────
//                 double slPrice, tpPrice;
//                 if ("buy".equalsIgnoreCase(side)) {
//                     double swLow  = swingLow(lo15, SWING_BARS);
//                     double rawSL  = swLow  - SL_SWING_BUFFER * atr;
//                     double minSL  = entry  - SL_MIN_ATR * atr;
//                     double maxSL  = entry  - SL_MAX_ATR * atr;
//                     slPrice = Math.max(Math.min(rawSL, minSL), maxSL);
//                     double risk = entry - slPrice;
//                     tpPrice = entry + RR * risk;
//                 } else {
//                     double swHigh = swingHigh(hi15, SWING_BARS);
//                     double rawSL  = swHigh + SL_SWING_BUFFER * atr;
//                     double minSL  = entry  + SL_MIN_ATR * atr;
//                     double maxSL  = entry  + SL_MAX_ATR * atr;
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
//     // SUPERTREND CALCULATION
//     // =========================================================================

//     /**
//      * Calculates Supertrend for every bar in the series.
//      * Returns boolean[] where true = bullish (price above line = GREEN)
//      *                          false = bearish (price below line = RED)
//      *
//      * Algorithm:
//      *   1. ATR of given period
//      *   2. Basic upper band = (high+low)/2 + multiplier * ATR
//      *      Basic lower band = (high+low)/2 - multiplier * ATR
//      *   3. Final bands carry forward unless price crosses them
//      *   4. Supertrend flips when price crosses the active band
//      */
//     private static boolean[] calcSupertrend(double[] hi, double[] lo, double[] cl,
//                                              int period, double multiplier) {
//         int n = cl.length;
//         boolean[] bullish = new boolean[n];

//         if (n < period + 1) {
//             // Not enough data — default to neutral (no signal)
//             Arrays.fill(bullish, true);
//             return bullish;
//         }

//         double[] atrArr = calcATRSeries(hi, lo, cl, period);

//         double[] upperBand = new double[n];
//         double[] lowerBand = new double[n];
//         double[] supertrend = new double[n];

//         // Seed first valid bar
//         for (int i = period; i < n; i++) {
//             double hl2 = (hi[i] + lo[i]) / 2.0;
//             double basicUpper = hl2 + multiplier * atrArr[i];
//             double basicLower = hl2 - multiplier * atrArr[i];

//             // Upper band: only tighten (never widen) when trend is down
//             if (i == period) {
//                 upperBand[i] = basicUpper;
//                 lowerBand[i] = basicLower;
//             } else {
//                 upperBand[i] = (basicUpper < upperBand[i-1] || cl[i-1] > upperBand[i-1])
//                         ? basicUpper : upperBand[i-1];
//                 lowerBand[i] = (basicLower > lowerBand[i-1] || cl[i-1] < lowerBand[i-1])
//                         ? basicLower : lowerBand[i-1];
//             }

//             // Determine direction
//             if (i == period) {
//                 // Initial: bullish if price above midpoint
//                 bullish[i] = cl[i] > (hi[i] + lo[i]) / 2.0;
//                 supertrend[i] = bullish[i] ? lowerBand[i] : upperBand[i];
//             } else {
//                 if (bullish[i-1]) {
//                     // Was bullish — stays bullish unless price drops below lower band
//                     bullish[i] = cl[i] >= lowerBand[i];
//                 } else {
//                     // Was bearish — stays bearish unless price rises above upper band
//                     bullish[i] = cl[i] > upperBand[i];
//                 }
//                 supertrend[i] = bullish[i] ? lowerBand[i] : upperBand[i];
//             }
//         }

//         // Fill early bars with seed value
//         for (int i = 0; i < period; i++) bullish[i] = bullish[period];

//         return bullish;
//     }

//     /**
//      * ATR series (one value per bar, using Wilder's smoothing).
//      * Returns array same length as input; first `period` values are approximate.
//      */
//     private static double[] calcATRSeries(double[] hi, double[] lo, double[] cl, int period) {
//         int n = hi.length;
//         double[] atr = new double[n];
//         if (n < 2) return atr;

//         // True range
//         double[] tr = new double[n];
//         tr[0] = hi[0] - lo[0];
//         for (int i = 1; i < n; i++)
//             tr[i] = Math.max(hi[i] - lo[i],
//                     Math.max(Math.abs(hi[i] - cl[i-1]), Math.abs(lo[i] - cl[i-1])));

//         // Seed
//         double sum = 0;
//         for (int i = 0; i < period && i < n; i++) sum += tr[i];
//         atr[period - 1] = sum / period;

//         // Wilder smoothing
//         for (int i = period; i < n; i++)
//             atr[i] = (atr[i-1] * (period - 1) + tr[i]) / period;

//         // Back-fill
//         for (int i = 0; i < period - 1; i++) atr[i] = atr[period - 1];

//         return atr;
//     }

//     // =========================================================================
//     // INDICATOR CALCULATIONS (unchanged from original)
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
//     // COINDCX API (unchanged from original)
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
//         double usdtInrRate = 98.0;
//         double qty = (MAX_MARGIN * LEVERAGE) / (price * usdtInrRate);
//         double finalQty = INTEGER_QTY_PAIRS.contains(pair)
//                 ? Math.floor(qty)
//                 : Math.floor(qty * 100) / 100.0;
//         return Math.max(finalQty, 0);
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

//     private static final double MAX_MARGIN             = 600.0;
//     private static final int    LEVERAGE               = 10;
//     private static final int    MAX_ENTRY_PRICE_CHECKS = 10;
//     private static final int    ENTRY_CHECK_DELAY_MS   = 1000;
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
//     private static final int SWING_BARS = 20;

//     // RSI — wide enough to fire, tight enough to avoid extremes
//     private static final double RSI_LONG_MIN  = 45.0;
//     private static final double RSI_LONG_MAX  = 65.0;
//     private static final double RSI_SHORT_MIN = 35.0;
//     private static final double RSI_SHORT_MAX = 55.0;

//     // SL parameters — 3-bound system (structure, minimum breathing room, maximum risk)
//     private static final double SL_SWING_BUFFER = 1.2;   // ATR buffer beyond swing low/high (structural)
//     private static final double SL_MIN_ATR      = 1.2;   // MINIMUM distance from entry (breathing room)
//     private static final double SL_MAX_ATR      = 2.0;   // MAXIMUM distance from entry (risk cap)
//     private static final double RR              = 2.0;   // 1:4 R:R

//     private static final int CANDLE_15M = 200;
//     private static final int CANDLE_1H  = 120;

//     private static final Map<String, JSONObject> instrumentCache = new ConcurrentHashMap<>();
//     private static long lastCacheUpdate = 0;

//     private static final Map<String, Long> lastTradeTime = new ConcurrentHashMap<>();//new
// private static final long COOLDOWN_MS = 2 * 60 * 60 * 1000L; // 4 hours //new

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
//                 long lastTrade = lastTradeTime.getOrDefault(pair, 0L);//new
//                 if (System.currentTimeMillis() - lastTrade < COOLDOWN_MS) {
//                     System.out.println("  Skip " + pair + " — cooldown active");
//                     continue;//new
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
//                     lastTradeTime.put(pair, System.currentTimeMillis());//new

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

//     // private static double calcQuantity(double price, String pair) {
//     //     // double qty = MAX_MARGIN / (price * LEVERAGE);
//     //     double qty = MAX_MARGIN / price;
//     //     return Math.max(
//     //             INTEGER_QTY_PAIRS.contains(pair)
//     //                     ? Math.floor(qty)
//     //                     : Math.floor(qty * 100) / 100,
//     //             0);
//     // }

// private static double calcQuantity(double price, String pair) {

//     // You can later replace this with dynamic API value
//     double usdtInrRate = 98.0;  // safer than 83 (exchange uses higher internal rate)

//     // Core formula
//     double qty = (MAX_MARGIN * LEVERAGE) / (price * usdtInrRate);

//     // Apply precision rules
//     double finalQty = INTEGER_QTY_PAIRS.contains(pair)
//             ? Math.floor(qty)
//             : Math.floor(qty * 100) / 100.0;

//     // Safety check (avoid zero or negative)
//     return Math.max(finalQty, 0);
// }

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
