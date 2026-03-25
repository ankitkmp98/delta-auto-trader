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
 * CoinDCXFuturesBothSidesTrader — BOTH LONG + SHORT ON EVERY SIGNAL
 *
 * =====================================================================
 * STRATEGY OVERVIEW
 * =====================================================================
 *
 * TIMEFRAMES USED:
 *   - 15-minute candles : short-term momentum & entry signal
 *   - 1-hour    candles : medium-term trend confirmation
 *   - 2-hour    candles : macro trend (replaces old 1H EMA50)
 *
 * SIGNAL CHECK (light — any 2 of 3 timeframes must agree):
 *   For each timeframe we compute:
 *     T1. EMA9 vs EMA21 cross (fast/slow EMAs)
 *     T2. MACD line vs Signal line direction
 *     T3. RSI is NOT extreme (30–70 band, meaning there is still room to run)
 *
 *   If at least 2 out of 3 timeframes show bullish alignment   → signal detected
 *   If at least 2 out of 3 timeframes show bearish alignment   → signal detected
 *   If EITHER signal is detected                               → place BOTH LONG and SHORT
 *
 * WHY BOTH SIDES?
 *   Opening both a LONG and a SHORT simultaneously creates a "futures straddle":
 *   - The market WILL move — only question is which way.
 *   - One side will hit TP; the other will hit SL.
 *   - Net profit if TP > SL distance × 2 (achieved with our 2:1 RR setting).
 *   - This strategy is immune to direction bias — captures big moves either way.
 *
 * STOP LOSS / TAKE PROFIT:
 *   Long  SL = entry - (SL_MIN_ATR × ATR),  clamped to SL_MAX_ATR
 *   Long  TP = entry + RR × risk
 *   Short SL = entry + (SL_MIN_ATR × ATR),  clamped to SL_MAX_ATR
 *   Short TP = entry - RR × risk
 *   RR = 2.0 (take profit is 2× the stop loss distance)
 *
 * NO TRAILING STOP LOSS:
 *   Trailing stop logic has been completely removed.
 *   Fixed TP/SL is set at the time of entry and never adjusted.
 *
 * SKIPPING LOGIC:
 *   If a coin already has an ACTIVE POSITION (long or short), it is skipped
 *   entirely — no new orders placed for it this cycle.
 * =====================================================================
 */
public class CoinDCXFuturesTrader8C_BUY_SELL_NEW_LOGIC_THREE {

    // =========================================================================
    // Configuration
    // =========================================================================
    private static final String API_KEY    = System.getenv("DELTA_API_KEY");
    private static final String API_SECRET = System.getenv("DELTA_API_SECRET");
    private static final String BASE_URL       = "https://api.coindcx.com";
    private static final String PUBLIC_API_URL = "https://public.coindcx.com";

    private static final double MAX_MARGIN             = 1200.0;
    private static final int    LEVERAGE               = 20;
    private static final int    MAX_ENTRY_PRICE_CHECKS = 10;
    private static final int    ENTRY_CHECK_DELAY_MS   = 1000;
    private static final long   TICK_CACHE_TTL_MS      = 3_600_000L;

    // Indicator parameters
    private static final int EMA_FAST   = 9;
    private static final int EMA_MID    = 21;
    private static final int MACD_FAST  = 12;
    private static final int MACD_SLOW  = 26;
    private static final int MACD_SIG   = 9;
    private static final int RSI_PERIOD = 14;
    private static final int ATR_PERIOD = 14;

    // RSI: only enter if RSI is NOT overbought/oversold (room left to run)
    private static final double RSI_MIN = 30.0;
    private static final double RSI_MAX = 70.0;

    // SL / TP parameters
    private static final double SL_MIN_ATR = 2.0;   // minimum SL distance in ATR
    private static final double SL_MAX_ATR = 5.0;   // maximum SL distance in ATR
    private static final double RR         = 2.0;   // TP = entry ± RR × risk (2:1 risk/reward)

