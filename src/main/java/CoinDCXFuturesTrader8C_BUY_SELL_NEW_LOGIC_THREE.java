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
// SCORE-BASED MULTI-TIMEFRAME STRATEGY  (HTF 1H/2H/4H -> 15M confirmation -> 5M trigger)
//
//  ENTRY  : weighted score 0-100 for LONG and SHORT separately (closed candles only).
//           HTF 30 pts  : EMA50/EMA200 + Supertrend + ADX/DI on 1H, 2H, 4H
//           15M 30 pts  : EMA9/21, RSI, Supertrend, structure (HL/LH, BOS), VWAP, volume
//           5M  40 pts  : breakout-or-retest trigger, candle strength, RSI, volume, room to next S/R
//           HARD GATES  : 15M EMA9/21 + 15M Supertrend must agree with the trade direction,
//                         BTC trend must not be strongly against the trade, breakout needs a volume spike,
//                         counter-trend trades are OFF by default (ALLOW_COUNTER_TREND).
//  SL/TP  : SL = 15M swing +/- ATR buffer, clamped to [MIN_SL_PCT, MAX_SL_PCT]; TP = TP_R x SL, cut before next 1H/4H S/R.
//  MANAGE : FIRST break-even is booked at BE_FIRST_TRIGGER_R x SL distance (default 1.0 => SL : first BE profit = 1 : 1).
//           Then profit-lock ladder, then R-based trailing SL; TP extended in limited steps.
//           All distances are in "R" (R = initial SL distance), so they scale with every coin's volatility.
//
//  v6 CHANGES (after the trade_log_v5 review)
//   RISK    : max MAX_SAME_DIRECTION_POSITIONS open LONGs and the same number of SHORTs (trades are highly correlated);
//             per-pair cooldown after a close (PAIR_COOLDOWN_AFTER_CLOSE_MS, longer after a stop-loss).
//   EXIT    : ladder stage 1 now locks +0.2R (was pure cost-covering break-even).
//   BUGFIX  : roomR was "Infinity" when no S/R level exists -> capped at ROOM_R_CAP.
//   LOGGING : trade_log_v6.csv. Exit reason is resolved from 1M candles since the position was last seen
//             (TP / SL / SL_LOCKED_TRAIL / NO_LEVEL_TOUCHED = manual or liquidation), so the old
//             "OTHER(manual/liquidation?)" bucket (mostly detection delay after a restart) goes away.
//             New columns: config version, signal price + entry slippage, estimated exit price / R / net R,
//             score components, trigger type, open positions at entry, BTC context.
// =============================================================================
public class CoinDCXFuturesTrader8C_BUY_SELL_NEW_LOGIC_THREE {

    static final String CONFIG_VERSION = "v6";

    // =========================================================================
    // Execution mode (false = PAPER: fixed SL/TP only, no break-even/trailing)
    // =========================================================================
    static final boolean LIVE_TRADING_ENABLED = true;

    // =========================================================================
    // Capital (position sizing unchanged: notional INR = FIXED_MARGIN)
    // =========================================================================
    private static final double FIXED_MARGIN = 2000.0;
    private static final int    LEVERAGE     = 10;
    static final double  USDT_INR_RATE       = 102.0;
    private static final int MAX_OPEN_POSITIONS = 10;
    static final int MAX_SAME_DIRECTION_POSITIONS = 30;    // v6: max open LONGs and max open SHORTs (correlated trades move together)

    // =========================================================================
    // Entry scoring
    // =========================================================================
    static final double ENTRY_SCORE_MIN         = 68.0;   // normal (trend-aligned) entries            (was 60)
    static final double COUNTER_TREND_SCORE_MIN = 80.0;   // reversal entries against the HTF trend   (was 72)
    static final double HTF_ALIGN_MIN           = 0.60;   // HTF score ratio below this = counter-trend (was 0.50)
    static final double PRE_SCORE_MIN           = 0.55;   // (HTF+15M) ratio needed before the 5M data is fetched (was 0.50)
    static final boolean ALLOW_COUNTER_TREND    = false;  // counter-trend (reversal) trades are the usual loss source -> OFF

    // BTC market filter: alt trades are skipped when BTC's HTF trend is strongly against the direction
    static final boolean BTC_FILTER_ENABLED = true;
    static final String  BTC_PAIR           = "B-BTC_USDT";
    static final double  BTC_MIN_ALIGN      = 0.35;       // BTC HTF score ratio for the trade direction must be >= this

    // Indicator parameters
    static final int    EMA_FAST = 9, EMA_SLOW = 21, EMA_TREND_FAST = 50, EMA_TREND_SLOW = 200;
    static final int    RSI_PERIOD = 14, ADX_PERIOD = 14, ATR_PERIOD = 14, ST_PERIOD = 10, VOL_AVG_PERIOD = 20;
    static final double ST_MULTIPLIER = 3.0;
    static final double ADX_TREND_MIN = 20.0;             // meaningful trend
    static final double ADX_SIDEWAYS_MAX = 15.0;          // all HTF ADX below this = sideways -> no trade
    static final String[] HTF_NAMES = {"1H", "2H", "4H"};
    static final double[] HTF_WEIGHT = {9, 9, 12};        // sums to 30

    // 15M reversal proof / structure
    static final int    ST_FLIP_BARS = 8;                 // Supertrend flip counts as "recent" within this many 15M bars
    static final int    CROSS_BARS   = 4;                 // EMA9/21 cross counts as "recent" within this many 15M bars

    // 5M trigger / anti-chase filters
    static final int    SWING_K_5M = 2;                   // pivot needs K lower highs / higher lows on each side
    static final int    SWING_LOOKBACK_5M = 60;
    static final int    RETEST_MAX_BARS = 10;
    static final double RETEST_TOL_ATR = 0.30;
    static final double MAX_CANDLE_ATR = 2.5;             // signal candle bigger than this x ATR = "extended", skip
    static final double SPIKE_ATR = 4.0;                  // any of the 2 candles before it bigger than this = abnormal spike
    static final double PRICE_JUMP_ATR = 1.0;             // live price farther than this from the signal close = skip
    static final double OVEREXT_EMA21_ATR = 3.0;          // signal close farther than this from 5M EMA21 = overextended
    static final double BREAKOUT_MAX_CHASE_ATR = 1.2;     // live price farther than this beyond the broken level = chasing
    static final double RSI5_EXTREME = 78.0;              // long skipped above, short skipped below 100-this (was 82)
    static final double COUNTER_MIN_VOL_RATIO = 1.5;      // counter-trend needs a volume spike on the trigger
    static final double BREAKOUT_MIN_VOL_RATIO = 1.2;     // fresh breakouts need a volume spike (fake-breakout filter)

    // Spread filter (public orderbook; if the call fails the trade is allowed when SPREAD_FAIL_OPEN)
    static final double  MAX_SPREAD_PCT = 0.15;
    static final boolean SPREAD_FAIL_OPEN = true;

    // =========================================================================
    // Initial SL / TP
    // =========================================================================
    static final double SL_ATR_BUFFER   = 0.6;            // x ATR(15M) beyond the swing                (was 0.5)
    static final double MIN_SL_PCT      = 1.0;            // price %, at 10x = 10% margin               (was 0.8)
    static final double MAX_SL_PCT      = 2.0;            // price %, at 10x = 20% margin               (was 1.5)
    static final double MIN_SL_ATR15    = 1.0;            // SL never tighter than this x ATR(15M)      (was 0.8)
    static final double TP_R            = 0.75;            // initial TP = TP_R x SL distance            (was 1.5)
    static final double MIN_ROOM_R      = 1.5;            // skip if next 1H/4H S/R is closer than this x SL distance (was 1.0)
    static final double MIN_TP_R        = 0.2;            // skip if TP (after cutting before S/R) is smaller than this x SL (was 0.8)
    static final double ROOM_BUFFER_ATR = 0.2;            // TP placed this x ATR(15M) before the S/R level
    static final double ROOM_R_CAP      = 20.0;           // v6: roomR is capped here (no S/R found used to print "Infinity")

    // =========================================================================
    // Cost model (used so the break-even SL really locks NET profit)
    // =========================================================================
    static final double FEE_PCT_PER_SIDE = 0.05;          // CoinDCX INR-M taker approx; use 0.02 if entry is maker
    static final double SLIPPAGE_PCT     = 0.05;          // total estimate
    static final double COST_BUFFER_PCT  = 2 * FEE_PCT_PER_SIDE + SLIPPAGE_PCT;

    // =========================================================================
    // Break-even + profit-lock LADDER (SL only). Values in R (R = initial SL distance)
    //   FIRST break-even is booked when profit = BE_FIRST_TRIGGER_R x SL distance.
    //   BE_FIRST_TRIGGER_R = 1.0  =>  risk : first BE trigger = 1 : 1  (SL distance == first BE profit distance)
    //   stage i is reached when the favourable extreme >= LADDER_TRIGGER_R[i]; SL then goes to
    //   entry +/- max(cost, LADDER_LOCK_R[i] x R). Never backwards.
    // =========================================================================
    static final boolean BREAKEVEN_ENABLED = true;
    static final double  BE_FIRST_TRIGGER_R = 1.0;
    static final double[] LADDER_TRIGGER_R = {BE_FIRST_TRIGGER_R, 1.5, 2.0, 3.0};
    static final double[] LADDER_LOCK_R    = {0.2,                0.5, 1.0, 2.0};   // v6: stage 1 locks +0.2R (was 0.0 = pure cost-covering break-even)

    // =========================================================================
    // Trailing SL (+ limited TP extension)
    // =========================================================================
    static final boolean TRAILING_ENABLED     = true;
    static final double  TRAIL_ACTIVATION_R   = 1.5;      // trailing starts here (after the 1R break-even)   (was 1.0)
    static final double  TRAIL_DISTANCE_R     = 0.6;      // SL = best price -/+ this x R  (wider = fewer early exits) (was 0.2)
    static final double  TRAIL_STEP_R         = 0.25;     // SL only updated when it improves by >= this x R  (was 0.15)
    static final double  TP_EXTEND_TRIGGER_R  = 0.30;     // when best price is within this x R of TP (and trailing active)...
    static final double  TP_EXTEND_R          = 1.0;      // ...TP moves up by this x R                       (was 0.8)
    static final int     MAX_TP_EXTENSIONS    = 10;       // never extended indefinitely                      (was 30)
    static final long    TRAIL_CHECK_INTERVAL_MS = 5_000L;
    static final int     TRAIL_UPDATE_MAX_RETRIES = 3;
    static final long    TRAIL_VERIFY_DELAY_MS = 800;
    static final long    TRAIL_RETRY_BACKOFF_MS = 15_000L;
    static final long    TRAIL_STATUS_LOG_MS = 60_000L;
    static final double  ENTRY_MISMATCH_PCT = 0.3;        // saved state with a different entry = another position
    static final double  RECOVER_R_PCT = 2.0;             // R (price %) assumed for positions rebuilt without a known initial SL
    static final String  TRADE_STATE_FILE = "trade_state_v5.json";   // unchanged on purpose: open positions survive the v6 upgrade

    // =========================================================================
    // API / exchange (unchanged)
    // =========================================================================
    private static final String API_KEY    = System.getenv("DELTA_API_KEY");
    private static final String API_SECRET = System.getenv("DELTA_API_SECRET");
    private static final String BASE_URL       = "https://api.coindcx.com";
    private static final String PUBLIC_API_URL = "https://public.coindcx.com";

    private static final int MAX_ENTRY_PRICE_CHECKS = 20;
    private static final int ENTRY_CHECK_DELAY_MS   = 1000;
    private static final int  TPSL_MAX_RETRIES    = 3;
    private static final long TPSL_RETRY_DELAY_MS = 2000L;
    private static final int  CANCEL_MAX_RETRIES     = 3;
    private static final long CANCEL_RETRY_DELAY_MS  = 1000L;
    private static final long CANCEL_VERIFY_DELAY_MS = 1500L;
    private static final long TICK_CACHE_TTL_MS = 3_600_000L;
    private static final int  POSITION_ID_MAX_RETRIES = 5;
    private static final long POSITION_ID_RETRY_DELAY_MS = 1500L;
    private static final double LIMIT_ORDER_BUFFER_PCT = 0.0005;

    // =========================================================================
    // Data fetching (CoinDCX natively gives 1M/5M/1H/1D: 15M, 2H and 4H are aggregated from 5M / 1H)
    // =========================================================================
    static final long MIN_MS = 60_000L, FIVE_MIN_MS = 5 * MIN_MS, FIFTEEN_MIN_MS = 15 * MIN_MS,
            HOUR_MS = 60 * MIN_MS, TWO_HOUR_MS = 2 * HOUR_MS, FOUR_HOUR_MS = 4 * HOUR_MS;
    static final int  MAX_BARS_PER_CALL = 500;
    static final long MIN_REQUEST_GAP_MS = 60L;
    static final int  FETCH_1H = 880;       // -> ~220 4H candles (EMA200 needs 200+)
    static final int  FETCH_5M_CTX = 450;   // -> 150 15M candles
    static final int  FETCH_5M_TRIGGER = 100;
    static final long DATA_RETRY_MS = 120_000L;   // pair with missing/lagging data is skipped for this long

    // =========================================================================
    // Timing / logging
    // =========================================================================
    static final long COOLDOWN_MS   = 5L * 60 * 1000L;                       // after an ENTRY order (anti double-order)
    static final long PAIR_COOLDOWN_AFTER_CLOSE_MS = 60L * 60 * 1000L;       // v6: no re-entry on the same pair after any close
    static final long PAIR_COOLDOWN_AFTER_SL_MS    = 120L * 60 * 1000L;      // v6: ... and longer after a plain stop-loss
    static final long SCAN_STEP_MS  = 60_000L;
    static final long SCAN_DELAY_MS = 5_000L;
    static final long PAPER_MAX_HOLD_MS = 24L * 60 * 60 * 1000L;
    static final boolean LOG_TRADES_CSV = true;
    static final boolean LOG_NO_TRADE_DETAIL = false;
    static final String TRADE_LOG_FILE = "trade_log_v6.csv";
    static final String PAPER_LOG_FILE = "paper_trade_log_v5.csv";
    static final double EPS = 1e-12;

    // =========================================================================
    // State
    // =========================================================================
    static class TradeInfo {
        boolean isLong;
        double entry, sl, tp, initialSl, initialTp, risk;   // risk = R = initial SL distance
        double maxPrice, minPrice;
        long openedMs;
        String ctx = "";
        int lockStage;          // ladder stages already reached
        boolean trailActive;
        int tpExt;              // TP extensions used
        long lastMoveMs;
        long nextTryMs, lastStatusLogMs;   // runtime only
        // ---- v6: bookkeeping used only for the trade log / exit resolution ----
        String cfg = CONFIG_VERSION;
        long lastSeenMs;        // last time the position was seen open by the management pass
        double lastSeenPx;
        double signalPx;        // live price at the final re-validation (entry slippage = fill vs this)
        int openAtEntry, longsAtEntry, shortsAtEntry;   // open positions (before this one) when it was entered
        double score, htfPts, htfMax, m15Pts, m15Max, m5Pts, m5Max, slPct, tpPct, roomR;
        double btcRatio = -1;   // BTC HTF score ratio for the trade direction (-1 = unavailable)
        int btc15m;             // BTC 15M direction: +1 / -1 / 0 (EMA and Supertrend disagree or unavailable)
        String trig = "";
        boolean counter;
    }
    static class PaperTrade { boolean isLong; double entry, sl, tp; long openedMs; String ctx = ""; }

