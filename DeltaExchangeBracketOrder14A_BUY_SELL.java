import org.json.JSONArray;
import org.json.JSONObject;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class DeltaExchangeBracketOrder14A_BUY_SELL {

    // API Configuration
    private static final String API_KEY = "FGfU0k3Xs5cxZFUioz2rI7Z77MLwKe";
    private static final String API_SECRET = "ti5ECVcbsMClkJoXy2CpfKLbb4SMjjMGHRIS0JN7QiSCLIHBSiQQ7sd4EjuX";
    private static final String BASE_URL = "https://api.india.delta.exchange";

    // Trade summary class to hold position details
    static class TradeSummary {
        String symbol;
        double entryPrice, contracts, coinQty, margin, tpPrice, tpLimit, slPrice, slLimit;
        int leverage;

        void printSummary() {
            System.out.println("\n📈 TRADE SUMMARY for " + symbol);
            System.out.printf("Entry Price     : %.8f\n", entryPrice);
            System.out.printf("Contracts       : %.2f\n", contracts);
            System.out.printf("Coin Quantity   : %.6f\n", coinQty);
            System.out.printf("Margin Used     : %.4f\n", margin);
            System.out.printf("Leverage        : %dx\n", leverage);
            System.out.printf("Take Profit     : Stop = %.4f, Limit = %.4f\n", tpPrice, tpLimit);
            System.out.printf("Stop Loss       : Stop = %.4f, Limit = %.4f\n", slPrice, slLimit);
            System.out.println("--------------------------------------------------");
        }
    }

    public static void main(String[] args) throws Exception {
        String[] symbols = {
                "1000SATSUSD", "1000XUSD", "ACTUSD", "ADAUSD", "AIXBTUSD", "AI16ZUSD", "ALGOUSD", "ALTUSD", "API3USD",
                "ARBUSD", "ARCUSD", "AVAAIUSD", "BAKEUSD", "BBUSD", "BIOUSD", "BLURUSD", "BMTUSD", "BONKUSD",
                "COOKIEUSD", "DOGEUSD", "DOGSUSD", "DYDXUSD", "EIGENUSD", "ENAUSD", "EOSUSD", "ETHFIUSD", "FARTCOINUSD",
                "FLOKIUSD", "GALAUSD", "GLMUSD", "GOATUSD", "GRIFFAINUSD", "HBARUSD", "HIVEUSD", "IOUSD", "IOTAUSD",
                "JASMYUSD", "JUPUSD", "KAITOUSD", "LDOUSD", "LISTAUSD", "MANAUSD", "MANTAUSD", "MEMEUSD", "MELANIAUSD",
                "MOODENGUSD", "MOVEUSD", "MUBARAKUSD", "NEIROUSD", "NOTUSD", "ONDOUSD", "OPUSD", "PEOPLEUSD", "PEPEUSD",
                "PENGUUSD", "PIUSD", "PNUTUSD", "POLUSD", "POPCATUSD", "RAREUSD", "REDUSD", "RSRUSD", "SAGAUSD", "SANDUSD",
                "SEIUSD", "SHIBUSD", "SOLVUSD", "SONICUSD", "SPXUSD", "STXUSD", "SUSD", "SUNUSD", "SWARMSUSD",
                "SUSHIUSD", "TSTUSD", "TRXUSD", "USUALUSD", "VINEUSD", "VIRTUALUSD", "WIFUSD", "WLDUSD", "XAIUSD", "XLMUSD",
                "XRPUSD", "ZKUSD"
        };

        boolean isBuy = true;

        for (String symbol : symbols) {
            printProcessingHeader(symbol, isBuy);

            try {
                JSONObject productInfo = getProductInfoForSymbol(symbol);
                if (productInfo == null) {
                    System.out.println("❌ Could not find product info for " + symbol);
                    continue;
                }

                int productId = productInfo.getInt("id");
                double contractValue = productInfo.getDouble("contract_value");

                if (hasOpenPosition(productId)) {
                    System.out.println("⚠️ Skipping " + symbol + " - already has open position.");
                    continue;
                }

                changeLeverageToFour(productId);
                placeMarketOrder(symbol, isBuy);
                Thread.sleep(3000); // Wait for order execution

                TradeSummary summary = fetchTradeDetails(productId, symbol, contractValue);
                if (summary != null) {
                    placeBracketOrder(symbol, productId, (int) summary.contracts,
                            summary.entryPrice, summary, isBuy);
                    summary.printSummary();
                }

                isBuy = !isBuy; // Alternate between buy/sell
            } catch (Exception e) {
                System.out.println("❌ Error while processing symbol " + symbol + ": " + e.getMessage());
                e.printStackTrace(System.out);
            }
        }

        System.out.println("\n✅ All symbols processed. Exiting.");
    }

    private static void printProcessingHeader(String symbol, boolean isBuy) {
        System.out.println("\n==========================");
        System.out.println((isBuy ? "🚀" : "🔻") + " Processing Symbol: " + symbol +
                " (" + (isBuy ? "BUY" : "SELL") + ")");
        System.out.println("==========================");
    }

    private static void placeBracketOrder(String symbol, int productId, int size,
                                          double entryPrice, TradeSummary summary,
                                          boolean isBuy) throws Exception {
        String path = "/v2/orders/bracket";
        long timestamp = System.currentTimeMillis() / 1000;

        // Calculate TP/SL prices
        double tpPrice = isBuy ? entryPrice * 1.06 : entryPrice * 0.94;
        double slPrice = isBuy ? entryPrice * 0.92 : entryPrice * 1.08;

//        // Calculate TP/SL prices
//        double tpPrice = isBuy ? entryPrice * 1.08 : entryPrice * 0.92;
//        double slPrice = isBuy ? entryPrice * 0.50 : entryPrice * 1.50;


        double tpLimit = isBuy ? tpPrice - 0.1 : tpPrice + 0.1;
        double slLimit = isBuy ? slPrice - 0.1 : slPrice + 0.1;

        // Update summary
        summary.tpPrice = tpPrice;
        summary.tpLimit = tpLimit;
        summary.slPrice = slPrice;
        summary.slLimit = slLimit;

        // Create payload
        String payload = String.format("""
            {
              "product_symbol": "%s",
              "product_id": %d,
              "stop_loss_order": {
                "order_type": "limit_order",
                "stop_price": "%.4f",
                "limit_price": "%.4f"
              },
              "take_profit_order": {
                "order_type": "limit_order",
                "stop_price": "%.4f",
                "limit_price": "%.4f"
              },
              "bracket_stop_trigger_method": "last_traded_price"
            }
            """, symbol, productId, slPrice, slLimit, tpPrice, tpLimit);

        String prehash = "POST" + timestamp + path + payload;
        String signature = generateHmacSignature(prehash, API_SECRET);

        System.out.println("\n🎯 Placing Bracket Order (" + (isBuy ? "BUY" : "SELL") + ") for " + symbol);
        String response = sendRequest("POST", path, payload, timestamp, signature);
        printPrettyJson(response);
    }

    private static void placeMarketOrder(String symbol, boolean isBuy) throws Exception {
        long timestamp = System.currentTimeMillis() / 1000;
        String path = "/v2/orders";

        String payload = String.format("""
            {
              "product_symbol": "%s",
              "order_type": "market_order",
              "size": 10,
              "side": "%s",
              "reduce_only": false,
              "client_order_id": "order_%s_%d"
            }
            """, symbol, isBuy ? "buy" : "sell", symbol, System.currentTimeMillis());

        String prehash = "POST" + timestamp + path + payload;
        String signature = generateHmacSignature(prehash, API_SECRET);

        System.out.println("\n📤 Placing Market Order (" + (isBuy ? "BUY" : "SELL") + ") for: " + symbol);
        String response = sendRequest("POST", path, payload, timestamp, signature);
        printPrettyJson(response);
    }

    private static boolean hasOpenPosition(int productId) throws Exception {
        long timestamp = System.currentTimeMillis() / 1000;
        String path = "/v2/positions/margined?product_ids=" + productId;
        String signature = generateHmacSignature("GET" + timestamp + path, API_SECRET);

        String response = sendRequest("GET", path, null, timestamp, signature);
        JSONArray positions = new JSONObject(response).optJSONArray("result");

        if (positions != null && positions.length() > 0) {
            JSONObject position = positions.getJSONObject(0);
            return position.optDouble("size", 0.0) != 0.0;
        }
        return false;
    }

    private static void changeLeverageToFour(int productId) throws Exception {
        String path = "/v2/products/" + productId + "/orders/leverage";
        long timestamp = System.currentTimeMillis() / 1000;
        String payload = "{\"leverage\":4}";


        String prehash = "POST" + timestamp + path + payload;
        String signature = generateHmacSignature(prehash, API_SECRET);

        System.out.println("\n⚙️ Changing leverage to 5 for product ID: " + productId);
        String response = sendRequest("POST", path, payload, timestamp, signature);
        printPrettyJson(response);
    }

    private static TradeSummary fetchTradeDetails(int productId, String symbol,
                                                  double contractValue) throws Exception {
        long timestamp = System.currentTimeMillis() / 1000;
        String path = "/v2/positions/margined?product_ids=" + productId;
        String signature = generateHmacSignature("GET" + timestamp + path, API_SECRET);

        String response = sendRequest("GET", path, null, timestamp, signature);
        JSONArray positions = new JSONObject(response).optJSONArray("result");

        if (positions == null || positions.length() == 0) {
            System.out.println("⚠️ No open positions found for " + symbol);
            return null;
        }

        JSONObject pos = positions.getJSONObject(0);
        double contracts = pos.getDouble("size");
        double entryPrice = pos.getDouble("entry_price");
        double margin = pos.getDouble("margin");

        JSONObject leverageData = getLeverageForProduct(String.valueOf(productId));
        int leverage = (leverageData != null) ? leverageData.optInt("leverage", 1) : 1;

        double notional = contracts * contractValue * leverage;
        double coinQty = notional / entryPrice;

        TradeSummary summary = new TradeSummary();
        summary.symbol = symbol;
        summary.contracts = contracts;
        summary.entryPrice = entryPrice;
        summary.margin = margin;
        summary.coinQty = coinQty;
        summary.leverage = leverage;

        return summary;
    }

    private static JSONObject getProductInfoForSymbol(String symbol) throws Exception {
        long timestamp = System.currentTimeMillis() / 1000;
        String path = "/v2/products";
        String signature = generateHmacSignature("GET" + timestamp + path, API_SECRET);

        String response = sendRequest("GET", path, null, timestamp, signature);
        JSONArray products = new JSONObject(response).getJSONArray("result");

        for (int i = 0; i < products.length(); i++) {
            JSONObject product = products.getJSONObject(i);
            if (symbol.equalsIgnoreCase(product.getString("symbol"))) {
                return product;
            }
        }
        return null;
    }

    private static JSONObject getLeverageForProduct(String productId) throws Exception {
        long timestamp = System.currentTimeMillis() / 1000;
        String path = "/v2/products/" + productId + "/orders/leverage";
        String signature = generateHmacSignature("GET" + timestamp + path, API_SECRET);

        String response = sendRequest("GET", path, null, timestamp, signature);
        return new JSONObject(response).optJSONObject("result");
    }

    private static String sendRequest(String method, String path, String payload,
                                      long timestamp, String signature) throws Exception {
        URL url = new URL(BASE_URL + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();

        conn.setRequestMethod(method);
        conn.setRequestProperty("Content-Type", "application/json");

        if (signature != null) {
            conn.setRequestProperty("api-key", API_KEY);
            conn.setRequestProperty("timestamp", String.valueOf(timestamp));
            conn.setRequestProperty("signature", signature);
        }

        conn.setDoOutput(true);

        if (payload != null && !payload.isEmpty()) {
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.getBytes(StandardCharsets.UTF_8));
            }
        }

        BufferedReader br = new BufferedReader(new InputStreamReader(
                conn.getResponseCode() >= 400 ? conn.getErrorStream() : conn.getInputStream(),
                StandardCharsets.UTF_8));

        StringBuilder responseBuilder = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) {
            responseBuilder.append(line.trim());
        }

        return responseBuilder.toString();
    }

    private static void printPrettyJson(String rawJson) {
        try {
            JSONObject json = new JSONObject(rawJson);
            System.out.println(json.toString(2));
        } catch (Exception e) {
            System.out.println("Raw response: " + rawJson);
        }
    }

    private static String generateHmacSignature(String message, String secret) throws Exception {
        Mac sha256_HMAC = Mac.getInstance("HmacSHA256");
        SecretKeySpec secret_key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        sha256_HMAC.init(secret_key);

        byte[] hash = sha256_HMAC.doFinal(message.getBytes(StandardCharsets.UTF_8));
        StringBuilder hexString = new StringBuilder();

        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }

        return hexString.toString();
    }
}