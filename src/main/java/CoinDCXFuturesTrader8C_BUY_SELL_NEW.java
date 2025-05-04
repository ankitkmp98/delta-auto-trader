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

public class CoinDCXFuturesTrader8C_BUY_SELL_NEW {
    private static final String API_KEY = System.getenv("DELTA_API_KEY");
 private static final String API_SECRET = System.getenv("DELTA_API_SECRET");
    private static final String BASE_URL = "https://api.coindcx.com";
    private static final String PUBLIC_API_URL = "https://public.coindcx.com";
    private static final double MAX_MARGIN = 600.0;
    private static final int MAX_ORDER_STATUS_CHECKS = 10;
    private static final int ORDER_CHECK_DELAY_MS = 1000;
    private static final long TICK_SIZE_CACHE_TTL_MS = 3600000; // 1 hour cache

    // Cache for instrument details with timestamp
    private static final Map<String, JSONObject> instrumentDetailsCache = new ConcurrentHashMap<>();
    private static long lastInstrumentUpdateTime = 0;

    private static final String[] COIN_SYMBOLS = 
    {
        "FIL", "DOT", "LTC", "ARK", "ENJ", "EOS", "USUAL", "TRX", "RENDER", "CRV",
"BCH", "OGN", "ONDO", "1000SHIB", "BIO", "AAVE", "ORCA", "NEAR", "PNUT", "T",
"POPCAT", "FUN", "VTHO", "WLD", "ALCH", "GAS", "XAI", "GALA", "TIA", "MBOX",
"APT", "ORDI", "HBAR", "OP", "INJ", "BEL", "JASMY", "RED", "KAITO", "PARTI",
"ARB", "BIGTIME", "AI16Z", "1000SATS", "NEIRO", "ETC", "JUP", "BOME", "UNI", "TON",
"1000BONK", "ACH", "XLM", "GOAT", "SAND", "ATOM", "LEVER", "S", "CAKE", "NOT",
"LOKA", "ARC", "VINE", "PENDLE", "LDO", "SEI", "HIFI", "RAYSOL", "APE", "RARE",
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
"HOOK", "ALT", "ZK", "BAKE", "COW", "SUSHI", "MLN", "SANTOS", "1MBABYDOGE", "SNX",
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
"ETHW", "DRIFT", "AKT", "KMNO", "SLERF", "DEFI", "USDC", "ADA", "ENA", "FARTCOIN",
"PEOPLE", "PEPE", "PI", "MOVE", "MUBARAK", "WIF", "XRP"
    };
    
