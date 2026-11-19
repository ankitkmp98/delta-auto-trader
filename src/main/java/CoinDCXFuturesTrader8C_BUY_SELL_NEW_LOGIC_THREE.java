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
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// =============================================================================
// SUPERTREND + EMA + 5M BREAKOUT STRATEGY  (all timeframes judged on the DEVELOPING candle)
//
//   Every timeframe candle used here is a DEVELOPING candle:
//     open  = open of the FIRST 5M candle of its UTC bucket
//     high/low = built from the 5M candles of that bucket + the live price
//     close = live price
//   Already-closed candles are only used as history (older Supertrend / EMA bars), never as the decision candle.
//
//   STAGE 1 (cheap, every minute, uses the hourly-cached history + 1 small 5M request):
//     Supertrend(10, 3.0) on 1H, 2H, 3H, 4H (last bar = developing bar), plus colour of the developing 1H and 2H candle.
//       LONG  candidate : all four Supertrend RED   + developing 1H GREEN + developing 2H GREEN   (ST_CONTRARIAN = true)
//       SHORT candidate : all four Supertrend GREEN + developing 1H RED   + developing 2H RED
//     (ST_CONTRARIAN = false flips the Supertrend side: LONG = all four GREEN, SHORT = all four RED)
//
//   STAGE 2 (only for candidates, with the live price, ALL must pass = AND):
//     3H developing candle colour == direction (price vs open, DOJI rejected)
//     4H developing candle colour == direction
//     15M EMA9 vs EMA21 (closes of completed 15M bars + live price as the developing close)
//     5M swing breakout: live price beyond the last confirmed swing high/low (3-3 pivot, 60-candle lookback), fresh only
//   Just before the order, fresh 5M candles + fresh price are fetched and BOTH stages are checked again.
//
//   SL / TP = fixed % from the ACTUAL average filled entry price.
//
//   BREAK-EVEN + PROFIT LOCK (BREAKEVEN_ENABLED):  unchanged (SL only, one-time, never backwards)
//   DYNAMIC TRAILING (TRAILING_ENABLED):           unchanged (SL and TP shift together, state persisted)
//
//   PORTFOLIO PROFIT TARGET (PORTFOLIO_PROFIT_TARGET_ENABLED):  NEW
//     When the COMBINED unrealized profit of all open positions reaches PORTFOLIO_PROFIT_TARGET_PERCENT of the
//     total locked margin, ALL positions are closed; entries resume after a short pause.
//     Works alongside break-even / trailing / fixed SL-TP (they stay active exactly as before).
//
//   Offline tests:  java -cp <classes>:<org.json jar> CoinDCXFuturesTrader8C_BUY_SELL_NEW_LOGIC_THREE --selftest
// =============================================================================
public class CoinDCXFuturesTrader8C_BUY_SELL_NEW_LOGIC_THREE {

    // =========================================================================
    // Execution mode  (false = PAPER. Switch to true only after paper results are good)
    // =========================================================================
    static final boolean LIVE_TRADING_ENABLED = true;

    // =========================================================================
    // Fixed SL / TP (percent of the actual average entry price) -- initial values at entry
    // (reduced from 10 / 25)
    // =========================================================================
    static final double  FIXED_SL_PERCENT = 4.0;
    static final double  FIXED_TP_PERCENT = 15.0;

    // =========================================================================
    // Break-even + Profit Lock  (LIVE mode only)  -- SL only, TP is never changed by this feature
    // =========================================================================
    static final boolean BREAKEVEN_ENABLED = true;           // MASTER SWITCH: false = break-even / profit-lock OFF
    static final double  BREAKEVEN_TRIGGER_PERCENT = 2.0;    // favourable move (% of entry) that activates break-even
    static final double  PROFIT_LOCK_PERCENT       = 0.5;    // SL goes to entry +PROFIT_LOCK% (LONG) / entry -PROFIT_LOCK% (SHORT). 0 = exact break-even

    // =========================================================================
    // Dynamic trailing SL / TP  (LIVE mode only)
    // =========================================================================
    static final boolean TRAILING_ENABLED = true;            // MASTER SWITCH: false = fixed SL/TP only, no trailing
    static final double  TRAIL_ACTIVATION_PERCENT = 2.5;     // trailing starts after price moves this % in profit
    static final double  TRAIL_STEP_PERCENT       = 0.1;     // SL/TP are moved only when price advanced this % beyond the last trail point
    static final long    TRAIL_CHECK_INTERVAL_MS  = 5_000L; // how often open positions are checked for trailing
    static final int     TRAIL_UPDATE_MAX_RETRIES = 3;       // SL/TP replace attempts per update
    static final long    TRAIL_VERIFY_DELAY_MS    = 800;  // wait before reading the position back after an update
    static final long    TRAIL_RETRY_BACKOFF_MS   = 15_000L; // after a failed update, wait before trying again
    static final long    TRAIL_STATUS_LOG_MS      = 60_000L; // status log per position at most once per this interval
    static final double  TRAIL_ENTRY_MISMATCH_PCT = 0.3;     // saved state whose entry differs more than this from the exchange = old/other position
    static final String  TRADE_STATE_FILE = "trade_state_v4.json";

    // =========================================================================
    // PORTFOLIO PROFIT TARGET  (LIVE mode only)                                              [NEW]
    //   combined profit % = (sum of unrealized PnL of all open positions) / (sum of their margin) * 100
    //   margin per position = |qty| * avgEntry / leverage   (isolated margin, same currency as the PnL)
    //   When combined profit % >= PORTFOLIO_PROFIT_TARGET_PERCENT -> close ALL positions, pause entries briefly, resume.
    //   Note: at 5x leverage, 3% of margin = 0.6% price move.
    // =========================================================================
    static final boolean PORTFOLIO_PROFIT_TARGET_ENABLED = true;    // MASTER SWITCH: false = feature OFF
    static final double  PORTFOLIO_PROFIT_TARGET_PERCENT = 6.0;     // edit this to change the target (% of total locked margin)
    static final long    PORTFOLIO_CHECK_INTERVAL_MS     = 5_000L;  // how often the combined profit is checked
    static final long    PORTFOLIO_REENTRY_PAUSE_MS      = 30_000L; // after a close-all, no new entries for this long
    static final long    PORTFOLIO_STATUS_LOG_MS         = 60_000L; // status log at most once per this interval

    // =========================================================================
    // API configuration (unchanged)
    // =========================================================================
    private static final String API_KEY    = System.getenv("DELTA_API_KEY");
    private static final String API_SECRET = System.getenv("DELTA_API_SECRET");
    private static final String BASE_URL       = "https://api.coindcx.com";
    private static final String PUBLIC_API_URL = "https://public.coindcx.com";

    private static final int MAX_ENTRY_PRICE_CHECKS = 20;
    private static final int ENTRY_CHECK_DELAY_MS    = 1000;

    private static final int  TPSL_MAX_RETRIES    = 3;
    private static final long TPSL_RETRY_DELAY_MS = 2000L;

    private static final int  CANCEL_MAX_RETRIES     = 3;
    private static final long CANCEL_RETRY_DELAY_MS  = 1000L;
    private static final long CANCEL_VERIFY_DELAY_MS = 1500L;

    private static final long TICK_CACHE_TTL_MS = 3_600_000L;

    private static final int MAX_OPEN_POSITIONS = 50;

    private static final int  POSITION_ID_MAX_RETRIES = 5;
    private static final long POSITION_ID_RETRY_DELAY_MS = 1500L;

    // =========================================================================
    // Capital configuration (unchanged except leverage)
    // =========================================================================
    private static final double FIXED_MARGIN = 1000.0;   // legacy sizing: notional INR value = FIXED_MARGIN
    private static final int    LEVERAGE     = 15;        // was 6
    static final double  USDT_INR_RATE       = 102.0;

    // =========================================================================
    // Time constants / candle alignment
    // =========================================================================
    static final long MIN_MS         = 60_000L;
    static final long FIVE_MIN_MS    = 5 * MIN_MS;
    static final long FIFTEEN_MIN_MS = 15 * MIN_MS;
    static final long HOUR_MS        = 60 * MIN_MS;
    static final long TWO_HOUR_MS    = 2 * HOUR_MS;
    static final long THREE_HOUR_MS  = 3 * HOUR_MS;
    static final long FOUR_HOUR_MS   = 4 * HOUR_MS;
    // 15M/1H/2H/3H/4H buckets are epoch(UTC)-aligned plus this offset. VERIFY the 3H candles against the CoinDCX chart;
    // the offset must stay a multiple of 15 minutes.
    static final long HTF_ALIGN_OFFSET_MS = 0L;

    // =========================================================================
    // Indicators
    // =========================================================================
    static final int    ST_PERIOD     = 10;       // Supertrend(10, 3.0) on 1H / 2H / 3H / 4H
    static final double ST_MULTIPLIER = 3.0;
    static final int    EMA_FAST      = 9;        // EMA9 / EMA21 on 15M
    static final int    EMA_SLOW      = 21;

    // =========================================================================
    // Strategy parameters
    // =========================================================================
    // ORDER MATTERS: index 0..3 = 1H, 2H, 3H, 4H
    static final String[] TF_NAMES = {"1H", "2H", "3H", "4H"};
    static final long[]   TF_MS    = {HOUR_MS, TWO_HOUR_MS, THREE_HOUR_MS, FOUR_HOUR_MS};

    // true  : LONG needs Supertrend RED on all four timeframes (reversal style) and SHORT needs all four GREEN
    // false : LONG needs Supertrend GREEN on all four, SHORT needs all four RED (trend-following)
    static final boolean ST_CONTRARIAN = true;

    static final int ST_MIN_BARS      = 25;   // completed bars needed per timeframe before the Supertrend is trusted
    static final int EMA_MIN_BARS_15M = 40;   // completed 15M bars needed for EMA9/EMA21
    static final int EMA_WINDOW_15M   = 120;  // how many 15M bars are fed into the EMA calculation

    static final int SWING_PIVOT_BARS   = 3;    // a swing high/low needs this many lower highs / higher lows on each side
    static final int SWING_LOOKBACK_5M  = 60;   // how far back the previous confirmed swing may be

    static final double EPS = 1e-12;

    // =========================================================================
    // Data fetching
    // =========================================================================
    static final int  MAX_BARS_PER_CALL = 500;
    static final long MIN_REQUEST_GAP_MS = 60L;

    static final int HIST_5M       = 1800;   // 5M history kept per symbol (150h = ~37 completed 4H bars). Refreshed once per hour (incremental)
    static final int SCAN_FETCH_5M = 80;     // small request every minute (a developing 4H bucket needs at most 48 candles + margin)

    // =========================================================================
    // Timing / duplicate protection
    // =========================================================================
    static final long COOLDOWN_MS      = 5L * 60 * 1000L;
    static final long SCAN_STEP_MS     = 60_000L;
    static final long SCAN_DELAY_MS    = 5_000L;
    static final long PAPER_MAX_HOLD_MS = 24L * 60 * 60 * 1000L;

    static final boolean LOG_TRADES_CSV = true;
    static final boolean LOG_NO_TRADE_DETAIL = true;   // log full detail for symbols that passed stage 1 but not stage 2
    static final String  TRADE_LOG_FILE = "trade_log_v4.csv";
    static final String  PAPER_LOG_FILE = "paper_trade_log_v4.csv";

    private static final double LIMIT_ORDER_BUFFER_PCT = 0.0005;

    static class TradeInfo {
        boolean isLong;
        double entry, sl, tp;           // sl / tp = the levels the bot believes are currently on the exchange
        double initialSl, risk;
        double maxPrice, minPrice;      // highest / lowest price seen since entry
        long openedMs;
        String ctx = "";
        // ---- trailing state (persisted) ----
        boolean trailActive;            // activation level reached
        double trailRef;                // last price point SL/TP were trailed to (activation level until the first shift)
        long lastTrailMs;
        boolean beDone;                 // break-even / profit-lock already handled for this trade (persisted)
        // ---- runtime only (NOT persisted) ----
        long nextTrailTryMs;
        long lastStatusLogMs;
    }
    static class PaperTrade {
        boolean isLong;
        double entry, sl, tp;
        long openedMs;
        String ctx = "";
    }
    private static final Map<String, TradeInfo> tradeState = new ConcurrentHashMap<>();
    private static final Map<String, PaperTrade> paperTrades = new ConcurrentHashMap<>();
    private static final Map<String, JSONObject> instrumentCache = new ConcurrentHashMap<>();
    private static long lastCacheUpdate = 0;
    private static final Map<String, Long> lastTradeTime = new ConcurrentHashMap<>();
    private static final Map<String, Long> lastSignalCandle = new ConcurrentHashMap<>();

    private static final String[] COIN_SYMBOLS = {
        "BANK","PIEVERSE","ALPINE","OG","XAU","CVC","KAT","SOON","MANTRA","LAB","EDGE","PIPPIN","BSB",
        "BASED","MUBARAK","TUT","MMT","F","ARKM","GRVT","PONS","AIOT","AGT","JCT","ZEST","ICNT","MAGIC",
        "ON","TA","PROVE","SKY","AVNT","KGEN","DATAIP","CYS","LIT","DOGE","ELSA","SKR","ESP","SOL","B","MYX",
        "H","IDOL","LTC","TRX","ROSE","IOST","ESPORTS","ALGO","FARTCOIN","KAVA","BAND","RLC","PUMP","ZORA",
        "DOT","GIGGLE","Q","RSR","FLOCK","CARV","BEL","HEMI","COTI","C98","ARPA","NEIRO","PEOPLE","WIF","MANTA",
        "IMX","XAI","API3","GMT","APE","JASMY","INJ","ETHFI","STBL","W","SAGA","SPELL","RARE","HOLO","LUNA2","G",
        "SUN","CL","ZKC","O","1000LUNC","MORPHO","ARB","WET","SPX","ME","AVA","NIGHT","PENGU","LUMIA","FLUID","T",
        "SEI","AIXBT","MINA","AKE","BICO","KMNO","JOE","BLUR","MAV","PENDLE","ALT","CRV","PHA","BILL","FF","YGG",
        "CYBER","BIGTIME","ZKP","WAXP","BREV","POLYX","GAS","POWR","MEME","OPN","ILV","KAS","1000BONK","BCH","SUPER",
        "ETHW","CTR","JUP","LINK","RONIN","XMR","DYM","ENS","NEO","GLM","PORTAL","JST","AXL","METIS","BOME","UNI",
        "RENDER","ZEN","1INCH","ALCH","1000XEC","STABLE","VTHO","DIA","MOODENG","SENT","SONIC","SAFE","1000FLOKI",
        "CETUS","ARIA","AKT","TAKE","ARK","CHILLGUY","BSV","ANIME","CAKE","VIRTUAL","DEEP","TURTLE","TST","USUAL",
        "ZETA","SHELL","PIXEL","NIL","VVV","FORM","PAXG","CHIP","ATH","INIT","LISTA","SKYAI","AWE","HUMA","TNSR",
        "TAO","POPCAT","THE","1000CAT","PLUME","ACT","BEAMX","M","HIVE","LSK","RVN","BIO","BB","TURBO","IO","RPL",
        "POL","1MBABYDOGE","FIDA","ARC","1000000MOG","REZ","BR","SWARMS","TRUMP","AIO","RE","CROSS","DYDX","CAP",
        "CATI","NAORIS","BANANAS31","AR","PNUT","BAN","ORCA","LINEA","MOVE","DEXE","ETH","GRIFFAIN","COOKIE","AERO",
        "S","RAYSOL","ERA","SOLV","XRP","AVAAI","MASK","B2","PARTI","MAVIA","HEI","TRUTH","JELLYJELLY","FRAX","DASH",
        "BZ","OPG","ROBO","BTW","BNB","FLOW","BTC","ATOM","ZIL","XLM","FET","NEWT","SOPH","KNC","COMP","NATGAS","BMT",
        "ASTR","CFX","MERL","YFI","SAPIEN","RUNE","BTR","STX","AVAX","LA","NEAR","SLP","PHAROS","LIGHT","1000PEPE",
        "EDEN","LYN","ONE","MET","RECALL","MTL","4","1000SHIB","BLUAI","APR","GTC","EGLD","RIVER","CTSI","TAIKO",
        "AGLD","BNT","ENJ","IOTX","DOS","SQD","NOM","FIL","RIF","BULLA","IN","EVAA","AXS","TWT","SUI","STEEM","CLO",
        "CHZ","AT","ANKR","SPACE","STO","MEGA","COPPER","DOOD","CHR","SUSHI","MANA","BERA","CELR","AIGENSYN","MARSCOIN",
        "TAC","SLX","TAG","CT","DODOX","ADA","LPT","GRAM","DUSK","THETA","GMX","TIA","TREE","USTC","TRB","GWEI","UAI",
        "TRIA","LDO","ICP","KSM","ONG","UB","USELESS","SSV","ID","HBAR","HAEDAL","US","VELVET","ETC","VET","AUCTION",
        "WLFI","WOO","XAG","ORDI","SFP","BAT","1000RATS","XNY","ACE","ACH","QTUM","CKB","XVS","YB","EDU","ZBT","ZEC",
        "UMA","MOVR","ZRX","PYTH","AAVE","ENA","GRT","SAND","MEW","HMSTR","SANTOS","FLUX","DRIFT","OP","APT","QNT",
        "KAIA","LQTY","XVG","MELANIA","GPS","FHE","RED","SXT","KERNEL","PUNDIX","STRK","ZK","ZRO","GRASS","SCR",
        "GOAT","MOCA","PROM","KAITO","SIREN","WAL","WCT","ASR","A","HYPE","AIN","PLAY","OPEN","XPIN","BLESS","COAI",
        "HANA","ORDER","XAN","CLANKER","BEAT","IRYS","PRL","RAVE","MAGMA","ZAMA","FOGO","ACU","FIGHT","BIRB","INX",
        "AZTEC","CFG","GENIUS","ARX","ALLO","BROCCOLI714","HYPER","XPL","RESOLV","ASTER","KITE","SIGN","HOME","MON",
        "CC","SAHARA","MIRA","EUL","TOWNS","SYRUP","C","DOLO","ALICE","BABY","SOMI","NOT","BARD","SPK","POWER","2Z",
        "BANANA","ENSO","SYN","NXPC","GUN","XTZ","ONT","SKL","HOT","JTO","DOGS","EIGEN","GALA","NMR","CGPT","ZEREBRO",
        "VANA","OGN","CELO","COW","0G","IOTA","SNX","WLD","1000SATS","ONDO","AEVO","BRETT","LAYER","TLM","KOMA"
    };

    // Optional liquid subset (edit freely). When USE_LIQUID_LIST = true only these symbols are traded.
    static final boolean USE_LIQUID_LIST = false;
    private static final Set<String> LIQUID_SYMBOLS = new HashSet<>(Arrays.asList(
        "BTC","ETH","SOL","XRP","DOGE","BNB","ADA","AVAX","LINK","DOT","LTC","BCH","TRX","ATOM",
        "NEAR","APT","ARB","OP","SUI","INJ","UNI","AAVE","FIL","ETC","XLM","HBAR","ICP","TIA","SEI",
        "WLD","ONDO","1000PEPE","1000SHIB","FET","RENDER","TAO","ENA","JUP","PENDLE","STX","IMX",
        "LDO","CRV","SAND","MANA","GALA","AXS","APE","ALGO","EGLD","THETA","VET","XMR","DASH","ZEC",
        "NEO","QTUM","XTZ","FLOW","KAS","TRUMP","WIF","1000BONK","HYPE","PUMP","FARTCOIN","PENGU",
        "WLFI","COMP","SNX","DYDX","GMX","1INCH","RUNE","POL","BERA","ORDI","PYTH","STRK","ZRO",
        "EIGEN","ETHFI","AEVO"));

    private static final Set<String> INTEGER_QTY_PAIRS = Stream.of(COIN_SYMBOLS)
            .flatMap(s -> Stream.of("B-" + s + "_USDT", s + "_USDT"))
            .collect(Collectors.toCollection(HashSet::new));

    private static final String[] COINS_TO_TRADE = Stream.of(COIN_SYMBOLS)
            .filter(s -> !USE_LIQUID_LIST || LIQUID_SYMBOLS.contains(s))
            .map(s -> "B-" + s + "_USDT")
            .toArray(String[]::new);

    // =========================================================================
    // Small helpers
    // =========================================================================
    static int colourOf(double open, double close) { return close > open ? 1 : (close < open ? -1 : 0); }
    static String colName(int c) { return c > 0 ? "GREEN" : (c < 0 ? "RED" : "DOJI"); }
    static String fmtTime(long ms) { return Instant.ofEpochMilli(ms).toString(); }

    static long alignedStart(long ms, long bucketMs, long offsetMs) {
        return Math.floorDiv(ms - offsetMs, bucketMs) * bucketMs + offsetMs;
    }

