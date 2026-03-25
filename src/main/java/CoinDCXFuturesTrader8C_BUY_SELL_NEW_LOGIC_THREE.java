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

public class CoinDCXFuturesTrader8C_BUY_SELL_NEW_LOGIC_THREE {

    private static final String API_KEY    = System.getenv("DELTA_API_KEY");
    private static final String API_SECRET = System.getenv("DELTA_API_SECRET");
    private static final String BASE_URL       = "https://api.coindcx.com";
    private static final String PUBLIC_API_URL = "https://public.coindcx.com";

    private static final double MAX_MARGIN            = 1200.0;
    private static final int    LEVERAGE              = 20;
    private static final int    MAX_CONCURRENT_TRADES = 5;
    private static final long   TRADE_COOLDOWN_MS     = 10 * 60 * 1000;

    private static final double RISK_PERCENT_NORMAL = 0.015;
    private static final double RISK_PERCENT_WIDE   = 0.010;

    private static final int EMA_FAST   = 9;
    private static final int EMA_MID    = 21;
    private static final int EMA_MACRO  = 50;
    private static final int MACD_FAST  = 12;
    private static final int MACD_SLOW  = 26;
    private static final int MACD_SIG   = 9;
    private static final int RSI_PERIOD = 14;
    private static final int ATR_PERIOD = 14;
    private static final int SWING_BARS = 20;

    private static final double RSI_LONG_MIN  = 35.0;
    private static final double RSI_LONG_MAX  = 72.0;
    private static final double RSI_SHORT_MIN = 28.0;
    private static final double RSI_SHORT_MAX = 65.0;

    private static final double SL_BUFFER_ATR = 1.0;
    private static final double SL_MAX_PCT    = 0.06;
    private static final double RR            = 3.0;

    private static final double TRAIL_BREAKEVEN_R = 1.0;
    private static final double TRAIL_LOCK_R      = 2.0;
    private static final double TRAIL_ATR_DIST    = 1.5;

    private static final int CANDLE_15M = 120;
    private static final int CANDLE_1H  = 70;

    private static final Map<String, Long>       lastTradeTime   = new ConcurrentHashMap<>();
    private static final Map<String, JSONObject> instrumentCache = new ConcurrentHashMap<>();
    private static       long                    lastCacheUpdate = 0;
    private static final long                    TICK_CACHE_TTL  = 3_600_000L;

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

