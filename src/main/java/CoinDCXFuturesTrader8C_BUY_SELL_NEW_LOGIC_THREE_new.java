import org.json.JSONArray;
import org.json.JSONObject;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class CoinDCXFuturesTrader8C_BUY_SELL_NEW_LOGIC_THREE_new {

    // private static final String API_KEY = "72707da4dc9c55175867e72d425dbc210c0da5ae3765e381";
    // private static final String API_SECRET = "72973c321fe3ee434029f8cde165212dee99cdb9b7d99adf1cc02fe539e5be14";


   private static final String API_KEY = System.getenv("DELTA_API_KEY");
   private static final String API_SECRET = System.getenv("DELTA_API_SECRET");

    private static final String BASE_URL = "https://api.coindcx.com";
    private static final String PUBLIC_API_URL = "https://public.coindcx.com";

    // Trading Parameters
    private static final double MAX_MARGIN = 800.0;
    private static final int FIXED_LEVERAGE = 4;
    private static final int MAX_ORDER_STATUS_CHECKS = 10;
    private static final int ORDER_CHECK_DELAY_MS = 1000;
    private static final long TICK_SIZE_CACHE_TTL_MS = 3600000; // 1 hour cache
    private static final int LOOKBACK_PERIOD = 24; // Hours for trend analysis
    private static final double TREND_THRESHOLD = 0.02; // 2% change threshold for trend
    private static final double TP_PERCENTAGE = 0.04; // 4% take profit
    private static final double SL_PERCENTAGE = 0.06; // 6% stop loss
    private static final double LIMIT_ADJUSTMENT = 0.1; // Limit price adjustment

    // Instrument Cache
    private static final Map<String, JSONObject> instrumentDetailsCache = new ConcurrentHashMap<>();
    private static long lastInstrumentUpdateTime = 0;

    // Trading Pairs
    private static final String[] COIN_SYMBOLS = {
            "1000SATS", "1000X", "ACT", "ADA", "AIXBT", "AI16Z", "ALGO", "ALT", "API3",
            "ARB", "ARC", "AVAAI", "BAKE", "BB", "BIO", "BLUR", "BMT", "BONK", "COOKIE",
            "DOGE", "DOGS", "DYDX", "EIGEN", "ENA", "EOS", "ETHFI", "FARTCOIN", "FLOKI",
            "GALA", "GLM", "GOAT", "GRIFFAIN", "HBAR", "HIVE", "IO", "IOTA", "JASMY",
            "JUP", "KAITO", "LDO", "LISTA", "MANA", "MANTA", "MEME", "MELANIA", "MOODENG",
            "MOVE", "MUBARAK", "NEIRO", "NOT", "ONDO", "OP", "PEOPLE", "PEPE", "PENGU",
            "PI", "PNUT", "POL", "POPCAT", "RARE", "RED", "RSR", "SAGA", "SAND", "SEI",
            "SHIB", "SOLV", "SONIC", "SPX", "STX", "SUN", "SWARMS", "SUSHI", "TST", "TRX",
            "USUAL", "VINE", "VIRTUAL", "WIF", "WLD", "XAI", "XLM", "XRP", "ZK"
    };

    private static final Set<String> INTEGER_QUANTITY_PAIRS = Stream.of(COIN_SYMBOLS)
            .flatMap(symbol -> Stream.of("B-" + symbol + "_USDT", symbol + "_USDT"))
            .collect(Collectors.toCollection(HashSet::new));

    private static final String[] COINS_TO_TRADE = Stream.of(COIN_SYMBOLS)
            .map(symbol -> "B-" + symbol + "_USDT")
            .toArray(String[]::new);

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

                String side = determinePositionSide(pair);
                if (side == null) continue;

                double currentPrice = getLastPrice(pair);
                System.out.println("\nCurrent price for " + pair + ": " + currentPrice + " USDT");

                if (currentPrice <= 0) {
                    System.out.println("❌ Invalid price received, aborting for this pair");
                    continue;
                }

                double quantity = calculateQuantity(currentPrice, FIXED_LEVERAGE, pair);
                System.out.println("Calculated quantity: " + quantity);

                if (quantity <= 0) {
                    System.out.println("❌ Invalid quantity calculated, aborting for this pair");
                    continue;
                }

                JSONObject orderResponse = placeFuturesMarketOrder(side, pair, quantity, FIXED_LEVERAGE,
                        "email_notification", "isolated", "INR");

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

                double margin = MAX_MARGIN / FIXED_LEVERAGE;
                double[] tpSlPrices = calculateTpSlPrices(side, entryPrice, quantity, margin);

                String positionId = getPositionId(pair);
                if (positionId != null) {
                    setTakeProfitAndStopLoss(positionId, tpSlPrices[0], tpSlPrices[1],
                            tpSlPrices[2], tpSlPrices[3], side, pair);
                } else {
                    System.out.println("❌ Could not get position ID for TP/SL");
                }

            } catch (Exception e) {
                System.err.println("❌ Error processing pair " + pair + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    private static void checkPositionStatus(String positionId) throws Exception {
        JSONObject body = new JSONObject();
        body.put("timestamp", Instant.now().toEpochMilli());
        body.put("id", positionId);

        String response = sendAuthenticatedRequest(
                BASE_URL + "/exchange/v1/derivatives/futures/positions/details",
                body.toString(),
                generateHmacSHA256(API_SECRET, body.toString())
        );

        System.out.println("Position Status:\n" + response);
    }

    private static double[] calculateTpSlPrices(String side, double entryPrice, double quantity, double margin) {
        double tpPrice, slPrice;
        double tpLimit, slLimit;

        if ("buy".equalsIgnoreCase(side)) {
            // For LONG positions
            tpPrice = entryPrice * (1 + TP_PERCENTAGE); // TP = entry + 5%
            slPrice = entryPrice * (1 - SL_PERCENTAGE); // SL = entry - 7%
            tpLimit = tpPrice * 0.999; // Slightly below TP price for limit order
            slLimit = slPrice * 0.999; // Slightly below SL price for limit order
        } else {
            // For SHORT positions
            tpPrice = entryPrice * (1 - TP_PERCENTAGE); // TP = entry - 5%
            slPrice = entryPrice * (1 + SL_PERCENTAGE); // SL = entry + 7%
            tpLimit = tpPrice * 1.001; // Slightly above TP price for limit order
            slLimit = slPrice * 1.001; // Slightly above SL price for limit order
        }

        System.out.println("\n" + side.toUpperCase() + " Order - TP: " + tpPrice + " (Limit: " + tpLimit +
                ") | SL: " + slPrice + " (Limit: " + slLimit + ")");

        return new double[]{tpPrice, slPrice, tpLimit, slLimit};
    }


    private static void setTakeProfitAndStopLoss(String positionId, double takeProfitPrice, double stopLossPrice,
                                                 double takeProfitLimit, double stopLossLimit,
                                                 String side, String pair) {
        try {
            if (positionId == null || positionId.isEmpty()) {
                System.err.println("❌ Invalid position ID");
                return;
            }

            JSONObject payload = new JSONObject();
            payload.put("timestamp", Instant.now().toEpochMilli());
            payload.put("id", positionId);

            // Round prices to tick size
            double roundedTpPrice = roundToTickSize(takeProfitPrice, pair);
            double roundedSlPrice = roundToTickSize(stopLossPrice, pair);
            double roundedTpLimit = roundToTickSize(takeProfitLimit, pair);
            double roundedSlLimit = roundToTickSize(stopLossLimit, pair);

            System.out.println("\nTP/SL Details:");
            System.out.println("Original TP Price: " + takeProfitPrice + " → Rounded: " + roundedTpPrice);
            System.out.println("Original SL Price: " + stopLossPrice + " → Rounded: " + roundedSlPrice);
            System.out.println("Original TP Limit: " + takeProfitLimit + " → Rounded: " + roundedTpLimit);
            System.out.println("Original SL Limit: " + stopLossLimit + " → Rounded: " + roundedSlLimit);

            // Create TP order
            JSONObject takeProfit = new JSONObject();
            takeProfit.put("stop_price", roundedTpPrice);
            takeProfit.put("limit_price", roundedTpLimit);
            takeProfit.put("order_type", "take_profit_limit");

            // Create SL order
            JSONObject stopLoss = new JSONObject();
            stopLoss.put("stop_price", roundedSlPrice);
            stopLoss.put("limit_price", roundedSlLimit);
            stopLoss.put("order_type", "stop_limit");

            payload.put("take_profit", takeProfit);
            payload.put("stop_loss", stopLoss);

            String payloadString = payload.toString();
            System.out.println("\nRequest Payload:\n" + payloadString);

            // Send request to API
            String signature = generateHmacSHA256(API_SECRET, payloadString);
            String response = sendAuthenticatedRequest(
                    BASE_URL + "/exchange/v1/derivatives/futures/positions/create_tpsl",
                    payloadString,
                    signature
            );

            System.out.println("\nAPI Response:\n" + response);

            // Parse response
            JSONObject tpslResponse = new JSONObject(response);
            if (tpslResponse.has("err_code_dcx")) {
                System.err.println("❌ Failed to set TP/SL: " + tpslResponse);
                System.err.println("Error Code: " + tpslResponse.optString("err_code_dcx"));
                System.err.println("Error Message: " + tpslResponse.optString("message"));
            } else {
                System.out.println("✅ TP/SL set successfully!");
                System.out.println("TP Order ID: " + tpslResponse.optString("take_profit_order_id"));
                System.out.println("SL Order ID: " + tpslResponse.optString("stop_loss_order_id"));
            }
        } catch (Exception e) {
            System.err.println("❌ Error setting TP/SL: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static String determinePositionSide(String pair) {
        try {
            JSONArray candles = getCandlestickData(pair, "1h", LOOKBACK_PERIOD);
            if (candles == null || candles.length() < 2) {
                System.out.println("⚠️ Not enough data for trend analysis, using default strategy");
                return Math.random() > 0.5 ? "buy" : "sell";
            }

            double firstClose = candles.getJSONObject(0).getDouble("close");
            double lastClose = candles.getJSONObject(candles.length() - 1).getDouble("close");
            double priceChange = (lastClose - firstClose) / firstClose;

            System.out.println("Trend Analysis for " + pair + ":");
            System.out.println("First Close: " + firstClose);
            System.out.println("Last Close: " + lastClose);
            System.out.println("Price Change: " + (priceChange * 100) + "%");

            if (priceChange > TREND_THRESHOLD) {
                System.out.println("📈 Uptrend detected - Going LONG");
                return "buy";
            } else if (priceChange < -TREND_THRESHOLD) {
                System.out.println("📉 Downtrend detected - Going SHORT");
                return "sell";
            } else {
                System.out.println("➡️ Sideways market - Using RSI for decision");
                return determineSideWithRSI(candles);
            }
        } catch (Exception e) {
            System.err.println("❌ Error determining position side: " + e.getMessage());
            return Math.random() > 0.5 ? "buy" : "sell";
        }
    }

    private static String determineSideWithRSI(JSONArray candles) {
        try {
            double[] closes = new double[candles.length()];
            for (int i = 0; i < candles.length(); i++) {
                closes[i] = candles.getJSONObject(i).getDouble("close");
            }

            double avgGain = 0;
            double avgLoss = 0;
            int rsiPeriod = 14;

            for (int i = 1; i <= rsiPeriod; i++) {
                double change = closes[i] - closes[i-1];
                if (change > 0) {
                    avgGain += change;
                } else {
                    avgLoss += Math.abs(change);
                }
            }

            avgGain /= rsiPeriod;
            avgLoss /= rsiPeriod;

            for (int i = rsiPeriod + 1; i < closes.length; i++) {
                double change = closes[i] - closes[i-1];
                if (change > 0) {
                    avgGain = (avgGain * (rsiPeriod - 1) + change) / rsiPeriod;
                    avgLoss = (avgLoss * (rsiPeriod - 1)) / rsiPeriod;
                } else {
                    avgLoss = (avgLoss * (rsiPeriod - 1) + Math.abs(change)) / rsiPeriod;
                    avgGain = (avgGain * (rsiPeriod - 1)) / rsiPeriod;
                }
            }

            double rs = avgGain / avgLoss;
            double rsi = 100 - (100 / (1 + rs));

            System.out.println("RSI: " + rsi);

            if (rsi < 30) {
                System.out.println("🔽 Oversold - Going LONG");
                return "buy";
            } else if (rsi > 70) {
                System.out.println("🔼 Overbought - Going SHORT");
                return "sell";
            } else {
                System.out.println("⏸ Neutral RSI - No trade");
                return null;
            }
        } catch (Exception e) {
            System.err.println("❌ Error calculating RSI: " + e.getMessage());
            return Math.random() > 0.5 ? "buy" : "sell";
        }
    }

    private static void initializeInstrumentDetails() {
        try {
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastInstrumentUpdateTime > TICK_SIZE_CACHE_TTL_MS) {
                System.out.println("ℹ️ Fetching latest instrument details from API...");
                instrumentDetailsCache.clear();

                String activeInstrumentsResponse = sendPublicRequest(
                        BASE_URL + "/exchange/v1/derivatives/futures/data/active_instruments");

                JSONArray activeInstruments = new JSONArray(activeInstrumentsResponse);

                for (int i = 0; i < activeInstruments.length(); i++) {
                    String pair = activeInstruments.getString(i);
                    try {
                        String instrumentResponse = sendPublicRequest(
                                BASE_URL + "/exchange/v1/derivatives/futures/data/instrument?pair=" + pair);
                        JSONObject instrumentDetails = new JSONObject(instrumentResponse).getJSONObject("instrument");
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

    private static double roundToTickSize(double price, String pair) {
        double tickSize = getTickSizeForPair(pair);
        return Math.round(price / tickSize) * tickSize;
    }

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
            for (int i = 0; i < new JSONArray(response).length(); i++) {
                JSONObject position = new JSONArray(response).getJSONObject(i);
                if (position.getString("pair").equals(pair)) return position;
            }
        } else {
            JSONObject position = new JSONObject(response);
            if (position.getString("pair").equals(pair)) return position;
        }
        return null;
    }

    private static double calculateQuantity(double currentPrice, int leverage, String pair) {
        double quantity = MAX_MARGIN / (currentPrice * 93);
        return Math.max(INTEGER_QUANTITY_PAIRS.contains(pair) ?
                Math.floor(quantity) : Math.floor(quantity * 100) / 100, 0);
    }

    public static double getLastPrice(String pair) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(
                    PUBLIC_API_URL + "/market_data/trade_history?pair=" + pair + "&limit=1"
            ).openConnection();
            if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                String response = readAllLines(conn.getInputStream());
                return response.startsWith("[") ?
                        new JSONArray(response).getJSONObject(0).getDouble("p") :
                        new JSONObject(response).getDouble("p");
            }
        } catch (Exception e) {
            System.err.println("❌ Error getting last price: " + e.getMessage());
        }
        return 0;
    }

    public static JSONObject placeFuturesMarketOrder(String side, String pair, double totalQuantity, int leverage,
                                                     String notification, String positionMarginType, String marginCurrency) {
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
            return response.startsWith("[") ?
                    new JSONArray(response).getJSONObject(0) :
                    new JSONObject(response);
        } catch (Exception e) {
            System.err.println("❌ Error placing futures market order: " + e.getMessage());
            return null;
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

            JSONArray positions = response.startsWith("[") ?
                    new JSONArray(response) :
                    new JSONArray().put(new JSONObject(response));

            for (int i = 0; i < positions.length(); i++) {
                JSONObject position = positions.getJSONObject(i);
                String pair = position.optString("pair", "");

                boolean isActive = position.optDouble("active_pos", 0) > 0 ||
                        position.optDouble("locked_margin", 0) > 0 ||
                        position.optDouble("avg_price", 0) > 0 ||
                        position.optDouble("take_profit_trigger", 0) > 0 ||
                        position.optDouble("stop_loss_trigger", 0) > 0;

                if (isActive) {
                    activePairs.add(pair);
                }
            }
        } catch (Exception e) {
            System.err.println("❌ Error fetching active positions: " + e.getMessage());
        }
        return activePairs;
    }

    private static JSONArray getCandlestickData(String pair, String resolution, int periods) {
        try {
            long endTime = Instant.now().toEpochMilli();
            long startTime = endTime - TimeUnit.HOURS.toMillis(periods);

            String url = PUBLIC_API_URL + "/market_data/candlesticks?pair=" + pair +
                    "&from=" + startTime + "&to=" + endTime +
                    "&resolution=" + resolution + "&pcode=#";

            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");

            if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                String response = readAllLines(conn.getInputStream());
                JSONObject jsonResponse = new JSONObject(response);
                if (jsonResponse.getString("s").equals("ok")) {
                    return jsonResponse.getJSONArray("data");
                }
            }
        } catch (Exception e) {
            System.err.println("❌ Error fetching candlestick data: " + e.getMessage());
        }
        return null;
    }

    private static String sendPublicRequest(String endpoint) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setRequestMethod("GET");
        if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
            return readAllLines(conn.getInputStream());
        }
        throw new IOException("HTTP error code: " + conn.getResponseCode());
    }

    private static String sendAuthenticatedRequest(String endpoint, String jsonBody, String signature) throws IOException {
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
        return new BufferedReader(new InputStreamReader(is)).lines()
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
}
