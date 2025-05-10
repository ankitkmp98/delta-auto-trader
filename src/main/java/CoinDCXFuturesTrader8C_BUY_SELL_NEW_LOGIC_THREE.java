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

// public class CoinDCXFuturesTraderImprovedSL_FOUR {
//     // private static final String API_KEY = "72707da4dc9c55175867e72d425dbc210c0da5ae3765e381";
//     // private static final String API_SECRET = "72973c321fe3ee434029f8cde165212dee99cdb9b7d99adf1cc02fe539e5be14";

//    private static final String API_KEY = System.getenv("DELTA_API_KEY");
//    private static final String API_SECRET = System.getenv("DELTA_API_SECRET");


//     private static final String BASE_URL = "https://api.coindcx.com";
//     private static final String PUBLIC_API_URL = "https://public.coindcx.com";
//     private static final double MAX_MARGIN = 600.0;
//     private static final int MAX_ORDER_STATUS_CHECKS = 10;
//     private static final int ORDER_CHECK_DELAY_MS = 1000;
//     private static final long TICK_SIZE_CACHE_TTL_MS = 3600000;
//     private static final int LOOKBACK_PERIOD = 24;
//     private static final double TREND_THRESHOLD = 0.02;
//     private static final double MIN_SL_PERCENTAGE = 1.5;
//     private static final double TP_MULTIPLIER = 1.8;
//     private static final Random RANDOM = new Random();

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
//             "USUAL", "VINE", "VIRTUAL", "WIF", "WLD", "XAI", "XLM", "XRP", "ZK"
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
//                 if (side == null) {
//                     System.out.println("\n⏩ ⚠️ No clear signal for " + pair + " - Skipping");
//                     continue;
//                 }

//                 double currentPrice = getLastPrice(pair);
//                 System.out.println("\nCurrent price for " + pair + ": " + currentPrice + " USDT");

//                 if (currentPrice <= 0) {
//                     System.out.println("❌ Invalid price received, aborting for this pair");
//                     continue;
//                 }

//                 int leverage = determineDynamicLeverage(pair, side);
//                 System.out.println("Using leverage: " + leverage + "x");

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
//                 double[] tpSlPrices = calculateSmartTpSlPrices(side, entryPrice, quantity, margin, pair);

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
//                 e.printStackTrace();
//             }
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

//     private static int determineDynamicLeverage(String pair, String side) {
//         try {
//             JSONArray candles = getCandlestickData(pair, "4h", 10);
//             if (candles == null || candles.length() < 5) return 10;

//             double totalRange = 0;
//             for (int i = 0; i < 5; i++) {
//                 JSONObject candle = candles.getJSONObject(i);
//                 double range = candle.getDouble("high") - candle.getDouble("low");
//                 totalRange += range / candle.getDouble("open");
//             }
//             double avgVolatility = totalRange / 5;

//             if (avgVolatility > 0.08) return 5;
//             if (avgVolatility > 0.05) return 8;

//             double trendStrength = calculateTrendStrength(candles, side);
//             if (trendStrength > 0.03) return 15;
//             if (trendStrength > 0.02) return 12;

//             return 10;
//         } catch (Exception e) {
//             return 10;
//         }
//     }

//     private static double calculateTrendStrength(JSONArray candles, String side) {
//         double firstClose = candles.getJSONObject(0).getDouble("close");
//         double lastClose = candles.getJSONObject(candles.length() - 1).getDouble("close");
//         double priceChange = (lastClose - firstClose) / firstClose;

//         if ("buy".equals(side) && priceChange > 0) {
//             return priceChange;
//         } else if ("sell".equals(side) && priceChange < 0) {
//             return Math.abs(priceChange);
//         }
//         return 0;
//     }