    public static void main(String[] args) {
        initInstrumentCache();

        System.out.println("\n=== STEP 1: Trailing SL Update ===");
        List<JSONObject> activePositions = getActivePositionsFull();
        System.out.println("Open positions: " + activePositions.size());

        if (activePositions.size() >= MAX_CONCURRENT_TRADES) {
            System.out.println("Max trades open — not scanning for new entries.");
            for (JSONObject pos : activePositions) {
                try { updateTrailingStopLoss(pos); } catch (Exception e) {
                    System.err.println("Trail SL error: " + e.getMessage());
                }
            }
            return;
        }

        for (JSONObject pos : activePositions) {
            try { updateTrailingStopLoss(pos); } catch (Exception e) {
                System.err.println("Trail SL error for " + pos.optString("pair") + ": " + e.getMessage());
            }
        }

        System.out.println("\n=== STEP 2: Scanning for New Entries ===");
        Set<String> activePairs = new HashSet<>();
        for (JSONObject pos : activePositions) activePairs.add(pos.optString("pair"));

        for (String pair : COINS_TO_TRADE) {
            if (activePairs.contains(pair)) {
                System.out.println("Skip " + pair + " — already in a trade");
                continue;
            }

            long now = System.currentTimeMillis();
            if (lastTradeTime.containsKey(pair) && now - lastTradeTime.get(pair) < TRADE_COOLDOWN_MS) {
                System.out.println("Skip " + pair + " — cooldown");
                continue;
            }

            try {
                System.out.println("\n--- " + pair + " ---");

                JSONArray raw15m = getCandlestickData(pair, "15", CANDLE_15M);
                JSONArray raw1h  = getCandlestickData(pair, "60", CANDLE_1H);

                if (raw15m == null || raw15m.length() < 60) {
                    System.out.println("  Not enough 15m candles — skip");
                    continue;
                }
                if (raw1h == null || raw1h.length() < EMA_MACRO) {
                    System.out.println("  Not enough 1H candles — skip");
                    continue;
                }

                double[] cl15 = extractCloses(raw15m);
                double[] hi15 = extractHighs(raw15m);
                double[] lo15 = extractLows(raw15m);
                double[] cl1h = extractCloses(raw1h);

                double price    = cl15[cl15.length - 1];
                double tickSize = getTickSize(pair);
                double atr      = calcATR(hi15, lo15, cl15, ATR_PERIOD);

                System.out.printf("  Price=%.6f  ATR=%.6f%n", price, atr);
                if (atr <= 0) { System.out.println("  ATR is zero — skip"); continue; }

                // CONDITION 1: 1H trend direction
                double  ema1h50   = calcEMA(cl1h, EMA_MACRO);
                boolean trendUp   = price > ema1h50;
                boolean trendDown = price < ema1h50;
                System.out.printf("  [C1] 1H EMA50=%.6f | %s%n", ema1h50, trendUp ? "UPTREND" : "DOWNTREND");
                if (!trendUp && !trendDown) { System.out.println("  C1: Price on EMA — skip"); continue; }

                // CONDITION 2: 15m EMA cross
                double  ema9_15m  = calcEMA(cl15, EMA_FAST);
                double  ema21_15m = calcEMA(cl15, EMA_MID);
                boolean emaBull   = ema9_15m > ema21_15m;
                boolean emaBear   = ema9_15m < ema21_15m;
                System.out.printf("  [C2] 15m EMA9=%.6f EMA21=%.6f -> %s%n",
                        ema9_15m, ema21_15m, emaBull ? "BULL" : emaBear ? "BEAR" : "FLAT");

                boolean goLong  = trendUp   && emaBull;
                boolean goShort = trendDown && emaBear;
                if (!goLong && !goShort) { System.out.println("  C2: EMAs conflict with trend — skip"); continue; }

                // CONDITION 3: MACD + RSI
                double[] macd    = calcMACD(cl15, MACD_FAST, MACD_SLOW, MACD_SIG);
                double   macdLine = macd[0];
                double   macdSig  = macd[1];
                double   rsi      = calcRSI(cl15, RSI_PERIOD);
                boolean  macdOk   = goLong ? (macdLine > macdSig) : (macdLine < macdSig);
                boolean  rsiOk    = goLong
                        ? (rsi >= RSI_LONG_MIN  && rsi <= RSI_LONG_MAX)
                        : (rsi >= RSI_SHORT_MIN && rsi <= RSI_SHORT_MAX);
                System.out.printf("  [C3] MACD=%.6f Sig=%.6f -> %s | RSI=%.2f -> %s%n",
                        macdLine, macdSig, macdOk ? "OK" : "FAIL", rsi, rsiOk ? "OK" : "EXTREME");
                if (!macdOk) { System.out.println("  C3: MACD not aligned — skip"); continue; }
                if (!rsiOk)  { System.out.println("  C3: RSI extreme — skip"); continue; }

                String side = goLong ? "buy" : "sell";
                System.out.println("  >>> ALL CONDITIONS PASSED -> " + side.toUpperCase() + " " + pair);

                // STOP LOSS: swing low/high ± 1×ATR buffer, capped at 6%
                double swingLow  = swingLow(lo15, SWING_BARS);
                double swingHigh = swingHigh(hi15, SWING_BARS);
                double slPrice;
                if (goLong) {
                    slPrice = swingLow - (SL_BUFFER_ATR * atr);
                    double maxDist = price * SL_MAX_PCT;
                    if (price - slPrice > maxDist) slPrice = price - maxDist;
                } else {
                    slPrice = swingHigh + (SL_BUFFER_ATR * atr);
                    double maxDist = price * SL_MAX_PCT;
                    if (slPrice - price > maxDist) slPrice = price + maxDist;
                }
                slPrice = roundToTick(slPrice, tickSize);

                double risk = Math.abs(price - slPrice);
                if (risk <= 0) { System.out.println("  Zero risk distance — skip"); continue; }

                // TAKE PROFIT: always 3× the risk distance
                double tpPrice = goLong
                        ? roundToTick(price + RR * risk, tickSize)
                        : roundToTick(price - RR * risk, tickSize);

                System.out.printf("  SL=%.6f | TP=%.6f | Risk=%.6f | Reward=%.6f | RR=1:%.1f%n",
                        slPrice, tpPrice, risk, Math.abs(tpPrice - price),
                        Math.abs(tpPrice - price) / risk);

                double qty = calcQuantity(price, slPrice, pair);
                if (qty <= 0) { System.out.println("  Qty too small — skip"); continue; }

                System.out.printf("  Placing %s | qty=%.4f | lev=%dx%n", side.toUpperCase(), qty, LEVERAGE);

                JSONObject resp = placeFuturesMarketOrder(side, pair, qty, LEVERAGE,
                        "email_notification", "isolated", "INR");
                if (resp == null || !resp.has("id")) {
                    System.out.println("  Order failed: " + resp);
                    continue;
                }

                System.out.println("  Order placed! id=" + resp.getString("id"));
                lastTradeTime.put(pair, System.currentTimeMillis());

                double entry = getEntryPrice(pair, resp.getString("id"));
                if (entry <= 0) { System.out.println("  Entry not confirmed — TP/SL skipped"); continue; }
                System.out.printf("  Confirmed entry: %.6f%n", entry);

                // Recalculate from actual fill price
                if (goLong) {
                    slPrice = swingLow - (SL_BUFFER_ATR * atr);
                    double maxDist = entry * SL_MAX_PCT;
                    if (entry - slPrice > maxDist) slPrice = entry - maxDist;
                } else {
                    slPrice = swingHigh + (SL_BUFFER_ATR * atr);
                    double maxDist = entry * SL_MAX_PCT;
                    if (slPrice - entry > maxDist) slPrice = entry + maxDist;
                }
                slPrice  = roundToTick(slPrice, tickSize);
                risk     = Math.abs(entry - slPrice);
                tpPrice  = goLong
                        ? roundToTick(entry + RR * risk, tickSize)
                        : roundToTick(entry - RR * risk, tickSize);

                System.out.printf("  Final SL=%.6f | Final TP=%.6f | RR=1:%.1f%n",
                        slPrice, tpPrice, Math.abs(tpPrice - entry) / risk);

                String posId = getPositionId(pair);
                if (posId != null) setTpSl(posId, tpPrice, slPrice, pair);
                else System.out.println("  Position ID not found — TP/SL skipped");

            } catch (Exception e) {
                System.err.println("Error on " + pair + ": " + e.getMessage());
            }
        }
        System.out.println("\n=== Scan complete ===");
    }