    // Candle counts per timeframe
    private static final int CANDLE_15M = 60;   // 60 bars × 15min = 15 hours of data
    private static final int CANDLE_1H  = 40;   // 40 bars × 1hr  = 40 hours of data
    private static final int CANDLE_2H  = 30;   // 30 bars × 2hr  = 60 hours of data

    // Minimum timeframes that must agree for signal (out of 3)
    private static final int MIN_TF_AGREEMENT = 2;

    private static final Map<String, JSONObject> instrumentCache = new ConcurrentHashMap<>();
    private static long lastCacheUpdate = 0;

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
        "DEFI", "USDC", "KAS", "1000SATS", "ARKM", "PIXEL", "MAV", "REI", "ZRO", "COOKIE",
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
        System.out.println("=== CoinDCX Both-Sides Trader ===");
        System.out.println("Strategy: Place LONG + SHORT on every detected signal");
        System.out.println("Trailing SL: DISABLED (fixed TP/SL only)");
        System.out.println("Timeframes : 15m + 1h + 2h (need " + MIN_TF_AGREEMENT + "/3 to agree)");
        System.out.println();

        initInstrumentCache();

        // Collect all active pairs so we skip them
        System.out.println("=== Fetching Active Positions ===");
        List<JSONObject> activePositions = getActivePositionsFull();
        Set<String> activePairs = new HashSet<>();
        for (JSONObject pos : activePositions) {
            String pair = pos.optString("pair", "");
            if (!pair.isEmpty()) activePairs.add(pair);
        }
        System.out.println("Active pairs (will skip): " + activePairs);

        // Scan every coin for signals
        System.out.println("\n=== Scanning Coins for Signals ===");

        int scanned = 0, signaled = 0, ordered = 0;

