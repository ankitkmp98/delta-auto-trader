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

    // API Configuration
    private static final String API_KEY = System.getenv("DELTA_API_KEY");
    private static final String API_SECRET = System.getenv("DELTA_API_SECRET");
    private static final String BASE_URL = "https://api.coindcx.com";
    private static final String PUBLIC_API_URL = "https://public.coindcx.com";
    
    // Risk Management Configuration
    private static final double ACCOUNT_BALANCE = 10000.0; // Your account balance
    private static final double RISK_PER_TRADE = 0.02; // 2% risk per trade
    private static final double MAX_PORTFOLIO_RISK = 0.20; // 20% max portfolio risk
    private static final int MAX_CONCURRENT_TRADES = 10;
    private static final double MIN_VOLUME_24H = 1000000.0; // $1M minimum 24h volume
    
    // Strategy Configuration
    private static final int LOOKBACK_CANDLES = 15;
    private static final int CANDLE_RES_MINUTES = 15;
    private static final double TREND_THRESHOLD = 0.01;
    private static final double TP_PERCENTAGE = 0.05; // 5% take profit
    private static final double SL_PERCENTAGE = 0.03; // 3% stop loss
    private static final int DEFAULT_LEVERAGE = 5;
    private static final int MAX_LEVERAGE = 8;
    
    // Technical Indicators Configuration
    private static final int RSI_PERIOD = 14;
    private static final int RSI_OVERBOUGHT = 70;
    private static final int RSI_OVERSOLD = 30;
    private static final double MIN_VOLUME_RATIO = 1.2; // Volume must be 20% above average
    
    // API Cache Configuration
    private static final long TICK_SIZE_CACHE_TTL_MS = 3600000;
    private static final int MAX_ORDER_STATUS_CHECKS = 10;
    private static final int ORDER_CHECK_DELAY_MS = 1000;
    
    // Caches
    private static final Map<String, JSONObject> instrumentDetailsCache = new ConcurrentHashMap<>();
    private static final Map<String, Double> volumeCache = new ConcurrentHashMap<>();
    private static long lastInstrumentUpdateTime = 0;
    private static long lastVolumeUpdateTime = 0;
    
    // Trade tracking
    private static final Set<String> activePairs = Collections.synchronizedSet(new HashSet<>());
    private static final List<TradeRecord> tradeHistory = Collections.synchronizedList(new ArrayList<>());
    
    // Trading pairs (reduced to top coins)
    private static final String[] TOP_COINS = {
        "BTC", "ETH", "SOL", "XRP", "BNB", "ADA", "DOGE", "AVAX", "DOT", "LINK",
        "TRX", "MATIC", "SHIB", "UNI", "ATOM", "ETC", "XLM", "FIL", "ALGO", "AAVE"
    };
    
    private static final String[] COINS_TO_TRADE = Stream.of(TOP_COINS)
            .map(symbol -> "B-" + symbol + "_USDT")
            .toArray(String[]::new);
    
    private static final Set<String> INTEGER_QUANTITY_PAIRS = Stream.of(TOP_COINS)
            .flatMap(symbol -> Stream.of("B-" + symbol + "_USDT", symbol + "_USDT"))
            .collect(Collectors.toCollection(HashSet::new));

    public static void main(String[] args) {
        System.out.println("🚀 Enhanced Futures Trading Bot Started");
        System.out.println("========================================");
        System.out.println("Account Balance: $" + ACCOUNT_BALANCE);
        System.out.println("Risk per trade: " + (RISK_PER_TRADE * 100) + "%");
        System.out.println("Max concurrent trades: " + MAX_CONCURRENT_TRADES);
        System.out.println("Default leverage: " + DEFAULT_LEVERAGE + "x");
        System.out.println("========================================");
        
        try {
            // Initialize caches
            initializeInstrumentDetails();
            updateVolumeCache();
            
            // Get current active positions
            Set<String> currentActivePairs = getActivePositions();
            activePairs.addAll(currentActivePairs);
            
            System.out.println("\n📊 Current Active Positions: " + activePairs.size());
            activePairs.forEach(pair -> System.out.println("  - " + pair));
            
            // Filter coins with sufficient volume
            List<String> tradablePairs = filterTradablePairs();
            System.out.println("\n🎯 Tradable Pairs: " + tradablePairs.size() + "/" + COINS_TO_TRADE.length);
            
            if (tradablePairs.isEmpty()) {
                System.out.println("❌ No tradable pairs available");
                return;
            }
            
            // Trade execution
            int tradesPlaced = 0;
            for (String pair : tradablePairs) {
                try {
                    // Check limits
                    if (activePairs.size() >= MAX_CONCURRENT_TRADES) {
                        System.out.println("\n⚠️ Max concurrent trades reached (" + MAX_CONCURRENT_TRADES + ")");
                        break;
                    }
                    
                    if (activePairs.contains(pair)) {
                        System.out.println("\n⏭ Skipping " + pair + " - Active position exists");
                        continue;
                    }
                    
                    System.out.println("\n" + "=".repeat(60));
                    System.out.println("📈 Analyzing: " + pair);
                    System.out.println("=".repeat(60));
                    
                    // Analysis
                    TradeSignal signal = analyzePair(pair);
                    if (signal == null || signal.side == null) {
                        System.out.println("⏭ No clear trading signal");
                        continue;
                    }
                    
                    // Calculate position size
                    PositionDetails position = calculatePosition(pair, signal);
                    if (position.quantity <= 0) {
                        System.out.println("❌ Invalid position size");
                        continue;
                    }
                    
                    // Display trade summary
                    displayTradeSummary(pair, signal, position);
                    
                    // Execute trade
                    boolean success = executeTrade(pair, signal, position);
                    
                    if (success) {
                        tradesPlaced++;
                        activePairs.add(pair);
                        
                        // Small delay between trades
                        if (tradesPlaced < MAX_CONCURRENT_TRADES) {
                            TimeUnit.SECONDS.sleep(2);
                        }
                    }
                    
                } catch (Exception e) {
                    System.err.println("❌ Error processing " + pair + ": " + e.getMessage());
                }
            }
            
            System.out.println("\n" + "=".repeat(60));
            System.out.println("✅ Trading Session Complete");
            System.out.println("Trades placed this session: " + tradesPlaced);
            System.out.println("Total active positions: " + activePairs.size());
            System.out.println("Portfolio exposure: $" + calculatePortfolioExposure());
            
        } catch (Exception e) {
            System.err.println("❌ Fatal error: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    // ==================== TRADE ANALYSIS & SIGNAL GENERATION ====================
    
    private static TradeSignal analyzePair(String pair) {
        try {
            String spotPair = getSpotPair(pair);
            
            // Get multi-timeframe data
            JSONArray dailyCandles = getCandlestickData(spotPair, "1d", 5);
            JSONArray hourlyCandles = getCandlestickData(spotPair, "1h", 24);
            JSONArray entryCandles = getCandlestickData(spotPair, "15m", LOOKBACK_CANDLES);
            
            if (dailyCandles == null || hourlyCandles == null || entryCandles == null) {
                System.out.println("⚠️ Insufficient data for analysis");
                return null;
            }
            
            // Calculate trends
            double dailyTrend = calculateTrend(dailyCandles);
            double hourlyTrend = calculateTrend(hourlyCandles);
            double entryTrend = calculateTrend(entryCandles);
            
            // Calculate indicators
            double rsi = calculateRSI(entryCandles);
            double volumeRatio = calculateVolumeRatio(entryCandles);
            double volatility = calculateVolatility(entryCandles);
            
            System.out.println("📊 Analysis Results:");
            System.out.println("  Daily Trend: " + String.format("%+.2f%%", dailyTrend * 100));
            System.out.println("  Hourly Trend: " + String.format("%+.2f%%", hourlyTrend * 100));
            System.out.println("  15-min Trend: " + String.format("%+.2f%%", entryTrend * 100));
            System.out.println("  RSI: " + String.format("%.2f", rsi));
            System.out.println("  Volume Ratio: " + String.format("%.2f", volumeRatio));
            System.out.println("  Volatility: " + String.format("%.2f%%", volatility * 100));
            
            // Generate signal
            TradeSignal signal = new TradeSignal();
            
            // Bullish conditions
            if (dailyTrend > 0 && hourlyTrend > 0 && entryTrend > TREND_THRESHOLD) {
                if (rsi < RSI_OVERSOLD && volumeRatio > MIN_VOLUME_RATIO) {
                    signal.side = "buy";
                    signal.confidence = calculateConfidence(dailyTrend, hourlyTrend, entryTrend, rsi, volumeRatio);
                    signal.reason = "Strong uptrend with oversold RSI and high volume";
                }
            }
            
            // Bearish conditions
            if (dailyTrend < 0 && hourlyTrend < 0 && entryTrend < -TREND_THRESHOLD) {
                if (rsi > RSI_OVERBOUGHT && volumeRatio > MIN_VOLUME_RATIO) {
                    signal.side = "sell";
                    signal.confidence = calculateConfidence(Math.abs(dailyTrend), Math.abs(hourlyTrend), 
                                                           Math.abs(entryTrend), 100 - rsi, volumeRatio);
                    signal.reason = "Strong downtrend with overbought RSI and high volume";
                }
            }
            
            if (signal.side != null) {
                System.out.println("✅ Signal: " + signal.side.toUpperCase());
                System.out.println("   Confidence: " + String.format("%.1f%%", signal.confidence * 100));
                System.out.println("   Reason: " + signal.reason);
            }
            
            return signal;
            
        } catch (Exception e) {
            System.err.println("❌ Analysis error: " + e.getMessage());
            return null;
        }
    }
    
    // ==================== POSITION MANAGEMENT ====================
    
    private static PositionDetails calculatePosition(String pair, TradeSignal signal) {
        PositionDetails position = new PositionDetails();
        
        try {
            double currentPrice = getLastPrice(pair);
            if (currentPrice <= 0) return position;
            
            // Calculate dynamic leverage based on volatility
            position.leverage = calculateOptimalLeverage(pair);
            position.leverage = Math.min(position.leverage, MAX_LEVERAGE);
            
            // Calculate TP/SL prices
            if ("buy".equals(signal.side)) {
                position.takeProfitPrice = currentPrice * (1 + TP_PERCENTAGE);
                position.stopLossPrice = currentPrice * (1 - SL_PERCENTAGE);
            } else {
                position.takeProfitPrice = currentPrice * (1 - TP_PERCENTAGE);
                position.stopLossPrice = currentPrice * (1 + SL_PERCENTAGE);
            }
            
            // Calculate position size based on risk
            double stopLossDistance = Math.abs(currentPrice - position.stopLossPrice);
            double riskPerUnit = stopLossDistance / currentPrice;
            
            if (riskPerUnit <= 0) return position;
            
            double riskAmount = ACCOUNT_BALANCE * RISK_PER_TRADE * signal.confidence;
            double positionValue = riskAmount / riskPerUnit;
            
            // Apply leverage
            positionValue = Math.min(positionValue, ACCOUNT_BALANCE * position.leverage);
            
            // Calculate quantity
            position.quantity = positionValue / currentPrice;
            
            // Apply minimum quantity and rounding
            double minQuantity = getMinQuantity(pair);
            if (position.quantity < minQuantity) {
                position.quantity = minQuantity;
            }
            
            if (INTEGER_QUANTITY_PAIRS.contains(pair)) {
                position.quantity = Math.floor(position.quantity);
            } else {
                position.quantity = Math.floor(position.quantity * 100) / 100;
            }
            
            // Final validation
            if (position.quantity < minQuantity) {
                position.quantity = 0;
            }
            
        } catch (Exception e) {
            System.err.println("❌ Position calculation error: " + e.getMessage());
        }
        
        return position;
    }
    
    private static boolean executeTrade(String pair, TradeSignal signal, PositionDetails position) {
        try {
            System.out.println("\n💼 Executing Trade:");
            System.out.println("  Pair: " + pair);
            System.out.println("  Side: " + signal.side.toUpperCase());
            System.out.println("  Quantity: " + position.quantity);
            System.out.println("  Leverage: " + position.leverage + "x");
            System.out.println("  Entry: ~$" + getLastPrice(pair));
            System.out.println("  TP: $" + String.format("%.4f", position.takeProfitPrice));
            System.out.println("  SL: $" + String.format("%.4f", position.stopLossPrice));
            
            // Place order
            JSONObject orderResponse = placeFuturesMarketOrder(
                signal.side, pair, position.quantity, position.leverage,
                "email_notification", "isolated", "INR"
            );
            
            if (orderResponse == null || !orderResponse.has("id")) {
                System.out.println("❌ Order placement failed");
                return false;
            }
            
            String orderId = orderResponse.getString("id");
            System.out.println("✅ Order placed successfully");
            System.out.println("  Order ID: " + orderId);
            
            // Get entry price
            double entryPrice = getEntryPriceFromOrder(orderId);
            if (entryPrice <= 0) {
                entryPrice = getLastPrice(pair);
            }
            
            // Set TP/SL
            String positionId = getPositionId(pair);
            if (positionId != null) {
                boolean tpslSet = setTakeProfitAndStopLoss(positionId, 
                    position.takeProfitPrice, position.stopLossPrice, signal.side, pair);
                
                if (tpslSet) {
                    System.out.println("✅ TP/SL orders placed");
                }
            }
            
            // Record trade
            TradeRecord trade = new TradeRecord();
            trade.pair = pair;
            trade.side = signal.side;
            trade.entryPrice = entryPrice;
            trade.takeProfitPrice = position.takeProfitPrice;
            trade.stopLossPrice = position.stopLossPrice;
            trade.quantity = position.quantity;
            trade.leverage = position.leverage;
            trade.timestamp = Instant.now().toEpochMilli();
            tradeHistory.add(trade);
            
            return true;
            
        } catch (Exception e) {
            System.err.println("❌ Trade execution error: " + e.getMessage());
            return false;
        }
    }
    
    // ==================== UTILITY METHODS ====================
    
    private static List<String> filterTradablePairs() {
        List<String> tradable = new ArrayList<>();
        
        for (String pair : COINS_TO_TRADE) {
            try {
                // Check volume
                Double volume24h = volumeCache.get(getSpotPair(pair));
                if (volume24h == null || volume24h < MIN_VOLUME_24H) {
                    System.out.println("⏭ Skipping " + pair + " - Low volume");
                    continue;
                }
                
                // Check if instrument exists
                if (!instrumentDetailsCache.containsKey(pair)) {
                    System.out.println("⏭ Skipping " + pair + " - Instrument not found");
                    continue;
                }
                
                tradable.add(pair);
                
            } catch (Exception e) {
                System.err.println("❌ Error checking pair " + pair + ": " + e.getMessage());
            }
        }
        
        return tradable;
    }
    
    private static String getSpotPair(String futuresPair) {
        return futuresPair.replace("B-", "");
    }
    
    private static double calculateTrend(JSONArray candles) {
        if (candles == null || candles.length() < 2) return 0;
        
        double firstClose = candles.getJSONObject(0).getDouble("close");
        double lastClose = candles.getJSONObject(candles.length() - 1).getDouble("close");
        
        return (lastClose - firstClose) / firstClose;
    }
    
    private static double calculateRSI(JSONArray candles) {
        if (candles == null || candles.length() < RSI_PERIOD + 1) return 50;
        
        double[] closes = new double[candles.length()];
        for (int i = 0; i < candles.length(); i++) {
            closes[i] = candles.getJSONObject(i).getDouble("close");
        }
        
        double avgGain = 0;
        double avgLoss = 0;
        
        // Initial calculation
        for (int i = 1; i <= RSI_PERIOD; i++) {
            double change = closes[i] - closes[i-1];
            if (change > 0) {
                avgGain += change;
            } else {
                avgLoss += Math.abs(change);
            }
        }
        
        avgGain /= RSI_PERIOD;
        avgLoss /= RSI_PERIOD;
        
        // Subsequent calculations
        for (int i = RSI_PERIOD + 1; i < closes.length; i++) {
            double change = closes[i] - closes[i-1];
            if (change > 0) {
                avgGain = (avgGain * (RSI_PERIOD - 1) + change) / RSI_PERIOD;
                avgLoss = (avgLoss * (RSI_PERIOD - 1)) / RSI_PERIOD;
            } else {
                avgLoss = (avgLoss * (RSI_PERIOD - 1) + Math.abs(change)) / RSI_PERIOD;
                avgGain = (avgGain * (RSI_PERIOD - 1)) / RSI_PERIOD;
            }
        }
        
        if (avgLoss == 0) return 100;
        
        double rs = avgGain / avgLoss;
        return 100 - (100 / (1 + rs));
    }
    
    private static double calculateVolumeRatio(JSONArray candles) {
        if (candles == null || candles.length() < 5) return 1.0;
        
        double recentVolume = 0;
        double averageVolume = 0;
        
        int recentCount = Math.min(3, candles.length());
        for (int i = candles.length() - recentCount; i < candles.length(); i++) {
            recentVolume += candles.getJSONObject(i).getDouble("volume");
        }
        recentVolume /= recentCount;
        
        for (int i = 0; i < candles.length(); i++) {
            averageVolume += candles.getJSONObject(i).getDouble("volume");
        }
        averageVolume /= candles.length();
        
        if (averageVolume == 0) return 1.0;
        return recentVolume / averageVolume;
    }
    
    private static double calculateVolatility(JSONArray candles) {
        if (candles == null || candles.length() < 2) return 0.05;
        
        double sum = 0;
        for (int i = 0; i < candles.length(); i++) {
            sum += candles.getJSONObject(i).getDouble("close");
        }
        double mean = sum / candles.length();
        
        double variance = 0;
        for (int i = 0; i < candles.length(); i++) {
            double close = candles.getJSONObject(i).getDouble("close");
            variance += Math.pow(close - mean, 2);
        }
        variance /= candles.length();
        
        return Math.sqrt(variance) / mean;
    }
    
    private static double calculateConfidence(double dailyTrend, double hourlyTrend, 
                                             double entryTrend, double rsiScore, double volumeRatio) {
        // Weighted average of factors
        double confidence = 0;
        confidence += dailyTrend * 0.2;
        confidence += hourlyTrend * 0.3;
        confidence += entryTrend * 0.4;
        confidence += (rsiScore / 100) * 0.1;
        
        // Boost for high volume
        if (volumeRatio > 1.5) {
            confidence *= 1.2;
        }
        
        return Math.min(Math.max(confidence, 0.3), 1.0);
    }
    
    private static int calculateOptimalLeverage(String pair) {
        try {
            String spotPair = getSpotPair(pair);
            JSONArray candles = getCandlestickData(spotPair, "1h", 24);
            if (candles == null) return DEFAULT_LEVERAGE;
            
            double volatility = calculateVolatility(candles);
            
            if (volatility > 0.08) return 3;     // High volatility
            if (volatility > 0.05) return 5;     // Medium volatility
            return DEFAULT_LEVERAGE;             // Low volatility
            
        } catch (Exception e) {
            return DEFAULT_LEVERAGE;
        }
    }
    
    private static double calculatePortfolioExposure() {
        double exposure = 0;
        for (TradeRecord trade : tradeHistory) {
            exposure += trade.entryPrice * trade.quantity * trade.leverage;
        }
        return exposure;
    }
    
    private static void displayTradeSummary(String pair, TradeSignal signal, PositionDetails position) {
        System.out.println("\n📋 Trade Summary:");
        System.out.println("  Pair: " + pair);
        System.out.println("  Signal: " + signal.side.toUpperCase());
        System.out.println("  Confidence: " + String.format("%.1f%%", signal.confidence * 100));
        System.out.println("  Quantity: " + position.quantity);
        System.out.println("  Leverage: " + position.leverage + "x");
        System.out.println("  Risk per trade: $" + 
            String.format("%.2f", ACCOUNT_BALANCE * RISK_PER_TRADE * signal.confidence));
        System.out.println("  Reward/Risk: " + String.format("%.2f", TP_PERCENTAGE / SL_PERCENTAGE));
    }
    
    // ==================== DATA MANAGEMENT ====================
    
    private static void initializeInstrumentDetails() {
        try {
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastInstrumentUpdateTime > TICK_SIZE_CACHE_TTL_MS) {
                System.out.println("🔄 Updating instrument details...");
                instrumentDetailsCache.clear();
                
                String response = sendPublicRequest(
                    BASE_URL + "/exchange/v1/derivatives/futures/data/active_instruments"
                );
                
                JSONArray instruments = new JSONArray(response);
                for (int i = 0; i < instruments.length(); i++) {
                    String pair = instruments.getString(i);
                    try {
                        String instrumentResponse = sendPublicRequest(
                            BASE_URL + "/exchange/v1/derivatives/futures/data/instrument?pair=" + pair
                        );
                        JSONObject instrument = new JSONObject(instrumentResponse).getJSONObject("instrument");
                        instrumentDetailsCache.put(pair, instrument);
                    } catch (Exception e) {
                        // Skip this instrument
                    }
                }
                
                lastInstrumentUpdateTime = currentTime;
                System.out.println("✅ Loaded " + instrumentDetailsCache.size() + " instruments");
            }
        } catch (Exception e) {
            System.err.println("❌ Error initializing instruments: " + e.getMessage());
        }
    }
    
    private static void updateVolumeCache() {
        try {
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastVolumeUpdateTime > 300000) { // 5 minutes
                System.out.println("🔄 Updating volume data...");
                volumeCache.clear();
                
                String response = sendPublicRequest(PUBLIC_API_URL + "/market_data/ticker");
                JSONArray tickers = new JSONArray(response);
                
                for (int i = 0; i < tickers.length(); i++) {
                    JSONObject ticker = tickers.getJSONObject(i);
                    String market = ticker.getString("market");
                    double volume = ticker.optDouble("volume", 0);
                    volumeCache.put(market, volume);
                }
                
                lastVolumeUpdateTime = currentTime;
                System.out.println("✅ Loaded volume for " + volumeCache.size() + " markets");
            }
        } catch (Exception e) {
            System.err.println("❌ Error updating volume: " + e.getMessage());
        }
    }
    
    private static double getMinQuantity(String pair) {
        JSONObject instrument = instrumentDetailsCache.get(pair);
        if (instrument != null) {
            return instrument.optDouble("min_quantity", 0.001);
        }
        return 0.001;
    }
    
    private static double getTickSizeForPair(String pair) {
        if (System.currentTimeMillis() - lastInstrumentUpdateTime > TICK_SIZE_CACHE_TTL_MS) {
            initializeInstrumentDetails();
        }
        
        JSONObject instrument = instrumentDetailsCache.get(pair);
        if (instrument != null) {
            return instrument.optDouble("price_increment", 0.0001);
        }
        return 0.0001;
    }
    
    // ==================== API METHODS ====================
    
    private static JSONArray getCandlestickData(String pair, String resolution, int candleCount) {
        try {
            long endTime = Instant.now().getEpochSecond();
            int resolutionMinutes = Integer.parseInt(resolution.replace("m", "").replace("h", ""));
            if (resolution.contains("h")) resolutionMinutes *= 60;
            if (resolution.contains("d")) resolutionMinutes *= 1440;
            
            long startTime = endTime - (candleCount * resolutionMinutes * 60);
            
            String url = PUBLIC_API_URL + "/market_data/candlesticks?pair=" + pair +
                    "&from=" + startTime + "&to=" + endTime + "&resolution=" + resolution;
            
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            
            if (conn.getResponseCode() == 200) {
                String response = readAllLines(conn.getInputStream());
                JSONObject jsonResponse = new JSONObject(response);
                if (jsonResponse.getString("s").equals("ok")) {
                    return jsonResponse.getJSONArray("data");
                }
            }
        } catch (Exception e) {
            // Silent fail - return null
        }
        return null;
    }
    
    private static double getLastPrice(String pair) {
        try {
            String spotPair = getSpotPair(pair);
            String url = PUBLIC_API_URL + "/market_data/trade_history?pair=" + spotPair + "&limit=1";
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            
            if (conn.getResponseCode() == 200) {
                String response = readAllLines(conn.getInputStream());
                if (response.startsWith("[")) {
                    return new JSONArray(response).getJSONObject(0).getDouble("p");
                } else {
                    return new JSONObject(response).getDouble("p");
                }
            }
        } catch (Exception e) {
            System.err.println("❌ Error getting price for " + pair);
        }
        return 0;
    }
    
    private static Set<String> getActivePositions() {
        Set<String> positions = new HashSet<>();
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
            
            JSONArray positionsArray = response.startsWith("[") ? 
                new JSONArray(response) : new JSONArray().put(new JSONObject(response));
            
            for (int i = 0; i < positionsArray.length(); i++) {
                JSONObject position = positionsArray.getJSONObject(i);
                String pair = position.optString("pair", "");
                double avgPrice = position.optDouble("avg_price", 0);
                
                if (!pair.isEmpty() && avgPrice > 0) {
                    positions.add(pair);
                }
            }
        } catch (Exception e) {
            System.err.println("❌ Error getting positions: " + e.getMessage());
        }
        return positions;
    }
    
    // ==================== ORDER METHODS ====================
    
    private static JSONObject placeFuturesMarketOrder(String side, String pair, double totalQuantity, 
                                                     int leverage, String notification, 
                                                     String positionMarginType, String marginCurrency) {
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
                new JSONArray(response).getJSONObject(0) : new JSONObject(response);
        } catch (Exception e) {
            System.err.println("❌ Order placement error: " + e.getMessage());
            return null;
        }
    }
    
    private static double getEntryPriceFromOrder(String orderId) {
        try {
            JSONObject body = new JSONObject();
            body.put("timestamp", Instant.now().toEpochMilli());
            body.put("id", orderId);
            
            String response = sendAuthenticatedRequest(
                BASE_URL + "/exchange/v1/derivatives/futures/orders/details",
                body.toString(),
                generateHmacSHA256(API_SECRET, body.toString())
            );
            
            JSONObject orderDetails = new JSONObject(response);
            return orderDetails.optDouble("average_price", 0);
        } catch (Exception e) {
            return 0;
        }
    }
    
    private static String getPositionId(String pair) {
        try {
            JSONObject body = new JSONObject();
            body.put("timestamp", Instant.now().toEpochMilli());
            body.put("page", "1");
            body.put("size", "10");
            
            String response = sendAuthenticatedRequest(
                BASE_URL + "/exchange/v1/derivatives/futures/positions",
                body.toString(),
                generateHmacSHA256(API_SECRET, body.toString())
            );
            
            JSONArray positions = response.startsWith("[") ? 
                new JSONArray(response) : new JSONArray().put(new JSONObject(response));
            
            for (int i = 0; i < positions.length(); i++) {
                JSONObject position = positions.getJSONObject(i);
                if (position.getString("pair").equals(pair)) {
                    return position.getString("id");
                }
            }
        } catch (Exception e) {
            System.err.println("❌ Error getting position ID: " + e.getMessage());
        }
        return null;
    }
    
    private static boolean setTakeProfitAndStopLoss(String positionId, double takeProfitPrice, 
                                                   double stopLossPrice, String side, String pair) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("timestamp", Instant.now().toEpochMilli());
            payload.put("id", positionId);
            
            double tickSize = getTickSizeForPair(pair);
            double roundedTp = Math.round(takeProfitPrice / tickSize) * tickSize;
            double roundedSl = Math.round(stopLossPrice / tickSize) * tickSize;
            
            JSONObject takeProfit = new JSONObject();
            takeProfit.put("stop_price", roundedTp);
            takeProfit.put("limit_price", roundedTp);
            takeProfit.put("order_type", "take_profit_market");
            
            JSONObject stopLoss = new JSONObject();
            stopLoss.put("stop_price", roundedSl);
            stopLoss.put("limit_price", roundedSl);
            stopLoss.put("order_type", "stop_market");
            
            payload.put("take_profit", takeProfit);
            payload.put("stop_loss", stopLoss);
            
            String response = sendAuthenticatedRequest(
                BASE_URL + "/exchange/v1/derivatives/futures/positions/create_tpsl",
                payload.toString(),
                generateHmacSHA256(API_SECRET, payload.toString())
            );
            
            JSONObject tpslResponse = new JSONObject(response);
            return !tpslResponse.has("err_code_dcx");
        } catch (Exception e) {
            System.err.println("❌ Error setting TP/SL: " + e.getMessage());
            return false;
        }
    }
    
    // ==================== HELPER CLASSES ====================
    
    static class TradeSignal {
        String side;
        double confidence;
        String reason;
    }
    
    static class PositionDetails {
        double quantity;
        int leverage;
        double takeProfitPrice;
        double stopLossPrice;
    }
    
    static class TradeRecord {
        String pair;
        String side;
        double entryPrice;
        double takeProfitPrice;
        double stopLossPrice;
        double quantity;
        int leverage;
        long timestamp;
        Double exitPrice;
        Double pnl;
    }
    
    // ==================== HTTP UTILITIES ====================
    
    private static String sendPublicRequest(String endpoint) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setRequestMethod("GET");
        if (conn.getResponseCode() == 200) {
            return readAllLines(conn.getInputStream());
        }
        throw new IOException("HTTP " + conn.getResponseCode());
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
                .lines().collect(Collectors.joining("\n"));
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
            throw new RuntimeException("Error generating HMAC", e);
        }
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
//     private static final int LOOKBACK_PERIOD = 15; // Minutes for trend analysis (changed from hours)
//    // Number of candles to look back for trend analysis
// private static final int LOOKBACK_CANDLES = 15; 
// private static final int CANDLE_RES_MINUTES = 15; // 15-minute candles
// private static final double TREND_THRESHOLD = 0.01; // 1% price change threshold
//     private static final double TP_PERCENTAGE = 0.05; // 3% take profit
//     private static final double SL_PERCENTAGE = 0.03; // 5% stop loss

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

