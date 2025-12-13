
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

    /* ===================== CONFIG ===================== */

    private static final String API_KEY = System.getenv("DELTA_API_KEY");
    private static final String API_SECRET = System.getenv("DELTA_API_SECRET");

    private static final String BASE_URL = "https://api.coindcx.com";
    private static final String PUBLIC_API_URL = "https://public.coindcx.com";

    private static final double MAX_MARGIN = 1200.0;
    private static final int DEFAULT_LEVERAGE = 6;

    /* ===== FIXED TREND SETTINGS (MINUTES-BASED) ===== */

    private static final int TREND_LOOKBACK_MINUTES = 60;   // 1 hour
    private static final int TREND_CANDLE_MINUTES = 5;      // 5m candles
    private static final int TREND_CANDLE_COUNT = TREND_LOOKBACK_MINUTES / TREND_CANDLE_MINUTES; // 12 candles

    private static final double TREND_THRESHOLD = 0.03;     // 3%

    private static final double TP_PERCENTAGE = 0.099;     // 9.9%
    private static final double SL_PERCENTAGE = 0.06;      // 6%

    private static final int MAX_ORDER_STATUS_CHECKS = 10;
    private static final int ORDER_CHECK_DELAY_MS = 1000;

    private static final long TICK_SIZE_CACHE_TTL_MS = 3600000; // 1 hour

    /* ===================== CACHES ===================== */

    private static final Map<String, JSONObject> instrumentDetailsCache = new ConcurrentHashMap<>();
    private static long lastInstrumentUpdateTime = 0;

    /* ===================== SYMBOLS ===================== */

    private static final String[] COIN_SYMBOLS = {
            "BTC", "ETH", "SOL", "XRP", "DOGE", "ADA", "BNB", "LINK"
    };

    private static final Set<String> INTEGER_QUANTITY_PAIRS = Stream.of(COIN_SYMBOLS)
            .flatMap(s -> Stream.of("B-" + s + "_USDT", s + "_USDT"))
            .collect(Collectors.toSet());

    private static final String[] COINS_TO_TRADE = Stream.of(COIN_SYMBOLS)
            .map(s -> "B-" + s + "_USDT")
            .toArray(String[]::new);

    /* ===================== MAIN ===================== */

    public static void main(String[] args) {
        initializeInstrumentDetails();
        Set<String> activePairs = getActivePositions();

        for (String pair : COINS_TO_TRADE) {
            try {
                if (activePairs.contains(pair)) {
                    System.out.println("⏩ Skipping " + pair + " (active position)");
                    continue;
                }

                String side = determinePositionSide(pair);
                if (side == null) continue;

                double price = getLastPrice(pair);
                if (price <= 0) continue;

                double qty = calculateQuantity(price, pair);
                if (qty <= 0) continue;

                JSONObject order = placeFuturesMarketOrder(side, pair, qty);
                if (order == null || !order.has("id")) continue;

                String orderId = order.getString("id");
                double entryPrice = getEntryPriceFromPosition(pair, orderId);
                if (entryPrice <= 0) continue;

                double tp = side.equals("buy")
                        ? entryPrice * (1 + TP_PERCENTAGE)
                        : entryPrice * (1 - TP_PERCENTAGE);

                double sl = side.equals("buy")
                        ? entryPrice * (1 - SL_PERCENTAGE)
                        : entryPrice * (1 + SL_PERCENTAGE);

                double tick = getTickSizeForPair(pair);
                tp = Math.round(tp / tick) * tick;
                sl = Math.round(sl / tick) * tick;

                String positionId = getPositionId(pair);
                if (positionId != null) {
                    setTakeProfitAndStopLoss(positionId, tp, sl, pair);
                }

            } catch (Exception e) {
                System.err.println("Error on " + pair + ": " + e.getMessage());
            }
        }
    }

    /* ===================== TREND LOGIC (FIXED) ===================== */

    private static String determinePositionSide(String pair) {
        try {
            JSONArray candles = getCandlestickData(
                    pair,
                    TREND_CANDLE_MINUTES + "m",
                    TREND_CANDLE_COUNT
            );

            if (candles == null || candles.length() < TREND_CANDLE_COUNT) return null;

            double firstClose = candles.getJSONObject(0).getDouble("close");
            double lastClose = candles.getJSONObject(candles.length() - 1).getDouble("close");

            double change = (lastClose - firstClose) / firstClose;

            System.out.printf("Trend %s → %.2f%%%n", pair, change * 100);

            if (change >= TREND_THRESHOLD) return "buy";
            if (change <= -TREND_THRESHOLD) return "sell";

            return determineSideWithRSI(candles);

        } catch (Exception e) {
            return null;
        }
    }

    private static JSONArray getCandlestickData(String pair, String resolution, int candleCount) {
        try {
            long end = Instant.now().toEpochMilli();
            long start = end - TimeUnit.MINUTES.toMillis(candleCount * TREND_CANDLE_MINUTES);

            String url = PUBLIC_API_URL + "/market_data/candlesticks?pair=" + pair
                    + "&from=" + start
                    + "&to=" + end
                    + "&resolution=" + resolution;

            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");

            if (conn.getResponseCode() == 200) {
                JSONObject json = new JSONObject(readAllLines(conn.getInputStream()));
                if (json.getString("s").equals("ok")) return json.getJSONArray("data");
            }
        } catch (Exception ignored) {}
        return null;
    }

    /* ===================== RSI ===================== */

    private static String determineSideWithRSI(JSONArray candles) {
        int period = 14;
        if (candles.length() <= period) return null;

        double gain = 0, loss = 0;
        for (int i = 1; i <= period; i++) {
            double diff = candles.getJSONObject(i).getDouble("close")
                    - candles.getJSONObject(i - 1).getDouble("close");
            if (diff > 0) gain += diff;
            else loss -= diff;
        }

        double rs = (loss == 0) ? 100 : (gain / period) / (loss / period);
        double rsi = 100 - (100 / (1 + rs));

        if (rsi < 30) return "buy";
        if (rsi > 70) return "sell";
        return null;
    }

    /* ===================== ORDER HELPERS ===================== */

    private static double calculateQuantity(double price, String pair) {
        double rawQty = MAX_MARGIN / (price * 93);
        return INTEGER_QUANTITY_PAIRS.contains(pair)
                ? Math.floor(rawQty)
                : Math.floor(rawQty * 100) / 100;
    }

    private static double getLastPrice(String pair) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(
                    PUBLIC_API_URL + "/market_data/trade_history?pair=" + pair + "&limit=1"
            ).openConnection();
            if (conn.getResponseCode() == 200) {
                JSONArray arr = new JSONArray(readAllLines(conn.getInputStream()));
                return arr.getJSONObject(0).getDouble("p");
            }
        } catch (Exception ignored) {}
        return 0;
    }

    private static JSONObject placeFuturesMarketOrder(String side, String pair, double qty) throws Exception {
        JSONObject order = new JSONObject();
        order.put("side", side);
        order.put("pair", pair);
        order.put("order_type", "market_order");
        order.put("total_quantity", qty);
        order.put("leverage", DEFAULT_LEVERAGE);
        order.put("position_margin_type", "isolated");
        order.put("margin_currency_short_name", "USDT");

        JSONObject body = new JSONObject();
        body.put("timestamp", Instant.now().toEpochMilli());
        body.put("order", order);

        String res = sendAuthenticatedRequest(
                BASE_URL + "/exchange/v1/derivatives/futures/orders/create",
                body.toString()
        );
        return new JSONObject(res);
    }

    /* ===================== POSITION / TP SL ===================== */

    private static double getEntryPriceFromPosition(String pair, String orderId) throws Exception {
        for (int i = 0; i < MAX_ORDER_STATUS_CHECKS; i++) {
            TimeUnit.MILLISECONDS.sleep(ORDER_CHECK_DELAY_MS);
            JSONObject pos = findPosition(pair);
            if (pos != null && pos.optDouble("avg_price", 0) > 0) {
                return pos.getDouble("avg_price");
            }
        }
        return 0;
    }

    private static void setTakeProfitAndStopLoss(String id, double tp, double sl, String pair) throws Exception {
        JSONObject body = new JSONObject();
        body.put("timestamp", Instant.now().toEpochMilli());
        body.put("id", id);

        JSONObject tpObj = new JSONObject();
        tpObj.put("stop_price", tp);
        tpObj.put("order_type", "take_profit_market");

        JSONObject slObj = new JSONObject();
        slObj.put("stop_price", sl);
        slObj.put("order_type", "stop_market");

        body.put("take_profit", tpObj);
        body.put("stop_loss", slObj);

        sendAuthenticatedRequest(
                BASE_URL + "/exchange/v1/derivatives/futures/positions/create_tpsl",
                body.toString()
        );
    }

    private static String getPositionId(String pair) throws Exception {
        JSONObject pos = findPosition(pair);
        return pos != null ? pos.getString("id") : null;
    }

    private static JSONObject findPosition(String pair) throws Exception {
        JSONObject body = new JSONObject();
        body.put("timestamp", Instant.now().toEpochMilli());
        body.put("page", 1);
        body.put("size", 50);

        String res = sendAuthenticatedRequest(
                BASE_URL + "/exchange/v1/derivatives/futures/positions",
                body.toString()
        );

        JSONArray arr = new JSONArray(res);
        for (int i = 0; i < arr.length(); i++) {
            if (pair.equals(arr.getJSONObject(i).getString("pair")))
                return arr.getJSONObject(i);
        }
        return null;
    }

    /* ===================== INSTRUMENT DATA ===================== */

    private static void initializeInstrumentDetails() {
        try {
            if (System.currentTimeMillis() - lastInstrumentUpdateTime < TICK_SIZE_CACHE_TTL_MS) return;

            instrumentDetailsCache.clear();
            JSONArray arr = new JSONArray(sendPublicRequest(
                    BASE_URL + "/exchange/v1/derivatives/futures/data/active_instruments"));

            for (int i = 0; i < arr.length(); i++) {
                String pair = arr.getString(i);
                JSONObject obj = new JSONObject(sendPublicRequest(
                        BASE_URL + "/exchange/v1/derivatives/futures/data/instrument?pair=" + pair));
                instrumentDetailsCache.put(pair, obj.getJSONObject("instrument"));
            }
            lastInstrumentUpdateTime = System.currentTimeMillis();
        } catch (Exception ignored) {}
    }

    private static double getTickSizeForPair(String pair) {
        JSONObject obj = instrumentDetailsCache.get(pair);
        return obj != null ? obj.optDouble("price_increment", 0.0001) : 0.0001;
    }

    /* ===================== AUTH / UTILS ===================== */

    private static Set<String> getActivePositions() {
        Set<String> set = new HashSet<>();
        try {
            JSONObject body = new JSONObject();
            body.put("timestamp", Instant.now().toEpochMilli());
            body.put("page", 1);
            body.put("size", 100);

            JSONArray arr = new JSONArray(sendAuthenticatedRequest(
                    BASE_URL + "/exchange/v1/derivatives/futures/positions",
                    body.toString()
            ));

            for (int i = 0; i < arr.length(); i++) {
                JSONObject p = arr.getJSONObject(i);
                if (p.optDouble("active_pos", 0) > 0) set.add(p.getString("pair"));
            }
        } catch (Exception ignored) {}
        return set;
    }

    private static String sendAuthenticatedRequest(String url, String body) throws Exception {
        String sig = generateHmacSHA256(API_SECRET, body);
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("X-AUTH-APIKEY", API_KEY);
        conn.setRequestProperty("X-AUTH-SIGNATURE", sig);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        return readAllLines(conn.getInputStream());
    }

    private static String sendPublicRequest(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        return readAllLines(conn.getInputStream());
    }

    private static String readAllLines(InputStream is) throws IOException {
        return new BufferedReader(new InputStreamReader(is)).lines().collect(Collectors.joining());
    }

    private static String generateHmacSHA256(String secret, String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] raw = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : raw) sb.append(String.format("%02x", b));
        return sb.toString();
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
//     private static final int LOOKBACK_PERIOD = 60; // Minutes for trend analysis (changed from hours)
//     private static final double TREND_THRESHOLD = 0.03; // 2% change threshold for trend
//     private static final double TP_PERCENTAGE = 0.099; // 3% take profit
//     private static final double SL_PERCENTAGE = 0.06; // 5% stop loss

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