//     private static double[] calculateSmartTpSlPrices(String side, double entryPrice, double quantity,
//                                                      double margin, String pair) {
//         double conversionRate = 93.0;
//         double leverage = MAX_MARGIN / margin;
//         double baseSlPercentage = 0.08 / (leverage / 10);
//         double volatilityFactor = getVolatilityFactor(pair);
//         baseSlPercentage *= volatilityFactor;
//         double randomFactor = 1 + (RANDOM.nextDouble() * 0.1 - 0.05);
//         baseSlPercentage *= randomFactor;
//         double minSlPriceDiff = entryPrice * (MIN_SL_PERCENTAGE / 100);
//         double calculatedSlDiff = (margin * baseSlPercentage) / (quantity * conversionRate);
//         double slPriceDiff = Math.max(calculatedSlDiff, minSlPriceDiff);
//         double tpPriceDiff = slPriceDiff * TP_MULTIPLIER;
//         double tickSize = getTickSizeForPair(pair);
//         slPriceDiff = Math.round(slPriceDiff / tickSize) * tickSize;
//         tpPriceDiff = Math.round(tpPriceDiff / tickSize) * tickSize;

//         double tpPrice, slPrice;

//         if ("buy".equalsIgnoreCase(side)) {
//             tpPrice = entryPrice + tpPriceDiff;
//             slPrice = entryPrice - slPriceDiff;
//             System.out.printf("\nBUY Order - TP (+%.2f%%): %.6f | SL (-%.2f%%): %.6f%n",
//                     (tpPriceDiff/entryPrice*100), tpPrice, (slPriceDiff/entryPrice*100), slPrice);
//         } else {
//             tpPrice = entryPrice - tpPriceDiff;
//             slPrice = entryPrice + slPriceDiff;
//             System.out.printf("\nSELL Order - TP (-%.2f%%): %.6f | SL (+%.2f%%): %.6f%n",
//                     (tpPriceDiff/entryPrice*100), tpPrice, (slPriceDiff/entryPrice*100), slPrice);
//         }

//         return new double[]{tpPrice, slPrice};
//     }

//     private static double getVolatilityFactor(String pair) {
//         try {
//             JSONArray candles = getCandlestickData(pair, "4h", 10);
//             if (candles == null || candles.length() < 5) return 1.5;

//             double totalRange = 0;
//             for (int i = 0; i < 5; i++) {
//                 JSONObject candle = candles.getJSONObject(i);
//                 double range = candle.getDouble("high") - candle.getDouble("low");
//                 totalRange += range / candle.getDouble("open");
//             }
//             double avgVolatility = totalRange / 5;

//             if (avgVolatility > 0.08) return 2.0;
//             if (avgVolatility > 0.05) return 1.7;
//             if (avgVolatility > 0.03) return 1.3;
//             return 1.0;
//         } catch (Exception e) {
//             return 1.5;
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
//             takeProfit.put("limit_price", Math.round(
//                     (side.equalsIgnoreCase("buy") ? roundedTpPrice * 0.99 : roundedTpPrice * 1.01) / tickSize) * tickSize);
//             takeProfit.put("order_type", "take_profit_market");

//             JSONObject stopLoss = new JSONObject();
//             stopLoss.put("stop_price", roundedSlPrice);
//             stopLoss.put("limit_price", Math.round(
//                     (side.equalsIgnoreCase("buy") ? roundedSlPrice * 1.01 : roundedSlPrice * 0.99) / tickSize) * tickSize);
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

//     private static String sendPublicRequest(String endpoint) throws IOException {
//         HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
//         conn.setRequestMethod("GET");
//         if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
//             return readAllLines(conn.getInputStream());
//         }
//         throw new IOException("HTTP error code: " + conn.getResponseCode());
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
// }


























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

public class CoinDCXFuturesTrader8C_BUY_SELL_NEW_LOGIC_THREE {
    // private static final String API_KEY = "72707da4dc9c55175867e72d425dbc210c0da5ae3765e381";
    // private static final String API_SECRET = "72973c321fe3ee434029f8cde165212dee99cdb9b7d99adf1cc02fe539e5be14";