    private static final Map<String, TradeInfo> tradeState = new ConcurrentHashMap<>();
    private static final Map<String, PaperTrade> paperTrades = new ConcurrentHashMap<>();
    private static final Map<String, JSONObject> instrumentCache = new ConcurrentHashMap<>();
    private static long lastCacheUpdate = 0;
    private static final Map<String, Long> lastTradeTime = new ConcurrentHashMap<>();
    private static final Map<String, Long> cooldownUntil = new ConcurrentHashMap<>();   // v6: per-pair cooldown after a close
    private static final Map<String, Long> lastSignalCandle = new ConcurrentHashMap<>();
    private static final Map<String, Long> lastEval5m = new ConcurrentHashMap<>();

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
    static String fmtTime(long ms) { return Instant.ofEpochMilli(ms).toString(); }
    static long alignedStart(long ms, long bucketMs, long offsetMs) { return Math.floorDiv(ms - offsetMs, bucketMs) * bucketMs + offsetMs; }
    private static void sleepMs(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); } }
    static boolean nan(double x) { return Double.isNaN(x); }
    static String dirName(int d) { return d > 0 ? "LONG" : "SHORT"; }

    // =========================================================================
    // Candle series (closed candles only) + aggregation
    // =========================================================================
    static class Series {
        long[] t; double[] o, h, l, c, v; int n;
        Series(int n) { this.n = n; t = new long[n]; o = new double[n]; h = new double[n]; l = new double[n]; c = new double[n]; v = new double[n]; }
    }

    // Assumes the candle "time" field is the candle OPEN time (seconds or ms).
    static long candleTimeMs(JSONObject c) {
        double t = c.optDouble("time", -1);
        if (t <= 0) return -1;
        long v = (long) t;
        return v < 100_000_000_000L ? v * 1000L : v;
    }

    // Sorted oldest-first, duplicates removed, ONLY candles that are fully closed (open + tfMs <= now).
    static Series toSeries(JSONArray a, long tfMs, long nowMs) {
        if (a == null) return null;
        List<JSONObject> l = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject c = a.getJSONObject(i);
            if (candleTimeMs(c) < 0) return null;
            l.add(c);
        }
        l.sort(Comparator.comparingLong(CoinDCXFuturesTrader8C_BUY_SELL_NEW_LOGIC_THREE::candleTimeMs));
        List<JSONObject> keep = new ArrayList<>();
        long prev = -1;
        for (JSONObject c : l) {
            long t = candleTimeMs(c);
            if (t == prev) continue;
            prev = t;
            if (t + tfMs > nowMs) continue;                       // still forming
            if (!(c.optDouble("close", 0) > 0) || !(c.optDouble("open", 0) > 0)) continue;
            keep.add(c);
        }
        Series s = new Series(keep.size());
        for (int i = 0; i < keep.size(); i++) {
            JSONObject c = keep.get(i);
            s.t[i] = candleTimeMs(c); s.o[i] = c.optDouble("open"); s.h[i] = c.optDouble("high"); s.l[i] = c.optDouble("low");
            s.c[i] = c.optDouble("close"); s.v[i] = c.optDouble("volume", 0);
        }
        return s;
    }

    // Time-aligned aggregation: ONLY complete buckets are kept.
    static Series aggregate(Series src, long bucketMs, long srcMs) {
        if (src == null) return null;
        int need = (int) (bucketMs / srcMs);
        List<double[]> rows = new ArrayList<>();
        int i = 0;
        while (i < src.n) {
            long bs = alignedStart(src.t[i], bucketMs, 0L);
            int j = i;
            double hi = Double.NEGATIVE_INFINITY, lo = Double.POSITIVE_INFINITY, vol = 0;
            while (j < src.n && alignedStart(src.t[j], bucketMs, 0L) == bs) {
                hi = Math.max(hi, src.h[j]); lo = Math.min(lo, src.l[j]); vol += src.v[j]; j++;
            }
            if (j - i == need && src.t[i] == bs) rows.add(new double[]{bs, src.o[i], hi, lo, src.c[j - 1], vol});
            i = Math.max(j, i + 1);
        }
        Series s = new Series(rows.size());
        for (int k = 0; k < rows.size(); k++) {
            double[] r = rows.get(k);
            s.t[k] = (long) r[0]; s.o[k] = r[1]; s.h[k] = r[2]; s.l[k] = r[3]; s.c[k] = r[4]; s.v[k] = r[5];
        }
        return s;
    }

    // =========================================================================
    // Indicators (pure functions)
    // =========================================================================
    static double[] ema(double[] x, int p) {
        double[] r = new double[x.length];
        Arrays.fill(r, Double.NaN);
        if (x.length < p) return r;
        double s = 0;
        for (int i = 0; i < p; i++) s += x[i];
        r[p - 1] = s / p;
        double k = 2.0 / (p + 1);
        for (int i = p; i < x.length; i++) r[i] = x[i] * k + r[i - 1] * (1 - k);
        return r;
    }

    static double[] rsi(double[] c, int p) {
        int n = c.length;
        double[] r = new double[n];
        Arrays.fill(r, Double.NaN);
        if (n <= p) return r;
        double g = 0, l = 0;
        for (int i = 1; i <= p; i++) { double d = c[i] - c[i - 1]; g += Math.max(d, 0); l += Math.max(-d, 0); }
        g /= p; l /= p;
        r[p] = l == 0 ? 100 : 100 - 100 / (1 + g / l);
        for (int i = p + 1; i < n; i++) {
            double d = c[i] - c[i - 1];
            g = (g * (p - 1) + Math.max(d, 0)) / p;
            l = (l * (p - 1) + Math.max(-d, 0)) / p;
            r[i] = l == 0 ? 100 : 100 - 100 / (1 + g / l);
        }
        return r;
    }

    static double[] atr(double[] h, double[] l, double[] c, int p) {
        int n = c.length;
        double[] a = new double[n];
        Arrays.fill(a, Double.NaN);
        if (n <= p) return a;
        double[] tr = new double[n];
        for (int i = 1; i < n; i++) tr[i] = Math.max(h[i] - l[i], Math.max(Math.abs(h[i] - c[i - 1]), Math.abs(l[i] - c[i - 1])));
        double s = 0;
        for (int i = 1; i <= p; i++) s += tr[i];
        a[p] = s / p;
        for (int i = p + 1; i < n; i++) a[i] = (a[i - 1] * (p - 1) + tr[i]) / p;
        return a;
    }

    // returns {adx, +DI, -DI}
    static double[][] adx(double[] h, double[] l, double[] c, int p) {
        int n = c.length;
        double[] adx = new double[n], pdi = new double[n], mdi = new double[n];
        Arrays.fill(adx, Double.NaN); Arrays.fill(pdi, Double.NaN); Arrays.fill(mdi, Double.NaN);
        if (n < 2 * p + 1) return new double[][]{adx, pdi, mdi};
        double[] tr = new double[n], pdm = new double[n], mdm = new double[n], dx = new double[n];
        for (int i = 1; i < n; i++) {
            double up = h[i] - h[i - 1], dn = l[i - 1] - l[i];
            pdm[i] = (up > dn && up > 0) ? up : 0;
            mdm[i] = (dn > up && dn > 0) ? dn : 0;
            tr[i] = Math.max(h[i] - l[i], Math.max(Math.abs(h[i] - c[i - 1]), Math.abs(l[i] - c[i - 1])));
        }
        double str = 0, sp = 0, sm = 0;
        for (int i = 1; i <= p; i++) { str += tr[i]; sp += pdm[i]; sm += mdm[i]; }
        for (int i = p; i < n; i++) {
            if (i > p) { str = str - str / p + tr[i]; sp = sp - sp / p + pdm[i]; sm = sm - sm / p + mdm[i]; }
            pdi[i] = str > 0 ? 100 * sp / str : 0;
            mdi[i] = str > 0 ? 100 * sm / str : 0;
            double sum = pdi[i] + mdi[i];
            dx[i] = sum > 0 ? 100 * Math.abs(pdi[i] - mdi[i]) / sum : 0;
        }
        double s = 0;
        for (int i = p; i < 2 * p; i++) s += dx[i];
        adx[2 * p - 1] = s / p;
        for (int i = 2 * p; i < n; i++) adx[i] = (adx[i - 1] * (p - 1) + dx[i]) / p;
        return new double[][]{adx, pdi, mdi};
    }

    static boolean[] supertrend(double[] hi, double[] lo, double[] cl, int period, double mult) {
        int n = cl.length;
        boolean[] bull = new boolean[n];
        double[] a = atr(hi, lo, cl, period);
        if (n <= period + 1) { Arrays.fill(bull, true); return bull; }
        double[] ub = new double[n], lb = new double[n];
        for (int i = period; i < n; i++) {
            double hl2 = (hi[i] + lo[i]) / 2.0, bu = hl2 + mult * a[i], bl = hl2 - mult * a[i];
            if (i == period) { ub[i] = bu; lb[i] = bl; bull[i] = cl[i] > hl2; continue; }
            ub[i] = (bu < ub[i - 1] || cl[i - 1] > ub[i - 1]) ? bu : ub[i - 1];
            lb[i] = (bl > lb[i - 1] || cl[i - 1] < lb[i - 1]) ? bl : lb[i - 1];
            bull[i] = bull[i - 1] ? cl[i] >= lb[i] : cl[i] > ub[i];
        }
        for (int i = 0; i < period; i++) bull[i] = bull[period];
        return bull;
    }

    static int lastFlip(boolean[] b) {
        for (int i = b.length - 1; i > 0; i--) if (b[i] != b[i - 1]) return i;
        return -1;
    }

    // Confirmed pivots: price strictly beyond the K bars on the left, and beyond-or-equal the K bars on the right.
    static List<Integer> pivotsHigh(double[] h, int k, int from, int to) {
        List<Integer> r = new ArrayList<>();
        for (int i = Math.max(from, k); i <= Math.min(to, h.length - 1 - k); i++) {
            boolean p = true;
            for (int j = 1; j <= k && p; j++) if (h[i] <= h[i - j] || h[i] < h[i + j]) p = false;
            if (p) r.add(i);
        }
        return r;
    }
    static List<Integer> pivotsLow(double[] l, int k, int from, int to) {
        List<Integer> r = new ArrayList<>();
        for (int i = Math.max(from, k); i <= Math.min(to, l.length - 1 - k); i++) {
            boolean p = true;
            for (int j = 1; j <= k && p; j++) if (l[i] >= l[i - j] || l[i] > l[i + j]) p = false;
            if (p) r.add(i);
        }
        return r;
    }

    static double vwapSession(Series s) {
        if (s.n == 0) return Double.NaN;
        long day = Math.floorDiv(s.t[s.n - 1], 86_400_000L) * 86_400_000L;
        double pv = 0, vv = 0;
        for (int i = 0; i < s.n; i++) if (s.t[i] >= day) { pv += (s.h[i] + s.l[i] + s.c[i]) / 3.0 * s.v[i]; vv += s.v[i]; }
        return vv > 0 ? pv / vv : Double.NaN;
    }

    static boolean volReliable(Series s) {
        int from = Math.max(0, s.n - 30), ok = 0;
        for (int i = from; i < s.n; i++) if (s.v[i] > 0) ok++;
        return s.n - from >= 20 && ok >= 0.8 * (s.n - from);
    }

    static double volRatio(Series s, int idx, int period) {
        if (idx < period) return Double.NaN;
        double sum = 0;
        for (int i = idx - period; i < idx; i++) sum += s.v[i];
        double avg = sum / period;
        return avg > 0 ? s.v[idx] / avg : Double.NaN;
    }

    // =========================================================================
    // Higher-timeframe context (1H / 2H / 4H), rebuilt once per closed 1H candle
    // =========================================================================
    static class HtfCtx {
        long bucket;
        Series s1, s4;
        boolean[] valid = new boolean[3];
        int[] emaDir = new int[3], stDir = new int[3], diDir = new int[3];
        double[] adx = new double[3];
        int validCount;
        boolean sideways;
    }

    static HtfCtx buildHtf(Series s1, Series s2, Series s4) {
        HtfCtx h = new HtfCtx();
        h.s1 = s1; h.s4 = s4;
        Series[] ss = {s1, s2, s4};
        for (int k = 0; k < 3; k++) {
            Series s = ss[k];
            if (s == null || s.n < EMA_TREND_SLOW + 5) continue;
            double[] e50 = ema(s.c, EMA_TREND_FAST), e200 = ema(s.c, EMA_TREND_SLOW);
            double[][] a = adx(s.h, s.l, s.c, ADX_PERIOD);
            boolean[] st = supertrend(s.h, s.l, s.c, ST_PERIOD, ST_MULTIPLIER);
            int i = s.n - 1;
            if (nan(e200[i]) || nan(a[0][i])) continue;
            h.valid[k] = true;
            h.emaDir[k] = e50[i] > e200[i] ? 1 : -1;
            h.stDir[k] = st[i] ? 1 : -1;
            h.adx[k] = a[0][i];
            h.diDir[k] = a[1][i] > a[2][i] ? 1 : -1;
            h.validCount++;
        }
        boolean sw = h.validCount > 0;
        for (int k = 0; k < 3; k++) if (h.valid[k] && h.adx[k] >= ADX_SIDEWAYS_MAX) sw = false;
        h.sideways = sw;
        return h;
    }

    // {points, maxPoints} for direction d
    static double[] htfScore(HtfCtx h, int d) {
        double pts = 0, max = 0;
        for (int k = 0; k < 3; k++) {
            if (!h.valid[k]) continue;
            double w = HTF_WEIGHT[k];
            double adxCredit = (h.diDir[k] == d) ? (h.adx[k] >= ADX_TREND_MIN ? 1.0 : (h.adx[k] >= ADX_SIDEWAYS_MAX ? 0.5 : 0)) : 0;
            max += w;
            pts += w * (0.4 * (h.emaDir[k] == d ? 1 : 0) + 0.3 * (h.stDir[k] == d ? 1 : 0) + 0.3 * adxCredit);
        }
        return new double[]{pts, max};
    }

    // =========================================================================
    // 15M context (aggregated from 5M), rebuilt once per closed 15M candle
    // =========================================================================
    static class M15Ctx {
        long bucket;
        Series s;
        double atr, rsi, rsiPrev, vwap, close, volRatio;
        boolean volOk, crossUp, crossDown, stFlipUp, stFlipDown, hl, lh, bosUp, bosDown;
        int emaDir, stDir;
    }

    static M15Ctx buildM15(Series s) {
        if (s == null || s.n < 60) return null;
        M15Ctx m = new M15Ctx();
        m.s = s;
        int n = s.n, i = n - 1;
        double[] e9 = ema(s.c, EMA_FAST), e21 = ema(s.c, EMA_SLOW), r = rsi(s.c, RSI_PERIOD), a = atr(s.h, s.l, s.c, ATR_PERIOD);
        if (nan(e21[i]) || nan(r[i]) || nan(a[i]) || nan(r[i - 2])) return null;
        m.emaDir = e9[i] > e21[i] ? 1 : -1;
        for (int j = i - CROSS_BARS + 1; j <= i; j++) {
            if (nan(e21[j - 1])) continue;
            if (e9[j - 1] <= e21[j - 1] && e9[j] > e21[j]) m.crossUp = true;
            if (e9[j - 1] >= e21[j - 1] && e9[j] < e21[j]) m.crossDown = true;
        }
        m.rsi = r[i]; m.rsiPrev = r[i - 2];
        boolean[] st = supertrend(s.h, s.l, s.c, ST_PERIOD, ST_MULTIPLIER);
        m.stDir = st[i] ? 1 : -1;
        int fl = lastFlip(st);
        m.stFlipUp = fl >= n - 1 - ST_FLIP_BARS && st[i];
        m.stFlipDown = fl >= n - 1 - ST_FLIP_BARS && !st[i];
        List<Integer> ph = pivotsHigh(s.h, 2, Math.max(2, n - 60), n - 3), pl = pivotsLow(s.l, 2, Math.max(2, n - 60), n - 3);
        m.hl = pl.size() >= 2 && s.l[pl.get(pl.size() - 1)] > s.l[pl.get(pl.size() - 2)];
        m.lh = ph.size() >= 2 && s.h[ph.get(ph.size() - 1)] < s.h[ph.get(ph.size() - 2)];
        m.bosUp = !ph.isEmpty() && s.c[i] > s.h[ph.get(ph.size() - 1)];
        m.bosDown = !pl.isEmpty() && s.c[i] < s.l[pl.get(pl.size() - 1)];
        m.vwap = vwapSession(s);
        m.volOk = volReliable(s);
        m.volRatio = volRatio(s, i, VOL_AVG_PERIOD);
        m.atr = a[i]; m.close = s.c[i];
        return m;
    }

    // {points, maxPoints}; max 30
    static double[] score15(M15Ctx m, int d) {
        double pts = 0, max = 0;
        max += 6; pts += (m.emaDir == d ? 4 : 0) + ((d > 0 ? m.crossUp : m.crossDown) ? 2 : 0);
        max += 6;
        boolean rising = m.rsi > m.rsiPrev;
        if (d > 0) pts += (m.rsi >= 50 && rising) ? 6 : ((m.rsi >= 45 && rising) || m.rsi >= 50) ? 3 : 0;
        else       pts += (m.rsi <= 50 && !rising) ? 6 : ((m.rsi <= 55 && !rising) || m.rsi <= 50) ? 3 : 0;
        max += 6; pts += m.stDir == d ? 6 : 0;
        max += 6; pts += d > 0 ? ((m.hl ? 3 : 0) + (m.bosUp ? 3 : 0)) : ((m.lh ? 3 : 0) + (m.bosDown ? 3 : 0));
        if (!nan(m.vwap)) { max += 3; pts += (d > 0 ? m.close > m.vwap : m.close < m.vwap) ? 3 : 0; }
        if (m.volOk && !nan(m.volRatio)) { max += 3; pts += m.volRatio >= 1.1 ? 3 : (m.volRatio >= 0.9 ? 1.5 : 0); }
        return new double[]{pts, max};
    }

    static boolean reversal15(M15Ctx m, int d) { return d > 0 ? (m.stFlipUp && m.bosUp) : (m.stFlipDown && m.bosDown); }

    // 15M must agree with the trade direction (EMA9/21 and Supertrend) for trend-aligned entries
    static boolean m15Aligned(M15Ctx m, int d) { return m.emaDir == d && m.stDir == d; }

    // =========================================================================
    // 5M trigger (closed candle): breakout of a confirmed swing, or a held retest of a broken swing
    // =========================================================================
    static class Trig {
        int type;                 // 0 none, 1 breakout, 2 retest
        double level, pts, max, atr5, rsi5, volRatio, cs;
        boolean volOk;
        String reject;
    }

    static Trig evalTrigger(Series s, int d, double price) {
        Trig r = new Trig();
        int n = s.n;
        if (n < 60) { r.reject = "noData5m"; return r; }
        int i = n - 1;
        double[] atrA = atr(s.h, s.l, s.c, ATR_PERIOD), rsiA = rsi(s.c, RSI_PERIOD), e21 = ema(s.c, EMA_SLOW);
        double a = atrA[i];
        if (!(a > 0) || nan(rsiA[i]) || nan(rsiA[i - 1]) || nan(e21[i])) { r.reject = "noIndicator5m"; return r; }
        r.atr5 = a; r.rsi5 = rsiA[i];
        double range = s.h[i] - s.l[i];
        if (!(range > 0)) { r.reject = "flatCandle"; return r; }
        if (range > MAX_CANDLE_ATR * a) { r.reject = "extendedCandle"; return r; }
        for (int k = i - 2; k < i; k++) if (s.h[k] - s.l[k] > SPIKE_ATR * a) { r.reject = "abnormalSpike"; return r; }
        if (Math.abs(price - s.c[i]) > PRICE_JUMP_ATR * a) { r.reject = "priceJump"; return r; }
        double ext = d > 0 ? s.c[i] - e21[i] : e21[i] - s.c[i];
        if (ext > OVEREXT_EMA21_ATR * a) { r.reject = "overextended"; return r; }
        if (d > 0 ? r.rsi5 > RSI5_EXTREME : r.rsi5 < 100 - RSI5_EXTREME) { r.reject = "rsiExtreme"; return r; }

        // candle strength 0..1 (strong body, engulfing, or rejection wick)
        double body = Math.abs(s.c[i] - s.o[i]);
        boolean dirCandle = d > 0 ? s.c[i] > s.o[i] : s.c[i] < s.o[i];
        double bodyRatio = body / range;
        double closePos = d > 0 ? (s.c[i] - s.l[i]) / range : (s.h[i] - s.c[i]) / range;
        double wick = d > 0 ? (Math.min(s.o[i], s.c[i]) - s.l[i]) / range : (s.h[i] - Math.max(s.o[i], s.c[i])) / range;
        boolean engulf = d > 0 ? (s.c[i - 1] < s.o[i - 1] && s.c[i] >= s.o[i - 1] && s.o[i] <= s.c[i - 1])
                               : (s.c[i - 1] > s.o[i - 1] && s.c[i] <= s.o[i - 1] && s.o[i] >= s.c[i - 1]);
        double cs = 0;
        if (dirCandle) {
            if (bodyRatio >= 0.6 && closePos >= 0.75) cs = 1.0;
            else if (engulf) cs = 0.9;
            else if (wick >= 0.5 && closePos >= 0.6) cs = 0.8;
            else if (bodyRatio >= 0.4 && closePos >= 0.6) cs = 0.5;
        }
        if (cs <= 0) { r.reject = "weakCandle"; return r; }
        r.cs = cs;

        List<Integer> piv = d > 0 ? pivotsHigh(s.h, SWING_K_5M, Math.max(SWING_K_5M, n - 1 - SWING_LOOKBACK_5M), n - 2 - SWING_K_5M)
                                  : pivotsLow(s.l, SWING_K_5M, Math.max(SWING_K_5M, n - 1 - SWING_LOOKBACK_5M), n - 2 - SWING_K_5M);
        boolean chased = false;
        for (int q = piv.size() - 1; q >= 0 && q >= piv.size() - 3 && r.type == 0; q--) {
            int p = piv.get(q);
            double level = d > 0 ? s.h[p] : s.l[p];
            int b = -1;
            for (int j = p + 1; j <= i; j++) if (d > 0 ? s.c[j] > level : s.c[j] < level) { b = j; break; }
            if (b < 0) continue;
            if (b == i) {
                double chase = d > 0 ? price - level : level - price;
                if (chase > BREAKOUT_MAX_CHASE_ATR * a) { chased = true; continue; }
                r.type = 1; r.level = level;
            } else if (i - b <= RETEST_MAX_BARS) {
                boolean touched = false, held = true;
                for (int j = b + 1; j <= i; j++) {
                    if (d > 0) { if (s.l[j] <= level + RETEST_TOL_ATR * a) touched = true; if (s.c[j] < level - RETEST_TOL_ATR * a) held = false; }
                    else       { if (s.h[j] >= level - RETEST_TOL_ATR * a) touched = true; if (s.c[j] > level + RETEST_TOL_ATR * a) held = false; }
                }
                boolean closeOk = d > 0 ? s.c[i] > level : s.c[i] < level;
                if (touched && held && closeOk) { r.type = 2; r.level = level; }
            }
        }
        if (r.type == 0) { r.reject = chased ? "chasing" : "noTrigger"; return r; }

        boolean rising = rsiA[i] > rsiA[i - 1];
        double rc;
        if (d > 0) rc = (r.rsi5 > 50 && rising) ? 1.0 : (r.rsi5 > 50 ? 0.6 : (r.rsi5 > 45 && rising ? 0.4 : 0));
        else       rc = (r.rsi5 < 50 && !rising) ? 1.0 : (r.rsi5 < 50 ? 0.6 : (r.rsi5 < 55 && !rising ? 0.4 : 0));
        r.volOk = volReliable(s);
        r.volRatio = volRatio(s, i, VOL_AVG_PERIOD);
        // fake-breakout filter: a fresh breakout without a volume spike is skipped (retests are not affected)
        if (r.type == 1 && r.volOk && !nan(r.volRatio) && r.volRatio < BREAKOUT_MIN_VOL_RATIO) { r.reject = "weakBreakoutVol"; return r; }
        r.max = 14 + 9 + 5;
        r.pts = (r.type == 1 ? 14 : 12) + 9 * cs + 5 * rc;
        if (r.volOk && !nan(r.volRatio)) {
            double vc = r.volRatio >= 1.5 ? 1.0 : r.volRatio >= 1.2 ? 0.8 : r.volRatio >= 1.0 ? 0.5 : r.volRatio >= 0.8 ? 0.2 : 0;
            r.max += 6; r.pts += 6 * vc;
        }
        return r;
    }

    // =========================================================================
    // Initial SL / TP plan (swing + ATR, clamped, TP cut before the next 1H/4H S/R)
    // =========================================================================
    static class Plan {
        double slDist, tpDist, swing, roomR;
        String reject;
    }

    static double nearestOpposing(Series s, int k, int lookback, double price, int d) {
        if (s == null || s.n < 2 * k + 3) return Double.NaN;
        int from = Math.max(k, s.n - lookback), to = s.n - 1 - k;
        List<Integer> piv = d > 0 ? pivotsHigh(s.h, k, from, to) : pivotsLow(s.l, k, from, to);
        double best = Double.NaN;
        for (int p : piv) {
            double lv = d > 0 ? s.h[p] : s.l[p];
            if (d > 0 ? lv > price : lv < price) {
                if (nan(best) || (d > 0 ? lv < best : lv > best)) best = lv;
            }
        }
        return best;
    }

    // v6: room to the next S/R expressed in R, capped (no S/R level found -> room is infinite -> used to print "Infinity")
    static double capRoomR(double room, double slDist) {
        if (!(slDist > 0)) return 0;
        double r = room / slDist;
        return Double.isNaN(r) ? 0 : Math.min(r, ROOM_R_CAP);
    }

    static Plan buildPlan(HtfCtx h, M15Ctx m, int d, double price) {
        Plan p = new Plan();
        Series s = m.s;
        int n = s.n;
        double atr15 = m.atr;
        double swing = Double.NaN;
        List<Integer> pv = d > 0 ? pivotsLow(s.l, 2, Math.max(2, n - 45), n - 3) : pivotsHigh(s.h, 2, Math.max(2, n - 45), n - 3);
        for (int q = pv.size() - 1; q >= 0; q--) {
            double v = d > 0 ? s.l[pv.get(q)] : s.h[pv.get(q)];
            if (d > 0 ? v < price : v > price) { swing = v; break; }
        }
        if (nan(swing)) {
            swing = d > 0 ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
            for (int j = Math.max(0, n - 12); j < n; j++) swing = d > 0 ? Math.min(swing, s.l[j]) : Math.max(swing, s.h[j]);
        }
        p.swing = swing;
        double slPrice = d > 0 ? swing - SL_ATR_BUFFER * atr15 : swing + SL_ATR_BUFFER * atr15;
        double dist = Math.abs(price - slPrice);
        double maxD = price * MAX_SL_PCT / 100.0;
        double minD = Math.min(Math.max(price * MIN_SL_PCT / 100.0, MIN_SL_ATR15 * atr15), maxD);
        p.slDist = Math.min(Math.max(dist, minD), maxD);

        double l1 = nearestOpposing(h.s1, 3, 150, price, d), l4 = nearestOpposing(h.s4, 2, 80, price, d);
        double lvl = Double.NaN;
        for (double x : new double[]{l1, l4}) if (!nan(x) && (nan(lvl) || (d > 0 ? x < lvl : x > lvl))) lvl = x;
        double room = nan(lvl) ? Double.POSITIVE_INFINITY : Math.abs(lvl - price);
        p.roomR = capRoomR(room, p.slDist);
        if (p.roomR < MIN_ROOM_R) { p.reject = "nearSR"; return p; }
        p.tpDist = Math.min(TP_R * p.slDist, room - ROOM_BUFFER_ATR * atr15);
        if (p.tpDist < MIN_TP_R * p.slDist) { p.reject = "tpTooSmall"; return p; }
        return p;
    }

    static class SlTp { boolean ok; double sl, tp, risk; String reason = ""; }

    // SL/TP from the ACTUAL average fill price using the planned distances
    static SlTp computeSlTp(boolean isLong, double entry, double slDist, double tpDist, double tick) {
        SlTp r = new SlTp();
        if (!(entry > 0) || !(slDist > 0) || !(tpDist > 0)) { r.reason = "invalid inputs"; return r; }
        double sl = roundToTick(isLong ? entry - slDist : entry + slDist, tick);
        double tp = roundToTick(isLong ? entry + tpDist : entry - tpDist, tick);
        if (isLong ? (sl >= entry || tp <= entry || sl <= 0) : (sl <= entry || tp >= entry || tp <= 0)) { r.reason = "SL/TP collapsed after tick rounding"; return r; }
        r.ok = true; r.sl = sl; r.tp = tp; r.risk = Math.abs(entry - sl);
        return r;
    }

    // =========================================================================
    // Full entry evaluation for one direction
    // =========================================================================
    static class Candidate {
        int dir; boolean ok, counter;
        String code = "";
        double score, price, level;
        Plan plan;
        String ctx = "";
        // v6: score components + context, copied into the TradeInfo for the trade log
        double htfPts, htfMax, m15Pts, m15Max, m5Pts, m5Max;
        int trigType;
        double btcRatio = -1;
        int btc15m;
    }

    static Candidate evaluateEntry(HtfCtx h, M15Ctx m, Series s5, double price, int d) {
        Candidate c = new Candidate();
        c.dir = d; c.price = price;
        double[] hs = htfScore(h, d), ms = score15(m, d);
        double htfRatio = hs[1] > 0 ? hs[0] / hs[1] : 0;
        c.counter = htfRatio < HTF_ALIGN_MIN;
        if (c.counter && !ALLOW_COUNTER_TREND) { c.code = "counterTrendOff"; return c; }
        if (!c.counter && !m15Aligned(m, d)) { c.code = "m15NotAligned"; return c; }
        Trig t = evalTrigger(s5, d, price);
        if (t.reject != null) { c.code = t.reject; return c; }
        c.level = t.level;
        if (c.counter) {
            if (!reversal15(m, d)) { c.code = "counterNoReversal"; return c; }
            boolean volProof = t.volOk && !nan(t.volRatio) ? t.volRatio >= COUNTER_MIN_VOL_RATIO : t.cs >= 0.9;
            if (!volProof) { c.code = "counterNoVolume"; return c; }
        }
        Plan p = buildPlan(h, m, d, price);
        c.plan = p;
        if (p.reject != null) { c.code = p.reject; return c; }
        double roomCredit = p.roomR >= 2.5 ? 1.0 : p.roomR >= 1.8 ? 0.75 : p.roomR >= 1.3 ? 0.5 : 0.3;
        double pts = hs[0] + ms[0] + t.pts + 6 * roomCredit, max = hs[1] + ms[1] + t.max + 6;
        c.score = 100.0 * pts / max;
        c.htfPts = hs[0]; c.htfMax = hs[1]; c.m15Pts = ms[0]; c.m15Max = ms[1];
        c.m5Pts = t.pts + 6 * roomCredit; c.m5Max = t.max + 6; c.trigType = t.type;
        double thr = c.counter ? COUNTER_TREND_SCORE_MIN : ENTRY_SCORE_MIN;
        c.ctx = String.format("score=%.0f(htf=%.0f/%.0f,15m=%.0f/%.0f,5m=%.0f/%.0f)%s trig=%s@%.6f sl=%.2f%% tp=%.2f%% roomR=%.1f",
                c.score, hs[0], hs[1], ms[0], ms[1], t.pts + 6 * roomCredit, t.max + 6, c.counter ? " COUNTER" : "",
                t.type == 1 ? "breakout" : "retest", t.level, p.slDist / price * 100, p.tpDist / price * 100, p.roomR);
        if (c.score < thr) { c.code = "score"; return c; }
        c.ok = true;
        return c;
    }

    // BTC HTF score ratio for direction d (NaN when BTC data is not available)
    static double btcRatio(int d, long now) {
        HtfCtx b = getHtf(BTC_PAIR, now);
        if (b == null || b.validCount < 2) return Double.NaN;
        double[] sc = htfScore(b, d);
        return sc[1] > 0 ? sc[0] / sc[1] : Double.NaN;
    }

    // BTC 15M direction: +1 / -1 when EMA9/21 and Supertrend agree, else 0 (also 0 when unavailable)
    static int btc15mDir(long now) {
        M15Ctx m = getM15(BTC_PAIR, now);
        if (m == null) return 0;
        return m.emaDir == m.stDir ? m.emaDir : 0;
    }

    // BTC market filter: false when BTC's HTF trend is strongly against direction d (alts follow BTC)
    static boolean btcAllows(String pair, int d, long now) {
        if (!BTC_FILTER_ENABLED || BTC_PAIR.equals(pair)) return true;
        double r = btcRatio(d, now);
        return nan(r) || r >= BTC_MIN_ALIGN;                       // no BTC data -> do not block
    }

    // =========================================================================
    // v6 risk controls: same-direction cap + per-pair cooldown
    // =========================================================================
    // Number of currently open positions in the given direction (LIVE: from the persisted trade state, PAPER: paper trades)
    static int sameDirCount(boolean isLong, Set<String> active) {
        int n = 0;
        if (LIVE_TRADING_ENABLED) {
            for (String p : active) { TradeInfo t = tradeState.get(p); if (t != null && t.isLong == isLong) n++; }
        } else {
            for (PaperTrade p : paperTrades.values()) if (p.isLong == isLong) n++;
        }
        return n;
    }

    // Called when a position closed. exitMs = estimated exit time (so a long downtime does not extend the cooldown).
    static void registerCloseCooldown(String pair, boolean slLoss, long exitMs) {
        cooldownUntil.put(pair, exitMs + (slLoss ? PAIR_COOLDOWN_AFTER_SL_MS : PAIR_COOLDOWN_AFTER_CLOSE_MS));
    }

    static boolean pairInCooldown(String pair, long now) {
        return now - lastTradeTime.getOrDefault(pair, 0L) < COOLDOWN_MS || now < cooldownUntil.getOrDefault(pair, 0L);
    }

    // =========================================================================
    // Stop management: break-even ladder + trailing + limited TP extension (pure, unit-tested)
    // =========================================================================
    static class StopMgmt {
        boolean slMove, tpMove, trailActive;
        double newSl, newTp, favR, ladderSl, trailSl;
        int newStage, newTpExt;
        String reason = "";
    }

    static StopMgmt computeManagement(boolean isLong, double entry, double risk, double curSl, double curTp, double extreme,
                                      int stage, boolean trailActive, int tpExt, double tick) {
        StopMgmt m = new StopMgmt();
        m.newSl = curSl; m.newTp = curTp; m.newStage = stage; m.trailActive = trailActive; m.newTpExt = tpExt;
        if (!(entry > 0) || !(risk > 0) || !(curSl > 0) || !(curTp > 0) || !(extreme > 0)) { m.reason = "invalid inputs"; return m; }
        double fav = isLong ? extreme - entry : entry - extreme;
        m.favR = fav / risk;
        double cost = entry * COST_BUFFER_PCT / 100.0;
        double best = curSl;
        if (BREAKEVEN_ENABLED) {
            int reached = 0;
            for (int i = 0; i < LADDER_TRIGGER_R.length; i++) if (m.favR >= LADDER_TRIGGER_R[i] - 1e-9) reached = i + 1;
            if (reached > 0) {
                double off = Math.max(cost, LADDER_LOCK_R[reached - 1] * risk);
                m.ladderSl = roundToTick(isLong ? entry + off : entry - off, tick);
                best = isLong ? Math.max(best, m.ladderSl) : Math.min(best, m.ladderSl);
                m.newStage = Math.max(stage, reached);
            }
        }
        if (TRAILING_ENABLED && (trailActive || m.favR >= TRAIL_ACTIVATION_R - 1e-9)) {
            m.trailActive = true;
            m.trailSl = roundToTick(isLong ? extreme - TRAIL_DISTANCE_R * risk : extreme + TRAIL_DISTANCE_R * risk, tick);
            best = isLong ? Math.max(best, m.trailSl) : Math.min(best, m.trailSl);
        }
        double imp = isLong ? best - curSl : curSl - best;
        boolean stageUp = m.newStage > stage;
        if (imp > tick * 0.5 && (stageUp || imp >= Math.max(tick * 0.5, TRAIL_STEP_R * risk))) { m.slMove = true; m.newSl = best; }
        if (TRAILING_ENABLED && m.trailActive && tpExt < MAX_TP_EXTENSIONS) {
            double toTp = isLong ? curTp - extreme : extreme - curTp;
            if (toTp <= TP_EXTEND_TRIGGER_R * risk + 1e-9) {
                double ntp = roundToTick(isLong ? curTp + TP_EXTEND_R * risk : curTp - TP_EXTEND_R * risk, tick);
                if (isLong ? ntp > curTp : ntp < curTp) { m.tpMove = true; m.newTp = ntp; m.newTpExt = tpExt + 1; }
            }
        }
        m.reason = m.slMove || m.tpMove ? "update" : String.format("no change (favR=%.2f)", m.favR);
        return m;
    }

    static boolean priceDiffers(double a, double b, double tick) { return Math.abs(a - b) > Math.max(tick * 0.5, 1e-12); }

    // =========================================================================
    // Trade state persistence
    // =========================================================================
    static JSONObject tradeToJson(TradeInfo t) {
        JSONObject j = new JSONObject();
        j.put("isLong", t.isLong); j.put("entry", t.entry); j.put("sl", t.sl); j.put("tp", t.tp);
        j.put("initialSl", t.initialSl); j.put("initialTp", t.initialTp); j.put("risk", t.risk);
        j.put("maxPrice", t.maxPrice); j.put("minPrice", t.minPrice); j.put("openedMs", t.openedMs);
        j.put("ctx", t.ctx == null ? "" : t.ctx);
        j.put("lockStage", t.lockStage); j.put("trailActive", t.trailActive); j.put("tpExt", t.tpExt); j.put("lastMoveMs", t.lastMoveMs);
        // v6 bookkeeping
        j.put("cfg", t.cfg == null ? "" : t.cfg);
        j.put("lastSeenMs", t.lastSeenMs); j.put("lastSeenPx", t.lastSeenPx); j.put("signalPx", t.signalPx);
        j.put("openAtEntry", t.openAtEntry); j.put("longsAtEntry", t.longsAtEntry); j.put("shortsAtEntry", t.shortsAtEntry);
        j.put("score", t.score); j.put("htfPts", t.htfPts); j.put("htfMax", t.htfMax);
        j.put("m15Pts", t.m15Pts); j.put("m15Max", t.m15Max); j.put("m5Pts", t.m5Pts); j.put("m5Max", t.m5Max);
        j.put("slPct", t.slPct); j.put("tpPct", t.tpPct); j.put("roomR", t.roomR);
        j.put("btcRatio", t.btcRatio); j.put("btc15m", t.btc15m);
        j.put("trig", t.trig == null ? "" : t.trig); j.put("counter", t.counter);
        return j;
    }

    static TradeInfo tradeFromJson(JSONObject j) {
        TradeInfo t = new TradeInfo();
        t.isLong = j.optBoolean("isLong", true); t.entry = j.optDouble("entry", 0);
        t.sl = j.optDouble("sl", 0); t.tp = j.optDouble("tp", 0);
        t.initialSl = j.optDouble("initialSl", t.sl); t.initialTp = j.optDouble("initialTp", t.tp); t.risk = j.optDouble("risk", 0);
        t.maxPrice = j.optDouble("maxPrice", t.entry); t.minPrice = j.optDouble("minPrice", t.entry);
        t.openedMs = j.optLong("openedMs", 0); t.ctx = j.optString("ctx", "");
        t.lockStage = j.optInt("lockStage", 0); t.trailActive = j.optBoolean("trailActive", false);
        t.tpExt = j.optInt("tpExt", 0); t.lastMoveMs = j.optLong("lastMoveMs", 0);
        t.cfg = j.optString("cfg", "v5");                       // positions saved by the v5 bot have no cfg
        t.lastSeenMs = j.optLong("lastSeenMs", 0); t.lastSeenPx = j.optDouble("lastSeenPx", 0); t.signalPx = j.optDouble("signalPx", 0);
        t.openAtEntry = j.optInt("openAtEntry", 0); t.longsAtEntry = j.optInt("longsAtEntry", 0); t.shortsAtEntry = j.optInt("shortsAtEntry", 0);
        t.score = j.optDouble("score", 0); t.htfPts = j.optDouble("htfPts", 0); t.htfMax = j.optDouble("htfMax", 0);
        t.m15Pts = j.optDouble("m15Pts", 0); t.m15Max = j.optDouble("m15Max", 0); t.m5Pts = j.optDouble("m5Pts", 0); t.m5Max = j.optDouble("m5Max", 0);
        t.slPct = j.optDouble("slPct", 0); t.tpPct = j.optDouble("tpPct", 0); t.roomR = j.optDouble("roomR", 0);
        t.btcRatio = j.optDouble("btcRatio", -1); t.btc15m = j.optInt("btc15m", 0);
        t.trig = j.optString("trig", ""); t.counter = j.optBoolean("counter", false);
        return t;
    }

    private static synchronized void saveTradeState() {
        try {
            JSONObject root = new JSONObject();
            for (Map.Entry<String, TradeInfo> en : tradeState.entrySet()) root.put(en.getKey(), tradeToJson(en.getValue()));
            File target = new File(TRADE_STATE_FILE), tmp = new File(TRADE_STATE_FILE + ".tmp");
            Files.write(tmp.toPath(), root.toString(2).getBytes(StandardCharsets.UTF_8));
            try { Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (Exception e) { Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING); }
        } catch (Exception e) { System.err.println("saveTradeState failed: " + e.getMessage()); }
    }

    private static void loadTradeState() {
        File f = new File(TRADE_STATE_FILE);
        if (!f.exists()) { System.out.println("[STATE] no saved trade state — starting fresh"); return; }
        try {
            JSONObject root = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            for (String pair : root.keySet()) { TradeInfo t = tradeFromJson(root.getJSONObject(pair)); if (t.entry > 0) tradeState.put(pair, t); }
            System.out.println("[STATE] restored " + tradeState.size() + " trade(s): " + tradeState.keySet());
        } catch (Exception e) {
            System.err.println("[STATE] could not read " + TRADE_STATE_FILE + " (" + e.getMessage() + ") — backed up, positions rebuilt from exchange");
            try { Files.move(f.toPath(), new File(TRADE_STATE_FILE + ".corrupt").toPath(), StandardCopyOption.REPLACE_EXISTING); } catch (Exception ignored) {}
        }
    }

    // =========================================================================
    // Quantity (unchanged)
    // =========================================================================
    static class QtyResult { double qty; String reason = ""; }

    static QtyResult sizeQuantity(double rawQty, double price, boolean integerOnly, JSONObject inst) {
        QtyResult r = new QtyResult();
        double inc = inst == null ? 0 : inst.optDouble("quantity_increment", 0);
        double qty;
        if (inc > 0) {
            BigDecimal bi = BigDecimal.valueOf(inc);
            qty = BigDecimal.valueOf(rawQty).divide(bi, 0, RoundingMode.FLOOR).multiply(bi).doubleValue();
        } else qty = integerOnly ? Math.floor(rawQty) : Math.floor(rawQty * 100) / 100.0;
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

    static boolean isPartialFill(double requested, double filled) { return filled > 0 && filled < requested * 0.999; }

    static boolean isErrorResponse(String resp) {
        if (resp == null || resp.trim().isEmpty()) return true;
        String l = resp.toLowerCase();
        return l.contains("err_code_dcx") || l.contains("\"status\":\"error\"") || l.contains("\"code\":4") || l.contains("\"code\":5") || l.startsWith("<");
    }

    static boolean registerSignal(Map<String, Long> registry, String key, long candleOpen) {
        Long prev = registry.get(key);
        if (prev != null && prev == candleOpen) return false;
        registry.put(key, candleOpen);
        return true;
    }

    // =========================================================================
    // Data access with caching (HTF per closed 1H, 15M per closed 15M)
    // =========================================================================
    static class TfCache { Series s; long bucket = -1; long retryAt; }
    private static final Map<String, TfCache> seriesCache = new ConcurrentHashMap<>();
    private static final Map<String, HtfCtx> htfCache = new ConcurrentHashMap<>();
    private static final Map<String, M15Ctx> m15Cache = new ConcurrentHashMap<>();

    static JSONArray fetchCandlesPaged(String pair, String res, int minsPerBar, int count) {
        long nowSec = Instant.now().getEpochSecond();
        if (count <= MAX_BARS_PER_CALL) return getCandlestickRange(pair, res, nowSec - (long) minsPerBar * 60L * count, nowSec);
        List<JSONObject> all = new ArrayList<>();
        long toSec = nowSec;
        int remaining = count;
        boolean first = true;
        while (remaining > 0) {
            int n = Math.min(MAX_BARS_PER_CALL, remaining);
            long fromSec = toSec - (long) minsPerBar * 60L * n;
            JSONArray part = getCandlestickRange(pair, res, fromSec, toSec);
            if (part == null) { if (first) return null; break; }
            for (int i = 0; i < part.length(); i++) all.add(part.getJSONObject(i));
            first = false;
            remaining -= n;
            toSec = fromSec - 1;
            if (part.length() == 0) break;
        }
        JSONArray out = new JSONArray();
        for (JSONObject c : all) out.put(c);
        return out;
    }

    private static long lastRequestAt = 0;
    private static synchronized void throttle() {
        long wait = MIN_REQUEST_GAP_MS - (System.currentTimeMillis() - lastRequestAt);
        if (wait > 0) { try { Thread.sleep(wait); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); } }
        lastRequestAt = System.currentTimeMillis();
    }

    private static JSONArray getCandlestickRange(String pair, String resolution, long from, long to) {
        try {
            throttle();
            String url = PUBLIC_API_URL + "/market_data/candlesticks?pair=" + pair + "&from=" + from + "&to=" + to
                    + "&resolution=" + resolution + "&pcode=f";
            HttpURLConnection conn = openGet(url);
            int code = conn.getResponseCode();
            if (code == 200) {
                JSONObject r = new JSONObject(readStream(conn.getInputStream()));
                if ("ok".equals(r.optString("s"))) return r.getJSONArray("data");
                System.err.println("  Candle s=" + r.optString("s") + " " + pair + "/" + resolution);
            } else System.err.println("  Candle HTTP " + code + " " + pair + "/" + resolution);
        } catch (Exception e) { System.err.println("  getCandlestickRange(" + pair + "/" + resolution + "): " + e.getMessage()); }
        return null;
    }

    // Cached closed-candle series; refreshed when a new `bucketMs` candle has started. Null when data is missing/lagging.
    private static Series cachedSeries(String pair, String res, int mins, int count, long bucketMs, long now) {
        long tfMs = mins * MIN_MS, bucket = alignedStart(now, bucketMs, 0L);
        TfCache c = seriesCache.computeIfAbsent(pair + "|" + res + "|" + bucketMs, k -> new TfCache());
        if (c.bucket == bucket && c.s != null) return c.s;
        if (now < c.retryAt) return null;
        Series s = toSeries(fetchCandlesPaged(pair, res, mins, count), tfMs, now);
        if (s == null || s.n == 0 || s.t[s.n - 1] < bucket - tfMs) { c.retryAt = now + DATA_RETRY_MS; return null; }
        c.s = s; c.bucket = bucket;
        return s;
    }

    static HtfCtx getHtf(String pair, long now) {
        long bucket = alignedStart(now, HOUR_MS, 0L);
        HtfCtx h = htfCache.get(pair);
        if (h != null && h.bucket == bucket) return h;
        Series s1 = cachedSeries(pair, "60", 60, FETCH_1H, HOUR_MS, now);
        if (s1 == null) return null;
        h = buildHtf(s1, aggregate(s1, TWO_HOUR_MS, HOUR_MS), aggregate(s1, FOUR_HOUR_MS, HOUR_MS));
        h.bucket = bucket;
        htfCache.put(pair, h);
        return h;
    }

    static M15Ctx getM15(String pair, long now) {
        long bucket = alignedStart(now, FIFTEEN_MIN_MS, 0L);
        M15Ctx m = m15Cache.get(pair);
        if (m != null && m.bucket == bucket) return m;
        Series s5 = cachedSeries(pair, "5", 5, FETCH_5M_CTX, FIFTEEN_MIN_MS, now);
        if (s5 == null) return null;
        m = buildM15(aggregate(s5, FIFTEEN_MIN_MS, FIVE_MIN_MS));
        if (m == null) return null;
        m.bucket = bucket;
        m15Cache.put(pair, m);
        return m;
    }

    static double getSpreadPct(String pair) {
        try {
            HttpURLConnection c = openGet(PUBLIC_API_URL + "/market_data/orderbook?pair=" + pair);
            if (c.getResponseCode() != 200) return -1;
            JSONObject o = new JSONObject(readStream(c.getInputStream()));
            JSONObject bids = o.optJSONObject("bids"), asks = o.optJSONObject("asks");
            if (bids == null || asks == null) return -1;
            double bb = 0, ba = Double.MAX_VALUE;
            for (String k : bids.keySet()) bb = Math.max(bb, Double.parseDouble(k));
            for (String k : asks.keySet()) ba = Math.min(ba, Double.parseDouble(k));
            if (bb <= 0 || ba == Double.MAX_VALUE) return -1;
            return (ba - bb) / ((ba + bb) / 2.0) * 100.0;
        } catch (Exception e) { return -1; }
    }

    static boolean spreadOk(String pair) {
        double sp = getSpreadPct(pair);
        if (sp < 0) { System.out.println("  [SPREAD] " + pair + " orderbook unavailable -> " + (SPREAD_FAIL_OPEN ? "allowed" : "blocked")); return SPREAD_FAIL_OPEN; }
        boolean ok = sp <= MAX_SPREAD_PCT;
        System.out.printf("  [SPREAD] %s %.3f%% -> %s%n", pair, sp, ok ? "ok" : "TOO WIDE");
        return ok;
    }

    // Fresh 5M candles + live price -> full re-evaluation just before the order
    private static Candidate revalidate(String pair, int d) {
        long now = System.currentTimeMillis();
        Candidate c = new Candidate();
        c.dir = d;
        HtfCtx h = getHtf(pair, now);
        M15Ctx m = getM15(pair, now);
        if (h == null || m == null || h.validCount < 2) { c.code = "ctxMissing"; return c; }
        Series s5 = toSeries(fetchCandlesPaged(pair, "5", 5, FETCH_5M_TRIGGER), FIVE_MIN_MS, now);
        double px = getLastPrice(pair);
        if (s5 == null || s5.n < 60 || !(px > 0)) { c.code = "noData"; return c; }
        if (s5.t[s5.n - 1] < alignedStart(now, FIVE_MIN_MS, 0L) - FIVE_MIN_MS) { c.code = "stale5m"; return c; }
        c = evaluateEntry(h, m, s5, px, d);
        if (c.ok && !btcAllows(pair, d, now)) { c.ok = false; c.code = "btcAgainst"; }
        double br = btcRatio(d, now);
        c.btcRatio = nan(br) ? -1 : br;
        c.btc15m = btc15mDir(now);
        System.out.println("  [REVALIDATE] " + pair + " " + dirName(d) + " -> " + (c.ok ? "PASS" : "FAIL (" + c.code + ")") + " | " + c.ctx);
        return c;
    }

    // =========================================================================
    // Main loop
    // =========================================================================
    public static void main(String[] args) {
        if (args.length > 0 && "--selftest".equals(args[0])) { System.exit(selfTest() ? 0 : 1); return; }
        System.out.println("=== Bot starting (" + CONFIG_VERSION + ") | MODE=" + (LIVE_TRADING_ENABLED ? "LIVE" : "PAPER") + " | SCORE STRATEGY HTF->15M->5M | leverage=" + LEVERAGE
                + "x | symbols=" + COINS_TO_TRADE.length + " | min score " + ENTRY_SCORE_MIN + " (counter " + (ALLOW_COUNTER_TREND ? String.valueOf(COUNTER_TREND_SCORE_MIN) : "OFF") + ") ===");
        System.out.println("=== SL " + MIN_SL_PCT + "-" + MAX_SL_PCT + "% (swing+ATR) TP=" + TP_R + "R | first BE at " + BE_FIRST_TRIGGER_R + "R (SL:BE = 1:" + BE_FIRST_TRIGGER_R + ", locks +" + LADDER_LOCK_R[0] + "R) | break-even ladder " + (BREAKEVEN_ENABLED ? "ON" : "OFF")
                + " | trailing " + (TRAILING_ENABLED ? "ON" : "OFF") + (LIVE_TRADING_ENABLED ? "" : " (not applied in PAPER)") + " | BTC filter " + (BTC_FILTER_ENABLED ? "ON" : "OFF") + " ===");
        System.out.println("=== Risk: max " + MAX_OPEN_POSITIONS + " open, max " + MAX_SAME_DIRECTION_POSITIONS + " per direction | pair cooldown after close " + (PAIR_COOLDOWN_AFTER_CLOSE_MS / 60_000L)
                + "m (after SL " + (PAIR_COOLDOWN_AFTER_SL_MS / 60_000L) + "m) | trade log " + TRADE_LOG_FILE + " ===");
        if (MAX_SL_PCT * LEVERAGE > 40) System.err.println("WARNING: MAX_SL_PCT x LEVERAGE > 40% of margin — liquidation risk is high");
        if (LIVE_TRADING_ENABLED && (API_KEY == null || API_SECRET == null)) { System.err.println("FATAL: DELTA_API_KEY / DELTA_API_SECRET not set"); return; }
        initInstrumentCache();
        if (LIVE_TRADING_ENABLED) loadTradeState();
        while (true) {
            try { runCycle(); }
            catch (Throwable t) { System.err.println("[MAIN-LOOP] Uncaught error, continuing: " + t.getMessage()); t.printStackTrace(); }
            try {
                long now = System.currentTimeMillis();
                TimeUnit.MILLISECONDS.sleep(Math.max(1000L, (now / SCAN_STEP_MS + 1) * SCAN_STEP_MS + SCAN_DELAY_MS - now));
            } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); break; }
        }
    }

    private static long lastTrailPassMs = 0;

    private static void runCycle() {
        Set<String> active;
        if (LIVE_TRADING_ENABLED) {
            active = getActivePositions();
            if (active == null) { System.err.println("Positions API failed — skipping this cycle."); return; }
            System.out.println("Active positions: " + active);
            handleClosedTrades(active);
            runManagementPass();
        } else { active = new HashSet<>(); updatePaperTrades(); }

        if (active.size() + paperTrades.size() >= MAX_OPEN_POSITIONS) System.out.println("MAX_OPEN_POSITIONS reached — skipping entries.");
        else runEntryScan(active);
        if (LIVE_TRADING_ENABLED) ensureTpSlForOpenPositions();
    }

    private static void reject(Map<String, Integer> counts, String reason) { counts.merge(reason, 1, Integer::sum); }

    private static void runEntryScan(Set<String> active) {
        Map<String, Integer> rej = new TreeMap<>();
        int fetched5 = 0, signals = 0;
        for (String pair : COINS_TO_TRADE) {
            try {
                if (LIVE_TRADING_ENABLED && System.currentTimeMillis() - lastTrailPassMs >= TRAIL_CHECK_INTERVAL_MS) runManagementPass();
                if (active.size() + paperTrades.size() >= MAX_OPEN_POSITIONS) break;
                if (active.contains(pair) || paperTrades.containsKey(pair)) continue;   // no duplicate / conflicting position
                if (pairInCooldown(pair, System.currentTimeMillis())) { reject(rej, "pairCooldown"); continue; }
                long now = System.currentTimeMillis();
                long expected5 = alignedStart(now, FIVE_MIN_MS, 0L) - FIVE_MIN_MS;       // newest closed 5M candle
                if (lastEval5m.getOrDefault(pair, 0L) >= expected5) continue;           // this 5M candle was already judged

                HtfCtx h = getHtf(pair, now);
                if (h == null || h.validCount < 2) { reject(rej, "htfData"); continue; }
                if (h.sideways) { reject(rej, "sideways"); continue; }
                M15Ctx m = getM15(pair, now);
                if (m == null) { reject(rej, "m15Data"); continue; }

                List<Integer> dirs = new ArrayList<>();
                boolean btcBlocked = false, dirLimited = false;
                for (int d : new int[]{1, -1}) {
                    if (sameDirCount(d > 0, active) >= MAX_SAME_DIRECTION_POSITIONS) { dirLimited = true; continue; }   // v6: too many trades already on this side
                    double[] a = htfScore(h, d), b = score15(m, d);
                    boolean counter = a[1] > 0 && a[0] / a[1] < HTF_ALIGN_MIN;
                    double ratio = (a[0] + b[0]) / (a[1] + b[1]);
                    boolean pass = false;
                    if (!counter && ratio >= PRE_SCORE_MIN && m15Aligned(m, d)) pass = true;
                    else if (counter && ALLOW_COUNTER_TREND && reversal15(m, d) && b[0] / b[1] >= 0.6) pass = true;
                    if (pass && !btcAllows(pair, d, now)) { btcBlocked = true; pass = false; }
                    if (pass) dirs.add(d);
                }
                if (dirs.isEmpty()) { reject(rej, dirLimited ? "sameDirLimit" : (btcBlocked ? "btcAgainst" : "preScore")); continue; }

                Series s5 = toSeries(fetchCandlesPaged(pair, "5", 5, FETCH_5M_TRIGGER), FIVE_MIN_MS, now);
                fetched5++;
                if (s5 == null || s5.n < 60) { reject(rej, "noData5m"); continue; }
                long last = s5.t[s5.n - 1];
                if (last < expected5 - FIVE_MIN_MS) { reject(rej, "stale5m"); continue; }
                lastEval5m.put(pair, last >= expected5 ? last : expected5 - 1);         // retry next minute if the exchange is a candle late

                double price = getLastPrice(pair);
                if (!(price > 0)) { reject(rej, "noPrice"); continue; }
                Candidate best = null;
                String firstCode = "";
                for (int d : dirs) {
                    Candidate c = evaluateEntry(h, m, s5, price, d);
                    if (c.ok && (best == null || c.score > best.score)) best = c;
                    else if (!c.ok && firstCode.isEmpty()) firstCode = c.code;
                }
                if (best == null) { reject(rej, firstCode); continue; }
                if (!registerSignal(lastSignalCandle, pair + "|" + dirName(best.dir), last)) { reject(rej, "duplicateSignal"); continue; }
                signals++;
                System.out.println("[SIGNAL] " + pair + " " + dirName(best.dir) + " price=" + price + " | " + best.ctx);
                handleSignal(pair, best, active);
            } catch (Exception ex) { System.err.println("Error on " + pair + ": " + ex.getMessage()); }
        }
        System.out.println("=== Scan complete | mode=" + (LIVE_TRADING_ENABLED ? "LIVE" : "PAPER") + " 5M-fetched=" + fetched5 + " signals=" + signals + " | rejections=" + rej + " ===");
    }

    private static void handleSignal(String pair, Candidate sig, Set<String> active) {
        if (LIVE_TRADING_ENABLED) { executeEntry(pair, sig, active); return; }
        Candidate rv = revalidate(pair, sig.dir);
        if (!rv.ok) { System.out.println("SIGNAL CANCELLED - " + pair + " - " + rv.code); return; }
        if (!spreadOk(pair)) { System.out.println("SIGNAL CANCELLED - " + pair + " - spread"); return; }
        paperEntry(pair, rv);
    }

    // =========================================================================
    // PAPER trading (fixed SL/TP resolved from 1M candles; no break-even / trailing)
    // =========================================================================
    private static void paperEntry(String pair, Candidate c) {
        boolean isLong = c.dir > 0;
        SlTp s = computeSlTp(isLong, c.price, c.plan.slDist, c.plan.tpDist, getTickSize(pair));
        if (!s.ok) { System.out.println("[PAPER] SKIP - " + pair + " - " + s.reason); return; }
        PaperTrade p = new PaperTrade();
        p.isLong = isLong; p.entry = c.price; p.sl = s.sl; p.tp = s.tp; p.openedMs = System.currentTimeMillis(); p.ctx = c.ctx;
        paperTrades.put(pair, p);
        lastTradeTime.put(pair, p.openedMs);
        System.out.printf("[PAPER-ENTRY] %s %s entry=%.6f SL=%.6f TP=%.6f | %s%n", pair, isLong ? "LONG" : "SHORT", c.price, s.sl, s.tp, c.ctx);
    }

    private static void updatePaperTrades() {
        if (paperTrades.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (Iterator<Map.Entry<String, PaperTrade>> it = paperTrades.entrySet().iterator(); it.hasNext();) {
            Map.Entry<String, PaperTrade> en = it.next();
            String pair = en.getKey();
            PaperTrade p = en.getValue();
            try {
                long firstFullMin = (p.openedMs / MIN_MS + 1) * MIN_MS;
                int minutes = (int) Math.max(3, (now - firstFullMin) / MIN_MS + 3);
                Series cs = toSeries(fetchCandlesPaged(pair, "1", 1, minutes), MIN_MS, now);
                String result = null;
                double exitPx = 0;
                for (int i = 0; cs != null && i < cs.n; i++) {
                    if (cs.t[i] < firstFullMin) continue;
                    boolean slHit = p.isLong ? cs.l[i] <= p.sl : cs.h[i] >= p.sl;
                    boolean tpHit = p.isLong ? cs.h[i] >= p.tp : cs.l[i] <= p.tp;
                    if (slHit) { result = "SL"; exitPx = p.sl; break; }       // SL first when both touch in one candle
                    if (tpHit) { result = "TP"; exitPx = p.tp; break; }
                }
                if (result == null && now - p.openedMs > PAPER_MAX_HOLD_MS) {
                    double px = getLastPrice(pair);
                    result = "TIMEOUT"; exitPx = px > 0 ? px : p.entry;
                }
                if (result != null) {
                    logPaperClosed(pair, p, result, exitPx);
                    registerCloseCooldown(pair, "SL".equals(result), now);
                    it.remove();
                }
            } catch (Exception e) { System.err.println("updatePaperTrades " + pair + ": " + e.getMessage()); }
        }
    }

    private static synchronized void logPaperClosed(String pair, PaperTrade p, String result, double exitPx) {
        double risk = Math.abs(p.entry - p.sl), move = p.isLong ? exitPx - p.entry : p.entry - exitPx;
        double r = risk > 0 ? move / risk : 0;
        long heldMin = (System.currentTimeMillis() - p.openedMs) / 60_000L;
        System.out.printf("[PAPER-CLOSED] %s %s result=%s entry=%.6f exit=%.6f R=%.2f held=%dmin | %s%n", pair, p.isLong ? "LONG" : "SHORT", result, p.entry, exitPx, r, heldMin, p.ctx);
        if (!LOG_TRADES_CSV) return;
        try {
            File f = new File(PAPER_LOG_FILE);
            boolean fresh = !f.exists();
            try (PrintWriter pw = new PrintWriter(new FileWriter(f, true))) {
                if (fresh) pw.println("closed_utc,pair,direction,result,entry,sl,tp,exit_price,r_multiple,held_min,context");
                pw.printf("%s,%s,%s,%s,%.8f,%.8f,%.8f,%.8f,%.2f,%d,\"%s\"%n", Instant.now(), pair, p.isLong ? "LONG" : "SHORT", result, p.entry, p.sl, p.tp, exitPx, r, heldMin, p.ctx);
            }
        } catch (Exception e) { System.err.println("paper log write failed: " + e.getMessage()); }
    }

    // =========================================================================
    // LIVE order execution (flow unchanged; entry rules/plan come from the score engine)
    // =========================================================================
    private static boolean executeEntry(String pair, Candidate sig, Set<String> active) {
        try {
            boolean isLong = sig.dir > 0;
            // v6: snapshot of the book BEFORE this trade (goes to the trade log)
            int openBefore = active.size(), longsBefore = sameDirCount(true, active), shortsBefore = sameDirCount(false, active);
            Candidate rv = revalidate(pair, sig.dir);
            if (!rv.ok) { System.out.println("SIGNAL CANCELLED - " + pair + " - " + rv.code); return false; }
            if (!spreadOk(pair)) { System.out.println("SIGNAL CANCELLED - " + pair + " - spread too wide"); return false; }
            double currentPrice = rv.price;
            Plan plan = rv.plan;
            String ctx = rv.ctx;
            double tickSize = getTickSize(pair);
            SlTp pre = computeSlTp(isLong, currentPrice, plan.slDist, plan.tpDist, tickSize);
            if (!pre.ok) { System.out.println("SKIP - " + pair + " - " + pre.reason); return false; }
            double rawQty = FIXED_MARGIN / (currentPrice * USDT_INR_RATE);
            QtyResult q = sizeQuantity(rawQty, currentPrice, INTEGER_QTY_PAIRS.contains(pair), instrumentCache.get(pair));
            if (q.qty <= 0) { System.out.println("SKIP - " + pair + " - " + q.reason); return false; }
            double qty = q.qty;

            System.out.println("\n==== " + pair + " — " + (isLong ? "LONG" : "SHORT") + " (score entry) ====");
            System.out.printf("  Price=%.6f preSL=%.6f preTP=%.6f | Qty=%.6f Lev=%dx%n", currentPrice, pre.sl, pre.tp, qty, LEVERAGE);

            JSONObject orderResp = placeFuturesOrder(isLong ? "buy" : "sell", pair, qty, LEVERAGE, "email_notification", "isolated", "INR", currentPrice);
            if (orderResp == null || !orderResp.has("id")) { System.out.println("  Order failed/rejected: " + orderResp); return false; }
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
                    if (cancelled) { System.out.println("  Unfilled order cancelled — no position opened for " + pair); lastTradeTime.remove(pair); }
                    else System.out.println("  WARNING: cancel of order " + orderId + " for " + pair + " NOT confirmed — check manually");
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
            }
            double avgEntry = pos == null ? 0 : pos.optDouble("avg_price", 0);
            if (avgEntry <= 0) { System.out.println("  Could not read the average entry price — safety sweep will protect the position"); active.add(pair); return true; }

            SlTp fin = computeSlTp(isLong, avgEntry, plan.slDist, plan.tpDist, tickSize);
            String posId = pos.optString("id", null);
            if (posId == null) posId = getPositionId(pair);
            if (!fin.ok) {
                System.out.println("  Cannot compute SL/TP from the fill price (" + fin.reason + ") — closing the position");
                if (posId == null || !closePosition(posId)) System.out.println("  CRITICAL: position " + pair + " is open WITHOUT protection — close it manually");
                return false;
            }
            System.out.printf("[ENTRY] %s %s Entry=%.6f SL=%.6f TP=%.6f R=%.6f | %s%n", pair, isLong ? "LONG" : "SHORT", avgEntry, fin.sl, fin.tp, fin.risk, ctx);

            if (posId == null) System.out.println("  Position ID not found after retries — safety sweep will handle it");
            else if (!setTpSlWithRetry(posId, fin.tp, fin.sl, pair) && !stopLossPresent(pair)) {
                System.out.println("  SL protection could NOT be established — closing the position immediately");
                if (!closePosition(posId)) System.out.println("  CRITICAL: position " + pair + " is open WITHOUT stop loss — close it manually");
                return false;
            }
            TradeInfo t = new TradeInfo();
            t.isLong = isLong; t.entry = avgEntry; t.sl = fin.sl; t.tp = fin.tp; t.initialSl = fin.sl; t.initialTp = fin.tp; t.risk = fin.risk;
            t.maxPrice = avgEntry; t.minPrice = avgEntry; t.openedMs = System.currentTimeMillis(); t.ctx = ctx;
            // v6 bookkeeping for the trade log
            t.lastSeenMs = t.openedMs; t.lastSeenPx = avgEntry; t.signalPx = currentPrice;
            t.openAtEntry = openBefore; t.longsAtEntry = longsBefore; t.shortsAtEntry = shortsBefore;
            t.score = rv.score; t.htfPts = rv.htfPts; t.htfMax = rv.htfMax; t.m15Pts = rv.m15Pts; t.m15Max = rv.m15Max; t.m5Pts = rv.m5Pts; t.m5Max = rv.m5Max;
            t.trig = rv.trigType == 1 ? "breakout" : "retest"; t.counter = rv.counter;
            t.slPct = plan.slDist / currentPrice * 100.0; t.tpPct = plan.tpDist / currentPrice * 100.0; t.roomR = plan.roomR;
            t.btcRatio = rv.btcRatio; t.btc15m = rv.btc15m;
            tradeState.put(pair, t);
            saveTradeState();
            active.add(pair);
            return true;
        } catch (Exception e) { System.err.println("executeEntry " + pair + ": " + e.getMessage()); return false; }
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
            } catch (Exception e) { System.err.println("cancelOrder attempt " + attempt + ": " + e.getMessage()); }
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
        } catch (Exception e) { System.err.println("closePosition: " + e.getMessage()); return false; }
    }

    private static boolean stopLossPresent(String pair) {
        try { JSONObject p = findPosition(pair); return p != null && p.optDouble("stop_loss_trigger", 0) > 0; }
        catch (Exception e) { return false; }
    }

    // =========================================================================
    // Closed-trade logging -> console + CSV (v6)
    //   The exit reason is resolved from the 1M candles between "last seen open" and now, so a bot restart / detection
    //   delay no longer turns a normal SL/TP into "OTHER". Labels:
    //     TP | SL | SL_LOCKED_TRAIL          : confirmed by 1M candles (SL wins when both touched in one candle -> both_touched=true)
    //     TP_approx | SL_approx | ..._approx : candles not conclusive, last price sits next to that level
    //     NO_LEVEL_TOUCHED                   : neither level was touched -> closed manually / liquidated / by the exchange
    //     UNKNOWN_NO_DATA                    : no candles and no price
    // =========================================================================
    static class ExitInfo { String reason = "NONE"; double price; long atMs; boolean both; }

    // Pure: first 1M candle (open time >= fromMs) that touched SL or TP. SL wins when both are touched in the same candle.
    static ExitInfo resolveExit(boolean isLong, double sl, double tp, Series cs, long fromMs) {
        ExitInfo e = new ExitInfo();
        if (cs == null) return e;
        for (int i = 0; i < cs.n; i++) {
            if (cs.t[i] < fromMs) continue;
            boolean slHit = isLong ? cs.l[i] <= sl : cs.h[i] >= sl;
            boolean tpHit = isLong ? cs.h[i] >= tp : cs.l[i] <= tp;
            if (slHit || tpHit) {
                e.both = slHit && tpHit;
                if (slHit) { e.reason = "SL"; e.price = sl; } else { e.reason = "TP"; e.price = tp; }
                e.atMs = cs.t[i];
                return e;
            }
        }
        return e;
    }

    static String classifyExit(TradeInfo t, double lastPx) {
        if (lastPx <= 0 || t.risk <= 0) return "UNKNOWN_NO_DATA";
        double dTp = Math.abs(lastPx - t.tp), dSl = Math.abs(lastPx - t.sl);
        if (Math.min(dTp, dSl) > 0.35 * t.risk) return "NO_LEVEL_TOUCHED";
        return dTp < dSl ? "TP_approx" : (t.lockStage > 0 || t.trailActive ? "SL_LOCKED_TRAIL_approx" : "SL_approx");
    }

    static double[] mfeMaeR(TradeInfo t) {
        if (t.risk <= 0) return new double[]{0, 0};
        double mfe = t.isLong ? (t.maxPrice - t.entry) : (t.entry - t.minPrice);
        double mae = t.isLong ? (t.entry - t.minPrice) : (t.maxPrice - t.entry);
        return new double[]{Math.max(0, mfe) / t.risk, Math.max(0, mae) / t.risk};
    }

    private static ExitInfo resolveClosedExit(String pair, TradeInfo t, double lastPx, long now) {
        long from = t.lastSeenMs > 0 ? t.lastSeenMs : t.openedMs;
        ExitInfo e = new ExitInfo();
        if (from > 0 && t.sl > 0 && t.tp > 0) {
            long fromMin = alignedStart(from, MIN_MS, 0L);
            int minutes = (int) Math.min(1500L, Math.max(5L, (now - fromMin) / MIN_MS + 3));
            Series cs = toSeries(fetchCandlesPaged(pair, "1", 1, minutes), MIN_MS, now);
            if (cs != null) e = resolveExit(t.isLong, t.sl, t.tp, cs, fromMin);
            else e.reason = "UNKNOWN_NO_DATA";
        } else e.reason = "UNKNOWN_NO_DATA";
        if ("SL".equals(e.reason) && (t.lockStage > 0 || t.trailActive)) e.reason = "SL_LOCKED_TRAIL";
        if ("NONE".equals(e.reason) || "UNKNOWN_NO_DATA".equals(e.reason)) {        // candles not conclusive -> price proximity
            String approx = classifyExit(t, lastPx);
            e.reason = approx;
            e.price = approx.startsWith("TP") ? t.tp : approx.startsWith("SL") ? t.sl : lastPx;
        }
        return e;
    }

    static final String TRADE_LOG_HEADER = "closed_utc,pair,direction,exit_reason,config,entry,signal_px,entry_slip_pct,initial_sl,final_sl,initial_tp,final_tp,"
            + "exit_px_est,last_price,exit_r_est,net_r_est,held_min,detect_delay_min,mfe_r,mae_r,lock_stage,trail_active,tp_ext,"
            + "score,htf_pts,htf_max,m15_pts,m15_max,m5_pts,m5_max,trigger,counter,sl_pct,tp_pct,room_r,"
            + "open_at_entry,longs_at_entry,shorts_at_entry,btc_htf_ratio,btc_15m_dir,both_touched,context";

    static String f(double v, int dp) { return (Double.isNaN(v) || Double.isInfinite(v)) ? "" : String.format(Locale.US, "%." + dp + "f", v); }
    static String fp(double v, int dp) { return v > 0 ? f(v, dp) : ""; }     // blank for 0 / unavailable

    // One CSV row (pure). exit_r_est / net_r_est use the estimated exit price (SL/TP level or last price) and the cost model.
    static String tradeLogRow(String closedUtc, String pair, TradeInfo t, String reason, double exitPx, double lastPx,
                              long heldMin, long delayMin, boolean both) {
        double[] m = mfeMaeR(t);
        double slip = t.signalPx > 0 ? (t.isLong ? t.entry - t.signalPx : t.signalPx - t.entry) / t.signalPx * 100.0 : Double.NaN;
        double move = t.isLong ? exitPx - t.entry : t.entry - exitPx;
        double exitR = (t.risk > 0 && exitPx > 0) ? move / t.risk : Double.NaN;
        double costR = t.risk > 0 ? (t.entry * COST_BUFFER_PCT / 100.0) / t.risk : Double.NaN;
        double netR = (nan(exitR) || nan(costR)) ? Double.NaN : exitR - costR;
        List<String> c = new ArrayList<>();
        c.add(closedUtc); c.add(pair); c.add(t.isLong ? "LONG" : "SHORT"); c.add(reason); c.add(t.cfg == null ? "" : t.cfg);
        c.add(f(t.entry, 8)); c.add(fp(t.signalPx, 8)); c.add(f(slip, 3));
        c.add(f(t.initialSl, 8)); c.add(f(t.sl, 8)); c.add(f(t.initialTp, 8)); c.add(f(t.tp, 8));
        c.add(fp(exitPx, 8)); c.add(fp(lastPx, 8)); c.add(f(exitR, 2)); c.add(f(netR, 2));
        c.add(String.valueOf(heldMin)); c.add(String.valueOf(delayMin));
        c.add(f(m[0], 2)); c.add(f(m[1], 2)); c.add(String.valueOf(t.lockStage)); c.add(String.valueOf(t.trailActive)); c.add(String.valueOf(t.tpExt));
        c.add(fp(t.score, 1)); c.add(fp(t.htfPts, 1)); c.add(fp(t.htfMax, 1)); c.add(fp(t.m15Pts, 1)); c.add(fp(t.m15Max, 1)); c.add(fp(t.m5Pts, 1)); c.add(fp(t.m5Max, 1));
        c.add(t.trig == null ? "" : t.trig); c.add(String.valueOf(t.counter));
        c.add(fp(t.slPct, 2)); c.add(fp(t.tpPct, 2)); c.add(fp(t.roomR, 1));
        c.add(String.valueOf(t.openAtEntry)); c.add(String.valueOf(t.longsAtEntry)); c.add(String.valueOf(t.shortsAtEntry));
        c.add(t.btcRatio >= 0 ? f(t.btcRatio, 2) : ""); c.add(String.valueOf(t.btc15m)); c.add(String.valueOf(both));
        return String.join(",", c) + ",\"" + (t.ctx == null ? "" : t.ctx.replace("\"", "\"\"")) + "\"";
    }

    private static synchronized void logClosedTrade(String pair, TradeInfo t, double lastPx) {
        long now = System.currentTimeMillis();
        ExitInfo e = resolveClosedExit(pair, t, lastPx, now);
        long endMs = e.atMs > 0 ? e.atMs : now;
        long heldMin = t.openedMs > 0 ? Math.max(0, (endMs - t.openedMs) / 60_000L) : -1;
        long delayMin = Math.max(0, (now - endMs) / 60_000L);
        double[] m = mfeMaeR(t);
        System.out.printf("[CLOSED] %s %s exit=%s%s entry=%.6f initialSl=%.6f finalSl=%.6f finalTp=%.6f exitPx~%.6f lastPx=%.6f held=%dmin delay=%dmin stage=%d trail=%s tpExt=%d MFE=%.2fR MAE=%.2fR | %s%n",
                pair, t.isLong ? "LONG" : "SHORT", e.reason, e.both ? "(both touched)" : "", t.entry, t.initialSl, t.sl, t.tp, e.price, lastPx, heldMin, delayMin,
                t.lockStage, t.trailActive, t.tpExt, m[0], m[1], t.ctx);
        registerCloseCooldown(pair, "SL".equals(e.reason) || "SL_approx".equals(e.reason), endMs);
        if (!LOG_TRADES_CSV) return;
        try {
            File f = new File(TRADE_LOG_FILE);
            boolean fresh = !f.exists();
            try (PrintWriter pw = new PrintWriter(new FileWriter(f, true))) {
                if (fresh) pw.println(TRADE_LOG_HEADER);
                pw.println(tradeLogRow(Instant.now().toString(), pair, t, e.reason, e.price, lastPx, heldMin, delayMin, e.both));
            }
        } catch (Exception ex) { System.err.println("trade log write failed: " + ex.getMessage()); }
    }

    private static void handleClosedTrades(Set<String> active) {
        boolean removed = false;
        for (Iterator<Map.Entry<String, TradeInfo>> it = tradeState.entrySet().iterator(); it.hasNext();) {
            Map.Entry<String, TradeInfo> en = it.next();
            if (active.contains(en.getKey())) continue;
            logClosedTrade(en.getKey(), en.getValue(), getLastPrice(en.getKey()));
            it.remove();
            removed = true;
        }
        if (removed) saveTradeState();
    }

    // =========================================================================
    // LIVE position management (break-even ladder + trailing + TP extension)
    //   - exchange = source of truth for current SL/TP (manual edits are adopted)
    //   - SL and TP replaced in ONE create_tpsl call, then verified by reading the position back
    // =========================================================================
    private static boolean isActivePosition(JSONObject p) {
        return p.optDouble("active_pos", 0) > 0 || p.optDouble("locked_margin", 0) > 0 || p.optDouble("avg_price", 0) > 0
                || p.optDouble("take_profit_trigger", 0) > 0 || p.optDouble("stop_loss_trigger", 0) > 0;
    }

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
        } catch (Exception e) { System.err.println("getOpenPositionsMap: " + e.getMessage()); return null; }
    }

    private static void runManagementPass() {
        lastTrailPassMs = System.currentTimeMillis();
        Map<String, JSONObject> positions = getOpenPositionsMap();
        if (positions == null) { System.err.println("[MANAGE] positions API failed — pass skipped (no changes made)"); return; }
        for (Map.Entry<String, JSONObject> en : positions.entrySet()) {
            try { manageOne(en.getKey(), en.getValue()); }
            catch (Exception e) { System.err.println("[MANAGE] " + en.getKey() + ": " + e.getMessage()); }
        }
    }

    private static void manageOne(String pair, JSONObject pos) {
        double signedQty = pos.optDouble("active_pos", 0), avg = pos.optDouble("avg_price", 0);
        if (Math.abs(signedQty) <= 0 || avg <= 0) return;
        double px = getLastPrice(pair);
        if (px <= 0) return;
        double tick = getTickSize(pair);
        double exSl = pos.optDouble("stop_loss_trigger", 0), exTp = pos.optDouble("take_profit_trigger", 0);
        long now = System.currentTimeMillis();
        boolean dirty = false;

        // 1. state: reuse the persisted one or rebuild from the exchange
        TradeInfo t = tradeState.get(pair);
        if (t != null && t.entry > 0 && Math.abs(avg - t.entry) / t.entry * 100.0 > ENTRY_MISMATCH_PCT) {
            System.out.printf("  [MANAGE] %s saved entry %.6f differs from exchange avg %.6f — treating as a NEW position%n", pair, t.entry, avg);
            t = null;
        }
        if (t == null) {
            t = new TradeInfo();
            t.isLong = signedQty >= 0;
            t.entry = avg; t.sl = exSl; t.tp = exTp; t.initialSl = exSl; t.initialTp = exTp;
            boolean slOnLossSide = exSl > 0 && (t.isLong ? exSl < avg : exSl > avg);
            t.risk = slOnLossSide ? Math.abs(avg - exSl) : avg * RECOVER_R_PCT / 100.0;
            t.maxPrice = Math.max(avg, px); t.minPrice = Math.min(avg, px);
            t.openedMs = now; t.ctx = "recovered-from-exchange";
            t.lastSeenMs = now; t.lastSeenPx = px;
            tradeState.put(pair, t);
            dirty = true;
        } else if (priceDiffers(avg, t.entry, tick)) {
            t.entry = avg; dirty = true;
        }
        if (px > t.maxPrice) { t.maxPrice = px; dirty = true; }
        if (px < t.minPrice) { t.minPrice = px; dirty = true; }
        // v6: remember when/where the position was last seen open (used to resolve the exit reason after it closes)
        if (now - t.lastSeenMs >= 60_000L) dirty = true;           // persisted at most once a minute
        t.lastSeenMs = now; t.lastSeenPx = px;
        if (!BREAKEVEN_ENABLED && !TRAILING_ENABLED) { if (dirty) saveTradeState(); return; }

        // 2. exchange must hold BOTH levels (the safety sweep restores missing ones)
        if (exSl <= 0 || exTp <= 0) {
            if (now - t.lastStatusLogMs >= TRAIL_STATUS_LOG_MS) { System.out.println("  [MANAGE] " + pair + " SL or TP missing on exchange — paused, safety sweep will restore"); t.lastStatusLogMs = now; }
            if (dirty) saveTradeState();
            return;
        }
        // 3. manual change on the exchange -> adopt it
        if (t.sl <= 0 || t.tp <= 0 || priceDiffers(exSl, t.sl, tick) || priceDiffers(exTp, t.tp, tick)) {
            if (t.sl > 0 && t.tp > 0) System.out.printf("  [MANAGE] %s SL/TP changed on the exchange (manual?): SL %.6f -> %.6f | TP %.6f -> %.6f — adopted%n", pair, t.sl, exSl, t.tp, exTp);
            t.sl = exSl; t.tp = exTp; dirty = true;
        }

        // 4. decision
        double extreme = t.isLong ? t.maxPrice : t.minPrice;
        StopMgmt m = computeManagement(t.isLong, t.entry, t.risk, t.sl, t.tp, extreme, t.lockStage, t.trailActive, t.tpExt, tick);
        if (!m.slMove && !m.tpMove) {
            if (m.newStage != t.lockStage || m.trailActive != t.trailActive) { t.lockStage = m.newStage; t.trailActive = m.trailActive; dirty = true; }
            if (now - t.lastStatusLogMs >= TRAIL_STATUS_LOG_MS) {
                System.out.printf("  [MANAGE] %s %s entry=%.6f price=%.6f best=%.6f SL=%.6f TP=%.6f favR=%.2f stage=%d trail=%s%n", pair, t.isLong ? "LONG" : "SHORT",
                        t.entry, px, extreme, t.sl, t.tp, m.favR, t.lockStage, t.trailActive ? "ACTIVE" : "waiting");
                t.lastStatusLogMs = now;
            }
            if (dirty) saveTradeState();
            return;
        }
        if (now < t.nextTryMs) { if (dirty) saveTradeState(); return; }
        boolean sane = t.isLong ? (m.newSl < px && m.newTp > px) : (m.newSl > px && m.newTp < px);   // never a stop on the wrong side of the market
        if (!sane) {
            if (now - t.lastStatusLogMs >= TRAIL_STATUS_LOG_MS) { System.out.printf("  [MANAGE] %s update skipped: SL=%.6f / TP=%.6f not valid against price %.6f%n", pair, m.newSl, m.newTp, px); t.lastStatusLogMs = now; }
            if (dirty) saveTradeState();
            return;
        }
        String posId = pos.optString("id", null);
        if (posId == null || posId.isEmpty()) { System.out.println("  [MANAGE] " + pair + " position id missing — cannot update TP/SL"); if (dirty) saveTradeState(); return; }

        double oldSl = t.sl, oldTp = t.tp;
        boolean ok = replaceTpSl(posId, pair, m.newTp, m.newSl, tick, oldTp, oldSl);
        if (ok) {
            t.sl = m.newSl; t.tp = m.newTp; t.lockStage = m.newStage; t.trailActive = m.trailActive; t.tpExt = m.newTpExt;
            t.lastMoveMs = now; t.nextTryMs = 0;
            System.out.printf("  [MANAGE-UPDATE] %s %s entry=%.6f price=%.6f best=%.6f favR=%.2f | SL %.6f -> %.6f | TP %.6f -> %.6f | stage=%d trail=%s tpExt=%d%n",
                    pair, t.isLong ? "LONG" : "SHORT", t.entry, px, extreme, m.favR, oldSl, t.sl, oldTp, t.tp, t.lockStage, t.trailActive, t.tpExt);
        } else {
            t.nextTryMs = now + TRAIL_RETRY_BACKOFF_MS;
            System.out.printf("  [MANAGE] %s update FAILED — keeping SL=%.6f TP=%.6f, retry in %ds%n", pair, t.sl, t.tp, TRAIL_RETRY_BACKOFF_MS / 1000);
        }
        saveTradeState();
    }

    // Sets BOTH levels with one create_tpsl call and confirms by reading the position back. Never cancels first.
    private static boolean replaceTpSl(String posId, String pair, double tp, double sl, double tick, double oldTp, double oldSl) {
        for (int attempt = 1; attempt <= TRAIL_UPDATE_MAX_RETRIES; attempt++) {
            setTpSl(posId, tp, sl, pair);
            sleepMs(TRAIL_VERIFY_DELAY_MS);
            try {
                JSONObject p = findPosition(pair);
                if (p == null || Math.abs(p.optDouble("active_pos", 0)) <= 0) { System.out.println("  [MANAGE] " + pair + " position is closed — update abandoned"); return false; }
                double nowSl = p.optDouble("stop_loss_trigger", 0), nowTp = p.optDouble("take_profit_trigger", 0);
                if (!priceDiffers(nowSl, sl, tick) && !priceDiffers(nowTp, tp, tick)) return true;
                System.out.printf("  [MANAGE] %s update not visible yet (attempt %d/%d): exchange SL=%.6f TP=%.6f, wanted SL=%.6f TP=%.6f%n", pair, attempt, TRAIL_UPDATE_MAX_RETRIES, nowSl, nowTp, sl, tp);
            } catch (Exception e) { System.err.println("  [MANAGE] verify " + pair + " attempt " + attempt + ": " + e.getMessage()); }
        }
        System.out.println("  [MANAGE] " + pair + " could not confirm the new TP/SL — restoring the previous levels");
        setTpSl(posId, oldTp, oldSl, pair);
        return false;
    }

    // Reliability-only sweep: restores MISSING TP/SL only, never modifies one that is set.
    private static void ensureTpSlForOpenPositions() {
        try {
            Set<String> stillOpen = getActivePositions();
            if (stillOpen == null) return;
            for (String pair : stillOpen) {
                JSONObject pos = findPosition(pair);
                if (pos == null) continue;
                double avgPrice = pos.optDouble("avg_price", 0), tpTrig = pos.optDouble("take_profit_trigger", 0), slTrig = pos.optDouble("stop_loss_trigger", 0);
                if (avgPrice <= 0 || (tpTrig > 0 && slTrig > 0)) continue;
                System.out.println("  [SWEEP] " + pair + " missing TP and/or SL — restoring protection...");
                boolean isLong = pos.optDouble("active_pos", 0) >= 0;
                double tick = getTickSize(pair);
                TradeInfo trade = tradeState.get(pair);
                double sl, tp;
                if (trade != null && trade.sl > 0 && trade.tp > 0) { sl = trade.sl; tp = trade.tp; }
                else {
                    double dist = avgPrice * RECOVER_R_PCT / 100.0;
                    SlTp fb = computeSlTp(isLong, avgPrice, dist, dist * TP_R, tick);
                    if (!fb.ok) { System.out.println("  [SWEEP] " + pair + " — cannot compute SL/TP: " + fb.reason); continue; }
                    sl = fb.sl; tp = fb.tp;
                    System.out.println("  [SWEEP] " + pair + " — no recorded state; using " + RECOVER_R_PCT + "% SL / " + TP_R + "R TP from average entry");
                }
                if (slTrig > 0) sl = slTrig;
                if (tpTrig > 0) tp = tpTrig;
                String posId = pos.optString("id", null);
                if (posId == null) { System.out.println("  [SWEEP] " + pair + " — position ID missing, cannot set TP/SL"); continue; }
                System.out.printf("  [SWEEP] %s SL=%.6f TP=%.6f%n", pair, sl, tp);
                setTpSlWithRetry(posId, tp, sl, pair);
                if (trade == null) {
                    TradeInfo nt = new TradeInfo();
                    nt.isLong = isLong; nt.entry = avgPrice; nt.sl = sl; nt.tp = tp; nt.initialSl = sl; nt.initialTp = tp;
                    nt.risk = avgPrice * RECOVER_R_PCT / 100.0; nt.maxPrice = avgPrice; nt.minPrice = avgPrice;
                    nt.openedMs = System.currentTimeMillis(); nt.ctx = "recovered-after-restart";
                    nt.lastSeenMs = nt.openedMs; nt.lastSeenPx = avgPrice;
                    tradeState.put(pair, nt);
                } else { trade.sl = sl; trade.tp = tp; }
                saveTradeState();
            }
        } catch (Exception e) { System.err.println("ensureTpSlForOpenPositions: " + e.getMessage()); }
    }

    private static boolean setTpSlWithRetry(String posId, double tp, double sl, String pair) {
        for (int attempt = 1; attempt <= TPSL_MAX_RETRIES; attempt++) {
            setTpSl(posId, tp, sl, pair);
            sleepMs(TPSL_RETRY_DELAY_MS);
            try {
                JSONObject pos = findPosition(pair);
                if (pos != null && pos.optDouble("take_profit_trigger", 0) > 0 && pos.optDouble("stop_loss_trigger", 0) > 0) {
                    System.out.println("  TP/SL confirmed set on attempt " + attempt + " for " + pair);
                    return true;
                }
            } catch (Exception ignored) {}
            System.out.println("  TP/SL not confirmed yet (attempt " + attempt + "/" + TPSL_MAX_RETRIES + ") for " + pair + " — retrying...");
        }
        System.out.println("  WARNING: TP/SL could not be confirmed for " + pair + " — will be retried by the next cycle's safety sweep");
        return false;
    }

    // =========================================================================
    // Tick rounding
    // =========================================================================
    private static BigDecimal roundToTickBD(double price, double tick) {
        if (tick <= 0) return BigDecimal.valueOf(price);
        BigDecimal bdTick = BigDecimal.valueOf(tick);
        BigDecimal multiples = BigDecimal.valueOf(price).divide(bdTick, 0, RoundingMode.HALF_UP);
        return multiples.multiply(bdTick).setScale(bdTick.scale(), RoundingMode.HALF_UP);
    }
    static double roundToTick(double price, double tick) { return tick <= 0 ? price : roundToTickBD(price, tick).doubleValue(); }

    // =========================================================================
    // Exchange plumbing (unchanged)
    // =========================================================================
    private static void initInstrumentCache() {
        try {
            long now = System.currentTimeMillis();
            if (now - lastCacheUpdate < TICK_CACHE_TTL_MS) return;
            instrumentCache.clear();
            System.out.println("Refreshing instrument cache...");
            JSONArray pairs = new JSONArray(publicGet(BASE_URL + "/exchange/v1/derivatives/futures/data/active_instruments"));
            for (int i = 0; i < pairs.length(); i++) {
                String p = pairs.getString(i);
                try {
                    String raw = publicGet(BASE_URL + "/exchange/v1/derivatives/futures/data/instrument?pair=" + p);
                    instrumentCache.put(p, new JSONObject(raw).getJSONObject("instrument"));
                } catch (Exception ignored) {}
            }
            lastCacheUpdate = now;
            System.out.println("Instruments cached: " + instrumentCache.size());
        } catch (Exception e) { System.err.println("initInstrumentCache: " + e.getMessage()); }
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
            if (pos != null && pos.optDouble("avg_price", 0) > 0) return pos.getDouble("avg_price");
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
                return r.startsWith("[") ? new JSONArray(r).getJSONObject(0).getDouble("p") : new JSONObject(r).getDouble("p");
            }
        } catch (Exception e) { System.err.println("getLastPrice(" + pair + "): " + e.getMessage()); }
        return 0;
    }

    public static JSONObject placeFuturesOrder(String side, String pair, double qty, int lev, String notif,
                                               String marginType, String marginCcy, double currentPrice) {
        try {
            double rawLimitPrice = "buy".equalsIgnoreCase(side) ? currentPrice * (1 + LIMIT_ORDER_BUFFER_PCT) : currentPrice * (1 - LIMIT_ORDER_BUFFER_PCT);
            BigDecimal limitPriceBD = roundToTickBD(rawLimitPrice, getTickSize(pair));
            JSONObject order = new JSONObject();
            order.put("side", side.toLowerCase());
            order.put("pair", pair);
            order.put("order_type", "limit_order");
            order.put("price", limitPriceBD);
            order.put("total_quantity", qty);
            order.put("leverage", lev);
            order.put("notification", notif);
            order.put("time_in_force", "good_till_cancel");
            order.put("hidden", false);
            order.put("post_only", false);
            order.put("position_margin_type", marginType);
            order.put("margin_currency_short_name", marginCcy);
            JSONObject body = new JSONObject();
            body.put("timestamp", Instant.now().toEpochMilli());
            body.put("order", order);
            String resp = authPost(BASE_URL + "/exchange/v1/derivatives/futures/orders/create", body.toString());
            return resp.startsWith("[") ? new JSONArray(resp).getJSONObject(0) : new JSONObject(resp);
        } catch (Exception e) { System.err.println("placeFuturesOrder: " + e.getMessage()); return null; }
    }

    public static void setTpSl(String posId, double tp, double sl, String pair) {
        try {
            double tick = getTickSize(pair);
            BigDecimal rtp = roundToTickBD(tp, tick), rsl = roundToTickBD(sl, tick);
            JSONObject tpObj = new JSONObject();
            tpObj.put("stop_price", rtp); tpObj.put("limit_price", rtp); tpObj.put("order_type", "take_profit_market");
            JSONObject slObj = new JSONObject();
            slObj.put("stop_price", rsl); slObj.put("limit_price", rsl); slObj.put("order_type", "stop_market");
            JSONObject payload = new JSONObject();
            payload.put("timestamp", Instant.now().toEpochMilli());
            payload.put("id", posId);
            payload.put("take_profit", tpObj);
            payload.put("stop_loss", slObj);
            String resp = authPost(BASE_URL + "/exchange/v1/derivatives/futures/positions/create_tpsl", payload.toString());
            JSONObject r = new JSONObject(resp);
            System.out.println(r.has("err_code_dcx") ? "  TP/SL error: " + r : "  TP/SL set successfully!");
        } catch (Exception e) { System.err.println("setTpSl: " + e.getMessage()); }
    }

    public static String getPositionId(String pair) {
        for (int attempt = 1; attempt <= POSITION_ID_MAX_RETRIES; attempt++) {
            try { JSONObject p = findPosition(pair); if (p != null && p.has("id")) return p.getString("id"); }
            catch (Exception e) { System.err.println("getPositionId attempt " + attempt + ": " + e.getMessage()); }
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
                JSONObject p = arr.getJSONObject(i);
                String pair = p.optString("pair", "");
                if (isActivePosition(p)) {
                    System.out.printf("  %s | qty=%.2f | entry=%.6f | TP=%.4f | SL=%.4f%n", pair, p.optDouble("active_pos", 0), p.optDouble("avg_price", 0),
                            p.optDouble("take_profit_trigger", 0), p.optDouble("stop_loss_trigger", 0));
                    active.add(pair);
                }
            }
            return active;
        } catch (Exception e) { System.err.println("getActivePositions: " + e.getMessage()); return null; }
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
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("X-AUTH-APIKEY", API_KEY);
        c.setRequestProperty("X-AUTH-SIGNATURE", sign(json));
        c.setConnectTimeout(10_000);
        c.setReadTimeout(10_000);
        c.setDoOutput(true);
        try (OutputStream os = c.getOutputStream()) { os.write(json.getBytes(StandardCharsets.UTF_8)); }
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
            StringBuilder sb = new StringBuilder();
            for (byte x : mac.doFinal(payload.getBytes(StandardCharsets.UTF_8))) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) { throw new RuntimeException("HMAC sign failed", e); }
    }

    public static String generateHmacSHA256(String secret, String payload) { return sign(payload); }

    // =========================================================================
    // OFFLINE SELF-TESTS (no network, no API keys):  --selftest
    // =========================================================================
    private static int testsRun = 0, testsFailed = 0;
    private static void check(String name, boolean cond) {
        testsRun++;
        if (!cond) testsFailed++;
        System.out.println((cond ? "  PASS  " : "  FAIL  ") + name);
    }

    private static Series trendSeries(int n, boolean up, long step) {
        Series s = new Series(n);
        long t0 = 1_760_000_000_000L / step * step;
        for (int i = 0; i < n; i++) {
            double o = up ? 100 + i * 0.5 : 400 - i * 0.5, c = up ? o + 0.45 : o - 0.45;
            if (i % 7 == 0) c = up ? o + 0.2 : o - 0.2;
            s.t[i] = t0 + i * step; s.o[i] = o; s.c[i] = c;
            s.h[i] = Math.max(o, c) + 0.05; s.l[i] = Math.min(o, c) - 0.05; s.v[i] = 10 + (i % 5);
        }
        return s;
    }

    static boolean selfTest() {
        testsRun = 0; testsFailed = 0;

        System.out.println("[Test] Closed-candle filter + aggregation");
        long t0 = (1_760_000_000_000L / HOUR_MS) * HOUR_MS, now = t0 + 10 * HOUR_MS + 30 * MIN_MS;
        JSONArray raw = new JSONArray();
        for (int i = 0; i < 11; i++) {
            JSONObject c = new JSONObject();
            c.put("time", t0 + i * HOUR_MS); c.put("open", 100 + i); c.put("high", 102 + i); c.put("low", 99 + i); c.put("close", 101 + i); c.put("volume", 5);
            raw.put(c);
        }
        Series s1 = toSeries(raw, HOUR_MS, now);
        check("forming 1H candle (i=10) is dropped", s1 != null && s1.n == 10 && s1.t[9] == t0 + 9 * HOUR_MS);
        Series s2 = aggregate(s1, TWO_HOUR_MS, HOUR_MS);
        boolean aligned = (t0 / TWO_HOUR_MS) * TWO_HOUR_MS == t0;
        check("2H aggregation keeps only complete aligned buckets", s2 != null && s2.n == (aligned ? 5 : 4));
        check("2H OHLC merged", s2 != null && s2.n > 0 && s2.h[0] >= s2.l[0] && s2.v[0] == 10);

        System.out.println("[Test] Indicators");
        double[] flat = new double[50]; Arrays.fill(flat, 5);
        check("EMA of a constant = constant", Math.abs(ema(flat, 9)[49] - 5) < 1e-9);
        Series up = trendSeries(300, true, FIVE_MIN_MS), dn = trendSeries(300, false, FIVE_MIN_MS);
        check("RSI strong uptrend > 70, downtrend < 30", rsi(up.c, 14)[299] > 70 && rsi(dn.c, 14)[299] < 30);
        double[][] au = adx(up.h, up.l, up.c, 14), ad = adx(dn.h, dn.l, dn.c, 14);
        check("ADX strong trend > 25 with +DI > -DI", au[0][299] > 25 && au[1][299] > au[2][299]);
        check("ADX downtrend: -DI > +DI", ad[0][299] > 25 && ad[2][299] > ad[1][299]);
        check("Supertrend: uptrend bull / downtrend bear", supertrend(up.h, up.l, up.c, 10, 3.0)[299] && !supertrend(dn.h, dn.l, dn.c, 10, 3.0)[299]);
        double[] zz = {1, 2, 3, 5, 3, 2, 1, 2, 3, 4, 3, 2};
        List<Integer> pk = pivotsHigh(zz, 2, 0, zz.length);
        check("pivot high detected at the peak (index 3)", pk.contains(3));

        System.out.println("[Test] HTF / 15M scoring");
        HtfCtx h = new HtfCtx();
        for (int k = 0; k < 3; k++) { h.valid[k] = true; h.emaDir[k] = 1; h.stDir[k] = 1; h.diDir[k] = 1; h.adx[k] = 30; }
        double[] hl = htfScore(h, 1), hs = htfScore(h, -1);
        check("fully bullish HTF -> LONG 30/30, SHORT 0", Math.abs(hl[0] - 30) < 1e-9 && Math.abs(hl[1] - 30) < 1e-9 && hs[0] == 0);
        M15Ctx m = new M15Ctx();
        m.emaDir = 1; m.crossUp = true; m.rsi = 58; m.rsiPrev = 52; m.stDir = 1; m.hl = true; m.bosUp = true; m.vwap = 99; m.close = 100; m.volOk = true; m.volRatio = 1.3;
        double[] ml = score15(m, 1);
        check("perfect 15M long = 30/30", Math.abs(ml[0] - 30) < 1e-9 && Math.abs(ml[1] - 30) < 1e-9);
        check("same 15M scores low for SHORT", score15(m, -1)[0] < 5);
        check("15M alignment gate: long ok, short blocked", m15Aligned(m, 1) && !m15Aligned(m, -1));

        System.out.println("[Test] 5M trigger (fresh breakout above a confirmed swing high)");
        Series s5 = new Series(80);
        long b5 = (1_760_000_000_000L / FIVE_MIN_MS) * FIVE_MIN_MS;
        for (int i = 0; i < 80; i++) {
            double o = 100, c = 100 + (i % 2 == 0 ? 0.5 : -0.5);
            s5.t[i] = b5 + i * FIVE_MIN_MS; s5.o[i] = o; s5.c[i] = c; s5.h[i] = Math.max(o, c) + 0.3; s5.l[i] = Math.min(o, c) - 0.3; s5.v[i] = 1;
        }
        s5.h[70] = 102.0;
        s5.o[79] = 101.8; s5.c[79] = 102.8; s5.h[79] = 102.85; s5.l[79] = 101.75; s5.v[79] = 2;
        Trig tr = evalTrigger(s5, 1, 102.8);
        check("breakout detected, level = swing high 102.0", tr.reject == null && tr.type == 1 && Math.abs(tr.level - 102.0) < 1e-9);
        check("live price 3+ ATR away -> priceJump reject", "priceJump".equals(evalTrigger(s5, 1, 110).reject));
        check("same candle gives no SHORT trigger", evalTrigger(s5, -1, 102.8).reject != null);
        s5.v[79] = 1;   // no volume spike on the breakout candle
        check("breakout without volume spike -> weakBreakoutVol", "weakBreakoutVol".equals(evalTrigger(s5, 1, 102.8).reject));
        s5.v[79] = 2;

        System.out.println("[Test] SL/TP from the actual fill");
        SlTp l = computeSlTp(true, 100.0, 2.0, 3.0, 0.01), s = computeSlTp(false, 100.0, 2.0, 3.0, 0.01);
        check("LONG SL 98 / TP 103", l.ok && Math.abs(l.sl - 98) < 1e-9 && Math.abs(l.tp - 103) < 1e-9 && Math.abs(l.risk - 2) < 1e-9);
        check("SHORT SL 102 / TP 97", s.ok && Math.abs(s.sl - 102) < 1e-9 && Math.abs(s.tp - 97) < 1e-9);
        check("zero distance rejected", !computeSlTp(true, 100.0, 0, 3, 0.01).ok);
        check("roomR cap: no S/R (infinite room) -> ROOM_R_CAP, finite room -> room/SL", capRoomR(Double.POSITIVE_INFINITY, 2) == ROOM_R_CAP
                && Math.abs(capRoomR(6, 2) - 3) < 1e-9 && capRoomR(5, 0) == 0);

        System.out.println("[Test] Break-even ladder + trailing (LONG, entry 100, R=2, SL 98, TP 104); first BE at 1R, locks +0.2R");
        double tk = 0.01, cost = 100 * COST_BUFFER_PCT / 100.0, lock1 = Math.max(cost, LADDER_LOCK_R[0] * 2);
        check("stage-1 lock is a real profit lock (> costs)", LADDER_LOCK_R[0] > 0 && lock1 > cost);
        StopMgmt a0 = computeManagement(true, 100, 2, 98, 104, 101.5, 0, false, 0, tk);
        check("0.75R -> nothing (first BE only at 1R)", !a0.slMove && !a0.tpMove && a0.newStage == 0);
        StopMgmt a1 = computeManagement(true, 100, 2, 98, 104, 102.0, 0, false, 0, tk);
        check("1.0R (== SL distance) -> SL to entry + max(costs, 0.2R)", a1.slMove && Math.abs(a1.newSl - (100 + lock1)) < 0.011 && a1.newStage == 1 && !a1.trailActive);
        StopMgmt a2 = computeManagement(true, 100, 2, a1.newSl, 104, 103.0, 1, false, 0, tk);
        check("1.5R -> trailing active, SL >= best - 0.6R, stage 2", a2.slMove && a2.trailActive && a2.newSl >= 103.0 - TRAIL_DISTANCE_R * 2 - 0.011 && a2.newStage == 2);
        StopMgmt a3 = computeManagement(true, 100, 2, 101.8, 104, 103.0, 2, true, 0, tk);
        check("price pulls back: SL never moves backwards", !a3.slMove && a3.newSl == 101.8);
        StopMgmt a4 = computeManagement(true, 100, 2, 101.0, 104, 103.6, 2, true, 0, tk);
        check("near TP with trailing: SL up AND TP extended once", a4.slMove && a4.tpMove && a4.newTp > 104 && a4.newTpExt == 1);
        StopMgmt a5 = computeManagement(true, 100, 2, 102.4, 104, 103.9, 3, true, MAX_TP_EXTENSIONS, tk);
        check("TP extension capped at MAX_TP_EXTENSIONS", !a5.tpMove);
        StopMgmt a6 = computeManagement(true, 100, 2, 103.0, 105, 103.5, 3, true, 0, tk);
        check("manual SL already tighter than ladder/trail -> kept", !a6.slMove && a6.newSl == 103.0);
        StopMgmt b1 = computeManagement(false, 100, 2, 102, 96, 98.0, 0, false, 0, tk);
        check("SHORT 1.0R -> SL to entry - max(costs, 0.2R)", b1.slMove && Math.abs(b1.newSl - (100 - lock1)) < 0.011);
        StopMgmt b2 = computeManagement(false, 100, 2, b1.newSl, 96, 97.0, 1, false, 0, tk);
        check("SHORT 1.5R -> trailing SL moves DOWN", b2.slMove && b2.trailActive && b2.newSl < b1.newSl);
        StopMgmt b3 = computeManagement(false, 100, 2, b2.newSl, 96, 97.0, 2, true, 0, tk);
        check("SHORT price bounces: SL does not move up", !b3.slMove);

        System.out.println("[Test] State persistence / quantity / helpers");
        TradeInfo pt = new TradeInfo();
        pt.isLong = false; pt.entry = 123.456; pt.sl = 125.0; pt.tp = 120.5; pt.initialSl = 126.0; pt.initialTp = 119.0; pt.risk = 2.5;
        pt.maxPrice = 124.0; pt.minPrice = 119.9; pt.openedMs = 1_760_000_000_000L; pt.ctx = "score=71"; pt.lockStage = 3; pt.trailActive = true; pt.tpExt = 2; pt.lastMoveMs = 5;
        pt.lastSeenMs = 1_760_000_100_000L; pt.lastSeenPx = 121.5; pt.signalPx = 123.4; pt.openAtEntry = 3; pt.longsAtEntry = 1; pt.shortsAtEntry = 2;
        pt.score = 82.5; pt.htfPts = 24; pt.htfMax = 30; pt.m15Pts = 22; pt.m15Max = 30; pt.m5Pts = 30; pt.m5Max = 40; pt.trig = "retest";
        pt.slPct = 1.5; pt.tpPct = 3.0; pt.roomR = 20; pt.btcRatio = 0.62; pt.btc15m = -1; pt.counter = false;
        TradeInfo rt = tradeFromJson(new JSONObject(tradeToJson(pt).toString()));
        check("all management fields survive save/load", !rt.isLong && rt.entry == pt.entry && rt.risk == pt.risk && rt.lockStage == 3 && rt.trailActive && rt.tpExt == 2
                && rt.initialTp == pt.initialTp && rt.initialSl == pt.initialSl && rt.ctx.equals(pt.ctx));
        check("v6 trade-log fields survive save/load", rt.lastSeenMs == pt.lastSeenMs && rt.signalPx == pt.signalPx && rt.openAtEntry == 3 && rt.longsAtEntry == 1
                && rt.shortsAtEntry == 2 && rt.score == 82.5 && rt.htfPts == 24 && rt.m5Max == 40 && "retest".equals(rt.trig) && rt.btcRatio == 0.62 && rt.btc15m == -1
                && rt.roomR == 20 && CONFIG_VERSION.equals(rt.cfg));
        TradeInfo old = tradeFromJson(new JSONObject("{\"isLong\":true,\"entry\":10,\"sl\":9,\"tp\":12,\"risk\":1}"));
        check("state saved by the v5 bot still loads (new fields default)", old.entry == 10 && old.lastSeenMs == 0 && old.btcRatio == -1 && "v5".equals(old.cfg));
        JSONObject inst = new JSONObject("{\"quantity_increment\":0.1,\"min_quantity\":1,\"min_notional\":5}");
        check("12.34 floors to 12.3", Math.abs(sizeQuantity(12.34, 100, false, inst).qty - 12.3) < 1e-9);
        check("0.57 rejected (below min quantity)", sizeQuantity(0.57, 100, false, inst).qty == 0);
        check("integer-only pair floors", sizeQuantity(7.9, 1, true, null).qty == 7.0);
        Map<String, Long> reg = new HashMap<>();
        check("duplicate signal blocked per closed 5M candle", registerSignal(reg, "X|LONG", 1000L) && !registerSignal(reg, "X|LONG", 1000L) && registerSignal(reg, "X|LONG", 1300L));
        check("partial fill / error helpers", isPartialFill(10, 4) && !isPartialFill(10, 10) && isErrorResponse("{\"err_code_dcx\":\"x\"}") && !isErrorResponse("{\"message\":\"success\"}"));

        System.out.println("[Test] v6 exit resolution from 1M candles");
        long m0 = (1_760_000_000_000L / MIN_MS) * MIN_MS;
        Series cs = new Series(4);
        for (int i = 0; i < 4; i++) { cs.t[i] = m0 + i * MIN_MS; cs.o[i] = 100; cs.c[i] = 100; cs.h[i] = 101; cs.l[i] = 99.5; cs.v[i] = 1; }
        check("no level touched -> NONE", "NONE".equals(resolveExit(true, 98, 104, cs, m0).reason));
        cs.h[2] = 104.5;
        ExitInfo ex = resolveExit(true, 98, 104, cs, m0);
        check("LONG: TP touched in candle 2 -> TP @104", "TP".equals(ex.reason) && ex.price == 104 && ex.atMs == m0 + 2 * MIN_MS && !ex.both);
        cs.l[2] = 97.5;
        ex = resolveExit(true, 98, 104, cs, m0);
        check("both touched in one candle -> SL wins, both flagged", "SL".equals(ex.reason) && ex.price == 98 && ex.both);
        check("candles before fromMs are ignored", "NONE".equals(resolveExit(true, 98, 104, cs, m0 + 3 * MIN_MS).reason));
        Series cs2 = new Series(3);
        for (int i = 0; i < 3; i++) { cs2.t[i] = m0 + i * MIN_MS; cs2.h[i] = 101; cs2.l[i] = 99.5; }
        cs2.l[1] = 95.8;
        ex = resolveExit(false, 102, 96, cs2, m0);
        check("SHORT: low pierces TP 96 in candle 1 -> TP", "TP".equals(ex.reason) && ex.price == 96 && ex.atMs == m0 + MIN_MS);
        cs2.h[2] = 102.2;
        check("SHORT: high >= SL 102 -> SL", "SL".equals(resolveExit(false, 102, 96, cs2, m0 + 2 * MIN_MS).reason));
        TradeInfo ct = new TradeInfo();
        ct.isLong = true; ct.entry = 100; ct.sl = 98; ct.tp = 104; ct.risk = 2;
        check("approx classifier: far from both levels -> NO_LEVEL_TOUCHED", "NO_LEVEL_TOUCHED".equals(classifyExit(ct, 101)));
        check("approx classifier: next to TP / next to SL / no price", "TP_approx".equals(classifyExit(ct, 103.8)) && "SL_approx".equals(classifyExit(ct, 98.1))
                && "UNKNOWN_NO_DATA".equals(classifyExit(ct, 0)));
        ct.lockStage = 1;
        check("approx classifier: SL with a lock stage -> SL_LOCKED_TRAIL_approx", "SL_LOCKED_TRAIL_approx".equals(classifyExit(ct, 98.1)));

        System.out.println("[Test] v6 same-direction cap + pair cooldown");
        tradeState.clear();
        for (String p : new String[]{"A", "B"}) { TradeInfo x = new TradeInfo(); x.isLong = true; tradeState.put(p, x); }
        TradeInfo xs = new TradeInfo(); xs.isLong = false; tradeState.put("C", xs);
        Set<String> act = new HashSet<>(Arrays.asList("A", "B", "C"));
        check("sameDirCount counts per direction", sameDirCount(true, act) == 2 && sameDirCount(false, act) == 1);
        act.remove("A");
        check("closed position stops counting", sameDirCount(true, act) == 1);
        tradeState.clear();
        long t1 = 10_000_000_000L;
        registerCloseCooldown("P1", true, t1);
        check("cooldown after SL lasts PAIR_COOLDOWN_AFTER_SL_MS", pairInCooldown("P1", t1 + PAIR_COOLDOWN_AFTER_SL_MS - 1) && !pairInCooldown("P1", t1 + PAIR_COOLDOWN_AFTER_SL_MS));
        registerCloseCooldown("P2", false, t1);
        check("cooldown after TP/trail/other lasts PAIR_COOLDOWN_AFTER_CLOSE_MS", pairInCooldown("P2", t1 + PAIR_COOLDOWN_AFTER_CLOSE_MS - 1) && !pairInCooldown("P2", t1 + PAIR_COOLDOWN_AFTER_CLOSE_MS));
        check("pair without a close is not in cooldown", !pairInCooldown("P3", t1));
        cooldownUntil.clear();

        System.out.println("[Test] v6 trade-log row");
        TradeInfo lt = new TradeInfo();
        lt.isLong = false; lt.entry = 100; lt.signalPx = 99.9; lt.sl = 101.5; lt.initialSl = 101.5; lt.tp = 97; lt.initialTp = 97; lt.risk = 1.5;
        lt.maxPrice = 100.4; lt.minPrice = 98.9; lt.score = 85.3; lt.htfPts = 24; lt.htfMax = 30; lt.m15Pts = 25; lt.m15Max = 30; lt.m5Pts = 33; lt.m5Max = 40;
        lt.trig = "retest"; lt.slPct = 1.5; lt.tpPct = 3.0; lt.roomR = 20; lt.btcRatio = 0.7; lt.btc15m = -1; lt.openAtEntry = 2; lt.longsAtEntry = 0; lt.shortsAtEntry = 2;
        lt.ctx = "score=85(htf=24/30,15m=25/30,5m=33/40) trig=retest@99.5 sl=1.50% tp=3.00% roomR=20.0";
        String row = tradeLogRow("2026-11-20T10:00:00Z", "B-TEST_USDT", lt, "TP", 97, 97.1, 95, 2, false);
        String prefix = row.substring(0, row.indexOf(",\""));
        check("CSV row has exactly as many fields as the header", prefix.split(",", -1).length == TRADE_LOG_HEADER.split(",").length - 1);
        check("CSV row: exit_r_est for a SHORT TP = +2.00R, context quoted last", row.contains(",2.00,") && row.endsWith("\"") && row.contains("B-TEST_USDT,SHORT,TP,"));
        TradeInfo bare = new TradeInfo();
        bare.isLong = true; bare.entry = 10; bare.sl = 9; bare.tp = 12; bare.risk = 1; bare.ctx = "recovered-from-exchange";
        String row2 = tradeLogRow("2026-11-20T10:00:00Z", "B-REC_USDT", bare, "NO_LEVEL_TOUCHED", 0, 0, -1, 0, false);
        check("recovered trade (no meta, no exit price) still gives a valid row", row2.substring(0, row2.indexOf(",\"")).split(",", -1).length == TRADE_LOG_HEADER.split(",").length - 1);

        System.out.println("\nSelf-test: " + (testsRun - testsFailed) + "/" + testsRun + " passed");
        return testsFailed == 0;
    }
}