    // "1000SATS", "1000X", "ACT", "ADA", "AIXBT", "AI16Z", "ALGO", "ALT", "API3", 
    // "ARB", "ARC", "AVAAI", "BAKE", "BB", "BIO", "BLUR", "BMT", "BONK", "COOKIE", 
    // "DOGE", "DOGS", "DYDX", "EIGEN", "ENA", "EOS", "ETHFI", "FARTCOIN", "FLOKI", 
    // "GALA", "GLM", "GOAT", "GRIFFAIN", "HBAR", "HIVE", "IO", "IOTA", "JASMY", 
    // "JUP", "KAITO", "LDO", "LISTA", "MANA", "MANTA", "MEME", "MELANIA", "MOODENG", 
    // "MOVE", "MUBARAK", "NEIRO", "NOT", "ONDO", "OP", "PEOPLE", "PEPE", "PENGU", 
    // "PI", "PNUT", "POL", "POPCAT", "RARE", "RED", "RSR", "SAGA", "SAND", "SEI", 
    // "SHIB", "SOLV", "SONIC", "SPX", "STX", "SUN", "SWARMS", "SUSHI", "TST", "TRX", 
    // "USUAL", "VINE", "VIRTUAL", "WIF", "WLD", "XAI", "XLM", "XRP", "ZK"
// };
    // {
    //         "BTC", "ETH", "VOXEL", "SOL", "NKN", "MAGIC", "XRP", "1000PEPE", "FARTCOIN", "DOGE",
    //         "TAO", "SUI", "TRUMP", "PERP", "OM","ADA", "BNB", "LINK", "PEOPLE", "GMT",
    //         "FET", "MUBARAK", "WIF", "LAYER", "AVAX", "HIGH", "ALPACA", "FLM", "ENA", "BSW",
    //         "FIL", "DOT", "LTC", "ARK", "ENJ", "EOS", "USUAL", "TRX", "RENDER", "CRV",
    //         "BCH", "OGN", "ONDO", "1000SHIB", "BIO", "AAVE", "ORCA", "NEAR", "PNUT", "T",
    //         "POPCAT", "FUN", "VTHO", "WLD", "ALCH", "GAS", "XAI", "GALA", "TIA", "MBOX",
    //         "APT", "ORDI", "HBAR", "OP", "INJ", "BEL", "JASMY", "RED", "KAITO", "PARTI",
    //         "ARB", "BIGTIME", "AI16Z", "1000SATS", "NEIRO", "ETC", "JUP", "BOME", "UNI", "TON",
    //         "1000BONK", "ACH", "XLM", "GOAT", "SAND", "ATOM", "LEVER", "S", "CAKE", "NOT",
    //         "LOKA", "ARC", "VINE", "PENDLE", "LDO", "SEI", "HIFI", "RAYSOL", "APE", "RARE",
    //         "WAXP", "GPS", "IP", "COTI", "AVAAI", "KOMA", "HFT", "ARKM", "ANIME", "ACT",
    //         "ALGO", "VIRTUAL", "MAVIA", "ALICE", "MANTA", "ZRO", "AGLD", "STX", "API3", "PIXEL",
    //         "MELANIA", "NEO", "IMX", "1000WHY", "MANA", "ACE", "SWARMS", "MKR", "AUCTION", "ICP",
    //         "PORTAL", "THETA", "CHESS", "ZEREBRO", "1000FLOKI", "PENGU", "STRK", "CATI", "TRB", "SAGA",
    //         "NIL", "TURBO", "AIXBT", "W", "PYTH", "LISTA", "CHILLGUY", "GRIFFAIN", "REZ", "IO",
    //         "UXLINK", "SHELL", "BTCDOM", "POL", "GRT", "BRETT", "DYDX", "JTO", "MOODENG", "ETHFI",
    //         "OMNI", "DOGS", "EIGEN", "ENS", "XMR", "D", "SOLV", "VET", "RUNE", "MEW",
    //         "AXS", "XCN", "SXP", "MASK", "BMT", "BANANA", "NFP", "XTZ", "FORTH", "ALPHA",
    //         "REI", "AR", "YGG", "PAXG", "SPX", "TRU", "ID", "GTC", "CHZ", "BLUR",
    //         "GRASS", "KAVA", "SPELL", "RSR", "FIDA", "MORPHO", "VANA", "RPL", "ANKR", "TLM",
    //         "CFX", "HIPPO", "TST", "ZEN", "ME", "AI", "MOVR", "GLM", "ZIL", "1000RATS",
    //         "HOOK", "ALT", "ZK", "BAKE", "COW", "SUSHI", "MLN", "SANTOS", "1MBABYDOGE", "SNX",
    //         "STORJ", "BEAMX", "WOO", "B3", "AEVO", "CTSI", "1000LUNC", "OXT", "ILV", "IOTA",
    //         "QTUM", "EPIC", "NEIROETH", "THE", "EDU", "ZEC", "AERO", "SKL", "ARPA", "BAN",
    //         "COMP", "CHR", "NMR", "ZETA", "LUMIA", "COOKIE", "PHB", "MINA", "1000CHEEMS", "1000CAT",
    //         "GHST", "KAS", "SUPER", "ROSE", "IOTX", "DYM", "EGLD", "SONIC", "RDNT", "LPT",
    //         "LUNA2", "PLUME", "XVG", "MYRO", "LQTY", "USTC", "C98", "SCR", "BB", "STEEM",
    //         "ONE", "FLOW", "QNT", "SSV", "POWR", "DEXE", "CGPT", "VANRY", "POLYX", "ZRX",
    //         "YFI", "TNSR", "GMX", "SYS", "1INCH", "CELO", "METIS", "1000X", "HEI", "ONT",
    //         "KSM", "KDA", "IOST", "BAT", "CETUS", "DF", "LRC", "HIVE", "DEGEN",
    //         "MTL", "SAFE", "CELR", "AVA", "CKB", "RIF", "FIO", "1000000MOG", "KNC", "ICX",
    //         "CYBER", "RONIN", "ONG", "VVV", "FXS", "MAV", "DEGO", "DASH", "ASTR", "PHA",
    //         "AXL", "BICO", "BAND", "SCRT", "HOT", "TOKEN", "STG", "PONKE", "DODOX", "DUSK",
    //         "SYN", "RVN", "UMA", "PIPPIN", "DENT", "PROM", "FLUX", "VELODROME", "SWELL", "MOCA",
    //         "ATA", "KAIA", "ATH", "XVS", "G", "LSK", "SUN", "NTRN", "RLC", "JOE",
    //         "1000XEC", "VIC", "SFP", "TWT", "QUICK", "BSV", "DIA", "BNT", "ACX", "COS",
    //         "ETHW", "DRIFT", "AKT", "KMNO", "SLERF", "DEFI", "USDC"
    // };

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

        // Counter to alternate between buy and sell
        int orderCounter = 0;

        for (String pair : COINS_TO_TRADE) {
            try {
                // Check if we already have an active position for this pair
                if (activePairs.contains(pair)) {
                    System.out.println("\n⏩ ⚠️ Skipping " + pair + " - Active position exists");
                    continue; // Skip to next pair
                }

                // Alternate between buy and sell based on counter
                String side = (orderCounter % 2 == 0) ? "buy" : "sell";
                int leverage = 30;

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

                // Increment counter for next order
                orderCounter++;

            } catch (Exception e) {
                System.err.println("❌ Error processing pair " + pair + ": " + e.getMessage());
            }
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

        double tpPercentage = 0.45; // 50% of margin for TP
        double slPercentage = 0.30; // 8% of margin for SL


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
}