    private static void sleepMs(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    // =========================================================================
    // Candle helpers
    // =========================================================================
    // Assumes the candle "time" field is the candle OPEN time (seconds or ms).
    static long candleTimeMs(JSONObject c) {
        double t = c.optDouble("time", -1);
        if (t <= 0) return -1;
        long v = (long) t;
        return v < 100_000_000_000L ? v * 1000L : v;
    }

    static JSONArray dropLastIfForming(JSONArray arr) {
        if (arr == null || arr.length() < 2) return arr;
        JSONArray out = new JSONArray();
        for (int i = 0; i < arr.length() - 1; i++) out.put(arr.getJSONObject(i));
        return out;
    }

    // Sorted oldest-first, duplicate timestamps removed. Null if input null or any candle lacks a timestamp.
    static JSONArray sortByTime(JSONArray src) {
        if (src == null) return null;
        List<JSONObject> l = new ArrayList<>();
        for (int i = 0; i < src.length(); i++) {
            JSONObject c = src.getJSONObject(i);
            if (candleTimeMs(c) < 0) return null;
            l.add(c);
        }
        l.sort(Comparator.comparingLong(CoinDCXFuturesTrader8C_BUY_SELL_NEW_LOGIC_THREE::candleTimeMs));
        JSONArray out = new JSONArray();
        long prev = -1;
        for (JSONObject c : l) {
            long t = candleTimeMs(c);
            if (t == prev) continue;
            out.put(c);
            prev = t;
        }
        return out;
    }

    static JSONArray fetchCandlesPaged(String pair, String res, int minsPerBar, int count) {
        long nowSec = Instant.now().getEpochSecond();
        if (count <= MAX_BARS_PER_CALL) {
            return getCandlestickRange(pair, res, nowSec - (long) minsPerBar * 60L * count, nowSec);
        }
        List<JSONObject> all = new ArrayList<>();
        long toSec = nowSec;
        int remaining = count;
        boolean first = true;
        while (remaining > 0) {
            int n = Math.min(MAX_BARS_PER_CALL, remaining);
            long fromSec = toSec - (long) minsPerBar * 60L * n;
            JSONArray part = getCandlestickRange(pair, res, fromSec, toSec);
            if (part == null) {
                if (first) return null;
                break;
            }
            for (int i = 0; i < part.length(); i++) all.add(part.getJSONObject(i));
            first = false;
            remaining -= n;
            toSec = fromSec - 1;
            if (part.length() == 0) break;
        }
        for (JSONObject c : all) {
            if (candleTimeMs(c) < 0) {
                System.err.println("  [ERROR] candle has no 'time' field — cannot merge pages for " + pair);
                return null;
            }
        }
        all.sort(Comparator.comparingLong(CoinDCXFuturesTrader8C_BUY_SELL_NEW_LOGIC_THREE::candleTimeMs));
        JSONArray out = new JSONArray();
        long prev = -1;
        for (JSONObject c : all) {
            long t = candleTimeMs(c);
            if (t == prev) continue;
            out.put(c);
            prev = t;
        }
        return out;
    }

    private static long lastRequestAt = 0;
    private static synchronized void throttle() {
        long now = System.currentTimeMillis();
        long wait = MIN_REQUEST_GAP_MS - (now - lastRequestAt);
        if (wait > 0) {
            try { Thread.sleep(wait); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        lastRequestAt = System.currentTimeMillis();
    }

    private static JSONArray getCandlestickRange(String pair, String resolution, long from, long to) {
        try {
            throttle();
            String url = PUBLIC_API_URL + "/market_data/candlesticks"
                    + "?pair=" + pair + "&from=" + from + "&to=" + to
                    + "&resolution=" + resolution + "&pcode=f";
            HttpURLConnection conn = openGet(url);
            int code = conn.getResponseCode();
            if (code == 200) {
                JSONObject r = new JSONObject(readStream(conn.getInputStream()));
                if ("ok".equals(r.optString("s"))) return r.getJSONArray("data");
                System.err.println("  Candle s=" + r.optString("s") + " " + pair + "/" + resolution);
            } else {
                System.err.println("  Candle HTTP " + code + " " + pair + "/" + resolution);
            }
        } catch (Exception e) {
            System.err.println("  getCandlestickRange(" + pair + "/" + resolution + "): " + e.getMessage());
        }
        return null;
    }

    // =========================================================================
    // Compact 5M series (arrays, so a long history for ~500 symbols stays small in memory)
    // =========================================================================
    static class Series5 {
        final int n;
        final long[] t;
        final double[] o, h, l, c;
        Series5(int n) {
            this.n = n;
            t = new long[n]; o = new double[n]; h = new double[n]; l = new double[n]; c = new double[n];
        }
    }

    // Input must already be sorted + de-duplicated (sortByTime). Null if input null or a candle is malformed.
    static Series5 toSeries(JSONArray a) {
        if (a == null) return null;
        Series5 s = new Series5(a.length());
        for (int i = 0; i < s.n; i++) {
            JSONObject j = a.getJSONObject(i);
            long t = candleTimeMs(j);
            double o = j.optDouble("open", Double.NaN), h = j.optDouble("high", Double.NaN);
            double l = j.optDouble("low", Double.NaN), c = j.optDouble("close", Double.NaN);
            if (t < 0 || Double.isNaN(o) || Double.isNaN(h) || Double.isNaN(l) || Double.isNaN(c)) return null;
            s.t[i] = t; s.o[i] = o; s.h[i] = h; s.l[i] = l; s.c[i] = c;
        }
        return s;
    }

    // a = older data, b = newer data. Everything in a that is not older than b's first candle is replaced by b.
    static Series5 mergeSeries(Series5 a, Series5 b) {
        if (a == null || a.n == 0) return b;
        if (b == null || b.n == 0) return a;
        int cut = a.n;
        while (cut > 0 && a.t[cut - 1] >= b.t[0]) cut--;
        Series5 r = new Series5(cut + b.n);
        System.arraycopy(a.t, 0, r.t, 0, cut); System.arraycopy(b.t, 0, r.t, cut, b.n);
        System.arraycopy(a.o, 0, r.o, 0, cut); System.arraycopy(b.o, 0, r.o, cut, b.n);
        System.arraycopy(a.h, 0, r.h, 0, cut); System.arraycopy(b.h, 0, r.h, cut, b.n);
        System.arraycopy(a.l, 0, r.l, 0, cut); System.arraycopy(b.l, 0, r.l, cut, b.n);
        System.arraycopy(a.c, 0, r.c, 0, cut); System.arraycopy(b.c, 0, r.c, cut, b.n);
        return r;
    }

    static Series5 trimSeries(Series5 s, int maxN) {
        if (s == null || s.n <= maxN) return s;
        int from = s.n - maxN;
        Series5 r = new Series5(maxN);
        System.arraycopy(s.t, from, r.t, 0, maxN);
        System.arraycopy(s.o, from, r.o, 0, maxN);
        System.arraycopy(s.h, from, r.h, 0, maxN);
        System.arraycopy(s.l, from, r.l, 0, maxN);
        System.arraycopy(s.c, from, r.c, 0, maxN);
        return r;
    }

    // ---- hourly history cache: refreshed at most once per UTC hour, incrementally when possible ----
    private static final Map<String, Series5> histCache = new ConcurrentHashMap<>();
    private static final Map<String, Long> histHour = new ConcurrentHashMap<>();

    private static Series5 getHistory(String pair, long nowMs) {
        long hour = alignedStart(nowMs, HOUR_MS, 0L);
        Series5 h = histCache.get(pair);
        Long fetched = histHour.get(pair);
        if (h != null && fetched != null && fetched == hour) return h;
        Series5 fresh = null;
        if (h != null && h.n > 0) {
            long need = (nowMs - h.t[h.n - 1]) / FIVE_MIN_MS + 3;
            if (need <= MAX_BARS_PER_CALL - 20) {
                Series5 inc = toSeries(sortByTime(fetchCandlesPaged(pair, "5", 5, (int) need)));
                if (inc != null && inc.n > 0) fresh = trimSeries(mergeSeries(h, inc), HIST_5M);
            }
        }
        if (fresh == null) fresh = toSeries(sortByTime(fetchCandlesPaged(pair, "5", 5, HIST_5M)));
        if (fresh == null || fresh.n == 0) return h;      // keep the old history; the validation below will reject gaps
        histCache.put(pair, fresh);
        histHour.put(pair, hour);
        return fresh;
    }

    // Cached history + a fresh small request. Null if the fresh request failed.
    private static Series5 getSeries(String pair, long nowMs) {
        Series5 hist = getHistory(pair, nowMs);
        Series5 recent = toSeries(sortByTime(fetchCandlesPaged(pair, "5", 5, SCAN_FETCH_5M)));
        if (recent == null || recent.n == 0) return null;
        return hist == null ? recent : mergeSeries(hist, recent);
    }

    // 5M series validation. Null if usable, otherwise "<code>: <reason>".
    static String validate5m(Series5 s, long nowMs) {
        if (s == null || s.n == 0) return "noData: no 5M candle data";
        long cur5 = alignedStart(nowMs, FIVE_MIN_MS, 0L);
        for (int i = 0; i < s.n; i++)
            if (s.t[i] % FIVE_MIN_MS != 0) return "misaligned: 5M candle at " + fmtTime(s.t[i]);
        long maxT = s.t[s.n - 1];
        if (maxT > cur5) return "future: newest 5M candle " + fmtTime(maxT) + " after current " + fmtTime(cur5);
        // CoinDCX sometimes does not return the forming 5M candle yet: the previous candle is fine,
        // because the live price supplies the developing close / high / low.
        if (maxT < cur5 - FIVE_MIN_MS) return "stale: newest 5M candle " + fmtTime(maxT) + " but forming 5M starts " + fmtTime(cur5);
        return null;
    }

    // =========================================================================
    // Higher-timeframe bars built from 5M candles
    // =========================================================================
    static class Bars {
        final long[] t;
        final double[] o, h, l, c;
        int n;
        Bars(int cap) { t = new long[cap]; o = new double[cap]; h = new double[cap]; l = new double[cap]; c = new double[cap]; }
    }

    // COMPLETE buckets only (every 5M candle present), and only buckets that start before `beforeStart`
    // (= start of the developing bucket). These are history bars, never the decision candle.
    static Bars aggComplete(Series5 s, long bucketMs, long beforeStart) {
        int need = (int) (bucketMs / FIVE_MIN_MS);
        Bars b = new Bars(s.n / need + 2);
        int i = 0;
        while (i < s.n) {
            long bs = alignedStart(s.t[i], bucketMs, HTF_ALIGN_OFFSET_MS);
            int j = i;
            double hi = Double.NEGATIVE_INFINITY, lo = Double.POSITIVE_INFINITY;
            while (j < s.n && alignedStart(s.t[j], bucketMs, HTF_ALIGN_OFFSET_MS) == bs) {
                hi = Math.max(hi, s.h[j]);
                lo = Math.min(lo, s.l[j]);
                j++;
            }
            if (j - i == need && s.t[i] == bs && bs < beforeStart) {
                b.t[b.n] = bs; b.o[b.n] = s.o[i]; b.h[b.n] = hi; b.l[b.n] = lo; b.c[b.n] = s.c[j - 1];
                b.n++;
            }
            i = j;
        }
        return b;
    }

    // =========================================================================
    // DEVELOPING candle of one timeframe
    // =========================================================================
    static class Dev {
        String tf;
        long tfMs, start;
        boolean ok;                 // data valid
        String reason = "";
        double open, high, low, price;
        int colour;                 // +1 GREEN, -1 RED, 0 DOJI
        boolean stBull;             // Supertrend colour with this developing bar as the last bar (true = GREEN)
        int stBars;                 // completed bars used before the developing bar
    }

    // open = open of the 5M candle that starts the bucket; high/low = 5M candles of the bucket + live price; close = live price.
    static Dev developing(Series5 s, long nowMs, double price, int k) {
        Dev e = new Dev();
        e.tf = TF_NAMES[k];
        e.tfMs = TF_MS[k];
        e.start = alignedStart(nowMs, e.tfMs, HTF_ALIGN_OFFSET_MS);
        e.price = price;
        long cur5 = alignedStart(nowMs, FIVE_MIN_MS, 0L);
        long last5 = Math.min(cur5, s.t[s.n - 1]);
        int expected = (int) ((last5 - e.start) / FIVE_MIN_MS) + 1;
        int count = 0;
        double open = Double.NaN, hi = price, lo = price;
        for (int i = s.n - 1; i >= 0 && s.t[i] >= e.start; i--) {
            if (s.t[i] > cur5) continue;
            count++;
            if (s.t[i] == e.start) open = s.o[i];
            hi = Math.max(hi, s.h[i]);
            lo = Math.min(lo, s.l[i]);
        }
        if (!(open > 0) || Double.isInfinite(open)) {
            e.reason = "noOpen: first 5M candle of the bucket (" + fmtTime(e.start) + ") not available yet";
            return e;
        }
        if (count != expected) {
            e.reason = "gap: " + count + "/" + expected + " 5M candles in developing " + e.tf + " bucket";
            return e;
        }
        e.open = open; e.high = hi; e.low = lo;
        e.colour = colourOf(open, price);
        e.ok = true;
        return e;
    }

    // =========================================================================
    // Full symbol evaluation (used by the scan AND by the pre-order revalidation)
    // =========================================================================
    static class Eval {
        String signal = "NO TRADE";    // BUY / SELL / NO TRADE
        String code = "";
        String reason = "";
        int dir;                       // stage-1 candidate: +1 LONG, -1 SHORT, 0 none
        final Dev[] dev = new Dev[4];
        double ema9, ema21, brkLevel;
        double price;
    }

    private static Eval fail(Eval r, String code, String reason) {
        r.signal = "NO TRADE"; r.code = code; r.reason = reason;
        return r;
    }

    static String stName(boolean bull) { return bull ? "GREEN" : "RED"; }

    static String summary(Eval r) {
        StringBuilder sb = new StringBuilder();
        for (Dev d : r.dev) if (d != null && d.ok) sb.append(d.tf).append(" ST=").append(stName(d.stBull))
                .append(" candle=").append(colName(d.colour)).append("; ");
        return sb.toString().trim();
    }

    // STAGE 1: Supertrend on 1H/2H/3H/4H (developing bar last) + developing 1H and 2H candle colour.
    // Returns +1 / -1 for a candidate, 0 otherwise (r.code / r.reason are set then).
    static int stage1(Series5 s, double price, long nowMs, Eval r) {
        String bad = validate5m(s, nowMs);
        if (bad != null) { fail(r, "5m_" + bad.substring(0, bad.indexOf(':')), bad); return 0; }
        for (int k = 0; k < 4; k++) {
            Dev d = developing(s, nowMs, price, k);
            r.dev[k] = d;
            if (!d.ok) { fail(r, "data", "data: " + d.tf + " " + d.reason); return 0; }
            Bars b = aggComplete(s, d.tfMs, d.start);
            d.stBars = b.n;
            if (b.n < ST_MIN_BARS) { fail(r, "stHistory", "history: only " + b.n + " completed " + d.tf + " bars (need " + ST_MIN_BARS + ")"); return 0; }
            if (b.t[b.n - 1] != d.start - d.tfMs) { fail(r, "histGap", "history gap: last completed " + d.tf + " bar is not the one right before the developing bar"); return 0; }
            int m = b.n + 1;
            double[] hi = new double[m], lo = new double[m], cl = new double[m];
            System.arraycopy(b.h, 0, hi, 0, b.n);
            System.arraycopy(b.l, 0, lo, 0, b.n);
            System.arraycopy(b.c, 0, cl, 0, b.n);
            hi[m - 1] = d.high; lo[m - 1] = d.low; cl[m - 1] = price;      // developing bar: close = live price
            d.stBull = calcSupertrend(hi, lo, cl, ST_PERIOD, ST_MULTIPLIER)[m - 1];
        }
        boolean allGreen = true, allRed = true;
        for (Dev d : r.dev) { if (d.stBull) allRed = false; else allGreen = false; }
        boolean longSt  = ST_CONTRARIAN ? allRed : allGreen;
        boolean shortSt = ST_CONTRARIAN ? allGreen : allRed;
        int c1 = r.dev[0].colour, c2 = r.dev[1].colour;
        if (longSt && c1 == 1 && c2 == 1) { r.dir = 1; return 1; }
        if (shortSt && c1 == -1 && c2 == -1) { r.dir = -1; return -1; }
        fail(r, "stage1", "stage1 not aligned: " + summary(r));
        return 0;
    }

    // EMA9 / EMA21 of the 15M closes: completed 15M bars + the live price as the developing close. Null if not enough data.
    static double[] ema15m(Series5 s, double price, long nowMs, Eval r) {
        long start15 = alignedStart(nowMs, FIFTEEN_MIN_MS, HTF_ALIGN_OFFSET_MS);
        Bars b = aggComplete(s, FIFTEEN_MIN_MS, start15);
        if (b.n < EMA_MIN_BARS_15M) { if (r != null) fail(r, "emaHistory", "history: only " + b.n + " completed 15M bars"); return null; }
        if (b.t[b.n - 1] != start15 - FIFTEEN_MIN_MS) { if (r != null) fail(r, "emaGap", "history gap before the developing 15M bar"); return null; }
        int use = Math.min(b.n, EMA_WINDOW_15M);
        double[] cl = new double[use + 1];
        System.arraycopy(b.c, b.n - use, cl, 0, use);
        cl[use] = price;
        return new double[]{calcEMA(cl, EMA_FAST), calcEMA(cl, EMA_SLOW)};
    }

    // STAGE 2 (AND): 3H colour, 4H colour (both developing), 15M EMA9 vs EMA21, fresh 5M swing breakout. Sets r.signal on success.
    static void stage2(Series5 s, double price, long nowMs, int dir, Eval r) {
        boolean isLong = dir > 0;
        if (r.dev[2].colour != dir || r.dev[3].colour != dir) {
            fail(r, "htfColour", "3H=" + colName(r.dev[2].colour) + " 4H=" + colName(r.dev[3].colour) + " not both " + (isLong ? "GREEN" : "RED"));
            return;
        }
        double[] e = ema15m(s, price, nowMs, r);
        if (e == null) return;
        r.ema9 = e[0]; r.ema21 = e[1];
        if (isLong ? !(e[0] > e[1]) : !(e[0] < e[1])) {
            fail(r, "ema", String.format("15M EMA9=%.6f EMA21=%.6f against %s", e[0], e[1], isLong ? "LONG" : "SHORT"));
            return;
        }
        // 5M: closed candles + the developing 5M candle (high/low from the forming candle + price, close = price)
        long cur5 = alignedStart(nowMs, FIVE_MIN_MS, 0L);
        int end = 0;
        while (end < s.n && s.t[end] < cur5) end++;
        int need = SWING_LOOKBACK_5M + 2 * SWING_PIVOT_BARS + 6;
        int from = Math.max(0, end - need);
        int m = end - from + 1;
        double[] hi = new double[m], lo = new double[m], cl = new double[m];
        for (int i = from; i < end; i++) { hi[i - from] = s.h[i]; lo[i - from] = s.l[i]; cl[i - from] = s.c[i]; }
        hi[m - 1] = price; lo[m - 1] = price; cl[m - 1] = price;
        for (int i = end; i < s.n; i++) if (s.t[i] == cur5) { hi[m - 1] = Math.max(hi[m - 1], s.h[i]); lo[m - 1] = Math.min(lo[m - 1], s.l[i]); }
        Breakout b = findBreakout(hi, lo, cl, isLong, SWING_PIVOT_BARS, SWING_LOOKBACK_5M);
        r.brkLevel = b.level;
        if (!b.ok) { fail(r, "breakout", "5M breakout: " + b.reason); return; }
        r.signal = isLong ? "BUY" : "SELL";
        r.code = "signal";
        r.reason = String.format("%s | 15M EMA9=%.6f EMA21=%.6f | 5M %s", summary(r), e[0], e[1], b.reason);
    }

    static Eval evaluateStage1(Series5 s, double price, long nowMs) {
        Eval r = new Eval();
        r.price = price;
        if (!(price > 0) || Double.isInfinite(price)) { fail(r, "noPrice", "no valid price"); return r; }
        stage1(s, price, nowMs, r);
        return r;
    }

    // Both stages with the same price (live price for orders).
    static Eval evaluateFull(Series5 s, double price, long nowMs) {
        Eval r = evaluateStage1(s, price, nowMs);
        if (r.dir == 0) return r;
        stage2(s, price, nowMs, r.dir, r);
        return r;
    }

    static String describeDev(Dev e) {
        if (!e.ok) return e.tf + " INVALID (" + e.reason + ")";
        return String.format("%s open=%.6f price=%.6f candle=%s supertrend=%s (%d bars)",
                e.tf, e.open, e.price, colName(e.colour), stName(e.stBull), e.stBars);
    }

    private static void logEval(String pair, Eval ev) {
        for (Dev d : ev.dev) if (d != null) System.out.println("  [ST] " + pair + " " + describeDev(d));
        if (ev.ema9 > 0) System.out.printf("  [ST] %s 15M EMA9=%.6f EMA21=%.6f 5M swing level=%.6f%n", pair, ev.ema9, ev.ema21, ev.brkLevel);
        System.out.println("  [ST] " + pair + " DECISION=" + (ev.signal.equals("BUY") ? "LONG" : ev.signal.equals("SELL") ? "SHORT" : "NO TRADE")
                + " | " + ev.reason);
    }

    static String ctxOf(Eval ev) {
        StringBuilder sb = new StringBuilder();
        for (Dev d : ev.dev) {
            if (d == null || !d.ok) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(d.tf).append(":ST=").append(d.stBull ? "G" : "R").append("/C=").append(d.colour > 0 ? "G" : d.colour < 0 ? "R" : "D");
        }
        sb.append(String.format(" EMA9=%.6f EMA21=%.6f BRK=%.6f", ev.ema9, ev.ema21, ev.brkLevel));
        return sb.toString();
    }

    static class Revalidation {
        String reason;      // null = still valid
        double price;
    }

    // Fresh 5M candles + fresh live price -> full re-evaluation (stage 1 and stage 2, same AND rules).
    private static Revalidation revalidateBeforeOrder(String pair, boolean isLong) {
        Revalidation rv = new Revalidation();
        long now = System.currentTimeMillis();
        Series5 s = getSeries(pair, now);
        rv.price = getLastPrice(pair);
        Eval ev;
        if (s == null) { ev = new Eval(); fail(ev, "noData", "no fresh 5M data"); }
        else ev = evaluateFull(s, rv.price, System.currentTimeMillis());
        String want = isLong ? "BUY" : "SELL";
        rv.reason = ev.signal.equals(want) ? null
                : "pre-order revalidation: now " + ev.signal + " - " + ev.reason;
        System.out.println("  [REVALIDATE] " + pair + " " + want + " price=" + rv.price + " | -> "
                + (rv.reason == null ? "PASS (order allowed)" : "FAIL (no order): " + rv.reason));
        if (rv.reason != null) logEval(pair, ev);
        return rv;
    }

    // =========================================================================
    // 5M swing breakout helper
    // =========================================================================
    static class Breakout {
        boolean ok;
        double level;
        int pivotIdx = -1;
        String reason = "";
    }

    // hi/lo/cl oldest first; the last element is the signal (developing) candle.
    // A swing high/low is "confirmed" when `pivotBars` candles on each side are strictly lower highs / higher lows.
    static Breakout findBreakout(double[] hi, double[] lo, double[] cl, boolean isLong, int pivotBars, int lookback) {
        Breakout b = new Breakout();
        int n = cl.length;
        if (n < pivotBars * 2 + 4) { b.reason = "not enough 5M candles"; return b; }
        int maxPivot = n - 2 - pivotBars;                    // right-side candles must all be before the signal candle
        int minPivot = Math.max(pivotBars, n - 1 - lookback);
        int found = -1;
        for (int i = maxPivot; i >= minPivot && found < 0; i--) {
            boolean pivot = true;
            for (int k = 1; k <= pivotBars && pivot; k++) {
                if (isLong) { if (hi[i] <= hi[i - k] || hi[i] <= hi[i + k]) pivot = false; }
                else        { if (lo[i] >= lo[i - k] || lo[i] >= lo[i + k]) pivot = false; }
            }
            if (pivot) found = i;
        }
        if (found < 0) {
            b.reason = "no confirmed swing " + (isLong ? "high" : "low") + " in last " + lookback + " 5M candles";
            return b;
        }
        double level = isLong ? hi[found] : lo[found];
        b.level = level;
        b.pivotIdx = found;
        for (int j = found + 1; j <= n - 2; j++) {
            if (isLong ? cl[j] > level : cl[j] < level) {
                b.reason = String.format("swing %s %.6f was already broken earlier - not a fresh breakout", isLong ? "high" : "low", level);
                return b;
            }
        }
        double last = cl[n - 1];
        b.ok = isLong ? last > level : last < level;
        b.reason = String.format("swing %s=%.6f livePrice=%.6f", isLong ? "high" : "low", level, last);
        return b;
    }

    // One signal per symbol+direction per developing 15M candle. Returns false if already signalled.
    static boolean registerSignal(Map<String, Long> registry, String key, long candleOpen) {
        Long prev = registry.get(key);
        if (prev != null && prev == candleOpen) return false;
        registry.put(key, candleOpen);
        return true;
    }

    // =========================================================================
    // Fixed SL / TP from the ACTUAL average entry price
    // =========================================================================
    static class SlTp {
        boolean ok;
        double sl, tp, risk;
        String reason = "";
    }

    static SlTp computeFixedSlTp(boolean isLong, double entry, double tick) {
        SlTp r = new SlTp();
        if (entry <= 0 || Double.isNaN(entry)) { r.reason = "invalid entry price"; return r; }
        double slRaw = isLong ? entry * (1 - FIXED_SL_PERCENT / 100.0) : entry * (1 + FIXED_SL_PERCENT / 100.0);
        double tpRaw = isLong ? entry * (1 + FIXED_TP_PERCENT / 100.0) : entry * (1 - FIXED_TP_PERCENT / 100.0);
        double sl = roundToTick(slRaw, tick), tp = roundToTick(tpRaw, tick);
        if (isLong ? (sl >= entry || tp <= entry) : (sl <= entry || tp >= entry)) {
            r.reason = "SL/TP collapsed onto entry after tick rounding";
            return r;
        }
        r.ok = true; r.sl = sl; r.tp = tp; r.risk = Math.abs(entry - sl);
        return r;
    }

    // =========================================================================
    // Dynamic trailing — pure calculation (no I/O, unit-tested in --selftest)   [UNCHANGED]
    // =========================================================================
    static class TrailStep {
        boolean active;          // trailing active after this evaluation
        boolean justActivated;   // activation level reached in this evaluation
        double  activationRef;   // trail reference right after activation (= activation price level)
        boolean move;            // true -> SL and TP should be moved to newSl / newTp
        double  shift;           // price distance both levels move by
        double  newSl, newTp, newRef;
        String  reason = "";
    }

    // extreme = highest price since entry (LONG) / lowest price since entry (SHORT).
    // ref     = last trail point (only meaningful when active == true).
    // SL and TP are shifted by the SAME price difference, so the SL<->TP gap never changes; never backward.
    static TrailStep computeTrail(boolean isLong, double entry, double sl, double tp, double extreme,
                                  boolean active, double ref, double tick) {
        TrailStep r = new TrailStep();
        r.active = active; r.newSl = sl; r.newTp = tp; r.newRef = ref;
        if (!(entry > 0) || !(sl > 0) || !(tp > 0) || !(extreme > 0)) { r.reason = "invalid inputs"; return r; }
        double act = isLong ? entry * (1 + TRAIL_ACTIVATION_PERCENT / 100.0) : entry * (1 - TRAIL_ACTIVATION_PERCENT / 100.0);
        double tol = entry * 1e-9;
        if (!r.active) {
            boolean reached = isLong ? extreme >= act - tol : extreme <= act + tol;
            if (!reached) { r.reason = String.format("activation not reached (need %.6f, best %.6f)", act, extreme); return r; }
            r.active = true; r.justActivated = true; r.activationRef = act;
            ref = act; r.newRef = act;
        }
        double delta = isLong ? extreme - ref : ref - extreme;     // favourable distance beyond the last trail point
        if (delta < ref * TRAIL_STEP_PERCENT / 100.0 - tol) { r.reason = "step not reached"; return r; }
        double shift = tick > 0 ? Math.floor(delta / tick + 1e-9) * tick : delta;   // whole ticks only
        if (!(shift > 0)) { r.reason = "shift rounds to 0 ticks"; return r; }
        double newSl = roundToTick(isLong ? sl + shift : sl - shift, tick);
        double newTp = roundToTick(isLong ? tp + shift : tp - shift, tick);
        if (isLong ? (newSl <= sl || newTp <= tp) : (newSl >= sl || newTp >= tp)) { r.reason = "would not move in the favourable direction"; return r; }
        r.move = true; r.shift = shift; r.newSl = newSl; r.newTp = newTp;
        r.newRef = isLong ? ref + shift : ref - shift;
        r.reason = "step reached";
        return r;
    }

    // =========================================================================
    // Break-even + Profit Lock — pure calculation (no I/O)   [UNCHANGED]
    // =========================================================================
    static class BeStep {
        boolean reached;         // trigger level reached by the favourable extreme
        boolean move;            // true -> SL should be moved to newSl (TP unchanged)
        double  trigger, lockSl, newSl;
        String  reason = "";
    }

    // extreme = highest price since entry (LONG) / lowest price since entry (SHORT).
    // SL is only ever moved in the profitable direction (LONG: up, SHORT: down); never backwards.
    static BeStep computeBreakeven(boolean isLong, double entry, double sl, double extreme, double tick) {
        BeStep r = new BeStep();
        r.newSl = sl;
        if (!(entry > 0) || !(sl > 0) || !(extreme > 0)) { r.reason = "invalid inputs"; return r; }
        r.trigger = isLong ? entry * (1 + BREAKEVEN_TRIGGER_PERCENT / 100.0) : entry * (1 - BREAKEVEN_TRIGGER_PERCENT / 100.0);
        double lockRaw = isLong ? entry * (1 + PROFIT_LOCK_PERCENT / 100.0) : entry * (1 - PROFIT_LOCK_PERCENT / 100.0);
        r.lockSl = roundToTick(lockRaw, tick);
        double tol = entry * 1e-9;
        r.reached = isLong ? extreme >= r.trigger - tol : extreme <= r.trigger + tol;
        if (!r.reached) { r.reason = String.format("break-even trigger not reached (need %.6f, best %.6f)", r.trigger, extreme); return r; }
        boolean better = isLong ? r.lockSl > sl : r.lockSl < sl;
        if (!better || !priceDiffers(r.lockSl, sl, tick)) { r.reason = "SL already at or beyond the lock level"; return r; }
        r.move = true; r.newSl = r.lockSl; r.reason = "move SL to lock level";
        return r;
    }

    // true when two prices differ by more than half a tick (exchange values are compared with this)
    static boolean priceDiffers(double a, double b, double tick) {
        return Math.abs(a - b) > Math.max(tick * 0.5, 1e-12);
    }

    // =========================================================================
    // Portfolio profit target — pure calculation (no I/O, unit-tested in --selftest)   [NEW]
    // =========================================================================
    // signedQty: LONG positive, SHORT negative. Result in the quote currency (USDT).
    static double positionPnl(double signedQty, double avg, double px) { return (px - avg) * signedQty; }

    // isolated margin = notional / leverage (USDT, same unit as the PnL, so the % never mixes INR and USDT)
    static double positionMargin(double signedQty, double avg, double lev) { return lev > 0 ? Math.abs(signedQty) * avg / lev : 0; }

    static boolean portfolioTargetReached(double pnl, double margin, double targetPct) {
        return margin > 0 && pnl / margin * 100.0 >= targetPct - 1e-9;
    }

    // =========================================================================
    // Trade state persistence (survives restarts)
    // =========================================================================
    static JSONObject tradeToJson(TradeInfo t) {
        JSONObject j = new JSONObject();
        j.put("isLong", t.isLong);
        j.put("entry", t.entry);
        j.put("sl", t.sl);
        j.put("tp", t.tp);
        j.put("initialSl", t.initialSl);
        j.put("risk", t.risk);
        j.put("maxPrice", t.maxPrice);
        j.put("minPrice", t.minPrice);
        j.put("openedMs", t.openedMs);
        j.put("ctx", t.ctx == null ? "" : t.ctx);
        j.put("trailActive", t.trailActive);
        j.put("trailRef", t.trailRef);
        j.put("lastTrailMs", t.lastTrailMs);
        j.put("beDone", t.beDone);
        return j;
    }

    static TradeInfo tradeFromJson(JSONObject j) {
        TradeInfo t = new TradeInfo();
        t.isLong = j.optBoolean("isLong", true);
        t.entry = j.optDouble("entry", 0);
        t.sl = j.optDouble("sl", 0);
        t.tp = j.optDouble("tp", 0);
        t.initialSl = j.optDouble("initialSl", t.sl);
        t.risk = j.optDouble("risk", 0);
        t.maxPrice = j.optDouble("maxPrice", t.entry);
        t.minPrice = j.optDouble("minPrice", t.entry);
        t.openedMs = j.optLong("openedMs", 0);
        t.ctx = j.optString("ctx", "");
        t.trailActive = j.optBoolean("trailActive", false);
        t.trailRef = j.optDouble("trailRef", 0);
        t.lastTrailMs = j.optLong("lastTrailMs", 0);
        t.beDone = j.optBoolean("beDone", false);
        return t;
    }

    // Atomic write (temp file + rename) so a crash in the middle never leaves a half-written state file.
    private static synchronized void saveTradeState() {
        try {
            JSONObject root = new JSONObject();
            for (Map.Entry<String, TradeInfo> en : tradeState.entrySet()) root.put(en.getKey(), tradeToJson(en.getValue()));
            File target = new File(TRADE_STATE_FILE);
            File tmp = new File(TRADE_STATE_FILE + ".tmp");
            Files.write(tmp.toPath(), root.toString(2).getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception atomicNotSupported) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            System.err.println("saveTradeState failed: " + e.getMessage());
        }
    }

    private static void loadTradeState() {
        File f = new File(TRADE_STATE_FILE);
        if (!f.exists()) { System.out.println("[STATE] no saved trade state (" + TRADE_STATE_FILE + ") — starting fresh"); return; }
        try {
            JSONObject root = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            for (String pair : root.keySet()) {
                TradeInfo t = tradeFromJson(root.getJSONObject(pair));
                if (t.entry > 0) tradeState.put(pair, t);
            }
            System.out.println("[STATE] restored " + tradeState.size() + " trade(s) from " + TRADE_STATE_FILE + ": " + tradeState.keySet());
        } catch (Exception e) {
            System.err.println("[STATE] could not read " + TRADE_STATE_FILE + " (" + e.getMessage()
                    + ") — backing it up; open positions will be rebuilt from the exchange");
            try { Files.move(f.toPath(), new File(TRADE_STATE_FILE + ".corrupt").toPath(), StandardCopyOption.REPLACE_EXISTING); }
            catch (Exception ignored) {}
        }
    }

    // =========================================================================
    // Quantity: precision, minimum quantity / notional validation
    // =========================================================================
    static class QtyResult {
        double qty;
        String reason = "";
    }

    static QtyResult sizeQuantity(double rawQty, double price, boolean integerOnly, JSONObject inst) {
        QtyResult r = new QtyResult();
        double inc = inst == null ? 0 : inst.optDouble("quantity_increment", 0);
        double qty;
        if (inc > 0) {
            BigDecimal bi = BigDecimal.valueOf(inc);
            qty = BigDecimal.valueOf(rawQty).divide(bi, 0, RoundingMode.FLOOR).multiply(bi).doubleValue();
        } else {
            qty = integerOnly ? Math.floor(rawQty) : Math.floor(rawQty * 100) / 100.0;
        }
        double minQty = inst == null ? 0 : inst.optDouble("min_quantity", 0);
        double minNotional = inst == null ? 0 : inst.optDouble("min_notional", 0);
        double maxQty = inst == null ? 0 : inst.optDouble("max_quantity", 0);
        if (qty <= 0) r.reason = "quantity rounds to 0";
        else if (minQty > 0 && qty < minQty) r.reason = String.format("quantity %.6f below min quantity %.6f", qty, minQty);
        else if (minNotional > 0 && qty * price < minNotional) r.reason = String.format("notional %.4f below min notional %.4f", qty * price, minNotional);
        else if (maxQty > 0 && qty > maxQty) r.reason = String.format("quantity %.6f above max quantity %.6f", qty, maxQty);
        else r.qty = qty;
        return r;
    }

    static boolean isPartialFill(double requested, double filled) {
        return filled > 0 && filled < requested * 0.999;
    }

    static boolean isErrorResponse(String resp) {
        if (resp == null || resp.trim().isEmpty()) return true;
        String l = resp.toLowerCase();
        return l.contains("err_code_dcx") || l.contains("\"status\":\"error\"") || l.contains("\"code\":4")
                || l.contains("\"code\":5") || l.startsWith("<");
    }

    // =========================================================================
    // Main loop — one cycle per minute
    // =========================================================================
    public static void main(String[] args) {
        if (args.length > 0 && "--selftest".equals(args[0])) {
            System.exit(selfTest() ? 0 : 1);
            return;
        }
        System.out.println("=== Bot starting | MODE=" + (LIVE_TRADING_ENABLED ? "LIVE" : "PAPER (live trading disabled)")
                + " | SUPERTREND(1H/2H/3H/4H) + 1H/2H candle -> 5M breakout + 15M EMA9/21 + 3H/4H candle, ALL ON DEVELOPING CANDLES"
                + " | Supertrend side=" + (ST_CONTRARIAN ? "CONTRARIAN (LONG needs RED)" : "TREND (LONG needs GREEN)")
                + " | SL=" + FIXED_SL_PERCENT + "% TP=" + FIXED_TP_PERCENT + "% | leverage=" + LEVERAGE + "x | liquid list=" + USE_LIQUID_LIST
                + " (" + COINS_TO_TRADE.length + " symbols) ===");
        System.out.println("=== Break-even + profit lock: " + (BREAKEVEN_ENABLED
                ? "ENABLED (trigger " + BREAKEVEN_TRIGGER_PERCENT + "%, SL locked at entry +/- " + PROFIT_LOCK_PERCENT + "%)"
                : "DISABLED") + (LIVE_TRADING_ENABLED ? "" : " — not applied in PAPER mode") + " ===");
        if (BREAKEVEN_ENABLED && BREAKEVEN_TRIGGER_PERCENT <= PROFIT_LOCK_PERCENT)
            System.err.println("WARNING: BREAKEVEN_TRIGGER_PERCENT should be greater than PROFIT_LOCK_PERCENT, otherwise the lock level can never be placed");
        System.out.println("=== Trailing SL/TP: " + (TRAILING_ENABLED
                ? "ENABLED (activation " + TRAIL_ACTIVATION_PERCENT + "%, step " + TRAIL_STEP_PERCENT + "%, check every " + (TRAIL_CHECK_INTERVAL_MS / 1000) + "s)"
                : "DISABLED (fixed SL/TP only)") + (LIVE_TRADING_ENABLED ? "" : " — not applied in PAPER mode") + " ===");
        System.out.println("=== Portfolio profit target: " + (PORTFOLIO_PROFIT_TARGET_ENABLED
                ? "ENABLED (close ALL positions when combined profit >= " + PORTFOLIO_PROFIT_TARGET_PERCENT + "% of total margin, check every "
                  + (PORTFOLIO_CHECK_INTERVAL_MS / 1000) + "s, re-entry pause " + (PORTFOLIO_REENTRY_PAUSE_MS / 1000) + "s)"
                : "DISABLED") + (LIVE_TRADING_ENABLED ? "" : " — not applied in PAPER mode") + " ===");
        if (LIVE_TRADING_ENABLED && (API_KEY == null || API_SECRET == null)) {
            System.err.println("FATAL: LIVE_TRADING_ENABLED but DELTA_API_KEY / DELTA_API_SECRET are not set");
            return;
        }
        initInstrumentCache();
        if (LIVE_TRADING_ENABLED) loadTradeState();

        while (true) {
            try {
                runCycle();
            } catch (Throwable t) {
                System.err.println("[MAIN-LOOP] Uncaught error, continuing: " + t.getMessage());
                t.printStackTrace();
            }
            try {
                long now = System.currentTimeMillis();
                long next = (now / SCAN_STEP_MS + 1) * SCAN_STEP_MS + SCAN_DELAY_MS;
                TimeUnit.MILLISECONDS.sleep(Math.max(1000L, next - now));
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private static void runCycle() {
        Set<String> active;
        if (LIVE_TRADING_ENABLED) {
            active = getActivePositions();
            if (active == null) {
                System.err.println("Positions API failed — skipping this cycle (no entries, no cleanup).");
                return;
            }
            System.out.println("Active positions: " + active);
            handleClosedTrades(active);
            runTrailingPass();
            runPortfolioProfitCheck();          // NEW: combined-profit target (does nothing when disabled)
        } else {
            active = new HashSet<>();
            updatePaperTrades();
        }

        boolean portfolioPaused = LIVE_TRADING_ENABLED && PORTFOLIO_PROFIT_TARGET_ENABLED
                && (portfolioLiquidating || System.currentTimeMillis() < portfolioPauseUntilMs);
        if (portfolioPaused) {
            System.out.println("Portfolio target pause — no new entries this cycle.");
        } else if (active.size() + paperTrades.size() >= MAX_OPEN_POSITIONS) {
            System.out.println("MAX_OPEN_POSITIONS reached — skipping entries.");
        } else {
            runEntryScan(active);
        }
        if (LIVE_TRADING_ENABLED) ensureTpSlForOpenPositions();  // ALWAYS runs — protection never depends on entry filters
    }

    private static void reject(Map<String, Integer> counts, String reason) { counts.merge(reason, 1, Integer::sum); }

    private static void runEntryScan(Set<String> active) {
        Map<String, Integer> rej = new TreeMap<>();
        int stage2 = 0, signals = 0;

        for (String pair : COINS_TO_TRADE) {
            try {
                // The scan of hundreds of symbols can take longer than the trailing interval, so trailing is also
                // checked from inside the scan loop (same thread -> no concurrent order/TP-SL calls).
                if (LIVE_TRADING_ENABLED && System.currentTimeMillis() - lastTrailPassMs >= TRAIL_CHECK_INTERVAL_MS) runTrailingPass();

                // Same for the portfolio profit target: if it fires, the scan stops and entries pause.
                if (LIVE_TRADING_ENABLED && PORTFOLIO_PROFIT_TARGET_ENABLED) {
                    if (System.currentTimeMillis() - lastPortfolioCheckMs >= PORTFOLIO_CHECK_INTERVAL_MS) runPortfolioProfitCheck();
                    if (portfolioLiquidating || System.currentTimeMillis() < portfolioPauseUntilMs) {
                        System.out.println("  [PORTFOLIO] target hit — scan stopped, new entries paused");
                        break;
                    }
                }

                if (active.size() + paperTrades.size() >= MAX_OPEN_POSITIONS) break;
                if (active.contains(pair) || paperTrades.containsKey(pair)) continue;   // no duplicate / conflicting position per symbol
                if (System.currentTimeMillis() - lastTradeTime.getOrDefault(pair, 0L) < COOLDOWN_MS) continue;

                // ---- data: hourly-cached history + ONE small 5M request ----
                Series5 s = getSeries(pair, System.currentTimeMillis());
                if (s == null) { reject(rej, "noData"); continue; }

                // ---- STAGE 1: Supertrend 1H-4H + developing 1H/2H colour, with the newest 5M close as the price ----
                long now0 = System.currentTimeMillis();
                Eval quick = evaluateStage1(s, s.c[s.n - 1], now0);
                if (quick.dir == 0) { reject(rej, quick.code); continue; }

                // ---- STAGE 2: all conditions again with the real live price ----
                stage2++;
                Eval ev = evaluateFull(s, getLastPrice(pair), System.currentTimeMillis());
                if (ev.signal.equals("NO TRADE")) {
                    reject(rej, "live_" + ev.code);
                    if (LOG_NO_TRADE_DETAIL) logEval(pair, ev);
                    continue;
                }

                boolean isLong = ev.signal.equals("BUY");
                String sigKey = pair + "|" + (isLong ? "LONG" : "SHORT");
                long start15 = alignedStart(System.currentTimeMillis(), FIFTEEN_MIN_MS, HTF_ALIGN_OFFSET_MS);
                if (!registerSignal(lastSignalCandle, sigKey, start15)) { reject(rej, "duplicateSignal"); continue; }
                signals++;
                logEval(pair, ev);
                handleSignal(pair, isLong, ctxOf(ev), active);
            } catch (Exception ex) {
                System.err.println("Error on " + pair + ": " + ex.getMessage());
            }
        }
        System.out.println("=== Scan complete | mode=" + (LIVE_TRADING_ENABLED ? "LIVE" : "PAPER") + " stage2=" + stage2
                + " signals=" + signals + " | rejections=" + rej + " ===");
    }

    private static void handleSignal(String pair, boolean isLong, String ctx, Set<String> active) {
        if (LIVE_TRADING_ENABLED) {
            executeEntry(pair, isLong, ctx, active);   // runs the immediate pre-order revalidation itself
            return;
        }
        Revalidation rv = revalidateBeforeOrder(pair, isLong);
        if (rv.reason != null) {
            System.out.println("SIGNAL CANCELLED - " + pair + " [" + (isLong ? "LONG" : "SHORT") + "] - " + rv.reason);
            return;
        }
        paperEntry(pair, isLong, rv.price, ctx);
    }

    // =========================================================================
    // PAPER trading: simulated entry at the signal price, SL/TP resolved from 1M candles (fixed SL/TP, no trailing)
    // =========================================================================
    private static void paperEntry(String pair, boolean isLong, double price, String ctx) {
        SlTp s = computeFixedSlTp(isLong, price, getTickSize(pair));
        if (!s.ok) { System.out.println("[PAPER] SKIP - " + pair + " - " + s.reason); return; }
        PaperTrade p = new PaperTrade();
        p.isLong = isLong; p.entry = price; p.sl = s.sl; p.tp = s.tp;
        p.openedMs = System.currentTimeMillis(); p.ctx = ctx;
        paperTrades.put(pair, p);
        lastTradeTime.put(pair, p.openedMs);
        System.out.printf("[PAPER-ENTRY] %s %s entry=%.6f SL=%.6f TP=%.6f (no real order sent)%n", pair, isLong ? "LONG" : "SHORT", price, s.sl, s.tp);
    }

    private static void updatePaperTrades() {
        if (paperTrades.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (Iterator<Map.Entry<String, PaperTrade>> it = paperTrades.entrySet().iterator(); it.hasNext();) {
            Map.Entry<String, PaperTrade> en = it.next();
            String pair = en.getKey();
            PaperTrade p = en.getValue();
            try {
                long firstFullMin = (p.openedMs / MIN_MS + 1) * MIN_MS;        // first 1M candle that opens after the entry
                int minutes = (int) Math.max(3, (now - firstFullMin) / MIN_MS + 3);
                JSONArray closed = dropLastIfForming(sortByTime(fetchCandlesPaged(pair, "1", 1, minutes)));
                String result = null;
                double exitPx = 0;
                for (int i = 0; closed != null && i < closed.length(); i++) {
                    JSONObject c = closed.getJSONObject(i);
                    if (candleTimeMs(c) < firstFullMin) continue;
                    double hi = c.getDouble("high"), lo = c.getDouble("low");
                    boolean slHit = p.isLong ? lo <= p.sl : hi >= p.sl;
                    boolean tpHit = p.isLong ? hi >= p.tp : lo <= p.tp;
                    if (slHit) { result = "SL"; exitPx = p.sl; break; }        // SL first when both touch in one candle (conservative)
                    if (tpHit) { result = "TP"; exitPx = p.tp; break; }
                }
                if (result == null && now - p.openedMs > PAPER_MAX_HOLD_MS) {
                    double px = getLastPrice(pair);
                    result = "TIMEOUT";
                    exitPx = px > 0 ? px : p.entry;
                }
                if (result != null) {
                    logPaperClosed(pair, p, result, exitPx);
                    it.remove();
                }
            } catch (Exception e) {
                System.err.println("updatePaperTrades " + pair + ": " + e.getMessage());
            }
        }
    }

    private static synchronized void logPaperClosed(String pair, PaperTrade p, String result, double exitPx) {
        double risk = Math.abs(p.entry - p.sl);
        double move = p.isLong ? exitPx - p.entry : p.entry - exitPx;
        double r = risk > 0 ? move / risk : 0;
        long heldMin = (System.currentTimeMillis() - p.openedMs) / 60_000L;
        System.out.printf("[PAPER-CLOSED] %s %s result=%s entry=%.6f exit=%.6f R=%.2f held=%dmin | %s%n",
                pair, p.isLong ? "LONG" : "SHORT", result, p.entry, exitPx, r, heldMin, p.ctx);
        if (!LOG_TRADES_CSV) return;
        try {
            File f = new File(PAPER_LOG_FILE);
            boolean fresh = !f.exists();
            try (PrintWriter pw = new PrintWriter(new FileWriter(f, true))) {
                if (fresh) pw.println("closed_utc,pair,direction,result,entry,sl,tp,exit_price,r_multiple,held_min,context");
                pw.printf("%s,%s,%s,%s,%.8f,%.8f,%.8f,%.8f,%.2f,%d,\"%s\"%n", Instant.now(), pair,
                        p.isLong ? "LONG" : "SHORT", result, p.entry, p.sl, p.tp, exitPx, r, heldMin, p.ctx);
            }
        } catch (Exception e) {
            System.err.println("paper log write failed: " + e.getMessage());
        }
    }

    // =========================================================================
    // LIVE order execution — unchanged except the revalidation call at the top and state persistence at the end
    // =========================================================================
    private static boolean executeEntry(String pair, boolean isLong, String ctx, Set<String> active) {
        try {
            // Immediate pre-order revalidation on FRESH candles + the latest price (stage 1 + stage 2, all AND rules).
            Revalidation rv = revalidateBeforeOrder(pair, isLong);
            double currentPrice = rv.price;
            if (rv.reason != null) {
                System.out.println("SIGNAL CANCELLED - " + pair + " - " + rv.reason);
                return false;
            }
            double tickSize = getTickSize(pair);
            SlTp pre = computeFixedSlTp(isLong, currentPrice, tickSize);
            if (!pre.ok) {
                System.out.println("SKIP - " + pair + " - " + pre.reason);
                return false;
            }
            double rawQty = FIXED_MARGIN / (currentPrice * USDT_INR_RATE);
            QtyResult q = sizeQuantity(rawQty, currentPrice, INTEGER_QTY_PAIRS.contains(pair), instrumentCache.get(pair));
            if (q.qty <= 0) {
                System.out.println("SKIP - " + pair + " - " + q.reason);
                return false;
            }
            double qty = q.qty;

            System.out.println("\n==== " + pair + " — " + (isLong ? "LONG" : "SHORT") + " (Supertrend + EMA + breakout, developing candles) ====");
            System.out.printf("  Price=%.6f preSL=%.6f preTP=%.6f | Qty=%.6f Lev=%dx%n", currentPrice, pre.sl, pre.tp, qty, LEVERAGE);

            String side = isLong ? "buy" : "sell";
            JSONObject orderResp = placeFuturesOrder(side, pair, qty, LEVERAGE,
                    "email_notification", "isolated", "INR", currentPrice);
            if (orderResp == null || !orderResp.has("id")) {
                System.out.println("  Order failed/rejected: " + orderResp);
                return false;
            }
            String orderId = orderResp.getString("id");
            System.out.println("  Order placed! id=" + orderId);
            lastTradeTime.put(pair, System.currentTimeMillis());

            double entry = getEntryPrice(pair, orderId);
            if (entry <= 0) {
                System.out.println("  Entry not confirmed within window — cancelling unfilled limit order " + orderId);
                boolean cancelled = cancelOrder(orderId);
                sleepMs(CANCEL_VERIFY_DELAY_MS);
                JSONObject posAfter = findPosition(pair);
                double avg = posAfter == null ? 0 : posAfter.optDouble("avg_price", 0);
                if (avg <= 0) {
                    if (cancelled) {
                        System.out.println("  Unfilled order cancelled — no position opened for " + pair);
                        lastTradeTime.remove(pair);
                    } else {
                        System.out.println("  WARNING: cancel of order " + orderId + " for " + pair
                                + " NOT confirmed — it may still be LIVE on the exchange, check manually");
                    }
                    return false;
                }
                System.out.println("  Order filled before the cancel took effect — continuing with fill price " + avg);
            }

            JSONObject pos = findPosition(pair);
            double filledQty = pos == null ? 0 : Math.abs(pos.optDouble("active_pos", 0));
            if (isPartialFill(qty, filledQty)) {
                System.out.printf("  PARTIAL FILL: requested=%.6f filled=%.6f — cancelling remainder%n", qty, filledQty);
                cancelOrder(orderId);
                sleepMs(CANCEL_VERIFY_DELAY_MS);
                pos = findPosition(pair);
                filledQty = pos == null ? 0 : Math.abs(pos.optDouble("active_pos", 0));
            }
            double avgEntry = pos == null ? 0 : pos.optDouble("avg_price", 0);
            if (avgEntry <= 0) {
                System.out.println("  Could not read the average entry price — safety sweep will protect the position");
                active.add(pair);
                return true;
            }
            System.out.printf("  Entry confirmed: avg=%.6f filledQty=%.6f (requested %.6f)%n", avgEntry, filledQty, qty);

            SlTp fin = computeFixedSlTp(isLong, avgEntry, tickSize);
            String posId = pos.optString("id", null);
            if (posId == null) posId = getPositionId(pair);
            if (!fin.ok) {
                System.out.println("  Cannot compute SL/TP from the fill price (" + fin.reason + ") — closing the position");
                if (posId == null || !closePosition(posId)) System.out.println("  CRITICAL: position " + pair + " is open WITHOUT protection — close it manually");
                return false;
            }
            System.out.println("[ENTRY] " + pair + " Side=" + (isLong ? "LONG" : "SHORT"));
            System.out.printf("  Entry=%.6f SL=%.6f TP=%.6f Risk=%.6f SL%%=%.2f TP%%=%.2f | trailing=%s%n",
                    avgEntry, fin.sl, fin.tp, fin.risk, FIXED_SL_PERCENT, FIXED_TP_PERCENT,
                    TRAILING_ENABLED ? "armed (activates at " + TRAIL_ACTIVATION_PERCENT + "% profit)" : "disabled");

            if (posId == null) {
                System.out.println("  Position ID not found after retries — safety sweep will handle it");
            } else if (!setTpSlWithRetry(posId, fin.tp, fin.sl, pair) && !stopLossPresent(pair)) {
                System.out.println("  SL protection could NOT be established — closing the position immediately");
                if (!closePosition(posId)) System.out.println("  CRITICAL: position " + pair + " is open WITHOUT stop loss — close it manually");
                return false;
            }
            TradeInfo trade = new TradeInfo();
            trade.isLong = isLong; trade.entry = avgEntry; trade.sl = fin.sl; trade.tp = fin.tp;
            trade.initialSl = fin.sl; trade.risk = fin.risk;
            trade.maxPrice = avgEntry; trade.minPrice = avgEntry;
            trade.openedMs = System.currentTimeMillis();
            trade.ctx = ctx;
            tradeState.put(pair, trade);
            saveTradeState();
            active.add(pair);
            return true;
        } catch (Exception e) {
            System.err.println("executeEntry " + pair + ": " + e.getMessage());
            return false;
        }
    }

    private static boolean cancelOrder(String orderId) {
        for (int attempt = 1; attempt <= CANCEL_MAX_RETRIES; attempt++) {
            try {
                JSONObject body = new JSONObject();
                body.put("timestamp", Instant.now().toEpochMilli());
                body.put("id", orderId);
                String resp = authPost(BASE_URL + "/exchange/v1/derivatives/futures/orders/cancel", body.toString());
                System.out.println("  Cancel response (attempt " + attempt + "/" + CANCEL_MAX_RETRIES + "): " + resp);
                if (!isErrorResponse(resp)) return true;
            } catch (Exception e) {
                System.err.println("cancelOrder attempt " + attempt + ": " + e.getMessage());
            }
            sleepMs(CANCEL_RETRY_DELAY_MS);
        }
        return false;
    }

    private static boolean closePosition(String posId) {
        try {
            JSONObject body = new JSONObject();
            body.put("timestamp", Instant.now().toEpochMilli());
            body.put("id", posId);
            String resp = authPost(BASE_URL + "/exchange/v1/derivatives/futures/positions/exit", body.toString());
            System.out.println("  Emergency close response: " + resp);
            return !isErrorResponse(resp);
        } catch (Exception e) {
            System.err.println("closePosition: " + e.getMessage());
            return false;
        }
    }

    private static boolean stopLossPresent(String pair) {
        try {
            JSONObject p = findPosition(pair);
            return p != null && p.optDouble("stop_loss_trigger", 0) > 0;
        } catch (Exception e) {
            return false;
        }
    }

    // =========================================================================
    // Closed-trade logging (approximate exit reason + MFE/MAE in R) -> console + CSV
    // =========================================================================
    private static void handleClosedTrades(Set<String> active) {
        boolean removed = false;
        for (Iterator<Map.Entry<String, TradeInfo>> it = tradeState.entrySet().iterator(); it.hasNext();) {
            Map.Entry<String, TradeInfo> en = it.next();
            if (active.contains(en.getKey())) continue;
            double px = getLastPrice(en.getKey());
            logClosedTrade(en.getKey(), en.getValue(), px);
            it.remove();
            removed = true;
        }
        if (removed) saveTradeState();     // closed positions are dropped from the persisted state too
    }

    static String classifyExit(TradeInfo t, double lastPx) {
        if (lastPx <= 0 || t.risk <= 0) return "UNKNOWN";
        double dTp = Math.abs(lastPx - t.tp), dSl = Math.abs(lastPx - t.sl);
        if (Math.min(dTp, dSl) > 0.35 * t.risk) return "OTHER(manual/liquidation?)";
        return dTp < dSl ? "TP(approx)" : "SL(approx)";
    }

    static double[] mfeMaeR(TradeInfo t) {
        if (t.risk <= 0) return new double[]{0, 0};
        double mfe = t.isLong ? (t.maxPrice - t.entry) : (t.entry - t.minPrice);
        double mae = t.isLong ? (t.entry - t.minPrice) : (t.maxPrice - t.entry);
        return new double[]{Math.max(0, mfe) / t.risk, Math.max(0, mae) / t.risk};
    }

    private static synchronized void logClosedTrade(String pair, TradeInfo t, double lastPx) {
        // positions closed by the portfolio profit target are labelled explicitly instead of guessed from the price
        String exit = portfolioClosedPairs.remove(pair) ? "PORTFOLIO_TARGET" : classifyExit(t, lastPx);
        double[] m = mfeMaeR(t);
        long heldMin = t.openedMs > 0 ? (System.currentTimeMillis() - t.openedMs) / 60_000L : -1;
        System.out.printf("[CLOSED] %s %s exit=%s entry=%.6f initialSl=%.6f finalSl=%.6f finalTp=%.6f lastPx=%.6f held=%dmin trailing=%s MFE=%.2fR MAE=%.2fR | %s%n",
                pair, t.isLong ? "LONG" : "SHORT", exit, t.entry, t.initialSl, t.sl, t.tp, lastPx, heldMin,
                t.trailActive ? "was active" : "never activated", m[0], m[1], t.ctx);
        if (!LOG_TRADES_CSV) return;
        try {
            File f = new File(TRADE_LOG_FILE);
            boolean fresh = !f.exists();
            try (PrintWriter pw = new PrintWriter(new FileWriter(f, true))) {
                if (fresh) pw.println("closed_utc,pair,direction,exit_approx,entry,initial_sl,final_sl,tp,last_price,held_min,mfe_r,mae_r,context");
                pw.printf("%s,%s,%s,%s,%.8f,%.8f,%.8f,%.8f,%.8f,%d,%.2f,%.2f,\"%s\"%n",
                        Instant.now(), pair, t.isLong ? "LONG" : "SHORT", exit, t.entry, t.initialSl, t.sl, t.tp,
                        lastPx, heldMin, m[0], m[1], t.ctx);
            }
        } catch (Exception e) {
            System.err.println("trade log write failed: " + e.getMessage());
        }
    }

    // =========================================================================
    // DYNAMIC TRAILING SL / TP  (LIVE)   [UNCHANGED]
    //   - the exchange is the source of truth for the CURRENT SL/TP (so manual edits are respected)
    //   - SL and TP are replaced in ONE create_tpsl call (never cancelled first -> position is never unprotected)
    //   - TP/SL attached to a position are exit-only by nature (they can only reduce/close that position)
    // =========================================================================
    private static long lastTrailPassMs = 0;

    private static boolean isActivePosition(JSONObject p) {
        return p.optDouble("active_pos", 0) > 0
                || p.optDouble("locked_margin", 0) > 0
                || p.optDouble("avg_price", 0) > 0
                || p.optDouble("take_profit_trigger", 0) > 0
                || p.optDouble("stop_loss_trigger", 0) > 0;
    }

    // One API call -> all open positions by pair. Null if the call fails (caller must NOT treat that as "no positions").
    private static Map<String, JSONObject> getOpenPositionsMap() {
        try {
            JSONObject body = new JSONObject();
            body.put("timestamp", Instant.now().toEpochMilli());
            body.put("page", "1");
            body.put("size", "100");
            body.put("margin_currency_short_name", new String[]{"INR", "USDT"});
            String resp = authPost(BASE_URL + "/exchange/v1/derivatives/futures/positions", body.toString());
            JSONArray arr = resp.startsWith("[") ? new JSONArray(resp) : new JSONArray().put(new JSONObject(resp));
            Map<String, JSONObject> m = new LinkedHashMap<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject p = arr.getJSONObject(i);
                String pair = p.optString("pair", "");
                if (!pair.isEmpty() && isActivePosition(p)) m.put(pair, p);
            }
            return m;
        } catch (Exception e) {
            System.err.println("getOpenPositionsMap: " + e.getMessage());
            return null;
        }
    }

    private static void runTrailingPass() {
        lastTrailPassMs = System.currentTimeMillis();
        Map<String, JSONObject> positions = getOpenPositionsMap();
        if (positions == null) {
            System.err.println("[TRAIL] positions API failed — trailing pass skipped (no changes made)");
            return;
        }
        for (Map.Entry<String, JSONObject> en : positions.entrySet()) {
            try {
                trailOne(en.getKey(), en.getValue());
            } catch (Exception e) {
                System.err.println("[TRAIL] " + en.getKey() + ": " + e.getMessage());
            }
        }
    }

    // =========================================================================
    // PORTFOLIO PROFIT TARGET  (LIVE)   [NEW]
    //   combined % = sum(PnL of all open positions) / sum(margin of all open positions) * 100
    //   >= PORTFOLIO_PROFIT_TARGET_PERCENT  ->  close ALL positions (exit API), pause entries, then resume.
    //   If a close fails, `portfolioLiquidating` stays true and the remaining positions are closed on the next pass
    //   (no need for the target to be hit again). Runs on the main thread only (no concurrent order calls).
    // =========================================================================
    private static long lastPortfolioCheckMs = 0;
    private static long portfolioPauseUntilMs = 0;
    private static long portfolioLastLogMs = 0;
    private static boolean portfolioLiquidating = false;
    private static final Set<String> portfolioClosedPairs = ConcurrentHashMap.newKeySet();

    private static void runPortfolioProfitCheck() {
        if (!LIVE_TRADING_ENABLED || !PORTFOLIO_PROFIT_TARGET_ENABLED) return;
        long now = System.currentTimeMillis();
        lastPortfolioCheckMs = now;
        Map<String, JSONObject> positions = getOpenPositionsMap();
        if (positions == null) { System.err.println("[PORTFOLIO] positions API failed — check skipped"); return; }
        if (positions.isEmpty()) { portfolioLiquidating = false; return; }

        double pnl = 0, margin = 0;
        int n = 0;
        for (Map.Entry<String, JSONObject> en : positions.entrySet()) {
            JSONObject p = en.getValue();
            double q = p.optDouble("active_pos", 0), avg = p.optDouble("avg_price", 0);
            if (Math.abs(q) <= 0 || avg <= 0) continue;                       // not filled yet / already closed
            double px = getLastPrice(en.getKey());
            if (px <= 0) { System.err.println("[PORTFOLIO] no price for " + en.getKey() + " — no decision this pass"); return; }
            double lev = p.optDouble("leverage", LEVERAGE);
            if (!(lev > 0)) lev = LEVERAGE;
            pnl += positionPnl(q, avg, px);
            margin += positionMargin(q, avg, lev);
            n++;
        }
        if (n == 0) return;
        double pct = margin > 0 ? pnl / margin * 100.0 : 0;
        if (now - portfolioLastLogMs >= PORTFOLIO_STATUS_LOG_MS) {
            System.out.printf("  [PORTFOLIO] %d position(s) | pnl=%.4f USDT | margin=%.4f USDT | combined=%.2f%% (target %.2f%%)%n",
                    n, pnl, margin, pct, PORTFOLIO_PROFIT_TARGET_PERCENT);
            portfolioLastLogMs = now;
        }
        if (!portfolioLiquidating) {
            if (!portfolioTargetReached(pnl, margin, PORTFOLIO_PROFIT_TARGET_PERCENT)) return;
            System.out.printf("[PORTFOLIO-TARGET] combined profit %.2f%% >= %.2f%% — closing ALL %d position(s)%n",
                    pct, PORTFOLIO_PROFIT_TARGET_PERCENT, n);
            portfolioLiquidating = true;
        }
        int failed = 0;
        for (Map.Entry<String, JSONObject> en : positions.entrySet()) {
            JSONObject p = en.getValue();
            if (Math.abs(p.optDouble("active_pos", 0)) <= 0) continue;
            String posId = p.optString("id", null);
            if (posId == null || posId.isEmpty() || !closePosition(posId)) {
                failed++;
                System.err.println("[PORTFOLIO] close FAILED for " + en.getKey() + " — will retry on the next pass");
            } else {
                portfolioClosedPairs.add(en.getKey());
                System.out.println("[PORTFOLIO] closed " + en.getKey());
            }
        }
        portfolioPauseUntilMs = System.currentTimeMillis() + PORTFOLIO_REENTRY_PAUSE_MS;
        if (failed == 0) portfolioLiquidating = false;
    }

    private static void trailOne(String pair, JSONObject pos) {
        double signedQty = pos.optDouble("active_pos", 0);
        double avg = pos.optDouble("avg_price", 0);
        if (Math.abs(signedQty) <= 0 || avg <= 0) return;           // closed / not filled yet -> nothing to trail
        double px = getLastPrice(pair);
        if (px <= 0) return;                                        // no price -> no decision
        double tick = getTickSize(pair);
        double exSl = pos.optDouble("stop_loss_trigger", 0);
        double exTp = pos.optDouble("take_profit_trigger", 0);
        long now = System.currentTimeMillis();
        boolean dirty = false;

        // ---- 1. state: reuse the persisted one, or (re)build it from the exchange ----
        TradeInfo t = tradeState.get(pair);
        if (t != null && t.entry > 0 && Math.abs(avg - t.entry) / t.entry * 100.0 > TRAIL_ENTRY_MISMATCH_PCT) {
            System.out.printf("  [TRAIL] %s saved entry %.6f differs from exchange avg %.6f — treating as a NEW position, state rebuilt%n", pair, t.entry, avg);
            t = null;
        }
        if (t == null) {
            t = new TradeInfo();
            t.isLong = signedQty >= 0;
            t.entry = avg; t.sl = exSl; t.tp = exTp; t.initialSl = exSl;
            t.risk = exSl > 0 ? Math.abs(avg - exSl) : 0;
            t.maxPrice = Math.max(avg, px); t.minPrice = Math.min(avg, px);
            t.openedMs = now; t.ctx = "recovered-from-exchange";
            tradeState.put(pair, t);
            dirty = true;
        } else if (priceDiffers(avg, t.entry, tick)) {
            System.out.printf("  [TRAIL] %s average entry changed %.6f -> %.6f (extra/partial fill) — entry updated%n", pair, t.entry, avg);
            t.entry = avg;
            dirty = true;
        }

        // ---- 2. highest / lowest price since entry ----
        if (px > t.maxPrice) { t.maxPrice = px; dirty = true; }
        if (px < t.minPrice) { t.minPrice = px; dirty = true; }

        if (!TRAILING_ENABLED && !BREAKEVEN_ENABLED) { if (dirty) saveTradeState(); return; }

        // ---- 3. exchange must hold BOTH levels (the safety sweep restores missing ones) ----
        if (exSl <= 0 || exTp <= 0) {
            if (now - t.lastStatusLogMs >= TRAIL_STATUS_LOG_MS) {
                System.out.println("  [TRAIL] " + pair + " SL or TP missing on exchange (SL=" + exSl + " TP=" + exTp + ") — trailing paused, safety sweep will restore");
                t.lastStatusLogMs = now;
            }
            if (dirty) saveTradeState();
            return;
        }

        // ---- 4. manual change on the exchange -> adopt it, the NEW gap is maintained from now on ----
        if (t.sl <= 0 || t.tp <= 0 || priceDiffers(exSl, t.sl, tick) || priceDiffers(exTp, t.tp, tick)) {
            if (t.sl > 0 && t.tp > 0) {
                System.out.printf("  [TRAIL] %s SL/TP changed on the exchange (manual?): SL %.6f -> %.6f | TP %.6f -> %.6f | new gap=%.6f — adopted%n",
                        pair, t.sl, exSl, t.tp, exTp, Math.abs(exTp - exSl));
            }
            t.sl = exSl; t.tp = exTp;
            dirty = true;
        }

        // ---- 4b. break-even + profit lock: SL only (TP unchanged), one-time, never backwards ----
        if (BREAKEVEN_ENABLED && !t.beDone) {
            double beExtreme = t.isLong ? t.maxPrice : t.minPrice;
            BeStep b = computeBreakeven(t.isLong, t.entry, t.sl, beExtreme, tick);
            if (b.reached && !b.move) {
                t.beDone = true; dirty = true;       // SL is already better than the lock level (e.g. trailed) -> nothing to do
                System.out.printf("  [BREAKEVEN] %s %s trigger reached but SL=%.6f is already at/beyond lock level %.6f — no change%n",
                        pair, t.isLong ? "LONG" : "SHORT", t.sl, b.lockSl);
            } else if (b.reached && b.move && now >= t.nextTrailTryMs) {
                boolean beSane = t.isLong ? b.newSl < px : b.newSl > px;    // stop must be on the correct side of the market
                String beId = pos.optString("id", null);
                if (!beSane) {
                    if (now - t.lastStatusLogMs >= TRAIL_STATUS_LOG_MS) {
                        System.out.printf("  [BREAKEVEN] %s lock SL=%.6f not valid against price %.6f yet — will retry%n", pair, b.newSl, px);
                        t.lastStatusLogMs = now;
                    }
                } else if (beId == null || beId.isEmpty()) {
                    System.out.println("  [BREAKEVEN] " + pair + " position id missing — cannot update SL");
                } else {
                    double oldSl = t.sl;
                    boolean beOk = replaceTpSl(beId, pair, t.tp, b.newSl, tick, t.tp, t.sl);
                    if (beOk) {
                        t.sl = b.newSl; t.beDone = true; t.nextTrailTryMs = 0; dirty = true;
                        System.out.printf("  [BREAKEVEN-UPDATE] %s %s entry=%.6f price=%.6f best=%.6f | SL %.6f -> %.6f (lock %.2f%%) | TP %.6f unchanged%n",
                                pair, t.isLong ? "LONG" : "SHORT", t.entry, px, beExtreme, oldSl, t.sl, PROFIT_LOCK_PERCENT, t.tp);
                        saveTradeState();
                        return;                      // normal trailing continues from the next pass
                    } else {
                        t.nextTrailTryMs = now + TRAIL_RETRY_BACKOFF_MS;
                        System.out.printf("  [BREAKEVEN] %s SL update FAILED — keeping SL=%.6f TP=%.6f, retry in %ds%n", pair, t.sl, t.tp, TRAIL_RETRY_BACKOFF_MS / 1000);
                    }
                }
            }
        }
        if (!TRAILING_ENABLED) { if (dirty) saveTradeState(); return; }

        // ---- 5. trailing decision ----
        double extreme = t.isLong ? t.maxPrice : t.minPrice;
        TrailStep r = computeTrail(t.isLong, t.entry, t.sl, t.tp, extreme, t.trailActive, t.trailRef, tick);
        if (r.justActivated) {
            t.trailActive = true;
            t.trailRef = r.activationRef;
            dirty = true;
            System.out.printf("  [TRAIL] %s %s TRAILING ACTIVATED | entry=%.6f price=%.6f best=%.6f SL=%.6f TP=%.6f%n",
                    pair, t.isLong ? "LONG" : "SHORT", t.entry, px, extreme, t.sl, t.tp);
        }

        if (now - t.lastStatusLogMs >= TRAIL_STATUS_LOG_MS) {
            System.out.printf("  [TRAIL] %s %s entry=%.6f price=%.6f best=%.6f SL=%.6f TP=%.6f gap=%.6f trailing=%s | %s%n",
                    pair, t.isLong ? "LONG" : "SHORT", t.entry, px, extreme, t.sl, t.tp, Math.abs(t.tp - t.sl),
                    t.trailActive ? "ACTIVE" : "waiting", r.reason);
            t.lastStatusLogMs = now;
        }

        if (!r.move) { if (dirty) saveTradeState(); return; }
        if (now < t.nextTrailTryMs) { if (dirty) saveTradeState(); return; }          // backing off after a failed update

        // never place a stop on the wrong side of the market (would trigger instantly / be rejected)
        boolean sane = t.isLong ? (r.newSl < px && r.newTp > px) : (r.newSl > px && r.newTp < px);
        if (!sane) {
            System.out.printf("  [TRAIL] %s update skipped: new SL=%.6f / TP=%.6f not valid against price %.6f%n", pair, r.newSl, r.newTp, px);
            if (dirty) saveTradeState();
            return;
        }

        String posId = pos.optString("id", null);
        if (posId == null || posId.isEmpty()) {
            System.out.println("  [TRAIL] " + pair + " position id missing — cannot update TP/SL");
            if (dirty) saveTradeState();
            return;
        }

        // ---- 6. replace SL+TP on the exchange (verified by reading the position back) ----
        double oldSl = t.sl, oldTp = t.tp;
        boolean ok = replaceTpSl(posId, pair, r.newTp, r.newSl, tick, oldTp, oldSl);
        if (ok) {
            t.sl = r.newSl; t.tp = r.newTp;
            t.trailRef = r.newRef;
            t.lastTrailMs = now;
            t.nextTrailTryMs = 0;
            System.out.printf("  [TRAIL-UPDATE] %s %s entry=%.6f price=%.6f best=%.6f | SL %.6f -> %.6f | TP %.6f -> %.6f | shift=%.6f gap=%.6f (unchanged)%n",
                    pair, t.isLong ? "LONG" : "SHORT", t.entry, px, extreme, oldSl, t.sl, oldTp, t.tp, r.shift, Math.abs(t.tp - t.sl));
        } else {
            t.nextTrailTryMs = now + TRAIL_RETRY_BACKOFF_MS;
            System.out.printf("  [TRAIL] %s update FAILED — keeping SL=%.6f TP=%.6f, retry in %ds%n", pair, t.sl, t.tp, TRAIL_RETRY_BACKOFF_MS / 1000);
        }
        saveTradeState();
    }

    // Sets BOTH levels with one create_tpsl call and confirms by reading the position back. Never cancels first,
    // so the position is protected the whole time. If the position vanished meanwhile, returns false and does nothing.
    private static boolean replaceTpSl(String posId, String pair, double tp, double sl, double tick, double oldTp, double oldSl) {
        for (int attempt = 1; attempt <= TRAIL_UPDATE_MAX_RETRIES; attempt++) {
            setTpSl(posId, tp, sl, pair);
            sleepMs(TRAIL_VERIFY_DELAY_MS);
            try {
                JSONObject p = findPosition(pair);
                if (p == null || Math.abs(p.optDouble("active_pos", 0)) <= 0) {
                    System.out.println("  [TRAIL] " + pair + " position is closed — TP/SL update abandoned");
                    return false;
                }
                double nowSl = p.optDouble("stop_loss_trigger", 0), nowTp = p.optDouble("take_profit_trigger", 0);
                if (!priceDiffers(nowSl, sl, tick) && !priceDiffers(nowTp, tp, tick)) return true;
                System.out.printf("  [TRAIL] %s update not visible yet (attempt %d/%d): exchange SL=%.6f TP=%.6f, wanted SL=%.6f TP=%.6f%n",
                        pair, attempt, TRAIL_UPDATE_MAX_RETRIES, nowSl, nowTp, sl, tp);
            } catch (Exception e) {
                System.err.println("  [TRAIL] verify " + pair + " attempt " + attempt + ": " + e.getMessage());
            }
        }
        System.out.println("  [TRAIL] " + pair + " could not confirm the new TP/SL — restoring the previous levels");
        setTpSl(posId, oldTp, oldSl, pair);
        return false;
    }

    // =========================================================================
    // Reliability-only safety sweep: restores MISSING TP/SL only, never modifies one that is set.
    // (Uses the trailed levels from the persisted state, so a restored SL/TP is never worse than the trailed one.)
    // =========================================================================
    private static void ensureTpSlForOpenPositions() {
        try {
            Set<String> stillOpen = getActivePositions();
            if (stillOpen == null) return;
            for (String pair : stillOpen) {
                JSONObject pos = findPosition(pair);
                if (pos == null) continue;
                double avgPrice = pos.optDouble("avg_price", 0);
                double tpTrig   = pos.optDouble("take_profit_trigger", 0);
                double slTrig   = pos.optDouble("stop_loss_trigger", 0);
                if (avgPrice <= 0) continue;
                if (tpTrig > 0 && slTrig > 0) continue;

                System.out.println("  [SWEEP] " + pair + " missing TP and/or SL — restoring protection...");
                boolean isLong = pos.optDouble("active_pos", 0) >= 0;
                double tick = getTickSize(pair);

                TradeInfo trade = tradeState.get(pair);
                double sl, tp;
                if (trade != null && trade.sl > 0 && trade.tp > 0) {
                    sl = trade.sl; tp = trade.tp;
                } else {
                    SlTp fb = computeFixedSlTp(isLong, avgPrice, tick);
                    if (!fb.ok) {
                        System.out.println("  [SWEEP] " + pair + " — cannot compute fixed SL/TP: " + fb.reason);
                        continue;
                    }
                    sl = fb.sl; tp = fb.tp;
                    System.out.println("  [SWEEP] " + pair + " — no recorded trade state; using fixed % from average entry");
                }
                if (slTrig > 0) sl = slTrig;
                if (tpTrig > 0) tp = tpTrig;

                String posId = pos.optString("id", null);
                if (posId != null) {
                    System.out.printf("  [SWEEP] %s SL=%.6f TP=%.6f%n", pair, sl, tp);
                    setTpSlWithRetry(posId, tp, sl, pair);
                    if (trade == null) {
                        TradeInfo nt = new TradeInfo();
                        nt.isLong = isLong; nt.entry = avgPrice; nt.sl = sl; nt.tp = tp;
                        nt.initialSl = sl; nt.risk = Math.abs(avgPrice - sl);
                        nt.maxPrice = avgPrice; nt.minPrice = avgPrice;
                        nt.openedMs = System.currentTimeMillis();
                        nt.ctx = "recovered-after-restart";
                        tradeState.put(pair, nt);
                        saveTradeState();
                    } else {
                        trade.sl = sl; trade.tp = tp;
                        saveTradeState();
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
            sleepMs(TPSL_RETRY_DELAY_MS);
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
                + " — will be retried by the next cycle's safety sweep");
        return false;
    }

    // =========================================================================
    // Indicators (ACTIVE in this strategy: Supertrend on 1H-4H, EMA9/EMA21 on 15M)
    // =========================================================================
    static boolean[] calcSupertrend(double[] hi, double[] lo, double[] cl, int period, double multiplier) {
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
                upperBand[i] = (basicUpper < upperBand[i-1] || cl[i-1] > upperBand[i-1]) ? basicUpper : upperBand[i-1];
                lowerBand[i] = (basicLower > lowerBand[i-1] || cl[i-1] < lowerBand[i-1]) ? basicLower : lowerBand[i-1];
            }
            if (i == period) bullish[i] = cl[i] > (hi[i] + lo[i]) / 2.0;
            else bullish[i] = bullish[i-1] ? cl[i] >= lowerBand[i] : cl[i] > upperBand[i];
        }
        for (int i = 0; i < period; i++) bullish[i] = bullish[period];
        return bullish;
    }

    static double[] calcATRSeries(double[] hi, double[] lo, double[] cl, int period) {
        int n = hi.length;
        double[] atr = new double[n];
        if (n < 2) return atr;
        double[] tr = new double[n];
        tr[0] = hi[0] - lo[0];
        for (int i = 1; i < n; i++)
            tr[i] = Math.max(hi[i] - lo[i], Math.max(Math.abs(hi[i] - cl[i-1]), Math.abs(lo[i] - cl[i-1])));
        double sum = 0;
        for (int i = 0; i < period && i < n; i++) sum += tr[i];
        atr[period - 1] = sum / period;
        for (int i = period; i < n; i++) atr[i] = (atr[i-1] * (period - 1) + tr[i]) / period;
        for (int i = 0; i < period - 1; i++) atr[i] = atr[period - 1];
        return atr;
    }

    static double calcEMA(double[] d, int period) {
        if (d.length < period) return 0;
        double k = 2.0 / (period + 1), ema = 0;
        for (int i = 0; i < period; i++) ema += d[i];
        ema /= period;
        for (int i = period; i < d.length; i++) ema = d[i] * k + ema * (1 - k);
        return ema;
    }

    private static BigDecimal roundToTickBD(double price, double tick) {
        if (tick <= 0) return BigDecimal.valueOf(price);
        BigDecimal bdPrice = BigDecimal.valueOf(price);
        BigDecimal bdTick  = BigDecimal.valueOf(tick);
        BigDecimal multiples = bdPrice.divide(bdTick, 0, RoundingMode.HALF_UP);
        BigDecimal result = multiples.multiply(bdTick);
        return result.setScale(bdTick.scale(), RoundingMode.HALF_UP);
    }

    static double roundToTick(double price, double tick) {
        if (tick <= 0) return price;
        return roundToTickBD(price, tick).doubleValue();
    }

    // =========================================================================
    // Exchange plumbing (unchanged)
    // =========================================================================
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
            if (!instrumentCache.isEmpty())
                System.out.println("Instrument fields (verify quantity_increment/min_quantity/min_notional exist): "
                        + instrumentCache.values().iterator().next().keySet());
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
        JSONArray arr = resp.startsWith("[") ? new JSONArray(resp) : new JSONArray().put(new JSONObject(resp));
        for (int i = 0; i < arr.length(); i++) {
            JSONObject p = arr.getJSONObject(i);
            if (pair.equals(p.optString("pair"))) return p;
        }
        return null;
    }

    public static double getLastPrice(String pair) {
        try {
            HttpURLConnection conn = openGet(PUBLIC_API_URL + "/market_data/trade_history?pair=" + pair + "&limit=1");
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

    public static JSONObject placeFuturesOrder(String side, String pair, double qty, int lev, String notif,
                                               String marginType, String marginCcy, double currentPrice) {
        try {
            double rawLimitPrice = "buy".equalsIgnoreCase(side)
                    ? currentPrice * (1 + LIMIT_ORDER_BUFFER_PCT)
                    : currentPrice * (1 - LIMIT_ORDER_BUFFER_PCT);
            double tick = getTickSize(pair);
            BigDecimal limitPriceBD = roundToTickBD(rawLimitPrice, tick);

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
            String resp = authPost(BASE_URL + "/exchange/v1/derivatives/futures/orders/create", body.toString());
            return resp.startsWith("[") ? new JSONArray(resp).getJSONObject(0) : new JSONObject(resp);
        } catch (Exception e) {
            System.err.println("placeFuturesOrder: " + e.getMessage());
            return null;
        }
    }

    public static void setTpSl(String posId, double tp, double sl, String pair) {
        try {
            double tick = getTickSize(pair);
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
            String resp = authPost(BASE_URL + "/exchange/v1/derivatives/futures/positions/create_tpsl", payload.toString());
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
            sleepMs(POSITION_ID_RETRY_DELAY_MS);
        }
        return null;
    }

    // Returns null if the positions API call fails (so callers never mistake an API error for "no positions").
    private static Set<String> getActivePositions() {
        Set<String> active = new HashSet<>();
        try {
            JSONObject body = new JSONObject();
            body.put("timestamp", Instant.now().toEpochMilli());
            body.put("page", "1");
            body.put("size", "100");
            body.put("margin_currency_short_name", new String[]{"INR", "USDT"});
            String resp = authPost(BASE_URL + "/exchange/v1/derivatives/futures/positions", body.toString());
            JSONArray arr = resp.startsWith("[") ? new JSONArray(resp) : new JSONArray().put(new JSONObject(resp));
            System.out.println("=== Open Positions (" + arr.length() + ") ===");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject p    = arr.getJSONObject(i);
                String    pair  = p.optString("pair", "");
                boolean isActive = isActivePosition(p);
                if (isActive) {
                    System.out.printf("  %s | qty=%.2f | entry=%.6f | TP=%.4f | SL=%.4f%n",
                            pair, p.optDouble("active_pos", 0), p.optDouble("avg_price", 0),
                            p.optDouble("take_profit_trigger", 0), p.optDouble("stop_loss_trigger", 0));
                    active.add(pair);
                }
            }
            return active;
        } catch (Exception e) {
            System.err.println("getActivePositions: " + e.getMessage());
            return null;
        }
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
        return new BufferedReader(new InputStreamReader(is)).lines().collect(Collectors.joining("\n"));
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

    // =========================================================================
    // OFFLINE SELF-TESTS (no network, no API keys):  --selftest
    // =========================================================================
    private static int testsRun = 0, testsFailed = 0;

    private static void check(String name, boolean cond) {
        testsRun++;
        if (!cond) testsFailed++;
        System.out.println((cond ? "  PASS  " : "  FAIL  ") + name);
    }

    // Builds a Series5 from a close path: candle i opens at the previous close, wick w on both sides.
    // Candle i starts at lastStart - (n-1-i)*5M.
    private static Series5 pathSeries(double[] c, long lastStart, double wick) {
        int n = c.length;
        Series5 s = new Series5(n);
        for (int i = 0; i < n; i++) {
            double o = i == 0 ? c[0] : c[i - 1];
            s.t[i] = lastStart - (long) (n - 1 - i) * FIVE_MIN_MS;
            s.o[i] = o; s.c[i] = c[i];
            s.h[i] = Math.max(o, c[i]) + wick;
            s.l[i] = Math.min(o, c[i]) - wick;
        }
        return s;
    }

    // Long decline into a low, closed candles only. Mirror (up=false) -> long rise into a high.
    private static double[] declinePath(int n, double slope, boolean decline, double base, double wiggle) {
        double[] c = new double[n];
        for (int i = 0; i < n; i++) {
            double v = base + slope * (n - 1 - i) + wiggle * Math.sin(i * 0.9);
            c[i] = decline ? v : 2 * base - v + 0;
        }
        return c;
    }

    static boolean selfTest() {
        testsRun = 0; testsFailed = 0;
        long b12 = (1_760_000_000_000L / (12 * HOUR_MS)) * (12 * HOUR_MS);     // aligned for 15M/1H/2H/3H/4H buckets

        System.out.println("[Test] Timeframes are 1H / 2H / 3H / 4H");
        check("timeframe names", Arrays.equals(TF_NAMES, new String[]{"1H", "2H", "3H", "4H"}));
        check("timeframe lengths", TF_MS[0] == 60 * MIN_MS && TF_MS[1] == 120 * MIN_MS && TF_MS[2] == 180 * MIN_MS && TF_MS[3] == 240 * MIN_MS);
        check("small 5M request covers a full 4H bucket", SCAN_FETCH_5M >= 48 + 2);
        check("history holds enough 4H bars for the Supertrend", HIST_5M / 48 >= ST_MIN_BARS + 4);

        System.out.println("[Test] Series merge / trim");
        Series5 sa = new Series5(3), sb = new Series5(2);
        for (int i = 0; i < 3; i++) { sa.t[i] = i * FIVE_MIN_MS; sa.c[i] = 1; }
        for (int i = 0; i < 2; i++) { sb.t[i] = (1 + i) * FIVE_MIN_MS; sb.c[i] = 2; }
        Series5 sm = mergeSeries(sa, sb);
        check("newer data replaces overlapping candles", sm.n == 3 && sm.c[0] == 1 && sm.c[1] == 2 && sm.c[2] == 2 && sm.t[2] == 2 * FIVE_MIN_MS);
        check("trim keeps the newest candles", trimSeries(sm, 2).n == 2 && trimSeries(sm, 2).t[0] == FIVE_MIN_MS);

        System.out.println("[Test] Complete higher-timeframe bars (history only)");
        double[] up = new double[48];
        for (int i = 0; i < up.length; i++) up[i] = 101 + i;
        Series5 su = pathSeries(up, b12 + 47 * FIVE_MIN_MS, 0);          // candles b12 .. b12+235min, open[i] = close[i-1]
        Bars b15 = aggComplete(su, FIFTEEN_MIN_MS, b12 + 240 * MIN_MS);
        check("16 complete 15M bars from 48 x 5M", b15.n == 16);
        check("15M bar OHLC merged (open of 1st 5M, close of 3rd 5M)", Math.abs(b15.o[1] - su.o[3]) < 1e-9 && Math.abs(b15.c[1] - su.c[5]) < 1e-9);
        check("buckets starting at/after the developing start are excluded", aggComplete(su, FIFTEEN_MIN_MS, b12 + 120 * MIN_MS).n == 8);
        Series5 holey = new Series5(su.n - 1);
        for (int i = 0, j = 0; i < su.n; i++) { if (i == 4) continue; holey.t[j] = su.t[i]; holey.o[j] = su.o[i]; holey.h[j] = su.h[i]; holey.l[j] = su.l[i]; holey.c[j] = su.c[i]; j++; }
        check("bucket with a missing 5M candle is dropped", aggComplete(holey, FIFTEEN_MIN_MS, b12 + 240 * MIN_MS).n == 15);

        System.out.println("[Test] Developing candle (open from first 5M candle of each bucket, close = live price)");
        double[] flatUp = new double[24];
        for (int i = 0; i < flatUp.length; i++) flatUp[i] = 101 + i;
        Series5 s24 = pathSeries(flatUp, b12 + 23 * FIVE_MIN_MS, 0);              // up to b12+115min
        long now = b12 + 115 * MIN_MS + 30_000L;
        check("validate5m accepts current data", validate5m(s24, now) == null);
        Dev d1 = developing(s24, now, 124.0, 0), d2 = developing(s24, now, 124.0, 1), d3 = developing(s24, now, 124.0, 2), d4 = developing(s24, now, 124.0, 3);
        check("1H open = first 5M candle of the 1H bucket", d1.ok && Math.abs(d1.open - s24.o[12]) < 1e-9);
        check("2H open = first 5M candle of the 2H bucket (b12+0..2h -> first candle)", d2.ok && Math.abs(d2.open - s24.o[0]) < 1e-9);
        check("3H and 4H open = first 5M candle of the bucket", d3.ok && d4.ok && Math.abs(d3.open - s24.o[0]) < 1e-9 && Math.abs(d4.open - s24.o[0]) < 1e-9);
        check("price above open -> GREEN", d1.colour == 1 && d4.colour == 1);
        check("price below open -> RED", developing(s24, now, 90.0, 0).colour == -1);
        check("price == open -> DOJI", developing(s24, now, s24.o[12], 0).colour == 0);
        check("developing high/low include the live price", developing(s24, now, 500.0, 0).high == 500.0 && developing(s24, now, 1.0, 0).low == 1.0);
        Series5 noFirst = new Series5(s24.n - 1);
        for (int i = 1; i < s24.n; i++) { noFirst.t[i - 1] = s24.t[i]; noFirst.o[i - 1] = s24.o[i]; noFirst.h[i - 1] = s24.h[i]; noFirst.l[i - 1] = s24.l[i]; noFirst.c[i - 1] = s24.c[i]; }
        check("first 5M candle of the bucket missing -> invalid (never guessed)", !developing(noFirst, now, 124.0, 3).ok);
        check("stale 5M data rejected", validate5m(s24, now + 10 * MIN_MS) != null && validate5m(s24, now + 10 * MIN_MS).startsWith("stale"));
        Series5 lagging = trimSeries(s24, s24.n - 1);   // forming candle not returned yet
        check("forming 5M candle not returned -> still valid", validate5m(lagging, now) == null);

        System.out.println("[Test] Supertrend + EMA indicators");
        double[] hiR = new double[40], loR = new double[40], clR = new double[40], hiF = new double[40], loF = new double[40], clF = new double[40];
        for (int i = 0; i < 40; i++) { clR[i] = 100 + i; hiR[i] = clR[i] + 0.5; loR[i] = clR[i] - 0.5; clF[i] = 200 - i; hiF[i] = clF[i] + 0.5; loF[i] = clF[i] - 0.5; }
        check("steady rise -> Supertrend GREEN", calcSupertrend(hiR, loR, clR, ST_PERIOD, ST_MULTIPLIER)[39]);
        check("steady fall -> Supertrend RED", !calcSupertrend(hiF, loF, clF, ST_PERIOD, ST_MULTIPLIER)[39]);
        check("EMA9 > EMA21 in a rising series, < in a falling one", calcEMA(clR, 9) > calcEMA(clR, 21) && calcEMA(clF, 9) < calcEMA(clF, 21));

        System.out.println("[Test] STAGE 1: all four Supertrend + developing 1H/2H colour (contrarian rule)");
        long cur5 = b12 + 235 * MIN_MS;                  // forming 5M candle starts here; 1H/3H bucket starts b12+180m, 2H b12+120m, 4H b12+0
        long nowS = cur5 + 30_000L;
        int N = 1700;
        double[] dec = declinePath(N, 0.02, true, 100.0, 0.0);
        Series5 sDown = pathSeries(dec, cur5 - FIVE_MIN_MS, 0.15);
        double low = dec[N - 1];
        Eval eLong = evaluateStage1(sDown, low + 0.9, nowS);
        check("long decline + small live bounce: stage 1 LONG candidate", eLong.dir == 1);
        if (eLong.dir != 1) System.out.println("      -> " + eLong.reason);
        Eval eNo = evaluateStage1(sDown, low - 0.3, nowS);
        check("price still falling (1H candle RED) -> no LONG candidate", eNo.dir == 0);
        double[] inc = declinePath(N, 0.02, false, 100.0, 0.0);
        Series5 sUp = pathSeries(inc, cur5 - FIVE_MIN_MS, 0.15);
        double high = inc[N - 1];
        Eval eShort = evaluateStage1(sUp, high - 0.9, nowS);
        check("mirror: long rise + small live dip: stage 1 SHORT candidate", eShort.dir == -1);
        if (eShort.dir != -1) System.out.println("      -> " + eShort.reason);
        Eval eBig = evaluateStage1(sDown, low + 40.0, nowS);
        check("huge pop flips a Supertrend to GREEN -> not all four RED -> no candidate", eBig.dir == 0);
        Series5 shortHist = trimSeries(sDown, 300);
        check("too little history -> rejected, not guessed", evaluateStage1(shortHist, low + 0.9, nowS).dir == 0 && evaluateStage1(shortHist, low + 0.9, nowS).code.equals("stHistory"));

        System.out.println("[Test] STAGE 2: 3H/4H colour AND 15M EMA AND fresh 5M breakout");
        double[] zig = new double[N];
        for (int i = 0; i < N; i++) zig[i] = 100 + 0.02 * i + 0.6 * Math.sin(i * 0.9);
        Series5 sZig = pathSeries(zig, cur5 - FIVE_MIN_MS, 0.1);
        Dev[] dv = new Dev[4];
        double[] hh = new double[SWING_LOOKBACK_5M + 2 * SWING_PIVOT_BARS + 7], ll = new double[hh.length], cc = new double[hh.length];
        int end = sZig.n;
        for (int i = 0; i < hh.length - 1; i++) { int src = end - (hh.length - 1) + i; hh[i] = sZig.h[src]; ll[i] = sZig.l[src]; cc[i] = sZig.c[src]; }
        double pxTop = sZig.c[end - 1] + 5;
        hh[hh.length - 1] = pxTop; ll[hh.length - 1] = pxTop; cc[hh.length - 1] = pxTop;
        Breakout probe = findBreakout(hh, ll, cc, true, SWING_PIVOT_BARS, SWING_LOOKBACK_5M);
        check("5M breakout found with the live price far above the last swing high", probe.ok && probe.level > 0);
        Eval st2 = new Eval();
        for (int k = 0; k < 4; k++) { Dev d = new Dev(); d.tf = TF_NAMES[k]; d.ok = true; d.colour = 1; st2.dev[k] = d; }
        stage2(sZig, pxTop, nowS, 1, st2);
        check("all LONG conditions true -> BUY", st2.signal.equals("BUY"));
        if (!st2.signal.equals("BUY")) System.out.println("      -> " + st2.code + ": " + st2.reason);
        Eval st2b = new Eval();
        for (int k = 0; k < 4; k++) { Dev d = new Dev(); d.tf = TF_NAMES[k]; d.ok = true; d.colour = 1; st2b.dev[k] = d; }
        st2b.dev[3].colour = -1;
        stage2(sZig, pxTop, nowS, 1, st2b);
        check("4H developing candle RED -> NO TRADE (AND, no OR between 3H/4H)", st2b.signal.equals("NO TRADE") && st2b.code.equals("htfColour"));
        Eval st2c = new Eval();
        for (int k = 0; k < 4; k++) { Dev d = new Dev(); d.tf = TF_NAMES[k]; d.ok = true; d.colour = 1; st2c.dev[k] = d; }
        st2c.dev[2].colour = 0;
        stage2(sZig, pxTop, nowS, 1, st2c);
        check("3H DOJI -> NO TRADE", st2c.signal.equals("NO TRADE") && st2c.code.equals("htfColour"));
        Eval st2d = new Eval();
        for (int k = 0; k < 4; k++) { Dev d = new Dev(); d.tf = TF_NAMES[k]; d.ok = true; d.colour = 1; st2d.dev[k] = d; }
        stage2(sZig, sZig.c[end - 1] - 0.5, nowS, 1, st2d);
        check("live price below the swing high -> no breakout -> NO TRADE", st2d.signal.equals("NO TRADE") && st2d.code.equals("breakout"));
        Eval st2e = new Eval();
        for (int k = 0; k < 4; k++) { Dev d = new Dev(); d.tf = TF_NAMES[k]; d.ok = true; d.colour = -1; st2e.dev[k] = d; }
        stage2(sZig, pxTop, nowS, -1, st2e);
        check("SHORT direction on a rising 15M EMA -> NO TRADE", st2e.signal.equals("NO TRADE") && st2e.code.equals("ema"));
        double[] ema = ema15m(sZig, pxTop, nowS, null);
        check("15M EMA9 > EMA21 on the rising zig-zag", ema != null && ema[0] > ema[1]);

        System.out.println("[Test] 5M swing breakout (fresh break only)");
        double[] hiL = {5, 6, 7, 9, 7, 6, 5, 5.5, 5.2, 5.1, 5.3, 9.5};
        double[] clL = {5, 5.5, 6.5, 8.5, 6.5, 5.5, 5, 5.2, 5.1, 5.0, 5.2, 9.2};
        double[] loL = new double[hiL.length];
        for (int i = 0; i < hiL.length; i++) loL[i] = hiL[i] - 0.5;
        Breakout bl = findBreakout(hiL, loL, clL, true, 2, 10);
        check("LONG: live price above confirmed swing high (9.0)", bl.ok && Math.abs(bl.level - 9.0) < 1e-9);
        double[] clNo = clL.clone(); clNo[clNo.length - 1] = 8.9;
        check("LONG: price below the swing high -> no breakout", !findBreakout(hiL, loL, clNo, true, 2, 10).ok);
        double[] clOld = clL.clone(); clOld[7] = 9.1;
        check("LONG: level already broken earlier -> not fresh", !findBreakout(hiL, loL, clOld, true, 2, 10).ok);
        double[] hiS = new double[hiL.length], loS = new double[hiL.length], clS = new double[hiL.length];
        for (int i = 0; i < hiL.length; i++) { hiS[i] = 20 - loL[i]; loS[i] = 20 - hiL[i]; clS[i] = 20 - clL[i]; }
        Breakout bs = findBreakout(hiS, loS, clS, false, 2, 10);
        check("SHORT: price below confirmed swing low (11.0)", bs.ok && Math.abs(bs.level - 11.0) < 1e-9);

        System.out.println("[Test] Fixed SL / TP from the actual average entry");
        SlTp l = computeFixedSlTp(true, 100.0, 0.01), s = computeFixedSlTp(false, 100.0, 0.01);
        double eSl = FIXED_SL_PERCENT / 100.0, eTp = FIXED_TP_PERCENT / 100.0;
        check("LONG SL/TP = entry -/+ configured percent", l.ok && Math.abs(l.sl - 100.0 * (1 - eSl)) < 0.006 && Math.abs(l.tp - 100.0 * (1 + eTp)) < 0.006);
        check("SHORT SL/TP = entry +/- configured percent", s.ok && Math.abs(s.sl - 100.0 * (1 + eSl)) < 0.006 && Math.abs(s.tp - 100.0 * (1 - eTp)) < 0.006);
        SlTp avg = computeFixedSlTp(true, 102.5, 0.5);
        check("tick rounding keeps SL/TP on the correct side", avg.ok && avg.sl < 102.5 && avg.tp > 102.5);
        check("break-even trigger sits inside the SL distance", BREAKEVEN_TRIGGER_PERCENT < FIXED_SL_PERCENT && PROFIT_LOCK_PERCENT < BREAKEVEN_TRIGGER_PERCENT);

        System.out.println("[Test] Dynamic trailing (activation, step, equal shift, never backward)");
        double tk = 0.01, entry = 100.0;
        double actL = entry * (1 + TRAIL_ACTIVATION_PERCENT / 100.0);
        double stepL = actL * TRAIL_STEP_PERCENT / 100.0;
        double slL = 99.0, tpL = 101.5;
        TrailStep t0 = computeTrail(true, entry, slL, tpL, actL - 0.1, false, 0, tk);
        check("LONG: below activation level -> not active, no move", !t0.active && !t0.move);
        TrailStep t1 = computeTrail(true, entry, slL, tpL, actL, false, 0, tk);
        check("LONG: activation level reached -> active, no move yet", t1.active && t1.justActivated && !t1.move && Math.abs(t1.activationRef - actL) < 1e-9);
        TrailStep t2 = computeTrail(true, entry, slL, tpL, actL + stepL * 0.5, true, actL, tk);
        check("LONG: less than one step beyond the trail point -> no API update", !t2.move);
        TrailStep t3 = computeTrail(true, entry, slL, tpL, actL + stepL * 1.5, true, actL, tk);
        check("LONG: step reached -> move", t3.move && t3.newSl > slL && t3.newTp > tpL);
        check("LONG: SL and TP shifted by exactly the same amount", t3.move && Math.abs((t3.newSl - slL) - (t3.newTp - tpL)) < 1e-9
                && Math.abs((t3.newSl - slL) - t3.shift) < 1e-9);
        check("LONG: SL<->TP gap unchanged", t3.move && Math.abs((t3.newTp - t3.newSl) - (tpL - slL)) < 1e-9);
        check("LONG: trail point advances by the shift", t3.move && Math.abs(t3.newRef - (actL + t3.shift)) < 1e-9);
        TrailStep t4 = computeTrail(true, entry, t3.newSl, t3.newTp, actL, true, t3.newRef, tk);
        check("LONG: price falling back never moves SL/TP down", !t4.move && t4.newSl == t3.newSl && t4.newTp == t3.newTp);
        double actS = entry * (1 - TRAIL_ACTIVATION_PERCENT / 100.0);
        double stepS = actS * TRAIL_STEP_PERCENT / 100.0;
        double slS = 101.0, tpS = 98.5;
        TrailStep s1 = computeTrail(false, entry, slS, tpS, actS, false, 0, tk);
        check("SHORT: activation level reached -> active, no move yet", s1.active && s1.justActivated && !s1.move);
        TrailStep s3 = computeTrail(false, entry, slS, tpS, actS - stepS * 1.5, true, actS, tk);
        check("SHORT: step reached -> SL and TP both move DOWN by the same amount", s3.move && s3.newSl < slS && s3.newTp < tpS
                && Math.abs((slS - s3.newSl) - (tpS - s3.newTp)) < 1e-9 && Math.abs((s3.newTp - s3.newSl) - (tpS - slS)) < 1e-9);
        TrailStep s4 = computeTrail(false, entry, s3.newSl, s3.newTp, actS + 0.5, true, s3.newRef, tk);
        check("SHORT: price rising back never moves SL/TP up", !s4.move);
        TrailStep tc = computeTrail(true, entry, 98.0, 103.0, actL + stepL * 1.5, true, actL, tk);
        check("custom (manually set) SL/TP: their gap (5.00) is preserved", tc.move && Math.abs((tc.newTp - tc.newSl) - 5.0) < 1e-9);
        check("manual-change detection uses half a tick", priceDiffers(99.0, 99.02, 0.01) && !priceDiffers(99.0, 99.004, 0.01));

        System.out.println("[Test] Break-even + profit lock (SL only, never backward)");
        double beTrigL = entry * (1 + BREAKEVEN_TRIGGER_PERCENT / 100.0), beLockL = entry * (1 + PROFIT_LOCK_PERCENT / 100.0);
        BeStep b0 = computeBreakeven(true, entry, slL, beTrigL - 0.05, tk);
        check("BE LONG: below trigger -> not reached, no move", !b0.reached && !b0.move);
        BeStep b1 = computeBreakeven(true, entry, slL, beTrigL, tk);
        check("BE LONG: trigger reached -> SL moves up to entry + lock", b1.reached && b1.move && Math.abs(b1.newSl - beLockL) < 0.006 && b1.newSl > entry);
        BeStep b2 = computeBreakeven(true, entry, beLockL + 0.5, beTrigL, tk);
        check("BE LONG: SL already better than lock level -> never moved backwards", b2.reached && !b2.move && b2.newSl == beLockL + 0.5);
        double beTrigS = entry * (1 - BREAKEVEN_TRIGGER_PERCENT / 100.0), beLockS = entry * (1 - PROFIT_LOCK_PERCENT / 100.0);
        BeStep b3 = computeBreakeven(false, entry, slS, beTrigS + 0.05, tk);
        check("BE SHORT: above trigger level -> not reached, no move", !b3.reached && !b3.move);
        BeStep b4 = computeBreakeven(false, entry, slS, beTrigS, tk);
        check("BE SHORT: trigger reached -> SL moves down to entry - lock", b4.reached && b4.move && Math.abs(b4.newSl - beLockS) < 0.006 && b4.newSl < entry);
        BeStep b5 = computeBreakeven(false, entry, beLockS - 0.5, beTrigS, tk);
        check("BE SHORT: SL already better than lock level -> never moved backwards", b5.reached && !b5.move);

        System.out.println("[Test] Portfolio profit target (combined PnL / combined margin)");
        double pLong = positionPnl(10, 100.0, 100.6), pShort = positionPnl(-10, 100.0, 99.4);
        check("LONG and SHORT both profit when price moves in their favour", pLong > 0 && pShort > 0 && Math.abs(pLong - pShort) < 1e-9);
        check("SHORT loses when price rises, LONG loses when price falls", positionPnl(-10, 100.0, 101.0) < 0 && positionPnl(10, 100.0, 99.0) < 0);
        check("margin = notional / leverage (SHORT uses |qty|)", Math.abs(positionMargin(-10, 100.0, 5) - 200.0) < 1e-9 && positionMargin(10, 100.0, 0) == 0);
        check("0.6% price move at 5x = 3% of margin -> target reached", portfolioTargetReached(pLong, positionMargin(10, 100.0, 5), 3.0));
        check("0.5% price move at 5x -> target NOT reached", !portfolioTargetReached(positionPnl(10, 100.0, 100.5), positionMargin(10, 100.0, 5), 3.0));
        // 4 positions, margin 200 each (800 total): +20 +15 -8 -3 = +24 = exactly 3.0% of 800
        double comb = 20 + 15 - 8 - 3;
        check("combined example: +24 on 800 margin = 3.0% -> reached", portfolioTargetReached(comb, 800.0, 3.0));
        check("combined example: +23 on 800 margin = 2.875% -> not reached", !portfolioTargetReached(23.0, 800.0, 3.0));
        check("zero margin never triggers", !portfolioTargetReached(5.0, 0.0, 3.0));
        check("losing basket never triggers", !portfolioTargetReached(-10.0, 800.0, 3.0));

        System.out.println("[Test] Trade state persistence round-trip");
        TradeInfo pt = new TradeInfo();
        pt.isLong = false; pt.entry = 123.456; pt.sl = 125.0; pt.tp = 120.5; pt.initialSl = 124.7; pt.risk = 1.244;
        pt.maxPrice = 124.0; pt.minPrice = 119.9; pt.openedMs = 1_760_000_000_000L; pt.ctx = "1H:ST=G/C=R";
        pt.trailActive = true; pt.trailRef = 121.9; pt.lastTrailMs = 1_760_000_100_000L; pt.beDone = true;
        TradeInfo rt = tradeFromJson(new JSONObject(tradeToJson(pt).toString()));
        check("all trailing fields survive save/load", !rt.isLong && rt.entry == pt.entry && rt.sl == pt.sl && rt.tp == pt.tp
                && rt.maxPrice == pt.maxPrice && rt.minPrice == pt.minPrice && rt.trailActive && rt.trailRef == pt.trailRef
                && rt.initialSl == pt.initialSl && rt.openedMs == pt.openedMs && rt.lastTrailMs == pt.lastTrailMs && rt.ctx.equals(pt.ctx) && rt.beDone);

        System.out.println("[Test] Duplicate signal protection (one per 15M developing candle)");
        Map<String, Long> reg = new HashMap<>();
        check("first signal accepted", registerSignal(reg, "B-BTC_USDT|LONG", 1000L));
        check("same 15M candle, same direction rejected", !registerSignal(reg, "B-BTC_USDT|LONG", 1000L));
        check("next 15M candle accepted", registerSignal(reg, "B-BTC_USDT|LONG", 1000L + FIFTEEN_MIN_MS));
        check("other direction is tracked separately", registerSignal(reg, "B-BTC_USDT|SHORT", 1000L));

        System.out.println("[Test] Quantity precision / minimum size validation");
        JSONObject inst = new JSONObject("{\"quantity_increment\":0.1,\"min_quantity\":1,\"min_notional\":5}");
        check("12.34 floors to 12.3", Math.abs(sizeQuantity(12.34, 100, false, inst).qty - 12.3) < 1e-9);
        check("0.57 rejected (below min quantity)", sizeQuantity(0.57, 100, false, inst).qty == 0);
        check("notional below minimum rejected", sizeQuantity(1.0, 1.0, false, inst).qty == 0);
        check("integer-only pair floors to whole units", sizeQuantity(7.9, 1, true, null).qty == 7.0);
        check("quantity rounding to 0 rejected", sizeQuantity(0.004, 1, false, null).qty == 0);

        System.out.println("[Test] Partial fill + API failure helpers");
        check("filled 40% of 10 = partial", isPartialFill(10, 4));
        check("fully filled is not partial", !isPartialFill(10, 10));
        check("no fill is not 'partial'", !isPartialFill(10, 0));
        check("error body detected", isErrorResponse("{\"err_code_dcx\":\"x\"}") && isErrorResponse("") && isErrorResponse(null) && isErrorResponse("<html>"));
        check("normal body accepted", !isErrorResponse("{\"message\":\"success\"}"));

        System.out.println("\nSelf-test: " + (testsRun - testsFailed) + "/" + testsRun + " passed");
        return testsFailed == 0;
    }
}






























// import org.json.JSONArray;
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
// import java.nio.file.StandardCopyOption;
// import java.time.Instant;
// import java.util.*;
// import java.util.concurrent.ConcurrentHashMap;
// import java.util.concurrent.TimeUnit;
// import java.util.stream.Collectors;
// import java.util.stream.Stream;

// // =============================================================================
// // SUPERTREND + EMA + 5M BREAKOUT STRATEGY  (all timeframes judged on the DEVELOPING candle)
// //
// //   Every timeframe candle used here is a DEVELOPING candle:
// //     open  = open of the FIRST 5M candle of its UTC bucket
// //     high/low = built from the 5M candles of that bucket + the live price
// //     close = live price
// //   Already-closed candles are only used as history (older Supertrend / EMA bars), never as the decision candle.
// //
// //   STAGE 1 (cheap, every minute, uses the hourly-cached history + 1 small 5M request):
// //     Supertrend(10, 3.0) on 1H, 2H, 3H, 4H (last bar = developing bar), plus colour of the developing 1H and 2H candle.
// //       LONG  candidate : all four Supertrend RED   + developing 1H GREEN + developing 2H GREEN   (ST_CONTRARIAN = true)
// //       SHORT candidate : all four Supertrend GREEN + developing 1H RED   + developing 2H RED
// //     (ST_CONTRARIAN = false flips the Supertrend side: LONG = all four GREEN, SHORT = all four RED)
// //
// //   STAGE 2 (only for candidates, with the live price, ALL must pass = AND):
// //     3H developing candle colour == direction (price vs open, DOJI rejected)
// //     4H developing candle colour == direction
// //     15M EMA9 vs EMA21 (closes of completed 15M bars + live price as the developing close)
// //     5M swing breakout: live price beyond the last confirmed swing high/low (3-3 pivot, 60-candle lookback), fresh only
// //   Just before the order, fresh 5M candles + fresh price are fetched and BOTH stages are checked again.
// //
// //   SL / TP = fixed % from the ACTUAL average filled entry price.
// //
// //   BREAK-EVEN + PROFIT LOCK (BREAKEVEN_ENABLED):  unchanged (SL only, one-time, never backwards)
// //   DYNAMIC TRAILING (TRAILING_ENABLED):           unchanged (SL and TP shift together, state persisted)
// //
// //   Offline tests:  java -cp <classes>:<org.json jar> CoinDCXFuturesTrader8C_BUY_SELL_NEW_LOGIC_THREE --selftest
// // =============================================================================
// public class CoinDCXFuturesTrader8C_BUY_SELL_NEW_LOGIC_THREE {

//     // =========================================================================
//     // Execution mode  (false = PAPER. Switch to true only after paper results are good)
//     // =========================================================================
//     static final boolean LIVE_TRADING_ENABLED = true;

//     // =========================================================================
//     // Fixed SL / TP (percent of the actual average entry price) -- initial values at entry
//     // (reduced from 10 / 25)
//     // =========================================================================
//     static final double  FIXED_SL_PERCENT = 6.0;
//     static final double  FIXED_TP_PERCENT = 15.0;

//     // =========================================================================
//     // Break-even + Profit Lock  (LIVE mode only)  -- SL only, TP is never changed by this feature
//     // =========================================================================
//     static final boolean BREAKEVEN_ENABLED = true;           // MASTER SWITCH: false = break-even / profit-lock OFF
//     static final double  BREAKEVEN_TRIGGER_PERCENT = 5.0;    // favourable move (% of entry) that activates break-even
//     static final double  PROFIT_LOCK_PERCENT       = 1.5;    // SL goes to entry +PROFIT_LOCK% (LONG) / entry -PROFIT_LOCK% (SHORT). 0 = exact break-even

//     // =========================================================================
//     // Dynamic trailing SL / TP  (LIVE mode only)
//     // =========================================================================
//     static final boolean TRAILING_ENABLED = true;            // MASTER SWITCH: false = fixed SL/TP only, no trailing
//     static final double  TRAIL_ACTIVATION_PERCENT = 6.0;     // trailing starts after price moves this % in profit
//     static final double  TRAIL_STEP_PERCENT       = 0.1;     // SL/TP are moved only when price advanced this % beyond the last trail point
//     static final long    TRAIL_CHECK_INTERVAL_MS  = 5_000L; // how often open positions are checked for trailing
//     static final int     TRAIL_UPDATE_MAX_RETRIES = 3;       // SL/TP replace attempts per update
//     static final long    TRAIL_VERIFY_DELAY_MS    = 800;  // wait before reading the position back after an update
//     static final long    TRAIL_RETRY_BACKOFF_MS   = 15_000L; // after a failed update, wait before trying again
//     static final long    TRAIL_STATUS_LOG_MS      = 60_000L; // status log per position at most once per this interval
//     static final double  TRAIL_ENTRY_MISMATCH_PCT = 0.3;     // saved state whose entry differs more than this from the exchange = old/other position
//     static final String  TRADE_STATE_FILE = "trade_state_v4.json";

//     // =========================================================================
//     // API configuration (unchanged)
//     // =========================================================================
//     private static final String API_KEY    = System.getenv("DELTA_API_KEY");
//     private static final String API_SECRET = System.getenv("DELTA_API_SECRET");
//     private static final String BASE_URL       = "https://api.coindcx.com";
//     private static final String PUBLIC_API_URL = "https://public.coindcx.com";

//     private static final int MAX_ENTRY_PRICE_CHECKS = 20;
//     private static final int ENTRY_CHECK_DELAY_MS    = 1000;

//     private static final int  TPSL_MAX_RETRIES    = 3;
//     private static final long TPSL_RETRY_DELAY_MS = 2000L;

//     private static final int  CANCEL_MAX_RETRIES     = 3;
//     private static final long CANCEL_RETRY_DELAY_MS  = 1000L;
//     private static final long CANCEL_VERIFY_DELAY_MS = 1500L;

//     private static final long TICK_CACHE_TTL_MS = 3_600_000L;

//     private static final int MAX_OPEN_POSITIONS = 500;

//     private static final int  POSITION_ID_MAX_RETRIES = 5;
//     private static final long POSITION_ID_RETRY_DELAY_MS = 1500L;

//     // =========================================================================
//     // Capital configuration (unchanged)
//     // =========================================================================
//     private static final double FIXED_MARGIN = 1000.0;   // legacy sizing: notional INR value = FIXED_MARGIN
//     private static final int    LEVERAGE     = 6;
//     static final double  USDT_INR_RATE       = 102.0;

//     // =========================================================================
//     // Time constants / candle alignment
//     // =========================================================================
//     static final long MIN_MS         = 60_000L;
//     static final long FIVE_MIN_MS    = 5 * MIN_MS;
//     static final long FIFTEEN_MIN_MS = 15 * MIN_MS;
//     static final long HOUR_MS        = 60 * MIN_MS;
//     static final long TWO_HOUR_MS    = 2 * HOUR_MS;
//     static final long THREE_HOUR_MS  = 3 * HOUR_MS;
//     static final long FOUR_HOUR_MS   = 4 * HOUR_MS;
//     // 15M/1H/2H/3H/4H buckets are epoch(UTC)-aligned plus this offset. VERIFY the 3H candles against the CoinDCX chart;
//     // the offset must stay a multiple of 15 minutes.
//     static final long HTF_ALIGN_OFFSET_MS = 0L;

//     // =========================================================================
//     // Indicators
//     // =========================================================================
//     static final int    ST_PERIOD     = 10;       // Supertrend(10, 3.0) on 1H / 2H / 3H / 4H
//     static final double ST_MULTIPLIER = 3.0;
//     static final int    EMA_FAST      = 9;        // EMA9 / EMA21 on 15M
//     static final int    EMA_SLOW      = 21;

//     // =========================================================================
//     // Strategy parameters
//     // =========================================================================
//     // ORDER MATTERS: index 0..3 = 1H, 2H, 3H, 4H
//     static final String[] TF_NAMES = {"1H", "2H", "3H", "4H"};
//     static final long[]   TF_MS    = {HOUR_MS, TWO_HOUR_MS, THREE_HOUR_MS, FOUR_HOUR_MS};

//     // true  : LONG needs Supertrend RED on all four timeframes (reversal style) and SHORT needs all four GREEN
//     // false : LONG needs Supertrend GREEN on all four, SHORT needs all four RED (trend-following)
//     static final boolean ST_CONTRARIAN = true;

//     static final int ST_MIN_BARS      = 25;   // completed bars needed per timeframe before the Supertrend is trusted
//     static final int EMA_MIN_BARS_15M = 40;   // completed 15M bars needed for EMA9/EMA21
//     static final int EMA_WINDOW_15M   = 120;  // how many 15M bars are fed into the EMA calculation

//     static final int SWING_PIVOT_BARS   = 3;    // a swing high/low needs this many lower highs / higher lows on each side
//     static final int SWING_LOOKBACK_5M  = 60;   // how far back the previous confirmed swing may be

//     static final double EPS = 1e-12;

//     // =========================================================================
//     // Data fetching
//     // =========================================================================
//     static final int  MAX_BARS_PER_CALL = 500;
//     static final long MIN_REQUEST_GAP_MS = 60L;

//     static final int HIST_5M       = 1800;   // 5M history kept per symbol (150h = ~37 completed 4H bars). Refreshed once per hour (incremental)
//     static final int SCAN_FETCH_5M = 80;     // small request every minute (a developing 4H bucket needs at most 48 candles + margin)

//     // =========================================================================
//     // Timing / duplicate protection
//     // =========================================================================
//     static final long COOLDOWN_MS      = 5L * 60 * 1000L;
//     static final long SCAN_STEP_MS     = 60_000L;
//     static final long SCAN_DELAY_MS    = 5_000L;
//     static final long PAPER_MAX_HOLD_MS = 24L * 60 * 60 * 1000L;

//     static final boolean LOG_TRADES_CSV = true;
//     static final boolean LOG_NO_TRADE_DETAIL = true;   // log full detail for symbols that passed stage 1 but not stage 2
//     static final String  TRADE_LOG_FILE = "trade_log_v4.csv";
//     static final String  PAPER_LOG_FILE = "paper_trade_log_v4.csv";

//     private static final double LIMIT_ORDER_BUFFER_PCT = 0.0005;

//     static class TradeInfo {
//         boolean isLong;
//         double entry, sl, tp;           // sl / tp = the levels the bot believes are currently on the exchange
//         double initialSl, risk;
//         double maxPrice, minPrice;      // highest / lowest price seen since entry
//         long openedMs;
//         String ctx = "";
//         // ---- trailing state (persisted) ----
//         boolean trailActive;            // activation level reached
//         double trailRef;                // last price point SL/TP were trailed to (activation level until the first shift)
//         long lastTrailMs;
//         boolean beDone;                 // break-even / profit-lock already handled for this trade (persisted)
//         // ---- runtime only (NOT persisted) ----
//         long nextTrailTryMs;
//         long lastStatusLogMs;
//     }
//     static class PaperTrade {
//         boolean isLong;
//         double entry, sl, tp;
//         long openedMs;
//         String ctx = "";
//     }
//     private static final Map<String, TradeInfo> tradeState = new ConcurrentHashMap<>();
//     private static final Map<String, PaperTrade> paperTrades = new ConcurrentHashMap<>();
//     private static final Map<String, JSONObject> instrumentCache = new ConcurrentHashMap<>();
//     private static long lastCacheUpdate = 0;
//     private static final Map<String, Long> lastTradeTime = new ConcurrentHashMap<>();
//     private static final Map<String, Long> lastSignalCandle = new ConcurrentHashMap<>();

//     private static final String[] COIN_SYMBOLS = {
//         "BANK","PIEVERSE","ALPINE","OG","XAU","CVC","KAT","SOON","MANTRA","LAB","EDGE","PIPPIN","BSB",
//         "BASED","MUBARAK","TUT","MMT","F","ARKM","GRVT","PONS","AIOT","AGT","JCT","ZEST","ICNT","MAGIC",
//         "ON","TA","PROVE","SKY","AVNT","KGEN","DATAIP","CYS","LIT","DOGE","ELSA","SKR","ESP","SOL","B","MYX",
//         "H","IDOL","LTC","TRX","ROSE","IOST","ESPORTS","ALGO","FARTCOIN","KAVA","BAND","RLC","PUMP","ZORA",
//         "DOT","GIGGLE","Q","RSR","FLOCK","CARV","BEL","HEMI","COTI","C98","ARPA","NEIRO","PEOPLE","WIF","MANTA",
//         "IMX","XAI","API3","GMT","APE","JASMY","INJ","ETHFI","STBL","W","SAGA","SPELL","RARE","HOLO","LUNA2","G",
//         "SUN","CL","ZKC","O","1000LUNC","MORPHO","ARB","WET","SPX","ME","AVA","NIGHT","PENGU","LUMIA","FLUID","T",
//         "SEI","AIXBT","MINA","AKE","BICO","KMNO","JOE","BLUR","MAV","PENDLE","ALT","CRV","PHA","BILL","FF","YGG",
//         "CYBER","BIGTIME","ZKP","WAXP","BREV","POLYX","GAS","POWR","MEME","OPN","ILV","KAS","1000BONK","BCH","SUPER",
//         "ETHW","CTR","JUP","LINK","RONIN","XMR","DYM","ENS","NEO","GLM","PORTAL","JST","AXL","METIS","BOME","UNI",
//         "RENDER","ZEN","1INCH","ALCH","1000XEC","STABLE","VTHO","DIA","MOODENG","SENT","SONIC","SAFE","1000FLOKI",
//         "CETUS","ARIA","AKT","TAKE","ARK","CHILLGUY","BSV","ANIME","CAKE","VIRTUAL","DEEP","TURTLE","TST","USUAL",
//         "ZETA","SHELL","PIXEL","NIL","VVV","FORM","PAXG","CHIP","ATH","INIT","LISTA","SKYAI","AWE","HUMA","TNSR",
//         "TAO","POPCAT","THE","1000CAT","PLUME","ACT","BEAMX","M","HIVE","LSK","RVN","BIO","BB","TURBO","IO","RPL",
//         "POL","1MBABYDOGE","FIDA","ARC","1000000MOG","REZ","BR","SWARMS","TRUMP","AIO","RE","CROSS","DYDX","CAP",
//         "CATI","NAORIS","BANANAS31","AR","PNUT","BAN","ORCA","LINEA","MOVE","DEXE","ETH","GRIFFAIN","COOKIE","AERO",
//         "S","RAYSOL","ERA","SOLV","XRP","AVAAI","MASK","B2","PARTI","MAVIA","HEI","TRUTH","JELLYJELLY","FRAX","DASH",
//         "BZ","OPG","ROBO","BTW","BNB","FLOW","BTC","ATOM","ZIL","XLM","FET","NEWT","SOPH","KNC","COMP","NATGAS","BMT",
//         "ASTR","CFX","MERL","YFI","SAPIEN","RUNE","BTR","STX","AVAX","LA","NEAR","SLP","PHAROS","LIGHT","1000PEPE",
//         "EDEN","LYN","ONE","MET","RECALL","MTL","4","1000SHIB","BLUAI","APR","GTC","EGLD","RIVER","CTSI","TAIKO",
//         "AGLD","BNT","ENJ","IOTX","DOS","SQD","NOM","FIL","RIF","BULLA","IN","EVAA","AXS","TWT","SUI","STEEM","CLO",
//         "CHZ","AT","ANKR","SPACE","STO","MEGA","COPPER","DOOD","CHR","SUSHI","MANA","BERA","CELR","AIGENSYN","MARSCOIN",
//         "TAC","SLX","TAG","CT","DODOX","ADA","LPT","GRAM","DUSK","THETA","GMX","TIA","TREE","USTC","TRB","GWEI","UAI",
//         "TRIA","LDO","ICP","KSM","ONG","UB","USELESS","SSV","ID","HBAR","HAEDAL","US","VELVET","ETC","VET","AUCTION",
//         "WLFI","WOO","XAG","ORDI","SFP","BAT","1000RATS","XNY","ACE","ACH","QTUM","CKB","XVS","YB","EDU","ZBT","ZEC",
//         "UMA","MOVR","ZRX","PYTH","AAVE","ENA","GRT","SAND","MEW","HMSTR","SANTOS","FLUX","DRIFT","OP","APT","QNT",
//         "KAIA","LQTY","XVG","MELANIA","GPS","FHE","RED","SXT","KERNEL","PUNDIX","STRK","ZK","ZRO","GRASS","SCR",
//         "GOAT","MOCA","PROM","KAITO","SIREN","WAL","WCT","ASR","A","HYPE","AIN","PLAY","OPEN","XPIN","BLESS","COAI",
//         "HANA","ORDER","XAN","CLANKER","BEAT","IRYS","PRL","RAVE","MAGMA","ZAMA","FOGO","ACU","FIGHT","BIRB","INX",
//         "AZTEC","CFG","GENIUS","ARX","ALLO","BROCCOLI714","HYPER","XPL","RESOLV","ASTER","KITE","SIGN","HOME","MON",
//         "CC","SAHARA","MIRA","EUL","TOWNS","SYRUP","C","DOLO","ALICE","BABY","SOMI","NOT","BARD","SPK","POWER","2Z",
//         "BANANA","ENSO","SYN","NXPC","GUN","XTZ","ONT","SKL","HOT","JTO","DOGS","EIGEN","GALA","NMR","CGPT","ZEREBRO",
//         "VANA","OGN","CELO","COW","0G","IOTA","SNX","WLD","1000SATS","ONDO","AEVO","BRETT","LAYER","TLM","KOMA"
//     };

//     // Optional liquid subset (edit freely). When USE_LIQUID_LIST = true only these symbols are traded.
//     static final boolean USE_LIQUID_LIST = false;
//     private static final Set<String> LIQUID_SYMBOLS = new HashSet<>(Arrays.asList(
//         "BTC","ETH","SOL","XRP","DOGE","BNB","ADA","AVAX","LINK","DOT","LTC","BCH","TRX","ATOM",
//         "NEAR","APT","ARB","OP","SUI","INJ","UNI","AAVE","FIL","ETC","XLM","HBAR","ICP","TIA","SEI",
//         "WLD","ONDO","1000PEPE","1000SHIB","FET","RENDER","TAO","ENA","JUP","PENDLE","STX","IMX",
//         "LDO","CRV","SAND","MANA","GALA","AXS","APE","ALGO","EGLD","THETA","VET","XMR","DASH","ZEC",
//         "NEO","QTUM","XTZ","FLOW","KAS","TRUMP","WIF","1000BONK","HYPE","PUMP","FARTCOIN","PENGU",
//         "WLFI","COMP","SNX","DYDX","GMX","1INCH","RUNE","POL","BERA","ORDI","PYTH","STRK","ZRO",
//         "EIGEN","ETHFI","AEVO"));

//     private static final Set<String> INTEGER_QTY_PAIRS = Stream.of(COIN_SYMBOLS)
//             .flatMap(s -> Stream.of("B-" + s + "_USDT", s + "_USDT"))
//             .collect(Collectors.toCollection(HashSet::new));

//     private static final String[] COINS_TO_TRADE = Stream.of(COIN_SYMBOLS)
//             .filter(s -> !USE_LIQUID_LIST || LIQUID_SYMBOLS.contains(s))
//             .map(s -> "B-" + s + "_USDT")
//             .toArray(String[]::new);

//     // =========================================================================
//     // Small helpers
//     // =========================================================================
//     static int colourOf(double open, double close) { return close > open ? 1 : (close < open ? -1 : 0); }
//     static String colName(int c) { return c > 0 ? "GREEN" : (c < 0 ? "RED" : "DOJI"); }
//     static String fmtTime(long ms) { return Instant.ofEpochMilli(ms).toString(); }

//     static long alignedStart(long ms, long bucketMs, long offsetMs) {
//         return Math.floorDiv(ms - offsetMs, bucketMs) * bucketMs + offsetMs;
//     }

//     private static void sleepMs(long ms) {
//         try { Thread.sleep(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
//     }

//     // =========================================================================
//     // Candle helpers
//     // =========================================================================
//     // Assumes the candle "time" field is the candle OPEN time (seconds or ms).
//     static long candleTimeMs(JSONObject c) {
//         double t = c.optDouble("time", -1);
//         if (t <= 0) return -1;
//         long v = (long) t;
//         return v < 100_000_000_000L ? v * 1000L : v;
//     }

//     static JSONArray dropLastIfForming(JSONArray arr) {
//         if (arr == null || arr.length() < 2) return arr;
//         JSONArray out = new JSONArray();
//         for (int i = 0; i < arr.length() - 1; i++) out.put(arr.getJSONObject(i));
//         return out;
//     }

//     // Sorted oldest-first, duplicate timestamps removed. Null if input null or any candle lacks a timestamp.
//     static JSONArray sortByTime(JSONArray src) {
//         if (src == null) return null;
//         List<JSONObject> l = new ArrayList<>();
//         for (int i = 0; i < src.length(); i++) {
//             JSONObject c = src.getJSONObject(i);
//             if (candleTimeMs(c) < 0) return null;
//             l.add(c);
//         }
//         l.sort(Comparator.comparingLong(CoinDCXFuturesTrader8C_BUY_SELL_NEW_LOGIC_THREE::candleTimeMs));
//         JSONArray out = new JSONArray();
//         long prev = -1;
//         for (JSONObject c : l) {
//             long t = candleTimeMs(c);
//             if (t == prev) continue;
//             out.put(c);
//             prev = t;
//         }
//         return out;
//     }

//     static JSONArray fetchCandlesPaged(String pair, String res, int minsPerBar, int count) {
//         long nowSec = Instant.now().getEpochSecond();
//         if (count <= MAX_BARS_PER_CALL) {
//             return getCandlestickRange(pair, res, nowSec - (long) minsPerBar * 60L * count, nowSec);
//         }
//         List<JSONObject> all = new ArrayList<>();
//         long toSec = nowSec;
//         int remaining = count;
//         boolean first = true;
//         while (remaining > 0) {
//             int n = Math.min(MAX_BARS_PER_CALL, remaining);
//             long fromSec = toSec - (long) minsPerBar * 60L * n;
//             JSONArray part = getCandlestickRange(pair, res, fromSec, toSec);
//             if (part == null) {
//                 if (first) return null;
//                 break;
//             }
//             for (int i = 0; i < part.length(); i++) all.add(part.getJSONObject(i));
//             first = false;
//             remaining -= n;
//             toSec = fromSec - 1;
//             if (part.length() == 0) break;
//         }
//         for (JSONObject c : all) {
//             if (candleTimeMs(c) < 0) {
//                 System.err.println("  [ERROR] candle has no 'time' field — cannot merge pages for " + pair);
//                 return null;
//             }
//         }
//         all.sort(Comparator.comparingLong(CoinDCXFuturesTrader8C_BUY_SELL_NEW_LOGIC_THREE::candleTimeMs));
//         JSONArray out = new JSONArray();
//         long prev = -1;
//         for (JSONObject c : all) {
//             long t = candleTimeMs(c);
//             if (t == prev) continue;
//             out.put(c);
//             prev = t;
//         }
//         return out;
//     }

//     private static long lastRequestAt = 0;
//     private static synchronized void throttle() {
//         long now = System.currentTimeMillis();
//         long wait = MIN_REQUEST_GAP_MS - (now - lastRequestAt);
//         if (wait > 0) {
//             try { Thread.sleep(wait); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
//         }
//         lastRequestAt = System.currentTimeMillis();
//     }

//     private static JSONArray getCandlestickRange(String pair, String resolution, long from, long to) {
//         try {
//             throttle();
//             String url = PUBLIC_API_URL + "/market_data/candlesticks"
//                     + "?pair=" + pair + "&from=" + from + "&to=" + to
//                     + "&resolution=" + resolution + "&pcode=f";
//             HttpURLConnection conn = openGet(url);
//             int code = conn.getResponseCode();
//             if (code == 200) {
//                 JSONObject r = new JSONObject(readStream(conn.getInputStream()));
//                 if ("ok".equals(r.optString("s"))) return r.getJSONArray("data");
//                 System.err.println("  Candle s=" + r.optString("s") + " " + pair + "/" + resolution);
//             } else {
//                 System.err.println("  Candle HTTP " + code + " " + pair + "/" + resolution);
//             }
//         } catch (Exception e) {
//             System.err.println("  getCandlestickRange(" + pair + "/" + resolution + "): " + e.getMessage());
//         }
//         return null;
//     }

//     // =========================================================================
//     // Compact 5M series (arrays, so a long history for ~500 symbols stays small in memory)
//     // =========================================================================
//     static class Series5 {
//         final int n;
//         final long[] t;
//         final double[] o, h, l, c;
//         Series5(int n) {
//             this.n = n;
//             t = new long[n]; o = new double[n]; h = new double[n]; l = new double[n]; c = new double[n];
//         }
//     }

//     // Input must already be sorted + de-duplicated (sortByTime). Null if input null or a candle is malformed.
//     static Series5 toSeries(JSONArray a) {
//         if (a == null) return null;
//         Series5 s = new Series5(a.length());
//         for (int i = 0; i < s.n; i++) {
//             JSONObject j = a.getJSONObject(i);
//             long t = candleTimeMs(j);
//             double o = j.optDouble("open", Double.NaN), h = j.optDouble("high", Double.NaN);
//             double l = j.optDouble("low", Double.NaN), c = j.optDouble("close", Double.NaN);
//             if (t < 0 || Double.isNaN(o) || Double.isNaN(h) || Double.isNaN(l) || Double.isNaN(c)) return null;
//             s.t[i] = t; s.o[i] = o; s.h[i] = h; s.l[i] = l; s.c[i] = c;
//         }
//         return s;
//     }

//     // a = older data, b = newer data. Everything in a that is not older than b's first candle is replaced by b.
//     static Series5 mergeSeries(Series5 a, Series5 b) {
//         if (a == null || a.n == 0) return b;
//         if (b == null || b.n == 0) return a;
//         int cut = a.n;
//         while (cut > 0 && a.t[cut - 1] >= b.t[0]) cut--;
//         Series5 r = new Series5(cut + b.n);
//         System.arraycopy(a.t, 0, r.t, 0, cut); System.arraycopy(b.t, 0, r.t, cut, b.n);
//         System.arraycopy(a.o, 0, r.o, 0, cut); System.arraycopy(b.o, 0, r.o, cut, b.n);
//         System.arraycopy(a.h, 0, r.h, 0, cut); System.arraycopy(b.h, 0, r.h, cut, b.n);
//         System.arraycopy(a.l, 0, r.l, 0, cut); System.arraycopy(b.l, 0, r.l, cut, b.n);
//         System.arraycopy(a.c, 0, r.c, 0, cut); System.arraycopy(b.c, 0, r.c, cut, b.n);
//         return r;
//     }

//     static Series5 trimSeries(Series5 s, int maxN) {
//         if (s == null || s.n <= maxN) return s;
//         int from = s.n - maxN;
//         Series5 r = new Series5(maxN);
//         System.arraycopy(s.t, from, r.t, 0, maxN);
//         System.arraycopy(s.o, from, r.o, 0, maxN);
//         System.arraycopy(s.h, from, r.h, 0, maxN);
//         System.arraycopy(s.l, from, r.l, 0, maxN);
//         System.arraycopy(s.c, from, r.c, 0, maxN);
//         return r;
//     }

//     // ---- hourly history cache: refreshed at most once per UTC hour, incrementally when possible ----
//     private static final Map<String, Series5> histCache = new ConcurrentHashMap<>();
//     private static final Map<String, Long> histHour = new ConcurrentHashMap<>();

//     private static Series5 getHistory(String pair, long nowMs) {
//         long hour = alignedStart(nowMs, HOUR_MS, 0L);
//         Series5 h = histCache.get(pair);
//         Long fetched = histHour.get(pair);
//         if (h != null && fetched != null && fetched == hour) return h;
//         Series5 fresh = null;
//         if (h != null && h.n > 0) {
//             long need = (nowMs - h.t[h.n - 1]) / FIVE_MIN_MS + 3;
//             if (need <= MAX_BARS_PER_CALL - 20) {
//                 Series5 inc = toSeries(sortByTime(fetchCandlesPaged(pair, "5", 5, (int) need)));
//                 if (inc != null && inc.n > 0) fresh = trimSeries(mergeSeries(h, inc), HIST_5M);
//             }
//         }
//         if (fresh == null) fresh = toSeries(sortByTime(fetchCandlesPaged(pair, "5", 5, HIST_5M)));
//         if (fresh == null || fresh.n == 0) return h;      // keep the old history; the validation below will reject gaps
//         histCache.put(pair, fresh);
//         histHour.put(pair, hour);
//         return fresh;
//     }

//     // Cached history + a fresh small request. Null if the fresh request failed.
//     private static Series5 getSeries(String pair, long nowMs) {
//         Series5 hist = getHistory(pair, nowMs);
//         Series5 recent = toSeries(sortByTime(fetchCandlesPaged(pair, "5", 5, SCAN_FETCH_5M)));
//         if (recent == null || recent.n == 0) return null;
//         return hist == null ? recent : mergeSeries(hist, recent);
//     }

//     // 5M series validation. Null if usable, otherwise "<code>: <reason>".
//     static String validate5m(Series5 s, long nowMs) {
//         if (s == null || s.n == 0) return "noData: no 5M candle data";
//         long cur5 = alignedStart(nowMs, FIVE_MIN_MS, 0L);
//         for (int i = 0; i < s.n; i++)
//             if (s.t[i] % FIVE_MIN_MS != 0) return "misaligned: 5M candle at " + fmtTime(s.t[i]);
//         long maxT = s.t[s.n - 1];
//         if (maxT > cur5) return "future: newest 5M candle " + fmtTime(maxT) + " after current " + fmtTime(cur5);
//         // CoinDCX sometimes does not return the forming 5M candle yet: the previous candle is fine,
//         // because the live price supplies the developing close / high / low.
//         if (maxT < cur5 - FIVE_MIN_MS) return "stale: newest 5M candle " + fmtTime(maxT) + " but forming 5M starts " + fmtTime(cur5);
//         return null;
//     }

//     // =========================================================================
//     // Higher-timeframe bars built from 5M candles
//     // =========================================================================
//     static class Bars {
//         final long[] t;
//         final double[] o, h, l, c;
//         int n;
//         Bars(int cap) { t = new long[cap]; o = new double[cap]; h = new double[cap]; l = new double[cap]; c = new double[cap]; }
//     }

//     // COMPLETE buckets only (every 5M candle present), and only buckets that start before `beforeStart`
//     // (= start of the developing bucket). These are history bars, never the decision candle.
//     static Bars aggComplete(Series5 s, long bucketMs, long beforeStart) {
//         int need = (int) (bucketMs / FIVE_MIN_MS);
//         Bars b = new Bars(s.n / need + 2);
//         int i = 0;
//         while (i < s.n) {
//             long bs = alignedStart(s.t[i], bucketMs, HTF_ALIGN_OFFSET_MS);
//             int j = i;
//             double hi = Double.NEGATIVE_INFINITY, lo = Double.POSITIVE_INFINITY;
//             while (j < s.n && alignedStart(s.t[j], bucketMs, HTF_ALIGN_OFFSET_MS) == bs) {
//                 hi = Math.max(hi, s.h[j]);
//                 lo = Math.min(lo, s.l[j]);
//                 j++;
//             }
//             if (j - i == need && s.t[i] == bs && bs < beforeStart) {
//                 b.t[b.n] = bs; b.o[b.n] = s.o[i]; b.h[b.n] = hi; b.l[b.n] = lo; b.c[b.n] = s.c[j - 1];
//                 b.n++;
//             }
//             i = j;
//         }
//         return b;
//     }

//     // =========================================================================
//     // DEVELOPING candle of one timeframe
//     // =========================================================================
//     static class Dev {
//         String tf;
//         long tfMs, start;
//         boolean ok;                 // data valid
//         String reason = "";
//         double open, high, low, price;
//         int colour;                 // +1 GREEN, -1 RED, 0 DOJI
//         boolean stBull;             // Supertrend colour with this developing bar as the last bar (true = GREEN)
//         int stBars;                 // completed bars used before the developing bar
//     }

//     // open = open of the 5M candle that starts the bucket; high/low = 5M candles of the bucket + live price; close = live price.
//     static Dev developing(Series5 s, long nowMs, double price, int k) {
//         Dev e = new Dev();
//         e.tf = TF_NAMES[k];
//         e.tfMs = TF_MS[k];
//         e.start = alignedStart(nowMs, e.tfMs, HTF_ALIGN_OFFSET_MS);
//         e.price = price;
//         long cur5 = alignedStart(nowMs, FIVE_MIN_MS, 0L);
//         long last5 = Math.min(cur5, s.t[s.n - 1]);
//         int expected = (int) ((last5 - e.start) / FIVE_MIN_MS) + 1;
//         int count = 0;
//         double open = Double.NaN, hi = price, lo = price;
//         for (int i = s.n - 1; i >= 0 && s.t[i] >= e.start; i--) {
//             if (s.t[i] > cur5) continue;
//             count++;
//             if (s.t[i] == e.start) open = s.o[i];
//             hi = Math.max(hi, s.h[i]);
//             lo = Math.min(lo, s.l[i]);
//         }
//         if (!(open > 0) || Double.isInfinite(open)) {
//             e.reason = "noOpen: first 5M candle of the bucket (" + fmtTime(e.start) + ") not available yet";
//             return e;
//         }
//         if (count != expected) {
//             e.reason = "gap: " + count + "/" + expected + " 5M candles in developing " + e.tf + " bucket";
//             return e;
//         }
//         e.open = open; e.high = hi; e.low = lo;
//         e.colour = colourOf(open, price);
//         e.ok = true;
//         return e;
//     }

//     // =========================================================================
//     // Full symbol evaluation (used by the scan AND by the pre-order revalidation)
//     // =========================================================================
//     static class Eval {
//         String signal = "NO TRADE";    // BUY / SELL / NO TRADE
//         String code = "";
//         String reason = "";
//         int dir;                       // stage-1 candidate: +1 LONG, -1 SHORT, 0 none
//         final Dev[] dev = new Dev[4];
//         double ema9, ema21, brkLevel;
//         double price;
//     }

//     private static Eval fail(Eval r, String code, String reason) {
//         r.signal = "NO TRADE"; r.code = code; r.reason = reason;
//         return r;
//     }

//     static String stName(boolean bull) { return bull ? "GREEN" : "RED"; }

//     static String summary(Eval r) {
//         StringBuilder sb = new StringBuilder();
//         for (Dev d : r.dev) if (d != null && d.ok) sb.append(d.tf).append(" ST=").append(stName(d.stBull))
//                 .append(" candle=").append(colName(d.colour)).append("; ");
//         return sb.toString().trim();
//     }

//     // STAGE 1: Supertrend on 1H/2H/3H/4H (developing bar last) + developing 1H and 2H candle colour.
//     // Returns +1 / -1 for a candidate, 0 otherwise (r.code / r.reason are set then).
//     static int stage1(Series5 s, double price, long nowMs, Eval r) {
//         String bad = validate5m(s, nowMs);
//         if (bad != null) { fail(r, "5m_" + bad.substring(0, bad.indexOf(':')), bad); return 0; }
//         for (int k = 0; k < 4; k++) {
//             Dev d = developing(s, nowMs, price, k);
//             r.dev[k] = d;
//             if (!d.ok) { fail(r, "data", "data: " + d.tf + " " + d.reason); return 0; }
//             Bars b = aggComplete(s, d.tfMs, d.start);
//             d.stBars = b.n;
//             if (b.n < ST_MIN_BARS) { fail(r, "stHistory", "history: only " + b.n + " completed " + d.tf + " bars (need " + ST_MIN_BARS + ")"); return 0; }
//             if (b.t[b.n - 1] != d.start - d.tfMs) { fail(r, "histGap", "history gap: last completed " + d.tf + " bar is not the one right before the developing bar"); return 0; }
//             int m = b.n + 1;
//             double[] hi = new double[m], lo = new double[m], cl = new double[m];
//             System.arraycopy(b.h, 0, hi, 0, b.n);
//             System.arraycopy(b.l, 0, lo, 0, b.n);
//             System.arraycopy(b.c, 0, cl, 0, b.n);
//             hi[m - 1] = d.high; lo[m - 1] = d.low; cl[m - 1] = price;      // developing bar: close = live price
//             d.stBull = calcSupertrend(hi, lo, cl, ST_PERIOD, ST_MULTIPLIER)[m - 1];
//         }
//         boolean allGreen = true, allRed = true;
//         for (Dev d : r.dev) { if (d.stBull) allRed = false; else allGreen = false; }
//         boolean longSt  = ST_CONTRARIAN ? allRed : allGreen;
//         boolean shortSt = ST_CONTRARIAN ? allGreen : allRed;
//         int c1 = r.dev[0].colour, c2 = r.dev[1].colour;
//         if (longSt && c1 == 1 && c2 == 1) { r.dir = 1; return 1; }
//         if (shortSt && c1 == -1 && c2 == -1) { r.dir = -1; return -1; }
//         fail(r, "stage1", "stage1 not aligned: " + summary(r));
//         return 0;
//     }

//     // EMA9 / EMA21 of the 15M closes: completed 15M bars + the live price as the developing close. Null if not enough data.
//     static double[] ema15m(Series5 s, double price, long nowMs, Eval r) {
//         long start15 = alignedStart(nowMs, FIFTEEN_MIN_MS, HTF_ALIGN_OFFSET_MS);
//         Bars b = aggComplete(s, FIFTEEN_MIN_MS, start15);
//         if (b.n < EMA_MIN_BARS_15M) { if (r != null) fail(r, "emaHistory", "history: only " + b.n + " completed 15M bars"); return null; }
//         if (b.t[b.n - 1] != start15 - FIFTEEN_MIN_MS) { if (r != null) fail(r, "emaGap", "history gap before the developing 15M bar"); return null; }
//         int use = Math.min(b.n, EMA_WINDOW_15M);
//         double[] cl = new double[use + 1];
//         System.arraycopy(b.c, b.n - use, cl, 0, use);
//         cl[use] = price;
//         return new double[]{calcEMA(cl, EMA_FAST), calcEMA(cl, EMA_SLOW)};
//     }

//     // STAGE 2 (AND): 3H colour, 4H colour (both developing), 15M EMA9 vs EMA21, fresh 5M swing breakout. Sets r.signal on success.
//     static void stage2(Series5 s, double price, long nowMs, int dir, Eval r) {
//         boolean isLong = dir > 0;
//         if (r.dev[2].colour != dir || r.dev[3].colour != dir) {
//             fail(r, "htfColour", "3H=" + colName(r.dev[2].colour) + " 4H=" + colName(r.dev[3].colour) + " not both " + (isLong ? "GREEN" : "RED"));
//             return;
//         }
//         double[] e = ema15m(s, price, nowMs, r);
//         if (e == null) return;
//         r.ema9 = e[0]; r.ema21 = e[1];
//         if (isLong ? !(e[0] > e[1]) : !(e[0] < e[1])) {
//             fail(r, "ema", String.format("15M EMA9=%.6f EMA21=%.6f against %s", e[0], e[1], isLong ? "LONG" : "SHORT"));
//             return;
//         }
//         // 5M: closed candles + the developing 5M candle (high/low from the forming candle + price, close = price)
//         long cur5 = alignedStart(nowMs, FIVE_MIN_MS, 0L);
//         int end = 0;
//         while (end < s.n && s.t[end] < cur5) end++;
//         int need = SWING_LOOKBACK_5M + 2 * SWING_PIVOT_BARS + 6;
//         int from = Math.max(0, end - need);
//         int m = end - from + 1;
//         double[] hi = new double[m], lo = new double[m], cl = new double[m];
//         for (int i = from; i < end; i++) { hi[i - from] = s.h[i]; lo[i - from] = s.l[i]; cl[i - from] = s.c[i]; }
//         hi[m - 1] = price; lo[m - 1] = price; cl[m - 1] = price;
//         for (int i = end; i < s.n; i++) if (s.t[i] == cur5) { hi[m - 1] = Math.max(hi[m - 1], s.h[i]); lo[m - 1] = Math.min(lo[m - 1], s.l[i]); }
//         Breakout b = findBreakout(hi, lo, cl, isLong, SWING_PIVOT_BARS, SWING_LOOKBACK_5M);
//         r.brkLevel = b.level;
//         if (!b.ok) { fail(r, "breakout", "5M breakout: " + b.reason); return; }
//         r.signal = isLong ? "BUY" : "SELL";
//         r.code = "signal";
//         r.reason = String.format("%s | 15M EMA9=%.6f EMA21=%.6f | 5M %s", summary(r), e[0], e[1], b.reason);
//     }

//     static Eval evaluateStage1(Series5 s, double price, long nowMs) {
//         Eval r = new Eval();
//         r.price = price;
//         if (!(price > 0) || Double.isInfinite(price)) { fail(r, "noPrice", "no valid price"); return r; }
//         stage1(s, price, nowMs, r);
//         return r;
//     }

//     // Both stages with the same price (live price for orders).
//     static Eval evaluateFull(Series5 s, double price, long nowMs) {
//         Eval r = evaluateStage1(s, price, nowMs);
//         if (r.dir == 0) return r;
//         stage2(s, price, nowMs, r.dir, r);
//         return r;
//     }

//     static String describeDev(Dev e) {
//         if (!e.ok) return e.tf + " INVALID (" + e.reason + ")";
//         return String.format("%s open=%.6f price=%.6f candle=%s supertrend=%s (%d bars)",
//                 e.tf, e.open, e.price, colName(e.colour), stName(e.stBull), e.stBars);
//     }

//     private static void logEval(String pair, Eval ev) {
//         for (Dev d : ev.dev) if (d != null) System.out.println("  [ST] " + pair + " " + describeDev(d));
//         if (ev.ema9 > 0) System.out.printf("  [ST] %s 15M EMA9=%.6f EMA21=%.6f 5M swing level=%.6f%n", pair, ev.ema9, ev.ema21, ev.brkLevel);
//         System.out.println("  [ST] " + pair + " DECISION=" + (ev.signal.equals("BUY") ? "LONG" : ev.signal.equals("SELL") ? "SHORT" : "NO TRADE")
//                 + " | " + ev.reason);
//     }

//     static String ctxOf(Eval ev) {
//         StringBuilder sb = new StringBuilder();
//         for (Dev d : ev.dev) {
//             if (d == null || !d.ok) continue;
//             if (sb.length() > 0) sb.append(' ');
//             sb.append(d.tf).append(":ST=").append(d.stBull ? "G" : "R").append("/C=").append(d.colour > 0 ? "G" : d.colour < 0 ? "R" : "D");
//         }
//         sb.append(String.format(" EMA9=%.6f EMA21=%.6f BRK=%.6f", ev.ema9, ev.ema21, ev.brkLevel));
//         return sb.toString();
//     }

//     static class Revalidation {
//         String reason;      // null = still valid
//         double price;
//     }

//     // Fresh 5M candles + fresh live price -> full re-evaluation (stage 1 and stage 2, same AND rules).
//     private static Revalidation revalidateBeforeOrder(String pair, boolean isLong) {
//         Revalidation rv = new Revalidation();
//         long now = System.currentTimeMillis();
//         Series5 s = getSeries(pair, now);
//         rv.price = getLastPrice(pair);
//         Eval ev;
//         if (s == null) { ev = new Eval(); fail(ev, "noData", "no fresh 5M data"); }
//         else ev = evaluateFull(s, rv.price, System.currentTimeMillis());
//         String want = isLong ? "BUY" : "SELL";
//         rv.reason = ev.signal.equals(want) ? null
//                 : "pre-order revalidation: now " + ev.signal + " - " + ev.reason;
//         System.out.println("  [REVALIDATE] " + pair + " " + want + " price=" + rv.price + " | -> "
//                 + (rv.reason == null ? "PASS (order allowed)" : "FAIL (no order): " + rv.reason));
//         if (rv.reason != null) logEval(pair, ev);
//         return rv;
//     }

//     // =========================================================================
//     // 5M swing breakout helper
//     // =========================================================================
//     static class Breakout {
//         boolean ok;
//         double level;
//         int pivotIdx = -1;
//         String reason = "";
//     }

//     // hi/lo/cl oldest first; the last element is the signal (developing) candle.
//     // A swing high/low is "confirmed" when `pivotBars` candles on each side are strictly lower highs / higher lows.
//     static Breakout findBreakout(double[] hi, double[] lo, double[] cl, boolean isLong, int pivotBars, int lookback) {
//         Breakout b = new Breakout();
//         int n = cl.length;
//         if (n < pivotBars * 2 + 4) { b.reason = "not enough 5M candles"; return b; }
//         int maxPivot = n - 2 - pivotBars;                    // right-side candles must all be before the signal candle
//         int minPivot = Math.max(pivotBars, n - 1 - lookback);
//         int found = -1;
//         for (int i = maxPivot; i >= minPivot && found < 0; i--) {
//             boolean pivot = true;
//             for (int k = 1; k <= pivotBars && pivot; k++) {
//                 if (isLong) { if (hi[i] <= hi[i - k] || hi[i] <= hi[i + k]) pivot = false; }
//                 else        { if (lo[i] >= lo[i - k] || lo[i] >= lo[i + k]) pivot = false; }
//             }
//             if (pivot) found = i;
//         }
//         if (found < 0) {
//             b.reason = "no confirmed swing " + (isLong ? "high" : "low") + " in last " + lookback + " 5M candles";
//             return b;
//         }
//         double level = isLong ? hi[found] : lo[found];
//         b.level = level;
//         b.pivotIdx = found;
//         for (int j = found + 1; j <= n - 2; j++) {
//             if (isLong ? cl[j] > level : cl[j] < level) {
//                 b.reason = String.format("swing %s %.6f was already broken earlier - not a fresh breakout", isLong ? "high" : "low", level);
//                 return b;
//             }
//         }
//         double last = cl[n - 1];
//         b.ok = isLong ? last > level : last < level;
//         b.reason = String.format("swing %s=%.6f livePrice=%.6f", isLong ? "high" : "low", level, last);
//         return b;
//     }

//     // One signal per symbol+direction per developing 15M candle. Returns false if already signalled.
//     static boolean registerSignal(Map<String, Long> registry, String key, long candleOpen) {
//         Long prev = registry.get(key);
//         if (prev != null && prev == candleOpen) return false;
//         registry.put(key, candleOpen);
//         return true;
//     }

//     // =========================================================================
//     // Fixed SL / TP from the ACTUAL average entry price
//     // =========================================================================
//     static class SlTp {
//         boolean ok;
//         double sl, tp, risk;
//         String reason = "";
//     }

//     static SlTp computeFixedSlTp(boolean isLong, double entry, double tick) {
//         SlTp r = new SlTp();
//         if (entry <= 0 || Double.isNaN(entry)) { r.reason = "invalid entry price"; return r; }
//         double slRaw = isLong ? entry * (1 - FIXED_SL_PERCENT / 100.0) : entry * (1 + FIXED_SL_PERCENT / 100.0);
//         double tpRaw = isLong ? entry * (1 + FIXED_TP_PERCENT / 100.0) : entry * (1 - FIXED_TP_PERCENT / 100.0);
//         double sl = roundToTick(slRaw, tick), tp = roundToTick(tpRaw, tick);
//         if (isLong ? (sl >= entry || tp <= entry) : (sl <= entry || tp >= entry)) {
//             r.reason = "SL/TP collapsed onto entry after tick rounding";
//             return r;
//         }
//         r.ok = true; r.sl = sl; r.tp = tp; r.risk = Math.abs(entry - sl);
//         return r;
//     }

//     // =========================================================================
//     // Dynamic trailing — pure calculation (no I/O, unit-tested in --selftest)   [UNCHANGED]
//     // =========================================================================
//     static class TrailStep {
//         boolean active;          // trailing active after this evaluation
//         boolean justActivated;   // activation level reached in this evaluation
//         double  activationRef;   // trail reference right after activation (= activation price level)
//         boolean move;            // true -> SL and TP should be moved to newSl / newTp
//         double  shift;           // price distance both levels move by
//         double  newSl, newTp, newRef;
//         String  reason = "";
//     }

//     // extreme = highest price since entry (LONG) / lowest price since entry (SHORT).
//     // ref     = last trail point (only meaningful when active == true).
//     // SL and TP are shifted by the SAME price difference, so the SL<->TP gap never changes; never backward.
//     static TrailStep computeTrail(boolean isLong, double entry, double sl, double tp, double extreme,
//                                   boolean active, double ref, double tick) {
//         TrailStep r = new TrailStep();
//         r.active = active; r.newSl = sl; r.newTp = tp; r.newRef = ref;
//         if (!(entry > 0) || !(sl > 0) || !(tp > 0) || !(extreme > 0)) { r.reason = "invalid inputs"; return r; }
//         double act = isLong ? entry * (1 + TRAIL_ACTIVATION_PERCENT / 100.0) : entry * (1 - TRAIL_ACTIVATION_PERCENT / 100.0);
//         double tol = entry * 1e-9;
//         if (!r.active) {
//             boolean reached = isLong ? extreme >= act - tol : extreme <= act + tol;
//             if (!reached) { r.reason = String.format("activation not reached (need %.6f, best %.6f)", act, extreme); return r; }
//             r.active = true; r.justActivated = true; r.activationRef = act;
//             ref = act; r.newRef = act;
//         }
//         double delta = isLong ? extreme - ref : ref - extreme;     // favourable distance beyond the last trail point
//         if (delta < ref * TRAIL_STEP_PERCENT / 100.0 - tol) { r.reason = "step not reached"; return r; }
//         double shift = tick > 0 ? Math.floor(delta / tick + 1e-9) * tick : delta;   // whole ticks only
//         if (!(shift > 0)) { r.reason = "shift rounds to 0 ticks"; return r; }
//         double newSl = roundToTick(isLong ? sl + shift : sl - shift, tick);
//         double newTp = roundToTick(isLong ? tp + shift : tp - shift, tick);
//         if (isLong ? (newSl <= sl || newTp <= tp) : (newSl >= sl || newTp >= tp)) { r.reason = "would not move in the favourable direction"; return r; }
//         r.move = true; r.shift = shift; r.newSl = newSl; r.newTp = newTp;
//         r.newRef = isLong ? ref + shift : ref - shift;
//         r.reason = "step reached";
//         return r;
//     }

//     // =========================================================================
//     // Break-even + Profit Lock — pure calculation (no I/O)   [UNCHANGED]
//     // =========================================================================
//     static class BeStep {
//         boolean reached;         // trigger level reached by the favourable extreme
//         boolean move;            // true -> SL should be moved to newSl (TP unchanged)
//         double  trigger, lockSl, newSl;
//         String  reason = "";
//     }

//     // extreme = highest price since entry (LONG) / lowest price since entry (SHORT).
//     // SL is only ever moved in the profitable direction (LONG: up, SHORT: down); never backwards.
//     static BeStep computeBreakeven(boolean isLong, double entry, double sl, double extreme, double tick) {
//         BeStep r = new BeStep();
//         r.newSl = sl;
//         if (!(entry > 0) || !(sl > 0) || !(extreme > 0)) { r.reason = "invalid inputs"; return r; }
//         r.trigger = isLong ? entry * (1 + BREAKEVEN_TRIGGER_PERCENT / 100.0) : entry * (1 - BREAKEVEN_TRIGGER_PERCENT / 100.0);
//         double lockRaw = isLong ? entry * (1 + PROFIT_LOCK_PERCENT / 100.0) : entry * (1 - PROFIT_LOCK_PERCENT / 100.0);
//         r.lockSl = roundToTick(lockRaw, tick);
//         double tol = entry * 1e-9;
//         r.reached = isLong ? extreme >= r.trigger - tol : extreme <= r.trigger + tol;
//         if (!r.reached) { r.reason = String.format("break-even trigger not reached (need %.6f, best %.6f)", r.trigger, extreme); return r; }
//         boolean better = isLong ? r.lockSl > sl : r.lockSl < sl;
//         if (!better || !priceDiffers(r.lockSl, sl, tick)) { r.reason = "SL already at or beyond the lock level"; return r; }
//         r.move = true; r.newSl = r.lockSl; r.reason = "move SL to lock level";
//         return r;
//     }

//     // true when two prices differ by more than half a tick (exchange values are compared with this)
//     static boolean priceDiffers(double a, double b, double tick) {
//         return Math.abs(a - b) > Math.max(tick * 0.5, 1e-12);
//     }

//     // =========================================================================
//     // Trade state persistence (survives restarts)
//     // =========================================================================
//     static JSONObject tradeToJson(TradeInfo t) {
//         JSONObject j = new JSONObject();
//         j.put("isLong", t.isLong);
//         j.put("entry", t.entry);
//         j.put("sl", t.sl);
//         j.put("tp", t.tp);
//         j.put("initialSl", t.initialSl);
//         j.put("risk", t.risk);
//         j.put("maxPrice", t.maxPrice);
//         j.put("minPrice", t.minPrice);
//         j.put("openedMs", t.openedMs);
//         j.put("ctx", t.ctx == null ? "" : t.ctx);
//         j.put("trailActive", t.trailActive);
//         j.put("trailRef", t.trailRef);
//         j.put("lastTrailMs", t.lastTrailMs);
//         j.put("beDone", t.beDone);
//         return j;
//     }

//     static TradeInfo tradeFromJson(JSONObject j) {
//         TradeInfo t = new TradeInfo();
//         t.isLong = j.optBoolean("isLong", true);
//         t.entry = j.optDouble("entry", 0);
//         t.sl = j.optDouble("sl", 0);
//         t.tp = j.optDouble("tp", 0);
//         t.initialSl = j.optDouble("initialSl", t.sl);
//         t.risk = j.optDouble("risk", 0);
//         t.maxPrice = j.optDouble("maxPrice", t.entry);
//         t.minPrice = j.optDouble("minPrice", t.entry);
//         t.openedMs = j.optLong("openedMs", 0);
//         t.ctx = j.optString("ctx", "");
//         t.trailActive = j.optBoolean("trailActive", false);
//         t.trailRef = j.optDouble("trailRef", 0);
//         t.lastTrailMs = j.optLong("lastTrailMs", 0);
//         t.beDone = j.optBoolean("beDone", false);
//         return t;
//     }

//     // Atomic write (temp file + rename) so a crash in the middle never leaves a half-written state file.
//     private static synchronized void saveTradeState() {
//         try {
//             JSONObject root = new JSONObject();
//             for (Map.Entry<String, TradeInfo> en : tradeState.entrySet()) root.put(en.getKey(), tradeToJson(en.getValue()));
//             File target = new File(TRADE_STATE_FILE);
//             File tmp = new File(TRADE_STATE_FILE + ".tmp");
//             Files.write(tmp.toPath(), root.toString(2).getBytes(StandardCharsets.UTF_8));
//             try {
//                 Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
//             } catch (Exception atomicNotSupported) {
//                 Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
//             }
//         } catch (Exception e) {
//             System.err.println("saveTradeState failed: " + e.getMessage());
//         }
//     }

//     private static void loadTradeState() {
//         File f = new File(TRADE_STATE_FILE);
//         if (!f.exists()) { System.out.println("[STATE] no saved trade state (" + TRADE_STATE_FILE + ") — starting fresh"); return; }
//         try {
//             JSONObject root = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
//             for (String pair : root.keySet()) {
//                 TradeInfo t = tradeFromJson(root.getJSONObject(pair));
//                 if (t.entry > 0) tradeState.put(pair, t);
//             }
//             System.out.println("[STATE] restored " + tradeState.size() + " trade(s) from " + TRADE_STATE_FILE + ": " + tradeState.keySet());
//         } catch (Exception e) {
//             System.err.println("[STATE] could not read " + TRADE_STATE_FILE + " (" + e.getMessage()
//                     + ") — backing it up; open positions will be rebuilt from the exchange");
//             try { Files.move(f.toPath(), new File(TRADE_STATE_FILE + ".corrupt").toPath(), StandardCopyOption.REPLACE_EXISTING); }
//             catch (Exception ignored) {}
//         }
//     }

//     // =========================================================================
//     // Quantity: precision, minimum quantity / notional validation
//     // =========================================================================
//     static class QtyResult {
//         double qty;
//         String reason = "";
//     }

//     static QtyResult sizeQuantity(double rawQty, double price, boolean integerOnly, JSONObject inst) {
//         QtyResult r = new QtyResult();
//         double inc = inst == null ? 0 : inst.optDouble("quantity_increment", 0);
//         double qty;
//         if (inc > 0) {
//             BigDecimal bi = BigDecimal.valueOf(inc);
//             qty = BigDecimal.valueOf(rawQty).divide(bi, 0, RoundingMode.FLOOR).multiply(bi).doubleValue();
//         } else {
//             qty = integerOnly ? Math.floor(rawQty) : Math.floor(rawQty * 100) / 100.0;
//         }
//         double minQty = inst == null ? 0 : inst.optDouble("min_quantity", 0);
//         double minNotional = inst == null ? 0 : inst.optDouble("min_notional", 0);
//         double maxQty = inst == null ? 0 : inst.optDouble("max_quantity", 0);
//         if (qty <= 0) r.reason = "quantity rounds to 0";
//         else if (minQty > 0 && qty < minQty) r.reason = String.format("quantity %.6f below min quantity %.6f", qty, minQty);
//         else if (minNotional > 0 && qty * price < minNotional) r.reason = String.format("notional %.4f below min notional %.4f", qty * price, minNotional);
//         else if (maxQty > 0 && qty > maxQty) r.reason = String.format("quantity %.6f above max quantity %.6f", qty, maxQty);
//         else r.qty = qty;
//         return r;
//     }

//     static boolean isPartialFill(double requested, double filled) {
//         return filled > 0 && filled < requested * 0.999;
//     }

//     static boolean isErrorResponse(String resp) {
//         if (resp == null || resp.trim().isEmpty()) return true;
//         String l = resp.toLowerCase();
//         return l.contains("err_code_dcx") || l.contains("\"status\":\"error\"") || l.contains("\"code\":4")
//                 || l.contains("\"code\":5") || l.startsWith("<");
//     }

//     // =========================================================================
//     // Main loop — one cycle per minute
//     // =========================================================================
//     public static void main(String[] args) {
//         if (args.length > 0 && "--selftest".equals(args[0])) {
//             System.exit(selfTest() ? 0 : 1);
//             return;
//         }
//         System.out.println("=== Bot starting | MODE=" + (LIVE_TRADING_ENABLED ? "LIVE" : "PAPER (live trading disabled)")
//                 + " | SUPERTREND(1H/2H/3H/4H) + 1H/2H candle -> 5M breakout + 15M EMA9/21 + 3H/4H candle, ALL ON DEVELOPING CANDLES"
//                 + " | Supertrend side=" + (ST_CONTRARIAN ? "CONTRARIAN (LONG needs RED)" : "TREND (LONG needs GREEN)")
//                 + " | SL=" + FIXED_SL_PERCENT + "% TP=" + FIXED_TP_PERCENT + "% | liquid list=" + USE_LIQUID_LIST
//                 + " (" + COINS_TO_TRADE.length + " symbols) ===");
//         System.out.println("=== Break-even + profit lock: " + (BREAKEVEN_ENABLED
//                 ? "ENABLED (trigger " + BREAKEVEN_TRIGGER_PERCENT + "%, SL locked at entry +/- " + PROFIT_LOCK_PERCENT + "%)"
//                 : "DISABLED") + (LIVE_TRADING_ENABLED ? "" : " — not applied in PAPER mode") + " ===");
//         if (BREAKEVEN_ENABLED && BREAKEVEN_TRIGGER_PERCENT <= PROFIT_LOCK_PERCENT)
//             System.err.println("WARNING: BREAKEVEN_TRIGGER_PERCENT should be greater than PROFIT_LOCK_PERCENT, otherwise the lock level can never be placed");
//         System.out.println("=== Trailing SL/TP: " + (TRAILING_ENABLED
//                 ? "ENABLED (activation " + TRAIL_ACTIVATION_PERCENT + "%, step " + TRAIL_STEP_PERCENT + "%, check every " + (TRAIL_CHECK_INTERVAL_MS / 1000) + "s)"
//                 : "DISABLED (fixed SL/TP only)") + (LIVE_TRADING_ENABLED ? "" : " — not applied in PAPER mode") + " ===");
//         if (LIVE_TRADING_ENABLED && (API_KEY == null || API_SECRET == null)) {
//             System.err.println("FATAL: LIVE_TRADING_ENABLED but DELTA_API_KEY / DELTA_API_SECRET are not set");
//             return;
//         }
//         initInstrumentCache();
//         if (LIVE_TRADING_ENABLED) loadTradeState();

//         while (true) {
//             try {
//                 runCycle();
//             } catch (Throwable t) {
//                 System.err.println("[MAIN-LOOP] Uncaught error, continuing: " + t.getMessage());
//                 t.printStackTrace();
//             }
//             try {
//                 long now = System.currentTimeMillis();
//                 long next = (now / SCAN_STEP_MS + 1) * SCAN_STEP_MS + SCAN_DELAY_MS;
//                 TimeUnit.MILLISECONDS.sleep(Math.max(1000L, next - now));
//             } catch (InterruptedException ignored) {
//                 Thread.currentThread().interrupt();
//                 break;
//             }
//         }
//     }

//     private static void runCycle() {
//         Set<String> active;
//         if (LIVE_TRADING_ENABLED) {
//             active = getActivePositions();
//             if (active == null) {
//                 System.err.println("Positions API failed — skipping this cycle (no entries, no cleanup).");
//                 return;
//             }
//             System.out.println("Active positions: " + active);
//             handleClosedTrades(active);
//             runTrailingPass();
//         } else {
//             active = new HashSet<>();
//             updatePaperTrades();
//         }

//         if (active.size() + paperTrades.size() >= MAX_OPEN_POSITIONS) {
//             System.out.println("MAX_OPEN_POSITIONS reached — skipping entries.");
//         } else {
//             runEntryScan(active);
//         }
//         if (LIVE_TRADING_ENABLED) ensureTpSlForOpenPositions();  // ALWAYS runs — protection never depends on entry filters
//     }

//     private static void reject(Map<String, Integer> counts, String reason) { counts.merge(reason, 1, Integer::sum); }

//     private static void runEntryScan(Set<String> active) {
//         Map<String, Integer> rej = new TreeMap<>();
//         int stage2 = 0, signals = 0;

//         for (String pair : COINS_TO_TRADE) {
//             try {
//                 // The scan of hundreds of symbols can take longer than the trailing interval, so trailing is also
//                 // checked from inside the scan loop (same thread -> no concurrent order/TP-SL calls).
//                 if (LIVE_TRADING_ENABLED && System.currentTimeMillis() - lastTrailPassMs >= TRAIL_CHECK_INTERVAL_MS) runTrailingPass();

//                 if (active.size() + paperTrades.size() >= MAX_OPEN_POSITIONS) break;
//                 if (active.contains(pair) || paperTrades.containsKey(pair)) continue;   // no duplicate / conflicting position per symbol
//                 if (System.currentTimeMillis() - lastTradeTime.getOrDefault(pair, 0L) < COOLDOWN_MS) continue;

//                 // ---- data: hourly-cached history + ONE small 5M request ----
//                 Series5 s = getSeries(pair, System.currentTimeMillis());
//                 if (s == null) { reject(rej, "noData"); continue; }

//                 // ---- STAGE 1: Supertrend 1H-4H + developing 1H/2H colour, with the newest 5M close as the price ----
//                 long now0 = System.currentTimeMillis();
//                 Eval quick = evaluateStage1(s, s.c[s.n - 1], now0);
//                 if (quick.dir == 0) { reject(rej, quick.code); continue; }

//                 // ---- STAGE 2: all conditions again with the real live price ----
//                 stage2++;
//                 Eval ev = evaluateFull(s, getLastPrice(pair), System.currentTimeMillis());
//                 if (ev.signal.equals("NO TRADE")) {
//                     reject(rej, "live_" + ev.code);
//                     if (LOG_NO_TRADE_DETAIL) logEval(pair, ev);
//                     continue;
//                 }

//                 boolean isLong = ev.signal.equals("BUY");
//                 String sigKey = pair + "|" + (isLong ? "LONG" : "SHORT");
//                 long start15 = alignedStart(System.currentTimeMillis(), FIFTEEN_MIN_MS, HTF_ALIGN_OFFSET_MS);
//                 if (!registerSignal(lastSignalCandle, sigKey, start15)) { reject(rej, "duplicateSignal"); continue; }
//                 signals++;
//                 logEval(pair, ev);
//                 handleSignal(pair, isLong, ctxOf(ev), active);
//             } catch (Exception ex) {
//                 System.err.println("Error on " + pair + ": " + ex.getMessage());
//             }
//         }
//         System.out.println("=== Scan complete | mode=" + (LIVE_TRADING_ENABLED ? "LIVE" : "PAPER") + " stage2=" + stage2
//                 + " signals=" + signals + " | rejections=" + rej + " ===");
//     }

//     private static void handleSignal(String pair, boolean isLong, String ctx, Set<String> active) {
//         if (LIVE_TRADING_ENABLED) {
//             executeEntry(pair, isLong, ctx, active);   // runs the immediate pre-order revalidation itself
//             return;
//         }
//         Revalidation rv = revalidateBeforeOrder(pair, isLong);
//         if (rv.reason != null) {
//             System.out.println("SIGNAL CANCELLED - " + pair + " [" + (isLong ? "LONG" : "SHORT") + "] - " + rv.reason);
//             return;
//         }
//         paperEntry(pair, isLong, rv.price, ctx);
//     }

//     // =========================================================================
//     // PAPER trading: simulated entry at the signal price, SL/TP resolved from 1M candles (fixed SL/TP, no trailing)
//     // =========================================================================
//     private static void paperEntry(String pair, boolean isLong, double price, String ctx) {
//         SlTp s = computeFixedSlTp(isLong, price, getTickSize(pair));
//         if (!s.ok) { System.out.println("[PAPER] SKIP - " + pair + " - " + s.reason); return; }
//         PaperTrade p = new PaperTrade();
//         p.isLong = isLong; p.entry = price; p.sl = s.sl; p.tp = s.tp;
//         p.openedMs = System.currentTimeMillis(); p.ctx = ctx;
//         paperTrades.put(pair, p);
//         lastTradeTime.put(pair, p.openedMs);
//         System.out.printf("[PAPER-ENTRY] %s %s entry=%.6f SL=%.6f TP=%.6f (no real order sent)%n", pair, isLong ? "LONG" : "SHORT", price, s.sl, s.tp);
//     }

//     private static void updatePaperTrades() {
//         if (paperTrades.isEmpty()) return;
//         long now = System.currentTimeMillis();
//         for (Iterator<Map.Entry<String, PaperTrade>> it = paperTrades.entrySet().iterator(); it.hasNext();) {
//             Map.Entry<String, PaperTrade> en = it.next();
//             String pair = en.getKey();
//             PaperTrade p = en.getValue();
//             try {
//                 long firstFullMin = (p.openedMs / MIN_MS + 1) * MIN_MS;        // first 1M candle that opens after the entry
//                 int minutes = (int) Math.max(3, (now - firstFullMin) / MIN_MS + 3);
//                 JSONArray closed = dropLastIfForming(sortByTime(fetchCandlesPaged(pair, "1", 1, minutes)));
//                 String result = null;
//                 double exitPx = 0;
//                 for (int i = 0; closed != null && i < closed.length(); i++) {
//                     JSONObject c = closed.getJSONObject(i);
//                     if (candleTimeMs(c) < firstFullMin) continue;
//                     double hi = c.getDouble("high"), lo = c.getDouble("low");
//                     boolean slHit = p.isLong ? lo <= p.sl : hi >= p.sl;
//                     boolean tpHit = p.isLong ? hi >= p.tp : lo <= p.tp;
//                     if (slHit) { result = "SL"; exitPx = p.sl; break; }        // SL first when both touch in one candle (conservative)
//                     if (tpHit) { result = "TP"; exitPx = p.tp; break; }
//                 }
//                 if (result == null && now - p.openedMs > PAPER_MAX_HOLD_MS) {
//                     double px = getLastPrice(pair);
//                     result = "TIMEOUT";
//                     exitPx = px > 0 ? px : p.entry;
//                 }
//                 if (result != null) {
//                     logPaperClosed(pair, p, result, exitPx);
//                     it.remove();
//                 }
//             } catch (Exception e) {
//                 System.err.println("updatePaperTrades " + pair + ": " + e.getMessage());
//             }
//         }
//     }

//     private static synchronized void logPaperClosed(String pair, PaperTrade p, String result, double exitPx) {
//         double risk = Math.abs(p.entry - p.sl);
//         double move = p.isLong ? exitPx - p.entry : p.entry - exitPx;
//         double r = risk > 0 ? move / risk : 0;
//         long heldMin = (System.currentTimeMillis() - p.openedMs) / 60_000L;
//         System.out.printf("[PAPER-CLOSED] %s %s result=%s entry=%.6f exit=%.6f R=%.2f held=%dmin | %s%n",
//                 pair, p.isLong ? "LONG" : "SHORT", result, p.entry, exitPx, r, heldMin, p.ctx);
//         if (!LOG_TRADES_CSV) return;
//         try {
//             File f = new File(PAPER_LOG_FILE);
//             boolean fresh = !f.exists();
//             try (PrintWriter pw = new PrintWriter(new FileWriter(f, true))) {
//                 if (fresh) pw.println("closed_utc,pair,direction,result,entry,sl,tp,exit_price,r_multiple,held_min,context");
//                 pw.printf("%s,%s,%s,%s,%.8f,%.8f,%.8f,%.8f,%.2f,%d,\"%s\"%n", Instant.now(), pair,
//                         p.isLong ? "LONG" : "SHORT", result, p.entry, p.sl, p.tp, exitPx, r, heldMin, p.ctx);
//             }
//         } catch (Exception e) {
//             System.err.println("paper log write failed: " + e.getMessage());
//         }
//     }

//     // =========================================================================
//     // LIVE order execution — unchanged except the revalidation call at the top and state persistence at the end
//     // =========================================================================
//     private static boolean executeEntry(String pair, boolean isLong, String ctx, Set<String> active) {
//         try {
//             // Immediate pre-order revalidation on FRESH candles + the latest price (stage 1 + stage 2, all AND rules).
//             Revalidation rv = revalidateBeforeOrder(pair, isLong);
//             double currentPrice = rv.price;
//             if (rv.reason != null) {
//                 System.out.println("SIGNAL CANCELLED - " + pair + " - " + rv.reason);
//                 return false;
//             }
//             double tickSize = getTickSize(pair);
//             SlTp pre = computeFixedSlTp(isLong, currentPrice, tickSize);
//             if (!pre.ok) {
//                 System.out.println("SKIP - " + pair + " - " + pre.reason);
//                 return false;
//             }
//             double rawQty = FIXED_MARGIN / (currentPrice * USDT_INR_RATE);
//             QtyResult q = sizeQuantity(rawQty, currentPrice, INTEGER_QTY_PAIRS.contains(pair), instrumentCache.get(pair));
//             if (q.qty <= 0) {
//                 System.out.println("SKIP - " + pair + " - " + q.reason);
//                 return false;
//             }
//             double qty = q.qty;

//             System.out.println("\n==== " + pair + " — " + (isLong ? "LONG" : "SHORT") + " (Supertrend + EMA + breakout, developing candles) ====");
//             System.out.printf("  Price=%.6f preSL=%.6f preTP=%.6f | Qty=%.6f Lev=%dx%n", currentPrice, pre.sl, pre.tp, qty, LEVERAGE);

//             String side = isLong ? "buy" : "sell";
//             JSONObject orderResp = placeFuturesOrder(side, pair, qty, LEVERAGE,
//                     "email_notification", "isolated", "INR", currentPrice);
//             if (orderResp == null || !orderResp.has("id")) {
//                 System.out.println("  Order failed/rejected: " + orderResp);
//                 return false;
//             }
//             String orderId = orderResp.getString("id");
//             System.out.println("  Order placed! id=" + orderId);
//             lastTradeTime.put(pair, System.currentTimeMillis());

//             double entry = getEntryPrice(pair, orderId);
//             if (entry <= 0) {
//                 System.out.println("  Entry not confirmed within window — cancelling unfilled limit order " + orderId);
//                 boolean cancelled = cancelOrder(orderId);
//                 sleepMs(CANCEL_VERIFY_DELAY_MS);
//                 JSONObject posAfter = findPosition(pair);
//                 double avg = posAfter == null ? 0 : posAfter.optDouble("avg_price", 0);
//                 if (avg <= 0) {
//                     if (cancelled) {
//                         System.out.println("  Unfilled order cancelled — no position opened for " + pair);
//                         lastTradeTime.remove(pair);
//                     } else {
//                         System.out.println("  WARNING: cancel of order " + orderId + " for " + pair
//                                 + " NOT confirmed — it may still be LIVE on the exchange, check manually");
//                     }
//                     return false;
//                 }
//                 System.out.println("  Order filled before the cancel took effect — continuing with fill price " + avg);
//             }

//             JSONObject pos = findPosition(pair);
//             double filledQty = pos == null ? 0 : Math.abs(pos.optDouble("active_pos", 0));
//             if (isPartialFill(qty, filledQty)) {
//                 System.out.printf("  PARTIAL FILL: requested=%.6f filled=%.6f — cancelling remainder%n", qty, filledQty);
//                 cancelOrder(orderId);
//                 sleepMs(CANCEL_VERIFY_DELAY_MS);
//                 pos = findPosition(pair);
//                 filledQty = pos == null ? 0 : Math.abs(pos.optDouble("active_pos", 0));
//             }
//             double avgEntry = pos == null ? 0 : pos.optDouble("avg_price", 0);
//             if (avgEntry <= 0) {
//                 System.out.println("  Could not read the average entry price — safety sweep will protect the position");
//                 active.add(pair);
//                 return true;
//             }
//             System.out.printf("  Entry confirmed: avg=%.6f filledQty=%.6f (requested %.6f)%n", avgEntry, filledQty, qty);

//             SlTp fin = computeFixedSlTp(isLong, avgEntry, tickSize);
//             String posId = pos.optString("id", null);
//             if (posId == null) posId = getPositionId(pair);
//             if (!fin.ok) {
//                 System.out.println("  Cannot compute SL/TP from the fill price (" + fin.reason + ") — closing the position");
//                 if (posId == null || !closePosition(posId)) System.out.println("  CRITICAL: position " + pair + " is open WITHOUT protection — close it manually");
//                 return false;
//             }
//             System.out.println("[ENTRY] " + pair + " Side=" + (isLong ? "LONG" : "SHORT"));
//             System.out.printf("  Entry=%.6f SL=%.6f TP=%.6f Risk=%.6f SL%%=%.2f TP%%=%.2f | trailing=%s%n",
//                     avgEntry, fin.sl, fin.tp, fin.risk, FIXED_SL_PERCENT, FIXED_TP_PERCENT,
//                     TRAILING_ENABLED ? "armed (activates at " + TRAIL_ACTIVATION_PERCENT + "% profit)" : "disabled");

//             if (posId == null) {
//                 System.out.println("  Position ID not found after retries — safety sweep will handle it");
//             } else if (!setTpSlWithRetry(posId, fin.tp, fin.sl, pair) && !stopLossPresent(pair)) {
//                 System.out.println("  SL protection could NOT be established — closing the position immediately");
//                 if (!closePosition(posId)) System.out.println("  CRITICAL: position " + pair + " is open WITHOUT stop loss — close it manually");
//                 return false;
//             }
//             TradeInfo trade = new TradeInfo();
//             trade.isLong = isLong; trade.entry = avgEntry; trade.sl = fin.sl; trade.tp = fin.tp;
//             trade.initialSl = fin.sl; trade.risk = fin.risk;
//             trade.maxPrice = avgEntry; trade.minPrice = avgEntry;
//             trade.openedMs = System.currentTimeMillis();
//             trade.ctx = ctx;
//             tradeState.put(pair, trade);
//             saveTradeState();
//             active.add(pair);
//             return true;
//         } catch (Exception e) {
//             System.err.println("executeEntry " + pair + ": " + e.getMessage());
//             return false;
//         }
//     }

//     private static boolean cancelOrder(String orderId) {
//         for (int attempt = 1; attempt <= CANCEL_MAX_RETRIES; attempt++) {
//             try {
//                 JSONObject body = new JSONObject();
//                 body.put("timestamp", Instant.now().toEpochMilli());
//                 body.put("id", orderId);
//                 String resp = authPost(BASE_URL + "/exchange/v1/derivatives/futures/orders/cancel", body.toString());
//                 System.out.println("  Cancel response (attempt " + attempt + "/" + CANCEL_MAX_RETRIES + "): " + resp);
//                 if (!isErrorResponse(resp)) return true;
//             } catch (Exception e) {
//                 System.err.println("cancelOrder attempt " + attempt + ": " + e.getMessage());
//             }
//             sleepMs(CANCEL_RETRY_DELAY_MS);
//         }
//         return false;
//     }

//     private static boolean closePosition(String posId) {
//         try {
//             JSONObject body = new JSONObject();
//             body.put("timestamp", Instant.now().toEpochMilli());
//             body.put("id", posId);
//             String resp = authPost(BASE_URL + "/exchange/v1/derivatives/futures/positions/exit", body.toString());
//             System.out.println("  Emergency close response: " + resp);
//             return !isErrorResponse(resp);
//         } catch (Exception e) {
//             System.err.println("closePosition: " + e.getMessage());
//             return false;
//         }
//     }

//     private static boolean stopLossPresent(String pair) {
//         try {
//             JSONObject p = findPosition(pair);
//             return p != null && p.optDouble("stop_loss_trigger", 0) > 0;
//         } catch (Exception e) {
//             return false;
//         }
//     }

//     // =========================================================================
//     // Closed-trade logging (approximate exit reason + MFE/MAE in R) -> console + CSV
//     // =========================================================================
//     private static void handleClosedTrades(Set<String> active) {
//         boolean removed = false;
//         for (Iterator<Map.Entry<String, TradeInfo>> it = tradeState.entrySet().iterator(); it.hasNext();) {
//             Map.Entry<String, TradeInfo> en = it.next();
//             if (active.contains(en.getKey())) continue;
//             double px = getLastPrice(en.getKey());
//             logClosedTrade(en.getKey(), en.getValue(), px);
//             it.remove();
//             removed = true;
//         }
//         if (removed) saveTradeState();     // closed positions are dropped from the persisted state too
//     }

//     static String classifyExit(TradeInfo t, double lastPx) {
//         if (lastPx <= 0 || t.risk <= 0) return "UNKNOWN";
//         double dTp = Math.abs(lastPx - t.tp), dSl = Math.abs(lastPx - t.sl);
//         if (Math.min(dTp, dSl) > 0.35 * t.risk) return "OTHER(manual/liquidation?)";
//         return dTp < dSl ? "TP(approx)" : "SL(approx)";
//     }

//     static double[] mfeMaeR(TradeInfo t) {
//         if (t.risk <= 0) return new double[]{0, 0};
//         double mfe = t.isLong ? (t.maxPrice - t.entry) : (t.entry - t.minPrice);
//         double mae = t.isLong ? (t.entry - t.minPrice) : (t.maxPrice - t.entry);
//         return new double[]{Math.max(0, mfe) / t.risk, Math.max(0, mae) / t.risk};
//     }

//     private static synchronized void logClosedTrade(String pair, TradeInfo t, double lastPx) {
//         String exit = classifyExit(t, lastPx);
//         double[] m = mfeMaeR(t);
//         long heldMin = t.openedMs > 0 ? (System.currentTimeMillis() - t.openedMs) / 60_000L : -1;
//         System.out.printf("[CLOSED] %s %s exit=%s entry=%.6f initialSl=%.6f finalSl=%.6f finalTp=%.6f lastPx=%.6f held=%dmin trailing=%s MFE=%.2fR MAE=%.2fR | %s%n",
//                 pair, t.isLong ? "LONG" : "SHORT", exit, t.entry, t.initialSl, t.sl, t.tp, lastPx, heldMin,
//                 t.trailActive ? "was active" : "never activated", m[0], m[1], t.ctx);
//         if (!LOG_TRADES_CSV) return;
//         try {
//             File f = new File(TRADE_LOG_FILE);
//             boolean fresh = !f.exists();
//             try (PrintWriter pw = new PrintWriter(new FileWriter(f, true))) {
//                 if (fresh) pw.println("closed_utc,pair,direction,exit_approx,entry,initial_sl,final_sl,tp,last_price,held_min,mfe_r,mae_r,context");
//                 pw.printf("%s,%s,%s,%s,%.8f,%.8f,%.8f,%.8f,%.8f,%d,%.2f,%.2f,\"%s\"%n",
//                         Instant.now(), pair, t.isLong ? "LONG" : "SHORT", exit, t.entry, t.initialSl, t.sl, t.tp,
//                         lastPx, heldMin, m[0], m[1], t.ctx);
//             }
//         } catch (Exception e) {
//             System.err.println("trade log write failed: " + e.getMessage());
//         }
//     }

//     // =========================================================================
//     // DYNAMIC TRAILING SL / TP  (LIVE)   [UNCHANGED]
//     //   - the exchange is the source of truth for the CURRENT SL/TP (so manual edits are respected)
//     //   - SL and TP are replaced in ONE create_tpsl call (never cancelled first -> position is never unprotected)
//     //   - TP/SL attached to a position are exit-only by nature (they can only reduce/close that position)
//     // =========================================================================
//     private static long lastTrailPassMs = 0;

//     private static boolean isActivePosition(JSONObject p) {
//         return p.optDouble("active_pos", 0) > 0
//                 || p.optDouble("locked_margin", 0) > 0
//                 || p.optDouble("avg_price", 0) > 0
//                 || p.optDouble("take_profit_trigger", 0) > 0
//                 || p.optDouble("stop_loss_trigger", 0) > 0;
//     }

//     // One API call -> all open positions by pair. Null if the call fails (caller must NOT treat that as "no positions").
//     private static Map<String, JSONObject> getOpenPositionsMap() {
//         try {
//             JSONObject body = new JSONObject();
//             body.put("timestamp", Instant.now().toEpochMilli());
//             body.put("page", "1");
//             body.put("size", "100");
//             body.put("margin_currency_short_name", new String[]{"INR", "USDT"});
//             String resp = authPost(BASE_URL + "/exchange/v1/derivatives/futures/positions", body.toString());
//             JSONArray arr = resp.startsWith("[") ? new JSONArray(resp) : new JSONArray().put(new JSONObject(resp));
//             Map<String, JSONObject> m = new LinkedHashMap<>();
//             for (int i = 0; i < arr.length(); i++) {
//                 JSONObject p = arr.getJSONObject(i);
//                 String pair = p.optString("pair", "");
//                 if (!pair.isEmpty() && isActivePosition(p)) m.put(pair, p);
//             }
//             return m;
//         } catch (Exception e) {
//             System.err.println("getOpenPositionsMap: " + e.getMessage());
//             return null;
//         }
//     }

//     private static void runTrailingPass() {
//         lastTrailPassMs = System.currentTimeMillis();
//         Map<String, JSONObject> positions = getOpenPositionsMap();
//         if (positions == null) {
//             System.err.println("[TRAIL] positions API failed — trailing pass skipped (no changes made)");
//             return;
//         }
//         for (Map.Entry<String, JSONObject> en : positions.entrySet()) {
//             try {
//                 trailOne(en.getKey(), en.getValue());
//             } catch (Exception e) {
//                 System.err.println("[TRAIL] " + en.getKey() + ": " + e.getMessage());
//             }
//         }
//     }

//     private static void trailOne(String pair, JSONObject pos) {
//         double signedQty = pos.optDouble("active_pos", 0);
//         double avg = pos.optDouble("avg_price", 0);
//         if (Math.abs(signedQty) <= 0 || avg <= 0) return;           // closed / not filled yet -> nothing to trail
//         double px = getLastPrice(pair);
//         if (px <= 0) return;                                        // no price -> no decision
//         double tick = getTickSize(pair);
//         double exSl = pos.optDouble("stop_loss_trigger", 0);
//         double exTp = pos.optDouble("take_profit_trigger", 0);
//         long now = System.currentTimeMillis();
//         boolean dirty = false;

//         // ---- 1. state: reuse the persisted one, or (re)build it from the exchange ----
//         TradeInfo t = tradeState.get(pair);
//         if (t != null && t.entry > 0 && Math.abs(avg - t.entry) / t.entry * 100.0 > TRAIL_ENTRY_MISMATCH_PCT) {
//             System.out.printf("  [TRAIL] %s saved entry %.6f differs from exchange avg %.6f — treating as a NEW position, state rebuilt%n", pair, t.entry, avg);
//             t = null;
//         }
//         if (t == null) {
//             t = new TradeInfo();
//             t.isLong = signedQty >= 0;
//             t.entry = avg; t.sl = exSl; t.tp = exTp; t.initialSl = exSl;
//             t.risk = exSl > 0 ? Math.abs(avg - exSl) : 0;
//             t.maxPrice = Math.max(avg, px); t.minPrice = Math.min(avg, px);
//             t.openedMs = now; t.ctx = "recovered-from-exchange";
//             tradeState.put(pair, t);
//             dirty = true;
//         } else if (priceDiffers(avg, t.entry, tick)) {
//             System.out.printf("  [TRAIL] %s average entry changed %.6f -> %.6f (extra/partial fill) — entry updated%n", pair, t.entry, avg);
//             t.entry = avg;
//             dirty = true;
//         }

//         // ---- 2. highest / lowest price since entry ----
//         if (px > t.maxPrice) { t.maxPrice = px; dirty = true; }
//         if (px < t.minPrice) { t.minPrice = px; dirty = true; }

//         if (!TRAILING_ENABLED && !BREAKEVEN_ENABLED) { if (dirty) saveTradeState(); return; }

//         // ---- 3. exchange must hold BOTH levels (the safety sweep restores missing ones) ----
//         if (exSl <= 0 || exTp <= 0) {
//             if (now - t.lastStatusLogMs >= TRAIL_STATUS_LOG_MS) {
//                 System.out.println("  [TRAIL] " + pair + " SL or TP missing on exchange (SL=" + exSl + " TP=" + exTp + ") — trailing paused, safety sweep will restore");
//                 t.lastStatusLogMs = now;
//             }
//             if (dirty) saveTradeState();
//             return;
//         }

//         // ---- 4. manual change on the exchange -> adopt it, the NEW gap is maintained from now on ----
//         if (t.sl <= 0 || t.tp <= 0 || priceDiffers(exSl, t.sl, tick) || priceDiffers(exTp, t.tp, tick)) {
//             if (t.sl > 0 && t.tp > 0) {
//                 System.out.printf("  [TRAIL] %s SL/TP changed on the exchange (manual?): SL %.6f -> %.6f | TP %.6f -> %.6f | new gap=%.6f — adopted%n",
//                         pair, t.sl, exSl, t.tp, exTp, Math.abs(exTp - exSl));
//             }
//             t.sl = exSl; t.tp = exTp;
//             dirty = true;
//         }

//         // ---- 4b. break-even + profit lock: SL only (TP unchanged), one-time, never backwards ----
//         if (BREAKEVEN_ENABLED && !t.beDone) {
//             double beExtreme = t.isLong ? t.maxPrice : t.minPrice;
//             BeStep b = computeBreakeven(t.isLong, t.entry, t.sl, beExtreme, tick);
//             if (b.reached && !b.move) {
//                 t.beDone = true; dirty = true;       // SL is already better than the lock level (e.g. trailed) -> nothing to do
//                 System.out.printf("  [BREAKEVEN] %s %s trigger reached but SL=%.6f is already at/beyond lock level %.6f — no change%n",
//                         pair, t.isLong ? "LONG" : "SHORT", t.sl, b.lockSl);
//             } else if (b.reached && b.move && now >= t.nextTrailTryMs) {
//                 boolean beSane = t.isLong ? b.newSl < px : b.newSl > px;    // stop must be on the correct side of the market
//                 String beId = pos.optString("id", null);
//                 if (!beSane) {
//                     if (now - t.lastStatusLogMs >= TRAIL_STATUS_LOG_MS) {
//                         System.out.printf("  [BREAKEVEN] %s lock SL=%.6f not valid against price %.6f yet — will retry%n", pair, b.newSl, px);
//                         t.lastStatusLogMs = now;
//                     }
//                 } else if (beId == null || beId.isEmpty()) {
//                     System.out.println("  [BREAKEVEN] " + pair + " position id missing — cannot update SL");
//                 } else {
//                     double oldSl = t.sl;
//                     boolean beOk = replaceTpSl(beId, pair, t.tp, b.newSl, tick, t.tp, t.sl);
//                     if (beOk) {
//                         t.sl = b.newSl; t.beDone = true; t.nextTrailTryMs = 0; dirty = true;
//                         System.out.printf("  [BREAKEVEN-UPDATE] %s %s entry=%.6f price=%.6f best=%.6f | SL %.6f -> %.6f (lock %.2f%%) | TP %.6f unchanged%n",
//                                 pair, t.isLong ? "LONG" : "SHORT", t.entry, px, beExtreme, oldSl, t.sl, PROFIT_LOCK_PERCENT, t.tp);
//                         saveTradeState();
//                         return;                      // normal trailing continues from the next pass
//                     } else {
//                         t.nextTrailTryMs = now + TRAIL_RETRY_BACKOFF_MS;
//                         System.out.printf("  [BREAKEVEN] %s SL update FAILED — keeping SL=%.6f TP=%.6f, retry in %ds%n", pair, t.sl, t.tp, TRAIL_RETRY_BACKOFF_MS / 1000);
//                     }
//                 }
//             }
//         }
//         if (!TRAILING_ENABLED) { if (dirty) saveTradeState(); return; }

//         // ---- 5. trailing decision ----
//         double extreme = t.isLong ? t.maxPrice : t.minPrice;
//         TrailStep r = computeTrail(t.isLong, t.entry, t.sl, t.tp, extreme, t.trailActive, t.trailRef, tick);
//         if (r.justActivated) {
//             t.trailActive = true;
//             t.trailRef = r.activationRef;
//             dirty = true;
//             System.out.printf("  [TRAIL] %s %s TRAILING ACTIVATED | entry=%.6f price=%.6f best=%.6f SL=%.6f TP=%.6f%n",
//                     pair, t.isLong ? "LONG" : "SHORT", t.entry, px, extreme, t.sl, t.tp);
//         }

//         if (now - t.lastStatusLogMs >= TRAIL_STATUS_LOG_MS) {
//             System.out.printf("  [TRAIL] %s %s entry=%.6f price=%.6f best=%.6f SL=%.6f TP=%.6f gap=%.6f trailing=%s | %s%n",
//                     pair, t.isLong ? "LONG" : "SHORT", t.entry, px, extreme, t.sl, t.tp, Math.abs(t.tp - t.sl),
//                     t.trailActive ? "ACTIVE" : "waiting", r.reason);
//             t.lastStatusLogMs = now;
//         }

//         if (!r.move) { if (dirty) saveTradeState(); return; }
//         if (now < t.nextTrailTryMs) { if (dirty) saveTradeState(); return; }          // backing off after a failed update

//         // never place a stop on the wrong side of the market (would trigger instantly / be rejected)
//         boolean sane = t.isLong ? (r.newSl < px && r.newTp > px) : (r.newSl > px && r.newTp < px);
//         if (!sane) {
//             System.out.printf("  [TRAIL] %s update skipped: new SL=%.6f / TP=%.6f not valid against price %.6f%n", pair, r.newSl, r.newTp, px);
//             if (dirty) saveTradeState();
//             return;
//         }

//         String posId = pos.optString("id", null);
//         if (posId == null || posId.isEmpty()) {
//             System.out.println("  [TRAIL] " + pair + " position id missing — cannot update TP/SL");
//             if (dirty) saveTradeState();
//             return;
//         }

//         // ---- 6. replace SL+TP on the exchange (verified by reading the position back) ----
//         double oldSl = t.sl, oldTp = t.tp;
//         boolean ok = replaceTpSl(posId, pair, r.newTp, r.newSl, tick, oldTp, oldSl);
//         if (ok) {
//             t.sl = r.newSl; t.tp = r.newTp;
//             t.trailRef = r.newRef;
//             t.lastTrailMs = now;
//             t.nextTrailTryMs = 0;
//             System.out.printf("  [TRAIL-UPDATE] %s %s entry=%.6f price=%.6f best=%.6f | SL %.6f -> %.6f | TP %.6f -> %.6f | shift=%.6f gap=%.6f (unchanged)%n",
//                     pair, t.isLong ? "LONG" : "SHORT", t.entry, px, extreme, oldSl, t.sl, oldTp, t.tp, r.shift, Math.abs(t.tp - t.sl));
//         } else {
//             t.nextTrailTryMs = now + TRAIL_RETRY_BACKOFF_MS;
//             System.out.printf("  [TRAIL] %s update FAILED — keeping SL=%.6f TP=%.6f, retry in %ds%n", pair, t.sl, t.tp, TRAIL_RETRY_BACKOFF_MS / 1000);
//         }
//         saveTradeState();
//     }

//     // Sets BOTH levels with one create_tpsl call and confirms by reading the position back. Never cancels first,
//     // so the position is protected the whole time. If the position vanished meanwhile, returns false and does nothing.
//     private static boolean replaceTpSl(String posId, String pair, double tp, double sl, double tick, double oldTp, double oldSl) {
//         for (int attempt = 1; attempt <= TRAIL_UPDATE_MAX_RETRIES; attempt++) {
//             setTpSl(posId, tp, sl, pair);
//             sleepMs(TRAIL_VERIFY_DELAY_MS);
//             try {
//                 JSONObject p = findPosition(pair);
//                 if (p == null || Math.abs(p.optDouble("active_pos", 0)) <= 0) {
//                     System.out.println("  [TRAIL] " + pair + " position is closed — TP/SL update abandoned");
//                     return false;
//                 }
//                 double nowSl = p.optDouble("stop_loss_trigger", 0), nowTp = p.optDouble("take_profit_trigger", 0);
//                 if (!priceDiffers(nowSl, sl, tick) && !priceDiffers(nowTp, tp, tick)) return true;
//                 System.out.printf("  [TRAIL] %s update not visible yet (attempt %d/%d): exchange SL=%.6f TP=%.6f, wanted SL=%.6f TP=%.6f%n",
//                         pair, attempt, TRAIL_UPDATE_MAX_RETRIES, nowSl, nowTp, sl, tp);
//             } catch (Exception e) {
//                 System.err.println("  [TRAIL] verify " + pair + " attempt " + attempt + ": " + e.getMessage());
//             }
//         }
//         System.out.println("  [TRAIL] " + pair + " could not confirm the new TP/SL — restoring the previous levels");
//         setTpSl(posId, oldTp, oldSl, pair);
//         return false;
//     }

//     // =========================================================================
//     // Reliability-only safety sweep: restores MISSING TP/SL only, never modifies one that is set.
//     // (Uses the trailed levels from the persisted state, so a restored SL/TP is never worse than the trailed one.)
//     // =========================================================================
//     private static void ensureTpSlForOpenPositions() {
//         try {
//             Set<String> stillOpen = getActivePositions();
//             if (stillOpen == null) return;
//             for (String pair : stillOpen) {
//                 JSONObject pos = findPosition(pair);
//                 if (pos == null) continue;
//                 double avgPrice = pos.optDouble("avg_price", 0);
//                 double tpTrig   = pos.optDouble("take_profit_trigger", 0);
//                 double slTrig   = pos.optDouble("stop_loss_trigger", 0);
//                 if (avgPrice <= 0) continue;
//                 if (tpTrig > 0 && slTrig > 0) continue;

//                 System.out.println("  [SWEEP] " + pair + " missing TP and/or SL — restoring protection...");
//                 boolean isLong = pos.optDouble("active_pos", 0) >= 0;
//                 double tick = getTickSize(pair);

//                 TradeInfo trade = tradeState.get(pair);
//                 double sl, tp;
//                 if (trade != null && trade.sl > 0 && trade.tp > 0) {
//                     sl = trade.sl; tp = trade.tp;
//                 } else {
//                     SlTp fb = computeFixedSlTp(isLong, avgPrice, tick);
//                     if (!fb.ok) {
//                         System.out.println("  [SWEEP] " + pair + " — cannot compute fixed SL/TP: " + fb.reason);
//                         continue;
//                     }
//                     sl = fb.sl; tp = fb.tp;
//                     System.out.println("  [SWEEP] " + pair + " — no recorded trade state; using fixed % from average entry");
//                 }
//                 if (slTrig > 0) sl = slTrig;
//                 if (tpTrig > 0) tp = tpTrig;

//                 String posId = pos.optString("id", null);
//                 if (posId != null) {
//                     System.out.printf("  [SWEEP] %s SL=%.6f TP=%.6f%n", pair, sl, tp);
//                     setTpSlWithRetry(posId, tp, sl, pair);
//                     if (trade == null) {
//                         TradeInfo nt = new TradeInfo();
//                         nt.isLong = isLong; nt.entry = avgPrice; nt.sl = sl; nt.tp = tp;
//                         nt.initialSl = sl; nt.risk = Math.abs(avgPrice - sl);
//                         nt.maxPrice = avgPrice; nt.minPrice = avgPrice;
//                         nt.openedMs = System.currentTimeMillis();
//                         nt.ctx = "recovered-after-restart";
//                         tradeState.put(pair, nt);
//                         saveTradeState();
//                     } else {
//                         trade.sl = sl; trade.tp = tp;
//                         saveTradeState();
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
//             sleepMs(TPSL_RETRY_DELAY_MS);
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
//                 + " — will be retried by the next cycle's safety sweep");
//         return false;
//     }

//     // =========================================================================
//     // Indicators (ACTIVE in this strategy: Supertrend on 1H-4H, EMA9/EMA21 on 15M)
//     // =========================================================================
//     static boolean[] calcSupertrend(double[] hi, double[] lo, double[] cl, int period, double multiplier) {
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
//                 upperBand[i] = (basicUpper < upperBand[i-1] || cl[i-1] > upperBand[i-1]) ? basicUpper : upperBand[i-1];
//                 lowerBand[i] = (basicLower > lowerBand[i-1] || cl[i-1] < lowerBand[i-1]) ? basicLower : lowerBand[i-1];
//             }
//             if (i == period) bullish[i] = cl[i] > (hi[i] + lo[i]) / 2.0;
//             else bullish[i] = bullish[i-1] ? cl[i] >= lowerBand[i] : cl[i] > upperBand[i];
//         }
//         for (int i = 0; i < period; i++) bullish[i] = bullish[period];
//         return bullish;
//     }

//     static double[] calcATRSeries(double[] hi, double[] lo, double[] cl, int period) {
//         int n = hi.length;
//         double[] atr = new double[n];
//         if (n < 2) return atr;
//         double[] tr = new double[n];
//         tr[0] = hi[0] - lo[0];
//         for (int i = 1; i < n; i++)
//             tr[i] = Math.max(hi[i] - lo[i], Math.max(Math.abs(hi[i] - cl[i-1]), Math.abs(lo[i] - cl[i-1])));
//         double sum = 0;
//         for (int i = 0; i < period && i < n; i++) sum += tr[i];
//         atr[period - 1] = sum / period;
//         for (int i = period; i < n; i++) atr[i] = (atr[i-1] * (period - 1) + tr[i]) / period;
//         for (int i = 0; i < period - 1; i++) atr[i] = atr[period - 1];
//         return atr;
//     }

//     static double calcEMA(double[] d, int period) {
//         if (d.length < period) return 0;
//         double k = 2.0 / (period + 1), ema = 0;
//         for (int i = 0; i < period; i++) ema += d[i];
//         ema /= period;
//         for (int i = period; i < d.length; i++) ema = d[i] * k + ema * (1 - k);
//         return ema;
//     }

//     private static BigDecimal roundToTickBD(double price, double tick) {
//         if (tick <= 0) return BigDecimal.valueOf(price);
//         BigDecimal bdPrice = BigDecimal.valueOf(price);
//         BigDecimal bdTick  = BigDecimal.valueOf(tick);
//         BigDecimal multiples = bdPrice.divide(bdTick, 0, RoundingMode.HALF_UP);
//         BigDecimal result = multiples.multiply(bdTick);
//         return result.setScale(bdTick.scale(), RoundingMode.HALF_UP);
//     }

//     static double roundToTick(double price, double tick) {
//         if (tick <= 0) return price;
//         return roundToTickBD(price, tick).doubleValue();
//     }

//     // =========================================================================
//     // Exchange plumbing (unchanged)
//     // =========================================================================
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
//             if (!instrumentCache.isEmpty())
//                 System.out.println("Instrument fields (verify quantity_increment/min_quantity/min_notional exist): "
//                         + instrumentCache.values().iterator().next().keySet());
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
//         JSONArray arr = resp.startsWith("[") ? new JSONArray(resp) : new JSONArray().put(new JSONObject(resp));
//         for (int i = 0; i < arr.length(); i++) {
//             JSONObject p = arr.getJSONObject(i);
//             if (pair.equals(p.optString("pair"))) return p;
//         }
//         return null;
//     }

//     public static double getLastPrice(String pair) {
//         try {
//             HttpURLConnection conn = openGet(PUBLIC_API_URL + "/market_data/trade_history?pair=" + pair + "&limit=1");
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

//     public static JSONObject placeFuturesOrder(String side, String pair, double qty, int lev, String notif,
//                                                String marginType, String marginCcy, double currentPrice) {
//         try {
//             double rawLimitPrice = "buy".equalsIgnoreCase(side)
//                     ? currentPrice * (1 + LIMIT_ORDER_BUFFER_PCT)
//                     : currentPrice * (1 - LIMIT_ORDER_BUFFER_PCT);
//             double tick = getTickSize(pair);
//             BigDecimal limitPriceBD = roundToTickBD(rawLimitPrice, tick);

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
//             String resp = authPost(BASE_URL + "/exchange/v1/derivatives/futures/orders/create", body.toString());
//             return resp.startsWith("[") ? new JSONArray(resp).getJSONObject(0) : new JSONObject(resp);
//         } catch (Exception e) {
//             System.err.println("placeFuturesOrder: " + e.getMessage());
//             return null;
//         }
//     }

//     public static void setTpSl(String posId, double tp, double sl, String pair) {
//         try {
//             double tick = getTickSize(pair);
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
//             String resp = authPost(BASE_URL + "/exchange/v1/derivatives/futures/positions/create_tpsl", payload.toString());
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
//             sleepMs(POSITION_ID_RETRY_DELAY_MS);
//         }
//         return null;
//     }

//     // Returns null if the positions API call fails (so callers never mistake an API error for "no positions").
//     private static Set<String> getActivePositions() {
//         Set<String> active = new HashSet<>();
//         try {
//             JSONObject body = new JSONObject();
//             body.put("timestamp", Instant.now().toEpochMilli());
//             body.put("page", "1");
//             body.put("size", "100");
//             body.put("margin_currency_short_name", new String[]{"INR", "USDT"});
//             String resp = authPost(BASE_URL + "/exchange/v1/derivatives/futures/positions", body.toString());
//             JSONArray arr = resp.startsWith("[") ? new JSONArray(resp) : new JSONArray().put(new JSONObject(resp));
//             System.out.println("=== Open Positions (" + arr.length() + ") ===");
//             for (int i = 0; i < arr.length(); i++) {
//                 JSONObject p    = arr.getJSONObject(i);
//                 String    pair  = p.optString("pair", "");
//                 boolean isActive = isActivePosition(p);
//                 if (isActive) {
//                     System.out.printf("  %s | qty=%.2f | entry=%.6f | TP=%.4f | SL=%.4f%n",
//                             pair, p.optDouble("active_pos", 0), p.optDouble("avg_price", 0),
//                             p.optDouble("take_profit_trigger", 0), p.optDouble("stop_loss_trigger", 0));
//                     active.add(pair);
//                 }
//             }
//             return active;
//         } catch (Exception e) {
//             System.err.println("getActivePositions: " + e.getMessage());
//             return null;
//         }
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
//         return new BufferedReader(new InputStreamReader(is)).lines().collect(Collectors.joining("\n"));
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

//     // =========================================================================
//     // OFFLINE SELF-TESTS (no network, no API keys):  --selftest
//     // =========================================================================
//     private static int testsRun = 0, testsFailed = 0;

//     private static void check(String name, boolean cond) {
//         testsRun++;
//         if (!cond) testsFailed++;
//         System.out.println((cond ? "  PASS  " : "  FAIL  ") + name);
//     }

//     // Builds a Series5 from a close path: candle i opens at the previous close, wick w on both sides.
//     // Candle i starts at lastStart - (n-1-i)*5M.
//     private static Series5 pathSeries(double[] c, long lastStart, double wick) {
//         int n = c.length;
//         Series5 s = new Series5(n);
//         for (int i = 0; i < n; i++) {
//             double o = i == 0 ? c[0] : c[i - 1];
//             s.t[i] = lastStart - (long) (n - 1 - i) * FIVE_MIN_MS;
//             s.o[i] = o; s.c[i] = c[i];
//             s.h[i] = Math.max(o, c[i]) + wick;
//             s.l[i] = Math.min(o, c[i]) - wick;
//         }
//         return s;
//     }

//     // Long decline into a low, closed candles only. Mirror (up=false) -> long rise into a high.
//     private static double[] declinePath(int n, double slope, boolean decline, double base, double wiggle) {
//         double[] c = new double[n];
//         for (int i = 0; i < n; i++) {
//             double v = base + slope * (n - 1 - i) + wiggle * Math.sin(i * 0.9);
//             c[i] = decline ? v : 2 * base - v + 0;
//         }
//         return c;
//     }

//     static boolean selfTest() {
//         testsRun = 0; testsFailed = 0;
//         long b12 = (1_760_000_000_000L / (12 * HOUR_MS)) * (12 * HOUR_MS);     // aligned for 15M/1H/2H/3H/4H buckets

//         System.out.println("[Test] Timeframes are 1H / 2H / 3H / 4H");
//         check("timeframe names", Arrays.equals(TF_NAMES, new String[]{"1H", "2H", "3H", "4H"}));
//         check("timeframe lengths", TF_MS[0] == 60 * MIN_MS && TF_MS[1] == 120 * MIN_MS && TF_MS[2] == 180 * MIN_MS && TF_MS[3] == 240 * MIN_MS);
//         check("small 5M request covers a full 4H bucket", SCAN_FETCH_5M >= 48 + 2);
//         check("history holds enough 4H bars for the Supertrend", HIST_5M / 48 >= ST_MIN_BARS + 4);

//         System.out.println("[Test] Series merge / trim");
//         Series5 sa = new Series5(3), sb = new Series5(2);
//         for (int i = 0; i < 3; i++) { sa.t[i] = i * FIVE_MIN_MS; sa.c[i] = 1; }
//         for (int i = 0; i < 2; i++) { sb.t[i] = (1 + i) * FIVE_MIN_MS; sb.c[i] = 2; }
//         Series5 sm = mergeSeries(sa, sb);
//         check("newer data replaces overlapping candles", sm.n == 3 && sm.c[0] == 1 && sm.c[1] == 2 && sm.c[2] == 2 && sm.t[2] == 2 * FIVE_MIN_MS);
//         check("trim keeps the newest candles", trimSeries(sm, 2).n == 2 && trimSeries(sm, 2).t[0] == FIVE_MIN_MS);

//         System.out.println("[Test] Complete higher-timeframe bars (history only)");
//         double[] up = new double[48];
//         for (int i = 0; i < up.length; i++) up[i] = 101 + i;
//         Series5 su = pathSeries(up, b12 + 47 * FIVE_MIN_MS, 0);          // candles b12 .. b12+235min, open[i] = close[i-1]
//         Bars b15 = aggComplete(su, FIFTEEN_MIN_MS, b12 + 240 * MIN_MS);
//         check("16 complete 15M bars from 48 x 5M", b15.n == 16);
//         check("15M bar OHLC merged (open of 1st 5M, close of 3rd 5M)", Math.abs(b15.o[1] - su.o[3]) < 1e-9 && Math.abs(b15.c[1] - su.c[5]) < 1e-9);
//         check("buckets starting at/after the developing start are excluded", aggComplete(su, FIFTEEN_MIN_MS, b12 + 120 * MIN_MS).n == 8);
//         Series5 holey = new Series5(su.n - 1);
//         for (int i = 0, j = 0; i < su.n; i++) { if (i == 4) continue; holey.t[j] = su.t[i]; holey.o[j] = su.o[i]; holey.h[j] = su.h[i]; holey.l[j] = su.l[i]; holey.c[j] = su.c[i]; j++; }
//         check("bucket with a missing 5M candle is dropped", aggComplete(holey, FIFTEEN_MIN_MS, b12 + 240 * MIN_MS).n == 15);

//         System.out.println("[Test] Developing candle (open from first 5M candle of each bucket, close = live price)");
//         double[] flatUp = new double[24];
//         for (int i = 0; i < flatUp.length; i++) flatUp[i] = 101 + i;
//         Series5 s24 = pathSeries(flatUp, b12 + 23 * FIVE_MIN_MS, 0);              // up to b12+115min
//         long now = b12 + 115 * MIN_MS + 30_000L;
//         check("validate5m accepts current data", validate5m(s24, now) == null);
//         Dev d1 = developing(s24, now, 124.0, 0), d2 = developing(s24, now, 124.0, 1), d3 = developing(s24, now, 124.0, 2), d4 = developing(s24, now, 124.0, 3);
//         check("1H open = first 5M candle of the 1H bucket", d1.ok && Math.abs(d1.open - s24.o[12]) < 1e-9);
//         check("2H open = first 5M candle of the 2H bucket (b12+0..2h -> first candle)", d2.ok && Math.abs(d2.open - s24.o[0]) < 1e-9);
//         check("3H and 4H open = first 5M candle of the bucket", d3.ok && d4.ok && Math.abs(d3.open - s24.o[0]) < 1e-9 && Math.abs(d4.open - s24.o[0]) < 1e-9);
//         check("price above open -> GREEN", d1.colour == 1 && d4.colour == 1);
//         check("price below open -> RED", developing(s24, now, 90.0, 0).colour == -1);
//         check("price == open -> DOJI", developing(s24, now, s24.o[12], 0).colour == 0);
//         check("developing high/low include the live price", developing(s24, now, 500.0, 0).high == 500.0 && developing(s24, now, 1.0, 0).low == 1.0);
//         Series5 noFirst = new Series5(s24.n - 1);
//         for (int i = 1; i < s24.n; i++) { noFirst.t[i - 1] = s24.t[i]; noFirst.o[i - 1] = s24.o[i]; noFirst.h[i - 1] = s24.h[i]; noFirst.l[i - 1] = s24.l[i]; noFirst.c[i - 1] = s24.c[i]; }
//         check("first 5M candle of the bucket missing -> invalid (never guessed)", !developing(noFirst, now, 124.0, 3).ok);
//         check("stale 5M data rejected", validate5m(s24, now + 10 * MIN_MS) != null && validate5m(s24, now + 10 * MIN_MS).startsWith("stale"));
//         Series5 lagging = trimSeries(s24, s24.n - 1);   // forming candle not returned yet
//         check("forming 5M candle not returned -> still valid", validate5m(lagging, now) == null);

//         System.out.println("[Test] Supertrend + EMA indicators");
//         double[] hiR = new double[40], loR = new double[40], clR = new double[40], hiF = new double[40], loF = new double[40], clF = new double[40];
//         for (int i = 0; i < 40; i++) { clR[i] = 100 + i; hiR[i] = clR[i] + 0.5; loR[i] = clR[i] - 0.5; clF[i] = 200 - i; hiF[i] = clF[i] + 0.5; loF[i] = clF[i] - 0.5; }
//         check("steady rise -> Supertrend GREEN", calcSupertrend(hiR, loR, clR, ST_PERIOD, ST_MULTIPLIER)[39]);
//         check("steady fall -> Supertrend RED", !calcSupertrend(hiF, loF, clF, ST_PERIOD, ST_MULTIPLIER)[39]);
//         check("EMA9 > EMA21 in a rising series, < in a falling one", calcEMA(clR, 9) > calcEMA(clR, 21) && calcEMA(clF, 9) < calcEMA(clF, 21));

//         System.out.println("[Test] STAGE 1: all four Supertrend + developing 1H/2H colour (contrarian rule)");
//         long cur5 = b12 + 235 * MIN_MS;                  // forming 5M candle starts here; 1H/3H bucket starts b12+180m, 2H b12+120m, 4H b12+0
//         long nowS = cur5 + 30_000L;
//         int N = 1700;
//         double[] dec = declinePath(N, 0.02, true, 100.0, 0.0);
//         Series5 sDown = pathSeries(dec, cur5 - FIVE_MIN_MS, 0.15);
//         double low = dec[N - 1];
//         Eval eLong = evaluateStage1(sDown, low + 0.9, nowS);
//         check("long decline + small live bounce: stage 1 LONG candidate", eLong.dir == 1);
//         if (eLong.dir != 1) System.out.println("      -> " + eLong.reason);
//         Eval eNo = evaluateStage1(sDown, low - 0.3, nowS);
//         check("price still falling (1H candle RED) -> no LONG candidate", eNo.dir == 0);
//         double[] inc = declinePath(N, 0.02, false, 100.0, 0.0);
//         Series5 sUp = pathSeries(inc, cur5 - FIVE_MIN_MS, 0.15);
//         double high = inc[N - 1];
//         Eval eShort = evaluateStage1(sUp, high - 0.9, nowS);
//         check("mirror: long rise + small live dip: stage 1 SHORT candidate", eShort.dir == -1);
//         if (eShort.dir != -1) System.out.println("      -> " + eShort.reason);
//         Eval eBig = evaluateStage1(sDown, low + 40.0, nowS);
//         check("huge pop flips a Supertrend to GREEN -> not all four RED -> no candidate", eBig.dir == 0);
//         Series5 shortHist = trimSeries(sDown, 300);
//         check("too little history -> rejected, not guessed", evaluateStage1(shortHist, low + 0.9, nowS).dir == 0 && evaluateStage1(shortHist, low + 0.9, nowS).code.equals("stHistory"));

//         System.out.println("[Test] STAGE 2: 3H/4H colour AND 15M EMA AND fresh 5M breakout");
//         double[] zig = new double[N];
//         for (int i = 0; i < N; i++) zig[i] = 100 + 0.02 * i + 0.6 * Math.sin(i * 0.9);
//         Series5 sZig = pathSeries(zig, cur5 - FIVE_MIN_MS, 0.1);
//         Dev[] dv = new Dev[4];
//         double[] hh = new double[SWING_LOOKBACK_5M + 2 * SWING_PIVOT_BARS + 7], ll = new double[hh.length], cc = new double[hh.length];
//         int end = sZig.n;
//         for (int i = 0; i < hh.length - 1; i++) { int src = end - (hh.length - 1) + i; hh[i] = sZig.h[src]; ll[i] = sZig.l[src]; cc[i] = sZig.c[src]; }
//         double pxTop = sZig.c[end - 1] + 5;
//         hh[hh.length - 1] = pxTop; ll[hh.length - 1] = pxTop; cc[hh.length - 1] = pxTop;
//         Breakout probe = findBreakout(hh, ll, cc, true, SWING_PIVOT_BARS, SWING_LOOKBACK_5M);
//         check("5M breakout found with the live price far above the last swing high", probe.ok && probe.level > 0);
//         Eval st2 = new Eval();
//         for (int k = 0; k < 4; k++) { Dev d = new Dev(); d.tf = TF_NAMES[k]; d.ok = true; d.colour = 1; st2.dev[k] = d; }
//         stage2(sZig, pxTop, nowS, 1, st2);
//         check("all LONG conditions true -> BUY", st2.signal.equals("BUY"));
//         if (!st2.signal.equals("BUY")) System.out.println("      -> " + st2.code + ": " + st2.reason);
//         Eval st2b = new Eval();
//         for (int k = 0; k < 4; k++) { Dev d = new Dev(); d.tf = TF_NAMES[k]; d.ok = true; d.colour = 1; st2b.dev[k] = d; }
//         st2b.dev[3].colour = -1;
//         stage2(sZig, pxTop, nowS, 1, st2b);
//         check("4H developing candle RED -> NO TRADE (AND, no OR between 3H/4H)", st2b.signal.equals("NO TRADE") && st2b.code.equals("htfColour"));
//         Eval st2c = new Eval();
//         for (int k = 0; k < 4; k++) { Dev d = new Dev(); d.tf = TF_NAMES[k]; d.ok = true; d.colour = 1; st2c.dev[k] = d; }
//         st2c.dev[2].colour = 0;
//         stage2(sZig, pxTop, nowS, 1, st2c);
//         check("3H DOJI -> NO TRADE", st2c.signal.equals("NO TRADE") && st2c.code.equals("htfColour"));
//         Eval st2d = new Eval();
//         for (int k = 0; k < 4; k++) { Dev d = new Dev(); d.tf = TF_NAMES[k]; d.ok = true; d.colour = 1; st2d.dev[k] = d; }
//         stage2(sZig, sZig.c[end - 1] - 0.5, nowS, 1, st2d);
//         check("live price below the swing high -> no breakout -> NO TRADE", st2d.signal.equals("NO TRADE") && st2d.code.equals("breakout"));
//         Eval st2e = new Eval();
//         for (int k = 0; k < 4; k++) { Dev d = new Dev(); d.tf = TF_NAMES[k]; d.ok = true; d.colour = -1; st2e.dev[k] = d; }
//         stage2(sZig, pxTop, nowS, -1, st2e);
//         check("SHORT direction on a rising 15M EMA -> NO TRADE", st2e.signal.equals("NO TRADE") && st2e.code.equals("ema"));
//         double[] ema = ema15m(sZig, pxTop, nowS, null);
//         check("15M EMA9 > EMA21 on the rising zig-zag", ema != null && ema[0] > ema[1]);

//         System.out.println("[Test] 5M swing breakout (fresh break only)");
//         double[] hiL = {5, 6, 7, 9, 7, 6, 5, 5.5, 5.2, 5.1, 5.3, 9.5};
//         double[] clL = {5, 5.5, 6.5, 8.5, 6.5, 5.5, 5, 5.2, 5.1, 5.0, 5.2, 9.2};
//         double[] loL = new double[hiL.length];
//         for (int i = 0; i < hiL.length; i++) loL[i] = hiL[i] - 0.5;
//         Breakout bl = findBreakout(hiL, loL, clL, true, 2, 10);
//         check("LONG: live price above confirmed swing high (9.0)", bl.ok && Math.abs(bl.level - 9.0) < 1e-9);
//         double[] clNo = clL.clone(); clNo[clNo.length - 1] = 8.9;
//         check("LONG: price below the swing high -> no breakout", !findBreakout(hiL, loL, clNo, true, 2, 10).ok);
//         double[] clOld = clL.clone(); clOld[7] = 9.1;
//         check("LONG: level already broken earlier -> not fresh", !findBreakout(hiL, loL, clOld, true, 2, 10).ok);
//         double[] hiS = new double[hiL.length], loS = new double[hiL.length], clS = new double[hiL.length];
//         for (int i = 0; i < hiL.length; i++) { hiS[i] = 20 - loL[i]; loS[i] = 20 - hiL[i]; clS[i] = 20 - clL[i]; }
//         Breakout bs = findBreakout(hiS, loS, clS, false, 2, 10);
//         check("SHORT: price below confirmed swing low (11.0)", bs.ok && Math.abs(bs.level - 11.0) < 1e-9);

//         System.out.println("[Test] Fixed SL / TP from the actual average entry");
//         SlTp l = computeFixedSlTp(true, 100.0, 0.01), s = computeFixedSlTp(false, 100.0, 0.01);
//         double eSl = FIXED_SL_PERCENT / 100.0, eTp = FIXED_TP_PERCENT / 100.0;
//         check("LONG SL/TP = entry -/+ configured percent", l.ok && Math.abs(l.sl - 100.0 * (1 - eSl)) < 0.006 && Math.abs(l.tp - 100.0 * (1 + eTp)) < 0.006);
//         check("SHORT SL/TP = entry +/- configured percent", s.ok && Math.abs(s.sl - 100.0 * (1 + eSl)) < 0.006 && Math.abs(s.tp - 100.0 * (1 - eTp)) < 0.006);
//         SlTp avg = computeFixedSlTp(true, 102.5, 0.5);
//         check("tick rounding keeps SL/TP on the correct side", avg.ok && avg.sl < 102.5 && avg.tp > 102.5);
//         check("break-even trigger sits inside the SL distance", BREAKEVEN_TRIGGER_PERCENT < FIXED_SL_PERCENT && PROFIT_LOCK_PERCENT < BREAKEVEN_TRIGGER_PERCENT);

//         System.out.println("[Test] Dynamic trailing (activation, step, equal shift, never backward)");
//         double tk = 0.01, entry = 100.0;
//         double actL = entry * (1 + TRAIL_ACTIVATION_PERCENT / 100.0);
//         double stepL = actL * TRAIL_STEP_PERCENT / 100.0;
//         double slL = 99.0, tpL = 101.5;
//         TrailStep t0 = computeTrail(true, entry, slL, tpL, actL - 0.1, false, 0, tk);
//         check("LONG: below activation level -> not active, no move", !t0.active && !t0.move);
//         TrailStep t1 = computeTrail(true, entry, slL, tpL, actL, false, 0, tk);
//         check("LONG: activation level reached -> active, no move yet", t1.active && t1.justActivated && !t1.move && Math.abs(t1.activationRef - actL) < 1e-9);
//         TrailStep t2 = computeTrail(true, entry, slL, tpL, actL + stepL * 0.5, true, actL, tk);
//         check("LONG: less than one step beyond the trail point -> no API update", !t2.move);
//         TrailStep t3 = computeTrail(true, entry, slL, tpL, actL + stepL * 1.5, true, actL, tk);
//         check("LONG: step reached -> move", t3.move && t3.newSl > slL && t3.newTp > tpL);
//         check("LONG: SL and TP shifted by exactly the same amount", t3.move && Math.abs((t3.newSl - slL) - (t3.newTp - tpL)) < 1e-9
//                 && Math.abs((t3.newSl - slL) - t3.shift) < 1e-9);
//         check("LONG: SL<->TP gap unchanged", t3.move && Math.abs((t3.newTp - t3.newSl) - (tpL - slL)) < 1e-9);
//         check("LONG: trail point advances by the shift", t3.move && Math.abs(t3.newRef - (actL + t3.shift)) < 1e-9);
//         TrailStep t4 = computeTrail(true, entry, t3.newSl, t3.newTp, actL, true, t3.newRef, tk);
//         check("LONG: price falling back never moves SL/TP down", !t4.move && t4.newSl == t3.newSl && t4.newTp == t3.newTp);
//         double actS = entry * (1 - TRAIL_ACTIVATION_PERCENT / 100.0);
//         double stepS = actS * TRAIL_STEP_PERCENT / 100.0;
//         double slS = 101.0, tpS = 98.5;
//         TrailStep s1 = computeTrail(false, entry, slS, tpS, actS, false, 0, tk);
//         check("SHORT: activation level reached -> active, no move yet", s1.active && s1.justActivated && !s1.move);
//         TrailStep s3 = computeTrail(false, entry, slS, tpS, actS - stepS * 1.5, true, actS, tk);
//         check("SHORT: step reached -> SL and TP both move DOWN by the same amount", s3.move && s3.newSl < slS && s3.newTp < tpS
//                 && Math.abs((slS - s3.newSl) - (tpS - s3.newTp)) < 1e-9 && Math.abs((s3.newTp - s3.newSl) - (tpS - slS)) < 1e-9);
//         TrailStep s4 = computeTrail(false, entry, s3.newSl, s3.newTp, actS + 0.5, true, s3.newRef, tk);
//         check("SHORT: price rising back never moves SL/TP up", !s4.move);
//         TrailStep tc = computeTrail(true, entry, 98.0, 103.0, actL + stepL * 1.5, true, actL, tk);
//         check("custom (manually set) SL/TP: their gap (5.00) is preserved", tc.move && Math.abs((tc.newTp - tc.newSl) - 5.0) < 1e-9);
//         check("manual-change detection uses half a tick", priceDiffers(99.0, 99.02, 0.01) && !priceDiffers(99.0, 99.004, 0.01));

//         System.out.println("[Test] Break-even + profit lock (SL only, never backward)");
//         double beTrigL = entry * (1 + BREAKEVEN_TRIGGER_PERCENT / 100.0), beLockL = entry * (1 + PROFIT_LOCK_PERCENT / 100.0);
//         BeStep b0 = computeBreakeven(true, entry, slL, beTrigL - 0.05, tk);
//         check("BE LONG: below trigger -> not reached, no move", !b0.reached && !b0.move);
//         BeStep b1 = computeBreakeven(true, entry, slL, beTrigL, tk);
//         check("BE LONG: trigger reached -> SL moves up to entry + lock", b1.reached && b1.move && Math.abs(b1.newSl - beLockL) < 0.006 && b1.newSl > entry);
//         BeStep b2 = computeBreakeven(true, entry, beLockL + 0.5, beTrigL, tk);
//         check("BE LONG: SL already better than lock level -> never moved backwards", b2.reached && !b2.move && b2.newSl == beLockL + 0.5);
//         double beTrigS = entry * (1 - BREAKEVEN_TRIGGER_PERCENT / 100.0), beLockS = entry * (1 - PROFIT_LOCK_PERCENT / 100.0);
//         BeStep b3 = computeBreakeven(false, entry, slS, beTrigS + 0.05, tk);
//         check("BE SHORT: above trigger level -> not reached, no move", !b3.reached && !b3.move);
//         BeStep b4 = computeBreakeven(false, entry, slS, beTrigS, tk);
//         check("BE SHORT: trigger reached -> SL moves down to entry - lock", b4.reached && b4.move && Math.abs(b4.newSl - beLockS) < 0.006 && b4.newSl < entry);
//         BeStep b5 = computeBreakeven(false, entry, beLockS - 0.5, beTrigS, tk);
//         check("BE SHORT: SL already better than lock level -> never moved backwards", b5.reached && !b5.move);

//         System.out.println("[Test] Trade state persistence round-trip");
//         TradeInfo pt = new TradeInfo();
//         pt.isLong = false; pt.entry = 123.456; pt.sl = 125.0; pt.tp = 120.5; pt.initialSl = 124.7; pt.risk = 1.244;
//         pt.maxPrice = 124.0; pt.minPrice = 119.9; pt.openedMs = 1_760_000_000_000L; pt.ctx = "1H:ST=G/C=R";
//         pt.trailActive = true; pt.trailRef = 121.9; pt.lastTrailMs = 1_760_000_100_000L; pt.beDone = true;
//         TradeInfo rt = tradeFromJson(new JSONObject(tradeToJson(pt).toString()));
//         check("all trailing fields survive save/load", !rt.isLong && rt.entry == pt.entry && rt.sl == pt.sl && rt.tp == pt.tp
//                 && rt.maxPrice == pt.maxPrice && rt.minPrice == pt.minPrice && rt.trailActive && rt.trailRef == pt.trailRef
//                 && rt.initialSl == pt.initialSl && rt.openedMs == pt.openedMs && rt.lastTrailMs == pt.lastTrailMs && rt.ctx.equals(pt.ctx) && rt.beDone);

//         System.out.println("[Test] Duplicate signal protection (one per 15M developing candle)");
//         Map<String, Long> reg = new HashMap<>();
//         check("first signal accepted", registerSignal(reg, "B-BTC_USDT|LONG", 1000L));
//         check("same 15M candle, same direction rejected", !registerSignal(reg, "B-BTC_USDT|LONG", 1000L));
//         check("next 15M candle accepted", registerSignal(reg, "B-BTC_USDT|LONG", 1000L + FIFTEEN_MIN_MS));
//         check("other direction is tracked separately", registerSignal(reg, "B-BTC_USDT|SHORT", 1000L));

//         System.out.println("[Test] Quantity precision / minimum size validation");
//         JSONObject inst = new JSONObject("{\"quantity_increment\":0.1,\"min_quantity\":1,\"min_notional\":5}");
//         check("12.34 floors to 12.3", Math.abs(sizeQuantity(12.34, 100, false, inst).qty - 12.3) < 1e-9);
//         check("0.57 rejected (below min quantity)", sizeQuantity(0.57, 100, false, inst).qty == 0);
//         check("notional below minimum rejected", sizeQuantity(1.0, 1.0, false, inst).qty == 0);
//         check("integer-only pair floors to whole units", sizeQuantity(7.9, 1, true, null).qty == 7.0);
//         check("quantity rounding to 0 rejected", sizeQuantity(0.004, 1, false, null).qty == 0);

//         System.out.println("[Test] Partial fill + API failure helpers");
//         check("filled 40% of 10 = partial", isPartialFill(10, 4));
//         check("fully filled is not partial", !isPartialFill(10, 10));
//         check("no fill is not 'partial'", !isPartialFill(10, 0));
//         check("error body detected", isErrorResponse("{\"err_code_dcx\":\"x\"}") && isErrorResponse("") && isErrorResponse(null) && isErrorResponse("<html>"));
//         check("normal body accepted", !isErrorResponse("{\"message\":\"success\"}"));

//         System.out.println("\nSelf-test: " + (testsRun - testsFailed) + "/" + testsRun + " passed");
//         return testsFailed == 0;
//     }
// }
