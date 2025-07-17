
import org.json.JSONArray;
import org.json.JSONObject;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class SimpleCoinDCXTrader {

    private static final String API_KEY = System.getenv("DELTA_API_KEY");
    private static final String API_SECRET = System.getenv("DELTA_API_SECRET");
    private static final String BASE_URL = "https://api.coindcx.com";
    private static final String PUBLIC_API_URL = "https://public.coindcx.com";

    private static final double MARGIN_PER_TRADE = 600.0;
    private static final int LEVERAGE = 3;
    private static final double TP_PERCENT = 0.005;   // 0.5% TP for fast hits
    private static final double SL_PERCENT = 0.02;   // 2% SL for risk control
    private static final int MAX_POSITIONS = 3;
    private static final int SHORT_MA = 5;
    private static final int LONG_MA = 20;

    private static final String[] PAIRS = {"B-BTC_USDT", "B-ETH_USDT"};

    private static final Queue<Long> rateLimiterQueue = new LinkedList<>();
    private static final int MAX_REQUESTS_PER_MINUTE = 60;
    private static final long RATE_LIMIT_WINDOW_MS = 60000;

    public static void main(String[] args) throws InterruptedException {
        if (API_KEY == null || API_SECRET == null) {
            System.err.println("Please set DELTA_API_KEY and DELTA_API_SECRET as environment variables.");
            return;
        }

        while (true) {
            try {
                Set<String> activePositions = getActivePositions();
                System.out.println("Active positions: " + activePositions.size());
                int freeSlots = MAX_POSITIONS - activePositions.size();
                if (freeSlots <= 0) {
                    System.out.println("Max open positions reached, sleeping...");
                    Thread.sleep(30_000);
                    continue;
                }

                for (String pair : PAIRS) {
                    if (activePositions.contains(pair)) continue;

                    System.out.println("Processing " + pair);
                    if (shouldPlaceOrder(pair)) {
                        placeMarketOrder(pair, MARGIN_PER_TRADE);
                    }
                    Thread.sleep(2000);
                }

                Thread.sleep(15_000);
            } catch (Exception e) {
                System.err.println("Error in main loop: " + e.getMessage());
                Thread.sleep(15_000);
            }
        }
    }

    private static boolean shouldPlaceOrder(String pair) throws Exception {
        JSONArray candles = getCandlestickData(pair, "5", 30);
        if (candles == null || candles.length() < LONG_MA) return false;

        double[] closes = extractField(candles, "close");
        double shortMA = calculateSMA(closes, SHORT_MA);
        double longMA = calculateSMA(closes, LONG_MA);

        if (shortMA > longMA) {
            System.out.println(pair + ": Signal BUY (short MA > long MA)");
            return true;
        } else {
            System.out.println(pair + ": No suitable signal");
            return false;
        }
    }

    private static double calculateSMA(double[] closes, int period) {
        if (closes.length < period) return 0;
        double sum = 0;
        for (int i = closes.length - period; i < closes.length; i++) sum += closes[i];
        return sum / period;
    }

    private static double[] extractField(JSONArray candles, String key) {
        double[] arr = new double[candles.length()];
        for (int i = 0; i < candles.length(); i++)
            arr[i] = candles.getJSONObject(i).getDouble(key);
        return arr;
    }

    private static JSONArray getCandlestickData(String pair, String resolution, int count) throws Exception {
        rateLimit();
        long now = Instant.now().getEpochSecond();
        long from = now - (count * 5 * 60);
        String url = PUBLIC_API_URL + "/market_data/candlesticks?pair=" + pair + "&from=" + from + "&to=" + now + "&resolution=" + resolution + "&pcode=f";

        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(10000);
        con.setReadTimeout(10000);
        con.setRequestMethod("GET");
        if (con.getResponseCode() == 200) {
            String resp = readInputStream(con.getInputStream());
            JSONObject json = new JSONObject(resp);
            if ("ok".equals(json.optString("s"))) {
                return json.getJSONArray("data");
            }
        }
        return null;
    }

    private static Set<String> getActivePositions() {
        Set<String> active = new HashSet<>();
        try {
            rateLimit();
            JSONObject body = new JSONObject();
            body.put("timestamp", Instant.now().toEpochMilli());
            body.put("page", "1");
            body.put("size", "50");
            body.put("margin_currency_short_name", new String[]{"USDT"});

            String response = sendAuthenticatedRequest(BASE_URL + "/exchange/v1/derivatives/futures/positions", body.toString());
            JSONArray positions = new JSONArray(response);
            for (int i = 0; i < positions.length(); i++) {
                JSONObject pos = positions.getJSONObject(i);
                if (pos.optDouble("active_pos", 0) != 0 || pos.optDouble("locked_margin", 0) > 0) {
                    active.add(pos.optString("pair", ""));
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to get active positions: " + e.getMessage());
        }
        return active;
    }

    private static void placeMarketOrder(String pair, double margin) throws Exception {
        JSONObject instrument = getInstrumentDetails(pair);
        if (instrument == null) {
            System.out.println("Skipping " + pair + ": instrument info unavailable");
            return;
        }

        double lastPrice = getLastPrice(pair);
        if (lastPrice == 0) {
            System.out.println("Skipping " + pair + ": last price unavailable");
            return;
        }

        double qty = margin / (lastPrice * LEVERAGE);
        qty = Math.floor(qty / instrument.getDouble("quantity_increment")) * instrument.getDouble("quantity_increment");

        if (qty < instrument.getDouble("min_quantity")) {
            System.out.println("Skipping " + pair + ": calculated qty below min");
            return;
        }

        rateLimit();

        JSONObject order = new JSONObject();
        order.put("side", "buy");
        order.put("pair", pair);
        order.put("order_type", "market_order");
        order.put("total_quantity", qty);
        order.put("leverage", LEVERAGE);
        order.put("position_margin_type", "isolated");
        order.put("margin_currency_short_name", "USDT");

        JSONObject body = new JSONObject();
        body.put("timestamp", Instant.now().toEpochMilli());
        body.put("order", order);

        String resp = sendAuthenticatedRequest(BASE_URL + "/exchange/v1/derivatives/futures/orders/create", body.toString());
        JSONArray arr = new JSONArray(resp);

        if (arr.length() > 0) {
            String orderId = arr.getJSONObject(0).getString("id");
            System.out.println("Order placed: " + orderId);

            Thread.sleep(3000);

            String positionId = getPositionId(pair);

            setTakeProfitStopLoss(positionId, pair, lastPrice);
        }
    }

    private static void setTakeProfitStopLoss(String positionId, String pair, double entryPrice) throws Exception {
        JSONObject instrument = getInstrumentDetails(pair);
        if (instrument == null) return;

        double tick = instrument.getDouble("price_increment");
        double tp = Math.round((entryPrice * (1 + TP_PERCENT)) / tick) * tick;
        double sl = Math.round((entryPrice * (1 - SL_PERCENT)) / tick) * tick;

        JSONObject tpObj = new JSONObject();
        tpObj.put("stop_price", tp);
        tpObj.put("limit_price", tp);
        tpObj.put("order_type", "take_profit_market");

        JSONObject slObj = new JSONObject();
        slObj.put("stop_price", sl);
        slObj.put("limit_price", sl);
        slObj.put("order_type", "stop_market");

        JSONObject body = new JSONObject();
        body.put("timestamp", Instant.now().toEpochMilli());
        body.put("id", positionId);
        body.put("take_profit", tpObj);
        body.put("stop_loss", slObj);

        String res = sendAuthenticatedRequest(BASE_URL + "/exchange/v1/derivatives/futures/positions/create_tpsl", body.toString());
        System.out.println("Set TP/SL for " + pair);
    }

    private static String getPositionId(String pair) throws Exception {
        rateLimit();

        JSONObject body = new JSONObject();
        body.put("timestamp", Instant.now().toEpochMilli());
        body.put("page", "1");
        body.put("size", "20");
        body.put("margin_currency_short_name", new String[]{"USDT"});

        String resp = sendAuthenticatedRequest(BASE_URL + "/exchange/v1/derivatives/futures/positions", body.toString());
        JSONArray arr = new JSONArray(resp);

        for (int i = 0; i < arr.length(); i++) {
            JSONObject pos = arr.getJSONObject(i);
            if (pair.equals(pos.optString("pair", ""))) {
                return pos.optString("id", null);
            }
        }
        return null;
    }

    private static double getLastPrice(String pair) throws Exception {
        rateLimit();
        String url = PUBLIC_API_URL + "/market_data/trade_history?pair=" + pair + "&limit=1";
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);

        if (conn.getResponseCode() == 200) {
            String response = readResponse(conn.getInputStream());
            JSONArray trades = new JSONArray(response);
            if (trades.length() > 0) {
                return trades.getJSONObject(0).getDouble("p");
            }
        }
        return 0;
    }

    private static JSONObject getInstrumentDetails(String pair) throws Exception {
        if (System.currentTimeMillis() - instrumentCacheLastUpdated > CACHE_TTL_MS) refreshInstrumentCache();
        return instrumentCache.get(pair);
    }

    private static void refreshInstrumentCache() throws Exception {
        rateLimit();
        String url = BASE_URL + "/exchange/v1/derivatives/futures/data/active_instruments";
        String response = sendPublicRequest(url);
        JSONArray instruments = new JSONArray(response);

        for (int i = 0; i < instruments.length(); i++) {
            String pair = instruments.getString(i);
            if (!Arrays.asList(TRADING_PAIRS).contains(pair)) continue;

            String u2 = BASE_URL + "/exchange/v1/derivatives/futures/data/instrument?pair=" + pair + "&margin_currency_short_name=USDT";
            String resp2 = sendPublicRequest(u2);
            JSONObject obj = new JSONObject(resp2).getJSONObject("instrument");
            instrumentCache.put(pair, obj);
        }
        instrumentCacheLastUpdated = System.currentTimeMillis();
        System.out.println("Refreshed instrument cache.");
    }

    private static String sendAuthenticatedRequest(String endpoint, String bodyJson) throws Exception {
        String sign = hmacSHA256(API_SECRET, bodyJson);
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("X-AUTH-APIKEY", API_KEY);
        conn.setRequestProperty("X-AUTH-SIGNATURE", sign);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(10_000);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(bodyJson.getBytes(StandardCharsets.UTF_8));
        }

        if (conn.getResponseCode() != 200) {
            String err = readResponse(conn.getErrorStream());
            throw new RuntimeException("HTTP " + conn.getResponseCode() + ": " + err);
        }

        return readResponse(conn.getInputStream());
    }

    private static String sendPublicRequest(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        if (conn.getResponseCode() != 200) throw new IOException("HTTP " + conn.getResponseCode());
        return readResponse(conn.getInputStream());
    }

    private static String readResponse(InputStream is) throws IOException {
        if (is == null) return "";
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
            return br.lines().collect(Collectors.joining("\n"));
        }
    }

    private static String hmacSHA256(String key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        StringBuilder res = new StringBuilder();
        for (byte b : hash) res.append(String.format("%02x", b));
        return res.toString();
    }

    private static void rateLimit() throws InterruptedException {
        long now = System.currentTimeMillis();
        while (!rateLimiterQueue.isEmpty() && now - rateLimiterQueue.peek() > RATE_LIMIT_WINDOW_MS) {
            rateLimiterQueue.poll();
        }
        if (rateLimiterQueue.size() >= MAX_REQUESTS_PER_MINUTE) {
            long waitTime = RATE_LIMIT_WINDOW_MS - (now - rateLimiterQueue.peek()) + 100;
            Thread.sleep(waitTime);
        }
        rateLimiterQueue.offer(System.currentTimeMillis());
    }

    private static final LinkedList<Long> rateLimiterQueue = new LinkedList<>();

    private static void log(String message) {
        System.out.println(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + " - " + message);
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
// import java.time.LocalDateTime;
// import java.time.format.DateTimeFormatter;
// import java.util.*;
// import java.util.concurrent.*;
// import java.util.stream.Collectors;

// public class CoinDCXFuturesTrader8C_BUY_SELL_NEW_LOGIC_THREE {

//     // --- CONFIG ---
//     private static final String API_KEY = System.getenv("DELTA_API_KEY");
//     private static final String API_SECRET = System.getenv("DELTA_API_SECRET");
//     private static final String BASE_URL = "https://api.coindcx.com";
//     private static final String PUBLIC_API_URL = "https://public.coindcx.com";
//     private static final int MAX_ACTIVE_POSITIONS = 5;
//     private static final double MARGIN = 600.0;           // USDT per order
//     private static final double TP_PCT = 0.008;           // 0.8%
//     private static final double SL_PCT = 0.05;            // 5%
//     private static final int LEVERAGE = 3;
//     private static final int RSI_PERIOD = 14;
//     private static final int LOOKBACK_CANDLES = 26;
//     private static final int MAX_REQUESTS_PER_MINUTE = 80;
//     private static final long RATE_LIMIT_MS = 60_000;
//     private static final long CACHE_TTL_MS = 600_000;

//     private static final String[] COIN_SYMBOLS = {"BTC", "ETH", "BNB", "ADA", "DOT"};
//     private static final String[] TRADING_PAIRS = Arrays.stream(COIN_SYMBOLS)
//             .map(s -> "B-" + s + "_USDT").toArray(String[]::new);

//     private static final ExecutorService exec = Executors.newFixedThreadPool(3);
//     private static final Queue<Long> rateLimitTimestamps = new LinkedList<>();
//     private static final Map<String, JSONObject> instrumentCache = new HashMap<>();
//     private static long instrumentCacheTime = 0;

//     public static void main(String[] args) throws Exception {
//         if (API_KEY == null || API_SECRET == null) {
//             System.err.println("[FATAL] Set DELTA_API_KEY and DELTA_API_SECRET env vars.");
//             return;
//         }
//         log("Starting bot...");
//         refreshInstrumentCache();

//         while (true) {
//             try {
//                 Set<String> open = getActivePositions();
//                 log("Active: " + open.size() + "/" + MAX_ACTIVE_POSITIONS);
//                 if (open.size() >= MAX_ACTIVE_POSITIONS) {
//                     log("Max positions - sleeping...");
//                     Thread.sleep(30_000); continue;
//                 }
//                 int slots = MAX_ACTIVE_POSITIONS - open.size();
//                 List<Future<?>> futs = new ArrayList<>();
//                 for (String pair : TRADING_PAIRS) {
//                     if (open.contains(pair)) continue;
//                     futs.add(exec.submit(() -> tradePair(pair)));
//                     if (--slots <= 0) break;
//                 }
//                 for (Future<?> f : futs) try { f.get(45, TimeUnit.SECONDS); } catch(Exception ignored) {}
//                 Thread.sleep(20_000);
//             } catch (Exception e) {
//                 loge("Main loop error: " + e);
//                 Thread.sleep(15_000);
//             }
//         }
//     }

//     private static void tradePair(String pair) {
//         try {
//             JSONArray candles = getCandlestickData(pair, "5", LOOKBACK_CANDLES);
//             if (candles == null || candles.length() < LOOKBACK_CANDLES) {
//                 log(pair + ": not enough candles, skipping.");
//                 return;
//             }
//             double[] closes = extractArray(candles, "close");
//             double rsi = calcRSI(closes, RSI_PERIOD);
//             double p0 = closes[0], p1 = closes[closes.length-1];
//             double trend = (p1-p0)/p0;
//             TradeSignal sig = TradeSignal.HOLD;
//             if ((trend > 0.02 && rsi < 70) || rsi < 30) sig = TradeSignal.BUY;
//             else if ((trend < -0.02 && rsi > 30) || rsi > 70) sig = TradeSignal.SELL;
//             if (sig == TradeSignal.HOLD) {
//                 log(pair + ": no signal.");
//                 return;
//             }
//             double curPrice = p1;
//             double qty = calcQty(pair, curPrice);
//             if (qty <= 0) {
//                 log(pair + ": calculated qty zero.");
//                 return;
//             }
//             String oid = placeOrder(pair, sig, qty);
//             if (oid == null) { loge(pair + ": no order ID placed."); return; }
//             log(pair + ": order placed " + oid);
//             Thread.sleep(2000);

//             String posId = getPositionId(pair);
//             if (posId == null) { loge(pair + ": no positionId found post order."); return; }
//             setTPSL(posId, pair, curPrice, sig);
//         } catch(Exception e){ loge(pair + ": error: "+e);}
//     }

//     private static JSONArray getCandlestickData(String pair, String resolution, int count) {
//         try {
//             waitForRateLimit();
//             long now = System.currentTimeMillis()/1000, from = now - count*5*60;
//             String url = PUBLIC_API_URL + "/market_data/candlesticks?pair=" + pair + "&from=" + from + "&to=" + now + "&resolution=" + resolution + "&pcode=f";
//             HttpURLConnection c = (HttpURLConnection)new URL(url).openConnection();
//             c.setRequestMethod("GET"); c.setConnectTimeout(10000); c.setReadTimeout(10000);
//             if (c.getResponseCode() == 200) {
//                 String resp = readAll(c.getInputStream());
//                 JSONObject jo = new JSONObject(resp);
//                 if ("ok".equals(jo.optString("s"))) return jo.getJSONArray("data");
//             }
//         }catch(Exception e){loge(pair+": candlestick fetch err: "+e);}
//         return null;
//     }

//     private static double[] extractArray(JSONArray ar, String key) {
//         double[] out = new double[ar.length()];
//         for (int i=0;i<ar.length();++i) out[i]=ar.getJSONObject(i).getDouble(key);
//         return out;
//     }

//     private static double calcRSI(double[] closes, int n) {
//         if (closes.length <= n) return 50;
//         double up = 0, dn = 0;
//         for (int i=1;i<=n;i++) {
//             double d = closes[i] - closes[i-1];
//             if (d > 0) up += d; else dn -= d;
//         }
//         up/=n; dn/=n;
//         for (int i=n+1;i<closes.length;i++) {
//             double d = closes[i] - closes[i-1];
//             up = (up*(n-1)+(d>0?d:0))/n;
//             dn = (dn*(n-1)+(d<0?-d:0))/n;
//         }
//         if (dn==0) return 100;
//         double rs = up/dn;
//         return 100-100/(1+rs);
//     }

//     private static double calcQty(String pair, double price) {
//         try {
//             JSONObject inst = getInstrumentDetails(pair);
//             if (inst==null) return 0;
//             double minQ = inst.getDouble("min_quantity");
//             double maxQ = inst.getDouble("max_quantity");
//             double qInc = inst.getDouble("quantity_increment");
//             double minNotional = inst.getDouble("min_notional");
//             double baseQty = MARGIN/price;
//             if (baseQty*price < minNotional) baseQty = minNotional/price;
//             double qty = Math.floor(baseQty/qInc)*qInc;
//             return Math.max(Math.min(qty,maxQ),minQ);
//         }catch(Exception e){ return 0;}
//     }

//     private static String placeOrder(String pair, TradeSignal sig, double qty) {
//         try {
//             waitForRateLimit();
//             JSONObject order = new JSONObject();
//             order.put("side", sig == TradeSignal.BUY ? "buy":"sell");
//             order.put("pair", pair);
//             order.put("order_type", "market_order");
//             order.put("total_quantity", qty);
//             order.put("leverage", LEVERAGE);
//             order.put("position_margin_type", "isolated");
//             order.put("margin_currency_short_name", "USDT");
//             JSONObject body = new JSONObject();
//             body.put("timestamp", Instant.now().toEpochMilli());
//             body.put("order",order);
//             JSONArray arr = new JSONArray(sendAuthenticatedRequest(BASE_URL+"/exchange/v1/derivatives/futures/orders/create",body.toString()));
//             if(arr.length()>0) return arr.getJSONObject(0).optString("id",null);
//         }catch(Exception e){loge(pair+": placeOrder "+e);}
//         return null;
//     }

//     private static void setTPSL(String posId, String pair, double entry, TradeSignal sig) {
//         try {
//             JSONObject inst = getInstrumentDetails(pair);
//             double tick = inst.getDouble("price_increment");
//             double tp = sig==TradeSignal.BUY?entry*(1+TP_PCT):entry*(1-TP_PCT);
//             double sl = sig==TradeSignal.BUY?entry*(1-SL_PCT):entry*(1+SL_PCT);
//             tp = Math.round(tp/tick)*tick; sl = Math.round(sl/tick)*tick;
//             JSONObject tpObj = new JSONObject(), slObj = new JSONObject();
//             tpObj.put("stop_price", tp); tpObj.put("limit_price", tp); tpObj.put("order_type","take_profit_market");
//             slObj.put("stop_price", sl); slObj.put("limit_price", sl); slObj.put("order_type", "stop_market");
//             JSONObject payload = new JSONObject();
//             payload.put("timestamp", Instant.now().toEpochMilli());
//             payload.put("id", posId);
//             payload.put("take_profit", tpObj);
//             payload.put("stop_loss", slObj);
//             sendAuthenticatedRequest(BASE_URL+"/exchange/v1/derivatives/futures/positions/create_tpsl",payload.toString());
//             log("Set TP/SL: "+pair+" TP@"+tp+" SL@"+sl);
//         }catch(Exception e){loge("TPSL: "+e);}
//     }

//     private static JSONObject getInstrumentDetails(String pair) throws Exception {
//         long now = System.currentTimeMillis();
//         if (instrumentCache.isEmpty() || now-instrumentCacheTime > CACHE_TTL_MS) {
//             refreshInstrumentCache();
//         }
//         return instrumentCache.get(pair);
//     }

//     private static void refreshInstrumentCache() throws Exception {
//         waitForRateLimit();
//         JSONArray actives = new JSONArray(sendPublicRequest(BASE_URL+"/exchange/v1/derivatives/futures/data/active_instruments"));
//         for (int i=0;i<actives.length();i++) {
//             String pair = actives.getString(i);
//             if (!Arrays.asList(TRADING_PAIRS).contains(pair)) continue;
//             String js = sendPublicRequest(BASE_URL+"/exchange/v1/derivatives/futures/data/instrument?pair="+pair+"&margin_currency_short_name=USDT");
//             JSONObject inst = new JSONObject(js).getJSONObject("instrument");
//             instrumentCache.put(pair, inst);
//         }
//         instrumentCacheTime = System.currentTimeMillis();
//         log("Refreshed instrument cache: "+instrumentCache.size());
//     }

//     private static Set<String> getActivePositions() {
//         Set<String> act = new HashSet<>();
//         try {
//             waitForRateLimit();
//             JSONObject body = new JSONObject();
//             body.put("timestamp", Instant.now().toEpochMilli());
//             body.put("page", "1");
//             body.put("size", "100");
//             body.put("margin_currency_short_name", new String[]{"USDT"});
//             JSONArray arr = new JSONArray(sendAuthenticatedRequest(BASE_URL+"/exchange/v1/derivatives/futures/positions",body.toString()));
//             for (int i=0;i<arr.length();i++) {
//                 JSONObject pos = arr.getJSONObject(i);
//                 if (pos.optDouble("active_pos",0)!=0||pos.optDouble("locked_margin",0)>0)
//                     act.add(pos.optString("pair",""));
//             }
//         }catch(Exception ignore){}
//         return act;
//     }

//     private static String getPositionId(String pair) {
//         try {
//             waitForRateLimit();
//             JSONObject body = new JSONObject();
//             body.put("timestamp", Instant.now().toEpochMilli());
//             body.put("page", "1");
//             body.put("size", "10");
//             body.put("margin_currency_short_name", new String[]{"USDT"});
//             JSONArray arr = new JSONArray(sendAuthenticatedRequest(BASE_URL+"/exchange/v1/derivatives/futures/positions",body.toString()));
//             for (int i=0;i<arr.length();i++) {
//                 JSONObject pos = arr.getJSONObject(i);
//                 if (pair.equals(pos.optString("pair",""))) return pos.optString("id",null);
//             }
//         }catch(Exception ignore){}
//         return null;
//     }

//     private static String sendAuthenticatedRequest(String endpoint, String jsonBody) throws Exception {
//         String sig = hmacSha256(API_SECRET, jsonBody);
//         HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
//         conn.setRequestMethod("POST");
//         conn.setRequestProperty("Content-Type", "application/json");
//         conn.setRequestProperty("X-AUTH-APIKEY", API_KEY);
//         conn.setRequestProperty("X-AUTH-SIGNATURE", sig);
//         conn.setDoOutput(true); conn.setConnectTimeout(12000); conn.setReadTimeout(12000);
//         try (OutputStream os = conn.getOutputStream()) { os.write(jsonBody.getBytes(StandardCharsets.UTF_8)); }
//         if (conn.getResponseCode() != 200) throw new IOException("HTTP "+conn.getResponseCode()+": "+readAll(conn.getErrorStream()));
//         return readAll(conn.getInputStream());
//     }

//     private static String sendPublicRequest(String url) throws Exception {
//         HttpURLConnection conn = (HttpURLConnection)new URL(url).openConnection();
//         conn.setRequestMethod("GET");conn.setConnectTimeout(10000);conn.setReadTimeout(10000);
//         if(conn.getResponseCode()!=200) throw new IOException("HTTP "+conn.getResponseCode());
//         return readAll(conn.getInputStream());
//     }

//     private static String readAll(InputStream is) throws IOException {
//         if (is == null) return "";
//         try (BufferedReader r = new BufferedReader(new InputStreamReader(is))) {
//             return r.lines().collect(Collectors.joining("\n"));
//         }
//     }

//     private static String hmacSha256(String secret, String payload) throws Exception {
//         Mac m = Mac.getInstance("HmacSHA256");
//         m.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
//         byte[] h = m.doFinal(payload.getBytes(StandardCharsets.UTF_8));
//         StringBuilder sb = new StringBuilder();
//         for (byte b : h) sb.append(String.format("%02x", b & 0xff));
//         return sb.toString();
//     }

//     private static void waitForRateLimit() {
//         long now = System.currentTimeMillis();
//         while (!rateLimitTimestamps.isEmpty()
//                 && now-rateLimitTimestamps.peek() > RATE_LIMIT_MS) rateLimitTimestamps.poll();
//         if (rateLimitTimestamps.size() >= MAX_REQUESTS_PER_MINUTE) {
//             try { Thread.sleep(700);} catch(Exception ignore){}
//         }
//         rateLimitTimestamps.offer(now);
//     }

//     private static void log(String s) { System.out.println("["+LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))+"] "+s);}
//     private static void loge(String s) { System.err.println("["+LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))+"] ERROR: "+s);}
//     private enum TradeSignal { BUY, SELL, HOLD }
// }



// -----------------------------------------------------------------------------------------------------------------------------------------






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
//    private static final String API_SECRET = System.getenv("DELTA_API_SECRET");

//     private static final String BASE_URL = "https://api.coindcx.com";
//     private static final String PUBLIC_API_URL = "https://public.coindcx.com";
//     private static final double MAX_MARGIN = 600.0;
//     private static final int MAX_ORDER_STATUS_CHECKS = 10;
//     private static final int ORDER_CHECK_DELAY_MS = 1000;
//     private static final long TICK_SIZE_CACHE_TTL_MS = 3600000; // 1 hour cache
// //    private static final int LOOKBACK_PERIOD = 4; // Hours for trend analysis
// private static final int LOOKBACK_PERIOD = 15; // Minutes for trend analysis (changed from hours)
//     private static final double TREND_THRESHOLD = 0.02; // 2% change threshold for trend
//     private static final double TP_PERCENTAGE = 0.01; // 3% take profit
//     private static final double SL_PERCENTAGE = 0.10; // 5% stop loss

//     // Cache for instrument details with timestamp
//     private static final Map<String, JSONObject> instrumentDetailsCache = new ConcurrentHashMap<>();
//     private static long lastInstrumentUpdateTime = 0;

//     private static final String[] COIN_SYMBOLS = {
//             "1000SATS", "1000X", "ACT", "ADA", "AIXBT", "AI16Z", "ALGO", "ALT", "API3",
//             "ARB", "ARC", "AVAAI", "BAKE", "BB", "BIO", "BLUR", "BMT", "BONK", "COOKIE",
//             "DOGE", "DOGS", "DYDX", "EIGEN", "ENA", "EOS", "ETHFI", "FARTCOIN", "FLOKI",
//             "GALA", "GLM", "GOAT", "GRIFFAIN", "HBAR", "HIVE", "IO", "IOTA", "JASMY",
//             "JUP", "KAITO", "LDO", "LISTA", "MANA", "MANTA", "MEME", "MELANIA", "MOODENG",
//             "MOVE", "MUBARAK", "NEIRO", "NOT", "ONDO", "OP", "PEOPLE", "PEPE", "PENGU",
//             "PI", "PNUT", "POL", "POPCAT", "RARE", "RED", "RSR", "SAGA", "SAND", "SEI",
//             "SHIB", "SOLV", "SONIC", "SPX", "STX", "SUN", "SWARMS", "SUSHI", "TST", "TRX",
//             "USUAL", "VINE", "VIRTUAL", "WIF", "WLD", "XAI", "XLM", "XRP", "ZK","TAO",
//             "TRUMP","PERP","OM","BNB","LINK","GMT","LAYER","AVAX","HIGH","ALPACA","FLM",
//             "BSW","FIL","DOT","LTC","ARK","ENJ","RENDER","CRV","BCH","OGN","1000SHIB","AAVE",
//             "ORCA","NEAR","T","FUN","VTHO","ALCH","GAS","TIA","MBOX","APT","ORDI","INJ","BEL",
//             "PARTI","BIGTIME","ETC","BOME","UNI","TON","1000BONK","ACH","XLM","ATOM","LEVER","S"
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

//                 int leverage = 03; // Default leverage

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

// //    private static String determinePositionSide(String pair) {
// //        try {
// //            JSONArray candles = getCandlestickData(pair, "1h", LOOKBACK_PERIOD);
// //
// //            if (candles == null || candles.length() < 2) {
// //                System.out.println("⚠️ Not enough data for trend analysis, using default strategy");
// //                return Math.random() > 0.5 ? "buy" : "sell";
// //            }
// //
// //            double firstClose = candles.getJSONObject(0).getDouble("close");
// //            double lastClose = candles.getJSONObject(candles.length() - 1).getDouble("close");
// //            double priceChange = (lastClose - firstClose) / firstClose;
// //
// //            System.out.println("Trend Analysis for " + pair + ":");
// //            System.out.println("First Close: " + firstClose);
// //            System.out.println("Last Close: " + lastClose);
// //            System.out.println("Price Change: " + (priceChange * 100) + "%");
// //
// //            if (priceChange > TREND_THRESHOLD) {
// //                System.out.println("📈 Uptrend detected - Going LONG");
// //                return "buy";
// //            } else if (priceChange < -TREND_THRESHOLD) {
// //                System.out.println("📉 Downtrend detected - Going SHORT");
// //                return "sell";
// //            } else {
// //                System.out.println("➡️ Sideways market - Using RSI for decision");
// //                return determineSideWithRSI(candles);
// //            }
// //        } catch (Exception e) {
// //            System.err.println("❌ Error determining position side: " + e.getMessage());
// //            return Math.random() > 0.5 ? "buy" : "sell";
// //        }
// //    }

//     private static String determinePositionSide(String pair) {
//         try {
//             // Changed resolution from "1h" to "5m" to match 5-minute lookback
//             JSONArray candles = getCandlestickData(pair, "5m", LOOKBACK_PERIOD);

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





// ---------------------------------------------------------------------------------------------------------------------------------------------------




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
//     private static final String API_KEY = "72707da4dc9c55175867e72d425dbc210c0da5ae3765e381";
//     private static final String API_SECRET = "72973c321fe3ee434029f8cde165212dee99cdb9b7d99adf1cc02fe539e5be14";
//     private static final String BASE_URL = "https://api.coindcx.com";
//     private static final String PUBLIC_API_URL = "https://public.coindcx.com";
//     private static final double MAX_MARGIN = 600.0;
//     private static final int MAX_ORDER_STATUS_CHECKS = 10;
//     private static final int ORDER_CHECK_DELAY_MS = 1000;
//     private static final long TICK_SIZE_CACHE_TTL_MS = 3600000; // 1 hour cache
//     private static final int LOOKBACK_PERIOD = 4; // Hours for trend analysis
//     private static final double TREND_THRESHOLD = 0.02; // 2% change threshold for trend
//     private static final double TRAILING_DISTANCE_PERCENT = 0.01; // 1% trailing distance
//     private static final int TRAILING_UPDATE_INTERVAL_SEC = 10; // Check every 10 seconds

//     // Cache for instrument details with timestamp
//     private static final Map<String, JSONObject> instrumentDetailsCache = new ConcurrentHashMap<>();
//     private static long lastInstrumentUpdateTime = 0;

//     private static final String[] COIN_SYMBOLS = {
//             "1000SATS", "1000X", "ACT", "ADA", "AIXBT", "AI16Z", "ALGO", "ALT", "API3",
//             "ARB", "ARC", "AVAAI", "BAKE", "BB", "BIO", "BLUR", "BMT", "BONK", "COOKIE",
//             "DOGE", "DOGS", "DYDX", "EIGEN", "ENA", "EOS", "ETHFI", "FARTCOIN", "FLOKI",
//             "GALA", "GLM", "GOAT", "GRIFFAIN", "HBAR", "HIVE", "IO", "IOTA", "JASMY",
//             "JUP", "KAITO", "LDO", "LISTA", "MANA", "MANTA", "MEME", "MELANIA", "MOODENG",
//             "MOVE", "MUBARAK", "NEIRO", "NOT", "ONDO", "OP", "PEOPLE", "PEPE", "PENGU",
//             "PI", "PNUT", "POL", "POPCAT", "RARE", "RED", "RSR", "SAGA", "SAND", "SEI",
//             "SHIB", "SOLV", "SONIC", "SPX", "STX", "SUN", "SWARMS", "SUSHI", "TST", "TRX",
//             "USUAL", "VINE", "VIRTUAL", "WIF", "WLD", "XAI", "XLM", "XRP", "ZK","TAO",
//             "TRUMP","PERP","OM","BNB","LINK","GMT","LAYER","AVAX","HIGH","ALPACA","FLM",
//             "BSW","FIL","DOT","LTC","ARK","ENJ","RENDER","CRV","BCH","OGN","1000SHIB","AAVE",
//             "ORCA","NEAR","T","FUN","VTHO","ALCH","GAS","TIA","MBOX","APT","ORDI","INJ","BEL",
//             "PARTI","BIGTIME","ETC","BOME","UNI","TON","1000BONK","ACH","XLM","ATOM","LEVER","S"
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

//                 int leverage = 2; // Default leverage

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

//                 double margin = MAX_MARGIN / leverage;
//                 double[] tpSlPrices = calculateTpSlPrices(side, entryPrice, quantity, margin);

//                 String positionId = getPositionId(pair);
//                 if (positionId != null) {
//                     setTakeProfitAndStopLoss(positionId,
//                             tpSlPrices[0],
//                             tpSlPrices[1],
//                             side, pair);
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
//             JSONArray candles = getCandlestickData(pair, "1h", LOOKBACK_PERIOD);

//             if (candles == null || candles.length() < 2) {
//                 System.out.println("⚠️ Not enough data for trend analysis, using default strategy");
//                 return Math.random() > 0.5 ? "buy" : "sell";
//             }

//             double firstClose = candles.getJSONObject(0).getDouble("close");
//             double lastClose = candles.getJSONObject(candles.length() - 1).getDouble("close");
//             double priceChange = (lastClose - firstClose) / firstClose;

//             System.out.println("Trend Analysis for " + pair + ":");
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

//     private static double[] calculateTpSlPrices(String side, double entryPrice, double quantity, double margin) {
//         double conversionRate = 93.0;


















//         double tpPercentage = 0.25;
//         double slPercentage = 0.40;







//         double tpPriceDiff = (margin * tpPercentage) / (quantity * conversionRate);
//         double slPriceDiff = (margin * slPercentage) / (quantity * conversionRate);

//         double tpPrice, slPrice;

//         if ("buy".equalsIgnoreCase(side)) {
//             tpPrice = entryPrice + tpPriceDiff;
//             slPrice = entryPrice - slPriceDiff;
//             System.out.println("\nBUY Order - TP (+3%): " + tpPrice + " | SL (-12%): " + slPrice);
//         } else {
//             tpPrice = entryPrice - tpPriceDiff;
//             slPrice = entryPrice + slPriceDiff;
//             System.out.println("\nSELL Order - TP (-3%): " + tpPrice + " | SL (+12%): " + slPrice);
//         }

//         return new double[]{tpPrice, slPrice};
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
//             takeProfit.put("limit_price", Math.round(
//                     (side.equalsIgnoreCase("buy") ? roundedTpPrice * 0.99 : roundedTpPrice * 1.01) / tickSize) * tickSize);
//             takeProfit.put("order_type", "take_profit_market");

//             JSONObject stopLoss = new JSONObject();
//             stopLoss.put("stop_price", roundedSlPrice);
//             stopLoss.put("limit_price", Math.round(
//                     (side.equalsIgnoreCase("buy") ? roundedSlPrice * 1.01 : roundedSlPrice * 0.99) / tickSize) * tickSize);
//             stopLoss.put("order_type", "stop_market");
//             stopLoss.put("trailing_sl", true);  // Enable trailing SL

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
//                 monitorAndUpdateTrailingSL(positionId, side, roundedSlPrice, pair);
//             } else {
//                 System.out.println("❌ Failed to set TP/SL: " + tpslResponse);
//             }
//         } catch (Exception e) {
//             System.err.println("❌ Error setting TP/SL: " + e.getMessage());
//         }
//     }

//     private static void editTrailingStopLoss(String positionId, double slPrice, boolean trailingSl) {
//         try {
//             if (!trailingSl) {
//                 System.out.println("Trailing SL not enabled for this position");
//                 return;
//             }

//             JSONObject body = new JSONObject();
//             body.put("id", positionId);
//             body.put("sl_price", slPrice);
//             body.put("timestamp", Instant.now().toEpochMilli());

//             String response = sendAuthenticatedRequest(
//                     BASE_URL + "/exchange/v1/margin/edit_trailing_sl",
//                     body.toString(),
//                     generateHmacSHA256(API_SECRET, body.toString())
//             );

//             JSONObject responseJson = new JSONObject(response);
//             if (responseJson.optInt("status", 0) == 200) {
//                 System.out.println("✅ Trailing SL price updated successfully to: " + slPrice);
//             } else {
//                 System.out.println("❌ Failed to update trailing SL: " + response);
//             }
//         } catch (Exception e) {
//             System.err.println("❌ Error updating trailing SL: " + e.getMessage());
//         }
//     }

//     private static void monitorAndUpdateTrailingSL(String positionId, String side, double initialSlPrice, String pair) {
//         new Thread(() -> {
//             try {
//                 double highestPrice = getEntryPriceFromPosition(pair, positionId);
//                 double currentSlPrice = initialSlPrice;
//                 double priceIncrement = getTickSizeForPair(pair);
//                 double trailingDistance = highestPrice * TRAILING_DISTANCE_PERCENT;

//                 while (true) {
//                     TimeUnit.SECONDS.sleep(TRAILING_UPDATE_INTERVAL_SEC);

//                     double currentPrice = getLastPrice(pair);
//                     JSONObject position = findPosition(pair);

//                     if (position == null || position.optDouble("active_pos", 0) <= 0) {
//                         System.out.println("Position closed, stopping trailing SL monitoring");
//                         return;
//                     }

//                     if ("buy".equalsIgnoreCase(side)) {
//                         if (currentPrice > highestPrice) {
//                             highestPrice = currentPrice;
//                             double newSlPrice = highestPrice - trailingDistance;
//                             newSlPrice = Math.round(newSlPrice / priceIncrement) * priceIncrement;

//                             if (newSlPrice > currentSlPrice) {
//                                 currentSlPrice = newSlPrice;
//                                 editTrailingStopLoss(positionId, currentSlPrice, true);
//                             }
//                         }
//                     } else {
//                         if (currentPrice < highestPrice) {
//                             highestPrice = currentPrice;
//                             double newSlPrice = highestPrice + trailingDistance;
//                             newSlPrice = Math.round(newSlPrice / priceIncrement) * priceIncrement;

//                             if (newSlPrice < currentSlPrice) {
//                                 currentSlPrice = newSlPrice;
//                                 editTrailingStopLoss(positionId, currentSlPrice, true);
//                             }
//                         }
//                     }
//                 }
//             } catch (Exception e) {
//                 System.err.println("❌ Error in trailing SL monitoring: " + e.getMessage());
//             }
//         }).start();
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
//             body.put("size", "100");  // Increased to 100 to catch all positions
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

//                 // New comprehensive position detection logic
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