//                 int leverage = 20; // Default leverage

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

// // ----------------------------
     
//      private static String determineSideWithRSIOnly(String pair) {
//     try {
//         // Get price data for RSI calculation
//         double lastPrice = getLastPrice(pair);
        
//         // Get recent trades for price movement
//         String tradesUrl = PUBLIC_API_URL + "/market_data/trade_history?pair=" + pair + "&limit=20";
//         HttpURLConnection conn = (HttpURLConnection) new URL(tradesUrl).openConnection();
        
//         if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
//             String response = readAllLines(conn.getInputStream());
//             JSONArray trades = new JSONArray(response);
            
//             if (trades.length() < 14) {
//                 System.out.println("⚠️ Not enough trade data for RSI calculation");
//                 return null;
//             }
            
//             double[] prices = new double[trades.length()];
//             for (int i = 0; i < trades.length(); i++) {
//                 prices[i] = trades.getJSONObject(i).getDouble("p");
//             }
            
//             // Calculate RSI
//             double[] changes = new double[prices.length - 1];
//             for (int i = 1; i < prices.length; i++) {
//                 changes[i - 1] = prices[i] - prices[i - 1];
//             }
            
//             double avgGain = 0;
//             double avgLoss = 0;
//             int period = 14;
            
//             for (int i = 0; i < period; i++) {
//                 if (changes[i] > 0) {
//                     avgGain += changes[i];
//                 } else {
//                     avgLoss += Math.abs(changes[i]);
//                 }
//             }
            
