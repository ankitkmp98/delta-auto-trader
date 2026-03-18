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
 * CoinDCX Futures Trader — 5-Filter Anti-Loss Edition
 *
 * SIGNAL LOGIC — 5 filters, all must pass:
 *   F0. ATR Guard       : ATR > 0.15% of price (skip choppy/flat markets)
 *   F1. 1H Macro Trend  : Price above/below 1H EMA-50
 *   F2. 15m Local Trend : EMA-9 > EMA-21 (bull) | EMA-9 < EMA-21 (bear)
 *   F3. MACD (15m)      : Line above signal AND histogram > 0 (bull) / < 0 (bear)
 *   F4. RSI (15m)       : Long 45-62 | Short 38-55
 *   F5. Volume + Stretch + Candle body confirmation
 *
 * SL/TP:
 *   Long  SL = max(swing low - 0.5xATR, EMA21 - 1.5xATR) capped at 2.5xATR from entry
 *   Short SL = min(swing high + 0.5xATR, EMA21 + 1.5xATR) capped at 2.5xATR from entry
 *   TP       = entry +/- 2.0 x actual risk (1:2 R:R enforced)
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
    private static final int    LEVERAGE               = 8;
    private static final int    MAX_ENTRY_PRICE_CHECKS = 10;
    private static final int    ENTRY_CHECK_DELAY_MS   = 1000;
    private static final long   TICK_CACHE_TTL_MS      = 3_600_000L;

    // Indicator parameters
    private static final int    EMA_FAST      = 9;
    private static final int    EMA_MID       = 21;
    private static final int    EMA_MACRO     = 50;
    private static final int    MACD_FAST     = 12;
    private static final int    MACD_SLOW     = 26;
    private static final int    MACD_SIG      = 9;
    private static final int    RSI_PERIOD    = 14;
    private static final int    ATR_PERIOD    = 14;
    private static final int    VOL_AVG_BARS  = 20;
    private static final int    SWING_BARS    = 15;

    // RSI tighter zones — prevents entering overbought/oversold reversals
    private static final double RSI_LONG_MIN  = 45.0;
    private static final double RSI_LONG_MAX  = 62.0;
    private static final double RSI_SHORT_MIN = 38.0;
    private static final double RSI_SHORT_MAX = 55.0;

    // Stretch filter — max distance of price from EMA-21
    private static final double MAX_STRETCH_ATR = 2.0;

    // Volume must exceed average by this multiplier
    private static final double VOL_MULTIPLIER = 1.2;

    // Skip pairs where ATR < 0.15% of price (choppy/sideways)
    private static final double MIN_ATR_PCT = 0.0015;

    // SL parameters
    private static final double SL_ATR_SWING_BUFFER = 0.5;
    private static final double SL_ATR_EMA_BUFFER   = 1.5;
    private static final double SL_MAX_ATR           = 2.5;
    private static final double RR                   = 2.0;

    private static final int CANDLE_15M = 120;
    private static final int CANDLE_1H  = 70;

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
        initInstrumentCache();
        Set<String> active = getActivePositions();
        System.out.println("Active positions: " + active);

        for (String pair : COINS_TO_TRADE) {
            try {
                if (active.contains(pair)) {
                    System.out.println("Skip " + pair + " — active position");
                    continue;
                }

                System.out.println("\n==== " + pair + " ====");

                // Fetch candles
                JSONArray raw15m = getCandlestickData(pair, "15", CANDLE_15M);
                JSONArray raw1h  = getCandlestickData(pair, "60", CANDLE_1H);

                if (raw15m == null || raw15m.length() < 60) {
                    System.out.println("  Insufficient 15m candles (" +
                            (raw15m == null ? 0 : raw15m.length()) + ") — skip");
                    continue;
                }
                if (raw1h == null || raw1h.length() < EMA_MACRO) {
                    System.out.println("  Insufficient 1H candles (" +
                            (raw1h == null ? 0 : raw1h.length()) + ") — skip");
                    continue;
                }

                double[] cl15 = extractCloses(raw15m);
                double[] op15 = extractOpens(raw15m);
                double[] hi15 = extractHighs(raw15m);
                double[] lo15 = extractLows(raw15m);
                double[] vo15 = extractVolumes(raw15m);
                double[] cl1h = extractCloses(raw1h);

                double lastClose = cl15[cl15.length - 1];
                double prevClose = cl15[cl15.length - 2];
                double prevOpen  = op15[op15.length - 2];
                double tickSize  = getTickSize(pair);
                double atr       = calcATR(hi15, lo15, cl15, ATR_PERIOD);

                System.out.printf("  Price=%.6f  ATR=%.6f  Tick=%.8f%n", lastClose, atr, tickSize);

                // F0: ATR Guard — skip choppy/flat pairs
                double atrPct = atr / lastClose;
                if (atrPct < MIN_ATR_PCT) {
                    System.out.printf("  F0 FAIL — ATR %.4f%% too small (choppy) — skip%n", atrPct * 100);
                    continue;
                }
                System.out.printf("  F0 OK — ATR %.4f%%%n", atrPct * 100);

                // F1: 1H Macro Trend
                double  ema1h     = calcEMA(cl1h, EMA_MACRO);
                boolean macroUp   = lastClose > ema1h;
                boolean macroDown = lastClose < ema1h;
                System.out.printf("  [F1] 1H EMA50=%.6f | %s%n", ema1h, macroUp ? "BULL" : "BEAR");
                if (!macroUp && !macroDown) { System.out.println("  F1 FAIL"); continue; }

                // F2: 15m Local Trend
                double  ema9      = calcEMA(cl15, EMA_FAST);
                double  ema21     = calcEMA(cl15, EMA_MID);
                boolean localUp   = ema9 > ema21;
                boolean localDown = ema9 < ema21;
                System.out.printf("  [F2] EMA9=%.6f EMA21=%.6f -> %s%n",
                        ema9, ema21, localUp ? "UP" : localDown ? "DOWN" : "flat");

                boolean trendUp   = macroUp   && localUp;
                boolean trendDown = macroDown && localDown;
                if (!trendUp && !trendDown) {
                    System.out.println("  F2 FAIL — macro/local misaligned — skip");
                    continue;
                }
                System.out.println("  F2 OK — " + (trendUp ? "BULLISH" : "BEARISH"));

                // F3: MACD
                double[] mv       = calcMACD(cl15, MACD_FAST, MACD_SLOW, MACD_SIG);
                double   macdLine = mv[0], macdSigV = mv[1], macdHist = mv[2];
                System.out.printf("  [F3] MACD=%.6f Sig=%.6f Hist=%.6f%n", macdLine, macdSigV, macdHist);
                boolean macdBull = macdLine > macdSigV && macdHist > 0;
                boolean macdBear = macdLine < macdSigV && macdHist < 0;
                if (trendUp   && !macdBull) { System.out.println("  F3 FAIL — skip"); continue; }
                if (trendDown && !macdBear) { System.out.println("  F3 FAIL — skip"); continue; }
                System.out.println("  F3 OK — MACD aligned");

                // F4: RSI — tighter zones prevent late entry
                double rsi = calcRSI(cl15, RSI_PERIOD);
                System.out.printf("  [F4] RSI=%.2f  (Long:45-62 | Short:38-55)%n", rsi);
                if (trendUp   && (rsi < RSI_LONG_MIN  || rsi > RSI_LONG_MAX))  { System.out.printf("  F4 FAIL — RSI %.2f outside long zone — skip%n",  rsi); continue; }
                if (trendDown && (rsi < RSI_SHORT_MIN || rsi > RSI_SHORT_MAX)) { System.out.printf("  F4 FAIL — RSI %.2f outside short zone — skip%n", rsi); continue; }
                System.out.printf("  F4 OK — RSI %.2f%n", rsi);

                // F5a: Stretch — price must be within 2xATR of EMA-21
                double stretch    = Math.abs(lastClose - ema21);
                double maxStretch = MAX_STRETCH_ATR * atr;
                System.out.printf("  [F5a] Stretch=%.6f MaxAllowed=%.6f%n", stretch, maxStretch);
                if (stretch > maxStretch) {
                    System.out.println("  F5a FAIL — price too extended from EMA-21 (late entry) — skip");
                    continue;
                }
                System.out.println("  F5a OK — price near EMA-21");

                // F5b: Volume must be above 1.2x average
                double avgVol = avgVolume(vo15, VOL_AVG_BARS);
                double curVol = vo15[vo15.length - 1];
                System.out.printf("  [F5b] CurVol=%.2f AvgVol=%.2f Required=%.2f%n",
                        curVol, avgVol, avgVol * VOL_MULTIPLIER);
                if (curVol < avgVol * VOL_MULTIPLIER) {
                    System.out.println("  F5b FAIL — low volume, weak signal — skip");
                    continue;
                }
                System.out.println("  F5b OK — volume confirmed");

                // F5c: Previous candle body must match trend direction
                boolean bullCandle = prevClose > prevOpen;
                boolean bearCandle = prevClose < prevOpen;
                System.out.printf("  [F5c] Prev candle: open=%.6f close=%.6f -> %s%n",
                        prevOpen, prevClose, bullCandle ? "BULL" : bearCandle ? "BEAR" : "DOJI");
                if (trendUp   && !bullCandle) { System.out.println("  F5c FAIL — prev candle bearish — skip"); continue; }
                if (trendDown && !bearCandle) { System.out.println("  F5c FAIL — prev candle bullish — skip"); continue; }
                System.out.println("  F5c OK — candle confirmed");

                // All filters passed
                String side = trendUp ? "buy" : "sell";
                System.out.println("  >>> ALL FILTERS PASSED -> " + side.toUpperCase() + " " + pair);

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

                double entry = getEntryPrice(pair, resp.getString("id"));
                if (entry <= 0) { System.out.println("  Could not confirm entry — TP/SL skipped"); continue; }
                System.out.printf("  Entry confirmed: %.6f%n", entry);

                // Structure-aware SL using better of swing-low or EMA-21 reference
                double slPrice, tpPrice;
                if ("buy".equalsIgnoreCase(side)) {
                    double swLow   = swingLow(lo15, SWING_BARS);
                    double slSwing = swLow - SL_ATR_SWING_BUFFER * atr;
                    double slEma   = ema21  - SL_ATR_EMA_BUFFER  * atr;
                    double rawSL   = Math.max(slSwing, slEma);
                    double capSL   = entry  - SL_MAX_ATR * atr;
                    slPrice = Math.max(rawSL, capSL);
                    tpPrice = entry + RR * (entry - slPrice);
                } else {
                    double swHigh  = swingHigh(hi15, SWING_BARS);
                    double slSwing = swHigh + SL_ATR_SWING_BUFFER * atr;
                    double slEma   = ema21  + SL_ATR_EMA_BUFFER  * atr;
                    double rawSL   = Math.min(slSwing, slEma);
                    double capSL   = entry  + SL_MAX_ATR * atr;
                    slPrice = Math.min(rawSL, capSL);
                    tpPrice = entry - RR * (slPrice - entry);
                }

                slPrice = roundToTick(slPrice, tickSize);
                tpPrice = roundToTick(tpPrice, tickSize);

                System.out.printf("  SL=%.6f | TP=%.6f | Risk=%.6f | Reward=%.6f | R:R=1:%.1f%n",
                        slPrice, tpPrice,
                        Math.abs(entry - slPrice),
                        Math.abs(tpPrice - entry),
                        RR);

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
    // INDICATORS
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

    private static double avgVolume(double[] vol, int bars) {
        double sum = 0;
        int start = Math.max(0, vol.length - bars - 1);
        int end   = vol.length - 1;
        for (int i = start; i < end; i++) sum += vol[i];
        int count = end - start;
        return count > 0 ? sum / count : 0;
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

    private static double[] extractVolumes(JSONArray a) {
        double[] o = new double[a.length()];
        for (int i = 0; i < a.length(); i++) {
            JSONObject bar = a.getJSONObject(i);
            o[i] = bar.has("volume") ? bar.getDouble("volume") : bar.optDouble("vol", 0);
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
            System.err.println("  getCandlestickData error: " + e.getMessage());
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

    private static double calcQuantity(double price, String pair) {
        double qty = MAX_MARGIN / (price * 93);
        return Math.max(
                INTEGER_QTY_PAIRS.contains(pair)
                        ? Math.floor(qty)
                        : Math.floor(qty * 100) / 100,
                0);
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
            System.err.println("getLastPrice error: " + e.getMessage());
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
                JSONObject p    = arr.getJSONObject(i);
                String    pair  = p.optString("pair", "");
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
// import java.util.Arrays;
// import java.util.HashSet;
// import java.util.Map;
// import java.util.Set;
// import java.util.concurrent.ConcurrentHashMap;
// import java.util.concurrent.TimeUnit;
// import java.util.stream.Collectors;
// import java.util.stream.Stream;

// public class CoinDCXFuturesTrader8C_BUY_SELL_NEW_LOGIC_THREE {


//      private static final String API_KEY = System.getenv("DELTA_API_KEY");
//     private static final String API_SECRET = System.getenv("DELTA_API_SECRET");
//     private static final String BASE_URL = "https://api.coindcx.com";
//     private static final String PUBLIC_API_URL = "https://public.coindcx.com";
//     private static final double MAX_MARGIN = 1200.0;
//     private static final int MAX_ORDER_STATUS_CHECKS = 10;
//     private static final int ORDER_CHECK_DELAY_MS = 1000;
//     private static final long TICK_SIZE_CACHE_TTL_MS = 3600000; // 1 hour cache
//     private static final int LOOKBACK_PERIOD = 12; // Minutes for trend analysis (changed from hours)
//     private static final double TREND_THRESHOLD = 0.01; // 2% change threshold for trend
//     private static final double TP_PERCENTAGE = 0.05; // 3% take profit
//     private static final double SL_PERCENTAGE = 0.08; // 5% stop loss

//     // Cache for instrument details with timestamp
//     private static final Map<String, JSONObject> instrumentDetailsCache = new ConcurrentHashMap<>();
//     private static long lastInstrumentUpdateTime = 0;

//     private static final String[] COIN_SYMBOLS = {
//          //   "1000SATS", "1000X", "ACT", "ADA", "AIXBT", "AI16Z", "ALGO", "ALT", "API3",
//           //  "ARB", "ARC", "AVAAI", "BAKE", "BB", "BIO", "BLUR", "BMT", "BONK", "COOKIE",
//          //   "DOGE", "DOGS", "DYDX", "EIGEN", "ENA", "EOS", "ETHFI", "FARTCOIN", "FLOKI",
//          //   "GALA", "GLM", "GOAT", "GRIFFAIN", "HBAR", "HIVE", "IO", "IOTA", "JASMY",
//          //   "JUP", "KAITO", "LDO", "LISTA", "MANA", "MANTA", "MEME", "MELANIA", "MOODENG",
//           //  "MOVE", "MUBARAK", "NEIRO", "NOT", "ONDO", "OP", "PEOPLE", "PEPE", "PENGU",
//           //  "PI", "PNUT", "POL", "POPCAT", "RARE", "RED", "RSR", "SAGA", "SAND", "SEI",
//           //  "SHIB", "SOLV", "SONIC", "SPX", "STX", "SUN", "SWARMS", "SUSHI", "TST", "TRX",
//           //  "USUAL", "VINE", "VIRTUAL", "WIF", "WLD", "XAI", "XLM", "XRP", "ZK","TAO",
//           //  "TRUMP","PERP","OM","BNB","LINK","GMT","LAYER","AVAX","HIGH","ALPACA","FLM",
//           //  "BSW","FIL","DOT","LTC","ARK","ENJ","RENDER","CRV","BCH","OGN","1000SHIB","AAVE",
//           //  "ORCA","NEAR","T","FUN","VTHO","ALCH","GAS","TIA","MBOX","APT","ORDI","INJ","BEL",
//           //  "PARTI","BIGTIME","ETC","BOME","UNI","TON","1000BONK","ACH","XLM","ATOM","LEVER","S"

//          "SOL", "1000SHIB", "API3", "DOGS", "KAVA", "ARK", "VOXEL", "SXP", "FLOW", "CHESS",
// "AXL", "MANA", "BNB", "1000BONK", "ALPHA", "NEAR", "TRB", "GRT", "WIF", "RSR",
// "QTUM", "AVAX", "BIGTIME", "COTI", "PONKE", "ETHFI", "ICP", "VET", "ACH", "MINA",
// "COMP", "XAI", "JTO", "USTC", "SPELL", "KNC", "INJ", "BLUR", "DYM", "SNX",
// "IMX", "1000WHY", "ALGO", "CRV", "JUP", "ZEN", "BAT", "SAGA", "AAVE", "SEI",
// "KAIA", "NFP", "PEOPLE", "SUI", "ONE", "RENDER", "POLYX", "ENS", "MOVR", "BRETT",
// "ETH", "OMNI", "MKR", "AR", "CFX", "ID", "CELR", "LDO", "UNI", "LTC",
// "TAO", "CKB", "FET", "STX", "SAND", "XLM", "EGLD", "BOME", "HOT", "LUNA2",
// "ADA", "RVN", "GLM", "MASK", "STRK", "GALA", "YFI", "IOST", "OP",
// "1000PEPE", "TRUMP", "ZIL", "RPL", "WLD", "DOGE", "XMR", "ONDO", "APT", "HIVE",
// "FIL", "TIA", "CHZ", "ETC", "LINK", "ORDI", "ATOM", "TON", "TRX", "HBAR",
// "NEO", "IOTA", "GMX", "QNT", "FTM", "VANA", "FLUX", "DASH", "ZRX", "MANTA",
// "CAKE", "PYTH", "ARB", "SFP", "METIS", "LRC", "SKL", "ZEC", "RUNE", "ALICE",
// "ANKR", "XTZ", "GTC", "ROSE", "BCH", "CELO", "BAND", "1INCH", "SUPER", "ILV",
// "SSV", "ARPA", "FXS", "UMA", "MTL", "DEGEN", "XVS", "ACE", "1000FLOKI", "AKT",
// "ASTR", "TWT", "CTSI", "VIRTUAL", "CHR", "EDU", "PROM", "KSM", "BICO", "DENT",
// "ALT", "C98", "RLC", "SUN", "PENDLE", "BANANA", "NMR", "POL", "MAGIC", "VET",
// "MOODENG", "WAXP", "ZK", "GAS", "ALPACA", "TNSR", "PHB", "POWR", "LSK", "FIO",
// "DEFI", "USDC", "KAS", "1000SATS", "ARKM", "PIXEL", "MAV", "REI", "ZRO", "COOKIE",
// "JOE", "BNT", "CYBER", "SCRT", "XRP", "VELODROME", "ONG", "AERO", "HOOK", "AI16Z",
// "KMNO", "LPT", "THETA", "NTRN", "VIC", "RAYSOL", "PARTI", "MELANIA", "MEW", "EIGEN",
// "XVG", "MYRO", "IO", "SHELL", "AUCTION", "STORJ", "SWELL", "COS", "FORTH", "BEL",
// "PNUT", "HIGH", "ENJ", "LISTA", "ZETA", "MORPHO", "WOO", "MLN", "COW", "HEI",
// "DEXE", "OM", "RED", "GHST", "STEEM", "LOKA", "ACT", "KAITO", "DIA", "SUSHI",
// "AGLD", "TLM", "BMT", "MAVIA", "ALCH", "VTHO", "FUN", "POPCAT", "TURBO", "1000CHEEMS",
// "1000CAT", "1000LUNC", "1000RATS", "1000000MOG", "1MBABYDOGE", "1000XEC", "1000X",
// "BTCDOM", "USUAL", "PERP", "LAYER", "NKN", "MUBARAK", "FARTCOIN", "GOAT", "LEVER",
// "SOLV", "S", "ARC", "VINE", "RARE", "GPS", "IP", "AVAAI", "KOMA", "HFT"
         

// //  "BTC", "ETH", "VOXEL", "SOL", "NKN", "MAGIC", "XRP", "1000PEPE", "FARTCOIN", "DOGE",
// // "TAO", "SUI", "TRUMP", "PERP", "OM","ADA", "BNB", "LINK", "PEOPLE", "GMT",
// // "FET", "MUBARAK", "WIF", "LAYER", "AVAX", "HIGH", "ALPACA", "FLM", "ENA", "BSW",
// //  "FIL", "DOT", "LTC", "ARK", "ENJ", "EOS", "USUAL", "TRX", "RENDER", "CRV",
// // "BCH", "OGN", "ONDO", "1000SHIB", "BIO", "AAVE", "ORCA", "NEAR", "PNUT", "T",
// //  "POPCAT", "FUN", "VTHO", "WLD", "ALCH", "GAS", "XAI", "GALA", "TIA", "MBOX",
// //  "APT", "ORDI", "HBAR", "OP", "INJ", "BEL", "JASMY", "RED", "KAITO", "PARTI",
// //  "ARB", "BIGTIME", "AI16Z", "1000SATS", "NEIRO", "ETC", "JUP", "BOME", "UNI", "TON",
// //  "1000BONK", "ACH", "XLM", "GOAT", "SAND", "ATOM", "LEVER", "S", "CAKE", "NOT",
// //  "LOKA", "ARC", "VINE", "PENDLE", "LDO", "SEI", "RAYSOL", "APE", "RARE",
// // "WAXP", "GPS", "IP", "COTI", "AVAAI", "KOMA", "HFT", "ARKM", "ANIME", "ACT",
// //  "ALGO", "VIRTUAL", "MAVIA", "ALICE", "MANTA", "ZRO", "AGLD", "STX", "API3", "PIXEL",
// // "MELANIA", "NEO", "IMX", "1000WHY", "MANA", "ACE", "SWARMS", "MKR", "AUCTION", "ICP",
// // "PORTAL", "THETA", "CHESS", "ZEREBRO", "1000FLOKI", "PENGU", "STRK", "CATI", "TRB", "SAGA",
// //  "NIL", "TURBO", "AIXBT", "W", "PYTH", "LISTA", "CHILLGUY", "GRIFFAIN", "REZ", "IO",
// //  "UXLINK", "SHELL", "BTCDOM", "POL", "GRT", "BRETT", "DYDX", "JTO", "MOODENG", "ETHFI",
// // "OMNI", "DOGS", "EIGEN", "ENS", "XMR", "D", "SOLV", "VET", "RUNE", "MEW",
// //  "AXS", "XCN", "SXP", "MASK", "BMT", "BANANA", "NFP", "XTZ", "FORTH", "ALPHA",
// // "REI", "AR", "YGG", "PAXG", "SPX", "TRU", "ID", "GTC", "CHZ", "BLUR",
// // "GRASS", "KAVA", "SPELL", "RSR", "FIDA", "MORPHO", "VANA", "RPL", "ANKR", "TLM",
// // "CFX", "HIPPO", "TST", "ZEN", "ME", "AI", "MOVR", "GLM", "ZIL", "1000RATS",
// // "HOOK", "ALT", "ZK", "COW", "SUSHI", "MLN", "SANTOS", "1MBABYDOGE", "SNX",
// //  "STORJ", "BEAMX", "WOO", "B3", "AEVO", "CTSI", "1000LUNC", "OXT", "ILV", "IOTA",
// // "QTUM", "EPIC", "NEIROETH", "THE", "EDU", "ZEC", "AERO", "SKL", "ARPA", "BAN",
// //  "COMP", "CHR", "NMR", "ZETA", "LUMIA", "COOKIE", "PHB", "MINA", "1000CHEEMS", "1000CAT",
// // "GHST", "KAS", "SUPER", "ROSE", "IOTX", "DYM", "EGLD", "SONIC", "RDNT", "LPT",
// // "LUNA2", "PLUME", "XVG", "MYRO", "LQTY", "USTC", "C98", "SCR", "BB", "STEEM",
// //  "ONE", "FLOW", "QNT", "SSV", "POWR", "DEXE", "CGPT", "VANRY", "POLYX", "ZRX",
// //  "YFI", "TNSR", "GMX", "SYS", "1INCH", "CELO", "METIS", "1000X", "HEI", "ONT",
// //  "KSM", "KDA", "IOST", "BAT", "CETUS", "DF", "LRC", "HIVE", "DEGEN",
// // "MTL", "SAFE", "CELR", "AVA", "CKB", "RIF", "FIO", "1000000MOG", "KNC", "ICX",
// // "CYBER", "RONIN", "ONG", "VVV", "FXS", "MAV", "DEGO", "DASH", "ASTR", "PHA",
// // "AXL", "BICO", "BAND", "SCRT", "HOT", "TOKEN", "STG", "PONKE", "DODOX", "DUSK",
// // "SYN", "RVN", "UMA", "PIPPIN", "DENT", "PROM", "FLUX", "VELODROME", "SWELL", "MOCA",
// // "ATA", "KAIA", "ATH", "XVS", "G", "LSK", "SUN", "NTRN", "RLC", "JOE",
// // "1000XEC", "VIC", "SFP", "TWT", "QUICK", "BSV", "DIA", "BNT", "ACX", "COS",
// // "ETHW", "DRIFT", "AKT", "KMNO", "SLERF", "DEFI", "USDC"

//     };

//     private static final Set<String> INTEGER_QUANTITY_PAIRS = Stream.of(COIN_SYMBOLS)
//             .flatMap(symbol -> Stream.of("B-" + symbol + "_USDT", symbol + "_USDT"))
//             .collect(Collectors.toCollection(HashSet::new));

//     private static final String[] COINS_TO_TRADE = Stream.of(COIN_SYMBOLS)
//             .map(symbol -> "B-" + symbol + "_USDT")
//             .toArray(String[]::new);

//     public static void main(String[] args) {
//         initializeInstrumentDetails();
//         Set<String> activePairs = getActivePositions();
//         System.out.println("\nActive Positions: " + activePairs);

//         for (String pair : COINS_TO_TRADE) {
//             try {
//                 if (activePairs.contains(pair)) {
//                     System.out.println("\n⏩ ⚠️ Skipping " + pair + " - Active position exists");
//                     continue;
//                 }

//                 String side = determinePositionSide(pair);
//                 if (side == null) continue; // No trade when RSI is neutral

// //               if ("buy".equalsIgnoreCase(side)) {
// //     System.out.println("⏩ Skipping " + pair + " - Buy (Long) side is disabled");
// //     continue;
// // }

//                 //-----------------------line number 120,121,122 is added intentionally to skip long or buy position order----------------

//                 int leverage = 8; // Default leverage

//                 double currentPrice = getLastPrice(pair);
//                 System.out.println("\nCurrent price for " + pair + ": " + currentPrice + " USDT");

//                 if (currentPrice <= 0) {
//                     System.out.println("❌ Invalid price received, aborting for this pair");
//                     continue;
//                 }

//                 double quantity = calculateQuantity(currentPrice, leverage, pair);
//                 System.out.println("Calculated quantity: " + quantity);

//                 if (quantity <= 0) {
//                     System.out.println("❌ Invalid quantity calculated, aborting for this pair");
//                     continue;
//                 }

//                 JSONObject orderResponse = placeFuturesMarketOrder(side, pair, quantity, leverage,
//                         "email_notification", "isolated", "INR");

//                 if (orderResponse == null || !orderResponse.has("id")) {
//                     System.out.println("❌ Failed to place order for this pair");
//                     continue;
//                 }

//                 String orderId = orderResponse.getString("id");
//                 System.out.println("✅ Order placed successfully! Order ID: " + orderId);
//                 System.out.println("Side: " + side.toUpperCase());

//                 double entryPrice = getEntryPriceFromPosition(pair, orderId);
//                 if (entryPrice <= 0) {
//                     System.out.println("❌ Could not determine entry price, aborting for this pair");
//                     continue;
//                 }

//                 System.out.println("Entry Price: " + entryPrice + " INR");

//                 // Calculate fixed percentage TP/SL prices
//                 double tpPrice, slPrice;
//                 if ("buy".equalsIgnoreCase(side)) {
//                     tpPrice = entryPrice * (1 + TP_PERCENTAGE);
//                     slPrice = entryPrice * (1 - SL_PERCENTAGE);
//                 } else {
//                     tpPrice = entryPrice * (1 - TP_PERCENTAGE);
//                     slPrice = entryPrice * (1 + SL_PERCENTAGE);
//                 }

//                 // Round to tick size
//                 double tickSize = getTickSizeForPair(pair);
//                 tpPrice = Math.round(tpPrice / tickSize) * tickSize;
//                 slPrice = Math.round(slPrice / tickSize) * tickSize;

//                 System.out.println("Take Profit Price: " + tpPrice);
//                 System.out.println("Stop Loss Price: " + slPrice);

//                 String positionId = getPositionId(pair);
//                 if (positionId != null) {
//                     setTakeProfitAndStopLoss(positionId, tpPrice, slPrice, side, pair);
//                 } else {
//                     System.out.println("❌ Could not get position ID for TP/SL");
//                 }

//             } catch (Exception e) {
//                 System.err.println("❌ Error processing pair " + pair + ": " + e.getMessage());
//             }
//         }
//     }

//     private static String determinePositionSide(String pair) {
//         try {
//             // Changed resolution from "1h" to "5m" to match 5-minute lookback
//             JSONArray candles = getCandlestickData(pair, "15m", LOOKBACK_PERIOD);

//             if (candles == null || candles.length() < 2) {
//                 System.out.println("⚠️ Not enough data for trend analysis, using default strategy");
//                 return Math.random() > 0.5 ? "sell" : "buy";
//             }

//             double firstClose = candles.getJSONObject(0).getDouble("close");
//             double lastClose = candles.getJSONObject(candles.length() - 1).getDouble("close");
//             double priceChange = (lastClose - firstClose) / firstClose;

//             System.out.println("5-Minute Trend Analysis for " + pair + ":");
//             System.out.println("First Close: " + firstClose);
//             System.out.println("Last Close: " + lastClose);
//             System.out.println("Price Change: " + (priceChange * 100) + "%");

//             if (priceChange > TREND_THRESHOLD) {
//                 System.out.println("📈 Uptrend detected - Going LONG");
//                 return "buy";
//             } else if (priceChange < -TREND_THRESHOLD) {
//                 System.out.println("📉 Downtrend detected - Going SHORT");
//                 return "sell";
//             } else {
//                 System.out.println("➡️ Sideways market - Using RSI for decision");
//                 return determineSideWithRSI(candles);
//             }
//         } catch (Exception e) {
//             System.err.println("❌ Error determining position side: " + e.getMessage());
//             return Math.random() > 0.5 ? "sell" : "buy";
//         }
//     }

//     private static JSONArray getCandlestickData(String pair, String resolution, int periods) {
//         try {
//             long endTime = Instant.now().toEpochMilli();
//             long startTime = endTime - TimeUnit.HOURS.toMillis(periods);

//             String url = PUBLIC_API_URL + "/market_data/candlesticks?pair=" + pair +
//                     "&from=" + startTime + "&to=" + endTime +
//                     "&resolution=" + resolution + "&pcode=#";

//             HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
//             conn.setRequestMethod("GET");

//             if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
//                 String response = readAllLines(conn.getInputStream());
//                 JSONObject jsonResponse = new JSONObject(response);
//                 if (jsonResponse.getString("s").equals("ok")) {
//                     return jsonResponse.getJSONArray("data");
//                 }
//             }
//         } catch (Exception e) {
//             System.err.println("❌ Error fetching candlestick data: " + e.getMessage());
//         }
//         return null;
//     }

//     private static String determineSideWithRSI(JSONArray candles) {
//         try {
//             double[] closes = new double[candles.length()];
//             for (int i = 0; i < candles.length(); i++) {
//                 closes[i] = candles.getJSONObject(i).getDouble("close");
//             }

//             double avgGain = 0;
//             double avgLoss = 0;
//             int rsiPeriod = 14;

//             for (int i = 1; i <= rsiPeriod; i++) {
//                 double change = closes[i] - closes[i-1];
//                 if (change > 0) {
//                     avgGain += change;
//                 } else {
//                     avgLoss += Math.abs(change);
//                 }
//             }

//             avgGain /= rsiPeriod;
//             avgLoss /= rsiPeriod;

//             for (int i = rsiPeriod + 1; i < closes.length; i++) {
//                 double change = closes[i] - closes[i-1];
//                 if (change > 0) {
//                     avgGain = (avgGain * (rsiPeriod - 1) + change) / rsiPeriod;
//                     avgLoss = (avgLoss * (rsiPeriod - 1)) / rsiPeriod;
//                 } else {
//                     avgLoss = (avgLoss * (rsiPeriod - 1) + Math.abs(change)) / rsiPeriod;
//                     avgGain = (avgGain * (rsiPeriod - 1)) / rsiPeriod;
//                 }
//             }

//             double rs = avgGain / avgLoss;
//             double rsi = 100 - (100 / (1 + rs));

//             System.out.println("RSI: " + rsi);

//             if (rsi < 30) {
//                 System.out.println("🔽 Oversold - Going LONG");
//                 return "buy";
//             } else if (rsi > 70) {
//                 System.out.println("🔼 Overbought - Going SHORT");
//                 return "sell";
//             } else {
//                 System.out.println("⏸ Neutral RSI - No trade");
//                 return null;
//             }
//         } catch (Exception e) {
//             System.err.println("❌ Error calculating RSI: " + e.getMessage());
//             return Math.random() > 0.5 ? "sell" : "buy";
//         }
//     }

//     private static void initializeInstrumentDetails() {
//         try {
//             long currentTime = System.currentTimeMillis();
//             if (currentTime - lastInstrumentUpdateTime > TICK_SIZE_CACHE_TTL_MS) {
//                 System.out.println("ℹ️ Fetching latest instrument details from API...");
//                 instrumentDetailsCache.clear();

//                 String activeInstrumentsResponse = sendPublicRequest(
//                         BASE_URL + "/exchange/v1/derivatives/futures/data/active_instruments");

//                 JSONArray activeInstruments = new JSONArray(activeInstrumentsResponse);

//                 for (int i = 0; i < activeInstruments.length(); i++) {
//                     String pair = activeInstruments.getString(i);
//                     try {
//                         String instrumentResponse = sendPublicRequest(
//                                 BASE_URL + "/exchange/v1/derivatives/futures/data/instrument?pair=" + pair);
//                         JSONObject instrumentDetails = new JSONObject(instrumentResponse).getJSONObject("instrument");
//                         instrumentDetailsCache.put(pair, instrumentDetails);
//                     } catch (Exception e) {
//                         System.err.println("❌ Error fetching details for " + pair + ": " + e.getMessage());
//                     }
//                 }

//                 lastInstrumentUpdateTime = currentTime;
//                 System.out.println("✅ Successfully updated instrument details for " + instrumentDetailsCache.size() + " pairs");
//             }
//         } catch (Exception e) {
//             System.err.println("❌ Error initializing instrument details: " + e.getMessage());
//         }
//     }

//     private static String sendPublicRequest(String endpoint) throws IOException {
//         HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
//         conn.setRequestMethod("GET");
//         if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
//             return readAllLines(conn.getInputStream());
//         }
//         throw new IOException("HTTP error code: " + conn.getResponseCode());
//     }

//     private static double getTickSizeForPair(String pair) {
//         if (System.currentTimeMillis() - lastInstrumentUpdateTime > TICK_SIZE_CACHE_TTL_MS) {
//             initializeInstrumentDetails();
//         }

//         JSONObject instrumentDetails = instrumentDetailsCache.get(pair);
//         if (instrumentDetails != null) {
//             return instrumentDetails.optDouble("price_increment", 0.0001);
//         }
//         return 0.0001;
//     }

//     private static double getEntryPriceFromPosition(String pair, String orderId) throws Exception {
//         System.out.println("\nChecking position for entry price...");
//         for (int attempts = 0; attempts < MAX_ORDER_STATUS_CHECKS; attempts++) {
//             TimeUnit.MILLISECONDS.sleep(ORDER_CHECK_DELAY_MS);
//             JSONObject position = findPosition(pair);
//             if (position != null && position.optDouble("avg_price", 0) > 0) {
//                 return position.getDouble("avg_price");
//             }
//         }
//         return 0;
//     }

//     private static JSONObject findPosition(String pair) throws Exception {
//         JSONObject body = new JSONObject();
//         body.put("timestamp", Instant.now().toEpochMilli());
//         body.put("page", "1");
//         body.put("size", "10");
//         body.put("margin_currency_short_name", new String[]{"INR", "USDT"});

//         String response = sendAuthenticatedRequest(
//                 BASE_URL + "/exchange/v1/derivatives/futures/positions",
//                 body.toString(),
//                 generateHmacSHA256(API_SECRET, body.toString())
//         );

//         if (response.startsWith("[")) {
//             for (int i = 0; i < new JSONArray(response).length(); i++) {
//                 JSONObject position = new JSONArray(response).getJSONObject(i);
//                 if (position.getString("pair").equals(pair)) return position;
//             }
//         } else {
//             JSONObject position = new JSONObject(response);
//             if (position.getString("pair").equals(pair)) return position;
//         }
//         return null;
//     }

//     private static double calculateQuantity(double currentPrice, int leverage, String pair) {
//         double quantity = MAX_MARGIN / (currentPrice * 93);
//         return Math.max(INTEGER_QUANTITY_PAIRS.contains(pair) ?
//                 Math.floor(quantity) : Math.floor(quantity * 100) / 100, 0);
//     }

//     public static double getLastPrice(String pair) {
//         try {
//             HttpURLConnection conn = (HttpURLConnection) new URL(
//                     PUBLIC_API_URL + "/market_data/trade_history?pair=" + pair + "&limit=1"
//             ).openConnection();
//             if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
//                 String response = readAllLines(conn.getInputStream());
//                 return response.startsWith("[") ?
//                         new JSONArray(response).getJSONObject(0).getDouble("p") :
//                         new JSONObject(response).getDouble("p");
//             }
//         } catch (Exception e) {
//             System.err.println("❌ Error getting last price: " + e.getMessage());
//         }
//         return 0;
//     }

//     public static JSONObject placeFuturesMarketOrder(String side, String pair, double totalQuantity, int leverage,
//                                                      String notification, String positionMarginType, String marginCurrency) {
//         try {
//             JSONObject order = new JSONObject();
//             order.put("side", side.toLowerCase());
//             order.put("pair", pair);
//             order.put("order_type", "market_order");
//             order.put("total_quantity", totalQuantity);
//             order.put("leverage", leverage);
//             order.put("notification", notification);
//             order.put("time_in_force", "good_till_cancel");
//             order.put("hidden", false);
//             order.put("post_only", false);
//             order.put("position_margin_type", positionMarginType);
//             order.put("margin_currency_short_name", marginCurrency);

//             JSONObject body = new JSONObject();
//             body.put("timestamp", Instant.now().toEpochMilli());
//             body.put("order", order);

//             String response = sendAuthenticatedRequest(
//                     BASE_URL + "/exchange/v1/derivatives/futures/orders/create",
//                     body.toString(),
//                     generateHmacSHA256(API_SECRET, body.toString())
//             );
//             return response.startsWith("[") ?
//                     new JSONArray(response).getJSONObject(0) :
//                     new JSONObject(response);
//         } catch (Exception e) {
//             System.err.println("❌ Error placing futures market order: " + e.getMessage());
//             return null;
//         }
//     }

//     public static void setTakeProfitAndStopLoss(String positionId, double takeProfitPrice, double stopLossPrice,
//                                                 String side, String pair) {
//         try {
//             JSONObject payload = new JSONObject();
//             payload.put("timestamp", Instant.now().toEpochMilli());
//             payload.put("id", positionId);

//             double tickSize = getTickSizeForPair(pair);
//             double roundedTpPrice = Math.round(takeProfitPrice / tickSize) * tickSize;
//             double roundedSlPrice = Math.round(stopLossPrice / tickSize) * tickSize;

//             JSONObject takeProfit = new JSONObject();
//             takeProfit.put("stop_price", roundedTpPrice);
//             takeProfit.put("limit_price", roundedTpPrice);
//             takeProfit.put("order_type", "take_profit_market");

//             JSONObject stopLoss = new JSONObject();
//             stopLoss.put("stop_price", roundedSlPrice);
//             stopLoss.put("limit_price", roundedSlPrice);
//             stopLoss.put("order_type", "stop_market");

//             payload.put("take_profit", takeProfit);
//             payload.put("stop_loss", stopLoss);

//             System.out.println("Final TP Price: " + roundedTpPrice + " | Final SL Price: " + roundedSlPrice);

//             String response = sendAuthenticatedRequest(
//                     BASE_URL + "/exchange/v1/derivatives/futures/positions/create_tpsl",
//                     payload.toString(),
//                     generateHmacSHA256(API_SECRET, payload.toString())
//             );

//             JSONObject tpslResponse = new JSONObject(response);
//             if (!tpslResponse.has("err_code_dcx")) {
//                 System.out.println("✅ TP/SL set successfully!");
//             } else {
//                 System.out.println("❌ Failed to set TP/SL: " + tpslResponse);
//             }
//         } catch (Exception e) {
//             System.err.println("❌ Error setting TP/SL: " + e.getMessage());
//         }
//     }

//     public static String getPositionId(String pair) {
//         try {
//             JSONObject position = findPosition(pair);
//             return position != null ? position.getString("id") : null;
//         } catch (Exception e) {
//             System.err.println("❌ Error getting position ID: " + e.getMessage());
//             return null;
//         }
//     }

//     private static String sendAuthenticatedRequest(String endpoint, String jsonBody, String signature) throws IOException {
//         HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
//         conn.setRequestMethod("POST");
//         conn.setRequestProperty("Content-Type", "application/json");
//         conn.setRequestProperty("X-AUTH-APIKEY", API_KEY);
//         conn.setRequestProperty("X-AUTH-SIGNATURE", signature);
//         conn.setDoOutput(true);

//         try (OutputStream os = conn.getOutputStream()) {
//             os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
//         }

//         return readAllLines(conn.getInputStream());
//     }

//     private static String readAllLines(InputStream is) throws IOException {
//         return new BufferedReader(new InputStreamReader(is)).lines()
//                 .collect(Collectors.joining("\n"));
//     }

//     public static String generateHmacSHA256(String secret, String payload) {
//         try {
//             Mac sha256_HMAC = Mac.getInstance("HmacSHA256");
//             sha256_HMAC.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
//             byte[] bytes = sha256_HMAC.doFinal(payload.getBytes(StandardCharsets.UTF_8));
//             StringBuilder hexString = new StringBuilder();
//             for (byte b : bytes) hexString.append(String.format("%02x", b));
//             return hexString.toString();
//         } catch (Exception e) {
//             throw new RuntimeException("Error generating HMAC signature", e);
//         }
//     }

//     private static Set<String> getActivePositions() {
//         Set<String> activePairs = new HashSet<>();
//         try {
//             JSONObject body = new JSONObject();
//             body.put("timestamp", Instant.now().toEpochMilli());
//             body.put("page", "1");
//             body.put("size", "100");
//             body.put("margin_currency_short_name", new String[]{"INR", "USDT"});

//             String response = sendAuthenticatedRequest(
//                     BASE_URL + "/exchange/v1/derivatives/futures/positions",
//                     body.toString(),
//                     generateHmacSHA256(API_SECRET, body.toString())
//             );

//             JSONArray positions = response.startsWith("[") ?
//                     new JSONArray(response) :
//                     new JSONArray().put(new JSONObject(response));

//             System.out.println("\n=== Raw Position Data ===");
//             System.out.println("Total positions received: " + positions.length());

//             for (int i = 0; i < positions.length(); i++) {
//                 JSONObject position = positions.getJSONObject(i);
//                 String pair = position.optString("pair", "");

//                 boolean isActive = position.optDouble("active_pos", 0) > 0 ||
//                         position.optDouble("locked_margin", 0) > 0 ||
//                         position.optDouble("avg_price", 0) > 0 ||
//                         position.optDouble("take_profit_trigger", 0) > 0 ||
//                         position.optDouble("stop_loss_trigger", 0) > 0;

//                 if (isActive) {
//                     System.out.printf("Active Position: %s | ActivePos: %.2f | Margin: %.4f | Entry: %.6f | TP: %.4f | SL: %.4f%n",
//                             pair,
//                             position.optDouble("active_pos", 0),
//                             position.optDouble("locked_margin", 0),
//                             position.optDouble("avg_price", 0),
//                             position.optDouble("take_profit_trigger", 0),
//                             position.optDouble("stop_loss_trigger", 0));
//                     activePairs.add(pair);
//                 }
//             }

//             System.out.println("\n=== Final Active Positions ===");
//             if (activePairs.isEmpty()) {
//                 System.out.println("No active positions found.");
//             } else {
//                 activePairs.forEach(pair -> System.out.println("- " + pair));
//                 System.out.println("Total active positions detected: " + activePairs.size());
//             }
//         } catch (Exception e) {
//             System.err.println("❌ Error fetching active positions: " + e.getMessage());
//             e.printStackTrace();
//         }
//         return activePairs;
//     }
// }
