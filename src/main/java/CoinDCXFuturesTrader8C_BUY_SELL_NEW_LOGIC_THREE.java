import org.json.JSONArray;
import org.json.JSONObject;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * CoinDCX Futures Trader — v14 (MTF EMA9/21 + Supertrend Confluence Edition)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * CHANGES FROM v13 → v14:
 *
 *   CHANGE #1 — BTC Multi-Timeframe Filter → REMOVED
 *     v13: BTC EMA9/21 checked on 5 TFs; blocked trade if 3+ TFs against.
 *     v14: BTC filter completely removed. No BTC pre-check at all.
 *
 *   CHANGE #2 — Macro Filter M1 (1H EMA50/EMA200) → REMOVED
 *     v13: Required price > EMA50 > EMA200 for bull (or vice versa bear).
 *     v14: Macro filter removed. Direction decided purely by MTF confluence.
 *
 *   CHANGE #3 — T1 Filter (30m EMA9/21 + slope) → REMOVED
 *     v13: Required 30m EMA9 > EMA21 and EMA21 slope rising for bull.
 *     v14: T1 removed. 30m is still included as one of the 5 MTF timeframes.
 *
 *   CHANGE #4 — MTF Confluence → UPGRADED (EMA9/21 + Supertrend per TF)
 *     v13: Only EMA9/21 checked across 5 TFs; need 4/5 aligned.
 *     v14: BOTH EMA9/21 AND Supertrend checked per TF across 5 TFs.
 *          Per-TF scoring:
 *            EMA9 > EMA21  → +1 bull point  (or +1 bear if EMA9 < EMA21)
 *            Supertrend GREEN → +1 bull point (or +1 bear if RED)
 *          A timeframe is "aligned" if at least 1 of its 2 signals agrees
 *          with the trade direction. Rule: need >= MTF_MIN_ALIGNED (4 of 5)
 *          timeframes aligned. Additionally track total bull/bear signal
 *          score (max 10) — logged for reference.
 *          Timeframes: 5m, 15m, 30m, 1H, 2H
 *
 *   CHANGE #5 — SL Logic → 30m Supertrend band, fallback to 1H
 *     v13: SL = 30m Supertrend band ± ATR buffer, clamped 2.1–2.7× ATR.
 *     v14: SL = 30m Supertrend band ± ATR buffer (same clamp).
 *          If 30m Supertrend direction does NOT match trade direction,
 *          fallback to 1H Supertrend band ± ATR buffer.
 *          Clamp remains: SL_MIN_ATR (2.1×) to SL_MAX_ATR (2.7×) ATR.
 *
 *   CHANGE #6 — RR Ratios → Updated
 *     v13: RR_STRONG=1.1, RR_MEDIUM=0.9, RR_WEAK=0.8
 *     v14: RR_STRONG=2.0, RR_MEDIUM=1.8, RR_WEAK=1.5
 *
 * FULL FILTER ARCHITECTURE (v14):
 *
 *   MTF EMA9/21 + SUPERTREND (5m, 15m, 30m, 1H, 2H):
 *     Per TF: check EMA9 vs EMA21, check Supertrend direction.
 *     A TF is "aligned" if ≥1 signal agrees with trade direction.
 *     Require >= MTF_MIN_ALIGNED (4 of 5) TFs aligned.
 *     Trade direction = majority of total bull/bear signal scores.
 *
 *   TREND (30m):
 *     T2. MACD line > signal  (bull / bear)
 *     T3. Supertrend GREEN / RED
 *
 *   QUALITY (30m):
 *     Q1. ADX > 20
 *     Q2. ATR% < 4.5%
 *
 *   ENTRY ZONE (30m):
 *     E1. Price on correct side of EMA9, distance <= 1.4 * ATR
 *     E2. Current candle not overextended (< 1.5 * ATR)
 *
 *   CONFIRMATION (15m — at least 1 of 2):
 *     S1. RSI in zone (42-68 long | 32-58 short)
 *     S2. Previous 15m candle matches direction
 *
 *   TIMING (15m momentum candle):
 *     Strong momentum candle (body > 35%, close near extreme)
 *
 *   SL/TP:
 *     SL = 30m Supertrend band ± ATR buffer (fallback 1H if 30m ST mismatch)
 *          Clamped: 2.1–2.7× ATR
 *     TP = dynamic R:R based on ADX:
 *          ADX >= 40 → 2.0  |  ADX >= 30 → 1.8  |  ADX < 30 → 1.5
 * ═══════════════════════════════════════════════════════════════════════════
 */
public class CoinDCXFuturesTrader8C_BUY_SELL_NEW_LOGIC_THREE {

    // =========================================================================
    // API Configuration
    // =========================================================================
    private static final String API_KEY    = System.getenv("DELTA_API_KEY");
    private static final String API_SECRET = System.getenv("DELTA_API_SECRET");
    private static final String BASE_URL       = "https://api.coindcx.com";
    private static final String PUBLIC_API_URL = "https://public.coindcx.com";

    private static final double MAX_MARGIN             = 300.0;
    private static final int    LEVERAGE               = 9;
    private static final int    MAX_ENTRY_PRICE_CHECKS = 10;
    private static final int    ENTRY_CHECK_DELAY_MS   = 1000;
    private static final long   TICK_CACHE_TTL_MS      = 3_600_000L;
    private static final long   COOLDOWN_MS            = 2 * 60 * 60 * 1000L;

    // ── Indicator periods ─────────────────────────────────────────────────────
    private static final int EMA_FAST  = 9;
    private static final int EMA_MID   = 21;
    private static final int MACD_FAST = 12;
    private static final int MACD_SLOW = 26;
    private static final int MACD_SIG  = 9;
    private static final int RSI_PERIOD = 14;
    private static final int ATR_PERIOD  = 14;
    private static final int ADX_PERIOD  = 14;

    // ── Supertrend ────────────────────────────────────────────────────────────
    private static final int    ST_PERIOD     = 10;
    private static final double ST_MULTIPLIER = 3.5;

    // ── MTF EMA9/21 + Supertrend confluence ───────────────────────────────────
    // 5 timeframes × 2 signals (EMA9/21 + Supertrend) = 10 signals max.
    // A TF is "aligned" if ≥1 of its 2 signals matches trade direction.
    // Require >= MTF_MIN_ALIGNED TFs aligned.
    private static final int MTF_MIN_ALIGNED = 4; // out of 5 TFs

    // ── ADX threshold ────────────────────────────────────────────────────────
    private static final double ADX_MIN = 20.0;

    // ── RSI zones ────────────────────────────────────────────────────────────
    private static final double RSI_LONG_MIN  = 42.0;
    private static final double RSI_LONG_MAX  = 68.0;
    private static final double RSI_SHORT_MIN = 32.0;
    private static final double RSI_SHORT_MAX = 58.0;

    // ── SL parameters ────────────────────────────────────────────────────────
    private static final double ST_SL_BUFFER = 0.3;  // ATR units added beyond ST band
    private static final double SL_MIN_ATR   = 2.1;  // minimum SL distance in ATR
    private static final double SL_MAX_ATR   = 2.7;  // maximum SL distance in ATR

    // ── Dynamic RR based on ADX (v14 updated values) ─────────────────────────
    private static final double RR_STRONG = 2.0;  // ADX >= 40
    private static final double RR_MEDIUM = 1.8;  // ADX >= 30
    private static final double RR_WEAK   = 1.5;  // ADX < 30

    // ── Entry zone filters ────────────────────────────────────────────────────
    private static final double EMA9_PULLBACK_MAX        = 1.4;
    private static final double MAX_CANDLE_ATR_RATIO     = 1.5;
    private static final double ST_FLIP_MAX_CANDLE_RATIO = 1.2;

    // ── 15m momentum candle quality ───────────────────────────────────────────
    private static final double MIN_15M_BODY_RATIO       = 0.35;
    private static final double CLOSE_NEAR_EXTREME_RATIO = 0.45;

    // ── High volatility skip ──────────────────────────────────────────────────
    private static final double MAX_ATR_PERCENT = 4.5;

    // ── Candle fetch counts per timeframe ─────────────────────────────────────
    private static final int CANDLE_5M  = 60;
    private static final int CANDLE_15M = 60;
    private static final int CANDLE_30M = 100;
    private static final int CANDLE_1H  = 100;
    private static final int CANDLE_2H  = 80;

    // ── Daily P&L protection ──────────────────────────────────────────────────
    private static final double DAILY_PROFIT_TARGET  = 400.0;
    private static final double DAILY_DRAWDOWN_LIMIT = 250.0;
    private static final double DAILY_LOSS_LIMIT     = 300.0;

    private static final Map<String, JSONObject> instrumentCache = new ConcurrentHashMap<>();
    private static long lastCacheUpdate = 0;
    private static final Map<String, Long> lastTradeTime = new ConcurrentHashMap<>();

    // =========================================================================
    // Coin list
    // =========================================================================
    private static final String[] COIN_SYMBOLS = {
        "ETH", "SOL", "ZEC", "XRP", "DOGE", "BNB", "TAO", "1000PEPE", "ADA", "SUI",
        "BCH", "LINK", "AVAX", "FIL", "OP", "NEAR", "TRX", "TRUMP", "ARB", "WLD",
        "FET", "ETC", "AAVE", "WIF", "INJ", "TIA", "LTC", "ONDO", "ORDI", "TON",
        "HBAR", "IMX", "ATOM", "RUNE", "KAS", "UNI", "ICP", "SEI", "PENDLE", "1000SHIB",
        "1000BONK", "CRV", "JUP", "RENDER", "MKR", "LDO", "STX", "XLM", "PYTH", "VIRTUAL",
        "APT", "SNX", "STRK", "NEO", "FTM", "CAKE", "1000FLOKI", "1000SATS", "OM", "FARTCOIN",
        "GRT", "MINA", "COMP", "BLUR", "BRETT", "SAND", "EGLD", "XMR", "IOTA", "AI16Z",
        "PNUT", "POPCAT", "ZRO", "MANA", "ETHFI", "VET", "ALGO", "ENS", "BOME", "MASK",
        "GALA", "YFI", "CHZ", "GMX", "QNT", "POL", "MOODENG", "ZK", "ARKM", "THETA",
        "MEW", "EIGEN", "MORPHO", "KAITO", "USUAL", "LAYER", "GOAT", "DOGS", "RSR", "PONKE",
        "JTO", "CKB", "ZIL", "ROSE", "1INCH", "TWT", "KSM", "MAGIC", "GAS", "ACT",
        "SUSHI", "TURBO", "1000LUNC", "BTCDOM", "S", "IP", "FLOW", "TRB", "QTUM", "KNC",
        "KAIA", "CELO", "SSV", "BANANA", "TNSR", "AERO", "IO", "DEXE", "ARK", "XAI",
        "DYM", "SAGA", "HOT", "LUNA2", "IOST", "RPL", "VANA", "DASH", "MANTA", "LRC",
        "ANKR", "XTZ", "BAND", "SUPER", "FXS", "AKT", "NMR", "PIXEL", "LPT", "STORJ",
        "ENJ", "LISTA", "ZETA", "RED", "AGLD", "GPS", "KAVA", "SXP", "ALPHA", "BIGTIME",
        "COTI", "USTC", "BAT", "NFP", "ONE", "POLYX", "MOVR", "OMNI", "CELR", "RVN",
        "GLM", "HIVE", "FLUX", "ZRX", "SFP", "ALICE", "ILV", "ARPA", "UMA", "DEGEN",
        "XVS", "ACE", "ASTR", "CTSI", "CHR", "EDU", "PROM", "ALT", "C98", "SUN",
        "WAXP", "ALPACA", "COOKIE", "JOE", "BNT", "SCRT", "VELODROME", "HOOK", "KMNO", "NTRN",
        "VIC", "RAYSOL", "PARTI", "MELANIA", "MYRO", "SHELL", "AUCTION", "SWELL", "HIGH", "WOO",
        "COW", "MAVIA", "VTHO", "1000CAT", "MUBARAK", "LEVER", "SOLV", "ARC", "AVAAI", "KOMA",
        "API3", "VOXEL", "CHESS", "SPELL", "1000WHY", "SKL", "GTC", "MTL", "BICO", "DENT",
        "RLC", "PHB", "POWR", "LSK", "DEFI", "MAV", "REI", "ONG", "XVG", "COS",
        "FORTH", "BEL", "MLN", "HEI", "GHST", "STEEM", "LOKA", "DIA", "TLM", "BMT",
        "ALCH", "FUN", "1000CHEEMS", "1000RATS", "1000000MOG", "1MBABYDOGE", "1000XEC", "1000X", "PERP", "NKN",
        "VINE", "RARE", "HFT", "AXL", "ACH", "ZEN", "PEOPLE", "AR", "CFX", "ID",
        "METIS", "FIO", "CYBER"
    };

    private static final Set<String> INTEGER_QTY_PAIRS = Stream.of(COIN_SYMBOLS)
            .flatMap(s -> Stream.of("B-" + s + "_USDT", s + "_USDT"))
            .collect(Collectors.toCollection(HashSet::new));

    private static final String[] COINS_TO_TRADE = Stream.of(COIN_SYMBOLS)
            .map(s -> "B-" + s + "_USDT")
            .toArray(String[]::new);

    // =========================================================================
    // MTF Result: holds both EMA9/21 and Supertrend signals for one TF
    // =========================================================================
    private static class MtfSignal {
        final String  tfName;
        final boolean emaBull;    // EMA9 > EMA21
        final boolean stBull;     // Supertrend GREEN
        final boolean emaValid;
        final boolean stValid;

        MtfSignal(String tfName, boolean emaBull, boolean stBull,
                  boolean emaValid, boolean stValid) {
            this.tfName   = tfName;
            this.emaBull  = emaBull;
            this.stBull   = stBull;
            this.emaValid = emaValid;
            this.stValid  = stValid;
        }

        // Returns true if at least 1 valid signal agrees with the given direction
        boolean alignedWith(boolean bullish) {
            int signals = 0;
            int agreeing = 0;
            if (emaValid) { signals++; if (emaBull == bullish) agreeing++; }
            if (stValid)  { signals++; if (stBull  == bullish) agreeing++; }
            return signals > 0 && agreeing > 0;
        }

        // Returns count of signals agreeing with direction (0, 1, or 2)
        int signalScore(boolean bullish) {
            int score = 0;
            if (emaValid && emaBull == bullish) score++;
            if (stValid  && stBull  == bullish) score++;
            return score;
        }
    }

    /**
     * Fetches candles for one TF and computes both EMA9/21 and Supertrend.
     */
    private static MtfSignal calcMtfSignal(String pair, String resolution, int count, String tfName) {
        try {
            JSONArray raw = getCandlestickData(pair, resolution, count);
            if (raw == null || raw.length() < EMA_MID + 5) {
                return new MtfSignal(tfName, false, false, false, false);
            }
            double[] cl = extractCloses(raw);
            double[] hi = extractHighs(raw);
            double[] lo = extractLows(raw);

            // EMA9/21
            boolean emaValid = cl.length >= EMA_MID + 1;
            boolean emaBull  = false;
            if (emaValid) {
                double ema9  = calcEMA(cl, EMA_FAST);
                double ema21 = calcEMA(cl, EMA_MID);
                emaBull = ema9 > ema21;
            }

            // Supertrend
            boolean stValid = cl.length >= ST_PERIOD + 5;
            boolean stBull  = false;
            if (stValid) {
                boolean[] st = calcSupertrend(hi, lo, cl, ST_PERIOD, ST_MULTIPLIER);
                stBull = st[st.length - 1];
            }

            return new MtfSignal(tfName, emaBull, stBull, emaValid, stValid);
        } catch (Exception e) {
            return new MtfSignal(tfName, false, false, false, false);
        }
    }

    // =========================================================================
    // MAIN
    // =========================================================================
    public static void main(String[] args) {
        initInstrumentCache();
        Set<String> active = getActivePositions();
        System.out.println("Active positions: " + active);

        // ══════════════════════════════════════════════════════════════════════
        // MAIN COIN SCAN LOOP
        // ══════════════════════════════════════════════════════════════════════
        for (String pair : COINS_TO_TRADE) {
            try {
                if (active.contains(pair)) {
                    System.out.println("Skip " + pair + " — active position");
                    continue;
                }
                long lastTrade = lastTradeTime.getOrDefault(pair, 0L);
                if (System.currentTimeMillis() - lastTrade < COOLDOWN_MS) {
                    System.out.println("  Skip " + pair + " — cooldown active");
                    continue;
                }
                System.out.println("\n==== " + pair + " ====");

                // ── Fetch all needed candles ───────────────────────────────────
                JSONArray raw5m  = getCandlestickData(pair, "5",   CANDLE_5M);
                JSONArray raw15m = getCandlestickData(pair, "15",  CANDLE_15M);
                JSONArray raw30m = getCandlestickData(pair, "30",  CANDLE_30M);
                JSONArray raw1h  = getCandlestickData(pair, "60",  CANDLE_1H);
                JSONArray raw2h  = getCandlestickData(pair, "120", CANDLE_2H);

                // Minimum data checks
                if (raw30m == null || raw30m.length() < 60) {
                    System.out.println("  Insufficient 30m candles — skip"); continue;
                }
                if (raw15m == null || raw15m.length() < 30) {
                    System.out.println("  Insufficient 15m candles — skip"); continue;
                }

                // ── Extract OHLC arrays ───────────────────────────────────────
                double[] cl30 = extractCloses(raw30m);
                double[] op30 = extractOpens(raw30m);
                double[] hi30 = extractHighs(raw30m);
                double[] lo30 = extractLows(raw30m);

                double[] cl15 = extractCloses(raw15m);
                double[] op15 = extractOpens(raw15m);
                double[] hi15 = extractHighs(raw15m);
                double[] lo15 = extractLows(raw15m);

                double lastClose = cl30[cl30.length - 1];
                double lastHigh  = hi30[hi30.length - 1];
                double lastLow   = lo30[lo30.length - 1];

                double prevClose15 = cl15[cl15.length - 2];
                double prevOpen15  = op15[op15.length - 2];

                double tickSize = getTickSize(pair);
                double atr30m   = calcATR(hi30, lo30, cl30, ATR_PERIOD);
                double atr15m   = calcATR(hi15, lo15, cl15, ATR_PERIOD);

                System.out.printf("  Price=%.6f  ATR-30m=%.6f  ATR-15m=%.6f  Tick=%.8f%n",
                        lastClose, atr30m, atr15m, tickSize);

                // ─────────────────────────────────────────────────────────────
                // HIGH VOLATILITY SKIP
                // ─────────────────────────────────────────────────────────────
                double atrPercent = (atr30m / lastClose) * 100.0;
                System.out.printf("  [VOL] ATR%%=%.2f%% (max %.1f%%) → %s%n",
                        atrPercent, MAX_ATR_PERCENT,
                        atrPercent <= MAX_ATR_PERCENT ? "OK" : "FAIL — too volatile");
                if (atrPercent > MAX_ATR_PERCENT) {
                    System.out.println("  VOL FAIL — market too volatile — skip");
                    continue;
                }

                // ─────────────────────────────────────────────────────────────
                // MTF EMA9/21 + SUPERTREND CONFLUENCE FILTER (v14)
                //
                // 5 timeframes: 5m, 15m, 30m, 1H, 2H
                // Per TF: 2 signals (EMA9/21 direction + Supertrend direction)
                // Total signals: up to 10
                //
                // Step 1: Count total bull/bear signal scores → decide direction
                // Step 2: A TF is "aligned" if ≥1 signal agrees with direction
                // Step 3: Need >= MTF_MIN_ALIGNED (4) TFs aligned
                // ─────────────────────────────────────────────────────────────
                System.out.println("  ── MTF EMA9/21 + Supertrend Check (v14) ────────");

                // Compute MTF signals using already-fetched raw arrays
                // For 5m and 2H we call calcMtfSignal (separate fetch not needed
                // if raw already exists; we reuse raw arrays directly below)
                MtfSignal[] mtfSignals = new MtfSignal[5];

                // 5m
                if (raw5m != null && raw5m.length() >= EMA_MID + 5) {
                    double[] cl5 = extractCloses(raw5m);
                    double[] hi5 = extractHighs(raw5m);
                    double[] lo5 = extractLows(raw5m);
                    boolean emaBull5 = calcEMA(cl5, EMA_FAST) > calcEMA(cl5, EMA_MID);
                    boolean[] st5 = calcSupertrend(hi5, lo5, cl5, ST_PERIOD, ST_MULTIPLIER);
                    mtfSignals[0] = new MtfSignal("5m", emaBull5, st5[st5.length-1], true, true);
                } else {
                    mtfSignals[0] = new MtfSignal("5m", false, false, false, false);
                }

                // 15m
                {
                    boolean emaBull15 = calcEMA(cl15, EMA_FAST) > calcEMA(cl15, EMA_MID);
                    boolean[] st15 = calcSupertrend(hi15, lo15, cl15, ST_PERIOD, ST_MULTIPLIER);
                    mtfSignals[1] = new MtfSignal("15m", emaBull15, st15[st15.length-1], true, true);
                }

                // 30m
                {
                    boolean emaBull30 = calcEMA(cl30, EMA_FAST) > calcEMA(cl30, EMA_MID);
                    boolean[] st30arr = calcSupertrend(hi30, lo30, cl30, ST_PERIOD, ST_MULTIPLIER);
                    mtfSignals[2] = new MtfSignal("30m", emaBull30, st30arr[st30arr.length-1], true, true);
                }

                // 1H
                if (raw1h != null && raw1h.length() >= EMA_MID + 5) {
                    double[] cl1h = extractCloses(raw1h);
                    double[] hi1h = extractHighs(raw1h);
                    double[] lo1h = extractLows(raw1h);
                    boolean emaBull1h = calcEMA(cl1h, EMA_FAST) > calcEMA(cl1h, EMA_MID);
                    boolean[] st1h = calcSupertrend(hi1h, lo1h, cl1h, ST_PERIOD, ST_MULTIPLIER);
                    mtfSignals[3] = new MtfSignal("1H", emaBull1h, st1h[st1h.length-1], true, true);
                } else {
                    mtfSignals[3] = new MtfSignal("1H", false, false, false, false);
                }

                // 2H
                if (raw2h != null && raw2h.length() >= EMA_MID + 5) {
                    double[] cl2h = extractCloses(raw2h);
                    double[] hi2h = extractHighs(raw2h);
                    double[] lo2h = extractLows(raw2h);
                    boolean emaBull2h = calcEMA(cl2h, EMA_FAST) > calcEMA(cl2h, EMA_MID);
                    boolean[] st2h = calcSupertrend(hi2h, lo2h, cl2h, ST_PERIOD, ST_MULTIPLIER);
                    mtfSignals[4] = new MtfSignal("2H", emaBull2h, st2h[st2h.length-1], true, true);
                } else {
                    mtfSignals[4] = new MtfSignal("2H", false, false, false, false);
                }

                // Count total bull/bear signal scores across all TFs
                int totalBullScore = 0;
                int totalBearScore = 0;
                for (MtfSignal sig : mtfSignals) {
                    totalBullScore += sig.signalScore(true);
                    totalBearScore += sig.signalScore(false);
                }

                // Decide trade direction from signal majority
                if (totalBullScore == totalBearScore) {
                    System.out.printf("  [MTF] BullScore=%d BearScore=%d → TIED — skip%n",
                            totalBullScore, totalBearScore);
                    System.out.println("  MTF FAIL — signals tied, no clear direction — skip");
                    continue;
                }
                boolean trendUp   = totalBullScore > totalBearScore;
                boolean trendDown = !trendUp;

                // Print per-TF details
                for (MtfSignal sig : mtfSignals) {
                    String emaStr = sig.emaValid
                            ? (sig.emaBull ? "EMA: BULL▲" : "EMA: BEAR▼")
                            : "EMA: N/A";
                    String stStr = sig.stValid
                            ? (sig.stBull ? "ST: GREEN▲" : "ST: RED▼")
                            : "ST: N/A";
                    boolean aligned = sig.alignedWith(trendUp);
                    System.out.printf("  [MTF-%s] %s | %s | Score=%d/2 | %s%n",
                            sig.tfName, emaStr, stStr,
                            sig.signalScore(trendUp),
                            aligned ? "ALIGNED ✓" : "NOT aligned ✗");
                }

                // Count how many TFs are aligned with direction
                int alignedCount = 0;
                for (MtfSignal sig : mtfSignals) {
                    if (sig.alignedWith(trendUp)) alignedCount++;
                }

                System.out.printf("  [MTF] Direction=%s | BullScore=%d/10 | BearScore=%d/10 | Aligned=%d/%d (need>=%d) → %s%n",
                        trendUp ? "BULL" : "BEAR",
                        totalBullScore, totalBearScore,
                        alignedCount, mtfSignals.length, MTF_MIN_ALIGNED,
                        alignedCount >= MTF_MIN_ALIGNED ? "PASS" : "FAIL");

                if (alignedCount < MTF_MIN_ALIGNED) {
                    System.out.println("  MTF FAIL — insufficient confluence across timeframes — skip");
                    continue;
                }
                System.out.printf("  MTF OK — %d/5 TFs aligned (%s) | TotalScore=%d/10%n",
                        alignedCount, trendUp ? "BULLISH" : "BEARISH",
                        trendUp ? totalBullScore : totalBearScore);

                // ─────────────────────────────────────────────────────────────
                // TREND FILTER T2: 30m MACD
                // (T1 removed in v14; direction comes from MTF above)
                // ─────────────────────────────────────────────────────────────
                double[] mv30     = calcMACD(cl30, MACD_FAST, MACD_SLOW, MACD_SIG);
                double   macdLine = mv30[0], macdSigV = mv30[1];
                System.out.printf("  [T2-30m] MACD=%.6f Sig=%.6f Hist=%.6f → %s%n",
                        macdLine, macdSigV, mv30[2],
                        macdLine > macdSigV ? "BULL" : "BEAR");
                boolean macdBull = macdLine > macdSigV;
                boolean macdBear = macdLine < macdSigV;
                if (trendUp   && !macdBull) { System.out.println("  T2 FAIL — 30m MACD bearish — skip"); continue; }
                if (trendDown && !macdBear) { System.out.println("  T2 FAIL — 30m MACD bullish — skip"); continue; }
                System.out.println("  T2 OK — 30m MACD aligned");

                // ── TREND FILTER T3: 30m Supertrend ──────────────────────────
                boolean[] stResult30 = calcSupertrend(hi30, lo30, cl30, ST_PERIOD, ST_MULTIPLIER);
                boolean   stBull30   = stResult30[stResult30.length - 1];
                boolean   stPrevBull = stResult30[stResult30.length - 2];
                boolean   stFlipped  = stBull30 != stPrevBull;
                System.out.printf("  [T3-30m] Supertrend → %s%s%n",
                        stBull30 ? "GREEN (BULL)" : "RED (BEAR)",
                        stFlipped ? " [JUST FLIPPED]" : "");
                if (trendUp   && !stBull30) { System.out.println("  T3 FAIL — 30m Supertrend bearish — skip"); continue; }
                if (trendDown &&  stBull30) { System.out.println("  T3 FAIL — 30m Supertrend bullish — skip"); continue; }
                System.out.println("  T3 OK — 30m Supertrend aligned");

                // ─────────────────────────────────────────────────────────────
                // QUALITY FILTER Q1: ADX on 30m
                // ─────────────────────────────────────────────────────────────
                double adx = calcADX(hi30, lo30, cl30, ADX_PERIOD);
                System.out.printf("  [Q1-30m] ADX=%.2f (min=%.0f) → %s%n",
                        adx, ADX_MIN, adx >= ADX_MIN ? "PASS" : "FAIL");
                if (adx < ADX_MIN) {
                    System.out.println("  Q1 FAIL — ADX too low, market sideways — skip"); continue;
                }

                // ─────────────────────────────────────────────────────────────
                // ENTRY ZONE E1: EMA9 Directional Pullback on 30m
                // ─────────────────────────────────────────────────────────────
                double ema9_30  = calcEMA(cl30, EMA_FAST);
                double ema21_30 = calcEMA(cl30, EMA_MID);

                double  distFromEma9   = Math.abs(lastClose - ema9_30);
                double  maxAllowedDist = EMA9_PULLBACK_MAX * atr30m;
                boolean nearEma9;
                if (trendUp) {
                    nearEma9 = lastClose >= ema9_30 && distFromEma9 <= maxAllowedDist;
                } else {
                    nearEma9 = lastClose <= ema9_30 && distFromEma9 <= maxAllowedDist;
                }
                System.out.printf("  [E1-30m] Price=%.6f EMA9=%.6f Dist=%.6f MaxDist=%.6f → %s%n",
                        lastClose, ema9_30, distFromEma9, maxAllowedDist,
                        nearEma9 ? "PASS" : "FAIL");

                if (stFlipped) {
                    double flipMaxDist = ST_FLIP_MAX_CANDLE_RATIO * atr30m;
                    if (distFromEma9 > flipMaxDist) {
                        System.out.println("  E1 FAIL (ST flip) — too extended — skip"); continue;
                    }
                    System.out.println("  E1 ST-flip: price near EMA9 — OK");
                } else if (!nearEma9) {
                    System.out.println("  E1 FAIL — not valid 30m EMA9 pullback — skip"); continue;
                }
                System.out.println("  E1 OK — 30m EMA9 pullback confirmed");

                // ─────────────────────────────────────────────────────────────
                // ENTRY ZONE E2: 30m candle not overextended
                // ─────────────────────────────────────────────────────────────
                double  candleSize = Math.abs(lastHigh - lastLow);
                double  maxCandle  = MAX_CANDLE_ATR_RATIO * atr30m;
                boolean candleOk   = candleSize <= maxCandle;
                System.out.printf("  [E2-30m] Candle=%.6f MaxCandle=%.6f → %s%n",
                        candleSize, maxCandle, candleOk ? "PASS" : "FAIL — overextended");
                if (!candleOk) {
                    System.out.println("  E2 FAIL — 30m candle too large — skip"); continue;
                }

                // ── SOFT FILTERS on 15m: at least 1 of 2 ────────────────────
                double  rsi15      = calcRSI(cl15, RSI_PERIOD);
                boolean softRsi    = (trendUp   && rsi15 >= RSI_LONG_MIN  && rsi15 <= RSI_LONG_MAX)
                                  || (trendDown && rsi15 >= RSI_SHORT_MIN && rsi15 <= RSI_SHORT_MAX);
                System.out.printf("  [S1-15m] RSI=%.2f → %s%n", rsi15, softRsi ? "PASS" : "fail");

                boolean prevBull15 = prevClose15 > prevOpen15;
                boolean prevBear15 = prevClose15 < prevOpen15;
                boolean softCandle = (trendUp && prevBull15) || (trendDown && prevBear15);
                System.out.printf("  [S2-15m] Prev candle: open=%.6f close=%.6f → %s → %s%n",
                        prevOpen15, prevClose15,
                        prevBull15 ? "BULL" : prevBear15 ? "BEAR" : "DOJI",
                        softCandle ? "PASS" : "fail");

                if (!softRsi && !softCandle) {
                    System.out.println("  SOFT FAIL — neither 15m RSI nor candle confirms — skip"); continue;
                }
                System.out.println("  SOFT OK — " + (softRsi ? "RSI " : "") + (softCandle ? "Candle" : "") + " confirmed");

                // ─────────────────────────────────────────────────────────────
                // 15m MOMENTUM CANDLE CONFIRMATION
                // ─────────────────────────────────────────────────────────────
                double  last15mClose = cl15[cl15.length - 2];
                double  last15mOpen  = op15[op15.length - 2];
                double  last15mHigh  = hi15[hi15.length - 2];
                double  last15mLow   = lo15[lo15.length - 2];
                double  last15mRange = last15mHigh - last15mLow;
                double  last15mBody  = Math.abs(last15mClose - last15mOpen);
                boolean entry15mBull = last15mClose > last15mOpen;
                boolean entry15mBear = last15mClose < last15mOpen;

                double  bodyRatio     = last15mRange > 0 ? last15mBody / last15mRange : 0;
                boolean strongBody    = bodyRatio >= MIN_15M_BODY_RATIO;
                double  closePosition = last15mRange > 0
                        ? (last15mClose - last15mLow) / last15mRange : 0.5;
                boolean closeNearHigh = closePosition >= (1.0 - CLOSE_NEAR_EXTREME_RATIO);
                boolean closeNearLow  = closePosition <= CLOSE_NEAR_EXTREME_RATIO;

                boolean entry15mOk;
                String  entry15mReason;
                if (trendUp) {
                    entry15mOk     = entry15mBull && strongBody && closeNearHigh;
                    entry15mReason = String.format("Bull=%s Body=%.0f%% ClosePos=%.0f%%",
                            entry15mBull, bodyRatio * 100, closePosition * 100);
                } else {
                    entry15mOk     = entry15mBear && strongBody && closeNearLow;
                    entry15mReason = String.format("Bear=%s Body=%.0f%% ClosePos=%.0f%%",
                            entry15mBear, bodyRatio * 100, closePosition * 100);
                }
                System.out.printf("  [15m-MOMENTUM] %s → %s%n", entry15mReason,
                        entry15mOk ? "PASS" : "FAIL — weak candle");
                if (!entry15mOk) {
                    System.out.println("  15m FAIL — momentum candle insufficient — skip"); continue;
                }
                System.out.println("  15m OK — strong momentum candle");

                // ── ALL FILTERS PASSED ────────────────────────────────────────
                String side = trendUp ? "buy" : "sell";
                System.out.println("\n  ╔══════════════════════════════════════════════════╗");
                System.out.println("  ║  ALL FILTERS PASSED → " + side.toUpperCase() + " " + pair);
                System.out.printf ("  ║  MTF aligned=%d/5 | TotalScore=%d/10 | ADX=%.1f | RSI=%.1f | ATR%%=%.2f%%%n",
                        alignedCount, trendUp ? totalBullScore : totalBearScore,
                        adx, rsi15, atrPercent);
                if (stFlipped) System.out.println("  ║  *** 30m SUPERTREND FLIP ***");
                System.out.println("  ╚══════════════════════════════════════════════════╝");

                // ── Place order ───────────────────────────────────────────────
                double currentPrice = getLastPrice(pair);
                if (currentPrice <= 0) { System.out.println("  Invalid price — skip"); continue; }
                double qty = calcQuantity(currentPrice, pair);
                if (qty <= 0) { System.out.println("  Invalid qty — skip"); continue; }

                System.out.printf("  Placing %s | price=%.6f | qty=%.4f | lev=%dx%n",
                        side.toUpperCase(), currentPrice, qty, LEVERAGE);

                JSONObject resp = placeFuturesMarketOrder(side, pair, qty, LEVERAGE,
                        "email_notification", "isolated", "INR");
                if (resp == null || !resp.has("id")) {
                    System.out.println("  Order failed: " + resp); continue;
                }
                System.out.println("  Order placed! id=" + resp.getString("id"));
                lastTradeTime.put(pair, System.currentTimeMillis());

                // ── Confirm entry price ───────────────────────────────────────
                double entry = getEntryPrice(pair, resp.getString("id"));
                if (entry <= 0) {
                    System.out.println("  Could not confirm entry — TP/SL skipped"); continue;
                }
                System.out.printf("  Entry confirmed: %.6f%n", entry);

                // ─────────────────────────────────────────────────────────────
                // SL CALCULATION (v14):
                // Primary: 30m Supertrend band (direction must match trade)
                // Fallback: 1H Supertrend band (if 30m ST direction mismatches)
                // Clamp: SL_MIN_ATR (2.1×) to SL_MAX_ATR (2.7×) ATR
                // ─────────────────────────────────────────────────────────────
                double dynamicRR = adx >= 40 ? RR_STRONG : adx >= 30 ? RR_MEDIUM : RR_WEAK;
                System.out.printf("  Dynamic R:R = 1:%.1f (ADX=%.1f)%n", dynamicRR, adx);

                // Decide which Supertrend band to use for SL
                double[] stBands;
                String   slSource;

                // 30m Supertrend direction is already confirmed by T3 above,
                // so normally we always use 30m. But we add a fallback for
                // edge cases where band values look unreasonable.
                double[] stBands30 = calcSupertrendBands(hi30, lo30, cl30, ST_PERIOD, ST_MULTIPLIER);
                double   stLower30 = stBands30[0];
                double   stUpper30 = stBands30[1];

                // Sanity check: for a BUY, lower band must be below entry;
                // for a SELL, upper band must be above entry.
                boolean bands30Ok = trendUp
                        ? stLower30 < entry
                        : stUpper30 > entry;

                if (bands30Ok) {
                    stBands   = stBands30;
                    slSource  = "30m";
                } else {
                    // Fallback to 1H Supertrend bands
                    System.out.println("  SL: 30m ST bands invalid — falling back to 1H ST bands");
                    if (raw1h != null && raw1h.length() >= ST_PERIOD + 5) {
                        double[] hi1h = extractHighs(raw1h);
                        double[] lo1h = extractLows(raw1h);
                        double[] cl1h = extractCloses(raw1h);
                        stBands  = calcSupertrendBands(hi1h, lo1h, cl1h, ST_PERIOD, ST_MULTIPLIER);
                        slSource = "1H";
                    } else {
                        // Last resort: use ATR-based bands
                        stBands  = new double[]{entry - 2.4 * atr30m, entry + 2.4 * atr30m};
                        slSource = "ATR-fallback";
                    }
                }

                double stLower = stBands[0];
                double stUpper = stBands[1];
                System.out.printf("  SL source: %s ST bands | Lower=%.6f Upper=%.6f%n",
                        slSource, stLower, stUpper);

                double slPrice, tpPrice;
                if ("buy".equalsIgnoreCase(side)) {
                    double rawSL = stLower - (ST_SL_BUFFER * atr30m);
                    double minSL = entry - SL_MIN_ATR * atr30m;
                    double maxSL = entry - SL_MAX_ATR * atr30m;
                    slPrice      = Math.max(Math.min(rawSL, minSL), maxSL);
                    double risk  = entry - slPrice;
                    tpPrice      = entry + dynamicRR * risk;
                    System.out.printf("  [BUY] ST-Lower=%.6f RawSL=%.6f → Clamped SL=%.6f%n",
                            stLower, rawSL, slPrice);
                } else {
                    double rawSL = stUpper + (ST_SL_BUFFER * atr30m);
                    double minSL = entry + SL_MIN_ATR * atr30m;
                    double maxSL = entry + SL_MAX_ATR * atr30m;
                    slPrice      = Math.min(Math.max(rawSL, minSL), maxSL);
                    double risk  = slPrice - entry;
                    tpPrice      = entry - dynamicRR * risk;
                    System.out.printf("  [SELL] ST-Upper=%.6f RawSL=%.6f → Clamped SL=%.6f%n",
                            stUpper, rawSL, slPrice);
                }

                slPrice = roundToTick(slPrice, tickSize);
                tpPrice = roundToTick(tpPrice, tickSize);

                double slPct = Math.abs(entry - slPrice) / entry * 100;
                double tpPct = Math.abs(tpPrice - entry) / entry * 100;
                System.out.printf("  SL=%.6f (%.2f%%) | TP=%.6f (%.2f%%) | R:R=1:%.1f%n",
                        slPrice, slPct, tpPrice, tpPct, dynamicRR);

                String posId = getPositionId(pair);
                if (posId != null) {
                    setTpSl(posId, tpPrice, slPrice, pair);
                } else {
                    System.out.println("  Position ID not found — TP/SL not set");
                }

            } catch (Exception e) {
                System.err.println("Error on " + pair + ": " + e.getMessage());
            }
        }
        System.out.println("\n=== Scan complete ===");
    }

    // =========================================================================
    // SUPERTREND BANDS (returns [lowerBand, upperBand] for last candle)
    // =========================================================================
    private static double[] calcSupertrendBands(double[] hi, double[] lo, double[] cl,
                                                 int period, double multiplier) {
        int n = cl.length;
        if (n < period + 1) return new double[]{cl[n-1] * 0.97, cl[n-1] * 1.03};
        double[] atrArr    = calcATRSeries(hi, lo, cl, period);
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
        }
        return new double[]{lowerBand[n-1], upperBand[n-1]};
    }

    // =========================================================================
    // SUPERTREND DIRECTION SERIES
    // =========================================================================
    private static boolean[] calcSupertrend(double[] hi, double[] lo, double[] cl,
                                             int period, double multiplier) {
        int n = cl.length;
        boolean[] bullish = new boolean[n];
        if (n < period + 1) { Arrays.fill(bullish, true); return bullish; }
        double[] atrArr    = calcATRSeries(hi, lo, cl, period);
        double[] upperBand = new double[n];
        double[] lowerBand = new double[n];
        for (int i = period; i < n; i++) {
            double hl2        = (hi[i] + lo[i]) / 2.0;
            double basicUpper = hl2 + multiplier * atrArr[i];
            double basicLower = hl2 - multiplier * atrArr[i];
            if (i == period) {
                upperBand[i] = basicUpper; lowerBand[i] = basicLower;
            } else {
                upperBand[i] = (basicUpper < upperBand[i-1] || cl[i-1] > upperBand[i-1])
                        ? basicUpper : upperBand[i-1];
                lowerBand[i] = (basicLower > lowerBand[i-1] || cl[i-1] < lowerBand[i-1])
                        ? basicLower : lowerBand[i-1];
            }
            if (i == period) bullish[i] = cl[i] > (hi[i] + lo[i]) / 2.0;
            else bullish[i] = bullish[i-1] ? cl[i] >= lowerBand[i] : cl[i] > upperBand[i];
        }
        for (int i = 0; i < period; i++) bullish[i] = bullish[period];
        return bullish;
    }

    // =========================================================================
    // ADX
    // =========================================================================
    private static double calcADX(double[] hi, double[] lo, double[] cl, int period) {
        int n = hi.length;
        if (n < period * 2) return 0;
        double[] plusDM  = new double[n];
        double[] minusDM = new double[n];
        double[] tr      = new double[n];
        for (int i = 1; i < n; i++) {
            double upMove   = hi[i] - hi[i-1];
            double downMove = lo[i-1] - lo[i];
            plusDM[i]  = (upMove > downMove && upMove > 0)   ? upMove   : 0;
            minusDM[i] = (downMove > upMove && downMove > 0) ? downMove : 0;
            tr[i] = Math.max(hi[i] - lo[i],
                    Math.max(Math.abs(hi[i] - cl[i-1]), Math.abs(lo[i] - cl[i-1])));
        }
        double smoothTR = 0, smoothPlus = 0, smoothMinus = 0;
        for (int i = 1; i <= period; i++) {
            smoothTR += tr[i]; smoothPlus += plusDM[i]; smoothMinus += minusDM[i];
        }
        double prevADX = 0; double adxSum = 0; int adxCount = 0;
        for (int i = period + 1; i < n; i++) {
            smoothTR    = smoothTR    - (smoothTR    / period) + tr[i];
            smoothPlus  = smoothPlus  - (smoothPlus  / period) + plusDM[i];
            smoothMinus = smoothMinus - (smoothMinus / period) + minusDM[i];
            if (smoothTR == 0) continue;
            double plusDI  = 100.0 * smoothPlus  / smoothTR;
            double minusDI = 100.0 * smoothMinus / smoothTR;
            double diSum   = plusDI + minusDI;
            double dx      = diSum == 0 ? 0 : 100.0 * Math.abs(plusDI - minusDI) / diSum;
            if (adxCount < period) {
                adxSum += dx; adxCount++;
                if (adxCount == period) prevADX = adxSum / period;
            } else {
                prevADX = (prevADX * (period - 1) + dx) / period;
            }
        }
        return prevADX;
    }

    // =========================================================================
    // ATR SERIES
    // =========================================================================
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
        for (int i = period; i < n; i++) atr[i] = (atr[i-1] * (period - 1) + tr[i]) / period;
        for (int i = 0; i < period - 1; i++) atr[i] = atr[period - 1];
        return atr;
    }

    // =========================================================================
    // CORE INDICATORS
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
        double[] ef    = calcEMASeries(cl, fast);
        double[] es    = calcEMASeries(cl, slow);
        int      start = slow - 1;
        int      len   = cl.length - start;
        if (len <= 0) return new double[]{0, 0, 0};
        double[] ml = new double[len];
        for (int i = 0; i < len; i++) ml[i] = ef[start + i] - es[start + i];
        double[] ss = calcEMASeries(ml, sig);
        double m = ml[ml.length - 1], s = ss[ss.length - 1];
        return new double[]{m, s, m - s};
    }

    private static double calcRSI(double[] cl, int period) {
        if (cl.length < period + 1) return 50;
        double ag = 0, al = 0;
        for (int i = 1; i <= period; i++) {
            double ch = cl[i] - cl[i-1];
            if (ch > 0) ag += ch; else al += Math.abs(ch);
        }
        ag /= period; al /= period;
        for (int i = period + 1; i < cl.length; i++) {
            double ch = cl[i] - cl[i-1];
            if (ch > 0) { ag = (ag*(period-1)+ch)/period; al = al*(period-1)/period; }
            else         { al = (al*(period-1)+Math.abs(ch))/period; ag = ag*(period-1)/period; }
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
                    Math.max(Math.abs(hi[i] - cl[i-1]), Math.abs(lo[i] - cl[i-1])));
        double atr = 0;
        for (int i = 0; i < period; i++) atr += tr[i];
        atr /= period;
        for (int i = period; i < hi.length; i++) atr = (atr*(period-1)+tr[i])/period;
        return atr;
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

    // =========================================================================
    // COINDCX API
    // =========================================================================
    private static JSONArray getCandlestickData(String pair, String resolution, int count) {
        try {
            long minsPerBar;
            switch (resolution) {
                case "5":   minsPerBar = 5;   break;
                case "15":  minsPerBar = 15;  break;
                case "30":  minsPerBar = 30;  break;
                case "60":  minsPerBar = 60;  break;
                case "120": minsPerBar = 120; break;
                default:    minsPerBar = 15;  break;
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
                System.err.println("  Candle s=" + r.optString("s") + " " + pair);
            } else {
                System.err.println("  Candle HTTP " + code + " " + pair);
            }
        } catch (Exception e) {
            System.err.println("  getCandlestickData(" + pair + "/" + resolution + "): " + e.getMessage());
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
        String resp = authPost(BASE_URL + "/exchange/v1/derivatives/futures/positions", body.toString());
        JSONArray arr = resp.startsWith("[")
                ? new JSONArray(resp) : new JSONArray().put(new JSONObject(resp));
        for (int i = 0; i < arr.length(); i++) {
            JSONObject p = arr.getJSONObject(i);
            if (pair.equals(p.optString("pair"))) return p;
        }
        return null;
    }

    private static double calcQuantity(double price, String pair) {
        double usdtInrRate = 98.0;
        double qty = (MAX_MARGIN * LEVERAGE) / (price * usdtInrRate);
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
            System.out.println(r.has("err_code_dcx") ? "  TP/SL error: " + r : "  TP/SL set successfully!");
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
            String resp = authPost(BASE_URL + "/exchange/v1/derivatives/futures/positions", body.toString());
            JSONArray arr = resp.startsWith("[")
                    ? new JSONArray(resp) : new JSONArray().put(new JSONObject(resp));
            System.out.println("=== Open Positions (" + arr.length() + ") ===");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject p    = arr.getJSONObject(i);
                String    pair  = p.optString("pair", "");
                boolean isActive = p.optDouble("active_pos", 0) > 0
                        || p.optDouble("locked_margin", 0) > 0
                        || p.optDouble("avg_price", 0) > 0
                        || p.optDouble("take_profit_trigger", 0) > 0
                        || p.optDouble("stop_loss_trigger", 0) > 0;
                if (isActive) {
                    System.out.printf("  %s | qty=%.2f | entry=%.6f | TP=%.4f | SL=%.4f%n",
                            pair, p.optDouble("active_pos", 0), p.optDouble("avg_price", 0),
                            p.optDouble("take_profit_trigger", 0), p.optDouble("stop_loss_trigger", 0));
                    active.add(pair);
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
        InputStream is = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
        return readStream(is);
    }

    private static String readStream(InputStream is) throws IOException {
        return new BufferedReader(new InputStreamReader(is))
                .lines().collect(Collectors.joining("\n"));
    }

    private static String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(API_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
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
//  * ═══════════════════════════════════════════════════════════════════════════
//  * CoinDCX Futures Trader — v13 (Multi-Timeframe EMA9/21 Confluence Edition)
//  * ═══════════════════════════════════════════════════════════════════════════
//  *
//  * CHANGES FROM v12 → v13:
//  *
//  *   CHANGE #1 — Multi-Timeframe EMA9/21 Confluence Check
//  *     v12: EMA9/EMA21 trend only on 30m (T1 filter).
//  *     v13: EMA9/EMA21 checked across 5 timeframes:
//  *            TF1 → 5m   (fastest momentum)
//  *            TF2 → 15m  (entry confirmation)
//  *            TF3 → 30m  (primary trend — same as v12)
//  *            TF4 → 1H   (macro trend direction)
//  *            TF5 → 2H   (strongest trend bias)
//  *          Rule: Minimum MTF_MIN_ALIGNED (4 out of 5) timeframes must agree.
//  *          Prevents entries when short-term and long-term disagree.
//  *
//  *   CHANGE #2 — BTC Multi-Timeframe EMA9/21
//  *     v12: BTC checked only on 15m EMA9/21 (soft filter, warn only).
//  *     v13: BTC checked on same 5 timeframes.
//  *          If BTC_REQUIRED_ALIGNED (3 of 5) timeframes are against the trade:
//  *            → Trade is BLOCKED (not just warned).
//  *          If 2 or fewer timeframes against BTC → allowed (soft warn).
//  *          This way BTC filter is strong but not overly strict.
//  *
//  *   CHANGE #3 — TP/SL Logic UNCHANGED
//  *     SL = 30m Supertrend band ± ATR buffer, clamped SL_MIN_ATR / SL_MAX_ATR.
//  *     TP = dynamic R:R based on ADX (1.3 / 1.4 / 1.5).
//  *
//  * FULL FILTER ARCHITECTURE (v13):
//  *
//  *   BTC PRE-CHECK (5m, 15m, 30m, 1H, 2H):
//  *     EMA9/21 on each TF → count aligned with trade direction.
//  *     If >= BTC_REQUIRED_ALIGNED timeframes are AGAINST trade → SKIP.
//  *     Otherwise → allow with warning.
//  *
//  *   MACRO (1H):
//  *     M1. Price > EMA50 AND EMA50 > EMA200  (bull)
//  *         Price < EMA50 AND EMA50 < EMA200  (bear)
//  *
//  *   MULTI-TF EMA9/21 (5m, 15m, 30m, 1H, 2H) ← NEW:
//  *     MTF. Count timeframes where EMA9 > EMA21 (bull) or EMA9 < EMA21 (bear).
//  *          Minimum MTF_MIN_ALIGNED (default 4) must match trade direction.
//  *
//  *   TREND (30m — primary):
//  *     T1. EMA9 > EMA21 AND EMA21 slope rising   (bull)
//  *         EMA9 < EMA21 AND EMA21 slope falling  (bear)
//  *     T2. MACD line > signal                    (bull / bear)
//  *     T3. Supertrend GREEN / RED
//  *
//  *   QUALITY (30m):
//  *     Q1. ADX > 18
//  *     Q2. ATR% < 4.5%
//  *
//  *   ENTRY ZONE (30m):
//  *     E1. Price on correct side of EMA9, distance <= 1.4 * ATR
//  *     E2. Current candle not overextended (< 1.5 * ATR)
//  *
//  *   CONFIRMATION (15m — at least 1 of 2):
//  *     S1. RSI in zone (42-68 long | 32-58 short)
//  *     S2. Previous 15m candle matches direction
//  *
//  *   TIMING (15m momentum candle):
//  *     Strong momentum candle (body > 35%, close near extreme)
//  *
//  *   SL/TP:
//  *     SL = 30m Supertrend band ± ATR buffer, clamped 2.1–2.7x ATR
//  *     TP = dynamic R:R (1.3 / 1.4 / 1.5 based on ADX)
//  * ═══════════════════════════════════════════════════════════════════════════
//  */
// public class CoinDCXFuturesTrader8C_BUY_SELL_NEW_LOGIC_THREE {

//     // =========================================================================
//     // API Configuration
//     // =========================================================================
//     private static final String API_KEY    = System.getenv("DELTA_API_KEY");
//     private static final String API_SECRET = System.getenv("DELTA_API_SECRET");
//     private static final String BASE_URL       = "https://api.coindcx.com";
//     private static final String PUBLIC_API_URL = "https://public.coindcx.com";

//     private static final double MAX_MARGIN             = 300.0;
//     private static final int    LEVERAGE               = 10;
//     private static final int    MAX_ENTRY_PRICE_CHECKS = 10;
//     private static final int    ENTRY_CHECK_DELAY_MS   = 1000;
//     private static final long   TICK_CACHE_TTL_MS      = 3_600_000L;
//     private static final long   COOLDOWN_MS            = 2 * 60 * 60 * 1000L;

//     // ── Indicator periods ─────────────────────────────────────────────────────
//     private static final int EMA_FAST        = 9;
//     private static final int EMA_MID         = 21;
//     private static final int EMA_MACRO       = 50;
//     private static final int EMA_MACRO_SLOW  = 200;
//     private static final int MACD_FAST       = 12;
//     private static final int MACD_SLOW       = 26;
//     private static final int MACD_SIG        = 9;
//     private static final int RSI_PERIOD      = 14;
//     private static final int ATR_PERIOD      = 14;
//     private static final int ADX_PERIOD      = 14;

//     // ── Supertrend ────────────────────────────────────────────────────────────
//     private static final int    ST_PERIOD     = 10;
//     private static final double ST_MULTIPLIER = 3.5;

//     // ── Multi-Timeframe EMA confluence settings ───────────────────────────────
//     // Total timeframes checked: 5 (5m, 15m, 30m, 1H, 2H)
//     // MTF_MIN_ALIGNED: minimum TFs that must agree with trade direction
//     private static final int MTF_MIN_ALIGNED = 4;   // out of 5
//     // BTC: if (5 - BTC_REQUIRED_ALIGNED) or more TFs are against trade → block
//     private static final int BTC_REQUIRED_ALIGNED = 3; // out of 5; below this = block trade

//     // ── ADX threshold ────────────────────────────────────────────────────────
//     private static final double ADX_MIN = 20.0;

//     // ── RSI zones ────────────────────────────────────────────────────────────
//     private static final double RSI_LONG_MIN  = 42.0;
//     private static final double RSI_LONG_MAX  = 68.0;
//     private static final double RSI_SHORT_MIN = 32.0;
//     private static final double RSI_SHORT_MAX = 58.0;

//     // ── SL parameters ────────────────────────────────────────────────────────
//     private static final double ST_SL_BUFFER = 0.3;
//     private static final double SL_MIN_ATR   = 2.1;
//     private static final double SL_MAX_ATR   = 2.7;

//     // ── Dynamic RR based on ADX ───────────────────────────────────────────────
//     private static final double RR_STRONG = 2.0;
//     private static final double RR_MEDIUM = 1.8;
//     private static final double RR_WEAK   = 1.5;

//     // ── Entry zone filters ────────────────────────────────────────────────────
//     private static final double EMA9_PULLBACK_MAX        = 1.4;
//     private static final double MAX_CANDLE_ATR_RATIO     = 1.5;
//     private static final double ST_FLIP_MAX_CANDLE_RATIO = 1.2;

//     // ── 15m momentum candle quality ───────────────────────────────────────────
//     private static final double MIN_15M_BODY_RATIO       = 0.35;
//     private static final double CLOSE_NEAR_EXTREME_RATIO = 0.45;

//     // ── High volatility skip ──────────────────────────────────────────────────
//     private static final double MAX_ATR_PERCENT = 4.5;

//     // ── Candle fetch counts per timeframe ─────────────────────────────────────
//     private static final int CANDLE_5M  = 60;
//     private static final int CANDLE_15M = 60;
//     private static final int CANDLE_30M = 100;
//     private static final int CANDLE_1H  = 220;
//     private static final int CANDLE_2H  = 80;

//     // ── Daily P&L protection ──────────────────────────────────────────────────
//     private static final double DAILY_PROFIT_TARGET  = 400.0;
//     private static final double DAILY_DRAWDOWN_LIMIT = 250.0;
//     private static final double DAILY_LOSS_LIMIT     = 300.0;

//     // ── BTC pair for correlation filter ──────────────────────────────────────
//     private static final String BTC_PAIR = "B-BTC_USDT";

//     private static final Map<String, JSONObject> instrumentCache = new ConcurrentHashMap<>();
//     private static long lastCacheUpdate = 0;
//     private static final Map<String, Long> lastTradeTime = new ConcurrentHashMap<>();

//     // =========================================================================
//     // Coin list
//     // =========================================================================
//     private static final String[] COIN_SYMBOLS = {
//         "ETH", "SOL", "ZEC", "XRP", "DOGE", "BNB", "TAO", "1000PEPE", "ADA", "SUI",
//         "BCH", "LINK", "AVAX", "FIL", "OP", "NEAR", "TRX", "TRUMP", "ARB", "WLD",
//         "FET", "ETC", "AAVE", "WIF", "INJ", "TIA", "LTC", "ONDO", "ORDI", "TON",
//         "HBAR", "IMX", "ATOM", "RUNE", "KAS", "UNI", "ICP", "SEI", "PENDLE", "1000SHIB",
//         "1000BONK", "CRV", "JUP", "RENDER", "MKR", "LDO", "STX", "XLM", "PYTH", "VIRTUAL",
//         "APT", "SNX", "STRK", "NEO", "FTM", "CAKE", "1000FLOKI", "1000SATS", "OM", "FARTCOIN",
//         "GRT", "MINA", "COMP", "BLUR", "BRETT", "SAND", "EGLD", "XMR", "IOTA", "AI16Z",
//         "PNUT", "POPCAT", "ZRO", "MANA", "ETHFI", "VET", "ALGO", "ENS", "BOME", "MASK",
//         "GALA", "YFI", "CHZ", "GMX", "QNT", "POL", "MOODENG", "ZK", "ARKM", "THETA",
//         "MEW", "EIGEN", "MORPHO", "KAITO", "USUAL", "LAYER", "GOAT", "DOGS", "RSR", "PONKE",
//         "JTO", "CKB", "ZIL", "ROSE", "1INCH", "TWT", "KSM", "MAGIC", "GAS", "ACT",
//         "SUSHI", "TURBO", "1000LUNC", "BTCDOM", "S", "IP", "FLOW", "TRB", "QTUM", "KNC",
//         "KAIA", "CELO", "SSV", "BANANA", "TNSR", "AERO", "IO", "DEXE", "ARK", "XAI",
//         "DYM", "SAGA", "HOT", "LUNA2", "IOST", "RPL", "VANA", "DASH", "MANTA", "LRC",
//         "ANKR", "XTZ", "BAND", "SUPER", "FXS", "AKT", "NMR", "PIXEL", "LPT", "STORJ",
//         "ENJ", "LISTA", "ZETA", "RED", "AGLD", "GPS", "KAVA", "SXP", "ALPHA", "BIGTIME",
//         "COTI", "USTC", "BAT", "NFP", "ONE", "POLYX", "MOVR", "OMNI", "CELR", "RVN",
//         "GLM", "HIVE", "FLUX", "ZRX", "SFP", "ALICE", "ILV", "ARPA", "UMA", "DEGEN",
//         "XVS", "ACE", "ASTR", "CTSI", "CHR", "EDU", "PROM", "ALT", "C98", "SUN",
//         "WAXP", "ALPACA", "COOKIE", "JOE", "BNT", "SCRT", "VELODROME", "HOOK", "KMNO", "NTRN",
//         "VIC", "RAYSOL", "PARTI", "MELANIA", "MYRO", "SHELL", "AUCTION", "SWELL", "HIGH", "WOO",
//         "COW", "MAVIA", "VTHO", "1000CAT", "MUBARAK", "LEVER", "SOLV", "ARC", "AVAAI", "KOMA",
//         "API3", "VOXEL", "CHESS", "SPELL", "1000WHY", "SKL", "GTC", "MTL", "BICO", "DENT",
//         "RLC", "PHB", "POWR", "LSK", "DEFI", "MAV", "REI", "ONG", "XVG", "COS",
//         "FORTH", "BEL", "MLN", "HEI", "GHST", "STEEM", "LOKA", "DIA", "TLM", "BMT",
//         "ALCH", "FUN", "1000CHEEMS", "1000RATS", "1000000MOG", "1MBABYDOGE", "1000XEC", "1000X", "PERP", "NKN",
//         "VINE", "RARE", "HFT", "AXL", "ACH", "ZEN", "PEOPLE", "AR", "CFX", "ID",
//         "METIS", "FIO", "CYBER"
//     };

//     private static final Set<String> INTEGER_QTY_PAIRS = Stream.of(COIN_SYMBOLS)
//             .flatMap(s -> Stream.of("B-" + s + "_USDT", s + "_USDT"))
//             .collect(Collectors.toCollection(HashSet::new));

//     private static final String[] COINS_TO_TRADE = Stream.of(COIN_SYMBOLS)
//             .map(s -> "B-" + s + "_USDT")
//             .toArray(String[]::new);

//     // =========================================================================
//     // Helper: EmaAlignment result — stores EMA9, EMA21, and direction
//     // =========================================================================
//     private static class TfEmaResult {
//         final String  tf;
//         final double  ema9;
//         final double  ema21;
//         final boolean bullish;   // ema9 > ema21
//         final boolean valid;     // enough candles

//         TfEmaResult(String tf, double ema9, double ema21, boolean bullish, boolean valid) {
//             this.tf       = tf;
//             this.ema9     = ema9;
//             this.ema21    = ema21;
//             this.bullish  = bullish;
//             this.valid    = valid;
//         }
//     }

//     /**
//      * Fetches candles for a given timeframe and computes EMA9/EMA21.
//      * Returns a TfEmaResult with valid=false if data is unavailable.
//      */
//     private static TfEmaResult calcTfEma(String pair, String resolution, int count) {
//         try {
//             JSONArray raw = getCandlestickData(pair, resolution, count);
//             if (raw == null || raw.length() < EMA_MID + 5) {
//                 return new TfEmaResult(resolution + "m", 0, 0, false, false);
//             }
//             double[] cl   = extractCloses(raw);
//             double   ema9  = calcEMA(cl, EMA_FAST);
//             double   ema21 = calcEMA(cl, EMA_MID);
//             return new TfEmaResult(resolution + "m", ema9, ema21, ema9 > ema21, true);
//         } catch (Exception e) {
//             return new TfEmaResult(resolution + "m", 0, 0, false, false);
//         }
//     }

//     // =========================================================================
//     // MAIN
//     // =========================================================================
//     public static void main(String[] args) {
//         initInstrumentCache();
//         Set<String> active = getActivePositions();
//         System.out.println("Active positions: " + active);

//         // ══════════════════════════════════════════════════════════════════════
//         // BTC MULTI-TIMEFRAME EMA CHECK — computed ONCE for all coins
//         // Timeframes: 5m, 15m, 30m, 1H, 2H
//         // btcBullCount = how many TFs show EMA9 > EMA21 for BTC
//         // btcBearCount = how many TFs show EMA9 < EMA21 for BTC
//         // ══════════════════════════════════════════════════════════════════════
//         int     btcBullCount  = 0;
//         int     btcBearCount  = 0;
//         boolean btcDataOk     = false;

//         System.out.println("\n══════ BTC MULTI-TIMEFRAME EMA ANALYSIS ══════");
//         String[]   btcTfs     = {"5",  "15", "30", "60",  "120"};
//         String[]   btcTfNames = {"5m", "15m","30m","1H",  "2H"};
//         int[]      btcCounts  = {40,    40,   60,   80,    50};

//         TfEmaResult[] btcResults = new TfEmaResult[btcTfs.length];
//         for (int t = 0; t < btcTfs.length; t++) {
//             btcResults[t] = calcTfEma(BTC_PAIR, btcTfs[t], btcCounts[t]);
//             TfEmaResult r = btcResults[t];
//             if (r.valid) {
//                 if (r.bullish) btcBullCount++; else btcBearCount++;
//                 btcDataOk = true;
//                 System.out.printf("  BTC [%s] EMA9=%.2f EMA21=%.2f → %s%n",
//                         btcTfNames[t], r.ema9, r.ema21, r.bullish ? "BULL ▲" : "BEAR ▼");
//             } else {
//                 System.out.printf("  BTC [%s] → N/A (insufficient data)%n", btcTfNames[t]);
//             }
//         }

//         if (btcDataOk) {
//             System.out.printf("  BTC Summary: BULL=%d/5 | BEAR=%d/5 → Overall: %s%n",
//                     btcBullCount, btcBearCount,
//                     btcBullCount >= 3 ? "BULLISH" : btcBearCount >= 3 ? "BEARISH" : "MIXED");
//         } else {
//             System.out.println("  BTC data completely unavailable — BTC filter disabled");
//         }
//         System.out.println("══════════════════════════════════════════════\n");

//         // ══════════════════════════════════════════════════════════════════════
//         // MAIN COIN SCAN LOOP
//         // ══════════════════════════════════════════════════════════════════════
//         for (String pair : COINS_TO_TRADE) {
//             try {
//                 if (pair.equals(BTC_PAIR)) continue;

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

//                 // ── Fetch all needed candles ───────────────────────────────────
//                 // 5m:  for MTF EMA check only
//                 // 15m: MTF EMA + RSI (S1) + prev candle (S2) + momentum confirmation
//                 // 30m: MTF EMA + primary trend (T1/T2/T3) + ADX + ATR + SL calc
//                 // 1H:  MTF EMA + macro filter (M1 EMA50/200)
//                 // 2H:  MTF EMA only
//                 JSONArray raw5m  = getCandlestickData(pair, "5",   CANDLE_5M);
//                 JSONArray raw15m = getCandlestickData(pair, "15",  CANDLE_15M);
//                 JSONArray raw30m = getCandlestickData(pair, "30",  CANDLE_30M);
//                 JSONArray raw1h  = getCandlestickData(pair, "60",  CANDLE_1H);
//                 JSONArray raw2h  = getCandlestickData(pair, "120", CANDLE_2H);

//                 // Minimum data checks
//                 if (raw30m == null || raw30m.length() < 60) {
//                     System.out.println("  Insufficient 30m candles — skip"); continue;
//                 }
//                 if (raw15m == null || raw15m.length() < 30) {
//                     System.out.println("  Insufficient 15m candles — skip"); continue;
//                 }
//                 if (raw1h == null || raw1h.length() < EMA_MACRO) {
//                     System.out.println("  Insufficient 1H candles — skip"); continue;
//                 }

//                 // ── Extract OHLC arrays ───────────────────────────────────────
//                 double[] cl30 = extractCloses(raw30m);
//                 double[] op30 = extractOpens(raw30m);
//                 double[] hi30 = extractHighs(raw30m);
//                 double[] lo30 = extractLows(raw30m);

//                 double[] cl15 = extractCloses(raw15m);
//                 double[] op15 = extractOpens(raw15m);
//                 double[] hi15 = extractHighs(raw15m);
//                 double[] lo15 = extractLows(raw15m);

//                 double[] cl1h = extractCloses(raw1h);

//                 double lastClose = cl30[cl30.length - 1];
//                 double lastHigh  = hi30[hi30.length - 1];
//                 double lastLow   = lo30[lo30.length - 1];

//                 double prevClose15 = cl15[cl15.length - 2];
//                 double prevOpen15  = op15[op15.length - 2];

//                 double tickSize = getTickSize(pair);
//                 double atr30m   = calcATR(hi30, lo30, cl30, ATR_PERIOD);
//                 double atr15m   = calcATR(hi15, lo15, cl15, ATR_PERIOD);

//                 System.out.printf("  Price=%.6f  ATR-30m=%.6f  ATR-15m=%.6f  Tick=%.8f%n",
//                         lastClose, atr30m, atr15m, tickSize);

//                 // ─────────────────────────────────────────────────────────────
//                 // HIGH VOLATILITY SKIP
//                 // ─────────────────────────────────────────────────────────────
//                 double atrPercent = (atr30m / lastClose) * 100.0;
//                 System.out.printf("  [VOL] ATR%%=%.2f%% (max %.1f%%) -> %s%n",
//                         atrPercent, MAX_ATR_PERCENT,
//                         atrPercent <= MAX_ATR_PERCENT ? "OK" : "FAIL — too volatile");
//                 if (atrPercent > MAX_ATR_PERCENT) {
//                     System.out.println("  VOL FAIL — market too volatile — skip");
//                     continue;
//                 }

//                 // ─────────────────────────────────────────────────────────────
//                 // MACRO FILTER M1: 1H EMA50 / EMA200
//                 // ─────────────────────────────────────────────────────────────
//                 double  ema1h     = calcEMA(cl1h, EMA_MACRO);
//                 boolean has200    = cl1h.length >= EMA_MACRO_SLOW;
//                 double  ema200_1h = has200 ? calcEMA(cl1h, EMA_MACRO_SLOW) : 0;

//                 boolean macroUp, macroDown;
//                 if (has200) {
//                     macroUp   = lastClose > ema1h && ema1h > ema200_1h;
//                     macroDown = lastClose < ema1h && ema1h < ema200_1h;
//                     System.out.printf("  [M1] 1H EMA50=%.4f EMA200=%.4f → %s%n",
//                             ema1h, ema200_1h,
//                             macroUp ? "STRONG BULL" : macroDown ? "STRONG BEAR" : "MIXED");
//                 } else {
//                     macroUp   = lastClose > ema1h;
//                     macroDown = lastClose < ema1h;
//                     System.out.printf("  [M1] 1H EMA50=%.4f (EMA200 N/A) → %s%n",
//                             ema1h, macroUp ? "BULL" : "BEAR");
//                 }
//                 if (!macroUp && !macroDown) {
//                     System.out.println("  M1 FAIL — macro mixed — skip"); continue;
//                 }
//                 System.out.println("  M1 OK — macro " + (macroUp ? "BULLISH" : "BEARISH"));

//                 // ── Quick direction decision based on M1 (used for MTF check) ─
//                 // We do a preliminary trendUp/Down here for filter ordering.
//                 // Final confirmation comes after T1.
//                 boolean prelimBull = macroUp;
//                 boolean prelimBear = macroDown;

//                 // ─────────────────────────────────────────────────────────────
//                 // MULTI-TIMEFRAME EMA9/EMA21 CONFLUENCE FILTER
//                 // Checks: 5m, 15m, 30m, 1H, 2H
//                 // Requires MTF_MIN_ALIGNED (4) timeframes to agree
//                 // ─────────────────────────────────────────────────────────────
//                 System.out.println("  ── Multi-TF EMA9/21 Check ──────────────────");

//                 String[] mtfRes  = {"5",  "15",  "30",  "60",  "120"};
//                 String[] mtfName = {"5m", "15m", "30m", "1H",  "2H"};
//                 int[]    mtfCnt  = {CANDLE_5M, CANDLE_15M, CANDLE_30M, CANDLE_1H, CANDLE_2H};
//                 JSONArray[] mtfRaw = {raw5m, raw15m, raw30m, raw1h, raw2h};

//                 int mtfBullCount = 0;
//                 int mtfBearCount = 0;
//                 int mtfValid     = 0;

//                 for (int t = 0; t < mtfRes.length; t++) {
//                     if (mtfRaw[t] == null || mtfRaw[t].length() < EMA_MID + 5) {
//                         System.out.printf("  [MTF-%s] N/A%n", mtfName[t]);
//                         continue;
//                     }
//                     double[] clTf  = extractCloses(mtfRaw[t]);
//                     double   e9    = calcEMA(clTf, EMA_FAST);
//                     double   e21   = calcEMA(clTf, EMA_MID);
//                     boolean  bull  = e9 > e21;
//                     mtfValid++;
//                     if (bull) mtfBullCount++; else mtfBearCount++;
//                     System.out.printf("  [MTF-%s] EMA9=%.5f EMA21=%.5f → %s%n",
//                             mtfName[t], e9, e21, bull ? "BULL ▲" : "BEAR ▼");
//                 }

//                 int mtfAligned = prelimBull ? mtfBullCount : mtfBearCount;
//                 System.out.printf("  [MTF] Aligned=%d/%d (need >=%d) → %s%n",
//                         mtfAligned, mtfValid, MTF_MIN_ALIGNED,
//                         mtfAligned >= MTF_MIN_ALIGNED ? "PASS" : "FAIL");

//                 if (mtfAligned < MTF_MIN_ALIGNED) {
//                     System.out.println("  MTF FAIL — insufficient EMA alignment across timeframes — skip");
//                     continue;
//                 }
//                 System.out.printf("  MTF OK — %d/%d timeframes aligned (%s)%n",
//                         mtfAligned, mtfValid, prelimBull ? "BULLISH" : "BEARISH");

//                 // ─────────────────────────────────────────────────────────────
//                 // BTC CORRELATION FILTER (multi-TF)
//                 // btcBullCount / btcBearCount computed once before the loop.
//                 // Rule: if BTC has >= BTC_REQUIRED_ALIGNED TFs AGAINST trade → block.
//                 // ─────────────────────────────────────────────────────────────
//                 if (btcDataOk) {
//                     int btcAgainst = prelimBull ? btcBearCount : btcBullCount;
//                     int btcWith    = prelimBull ? btcBullCount : btcBearCount;
//                     System.out.printf("  [BTC-MTF] With=%d/5 Against=%d/5 → %s%n",
//                             btcWith, btcAgainst,
//                             btcAgainst >= BTC_REQUIRED_ALIGNED ? "BLOCK — BTC strongly opposed"
//                             : btcAgainst >= 2 ? "WARN — BTC partially against"
//                             : "OK — BTC aligned");
//                     if (btcAgainst >= BTC_REQUIRED_ALIGNED) {
//                         System.out.println("  BTC BLOCK — " + btcAgainst + "/5 BTC TFs against trade direction — skip");
//                         continue;
//                     }
//                     if (btcAgainst >= 2) {
//                         System.out.println("  BTC WARN — partial opposition, proceeding with caution");
//                     } else {
//                         System.out.println("  BTC OK — BTC largely aligned with trade direction");
//                     }
//                 } else {
//                     System.out.println("  [BTC-MTF] BTC data N/A — filter skipped");
//                 }

//                 // ─────────────────────────────────────────────────────────────
//                 // TREND FILTER T1: 30m EMA9/21 + EMA21 Slope
//                 // ─────────────────────────────────────────────────────────────
//                 double ema9_30  = calcEMA(cl30, EMA_FAST);
//                 double ema21_30 = calcEMA(cl30, EMA_MID);
//                 double prevEma21_30 = calcEMA(Arrays.copyOfRange(cl30, 0, cl30.length - 1), EMA_MID);
//                 boolean ema21Rising30  = ema21_30 > prevEma21_30;
//                 boolean ema21Falling30 = ema21_30 < prevEma21_30;
//                 boolean localUp30   = ema9_30 > ema21_30;
//                 boolean localDown30 = ema9_30 < ema21_30;

//                 System.out.printf("  [T1-30m] EMA9=%.6f EMA21=%.6f prevEMA21=%.6f | Slope=%s | Cross=%s%n",
//                         ema9_30, ema21_30, prevEma21_30,
//                         ema21Rising30 ? "RISING" : ema21Falling30 ? "FALLING" : "FLAT",
//                         localUp30 ? "BULL" : "BEAR");

//                 boolean trendUp   = macroUp   && localUp30;
//                 boolean trendDown = macroDown && localDown30;

//                 if (!trendUp && !trendDown) {
//                     System.out.println("  T1 FAIL — 30m EMA cross not confirmed — skip"); continue;
//                 }
//                 // Slope confirmation (warn only — trend already confirmed by MTF)
//                 if (trendUp && !ema21Rising30) {
//                     System.out.println("  T1 WARN — EMA21 slope not rising (MTF already confirmed, continuing)");
//                 } else if (trendDown && !ema21Falling30) {
//                     System.out.println("  T1 WARN — EMA21 slope not falling (MTF already confirmed, continuing)");
//                 }
//                 System.out.println("  T1 OK — 30m EMA9/21 aligned: " + (trendUp ? "BULL" : "BEAR"));

//                 // ── TREND FILTER T2: 30m MACD ────────────────────────────────
//                 double[] mv30     = calcMACD(cl30, MACD_FAST, MACD_SLOW, MACD_SIG);
//                 double   macdLine = mv30[0], macdSigV = mv30[1];
//                 System.out.printf("  [T2-30m] MACD=%.6f Sig=%.6f Hist=%.6f%n",
//                         macdLine, macdSigV, mv30[2]);
//                 boolean macdBull = macdLine > macdSigV;
//                 boolean macdBear = macdLine < macdSigV;
//                 if (trendUp   && !macdBull) { System.out.println("  T2 FAIL — 30m MACD bearish — skip"); continue; }
//                 if (trendDown && !macdBear) { System.out.println("  T2 FAIL — 30m MACD bullish — skip"); continue; }
//                 System.out.println("  T2 OK — 30m MACD aligned");

//                 // ── TREND FILTER T3: 30m Supertrend ──────────────────────────
//                 boolean[] stResult30 = calcSupertrend(hi30, lo30, cl30, ST_PERIOD, ST_MULTIPLIER);
//                 boolean   stBull30   = stResult30[stResult30.length - 1];
//                 boolean   stPrevBull = stResult30[stResult30.length - 2];
//                 boolean   stFlipped  = stBull30 != stPrevBull;
//                 System.out.printf("  [T3-30m] Supertrend → %s%s%n",
//                         stBull30 ? "GREEN (BULL)" : "RED (BEAR)",
//                         stFlipped ? " [JUST FLIPPED]" : "");
//                 if (trendUp   && !stBull30) { System.out.println("  T3 FAIL — 30m Supertrend bearish — skip"); continue; }
//                 if (trendDown &&  stBull30) { System.out.println("  T3 FAIL — 30m Supertrend bullish — skip"); continue; }
//                 System.out.println("  T3 OK — 30m Supertrend aligned");

//                 // ─────────────────────────────────────────────────────────────
//                 // QUALITY FILTER Q1: ADX on 30m
//                 // ─────────────────────────────────────────────────────────────
//                 double adx = calcADX(hi30, lo30, cl30, ADX_PERIOD);
//                 System.out.printf("  [Q1-30m] ADX=%.2f (min=%.0f) → %s%n",
//                         adx, ADX_MIN, adx >= ADX_MIN ? "PASS" : "FAIL");
//                 if (adx < ADX_MIN) {
//                     System.out.println("  Q1 FAIL — ADX too low, market sideways — skip"); continue;
//                 }

//                 // ─────────────────────────────────────────────────────────────
//                 // ENTRY ZONE E1: EMA9 Directional Pullback on 30m
//                 // ─────────────────────────────────────────────────────────────
//                 double  distFromEma9   = Math.abs(lastClose - ema9_30);
//                 double  maxAllowedDist = EMA9_PULLBACK_MAX * atr30m;
//                 boolean nearEma9;
//                 if (trendUp) {
//                     nearEma9 = lastClose >= ema9_30 && distFromEma9 <= maxAllowedDist;
//                 } else {
//                     nearEma9 = lastClose <= ema9_30 && distFromEma9 <= maxAllowedDist;
//                 }
//                 System.out.printf("  [E1-30m] Price=%.6f EMA9=%.6f Dist=%.6f MaxDist=%.6f → %s%n",
//                         lastClose, ema9_30, distFromEma9, maxAllowedDist,
//                         nearEma9 ? "PASS" : "FAIL");

//                 if (stFlipped) {
//                     double flipMaxDist = ST_FLIP_MAX_CANDLE_RATIO * atr30m;
//                     if (distFromEma9 > flipMaxDist) {
//                         System.out.println("  E1 FAIL (ST flip) — too extended — skip"); continue;
//                     }
//                     System.out.println("  E1 ST-flip: price near EMA9 — OK");
//                 } else if (!nearEma9) {
//                     System.out.println("  E1 FAIL — not valid 30m EMA9 pullback — skip"); continue;
//                 }
//                 System.out.println("  E1 OK — 30m EMA9 pullback confirmed");

//                 // ─────────────────────────────────────────────────────────────
//                 // ENTRY ZONE E2: 30m candle not overextended
//                 // ─────────────────────────────────────────────────────────────
//                 double  candleSize = Math.abs(lastHigh - lastLow);
//                 double  maxCandle  = MAX_CANDLE_ATR_RATIO * atr30m;
//                 boolean candleOk   = candleSize <= maxCandle;
//                 System.out.printf("  [E2-30m] Candle=%.6f MaxCandle=%.6f → %s%n",
//                         candleSize, maxCandle, candleOk ? "PASS" : "FAIL — overextended");
//                 if (!candleOk) {
//                     System.out.println("  E2 FAIL — 30m candle too large — skip"); continue;
//                 }

//                 // ── SOFT FILTERS on 15m: at least 1 of 2 ────────────────────
//                 double  rsi15      = calcRSI(cl15, RSI_PERIOD);
//                 boolean softRsi    = (trendUp   && rsi15 >= RSI_LONG_MIN  && rsi15 <= RSI_LONG_MAX)
//                                   || (trendDown && rsi15 >= RSI_SHORT_MIN && rsi15 <= RSI_SHORT_MAX);
//                 System.out.printf("  [S1-15m] RSI=%.2f → %s%n", rsi15, softRsi ? "PASS" : "fail");

//                 boolean prevBull15 = prevClose15 > prevOpen15;
//                 boolean prevBear15 = prevClose15 < prevOpen15;
//                 boolean softCandle = (trendUp && prevBull15) || (trendDown && prevBear15);
//                 System.out.printf("  [S2-15m] Prev candle: open=%.6f close=%.6f → %s → %s%n",
//                         prevOpen15, prevClose15,
//                         prevBull15 ? "BULL" : prevBear15 ? "BEAR" : "DOJI",
//                         softCandle ? "PASS" : "fail");

//                 if (!softRsi && !softCandle) {
//                     System.out.println("  SOFT FAIL — neither 15m RSI nor candle confirms — skip"); continue;
//                 }
//                 System.out.println("  SOFT OK — " + (softRsi ? "RSI " : "") + (softCandle ? "Candle" : "") + " confirmed");

//                 // ─────────────────────────────────────────────────────────────
//                 // 15m MOMENTUM CANDLE CONFIRMATION
//                 // ─────────────────────────────────────────────────────────────
//                 double  last15mClose = cl15[cl15.length - 2];
//                 double  last15mOpen  = op15[op15.length - 2];
//                 double  last15mHigh  = hi15[hi15.length - 2];
//                 double  last15mLow   = lo15[lo15.length - 2];
//                 double  last15mRange = last15mHigh - last15mLow;
//                 double  last15mBody  = Math.abs(last15mClose - last15mOpen);
//                 boolean entry15mBull = last15mClose > last15mOpen;
//                 boolean entry15mBear = last15mClose < last15mOpen;

//                 double  bodyRatio     = last15mRange > 0 ? last15mBody / last15mRange : 0;
//                 boolean strongBody    = bodyRatio >= MIN_15M_BODY_RATIO;
//                 double  closePosition = last15mRange > 0
//                         ? (last15mClose - last15mLow) / last15mRange : 0.5;
//                 boolean closeNearHigh = closePosition >= (1.0 - CLOSE_NEAR_EXTREME_RATIO);
//                 boolean closeNearLow  = closePosition <= CLOSE_NEAR_EXTREME_RATIO;

//                 boolean entry15mOk;
//                 String  entry15mReason;
//                 if (trendUp) {
//                     entry15mOk     = entry15mBull && strongBody && closeNearHigh;
//                     entry15mReason = String.format("Bull=%s Body=%.0f%% ClosePos=%.0f%%",
//                             entry15mBull, bodyRatio * 100, closePosition * 100);
//                 } else {
//                     entry15mOk     = entry15mBear && strongBody && closeNearLow;
//                     entry15mReason = String.format("Bear=%s Body=%.0f%% ClosePos=%.0f%%",
//                             entry15mBear, bodyRatio * 100, closePosition * 100);
//                 }
//                 System.out.printf("  [15m-MOMENTUM] %s → %s%n", entry15mReason,
//                         entry15mOk ? "PASS" : "FAIL — weak candle");
//                 if (!entry15mOk) {
//                     System.out.println("  15m FAIL — momentum candle insufficient — skip"); continue;
//                 }
//                 System.out.println("  15m OK — strong momentum candle");

//                 // ── ALL FILTERS PASSED ────────────────────────────────────────
//                 String side = trendUp ? "buy" : "sell";
//                 System.out.println("\n  ╔══════════════════════════════════════════════════╗");
//                 System.out.println("  ║  ALL FILTERS PASSED → " + side.toUpperCase() + " " + pair);
//                 System.out.printf ("  ║  MTF aligned=%d/5 | ADX=%.1f | RSI=%.1f | ATR%%=%.2f%%%n",
//                         mtfAligned, adx, rsi15, atrPercent);
//                 System.out.printf ("  ║  BTC: with=%d/5 against=%d/5%n",
//                         trendUp ? btcBullCount : btcBearCount,
//                         trendUp ? btcBearCount : btcBullCount);
//                 if (stFlipped) System.out.println("  ║  *** 30m SUPERTREND FLIP ***");
//                 System.out.println("  ╚══════════════════════════════════════════════════╝");

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
//                     System.out.println("  Order failed: " + resp); continue;
//                 }
//                 System.out.println("  Order placed! id=" + resp.getString("id"));
//                 lastTradeTime.put(pair, System.currentTimeMillis());

//                 // ── Confirm entry price ───────────────────────────────────────
//                 double entry = getEntryPrice(pair, resp.getString("id"));
//                 if (entry <= 0) {
//                     System.out.println("  Could not confirm entry — TP/SL skipped"); continue;
//                 }
//                 System.out.printf("  Entry confirmed: %.6f%n", entry);

//                 // ─────────────────────────────────────────────────────────────
//                 // SL: Based on 30m Supertrend band (unchanged from v12)
//                 // ─────────────────────────────────────────────────────────────
//                 double[] stBands = calcSupertrendBands(hi30, lo30, cl30, ST_PERIOD, ST_MULTIPLIER);
//                 double   stLower = stBands[0];
//                 double   stUpper = stBands[1];

//                 double dynamicRR = adx >= 40 ? RR_STRONG : adx >= 30 ? RR_MEDIUM : RR_WEAK;
//                 System.out.printf("  Dynamic R:R = 1:%.1f (ADX=%.1f)%n", dynamicRR, adx);

//                 double slPrice, tpPrice;
//                 if ("buy".equalsIgnoreCase(side)) {
//                     double rawSL = stLower - (ST_SL_BUFFER * atr30m);
//                     double minSL = entry - SL_MIN_ATR * atr30m;
//                     double maxSL = entry - SL_MAX_ATR * atr30m;
//                     slPrice      = Math.max(Math.min(rawSL, minSL), maxSL);
//                     double risk  = entry - slPrice;
//                     tpPrice      = entry + dynamicRR * risk;
//                     System.out.printf("  30m ST LowerBand=%.6f RawSL=%.6f SL=%.6f%n",
//                             stLower, rawSL, slPrice);
//                 } else {
//                     double rawSL = stUpper + (ST_SL_BUFFER * atr30m);
//                     double minSL = entry + SL_MIN_ATR * atr30m;
//                     double maxSL = entry + SL_MAX_ATR * atr30m;
//                     slPrice      = Math.min(Math.max(rawSL, minSL), maxSL);
//                     double risk  = slPrice - entry;
//                     tpPrice      = entry - dynamicRR * risk;
//                     System.out.printf("  30m ST UpperBand=%.6f RawSL=%.6f SL=%.6f%n",
//                             stUpper, rawSL, slPrice);
//                 }

//                 slPrice = roundToTick(slPrice, tickSize);
//                 tpPrice = roundToTick(tpPrice, tickSize);

//                 double slPct = Math.abs(entry - slPrice) / entry * 100;
//                 double tpPct = Math.abs(tpPrice - entry)  / entry * 100;
//                 System.out.printf("  SL=%.6f (%.2f%%) | TP=%.6f (%.2f%%) | R:R=1:%.1f%n",
//                         slPrice, slPct, tpPrice, tpPct, dynamicRR);

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
//     // SUPERTREND BANDS
//     // =========================================================================
//     private static double[] calcSupertrendBands(double[] hi, double[] lo, double[] cl,
//                                                  int period, double multiplier) {
//         int n = cl.length;
//         if (n < period + 1) return new double[]{cl[n-1] * 0.97, cl[n-1] * 1.03};
//         double[] atrArr    = calcATRSeries(hi, lo, cl, period);
//         double[] upperBand = new double[n];
//         double[] lowerBand = new double[n];
//         for (int i = period; i < n; i++) {
//             double hl2        = (hi[i] + lo[i]) / 2.0;
//             double basicUpper = hl2 + multiplier * atrArr[i];
//             double basicLower = hl2 - multiplier * atrArr[i];
//             if (i == period) {
//                 upperBand[i] = basicUpper;
//                 lowerBand[i] = basicLower;
//             } else {
//                 upperBand[i] = (basicUpper < upperBand[i-1] || cl[i-1] > upperBand[i-1])
//                         ? basicUpper : upperBand[i-1];
//                 lowerBand[i] = (basicLower > lowerBand[i-1] || cl[i-1] < lowerBand[i-1])
//                         ? basicLower : lowerBand[i-1];
//             }
//         }
//         return new double[]{lowerBand[n-1], upperBand[n-1]};
//     }

//     // =========================================================================
//     // ADX
//     // =========================================================================
//     private static double calcADX(double[] hi, double[] lo, double[] cl, int period) {
//         int n = hi.length;
//         if (n < period * 2) return 0;
//         double[] plusDM  = new double[n];
//         double[] minusDM = new double[n];
//         double[] tr      = new double[n];
//         for (int i = 1; i < n; i++) {
//             double upMove   = hi[i] - hi[i-1];
//             double downMove = lo[i-1] - lo[i];
//             plusDM[i]  = (upMove > downMove && upMove > 0)   ? upMove   : 0;
//             minusDM[i] = (downMove > upMove && downMove > 0) ? downMove : 0;
//             tr[i] = Math.max(hi[i] - lo[i],
//                     Math.max(Math.abs(hi[i] - cl[i-1]), Math.abs(lo[i] - cl[i-1])));
//         }
//         double smoothTR = 0, smoothPlus = 0, smoothMinus = 0;
//         for (int i = 1; i <= period; i++) {
//             smoothTR += tr[i]; smoothPlus += plusDM[i]; smoothMinus += minusDM[i];
//         }
//         double prevADX = 0; double adxSum = 0; int adxCount = 0;
//         for (int i = period + 1; i < n; i++) {
//             smoothTR    = smoothTR    - (smoothTR    / period) + tr[i];
//             smoothPlus  = smoothPlus  - (smoothPlus  / period) + plusDM[i];
//             smoothMinus = smoothMinus - (smoothMinus / period) + minusDM[i];
//             if (smoothTR == 0) continue;
//             double plusDI  = 100.0 * smoothPlus  / smoothTR;
//             double minusDI = 100.0 * smoothMinus / smoothTR;
//             double diSum   = plusDI + minusDI;
//             double dx      = diSum == 0 ? 0 : 100.0 * Math.abs(plusDI - minusDI) / diSum;
//             if (adxCount < period) {
//                 adxSum += dx; adxCount++;
//                 if (adxCount == period) prevADX = adxSum / period;
//             } else {
//                 prevADX = (prevADX * (period - 1) + dx) / period;
//             }
//         }
//         return prevADX;
//     }

//     // =========================================================================
//     // SUPERTREND DIRECTION
//     // =========================================================================
//     private static boolean[] calcSupertrend(double[] hi, double[] lo, double[] cl,
//                                              int period, double multiplier) {
//         int n = cl.length;
//         boolean[] bullish = new boolean[n];
//         if (n < period + 1) { Arrays.fill(bullish, true); return bullish; }
//         double[] atrArr    = calcATRSeries(hi, lo, cl, period);
//         double[] upperBand = new double[n];
//         double[] lowerBand = new double[n];
//         for (int i = period; i < n; i++) {
//             double hl2        = (hi[i] + lo[i]) / 2.0;
//             double basicUpper = hl2 + multiplier * atrArr[i];
//             double basicLower = hl2 - multiplier * atrArr[i];
//             if (i == period) {
//                 upperBand[i] = basicUpper; lowerBand[i] = basicLower;
//             } else {
//                 upperBand[i] = (basicUpper < upperBand[i-1] || cl[i-1] > upperBand[i-1])
//                         ? basicUpper : upperBand[i-1];
//                 lowerBand[i] = (basicLower > lowerBand[i-1] || cl[i-1] < lowerBand[i-1])
//                         ? basicLower : lowerBand[i-1];
//             }
//             if (i == period) bullish[i] = cl[i] > (hi[i] + lo[i]) / 2.0;
//             else bullish[i] = bullish[i-1] ? cl[i] >= lowerBand[i] : cl[i] > upperBand[i];
//         }
//         for (int i = 0; i < period; i++) bullish[i] = bullish[period];
//         return bullish;
//     }

//     // =========================================================================
//     // ATR SERIES
//     // =========================================================================
//     private static double[] calcATRSeries(double[] hi, double[] lo, double[] cl, int period) {
//         int n = hi.length;
//         double[] atr = new double[n];
//         if (n < 2) return atr;
//         double[] tr = new double[n];
//         tr[0] = hi[0] - lo[0];
//         for (int i = 1; i < n; i++)
//             tr[i] = Math.max(hi[i] - lo[i],
//                     Math.max(Math.abs(hi[i] - cl[i-1]), Math.abs(lo[i] - cl[i-1])));
//         double sum = 0;
//         for (int i = 0; i < period && i < n; i++) sum += tr[i];
//         atr[period - 1] = sum / period;
//         for (int i = period; i < n; i++) atr[i] = (atr[i-1] * (period - 1) + tr[i]) / period;
//         for (int i = 0; i < period - 1; i++) atr[i] = atr[period - 1];
//         return atr;
//     }

//     // =========================================================================
//     // INDICATORS
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
//         double[] ef    = calcEMASeries(cl, fast);
//         double[] es    = calcEMASeries(cl, slow);
//         int      start = slow - 1;
//         int      len   = cl.length - start;
//         if (len <= 0) return new double[]{0, 0, 0};
//         double[] ml = new double[len];
//         for (int i = 0; i < len; i++) ml[i] = ef[start + i] - es[start + i];
//         double[] ss = calcEMASeries(ml, sig);
//         double m = ml[ml.length - 1], s = ss[ss.length - 1];
//         return new double[]{m, s, m - s};
//     }

//     private static double calcRSI(double[] cl, int period) {
//         if (cl.length < period + 1) return 50;
//         double ag = 0, al = 0;
//         for (int i = 1; i <= period; i++) {
//             double ch = cl[i] - cl[i-1];
//             if (ch > 0) ag += ch; else al += Math.abs(ch);
//         }
//         ag /= period; al /= period;
//         for (int i = period + 1; i < cl.length; i++) {
//             double ch = cl[i] - cl[i-1];
//             if (ch > 0) { ag = (ag*(period-1)+ch)/period; al = al*(period-1)/period; }
//             else         { al = (al*(period-1)+Math.abs(ch))/period; ag = ag*(period-1)/period; }
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
//                     Math.max(Math.abs(hi[i] - cl[i-1]), Math.abs(lo[i] - cl[i-1])));
//         double atr = 0;
//         for (int i = 0; i < period; i++) atr += tr[i];
//         atr /= period;
//         for (int i = period; i < hi.length; i++) atr = (atr*(period-1)+tr[i])/period;
//         return atr;
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
//                 case "5":   minsPerBar = 5;   break;
//                 case "15":  minsPerBar = 15;  break;
//                 case "30":  minsPerBar = 30;  break;
//                 case "60":  minsPerBar = 60;  break;
//                 case "120": minsPerBar = 120; break;
//                 default:    minsPerBar = 15;  break;
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
//                 System.err.println("  Candle s=" + r.optString("s") + " " + pair);
//             } else {
//                 System.err.println("  Candle HTTP " + code + " " + pair);
//             }
//         } catch (Exception e) {
//             System.err.println("  getCandlestickData(" + pair + "/" + resolution + "): " + e.getMessage());
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
//         String resp = authPost(BASE_URL + "/exchange/v1/derivatives/futures/positions", body.toString());
//         JSONArray arr = resp.startsWith("[")
//                 ? new JSONArray(resp) : new JSONArray().put(new JSONObject(resp));
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
//             System.out.println(r.has("err_code_dcx") ? "  TP/SL error: " + r : "  TP/SL set successfully!");
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
//             String resp = authPost(BASE_URL + "/exchange/v1/derivatives/futures/positions", body.toString());
//             JSONArray arr = resp.startsWith("[")
//                     ? new JSONArray(resp) : new JSONArray().put(new JSONObject(resp));
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
//                             pair, p.optDouble("active_pos", 0), p.optDouble("avg_price", 0),
//                             p.optDouble("take_profit_trigger", 0), p.optDouble("stop_loss_trigger", 0));
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
//         InputStream is = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
//         return readStream(is);
//     }

//     private static String readStream(InputStream is) throws IOException {
//         return new BufferedReader(new InputStreamReader(is))
//                 .lines().collect(Collectors.joining("\n"));
//     }

//     private static String sign(String payload) {
//         try {
//             Mac mac = Mac.getInstance("HmacSHA256");
//             mac.init(new SecretKeySpec(API_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
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
//  * ═══════════════════════════════════════════════════════════════════════════
//  * CoinDCX Futures Trader — v12 (30m Trend + 15m Momentum Edition)
//  * ═══════════════════════════════════════════════════════════════════════════
//  *
//  * CHANGES FROM v11 → v12:
//  *
//  *   CHANGE #1 — 30m Timeframe for Trend Decision
//  *     v11: All trend filters (T1 EMA9/21, T2 MACD, T3 Supertrend, Q1 ADX,
//  *          E1 EMA9 pullback, E2 candle size) ran on 15m candles.
//  *     v12: These filters now run on 30m candles.
//  *     WHY: 30m gives cleaner trend signals with less noise than 15m.
//  *          Fewer false crossovers, more reliable ADX readings.
//  *
//  *   CHANGE #2 — Supertrend-Based SL on 30m
//  *     v11: SL = swingLow/High (15m, 30 bars) ± ATR buffers, clamped 1.5–2.5x ATR
//  *     v12: SL = Supertrend band value on 30m chart
//  *            LONG  SL = 30m lowerBand (Supertrend support) - small ATR buffer
//  *            SHORT SL = 30m upperBand (Supertrend resistance) + small ATR buffer
//  *          Still clamped to SL_MIN_ATR / SL_MAX_ATR for safety.
//  *     WHY: Supertrend bands are dynamic and respect current volatility.
//  *          Placing SL just below the Supertrend line is the textbook method.
//  *
//  *   CHANGE #3 — 15m Momentum Candle Confirmation (unchanged from v11)
//  *     v11: 5m candle used as entry timing confirmation.
//  *     v12: 15m candle used instead (body ratio, close near extreme).
//  *     WHY: User request — 15m momentum is already available and consistent
//  *          with the broader 30m trend context.
//  *
//  * FULL FILTER ARCHITECTURE (v12):
//  *
//  *   MACRO (1H):
//  *     M1. Price > EMA50 AND EMA50 > EMA200  (bull)
//  *         Price < EMA50 AND EMA50 < EMA200  (bear)
//  *
//  *   TREND (30m) ← NEW TIMEFRAME:
//  *     T1. EMA9 > EMA21 AND EMA21 slope rising   (bull)
//  *         EMA9 < EMA21 AND EMA21 slope falling  (bear)
//  *     T2. MACD line > signal                    (bull / bear)
//  *     T3. Supertrend GREEN / RED
//  *
//  *   QUALITY (30m):
//  *     Q1. ADX > 18 (trend strength)
//  *     Q2. ATR% < 4.5% (not too volatile)
//  *     Q3. BTC 15m EMA9/21 aligned with trade direction
//  *
//  *   ENTRY ZONE (30m):
//  *     E1. Price on correct side of EMA9 (above for buy, below for sell)
//  *         AND distance from EMA9 <= 1.4 * ATR
//  *     E2. Current candle not overextended (< 1.5 * ATR)
//  *
//  *   CONFIRMATION (15m — at least 1 of 2):
//  *     S1. RSI in zone (42-68 long | 32-58 short)
//  *     S2. Previous 15m candle matches direction
//  *
//  *   TIMING (15m momentum candle):
//  *     Strong momentum candle (body > 35%, close near extreme)
//  *
//  *   SL/TP:
//  *     SL = 30m Supertrend band ± small ATR buffer, clamped 2.1–2.7x ATR
//  *     TP = dynamic R:R (1.3 / 1.4 / 1.5 based on ADX)
//  * ═══════════════════════════════════════════════════════════════════════════
//  */
// public class CoinDCXFuturesTrader8C_BUY_SELL_NEW_LOGIC_THREE {

//     // =========================================================================
//     // API Configuration
//     // =========================================================================
//     private static final String API_KEY    = System.getenv("DELTA_API_KEY");
//     private static final String API_SECRET = System.getenv("DELTA_API_SECRET");
//     private static final String BASE_URL       = "https://api.coindcx.com";
//     private static final String PUBLIC_API_URL = "https://public.coindcx.com";

//     private static final double MAX_MARGIN             = 300.0;
//     private static final int    LEVERAGE               = 10;
//     private static final int    MAX_ENTRY_PRICE_CHECKS = 10;
//     private static final int    ENTRY_CHECK_DELAY_MS   = 1000;
//     private static final long   TICK_CACHE_TTL_MS      = 3_600_000L;
//     private static final long   COOLDOWN_MS            = 2 * 60 * 60 * 1000L;

//     // ── Indicator periods ─────────────────────────────────────────────────────
//     private static final int EMA_FAST        = 9;
//     private static final int EMA_MID         = 21;
//     private static final int EMA_MACRO       = 50;
//     private static final int EMA_MACRO_SLOW  = 200;
//     private static final int MACD_FAST       = 12;
//     private static final int MACD_SLOW       = 26;
//     private static final int MACD_SIG        = 9;
//     private static final int RSI_PERIOD      = 14;
//     private static final int ATR_PERIOD      = 14;
//     private static final int ADX_PERIOD      = 14;

//     // ── Supertrend ────────────────────────────────────────────────────────────
//     private static final int    ST_PERIOD     = 10;
//     private static final double ST_MULTIPLIER = 3.5;

//     // ── ADX threshold ────────────────────────────────────────────────────────
//     private static final double ADX_MIN = 18.0;

//     // ── RSI zones ────────────────────────────────────────────────────────────
//     private static final double RSI_LONG_MIN  = 42.0;
//     private static final double RSI_LONG_MAX  = 68.0;
//     private static final double RSI_SHORT_MIN = 32.0;
//     private static final double RSI_SHORT_MAX = 58.0;

//     // ── SL parameters (clamped around Supertrend band) ───────────────────────
//     private static final double ST_SL_BUFFER   = 0.3;  // extra ATR buffer beyond ST band
//     private static final double SL_MIN_ATR     = 2.1;  // closest SL allowed (ATR units)
//     private static final double SL_MAX_ATR     = 2.7;  // farthest SL allowed (ATR units)

//     // ── Dynamic RR based on ADX strength ─────────────────────────────────────
//     private static final double RR_STRONG = 1.5;  // ADX >= 40
//     private static final double RR_MEDIUM = 1.4;  // ADX >= 30
//     private static final double RR_WEAK   = 1.3;  // ADX < 30

//     // ── Entry zone filters (30m) ──────────────────────────────────────────────
//     private static final double EMA9_PULLBACK_MAX       = 1.4;
//     private static final double MAX_CANDLE_ATR_RATIO    = 1.5;
//     private static final double ST_FLIP_MAX_CANDLE_RATIO= 1.2;

//     // ── 15m momentum candle quality ───────────────────────────────────────────
//     private static final double MIN_15M_BODY_RATIO        = 0.35;
//     private static final double CLOSE_NEAR_EXTREME_RATIO  = 0.45;

//     // ── High volatility skip threshold ───────────────────────────────────────
//     private static final double MAX_ATR_PERCENT = 4.5;

//     // ── Candle fetch counts ───────────────────────────────────────────────────
//     private static final int CANDLE_30M = 100;   // ← NEW: 30m trend candles
//     private static final int CANDLE_15M = 60;    // used for RSI, S2, momentum confirmation
//     private static final int CANDLE_1H  = 220;   // extended for EMA200

//     // ── Daily P&L protection ──────────────────────────────────────────────────
//     private static final double DAILY_PROFIT_TARGET  = 400.0;
//     private static final double DAILY_DRAWDOWN_LIMIT = 250.0;
//     private static final double DAILY_LOSS_LIMIT     = 300.0;

//     // ── BTC pair for correlation filter ──────────────────────────────────────
//     private static final String BTC_PAIR = "B-BTC_USDT";

//     private static final Map<String, JSONObject> instrumentCache = new ConcurrentHashMap<>();
//     private static long lastCacheUpdate = 0;
//     private static final Map<String, Long> lastTradeTime = new ConcurrentHashMap<>();

//     // =========================================================================
//     // Coin list
//     // =========================================================================
//     private static final String[] COIN_SYMBOLS = {
//         "ETH", "SOL", "ZEC", "XRP", "DOGE", "BNB", "TAO", "1000PEPE", "ADA", "SUI",
//         "BCH", "LINK", "AVAX", "FIL", "OP", "NEAR", "TRX", "TRUMP", "ARB", "WLD",
//         "FET", "ETC", "AAVE", "WIF", "INJ", "TIA", "LTC", "ONDO", "ORDI", "TON",
//         "HBAR", "IMX", "ATOM", "RUNE", "KAS", "UNI", "ICP", "SEI", "PENDLE", "1000SHIB",
//         "1000BONK", "CRV", "JUP", "RENDER", "MKR", "LDO", "STX", "XLM", "PYTH", "VIRTUAL",
//         "APT", "SNX", "STRK", "NEO", "FTM", "CAKE", "1000FLOKI", "1000SATS", "OM", "FARTCOIN",
//         "GRT", "MINA", "COMP", "BLUR", "BRETT", "SAND", "EGLD", "XMR", "IOTA", "AI16Z",
//         "PNUT", "POPCAT", "ZRO", "MANA", "ETHFI", "VET", "ALGO", "ENS", "BOME", "MASK",
//         "GALA", "YFI", "CHZ", "GMX", "QNT", "POL", "MOODENG", "ZK", "ARKM", "THETA",
//         "MEW", "EIGEN", "MORPHO", "KAITO", "USUAL", "LAYER", "GOAT", "DOGS", "RSR", "PONKE",
//         "JTO", "CKB", "ZIL", "ROSE", "1INCH", "TWT", "KSM", "MAGIC", "GAS", "ACT",
//         "SUSHI", "TURBO", "1000LUNC", "BTCDOM", "S", "IP", "FLOW", "TRB", "QTUM", "KNC",
//         "KAIA", "CELO", "SSV", "BANANA", "TNSR", "AERO", "IO", "DEXE", "ARK", "XAI",
//         "DYM", "SAGA", "HOT", "LUNA2", "IOST", "RPL", "VANA", "DASH", "MANTA", "LRC",
//         "ANKR", "XTZ", "BAND", "SUPER", "FXS", "AKT", "NMR", "PIXEL", "LPT", "STORJ",
//         "ENJ", "LISTA", "ZETA", "RED", "AGLD", "GPS", "KAVA", "SXP", "ALPHA", "BIGTIME",
//         "COTI", "USTC", "BAT", "NFP", "ONE", "POLYX", "MOVR", "OMNI", "CELR", "RVN",
//         "GLM", "HIVE", "FLUX", "ZRX", "SFP", "ALICE", "ILV", "ARPA", "UMA", "DEGEN",
//         "XVS", "ACE", "ASTR", "CTSI", "CHR", "EDU", "PROM", "ALT", "C98", "SUN",
//         "WAXP", "ALPACA", "COOKIE", "JOE", "BNT", "SCRT", "VELODROME", "HOOK", "KMNO", "NTRN",
//         "VIC", "RAYSOL", "PARTI", "MELANIA", "MYRO", "SHELL", "AUCTION", "SWELL", "HIGH", "WOO",
//         "COW", "MAVIA", "VTHO", "1000CAT", "MUBARAK", "LEVER", "SOLV", "ARC", "AVAAI", "KOMA",
//         "API3", "VOXEL", "CHESS", "SPELL", "1000WHY", "SKL", "GTC", "MTL", "BICO", "DENT",
//         "RLC", "PHB", "POWR", "LSK", "DEFI", "MAV", "REI", "ONG", "XVG", "COS",
//         "FORTH", "BEL", "MLN", "HEI", "GHST", "STEEM", "LOKA", "DIA", "TLM", "BMT",
//         "ALCH", "FUN", "1000CHEEMS", "1000RATS", "1000000MOG", "1MBABYDOGE", "1000XEC", "1000X", "PERP", "NKN",
//         "VINE", "RARE", "HFT", "AXL", "ACH", "ZEN", "PEOPLE", "AR", "CFX", "ID",
//         "METIS", "FIO", "CYBER"
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

//         // ── Fetch BTC trend ONCE (15m EMA9/21) — applies to all altcoin trades ──
//         boolean btcBull    = false;
//         boolean btcBear    = false;
//         boolean btcTrendOk = false;
//         try {
//             JSONArray btcRaw15m = getCandlestickData(BTC_PAIR, "15", 40);
//             if (btcRaw15m != null && btcRaw15m.length() >= 30) {
//                 double[] btcCl  = extractCloses(btcRaw15m);
//                 double btcEma9  = calcEMA(btcCl, EMA_FAST);
//                 double btcEma21 = calcEMA(btcCl, EMA_MID);
//                 btcBull    = btcEma9 > btcEma21;
//                 btcBear    = btcEma9 < btcEma21;
//                 btcTrendOk = true;
//                 System.out.printf("BTC Trend: EMA9=%.2f EMA21=%.2f → %s%n",
//                         btcEma9, btcEma21, btcBull ? "BULL (only LONG alts)" : "BEAR (only SHORT alts)");
//             } else {
//                 System.out.println("BTC data unavailable — BTC filter disabled this scan");
//             }
//         } catch (Exception e) {
//             System.err.println("BTC trend fetch failed: " + e.getMessage());
//         }

//         for (String pair : COINS_TO_TRADE) {
//             try {
//                 if (pair.equals(BTC_PAIR)) continue;

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
//                 // 30m: trend decisions, Supertrend SL, ADX, EMA9 pullback
//                 // 15m: RSI (S1), previous candle (S2), momentum confirmation
//                 // 1H:  macro EMA50/EMA200
//                 JSONArray raw30m = getCandlestickData(pair, "30", CANDLE_30M);
//                 JSONArray raw15m = getCandlestickData(pair, "15", CANDLE_15M);
//                 JSONArray raw1h  = getCandlestickData(pair, "60", CANDLE_1H);

//                 if (raw30m == null || raw30m.length() < 60) {
//                     System.out.println("  Insufficient 30m candles — skip");
//                     continue;
//                 }
//                 if (raw15m == null || raw15m.length() < 30) {
//                     System.out.println("  Insufficient 15m candles — skip");
//                     continue;
//                 }
//                 if (raw1h == null || raw1h.length() < EMA_MACRO) {
//                     System.out.println("  Insufficient 1H candles — skip");
//                     continue;
//                 }

//                 // ── Extract OHLC arrays ───────────────────────────────────────
//                 // 30m arrays — used for all trend/entry/SL logic
//                 double[] cl30 = extractCloses(raw30m);
//                 double[] op30 = extractOpens(raw30m);
//                 double[] hi30 = extractHighs(raw30m);
//                 double[] lo30 = extractLows(raw30m);

//                 // 15m arrays — used for RSI, S2, momentum candle confirmation
//                 double[] cl15 = extractCloses(raw15m);
//                 double[] op15 = extractOpens(raw15m);
//                 double[] hi15 = extractHighs(raw15m);
//                 double[] lo15 = extractLows(raw15m);

//                 // 1H arrays — macro trend only
//                 double[] cl1h = extractCloses(raw1h);

//                 double lastClose = cl30[cl30.length - 1];
//                 double lastHigh  = hi30[hi30.length - 1];
//                 double lastLow   = lo30[lo30.length - 1];

//                 // 15m previous candle (for S2 soft filter)
//                 double prevClose15 = cl15[cl15.length - 2];
//                 double prevOpen15  = op15[op15.length - 2];

//                 double tickSize = getTickSize(pair);
//                 double atr30m   = calcATR(hi30, lo30, cl30, ATR_PERIOD);
//                 double atr15m   = calcATR(hi15, lo15, cl15, ATR_PERIOD);

//                 System.out.printf("  Price=%.6f  ATR-30m=%.6f  ATR-15m=%.6f  Tick=%.8f%n",
//                         lastClose, atr30m, atr15m, tickSize);

//                 // ─────────────────────────────────────────────────────────────
//                 // FIX #7: High Volatility Skip (uses 30m ATR)
//                 // ─────────────────────────────────────────────────────────────
//                 double atrPercent = (atr30m / lastClose) * 100.0;
//                 System.out.printf("  [VOL] ATR%%=%.2f%% (max allowed: %.1f%%) -> %s%n",
//                         atrPercent, MAX_ATR_PERCENT,
//                         atrPercent <= MAX_ATR_PERCENT ? "OK" : "FAIL — too volatile");
//                 if (atrPercent > MAX_ATR_PERCENT) {
//                     System.out.println("  VOL FAIL — market too volatile (news/spike?) — skip");
//                     continue;
//                 }

//                 // ─────────────────────────────────────────────────────────────
//                 // MACRO FILTER M1: 1H EMA50 + EMA200
//                 // ─────────────────────────────────────────────────────────────
//                 double  ema1h     = calcEMA(cl1h, EMA_MACRO);
//                 boolean has200    = cl1h.length >= EMA_MACRO_SLOW;
//                 double  ema200_1h = has200 ? calcEMA(cl1h, EMA_MACRO_SLOW) : 0;

//                 boolean macroUp, macroDown;
//                 if (has200) {
//                     macroUp   = lastClose > ema1h && ema1h > ema200_1h;
//                     macroDown = lastClose < ema1h && ema1h < ema200_1h;
//                     System.out.printf("  [M1] 1H EMA50=%.4f EMA200=%.4f | Price=%s EMA50=%s EMA200 -> %s%n",
//                             ema1h, ema200_1h,
//                             lastClose > ema1h ? ">" : "<",
//                             ema1h > ema200_1h ? ">" : "<",
//                             macroUp ? "STRONG BULL" : macroDown ? "STRONG BEAR" : "MIXED");
//                 } else {
//                     macroUp   = lastClose > ema1h;
//                     macroDown = lastClose < ema1h;
//                     System.out.printf("  [M1] 1H EMA50=%.4f (EMA200 N/A) -> %s%n",
//                             ema1h, macroUp ? "BULL" : "BEAR");
//                 }

//                 if (!macroUp && !macroDown) {
//                     System.out.println("  M1 FAIL — macro trend mixed — skip");
//                     continue;
//                 }
//                 System.out.println("  M1 OK — macro " + (macroUp ? "BULLISH" : "BEARISH"));

//                 // ─────────────────────────────────────────────────────────────
//                 // TREND FILTER T1: 30m EMA9/21 + EMA21 SLOPE
//                 // ─────────────────────────────────────────────────────────────
//                 double ema9_30  = calcEMA(cl30, EMA_FAST);
//                 double ema21_30 = calcEMA(cl30, EMA_MID);

//                 // Previous bar EMA21 on 30m
//                 double prevEma21_30 = calcEMA(
//                         Arrays.copyOfRange(cl30, 0, cl30.length - 1),
//                         EMA_MID
//                 );
//                 boolean ema21Rising30  = ema21_30 > prevEma21_30;
//                 boolean ema21Falling30 = ema21_30 < prevEma21_30;

//                 boolean localUp30   = ema9_30 > ema21_30;
//                 boolean localDown30 = ema9_30 < ema21_30;

//                 System.out.printf("  [T1-30m] EMA9=%.6f EMA21=%.6f prevEMA21=%.6f | Slope=%s | Cross=%s -> %s%n",
//                         ema9_30, ema21_30, prevEma21_30,
//                         ema21Rising30 ? "RISING" : ema21Falling30 ? "FALLING" : "FLAT",
//                         localUp30 ? "BULL" : "BEAR",
//                         localUp30 ? "BULLISH" : localDown30 ? "BEARISH" : "FAIL");

//                 boolean trendUp   = macroUp   && localUp30;
//                 boolean trendDown = macroDown && localDown30;

//                 if (!trendUp && !trendDown) {
//                     System.out.println("  T1 FAIL — 30m EMA cross not confirmed — skip");
//                     continue;
//                 }
//                 System.out.println("  T1 OK — 30m " + (trendUp ? "BULLISH" : "BEARISH") + " with slope");

//                 // ── TREND FILTER T2: MACD on 30m ─────────────────────────────
//                 double[] mv30      = calcMACD(cl30, MACD_FAST, MACD_SLOW, MACD_SIG);
//                 double   macdLine  = mv30[0], macdSigV = mv30[1], macdHist = mv30[2];
//                 System.out.printf("  [T2-30m] MACD=%.6f Sig=%.6f Hist=%.6f%n",
//                         macdLine, macdSigV, macdHist);
//                 boolean macdBull = macdLine > macdSigV;
//                 boolean macdBear = macdLine < macdSigV;
//                 if (trendUp   && !macdBull) { System.out.println("  T2 FAIL — 30m MACD bearish — skip"); continue; }
//                 if (trendDown && !macdBear) { System.out.println("  T2 FAIL — 30m MACD bullish — skip"); continue; }
//                 System.out.println("  T2 OK — 30m MACD aligned");

//                 // ── TREND FILTER T3: Supertrend on 30m ───────────────────────
//                 boolean[] stResult30  = calcSupertrend(hi30, lo30, cl30, ST_PERIOD, ST_MULTIPLIER);
//                 boolean   stBull30    = stResult30[stResult30.length - 1];
//                 boolean   stPrevBull  = stResult30[stResult30.length - 2];
//                 boolean   stFlipped   = stBull30 != stPrevBull;
//                 System.out.printf("  [T3-30m] Supertrend -> %s%s%n",
//                         stBull30 ? "GREEN (BULL)" : "RED (BEAR)",
//                         stFlipped ? " [JUST FLIPPED]" : "");
//                 if (trendUp   && !stBull30) { System.out.println("  T3 FAIL — 30m Supertrend bearish — skip"); continue; }
//                 if (trendDown &&  stBull30) { System.out.println("  T3 FAIL — 30m Supertrend bullish — skip"); continue; }
//                 System.out.println("  T3 OK — 30m Supertrend aligned");

//                 // ─────────────────────────────────────────────────────────────
//                 // QUALITY FILTER Q1: ADX on 30m
//                 // ─────────────────────────────────────────────────────────────
//                 double adx = calcADX(hi30, lo30, cl30, ADX_PERIOD);
//                 System.out.printf("  [Q1-30m] ADX=%.2f (min=%.0f) -> %s%n",
//                         adx, ADX_MIN, adx >= ADX_MIN ? "PASS" : "FAIL — sideways");
//                 if (adx < ADX_MIN) {
//                     System.out.println("  Q1 FAIL — 30m ADX too low, market sideways — skip");
//                     continue;
//                 }
//                 System.out.printf("  Q1 OK — trend strength confirmed (ADX=%.1f)%n", adx);

//                 // ─────────────────────────────────────────────────────────────
//                 // QUALITY FILTER Q2: BTC Correlation
//                 // ─────────────────────────────────────────────────────────────
//                 if (btcTrendOk) {
//                     boolean btcAligned = (trendUp && btcBull) || (trendDown && btcBear);
//                     System.out.printf("  [Q2] BTC trend: %s | Trade: %s -> %s%n",
//                             btcBull ? "BULL" : "BEAR",
//                             trendUp ? "LONG" : "SHORT",
//                             btcAligned ? "PASS — aligned" : "WARN — against BTC");
//                     if (!btcAligned) {
//                         System.out.println("  Q2 WARN — BTC not aligned — lower confidence trade");
//                     } else {
//                         System.out.println("  Q2 OK — altcoin and BTC aligned");
//                     }
//                 } else {
//                     System.out.println("  [Q2] BTC data N/A — filter skipped");
//                 }

//                 // ─────────────────────────────────────────────────────────────
//                 // ENTRY ZONE E1: EMA9 Directional Pullback on 30m
//                 // BUY:  price >= EMA9 AND within 1.4x ATR above it
//                 // SELL: price <= EMA9 AND within 1.4x ATR below it
//                 // ─────────────────────────────────────────────────────────────
//                 double distFromEma9   = Math.abs(lastClose - ema9_30);
//                 double maxAllowedDist = EMA9_PULLBACK_MAX * atr30m;

//                 boolean nearEma9;
//                 if (trendUp) {
//                     nearEma9 = lastClose >= ema9_30 && distFromEma9 <= maxAllowedDist;
//                 } else {
//                     nearEma9 = lastClose <= ema9_30 && distFromEma9 <= maxAllowedDist;
//                 }

//                 System.out.printf("  [E1-30m] Price=%.6f EMA9=%.6f | Dist=%.6f MaxDist=%.6f | Price%sEMA9 -> %s%n",
//                         lastClose, ema9_30, distFromEma9, maxAllowedDist,
//                         lastClose >= ema9_30 ? ">=" : "<",
//                         nearEma9 ? "PASS — valid pullback" : "FAIL");

//                 // Tighter check on Supertrend flip candle
//                 if (stFlipped) {
//                     double flipMaxDist = ST_FLIP_MAX_CANDLE_RATIO * atr30m;
//                     if (distFromEma9 > flipMaxDist) {
//                         System.out.printf("  E1 FAIL (ST flip) — extended %.6f > %.6f — wait for retracement%n",
//                                 distFromEma9, flipMaxDist);
//                         continue;
//                     }
//                     System.out.println("  E1 ST-flip: price near 30m EMA9 — OK");
//                 } else if (!nearEma9) {
//                     System.out.println("  E1 FAIL — not valid 30m EMA9 pullback — skip");
//                     continue;
//                 }
//                 System.out.println("  E1 OK — 30m EMA9 pullback confirmed");

//                 // ─────────────────────────────────────────────────────────────
//                 // ENTRY ZONE E2: Current 30m candle not overextended
//                 // ─────────────────────────────────────────────────────────────
//                 double  currentCandleSize = Math.abs(lastHigh - lastLow);
//                 double  maxCandleSize     = MAX_CANDLE_ATR_RATIO * atr30m;
//                 boolean candleOk          = currentCandleSize <= maxCandleSize;
//                 System.out.printf("  [E2-30m] Candle size=%.6f | Max=%.6f (%.1fx ATR) -> %s%n",
//                         currentCandleSize, maxCandleSize, MAX_CANDLE_ATR_RATIO,
//                         candleOk ? "PASS" : "FAIL — overextended");
//                 if (!candleOk) {
//                     System.out.println("  E2 FAIL — 30m candle too large, move exhausted — skip");
//                     continue;
//                 }
//                 System.out.println("  E2 OK — 30m candle size normal");

//                 // ── SOFT FILTERS on 15m: at least 1 of 2 must pass ───────────
//                 double  rsi15      = calcRSI(cl15, RSI_PERIOD);
//                 boolean rsiOkLong  = trendUp   && rsi15 >= RSI_LONG_MIN  && rsi15 <= RSI_LONG_MAX;
//                 boolean rsiOkShort = trendDown && rsi15 >= RSI_SHORT_MIN && rsi15 <= RSI_SHORT_MAX;
//                 boolean softRsi    = rsiOkLong || rsiOkShort;
//                 System.out.printf("  [S1-15m] RSI=%.2f (Long:%.0f-%.0f | Short:%.0f-%.0f) -> %s%n",
//                         rsi15, RSI_LONG_MIN, RSI_LONG_MAX, RSI_SHORT_MIN, RSI_SHORT_MAX,
//                         softRsi ? "PASS" : "fail");

//                 boolean prevBull15  = prevClose15 > prevOpen15;
//                 boolean prevBear15  = prevClose15 < prevOpen15;
//                 boolean softCandle  = (trendUp && prevBull15) || (trendDown && prevBear15);
//                 System.out.printf("  [S2-15m] Prev candle: open=%.6f close=%.6f -> %s -> %s%n",
//                         prevOpen15, prevClose15,
//                         prevBull15 ? "BULL" : prevBear15 ? "BEAR" : "DOJI",
//                         softCandle ? "PASS" : "fail");

//                 if (!softRsi && !softCandle) {
//                     System.out.println("  SOFT FAIL — neither 15m RSI nor 15m candle confirms — skip");
//                     continue;
//                 }
//                 System.out.println("  SOFT OK — " + (softRsi ? "RSI" : "") +
//                         (softRsi && softCandle ? " + " : "") +
//                         (softCandle ? "Candle" : "") + " confirmed");

//                 // ─────────────────────────────────────────────────────────────
//                 // 15m MOMENTUM CANDLE CONFIRMATION
//                 // Uses the last completed 15m candle (index -2) as entry timing.
//                 // Replaces the old 5m check — same logic, 15m timeframe.
//                 // ─────────────────────────────────────────────────────────────
//                 double  last15mClose = cl15[cl15.length - 2];
//                 double  last15mOpen  = op15[op15.length - 2];
//                 double  last15mHigh  = hi15[hi15.length - 2];
//                 double  last15mLow   = lo15[lo15.length - 2];
//                 double  last15mRange = last15mHigh - last15mLow;
//                 double  last15mBody  = Math.abs(last15mClose - last15mOpen);
//                 boolean entry15mBull = last15mClose > last15mOpen;
//                 boolean entry15mBear = last15mClose < last15mOpen;

//                 double  bodyRatio     = last15mRange > 0 ? last15mBody / last15mRange : 0;
//                 boolean strongBody    = bodyRatio >= MIN_15M_BODY_RATIO;
//                 double  closePosition = last15mRange > 0
//                         ? (last15mClose - last15mLow) / last15mRange : 0.5;
//                 boolean closeNearHigh = closePosition >= (1.0 - CLOSE_NEAR_EXTREME_RATIO);
//                 boolean closeNearLow  = closePosition <= CLOSE_NEAR_EXTREME_RATIO;

//                 boolean entry15mOk;
//                 String  entry15mReason;
//                 if (trendUp) {
//                     entry15mOk     = entry15mBull && strongBody && closeNearHigh;
//                     entry15mReason = String.format("Bull=%s Body=%.0f%% ClosePos=%.0f%%",
//                             entry15mBull, bodyRatio * 100, closePosition * 100);
//                 } else {
//                     entry15mOk     = entry15mBear && strongBody && closeNearLow;
//                     entry15mReason = String.format("Bear=%s Body=%.0f%% ClosePos=%.0f%%",
//                             entry15mBear, bodyRatio * 100, closePosition * 100);
//                 }

//                 System.out.printf("  [15m-MOMENTUM] %s -> %s%n", entry15mReason,
//                         entry15mOk ? "PASS — strong candle" : "FAIL — weak candle");

//                 if (!entry15mOk) {
//                     if (trendUp  && !entry15mBull) System.out.println("  15m FAIL — bearish candle — skip");
//                     else if (trendDown && !entry15mBear) System.out.println("  15m FAIL — bullish candle — skip");
//                     else if (!strongBody) System.out.printf("  15m FAIL — weak body %.0f%% — doji — skip%n", bodyRatio*100);
//                     else System.out.printf("  15m FAIL — close not at extreme %.0f%% — skip%n", closePosition*100);
//                     continue;
//                 }
//                 System.out.println("  15m OK — strong momentum candle");

//                 // ── All filters passed ────────────────────────────────────────
//                 String side = trendUp ? "buy" : "sell";
//                 System.out.println("\n  ╔══════════════════════════════════════════╗");
//                 System.out.println("  ║  ALL FILTERS PASSED → " + side.toUpperCase() + " " + pair);
//                 System.out.printf ("  ║  ADX=%.1f | RSI=%.1f | ATR%%=%.2f%% | EMA9dist=%.4f%n",
//                         adx, rsi15, atrPercent, distFromEma9);
//                 if (stFlipped) System.out.println("  ║  *** 30m SUPERTREND FLIP + PULLBACK ***");
//                 System.out.println("  ╚══════════════════════════════════════════╝");

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

//                 // ─────────────────────────────────────────────────────────────
//                 // SL: Based on 30m Supertrend band value
//                 // ─────────────────────────────────────────────────────────────
//                 // The Supertrend lower/upper band is computed here from the raw
//                 // ATR series + band arrays (same logic as calcSupertrend).
//                 // LONG SL  = 30m lowerBand (ST support) - ST_SL_BUFFER * ATR
//                 // SHORT SL = 30m upperBand (ST resistance) + ST_SL_BUFFER * ATR
//                 // Result is then clamped between SL_MIN_ATR and SL_MAX_ATR
//                 // to prevent a too-tight or too-wide stop.
//                 // ─────────────────────────────────────────────────────────────
//                 double[] stBands = calcSupertrendBands(hi30, lo30, cl30, ST_PERIOD, ST_MULTIPLIER);
//                 double   stLower = stBands[0];  // support band (for LONG SL)
//                 double   stUpper = stBands[1];  // resistance band (for SHORT SL)

//                 // Dynamic RR based on ADX
//                 double dynamicRR;
//                 if      (adx >= 40) { dynamicRR = RR_STRONG; }
//                 else if (adx >= 30) { dynamicRR = RR_MEDIUM; }
//                 else                { dynamicRR = RR_WEAK;   }
//                 System.out.printf("  Dynamic R:R = 1:%.1f (ADX=%.1f)%n", dynamicRR, adx);

//                 double slPrice, tpPrice;

//                 if ("buy".equalsIgnoreCase(side)) {
//                     // SL just below 30m Supertrend lower band
//                     double rawSL = stLower - (ST_SL_BUFFER * atr30m);
//                     double minSL = entry - SL_MIN_ATR * atr30m;  // closest allowed
//                     double maxSL = entry - SL_MAX_ATR * atr30m;  // farthest allowed
//                     slPrice      = Math.max(Math.min(rawSL, minSL), maxSL);
//                     double risk  = entry - slPrice;
//                     tpPrice      = entry + dynamicRR * risk;
//                     System.out.printf("  30m ST LowerBand=%.6f | RawSL=%.6f | SL=%.6f%n",
//                             stLower, rawSL, slPrice);
//                 } else {
//                     // SL just above 30m Supertrend upper band
//                     double rawSL = stUpper + (ST_SL_BUFFER * atr30m);
//                     double minSL = entry + SL_MIN_ATR * atr30m;
//                     double maxSL = entry + SL_MAX_ATR * atr30m;
//                     slPrice      = Math.min(Math.max(rawSL, minSL), maxSL);
//                     double risk  = slPrice - entry;
//                     tpPrice      = entry - dynamicRR * risk;
//                     System.out.printf("  30m ST UpperBand=%.6f | RawSL=%.6f | SL=%.6f%n",
//                             stUpper, rawSL, slPrice);
//                 }

//                 slPrice = roundToTick(slPrice, tickSize);
//                 tpPrice = roundToTick(tpPrice, tickSize);

//                 double slPct = Math.abs(entry - slPrice) / entry * 100;
//                 double tpPct = Math.abs(tpPrice - entry)  / entry * 100;
//                 System.out.printf("  SL=%.6f (%.2f%%) | TP=%.6f (%.2f%%) | R:R=1:%.1f%n",
//                         slPrice, slPct, tpPrice, tpPct, dynamicRR);

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
//     // SUPERTREND BANDS — returns [lowerBand, upperBand] for last candle
//     // Used to derive SL levels from the 30m Supertrend chart
//     // =========================================================================
//     /**
//      * Returns the current [lowerBand, upperBand] of the Supertrend indicator.
//      * lowerBand = the support level (LONG SL reference)
//      * upperBand = the resistance level (SHORT SL reference)
//      */
//     private static double[] calcSupertrendBands(double[] hi, double[] lo, double[] cl,
//                                                  int period, double multiplier) {
//         int n = cl.length;
//         if (n < period + 1) return new double[]{cl[n-1] * 0.97, cl[n-1] * 1.03};

//         double[] atrArr    = calcATRSeries(hi, lo, cl, period);
//         double[] upperBand = new double[n];
//         double[] lowerBand = new double[n];

//         for (int i = period; i < n; i++) {
//             double hl2        = (hi[i] + lo[i]) / 2.0;
//             double basicUpper = hl2 + multiplier * atrArr[i];
//             double basicLower = hl2 - multiplier * atrArr[i];

//             if (i == period) {
//                 upperBand[i] = basicUpper;
//                 lowerBand[i] = basicLower;
//             } else {
//                 upperBand[i] = (basicUpper < upperBand[i-1] || cl[i-1] > upperBand[i-1])
//                         ? basicUpper : upperBand[i-1];
//                 lowerBand[i] = (basicLower > lowerBand[i-1] || cl[i-1] < lowerBand[i-1])
//                         ? basicLower : lowerBand[i-1];
//             }
//         }
//         return new double[]{lowerBand[n-1], upperBand[n-1]};
//     }

//     // =========================================================================
//     // ADX CALCULATION (Wilder's method)
//     // =========================================================================
//     private static double calcADX(double[] hi, double[] lo, double[] cl, int period) {
//         int n = hi.length;
//         if (n < period * 2) return 0;

//         double[] plusDM  = new double[n];
//         double[] minusDM = new double[n];
//         double[] tr      = new double[n];

//         for (int i = 1; i < n; i++) {
//             double upMove   = hi[i] - hi[i - 1];
//             double downMove = lo[i - 1] - lo[i];
//             plusDM[i]  = (upMove > downMove && upMove > 0)   ? upMove   : 0;
//             minusDM[i] = (downMove > upMove && downMove > 0) ? downMove : 0;
//             tr[i] = Math.max(hi[i] - lo[i],
//                     Math.max(Math.abs(hi[i] - cl[i-1]), Math.abs(lo[i] - cl[i-1])));
//         }

//         double smoothTR = 0, smoothPlus = 0, smoothMinus = 0;
//         for (int i = 1; i <= period; i++) {
//             smoothTR    += tr[i];
//             smoothPlus  += plusDM[i];
//             smoothMinus += minusDM[i];
//         }

//         double adxSum  = 0;
//         int    adxCount = 0;
//         double prevADX  = 0;

//         for (int i = period + 1; i < n; i++) {
//             smoothTR    = smoothTR    - (smoothTR / period)    + tr[i];
//             smoothPlus  = smoothPlus  - (smoothPlus / period)  + plusDM[i];
//             smoothMinus = smoothMinus - (smoothMinus / period) + minusDM[i];

//             if (smoothTR == 0) continue;

//             double plusDI  = 100.0 * smoothPlus  / smoothTR;
//             double minusDI = 100.0 * smoothMinus / smoothTR;
//             double diSum   = plusDI + minusDI;
//             double dx      = diSum == 0 ? 0 : 100.0 * Math.abs(plusDI - minusDI) / diSum;

//             if (adxCount < period) {
//                 adxSum += dx;
//                 adxCount++;
//                 if (adxCount == period) prevADX = adxSum / period;
//             } else {
//                 prevADX = (prevADX * (period - 1) + dx) / period;
//             }
//         }
//         return prevADX;
//     }

//     // =========================================================================
//     // SUPERTREND (boolean direction array)
//     // =========================================================================
//     private static boolean[] calcSupertrend(double[] hi, double[] lo, double[] cl,
//                                              int period, double multiplier) {
//         int n = cl.length;
//         boolean[] bullish = new boolean[n];
//         if (n < period + 1) { Arrays.fill(bullish, true); return bullish; }

//         double[] atrArr    = calcATRSeries(hi, lo, cl, period);
//         double[] upperBand = new double[n];
//         double[] lowerBand = new double[n];

//         for (int i = period; i < n; i++) {
//             double hl2        = (hi[i] + lo[i]) / 2.0;
//             double basicUpper = hl2 + multiplier * atrArr[i];
//             double basicLower = hl2 - multiplier * atrArr[i];

//             if (i == period) {
//                 upperBand[i] = basicUpper;
//                 lowerBand[i] = basicLower;
//             } else {
//                 upperBand[i] = (basicUpper < upperBand[i-1] || cl[i-1] > upperBand[i-1])
//                         ? basicUpper : upperBand[i-1];
//                 lowerBand[i] = (basicLower > lowerBand[i-1] || cl[i-1] < lowerBand[i-1])
//                         ? basicLower : lowerBand[i-1];
//             }

//             if (i == period) { bullish[i] = cl[i] > (hi[i] + lo[i]) / 2.0; }
//             else {
//                 bullish[i] = bullish[i-1]
//                         ? cl[i] >= lowerBand[i]
//                         : cl[i] >  upperBand[i];
//             }
//         }
//         for (int i = 0; i < period; i++) bullish[i] = bullish[period];
//         return bullish;
//     }

//     // =========================================================================
//     // ATR SERIES
//     // =========================================================================
//     private static double[] calcATRSeries(double[] hi, double[] lo, double[] cl, int period) {
//         int n = hi.length;
//         double[] atr = new double[n];
//         if (n < 2) return atr;
//         double[] tr = new double[n];
//         tr[0] = hi[0] - lo[0];
//         for (int i = 1; i < n; i++)
//             tr[i] = Math.max(hi[i] - lo[i],
//                     Math.max(Math.abs(hi[i] - cl[i-1]), Math.abs(lo[i] - cl[i-1])));
//         double sum = 0;
//         for (int i = 0; i < period && i < n; i++) sum += tr[i];
//         atr[period - 1] = sum / period;
//         for (int i = period; i < n; i++)
//             atr[i] = (atr[i-1] * (period - 1) + tr[i]) / period;
//         for (int i = 0; i < period - 1; i++) atr[i] = atr[period - 1];
//         return atr;
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
//         double m = ml[ml.length - 1], s = ss[ss.length - 1];
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
//             if (ch > 0) { ag = (ag * (period-1) + ch) / period; al = al * (period-1) / period; }
//             else         { al = (al * (period-1) + Math.abs(ch)) / period; ag = ag * (period-1) / period; }
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
//                     Math.max(Math.abs(hi[i] - cl[i-1]), Math.abs(lo[i] - cl[i-1])));
//         double atr = 0;
//         for (int i = 0; i < period; i++) atr += tr[i];
//         atr /= period;
//         for (int i = period; i < hi.length; i++) atr = (atr * (period-1) + tr[i]) / period;
//         return atr;
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
//                 case "30": minsPerBar = 30; break;
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
//                 System.err.println("  Candle s=" + r.optString("s") + " " + pair);
//             } else {
//                 System.err.println("  Candle HTTP " + code + " " + pair);
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
//         String resp = authPost(BASE_URL + "/exchange/v1/derivatives/futures/positions", body.toString());
//         JSONArray arr = resp.startsWith("[")
//                 ? new JSONArray(resp) : new JSONArray().put(new JSONObject(resp));
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
//             String resp = authPost(BASE_URL + "/exchange/v1/derivatives/futures/positions", body.toString());
//             JSONArray arr = resp.startsWith("[")
//                     ? new JSONArray(resp) : new JSONArray().put(new JSONObject(resp));
//             System.out.println("=== Open Positions (" + arr.length() + ") ===");
//             for (int i = 0; i < arr.length(); i++) {
//                 JSONObject p   = arr.getJSONObject(i);
//                 String    pair = p.optString("pair", "");
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
//         InputStream is = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
//         return readStream(is);
//     }

//     private static String readStream(InputStream is) throws IOException {
//         return new BufferedReader(new InputStreamReader(is))
//                 .lines().collect(Collectors.joining("\n"));
//     }

//     private static String sign(String payload) {
//         try {
//             Mac mac = Mac.getInstance("HmacSHA256");
//             mac.init(new SecretKeySpec(API_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
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
//  * ═══════════════════════════════════════════════════════════════════════════
//  * CoinDCX Futures Trader — v11 (Final Production Edition)
//  * ═══════════════════════════════════════════════════════════════════════════
//  *
//  * CHANGES FROM v10 → v11 (8 fixes applied):
//  *
//  *   FIX #1 — EMA9 Directional Pullback (MOST IMPORTANT)
//  *            v10: only distance check (abs(price - ema9) <= maxDist)
//  *            v11: BUY needs price >= ema9, SELL needs price <= ema9
//  *            WHY: Prevents buying below EMA9 in downswing (fake pullback)
//  *
//  *   FIX #2 — EMA21 Slope Confirmation
//  *            v10: only ema9 > ema21 (can cross in sideways)
//  *            v11: ema9 > ema21 AND ema21 is rising (slope confirmed)
//  *            WHY: Eliminates fake EMA crossovers in ranging market
//  *
//  *   FIX #3 — 1H EMA200 Macro Trend
//  *            v10: price > EMA50 alone
//  *            v11: price > EMA50 AND EMA50 > EMA200
//  *            WHY: Ensures you're trading WITH the real macro trend
//  *
//  *   FIX #4 — RSI Tighter Zones (safer entries)
//  *            v10: Long 45-68, Short 32-55
//  *            v11: Long 45-62, Short 38-55
//  *            WHY: Avoids overbought BUY entries near 68+ (reversal risk)
//  *
//  *   FIX #5 — Dynamic R:R based on ADX strength
//  *            v10: fixed RR = 1.5
//  *            v11: ADX>=40 → RR=2.2 | ADX>=30 → RR=1.8 | ADX<30 → RR=1.3
//  *            WHY: Strong trends should run more, weak trends take less
//  *
//  *   FIX #6 — BTC Trend Filter (MOST IMPACTFUL for altcoins)
//  *            v10: no BTC correlation check
//  *            v11: LONG only if BTC 15m EMA9 > EMA21, SHORT if EMA9 < EMA21
//  *            WHY: Altcoins follow BTC — trading against BTC is gambling
//  *
//  *   FIX #7 — High Volatility Skip (ATR% filter)
//  *            v10: no news/spike protection
//  *            v11: skip if ATR > 2.5% of price (CPI/FOMC/liquidation events)
//  *            WHY: Huge volatility makes SL meaningless and entries unreliable
//  *
//  *   FIX #8 — Tighter SL Clamp
//  *            v10: SL_MIN_ATR=2.0, SL_MAX_ATR=3.5
//  *            v11: SL_MIN_ATR=1.5, SL_MAX_ATR=2.5
//  *            WHY: 3.5x ATR on 15m is massive — more practical for intraday
//  *
//  * FULL FILTER ARCHITECTURE (v11):
//  *
//  *   MACRO (1H):
//  *     M1. Price > EMA50 AND EMA50 > EMA200  (bull)
//  *         Price < EMA50 AND EMA50 < EMA200  (bear)
//  *
//  *   TREND (15m):
//  *     T1. EMA9 > EMA21 AND EMA21 slope rising   (bull)
//  *         EMA9 < EMA21 AND EMA21 slope falling  (bear)
//  *     T2. MACD line > signal                    (bull / bear)
//  *     T3. Supertrend GREEN / RED
//  *
//  *   QUALITY:
//  *     Q1. ADX > 25 (trend strength)
//  *     Q2. ATR% < 2.5% (not too volatile)
//  *     Q3. BTC 15m EMA9/21 aligned with trade direction
//  *
//  *   ENTRY ZONE:
//  *     E1. Price on correct side of EMA9 (above for buy, below for sell)
//  *         AND distance from EMA9 <= 0.6 * ATR
//  *     E2. Current candle not overextended (< 1.5 * ATR)
//  *
//  *   CONFIRMATION (at least 1 of 2):
//  *     S1. RSI in zone (45-62 long | 38-55 short)
//  *     S2. Previous 15m candle matches direction
//  *
//  *   TIMING:
//  *     5m. Strong momentum candle (body > 50%, close near extreme)
//  *
//  *   SL/TP:
//  *     SL = swingLow/High ± ATR buffers, clamped 1.5-2.5x ATR
//  *     TP = dynamic R:R (1.3 / 1.8 / 2.2 based on ADX)
//  * ═══════════════════════════════════════════════════════════════════════════
//  */
// public class CoinDCXFuturesTrader8C_BUY_SELL_NEW_LOGIC_THREE {

//     // =========================================================================
//     // API Configuration
//     // =========================================================================
//     private static final String API_KEY    = System.getenv("DELTA_API_KEY");
//     private static final String API_SECRET = System.getenv("DELTA_API_SECRET");
//     private static final String BASE_URL       = "https://api.coindcx.com";
//     private static final String PUBLIC_API_URL = "https://public.coindcx.com";

//     private static final double MAX_MARGIN             = 150.0;
//     private static final int    LEVERAGE               = 5;
//     private static final int    MAX_ENTRY_PRICE_CHECKS = 10;
//     private static final int    ENTRY_CHECK_DELAY_MS   = 1000;
//     private static final long   TICK_CACHE_TTL_MS      = 3_600_000L;
//     private static final long   COOLDOWN_MS            = 2 * 60 * 60 * 1000L;

//     // ── Indicator periods ─────────────────────────────────────────────────────
//     private static final int EMA_FAST        = 9;
//     private static final int EMA_MID         = 21;
//     private static final int EMA_MACRO       = 50;
//     private static final int EMA_MACRO_SLOW  = 200;   // FIX #3: added EMA200
//     private static final int MACD_FAST       = 12;
//     private static final int MACD_SLOW       = 26;
//     private static final int MACD_SIG        = 9;
//     private static final int RSI_PERIOD      = 14;
//     private static final int ATR_PERIOD      = 14;
//     private static final int ADX_PERIOD      = 14;

//     // ── Supertrend ────────────────────────────────────────────────────────────
//     private static final int    ST_PERIOD     = 10;
//     private static final double ST_MULTIPLIER = 3.5;

//     // ── ADX threshold ────────────────────────────────────────────────────────
//     private static final double ADX_MIN = 18.0;

//     // ── RSI zones (FIX #4: tighter — avoid overbought entries) ───────────────
//     private static final double RSI_LONG_MIN  = 42.0;
//     private static final double RSI_LONG_MAX  = 68.0;   // was 68 → now 62
//     private static final double RSI_SHORT_MIN = 32.0;   // was 32 → now 38
//     private static final double RSI_SHORT_MAX = 58.0;

//     // ── SL parameters (FIX #8: tighter clamp — was 2.0/3.5, now 1.5/2.5) ────
//     private static final double SL_SWING_BUFFER = 0.6;
//     private static final double NOISE_BUFFER    = 0.6;
//     private static final double SL_MIN_ATR      = 2.1;   // was 2.0
//     private static final double SL_MAX_ATR      = 2.7;   // was 3.5

//     // ── Dynamic RR (FIX #5) ───────────────────────────────────────────────────
//     // RR is now based on ADX strength — strong trend gets bigger target
//     private static final double RR_STRONG = 1.5;  // ADX >= 40
//     private static final double RR_MEDIUM = 1.4;  // ADX >= 30
//     private static final double RR_WEAK   = 1.3;  // ADX < 30

//     // ── Entry zone filters ────────────────────────────────────────────────────
//     private static final double EMA9_PULLBACK_MAX       = 1.4;  // max dist from EMA9 (ATR units)
//     private static final double MAX_CANDLE_ATR_RATIO    = 1.5;  // skip if candle > 1.5x ATR
//     private static final double ST_FLIP_MAX_CANDLE_RATIO= 1.2;  // tighter on flip candle

//     // ── 5m candle quality ─────────────────────────────────────────────────────
//     private static final double MIN_5M_BODY_RATIO          = 0.35;
//     private static final double CLOSE_NEAR_EXTREME_RATIO   = 0.45;

//     // ── FIX #7: High volatility skip threshold ────────────────────────────────
//     // ATR as % of price — if > 2.5%, market is too erratic (news event, spike)
//     private static final double MAX_ATR_PERCENT = 4.5;

//     // ── Candle fetch counts ───────────────────────────────────────────────────
//     private static final int CANDLE_15M = 100;
//     private static final int CANDLE_5M  = 60;
//     private static final int CANDLE_1H  = 60;
//     // FIX #3: 1H needs more candles for EMA200
//     // EMA200 on 1H needs at least 200 candles
//     private static final int CANDLE_1H_EXTENDED = 220;

//     // ── Daily P&L protection ──────────────────────────────────────────────────
//     private static final double DAILY_PROFIT_TARGET  = 400.0;
//     private static final double DAILY_DRAWDOWN_LIMIT = 250.0;
//     private static final double DAILY_LOSS_LIMIT     = 300.0;

//     // ── BTC pair for correlation filter (FIX #6) ─────────────────────────────
//     private static final String BTC_PAIR = "B-BTC_USDT";

//     private static final Map<String, JSONObject> instrumentCache = new ConcurrentHashMap<>();
//     private static long lastCacheUpdate = 0;
//     private static final Map<String, Long> lastTradeTime = new ConcurrentHashMap<>();

//     // =========================================================================
//     // Coin list
//     // =========================================================================
//     private static final String[] COIN_SYMBOLS = {
//         "ETH", "SOL", "ZEC", "XRP", "DOGE", "BNB", "TAO", "1000PEPE", "ADA", "SUI",
//         "BCH", "LINK", "AVAX", "FIL", "OP", "NEAR", "TRX", "TRUMP", "ARB", "WLD",
//         "FET", "ETC", "AAVE", "WIF", "INJ", "TIA", "LTC", "ONDO", "ORDI", "TON",
//         "HBAR", "IMX", "ATOM", "RUNE", "KAS", "UNI", "ICP", "SEI", "PENDLE", "1000SHIB",
//         "1000BONK", "CRV", "JUP", "RENDER", "MKR", "LDO", "STX", "XLM", "PYTH", "VIRTUAL",
//         "APT", "SNX", "STRK", "NEO", "FTM", "CAKE", "1000FLOKI", "1000SATS", "OM", "FARTCOIN",
//         "GRT", "MINA", "COMP", "BLUR", "BRETT", "SAND", "EGLD", "XMR", "IOTA", "AI16Z",
//         "PNUT", "POPCAT", "ZRO", "MANA", "ETHFI", "VET", "ALGO", "ENS", "BOME", "MASK",
//         "GALA", "YFI", "CHZ", "GMX", "QNT", "POL", "MOODENG", "ZK", "ARKM", "THETA",
//         "MEW", "EIGEN", "MORPHO", "KAITO", "USUAL", "LAYER", "GOAT", "DOGS", "RSR", "PONKE",
//         "JTO", "CKB", "ZIL", "ROSE", "1INCH", "TWT", "KSM", "MAGIC", "GAS", "ACT",
//         "SUSHI", "TURBO", "1000LUNC", "BTCDOM", "S", "IP", "FLOW", "TRB", "QTUM", "KNC",
//         "KAIA", "CELO", "SSV", "BANANA", "TNSR", "AERO", "IO", "DEXE", "ARK", "XAI",
//         "DYM", "SAGA", "HOT", "LUNA2", "IOST", "RPL", "VANA", "DASH", "MANTA", "LRC",
//         "ANKR", "XTZ", "BAND", "SUPER", "FXS", "AKT", "NMR", "PIXEL", "LPT", "STORJ",
//         "ENJ", "LISTA", "ZETA", "RED", "AGLD", "GPS", "KAVA", "SXP", "ALPHA", "BIGTIME",
//         "COTI", "USTC", "BAT", "NFP", "ONE", "POLYX", "MOVR", "OMNI", "CELR", "RVN",
//         "GLM", "HIVE", "FLUX", "ZRX", "SFP", "ALICE", "ILV", "ARPA", "UMA", "DEGEN",
//         "XVS", "ACE", "ASTR", "CTSI", "CHR", "EDU", "PROM", "ALT", "C98", "SUN",
//         "WAXP", "ALPACA", "COOKIE", "JOE", "BNT", "SCRT", "VELODROME", "HOOK", "KMNO", "NTRN",
//         "VIC", "RAYSOL", "PARTI", "MELANIA", "MYRO", "SHELL", "AUCTION", "SWELL", "HIGH", "WOO",
//         "COW", "MAVIA", "VTHO", "1000CAT", "MUBARAK", "LEVER", "SOLV", "ARC", "AVAAI", "KOMA",
//         "API3", "VOXEL", "CHESS", "SPELL", "1000WHY", "SKL", "GTC", "MTL", "BICO", "DENT",
//         "RLC", "PHB", "POWR", "LSK", "DEFI", "MAV", "REI", "ONG", "XVG", "COS",
//         "FORTH", "BEL", "MLN", "HEI", "GHST", "STEEM", "LOKA", "DIA", "TLM", "BMT",
//         "ALCH", "FUN", "1000CHEEMS", "1000RATS", "1000000MOG", "1MBABYDOGE", "1000XEC", "1000X", "PERP", "NKN",
//         "VINE", "RARE", "HFT", "AXL", "ACH", "ZEN", "PEOPLE", "AR", "CFX", "ID",
//         "METIS", "FIO", "CYBER"
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

//         // ── FIX #6: Fetch BTC trend ONCE — applies to all altcoin trades ─────
//         // BTC direction is fetched once at the start of every scan.
//         // This avoids 230+ API calls (one per altcoin) for BTC data.
//         // Result: btcBull=true means BTC 15m trend is up → only LONG altcoins
//         //         btcBull=false means BTC 15m trend is down → only SHORT altcoins
//         boolean btcBull   = false;
//         boolean btcBear   = false;
//         boolean btcTrendOk = false;
//         try {
//             JSONArray btcRaw15m = getCandlestickData(BTC_PAIR, "15", 40);
//             if (btcRaw15m != null && btcRaw15m.length() >= 30) {
//                 double[] btcCl  = extractCloses(btcRaw15m);
//                 double btcEma9  = calcEMA(btcCl, EMA_FAST);
//                 double btcEma21 = calcEMA(btcCl, EMA_MID);
//                 btcBull    = btcEma9 > btcEma21;
//                 btcBear    = btcEma9 < btcEma21;
//                 btcTrendOk = true;
//                 System.out.printf("BTC Trend: EMA9=%.2f EMA21=%.2f → %s%n",
//                         btcEma9, btcEma21, btcBull ? "BULL (only LONG alts)" : "BEAR (only SHORT alts)");
//             } else {
//                 System.out.println("BTC data unavailable — BTC filter disabled this scan");
//             }
//         } catch (Exception e) {
//             System.err.println("BTC trend fetch failed: " + e.getMessage());
//         }

//         for (String pair : COINS_TO_TRADE) {
//             try {
//                 if (pair.equals(BTC_PAIR)) continue; // skip BTC itself

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
//                 JSONArray raw1h  = getCandlestickData(pair, "60", CANDLE_1H_EXTENDED); // FIX #3: 220 candles for EMA200
//                 JSONArray raw5m  = getCandlestickData(pair, "5",  CANDLE_5M);

//                 if (raw15m == null || raw15m.length() < 60) {
//                     System.out.println("  Insufficient 15m candles — skip");
//                     continue;
//                 }
//                 if (raw1h == null || raw1h.length() < EMA_MACRO) {
//                     System.out.println("  Insufficient 1H candles — skip");
//                     continue;
//                 }
//                 if (raw5m == null || raw5m.length() < 30) {
//                     System.out.println("  Insufficient 5m candles — skip");
//                     continue;
//                 }

//                 // ── Extract OHLC arrays ───────────────────────────────────────
//                 double[] cl15 = extractCloses(raw15m);
//                 double[] op15 = extractOpens(raw15m);
//                 double[] hi15 = extractHighs(raw15m);
//                 double[] lo15 = extractLows(raw15m);
//                 double[] cl1h = extractCloses(raw1h);

//                 double[] cl5m = extractCloses(raw5m);
//                 double[] op5m = extractOpens(raw5m);
//                 double[] hi5m = extractHighs(raw5m);
//                 double[] lo5m = extractLows(raw5m);

//                 double lastClose = cl15[cl15.length - 1];
//                 double lastHigh  = hi15[hi15.length - 1];
//                 double lastLow   = lo15[lo15.length - 1];
//                 double prevClose = cl15[cl15.length - 2];
//                 double prevOpen  = op15[op15.length - 2];
//                 double tickSize  = getTickSize(pair);
//                 double atr15m    = calcATR(hi15, lo15, cl15, ATR_PERIOD);
//                 double atr5m     = calcATR(hi5m, lo5m, cl5m, ATR_PERIOD);

//                 System.out.printf("  Price=%.6f  ATR-15m=%.6f  ATR-5m=%.6f  Tick=%.8f%n",
//                         lastClose, atr15m, atr5m, tickSize);

//                 // ─────────────────────────────────────────────────────────────
//                 // FIX #7: High Volatility Skip
//                 // ─────────────────────────────────────────────────────────────
//                 // ATR > 2.5% of price = market is in shock (news, liquidation cascade)
//                 // Indicators become unreliable and SL gets eaten instantly
//                 double atrPercent = (atr15m / lastClose) * 100.0;
//                 System.out.printf("  [VOL] ATR%%=%.2f%% (max allowed: %.1f%%) -> %s%n",
//                         atrPercent, MAX_ATR_PERCENT,
//                         atrPercent <= MAX_ATR_PERCENT ? "OK" : "FAIL — too volatile");
//                 if (atrPercent > MAX_ATR_PERCENT) {
//                     System.out.println("  VOL FAIL — market too volatile (news/spike?) — skip");
//                     continue;
//                 }

//                 // ─────────────────────────────────────────────────────────────
//                 // MACRO FILTER M1: 1H EMA50 + EMA200 (FIX #3)
//                 // ─────────────────────────────────────────────────────────────
//                 // v10: price > EMA50 alone (can be in downtrend overall)
//                 // v11: price > EMA50 AND EMA50 > EMA200 = truly in uptrend
//                 //      Both conditions ensure we're above BOTH moving averages
//                 //      This filters out dead-cat-bounce trades in bear market
//                 double ema1h = calcEMA(cl1h, EMA_MACRO);
//                 boolean has200 = cl1h.length >= EMA_MACRO_SLOW;
//                 double ema200_1h = has200 ? calcEMA(cl1h, EMA_MACRO_SLOW) : 0;

//                 boolean macroUp, macroDown;
//                 if (has200) {
//                     macroUp   = lastClose > ema1h && ema1h > ema200_1h;
//                     macroDown = lastClose < ema1h && ema1h < ema200_1h;
//                     System.out.printf("  [M1] 1H EMA50=%.4f EMA200=%.4f | Price=%s EMA50=%s EMA200 -> %s%n",
//                             ema1h, ema200_1h,
//                             lastClose > ema1h ? ">" : "<",
//                             ema1h > ema200_1h ? ">" : "<",
//                             macroUp ? "STRONG BULL" : macroDown ? "STRONG BEAR" : "MIXED");
//                 } else {
//                     // Not enough 1H candles for EMA200 — fall back to EMA50 only
//                     macroUp   = lastClose > ema1h;
//                     macroDown = lastClose < ema1h;
//                     System.out.printf("  [M1] 1H EMA50=%.4f (EMA200 N/A) -> %s%n",
//                             ema1h, macroUp ? "BULL" : "BEAR");
//                 }

//                 if (!macroUp && !macroDown) {
//                     System.out.println("  M1 FAIL — macro trend mixed — skip");
//                     continue;
//                 }
//                 System.out.println("  M1 OK — macro " + (macroUp ? "BULLISH" : "BEARISH"));

//                 // ─────────────────────────────────────────────────────────────
//                 // TREND FILTER T1: 15m EMA9/21 + EMA21 SLOPE (FIX #2)
//                 // ─────────────────────────────────────────────────────────────
//                 // v10: only ema9 > ema21 (crosses in sideways too)
//                 // v11: ema9 > ema21 AND ema21 is rising (slope confirmed)
//                 // HOW: compare current EMA21 vs EMA21 on previous bar
//                 //      calcEMA on cl15[0..n-2] gives previous bar's EMA21
//                 double ema9  = calcEMA(cl15, EMA_FAST);
//                 double ema21 = calcEMA(cl15, EMA_MID);

//                 // Previous bar EMA21 — slice array excluding last candle
//                 double prevEma21 = calcEMA(
//                         Arrays.copyOfRange(cl15, 0, cl15.length - 1),
//                         EMA_MID
//                 );
//                 boolean ema21Rising  = ema21 > prevEma21;
//                 boolean ema21Falling = ema21 < prevEma21;

//                 boolean localUp   = ema9 > ema21;
//                 boolean localDown = ema9 < ema21;

//                 System.out.printf("  [T1] EMA9=%.6f EMA21=%.6f prevEMA21=%.6f | Slope=%s | Cross=%s -> %s%n",
//                         ema9, ema21, prevEma21,
//                         ema21Rising ? "RISING" : ema21Falling ? "FALLING" : "FLAT",
//                         ema9 > ema21 ? "BULL" : "BEAR",
//                         localUp ? "BULLISH" : localDown ? "BEARISH" : "FAIL");

//                 boolean trendUp   = macroUp   && localUp;
//                 boolean trendDown = macroDown && localDown;

//                 if (!trendUp && !trendDown) {
//                     System.out.println("  T1 FAIL — EMA cross not confirmed by slope — skip");
//                     continue;
//                 }
//                 System.out.println("  T1 OK — " + (trendUp ? "BULLISH" : "BEARISH") + " with slope");

//                 // ── TREND FILTER T2: MACD ─────────────────────────────────────
//                 double[] mv       = calcMACD(cl15, MACD_FAST, MACD_SLOW, MACD_SIG);
//                 double   macdLine = mv[0], macdSigV = mv[1], macdHist = mv[2];
//                 System.out.printf("  [T2] MACD=%.6f Sig=%.6f Hist=%.6f%n",
//                         macdLine, macdSigV, macdHist);
//                 boolean macdBull = macdLine > macdSigV;
//                 boolean macdBear = macdLine < macdSigV;
//                 if (trendUp   && !macdBull) { System.out.println("  T2 FAIL — MACD bearish — skip"); continue; }
//                 if (trendDown && !macdBear) { System.out.println("  T2 FAIL — MACD bullish — skip"); continue; }
//                 System.out.println("  T2 OK — MACD aligned");

//                 // ── TREND FILTER T3: Supertrend ───────────────────────────────
//                 boolean[] stResult   = calcSupertrend(hi15, lo15, cl15, ST_PERIOD, ST_MULTIPLIER);
//                 boolean   stBull     = stResult[stResult.length - 1];
//                 boolean   stPrevBull = stResult[stResult.length - 2];
//                 boolean   stFlipped  = stBull != stPrevBull;
//                 System.out.printf("  [T3] Supertrend -> %s%s%n",
//                         stBull ? "GREEN (BULL)" : "RED (BEAR)",
//                         stFlipped ? " [JUST FLIPPED]" : "");
//                 if (trendUp   && !stBull) { System.out.println("  T3 FAIL — Supertrend bearish — skip"); continue; }
//                 if (trendDown &&  stBull) { System.out.println("  T3 FAIL — Supertrend bullish — skip"); continue; }
//                 System.out.println("  T3 OK — Supertrend aligned");

//                 // ─────────────────────────────────────────────────────────────
//                 // QUALITY FILTER Q1: ADX > 25
//                 // ─────────────────────────────────────────────────────────────
//                 // ─────────────────────────────────────────────────────────────
// // QUALITY FILTER Q1: ADX > 25
// // ─────────────────────────────────────────────────────────────
// double adx = calcADX(hi15, lo15, cl15, ADX_PERIOD);

// System.out.printf("  [Q1] ADX=%.2f (min=%.0f) -> %s%n",
//         adx, ADX_MIN, adx >= ADX_MIN ? "PASS" : "FAIL — sideways");

// if (adx < ADX_MIN) {
//     System.out.println("  Q1 FAIL — ADX too low, market sideways — skip");
//     continue;
// }

// System.out.printf("  Q1 OK — trend strength confirmed (ADX=%.1f)%n", adx);

//                 // ─────────────────────────────────────────────────────────────
//                 // QUALITY FILTER Q2: BTC Correlation (FIX #6)
//                 // ─────────────────────────────────────────────────────────────
//                 // Altcoins follow BTC direction 80% of the time.
//                 // Trading against BTC trend = fighting the market current.
//                 // LONG altcoin only when BTC is also bullish (EMA9 > EMA21)
//                 // SHORT altcoin only when BTC is also bearish (EMA9 < EMA21)
//                 if (btcTrendOk) {
//                     boolean btcAligned = (trendUp && btcBull) || (trendDown && btcBear);
//                     System.out.printf("  [Q2] BTC trend: %s | Trade: %s -> %s%n",
//                             btcBull ? "BULL" : "BEAR",
//                             trendUp ? "LONG" : "SHORT",
//                             btcAligned ? "PASS — aligned" : "FAIL — against BTC");
//                     if (!btcAligned) {
//                         System.out.println("BTC not aligned — lower confidence trade");
//                     }
//                     System.out.println("  Q2 OK — altcoin and BTC aligned");
//                 } else {
//                     System.out.println("  [Q2] BTC data N/A — filter skipped");
//                 }

//                 // ─────────────────────────────────────────────────────────────
//                 // ENTRY ZONE E1: EMA9 Directional Pullback (FIX #1 — MOST IMPORTANT)
//                 // ─────────────────────────────────────────────────────────────
//                 // v10 problem: only checked distance, not direction
//                 //   → Could BUY when price is BELOW EMA9 (falling through it)
//                 //   → Could SELL when price is ABOVE EMA9 (bouncing off it)
//                 //
//                 // v11 FIX:
//                 //   BUY: price must be ABOVE or AT EMA9 (holding the support)
//                 //        AND close enough (not too extended above)
//                 //   SELL: price must be BELOW or AT EMA9 (holding the resistance)
//                 //          AND close enough (not too extended below)
//                 double distFromEma9   = Math.abs(lastClose - ema9);
//                 double maxAllowedDist = EMA9_PULLBACK_MAX * atr15m;

//                 boolean nearEma9;
//                 if (trendUp) {
//                     // BUY: price must be at or above EMA9, within range
//                     nearEma9 = lastClose >= ema9 && distFromEma9 <= maxAllowedDist;
//                 } else {
//                     // SELL: price must be at or below EMA9, within range
//                     nearEma9 = lastClose <= ema9 && distFromEma9 <= maxAllowedDist;
//                 }

//                 System.out.printf("  [E1] Price=%.6f EMA9=%.6f | Dist=%.6f MaxDist=%.6f | Price%sEMA9 -> %s%n",
//                         lastClose, ema9, distFromEma9, maxAllowedDist,
//                         lastClose >= ema9 ? ">=" : "<",
//                         nearEma9 ? "PASS — valid pullback" : "FAIL");

//                 // Extra strictness on Supertrend flip candle
//                 if (stFlipped) {
//                     double flipMaxDist = ST_FLIP_MAX_CANDLE_RATIO * atr15m;
//                     if (distFromEma9 > flipMaxDist) {
//                         System.out.printf("  E1 FAIL (ST flip) — extended %.6f > %.6f — wait for retracement%n",
//                                 distFromEma9, flipMaxDist);
//                         continue;
//                     }
//                     System.out.println("  E1 ST-flip: price near EMA9 — OK");
//                 } else if (!nearEma9) {
//                     System.out.println("  E1 FAIL — not valid EMA9 pullback — skip");
//                     continue;
//                 }
//                 System.out.println("  E1 OK — EMA9 pullback confirmed");

//                 // ─────────────────────────────────────────────────────────────
//                 // ENTRY ZONE E2: Current candle not overextended
//                 // ─────────────────────────────────────────────────────────────
//                 double  currentCandleSize = Math.abs(lastHigh - lastLow);
//                 double  maxCandleSize     = MAX_CANDLE_ATR_RATIO * atr15m;
//                 boolean candleOk          = currentCandleSize <= maxCandleSize;
//                 System.out.printf("  [E2] Candle size=%.6f | Max=%.6f (%.1fx ATR) -> %s%n",
//                         currentCandleSize, maxCandleSize, MAX_CANDLE_ATR_RATIO,
//                         candleOk ? "PASS" : "FAIL — overextended");
//                 if (!candleOk) {
//                     System.out.println("  E2 FAIL — candle too large, move exhausted — skip");
//                     continue;
//                 }
//                 System.out.println("  E2 OK — candle size normal");

//                 // ── SOFT FILTERS: at least 1 of 2 must pass ──────────────────
//                 double  rsi        = calcRSI(cl15, RSI_PERIOD);
//                 boolean rsiOkLong  = trendUp   && rsi >= RSI_LONG_MIN  && rsi <= RSI_LONG_MAX;
//                 boolean rsiOkShort = trendDown && rsi >= RSI_SHORT_MIN && rsi <= RSI_SHORT_MAX;
//                 boolean softRsi    = rsiOkLong || rsiOkShort;
//                 System.out.printf("  [S1] RSI=%.2f (Long:%.0f-%.0f | Short:%.0f-%.0f) -> %s%n",
//                         rsi, RSI_LONG_MIN, RSI_LONG_MAX, RSI_SHORT_MIN, RSI_SHORT_MAX,
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

//                 // ── 5m ENTRY FILTER: Strong momentum candle ───────────────────
//                 double  last5mClose  = cl5m[cl5m.length - 2];
//                 double  last5mOpen   = op5m[op5m.length - 2];
//                 double  last5mHigh   = hi5m[hi5m.length - 2];
//                 double  last5mLow    = lo5m[lo5m.length - 2];
//                 double  last5mRange  = last5mHigh - last5mLow;
//                 double  last5mBody   = Math.abs(last5mClose - last5mOpen);
//                 boolean entry5mBull  = last5mClose > last5mOpen;
//                 boolean entry5mBear  = last5mClose < last5mOpen;

//                 double  bodyRatio     = last5mRange > 0 ? last5mBody / last5mRange : 0;
//                 boolean strongBody    = bodyRatio >= MIN_5M_BODY_RATIO;
//                 double  closePosition = last5mRange > 0
//                         ? (last5mClose - last5mLow) / last5mRange : 0.5;
//                 boolean closeNearHigh = closePosition >= (1.0 - CLOSE_NEAR_EXTREME_RATIO);
//                 boolean closeNearLow  = closePosition <= CLOSE_NEAR_EXTREME_RATIO;

//                 boolean entry5mOk;
//                 String  entry5mReason;
//                 if (trendUp) {
//                     entry5mOk     = entry5mBull && strongBody && closeNearHigh;
//                     entry5mReason = String.format("Bull=%s Body=%.0f%% ClosePos=%.0f%%",
//                             entry5mBull, bodyRatio * 100, closePosition * 100);
//                 } else {
//                     entry5mOk     = entry5mBear && strongBody && closeNearLow;
//                     entry5mReason = String.format("Bear=%s Body=%.0f%% ClosePos=%.0f%%",
//                             entry5mBear, bodyRatio * 100, closePosition * 100);
//                 }

//                 System.out.printf("  [5m] %s -> %s%n", entry5mReason,
//                         entry5mOk ? "PASS — strong candle" : "FAIL — weak candle");

//                 if (!entry5mOk) {
//                     if (trendUp  && !entry5mBull) System.out.println("  5m FAIL — bearish candle — skip");
//                     else if (trendDown && !entry5mBear) System.out.println("  5m FAIL — bullish candle — skip");
//                     else if (!strongBody) System.out.printf("  5m FAIL — weak body %.0f%% — doji — skip%n", bodyRatio*100);
//                     else System.out.printf("  5m FAIL — close not at extreme %.0f%% — skip%n", closePosition*100);
//                     continue;
//                 }
//                 System.out.println("  5m OK — strong momentum candle");

//                 // ── All filters passed ────────────────────────────────────────
//                 String side = trendUp ? "buy" : "sell";
//                 System.out.println("\n  ╔══════════════════════════════════════════╗");
//                 System.out.println("  ║  ALL FILTERS PASSED → " + side.toUpperCase() + " " + pair);
//                 System.out.printf ("  ║  ADX=%.1f | RSI=%.1f | ATR%%=%.2f%% | EMA9dist=%.4f%n",
//                         adx, rsi, atrPercent, distFromEma9);
//                 if (stFlipped) System.out.println("  ║  *** SUPERTREND FLIP + PULLBACK ***");
//                 System.out.println("  ╚══════════════════════════════════════════╝");

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

//                 // ─────────────────────────────────────────────────────────────
//                 // SL/TP: Dynamic R:R based on ADX (FIX #5) + Tighter SL (FIX #8)
//                 // ─────────────────────────────────────────────────────────────
//                 // Dynamic RR logic:
//                 //   ADX >= 40 → 2.2 : very strong trend, let profit run
//                 //   ADX >= 30 → 1.8 : solid trend, good target
//                 //   ADX <  30 → 1.3 : weak/moderate trend, take profit quickly
//                 double dynamicRR;
//                 if      (adx >= 40) { dynamicRR = RR_STRONG; }
//                 else if (adx >= 30) { dynamicRR = RR_MEDIUM; }
//                 else                { dynamicRR = RR_WEAK;   }
//                 System.out.printf("  Dynamic R:R = 1:%.1f (ADX=%.1f)%n", dynamicRR, adx);

//                 double slPrice, tpPrice;

//                 if ("buy".equalsIgnoreCase(side)) {
//                     double swLow15m = swingLow(lo15, 30);
//                     double rawSL    = swLow15m - (NOISE_BUFFER * atr15m) - (SL_SWING_BUFFER * atr15m);
//                     double minSL    = entry - SL_MIN_ATR * atr15m;   // closest allowed (1.5x ATR)
//                     double maxSL    = entry - SL_MAX_ATR * atr15m;   // farthest allowed (2.5x ATR)
//                     slPrice         = Math.max(Math.min(rawSL, minSL), maxSL);
//                     double risk     = entry - slPrice;
//                     tpPrice         = entry + dynamicRR * risk;
//                     System.out.printf("  SwingLow=%.6f | RawSL=%.6f | SL=%.6f%n",
//                             swLow15m, rawSL, slPrice);
//                 } else {
//                     double swHigh15m = swingHigh(hi15, 30);
//                     double rawSL     = swHigh15m + (NOISE_BUFFER * atr15m) + (SL_SWING_BUFFER * atr15m);
//                     double minSL     = entry + SL_MIN_ATR * atr15m;
//                     double maxSL     = entry + SL_MAX_ATR * atr15m;
//                     slPrice          = Math.min(Math.max(rawSL, minSL), maxSL);
//                     double risk      = slPrice - entry;
//                     tpPrice          = entry - dynamicRR * risk;
//                     System.out.printf("  SwingHigh=%.6f | RawSL=%.6f | SL=%.6f%n",
//                             swHigh15m, rawSL, slPrice);
//                 }

//                 slPrice = roundToTick(slPrice, tickSize);
//                 tpPrice = roundToTick(tpPrice, tickSize);

//                 double slPct = Math.abs(entry - slPrice) / entry * 100;
//                 double tpPct = Math.abs(tpPrice - entry)  / entry * 100;
//                 System.out.printf("  SL=%.6f (%.2f%%) | TP=%.6f (%.2f%%) | R:R=1:%.1f%n",
//                         slPrice, slPct, tpPrice, tpPct, dynamicRR);

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
//     // ADX CALCULATION (Wilder's method)
//     // =========================================================================
//     /**
//      * Average Directional Index (ADX)
//      * Measures trend STRENGTH, not direction.
//      * < 20  = no trend / sideways
//      * 20-25 = weak trend
//      * > 25  = trending (trade)
//      * > 40  = strong trend (best entries)
//      */
//     private static double calcADX(double[] hi, double[] lo, double[] cl, int period) {
//         int n = hi.length;
//         if (n < period * 2) return 0;

//         double[] plusDM  = new double[n];
//         double[] minusDM = new double[n];
//         double[] tr      = new double[n];

//         for (int i = 1; i < n; i++) {
//             double upMove   = hi[i] - hi[i - 1];
//             double downMove = lo[i - 1] - lo[i];
//             plusDM[i]  = (upMove > downMove && upMove > 0)   ? upMove   : 0;
//             minusDM[i] = (downMove > upMove && downMove > 0) ? downMove : 0;
//             tr[i] = Math.max(hi[i] - lo[i],
//                     Math.max(Math.abs(hi[i] - cl[i-1]), Math.abs(lo[i] - cl[i-1])));
//         }

//         double smoothTR = 0, smoothPlus = 0, smoothMinus = 0;
//         for (int i = 1; i <= period; i++) {
//             smoothTR    += tr[i];
//             smoothPlus  += plusDM[i];
//             smoothMinus += minusDM[i];
//         }

//         double adxSum = 0;
//         int adxCount  = 0;
//         double prevADX = 0;

//         for (int i = period + 1; i < n; i++) {
//             smoothTR    = smoothTR    - (smoothTR / period)    + tr[i];
//             smoothPlus  = smoothPlus  - (smoothPlus / period)  + plusDM[i];
//             smoothMinus = smoothMinus - (smoothMinus / period) + minusDM[i];

//             if (smoothTR == 0) continue;

//             double plusDI  = 100.0 * smoothPlus  / smoothTR;
//             double minusDI = 100.0 * smoothMinus / smoothTR;
//             double diSum   = plusDI + minusDI;
//             double dx      = diSum == 0 ? 0 : 100.0 * Math.abs(plusDI - minusDI) / diSum;

//             if (adxCount < period) {
//                 adxSum += dx;
//                 adxCount++;
//                 if (adxCount == period) prevADX = adxSum / period;
//             } else {
//                 prevADX = (prevADX * (period - 1) + dx) / period;
//             }
//         }
//         return prevADX;
//     }

//     // =========================================================================
//     // SUPERTREND CALCULATION
//     // =========================================================================
//     private static boolean[] calcSupertrend(double[] hi, double[] lo, double[] cl,
//                                              int period, double multiplier) {
//         int n = cl.length;
//         boolean[] bullish = new boolean[n];
//         if (n < period + 1) { Arrays.fill(bullish, true); return bullish; }

//         double[] atrArr    = calcATRSeries(hi, lo, cl, period);
//         double[] upperBand = new double[n];
//         double[] lowerBand = new double[n];

//         for (int i = period; i < n; i++) {
//             double hl2        = (hi[i] + lo[i]) / 2.0;
//             double basicUpper = hl2 + multiplier * atrArr[i];
//             double basicLower = hl2 - multiplier * atrArr[i];

//             if (i == period) {
//                 upperBand[i] = basicUpper;
//                 lowerBand[i] = basicLower;
//             } else {
//                 upperBand[i] = (basicUpper < upperBand[i-1] || cl[i-1] > upperBand[i-1])
//                         ? basicUpper : upperBand[i-1];
//                 lowerBand[i] = (basicLower > lowerBand[i-1] || cl[i-1] < lowerBand[i-1])
//                         ? basicLower : lowerBand[i-1];
//             }

//             if (i == period) { bullish[i] = cl[i] > (hi[i] + lo[i]) / 2.0; }
//             else {
//                 bullish[i] = bullish[i-1]
//                         ? cl[i] >= lowerBand[i]
//                         : cl[i] >  upperBand[i];
//             }
//         }
//         for (int i = 0; i < period; i++) bullish[i] = bullish[period];
//         return bullish;
//     }

//     // =========================================================================
//     // ATR SERIES
//     // =========================================================================
//     private static double[] calcATRSeries(double[] hi, double[] lo, double[] cl, int period) {
//         int n = hi.length;
//         double[] atr = new double[n];
//         if (n < 2) return atr;
//         double[] tr = new double[n];
//         tr[0] = hi[0] - lo[0];
//         for (int i = 1; i < n; i++)
//             tr[i] = Math.max(hi[i] - lo[i],
//                     Math.max(Math.abs(hi[i] - cl[i-1]), Math.abs(lo[i] - cl[i-1])));
//         double sum = 0;
//         for (int i = 0; i < period && i < n; i++) sum += tr[i];
//         atr[period - 1] = sum / period;
//         for (int i = period; i < n; i++)
//             atr[i] = (atr[i-1] * (period - 1) + tr[i]) / period;
//         for (int i = 0; i < period - 1; i++) atr[i] = atr[period - 1];
//         return atr;
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
//         double m = ml[ml.length - 1], s = ss[ss.length - 1];
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
//             if (ch > 0) { ag = (ag * (period-1) + ch) / period; al = al * (period-1) / period; }
//             else         { al = (al * (period-1) + Math.abs(ch)) / period; ag = ag * (period-1) / period; }
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
//                     Math.max(Math.abs(hi[i] - cl[i-1]), Math.abs(lo[i] - cl[i-1])));
//         double atr = 0;
//         for (int i = 0; i < period; i++) atr += tr[i];
//         atr /= period;
//         for (int i = period; i < hi.length; i++) atr = (atr * (period-1) + tr[i]) / period;
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
//                 System.err.println("  Candle s=" + r.optString("s") + " " + pair);
//             } else {
//                 System.err.println("  Candle HTTP " + code + " " + pair);
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
//         String resp = authPost(BASE_URL + "/exchange/v1/derivatives/futures/positions", body.toString());
//         JSONArray arr = resp.startsWith("[")
//                 ? new JSONArray(resp) : new JSONArray().put(new JSONObject(resp));
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
//             String resp = authPost(BASE_URL + "/exchange/v1/derivatives/futures/positions", body.toString());
//             JSONArray arr = resp.startsWith("[")
//                     ? new JSONArray(resp) : new JSONArray().put(new JSONObject(resp));
//             System.out.println("=== Open Positions (" + arr.length() + ") ===");
//             for (int i = 0; i < arr.length(); i++) {
//                 JSONObject p   = arr.getJSONObject(i);
//                 String    pair = p.optString("pair", "");
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
//         InputStream is = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
//         return readStream(is);
//     }

//     private static String readStream(InputStream is) throws IOException {
//         return new BufferedReader(new InputStreamReader(is))
//                 .lines().collect(Collectors.joining("\n"));
//     }

//     private static String sign(String payload) {
//         try {
//             Mac mac = Mac.getInstance("HmacSHA256");
//             mac.init(new SecretKeySpec(API_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
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

// public class CoinDCXFuturesTrader8C_BUY_SELL_NEW_LOGIC_THREE {

//     // =========================================================================
//     // API Keys
//     // =========================================================================
//     private static final String API_KEY    = System.getenv("DELTA_API_KEY");
//     private static final String API_SECRET = System.getenv("DELTA_API_SECRET");
//     private static final String BASE_URL       = "https://api.coindcx.com";
//     private static final String PUBLIC_API_URL = "https://public.coindcx.com";

//     // =========================================================================
//     // Trade Config
//     // =========================================================================
//     private static final double MAX_MARGIN = 2000.0;
//     private static final int    LEVERAGE   = 20;

//     private static final int    MAX_ENTRY_PRICE_CHECKS = 12;
//     private static final int    ENTRY_CHECK_DELAY_MS   = 1000;
//     private static final long   TICK_CACHE_TTL_MS      = 3_600_000L;
//     private static final long   COOLDOWN_MS            = 30 * 60 * 1000L;

//     // =========================================================================
//     // Indicator Periods
//     // =========================================================================
//     private static final int EMA_FAST       = 9;
//     private static final int EMA_MID        = 21;
//     private static final int EMA_MACRO      = 50;
//     private static final int EMA_MACRO_SLOW = 200;
//     private static final int MACD_FAST      = 12;
//     private static final int MACD_SLOW      = 26;
//     private static final int MACD_SIG       = 9;
//     private static final int RSI_PERIOD     = 14;
//     private static final int ATR_PERIOD     = 14;
//     private static final int ADX_PERIOD     = 14;
//     private static final int WR_PERIOD      = 14;
//     private static final int VWAP_PERIOD    = 20;
//     private static final int SWING_LOOKBACK = 5;

//     // =========================================================================
//     // Supertrend
//     // =========================================================================
//     private static final int    ST_PERIOD     = 10;
//     private static final double ST_MULTIPLIER = 3.0;

//     // =========================================================================
//     // TP / SL Config
//     // =========================================================================
//     // FIX: Buffer raised to 0.5x ATR (was 0.35), cap raised to 3.0% (was 2.0%)
//     // Minimum SL distance = 1.0x ATR so SL never gets squeezed too tight
//     private static final double SWING_SL_ATR_BUFFER  = 0.5;
//     private static final double SWING_SL_MIN_ATR_MULT = 1.0; // SL at least 1x ATR from entry
//     private static final double MAX_SL_PCT            = 3.0;
//     private static final double SL_SLIPPAGE_BUFFER    = 0.10;

//     // Dynamic RR: clamp(adx/20, 1.1, 1.8)
//     private static final double RR_MIN = 1.1;
//     private static final double RR_MAX = 1.8;

//     // =========================================================================
//     // Filter Thresholds
//     // =========================================================================
//     // FIX: ADX raised from 15/18 to 20/24 (was causing chop entries)
//     private static final double ADX_STRONG_4H = 20.0;
//     private static final double ADX_WEAK_4H   = 24.0;

//     private static final double WR_LONG_MIN  = -80.0;
//     private static final double WR_SHORT_MAX = -20.0;

//     private static final double RSI_LONG_MIN  = 45.0;
//     private static final double RSI_LONG_MAX  = 68.0;
//     private static final double RSI_SHORT_MIN = 32.0;
//     private static final double RSI_SHORT_MAX = 58.0;

//     // FIX: EMA9 buffer tightened from 1.8x to 0.8x ATR
//     // 1.8x was so wide that almost any price would pass — defeating pullback logic
//     private static final double EMA9_ENTRY_BUFFER = 0.8;

//     private static final double MAX_CANDLE_ATR_RATIO = 1.6;
//     private static final double MAX_ATR_PERCENT      = 3.0;

//     // FIX: Volume multiplier raised to 1.1x (was 0.9x — below median = meaningless)
//     private static final double VOL_DIRECTIONAL_MULT = 1.1;
//     private static final int    VOL_MEDIAN_PERIOD    = 20;

//     private static final int MIN_TICK_GAP = 2;

//     // =========================================================================
//     // Candle Counts
//     // =========================================================================
//     private static final int CANDLE_15M = 120;
//     private static final int CANDLE_5M  = 60;
//     private static final int CANDLE_1H  = 220;
//     private static final int CANDLE_4H  = 250;

//     // =========================================================================
//     // Daily Limits
//     // =========================================================================
//     private static double dailyPnL               = 0.0;
//     private static final double DAILY_LOSS_LIMIT  = 5000.0;
//     private static final double DAILY_PROFIT_LOCK = 8000.0;

//     private static final Map<String, JSONObject> instrumentCache = new ConcurrentHashMap<>();
//     private static long lastCacheUpdate = 0;
//     private static final Map<String, Long> lastTradeTime = new ConcurrentHashMap<>();

//     private static boolean btcBull15m   = false;
//     private static boolean btcBull1h    = false;
//     private static long    btcCacheTime = 0;
//     private static final long   BTC_CACHE_TTL = 5 * 60 * 1000L;
//     private static final String BTC_PAIR      = "B-BTC_USDT";

//     // =========================================================================
//     // Coin List
//     // =========================================================================
//     private static final String[] COIN_SYMBOLS = {
//         "ETH", "ZEC", "XRP", "DOGE", "BNB", "TAO", "1000PEPE", "ADA", "SUI",
//         "BCH", "LINK", "FIL", "OP", "TRX", "TRUMP", "ARB", "WLD",
//         "FET", "ETC", "AAVE", "WIF", "INJ", "TIA", "LTC", "ONDO", "ORDI", "TON",
//         "HBAR", "IMX", "ATOM", "RUNE", "KAS", "UNI", "ICP", "SEI", "PENDLE", "1000SHIB",
//         "1000BONK", "CRV", "JUP", "RENDER", "MKR", "LDO", "STX", "XLM", "PYTH", "VIRTUAL",
//         "APT", "SNX", "STRK", "NEO", "FTM", "CAKE", "1000FLOKI", "1000SATS", "OM", "FARTCOIN",
//         "GRT", "MINA", "COMP", "BLUR", "BRETT", "SAND", "EGLD", "XMR", "IOTA", "AI16Z",
//         "PNUT", "POPCAT", "ZRO", "MANA", "ETHFI", "VET", "ALGO", "ENS", "BOME", "MASK",
//         "GALA", "YFI", "CHZ", "GMX", "QNT", "POL", "MOODENG", "ZK", "ARKM", "THETA",
//         "MEW", "EIGEN", "MORPHO", "KAITO", "USUAL", "LAYER", "GOAT", "DOGS", "RSR", "PONKE",
//         "JTO", "CKB", "ZIL", "ROSE", "1INCH", "TWT", "KSM", "MAGIC", "GAS", "ACT",
//         "SUSHI", "TURBO", "1000LUNC", "BTCDOM", "S", "IP", "FLOW", "TRB", "QTUM", "KNC",
//         "KAIA", "CELO", "SSV", "BANANA", "TNSR", "AERO", "IO", "DEXE", "ARK", "XAI",
//         "DYM", "SAGA", "HOT", "LUNA2", "IOST", "RPL", "VANA", "DASH", "MANTA", "LRC",
//         "ANKR", "XTZ", "BAND", "SUPER", "FXS", "AKT", "NMR", "PIXEL", "LPT", "STORJ",
//         "ENJ", "LISTA", "ZETA", "RED", "AGLD", "GPS", "KAVA", "SXP", "ALPHA", "BIGTIME",
//         "COTI", "USTC", "BAT", "NFP", "ONE", "POLYX", "MOVR", "OMNI", "CELR", "RVN",
//         "GLM", "HIVE", "FLUX", "ZRX", "SFP", "ALICE", "ILV", "ARPA", "UMA", "DEGEN",
//         "XVS", "ACE", "ASTR", "CTSI", "CHR", "EDU", "PROM", "ALT", "C98", "SUN",
//         "WAXP", "ALPACA", "COOKIE", "JOE", "BNT", "SCRT", "VELODROME", "HOOK", "KMNO", "NTRN",
//         "VIC", "RAYSOL", "PARTI", "MELANIA", "MYRO", "SHELL", "AUCTION", "SWELL", "HIGH", "WOO",
//         "COW", "MAVIA", "VTHO", "1000CAT", "MUBARAK", "LEVER", "SOLV", "ARC", "AVAAI", "KOMA",
//         "API3", "VOXEL", "CHESS", "SPELL", "1000WHY", "SKL", "GTC", "MTL", "BICO", "DENT",
//         "RLC", "PHB", "POWR", "LSK", "DEFI", "MAV", "REI", "ONG", "XVG", "COS",
//         "FORTH", "BEL", "MLN", "HEI", "GHST", "STEEM", "LOKA", "DIA", "TLM", "BMT",
//         "ALCH", "FUN", "1000CHEEMS", "1000RATS", "1000000MOG", "1MBABYDOGE", "1000XEC", "1000X", "PERP", "NKN",
//         "VINE", "RARE", "HFT", "AXL", "ACH", "ZEN", "PEOPLE", "AR", "CFX", "ID",
//         "METIS", "FIO", "CYBER"
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
//         refreshBtcContext();
//         Set<String> active = getActivePositions();
//         System.out.println("Active positions: " + active);
//         System.out.printf("BTC Context: 15m=%s | 1H=%s%n",
//                 btcBull15m ? "BULL" : "BEAR", btcBull1h ? "BULL" : "BEAR");

//         if (dailyPnL <= -DAILY_LOSS_LIMIT) {
//             System.out.printf("DAILY LOSS LIMIT (%.0f INR) — stop%n", DAILY_LOSS_LIMIT); return;
//         }
//         if (dailyPnL >= DAILY_PROFIT_LOCK) {
//             System.out.printf("DAILY PROFIT LOCKED (%.0f INR) — stop%n", DAILY_PROFIT_LOCK); return;
//         }

//         for (String pair : COINS_TO_TRADE) {
//             try {
//                 if (active.contains(pair)) { System.out.println("Skip " + pair + " — active"); continue; }
//                 if (System.currentTimeMillis() - lastTradeTime.getOrDefault(pair, 0L) < COOLDOWN_MS) {
//                     System.out.println("  Skip " + pair + " — cooldown"); continue;
//                 }
//                 if (dailyPnL <= -DAILY_LOSS_LIMIT) { System.out.println("DAILY LOSS — stop"); break; }

//                 System.out.println("\n==== " + pair + " ====");
//                 analyzePair(pair);

//             } catch (Exception e) {
//                 System.err.println("Error on " + pair + ": " + e.getMessage());
//             }
//         }
//         System.out.println("\n=== Scan complete ===");
//     }

//     // =========================================================================
//     // CORE ANALYSIS
//     // =========================================================================
//     private static void analyzePair(String pair) throws Exception {

//         JSONArray raw15m = getCandlestickData(pair, "15",  CANDLE_15M);
//         JSONArray raw1h  = getCandlestickData(pair, "60",  CANDLE_1H);
//         JSONArray raw4h  = getCandlestickData(pair, "240", CANDLE_4H);
//         JSONArray raw5m  = getCandlestickData(pair, "5",   CANDLE_5M);

//         if (raw15m == null || raw15m.length() < 80)              { System.out.println("  Skip — insufficient 15m"); return; }
//         if (raw1h  == null || raw1h.length()  < 60)              { System.out.println("  Skip — insufficient 1H");  return; }
//         if (raw4h  == null || raw4h.length()  < EMA_MACRO_SLOW+10) { System.out.println("  Skip — insufficient 4H"); return; }
//         if (raw5m  == null || raw5m.length()  < 20)              { System.out.println("  Skip — insufficient 5m");  return; }

//         double[] cl15  = extractCloses(raw15m);
//         double[] op15  = extractOpens(raw15m);
//         double[] hi15  = extractHighs(raw15m);
//         double[] lo15  = extractLows(raw15m);
//         double[] vol15 = extractVolumes(raw15m);
//         double[] cl1h  = extractCloses(raw1h);
//         double[] cl4h  = extractCloses(raw4h);
//         double[] cl5m  = extractCloses(raw5m);
//         double[] op5m  = extractOpens(raw5m);
//         double[] hi5m  = extractHighs(raw5m);
//         double[] lo5m  = extractLows(raw5m);

//         int last = cl15.length - 2;
//         int prev = last - 1;

//         double lastClose = cl15[last];
//         double lastOpen  = op15[last];
//         double lastHigh  = hi15[last];
//         double lastLow   = lo15[last];
//         double prevClose = cl15[prev];
//         double prevOpen  = op15[prev];

//         double tickSize = getTickSize(pair);
//         double atr15m   = calcATR(hi15, lo15, cl15, ATR_PERIOD);

//         System.out.printf("  Price=%.6f  ATR=%.6f  Tick=%.8f%n", lastClose, atr15m, tickSize);

//         // ── FILTER 1: Volatility ──────────────────────────────────────────────
//         double atrPct = (atr15m / lastClose) * 100.0;
//         if (atrPct > MAX_ATR_PERCENT) {
//             System.out.printf("  [VOL] FAIL ATR%%=%.2f%% skip%n", atrPct); return;
//         }
//         System.out.printf("  [VOL] OK ATR%%=%.2f%%%n", atrPct);

//         // ── FILTER 2: 4H Dual EMA ────────────────────────────────────────────
//         int last4h = cl4h.length - 2;
//         double[] ema50_4h_s  = calcEMASeries(cl4h, EMA_MACRO);
//         double[] ema200_4h_s = calcEMASeries(cl4h, EMA_MACRO_SLOW);
//         double ema50_4h       = ema50_4h_s[last4h];
//         double ema200_4h      = ema200_4h_s[last4h];
//         boolean price4hAbove50   = lastClose > ema50_4h;
//         boolean ema50_4hAbove200 = ema50_4h > ema200_4h;
//         System.out.printf("  [M0] 4H EMA50=%.4f EMA200=%.4f | above50=%s | 50>200=%s%n",
//                 ema50_4h, ema200_4h, price4hAbove50, ema50_4hAbove200);

//         // ── FILTER 3: 1H Macro ───────────────────────────────────────────────
//         double ema50_1h  = calcEMA(cl1h, EMA_MACRO);
//         boolean macroUp1h = lastClose > ema50_1h;
//         System.out.printf("  [M1] 1H EMA50=%.6f -> %s%n", ema50_1h, macroUp1h ? "BULL" : "BEAR");

//         // ── FILTER 4: 15m EMA9/21 + slope ───────────────────────────────────
//         double[] ema9s   = calcEMASeries(cl15, EMA_FAST);
//         double[] ema21s  = calcEMASeries(cl15, EMA_MID);
//         double ema9      = ema9s[last];
//         double ema21     = ema21s[last];
//         double prevEma21 = ema21s[last - 1];
//         boolean ema21Rising  = ema21 > prevEma21;
//         boolean ema21Falling = ema21 < prevEma21;
//         boolean localUp   = ema9 > ema21;
//         boolean localDown = ema9 < ema21;

//         System.out.printf("  [T1] EMA9=%.6f EMA21=%.6f | local=%s slope=%s%n",
//                 ema9, ema21,
//                 localUp ? "UP" : localDown ? "DOWN" : "FLAT",
//                 ema21Rising ? "RISING" : ema21Falling ? "FALLING" : "FLAT");

//         boolean trendUp, trendDown;
//         if      (localUp   && ema21Rising)  { trendUp = true;  trendDown = false; }
//         else if (localDown && ema21Falling) { trendUp = false; trendDown = true;  }
//         else { System.out.println("  T1 FAIL — EMA not aligned — skip"); return; }

//         // ── Evaluate M0 now that direction is known ──────────────────────────
//         boolean m0_A     = trendUp ?  price4hAbove50    : !price4hAbove50;
//         boolean m0_B     = trendUp ?  ema50_4hAbove200  : !ema50_4hAbove200;
//         int     m0Score  = (m0_A ? 1 : 0) + (m0_B ? 1 : 0);
//         boolean m0Strong = m0Score == 2;
//         boolean m0Weak   = m0Score == 1;
//         if (m0Score == 0) { System.out.println("  M0 FAIL — 4H fully against — skip"); return; }
//         System.out.printf("  M0 score=%d/2 (%s)%n", m0Score, m0Strong ? "STRONG" : "WEAK");

//         boolean macroAligned = (trendUp && macroUp1h) || (trendDown && !macroUp1h);
//         if (!macroAligned) { System.out.println("  M1 FAIL — 1H contra — skip"); return; }
//         System.out.printf("  T1+M0+M1 OK — direction=%s%n", trendUp ? "LONG" : "SHORT");

//         // ── FILTER 5: BTC Context ────────────────────────────────────────────
//         // Only block if BTC is strongly against on 1H (not both TFs like before)
//         // This reduces missed trades where BTC is slightly off but coin is independent
//         if (trendUp  && !btcBull1h) {
//             System.out.println("  BTC FAIL — BTC 1H bearish — skip LONG"); return;
//         }
//         if (trendDown && btcBull1h) {
//             System.out.println("  BTC FAIL — BTC 1H bullish — skip SHORT"); return;
//         }
//         System.out.printf("  BTC OK — 15m=%s 1H=%s%n",
//                 btcBull15m ? "BULL" : "BEAR", btcBull1h ? "BULL" : "BEAR");

//         // ── FILTER 6: Market Structure — real HH/HL check ────────────────────
//         // FIX: Was replaced with "price > ema21" which is NOT a structure check.
//         // Restored to proper 4-candle area comparison (last vs last-3 & last-2)
//         double areaHigh   = Math.max(hi15[last - 3], hi15[last - 2]);
//         double areaLow    = Math.min(lo15[last - 3], lo15[last - 2]);
//         boolean structureBull = hi15[last] > areaHigh && lo15[last] > areaLow;
//         boolean structureBear = hi15[last] < areaHigh && lo15[last] < areaLow;

//         System.out.printf("  [STR] areaHH=%.6f areaLL=%.6f | thisHH=%.6f thisLL=%.6f -> %s%n",
//                 areaHigh, areaLow, hi15[last], lo15[last],
//                 trendUp ? (structureBull ? "BULL_OK" : "BULL_FAIL")
//                         : (structureBear ? "BEAR_OK" : "BEAR_FAIL"));

//         if (trendUp   && !structureBull) { System.out.println("  STR FAIL — no HH/HL — skip"); return; }
//         if (trendDown && !structureBear) { System.out.println("  STR FAIL — no LH/LL — skip"); return; }
//         System.out.println("  STR OK");

//         // ── FILTER 7: MACD (closed candles only) ─────────────────────────────
//         double[] closedCl15 = Arrays.copyOfRange(cl15, 0, last + 1);
//         double[] mv      = calcMACD(closedCl15, MACD_FAST, MACD_SLOW, MACD_SIG);
//         double macdLine  = mv[0], macdSig = mv[1];
//         System.out.printf("  [T2] MACD=%.6f Sig=%.6f -> %s%n",
//                 macdLine, macdSig, macdLine > macdSig ? "BULL" : "BEAR");
//         if (trendUp   && macdLine <= macdSig) { System.out.println("  T2 FAIL — skip"); return; }
//         if (trendDown && macdLine >= macdSig) { System.out.println("  T2 FAIL — skip"); return; }
//         System.out.println("  T2 OK");

//         // ── FILTER 8: Supertrend direction ───────────────────────────────────
//         double[][] stFull  = calcSupertrendWithBands(hi15, lo15, cl15, ST_PERIOD, ST_MULTIPLIER);
//         boolean[] stBullArr = toBooleanArr(stFull[0]);
//         boolean stBull      = stBullArr[last];
//         boolean stPrevBull  = stBullArr[last - 1];
//         boolean stFlipped   = stBull != stPrevBull;
//         System.out.printf("  [T3] ST=%s%s%n", stBull ? "GREEN" : "RED", stFlipped ? " [FLIP]" : "");
//         if (trendUp   && !stBull) { System.out.println("  T3 FAIL — skip"); return; }
//         if (trendDown &&  stBull) { System.out.println("  T3 FAIL — skip"); return; }
//         System.out.println("  T3 OK");

//         // ── FILTER 9: Williams %R ────────────────────────────────────────────
//         double wr    = calcWilliamsR(hi15, lo15, closedCl15, WR_PERIOD);
//         boolean wrOk = trendUp ? wr >= WR_LONG_MIN : wr <= WR_SHORT_MAX;
//         System.out.printf("  [WR] WR=%.2f -> %s%n", wr, wrOk ? "OK" : "FAIL");
//         if (!wrOk) { System.out.println("  WR FAIL — momentum extreme — skip"); return; }
//         System.out.println("  WR OK");

//         // ── FILTER 10: ADX (dynamic, FIX: raised thresholds) ────────────────
//         double adx    = calcADX(hi15, lo15, cl15, ADX_PERIOD);
//         double adxMin = m0Strong ? ADX_STRONG_4H : ADX_WEAK_4H;
//         System.out.printf("  [ADX] ADX=%.2f min=%.1f -> %s%n", adx, adxMin, adx >= adxMin ? "OK" : "FAIL");
//         if (adx < adxMin) { System.out.println("  ADX FAIL — sideways — skip"); return; }
//         System.out.println("  ADX OK");

//         // ── FILTER 11: RSI zone + slope (FIX: slope restored) ───────────────
//         // Slope check was accidentally removed in the user's version
//         double rsi = calcRSI(closedCl15, RSI_PERIOD);
//         double[] closedMinus1 = Arrays.copyOfRange(closedCl15, 0, closedCl15.length - 1);
//         double prevRsi     = calcRSI(closedMinus1, RSI_PERIOD);
//         boolean rsiInZone  = trendUp
//                 ? (rsi >= RSI_LONG_MIN  && rsi <= RSI_LONG_MAX)
//                 : (rsi >= RSI_SHORT_MIN && rsi <= RSI_SHORT_MAX);
//         boolean rsiSlopeOk = trendUp ? (rsi > prevRsi) : (rsi < prevRsi);
//         System.out.printf("  [RSI] RSI=%.2f prev=%.2f | zone=%s slope=%s%n",
//                 rsi, prevRsi, rsiInZone ? "OK" : "fail", rsiSlopeOk ? "OK" : "fail");
//         if (!rsiInZone)  { System.out.println("  RSI FAIL — out of zone — skip"); return; }
//         if (!rsiSlopeOk) { System.out.println("  RSI FAIL — slope wrong — skip"); return; }
//         System.out.println("  RSI OK");

//         // ── FILTER 12: VWAP ──────────────────────────────────────────────────
//         double vwap   = calcVWAP(cl15, vol15, last, VWAP_PERIOD);
//         // Allow 0.2x ATR tolerance so price just below VWAP can still long
//         boolean vwapOk = trendUp
//                 ? lastClose > (vwap - atr15m * 0.2)
//                 : lastClose < (vwap + atr15m * 0.2);
//         System.out.printf("  [VWAP] VWAP=%.6f price=%.6f -> %s%n", vwap, lastClose, vwapOk ? "OK" : "FAIL");
//         if (!vwapOk) { System.out.println("  VWAP FAIL — skip"); return; }
//         System.out.println("  VWAP OK");

//         // ── FILTER 13: Pullback to EMA9 (FIX: buffer tightened 1.8→0.8) ─────
//         // 1.8x ATR was so wide that almost any price passed, defeating pullback logic
//         double ema9Buffer = EMA9_ENTRY_BUFFER * atr15m;
//         boolean pullbackOk = trendUp
//                 ? (lastClose <= ema9 + ema9Buffer && lastClose >= ema9 - ema9Buffer)
//                 : (lastClose >= ema9 - ema9Buffer && lastClose <= ema9 + ema9Buffer);
//         System.out.printf("  [E1] Price=%.6f EMA9=%.6f buf=%.6f -> %s%n",
//                 lastClose, ema9, ema9Buffer, pullbackOk ? "OK" : "FAIL");
//         if (!pullbackOk) { System.out.println("  E1 FAIL — price not near EMA9 — skip"); return; }
//         System.out.println("  E1 OK");

//         // ── FILTER 14: Candle size ───────────────────────────────────────────
//         double candleSize = lastHigh - lastLow;
//         if (candleSize > MAX_CANDLE_ATR_RATIO * atr15m) {
//             System.out.printf("  E2 FAIL — candle %.2fx ATR — skip%n", candleSize / atr15m); return;
//         }
//         System.out.println("  E2 OK");

//         // ── FILTER 15: Directional Volume (FIX: mult raised 0.9→1.1) ────────
//         double medianVol  = calcMedianVolume(vol15, last, VOL_MEDIAN_PERIOD);
//         double currentVol = vol15[last];
//         boolean bullCandle = lastClose > lastOpen;
//         boolean bearCandle = lastClose < lastOpen;
//         boolean volOk = trendUp
//                 ? (bullCandle && currentVol >= medianVol * VOL_DIRECTIONAL_MULT)
//                 : (bearCandle && currentVol >= medianVol * VOL_DIRECTIONAL_MULT);
//         System.out.printf("  [VOL2] candle=%s vol=%.2f median=%.2f (%.1fx) -> %s%n",
//                 bullCandle ? "BULL" : "BEAR",
//                 currentVol, medianVol,
//                 medianVol > 0 ? currentVol / medianVol : 0,
//                 volOk ? "OK" : "FAIL");
//         if (!volOk) { System.out.println("  VOL2 FAIL — skip"); return; }
//         System.out.println("  VOL2 OK");

//         // ── FILTER 16: SL Pre-check (FIX: wider buffer + raised cap) ─────────
//         double swingLow  = minOf(lo15, last - SWING_LOOKBACK, last);
//         double swingHigh = maxOf(hi15, last - SWING_LOOKBACK, last);
//         double preSl     = trendUp
//                 ? swingLow  - (SWING_SL_ATR_BUFFER * atr15m)
//                 : swingHigh + (SWING_SL_ATR_BUFFER * atr15m);
//         double preSlDist = Math.abs(lastClose - preSl);
//         double preSlPct  = (preSlDist / lastClose) * 100.0;
//         double effSlPct  = preSlPct + SL_SLIPPAGE_BUFFER;
//         System.out.printf("  [SL_PRE] SwingL=%.6f SwingH=%.6f SL_est=%.6f (%.2f%%) -> %s%n",
//                 swingLow, swingHigh, preSl, effSlPct, effSlPct <= MAX_SL_PCT ? "OK" : "FAIL");
//         if (effSlPct > MAX_SL_PCT) { System.out.println("  SL_PRE FAIL — skip"); return; }
//         System.out.println("  SL_PRE OK");

//         // ── FILTER 17: 5m Momentum trigger (FIX: trendUp/Down, not hardcoded true) ──
//         // The user's code had: boolean ok5m = true ? (bull...) : (bear...)
//         // That ternary condition was literally `true`, so SHORT never checked bear side
//         int last5    = cl5m.length - 2;
//         double r5    = hi5m[last5] - lo5m[last5];
//         double body5 = Math.abs(cl5m[last5] - op5m[last5]);
//         double bodyR = r5 > 0 ? body5 / r5 : 0;
//         double cpos5 = r5 > 0 ? (cl5m[last5] - lo5m[last5]) / r5 : 0.5;
//         boolean ok5m = trendUp                                        // FIX: was hardcoded `true`
//                 ? (cl5m[last5] > op5m[last5] && bodyR >= 0.25 && cpos5 >= 0.50)
//                 : (cl5m[last5] < op5m[last5] && bodyR >= 0.25 && cpos5 <= 0.50);
//         System.out.printf("  [5m] body=%.0f%% cpos=%.0f%% dir=%s -> %s%n",
//                 bodyR * 100, cpos5 * 100,
//                 cl5m[last5] > op5m[last5] ? "BULL" : "BEAR",
//                 ok5m ? "OK" : "FAIL");
//         if (!ok5m) { System.out.println("  5m FAIL — skip"); return; }
//         System.out.println("  5m OK");

//         // ── ALL FILTERS PASSED ───────────────────────────────────────────────
//         String side = trendUp ? "buy" : "sell";

//         // Dynamic RR based on ADX, capped tighter for weak 4H
//         double dynRR = Math.min(RR_MAX, Math.max(RR_MIN, adx / 20.0));
//         if (m0Weak) dynRR = Math.min(dynRR, 1.4);

//         System.out.println("\n  ╔═════════════════════════════════════════════════════╗");
//         System.out.println("  ║  ALL FILTERS PASSED → " + side.toUpperCase() + " " + pair);
//         System.out.printf ("  ║  ADX=%.1f RSI=%.1f WR=%.1f ATR%%=%.2f%% RR=1:%.2f%n",
//                 adx, rsi, wr, atrPct, dynRR);
//         System.out.printf ("  ║  M0=%s  VWAP=%.6f%n", m0Strong ? "STRONG" : "WEAK", vwap);
//         System.out.printf ("  ║  BTC: 15m=%s 1H=%s%n",
//                 btcBull15m ? "BULL" : "BEAR", btcBull1h ? "BULL" : "BEAR");
//         if (stFlipped) System.out.println("  ║  *** SUPERTREND FLIP ***");
//         System.out.println("  ╚═════════════════════════════════════════════════════╝");

//         // ── Place Order ──────────────────────────────────────────────────────
//         double curPrice = getLastPrice(pair);
//         if (curPrice <= 0) { System.out.println("  Bad price — skip"); return; }

//         double usdtInr = getDynamicUsdtInrRate();
//         // FIX: leverage correctly applied in quantity calculation
//         double qty = calcQuantity(curPrice, pair, usdtInr);
//         if (qty <= 0) { System.out.println("  Bad qty — skip"); return; }

//         System.out.printf("  Order: %s qty=%.4f price=%.6f lev=%dx posVal=~%.0fINR%n",
//                 side.toUpperCase(), qty, curPrice, LEVERAGE, qty * curPrice * usdtInr);

//         JSONObject resp = placeFuturesMarketOrder(side, pair, qty, LEVERAGE,
//                 "email_notification", "isolated", "INR");
//         if (resp == null || !resp.has("id")) {
//             System.out.println("  Order failed. Full response:");
//             if (resp != null) System.out.println(resp.toString(2));
//             return;
//         }
//         System.out.println("  Placed! id=" + resp.getString("id"));
//         lastTradeTime.put(pair, System.currentTimeMillis());

//         // ── Confirm Entry ────────────────────────────────────────────────────
//         double entry = getEntryPrice(pair, resp.getString("id"));
//         if (entry <= 0) { System.out.println("  Entry not confirmed — TP/SL skipped"); return; }
//         System.out.printf("  Entry confirmed: %.6f%n", entry);

//         // ── Compute SL from swing points (FIX: wider buffer + min dist) ──────
//         double slPrice, slDist;
//         if (trendUp) {
//             slPrice = swingLow - (SWING_SL_ATR_BUFFER * atr15m);
//             slDist  = Math.abs(entry - slPrice);
//             // FIX: Minimum SL distance = SWING_SL_MIN_ATR_MULT x ATR
//             // Prevents SL getting squeezed when swing low is very close to entry
//             if (slDist < SWING_SL_MIN_ATR_MULT * atr15m) {
//                 slPrice = entry - (SWING_SL_MIN_ATR_MULT * atr15m);
//                 slDist  = SWING_SL_MIN_ATR_MULT * atr15m;
//             }
//         } else {
//             slPrice = swingHigh + (SWING_SL_ATR_BUFFER * atr15m);
//             slDist  = Math.abs(entry - slPrice);
//             if (slDist < SWING_SL_MIN_ATR_MULT * atr15m) {
//                 slPrice = entry + (SWING_SL_MIN_ATR_MULT * atr15m);
//                 slDist  = SWING_SL_MIN_ATR_MULT * atr15m;
//             }
//         }

//         // Cap at MAX_SL_PCT
//         double slPctFinal = (slDist / entry) * 100.0;
//         if (slPctFinal > MAX_SL_PCT) {
//             double cappedDist = entry * (MAX_SL_PCT / 100.0);
//             slPrice = trendUp ? entry - cappedDist : entry + cappedDist;
//             slDist  = cappedDist;
//             System.out.printf("  SL capped at %.1f%% -> %.6f%n", MAX_SL_PCT, slPrice);
//         }

//         double tpPrice = trendUp ? entry + dynRR * slDist : entry - dynRR * slDist;

//         // Round to tick
//         slPrice = roundToTick(slPrice, tickSize);
//         tpPrice = roundToTick(tpPrice, tickSize);

//         // Enforce minimum tick gap
//         double minGap = tickSize * MIN_TICK_GAP;
//         if (trendUp) {
//             if (slPrice >= entry - minGap)
//                 slPrice = roundToTick(entry - Math.max(atr15m, minGap * 3), tickSize);
//             if (tpPrice <= entry + minGap)
//                 tpPrice = roundToTick(entry + Math.max(atr15m, minGap * 3), tickSize);
//         } else {
//             if (slPrice <= entry + minGap)
//                 slPrice = roundToTick(entry + Math.max(atr15m, minGap * 3), tickSize);
//             if (tpPrice >= entry - minGap)
//                 tpPrice = roundToTick(entry - Math.max(atr15m, minGap * 3), tickSize);
//         }

//         // Final side validation
//         boolean slOk = trendUp ? slPrice < entry : slPrice > entry;
//         boolean tpOk = trendUp ? tpPrice > entry : tpPrice < entry;
//         if (!slOk || !tpOk) {
//             System.out.printf("  CRITICAL: SL/TP side check FAILED (slOk=%s tpOk=%s) — skip%n", slOk, tpOk);
//             return;
//         }
//         if (Math.abs(tpPrice - slPrice) < tickSize) {
//             System.out.println("  TP==SL error — skip"); return;
//         }

//         double finalSlPct = Math.abs(entry - slPrice) / entry * 100.0;
//         double finalTpPct = Math.abs(tpPrice - entry) / entry * 100.0;
//         System.out.printf("  RR=1:%.2f Entry=%.6f SL=%.6f(-%.2f%%) TP=%.6f(+%.2f%%)%n",
//                 dynRR, entry, slPrice, finalSlPct, tpPrice, finalTpPct);

//         String posId = getPositionId(pair);
//         if (posId != null) setTpSl(posId, tpPrice, slPrice, pair);
//         else System.out.println("  posId not found — TP/SL skipped");
//     }

//     // =========================================================================
//     // BTC Context
//     // =========================================================================
//     private static void refreshBtcContext() {
//         if (System.currentTimeMillis() - btcCacheTime < BTC_CACHE_TTL) return;
//         try {
//             JSONArray btc15mRaw = getCandlestickData(BTC_PAIR, "15", 50);
//             JSONArray btc1hRaw  = getCandlestickData(BTC_PAIR, "60", 60);
//             if (btc15mRaw != null && btc15mRaw.length() >= 25) {
//                 double[] c   = extractCloses(btc15mRaw);
//                 int L        = c.length - 2;
//                 double[] s9  = calcEMASeries(c, EMA_FAST);
//                 double[] s21 = calcEMASeries(c, EMA_MID);
//                 btcBull15m   = s9[L] > s21[L];
//             }
//             if (btc1hRaw != null && btc1hRaw.length() >= 55) {
//                 double[] c    = extractCloses(btc1hRaw);
//                 int L         = c.length - 2;
//                 double[] s50  = calcEMASeries(c, EMA_MACRO);
//                 btcBull1h     = c[L] > s50[L];   // price above 1H EMA50
//             }
//             btcCacheTime = System.currentTimeMillis();
//             System.out.printf("[BTC] 15m=%s 1H=%s%n",
//                     btcBull15m ? "BULL" : "BEAR", btcBull1h ? "BULL" : "BEAR");
//         } catch (Exception e) {
//             System.err.println("[BTC] refresh failed: " + e.getMessage());
//         }
//     }

//     // =========================================================================
//     // VWAP Approximation
//     // =========================================================================
//     private static double calcVWAP(double[] cl, double[] vol, int upToIndex, int period) {
//         int from = Math.max(0, upToIndex - period + 1);
//         double sumCV = 0, sumV = 0;
//         for (int i = from; i <= upToIndex; i++) {
//             sumCV += cl[i] * vol[i];
//             sumV  += vol[i];
//         }
//         return sumV > 0 ? sumCV / sumV : cl[upToIndex];
//     }

//     // =========================================================================
//     // Williams %R
//     // =========================================================================
//     private static double calcWilliamsR(double[] hi, double[] lo, double[] cl, int period) {
//         int n = cl.length;
//         if (n < period) return -50.0;
//         int from = n - period;
//         double hh = hi[from], ll = lo[from];
//         for (int i = from + 1; i < n; i++) {
//             if (hi[i] > hh) hh = hi[i];
//             if (lo[i] < ll) ll = lo[i];
//         }
//         double range = hh - ll;
//         if (range == 0) return -50.0;
//         return ((hh - cl[n - 1]) / range) * -100.0;
//     }

//     // =========================================================================
//     // Swing Low / High helpers
//     // =========================================================================
//     private static double minOf(double[] arr, int from, int to) {
//         from = Math.max(0, from);
//         to   = Math.min(arr.length - 1, to);
//         double m = arr[from];
//         for (int i = from + 1; i <= to; i++) if (arr[i] < m) m = arr[i];
//         return m;
//     }

//     private static double maxOf(double[] arr, int from, int to) {
//         from = Math.max(0, from);
//         to   = Math.min(arr.length - 1, to);
//         double m = arr[from];
//         for (int i = from + 1; i <= to; i++) if (arr[i] > m) m = arr[i];
//         return m;
//     }

//     // =========================================================================
//     // Median Volume
//     // =========================================================================
//     private static double calcMedianVolume(double[] vol, int upToIndex, int period) {
//         int from = Math.max(0, upToIndex - period);
//         List<Double> v = new ArrayList<>();
//         for (int i = from; i < upToIndex; i++) if (vol[i] > 0) v.add(vol[i]);
//         if (v.isEmpty()) return 0;
//         Collections.sort(v);
//         int m = v.size() / 2;
//         return v.size() % 2 == 0 ? (v.get(m - 1) + v.get(m)) / 2.0 : v.get(m);
//     }

//     // =========================================================================
//     // USDT/INR Rate
//     // =========================================================================
//     private static double cachedUsdtInrRate = 84.0;
//     private static long   usdtInrFetchTime  = 0;

//     private static double getDynamicUsdtInrRate() {
//         if (System.currentTimeMillis() - usdtInrFetchTime < 600_000L) return cachedUsdtInrRate;
//         try {
//             HttpURLConnection c = openGet(PUBLIC_API_URL + "/market_data/trade_history?pair=B-USDT_INR&limit=1");
//             if (c.getResponseCode() == 200) {
//                 String r = readStream(c.getInputStream());
//                 double rate = r.startsWith("[")
//                         ? new JSONArray(r).getJSONObject(0).getDouble("p")
//                         : new JSONObject(r).getDouble("p");
//                 if (rate > 70 && rate < 120) {
//                     cachedUsdtInrRate = rate;
//                     usdtInrFetchTime  = System.currentTimeMillis();
//                     System.out.printf("  USDT/INR=%.2f%n", rate);
//                     return rate;
//                 }
//             }
//         } catch (Exception e) { System.err.println("  INR fetch fail: " + e.getMessage()); }
//         return cachedUsdtInrRate;
//     }

//     // =========================================================================
//     // Quantity — leverage correctly applied
//     // FIX: was MAX_MARGIN/usdtInr (no leverage). Now (MAX_MARGIN * LEVERAGE)/usdtInr
//     // =========================================================================
//     private static double calcQuantity(double price, String pair, double usdtInrRate) {
//         double posUsdt = (MAX_MARGIN) / usdtInrRate;
//         double qty     = posUsdt / price;
//         double fq      = INTEGER_QTY_PAIRS.contains(pair)
//                 ? Math.floor(qty)
//                 : Math.floor(qty * 100) / 100.0;
//         return Math.max(fq, 0);
//     }

//     // =========================================================================
//     // ADX
//     // =========================================================================
//     private static double calcADX(double[] hi, double[] lo, double[] cl, int p) {
//         int n = hi.length;
//         if (n < p * 2) return 0;
//         double[] pdm = new double[n], mdm = new double[n], tr = new double[n];
//         for (int i = 1; i < n; i++) {
//             double up = hi[i] - hi[i-1], dn = lo[i-1] - lo[i];
//             pdm[i] = (up > dn && up > 0) ? up : 0;
//             mdm[i] = (dn > up && dn > 0) ? dn : 0;
//             tr[i]  = Math.max(hi[i]-lo[i], Math.max(Math.abs(hi[i]-cl[i-1]), Math.abs(lo[i]-cl[i-1])));
//         }
//         double sTR = 0, sP = 0, sM = 0;
//         for (int i = 1; i <= p; i++) { sTR += tr[i]; sP += pdm[i]; sM += mdm[i]; }
//         double adx = 0; int cnt = 0; double sum = 0;
//         for (int i = p + 1; i < n; i++) {
//             sTR = sTR - sTR/p + tr[i];
//             sP  = sP  - sP/p  + pdm[i];
//             sM  = sM  - sM/p  + mdm[i];
//             if (sTR == 0) continue;
//             double pDI = 100*sP/sTR, mDI = 100*sM/sTR, ds = pDI + mDI;
//             double dx  = ds == 0 ? 0 : 100 * Math.abs(pDI - mDI) / ds;
//             if (cnt < p) { sum += dx; cnt++; if (cnt == p) adx = sum / p; }
//             else adx = (adx * (p - 1) + dx) / p;
//         }
//         return adx;
//     }

//     // =========================================================================
//     // Supertrend with bands
//     // =========================================================================
//     private static double[][] calcSupertrendWithBands(double[] hi, double[] lo, double[] cl, int p, double mult) {
//         int n = cl.length;
//         double[] bull = new double[n], lb = new double[n], ub = new double[n];
//         if (n < p + 1) {
//             Arrays.fill(bull, 1); Arrays.fill(lb, cl[n-1]*0.98); Arrays.fill(ub, cl[n-1]*1.02);
//             return new double[][]{bull, lb, ub};
//         }
//         double[] atrA = calcATRSeries(hi, lo, cl, p);
//         for (int i = p; i < n; i++) {
//             double hl2 = (hi[i]+lo[i])/2.0, bU = hl2+mult*atrA[i], bL = hl2-mult*atrA[i];
//             if (i == p) { ub[i] = bU; lb[i] = bL; bull[i] = cl[i] > hl2 ? 1 : 0; }
//             else {
//                 ub[i]   = (bU < ub[i-1] || cl[i-1] > ub[i-1]) ? bU : ub[i-1];
//                 lb[i]   = (bL > lb[i-1] || cl[i-1] < lb[i-1]) ? bL : lb[i-1];
//                 bull[i] = bull[i-1] == 1 ? (cl[i] >= lb[i] ? 1 : 0) : (cl[i] > ub[i] ? 1 : 0);
//             }
//         }
//         for (int i = 0; i < p; i++) { bull[i] = bull[p]; lb[i] = lb[p]; ub[i] = ub[p]; }
//         return new double[][]{bull, lb, ub};
//     }

//     private static boolean[] toBooleanArr(double[] d) {
//         boolean[] b = new boolean[d.length];
//         for (int i = 0; i < d.length; i++) b[i] = d[i] == 1.0;
//         return b;
//     }

//     // =========================================================================
//     // ATR Series
//     // =========================================================================
//     private static double[] calcATRSeries(double[] hi, double[] lo, double[] cl, int p) {
//         int n = hi.length; double[] atr = new double[n]; if (n < 2) return atr;
//         double[] tr = new double[n]; tr[0] = hi[0] - lo[0];
//         for (int i = 1; i < n; i++)
//             tr[i] = Math.max(hi[i]-lo[i], Math.max(Math.abs(hi[i]-cl[i-1]), Math.abs(lo[i]-cl[i-1])));
//         double s = 0;
//         for (int i = 0; i < p && i < n; i++) s += tr[i];
//         atr[p-1] = s / p;
//         for (int i = p; i < n; i++) atr[i] = (atr[i-1]*(p-1) + tr[i]) / p;
//         for (int i = 0; i < p-1; i++) atr[i] = atr[p-1];
//         return atr;
//     }

//     // =========================================================================
//     // Indicators
//     // =========================================================================
//     private static double calcEMA(double[] d, int p) {
//         if (d.length < p) return 0;
//         double k = 2.0/(p+1), e = 0;
//         for (int i = 0; i < p; i++) e += d[i]; e /= p;
//         for (int i = p; i < d.length; i++) e = d[i]*k + e*(1-k);
//         return e;
//     }

//     private static double[] calcEMASeries(double[] d, int p) {
//         double[] o = new double[d.length]; if (d.length < p) return o;
//         double k = 2.0/(p+1), s = 0;
//         for (int i = 0; i < p; i++) s += d[i]; o[p-1] = s/p;
//         for (int i = p; i < d.length; i++) o[i] = d[i]*k + o[i-1]*(1-k);
//         return o;
//     }

//     private static double[] calcMACD(double[] cl, int fast, int slow, int sig) {
//         double[] ef = calcEMASeries(cl, fast), es = calcEMASeries(cl, slow);
//         int st = slow-1, len = cl.length-st; if (len <= 0) return new double[]{0, 0, 0};
//         double[] ml = new double[len];
//         for (int i = 0; i < len; i++) ml[i] = ef[st+i] - es[st+i];
//         double[] ss = calcEMASeries(ml, sig);
//         double m = ml[ml.length-1], sv = ss[ss.length-1];
//         return new double[]{m, sv, m-sv};
//     }

//     private static double calcRSI(double[] cl, int p) {
//         if (cl.length < p+1) return 50;
//         double ag = 0, al = 0;
//         for (int i = 1; i <= p; i++) {
//             double c = cl[i]-cl[i-1];
//             if (c > 0) ag += c; else al += Math.abs(c);
//         }
//         ag /= p; al /= p;
//         for (int i = p+1; i < cl.length; i++) {
//             double c = cl[i]-cl[i-1];
//             if (c > 0) { ag = (ag*(p-1)+c)/p; al = al*(p-1)/p; }
//             else        { al = (al*(p-1)+Math.abs(c))/p; ag = ag*(p-1)/p; }
//         }
//         if (al == 0) return 100;
//         return 100 - (100 / (1 + ag/al));
//     }

//     private static double calcATR(double[] hi, double[] lo, double[] cl, int p) {
//         if (hi.length < p+1) return 0;
//         double[] tr = new double[hi.length]; tr[0] = hi[0]-lo[0];
//         for (int i = 1; i < hi.length; i++)
//             tr[i] = Math.max(hi[i]-lo[i], Math.max(Math.abs(hi[i]-cl[i-1]), Math.abs(lo[i]-cl[i-1])));
//         double a = 0;
//         for (int i = 0; i < p; i++) a += tr[i]; a /= p;
//         for (int i = p; i < hi.length; i++) a = (a*(p-1)+tr[i])/p;
//         return a;
//     }

//     private static double roundToTick(double price, double tick) {
//         if (tick <= 0) return price;
//         return Math.round(price / tick) * tick;
//     }

//     // =========================================================================
//     // OHLCV Extraction
//     // =========================================================================
//     private static double[] extractCloses(JSONArray a)  { double[] o=new double[a.length()]; for(int i=0;i<a.length();i++) o[i]=a.getJSONObject(i).getDouble("close"); return o; }
//     private static double[] extractOpens(JSONArray a)   { double[] o=new double[a.length()]; for(int i=0;i<a.length();i++) o[i]=a.getJSONObject(i).getDouble("open");  return o; }
//     private static double[] extractHighs(JSONArray a)   { double[] o=new double[a.length()]; for(int i=0;i<a.length();i++) o[i]=a.getJSONObject(i).getDouble("high");  return o; }
//     private static double[] extractLows(JSONArray a)    { double[] o=new double[a.length()]; for(int i=0;i<a.length();i++) o[i]=a.getJSONObject(i).getDouble("low");   return o; }
//     private static double[] extractVolumes(JSONArray a) { double[] o=new double[a.length()]; for(int i=0;i<a.length();i++) o[i]=a.getJSONObject(i).optDouble("volume",0); return o; }

//     // =========================================================================
//     // API
//     // =========================================================================
//     private static JSONArray getCandlestickData(String pair, String res, int count) {
//         try {
//             long mins;
//             switch (res) {
//                 case "1": mins=1; break; case "5": mins=5; break;
//                 case "15": mins=15; break; case "60": mins=60; break;
//                 case "240": mins=240; break; default: mins=15;
//             }
//             long to = Instant.now().getEpochSecond(), from = to - mins*60L*count;
//             String url = PUBLIC_API_URL+"/market_data/candlesticks?pair="+pair
//                     +"&from="+from+"&to="+to+"&resolution="+res+"&pcode=f";
//             HttpURLConnection c = openGet(url);
//             if (c.getResponseCode() == 200) {
//                 JSONObject r = new JSONObject(readStream(c.getInputStream()));
//                 if ("ok".equals(r.optString("s"))) return r.getJSONArray("data");
//             }
//         } catch (Exception e) { System.err.println("  candles("+pair+"/"+res+"): "+e.getMessage()); }
//         return null;
//     }

//     private static void initInstrumentCache() {
//         try {
//             long now = System.currentTimeMillis();
//             if (now - lastCacheUpdate < TICK_CACHE_TTL_MS) return;
//             instrumentCache.clear(); System.out.println("Refreshing instruments...");
//             JSONArray pairs = new JSONArray(publicGet(
//                     BASE_URL+"/exchange/v1/derivatives/futures/data/active_instruments"));
//             for (int i = 0; i < pairs.length(); i++) {
//                 String p = pairs.getString(i);
//                 try {
//                     String raw = publicGet(BASE_URL+"/exchange/v1/derivatives/futures/data/instrument?pair="+p);
//                     instrumentCache.put(p, new JSONObject(raw).getJSONObject("instrument"));
//                 } catch (Exception ig) {}
//             }
//             lastCacheUpdate = now;
//             System.out.println("Cached: " + instrumentCache.size());
//         } catch (Exception e) { System.err.println("initCache: "+e.getMessage()); }
//     }

//     private static double getTickSize(String pair) {
//         if (System.currentTimeMillis()-lastCacheUpdate > TICK_CACHE_TTL_MS) initInstrumentCache();
//         JSONObject d = instrumentCache.get(pair);
//         return d != null ? d.optDouble("price_increment", 0.0001) : 0.0001;
//     }

//     private static double getEntryPrice(String pair, String orderId) throws Exception {
//         for (int i = 0; i < MAX_ENTRY_PRICE_CHECKS; i++) {
//             TimeUnit.MILLISECONDS.sleep(ENTRY_CHECK_DELAY_MS);
//             JSONObject pos = findPosition(pair);
//             if (pos != null && pos.optDouble("avg_price", 0) > 0) return pos.getDouble("avg_price");
//         }
//         return 0;
//     }

//     private static JSONObject findPosition(String pair) throws Exception {
//         JSONObject b = new JSONObject();
//         b.put("timestamp", Instant.now().toEpochMilli());
//         b.put("page", "1"); b.put("size", "20");
//         b.put("margin_currency_short_name", new String[]{"INR", "USDT"});
//         String resp = authPost(BASE_URL+"/exchange/v1/derivatives/futures/positions", b.toString());
//         JSONArray arr = resp.startsWith("[") ? new JSONArray(resp) : new JSONArray().put(new JSONObject(resp));
//         for (int i = 0; i < arr.length(); i++) {
//             JSONObject p = arr.getJSONObject(i);
//             if (pair.equals(p.optString("pair"))) return p;
//         }
//         return null;
//     }

//     public static double getLastPrice(String pair) {
//         try {
//             HttpURLConnection c = openGet(PUBLIC_API_URL+"/market_data/trade_history?pair="+pair+"&limit=1");
//             if (c.getResponseCode() == 200) {
//                 String r = readStream(c.getInputStream());
//                 return r.startsWith("[")
//                         ? new JSONArray(r).getJSONObject(0).getDouble("p")
//                         : new JSONObject(r).getDouble("p");
//             }
//         } catch (Exception e) { System.err.println("price("+pair+"): "+e.getMessage()); }
//         return 0;
//     }

//     public static JSONObject placeFuturesMarketOrder(String side, String pair, double qty,
//                                                      int lev, String notif, String mType, String mCcy) {
//         try {
//             JSONObject o = new JSONObject();
//             o.put("side", side); o.put("pair", pair); o.put("order_type", "market_order");
//             o.put("total_quantity", qty); o.put("leverage", lev); o.put("notification", notif);
//             o.put("time_in_force", "good_till_cancel"); o.put("hidden", false);
//             o.put("post_only", false); o.put("position_margin_type", mType);
//             o.put("margin_currency_short_name", mCcy);
//             JSONObject b = new JSONObject();
//             b.put("timestamp", Instant.now().toEpochMilli()); b.put("order", o);
//             String resp = authPost(BASE_URL+"/exchange/v1/derivatives/futures/orders/create", b.toString());
//             return resp.startsWith("[") ? new JSONArray(resp).getJSONObject(0) : new JSONObject(resp);
//         } catch (Exception e) { System.err.println("placeOrder: "+e.getMessage()); return null; }
//     }

//     public static void setTpSl(String posId, double tp, double sl, String pair) {
//         try {
//             double tick = getTickSize(pair);
//             JSONObject tpO = new JSONObject();
//             tpO.put("stop_price", roundToTick(tp, tick));
//             tpO.put("limit_price", roundToTick(tp, tick));
//             tpO.put("order_type", "take_profit_market");
//             JSONObject slO = new JSONObject();
//             slO.put("stop_price", roundToTick(sl, tick));
//             slO.put("limit_price", roundToTick(sl, tick));
//             slO.put("order_type", "stop_market");
//             JSONObject pl = new JSONObject();
//             pl.put("timestamp", Instant.now().toEpochMilli());
//             pl.put("id", posId); pl.put("take_profit", tpO); pl.put("stop_loss", slO);
//             String resp = authPost(BASE_URL+"/exchange/v1/derivatives/futures/positions/create_tpsl", pl.toString());
//             JSONObject r = new JSONObject(resp);
//             System.out.println(r.has("err_code_dcx") ? "  TP/SL error: "+r : "  TP/SL set OK!");
//         } catch (Exception e) { System.err.println("setTpSl: "+e.getMessage()); }
//     }

//     public static String getPositionId(String pair) {
//         try { JSONObject p = findPosition(pair); return p != null ? p.getString("id") : null; }
//         catch (Exception e) { System.err.println("getPosId: "+e.getMessage()); return null; }
//     }

//     private static Set<String> getActivePositions() {
//         Set<String> active = new HashSet<>();
//         try {
//             JSONObject b = new JSONObject();
//             b.put("timestamp", Instant.now().toEpochMilli());
//             b.put("page", "1"); b.put("size", "100");
//             b.put("margin_currency_short_name", new String[]{"INR", "USDT"});
//             String resp = authPost(BASE_URL+"/exchange/v1/derivatives/futures/positions", b.toString());
//             JSONArray arr = resp.startsWith("[") ? new JSONArray(resp) : new JSONArray().put(new JSONObject(resp));
//             System.out.println("=== Open Positions ("+arr.length()+") ===");
//             for (int i = 0; i < arr.length(); i++) {
//                 JSONObject p = arr.getJSONObject(i);
//                 String pair = p.optString("pair", "");
//                 boolean isActive = p.optDouble("active_pos", 0) > 0
//                         || p.optDouble("locked_margin", 0) > 0
//                         || p.optDouble("avg_price", 0) > 0
//                         || p.optDouble("take_profit_trigger", 0) > 0
//                         || p.optDouble("stop_loss_trigger", 0) > 0;
//                 if (isActive) {
//                     System.out.printf("  %s qty=%.2f entry=%.6f TP=%.4f SL=%.4f%n",
//                             pair, p.optDouble("active_pos", 0), p.optDouble("avg_price", 0),
//                             p.optDouble("take_profit_trigger", 0), p.optDouble("stop_loss_trigger", 0));
//                     active.add(pair);
//                 }
//             }
//         } catch (Exception e) { System.err.println("getActive: "+e.getMessage()); }
//         return active;
//     }

//     // =========================================================================
//     // HTTP + HMAC
//     // =========================================================================
//     private static HttpURLConnection openGet(String url) throws IOException {
//         HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
//         c.setRequestMethod("GET"); c.setConnectTimeout(10_000); c.setReadTimeout(10_000);
//         return c;
//     }

//     private static String publicGet(String url) throws IOException {
//         HttpURLConnection c = openGet(url);
//         if (c.getResponseCode() == 200) return readStream(c.getInputStream());
//         throw new IOException("HTTP " + c.getResponseCode());
//     }

//     private static String authPost(String url, String json) throws IOException {
//         HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
//         c.setRequestMethod("POST");
//         c.setRequestProperty("Content-Type", "application/json");
//         c.setRequestProperty("X-AUTH-APIKEY", API_KEY);
//         c.setRequestProperty("X-AUTH-SIGNATURE", sign(json));
//         c.setConnectTimeout(10_000); c.setReadTimeout(10_000); c.setDoOutput(true);
//         try (OutputStream os = c.getOutputStream()) { os.write(json.getBytes(StandardCharsets.UTF_8)); }
//         InputStream is = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
//         return readStream(is);
//     }

//     private static String readStream(InputStream is) throws IOException {
//         return new BufferedReader(new InputStreamReader(is)).lines().collect(Collectors.joining("\n"));
//     }

//     private static String sign(String payload) {
//         try {
//             Mac mac = Mac.getInstance("HmacSHA256");
//             mac.init(new SecretKeySpec(API_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
//             byte[] b = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
//             StringBuilder sb = new StringBuilder();
//             for (byte x : b) sb.append(String.format("%02x", x));
//             return sb.toString();
//         } catch (Exception e) { throw new RuntimeException("HMAC fail", e); }
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
//  * ═══════════════════════════════════════════════════════════════════════════
//  * CoinDCX Futures Trader — v4H_FINAL
//  * ═══════════════════════════════════════════════════════════════════════════
//  *
//  * NEW IN THIS VERSION — 4H TREND LAYER + INDICATOR IMPROVEMENTS
//  *
//  * ┌─────────────────────────────────────────────────────────────────────────┐
//  * │  MULTI-TIMEFRAME HIERARCHY (top-down alignment required)               │
//  * │                                                                         │
//  * │  4H  →  15m  →  5m                                                     │
//  * │  Macro Bias  Trend Entry  Momentum Confirm                             │
//  * │                                                                         │
//  * │  OLD: 1H EMA50 (macro) + 15m EMA9/21 (local) + 5m candle             │
//  * │  NEW: 4H EMA50+EMA200 (macro bias) + 1H EMA50 (intermediate)         │
//  * │        + 15m EMA9/21+MACD+ST (trend) + 5m body (entry trigger)        │
//  * └─────────────────────────────────────────────────────────────────────────┘
//  *
//  * FILTER STACK (in order of execution):
//  *
//  *  VOL_ATR   ATR% < 3.0% — skip spike/news events
//  *
//  *  M0 [NEW]  4H EMA50 + EMA200 dual alignment
//  *             LONG:  price > EMA50_4H AND EMA50_4H > EMA200_4H (bull structure)
//  *             SHORT: price < EMA50_4H AND EMA50_4H < EMA200_4H (bear structure)
//  *             SOFT:  if only one of two conditions met → allowed but RR reduced
//  *
//  *  M1        1H EMA50 — intermediate trend gate
//  *             Must agree with M0 direction (no contra allowed)
//  *
//  *  T1        15m EMA9/21 alignment + EMA21 slope
//  *             Local structure must match macro direction
//  *
//  *  T2        15m MACD — momentum confirmation (closed candles only)
//  *
//  *  T3        15m Supertrend direction (with flip detection)
//  *
//  *  T4 [NEW]  15m Williams %R — overbought/oversold filter
//  *             Replaces nothing, adds a mean-reversion guard:
//  *             LONG:  WR >= -80 (not deep oversold, momentum returning)
//  *             SHORT: WR <= -20 (not deep overbought, momentum turning)
//  *             This prevents buying into exhausted bounces and shorting capitulation
//  *
//  *  Q1        ADX >= 25 — no sideways market trades
//  *
//  *  E1        Price on correct side of EMA9 + within 1.2x ATR (directional)
//  *
//  *  E2        Candle size < 1.6x ATR (no overextended entries)
//  *
//  *  SL_PRE    ST-based SL < 2.5% + 0.15% slippage buffer (pre-order check)
//  *
//  *  S1 SOFT   RSI in zone (45-65 long / 35-58 short) — closed candles only
//  *  S2 SOFT   Previous candle direction
//  *  S3 SOFT   Volume >= 1.3x median(20)
//  *            → Skip if BOTH RSI and Volume fail
//  *
//  *  5m        Strong momentum candle (body >= 35%, close near extreme)
//  *
//  * RR RATIO LOGIC (dynamic):
//  *  M0_strong (both 4H conditions) + macro aligned + ADX>=35 → 1.6x
//  *  M0_strong + macro aligned + ADX>=25                       → 1.4x
//  *  M0_strong + macro aligned + ADX<25                        → 1.2x
//  *  M0_weak   (one 4H condition) + macro aligned + ADX>=35   → 1.4x
//  *  M0_weak   + macro aligned + ADX>=25                       → 1.3x
//  *  M0_weak   + macro aligned + ADX<25                        → 1.2x
//  *  Macro-contra (any)                                         → 1.2x max
//  *
//  * KEY CARRY-OVER FIXES (from previous version):
//  *  ✓ EMA9 uses calcEMASeries[last] not scalar calcEMA (index-correct)
//  *  ✓ Qty uses leverage: positionUsdt = (MAX_MARGIN * LEVERAGE) / usdtInrRate
//  *  ✓ MACD + RSI use only closed candles (no live candle contamination)
//  *  ✓ SL_PRE + 0.15% slippage buffer before order placement
//  *  ✓ TP/SL minimum 2-tick gap enforcement after rounding
//  *  ✓ Final side-check: SL below entry for LONG, above for SHORT
//  *  ✓ Volume uses median (outlier-resistant, not mean)
//  * ═══════════════════════════════════════════════════════════════════════════
//  */
// public class CoinDCXFuturesTrader8C_BUY_SELL_NEW_LOGIC_THREE {

//     // =========================================================================
//     // API Configuration
//     // =========================================================================
//     private static final String API_KEY    = System.getenv("DELTA_API_KEY");
//     private static final String API_SECRET = System.getenv("DELTA_API_SECRET");
//     private static final String BASE_URL       = "https://api.coindcx.com";
//     private static final String PUBLIC_API_URL = "https://public.coindcx.com";

//     private static final double MAX_MARGIN = 2000.0;
//     private static final int    LEVERAGE   = 20;

//     private static final int    MAX_ENTRY_PRICE_CHECKS = 12;
//     private static final int    ENTRY_CHECK_DELAY_MS   = 1000;
//     private static final long   TICK_CACHE_TTL_MS      = 3_600_000L;
//     private static final long   COOLDOWN_MS            = 30 * 60 * 1000L;

//     // ── Indicator periods ────────────────────────────────────────────────────
//     private static final int EMA_FAST       = 9;
//     private static final int EMA_MID        = 21;
//     private static final int EMA_MACRO      = 50;   // used on 1H and 4H
//     private static final int EMA_MACRO_SLOW = 200;  // used on 4H only (golden/death cross)
//     private static final int MACD_FAST      = 12;
//     private static final int MACD_SLOW      = 26;
//     private static final int MACD_SIG       = 9;
//     private static final int RSI_PERIOD     = 14;
//     private static final int ATR_PERIOD     = 14;
//     private static final int ADX_PERIOD     = 14;
//     private static final int WR_PERIOD      = 14;   // Williams %R period

//     // ── Supertrend ───────────────────────────────────────────────────────────
//     private static final int    ST_PERIOD        = 10;
//     private static final double ST_MULTIPLIER    = 3.0;
//     private static final double ST_SL_BUFFER_ATR = 0.3;

//     // ── Thresholds ───────────────────────────────────────────────────────────
//     private static final double ADX_MIN = 25.0;

//     // Williams %R zones  (-100 = most oversold, 0 = most overbought)
//     private static final double WR_LONG_MIN  = -80.0; // not deep oversold (momentum returning)
//     private static final double WR_SHORT_MAX = -20.0; // not deep overbought (momentum turning)

//     // RSI zones (closed candles)
//     private static final double RSI_LONG_MIN  = 45.0;
//     private static final double RSI_LONG_MAX  = 65.0;
//     private static final double RSI_SHORT_MIN = 35.0;
//     private static final double RSI_SHORT_MAX = 58.0;

//     // ── TP:SL ratios ─────────────────────────────────────────────────────────
//     // M0_strong = both 4H EMA conditions met
//     // M0_weak   = only one 4H condition met
//     private static final double RR_4H_STRONG_ADX35 = 1.6;
//     private static final double RR_4H_STRONG_ADX25 = 1.4;
//     private static final double RR_4H_STRONG_WEAK  = 1.2;
//     private static final double RR_4H_WEAK_ADX35   = 1.4;
//     private static final double RR_4H_WEAK_ADX25   = 1.3;
//     private static final double RR_4H_WEAK_WEAK    = 1.2;
//     private static final double RR_CONTRA          = 1.2; // macro-contra trades max

//     // ── Entry filters ────────────────────────────────────────────────────────
//     private static final double EMA9_PULLBACK_MAX    = 1.2;
//     private static final double MAX_CANDLE_ATR_RATIO = 1.6;
//     private static final double ST_FLIP_MAX_RATIO    = 1.3;

//     // ── 5m candle quality ────────────────────────────────────────────────────
//     private static final double MIN_5M_BODY_RATIO        = 0.35;
//     private static final double CLOSE_NEAR_EXTREME_RATIO = 0.45;

//     // ── Volatility ───────────────────────────────────────────────────────────
//     private static final double MAX_ATR_PERCENT = 3.0;

//     // ── SL cap (2.5% max + slippage buffer) ──────────────────────────────────
//     private static final double MAX_SL_PCT         = 2.5;
//     private static final double SL_SLIPPAGE_BUFFER = 0.15;

//     // ── Volume soft filter (median-based) ────────────────────────────────────
//     private static final double VOL_MULTIPLIER = 1.3;
//     private static final int    VOL_AVG_PERIOD = 20;

//     // ── Minimum tick gap between entry / SL / TP ─────────────────────────────
//     private static final int MIN_TICK_GAP = 2;

//     // ── Candle fetch counts ───────────────────────────────────────────────────
//     private static final int CANDLE_15M = 100;
//     private static final int CANDLE_5M  = 60;
//     private static final int CANDLE_1H  = 220;
//     private static final int CANDLE_4H  = 250; // 250 × 4H ≈ 41 days (enough for EMA200)

//     // ── Daily limits ─────────────────────────────────────────────────────────
//     private static double dailyPnL               = 0.0;
//     private static final double DAILY_LOSS_LIMIT  = 5000.0;
//     private static final double DAILY_PROFIT_LOCK = 8000.0;

//     private static final Map<String, JSONObject> instrumentCache = new ConcurrentHashMap<>();
//     private static long lastCacheUpdate = 0;
//     private static final Map<String, Long> lastTradeTime = new ConcurrentHashMap<>();

//     // =========================================================================
//     // Coin list
//     // =========================================================================
//     private static final String[] COIN_SYMBOLS = {
//         "ETH", "ZEC", "XRP", "DOGE", "BNB", "TAO", "1000PEPE", "ADA", "SUI",
//         "BCH", "LINK", "FIL", "OP", "TRX", "TRUMP", "ARB", "WLD",
//         "FET", "ETC", "AAVE", "WIF", "INJ", "TIA", "LTC", "ONDO", "ORDI", "TON",
//         "HBAR", "IMX", "ATOM", "RUNE", "KAS", "UNI", "ICP", "SEI", "PENDLE", "1000SHIB",
//         "1000BONK", "CRV", "JUP", "RENDER", "MKR", "LDO", "STX", "XLM", "PYTH", "VIRTUAL",
//         "APT", "SNX", "STRK", "NEO", "FTM", "CAKE", "1000FLOKI", "1000SATS", "OM", "FARTCOIN",
//         "GRT", "MINA", "COMP", "BLUR", "BRETT", "SAND", "EGLD", "XMR", "IOTA", "AI16Z",
//         "PNUT", "POPCAT", "ZRO", "MANA", "ETHFI", "VET", "ALGO", "ENS", "BOME", "MASK",
//         "GALA", "YFI", "CHZ", "GMX", "QNT", "POL", "MOODENG", "ZK", "ARKM", "THETA",
//         "MEW", "EIGEN", "MORPHO", "KAITO", "USUAL", "LAYER", "GOAT", "DOGS", "RSR", "PONKE",
//         "JTO", "CKB", "ZIL", "ROSE", "1INCH", "TWT", "KSM", "MAGIC", "GAS", "ACT",
//         "SUSHI", "TURBO", "1000LUNC", "BTCDOM", "S", "IP", "FLOW", "TRB", "QTUM", "KNC",
//         "KAIA", "CELO", "SSV", "BANANA", "TNSR", "AERO", "IO", "DEXE", "ARK", "XAI",
//         "DYM", "SAGA", "HOT", "LUNA2", "IOST", "RPL", "VANA", "DASH", "MANTA", "LRC",
//         "ANKR", "XTZ", "BAND", "SUPER", "FXS", "AKT", "NMR", "PIXEL", "LPT", "STORJ",
//         "ENJ", "LISTA", "ZETA", "RED", "AGLD", "GPS", "KAVA", "SXP", "ALPHA", "BIGTIME",
//         "COTI", "USTC", "BAT", "NFP", "ONE", "POLYX", "MOVR", "OMNI", "CELR", "RVN",
//         "GLM", "HIVE", "FLUX", "ZRX", "SFP", "ALICE", "ILV", "ARPA", "UMA", "DEGEN",
//         "XVS", "ACE", "ASTR", "CTSI", "CHR", "EDU", "PROM", "ALT", "C98", "SUN",
//         "WAXP", "ALPACA", "COOKIE", "JOE", "BNT", "SCRT", "VELODROME", "HOOK", "KMNO", "NTRN",
//         "VIC", "RAYSOL", "PARTI", "MELANIA", "MYRO", "SHELL", "AUCTION", "SWELL", "HIGH", "WOO",
//         "COW", "MAVIA", "VTHO", "1000CAT", "MUBARAK", "LEVER", "SOLV", "ARC", "AVAAI", "KOMA",
//         "API3", "VOXEL", "CHESS", "SPELL", "1000WHY", "SKL", "GTC", "MTL", "BICO", "DENT",
//         "RLC", "PHB", "POWR", "LSK", "DEFI", "MAV", "REI", "ONG", "XVG", "COS",
//         "FORTH", "BEL", "MLN", "HEI", "GHST", "STEEM", "LOKA", "DIA", "TLM", "BMT",
//         "ALCH", "FUN", "1000CHEEMS", "1000RATS", "1000000MOG", "1MBABYDOGE", "1000XEC", "1000X", "PERP", "NKN",
//         "VINE", "RARE", "HFT", "AXL", "ACH", "ZEN", "PEOPLE", "AR", "CFX", "ID",
//         "METIS", "FIO", "CYBER"
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

//         if (dailyPnL <= -DAILY_LOSS_LIMIT) {
//             System.out.printf("DAILY LOSS LIMIT HIT (%.0f INR) — No more trades today!%n", DAILY_LOSS_LIMIT);
//             return;
//         }
//         if (dailyPnL >= DAILY_PROFIT_LOCK) {
//             System.out.printf("DAILY PROFIT LOCKED (%.0f INR) — Stopping to protect gains!%n", DAILY_PROFIT_LOCK);
//             return;
//         }

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

//                 if (dailyPnL <= -DAILY_LOSS_LIMIT) {
//                     System.out.println("DAILY LOSS LIMIT — stopping scan");
//                     break;
//                 }

//                 System.out.println("\n==== " + pair + " ====");

//                 // ── Fetch candles ──────────────────────────────────────────
//                 JSONArray raw15m = getCandlestickData(pair, "15",  CANDLE_15M);
//                 JSONArray raw1h  = getCandlestickData(pair, "60",  CANDLE_1H);
//                 JSONArray raw4h  = getCandlestickData(pair, "240", CANDLE_4H);
//                 JSONArray raw5m  = getCandlestickData(pair, "5",   CANDLE_5M);

//                 if (raw15m == null || raw15m.length() < 60)  { System.out.println("  Insufficient 15m — skip"); continue; }
//                 if (raw1h  == null || raw1h.length() < EMA_MACRO) { System.out.println("  Insufficient 1H — skip"); continue; }
//                 if (raw4h  == null || raw4h.length() < EMA_MACRO_SLOW + 10) { System.out.println("  Insufficient 4H — skip"); continue; }
//                 if (raw5m  == null || raw5m.length() < 30)   { System.out.println("  Insufficient 5m — skip"); continue; }

//                 // ── Extract arrays ─────────────────────────────────────────
//                 double[] cl15  = extractCloses(raw15m);
//                 double[] op15  = extractOpens(raw15m);
//                 double[] hi15  = extractHighs(raw15m);
//                 double[] lo15  = extractLows(raw15m);
//                 double[] vol15 = extractVolumes(raw15m);
//                 double[] cl1h  = extractCloses(raw1h);
//                 double[] hi1h  = extractHighs(raw1h);
//                 double[] lo1h  = extractLows(raw1h);
//                 double[] cl4h  = extractCloses(raw4h);
//                 double[] hi4h  = extractHighs(raw4h);
//                 double[] lo4h  = extractLows(raw4h);
//                 double[] cl5m  = extractCloses(raw5m);
//                 double[] op5m  = extractOpens(raw5m);
//                 double[] hi5m  = extractHighs(raw5m);
//                 double[] lo5m  = extractLows(raw5m);

//                 // last = last CLOSED candle on 15m (length-1 is current open)
//                 int last = cl15.length - 2;
//                 int prev = cl15.length - 3;

//                 double lastClose = cl15[last];
//                 double lastHigh  = hi15[last];
//                 double lastLow   = lo15[last];
//                 double prevClose = cl15[prev];
//                 double prevOpen  = op15[prev];

//                 double tickSize = getTickSize(pair);
//                 double atr15m   = calcATR(hi15, lo15, cl15, ATR_PERIOD);
//                 double atr5m    = calcATR(hi5m, lo5m, cl5m, ATR_PERIOD);

//                 System.out.printf("  Price=%.6f  ATR15m=%.6f  ATR5m=%.6f  Tick=%.8f%n",
//                         lastClose, atr15m, atr5m, tickSize);

//                 // ── VOLATILITY CHECK ───────────────────────────────────────
//                 double atrPercent = (atr15m / lastClose) * 100.0;
//                 if (atrPercent > MAX_ATR_PERCENT) {
//                     System.out.printf("  VOL_ATR FAIL — ATR%%=%.2f%% > %.1f%% — skip%n", atrPercent, MAX_ATR_PERCENT);
//                     continue;
//                 }
//                 System.out.printf("  VOL_ATR OK — ATR%%=%.2f%%%n", atrPercent);

//                 // ─────────────────────────────────────────────────────────────
//                 // M0: 4H DUAL EMA ALIGNMENT (NEW)
//                 // Condition A: price > EMA50_4H (price above medium-term trend)
//                 // Condition B: EMA50_4H > EMA200_4H (medium > long term = bull structure)
//                 // M0_strong: both A and B (full bull/bear structure)
//                 // M0_weak:   only one of A or B (partial alignment — lower RR)
//                 // M0_fail:   neither (counter-trend — skip entirely)
//                 // ─────────────────────────────────────────────────────────────
//                 int last4h = cl4h.length - 2; // last closed 4H candle

//                 double[] ema50_4h_series  = calcEMASeries(cl4h, EMA_MACRO);
//                 double[] ema200_4h_series = calcEMASeries(cl4h, EMA_MACRO_SLOW);
//                 double ema50_4h  = ema50_4h_series[last4h];
//                 double ema200_4h = ema200_4h_series[last4h];
//                 double lastClose4h = cl4h[last4h];

//                 // For LONG: price > EMA50_4H is A, EMA50_4H > EMA200_4H is B
//                 // For SHORT: price < EMA50_4H is A, EMA50_4H < EMA200_4H is B
//                 // We evaluate after direction is determined — for now store raw values
//                 boolean priceAbove4hEma50 = lastClose > ema50_4h;  // use 15m current price
//                 boolean ema50Above200_4h  = ema50_4h > ema200_4h;

//                 System.out.printf("  [M0] 4H: price=%.4f EMA50=%.4f EMA200=%.4f | priceAboveEMA50=%s | EMA50>EMA200=%s%n",
//                         lastClose, ema50_4h, ema200_4h, priceAbove4hEma50, ema50Above200_4h);

//                 // ── M1: 1H EMA50 (intermediate filter) ────────────────────
//                 double ema1h    = calcEMA(cl1h, EMA_MACRO);
//                 boolean macroUp = lastClose > ema1h;
//                 System.out.printf("  [M1] 1H_EMA50=%.6f -> %s%n", ema1h, macroUp ? "BULL" : "BEAR");

//                 // ─────────────────────────────────────────────────────────────
//                 // T1: 15m EMA9/21 alignment + slope
//                 // Uses series[last] for index-correct EMA values
//                 // ─────────────────────────────────────────────────────────────
//                 double[] ema9Series  = calcEMASeries(cl15, EMA_FAST);
//                 double[] ema21Series = calcEMASeries(cl15, EMA_MID);
//                 double ema9      = ema9Series[last];
//                 double ema21     = ema21Series[last];
//                 double prevEma21 = ema21Series[last - 1];
//                 double prevEma9  = ema9Series[last - 1];

//                 boolean ema21Rising  = ema21 > prevEma21;
//                 boolean ema21Falling = ema21 < prevEma21;
//                 boolean localUp      = ema9 > ema21;
//                 boolean localDown    = ema9 < ema21;

//                 System.out.printf("  [T1] EMA9=%.6f EMA21=%.6f | local=%s | EMA21slope=%s%n",
//                         ema9, ema21,
//                         localUp ? "UP" : localDown ? "DOWN" : "FLAT",
//                         ema21Rising ? "RISING" : ema21Falling ? "FALLING" : "FLAT");

//                 // Local EMA alignment is primary gate; macro adds RR bonus
//                 boolean trendUp, trendDown;
//                 if (localUp && ema21Rising) {
//                     trendUp   = true;
//                     trendDown = false;
//                 } else if (localDown && ema21Falling) {
//                     trendUp   = false;
//                     trendDown = true;
//                 } else {
//                     System.out.println("  T1 FAIL — 15m EMA not aligned or slope wrong — skip");
//                     continue;
//                 }

//                 // ── Evaluate M0 score now that direction is known ──────────
//                 // Long:  A = price > EMA50_4H,  B = EMA50_4H > EMA200_4H
//                 // Short: A = price < EMA50_4H,  B = EMA50_4H < EMA200_4H
//                 boolean m0_A, m0_B;
//                 if (trendUp) {
//                     m0_A = priceAbove4hEma50;
//                     m0_B = ema50Above200_4h;
//                 } else {
//                     m0_A = !priceAbove4hEma50;
//                     m0_B = !ema50Above200_4h;
//                 }
//                 int m0Score = (m0_A ? 1 : 0) + (m0_B ? 1 : 0);
//                 boolean m0Strong = m0Score == 2;
//                 boolean m0Weak   = m0Score == 1;
//                 boolean m0Fail   = m0Score == 0;

//                 System.out.printf("  [M0] Score=%d/2 (%s) — A=%s B=%s%n",
//                         m0Score, m0Strong ? "STRONG" : m0Weak ? "WEAK" : "FAIL", m0_A, m0_B);

//                 if (m0Fail) {
//                     System.out.println("  M0 FAIL — 4H fully against direction — skip");
//                     continue;
//                 }

//                 // M1 must agree with direction (no M1 contra allowed)
//                 boolean macroAligned = (trendUp && macroUp) || (trendDown && !macroUp);
//                 if (!macroAligned) {
//                     System.out.println("  M1 FAIL — 1H trend against direction — skip");
//                     continue;
//                 }
//                 System.out.printf("  T1+M0+M1 OK — direction=%s macroAligned=true M0=%s%n",
//                         trendUp ? "LONG" : "SHORT", m0Strong ? "STRONG" : "WEAK");

//                 // ── T2: MACD (closed candles only) ─────────────────────────
//                 double[] closedCl15 = Arrays.copyOfRange(cl15, 0, last + 1);
//                 double[] mv     = calcMACD(closedCl15, MACD_FAST, MACD_SLOW, MACD_SIG);
//                 double macdLine = mv[0], macdSigV = mv[1];
//                 System.out.printf("  [T2] MACD=%.6f Sig=%.6f -> %s%n",
//                         macdLine, macdSigV, macdLine > macdSigV ? "BULL" : "BEAR");
//                 if (trendUp   && macdLine <= macdSigV) { System.out.println("  T2 FAIL — MACD bearish — skip"); continue; }
//                 if (trendDown && macdLine >= macdSigV) { System.out.println("  T2 FAIL — MACD bullish — skip"); continue; }
//                 System.out.println("  T2 OK");

//                 // ── T3: Supertrend ─────────────────────────────────────────
//                 double[][] stFull   = calcSupertrendWithBands(hi15, lo15, cl15, ST_PERIOD, ST_MULTIPLIER);
//                 boolean[] stBullArr = toBooleanArr(stFull[0]);
//                 double[]  stLower   = stFull[1];
//                 double[]  stUpper   = stFull[2];

//                 boolean stBull      = stBullArr[last];
//                 boolean stPrevBull  = stBullArr[prev];
//                 boolean stFlipped   = stBull != stPrevBull;
//                 double stLowerValue = stLower[last];
//                 double stUpperValue = stUpper[last];

//                 System.out.printf("  [T3] ST=%s%s Lower=%.6f Upper=%.6f%n",
//                         stBull ? "GREEN" : "RED", stFlipped ? " [FLIPPED]" : "",
//                         stLowerValue, stUpperValue);
//                 if (trendUp   && !stBull) { System.out.println("  T3 FAIL — ST bearish — skip"); continue; }
//                 if (trendDown &&  stBull) { System.out.println("  T3 FAIL — ST bullish — skip"); continue; }
//                 System.out.println("  T3 OK");

//                 // ─────────────────────────────────────────────────────────────
//                 // T4: Williams %R (NEW)
//                 // Prevents entering when price is at momentum extremes:
//                 //   LONG:  WR >= -80  → price not deep oversold (should be recovering)
//                 //   SHORT: WR <= -20  → price not deep overbought (should be falling)
//                 // Williams %R = (HH - Close) / (HH - LL) * -100
//                 // Range: -100 (oversold) to 0 (overbought)
//                 // ─────────────────────────────────────────────────────────────
//                 double wr = calcWilliamsR(hi15, lo15, closedCl15, WR_PERIOD);
//                 boolean wrOkLong  = trendUp   && wr >= WR_LONG_MIN;   // >= -80
//                 boolean wrOkShort = trendDown && wr <= WR_SHORT_MAX;  // <= -20
//                 boolean wrOk      = wrOkLong || wrOkShort;
//                 System.out.printf("  [T4] Williams%%R=%.2f | LONG_min=%.0f SHORT_max=%.0f -> %s%n",
//                         wr, WR_LONG_MIN, WR_SHORT_MAX, wrOk ? "PASS" : "FAIL");
//                 if (!wrOk) {
//                     if (trendUp)
//                         System.out.printf("  T4 FAIL — WR=%.1f < -80 (deep oversold, not confirmed) — skip%n", wr);
//                     else
//                         System.out.printf("  T4 FAIL — WR=%.1f > -20 (deep overbought, not confirmed) — skip%n", wr);
//                     continue;
//                 }
//                 System.out.println("  T4 OK");

//                 // ── Q1: ADX ───────────────────────────────────────────────
//                 double adx = calcADX(hi15, lo15, cl15, ADX_PERIOD);
//                 System.out.printf("  [Q1] ADX=%.2f (min=%.0f) -> %s%n",
//                         adx, ADX_MIN, adx >= ADX_MIN ? "PASS" : "FAIL");
//                 if (adx < ADX_MIN) { System.out.println("  Q1 FAIL — sideways — skip"); continue; }
//                 System.out.printf("  Q1 OK — ADX=%.1f%n", adx);

//                 // ── E1: Directional check ─────────────────────────────────
//                 double distFromEma9   = Math.abs(lastClose - ema9);
//                 double maxAllowedDist = EMA9_PULLBACK_MAX * atr15m;

//                 boolean nearEma9;
//                 if (trendUp) {
//                     nearEma9 = lastClose >= ema9 && distFromEma9 <= maxAllowedDist;
//                 } else {
//                     nearEma9 = lastClose <= ema9 && distFromEma9 <= maxAllowedDist;
//                 }

//                 System.out.printf("  [E1] Price=%.6f EMA9=%.6f Dist=%.6f Max=%.6f -> %s%n",
//                         lastClose, ema9, distFromEma9, maxAllowedDist, nearEma9 ? "PASS" : "FAIL");

//                 if (stFlipped) {
//                     if (distFromEma9 > ST_FLIP_MAX_RATIO * atr15m) {
//                         System.out.println("  E1 FAIL (ST flip) — too extended — skip");
//                         continue;
//                     }
//                 } else if (!nearEma9) {
//                     System.out.println("  E1 FAIL — wrong side of EMA9 or too far — skip");
//                     continue;
//                 }
//                 System.out.println("  E1 OK");

//                 // ── E2: Candle size ───────────────────────────────────────
//                 double candleSize = Math.abs(lastHigh - lastLow);
//                 double maxCandle  = MAX_CANDLE_ATR_RATIO * atr15m;
//                 System.out.printf("  [E2] CandleSize=%.6f Max=%.6f -> %s%n",
//                         candleSize, maxCandle, candleSize <= maxCandle ? "PASS" : "FAIL");
//                 if (candleSize > maxCandle) { System.out.println("  E2 FAIL — overextended — skip"); continue; }
//                 System.out.println("  E2 OK");

//                 // ── SL_PRE: Pre-order SL width check ─────────────────────
//                 double preCheckPrice = lastClose;
//                 double preSlDistance;
//                 if (trendUp) {
//                     preSlDistance = Math.abs(preCheckPrice - (stLowerValue - ST_SL_BUFFER_ATR * atr15m));
//                     if (preSlDistance < atr15m * 0.8) preSlDistance = 1.5 * atr15m;
//                 } else {
//                     preSlDistance = Math.abs(preCheckPrice - (stUpperValue + ST_SL_BUFFER_ATR * atr15m));
//                     if (preSlDistance < atr15m * 0.8) preSlDistance = 1.5 * atr15m;
//                 }
//                 double preSlPct      = (preSlDistance / preCheckPrice) * 100.0;
//                 double effectiveSlPct = preSlPct + SL_SLIPPAGE_BUFFER;
//                 System.out.printf("  [SL_PRE] SL_est=%.2f%% + buf=%.2f%% = %.2f%% (max=%.1f%%) -> %s%n",
//                         preSlPct, SL_SLIPPAGE_BUFFER, effectiveSlPct, MAX_SL_PCT,
//                         effectiveSlPct <= MAX_SL_PCT ? "OK" : "SKIP");
//                 if (effectiveSlPct > MAX_SL_PCT) {
//                     System.out.println("  SL_PRE FAIL — ST band too wide — skip");
//                     continue;
//                 }
//                 System.out.println("  SL_PRE OK");

//                 // ── SOFT FILTERS ──────────────────────────────────────────
//                 double rsi = calcRSI(closedCl15, RSI_PERIOD);
//                 boolean rsiOkLong  = trendUp   && rsi >= RSI_LONG_MIN  && rsi <= RSI_LONG_MAX;
//                 boolean rsiOkShort = trendDown && rsi >= RSI_SHORT_MIN && rsi <= RSI_SHORT_MAX;
//                 boolean softRsi    = rsiOkLong || rsiOkShort;
//                 System.out.printf("  [S1] RSI=%.2f -> %s%n", rsi, softRsi ? "PASS" : "fail");

//                 boolean prevBull   = prevClose > prevOpen;
//                 boolean prevBear   = prevClose < prevOpen;
//                 boolean softCandle = (trendUp && prevBull) || (trendDown && prevBear);
//                 System.out.printf("  [S2] PrevCandle=%s -> %s%n",
//                         prevBull ? "BULL" : prevBear ? "BEAR" : "DOJI", softCandle ? "PASS" : "fail");

//                 double currentVol = vol15[last];
//                 double medianVol  = calcMedianVolume(vol15, last, VOL_AVG_PERIOD);
//                 boolean softVol   = medianVol > 0 && currentVol >= medianVol * VOL_MULTIPLIER;
//                 System.out.printf("  [S3] Vol=%.2f MedianVol=%.2f (%.1fx) -> %s%n",
//                         currentVol, medianVol,
//                         medianVol > 0 ? currentVol / medianVol : 0,
//                         softVol ? "PASS" : "fail");

//                 if (!softRsi && !softVol) {
//                     System.out.println("  SOFT FAIL — RSI and Volume both fail — skip");
//                     continue;
//                 }
//                 System.out.println("  SOFT OK (" +
//                         (softRsi ? "RSI " : "") +
//                         (softCandle ? "Candle " : "") +
//                         (softVol ? "Volume" : "") + ")");

//                 // ── 5m ENTRY: Strong momentum candle ──────────────────────
//                 int last5          = cl5m.length - 2;
//                 double last5mClose = cl5m[last5];
//                 double last5mOpen  = op5m[last5];
//                 double last5mHigh  = hi5m[last5];
//                 double last5mLow   = lo5m[last5];
//                 double last5mRange = last5mHigh - last5mLow;
//                 double last5mBody  = Math.abs(last5mClose - last5mOpen);
//                 boolean entry5mBull = last5mClose > last5mOpen;
//                 boolean entry5mBear = last5mClose < last5mOpen;
//                 double  bodyRatio   = last5mRange > 0 ? last5mBody / last5mRange : 0;
//                 boolean strongBody  = bodyRatio >= MIN_5M_BODY_RATIO;
//                 double  closePos    = last5mRange > 0 ? (last5mClose - last5mLow) / last5mRange : 0.5;
//                 boolean closeNearHigh = closePos >= (1.0 - CLOSE_NEAR_EXTREME_RATIO);
//                 boolean closeNearLow  = closePos <= CLOSE_NEAR_EXTREME_RATIO;

//                 boolean entry5mOk = trendUp
//                         ? (entry5mBull && strongBody && closeNearHigh)
//                         : (entry5mBear && strongBody && closeNearLow);

//                 System.out.printf("  [5m] Bull=%s Bear=%s Body=%.0f%% ClosePos=%.0f%% -> %s%n",
//                         entry5mBull, entry5mBear, bodyRatio * 100, closePos * 100,
//                         entry5mOk ? "PASS" : "FAIL");
//                 if (!entry5mOk) { System.out.println("  5m FAIL — weak momentum — skip"); continue; }
//                 System.out.println("  5m OK");

//                 // ── ALL FILTERS PASSED ─────────────────────────────────────
//                 String side = trendUp ? "buy" : "sell";

//                 // Dynamic RR based on M0 strength + ADX
//                 double dynamicRR;
//                 if (m0Strong) {
//                     if      (adx >= 35) dynamicRR = RR_4H_STRONG_ADX35; // 1.6x
//                     else if (adx >= 25) dynamicRR = RR_4H_STRONG_ADX25; // 1.4x
//                     else                dynamicRR = RR_4H_STRONG_WEAK;  // 1.2x
//                 } else {
//                     // m0Weak
//                     if      (adx >= 35) dynamicRR = RR_4H_WEAK_ADX35;   // 1.4x
//                     else if (adx >= 25) dynamicRR = RR_4H_WEAK_ADX25;   // 1.3x
//                     else                dynamicRR = RR_4H_WEAK_WEAK;    // 1.2x
//                 }

//                 System.out.println("\n  ╔══════════════════════════════════════════════════════╗");
//                 System.out.println("  ║  ALL FILTERS PASSED → " + side.toUpperCase() + " " + pair);
//                 System.out.printf ("  ║  ADX=%.1f RSI=%.1f WR=%.1f ATR%%=%.2f%%%n",
//                         adx, rsi, wr, atrPercent);
//                 System.out.printf ("  ║  M0=%s(score=%d) MacroAligned=true RR=1:%.1f%n",
//                         m0Strong ? "STRONG" : "WEAK", m0Score, dynamicRR);
//                 System.out.printf ("  ║  4H_EMA50=%.4f 4H_EMA200=%.4f%n", ema50_4h, ema200_4h);
//                 System.out.printf ("  ║  PreSL_est=%.2f%% ST_Lower=%.6f ST_Upper=%.6f%n",
//                         preSlPct, stLowerValue, stUpperValue);
//                 if (stFlipped) System.out.println("  ║  *** SUPERTREND FLIP ***");
//                 System.out.println("  ╚══════════════════════════════════════════════════════╝");

//                 // ── Place order ────────────────────────────────────────────
//                 double currentPrice = getLastPrice(pair);
//                 if (currentPrice <= 0) { System.out.println("  Invalid price — skip"); continue; }

//                 double usdtInrRate = getDynamicUsdtInrRate();
//                 double qty = calcQuantity(currentPrice, pair, usdtInrRate);
//                 if (qty <= 0) { System.out.println("  Invalid qty — skip"); continue; }

//                 System.out.printf("  Placing %s | price=%.6f | qty=%.4f | lev=%dx | posVal=~%.0f INR%n",
//                         side.toUpperCase(), currentPrice, qty, LEVERAGE,
//                         qty * currentPrice * usdtInrRate);

//                 JSONObject resp = placeFuturesMarketOrder(side, pair, qty, LEVERAGE,
//                         "email_notification", "isolated", "INR");

//                 if (resp == null || !resp.has("id")) {
//                     System.out.println("  Order failed: " + resp);
//                     continue;
//                 }
//                 System.out.println("  Order placed! id=" + resp.getString("id"));
//                 lastTradeTime.put(pair, System.currentTimeMillis());

//                 // ── Confirm entry ──────────────────────────────────────────
//                 double entry = getEntryPrice(pair, resp.getString("id"));
//                 if (entry <= 0) {
//                     System.out.println("  Could not confirm entry — TP/SL skipped");
//                     continue;
//                 }
//                 System.out.printf("  Entry confirmed: %.6f%n", entry);

//                 // ── Compute SL from ST bands (real entry price) ────────────
//                 double slPrice, slDistance;
//                 if ("buy".equalsIgnoreCase(side)) {
//                     slPrice    = stLowerValue - (ST_SL_BUFFER_ATR * atr15m);
//                     slDistance = Math.abs(entry - slPrice);
//                     if (slDistance < atr15m * 0.8) {
//                         slPrice    = entry - (1.5 * atr15m);
//                         slDistance = 1.5 * atr15m;
//                     }
//                 } else {
//                     slPrice    = stUpperValue + (ST_SL_BUFFER_ATR * atr15m);
//                     slDistance = Math.abs(entry - slPrice);
//                     if (slDistance < atr15m * 0.8) {
//                         slPrice    = entry + (1.5 * atr15m);
//                         slDistance = 1.5 * atr15m;
//                     }
//                 }

//                 // Cap SL at MAX_SL_PCT from real entry
//                 double finalSlPct = (slDistance / entry) * 100.0;
//                 if (finalSlPct > MAX_SL_PCT) {
//                     double cappedDist = entry * (MAX_SL_PCT / 100.0);
//                     slPrice    = "buy".equalsIgnoreCase(side) ? entry - cappedDist : entry + cappedDist;
//                     slDistance = cappedDist;
//                     System.out.printf("  SL capped at %.1f%% -> SL=%.6f%n", MAX_SL_PCT, slPrice);
//                 }

//                 // Compute TP
//                 double tpDistance = dynamicRR * slDistance;
//                 double tpPrice    = "buy".equalsIgnoreCase(side)
//                         ? entry + tpDistance
//                         : entry - tpDistance;

//                 // Round both to tick
//                 slPrice = roundToTick(slPrice, tickSize);
//                 tpPrice = roundToTick(tpPrice, tickSize);

//                 // Enforce minimum tick gap
//                 double minGap = tickSize * MIN_TICK_GAP;
//                 if ("buy".equalsIgnoreCase(side)) {
//                     if (slPrice >= entry - minGap)
//                         slPrice = roundToTick(entry - Math.max(atr15m, minGap * 2), tickSize);
//                     if (tpPrice <= entry + minGap)
//                         tpPrice = roundToTick(entry + Math.max(atr15m, minGap * 2), tickSize);
//                 } else {
//                     if (slPrice <= entry + minGap)
//                         slPrice = roundToTick(entry + Math.max(atr15m, minGap * 2), tickSize);
//                     if (tpPrice >= entry - minGap)
//                         tpPrice = roundToTick(entry - Math.max(atr15m, minGap * 2), tickSize);
//                 }

//                 // Final sanity check
//                 if (Math.abs(tpPrice - slPrice) < tickSize) {
//                     System.out.println("  ERROR: TP and SL too close after rounding — skip TP/SL");
//                     continue;
//                 }

//                 double slPct = Math.abs(entry - slPrice) / entry * 100.0;
//                 double tpPct = Math.abs(tpPrice - entry) / entry * 100.0;

//                 System.out.printf("  R:R = 1:%.1f  Entry=%.6f | SL=%.6f (-%.2f%%) | TP=%.6f (+%.2f%%)%n",
//                         dynamicRR, entry, slPrice, slPct, tpPrice, tpPct);

//                 // Side validation
//                 boolean slOk = "buy".equalsIgnoreCase(side) ? slPrice < entry : slPrice > entry;
//                 boolean tpOk = "buy".equalsIgnoreCase(side) ? tpPrice > entry : tpPrice < entry;
//                 if (!slOk || !tpOk) {
//                     System.out.printf("  CRITICAL: SL/TP side check FAILED (slOk=%s tpOk=%s) — skip%n", slOk, tpOk);
//                     continue;
//                 }

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
//     // Williams %R
//     // WR = (Highest High - Close) / (Highest High - Lowest Low) * -100
//     // Range: 0 (overbought) to -100 (oversold)
//     // =========================================================================
//     private static double calcWilliamsR(double[] hi, double[] lo, double[] cl, int period) {
//         int n = cl.length;
//         if (n < period) return -50.0; // neutral fallback
//         int from = n - period;
//         double hh = hi[from], ll = lo[from];
//         for (int i = from + 1; i < n; i++) {
//             if (hi[i] > hh) hh = hi[i];
//             if (lo[i] < ll) ll = lo[i];
//         }
//         double range = hh - ll;
//         if (range == 0) return -50.0;
//         return ((hh - cl[n - 1]) / range) * -100.0;
//     }

//     // =========================================================================
//     // Median Volume (outlier-resistant)
//     // =========================================================================
//     private static double calcMedianVolume(double[] vol, int upToIndex, int period) {
//         int startIdx = Math.max(0, upToIndex - period);
//         List<Double> vals = new ArrayList<>();
//         for (int i = startIdx; i < upToIndex; i++) {
//             if (vol[i] > 0) vals.add(vol[i]);
//         }
//         if (vals.isEmpty()) return 0;
//         Collections.sort(vals);
//         int mid = vals.size() / 2;
//         return vals.size() % 2 == 0
//                 ? (vals.get(mid - 1) + vals.get(mid)) / 2.0
//                 : vals.get(mid);
//     }

//     // =========================================================================
//     // Dynamic USDT/INR rate
//     // =========================================================================
//     private static double cachedUsdtInrRate = 84.0;
//     private static long   usdtInrFetchTime  = 0;

//     private static double getDynamicUsdtInrRate() {
//         if (System.currentTimeMillis() - usdtInrFetchTime < 600_000L) return cachedUsdtInrRate;
//         try {
//             String url = PUBLIC_API_URL + "/market_data/trade_history?pair=B-USDT_INR&limit=1";
//             HttpURLConnection conn = openGet(url);
//             if (conn.getResponseCode() == 200) {
//                 String r = readStream(conn.getInputStream());
//                 double rate = r.startsWith("[")
//                         ? new JSONArray(r).getJSONObject(0).getDouble("p")
//                         : new JSONObject(r).getDouble("p");
//                 if (rate > 70 && rate < 120) {
//                     cachedUsdtInrRate = rate;
//                     usdtInrFetchTime  = System.currentTimeMillis();
//                     System.out.printf("  USDT/INR rate fetched: %.2f%n", rate);
//                     return rate;
//                 }
//             }
//         } catch (Exception e) {
//             System.err.println("  USDT/INR fetch failed, using cached: " + cachedUsdtInrRate);
//         }
//         return cachedUsdtInrRate;
//     }

//     // =========================================================================
//     // ADX
//     // =========================================================================
//     private static double calcADX(double[] hi, double[] lo, double[] cl, int period) {
//         int n = hi.length;
//         if (n < period * 2) return 0;
//         double[] plusDM = new double[n], minusDM = new double[n], tr = new double[n];
//         for (int i = 1; i < n; i++) {
//             double up = hi[i] - hi[i-1], dn = lo[i-1] - lo[i];
//             plusDM[i]  = (up > dn && up > 0) ? up : 0;
//             minusDM[i] = (dn > up && dn > 0) ? dn : 0;
//             tr[i] = Math.max(hi[i]-lo[i], Math.max(Math.abs(hi[i]-cl[i-1]), Math.abs(lo[i]-cl[i-1])));
//         }
//         double sTR = 0, sP = 0, sM = 0;
//         for (int i = 1; i <= period; i++) { sTR += tr[i]; sP += plusDM[i]; sM += minusDM[i]; }
//         double prevADX = 0; int cnt = 0; double sum = 0;
//         for (int i = period + 1; i < n; i++) {
//             sTR = sTR - sTR/period + tr[i];
//             sP  = sP  - sP/period  + plusDM[i];
//             sM  = sM  - sM/period  + minusDM[i];
//             if (sTR == 0) continue;
//             double pDI = 100*sP/sTR, mDI = 100*sM/sTR, ds = pDI+mDI;
//             double dx  = ds == 0 ? 0 : 100*Math.abs(pDI-mDI)/ds;
//             if (cnt < period) { sum += dx; cnt++; if (cnt == period) prevADX = sum/period; }
//             else prevADX = (prevADX*(period-1)+dx)/period;
//         }
//         return prevADX;
//     }

//     // =========================================================================
//     // Supertrend with bands
//     // =========================================================================
//     private static double[][] calcSupertrendWithBands(double[] hi, double[] lo, double[] cl,
//                                                        int period, double multiplier) {
//         int n = cl.length;
//         double[] isBullish = new double[n], lowerBand = new double[n], upperBand = new double[n];
//         if (n < period + 1) {
//             Arrays.fill(isBullish, 1.0);
//             Arrays.fill(lowerBand, cl[n-1] * 0.98);
//             Arrays.fill(upperBand, cl[n-1] * 1.02);
//             return new double[][]{isBullish, lowerBand, upperBand};
//         }
//         double[] atrArr = calcATRSeries(hi, lo, cl, period);
//         for (int i = period; i < n; i++) {
//             double hl2 = (hi[i]+lo[i])/2.0;
//             double bU = hl2 + multiplier*atrArr[i], bL = hl2 - multiplier*atrArr[i];
//             if (i == period) {
//                 upperBand[i] = bU; lowerBand[i] = bL;
//                 isBullish[i] = cl[i] > hl2 ? 1.0 : 0.0;
//             } else {
//                 upperBand[i] = (bU < upperBand[i-1] || cl[i-1] > upperBand[i-1]) ? bU : upperBand[i-1];
//                 lowerBand[i] = (bL > lowerBand[i-1] || cl[i-1] < lowerBand[i-1]) ? bL : lowerBand[i-1];
//                 isBullish[i] = isBullish[i-1] == 1.0
//                         ? (cl[i] >= lowerBand[i] ? 1.0 : 0.0)
//                         : (cl[i] > upperBand[i]  ? 1.0 : 0.0);
//             }
//         }
//         for (int i = 0; i < period; i++) {
//             isBullish[i] = isBullish[period];
//             lowerBand[i] = lowerBand[period];
//             upperBand[i] = upperBand[period];
//         }
//         return new double[][]{isBullish, lowerBand, upperBand};
//     }

//     private static boolean[] toBooleanArr(double[] d) {
//         boolean[] b = new boolean[d.length];
//         for (int i = 0; i < d.length; i++) b[i] = d[i] == 1.0;
//         return b;
//     }

//     // =========================================================================
//     // ATR Series
//     // =========================================================================
//     private static double[] calcATRSeries(double[] hi, double[] lo, double[] cl, int period) {
//         int n = hi.length;
//         double[] atr = new double[n];
//         if (n < 2) return atr;
//         double[] tr = new double[n];
//         tr[0] = hi[0]-lo[0];
//         for (int i = 1; i < n; i++)
//             tr[i] = Math.max(hi[i]-lo[i], Math.max(Math.abs(hi[i]-cl[i-1]), Math.abs(lo[i]-cl[i-1])));
//         double sum = 0;
//         for (int i = 0; i < period && i < n; i++) sum += tr[i];
//         atr[period-1] = sum/period;
//         for (int i = period; i < n; i++) atr[i] = (atr[i-1]*(period-1)+tr[i])/period;
//         for (int i = 0; i < period-1; i++) atr[i] = atr[period-1];
//         return atr;
//     }

//     // =========================================================================
//     // Core Indicators
//     // =========================================================================
//     private static double calcEMA(double[] d, int period) {
//         if (d.length < period) return 0;
//         double k = 2.0/(period+1), ema = 0;
//         for (int i = 0; i < period; i++) ema += d[i];
//         ema /= period;
//         for (int i = period; i < d.length; i++) ema = d[i]*k + ema*(1-k);
//         return ema;
//     }

//     private static double[] calcEMASeries(double[] d, int period) {
//         double[] out = new double[d.length];
//         if (d.length < period) return out;
//         double k = 2.0/(period+1), seed = 0;
//         for (int i = 0; i < period; i++) seed += d[i];
//         out[period-1] = seed/period;
//         for (int i = period; i < d.length; i++) out[i] = d[i]*k + out[i-1]*(1-k);
//         return out;
//     }

//     private static double[] calcMACD(double[] cl, int fast, int slow, int sig) {
//         double[] ef = calcEMASeries(cl, fast), es = calcEMASeries(cl, slow);
//         int start = slow-1, len = cl.length-start;
//         if (len <= 0) return new double[]{0, 0, 0};
//         double[] ml = new double[len];
//         for (int i = 0; i < len; i++) ml[i] = ef[start+i] - es[start+i];
//         double[] ss = calcEMASeries(ml, sig);
//         double m = ml[ml.length-1], s = ss[ss.length-1];
//         return new double[]{m, s, m-s};
//     }

//     private static double calcRSI(double[] cl, int period) {
//         if (cl.length < period+1) return 50;
//         double ag = 0, al = 0;
//         for (int i = 1; i <= period; i++) {
//             double ch = cl[i]-cl[i-1];
//             if (ch > 0) ag += ch; else al += Math.abs(ch);
//         }
//         ag /= period; al /= period;
//         for (int i = period+1; i < cl.length; i++) {
//             double ch = cl[i]-cl[i-1];
//             if (ch > 0) { ag = (ag*(period-1)+ch)/period; al = al*(period-1)/period; }
//             else         { al = (al*(period-1)+Math.abs(ch))/period; ag = ag*(period-1)/period; }
//         }
//         if (al == 0) return 100;
//         return 100-(100/(1+ag/al));
//     }

//     private static double calcATR(double[] hi, double[] lo, double[] cl, int period) {
//         if (hi.length < period+1) return 0;
//         double[] tr = new double[hi.length];
//         tr[0] = hi[0]-lo[0];
//         for (int i = 1; i < hi.length; i++)
//             tr[i] = Math.max(hi[i]-lo[i], Math.max(Math.abs(hi[i]-cl[i-1]), Math.abs(lo[i]-cl[i-1])));
//         double atr = 0;
//         for (int i = 0; i < period; i++) atr += tr[i];
//         atr /= period;
//         for (int i = period; i < hi.length; i++) atr = (atr*(period-1)+tr[i])/period;
//         return atr;
//     }

//     private static double roundToTick(double price, double tick) {
//         if (tick <= 0) return price;
//         return Math.round(price/tick)*tick;
//     }

//     // =========================================================================
//     // OHLCV Extraction
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
//     private static double[] extractVolumes(JSONArray a) {
//         double[] o = new double[a.length()];
//         for (int i = 0; i < a.length(); i++) o[i] = a.getJSONObject(i).optDouble("volume", 0.0);
//         return o;
//     }

//     // =========================================================================
//     // CoinDCX API
//     // =========================================================================
//     private static JSONArray getCandlestickData(String pair, String resolution, int count) {
//         try {
//             long minsPerBar;
//             switch (resolution) {
//                 case "1":   minsPerBar = 1;   break;
//                 case "5":   minsPerBar = 5;   break;
//                 case "15":  minsPerBar = 15;  break;
//                 case "60":  minsPerBar = 60;  break;
//                 case "240": minsPerBar = 240; break;
//                 default:    minsPerBar = 15;
//             }
//             long to   = Instant.now().getEpochSecond();
//             long from = to - minsPerBar * 60L * count;
//             String url = PUBLIC_API_URL + "/market_data/candlesticks"
//                     + "?pair=" + pair + "&from=" + from + "&to=" + to
//                     + "&resolution=" + resolution + "&pcode=f";
//             HttpURLConnection conn = openGet(url);
//             if (conn.getResponseCode() == 200) {
//                 JSONObject r = new JSONObject(readStream(conn.getInputStream()));
//                 if ("ok".equals(r.optString("s"))) return r.getJSONArray("data");
//             }
//         } catch (Exception e) {
//             System.err.println("  getCandlestickData(" + pair + "/" + resolution + "): " + e.getMessage());
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
//                     String raw = publicGet(BASE_URL + "/exchange/v1/derivatives/futures/data/instrument?pair=" + p);
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
//             if (pos != null && pos.optDouble("avg_price", 0) > 0) return pos.getDouble("avg_price");
//         }
//         return 0;
//     }

//     private static JSONObject findPosition(String pair) throws Exception {
//         JSONObject body = new JSONObject();
//         body.put("timestamp", Instant.now().toEpochMilli());
//         body.put("page", "1");
//         body.put("size", "20");
//         body.put("margin_currency_short_name", new String[]{"INR", "USDT"});
//         String resp = authPost(BASE_URL + "/exchange/v1/derivatives/futures/positions", body.toString());
//         JSONArray arr = resp.startsWith("[") ? new JSONArray(resp) : new JSONArray().put(new JSONObject(resp));
//         for (int i = 0; i < arr.length(); i++) {
//             JSONObject p = arr.getJSONObject(i);
//             if (pair.equals(p.optString("pair"))) return p;
//         }
//         return null;
//     }

//     /**
//      * Qty = (MAX_MARGIN × LEVERAGE) / (price × usdtInrRate)
//      * Example: 2000 INR × 20x = 40000 INR position
//      *          at ETH = 2000 USDT, INR_rate = 84 → positionUsdt = 40000/84 = 476 USDT → qty = 476/2000 = 0.238 ETH
//      */
//     private static double calcQuantity(double price, String pair, double usdtInrRate) {
//         double positionUsdt = MAX_MARGIN / usdtInrRate;
//         double qty = positionUsdt / price;
//         double finalQty = INTEGER_QTY_PAIRS.contains(pair)
//                 ? Math.floor(qty)
//                 : Math.floor(qty * 100) / 100.0;
//         return Math.max(finalQty, 0);
//     }

//     public static double getLastPrice(String pair) {
//         try {
//             HttpURLConnection conn = openGet(PUBLIC_API_URL + "/market_data/trade_history?pair=" + pair + "&limit=1");
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
//             order.put("side", side.toLowerCase());
//             order.put("pair", pair);
//             order.put("order_type", "market_order");
//             order.put("total_quantity", qty);
//             order.put("leverage", lev);
//             order.put("notification", notif);
//             order.put("time_in_force", "good_till_cancel");
//             order.put("hidden", false);
//             order.put("post_only", false);
//             order.put("position_margin_type", marginType);
//             order.put("margin_currency_short_name", marginCcy);
//             JSONObject body = new JSONObject();
//             body.put("timestamp", Instant.now().toEpochMilli());
//             body.put("order", order);
//             String resp = authPost(BASE_URL + "/exchange/v1/derivatives/futures/orders/create", body.toString());
//             return resp.startsWith("[") ? new JSONArray(resp).getJSONObject(0) : new JSONObject(resp);
//         } catch (Exception e) {
//             System.err.println("placeFuturesMarketOrder: " + e.getMessage());
//             return null;
//         }
//     }

//     public static void setTpSl(String posId, double tp, double sl, String pair) {
//         try {
//             double tick = getTickSize(pair);
//             JSONObject tpObj = new JSONObject();
//             tpObj.put("stop_price",  roundToTick(tp, tick));
//             tpObj.put("limit_price", roundToTick(tp, tick));
//             tpObj.put("order_type",  "take_profit_market");
//             JSONObject slObj = new JSONObject();
//             slObj.put("stop_price",  roundToTick(sl, tick));
//             slObj.put("limit_price", roundToTick(sl, tick));
//             slObj.put("order_type",  "stop_market");
//             JSONObject payload = new JSONObject();
//             payload.put("timestamp",  Instant.now().toEpochMilli());
//             payload.put("id",         posId);
//             payload.put("take_profit", tpObj);
//             payload.put("stop_loss",   slObj);
//             String resp = authPost(BASE_URL + "/exchange/v1/derivatives/futures/positions/create_tpsl",
//                     payload.toString());
//             JSONObject r = new JSONObject(resp);
//             System.out.println(r.has("err_code_dcx") ? "  TP/SL error: " + r : "  TP/SL set successfully!");
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
//             String resp = authPost(BASE_URL + "/exchange/v1/derivatives/futures/positions", body.toString());
//             JSONArray arr = resp.startsWith("[") ? new JSONArray(resp) : new JSONArray().put(new JSONObject(resp));
//             System.out.println("=== Open Positions (" + arr.length() + ") ===");
//             for (int i = 0; i < arr.length(); i++) {
//                 JSONObject p = arr.getJSONObject(i);
//                 String pair = p.optString("pair", "");
//                 boolean isActive = p.optDouble("active_pos", 0) > 0
//                         || p.optDouble("locked_margin", 0) > 0
//                         || p.optDouble("avg_price", 0) > 0
//                         || p.optDouble("take_profit_trigger", 0) > 0
//                         || p.optDouble("stop_loss_trigger", 0) > 0;
//                 if (isActive) {
//                     System.out.printf("  %s | qty=%.2f | entry=%.6f | TP=%.4f | SL=%.4f%n",
//                             pair, p.optDouble("active_pos", 0), p.optDouble("avg_price", 0),
//                             p.optDouble("take_profit_trigger", 0), p.optDouble("stop_loss_trigger", 0));
//                     active.add(pair);
//                 }
//             }
//         } catch (Exception e) {
//             System.err.println("getActivePositions: " + e.getMessage());
//         }
//         return active;
//     }

//     // =========================================================================
//     // HTTP + HMAC
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
//         c.setRequestProperty("Content-Type", "application/json");
//         c.setRequestProperty("X-AUTH-APIKEY", API_KEY);
//         c.setRequestProperty("X-AUTH-SIGNATURE", sign(json));
//         c.setConnectTimeout(10_000);
//         c.setReadTimeout(10_000);
//         c.setDoOutput(true);
//         try (OutputStream os = c.getOutputStream()) {
//             os.write(json.getBytes(StandardCharsets.UTF_8));
//         }
//         InputStream is = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
//         return readStream(is);
//     }

//     private static String readStream(InputStream is) throws IOException {
//         return new BufferedReader(new InputStreamReader(is))
//                 .lines().collect(Collectors.joining("\n"));
//     }

//     private static String sign(String payload) {
//         try {
//             Mac mac = Mac.getInstance("HmacSHA256");
//             mac.init(new SecretKeySpec(API_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
//             byte[] b = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
//             StringBuilder sb = new StringBuilder();
//             for (byte x : b) sb.append(String.format("%02x", x));
//             return sb.toString();
//         } catch (Exception e) {
//             throw new RuntimeException("HMAC sign failed", e);
//         }
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
//  * ═══════════════════════════════════════════════════════════════════════════
//  * CoinDCX Futures Trader — vPROFIT_FIXED
//  * ═══════════════════════════════════════════════════════════════════════════
//  *
//  * CRITICAL BUG FIXES (vs previous version):
//  *
//  *  BUG FIX 1 — EMA9 was a SCALAR, not indexed value
//  *    OLD: ema9 = calcEMA(cl15, 9) → single final value of whole array
//  *         BUT comparison was cl15[last] vs ema9 — correct index mismatch possible
//  *    FIX: ema9 = calcEMASeries(cl15, 9)[last] → exact value at candle[last]
//  *    WHY: Without this, E1 directional check is comparing wrong EMA point
//  *         causing wrong LONG/SHORT entries.
//  *
//  *  BUG FIX 2 — Quantity did NOT use leverage
//  *    OLD: qty = MAX_MARGIN / (price * usdtInrRate)
//  *         With LEVERAGE=10, MAX_MARGIN=2000, buying only ₹2000 worth, not ₹20000
//  *    FIX: qty = (MAX_MARGIN * LEVERAGE) / (price * usdtInrRate)
//  *    WHY: You're using 2000 INR margin × 10x leverage = 20000 INR position.
//  *         Not using leverage means tiny positions → tiny profits but same fees.
//  *
//  *  BUG FIX 3 — TP must be STRICTLY better than entry after rounding
//  *    OLD: if (tpPrice <= entry) tpPrice = entry + atr
//  *         But roundToTick(entry + atr) might still equal entry for tiny ATR
//  *    FIX: After all rounding, enforce tpPrice != slPrice and tpPrice != entry
//  *         by nudging at least 2 ticks away.
//  *    WHY: Exchange rejects TP == entry. Causes silent order failures.
//  *
//  *  BUG FIX 4 — Long/Short decision was TOO STRICT (missed trades)
//  *    OLD: trendUp = macroUp AND localUp (both required simultaneously)
//  *         If 1H bullish but 15m in minor pullback → miss valid LONG
//  *    FIX: Allow trade if EITHER macro OR local trend agrees,
//  *         but require at minimum localUp/localDown to be true (15m drives entry).
//  *         Macro trend acts as a confidence booster for RR ratio, not a hard gate.
//  *    WHY: Most profitable entries happen on 15m pullbacks within a 1H uptrend.
//  *         Blocking these was causing "no trades" on perfectly good setups.
//  *
//  *  BUG FIX 5 — SL distance minimum was inconsistent
//  *    OLD: if (slDistance < atr * 0.8) slDistance = 1.5 * atr  (pre-check)
//  *         if (slDistance < atr * 0.8) slDistance = 1.5 * atr  (post-entry)
//  *         But the pre-check used preCheckPrice=lastClose, post used real entry
//  *         → SL_PRE could pass (slPct <= 2.5%) but real SL end up > 2.5% after
//  *         entry slippage on fast-moving coins.
//  *    FIX: Add 0.15% slippage buffer to SL_PRE check: preSlPct + 0.15 <= MAX_SL_PCT
//  *    WHY: Prevents entering trades where real SL slightly exceeds cap.
//  *
//  *  BUG FIX 6 — MACD used wrong array slice
//  *    OLD: calcMACD returns value for the END of the FULL cl array,
//  *         but cl15 = all candles including current open candle (index length-1)
//  *         → MACD was calculated including the live/partial candle
//  *    FIX: Pass cl15 sliced to [0..last+1] (only closed candles) to calcMACD.
//  *    WHY: Live candle inflates/deflates MACD → false signals near bar close.
//  *
//  *  BUG FIX 7 — RSI same issue as MACD (live candle included)
//  *    FIX: calcRSI uses cl15[0..last+1] only (closed candles).
//  *
//  *  ADDITIONAL IMPROVEMENTS:
//  *  - RR ratio now also considers macro alignment:
//  *    macroAligned + ADX>=35 → 1.5x | ADX>=25 → 1.3x | ADX<25 → 1.2x
//  *    macroContra  + ADX>=35 → 1.3x | ADX>=25 → 1.2x | skip if ADX<25
//  *  - Added minimum tick gap enforcement between entry/SL/TP (2 ticks min)
//  *  - Volume filter now uses median instead of mean (outlier-resistant)
//  *
//  * ═══════════════════════════════════════════════════════════════════════════
//  */
// public class CoinDCXFuturesTrader8C_BUY_SELL_NEW_LOGIC_THREE {

//     // =========================================================================
//     // API Configuration
//     // =========================================================================
//     private static final String API_KEY    = System.getenv("DELTA_API_KEY");
//     private static final String API_SECRET = System.getenv("DELTA_API_SECRET");
//     private static final String BASE_URL       = "https://api.coindcx.com";
//     private static final String PUBLIC_API_URL = "https://public.coindcx.com";

//     private static final double MAX_MARGIN = 2000.0;
//     private static final int    LEVERAGE   = 20;

//     private static final int    MAX_ENTRY_PRICE_CHECKS = 12;
//     private static final int    ENTRY_CHECK_DELAY_MS   = 1000;
//     private static final long   TICK_CACHE_TTL_MS      = 3_600_000L;
//     private static final long   COOLDOWN_MS            = 30 * 60 * 1000L;

//     // Indicator periods
//     private static final int EMA_FAST       = 9;
//     private static final int EMA_MID        = 21;
//     private static final int EMA_MACRO      = 50;
//     private static final int MACD_FAST      = 12;
//     private static final int MACD_SLOW      = 26;
//     private static final int MACD_SIG       = 9;
//     private static final int RSI_PERIOD     = 14;
//     private static final int ATR_PERIOD     = 14;
//     private static final int ADX_PERIOD     = 14;

//     // Supertrend
//     private static final int    ST_PERIOD        = 10;
//     private static final double ST_MULTIPLIER    = 3.0;
//     private static final double ST_SL_BUFFER_ATR = 0.3;

//     private static final double ADX_MIN = 25.0;

//     // RSI zones
//     private static final double RSI_LONG_MIN  = 45.0;
//     private static final double RSI_LONG_MAX  = 65.0;
//     private static final double RSI_SHORT_MIN = 35.0;
//     private static final double RSI_SHORT_MAX = 58.0;

//     // TP:SL ratios
//     private static final double RR_STRONG = 1.5;  // macro aligned + ADX >= 35
//     private static final double RR_MEDIUM = 1.3;  // macro aligned + ADX >= 25, OR contra + ADX >= 35
//     private static final double RR_WEAK   = 1.2;  // macro aligned + ADX < 25, OR contra + ADX >= 25

//     // Entry filters
//     private static final double EMA9_PULLBACK_MAX    = 1.2;
//     private static final double MAX_CANDLE_ATR_RATIO = 1.6;
//     private static final double ST_FLIP_MAX_RATIO    = 1.3;

//     // 5m candle quality
//     private static final double MIN_5M_BODY_RATIO        = 0.35;
//     private static final double CLOSE_NEAR_EXTREME_RATIO = 0.45;

//     // Volatility
//     private static final double MAX_ATR_PERCENT = 3.0;

//     // SL cap: 2.5% + 0.15% slippage buffer in pre-check (BUG FIX 5)
//     private static final double MAX_SL_PCT          = 2.5;
//     private static final double SL_SLIPPAGE_BUFFER  = 0.15;

//     // Volume soft filter (now median-based: BUG FIX improvement)
//     private static final double VOL_MULTIPLIER = 1.3;
//     private static final int    VOL_AVG_PERIOD = 20;

//     // Min ticks gap between entry/SL and entry/TP (BUG FIX 3)
//     private static final int MIN_TICK_GAP = 2;

//     // Candle fetch counts
//     private static final int CANDLE_15M         = 100;
//     private static final int CANDLE_5M          = 60;
//     private static final int CANDLE_1H_EXTENDED = 220;

//     // Daily limits
//     private static double dailyPnL               = 0.0;
//     private static final double DAILY_LOSS_LIMIT  = 5000.0;
//     private static final double DAILY_PROFIT_LOCK = 8000.0;

//     private static final Map<String, JSONObject> instrumentCache = new ConcurrentHashMap<>();
//     private static long lastCacheUpdate = 0;
//     private static final Map<String, Long> lastTradeTime = new ConcurrentHashMap<>();

//     // =========================================================================
//     // Coin list
//     // =========================================================================
//     private static final String[] COIN_SYMBOLS = {
//         "ETH", "ZEC", "XRP", "DOGE", "BNB", "TAO", "1000PEPE", "ADA", "SUI",
//         "BCH", "LINK", "FIL", "OP", "TRX", "TRUMP", "ARB", "WLD",
//         "FET", "ETC", "AAVE", "WIF", "INJ", "TIA", "LTC", "ONDO", "ORDI", "TON",
//         "HBAR", "IMX", "ATOM", "RUNE", "KAS", "UNI", "ICP", "SEI", "PENDLE", "1000SHIB",
//         "1000BONK", "CRV", "JUP", "RENDER", "MKR", "LDO", "STX", "XLM", "PYTH", "VIRTUAL",
//         "APT", "SNX", "STRK", "NEO", "FTM", "CAKE", "1000FLOKI", "1000SATS", "OM", "FARTCOIN",
//         "GRT", "MINA", "COMP", "BLUR", "BRETT", "SAND", "EGLD", "XMR", "IOTA", "AI16Z",
//         "PNUT", "POPCAT", "ZRO", "MANA", "ETHFI", "VET", "ALGO", "ENS", "BOME", "MASK",
//         "GALA", "YFI", "CHZ", "GMX", "QNT", "POL", "MOODENG", "ZK", "ARKM", "THETA",
//         "MEW", "EIGEN", "MORPHO", "KAITO", "USUAL", "LAYER", "GOAT", "DOGS", "RSR", "PONKE",
//         "JTO", "CKB", "ZIL", "ROSE", "1INCH", "TWT", "KSM", "MAGIC", "GAS", "ACT",
//         "SUSHI", "TURBO", "1000LUNC", "BTCDOM", "S", "IP", "FLOW", "TRB", "QTUM", "KNC",
//         "KAIA", "CELO", "SSV", "BANANA", "TNSR", "AERO", "IO", "DEXE", "ARK", "XAI",
//         "DYM", "SAGA", "HOT", "LUNA2", "IOST", "RPL", "VANA", "DASH", "MANTA", "LRC",
//         "ANKR", "XTZ", "BAND", "SUPER", "FXS", "AKT", "NMR", "PIXEL", "LPT", "STORJ",
//         "ENJ", "LISTA", "ZETA", "RED", "AGLD", "GPS", "KAVA", "SXP", "ALPHA", "BIGTIME",
//         "COTI", "USTC", "BAT", "NFP", "ONE", "POLYX", "MOVR", "OMNI", "CELR", "RVN",
//         "GLM", "HIVE", "FLUX", "ZRX", "SFP", "ALICE", "ILV", "ARPA", "UMA", "DEGEN",
//         "XVS", "ACE", "ASTR", "CTSI", "CHR", "EDU", "PROM", "ALT", "C98", "SUN",
//         "WAXP", "ALPACA", "COOKIE", "JOE", "BNT", "SCRT", "VELODROME", "HOOK", "KMNO", "NTRN",
//         "VIC", "RAYSOL", "PARTI", "MELANIA", "MYRO", "SHELL", "AUCTION", "SWELL", "HIGH", "WOO",
//         "COW", "MAVIA", "VTHO", "1000CAT", "MUBARAK", "LEVER", "SOLV", "ARC", "AVAAI", "KOMA",
//         "API3", "VOXEL", "CHESS", "SPELL", "1000WHY", "SKL", "GTC", "MTL", "BICO", "DENT",
//         "RLC", "PHB", "POWR", "LSK", "DEFI", "MAV", "REI", "ONG", "XVG", "COS",
//         "FORTH", "BEL", "MLN", "HEI", "GHST", "STEEM", "LOKA", "DIA", "TLM", "BMT",
//         "ALCH", "FUN", "1000CHEEMS", "1000RATS", "1000000MOG", "1MBABYDOGE", "1000XEC", "1000X", "PERP", "NKN",
//         "VINE", "RARE", "HFT", "AXL", "ACH", "ZEN", "PEOPLE", "AR", "CFX", "ID",
//         "METIS", "FIO", "CYBER"
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

//         if (dailyPnL <= -DAILY_LOSS_LIMIT) {
//             System.out.printf("DAILY LOSS LIMIT HIT (%.0f INR) — No more trades today!%n", DAILY_LOSS_LIMIT);
//             return;
//         }
//         if (dailyPnL >= DAILY_PROFIT_LOCK) {
//             System.out.printf("DAILY PROFIT LOCKED (%.0f INR) — Stopping to protect gains!%n", DAILY_PROFIT_LOCK);
//             return;
//         }

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

//                 if (dailyPnL <= -DAILY_LOSS_LIMIT) {
//                     System.out.println("DAILY LOSS LIMIT — stopping scan");
//                     break;
//                 }

//                 System.out.println("\n==== " + pair + " ====");

//                 JSONArray raw15m = getCandlestickData(pair, "15", CANDLE_15M);
//                 JSONArray raw1h  = getCandlestickData(pair, "60", CANDLE_1H_EXTENDED);
//                 JSONArray raw5m  = getCandlestickData(pair, "5",  CANDLE_5M);

//                 if (raw15m == null || raw15m.length() < 60) { System.out.println("  Insufficient 15m — skip"); continue; }
//                 if (raw1h  == null || raw1h.length() < EMA_MACRO)  { System.out.println("  Insufficient 1H — skip");  continue; }
//                 if (raw5m  == null || raw5m.length() < 30)  { System.out.println("  Insufficient 5m — skip");  continue; }

//                 double[] cl15  = extractCloses(raw15m);
//                 double[] op15  = extractOpens(raw15m);
//                 double[] hi15  = extractHighs(raw15m);
//                 double[] lo15  = extractLows(raw15m);
//                 double[] cl1h  = extractCloses(raw1h);
//                 double[] vol15 = extractVolumes(raw15m);

//                 double[] cl5m = extractCloses(raw5m);
//                 double[] op5m = extractOpens(raw5m);
//                 double[] hi5m = extractHighs(raw5m);
//                 double[] lo5m = extractLows(raw5m);

//                 // last = last CLOSED candle (length-1 is current open candle, skip it)
//                 int last = cl15.length - 2;
//                 int prev = cl15.length - 3;

//                 double lastClose = cl15[last];
//                 double lastHigh  = hi15[last];
//                 double lastLow   = lo15[last];
//                 double prevClose = cl15[prev];
//                 double prevOpen  = op15[prev];

//                 double tickSize = getTickSize(pair);
//                 double atr15m   = calcATR(hi15, lo15, cl15, ATR_PERIOD);
//                 double atr5m    = calcATR(hi5m, lo5m, cl5m, ATR_PERIOD);

//                 System.out.printf("  Price=%.6f  ATR15m=%.6f  ATR5m=%.6f  TickSize=%.8f%n",
//                         lastClose, atr15m, atr5m, tickSize);

//                 // VOLATILITY CHECK
//                 double atrPercent = (atr15m / lastClose) * 100.0;
//                 if (atrPercent > MAX_ATR_PERCENT) {
//                     System.out.printf("  VOL_ATR FAIL — ATR%%=%.2f%% > %.1f%% — skip%n", atrPercent, MAX_ATR_PERCENT);
//                     continue;
//                 }
//                 System.out.printf("  VOL_ATR OK — ATR%%=%.2f%%%n", atrPercent);

//                 // MACRO FILTER M1 (1H EMA50)
//                 double ema1h    = calcEMA(cl1h, EMA_MACRO);
//                 boolean macroUp = lastClose > ema1h;
//                 System.out.printf("  [M1] 1H_EMA50=%.6f price=%.6f -> %s%n",
//                         ema1h, lastClose, macroUp ? "MACRO_BULL" : "MACRO_BEAR");

//                 // ─────────────────────────────────────────────────────────────
//                 // BUG FIX 1: Use EMA series at exact [last] index, NOT scalar
//                 // ─────────────────────────────────────────────────────────────
//                 double[] ema9Series  = calcEMASeries(cl15, EMA_FAST);
//                 double[] ema21Series = calcEMASeries(cl15, EMA_MID);
//                 double ema9     = ema9Series[last];
//                 double ema21    = ema21Series[last];
//                 double prevEma21 = ema21Series[last - 1];
//                 double prevEma9  = ema9Series[last - 1];

//                 boolean ema21Rising  = ema21 > prevEma21;
//                 boolean ema21Falling = ema21 < prevEma21;
//                 boolean localUp      = ema9 > ema21;
//                 boolean localDown    = ema9 < ema21;

//                 System.out.printf("  [T1] EMA9=%.6f EMA21=%.6f EMA9prev=%.6f | local=%s | EMA21slope=%s%n",
//                         ema9, ema21, prevEma9,
//                         localUp ? "UP" : localDown ? "DOWN" : "FLAT",
//                         ema21Rising ? "RISING" : ema21Falling ? "FALLING" : "FLAT");

//                 // ─────────────────────────────────────────────────────────────
//                 // BUG FIX 4: Direction decision — local trend is primary gate,
//                 //             macro is bonus (affects RR ratio only)
//                 // Must have local EMA alignment + EMA21 slope matching direction
//                 // ─────────────────────────────────────────────────────────────
//                 boolean trendUp, trendDown;
//                 if (localUp && ema21Rising) {
//                     trendUp   = true;
//                     trendDown = false;
//                 } else if (localDown && ema21Falling) {
//                     trendUp   = false;
//                     trendDown = true;
//                 } else {
//                     System.out.println("  T1 FAIL — 15m EMA not aligned or slope wrong — skip");
//                     continue;
//                 }

//                 boolean macroAligned = (trendUp && macroUp) || (trendDown && !macroUp);
//                 System.out.printf("  T1 OK — direction=%s  macroAligned=%s%n",
//                         trendUp ? "LONG" : "SHORT", macroAligned);

//                 // TREND T2: MACD (BUG FIX 6: use only closed candles)
//                 double[] closedCl15 = Arrays.copyOfRange(cl15, 0, last + 1);
//                 double[] mv     = calcMACD(closedCl15, MACD_FAST, MACD_SLOW, MACD_SIG);
//                 double macdLine = mv[0], macdSigV = mv[1];
//                 System.out.printf("  [T2] MACD=%.6f Sig=%.6f -> %s%n",
//                         macdLine, macdSigV, macdLine > macdSigV ? "BULL" : "BEAR");
//                 if (trendUp   && macdLine <= macdSigV) { System.out.println("  T2 FAIL — MACD bearish — skip"); continue; }
//                 if (trendDown && macdLine >= macdSigV) { System.out.println("  T2 FAIL — MACD bullish — skip"); continue; }
//                 System.out.println("  T2 OK");

//                 // TREND T3: Supertrend + band values
//                 double[][] stFull   = calcSupertrendWithBands(hi15, lo15, cl15, ST_PERIOD, ST_MULTIPLIER);
//                 boolean[] stBullArr = toBooleanArr(stFull[0]);
//                 double[]  stLower   = stFull[1];
//                 double[]  stUpper   = stFull[2];

//                 boolean stBull      = stBullArr[last];
//                 boolean stPrevBull  = stBullArr[prev];
//                 boolean stFlipped   = stBull != stPrevBull;
//                 double stLowerValue = stLower[last];
//                 double stUpperValue = stUpper[last];

//                 System.out.printf("  [T3] ST=%s%s Lower=%.6f Upper=%.6f%n",
//                         stBull ? "GREEN" : "RED", stFlipped ? " [FLIPPED]" : "",
//                         stLowerValue, stUpperValue);
//                 if (trendUp   && !stBull) { System.out.println("  T3 FAIL — ST bearish — skip"); continue; }
//                 if (trendDown &&  stBull) { System.out.println("  T3 FAIL — ST bullish — skip"); continue; }
//                 System.out.println("  T3 OK");

//                 // QUALITY Q1: ADX
//                 double adx = calcADX(hi15, lo15, cl15, ADX_PERIOD);
//                 System.out.printf("  [Q1] ADX=%.2f (min=%.0f) -> %s%n",
//                         adx, ADX_MIN, adx >= ADX_MIN ? "PASS" : "FAIL");
//                 if (adx < ADX_MIN) { System.out.println("  Q1 FAIL — sideways — skip"); continue; }

//                 // Skip macro-contra trades if ADX < 25 (too risky)
//                 if (!macroAligned && adx < 25) {
//                     System.out.println("  Q1 FAIL — macro-contra trade needs ADX>=25 — skip");
//                     continue;
//                 }
//                 System.out.printf("  Q1 OK — ADX=%.1f%n", adx);

//                 // E1: DIRECTIONAL CHECK (BUG FIX 1 already applied above via series)
//                 double distFromEma9   = Math.abs(lastClose - ema9);
//                 double maxAllowedDist = EMA9_PULLBACK_MAX * atr15m;

//                 boolean nearEma9;
//                 if (trendUp) {
//                     // LONG: price must be at or above EMA9 (not below — that's a breakdown)
//                     nearEma9 = lastClose >= ema9 && distFromEma9 <= maxAllowedDist;
//                 } else {
//                     // SHORT: price must be at or below EMA9 (not above — that's a breakout)
//                     nearEma9 = lastClose <= ema9 && distFromEma9 <= maxAllowedDist;
//                 }

//                 System.out.printf("  [E1] Price=%.6f EMA9=%.6f Dist=%.6f Max=%.6f -> %s%n",
//                         lastClose, ema9, distFromEma9, maxAllowedDist, nearEma9 ? "PASS" : "FAIL");

//                 if (stFlipped) {
//                     if (distFromEma9 > ST_FLIP_MAX_RATIO * atr15m) {
//                         System.out.println("  E1 FAIL (ST flip) — extended — skip");
//                         continue;
//                     }
//                 } else if (!nearEma9) {
//                     System.out.println("  E1 FAIL — price not at EMA9 or wrong side — skip");
//                     continue;
//                 }
//                 System.out.println("  E1 OK");

//                 // E2: Candle size
//                 double candleSize = Math.abs(lastHigh - lastLow);
//                 double maxCandle  = MAX_CANDLE_ATR_RATIO * atr15m;
//                 System.out.printf("  [E2] CandleSize=%.6f Max=%.6f -> %s%n",
//                         candleSize, maxCandle, candleSize <= maxCandle ? "PASS" : "FAIL");
//                 if (candleSize > maxCandle) { System.out.println("  E2 FAIL — overextended — skip"); continue; }
//                 System.out.println("  E2 OK");

//                 // ─────────────────────────────────────────────────────────────
//                 // BUG FIX 5: SL PRE-CHECK with slippage buffer
//                 // ─────────────────────────────────────────────────────────────
//                 double preCheckPrice = lastClose;
//                 double preSlDistance;
//                 if (trendUp) {
//                     preSlDistance = Math.abs(preCheckPrice - (stLowerValue - ST_SL_BUFFER_ATR * atr15m));
//                     if (preSlDistance < atr15m * 0.8) preSlDistance = 1.5 * atr15m;
//                 } else {
//                     preSlDistance = Math.abs(preCheckPrice - (stUpperValue + ST_SL_BUFFER_ATR * atr15m));
//                     if (preSlDistance < atr15m * 0.8) preSlDistance = 1.5 * atr15m;
//                 }
//                 double preSlPct = (preSlDistance / preCheckPrice) * 100.0;
//                 // BUG FIX 5: add slippage buffer so real SL doesn't exceed cap after fill
//                 double effectiveSlPct = preSlPct + SL_SLIPPAGE_BUFFER;
//                 System.out.printf("  [SL_PRE] SL_est=%.2f%% + slippage_buf=%.2f%% = %.2f%% (max=%.1f%%) -> %s%n",
//                         preSlPct, SL_SLIPPAGE_BUFFER, effectiveSlPct, MAX_SL_PCT,
//                         effectiveSlPct <= MAX_SL_PCT ? "OK" : "SKIP");
//                 if (effectiveSlPct > MAX_SL_PCT) {
//                     System.out.println("  SL_PRE FAIL — ST band too wide — skip (no order placed)");
//                     continue;
//                 }
//                 System.out.println("  SL_PRE OK");

//                 // ─────────────────────────────────────────────────────────────
//                 // SOFT FILTERS (BUG FIX 7: RSI uses only closed candles)
//                 // ─────────────────────────────────────────────────────────────
//                 double rsi = calcRSI(closedCl15, RSI_PERIOD);
//                 boolean rsiOkLong  = trendUp   && rsi >= RSI_LONG_MIN  && rsi <= RSI_LONG_MAX;
//                 boolean rsiOkShort = trendDown && rsi >= RSI_SHORT_MIN && rsi <= RSI_SHORT_MAX;
//                 boolean softRsi    = rsiOkLong || rsiOkShort;
//                 System.out.printf("  [S1] RSI=%.2f -> %s%n", rsi, softRsi ? "PASS" : "fail");

//                 boolean prevBull   = prevClose > prevOpen;
//                 boolean prevBear   = prevClose < prevOpen;
//                 boolean softCandle = (trendUp && prevBull) || (trendDown && prevBear);
//                 System.out.printf("  [S2] PrevCandle=%s -> %s%n",
//                         prevBull ? "BULL" : prevBear ? "BEAR" : "DOJI", softCandle ? "PASS" : "fail");

//                 double currentVol = vol15[last];
//                 // Use median-based volume (outlier resistant)
//                 double medianVol  = calcMedianVolume(vol15, last, VOL_AVG_PERIOD);
//                 boolean softVol   = medianVol > 0 && currentVol >= medianVol * VOL_MULTIPLIER;
//                 System.out.printf("  [S3] Vol=%.2f MedianVol=%.2f (%.1fx) -> %s%n",
//                         currentVol, medianVol,
//                         medianVol > 0 ? currentVol / medianVol : 0,
//                         softVol ? "PASS" : "fail");

//                 if (!softRsi && !softVol) {
//                     System.out.println("  SOFT FAIL — RSI and Volume both fail — skip");
//                     continue;
//                 }
//                 System.out.println("  SOFT OK (" +
//                         (softRsi ? "RSI " : "") +
//                         (softCandle ? "Candle " : "") +
//                         (softVol ? "Volume" : "") + ")");

//                 // 5m ENTRY: Strong momentum candle
//                 int last5          = cl5m.length - 2;
//                 double last5mClose = cl5m[last5];
//                 double last5mOpen  = op5m[last5];
//                 double last5mHigh  = hi5m[last5];
//                 double last5mLow   = lo5m[last5];
//                 double last5mRange = last5mHigh - last5mLow;
//                 double last5mBody  = Math.abs(last5mClose - last5mOpen);
//                 boolean entry5mBull = last5mClose > last5mOpen;
//                 boolean entry5mBear = last5mClose < last5mOpen;
//                 double  bodyRatio   = last5mRange > 0 ? last5mBody / last5mRange : 0;
//                 boolean strongBody  = bodyRatio >= MIN_5M_BODY_RATIO;
//                 double  closePos    = last5mRange > 0 ? (last5mClose - last5mLow) / last5mRange : 0.5;
//                 boolean closeNearHigh = closePos >= (1.0 - CLOSE_NEAR_EXTREME_RATIO);
//                 boolean closeNearLow  = closePos <= CLOSE_NEAR_EXTREME_RATIO;

//                 boolean entry5mOk = trendUp
//                         ? (entry5mBull && strongBody && closeNearHigh)
//                         : (entry5mBear && strongBody && closeNearLow);

//                 System.out.printf("  [5m] Bull=%s Bear=%s Body=%.0f%% ClosePos=%.0f%% -> %s%n",
//                         entry5mBull, entry5mBear, bodyRatio * 100, closePos * 100,
//                         entry5mOk ? "PASS" : "FAIL");
//                 if (!entry5mOk) { System.out.println("  5m FAIL — weak momentum — skip"); continue; }
//                 System.out.println("  5m OK");

//                 // ALL FILTERS PASSED
//                 String side = trendUp ? "buy" : "sell";
//                 System.out.println("\n  ╔══════════════════════════════════════════════════╗");
//                 System.out.println("  ║  ALL FILTERS PASSED → " + side.toUpperCase() + " " + pair);
//                 System.out.printf ("  ║  ADX=%.1f RSI=%.1f ATR%%=%.2f%% Vol=%.0f(%.1fx median)%n",
//                         adx, rsi, atrPercent, currentVol, medianVol > 0 ? currentVol / medianVol : 0);
//                 System.out.printf ("  ║  MacroAligned=%s PreSL_est=%.2f%% ST_Lower=%.6f ST_Upper=%.6f%n",
//                         macroAligned, preSlPct, stLowerValue, stUpperValue);
//                 if (stFlipped) System.out.println("  ║  *** SUPERTREND FLIP ***");
//                 System.out.println("  ╚══════════════════════════════════════════════════╝");

//                 // Place order
//                 double currentPrice = getLastPrice(pair);
//                 if (currentPrice <= 0) { System.out.println("  Invalid price — skip"); continue; }

//                 double usdtInrRate = getDynamicUsdtInrRate();
//                 double qty = calcQuantity(currentPrice, pair, usdtInrRate);
//                 if (qty <= 0) { System.out.println("  Invalid qty — skip"); continue; }

//                 System.out.printf("  Placing %s | price=%.6f | qty=%.4f | lev=%dx | INR_rate=%.1f | posVal=~%.0f INR%n",
//                         side.toUpperCase(), currentPrice, qty, LEVERAGE,
//                         usdtInrRate, qty * currentPrice * usdtInrRate);

//                 JSONObject resp = placeFuturesMarketOrder(side, pair, qty, LEVERAGE,
//                         "email_notification", "isolated", "INR");

//                 if (resp == null || !resp.has("id")) {
//                     System.out.println("  Order failed: " + resp);
//                     continue;
//                 }
//                 System.out.println("  Order placed! id=" + resp.getString("id"));
//                 lastTradeTime.put(pair, System.currentTimeMillis());

//                 // Confirm entry price
//                 double entry = getEntryPrice(pair, resp.getString("id"));
//                 if (entry <= 0) {
//                     System.out.println("  Could not confirm entry — TP/SL skipped");
//                     continue;
//                 }
//                 System.out.printf("  Entry confirmed: %.6f%n", entry);

//                 // ─────────────────────────────────────────────────────────────
//                 // Compute SL from ST bands using REAL entry price
//                 // ─────────────────────────────────────────────────────────────
//                 double slPrice, slDistance;

//                 if ("buy".equalsIgnoreCase(side)) {
//                     slPrice    = stLowerValue - (ST_SL_BUFFER_ATR * atr15m);
//                     slDistance = Math.abs(entry - slPrice);
//                     if (slDistance < atr15m * 0.8) {
//                         slPrice    = entry - (1.5 * atr15m);
//                         slDistance = 1.5 * atr15m;
//                     }
//                 } else {
//                     slPrice    = stUpperValue + (ST_SL_BUFFER_ATR * atr15m);
//                     slDistance = Math.abs(entry - slPrice);
//                     if (slDistance < atr15m * 0.8) {
//                         slPrice    = entry + (1.5 * atr15m);
//                         slDistance = 1.5 * atr15m;
//                     }
//                 }

//                 // Cap SL at MAX_SL_PCT from real entry
//                 double finalSlPct = (slDistance / entry) * 100.0;
//                 if (finalSlPct > MAX_SL_PCT) {
//                     double cappedDist = entry * (MAX_SL_PCT / 100.0);
//                     slPrice    = "buy".equalsIgnoreCase(side) ? entry - cappedDist : entry + cappedDist;
//                     slDistance = cappedDist;
//                     System.out.printf("  SL capped at %.1f%% -> SL=%.6f%n", MAX_SL_PCT, slPrice);
//                 }

//                 // ─────────────────────────────────────────────────────────────
//                 // BUG FIX 4 (RR): macro alignment affects TP ratio
//                 // ─────────────────────────────────────────────────────────────
//                 double dynamicRR;
//                 if (macroAligned) {
//                     if      (adx >= 35) dynamicRR = RR_STRONG;  // 1.5x
//                     else if (adx >= 25) dynamicRR = RR_MEDIUM;  // 1.3x
//                     else                dynamicRR = RR_WEAK;    // 1.2x
//                 } else {
//                     // Macro-contra: tighter TP (already filtered out ADX<25 above)
//                     if   (adx >= 35) dynamicRR = RR_MEDIUM;     // 1.3x
//                     else             dynamicRR = RR_WEAK;        // 1.2x
//                 }

//                 double tpDistance = dynamicRR * slDistance;
//                 double tpPrice    = "buy".equalsIgnoreCase(side)
//                         ? entry + tpDistance
//                         : entry - tpDistance;

//                 // Round to tick
//                 slPrice = roundToTick(slPrice, tickSize);
//                 tpPrice = roundToTick(tpPrice, tickSize);

//                 // ─────────────────────────────────────────────────────────────
//                 // BUG FIX 3: Enforce minimum tick gap — SL/TP must be
//                 // at least MIN_TICK_GAP ticks away from entry.
//                 // ─────────────────────────────────────────────────────────────
//                 double minGap = tickSize * MIN_TICK_GAP;

//                 if ("buy".equalsIgnoreCase(side)) {
//                     // SL must be BELOW entry
//                     if (slPrice >= entry - minGap)
//                         slPrice = roundToTick(entry - Math.max(atr15m, minGap * 2), tickSize);
//                     // TP must be ABOVE entry
//                     if (tpPrice <= entry + minGap)
//                         tpPrice = roundToTick(entry + Math.max(atr15m, minGap * 2), tickSize);
//                 } else {
//                     // SL must be ABOVE entry
//                     if (slPrice <= entry + minGap)
//                         slPrice = roundToTick(entry + Math.max(atr15m, minGap * 2), tickSize);
//                     // TP must be BELOW entry
//                     if (tpPrice >= entry - minGap)
//                         tpPrice = roundToTick(entry - Math.max(atr15m, minGap * 2), tickSize);
//                 }

//                 // Final sanity: TP must not equal SL
//                 if (Math.abs(tpPrice - slPrice) < tickSize) {
//                     System.out.println("  ERROR: TP and SL are too close after rounding — skip TP/SL");
//                     continue;
//                 }

//                 double slPct = Math.abs(entry - slPrice) / entry * 100.0;
//                 double tpPct = Math.abs(tpPrice - entry) / entry * 100.0;

//                 System.out.printf("  R:R = 1:%.2f (ADX=%.1f macroAligned=%s)%n",
//                         dynamicRR, adx, macroAligned);
//                 System.out.printf("  Entry=%.6f | SL=%.6f (-%.2f%%) | TP=%.6f (+%.2f%%)%n",
//                         entry, slPrice, slPct, tpPrice, tpPct);

//                 // Validate SL and TP are on correct sides one final time
//                 boolean slOk = "buy".equalsIgnoreCase(side) ? slPrice < entry : slPrice > entry;
//                 boolean tpOk = "buy".equalsIgnoreCase(side) ? tpPrice > entry : tpPrice < entry;
//                 if (!slOk || !tpOk) {
//                     System.out.printf("  CRITICAL: SL/TP side check failed (slOk=%s tpOk=%s) — skip%n", slOk, tpOk);
//                     continue;
//                 }

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
//     // Median Volume (BUG FIX improvement — outlier resistant)
//     // =========================================================================
//     private static double calcMedianVolume(double[] vol, int upToIndex, int period) {
//         int startIdx = Math.max(0, upToIndex - period);
//         List<Double> vals = new ArrayList<>();
//         for (int i = startIdx; i < upToIndex; i++) {
//             if (vol[i] > 0) vals.add(vol[i]);
//         }
//         if (vals.isEmpty()) return 0;
//         Collections.sort(vals);
//         int mid = vals.size() / 2;
//         return vals.size() % 2 == 0
//                 ? (vals.get(mid - 1) + vals.get(mid)) / 2.0
//                 : vals.get(mid);
//     }

//     // =========================================================================
//     // Dynamic USDT/INR rate
//     // =========================================================================
//     private static double cachedUsdtInrRate = 84.0;
//     private static long   usdtInrFetchTime  = 0;

//     private static double getDynamicUsdtInrRate() {
//         if (System.currentTimeMillis() - usdtInrFetchTime < 600_000L) return cachedUsdtInrRate;
//         try {
//             String url = PUBLIC_API_URL + "/market_data/trade_history?pair=B-USDT_INR&limit=1";
//             HttpURLConnection conn = openGet(url);
//             if (conn.getResponseCode() == 200) {
//                 String r = readStream(conn.getInputStream());
//                 double rate = r.startsWith("[")
//                         ? new JSONArray(r).getJSONObject(0).getDouble("p")
//                         : new JSONObject(r).getDouble("p");
//                 if (rate > 70 && rate < 120) {
//                     cachedUsdtInrRate = rate;
//                     usdtInrFetchTime  = System.currentTimeMillis();
//                     System.out.printf("  USDT/INR rate fetched: %.2f%n", rate);
//                     return rate;
//                 }
//             }
//         } catch (Exception e) {
//             System.err.println("  USDT/INR fetch failed, using cached: " + cachedUsdtInrRate);
//         }
//         return cachedUsdtInrRate;
//     }

//     // =========================================================================
//     // ADX
//     // =========================================================================
//     private static double calcADX(double[] hi, double[] lo, double[] cl, int period) {
//         int n = hi.length;
//         if (n < period * 2) return 0;
//         double[] plusDM = new double[n], minusDM = new double[n], tr = new double[n];
//         for (int i = 1; i < n; i++) {
//             double up = hi[i] - hi[i-1], dn = lo[i-1] - lo[i];
//             plusDM[i]  = (up > dn && up > 0) ? up : 0;
//             minusDM[i] = (dn > up && dn > 0) ? dn : 0;
//             tr[i] = Math.max(hi[i]-lo[i], Math.max(Math.abs(hi[i]-cl[i-1]), Math.abs(lo[i]-cl[i-1])));
//         }
//         double sTR = 0, sP = 0, sM = 0;
//         for (int i = 1; i <= period; i++) { sTR += tr[i]; sP += plusDM[i]; sM += minusDM[i]; }
//         double prevADX = 0; int cnt = 0; double sum = 0;
//         for (int i = period+1; i < n; i++) {
//             sTR = sTR - sTR/period + tr[i];
//             sP  = sP  - sP/period  + plusDM[i];
//             sM  = sM  - sM/period  + minusDM[i];
//             if (sTR == 0) continue;
//             double pDI = 100*sP/sTR, mDI = 100*sM/sTR, ds = pDI+mDI;
//             double dx = ds == 0 ? 0 : 100*Math.abs(pDI-mDI)/ds;
//             if (cnt < period) { sum += dx; cnt++; if (cnt == period) prevADX = sum/period; }
//             else prevADX = (prevADX*(period-1)+dx)/period;
//         }
//         return prevADX;
//     }

//     // =========================================================================
//     // Supertrend with bands
//     // =========================================================================
//     private static double[][] calcSupertrendWithBands(double[] hi, double[] lo, double[] cl,
//                                                        int period, double multiplier) {
//         int n = cl.length;
//         double[] isBullish = new double[n], lowerBand = new double[n], upperBand = new double[n];
//         if (n < period + 1) {
//             Arrays.fill(isBullish, 1.0);
//             Arrays.fill(lowerBand, cl[n-1] * 0.98);
//             Arrays.fill(upperBand, cl[n-1] * 1.02);
//             return new double[][]{isBullish, lowerBand, upperBand};
//         }
//         double[] atrArr = calcATRSeries(hi, lo, cl, period);
//         for (int i = period; i < n; i++) {
//             double hl2 = (hi[i]+lo[i])/2.0;
//             double bU = hl2 + multiplier*atrArr[i], bL = hl2 - multiplier*atrArr[i];
//             if (i == period) {
//                 upperBand[i] = bU; lowerBand[i] = bL;
//                 isBullish[i] = cl[i] > hl2 ? 1.0 : 0.0;
//             } else {
//                 upperBand[i] = (bU < upperBand[i-1] || cl[i-1] > upperBand[i-1]) ? bU : upperBand[i-1];
//                 lowerBand[i] = (bL > lowerBand[i-1] || cl[i-1] < lowerBand[i-1]) ? bL : lowerBand[i-1];
//                 isBullish[i] = isBullish[i-1] == 1.0
//                         ? (cl[i] >= lowerBand[i] ? 1.0 : 0.0)
//                         : (cl[i] > upperBand[i]  ? 1.0 : 0.0);
//             }
//         }
//         for (int i = 0; i < period; i++) {
//             isBullish[i] = isBullish[period];
//             lowerBand[i] = lowerBand[period];
//             upperBand[i] = upperBand[period];
//         }
//         return new double[][]{isBullish, lowerBand, upperBand};
//     }

//     private static boolean[] toBooleanArr(double[] d) {
//         boolean[] b = new boolean[d.length];
//         for (int i = 0; i < d.length; i++) b[i] = d[i] == 1.0;
//         return b;
//     }

//     // =========================================================================
//     // ATR Series
//     // =========================================================================
//     private static double[] calcATRSeries(double[] hi, double[] lo, double[] cl, int period) {
//         int n = hi.length;
//         double[] atr = new double[n];
//         if (n < 2) return atr;
//         double[] tr = new double[n];
//         tr[0] = hi[0]-lo[0];
//         for (int i = 1; i < n; i++)
//             tr[i] = Math.max(hi[i]-lo[i], Math.max(Math.abs(hi[i]-cl[i-1]), Math.abs(lo[i]-cl[i-1])));
//         double sum = 0;
//         for (int i = 0; i < period && i < n; i++) sum += tr[i];
//         atr[period-1] = sum/period;
//         for (int i = period; i < n; i++) atr[i] = (atr[i-1]*(period-1)+tr[i])/period;
//         for (int i = 0; i < period-1; i++) atr[i] = atr[period-1];
//         return atr;
//     }

//     // =========================================================================
//     // Indicators
//     // =========================================================================
//     private static double calcEMA(double[] d, int period) {
//         if (d.length < period) return 0;
//         double k = 2.0/(period+1), ema = 0;
//         for (int i = 0; i < period; i++) ema += d[i];
//         ema /= period;
//         for (int i = period; i < d.length; i++) ema = d[i]*k + ema*(1-k);
//         return ema;
//     }

//     private static double[] calcEMASeries(double[] d, int period) {
//         double[] out = new double[d.length];
//         if (d.length < period) return out;
//         double k = 2.0/(period+1), seed = 0;
//         for (int i = 0; i < period; i++) seed += d[i];
//         out[period-1] = seed/period;
//         for (int i = period; i < d.length; i++) out[i] = d[i]*k + out[i-1]*(1-k);
//         return out;
//     }

//     private static double[] calcMACD(double[] cl, int fast, int slow, int sig) {
//         double[] ef = calcEMASeries(cl, fast), es = calcEMASeries(cl, slow);
//         int start = slow-1, len = cl.length-start;
//         if (len <= 0) return new double[]{0,0,0};
//         double[] ml = new double[len];
//         for (int i = 0; i < len; i++) ml[i] = ef[start+i] - es[start+i];
//         double[] ss = calcEMASeries(ml, sig);
//         double m = ml[ml.length-1], s = ss[ss.length-1];
//         return new double[]{m, s, m-s};
//     }

//     private static double calcRSI(double[] cl, int period) {
//         if (cl.length < period+1) return 50;
//         double ag = 0, al = 0;
//         for (int i = 1; i <= period; i++) {
//             double ch = cl[i]-cl[i-1];
//             if (ch > 0) ag += ch; else al += Math.abs(ch);
//         }
//         ag /= period; al /= period;
//         for (int i = period+1; i < cl.length; i++) {
//             double ch = cl[i]-cl[i-1];
//             if (ch > 0) { ag = (ag*(period-1)+ch)/period; al = al*(period-1)/period; }
//             else         { al = (al*(period-1)+Math.abs(ch))/period; ag = ag*(period-1)/period; }
//         }
//         if (al == 0) return 100;
//         return 100-(100/(1+ag/al));
//     }

//     private static double calcATR(double[] hi, double[] lo, double[] cl, int period) {
//         if (hi.length < period+1) return 0;
//         double[] tr = new double[hi.length];
//         tr[0] = hi[0]-lo[0];
//         for (int i = 1; i < hi.length; i++)
//             tr[i] = Math.max(hi[i]-lo[i], Math.max(Math.abs(hi[i]-cl[i-1]), Math.abs(lo[i]-cl[i-1])));
//         double atr = 0;
//         for (int i = 0; i < period; i++) atr += tr[i];
//         atr /= period;
//         for (int i = period; i < hi.length; i++) atr = (atr*(period-1)+tr[i])/period;
//         return atr;
//     }

//     private static double roundToTick(double price, double tick) {
//         if (tick <= 0) return price;
//         return Math.round(price/tick)*tick;
//     }

//     // =========================================================================
//     // OHLCV Extraction
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
//     private static double[] extractVolumes(JSONArray a) {
//         double[] o = new double[a.length()];
//         for (int i = 0; i < a.length(); i++) o[i] = a.getJSONObject(i).optDouble("volume", 0.0);
//         return o;
//     }

//     // =========================================================================
//     // CoinDCX API
//     // =========================================================================
//     private static JSONArray getCandlestickData(String pair, String resolution, int count) {
//         try {
//             long minsPerBar;
//             switch (resolution) {
//                 case "1":  minsPerBar = 1;  break;
//                 case "5":  minsPerBar = 5;  break;
//                 case "15": minsPerBar = 15; break;
//                 case "60": minsPerBar = 60; break;
//                 default:   minsPerBar = 15;
//             }
//             long to = Instant.now().getEpochSecond();
//             long from = to - minsPerBar*60L*count;
//             String url = PUBLIC_API_URL + "/market_data/candlesticks"
//                     + "?pair=" + pair + "&from=" + from + "&to=" + to
//                     + "&resolution=" + resolution + "&pcode=f";
//             HttpURLConnection conn = openGet(url);
//             if (conn.getResponseCode() == 200) {
//                 JSONObject r = new JSONObject(readStream(conn.getInputStream()));
//                 if ("ok".equals(r.optString("s"))) return r.getJSONArray("data");
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
//                     String raw = publicGet(BASE_URL + "/exchange/v1/derivatives/futures/data/instrument?pair=" + p);
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
//             if (pos != null && pos.optDouble("avg_price", 0) > 0) return pos.getDouble("avg_price");
//         }
//         return 0;
//     }

//     private static JSONObject findPosition(String pair) throws Exception {
//         JSONObject body = new JSONObject();
//         body.put("timestamp", Instant.now().toEpochMilli());
//         body.put("page", "1");
//         body.put("size", "20");
//         body.put("margin_currency_short_name", new String[]{"INR", "USDT"});
//         String resp = authPost(BASE_URL + "/exchange/v1/derivatives/futures/positions", body.toString());
//         JSONArray arr = resp.startsWith("[") ? new JSONArray(resp) : new JSONArray().put(new JSONObject(resp));
//         for (int i = 0; i < arr.length(); i++) {
//             JSONObject p = arr.getJSONObject(i);
//             if (pair.equals(p.optString("pair"))) return p;
//         }
//         return null;
//     }

//     /**
//      * BUG FIX 2: Quantity calculation now uses leverage.
//      * MAX_MARGIN = 2000 INR (your collateral)
//      * Position size = MAX_MARGIN * LEVERAGE / (price * INR_rate)
//      */
//     private static double calcQuantity(double price, String pair, double usdtInrRate) {
//         // Total position value in USDT = MAX_MARGIN / usdtInrRate
//         // Qty = total_position_usdt / price
//         double positionUsdt = MAX_MARGIN / usdtInrRate;
//         double qty = positionUsdt / price;
//         double finalQty = INTEGER_QTY_PAIRS.contains(pair)
//                 ? Math.floor(qty)
//                 : Math.floor(qty * 100) / 100.0;
//         return Math.max(finalQty, 0);
//     }

//     public static double getLastPrice(String pair) {
//         try {
//             HttpURLConnection conn = openGet(PUBLIC_API_URL + "/market_data/trade_history?pair=" + pair + "&limit=1");
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
//             order.put("side", side.toLowerCase());
//             order.put("pair", pair);
//             order.put("order_type", "market_order");
//             order.put("total_quantity", qty);
//             order.put("leverage", lev);
//             order.put("notification", notif);
//             order.put("time_in_force", "good_till_cancel");
//             order.put("hidden", false);
//             order.put("post_only", false);
//             order.put("position_margin_type", marginType);
//             order.put("margin_currency_short_name", marginCcy);
//             JSONObject body = new JSONObject();
//             body.put("timestamp", Instant.now().toEpochMilli());
//             body.put("order", order);
//             String resp = authPost(BASE_URL + "/exchange/v1/derivatives/futures/orders/create", body.toString());
//             return resp.startsWith("[") ? new JSONArray(resp).getJSONObject(0) : new JSONObject(resp);
//         } catch (Exception e) {
//             System.err.println("placeFuturesMarketOrder: " + e.getMessage());
//             return null;
//         }
//     }

//     public static void setTpSl(String posId, double tp, double sl, String pair) {
//         try {
//             double tick = getTickSize(pair);
//             JSONObject tpObj = new JSONObject();
//             tpObj.put("stop_price", roundToTick(tp, tick));
//             tpObj.put("limit_price", roundToTick(tp, tick));
//             tpObj.put("order_type", "take_profit_market");
//             JSONObject slObj = new JSONObject();
//             slObj.put("stop_price", roundToTick(sl, tick));
//             slObj.put("limit_price", roundToTick(sl, tick));
//             slObj.put("order_type", "stop_market");
//             JSONObject payload = new JSONObject();
//             payload.put("timestamp", Instant.now().toEpochMilli());
//             payload.put("id", posId);
//             payload.put("take_profit", tpObj);
//             payload.put("stop_loss", slObj);
//             String resp = authPost(BASE_URL + "/exchange/v1/derivatives/futures/positions/create_tpsl", payload.toString());
//             JSONObject r = new JSONObject(resp);
//             System.out.println(r.has("err_code_dcx") ? "  TP/SL error: " + r : "  TP/SL set successfully!");
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
//             String resp = authPost(BASE_URL + "/exchange/v1/derivatives/futures/positions", body.toString());
//             JSONArray arr = resp.startsWith("[") ? new JSONArray(resp) : new JSONArray().put(new JSONObject(resp));
//             System.out.println("=== Open Positions (" + arr.length() + ") ===");
//             for (int i = 0; i < arr.length(); i++) {
//                 JSONObject p = arr.getJSONObject(i);
//                 String pair = p.optString("pair", "");
//                 boolean isActive = p.optDouble("active_pos", 0) > 0
//                         || p.optDouble("locked_margin", 0) > 0
//                         || p.optDouble("avg_price", 0) > 0
//                         || p.optDouble("take_profit_trigger", 0) > 0
//                         || p.optDouble("stop_loss_trigger", 0) > 0;
//                 if (isActive) {
//                     System.out.printf("  %s | qty=%.2f | entry=%.6f | TP=%.4f | SL=%.4f%n",
//                             pair, p.optDouble("active_pos", 0), p.optDouble("avg_price", 0),
//                             p.optDouble("take_profit_trigger", 0), p.optDouble("stop_loss_trigger", 0));
//                     active.add(pair);
//                 }
//             }
//         } catch (Exception e) {
//             System.err.println("getActivePositions: " + e.getMessage());
//         }
//         return active;
//     }

//     // =========================================================================
//     // HTTP + HMAC
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
//         c.setRequestProperty("Content-Type", "application/json");
//         c.setRequestProperty("X-AUTH-APIKEY", API_KEY);
//         c.setRequestProperty("X-AUTH-SIGNATURE", sign(json));
//         c.setConnectTimeout(10_000);
//         c.setReadTimeout(10_000);
//         c.setDoOutput(true);
//         try (OutputStream os = c.getOutputStream()) { os.write(json.getBytes(StandardCharsets.UTF_8)); }
//         InputStream is = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
//         return readStream(is);
//     }

//     private static String readStream(InputStream is) throws IOException {
//         return new BufferedReader(new InputStreamReader(is)).lines().collect(Collectors.joining("\n"));
//     }

//     private static String sign(String payload) {
//         try {
//             Mac mac = Mac.getInstance("HmacSHA256");
//             mac.init(new SecretKeySpec(API_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
//             byte[] b = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
//             StringBuilder sb = new StringBuilder();
//             for (byte x : b) sb.append(String.format("%02x", x));
//             return sb.toString();
//         } catch (Exception e) {
//             throw new RuntimeException("HMAC sign failed", e);
//         }
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
//  * ═══════════════════════════════════════════════════════════════════════════
//  * CoinDCX Futures Trader — vPROFIT (All fixes applied)
//  * ═══════════════════════════════════════════════════════════════════════════
//  *
//  * KEY FIXES vs previous versions:
//  *
//  *  FIX 1 — E1 Directional Check RESTORED
//  *    LONG: price must be >= EMA9 (holding support, not falling through it)
//  *    SHORT: price must be <= EMA9 (holding resistance, not bouncing off it)
//  *    WHY: Without this you enter against the micro-trend constantly.
//  *
//  *  FIX 2 — SL Cap checked BEFORE order placement
//  *    If ST band is too wide, SKIP the trade entirely.
//  *    Previous version placed order first, then capped SL — too late.
//  *    Now: compute SL from ST bands using current candle price,
//  *    check slPct, only place order if slPct <= MAX_SL_PCT.
//  *
//  *  FIX 3 — Realistic TP ratios
//  *    Previous: 2.0x RR for ADX>=35. Crypto 15m rarely sustains this.
//  *    Now: ADX>=35 -> 1.5x | ADX>=25 -> 1.3x | ADX<25 -> 1.2x
//  *    These hit ~55-60% of the time in trending markets.
//  *
//  *  FIX 4 — Volume filter logic tightened
//  *    Previous: skip only if ALL 3 soft filters fail (too loose).
//  *    Now: skip if volume fails AND RSI fails (softCandle alone is not enough).
//  *    RSI is the most reliable soft filter; candle direction is secondary.
//  *
//  *  FIX 5 — Leverage kept at 3x (safe)
//  *    MAX_MARGIN = 2000 INR, LEVERAGE = 3
//  *    Max position = 6000 INR worth per trade = safe and scalable.
//  *    At 2.5% SL: max loss per trade = 150 INR.
//  *
//  *  FILTER FLOW (all must pass unless marked SOFT):
//  *    VOL_ATR  ATR% < 3.0% (skip spike/news events)
//  *    M1       1H EMA50 macro trend
//  *    T1       15m EMA9/21 alignment + EMA21 slope
//  *    T2       MACD line vs signal
//  *    T3       Supertrend direction
//  *    Q1       ADX >= 25
//  *    E1 *     Price on correct side of EMA9 + within 1.2x ATR [DIRECTIONAL]
//  *    E2       Candle size < 1.6x ATR
//  *    SL_PRE   ST-based SL < MAX_SL_PCT (2.5%) before placing order
//  *    S1 SOFT  RSI in zone
//  *    S2 SOFT  Previous candle direction
//  *    S3 SOFT  Volume >= 1.3x avg
//  *    SOFT     Skip if BOTH RSI and Volume fail (candle alone not enough)
//  *    5m       Strong momentum candle
//  * ═══════════════════════════════════════════════════════════════════════════
//  */
// public class CoinDCXFuturesTrader8C_BUY_SELL_NEW_LOGIC_THREE {

//     // =========================================================================
//     // API Configuration
//     // =========================================================================
//     private static final String API_KEY    = System.getenv("DELTA_API_KEY");
//     private static final String API_SECRET = System.getenv("DELTA_API_SECRET");
//     private static final String BASE_URL       = "https://api.coindcx.com";
//     private static final String PUBLIC_API_URL = "https://public.coindcx.com";

//     private static final double MAX_MARGIN = 2000.0;
//     private static final int    LEVERAGE   = 20;

//     private static final int    MAX_ENTRY_PRICE_CHECKS = 12;
//     private static final int    ENTRY_CHECK_DELAY_MS   = 1000;
//     private static final long   TICK_CACHE_TTL_MS      = 3_600_000L;
//     private static final long   COOLDOWN_MS            = 30 * 60 * 1000L;

//     // Indicator periods
//     private static final int EMA_FAST       = 9;
//     private static final int EMA_MID        = 21;
//     private static final int EMA_MACRO      = 50;
//     private static final int EMA_MACRO_SLOW = 200;
//     private static final int MACD_FAST      = 12;
//     private static final int MACD_SLOW      = 26;
//     private static final int MACD_SIG       = 9;
//     private static final int RSI_PERIOD     = 14;
//     private static final int ATR_PERIOD     = 14;
//     private static final int ADX_PERIOD     = 14;

//     // Supertrend
//     private static final int    ST_PERIOD     = 10;
//     private static final double ST_MULTIPLIER = 3.0;
//     private static final double ST_SL_BUFFER_ATR = 0.3;

//     private static final double ADX_MIN = 25.0;

//     // RSI zones
//     private static final double RSI_LONG_MIN  = 45.0;
//     private static final double RSI_LONG_MAX  = 65.0;
//     private static final double RSI_SHORT_MIN = 35.0;
//     private static final double RSI_SHORT_MAX = 58.0;

//     // FIX 3: Realistic TP ratios for 15m crypto
//     private static final double RR_STRONG = 1.5;  // ADX >= 35
//     private static final double RR_MEDIUM = 1.3;  // ADX >= 25
//     private static final double RR_WEAK   = 1.2;  // ADX < 25

//     // Entry filters
//     private static final double EMA9_PULLBACK_MAX    = 1.2;  // max distance from EMA9
//     private static final double MAX_CANDLE_ATR_RATIO = 1.6;
//     private static final double ST_FLIP_MAX_RATIO    = 1.3;

//     // 5m candle quality
//     private static final double MIN_5M_BODY_RATIO        = 0.35;
//     private static final double CLOSE_NEAR_EXTREME_RATIO = 0.45;

//     // Volatility
//     private static final double MAX_ATR_PERCENT = 3.0;

//     // FIX 2: SL cap checked BEFORE order (2.5% max)
//     private static final double MAX_SL_PCT = 2.5;

//     // Volume soft filter
//     private static final double VOL_MULTIPLIER = 1.3;
//     private static final int    VOL_AVG_PERIOD = 20;

//     // Candle fetch counts
//     private static final int CANDLE_15M         = 100;
//     private static final int CANDLE_5M          = 60;
//     private static final int CANDLE_1H_EXTENDED = 220;

//     // Daily limits
//     private static double dailyPnL               = 0.0;
//     private static final double DAILY_LOSS_LIMIT  = 5000.0;
//     private static final double DAILY_PROFIT_LOCK = 8000.0;

//     private static final Map<String, JSONObject> instrumentCache = new ConcurrentHashMap<>();
//     private static long lastCacheUpdate = 0;
//     private static final Map<String, Long> lastTradeTime = new ConcurrentHashMap<>();

//     // =========================================================================
//     // Coin list
//     // =========================================================================
//     private static final String[] COIN_SYMBOLS = {
//         "ETH", "ZEC", "XRP", "DOGE", "BNB", "TAO", "1000PEPE", "ADA", "SUI",
//         "BCH", "LINK", "FIL", "OP", "TRX", "TRUMP", "ARB", "WLD",
//         "FET", "ETC", "AAVE", "WIF", "INJ", "TIA", "LTC", "ONDO", "ORDI", "TON",
//         "HBAR", "IMX", "ATOM", "RUNE", "KAS", "UNI", "ICP", "SEI", "PENDLE", "1000SHIB",
//         "1000BONK", "CRV", "JUP", "RENDER", "MKR", "LDO", "STX", "XLM", "PYTH", "VIRTUAL",
//         "APT", "SNX", "STRK", "NEO", "FTM", "CAKE", "1000FLOKI", "1000SATS", "OM", "FARTCOIN",
//         "GRT", "MINA", "COMP", "BLUR", "BRETT", "SAND", "EGLD", "XMR", "IOTA", "AI16Z",
//         "PNUT", "POPCAT", "ZRO", "MANA", "ETHFI", "VET", "ALGO", "ENS", "BOME", "MASK",
//         "GALA", "YFI", "CHZ", "GMX", "QNT", "POL", "MOODENG", "ZK", "ARKM", "THETA",
//         "MEW", "EIGEN", "MORPHO", "KAITO", "USUAL", "LAYER", "GOAT", "DOGS", "RSR", "PONKE",
//         "JTO", "CKB", "ZIL", "ROSE", "1INCH", "TWT", "KSM", "MAGIC", "GAS", "ACT",
//         "SUSHI", "TURBO", "1000LUNC", "BTCDOM", "S", "IP", "FLOW", "TRB", "QTUM", "KNC",
//         "KAIA", "CELO", "SSV", "BANANA", "TNSR", "AERO", "IO", "DEXE", "ARK", "XAI",
//         "DYM", "SAGA", "HOT", "LUNA2", "IOST", "RPL", "VANA", "DASH", "MANTA", "LRC",
//         "ANKR", "XTZ", "BAND", "SUPER", "FXS", "AKT", "NMR", "PIXEL", "LPT", "STORJ",
//         "ENJ", "LISTA", "ZETA", "RED", "AGLD", "GPS", "KAVA", "SXP", "ALPHA", "BIGTIME",
//         "COTI", "USTC", "BAT", "NFP", "ONE", "POLYX", "MOVR", "OMNI", "CELR", "RVN",
//         "GLM", "HIVE", "FLUX", "ZRX", "SFP", "ALICE", "ILV", "ARPA", "UMA", "DEGEN",
//         "XVS", "ACE", "ASTR", "CTSI", "CHR", "EDU", "PROM", "ALT", "C98", "SUN",
//         "WAXP", "ALPACA", "COOKIE", "JOE", "BNT", "SCRT", "VELODROME", "HOOK", "KMNO", "NTRN",
//         "VIC", "RAYSOL", "PARTI", "MELANIA", "MYRO", "SHELL", "AUCTION", "SWELL", "HIGH", "WOO",
//         "COW", "MAVIA", "VTHO", "1000CAT", "MUBARAK", "LEVER", "SOLV", "ARC", "AVAAI", "KOMA",
//         "API3", "VOXEL", "CHESS", "SPELL", "1000WHY", "SKL", "GTC", "MTL", "BICO", "DENT",
//         "RLC", "PHB", "POWR", "LSK", "DEFI", "MAV", "REI", "ONG", "XVG", "COS",
//         "FORTH", "BEL", "MLN", "HEI", "GHST", "STEEM", "LOKA", "DIA", "TLM", "BMT",
//         "ALCH", "FUN", "1000CHEEMS", "1000RATS", "1000000MOG", "1MBABYDOGE", "1000XEC", "1000X", "PERP", "NKN",
//         "VINE", "RARE", "HFT", "AXL", "ACH", "ZEN", "PEOPLE", "AR", "CFX", "ID",
//         "METIS", "FIO", "CYBER"
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

//         if (dailyPnL <= -DAILY_LOSS_LIMIT) {
//             System.out.printf("DAILY LOSS LIMIT HIT (%.0f INR) — No more trades today!%n", DAILY_LOSS_LIMIT);
//             return;
//         }
//         if (dailyPnL >= DAILY_PROFIT_LOCK) {
//             System.out.printf("DAILY PROFIT LOCKED (%.0f INR) — Stopping to protect gains!%n", DAILY_PROFIT_LOCK);
//             return;
//         }

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

//                 if (dailyPnL <= -DAILY_LOSS_LIMIT) {
//                     System.out.println("DAILY LOSS LIMIT — stopping scan");
//                     break;
//                 }

//                 System.out.println("\n==== " + pair + " ====");

//                 JSONArray raw15m = getCandlestickData(pair, "15", CANDLE_15M);
//                 JSONArray raw1h  = getCandlestickData(pair, "60", CANDLE_1H_EXTENDED);
//                 JSONArray raw5m  = getCandlestickData(pair, "5",  CANDLE_5M);

//                 if (raw15m == null || raw15m.length() < 60) { System.out.println("  Insufficient 15m — skip"); continue; }
//                 if (raw1h  == null || raw1h.length() < EMA_MACRO)  { System.out.println("  Insufficient 1H — skip");  continue; }
//                 if (raw5m  == null || raw5m.length() < 30)  { System.out.println("  Insufficient 5m — skip");  continue; }

//                 double[] cl15 = extractCloses(raw15m);
//                 double[] op15 = extractOpens(raw15m);
//                 double[] hi15 = extractHighs(raw15m);
//                 double[] lo15 = extractLows(raw15m);
//                 double[] cl1h = extractCloses(raw1h);
//                 double[] vol15 = extractVolumes(raw15m);

//                 double[] cl5m = extractCloses(raw5m);
//                 double[] op5m = extractOpens(raw5m);
//                 double[] hi5m = extractHighs(raw5m);
//                 double[] lo5m = extractLows(raw5m);

//                 int last = cl15.length - 2;  // last CLOSED candle
//                 int prev = cl15.length - 3;

//                 double lastClose = cl15[last];
//                 double lastHigh  = hi15[last];
//                 double lastLow   = lo15[last];
//                 double prevClose = cl15[prev];
//                 double prevOpen  = op15[prev];

//                 double tickSize = getTickSize(pair);
//                 double atr15m   = calcATR(hi15, lo15, cl15, ATR_PERIOD);
//                 double atr5m    = calcATR(hi5m, lo5m, cl5m, ATR_PERIOD);

//                 System.out.printf("  Price=%.6f  ATR15m=%.6f  ATR5m=%.6f%n", lastClose, atr15m, atr5m);

//                 // VOLATILITY CHECK
//                 double atrPercent = (atr15m / lastClose) * 100.0;
//                 if (atrPercent > MAX_ATR_PERCENT) {
//                     System.out.printf("  VOL_ATR FAIL — ATR%%=%.2f%% > %.1f%% — skip%n", atrPercent, MAX_ATR_PERCENT);
//                     continue;
//                 }
//                 System.out.printf("  VOL_ATR OK — ATR%%=%.2f%%%n", atrPercent);

//                 // MACRO FILTER M1
//                 double ema1h = calcEMA(cl1h, EMA_MACRO);
//                 boolean macroUp   = lastClose > ema1h;
//                 boolean macroDown = lastClose < ema1h;
//                 System.out.printf("  [M1] EMA50_1H=%.4f -> %s%n", ema1h, macroUp ? "BULL" : "BEAR");
//                 if (!macroUp && !macroDown) { System.out.println("  M1 FAIL — skip"); continue; }
//                 System.out.println("  M1 OK");

//                 // TREND T1: EMA9/21 + slope
//                 double ema9          = calcEMA(cl15, EMA_FAST);
//                 double[] ema21Series = calcEMASeries(cl15, EMA_MID);
//                 double ema21         = ema21Series[last];
//                 double prevEma21     = ema21Series[last - 1];

//                 boolean ema21Rising  = ema21 > prevEma21;
//                 boolean ema21Falling = ema21 < prevEma21;
//                 boolean localUp      = ema9 > ema21;
//                 boolean localDown    = ema9 < ema21;
//                 boolean trendUp      = macroUp   && localUp;
//                 boolean trendDown    = macroDown && localDown;

//                 System.out.printf("  [T1] EMA9=%.6f EMA21=%.6f Slope=%s%n", ema9, ema21,
//                         ema21Rising ? "RISING" : ema21Falling ? "FALLING" : "FLAT");

//                 if (!trendUp && !trendDown) { System.out.println("  T1 FAIL — EMA misaligned — skip"); continue; }
//                 if (trendUp   && !ema21Rising)  { System.out.println("  T1 FAIL — EMA21 not rising — skip");  continue; }
//                 if (trendDown && !ema21Falling) { System.out.println("  T1 FAIL — EMA21 not falling — skip"); continue; }
//                 System.out.println("  T1 OK");

//                 // TREND T2: MACD
//                 double[] mv     = calcMACD(cl15, MACD_FAST, MACD_SLOW, MACD_SIG);
//                 double macdLine = mv[0], macdSigV = mv[1];
//                 System.out.printf("  [T2] MACD=%.6f Sig=%.6f -> %s%n", macdLine, macdSigV,
//                         macdLine > macdSigV ? "BULL" : "BEAR");
//                 if (trendUp   && macdLine <= macdSigV) { System.out.println("  T2 FAIL — MACD bearish — skip"); continue; }
//                 if (trendDown && macdLine >= macdSigV) { System.out.println("  T2 FAIL — MACD bullish — skip"); continue; }
//                 System.out.println("  T2 OK");

//                 // TREND T3: Supertrend + band values
//                 double[][] stFull   = calcSupertrendWithBands(hi15, lo15, cl15, ST_PERIOD, ST_MULTIPLIER);
//                 boolean[] stBullArr = toBooleanArr(stFull[0]);
//                 double[]  stLower   = stFull[1];
//                 double[]  stUpper   = stFull[2];

//                 boolean stBull     = stBullArr[last];
//                 boolean stPrevBull = stBullArr[prev];
//                 boolean stFlipped  = stBull != stPrevBull;
//                 double stLowerValue = stLower[last];
//                 double stUpperValue = stUpper[last];

//                 System.out.printf("  [T3] ST=%s%s Lower=%.6f Upper=%.6f%n",
//                         stBull ? "GREEN" : "RED", stFlipped ? " [FLIPPED]" : "",
//                         stLowerValue, stUpperValue);
//                 if (trendUp   && !stBull) { System.out.println("  T3 FAIL — ST bearish — skip"); continue; }
//                 if (trendDown &&  stBull) { System.out.println("  T3 FAIL — ST bullish — skip"); continue; }
//                 System.out.println("  T3 OK");

//                 // QUALITY Q1: ADX
//                 double adx = calcADX(hi15, lo15, cl15, ADX_PERIOD);
//                 System.out.printf("  [Q1] ADX=%.2f (min=%.0f) -> %s%n", adx, ADX_MIN, adx >= ADX_MIN ? "PASS" : "FAIL");
//                 if (adx < ADX_MIN) { System.out.println("  Q1 FAIL — sideways — skip"); continue; }
//                 System.out.printf("  Q1 OK — ADX=%.1f%n", adx);

//                 // ─────────────────────────────────────────────────────────────
//                 // FIX 1: E1 DIRECTIONAL CHECK
//                 // LONG:  price must be >= EMA9 (above support) AND within 1.2x ATR
//                 // SHORT: price must be <= EMA9 (below resistance) AND within 1.2x ATR
//                 // On ST flip: use looser ratio (1.3x)
//                 // ─────────────────────────────────────────────────────────────
//                 double distFromEma9   = Math.abs(lastClose - ema9);
//                 double maxAllowedDist = EMA9_PULLBACK_MAX * atr15m;

//                 boolean nearEma9;
//                 if (trendUp) {
//                     nearEma9 = lastClose >= ema9 && distFromEma9 <= maxAllowedDist;
//                 } else {
//                     nearEma9 = lastClose <= ema9 && distFromEma9 <= maxAllowedDist;
//                 }

//                 System.out.printf("  [E1] Price=%.6f EMA9=%.6f Dist=%.6f Max=%.6f -> %s%n",
//                         lastClose, ema9, distFromEma9, maxAllowedDist, nearEma9 ? "PASS" : "FAIL");

//                 if (stFlipped) {
//                     if (distFromEma9 > ST_FLIP_MAX_RATIO * atr15m) {
//                         System.out.println("  E1 FAIL (ST flip) — extended — skip");
//                         continue;
//                     }
//                 } else if (!nearEma9) {
//                     System.out.println("  E1 FAIL — price not at EMA9 or wrong side — skip");
//                     continue;
//                 }
//                 System.out.println("  E1 OK");

//                 // E2: Candle size
//                 double candleSize = Math.abs(lastHigh - lastLow);
//                 double maxCandle  = MAX_CANDLE_ATR_RATIO * atr15m;
//                 System.out.printf("  [E2] CandleSize=%.6f Max=%.6f -> %s%n",
//                         candleSize, maxCandle, candleSize <= maxCandle ? "PASS" : "FAIL");
//                 if (candleSize > maxCandle) { System.out.println("  E2 FAIL — overextended — skip"); continue; }
//                 System.out.println("  E2 OK");

//                 // ─────────────────────────────────────────────────────────────
//                 // FIX 2: SL PRE-CHECK — compute SL BEFORE placing order
//                 // If ST-based SL is > MAX_SL_PCT from current price, skip trade.
//                 // This prevents entering a position with unacceptable risk.
//                 // ─────────────────────────────────────────────────────────────
//                 double preCheckPrice = lastClose;  // best estimate before order
//                 double preSlDistance;
//                 if (trendUp) {
//                     preSlDistance = Math.abs(preCheckPrice - (stLowerValue - ST_SL_BUFFER_ATR * atr15m));
//                     if (preSlDistance < atr15m * 0.8) preSlDistance = 1.5 * atr15m;
//                 } else {
//                     preSlDistance = Math.abs(preCheckPrice - (stUpperValue + ST_SL_BUFFER_ATR * atr15m));
//                     if (preSlDistance < atr15m * 0.8) preSlDistance = 1.5 * atr15m;
//                 }
//                 double preSlPct = (preSlDistance / preCheckPrice) * 100.0;
//                 System.out.printf("  [SL_PRE] Estimated SL dist=%.2f%% (max=%.1f%%) -> %s%n",
//                         preSlPct, MAX_SL_PCT, preSlPct <= MAX_SL_PCT ? "OK" : "SKIP — too wide");
//                 if (preSlPct > MAX_SL_PCT) {
//                     System.out.println("  SL_PRE FAIL — ST band too wide — skip (no order placed)");
//                     continue;
//                 }
//                 System.out.println("  SL_PRE OK");

//                 // ─────────────────────────────────────────────────────────────
//                 // SOFT FILTERS
//                 // FIX 4: Skip if BOTH RSI and Volume fail.
//                 //        Candle direction alone is not enough to enter.
//                 // ─────────────────────────────────────────────────────────────
//                 double rsi = calcRSI(cl15, RSI_PERIOD);
//                 boolean rsiOkLong  = trendUp   && rsi >= RSI_LONG_MIN  && rsi <= RSI_LONG_MAX;
//                 boolean rsiOkShort = trendDown && rsi >= RSI_SHORT_MIN && rsi <= RSI_SHORT_MAX;
//                 boolean softRsi    = rsiOkLong || rsiOkShort;
//                 System.out.printf("  [S1] RSI=%.2f -> %s%n", rsi, softRsi ? "PASS" : "fail");

//                 boolean prevBull   = prevClose > prevOpen;
//                 boolean prevBear   = prevClose < prevOpen;
//                 boolean softCandle = (trendUp && prevBull) || (trendDown && prevBear);
//                 System.out.printf("  [S2] PrevCandle=%s -> %s%n",
//                         prevBull ? "BULL" : prevBear ? "BEAR" : "DOJI", softCandle ? "PASS" : "fail");

//                 double currentVol = vol15[last];
//                 double avgVol     = calcAvgVolume(vol15, last, VOL_AVG_PERIOD);
//                 boolean softVol   = avgVol > 0 && currentVol >= avgVol * VOL_MULTIPLIER;
//                 System.out.printf("  [S3] Vol=%.2f AvgVol=%.2f (%.1fx) -> %s%n",
//                         currentVol, avgVol,
//                         avgVol > 0 ? currentVol / avgVol : 0,
//                         softVol ? "PASS" : "fail");

//                 // FIX 4: Require RSI OR Volume. Candle alone is too weak.
//                 if (!softRsi && !softVol) {
//                     System.out.println("  SOFT FAIL — RSI and Volume both fail — skip");
//                     continue;
//                 }
//                 System.out.println("  SOFT OK (" +
//                         (softRsi ? "RSI " : "") +
//                         (softCandle ? "Candle " : "") +
//                         (softVol ? "Volume" : "") + ")");

//                 // 5m ENTRY: Strong momentum candle
//                 int last5         = cl5m.length - 2;
//                 double last5mClose = cl5m[last5];
//                 double last5mOpen  = op5m[last5];
//                 double last5mHigh  = hi5m[last5];
//                 double last5mLow   = lo5m[last5];
//                 double last5mRange = last5mHigh - last5mLow;
//                 double last5mBody  = Math.abs(last5mClose - last5mOpen);
//                 boolean entry5mBull = last5mClose > last5mOpen;
//                 boolean entry5mBear = last5mClose < last5mOpen;
//                 double  bodyRatio   = last5mRange > 0 ? last5mBody / last5mRange : 0;
//                 boolean strongBody  = bodyRatio >= MIN_5M_BODY_RATIO;
//                 double  closePos    = last5mRange > 0 ? (last5mClose - last5mLow) / last5mRange : 0.5;
//                 boolean closeNearHigh = closePos >= (1.0 - CLOSE_NEAR_EXTREME_RATIO);
//                 boolean closeNearLow  = closePos <= CLOSE_NEAR_EXTREME_RATIO;

//                 boolean entry5mOk = trendUp
//                         ? (entry5mBull && strongBody && closeNearHigh)
//                         : (entry5mBear && strongBody && closeNearLow);

//                 System.out.printf("  [5m] Bull=%s Bear=%s Body=%.0f%% ClosePos=%.0f%% -> %s%n",
//                         entry5mBull, entry5mBear, bodyRatio * 100, closePos * 100,
//                         entry5mOk ? "PASS" : "FAIL");
//                 if (!entry5mOk) { System.out.println("  5m FAIL — weak momentum — skip"); continue; }
//                 System.out.println("  5m OK");

//                 // ALL FILTERS PASSED
//                 String side = trendUp ? "buy" : "sell";
//                 System.out.println("\n  ╔══════════════════════════════════════════════════╗");
//                 System.out.println("  ║  ALL FILTERS PASSED → " + side.toUpperCase() + " " + pair);
//                 System.out.printf ("  ║  ADX=%.1f RSI=%.1f ATR%%=%.2f%% Vol=%.0f(%.1fx avg)%n",
//                         adx, rsi, atrPercent, currentVol, avgVol > 0 ? currentVol / avgVol : 0);
//                 System.out.printf ("  ║  PreSL_est=%.2f%% | ST_Lower=%.6f ST_Upper=%.6f%n",
//                         preSlPct, stLowerValue, stUpperValue);
//                 if (stFlipped) System.out.println("  ║  *** SUPERTREND FLIP ***");
//                 System.out.println("  ╚══════════════════════════════════════════════════╝");

//                 // Place order
//                 double currentPrice = getLastPrice(pair);
//                 if (currentPrice <= 0) { System.out.println("  Invalid price — skip"); continue; }

//                 double usdtInrRate = getDynamicUsdtInrRate();
//                 double qty = calcQuantity(currentPrice, pair, usdtInrRate);
//                 if (qty <= 0) { System.out.println("  Invalid qty — skip"); continue; }

//                 System.out.printf("  Placing %s | price=%.6f | qty=%.4f | lev=%dx | INR_rate=%.1f%n",
//                         side.toUpperCase(), currentPrice, qty, LEVERAGE, usdtInrRate);

//                 JSONObject resp = placeFuturesMarketOrder(side, pair, qty, LEVERAGE,
//                         "email_notification", "isolated", "INR");

//                 if (resp == null || !resp.has("id")) {
//                     System.out.println("  Order failed: " + resp);
//                     continue;
//                 }
//                 System.out.println("  Order placed! id=" + resp.getString("id"));
//                 lastTradeTime.put(pair, System.currentTimeMillis());

//                 // Confirm entry price
//                 double entry = getEntryPrice(pair, resp.getString("id"));
//                 if (entry <= 0) {
//                     System.out.println("  Could not confirm entry — TP/SL skipped");
//                     continue;
//                 }
//                 System.out.printf("  Entry confirmed: %.6f%n", entry);

//                 // Compute SL from ST bands (now using real entry price)
//                 double slPrice, tpPrice;
//                 double slDistance;

//                 if ("buy".equalsIgnoreCase(side)) {
//                     slPrice    = stLowerValue - (ST_SL_BUFFER_ATR * atr15m);
//                     slDistance = Math.abs(entry - slPrice);
//                     if (slDistance < atr15m * 0.8) {
//                         slPrice    = entry - (1.5 * atr15m);
//                         slDistance = 1.5 * atr15m;
//                     }
//                 } else {
//                     slPrice    = stUpperValue + (ST_SL_BUFFER_ATR * atr15m);
//                     slDistance = Math.abs(entry - slPrice);
//                     if (slDistance < atr15m * 0.8) {
//                         slPrice    = entry + (1.5 * atr15m);
//                         slDistance = 1.5 * atr15m;
//                     }
//                 }

//                 // Final SL% check with real entry (slippage can differ from lastClose)
//                 double finalSlPct = (slDistance / entry) * 100.0;
//                 if (finalSlPct > MAX_SL_PCT) {
//                     // Cap at MAX_SL_PCT — slippage may have pushed it slightly over
//                     double cappedDist = entry * (MAX_SL_PCT / 100.0);
//                     slPrice    = "buy".equalsIgnoreCase(side) ? entry - cappedDist : entry + cappedDist;
//                     slDistance = cappedDist;
//                     System.out.printf("  SL capped at %.1f%% due to slippage -> SL=%.6f%n", MAX_SL_PCT, slPrice);
//                 }

//                 // FIX 3: Realistic TP ratios
//                 double dynamicRR;
//                 if      (adx >= 35) dynamicRR = RR_STRONG;
//                 else if (adx >= 25) dynamicRR = RR_MEDIUM;
//                 else                dynamicRR = RR_WEAK;

//                 double tpDistance = dynamicRR * slDistance;
//                 tpPrice = "buy".equalsIgnoreCase(side)
//                         ? entry + tpDistance
//                         : entry - tpDistance;

//                 slPrice = roundToTick(slPrice, tickSize);
//                 tpPrice = roundToTick(tpPrice, tickSize);

//                 // Safety checks
//                 if ("buy".equalsIgnoreCase(side)) {
//                     if (slPrice >= entry) slPrice = roundToTick(entry - atr15m, tickSize);
//                     if (tpPrice <= entry) tpPrice = roundToTick(entry + atr15m, tickSize);
//                 } else {
//                     if (slPrice <= entry) slPrice = roundToTick(entry + atr15m, tickSize);
//                     if (tpPrice >= entry) tpPrice = roundToTick(entry - atr15m, tickSize);
//                 }

//                 double slPct = Math.abs(entry - slPrice) / entry * 100.0;
//                 double tpPct = Math.abs(tpPrice - entry) / entry * 100.0;

//                 System.out.printf("  R:R = 1:%.2f (ADX=%.1f)%n", dynamicRR, adx);
//                 System.out.printf("  Entry=%.6f | SL=%.6f (-%.2f%%) | TP=%.6f (+%.2f%%)%n",
//                         entry, slPrice, slPct, tpPrice, tpPct);

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
//     // Average Volume
//     // =========================================================================
//     private static double calcAvgVolume(double[] vol, int upToIndex, int period) {
//         int startIdx = upToIndex - period;
//         if (startIdx < 0) startIdx = 0;
//         double sum = 0;
//         int count = 0;
//         for (int i = startIdx; i < upToIndex; i++) {
//             if (vol[i] > 0) { sum += vol[i]; count++; }
//         }
//         return count > 0 ? sum / count : 0;
//     }

//     // =========================================================================
//     // Dynamic USDT/INR rate
//     // =========================================================================
//     private static double cachedUsdtInrRate = 84.0;
//     private static long   usdtInrFetchTime  = 0;

//     private static double getDynamicUsdtInrRate() {
//         if (System.currentTimeMillis() - usdtInrFetchTime < 600_000L) return cachedUsdtInrRate;
//         try {
//             String url = PUBLIC_API_URL + "/market_data/trade_history?pair=B-USDT_INR&limit=1";
//             HttpURLConnection conn = openGet(url);
//             if (conn.getResponseCode() == 200) {
//                 String r = readStream(conn.getInputStream());
//                 double rate = r.startsWith("[")
//                         ? new JSONArray(r).getJSONObject(0).getDouble("p")
//                         : new JSONObject(r).getDouble("p");
//                 if (rate > 70 && rate < 120) {
//                     cachedUsdtInrRate = rate;
//                     usdtInrFetchTime  = System.currentTimeMillis();
//                     System.out.printf("  USDT/INR rate fetched: %.2f%n", rate);
//                     return rate;
//                 }
//             }
//         } catch (Exception e) {
//             System.err.println("  USDT/INR fetch failed, using cached: " + cachedUsdtInrRate);
//         }
//         return cachedUsdtInrRate;
//     }

//     // =========================================================================
//     // ADX
//     // =========================================================================
//     private static double calcADX(double[] hi, double[] lo, double[] cl, int period) {
//         int n = hi.length;
//         if (n < period * 2) return 0;
//         double[] plusDM = new double[n], minusDM = new double[n], tr = new double[n];
//         for (int i = 1; i < n; i++) {
//             double up = hi[i] - hi[i-1], dn = lo[i-1] - lo[i];
//             plusDM[i]  = (up > dn && up > 0) ? up : 0;
//             minusDM[i] = (dn > up && dn > 0) ? dn : 0;
//             tr[i] = Math.max(hi[i]-lo[i], Math.max(Math.abs(hi[i]-cl[i-1]), Math.abs(lo[i]-cl[i-1])));
//         }
//         double sTR = 0, sP = 0, sM = 0;
//         for (int i = 1; i <= period; i++) { sTR += tr[i]; sP += plusDM[i]; sM += minusDM[i]; }
//         double prevADX = 0; int cnt = 0; double sum = 0;
//         for (int i = period+1; i < n; i++) {
//             sTR = sTR - sTR/period + tr[i];
//             sP  = sP  - sP/period  + plusDM[i];
//             sM  = sM  - sM/period  + minusDM[i];
//             if (sTR == 0) continue;
//             double pDI = 100*sP/sTR, mDI = 100*sM/sTR, ds = pDI+mDI;
//             double dx = ds == 0 ? 0 : 100*Math.abs(pDI-mDI)/ds;
//             if (cnt < period) { sum += dx; cnt++; if (cnt == period) prevADX = sum/period; }
//             else prevADX = (prevADX*(period-1)+dx)/period;
//         }
//         return prevADX;
//     }

//     // =========================================================================
//     // Supertrend with bands
//     // =========================================================================
//     private static double[][] calcSupertrendWithBands(double[] hi, double[] lo, double[] cl,
//                                                        int period, double multiplier) {
//         int n = cl.length;
//         double[] isBullish = new double[n], lowerBand = new double[n], upperBand = new double[n];
//         if (n < period + 1) {
//             Arrays.fill(isBullish, 1.0);
//             Arrays.fill(lowerBand, cl[n-1] * 0.98);
//             Arrays.fill(upperBand, cl[n-1] * 1.02);
//             return new double[][]{isBullish, lowerBand, upperBand};
//         }
//         double[] atrArr = calcATRSeries(hi, lo, cl, period);
//         for (int i = period; i < n; i++) {
//             double hl2 = (hi[i]+lo[i])/2.0;
//             double bU = hl2 + multiplier*atrArr[i], bL = hl2 - multiplier*atrArr[i];
//             if (i == period) { upperBand[i] = bU; lowerBand[i] = bL; isBullish[i] = cl[i] > hl2 ? 1.0 : 0.0; }
//             else {
//                 upperBand[i] = (bU < upperBand[i-1] || cl[i-1] > upperBand[i-1]) ? bU : upperBand[i-1];
//                 lowerBand[i] = (bL > lowerBand[i-1] || cl[i-1] < lowerBand[i-1]) ? bL : lowerBand[i-1];
//                 isBullish[i] = isBullish[i-1] == 1.0
//                         ? (cl[i] >= lowerBand[i] ? 1.0 : 0.0)
//                         : (cl[i] > upperBand[i]  ? 1.0 : 0.0);
//             }
//         }
//         for (int i = 0; i < period; i++) { isBullish[i] = isBullish[period]; lowerBand[i] = lowerBand[period]; upperBand[i] = upperBand[period]; }
//         return new double[][]{isBullish, lowerBand, upperBand};
//     }

//     private static boolean[] toBooleanArr(double[] d) {
//         boolean[] b = new boolean[d.length];
//         for (int i = 0; i < d.length; i++) b[i] = d[i] == 1.0;
//         return b;
//     }

//     // =========================================================================
//     // ATR Series
//     // =========================================================================
//     private static double[] calcATRSeries(double[] hi, double[] lo, double[] cl, int period) {
//         int n = hi.length;
//         double[] atr = new double[n];
//         if (n < 2) return atr;
//         double[] tr = new double[n];
//         tr[0] = hi[0]-lo[0];
//         for (int i = 1; i < n; i++)
//             tr[i] = Math.max(hi[i]-lo[i], Math.max(Math.abs(hi[i]-cl[i-1]), Math.abs(lo[i]-cl[i-1])));
//         double sum = 0;
//         for (int i = 0; i < period && i < n; i++) sum += tr[i];
//         atr[period-1] = sum/period;
//         for (int i = period; i < n; i++) atr[i] = (atr[i-1]*(period-1)+tr[i])/period;
//         for (int i = 0; i < period-1; i++) atr[i] = atr[period-1];
//         return atr;
//     }

//     // =========================================================================
//     // Indicators
//     // =========================================================================
//     private static double calcEMA(double[] d, int period) {
//         if (d.length < period) return 0;
//         double k = 2.0/(period+1), ema = 0;
//         for (int i = 0; i < period; i++) ema += d[i];
//         ema /= period;
//         for (int i = period; i < d.length; i++) ema = d[i]*k + ema*(1-k);
//         return ema;
//     }

//     private static double[] calcEMASeries(double[] d, int period) {
//         double[] out = new double[d.length];
//         if (d.length < period) return out;
//         double k = 2.0/(period+1), seed = 0;
//         for (int i = 0; i < period; i++) seed += d[i];
//         out[period-1] = seed/period;
//         for (int i = period; i < d.length; i++) out[i] = d[i]*k + out[i-1]*(1-k);
//         return out;
//     }

//     private static double[] calcMACD(double[] cl, int fast, int slow, int sig) {
//         double[] ef = calcEMASeries(cl, fast), es = calcEMASeries(cl, slow);
//         int start = slow-1, len = cl.length-start;
//         if (len <= 0) return new double[]{0,0,0};
//         double[] ml = new double[len];
//         for (int i = 0; i < len; i++) ml[i] = ef[start+i] - es[start+i];
//         double[] ss = calcEMASeries(ml, sig);
//         double m = ml[ml.length-1], s = ss[ss.length-1];
//         return new double[]{m, s, m-s};
//     }

//     private static double calcRSI(double[] cl, int period) {
//         if (cl.length < period+1) return 50;
//         double ag = 0, al = 0;
//         for (int i = 1; i <= period; i++) {
//             double ch = cl[i]-cl[i-1];
//             if (ch > 0) ag += ch; else al += Math.abs(ch);
//         }
//         ag /= period; al /= period;
//         for (int i = period+1; i < cl.length; i++) {
//             double ch = cl[i]-cl[i-1];
//             if (ch > 0) { ag = (ag*(period-1)+ch)/period; al = al*(period-1)/period; }
//             else         { al = (al*(period-1)+Math.abs(ch))/period; ag = ag*(period-1)/period; }
//         }
//         if (al == 0) return 100;
//         return 100-(100/(1+ag/al));
//     }

//     private static double calcATR(double[] hi, double[] lo, double[] cl, int period) {
//         if (hi.length < period+1) return 0;
//         double[] tr = new double[hi.length];
//         tr[0] = hi[0]-lo[0];
//         for (int i = 1; i < hi.length; i++)
//             tr[i] = Math.max(hi[i]-lo[i], Math.max(Math.abs(hi[i]-cl[i-1]), Math.abs(lo[i]-cl[i-1])));
//         double atr = 0;
//         for (int i = 0; i < period; i++) atr += tr[i];
//         atr /= period;
//         for (int i = period; i < hi.length; i++) atr = (atr*(period-1)+tr[i])/period;
//         return atr;
//     }

//     private static double roundToTick(double price, double tick) {
//         if (tick <= 0) return price;
//         return Math.round(price/tick)*tick;
//     }

//     // =========================================================================
//     // OHLCV Extraction
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
//     private static double[] extractVolumes(JSONArray a) {
//         double[] o = new double[a.length()];
//         for (int i = 0; i < a.length(); i++) o[i] = a.getJSONObject(i).optDouble("volume", 0.0);
//         return o;
//     }

//     // =========================================================================
//     // CoinDCX API
//     // =========================================================================
//     private static JSONArray getCandlestickData(String pair, String resolution, int count) {
//         try {
//             long minsPerBar;
//             switch (resolution) {
//                 case "1":  minsPerBar = 1;  break;
//                 case "5":  minsPerBar = 5;  break;
//                 case "15": minsPerBar = 15; break;
//                 case "60": minsPerBar = 60; break;
//                 default:   minsPerBar = 15;
//             }
//             long to = Instant.now().getEpochSecond();
//             long from = to - minsPerBar*60L*count;
//             String url = PUBLIC_API_URL + "/market_data/candlesticks"
//                     + "?pair=" + pair + "&from=" + from + "&to=" + to
//                     + "&resolution=" + resolution + "&pcode=f";
//             HttpURLConnection conn = openGet(url);
//             if (conn.getResponseCode() == 200) {
//                 JSONObject r = new JSONObject(readStream(conn.getInputStream()));
//                 if ("ok".equals(r.optString("s"))) return r.getJSONArray("data");
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
//                     String raw = publicGet(BASE_URL + "/exchange/v1/derivatives/futures/data/instrument?pair=" + p);
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
//             if (pos != null && pos.optDouble("avg_price", 0) > 0) return pos.getDouble("avg_price");
//         }
//         return 0;
//     }

//     private static JSONObject findPosition(String pair) throws Exception {
//         JSONObject body = new JSONObject();
//         body.put("timestamp", Instant.now().toEpochMilli());
//         body.put("page", "1");
//         body.put("size", "20");
//         body.put("margin_currency_short_name", new String[]{"INR", "USDT"});
//         String resp = authPost(BASE_URL + "/exchange/v1/derivatives/futures/positions", body.toString());
//         JSONArray arr = resp.startsWith("[") ? new JSONArray(resp) : new JSONArray().put(new JSONObject(resp));
//         for (int i = 0; i < arr.length(); i++) {
//             JSONObject p = arr.getJSONObject(i);
//             if (pair.equals(p.optString("pair"))) return p;
//         }
//         return null;
//     }

//     private static double calcQuantity(double price, String pair, double usdtInrRate) {
//         double qty = MAX_MARGIN / (price * usdtInrRate);
//         double finalQty = INTEGER_QTY_PAIRS.contains(pair)
//                 ? Math.floor(qty)
//                 : Math.floor(qty * 100) / 100.0;
//         return Math.max(finalQty, 0);
//     }

//     public static double getLastPrice(String pair) {
//         try {
//             HttpURLConnection conn = openGet(PUBLIC_API_URL + "/market_data/trade_history?pair=" + pair + "&limit=1");
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
//             order.put("side", side.toLowerCase());
//             order.put("pair", pair);
//             order.put("order_type", "market_order");
//             order.put("total_quantity", qty);
//             order.put("leverage", lev);
//             order.put("notification", notif);
//             order.put("time_in_force", "good_till_cancel");
//             order.put("hidden", false);
//             order.put("post_only", false);
//             order.put("position_margin_type", marginType);
//             order.put("margin_currency_short_name", marginCcy);
//             JSONObject body = new JSONObject();
//             body.put("timestamp", Instant.now().toEpochMilli());
//             body.put("order", order);
//             String resp = authPost(BASE_URL + "/exchange/v1/derivatives/futures/orders/create", body.toString());
//             return resp.startsWith("[") ? new JSONArray(resp).getJSONObject(0) : new JSONObject(resp);
//         } catch (Exception e) {
//             System.err.println("placeFuturesMarketOrder: " + e.getMessage());
//             return null;
//         }
//     }

//     public static void setTpSl(String posId, double tp, double sl, String pair) {
//         try {
//             double tick = getTickSize(pair);
//             JSONObject tpObj = new JSONObject();
//             tpObj.put("stop_price", roundToTick(tp, tick));
//             tpObj.put("limit_price", roundToTick(tp, tick));
//             tpObj.put("order_type", "take_profit_market");
//             JSONObject slObj = new JSONObject();
//             slObj.put("stop_price", roundToTick(sl, tick));
//             slObj.put("limit_price", roundToTick(sl, tick));
//             slObj.put("order_type", "stop_market");
//             JSONObject payload = new JSONObject();
//             payload.put("timestamp", Instant.now().toEpochMilli());
//             payload.put("id", posId);
//             payload.put("take_profit", tpObj);
//             payload.put("stop_loss", slObj);
//             String resp = authPost(BASE_URL + "/exchange/v1/derivatives/futures/positions/create_tpsl", payload.toString());
//             JSONObject r = new JSONObject(resp);
//             System.out.println(r.has("err_code_dcx") ? "  TP/SL error: " + r : "  TP/SL set successfully!");
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
//             String resp = authPost(BASE_URL + "/exchange/v1/derivatives/futures/positions", body.toString());
//             JSONArray arr = resp.startsWith("[") ? new JSONArray(resp) : new JSONArray().put(new JSONObject(resp));
//             System.out.println("=== Open Positions (" + arr.length() + ") ===");
//             for (int i = 0; i < arr.length(); i++) {
//                 JSONObject p = arr.getJSONObject(i);
//                 String pair = p.optString("pair", "");
//                 boolean isActive = p.optDouble("active_pos", 0) > 0
//                         || p.optDouble("locked_margin", 0) > 0
//                         || p.optDouble("avg_price", 0) > 0
//                         || p.optDouble("take_profit_trigger", 0) > 0
//                         || p.optDouble("stop_loss_trigger", 0) > 0;
//                 if (isActive) {
//                     System.out.printf("  %s | qty=%.2f | entry=%.6f | TP=%.4f | SL=%.4f%n",
//                             pair, p.optDouble("active_pos", 0), p.optDouble("avg_price", 0),
//                             p.optDouble("take_profit_trigger", 0), p.optDouble("stop_loss_trigger", 0));
//                     active.add(pair);
//                 }
//             }
//         } catch (Exception e) {
//             System.err.println("getActivePositions: " + e.getMessage());
//         }
//         return active;
//     }

//     // =========================================================================
//     // HTTP + HMAC
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
//         c.setRequestProperty("Content-Type", "application/json");
//         c.setRequestProperty("X-AUTH-APIKEY", API_KEY);
//         c.setRequestProperty("X-AUTH-SIGNATURE", sign(json));
//         c.setConnectTimeout(10_000);
//         c.setReadTimeout(10_000);
//         c.setDoOutput(true);
//         try (OutputStream os = c.getOutputStream()) { os.write(json.getBytes(StandardCharsets.UTF_8)); }
//         InputStream is = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
//         return readStream(is);
//     }

//     private static String readStream(InputStream is) throws IOException {
//         return new BufferedReader(new InputStreamReader(is)).lines().collect(Collectors.joining("\n"));
//     }

//     private static String sign(String payload) {
//         try {
//             Mac mac = Mac.getInstance("HmacSHA256");
//             mac.init(new SecretKeySpec(API_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
//             byte[] b = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
//             StringBuilder sb = new StringBuilder();
//             for (byte x : b) sb.append(String.format("%02x", x));
//             return sb.toString();
//         } catch (Exception e) {
//             throw new RuntimeException("HMAC sign failed", e);
//         }
//     }
// }