//             avgGain /= period;
//             avgLoss /= period;
            
//             if (avgLoss == 0) {
//                 return "sell"; // If only gains, market might be overbought
//             }
            
//             double rs = avgGain / avgLoss;
//             double rsi = 100 - (100 / (1 + rs));
            
//             System.out.println("RSI for " + pair + ": " + rsi);
            
//    if (rsi <= 35) {
//     System.out.println("🔽 RSI " + rsi + " → LONG");
//     return "buy";
// } else if (rsi >= 65) {
//     System.out.println("🔼 RSI " + rsi + " → SHORT");
//     return "sell";
// } else {
//     System.out.println("⏸ RSI " + rsi + " → Neutral, follow trend");
//     return null;
// }

//         }
//     } catch (Exception e) {
//         System.err.println("❌ Error in RSI-only calculation: " + e.getMessage());
//     }
//     return null;
// }


//      private static String determinePositionSide(String pair) {
//     try {
//         System.out.println("\n📊 Analyzing " + pair + "...");
        
//         JSONArray candles = getCandlestickData(pair, CANDLE_RES_MINUTES + "m", LOOKBACK_CANDLES);

//         if (candles == null || candles.length() < 2) {
//             System.out.println("⚠️ Not enough data for trend analysis, trying alternative methods...");
            