       private static final String API_KEY = System.getenv("DELTA_API_KEY");
   private static final String API_SECRET = System.getenv("DELTA_API_SECRET");
    private static final String BASE_URL = "https://api.coindcx.com";
    private static final String PUBLIC_API_URL = "https://public.coindcx.com";
    private static final double MAX_MARGIN = 600.0;
    private static final int MAX_ORDER_STATUS_CHECKS = 10;
    private static final int ORDER_CHECK_DELAY_MS = 1000;
    private static final long TICK_SIZE_CACHE_TTL_MS = 3600000; // 1 hour cache
    private static final int LOOKBACK_PERIOD = 24; // Hours for trend analysis
    private static final double TREND_THRESHOLD = 0.02; // 2% change threshold for trend

    // Cache for instrument details with timestamp
    private static final Map<String, JSONObject> instrumentDetailsCache = new ConcurrentHashMap<>();
    private static long lastInstrumentUpdateTime = 0;

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
        // Initialize instrument details before starting trading
        initializeInstrumentDetails();

        // Get all active positions once at the start
        Set<String> activePairs = getActivePositions();
        System.out.println("\nActive Positions: " + activePairs);

        for (String pair : COINS_TO_TRADE) {
            try {
                // Check if we already have an active position for this pair
                if (activePairs.contains(pair)) {
                    System.out.println("\n⏩ ⚠️ Skipping " + pair + " - Active position exists");
                    continue; // Skip to next pair
                }

                // Determine market trend and decide position side
                String side = determinePositionSide(pair);
                int leverage = 15;

                double currentPrice = getLastPrice(pair);
                System.out.println("\nCurrent price for " + pair + ": " + currentPrice + " USDT");

                if (currentPrice <= 0) {
                    System.out.println("❌ Invalid price received, aborting for this pair");
                    continue;
                }

                double quantity = calculateQuantity(currentPrice, leverage, pair);
                System.out.println("Calculated quantity: " + quantity);

                if (quantity <= 0) {
                    System.out.println("❌ Invalid quantity calculated, aborting for this pair");
                    continue;
                }

                JSONObject orderResponse = placeFuturesMarketOrder(side, pair, quantity, leverage,
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

                // Calculate TP/SL prices based on side
                double margin = MAX_MARGIN / leverage;
                double[] tpSlPrices = calculateTpSlPrices(side, entryPrice, quantity, margin);

                String positionId = getPositionId(pair);
                if (positionId != null) {
                    setTakeProfitAndStopLoss(positionId,
                            tpSlPrices[0],
                            tpSlPrices[1],
                            side, pair);
                } else {
                    System.out.println("❌ Could not get position ID for TP/SL");
                }

            } catch (Exception e) {
                System.err.println("❌ Error processing pair " + pair + ": " + e.getMessage());
            }
        }
    }

    // New method to determine position side based on market trend
    private static String determinePositionSide(String pair) {
        try {
            // Get candlestick data for trend analysis
            JSONArray candles = getCandlestickData(pair, "1h", LOOKBACK_PERIOD);

            if (candles == null || candles.length() < 2) {
                System.out.println("⚠️ Not enough data for trend analysis, using default strategy");
                return Math.random() > 0.5 ? "buy" : "sell";
            }

            // Calculate price change over the lookback period
            double firstClose = candles.getJSONObject(0).getDouble("close");
            double lastClose = candles.getJSONObject(candles.length() - 1).getDouble("close");
            double priceChange = (lastClose - firstClose) / firstClose;

            System.out.println("Trend Analysis for " + pair + ":");
            System.out.println("First Close: " + firstClose);
            System.out.println("Last Close: " + lastClose);
            System.out.println("Price Change: " + (priceChange * 100) + "%");

            // Determine trend
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
            return Math.random() > 0.5 ? "buy" : "sell"; // Fallback to random if analysis fails
        }
    }

    // New method to get candlestick data for trend analysis
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

