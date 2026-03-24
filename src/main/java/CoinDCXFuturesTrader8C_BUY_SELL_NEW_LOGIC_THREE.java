Two   import org.json.JSONArray;
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

    // =========================================================================
    // Configuration
    // =========================================================================
    private static final String API_KEY    = System.getenv("DELTA_API_KEY");
    private static final String API_SECRET = System.getenv("DELTA_API_SECRET");
    private static final String BASE_URL       = "https://api.coindcx.com";
    private static final String PUBLIC_API_URL = "https://public.coindcx.com";

    private static final double MAX_MARGIN             = 1200.0;
    private static final int    LEVERAGE               = 10;
    private static final int    MAX_ENTRY_PRICE_CHECKS = 10;
    private static final int    ENTRY_CHECK_DELAY_MS   = 1000;
    private static final long   TICK_CACHE_TTL_MS      = 3_600_000L;

    // Indicator parameters
    private static final int EMA_FAST   = 9;
    private static final int EMA_MID    = 21;
    private static final int EMA_MACRO  = 50;
    private static final int MACD_FAST  = 12;
    private static final int MACD_SLOW  = 26;
    private static final int MACD_SIG   = 9;
    private static final int RSI_PERIOD = 14;
    private static final int ATR_PERIOD = 14;
    private static final int SWING_BARS = 20;

    // RSI zones
    private static final double RSI_LONG_MIN  = 40.0;
    private static final double RSI_LONG_MAX  = 68.0;
    private static final double RSI_SHORT_MIN = 32.0;
    private static final double RSI_SHORT_MAX = 60.0;

    // Pullback zone: price must be within this many ATRs of EMA21 to enter
    private static final double PULLBACK_ATR_BAND = 2.0;

    // SL / TP
    private static final double SL_SWING_BUFFER = 0.5;
    private static final double SL_MIN_ATR      = 2.0;
    private static final double SL_MAX_ATR      = 4.0;
    private static final double RR              = 3.0;

    // Trailing SL thresholds
    private static final double TRAIL_BREAKEVEN_R = 1.0;
    private static final double TRAIL_LOCK_R      = 2.0;
    private static final double TRAIL_ATR_DIST    = 1.5;

    // Max concurrent positions — hard cap
    private static final int MAX_POSITIONS = 5;

    // Daily loss limit in INR — bot stops opening new trades if breached
    private static final double MAX_DAILY_LOSS_INR = -3600.0;

    // Volume filter — current candle volume must be VOL_MIN_RATIO x the 20-candle average
    private static final int    VOL_LOOKBACK  = 20;
    private static final double VOL_MIN_RATIO = 1.2;

    // Regime filter — ATR lookback to determine trending vs ranging
    private static final int REGIME_LOOKBACK = 20;

    // Log file
    private static final String LOG_FILE = "bot_log.txt";

    private static final int CANDLE_15M = 120;
    private static final int CANDLE_1H  = 70;

    private static final Map<String, JSONObject> instrumentCache = new ConcurrentHashMap<>();
    private static long lastCacheUpdate = 0;

    // State
    private static double      dailyPnlINR = 0.0;
    private static PrintWriter logWriter   = null;

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
        "ALT", "C98", "RLC", "SUN", "PENDLE", "BANANA", "NMR", "POL", "MAGIC",
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

    private static final Set<String> INTEGER_QTY_PAIRS = Stream.of(COIN_SYMBOLS)
            .flatMap(s -> Stream.of("B-" + s + "_USDT", s + "_USDT"))
            .collect(Collectors.toCollection(HashSet::new));

    private static final String[] COINS_TO_TRADE = Stream.of(COIN_SYMBOLS)
            .map(s -> "B-" + s + "_USDT")
            .toArray(String[]::new);

    // =========================================================================
    // MAIN
    // =========================================================================
    public static void main(String[] args) {
        initLogger();
        initInstrumentCache();

        // ── PHASE 1: Trailing SL update for all active positions ──────────────
        log("=== PHASE 1: Trailing SL Update for Active Positions ===");
        List<JSONObject> activePositions = getActivePositionsFull();
        log("Active positions found: " + activePositions.size());

        updateDailyPnl(activePositions);

        for (JSONObject pos : activePositions) {
            try {
                updateTrailingStopLoss(pos);
            } catch (Exception e) {
                log("Trail SL error for " + pos.optString("pair") + ": " + e.getMessage());
            }
        }

        // ── PHASE 2: Scan for new entry signals ───────────────────────────────
        log("=== PHASE 2: Scanning for New Entry Opportunities ===");

        Set<String> activeSet = new HashSet<>();
        for (JSONObject pos : activePositions) activeSet.add(pos.optString("pair"));
        log("Active pairs (will skip): " + activeSet);

        // Hard cap: max concurrent positions
        if (activePositions.size() >= MAX_POSITIONS) {
            log("MAX POSITIONS REACHED (" + activePositions.size() + "/" + MAX_POSITIONS
                    + ") — skipping new entries this cycle");
            closeLogger();
            return;
        }

        // Daily loss limit
        if (dailyPnlINR <= MAX_DAILY_LOSS_INR) {
            log("DAILY LOSS LIMIT HIT (" + String.format("%.2f", dailyPnlINR)
                    + " INR) — no new trades today");
            closeLogger();
            return;
        }

        for (String pair : COINS_TO_TRADE) {
            try {
                if (activeSet.contains(pair)) {
                    System.out.println("Skip " + pair + " — active position");
                    continue;
                }

                // Stop if max positions reached mid-scan
                if (activeSet.size() >= MAX_POSITIONS) {
                    log("Max positions reached mid-scan — stopping");
                    break;
                }

                System.out.println("\n==== " + pair + " ====");

                // ── Fetch candles ─────────────────────────────────────────────
                JSONArray raw15m = getCandlestickData(pair, "15", CANDLE_15M);
                JSONArray raw1h  = getCandlestickData(pair, "60", CANDLE_1H);

                if (raw15m == null || raw15m.length() < 60) {
                    System.out.println("  Insufficient 15m candles ("
                            + (raw15m == null ? 0 : raw15m.length()) + ") — skip");
                    continue;
                }
                if (raw1h == null || raw1h.length() < EMA_MACRO) {
                    System.out.println("  Insufficient 1H candles ("
                            + (raw1h == null ? 0 : raw1h.length()) + ") — skip");
                    continue;
                }

                double[] cl15  = extractCloses(raw15m);
                double[] op15  = extractOpens(raw15m);
                double[] hi15  = extractHighs(raw15m);
                double[] lo15  = extractLows(raw15m);
                double[] vol15 = extractVolumes(raw15m);
                double[] cl1h  = extractCloses(raw1h);

                double lastClose = cl15[cl15.length - 1];
                double prevClose = cl15[cl15.length - 2];
                double prevOpen  = op15[op15.length - 2];
                double tickSize  = getTickSize(pair);
                double atr       = calcATR(hi15, lo15, cl15, ATR_PERIOD);

                System.out.printf("  Price=%.6f  ATR=%.6f  Tick=%.8f%n", lastClose, atr, tickSize);

                // ── REGIME FILTER ─────────────────────────────────────────────
                if (!isTrending(hi15, lo15, cl15)) {
                    System.out.println("  REGIME FAIL — market ranging/compressing — skip");
                    continue;
                }
                System.out.println("  REGIME OK — market trending");

                // ── VOLUME FILTER ─────────────────────────────────────────────
                if (!isVolumeConfirmed(vol15)) {
                    System.out.printf("  VOLUME FAIL — current vol=%.2f below 1.2x average — skip%n",
                            vol15[vol15.length - 1]);
                    continue;
                }
                System.out.printf("  VOLUME OK — current vol=%.2f confirmed%n", vol15[vol15.length - 1]);

                // ── H1: 1H Macro Trend ────────────────────────────────────────
                double  ema1h     = calcEMA(cl1h, EMA_MACRO);
                boolean macroUp   = lastClose > ema1h;
                boolean macroDown = lastClose < ema1h;
                System.out.printf("  [H1] 1H EMA50=%.6f | Price %s EMA -> %s%n",
                        ema1h, macroUp ? ">" : "<", macroUp ? "BULL" : "BEAR");
                if (!macroUp && !macroDown) { System.out.println("  H1 FAIL — skip"); continue; }

                // ── H2: 15m Local Trend aligned with macro ────────────────────
                double  ema9      = calcEMA(cl15, EMA_FAST);
                double  ema21     = calcEMA(cl15, EMA_MID);
                boolean localUp   = ema9 > ema21;
                boolean localDown = ema9 < ema21;
                System.out.printf("  [H2] EMA9=%.6f EMA21=%.6f -> %s%n",
                        ema9, ema21, localUp ? "UP" : localDown ? "DOWN" : "flat");

                boolean trendUp   = macroUp   && localUp;
                boolean trendDown = macroDown && localDown;
                if (!trendUp && !trendDown) {
                    System.out.println("  H2 FAIL — macro/local misaligned — skip");
                    continue;
                }
                System.out.println("  H2 OK — " + (trendUp ? "BULLISH" : "BEARISH"));

                // ── H3: MACD aligned + histogram growing ──────────────────────
                double[] mv           = calcMACD(cl15, MACD_FAST, MACD_SLOW, MACD_SIG);
                double[] mvPrev       = calcMACDPrev(cl15, MACD_FAST, MACD_SLOW, MACD_SIG);
                double   macdLine     = mv[0], macdSigV = mv[1], macdHist = mv[2];
                double   macdHistPrev = mvPrev[2];
                System.out.printf("  [H3] MACD=%.6f Sig=%.6f Hist=%.6f prevHist=%.6f%n",
                        macdLine, macdSigV, macdHist, macdHistPrev);

                boolean macdBull    = macdLine > macdSigV;
                boolean macdBear    = macdLine < macdSigV;
                boolean histGrowing = Math.abs(macdHist) > Math.abs(macdHistPrev);

                if (trendUp   && !macdBull)  { System.out.println("  H3 FAIL — MACD bearish — skip");      continue; }
                if (trendDown && !macdBear)  { System.out.println("  H3 FAIL — MACD bullish — skip");      continue; }
                if (!histGrowing)            { System.out.println("  H3 FAIL — histogram shrinking — skip"); continue; }
                System.out.println("  H3 OK — MACD aligned + momentum growing");

                // ── H4: Pullback Zone ─────────────────────────────────────────
                double  pullbackBand    = PULLBACK_ATR_BAND * atr;
                boolean inPullbackLong  = trendUp   && lastClose >= ema21 && lastClose <= (ema21 + pullbackBand);
                boolean inPullbackShort = trendDown && lastClose <= ema21 && lastClose >= (ema21 - pullbackBand);
                boolean inPullbackZone  = inPullbackLong || inPullbackShort;

                if (trendUp) {
                    System.out.printf("  [H4] Long zone: [%.6f , %.6f] | Price=%.6f -> %s%n",
                            ema21, ema21 + pullbackBand, lastClose, inPullbackLong ? "PASS" : "FAIL");
                } else {
                    System.out.printf("  [H4] Short zone: [%.6f , %.6f] | Price=%.6f -> %s%n",
                            ema21 - pullbackBand, ema21, lastClose, inPullbackShort ? "PASS" : "FAIL");
                }
                if (!inPullbackZone) { System.out.println("  H4 FAIL — not in pullback zone — skip"); continue; }
                System.out.println("  H4 OK — price in pullback zone near EMA21");

                // ── SOFT FILTERS: at least 1 of 2 must pass ──────────────────
                double  rsi        = calcRSI(cl15, RSI_PERIOD);
                boolean rsiOkLong  = trendUp   && rsi >= RSI_LONG_MIN  && rsi <= RSI_LONG_MAX;
                boolean rsiOkShort = trendDown && rsi >= RSI_SHORT_MIN && rsi <= RSI_SHORT_MAX;
                boolean softRsi    = rsiOkLong || rsiOkShort;
                System.out.printf("  [S1] RSI=%.2f -> %s%n", rsi, softRsi ? "PASS" : "fail");

                boolean prevBull   = prevClose > prevOpen;
                boolean prevBear   = prevClose < prevOpen;
                boolean softCandle = (trendUp && prevBull) || (trendDown && prevBear);
                System.out.printf("  [S2] Prev candle %s -> %s%n",
                        prevBull ? "BULL" : prevBear ? "BEAR" : "DOJI",
                        softCandle ? "PASS" : "fail");

                if (!softRsi && !softCandle) {
                    System.out.println("  SOFT FAIL — neither RSI nor candle confirms — skip");
                    continue;
                }
                System.out.println("  SOFT OK — " + (softRsi ? "RSI " : "") + (softCandle ? "Candle" : "") + " confirmed");

                // ── All filters passed — place order ──────────────────────────
                String side = trendUp ? "buy" : "sell";
                log("ALL FILTERS PASSED -> " + side.toUpperCase() + " " + pair);

                double currentPrice = getLastPrice(pair);
                if (currentPrice <= 0) { System.out.println("  Invalid price — skip"); continue; }

                double qty = calcQuantity(currentPrice, pair);
                if (qty <= 0) { System.out.println("  Invalid qty — skip"); continue; }

                System.out.printf("  Placing %s | price=%.6f | qty=%.4f | lev=%dx%n",
                        side.toUpperCase(), currentPrice, qty, LEVERAGE);

                JSONObject resp = placeFuturesMarketOrder(side, pair, qty, LEVERAGE,
                        "email_notification", "isolated", "INR");

                if (resp == null || !resp.has("id")) {
                    log("Order FAILED for " + pair + ": " + resp);
                    continue;
                }
                log("Order placed! id=" + resp.getString("id") + " pair=" + pair + " side=" + side);

                // ── Confirm actual fill price ─────────────────────────────────
                double entry = getEntryPrice(pair, resp.getString("id"));
                if (entry <= 0) { System.out.println("  Could not confirm entry — TP/SL skipped"); continue; }
                System.out.printf("  Entry confirmed: %.6f%n", entry);

                // ── Calculate SL and TP ───────────────────────────────────────
                //
                // LONG:  rawSL = swingLow  - 0.5*ATR → clamp [entry-2ATR, entry-4ATR] → TP = entry + 3*risk
                // SHORT: rawSL = swingHigh + 0.5*ATR → clamp [entry+2ATR, entry+4ATR] → TP = entry - 3*risk
                //
                double slPrice, tpPrice;
                if ("buy".equalsIgnoreCase(side)) {
                    double swLow = swingLow(lo15, SWING_BARS);
                    double rawSL = swLow  - SL_SWING_BUFFER * atr;
                    double minSL = entry  - SL_MIN_ATR * atr;
                    double maxSL = entry  - SL_MAX_ATR * atr;
                    slPrice = Math.max(Math.min(rawSL, minSL), maxSL);
                    double risk = entry - slPrice;
                    tpPrice = entry + RR * risk;
                } else {
                    double swHigh = swingHigh(hi15, SWING_BARS);
                    double rawSL  = swHigh + SL_SWING_BUFFER * atr;
                    double minSL  = entry  + SL_MIN_ATR * atr;
                    double maxSL  = entry  + SL_MAX_ATR * atr;
                    slPrice = Math.min(Math.max(rawSL, minSL), maxSL);
                    double risk = slPrice - entry;
                    tpPrice = entry - RR * risk;
                }

                slPrice = roundToTick(slPrice, tickSize);
                tpPrice = roundToTick(tpPrice, tickSize);

                double risk   = Math.abs(entry - slPrice);
                double reward = Math.abs(tpPrice - entry);
                log(pair + " | Entry=" + String.format("%.6f", entry)
                        + " SL=" + String.format("%.6f", slPrice)
                        + " TP=" + String.format("%.6f", tpPrice)
                        + " Risk=" + String.format("%.6f", risk)
                        + " Reward=" + String.format("%.6f", reward)
                        + " R:R=1:" + String.format("%.1f", risk > 0 ? reward / risk : 0));

                // ── Set TP/SL on the position ─────────────────────────────────
                String posId = getPositionId(pair);
                if (posId != null) {
                    setTpSl(posId, tpPrice, slPrice, pair);
                    activeSet.add(pair);
                } else {
                    System.out.println("  Position ID not found — TP/SL not set");
                }

            } catch (Exception e) {
                log("Error on " + pair + ": " + e.getMessage());
            }
        }

        log("=== Scan complete ===");
        closeLogger();
    }

    // =========================================================================
    // TRAILING STOP LOSS
    // =========================================================================

    /**
     * Called every cycle for each active position.
     *   +1R profit → move SL to entry (breakeven, zero risk from now on)
     *   +2R profit → trail SL at livePrice ± 1.5*ATR  (locks profit)
     * SL only ever moves in the favourable direction — never widened.
     */
    private static void updateTrailingStopLoss(JSONObject pos) {
        String pair = pos.optString("pair");
        if (pair.isEmpty()) return;

        double entry   = pos.optDouble("avg_price", 0);
        double activeQ = pos.optDouble("active_pos", 0);
        if (entry <= 0 || activeQ == 0) return;

        boolean isLong    = activeQ > 0;
        double  currentSL = pos.optDouble("stop_loss_trigger", 0);
        double  currentTP = pos.optDouble("take_profit_trigger", 0);
        String  posId     = pos.optString("id");
        double  tickSize  = getTickSize(pair);

        System.out.printf("\n  [TRAIL] %s %s | entry=%.6f | SL=%.6f | TP=%.6f%n",
                isLong ? "LONG" : "SHORT", pair, entry, currentSL, currentTP);

        if (currentSL <= 0 || posId.isEmpty()) {
            System.out.println("  [TRAIL] No SL or position ID — skip");
            return;
        }

        double livePrice = getLastPrice(pair);
        if (livePrice <= 0) { System.out.println("  [TRAIL] Cannot get live price — skip"); return; }

        JSONArray raw15m = getCandlestickData(pair, "15", 30);
        if (raw15m == null || raw15m.length() < ATR_PERIOD + 1) {
            System.out.println("  [TRAIL] Cannot get candles — skip");
            return;
        }
        double atr = calcATR(extractHighs(raw15m), extractLows(raw15m), extractCloses(raw15m), ATR_PERIOD);
        if (atr <= 0) { System.out.println("  [TRAIL] ATR is 0 — skip"); return; }

        double initialRisk = isLong ? (entry - currentSL) : (currentSL - entry);
        if (initialRisk <= 0) initialRisk = SL_MIN_ATR * atr;

        double profit    = isLong ? (livePrice - entry) : (entry - livePrice);
        double rMultiple = (initialRisk > 0) ? profit / initialRisk : 0;

        System.out.printf("  [TRAIL] Live=%.6f | ATR=%.6f | InitRisk=%.6f | Profit=%.6f (%.2fR)%n",
                livePrice, atr, initialRisk, profit, rMultiple);

        double newSL = currentSL;

        if (rMultiple >= TRAIL_LOCK_R) {
            // +2R: trail at live price ± 1.5*ATR
            double trailSL = isLong
                    ? livePrice - TRAIL_ATR_DIST * atr
                    : livePrice + TRAIL_ATR_DIST * atr;

            boolean improved = isLong ? (trailSL > currentSL) : (trailSL < currentSL);
            if (improved) {
                newSL = trailSL;
                log("[TRAIL] " + pair + " +" + String.format("%.2f", rMultiple) + "R → Trail SL: "
                        + String.format("%.6f", currentSL) + " -> " + String.format("%.6f", newSL));
            } else {
                System.out.println("  [TRAIL] Trail SL already better — no change");
                return;
            }

        } else if (rMultiple >= TRAIL_BREAKEVEN_R) {
            // +1R: move SL to entry
            boolean needsMove = isLong ? (currentSL < entry) : (currentSL > entry);
            if (needsMove) {
                newSL = entry;
                log("[TRAIL] " + pair + " +" + String.format("%.2f", rMultiple) + "R → Breakeven SL: "
                        + String.format("%.6f", currentSL) + " -> " + String.format("%.6f", newSL));
            } else {
                System.out.println("  [TRAIL] SL already at/past breakeven — no change");
                return;
            }

        } else {
            System.out.printf("  [TRAIL] Profit=%.2fR — need +%.1fR — no action%n",
                    rMultiple, TRAIL_BREAKEVEN_R);
            return;
        }

        newSL = roundToTick(newSL, tickSize);
        if (Math.abs(newSL - currentSL) < tickSize * 0.5) {
            System.out.println("  [TRAIL] SL unchanged after rounding — skip");
            return;
        }

        if (currentTP > 0) {
            setTpSl(posId, currentTP, newSL, pair);
        } else {
            System.out.println("  [TRAIL] No existing TP — skipping API call");
        }
    }

    // =========================================================================
    // VOLUME CONFIRMATION
    // =========================================================================

    /**
     * Extracts the volume field from each candlestick bar.
     * The CoinDCX candlestick API includes volume in every bar response.
     */
    private static double[] extractVolumes(JSONArray a) {
        double[] o = new double[a.length()];
        for (int i = 0; i < a.length(); i++) o[i] = a.getJSONObject(i).getDouble("volume");
        return o;
    }

    /**
     * Returns true if the most recent candle's volume is at least VOL_MIN_RATIO
     * times the average volume of the previous VOL_LOOKBACK candles.
     *
     * Purpose: Filters out low-liquidity fake moves that reverse quickly.
     * VOL_MIN_RATIO=1.2 means current candle must have 20% above-average volume.
     */
    private static boolean isVolumeConfirmed(double[] volumes) {
        if (volumes.length < VOL_LOOKBACK + 1) return true;
        double current = volumes[volumes.length - 1];
        double sum = 0;
        int start = volumes.length - 1 - VOL_LOOKBACK;
        for (int i = start; i < volumes.length - 1; i++) sum += volumes[i];
        double avg = sum / VOL_LOOKBACK;
        System.out.printf("  [VOL] Current=%.2f Avg=%.2f Ratio=%.2fx (need %.1fx)%n",
                current, avg, avg > 0 ? current / avg : 0, VOL_MIN_RATIO);
        return avg > 0 && current >= avg * VOL_MIN_RATIO;
    }

    // =========================================================================
    // MARKET REGIME FILTER
    // =========================================================================

    /**
     * Returns true  = TRENDING  (ATR expanding, enter trades)
     * Returns false = RANGING   (ATR compressing, skip all entries)
     *
     * Compares current ATR vs average ATR of the last REGIME_LOOKBACK bars.
     * currentATR > avgATR → volatility growing → trending conditions present.
     * currentATR < avgATR → volatility shrinking → sideways/range market.
     *
     * This prevents EMA crossover entries in flat markets where false signals
     * are most common and SL hits most frequent.
     */
    private static boolean isTrending(double[] hi, double[] lo, double[] cl) {
        int len = cl.length;
        if (len < REGIME_LOOKBACK + ATR_PERIOD + 1) return true;

        double currentATR = calcATR(hi, lo, cl, ATR_PERIOD);
        double atrSum = 0;
        int    count  = 0;

        for (int i = len - REGIME_LOOKBACK; i < len - 1; i++) {
            double[] hiS = Arrays.copyOf(hi, i + 1);
            double[] loS = Arrays.copyOf(lo, i + 1);
            double[] clS = Arrays.copyOf(cl, i + 1);
            if (hiS.length > ATR_PERIOD + 1) {
                atrSum += calcATR(hiS, loS, clS, ATR_PERIOD);
                count++;
            }
        }
        if (count == 0) return true;
        double avgATR = atrSum / count;

        System.out.printf("  [REGIME] CurrentATR=%.6f AvgATR=%.6f -> %s%n",
                currentATR, avgATR, currentATR > avgATR ? "TRENDING" : "RANGING");
        return currentATR > avgATR;
    }

    // =========================================================================
    // DAILY P&L TRACKING
    // =========================================================================

    /**
     * Estimates total unrealised PnL across all active positions.
     * Called once at startup before Phase 2 begins.
     * Converts USDT PnL to INR at approximately 83 INR/USDT.
     * If total loss exceeds MAX_DAILY_LOSS_INR, Phase 2 is skipped entirely.
     */
    private static void updateDailyPnl(List<JSONObject> positions) {
        double totalPnlUsdt = 0;
        for (JSONObject pos : positions) {
            double activeQty = pos.optDouble("active_pos", 0);
            double avgPrice  = pos.optDouble("avg_price", 0);
            if (activeQty == 0 || avgPrice == 0) continue;

            String pair      = pos.optString("pair", "");
            double livePrice = getLastPrice(pair);
            if (livePrice <= 0) continue;

            boolean isLong = activeQty > 0;
            double  diff   = isLong ? (livePrice - avgPrice) : (avgPrice - livePrice);
            totalPnlUsdt  += diff * Math.abs(activeQty);
        }
        dailyPnlINR = totalPnlUsdt * 83.0;
        log("Daily PnL estimate: " + String.format("%.2f", dailyPnlINR)
                + " INR (limit: " + MAX_DAILY_LOSS_INR + " INR)");
    }

    // =========================================================================
    // FILE LOGGING
    // =========================================================================

    /** Opens bot_log.txt in append mode. Called once at start of main(). */
    private static void initLogger() {
        try {
            logWriter = new PrintWriter(new FileWriter(LOG_FILE, true));
            log("===== BOT RUN STARTED =====");
        } catch (IOException e) {
            System.err.println("Could not open log file: " + e.getMessage());
        }
    }

    /**
     * Writes a timestamped line to both stdout and bot_log.txt.
     * Use for all important events: orders placed, filters failed, errors.
     */
    private static void log(String message) {
        String line = Instant.now() + " | " + message;
        System.out.println(line);
        if (logWriter != null) {
            logWriter.println(line);
            logWriter.flush();
        }
    }

    /** Call at the very end of main() to flush and close the log file cleanly. */
    private static void closeLogger() {
        if (logWriter != null) {
            log("===== BOT RUN COMPLETE =====");
            logWriter.close();
        }
    }

    // =========================================================================
    // INDICATOR CALCULATIONS
    // =========================================================================

    private static double calcEMA(double[] d, int period) {
        if (d.length < period) return 0;
        double k = 2.0 / (period + 1);
        double ema = 0;
        for (int i = 0; i < period; i++) ema += d[i];
        ema /= period;
        for (int i = period; i < d.length; i++) ema = d[i] * k + ema * (1 - k);
        return ema;
    }

    private static double[] calcEMASeries(double[] d, int period) {
        double[] out = new double[d.length];
        if (d.length < period) return out;
        double k = 2.0 / (period + 1);
        double seed = 0;
        for (int i = 0; i < period; i++) seed += d[i];
        out[period - 1] = seed / period;
        for (int i = period; i < d.length; i++) out[i] = d[i] * k + out[i - 1] * (1 - k);
        return out;
    }

    private static double[] calcMACD(double[] cl, int fast, int slow, int sig) {
        double[] ef = calcEMASeries(cl, fast);
        double[] es = calcEMASeries(cl, slow);
        int start = slow - 1;
        int len   = cl.length - start;
        if (len <= 0) return new double[]{0, 0, 0};
        double[] ml = new double[len];
        for (int i = 0; i < len; i++) ml[i] = ef[start + i] - es[start + i];
        double[] ss = calcEMASeries(ml, sig);
        double m = ml[ml.length - 1];
        double s = ss[ss.length - 1];
        return new double[]{m, s, m - s};
    }

    /** MACD computed on all candles except the last — used for histogram growing check. */
    private static double[] calcMACDPrev(double[] cl, int fast, int slow, int sig) {
        if (cl.length < 2) return new double[]{0, 0, 0};
        return calcMACD(Arrays.copyOf(cl, cl.length - 1), fast, slow, sig);
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
                al =  al * (period - 1) / period;
            } else {
                al = (al * (period - 1) + Math.abs(ch)) / period;
                ag =  ag * (period - 1) / period;
            }
        }
        if (al == 0) return 100;
        return 100 - (100.0 / (1 + ag / al));
    }

    private static double calcATR(double[] hi, double[] lo, double[] cl, int period) {
        if (hi.length < period + 1) return 0;
        double[] tr = new double[hi.length];
        tr[0] = hi[0] - lo[0];
        for (int i = 1; i < hi.length; i++) {
            tr[i] = Math.max(hi[i] - lo[i],
                    Math.max(Math.abs(hi[i] - cl[i - 1]), Math.abs(lo[i] - cl[i - 1])));
        }
        double atr = 0;
        for (int i = 0; i < period; i++) atr += tr[i];
        atr /= period;
        for (int i = period; i < hi.length; i++) atr = (atr * (period - 1) + tr[i]) / period;
        return atr;
    }

    private static double swingLow(double[] lo, int bars) {
        double min = Double.MAX_VALUE;
        for (int i = Math.max(0, lo.length - bars); i < lo.length; i++) min = Math.min(min, lo[i]);
        return min;
    }

    private static double swingHigh(double[] hi, int bars) {
        double max = -Double.MAX_VALUE;
        for (int i = Math.max(0, hi.length - bars); i < hi.length; i++) max = Math.max(max, hi[i]);
        return max;
    }

    private static double roundToTick(double price, double tick) {
        if (tick <= 0) return price;
        return Math.round(price / tick) * tick;
    }

    // =========================================================================
    // OHLCV EXTRACTION
    // =========================================================================

    private static double[] extractCloses(JSONArray a) {
        double[] o = new double[a.length()];
        for (int i = 0; i < a.length(); i++) o[i] = a.getJSONObject(i).getDouble("close");
        return o;
    }

    private static double[] extractOpens(JSONArray a) {
        double[] o = new double[a.length()];
        for (int i = 0; i < a.length(); i++) o[i] = a.getJSONObject(i).getDouble("open");
        return o;
    }

    private static double[] extractHighs(JSONArray a) {
        double[] o = new double[a.length()];
        for (int i = 0; i < a.length(); i++) o[i] = a.getJSONObject(i).getDouble("high");
        return o;
    }

    private static double[] extractLows(JSONArray a) {
        double[] o = new double[a.length()];
        for (int i = 0; i < a.length(); i++) o[i] = a.getJSONObject(i).getDouble("low");
        return o;
    }

    // =========================================================================
    // COINDCX API
    // =========================================================================

    private static JSONArray getCandlestickData(String pair, String resolution, int count) {
        try {
            long minsPerBar;
            switch (resolution) {
                case "1":  minsPerBar = 1;  break;
                case "5":  minsPerBar = 5;  break;
                case "15": minsPerBar = 15; break;
                case "60": minsPerBar = 60; break;
                default:   minsPerBar = 15; break;
            }
            long to   = Instant.now().getEpochSecond();
            long from = to - minsPerBar * 60L * count;
            String url = PUBLIC_API_URL + "/market_data/candlesticks"
                    + "?pair=" + pair
                    + "&from=" + from
                    + "&to=" + to
                    + "&resolution=" + resolution
                    + "&pcode=f";
            HttpURLConnection conn = openGet(url);
            if (conn.getResponseCode() == 200) {
                JSONObject r = new JSONObject(readStream(conn.getInputStream()));
                if ("ok".equals(r.optString("s"))) return r.getJSONArray("data");
                System.err.println("  Candle status=" + r.optString("s"));
            } else {
                System.err.println("  Candle HTTP " + conn.getResponseCode() + " for " + pair);
            }
        } catch (Exception e) {
            System.err.println("  getCandlestickData(" + pair + "): " + e.getMessage());
        }
        return null;
    }

    private static void initInstrumentCache() {
        try {
            long now = System.currentTimeMillis();
            if (now - lastCacheUpdate < TICK_CACHE_TTL_MS) return;
            instrumentCache.clear();
            System.out.println("Refreshing instrument cache...");
            JSONArray pairs = new JSONArray(publicGet(
                    BASE_URL + "/exchange/v1/derivatives/futures/data/active_instruments"));
            for (int i = 0; i < pairs.length(); i++) {
                String p = pairs.getString(i);
                try {
                    String raw = publicGet(
                            BASE_URL + "/exchange/v1/derivatives/futures/data/instrument?pair=" + p);
                    instrumentCache.put(p, new JSONObject(raw).getJSONObject("instrument"));
                } catch (Exception ignored) {}
            }
            lastCacheUpdate = now;
            System.out.println("Instruments cached: " + instrumentCache.size());
        } catch (Exception e) {
            System.err.println("initInstrumentCache: " + e.getMessage());
        }
    }

    private static double getTickSize(String pair) {
        if (System.currentTimeMillis() - lastCacheUpdate > TICK_CACHE_TTL_MS) initInstrumentCache();
        JSONObject d = instrumentCache.get(pair);
        return d != null ? d.optDouble("price_increment", 0.0001) : 0.0001;
    }

    private static double getEntryPrice(String pair, String orderId) throws Exception {
        for (int i = 0; i < MAX_ENTRY_PRICE_CHECKS; i++) {
            TimeUnit.MILLISECONDS.sleep(ENTRY_CHECK_DELAY_MS);
            JSONObject pos = findPosition(pair);
            if (pos != null && pos.optDouble("avg_price", 0) > 0)
                return pos.getDouble("avg_price");
        }
        return 0;
    }

    private static JSONObject findPosition(String pair) throws Exception {
        JSONObject body = new JSONObject();
        body.put("timestamp", Instant.now().toEpochMilli());
        body.put("page", "1");
        body.put("size", "20");
        body.put("margin_currency_short_name", new JSONArray(Arrays.asList("INR", "USDT")));
        String resp = authPost(
                BASE_URL + "/exchange/v1/derivatives/futures/positions", body.toString());
        JSONArray arr = resp.startsWith("[")
                ? new JSONArray(resp)
                : new JSONArray().put(new JSONObject(resp));
        for (int i = 0; i < arr.length(); i++) {
            JSONObject p = arr.getJSONObject(i);
            if (pair.equals(p.optString("pair"))) return p;
        }
        return null;
    }

    private static double calcQuantity(double price, String pair) {
        double qty = MAX_MARGIN / (price * 93);
        return Math.max(
                INTEGER_QTY_PAIRS.contains(pair)
                        ? Math.floor(qty)
                        : Math.floor(qty * 100) / 100,
                0);
    }

    public static double getLastPrice(String pair) {
        try {
            HttpURLConnection conn = openGet(
                    PUBLIC_API_URL + "/market_data/trade_history?pair=" + pair + "&limit=1"); **...**             } catch (Exception e) {
                System.err.println("authPost error: " + e.getMessage());
                throw new IOException(e.getMessage());
            }
        }

        private static String readStream(InputStream is) throws IOException {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line).append("\n");
                return sb.toString();
            }
        }

        private static String sign(String payload) {
            try {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(
                        API_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
                byte[] b = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                for (byte x : b) sb.append(String.format("%02x", x));
                return sb.toString();
            } catch (Exception e) {
                throw new RuntimeException("HMAC sign failed", e);
            }
        }

        public static String generateHmacSHA256(String secret, String payload) {
            return sign(payload);
        }
    }