    // =========================================================================
    // TRAILING STOP LOSS
    // =========================================================================
    private static void updateTrailingStopLoss(JSONObject pos) {
        String pair = pos.optString("pair");
        if (pair.isEmpty()) return;
        double entry   = pos.optDouble("avg_price", 0);
        double activeQ = pos.optDouble("active_pos", 0);
        if (entry <= 0 || activeQ == 0) return;

        boolean isLong    = activeQ > 0;
        double  currentSL = pos.optDouble("stop_loss_trigger", 0);
        double  currentTP = pos.optDouble("take_profit_trigger", 0);
        String  posId     = pos.optString("id");
        double  tickSize  = getTickSize(pair);

        if (currentSL <= 0 || posId.isEmpty()) { System.out.println("  [Trail] No SL for " + pair); return; }

        double livePrice = getLastPrice(pair);
        if (livePrice <= 0) return;

        JSONArray raw15m = getCandlestickData(pair, "15", 30);
        if (raw15m == null || raw15m.length() < ATR_PERIOD + 1) return;
        double atr = calcATR(extractHighs(raw15m), extractLows(raw15m), extractCloses(raw15m), ATR_PERIOD);
        if (atr <= 0) return;

        double initialRisk = isLong ? (entry - currentSL) : (currentSL - entry);
        if (initialRisk <= 0) initialRisk = atr * 2;

        double profit    = isLong ? (livePrice - entry) : (entry - livePrice);
        double rMultiple = profit / initialRisk;

        System.out.printf("  [Trail] %s %s | entry=%.6f live=%.6f profit=%.2fR | SL=%.6f%n",
                isLong ? "LONG" : "SHORT", pair, entry, livePrice, rMultiple, currentSL);

        double newSL = currentSL;
        if (rMultiple >= TRAIL_LOCK_R) {
            double trailSL  = isLong ? livePrice - TRAIL_ATR_DIST * atr : livePrice + TRAIL_ATR_DIST * atr;
            boolean improved = isLong ? (trailSL > currentSL) : (trailSL < currentSL);
            if (!improved) { System.out.println("  [Trail] Already in better position — no change"); return; }
            newSL = trailSL;
            System.out.printf("  [Trail] +%.2fR — Trailing SL to %.6f%n", rMultiple, newSL);
        } else if (rMultiple >= TRAIL_BREAKEVEN_R) {
            boolean needsMove = isLong ? (currentSL < entry) : (currentSL > entry);
            if (!needsMove) { System.out.println("  [Trail] Already at breakeven — no change"); return; }
            newSL = entry;
            System.out.printf("  [Trail] +%.2fR — Moving SL to breakeven %.6f%n", rMultiple, entry);
        } else {
            System.out.printf("  [Trail] %.2fR profit — waiting for +1R%n", rMultiple);
            return;
        }

        newSL = roundToTick(newSL, tickSize);
        if (Math.abs(newSL - currentSL) < tickSize * 0.5) { System.out.println("  [Trail] No change after rounding"); return; }
        if (currentTP > 0) setTpSl(posId, currentTP, newSL, pair);
        else System.out.println("  [Trail] No TP found — skipping update");
    }