    // New method to determine side using RSI when market is sideways
    private static String determineSideWithRSI(JSONArray candles) {
        try {
            // Calculate RSI (simplified version)
            double[] closes = new double[candles.length()];
            for (int i = 0; i < candles.length(); i++) {
                closes[i] = candles.getJSONObject(i).getDouble("close");
            }

            double avgGain = 0;
            double avgLoss = 0;
            int rsiPeriod = 14;

            // Initial average gain/loss calculation
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

            // Subsequent smoothing
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

            // Trading decision based on RSI
            if (rsi < 30) {
                System.out.println("🔽 Oversold - Going LONG");
                return "buy";
            } else if (rsi > 70) {
                System.out.println("🔼 Overbought - Going SHORT");
                return "sell";
            } else {
                System.out.println("⏸ Neutral RSI - No trade");
                return null; // Indicates no trade should be placed
            }
        } catch (Exception e) {
            System.err.println("❌ Error calculating RSI: " + e.getMessage());
            return Math.random() > 0.5 ? "buy" : "sell";
        }
    }







        private static void initializeInstrumentDetails() {
            try {
                // Check if cache needs refresh
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastInstrumentUpdateTime > TICK_SIZE_CACHE_TTL_MS) {
                    System.out.println("ℹ️ Fetching latest instrument details from API...");

                    // Clear old cache
                    instrumentDetailsCache.clear();

                    // Fetch details for all active instruments
                    String activeInstrumentsResponse = sendPublicRequest(
                            BASE_URL + "/exchange/v1/derivatives/futures/data/active_instruments");

                    JSONArray activeInstruments = new JSONArray(activeInstrumentsResponse);

                    // Fetch details for each instrument
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

        private static String sendPublicRequest(String endpoint) throws IOException {
            HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
            conn.setRequestMethod("GET");
            if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                return readAllLines(conn.getInputStream());
            }
            throw new IOException("HTTP error code: " + conn.getResponseCode());
        }

        private static double getTickSizeForPair(String pair) {
            // Check if we need to refresh cache
            if (System.currentTimeMillis() - lastInstrumentUpdateTime > TICK_SIZE_CACHE_TTL_MS) {
                initializeInstrumentDetails();
            }

            JSONObject instrumentDetails = instrumentDetailsCache.get(pair);
            if (instrumentDetails != null) {
                return instrumentDetails.optDouble("price_increment", 0.0001); // Default to 0.0001 if not specified
            }
            return 0.0001; // Fallback default
        }

        private static double roundToTickSize(double price, String pair) {
            double tickSize = getTickSizeForPair(pair);
            return Math.round(price / tickSize) * tickSize;
        }

        private static boolean validatePrice(double price, String pair) {
            double tickSize = getTickSizeForPair(pair);
            return Math.abs(price - (Math.round(price / tickSize) * tickSize)) < 1e-10;
        }

        private static double[] calculateTpSlPrices(String side, double entryPrice, double quantity, double margin) {
            double conversionRate = 93.0; // USDT to INR conversion rate

            double tpPercentage = 0.15; // 50% of margin for TP
            double slPercentage = 0.21; // 8% of margin for SL


            double tpPriceDiff = (margin * tpPercentage) / (quantity * conversionRate);
            double slPriceDiff = (margin * slPercentage) / (quantity * conversionRate);

            double tpPrice, slPrice;

            if ("buy".equalsIgnoreCase(side)) {
                tpPrice = entryPrice + tpPriceDiff;
                slPrice = entryPrice - slPriceDiff;
                System.out.println("\nBUY Order - TP (+15%): " + tpPrice + " | SL (-20%): " + slPrice);
            } else {
                tpPrice = entryPrice - tpPriceDiff;
                slPrice = entryPrice + slPriceDiff;
                System.out.println("\nSELL Order - TP (-15%): " + tpPrice + " | SL (+20%): " + slPrice);
            }

            return new double[]{tpPrice, slPrice};
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

        public static void setTakeProfitAndStopLoss(String positionId, double takeProfitPrice, double stopLossPrice,
                                                    String side, String pair) {
            try {
                JSONObject payload = new JSONObject();
                payload.put("timestamp", Instant.now().toEpochMilli());
                payload.put("id", positionId);

                // Get tick size for the pair
                double tickSize = getTickSizeForPair(pair);

                // Round TP/SL prices to correct tick size
                double roundedTpPrice = Math.round(takeProfitPrice / tickSize) * tickSize;
                double roundedSlPrice = Math.round(stopLossPrice / tickSize) * tickSize;

                JSONObject takeProfit = new JSONObject();
                takeProfit.put("stop_price", roundedTpPrice);
                takeProfit.put("limit_price", Math.round(
                        (side.equalsIgnoreCase("buy") ? roundedTpPrice * 0.99 : roundedTpPrice * 1.01) / tickSize) * tickSize);
                takeProfit.put("order_type", "take_profit_market");

                JSONObject stopLoss = new JSONObject();
                stopLoss.put("stop_price", roundedSlPrice);
                stopLoss.put("limit_price", Math.round(
                        (side.equalsIgnoreCase("buy") ? roundedSlPrice * 1.01 : roundedSlPrice * 0.99) / tickSize) * tickSize);
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

        private static Set<String> getActivePositions() {
            Set<String> activePairs = new HashSet<>();
            try {
                JSONObject body = new JSONObject();
                body.put("timestamp", Instant.now().toEpochMilli());
                body.put("page", "1");
                body.put("size", "100");  // Increased to 100 to catch all positions
                body.put("margin_currency_short_name", new String[]{"INR", "USDT"});

                String response = sendAuthenticatedRequest(
                        BASE_URL + "/exchange/v1/derivatives/futures/positions",
                        body.toString(),
                        generateHmacSHA256(API_SECRET, body.toString())
                );

                JSONArray positions = response.startsWith("[") ?
                        new JSONArray(response) :
                        new JSONArray().put(new JSONObject(response));

                System.out.println("\n=== Raw Position Data ===");
                System.out.println("Total positions received: " + positions.length());

                for (int i = 0; i < positions.length(); i++) {
                    JSONObject position = positions.getJSONObject(i);
                    String pair = position.optString("pair", "");

                    // New comprehensive position detection logic
                    boolean isActive = position.optDouble("active_pos", 0) > 0 ||
                            position.optDouble("locked_margin", 0) > 0 ||
                            position.optDouble("avg_price", 0) > 0 ||
                            position.optDouble("take_profit_trigger", 0) > 0 ||
                            position.optDouble("stop_loss_trigger", 0) > 0;

                    if (isActive) {
                        System.out.printf("Active Position: %s | ActivePos: %.2f | Margin: %.4f | Entry: %.6f | TP: %.4f | SL: %.4f%n",
                                pair,
                                position.optDouble("active_pos", 0),
                                position.optDouble("locked_margin", 0),
                                position.optDouble("avg_price", 0),
                                position.optDouble("take_profit_trigger", 0),
                                position.optDouble("stop_loss_trigger", 0));
                        activePairs.add(pair);
                    }
                }

                System.out.println("\n=== Final Active Positions ===");
                if (activePairs.isEmpty()) {
                    System.out.println("No active positions found.");
                } else {
                    activePairs.forEach(pair -> System.out.println("- " + pair));
                    System.out.println("Total active positions detected: " + activePairs.size());
                }
            } catch (Exception e) {
                System.err.println("❌ Error fetching active positions: " + e.getMessage());
                e.printStackTrace();
            }
            return activePairs;
        }

    private static int determineLeverage(String side, double priceChange) {
        // For strong trends
        if (Math.abs(priceChange) > TREND_THRESHOLD) {
            return 10;
        }
        // For RSI-based trades or when analysis fails
        return 5;
    }

    }