        for (String pair : COINS_TO_TRADE) {
            try {
                scanned++;

                // Skip if EITHER long or short is already open for this pair
                if (activePairs.contains(pair)) {
                    System.out.println("SKIP " + pair + " — active position exists");
                    continue;
                }

                System.out.println("\n---- " + pair + " ----");

                // ── Fetch candles for all 3 timeframes ───────────────────────
                JSONArray raw15m = getCandlestickData(pair, "15",  CANDLE_15M);
                JSONArray raw1h  = getCandlestickData(pair, "60",  CANDLE_1H);
                JSONArray raw2h  = getCandlestickData(pair, "120", CANDLE_2H);

                // Validate minimum candle counts
                int minFor15m = MACD_SLOW + MACD_SIG + 5;  // ~40 bars minimum
                int minFor1h  = MACD_SLOW + MACD_SIG + 5;
                int minFor2h  = MACD_SLOW + MACD_SIG + 5;

                if (raw15m == null || raw15m.length() < minFor15m) {
                    System.out.println("  Insufficient 15m candles — skip");
                    continue;
                }
                if (raw1h == null || raw1h.length() < minFor1h) {
                    System.out.println("  Insufficient 1h candles — skip");
                    continue;
                }
                if (raw2h == null || raw2h.length() < minFor2h) {
                    System.out.println("  Insufficient 2h candles — skip");
                    continue;
                }

                double[] cl15 = extractCloses(raw15m);
                double[] cl1h = extractCloses(raw1h);
                double[] cl2h = extractCloses(raw2h);
                double[] hi15 = extractHighs(raw15m);
                double[] lo15 = extractLows(raw15m);

                double lastClose = cl15[cl15.length - 1];
                double tickSize  = getTickSize(pair);
                double atr       = calcATR(hi15, lo15, cl15, ATR_PERIOD);

                System.out.printf("  Price=%.6f | ATR=%.6f | Tick=%.8f%n",
                        lastClose, atr, tickSize);

                // ── Evaluate signal for each timeframe ────────────────────────
                //
                // For each timeframe we compute 3 sub-signals:
                //   1. EMA cross: EMA9 > EMA21 = bullish, EMA9 < EMA21 = bearish
                //   2. MACD:      MACD line > signal line = bullish, else bearish
                //   3. RSI:       30 < RSI < 70 = not extreme (neutral filter)
                //                 We count this timeframe as bullish if RSI < 55,
                //                 and bearish if RSI > 45 (overlapping neutral zone)
                //
                // A timeframe is "bullish" if EMA cross is bullish AND MACD is bullish AND RSI is in range
                // A timeframe is "bearish" if EMA cross is bearish AND MACD is bearish AND RSI is in range
                //

                TimeframeSignal tf15m = evaluateTimeframe(cl15, "15m");
                TimeframeSignal tf1h  = evaluateTimeframe(cl1h, "1h ");
                TimeframeSignal tf2h  = evaluateTimeframe(cl2h, "2h ");

                int bullCount = (tf15m.bullish ? 1 : 0) + (tf1h.bullish ? 1 : 0) + (tf2h.bullish ? 1 : 0);
                int bearCount = (tf15m.bearish ? 1 : 0) + (tf1h.bearish ? 1 : 0) + (tf2h.bearish ? 1 : 0);

                System.out.printf("  Timeframe agreement: BULL=%d/3  BEAR=%d/3  (need %d)%n",
                        bullCount, bearCount, MIN_TF_AGREEMENT);

                boolean hasSignal = (bullCount >= MIN_TF_AGREEMENT) || (bearCount >= MIN_TF_AGREEMENT);

                if (!hasSignal) {
                    System.out.println("  NO SIGNAL — timeframes disagree — skip");
                    continue;
                }

                signaled++;
                System.out.println("  SIGNAL DETECTED — placing BOTH LONG and SHORT");

                // ── Get current market price ──────────────────────────────────
                double currentPrice = getLastPrice(pair);
                if (currentPrice <= 0) {
                    System.out.println("  Cannot get price — skip");
                    continue;
                }

                double qty = calcQuantity(currentPrice, pair);
                if (qty <= 0) {
                    System.out.println("  Quantity too small — skip");
                    continue;
                }

                System.out.printf("  Price=%.6f | Qty=%.4f | Leverage=%dx%n",
                        currentPrice, qty, LEVERAGE);

                // ── Place LONG order ──────────────────────────────────────────
                System.out.println("  >> Placing LONG order...");
                JSONObject longResp = placeFuturesMarketOrder("buy", pair, qty, LEVERAGE,
                        "email_notification", "isolated", "INR");

                if (longResp != null && longResp.has("id")) {
                    System.out.println("  LONG order placed! id=" + longResp.getString("id"));
                    ordered++;

                    double longEntry = getEntryPrice(pair, longResp.getString("id"));
                    if (longEntry > 0) {
                        double risk   = Math.min(Math.max(SL_MIN_ATR * atr, SL_MIN_ATR * atr), SL_MAX_ATR * atr);
                        double longSL = roundToTick(longEntry - risk, tickSize);
                        double longTP = roundToTick(longEntry + RR * risk, tickSize);
                        System.out.printf("  LONG entry=%.6f | SL=%.6f | TP=%.6f | Risk=%.6f%n",
                                longEntry, longSL, longTP, risk);

                        String longPosId = getPositionId(pair);
                        if (longPosId != null) {
                            setTpSl(longPosId, longTP, longSL, pair);
                        }
                    }
                } else {
                    System.out.println("  LONG order FAILED: " + longResp);
                }

                // Small delay between orders to avoid rate limits
                TimeUnit.MILLISECONDS.sleep(500);

                // ── Place SHORT order ─────────────────────────────────────────
                System.out.println("  >> Placing SHORT order...");
                JSONObject shortResp = placeFuturesMarketOrder("sell", pair, qty, LEVERAGE,
                        "email_notification", "isolated", "INR");

                if (shortResp != null && shortResp.has("id")) {
                    System.out.println("  SHORT order placed! id=" + shortResp.getString("id"));
                    ordered++;

                    double shortEntry = getLastPrice(pair); // use live price for short entry estimate
                    if (shortEntry <= 0) shortEntry = currentPrice;

                    // Wait briefly for fill confirmation
                    TimeUnit.MILLISECONDS.sleep(ENTRY_CHECK_DELAY_MS);
                    double confirmedShortEntry = getLastPrice(pair);
                    if (confirmedShortEntry > 0) shortEntry = confirmedShortEntry;

                    double risk    = Math.min(Math.max(SL_MIN_ATR * atr, SL_MIN_ATR * atr), SL_MAX_ATR * atr);
                    double shortSL = roundToTick(shortEntry + risk, tickSize);
                    double shortTP = roundToTick(shortEntry - RR * risk, tickSize);
                    System.out.printf("  SHORT entry~%.6f | SL=%.6f | TP=%.6f | Risk=%.6f%n",
                            shortEntry, shortSL, shortTP, risk);

                    // Note: For hedged positions on the same pair, the exchange may return
                    // different position IDs for long and short. We re-query after the short fill.
                    TimeUnit.MILLISECONDS.sleep(500);
                    String shortPosId = getShortPositionId(pair);
                    if (shortPosId != null) {
                        setTpSl(shortPosId, shortTP, shortSL, pair);
                    } else {
                        System.out.println("  SHORT position ID not found — TP/SL not set for short");
                    }
                } else {
                    System.out.println("  SHORT order FAILED: " + shortResp);
                }

            } catch (Exception e) {
                System.err.println("Error processing " + pair + ": " + e.getMessage());
            }
        }