//             // Try 5m candles as fallback
//             candles = getCandlestickData(pair, "5m", LOOKBACK_CANDLES * 3);
            
//             if (candles == null || candles.length() < 2) {
//                 System.out.println("⚠️ Still no candle data, checking last price movement...");
//                 return determineSideFromPriceMovement(pair);
//             }
//         }

//         // Ensure we have enough data for analysis
//         int requiredCandles = Math.min(LOOKBACK_CANDLES, candles.length());
//         double firstClose = candles.getJSONObject(0).getDouble("close");
//         double lastClose = candles.getJSONObject(requiredCandles - 1).getDouble("close");
//         double priceChange = (lastClose - firstClose) / firstClose;

//         System.out.println("Trend Analysis for " + pair + ":");
//         System.out.println("Candles analyzed: " + requiredCandles);
//         System.out.println("First Close: " + firstClose);
//         System.out.println("Last Close: " + lastClose);
//         System.out.println("Price Change: " + String.format("%.2f%%", priceChange * 100));

//         if (priceChange > TREND_THRESHOLD) {
//             System.out.println("📈 Uptrend detected - Going LONG");
//             return "buy";
//         } else if (priceChange < -TREND_THRESHOLD) {
//             System.out.println("📉 Downtrend detected - Going SHORT");
//             return "sell";
//         } else {
//             System.out.println("➡️ Sideways market - Using RSI for decision");
//             return determineSideWithRSI(candles);
//         }
//     } catch (Exception e) {
//         System.err.println("❌ Error determining position side: " + e.getMessage());
//         // Fallback to random or skip
//         return null;
//     }
// }


