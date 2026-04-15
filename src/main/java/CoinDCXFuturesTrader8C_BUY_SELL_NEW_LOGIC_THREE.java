import org.json.JSONArray;
import org.json.JSONObject;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class CoinDCXFuturesTrader8C_BUY_SELL_NEW_LOGIC_THREE {

    // =========================================================================
    // API & endpoints
    // =========================================================================
    private static final String API_KEY    = System.getenv("DELTA_API_KEY");
    private static final String API_SECRET = System.getenv("DELTA_API_SECRET");
    private static final String BASE_URL       = "https://api.coindcx.com";
    private static final String PUBLIC_API_URL = "https://public.coindcx.com";

    // =========================================================================
    // Margin / position sizing (UNCHANGED)
    // =========================================================================
    // MAX_MARGIN is in INR-equivalent; your quantity logic uses this as-is.
    private static final double MAX_MARGIN = 1200.0;

    private static final int  MAX_ORDER_STATUS_CHECKS = 10;
    private static final int  ORDER_CHECK_DELAY_MS    = 1000;
    private static final long TICK_SIZE_CACHE_TTL_MS  = 3_600_000L; // 1 hour cache

    // Old trend config (no longer used for side; EMA logic replaces it, but we keep TP/SL%)
    private static final int    LOOKBACK_PERIOD = 12;    // unused for EMA
    private static final double TREND_THRESHOLD = 0.01;  // unused for EMA

    // Fixed TP/SL percentages for this version
    private static final double TP_PERCENTAGE = 0.05; // 5% take profit
    private static final double SL_PERCENTAGE = 0.08; // 8% stop loss

    // =========================================================================
    // EMA / indicator configuration (from v3)
    // =========================================================================
    private static final int EMA_FAST   = 9;    // 15m EMA fast
    private static final int EMA_MID    = 21;   // 4H EMA, 15m mid
    private static final int EMA_MACRO  = 50;   // 1H macro EMA
    private static final int MACD_FAST  = 12;
    private static final int MACD_SLOW  = 26;
    private static final int MACD_SIG   = 9;
    private static final int RSI_PERIOD = 14;
    private static final int ATR_PERIOD = 14;
    private static final int VOL_PERIOD = 20;
    private static final int SWING_BARS = 15;   // not used in this simple TP/SL version

    // EMA distance buffer for stronger trend confirmation
    private static final double MIN_EMA_DISTANCE = 0.2;  // EMA9 must be 0.2*ATR away from EMA21

    // RSI zones
    private static final double RSI_LONG_MIN  = 45.0;
    private static final double RSI_LONG_MAX  = 64.0;
    private static final double RSI_SHORT_MIN = 36.0;
    private static final double RSI_SHORT_MAX = 56.0;

    // Candle counts for EMA logic
    private static final int CANDLE_15M = 200;
    private static final int CANDLE_4H  = 60;
    private static final int CANDLE_1H  = 120;

    // =========================================================================
    // Instrument cache
    // =========================================================================
    private static final Map<String, JSONObject> instrumentDetailsCache = new ConcurrentHashMap<>();
    private static long lastInstrumentUpdateTime = 0L;

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
        "ALT", "C98", "RLC", "SUN", "PENDLE", "BANANA", "NMR", "POL", "MAGIC", "VET",
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

    // Pairs that must use integer quantity
    private static final Set<String> INTEGER_QUANTITY_PAIRS = Stream.of(COIN_SYMBOLS)
            .flatMap(symbol -> Stream.of("B-" + symbol + "_USDT", symbol + "_USDT"))
            .collect(Collectors.toCollection(HashSet::new));

    private static final String[] COINS_TO_TRADE = Stream.of(COIN_SYMBOLS)
            .map(symbol -> "B-" + symbol + "_USDT")
            .toArray(String[]::new);

    // =========================================================================
    // MAIN
    // =========================================================================
    public static void main(String[] args) {
        initializeInstrumentDetails();

        Set<String> activePairs = getActivePositions();
        System.out.println("\nActive Positions: " + activePairs);

        for (String pair : COINS_TO_TRADE) {
            try {
                if (activePairs.contains(pair)) {
                    System.out.println("\n⏩ ⚠️ Skipping " + pair + " - Active position exists");
                    continue;
                }

                // --- NEW: side determined by EMA + MACD + soft filters ---
                String side = determinePositionSide(pair);
                if (side == null) {
                    System.out.println("No valid EMA signal for " + pair + " — skip");
                    continue;
                }

                // Uncomment to disable longs:
                // if ("buy".equalsIgnoreCase(side)) {
                //     System.out.println("⏩ Skipping " + pair + " - Buy (Long) side is disabled");
                //     continue;
                // }

                int leverage = 15; // keep your leverage choice for this bot

                double currentPrice = getLastPrice(pair);
                System.out.println("\nCurrent price for " + pair + ": " + currentPrice + " USDT");

                if (currentPrice <= 0) {
                    System.out.println("❌ Invalid price received, aborting for this pair");
                    continue;
                }

                // === MARGIN / SIZE LOGIC UNCHANGED ===
                double quantity = calculateQuantity(currentPrice, leverage, pair);
                System.out.println("Calculated quantity: " + quantity);

                if (quantity <= 0) {
                    System.out.println("❌ Invalid quantity calculated, aborting for this pair");
                    continue;
                }

                JSONObject orderResponse = placeFuturesMarketOrder(
                        side, pair, quantity, leverage,
                        "email_notification", "isolated", "INR"
                );

                if (orderResponse == null || !orderResponse.has("id")) {
                    System.out.println("❌ Failed to place order for this pair");
                    continue;
                }

                String orderId = orderResponse.getString("id");
                System.out.println("✅ Order placed successfully! Order ID: " + orderId);
                System.out.println("Side: " + side.toUpperCase());

                double entryPrice = getEntryPriceFromPosition(pair, orderId);
                if (entryPrice <= 0) {
                    System.out.println("❌ Could not determine entry price, aborting for this pair");
                    continue;
                }

                System.out.println("Entry Price: " + entryPrice + " INR");

                // Fixed percentage TP/SL based on entry
                double tpPrice;
                double slPrice;
                if ("buy".equalsIgnoreCase(side)) {
                    tpPrice = entryPrice * (1 + TP_PERCENTAGE);
                    slPrice = entryPrice * (1 - SL_PERCENTAGE);
                } else {
                    tpPrice = entryPrice * (1 - TP_PERCENTAGE);
                    slPrice = entryPrice * (1 + SL_PERCENTAGE);
                }

                // Round to tick size
                double tickSize = getTickSizeForPair(pair);
                tpPrice = Math.round(tpPrice / tickSize) * tickSize;
                slPrice = Math.round(slPrice / tickSize) * tickSize;

                System.out.println("Take Profit Price: " + tpPrice);
                System.out.println("Stop Loss Price: " + slPrice);

                String positionId = getPositionId(pair);
                if (positionId != null) {
                    setTakeProfitAndStopLoss(positionId, tpPrice, slPrice, side, pair);
                } else {
                    System.out.println("❌ Could not get position ID for TP/SL");
                }

            } catch (Exception e) {
                System.err.println("❌ Error processing pair " + pair + ": " + e.getMessage());
            }
        }
    }

    // =========================================================================
    // EMA-based side determination (H1–H4 + soft filters)
    // =========================================================================
    private static String determinePositionSide(String pair) {
        try {
            System.out.println("\n==== " + pair + " (EMA logic) ====");

            // Fetch candles for EMA logic
            JSONArray raw15m = getCandlestickData(pair, "15", CANDLE_15M);
            JSONArray raw4h  = getCandlestickData(pair, "240", CANDLE_4H);
            JSONArray raw1h  = getCandlestickData(pair, "60", CANDLE_1H);

            if (raw15m == null || raw15m.length() < 60) {
                System.out.println("  Insufficient 15m candles — no signal");
                return null;
            }
            if (raw4h == null || raw4h.length() < 25) {
                System.out.println("  Insufficient 4H candles — no signal");
                return null;
            }
            if (raw1h == null || raw1h.length() < EMA_MACRO) {
                System.out.println("  Insufficient 1H candles — no signal");
                return null;
            }

            if (raw15m.length() < MACD_SLOW + MACD_SIG + 5) {
                System.out.println("  Insufficient data for MACD — no signal");
                return null;
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
            double atr       = calcATR(hi15, lo15, cl15, ATR_PERIOD);

            System.out.printf("  Price=%.6f  ATR=%.6f%n", lastClose, atr);

            // H1 macro trend (1H EMA50)
            double ema1h = calcEMA(cl1h, EMA_MACRO);
            boolean macroUp   = lastClose > ema1h;
            boolean macroDown = lastClose < ema1h;
            System.out.printf("  [H1] 1H EMA50=%.6f → %s%n", ema1h, macroUp ? "BULL" : "BEAR");
            if (!macroUp && !macroDown) {
                System.out.println("  H1 flat — no signal");
                return null;
            }

            // H2 mid-tier (4H EMA21)
            double ema4h = calcEMA(cl4h, EMA_MID);
            boolean mid4hUp   = lastClose > ema4h;
            boolean mid4hDown = lastClose < ema4h;
            System.out.printf("  [H2] 4H EMA21=%.6f → %s%n", ema4h, mid4hUp ? "BULL" : "BEAR");
            if ((macroUp && !mid4hUp) || (macroDown && !mid4hDown)) {
                System.out.println("  H2 FAIL — 4H disagrees with 1H — no signal");
                return null;
            }
            System.out.println("  H2 OK — 4H aligned");

            // H3 local trend (15m EMA9/21 with distance)
            double ema9  = calcEMA(cl15, EMA_FAST);
            double ema21 = calcEMA(cl15, EMA_MID);
            double emaDistance    = Math.abs(ema9 - ema21);
            double minEmaDistance = MIN_EMA_DISTANCE * atr;
            boolean localUp   = ema9 > ema21 && emaDistance > minEmaDistance;
            boolean localDown = ema9 < ema21 && emaDistance > minEmaDistance;
            System.out.printf(
                    "  [H3] EMA9=%.6f EMA21=%.6f Dist=%.6f (min=%.6f) → %s%n",
                    ema9, ema21, emaDistance, minEmaDistance,
                    localUp ? "UP" : localDown ? "DOWN" : "weak/flat"
            );

            boolean trendUp   = macroUp   && mid4hUp   && localUp;
            boolean trendDown = macroDown && mid4hDown && localDown;
            if (!trendUp && !trendDown) {
                System.out.println("  H3 FAIL — 3-TF alignment failed or trend too weak — no signal");
                return null;
            }
            System.out.println("  H3 OK — all 3 timeframes aligned: " + (trendUp ? "BULLISH" : "BEARISH"));

            // H4 MACD momentum
            double[] mv       = calcMACDWithPrev(cl15, MACD_FAST, MACD_SLOW, MACD_SIG);
            double   macdLine = mv[0];
            double   macdSigV = mv[1];
            double   macdHist = mv[2];
            double   prevHist = mv[3];
            System.out.printf(
                    "  [H4] MACD=%.6f Sig=%.6f Hist=%.6f PrevHist=%.6f%n",
                    macdLine, macdSigV, macdHist, prevHist
            );

            boolean macdBull = macdLine > macdSigV && macdHist > prevHist;
            boolean macdBear = macdLine < macdSigV && macdHist < prevHist;

            if (trendUp && !macdBull) {
                System.out.println("  H4 FAIL — MACD not bullish/building — no signal");
                return null;
            }
            if (trendDown && !macdBear) {
                System.out.println("  H4 FAIL — MACD not bearish/building — no signal");
                return null;
            }
            System.out.println("  H4 OK — MACD momentum building");

            // Soft filters: need at least 2 of 3
            double  rsi        = calcRSI(cl15, RSI_PERIOD);
            boolean rsiOkLong  = trendUp   && rsi >= RSI_LONG_MIN  && rsi <= RSI_LONG_MAX;
            boolean rsiOkShort = trendDown && rsi >= RSI_SHORT_MIN && rsi <= RSI_SHORT_MAX;
            boolean softRsi    = rsiOkLong || rsiOkShort;
            System.out.printf("  [S1] RSI=%.2f → %s%n", rsi, softRsi ? "PASS" : "fail");

            boolean prevBull   = prevClose > prevOpen;
            boolean prevBear   = prevClose < prevOpen;
            boolean softCandle = (trendUp && prevBull) || (trendDown && prevBear);
            System.out.printf("  [S2] Prev candle body → %s%n", softCandle ? "PASS" : "fail");

            boolean softVolume = false;
            if (vl15 != null && vl15.length > VOL_PERIOD + 1) {
                double avgVol   = calcSMA(vl15, VOL_PERIOD);
                double entryVol = vl15[vl15.length - 2];
                softVolume = entryVol > avgVol * 1.2;
                System.out.printf(
                        "  [S3] Vol=%.2f AvgVol=%.2f (1.2x=%.2f) → %s%n",
                        entryVol, avgVol, avgVol * 1.2, softVolume ? "PASS" : "fail"
                );
            } else {
                System.out.println("  [S3] Volume data unavailable — skip soft filter");
            }

            int softCount = (softRsi ? 1 : 0) + (softCandle ? 1 : 0) + (softVolume ? 1 : 0);
            if (softCount < 2) {
                System.out.println("  SOFT FAIL — only " + softCount + " of 3 filters passed (need 2) — no signal");
                return null;
            }

            String softPassed =
                    (softRsi ? "RSI " : "") +
                    (softCandle ? "Candle " : "") +
                    (softVolume ? "Volume" : "");
            System.out.println("  SOFT OK (" + softCount + " of 3) — confirmed by: " + softPassed.trim());

            String side = trendUp ? "buy" : "sell";
            System.out.println("  >>> ALL FILTERS PASSED → " + side.toUpperCase() + " " + pair);
            return side;

        } catch (Exception e) {
            System.err.println("❌ Error in EMA side logic for " + pair + ": " + e.getMessage());
            return null;
        }
    }

    // =========================================================================
    // Candles (EMA version)
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
                    + "?pair=" + pair
                    + "&from=" + from
                    + "&to=" + to
                    + "&resolution=" + resolution
                    + "&pcode=f";

            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            if (code == 200) {
                String resp = readAllLines(conn.getInputStream());
                JSONObject r = new JSONObject(resp);
                if ("ok".equals(r.optString("s"))) {
                    return r.getJSONArray("data");
                }
                System.err.println("  Candle status=" + r.optString("s") + " for " + pair);
            } else {
                System.err.println("  Candle HTTP " + code + " for " + pair);
            }
        } catch (Exception e) {
            System.err.println("  getCandlestickData(" + pair + "," + resolution + "): " + e.getMessage());
        }
        return null;
    }

    // =========================================================================
    // Indicator calculations
    // =========================================================================
    private static double calcEMA(double[] d, int period) {
        if (d.length < period) return 0;
        double k = 2.0 / (period + 1);
        double ema = 0;
        for (int i = 0; i < period; i++) ema += d[i];
        ema /= period;
        for (int i = period; i < d.length; i++) {
            ema = d[i] * k + ema * (1 - k);
        }
        return ema;
    }

    private static double[] calcEMASeries(double[] d, int period) {
        double[] out = new double[d.length];
        if (d.length < period) return out;
        double k = 2.0 / (period + 1);
        double seed = 0;
        for (int i = 0; i < period; i++) seed += d[i];
        out[period - 1] = seed / period;
        for (int i = period; i < d.length; i++) {
            out[i] = d[i] * k + out[i - 1] * (1 - k);
        }
        return out;
    }

    // MACD with previous histogram for momentum check
    private static double[] calcMACDWithPrev(double[] cl, int fast, int slow, int sig) {
        double[] ef = calcEMASeries(cl, fast);
        double[] es = calcEMASeries(cl, slow);
        int start = slow - 1;
        int len   = cl.length - start;
        if (len <= 1) return new double[]{0, 0, 0, 0};

        double[] ml = new double[len];
        for (int i = 0; i < len; i++) {
            ml[i] = ef[start + i] - es[start + i];
        }
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
        for (int i = 1; i < hi.length; i++) {
            tr[i] = Math.max(
                    hi[i] - lo[i],
                    Math.max(Math.abs(hi[i] - cl[i - 1]), Math.abs(lo[i] - cl[i - 1]))
            );
        }
        double atr = 0;
        for (int i = 0; i < period; i++) atr += tr[i];
        atr /= period;
        for (int i = period; i < hi.length; i++) {
            atr = (atr * (period - 1) + tr[i]) / period;
        }
        return atr;
    }

    private static double calcSMA(double[] d, int period) {
        if (d.length < period) return 0;
        double sum = 0;
        for (int i = d.length - period; i < d.length; i++) sum += d[i];
        return sum / period;
    }

    // =========================================================================
    // OHLCV extraction
    // =========================================================================
    private static double[] extractCloses(JSONArray a) {
        double[] o = new double[a.length()];
        for (int i = 0; i < a.length(); i++) {
            o[i] = a.getJSONObject(i).getDouble("close");
        }
        return o;
    }

    private static double[] extractOpens(JSONArray a) {
        double[] o = new double[a.length()];
        for (int i = 0; i < a.length(); i++) {
            o[i] = a.getJSONObject(i).getDouble("open");
        }
        return o;
    }

    private static double[] extractHighs(JSONArray a) {
        double[] o = new double[a.length()];
        for (int i = 0; i < a.length(); i++) {
            o[i] = a.getJSONObject(i).getDouble("high");
        }
        return o;
    }

    private static double[] extractLows(JSONArray a) {
        double[] o = new double[a.length()];
        for (int i = 0; i < a.length(); i++) {
            o[i] = a.getJSONObject(i).getDouble("low");
        }
        return o;
    }

    private static double[] extractVolumes(JSONArray a) {
        try {
            if (a.length() == 0 || !a.getJSONObject(0).has("volume")) return null;
            double[] o = new double[a.length()];
            for (int i = 0; i < a.length(); i++) {
                o[i] = a.getJSONObject(i).getDouble("volume");
            }
            return o;
        } catch (Exception e) {
            return null;
        }
    }

    // =========================================================================
    // Instrument metadata / tick size
    // =========================================================================
    private static void initializeInstrumentDetails() {
        try {
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastInstrumentUpdateTime > TICK_SIZE_CACHE_TTL_MS) {
                System.out.println("ℹ️ Fetching latest instrument details from API...");
                instrumentDetailsCache.clear();

                String activeInstrumentsResponse = sendPublicRequest(
                        BASE_URL + "/exchange/v1/derivatives/futures/data/active_instruments"
                );

                JSONArray activeInstruments = new JSONArray(activeInstrumentsResponse);

                for (int i = 0; i < activeInstruments.length(); i++) {
                    String pair = activeInstruments.getString(i);
                    try {
                        String instrumentResponse = sendPublicRequest(
                                BASE_URL + "/exchange/v1/derivatives/futures/data/instrument?pair=" + pair
                        );
                        JSONObject instrumentDetails = new JSONObject(instrumentResponse)
                                .getJSONObject("instrument");
                        instrumentDetailsCache.put(pair, instrumentDetails);
                    } catch (Exception e) {
                        System.err.println("❌ Error fetching details for " + pair + ": " + e.getMessage());
                    }
                }

                lastInstrumentUpdateTime = currentTime;
                System.out.println("✅ Successfully updated instrument details for " + instrumentDetailsCache.size() + " pairs");
            }
        } catch (Exception e) {
            System.err.println("❌ Error initializing instrument details: " + e.getMessage());
        }
    }

    private static String sendPublicRequest(String endpoint) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setRequestMethod("GET");
        if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
            return readAllLines(conn.getInputStream());
        }
        throw new IOException("HTTP error code: " + conn.getResponseCode());
    }

    private static double getTickSizeForPair(String pair) {
        if (System.currentTimeMillis() - lastInstrumentUpdateTime > TICK_SIZE_CACHE_TTL_MS) {
            initializeInstrumentDetails();
        }

        JSONObject instrumentDetails = instrumentDetailsCache.get(pair);
        if (instrumentDetails != null) {
            return instrumentDetails.optDouble("price_increment", 0.0001);
        }
        return 0.0001;
    }

    // =========================================================================
    // Order / position helpers (UNCHANGED TP/SL wiring)
    // =========================================================================
    private static double getEntryPriceFromPosition(String pair, String orderId) throws Exception {
        System.out.println("\nChecking position for entry price...");
        for (int attempts = 0; attempts < MAX_ORDER_STATUS_CHECKS; attempts++) {
            TimeUnit.MILLISECONDS.sleep(ORDER_CHECK_DELAY_MS);
            JSONObject position = findPosition(pair);
            if (position != null && position.optDouble("avg_price", 0) > 0) {
                return position.getDouble("avg_price");
            }
        }
        return 0;
    }

    private static JSONObject findPosition(String pair) throws Exception {
        JSONObject body = new JSONObject();
        body.put("timestamp", Instant.now().toEpochMilli());
        body.put("page", "1");
        body.put("size", "10");
        body.put("margin_currency_short_name", new String[]{"INR", "USDT"});

        String response = sendAuthenticatedRequest(
                BASE_URL + "/exchange/v1/derivatives/futures/positions",
                body.toString(),
                generateHmacSHA256(API_SECRET, body.toString())
        );

        if (response.startsWith("[")) {
            JSONArray arr = new JSONArray(response);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject position = arr.getJSONObject(i);
                if (position.getString("pair").equals(pair)) return position;
            }
        } else {
            JSONObject position = new JSONObject(response);
            if (position.getString("pair").equals(pair)) return position;
        }
        return null;
    }

    // === UNCHANGED MARGIN/SIZE LOGIC ===
    private static double calculateQuantity(double currentPrice, int leverage, String pair) {
        double quantity = MAX_MARGIN / (currentPrice * 93);
        if (quantity <= 0) return 0;
        if (INTEGER_QUANTITY_PAIRS.contains(pair)) {
            return Math.max(Math.floor(quantity), 0);
        }
        return Math.max(Math.floor(quantity * 100) / 100, 0);
    }

    public static double getLastPrice(String pair) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(
                    PUBLIC_API_URL + "/market_data/trade_history?pair=" + pair + "&limit=1"
            ).openConnection();
            if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                String response = readAllLines(conn.getInputStream());
                if (response.startsWith("[")) {
                    return new JSONArray(response).getJSONObject(0).getDouble("p");
                } else {
                    return new JSONObject(response).getDouble("p");
                }
            }
        } catch (Exception e) {
            System.err.println("❌ Error getting last price: " + e.getMessage());
        }
        return 0;
    }

    public static JSONObject placeFuturesMarketOrder(
            String side,
            String pair,
            double totalQuantity,
            int leverage,
            String notification,
            String positionMarginType,
            String marginCurrency
    ) {
        try {
            JSONObject order = new JSONObject();
            order.put("side", side.toLowerCase());
            order.put("pair", pair);
            order.put("order_type", "market_order");
            order.put("total_quantity", totalQuantity);
            order.put("leverage", leverage);
            order.put("notification", notification);
            order.put("time_in_force", "good_till_cancel");
            order.put("hidden", false);
            order.put("post_only", false);
            order.put("position_margin_type", positionMarginType);
            order.put("margin_currency_short_name", marginCurrency);

            JSONObject body = new JSONObject();
            body.put("timestamp", Instant.now().toEpochMilli());
            body.put("order", order);

            String response = sendAuthenticatedRequest(
                    BASE_URL + "/exchange/v1/derivatives/futures/orders/create",
                    body.toString(),
                    generateHmacSHA256(API_SECRET, body.toString())
            );
            if (response.startsWith("[")) {
                return new JSONArray(response).getJSONObject(0);
            }
            return new JSONObject(response);
        } catch (Exception e) {
            System.err.println("❌ Error placing futures market order: " + e.getMessage());
            return null;
        }
    }

    public static void setTakeProfitAndStopLoss(
            String positionId,
            double takeProfitPrice,
            double stopLossPrice,
            String side,
            String pair
    ) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("timestamp", Instant.now().toEpochMilli());
            payload.put("id", positionId);

            double tickSize = getTickSizeForPair(pair);
            double roundedTpPrice = Math.round(takeProfitPrice / tickSize) * tickSize;
            double roundedSlPrice = Math.round(stopLossPrice / tickSize) * tickSize;

            JSONObject takeProfit = new JSONObject();
            takeProfit.put("stop_price", roundedTpPrice);
            takeProfit.put("limit_price", roundedTpPrice);
            takeProfit.put("order_type", "take_profit_market");

            JSONObject stopLoss = new JSONObject();
            stopLoss.put("stop_price", roundedSlPrice);
            stopLoss.put("limit_price", roundedSlPrice);
            stopLoss.put("order_type", "stop_market");

            payload.put("take_profit", takeProfit);
            payload.put("stop_loss", stopLoss);

            System.out.println("Final TP Price: " + roundedTpPrice + " | Final SL Price: " + roundedSlPrice);

            String response = sendAuthenticatedRequest(
                    BASE_URL + "/exchange/v1/derivatives/futures/positions/create_tpsl",
                    payload.toString(),
                    generateHmacSHA256(API_SECRET, payload.toString())
            );

            JSONObject tpslResponse = new JSONObject(response);
            if (!tpslResponse.has("err_code_dcx")) {
                System.out.println("✅ TP/SL set successfully!");
            } else {
                System.out.println("❌ Failed to set TP/SL: " + tpslResponse);
            }
        } catch (Exception e) {
            System.err.println("❌ Error setting TP/SL: " + e.getMessage());
        }
    }

    public static String getPositionId(String pair) {
        try {
            JSONObject position = findPosition(pair);
            return position != null ? position.getString("id") : null;
        } catch (Exception e) {
            System.err.println("❌ Error getting position ID: " + e.getMessage());
            return null;
        }
    }

    private static String sendAuthenticatedRequest(String endpoint, String jsonBody, String signature)
            throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("X-AUTH-APIKEY", API_KEY);
        conn.setRequestProperty("X-AUTH-SIGNATURE", signature);
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
        }

        return readAllLines(conn.getInputStream());
    }

    private static String readAllLines(InputStream is) throws IOException {
        return new BufferedReader(new InputStreamReader(is))
                .lines()
                .collect(Collectors.joining("\n"));
    }

    public static String generateHmacSHA256(String secret, String payload) {
        try {
            Mac sha256_HMAC = Mac.getInstance("HmacSHA256");
            sha256_HMAC.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] bytes = sha256_HMAC.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : bytes) hexString.append(String.format("%02x", b));
            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException("Error generating HMAC signature", e);
        }
    }

    private static Set<String> getActivePositions() {
        Set<String> activePairs = new HashSet<>();
        try {
            JSONObject body = new JSONObject();
            body.put("timestamp", Instant.now().toEpochMilli());
            body.put("page", "1");
            body.put("size", "100");
            body.put("margin_currency_short_name", new String[]{"INR", "USDT"});

            String response = sendAuthenticatedRequest(
                    BASE_URL + "/exchange/v1/derivatives/futures/positions",
                    body.toString(),
                    generateHmacSHA256(API_SECRET, body.toString())
            );

            JSONArray positions = response.startsWith("[")
                    ? new JSONArray(response)
                    : new JSONArray().put(new JSONObject(response));

            System.out.println("\n=== Raw Position Data ===");
            System.out.println("Total positions received: " + positions.length());

            for (int i = 0; i < positions.length(); i++) {
                JSONObject position = positions.getJSONObject(i);
                String pair = position.optString("pair", "");

                boolean isActive =
                        position.optDouble("active_pos", 0) > 0 ||
                        position.optDouble("locked_margin", 0) > 0 ||
                        position.optDouble("avg_price", 0) > 0 ||
                        position.optDouble("take_profit_trigger", 0) > 0 ||
                        position.optDouble("stop_loss_trigger", 0) > 0;

                if (isActive) {
                    System.out.printf(
                            "Active Position: %s | ActivePos: %.2f | Margin: %.4f | Entry: %.6f | TP: %.4f | SL: %.4f%n",
                            pair,
                            position.optDouble("active_pos", 0),
                            position.optDouble("locked_margin", 0),
                            position.optDouble("avg_price", 0),
                            position.optDouble("take_profit_trigger", 0),
                            position.optDouble("stop_loss_trigger", 0)
                    );
                    activePairs.add(pair);
                }
            }

            System.out.println("\n=== Final Active Positions ===");
            if (activePairs.isEmpty()) {
                System.out.println("No active positions found.");
            } else {
                activePairs.forEach(p -> System.out.println("- " + p));
                System.out.println("Total active positions detected: " + activePairs.size());
            }
        } catch (Exception e) {
            System.err.println("❌ Error fetching active positions: " + e.getMessage());
            e.printStackTrace();
        }
        return activePairs;
    }
}