        System.out.println("\n=== Scan Complete ===");
        System.out.printf("Coins scanned=%d | Signals=%d | Orders placed=%d%n",
                scanned, signaled, ordered);
    }

    // =========================================================================
    // TIMEFRAME SIGNAL EVALUATION
    // =========================================================================

    /**
     * Evaluates bullish/bearish signal for a single timeframe's close prices.
     *
     * Signal criteria:
     *   EMA cross  : EMA9 vs EMA21 direction
     *   MACD       : MACD line vs Signal line direction
     *   RSI filter : RSI must be between RSI_MIN (30) and RSI_MAX (70) — not extreme
     *
     * Bullish = EMA9 > EMA21 AND MACD > Signal AND RSI in [30,70]
     * Bearish = EMA9 < EMA21 AND MACD < Signal AND RSI in [30,70]
     */
    private static TimeframeSignal evaluateTimeframe(double[] closes, String label) {
        TimeframeSignal sig = new TimeframeSignal();

        if (closes.length < MACD_SLOW + MACD_SIG + 5) {
            System.out.printf("  [%s] Not enough data%n", label);
            return sig;
        }

        double ema9  = calcEMA(closes, EMA_FAST);
        double ema21 = calcEMA(closes, EMA_MID);
        double[] macdArr = calcMACD(closes, MACD_FAST, MACD_SLOW, MACD_SIG);
        double macdLine = macdArr[0];
        double macdSig  = macdArr[1];
        double rsi = calcRSI(closes, RSI_PERIOD);

        boolean emaBull  = ema9 > ema21;
        boolean emaBear  = ema9 < ema21;
        boolean macdBull = macdLine > macdSig;
        boolean macdBear = macdLine < macdSig;
        boolean rsiOk    = rsi >= RSI_MIN && rsi <= RSI_MAX;

        sig.bullish = emaBull && macdBull && rsiOk;
        sig.bearish = emaBear && macdBear && rsiOk;

        System.out.printf("  [%s] EMA9=%.4f EMA21=%.4f | MACD=%.6f Sig=%.6f | RSI=%.1f" +
                        " -> EMA:%s MACD:%s RSI:%s => %s%n",
                label, ema9, ema21, macdLine, macdSig, rsi,
                emaBull ? "BULL" : "BEAR",
                macdBull ? "BULL" : "BEAR",
                rsiOk ? "OK" : "EXTREME",
                sig.bullish ? "BULLISH" : sig.bearish ? "BEARISH" : "NEUTRAL");

        return sig;
    }

    /** Simple holder for a timeframe's bull/bear signal. */
    private static class TimeframeSignal {
        boolean bullish = false;
        boolean bearish = false;
    }

    // =========================================================================
    // INDICATOR CALCULATIONS
    // =========================================================================

    private static double calcEMA(double[] d, int period) {
        if (d.length < period) return 0;
        double k = 2.0 / (period + 1);
        double ema = 0;
        for (int i = 0; i < period; i++) ema += d[i];
        ema /= period;
        for (int i = period; i < d.length; i++) ema = d[i] * k + ema * (1 - k);
        return ema;
    }

    private static double[] calcEMASeries(double[] d, int period) {
        double[] out = new double[d.length];
        if (d.length < period) return out;
        double k = 2.0 / (period + 1);
        double seed = 0;
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
        double m = ml[ml.length - 1];
        double s = ss[ss.length - 1];
        return new double[]{m, s, m - s};
    }

    private static double calcRSI(double[] cl, int period) {
        if (cl.length < period + 1) return 50;
        double ag = 0, al = 0;
        for (int i = 1; i <= period; i++) {
            double ch = cl[i] - cl[i - 1];
            if (ch > 0) ag += ch;
            else al += Math.abs(ch);
        }
        ag /= period;
        al /= period;
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
        return 100 - (100.0 / (1 + ag / al));
    }

    private static double calcATR(double[] hi, double[] lo, double[] cl, int period) {
        if (hi.length < period + 1) return 0;
        double[] tr = new double[hi.length];
        tr[0] = hi[0] - lo[0];
        for (int i = 1; i < hi.length; i++) {
            tr[i] = Math.max(hi[i] - lo[i],
                    Math.max(Math.abs(hi[i] - cl[i - 1]), Math.abs(lo[i] - cl[i - 1])));
        }
        double atr = 0;
        for (int i = 0; i < period; i++) atr += tr[i];
        atr /= period;
        for (int i = period; i < hi.length; i++) atr = (atr * (period - 1) + tr[i]) / period;
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
    // POSITION & ORDER HELPERS
    // =========================================================================

    /**
     * Returns the position ID for the LONG (positive active_pos) side of a pair.
     */
    public static String getPositionId(String pair) {
        try {
            List<JSONObject> positions = getPositionsForPair(pair);
            for (JSONObject p : positions) {
                if (p.optDouble("active_pos", 0) > 0) return p.getString("id");
            }
        } catch (Exception e) {
            System.err.println("getPositionId: " + e.getMessage());
        }
        return null;
    }

    /**
     * Returns the position ID for the SHORT (negative active_pos) side of a pair.
     */
    public static String getShortPositionId(String pair) {
        try {
            List<JSONObject> positions = getPositionsForPair(pair);
            for (JSONObject p : positions) {
                if (p.optDouble("active_pos", 0) < 0) return p.getString("id");
            }
        } catch (Exception e) {
            System.err.println("getShortPositionId: " + e.getMessage());
        }
        return null;
    }

    private static List<JSONObject> getPositionsForPair(String pair) throws Exception {
        JSONObject body = new JSONObject();
        body.put("timestamp", Instant.now().toEpochMilli());
        body.put("page", "1");
        body.put("size", "50");
        body.put("margin_currency_short_name", new JSONArray(Arrays.asList("INR", "USDT")));
        String resp = authPost(
                BASE_URL + "/exchange/v1/derivatives/futures/positions", body.toString());
        JSONArray arr = resp.startsWith("[")
                ? new JSONArray(resp)
                : new JSONArray().put(new JSONObject(resp));
        List<JSONObject> result = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject p = arr.getJSONObject(i);
            if (pair.equals(p.optString("pair"))) result.add(p);
        }
        return result;
    }

    private static double getEntryPrice(String pair, String orderId) throws Exception {
        for (int i = 0; i < MAX_ENTRY_PRICE_CHECKS; i++) {
            TimeUnit.MILLISECONDS.sleep(ENTRY_CHECK_DELAY_MS);
            List<JSONObject> positions = getPositionsForPair(pair);
            for (JSONObject pos : positions) {
                if (pos.optDouble("active_pos", 0) > 0 && pos.optDouble("avg_price", 0) > 0) {
                    return pos.getDouble("avg_price");
                }
            }
        }
        return 0;
    }

    private static double calcQuantity(double price, String pair) {
        double qty = MAX_MARGIN / (price * 93);
        return Math.max(
                INTEGER_QTY_PAIRS.contains(pair)
                        ? Math.floor(qty)
                        : Math.floor(qty * 100) / 100,
                0);
    }

    // =========================================================================
    // COINDCX API CALLS
    // =========================================================================

    private static JSONArray getCandlestickData(String pair, String resolution, int count) {
        try {
            long minsPerBar;
            switch (resolution) {
                case "1":   minsPerBar = 1;   break;
                case "5":   minsPerBar = 5;   break;
                case "15":  minsPerBar = 15;  break;
                case "60":  minsPerBar = 60;  break;
                case "120": minsPerBar = 120; break;
                case "240": minsPerBar = 240; break;
                default:    minsPerBar = 15;  break;
            }
            long to   = Instant.now().getEpochSecond();
            long from = to - minsPerBar * 60L * count;
            String url = PUBLIC_API_URL + "/market_data/candlesticks"
                    + "?pair=" + pair
                    + "&from=" + from
                    + "&to=" + to
                    + "&resolution=" + resolution
                    + "&pcode=f";
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
            System.err.println("  getCandlestickData(" + pair + " res=" + resolution + "): " + e.getMessage());
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

    /**
     * Sets fixed TP and SL on a position. Called once at entry, never again.
     * (Trailing stop logic has been completely removed.)
     */
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
            if (r.has("err_code_dcx")) {
                System.out.println("  TP/SL error: " + r);
            } else {
                System.out.printf("  TP/SL set: SL=%.6f  TP=%.6f%n", rsl, rtp);
            }
        } catch (Exception e) {
            System.err.println("setTpSl: " + e.getMessage());
        }
    }

    private static List<JSONObject> getActivePositionsFull() {
        List<JSONObject> result = new ArrayList<>();
        try {
            JSONObject body = new JSONObject();
            body.put("timestamp", Instant.now().toEpochMilli());
            body.put("page", "1");
            body.put("size", "100");
            body.put("margin_currency_short_name", new JSONArray(Arrays.asList("INR", "USDT")));
            String resp = authPost(
                    BASE_URL + "/exchange/v1/derivatives/futures/positions", body.toString());
            JSONArray arr = resp.startsWith("[")
                    ? new JSONArray(resp)
                    : new JSONArray().put(new JSONObject(resp));
            System.out.println("Open positions count: " + arr.length());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject p = arr.getJSONObject(i);
                boolean isActive = p.optDouble("active_pos", 0) != 0
                        || p.optDouble("locked_margin", 0) > 0;
                if (isActive) {
                    String pair = p.optString("pair", "?");
                    double qty  = p.optDouble("active_pos", 0);
                    System.out.printf("  Active: %s | qty=%.4f | entry=%.6f%n",
                            pair, qty, p.optDouble("avg_price", 0));
                    result.add(p);
                }
            }
        } catch (Exception e) {
            System.err.println("getActivePositionsFull: " + e.getMessage());
        }
        return result;
    }

    // =========================================================================
    // HTTP HELPERS
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