//      private static String determineSideFromPriceMovement(String pair) {
//     try {
//         // Get recent trades as fallback
//         String tradesUrl = PUBLIC_API_URL + "/market_data/trade_history?pair=" + pair + "&limit=50";
//         HttpURLConnection conn = (HttpURLConnection) new URL(tradesUrl).openConnection();
//         conn.setRequestMethod("GET");
        
//         if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
//             String response = readAllLines(conn.getInputStream());
//             JSONArray trades = new JSONArray(response);
            
//             if (trades.length() >= 10) {
//                 // Analyze last 10 trades
//                 double oldestPrice = trades.getJSONObject(0).getDouble("p");
//                 double newestPrice = trades.getJSONObject(trades.length() - 1).getDouble("p");
//                 double change = (newestPrice - oldestPrice) / oldestPrice;
                
//                 System.out.println("Price movement analysis for " + pair + ":");
//                 System.out.println("Oldest price: " + oldestPrice);
//                 System.out.println("Newest price: " + newestPrice);
//                 System.out.println("Change: " + String.format("%.2f%%", change * 100));
                
//                 if (change > TREND_THRESHOLD) {
//                     System.out.println("📈 Price uptrend - Going LONG");
//                     return "buy";
//                 } else if (change < -TREND_THRESHOLD) {
//                     System.out.println("📉 Price downtrend - Going SHORT");
//                     return "sell";
//                 }
//             }
//         }
        