    // =========================================================================
    // INDICATORS
    // =========================================================================
    private static double calcEMA(double[] prices, int period) {
        if (prices.length < period) return 0;
        double k = 2.0 / (period + 1), ema = 0;
        for (int i = 0; i < period; i++) ema += prices[i];
        ema /= period;
        for (int i = period; i < prices.length; i++) ema = prices[i] * k + ema * (1 - k);
        return ema;
    }

    private static double[] calcEMASeries(double[] prices, int period) {
        double[] out = new double[prices.length];
        if (prices.length < period) return out;
        double k = 2.0 / (period + 1), seed = 0;
        for (int i = 0; i < period; i++) seed += prices[i];
        out[period - 1] = seed / period;
        for (int i = period; i < prices.length; i++) out[i] = prices[i] * k + out[i - 1] * (1 - k);
        return out;
    }

    private static double[] calcMACD(double[] prices, int fast, int slow, int sig) {
        double[] ef = calcEMASeries(prices, fast), es = calcEMASeries(prices, slow);
        int start = slow - 1, len = prices.length - start;
        if (len <= 0) return new double[]{0, 0, 0};
        double[] ml = new double[len];
        for (int i = 0; i < len; i++) ml[i] = ef[start + i] - es[start + i];
        double[] ss = calcEMASeries(ml, sig);
        double m = ml[ml.length - 1], s = ss[ss.length - 1];
        return new double[]{m, s, m - s};
    }

    private static double calcRSI(double[] prices, int period) {
        if (prices.length < period + 1) return 50;
        double ag = 0, al = 0;
        for (int i = 1; i <= period; i++) {
            double ch = prices[i] - prices[i - 1];
            if (ch > 0) ag += ch; else al += Math.abs(ch);
        }
        ag /= period; al /= period;
        for (int i = period + 1; i < prices.length; i++) {
            double ch = prices[i] - prices[i - 1];
            if (ch > 0) { ag = (ag * (period - 1) + ch) / period; al = al * (period - 1) / period; }
            else { al = (al * (period - 1) + Math.abs(ch)) / period; ag = ag * (period - 1) / period; }
        }
        return al == 0 ? 100 : 100 - (100.0 / (1 + ag / al));
    }

