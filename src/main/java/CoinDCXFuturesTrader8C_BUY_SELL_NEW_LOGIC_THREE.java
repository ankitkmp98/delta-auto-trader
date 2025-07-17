import org.json.JSONArray;
import org.json.JSONObject;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class AdvancedCoinDCXFuturesTrader {
    
    // API Configuration
    private static final String API_KEY = System.getenv("DELTA_API_KEY");
    private static final String API_SECRET = System.getenv("DELTA_API_SECRET");
    private static final String BASE_URL = "https://api.coindcx.com";
    private static final String PUBLIC_API_URL = "https://public.coindcx.com";
    
    // Enhanced Trading Configuration
    private static final double MAX_MARGIN_PER_TRADE = 600.0;
    private static final int DEFAULT_LEVERAGE = 3;
    private static final double BASE_TP_PERCENTAGE = 0.01; // 1% base TP
    private static final double BASE_SL_PERCENTAGE = 0.10; // 10% base SL
    private static final int TREND_LOOKBACK_MINUTES = 15;
    private static final double TREND_THRESHOLD = 0.02; // 2% threshold
    private static final double VOLATILITY_MULTIPLIER = 1.5; // ATR multiplier
    private static final int MAX_CONCURRENT_POSITIONS = 10;
    
    // Rate Limiting & Caching
    private static final int MAX_REQUESTS_PER_MINUTE = 100;
    private static final long RATE_LIMIT_WINDOW_MS = 60000;
    private static final Queue<Long> requestTimes = new ConcurrentLinkedQueue<>();
    private static final Map<String, JSONObject> instrumentCache = new ConcurrentHashMap<>();
    private static final Map<String, MarketData> marketDataCache = new ConcurrentHashMap<>();
    private static final Map<String, Double> priceCache = new ConcurrentHashMap<>();
    private static long lastCacheUpdate = 0;
    private static final long CACHE_TTL_MS = 300000; // 5 minutes
    
    // Enhanced Trading Symbols
    private static final String[] COIN_SYMBOLS = {
        "BTC", "ETH", "BNB", "ADA", "DOT", "LINK", "LTC", "XRP", "AVAX", "MATIC",
        "SOL", "DOGE", "SHIB", "UNI", "ATOM", "NEAR", "FTM", "ALGO", "MANA", "SAND",
        "1000SATS", "1000X", "ACT", "AIXBT", "AI16Z", "ALT", "API3", "ARB", "ARC",
        "AVAAI", "BAKE", "BB", "BIO", "BLUR", "BMT", "BONK", "COOKIE", "DOGS", "DYDX",
        "EIGEN", "ENA", "EOS", "ETHFI", "FARTCOIN", "FLOKI", "GALA", "GLM", "GOAT",
        "GRIFFAIN", "HBAR", "HIVE", "IO", "IOTA", "JASMY", "JUP", "KAITO", "LDO",
        "LISTA", "MANTA", "MEME", "MELANIA", "MOODENG", "MOVE", "MUBARAK", "NEIRO",
        "NOT", "ONDO", "OP", "PEOPLE", "PEPE", "PENGU", "PI", "PNUT", "POL", "POPCAT",
        "RARE", "RED", "RSR", "SAGA", "SEI", "SOLV", "SONIC", "SPX", "STX", "SUN",
        "SWARMS", "SUSHI", "TST", "TRX", "USUAL", "VINE", "VIRTUAL", "WIF", "WLD",
        "XAI", "XLM", "ZK", "TAO", "TRUMP", "PERP", "OM", "GMT", "LAYER", "HIGH",
        "ALPACA", "FLM", "BSW", "FIL", "ARK", "ENJ", "RENDER", "CRV", "BCH", "OGN",
        "1000SHIB", "AAVE", "ORCA", "T", "FUN", "VTHO", "ALCH", "GAS", "TIA", "MBOX",
        "APT", "ORDI", "INJ", "BEL", "PARTI", "BIGTIME", "ETC", "BOME", "TON",
        "1000BONK", "ACH", "LEVER", "S"
    };
    
    private static final String[] TRADING_PAIRS = Arrays.stream(COIN_SYMBOLS)
            .map(symbol -> "B-" + symbol + "_USDT")
            .toArray(String[]::new);
    
    // Thread pool for concurrent operations
    private static final ExecutorService executor = Executors.newFixedThreadPool(10);
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(5);
    
    public static void main(String[] args) {
        logInfo("Starting Advanced CoinDCX Futures Trading Bot...");
        
        try {
            // Initialize systems
            initializeSystem();
            
            // Start main trading loop
            startTradingLoop();
            
        } catch (Exception e) {
            logError("Critical error in main execution: " + e.getMessage());
            e.printStackTrace();
        } finally {
            cleanup();
        }
    }
    
    private static void initializeSystem() {
        try {
            // Initialize instrument cache
            initializeInstrumentCache();
            
            // Start background tasks
            startBackgroundTasks();
            
            logInfo("System initialized successfully");
            
        } catch (Exception e) {
            logError("Failed to initialize system: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }
    
    private static void startTradingLoop() {
        while (true) {
            try {
                // Get active positions
                Set<String> activePositions = getActivePositions();
                logInfo("Active positions: " + activePositions.size());
                
                // Check if we've hit max concurrent positions
                if (activePositions.size() >= MAX_CONCURRENT_POSITIONS) {
                    logInfo("Maximum concurrent positions reached. Waiting...");
                    Thread.sleep(60000); // Wait 1 minute
                    continue;
                }
                
                // Process trading pairs
                processTradingPairs(activePositions);
                
                // Wait before next iteration
                Thread.sleep(30000); // Wait 30 seconds between iterations
                
            } catch (Exception e) {
                logError("Error in trading loop: " + e.getMessage());
                try {
                    Thread.sleep(60000); // Wait 1 minute on error
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }
    
    private static void processTradingPairs(Set<String> activePositions) {
        List<Future<Void>> futures = new ArrayList<>();
        
        for (String pair : TRADING_PAIRS) {
            if (activePositions.contains(pair)) {
                continue; // Skip active positions
            }
            
            Future<Void> future = executor.submit(() -> {
                try {
                    processSinglePair(pair);
                } catch (Exception e) {
                    logError("Error processing " + pair + ": " + e.getMessage());
                }
                return null;
            });
            
            futures.add(future);
            
            // Small delay to avoid overwhelming the API
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        
        // Wait for all tasks to complete with timeout
        for (Future<Void> future : futures) {
            try {
                future.get(60, TimeUnit.SECONDS);
            } catch (Exception e) {
                logError("Task execution error: " + e.getMessage());
            }
        }
    }
    
    private static void processSinglePair(String pair) throws Exception {
        logInfo("Analyzing " + pair + "...");
        
        // Get comprehensive market analysis
        ComprehensiveAnalysis analysis = performComprehensiveAnalysis(pair);
        if (analysis == null || analysis.signal == TradeSignal.HOLD) {
            logInfo("No trading signal for " + pair);
            return;
        }
        
        // Check order book liquidity
        if (!hasAdequateLiquidity(pair)) {
            logInfo("Insufficient liquidity for " + pair);
            return;
        }
        
        // Get current market price
        double currentPrice = getCurrentPrice(pair);
        if (currentPrice <= 0) {
            logError("Invalid price for " + pair);
            return;
        }
        
        // Calculate optimal position size
        double quantity = calculateOptimalQuantity(pair, currentPrice, analysis.volatility);
        if (quantity <= 0) {
            logError("Invalid quantity for " + pair);
            return;
        }
        
        // Place order with advanced parameters
        String orderId = placeAdvancedOrder(pair, analysis, quantity, currentPrice);
        if (orderId == null) {
            logError("Failed to place order for " + pair);
            return;
        }
        
        logInfo("Order placed successfully for " + pair + " - ID: " + orderId);
        
        // Wait for order execution and set advanced TP/SL
        Thread.sleep(3000); // Wait for order to execute
        setAdvancedTakeProfitStopLoss(pair, orderId, analysis, currentPrice);
    }
    
    private static ComprehensiveAnalysis performComprehensiveAnalysis(String pair) {
        try {
            // Multi-timeframe analysis
            MultiTimeframeAnalysis mtfAnalysis = performMultiTimeframeAnalysis(pair);
            
            // Technical indicators
            TechnicalIndicators indicators = calculateTechnicalIndicators(pair);
            
            // Market sentiment analysis
            MarketSentiment sentiment = analyzeSentiment(pair);
            
            // Order book analysis
            OrderBookAnalysis orderBook = analyzeOrderBook(pair);
            
            // Combine all signals
            TradeSignal finalSignal = combineSignals(mtfAnalysis, indicators, sentiment, orderBook);
            
            // Calculate volatility for position sizing
            double volatility = calculateVolatility(pair);
            
            return new ComprehensiveAnalysis(finalSignal, volatility, indicators, sentiment);
            
        } catch (Exception e) {
            logError("Error in comprehensive analysis for " + pair + ": " + e.getMessage());
            return null;
        }
    }
    
    private static MultiTimeframeAnalysis performMultiTimeframeAnalysis(String pair) {
        try {
            // Analyze multiple timeframes
            Map<String, TrendAnalysis> trends = new HashMap<>();
            
            // 5-minute trend
            JSONArray candles5m = getCandlestickData(pair, "5", 20);
            trends.put("5m", analyzeTrend(candles5m));
            
            // 15-minute trend
            JSONArray candles15m = getCandlestickData(pair, "15", 20);
            trends.put("15m", analyzeTrend(candles15m));
            
            // 1-hour trend
            JSONArray candles1h = getCandlestickData(pair, "60", 20);
            trends.put("1h", analyzeTrend(candles1h));
            
            // 1-day trend
            JSONArray candles1d = getCandlestickData(pair, "1D", 20);
            trends.put("1d", analyzeTrend(candles1d));
            
            return new MultiTimeframeAnalysis(trends);
            
        } catch (Exception e) {
            logError("Error in multi-timeframe analysis: " + e.getMessage());
            return new MultiTimeframeAnalysis(new HashMap<>());
        }
    }
    
    private static TechnicalIndicators calculateTechnicalIndicators(String pair) {
        try {
            JSONArray candles = getCandlestickData(pair, "5", 50);
            if (candles == null || candles.length() < 20) {
                return new TechnicalIndicators();
            }
            
            double[] closes = extractCloses(candles);
            double[] highs = extractHighs(candles);
            double[] lows = extractLows(candles);
            double[] volumes = extractVolumes(candles);
            
            // Calculate indicators
            double rsi = calculateRSI(closes, 14);
            MACDResult macd = calculateMACD(closes);
            BollingerBands bb = calculateBollingerBands(closes, 20, 2.0);
            double[] sma20 = calculateSMA(closes, 20);
            double[] ema12 = calculateEMA(closes, 12);
            double[] ema26 = calculateEMA(closes, 26);
            double atr = calculateATR(highs, lows, closes, 14);
            double volume = calculateAverageVolume(volumes, 20);
            
            return new TechnicalIndicators(rsi, macd, bb, sma20, ema12, ema26, atr, volume);
            
        } catch (Exception e) {
            logError("Error calculating technical indicators: " + e.getMessage());
            return new TechnicalIndicators();
        }
    }
    
    private static MarketSentiment analyzeSentiment(String pair) {
        try {
            // Get market statistics
            JSONObject stats = getMarketStats(pair);
            
            // Analyze 24h volume and price change
            double priceChange24h = stats.optDouble("change_24_hour", 0);
            double volume24h = stats.optDouble("volume", 0);
            
            // Get funding rate data (if available)
            double fundingRate = getFundingRate(pair);
            
            // Analyze long/short ratio
            double longShortRatio = getLongShortRatio(pair);
            
            return new MarketSentiment(priceChange24h, volume24h, fundingRate, longShortRatio);
            
        } catch (Exception e) {
            logError("Error analyzing sentiment: " + e.getMessage());
            return new MarketSentiment(0, 0, 0, 1.0);
        }
    }
    
    private static OrderBookAnalysis analyzeOrderBook(String pair) {
        try {
            JSONObject orderBook = getOrderBook(pair);
            
            // Calculate bid-ask spread
            JSONObject bids = orderBook.getJSONObject("bids");
            JSONObject asks = orderBook.getJSONObject("asks");
            
            double bestBid = getHighestBid(bids);
            double bestAsk = getLowestAsk(asks);
            double spread = (bestAsk - bestBid) / bestBid * 100;
            
            // Calculate market depth
            double bidDepth = calculateDepth(bids, bestBid, 0.02); // 2% depth
            double askDepth = calculateDepth(asks, bestAsk, 0.02);
            
            // Calculate order book imbalance
            double imbalance = (bidDepth - askDepth) / (bidDepth + askDepth);
            
            return new OrderBookAnalysis(spread, bidDepth, askDepth, imbalance);
            
        } catch (Exception e) {
            logError("Error analyzing order book: " + e.getMessage());
            return new OrderBookAnalysis(0, 0, 0, 0);
        }
    }
    
    private static TradeSignal combineSignals(MultiTimeframeAnalysis mtf, TechnicalIndicators tech, 
                                            MarketSentiment sentiment, OrderBookAnalysis orderBook) {
        int bullishScore = 0;
        int bearishScore = 0;
        
        // Multi-timeframe scoring
        for (TrendAnalysis trend : mtf.trends.values()) {
            if (trend.signal == TradeSignal.BUY) bullishScore++;
            else if (trend.signal == TradeSignal.SELL) bearishScore++;
        }
        
        // Technical indicators scoring
        if (tech.rsi < 30) bullishScore += 2;
        else if (tech.rsi > 70) bearishScore += 2;
        
        if (tech.macd.signal == TradeSignal.BUY) bullishScore += 2;
        else if (tech.macd.signal == TradeSignal.SELL) bearishScore += 2;
        
        // Bollinger Bands
        if (tech.bb.signal == TradeSignal.BUY) bullishScore += 1;
        else if (tech.bb.signal == TradeSignal.SELL) bearishScore += 1;
        
        // Moving average crossover
        if (tech.ema12[tech.ema12.length - 1] > tech.ema26[tech.ema26.length - 1]) bullishScore += 1;
        else bearishScore += 1;
        
        // Sentiment analysis
        if (sentiment.priceChange24h > 5) bullishScore += 1;
        else if (sentiment.priceChange24h < -5) bearishScore += 1;
        
        if (sentiment.fundingRate < -0.01) bullishScore += 1;
        else if (sentiment.fundingRate > 0.01) bearishScore += 1;
        
        // Order book analysis
        if (orderBook.imbalance > 0.2) bullishScore += 1;
        else if (orderBook.imbalance < -0.2) bearishScore += 1;
        
        // Final decision
        if (bullishScore > bearishScore + 2) return TradeSignal.BUY;
        else if (bearishScore > bullishScore + 2) return TradeSignal.SELL;
        else return TradeSignal.HOLD;
    }
    
    private static boolean hasAdequateLiquidity(String pair) {
        try {
            JSONObject orderBook = getOrderBook(pair);
            OrderBookAnalysis analysis = analyzeOrderBook(pair);
            
            // Check if spread is reasonable (< 0.5%)
            if (analysis.spread > 0.5) {
                return false;
            }
            
            // Check if there's adequate depth
            if (analysis.bidDepth < 1000 || analysis.askDepth < 1000) {
                return false;
            }
            
            return true;
            
        } catch (Exception e) {
            logError("Error checking liquidity: " + e.getMessage());
            return false;
        }
    }
    
    private static double calculateOptimalQuantity(String pair, double price, double volatility) {
        try {
            JSONObject instrument = getInstrumentDetails(pair);
            if (instrument == null) return 0;
            
            double minQuantity = instrument.getDouble("min_quantity");
            double maxQuantity = instrument.getDouble("max_quantity");
            double quantityIncrement = instrument.getDouble("quantity_increment");
            double minNotional = instrument.getDouble("min_notional");
            
            // Base quantity from margin allocation
            double baseQuantity = MAX_MARGIN_PER_TRADE / (price * DEFAULT_LEVERAGE);
            
            // Adjust for volatility (reduce size for high volatility)
            double volatilityAdjustment = Math.max(0.5, 1.0 - volatility * 2);
            baseQuantity *= volatilityAdjustment;
            
            // Ensure minimum notional
            if (baseQuantity * price < minNotional) {
                baseQuantity = minNotional / price;
            }
            
            // Round to valid increment
            double quantity = Math.floor(baseQuantity / quantityIncrement) * quantityIncrement;
            
            // Ensure within bounds
            quantity = Math.max(minQuantity, Math.min(maxQuantity, quantity));
            
            return quantity;
            
        } catch (Exception e) {
            logError("Error calculating optimal quantity: " + e.getMessage());
            return 0;
        }
    }
    
    private static String placeAdvancedOrder(String pair, ComprehensiveAnalysis analysis, 
                                           double quantity, double currentPrice) {
        try {
            waitForRateLimit();
            
            // Determine order type based on market conditions
            String orderType = determineOrderType(analysis);
            
            JSONObject order = new JSONObject();
            order.put("side", analysis.signal == TradeSignal.BUY ? "buy" : "sell");
            order.put("pair", pair);
            order.put("order_type", orderType);
            order.put("total_quantity", quantity);
            order.put("leverage", DEFAULT_LEVERAGE);
            order.put("notification", "email_notification");
            order.put("position_margin_type", "isolated");
            order.put("margin_currency_short_name", "USDT");
            
            // Add price for limit orders
            if (orderType.contains("limit")) {
                double limitPrice = calculateOptimalLimitPrice(currentPrice, analysis);
                order.put("price", limitPrice);
            }
            
            // Add time in force for non-market orders
            if (!orderType.equals("market_order")) {
                order.put("time_in_force", "good_till_cancel");
            }
            
            JSONObject body = new JSONObject();
            body.put("timestamp", Instant.now().toEpochMilli());
            body.put("order", order);
            
            String response = sendAuthenticatedRequest(
                    BASE_URL + "/exchange/v1/derivatives/futures/orders/create",
                    body.toString()
            );
            
            JSONObject orderResponse = new JSONObject(response);
            
            if (orderResponse.has("id")) {
                return orderResponse.getString("id");
            } else {
                logError("Order placement failed: " + response);
                return null;
            }
            
        } catch (Exception e) {
            logError("Error placing advanced order: " + e.getMessage());
            return null;
        }
    }
    
    private static void setAdvancedTakeProfitStopLoss(String pair, String orderId, 
                                                    ComprehensiveAnalysis analysis, double entryPrice) {
        try {
            // Get position details
            String positionId = getPositionId(pair);
            if (positionId == null) {
                logError("Could not find position for " + pair);
                return;
            }
            
            // Calculate dynamic TP/SL based on volatility
            double atr = analysis.indicators.atr;
            TPSLLevels levels = calculateDynamicTPSL(entryPrice, atr, analysis.signal);
            
            // Round to tick size
            JSONObject instrument = getInstrumentDetails(pair);
            if (instrument != null) {
                double tickSize = instrument.getDouble("price_increment");
                levels.tpPrice = Math.round(levels.tpPrice / tickSize) * tickSize;
                levels.slPrice = Math.round(levels.slPrice / tickSize) * tickSize;
            }
            
            // Create TP/SL order
            JSONObject tpslPayload = new JSONObject();
            tpslPayload.put("timestamp", Instant.now().toEpochMilli());
            tpslPayload.put("id", positionId);
            
            // Take Profit
            JSONObject takeProfit = new JSONObject();
            takeProfit.put("stop_price", levels.tpPrice);
            takeProfit.put("limit_price", levels.tpPrice);
            takeProfit.put("order_type", "take_profit_market");
            
            // Stop Loss with trailing option
            JSONObject stopLoss = new JSONObject();
            stopLoss.put("stop_price", levels.slPrice);
            stopLoss.put("limit_price", levels.slPrice);
            stopLoss.put("order_type", "stop_market");
            
            tpslPayload.put("take_profit", takeProfit);
            tpslPayload.put("stop_loss", stopLoss);
            
            String response = sendAuthenticatedRequest(
                    BASE_URL + "/exchange/v1/derivatives/futures/positions/create_tpsl",
                    tpslPayload.toString()
            );
            
            JSONObject tpslResponse = new JSONObject(response);
            if (!tpslResponse.has("err_code_dcx")) {
                logInfo("Advanced TP/SL set for " + pair + " - TP: " + levels.tpPrice + ", SL: " + levels.slPrice);
            } else {
                logError("Failed to set TP/SL: " + response);
            }
            
        } catch (Exception e) {
            logError("Error setting advanced TP/SL: " + e.getMessage());
        }
    }
    
    // Helper methods for technical analysis
    private static double calculateRSI(double[] closes, int period) {
        if (closes.length < period + 1) return 50.0;
        
        double avgGain = 0;
        double avgLoss = 0;
        
        for (int i = 1; i <= period; i++) {
            double change = closes[i] - closes[i - 1];
            if (change > 0) {
                avgGain += change;
            } else {
                avgLoss += Math.abs(change);
            }
        }
        
        avgGain /= period;
        avgLoss /= period;
        
        for (int i = period + 1; i < closes.length; i++) {
            double change = closes[i] - closes[i - 1];
            if (change > 0) {
                avgGain = (avgGain * (period - 1) + change) / period;
                avgLoss = (avgLoss * (period - 1)) / period;
            } else {
                avgLoss = (avgLoss * (period - 1) + Math.abs(change)) / period;
                avgGain = (avgGain * (period - 1)) / period;
            }
        }
        
        if (avgLoss == 0) return 100.0;
        double rs = avgGain / avgLoss;
        return 100.0 - (100.0 / (1.0 + rs));
    }
    
    private static MACDResult calculateMACD(double[] closes) {
        double[] ema12 = calculateEMA(closes, 12);
        double[] ema26 = calculateEMA(closes, 26);
        
        double[] macdLine = new double[closes.length];
        for (int i = 0; i < closes.length; i++) {
            macdLine[i] = ema12[i] - ema26[i];
        }
        
        double[] signalLine = calculateEMA(macdLine, 9);
        double[] histogram = new double[closes.length];
        
        for (int i = 0; i < closes.length; i++) {
            histogram[i] = macdLine[i] - signalLine[i];
        }
        
        // Determine signal
        TradeSignal signal = TradeSignal.HOLD;
        if (histogram.length > 1) {
            if (histogram[histogram.length - 1] > 0 && histogram[histogram.length - 2] <= 0) {
                signal = TradeSignal.BUY;
            } else if (histogram[histogram.length - 1] < 0 && histogram[histogram.length - 2] >= 0) {
                signal = TradeSignal.SELL;
            }
        }
        
        return new MACDResult(macdLine, signalLine, histogram, signal);
    }
    
    private static BollingerBands calculateBollingerBands(double[] closes, int period, double stdDev) {
        double[] sma = calculateSMA(closes, period);
        double[] upperBand = new double[closes.length];
        double[] lowerBand = new double[closes.length];
        
        for (int i = period - 1; i < closes.length; i++) {
            double sum = 0;
            for (int j = i - period + 1; j <= i; j++) {
                sum += Math.pow(closes[j] - sma[i], 2);
            }
            double std = Math.sqrt(sum / period);
            upperBand[i] = sma[i] + (stdDev * std);
            lowerBand[i] = sma[i] - (stdDev * std);
        }
        
        // Determine signal
        TradeSignal signal = TradeSignal.HOLD;
        if (closes.length > 0) {
            double currentPrice = closes[closes.length - 1];
            if (currentPrice <= lowerBand[closes.length - 1]) {
                signal = TradeSignal.BUY;
            } else if (currentPrice >= upperBand[closes.length - 1]) {
                signal = TradeSignal.SELL;
            }
        }
        
        return new BollingerBands(upperBand, lowerBand, sma, signal);
    }
    
    private static double[] calculateSMA(double[] prices, int period) {
        double[] sma = new double[prices.length];
        for (int i = period - 1; i < prices.length; i++) {
            double sum = 0;
            for (int j = i - period + 1; j <= i; j++) {
                sum += prices[j];
            }
            sma[i] = sum / period;
        }
        return sma;
    }
    
    private static double[] calculateEMA(double[] prices, int period) {
        double[] ema = new double[prices.length];
        double multiplier = 2.0 / (period + 1);
        
        // First EMA value is SMA
        double sum = 0;
        for (int i = 0; i < period; i++) {
            sum += prices[i];
        }
        ema[period - 1] = sum / period;
        
        // Calculate EMA for remaining values
        for (int i = period; i < prices.length; i++) {
            ema[i] = (prices[i] * multiplier) + (ema[i - 1] * (1 - multiplier));
        }
        
        return ema;
    }
    
    private static double calculateATR(double[] highs, double[] lows, double[] closes, int period) {
        if (highs.length < period + 1) return 0.0;
        
        double[] trueRanges = new double[highs.length - 1];
        for (int i = 1; i < highs.length; i++) {
            double tr1 = highs[i] - lows[i];
            double tr2 = Math.abs(highs[i] - closes[i - 1]);
            double tr3 = Math.abs(lows[i] - closes[i - 1]);
            trueRanges[i - 1] = Math.max(tr1, Math.max(tr2, tr3));
        }
        
        // Calculate ATR as SMA of true ranges
        double sum = 0;
        for (int i = Math.max(0, trueRanges.length - period); i < trueRanges.length; i++) {
            sum += trueRanges[i];
        }
        
        return sum / Math.min(period, trueRanges.length);
    }
    
    private static TPSLLevels calculateDynamicTPSL(double entryPrice, double atr, TradeSignal signal) {
        double tpPrice, slPrice;
        
        // Use ATR for dynamic levels
        double atrMultiplier = VOLATILITY_MULTIPLIER;
        double tpDistance = Math.max(atr * atrMultiplier, entryPrice * BASE_TP_PERCENTAGE);
        double slDistance = Math.max(atr * atrMultiplier * 2, entryPrice * BASE_SL_PERCENTAGE);
        
        if (signal == TradeSignal.BUY) {
            tpPrice = entryPrice + tpDistance;
            slPrice = entryPrice - slDistance;
        } else {
            tpPrice = entryPrice - tpDistance;
            slPrice = entryPrice + slDistance;
        }
        
        return new TPSLLevels(tpPrice, slPrice);
    }
    
    // Data extraction methods
    private static double[] extractCloses(JSONArray candles) {
        double[] closes = new double[candles.length()];
        for (int i = 0; i < candles.length(); i++) {
            closes[i] = candles.getJSONObject(i).getDouble("close");
        }
        return closes;
    }
    
    private static double[] extractHighs(JSONArray candles) {
        double[] highs = new double[candles.length()];
        for (int i = 0; i < candles.length(); i++) {
            highs[i] = candles.getJSONObject(i).getDouble("high");
        }
        return highs;
    }
    
    private static double[] extractLows(JSONArray candles) {
        double[] lows = new double[candles.length()];
        for (int i = 0; i < candles.length(); i++) {
            lows[i] = candles.getJSONObject(i).getDouble("low");
        }
        return lows;
    }
    
    private static double[] extractVolumes(JSONArray candles) {
        double[] volumes = new double[candles.length()];
        for (int i = 0; i < candles.length(); i++) {
            volumes[i] = candles.getJSONObject(i).getDouble("volume");
        }
        return volumes;
    }
    
    // Enhanced API methods
    private static JSONArray getCandlestickData(String pair, String resolution, int periods) {
        try {
            waitForRateLimit();
            
            long endTime = Instant.now().toEpochMilli() / 1000;
            long startTime = endTime - (periods * getResolutionInSeconds(resolution));
            
            String url = PUBLIC_API_URL + "/market_data/candlesticks" +
                    "?pair=" + pair +
                    "&from=" + startTime +
                    "&to=" + endTime +
                    "&resolution=" + resolution +
                    "&pcode=f";
            
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            
            if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                String response = readResponse(conn.getInputStream());
                JSONObject jsonResponse = new JSONObject(response);
                
                if ("ok".equals(jsonResponse.optString("s"))) {
                    return jsonResponse.getJSONArray("data");
                }
            }
            
        } catch (Exception e) {
            logError("Error fetching candlestick data: " + e.getMessage());
        }
        return null;
    }
    
    private static JSONObject getOrderBook(String pair) {
        try {
            waitForRateLimit();
            
            String url = PUBLIC_API_URL + "/market_data/v3/orderbook/" + pair + "-futures/50";
            
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            
            if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                String response = readResponse(conn.getInputStream());
                return new JSONObject(response);
            }
            
        } catch (Exception e) {
            logError("Error fetching order book: " + e.getMessage());
        }
        return new JSONObject();
    }
    
    private static JSONObject getMarketStats(String pair) {
        try {
            waitForRateLimit();
            
            String url = BASE_URL + "/exchange/ticker";
            
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            
            if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                String response = readResponse(conn.getInputStream());
                JSONArray tickers = new JSONArray(response);
                
                for (int i = 0; i < tickers.length(); i++) {
                    JSONObject ticker = tickers.getJSONObject(i);
                    if (pair.equals(ticker.optString("market"))) {
                        return ticker;
                    }
                }
            }
            
        } catch (Exception e) {
            logError("Error fetching market stats: " + e.getMessage());
        }
        return new JSONObject();
    }
    
    private static double getCurrentPrice(String pair) {
        try {
            String cacheKey = pair + "_price";
            if (priceCache.containsKey(cacheKey) && 
                System.currentTimeMillis() - lastCacheUpdate < 30000) {
                return priceCache.get(cacheKey);
            }
            
            waitForRateLimit();
            
            String url = PUBLIC_API_URL + "/market_data/trade_history?pair=" + pair + "&limit=1";
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            
            if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                String response = readResponse(conn.getInputStream());
                JSONArray trades = new JSONArray(response);
                
                if (trades.length() > 0) {
                    double price = trades.getJSONObject(0).getDouble("p");
                    priceCache.put(cacheKey, price);
                    return price;
                }
            }
            
        } catch (Exception e) {
            logError("Error getting current price: " + e.getMessage());
        }
        return 0;
    }
    
    private static Set<String> getActivePositions() {
        Set<String> activePositions = new HashSet<>();
        
        try {
            waitForRateLimit();
            
            JSONObject body = new JSONObject();
            body.put("timestamp", Instant.now().toEpochMilli());
            body.put("page", "1");
            body.put("size", "100");
            body.put("margin_currency_short_name", new String[]{"USDT", "INR"});
            
            String response = sendAuthenticatedRequest(
                    BASE_URL + "/exchange/v1/derivatives/futures/positions",
                    body.toString()
            );
            
            JSONArray positions = new JSONArray(response);
            
            for (int i = 0; i < positions.length(); i++) {
                JSONObject position = positions.getJSONObject(i);
                String pair = position.optString("pair", "");
                
                boolean isActive = position.optDouble("active_pos", 0) != 0 ||
                                 position.optDouble("locked_margin", 0) > 0;
                
                if (isActive && !pair.isEmpty()) {
                    activePositions.add(pair);
                }
            }
            
        } catch (Exception e) {
            logError("Error fetching active positions: " + e.getMessage());
        }
        
        return activePositions;
    }
    
    // Background tasks
    private static void startBackgroundTasks() {
        // Schedule cache refresh
        scheduler.scheduleAtFixedRate(() -> {
            try {
                refreshCaches();
            } catch (Exception e) {
                logError("Error refreshing caches: " + e.getMessage());
            }
        }, 0, 5, TimeUnit.MINUTES);
        
        // Schedule position monitoring
        scheduler.scheduleAtFixedRate(() -> {
            try {
                monitorPositions();
            } catch (Exception e) {
                logError("Error monitoring positions: " + e.getMessage());
            }
        }, 0, 1, TimeUnit.MINUTES);
    }
    
    private static void refreshCaches() {
        // Refresh instrument cache
        initializeInstrumentCache();
        
        // Clear price cache
        priceCache.clear();
        
        logInfo("Caches refreshed successfully");
    }
    
    private static void monitorPositions() {
        Set<String> activePositions = getActivePositions();
        logInfo("Monitoring " + activePositions.size() + " active positions");
        
        // Additional position monitoring logic can be added here
    }
    
    // Utility methods
    private static void waitForRateLimit() {
        long currentTime = System.currentTimeMillis();
        
        while (!requestTimes.isEmpty() && 
               currentTime - requestTimes.peek() > RATE_LIMIT_WINDOW_MS) {
            requestTimes.poll();
        }
        
        if (requestTimes.size() >= MAX_REQUESTS_PER_MINUTE) {
            try {
                long waitTime = RATE_LIMIT_WINDOW_MS - (currentTime - requestTimes.peek());
                if (waitTime > 0) {
                    Thread.sleep(waitTime + 100);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        
        requestTimes.offer(currentTime);
    }
    
    private static String sendAuthenticatedRequest(String endpoint, String jsonBody) throws Exception {
        String signature = generateHmacSHA256(API_SECRET, jsonBody);
        
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("X-AUTH-APIKEY", API_KEY);
        conn.setRequestProperty("X-AUTH-SIGNATURE", signature);
        conn.setDoOutput(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        
        try (OutputStream os = conn.getOutputStream()) {
            os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
        }
        
        if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
            String errorResponse = readResponse(conn.getErrorStream());
            throw new RuntimeException("HTTP " + conn.getResponseCode() + ": " + errorResponse);
        }
        
        return readResponse(conn.getInputStream());
    }
    
    private static String readResponse(InputStream is) throws IOException {
        if (is == null) return "";
        
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }
    
    private static String generateHmacSHA256(String secret, String payload) {
        try {
            Mac sha256_HMAC = Mac.getInstance("HmacSHA256");
            SecretKeySpec secret_key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            sha256_HMAC.init(secret_key);
            
            byte[] bytes = sha256_HMAC.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte b : bytes) {
                result.append(String.format("%02x", b));
            }
            return result.toString();
            
        } catch (Exception e) {
            throw new RuntimeException("Error generating HMAC signature", e);
        }
    }
    
    // Initialization methods
    private static void initializeInstrumentCache() {
        try {
            waitForRateLimit();
            
            String response = sendPublicRequest(
                    BASE_URL + "/exchange/v1/derivatives/futures/data/active_instruments"
            );
            
            JSONArray instruments = new JSONArray(response);
            
            for (int i = 0; i < instruments.length(); i++) {
                String pair = instruments.getString(i);
                if (Arrays.asList(TRADING_PAIRS).contains(pair)) {
                    try {
                        String instrumentResponse = sendPublicRequest(
                                BASE_URL + "/exchange/v1/derivatives/futures/data/instrument" +
                                "?pair=" + pair + "&margin_currency_short_name=USDT"
                        );
                        
                        JSONObject instrumentData = new JSONObject(instrumentResponse);
                        instrumentCache.put(pair, instrumentData.getJSONObject("instrument"));
                        
                    } catch (Exception e) {
                        logError("Error caching instrument " + pair + ": " + e.getMessage());
                    }
                }
            }
            
            lastCacheUpdate = System.currentTimeMillis();
            logInfo("Instrument cache initialized with " + instrumentCache.size() + " instruments");
            
        } catch (Exception e) {
            logError("Error initializing instrument cache: " + e.getMessage());
        }
    }
    
    private static String sendPublicRequest(String endpoint) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        
        if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
            throw new RuntimeException("HTTP " + conn.getResponseCode());
        }
        
        return readResponse(conn.getInputStream());
    }
    
    private static JSONObject getInstrumentDetails(String pair) {
        if (System.currentTimeMillis() - lastCacheUpdate > CACHE_TTL_MS) {
            initializeInstrumentCache();
        }
        
        return instrumentCache.get(pair);
    }
    
    private static String getPositionId(String pair) {
        try {
            waitForRateLimit();
            
            JSONObject body = new JSONObject();
            body.put("timestamp", Instant.now().toEpochMilli());
            body.put("page", "1");
            body.put("size", "10");
            body.put("margin_currency_short_name", new String[]{"USDT", "INR"});
            
            String response = sendAuthenticatedRequest(
                    BASE_URL + "/exchange/v1/derivatives/futures/positions",
                    body.toString()
            );
            
            JSONArray positions = new JSONArray(response);
            
            for (int i = 0; i < positions.length(); i++) {
                JSONObject position = positions.getJSONObject(i);
                if (pair.equals(position.optString("pair", ""))) {
                    return position.optString("id", null);
                }
            }
            
        } catch (Exception e) {
            logError("Error getting position ID: " + e.getMessage());
        }
        
        return null;
    }
    
    // Helper methods for enhanced functionality
    private static long getResolutionInSeconds(String resolution) {
        switch (resolution) {
            case "1": return 60;
            case "5": return 300;
            case "15": return 900;
            case "60": return 3600;
            case "1D": return 86400;
            default: return 300;
        }
    }
    
    private static String determineOrderType(ComprehensiveAnalysis analysis) {
        // Use limit orders for better execution in normal conditions
        if (analysis.indicators.atr > 0 && analysis.sentiment.volume24h > 1000) {
            return "limit_order";
        }
        // Use market orders for high volatility or low volume
        return "market_order";
    }
    
    private static double calculateOptimalLimitPrice(double currentPrice, ComprehensiveAnalysis analysis) {
        double spread = analysis.indicators.atr * 0.1; // 10% of ATR
        
        if (analysis.signal == TradeSignal.BUY) {
            return currentPrice - spread; // Buy below current price
        } else {
            return currentPrice + spread; // Sell above current price
        }
    }
    
    private static TrendAnalysis analyzeTrend(JSONArray candles) {
        if (candles == null || candles.length() < 2) {
            return new TrendAnalysis(TradeSignal.HOLD, 0);
        }
        
        double firstClose = candles.getJSONObject(0).getDouble("close");
        double lastClose = candles.getJSONObject(candles.length() - 1).getDouble("close");
        double priceChange = (lastClose - firstClose) / firstClose;
        
        TradeSignal signal = TradeSignal.HOLD;
        if (priceChange > TREND_THRESHOLD) {
            signal = TradeSignal.BUY;
        } else if (priceChange < -TREND_THRESHOLD) {
            signal = TradeSignal.SELL;
        }
        
        return new TrendAnalysis(signal, priceChange);
    }
    
    private static double calculateVolatility(String pair) {
        try {
            JSONArray candles = getCandlestickData(pair, "5", 20);
            if (candles == null || candles.length() < 2) {
                return 0.02; // Default volatility
            }
            
            double[] closes = extractCloses(candles);
            double[] returns = new double[closes.length - 1];
            
            for (int i = 1; i < closes.length; i++) {
                returns[i - 1] = Math.log(closes[i] / closes[i - 1]);
            }
            
            // Calculate standard deviation
            double mean = Arrays.stream(returns).average().orElse(0);
            double variance = Arrays.stream(returns)
                    .map(r -> Math.pow(r - mean, 2))
                    .average()
                    .orElse(0);
            
            return Math.sqrt(variance);
            
        } catch (Exception e) {
            logError("Error calculating volatility: " + e.getMessage());
            return 0.02;
        }
    }
    
    private static double calculateAverageVolume(double[] volumes, int period) {
        if (volumes.length < period) {
            return Arrays.stream(volumes).average().orElse(0);
        }
        
        double sum = 0;
        for (int i = volumes.length - period; i < volumes.length; i++) {
            sum += volumes[i];
        }
        
        return sum / period;
    }
    
    private static double getFundingRate(String pair) {
        // Placeholder - implement funding rate API call
        return 0.0;
    }
    
    private static double getLongShortRatio(String pair) {
        // Placeholder - implement long/short ratio API call
        return 1.0;
    }
    
    private static double getHighestBid(JSONObject bids) {
        return bids.keys().hasNext() ? 
                bids.keys().next().toString().chars()
                        .mapToDouble(c -> Double.parseDouble(bids.keys().next().toString()))
                        .max().orElse(0) : 0;
    }
    
    private static double getLowestAsk(JSONObject asks) {
        return asks.keys().hasNext() ? 
                asks.keys().next().toString().chars()
                        .mapToDouble(c -> Double.parseDouble(asks.keys().next().toString()))
                        .min().orElse(0) : 0;
    }
    
    private static double calculateDepth(JSONObject orders, double basePrice, double percentage) {
        double totalDepth = 0;
        double priceLimit = basePrice * (1 + percentage);
        
        for (String priceStr : orders.keySet()) {
            double price = Double.parseDouble(priceStr);
            if (price <= priceLimit) {
                totalDepth += orders.getDouble(priceStr) * price;
            }
        }
        
        return totalDepth;
    }
    
    // Logging utilities
    private static void logInfo(String message) {
        System.out.println("[" + getCurrentTimestamp() + "] INFO: " + message);
    }
    
    private static void logError(String message) {
        System.err.println("[" + getCurrentTimestamp() + "] ERROR: " + message);
    }
    
    private static void logWarning(String message) {
        System.out.println("[" + getCurrentTimestamp() + "] WARNING: " + message);
    }
    
    private static String getCurrentTimestamp() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
    
    private static void cleanup() {
        try {
            executor.shutdown();
            scheduler.shutdown();
            
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
            
            if (!scheduler.awaitTermination(60, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
            
            logInfo("System shutdown completed");
            
        } catch (InterruptedException e) {
            executor.shutdownNow();
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    
    // Data classes
    private static class ComprehensiveAnalysis {
        final TradeSignal signal;
        final double volatility;
        final TechnicalIndicators indicators;
        final MarketSentiment sentiment;
        
        ComprehensiveAnalysis(TradeSignal signal, double volatility, 
                            TechnicalIndicators indicators, MarketSentiment sentiment) {
            this.signal = signal;
            this.volatility = volatility;
            this.indicators = indicators;
            this.sentiment = sentiment;
        }
    }
    
    private static class MultiTimeframeAnalysis {
        final Map<String, TrendAnalysis> trends;
        
        MultiTimeframeAnalysis(Map<String, TrendAnalysis> trends) {
            this.trends = trends;
        }
    }
    
    private static class TechnicalIndicators {
        final double rsi;
        final MACDResult macd;
        final BollingerBands bb;
        final double[] sma20;
        final double[] ema12;
        final double[] ema26;
        final double atr;
        final double volume;
        
        TechnicalIndicators() {
            this(50, new MACDResult(new double[0], new double[0], new double[0], TradeSignal.HOLD),
                 new BollingerBands(new double[0], new double[0], new double[0], TradeSignal.HOLD),
                 new double[0], new double[0], new double[0], 0, 0);
        }
        
        TechnicalIndicators(double rsi, MACDResult macd, BollingerBands bb, 
                          double[] sma20, double[] ema12, double[] ema26, double atr, double volume) {
            this.rsi = rsi;
            this.macd = macd;
            this.bb = bb;
            this.sma20 = sma20;
            this.ema12 = ema12;
            this.ema26 = ema26;
            this.atr = atr;
            this.volume = volume;
        }
    }
    
    private static class MarketSentiment {
        final double priceChange24h;
        final double volume24h;
        final double fundingRate;
        final double longShortRatio;
        
        MarketSentiment(double priceChange24h, double volume24h, double fundingRate, double longShortRatio) {
            this.priceChange24h = priceChange24h;
            this.volume24h = volume24h;
            this.fundingRate = fundingRate;
            this.longShortRatio = longShortRatio;
        }
    }
    
    private static class OrderBookAnalysis {
        final double spread;
        final double bidDepth;
        final double askDepth;
        final double imbalance;
        
        OrderBookAnalysis(double spread, double bidDepth, double askDepth, double imbalance) {
            this.spread = spread;
            this.bidDepth = bidDepth;
            this.askDepth = askDepth;
            this.imbalance = imbalance;
        }
    }
    
    private static class TrendAnalysis {
        final TradeSignal signal;
        final double priceChange;
        
        TrendAnalysis(TradeSignal signal, double priceChange) {
            this.signal = signal;
            this.priceChange = priceChange;
        }
    }
    
    private static class MACDResult {
        final double[] macdLine;
        final double[] signalLine;
        final double[] histogram;
        final TradeSignal signal;
        
        MACDResult(double[] macdLine, double[] signalLine, double[] histogram, TradeSignal signal) {
            this.macdLine = macdLine;
            this.signalLine = signalLine;
            this.histogram = histogram;
            this.signal = signal;
        }
    }
    
    private static class BollingerBands {
        final double[] upperBand;
        final double[] lowerBand;
        final double[] middleBand;
        final TradeSignal signal;
        
        BollingerBands(double[] upperBand, double[] lowerBand, double[] middleBand, TradeSignal signal) {
            this.upperBand = upperBand;
            this.lowerBand = lowerBand;
            this.middleBand = middleBand;
            this.signal = signal;
        }
    }
    
    private static class TPSLLevels {
        final double tpPrice;
        final double slPrice;
        
        TPSLLevels(double tpPrice, double slPrice) {
            this.tpPrice = tpPrice;
            this.slPrice = slPrice;
        }
    }
    
    private static class MarketData {
        final double price;
        final double volume;
        final long timestamp;
        
        MarketData(double price, double volume, long timestamp) {
            this.price = price;
            this.volume = volume;
            this.timestamp = timestamp;
        }
    }
    
    private enum TradeSignal {
        BUY, SELL, HOLD
    }
}





-----------------------------------------------------------------------------------------------------------------------------------------






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