//         System.out.println("⚠️ Not enough data for any analysis - Skipping");
//         return null;
        
//     } catch (Exception e) {
//         System.err.println("❌ Error in price movement analysis: " + e.getMessage());
//         return null;
//     }
// }

     
// // ----------------------------


// private static JSONArray getCandlestickData(String pair, String resolution, int candleCount) {
//     try {
//         // Current timestamp in seconds
//         long endTime = Instant.now().getEpochSecond();
        
//         // Parse resolution (e.g., "15m" -> 15)
//         String resolutionStr = resolution.replace("m", "");
//         int resolutionMinutes = Integer.parseInt(resolutionStr);
        
//         long startTime = endTime - (candleCount * resolutionMinutes * 60);
        
//         String url = PUBLIC_API_URL + "/market_data/candlesticks?pair=" + pair +
//                 "&from=" + startTime + "&to=" + endTime +
//                 "&resolution=" + resolution;
        
//         System.out.println("🔍 Fetching candles for " + pair + " from URL: " + url);
        
//         HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
//         conn.setRequestMethod("GET");
//         conn.setConnectTimeout(10000);
//         conn.setReadTimeout(10000);
        
//         int responseCode = conn.getResponseCode();
//         System.out.println("📡 Response Code: " + responseCode);
        
//         if (responseCode == HttpURLConnection.HTTP_OK) {
//             String response = readAllLines(conn.getInputStream());
            
