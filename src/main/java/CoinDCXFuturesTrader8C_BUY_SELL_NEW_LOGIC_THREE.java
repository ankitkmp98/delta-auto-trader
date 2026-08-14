import org.json.JSONArray;
import org.json.JSONObject;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class CoinDCXFuturesTrader8C_BUY_SELL_NEW_LOGIC_THREE {

    // =========================================================================
    // API Configuration
    // =========================================================================
    private static final String API_KEY    = System.getenv("DELTA_API_KEY");
    private static final String API_SECRET = System.getenv("DELTA_API_SECRET");
    private static final String BASE_URL       = "https://api.coindcx.com";
    private static final String PUBLIC_API_URL = "https://public.coindcx.com";

    private static final double MAX_MARGIN = 2500.0;
    private static final int    LEVERAGE   = 10;

    private static final int MAX_ENTRY_PRICE_CHECKS = 20;
    private static final int ENTRY_CHECK_DELAY_MS    = 1000;

    private static final int  TPSL_MAX_RETRIES    = 3;
    private static final long TPSL_RETRY_DELAY_MS = 2000L;

    private static final long TICK_CACHE_TTL_MS = 3_600_000L;
    private static final long COOLDOWN_MS       = 2 * 60 * 60 * 1000L;

    private static final int MAX_OPEN_POSITIONS = 120;

    private static final int  POSITION_ID_MAX_RETRIES = 5;
    private static final long POSITION_ID_RETRY_DELAY_MS = 1500L;

    private static final int EMA_FAST = 9;
    private static final int EMA_MID  = 21;
    private static final int ATR_PERIOD = 14;

    private static final int    ST_PERIOD     = 10;
    private static final double ST_MULTIPLIER = 3.0;

    private static final double PULLBACK_MAX_ATR = 0.6;

    private static final double SL_ATR_BUFFER   = 0.35;
    private static final double SL_MAX_PERCENT  = 4.5;
    private static final int    SWING_LOOKBACK       = 20;
    private static final int    SWING_EXCLUDE_RECENT = 2;
    private static final double SWING_EXTRA_BUFFER_ATR = 0.15;

    // NOTE: RR_TARGET controls the TP-gap-to-SL-gap ratio at ENTRY time.
    // If you want SL gap == TP gap exactly (e.g. entry=100, SL=97, TP=103),
    // set this to 1.0. It was 1.2 originally (TP gap 20% wider than SL gap).
    private static final double RR_TARGET = 1.0;

    private static final double LIMIT_ORDER_BUFFER_PCT = 0.001;

    private static final int CANDLE_15M = 60;
    private static final int CANDLE_30M = 100;
    private static final int CANDLE_1H  = 100;
    private static final int HTF_1H_FETCH_COUNT = 700;

    // =========================================================================
    // Trailing SL/TP configuration
    // =========================================================================
    // How often (ms) we re-check open positions for trailing. This is the
    // "24x7 manual monitoring" replacement — keep this low enough to react
    // quickly, but not so low that we hammer the exchange REST API across
    // many open positions. 5-10s is a reasonable floor for REST polling.
    private static final long TRAIL_POLL_INTERVAL_MS = 8_000L;

    // How often (ms) we re-run the full multi-timeframe entry scan across
    // all ~250 pairs. This does NOT need to be as frequent as trailing —
    // trend/structure changes are not a 15-second phenomenon.
    private static final long ENTRY_SCAN_INTERVAL_MS = 3 * 60 * 1000L;

    // Where trailing state is persisted so it survives VM reboots / JVM
    // restarts. Mirrors the existing bot_state.json / last_trade_state.json
    // pattern already used elsewhere in this project.
    private static final String TRAIL_STATE_FILE = "trail_state.json";

    private static final Map<String, JSONObject> instrumentCache = new ConcurrentHashMap<>();
    private static long lastCacheUpdate = 0;
    private static final Map<String, Long> lastTradeTime = new ConcurrentHashMap<>();

    // pair -> trailing state (in-memory, backed by TRAIL_STATE_FILE on disk)
    private static final Map<String, TrailState> trailStateMap = new ConcurrentHashMap<>();

    private static final String[] COIN_SYMBOLS = {
        "ETH", "SOL", "ZEC", "XRP", "DOGE", "BNB", "TAO", "1000PEPE", "ADA", "SUI",
        "BCH", "LINK", "AVAX", "FIL", "OP", "NEAR", "TRX", "TRUMP", "ARB", "WLD",
        "FET", "ETC", "AAVE", "WIF", "INJ", "TIA", "LTC", "ONDO", "ORDI", "TON",
        "HBAR", "IMX", "ATOM", "RUNE", "KAS", "UNI", "ICP", "SEI", "PENDLE", "1000SHIB",
        "1000BONK", "CRV", "JUP", "RENDER", "MKR", "LDO", "STX", "XLM", "PYTH", "VIRTUAL",
        "APT", "SNX", "STRK", "NEO", "FTM", "CAKE", "1000FLOKI", "1000SATS", "OM", "FARTCOIN",
        "GRT", "MINA", "COMP", "BLUR", "BRETT", "SAND", "EGLD", "XMR", "IOTA", "AI16Z",
        "PNUT", "POPCAT", "ZRO", "MANA", "ETHFI", "VET", "ALGO", "ENS", "BOME", "MASK",
        "GALA", "YFI", "CHZ", "GMX", "QNT", "POL", "MOODENG", "ZK", "ARKM", "THETA",
        "MEW", "EIGEN", "MORPHO", "KAITO", "USUAL", "LAYER", "GOAT", "DOGS", "RSR", "PONKE",
        "JTO", "CKB", "ZIL", "ROSE", "1INCH", "TWT", "KSM", "MAGIC", "GAS", "ACT",
        "SUSHI", "TURBO", "1000LUNC", "BTCDOM", "S", "IP", "FLOW", "TRB", "QTUM", "KNC",
        "KAIA", "CELO", "SSV", "BANANA", "TNSR", "AERO", "IO", "DEXE", "ARK", "XAI",
        "DYM", "SAGA", "HOT", "LUNA2", "IOST", "RPL", "VANA", "DASH", "MANTA", "LRC",
        "ANKR", "XTZ", "BAND", "SUPER", "FXS", "AKT", "NMR", "PIXEL", "LPT", "STORJ",
        "ENJ", "LISTA", "ZETA", "RED", "AGLD", "GPS", "KAVA", "SXP", "ALPHA", "BIGTIME",
        "COTI", "USTC", "BAT", "NFP", "ONE", "POLYX", "MOVR", "OMNI", "CELR", "RVN",
        "GLM", "HIVE", "FLUX", "ZRX", "SFP", "ALICE", "ILV", "ARPA", "UMA", "DEGEN",
        "XVS", "ACE", "ASTR", "CTSI", "CHR", "EDU", "PROM", "ALT", "C98", "SUN",
        "WAXP", "ALPACA", "COOKIE", "JOE", "BNT", "SCRT", "VELODROME", "HOOK", "KMNO", "NTRN",
        "RAYSOL", "PARTI", "MELANIA", "MYRO", "SHELL", "AUCTION", "SWELL", "HIGH", "WOO",
        "COW", "MAVIA", "VTHO", "1000CAT", "MUBARAK", "LEVER", "SOLV", "ARC", "AVAAI", "KOMA",
        "API3", "VOXEL", "CHESS", "SPELL", "1000WHY", "SKL", "GTC", "MTL", "BICO", "DENT",
        "RLC", "PHB", "POWR", "LSK", "DEFI", "MAV", "REI", "ONG", "XVG", "COS",
        "FORTH", "BEL", "MLN", "HEI", "GHST", "STEEM", "LOKA", "DIA", "TLM", "BMT",
        "ALCH", "FUN", "1000CHEEMS", "1000RATS", "1000000MOG", "1MBABYDOGE", "1000XEC", "1000X", "PERP", "NKN",
        "VINE", "RARE", "HFT", "AXL", "ACH", "ZEN", "PEOPLE", "AR", "CFX", "ID",
        "METIS", "FIO", "CYBER"
    };

    private static final Set<String> INTEGER_QTY_PAIRS = Stream.of(COIN_SYMBOLS)
            .flatMap(s -> Stream.of("B-" + s + "_USDT", s + "_USDT"))
            .collect(Collectors.toCollection(HashSet::new));

    private static final String[] COINS_TO_TRADE = Stream.of(COIN_SYMBOLS)
            .map(s -> "B-" + s + "_USDT")
            .toArray(String[]::new);

    private static class TFResult {
        boolean valid;
        boolean bullish;
        boolean bearish;
        boolean stGreen;
        double  ema9, ema21, price;
        double  atr;
        double[] stBands;
        double[] hi, lo, cl;
    }

    // =========================================================================
    // Per-position trailing state
    // =========================================================================
    // initialRisk   = |entryPrice - originalSL| at the moment the trade was
    //                 opened. This is the fixed SL "gap" we maintain forever.
    // initialReward = |originalTP - entryPrice| at the moment the trade was
    //                 opened. This is the fixed TP "gap" we maintain forever.
    //
    // Trailing logic (continuous 1:1, no step/threshold):
    //   entry=100, SL=97 (gap=3), TP=103 (gap=3)
    //   price -> 101  => SL -> 98,  TP -> 104
    //   price -> 105  => SL -> 102, TP -> 108
    //   Both gaps stay fixed at 3 for the life of the trade. SL/TP only ever
    //   move in the favorable direction, never backward.
    private static class TrailState {
        boolean isLong;
        double entryPrice;
        double initialRisk;    // SL gap
        double initialReward;  // TP gap

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            o.put("isLong", isLong);
            o.put("entryPrice", entryPrice);
            o.put("initialRisk", initialRisk);
            o.put("initialReward", initialReward);
            return o;
        }

        static TrailState fromJson(JSONObject o) {
            TrailState t = new TrailState();
            t.isLong = o.optBoolean("isLong", true);
            t.entryPrice = o.optDouble("entryPrice", 0);
            t.initialRisk = o.optDouble("initialRisk", 0);
            t.initialReward = o.optDouble("initialReward", 0);
            return t;
        }
    }

    // =========================================================================
    // Trail state persistence (mirrors bot_state.json pattern)
    // =========================================================================
    private static synchronized void loadTrailState() {
        try {
            Path p = Paths.get(TRAIL_STATE_FILE);
            if (!Files.exists(p)) {
                System.out.println("[TRAIL] No existing " + TRAIL_STATE_FILE + " — starting fresh.");
                return;
            }
            String content = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            if (content.trim().isEmpty()) return;
            JSONObject root = new JSONObject(content);
            for (String pair : root.keySet()) {
                trailStateMap.put(pair, TrailState.fromJson(root.getJSONObject(pair)));
            }
            System.out.println("[TRAIL] Loaded trail state for " + trailStateMap.size() + " pair(s).");
        } catch (Exception e) {
            System.err.println("[TRAIL] loadTrailState failed (continuing with empty state): " + e.getMessage());
        }
    }

    private static synchronized void saveTrailState() {
        try {
            JSONObject root = new JSONObject();
            for (Map.Entry<String, TrailState> e : trailStateMap.entrySet()) {
                root.put(e.getKey(), e.getValue().toJson());
            }
            Files.write(Paths.get(TRAIL_STATE_FILE), root.toString(2).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            System.err.println("[TRAIL] saveTrailState failed: " + e.getMessage());
        }
    }

    private static TFResult analyzeTF(JSONArray candles) {
        TFResult r = new TFResult();
        if (candles == null || candles.length() < EMA_MID + ST_PERIOD + 5) {
            r.valid = false;
            return r;
        }
        double[] cl = extractCloses(candles);
        double[] hi = extractHighs(candles);
        double[] lo = extractLows(candles);

        r.cl = cl; r.hi = hi; r.lo = lo;
        r.ema9  = calcEMA(cl, EMA_FAST);
        r.ema21 = calcEMA(cl, EMA_MID);
        r.price = cl[cl.length - 1];
        r.atr   = calcATR(hi, lo, cl, ATR_PERIOD);

        boolean[] stSeries = calcSupertrend(hi, lo, cl, ST_PERIOD, ST_MULTIPLIER);
        r.stGreen  = stSeries[stSeries.length - 1];
        r.stBands  = calcSupertrendBands(hi, lo, cl, ST_PERIOD, ST_MULTIPLIER);
        r.valid = true;

        boolean priceAboveEmas = r.price > r.ema9 && r.price > r.ema21;
        boolean priceBelowEmas = r.price < r.ema9 && r.price < r.ema21;
        boolean priceAboveSt   = r.price > r.stBands[0];
        boolean priceBelowSt   = r.price < r.stBands[1];

        r.bullish = r.stGreen && priceAboveSt && (r.ema9 > r.ema21) && priceAboveEmas;
        r.bearish = (!r.stGreen) && priceBelowSt && (r.ema9 < r.ema21) && priceBelowEmas;
        return r;
    }

    private static boolean isBullishRejection(double open, double high, double low, double close,
                                               double prevOpen, double prevClose) {
        double range = high - low;
        if (range <= 0) return false;
        double body       = Math.abs(close - open);
        double lowerWick   = Math.min(open, close) - low;
        double upperWick   = high - Math.max(open, close);
        boolean isBull     = close > open;

        boolean hammer = body > 0 && lowerWick >= 2 * body && upperWick <= body * 0.6;

        boolean bullishEngulf = prevClose < prevOpen && isBull
                && close >= prevOpen && open <= prevClose;

        double bodyRatio = body / range;
        double closePos  = (close - low) / range;
        boolean strongBull = isBull && bodyRatio >= 0.55 && closePos >= 0.7;

        return hammer || bullishEngulf || strongBull;
    }

    private static boolean isBearishRejection(double open, double high, double low, double close,
                                               double prevOpen, double prevClose) {
        double range = high - low;
        if (range <= 0) return false;
        double body       = Math.abs(close - open);
        double lowerWick   = Math.min(open, close) - low;
        double upperWick   = high - Math.max(open, close);
        boolean isBear     = close < open;

        boolean shootingStar = body > 0 && upperWick >= 2 * body && lowerWick <= body * 0.6;

        boolean bearishEngulf = prevClose > prevOpen && isBear
                && open >= prevClose && close <= prevOpen;

        double bodyRatio = body / range;
        double closePos  = (close - low) / range;
        boolean strongBear = isBear && bodyRatio >= 0.55 && closePos <= 0.3;

        return shootingStar || bearishEngulf || strongBear;
    }

    private static double findSwingLow(double[] lo, int lookback, int excludeRecent) {
        int n = lo.length;
        int start = Math.max(0, n - lookback - excludeRecent);
        int end   = Math.max(start, n - excludeRecent);
        double sw = Double.POSITIVE_INFINITY;
        for (int i = start; i < end; i++) sw = Math.min(sw, lo[i]);
        return sw;
    }

    private static double findSwingHigh(double[] hi, int lookback, int excludeRecent) {
        int n = hi.length;
        int start = Math.max(0, n - lookback - excludeRecent);
        int end   = Math.max(start, n - excludeRecent);
        double sw = Double.NEGATIVE_INFINITY;
        for (int i = start; i < end; i++) sw = Math.max(sw, hi[i]);
        return sw;
    }

    private static double[] computeSlTp(boolean isLong, double entryPrice, TFResult tf2h, double tickSize) {
        double sl, tp;
        if (isLong) {
            double raw = tf2h.stBands[0] - SL_ATR_BUFFER * tf2h.atr;
            if (raw >= entryPrice) raw = entryPrice - (SL_ATR_BUFFER + 1.5) * tf2h.atr;
            double hardFloor = entryPrice * (1 - SL_MAX_PERCENT / 100.0);

            double swingLow = findSwingLow(tf2h.lo, SWING_LOOKBACK, SWING_EXCLUDE_RECENT);
            if (swingLow < raw && swingLow > hardFloor) {
                raw = swingLow - SWING_EXTRA_BUFFER_ATR * tf2h.atr;
            }

            sl = Math.max(raw, hardFloor);
            double risk = entryPrice - sl;
            tp = entryPrice + RR_TARGET * risk;
        } else {
            double raw = tf2h.stBands[1] + SL_ATR_BUFFER * tf2h.atr;
            if (raw <= entryPrice) raw = entryPrice + (SL_ATR_BUFFER + 1.5) * tf2h.atr;
            double hardCeil = entryPrice * (1 + SL_MAX_PERCENT / 100.0);

            double swingHigh = findSwingHigh(tf2h.hi, SWING_LOOKBACK, SWING_EXCLUDE_RECENT);
            if (swingHigh > raw && swingHigh < hardCeil) {
                raw = swingHigh + SWING_EXTRA_BUFFER_ATR * tf2h.atr;
            }

            sl = Math.min(raw, hardCeil);
            double risk = sl - entryPrice;
            tp = entryPrice - RR_TARGET * risk;
        }
        sl = roundToTick(sl, tickSize);
        tp = roundToTick(tp, tickSize);
        return new double[]{sl, tp};
    }

    private static double[] sanityClampSlTp(boolean isLong, double entry, double sl, double tp, double tick) {
        double minGap = Math.max(tick, entry * 0.0005);
        if (isLong) {
            if (sl >= entry - minGap) sl = entry - minGap;
            if (tp <= entry + minGap) tp = entry + minGap;
        } else {
            if (sl <= entry + minGap) sl = entry + minGap;
            if (tp >= entry - minGap) tp = entry - minGap;
        }
        sl = roundToTick(sl, tick);
        tp = roundToTick(tp, tick);
        return new double[]{sl, tp};
    }

    // =========================================================================
    // Orchestrator. This is a continuous 24x7 process instead of a
    // single scan-and-exit run. Two independent timers:
    //   1. Entry scan (new trades)      -> every ENTRY_SCAN_INTERVAL_MS
    //   2. Trailing SL/TP (open trades) -> every TRAIL_POLL_INTERVAL_MS
    // =========================================================================
    public static void main(String[] args) {
        System.out.println("=== Bot starting (continuous mode) ===");
        loadTrailState();
        initInstrumentCache();

        // On startup, reconcile trail state against whatever is actually
        // open on the exchange right now — covers VM reboot / JVM crash
        // recovery so we never "lose track" of an existing position.
        reconcileTrailStateOnStartup();

        long lastEntryScan = 0L;

        while (true) {
            try {
                long now = System.currentTimeMillis();

                // ---- Trailing check (frequent) ----
                trailOpenPositions();

                // ---- Entry scan (less frequent) ----
                if (now - lastEntryScan >= ENTRY_SCAN_INTERVAL_MS) {
                    runEntryScan();
                    lastEntryScan = System.currentTimeMillis();
                }

            } catch (Throwable t) {
                // Never let one bad cycle kill the whole 24x7 process.
                System.err.println("[MAIN-LOOP] Uncaught error, continuing: " + t.getMessage());
                t.printStackTrace();
            }

            try {
                TimeUnit.MILLISECONDS.sleep(TRAIL_POLL_INTERVAL_MS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    // =========================================================================
    // Reconcile persisted trail state with live exchange positions on
    // startup. Any open position with no known trail state (fresh position,
    // or state file lost/incompatible) gets a state reconstructed from its
    // CURRENT avg_price / stop_loss_trigger / take_profit_trigger as a safe
    // fallback. Any stale trail state entries for positions that are no
    // longer open get removed.
    //
    // This is also what makes the new logic apply automatically to any
    // positions that were already open before this update — just restart
    // the process (after deleting the old trail_state.json, since its
    // schema doesn't have initialReward).
    // =========================================================================
    private static void reconcileTrailStateOnStartup() {
        try {
            Set<String> active = getActivePositions();

            // Drop trail state for anything no longer open.
            trailStateMap.keySet().removeIf(pair -> !active.contains(pair));

            for (String pair : active) {
                if (trailStateMap.containsKey(pair)) continue;
                JSONObject pos = findPosition(pair);
                if (pos == null) continue;
                double avgPrice = pos.optDouble("avg_price", 0);
                double slTrig   = pos.optDouble("stop_loss_trigger", 0);
                double tpTrig   = pos.optDouble("take_profit_trigger", 0);
                double posQty   = pos.optDouble("active_pos", 0);
                if (avgPrice <= 0 || slTrig <= 0 || tpTrig <= 0) continue;

                TrailState t = new TrailState();
                t.isLong = posQty >= 0;
                t.entryPrice = avgPrice;
                t.initialRisk = Math.abs(avgPrice - slTrig);
                t.initialReward = Math.abs(tpTrig - avgPrice);
                trailStateMap.put(pair, t);
                System.out.println("[TRAIL] Reconstructed state on startup for " + pair
                        + " (entry=" + avgPrice + ", riskGap=" + t.initialRisk
                        + ", rewardGap=" + t.initialReward + ")");
            }
            saveTrailState();
        } catch (Exception e) {
            System.err.println("reconcileTrailStateOnStartup: " + e.getMessage());
        }
    }

    // =========================================================================
    // The core trailing loop. For every open position: maintain a FIXED gap
    // between the current price and SL/TP, continuously — no step/threshold.
    //   entry=100, SL=97 (gap=3), TP=103 (gap=3)
    //   price=101 -> SL=98,  TP=104
    //   price=105 -> SL=102, TP=108
    // SL/TP only ever move in the favorable direction, never backward.
    // =========================================================================
    private static void trailOpenPositions() {
        try {
            JSONObject body = new JSONObject();
            body.put("timestamp", Instant.now().toEpochMilli());
            body.put("page", "1");
            body.put("size", "100");
            body.put("margin_currency_short_name", new String[]{"INR", "USDT"});
            String resp = authPost(BASE_URL + "/exchange/v1/derivatives/futures/positions", body.toString());
            JSONArray arr = resp.startsWith("[")
                    ? new JSONArray(resp) : new JSONArray().put(new JSONObject(resp));

            Set<String> stillOpen = new HashSet<>();

            for (int i = 0; i < arr.length(); i++) {
                JSONObject pos = arr.getJSONObject(i);
                String pair = pos.optString("pair", "");
                double avgPrice = pos.optDouble("avg_price", 0);
                double posQty   = pos.optDouble("active_pos", 0);
                double curTP    = pos.optDouble("take_profit_trigger", 0);
                double curSL    = pos.optDouble("stop_loss_trigger", 0);

                boolean isOpen = posQty != 0 || pos.optDouble("locked_margin", 0) > 0 || avgPrice > 0;
                if (!isOpen || pair.isEmpty()) continue;
                stillOpen.add(pair);

                if (avgPrice <= 0 || curTP <= 0 || curSL <= 0) {
                    // No TP/SL yet on this position — the entry-scan's own
                    // safety sweep (ensureTpSlForOpenPositions) handles this.
                    continue;
                }

                TrailState state = trailStateMap.get(pair);
                if (state == null) {
                    // Shouldn't normally happen (startup reconciliation
                    // covers this), but guard anyway so trailing never
                    // silently skips a position forever.
                    boolean isLong = posQty >= 0;
                    state = new TrailState();
                    state.isLong = isLong;
                    state.entryPrice = avgPrice;
                    state.initialRisk = Math.abs(avgPrice - curSL);
                    state.initialReward = Math.abs(curTP - avgPrice);
                    trailStateMap.put(pair, state);
                }

                if (state.initialRisk <= 0 || state.initialReward <= 0) continue; // invalid state, skip safely

                double currentPrice = getLastPrice(pair);
                if (currentPrice <= 0) continue;

                double favorableMove = state.isLong
                        ? (currentPrice - state.entryPrice)
                        : (state.entryPrice - currentPrice);

                if (favorableMove <= 0) continue; // price hasn't moved in our favor at all

                // Continuous 1:1 trail: SL/TP tracked at a fixed distance
                // from the current price, using the ORIGINAL gaps from entry.
                double targetSL = state.isLong
                        ? currentPrice - state.initialRisk
                        : currentPrice + state.initialRisk;
                double targetTP = state.isLong
                        ? currentPrice + state.initialReward
                        : currentPrice - state.initialReward;

                // Only ever improve — never move SL/TP backward against the position.
                boolean slImproved = state.isLong ? targetSL > curSL : targetSL < curSL;
                boolean tpImproved = state.isLong ? targetTP > curTP : targetTP < curTP;
                if (!slImproved && !tpImproved) continue;

                double tick = getTickSize(pair);
                double newSL = slImproved ? roundToTick(targetSL, tick) : curSL;
                double newTP = tpImproved ? roundToTick(targetTP, tick) : curTP;

                // CRITICAL SAFETY GUARD — SL must never be <= 0 and must never
                // cross to the wrong side of current price. This is the guard
                // that was missing before and let SL collapse to 0.
                double minGap = Math.max(tick, currentPrice * 0.0005);
                boolean slInvalid = state.isLong
                        ? (newSL <= 0 || newSL >= currentPrice - minGap)
                        : (newSL <= currentPrice + minGap);
                if (slInvalid) {
                    System.out.println("[TRAIL] " + pair + " — computed SL invalid (" + newSL
                            + "), skipping this cycle");
                    continue;
                }

                // No-op guard: don't call the API if rounding produced no
                // real change (avoids spamming create_tpsl every cycle).
                if (Math.abs(newSL - curSL) < tick && Math.abs(newTP - curTP) < tick) {
                    continue;
                }

                String posId = pos.optString("id", null);
                if (posId == null) {
                    System.out.println("[TRAIL] " + pair + " — position id missing, skipping this cycle");
                    continue;
                }

                System.out.printf("[TRAIL] %s | price=%.6f | SL %.6f -> %.6f | TP %.6f -> %.6f%n",
                        pair, currentPrice, curSL, newSL, curTP, newTP);

                setTpSl(posId, newTP, newSL, pair);

                // Confirm the update actually landed on the exchange before
                // persisting — protects against a silent partial failure
                // (e.g. TP applied but SL rejected) leaving state out of sync.
                boolean confirmed = false;
                try {
                    TimeUnit.MILLISECONDS.sleep(1500);
                    JSONObject verify = findPosition(pair);
                    if (verify != null
                            && verify.optDouble("stop_loss_trigger", 0) > 0
                            && verify.optDouble("take_profit_trigger", 0) > 0) {
                        confirmed = true;
                    }
                } catch (Exception ignored) {}

                if (!confirmed) {
                    System.out.println("[TRAIL] WARNING: " + pair
                            + " — SL/TP update could not be confirmed on exchange, will retry next cycle");
                    continue; // don't advance state; next cycle will try again
                }

                saveTrailState();
            }

            // Drop state for anything that closed since the last check.
            if (trailStateMap.keySet().retainAll(stillOpen)) {
                saveTrailState();
            }

        } catch (Exception e) {
            System.err.println("[TRAIL] trailOpenPositions error: " + e.getMessage());
        }
    }

    // =========================================================================
    // Entry scan — this is the ORIGINAL main() logic, unchanged, just renamed
    // so it can be invoked periodically from the continuous loop above.
    // =========================================================================
    private static void runEntryScan() {
        Set<String> active = getActivePositions();
        System.out.println("Active positions: " + active);

        if (active.size() >= MAX_OPEN_POSITIONS) {
            System.out.println("MAX_OPEN_POSITIONS (" + MAX_OPEN_POSITIONS +
                    ") already reached (" + active.size() + " open) — skipping scan entirely.");
            ensureTpSlForOpenPositions();
            return;
        }

        for (String pair : COINS_TO_TRADE) {
            try {
                if (active.size() >= MAX_OPEN_POSITIONS) {
                    System.out.println("MAX_OPEN_POSITIONS reached mid-scan — stopping.");
                    break;
                }
                if (active.contains(pair)) {
                    System.out.println("Skip " + pair + " — active position");
                    continue;
                }
                long lastTrade = lastTradeTime.getOrDefault(pair, 0L);
                if (System.currentTimeMillis() - lastTrade < COOLDOWN_MS) {
                    System.out.println("  Skip " + pair + " — cooldown active");
                    continue;
                }
                System.out.println("\n==== " + pair + " ====");

                JSONArray raw15m         = getCandlestickData(pair, "15", CANDLE_15M);
                JSONArray raw30m         = getCandlestickData(pair, "30", CANDLE_30M);
                JSONArray raw1hExtended  = getCandlestickData(pair, "60", HTF_1H_FETCH_COUNT);
                JSONArray raw1h          = lastN(raw1hExtended, CANDLE_1H);
                JSONArray raw2h          = aggregateCandles(raw1hExtended, 2);
                JSONArray raw4h          = aggregateCandles(raw1hExtended, 4);

                if (raw15m == null || raw15m.length() < EMA_MID + 5) {
                    System.out.println("  Insufficient 15m candles — skip"); continue;
                }
                if (raw30m == null || raw30m.length() < EMA_MID + 5) {
                    System.out.println("  Insufficient 30m candles — skip"); continue;
                }

                TFResult tf4h = analyzeTF(raw4h);
                if (!tf4h.valid) {
                    System.out.println("  [4H] insufficient data — skip"); continue;
                }
                System.out.printf("  [4H] ST=%s EMA9=%.6f EMA21=%.6f Price=%.6f → %s%n",
                        tf4h.stGreen ? "GREEN" : "RED", tf4h.ema9, tf4h.ema21, tf4h.price,
                        tf4h.bullish ? "BULLISH" : tf4h.bearish ? "BEARISH" : "NO CLEAR TREND");
                if (!tf4h.bullish && !tf4h.bearish) {
                    System.out.println("  4H FAIL — macro trend not clean — skip"); continue;
                }

                TFResult tf2h = analyzeTF(raw2h);
                if (!tf2h.valid) {
                    System.out.println("  [2H] insufficient data — skip"); continue;
                }
                System.out.printf("  [2H] ST=%s EMA9=%.6f EMA21=%.6f Price=%.6f → %s%n",
                        tf2h.stGreen ? "GREEN" : "RED", tf2h.ema9, tf2h.ema21, tf2h.price,
                        tf2h.bullish ? "BULLISH" : tf2h.bearish ? "BEARISH" : "NO CLEAR TREND");
                boolean tf2hMatches4h = (tf4h.bullish && tf2h.bullish) || (tf4h.bearish && tf2h.bearish);
                if (!tf2hMatches4h) {
                    System.out.println("  2H FAIL — disagrees with (or unclear vs) 4H macro trend — skip");
                    continue;
                }

                TFResult tf1h = analyzeTF(raw1h);
                if (!tf1h.valid) {
                    System.out.println("  [1H] insufficient data — skip"); continue;
                }
                System.out.printf("  [1H] ST=%s EMA9=%.6f EMA21=%.6f Price=%.6f → %s%n",
                        tf1h.stGreen ? "GREEN" : "RED", tf1h.ema9, tf1h.ema21, tf1h.price,
                        tf1h.bullish ? "BULLISH" : tf1h.bearish ? "BEARISH" : "NO CLEAR TREND");

                boolean trendUp;
                if (tf4h.bullish && tf2h.bullish && tf1h.bullish) {
                    trendUp = true;
                } else if (tf4h.bearish && tf2h.bearish && tf1h.bearish) {
                    trendUp = false;
                } else {
                    System.out.println("  1H DISAGREES (or unclear) with 4H/2H — no trade, wait — skip");
                    continue;
                }
                System.out.println("  4H+2H+1H OK — " + (trendUp ? "BULLISH" : "BEARISH") + " confirmed on all three");

                TFResult tf30m = analyzeTF(raw30m);
                if (!tf30m.valid) {
                    System.out.println("  [30M] insufficient data — skip"); continue;
                }
                System.out.printf("  [30M] ST=%s EMA9=%.6f EMA21=%.6f Price=%.6f → %s%n",
                        tf30m.stGreen ? "GREEN" : "RED", tf30m.ema9, tf30m.ema21, tf30m.price,
                        tf30m.bullish ? "BULLISH" : tf30m.bearish ? "BEARISH" : "NO CLEAR TREND");
                boolean tf30mAligned = trendUp ? tf30m.bullish : tf30m.bearish;
                if (!tf30mAligned) {
                    System.out.println("  30M FAIL — disagrees with (or unclear vs) 4H/2H/1H trend — skip");
                    continue;
                }
                System.out.println("  30M OK — aligned with higher timeframes");

                double[] cl15 = extractCloses(raw15m);
                double[] op15 = extractOpens(raw15m);
                double[] hi15 = extractHighs(raw15m);
                double[] lo15 = extractLows(raw15m);
                int n15 = cl15.length;

                double ema9_15  = calcEMA(cl15, EMA_FAST);
                double ema21_15 = calcEMA(cl15, EMA_MID);
                double atr15    = calcATR(hi15, lo15, cl15, ATR_PERIOD);
                boolean[] st15Series = calcSupertrend(hi15, lo15, cl15, ST_PERIOD, ST_MULTIPLIER);
                boolean stBull15 = st15Series[st15Series.length - 1];
                double[] stBands15 = calcSupertrendBands(hi15, lo15, cl15, ST_PERIOD, ST_MULTIPLIER);

                boolean tf15Aligned = trendUp
                        ? (stBull15 && ema9_15 > ema21_15)
                        : (!stBull15 && ema9_15 < ema21_15);
                System.out.printf("  [15M] ST=%s EMA9=%.6f EMA21=%.6f → %s%n",
                        stBull15 ? "GREEN" : "RED", ema9_15, ema21_15,
                        tf15Aligned ? "ALIGNED" : "NOT ALIGNED");
                if (!tf15Aligned) {
                    System.out.println("  15M FAIL — not aligned with higher-timeframe direction — skip"); continue;
                }

                if (n15 < 3) { System.out.println("  Not enough 15m candles for entry check — skip"); continue; }
                double entryClose = cl15[n15 - 2], entryOpen = op15[n15 - 2];
                double entryHigh  = hi15[n15 - 2], entryLow  = lo15[n15 - 2];
                double prevClose  = cl15[n15 - 3], prevOpen  = op15[n15 - 3];

                double distEma9  = Math.abs(entryClose - ema9_15);
                double distEma21 = Math.abs(entryClose - ema21_15);
                double distSt    = trendUp
                        ? Math.abs(entryClose - stBands15[0])
                        : Math.abs(entryClose - stBands15[1]);
                double maxDist   = PULLBACK_MAX_ATR * atr15;
                boolean pullbackOk = distEma9 <= maxDist || distEma21 <= maxDist || distSt <= maxDist;
                System.out.printf("  [15M-Pullback] distEMA9=%.6f distEMA21=%.6f distST=%.6f maxAllowed=%.6f → %s%n",
                        distEma9, distEma21, distSt, maxDist, pullbackOk ? "PASS" : "FAIL");
                if (!pullbackOk) {
                    System.out.println("  15M FAIL — no valid pullback — skip"); continue;
                }

                boolean rejectionOk = trendUp
                        ? isBullishRejection(entryOpen, entryHigh, entryLow, entryClose, prevOpen, prevClose)
                        : isBearishRejection(entryOpen, entryHigh, entryLow, entryClose, prevOpen, prevClose);
                System.out.printf("  [15M-Rejection] %s candle → %s%n",
                        trendUp ? "Bullish" : "Bearish", rejectionOk ? "CONFIRMED" : "not present");
                if (!rejectionOk) {
                    System.out.println("  15M FAIL — no rejection candle confirmation — skip"); continue;
                }
                System.out.println("  15M OK — pullback + rejection candle confirmed");

                String side = trendUp ? "buy" : "sell";
                System.out.println("\n  ╔══════════════════════════════════════════════════╗");
                System.out.println("  ║  ALL CONDITIONS PASSED → " + side.toUpperCase() + " " + pair);
                System.out.println("  ╚══════════════════════════════════════════════════╝");

                double currentPrice = getLastPrice(pair);
                if (currentPrice <= 0) { System.out.println("  Invalid price — skip"); continue; }
                double qty = calcQuantity(currentPrice, pair);
                if (qty <= 0) { System.out.println("  Invalid qty — skip"); continue; }
                double tickSize = getTickSize(pair);

                System.out.printf("  Placing %s | price=%.6f | qty=%.4f | lev=%dx%n",
                        side.toUpperCase(), currentPrice, qty, LEVERAGE);

                JSONObject resp = placeFuturesOrder(side, pair, qty, LEVERAGE,
                        "email_notification", "isolated", "INR", currentPrice);
                if (resp == null || !resp.has("id")) {
                    System.out.println("  Order failed: " + resp); continue;
                }
                System.out.println("  Order placed! id=" + resp.getString("id"));
                lastTradeTime.put(pair, System.currentTimeMillis());

                double entry = getEntryPrice(pair, resp.getString("id"));
                if (entry <= 0) {
                    System.out.println("  Could not confirm entry within window — TP/SL will be handled by end-of-scan safety sweep");
                    active.add(pair);
                    continue;
                }
                System.out.printf("  Entry confirmed: %.6f%n", entry);

                double[] slTp = computeSlTp(trendUp, entry, tf2h, tickSize);
                double[] clamped = sanityClampSlTp(trendUp, entry, slTp[0], slTp[1], tickSize);
                double slPrice = clamped[0], tpPrice = clamped[1];
                double slPct = Math.abs(entry - slPrice) / entry * 100;
                double tpPct = Math.abs(tpPrice - entry) / entry * 100;
                System.out.printf("  SL=%.6f (%.2f%%) | TP=%.6f (%.2f%%) | R:R target=%.1f%n",
                        slPrice, slPct, tpPrice, tpPct, RR_TARGET);

                String posId = getPositionId(pair);
                if (posId != null) {
                    boolean confirmed = setTpSlWithRetry(posId, tpPrice, slPrice, pair);
                    if (confirmed) {
                        // Seed the trailing state for this fresh position.
                        TrailState state = new TrailState();
                        state.isLong = trendUp;
                        state.entryPrice = entry;
                        state.initialRisk = Math.abs(entry - slPrice);
                        state.initialReward = Math.abs(tpPrice - entry);
                        trailStateMap.put(pair, state);
                        saveTrailState();
                    }
                } else {
                    System.out.println("  Position ID not found after retries — TP/SL will be handled by end-of-scan safety sweep");
                }

                active.add(pair);

            } catch (Exception e) {
                System.err.println("Error on " + pair + ": " + e.getMessage());
            }
        }
        System.out.println("\n=== Scan complete ===");
        ensureTpSlForOpenPositions();
    }

    private static void ensureTpSlForOpenPositions() {
        try {
            Set<String> stillOpen = getActivePositions();
            for (String pair : stillOpen) {
                JSONObject pos = findPosition(pair);
                if (pos == null) continue;
                double avgPrice = pos.optDouble("avg_price", 0);
                double tpTrig   = pos.optDouble("take_profit_trigger", 0);
                double slTrig   = pos.optDouble("stop_loss_trigger", 0);
                if (avgPrice <= 0) continue;
                if (tpTrig > 0 && slTrig > 0) continue;

                System.out.println("  [SWEEP] " + pair + " missing TP/SL — computing fallback protection...");
                JSONArray raw1hExtended = getCandlestickData(pair, "60", HTF_1H_FETCH_COUNT);
                JSONArray raw2h = aggregateCandles(raw1hExtended, 2);
                TFResult tf2h = analyzeTF(raw2h);
                if (!tf2h.valid) {
                    System.out.println("  [SWEEP] insufficient 2H data for " + pair + " — will retry next run");
                    continue;
                }

                double posQty = pos.optDouble("active_pos", 0);
                boolean isLong = posQty >= 0;

                double tick = getTickSize(pair);
                double[] slTp = computeSlTp(isLong, avgPrice, tf2h, tick);
                double[] clamped = sanityClampSlTp(isLong, avgPrice, slTp[0], slTp[1], tick);
                double sl = clamped[0], tp = clamped[1];

                String posId = pos.optString("id", null);
                if (posId != null) {
                    System.out.printf("  [SWEEP] %s fallback SL=%.6f TP=%.6f (R:R target=%.1f)%n", pair, sl, tp, RR_TARGET);
                    boolean confirmed = setTpSlWithRetry(posId, tp, sl, pair);
                    if (confirmed) {
                        // Seed trailing state here too, since this is also a
                        // "first time TP/SL is set" moment.
                        TrailState state = new TrailState();
                        state.isLong = isLong;
                        state.entryPrice = avgPrice;
                        state.initialRisk = Math.abs(avgPrice - sl);
                        state.initialReward = Math.abs(tp - avgPrice);
                        trailStateMap.put(pair, state);
                        saveTrailState();
                    }
                } else {
                    System.out.println("  [SWEEP] " + pair + " — position ID missing, cannot set TP/SL");
                }
            }
        } catch (Exception e) {
            System.err.println("ensureTpSlForOpenPositions: " + e.getMessage());
        }
    }

    private static boolean setTpSlWithRetry(String posId, double tp, double sl, String pair) {
        for (int attempt = 1; attempt <= TPSL_MAX_RETRIES; attempt++) {
            setTpSl(posId, tp, sl, pair);
            try {
                TimeUnit.MILLISECONDS.sleep(TPSL_RETRY_DELAY_MS);
            } catch (InterruptedException ignored) {}
            try {
                JSONObject pos = findPosition(pair);
                if (pos != null && pos.optDouble("take_profit_trigger", 0) > 0
                        && pos.optDouble("stop_loss_trigger", 0) > 0) {
                    System.out.println("  TP/SL confirmed set on attempt " + attempt + " for " + pair);
                    return true;
                }
            } catch (Exception ignored) {}
            System.out.println("  TP/SL not confirmed yet (attempt " + attempt + "/" + TPSL_MAX_RETRIES + ") for " + pair + " — retrying...");
        }
        System.out.println("  WARNING: TP/SL could not be confirmed after " + TPSL_MAX_RETRIES + " attempts for " + pair
                + " — will be retried by the next scan's safety sweep");
        return false;
    }

    private static JSONArray lastN(JSONArray arr, int n) {
        if (arr == null) return null;
        int len = arr.length();
        if (len <= n) return arr;
        JSONArray out = new JSONArray();
        for (int i = len - n; i < len; i++) out.put(arr.getJSONObject(i));
        return out;
    }

    private static double[] calcSupertrendBands(double[] hi, double[] lo, double[] cl,
                                                 int period, double multiplier) {
        int n = cl.length;
        if (n < period + 1) return new double[]{cl[n-1] * 0.97, cl[n-1] * 1.03};
        double[] atrArr    = calcATRSeries(hi, lo, cl, period);
        double[] upperBand = new double[n];
        double[] lowerBand = new double[n];
        for (int i = period; i < n; i++) {
            double hl2        = (hi[i] + lo[i]) / 2.0;
            double basicUpper = hl2 + multiplier * atrArr[i];
            double basicLower = hl2 - multiplier * atrArr[i];
            if (i == period) {
                upperBand[i] = basicUpper;
                lowerBand[i] = basicLower;
            } else {
                upperBand[i] = (basicUpper < upperBand[i-1] || cl[i-1] > upperBand[i-1])
                        ? basicUpper : upperBand[i-1];
                lowerBand[i] = (basicLower > lowerBand[i-1] || cl[i-1] < lowerBand[i-1])
                        ? basicLower : lowerBand[i-1];
            }
        }
        return new double[]{lowerBand[n-1], upperBand[n-1]};
    }

    private static boolean[] calcSupertrend(double[] hi, double[] lo, double[] cl,
                                             int period, double multiplier) {
        int n = cl.length;
        boolean[] bullish = new boolean[n];
        if (n < period + 1) { Arrays.fill(bullish, true); return bullish; }
        double[] atrArr    = calcATRSeries(hi, lo, cl, period);
        double[] upperBand = new double[n];
        double[] lowerBand = new double[n];
        for (int i = period; i < n; i++) {
            double hl2        = (hi[i] + lo[i]) / 2.0;
            double basicUpper = hl2 + multiplier * atrArr[i];
            double basicLower = hl2 - multiplier * atrArr[i];
            if (i == period) {
                upperBand[i] = basicUpper; lowerBand[i] = basicLower;
            } else {
                upperBand[i] = (basicUpper < upperBand[i-1] || cl[i-1] > upperBand[i-1])
                        ? basicUpper : upperBand[i-1];
                lowerBand[i] = (basicLower > lowerBand[i-1] || cl[i-1] < lowerBand[i-1])
                        ? basicLower : lowerBand[i-1];
            }
            if (i == period) bullish[i] = cl[i] > (hi[i] + lo[i]) / 2.0;
            else bullish[i] = bullish[i-1] ? cl[i] >= lowerBand[i] : cl[i] > upperBand[i];
        }
        for (int i = 0; i < period; i++) bullish[i] = bullish[period];
        return bullish;
    }

    private static double[] calcATRSeries(double[] hi, double[] lo, double[] cl, int period) {
        int n = hi.length;
        double[] atr = new double[n];
        if (n < 2) return atr;
        double[] tr = new double[n];
        tr[0] = hi[0] - lo[0];
        for (int i = 1; i < n; i++)
            tr[i] = Math.max(hi[i] - lo[i],
                    Math.max(Math.abs(hi[i] - cl[i-1]), Math.abs(lo[i] - cl[i-1])));
        double sum = 0;
        for (int i = 0; i < period && i < n; i++) sum += tr[i];
        atr[period - 1] = sum / period;
        for (int i = period; i < n; i++) atr[i] = (atr[i-1] * (period - 1) + tr[i]) / period;
        for (int i = 0; i < period - 1; i++) atr[i] = atr[period - 1];
        return atr;
    }

    private static double calcATR(double[] hi, double[] lo, double[] cl, int period) {
        if (hi.length < period + 1) return 0;
        double[] tr = new double[hi.length];
        tr[0] = hi[0] - lo[0];
        for (int i = 1; i < hi.length; i++)
            tr[i] = Math.max(hi[i] - lo[i],
                    Math.max(Math.abs(hi[i] - cl[i-1]), Math.abs(lo[i] - cl[i-1])));
        double atr = 0;
        for (int i = 0; i < period; i++) atr += tr[i];
        atr /= period;
        for (int i = period; i < hi.length; i++) atr = (atr*(period-1)+tr[i])/period;
        return atr;
    }

    private static double calcEMA(double[] d, int period) {
        if (d.length < period) return 0;
        double k = 2.0 / (period + 1), ema = 0;
        for (int i = 0; i < period; i++) ema += d[i];
        ema /= period;
        for (int i = period; i < d.length; i++) ema = d[i] * k + ema * (1 - k);
        return ema;
    }

    // =========================================================================
    // FIX (v20.1) — real root cause of every order in the log failing with
    // {"code":400,"message":"Price should be divisible by 0.00001"}:
    //
    //   The old roundToTick() did `Math.round(price / tick) * tick` in raw
    //   double arithmetic. Most tick sizes (0.00001, 0.0001, ...) are NOT
    //   exactly representable in binary floating point, so the multiply-back
    //   step can land on something like 0.026099999999999998 instead of the
    //   intended 0.0261 — a value that is mathematically a clean multiple of
    //   the tick, but whose IEEE-754 double representation is not. When that
    //   double gets serialized into the order JSON, org.json prints the full
    //   (slightly-off) decimal, and CoinDCX's exact-string divisibility check
    //   on the exchange side rejects it. This is why EVERY single trade that
    //   passed all the strategy gates (XMR aside — that was a qty=0 margin
    //   issue, not this bug) still failed at order placement: KAIA, FLUX,
    //   CTSI all hit this exact error.
    //
    //   Fix: do the rounding in BigDecimal (exact decimal arithmetic, no
    //   binary rounding error) and normalize the result to the tick's own
    //   decimal scale. Use roundToTickBD() wherever the price is about to be
    //   put into a JSON payload sent to the exchange; the plain double
    //   version below is kept for internal math/logging only.
    // =========================================================================
    private static BigDecimal roundToTickBD(double price, double tick) {
        if (tick <= 0) return BigDecimal.valueOf(price);
        BigDecimal bdPrice = BigDecimal.valueOf(price);
        BigDecimal bdTick  = BigDecimal.valueOf(tick);
        BigDecimal multiples = bdPrice.divide(bdTick, 0, RoundingMode.HALF_UP);
        BigDecimal result = multiples.multiply(bdTick);
        // Normalize to the tick's own scale (e.g. tick=0.00001 -> 5 dp) so we
        // never emit trailing-zero noise or a different scale than the tick.
        return result.setScale(bdTick.scale(), RoundingMode.HALF_UP);
    }

    private static double roundToTick(double price, double tick) {
        if (tick <= 0) return price;
        return roundToTickBD(price, tick).doubleValue();
    }

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

    private static JSONArray getCandlestickData(String pair, String resolution, int count) {
        try {
            long minsPerBar;
            switch (resolution) {
                case "5":   minsPerBar = 5;   break;
                case "15":  minsPerBar = 15;  break;
                case "30":  minsPerBar = 30;  break;
                case "60":  minsPerBar = 60;  break;
                case "120": minsPerBar = 120; break;
                default:    minsPerBar = 15;  break;
            }
            long to   = Instant.now().getEpochSecond();
            long from = to - minsPerBar * 60L * count;
            String url = PUBLIC_API_URL + "/market_data/candlesticks"
                    + "?pair=" + pair + "&from=" + from + "&to=" + to
                    + "&resolution=" + resolution + "&pcode=f";
            HttpURLConnection conn = openGet(url);
            int code = conn.getResponseCode();
            if (code == 200) {
                JSONObject r = new JSONObject(readStream(conn.getInputStream()));
                if ("ok".equals(r.optString("s"))) return r.getJSONArray("data");
                System.err.println("  Candle s=" + r.optString("s") + " " + pair);
            } else {
                System.err.println("  Candle HTTP " + code + " " + pair);
            }
        } catch (Exception e) {
            System.err.println("  getCandlestickData(" + pair + "/" + resolution + "): " + e.getMessage());
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
        body.put("size", "100");
        body.put("margin_currency_short_name", new String[]{"INR", "USDT"});
        String resp = authPost(BASE_URL + "/exchange/v1/derivatives/futures/positions", body.toString());
        JSONArray arr = resp.startsWith("[")
                ? new JSONArray(resp) : new JSONArray().put(new JSONObject(resp));
        for (int i = 0; i < arr.length(); i++) {
            JSONObject p = arr.getJSONObject(i);
            if (pair.equals(p.optString("pair"))) return p;
        }
        return null;
    }

    private static double calcQuantity(double price, String pair) {
        double usdtInrRate = 98.0;
        // double qty = (MAX_MARGIN * LEVERAGE) / (price * usdtInrRate);
        double qty = MAX_MARGIN / (price * usdtInrRate);
        double finalQty = INTEGER_QTY_PAIRS.contains(pair)
                ? Math.floor(qty)
                : Math.floor(qty * 100) / 100.0;
        return Math.max(finalQty, 0);
    }

    public static double getLastPrice(String pair) {
        try {
            HttpURLConnection conn = openGet(
                    PUBLIC_API_URL + "/market_data/trade_history?pair=" + pair + "&limit=1");
            if (conn.getResponseCode() == 200) {
                String r = readStream(conn.getInputStream());
                return r.startsWith("[")
                        ? new JSONArray(r).getJSONObject(0).getDouble("p")
                        : new JSONObject(r).getDouble("p");
            }
        } catch (Exception e) {
            System.err.println("getLastPrice(" + pair + "): " + e.getMessage());
        }
        return 0;
    }

    public static JSONObject placeFuturesOrder(String side, String pair, double qty,
                                                     int lev, String notif,
                                                     String marginType, String marginCcy,
                                                     double currentPrice) {
        try {
            double rawLimitPrice = "buy".equalsIgnoreCase(side)
                    ? currentPrice * (1 + LIMIT_ORDER_BUFFER_PCT)
                    : currentPrice * (1 - LIMIT_ORDER_BUFFER_PCT);
            double tick = getTickSize(pair);
            // FIX: put the exact BigDecimal in the JSON, not a double, so the
            // exchange's tick-divisibility check never sees floating-point
            // noise like 0.026099999999999998.
            BigDecimal limitPriceBD = roundToTickBD(rawLimitPrice, tick);
            double limitPrice = limitPriceBD.doubleValue(); // for logging only

            JSONObject order = new JSONObject();
            order.put("side",                       side.toLowerCase());
            order.put("pair",                       pair);
            order.put("order_type",                 "limit_order");
            order.put("price",                      limitPriceBD);
            order.put("total_quantity",             qty);
            order.put("leverage",                   lev);
            order.put("notification",               notif);
            order.put("time_in_force",              "good_till_cancel");
            order.put("hidden",                     false);
            order.put("post_only",                  false);
            order.put("position_margin_type",       marginType);
            order.put("margin_currency_short_name", marginCcy);
            JSONObject body = new JSONObject();
            body.put("timestamp", Instant.now().toEpochMilli());
            body.put("order", order);
            String resp = authPost(
                    BASE_URL + "/exchange/v1/derivatives/futures/orders/create", body.toString());
            return resp.startsWith("[")
                    ? new JSONArray(resp).getJSONObject(0)
                    : new JSONObject(resp);
        } catch (Exception e) {
            System.err.println("placeFuturesOrder: " + e.getMessage());
            return null;
        }
    }

    public static void setTpSl(String posId, double tp, double sl, String pair) {
        try {
            double tick = getTickSize(pair);
            // FIX: same BigDecimal-exact rounding as the entry order — avoids
            // create_tpsl being silently rejected for the same
            // divisible-by-tick reason.
            BigDecimal rtp = roundToTickBD(tp, tick);
            BigDecimal rsl = roundToTickBD(sl, tick);
            JSONObject tpObj = new JSONObject();
            tpObj.put("stop_price",  rtp);
            tpObj.put("limit_price", rtp);
            tpObj.put("order_type",  "take_profit_market");
            JSONObject slObj = new JSONObject();
            slObj.put("stop_price",  rsl);
            slObj.put("limit_price", rsl);
            slObj.put("order_type",  "stop_market");
            JSONObject payload = new JSONObject();
            payload.put("timestamp",   Instant.now().toEpochMilli());
            payload.put("id",          posId);
            payload.put("take_profit", tpObj);
            payload.put("stop_loss",   slObj);
            String resp = authPost(
                    BASE_URL + "/exchange/v1/derivatives/futures/positions/create_tpsl",
                    payload.toString());
            JSONObject r = new JSONObject(resp);
            System.out.println(r.has("err_code_dcx") ? "  TP/SL error: " + r : "  TP/SL set successfully!");
        } catch (Exception e) {
            System.err.println("setTpSl: " + e.getMessage());
        }
    }

    public static String getPositionId(String pair) {
        for (int attempt = 1; attempt <= POSITION_ID_MAX_RETRIES; attempt++) {
            try {
                JSONObject p = findPosition(pair);
                if (p != null && p.has("id")) return p.getString("id");
            } catch (Exception e) {
                System.err.println("getPositionId attempt " + attempt + ": " + e.getMessage());
            }
            try {
                TimeUnit.MILLISECONDS.sleep(POSITION_ID_RETRY_DELAY_MS);
            } catch (InterruptedException ignored) {}
        }
        return null;
    }

    private static Set<String> getActivePositions() {
        Set<String> active = new HashSet<>();
        try {
            JSONObject body = new JSONObject();
            body.put("timestamp", Instant.now().toEpochMilli());
            body.put("page", "1");
            body.put("size", "100");
            body.put("margin_currency_short_name", new String[]{"INR", "USDT"});
            String resp = authPost(BASE_URL + "/exchange/v1/derivatives/futures/positions", body.toString());
            JSONArray arr = resp.startsWith("[")
                    ? new JSONArray(resp) : new JSONArray().put(new JSONObject(resp));
            System.out.println("=== Open Positions (" + arr.length() + ") ===");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject p    = arr.getJSONObject(i);
                String    pair  = p.optString("pair", "");
                boolean isActive = p.optDouble("active_pos", 0) > 0
                        || p.optDouble("locked_margin", 0) > 0
                        || p.optDouble("avg_price", 0) > 0
                        || p.optDouble("take_profit_trigger", 0) > 0
                        || p.optDouble("stop_loss_trigger", 0) > 0;
                if (isActive) {
                    System.out.printf("  %s | qty=%.2f | entry=%.6f | TP=%.4f | SL=%.4f%n",
                            pair, p.optDouble("active_pos", 0), p.optDouble("avg_price", 0),
                            p.optDouble("take_profit_trigger", 0), p.optDouble("stop_loss_trigger", 0));
                    active.add(pair);
                }
            }
        } catch (Exception e) {
            System.err.println("getActivePositions: " + e.getMessage());
        }
        return active;
    }

    private static HttpURLConnection openGet(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(10_000);
        c.setReadTimeout(10_000);
        return c;
    }

    private static String publicGet(String url) throws IOException {
        HttpURLConnection c = openGet(url);
        if (c.getResponseCode() == 200) return readStream(c.getInputStream());
        throw new IOException("HTTP " + c.getResponseCode() + " — " + url);
    }

    private static String authPost(String url, String json) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type",     "application/json");
        c.setRequestProperty("X-AUTH-APIKEY",    API_KEY);
        c.setRequestProperty("X-AUTH-SIGNATURE", sign(json));
        c.setConnectTimeout(10_000);
        c.setReadTimeout(10_000);
        c.setDoOutput(true);
        try (OutputStream os = c.getOutputStream()) {
            os.write(json.getBytes(StandardCharsets.UTF_8));
        }
        InputStream is = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
        return readStream(is);
    }

    private static String readStream(InputStream is) throws IOException {
        return new BufferedReader(new InputStreamReader(is))
                .lines().collect(Collectors.joining("\n"));
    }

    private static String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(API_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
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

    private static JSONArray aggregateCandles(JSONArray source, int groupSize) {
        if (source == null || source.length() < groupSize) return null;
        int n = source.length();
        int usableCount = (n / groupSize) * groupSize;
        int startIdx = n - usableCount;
        JSONArray result = new JSONArray();
        for (int i = startIdx; i < n; i += groupSize) {
            double open  = source.getJSONObject(i).getDouble("open");
            double close = source.getJSONObject(i + groupSize - 1).getDouble("close");
            double high  = Double.NEGATIVE_INFINITY;
            double low   = Double.POSITIVE_INFINITY;
            for (int j = i; j < i + groupSize; j++) {
                JSONObject c = source.getJSONObject(j);
                high = Math.max(high, c.getDouble("high"));
                low  = Math.min(low,  c.getDouble("low"));
            }
            JSONObject merged = new JSONObject();
            merged.put("open", open);
            merged.put("close", close);
            merged.put("high", high);
            merged.put("low", low);
            result.put(merged);
        }
        return result;
    }
}





























// import org.json.JSONArray; // ye mera working code hai oracle vm ka isko bas maine upar copy kiya hai changes krne ke liye
// import org.json.JSONObject;

// import javax.crypto.Mac;
// import javax.crypto.spec.SecretKeySpec;
// import java.math.BigDecimal;
// import java.math.RoundingMode;
// import java.io.*;
// import java.net.HttpURLConnection;
// import java.net.URL;
// import java.nio.charset.StandardCharsets;
// import java.nio.file.Files;
// import java.nio.file.Path;
// import java.nio.file.Paths;
// import java.time.Instant;
// import java.util.*;
// import java.util.concurrent.ConcurrentHashMap;
// import java.util.concurrent.TimeUnit;
// import java.util.stream.Collectors;
// import java.util.stream.Stream;

// public class CoinDCXFuturesTrader8C_BUY_SELL_NEW_LOGIC_THREE {

//     // =========================================================================
//     // API Configuration
//     // =========================================================================
//     private static final String API_KEY    = System.getenv("DELTA_API_KEY");
//     private static final String API_SECRET = System.getenv("DELTA_API_SECRET");
//     private static final String BASE_URL       = "https://api.coindcx.com";
//     private static final String PUBLIC_API_URL = "https://public.coindcx.com";

//     private static final double MAX_MARGIN = 2500.0;
//     private static final int    LEVERAGE   = 10;

//     private static final int MAX_ENTRY_PRICE_CHECKS = 20;
//     private static final int ENTRY_CHECK_DELAY_MS    = 1000;

//     private static final int  TPSL_MAX_RETRIES    = 3;
//     private static final long TPSL_RETRY_DELAY_MS = 2000L;

//     private static final long TICK_CACHE_TTL_MS = 3_600_000L;
//     private static final long COOLDOWN_MS       = 2 * 60 * 60 * 1000L;

//     private static final int MAX_OPEN_POSITIONS = 120;

//     private static final int  POSITION_ID_MAX_RETRIES = 5;
//     private static final long POSITION_ID_RETRY_DELAY_MS = 1500L;

//     private static final int EMA_FAST = 9;
//     private static final int EMA_MID  = 21;
//     private static final int ATR_PERIOD = 14;

//     private static final int    ST_PERIOD     = 10;
//     private static final double ST_MULTIPLIER = 3.0;

//     private static final double PULLBACK_MAX_ATR = 0.6;

//     private static final double SL_ATR_BUFFER   = 0.35;
//     private static final double SL_MAX_PERCENT  = 4.5;
//     private static final int    SWING_LOOKBACK       = 20;
//     private static final int    SWING_EXCLUDE_RECENT = 2;
//     private static final double SWING_EXTRA_BUFFER_ATR = 0.15;

//     private static final double RR_TARGET = 1.2;

//     private static final double LIMIT_ORDER_BUFFER_PCT = 0.001;

//     private static final int CANDLE_15M = 60;
//     private static final int CANDLE_30M = 100;
//     private static final int CANDLE_1H  = 100;
//     private static final int HTF_1H_FETCH_COUNT = 700;

//     // =========================================================================
//     // NEW — Trailing SL/TP configuration
//     // =========================================================================
//     // How many "R" (multiples of the original entry risk) price must move,
//     // favorably, before we shift SL and TP by that same amount again.
//     // Example from spec: entry=100, SL=97 -> 1R = 3. Price hits 103 (=+1R)
//     // -> SL 97->100 (+3), TP 105->108 (+3). Price hits 106 (=+2R) -> SL
//     // 100->103, TP 108->111. Set to 1.0 to match that behaviour exactly.
//     // Lower it (e.g. 0.5) to trail more aggressively/frequently.
//     private static final double TRAIL_STEP_R = 1.0;

//     // How often (ms) we re-check open positions for trailing. This is the
//     // "24x7 manual monitoring" replacement — keep this low (15-30s) so the
//     // bot reacts quickly, but not so low that we hammer the exchange API.
//     private static final long TRAIL_POLL_INTERVAL_MS = 20_000L;

//     // How often (ms) we re-run the full multi-timeframe entry scan across
//     // all ~250 pairs. This does NOT need to be as frequent as trailing —
//     // trend/structure changes are not a 15-second phenomenon.
//     private static final long ENTRY_SCAN_INTERVAL_MS = 3 * 60 * 1000L;

//     // Where trailing state is persisted so it survives VM reboots / JVM
//     // restarts. Mirrors the existing bot_state.json / last_trade_state.json
//     // pattern already used elsewhere in this project.
//     private static final String TRAIL_STATE_FILE = "trail_state.json";

//     private static final Map<String, JSONObject> instrumentCache = new ConcurrentHashMap<>();
//     private static long lastCacheUpdate = 0;
//     private static final Map<String, Long> lastTradeTime = new ConcurrentHashMap<>();

//     // pair -> trailing state (in-memory, backed by TRAIL_STATE_FILE on disk)
//     private static final Map<String, TrailState> trailStateMap = new ConcurrentHashMap<>();

//     private static final String[] COIN_SYMBOLS = {
//         "ETH", "SOL", "ZEC", "XRP", "DOGE", "BNB", "TAO", "1000PEPE", "ADA", "SUI",
//         "BCH", "LINK", "AVAX", "FIL", "OP", "NEAR", "TRX", "TRUMP", "ARB", "WLD",
//         "FET", "ETC", "AAVE", "WIF", "INJ", "TIA", "LTC", "ONDO", "ORDI", "TON",
//         "HBAR", "IMX", "ATOM", "RUNE", "KAS", "UNI", "ICP", "SEI", "PENDLE", "1000SHIB",
//         "1000BONK", "CRV", "JUP", "RENDER", "MKR", "LDO", "STX", "XLM", "PYTH", "VIRTUAL",
//         "APT", "SNX", "STRK", "NEO", "FTM", "CAKE", "1000FLOKI", "1000SATS", "OM", "FARTCOIN",
//         "GRT", "MINA", "COMP", "BLUR", "BRETT", "SAND", "EGLD", "XMR", "IOTA", "AI16Z",
//         "PNUT", "POPCAT", "ZRO", "MANA", "ETHFI", "VET", "ALGO", "ENS", "BOME", "MASK",
//         "GALA", "YFI", "CHZ", "GMX", "QNT", "POL", "MOODENG", "ZK", "ARKM", "THETA",
//         "MEW", "EIGEN", "MORPHO", "KAITO", "USUAL", "LAYER", "GOAT", "DOGS", "RSR", "PONKE",
//         "JTO", "CKB", "ZIL", "ROSE", "1INCH", "TWT", "KSM", "MAGIC", "GAS", "ACT",
//         "SUSHI", "TURBO", "1000LUNC", "BTCDOM", "S", "IP", "FLOW", "TRB", "QTUM", "KNC",
//         "KAIA", "CELO", "SSV", "BANANA", "TNSR", "AERO", "IO", "DEXE", "ARK", "XAI",
//         "DYM", "SAGA", "HOT", "LUNA2", "IOST", "RPL", "VANA", "DASH", "MANTA", "LRC",
//         "ANKR", "XTZ", "BAND", "SUPER", "FXS", "AKT", "NMR", "PIXEL", "LPT", "STORJ",
//         "ENJ", "LISTA", "ZETA", "RED", "AGLD", "GPS", "KAVA", "SXP", "ALPHA", "BIGTIME",
//         "COTI", "USTC", "BAT", "NFP", "ONE", "POLYX", "MOVR", "OMNI", "CELR", "RVN",
//         "GLM", "HIVE", "FLUX", "ZRX", "SFP", "ALICE", "ILV", "ARPA", "UMA", "DEGEN",
//         "XVS", "ACE", "ASTR", "CTSI", "CHR", "EDU", "PROM", "ALT", "C98", "SUN",
//         "WAXP", "ALPACA", "COOKIE", "JOE", "BNT", "SCRT", "VELODROME", "HOOK", "KMNO", "NTRN",
//         "RAYSOL", "PARTI", "MELANIA", "MYRO", "SHELL", "AUCTION", "SWELL", "HIGH", "WOO",
//         "COW", "MAVIA", "VTHO", "1000CAT", "MUBARAK", "LEVER", "SOLV", "ARC", "AVAAI", "KOMA",
//         "API3", "VOXEL", "CHESS", "SPELL", "1000WHY", "SKL", "GTC", "MTL", "BICO", "DENT",
//         "RLC", "PHB", "POWR", "LSK", "DEFI", "MAV", "REI", "ONG", "XVG", "COS",
//         "FORTH", "BEL", "MLN", "HEI", "GHST", "STEEM", "LOKA", "DIA", "TLM", "BMT",
//         "ALCH", "FUN", "1000CHEEMS", "1000RATS", "1000000MOG", "1MBABYDOGE", "1000XEC", "1000X", "PERP", "NKN",
//         "VINE", "RARE", "HFT", "AXL", "ACH", "ZEN", "PEOPLE", "AR", "CFX", "ID",
//         "METIS", "FIO", "CYBER"
//     };

//     private static final Set<String> INTEGER_QTY_PAIRS = Stream.of(COIN_SYMBOLS)
//             .flatMap(s -> Stream.of("B-" + s + "_USDT", s + "_USDT"))
//             .collect(Collectors.toCollection(HashSet::new));

//     private static final String[] COINS_TO_TRADE = Stream.of(COIN_SYMBOLS)
//             .map(s -> "B-" + s + "_USDT")
//             .toArray(String[]::new);

//     private static class TFResult {
//         boolean valid;
//         boolean bullish;
//         boolean bearish;
//         boolean stGreen;
//         double  ema9, ema21, price;
//         double  atr;
//         double[] stBands;
//         double[] hi, lo, cl;
//     }

//     // =========================================================================
//     // NEW — per-position trailing state
//     // =========================================================================
//     // initialRisk  = |entryPrice - originalSL| at the moment the trade was
//     //                opened (this is "1R" for that specific trade).
//     // lastTrailedR = how many whole TRAIL_STEP_R multiples have already been
//     //                applied. We only ever move forward (never re-trail a
//     //                level we've already passed), and we never move SL/TP
//     //                backwards against the position.
//     private static class TrailState {
//         boolean isLong;
//         double entryPrice;
//         double initialRisk;
//         double lastTrailedR; // in units of TRAIL_STEP_R already applied

//         JSONObject toJson() {
//             JSONObject o = new JSONObject();
//             o.put("isLong", isLong);
//             o.put("entryPrice", entryPrice);
//             o.put("initialRisk", initialRisk);
//             o.put("lastTrailedR", lastTrailedR);
//             return o;
//         }

//         static TrailState fromJson(JSONObject o) {
//             TrailState t = new TrailState();
//             t.isLong = o.optBoolean("isLong", true);
//             t.entryPrice = o.optDouble("entryPrice", 0);
//             t.initialRisk = o.optDouble("initialRisk", 0);
//             t.lastTrailedR = o.optDouble("lastTrailedR", 0);
//             return t;
//         }
//     }

//     // =========================================================================
//     // NEW — trail state persistence (mirrors bot_state.json pattern)
//     // =========================================================================
//     private static synchronized void loadTrailState() {
//         try {
//             Path p = Paths.get(TRAIL_STATE_FILE);
//             if (!Files.exists(p)) {
//                 System.out.println("[TRAIL] No existing " + TRAIL_STATE_FILE + " — starting fresh.");
//                 return;
//             }
//             String content = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
//             if (content.trim().isEmpty()) return;
//             JSONObject root = new JSONObject(content);
//             for (String pair : root.keySet()) {
//                 trailStateMap.put(pair, TrailState.fromJson(root.getJSONObject(pair)));
//             }
//             System.out.println("[TRAIL] Loaded trail state for " + trailStateMap.size() + " pair(s).");
//         } catch (Exception e) {
//             System.err.println("[TRAIL] loadTrailState failed (continuing with empty state): " + e.getMessage());
//         }
//     }

//     private static synchronized void saveTrailState() {
//         try {
//             JSONObject root = new JSONObject();
//             for (Map.Entry<String, TrailState> e : trailStateMap.entrySet()) {
//                 root.put(e.getKey(), e.getValue().toJson());
//             }
//             Files.write(Paths.get(TRAIL_STATE_FILE), root.toString(2).getBytes(StandardCharsets.UTF_8));
//         } catch (Exception e) {
//             System.err.println("[TRAIL] saveTrailState failed: " + e.getMessage());
//         }
//     }

//     private static TFResult analyzeTF(JSONArray candles) {
//         TFResult r = new TFResult();
//         if (candles == null || candles.length() < EMA_MID + ST_PERIOD + 5) {
//             r.valid = false;
//             return r;
//         }
//         double[] cl = extractCloses(candles);
//         double[] hi = extractHighs(candles);
//         double[] lo = extractLows(candles);

//         r.cl = cl; r.hi = hi; r.lo = lo;
//         r.ema9  = calcEMA(cl, EMA_FAST);
//         r.ema21 = calcEMA(cl, EMA_MID);
//         r.price = cl[cl.length - 1];
//         r.atr   = calcATR(hi, lo, cl, ATR_PERIOD);

//         boolean[] stSeries = calcSupertrend(hi, lo, cl, ST_PERIOD, ST_MULTIPLIER);
//         r.stGreen  = stSeries[stSeries.length - 1];
//         r.stBands  = calcSupertrendBands(hi, lo, cl, ST_PERIOD, ST_MULTIPLIER);
//         r.valid = true;

//         boolean priceAboveEmas = r.price > r.ema9 && r.price > r.ema21;
//         boolean priceBelowEmas = r.price < r.ema9 && r.price < r.ema21;
//         boolean priceAboveSt   = r.price > r.stBands[0];
//         boolean priceBelowSt   = r.price < r.stBands[1];

//         r.bullish = r.stGreen && priceAboveSt && (r.ema9 > r.ema21) && priceAboveEmas;
//         r.bearish = (!r.stGreen) && priceBelowSt && (r.ema9 < r.ema21) && priceBelowEmas;
//         return r;
//     }

//     private static boolean isBullishRejection(double open, double high, double low, double close,
//                                                double prevOpen, double prevClose) {
//         double range = high - low;
//         if (range <= 0) return false;
//         double body       = Math.abs(close - open);
//         double lowerWick   = Math.min(open, close) - low;
//         double upperWick   = high - Math.max(open, close);
//         boolean isBull     = close > open;

//         boolean hammer = body > 0 && lowerWick >= 2 * body && upperWick <= body * 0.6;

//         boolean bullishEngulf = prevClose < prevOpen && isBull
//                 && close >= prevOpen && open <= prevClose;

//         double bodyRatio = body / range;
//         double closePos  = (close - low) / range;
//         boolean strongBull = isBull && bodyRatio >= 0.55 && closePos >= 0.7;

//         return hammer || bullishEngulf || strongBull;
//     }

//     private static boolean isBearishRejection(double open, double high, double low, double close,
//                                                double prevOpen, double prevClose) {
//         double range = high - low;
//         if (range <= 0) return false;
//         double body       = Math.abs(close - open);
//         double lowerWick   = Math.min(open, close) - low;
//         double upperWick   = high - Math.max(open, close);
//         boolean isBear     = close < open;

//         boolean shootingStar = body > 0 && upperWick >= 2 * body && lowerWick <= body * 0.6;

//         boolean bearishEngulf = prevClose > prevOpen && isBear
//                 && open >= prevClose && close <= prevOpen;

//         double bodyRatio = body / range;
//         double closePos  = (close - low) / range;
//         boolean strongBear = isBear && bodyRatio >= 0.55 && closePos <= 0.3;

//         return shootingStar || bearishEngulf || strongBear;
//     }

//     private static double findSwingLow(double[] lo, int lookback, int excludeRecent) {
//         int n = lo.length;
//         int start = Math.max(0, n - lookback - excludeRecent);
//         int end   = Math.max(start, n - excludeRecent);
//         double sw = Double.POSITIVE_INFINITY;
//         for (int i = start; i < end; i++) sw = Math.min(sw, lo[i]);
//         return sw;
//     }

//     private static double findSwingHigh(double[] hi, int lookback, int excludeRecent) {
//         int n = hi.length;
//         int start = Math.max(0, n - lookback - excludeRecent);
//         int end   = Math.max(start, n - excludeRecent);
//         double sw = Double.NEGATIVE_INFINITY;
//         for (int i = start; i < end; i++) sw = Math.max(sw, hi[i]);
//         return sw;
//     }

//     private static double[] computeSlTp(boolean isLong, double entryPrice, TFResult tf2h, double tickSize) {
//         double sl, tp;
//         if (isLong) {
//             double raw = tf2h.stBands[0] - SL_ATR_BUFFER * tf2h.atr;
//             if (raw >= entryPrice) raw = entryPrice - (SL_ATR_BUFFER + 1.5) * tf2h.atr;
//             double hardFloor = entryPrice * (1 - SL_MAX_PERCENT / 100.0);

//             double swingLow = findSwingLow(tf2h.lo, SWING_LOOKBACK, SWING_EXCLUDE_RECENT);
//             if (swingLow < raw && swingLow > hardFloor) {
//                 raw = swingLow - SWING_EXTRA_BUFFER_ATR * tf2h.atr;
//             }

//             sl = Math.max(raw, hardFloor);
//             double risk = entryPrice - sl;
//             tp = entryPrice + RR_TARGET * risk;
//         } else {
//             double raw = tf2h.stBands[1] + SL_ATR_BUFFER * tf2h.atr;
//             if (raw <= entryPrice) raw = entryPrice + (SL_ATR_BUFFER + 1.5) * tf2h.atr;
//             double hardCeil = entryPrice * (1 + SL_MAX_PERCENT / 100.0);

//             double swingHigh = findSwingHigh(tf2h.hi, SWING_LOOKBACK, SWING_EXCLUDE_RECENT);
//             if (swingHigh > raw && swingHigh < hardCeil) {
//                 raw = swingHigh + SWING_EXTRA_BUFFER_ATR * tf2h.atr;
//             }

//             sl = Math.min(raw, hardCeil);
//             double risk = sl - entryPrice;
//             tp = entryPrice - RR_TARGET * risk;
//         }
//         sl = roundToTick(sl, tickSize);
//         tp = roundToTick(tp, tickSize);
//         return new double[]{sl, tp};
//     }

//     private static double[] sanityClampSlTp(boolean isLong, double entry, double sl, double tp, double tick) {
//         double minGap = Math.max(tick, entry * 0.0005);
//         if (isLong) {
//             if (sl >= entry - minGap) sl = entry - minGap;
//             if (tp <= entry + minGap) tp = entry + minGap;
//         } else {
//             if (sl <= entry + minGap) sl = entry + minGap;
//             if (tp >= entry - minGap) tp = entry - minGap;
//         }
//         sl = roundToTick(sl, tick);
//         tp = roundToTick(tp, tick);
//         return new double[]{sl, tp};
//     }

//     // =========================================================================
//     // NEW — Orchestrator. This is now a continuous 24x7 process instead of a
//     // single scan-and-exit run. Two independent timers:
//     //   1. Entry scan (new trades)      -> every ENTRY_SCAN_INTERVAL_MS
//     //   2. Trailing SL/TP (open trades) -> every TRAIL_POLL_INTERVAL_MS
//     // =========================================================================
//     public static void main(String[] args) {
//         System.out.println("=== Bot starting (continuous mode) ===");
//         loadTrailState();
//         initInstrumentCache();

//         // On startup, reconcile trail state against whatever is actually
//         // open on the exchange right now — covers VM reboot / JVM crash
//         // recovery so we never "lose track" of an existing position.
//         reconcileTrailStateOnStartup();

//         long lastEntryScan = 0L;

//         while (true) {
//             try {
//                 long now = System.currentTimeMillis();

//                 // ---- Trailing check (frequent) ----
//                 trailOpenPositions();

//                 // ---- Entry scan (less frequent) ----
//                 if (now - lastEntryScan >= ENTRY_SCAN_INTERVAL_MS) {
//                     runEntryScan();
//                     lastEntryScan = System.currentTimeMillis();
//                 }

//             } catch (Throwable t) {
//                 // Never let one bad cycle kill the whole 24x7 process.
//                 System.err.println("[MAIN-LOOP] Uncaught error, continuing: " + t.getMessage());
//                 t.printStackTrace();
//             }

//             try {
//                 TimeUnit.MILLISECONDS.sleep(TRAIL_POLL_INTERVAL_MS);
//             } catch (InterruptedException ignored) {
//                 Thread.currentThread().interrupt();
//                 break;
//             }
//         }
//     }

//     // =========================================================================
//     // NEW — reconcile persisted trail state with live exchange positions on
//     // startup. Any open position with no known trail state (fresh position,
//     // or state file lost) gets a state reconstructed from its CURRENT
//     // avg_price / stop_loss_trigger as a safe fallback. Any stale trail
//     // state entries for positions that are no longer open get removed.
//     // =========================================================================
//     private static void reconcileTrailStateOnStartup() {
//         try {
//             Set<String> active = getActivePositions();

//             // Drop trail state for anything no longer open.
//             trailStateMap.keySet().removeIf(pair -> !active.contains(pair));

//             for (String pair : active) {
//                 if (trailStateMap.containsKey(pair)) continue;
//                 JSONObject pos = findPosition(pair);
//                 if (pos == null) continue;
//                 double avgPrice = pos.optDouble("avg_price", 0);
//                 double slTrig   = pos.optDouble("stop_loss_trigger", 0);
//                 double posQty   = pos.optDouble("active_pos", 0);
//                 if (avgPrice <= 0 || slTrig <= 0) continue;

//                 TrailState t = new TrailState();
//                 t.isLong = posQty >= 0;
//                 t.entryPrice = avgPrice;
//                 t.initialRisk = Math.abs(avgPrice - slTrig);
//                 t.lastTrailedR = 0;
//                 trailStateMap.put(pair, t);
//                 System.out.println("[TRAIL] Reconstructed state on startup for " + pair
//                         + " (entry=" + avgPrice + ", risk=" + t.initialRisk + ")");
//             }
//             saveTrailState();
//         } catch (Exception e) {
//             System.err.println("reconcileTrailStateOnStartup: " + e.getMessage());
//         }
//     }

//     // =========================================================================
//     // NEW — the core trailing loop. For every open position: figure out how
//     // many whole TRAIL_STEP_R multiples of favorable movement have occurred
//     // since entry, and if that's more than what we've already applied, shift
//     // both SL and TP by the same amount (gap stays constant) and persist.
//     // =========================================================================
//     private static void trailOpenPositions() {
//         try {
//             JSONObject body = new JSONObject();
//             body.put("timestamp", Instant.now().toEpochMilli());
//             body.put("page", "1");
//             body.put("size", "100");
//             body.put("margin_currency_short_name", new String[]{"INR", "USDT"});
//             String resp = authPost(BASE_URL + "/exchange/v1/derivatives/futures/positions", body.toString());
//             JSONArray arr = resp.startsWith("[")
//                     ? new JSONArray(resp) : new JSONArray().put(new JSONObject(resp));

//             Set<String> stillOpen = new HashSet<>();

//             for (int i = 0; i < arr.length(); i++) {
//                 JSONObject pos = arr.getJSONObject(i);
//                 String pair = pos.optString("pair", "");
//                 double avgPrice = pos.optDouble("avg_price", 0);
//                 double posQty   = pos.optDouble("active_pos", 0);
//                 double curTP    = pos.optDouble("take_profit_trigger", 0);
//                 double curSL    = pos.optDouble("stop_loss_trigger", 0);

//                 boolean isOpen = posQty != 0 || pos.optDouble("locked_margin", 0) > 0 || avgPrice > 0;
//                 if (!isOpen || pair.isEmpty()) continue;
//                 stillOpen.add(pair);

//                 if (avgPrice <= 0 || curTP <= 0 || curSL <= 0) {
//                     // No TP/SL yet on this position — the entry-scan's own
//                     // safety sweep (ensureTpSlForOpenPositions) handles this.
//                     continue;
//                 }

//                 TrailState state = trailStateMap.get(pair);
//                 if (state == null) {
//                     // Shouldn't normally happen (startup reconciliation
//                     // covers this), but guard anyway so trailing never
//                     // silently skips a position forever.
//                     boolean isLong = posQty >= 0;
//                     state = new TrailState();
//                     state.isLong = isLong;
//                     state.entryPrice = avgPrice;
//                     state.initialRisk = Math.abs(avgPrice - curSL);
//                     state.lastTrailedR = 0;
//                     trailStateMap.put(pair, state);
//                 }

//                 if (state.initialRisk <= 0) continue; // avoid div-by-zero / nonsense

//                 double currentPrice = getLastPrice(pair);
//                 if (currentPrice <= 0) continue;

//                 double favorableMove = state.isLong
//                         ? (currentPrice - state.entryPrice)
//                         : (state.entryPrice - currentPrice);

//                 if (favorableMove <= 0) continue; // price hasn't moved in our favor at all

//                 double rGained = favorableMove / state.initialRisk;
//                 double stepsToApply = Math.floor(rGained / TRAIL_STEP_R) - Math.floor(state.lastTrailedR / TRAIL_STEP_R);

//                 if (stepsToApply < 1) continue; // not yet reached the next trail level

//                 double shiftAmount = state.initialRisk * TRAIL_STEP_R * stepsToApply;

//                 double newSL = state.isLong ? curSL + shiftAmount : curSL - shiftAmount;
//                 double newTP = state.isLong ? curTP + shiftAmount : curTP - shiftAmount;

//                 // Safety: never move SL backwards against the position.
//                 if (state.isLong && newSL < curSL) newSL = curSL;
//                 if (!state.isLong && newSL > curSL) newSL = curSL;

//                 double tick = getTickSize(pair);
//                 newSL = roundToTick(newSL, tick);
//                 newTP = roundToTick(newTP, tick);

//                 // No-op guard: don't call the API if rounding produced no
//                 // real change (avoids spamming create_tpsl every cycle).
//                 if (Math.abs(newSL - curSL) < tick && Math.abs(newTP - curTP) < tick) {
//                     continue;
//                 }

//                 String posId = pos.optString("id", null);
//                 if (posId == null) {
//                     System.out.println("[TRAIL] " + pair + " — position id missing, skipping this cycle");
//                     continue;
//                 }

//                 System.out.printf("[TRAIL] %s | price=%.6f | R gained=%.2f | SL %.6f -> %.6f | TP %.6f -> %.6f%n",
//                         pair, currentPrice, rGained, curSL, newSL, curTP, newTP);

//                 setTpSl(posId, newTP, newSL, pair);

//                 state.lastTrailedR = Math.floor(rGained / TRAIL_STEP_R) * TRAIL_STEP_R;
//                 saveTrailState();
//             }

//             // Drop state for anything that closed since the last check.
//             if (trailStateMap.keySet().retainAll(stillOpen)) {
//                 saveTrailState();
//             }

//         } catch (Exception e) {
//             System.err.println("[TRAIL] trailOpenPositions error: " + e.getMessage());
//         }
//     }

//     // =========================================================================
//     // Entry scan — this is the ORIGINAL main() logic, unchanged, just renamed
//     // so it can be invoked periodically from the new continuous loop above.
//     // =========================================================================
//     private static void runEntryScan() {
//         Set<String> active = getActivePositions();
//         System.out.println("Active positions: " + active);

//         if (active.size() >= MAX_OPEN_POSITIONS) {
//             System.out.println("MAX_OPEN_POSITIONS (" + MAX_OPEN_POSITIONS +
//                     ") already reached (" + active.size() + " open) — skipping scan entirely.");
//             ensureTpSlForOpenPositions();
//             return;
//         }

//         for (String pair : COINS_TO_TRADE) {
//             try {
//                 if (active.size() >= MAX_OPEN_POSITIONS) {
//                     System.out.println("MAX_OPEN_POSITIONS reached mid-scan — stopping.");
//                     break;
//                 }
//                 if (active.contains(pair)) {
//                     System.out.println("Skip " + pair + " — active position");
//                     continue;
//                 }
//                 long lastTrade = lastTradeTime.getOrDefault(pair, 0L);
//                 if (System.currentTimeMillis() - lastTrade < COOLDOWN_MS) {
//                     System.out.println("  Skip " + pair + " — cooldown active");
//                     continue;
//                 }
//                 System.out.println("\n==== " + pair + " ====");

//                 JSONArray raw15m         = getCandlestickData(pair, "15", CANDLE_15M);
//                 JSONArray raw30m         = getCandlestickData(pair, "30", CANDLE_30M);
//                 JSONArray raw1hExtended  = getCandlestickData(pair, "60", HTF_1H_FETCH_COUNT);
//                 JSONArray raw1h          = lastN(raw1hExtended, CANDLE_1H);
//                 JSONArray raw2h          = aggregateCandles(raw1hExtended, 2);
//                 JSONArray raw4h          = aggregateCandles(raw1hExtended, 4);

//                 if (raw15m == null || raw15m.length() < EMA_MID + 5) {
//                     System.out.println("  Insufficient 15m candles — skip"); continue;
//                 }
//                 if (raw30m == null || raw30m.length() < EMA_MID + 5) {
//                     System.out.println("  Insufficient 30m candles — skip"); continue;
//                 }

//                 TFResult tf4h = analyzeTF(raw4h);
//                 if (!tf4h.valid) {
//                     System.out.println("  [4H] insufficient data — skip"); continue;
//                 }
//                 System.out.printf("  [4H] ST=%s EMA9=%.6f EMA21=%.6f Price=%.6f → %s%n",
//                         tf4h.stGreen ? "GREEN" : "RED", tf4h.ema9, tf4h.ema21, tf4h.price,
//                         tf4h.bullish ? "BULLISH" : tf4h.bearish ? "BEARISH" : "NO CLEAR TREND");
//                 if (!tf4h.bullish && !tf4h.bearish) {
//                     System.out.println("  4H FAIL — macro trend not clean — skip"); continue;
//                 }

//                 TFResult tf2h = analyzeTF(raw2h);
//                 if (!tf2h.valid) {
//                     System.out.println("  [2H] insufficient data — skip"); continue;
//                 }
//                 System.out.printf("  [2H] ST=%s EMA9=%.6f EMA21=%.6f Price=%.6f → %s%n",
//                         tf2h.stGreen ? "GREEN" : "RED", tf2h.ema9, tf2h.ema21, tf2h.price,
//                         tf2h.bullish ? "BULLISH" : tf2h.bearish ? "BEARISH" : "NO CLEAR TREND");
//                 boolean tf2hMatches4h = (tf4h.bullish && tf2h.bullish) || (tf4h.bearish && tf2h.bearish);
//                 if (!tf2hMatches4h) {
//                     System.out.println("  2H FAIL — disagrees with (or unclear vs) 4H macro trend — skip");
//                     continue;
//                 }

//                 TFResult tf1h = analyzeTF(raw1h);
//                 if (!tf1h.valid) {
//                     System.out.println("  [1H] insufficient data — skip"); continue;
//                 }
//                 System.out.printf("  [1H] ST=%s EMA9=%.6f EMA21=%.6f Price=%.6f → %s%n",
//                         tf1h.stGreen ? "GREEN" : "RED", tf1h.ema9, tf1h.ema21, tf1h.price,
//                         tf1h.bullish ? "BULLISH" : tf1h.bearish ? "BEARISH" : "NO CLEAR TREND");

//                 boolean trendUp;
//                 if (tf4h.bullish && tf2h.bullish && tf1h.bullish) {
//                     trendUp = true;
//                 } else if (tf4h.bearish && tf2h.bearish && tf1h.bearish) {
//                     trendUp = false;
//                 } else {
//                     System.out.println("  1H DISAGREES (or unclear) with 4H/2H — no trade, wait — skip");
//                     continue;
//                 }
//                 System.out.println("  4H+2H+1H OK — " + (trendUp ? "BULLISH" : "BEARISH") + " confirmed on all three");

//                 TFResult tf30m = analyzeTF(raw30m);
//                 if (!tf30m.valid) {
//                     System.out.println("  [30M] insufficient data — skip"); continue;
//                 }
//                 System.out.printf("  [30M] ST=%s EMA9=%.6f EMA21=%.6f Price=%.6f → %s%n",
//                         tf30m.stGreen ? "GREEN" : "RED", tf30m.ema9, tf30m.ema21, tf30m.price,
//                         tf30m.bullish ? "BULLISH" : tf30m.bearish ? "BEARISH" : "NO CLEAR TREND");
//                 boolean tf30mAligned = trendUp ? tf30m.bullish : tf30m.bearish;
//                 if (!tf30mAligned) {
//                     System.out.println("  30M FAIL — disagrees with (or unclear vs) 4H/2H/1H trend — skip");
//                     continue;
//                 }
//                 System.out.println("  30M OK — aligned with higher timeframes");

//                 double[] cl15 = extractCloses(raw15m);
//                 double[] op15 = extractOpens(raw15m);
//                 double[] hi15 = extractHighs(raw15m);
//                 double[] lo15 = extractLows(raw15m);
//                 int n15 = cl15.length;

//                 double ema9_15  = calcEMA(cl15, EMA_FAST);
//                 double ema21_15 = calcEMA(cl15, EMA_MID);
//                 double atr15    = calcATR(hi15, lo15, cl15, ATR_PERIOD);
//                 boolean[] st15Series = calcSupertrend(hi15, lo15, cl15, ST_PERIOD, ST_MULTIPLIER);
//                 boolean stBull15 = st15Series[st15Series.length - 1];
//                 double[] stBands15 = calcSupertrendBands(hi15, lo15, cl15, ST_PERIOD, ST_MULTIPLIER);

//                 boolean tf15Aligned = trendUp
//                         ? (stBull15 && ema9_15 > ema21_15)
//                         : (!stBull15 && ema9_15 < ema21_15);
//                 System.out.printf("  [15M] ST=%s EMA9=%.6f EMA21=%.6f → %s%n",
//                         stBull15 ? "GREEN" : "RED", ema9_15, ema21_15,
//                         tf15Aligned ? "ALIGNED" : "NOT ALIGNED");
//                 if (!tf15Aligned) {
//                     System.out.println("  15M FAIL — not aligned with higher-timeframe direction — skip"); continue;
//                 }

//                 if (n15 < 3) { System.out.println("  Not enough 15m candles for entry check — skip"); continue; }
//                 double entryClose = cl15[n15 - 2], entryOpen = op15[n15 - 2];
//                 double entryHigh  = hi15[n15 - 2], entryLow  = lo15[n15 - 2];
//                 double prevClose  = cl15[n15 - 3], prevOpen  = op15[n15 - 3];

//                 double distEma9  = Math.abs(entryClose - ema9_15);
//                 double distEma21 = Math.abs(entryClose - ema21_15);
//                 double distSt    = trendUp
//                         ? Math.abs(entryClose - stBands15[0])
//                         : Math.abs(entryClose - stBands15[1]);
//                 double maxDist   = PULLBACK_MAX_ATR * atr15;
//                 boolean pullbackOk = distEma9 <= maxDist || distEma21 <= maxDist || distSt <= maxDist;
//                 System.out.printf("  [15M-Pullback] distEMA9=%.6f distEMA21=%.6f distST=%.6f maxAllowed=%.6f → %s%n",
//                         distEma9, distEma21, distSt, maxDist, pullbackOk ? "PASS" : "FAIL");
//                 if (!pullbackOk) {
//                     System.out.println("  15M FAIL — no valid pullback — skip"); continue;
//                 }

//                 boolean rejectionOk = trendUp
//                         ? isBullishRejection(entryOpen, entryHigh, entryLow, entryClose, prevOpen, prevClose)
//                         : isBearishRejection(entryOpen, entryHigh, entryLow, entryClose, prevOpen, prevClose);
//                 System.out.printf("  [15M-Rejection] %s candle → %s%n",
//                         trendUp ? "Bullish" : "Bearish", rejectionOk ? "CONFIRMED" : "not present");
//                 if (!rejectionOk) {
//                     System.out.println("  15M FAIL — no rejection candle confirmation — skip"); continue;
//                 }
//                 System.out.println("  15M OK — pullback + rejection candle confirmed");

//                 String side = trendUp ? "buy" : "sell";
//                 System.out.println("\n  ╔══════════════════════════════════════════════════╗");
//                 System.out.println("  ║  ALL CONDITIONS PASSED → " + side.toUpperCase() + " " + pair);
//                 System.out.println("  ╚══════════════════════════════════════════════════╝");

//                 double currentPrice = getLastPrice(pair);
//                 if (currentPrice <= 0) { System.out.println("  Invalid price — skip"); continue; }
//                 double qty = calcQuantity(currentPrice, pair);
//                 if (qty <= 0) { System.out.println("  Invalid qty — skip"); continue; }
//                 double tickSize = getTickSize(pair);

//                 System.out.printf("  Placing %s | price=%.6f | qty=%.4f | lev=%dx%n",
//                         side.toUpperCase(), currentPrice, qty, LEVERAGE);

//                 JSONObject resp = placeFuturesOrder(side, pair, qty, LEVERAGE,
//                         "email_notification", "isolated", "INR", currentPrice);
//                 if (resp == null || !resp.has("id")) {
//                     System.out.println("  Order failed: " + resp); continue;
//                 }
//                 System.out.println("  Order placed! id=" + resp.getString("id"));
//                 lastTradeTime.put(pair, System.currentTimeMillis());

//                 double entry = getEntryPrice(pair, resp.getString("id"));
//                 if (entry <= 0) {
//                     System.out.println("  Could not confirm entry within window — TP/SL will be handled by end-of-scan safety sweep");
//                     active.add(pair);
//                     continue;
//                 }
//                 System.out.printf("  Entry confirmed: %.6f%n", entry);

//                 double[] slTp = computeSlTp(trendUp, entry, tf2h, tickSize);
//                 double[] clamped = sanityClampSlTp(trendUp, entry, slTp[0], slTp[1], tickSize);
//                 double slPrice = clamped[0], tpPrice = clamped[1];
//                 double slPct = Math.abs(entry - slPrice) / entry * 100;
//                 double tpPct = Math.abs(tpPrice - entry) / entry * 100;
//                 System.out.printf("  SL=%.6f (%.2f%%) | TP=%.6f (%.2f%%) | R:R target=%.1f%n",
//                         slPrice, slPct, tpPrice, tpPct, RR_TARGET);

//                 String posId = getPositionId(pair);
//                 if (posId != null) {
//                     boolean confirmed = setTpSlWithRetry(posId, tpPrice, slPrice, pair);
//                     if (confirmed) {
//                         // NEW — seed the trailing state for this fresh position.
//                         TrailState state = new TrailState();
//                         state.isLong = trendUp;
//                         state.entryPrice = entry;
//                         state.initialRisk = Math.abs(entry - slPrice);
//                         state.lastTrailedR = 0;
//                         trailStateMap.put(pair, state);
//                         saveTrailState();
//                     }
//                 } else {
//                     System.out.println("  Position ID not found after retries — TP/SL will be handled by end-of-scan safety sweep");
//                 }

//                 active.add(pair);

//             } catch (Exception e) {
//                 System.err.println("Error on " + pair + ": " + e.getMessage());
//             }
//         }
//         System.out.println("\n=== Scan complete ===");
//         ensureTpSlForOpenPositions();
//     }

//     private static void ensureTpSlForOpenPositions() {
//         try {
//             Set<String> stillOpen = getActivePositions();
//             for (String pair : stillOpen) {
//                 JSONObject pos = findPosition(pair);
//                 if (pos == null) continue;
//                 double avgPrice = pos.optDouble("avg_price", 0);
//                 double tpTrig   = pos.optDouble("take_profit_trigger", 0);
//                 double slTrig   = pos.optDouble("stop_loss_trigger", 0);
//                 if (avgPrice <= 0) continue;
//                 if (tpTrig > 0 && slTrig > 0) continue;

//                 System.out.println("  [SWEEP] " + pair + " missing TP/SL — computing fallback protection...");
//                 JSONArray raw1hExtended = getCandlestickData(pair, "60", HTF_1H_FETCH_COUNT);
//                 JSONArray raw2h = aggregateCandles(raw1hExtended, 2);
//                 TFResult tf2h = analyzeTF(raw2h);
//                 if (!tf2h.valid) {
//                     System.out.println("  [SWEEP] insufficient 2H data for " + pair + " — will retry next run");
//                     continue;
//                 }

//                 double posQty = pos.optDouble("active_pos", 0);
//                 boolean isLong = posQty >= 0;

//                 double tick = getTickSize(pair);
//                 double[] slTp = computeSlTp(isLong, avgPrice, tf2h, tick);
//                 double[] clamped = sanityClampSlTp(isLong, avgPrice, slTp[0], slTp[1], tick);
//                 double sl = clamped[0], tp = clamped[1];

//                 String posId = pos.optString("id", null);
//                 if (posId != null) {
//                     System.out.printf("  [SWEEP] %s fallback SL=%.6f TP=%.6f (R:R target=%.1f)%n", pair, sl, tp, RR_TARGET);
//                     boolean confirmed = setTpSlWithRetry(posId, tp, sl, pair);
//                     if (confirmed) {
//                         // NEW — seed trailing state here too, since this is
//                         // also a "first time TP/SL is set" moment.
//                         TrailState state = new TrailState();
//                         state.isLong = isLong;
//                         state.entryPrice = avgPrice;
//                         state.initialRisk = Math.abs(avgPrice - sl);
//                         state.lastTrailedR = 0;
//                         trailStateMap.put(pair, state);
//                         saveTrailState();
//                     }
//                 } else {
//                     System.out.println("  [SWEEP] " + pair + " — position ID missing, cannot set TP/SL");
//                 }
//             }
//         } catch (Exception e) {
//             System.err.println("ensureTpSlForOpenPositions: " + e.getMessage());
//         }
//     }

//     private static boolean setTpSlWithRetry(String posId, double tp, double sl, String pair) {
//         for (int attempt = 1; attempt <= TPSL_MAX_RETRIES; attempt++) {
//             setTpSl(posId, tp, sl, pair);
//             try {
//                 TimeUnit.MILLISECONDS.sleep(TPSL_RETRY_DELAY_MS);
//             } catch (InterruptedException ignored) {}
//             try {
//                 JSONObject pos = findPosition(pair);
//                 if (pos != null && pos.optDouble("take_profit_trigger", 0) > 0
//                         && pos.optDouble("stop_loss_trigger", 0) > 0) {
//                     System.out.println("  TP/SL confirmed set on attempt " + attempt + " for " + pair);
//                     return true;
//                 }
//             } catch (Exception ignored) {}
//             System.out.println("  TP/SL not confirmed yet (attempt " + attempt + "/" + TPSL_MAX_RETRIES + ") for " + pair + " — retrying...");
//         }
//         System.out.println("  WARNING: TP/SL could not be confirmed after " + TPSL_MAX_RETRIES + " attempts for " + pair
//                 + " — will be retried by the next scan's safety sweep");
//         return false;
//     }

//     private static JSONArray lastN(JSONArray arr, int n) {
//         if (arr == null) return null;
//         int len = arr.length();
//         if (len <= n) return arr;
//         JSONArray out = new JSONArray();
//         for (int i = len - n; i < len; i++) out.put(arr.getJSONObject(i));
//         return out;
//     }

//     private static double[] calcSupertrendBands(double[] hi, double[] lo, double[] cl,
//                                                  int period, double multiplier) {
//         int n = cl.length;
//         if (n < period + 1) return new double[]{cl[n-1] * 0.97, cl[n-1] * 1.03};
//         double[] atrArr    = calcATRSeries(hi, lo, cl, period);
//         double[] upperBand = new double[n];
//         double[] lowerBand = new double[n];
//         for (int i = period; i < n; i++) {
//             double hl2        = (hi[i] + lo[i]) / 2.0;
//             double basicUpper = hl2 + multiplier * atrArr[i];
//             double basicLower = hl2 - multiplier * atrArr[i];
//             if (i == period) {
//                 upperBand[i] = basicUpper;
//                 lowerBand[i] = basicLower;
//             } else {
//                 upperBand[i] = (basicUpper < upperBand[i-1] || cl[i-1] > upperBand[i-1])
//                         ? basicUpper : upperBand[i-1];
//                 lowerBand[i] = (basicLower > lowerBand[i-1] || cl[i-1] < lowerBand[i-1])
//                         ? basicLower : lowerBand[i-1];
//             }
//         }
//         return new double[]{lowerBand[n-1], upperBand[n-1]};
//     }

//     private static boolean[] calcSupertrend(double[] hi, double[] lo, double[] cl,
//                                              int period, double multiplier) {
//         int n = cl.length;
//         boolean[] bullish = new boolean[n];
//         if (n < period + 1) { Arrays.fill(bullish, true); return bullish; }
//         double[] atrArr    = calcATRSeries(hi, lo, cl, period);
//         double[] upperBand = new double[n];
//         double[] lowerBand = new double[n];
//         for (int i = period; i < n; i++) {
//             double hl2        = (hi[i] + lo[i]) / 2.0;
//             double basicUpper = hl2 + multiplier * atrArr[i];
//             double basicLower = hl2 - multiplier * atrArr[i];
//             if (i == period) {
//                 upperBand[i] = basicUpper; lowerBand[i] = basicLower;
//             } else {
//                 upperBand[i] = (basicUpper < upperBand[i-1] || cl[i-1] > upperBand[i-1])
//                         ? basicUpper : upperBand[i-1];
//                 lowerBand[i] = (basicLower > lowerBand[i-1] || cl[i-1] < lowerBand[i-1])
//                         ? basicLower : lowerBand[i-1];
//             }
//             if (i == period) bullish[i] = cl[i] > (hi[i] + lo[i]) / 2.0;
//             else bullish[i] = bullish[i-1] ? cl[i] >= lowerBand[i] : cl[i] > upperBand[i];
//         }
//         for (int i = 0; i < period; i++) bullish[i] = bullish[period];
//         return bullish;
//     }

//     private static double[] calcATRSeries(double[] hi, double[] lo, double[] cl, int period) {
//         int n = hi.length;
//         double[] atr = new double[n];
//         if (n < 2) return atr;
//         double[] tr = new double[n];
//         tr[0] = hi[0] - lo[0];
//         for (int i = 1; i < n; i++)
//             tr[i] = Math.max(hi[i] - lo[i],
//                     Math.max(Math.abs(hi[i] - cl[i-1]), Math.abs(lo[i] - cl[i-1])));
//         double sum = 0;
//         for (int i = 0; i < period && i < n; i++) sum += tr[i];
//         atr[period - 1] = sum / period;
//         for (int i = period; i < n; i++) atr[i] = (atr[i-1] * (period - 1) + tr[i]) / period;
//         for (int i = 0; i < period - 1; i++) atr[i] = atr[period - 1];
//         return atr;
//     }

//     private static double calcATR(double[] hi, double[] lo, double[] cl, int period) {
//         if (hi.length < period + 1) return 0;
//         double[] tr = new double[hi.length];
//         tr[0] = hi[0] - lo[0];
//         for (int i = 1; i < hi.length; i++)
//             tr[i] = Math.max(hi[i] - lo[i],
//                     Math.max(Math.abs(hi[i] - cl[i-1]), Math.abs(lo[i] - cl[i-1])));
//         double atr = 0;
//         for (int i = 0; i < period; i++) atr += tr[i];
//         atr /= period;
//         for (int i = period; i < hi.length; i++) atr = (atr*(period-1)+tr[i])/period;
//         return atr;
//     }

//     private static double calcEMA(double[] d, int period) {
//         if (d.length < period) return 0;
//         double k = 2.0 / (period + 1), ema = 0;
//         for (int i = 0; i < period; i++) ema += d[i];
//         ema /= period;
//         for (int i = period; i < d.length; i++) ema = d[i] * k + ema * (1 - k);
//         return ema;
//     }

//     // =========================================================================
//     // FIX (v20.1) — real root cause of every order in the log failing with
//     // {"code":400,"message":"Price should be divisible by 0.00001"}:
//     //
//     //   The old roundToTick() did `Math.round(price / tick) * tick` in raw
//     //   double arithmetic. Most tick sizes (0.00001, 0.0001, ...) are NOT
//     //   exactly representable in binary floating point, so the multiply-back
//     //   step can land on something like 0.026099999999999998 instead of the
//     //   intended 0.0261 — a value that is mathematically a clean multiple of
//     //   the tick, but whose IEEE-754 double representation is not. When that
//     //   double gets serialized into the order JSON, org.json prints the full
//     //   (slightly-off) decimal, and CoinDCX's exact-string divisibility check
//     //   on the exchange side rejects it. This is why EVERY single trade that
//     //   passed all the strategy gates (XMR aside — that was a qty=0 margin
//     //   issue, not this bug) still failed at order placement: KAIA, FLUX,
//     //   CTSI all hit this exact error.
//     //
//     //   Fix: do the rounding in BigDecimal (exact decimal arithmetic, no
//     //   binary rounding error) and normalize the result to the tick's own
//     //   decimal scale. Use roundToTickBD() wherever the price is about to be
//     //   put into a JSON payload sent to the exchange; the plain double
//     //   version below is kept for internal math/logging only.
//     // =========================================================================
//     private static BigDecimal roundToTickBD(double price, double tick) {
//         if (tick <= 0) return BigDecimal.valueOf(price);
//         BigDecimal bdPrice = BigDecimal.valueOf(price);
//         BigDecimal bdTick  = BigDecimal.valueOf(tick);
//         BigDecimal multiples = bdPrice.divide(bdTick, 0, RoundingMode.HALF_UP);
//         BigDecimal result = multiples.multiply(bdTick);
//         // Normalize to the tick's own scale (e.g. tick=0.00001 -> 5 dp) so we
//         // never emit trailing-zero noise or a different scale than the tick.
//         return result.setScale(bdTick.scale(), RoundingMode.HALF_UP);
//     }

//     private static double roundToTick(double price, double tick) {
//         if (tick <= 0) return price;
//         return roundToTickBD(price, tick).doubleValue();
//     }

//     private static double[] extractCloses(JSONArray a) {
//         double[] o = new double[a.length()];
//         for (int i = 0; i < a.length(); i++) o[i] = a.getJSONObject(i).getDouble("close");
//         return o;
//     }
//     private static double[] extractOpens(JSONArray a) {
//         double[] o = new double[a.length()];
//         for (int i = 0; i < a.length(); i++) o[i] = a.getJSONObject(i).getDouble("open");
//         return o;
//     }
//     private static double[] extractHighs(JSONArray a) {
//         double[] o = new double[a.length()];
//         for (int i = 0; i < a.length(); i++) o[i] = a.getJSONObject(i).getDouble("high");
//         return o;
//     }
//     private static double[] extractLows(JSONArray a) {
//         double[] o = new double[a.length()];
//         for (int i = 0; i < a.length(); i++) o[i] = a.getJSONObject(i).getDouble("low");
//         return o;
//     }

//     private static JSONArray getCandlestickData(String pair, String resolution, int count) {
//         try {
//             long minsPerBar;
//             switch (resolution) {
//                 case "5":   minsPerBar = 5;   break;
//                 case "15":  minsPerBar = 15;  break;
//                 case "30":  minsPerBar = 30;  break;
//                 case "60":  minsPerBar = 60;  break;
//                 case "120": minsPerBar = 120; break;
//                 default:    minsPerBar = 15;  break;
//             }
//             long to   = Instant.now().getEpochSecond();
//             long from = to - minsPerBar * 60L * count;
//             String url = PUBLIC_API_URL + "/market_data/candlesticks"
//                     + "?pair=" + pair + "&from=" + from + "&to=" + to
//                     + "&resolution=" + resolution + "&pcode=f";
//             HttpURLConnection conn = openGet(url);
//             int code = conn.getResponseCode();
//             if (code == 200) {
//                 JSONObject r = new JSONObject(readStream(conn.getInputStream()));
//                 if ("ok".equals(r.optString("s"))) return r.getJSONArray("data");
//                 System.err.println("  Candle s=" + r.optString("s") + " " + pair);
//             } else {
//                 System.err.println("  Candle HTTP " + code + " " + pair);
//             }
//         } catch (Exception e) {
//             System.err.println("  getCandlestickData(" + pair + "/" + resolution + "): " + e.getMessage());
//         }
//         return null;
//     }

//     private static void initInstrumentCache() {
//         try {
//             long now = System.currentTimeMillis();
//             if (now - lastCacheUpdate < TICK_CACHE_TTL_MS) return;
//             instrumentCache.clear();
//             System.out.println("Refreshing instrument cache...");
//             JSONArray pairs = new JSONArray(publicGet(
//                     BASE_URL + "/exchange/v1/derivatives/futures/data/active_instruments"));
//             for (int i = 0; i < pairs.length(); i++) {
//                 String p = pairs.getString(i);
//                 try {
//                     String raw = publicGet(
//                             BASE_URL + "/exchange/v1/derivatives/futures/data/instrument?pair=" + p);
//                     instrumentCache.put(p, new JSONObject(raw).getJSONObject("instrument"));
//                 } catch (Exception ignored) {}
//             }
//             lastCacheUpdate = now;
//             System.out.println("Instruments cached: " + instrumentCache.size());
//         } catch (Exception e) {
//             System.err.println("initInstrumentCache: " + e.getMessage());
//         }
//     }

//     private static double getTickSize(String pair) {
//         if (System.currentTimeMillis() - lastCacheUpdate > TICK_CACHE_TTL_MS) initInstrumentCache();
//         JSONObject d = instrumentCache.get(pair);
//         return d != null ? d.optDouble("price_increment", 0.0001) : 0.0001;
//     }

//     private static double getEntryPrice(String pair, String orderId) throws Exception {
//         for (int i = 0; i < MAX_ENTRY_PRICE_CHECKS; i++) {
//             TimeUnit.MILLISECONDS.sleep(ENTRY_CHECK_DELAY_MS);
//             JSONObject pos = findPosition(pair);
//             if (pos != null && pos.optDouble("avg_price", 0) > 0)
//                 return pos.getDouble("avg_price");
//         }
//         return 0;
//     }

//     private static JSONObject findPosition(String pair) throws Exception {
//         JSONObject body = new JSONObject();
//         body.put("timestamp", Instant.now().toEpochMilli());
//         body.put("page", "1");
//         body.put("size", "100");
//         body.put("margin_currency_short_name", new String[]{"INR", "USDT"});
//         String resp = authPost(BASE_URL + "/exchange/v1/derivatives/futures/positions", body.toString());
//         JSONArray arr = resp.startsWith("[")
//                 ? new JSONArray(resp) : new JSONArray().put(new JSONObject(resp));
//         for (int i = 0; i < arr.length(); i++) {
//             JSONObject p = arr.getJSONObject(i);
//             if (pair.equals(p.optString("pair"))) return p;
//         }
//         return null;
//     }

//     private static double calcQuantity(double price, String pair) {
//         double usdtInrRate = 98.0;
//         // double qty = (MAX_MARGIN * LEVERAGE) / (price * usdtInrRate);
//         double qty = MAX_MARGIN / (price * usdtInrRate);
//         double finalQty = INTEGER_QTY_PAIRS.contains(pair)
//                 ? Math.floor(qty)
//                 : Math.floor(qty * 100) / 100.0;
//         return Math.max(finalQty, 0);
//     }

//     public static double getLastPrice(String pair) {
//         try {
//             HttpURLConnection conn = openGet(
//                     PUBLIC_API_URL + "/market_data/trade_history?pair=" + pair + "&limit=1");
//             if (conn.getResponseCode() == 200) {
//                 String r = readStream(conn.getInputStream());
//                 return r.startsWith("[")
//                         ? new JSONArray(r).getJSONObject(0).getDouble("p")
//                         : new JSONObject(r).getDouble("p");
//             }
//         } catch (Exception e) {
//             System.err.println("getLastPrice(" + pair + "): " + e.getMessage());
//         }
//         return 0;
//     }

//     public static JSONObject placeFuturesOrder(String side, String pair, double qty,
//                                                      int lev, String notif,
//                                                      String marginType, String marginCcy,
//                                                      double currentPrice) {
//         try {
//             double rawLimitPrice = "buy".equalsIgnoreCase(side)
//                     ? currentPrice * (1 + LIMIT_ORDER_BUFFER_PCT)
//                     : currentPrice * (1 - LIMIT_ORDER_BUFFER_PCT);
//             double tick = getTickSize(pair);
//             // FIX: put the exact BigDecimal in the JSON, not a double, so the
//             // exchange's tick-divisibility check never sees floating-point
//             // noise like 0.026099999999999998.
//             BigDecimal limitPriceBD = roundToTickBD(rawLimitPrice, tick);
//             double limitPrice = limitPriceBD.doubleValue(); // for logging only

//             JSONObject order = new JSONObject();
//             order.put("side",                       side.toLowerCase());
//             order.put("pair",                       pair);
//             order.put("order_type",                 "limit_order");
//             order.put("price",                      limitPriceBD);
//             order.put("total_quantity",             qty);
//             order.put("leverage",                   lev);
//             order.put("notification",               notif);
//             order.put("time_in_force",              "good_till_cancel");
//             order.put("hidden",                     false);
//             order.put("post_only",                  false);
//             order.put("position_margin_type",       marginType);
//             order.put("margin_currency_short_name", marginCcy);
//             JSONObject body = new JSONObject();
//             body.put("timestamp", Instant.now().toEpochMilli());
//             body.put("order", order);
//             String resp = authPost(
//                     BASE_URL + "/exchange/v1/derivatives/futures/orders/create", body.toString());
//             return resp.startsWith("[")
//                     ? new JSONArray(resp).getJSONObject(0)
//                     : new JSONObject(resp);
//         } catch (Exception e) {
//             System.err.println("placeFuturesOrder: " + e.getMessage());
//             return null;
//         }
//     }

//     public static void setTpSl(String posId, double tp, double sl, String pair) {
//         try {
//             double tick = getTickSize(pair);
//             // FIX: same BigDecimal-exact rounding as the entry order — avoids
//             // create_tpsl being silently rejected for the same
//             // divisible-by-tick reason.
//             BigDecimal rtp = roundToTickBD(tp, tick);
//             BigDecimal rsl = roundToTickBD(sl, tick);
//             JSONObject tpObj = new JSONObject();
//             tpObj.put("stop_price",  rtp);
//             tpObj.put("limit_price", rtp);
//             tpObj.put("order_type",  "take_profit_market");
//             JSONObject slObj = new JSONObject();
//             slObj.put("stop_price",  rsl);
//             slObj.put("limit_price", rsl);
//             slObj.put("order_type",  "stop_market");
//             JSONObject payload = new JSONObject();
//             payload.put("timestamp",   Instant.now().toEpochMilli());
//             payload.put("id",          posId);
//             payload.put("take_profit", tpObj);
//             payload.put("stop_loss",   slObj);
//             String resp = authPost(
//                     BASE_URL + "/exchange/v1/derivatives/futures/positions/create_tpsl",
//                     payload.toString());
//             JSONObject r = new JSONObject(resp);
//             System.out.println(r.has("err_code_dcx") ? "  TP/SL error: " + r : "  TP/SL set successfully!");
//         } catch (Exception e) {
//             System.err.println("setTpSl: " + e.getMessage());
//         }
//     }

//     public static String getPositionId(String pair) {
//         for (int attempt = 1; attempt <= POSITION_ID_MAX_RETRIES; attempt++) {
//             try {
//                 JSONObject p = findPosition(pair);
//                 if (p != null && p.has("id")) return p.getString("id");
//             } catch (Exception e) {
//                 System.err.println("getPositionId attempt " + attempt + ": " + e.getMessage());
//             }
//             try {
//                 TimeUnit.MILLISECONDS.sleep(POSITION_ID_RETRY_DELAY_MS);
//             } catch (InterruptedException ignored) {}
//         }
//         return null;
//     }

//     private static Set<String> getActivePositions() {
//         Set<String> active = new HashSet<>();
//         try {
//             JSONObject body = new JSONObject();
//             body.put("timestamp", Instant.now().toEpochMilli());
//             body.put("page", "1");
//             body.put("size", "100");
//             body.put("margin_currency_short_name", new String[]{"INR", "USDT"});
//             String resp = authPost(BASE_URL + "/exchange/v1/derivatives/futures/positions", body.toString());
//             JSONArray arr = resp.startsWith("[")
//                     ? new JSONArray(resp) : new JSONArray().put(new JSONObject(resp));
//             System.out.println("=== Open Positions (" + arr.length() + ") ===");
//             for (int i = 0; i < arr.length(); i++) {
//                 JSONObject p    = arr.getJSONObject(i);
//                 String    pair  = p.optString("pair", "");
//                 boolean isActive = p.optDouble("active_pos", 0) > 0
//                         || p.optDouble("locked_margin", 0) > 0
//                         || p.optDouble("avg_price", 0) > 0
//                         || p.optDouble("take_profit_trigger", 0) > 0
//                         || p.optDouble("stop_loss_trigger", 0) > 0;
//                 if (isActive) {
//                     System.out.printf("  %s | qty=%.2f | entry=%.6f | TP=%.4f | SL=%.4f%n",
//                             pair, p.optDouble("active_pos", 0), p.optDouble("avg_price", 0),
//                             p.optDouble("take_profit_trigger", 0), p.optDouble("stop_loss_trigger", 0));
//                     active.add(pair);
//                 }
//             }
//         } catch (Exception e) {
//             System.err.println("getActivePositions: " + e.getMessage());
//         }
//         return active;
//     }

//     private static HttpURLConnection openGet(String url) throws IOException {
//         HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
//         c.setRequestMethod("GET");
//         c.setConnectTimeout(10_000);
//         c.setReadTimeout(10_000);
//         return c;
//     }

//     private static String publicGet(String url) throws IOException {
//         HttpURLConnection c = openGet(url);
//         if (c.getResponseCode() == 200) return readStream(c.getInputStream());
//         throw new IOException("HTTP " + c.getResponseCode() + " — " + url);
//     }

//     private static String authPost(String url, String json) throws IOException {
//         HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
//         c.setRequestMethod("POST");
//         c.setRequestProperty("Content-Type",     "application/json");
//         c.setRequestProperty("X-AUTH-APIKEY",    API_KEY);
//         c.setRequestProperty("X-AUTH-SIGNATURE", sign(json));
//         c.setConnectTimeout(10_000);
//         c.setReadTimeout(10_000);
//         c.setDoOutput(true);
//         try (OutputStream os = c.getOutputStream()) {
//             os.write(json.getBytes(StandardCharsets.UTF_8));
//         }
//         InputStream is = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
//         return readStream(is);
//     }

//     private static String readStream(InputStream is) throws IOException {
//         return new BufferedReader(new InputStreamReader(is))
//                 .lines().collect(Collectors.joining("\n"));
//     }

//     private static String sign(String payload) {
//         try {
//             Mac mac = Mac.getInstance("HmacSHA256");
//             mac.init(new SecretKeySpec(API_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
//             byte[] b = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
//             StringBuilder sb = new StringBuilder();
//             for (byte x : b) sb.append(String.format("%02x", x));
//             return sb.toString();
//         } catch (Exception e) {
//             throw new RuntimeException("HMAC sign failed", e);
//         }
//     }

//     public static String generateHmacSHA256(String secret, String payload) {
//         return sign(payload);
//     }

//     private static JSONArray aggregateCandles(JSONArray source, int groupSize) {
//         if (source == null || source.length() < groupSize) return null;
//         int n = source.length();
//         int usableCount = (n / groupSize) * groupSize;
//         int startIdx = n - usableCount;
//         JSONArray result = new JSONArray();
//         for (int i = startIdx; i < n; i += groupSize) {
//             double open  = source.getJSONObject(i).getDouble("open");
//             double close = source.getJSONObject(i + groupSize - 1).getDouble("close");
//             double high  = Double.NEGATIVE_INFINITY;
//             double low   = Double.POSITIVE_INFINITY;
//             for (int j = i; j < i + groupSize; j++) {
//                 JSONObject c = source.getJSONObject(j);
//                 high = Math.max(high, c.getDouble("high"));
//                 low  = Math.min(low,  c.getDouble("low"));
//             }
//             JSONObject merged = new JSONObject();
//             merged.put("open", open);
//             merged.put("close", close);
//             merged.put("high", high);
//             merged.put("low", low);
//             result.put(merged);
//         }
//         return result;
//     }
// }





























// import org.json.JSONArray; //chaleble code ye mera github action wala working code hai jo mai github se chalat tha
// import org.json.JSONObject;

// import javax.crypto.Mac;
// import javax.crypto.spec.SecretKeySpec;
// import java.math.BigDecimal;
// import java.math.RoundingMode;
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

// public class CoinDCXFuturesTrader8C_BUY_SELL_NEW_LOGIC_THREE {

//     // =========================================================================
//     // API Configuration
//     // =========================================================================
//     private static final String API_KEY    = System.getenv("DELTA_API_KEY");
//     private static final String API_SECRET = System.getenv("DELTA_API_SECRET");
//     private static final String BASE_URL       = "https://api.coindcx.com";
//     private static final String PUBLIC_API_URL = "https://public.coindcx.com";

//     private static final double MAX_MARGIN = 2500.0;
//     private static final int    LEVERAGE   = 10;

//     private static final int MAX_ENTRY_PRICE_CHECKS = 20;
//     private static final int ENTRY_CHECK_DELAY_MS    = 1000;

//     private static final int  TPSL_MAX_RETRIES    = 3;
//     private static final long TPSL_RETRY_DELAY_MS = 2000L;

//     private static final long TICK_CACHE_TTL_MS = 3_600_000L;
//     private static final long COOLDOWN_MS       = 2 * 60 * 60 * 1000L;

//     private static final int MAX_OPEN_POSITIONS = 120;

//     private static final int  POSITION_ID_MAX_RETRIES = 5;
//     private static final long POSITION_ID_RETRY_DELAY_MS = 1500L;

//     private static final int EMA_FAST = 9;
//     private static final int EMA_MID  = 21;
//     private static final int ATR_PERIOD = 14;

//     private static final int    ST_PERIOD     = 10;
//     private static final double ST_MULTIPLIER = 3.0;

//     private static final double PULLBACK_MAX_ATR = 0.6;

//     private static final double SL_ATR_BUFFER   = 0.35;
//     private static final double SL_MAX_PERCENT  = 4.5;
//     private static final int    SWING_LOOKBACK       = 20;
//     private static final int    SWING_EXCLUDE_RECENT = 2;
//     private static final double SWING_EXTRA_BUFFER_ATR = 0.15;

//     private static final double RR_TARGET = 1.2;

//     private static final double LIMIT_ORDER_BUFFER_PCT = 0.001;

//     private static final int CANDLE_15M = 60;
//     private static final int CANDLE_30M = 100;
//     private static final int CANDLE_1H  = 100;
//     private static final int HTF_1H_FETCH_COUNT = 700;

//     private static final Map<String, JSONObject> instrumentCache = new ConcurrentHashMap<>();
//     private static long lastCacheUpdate = 0;
//     private static final Map<String, Long> lastTradeTime = new ConcurrentHashMap<>();

//     private static final String[] COIN_SYMBOLS = {
//         "ETH", "SOL", "ZEC", "XRP", "DOGE", "BNB", "TAO", "1000PEPE", "ADA", "SUI",
//         "BCH", "LINK", "AVAX", "FIL", "OP", "NEAR", "TRX", "TRUMP", "ARB", "WLD",
//         "FET", "ETC", "AAVE", "WIF", "INJ", "TIA", "LTC", "ONDO", "ORDI", "TON",
//         "HBAR", "IMX", "ATOM", "RUNE", "KAS", "UNI", "ICP", "SEI", "PENDLE", "1000SHIB",
//         "1000BONK", "CRV", "JUP", "RENDER", "MKR", "LDO", "STX", "XLM", "PYTH", "VIRTUAL",
//         "APT", "SNX", "STRK", "NEO", "FTM", "CAKE", "1000FLOKI", "1000SATS", "OM", "FARTCOIN",
//         "GRT", "MINA", "COMP", "BLUR", "BRETT", "SAND", "EGLD", "XMR", "IOTA", "AI16Z",
//         "PNUT", "POPCAT", "ZRO", "MANA", "ETHFI", "VET", "ALGO", "ENS", "BOME", "MASK",
//         "GALA", "YFI", "CHZ", "GMX", "QNT", "POL", "MOODENG", "ZK", "ARKM", "THETA",
//         "MEW", "EIGEN", "MORPHO", "KAITO", "USUAL", "LAYER", "GOAT", "DOGS", "RSR", "PONKE",
//         "JTO", "CKB", "ZIL", "ROSE", "1INCH", "TWT", "KSM", "MAGIC", "GAS", "ACT",
//         "SUSHI", "TURBO", "1000LUNC", "BTCDOM", "S", "IP", "FLOW", "TRB", "QTUM", "KNC",
//         "KAIA", "CELO", "SSV", "BANANA", "TNSR", "AERO", "IO", "DEXE", "ARK", "XAI",
//         "DYM", "SAGA", "HOT", "LUNA2", "IOST", "RPL", "VANA", "DASH", "MANTA", "LRC",
//         "ANKR", "XTZ", "BAND", "SUPER", "FXS", "AKT", "NMR", "PIXEL", "LPT", "STORJ",
//         "ENJ", "LISTA", "ZETA", "RED", "AGLD", "GPS", "KAVA", "SXP", "ALPHA", "BIGTIME",
//         "COTI", "USTC", "BAT", "NFP", "ONE", "POLYX", "MOVR", "OMNI", "CELR", "RVN",
//         "GLM", "HIVE", "FLUX", "ZRX", "SFP", "ALICE", "ILV", "ARPA", "UMA", "DEGEN",
//         "XVS", "ACE", "ASTR", "CTSI", "CHR", "EDU", "PROM", "ALT", "C98", "SUN",
//         "WAXP", "ALPACA", "COOKIE", "JOE", "BNT", "SCRT", "VELODROME", "HOOK", "KMNO", "NTRN",
//         "RAYSOL", "PARTI", "MELANIA", "MYRO", "SHELL", "AUCTION", "SWELL", "HIGH", "WOO",
//         "COW", "MAVIA", "VTHO", "1000CAT", "MUBARAK", "LEVER", "SOLV", "ARC", "AVAAI", "KOMA",
//         "API3", "VOXEL", "CHESS", "SPELL", "1000WHY", "SKL", "GTC", "MTL", "BICO", "DENT",
//         "RLC", "PHB", "POWR", "LSK", "DEFI", "MAV", "REI", "ONG", "XVG", "COS",
//         "FORTH", "BEL", "MLN", "HEI", "GHST", "STEEM", "LOKA", "DIA", "TLM", "BMT",
//         "ALCH", "FUN", "1000CHEEMS", "1000RATS", "1000000MOG", "1MBABYDOGE", "1000XEC", "1000X", "PERP", "NKN",
//         "VINE", "RARE", "HFT", "AXL", "ACH", "ZEN", "PEOPLE", "AR", "CFX", "ID",
//         "METIS", "FIO", "CYBER"
//     };

//     private static final Set<String> INTEGER_QTY_PAIRS = Stream.of(COIN_SYMBOLS)
//             .flatMap(s -> Stream.of("B-" + s + "_USDT", s + "_USDT"))
//             .collect(Collectors.toCollection(HashSet::new));

//     private static final String[] COINS_TO_TRADE = Stream.of(COIN_SYMBOLS)
//             .map(s -> "B-" + s + "_USDT")
//             .toArray(String[]::new);

//     private static class TFResult {
//         boolean valid;
//         boolean bullish;
//         boolean bearish;
//         boolean stGreen;
//         double  ema9, ema21, price;
//         double  atr;
//         double[] stBands;
//         double[] hi, lo, cl;
//     }

//     private static TFResult analyzeTF(JSONArray candles) {
//         TFResult r = new TFResult();
//         if (candles == null || candles.length() < EMA_MID + ST_PERIOD + 5) {
//             r.valid = false;
//             return r;
//         }
//         double[] cl = extractCloses(candles);
//         double[] hi = extractHighs(candles);
//         double[] lo = extractLows(candles);

//         r.cl = cl; r.hi = hi; r.lo = lo;
//         r.ema9  = calcEMA(cl, EMA_FAST);
//         r.ema21 = calcEMA(cl, EMA_MID);
//         r.price = cl[cl.length - 1];
//         r.atr   = calcATR(hi, lo, cl, ATR_PERIOD);

//         boolean[] stSeries = calcSupertrend(hi, lo, cl, ST_PERIOD, ST_MULTIPLIER);
//         r.stGreen  = stSeries[stSeries.length - 1];
//         r.stBands  = calcSupertrendBands(hi, lo, cl, ST_PERIOD, ST_MULTIPLIER);
//         r.valid = true;

//         boolean priceAboveEmas = r.price > r.ema9 && r.price > r.ema21;
//         boolean priceBelowEmas = r.price < r.ema9 && r.price < r.ema21;
//         boolean priceAboveSt   = r.price > r.stBands[0];
//         boolean priceBelowSt   = r.price < r.stBands[1];

//         r.bullish = r.stGreen && priceAboveSt && (r.ema9 > r.ema21) && priceAboveEmas;
//         r.bearish = (!r.stGreen) && priceBelowSt && (r.ema9 < r.ema21) && priceBelowEmas;
//         return r;
//     }

//     private static boolean isBullishRejection(double open, double high, double low, double close,
//                                                double prevOpen, double prevClose) {
//         double range = high - low;
//         if (range <= 0) return false;
//         double body       = Math.abs(close - open);
//         double lowerWick   = Math.min(open, close) - low;
//         double upperWick   = high - Math.max(open, close);
//         boolean isBull     = close > open;

//         boolean hammer = body > 0 && lowerWick >= 2 * body && upperWick <= body * 0.6;

//         boolean bullishEngulf = prevClose < prevOpen && isBull
//                 && close >= prevOpen && open <= prevClose;

//         double bodyRatio = body / range;
//         double closePos  = (close - low) / range;
//         boolean strongBull = isBull && bodyRatio >= 0.55 && closePos >= 0.7;

//         return hammer || bullishEngulf || strongBull;
//     }

//     private static boolean isBearishRejection(double open, double high, double low, double close,
//                                                double prevOpen, double prevClose) {
//         double range = high - low;
//         if (range <= 0) return false;
//         double body       = Math.abs(close - open);
//         double lowerWick   = Math.min(open, close) - low;
//         double upperWick   = high - Math.max(open, close);
//         boolean isBear     = close < open;

//         boolean shootingStar = body > 0 && upperWick >= 2 * body && lowerWick <= body * 0.6;

//         boolean bearishEngulf = prevClose > prevOpen && isBear
//                 && open >= prevClose && close <= prevOpen;

//         double bodyRatio = body / range;
//         double closePos  = (close - low) / range;
//         boolean strongBear = isBear && bodyRatio >= 0.55 && closePos <= 0.3;

//         return shootingStar || bearishEngulf || strongBear;
//     }

//     private static double findSwingLow(double[] lo, int lookback, int excludeRecent) {
//         int n = lo.length;
//         int start = Math.max(0, n - lookback - excludeRecent);
//         int end   = Math.max(start, n - excludeRecent);
//         double sw = Double.POSITIVE_INFINITY;
//         for (int i = start; i < end; i++) sw = Math.min(sw, lo[i]);
//         return sw;
//     }

//     private static double findSwingHigh(double[] hi, int lookback, int excludeRecent) {
//         int n = hi.length;
//         int start = Math.max(0, n - lookback - excludeRecent);
//         int end   = Math.max(start, n - excludeRecent);
//         double sw = Double.NEGATIVE_INFINITY;
//         for (int i = start; i < end; i++) sw = Math.max(sw, hi[i]);
//         return sw;
//     }

//     private static double[] computeSlTp(boolean isLong, double entryPrice, TFResult tf2h, double tickSize) {
//         double sl, tp;
//         if (isLong) {
//             double raw = tf2h.stBands[0] - SL_ATR_BUFFER * tf2h.atr;
//             if (raw >= entryPrice) raw = entryPrice - (SL_ATR_BUFFER + 1.5) * tf2h.atr;
//             double hardFloor = entryPrice * (1 - SL_MAX_PERCENT / 100.0);

//             double swingLow = findSwingLow(tf2h.lo, SWING_LOOKBACK, SWING_EXCLUDE_RECENT);
//             if (swingLow < raw && swingLow > hardFloor) {
//                 raw = swingLow - SWING_EXTRA_BUFFER_ATR * tf2h.atr;
//             }

//             sl = Math.max(raw, hardFloor);
//             double risk = entryPrice - sl;
//             tp = entryPrice + RR_TARGET * risk;
//         } else {
//             double raw = tf2h.stBands[1] + SL_ATR_BUFFER * tf2h.atr;
//             if (raw <= entryPrice) raw = entryPrice + (SL_ATR_BUFFER + 1.5) * tf2h.atr;
//             double hardCeil = entryPrice * (1 + SL_MAX_PERCENT / 100.0);

//             double swingHigh = findSwingHigh(tf2h.hi, SWING_LOOKBACK, SWING_EXCLUDE_RECENT);
//             if (swingHigh > raw && swingHigh < hardCeil) {
//                 raw = swingHigh + SWING_EXTRA_BUFFER_ATR * tf2h.atr;
//             }

//             sl = Math.min(raw, hardCeil);
//             double risk = sl - entryPrice;
//             tp = entryPrice - RR_TARGET * risk;
//         }
//         sl = roundToTick(sl, tickSize);
//         tp = roundToTick(tp, tickSize);
//         return new double[]{sl, tp};
//     }

//     private static double[] sanityClampSlTp(boolean isLong, double entry, double sl, double tp, double tick) {
//         double minGap = Math.max(tick, entry * 0.0005);
//         if (isLong) {
//             if (sl >= entry - minGap) sl = entry - minGap;
//             if (tp <= entry + minGap) tp = entry + minGap;
//         } else {
//             if (sl <= entry + minGap) sl = entry + minGap;
//             if (tp >= entry - minGap) tp = entry - minGap;
//         }
//         sl = roundToTick(sl, tick);
//         tp = roundToTick(tp, tick);
//         return new double[]{sl, tp};
//     }

//     public static void main(String[] args) {
//         initInstrumentCache();
//         Set<String> active = getActivePositions();
//         System.out.println("Active positions: " + active);

//         if (active.size() >= MAX_OPEN_POSITIONS) {
//             System.out.println("MAX_OPEN_POSITIONS (" + MAX_OPEN_POSITIONS +
//                     ") already reached (" + active.size() + " open) — skipping scan entirely.");
//             ensureTpSlForOpenPositions();
//             return;
//         }

//         for (String pair : COINS_TO_TRADE) {
//             try {
//                 if (active.size() >= MAX_OPEN_POSITIONS) {
//                     System.out.println("MAX_OPEN_POSITIONS reached mid-scan — stopping.");
//                     break;
//                 }
//                 if (active.contains(pair)) {
//                     System.out.println("Skip " + pair + " — active position");
//                     continue;
//                 }
//                 long lastTrade = lastTradeTime.getOrDefault(pair, 0L);
//                 if (System.currentTimeMillis() - lastTrade < COOLDOWN_MS) {
//                     System.out.println("  Skip " + pair + " — cooldown active");
//                     continue;
//                 }
//                 System.out.println("\n==== " + pair + " ====");

//                 JSONArray raw15m         = getCandlestickData(pair, "15", CANDLE_15M);
//                 JSONArray raw30m         = getCandlestickData(pair, "30", CANDLE_30M);
//                 JSONArray raw1hExtended  = getCandlestickData(pair, "60", HTF_1H_FETCH_COUNT);
//                 JSONArray raw1h          = lastN(raw1hExtended, CANDLE_1H);
//                 JSONArray raw2h          = aggregateCandles(raw1hExtended, 2);
//                 JSONArray raw4h          = aggregateCandles(raw1hExtended, 4);

//                 if (raw15m == null || raw15m.length() < EMA_MID + 5) {
//                     System.out.println("  Insufficient 15m candles — skip"); continue;
//                 }
//                 if (raw30m == null || raw30m.length() < EMA_MID + 5) {
//                     System.out.println("  Insufficient 30m candles — skip"); continue;
//                 }

//                 TFResult tf4h = analyzeTF(raw4h);
//                 if (!tf4h.valid) {
//                     System.out.println("  [4H] insufficient data — skip"); continue;
//                 }
//                 System.out.printf("  [4H] ST=%s EMA9=%.6f EMA21=%.6f Price=%.6f → %s%n",
//                         tf4h.stGreen ? "GREEN" : "RED", tf4h.ema9, tf4h.ema21, tf4h.price,
//                         tf4h.bullish ? "BULLISH" : tf4h.bearish ? "BEARISH" : "NO CLEAR TREND");
//                 if (!tf4h.bullish && !tf4h.bearish) {
//                     System.out.println("  4H FAIL — macro trend not clean — skip"); continue;
//                 }

//                 TFResult tf2h = analyzeTF(raw2h);
//                 if (!tf2h.valid) {
//                     System.out.println("  [2H] insufficient data — skip"); continue;
//                 }
//                 System.out.printf("  [2H] ST=%s EMA9=%.6f EMA21=%.6f Price=%.6f → %s%n",
//                         tf2h.stGreen ? "GREEN" : "RED", tf2h.ema9, tf2h.ema21, tf2h.price,
//                         tf2h.bullish ? "BULLISH" : tf2h.bearish ? "BEARISH" : "NO CLEAR TREND");
//                 boolean tf2hMatches4h = (tf4h.bullish && tf2h.bullish) || (tf4h.bearish && tf2h.bearish);
//                 if (!tf2hMatches4h) {
//                     System.out.println("  2H FAIL — disagrees with (or unclear vs) 4H macro trend — skip");
//                     continue;
//                 }

//                 TFResult tf1h = analyzeTF(raw1h);
//                 if (!tf1h.valid) {
//                     System.out.println("  [1H] insufficient data — skip"); continue;
//                 }
//                 System.out.printf("  [1H] ST=%s EMA9=%.6f EMA21=%.6f Price=%.6f → %s%n",
//                         tf1h.stGreen ? "GREEN" : "RED", tf1h.ema9, tf1h.ema21, tf1h.price,
//                         tf1h.bullish ? "BULLISH" : tf1h.bearish ? "BEARISH" : "NO CLEAR TREND");

//                 boolean trendUp;
//                 if (tf4h.bullish && tf2h.bullish && tf1h.bullish) {
//                     trendUp = true;
//                 } else if (tf4h.bearish && tf2h.bearish && tf1h.bearish) {
//                     trendUp = false;
//                 } else {
//                     System.out.println("  1H DISAGREES (or unclear) with 4H/2H — no trade, wait — skip");
//                     continue;
//                 }
//                 System.out.println("  4H+2H+1H OK — " + (trendUp ? "BULLISH" : "BEARISH") + " confirmed on all three");

//                 TFResult tf30m = analyzeTF(raw30m);
//                 if (!tf30m.valid) {
//                     System.out.println("  [30M] insufficient data — skip"); continue;
//                 }
//                 System.out.printf("  [30M] ST=%s EMA9=%.6f EMA21=%.6f Price=%.6f → %s%n",
//                         tf30m.stGreen ? "GREEN" : "RED", tf30m.ema9, tf30m.ema21, tf30m.price,
//                         tf30m.bullish ? "BULLISH" : tf30m.bearish ? "BEARISH" : "NO CLEAR TREND");
//                 boolean tf30mAligned = trendUp ? tf30m.bullish : tf30m.bearish;
//                 if (!tf30mAligned) {
//                     System.out.println("  30M FAIL — disagrees with (or unclear vs) 4H/2H/1H trend — skip");
//                     continue;
//                 }
//                 System.out.println("  30M OK — aligned with higher timeframes");

//                 double[] cl15 = extractCloses(raw15m);
//                 double[] op15 = extractOpens(raw15m);
//                 double[] hi15 = extractHighs(raw15m);
//                 double[] lo15 = extractLows(raw15m);
//                 int n15 = cl15.length;

//                 double ema9_15  = calcEMA(cl15, EMA_FAST);
//                 double ema21_15 = calcEMA(cl15, EMA_MID);
//                 double atr15    = calcATR(hi15, lo15, cl15, ATR_PERIOD);
//                 boolean[] st15Series = calcSupertrend(hi15, lo15, cl15, ST_PERIOD, ST_MULTIPLIER);
//                 boolean stBull15 = st15Series[st15Series.length - 1];
//                 double[] stBands15 = calcSupertrendBands(hi15, lo15, cl15, ST_PERIOD, ST_MULTIPLIER);

//                 boolean tf15Aligned = trendUp
//                         ? (stBull15 && ema9_15 > ema21_15)
//                         : (!stBull15 && ema9_15 < ema21_15);
//                 System.out.printf("  [15M] ST=%s EMA9=%.6f EMA21=%.6f → %s%n",
//                         stBull15 ? "GREEN" : "RED", ema9_15, ema21_15,
//                         tf15Aligned ? "ALIGNED" : "NOT ALIGNED");
//                 if (!tf15Aligned) {
//                     System.out.println("  15M FAIL — not aligned with higher-timeframe direction — skip"); continue;
//                 }

//                 if (n15 < 3) { System.out.println("  Not enough 15m candles for entry check — skip"); continue; }
//                 double entryClose = cl15[n15 - 2], entryOpen = op15[n15 - 2];
//                 double entryHigh  = hi15[n15 - 2], entryLow  = lo15[n15 - 2];
//                 double prevClose  = cl15[n15 - 3], prevOpen  = op15[n15 - 3];

//                 double distEma9  = Math.abs(entryClose - ema9_15);
//                 double distEma21 = Math.abs(entryClose - ema21_15);
//                 double distSt    = trendUp
//                         ? Math.abs(entryClose - stBands15[0])
//                         : Math.abs(entryClose - stBands15[1]);
//                 double maxDist   = PULLBACK_MAX_ATR * atr15;
//                 boolean pullbackOk = distEma9 <= maxDist || distEma21 <= maxDist || distSt <= maxDist;
//                 System.out.printf("  [15M-Pullback] distEMA9=%.6f distEMA21=%.6f distST=%.6f maxAllowed=%.6f → %s%n",
//                         distEma9, distEma21, distSt, maxDist, pullbackOk ? "PASS" : "FAIL");
//                 if (!pullbackOk) {
//                     System.out.println("  15M FAIL — no valid pullback — skip"); continue;
//                 }

//                 boolean rejectionOk = trendUp
//                         ? isBullishRejection(entryOpen, entryHigh, entryLow, entryClose, prevOpen, prevClose)
//                         : isBearishRejection(entryOpen, entryHigh, entryLow, entryClose, prevOpen, prevClose);
//                 System.out.printf("  [15M-Rejection] %s candle → %s%n",
//                         trendUp ? "Bullish" : "Bearish", rejectionOk ? "CONFIRMED" : "not present");
//                 if (!rejectionOk) {
//                     System.out.println("  15M FAIL — no rejection candle confirmation — skip"); continue;
//                 }
//                 System.out.println("  15M OK — pullback + rejection candle confirmed");

//                 String side = trendUp ? "buy" : "sell";
//                 System.out.println("\n  ╔══════════════════════════════════════════════════╗");
//                 System.out.println("  ║  ALL CONDITIONS PASSED → " + side.toUpperCase() + " " + pair);
//                 System.out.println("  ╚══════════════════════════════════════════════════╝");

//                 double currentPrice = getLastPrice(pair);
//                 if (currentPrice <= 0) { System.out.println("  Invalid price — skip"); continue; }
//                 double qty = calcQuantity(currentPrice, pair);
//                 if (qty <= 0) { System.out.println("  Invalid qty — skip"); continue; }
//                 double tickSize = getTickSize(pair);

//                 System.out.printf("  Placing %s | price=%.6f | qty=%.4f | lev=%dx%n",
//                         side.toUpperCase(), currentPrice, qty, LEVERAGE);

//                 JSONObject resp = placeFuturesOrder(side, pair, qty, LEVERAGE,
//                         "email_notification", "isolated", "INR", currentPrice);
//                 if (resp == null || !resp.has("id")) {
//                     System.out.println("  Order failed: " + resp); continue;
//                 }
//                 System.out.println("  Order placed! id=" + resp.getString("id"));
//                 lastTradeTime.put(pair, System.currentTimeMillis());

//                 double entry = getEntryPrice(pair, resp.getString("id"));
//                 if (entry <= 0) {
//                     System.out.println("  Could not confirm entry within window — TP/SL will be handled by end-of-scan safety sweep");
//                     active.add(pair);
//                     continue;
//                 }
//                 System.out.printf("  Entry confirmed: %.6f%n", entry);

//                 double[] slTp = computeSlTp(trendUp, entry, tf2h, tickSize);
//                 double[] clamped = sanityClampSlTp(trendUp, entry, slTp[0], slTp[1], tickSize);
//                 double slPrice = clamped[0], tpPrice = clamped[1];
//                 double slPct = Math.abs(entry - slPrice) / entry * 100;
//                 double tpPct = Math.abs(tpPrice - entry) / entry * 100;
//                 System.out.printf("  SL=%.6f (%.2f%%) | TP=%.6f (%.2f%%) | R:R target=%.1f%n",
//                         slPrice, slPct, tpPrice, tpPct, RR_TARGET);

//                 String posId = getPositionId(pair);
//                 if (posId != null) {
//                     setTpSlWithRetry(posId, tpPrice, slPrice, pair);
//                 } else {
//                     System.out.println("  Position ID not found after retries — TP/SL will be handled by end-of-scan safety sweep");
//                 }

//                 active.add(pair);

//             } catch (Exception e) {
//                 System.err.println("Error on " + pair + ": " + e.getMessage());
//             }
//         }
//         System.out.println("\n=== Scan complete ===");
//         ensureTpSlForOpenPositions();
//     }

//     private static void ensureTpSlForOpenPositions() {
//         try {
//             Set<String> stillOpen = getActivePositions();
//             for (String pair : stillOpen) {
//                 JSONObject pos = findPosition(pair);
//                 if (pos == null) continue;
//                 double avgPrice = pos.optDouble("avg_price", 0);
//                 double tpTrig   = pos.optDouble("take_profit_trigger", 0);
//                 double slTrig   = pos.optDouble("stop_loss_trigger", 0);
//                 if (avgPrice <= 0) continue;
//                 if (tpTrig > 0 && slTrig > 0) continue;

//                 System.out.println("  [SWEEP] " + pair + " missing TP/SL — computing fallback protection...");
//                 JSONArray raw1hExtended = getCandlestickData(pair, "60", HTF_1H_FETCH_COUNT);
//                 JSONArray raw2h = aggregateCandles(raw1hExtended, 2);
//                 TFResult tf2h = analyzeTF(raw2h);
//                 if (!tf2h.valid) {
//                     System.out.println("  [SWEEP] insufficient 2H data for " + pair + " — will retry next run");
//                     continue;
//                 }

//                 double posQty = pos.optDouble("active_pos", 0);
//                 boolean isLong = posQty >= 0;

//                 double tick = getTickSize(pair);
//                 double[] slTp = computeSlTp(isLong, avgPrice, tf2h, tick);
//                 double[] clamped = sanityClampSlTp(isLong, avgPrice, slTp[0], slTp[1], tick);
//                 double sl = clamped[0], tp = clamped[1];

//                 String posId = pos.optString("id", null);
//                 if (posId != null) {
//                     System.out.printf("  [SWEEP] %s fallback SL=%.6f TP=%.6f (R:R target=%.1f)%n", pair, sl, tp, RR_TARGET);
//                     setTpSlWithRetry(posId, tp, sl, pair);
//                 } else {
//                     System.out.println("  [SWEEP] " + pair + " — position ID missing, cannot set TP/SL");
//                 }
//             }
//         } catch (Exception e) {
//             System.err.println("ensureTpSlForOpenPositions: " + e.getMessage());
//         }
//     }

//     private static boolean setTpSlWithRetry(String posId, double tp, double sl, String pair) {
//         for (int attempt = 1; attempt <= TPSL_MAX_RETRIES; attempt++) {
//             setTpSl(posId, tp, sl, pair);
//             try {
//                 TimeUnit.MILLISECONDS.sleep(TPSL_RETRY_DELAY_MS);
//             } catch (InterruptedException ignored) {}
//             try {
//                 JSONObject pos = findPosition(pair);
//                 if (pos != null && pos.optDouble("take_profit_trigger", 0) > 0
//                         && pos.optDouble("stop_loss_trigger", 0) > 0) {
//                     System.out.println("  TP/SL confirmed set on attempt " + attempt + " for " + pair);
//                     return true;
//                 }
//             } catch (Exception ignored) {}
//             System.out.println("  TP/SL not confirmed yet (attempt " + attempt + "/" + TPSL_MAX_RETRIES + ") for " + pair + " — retrying...");
//         }
//         System.out.println("  WARNING: TP/SL could not be confirmed after " + TPSL_MAX_RETRIES + " attempts for " + pair
//                 + " — will be retried by the next scan's safety sweep");
//         return false;
//     }

//     private static JSONArray lastN(JSONArray arr, int n) {
//         if (arr == null) return null;
//         int len = arr.length();
//         if (len <= n) return arr;
//         JSONArray out = new JSONArray();
//         for (int i = len - n; i < len; i++) out.put(arr.getJSONObject(i));
//         return out;
//     }

//     private static double[] calcSupertrendBands(double[] hi, double[] lo, double[] cl,
//                                                  int period, double multiplier) {
//         int n = cl.length;
//         if (n < period + 1) return new double[]{cl[n-1] * 0.97, cl[n-1] * 1.03};
//         double[] atrArr    = calcATRSeries(hi, lo, cl, period);
//         double[] upperBand = new double[n];
//         double[] lowerBand = new double[n];
//         for (int i = period; i < n; i++) {
//             double hl2        = (hi[i] + lo[i]) / 2.0;
//             double basicUpper = hl2 + multiplier * atrArr[i];
//             double basicLower = hl2 - multiplier * atrArr[i];
//             if (i == period) {
//                 upperBand[i] = basicUpper;
//                 lowerBand[i] = basicLower;
//             } else {
//                 upperBand[i] = (basicUpper < upperBand[i-1] || cl[i-1] > upperBand[i-1])
//                         ? basicUpper : upperBand[i-1];
//                 lowerBand[i] = (basicLower > lowerBand[i-1] || cl[i-1] < lowerBand[i-1])
//                         ? basicLower : lowerBand[i-1];
//             }
//         }
//         return new double[]{lowerBand[n-1], upperBand[n-1]};
//     }

//     private static boolean[] calcSupertrend(double[] hi, double[] lo, double[] cl,
//                                              int period, double multiplier) {
//         int n = cl.length;
//         boolean[] bullish = new boolean[n];
//         if (n < period + 1) { Arrays.fill(bullish, true); return bullish; }
//         double[] atrArr    = calcATRSeries(hi, lo, cl, period);
//         double[] upperBand = new double[n];
//         double[] lowerBand = new double[n];
//         for (int i = period; i < n; i++) {
//             double hl2        = (hi[i] + lo[i]) / 2.0;
//             double basicUpper = hl2 + multiplier * atrArr[i];
//             double basicLower = hl2 - multiplier * atrArr[i];
//             if (i == period) {
//                 upperBand[i] = basicUpper; lowerBand[i] = basicLower;
//             } else {
//                 upperBand[i] = (basicUpper < upperBand[i-1] || cl[i-1] > upperBand[i-1])
//                         ? basicUpper : upperBand[i-1];
//                 lowerBand[i] = (basicLower > lowerBand[i-1] || cl[i-1] < lowerBand[i-1])
//                         ? basicLower : lowerBand[i-1];
//             }
//             if (i == period) bullish[i] = cl[i] > (hi[i] + lo[i]) / 2.0;
//             else bullish[i] = bullish[i-1] ? cl[i] >= lowerBand[i] : cl[i] > upperBand[i];
//         }
//         for (int i = 0; i < period; i++) bullish[i] = bullish[period];
//         return bullish;
//     }

//     private static double[] calcATRSeries(double[] hi, double[] lo, double[] cl, int period) {
//         int n = hi.length;
//         double[] atr = new double[n];
//         if (n < 2) return atr;
//         double[] tr = new double[n];
//         tr[0] = hi[0] - lo[0];
//         for (int i = 1; i < n; i++)
//             tr[i] = Math.max(hi[i] - lo[i],
//                     Math.max(Math.abs(hi[i] - cl[i-1]), Math.abs(lo[i] - cl[i-1])));
//         double sum = 0;
//         for (int i = 0; i < period && i < n; i++) sum += tr[i];
//         atr[period - 1] = sum / period;
//         for (int i = period; i < n; i++) atr[i] = (atr[i-1] * (period - 1) + tr[i]) / period;
//         for (int i = 0; i < period - 1; i++) atr[i] = atr[period - 1];
//         return atr;
//     }

//     private static double calcATR(double[] hi, double[] lo, double[] cl, int period) {
//         if (hi.length < period + 1) return 0;
//         double[] tr = new double[hi.length];
//         tr[0] = hi[0] - lo[0];
//         for (int i = 1; i < hi.length; i++)
//             tr[i] = Math.max(hi[i] - lo[i],
//                     Math.max(Math.abs(hi[i] - cl[i-1]), Math.abs(lo[i] - cl[i-1])));
//         double atr = 0;
//         for (int i = 0; i < period; i++) atr += tr[i];
//         atr /= period;
//         for (int i = period; i < hi.length; i++) atr = (atr*(period-1)+tr[i])/period;
//         return atr;
//     }

//     private static double calcEMA(double[] d, int period) {
//         if (d.length < period) return 0;
//         double k = 2.0 / (period + 1), ema = 0;
//         for (int i = 0; i < period; i++) ema += d[i];
//         ema /= period;
//         for (int i = period; i < d.length; i++) ema = d[i] * k + ema * (1 - k);
//         return ema;
//     }

//     // =========================================================================
//     // FIX (v20.1) — real root cause of every order in the log failing with
//     // {"code":400,"message":"Price should be divisible by 0.00001"}:
//     //
//     //   The old roundToTick() did `Math.round(price / tick) * tick` in raw
//     //   double arithmetic. Most tick sizes (0.00001, 0.0001, ...) are NOT
//     //   exactly representable in binary floating point, so the multiply-back
//     //   step can land on something like 0.026099999999999998 instead of the
//     //   intended 0.0261 — a value that is mathematically a clean multiple of
//     //   the tick, but whose IEEE-754 double representation is not. When that
//     //   double gets serialized into the order JSON, org.json prints the full
//     //   (slightly-off) decimal, and CoinDCX's exact-string divisibility check
//     //   on the exchange side rejects it. This is why EVERY single trade that
//     //   passed all the strategy gates (XMR aside — that was a qty=0 margin
//     //   issue, not this bug) still failed at order placement: KAIA, FLUX,
//     //   CTSI all hit this exact error.
//     //
//     //   Fix: do the rounding in BigDecimal (exact decimal arithmetic, no
//     //   binary rounding error) and normalize the result to the tick's own
//     //   decimal scale. Use roundToTickBD() wherever the price is about to be
//     //   put into a JSON payload sent to the exchange; the plain double
//     //   version below is kept for internal math/logging only.
//     // =========================================================================
//     private static BigDecimal roundToTickBD(double price, double tick) {
//         if (tick <= 0) return BigDecimal.valueOf(price);
//         BigDecimal bdPrice = BigDecimal.valueOf(price);
//         BigDecimal bdTick  = BigDecimal.valueOf(tick);
//         BigDecimal multiples = bdPrice.divide(bdTick, 0, RoundingMode.HALF_UP);
//         BigDecimal result = multiples.multiply(bdTick);
//         // Normalize to the tick's own scale (e.g. tick=0.00001 -> 5 dp) so we
//         // never emit trailing-zero noise or a different scale than the tick.
//         return result.setScale(bdTick.scale(), RoundingMode.HALF_UP);
//     }

//     private static double roundToTick(double price, double tick) {
//         if (tick <= 0) return price;
//         return roundToTickBD(price, tick).doubleValue();
//     }

//     private static double[] extractCloses(JSONArray a) {
//         double[] o = new double[a.length()];
//         for (int i = 0; i < a.length(); i++) o[i] = a.getJSONObject(i).getDouble("close");
//         return o;
//     }
//     private static double[] extractOpens(JSONArray a) {
//         double[] o = new double[a.length()];
//         for (int i = 0; i < a.length(); i++) o[i] = a.getJSONObject(i).getDouble("open");
//         return o;
//     }
//     private static double[] extractHighs(JSONArray a) {
//         double[] o = new double[a.length()];
//         for (int i = 0; i < a.length(); i++) o[i] = a.getJSONObject(i).getDouble("high");
//         return o;
//     }
//     private static double[] extractLows(JSONArray a) {
//         double[] o = new double[a.length()];
//         for (int i = 0; i < a.length(); i++) o[i] = a.getJSONObject(i).getDouble("low");
//         return o;
//     }

//     private static JSONArray getCandlestickData(String pair, String resolution, int count) {
//         try {
//             long minsPerBar;
//             switch (resolution) {
//                 case "5":   minsPerBar = 5;   break;
//                 case "15":  minsPerBar = 15;  break;
//                 case "30":  minsPerBar = 30;  break;
//                 case "60":  minsPerBar = 60;  break;
//                 case "120": minsPerBar = 120; break;
//                 default:    minsPerBar = 15;  break;
//             }
//             long to   = Instant.now().getEpochSecond();
//             long from = to - minsPerBar * 60L * count;
//             String url = PUBLIC_API_URL + "/market_data/candlesticks"
//                     + "?pair=" + pair + "&from=" + from + "&to=" + to
//                     + "&resolution=" + resolution + "&pcode=f";
//             HttpURLConnection conn = openGet(url);
//             int code = conn.getResponseCode();
//             if (code == 200) {
//                 JSONObject r = new JSONObject(readStream(conn.getInputStream()));
//                 if ("ok".equals(r.optString("s"))) return r.getJSONArray("data");
//                 System.err.println("  Candle s=" + r.optString("s") + " " + pair);
//             } else {
//                 System.err.println("  Candle HTTP " + code + " " + pair);
//             }
//         } catch (Exception e) {
//             System.err.println("  getCandlestickData(" + pair + "/" + resolution + "): " + e.getMessage());
//         }
//         return null;
//     }

//     private static void initInstrumentCache() {
//         try {
//             long now = System.currentTimeMillis();
//             if (now - lastCacheUpdate < TICK_CACHE_TTL_MS) return;
//             instrumentCache.clear();
//             System.out.println("Refreshing instrument cache...");
//             JSONArray pairs = new JSONArray(publicGet(
//                     BASE_URL + "/exchange/v1/derivatives/futures/data/active_instruments"));
//             for (int i = 0; i < pairs.length(); i++) {
//                 String p = pairs.getString(i);
//                 try {
//                     String raw = publicGet(
//                             BASE_URL + "/exchange/v1/derivatives/futures/data/instrument?pair=" + p);
//                     instrumentCache.put(p, new JSONObject(raw).getJSONObject("instrument"));
//                 } catch (Exception ignored) {}
//             }
//             lastCacheUpdate = now;
//             System.out.println("Instruments cached: " + instrumentCache.size());
//         } catch (Exception e) {
//             System.err.println("initInstrumentCache: " + e.getMessage());
//         }
//     }

//     private static double getTickSize(String pair) {
//         if (System.currentTimeMillis() - lastCacheUpdate > TICK_CACHE_TTL_MS) initInstrumentCache();
//         JSONObject d = instrumentCache.get(pair);
//         return d != null ? d.optDouble("price_increment", 0.0001) : 0.0001;
//     }

//     private static double getEntryPrice(String pair, String orderId) throws Exception {
//         for (int i = 0; i < MAX_ENTRY_PRICE_CHECKS; i++) {
//             TimeUnit.MILLISECONDS.sleep(ENTRY_CHECK_DELAY_MS);
//             JSONObject pos = findPosition(pair);
//             if (pos != null && pos.optDouble("avg_price", 0) > 0)
//                 return pos.getDouble("avg_price");
//         }
//         return 0;
//     }

//     private static JSONObject findPosition(String pair) throws Exception {
//         JSONObject body = new JSONObject();
//         body.put("timestamp", Instant.now().toEpochMilli());
//         body.put("page", "1");
//         body.put("size", "100");
//         body.put("margin_currency_short_name", new String[]{"INR", "USDT"});
//         String resp = authPost(BASE_URL + "/exchange/v1/derivatives/futures/positions", body.toString());
//         JSONArray arr = resp.startsWith("[")
//                 ? new JSONArray(resp) : new JSONArray().put(new JSONObject(resp));
//         for (int i = 0; i < arr.length(); i++) {
//             JSONObject p = arr.getJSONObject(i);
//             if (pair.equals(p.optString("pair"))) return p;
//         }
//         return null;
//     }

//     private static double calcQuantity(double price, String pair) {
//         double usdtInrRate = 98.0;
//         // double qty = (MAX_MARGIN * LEVERAGE) / (price * usdtInrRate);
//         double qty = MAX_MARGIN / (price * usdtInrRate);
//         double finalQty = INTEGER_QTY_PAIRS.contains(pair)
//                 ? Math.floor(qty)
//                 : Math.floor(qty * 100) / 100.0;
//         return Math.max(finalQty, 0);
//     }

//     public static double getLastPrice(String pair) {
//         try {
//             HttpURLConnection conn = openGet(
//                     PUBLIC_API_URL + "/market_data/trade_history?pair=" + pair + "&limit=1");
//             if (conn.getResponseCode() == 200) {
//                 String r = readStream(conn.getInputStream());
//                 return r.startsWith("[")
//                         ? new JSONArray(r).getJSONObject(0).getDouble("p")
//                         : new JSONObject(r).getDouble("p");
//             }
//         } catch (Exception e) {
//             System.err.println("getLastPrice(" + pair + "): " + e.getMessage());
//         }
//         return 0;
//     }

//     public static JSONObject placeFuturesOrder(String side, String pair, double qty,
//                                                      int lev, String notif,
//                                                      String marginType, String marginCcy,
//                                                      double currentPrice) {
//         try {
//             double rawLimitPrice = "buy".equalsIgnoreCase(side)
//                     ? currentPrice * (1 + LIMIT_ORDER_BUFFER_PCT)
//                     : currentPrice * (1 - LIMIT_ORDER_BUFFER_PCT);
//             double tick = getTickSize(pair);
//             // FIX: put the exact BigDecimal in the JSON, not a double, so the
//             // exchange's tick-divisibility check never sees floating-point
//             // noise like 0.026099999999999998.
//             BigDecimal limitPriceBD = roundToTickBD(rawLimitPrice, tick);
//             double limitPrice = limitPriceBD.doubleValue(); // for logging only

//             JSONObject order = new JSONObject();
//             order.put("side",                       side.toLowerCase());
//             order.put("pair",                       pair);
//             order.put("order_type",                 "limit_order");
//             order.put("price",                      limitPriceBD);
//             order.put("total_quantity",             qty);
//             order.put("leverage",                   lev);
//             order.put("notification",               notif);
//             order.put("time_in_force",              "good_till_cancel");
//             order.put("hidden",                     false);
//             order.put("post_only",                  false);
//             order.put("position_margin_type",       marginType);
//             order.put("margin_currency_short_name", marginCcy);
//             JSONObject body = new JSONObject();
//             body.put("timestamp", Instant.now().toEpochMilli());
//             body.put("order", order);
//             String resp = authPost(
//                     BASE_URL + "/exchange/v1/derivatives/futures/orders/create", body.toString());
//             return resp.startsWith("[")
//                     ? new JSONArray(resp).getJSONObject(0)
//                     : new JSONObject(resp);
//         } catch (Exception e) {
//             System.err.println("placeFuturesOrder: " + e.getMessage());
//             return null;
//         }
//     }

//     public static void setTpSl(String posId, double tp, double sl, String pair) {
//         try {
//             double tick = getTickSize(pair);
//             // FIX: same BigDecimal-exact rounding as the entry order — avoids
//             // create_tpsl being silently rejected for the same
//             // divisible-by-tick reason.
//             BigDecimal rtp = roundToTickBD(tp, tick);
//             BigDecimal rsl = roundToTickBD(sl, tick);
//             JSONObject tpObj = new JSONObject();
//             tpObj.put("stop_price",  rtp);
//             tpObj.put("limit_price", rtp);
//             tpObj.put("order_type",  "take_profit_market");
//             JSONObject slObj = new JSONObject();
//             slObj.put("stop_price",  rsl);
//             slObj.put("limit_price", rsl);
//             slObj.put("order_type",  "stop_market");
//             JSONObject payload = new JSONObject();
//             payload.put("timestamp",   Instant.now().toEpochMilli());
//             payload.put("id",          posId);
//             payload.put("take_profit", tpObj);
//             payload.put("stop_loss",   slObj);
//             String resp = authPost(
//                     BASE_URL + "/exchange/v1/derivatives/futures/positions/create_tpsl",
//                     payload.toString());
//             JSONObject r = new JSONObject(resp);
//             System.out.println(r.has("err_code_dcx") ? "  TP/SL error: " + r : "  TP/SL set successfully!");
//         } catch (Exception e) {
//             System.err.println("setTpSl: " + e.getMessage());
//         }
//     }

//     public static String getPositionId(String pair) {
//         for (int attempt = 1; attempt <= POSITION_ID_MAX_RETRIES; attempt++) {
//             try {
//                 JSONObject p = findPosition(pair);
//                 if (p != null && p.has("id")) return p.getString("id");
//             } catch (Exception e) {
//                 System.err.println("getPositionId attempt " + attempt + ": " + e.getMessage());
//             }
//             try {
//                 TimeUnit.MILLISECONDS.sleep(POSITION_ID_RETRY_DELAY_MS);
//             } catch (InterruptedException ignored) {}
//         }
//         return null;
//     }

//     private static Set<String> getActivePositions() {
//         Set<String> active = new HashSet<>();
//         try {
//             JSONObject body = new JSONObject();
//             body.put("timestamp", Instant.now().toEpochMilli());
//             body.put("page", "1");
//             body.put("size", "100");
//             body.put("margin_currency_short_name", new String[]{"INR", "USDT"});
//             String resp = authPost(BASE_URL + "/exchange/v1/derivatives/futures/positions", body.toString());
//             JSONArray arr = resp.startsWith("[")
//                     ? new JSONArray(resp) : new JSONArray().put(new JSONObject(resp));
//             System.out.println("=== Open Positions (" + arr.length() + ") ===");
//             for (int i = 0; i < arr.length(); i++) {
//                 JSONObject p    = arr.getJSONObject(i);
//                 String    pair  = p.optString("pair", "");
//                 boolean isActive = p.optDouble("active_pos", 0) > 0
//                         || p.optDouble("locked_margin", 0) > 0
//                         || p.optDouble("avg_price", 0) > 0
//                         || p.optDouble("take_profit_trigger", 0) > 0
//                         || p.optDouble("stop_loss_trigger", 0) > 0;
//                 if (isActive) {
//                     System.out.printf("  %s | qty=%.2f | entry=%.6f | TP=%.4f | SL=%.4f%n",
//                             pair, p.optDouble("active_pos", 0), p.optDouble("avg_price", 0),
//                             p.optDouble("take_profit_trigger", 0), p.optDouble("stop_loss_trigger", 0));
//                     active.add(pair);
//                 }
//             }
//         } catch (Exception e) {
//             System.err.println("getActivePositions: " + e.getMessage());
//         }
//         return active;
//     }

//     private static HttpURLConnection openGet(String url) throws IOException {
//         HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
//         c.setRequestMethod("GET");
//         c.setConnectTimeout(10_000);
//         c.setReadTimeout(10_000);
//         return c;
//     }

//     private static String publicGet(String url) throws IOException {
//         HttpURLConnection c = openGet(url);
//         if (c.getResponseCode() == 200) return readStream(c.getInputStream());
//         throw new IOException("HTTP " + c.getResponseCode() + " — " + url);
//     }

//     private static String authPost(String url, String json) throws IOException {
//         HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
//         c.setRequestMethod("POST");
//         c.setRequestProperty("Content-Type",     "application/json");
//         c.setRequestProperty("X-AUTH-APIKEY",    API_KEY);
//         c.setRequestProperty("X-AUTH-SIGNATURE", sign(json));
//         c.setConnectTimeout(10_000);
//         c.setReadTimeout(10_000);
//         c.setDoOutput(true);
//         try (OutputStream os = c.getOutputStream()) {
//             os.write(json.getBytes(StandardCharsets.UTF_8));
//         }
//         InputStream is = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
//         return readStream(is);
//     }

//     private static String readStream(InputStream is) throws IOException {
//         return new BufferedReader(new InputStreamReader(is))
//                 .lines().collect(Collectors.joining("\n"));
//     }

//     private static String sign(String payload) {
//         try {
//             Mac mac = Mac.getInstance("HmacSHA256");
//             mac.init(new SecretKeySpec(API_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
//             byte[] b = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
//             StringBuilder sb = new StringBuilder();
//             for (byte x : b) sb.append(String.format("%02x", x));
//             return sb.toString();
//         } catch (Exception e) {
//             throw new RuntimeException("HMAC sign failed", e);
//         }
//     }

//     public static String generateHmacSHA256(String secret, String payload) {
//         return sign(payload);
//     }

//     private static JSONArray aggregateCandles(JSONArray source, int groupSize) {
//         if (source == null || source.length() < groupSize) return null;
//         int n = source.length();
//         int usableCount = (n / groupSize) * groupSize;
//         int startIdx = n - usableCount;
//         JSONArray result = new JSONArray();
//         for (int i = startIdx; i < n; i += groupSize) {
//             double open  = source.getJSONObject(i).getDouble("open");
//             double close = source.getJSONObject(i + groupSize - 1).getDouble("close");
//             double high  = Double.NEGATIVE_INFINITY;
//             double low   = Double.POSITIVE_INFINITY;
//             for (int j = i; j < i + groupSize; j++) {
//                 JSONObject c = source.getJSONObject(j);
//                 high = Math.max(high, c.getDouble("high"));
//                 low  = Math.min(low,  c.getDouble("low"));
//             }
//             JSONObject merged = new JSONObject();
//             merged.put("open", open);
//             merged.put("close", close);
//             merged.put("high", high);
//             merged.put("low", low);
//             result.put(merged);
//         }
//         return result;
//     }
// }

