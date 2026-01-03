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
    private static final double ACCOUNT_BALANCE = 10000.0;
    private static final double RISK_PER_TRADE = 0.02; // 2% risk per trade
    private static final int MAX_CONCURRENT_TRADES = 15;
    private static final int MAX_NEW_TRADES_PER_RUN = 5;
    
    // Strategy Configuration
    private static final double TP_PERCENTAGE = 0.05; // 5% take profit
    private static final double SL_PERCENTAGE = 0.03; // 3% stop loss
    private static final int DEFAULT_LEVERAGE = 5;
    
    // API Cache Configuration
    private static final long TICK_SIZE_CACHE_TTL_MS = 3600000;
    
    // Caches
    private static final Map<String, JSONObject> instrumentDetailsCache = new ConcurrentHashMap<>();
    private static long lastInstrumentUpdateTime = 0;
    
    // Trade tracking
    private static final Set<String> activePairs = Collections.synchronizedSet(new HashSet<>());
    
    // Trading pairs - ONLY MAJOR COINS with known liquidity
    private static final String[] COIN_SYMBOLS = {
      "BTC", "ETH", "VOXEL", "SOL", "NKN", "MAGIC", "XRP", "1000PEPE", "FARTCOIN", "DOGE",
"TAO", "SUI", "TRUMP", "PERP", "OM","ADA", "BNB", "LINK", "PEOPLE", "GMT",
"FET", "MUBARAK", "WIF", "LAYER", "AVAX", "HIGH", "ALPACA", "FLM", "ENA", "BSW",
 "FIL", "DOT", "LTC", "ARK", "ENJ", "EOS", "USUAL", "TRX", "RENDER", "CRV",
"BCH", "OGN", "ONDO", "1000SHIB", "BIO", "AAVE", "ORCA", "NEAR", "PNUT", "T",
 "POPCAT", "FUN", "VTHO", "WLD", "ALCH", "GAS", "XAI", "GALA", "TIA", "MBOX",
 "APT", "ORDI", "HBAR", "OP", "INJ", "BEL", "JASMY", "RED", "KAITO", "PARTI",
 "ARB", "BIGTIME", "AI16Z", "1000SATS", "NEIRO", "ETC", "JUP", "BOME", "UNI", "TON",
 "1000BONK", "ACH", "XLM", "GOAT", "SAND", "ATOM", "LEVER", "S", "CAKE", "NOT",
 "LOKA", "ARC", "VINE", "PENDLE", "LDO", "SEI", "RAYSOL", "APE", "RARE",
"WAXP", "GPS", "IP", "COTI", "AVAAI", "KOMA", "HFT", "ARKM", "ANIME", "ACT",
 "ALGO", "VIRTUAL", "MAVIA", "ALICE", "MANTA", "ZRO", "AGLD", "STX", "API3", "PIXEL",
"MELANIA", "NEO", "IMX", "1000WHY", "MANA", "ACE", "SWARMS", "MKR", "AUCTION", "ICP",
"PORTAL", "THETA", "CHESS", "ZEREBRO", "1000FLOKI", "PENGU", "STRK", "CATI", "TRB", "SAGA",
 "NIL", "TURBO", "AIXBT", "W", "PYTH", "LISTA", "CHILLGUY", "GRIFFAIN", "REZ", "IO",
 "UXLINK", "SHELL", "BTCDOM", "POL", "GRT", "BRETT", "DYDX", "JTO", "MOODENG", "ETHFI",
"OMNI", "DOGS", "EIGEN", "ENS", "XMR", "D", "SOLV", "VET", "RUNE", "MEW",
 "AXS", "XCN", "SXP", "MASK", "BMT", "BANANA", "NFP", "XTZ", "FORTH", "ALPHA",
"REI", "AR", "YGG", "PAXG", "SPX", "TRU", "ID", "GTC", "CHZ", "BLUR",
"GRASS", "KAVA", "SPELL", "RSR", "FIDA", "MORPHO", "VANA", "RPL", "ANKR", "TLM",
"CFX", "HIPPO", "TST", "ZEN", "ME", "AI", "MOVR", "GLM", "ZIL", "1000RATS",
"HOOK", "ALT", "ZK", "COW", "SUSHI", "MLN", "SANTOS", "1MBABYDOGE", "SNX",
 "STORJ", "BEAMX", "WOO", "B3", "AEVO", "CTSI", "1000LUNC", "OXT", "ILV", "IOTA",
"QTUM", "EPIC", "NEIROETH", "THE", "EDU", "ZEC", "AERO", "SKL", "ARPA", "BAN",
 "COMP", "CHR", "NMR", "ZETA", "LUMIA", "COOKIE", "PHB", "MINA", "1000CHEEMS", "1000CAT",
"GHST", "KAS", "SUPER", "ROSE", "IOTX", "DYM", "EGLD", "SONIC", "RDNT", "LPT",
"LUNA2", "PLUME", "XVG", "MYRO", "LQTY", "USTC", "C98", "SCR", "BB", "STEEM",
 "ONE", "FLOW", "QNT", "SSV", "POWR", "DEXE", "CGPT", "VANRY", "POLYX", "ZRX",
 "YFI", "TNSR", "GMX", "SYS", "1INCH", "CELO", "METIS", "1000X", "HEI", "ONT",
 "KSM", "KDA", "IOST", "BAT", "CETUS", "DF", "LRC", "HIVE", "DEGEN",
"MTL", "SAFE", "CELR", "AVA", "CKB", "RIF", "FIO", "1000000MOG", "KNC", "ICX",
"CYBER", "RONIN", "ONG", "VVV", "FXS", "MAV", "DEGO", "DASH", "ASTR", "PHA",
"AXL", "BICO", "BAND", "SCRT", "HOT", "TOKEN", "STG", "PONKE", "DODOX", "DUSK",
"SYN", "RVN", "UMA", "PIPPIN", "DENT", "PROM", "FLUX", "VELODROME", "SWELL", "MOCA",
"ATA", "KAIA", "ATH", "XVS", "G", "LSK", "SUN", "NTRN", "RLC", "JOE",
"1000XEC", "VIC", "SFP", "TWT", "QUICK", "BSV", "DIA", "BNT", "ACX", "COS",
"ETHW", "DRIFT", "AKT", "KMNO", "SLERF", "DEFI", "USDC"
    };
    
    private static final String[] COINS_TO_TRADE = Stream.of(COIN_SYMBOLS)
            .map(symbol -> "B-" + symbol + "_USDT")
            .toArray(String[]::new);
    
    private static final Set<String> INTEGER_QUANTITY_PAIRS = Stream.of(COIN_SYMBOLS)
            .flatMap(symbol -> Stream.of("B-" + symbol + "_USDT", symbol + "_USDT"))
            .collect(Collectors.toCollection(HashSet::new));

    public static void main(String[] args) {
        System.out.println("🚀 Working Futures Trading Bot Started");
        System.out.println("========================================");
        System.out.println("Account Balance: $" + ACCOUNT_BALANCE);
        System.out.println("Risk per trade: " + (RISK_PER_TRADE * 100) + "%");
        System.out.println("Max concurrent trades: " + MAX_CONCURRENT_TRADES);
        System.out.println("Default leverage: " + DEFAULT_LEVERAGE + "x");
        System.out.println("Trading pairs: " + COINS_TO_TRADE.length);
        System.out.println("========================================");
        
        try {
            // Initialize caches
            initializeInstrumentDetails();
            
            // Get current active positions
            Set<String> currentActivePairs = getActivePositions();
            activePairs.addAll(currentActivePairs);
            
            System.out.println("\n📊 Current Active Positions: " + activePairs.size());
            if (!activePairs.isEmpty()) {
                System.out.println("Existing positions:");
                for (String pair : activePairs) {
                    System.out.println("  - " + pair);
                }
            }
            
            // Check if we can add more positions
            if (activePairs.size() >= MAX_CONCURRENT_TRADES) {
                System.out.println("\n⚠️ Max concurrent trades reached (" + MAX_CONCURRENT_TRADES + ")");
                System.out.println("No new trades will be placed.");
                return;
            }
            
            int availableSlots = MAX_CONCURRENT_TRADES - activePairs.size();
            int maxNewTrades = Math.min(availableSlots, MAX_NEW_TRADES_PER_RUN);
            
            System.out.println("\n🎯 Available slots: " + availableSlots);
            System.out.println("Will attempt up to " + maxNewTrades + " new trades");
            
            // Trade execution
            int tradesPlaced = 0;
            for (String pair : COINS_TO_TRADE) {
                try {
                    if (tradesPlaced >= maxNewTrades) {
                        System.out.println("\n✅ Reached max new trades limit for this run");
                        break;
                    }
                    
                    if (activePairs.contains(pair)) {
                        System.out.println("\n⏭ Skipping " + pair + " - Active position exists");
                        continue;
                    }
                    
                    System.out.println("\n" + "=".repeat(60));
                    System.out.println("📈 Analyzing: " + pair);
                    System.out.println("=".repeat(60));
                    
                    // Get current price first
                    double currentPrice = getLastPrice(pair);
                    if (currentPrice <= 0) {
                        System.out.println("❌ Cannot get price for " + pair);
                        continue;
                    }
                    
                    System.out.println("Current price: $" + currentPrice);
                    
                    // SIMPLE STRATEGY: Alternate between buy/sell or use simple indicator
                    String side = getSimpleSignal(pair, currentPrice);
                    if (side == null) {
                        System.out.println("⏭ No clear signal");
                        continue;
                    }
                    
                    System.out.println("Signal: " + side.toUpperCase());
                    
                    // Calculate TP/SL
                    double tpPrice, slPrice;
                    if ("buy".equals(side)) {
                        tpPrice = currentPrice * (1 + TP_PERCENTAGE);
                        slPrice = currentPrice * (1 - SL_PERCENTAGE);
                    } else {
                        tpPrice = currentPrice * (1 - TP_PERCENTAGE);
                        slPrice = currentPrice * (1 + SL_PERCENTAGE);
                    }
                    
                    // Round to tick size
                    double tickSize = getTickSizeForPair(pair);
                    tpPrice = Math.round(tpPrice / tickSize) * tickSize;
                    slPrice = Math.round(slPrice / tickSize) * tickSize;
                    
                    // Calculate quantity
                    double quantity = calculateSimpleQuantity(currentPrice, slPrice, pair);
                    if (quantity <= 0) {
                        System.out.println("❌ Invalid quantity");
                        continue;
                    }
                    
                    System.out.println("\n📋 Trade Summary:");
                    System.out.println("  Side: " + side.toUpperCase());
                    System.out.println("  Entry: $" + currentPrice);
                    System.out.println("  TP: $" + String.format("%.4f", tpPrice));
                    System.out.println("  SL: $" + String.format("%.4f", slPrice));
                    System.out.println("  Quantity: " + quantity);
                    System.out.println("  Leverage: " + DEFAULT_LEVERAGE + "x");
                    System.out.println("  Risk: $" + String.format("%.2f", 
                        Math.abs(currentPrice - slPrice) * quantity));
                    
                    // Ask for confirmation (optional - remove for full automation)
                    if (!confirmTrade(pair, side)) {
                        System.out.println("⏭ Trade cancelled");
                        continue;
                    }
                    
                    // Execute trade
                    boolean success = executeTrade(pair, side, quantity, tpPrice, slPrice);
                    
                    if (success) {
                        tradesPlaced++;
                        activePairs.add(pair);
                        
                        // Small delay between trades
                        TimeUnit.SECONDS.sleep(2);
                    }
                    
                } catch (Exception e) {
                    System.err.println("❌ Error processing " + pair + ": " + e.getMessage());
                }
            }
            
            System.out.println("\n" + "=".repeat(60));
            System.out.println("✅ Trading Session Complete");
            System.out.println("New trades placed: " + tradesPlaced);
            System.out.println("Total active positions: " + activePairs.size());
            
        } catch (Exception e) {
            System.err.println("❌ Fatal error: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    // ==================== SIMPLE SIGNAL GENERATION ====================
    
    private static String getSimpleSignal(String pair, double currentPrice) {
        try {
            // Method 1: Simple momentum from recent trades
            String tradesUrl = PUBLIC_API_URL + "/market_data/trade_history?pair=" + 
                              getSpotPair(pair) + "&limit=10";
            
            HttpURLConnection conn = (HttpURLConnection) new URL(tradesUrl).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            
            if (conn.getResponseCode() == 200) {
                String response = readAllLines(conn.getInputStream());
                if (response.startsWith("[")) {
                    JSONArray trades = new JSONArray(response);
                    if (trades.length() >= 5) {
                        double oldestPrice = trades.getJSONObject(0).getDouble("p");
                        double newestPrice = trades.getJSONObject(trades.length() - 1).getDouble("p");
                        double change = (newestPrice - oldestPrice) / oldestPrice;
                        
                        System.out.println("Recent price change: " + String.format("%+.4f%%", change * 100));
                        
                        if (change > 0.005) { // 0.5% uptrend
                            return "buy";
                        } else if (change < -0.005) { // 0.5% downtrend
                            return "sell";
                        }
                    }
                }
            }
            
            // Method 2: Random bias (for testing)
            // Remove this in production
            double random = Math.random();
            if (random > 0.6) {
                return "buy";
            } else if (random < 0.4) {
                return "sell";
            }
            
            return null;
            
        } catch (Exception e) {
            System.err.println("❌ Signal generation error: " + e.getMessage());
            return null;
        }
    }
    
    private static double calculateSimpleQuantity(double entryPrice, double stopLossPrice, String pair) {
        try {
            double stopLossDistance = Math.abs(entryPrice - stopLossPrice);
            double riskPerUnit = stopLossDistance / entryPrice;
            
            if (riskPerUnit <= 0) return 0;
            
            double riskAmount = ACCOUNT_BALANCE * RISK_PER_TRADE;
            double positionValue = riskAmount / riskPerUnit;
            
            // Apply leverage
            positionValue = Math.min(positionValue, ACCOUNT_BALANCE * DEFAULT_LEVERAGE);
            
            // Calculate quantity
            double quantity = positionValue / entryPrice;
            
            // Apply minimum quantity
            double minQuantity = getMinQuantity(pair);
            if (quantity < minQuantity) {
                quantity = minQuantity;
            }
            
            // Round based on pair type
            if (INTEGER_QUANTITY_PAIRS.contains(pair)) {
                quantity = Math.floor(quantity);
            } else {
                // Most coins use 3 decimal places
                quantity = Math.floor(quantity * 1000) / 1000;
            }
            
            return Math.max(quantity, minQuantity);
            
        } catch (Exception e) {
            System.err.println("❌ Quantity calculation error: " + e.getMessage());
            return 0;
        }
    }
    
    private static boolean confirmTrade(String pair, String side) {
        // For automated trading, return true
        // For manual confirmation, you could add:
        // System.out.print("Confirm " + side + " " + pair + "? (y/n): ");
        // Scanner scanner = new Scanner(System.in);
        // return scanner.nextLine().trim().equalsIgnoreCase("y");
        
        return true; // Auto-confirm for now
    }
    
    private static boolean executeTrade(String pair, String side, double quantity, 
                                       double tpPrice, double slPrice) {
        try {
            System.out.println("\n💼 Placing Order...");
            
            JSONObject orderResponse = placeFuturesMarketOrder(
                side, pair, quantity, DEFAULT_LEVERAGE,
                "email_notification", "isolated", "INR"
            );
            
            if (orderResponse == null || !orderResponse.has("id")) {
                System.out.println("❌ Order placement failed");
                return false;
            }
            
            String orderId = orderResponse.getString("id");
            System.out.println("✅ Order placed: " + orderId);
            
            // Get actual entry price from order
            double entryPrice = getEntryPriceFromOrder(orderId);
            if (entryPrice <= 0) {
                entryPrice = getLastPrice(pair);
            }
            
            System.out.println("Actual entry price: $" + entryPrice);
            
            // Update TP/SL based on actual entry
            if ("buy".equals(side)) {
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
            
            // Try to set TP/SL
            String positionId = getPositionId(pair);
            if (positionId != null) {
                try {
                    boolean tpslSuccess = setTakeProfitAndStopLoss(positionId, tpPrice, slPrice, side, pair);
                    if (tpslSuccess) {
                        System.out.println("✅ TP/SL orders placed");
                        System.out.println("  TP: $" + String.format("%.4f", tpPrice));
                        System.out.println("  SL: $" + String.format("%.4f", slPrice));
                    } else {
                        System.out.println("⚠️ TP/SL orders may have failed");
                    }
                } catch (Exception e) {
                    System.err.println("⚠️ TP/SL error: " + e.getMessage());
                }
            }
            
            return true;
            
        } catch (Exception e) {
            System.err.println("❌ Trade execution error: " + e.getMessage());
            return false;
        }
    }
    
    // ==================== UTILITY METHODS ====================
    
    private static String getSpotPair(String futuresPair) {
        // Convert "B-BTC_USDT" to "BTC_USDT" for public API
        return futuresPair.replace("B-", "");
    }
    
    private static double getLastPrice(String pair) {
        try {
            String spotPair = getSpotPair(pair);
            
            // Try multiple methods to get price
            double price = 0;
            
            // Method 1: Trade history (most accurate)
            String url1 = PUBLIC_API_URL + "/market_data/trade_history?pair=" + spotPair + "&limit=1";
            HttpURLConnection conn1 = (HttpURLConnection) new URL(url1).openConnection();
            conn1.setConnectTimeout(3000);
            conn1.setReadTimeout(3000);
            
            if (conn1.getResponseCode() == 200) {
                String response = readAllLines(conn1.getInputStream());
                if (response.startsWith("[")) {
                    JSONArray trades = new JSONArray(response);
                    if (trades.length() > 0) {
                        price = trades.getJSONObject(0).getDouble("p");
                        System.out.println("Got price from trade history: $" + price);
                        return price;
                    }
                } else {
                    try {
                        JSONObject trade = new JSONObject(response);
                        price = trade.getDouble("p");
                        System.out.println("Got price from single trade: $" + price);
                        return price;
                    } catch (Exception e) {
                        // Continue to next method
                    }
                }
            }
            
            // Method 2: Ticker data
            System.out.println("Trying ticker data...");
            String url2 = PUBLIC_API_URL + "/market_data/ticker";
            HttpURLConnection conn2 = (HttpURLConnection) new URL(url2).openConnection();
            
            if (conn2.getResponseCode() == 200) {
                String response = readAllLines(conn2.getInputStream());
                JSONArray tickers = new JSONArray(response);
                
                for (int i = 0; i < tickers.length(); i++) {
                    JSONObject ticker = tickers.getJSONObject(i);
                    String market = ticker.optString("market", "");
                    if (market.equals(spotPair) || market.equals(pair)) {
                        price = ticker.optDouble("last_price", 0);
                        if (price > 0) {
                            System.out.println("Got price from ticker: $" + price);
                            return price;
                        }
                    }
                }
            }
            
            System.out.println("❌ Could not get price for " + pair);
            return 0;
            
        } catch (Exception e) {
            System.err.println("❌ Error getting price for " + pair + ": " + e.getMessage());
            return 0;
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
                System.out.println("Found " + activeInstruments.length() + " active instruments");

                for (int i = 0; i < activeInstruments.length(); i++) {
                    String pair = activeInstruments.getString(i);
                    try {
                        // Only load details for pairs we might trade
                        if (shouldLoadInstrument(pair)) {
                            String instrumentResponse = sendPublicRequest(
                                    BASE_URL + "/exchange/v1/derivatives/futures/data/instrument?pair=" + pair);
                            JSONObject instrumentDetails = new JSONObject(instrumentResponse).getJSONObject("instrument");
                            instrumentDetailsCache.put(pair, instrumentDetails);
                        }
                    } catch (Exception e) {
                        // Skip errors for specific instruments
                    }
                }

                lastInstrumentUpdateTime = currentTime;
                System.out.println("✅ Successfully updated instrument details for " + instrumentDetailsCache.size() + " pairs");
            }
        } catch (Exception e) {
            System.err.println("❌ Error initializing instrument details: " + e.getMessage());
        }
    }
    
    private static boolean shouldLoadInstrument(String pair) {
        // Only load instruments for our trading pairs
        for (String tradingPair : COINS_TO_TRADE) {
            if (tradingPair.equals(pair)) {
                return true;
            }
        }
        return false;
    }
    
    private static double getMinQuantity(String pair) {
        JSONObject instrument = instrumentDetailsCache.get(pair);
        if (instrument != null) {
            return instrument.optDouble("min_quantity", 0.001);
        }
        return 0.001; // Default minimum
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
    
    // ==================== API METHODS ====================
    
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

            System.out.println("\n=== Raw Position Data ===");
            System.out.println("Total positions received: " + positions.length());

            for (int i = 0; i < positions.length(); i++) {
                JSONObject position = positions.getJSONObject(i);
                String pair = position.optString("pair", "");

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
                activePairs.forEach(p -> System.out.println("- " + p));
                System.out.println("Total active positions detected: " + activePairs.size());
            }
        } catch (Exception e) {
            System.err.println("❌ Error fetching active positions: " + e.getMessage());
            e.printStackTrace();
        }
        return activePairs;
    }
    
    private static JSONObject placeFuturesMarketOrder(String side, String pair, double totalQuantity, int leverage,
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

            System.out.println("Order payload: " + body.toString());

            String response = sendAuthenticatedRequest(
                    BASE_URL + "/exchange/v1/derivatives/futures/orders/create",
                    body.toString(),
                    generateHmacSHA256(API_SECRET, body.toString())
            );
            
            System.out.println("Order response: " + response);
            
            return response.startsWith("[") ?
                    new JSONArray(response).getJSONObject(0) :
                    new JSONObject(response);
        } catch (Exception e) {
            System.err.println("❌ Error placing futures market order: " + e.getMessage());
            return null;
        }
    }
    
    private static double getEntryPriceFromPosition(String pair, String orderId) throws Exception {
        System.out.println("\nChecking position for entry price...");
        for (int attempts = 0; attempts < 10; attempts++) {
            TimeUnit.MILLISECONDS.sleep(1000);
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
    
    private static double getEntryPriceFromOrder(String orderId) {
        try {
            // Wait for order to fill
            TimeUnit.MILLISECONDS.sleep(1000);
            
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
            System.err.println("❌ Error getting entry price: " + e.getMessage());
            return 0;
        }
    }
    
    private static String getPositionId(String pair) {
        try {
            // Give it a moment for position to appear
            for (int i = 0; i < 5; i++) {
                JSONObject position = findPosition(pair);
                if (position != null && position.has("id")) {
                    return position.getString("id");
                }
                TimeUnit.MILLISECONDS.sleep(500);
            }
        } catch (Exception e) {
            System.err.println("❌ Error getting position ID: " + e.getMessage());
        }
        return null;
    }
    
    private static boolean setTakeProfitAndStopLoss(String positionId, double takeProfitPrice, double stopLossPrice,
                                                   String side, String pair) {
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

            System.out.println("Setting TP: $" + roundedTpPrice + " | SL: $" + roundedSlPrice);

            String response = sendAuthenticatedRequest(
                    BASE_URL + "/exchange/v1/derivatives/futures/positions/create_tpsl",
                    payload.toString(),
                    generateHmacSHA256(API_SECRET, payload.toString())
            );

            System.out.println("TP/SL response: " + response);
            
            JSONObject tpslResponse = new JSONObject(response);
            return !tpslResponse.has("err_code_dcx");
            
        } catch (Exception e) {
            System.err.println("❌ Error setting TP/SL: " + e.getMessage());
            return false;
        }
    }
    
    // ==================== HTTP UTILITIES ====================
    
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