//             if (response == null || response.trim().isEmpty()) {
//                 System.out.println("⚠️ Empty response for " + pair);
//                 return null;
//             }
            
//             System.out.println("📄 Raw Response (first 500 chars): " + 
//                 (response.length() > 500 ? response.substring(0, 500) + "..." : response));
            
//             try {
//                 JSONObject jsonResponse = new JSONObject(response);
//                 if (jsonResponse.has("s") && jsonResponse.getString("s").equals("ok")) {
//                     if (jsonResponse.has("data")) {
//                         JSONArray data = jsonResponse.getJSONArray("data");
//                         System.out.println("✅ Received " + data.length() + " candles for " + pair);
//                         if (data.length() > 0) {
//                             // Log first and last candle
//                             System.out.println("First candle: " + data.getJSONObject(0));
//                             System.out.println("Last candle: " + data.getJSONObject(data.length()-1));
//                         }
//                         return data;
//                     }
//                 } else {
//                     System.out.println("⚠️ API error status for " + pair + ": " + jsonResponse);
//                     if (jsonResponse.has("error")) {
//                         System.out.println("Error message: " + jsonResponse.getString("error"));
//                     }
//                 }
//             } catch (Exception e) {
//                 System.err.println("❌ JSON parsing error: " + e.getMessage());
//                 System.err.println("Response: " + response);
//             }
//         } else {
//             System.out.println("❌ HTTP error: " + responseCode);
//             try {
//                 String errorResponse = readAllLines(conn.getErrorStream());
//                 System.err.println("Error response: " + errorResponse);
//             } catch (Exception e) {
//                 // Ignore
//             }
//         }
//     } catch (Exception e) {
//         System.err.println("❌ Exception in getCandlestickData for " + pair + ": " + e.getMessage());
//         e.printStackTrace();
//     }
//     return null;
// }
   