    private static double calcATR(double[] hi, double[] lo, double[] cl, int period) {
        if (hi.length < period + 1) return 0;
        double[] tr = new double[hi.length];
        tr[0] = hi[0] - lo[0];
        for (int i = 1; i < hi.length; i++)
            tr[i] = Math.max(hi[i] - lo[i], Math.max(Math.abs(hi[i] - cl[i-1]), Math.abs(lo[i] - cl[i-1])));
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

    private static double calcQuantity(double entry, double slPrice, String pair) {
        double stopDistance = Math.abs(entry - slPrice);
        if (stopDistance <= 0) return 0;
        double riskPercent = (stopDistance / entry > 0.02) ? RISK_PERCENT_WIDE : RISK_PERCENT_NORMAL;
        double qty = (MAX_MARGIN * riskPercent) / stopDistance;
        return Math.max(INTEGER_QTY_PAIRS.contains(pair) ? Math.floor(qty) : Math.floor(qty * 100) / 100, 0);
    }

    // =========================================================================
    // CANDLE EXTRACTION
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
    // API METHODS
    // =========================================================================
    private static JSONArray getCandlestickData(String pair, String resolution, int count) {
        try {
            long minsPerBar;
            switch (resolution) {
                case "5":   minsPerBar = 5;   break;
                case "15":  minsPerBar = 15;  break;
                case "60":  minsPerBar = 60;  break;
                case "240": minsPerBar = 240; break;
                default:    minsPerBar = 15;  break;
            }
            long to = Instant.now().getEpochSecond(), from = to - minsPerBar * 60L * count;
            String url = PUBLIC_API_URL + "/market_data/candlesticks?pair=" + pair
                    + "&from=" + from + "&to=" + to + "&resolution=" + resolution + "&pcode=f";
            HttpURLConnection conn = openGet(url);
            if (conn.getResponseCode() == 200) {
                JSONObject r = new JSONObject(readStream(conn.getInputStream()));
                if ("ok".equals(r.optString("s"))) return r.getJSONArray("data");
            }
        } catch (Exception e) { System.err.println("  getCandlestickData(" + pair + "): " + e.getMessage()); }
        return null;
    }

    private static void initInstrumentCache() {
        try {
            long now = System.currentTimeMillis();
            if (now - lastCacheUpdate < TICK_CACHE_TTL) return;
            instrumentCache.clear();
            System.out.println("Loading instrument cache...");
            JSONArray pairs = new JSONArray(publicGet(BASE_URL + "/exchange/v1/derivatives/futures/data/active_instruments"));
            for (int i = 0; i < pairs.length(); i++) {
                String p = pairs.getString(i);
                try {
                    String raw = publicGet(BASE_URL + "/exchange/v1/derivatives/futures/data/instrument?pair=" + p);
                    instrumentCache.put(p, new JSONObject(raw).getJSONObject("instrument"));
                } catch (Exception ignored) {}
            }
            lastCacheUpdate = now;
            System.out.println("Cached " + instrumentCache.size() + " instruments.");
        } catch (Exception e) { System.err.println("initInstrumentCache: " + e.getMessage()); }
    }

    private static double getTickSize(String pair) {
        if (System.currentTimeMillis() - lastCacheUpdate > TICK_CACHE_TTL) initInstrumentCache();
        JSONObject d = instrumentCache.get(pair);
        return d != null ? d.optDouble("price_increment", 0.0001) : 0.0001;
    }

    private static double getEntryPrice(String pair, String orderId) throws Exception {
        for (int i = 0; i < 10; i++) {
            TimeUnit.MILLISECONDS.sleep(1000);
            JSONObject pos = findPosition(pair);
            if (pos != null && pos.optDouble("avg_price", 0) > 0) return pos.getDouble("avg_price");
        }
        return 0;
    }

    private static JSONObject findPosition(String pair) throws Exception {
        JSONObject body = new JSONObject();
        body.put("timestamp", Instant.now().toEpochMilli());
        body.put("page", "1"); body.put("size", "20");
        body.put("margin_currency_short_name", new String[]{"INR", "USDT"});
        String resp = authPost(BASE_URL + "/exchange/v1/derivatives/futures/positions", body.toString());
        JSONArray arr = resp.startsWith("[") ? new JSONArray(resp) : new JSONArray().put(new JSONObject(resp));
        for (int i = 0; i < arr.length(); i++) {
            JSONObject p = arr.getJSONObject(i);
            if (pair.equals(p.optString("pair"))) return p;
        }
        return null;
    }

    private static String getPositionId(String pair) {
        try { JSONObject pos = findPosition(pair); return pos != null ? pos.optString("id", null) : null; }
        catch (Exception e) { return null; }
    }

    private static List<JSONObject> getActivePositionsFull() {
        List<JSONObject> result = new ArrayList<>();
        try {
            JSONObject body = new JSONObject();
            body.put("timestamp", Instant.now().toEpochMilli());
            body.put("page", "1"); body.put("size", "50");
            body.put("margin_currency_short_name", new String[]{"INR", "USDT"});
            String resp = authPost(BASE_URL + "/exchange/v1/derivatives/futures/positions", body.toString());
            JSONArray arr = resp.startsWith("[") ? new JSONArray(resp) : new JSONArray().put(new JSONObject(resp));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject p = arr.getJSONObject(i);
                if (p.optDouble("active_pos", 0) != 0) result.add(p);
            }
        } catch (Exception e) { System.err.println("getActivePositionsFull: " + e.getMessage()); }
        return result;
    }