//  "BTC", "ETH", "VOXEL", "SOL", "NKN", "MAGIC", "XRP", "1000PEPE", "FARTCOIN", "DOGE",
// "TAO", "SUI", "TRUMP", "PERP", "OM","ADA", "BNB", "LINK", "PEOPLE", "GMT",
// "FET", "MUBARAK", "WIF", "LAYER", "AVAX", "HIGH", "ALPACA", "FLM", "ENA", "BSW",
//  "FIL", "DOT", "LTC", "ARK", "ENJ", "EOS", "USUAL", "TRX", "RENDER", "CRV",
// "BCH", "OGN", "ONDO", "1000SHIB", "BIO", "AAVE", "ORCA", "NEAR", "PNUT", "T",
//  "POPCAT", "FUN", "VTHO", "WLD", "ALCH", "GAS", "XAI", "GALA", "TIA", "MBOX",
//  "APT", "ORDI", "HBAR", "OP", "INJ", "BEL", "JASMY", "RED", "KAITO", "PARTI",
//  "ARB", "BIGTIME", "AI16Z", "1000SATS", "NEIRO", "ETC", "JUP", "BOME", "UNI", "TON",
//  "1000BONK", "ACH", "XLM", "GOAT", "SAND", "ATOM", "LEVER", "S", "CAKE", "NOT",
//  "LOKA", "ARC", "VINE", "PENDLE", "LDO", "SEI", "RAYSOL", "APE", "RARE",
// "WAXP", "GPS", "IP", "COTI", "AVAAI", "KOMA", "HFT", "ARKM", "ANIME", "ACT",
//  "ALGO", "VIRTUAL", "MAVIA", "ALICE", "MANTA", "ZRO", "AGLD", "STX", "API3", "PIXEL",
// "MELANIA", "NEO", "IMX", "1000WHY", "MANA", "ACE", "SWARMS", "MKR", "AUCTION", "ICP",
// "PORTAL", "THETA", "CHESS", "ZEREBRO", "1000FLOKI", "PENGU", "STRK", "CATI", "TRB", "SAGA",
//  "NIL", "TURBO", "AIXBT", "W", "PYTH", "LISTA", "CHILLGUY", "GRIFFAIN", "REZ", "IO",
//  "UXLINK", "SHELL", "BTCDOM", "POL", "GRT", "BRETT", "DYDX", "JTO", "MOODENG", "ETHFI",
// "OMNI", "DOGS", "EIGEN", "ENS", "XMR", "D", "SOLV", "VET", "RUNE", "MEW",
//  "AXS", "XCN", "SXP", "MASK", "BMT", "BANANA", "NFP", "XTZ", "FORTH", "ALPHA",
// "REI", "AR", "YGG", "PAXG", "SPX", "TRU", "ID", "GTC", "CHZ", "BLUR",
// "GRASS", "KAVA", "SPELL", "RSR", "FIDA", "MORPHO", "VANA", "RPL", "ANKR", "TLM",
// "CFX", "HIPPO", "TST", "ZEN", "ME", "AI", "MOVR", "GLM", "ZIL", "1000RATS",
// "HOOK", "ALT", "ZK", "COW", "SUSHI", "MLN", "SANTOS", "1MBABYDOGE", "SNX",
//  "STORJ", "BEAMX", "WOO", "B3", "AEVO", "CTSI", "1000LUNC", "OXT", "ILV", "IOTA",
// "QTUM", "EPIC", "NEIROETH", "THE", "EDU", "ZEC", "AERO", "SKL", "ARPA", "BAN",
//  "COMP", "CHR", "NMR", "ZETA", "LUMIA", "COOKIE", "PHB", "MINA", "1000CHEEMS", "1000CAT",
// "GHST", "KAS", "SUPER", "ROSE", "IOTX", "DYM", "EGLD", "SONIC", "RDNT", "LPT",
// "LUNA2", "PLUME", "XVG", "MYRO", "LQTY", "USTC", "C98", "SCR", "BB", "STEEM",
//  "ONE", "FLOW", "QNT", "SSV", "POWR", "DEXE", "CGPT", "VANRY", "POLYX", "ZRX",
//  "YFI", "TNSR", "GMX", "SYS", "1INCH", "CELO", "METIS", "1000X", "HEI", "ONT",
//  "KSM", "KDA", "IOST", "BAT", "CETUS", "DF", "LRC", "HIVE", "DEGEN",
// "MTL", "SAFE", "CELR", "AVA", "CKB", "RIF", "FIO", "1000000MOG", "KNC", "ICX",
// "CYBER", "RONIN", "ONG", "VVV", "FXS", "MAV", "DEGO", "DASH", "ASTR", "PHA",
// "AXL", "BICO", "BAND", "SCRT", "HOT", "TOKEN", "STG", "PONKE", "DODOX", "DUSK",
// "SYN", "RVN", "UMA", "PIPPIN", "DENT", "PROM", "FLUX", "VELODROME", "SWELL", "MOCA",
// "ATA", "KAIA", "ATH", "XVS", "G", "LSK", "SUN", "NTRN", "RLC", "JOE",
// "1000XEC", "VIC", "SFP", "TWT", "QUICK", "BSV", "DIA", "BNT", "ACX", "COS",
// "ETHW", "DRIFT", "AKT", "KMNO", "SLERF", "DEFI", "USDC"

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

//                 int leverage = 6; // Default leverage

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
//             JSONArray candles = getCandlestickData(pair, "60m", LOOKBACK_PERIOD);

//             if (candles == null || candles.length() < 2) {
//                 System.out.println("⚠️ Not enough data for trend analysis, using default strategy");
//                 return Math.random() > 0.5 ? "buy" : "sell";
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
//             return Math.random() > 0.5 ? "buy" : "sell";
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
//             return Math.random() > 0.5 ? "buy" : "sell";
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