//      // Add this temporary debug method
// private static void debugCandleAPI(String pair) {
//     try {
//         long endTime = Instant.now().toEpochMilli();
//         long startTime = endTime - TimeUnit.HOURS.toMillis(1); // Last hour
        
//         String url = PUBLIC_API_URL + "/market_data/candlesticks?pair=" + pair +
//                 "&from=" + startTime + "&to=" + endTime +
//                 "&resolution=15m";
        
//         System.out.println("Debug URL for " + pair + ": " + url);
        
//         HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
//         conn.setRequestMethod("GET");
        
//         int responseCode = conn.getResponseCode();
//         System.out.println("Response Code: " + responseCode);
        
//         if (responseCode == HttpURLConnection.HTTP_OK) {
//             String response = readAllLines(conn.getInputStream());
//             System.out.println("Response: " + response);
//         }
//     } catch (Exception e) {
//         e.printStackTrace();
//     }
// }


//     private static String determineSideWithRSI(JSONArray candles) {
//         try {
//             double[] closes = new double[candles.length()];
//             for (int i = 0; i < candles.length(); i++) {
//                 closes[i] = candles.getJSONObject(i).getDouble("close");
//             }

//             double avgGain = 0;
//             double avgLoss = 0;
//             int rsiPeriod = 7;

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
//     private static final double TREND_THRESHOLD = 0.005; // 2% change threshold for trend
//     private static final double TP_PERCENTAGE = 0.05; // 3% take profit
//     private static final double SL_PERCENTAGE = 0.04; // 5% stop loss

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

//                 int leverage = 15; // Default leverage

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
//             JSONArray candles = getCandlestickData(pair, "30m", LOOKBACK_PERIOD);

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