    public static double getLastPrice(String pair) {
        try {
            HttpURLConnection conn = openGet(PUBLIC_API_URL + "/market_data/trade_history?pair=" + pair + "&limit=1");
            if (conn.getResponseCode() == 200) {
                String r = readStream(conn.getInputStream());
                return r.startsWith("[") ? new JSONArray(r).getJSONObject(0).getDouble("p")
                        : new JSONObject(r).getDouble("p");
            }
        } catch (Exception e) { System.err.println("getLastPrice(" + pair + "): " + e.getMessage()); }
        return 0;
    }

    public static JSONObject placeFuturesMarketOrder(String side, String pair, double qty,
                                                     int lev, String notif, String marginType, String marginCcy) {
        try {
            JSONObject order = new JSONObject();
            order.put("side", side.toLowerCase()); order.put("pair", pair);
            order.put("order_type", "market_order"); order.put("total_quantity", qty);
            order.put("leverage", lev); order.put("notification", notif);
            order.put("time_in_force", "good_till_cancel"); order.put("hidden", false);
            order.put("post_only", false); order.put("position_margin_type", marginType);
            order.put("margin_currency_short_name", marginCcy);
            JSONObject body = new JSONObject();
            body.put("timestamp", Instant.now().toEpochMilli()); body.put("order", order);
            String resp = authPost(BASE_URL + "/exchange/v1/derivatives/futures/orders/create", body.toString());
            return resp.startsWith("[") ? new JSONArray(resp).getJSONObject(0) : new JSONObject(resp);
        } catch (Exception e) { System.err.println("placeFuturesMarketOrder: " + e.getMessage()); return null; }
    }

    public static void setTpSl(String posId, double tp, double sl, String pair) {
        try {
            double tick = getTickSize(pair);
            JSONObject tpObj = new JSONObject();
            tpObj.put("stop_price", roundToTick(tp, tick));
            tpObj.put("limit_price", roundToTick(tp, tick));
            tpObj.put("order_type", "take_profit_market");
            JSONObject slObj = new JSONObject();
            slObj.put("stop_price", roundToTick(sl, tick));
            slObj.put("limit_price", roundToTick(sl, tick));
            slObj.put("order_type", "stop_loss_market");
            JSONObject body = new JSONObject();
            body.put("timestamp", Instant.now().toEpochMilli());
            body.put("id", posId); body.put("take_profit", tpObj); body.put("stop_loss", slObj);
            String resp = authPost(BASE_URL + "/exchange/v1/derivatives/futures/positions/update_tp_sl", body.toString());
            System.out.println("  TP/SL set: " + resp);
        } catch (Exception e) { System.err.println("setTpSl: " + e.getMessage()); }
    }

    // =========================================================================
    // HTTP HELPERS
    // =========================================================================
    private static HttpURLConnection openGet(String urlStr) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET"); conn.setConnectTimeout(10_000); conn.setReadTimeout(10_000);
        return conn;
    }

    private static String publicGet(String urlStr) throws IOException {
        HttpURLConnection conn = openGet(urlStr);
        int code = conn.getResponseCode();
        if (code == 200) return readStream(conn.getInputStream());
        throw new IOException("HTTP " + code + " for " + urlStr);
    }

    private static String authPost(String urlStr, String jsonBody) throws Exception {
        String signature = hmacSha256(API_SECRET, jsonBody);
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("X-AUTH-APIKEY", API_KEY);
        conn.setRequestProperty("X-AUTH-SIGNATURE", signature);
        conn.setConnectTimeout(15_000); conn.setReadTimeout(15_000); conn.setDoOutput(true);
        try (OutputStream os = conn.getOutputStream()) { os.write(jsonBody.getBytes(StandardCharsets.UTF_8)); }
        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        String resp = readStream(is);
        if (code < 200 || code >= 300) System.err.println("authPost HTTP " + code + ": " + resp);
        return resp;
    }

    private static String hmacSha256(String secret, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static String readStream(InputStream is) throws IOException {
        if (is == null) return "";
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }
}
