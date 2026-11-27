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
// HYBRID TREND-FOLLOWING ENTRY  +  PORTFOLIO-LEVEL PROFIT MANAGEMENT V2
//
//   ENTRY (LONG shown, SHORT = exact mirror), all decisions on CLOSED candles, live price only for extension checks:
//     mandatory : 4H Supertrend GREEN + 1H Supertrend GREEN, 15M EMA9 > EMA21, a 5M trigger
//                 (pullback/retest + confirmed bullish close, OR fresh confirmed swing-high breakout on a closed candle),
//                 fresh data / no price spike / liquidity / not extended / not chased
//     scored    : HTF 25 + 15M EMA 20 + ADX 15 + 5M trigger 20 + volume 10 + BTC 10 = 100  (ENTRY_MIN_SCORE to qualify)
//     The old ST_CONTRARIAN (countertrend) mode is removed. Revalidated on fresh data immediately before the order.
//
//   SL / TP = fixed % from the ACTUAL average filled entry price.            [UNCHANGED]
//   BREAK-EVEN + PROFIT LOCK per position (BREAKEVEN_ENABLED)                  [UNCHANGED]
//   DYNAMIC TRAILING per position (TRAILING_ENABLED)                           [UNCHANGED]
//
//   PORTFOLIO V2 (works alongside the per-position logic, never touches a position's SL/TP):
//     hard profit target  |  portfolio breakeven  |  portfolio trailing profit floor  |  max-loss limit  |  close-all with
//     verification + retry  |  persisted state (portfolio_state_v1.json)  |  entry gating (liquidation / cooldown / limits)
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
    static final double  FIXED_SL_PERCENT = 3.0;
    static final double  FIXED_TP_PERCENT = 10.0;

    // =========================================================================
    // Break-even + Profit Lock  (LIVE mode only)  -- SL only, TP is never changed by this feature
    // =========================================================================
    static final boolean BREAKEVEN_ENABLED = true;           // MASTER SWITCH: false = break-even / profit-lock OFF
    static final double  BREAKEVEN_TRIGGER_PERCENT = 4.0;    // favourable move (% of entry) that activates break-even
    static final double  PROFIT_LOCK_PERCENT       = 2.0;    // SL goes to entry +PROFIT_LOCK% (LONG) / entry -PROFIT_LOCK% (SHORT). 0 = exact break-even

    // =========================================================================
    // Dynamic trailing SL / TP  (LIVE mode only)
    // =========================================================================
    static final boolean TRAILING_ENABLED = true;            // MASTER SWITCH: false = fixed SL/TP only, no trailing
    static final double  TRAIL_ACTIVATION_PERCENT = 5.0;     // trailing starts after price moves this % in profit
    static final double  TRAIL_STEP_PERCENT       = 0.15;     // SL/TP are moved only when price advanced this % beyond the last trail point
    static final long    TRAIL_CHECK_INTERVAL_MS  = 5_000L; // how often open positions are checked for trailing
    static final int     TRAIL_UPDATE_MAX_RETRIES = 3;       // SL/TP replace attempts per update
    static final long    TRAIL_VERIFY_DELAY_MS    = 800;  // wait before reading the position back after an update
    static final long    TRAIL_RETRY_BACKOFF_MS   = 15_000L; // after a failed update, wait before trying again
    static final long    TRAIL_STATUS_LOG_MS      = 60_000L; // status log per position at most once per this interval
    static final double  TRAIL_ENTRY_MISMATCH_PCT = 0.3;     // saved state whose entry differs more than this from the exchange = old/other position
    static final String  TRADE_STATE_FILE = "trade_state_v4.json";

    // =========================================================================
    // PORTFOLIO MANAGEMENT V2  (LIVE and PAPER)                                              [NEW]
    //   All portfolio percentages are % of the BASIS = your CURRENT LOCKED MARGIN (no capital value needs to be configured):
    //     LOCKED MARGIN = sum over all open positions of |qty| * avgEntry / leverage   (read live from the exchange positions)
    //     e.g. locked margin INR 5000: 3% = INR 150, 4% = 200, 5% = 250  -> the targets scale automatically as positions open/close
    //   Leveraged notional is never used as a basis. PnL is in USDT internally; INR = USDT * USDT_INR_RATE.
    // =========================================================================
    static final boolean PORTFOLIO_PROFIT_TARGET_ENABLED = true;    // hard target: close ALL when NET portfolio profit >= target
    static final double  PORTFOLIO_PROFIT_TARGET_PERCENT = 2.0;     // 3.0 / 4.0 / 5.0 ...
    // (basis = current locked margin, computed live - there is no capital constant to set)
    static final long    PORTFOLIO_CHECK_INTERVAL_MS     = 5_000L;  // how often the portfolio is evaluated

    static final boolean PORTFOLIO_BREAKEVEN_ENABLED         = true;
    static final double  PORTFOLIO_BREAKEVEN_TRIGGER_PERCENT = 6.0; // net profit (% of basis) that activates portfolio protection
    static final double  PORTFOLIO_BREAKEVEN_LOCK_PERCENT    = 4.0; // protected net profit floor (% of basis) once activated. 0 = true breakeven after costs

    static final boolean PORTFOLIO_TRAILING_ENABLED            = true;
    static final double  PORTFOLIO_TRAILING_ACTIVATION_PERCENT = 6.0;  // trailing starts when the peak net profit reaches this (% of basis)
    static final double  PORTFOLIO_TRAILING_GIVEBACK_PERCENT   = 20.0; // allowed giveback = this % OF THE PEAK profit: floor = max(lock, peak - peak*giveback)
    static final long    PORTFOLIO_TRAILING_CHECK_INTERVAL_MS  = 5_000L; // the portfolio pass runs every min(CHECK, TRAILING_CHECK) ms

    static final boolean PORTFOLIO_MAX_LOSS_ENABLED            = false;
    static final double  PORTFOLIO_MAX_LOSS_PERCENT            = 10.0;  // close ALL + pause entries when net loss reaches this (% of basis)
    static final int     PORTFOLIO_MAX_OPEN_POSITIONS          = 600;    // no new entry above this many open positions (<= 0 disables)

    static final long    PORTFOLIO_REENTRY_PAUSE_MS      = 0L; //30_000L;      // cooldown after a successful close-all
    static final long    PORTFOLIO_RISK_PAUSE_MS         = 15L * 60_000L; // entry pause after a PORTFOLIO_RISK_LIMIT exit
    static final double  PORTFOLIO_FEE_RATE_PERCENT      = 0.05;         // estimated taker fee per side (% of notional) - VERIFY your CoinDCX tier
    static final double  PORTFOLIO_FEE_GST_PERCENT       = 18.0;         // GST on fees (% of the fee) - set 0 if not applicable to you
    static final long    PORTFOLIO_CLOSE_RETRY_INTERVAL_MS   = 6_000L;   // min gap between two close requests for the same position
    static final long    PORTFOLIO_CLOSE_VERIFY_DELAY_MS     = 1_500L;   // wait before reading positions back after close requests
    static final long    PORTFOLIO_LIQUIDATION_LOOP_MAX_MS   = 600_000L; // a running liquidation is retried this long per cycle, then every cycle
    static final long    PORTFOLIO_STATUS_LOG_MS         = 60_000L;      // status log at most once per this interval
    static final String  PORTFOLIO_STATE_FILE            = "portfolio_state_v1.json";
    static final long    POSITIONS_CACHE_MS              = 1_500L;       // positions snapshot shared by the trailing + portfolio passes
    static final long    PRICE_CACHE_MS                  = 2_000L;       // live price shared by the trailing + portfolio passes

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

    private static final int MAX_OPEN_POSITIONS = 600;

    private static final int  POSITION_ID_MAX_RETRIES = 5;
    private static final long POSITION_ID_RETRY_DELAY_MS = 1500L;

    // =========================================================================
    // Capital configuration (unchanged except leverage)
    // =========================================================================
    private static final double FIXED_MARGIN = 1000.0;   // legacy sizing: notional INR value = FIXED_MARGIN
    private static final int    LEVERAGE     = 5;        // was 6
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

    static final int ST_MIN_BARS      = 25;   // completed bars needed per timeframe before the Supertrend is trusted
    static final int EMA_MIN_BARS_15M = 40;   // completed 15M bars needed for EMA9/EMA21
    static final int EMA_WINDOW_15M   = 120;  // how many 15M bars are fed into the EMA calculation

    static final int SWING_PIVOT_BARS   = 3;    // a swing high/low needs this many lower highs / higher lows on each side
    static final int SWING_LOOKBACK_5M  = 60;   // how far back the previous confirmed swing may be

    // =========================================================================
    // TREND-FOLLOWING ENTRY CONFIGURATION                                                   [NEW]
    // =========================================================================
    static final boolean HTF_SUPERTREND_ON_CLOSED_CANDLES = true;  // true: 4H/1H Supertrend on COMPLETED bars (no repaint). false: developing bar
    static final boolean ENTRY_REQUIRE_15M_EMA = true;             // mandatory 15M EMA9>EMA21 (LONG) / EMA9<EMA21 (SHORT); false = score only

    // signal quality score (normalised to 100 over the active components)
    static final int ENTRY_MIN_SCORE = 80;
    static final int SCORE_HTF = 25, SCORE_EMA15 = 20, SCORE_ADX = 15, SCORE_TRIGGER = 20, SCORE_VOLUME = 10, SCORE_BTC = 10;

    static final int    ADX_PERIOD = 14;
    static final double ADX_MIN = 20.0;                 // 15M ADX needed for the ADX points (<= 0 disables the requirement)
    static final int    ADX_WINDOW_15M = 150;
    static final int    ATR_PERIOD_5M = 14, ATR_PERIOD_15M = 14;

    // 5M trigger
    static final boolean ENTRY_ALLOW_PULLBACK = true, ENTRY_ALLOW_BREAKOUT = true;
    static final int    PULLBACK_LOOKBACK_5M = 6;       // the retest must have happened within this many closed 5M candles before the confirmation candle
    static final double PULLBACK_ZONE_ATR = 0.30;       // retest = low reached EMA21(5M) + this x ATR5 (LONG)
    static final double PULLBACK_MAX_BREAK_ATR = 0.60;  // pullback invalid if a close went this x ATR5 beyond EMA21 (structure broken)
    static final double ENTRY_MIN_BODY_RATIO = 0.40;    // confirmation candle body / range
    static final int    VOLUME_AVG_BARS = 20;
    static final double VOLUME_MIN_RATIO = 1.2;         // confirmation candle volume >= ratio x average of the previous VOLUME_AVG_BARS
    static final boolean BREAKOUT_REQUIRES_VOLUME = true; // a swing breakout without volume is rejected (weak breakout)

    // anti-chasing / extension (mandatory)
    static final double ENTRY_MAX_EXT_ATR15  = 3.0;     // price must be within this x ATR15 of the 15M EMA21 (the EMA itself lags a strong trend by ~2 ATR)
    static final double ENTRY_MAX_CHASE_ATR5 = 0.6;     // price must be within this x ATR5 of the confirmation candle close
    static final long   ENTRY_MAX_CANDLE_AGE_MS = 180_000L; // enter only in the first 3 min after the 5M confirmation candle closed

    // market safety (mandatory)
    static final double ENTRY_SPIKE_RANGE_ATR5 = 4.0;   // last closed 5M range above this x ATR = abnormal spike
    static final double ENTRY_SPIKE_JUMP_PCT   = 2.0;   // live price this % away from the last close = abnormal jump
    static final boolean ENTRY_LIQUIDITY_CHECK_ENABLED = true;
    static final double ENTRY_MIN_AVG_5M_NOTIONAL_USDT = 300.0; // avg 5M volume x price (verify the candle volume unit on your first run)
    static final long   ENTRY_MAX_PRICE_AGE_MS = 120_000L;      // last trade older than this = stale/illiquid
    static final boolean ENTRY_SPREAD_CHECK_ENABLED = true;     // pre-order order-book check (best effort, see marketSafetyReason)
    static final double ENTRY_MAX_SPREAD_PCT = 0.15;
    static final double ENTRY_MIN_BOOK_NOTIONAL_USDT = 200.0;
    static final boolean ENTRY_REQUIRE_SPREAD_DATA = false;     // true = no order when the order book cannot be read
    static final int    ORDERBOOK_DEPTH = 10;

    // BTC market direction filter for altcoins
    static final boolean BTC_FILTER_ENABLED = true;
    static final boolean BTC_HARD_BLOCK_ON_OPPOSITE = true;     // BTC clearly against the trade side = no entry (false = just 0 points)
    static final String  BTC_PAIR = "B-BTC_USDT";
    static final long    BTC_CACHE_MS = 60_000L;

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
        double qty;                     // absolute position quantity (persisted; used for the portfolio realized-PnL estimate)
        // ---- runtime only (NOT persisted) ----
        long nextTrailTryMs;
        long lastStatusLogMs;
    }
    static class PaperTrade {
        boolean isLong;
        double qty;                     // simulated quantity (portfolio PnL in PAPER mode)
        double entry, sl, tp;
        long openedMs;
        String ctx = "";
    }
    private static final Map<String, TradeInfo> tradeState = new ConcurrentHashMap<>();
    static String tradeStateFile = TRADE_STATE_FILE;        // the self-tests redirect this so they can never touch the real state file
    static boolean selfTestMode = false;                    // true only inside --selftest (no CSV / state-file writes to the live files)
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
        final double[] o, h, l, c, v;     // v = volume (-1 = not provided by the exchange)
        Series5(int n) {
            this.n = n;
            t = new long[n]; o = new double[n]; h = new double[n]; l = new double[n]; c = new double[n]; v = new double[n];
            Arrays.fill(v, -1);
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
            double vol = j.optDouble("volume", -1);
            s.v[i] = (Double.isNaN(vol) || vol < 0) ? -1 : vol;
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
        System.arraycopy(a.v, 0, r.v, 0, cut); System.arraycopy(b.v, 0, r.v, cut, b.n);
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
        System.arraycopy(s.v, from, r.v, 0, maxN);
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
    // TREND-FOLLOWING ENTRY EVALUATION (used by the scan AND by the pre-order revalidation)
    //   Direction  : 4H + 1H Supertrend both GREEN (LONG) / both RED (SHORT)          -> mandatory
    //   Momentum   : 15M EMA9 vs EMA21 (closed 15M bars)                               -> mandatory (ENTRY_REQUIRE_15M_EMA) + score
    //   Strength   : 15M ADX(14) >= ADX_MIN with +DI/-DI on the trade side             -> score
    //   Trigger    : 5M pullback/retest + confirmed candle close  OR  fresh confirmed swing break (closed candle) -> mandatory + score
    //   Volume     : last closed 5M candle volume >= VOLUME_MIN_RATIO x average        -> score (mandatory for breakouts)
    //   BTC filter : BTC 1H Supertrend + 15M EMA direction vs the trade side           -> score (+ optional hard block)
    //   Safety     : fresh data, no spike, liquidity, not extended / not chased        -> mandatory, never bypassed by the score
    // =========================================================================
    static final int BTC_UNAVAILABLE = -99;   // BTC bias could not be computed
    static final int BTC_NA          = 99;    // the symbol IS BTC (or the filter is off) -> not applicable

    static class Eval {
        String signal = "NO TRADE";    // BUY / SELL / NO TRADE
        String code = "";
        String reason = "";
        int dir;                       // stage-1 candidate: +1 LONG, -1 SHORT, 0 none
        final Dev[] dev = new Dev[4];  // only index 0 (1H) and 3 (4H) are used by this strategy
        double ema9, ema21, brkLevel;  // 15M EMAs (closed bars) / trigger level
        double price;
        int score;                     // normalised 0..100
        String scoreDetail = "";
        String trigger = "";           // PULLBACK / BREAKOUT
        double adx, plusDI, minusDI, atr5, atr15;
        long candleOpen;               // open time of the last CLOSED 5M candle = signal candle id
    }

    private static Eval fail(Eval r, String code, String reason) {
        if (r == null) return null;
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

    // ---- small closed-candle helpers ----
    static int closedEnd(Series5 s, long cur5) {
        int end = s.n;
        while (end > 0 && s.t[end - 1] >= cur5) end--;
        return end;
    }

    // EMA series (NaN before the first full period)
    static double[] emaSeries(double[] d, int period) {
        double[] e = new double[d.length];
        Arrays.fill(e, Double.NaN);
        if (d.length < period) return e;
        double k = 2.0 / (period + 1), v = 0;
        for (int i = 0; i < period; i++) v += d[i];
        v /= period;
        e[period - 1] = v;
        for (int i = period; i < d.length; i++) { v = d[i] * k + v * (1 - k); e[i] = v; }
        return e;
    }

    // Wilder ADX. Returns {ADX, +DI, -DI} of the last bar, or null when there are not enough bars.
    static double[] calcADX(double[] hi, double[] lo, double[] cl, int period) {
        int n = cl.length;
        if (n < 2 * period + 2) return null;
        double[] tr = new double[n], pdm = new double[n], mdm = new double[n];
        for (int i = 1; i < n; i++) {
            double up = hi[i] - hi[i - 1], dn = lo[i - 1] - lo[i];
            pdm[i] = (up > dn && up > 0) ? up : 0;
            mdm[i] = (dn > up && dn > 0) ? dn : 0;
            tr[i] = Math.max(hi[i] - lo[i], Math.max(Math.abs(hi[i] - cl[i - 1]), Math.abs(lo[i] - cl[i - 1])));
        }
        double sTr = 0, sP = 0, sM = 0;
        for (int i = 1; i <= period; i++) { sTr += tr[i]; sP += pdm[i]; sM += mdm[i]; }
        double[] dx = new double[n];
        double pdi = 0, mdi = 0;
        for (int i = period; i < n; i++) {
            if (i > period) { sTr = sTr - sTr / period + tr[i]; sP = sP - sP / period + pdm[i]; sM = sM - sM / period + mdm[i]; }
            pdi = sTr > 0 ? 100.0 * sP / sTr : 0;
            mdi = sTr > 0 ? 100.0 * sM / sTr : 0;
            double sum = pdi + mdi;
            dx[i] = sum > 0 ? 100.0 * Math.abs(pdi - mdi) / sum : 0;
        }
        double adx = 0;
        for (int i = period; i < 2 * period; i++) adx += dx[i];
        adx /= period;
        for (int i = 2 * period; i < n; i++) adx = (adx * (period - 1) + dx[i]) / period;
        return new double[]{adx, pdi, mdi};
    }

    // Completed 15M bars only (never the developing one). Null (and r failed) if history is too short / has a gap.
    static Bars closed15(Series5 s, long nowMs, Eval r) {
        long start15 = alignedStart(nowMs, FIFTEEN_MIN_MS, HTF_ALIGN_OFFSET_MS);
        Bars b = aggComplete(s, FIFTEEN_MIN_MS, start15);
        if (b.n < Math.max(EMA_MIN_BARS_15M, 2 * ADX_PERIOD + 4)) { fail(r, "emaHistory", "history: only " + b.n + " completed 15M bars"); return null; }
        if (b.t[b.n - 1] != start15 - FIFTEEN_MIN_MS) { fail(r, "emaGap", "history gap before the developing 15M bar"); return null; }
        return b;
    }

    static double[] ema15Closed(Bars b) {
        int use = Math.min(b.n, EMA_WINDOW_15M);
        double[] cl = new double[use];
        System.arraycopy(b.c, b.n - use, cl, 0, use);
        return new double[]{calcEMA(cl, EMA_FAST), calcEMA(cl, EMA_SLOW)};
    }

    // Supertrend of timeframe k. Closed mode: last bar = last COMPLETED bar (no repainting).
    // Developing mode (HTF_SUPERTREND_ON_CLOSED_CANDLES = false): last bar = developing bar with close = live price.
    static Dev htfSupertrend(Series5 s, long nowMs, double price, int k) {
        Dev d;
        if (!HTF_SUPERTREND_ON_CLOSED_CANDLES) {
            d = developing(s, nowMs, price, k);
            if (!d.ok) return d;
        } else {
            d = new Dev();
            d.tf = TF_NAMES[k]; d.tfMs = TF_MS[k];
            d.start = alignedStart(nowMs, d.tfMs, HTF_ALIGN_OFFSET_MS);
            d.price = price;
        }
        Bars b = aggComplete(s, d.tfMs, d.start);
        d.stBars = b.n;
        d.ok = false;
        if (b.n < ST_MIN_BARS) { d.reason = "stHistory: only " + b.n + " completed " + d.tf + " bars (need " + ST_MIN_BARS + ")"; return d; }
        if (b.t[b.n - 1] != d.start - d.tfMs) { d.reason = "histGap: last completed " + d.tf + " bar is not the one right before the current bar"; return d; }
        if (HTF_SUPERTREND_ON_CLOSED_CANDLES) {
            double[] hi = Arrays.copyOf(b.h, b.n), lo = Arrays.copyOf(b.l, b.n), cl = Arrays.copyOf(b.c, b.n);
            d.open = b.o[b.n - 1]; d.high = b.h[b.n - 1]; d.low = b.l[b.n - 1]; d.price = b.c[b.n - 1];
            d.colour = colourOf(d.open, d.price);
            d.stBull = calcSupertrend(hi, lo, cl, ST_PERIOD, ST_MULTIPLIER)[b.n - 1];
        } else {
            int m = b.n + 1;
            double[] hi = new double[m], lo = new double[m], cl = new double[m];
            System.arraycopy(b.h, 0, hi, 0, b.n);
            System.arraycopy(b.l, 0, lo, 0, b.n);
            System.arraycopy(b.c, 0, cl, 0, b.n);
            hi[m - 1] = d.high; lo[m - 1] = d.low; cl[m - 1] = price;
            d.stBull = calcSupertrend(hi, lo, cl, ST_PERIOD, ST_MULTIPLIER)[m - 1];
        }
        d.ok = true;
        return d;
    }

    // STAGE 1 (cheap, no live price needed in closed mode): 4H + 1H Supertrend aligned (+ 15M EMA when mandatory).
    // Returns +1 / -1 for a candidate, 0 otherwise (r.code / r.reason are set then).
    static int stage1(Series5 s, double price, long nowMs, Eval r) {
        String bad = validate5m(s, nowMs);
        if (bad != null) { fail(r, "5m_" + bad.substring(0, bad.indexOf(':')), bad); return 0; }
        long cur5 = alignedStart(nowMs, FIVE_MIN_MS, 0L);
        int end = closedEnd(s, cur5);
        if (end < 60 || s.t[end - 1] != cur5 - FIVE_MIN_MS) { fail(r, "5m_noClosed", "last CLOSED 5M candle missing"); return 0; }
        for (int i = end - 39; i < end; i++)
            if (s.t[i] - s.t[i - 1] != FIVE_MIN_MS) { fail(r, "5m_gap", "gap inside the last 40 closed 5M candles"); return 0; }
        r.candleOpen = s.t[end - 1];
        for (int k : new int[]{0, 3}) {
            Dev d = htfSupertrend(s, nowMs, price, k);
            r.dev[k] = d;
            if (!d.ok) { fail(r, d.reason.substring(0, d.reason.indexOf(':')), "data: " + d.tf + " " + d.reason); return 0; }
        }
        boolean allGreen = r.dev[0].stBull && r.dev[3].stBull;
        boolean allRed = !r.dev[0].stBull && !r.dev[3].stBull;
        if (!allGreen && !allRed) { fail(r, "stage1", "4H/1H Supertrend not aligned: " + summary(r)); return 0; }
        int dir = allGreen ? 1 : -1;
        if (ENTRY_REQUIRE_15M_EMA) {
            Bars b = closed15(s, nowMs, r);
            if (b == null) return 0;
            double[] e = ema15Closed(b);
            r.ema9 = e[0]; r.ema21 = e[1];
            if (dir > 0 ? !(e[0] > e[1]) : !(e[0] < e[1])) {
                fail(r, "ema", String.format("15M EMA9=%.6f EMA21=%.6f against %s", e[0], e[1], dir > 0 ? "LONG" : "SHORT"));
                return 0;
            }
        }
        r.dir = dir;
        return dir;
    }

    // STAGE 2: score + mandatory safety checks on CLOSED candles, with the live price only for extension/chase checks.
    // priceAgeMs < 0 = unknown. btc = BTC bias (+1 bullish / -1 bearish / 0 mixed) or BTC_NA / BTC_UNAVAILABLE.
    static void stage2(Series5 s, double price, long priceAgeMs, long nowMs, int dir, int btc, Eval r) {
        boolean isLong = dir > 0;
        long cur5 = alignedStart(nowMs, FIVE_MIN_MS, 0L);
        int end = closedEnd(s, cur5);
        if (end < 60) { fail(r, "5m_noClosed", "not enough closed 5M candles"); return; }

        // ---- 15M: EMA, ADX, ATR (closed bars only) ----
        Bars b = closed15(s, nowMs, r);
        if (b == null) return;
        double[] e = ema15Closed(b);
        r.ema9 = e[0]; r.ema21 = e[1];
        boolean emaOk = isLong ? e[0] > e[1] : e[0] < e[1];
        if (ENTRY_REQUIRE_15M_EMA && !emaOk) {
            fail(r, "ema", String.format("15M EMA9=%.6f EMA21=%.6f against %s", e[0], e[1], isLong ? "LONG" : "SHORT"));
            return;
        }
        int use15 = Math.min(b.n, ADX_WINDOW_15M);
        double[] h15 = new double[use15], l15 = new double[use15], c15 = new double[use15];
        System.arraycopy(b.h, b.n - use15, h15, 0, use15);
        System.arraycopy(b.l, b.n - use15, l15, 0, use15);
        System.arraycopy(b.c, b.n - use15, c15, 0, use15);
        double[] adx = calcADX(h15, l15, c15, ADX_PERIOD);
        if (adx == null) { fail(r, "adxHistory", "not enough 15M bars for ADX"); return; }
        r.adx = adx[0]; r.plusDI = adx[1]; r.minusDI = adx[2];
        r.atr15 = calcATRSeries(h15, l15, c15, ATR_PERIOD_15M)[use15 - 1];

        // ---- 5M closed candles ----
        int W = Math.min(end, 200), from = end - W, L = W - 1;
        double[] o = new double[W], h = new double[W], l = new double[W], c = new double[W], v = new double[W];
        for (int i = 0; i < W; i++) { o[i] = s.o[from + i]; h[i] = s.h[from + i]; l[i] = s.l[from + i]; c[i] = s.c[from + i]; v[i] = s.v[from + i]; }
        double[] e9 = emaSeries(c, EMA_FAST), e21 = emaSeries(c, EMA_SLOW);
        double[] atrS = calcATRSeries(h, l, c, ATR_PERIOD_5M);
        r.atr5 = atrS[L];
        if (!(r.atr5 > 0) || !(r.atr15 > 0)) { fail(r, "atr", "ATR not available"); return; }
        r.candleOpen = s.t[from + L];

        // ---- mandatory safety: fresh data, no spike, liquidity ----
        long candleAge = nowMs - (r.candleOpen + FIVE_MIN_MS);
        if (candleAge > ENTRY_MAX_CANDLE_AGE_MS) { fail(r, "lateSignal", "signal candle closed " + (candleAge / 1000) + "s ago (max " + (ENTRY_MAX_CANDLE_AGE_MS / 1000) + "s)"); return; }
        if (priceAgeMs >= 0 && priceAgeMs > ENTRY_MAX_PRICE_AGE_MS) { fail(r, "stalePrice", "last trade is " + (priceAgeMs / 1000) + "s old"); return; }
        double prevAtr = L >= 1 ? atrS[L - 1] : r.atr5;
        if ((h[L] - l[L]) > ENTRY_SPIKE_RANGE_ATR5 * prevAtr) { fail(r, "spike", String.format("last closed 5M range %.6f > %.1f x ATR %.6f", h[L] - l[L], ENTRY_SPIKE_RANGE_ATR5, prevAtr)); return; }
        if (Math.abs(price - c[L]) / c[L] * 100.0 > ENTRY_SPIKE_JUMP_PCT) { fail(r, "spike", String.format("live price %.6f jumped %.2f%% from the last close", price, Math.abs(price - c[L]) / c[L] * 100.0)); return; }

        boolean volAvail = v[L] >= 0;
        double avgV = 0;
        for (int i = L - VOLUME_AVG_BARS; i < L; i++) { if (i < 0 || v[i] < 0) { volAvail = false; break; } avgV += v[i]; }
        avgV /= VOLUME_AVG_BARS;
        boolean volOk = volAvail && avgV > 0 && v[L] >= VOLUME_MIN_RATIO * avgV;
        if (ENTRY_LIQUIDITY_CHECK_ENABLED && volAvail) {
            double sum = 0;
            for (int i = L - VOLUME_AVG_BARS + 1; i <= L; i++) sum += v[i];
            double notional = sum / VOLUME_AVG_BARS * price;
            if (notional < ENTRY_MIN_AVG_5M_NOTIONAL_USDT) { fail(r, "illiquid", String.format("avg 5M traded notional %.1f < %.1f", notional, ENTRY_MIN_AVG_5M_NOTIONAL_USDT)); return; }
        }

        // ---- trigger: pullback + confirmed candle, OR fresh confirmed swing break (both on CLOSED candles) ----
        double body = Math.abs(c[L] - o[L]), range = h[L] - l[L];
        boolean candleDir = dir * (c[L] - o[L]) > 0 && range > 0 && body / range >= ENTRY_MIN_BODY_RATIO;
        String trig = null, trigWhy = "";
        if (ENTRY_ALLOW_BREAKOUT) {
            int n2 = SWING_LOOKBACK_5M + 2 * SWING_PIVOT_BARS + 6;
            int m = Math.min(W, n2);
            double[] hh = Arrays.copyOfRange(h, W - m, W), ll = Arrays.copyOfRange(l, W - m, W), cc = Arrays.copyOfRange(c, W - m, W);
            Breakout bo = findBreakout(hh, ll, cc, isLong, SWING_PIVOT_BARS, SWING_LOOKBACK_5M);
            if (bo.ok && candleDir && (!BREAKOUT_REQUIRES_VOLUME || volOk)) { trig = "BREAKOUT"; r.brkLevel = bo.level; trigWhy = bo.reason; }
            else trigWhy = "breakout: " + (bo.ok ? (candleDir ? "volume too low/unavailable" : "weak/opposite candle") : bo.reason);
        }
        if (trig == null && ENTRY_ALLOW_PULLBACK) {
            double z = PULLBACK_ZONE_ATR * r.atr5, brk = PULLBACK_MAX_BREAK_ATR * r.atr5;
            boolean touched = false, counter = false, broken = false;
            for (int i = Math.max(0, L - PULLBACK_LOOKBACK_5M); i < L; i++) {
                if (isLong ? l[i] <= e21[i] + z : h[i] >= e21[i] - z) touched = true;
                if (isLong ? c[i] < o[i] : c[i] > o[i]) counter = true;
                if (isLong ? c[i] < e21[i] - brk : c[i] > e21[i] + brk) broken = true;
            }
            boolean reclaim = isLong ? (c[L] > e9[L] && c[L] > e21[L]) : (c[L] < e9[L] && c[L] < e21[L]);
            if (touched && counter && !broken && reclaim && candleDir) { trig = "PULLBACK"; r.brkLevel = e21[L]; trigWhy = String.format("5M pullback to EMA21=%.6f then confirmed close %.6f", e21[L], c[L]); }
            else trigWhy += (trigWhy.isEmpty() ? "" : " | ") + String.format("pullback: touched=%s counter=%s broken=%s reclaim=%s candle=%s", touched, counter, broken, reclaim, candleDir);
        }
        if (trig == null) { fail(r, "trigger", "no 5M trigger: " + trigWhy); return; }
        r.trigger = trig;

        // ---- mandatory: not extended / not chased ----
        if (dir * (price - e[1]) > ENTRY_MAX_EXT_ATR15 * r.atr15) {
            fail(r, "extended", String.format("price %.6f is %.2f x ATR15 beyond 15M EMA21 %.6f (max %.1f)", price, dir * (price - e[1]) / r.atr15, e[1], ENTRY_MAX_EXT_ATR15)); return;
        }
        if (dir * (price - c[L]) > ENTRY_MAX_CHASE_ATR5 * r.atr5) {
            fail(r, "chased", String.format("price %.6f already %.2f x ATR5 beyond the confirmation close %.6f (max %.1f)", price, dir * (price - c[L]) / r.atr5, c[L], ENTRY_MAX_CHASE_ATR5)); return;
        }
        if (dir * (price - o[L]) <= 0) { fail(r, "faded", "price fell back through the confirmation candle's open"); return; }

        // ---- BTC filter ----
        int btcPts = 0;
        String btcTxt = "off";
        if (BTC_FILTER_ENABLED) {
            if (btc == BTC_NA) { btcPts = SCORE_BTC; btcTxt = "n/a"; }
            else if (btc == BTC_UNAVAILABLE) { btcPts = 0; btcTxt = "unavailable"; }
            else if (dir * btc > 0) { btcPts = SCORE_BTC; btcTxt = "aligned"; }
            else if (btc == 0) { btcPts = SCORE_BTC / 2; btcTxt = "neutral"; }
            else {
                btcPts = 0; btcTxt = "opposed";
                if (BTC_HARD_BLOCK_ON_OPPOSITE) { fail(r, "btcOpposed", "BTC direction is against " + (isLong ? "LONG" : "SHORT")); return; }
            }
        }

        // ---- score (normalised to 100 over the components that are active) ----
        int earned = SCORE_HTF, possible = SCORE_HTF;           // HTF alignment is mandatory -> always earned
        StringBuilder sd = new StringBuilder("HTF=" + SCORE_HTF);
        possible += SCORE_EMA15; if (emaOk) earned += SCORE_EMA15; sd.append(" EMA15=").append(emaOk ? SCORE_EMA15 : 0);
        boolean adxOk = ADX_MIN <= 0 || (r.adx >= ADX_MIN && dir * (r.plusDI - r.minusDI) > 0);
        possible += SCORE_ADX; if (adxOk) earned += SCORE_ADX; sd.append(" ADX=").append(adxOk ? SCORE_ADX : 0);
        possible += SCORE_TRIGGER; earned += SCORE_TRIGGER; sd.append(" TRIG=").append(SCORE_TRIGGER);
        possible += SCORE_VOLUME; if (volOk) earned += SCORE_VOLUME; sd.append(" VOL=").append(volOk ? SCORE_VOLUME : 0);
        if (BTC_FILTER_ENABLED) { possible += SCORE_BTC; earned += btcPts; sd.append(" BTC=").append(btcPts).append('(').append(btcTxt).append(')'); }
        r.score = (int) Math.round(100.0 * earned / possible);
        r.scoreDetail = sd + " => " + earned + "/" + possible;
        if (r.score < ENTRY_MIN_SCORE) { fail(r, "score", "score " + r.score + " < " + ENTRY_MIN_SCORE + " (" + r.scoreDetail + ")"); return; }

        r.signal = isLong ? "BUY" : "SELL";
        r.code = "signal";
        r.reason = String.format("%s | 15M EMA9=%.6f EMA21=%.6f ADX=%.1f(+DI %.1f/-DI %.1f) | 5M %s: %s | vol=%s | score=%d [%s]",
                summary(r), e[0], e[1], r.adx, r.plusDI, r.minusDI, trig, trigWhy, volOk ? "ok" : "low/NA", r.score, r.scoreDetail);
    }

    static Eval evaluateStage1(Series5 s, double price, long nowMs) {
        Eval r = new Eval();
        r.price = price;
        if (!(price > 0) || Double.isInfinite(price)) { fail(r, "noPrice", "no valid price"); return r; }
        stage1(s, price, nowMs, r);
        return r;
    }

    // Both stages with the same live price.
    static Eval evaluateFull(Series5 s, double price, long priceAgeMs, long nowMs, int btc) {
        Eval r = evaluateStage1(s, price, nowMs);
        if (r.dir == 0) return r;
        stage2(s, price, priceAgeMs, nowMs, r.dir, btc, r);
        return r;
    }

    static String describeDev(Dev e) {
        if (!e.ok) return e.tf + " INVALID (" + e.reason + ")";
        return String.format("%s open=%.6f price=%.6f candle=%s supertrend=%s (%d bars)",
                e.tf, e.open, e.price, colName(e.colour), stName(e.stBull), e.stBars);
    }

    private static void logEval(String pair, Eval ev) {
        for (Dev d : ev.dev) if (d != null) System.out.println("  [ST] " + pair + " " + describeDev(d));
        if (ev.ema9 > 0) System.out.printf("  [ST] %s 15M EMA9=%.6f EMA21=%.6f ADX=%.1f trigger=%s level=%.6f score=%d%n", pair, ev.ema9, ev.ema21, ev.adx, ev.trigger, ev.brkLevel, ev.score);
        System.out.println("  [ST] " + pair + " DECISION=" + (ev.signal.equals("BUY") ? "LONG" : ev.signal.equals("SELL") ? "SHORT" : "NO TRADE")
                + " | " + ev.reason);
    }

    static String ctxOf(Eval ev) {
        StringBuilder sb = new StringBuilder();
        for (Dev d : ev.dev) {
            if (d == null || !d.ok) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(d.tf).append(":ST=").append(d.stBull ? "G" : "R");
        }
        sb.append(String.format(" EMA9=%.6f EMA21=%.6f ADX=%.1f TRIG=%s LVL=%.6f SCORE=%d", ev.ema9, ev.ema21, ev.adx, ev.trigger, ev.brkLevel, ev.score));
        return sb.toString();
    }

    // ---- BTC market direction (cached; one extra small fetch per BTC_CACHE_MS) ----
    // +1 bullish (1H Supertrend GREEN and 15M EMA9 > EMA21), -1 bearish (both red), 0 mixed.
    static int btcBiasOf(Series5 s, long nowMs) {
        if (s == null || validate5m(s, nowMs) != null) return BTC_UNAVAILABLE;
        Dev d = htfSupertrend(s, nowMs, s.c[s.n - 1], 0);
        if (!d.ok) return BTC_UNAVAILABLE;
        Bars b = closed15(s, nowMs, null);
        if (b == null) return BTC_UNAVAILABLE;
        double[] e = ema15Closed(b);
        int sum = (d.stBull ? 1 : -1) + (e[0] > e[1] ? 1 : -1);
        return sum > 0 ? 1 : (sum < 0 ? -1 : 0);
    }

    private static long btcAt = 0;
    private static int btcBiasCached = BTC_UNAVAILABLE;

    private static int btcFor(String pair, long nowMs) {
        if (!BTC_FILTER_ENABLED || BTC_PAIR.equals(pair)) return BTC_NA;
        if (btcAt == 0 || nowMs - btcAt >= BTC_CACHE_MS) {
            int nb = btcBiasOf(getSeries(BTC_PAIR, nowMs), nowMs);
            if (nb != btcBiasCached || btcAt == 0)
                System.out.println("  [BTC] market bias = " + (nb == BTC_UNAVAILABLE ? "UNAVAILABLE" : nb > 0 ? "BULLISH" : nb < 0 ? "BEARISH" : "MIXED"));
            btcBiasCached = nb;
            btcAt = nowMs;
        }
        return btcBiasCached;
    }

    // ---- live price with the age of the last trade (age -1 = unknown) ----
    static double[] getLastTradeInfo(String pair) {
        try {
            HttpURLConnection conn = openGet(PUBLIC_API_URL + "/market_data/trade_history?pair=" + pair + "&limit=1");
            if (conn.getResponseCode() == 200) {
                String r = readStream(conn.getInputStream());
                JSONObject j = r.startsWith("[") ? new JSONArray(r).getJSONObject(0) : new JSONObject(r);
                double p = j.getDouble("p");
                double t = j.optDouble("T", j.optDouble("t", -1));
                long age = -1;
                if (t > 0) { long tm = (long) t; if (tm < 100_000_000_000L) tm *= 1000L; age = Math.max(0, System.currentTimeMillis() - tm); }
                return new double[]{p, age};
            }
        } catch (Exception e) {
            System.err.println("getLastTradeInfo(" + pair + "): " + e.getMessage());
        }
        return new double[]{0, -1};
    }

    // ---- order-book spread / depth (best effort: the endpoint format is verified on the first run) ----
    static class BookInfo { double bid, ask, bidNotional, askNotional; }

    static BookInfo parseBook(String body) {
        try {
            JSONObject j = new JSONObject(body);
            double[] bid = sideOf(j.opt("bids"), true), ask = sideOf(j.opt("asks"), false);
            if (bid == null || ask == null) return null;
            BookInfo b = new BookInfo();
            b.bid = bid[0]; b.bidNotional = bid[1]; b.ask = ask[0]; b.askNotional = ask[1];
            return b.bid > 0 && b.ask > 0 ? b : null;
        } catch (Exception e) { return null; }
    }

    // {best price, total notional} of one book side. Accepts {"price":"qty"} maps and [{price,quantity}] arrays.
    private static double[] sideOf(Object o, boolean bids) {
        double best = bids ? 0 : Double.MAX_VALUE, notional = 0;
        int cnt = 0;
        if (o instanceof JSONObject) {
            JSONObject m = (JSONObject) o;
            for (String k : m.keySet()) {
                double p = Double.parseDouble(k), q = m.optDouble(k, 0);
                best = bids ? Math.max(best, p) : Math.min(best, p);
                notional += p * q; cnt++;
            }
        } else if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            for (int i = 0; i < a.length(); i++) {
                JSONObject x = a.getJSONObject(i);
                double p = x.optDouble("price", x.optDouble("p", 0)), q = x.optDouble("quantity", x.optDouble("q", 0));
                best = bids ? Math.max(best, p) : Math.min(best, p);
                notional += p * q; cnt++;
            }
        }
        return cnt == 0 ? null : new double[]{best, notional};
    }

    // null = book OK. Pure function (unit-tested).
    static String bookReason(BookInfo b, boolean isLong, double maxSpreadPct, double minNotional) {
        double mid = (b.bid + b.ask) / 2.0;
        double spread = (b.ask - b.bid) / mid * 100.0;
        if (b.ask < b.bid) return "crossed order book";
        if (spread > maxSpreadPct) return String.format("spread %.3f%% > %.3f%%", spread, maxSpreadPct);
        double side = isLong ? b.askNotional : b.bidNotional;
        if (side < minNotional) return String.format("thin book: %.1f USDT on the %s side < %.1f", side, isLong ? "ask" : "bid", minNotional);
        return null;
    }

    private static String marketSafetyReason(String pair, boolean isLong) {
        if (!ENTRY_SPREAD_CHECK_ENABLED) return null;
        BookInfo b = null;
        try {
            b = parseBook(publicGet(PUBLIC_API_URL + "/market_data/v3/orderbook/" + pair + "-futures/" + ORDERBOOK_DEPTH));
        } catch (Exception e) {
            System.err.println("  [BOOK] " + pair + ": " + e.getMessage());
        }
        if (b == null) {
            if (ENTRY_REQUIRE_SPREAD_DATA) return "order book unavailable (ENTRY_REQUIRE_SPREAD_DATA=true)";
            System.out.println("  [BOOK] " + pair + " order book unavailable — spread check skipped");
            return null;
        }
        return bookReason(b, isLong, ENTRY_MAX_SPREAD_PCT, ENTRY_MIN_BOOK_NOTIONAL_USDT);
    }

    static class Revalidation {
        String reason;      // null = still valid
        double price;
    }

    // Fresh 5M candles + fresh live price + fresh BTC bias -> full re-evaluation (all mandatory checks + score),
    // then the order-book spread / depth check.
    private static Revalidation revalidateBeforeOrder(String pair, boolean isLong) {
        Revalidation rv = new Revalidation();
        long now = System.currentTimeMillis();
        Series5 s = getSeries(pair, now);
        double[] lp = getLastTradeInfo(pair);
        rv.price = lp[0];
        Eval ev;
        if (s == null) { ev = new Eval(); fail(ev, "noData", "no fresh 5M data"); }
        else ev = evaluateFull(s, rv.price, (long) lp[1], System.currentTimeMillis(), btcFor(pair, System.currentTimeMillis()));
        String want = isLong ? "BUY" : "SELL";
        rv.reason = ev.signal.equals(want) ? null
                : "pre-order revalidation: now " + ev.signal + " - " + ev.reason;
        if (rv.reason == null) {
            String m = marketSafetyReason(pair, isLong);
            if (m != null) rv.reason = "pre-order market check: " + m;
        }
        System.out.println("  [REVALIDATE] " + pair + " " + want + " price=" + rv.price + " score=" + ev.score + " | -> "
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
        j.put("qty", t.qty);
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
        t.qty = j.optDouble("qty", 0);
        return t;
    }

    // Atomic write (temp file + rename) so a crash in the middle never leaves a half-written state file.
    private static synchronized void saveTradeState() {
        try {
            JSONObject root = new JSONObject();
            for (Map.Entry<String, TradeInfo> en : tradeState.entrySet()) root.put(en.getKey(), tradeToJson(en.getValue()));
            File target = new File(tradeStateFile);
            File tmp = new File(tradeStateFile + ".tmp");
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
        File f = new File(tradeStateFile);
        if (!f.exists()) { System.out.println("[STATE] no saved trade state (" + tradeStateFile + ") — starting fresh"); return; }
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
                + " | TREND-FOLLOWING: 4H+1H Supertrend -> 15M EMA9/21 + ADX -> 5M pullback/breakout + volume + BTC filter, min score " + ENTRY_MIN_SCORE
                + " | HTF candles=" + (HTF_SUPERTREND_ON_CLOSED_CANDLES ? "CLOSED" : "DEVELOPING")
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
        System.out.println("=== Portfolio V2: target " + (PORTFOLIO_PROFIT_TARGET_ENABLED ? PORTFOLIO_PROFIT_TARGET_PERCENT + "%" : "OFF")
                + " of LOCKED MARGIN | breakeven " + (PORTFOLIO_BREAKEVEN_ENABLED ? "ON" : "OFF")
                + " | trailing " + (PORTFOLIO_TRAILING_ENABLED ? "ON" : "OFF") + " | max-loss " + (PORTFOLIO_MAX_LOSS_ENABLED ? PORTFOLIO_MAX_LOSS_PERCENT + "%" : "OFF")
                + " | check every " + (PORTFOLIO_CHECK_INTERVAL_MS / 1000) + "s | re-entry pause " + (PORTFOLIO_REENTRY_PAUSE_MS / 1000) + "s"
                + (LIVE_TRADING_ENABLED ? "" : " | PAPER: simulated positions, state not persisted") + " ===");
        validatePortfolioConfig();
        if (LIVE_TRADING_ENABLED && (API_KEY == null || API_SECRET == null)) {
            System.err.println("FATAL: LIVE_TRADING_ENABLED but DELTA_API_KEY / DELTA_API_SECRET are not set");
            return;
        }
        initInstrumentCache();
        if (LIVE_TRADING_ENABLED) { loadTradeState(); loadPortfolioState(); }

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

    private static PortfolioOps currentOps() { return LIVE_TRADING_ENABLED ? LIVE_OPS : PAPER_OPS; }

    private static double newPositionMarginUsdt() { return FIXED_MARGIN / USDT_INR_RATE / LEVERAGE; }

    // snapshot-based portfolio gate (liquidation / cooldowns / position + margin limits). null = entries allowed.
    private static String portfolioBlockReason(int openCount) {
        return portfolioEntryBlockedReason(System.currentTimeMillis(), openCount, portfolioDeployedMarginUsdt, newPositionMarginUsdt());
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
        } else {
            active = new HashSet<>();
            updatePaperTrades();
        }
        runPortfolioProfitCheck();              // portfolio V2: target / breakeven / trailing floor / max loss / close-all (does nothing when all OFF)
        if (portfolioLiquidating()) {
            System.out.println("Portfolio liquidation in progress — no new entries; retrying until the exchange confirms all positions closed.");
            drainLiquidation(currentOps());
            if (LIVE_TRADING_ENABLED) ensureTpSlForOpenPositions();
            return;
        }

        String gate = portfolioBlockReason(active.size() + paperTrades.size());
        if (gate != null) {
            System.out.println("Portfolio gate — no new entries this cycle: " + gate);
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
        btcFor("", System.currentTimeMillis());          // refreshes the cached BTC bias once per scan (no-op when the filter is off)

        for (String pair : COINS_TO_TRADE) {
            try {
                // The scan of hundreds of symbols can take longer than the trailing interval, so trailing is also
                // checked from inside the scan loop (same thread -> no concurrent order/TP-SL calls).
                if (LIVE_TRADING_ENABLED && System.currentTimeMillis() - lastTrailPassMs >= TRAIL_CHECK_INTERVAL_MS) runTrailingPass();

                // Portfolio checks also run from inside the scan loop (rate limited), so they never wait for a full scan.
                if (PARAMS.anyEnabled()) runPortfolioProfitCheck();
                String gate = portfolioBlockReason(active.size() + paperTrades.size());
                if (gate != null) {
                    System.out.println("  [PORTFOLIO] entries blocked — scan stopped: " + gate);
                    break;
                }

                if (active.size() + paperTrades.size() >= MAX_OPEN_POSITIONS) break;
                if (active.contains(pair) || paperTrades.containsKey(pair)) continue;   // no duplicate / conflicting position per symbol
                if (System.currentTimeMillis() - lastTradeTime.getOrDefault(pair, 0L) < COOLDOWN_MS) continue;

                // ---- data: hourly-cached history + ONE small 5M request ----
                Series5 s = getSeries(pair, System.currentTimeMillis());
                if (s == null) { reject(rej, "noData"); continue; }

                // ---- STAGE 1: 4H + 1H Supertrend direction (+ 15M EMA) on closed candles ----
                long now0 = System.currentTimeMillis();
                Eval quick = evaluateStage1(s, s.c[s.n - 1], now0);
                if (quick.dir == 0) { reject(rej, quick.code); continue; }

                // ---- STAGE 2: score + mandatory safety checks with the real live price ----
                stage2++;
                double[] lp = getLastTradeInfo(pair);
                long now1 = System.currentTimeMillis();
                Eval ev = evaluateFull(s, lp[0], (long) lp[1], now1, btcFor(pair, now1));
                if (ev.signal.equals("NO TRADE")) {
                    reject(rej, "live_" + ev.code);
                    if (LOG_NO_TRADE_DETAIL && !ev.code.equals("trigger")) logEval(pair, ev);
                    continue;
                }

                boolean isLong = ev.signal.equals("BUY");
                String sigKey = pair + "|" + (isLong ? "LONG" : "SHORT");
                // one signal per symbol + direction per CLOSED 5M signal candle (no repeated signals from the same candle)
                if (!registerSignal(lastSignalCandle, sigKey, ev.candleOpen)) { reject(rej, "duplicateSignal"); continue; }
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
        QtyResult pq = sizeQuantity(FIXED_MARGIN / (price * USDT_INR_RATE), price, INTEGER_QTY_PAIRS.contains(pair), instrumentCache.get(pair));
        if (pq.qty <= 0) { System.out.println("[PAPER] SKIP - " + pair + " - " + pq.reason); return; }
        PaperTrade p = new PaperTrade();
        p.qty = pq.qty;
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
                    if (PARAMS.anyEnabled() && p.qty > 0) portfolioAddRealized(estimateRealizedNet(p.isLong, p.qty, p.entry, exitPx, PARAMS));
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
            // Portfolio gate with FRESH exchange numbers (liquidation / cooldown / max positions / max deployed margin).
            String pg = portfolioGateFresh();
            if (pg != null) {
                System.out.println("SIGNAL CANCELLED - " + pair + " - portfolio gate: " + pg);
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
            invalidatePositionsCache();

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
            trade.qty = filledQty > 0 ? filledQty : qty;
            tradeState.put(pair, trade);
            saveTradeState();
            active.add(pair);
            portfolioDeployedMarginUsdt += newPositionMarginUsdt();
            invalidatePositionsCache();
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
        handleClosedTrades(active, CoinDCXFuturesTrader8C_BUY_SELL_NEW_LOGIC_THREE::getLastPrice);
    }

    // priceFn gives the last price of a pair (the portfolio pass shares its cached prices; the self-tests inject a fake)
    static void handleClosedTrades(Set<String> active, java.util.function.ToDoubleFunction<String> priceFn) {
        boolean removed = false;
        for (Iterator<Map.Entry<String, TradeInfo>> it = tradeState.entrySet().iterator(); it.hasNext();) {
            Map.Entry<String, TradeInfo> en = it.next();
            if (active.contains(en.getKey())) continue;
            double px = priceFn.applyAsDouble(en.getKey());
            // positions closed on their own (SL / TP / trailing) are booked into the portfolio cycle as realized PnL
            if (!portfolioClosedPairs.containsKey(en.getKey())) portfolioRecordIndividualClose(en.getKey(), en.getValue(), px);
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
        String pr = portfolioClosedPairs.remove(pair);      // set when the position was closed by a portfolio close-all
        String exit = pr != null ? pr : classifyExit(t, lastPx);
        double[] m = mfeMaeR(t);
        long heldMin = t.openedMs > 0 ? (System.currentTimeMillis() - t.openedMs) / 60_000L : -1;
        System.out.printf("[CLOSED] %s %s exit=%s entry=%.6f initialSl=%.6f finalSl=%.6f finalTp=%.6f lastPx=%.6f held=%dmin trailing=%s MFE=%.2fR MAE=%.2fR | %s%n",
                pair, t.isLong ? "LONG" : "SHORT", exit, t.entry, t.initialSl, t.sl, t.tp, lastPx, heldMin,
                t.trailActive ? "was active" : "never activated", m[0], m[1], t.ctx);
        if (!LOG_TRADES_CSV || selfTestMode) return;
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
            JSONArray arr = parsePositionsResponse(resp);
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

    // A positions response must be a JSON array, or a single position object. An error body is turned into an exception so the
    // caller sees an API FAILURE (null) instead of a bogus "no positions" (which would wipe state / confirm a liquidation).
    static JSONArray parsePositionsResponse(String resp) {
        if (resp == null || resp.trim().isEmpty()) throw new IllegalStateException("empty positions response");
        String t = resp.trim();
        if (t.startsWith("[")) return new JSONArray(t);
        JSONObject o = new JSONObject(t);
        if (!o.has("pair") && (isErrorResponse(t) || o.has("message") || o.has("error") || o.has("code")))
            throw new IllegalStateException("unexpected positions response: " + (t.length() > 120 ? t.substring(0, 120) : t));
        return o.has("pair") ? new JSONArray().put(o) : new JSONArray();
    }

    // One positions snapshot shared by the trailing pass and the portfolio pass (max age in ms; 0 = always fetch).
    private static Map<String, JSONObject> posCache = null;
    private static long posCacheAt = 0;

    private static Map<String, JSONObject> getOpenPositionsMapCached(long maxAgeMs) {
        long now = System.currentTimeMillis();
        if (maxAgeMs > 0 && posCache != null && now - posCacheAt <= maxAgeMs) return posCache;
        Map<String, JSONObject> m = getOpenPositionsMap();
        if (m != null) { posCache = m; posCacheAt = now; } else posCache = null;
        return m;
    }

    private static void invalidatePositionsCache() { posCache = null; }

    // Live price shared by the trailing pass and the portfolio pass (short TTL, so no duplicate price calls).
    private static final Map<String, double[]> priceCache = new ConcurrentHashMap<>();   // pair -> {price, fetchedAtMs}

    static double getLivePrice(String pair) {
        long now = System.currentTimeMillis();
        double[] c = priceCache.get(pair);
        if (c != null && now - (long) c[1] <= PRICE_CACHE_MS) return c[0];
        double p = getLastPrice(pair);
        if (p > 0) priceCache.put(pair, new double[]{p, now});
        return p;
    }

    // Fresh exchange numbers right before an order: null = entry allowed. An API failure blocks the entry (safe side).
    private static String portfolioGateFresh() {
        Map<String, PosRec> fresh = toRecs(getOpenPositionsMapCached(0));
        if (fresh == null) return "positions API failed — cannot verify the portfolio limits";
        int n = 0; double dep = 0;
        for (PosRec r : fresh.values()) if (r.open()) { n++; dep += positionMargin(r.q, r.avg, r.lev); }
        portfolioOpenCount = n; portfolioDeployedMarginUsdt = dep;
        return portfolioEntryBlockedReason(System.currentTimeMillis(), n, dep, newPositionMarginUsdt());
    }

    private static void runTrailingPass() {
        lastTrailPassMs = System.currentTimeMillis();
        if (portfolioLiquidating()) { System.out.println("[TRAIL] portfolio liquidation in progress — trailing pass skipped"); return; }
        Map<String, JSONObject> positions = getOpenPositionsMapCached(POSITIONS_CACHE_MS);
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
    // PORTFOLIO MANAGEMENT V2  (one combined portfolio: hard target, breakeven, trailing floor, max loss, close-all)
    //
    //   UNITS: everything is computed in USDT (the quote currency of price/PnL) and converted with USDT_INR_RATE only for
    //          INR display. Leveraged notional is NEVER used as a basis.
    //   NET PnL = unrealized gross PnL - estimated entry fees - estimated exit fees - funding + realized PnL of the cycle
    //   BASIS   = current LOCKED MARGIN = sum over open positions of |qty|*avg/leverage (live, no configured capital)
    //   Everything runs on the MAIN thread (cooperatively, between symbol evaluations): no second thread can send a conflicting
    //   order / close / SL-TP request.
    // =========================================================================
    static final String EXIT_HARD_TP    = "PORTFOLIO_HARD_TP";
    static final String EXIT_BREAKEVEN  = "PORTFOLIO_BREAKEVEN";
    static final String EXIT_TRAILING   = "PORTFOLIO_TRAILING_SL";
    static final String EXIT_RISK_LIMIT = "PORTFOLIO_RISK_LIMIT";

    // ---- all tunables in one object so the pure logic can be unit-tested with other values ----
    static class PortfolioParams {
        boolean targetEnabled = PORTFOLIO_PROFIT_TARGET_ENABLED;
        double  targetPct = PORTFOLIO_PROFIT_TARGET_PERCENT;
        boolean beEnabled = PORTFOLIO_BREAKEVEN_ENABLED;
        double  beTrigPct = PORTFOLIO_BREAKEVEN_TRIGGER_PERCENT, beLockPct = PORTFOLIO_BREAKEVEN_LOCK_PERCENT;
        boolean trEnabled = PORTFOLIO_TRAILING_ENABLED;
        double  trActPct = PORTFOLIO_TRAILING_ACTIVATION_PERCENT, trGivebackPct = PORTFOLIO_TRAILING_GIVEBACK_PERCENT;
        boolean maxLossEnabled = PORTFOLIO_MAX_LOSS_ENABLED;
        double  maxLossPct = PORTFOLIO_MAX_LOSS_PERCENT;
        double  feeRatePct = PORTFOLIO_FEE_RATE_PERCENT, gstPct = PORTFOLIO_FEE_GST_PERCENT;
        double feeFactor() { return feeRatePct / 100.0 * (1.0 + gstPct / 100.0); }
        boolean anyEnabled() { return targetEnabled || beEnabled || trEnabled || maxLossEnabled; }
    }
    static PortfolioParams PARAMS = new PortfolioParams();

    // ---- persisted cycle state ----
    static class PortfolioState {
        long   cycleId;
        long   cycleStartMs;            // 0 = no active cycle
        double peakNet;                 // highest NET portfolio PnL of the cycle (USDT)
        boolean beActive, trailActive, floorActive;
        double floor;                   // protected net profit floor (USDT) - never decreases within a cycle
        String floorSource = "";        // BREAKEVEN / TRAILING
        double realizedNet;             // estimated realized PnL (net of fees) of positions closed individually in this cycle
        boolean liquidating;
        String  liquidationReason = "";
        long    liquidationStartMs;
        int     liquidationRounds;
        double  liquidationNet;         // net PnL snapshot at the moment the exit fired
        long    reentryUntilMs, riskPauseUntilMs;
        String  lastExitReason = "";
        double  lastFinalPnl;
        long    lastCycleEndMs;
    }
    static PortfolioState pState = new PortfolioState();
    static String portfolioStateFile = PORTFOLIO_STATE_FILE;

    static JSONObject portfolioToJson(PortfolioState s) {
        JSONObject j = new JSONObject();
        j.put("cycleId", s.cycleId); j.put("cycleStartMs", s.cycleStartMs); j.put("peakNet", s.peakNet);
        j.put("beActive", s.beActive); j.put("trailActive", s.trailActive); j.put("floorActive", s.floorActive);
        j.put("floor", s.floor); j.put("floorSource", s.floorSource); j.put("realizedNet", s.realizedNet);
        j.put("liquidating", s.liquidating); j.put("liquidationReason", s.liquidationReason);
        j.put("liquidationStartMs", s.liquidationStartMs); j.put("liquidationRounds", s.liquidationRounds);
        j.put("liquidationNet", s.liquidationNet);
        j.put("reentryUntilMs", s.reentryUntilMs); j.put("riskPauseUntilMs", s.riskPauseUntilMs);
        j.put("lastExitReason", s.lastExitReason); j.put("lastFinalPnl", s.lastFinalPnl); j.put("lastCycleEndMs", s.lastCycleEndMs);
        return j;
    }

    static PortfolioState portfolioFromJson(JSONObject j) {
        PortfolioState s = new PortfolioState();
        s.cycleId = j.optLong("cycleId", 0); s.cycleStartMs = j.optLong("cycleStartMs", 0); s.peakNet = j.optDouble("peakNet", 0);
        s.beActive = j.optBoolean("beActive", false); s.trailActive = j.optBoolean("trailActive", false);
        s.floorActive = j.optBoolean("floorActive", false); s.floor = j.optDouble("floor", 0);
        s.floorSource = j.optString("floorSource", ""); s.realizedNet = j.optDouble("realizedNet", 0);
        s.liquidating = j.optBoolean("liquidating", false); s.liquidationReason = j.optString("liquidationReason", "");
        s.liquidationStartMs = j.optLong("liquidationStartMs", 0); s.liquidationRounds = j.optInt("liquidationRounds", 0);
        s.liquidationNet = j.optDouble("liquidationNet", 0);
        s.reentryUntilMs = j.optLong("reentryUntilMs", 0); s.riskPauseUntilMs = j.optLong("riskPauseUntilMs", 0);
        s.lastExitReason = j.optString("lastExitReason", ""); s.lastFinalPnl = j.optDouble("lastFinalPnl", 0);
        s.lastCycleEndMs = j.optLong("lastCycleEndMs", 0);
        return s;
    }

    // Atomic write (temp file + rename), same pattern as the trade state.
    private static synchronized void savePortfolioState() {
        try {
            File target = new File(portfolioStateFile);
            File tmp = new File(portfolioStateFile + ".tmp");
            Files.write(tmp.toPath(), portfolioToJson(pState).toString(2).getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception atomicNotSupported) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            System.err.println("savePortfolioState failed: " + e.getMessage());
        }
    }

    static void loadPortfolioState() {
        File f = new File(portfolioStateFile);
        if (!f.exists()) { System.out.println("[PORTFOLIO] no saved portfolio state (" + portfolioStateFile + ") — starting fresh"); return; }
        try {
            pState = portfolioFromJson(new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)));
            System.out.printf("[PORTFOLIO] state restored: cycle #%d active=%s peak=%.4f USDT BE=%s trail=%s floor=%s liquidating=%s%n",
                    pState.cycleId, pState.cycleStartMs > 0, pState.peakNet, pState.beActive, pState.trailActive,
                    pState.floorActive ? String.format("%.4f", pState.floor) : "none", pState.liquidating);
        } catch (Exception e) {
            System.err.println("[PORTFOLIO] could not read " + portfolioStateFile + " (" + e.getMessage() + ") — backing it up, starting fresh");
            try { Files.move(f.toPath(), new File(portfolioStateFile + ".corrupt").toPath(), StandardCopyOption.REPLACE_EXISTING); }
            catch (Exception ignored) {}
            pState = new PortfolioState();
        }
    }

    // ---- pure calculations (no I/O, unit-tested) ----
    static class PosView { String pair; double q, avg, px, lev, funding; }

    static class PortfolioMetrics {
        int n;
        double margin, gross, entryFees, exitFees, funding, realized, net, basis;
        double targetAmt, beTrigAmt, beLockAmt, trActAmt, maxLossAmt;
    }

    // basis in USDT
    static double portfolioBasisUsdt(double lockedMarginUsdt) {
        return lockedMarginUsdt;
    }

    // Net PnL of one position given a price (fees estimated on both sides; funding = net funding PAID, positive = cost)
    static PortfolioMetrics computeMetrics(List<PosView> views, double realizedCycle, PortfolioParams p) {
        PortfolioMetrics m = new PortfolioMetrics();
        for (PosView v : views) {
            m.n++;
            m.gross     += positionPnl(v.q, v.avg, v.px);
            m.margin    += positionMargin(v.q, v.avg, v.lev);
            m.entryFees += Math.abs(v.q) * v.avg * p.feeFactor();
            m.exitFees  += Math.abs(v.q) * v.px  * p.feeFactor();
            m.funding   += v.funding;
        }
        m.realized = realizedCycle;
        m.net = m.gross - m.entryFees - m.exitFees - m.funding + m.realized;
        applyThresholds(m, p);
        return m;
    }

    // basis + every threshold in USDT (all percentages are % of the basis)
    static void applyThresholds(PortfolioMetrics m, PortfolioParams p) {
        m.basis = portfolioBasisUsdt(m.margin);
        m.targetAmt  = m.basis * p.targetPct / 100.0;
        m.beTrigAmt  = m.basis * p.beTrigPct / 100.0;
        m.beLockAmt  = p.beEnabled ? m.basis * p.beLockPct / 100.0 : 0.0;
        m.trActAmt   = m.basis * p.trActPct / 100.0;
        m.maxLossAmt = m.basis * p.maxLossPct / 100.0;
    }

    static class PortfolioDecision {
        String exitReason;                       // null = hold
        String detail = "";
        boolean stateChanged;
        final List<String> events = new ArrayList<>();
    }

    // Updates peak / breakeven / trailing floor (monotonic) and decides whether the whole portfolio must be closed.
    // Priority: HARD TP  >  RISK LIMIT  >  protected floor (trailing / breakeven).
    static PortfolioDecision decidePortfolio(PortfolioState st, PortfolioMetrics m, PortfolioParams p) {
        PortfolioDecision d = new PortfolioDecision();
        if (!(m.basis > 0)) return d;
        final double eps = 1e-9;
        if (m.net > st.peakNet + eps) { st.peakNet = m.net; d.stateChanged = true; }

        if (p.targetEnabled && m.net >= m.targetAmt - eps) {
            d.exitReason = EXIT_HARD_TP;
            d.detail = String.format("net %.4f >= target %.4f USDT (%.2f%%)", m.net, m.targetAmt, p.targetPct);
            return d;
        }
        if (p.maxLossEnabled && m.net <= -m.maxLossAmt + eps) {
            d.exitReason = EXIT_RISK_LIMIT;
            d.detail = String.format("net %.4f <= -%.4f USDT (-%.2f%%)", m.net, m.maxLossAmt, p.maxLossPct);
            return d;
        }
        if (p.beEnabled && !st.beActive && st.peakNet >= m.beTrigAmt - eps) {
            st.beActive = true; d.stateChanged = true;
            d.events.add(String.format("[PORTFOLIO-BE] breakeven protection ACTIVATED at net %.4f USDT (trigger %.4f)", m.net, m.beTrigAmt));
        }
        if (p.trEnabled && !st.trailActive && st.peakNet >= m.trActAmt - eps) {
            st.trailActive = true; d.stateChanged = true;
            d.events.add(String.format("[PORTFOLIO-TRAIL] trailing profit lock ACTIVATED at net %.4f USDT (activation %.4f)", m.net, m.trActAmt));
        }
        // ProtectedProfit = max(BreakevenLockAmount, Peak - AllowedGiveback)
        double cand = Double.NEGATIVE_INFINITY;
        String src = "";
        if (st.beActive) { cand = m.beLockAmt; src = EXIT_BREAKEVEN; }
        if (st.trailActive) {
            double tf = Math.max(m.beLockAmt, st.peakNet - st.peakNet * p.trGivebackPct / 100.0);
            if (tf > cand + eps || cand == Double.NEGATIVE_INFINITY) { cand = tf; src = tf > m.beLockAmt + eps ? EXIT_TRAILING : EXIT_BREAKEVEN; }
        }
        if (cand > Double.NEGATIVE_INFINITY && (!st.floorActive || cand > st.floor + eps)) {
            double old = st.floor; boolean had = st.floorActive;
            st.floor = cand; st.floorActive = true; st.floorSource = src; d.stateChanged = true;
            d.events.add(String.format("%s protected profit floor %s -> %.4f USDT (peak %.4f)", EXIT_TRAILING.equals(src) ? "[PORTFOLIO-TRAIL]" : "[PORTFOLIO-BE]",
                    had ? String.format("%.4f", old) : "none", st.floor, st.peakNet));
        }
        if (st.floorActive && m.net <= st.floor + eps) {
            d.exitReason = EXIT_TRAILING.equals(st.floorSource) ? EXIT_TRAILING : EXIT_BREAKEVEN;
            d.detail = String.format("net %.4f <= protected floor %.4f USDT (peak %.4f)", m.net, st.floor, st.peakNet);
        }
        return d;
    }

    // estimated realized PnL of a fully closed position, net of both fees (USDT)
    static double estimateRealizedNet(boolean isLong, double qtyAbs, double entry, double exit, PortfolioParams p) {
        double gross = (exit - entry) * (isLong ? qtyAbs : -qtyAbs);
        return gross - qtyAbs * (entry + exit) * p.feeFactor();
    }

    static double inr(double usdt) { return usdt * USDT_INR_RATE; }

    // =========================================================================
    // Exchange abstraction (LIVE and PAPER implement it; the self-tests use a scripted fake)
    // =========================================================================
    static class PosRec {
        String pair, id = "";
        double q, avg, lev;
        boolean open() { return Math.abs(q) > 0; }
    }

    interface PortfolioOps {
        Map<String, PosRec> fetchOpen();                 // may be a <=1.5s cached snapshot; null = API failure
        Map<String, PosRec> fetchOpenFresh();            // never cached; null = API failure
        double price(String pair);                       // <= 0 = unavailable
        boolean close(PosRec p, String reason);          // true = close request accepted
        void sleep(long ms);
        long now();
        void closedAccounting(Set<String> openPairs);    // bookkeeping of positions that closed on their own
        boolean persist();
    }

    // ---- runtime (not persisted) ----
    private static long lastPortfolioCheckMs = 0, portfolioLastLogMs = 0, portfolioIncompleteLogMs = 0;
    private static boolean portfolioBusy = false;
    private static double portfolioDeployedMarginUsdt = 0;
    private static int portfolioOpenCount = 0;
    private static long portfolioSnapshotMs = 0;
    private static final Map<String, Long> closeRequestedAt = new HashMap<>();
    private static final Map<String, Integer> closeAttempts = new HashMap<>();
    // pair -> portfolio exit reason, for positions closed by a close-all (used to label the trade log)
    private static final Map<String, String> portfolioClosedPairs = new ConcurrentHashMap<>();

    private static void persistState(PortfolioOps ops) { if (ops.persist()) savePortfolioState(); }

    // Why a NEW entry is not allowed right now (null = allowed). newMarginUsdt = margin the new position would lock.
    static String portfolioEntryBlockedReason(long now, int openCount, double deployedMarginUsdt, double newMarginUsdt) {
        PortfolioState st = pState;
        if (st.liquidating) return "portfolio liquidation in progress (" + st.liquidationReason + ")";
        if (now < st.riskPauseUntilMs) return "portfolio risk pause for another " + ((st.riskPauseUntilMs - now) / 60_000L) + " min";
        if (now < st.reentryUntilMs) return "portfolio re-entry cooldown for another " + ((st.reentryUntilMs - now) / 1000L) + "s";
        if (PORTFOLIO_MAX_OPEN_POSITIONS > 0 && openCount >= PORTFOLIO_MAX_OPEN_POSITIONS)
            return "PORTFOLIO_MAX_OPEN_POSITIONS (" + PORTFOLIO_MAX_OPEN_POSITIONS + ") reached";
        return null;
    }

    // ---------------------------------------------------------------------------------------------
    // One portfolio pass (single-threaded; re-entrancy guarded)
    // ---------------------------------------------------------------------------------------------
    static void portfolioPass(PortfolioOps ops) {
        if (portfolioBusy) return;
        portfolioBusy = true;
        try {
            portfolioPassInner(ops);
        } catch (Exception e) {
            System.err.println("[PORTFOLIO] pass error: " + e.getMessage());
        } finally {
            portfolioBusy = false;
        }
    }

    private static void portfolioPassInner(PortfolioOps ops) {
        PortfolioState st = pState;
        long now = ops.now();
        Map<String, PosRec> all = ops.fetchOpen();
        if (all == null) {
            System.err.println("[PORTFOLIO] positions API failed — no portfolio decision this pass" + (st.liquidating ? " (liquidation continues on the next pass)" : ""));
            return;
        }
        List<PosRec> open = new ArrayList<>();
        Set<String> openPairs = new HashSet<>();
        for (PosRec r : all.values()) if (r.open()) { open.add(r); openPairs.add(r.pair); }
        ops.closedAccounting(openPairs);          // positions that closed on their own -> realized PnL is booked into the cycle

        double dep = 0;
        for (PosRec r : open) dep += positionMargin(r.q, r.avg, r.lev);
        portfolioOpenCount = open.size();
        portfolioDeployedMarginUsdt = dep;
        portfolioSnapshotMs = now;

        if (open.isEmpty()) {
            if (st.liquidating) finalizeLiquidation(ops, now);
            else if (st.cycleStartMs > 0) endCycleNaturally(ops, now);
            return;
        }
        if (st.cycleStartMs == 0) {
            st.cycleId++; st.cycleStartMs = now; st.peakNet = 0;
            System.out.println("[PORTFOLIO-RESET] new portfolio cycle #" + st.cycleId + " started with " + open.size() + " position(s)");
            persistState(ops);
        }
        if (st.liquidating) { liquidationStep(ops, open, now); return; }

        // ---- net PnL: every price/position value must be known, otherwise NO decision (never a fake zero) ----
        List<PosView> views = new ArrayList<>();
        String why = null;
        for (PosRec r : open) {
            double px = ops.price(r.pair);
            if (!(px > 0)) { why = "no price for " + r.pair; break; }
            if (!(r.avg > 0)) { why = "no average entry for " + r.pair; break; }
            if (!(r.lev > 0)) { why = "no leverage for " + r.pair; break; }
            PosView v = new PosView();
            v.pair = r.pair; v.q = r.q; v.avg = r.avg; v.px = px; v.lev = r.lev; v.funding = 0;
            views.add(v);
        }
        if (why != null) {
            if (now - portfolioIncompleteLogMs >= 30_000L) { System.err.println("[PORTFOLIO] incomplete data (" + why + ") — exit decision skipped this pass"); portfolioIncompleteLogMs = now; }
            return;
        }
        PortfolioMetrics m = computeMetrics(views, st.realizedNet, PARAMS);
        PortfolioDecision d = decidePortfolio(st, m, PARAMS);
        for (String ev : d.events) System.out.println(ev);
        if (now - portfolioLastLogMs >= PORTFOLIO_STATUS_LOG_MS || d.exitReason != null) {
            System.out.printf("  [PORTFOLIO] cycle #%d | %d position(s) | basis=LOCKED_MARGIN %.2f USDT (INR %.0f) | lockedMargin=%.2f USDT (INR %.0f)%n",
                    st.cycleId, m.n, m.basis, inr(m.basis), m.margin, inr(m.margin));
            System.out.printf("  [PORTFOLIO] grossPnL=%.4f | fees(entry %.4f + exit %.4f) | funding=%.4f%s | realized(est)=%.4f | NET=%.4f USDT (INR %.2f) = %.2f%% of basis%n",
                    m.gross, m.entryFees, m.exitFees, m.funding, " (not provided by the positions API -> excluded)", m.realized, m.net, inr(m.net), m.net / m.basis * 100.0);
            System.out.printf("  [PORTFOLIO-TARGET] target=%.2f%% = %.4f USDT (INR %.2f) | peak=%.4f | BE=%s | trailing=%s | protectedFloor=%s%n",
                    PARAMS.targetPct, m.targetAmt, inr(m.targetAmt), st.peakNet, st.beActive ? "ACTIVE" : "waiting", st.trailActive ? "ACTIVE" : "waiting",
                    st.floorActive ? String.format("%.4f USDT (%s)", st.floor, st.floorSource) : "none");
            portfolioLastLogMs = now;
        }
        if (d.stateChanged) persistState(ops);
        if (d.exitReason == null) return;

        // ---- exit condition hit: start the liquidation (state is persisted BEFORE any close request is sent) ----
        st.liquidating = true; st.liquidationReason = d.exitReason; st.liquidationStartMs = now;
        st.liquidationRounds = 0; st.liquidationNet = m.net;
        closeRequestedAt.clear(); closeAttempts.clear();
        persistState(ops);
        String tag = EXIT_HARD_TP.equals(d.exitReason) ? "[PORTFOLIO-TARGET]" : EXIT_TRAILING.equals(d.exitReason) ? "[PORTFOLIO-TRAIL]"
                : EXIT_BREAKEVEN.equals(d.exitReason) ? "[PORTFOLIO-BE]" : "[PORTFOLIO]";
        System.out.printf("%s %s — %s | closing ALL %d position(s), new entries stopped%n", tag, d.exitReason, d.detail, open.size());
        System.out.printf("[PORTFOLIO-LIQUIDATION] started: reason=%s net=%.4f USDT (INR %.2f) peak=%.4f%n", d.exitReason, m.net, inr(m.net), st.peakNet);
        liquidationStep(ops, open, now);
    }

    // Sends close requests (at most one per pair per PORTFOLIO_CLOSE_RETRY_INTERVAL_MS), verifies against the exchange,
    // and finalizes only when the exchange confirms that NO position is left. Retried on every pass until then.
    private static void liquidationStep(PortfolioOps ops, List<PosRec> open, long now) {
        PortfolioState st = pState;
        st.liquidationRounds++;
        int sent = 0, failed = 0, waiting = 0;
        for (PosRec r : open) {
            long last = closeRequestedAt.getOrDefault(r.pair, 0L);
            if (last > 0 && now - last < PORTFOLIO_CLOSE_RETRY_INTERVAL_MS) { waiting++; continue; }     // no duplicate close request
            closeRequestedAt.put(r.pair, now);
            int attempt = closeAttempts.merge(r.pair, 1, Integer::sum);
            boolean ok = r.id != null && !r.id.isEmpty() && ops.close(r, st.liquidationReason);
            if (ok) { sent++; portfolioClosedPairs.put(r.pair, st.liquidationReason); }
            else {
                failed++;
                System.err.println("[PORTFOLIO-LIQUIDATION] close request FAILED for " + r.pair + " (attempt " + attempt + ") — will retry");
            }
        }
        System.out.printf("[PORTFOLIO-LIQUIDATION] round %d: requested=%d failed=%d pendingVerification=%d of %d open%n", st.liquidationRounds, sent, failed, waiting, open.size());
        ops.sleep(PORTFOLIO_CLOSE_VERIFY_DELAY_MS);
        Map<String, PosRec> after = ops.fetchOpenFresh();
        if (after == null) {
            System.err.println("[PORTFOLIO-LIQUIDATION] cannot verify (positions API failed) — liquidation stays active, retry next pass");
            persistState(ops);
            return;
        }
        List<String> left = new ArrayList<>();
        for (PosRec r : after.values()) if (r.open()) left.add(r.pair);
        if (left.isEmpty()) { finalizeLiquidation(ops, ops.now()); return; }
        System.out.println("[PORTFOLIO-LIQUIDATION] still open on the exchange: " + left + " — retrying");
        persistState(ops);
    }

    private static void finalizeLiquidation(PortfolioOps ops, long now) {
        PortfolioState st = pState;
        ops.closedAccounting(new HashSet<String>());      // logs the closed trades (labelled with the portfolio exit reason)
        double fin = st.liquidationNet;
        System.out.printf("[PORTFOLIO-LIQUIDATION] ALL positions confirmed closed after %d round(s). Reason=%s%n", st.liquidationRounds, st.liquidationReason);
        System.out.printf("[PORTFOLIO-RESET] cycle #%d final realized portfolio PnL ≈ %.4f USDT (INR %.2f) [estimate from the last marks + fees; actual fills may differ] | peak was %.4f%n",
                st.cycleId, fin, inr(fin), st.peakNet);
        st.lastExitReason = st.liquidationReason; st.lastFinalPnl = fin; st.lastCycleEndMs = now;
        if (EXIT_RISK_LIMIT.equals(st.liquidationReason)) {
            st.riskPauseUntilMs = now + PORTFOLIO_RISK_PAUSE_MS;
            System.out.println("[PORTFOLIO-RESET] max-loss reached — new entries paused for " + (PORTFOLIO_RISK_PAUSE_MS / 60_000L) + " min");
        }
        resetCycle(st);
        st.reentryUntilMs = now + PORTFOLIO_REENTRY_PAUSE_MS;
        closeRequestedAt.clear(); closeAttempts.clear(); portfolioClosedPairs.clear();
        System.out.println("[PORTFOLIO-RESET] peak / breakeven / trailing / cycle state reset; fresh cycle starts with the next position (re-entry cooldown " + (PORTFOLIO_REENTRY_PAUSE_MS / 1000L) + "s)");
        persistState(ops);
    }

    private static void endCycleNaturally(PortfolioOps ops, long now) {
        PortfolioState st = pState;
        System.out.printf("[PORTFOLIO-RESET] cycle #%d ended: every position closed on its own (SL/TP/trailing). Realized(est)=%.4f USDT (INR %.2f), peak was %.4f%n",
                st.cycleId, st.realizedNet, inr(st.realizedNet), st.peakNet);
        st.lastExitReason = "ALL_CLOSED_INDIVIDUALLY"; st.lastFinalPnl = st.realizedNet; st.lastCycleEndMs = now;
        resetCycle(st);
        persistState(ops);
    }

    private static void resetCycle(PortfolioState st) {
        st.cycleStartMs = 0; st.peakNet = 0; st.beActive = false; st.trailActive = false; st.floorActive = false;
        st.floor = 0; st.floorSource = ""; st.realizedNet = 0;
        st.liquidating = false; st.liquidationReason = ""; st.liquidationRounds = 0; st.liquidationStartMs = 0; st.liquidationNet = 0;
    }

    // Realized PnL of a position that closed on its own (SL / TP / trailing) is booked into the running cycle.
    static void portfolioAddRealized(double pnlUsdt) {
        if (pState.cycleStartMs == 0) pState.cycleStartMs = System.currentTimeMillis();
        pState.realizedNet += pnlUsdt;
    }

    private static void portfolioRecordIndividualClose(String pair, TradeInfo t, double lastPx) {
        if (!PARAMS.anyEnabled()) return;
        if (t.qty <= 0) { System.err.println("[PORTFOLIO] realized PnL of " + pair + " not booked: quantity unknown (older saved state) — net may be slightly off this cycle"); return; }
        String ex = classifyExit(t, lastPx);
        double exitPx = ex.startsWith("TP") ? t.tp : ex.startsWith("SL") ? t.sl : lastPx;
        if (!(exitPx > 0)) { System.err.println("[PORTFOLIO] realized PnL of " + pair + " not booked: exit price unknown"); return; }
        double pnl = estimateRealizedNet(t.isLong, t.qty, t.entry, exitPx, PARAMS);
        portfolioAddRealized(pnl);
        System.out.printf("  [PORTFOLIO] %s closed on its own (%s) — realized(est) %.4f USDT booked into cycle #%d (cycle realized now %.4f)%n", pair, ex, pnl, pState.cycleId, pState.realizedNet);
        if (!selfTestMode) savePortfolioState();
    }

    // =========================================================================
    // LIVE / PAPER adapters
    // =========================================================================
    private static Map<String, PosRec> toRecs(Map<String, JSONObject> m) {
        if (m == null) return null;
        Map<String, PosRec> out = new LinkedHashMap<>();
        for (Map.Entry<String, JSONObject> en : m.entrySet()) {
            JSONObject p = en.getValue();
            PosRec r = new PosRec();
            r.pair = en.getKey();
            r.id = p.optString("id", "");
            r.q = p.optDouble("active_pos", 0);
            r.avg = p.optDouble("avg_price", 0);
            double lev = p.optDouble("leverage", 0);
            r.lev = lev > 0 ? lev : LEVERAGE;        // the exchange value when present; the bot's configured leverage otherwise
            out.put(r.pair, r);
        }
        return out;
    }

    static final PortfolioOps LIVE_OPS = new PortfolioOps() {
        public Map<String, PosRec> fetchOpen()      { return toRecs(getOpenPositionsMapCached(POSITIONS_CACHE_MS)); }
        public Map<String, PosRec> fetchOpenFresh() { invalidatePositionsCache(); return toRecs(getOpenPositionsMapCached(0)); }
        public double price(String pair)            { return getLivePrice(pair); }
        public boolean close(PosRec p, String why)  { boolean ok = closePosition(p.id); invalidatePositionsCache(); return ok; }
        public void sleep(long ms)                  { sleepMs(ms); }
        public long now()                           { return System.currentTimeMillis(); }
        public void closedAccounting(Set<String> openPairs) { handleClosedTrades(openPairs, CoinDCXFuturesTrader8C_BUY_SELL_NEW_LOGIC_THREE::getLivePrice); }
        public boolean persist()                    { return true; }
    };

    static final PortfolioOps PAPER_OPS = new PortfolioOps() {
        private Map<String, PosRec> snap() {
            Map<String, PosRec> out = new LinkedHashMap<>();
            for (Map.Entry<String, PaperTrade> en : paperTrades.entrySet()) {
                PaperTrade t = en.getValue();
                if (t.qty <= 0) continue;
                PosRec r = new PosRec();
                r.pair = en.getKey(); r.id = en.getKey(); r.q = t.isLong ? t.qty : -t.qty; r.avg = t.entry; r.lev = LEVERAGE;
                out.put(r.pair, r);
            }
            return out;
        }
        public Map<String, PosRec> fetchOpen()      { return snap(); }
        public Map<String, PosRec> fetchOpenFresh() { return snap(); }
        public double price(String pair)            { return getLivePrice(pair); }
        public boolean close(PosRec p, String why) {
            double px = getLivePrice(p.pair);
            PaperTrade t = paperTrades.get(p.pair);
            if (t == null) return true;
            if (!(px > 0)) return false;
            logPaperClosed(p.pair, t, why, px);
            paperTrades.remove(p.pair);
            return true;
        }
        public void sleep(long ms)                  { }
        public long now()                           { return System.currentTimeMillis(); }
        public void closedAccounting(Set<String> openPairs) { }
        public boolean persist()                    { return false; }
    };

    // Entry point from runCycle and from inside the scan loop (rate limited; fastest of the two intervals wins)
    private static void runPortfolioProfitCheck() {
        if (!PARAMS.anyEnabled()) return;
        long now = System.currentTimeMillis();
        long gap = PARAMS.trEnabled ? Math.min(PORTFOLIO_CHECK_INTERVAL_MS, PORTFOLIO_TRAILING_CHECK_INTERVAL_MS) : PORTFOLIO_CHECK_INTERVAL_MS;
        if (now - lastPortfolioCheckMs < gap) return;
        lastPortfolioCheckMs = now;
        portfolioPass(currentOps());
    }

    private static boolean portfolioLiquidating() { return PARAMS.anyEnabled() && pState.liquidating; }

    // Keeps retrying a running liquidation until the exchange confirms everything is closed (bounded per call; the main loop calls again).
    private static void drainLiquidation(PortfolioOps ops) {
        long until = System.currentTimeMillis() + PORTFOLIO_LIQUIDATION_LOOP_MAX_MS;
        while (pState.liquidating && System.currentTimeMillis() < until) {
            ops.sleep(Math.max(1000L, PORTFOLIO_CLOSE_RETRY_INTERVAL_MS));
            portfolioPass(ops);
        }
    }

    // Capital-based sanity warnings for the configured numbers
    static void validatePortfolioConfig() {
        if (PORTFOLIO_BREAKEVEN_ENABLED && PORTFOLIO_BREAKEVEN_LOCK_PERCENT >= PORTFOLIO_BREAKEVEN_TRIGGER_PERCENT)
            System.err.println("WARNING: PORTFOLIO_BREAKEVEN_LOCK_PERCENT should be below PORTFOLIO_BREAKEVEN_TRIGGER_PERCENT");
        if (PORTFOLIO_PROFIT_TARGET_ENABLED && PORTFOLIO_TRAILING_ENABLED && PORTFOLIO_TRAILING_ACTIVATION_PERCENT >= PORTFOLIO_PROFIT_TARGET_PERCENT)
            System.err.println("WARNING: PORTFOLIO_TRAILING_ACTIVATION_PERCENT should be below PORTFOLIO_PROFIT_TARGET_PERCENT (the hard target would always fire first)");
        if (PORTFOLIO_BREAKEVEN_ENABLED && PORTFOLIO_TRAILING_ENABLED && PORTFOLIO_BREAKEVEN_TRIGGER_PERCENT > PORTFOLIO_TRAILING_ACTIVATION_PERCENT)
            System.err.println("WARNING: PORTFOLIO_BREAKEVEN_TRIGGER_PERCENT is above PORTFOLIO_TRAILING_ACTIVATION_PERCENT");
        double perMarginInr = FIXED_MARGIN / LEVERAGE;
        System.out.printf("[PORTFOLIO] basis = CURRENT LOCKED MARGIN (live from exchange) | one position locks ~INR %.0f margin | max %d positions%n",
                perMarginInr, PORTFOLIO_MAX_OPEN_POSITIONS);
        System.out.printf("[PORTFOLIO] target %.2f%% | BE trigger %.2f%% lock %.2f%% | trail activation %.2f%% giveback %.0f%% of peak | max loss %.2f%%  (all %% of locked margin)%n",
                PORTFOLIO_PROFIT_TARGET_PERCENT, PORTFOLIO_BREAKEVEN_TRIGGER_PERCENT, PORTFOLIO_BREAKEVEN_LOCK_PERCENT, PORTFOLIO_TRAILING_ACTIVATION_PERCENT,
                PORTFOLIO_TRAILING_GIVEBACK_PERCENT, PORTFOLIO_MAX_LOSS_PERCENT);
    }

    private static void trailOne(String pair, JSONObject pos) {
        double signedQty = pos.optDouble("active_pos", 0);
        double avg = pos.optDouble("avg_price", 0);
        if (Math.abs(signedQty) <= 0 || avg <= 0) return;           // closed / not filled yet -> nothing to trail
        double px = getLivePrice(pair);
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

        if (Math.abs(t.qty - Math.abs(signedQty)) > 1e-12) { t.qty = Math.abs(signedQty); dirty = true; }   // quantity for the portfolio realized-PnL estimate

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
        if (portfolioLiquidating()) return;     // positions are being closed: no TP/SL restoration meanwhile
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
            JSONArray arr = parsePositionsResponse(resp);
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
        selfTestMode = true;                                 // no CSV logs, state files redirected to a temp directory
        File tmpDir = new File(System.getProperty("java.io.tmpdir"), "dcx-selftest-" + System.nanoTime());
        tmpDir.mkdirs();
        tradeStateFile = new File(tmpDir, "trade_state_test.json").getPath();
        portfolioStateFile = new File(tmpDir, "portfolio_state_test.json").getPath();
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

        // (entry-logic and portfolio tests are in selfTestEntry() / selfTestPortfolio(), called at the end)

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

        selfTestEntry();
        selfTestPortfolio();

        System.out.println("\nSelf-test: " + (testsRun - testsFailed) + "/" + testsRun + " passed");
        return testsFailed == 0;
    }

    
    // =========================================================================
    // SELF-TESTS: trend-following entry logic
    // =========================================================================
    // scenario 0 = plain trend, 1 = pullback + confirmation candle at the end, 2 = fresh swing breakout at the end.
    // The last CLOSED 5M candle starts at cur5 - 5M. Volume: 1000 per candle, `volLast` on the last candle.
    private static Series5 entrySeries(long cur5, int scenario, double volLast, double slope) {
        int N = 1700;
        double[] c = new double[N], o = new double[N], h = new double[N], l = new double[N], v = new double[N];
        for (int i = 0; i < N; i++) c[i] = 100 + slope * i + 0.10 * Math.sin(i * 0.7);
        int B = N - 12;                       // first patched candle
        double T = c[B - 1];
        if (scenario == 1) {                  // shallow dip to the 5M EMA21 zone, then a confirmed bullish close
            double[] p = {-0.04, -0.08, -0.12, -0.15, -0.17, -0.18, -0.17, -0.16, -0.15, -0.14, -0.14, 0.10};
            for (int k = 0; k < 12; k++) c[B + k] = T + p[k];
        } else if (scenario == 2) {           // swing high at B+1, lower highs after it, last candle closes above it
            double[] p = {0.02, 0.18, 0.10, 0.04, 0.03, 0.04, 0.06, 0.07, 0.08, 0.09, 0.10, 0.42};
            for (int k = 0; k < 12; k++) c[B + k] = T + p[k];
        }
        Series5 s = new Series5(N);
        long last = cur5 - FIVE_MIN_MS;
        for (int i = 0; i < N; i++) {
            o[i] = i == 0 ? c[0] : c[i - 1];
            h[i] = Math.max(o[i], c[i]) + 0.10 + 0.002 * (i % 5);
            l[i] = Math.min(o[i], c[i]) - 0.10 - 0.002 * (i % 3);
            v[i] = 1000 + 30 * Math.sin(i);
            s.t[i] = last - (long) (N - 1 - i) * FIVE_MIN_MS;
            s.o[i] = o[i]; s.h[i] = h[i]; s.l[i] = l[i]; s.c[i] = c[i]; s.v[i] = v[i];
        }
        if (scenario == 2) s.h[B + 1] += 0.06;           // clear swing-high pivot (strictly higher than its neighbours)
        s.v[N - 1] = volLast;
        return s;
    }

    // exact mirror around `axis`: the same market upside down (volume unchanged)
    private static Series5 mirrorSeries(Series5 a, double axis) {
        Series5 m = new Series5(a.n);
        for (int i = 0; i < a.n; i++) {
            m.t[i] = a.t[i]; m.v[i] = a.v[i];
            m.o[i] = 2 * axis - a.o[i]; m.c[i] = 2 * axis - a.c[i];
            m.h[i] = 2 * axis - a.l[i]; m.l[i] = 2 * axis - a.h[i];
        }
        return m;
    }

    private static void why(Eval e) { if (!e.signal.equals("BUY") && !e.signal.equals("SELL")) System.out.println("      -> " + e.code + ": " + e.reason); }

    static void selfTestEntry() {
        long b12 = (1_760_000_000_000L / (12 * HOUR_MS)) * (12 * HOUR_MS);
        long cur5 = b12 + 235 * MIN_MS;
        long now = cur5 + 30_000L;

        System.out.println("[Test] ADX / EMA series helpers");
        double[] hiT = new double[80], loT = new double[80], clT = new double[80], hiF = new double[80], loF = new double[80], clF = new double[80];
        for (int i = 0; i < 80; i++) { clT[i] = 100 + i; hiT[i] = clT[i] + 0.5; loT[i] = clT[i] - 0.5; clF[i] = 100 + 0.1 * Math.sin(i); hiF[i] = clF[i] + 0.5; loF[i] = clF[i] - 0.5; }
        double[] adxT = calcADX(hiT, loT, clT, 14), adxF = calcADX(hiF, loF, clF, 14);
        check("ADX is high with +DI > -DI in a steady rise", adxT != null && adxT[0] > 40 && adxT[1] > adxT[2]);
        check("ADX is low in a sideways market", adxF != null && adxF[0] < 20);
        check("ADX needs enough bars", calcADX(hiT, loT, clT, 50) == null);
        double[] es = emaSeries(new double[]{1, 2, 3, 4, 5, 6}, 3);
        check("emaSeries: NaN before the first period, SMA seed, then recursive", Double.isNaN(es[1]) && Math.abs(es[2] - 2.0) < 1e-9 && Math.abs(es[3] - 3.0) < 1e-9);

        System.out.println("[Test] LONG entry: pullback / retest + confirmed bullish close");
        Series5 sPull = entrySeries(cur5, 1, 3000, 0.02);
        double pxL = sPull.c[sPull.n - 1] + 0.02;
        Eval eL = evaluateFull(sPull, pxL, 5_000, now, 1);
        why(eL);
        check("4H + 1H Supertrend GREEN, 15M EMA9 > EMA21, pullback confirmed, volume, BTC bullish -> BUY", eL.signal.equals("BUY"));
        check("trigger is PULLBACK or BREAKOUT, score 100 with every component met", (eL.trigger.equals("PULLBACK") || eL.trigger.equals("BREAKOUT")) && eL.score == 100);
        check("4H and 1H Supertrend are GREEN, ADX >= threshold", eL.dev[3] != null && eL.dev[3].stBull && eL.dev[0].stBull && eL.adx >= ADX_MIN);
        check("signal candle id = last CLOSED 5M candle", eL.candleOpen == cur5 - FIVE_MIN_MS);

        System.out.println("[Test] SHORT entry is the exact mirror of LONG");
        double axis = 134.0;
        Series5 sMir = mirrorSeries(sPull, axis);
        Eval eS = evaluateFull(sMir, 2 * axis - pxL, 5_000, now, -1);
        why(eS);
        check("mirrored market -> SELL", eS.signal.equals("SELL"));
        check("same trigger, same score, same ADX in both directions", eS.trigger.equals(eL.trigger) && eS.score == eL.score && Math.abs(eS.adx - eL.adx) < 1e-6);
        check("1H and 4H Supertrend RED, EMA9 < EMA21", eS.dev[3] != null && !eS.dev[3].stBull && !eS.dev[0].stBull && eS.ema9 < eS.ema21);
        check("symmetric ATR / EMA values", Math.abs(eS.atr5 - eL.atr5) < 1e-9 && Math.abs((axis - eS.ema9) - (eL.ema9 - axis)) < 1e-6);

        System.out.println("[Test] No countertrend entries (the old ST_CONTRARIAN behaviour is gone)");
        Eval st1Up = evaluateStage1(sPull, pxL, now), st1Dn = evaluateStage1(sMir, 2 * axis - pxL, now);
        check("uptrend market can only produce a LONG candidate", st1Up.dir == 1);
        check("downtrend market can only produce a SHORT candidate", st1Dn.dir == -1);
        Eval wrongWay = evaluateFull(sMir, 2 * axis - pxL, 5_000, now, 1);
        check("a downtrend never yields a BUY", !wrongWay.signal.equals("BUY"));

        System.out.println("[Test] 1H and 4H must agree (mixed higher timeframes -> no candidate)");
        int N = 1700;
        double[] cm = new double[N];
        for (int i = 0; i < N; i++) cm[i] = i < N - 36 ? 150 - 0.03 * i : 150 - 0.03 * (N - 36) + 0.10 * (i - (N - 36));
        Series5 sMixed = pathSeries(cm, cur5 - FIVE_MIN_MS, 0.05);
        Eval mixed = evaluateStage1(sMixed, cm[N - 1] + 0.1, now);
        check("long decline then a sharp 3h rally: 1H turns GREEN but 4H is still RED -> no candidate", mixed.dir == 0 && mixed.code.equals("stage1")
                && mixed.dev[0] != null && mixed.dev[3] != null && mixed.dev[0].stBull != mixed.dev[3].stBull);

        System.out.println("[Test] Fresh swing-high breakout (closed candle) with volume");
        Series5 sBrk = entrySeries(cur5, 2, 3000, 0.02);
        double pxB = sBrk.c[sBrk.n - 1] + 0.02;
        Eval eB = evaluateFull(sBrk, pxB, 5_000, now, 1);
        why(eB);
        check("breakout + volume -> BUY", eB.signal.equals("BUY"));
        check("trigger = BREAKOUT, level is the confirmed swing high", eB.trigger.equals("BREAKOUT") && eB.brkLevel > 0 && eB.brkLevel < sBrk.c[sBrk.n - 1]);
        Series5 sBrkLowVol = entrySeries(cur5, 2, 900, 0.02);
        Eval eBl = evaluateFull(sBrkLowVol, sBrkLowVol.c[sBrkLowVol.n - 1] + 0.02, 5_000, now, 1);
        check("breakout WITHOUT volume is rejected (weak breakout)", eBl.signal.equals("NO TRADE") && eBl.code.equals("trigger"));
        Series5 sMirB = mirrorSeries(sBrk, axis);
        Eval eBs = evaluateFull(sMirB, 2 * axis - pxB, 5_000, now, -1);
        check("mirrored swing-low breakdown -> SELL with the same score", eBs.signal.equals("SELL") && eBs.trigger.equals("BREAKOUT") && eBs.score == eB.score);

        System.out.println("[Test] Pullback with low volume still qualifies (volume is scored, not mandatory, for pullbacks)");
        Series5 sPullLow = entrySeries(cur5, 1, 900, 0.02);
        Eval ePl = evaluateFull(sPullLow, sPullLow.c[sPullLow.n - 1] + 0.02, 5_000, now, 1);
        why(ePl);
        check("low volume costs the volume points (score 90) but the setup still passes", ePl.signal.equals("BUY") && ePl.score == 90 && ePl.trigger.equals("PULLBACK"));

        System.out.println("[Test] BTC market filter");
        Eval bOpp = evaluateFull(sPull, pxL, 5_000, now, -1);
        check("BTC clearly bearish blocks an altcoin LONG (hard block)", bOpp.signal.equals("NO TRADE") && bOpp.code.equals("btcOpposed"));
        Eval bNeu = evaluateFull(sPull, pxL, 5_000, now, 0);
        check("BTC mixed -> half points, still a signal (score 95)", bNeu.signal.equals("BUY") && bNeu.score == 95);
        Eval bNa = evaluateFull(sPull, pxL, 5_000, now, BTC_UNAVAILABLE);
        check("BTC unavailable -> no BTC points (score 90), not a block", bNa.signal.equals("BUY") && bNa.score == 90);
        Eval bSelf = evaluateFull(sPull, pxL, 5_000, now, BTC_NA);
        check("the symbol is BTC itself -> filter not applicable", bSelf.signal.equals("BUY") && bSelf.score == 100);

        System.out.println("[Test] Mandatory safety conditions are never bypassed by the score");
        double lastC = sPull.c[sPull.n - 1];
        double atr5 = eL.atr5;
        Eval chased = evaluateFull(sPull, lastC + ENTRY_MAX_CHASE_ATR5 * atr5 * 1.5, 5_000, now, 1);
        check("price already ran > 0.6 ATR past the confirmation close -> chased", chased.code.equals("chased") && chased.signal.equals("NO TRADE"));
        Eval spikeP = evaluateFull(sPull, lastC * (1 + ENTRY_SPIKE_JUMP_PCT / 100.0 * 1.5), 5_000, now, 1);
        check("abnormal price jump from the last close -> spike", spikeP.code.equals("spike"));
        Eval faded = evaluateFull(sPull, sPull.o[sPull.n - 1] - 0.01, 5_000, now, 1);
        check("price fell back through the confirmation candle -> no entry", faded.signal.equals("NO TRADE"));
        Eval stale = evaluateFull(sPull, pxL, ENTRY_MAX_PRICE_AGE_MS + 1000, now, 1);
        check("stale last trade -> rejected", stale.code.equals("stalePrice"));
        Eval late = evaluateFull(sPull, pxL, 5_000, now + ENTRY_MAX_CANDLE_AGE_MS + 60_000L, 1);
        check("signal candle closed too long ago (late entry) -> rejected", late.signal.equals("NO TRADE"));
        Series5 steep = entrySeries(cur5, 1, 3000, 0.14);
        Eval stE = evaluateFull(steep, steep.c[steep.n - 1] + 0.02, 5_000, now, 1);
        check("price far above the 15M EMA21 in ATR terms (parabolic) is never bought", stE.signal.equals("NO TRADE"));
        Eval unknownAge = evaluateFull(sPull, pxL, -1, now, 1);
        check("unknown price age does not block", unknownAge.signal.equals("BUY"));
        Series5 thin = entrySeries(cur5, 1, 3000, 0.02);
        for (int i = 0; i < thin.n; i++) thin.v[i] = thin.v[i] * 1e-4;
        thin.v[thin.n - 1] = 0.3;
        Eval thinE = evaluateFull(thin, pxL, 5_000, now, 1);
        check("illiquid symbol (tiny traded notional) -> rejected", thinE.code.equals("illiquid"));
        Series5 spk = entrySeries(cur5, 1, 3000, 0.02);
        spk.h[spk.n - 1] = spk.c[spk.n - 1] + 3.0;             // huge upper wick on the signal candle
        Eval spkE = evaluateFull(spk, pxL, 5_000, now, 1);
        check("abnormal range on the signal candle -> spike", spkE.code.equals("spike"));
        Series5 gap = entrySeries(cur5, 1, 3000, 0.02);
        for (int i = 0; i < gap.n; i++) if (i >= gap.n - 20 && i < gap.n - 10) gap.t[i] += 1_000L;   // timestamps no longer contiguous
        check("gap inside the recent closed candles -> rejected, never guessed", evaluateStage1(gap, pxL, now).dir == 0);
        Series5 noVol = entrySeries(cur5, 1, 3000, 0.02);
        Arrays.fill(noVol.v, -1);
        Eval nv = evaluateFull(noVol, pxL, 5_000, now, 1);
        check("exchange without volume data: pullback still works, no volume points (score 90)", nv.signal.equals("BUY") && nv.score == 90);

        System.out.println("[Test] No repeated signals from the same closed candle");
        Map<String, Long> reg = new HashMap<>();
        check("first signal accepted", registerSignal(reg, "B-BTC_USDT|LONG", eL.candleOpen));
        check("same closed 5M candle, same direction rejected", !registerSignal(reg, "B-BTC_USDT|LONG", eL.candleOpen));
        check("next closed 5M candle accepted", registerSignal(reg, "B-BTC_USDT|LONG", eL.candleOpen + FIVE_MIN_MS));
        check("other direction tracked separately", registerSignal(reg, "B-BTC_USDT|SHORT", eL.candleOpen));

        System.out.println("[Test] Signals use only closed candles (the forming candle cannot change the decision)");
        Series5 withForming = new Series5(sPull.n + 1);
        System.arraycopy(sPull.t, 0, withForming.t, 0, sPull.n); System.arraycopy(sPull.o, 0, withForming.o, 0, sPull.n);
        System.arraycopy(sPull.h, 0, withForming.h, 0, sPull.n); System.arraycopy(sPull.l, 0, withForming.l, 0, sPull.n);
        System.arraycopy(sPull.c, 0, withForming.c, 0, sPull.n); System.arraycopy(sPull.v, 0, withForming.v, 0, sPull.n);
        int fi = sPull.n;
        withForming.t[fi] = cur5; withForming.o[fi] = lastC; withForming.c[fi] = lastC - 5; withForming.h[fi] = lastC + 1; withForming.l[fi] = lastC - 6; withForming.v[fi] = 99999;
        Eval f2 = evaluateFull(withForming, pxL, 5_000, now, 1);
        check("a wild forming candle in the data changes nothing", f2.signal.equals("BUY") && f2.score == eL.score && f2.candleOpen == eL.candleOpen);

        System.out.println("[Test] Order-book spread / depth check + positions-response parsing");
        BookInfo bk = parseBook("{\"bids\":{\"100.0\":\"50\",\"99.9\":\"40\"},\"asks\":{\"100.05\":\"60\",\"100.2\":\"10\"}}");
        check("order book (price->qty maps) parsed", bk != null && Math.abs(bk.bid - 100.0) < 1e-9 && Math.abs(bk.ask - 100.05) < 1e-9);
        check("tight spread + deep book passes", bk != null && bookReason(bk, true, 0.15, 200) == null);
        check("wide spread rejected", bk != null && bookReason(bk, true, 0.01, 200) != null);
        check("thin book rejected", bk != null && bookReason(bk, true, 0.15, 1e9) != null);
        BookInfo bk2 = parseBook("{\"bids\":[{\"price\":\"10\",\"quantity\":\"5\"}],\"asks\":[{\"price\":\"10.01\",\"quantity\":\"5\"}]}");
        check("order book (array form) parsed", bk2 != null && bk2.ask > bk2.bid);
        check("unparseable book -> null (treated as unavailable, never as OK-by-accident)", parseBook("<html>") == null && parseBook("{}") == null);
        boolean threw = false;
        try { parsePositionsResponse("{\"message\":\"rate limited\",\"code\":429}"); } catch (Exception ex) { threw = true; }
        check("error body is an API FAILURE, not 'no positions'", threw);
        check("empty array / single object / {} parse", parsePositionsResponse("[]").length() == 0
                && parsePositionsResponse("{\"pair\":\"B-X_USDT\",\"active_pos\":1}").length() == 1 && parsePositionsResponse("{}").length() == 0);
    }

    // =========================================================================
    // SELF-TESTS: portfolio management V2 (pure logic + liquidation against a scripted fake exchange)
    // =========================================================================
    static class FakeOps implements PortfolioOps {
        final Map<String, PosRec> pos = new LinkedHashMap<>();
        final Map<String, Double> prices = new HashMap<>();
        boolean apiDown = false, persist = false;
        final Set<String> failFirstClose = new HashSet<>();      // first close request for these pairs is rejected
        final Set<String> ignoreFirstClose = new HashSet<>();    // first close request is "accepted" but the position stays open (partial)
        final Map<String, Integer> closeTries = new HashMap<>();
        int closeCalls = 0, ordersPlaced = 0;
        long clock = 1_800_000_000_000L;

        PosRec add(String pair, double q, double avg, double px) {
            PosRec r = new PosRec(); r.pair = pair; r.id = "id-" + pair; r.q = q; r.avg = avg; r.lev = 5;
            pos.put(pair, r); prices.put(pair, px);
            return r;
        }
        public Map<String, PosRec> fetchOpen()      { return apiDown ? null : new LinkedHashMap<>(pos); }
        public Map<String, PosRec> fetchOpenFresh() { return apiDown ? null : new LinkedHashMap<>(pos); }
        public double price(String pair)            { return prices.getOrDefault(pair, 0.0); }
        public boolean close(PosRec p, String why) {
            closeCalls++;
            int n = closeTries.merge(p.pair, 1, Integer::sum);
            if (failFirstClose.contains(p.pair) && n == 1) return false;
            if (ignoreFirstClose.contains(p.pair) && n == 1) return true;
            pos.remove(p.pair);
            return true;
        }
        public void sleep(long ms)                  { clock += ms; }
        public long now()                           { return clock; }
        public void closedAccounting(Set<String> openPairs) { handleClosedTrades(openPairs, pr -> prices.getOrDefault(pr, 0.0)); }
        public boolean persist()                    { return persist; }
    }

    private static void resetPortfolioTestState() {
        pState = new PortfolioState();
        PARAMS = new PortfolioParams();
        tradeState.clear();
        portfolioClosedPairs.clear(); closeRequestedAt.clear(); closeAttempts.clear();
        portfolioBusy = false; portfolioLastLogMs = 0; portfolioIncompleteLogMs = 0;
    }

    // price at which ONE long position (q=1 @ 20 USDT) has the given NET pnl (fees on both sides included)
    private static double pxForNet(double net, PortfolioParams p) {
        double f = p.feeFactor();
        return (net + 20.0 + 20.0 * f) / (1.0 - f);
    }

    private static PortfolioMetrics metricsFor(PortfolioParams p, double net, double marginUsdt) {
        PortfolioMetrics m = new PortfolioMetrics();
        m.net = net; m.margin = marginUsdt; m.n = 1;
        applyThresholds(m, p);
        return m;
    }

    static void selfTestPortfolio() {
        PortfolioParams P = new PortfolioParams();
        final double CAP = 5000.0 / USDT_INR_RATE;             // test locked margin: INR 5000 -> 49.02 USDT (basis = locked margin)

        System.out.println("[Test] Portfolio target examples: INR 5000 locked margin -> 3% / 4% / 5% = INR 150 / 200 / 250");
        for (double pct : new double[]{3.0, 4.0, 5.0}) {
            PortfolioParams q = new PortfolioParams(); q.targetPct = pct;
            PortfolioMetrics m = metricsFor(q, 0, CAP);
            check(String.format("%.0f%% of INR 5000 locked margin = INR %.0f (leveraged notional does not change it)", pct, 5000 * pct / 100), Math.abs(inr(m.targetAmt) - 5000 * pct / 100) < 1e-6);
        }
        PortfolioParams dm = new PortfolioParams(); dm.targetPct = 4.0;
        PortfolioMetrics mdm = metricsFor(dm, 0, 40.0);
        check("locked-margin basis: 4% of 40 USDT locked margin = 1.6 USDT", Math.abs(mdm.targetAmt - 1.6) < 1e-9 && Math.abs(mdm.basis - 40.0) < 1e-9);
        check("basis always follows the current locked margin", Math.abs(metricsFor(P, 0, 999).basis - 999) < 1e-9 && Math.abs(metricsFor(P, 0, 12.5).basis - 12.5) < 1e-9);

        System.out.println("[Test] Net PnL: LONG + SHORT mixed, per-position leverage, fees");
        List<PosView> mix = new ArrayList<>();
        PosView a = new PosView(); a.pair = "A"; a.q = 0.2;  a.avg = 100; a.px = 102;  a.lev = 5;  mix.add(a);   // long  +0.40
        PosView b = new PosView(); b.pair = "B"; b.q = -0.4; b.avg = 50;  b.px = 48.5; b.lev = 10; mix.add(b);   // short +0.60
        PosView c = new PosView(); c.pair = "C"; c.q = 1;    c.avg = 20;  c.px = 19.5; c.lev = 5;  mix.add(c);   // long  -0.50
        PortfolioMetrics mm = computeMetrics(mix, 0.25, P);
        check("gross PnL: LONG and SHORT signs are correct", Math.abs(mm.gross - (0.40 + 0.60 - 0.50)) < 1e-9);
        check("margin uses each position's own leverage (2 + 4... = 4 + 2 + 4)", Math.abs(mm.margin - (20 / 5.0 + 20 / 10.0 + 20 / 5.0)) < 1e-9);
        double fees = (20 * 0.0005 * 1.18) * 3 + (0.2 * 102 + 0.4 * 48.5 + 1 * 19.5) * 0.0005 * 1.18;
        check("net = gross - entry fees - exit fees + realized of the cycle", Math.abs(mm.net - (mm.gross - fees + 0.25)) < 1e-9 && mm.net < mm.gross + 0.25);
        check("one negative position does not make the portfolio negative", mm.net > 0);

        System.out.println("[Test] Hard take-profit");
        PortfolioState st = new PortfolioState();
        PortfolioMetrics m1 = metricsFor(P, 1.0, CAP);
        PortfolioDecision d1 = decidePortfolio(st, m1, P);
        check("below the target: no exit", d1.exitReason == null);
        PortfolioDecision d2 = decidePortfolio(st, metricsFor(P, CAP * 0.03 + 1e-6, CAP), P);
        check("net >= 3% of capital -> PORTFOLIO_HARD_TP immediately", EXIT_HARD_TP.equals(d2.exitReason));
        PortfolioState st2 = new PortfolioState(); st2.beActive = true; st2.trailActive = true; st2.floorActive = true; st2.floor = CAP * 0.02; st2.peakNet = CAP * 0.025;
        PortfolioDecision d3 = decidePortfolio(st2, metricsFor(P, CAP * 0.031, CAP), P);
        check("hard target wins over an active trailing floor", EXIT_HARD_TP.equals(d3.exitReason));
        PortfolioParams off = new PortfolioParams(); off.targetEnabled = false; off.beEnabled = false; off.trEnabled = false; off.maxLossEnabled = false;
        check("everything disabled -> never an exit, anyEnabled() false", decidePortfolio(new PortfolioState(), metricsFor(off, 99, CAP), off).exitReason == null && !off.anyEnabled());

        System.out.println("[Test] Portfolio breakeven");
        PortfolioState sb = new PortfolioState();
        PortfolioDecision b0 = decidePortfolio(sb, metricsFor(P, CAP * 0.005, CAP), P);
        check("below the breakeven trigger: not active", !sb.beActive && !sb.floorActive && b0.exitReason == null);
        PortfolioDecision b1 = decidePortfolio(sb, metricsFor(P, CAP * 0.011, CAP), P);
        check("net reaches 1.0% -> breakeven protection ACTIVE with floor = 0.3% of capital", sb.beActive && sb.floorActive && Math.abs(sb.floor - CAP * 0.003) < 1e-9 && b1.exitReason == null);
        PortfolioDecision b2 = decidePortfolio(sb, metricsFor(P, CAP * 0.0031, CAP), P);
        check("net still above the floor (even if single positions are red) -> hold", b2.exitReason == null);
        PortfolioDecision b3 = decidePortfolio(sb, metricsFor(P, CAP * 0.003, CAP), P);
        check("net falls back to the floor -> PORTFOLIO_BREAKEVEN", EXIT_BREAKEVEN.equals(b3.exitReason));
        PortfolioParams lock0 = new PortfolioParams(); lock0.beLockPct = 0.0; lock0.trEnabled = false;
        PortfolioState sl0 = new PortfolioState();
        decidePortfolio(sl0, metricsFor(lock0, CAP * 0.011, CAP), lock0);
        check("lock 0% = true breakeven AFTER costs (floor 0 net, fees already inside net)", sl0.floorActive && Math.abs(sl0.floor) < 1e-12
                && EXIT_BREAKEVEN.equals(decidePortfolio(sl0, metricsFor(lock0, -1e-6, CAP), lock0).exitReason));

        System.out.println("[Test] Portfolio trailing profit floor");
        PortfolioState sr = new PortfolioState();
        decidePortfolio(sr, metricsFor(P, CAP * 0.015, CAP), P);
        check("before the activation level (1.8%) trailing is not active", !sr.trailActive);
        decidePortfolio(sr, metricsFor(P, CAP * 0.020, CAP), P);
        double expect1 = Math.max(CAP * 0.003, CAP * 0.020 * 0.70);
        check("2.0% peak -> trailing ACTIVE, floor = max(lock, peak - 30% of peak) = 1.4%", sr.trailActive && Math.abs(sr.floor - expect1) < 1e-9 && EXIT_TRAILING.equals(sr.floorSource));
        double f1 = sr.floor;
        decidePortfolio(sr, metricsFor(P, CAP * 0.025, CAP), P);
        check("peak rises to 2.5% -> floor rises to 1.75%", Math.abs(sr.floor - CAP * 0.025 * 0.70) < 1e-9 && sr.floor > f1);
        double f2 = sr.floor;
        PortfolioDecision r1 = decidePortfolio(sr, metricsFor(P, CAP * 0.022, CAP), P);
        check("profit falls back but stays above the floor -> hold, peak and floor never decrease", r1.exitReason == null && Math.abs(sr.peakNet - CAP * 0.025) < 1e-9 && Math.abs(sr.floor - f2) < 1e-12);
        PortfolioDecision r2 = decidePortfolio(sr, metricsFor(P, f2 - 1e-6, CAP), P);
        check("net falls to the floor -> PORTFOLIO_TRAILING_SL", EXIT_TRAILING.equals(r2.exitReason));
        PortfolioParams shrink = new PortfolioParams(); shrink.trEnabled = true;
        PortfolioState ss = new PortfolioState();
        decidePortfolio(ss, metricsFor(shrink, 0.40, 20.0), shrink);      // 2% of 20 USDT margin, trailing active (1.8% = 0.36)
        double fHigh = ss.floor;
        decidePortfolio(ss, metricsFor(shrink, 0.40, 5.0), shrink);       // margin shrinks (positions closed): thresholds shrink
        check("deployed-margin basis shrinking never lowers the protected floor", ss.floor >= fHigh - 1e-12);

        System.out.println("[Test] Portfolio max loss");
        PortfolioState sx = new PortfolioState();
        PortfolioDecision x1 = decidePortfolio(sx, metricsFor(P, -CAP * 0.05, CAP), P);
        check("-5% (limit 6%): no exit", x1.exitReason == null);
        PortfolioDecision x2 = decidePortfolio(sx, metricsFor(P, -CAP * 0.06, CAP), P);
        check("-6% of capital -> PORTFOLIO_RISK_LIMIT", EXIT_RISK_LIMIT.equals(x2.exitReason));

        System.out.println("[Test] Entry gating (never an entry during liquidation / cooldown / limits)");
        resetPortfolioTestState();
        long T0 = 1_800_000_000_000L;
        check("idle portfolio: entries allowed", portfolioEntryBlockedReason(T0, 2, 8.0, 4.0) == null);
        check("max open positions", portfolioEntryBlockedReason(T0, PORTFOLIO_MAX_OPEN_POSITIONS, 8.0, 4.0) != null);
        pState.reentryUntilMs = T0 + 10_000; check("re-entry cooldown blocks", portfolioEntryBlockedReason(T0, 0, 0, 4.0) != null && portfolioEntryBlockedReason(T0 + 11_000, 0, 0, 4.0) == null);
        pState.riskPauseUntilMs = T0 + 3_600_000; check("risk pause blocks", portfolioEntryBlockedReason(T0 + 11_000, 0, 0, 4.0) != null);
        pState = new PortfolioState(); pState.liquidating = true; check("liquidation blocks", portfolioEntryBlockedReason(T0, 0, 0, 4.0) != null);

        System.out.println("[Test] Close-all: hard target with mixed LONG/SHORT positions");
        resetPortfolioTestState();
        FakeOps fx = new FakeOps();
        fx.add("B-A_USDT", 1.0, 20.0, 20.0);          // long
        fx.add("B-B_USDT", -1.0, 20.0, 20.0);         // short
        fx.add("B-C_USDT", 1.0, 20.0, 20.0);          // long
        TradeInfo ta = new TradeInfo(); ta.isLong = true; ta.entry = 20; ta.sl = 17.6; ta.tp = 23; ta.initialSl = 17.6; ta.risk = 2.4; ta.qty = 1;
        tradeState.put("B-A_USDT", ta);
        portfolioPass(fx);
        check("flat prices: cycle starts, nothing closed, entries not blocked by liquidation", pState.cycleStartMs > 0 && fx.closeCalls == 0 && !pState.liquidating);
        fx.prices.put("B-A_USDT", 21.2); fx.prices.put("B-B_USDT", 18.6); fx.prices.put("B-C_USDT", 20.9);     // +1.2, +1.4, +0.9 = +3.5 gross
        double slBefore = ta.sl, tpBefore = ta.tp;
        portfolioPass(fx);
        check("net >= INR 150 (3%) -> everything closed in one liquidation", fx.pos.isEmpty() && fx.closeCalls == 3);
        check("exchange confirmed flat -> cycle reset, liquidation off, reason recorded", !pState.liquidating && pState.cycleStartMs == 0 && EXIT_HARD_TP.equals(pState.lastExitReason) && pState.lastFinalPnl > 1.4);
        check("peak / breakeven / trailing state reset", pState.peakNet == 0 && !pState.beActive && !pState.trailActive && !pState.floorActive);
        check("re-entry cooldown applied after the close-all", pState.reentryUntilMs > fx.clock && portfolioEntryBlockedReason(fx.clock, 0, 0, 4.0) != null);
        check("per-position SL/TP values were not touched by the portfolio logic", ta.sl == slBefore && ta.tp == tpBefore);
        check("no order of any kind is created by the portfolio code", fx.ordersPlaced == 0);

        System.out.println("[Test] Partial liquidation, rejected close, duplicate-close protection, retry");
        resetPortfolioTestState();
        FakeOps fp = new FakeOps();
        fp.add("B-P1_USDT", 1.0, 20.0, 21.5); fp.add("B-P2_USDT", 1.0, 20.0, 21.5); fp.add("B-P3_USDT", 1.0, 20.0, 21.5);
        fp.ignoreFirstClose.add("B-P2_USDT");      // accepted but still open (partial / delayed)
        fp.failFirstClose.add("B-P3_USDT");        // rejected by the exchange
        portfolioPass(fp);
        check("round 1: P1 closed, P2 + P3 still open -> liquidation stays ACTIVE", fp.pos.size() == 2 && pState.liquidating && EXIT_HARD_TP.equals(pState.liquidationReason));
        check("entries stay blocked while any position of the liquidation is open", portfolioEntryBlockedReason(fp.clock, fp.pos.size(), 0, 4.0) != null);
        int callsAfterRound1 = fp.closeCalls;
        fp.clock += 1_000;
        portfolioPass(fp);
        check("a second pass within the retry interval sends NO duplicate close requests", fp.closeCalls == callsAfterRound1 && pState.liquidating);
        fp.clock += PORTFOLIO_CLOSE_RETRY_INTERVAL_MS + 1000;
        portfolioPass(fp);
        check("after the retry interval the remaining positions are closed again and confirmed", fp.pos.isEmpty() && !pState.liquidating && pState.cycleStartMs == 0);
        check("every position was closed by at most two requests, P1 exactly once", fp.closeTries.get("B-P1_USDT") == 1 && fp.closeTries.get("B-P2_USDT") == 2 && fp.closeTries.get("B-P3_USDT") == 2);
        check("the liquidation reason labels the closed trades", pState.lastExitReason.equals(EXIT_HARD_TP));

        System.out.println("[Test] API failures: never a fake zero, never a false 'all closed'");
        resetPortfolioTestState();
        FakeOps fa = new FakeOps();
        fa.add("B-F1_USDT", 1.0, 20.0, 25.0); fa.add("B-F2_USDT", 1.0, 20.0, 0.0);      // F1 is hugely profitable, but F2 has NO price
        portfolioPass(fa);
        check("one price missing -> incomplete data: no exit decision at all", fa.closeCalls == 0 && !pState.liquidating && pState.peakNet == 0);
        fa.prices.put("B-F2_USDT", 20.0);
        fa.apiDown = true;
        portfolioPass(fa);
        check("positions API down -> no decision, no close", fa.closeCalls == 0 && !pState.liquidating);
        fa.apiDown = false;
        fa.failFirstClose.add("B-F1_USDT"); fa.failFirstClose.add("B-F2_USDT");
        portfolioPass(fa);
        check("target hit -> liquidation starts, both closes rejected -> stays active", pState.liquidating && fa.pos.size() == 2);
        fa.apiDown = true; fa.clock += 7_000;
        portfolioPass(fa);
        check("API down during liquidation: cannot verify -> NOT finalized, no reset", pState.liquidating && pState.cycleStartMs > 0);
        fa.apiDown = false; fa.clock += 7_000;
        portfolioPass(fa);
        check("API back: retried, confirmed flat, finalized", !pState.liquidating && fa.pos.isEmpty());

        System.out.println("[Test] Max-loss exit pauses entries");
        resetPortfolioTestState();
        FakeOps fl = new FakeOps();
        fl.add("B-L1_USDT", 1.0, 20.0, pxForNet(-3.2, new PortfolioParams()));
        portfolioPass(fl);
        check("net loss beyond 6% of capital -> PORTFOLIO_RISK_LIMIT, all closed", fl.pos.isEmpty() && EXIT_RISK_LIMIT.equals(pState.lastExitReason) && pState.lastFinalPnl < -2.9);
        check("risk pause: no new entries for the configured time", portfolioEntryBlockedReason(fl.clock + 60_000, 0, 0, 4.0) != null && pState.riskPauseUntilMs > fl.clock + PORTFOLIO_RISK_PAUSE_MS - 10_000);

        System.out.println("[Test] Trailing floor through the real pass (peak, floor, exit)");
        resetPortfolioTestState();
        final double TM = 20.0 / 5.0;                     // locked margin of this single test position (q=1 @ 20, lev 5)
        FakeOps ft = new FakeOps();
        PosRec tr = ft.add("B-T1_USDT", 1.0, 20.0, pxForNet(TM * 0.020, P));
        portfolioPass(ft);
        check("2.0% net: trailing + breakeven active, no exit yet", pState.trailActive && pState.beActive && pState.floorActive && fx.ordersPlaced == 0 && ft.closeCalls == 0);
        double floorSeen = pState.floor;
        ft.prices.put("B-T1_USDT", pxForNet(TM * 0.026, P)); ft.clock += 6_000; portfolioPass(ft);
        check("higher profit raises the floor", pState.floor > floorSeen && Math.abs(pState.peakNet - TM * 0.026) < 1e-6);
        ft.prices.put("B-T1_USDT", pxForNet(TM * 0.022, P)); ft.clock += 6_000; portfolioPass(ft);
        check("pullback above the floor: still open", ft.closeCalls == 0 && ft.pos.size() == 1);
        ft.prices.put("B-T1_USDT", pxForNet(TM * 0.0175, P)); ft.clock += 6_000; portfolioPass(ft);
        check("fall to the floor (peak 2.6% - 30% = 1.82%) -> PORTFOLIO_TRAILING_SL and everything closed", ft.pos.isEmpty() && EXIT_TRAILING.equals(pState.lastExitReason));

        System.out.println("[Test] Realized PnL of positions that closed on their own is booked into the cycle");
        resetPortfolioTestState();
        FakeOps fr = new FakeOps();
        fr.add("B-R1_USDT", 1.0, 20.0, 20.1);
        fr.add("B-R2_USDT", 1.0, 20.0, 20.0);
        TradeInfo t2 = new TradeInfo(); t2.isLong = true; t2.entry = 20; t2.sl = 17.6; t2.tp = 20.6; t2.initialSl = 17.6; t2.risk = 2.4; t2.qty = 1;
        tradeState.put("B-R2_USDT", t2);
        portfolioPass(fr);
        fr.pos.remove("B-R2_USDT"); fr.prices.put("B-R2_USDT", 20.59);        // closed by its own TP at ~20.6
        fr.clock += 6_000;
        portfolioPass(fr);
        double realizedExpected = estimateRealizedNet(true, 1, 20, 20.6, new PortfolioParams());
        check("TP close of one position is booked as realized (net of both fees)", Math.abs(pState.realizedNet - realizedExpected) < 1e-9 && realizedExpected > 0.5);
        check("a profit booked by one position counts toward the portfolio target", pState.peakNet >= realizedExpected - 0.5);
        check("the closed position is dropped from the trade state (individual management unchanged)", !tradeState.containsKey("B-R2_USDT"));

        System.out.println("[Test] State persistence and restart recovery");
        resetPortfolioTestState();
        PortfolioState ps = new PortfolioState();
        ps.cycleId = 7; ps.cycleStartMs = 123456L; ps.peakNet = 1.234; ps.beActive = true; ps.trailActive = true; ps.floorActive = true; ps.floor = 0.9; ps.floorSource = EXIT_TRAILING;
        ps.realizedNet = 0.31; ps.liquidating = true; ps.liquidationReason = EXIT_RISK_LIMIT; ps.liquidationRounds = 3; ps.liquidationNet = -2.5; ps.reentryUntilMs = 99L; ps.riskPauseUntilMs = 77L;
        PortfolioState rt = portfolioFromJson(new JSONObject(portfolioToJson(ps).toString()));
        check("every field survives save/load", rt.cycleId == 7 && rt.peakNet == 1.234 && rt.beActive && rt.trailActive && rt.floorActive && rt.floor == 0.9
                && rt.floorSource.equals(EXIT_TRAILING) && rt.realizedNet == 0.31 && rt.liquidating && rt.liquidationReason.equals(EXIT_RISK_LIMIT)
                && rt.liquidationRounds == 3 && rt.liquidationNet == -2.5 && rt.reentryUntilMs == 99L && rt.riskPauseUntilMs == 77L);
        FakeOps fs = new FakeOps(); fs.persist = true;
        fs.add("B-S1_USDT", 1.0, 20.0, pxForNet(CAP * 0.022, P));
        portfolioPass(fs);
        double floorBeforeRestart = pState.floor, peakBeforeRestart = pState.peakNet;
        check("state is written to disk when protection activates", new File(portfolioStateFile).exists() && pState.floorActive);
        pState = new PortfolioState();                    // simulated restart: memory is gone
        loadPortfolioState();
        check("after the restart the peak / floor / activation are restored, NOT reset", Math.abs(pState.peakNet - peakBeforeRestart) < 1e-12 && Math.abs(pState.floor - floorBeforeRestart) < 1e-12 && pState.trailActive && pState.beActive && pState.cycleStartMs > 0);
        fs.prices.put("B-S1_USDT", pxForNet(floorBeforeRestart - 0.01, P)); fs.clock += 6_000;
        portfolioPass(fs);
        check("restored floor still protects: falling to it closes the position", fs.pos.isEmpty() && EXIT_TRAILING.equals(pState.lastExitReason));
        FakeOps fm = new FakeOps(); fm.persist = true;
        fm.add("B-M1_USDT", 1.0, 20.0, 20.0); fm.add("B-M2_USDT", 1.0, 20.0, 20.0);
        pState = new PortfolioState(); pState.cycleId = 3; pState.cycleStartMs = fm.clock - 100_000; pState.liquidating = true; pState.liquidationReason = EXIT_HARD_TP; pState.liquidationNet = 1.6;
        savePortfolioState();
        pState = new PortfolioState(); loadPortfolioState();
        check("restart in the middle of a liquidation: the saved liquidation flag is restored", pState.liquidating && pState.liquidationReason.equals(EXIT_HARD_TP));
        check("... and entries stay blocked", portfolioEntryBlockedReason(fm.clock, 2, 0, 4.0) != null);
        portfolioPass(fm);
        check("... the liquidation is completed against the actual exchange positions and then reset", fm.pos.isEmpty() && !pState.liquidating && pState.cycleStartMs == 0);
        FakeOps fz = new FakeOps(); fz.persist = false;
        pState = new PortfolioState(); pState.cycleId = 9; pState.cycleStartMs = fz.clock - 5000; pState.peakNet = 1.0; pState.floorActive = true; pState.floor = 0.5;
        portfolioPass(fz);
        check("saved cycle but the exchange is flat (closed while offline): cycle is reset cleanly", pState.cycleStartMs == 0 && !pState.floorActive && pState.peakNet == 0);
        File corrupt = new File(portfolioStateFile);
        try { Files.write(corrupt.toPath(), "{ not json".getBytes(StandardCharsets.UTF_8)); } catch (Exception ignored) {}
        loadPortfolioState();
        check("corrupt state file is backed up and the bot starts fresh (no crash)", !pState.liquidating && new File(portfolioStateFile + ".corrupt").exists());

        System.out.println("[Test] Individual SL/TP, break-even and trailing keep working next to the portfolio logic");
        double e0 = 100.0, tk0 = 0.01;
        TrailStep ts = computeTrail(true, e0, 88.0, 115.0, e0 * 1.14, false, 0, tk0);
        BeStep bs = computeBreakeven(true, e0, 88.0, e0 * 1.11, tk0);
        check("per-position trailing activates exactly as before", ts.active && ts.justActivated);
        check("per-position break-even + profit lock moves the SL exactly as before", bs.reached && bs.move && bs.newSl > e0);
        check("the portfolio settings are independent of the per-position settings", PORTFOLIO_BREAKEVEN_TRIGGER_PERCENT != BREAKEVEN_TRIGGER_PERCENT && PORTFOLIO_TRAILING_ACTIVATION_PERCENT != TRAIL_ACTIVATION_PERCENT);
        TradeInfo q1 = new TradeInfo(); q1.qty = 12.5; q1.entry = 1;
        check("trade state keeps the quantity across restarts", tradeFromJson(new JSONObject(tradeToJson(q1).toString())).qty == 12.5);
        check("config sanity: lock < BE trigger < trailing activation < hard target", PORTFOLIO_BREAKEVEN_LOCK_PERCENT < PORTFOLIO_BREAKEVEN_TRIGGER_PERCENT
                && PORTFOLIO_BREAKEVEN_TRIGGER_PERCENT < PORTFOLIO_TRAILING_ACTIVATION_PERCENT && PORTFOLIO_TRAILING_ACTIVATION_PERCENT < PORTFOLIO_PROFIT_TARGET_PERCENT);
        resetPortfolioTestState();
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
// //   PORTFOLIO PROFIT TARGET (PORTFOLIO_PROFIT_TARGET_ENABLED):  NEW
// //     When the COMBINED unrealized profit of all open positions reaches PORTFOLIO_PROFIT_TARGET_PERCENT of the
// //     total locked margin, ALL positions are closed; entries resume after a short pause.
// //     Works alongside break-even / trailing / fixed SL-TP (they stay active exactly as before).
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
//     static final double  FIXED_SL_PERCENT = 3.0;
//     static final double  FIXED_TP_PERCENT = 30.0;

//     // =========================================================================
//     // Break-even + Profit Lock  (LIVE mode only)  -- SL only, TP is never changed by this feature
//     // =========================================================================
//     static final boolean BREAKEVEN_ENABLED = true;           // MASTER SWITCH: false = break-even / profit-lock OFF
//     static final double  BREAKEVEN_TRIGGER_PERCENT = 1.50;    // favourable move (% of entry) that activates break-even
//     static final double  PROFIT_LOCK_PERCENT       = 1.0;    // SL goes to entry +PROFIT_LOCK% (LONG) / entry -PROFIT_LOCK% (SHORT). 0 = exact break-even

//     // =========================================================================
//     // Dynamic trailing SL / TP  (LIVE mode only)
//     // =========================================================================
//     static final boolean TRAILING_ENABLED = true;            // MASTER SWITCH: false = fixed SL/TP only, no trailing
//     static final double  TRAIL_ACTIVATION_PERCENT = 2.50;     // trailing starts after price moves this % in profit
//     static final double  TRAIL_STEP_PERCENT       = 0.20;     // SL/TP are moved only when price advanced this % beyond the last trail point
//     static final long    TRAIL_CHECK_INTERVAL_MS  = 5_000L; // how often open positions are checked for trailing
//     static final int     TRAIL_UPDATE_MAX_RETRIES = 3;       // SL/TP replace attempts per update
//     static final long    TRAIL_VERIFY_DELAY_MS    = 800;  // wait before reading the position back after an update
//     static final long    TRAIL_RETRY_BACKOFF_MS   = 15_000L; // after a failed update, wait before trying again
//     static final long    TRAIL_STATUS_LOG_MS      = 60_000L; // status log per position at most once per this interval
//     static final double  TRAIL_ENTRY_MISMATCH_PCT = 0.3;     // saved state whose entry differs more than this from the exchange = old/other position
//     static final String  TRADE_STATE_FILE = "trade_state_v4.json";

//     // =========================================================================
//     // PORTFOLIO PROFIT TARGET  (LIVE mode only)                                              [NEW]
//     //   combined profit % = (sum of unrealized PnL of all open positions) / (sum of their margin) * 100
//     //   margin per position = |qty| * avgEntry / leverage   (isolated margin, same currency as the PnL)
//     //   When combined profit % >= PORTFOLIO_PROFIT_TARGET_PERCENT -> close ALL positions, pause entries briefly, resume.
//     //   Note: at 5x leverage, 3% of margin = 0.6% price move.
//     // =========================================================================
//     static final boolean PORTFOLIO_PROFIT_TARGET_ENABLED = true;    // MASTER SWITCH: false = feature OFF
//     static final double  PORTFOLIO_PROFIT_TARGET_PERCENT = 12.0;     // edit this to change the target (% of total locked margin)
//     static final long    PORTFOLIO_CHECK_INTERVAL_MS     = 5_000L;  // how often the combined profit is checked
//     // static final long    PORTFOLIO_REENTRY_PAUSE_MS      = 30_000L; // after a close-all, no new entries for this long

//     static final long    PORTFOLIO_REENTRY_PAUSE_MS      = 0L; // after a close-all, no new entries for this long
    
//     static final long    PORTFOLIO_STATUS_LOG_MS         = 60_000L; // status log at most once per this interval

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
//     // Capital configuration (unchanged except leverage)
//     // =========================================================================
//     private static final double FIXED_MARGIN = 1000.0;   // legacy sizing: notional INR value = FIXED_MARGIN
//     private static final int    LEVERAGE     = 20;        // was 6
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
//     // Portfolio profit target — pure calculation (no I/O, unit-tested in --selftest)   [NEW]
//     // =========================================================================
//     // signedQty: LONG positive, SHORT negative. Result in the quote currency (USDT).
//     static double positionPnl(double signedQty, double avg, double px) { return (px - avg) * signedQty; }

//     // isolated margin = notional / leverage (USDT, same unit as the PnL, so the % never mixes INR and USDT)
//     static double positionMargin(double signedQty, double avg, double lev) { return lev > 0 ? Math.abs(signedQty) * avg / lev : 0; }

//     static boolean portfolioTargetReached(double pnl, double margin, double targetPct) {
//         return margin > 0 && pnl / margin * 100.0 >= targetPct - 1e-9;
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
//                 + " | SL=" + FIXED_SL_PERCENT + "% TP=" + FIXED_TP_PERCENT + "% | leverage=" + LEVERAGE + "x | liquid list=" + USE_LIQUID_LIST
//                 + " (" + COINS_TO_TRADE.length + " symbols) ===");
//         System.out.println("=== Break-even + profit lock: " + (BREAKEVEN_ENABLED
//                 ? "ENABLED (trigger " + BREAKEVEN_TRIGGER_PERCENT + "%, SL locked at entry +/- " + PROFIT_LOCK_PERCENT + "%)"
//                 : "DISABLED") + (LIVE_TRADING_ENABLED ? "" : " — not applied in PAPER mode") + " ===");
//         if (BREAKEVEN_ENABLED && BREAKEVEN_TRIGGER_PERCENT <= PROFIT_LOCK_PERCENT)
//             System.err.println("WARNING: BREAKEVEN_TRIGGER_PERCENT should be greater than PROFIT_LOCK_PERCENT, otherwise the lock level can never be placed");
//         System.out.println("=== Trailing SL/TP: " + (TRAILING_ENABLED
//                 ? "ENABLED (activation " + TRAIL_ACTIVATION_PERCENT + "%, step " + TRAIL_STEP_PERCENT + "%, check every " + (TRAIL_CHECK_INTERVAL_MS / 1000) + "s)"
//                 : "DISABLED (fixed SL/TP only)") + (LIVE_TRADING_ENABLED ? "" : " — not applied in PAPER mode") + " ===");
//         System.out.println("=== Portfolio profit target: " + (PORTFOLIO_PROFIT_TARGET_ENABLED
//                 ? "ENABLED (close ALL positions when combined profit >= " + PORTFOLIO_PROFIT_TARGET_PERCENT + "% of total margin, check every "
//                   + (PORTFOLIO_CHECK_INTERVAL_MS / 1000) + "s, re-entry pause " + (PORTFOLIO_REENTRY_PAUSE_MS / 1000) + "s)"
//                 : "DISABLED") + (LIVE_TRADING_ENABLED ? "" : " — not applied in PAPER mode") + " ===");
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
//             runPortfolioProfitCheck();          // NEW: combined-profit target (does nothing when disabled)
//         } else {
//             active = new HashSet<>();
//             updatePaperTrades();
//         }

//         boolean portfolioPaused = LIVE_TRADING_ENABLED && PORTFOLIO_PROFIT_TARGET_ENABLED
//                 && (portfolioLiquidating || System.currentTimeMillis() < portfolioPauseUntilMs);
//         if (portfolioPaused) {
//             System.out.println("Portfolio target pause — no new entries this cycle.");
//         } else if (active.size() + paperTrades.size() >= MAX_OPEN_POSITIONS) {
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

//                 // Same for the portfolio profit target: if it fires, the scan stops and entries pause.
//                 if (LIVE_TRADING_ENABLED && PORTFOLIO_PROFIT_TARGET_ENABLED) {
//                     if (System.currentTimeMillis() - lastPortfolioCheckMs >= PORTFOLIO_CHECK_INTERVAL_MS) runPortfolioProfitCheck();
//                     if (portfolioLiquidating || System.currentTimeMillis() < portfolioPauseUntilMs) {
//                         System.out.println("  [PORTFOLIO] target hit — scan stopped, new entries paused");
//                         break;
//                     }
//                 }

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
//         // positions closed by the portfolio profit target are labelled explicitly instead of guessed from the price
//         String exit = portfolioClosedPairs.remove(pair) ? "PORTFOLIO_TARGET" : classifyExit(t, lastPx);
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

//     // =========================================================================
//     // PORTFOLIO PROFIT TARGET  (LIVE)   [NEW]
//     //   combined % = sum(PnL of all open positions) / sum(margin of all open positions) * 100
//     //   >= PORTFOLIO_PROFIT_TARGET_PERCENT  ->  close ALL positions (exit API), pause entries, then resume.
//     //   If a close fails, `portfolioLiquidating` stays true and the remaining positions are closed on the next pass
//     //   (no need for the target to be hit again). Runs on the main thread only (no concurrent order calls).
//     // =========================================================================
//     private static long lastPortfolioCheckMs = 0;
//     private static long portfolioPauseUntilMs = 0;
//     private static long portfolioLastLogMs = 0;
//     private static boolean portfolioLiquidating = false;
//     private static final Set<String> portfolioClosedPairs = ConcurrentHashMap.newKeySet();

//     private static void runPortfolioProfitCheck() {
//         if (!LIVE_TRADING_ENABLED || !PORTFOLIO_PROFIT_TARGET_ENABLED) return;
//         long now = System.currentTimeMillis();
//         lastPortfolioCheckMs = now;
//         Map<String, JSONObject> positions = getOpenPositionsMap();
//         if (positions == null) { System.err.println("[PORTFOLIO] positions API failed — check skipped"); return; }
//         if (positions.isEmpty()) { portfolioLiquidating = false; return; }

//         double pnl = 0, margin = 0;
//         int n = 0;
//         for (Map.Entry<String, JSONObject> en : positions.entrySet()) {
//             JSONObject p = en.getValue();
//             double q = p.optDouble("active_pos", 0), avg = p.optDouble("avg_price", 0);
//             if (Math.abs(q) <= 0 || avg <= 0) continue;                       // not filled yet / already closed
//             double px = getLastPrice(en.getKey());
//             if (px <= 0) { System.err.println("[PORTFOLIO] no price for " + en.getKey() + " — no decision this pass"); return; }
//             double lev = p.optDouble("leverage", LEVERAGE);
//             if (!(lev > 0)) lev = LEVERAGE;
//             pnl += positionPnl(q, avg, px);
//             margin += positionMargin(q, avg, lev);
//             n++;
//         }
//         if (n == 0) return;
//         double pct = margin > 0 ? pnl / margin * 100.0 : 0;
//         if (now - portfolioLastLogMs >= PORTFOLIO_STATUS_LOG_MS) {
//             System.out.printf("  [PORTFOLIO] %d position(s) | pnl=%.4f USDT | margin=%.4f USDT | combined=%.2f%% (target %.2f%%)%n",
//                     n, pnl, margin, pct, PORTFOLIO_PROFIT_TARGET_PERCENT);
//             portfolioLastLogMs = now;
//         }
//         if (!portfolioLiquidating) {
//             if (!portfolioTargetReached(pnl, margin, PORTFOLIO_PROFIT_TARGET_PERCENT)) return;
//             System.out.printf("[PORTFOLIO-TARGET] combined profit %.2f%% >= %.2f%% — closing ALL %d position(s)%n",
//                     pct, PORTFOLIO_PROFIT_TARGET_PERCENT, n);
//             portfolioLiquidating = true;
//         }
//         int failed = 0;
//         for (Map.Entry<String, JSONObject> en : positions.entrySet()) {
//             JSONObject p = en.getValue();
//             if (Math.abs(p.optDouble("active_pos", 0)) <= 0) continue;
//             String posId = p.optString("id", null);
//             if (posId == null || posId.isEmpty() || !closePosition(posId)) {
//                 failed++;
//                 System.err.println("[PORTFOLIO] close FAILED for " + en.getKey() + " — will retry on the next pass");
//             } else {
//                 portfolioClosedPairs.add(en.getKey());
//                 System.out.println("[PORTFOLIO] closed " + en.getKey());
//             }
//         }
//         portfolioPauseUntilMs = System.currentTimeMillis() + PORTFOLIO_REENTRY_PAUSE_MS;
//         if (failed == 0) portfolioLiquidating = false;
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

//         System.out.println("[Test] Portfolio profit target (combined PnL / combined margin)");
//         double pLong = positionPnl(10, 100.0, 100.6), pShort = positionPnl(-10, 100.0, 99.4);
//         check("LONG and SHORT both profit when price moves in their favour", pLong > 0 && pShort > 0 && Math.abs(pLong - pShort) < 1e-9);
//         check("SHORT loses when price rises, LONG loses when price falls", positionPnl(-10, 100.0, 101.0) < 0 && positionPnl(10, 100.0, 99.0) < 0);
//         check("margin = notional / leverage (SHORT uses |qty|)", Math.abs(positionMargin(-10, 100.0, 5) - 200.0) < 1e-9 && positionMargin(10, 100.0, 0) == 0);
//         check("0.6% price move at 5x = 3% of margin -> target reached", portfolioTargetReached(pLong, positionMargin(10, 100.0, 5), 3.0));
//         check("0.5% price move at 5x -> target NOT reached", !portfolioTargetReached(positionPnl(10, 100.0, 100.5), positionMargin(10, 100.0, 5), 3.0));
//         // 4 positions, margin 200 each (800 total): +20 +15 -8 -3 = +24 = exactly 3.0% of 800
//         double comb = 20 + 15 - 8 - 3;
//         check("combined example: +24 on 800 margin = 3.0% -> reached", portfolioTargetReached(comb, 800.0, 3.0));
//         check("combined example: +23 on 800 margin = 2.875% -> not reached", !portfolioTargetReached(23.0, 800.0, 3.0));
//         check("zero margin never triggers", !portfolioTargetReached(5.0, 0.0, 3.0));
//         check("losing basket never triggers", !portfolioTargetReached(-10.0, 800.0, 3.0));

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
// // HYBRID TREND-FOLLOWING ENTRY  +  PORTFOLIO-LEVEL PROFIT MANAGEMENT V2
// //
// //   ENTRY (LONG shown, SHORT = exact mirror), all decisions on CLOSED candles, live price only for extension checks:
// //     mandatory : 4H Supertrend GREEN + 1H Supertrend GREEN, 15M EMA9 > EMA21, a 5M trigger
// //                 (pullback/retest + confirmed bullish close, OR fresh confirmed swing-high breakout on a closed candle),
// //                 fresh data / no price spike / liquidity / not extended / not chased
// //     scored    : HTF 25 + 15M EMA 20 + ADX 15 + 5M trigger 20 + volume 10 + BTC 10 = 100  (ENTRY_MIN_SCORE to qualify)
// //     The old ST_CONTRARIAN (countertrend) mode is removed. Revalidated on fresh data immediately before the order.
// //
// //   SL / TP = fixed % from the ACTUAL average filled entry price.            [UNCHANGED]
// //   BREAK-EVEN + PROFIT LOCK per position (BREAKEVEN_ENABLED)                  [UNCHANGED]
// //   DYNAMIC TRAILING per position (TRAILING_ENABLED)                           [UNCHANGED]
// //
// //   PORTFOLIO V2 (works alongside the per-position logic, never touches a position's SL/TP):
// //     hard profit target  |  portfolio breakeven  |  portfolio trailing profit floor  |  max-loss limit  |  close-all with
// //     verification + retry  |  persisted state (portfolio_state_v1.json)  |  entry gating (liquidation / cooldown / limits)
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
//     static final double  FIXED_SL_PERCENT = 2.0;
//     static final double  FIXED_TP_PERCENT = 50.0;

//     // =========================================================================
//     // Break-even + Profit Lock  (LIVE mode only)  -- SL only, TP is never changed by this feature
//     // =========================================================================
//     static final boolean BREAKEVEN_ENABLED = true;           // MASTER SWITCH: false = break-even / profit-lock OFF
//     static final double  BREAKEVEN_TRIGGER_PERCENT = 1.50;    // favourable move (% of entry) that activates break-even
//     static final double  PROFIT_LOCK_PERCENT       = 1.00;    // SL goes to entry +PROFIT_LOCK% (LONG) / entry -PROFIT_LOCK% (SHORT). 0 = exact break-even

//     // =========================================================================
//     // Dynamic trailing SL / TP  (LIVE mode only)
//     // =========================================================================
//     static final boolean TRAILING_ENABLED = true;            // MASTER SWITCH: false = fixed SL/TP only, no trailing
//     static final double  TRAIL_ACTIVATION_PERCENT = 2.50;     // trailing starts after price moves this % in profit
//     static final double  TRAIL_STEP_PERCENT       = 0.50;     // SL/TP are moved only when price advanced this % beyond the last trail point
//     static final long    TRAIL_CHECK_INTERVAL_MS  = 5_000L; // how often open positions are checked for trailing
//     static final int     TRAIL_UPDATE_MAX_RETRIES = 3;       // SL/TP replace attempts per update
//     static final long    TRAIL_VERIFY_DELAY_MS    = 800;  // wait before reading the position back after an update
//     static final long    TRAIL_RETRY_BACKOFF_MS   = 15_000L; // after a failed update, wait before trying again
//     static final long    TRAIL_STATUS_LOG_MS      = 60_000L; // status log per position at most once per this interval
//     static final double  TRAIL_ENTRY_MISMATCH_PCT = 0.3;     // saved state whose entry differs more than this from the exchange = old/other position
//     static final String  TRADE_STATE_FILE = "trade_state_v4.json";

//     // =========================================================================
//     // PORTFOLIO MANAGEMENT V2  (LIVE and PAPER)                                              [NEW]
//     //   All portfolio percentages are % of the BASIS:
//     //     TOTAL_ACCOUNT_CAPITAL  (default) : PORTFOLIO_ACCOUNT_CAPITAL_INR            e.g. INR 5000: 3% = INR 150, 4% = 200, 5% = 250
//     //     TOTAL_DEPLOYED_MARGIN            : sum over open positions of |qty| * avgEntry / leverage
//     //   Leveraged notional is never used as a basis. PnL is in USDT internally; INR = USDT * USDT_INR_RATE.
//     // =========================================================================
//     static final boolean PORTFOLIO_PROFIT_TARGET_ENABLED = true;    // hard target: close ALL when NET portfolio profit >= target
//     static final double  PORTFOLIO_PROFIT_TARGET_PERCENT = 1.5;     // 3.0 / 4.0 / 5.0 ...
//     static final String  PORTFOLIO_TARGET_BASIS          = "TOTAL_ACCOUNT_CAPITAL";   // or "TOTAL_DEPLOYED_MARGIN"
//     static final double  PORTFOLIO_ACCOUNT_CAPITAL_INR   = 1000.0;  // SET THIS to your real trading capital (INR)
//     static final long    PORTFOLIO_CHECK_INTERVAL_MS     = 5_000L;  // how often the portfolio is evaluated

//     static final boolean PORTFOLIO_BREAKEVEN_ENABLED         = true;
//     static final double  PORTFOLIO_BREAKEVEN_TRIGGER_PERCENT = 2.0; // net profit (% of basis) that activates portfolio protection
//     static final double  PORTFOLIO_BREAKEVEN_LOCK_PERCENT    = 1.6; // protected net profit floor (% of basis) once activated. 0 = true breakeven after costs

//     static final boolean PORTFOLIO_TRAILING_ENABLED            = true;
//     static final double  PORTFOLIO_TRAILING_ACTIVATION_PERCENT = 3.0;  // trailing starts when the peak net profit reaches this (% of basis)
//     static final double  PORTFOLIO_TRAILING_GIVEBACK_PERCENT   = 25.0; // allowed giveback = this % OF THE PEAK profit: floor = max(lock, peak - peak*giveback)
//     static final long    PORTFOLIO_TRAILING_CHECK_INTERVAL_MS  = 5_000L; // the portfolio pass runs every min(CHECK, TRAILING_CHECK) ms

//     static final boolean PORTFOLIO_MAX_LOSS_ENABLED            = false;
//     static final double  PORTFOLIO_MAX_LOSS_PERCENT            = 2.0;  // close ALL + pause entries when net loss reaches this (% of basis)
//     static final int     PORTFOLIO_MAX_OPEN_POSITIONS          = 600;    // no new entry above this many open positions (<= 0 disables)
//     static final double  PORTFOLIO_MAX_DEPLOYED_MARGIN_PERCENT = 50.0; // no new entry if deployed margin would exceed this % of ACCOUNT CAPITAL (<= 0 disables)

//     static final long    PORTFOLIO_REENTRY_PAUSE_MS      = 30_000L;      // cooldown after a successful close-all
//     static final long    PORTFOLIO_RISK_PAUSE_MS         = 15L * 60_000L; // entry pause after a PORTFOLIO_RISK_LIMIT exit
//     static final double  PORTFOLIO_FEE_RATE_PERCENT      = 0.05;         // estimated taker fee per side (% of notional) - VERIFY your CoinDCX tier
//     static final double  PORTFOLIO_FEE_GST_PERCENT       = 18.0;         // GST on fees (% of the fee) - set 0 if not applicable to you
//     static final long    PORTFOLIO_CLOSE_RETRY_INTERVAL_MS   = 6_000L;   // min gap between two close requests for the same position
//     static final long    PORTFOLIO_CLOSE_VERIFY_DELAY_MS     = 1_500L;   // wait before reading positions back after close requests
//     static final long    PORTFOLIO_LIQUIDATION_LOOP_MAX_MS   = 600_000L; // a running liquidation is retried this long per cycle, then every cycle
//     static final long    PORTFOLIO_STATUS_LOG_MS         = 60_000L;      // status log at most once per this interval
//     static final String  PORTFOLIO_STATE_FILE            = "portfolio_state_v1.json";
//     static final long    POSITIONS_CACHE_MS              = 1_500L;       // positions snapshot shared by the trailing + portfolio passes
//     static final long    PRICE_CACHE_MS                  = 2_000L;       // live price shared by the trailing + portfolio passes

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

//     private static final int MAX_OPEN_POSITIONS = 600;

//     private static final int  POSITION_ID_MAX_RETRIES = 5;
//     private static final long POSITION_ID_RETRY_DELAY_MS = 1500L;

//     // =========================================================================
//     // Capital configuration (unchanged except leverage)
//     // =========================================================================
//     private static final double FIXED_MARGIN = 1000.0;   // legacy sizing: notional INR value = FIXED_MARGIN
//     private static final int    LEVERAGE     = 5;        // was 6
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

//     static final int ST_MIN_BARS      = 25;   // completed bars needed per timeframe before the Supertrend is trusted
//     static final int EMA_MIN_BARS_15M = 40;   // completed 15M bars needed for EMA9/EMA21
//     static final int EMA_WINDOW_15M   = 120;  // how many 15M bars are fed into the EMA calculation

//     static final int SWING_PIVOT_BARS   = 3;    // a swing high/low needs this many lower highs / higher lows on each side
//     static final int SWING_LOOKBACK_5M  = 60;   // how far back the previous confirmed swing may be

//     // =========================================================================
//     // TREND-FOLLOWING ENTRY CONFIGURATION                                                   [NEW]
//     // =========================================================================
//     static final boolean HTF_SUPERTREND_ON_CLOSED_CANDLES = true;  // true: 4H/1H Supertrend on COMPLETED bars (no repaint). false: developing bar
//     static final boolean ENTRY_REQUIRE_15M_EMA = true;             // mandatory 15M EMA9>EMA21 (LONG) / EMA9<EMA21 (SHORT); false = score only

//     // signal quality score (normalised to 100 over the active components)
//     static final int ENTRY_MIN_SCORE = 80;
//     static final int SCORE_HTF = 25, SCORE_EMA15 = 20, SCORE_ADX = 15, SCORE_TRIGGER = 20, SCORE_VOLUME = 10, SCORE_BTC = 10;

//     static final int    ADX_PERIOD = 14;
//     static final double ADX_MIN = 20.0;                 // 15M ADX needed for the ADX points (<= 0 disables the requirement)
//     static final int    ADX_WINDOW_15M = 150;
//     static final int    ATR_PERIOD_5M = 14, ATR_PERIOD_15M = 14;

//     // 5M trigger
//     static final boolean ENTRY_ALLOW_PULLBACK = true, ENTRY_ALLOW_BREAKOUT = true;
//     static final int    PULLBACK_LOOKBACK_5M = 6;       // the retest must have happened within this many closed 5M candles before the confirmation candle
//     static final double PULLBACK_ZONE_ATR = 0.30;       // retest = low reached EMA21(5M) + this x ATR5 (LONG)
//     static final double PULLBACK_MAX_BREAK_ATR = 0.60;  // pullback invalid if a close went this x ATR5 beyond EMA21 (structure broken)
//     static final double ENTRY_MIN_BODY_RATIO = 0.40;    // confirmation candle body / range
//     static final int    VOLUME_AVG_BARS = 20;
//     static final double VOLUME_MIN_RATIO = 1.2;         // confirmation candle volume >= ratio x average of the previous VOLUME_AVG_BARS
//     static final boolean BREAKOUT_REQUIRES_VOLUME = true; // a swing breakout without volume is rejected (weak breakout)

//     // anti-chasing / extension (mandatory)
//     static final double ENTRY_MAX_EXT_ATR15  = 3.0;     // price must be within this x ATR15 of the 15M EMA21 (the EMA itself lags a strong trend by ~2 ATR)
//     static final double ENTRY_MAX_CHASE_ATR5 = 0.6;     // price must be within this x ATR5 of the confirmation candle close
//     static final long   ENTRY_MAX_CANDLE_AGE_MS = 180_000L; // enter only in the first 3 min after the 5M confirmation candle closed

//     // market safety (mandatory)
//     static final double ENTRY_SPIKE_RANGE_ATR5 = 4.0;   // last closed 5M range above this x ATR = abnormal spike
//     static final double ENTRY_SPIKE_JUMP_PCT   = 2.0;   // live price this % away from the last close = abnormal jump
//     static final boolean ENTRY_LIQUIDITY_CHECK_ENABLED = true;
//     static final double ENTRY_MIN_AVG_5M_NOTIONAL_USDT = 300.0; // avg 5M volume x price (verify the candle volume unit on your first run)
//     static final long   ENTRY_MAX_PRICE_AGE_MS = 120_000L;      // last trade older than this = stale/illiquid
//     static final boolean ENTRY_SPREAD_CHECK_ENABLED = true;     // pre-order order-book check (best effort, see marketSafetyReason)
//     static final double ENTRY_MAX_SPREAD_PCT = 0.15;
//     static final double ENTRY_MIN_BOOK_NOTIONAL_USDT = 200.0;
//     static final boolean ENTRY_REQUIRE_SPREAD_DATA = false;     // true = no order when the order book cannot be read
//     static final int    ORDERBOOK_DEPTH = 10;

//     // BTC market direction filter for altcoins
//     static final boolean BTC_FILTER_ENABLED = true;
//     static final boolean BTC_HARD_BLOCK_ON_OPPOSITE = true;     // BTC clearly against the trade side = no entry (false = just 0 points)
//     static final String  BTC_PAIR = "B-BTC_USDT";
//     static final long    BTC_CACHE_MS = 60_000L;

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
//         double qty;                     // absolute position quantity (persisted; used for the portfolio realized-PnL estimate)
//         // ---- runtime only (NOT persisted) ----
//         long nextTrailTryMs;
//         long lastStatusLogMs;
//     }
//     static class PaperTrade {
//         boolean isLong;
//         double qty;                     // simulated quantity (portfolio PnL in PAPER mode)
//         double entry, sl, tp;
//         long openedMs;
//         String ctx = "";
//     }
//     private static final Map<String, TradeInfo> tradeState = new ConcurrentHashMap<>();
//     static String tradeStateFile = TRADE_STATE_FILE;        // the self-tests redirect this so they can never touch the real state file
//     static boolean selfTestMode = false;                    // true only inside --selftest (no CSV / state-file writes to the live files)
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
//         final double[] o, h, l, c, v;     // v = volume (-1 = not provided by the exchange)
//         Series5(int n) {
//             this.n = n;
//             t = new long[n]; o = new double[n]; h = new double[n]; l = new double[n]; c = new double[n]; v = new double[n];
//             Arrays.fill(v, -1);
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
//             double vol = j.optDouble("volume", -1);
//             s.v[i] = (Double.isNaN(vol) || vol < 0) ? -1 : vol;
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
//         System.arraycopy(a.v, 0, r.v, 0, cut); System.arraycopy(b.v, 0, r.v, cut, b.n);
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
//         System.arraycopy(s.v, from, r.v, 0, maxN);
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
//     // TREND-FOLLOWING ENTRY EVALUATION (used by the scan AND by the pre-order revalidation)
//     //   Direction  : 4H + 1H Supertrend both GREEN (LONG) / both RED (SHORT)          -> mandatory
//     //   Momentum   : 15M EMA9 vs EMA21 (closed 15M bars)                               -> mandatory (ENTRY_REQUIRE_15M_EMA) + score
//     //   Strength   : 15M ADX(14) >= ADX_MIN with +DI/-DI on the trade side             -> score
//     //   Trigger    : 5M pullback/retest + confirmed candle close  OR  fresh confirmed swing break (closed candle) -> mandatory + score
//     //   Volume     : last closed 5M candle volume >= VOLUME_MIN_RATIO x average        -> score (mandatory for breakouts)
//     //   BTC filter : BTC 1H Supertrend + 15M EMA direction vs the trade side           -> score (+ optional hard block)
//     //   Safety     : fresh data, no spike, liquidity, not extended / not chased        -> mandatory, never bypassed by the score
//     // =========================================================================
//     static final int BTC_UNAVAILABLE = -99;   // BTC bias could not be computed
//     static final int BTC_NA          = 99;    // the symbol IS BTC (or the filter is off) -> not applicable

//     static class Eval {
//         String signal = "NO TRADE";    // BUY / SELL / NO TRADE
//         String code = "";
//         String reason = "";
//         int dir;                       // stage-1 candidate: +1 LONG, -1 SHORT, 0 none
//         final Dev[] dev = new Dev[4];  // only index 0 (1H) and 3 (4H) are used by this strategy
//         double ema9, ema21, brkLevel;  // 15M EMAs (closed bars) / trigger level
//         double price;
//         int score;                     // normalised 0..100
//         String scoreDetail = "";
//         String trigger = "";           // PULLBACK / BREAKOUT
//         double adx, plusDI, minusDI, atr5, atr15;
//         long candleOpen;               // open time of the last CLOSED 5M candle = signal candle id
//     }

//     private static Eval fail(Eval r, String code, String reason) {
//         if (r == null) return null;
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

//     // ---- small closed-candle helpers ----
//     static int closedEnd(Series5 s, long cur5) {
//         int end = s.n;
//         while (end > 0 && s.t[end - 1] >= cur5) end--;
//         return end;
//     }

//     // EMA series (NaN before the first full period)
//     static double[] emaSeries(double[] d, int period) {
//         double[] e = new double[d.length];
//         Arrays.fill(e, Double.NaN);
//         if (d.length < period) return e;
//         double k = 2.0 / (period + 1), v = 0;
//         for (int i = 0; i < period; i++) v += d[i];
//         v /= period;
//         e[period - 1] = v;
//         for (int i = period; i < d.length; i++) { v = d[i] * k + v * (1 - k); e[i] = v; }
//         return e;
//     }

//     // Wilder ADX. Returns {ADX, +DI, -DI} of the last bar, or null when there are not enough bars.
//     static double[] calcADX(double[] hi, double[] lo, double[] cl, int period) {
//         int n = cl.length;
//         if (n < 2 * period + 2) return null;
//         double[] tr = new double[n], pdm = new double[n], mdm = new double[n];
//         for (int i = 1; i < n; i++) {
//             double up = hi[i] - hi[i - 1], dn = lo[i - 1] - lo[i];
//             pdm[i] = (up > dn && up > 0) ? up : 0;
//             mdm[i] = (dn > up && dn > 0) ? dn : 0;
//             tr[i] = Math.max(hi[i] - lo[i], Math.max(Math.abs(hi[i] - cl[i - 1]), Math.abs(lo[i] - cl[i - 1])));
//         }
//         double sTr = 0, sP = 0, sM = 0;
//         for (int i = 1; i <= period; i++) { sTr += tr[i]; sP += pdm[i]; sM += mdm[i]; }
//         double[] dx = new double[n];
//         double pdi = 0, mdi = 0;
//         for (int i = period; i < n; i++) {
//             if (i > period) { sTr = sTr - sTr / period + tr[i]; sP = sP - sP / period + pdm[i]; sM = sM - sM / period + mdm[i]; }
//             pdi = sTr > 0 ? 100.0 * sP / sTr : 0;
//             mdi = sTr > 0 ? 100.0 * sM / sTr : 0;
//             double sum = pdi + mdi;
//             dx[i] = sum > 0 ? 100.0 * Math.abs(pdi - mdi) / sum : 0;
//         }
//         double adx = 0;
//         for (int i = period; i < 2 * period; i++) adx += dx[i];
//         adx /= period;
//         for (int i = 2 * period; i < n; i++) adx = (adx * (period - 1) + dx[i]) / period;
//         return new double[]{adx, pdi, mdi};
//     }

//     // Completed 15M bars only (never the developing one). Null (and r failed) if history is too short / has a gap.
//     static Bars closed15(Series5 s, long nowMs, Eval r) {
//         long start15 = alignedStart(nowMs, FIFTEEN_MIN_MS, HTF_ALIGN_OFFSET_MS);
//         Bars b = aggComplete(s, FIFTEEN_MIN_MS, start15);
//         if (b.n < Math.max(EMA_MIN_BARS_15M, 2 * ADX_PERIOD + 4)) { fail(r, "emaHistory", "history: only " + b.n + " completed 15M bars"); return null; }
//         if (b.t[b.n - 1] != start15 - FIFTEEN_MIN_MS) { fail(r, "emaGap", "history gap before the developing 15M bar"); return null; }
//         return b;
//     }

//     static double[] ema15Closed(Bars b) {
//         int use = Math.min(b.n, EMA_WINDOW_15M);
//         double[] cl = new double[use];
//         System.arraycopy(b.c, b.n - use, cl, 0, use);
//         return new double[]{calcEMA(cl, EMA_FAST), calcEMA(cl, EMA_SLOW)};
//     }

//     // Supertrend of timeframe k. Closed mode: last bar = last COMPLETED bar (no repainting).
//     // Developing mode (HTF_SUPERTREND_ON_CLOSED_CANDLES = false): last bar = developing bar with close = live price.
//     static Dev htfSupertrend(Series5 s, long nowMs, double price, int k) {
//         Dev d;
//         if (!HTF_SUPERTREND_ON_CLOSED_CANDLES) {
//             d = developing(s, nowMs, price, k);
//             if (!d.ok) return d;
//         } else {
//             d = new Dev();
//             d.tf = TF_NAMES[k]; d.tfMs = TF_MS[k];
//             d.start = alignedStart(nowMs, d.tfMs, HTF_ALIGN_OFFSET_MS);
//             d.price = price;
//         }
//         Bars b = aggComplete(s, d.tfMs, d.start);
//         d.stBars = b.n;
//         d.ok = false;
//         if (b.n < ST_MIN_BARS) { d.reason = "stHistory: only " + b.n + " completed " + d.tf + " bars (need " + ST_MIN_BARS + ")"; return d; }
//         if (b.t[b.n - 1] != d.start - d.tfMs) { d.reason = "histGap: last completed " + d.tf + " bar is not the one right before the current bar"; return d; }
//         if (HTF_SUPERTREND_ON_CLOSED_CANDLES) {
//             double[] hi = Arrays.copyOf(b.h, b.n), lo = Arrays.copyOf(b.l, b.n), cl = Arrays.copyOf(b.c, b.n);
//             d.open = b.o[b.n - 1]; d.high = b.h[b.n - 1]; d.low = b.l[b.n - 1]; d.price = b.c[b.n - 1];
//             d.colour = colourOf(d.open, d.price);
//             d.stBull = calcSupertrend(hi, lo, cl, ST_PERIOD, ST_MULTIPLIER)[b.n - 1];
//         } else {
//             int m = b.n + 1;
//             double[] hi = new double[m], lo = new double[m], cl = new double[m];
//             System.arraycopy(b.h, 0, hi, 0, b.n);
//             System.arraycopy(b.l, 0, lo, 0, b.n);
//             System.arraycopy(b.c, 0, cl, 0, b.n);
//             hi[m - 1] = d.high; lo[m - 1] = d.low; cl[m - 1] = price;
//             d.stBull = calcSupertrend(hi, lo, cl, ST_PERIOD, ST_MULTIPLIER)[m - 1];
//         }
//         d.ok = true;
//         return d;
//     }

//     // STAGE 1 (cheap, no live price needed in closed mode): 4H + 1H Supertrend aligned (+ 15M EMA when mandatory).
//     // Returns +1 / -1 for a candidate, 0 otherwise (r.code / r.reason are set then).
//     static int stage1(Series5 s, double price, long nowMs, Eval r) {
//         String bad = validate5m(s, nowMs);
//         if (bad != null) { fail(r, "5m_" + bad.substring(0, bad.indexOf(':')), bad); return 0; }
//         long cur5 = alignedStart(nowMs, FIVE_MIN_MS, 0L);
//         int end = closedEnd(s, cur5);
//         if (end < 60 || s.t[end - 1] != cur5 - FIVE_MIN_MS) { fail(r, "5m_noClosed", "last CLOSED 5M candle missing"); return 0; }
//         for (int i = end - 39; i < end; i++)
//             if (s.t[i] - s.t[i - 1] != FIVE_MIN_MS) { fail(r, "5m_gap", "gap inside the last 40 closed 5M candles"); return 0; }
//         r.candleOpen = s.t[end - 1];
//         for (int k : new int[]{0, 3}) {
//             Dev d = htfSupertrend(s, nowMs, price, k);
//             r.dev[k] = d;
//             if (!d.ok) { fail(r, d.reason.substring(0, d.reason.indexOf(':')), "data: " + d.tf + " " + d.reason); return 0; }
//         }
//         boolean allGreen = r.dev[0].stBull && r.dev[3].stBull;
//         boolean allRed = !r.dev[0].stBull && !r.dev[3].stBull;
//         if (!allGreen && !allRed) { fail(r, "stage1", "4H/1H Supertrend not aligned: " + summary(r)); return 0; }
//         int dir = allGreen ? 1 : -1;
//         if (ENTRY_REQUIRE_15M_EMA) {
//             Bars b = closed15(s, nowMs, r);
//             if (b == null) return 0;
//             double[] e = ema15Closed(b);
//             r.ema9 = e[0]; r.ema21 = e[1];
//             if (dir > 0 ? !(e[0] > e[1]) : !(e[0] < e[1])) {
//                 fail(r, "ema", String.format("15M EMA9=%.6f EMA21=%.6f against %s", e[0], e[1], dir > 0 ? "LONG" : "SHORT"));
//                 return 0;
//             }
//         }
//         r.dir = dir;
//         return dir;
//     }

//     // STAGE 2: score + mandatory safety checks on CLOSED candles, with the live price only for extension/chase checks.
//     // priceAgeMs < 0 = unknown. btc = BTC bias (+1 bullish / -1 bearish / 0 mixed) or BTC_NA / BTC_UNAVAILABLE.
//     static void stage2(Series5 s, double price, long priceAgeMs, long nowMs, int dir, int btc, Eval r) {
//         boolean isLong = dir > 0;
//         long cur5 = alignedStart(nowMs, FIVE_MIN_MS, 0L);
//         int end = closedEnd(s, cur5);
//         if (end < 60) { fail(r, "5m_noClosed", "not enough closed 5M candles"); return; }

//         // ---- 15M: EMA, ADX, ATR (closed bars only) ----
//         Bars b = closed15(s, nowMs, r);
//         if (b == null) return;
//         double[] e = ema15Closed(b);
//         r.ema9 = e[0]; r.ema21 = e[1];
//         boolean emaOk = isLong ? e[0] > e[1] : e[0] < e[1];
//         if (ENTRY_REQUIRE_15M_EMA && !emaOk) {
//             fail(r, "ema", String.format("15M EMA9=%.6f EMA21=%.6f against %s", e[0], e[1], isLong ? "LONG" : "SHORT"));
//             return;
//         }
//         int use15 = Math.min(b.n, ADX_WINDOW_15M);
//         double[] h15 = new double[use15], l15 = new double[use15], c15 = new double[use15];
//         System.arraycopy(b.h, b.n - use15, h15, 0, use15);
//         System.arraycopy(b.l, b.n - use15, l15, 0, use15);
//         System.arraycopy(b.c, b.n - use15, c15, 0, use15);
//         double[] adx = calcADX(h15, l15, c15, ADX_PERIOD);
//         if (adx == null) { fail(r, "adxHistory", "not enough 15M bars for ADX"); return; }
//         r.adx = adx[0]; r.plusDI = adx[1]; r.minusDI = adx[2];
//         r.atr15 = calcATRSeries(h15, l15, c15, ATR_PERIOD_15M)[use15 - 1];

//         // ---- 5M closed candles ----
//         int W = Math.min(end, 200), from = end - W, L = W - 1;
//         double[] o = new double[W], h = new double[W], l = new double[W], c = new double[W], v = new double[W];
//         for (int i = 0; i < W; i++) { o[i] = s.o[from + i]; h[i] = s.h[from + i]; l[i] = s.l[from + i]; c[i] = s.c[from + i]; v[i] = s.v[from + i]; }
//         double[] e9 = emaSeries(c, EMA_FAST), e21 = emaSeries(c, EMA_SLOW);
//         double[] atrS = calcATRSeries(h, l, c, ATR_PERIOD_5M);
//         r.atr5 = atrS[L];
//         if (!(r.atr5 > 0) || !(r.atr15 > 0)) { fail(r, "atr", "ATR not available"); return; }
//         r.candleOpen = s.t[from + L];

//         // ---- mandatory safety: fresh data, no spike, liquidity ----
//         long candleAge = nowMs - (r.candleOpen + FIVE_MIN_MS);
//         if (candleAge > ENTRY_MAX_CANDLE_AGE_MS) { fail(r, "lateSignal", "signal candle closed " + (candleAge / 1000) + "s ago (max " + (ENTRY_MAX_CANDLE_AGE_MS / 1000) + "s)"); return; }
//         if (priceAgeMs >= 0 && priceAgeMs > ENTRY_MAX_PRICE_AGE_MS) { fail(r, "stalePrice", "last trade is " + (priceAgeMs / 1000) + "s old"); return; }
//         double prevAtr = L >= 1 ? atrS[L - 1] : r.atr5;
//         if ((h[L] - l[L]) > ENTRY_SPIKE_RANGE_ATR5 * prevAtr) { fail(r, "spike", String.format("last closed 5M range %.6f > %.1f x ATR %.6f", h[L] - l[L], ENTRY_SPIKE_RANGE_ATR5, prevAtr)); return; }
//         if (Math.abs(price - c[L]) / c[L] * 100.0 > ENTRY_SPIKE_JUMP_PCT) { fail(r, "spike", String.format("live price %.6f jumped %.2f%% from the last close", price, Math.abs(price - c[L]) / c[L] * 100.0)); return; }

//         boolean volAvail = v[L] >= 0;
//         double avgV = 0;
//         for (int i = L - VOLUME_AVG_BARS; i < L; i++) { if (i < 0 || v[i] < 0) { volAvail = false; break; } avgV += v[i]; }
//         avgV /= VOLUME_AVG_BARS;
//         boolean volOk = volAvail && avgV > 0 && v[L] >= VOLUME_MIN_RATIO * avgV;
//         if (ENTRY_LIQUIDITY_CHECK_ENABLED && volAvail) {
//             double sum = 0;
//             for (int i = L - VOLUME_AVG_BARS + 1; i <= L; i++) sum += v[i];
//             double notional = sum / VOLUME_AVG_BARS * price;
//             if (notional < ENTRY_MIN_AVG_5M_NOTIONAL_USDT) { fail(r, "illiquid", String.format("avg 5M traded notional %.1f < %.1f", notional, ENTRY_MIN_AVG_5M_NOTIONAL_USDT)); return; }
//         }

//         // ---- trigger: pullback + confirmed candle, OR fresh confirmed swing break (both on CLOSED candles) ----
//         double body = Math.abs(c[L] - o[L]), range = h[L] - l[L];
//         boolean candleDir = dir * (c[L] - o[L]) > 0 && range > 0 && body / range >= ENTRY_MIN_BODY_RATIO;
//         String trig = null, trigWhy = "";
//         if (ENTRY_ALLOW_BREAKOUT) {
//             int n2 = SWING_LOOKBACK_5M + 2 * SWING_PIVOT_BARS + 6;
//             int m = Math.min(W, n2);
//             double[] hh = Arrays.copyOfRange(h, W - m, W), ll = Arrays.copyOfRange(l, W - m, W), cc = Arrays.copyOfRange(c, W - m, W);
//             Breakout bo = findBreakout(hh, ll, cc, isLong, SWING_PIVOT_BARS, SWING_LOOKBACK_5M);
//             if (bo.ok && candleDir && (!BREAKOUT_REQUIRES_VOLUME || volOk)) { trig = "BREAKOUT"; r.brkLevel = bo.level; trigWhy = bo.reason; }
//             else trigWhy = "breakout: " + (bo.ok ? (candleDir ? "volume too low/unavailable" : "weak/opposite candle") : bo.reason);
//         }
//         if (trig == null && ENTRY_ALLOW_PULLBACK) {
//             double z = PULLBACK_ZONE_ATR * r.atr5, brk = PULLBACK_MAX_BREAK_ATR * r.atr5;
//             boolean touched = false, counter = false, broken = false;
//             for (int i = Math.max(0, L - PULLBACK_LOOKBACK_5M); i < L; i++) {
//                 if (isLong ? l[i] <= e21[i] + z : h[i] >= e21[i] - z) touched = true;
//                 if (isLong ? c[i] < o[i] : c[i] > o[i]) counter = true;
//                 if (isLong ? c[i] < e21[i] - brk : c[i] > e21[i] + brk) broken = true;
//             }
//             boolean reclaim = isLong ? (c[L] > e9[L] && c[L] > e21[L]) : (c[L] < e9[L] && c[L] < e21[L]);
//             if (touched && counter && !broken && reclaim && candleDir) { trig = "PULLBACK"; r.brkLevel = e21[L]; trigWhy = String.format("5M pullback to EMA21=%.6f then confirmed close %.6f", e21[L], c[L]); }
//             else trigWhy += (trigWhy.isEmpty() ? "" : " | ") + String.format("pullback: touched=%s counter=%s broken=%s reclaim=%s candle=%s", touched, counter, broken, reclaim, candleDir);
//         }
//         if (trig == null) { fail(r, "trigger", "no 5M trigger: " + trigWhy); return; }
//         r.trigger = trig;

//         // ---- mandatory: not extended / not chased ----
//         if (dir * (price - e[1]) > ENTRY_MAX_EXT_ATR15 * r.atr15) {
//             fail(r, "extended", String.format("price %.6f is %.2f x ATR15 beyond 15M EMA21 %.6f (max %.1f)", price, dir * (price - e[1]) / r.atr15, e[1], ENTRY_MAX_EXT_ATR15)); return;
//         }
//         if (dir * (price - c[L]) > ENTRY_MAX_CHASE_ATR5 * r.atr5) {
//             fail(r, "chased", String.format("price %.6f already %.2f x ATR5 beyond the confirmation close %.6f (max %.1f)", price, dir * (price - c[L]) / r.atr5, c[L], ENTRY_MAX_CHASE_ATR5)); return;
//         }
//         if (dir * (price - o[L]) <= 0) { fail(r, "faded", "price fell back through the confirmation candle's open"); return; }

//         // ---- BTC filter ----
//         int btcPts = 0;
//         String btcTxt = "off";
//         if (BTC_FILTER_ENABLED) {
//             if (btc == BTC_NA) { btcPts = SCORE_BTC; btcTxt = "n/a"; }
//             else if (btc == BTC_UNAVAILABLE) { btcPts = 0; btcTxt = "unavailable"; }
//             else if (dir * btc > 0) { btcPts = SCORE_BTC; btcTxt = "aligned"; }
//             else if (btc == 0) { btcPts = SCORE_BTC / 2; btcTxt = "neutral"; }
//             else {
//                 btcPts = 0; btcTxt = "opposed";
//                 if (BTC_HARD_BLOCK_ON_OPPOSITE) { fail(r, "btcOpposed", "BTC direction is against " + (isLong ? "LONG" : "SHORT")); return; }
//             }
//         }

//         // ---- score (normalised to 100 over the components that are active) ----
//         int earned = SCORE_HTF, possible = SCORE_HTF;           // HTF alignment is mandatory -> always earned
//         StringBuilder sd = new StringBuilder("HTF=" + SCORE_HTF);
//         possible += SCORE_EMA15; if (emaOk) earned += SCORE_EMA15; sd.append(" EMA15=").append(emaOk ? SCORE_EMA15 : 0);
//         boolean adxOk = ADX_MIN <= 0 || (r.adx >= ADX_MIN && dir * (r.plusDI - r.minusDI) > 0);
//         possible += SCORE_ADX; if (adxOk) earned += SCORE_ADX; sd.append(" ADX=").append(adxOk ? SCORE_ADX : 0);
//         possible += SCORE_TRIGGER; earned += SCORE_TRIGGER; sd.append(" TRIG=").append(SCORE_TRIGGER);
//         possible += SCORE_VOLUME; if (volOk) earned += SCORE_VOLUME; sd.append(" VOL=").append(volOk ? SCORE_VOLUME : 0);
//         if (BTC_FILTER_ENABLED) { possible += SCORE_BTC; earned += btcPts; sd.append(" BTC=").append(btcPts).append('(').append(btcTxt).append(')'); }
//         r.score = (int) Math.round(100.0 * earned / possible);
//         r.scoreDetail = sd + " => " + earned + "/" + possible;
//         if (r.score < ENTRY_MIN_SCORE) { fail(r, "score", "score " + r.score + " < " + ENTRY_MIN_SCORE + " (" + r.scoreDetail + ")"); return; }

//         r.signal = isLong ? "BUY" : "SELL";
//         r.code = "signal";
//         r.reason = String.format("%s | 15M EMA9=%.6f EMA21=%.6f ADX=%.1f(+DI %.1f/-DI %.1f) | 5M %s: %s | vol=%s | score=%d [%s]",
//                 summary(r), e[0], e[1], r.adx, r.plusDI, r.minusDI, trig, trigWhy, volOk ? "ok" : "low/NA", r.score, r.scoreDetail);
//     }

//     static Eval evaluateStage1(Series5 s, double price, long nowMs) {
//         Eval r = new Eval();
//         r.price = price;
//         if (!(price > 0) || Double.isInfinite(price)) { fail(r, "noPrice", "no valid price"); return r; }
//         stage1(s, price, nowMs, r);
//         return r;
//     }

//     // Both stages with the same live price.
//     static Eval evaluateFull(Series5 s, double price, long priceAgeMs, long nowMs, int btc) {
//         Eval r = evaluateStage1(s, price, nowMs);
//         if (r.dir == 0) return r;
//         stage2(s, price, priceAgeMs, nowMs, r.dir, btc, r);
//         return r;
//     }

//     static String describeDev(Dev e) {
//         if (!e.ok) return e.tf + " INVALID (" + e.reason + ")";
//         return String.format("%s open=%.6f price=%.6f candle=%s supertrend=%s (%d bars)",
//                 e.tf, e.open, e.price, colName(e.colour), stName(e.stBull), e.stBars);
//     }

//     private static void logEval(String pair, Eval ev) {
//         for (Dev d : ev.dev) if (d != null) System.out.println("  [ST] " + pair + " " + describeDev(d));
//         if (ev.ema9 > 0) System.out.printf("  [ST] %s 15M EMA9=%.6f EMA21=%.6f ADX=%.1f trigger=%s level=%.6f score=%d%n", pair, ev.ema9, ev.ema21, ev.adx, ev.trigger, ev.brkLevel, ev.score);
//         System.out.println("  [ST] " + pair + " DECISION=" + (ev.signal.equals("BUY") ? "LONG" : ev.signal.equals("SELL") ? "SHORT" : "NO TRADE")
//                 + " | " + ev.reason);
//     }

//     static String ctxOf(Eval ev) {
//         StringBuilder sb = new StringBuilder();
//         for (Dev d : ev.dev) {
//             if (d == null || !d.ok) continue;
//             if (sb.length() > 0) sb.append(' ');
//             sb.append(d.tf).append(":ST=").append(d.stBull ? "G" : "R");
//         }
//         sb.append(String.format(" EMA9=%.6f EMA21=%.6f ADX=%.1f TRIG=%s LVL=%.6f SCORE=%d", ev.ema9, ev.ema21, ev.adx, ev.trigger, ev.brkLevel, ev.score));
//         return sb.toString();
//     }

//     // ---- BTC market direction (cached; one extra small fetch per BTC_CACHE_MS) ----
//     // +1 bullish (1H Supertrend GREEN and 15M EMA9 > EMA21), -1 bearish (both red), 0 mixed.
//     static int btcBiasOf(Series5 s, long nowMs) {
//         if (s == null || validate5m(s, nowMs) != null) return BTC_UNAVAILABLE;
//         Dev d = htfSupertrend(s, nowMs, s.c[s.n - 1], 0);
//         if (!d.ok) return BTC_UNAVAILABLE;
//         Bars b = closed15(s, nowMs, null);
//         if (b == null) return BTC_UNAVAILABLE;
//         double[] e = ema15Closed(b);
//         int sum = (d.stBull ? 1 : -1) + (e[0] > e[1] ? 1 : -1);
//         return sum > 0 ? 1 : (sum < 0 ? -1 : 0);
//     }

//     private static long btcAt = 0;
//     private static int btcBiasCached = BTC_UNAVAILABLE;

//     private static int btcFor(String pair, long nowMs) {
//         if (!BTC_FILTER_ENABLED || BTC_PAIR.equals(pair)) return BTC_NA;
//         if (btcAt == 0 || nowMs - btcAt >= BTC_CACHE_MS) {
//             int nb = btcBiasOf(getSeries(BTC_PAIR, nowMs), nowMs);
//             if (nb != btcBiasCached || btcAt == 0)
//                 System.out.println("  [BTC] market bias = " + (nb == BTC_UNAVAILABLE ? "UNAVAILABLE" : nb > 0 ? "BULLISH" : nb < 0 ? "BEARISH" : "MIXED"));
//             btcBiasCached = nb;
//             btcAt = nowMs;
//         }
//         return btcBiasCached;
//     }

//     // ---- live price with the age of the last trade (age -1 = unknown) ----
//     static double[] getLastTradeInfo(String pair) {
//         try {
//             HttpURLConnection conn = openGet(PUBLIC_API_URL + "/market_data/trade_history?pair=" + pair + "&limit=1");
//             if (conn.getResponseCode() == 200) {
//                 String r = readStream(conn.getInputStream());
//                 JSONObject j = r.startsWith("[") ? new JSONArray(r).getJSONObject(0) : new JSONObject(r);
//                 double p = j.getDouble("p");
//                 double t = j.optDouble("T", j.optDouble("t", -1));
//                 long age = -1;
//                 if (t > 0) { long tm = (long) t; if (tm < 100_000_000_000L) tm *= 1000L; age = Math.max(0, System.currentTimeMillis() - tm); }
//                 return new double[]{p, age};
//             }
//         } catch (Exception e) {
//             System.err.println("getLastTradeInfo(" + pair + "): " + e.getMessage());
//         }
//         return new double[]{0, -1};
//     }

//     // ---- order-book spread / depth (best effort: the endpoint format is verified on the first run) ----
//     static class BookInfo { double bid, ask, bidNotional, askNotional; }

//     static BookInfo parseBook(String body) {
//         try {
//             JSONObject j = new JSONObject(body);
//             double[] bid = sideOf(j.opt("bids"), true), ask = sideOf(j.opt("asks"), false);
//             if (bid == null || ask == null) return null;
//             BookInfo b = new BookInfo();
//             b.bid = bid[0]; b.bidNotional = bid[1]; b.ask = ask[0]; b.askNotional = ask[1];
//             return b.bid > 0 && b.ask > 0 ? b : null;
//         } catch (Exception e) { return null; }
//     }

//     // {best price, total notional} of one book side. Accepts {"price":"qty"} maps and [{price,quantity}] arrays.
//     private static double[] sideOf(Object o, boolean bids) {
//         double best = bids ? 0 : Double.MAX_VALUE, notional = 0;
//         int cnt = 0;
//         if (o instanceof JSONObject) {
//             JSONObject m = (JSONObject) o;
//             for (String k : m.keySet()) {
//                 double p = Double.parseDouble(k), q = m.optDouble(k, 0);
//                 best = bids ? Math.max(best, p) : Math.min(best, p);
//                 notional += p * q; cnt++;
//             }
//         } else if (o instanceof JSONArray) {
//             JSONArray a = (JSONArray) o;
//             for (int i = 0; i < a.length(); i++) {
//                 JSONObject x = a.getJSONObject(i);
//                 double p = x.optDouble("price", x.optDouble("p", 0)), q = x.optDouble("quantity", x.optDouble("q", 0));
//                 best = bids ? Math.max(best, p) : Math.min(best, p);
//                 notional += p * q; cnt++;
//             }
//         }
//         return cnt == 0 ? null : new double[]{best, notional};
//     }

//     // null = book OK. Pure function (unit-tested).
//     static String bookReason(BookInfo b, boolean isLong, double maxSpreadPct, double minNotional) {
//         double mid = (b.bid + b.ask) / 2.0;
//         double spread = (b.ask - b.bid) / mid * 100.0;
//         if (b.ask < b.bid) return "crossed order book";
//         if (spread > maxSpreadPct) return String.format("spread %.3f%% > %.3f%%", spread, maxSpreadPct);
//         double side = isLong ? b.askNotional : b.bidNotional;
//         if (side < minNotional) return String.format("thin book: %.1f USDT on the %s side < %.1f", side, isLong ? "ask" : "bid", minNotional);
//         return null;
//     }

//     private static String marketSafetyReason(String pair, boolean isLong) {
//         if (!ENTRY_SPREAD_CHECK_ENABLED) return null;
//         BookInfo b = null;
//         try {
//             b = parseBook(publicGet(PUBLIC_API_URL + "/market_data/v3/orderbook/" + pair + "-futures/" + ORDERBOOK_DEPTH));
//         } catch (Exception e) {
//             System.err.println("  [BOOK] " + pair + ": " + e.getMessage());
//         }
//         if (b == null) {
//             if (ENTRY_REQUIRE_SPREAD_DATA) return "order book unavailable (ENTRY_REQUIRE_SPREAD_DATA=true)";
//             System.out.println("  [BOOK] " + pair + " order book unavailable — spread check skipped");
//             return null;
//         }
//         return bookReason(b, isLong, ENTRY_MAX_SPREAD_PCT, ENTRY_MIN_BOOK_NOTIONAL_USDT);
//     }

//     static class Revalidation {
//         String reason;      // null = still valid
//         double price;
//     }

//     // Fresh 5M candles + fresh live price + fresh BTC bias -> full re-evaluation (all mandatory checks + score),
//     // then the order-book spread / depth check.
//     private static Revalidation revalidateBeforeOrder(String pair, boolean isLong) {
//         Revalidation rv = new Revalidation();
//         long now = System.currentTimeMillis();
//         Series5 s = getSeries(pair, now);
//         double[] lp = getLastTradeInfo(pair);
//         rv.price = lp[0];
//         Eval ev;
//         if (s == null) { ev = new Eval(); fail(ev, "noData", "no fresh 5M data"); }
//         else ev = evaluateFull(s, rv.price, (long) lp[1], System.currentTimeMillis(), btcFor(pair, System.currentTimeMillis()));
//         String want = isLong ? "BUY" : "SELL";
//         rv.reason = ev.signal.equals(want) ? null
//                 : "pre-order revalidation: now " + ev.signal + " - " + ev.reason;
//         if (rv.reason == null) {
//             String m = marketSafetyReason(pair, isLong);
//             if (m != null) rv.reason = "pre-order market check: " + m;
//         }
//         System.out.println("  [REVALIDATE] " + pair + " " + want + " price=" + rv.price + " score=" + ev.score + " | -> "
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
//     // Portfolio profit target — pure calculation (no I/O, unit-tested in --selftest)   [NEW]
//     // =========================================================================
//     // signedQty: LONG positive, SHORT negative. Result in the quote currency (USDT).
//     static double positionPnl(double signedQty, double avg, double px) { return (px - avg) * signedQty; }

//     // isolated margin = notional / leverage (USDT, same unit as the PnL, so the % never mixes INR and USDT)
//     static double positionMargin(double signedQty, double avg, double lev) { return lev > 0 ? Math.abs(signedQty) * avg / lev : 0; }

//     static boolean portfolioTargetReached(double pnl, double margin, double targetPct) {
//         return margin > 0 && pnl / margin * 100.0 >= targetPct - 1e-9;
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
//         j.put("qty", t.qty);
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
//         t.qty = j.optDouble("qty", 0);
//         return t;
//     }

//     // Atomic write (temp file + rename) so a crash in the middle never leaves a half-written state file.
//     private static synchronized void saveTradeState() {
//         try {
//             JSONObject root = new JSONObject();
//             for (Map.Entry<String, TradeInfo> en : tradeState.entrySet()) root.put(en.getKey(), tradeToJson(en.getValue()));
//             File target = new File(tradeStateFile);
//             File tmp = new File(tradeStateFile + ".tmp");
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
//         File f = new File(tradeStateFile);
//         if (!f.exists()) { System.out.println("[STATE] no saved trade state (" + tradeStateFile + ") — starting fresh"); return; }
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
//                 + " | TREND-FOLLOWING: 4H+1H Supertrend -> 15M EMA9/21 + ADX -> 5M pullback/breakout + volume + BTC filter, min score " + ENTRY_MIN_SCORE
//                 + " | HTF candles=" + (HTF_SUPERTREND_ON_CLOSED_CANDLES ? "CLOSED" : "DEVELOPING")
//                 + " | SL=" + FIXED_SL_PERCENT + "% TP=" + FIXED_TP_PERCENT + "% | leverage=" + LEVERAGE + "x | liquid list=" + USE_LIQUID_LIST
//                 + " (" + COINS_TO_TRADE.length + " symbols) ===");
//         System.out.println("=== Break-even + profit lock: " + (BREAKEVEN_ENABLED
//                 ? "ENABLED (trigger " + BREAKEVEN_TRIGGER_PERCENT + "%, SL locked at entry +/- " + PROFIT_LOCK_PERCENT + "%)"
//                 : "DISABLED") + (LIVE_TRADING_ENABLED ? "" : " — not applied in PAPER mode") + " ===");
//         if (BREAKEVEN_ENABLED && BREAKEVEN_TRIGGER_PERCENT <= PROFIT_LOCK_PERCENT)
//             System.err.println("WARNING: BREAKEVEN_TRIGGER_PERCENT should be greater than PROFIT_LOCK_PERCENT, otherwise the lock level can never be placed");
//         System.out.println("=== Trailing SL/TP: " + (TRAILING_ENABLED
//                 ? "ENABLED (activation " + TRAIL_ACTIVATION_PERCENT + "%, step " + TRAIL_STEP_PERCENT + "%, check every " + (TRAIL_CHECK_INTERVAL_MS / 1000) + "s)"
//                 : "DISABLED (fixed SL/TP only)") + (LIVE_TRADING_ENABLED ? "" : " — not applied in PAPER mode") + " ===");
//         System.out.println("=== Portfolio V2: target " + (PORTFOLIO_PROFIT_TARGET_ENABLED ? PORTFOLIO_PROFIT_TARGET_PERCENT + "%" : "OFF")
//                 + " of " + PORTFOLIO_TARGET_BASIS + " | breakeven " + (PORTFOLIO_BREAKEVEN_ENABLED ? "ON" : "OFF")
//                 + " | trailing " + (PORTFOLIO_TRAILING_ENABLED ? "ON" : "OFF") + " | max-loss " + (PORTFOLIO_MAX_LOSS_ENABLED ? PORTFOLIO_MAX_LOSS_PERCENT + "%" : "OFF")
//                 + " | check every " + (PORTFOLIO_CHECK_INTERVAL_MS / 1000) + "s | re-entry pause " + (PORTFOLIO_REENTRY_PAUSE_MS / 1000) + "s"
//                 + (LIVE_TRADING_ENABLED ? "" : " | PAPER: simulated positions, state not persisted") + " ===");
//         validatePortfolioConfig();
//         if (LIVE_TRADING_ENABLED && (API_KEY == null || API_SECRET == null)) {
//             System.err.println("FATAL: LIVE_TRADING_ENABLED but DELTA_API_KEY / DELTA_API_SECRET are not set");
//             return;
//         }
//         initInstrumentCache();
//         if (LIVE_TRADING_ENABLED) { loadTradeState(); loadPortfolioState(); }

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

//     private static PortfolioOps currentOps() { return LIVE_TRADING_ENABLED ? LIVE_OPS : PAPER_OPS; }

//     private static double newPositionMarginUsdt() { return FIXED_MARGIN / USDT_INR_RATE / LEVERAGE; }

//     // snapshot-based portfolio gate (liquidation / cooldowns / position + margin limits). null = entries allowed.
//     private static String portfolioBlockReason(int openCount) {
//         return portfolioEntryBlockedReason(System.currentTimeMillis(), openCount, portfolioDeployedMarginUsdt, newPositionMarginUsdt());
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
//         runPortfolioProfitCheck();              // portfolio V2: target / breakeven / trailing floor / max loss / close-all (does nothing when all OFF)
//         if (portfolioLiquidating()) {
//             System.out.println("Portfolio liquidation in progress — no new entries; retrying until the exchange confirms all positions closed.");
//             drainLiquidation(currentOps());
//             if (LIVE_TRADING_ENABLED) ensureTpSlForOpenPositions();
//             return;
//         }

//         String gate = portfolioBlockReason(active.size() + paperTrades.size());
//         if (gate != null) {
//             System.out.println("Portfolio gate — no new entries this cycle: " + gate);
//         } else if (active.size() + paperTrades.size() >= MAX_OPEN_POSITIONS) {
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
//         btcFor("", System.currentTimeMillis());          // refreshes the cached BTC bias once per scan (no-op when the filter is off)

//         for (String pair : COINS_TO_TRADE) {
//             try {
//                 // The scan of hundreds of symbols can take longer than the trailing interval, so trailing is also
//                 // checked from inside the scan loop (same thread -> no concurrent order/TP-SL calls).
//                 if (LIVE_TRADING_ENABLED && System.currentTimeMillis() - lastTrailPassMs >= TRAIL_CHECK_INTERVAL_MS) runTrailingPass();

//                 // Portfolio checks also run from inside the scan loop (rate limited), so they never wait for a full scan.
//                 if (PARAMS.anyEnabled()) runPortfolioProfitCheck();
//                 String gate = portfolioBlockReason(active.size() + paperTrades.size());
//                 if (gate != null) {
//                     System.out.println("  [PORTFOLIO] entries blocked — scan stopped: " + gate);
//                     break;
//                 }

//                 if (active.size() + paperTrades.size() >= MAX_OPEN_POSITIONS) break;
//                 if (active.contains(pair) || paperTrades.containsKey(pair)) continue;   // no duplicate / conflicting position per symbol
//                 if (System.currentTimeMillis() - lastTradeTime.getOrDefault(pair, 0L) < COOLDOWN_MS) continue;

//                 // ---- data: hourly-cached history + ONE small 5M request ----
//                 Series5 s = getSeries(pair, System.currentTimeMillis());
//                 if (s == null) { reject(rej, "noData"); continue; }

//                 // ---- STAGE 1: 4H + 1H Supertrend direction (+ 15M EMA) on closed candles ----
//                 long now0 = System.currentTimeMillis();
//                 Eval quick = evaluateStage1(s, s.c[s.n - 1], now0);
//                 if (quick.dir == 0) { reject(rej, quick.code); continue; }

//                 // ---- STAGE 2: score + mandatory safety checks with the real live price ----
//                 stage2++;
//                 double[] lp = getLastTradeInfo(pair);
//                 long now1 = System.currentTimeMillis();
//                 Eval ev = evaluateFull(s, lp[0], (long) lp[1], now1, btcFor(pair, now1));
//                 if (ev.signal.equals("NO TRADE")) {
//                     reject(rej, "live_" + ev.code);
//                     if (LOG_NO_TRADE_DETAIL && !ev.code.equals("trigger")) logEval(pair, ev);
//                     continue;
//                 }

//                 boolean isLong = ev.signal.equals("BUY");
//                 String sigKey = pair + "|" + (isLong ? "LONG" : "SHORT");
//                 // one signal per symbol + direction per CLOSED 5M signal candle (no repeated signals from the same candle)
//                 if (!registerSignal(lastSignalCandle, sigKey, ev.candleOpen)) { reject(rej, "duplicateSignal"); continue; }
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
//         QtyResult pq = sizeQuantity(FIXED_MARGIN / (price * USDT_INR_RATE), price, INTEGER_QTY_PAIRS.contains(pair), instrumentCache.get(pair));
//         if (pq.qty <= 0) { System.out.println("[PAPER] SKIP - " + pair + " - " + pq.reason); return; }
//         PaperTrade p = new PaperTrade();
//         p.qty = pq.qty;
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
//                     if (PARAMS.anyEnabled() && p.qty > 0) portfolioAddRealized(estimateRealizedNet(p.isLong, p.qty, p.entry, exitPx, PARAMS));
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
//             // Portfolio gate with FRESH exchange numbers (liquidation / cooldown / max positions / max deployed margin).
//             String pg = portfolioGateFresh();
//             if (pg != null) {
//                 System.out.println("SIGNAL CANCELLED - " + pair + " - portfolio gate: " + pg);
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
//             invalidatePositionsCache();

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
//             trade.qty = filledQty > 0 ? filledQty : qty;
//             tradeState.put(pair, trade);
//             saveTradeState();
//             active.add(pair);
//             portfolioDeployedMarginUsdt += newPositionMarginUsdt();
//             invalidatePositionsCache();
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
//         handleClosedTrades(active, CoinDCXFuturesTrader8C_BUY_SELL_NEW_LOGIC_THREE::getLastPrice);
//     }

//     // priceFn gives the last price of a pair (the portfolio pass shares its cached prices; the self-tests inject a fake)
//     static void handleClosedTrades(Set<String> active, java.util.function.ToDoubleFunction<String> priceFn) {
//         boolean removed = false;
//         for (Iterator<Map.Entry<String, TradeInfo>> it = tradeState.entrySet().iterator(); it.hasNext();) {
//             Map.Entry<String, TradeInfo> en = it.next();
//             if (active.contains(en.getKey())) continue;
//             double px = priceFn.applyAsDouble(en.getKey());
//             // positions closed on their own (SL / TP / trailing) are booked into the portfolio cycle as realized PnL
//             if (!portfolioClosedPairs.containsKey(en.getKey())) portfolioRecordIndividualClose(en.getKey(), en.getValue(), px);
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
//         // positions closed by the portfolio profit target are labelled explicitly instead of guessed from the price
//         String pr = portfolioClosedPairs.remove(pair);      // set when the position was closed by a portfolio close-all
//         String exit = pr != null ? pr : classifyExit(t, lastPx);
//         double[] m = mfeMaeR(t);
//         long heldMin = t.openedMs > 0 ? (System.currentTimeMillis() - t.openedMs) / 60_000L : -1;
//         System.out.printf("[CLOSED] %s %s exit=%s entry=%.6f initialSl=%.6f finalSl=%.6f finalTp=%.6f lastPx=%.6f held=%dmin trailing=%s MFE=%.2fR MAE=%.2fR | %s%n",
//                 pair, t.isLong ? "LONG" : "SHORT", exit, t.entry, t.initialSl, t.sl, t.tp, lastPx, heldMin,
//                 t.trailActive ? "was active" : "never activated", m[0], m[1], t.ctx);
//         if (!LOG_TRADES_CSV || selfTestMode) return;
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
//             JSONArray arr = parsePositionsResponse(resp);
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

//     // A positions response must be a JSON array, or a single position object. An error body is turned into an exception so the
//     // caller sees an API FAILURE (null) instead of a bogus "no positions" (which would wipe state / confirm a liquidation).
//     static JSONArray parsePositionsResponse(String resp) {
//         if (resp == null || resp.trim().isEmpty()) throw new IllegalStateException("empty positions response");
//         String t = resp.trim();
//         if (t.startsWith("[")) return new JSONArray(t);
//         JSONObject o = new JSONObject(t);
//         if (!o.has("pair") && (isErrorResponse(t) || o.has("message") || o.has("error") || o.has("code")))
//             throw new IllegalStateException("unexpected positions response: " + (t.length() > 120 ? t.substring(0, 120) : t));
//         return o.has("pair") ? new JSONArray().put(o) : new JSONArray();
//     }

//     // One positions snapshot shared by the trailing pass and the portfolio pass (max age in ms; 0 = always fetch).
//     private static Map<String, JSONObject> posCache = null;
//     private static long posCacheAt = 0;

//     private static Map<String, JSONObject> getOpenPositionsMapCached(long maxAgeMs) {
//         long now = System.currentTimeMillis();
//         if (maxAgeMs > 0 && posCache != null && now - posCacheAt <= maxAgeMs) return posCache;
//         Map<String, JSONObject> m = getOpenPositionsMap();
//         if (m != null) { posCache = m; posCacheAt = now; } else posCache = null;
//         return m;
//     }

//     private static void invalidatePositionsCache() { posCache = null; }

//     // Live price shared by the trailing pass and the portfolio pass (short TTL, so no duplicate price calls).
//     private static final Map<String, double[]> priceCache = new ConcurrentHashMap<>();   // pair -> {price, fetchedAtMs}

//     static double getLivePrice(String pair) {
//         long now = System.currentTimeMillis();
//         double[] c = priceCache.get(pair);
//         if (c != null && now - (long) c[1] <= PRICE_CACHE_MS) return c[0];
//         double p = getLastPrice(pair);
//         if (p > 0) priceCache.put(pair, new double[]{p, now});
//         return p;
//     }

//     // Fresh exchange numbers right before an order: null = entry allowed. An API failure blocks the entry (safe side).
//     private static String portfolioGateFresh() {
//         Map<String, PosRec> fresh = toRecs(getOpenPositionsMapCached(0));
//         if (fresh == null) return "positions API failed — cannot verify the portfolio limits";
//         int n = 0; double dep = 0;
//         for (PosRec r : fresh.values()) if (r.open()) { n++; dep += positionMargin(r.q, r.avg, r.lev); }
//         portfolioOpenCount = n; portfolioDeployedMarginUsdt = dep;
//         return portfolioEntryBlockedReason(System.currentTimeMillis(), n, dep, newPositionMarginUsdt());
//     }

//     private static void runTrailingPass() {
//         lastTrailPassMs = System.currentTimeMillis();
//         if (portfolioLiquidating()) { System.out.println("[TRAIL] portfolio liquidation in progress — trailing pass skipped"); return; }
//         Map<String, JSONObject> positions = getOpenPositionsMapCached(POSITIONS_CACHE_MS);
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

//     // =========================================================================
//     // PORTFOLIO MANAGEMENT V2  (one combined portfolio: hard target, breakeven, trailing floor, max loss, close-all)
//     //
//     //   UNITS: everything is computed in USDT (the quote currency of price/PnL) and converted with USDT_INR_RATE only for
//     //          the account-capital basis and for INR display. Leveraged notional is NEVER used as a basis.
//     //   NET PnL = unrealized gross PnL - estimated entry fees - estimated exit fees - funding + realized PnL of the cycle
//     //   BASIS   = TOTAL_ACCOUNT_CAPITAL (PORTFOLIO_ACCOUNT_CAPITAL_INR / USDT_INR_RATE)  or  TOTAL_DEPLOYED_MARGIN (sum of |qty|*avg/leverage)
//     //   Everything runs on the MAIN thread (cooperatively, between symbol evaluations): no second thread can send a conflicting
//     //   order / close / SL-TP request.
//     // =========================================================================
//     static final String EXIT_HARD_TP    = "PORTFOLIO_HARD_TP";
//     static final String EXIT_BREAKEVEN  = "PORTFOLIO_BREAKEVEN";
//     static final String EXIT_TRAILING   = "PORTFOLIO_TRAILING_SL";
//     static final String EXIT_RISK_LIMIT = "PORTFOLIO_RISK_LIMIT";

//     // ---- all tunables in one object so the pure logic can be unit-tested with other values ----
//     static class PortfolioParams {
//         boolean targetEnabled = PORTFOLIO_PROFIT_TARGET_ENABLED;
//         double  targetPct = PORTFOLIO_PROFIT_TARGET_PERCENT;
//         String  basis = PORTFOLIO_TARGET_BASIS;
//         double  capitalInr = PORTFOLIO_ACCOUNT_CAPITAL_INR;
//         boolean beEnabled = PORTFOLIO_BREAKEVEN_ENABLED;
//         double  beTrigPct = PORTFOLIO_BREAKEVEN_TRIGGER_PERCENT, beLockPct = PORTFOLIO_BREAKEVEN_LOCK_PERCENT;
//         boolean trEnabled = PORTFOLIO_TRAILING_ENABLED;
//         double  trActPct = PORTFOLIO_TRAILING_ACTIVATION_PERCENT, trGivebackPct = PORTFOLIO_TRAILING_GIVEBACK_PERCENT;
//         boolean maxLossEnabled = PORTFOLIO_MAX_LOSS_ENABLED;
//         double  maxLossPct = PORTFOLIO_MAX_LOSS_PERCENT;
//         double  feeRatePct = PORTFOLIO_FEE_RATE_PERCENT, gstPct = PORTFOLIO_FEE_GST_PERCENT;
//         double feeFactor() { return feeRatePct / 100.0 * (1.0 + gstPct / 100.0); }
//         boolean anyEnabled() { return targetEnabled || beEnabled || trEnabled || maxLossEnabled; }
//     }
//     static PortfolioParams PARAMS = new PortfolioParams();

//     // ---- persisted cycle state ----
//     static class PortfolioState {
//         long   cycleId;
//         long   cycleStartMs;            // 0 = no active cycle
//         double peakNet;                 // highest NET portfolio PnL of the cycle (USDT)
//         boolean beActive, trailActive, floorActive;
//         double floor;                   // protected net profit floor (USDT) - never decreases within a cycle
//         String floorSource = "";        // BREAKEVEN / TRAILING
//         double realizedNet;             // estimated realized PnL (net of fees) of positions closed individually in this cycle
//         boolean liquidating;
//         String  liquidationReason = "";
//         long    liquidationStartMs;
//         int     liquidationRounds;
//         double  liquidationNet;         // net PnL snapshot at the moment the exit fired
//         long    reentryUntilMs, riskPauseUntilMs;
//         String  lastExitReason = "";
//         double  lastFinalPnl;
//         long    lastCycleEndMs;
//     }
//     static PortfolioState pState = new PortfolioState();
//     static String portfolioStateFile = PORTFOLIO_STATE_FILE;

//     static JSONObject portfolioToJson(PortfolioState s) {
//         JSONObject j = new JSONObject();
//         j.put("cycleId", s.cycleId); j.put("cycleStartMs", s.cycleStartMs); j.put("peakNet", s.peakNet);
//         j.put("beActive", s.beActive); j.put("trailActive", s.trailActive); j.put("floorActive", s.floorActive);
//         j.put("floor", s.floor); j.put("floorSource", s.floorSource); j.put("realizedNet", s.realizedNet);
//         j.put("liquidating", s.liquidating); j.put("liquidationReason", s.liquidationReason);
//         j.put("liquidationStartMs", s.liquidationStartMs); j.put("liquidationRounds", s.liquidationRounds);
//         j.put("liquidationNet", s.liquidationNet);
//         j.put("reentryUntilMs", s.reentryUntilMs); j.put("riskPauseUntilMs", s.riskPauseUntilMs);
//         j.put("lastExitReason", s.lastExitReason); j.put("lastFinalPnl", s.lastFinalPnl); j.put("lastCycleEndMs", s.lastCycleEndMs);
//         return j;
//     }

//     static PortfolioState portfolioFromJson(JSONObject j) {
//         PortfolioState s = new PortfolioState();
//         s.cycleId = j.optLong("cycleId", 0); s.cycleStartMs = j.optLong("cycleStartMs", 0); s.peakNet = j.optDouble("peakNet", 0);
//         s.beActive = j.optBoolean("beActive", false); s.trailActive = j.optBoolean("trailActive", false);
//         s.floorActive = j.optBoolean("floorActive", false); s.floor = j.optDouble("floor", 0);
//         s.floorSource = j.optString("floorSource", ""); s.realizedNet = j.optDouble("realizedNet", 0);
//         s.liquidating = j.optBoolean("liquidating", false); s.liquidationReason = j.optString("liquidationReason", "");
//         s.liquidationStartMs = j.optLong("liquidationStartMs", 0); s.liquidationRounds = j.optInt("liquidationRounds", 0);
//         s.liquidationNet = j.optDouble("liquidationNet", 0);
//         s.reentryUntilMs = j.optLong("reentryUntilMs", 0); s.riskPauseUntilMs = j.optLong("riskPauseUntilMs", 0);
//         s.lastExitReason = j.optString("lastExitReason", ""); s.lastFinalPnl = j.optDouble("lastFinalPnl", 0);
//         s.lastCycleEndMs = j.optLong("lastCycleEndMs", 0);
//         return s;
//     }

//     // Atomic write (temp file + rename), same pattern as the trade state.
//     private static synchronized void savePortfolioState() {
//         try {
//             File target = new File(portfolioStateFile);
//             File tmp = new File(portfolioStateFile + ".tmp");
//             Files.write(tmp.toPath(), portfolioToJson(pState).toString(2).getBytes(StandardCharsets.UTF_8));
//             try {
//                 Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
//             } catch (Exception atomicNotSupported) {
//                 Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
//             }
//         } catch (Exception e) {
//             System.err.println("savePortfolioState failed: " + e.getMessage());
//         }
//     }

//     static void loadPortfolioState() {
//         File f = new File(portfolioStateFile);
//         if (!f.exists()) { System.out.println("[PORTFOLIO] no saved portfolio state (" + portfolioStateFile + ") — starting fresh"); return; }
//         try {
//             pState = portfolioFromJson(new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)));
//             System.out.printf("[PORTFOLIO] state restored: cycle #%d active=%s peak=%.4f USDT BE=%s trail=%s floor=%s liquidating=%s%n",
//                     pState.cycleId, pState.cycleStartMs > 0, pState.peakNet, pState.beActive, pState.trailActive,
//                     pState.floorActive ? String.format("%.4f", pState.floor) : "none", pState.liquidating);
//         } catch (Exception e) {
//             System.err.println("[PORTFOLIO] could not read " + portfolioStateFile + " (" + e.getMessage() + ") — backing it up, starting fresh");
//             try { Files.move(f.toPath(), new File(portfolioStateFile + ".corrupt").toPath(), StandardCopyOption.REPLACE_EXISTING); }
//             catch (Exception ignored) {}
//             pState = new PortfolioState();
//         }
//     }

//     // ---- pure calculations (no I/O, unit-tested) ----
//     static class PosView { String pair; double q, avg, px, lev, funding; }

//     static class PortfolioMetrics {
//         int n;
//         double margin, gross, entryFees, exitFees, funding, realized, net, basis;
//         double targetAmt, beTrigAmt, beLockAmt, trActAmt, maxLossAmt;
//     }

//     // basis in USDT
//     static double portfolioBasisUsdt(String basis, double capitalInr, double deployedMarginUsdt) {
//         return "TOTAL_DEPLOYED_MARGIN".equals(basis) ? deployedMarginUsdt : capitalInr / USDT_INR_RATE;
//     }

//     // Net PnL of one position given a price (fees estimated on both sides; funding = net funding PAID, positive = cost)
//     static PortfolioMetrics computeMetrics(List<PosView> views, double realizedCycle, PortfolioParams p) {
//         PortfolioMetrics m = new PortfolioMetrics();
//         for (PosView v : views) {
//             m.n++;
//             m.gross     += positionPnl(v.q, v.avg, v.px);
//             m.margin    += positionMargin(v.q, v.avg, v.lev);
//             m.entryFees += Math.abs(v.q) * v.avg * p.feeFactor();
//             m.exitFees  += Math.abs(v.q) * v.px  * p.feeFactor();
//             m.funding   += v.funding;
//         }
//         m.realized = realizedCycle;
//         m.net = m.gross - m.entryFees - m.exitFees - m.funding + m.realized;
//         applyThresholds(m, p);
//         return m;
//     }

//     // basis + every threshold in USDT (all percentages are % of the basis)
//     static void applyThresholds(PortfolioMetrics m, PortfolioParams p) {
//         m.basis = portfolioBasisUsdt(p.basis, p.capitalInr, m.margin);
//         m.targetAmt  = m.basis * p.targetPct / 100.0;
//         m.beTrigAmt  = m.basis * p.beTrigPct / 100.0;
//         m.beLockAmt  = p.beEnabled ? m.basis * p.beLockPct / 100.0 : 0.0;
//         m.trActAmt   = m.basis * p.trActPct / 100.0;
//         m.maxLossAmt = m.basis * p.maxLossPct / 100.0;
//     }

//     static class PortfolioDecision {
//         String exitReason;                       // null = hold
//         String detail = "";
//         boolean stateChanged;
//         final List<String> events = new ArrayList<>();
//     }

//     // Updates peak / breakeven / trailing floor (monotonic) and decides whether the whole portfolio must be closed.
//     // Priority: HARD TP  >  RISK LIMIT  >  protected floor (trailing / breakeven).
//     static PortfolioDecision decidePortfolio(PortfolioState st, PortfolioMetrics m, PortfolioParams p) {
//         PortfolioDecision d = new PortfolioDecision();
//         if (!(m.basis > 0)) return d;
//         final double eps = 1e-9;
//         if (m.net > st.peakNet + eps) { st.peakNet = m.net; d.stateChanged = true; }

//         if (p.targetEnabled && m.net >= m.targetAmt - eps) {
//             d.exitReason = EXIT_HARD_TP;
//             d.detail = String.format("net %.4f >= target %.4f USDT (%.2f%%)", m.net, m.targetAmt, p.targetPct);
//             return d;
//         }
//         if (p.maxLossEnabled && m.net <= -m.maxLossAmt + eps) {
//             d.exitReason = EXIT_RISK_LIMIT;
//             d.detail = String.format("net %.4f <= -%.4f USDT (-%.2f%%)", m.net, m.maxLossAmt, p.maxLossPct);
//             return d;
//         }
//         if (p.beEnabled && !st.beActive && st.peakNet >= m.beTrigAmt - eps) {
//             st.beActive = true; d.stateChanged = true;
//             d.events.add(String.format("[PORTFOLIO-BE] breakeven protection ACTIVATED at net %.4f USDT (trigger %.4f)", m.net, m.beTrigAmt));
//         }
//         if (p.trEnabled && !st.trailActive && st.peakNet >= m.trActAmt - eps) {
//             st.trailActive = true; d.stateChanged = true;
//             d.events.add(String.format("[PORTFOLIO-TRAIL] trailing profit lock ACTIVATED at net %.4f USDT (activation %.4f)", m.net, m.trActAmt));
//         }
//         // ProtectedProfit = max(BreakevenLockAmount, Peak - AllowedGiveback)
//         double cand = Double.NEGATIVE_INFINITY;
//         String src = "";
//         if (st.beActive) { cand = m.beLockAmt; src = EXIT_BREAKEVEN; }
//         if (st.trailActive) {
//             double tf = Math.max(m.beLockAmt, st.peakNet - st.peakNet * p.trGivebackPct / 100.0);
//             if (tf > cand + eps || cand == Double.NEGATIVE_INFINITY) { cand = tf; src = tf > m.beLockAmt + eps ? EXIT_TRAILING : EXIT_BREAKEVEN; }
//         }
//         if (cand > Double.NEGATIVE_INFINITY && (!st.floorActive || cand > st.floor + eps)) {
//             double old = st.floor; boolean had = st.floorActive;
//             st.floor = cand; st.floorActive = true; st.floorSource = src; d.stateChanged = true;
//             d.events.add(String.format("%s protected profit floor %s -> %.4f USDT (peak %.4f)", EXIT_TRAILING.equals(src) ? "[PORTFOLIO-TRAIL]" : "[PORTFOLIO-BE]",
//                     had ? String.format("%.4f", old) : "none", st.floor, st.peakNet));
//         }
//         if (st.floorActive && m.net <= st.floor + eps) {
//             d.exitReason = EXIT_TRAILING.equals(st.floorSource) ? EXIT_TRAILING : EXIT_BREAKEVEN;
//             d.detail = String.format("net %.4f <= protected floor %.4f USDT (peak %.4f)", m.net, st.floor, st.peakNet);
//         }
//         return d;
//     }

//     // estimated realized PnL of a fully closed position, net of both fees (USDT)
//     static double estimateRealizedNet(boolean isLong, double qtyAbs, double entry, double exit, PortfolioParams p) {
//         double gross = (exit - entry) * (isLong ? qtyAbs : -qtyAbs);
//         return gross - qtyAbs * (entry + exit) * p.feeFactor();
//     }

//     static double inr(double usdt) { return usdt * USDT_INR_RATE; }

//     // =========================================================================
//     // Exchange abstraction (LIVE and PAPER implement it; the self-tests use a scripted fake)
//     // =========================================================================
//     static class PosRec {
//         String pair, id = "";
//         double q, avg, lev;
//         boolean open() { return Math.abs(q) > 0; }
//     }

//     interface PortfolioOps {
//         Map<String, PosRec> fetchOpen();                 // may be a <=1.5s cached snapshot; null = API failure
//         Map<String, PosRec> fetchOpenFresh();            // never cached; null = API failure
//         double price(String pair);                       // <= 0 = unavailable
//         boolean close(PosRec p, String reason);          // true = close request accepted
//         void sleep(long ms);
//         long now();
//         void closedAccounting(Set<String> openPairs);    // bookkeeping of positions that closed on their own
//         boolean persist();
//     }

//     // ---- runtime (not persisted) ----
//     private static long lastPortfolioCheckMs = 0, portfolioLastLogMs = 0, portfolioIncompleteLogMs = 0;
//     private static boolean portfolioBusy = false;
//     private static double portfolioDeployedMarginUsdt = 0;
//     private static int portfolioOpenCount = 0;
//     private static long portfolioSnapshotMs = 0;
//     private static final Map<String, Long> closeRequestedAt = new HashMap<>();
//     private static final Map<String, Integer> closeAttempts = new HashMap<>();
//     // pair -> portfolio exit reason, for positions closed by a close-all (used to label the trade log)
//     private static final Map<String, String> portfolioClosedPairs = new ConcurrentHashMap<>();

//     private static void persistState(PortfolioOps ops) { if (ops.persist()) savePortfolioState(); }

//     // Why a NEW entry is not allowed right now (null = allowed). newMarginUsdt = margin the new position would lock.
//     static String portfolioEntryBlockedReason(long now, int openCount, double deployedMarginUsdt, double newMarginUsdt) {
//         PortfolioState st = pState;
//         if (st.liquidating) return "portfolio liquidation in progress (" + st.liquidationReason + ")";
//         if (now < st.riskPauseUntilMs) return "portfolio risk pause for another " + ((st.riskPauseUntilMs - now) / 60_000L) + " min";
//         if (now < st.reentryUntilMs) return "portfolio re-entry cooldown for another " + ((st.reentryUntilMs - now) / 1000L) + "s";
//         if (PORTFOLIO_MAX_OPEN_POSITIONS > 0 && openCount >= PORTFOLIO_MAX_OPEN_POSITIONS)
//             return "PORTFOLIO_MAX_OPEN_POSITIONS (" + PORTFOLIO_MAX_OPEN_POSITIONS + ") reached";
//         if (PORTFOLIO_MAX_DEPLOYED_MARGIN_PERCENT > 0) {
//             double cap = PARAMS.capitalInr / USDT_INR_RATE * PORTFOLIO_MAX_DEPLOYED_MARGIN_PERCENT / 100.0;
//             if (deployedMarginUsdt + newMarginUsdt > cap + 1e-9)
//                 return String.format("PORTFOLIO_MAX_DEPLOYED_MARGIN_PERCENT: %.2f + %.2f > %.2f USDT", deployedMarginUsdt, newMarginUsdt, cap);
//         }
//         return null;
//     }

//     // ---------------------------------------------------------------------------------------------
//     // One portfolio pass (single-threaded; re-entrancy guarded)
//     // ---------------------------------------------------------------------------------------------
//     static void portfolioPass(PortfolioOps ops) {
//         if (portfolioBusy) return;
//         portfolioBusy = true;
//         try {
//             portfolioPassInner(ops);
//         } catch (Exception e) {
//             System.err.println("[PORTFOLIO] pass error: " + e.getMessage());
//         } finally {
//             portfolioBusy = false;
//         }
//     }

//     private static void portfolioPassInner(PortfolioOps ops) {
//         PortfolioState st = pState;
//         long now = ops.now();
//         Map<String, PosRec> all = ops.fetchOpen();
//         if (all == null) {
//             System.err.println("[PORTFOLIO] positions API failed — no portfolio decision this pass" + (st.liquidating ? " (liquidation continues on the next pass)" : ""));
//             return;
//         }
//         List<PosRec> open = new ArrayList<>();
//         Set<String> openPairs = new HashSet<>();
//         for (PosRec r : all.values()) if (r.open()) { open.add(r); openPairs.add(r.pair); }
//         ops.closedAccounting(openPairs);          // positions that closed on their own -> realized PnL is booked into the cycle

//         double dep = 0;
//         for (PosRec r : open) dep += positionMargin(r.q, r.avg, r.lev);
//         portfolioOpenCount = open.size();
//         portfolioDeployedMarginUsdt = dep;
//         portfolioSnapshotMs = now;

//         if (open.isEmpty()) {
//             if (st.liquidating) finalizeLiquidation(ops, now);
//             else if (st.cycleStartMs > 0) endCycleNaturally(ops, now);
//             return;
//         }
//         if (st.cycleStartMs == 0) {
//             st.cycleId++; st.cycleStartMs = now; st.peakNet = 0;
//             System.out.println("[PORTFOLIO-RESET] new portfolio cycle #" + st.cycleId + " started with " + open.size() + " position(s)");
//             persistState(ops);
//         }
//         if (st.liquidating) { liquidationStep(ops, open, now); return; }

//         // ---- net PnL: every price/position value must be known, otherwise NO decision (never a fake zero) ----
//         List<PosView> views = new ArrayList<>();
//         String why = null;
//         for (PosRec r : open) {
//             double px = ops.price(r.pair);
//             if (!(px > 0)) { why = "no price for " + r.pair; break; }
//             if (!(r.avg > 0)) { why = "no average entry for " + r.pair; break; }
//             if (!(r.lev > 0)) { why = "no leverage for " + r.pair; break; }
//             PosView v = new PosView();
//             v.pair = r.pair; v.q = r.q; v.avg = r.avg; v.px = px; v.lev = r.lev; v.funding = 0;
//             views.add(v);
//         }
//         if (why != null) {
//             if (now - portfolioIncompleteLogMs >= 30_000L) { System.err.println("[PORTFOLIO] incomplete data (" + why + ") — exit decision skipped this pass"); portfolioIncompleteLogMs = now; }
//             return;
//         }
//         PortfolioMetrics m = computeMetrics(views, st.realizedNet, PARAMS);
//         PortfolioDecision d = decidePortfolio(st, m, PARAMS);
//         for (String ev : d.events) System.out.println(ev);
//         if (now - portfolioLastLogMs >= PORTFOLIO_STATUS_LOG_MS || d.exitReason != null) {
//             System.out.printf("  [PORTFOLIO] cycle #%d | %d position(s) | basis=%s %.2f USDT (INR %.0f) | deployedMargin=%.2f USDT (INR %.0f)%n",
//                     st.cycleId, m.n, PARAMS.basis, m.basis, inr(m.basis), m.margin, inr(m.margin));
//             System.out.printf("  [PORTFOLIO] grossPnL=%.4f | fees(entry %.4f + exit %.4f) | funding=%.4f%s | realized(est)=%.4f | NET=%.4f USDT (INR %.2f) = %.2f%% of basis%n",
//                     m.gross, m.entryFees, m.exitFees, m.funding, " (not provided by the positions API -> excluded)", m.realized, m.net, inr(m.net), m.net / m.basis * 100.0);
//             System.out.printf("  [PORTFOLIO-TARGET] target=%.2f%% = %.4f USDT (INR %.2f) | peak=%.4f | BE=%s | trailing=%s | protectedFloor=%s%n",
//                     PARAMS.targetPct, m.targetAmt, inr(m.targetAmt), st.peakNet, st.beActive ? "ACTIVE" : "waiting", st.trailActive ? "ACTIVE" : "waiting",
//                     st.floorActive ? String.format("%.4f USDT (%s)", st.floor, st.floorSource) : "none");
//             portfolioLastLogMs = now;
//         }
//         if (d.stateChanged) persistState(ops);
//         if (d.exitReason == null) return;

//         // ---- exit condition hit: start the liquidation (state is persisted BEFORE any close request is sent) ----
//         st.liquidating = true; st.liquidationReason = d.exitReason; st.liquidationStartMs = now;
//         st.liquidationRounds = 0; st.liquidationNet = m.net;
//         closeRequestedAt.clear(); closeAttempts.clear();
//         persistState(ops);
//         String tag = EXIT_HARD_TP.equals(d.exitReason) ? "[PORTFOLIO-TARGET]" : EXIT_TRAILING.equals(d.exitReason) ? "[PORTFOLIO-TRAIL]"
//                 : EXIT_BREAKEVEN.equals(d.exitReason) ? "[PORTFOLIO-BE]" : "[PORTFOLIO]";
//         System.out.printf("%s %s — %s | closing ALL %d position(s), new entries stopped%n", tag, d.exitReason, d.detail, open.size());
//         System.out.printf("[PORTFOLIO-LIQUIDATION] started: reason=%s net=%.4f USDT (INR %.2f) peak=%.4f%n", d.exitReason, m.net, inr(m.net), st.peakNet);
//         liquidationStep(ops, open, now);
//     }

//     // Sends close requests (at most one per pair per PORTFOLIO_CLOSE_RETRY_INTERVAL_MS), verifies against the exchange,
//     // and finalizes only when the exchange confirms that NO position is left. Retried on every pass until then.
//     private static void liquidationStep(PortfolioOps ops, List<PosRec> open, long now) {
//         PortfolioState st = pState;
//         st.liquidationRounds++;
//         int sent = 0, failed = 0, waiting = 0;
//         for (PosRec r : open) {
//             long last = closeRequestedAt.getOrDefault(r.pair, 0L);
//             if (last > 0 && now - last < PORTFOLIO_CLOSE_RETRY_INTERVAL_MS) { waiting++; continue; }     // no duplicate close request
//             closeRequestedAt.put(r.pair, now);
//             int attempt = closeAttempts.merge(r.pair, 1, Integer::sum);
//             boolean ok = r.id != null && !r.id.isEmpty() && ops.close(r, st.liquidationReason);
//             if (ok) { sent++; portfolioClosedPairs.put(r.pair, st.liquidationReason); }
//             else {
//                 failed++;
//                 System.err.println("[PORTFOLIO-LIQUIDATION] close request FAILED for " + r.pair + " (attempt " + attempt + ") — will retry");
//             }
//         }
//         System.out.printf("[PORTFOLIO-LIQUIDATION] round %d: requested=%d failed=%d pendingVerification=%d of %d open%n", st.liquidationRounds, sent, failed, waiting, open.size());
//         ops.sleep(PORTFOLIO_CLOSE_VERIFY_DELAY_MS);
//         Map<String, PosRec> after = ops.fetchOpenFresh();
//         if (after == null) {
//             System.err.println("[PORTFOLIO-LIQUIDATION] cannot verify (positions API failed) — liquidation stays active, retry next pass");
//             persistState(ops);
//             return;
//         }
//         List<String> left = new ArrayList<>();
//         for (PosRec r : after.values()) if (r.open()) left.add(r.pair);
//         if (left.isEmpty()) { finalizeLiquidation(ops, ops.now()); return; }
//         System.out.println("[PORTFOLIO-LIQUIDATION] still open on the exchange: " + left + " — retrying");
//         persistState(ops);
//     }

//     private static void finalizeLiquidation(PortfolioOps ops, long now) {
//         PortfolioState st = pState;
//         ops.closedAccounting(new HashSet<String>());      // logs the closed trades (labelled with the portfolio exit reason)
//         double fin = st.liquidationNet;
//         System.out.printf("[PORTFOLIO-LIQUIDATION] ALL positions confirmed closed after %d round(s). Reason=%s%n", st.liquidationRounds, st.liquidationReason);
//         System.out.printf("[PORTFOLIO-RESET] cycle #%d final realized portfolio PnL ≈ %.4f USDT (INR %.2f) [estimate from the last marks + fees; actual fills may differ] | peak was %.4f%n",
//                 st.cycleId, fin, inr(fin), st.peakNet);
//         st.lastExitReason = st.liquidationReason; st.lastFinalPnl = fin; st.lastCycleEndMs = now;
//         if (EXIT_RISK_LIMIT.equals(st.liquidationReason)) {
//             st.riskPauseUntilMs = now + PORTFOLIO_RISK_PAUSE_MS;
//             System.out.println("[PORTFOLIO-RESET] max-loss reached — new entries paused for " + (PORTFOLIO_RISK_PAUSE_MS / 60_000L) + " min");
//         }
//         resetCycle(st);
//         st.reentryUntilMs = now + PORTFOLIO_REENTRY_PAUSE_MS;
//         closeRequestedAt.clear(); closeAttempts.clear(); portfolioClosedPairs.clear();
//         System.out.println("[PORTFOLIO-RESET] peak / breakeven / trailing / cycle state reset; fresh cycle starts with the next position (re-entry cooldown " + (PORTFOLIO_REENTRY_PAUSE_MS / 1000L) + "s)");
//         persistState(ops);
//     }

//     private static void endCycleNaturally(PortfolioOps ops, long now) {
//         PortfolioState st = pState;
//         System.out.printf("[PORTFOLIO-RESET] cycle #%d ended: every position closed on its own (SL/TP/trailing). Realized(est)=%.4f USDT (INR %.2f), peak was %.4f%n",
//                 st.cycleId, st.realizedNet, inr(st.realizedNet), st.peakNet);
//         st.lastExitReason = "ALL_CLOSED_INDIVIDUALLY"; st.lastFinalPnl = st.realizedNet; st.lastCycleEndMs = now;
//         resetCycle(st);
//         persistState(ops);
//     }

//     private static void resetCycle(PortfolioState st) {
//         st.cycleStartMs = 0; st.peakNet = 0; st.beActive = false; st.trailActive = false; st.floorActive = false;
//         st.floor = 0; st.floorSource = ""; st.realizedNet = 0;
//         st.liquidating = false; st.liquidationReason = ""; st.liquidationRounds = 0; st.liquidationStartMs = 0; st.liquidationNet = 0;
//     }

//     // Realized PnL of a position that closed on its own (SL / TP / trailing) is booked into the running cycle.
//     static void portfolioAddRealized(double pnlUsdt) {
//         if (pState.cycleStartMs == 0) pState.cycleStartMs = System.currentTimeMillis();
//         pState.realizedNet += pnlUsdt;
//     }

//     private static void portfolioRecordIndividualClose(String pair, TradeInfo t, double lastPx) {
//         if (!PARAMS.anyEnabled()) return;
//         if (t.qty <= 0) { System.err.println("[PORTFOLIO] realized PnL of " + pair + " not booked: quantity unknown (older saved state) — net may be slightly off this cycle"); return; }
//         String ex = classifyExit(t, lastPx);
//         double exitPx = ex.startsWith("TP") ? t.tp : ex.startsWith("SL") ? t.sl : lastPx;
//         if (!(exitPx > 0)) { System.err.println("[PORTFOLIO] realized PnL of " + pair + " not booked: exit price unknown"); return; }
//         double pnl = estimateRealizedNet(t.isLong, t.qty, t.entry, exitPx, PARAMS);
//         portfolioAddRealized(pnl);
//         System.out.printf("  [PORTFOLIO] %s closed on its own (%s) — realized(est) %.4f USDT booked into cycle #%d (cycle realized now %.4f)%n", pair, ex, pnl, pState.cycleId, pState.realizedNet);
//         if (!selfTestMode) savePortfolioState();
//     }

//     // =========================================================================
//     // LIVE / PAPER adapters
//     // =========================================================================
//     private static Map<String, PosRec> toRecs(Map<String, JSONObject> m) {
//         if (m == null) return null;
//         Map<String, PosRec> out = new LinkedHashMap<>();
//         for (Map.Entry<String, JSONObject> en : m.entrySet()) {
//             JSONObject p = en.getValue();
//             PosRec r = new PosRec();
//             r.pair = en.getKey();
//             r.id = p.optString("id", "");
//             r.q = p.optDouble("active_pos", 0);
//             r.avg = p.optDouble("avg_price", 0);
//             double lev = p.optDouble("leverage", 0);
//             r.lev = lev > 0 ? lev : LEVERAGE;        // the exchange value when present; the bot's configured leverage otherwise
//             out.put(r.pair, r);
//         }
//         return out;
//     }

//     static final PortfolioOps LIVE_OPS = new PortfolioOps() {
//         public Map<String, PosRec> fetchOpen()      { return toRecs(getOpenPositionsMapCached(POSITIONS_CACHE_MS)); }
//         public Map<String, PosRec> fetchOpenFresh() { invalidatePositionsCache(); return toRecs(getOpenPositionsMapCached(0)); }
//         public double price(String pair)            { return getLivePrice(pair); }
//         public boolean close(PosRec p, String why)  { boolean ok = closePosition(p.id); invalidatePositionsCache(); return ok; }
//         public void sleep(long ms)                  { sleepMs(ms); }
//         public long now()                           { return System.currentTimeMillis(); }
//         public void closedAccounting(Set<String> openPairs) { handleClosedTrades(openPairs, CoinDCXFuturesTrader8C_BUY_SELL_NEW_LOGIC_THREE::getLivePrice); }
//         public boolean persist()                    { return true; }
//     };

//     static final PortfolioOps PAPER_OPS = new PortfolioOps() {
//         private Map<String, PosRec> snap() {
//             Map<String, PosRec> out = new LinkedHashMap<>();
//             for (Map.Entry<String, PaperTrade> en : paperTrades.entrySet()) {
//                 PaperTrade t = en.getValue();
//                 if (t.qty <= 0) continue;
//                 PosRec r = new PosRec();
//                 r.pair = en.getKey(); r.id = en.getKey(); r.q = t.isLong ? t.qty : -t.qty; r.avg = t.entry; r.lev = LEVERAGE;
//                 out.put(r.pair, r);
//             }
//             return out;
//         }
//         public Map<String, PosRec> fetchOpen()      { return snap(); }
//         public Map<String, PosRec> fetchOpenFresh() { return snap(); }
//         public double price(String pair)            { return getLivePrice(pair); }
//         public boolean close(PosRec p, String why) {
//             double px = getLivePrice(p.pair);
//             PaperTrade t = paperTrades.get(p.pair);
//             if (t == null) return true;
//             if (!(px > 0)) return false;
//             logPaperClosed(p.pair, t, why, px);
//             paperTrades.remove(p.pair);
//             return true;
//         }
//         public void sleep(long ms)                  { }
//         public long now()                           { return System.currentTimeMillis(); }
//         public void closedAccounting(Set<String> openPairs) { }
//         public boolean persist()                    { return false; }
//     };

//     // Entry point from runCycle and from inside the scan loop (rate limited; fastest of the two intervals wins)
//     private static void runPortfolioProfitCheck() {
//         if (!PARAMS.anyEnabled()) return;
//         long now = System.currentTimeMillis();
//         long gap = PARAMS.trEnabled ? Math.min(PORTFOLIO_CHECK_INTERVAL_MS, PORTFOLIO_TRAILING_CHECK_INTERVAL_MS) : PORTFOLIO_CHECK_INTERVAL_MS;
//         if (now - lastPortfolioCheckMs < gap) return;
//         lastPortfolioCheckMs = now;
//         portfolioPass(currentOps());
//     }

//     private static boolean portfolioLiquidating() { return PARAMS.anyEnabled() && pState.liquidating; }

//     // Keeps retrying a running liquidation until the exchange confirms everything is closed (bounded per call; the main loop calls again).
//     private static void drainLiquidation(PortfolioOps ops) {
//         long until = System.currentTimeMillis() + PORTFOLIO_LIQUIDATION_LOOP_MAX_MS;
//         while (pState.liquidating && System.currentTimeMillis() < until) {
//             ops.sleep(Math.max(1000L, PORTFOLIO_CLOSE_RETRY_INTERVAL_MS));
//             portfolioPass(ops);
//         }
//     }

//     // Capital-based sanity warnings for the configured numbers
//     static void validatePortfolioConfig() {
//         if (!"TOTAL_ACCOUNT_CAPITAL".equals(PORTFOLIO_TARGET_BASIS) && !"TOTAL_DEPLOYED_MARGIN".equals(PORTFOLIO_TARGET_BASIS))
//             throw new IllegalStateException("PORTFOLIO_TARGET_BASIS must be TOTAL_ACCOUNT_CAPITAL or TOTAL_DEPLOYED_MARGIN, got: " + PORTFOLIO_TARGET_BASIS);
//         if (PORTFOLIO_BREAKEVEN_ENABLED && PORTFOLIO_BREAKEVEN_LOCK_PERCENT >= PORTFOLIO_BREAKEVEN_TRIGGER_PERCENT)
//             System.err.println("WARNING: PORTFOLIO_BREAKEVEN_LOCK_PERCENT should be below PORTFOLIO_BREAKEVEN_TRIGGER_PERCENT");
//         if (PORTFOLIO_PROFIT_TARGET_ENABLED && PORTFOLIO_TRAILING_ENABLED && PORTFOLIO_TRAILING_ACTIVATION_PERCENT >= PORTFOLIO_PROFIT_TARGET_PERCENT)
//             System.err.println("WARNING: PORTFOLIO_TRAILING_ACTIVATION_PERCENT should be below PORTFOLIO_PROFIT_TARGET_PERCENT (the hard target would always fire first)");
//         if (PORTFOLIO_BREAKEVEN_ENABLED && PORTFOLIO_TRAILING_ENABLED && PORTFOLIO_BREAKEVEN_TRIGGER_PERCENT > PORTFOLIO_TRAILING_ACTIVATION_PERCENT)
//             System.err.println("WARNING: PORTFOLIO_BREAKEVEN_TRIGGER_PERCENT is above PORTFOLIO_TRAILING_ACTIVATION_PERCENT");
//         double perMarginInr = FIXED_MARGIN / LEVERAGE;
//         System.out.printf("[PORTFOLIO] capital INR %.0f (= %.2f USDT @ %.2f) | one position locks ~INR %.0f margin | max %d positions / %.0f%% deployed (≈ INR %.0f)%n",
//                 PORTFOLIO_ACCOUNT_CAPITAL_INR, PORTFOLIO_ACCOUNT_CAPITAL_INR / USDT_INR_RATE, USDT_INR_RATE, perMarginInr,
//                 PORTFOLIO_MAX_OPEN_POSITIONS, PORTFOLIO_MAX_DEPLOYED_MARGIN_PERCENT, PORTFOLIO_ACCOUNT_CAPITAL_INR * PORTFOLIO_MAX_DEPLOYED_MARGIN_PERCENT / 100.0);
//         System.out.printf("[PORTFOLIO] target %.2f%% = INR %.0f | BE trigger %.2f%% lock %.2f%% | trail activation %.2f%% giveback %.0f%% of peak | max loss %.2f%% = INR %.0f%n",
//                 PORTFOLIO_PROFIT_TARGET_PERCENT, PORTFOLIO_ACCOUNT_CAPITAL_INR * PORTFOLIO_PROFIT_TARGET_PERCENT / 100.0,
//                 PORTFOLIO_BREAKEVEN_TRIGGER_PERCENT, PORTFOLIO_BREAKEVEN_LOCK_PERCENT, PORTFOLIO_TRAILING_ACTIVATION_PERCENT,
//                 PORTFOLIO_TRAILING_GIVEBACK_PERCENT, PORTFOLIO_MAX_LOSS_PERCENT, PORTFOLIO_ACCOUNT_CAPITAL_INR * PORTFOLIO_MAX_LOSS_PERCENT / 100.0);
//     }

//     private static void trailOne(String pair, JSONObject pos) {
//         double signedQty = pos.optDouble("active_pos", 0);
//         double avg = pos.optDouble("avg_price", 0);
//         if (Math.abs(signedQty) <= 0 || avg <= 0) return;           // closed / not filled yet -> nothing to trail
//         double px = getLivePrice(pair);
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

//         if (Math.abs(t.qty - Math.abs(signedQty)) > 1e-12) { t.qty = Math.abs(signedQty); dirty = true; }   // quantity for the portfolio realized-PnL estimate

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
//         if (portfolioLiquidating()) return;     // positions are being closed: no TP/SL restoration meanwhile
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
//             JSONArray arr = parsePositionsResponse(resp);
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
//         selfTestMode = true;                                 // no CSV logs, state files redirected to a temp directory
//         File tmpDir = new File(System.getProperty("java.io.tmpdir"), "dcx-selftest-" + System.nanoTime());
//         tmpDir.mkdirs();
//         tradeStateFile = new File(tmpDir, "trade_state_test.json").getPath();
//         portfolioStateFile = new File(tmpDir, "portfolio_state_test.json").getPath();
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

//         // (entry-logic and portfolio tests are in selfTestEntry() / selfTestPortfolio(), called at the end)

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

//         System.out.println("[Test] Portfolio profit target (combined PnL / combined margin)");
//         double pLong = positionPnl(10, 100.0, 100.6), pShort = positionPnl(-10, 100.0, 99.4);
//         check("LONG and SHORT both profit when price moves in their favour", pLong > 0 && pShort > 0 && Math.abs(pLong - pShort) < 1e-9);
//         check("SHORT loses when price rises, LONG loses when price falls", positionPnl(-10, 100.0, 101.0) < 0 && positionPnl(10, 100.0, 99.0) < 0);
//         check("margin = notional / leverage (SHORT uses |qty|)", Math.abs(positionMargin(-10, 100.0, 5) - 200.0) < 1e-9 && positionMargin(10, 100.0, 0) == 0);
//         check("0.6% price move at 5x = 3% of margin -> target reached", portfolioTargetReached(pLong, positionMargin(10, 100.0, 5), 3.0));
//         check("0.5% price move at 5x -> target NOT reached", !portfolioTargetReached(positionPnl(10, 100.0, 100.5), positionMargin(10, 100.0, 5), 3.0));
//         // 4 positions, margin 200 each (800 total): +20 +15 -8 -3 = +24 = exactly 3.0% of 800
//         double comb = 20 + 15 - 8 - 3;
//         check("combined example: +24 on 800 margin = 3.0% -> reached", portfolioTargetReached(comb, 800.0, 3.0));
//         check("combined example: +23 on 800 margin = 2.875% -> not reached", !portfolioTargetReached(23.0, 800.0, 3.0));
//         check("zero margin never triggers", !portfolioTargetReached(5.0, 0.0, 3.0));
//         check("losing basket never triggers", !portfolioTargetReached(-10.0, 800.0, 3.0));

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

//         selfTestEntry();
//         selfTestPortfolio();

//         System.out.println("\nSelf-test: " + (testsRun - testsFailed) + "/" + testsRun + " passed");
//         return testsFailed == 0;
//     }

    
//     // =========================================================================
//     // SELF-TESTS: trend-following entry logic
//     // =========================================================================
//     // scenario 0 = plain trend, 1 = pullback + confirmation candle at the end, 2 = fresh swing breakout at the end.
//     // The last CLOSED 5M candle starts at cur5 - 5M. Volume: 1000 per candle, `volLast` on the last candle.
//     private static Series5 entrySeries(long cur5, int scenario, double volLast, double slope) {
//         int N = 1700;
//         double[] c = new double[N], o = new double[N], h = new double[N], l = new double[N], v = new double[N];
//         for (int i = 0; i < N; i++) c[i] = 100 + slope * i + 0.10 * Math.sin(i * 0.7);
//         int B = N - 12;                       // first patched candle
//         double T = c[B - 1];
//         if (scenario == 1) {                  // shallow dip to the 5M EMA21 zone, then a confirmed bullish close
//             double[] p = {-0.04, -0.08, -0.12, -0.15, -0.17, -0.18, -0.17, -0.16, -0.15, -0.14, -0.14, 0.10};
//             for (int k = 0; k < 12; k++) c[B + k] = T + p[k];
//         } else if (scenario == 2) {           // swing high at B+1, lower highs after it, last candle closes above it
//             double[] p = {0.02, 0.18, 0.10, 0.04, 0.03, 0.04, 0.06, 0.07, 0.08, 0.09, 0.10, 0.42};
//             for (int k = 0; k < 12; k++) c[B + k] = T + p[k];
//         }
//         Series5 s = new Series5(N);
//         long last = cur5 - FIVE_MIN_MS;
//         for (int i = 0; i < N; i++) {
//             o[i] = i == 0 ? c[0] : c[i - 1];
//             h[i] = Math.max(o[i], c[i]) + 0.10 + 0.002 * (i % 5);
//             l[i] = Math.min(o[i], c[i]) - 0.10 - 0.002 * (i % 3);
//             v[i] = 1000 + 30 * Math.sin(i);
//             s.t[i] = last - (long) (N - 1 - i) * FIVE_MIN_MS;
//             s.o[i] = o[i]; s.h[i] = h[i]; s.l[i] = l[i]; s.c[i] = c[i]; s.v[i] = v[i];
//         }
//         if (scenario == 2) s.h[B + 1] += 0.06;           // clear swing-high pivot (strictly higher than its neighbours)
//         s.v[N - 1] = volLast;
//         return s;
//     }

//     // exact mirror around `axis`: the same market upside down (volume unchanged)
//     private static Series5 mirrorSeries(Series5 a, double axis) {
//         Series5 m = new Series5(a.n);
//         for (int i = 0; i < a.n; i++) {
//             m.t[i] = a.t[i]; m.v[i] = a.v[i];
//             m.o[i] = 2 * axis - a.o[i]; m.c[i] = 2 * axis - a.c[i];
//             m.h[i] = 2 * axis - a.l[i]; m.l[i] = 2 * axis - a.h[i];
//         }
//         return m;
//     }

//     private static void why(Eval e) { if (!e.signal.equals("BUY") && !e.signal.equals("SELL")) System.out.println("      -> " + e.code + ": " + e.reason); }

//     static void selfTestEntry() {
//         long b12 = (1_760_000_000_000L / (12 * HOUR_MS)) * (12 * HOUR_MS);
//         long cur5 = b12 + 235 * MIN_MS;
//         long now = cur5 + 30_000L;

//         System.out.println("[Test] ADX / EMA series helpers");
//         double[] hiT = new double[80], loT = new double[80], clT = new double[80], hiF = new double[80], loF = new double[80], clF = new double[80];
//         for (int i = 0; i < 80; i++) { clT[i] = 100 + i; hiT[i] = clT[i] + 0.5; loT[i] = clT[i] - 0.5; clF[i] = 100 + 0.1 * Math.sin(i); hiF[i] = clF[i] + 0.5; loF[i] = clF[i] - 0.5; }
//         double[] adxT = calcADX(hiT, loT, clT, 14), adxF = calcADX(hiF, loF, clF, 14);
//         check("ADX is high with +DI > -DI in a steady rise", adxT != null && adxT[0] > 40 && adxT[1] > adxT[2]);
//         check("ADX is low in a sideways market", adxF != null && adxF[0] < 20);
//         check("ADX needs enough bars", calcADX(hiT, loT, clT, 50) == null);
//         double[] es = emaSeries(new double[]{1, 2, 3, 4, 5, 6}, 3);
//         check("emaSeries: NaN before the first period, SMA seed, then recursive", Double.isNaN(es[1]) && Math.abs(es[2] - 2.0) < 1e-9 && Math.abs(es[3] - 3.0) < 1e-9);

//         System.out.println("[Test] LONG entry: pullback / retest + confirmed bullish close");
//         Series5 sPull = entrySeries(cur5, 1, 3000, 0.02);
//         double pxL = sPull.c[sPull.n - 1] + 0.02;
//         Eval eL = evaluateFull(sPull, pxL, 5_000, now, 1);
//         why(eL);
//         check("4H + 1H Supertrend GREEN, 15M EMA9 > EMA21, pullback confirmed, volume, BTC bullish -> BUY", eL.signal.equals("BUY"));
//         check("trigger is PULLBACK or BREAKOUT, score 100 with every component met", (eL.trigger.equals("PULLBACK") || eL.trigger.equals("BREAKOUT")) && eL.score == 100);
//         check("4H and 1H Supertrend are GREEN, ADX >= threshold", eL.dev[3] != null && eL.dev[3].stBull && eL.dev[0].stBull && eL.adx >= ADX_MIN);
//         check("signal candle id = last CLOSED 5M candle", eL.candleOpen == cur5 - FIVE_MIN_MS);

//         System.out.println("[Test] SHORT entry is the exact mirror of LONG");
//         double axis = 134.0;
//         Series5 sMir = mirrorSeries(sPull, axis);
//         Eval eS = evaluateFull(sMir, 2 * axis - pxL, 5_000, now, -1);
//         why(eS);
//         check("mirrored market -> SELL", eS.signal.equals("SELL"));
//         check("same trigger, same score, same ADX in both directions", eS.trigger.equals(eL.trigger) && eS.score == eL.score && Math.abs(eS.adx - eL.adx) < 1e-6);
//         check("1H and 4H Supertrend RED, EMA9 < EMA21", eS.dev[3] != null && !eS.dev[3].stBull && !eS.dev[0].stBull && eS.ema9 < eS.ema21);
//         check("symmetric ATR / EMA values", Math.abs(eS.atr5 - eL.atr5) < 1e-9 && Math.abs((axis - eS.ema9) - (eL.ema9 - axis)) < 1e-6);

//         System.out.println("[Test] No countertrend entries (the old ST_CONTRARIAN behaviour is gone)");
//         Eval st1Up = evaluateStage1(sPull, pxL, now), st1Dn = evaluateStage1(sMir, 2 * axis - pxL, now);
//         check("uptrend market can only produce a LONG candidate", st1Up.dir == 1);
//         check("downtrend market can only produce a SHORT candidate", st1Dn.dir == -1);
//         Eval wrongWay = evaluateFull(sMir, 2 * axis - pxL, 5_000, now, 1);
//         check("a downtrend never yields a BUY", !wrongWay.signal.equals("BUY"));

//         System.out.println("[Test] 1H and 4H must agree (mixed higher timeframes -> no candidate)");
//         int N = 1700;
//         double[] cm = new double[N];
//         for (int i = 0; i < N; i++) cm[i] = i < N - 36 ? 150 - 0.03 * i : 150 - 0.03 * (N - 36) + 0.10 * (i - (N - 36));
//         Series5 sMixed = pathSeries(cm, cur5 - FIVE_MIN_MS, 0.05);
//         Eval mixed = evaluateStage1(sMixed, cm[N - 1] + 0.1, now);
//         check("long decline then a sharp 3h rally: 1H turns GREEN but 4H is still RED -> no candidate", mixed.dir == 0 && mixed.code.equals("stage1")
//                 && mixed.dev[0] != null && mixed.dev[3] != null && mixed.dev[0].stBull != mixed.dev[3].stBull);

//         System.out.println("[Test] Fresh swing-high breakout (closed candle) with volume");
//         Series5 sBrk = entrySeries(cur5, 2, 3000, 0.02);
//         double pxB = sBrk.c[sBrk.n - 1] + 0.02;
//         Eval eB = evaluateFull(sBrk, pxB, 5_000, now, 1);
//         why(eB);
//         check("breakout + volume -> BUY", eB.signal.equals("BUY"));
//         check("trigger = BREAKOUT, level is the confirmed swing high", eB.trigger.equals("BREAKOUT") && eB.brkLevel > 0 && eB.brkLevel < sBrk.c[sBrk.n - 1]);
//         Series5 sBrkLowVol = entrySeries(cur5, 2, 900, 0.02);
//         Eval eBl = evaluateFull(sBrkLowVol, sBrkLowVol.c[sBrkLowVol.n - 1] + 0.02, 5_000, now, 1);
//         check("breakout WITHOUT volume is rejected (weak breakout)", eBl.signal.equals("NO TRADE") && eBl.code.equals("trigger"));
//         Series5 sMirB = mirrorSeries(sBrk, axis);
//         Eval eBs = evaluateFull(sMirB, 2 * axis - pxB, 5_000, now, -1);
//         check("mirrored swing-low breakdown -> SELL with the same score", eBs.signal.equals("SELL") && eBs.trigger.equals("BREAKOUT") && eBs.score == eB.score);

//         System.out.println("[Test] Pullback with low volume still qualifies (volume is scored, not mandatory, for pullbacks)");
//         Series5 sPullLow = entrySeries(cur5, 1, 900, 0.02);
//         Eval ePl = evaluateFull(sPullLow, sPullLow.c[sPullLow.n - 1] + 0.02, 5_000, now, 1);
//         why(ePl);
//         check("low volume costs the volume points (score 90) but the setup still passes", ePl.signal.equals("BUY") && ePl.score == 90 && ePl.trigger.equals("PULLBACK"));

//         System.out.println("[Test] BTC market filter");
//         Eval bOpp = evaluateFull(sPull, pxL, 5_000, now, -1);
//         check("BTC clearly bearish blocks an altcoin LONG (hard block)", bOpp.signal.equals("NO TRADE") && bOpp.code.equals("btcOpposed"));
//         Eval bNeu = evaluateFull(sPull, pxL, 5_000, now, 0);
//         check("BTC mixed -> half points, still a signal (score 95)", bNeu.signal.equals("BUY") && bNeu.score == 95);
//         Eval bNa = evaluateFull(sPull, pxL, 5_000, now, BTC_UNAVAILABLE);
//         check("BTC unavailable -> no BTC points (score 90), not a block", bNa.signal.equals("BUY") && bNa.score == 90);
//         Eval bSelf = evaluateFull(sPull, pxL, 5_000, now, BTC_NA);
//         check("the symbol is BTC itself -> filter not applicable", bSelf.signal.equals("BUY") && bSelf.score == 100);

//         System.out.println("[Test] Mandatory safety conditions are never bypassed by the score");
//         double lastC = sPull.c[sPull.n - 1];
//         double atr5 = eL.atr5;
//         Eval chased = evaluateFull(sPull, lastC + ENTRY_MAX_CHASE_ATR5 * atr5 * 1.5, 5_000, now, 1);
//         check("price already ran > 0.6 ATR past the confirmation close -> chased", chased.code.equals("chased") && chased.signal.equals("NO TRADE"));
//         Eval spikeP = evaluateFull(sPull, lastC * (1 + ENTRY_SPIKE_JUMP_PCT / 100.0 * 1.5), 5_000, now, 1);
//         check("abnormal price jump from the last close -> spike", spikeP.code.equals("spike"));
//         Eval faded = evaluateFull(sPull, sPull.o[sPull.n - 1] - 0.01, 5_000, now, 1);
//         check("price fell back through the confirmation candle -> no entry", faded.signal.equals("NO TRADE"));
//         Eval stale = evaluateFull(sPull, pxL, ENTRY_MAX_PRICE_AGE_MS + 1000, now, 1);
//         check("stale last trade -> rejected", stale.code.equals("stalePrice"));
//         Eval late = evaluateFull(sPull, pxL, 5_000, now + ENTRY_MAX_CANDLE_AGE_MS + 60_000L, 1);
//         check("signal candle closed too long ago (late entry) -> rejected", late.signal.equals("NO TRADE"));
//         Series5 steep = entrySeries(cur5, 1, 3000, 0.14);
//         Eval stE = evaluateFull(steep, steep.c[steep.n - 1] + 0.02, 5_000, now, 1);
//         check("price far above the 15M EMA21 in ATR terms (parabolic) is never bought", stE.signal.equals("NO TRADE"));
//         Eval unknownAge = evaluateFull(sPull, pxL, -1, now, 1);
//         check("unknown price age does not block", unknownAge.signal.equals("BUY"));
//         Series5 thin = entrySeries(cur5, 1, 3000, 0.02);
//         for (int i = 0; i < thin.n; i++) thin.v[i] = thin.v[i] * 1e-4;
//         thin.v[thin.n - 1] = 0.3;
//         Eval thinE = evaluateFull(thin, pxL, 5_000, now, 1);
//         check("illiquid symbol (tiny traded notional) -> rejected", thinE.code.equals("illiquid"));
//         Series5 spk = entrySeries(cur5, 1, 3000, 0.02);
//         spk.h[spk.n - 1] = spk.c[spk.n - 1] + 3.0;             // huge upper wick on the signal candle
//         Eval spkE = evaluateFull(spk, pxL, 5_000, now, 1);
//         check("abnormal range on the signal candle -> spike", spkE.code.equals("spike"));
//         Series5 gap = entrySeries(cur5, 1, 3000, 0.02);
//         for (int i = 0; i < gap.n; i++) if (i >= gap.n - 20 && i < gap.n - 10) gap.t[i] += 1_000L;   // timestamps no longer contiguous
//         check("gap inside the recent closed candles -> rejected, never guessed", evaluateStage1(gap, pxL, now).dir == 0);
//         Series5 noVol = entrySeries(cur5, 1, 3000, 0.02);
//         Arrays.fill(noVol.v, -1);
//         Eval nv = evaluateFull(noVol, pxL, 5_000, now, 1);
//         check("exchange without volume data: pullback still works, no volume points (score 90)", nv.signal.equals("BUY") && nv.score == 90);

//         System.out.println("[Test] No repeated signals from the same closed candle");
//         Map<String, Long> reg = new HashMap<>();
//         check("first signal accepted", registerSignal(reg, "B-BTC_USDT|LONG", eL.candleOpen));
//         check("same closed 5M candle, same direction rejected", !registerSignal(reg, "B-BTC_USDT|LONG", eL.candleOpen));
//         check("next closed 5M candle accepted", registerSignal(reg, "B-BTC_USDT|LONG", eL.candleOpen + FIVE_MIN_MS));
//         check("other direction tracked separately", registerSignal(reg, "B-BTC_USDT|SHORT", eL.candleOpen));

//         System.out.println("[Test] Signals use only closed candles (the forming candle cannot change the decision)");
//         Series5 withForming = new Series5(sPull.n + 1);
//         System.arraycopy(sPull.t, 0, withForming.t, 0, sPull.n); System.arraycopy(sPull.o, 0, withForming.o, 0, sPull.n);
//         System.arraycopy(sPull.h, 0, withForming.h, 0, sPull.n); System.arraycopy(sPull.l, 0, withForming.l, 0, sPull.n);
//         System.arraycopy(sPull.c, 0, withForming.c, 0, sPull.n); System.arraycopy(sPull.v, 0, withForming.v, 0, sPull.n);
//         int fi = sPull.n;
//         withForming.t[fi] = cur5; withForming.o[fi] = lastC; withForming.c[fi] = lastC - 5; withForming.h[fi] = lastC + 1; withForming.l[fi] = lastC - 6; withForming.v[fi] = 99999;
//         Eval f2 = evaluateFull(withForming, pxL, 5_000, now, 1);
//         check("a wild forming candle in the data changes nothing", f2.signal.equals("BUY") && f2.score == eL.score && f2.candleOpen == eL.candleOpen);

//         System.out.println("[Test] Order-book spread / depth check + positions-response parsing");
//         BookInfo bk = parseBook("{\"bids\":{\"100.0\":\"50\",\"99.9\":\"40\"},\"asks\":{\"100.05\":\"60\",\"100.2\":\"10\"}}");
//         check("order book (price->qty maps) parsed", bk != null && Math.abs(bk.bid - 100.0) < 1e-9 && Math.abs(bk.ask - 100.05) < 1e-9);
//         check("tight spread + deep book passes", bk != null && bookReason(bk, true, 0.15, 200) == null);
//         check("wide spread rejected", bk != null && bookReason(bk, true, 0.01, 200) != null);
//         check("thin book rejected", bk != null && bookReason(bk, true, 0.15, 1e9) != null);
//         BookInfo bk2 = parseBook("{\"bids\":[{\"price\":\"10\",\"quantity\":\"5\"}],\"asks\":[{\"price\":\"10.01\",\"quantity\":\"5\"}]}");
//         check("order book (array form) parsed", bk2 != null && bk2.ask > bk2.bid);
//         check("unparseable book -> null (treated as unavailable, never as OK-by-accident)", parseBook("<html>") == null && parseBook("{}") == null);
//         boolean threw = false;
//         try { parsePositionsResponse("{\"message\":\"rate limited\",\"code\":429}"); } catch (Exception ex) { threw = true; }
//         check("error body is an API FAILURE, not 'no positions'", threw);
//         check("empty array / single object / {} parse", parsePositionsResponse("[]").length() == 0
//                 && parsePositionsResponse("{\"pair\":\"B-X_USDT\",\"active_pos\":1}").length() == 1 && parsePositionsResponse("{}").length() == 0);
//     }

//     // =========================================================================
//     // SELF-TESTS: portfolio management V2 (pure logic + liquidation against a scripted fake exchange)
//     // =========================================================================
//     static class FakeOps implements PortfolioOps {
//         final Map<String, PosRec> pos = new LinkedHashMap<>();
//         final Map<String, Double> prices = new HashMap<>();
//         boolean apiDown = false, persist = false;
//         final Set<String> failFirstClose = new HashSet<>();      // first close request for these pairs is rejected
//         final Set<String> ignoreFirstClose = new HashSet<>();    // first close request is "accepted" but the position stays open (partial)
//         final Map<String, Integer> closeTries = new HashMap<>();
//         int closeCalls = 0, ordersPlaced = 0;
//         long clock = 1_800_000_000_000L;

//         PosRec add(String pair, double q, double avg, double px) {
//             PosRec r = new PosRec(); r.pair = pair; r.id = "id-" + pair; r.q = q; r.avg = avg; r.lev = 5;
//             pos.put(pair, r); prices.put(pair, px);
//             return r;
//         }
//         public Map<String, PosRec> fetchOpen()      { return apiDown ? null : new LinkedHashMap<>(pos); }
//         public Map<String, PosRec> fetchOpenFresh() { return apiDown ? null : new LinkedHashMap<>(pos); }
//         public double price(String pair)            { return prices.getOrDefault(pair, 0.0); }
//         public boolean close(PosRec p, String why) {
//             closeCalls++;
//             int n = closeTries.merge(p.pair, 1, Integer::sum);
//             if (failFirstClose.contains(p.pair) && n == 1) return false;
//             if (ignoreFirstClose.contains(p.pair) && n == 1) return true;
//             pos.remove(p.pair);
//             return true;
//         }
//         public void sleep(long ms)                  { clock += ms; }
//         public long now()                           { return clock; }
//         public void closedAccounting(Set<String> openPairs) { handleClosedTrades(openPairs, pr -> prices.getOrDefault(pr, 0.0)); }
//         public boolean persist()                    { return persist; }
//     }

//     private static void resetPortfolioTestState() {
//         pState = new PortfolioState();
//         PARAMS = new PortfolioParams();
//         tradeState.clear();
//         portfolioClosedPairs.clear(); closeRequestedAt.clear(); closeAttempts.clear();
//         portfolioBusy = false; portfolioLastLogMs = 0; portfolioIncompleteLogMs = 0;
//     }

//     // price at which ONE long position (q=1 @ 20 USDT) has the given NET pnl (fees on both sides included)
//     private static double pxForNet(double net, PortfolioParams p) {
//         double f = p.feeFactor();
//         return (net + 20.0 + 20.0 * f) / (1.0 - f);
//     }

//     private static PortfolioMetrics metricsFor(PortfolioParams p, double net, double marginUsdt) {
//         PortfolioMetrics m = new PortfolioMetrics();
//         m.net = net; m.margin = marginUsdt; m.n = 1;
//         applyThresholds(m, p);
//         return m;
//     }

//     static void selfTestPortfolio() {
//         PortfolioParams P = new PortfolioParams();
//         final double CAP = P.capitalInr / USDT_INR_RATE;       // 5000 INR -> 49.02 USDT

//         System.out.println("[Test] Portfolio target examples: INR 5000 capital -> 3% / 4% / 5% = INR 150 / 200 / 250");
//         for (double pct : new double[]{3.0, 4.0, 5.0}) {
//             PortfolioParams q = new PortfolioParams(); q.targetPct = pct; q.capitalInr = 5000.0;
//             PortfolioMetrics m = metricsFor(q, 0, 40.0);
//             check(String.format("%.0f%% of INR 5000 = INR %.0f (leveraged notional and margin do not change it)", pct, 5000 * pct / 100), Math.abs(inr(m.targetAmt) - 5000 * pct / 100) < 1e-6);
//         }
//         PortfolioParams dm = new PortfolioParams(); dm.basis = "TOTAL_DEPLOYED_MARGIN"; dm.targetPct = 4.0;
//         PortfolioMetrics mdm = metricsFor(dm, 0, 40.0);
//         check("TOTAL_DEPLOYED_MARGIN basis: 4% of 40 USDT deployed margin = 1.6 USDT", Math.abs(mdm.targetAmt - 1.6) < 1e-9 && Math.abs(mdm.basis - 40.0) < 1e-9);
//         check("TOTAL_ACCOUNT_CAPITAL is the default basis", PORTFOLIO_TARGET_BASIS.equals("TOTAL_ACCOUNT_CAPITAL") && Math.abs(metricsFor(P, 0, 999).basis - CAP) < 1e-9);

//         System.out.println("[Test] Net PnL: LONG + SHORT mixed, per-position leverage, fees");
//         List<PosView> mix = new ArrayList<>();
//         PosView a = new PosView(); a.pair = "A"; a.q = 0.2;  a.avg = 100; a.px = 102;  a.lev = 5;  mix.add(a);   // long  +0.40
//         PosView b = new PosView(); b.pair = "B"; b.q = -0.4; b.avg = 50;  b.px = 48.5; b.lev = 10; mix.add(b);   // short +0.60
//         PosView c = new PosView(); c.pair = "C"; c.q = 1;    c.avg = 20;  c.px = 19.5; c.lev = 5;  mix.add(c);   // long  -0.50
//         PortfolioMetrics mm = computeMetrics(mix, 0.25, P);
//         check("gross PnL: LONG and SHORT signs are correct", Math.abs(mm.gross - (0.40 + 0.60 - 0.50)) < 1e-9);
//         check("margin uses each position's own leverage (2 + 4... = 4 + 2 + 4)", Math.abs(mm.margin - (20 / 5.0 + 20 / 10.0 + 20 / 5.0)) < 1e-9);
//         double fees = (20 * 0.0005 * 1.18) * 3 + (0.2 * 102 + 0.4 * 48.5 + 1 * 19.5) * 0.0005 * 1.18;
//         check("net = gross - entry fees - exit fees + realized of the cycle", Math.abs(mm.net - (mm.gross - fees + 0.25)) < 1e-9 && mm.net < mm.gross + 0.25);
//         check("one negative position does not make the portfolio negative", mm.net > 0);

//         System.out.println("[Test] Hard take-profit");
//         PortfolioState st = new PortfolioState();
//         PortfolioMetrics m1 = metricsFor(P, 1.0, 12.0);
//         PortfolioDecision d1 = decidePortfolio(st, m1, P);
//         check("below the target: no exit", d1.exitReason == null);
//         PortfolioDecision d2 = decidePortfolio(st, metricsFor(P, CAP * 0.03 + 1e-6, 12.0), P);
//         check("net >= 3% of capital -> PORTFOLIO_HARD_TP immediately", EXIT_HARD_TP.equals(d2.exitReason));
//         PortfolioState st2 = new PortfolioState(); st2.beActive = true; st2.trailActive = true; st2.floorActive = true; st2.floor = CAP * 0.02; st2.peakNet = CAP * 0.025;
//         PortfolioDecision d3 = decidePortfolio(st2, metricsFor(P, CAP * 0.031, 12.0), P);
//         check("hard target wins over an active trailing floor", EXIT_HARD_TP.equals(d3.exitReason));
//         PortfolioParams off = new PortfolioParams(); off.targetEnabled = false; off.beEnabled = false; off.trEnabled = false; off.maxLossEnabled = false;
//         check("everything disabled -> never an exit, anyEnabled() false", decidePortfolio(new PortfolioState(), metricsFor(off, 99, 12.0), off).exitReason == null && !off.anyEnabled());

//         System.out.println("[Test] Portfolio breakeven");
//         PortfolioState sb = new PortfolioState();
//         PortfolioDecision b0 = decidePortfolio(sb, metricsFor(P, CAP * 0.005, 12.0), P);
//         check("below the breakeven trigger: not active", !sb.beActive && !sb.floorActive && b0.exitReason == null);
//         PortfolioDecision b1 = decidePortfolio(sb, metricsFor(P, CAP * 0.011, 12.0), P);
//         check("net reaches 1.0% -> breakeven protection ACTIVE with floor = 0.3% of capital", sb.beActive && sb.floorActive && Math.abs(sb.floor - CAP * 0.003) < 1e-9 && b1.exitReason == null);
//         PortfolioDecision b2 = decidePortfolio(sb, metricsFor(P, CAP * 0.0031, 12.0), P);
//         check("net still above the floor (even if single positions are red) -> hold", b2.exitReason == null);
//         PortfolioDecision b3 = decidePortfolio(sb, metricsFor(P, CAP * 0.003, 12.0), P);
//         check("net falls back to the floor -> PORTFOLIO_BREAKEVEN", EXIT_BREAKEVEN.equals(b3.exitReason));
//         PortfolioParams lock0 = new PortfolioParams(); lock0.beLockPct = 0.0; lock0.trEnabled = false;
//         PortfolioState sl0 = new PortfolioState();
//         decidePortfolio(sl0, metricsFor(lock0, CAP * 0.011, 12.0), lock0);
//         check("lock 0% = true breakeven AFTER costs (floor 0 net, fees already inside net)", sl0.floorActive && Math.abs(sl0.floor) < 1e-12
//                 && EXIT_BREAKEVEN.equals(decidePortfolio(sl0, metricsFor(lock0, -1e-6, 12.0), lock0).exitReason));

//         System.out.println("[Test] Portfolio trailing profit floor");
//         PortfolioState sr = new PortfolioState();
//         decidePortfolio(sr, metricsFor(P, CAP * 0.015, 12.0), P);
//         check("before the activation level (1.8%) trailing is not active", !sr.trailActive);
//         decidePortfolio(sr, metricsFor(P, CAP * 0.020, 12.0), P);
//         double expect1 = Math.max(CAP * 0.003, CAP * 0.020 * 0.70);
//         check("2.0% peak -> trailing ACTIVE, floor = max(lock, peak - 30% of peak) = 1.4%", sr.trailActive && Math.abs(sr.floor - expect1) < 1e-9 && EXIT_TRAILING.equals(sr.floorSource));
//         double f1 = sr.floor;
//         decidePortfolio(sr, metricsFor(P, CAP * 0.025, 12.0), P);
//         check("peak rises to 2.5% -> floor rises to 1.75%", Math.abs(sr.floor - CAP * 0.025 * 0.70) < 1e-9 && sr.floor > f1);
//         double f2 = sr.floor;
//         PortfolioDecision r1 = decidePortfolio(sr, metricsFor(P, CAP * 0.022, 12.0), P);
//         check("profit falls back but stays above the floor -> hold, peak and floor never decrease", r1.exitReason == null && Math.abs(sr.peakNet - CAP * 0.025) < 1e-9 && Math.abs(sr.floor - f2) < 1e-12);
//         PortfolioDecision r2 = decidePortfolio(sr, metricsFor(P, f2 - 1e-6, 12.0), P);
//         check("net falls to the floor -> PORTFOLIO_TRAILING_SL", EXIT_TRAILING.equals(r2.exitReason));
//         PortfolioParams shrink = new PortfolioParams(); shrink.basis = "TOTAL_DEPLOYED_MARGIN"; shrink.trEnabled = true;
//         PortfolioState ss = new PortfolioState();
//         decidePortfolio(ss, metricsFor(shrink, 0.40, 20.0), shrink);      // 2% of 20 USDT margin, trailing active (1.8% = 0.36)
//         double fHigh = ss.floor;
//         decidePortfolio(ss, metricsFor(shrink, 0.40, 5.0), shrink);       // margin shrinks (positions closed): thresholds shrink
//         check("deployed-margin basis shrinking never lowers the protected floor", ss.floor >= fHigh - 1e-12);

//         System.out.println("[Test] Portfolio max loss");
//         PortfolioState sx = new PortfolioState();
//         PortfolioDecision x1 = decidePortfolio(sx, metricsFor(P, -CAP * 0.05, 12.0), P);
//         check("-5% (limit 6%): no exit", x1.exitReason == null);
//         PortfolioDecision x2 = decidePortfolio(sx, metricsFor(P, -CAP * 0.06, 12.0), P);
//         check("-6% of capital -> PORTFOLIO_RISK_LIMIT", EXIT_RISK_LIMIT.equals(x2.exitReason));

//         System.out.println("[Test] Entry gating (never an entry during liquidation / cooldown / limits)");
//         resetPortfolioTestState();
//         long T0 = 1_800_000_000_000L;
//         check("idle portfolio: entries allowed", portfolioEntryBlockedReason(T0, 2, 8.0, 4.0) == null);
//         check("max open positions", portfolioEntryBlockedReason(T0, PORTFOLIO_MAX_OPEN_POSITIONS, 8.0, 4.0) != null);
//         check("max deployed margin (70% of capital)", portfolioEntryBlockedReason(T0, 2, CAP * 0.70 - 1.0, 4.0) != null && portfolioEntryBlockedReason(T0, 2, CAP * 0.70 - 5.0, 4.0) == null);
//         pState.reentryUntilMs = T0 + 10_000; check("re-entry cooldown blocks", portfolioEntryBlockedReason(T0, 0, 0, 4.0) != null && portfolioEntryBlockedReason(T0 + 11_000, 0, 0, 4.0) == null);
//         pState.riskPauseUntilMs = T0 + 3_600_000; check("risk pause blocks", portfolioEntryBlockedReason(T0 + 11_000, 0, 0, 4.0) != null);
//         pState = new PortfolioState(); pState.liquidating = true; check("liquidation blocks", portfolioEntryBlockedReason(T0, 0, 0, 4.0) != null);

//         System.out.println("[Test] Close-all: hard target with mixed LONG/SHORT positions");
//         resetPortfolioTestState();
//         FakeOps fx = new FakeOps();
//         fx.add("B-A_USDT", 1.0, 20.0, 20.0);          // long
//         fx.add("B-B_USDT", -1.0, 20.0, 20.0);         // short
//         fx.add("B-C_USDT", 1.0, 20.0, 20.0);          // long
//         TradeInfo ta = new TradeInfo(); ta.isLong = true; ta.entry = 20; ta.sl = 17.6; ta.tp = 23; ta.initialSl = 17.6; ta.risk = 2.4; ta.qty = 1;
//         tradeState.put("B-A_USDT", ta);
//         portfolioPass(fx);
//         check("flat prices: cycle starts, nothing closed, entries not blocked by liquidation", pState.cycleStartMs > 0 && fx.closeCalls == 0 && !pState.liquidating);
//         fx.prices.put("B-A_USDT", 21.2); fx.prices.put("B-B_USDT", 18.6); fx.prices.put("B-C_USDT", 20.9);     // +1.2, +1.4, +0.9 = +3.5 gross
//         double slBefore = ta.sl, tpBefore = ta.tp;
//         portfolioPass(fx);
//         check("net >= INR 150 (3%) -> everything closed in one liquidation", fx.pos.isEmpty() && fx.closeCalls == 3);
//         check("exchange confirmed flat -> cycle reset, liquidation off, reason recorded", !pState.liquidating && pState.cycleStartMs == 0 && EXIT_HARD_TP.equals(pState.lastExitReason) && pState.lastFinalPnl > 1.4);
//         check("peak / breakeven / trailing state reset", pState.peakNet == 0 && !pState.beActive && !pState.trailActive && !pState.floorActive);
//         check("re-entry cooldown applied after the close-all", pState.reentryUntilMs > fx.clock && portfolioEntryBlockedReason(fx.clock, 0, 0, 4.0) != null);
//         check("per-position SL/TP values were not touched by the portfolio logic", ta.sl == slBefore && ta.tp == tpBefore);
//         check("no order of any kind is created by the portfolio code", fx.ordersPlaced == 0);

//         System.out.println("[Test] Partial liquidation, rejected close, duplicate-close protection, retry");
//         resetPortfolioTestState();
//         FakeOps fp = new FakeOps();
//         fp.add("B-P1_USDT", 1.0, 20.0, 21.5); fp.add("B-P2_USDT", 1.0, 20.0, 21.5); fp.add("B-P3_USDT", 1.0, 20.0, 21.5);
//         fp.ignoreFirstClose.add("B-P2_USDT");      // accepted but still open (partial / delayed)
//         fp.failFirstClose.add("B-P3_USDT");        // rejected by the exchange
//         portfolioPass(fp);
//         check("round 1: P1 closed, P2 + P3 still open -> liquidation stays ACTIVE", fp.pos.size() == 2 && pState.liquidating && EXIT_HARD_TP.equals(pState.liquidationReason));
//         check("entries stay blocked while any position of the liquidation is open", portfolioEntryBlockedReason(fp.clock, fp.pos.size(), 0, 4.0) != null);
//         int callsAfterRound1 = fp.closeCalls;
//         fp.clock += 1_000;
//         portfolioPass(fp);
//         check("a second pass within the retry interval sends NO duplicate close requests", fp.closeCalls == callsAfterRound1 && pState.liquidating);
//         fp.clock += PORTFOLIO_CLOSE_RETRY_INTERVAL_MS + 1000;
//         portfolioPass(fp);
//         check("after the retry interval the remaining positions are closed again and confirmed", fp.pos.isEmpty() && !pState.liquidating && pState.cycleStartMs == 0);
//         check("every position was closed by at most two requests, P1 exactly once", fp.closeTries.get("B-P1_USDT") == 1 && fp.closeTries.get("B-P2_USDT") == 2 && fp.closeTries.get("B-P3_USDT") == 2);
//         check("the liquidation reason labels the closed trades", pState.lastExitReason.equals(EXIT_HARD_TP));

//         System.out.println("[Test] API failures: never a fake zero, never a false 'all closed'");
//         resetPortfolioTestState();
//         FakeOps fa = new FakeOps();
//         fa.add("B-F1_USDT", 1.0, 20.0, 25.0); fa.add("B-F2_USDT", 1.0, 20.0, 0.0);      // F1 is hugely profitable, but F2 has NO price
//         portfolioPass(fa);
//         check("one price missing -> incomplete data: no exit decision at all", fa.closeCalls == 0 && !pState.liquidating && pState.peakNet == 0);
//         fa.prices.put("B-F2_USDT", 20.0);
//         fa.apiDown = true;
//         portfolioPass(fa);
//         check("positions API down -> no decision, no close", fa.closeCalls == 0 && !pState.liquidating);
//         fa.apiDown = false;
//         fa.failFirstClose.add("B-F1_USDT"); fa.failFirstClose.add("B-F2_USDT");
//         portfolioPass(fa);
//         check("target hit -> liquidation starts, both closes rejected -> stays active", pState.liquidating && fa.pos.size() == 2);
//         fa.apiDown = true; fa.clock += 7_000;
//         portfolioPass(fa);
//         check("API down during liquidation: cannot verify -> NOT finalized, no reset", pState.liquidating && pState.cycleStartMs > 0);
//         fa.apiDown = false; fa.clock += 7_000;
//         portfolioPass(fa);
//         check("API back: retried, confirmed flat, finalized", !pState.liquidating && fa.pos.isEmpty());

//         System.out.println("[Test] Max-loss exit pauses entries");
//         resetPortfolioTestState();
//         FakeOps fl = new FakeOps();
//         fl.add("B-L1_USDT", 1.0, 20.0, pxForNet(-3.2, new PortfolioParams()));
//         portfolioPass(fl);
//         check("net loss beyond 6% of capital -> PORTFOLIO_RISK_LIMIT, all closed", fl.pos.isEmpty() && EXIT_RISK_LIMIT.equals(pState.lastExitReason) && pState.lastFinalPnl < -2.9);
//         check("risk pause: no new entries for the configured time", portfolioEntryBlockedReason(fl.clock + 60_000, 0, 0, 4.0) != null && pState.riskPauseUntilMs > fl.clock + PORTFOLIO_RISK_PAUSE_MS - 10_000);

//         System.out.println("[Test] Trailing floor through the real pass (peak, floor, exit)");
//         resetPortfolioTestState();
//         FakeOps ft = new FakeOps();
//         PosRec tr = ft.add("B-T1_USDT", 1.0, 20.0, pxForNet(CAP * 0.020, P));
//         portfolioPass(ft);
//         check("2.0% net: trailing + breakeven active, no exit yet", pState.trailActive && pState.beActive && pState.floorActive && fx.ordersPlaced == 0 && ft.closeCalls == 0);
//         double floorSeen = pState.floor;
//         ft.prices.put("B-T1_USDT", pxForNet(CAP * 0.026, P)); ft.clock += 6_000; portfolioPass(ft);
//         check("higher profit raises the floor", pState.floor > floorSeen && Math.abs(pState.peakNet - CAP * 0.026) < 1e-6);
//         ft.prices.put("B-T1_USDT", pxForNet(CAP * 0.022, P)); ft.clock += 6_000; portfolioPass(ft);
//         check("pullback above the floor: still open", ft.closeCalls == 0 && ft.pos.size() == 1);
//         ft.prices.put("B-T1_USDT", pxForNet(CAP * 0.0175, P)); ft.clock += 6_000; portfolioPass(ft);
//         check("fall to the floor (peak 2.6% - 30% = 1.82%) -> PORTFOLIO_TRAILING_SL and everything closed", ft.pos.isEmpty() && EXIT_TRAILING.equals(pState.lastExitReason));

//         System.out.println("[Test] Realized PnL of positions that closed on their own is booked into the cycle");
//         resetPortfolioTestState();
//         FakeOps fr = new FakeOps();
//         fr.add("B-R1_USDT", 1.0, 20.0, 20.1);
//         fr.add("B-R2_USDT", 1.0, 20.0, 20.0);
//         TradeInfo t2 = new TradeInfo(); t2.isLong = true; t2.entry = 20; t2.sl = 17.6; t2.tp = 20.6; t2.initialSl = 17.6; t2.risk = 2.4; t2.qty = 1;
//         tradeState.put("B-R2_USDT", t2);
//         portfolioPass(fr);
//         fr.pos.remove("B-R2_USDT"); fr.prices.put("B-R2_USDT", 20.59);        // closed by its own TP at ~20.6
//         fr.clock += 6_000;
//         portfolioPass(fr);
//         double realizedExpected = estimateRealizedNet(true, 1, 20, 20.6, new PortfolioParams());
//         check("TP close of one position is booked as realized (net of both fees)", Math.abs(pState.realizedNet - realizedExpected) < 1e-9 && realizedExpected > 0.5);
//         check("a profit booked by one position counts toward the portfolio target", pState.peakNet >= realizedExpected - 0.5);
//         check("the closed position is dropped from the trade state (individual management unchanged)", !tradeState.containsKey("B-R2_USDT"));

//         System.out.println("[Test] State persistence and restart recovery");
//         resetPortfolioTestState();
//         PortfolioState ps = new PortfolioState();
//         ps.cycleId = 7; ps.cycleStartMs = 123456L; ps.peakNet = 1.234; ps.beActive = true; ps.trailActive = true; ps.floorActive = true; ps.floor = 0.9; ps.floorSource = EXIT_TRAILING;
//         ps.realizedNet = 0.31; ps.liquidating = true; ps.liquidationReason = EXIT_RISK_LIMIT; ps.liquidationRounds = 3; ps.liquidationNet = -2.5; ps.reentryUntilMs = 99L; ps.riskPauseUntilMs = 77L;
//         PortfolioState rt = portfolioFromJson(new JSONObject(portfolioToJson(ps).toString()));
//         check("every field survives save/load", rt.cycleId == 7 && rt.peakNet == 1.234 && rt.beActive && rt.trailActive && rt.floorActive && rt.floor == 0.9
//                 && rt.floorSource.equals(EXIT_TRAILING) && rt.realizedNet == 0.31 && rt.liquidating && rt.liquidationReason.equals(EXIT_RISK_LIMIT)
//                 && rt.liquidationRounds == 3 && rt.liquidationNet == -2.5 && rt.reentryUntilMs == 99L && rt.riskPauseUntilMs == 77L);
//         FakeOps fs = new FakeOps(); fs.persist = true;
//         fs.add("B-S1_USDT", 1.0, 20.0, pxForNet(CAP * 0.022, P));
//         portfolioPass(fs);
//         double floorBeforeRestart = pState.floor, peakBeforeRestart = pState.peakNet;
//         check("state is written to disk when protection activates", new File(portfolioStateFile).exists() && pState.floorActive);
//         pState = new PortfolioState();                    // simulated restart: memory is gone
//         loadPortfolioState();
//         check("after the restart the peak / floor / activation are restored, NOT reset", Math.abs(pState.peakNet - peakBeforeRestart) < 1e-12 && Math.abs(pState.floor - floorBeforeRestart) < 1e-12 && pState.trailActive && pState.beActive && pState.cycleStartMs > 0);
//         fs.prices.put("B-S1_USDT", pxForNet(floorBeforeRestart - 0.01, P)); fs.clock += 6_000;
//         portfolioPass(fs);
//         check("restored floor still protects: falling to it closes the position", fs.pos.isEmpty() && EXIT_TRAILING.equals(pState.lastExitReason));
//         FakeOps fm = new FakeOps(); fm.persist = true;
//         fm.add("B-M1_USDT", 1.0, 20.0, 20.0); fm.add("B-M2_USDT", 1.0, 20.0, 20.0);
//         pState = new PortfolioState(); pState.cycleId = 3; pState.cycleStartMs = fm.clock - 100_000; pState.liquidating = true; pState.liquidationReason = EXIT_HARD_TP; pState.liquidationNet = 1.6;
//         savePortfolioState();
//         pState = new PortfolioState(); loadPortfolioState();
//         check("restart in the middle of a liquidation: the saved liquidation flag is restored", pState.liquidating && pState.liquidationReason.equals(EXIT_HARD_TP));
//         check("... and entries stay blocked", portfolioEntryBlockedReason(fm.clock, 2, 0, 4.0) != null);
//         portfolioPass(fm);
//         check("... the liquidation is completed against the actual exchange positions and then reset", fm.pos.isEmpty() && !pState.liquidating && pState.cycleStartMs == 0);
//         FakeOps fz = new FakeOps(); fz.persist = false;
//         pState = new PortfolioState(); pState.cycleId = 9; pState.cycleStartMs = fz.clock - 5000; pState.peakNet = 1.0; pState.floorActive = true; pState.floor = 0.5;
//         portfolioPass(fz);
//         check("saved cycle but the exchange is flat (closed while offline): cycle is reset cleanly", pState.cycleStartMs == 0 && !pState.floorActive && pState.peakNet == 0);
//         File corrupt = new File(portfolioStateFile);
//         try { Files.write(corrupt.toPath(), "{ not json".getBytes(StandardCharsets.UTF_8)); } catch (Exception ignored) {}
//         loadPortfolioState();
//         check("corrupt state file is backed up and the bot starts fresh (no crash)", !pState.liquidating && new File(portfolioStateFile + ".corrupt").exists());

//         System.out.println("[Test] Individual SL/TP, break-even and trailing keep working next to the portfolio logic");
//         double e0 = 100.0, tk0 = 0.01;
//         TrailStep ts = computeTrail(true, e0, 88.0, 115.0, e0 * 1.14, false, 0, tk0);
//         BeStep bs = computeBreakeven(true, e0, 88.0, e0 * 1.11, tk0);
//         check("per-position trailing activates exactly as before", ts.active && ts.justActivated);
//         check("per-position break-even + profit lock moves the SL exactly as before", bs.reached && bs.move && bs.newSl > e0);
//         check("the portfolio settings are independent of the per-position settings", PORTFOLIO_BREAKEVEN_TRIGGER_PERCENT != BREAKEVEN_TRIGGER_PERCENT && PORTFOLIO_TRAILING_ACTIVATION_PERCENT != TRAIL_ACTIVATION_PERCENT);
//         TradeInfo q1 = new TradeInfo(); q1.qty = 12.5; q1.entry = 1;
//         check("trade state keeps the quantity across restarts", tradeFromJson(new JSONObject(tradeToJson(q1).toString())).qty == 12.5);
//         check("config sanity: lock < BE trigger < trailing activation < hard target", PORTFOLIO_BREAKEVEN_LOCK_PERCENT < PORTFOLIO_BREAKEVEN_TRIGGER_PERCENT
//                 && PORTFOLIO_BREAKEVEN_TRIGGER_PERCENT < PORTFOLIO_TRAILING_ACTIVATION_PERCENT && PORTFOLIO_TRAILING_ACTIVATION_PERCENT < PORTFOLIO_PROFIT_TARGET_PERCENT);
//         resetPortfolioTestState();
//     }

// }
