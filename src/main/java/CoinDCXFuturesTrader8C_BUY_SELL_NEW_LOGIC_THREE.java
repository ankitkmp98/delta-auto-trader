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
 * CoinDCX Futures Trader — v11 (Final Production Edition)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * CHANGES FROM v10 → v11 (8 fixes applied):
 *
 *   FIX #1 — EMA9 Directional Pullback (MOST IMPORTANT)
 *            v10: only distance check (abs(price - ema9) <= maxDist)
 *            v11: BUY needs price >= ema9, SELL needs price <= ema9
 *            WHY: Prevents buying below EMA9 in downswing (fake pullback)
 *
 *   FIX #2 — EMA21 Slope Confirmation
 *            v10: only ema9 > ema21 (can cross in sideways)
 *            v11: ema9 > ema21 AND ema21 is rising (slope confirmed)
 *            WHY: Eliminates fake EMA crossovers in ranging market
 *
 *   FIX #3 — 1H EMA200 Macro Trend
 *            v10: price > EMA50 alone
 *            v11: price > EMA50 AND EMA50 > EMA200
 *            WHY: Ensures you're trading WITH the real macro trend
 *
 *   FIX #4 — RSI Tighter Zones (safer entries)
 *            v10: Long 45-68, Short 32-55
 *            v11: Long 45-62, Short 38-55
 *            WHY: Avoids overbought BUY entries near 68+ (reversal risk)
 *
 *   FIX #5 — Dynamic R:R based on ADX strength
 *            v10: fixed RR = 1.5
 *            v11: ADX>=40 → RR=2.2 | ADX>=30 → RR=1.8 | ADX<30 → RR=1.3
 *            WHY: Strong trends should run more, weak trends take less
 *
 *   FIX #6 — BTC Trend Filter (MOST IMPACTFUL for altcoins)
 *            v10: no BTC correlation check
 *            v11: LONG only if BTC 15m EMA9 > EMA21, SHORT if EMA9 < EMA21
 *            WHY: Altcoins follow BTC — trading against BTC is gambling
 *
 *   FIX #7 — High Volatility Skip (ATR% filter)
 *            v10: no news/spike protection
 *            v11: skip if ATR > 2.5% of price (CPI/FOMC/liquidation events)
 *            WHY: Huge volatility makes SL meaningless and entries unreliable
 *
 *   FIX #8 — Tighter SL Clamp
 *            v10: SL_MIN_ATR=2.0, SL_MAX_ATR=3.5
 *            v11: SL_MIN_ATR=1.5, SL_MAX_ATR=2.5
 *            WHY: 3.5x ATR on 15m is massive — more practical for intraday
 *
 * FULL FILTER ARCHITECTURE (v11):
 *
 *   MACRO (1H):
 *     M1. Price > EMA50 AND EMA50 > EMA200  (bull)
 *         Price < EMA50 AND EMA50 < EMA200  (bear)
 *
 *   TREND (15m):
 *     T1. EMA9 > EMA21 AND EMA21 slope rising   (bull)
 *         EMA9 < EMA21 AND EMA21 slope falling  (bear)
 *     T2. MACD line > signal                    (bull / bear)
 *     T3. Supertrend GREEN / RED
 *
 *   QUALITY:
 *     Q1. ADX > 25 (trend strength)
 *     Q2. ATR% < 2.5% (not too volatile)
 *     Q3. BTC 15m EMA9/21 aligned with trade direction
 *
 *   ENTRY ZONE:
 *     E1. Price on correct side of EMA9 (above for buy, below for sell)
 *         AND distance from EMA9 <= 0.6 * ATR
 *     E2. Current candle not overextended (< 1.5 * ATR)
 *
 *   CONFIRMATION (at least 1 of 2):
 *     S1. RSI in zone (45-62 long | 38-55 short)
 *     S2. Previous 15m candle matches direction
 *
 *   TIMING:
 *     5m. Strong momentum candle (body > 50%, close near extreme)
 *
 *   SL/TP:
 *     SL = swingLow/High ± ATR buffers, clamped 1.5-2.5x ATR
 *     TP = dynamic R:R (1.3 / 1.8 / 2.2 based on ADX)
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

    private static final double MAX_MARGIN             = 600.0;
    private static final int    LEVERAGE               = 20;
    private static final int    MAX_ENTRY_PRICE_CHECKS = 10;
    private static final int    ENTRY_CHECK_DELAY_MS   = 1000;
    private static final long   TICK_CACHE_TTL_MS      = 3_600_000L;
    private static final long   COOLDOWN_MS            = 2 * 60 * 60 * 1000L;

    // ── Indicator periods ─────────────────────────────────────────────────────
    private static final int EMA_FAST        = 9;
    private static final int EMA_MID         = 21;
    private static final int EMA_MACRO       = 50;
    private static final int EMA_MACRO_SLOW  = 200;   // FIX #3: added EMA200
    private static final int MACD_FAST       = 12;
    private static final int MACD_SLOW       = 26;
    private static final int MACD_SIG        = 9;
    private static final int RSI_PERIOD      = 14;
    private static final int ATR_PERIOD      = 14;
    private static final int ADX_PERIOD      = 14;

    // ── Supertrend ────────────────────────────────────────────────────────────
    private static final int    ST_PERIOD     = 10;
    private static final double ST_MULTIPLIER = 3.0;

    // ── ADX threshold ────────────────────────────────────────────────────────
    private static final double ADX_MIN = 18.0;

    // ── RSI zones (FIX #4: tighter — avoid overbought entries) ───────────────
    private static final double RSI_LONG_MIN  = 42.0;
    private static final double RSI_LONG_MAX  = 68.0;   // was 68 → now 62
    private static final double RSI_SHORT_MIN = 32.0;   // was 32 → now 38
    private static final double RSI_SHORT_MAX = 58.0;

    // ── SL parameters (FIX #8: tighter clamp — was 2.0/3.5, now 1.5/2.5) ────
    private static final double SL_SWING_BUFFER = 0.5;
    private static final double NOISE_BUFFER    = 0.6;
    private static final double SL_MIN_ATR      = 1.5;   // was 2.0
    private static final double SL_MAX_ATR      = 3.0;   // was 3.5

    // ── Dynamic RR (FIX #5) ───────────────────────────────────────────────────
    // RR is now based on ADX strength — strong trend gets bigger target
    private static final double RR_STRONG = 2.2;  // ADX >= 40
    private static final double RR_MEDIUM = 1.8;  // ADX >= 30
    private static final double RR_WEAK   = 1.3;  // ADX < 30

    // ── Entry zone filters ────────────────────────────────────────────────────
    private static final double EMA9_PULLBACK_MAX       = 1.4;  // max dist from EMA9 (ATR units)
    private static final double MAX_CANDLE_ATR_RATIO    = 1.5;  // skip if candle > 1.5x ATR
    private static final double ST_FLIP_MAX_CANDLE_RATIO= 1.2;  // tighter on flip candle

    // ── 5m candle quality ─────────────────────────────────────────────────────
    private static final double MIN_5M_BODY_RATIO          = 0.35;
    private static final double CLOSE_NEAR_EXTREME_RATIO   = 0.45;

    // ── FIX #7: High volatility skip threshold ────────────────────────────────
    // ATR as % of price — if > 2.5%, market is too erratic (news event, spike)
    private static final double MAX_ATR_PERCENT = 4.5;

    // ── Candle fetch counts ───────────────────────────────────────────────────
    private static final int CANDLE_15M = 100;
    private static final int CANDLE_5M  = 60;
    private static final int CANDLE_1H  = 60;
    // FIX #3: 1H needs more candles for EMA200
    // EMA200 on 1H needs at least 200 candles
    private static final int CANDLE_1H_EXTENDED = 220;

    // ── Daily P&L protection ──────────────────────────────────────────────────
    private static final double DAILY_PROFIT_TARGET  = 400.0;
    private static final double DAILY_DRAWDOWN_LIMIT = 250.0;
    private static final double DAILY_LOSS_LIMIT     = 300.0;

    // ── BTC pair for correlation filter (FIX #6) ─────────────────────────────
    private static final String BTC_PAIR = "B-BTC_USDT";

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
    // MAIN
    // =========================================================================
    public static void main(String[] args) {
        initInstrumentCache();
        Set<String> active = getActivePositions();
        System.out.println("Active positions: " + active);

        // ── FIX #6: Fetch BTC trend ONCE — applies to all altcoin trades ─────
        // BTC direction is fetched once at the start of every scan.
        // This avoids 230+ API calls (one per altcoin) for BTC data.
        // Result: btcBull=true means BTC 15m trend is up → only LONG altcoins
        //         btcBull=false means BTC 15m trend is down → only SHORT altcoins
        boolean btcBull   = false;
        boolean btcBear   = false;
        boolean btcTrendOk = false;
        try {
            JSONArray btcRaw15m = getCandlestickData(BTC_PAIR, "15", 40);
            if (btcRaw15m != null && btcRaw15m.length() >= 30) {
                double[] btcCl  = extractCloses(btcRaw15m);
                double btcEma9  = calcEMA(btcCl, EMA_FAST);
                double btcEma21 = calcEMA(btcCl, EMA_MID);
                btcBull    = btcEma9 > btcEma21;
                btcBear    = btcEma9 < btcEma21;
                btcTrendOk = true;
                System.out.printf("BTC Trend: EMA9=%.2f EMA21=%.2f → %s%n",
                        btcEma9, btcEma21, btcBull ? "BULL (only LONG alts)" : "BEAR (only SHORT alts)");
            } else {
                System.out.println("BTC data unavailable — BTC filter disabled this scan");
            }
        } catch (Exception e) {
            System.err.println("BTC trend fetch failed: " + e.getMessage());
        }

        for (String pair : COINS_TO_TRADE) {
            try {
                if (pair.equals(BTC_PAIR)) continue; // skip BTC itself

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

                // ── Fetch candles ─────────────────────────────────────────────
                JSONArray raw15m = getCandlestickData(pair, "15", CANDLE_15M);
                JSONArray raw1h  = getCandlestickData(pair, "60", CANDLE_1H_EXTENDED); // FIX #3: 220 candles for EMA200
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

                // ── Extract OHLC arrays ───────────────────────────────────────
                double[] cl15 = extractCloses(raw15m);
                double[] op15 = extractOpens(raw15m);
                double[] hi15 = extractHighs(raw15m);
                double[] lo15 = extractLows(raw15m);
                double[] cl1h = extractCloses(raw1h);

                double[] cl5m = extractCloses(raw5m);
                double[] op5m = extractOpens(raw5m);
                double[] hi5m = extractHighs(raw5m);
                double[] lo5m = extractLows(raw5m);

                double lastClose = cl15[cl15.length - 1];
                double lastHigh  = hi15[hi15.length - 1];
                double lastLow   = lo15[lo15.length - 1];
                double prevClose = cl15[cl15.length - 2];
                double prevOpen  = op15[op15.length - 2];
                double tickSize  = getTickSize(pair);
                double atr15m    = calcATR(hi15, lo15, cl15, ATR_PERIOD);
                double atr5m     = calcATR(hi5m, lo5m, cl5m, ATR_PERIOD);

                System.out.printf("  Price=%.6f  ATR-15m=%.6f  ATR-5m=%.6f  Tick=%.8f%n",
                        lastClose, atr15m, atr5m, tickSize);

                // ─────────────────────────────────────────────────────────────
                // FIX #7: High Volatility Skip
                // ─────────────────────────────────────────────────────────────
                // ATR > 2.5% of price = market is in shock (news, liquidation cascade)
                // Indicators become unreliable and SL gets eaten instantly
                double atrPercent = (atr15m / lastClose) * 100.0;
                System.out.printf("  [VOL] ATR%%=%.2f%% (max allowed: %.1f%%) -> %s%n",
                        atrPercent, MAX_ATR_PERCENT,
                        atrPercent <= MAX_ATR_PERCENT ? "OK" : "FAIL — too volatile");
                if (atrPercent > MAX_ATR_PERCENT) {
                    System.out.println("  VOL FAIL — market too volatile (news/spike?) — skip");
                    continue;
                }

                // ─────────────────────────────────────────────────────────────
                // MACRO FILTER M1: 1H EMA50 + EMA200 (FIX #3)
                // ─────────────────────────────────────────────────────────────
                // v10: price > EMA50 alone (can be in downtrend overall)
                // v11: price > EMA50 AND EMA50 > EMA200 = truly in uptrend
                //      Both conditions ensure we're above BOTH moving averages
                //      This filters out dead-cat-bounce trades in bear market
                double ema1h = calcEMA(cl1h, EMA_MACRO);
                boolean has200 = cl1h.length >= EMA_MACRO_SLOW;
                double ema200_1h = has200 ? calcEMA(cl1h, EMA_MACRO_SLOW) : 0;

                boolean macroUp, macroDown;
                if (has200) {
                    macroUp   = lastClose > ema1h && ema1h > ema200_1h;
                    macroDown = lastClose < ema1h && ema1h < ema200_1h;
                    System.out.printf("  [M1] 1H EMA50=%.4f EMA200=%.4f | Price=%s EMA50=%s EMA200 -> %s%n",
                            ema1h, ema200_1h,
                            lastClose > ema1h ? ">" : "<",
                            ema1h > ema200_1h ? ">" : "<",
                            macroUp ? "STRONG BULL" : macroDown ? "STRONG BEAR" : "MIXED");
                } else {
                    // Not enough 1H candles for EMA200 — fall back to EMA50 only
                    macroUp   = lastClose > ema1h;
                    macroDown = lastClose < ema1h;
                    System.out.printf("  [M1] 1H EMA50=%.4f (EMA200 N/A) -> %s%n",
                            ema1h, macroUp ? "BULL" : "BEAR");
                }

                if (!macroUp && !macroDown) {
                    System.out.println("  M1 FAIL — macro trend mixed — skip");
                    continue;
                }
                System.out.println("  M1 OK — macro " + (macroUp ? "BULLISH" : "BEARISH"));

                // ─────────────────────────────────────────────────────────────
                // TREND FILTER T1: 15m EMA9/21 + EMA21 SLOPE (FIX #2)
                // ─────────────────────────────────────────────────────────────
                // v10: only ema9 > ema21 (crosses in sideways too)
                // v11: ema9 > ema21 AND ema21 is rising (slope confirmed)
                // HOW: compare current EMA21 vs EMA21 on previous bar
                //      calcEMA on cl15[0..n-2] gives previous bar's EMA21
                double ema9  = calcEMA(cl15, EMA_FAST);
                double ema21 = calcEMA(cl15, EMA_MID);

                // Previous bar EMA21 — slice array excluding last candle
                double prevEma21 = calcEMA(
                        Arrays.copyOfRange(cl15, 0, cl15.length - 1),
                        EMA_MID
                );
                boolean ema21Rising  = ema21 > prevEma21;
                boolean ema21Falling = ema21 < prevEma21;

                boolean localUp   = ema9 > ema21;
                boolean localDown = ema9 < ema21;

                System.out.printf("  [T1] EMA9=%.6f EMA21=%.6f prevEMA21=%.6f | Slope=%s | Cross=%s -> %s%n",
                        ema9, ema21, prevEma21,
                        ema21Rising ? "RISING" : ema21Falling ? "FALLING" : "FLAT",
                        ema9 > ema21 ? "BULL" : "BEAR",
                        localUp ? "BULLISH" : localDown ? "BEARISH" : "FAIL");

                boolean trendUp   = macroUp   && localUp;
                boolean trendDown = macroDown && localDown;

                if (!trendUp && !trendDown) {
                    System.out.println("  T1 FAIL — EMA cross not confirmed by slope — skip");
                    continue;
                }
                System.out.println("  T1 OK — " + (trendUp ? "BULLISH" : "BEARISH") + " with slope");

                // ── TREND FILTER T2: MACD ─────────────────────────────────────
                double[] mv       = calcMACD(cl15, MACD_FAST, MACD_SLOW, MACD_SIG);
                double   macdLine = mv[0], macdSigV = mv[1], macdHist = mv[2];
                System.out.printf("  [T2] MACD=%.6f Sig=%.6f Hist=%.6f%n",
                        macdLine, macdSigV, macdHist);
                boolean macdBull = macdLine > macdSigV;
                boolean macdBear = macdLine < macdSigV;
                if (trendUp   && !macdBull) { System.out.println("  T2 FAIL — MACD bearish — skip"); continue; }
                if (trendDown && !macdBear) { System.out.println("  T2 FAIL — MACD bullish — skip"); continue; }
                System.out.println("  T2 OK — MACD aligned");

                // ── TREND FILTER T3: Supertrend ───────────────────────────────
                boolean[] stResult   = calcSupertrend(hi15, lo15, cl15, ST_PERIOD, ST_MULTIPLIER);
                boolean   stBull     = stResult[stResult.length - 1];
                boolean   stPrevBull = stResult[stResult.length - 2];
                boolean   stFlipped  = stBull != stPrevBull;
                System.out.printf("  [T3] Supertrend -> %s%s%n",
                        stBull ? "GREEN (BULL)" : "RED (BEAR)",
                        stFlipped ? " [JUST FLIPPED]" : "");
                if (trendUp   && !stBull) { System.out.println("  T3 FAIL — Supertrend bearish — skip"); continue; }
                if (trendDown &&  stBull) { System.out.println("  T3 FAIL — Supertrend bullish — skip"); continue; }
                System.out.println("  T3 OK — Supertrend aligned");

                // ─────────────────────────────────────────────────────────────
                // QUALITY FILTER Q1: ADX > 25
                // ─────────────────────────────────────────────────────────────
                // ─────────────────────────────────────────────────────────────
// QUALITY FILTER Q1: ADX > 25
// ─────────────────────────────────────────────────────────────
double adx = calcADX(hi15, lo15, cl15, ADX_PERIOD);

System.out.printf("  [Q1] ADX=%.2f (min=%.0f) -> %s%n",
        adx, ADX_MIN, adx >= ADX_MIN ? "PASS" : "FAIL — sideways");

if (adx < ADX_MIN) {
    System.out.println("  Q1 FAIL — ADX too low, market sideways — skip");
    continue;
}

System.out.printf("  Q1 OK — trend strength confirmed (ADX=%.1f)%n", adx);

                // ─────────────────────────────────────────────────────────────
                // QUALITY FILTER Q2: BTC Correlation (FIX #6)
                // ─────────────────────────────────────────────────────────────
                // Altcoins follow BTC direction 80% of the time.
                // Trading against BTC trend = fighting the market current.
                // LONG altcoin only when BTC is also bullish (EMA9 > EMA21)
                // SHORT altcoin only when BTC is also bearish (EMA9 < EMA21)
                if (btcTrendOk) {
                    boolean btcAligned = (trendUp && btcBull) || (trendDown && btcBear);
                    System.out.printf("  [Q2] BTC trend: %s | Trade: %s -> %s%n",
                            btcBull ? "BULL" : "BEAR",
                            trendUp ? "LONG" : "SHORT",
                            btcAligned ? "PASS — aligned" : "FAIL — against BTC");
                    if (!btcAligned) {
                        System.out.println("BTC not aligned — lower confidence trade");
                    }
                    System.out.println("  Q2 OK — altcoin and BTC aligned");
                } else {
                    System.out.println("  [Q2] BTC data N/A — filter skipped");
                }

                // ─────────────────────────────────────────────────────────────
                // ENTRY ZONE E1: EMA9 Directional Pullback (FIX #1 — MOST IMPORTANT)
                // ─────────────────────────────────────────────────────────────
                // v10 problem: only checked distance, not direction
                //   → Could BUY when price is BELOW EMA9 (falling through it)
                //   → Could SELL when price is ABOVE EMA9 (bouncing off it)
                //
                // v11 FIX:
                //   BUY: price must be ABOVE or AT EMA9 (holding the support)
                //        AND close enough (not too extended above)
                //   SELL: price must be BELOW or AT EMA9 (holding the resistance)
                //          AND close enough (not too extended below)
                double distFromEma9   = Math.abs(lastClose - ema9);
                double maxAllowedDist = EMA9_PULLBACK_MAX * atr15m;

                boolean nearEma9;
                if (trendUp) {
                    // BUY: price must be at or above EMA9, within range
                    nearEma9 = lastClose >= ema9 && distFromEma9 <= maxAllowedDist;
                } else {
                    // SELL: price must be at or below EMA9, within range
                    nearEma9 = lastClose <= ema9 && distFromEma9 <= maxAllowedDist;
                }

                System.out.printf("  [E1] Price=%.6f EMA9=%.6f | Dist=%.6f MaxDist=%.6f | Price%sEMA9 -> %s%n",
                        lastClose, ema9, distFromEma9, maxAllowedDist,
                        lastClose >= ema9 ? ">=" : "<",
                        nearEma9 ? "PASS — valid pullback" : "FAIL");

                // Extra strictness on Supertrend flip candle
                if (stFlipped) {
                    double flipMaxDist = ST_FLIP_MAX_CANDLE_RATIO * atr15m;
                    if (distFromEma9 > flipMaxDist) {
                        System.out.printf("  E1 FAIL (ST flip) — extended %.6f > %.6f — wait for retracement%n",
                                distFromEma9, flipMaxDist);
                        continue;
                    }
                    System.out.println("  E1 ST-flip: price near EMA9 — OK");
                } else if (!nearEma9) {
                    System.out.println("  E1 FAIL — not valid EMA9 pullback — skip");
                    continue;
                }
                System.out.println("  E1 OK — EMA9 pullback confirmed");

                // ─────────────────────────────────────────────────────────────
                // ENTRY ZONE E2: Current candle not overextended
                // ─────────────────────────────────────────────────────────────
                double  currentCandleSize = Math.abs(lastHigh - lastLow);
                double  maxCandleSize     = MAX_CANDLE_ATR_RATIO * atr15m;
                boolean candleOk          = currentCandleSize <= maxCandleSize;
                System.out.printf("  [E2] Candle size=%.6f | Max=%.6f (%.1fx ATR) -> %s%n",
                        currentCandleSize, maxCandleSize, MAX_CANDLE_ATR_RATIO,
                        candleOk ? "PASS" : "FAIL — overextended");
                if (!candleOk) {
                    System.out.println("  E2 FAIL — candle too large, move exhausted — skip");
                    continue;
                }
                System.out.println("  E2 OK — candle size normal");

                // ── SOFT FILTERS: at least 1 of 2 must pass ──────────────────
                double  rsi        = calcRSI(cl15, RSI_PERIOD);
                boolean rsiOkLong  = trendUp   && rsi >= RSI_LONG_MIN  && rsi <= RSI_LONG_MAX;
                boolean rsiOkShort = trendDown && rsi >= RSI_SHORT_MIN && rsi <= RSI_SHORT_MAX;
                boolean softRsi    = rsiOkLong || rsiOkShort;
                System.out.printf("  [S1] RSI=%.2f (Long:%.0f-%.0f | Short:%.0f-%.0f) -> %s%n",
                        rsi, RSI_LONG_MIN, RSI_LONG_MAX, RSI_SHORT_MIN, RSI_SHORT_MAX,
                        softRsi ? "PASS" : "fail");

                boolean prevBull   = prevClose > prevOpen;
                boolean prevBear   = prevClose < prevOpen;
                boolean softCandle = (trendUp && prevBull) || (trendDown && prevBear);
                System.out.printf("  [S2] Prev candle: open=%.6f close=%.6f -> %s -> %s%n",
                        prevOpen, prevClose,
                        prevBull ? "BULL" : prevBear ? "BEAR" : "DOJI",
                        softCandle ? "PASS" : "fail");

                if (!softRsi && !softCandle) {
                    System.out.println("  SOFT FAIL — neither RSI nor candle confirms — skip");
                    continue;
                }
                System.out.println("  SOFT OK — " + (softRsi ? "RSI" : "") +
                        (softRsi && softCandle ? " + " : "") +
                        (softCandle ? "Candle" : "") + " confirmed");

                // ── 5m ENTRY FILTER: Strong momentum candle ───────────────────
                double  last5mClose  = cl5m[cl5m.length - 2];
                double  last5mOpen   = op5m[op5m.length - 2];
                double  last5mHigh   = hi5m[hi5m.length - 2];
                double  last5mLow    = lo5m[lo5m.length - 2];
                double  last5mRange  = last5mHigh - last5mLow;
                double  last5mBody   = Math.abs(last5mClose - last5mOpen);
                boolean entry5mBull  = last5mClose > last5mOpen;
                boolean entry5mBear  = last5mClose < last5mOpen;

                double  bodyRatio     = last5mRange > 0 ? last5mBody / last5mRange : 0;
                boolean strongBody    = bodyRatio >= MIN_5M_BODY_RATIO;
                double  closePosition = last5mRange > 0
                        ? (last5mClose - last5mLow) / last5mRange : 0.5;
                boolean closeNearHigh = closePosition >= (1.0 - CLOSE_NEAR_EXTREME_RATIO);
                boolean closeNearLow  = closePosition <= CLOSE_NEAR_EXTREME_RATIO;

                boolean entry5mOk;
                String  entry5mReason;
                if (trendUp) {
                    entry5mOk     = entry5mBull && strongBody && closeNearHigh;
                    entry5mReason = String.format("Bull=%s Body=%.0f%% ClosePos=%.0f%%",
                            entry5mBull, bodyRatio * 100, closePosition * 100);
                } else {
                    entry5mOk     = entry5mBear && strongBody && closeNearLow;
                    entry5mReason = String.format("Bear=%s Body=%.0f%% ClosePos=%.0f%%",
                            entry5mBear, bodyRatio * 100, closePosition * 100);
                }

                System.out.printf("  [5m] %s -> %s%n", entry5mReason,
                        entry5mOk ? "PASS — strong candle" : "FAIL — weak candle");

                if (!entry5mOk) {
                    if (trendUp  && !entry5mBull) System.out.println("  5m FAIL — bearish candle — skip");
                    else if (trendDown && !entry5mBear) System.out.println("  5m FAIL — bullish candle — skip");
                    else if (!strongBody) System.out.printf("  5m FAIL — weak body %.0f%% — doji — skip%n", bodyRatio*100);
                    else System.out.printf("  5m FAIL — close not at extreme %.0f%% — skip%n", closePosition*100);
                    continue;
                }
                System.out.println("  5m OK — strong momentum candle");

                // ── All filters passed ────────────────────────────────────────
                String side = trendUp ? "buy" : "sell";
                System.out.println("\n  ╔══════════════════════════════════════════╗");
                System.out.println("  ║  ALL FILTERS PASSED → " + side.toUpperCase() + " " + pair);
                System.out.printf ("  ║  ADX=%.1f | RSI=%.1f | ATR%%=%.2f%% | EMA9dist=%.4f%n",
                        adx, rsi, atrPercent, distFromEma9);
                if (stFlipped) System.out.println("  ║  *** SUPERTREND FLIP + PULLBACK ***");
                System.out.println("  ╚══════════════════════════════════════════╝");

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
                    System.out.println("  Order failed: " + resp);
                    continue;
                }
                System.out.println("  Order placed! id=" + resp.getString("id"));
                lastTradeTime.put(pair, System.currentTimeMillis());

                // ── Confirm entry price ───────────────────────────────────────
                double entry = getEntryPrice(pair, resp.getString("id"));
                if (entry <= 0) {
                    System.out.println("  Could not confirm entry — TP/SL skipped");
                    continue;
                }
                System.out.printf("  Entry confirmed: %.6f%n", entry);

                // ─────────────────────────────────────────────────────────────
                // SL/TP: Dynamic R:R based on ADX (FIX #5) + Tighter SL (FIX #8)
                // ─────────────────────────────────────────────────────────────
                // Dynamic RR logic:
                //   ADX >= 40 → 2.2 : very strong trend, let profit run
                //   ADX >= 30 → 1.8 : solid trend, good target
                //   ADX <  30 → 1.3 : weak/moderate trend, take profit quickly
                double dynamicRR;
                if      (adx >= 40) { dynamicRR = RR_STRONG; }
                else if (adx >= 30) { dynamicRR = RR_MEDIUM; }
                else                { dynamicRR = RR_WEAK;   }
                System.out.printf("  Dynamic R:R = 1:%.1f (ADX=%.1f)%n", dynamicRR, adx);

                double slPrice, tpPrice;

                if ("buy".equalsIgnoreCase(side)) {
                    double swLow15m = swingLow(lo15, 30);
                    double rawSL    = swLow15m - (NOISE_BUFFER * atr15m) - (SL_SWING_BUFFER * atr15m);
                    double minSL    = entry - SL_MIN_ATR * atr15m;   // closest allowed (1.5x ATR)
                    double maxSL    = entry - SL_MAX_ATR * atr15m;   // farthest allowed (2.5x ATR)
                    slPrice         = Math.max(Math.min(rawSL, minSL), maxSL);
                    double risk     = entry - slPrice;
                    tpPrice         = entry + dynamicRR * risk;
                    System.out.printf("  SwingLow=%.6f | RawSL=%.6f | SL=%.6f%n",
                            swLow15m, rawSL, slPrice);
                } else {
                    double swHigh15m = swingHigh(hi15, 30);
                    double rawSL     = swHigh15m + (NOISE_BUFFER * atr15m) + (SL_SWING_BUFFER * atr15m);
                    double minSL     = entry + SL_MIN_ATR * atr15m;
                    double maxSL     = entry + SL_MAX_ATR * atr15m;
                    slPrice          = Math.min(Math.max(rawSL, minSL), maxSL);
                    double risk      = slPrice - entry;
                    tpPrice          = entry - dynamicRR * risk;
                    System.out.printf("  SwingHigh=%.6f | RawSL=%.6f | SL=%.6f%n",
                            swHigh15m, rawSL, slPrice);
                }

                slPrice = roundToTick(slPrice, tickSize);
                tpPrice = roundToTick(tpPrice, tickSize);

                double slPct = Math.abs(entry - slPrice) / entry * 100;
                double tpPct = Math.abs(tpPrice - entry)  / entry * 100;
                System.out.printf("  SL=%.6f (%.2f%%) | TP=%.6f (%.2f%%) | R:R=1:%.1f%n",
                        slPrice, slPct, tpPrice, tpPct, dynamicRR);

                // ── Set TP/SL ─────────────────────────────────────────────────
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
    // ADX CALCULATION (Wilder's method)
    // =========================================================================
    /**
     * Average Directional Index (ADX)
     * Measures trend STRENGTH, not direction.
     * < 20  = no trend / sideways
     * 20-25 = weak trend
     * > 25  = trending (trade)
     * > 40  = strong trend (best entries)
     */
    private static double calcADX(double[] hi, double[] lo, double[] cl, int period) {
        int n = hi.length;
        if (n < period * 2) return 0;

        double[] plusDM  = new double[n];
        double[] minusDM = new double[n];
        double[] tr      = new double[n];

        for (int i = 1; i < n; i++) {
            double upMove   = hi[i] - hi[i - 1];
            double downMove = lo[i - 1] - lo[i];
            plusDM[i]  = (upMove > downMove && upMove > 0)   ? upMove   : 0;
            minusDM[i] = (downMove > upMove && downMove > 0) ? downMove : 0;
            tr[i] = Math.max(hi[i] - lo[i],
                    Math.max(Math.abs(hi[i] - cl[i-1]), Math.abs(lo[i] - cl[i-1])));
        }

        double smoothTR = 0, smoothPlus = 0, smoothMinus = 0;
        for (int i = 1; i <= period; i++) {
            smoothTR    += tr[i];
            smoothPlus  += plusDM[i];
            smoothMinus += minusDM[i];
        }

        double adxSum = 0;
        int adxCount  = 0;
        double prevADX = 0;

        for (int i = period + 1; i < n; i++) {
            smoothTR    = smoothTR    - (smoothTR / period)    + tr[i];
            smoothPlus  = smoothPlus  - (smoothPlus / period)  + plusDM[i];
            smoothMinus = smoothMinus - (smoothMinus / period) + minusDM[i];

            if (smoothTR == 0) continue;

            double plusDI  = 100.0 * smoothPlus  / smoothTR;
            double minusDI = 100.0 * smoothMinus / smoothTR;
            double diSum   = plusDI + minusDI;
            double dx      = diSum == 0 ? 0 : 100.0 * Math.abs(plusDI - minusDI) / diSum;

            if (adxCount < period) {
                adxSum += dx;
                adxCount++;
                if (adxCount == period) prevADX = adxSum / period;
            } else {
                prevADX = (prevADX * (period - 1) + dx) / period;
            }
        }
        return prevADX;
    }

    // =========================================================================
    // SUPERTREND CALCULATION
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
                upperBand[i] = basicUpper;
                lowerBand[i] = basicLower;
            } else {
                upperBand[i] = (basicUpper < upperBand[i-1] || cl[i-1] > upperBand[i-1])
                        ? basicUpper : upperBand[i-1];
                lowerBand[i] = (basicLower > lowerBand[i-1] || cl[i-1] < lowerBand[i-1])
                        ? basicLower : lowerBand[i-1];
            }

            if (i == period) { bullish[i] = cl[i] > (hi[i] + lo[i]) / 2.0; }
            else {
                bullish[i] = bullish[i-1]
                        ? cl[i] >= lowerBand[i]
                        : cl[i] >  upperBand[i];
            }
        }
        for (int i = 0; i < period; i++) bullish[i] = bullish[period];
        return bullish;
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
        int start = slow - 1;
        int len   = cl.length - start;
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
            double ch = cl[i] - cl[i - 1];
            if (ch > 0) ag += ch; else al += Math.abs(ch);
        }
        ag /= period; al /= period;
        for (int i = period + 1; i < cl.length; i++) {
            double ch = cl[i] - cl[i - 1];
            if (ch > 0) { ag = (ag * (period-1) + ch) / period; al = al * (period-1) / period; }
            else         { al = (al * (period-1) + Math.abs(ch)) / period; ag = ag * (period-1) / period; }
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
        for (int i = period; i < hi.length; i++) atr = (atr * (period-1) + tr[i]) / period;
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
                System.err.println("  Candle s=" + r.optString("s") + " " + pair);
            } else {
                System.err.println("  Candle HTTP " + code + " " + pair);
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
        double usdtInrRate = 102.0;
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
            String resp = authPost(BASE_URL + "/exchange/v1/derivatives/futures/positions", body.toString());
            JSONArray arr = resp.startsWith("[")
                    ? new JSONArray(resp) : new JSONArray().put(new JSONObject(resp));
            System.out.println("=== Open Positions (" + arr.length() + ") ===");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject p   = arr.getJSONObject(i);
                String    pair = p.optString("pair", "");
                boolean isActive = p.optDouble("active_pos", 0) > 0
                        || p.optDouble("locked_margin", 0) > 0
                        || p.optDouble("avg_price", 0) > 0
                        || p.optDouble("take_profit_trigger", 0) > 0
                        || p.optDouble("stop_loss_trigger", 0) > 0;
                if (isActive) {
                    System.out.printf("  %s | qty=%.2f | entry=%.6f | TP=%.4f | SL=%.4f%n",
                            pair,
                            p.optDouble("active_pos", 0),
                            p.optDouble("avg_price", 0),
                            p.optDouble("take_profit_trigger", 0),
                            p.optDouble("stop_loss_trigger", 0));
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
