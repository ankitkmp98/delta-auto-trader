import org.json.JSONArray;
import org.json.JSONObject;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * STRATEGY: "Trend -> Pullback -> Rejection -> Breakout -> Fixed SL + Fixed TP"
 *
 *   4H  -> master trend direction          (analyze4H)
 *   1H  -> is the 4H trend tradable now?   (analyze1H)
 *   15M -> healthy pullback / structure    (analyze15M)
 *   5M  -> confirmation candle + trigger   (analyze5M)
 *   ARM -> wait for trigger HIGH/LOW break (processPendingSignals)
 *   ENTRY -> fixed SL (saved swing anchor) + fixed TP (1.8R / 2.2R)
 *   EXIT  -> ONLY when the exchange SL or TP is hit.
 *
 * After entry there is NO trailing, NO partial booking, NO breakeven move,
 * NO early exit, NO TP/SL modification. The safety sweep only fills in a
 * MISSING SL/TP using the originally planned values — it never changes an
 * existing one.
 *
 * 4H candles are built from 1H candles, 15M candles from 5M candles, both
 * aligned to UTC time buckets. Only CLOSED candles are ever used.
 *
 * PHASE 1 CHANGES (v2):
 *   1. SL anchor = 15M pullback low/high, buffer = SL_BUFFER_ATR x 15M ATR
 *   2. Risk-based sizing ON (fixed INR loss per trade, capped by notional)
 *   3. Breakout trigger = 5M candle high/low +/- BREAKOUT_BUFFER_ATR x 5M ATR
 *      (optional: BREAKOUT_NEEDS_5M_CLOSE = true -> wait for a 5M close beyond it)
 *   4. 5M candle quality: body >= 45%, close in top/bottom 35% of range
 *   5. BTC filter: no trade against a BTC 1H trend
 *   6. MFE / MAE tracking + trade_journal.csv for analysis
 *   Unchanged: RR 1.8 / 2.2, 5M optional 3/5, RSI ranges, 15M rules.
 */
public class CoinDCXFuturesTrader_TrendPullbackFixedSLTP {

    // =========================================================================
    // 1. API / ACCOUNT CONFIG
    // =========================================================================
    private static final String API_KEY    = System.getenv("DELTA_API_KEY");
    private static final String API_SECRET = System.getenv("DELTA_API_SECRET");
    private static final String BASE_URL       = "https://api.coindcx.com";
    private static final String PUBLIC_API_URL = "https://public.coindcx.com";

    private static final int    LEVERAGE        = 5;
    private static final String MARGIN_TYPE     = "isolated";
    private static final String MARGIN_CURRENCY = "INR";

    // Fixed notional per trade (INR). Margin used = notional / LEVERAGE.
    private static final double POSITION_NOTIONAL_INR = 2100.0;
    private static final double USDT_INR_RATE         = 102.0;

    // Optional risk-based sizing (always capped by POSITION_NOTIONAL_INR).
    private static final boolean RISK_BASED_SIZING_ENABLED = true;   // PHASE 1: ON
    private static final double  ACCOUNT_BALANCE_INR       = 2000.0;
    private static final double  RISK_PER_TRADE_PERCENT    = 1.5;   // ~30 INR per SL on a 2000 INR account

    private static final int  MAX_OPEN_POSITIONS = 5;   // PHASE 1 testing: keeps margin within the account
    private static final long PAIR_COOLDOWN_MS   = 15 * 60 * 1000L;
    private static final long SCAN_INTERVAL_MS   = 20 * 1000L;
    // During a long scan, re-check armed signals every N pairs so breakouts
    // are not missed while the rest of the coin list is being scanned.
    private static final int  PENDING_CHECK_EVERY_N_PAIRS = 25;

    // true  -> one log line for EVERY skip (4H/1H/15M/5M/SL/room reason)
    // false -> only arms, cancels, entries, protection and errors are logged
    private static final boolean LOG_ALL_SKIPS = true;

    // Every closed trade is appended here (win/loss estimate, SL%, MFE, MAE...).
    private static final String TRADE_JOURNAL_FILE = "trade_journal.csv";

    // Orders
    private static final double LIMIT_ORDER_BUFFER_PCT  = 0.0005; // entry limit 0.05% through the market
    private static final int    FILL_CHECK_ATTEMPTS     = 20;
    private static final long   FILL_CHECK_DELAY_MS     = 1000L;
    private static final int    TPSL_MAX_RETRIES        = 3;
    private static final long   TPSL_RETRY_DELAY_MS     = 2000L;
    private static final long   INSTRUMENT_CACHE_TTL_MS = 3_600_000L;

    // =========================================================================
    // 2. INDICATORS
    // =========================================================================
    private static final int    EMA_FAST          = 9;
    private static final int    EMA_SLOW          = 21;
    private static final int    ATR_PERIOD        = 14;
    private static final int    ST_PERIOD         = 10;
    private static final double ST_MULTIPLIER     = 3.0;
    private static final int    RSI_PERIOD        = 14;
    private static final int    VOLUME_MA_PERIOD  = 20;
    private static final int    EMA_SLOPE_LOOKBACK = 5;
    private static final int    VWAP_MIN_SESSION_BARS  = 6;  // below this, fall back to rolling VWAP
    private static final int    VWAP_FALLBACK_LOOKBACK = 20;

    // =========================================================================
    // 3. TIMEFRAMES / DATA
    // =========================================================================
    private static final long MIN_MS = 60_000L;
    private static final long TF_5M  = 5 * MIN_MS;
    private static final long TF_15M = 15 * MIN_MS;
    private static final long TF_1H  = 60 * MIN_MS;
    private static final long TF_4H  = 240 * MIN_MS;
    private static final long DAY_MS = 24 * TF_1H;

    private static final String RES_5M = "5";
    private static final String RES_1H = "60";
    private static final int FETCH_5M_COUNT = 450; // -> ~150 x 15M candles
    private static final int FETCH_1H_COUNT = 400; // -> ~100 x 4H candles

    private static final long CANDLE_CLOSE_GRACE_MS = 3000L;
    private static final long STALE_RETRY_MS        = 10_000L;

    private static final int MIN_BARS_4H = 50;
    private static final int MIN_BARS    = 60; // 1H / 15M / 5M

    // Swing pivots (4H / 15M): 2 bars each side.
    private static final int PIVOT_STRENGTH_HTF = 2;

    // =========================================================================
    // 4. 4H — MASTER TREND
    // =========================================================================
    private static final int H4_MIN_SCORE          = 3;  // out of 4
    private static final int H4_STRUCTURE_LOOKBACK = 40;
    private static final int H4_CHOP_LOOKBACK      = 20;
    private static final int H4_MAX_EMA_CROSSES    = 3;  // >= this many EMA9/21 crosses -> sideways
    private static final int H4_MAX_ST_FLIPS       = 3;  // >= this many Supertrend flips -> sideways

    // =========================================================================
    // 5. 1H — TREND CONFIRMATION
    // =========================================================================
    private static final int    H1_MIN_SCORE          = 3;    // out of 5
    private static final int    H1_STRONG_SCORE       = 4;
    private static final double H1_SLOPE_MIN_ATR      = 0.10;
    private static final double H1_RSI_LONG_MIN  = 45, H1_RSI_LONG_MAX  = 65;
    private static final double H1_RSI_SHORT_MIN = 35, H1_RSI_SHORT_MAX = 55;
    private static final double H1_MAX_EXTENSION_ATR  = 3.0;  // close vs EMA21
    private static final double H1_MAX_CHASE_BODY_ATR = 2.0;  // last 1H candle body in trend direction

    // =========================================================================
    // 6. 15M — PULLBACK
    // =========================================================================
    private static final int    M15_PULLBACK_WINDOW    = 8;    // last 8 x 15M = 2 hours
    private static final double M15_TOUCH_TOL_ATR      = 0.20; // how close to EMA9 counts as "pulled back"
    private static final double M15_MAX_DEPTH_ATR      = 1.0;  // closing >1 ATR beyond EMA21 = pullback too deep
    private static final double M15_MAX_EXTENSION_ATR  = 2.0;
    private static final int    M15_STRUCTURE_LOOKBACK = 40;
    private static final double M15_RSI_LONG_MIN  = 40;
    private static final double M15_RSI_SHORT_MAX = 60;
    private static final double M15_CLEAN_RSI_LONG_MIN  = 45, M15_CLEAN_RSI_LONG_MAX  = 65;
    private static final double M15_CLEAN_RSI_SHORT_MIN = 35, M15_CLEAN_RSI_SHORT_MAX = 55;

    // =========================================================================
    // 7. 5M — ENTRY CONFIRMATION
    // =========================================================================
    private static final double M5_BODY_RATIO_MIN      = 0.45; // PHASE 1: was 0.40
    private static final double M5_CLOSE_IN_RANGE_MIN  = 0.65; // PHASE 1: was 0.60 — close in top/bottom 35% of range
    private static final double M5_SLOPE_MIN_ATR       = 0.15;
    private static final double M5_MAX_EMA_DIST_ATR    = 1.5;
    private static final double M5_MAX_CANDLE_RANGE_ATR = 2.5; // bigger candle = overextended trigger
    private static final int    M5_OPTIONAL_MIN        = 3;    // out of 5
    private static final int    M5_OPTIONAL_STRONG     = 4;
    private static final double M5_RSI_LONG_MIN  = 40, M5_RSI_LONG_MAX  = 70;
    private static final double M5_RSI_SHORT_MIN = 30, M5_RSI_SHORT_MAX = 60;

    // =========================================================================
    // 8. SIGNAL ARMING
    // =========================================================================
    private static final long   SIGNAL_EXPIRY_MS          = 15 * 60 * 1000L;
    private static final double PENDING_MAX_EXTENSION_ATR = 2.0; // price left the pullback area
    private static final double MAX_BREAKOUT_CHASE_ATR    = 0.5; // price already too far past the (buffered) trigger

    // PHASE 1: breakout must clear the 5M candle high/low by this many 5M ATRs.
    private static final double  BREAKOUT_BUFFER_ATR     = 0.10;
    // true -> additionally wait for a CLOSED 5M candle beyond the buffered trigger.
    private static final boolean BREAKOUT_NEEDS_5M_CLOSE = false;

    // PHASE 1: BTC filter — skip trades while BTC's 1H trend points the other way.
    private static final boolean BTC_FILTER_ENABLED = true;
    private static final String  BTC_PAIR           = "B-BTC_USDT";

    // =========================================================================
    // 9. FIXED SL / FIXED TP
    // =========================================================================
    // PHASE 1: SL anchor = 15M pullback low/high. ALL SL ATR values below are 15M ATR.
    private static final double SL_BUFFER_ATR   = 0.30; // 15M ATR beyond the pullback swing
    private static final double SL_MIN_PERCENT  = 1.0;  // tighter -> widened to this
    private static final double SL_MAX_PERCENT  = 3.5;  // wider  -> trade skipped (risk sizing keeps INR loss fixed)
    private static final double SL_MAX_ATR      = 3.0;  // 15M ATR; wider -> trade skipped
    private static final double RR_NORMAL       = 1.8;
    private static final double RR_STRONG       = 2.2;

    // Opposing structure ("major resistance above a LONG / support below a SHORT")
    private static final int    OBSTACLE_LOOKBACK_15M = 40;
    private static final double OBSTACLE_BLOCK_R      = 1.0; // 15M swing level within 1.0R -> skip
    private static final double STRONG_ROOM_R         = 1.8; // strong RR only if nothing within 1.8R

    private static final double MIN_SLTP_GAP_PCT = 0.05;
    private static final int    MIN_SLTP_TICKS   = 2;

    // =========================================================================
    // TYPES
    // =========================================================================
    private enum Direction {
        LONG, SHORT, NONE;
        boolean isLong() { return this == LONG; }
    }

    private enum PullbackStatus { HEALTHY, NO_PULLBACK, OVEREXTENDED, WEAK_MOMENTUM, STRUCTURE_BROKEN, INSUFFICIENT_DATA }

    private static final class Candle {
        final long time;
        final double open, high, low, close, volume;
        Candle(long time, double open, double high, double low, double close, double volume) {
            this.time = time; this.open = open; this.high = high; this.low = low; this.close = close; this.volume = volume;
        }
    }

    private static final class Series {
        final int n;
        final long[] t;
        final double[] o, h, l, c, v;
        Series(List<Candle> list) {
            n = list.size();
            t = new long[n]; o = new double[n]; h = new double[n]; l = new double[n]; c = new double[n]; v = new double[n];
            for (int i = 0; i < n; i++) {
                Candle k = list.get(i);
                t[i] = k.time; o[i] = k.open; h[i] = k.high; l[i] = k.low; c[i] = k.close; v[i] = k.volume;
            }
        }
    }

    private static final class StResult {
        boolean[] bull;
        double[] upper, lower;
    }

    private static final class TrendResult {
        Direction bias = Direction.NONE;
        int bullScore, bearScore, structure;
        String reason = "";
        int score() { return bias == Direction.LONG ? bullScore : bias == Direction.SHORT ? bearScore : 0; }
    }

    private static final class ConfirmResult {
        boolean ok, strong;
        int score, opposite;
        String reason = "";
    }

    private static final class PullbackResult {
        PullbackStatus status = PullbackStatus.INSUFFICIENT_DATA;
        boolean clean;
        double extreme; // pullback low (LONG) / high (SHORT) — the SL anchor
        double atr;     // 15M ATR — the SL buffer unit
        String reason = "";
    }

    private static final class EntryResult {
        boolean ok, strong;
        long candleTime;
        double triggerHigh, triggerLow, atr;
        int optionalScore;
        String reason = "";
    }

    private static final class SlPlan {
        boolean ok, widened, clamped;
        double sl, tp, risk, slPercent, rawSlAtr, rr;
        String reason = "";
    }

    private static final class RoomResult {
        boolean blocked, strongRoom;
        String reason = "";
    }

    private static final class QtyResult {
        boolean ok;
        BigDecimal qty = BigDecimal.ZERO;
        String reason = "";
    }

    // Everything captured at ARM time. Used unchanged at entry — only the fill price is new.
    private static final class PendingSignal {
        String pair;
        Direction dir;
        String timeframe = "5M";
        double triggerPrice;     // candle high/low +/- breakout buffer
        double rawTrigger;       // the 5M confirmation candle's high/low itself
        long   triggerCandleTime;
        long   createdAtMs;
        double swingAnchor;      // 15M pullback swing
        double slAtr;            // 15M ATR used for the SL buffer
        double atr5m;            // 5M ATR (breakout buffer / chase / extension checks)
        double rr;
        boolean strongTrend;
        int score4h, score1h, score5mOptional;
        boolean clean15m;
        String meta;
    }

    // The fixed plan of an open trade. Never modified after entry.
    private static final class TradePlan {
        String pair;
        Direction dir;
        double entry, sl, tp, rr, risk, anchor, atr;
        String qty;
        long openedAtMs;
        String source; // SIGNAL / ADOPTED / FALLBACK
        boolean protectionConfirmed;
        boolean strong;
        double slPercent;
        double bestFav;   // MFE in R (max move in favour)
        double worstAdv;  // MAE in R (max move against, negative)
    }

    private static final class CachedSeries {
        final Series series;
        final long validUntil;
        CachedSeries(Series series, long validUntil) { this.series = series; this.validUntil = validUntil; }
    }

    // =========================================================================
    // STATE
    // =========================================================================
    private static final Map<String, PendingSignal> pendingSignals        = new ConcurrentHashMap<>();
    private static final Map<String, TradePlan>     activePlans           = new ConcurrentHashMap<>();
    private static final Map<String, Long>          lastTradeTime         = new ConcurrentHashMap<>();
    private static final Map<String, Long>          lastUsedTriggerCandle = new ConcurrentHashMap<>();
    private static final Map<String, CachedSeries>  candleCache           = new ConcurrentHashMap<>();
    private static final Map<String, JSONObject>    instrumentCache       = new ConcurrentHashMap<>();
    private static volatile long lastInstrumentRefresh = 0;

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    // =========================================================================
    // COINS
    // =========================================================================
    private static final String[] COIN_SYMBOLS = {
       "PIEVERSE","XAU","APE","ERA","US","RAVE","EDEN","LIT","BREV","MAGMA","BLESS","ZAMA",
        "FRAX","ACU","1000FLOKI","ELSA","LINEA","SPACE","CLO","FIGHT","UMA","MEGA","MAV","TRIA",
        "YGG","OPN","ROBO","SUI","GLM","MANTRA","SEI","CAKE","AUCTION","SENT","BSB","BASED","IRYS",
        "ACE","WET","CL","PRL","GENIUS","WIF","MANTA","LSK","AIGENSYN","PHAROS","JUP","AXL","BOME",
        "SLX","ZEST","AIOT","VVV","CAP","DATAIP","GRVT","TAO","BR","TURBO","BTC","ETH","ZK","LISTA",
        "A","LTC","XAG","COAI","HANA","ZRO","SKYAI","COPPER","RARE","ETC","M","AKE","XLM","PIXEL",
        "XAN","ADA","CROSS","XMR","G","DASH","ZEC","ATOM","TRUTH","BCH","NEO","IOST","FLUX","ALGO",
        "ZRX","COMP","WLFI","POL","DOGE","BAND","OPG","FIDA","PROM","SANTOS","RLC","1000000MOG",
        "GRASS","PNUT","TRB","KAIA","ARX","XAI","S","4","COTI","CHR","SOLV","SAGA","ORCA","1000LUNC",
        "MOVE","VIRTUAL","ME","IOTX","GIGGLE","AVA","VELODROME","AIXBT","KMNO","LA","DEXE","ZBT",
        "GRIFFAIN","BLUAI","CTSI","ROSE","TURTLE","IMX","SUN","APR","TA","ON","BIO","COOKIE",
        "AVAAI","DOT","TRUMP","MELANIA","GMT","FLOCK","CLANKER","CYS","SUSHI","VTHO","DIA",
        "SLP","GOAT","BMT","KGEN","GWEI","MUBARAK","LDO","ESP","DRIFT","FORM","PLUME","NIL",
        "UNI","ZORA","RECALL","INIT","BZ","PARTI","NATGAS","SPX","BANK","AVAX","RIVER","BILL",
        "ATH","XRP","KERNEL","JST","PUNDIX","HAEDAL","ALPINE","SOON","SOPH","HUMA","TRX","LINK",
        "HYPE","HIVE","TAIKO","TAG","MYX","NEWT","AIN","USUAL","PUMP","ICNT","BNB","H","BAT",
        "QTUM","ARC","AIO","BEAT","BTR","ALCH","THETA","VELVET","ARIA","PTB","UB","LIGHT","FF",
        "EVAAI","GMX","LYN","TAC","LAB","ENJ","AT","MMT","UAI","AAVE","JCT","KSM","HEI","JASMY",
        "NEAR","TST","SOL","OP","PLAY","INJ","STG","HOLO","ASR","B","LUNA2","RSR","INX","KAT",
        "ICP","QNT","MAGIC","T","MINA","STX","ACH","LQTY","ID","GRT","NEIRO","XVS","1INCH","SAND",
        "ANKR","RVN","SFP","KAVA","MANA","HBAR","ARB","MTL","C98","TUT","SIREN","MASK","1000XEC",
        "AR","ARPA","FIL","LPT","ENS","PEOPLE","LUMIA","DUSK","FLOW","XVG","ARKM","POPCAT","ARK",
        "MOODENG","SAFE","AXS","BICO","BIGTIME","WAXP","GAS","POWR","TIA","CHIP","STO","ORDI",
        "BEAMX","1000BONK","PYTH","ETHW","1000RATS","ANIME","OPEN","DYM","BERA","PORTAL","BB",
        "BANANAS31","CFX","SSV","TNSR","EDU","JELLYJELLY","BLUR","WAL","FHE","WCT","DEEP","SXT",
        "NAORIS","OG","CVC","AWE","O","BEL","JOE","SQD","1000PEPE","CARV","FET","SAPIEN","MEME",
        "AVNT","XPIN","ILV","KAS","BNT","STBL","BSV","RIF","SUPER","USTC","METIS","ETHFI","ENA",
        "1MBABYDOGE","CATI","HMSTR","GPS","SHELL","KAITO","ACT","RPL","BAN","THE","AKT","MORPHO",
        "CHILLGUY","AERO","MOCA","PENGU","PHA","RED","EPIC","TREE","1000CAT","MAVIA","FARTCOIN",
        "PAXG","IN","ORDER","VET","ZEN","STABLE","CHZ","NIGHT","NOM","ZKP","SKR","GRAM","BIRB",
        "CTR","KNC","ZIL","YFI","EGLD","RUNE","ASTR","ONE","1000SHIB","API3","SPELL","WOO","APT",
        "PENDLE","AGLD","CYBER","CKB","ONG","MOVR","POLYX","TWT","STEEM","ALT","ZETA","REZ","RENDER",
        "RONIN","STRK","W","SCR","CETUS","IO","MEW","SWARMS","SONIC","PIPPIN","PROMPT","MERL","F",
        "ESPORTS","PROVE","XNY","USELESS","HEMI","Q","SKY","ZKC","FLUID","MITO","CFG","EDGE","RE",
        "YB","MET","DOS","FOGO","BTW","ALLO","BROCCOLI714","HYPER","XPL","RESOLV","ASTER","KITE",
        "SIGN","HOME","MON","CC","SAHARA","MIRA","EUL","TOWNS","SYRUP","C","DOLO","ALICE","BABY",
        "SOMI","NOT","BARD","SPK","POWER","2Z","BANANA","ENSO","SYN","NXPC","GUN","XTZ","ONT","SKL",
        "HOT","JTO","DOGS","EIGEN","GTC","GALA","NMR","CGPT","ZEREBRO","VANA","OGN","CELO","USDC",
        "COW","0G","IOTA","SNX","DYDX","WLD","1000SATS","ONDO","AEVO","BRETT","LAYER","CRV","TLM","KOMA"
    };

    private static final String[] COINS_TO_TRADE = Stream.of(COIN_SYMBOLS)
            .map(s -> "B-" + s + "_USDT")
            .toArray(String[]::new);

    // =========================================================================
    // MAIN LOOP
    // =========================================================================
    public static void main(String[] args) {
        if (API_KEY == null || API_SECRET == null) {
            System.err.println("DELTA_API_KEY / DELTA_API_SECRET environment variables are not set — exiting.");
            return;
        }
        printConfig();
        refreshInstrumentCacheIfNeeded();

        while (true) {
            try {
                runCycle();
            } catch (Throwable t) {
                logErr("[MAIN-LOOP] uncaught error, continuing: " + t);
                t.printStackTrace();
            }
            if (!sleep(SCAN_INTERVAL_MS)) break;
        }
    }

    private static void printConfig() {
        log("=== Strategy: 4H trend -> 1H confirm -> 15M pullback -> 5M confirmation -> trigger breakout -> FIXED SL + FIXED TP ===");
        log(String.format("=== SL: 15M pullback swing +/- %.2f x 15M ATR, min %.1f%%, max %.1f%% / %.1f ATR15 (wider = SKIP) | RR normal=%.1f strong=%.1f ===",
                SL_BUFFER_ATR, SL_MIN_PERCENT, SL_MAX_PERCENT, SL_MAX_ATR, RR_NORMAL, RR_STRONG));
        log(String.format("=== Breakout: 5M high/low +/- %.2f x 5M ATR%s | 5M candle body>=%.0f%% close>=%.0f%% | BTC filter=%s ===",
                BREAKOUT_BUFFER_ATR, BREAKOUT_NEEDS_5M_CLOSE ? " + 5M CLOSE beyond it" : " (tick break)",
                M5_BODY_RATIO_MIN * 100, M5_CLOSE_IN_RANGE_MIN * 100, BTC_FILTER_ENABLED));
        log("=== Trailing=OFF  Partial=OFF  Breakeven=OFF  EarlyExit=OFF  DynamicTP/SL=OFF ===");
        double marginPerTrade = POSITION_NOTIONAL_INR / LEVERAGE;
        log(String.format("=== Sizing: risk-sizing=%s (%.1f%% of %.0f INR = ~%.0f INR per SL) | notional cap %.0f INR @%dx -> max ~%.0f INR margin/trade | max %d positions ===",
                RISK_BASED_SIZING_ENABLED, RISK_PER_TRADE_PERCENT, ACCOUNT_BALANCE_INR,
                ACCOUNT_BALANCE_INR * RISK_PER_TRADE_PERCENT / 100.0, POSITION_NOTIONAL_INR, LEVERAGE, marginPerTrade, MAX_OPEN_POSITIONS));
        log("=== Trade journal: " + new File(TRADE_JOURNAL_FILE).getAbsolutePath() + " ===");
        if (marginPerTrade * MAX_OPEN_POSITIONS > ACCOUNT_BALANCE_INR) {
            logErr(String.format("WARNING: %d positions x %.0f INR margin = %.0f INR > account %.0f INR — later orders may be rejected for margin.",
                    MAX_OPEN_POSITIONS, marginPerTrade, marginPerTrade * MAX_OPEN_POSITIONS, ACCOUNT_BALANCE_INR));
        }
    }

    private static void runCycle() {
        long start = System.currentTimeMillis();
        refreshInstrumentCacheIfNeeded();

        Map<String, JSONObject> positions = fetchOpenPositions();
        if (positions == null) {
            logErr("Could not fetch positions — skipping this cycle (never trade blind).");
            return;
        }
        Set<String> active = new HashSet<>(positions.keySet());

        reconcileClosedPositions(active);
        protectOpenPositions(positions);
        trackExcursions();

        pendingSignals.keySet().removeIf(active::contains);
        processPendingSignals(active);

        if (active.size() >= MAX_OPEN_POSITIONS) {
            log("MAX_OPEN_POSITIONS (" + MAX_OPEN_POSITIONS + ") reached — not scanning for new setups.");
            return;
        }

        int count = 0;
        for (String pair : COINS_TO_TRADE) {
            if (active.size() >= MAX_OPEN_POSITIONS) break;
            try {
                scanPair(pair, active);
            } catch (Exception e) {
                logErr("scanPair(" + pair + "): " + e);
            }
            if (++count % PENDING_CHECK_EVERY_N_PAIRS == 0 && !pendingSignals.isEmpty()) {
                processPendingSignals(active);
            }
        }
        processPendingSignals(active);

        log(String.format("=== Cycle done in %.1fs | open=%d pending=%d plans=%d ===",
                (System.currentTimeMillis() - start) / 1000.0, active.size(), pendingSignals.size(), activePlans.size()));
    }

    // =========================================================================
    // SCAN: 4H -> 1H -> 15M -> 5M -> ARM
    // =========================================================================
    private static void scanPair(String pair, Set<String> active) {
        if (active.contains(pair) || pendingSignals.containsKey(pair)) return;
        if (!instrumentCache.containsKey(pair)) return; // not an active futures instrument
        long now = System.currentTimeMillis();
        Long last = lastTradeTime.get(pair);
        if (last != null && now - last < PAIR_COOLDOWN_MS) return;

        // ---- 4H: master direction ----
        Series s1h = getClosedSeries(pair, RES_1H, TF_1H, FETCH_1H_COUNT);
        if (s1h == null) { skip(pair, "DATA", "1H candles unavailable"); return; }
        TrendResult t4 = analyze4H(aggregate(s1h, TF_4H));
        if (t4.bias == Direction.NONE) { skip(pair, "4H", t4.reason); return; }
        Direction dir = t4.bias;

        // ---- 1H: is the 4H trend tradable right now? ----
        ConfirmResult c1 = analyze1H(s1h, dir);
        if (!c1.ok) { skip(pair, "1H", dir + " " + c1.reason); return; }

        // ---- BTC filter (PHASE 1) ----
        String btc = btcAgainst(pair, dir);
        if (btc != null) { skip(pair, "BTC", dir + " " + btc); return; }

        // ---- 15M: healthy pullback? ----
        Series s5 = getClosedSeries(pair, RES_5M, TF_5M, FETCH_5M_COUNT);
        if (s5 == null) { skip(pair, "DATA", "5M candles unavailable"); return; }
        Series s15 = aggregate(s5, TF_15M);
        PullbackResult p15 = analyze15M(s15, dir);
        if (p15.status != PullbackStatus.HEALTHY) {
            skip(pair, "15M", dir + " " + p15.status + " — " + p15.reason);
            return;
        }

        // ---- 5M: confirmation candle ----
        EntryResult e5 = analyze5M(s5, dir);
        if (!e5.ok) { skip(pair, "5M", dir + " " + e5.reason); return; }

        Long used = lastUsedTriggerCandle.get(pair);
        if (used != null && used == e5.candleTime) return; // this candle was already armed/rejected once

        double tick = getTickSize(pair);
        if (tick <= 0) { skip(pair, "META", "tick size unknown"); return; }

        // ---- Trigger + SL anchor (captured NOW, never re-searched later) ----
        // PHASE 1: trigger = candle high/low +/- 0.10 x 5M ATR;
        //          anchor  = 15M pullback low/high (never above/below the trigger candle's own extreme);
        //          SL buffer unit = 15M ATR.
        boolean L = dir.isLong();
        double rawTrigger = L ? e5.triggerHigh : e5.triggerLow;
        double trigger = L ? rawTrigger + BREAKOUT_BUFFER_ATR * e5.atr : rawTrigger - BREAKOUT_BUFFER_ATR * e5.atr;
        trigger = roundToTick(trigger, tick, L ? RoundingMode.CEILING : RoundingMode.FLOOR);
        double anchor = L ? Math.min(p15.extreme, e5.triggerLow) : Math.max(p15.extreme, e5.triggerHigh);
        double slAtr = p15.atr;

        SlPlan prelim = buildSlTp(dir, trigger, anchor, slAtr, RR_NORMAL, tick, false);
        if (!prelim.ok) {
            skip(pair, "SL", dir + " " + prelim.reason);
            lastUsedTriggerCandle.put(pair, e5.candleTime);
            return;
        }

        RoomResult room = checkRoom(s15, dir, trigger, prelim.risk);
        if (room.blocked) {
            skip(pair, "ROOM", dir + " " + room.reason);
            lastUsedTriggerCandle.put(pair, e5.candleTime);
            return;
        }

        // ---- RR decided BEFORE entry, never changed afterwards ----
        boolean strong = t4.score() == 4 && c1.strong && p15.clean && e5.strong && room.strongRoom;
        double rr = strong ? RR_STRONG : RR_NORMAL;
        SlPlan planned = strong ? buildSlTp(dir, trigger, anchor, slAtr, rr, tick, false) : prelim;

        PendingSignal p = new PendingSignal();
        p.pair = pair;
        p.dir = dir;
        p.triggerPrice = trigger;
        p.rawTrigger = rawTrigger;
        p.triggerCandleTime = e5.candleTime;
        p.createdAtMs = now;
        p.swingAnchor = anchor;
        p.slAtr = slAtr;
        p.atr5m = e5.atr;
        p.rr = rr;
        p.strongTrend = strong;
        p.score4h = t4.score();
        p.score1h = c1.score;
        p.score5mOptional = e5.optionalScore;
        p.clean15m = p15.clean;
        p.meta = e5.reason;
        pendingSignals.put(pair, p);
        lastUsedTriggerCandle.put(pair, e5.candleTime);

        log(String.format("[ARMED] %s %s | 4H=%d/4 1H=%d/5 15M=%s%s 5M-opt=%d/5 | candle %s=%s trigger=%s (+%.2f ATR5m%s) "
                        + "anchor(15M)=%s ATR15=%s | plan@trigger SL=%s (%.2f%%, raw %.2f ATR15%s) TP=%s RR=%.1f%s | %s | expires in %d min",
                dir, pair, t4.score(), c1.score, p15.status, p15.clean ? "(clean)" : "", e5.optionalScore,
                L ? "high" : "low", px(rawTrigger), px(trigger), BREAKOUT_BUFFER_ATR,
                BREAKOUT_NEEDS_5M_CLOSE ? ", needs 5M close" : "", px(anchor), px(slAtr),
                px(planned.sl), planned.slPercent, planned.rawSlAtr, planned.widened ? ", widened to min" : "",
                px(planned.tp), rr, strong ? " STRONG" : "", room.reason, SIGNAL_EXPIRY_MS / 60000));
    }

    // =========================================================================
    // 4H — MASTER TREND (EMA9>EMA21, close>EMA21, Supertrend, HH/HL) >= 3/4
    // =========================================================================
    private static TrendResult analyze4H(Series s) {
        TrendResult r = new TrendResult();
        if (s == null || s.n < MIN_BARS_4H) {
            r.reason = "insufficient 4H data (" + (s == null ? 0 : s.n) + " bars)";
            return r;
        }
        int i = s.n - 1;
        double[] e9 = emaSeries(s.c, EMA_FAST), e21 = emaSeries(s.c, EMA_SLOW);
        StResult st = supertrend(s);
        int struct = marketStructure(s.h, s.l, H4_STRUCTURE_LOOKBACK, PIVOT_STRENGTH_HTF);
        r.structure = struct;

        boolean stBull = st.bull[i];
        r.bullScore = b(e9[i] > e21[i]) + b(s.c[i] > e21[i]) + b(stBull) + b(struct == 1);
        r.bearScore = b(e9[i] < e21[i]) + b(s.c[i] < e21[i]) + b(!stBull) + b(struct == -1);

        int crosses = countCrosses(e9, e21, H4_CHOP_LOOKBACK);
        int flips = countFlips(st.bull, H4_CHOP_LOOKBACK);
        String detail = String.format("4H bull=%d/4 bear=%d/4 [ema9>21=%s close>ema21=%s ST=%s struct=%s crosses=%d stFlips=%d]",
                r.bullScore, r.bearScore, e9[i] > e21[i], s.c[i] > e21[i], stBull ? "GREEN" : "RED",
                structName(struct), crosses, flips);

        if (crosses >= H4_MAX_EMA_CROSSES || flips >= H4_MAX_ST_FLIPS) {
            r.reason = "sideways/choppy " + detail;
            return r;
        }
        if (r.bullScore >= H4_MIN_SCORE) {
            if (struct == -1) { r.reason = "EMAs bullish but structure is LL/LH (structure wins) " + detail; return r; }
            r.bias = Direction.LONG;
        } else if (r.bearScore >= H4_MIN_SCORE) {
            if (struct == 1) { r.reason = "EMAs bearish but structure is HH/HL (structure wins) " + detail; return r; }
            r.bias = Direction.SHORT;
        } else {
            r.reason = "mixed " + detail;
            return r;
        }
        r.reason = detail;
        return r;
    }

    // =========================================================================
    // 1H — CONFIRMATION (>= 3/5, must not contradict, must not be overextended)
    // =========================================================================
    private static ConfirmResult analyze1H(Series s, Direction dir) {
        ConfirmResult r = new ConfirmResult();
        if (s == null || s.n < MIN_BARS) {
            r.reason = "insufficient 1H data (" + (s == null ? 0 : s.n) + " bars)";
            return r;
        }
        int i = s.n - 1;
        double[] e9 = emaSeries(s.c, EMA_FAST), e21 = emaSeries(s.c, EMA_SLOW);
        double[] atrS = atrSeries(s.h, s.l, s.c, ATR_PERIOD);
        double atr = atrS[i];
        if (atr <= 0) { r.reason = "1H ATR unavailable"; return r; }
        StResult st = supertrend(s);
        double rsi = rsi(s.c, RSI_PERIOD);
        double slope = e9[i] - e9[i - EMA_SLOPE_LOOKBACK];

        int bull = b(e9[i] > e21[i]) + b(s.c[i] > e21[i]) + b(st.bull[i])
                + b(slope >= H1_SLOPE_MIN_ATR * atr) + b(rsi >= H1_RSI_LONG_MIN && rsi <= H1_RSI_LONG_MAX);
        int bear = b(e9[i] < e21[i]) + b(s.c[i] < e21[i]) + b(!st.bull[i])
                + b(slope <= -H1_SLOPE_MIN_ATR * atr) + b(rsi >= H1_RSI_SHORT_MIN && rsi <= H1_RSI_SHORT_MAX);

        boolean L = dir.isLong();
        r.score = L ? bull : bear;
        r.opposite = L ? bear : bull;
        double ext = L ? (s.c[i] - e21[i]) / atr : (e21[i] - s.c[i]) / atr;
        double body = L ? (s.c[i] - s.o[i]) / atr : (s.o[i] - s.c[i]) / atr;

        String detail = String.format("1H score=%d/5 opposite=%d/5 [slope=%.2fATR rsi=%.1f ST=%s ext=%.2fATR lastBody=%.2fATR]",
                r.score, r.opposite, slope / atr, rsi, st.bull[i] ? "GREEN" : "RED", ext, body);

        if (r.opposite >= H1_MIN_SCORE)            r.reason = "1H contradicts 4H " + detail;
        else if (r.score < H1_MIN_SCORE)           r.reason = "1H confirmation too weak " + detail;
        else if (ext > H1_MAX_EXTENSION_ATR)       r.reason = "1H overextended from EMA21 " + detail;
        else if (body > H1_MAX_CHASE_BODY_ATR)     r.reason = "huge 1H candle — not chasing " + detail;
        else {
            r.ok = true;
            r.strong = r.score >= H1_STRONG_SCORE;
            r.reason = detail;
        }
        return r;
    }

    // =========================================================================
    // 15M — PULLBACK (HEALTHY / NO_PULLBACK / OVEREXTENDED / WEAK / BROKEN)
    // =========================================================================
    private static PullbackResult analyze15M(Series s, Direction dir) {
        PullbackResult r = new PullbackResult();
        if (s == null || s.n < MIN_BARS) {
            r.reason = "insufficient 15M data (" + (s == null ? 0 : s.n) + " bars)";
            return r;
        }
        int i = s.n - 1;
        boolean L = dir.isLong();
        double[] e9 = emaSeries(s.c, EMA_FAST), e21 = emaSeries(s.c, EMA_SLOW);
        double[] atrS = atrSeries(s.h, s.l, s.c, ATR_PERIOD);
        double atr = atrS[i];
        if (atr <= 0) { r.reason = "15M ATR unavailable"; return r; }
        r.atr = atr;
        double rsi = rsi(s.c, RSI_PERIOD);

        // (a) Structure must survive the pullback
        if (L) {
            List<Integer> lows = pivotLows(s.l, M15_STRUCTURE_LOOKBACK, PIVOT_STRENGTH_HTF);
            if (lows.size() >= 2) {
                double prev = s.l[lows.get(lows.size() - 2)], latest = s.l[lows.get(lows.size() - 1)];
                if (latest < prev) {
                    r.status = PullbackStatus.STRUCTURE_BROKEN;
                    r.reason = "15M printed a Lower Low (" + px(latest) + " < " + px(prev) + ")";
                    return r;
                }
            }
            if (!lows.isEmpty() && s.c[i] < s.l[lows.get(lows.size() - 1)]) {
                r.status = PullbackStatus.STRUCTURE_BROKEN;
                r.reason = "15M closed below last swing low " + px(s.l[lows.get(lows.size() - 1)]);
                return r;
            }
        } else {
            List<Integer> highs = pivotHighs(s.h, M15_STRUCTURE_LOOKBACK, PIVOT_STRENGTH_HTF);
            if (highs.size() >= 2) {
                double prev = s.h[highs.get(highs.size() - 2)], latest = s.h[highs.get(highs.size() - 1)];
                if (latest > prev) {
                    r.status = PullbackStatus.STRUCTURE_BROKEN;
                    r.reason = "15M printed a Higher High (" + px(latest) + " > " + px(prev) + ")";
                    return r;
                }
            }
            if (!highs.isEmpty() && s.c[i] > s.h[highs.get(highs.size() - 1)]) {
                r.status = PullbackStatus.STRUCTURE_BROKEN;
                r.reason = "15M closed above last swing high " + px(s.h[highs.get(highs.size() - 1)]);
                return r;
            }
        }

        // (b) Did price actually come back to EMA9/EMA21 recently, and was it controlled?
        int start = Math.max(0, s.n - M15_PULLBACK_WINDOW);
        boolean touched = false;
        double worstDepth = Double.POSITIVE_INFINITY; // closes relative to EMA21, in ATR (negative = beyond EMA21)
        double extreme = L ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
        for (int j = start; j <= i; j++) {
            double a = atrS[j] > 0 ? atrS[j] : atr;
            if (L) {
                if (s.l[j] <= e9[j] + M15_TOUCH_TOL_ATR * a) touched = true;
                worstDepth = Math.min(worstDepth, (s.c[j] - e21[j]) / a);
                extreme = Math.min(extreme, s.l[j]);
            } else {
                if (s.h[j] >= e9[j] - M15_TOUCH_TOL_ATR * a) touched = true;
                worstDepth = Math.min(worstDepth, (e21[j] - s.c[j]) / a);
                extreme = Math.max(extreme, s.h[j]);
            }
        }
        r.extreme = extreme;
        double ext = L ? (s.c[i] - e21[i]) / atr : (e21[i] - s.c[i]) / atr;
        String detail = String.format("[touchedEMA=%s worstDepth=%.2fATR ext=%.2fATR rsi=%.1f pullbackExtreme=%s]",
                touched, worstDepth, ext, rsi, px(extreme));

        if (worstDepth < -M15_MAX_DEPTH_ATR) {
            r.status = PullbackStatus.STRUCTURE_BROKEN;
            r.reason = "pullback too deep (closed >" + M15_MAX_DEPTH_ATR + " ATR beyond EMA21) " + detail;
        } else if (!touched) {
            r.status = PullbackStatus.NO_PULLBACK;
            r.reason = "price has not pulled back to EMA9/EMA21 — wait " + detail;
        } else if (ext > M15_MAX_EXTENSION_ATR) {
            r.status = PullbackStatus.OVEREXTENDED;
            r.reason = "already extended from EMA21 — wait " + detail;
        } else if (L ? rsi < M15_RSI_LONG_MIN : rsi > M15_RSI_SHORT_MAX) {
            r.status = PullbackStatus.WEAK_MOMENTUM;
            r.reason = "15M RSI unhealthy for " + dir + " " + detail;
        } else {
            r.status = PullbackStatus.HEALTHY;
            r.clean = worstDepth >= 0 && (L
                    ? rsi >= M15_CLEAN_RSI_LONG_MIN && rsi <= M15_CLEAN_RSI_LONG_MAX
                    : rsi >= M15_CLEAN_RSI_SHORT_MIN && rsi <= M15_CLEAN_RSI_SHORT_MAX);
            r.reason = detail;
        }
        return r;
    }

    // =========================================================================
    // 5M — CONFIRMATION CANDLE (mandatory checks + >= 3/5 optional quality)
    // =========================================================================
    private static EntryResult analyze5M(Series s, Direction dir) {
        EntryResult r = new EntryResult();
        if (s == null || s.n < MIN_BARS) {
            r.reason = "insufficient 5M data (" + (s == null ? 0 : s.n) + " bars)";
            return r;
        }
        int i = s.n - 1;
        long now = System.currentTimeMillis();
        long expectedLastOpen = ((now - CANDLE_CLOSE_GRACE_MS) / TF_5M) * TF_5M - TF_5M;
        if (s.t[i] < expectedLastOpen) {
            r.reason = "5M data stale (latest closed candle missing)";
            return r;
        }
        boolean L = dir.isLong();
        double[] e9 = emaSeries(s.c, EMA_FAST), e21 = emaSeries(s.c, EMA_SLOW);
        double[] atrS = atrSeries(s.h, s.l, s.c, ATR_PERIOD);
        double atr = atrS[i];
        if (atr <= 0) { r.reason = "5M ATR unavailable"; return r; }
        r.atr = atr;
        r.candleTime = s.t[i];
        r.triggerHigh = s.h[i];
        r.triggerLow = s.l[i];

        double o = s.o[i], h = s.h[i], l = s.l[i], c = s.c[i];
        double range = h - l, body = Math.abs(c - o);

        // ---- mandatory ----
        boolean directional = L ? c > o : c < o;
        boolean bodyOk = range > 0 && body / range >= M5_BODY_RATIO_MIN;
        double closePos = range > 0 ? (L ? (c - l) / range : (h - c) / range) : 0;
        boolean closeOk = closePos >= M5_CLOSE_IN_RANGE_MIN;
        double slope = e9[i] - e9[i - EMA_SLOPE_LOOKBACK];
        boolean slopeOk = L ? slope >= M5_SLOPE_MIN_ATR * atr : slope <= -M5_SLOPE_MIN_ATR * atr;
        double emaDist = Math.min(Math.abs(c - e9[i]), Math.abs(c - e21[i])) / atr;
        boolean nearEma = emaDist <= M5_MAX_EMA_DIST_ATR;
        boolean sizeOk = range <= M5_MAX_CANDLE_RANGE_ATR * atr;
        boolean mandatory = directional && bodyOk && closeOk && slopeOk && nearEma && sizeOk;

        // ---- optional quality ----
        double volMa = 0; int cnt = 0;
        for (int j = Math.max(0, i - VOLUME_MA_PERIOD); j < i; j++) { volMa += s.v[j]; cnt++; }
        volMa = cnt > 0 ? volMa / cnt : 0;
        boolean volOk = volMa > 0 && s.v[i] >= volMa;
        double rsi = rsi(s.c, RSI_PERIOD);
        boolean rsiOk = L ? rsi >= M5_RSI_LONG_MIN && rsi <= M5_RSI_LONG_MAX
                          : rsi >= M5_RSI_SHORT_MIN && rsi <= M5_RSI_SHORT_MAX;
        double vwap = sessionVwap(s);
        boolean vwapOk = L ? c >= vwap : c <= vwap;
        StResult st = supertrend(s);
        boolean stOk = L ? st.bull[i] : !st.bull[i];
        boolean alignOk = L ? (c > e9[i] && c > e21[i]) : (c < e9[i] && c < e21[i]);
        int opt = b(volOk) + b(rsiOk) + b(vwapOk) + b(stOk) + b(alignOk);
        r.optionalScore = opt;

        r.ok = mandatory && opt >= M5_OPTIONAL_MIN;
        r.strong = r.ok && opt >= M5_OPTIONAL_STRONG;
        r.reason = String.format("5M %s | directional=%s body=%.0f%%(%s) closePos=%.2f(%s) slope=%.2fATR(%s) emaDist=%.2fATR(%s) "
                        + "range=%.2fATR(%s) | vol=%s rsi=%.1f(%s) vwap=%s st=%s align=%s opt=%d/5",
                r.ok ? "CONFIRMED" : "not confirmed",
                directional, range > 0 ? body / range * 100 : 0, bodyOk, closePos, closeOk, slope / atr, slopeOk,
                emaDist, nearEma, range / atr, sizeOk, volOk, rsi, rsiOk, vwapOk, stOk, alignOk, opt);
        return r;
    }

    // =========================================================================
    // FIXED SL / TP CONSTRUCTION
    //   SL = anchor -/+ 0.35 ATR. Too tight -> widened to SL_MIN_PERCENT.
    //   Too wide (pre-entry)  -> REJECT.
    //   Too wide (post-fill, slippage only) -> clamp so risk stays bounded.
    //   TP = entry +/- RR x risk. SL rounded away from entry, TP toward entry.
    // =========================================================================
    private static SlPlan buildSlTp(Direction dir, double entry, double anchor, double atr,
                                    double rr, double tick, boolean postFill) {
        SlPlan p = new SlPlan();
        p.rr = rr;
        boolean L = dir.isLong();
        if (entry <= 0 || atr <= 0 || tick <= 0) {
            p.reason = "invalid input (entry/ATR/tick)";
            return p;
        }
        double raw = L ? anchor - SL_BUFFER_ATR * atr : anchor + SL_BUFFER_ATR * atr;
        boolean sideOk = L ? raw < entry : raw > entry;
        double sl;
        if (!sideOk) {
            if (!postFill) {
                p.reason = "structural SL on wrong side of entry (price already beyond swing anchor)";
                return p;
            }
            sl = L ? entry * (1 - SL_MIN_PERCENT / 100.0) : entry * (1 + SL_MIN_PERCENT / 100.0);
            p.clamped = true;
        } else {
            double dist = Math.abs(entry - raw);
            double rawPct = dist / entry * 100.0;
            p.rawSlAtr = dist / atr;
            if (p.rawSlAtr > SL_MAX_ATR || rawPct > SL_MAX_PERCENT) {
                if (!postFill) {
                    p.reason = String.format("SL too wide: %.2f%% / %.2f ATR (max %.1f%% / %.1f ATR) — skip, not forcing a bad setup",
                            rawPct, p.rawSlAtr, SL_MAX_PERCENT, SL_MAX_ATR);
                    return p;
                }
                double capDist = Math.min(entry * SL_MAX_PERCENT / 100.0, SL_MAX_ATR * atr);
                capDist = Math.max(capDist, entry * SL_MIN_PERCENT / 100.0);
                sl = L ? entry - capDist : entry + capDist;
                p.clamped = true;
            } else if (rawPct < SL_MIN_PERCENT) {
                sl = L ? entry * (1 - SL_MIN_PERCENT / 100.0) : entry * (1 + SL_MIN_PERCENT / 100.0);
                p.widened = true;
            } else {
                sl = raw;
            }
        }
        sl = roundToTick(sl, tick, L ? RoundingMode.FLOOR : RoundingMode.CEILING);
        double risk = Math.abs(entry - sl);
        double tp = L ? entry + rr * risk : entry - rr * risk;
        tp = roundToTick(tp, tick, L ? RoundingMode.FLOOR : RoundingMode.CEILING);

        double minGap = Math.max(MIN_SLTP_TICKS * tick, entry * MIN_SLTP_GAP_PCT / 100.0);
        boolean slValid = L ? sl <= entry - minGap : sl >= entry + minGap;
        boolean tpValid = L ? tp >= entry + minGap : tp <= entry - minGap;
        if (!slValid || !tpValid) {
            p.reason = "SL/TP on wrong side or closer than min tick distance after rounding (SL=" + px(sl) + " TP=" + px(tp) + ")";
            return p;
        }
        p.sl = sl;
        p.tp = tp;
        p.risk = risk;
        p.slPercent = risk / entry * 100.0;
        p.ok = true;
        return p;
    }

    private static SlPlan fixedPercentPlan(Direction dir, double entry, double slPct, double rr, double tick) {
        SlPlan p = new SlPlan();
        boolean L = dir.isLong();
        double t = tick > 0 ? tick : 0.0001;
        double sl = roundToTick(L ? entry * (1 - slPct / 100.0) : entry * (1 + slPct / 100.0), t,
                L ? RoundingMode.FLOOR : RoundingMode.CEILING);
        double risk = Math.abs(entry - sl);
        double tp = roundToTick(L ? entry + rr * risk : entry - rr * risk, t, L ? RoundingMode.FLOOR : RoundingMode.CEILING);
        p.sl = sl; p.tp = tp; p.risk = risk; p.rr = rr; p.slPercent = risk / entry * 100.0; p.ok = true; p.clamped = true;
        return p;
    }

    // Opposing 15M swing level (resistance above a LONG / support below a SHORT).
    private static RoomResult checkRoom(Series s15, Direction dir, double entry, double risk) {
        RoomResult r = new RoomResult();
        if (s15 == null || risk <= 0) { r.reason = "room=unknown"; return r; }
        boolean L = dir.isLong();
        List<Integer> piv = L ? pivotHighs(s15.h, OBSTACLE_LOOKBACK_15M, PIVOT_STRENGTH_HTF)
                              : pivotLows(s15.l, OBSTACLE_LOOKBACK_15M, PIVOT_STRENGTH_HTF);
        double nearestDist = Double.POSITIVE_INFINITY, nearestLevel = Double.NaN;
        for (int idx : piv) {
            double level = L ? s15.h[idx] : s15.l[idx];
            boolean ahead = L ? level > entry : level < entry;
            if (!ahead) continue;
            double d = Math.abs(level - entry);
            if (d < nearestDist) { nearestDist = d; nearestLevel = level; }
        }
        if (Double.isNaN(nearestLevel)) {
            r.strongRoom = true;
            r.reason = "room=clear (no opposing 15M swing ahead)";
            return r;
        }
        double inR = nearestDist / risk;
        r.blocked = inR <= OBSTACLE_BLOCK_R;
        r.strongRoom = inR > STRONG_ROOM_R;
        r.reason = String.format("nearest %s %s at %.2fR", L ? "resistance" : "support", px(nearestLevel), inR);
        return r;
    }

    // =========================================================================
    // PENDING SIGNALS: validate -> wait for trigger break -> enter
    // =========================================================================
    private static void processPendingSignals(Set<String> active) {
        if (pendingSignals.isEmpty()) return;
        for (String pair : new ArrayList<>(pendingSignals.keySet())) {
            PendingSignal p = pendingSignals.get(pair);
            if (p == null) continue;
            try {
                if (active.contains(pair)) { pendingSignals.remove(pair); continue; }

                String invalid = validatePending(p);
                if (invalid != null) { cancelSignal(p, invalid); continue; }

                double price = getLastPrice(pair);
                if (price <= 0) continue;
                boolean L = p.dir.isLong();
                boolean hit;
                if (BREAKOUT_NEEDS_5M_CLOSE) {
                    // A CLOSED 5M candle (after the trigger candle) must finish beyond the buffered trigger,
                    // and the live price must still be beyond it.
                    Series s5c = getClosedSeries(pair, RES_5M, TF_5M, FETCH_5M_COUNT);
                    if (s5c == null || s5c.n == 0) continue;
                    int k = s5c.n - 1;
                    boolean closedBeyond = s5c.t[k] > p.triggerCandleTime
                            && (L ? s5c.c[k] > p.triggerPrice : s5c.c[k] < p.triggerPrice);
                    hit = closedBeyond && (L ? price > p.triggerPrice : price < p.triggerPrice);
                } else {
                    hit = L ? price > p.triggerPrice : price < p.triggerPrice;
                }
                if (!hit) continue;

                double chaseAtr = (L ? price - p.triggerPrice : p.triggerPrice - price) / p.atr5m;
                if (chaseAtr > MAX_BREAKOUT_CHASE_ATR) {
                    cancelSignal(p, String.format("price already %.2f ATR beyond trigger — not chasing", chaseAtr));
                    continue;
                }
                if (active.size() >= MAX_OPEN_POSITIONS) {
                    log("[PENDING] " + pair + " trigger hit but MAX_OPEN_POSITIONS reached — waiting");
                    continue;
                }
                executeEntry(p, price, active);
            } catch (Exception e) {
                logErr("processPendingSignals(" + pair + "): " + e);
            }
        }
    }

    // Returns null if the armed setup is still valid, otherwise the cancel reason.
    private static String validatePending(PendingSignal p) {
        long now = System.currentTimeMillis();
        if (now - p.createdAtMs > SIGNAL_EXPIRY_MS) return "expired — no breakout within " + (SIGNAL_EXPIRY_MS / 60000) + " min";

        Series s1h = getClosedSeries(p.pair, RES_1H, TF_1H, FETCH_1H_COUNT);
        if (s1h != null) {
            TrendResult t4 = analyze4H(aggregate(s1h, TF_4H));
            if (t4.bias != p.dir) return "4H no longer " + p.dir + ": " + t4.reason;
            ConfirmResult c1 = analyze1H(s1h, p.dir);
            if (!c1.ok) return "1H no longer confirms: " + c1.reason;
        }
        String btc = btcAgainst(p.pair, p.dir);
        if (btc != null) return btc;

        Series s5 = getClosedSeries(p.pair, RES_5M, TF_5M, FETCH_5M_COUNT);
        if (s5 == null || s5.n < MIN_BARS) return null; // cannot re-validate right now — keep waiting
        PullbackResult p15 = analyze15M(aggregate(s5, TF_15M), p.dir);
        if (p15.status == PullbackStatus.STRUCTURE_BROKEN) return "15M structure invalid: " + p15.reason;

        int i = s5.n - 1;
        double[] e9 = emaSeries(s5.c, EMA_FAST), e21 = emaSeries(s5.c, EMA_SLOW);
        double dist = Math.min(Math.abs(s5.c[i] - e9[i]), Math.abs(s5.c[i] - e21[i])) / p.atr5m;
        if (dist > PENDING_MAX_EXTENSION_ATR) return String.format("moved %.2f ATR away from the pullback area", dist);
        boolean beyondAnchor = p.dir.isLong() ? s5.c[i] < p.swingAnchor : s5.c[i] > p.swingAnchor;
        if (beyondAnchor) return "5M closed beyond the swing anchor " + px(p.swingAnchor) + " — setup failed";
        return null;
    }

    private static void cancelSignal(PendingSignal p, String reason) {
        pendingSignals.remove(p.pair);
        log("[SIGNAL CANCELLED] " + p.dir + " " + p.pair + " — " + reason);
    }

    // =========================================================================
    // ENTRY + FIXED PROTECTION
    // =========================================================================
    private static void executeEntry(PendingSignal p, double price, Set<String> active) {
        String pair = p.pair;
        double tick = getTickSize(pair);
        if (tick <= 0) { cancelSignal(p, "tick size unknown"); return; }

        // Re-check SL width and room at the actual breakout price (same stored anchor/ATR/RR).
        SlPlan pre = buildSlTp(p.dir, price, p.swingAnchor, p.slAtr, p.rr, tick, false);
        if (!pre.ok) { cancelSignal(p, "at breakout price: " + pre.reason); return; }
        Series s5 = getClosedSeries(pair, RES_5M, TF_5M, FETCH_5M_COUNT);
        RoomResult room = checkRoom(aggregate(s5, TF_15M), p.dir, price, pre.risk);
        if (room.blocked) { cancelSignal(p, "at breakout price: " + room.reason + " — too close"); return; }

        QtyResult q = calcQuantity(pair, price, pre.sl);
        if (!q.ok) { cancelSignal(p, "quantity: " + q.reason); return; }

        log(String.format("==== BREAKOUT ENTRY %s %s | price=%s trigger=%s | %s ====",
                p.dir, pair, px(price), px(p.triggerPrice), q.reason));
        JSONObject resp = placeLimitEntryOrder(p.dir, pair, q.qty, price, tick);
        pendingSignals.remove(pair);
        if (resp == null || !resp.has("id")) {
            logErr("[ORDER FAILED] " + pair + " response=" + resp);
            return;
        }
        String orderId = resp.optString("id");
        lastTradeTime.put(pair, System.currentTimeMillis());
        log("  order placed id=" + orderId + " — waiting for fill...");

        waitForFill(pair);
        cancelOrder(orderId, pair); // cancel any unfilled remainder (harmless if already fully filled)
        sleep(1000);

        JSONObject pos = findPositionSafe(pair);
        if (pos == null || pos.optDouble("avg_price", 0) <= 0 || Math.abs(pos.optDouble("active_pos", 0)) <= 0) {
            log("  [NO FILL] " + pair + " — entry order not filled and cancelled. No trade.");
            return;
        }
        double entry = pos.optDouble("avg_price", 0);
        double filledQty = Math.abs(pos.optDouble("active_pos", 0));

        // Final SL/TP from the ACTUAL fill price + the ORIGINAL anchor / ATR / RR.
        SlPlan fin = buildSlTp(p.dir, entry, p.swingAnchor, p.slAtr, p.rr, tick, true);
        if (!fin.ok) {
            logErr("  [WARN] " + pair + " post-fill SL build failed (" + fin.reason + ") — using fixed " + SL_MAX_PERCENT + "% SL");
            fin = fixedPercentPlan(p.dir, entry, SL_MAX_PERCENT, p.rr, tick);
        }

        TradePlan plan = new TradePlan();
        plan.pair = pair;
        plan.dir = p.dir;
        plan.entry = entry;
        plan.sl = fin.sl;
        plan.tp = fin.tp;
        plan.rr = p.rr;
        plan.risk = fin.risk;
        plan.anchor = p.swingAnchor;
        plan.atr = p.slAtr;
        plan.qty = BigDecimal.valueOf(filledQty).stripTrailingZeros().toPlainString();
        plan.openedAtMs = System.currentTimeMillis();
        plan.source = "SIGNAL";
        plan.strong = p.strongTrend;
        plan.slPercent = fin.slPercent;
        activePlans.put(pair, plan);
        active.add(pair);

        double riskInr = filledQty * fin.risk * USDT_INR_RATE;
        log(String.format("[ENTRY] %s %s | fill=%s qty=%s | anchor(15M)=%s ATR15=%s | SL=%s (%.2f%%%s) risk=%s (~%.0f INR) | RR=%.1f%s | TP=%s | "
                        + "4H=%d/4 1H=%d/5 15M clean=%s 5M-opt=%d/5",
                p.dir, pair, px(entry), plan.qty, px(p.swingAnchor), px(p.slAtr), px(fin.sl), fin.slPercent,
                fin.widened ? ", widened to min" : fin.clamped ? ", CLAMPED post-fill" : "", px(fin.risk), riskInr, p.rr,
                p.strongTrend ? " STRONG" : "", px(fin.tp), p.score4h, p.score1h, p.clean15m, p.score5mOptional));

        String posId = pos.optString("id", null);
        if (posId == null) posId = getPositionIdWithRetry(pair);
        if (posId == null) {
            logErr("  [WARN] " + pair + " position id not found — safety sweep will place SL/TP next cycle");
            return;
        }
        placeAndVerifyTpSl(pair, posId, plan.tp, plan.sl, plan);
    }

    // Places SL+TP and verifies both exist on the exchange.
    private static boolean placeAndVerifyTpSl(String pair, String posId, double tp, double sl, TradePlan plan) {
        double tick = getTickSize(pair);
        double t = tick > 0 ? tick : 0.0001;
        for (int attempt = 1; attempt <= TPSL_MAX_RETRIES; attempt++) {
            setTpSl(posId, tp, sl, t, pair);
            sleep(TPSL_RETRY_DELAY_MS);
            JSONObject pos = findPositionSafe(pair);
            if (pos != null) {
                double tpEx = pos.optDouble("take_profit_trigger", 0), slEx = pos.optDouble("stop_loss_trigger", 0);
                if (tpEx > 0 && slEx > 0) {
                    double tol = Math.max(2 * t, plan.entry * 0.0001);
                    if (Math.abs(tpEx - tp) > tol || Math.abs(slEx - sl) > tol) {
                        logErr(String.format("  [PROTECTION] %s exchange shows TP=%s SL=%s vs planned TP=%s SL=%s — left as is (no modification)",
                                pair, px(tpEx), px(slEx), px(tp), px(sl)));
                    } else {
                        log(String.format("  [PROTECTION] %s SL=%s TP=%s confirmed on exchange (attempt %d). Fixed until exit.",
                                pair, px(slEx), px(tpEx), attempt));
                    }
                    plan.protectionConfirmed = true;
                    return true;
                }
            }
            log("  [PROTECTION] " + pair + " SL/TP not confirmed yet (attempt " + attempt + "/" + TPSL_MAX_RETRIES + ")");
        }
        logErr("  [CRITICAL] " + pair + " SL/TP NOT confirmed after " + TPSL_MAX_RETRIES + " attempts — sweep retries next cycle");
        return false;
    }

    // =========================================================================
    // SAFETY SWEEP — only fills a MISSING SL/TP. Never modifies an existing one.
    // =========================================================================
    private static void protectOpenPositions(Map<String, JSONObject> positions) {
        for (Map.Entry<String, JSONObject> e : positions.entrySet()) {
            String pair = e.getKey();
            JSONObject pos = e.getValue();
            try {
                double avg = pos.optDouble("avg_price", 0);
                double activePos = pos.optDouble("active_pos", 0);
                if (avg <= 0 || activePos == 0) continue;
                double tpEx = pos.optDouble("take_profit_trigger", 0);
                double slEx = pos.optDouble("stop_loss_trigger", 0);
                String posId = pos.optString("id", null);
                Direction dir = activePos > 0 ? Direction.LONG : Direction.SHORT;
                TradePlan plan = activePlans.get(pair);

                if (tpEx > 0 && slEx > 0) {
                    if (plan == null) {
                        plan = new TradePlan();
                        plan.pair = pair; plan.dir = dir; plan.entry = avg; plan.sl = slEx; plan.tp = tpEx;
                        plan.risk = Math.abs(avg - slEx);
                        plan.rr = plan.risk > 0 ? Math.abs(tpEx - avg) / plan.risk : 0;
                        plan.qty = BigDecimal.valueOf(Math.abs(activePos)).stripTrailingZeros().toPlainString();
                        plan.openedAtMs = System.currentTimeMillis();
                        plan.source = "ADOPTED";
                        plan.slPercent = plan.risk / avg * 100.0;
                        plan.protectionConfirmed = true;
                        activePlans.put(pair, plan);
                        log(String.format("[ADOPTED] %s %s entry=%s SL=%s TP=%s — existing protection left untouched",
                                dir, pair, px(avg), px(slEx), px(tpEx)));
                    } else if (!plan.protectionConfirmed) {
                        plan.protectionConfirmed = true;
                    }
                    continue;
                }

                logErr("[SWEEP] " + pair + " missing " + (tpEx <= 0 ? "TP " : "") + (slEx <= 0 ? "SL" : ""));
                if (plan == null) {
                    plan = buildFallbackPlan(pair, dir, avg, Math.abs(activePos));
                    activePlans.put(pair, plan);
                }
                double sl = slEx > 0 ? slEx : plan.sl;
                double tp = tpEx > 0 ? tpEx : plan.tp;

                double price = getLastPrice(pair);
                if (price > 0) {
                    boolean slBreached = dir.isLong() ? price <= sl : price >= sl;
                    boolean tpReached  = dir.isLong() ? price >= tp : price <= tp;
                    if (slBreached || tpReached) {
                        logErr(String.format("[SWEEP] %s price %s is already beyond planned %s (%s) while unprotected — exiting at market",
                                pair, px(price), slBreached ? "SL" : "TP", px(slBreached ? sl : tp)));
                        if (posId != null) exitPositionNow(posId, pair);
                        continue;
                    }
                }
                if (posId == null) { logErr("[SWEEP] " + pair + " position id missing — cannot place SL/TP"); continue; }
                log(String.format("[SWEEP] %s placing planned SL=%s TP=%s (source=%s)", pair, px(sl), px(tp), plan.source));
                placeAndVerifyTpSl(pair, posId, tp, sl, plan);
            } catch (Exception ex) {
                logErr("protectOpenPositions(" + pair + "): " + ex);
            }
        }
    }

    // Only for positions with no stored plan (e.g. after a restart). Computed once, then fixed.
    private static TradePlan buildFallbackPlan(String pair, Direction dir, double avg, double qty) {
        double tick = getTickSize(pair);
        SlPlan sp = null;
        double anchor = Double.NaN, atr = 0;
        Series s15 = aggregate(getClosedSeries(pair, RES_5M, TF_5M, FETCH_5M_COUNT), TF_15M);
        if (s15 != null && s15.n >= MIN_BARS && tick > 0) {
            double[] atrS = atrSeries(s15.h, s15.l, s15.c, ATR_PERIOD);
            atr = atrS[s15.n - 1];
            anchor = dir.isLong() ? recentSwingLow(s15.l, M15_PULLBACK_WINDOW * 2, PIVOT_STRENGTH_HTF)
                                  : recentSwingHigh(s15.h, M15_PULLBACK_WINDOW * 2, PIVOT_STRENGTH_HTF);
            if (atr > 0) sp = buildSlTp(dir, avg, anchor, atr, RR_NORMAL, tick, true);
        }
        if (sp == null || !sp.ok) sp = fixedPercentPlan(dir, avg, SL_MAX_PERCENT, RR_NORMAL, tick);

        TradePlan plan = new TradePlan();
        plan.pair = pair; plan.dir = dir; plan.entry = avg; plan.sl = sp.sl; plan.tp = sp.tp;
        plan.rr = RR_NORMAL; plan.risk = sp.risk; plan.anchor = anchor; plan.atr = atr; plan.slPercent = sp.slPercent;
        plan.qty = BigDecimal.valueOf(qty).stripTrailingZeros().toPlainString();
        plan.openedAtMs = System.currentTimeMillis();
        plan.source = "FALLBACK";
        log(String.format("[FALLBACK PLAN] %s %s entry=%s SL=%s TP=%s (no stored signal — computed once, fixed from now)",
                dir, pair, px(avg), px(sp.sl), px(sp.tp)));
        return plan;
    }

    private static void reconcileClosedPositions(Set<String> active) {
        for (String pair : new ArrayList<>(activePlans.keySet())) {
            if (active.contains(pair)) continue;
            TradePlan plan = activePlans.remove(pair);
            if (plan == null) continue;

            // Fold in the 5M candles since entry so wicks count toward MFE/MAE,
            // and estimate which fixed level was touched first.
            String outcome = "unknown";
            Series s5 = getClosedSeries(pair, RES_5M, TF_5M, FETCH_5M_COUNT);
            if (s5 != null && plan.risk > 0) {
                long entryBar = (plan.openedAtMs / TF_5M) * TF_5M;
                boolean L = plan.dir.isLong();
                for (int i = 0; i < s5.n; i++) {
                    if (s5.t[i] < entryBar) continue;
                    double fav = (L ? s5.h[i] - plan.entry : plan.entry - s5.l[i]) / plan.risk;
                    double adv = (L ? s5.l[i] - plan.entry : plan.entry - s5.h[i]) / plan.risk;
                    if (s5.t[i] > entryBar) {               // entry bar partly pre-entry — skip it for MFE/MAE
                        plan.bestFav = Math.max(plan.bestFav, fav);
                        plan.worstAdv = Math.min(plan.worstAdv, adv);
                    }
                    if (outcome.equals("unknown")) {
                        boolean slTouched = L ? s5.l[i] <= plan.sl : s5.h[i] >= plan.sl;
                        boolean tpTouched = L ? s5.h[i] >= plan.tp : s5.l[i] <= plan.tp;
                        if (slTouched && tpTouched) outcome = "SL/TP same candle";
                        else if (slTouched) outcome = "SL";
                        else if (tpTouched) outcome = "TP";
                    }
                }
            }
            if (outcome.equals("unknown")) {
                double price = getLastPrice(pair);
                if (price > 0) outcome = Math.abs(price - plan.tp) < Math.abs(price - plan.sl) ? "TP?" : "SL?";
            }
            double heldMin = (System.currentTimeMillis() - plan.openedAtMs) / 60000.0;
            log(String.format("[CLOSED] %s %s entry=%s SL=%s (%.2f%%) TP=%s RR=%.1f -> exit via %s (estimated) | MFE=%.2fR MAE=%.2fR | held %.0f min",
                    plan.dir, pair, px(plan.entry), px(plan.sl), plan.slPercent, px(plan.tp), plan.rr, outcome,
                    plan.bestFav, plan.worstAdv, heldMin));
            writeJournal(plan, outcome, heldMin);
        }
    }

    // MFE/MAE sampling of open trades each cycle (live price, in R).
    private static void trackExcursions() {
        for (TradePlan tp : activePlans.values()) {
            if (tp.risk <= 0) continue;
            double lp = getLastPrice(tp.pair);
            if (lp <= 0) continue;
            double fav = (tp.dir.isLong() ? lp - tp.entry : tp.entry - lp) / tp.risk;
            tp.bestFav = Math.max(tp.bestFav, fav);
            tp.worstAdv = Math.min(tp.worstAdv, fav);
        }
    }

    // Appends one row per closed trade. Outcome is an ESTIMATE — verify with CoinDCX trade history.
    private static void writeJournal(TradePlan p, String outcome, double heldMin) {
        File f = new File(TRADE_JOURNAL_FILE);
        boolean header = !f.exists();
        try (PrintWriter w = new PrintWriter(new FileWriter(f, true))) {
            if (header) {
                w.println("closed_at,pair,dir,source,entry,sl,tp,sl_pct,rr,strong,outcome_est,mfe_r,mae_r,held_min");
            }
            w.println(String.format(Locale.US, "%s,%s,%s,%s,%s,%s,%s,%.3f,%.2f,%s,%s,%.2f,%.2f,%.0f",
                    Instant.now().toString(), p.pair, p.dir, p.source, px(p.entry), px(p.sl), px(p.tp),
                    p.slPercent, p.rr, p.strong, outcome, p.bestFav, p.worstAdv, heldMin));
        } catch (IOException e) {
            logErr("writeJournal: " + e.getMessage());
        }
    }

    // PHASE 1: returns a reason if BTC's 1H trend points against `dir`, else null.
    private static String btcAgainst(String pair, Direction dir) {
        if (!BTC_FILTER_ENABLED || BTC_PAIR.equals(pair)) return null;
        Series btc = getClosedSeries(BTC_PAIR, RES_1H, TF_1H, FETCH_1H_COUNT);
        if (btc == null) return null;
        Direction opp = dir == Direction.LONG ? Direction.SHORT : Direction.LONG;
        ConfirmResult c = analyze1H(btc, opp);
        if (c.score >= H1_MIN_SCORE && c.score > c.opposite) {
            return String.format("BTC 1H trending %s (%d/5 vs %d/5)", opp, c.score, c.opposite);
        }
        return null;
    }

    // =========================================================================
    // QUANTITY — uses each instrument's quantity_increment / limits
    // NOTE: verify these instrument field names against CoinDCX's instrument API.
    // =========================================================================
    private static QtyResult calcQuantity(String pair, double price, double sl) {
        QtyResult r = new QtyResult();
        JSONObject inst = instrumentCache.get(pair);
        if (inst == null) { r.reason = "instrument metadata missing"; return r; }
        double step = firstPositive(inst, "quantity_increment", "lot_size", "min_trade_size");
        if (step <= 0) { r.reason = "quantity_increment not available"; return r; }
        double minQty = firstPositive(inst, "min_quantity", "min_trade_size");
        double maxQty = firstPositive(inst, "max_quantity");
        double minNotional = firstPositive(inst, "min_notional");

        double notionalUsdt = POSITION_NOTIONAL_INR / USDT_INR_RATE;
        double raw = notionalUsdt / price;
        if (RISK_BASED_SIZING_ENABLED) {
            double stop = Math.abs(price - sl);
            if (stop <= 0) { r.reason = "zero stop distance"; return r; }
            double riskUsdt = ACCOUNT_BALANCE_INR * RISK_PER_TRADE_PERCENT / 100.0 / USDT_INR_RATE;
            raw = Math.min(raw, riskUsdt / stop);
        }
        BigDecimal qty = floorToStep(raw, step);
        if (maxQty > 0 && qty.doubleValue() > maxQty) qty = floorToStep(maxQty, step);
        String stepStr = BigDecimal.valueOf(step).stripTrailingZeros().toPlainString();
        if (qty.signum() <= 0) {
            r.reason = String.format("qty rounds to 0 (raw=%.8f step=%s) — notional too small for this price", raw, stepStr);
            return r;
        }
        if (minQty > 0 && qty.doubleValue() < minQty) {
            r.reason = "qty " + qty.toPlainString() + " < min_quantity " + minQty;
            return r;
        }
        double notional = qty.doubleValue() * price;
        if (minNotional > 0 && notional < minNotional) {
            r.reason = String.format("notional %.2f < min_notional %.2f", notional, minNotional);
            return r;
        }
        r.ok = true;
        r.qty = qty;
        double riskInr = qty.doubleValue() * Math.abs(price - sl) * USDT_INR_RATE;
        r.reason = String.format("qty=%s (step=%s) notional=%.2f USDT risk~%.0f INR", qty.toPlainString(), stepStr, notional, riskInr);
        return r;
    }

    private static BigDecimal floorToStep(double value, double step) {
        BigDecimal s = BigDecimal.valueOf(step);
        int scale = Math.max(0, s.stripTrailingZeros().scale());
        return BigDecimal.valueOf(value).divide(s, 0, RoundingMode.FLOOR).multiply(s).setScale(scale, RoundingMode.FLOOR);
    }

    private static double firstPositive(JSONObject o, String... keys) {
        for (String k : keys) {
            double v = o.optDouble(k, 0);
            if (!Double.isNaN(v) && v > 0) return v;
        }
        return 0;
    }

    // =========================================================================
    // CANDLES — closed only, cached until the next candle close
    // =========================================================================
    private static Series getClosedSeries(String pair, String res, long intervalMs, int count) {
        long now = System.currentTimeMillis();
        String key = pair + "|" + res;
        CachedSeries cs = candleCache.get(key);
        if (cs != null && now < cs.validUntil) return cs.series;

        List<Candle> list = fetchClosedCandles(pair, res, intervalMs, count);
        if (list == null || list.isEmpty()) return null;
        Series s = new Series(list);
        long expectedLastOpen = ((now - CANDLE_CLOSE_GRACE_MS) / intervalMs) * intervalMs - intervalMs;
        long validUntil = s.t[s.n - 1] < expectedLastOpen
                ? now + STALE_RETRY_MS
                : (now / intervalMs + 1) * intervalMs + CANDLE_CLOSE_GRACE_MS;
        candleCache.put(key, new CachedSeries(s, validUntil));
        return s;
    }

    private static List<Candle> fetchClosedCandles(String pair, String resolution, long intervalMs, int count) {
        try {
            long nowMs = System.currentTimeMillis();
            long toSec = nowMs / 1000;
            long fromSec = toSec - (intervalMs / 1000) * count;
            String url = PUBLIC_API_URL + "/market_data/candlesticks?pair=" + pair + "&from=" + fromSec + "&to=" + toSec
                    + "&resolution=" + resolution + "&pcode=f";
            HttpURLConnection conn = openGet(url);
            int code = conn.getResponseCode();
            if (code != 200) {
                logErr("  candles HTTP " + code + " " + pair + "/" + resolution);
                return null;
            }
            JSONObject r = new JSONObject(readStream(conn.getInputStream()));
            if (!"ok".equals(r.optString("s"))) return null;
            JSONArray data = r.optJSONArray("data");
            if (data == null) return null;
            List<Candle> out = new ArrayList<>(data.length());
            for (int i = 0; i < data.length(); i++) {
                JSONObject o = data.getJSONObject(i);
                long t = o.optLong("time", 0);
                if (t <= 0) continue;
                if (t < 100_000_000_000L) t *= 1000; // seconds -> ms
                if (t + intervalMs > nowMs) continue; // still forming
                out.add(new Candle(t, o.getDouble("open"), o.getDouble("high"), o.getDouble("low"),
                        o.getDouble("close"), o.optDouble("volume", 0)));
            }
            out.sort(Comparator.comparingLong(c -> c.time));
            List<Candle> dedup = new ArrayList<>(out.size());
            for (Candle c : out) {
                if (dedup.isEmpty() || dedup.get(dedup.size() - 1).time != c.time) dedup.add(c);
            }
            return dedup;
        } catch (Exception e) {
            logErr("  fetchClosedCandles(" + pair + "/" + resolution + "): " + e.getMessage());
            return null;
        }
    }

    // UTC-aligned aggregation (5M->15M, 1H->4H). Drops a partial first bucket
    // and any bucket that has not closed yet.
    private static Series aggregate(Series src, long dstIntervalMs) {
        if (src == null || src.n == 0) return null;
        long now = System.currentTimeMillis();
        List<Candle> out = new ArrayList<>();
        long bucket = -1;
        boolean skipBucket = false;
        double o = 0, h = 0, l = 0, c = 0, v = 0;
        for (int i = 0; i < src.n; i++) {
            long b = (src.t[i] / dstIntervalMs) * dstIntervalMs;
            if (b != bucket) {
                if (bucket >= 0 && !skipBucket) out.add(new Candle(bucket, o, h, l, c, v));
                bucket = b;
                skipBucket = (i == 0 && src.t[i] != b);
                o = src.o[i]; h = src.h[i]; l = src.l[i]; c = src.c[i]; v = src.v[i];
            } else {
                h = Math.max(h, src.h[i]);
                l = Math.min(l, src.l[i]);
                c = src.c[i];
                v += src.v[i];
            }
        }
        if (bucket >= 0 && !skipBucket && bucket + dstIntervalMs <= now) out.add(new Candle(bucket, o, h, l, c, v));
        return out.isEmpty() ? null : new Series(out);
    }

    // =========================================================================
    // INDICATORS
    // =========================================================================
    private static double[] emaSeries(double[] d, int p) {
        int n = d.length;
        double[] out = new double[n];
        if (n == 0) return out;
        if (n < p) { Arrays.fill(out, d[n - 1]); return out; }
        double k = 2.0 / (p + 1);
        double sma = 0;
        for (int i = 0; i < p; i++) sma += d[i];
        sma /= p;
        for (int i = 0; i < p; i++) out[i] = sma;
        double e = sma;
        for (int i = p; i < n; i++) { e = d[i] * k + e * (1 - k); out[i] = e; }
        return out;
    }

    private static double[] atrSeries(double[] h, double[] l, double[] c, int p) {
        int n = h.length;
        double[] atr = new double[n];
        if (n < p + 1) return atr;
        double[] tr = new double[n];
        tr[0] = h[0] - l[0];
        for (int i = 1; i < n; i++)
            tr[i] = Math.max(h[i] - l[i], Math.max(Math.abs(h[i] - c[i - 1]), Math.abs(l[i] - c[i - 1])));
        double sum = 0;
        for (int i = 0; i < p; i++) sum += tr[i];
        atr[p - 1] = sum / p;
        for (int i = p; i < n; i++) atr[i] = (atr[i - 1] * (p - 1) + tr[i]) / p;
        for (int i = 0; i < p - 1; i++) atr[i] = atr[p - 1];
        return atr;
    }

    private static double rsi(double[] c, int p) {
        if (c.length < p + 1) return 50.0;
        double gain = 0, loss = 0;
        for (int i = 1; i <= p; i++) {
            double ch = c[i] - c[i - 1];
            if (ch > 0) gain += ch; else loss -= ch;
        }
        gain /= p; loss /= p;
        for (int i = p + 1; i < c.length; i++) {
            double ch = c[i] - c[i - 1];
            gain = (gain * (p - 1) + Math.max(ch, 0)) / p;
            loss = (loss * (p - 1) + Math.max(-ch, 0)) / p;
        }
        if (loss == 0) return 100.0;
        return 100.0 - 100.0 / (1.0 + gain / loss);
    }

    private static StResult supertrend(Series s) {
        int n = s.n;
        StResult r = new StResult();
        r.bull = new boolean[n]; r.upper = new double[n]; r.lower = new double[n];
        if (n < ST_PERIOD + 1) { Arrays.fill(r.bull, true); return r; }
        double[] atr = atrSeries(s.h, s.l, s.c, ST_PERIOD);
        for (int i = ST_PERIOD; i < n; i++) {
            double hl2 = (s.h[i] + s.l[i]) / 2.0;
            double bu = hl2 + ST_MULTIPLIER * atr[i], bl = hl2 - ST_MULTIPLIER * atr[i];
            if (i == ST_PERIOD) {
                r.upper[i] = bu; r.lower[i] = bl;
                r.bull[i] = s.c[i] > hl2;
            } else {
                r.upper[i] = (bu < r.upper[i - 1] || s.c[i - 1] > r.upper[i - 1]) ? bu : r.upper[i - 1];
                r.lower[i] = (bl > r.lower[i - 1] || s.c[i - 1] < r.lower[i - 1]) ? bl : r.lower[i - 1];
                r.bull[i] = r.bull[i - 1] ? s.c[i] >= r.lower[i] : s.c[i] > r.upper[i];
            }
        }
        for (int i = 0; i < ST_PERIOD; i++) {
            r.bull[i] = r.bull[ST_PERIOD]; r.upper[i] = r.upper[ST_PERIOD]; r.lower[i] = r.lower[ST_PERIOD];
        }
        return r;
    }

    // Session VWAP from 00:00 UTC; rolling VWAP if the session is too young.
    private static double sessionVwap(Series s) {
        int i = s.n - 1;
        long dayStart = (s.t[i] / DAY_MS) * DAY_MS;
        double pv = 0, vv = 0;
        int cnt = 0;
        for (int j = i; j >= 0 && s.t[j] >= dayStart; j--) {
            double tp = (s.h[j] + s.l[j] + s.c[j]) / 3.0;
            pv += tp * s.v[j]; vv += s.v[j]; cnt++;
        }
        if (cnt < VWAP_MIN_SESSION_BARS || vv <= 0) {
            pv = 0; vv = 0;
            for (int j = Math.max(0, s.n - VWAP_FALLBACK_LOOKBACK); j <= i; j++) {
                double tp = (s.h[j] + s.l[j] + s.c[j]) / 3.0;
                pv += tp * s.v[j]; vv += s.v[j];
            }
        }
        return vv > 0 ? pv / vv : s.c[i];
    }

    private static int countCrosses(double[] a, double[] b, int lookback) {
        int n = a.length, cnt = 0;
        for (int i = Math.max(1, n - lookback); i < n; i++) {
            boolean prevAbove = a[i - 1] > b[i - 1], nowAbove = a[i] > b[i];
            if (prevAbove != nowAbove) cnt++;
        }
        return cnt;
    }

    private static int countFlips(boolean[] x, int lookback) {
        int n = x.length, cnt = 0;
        for (int i = Math.max(1, n - lookback); i < n; i++) if (x[i] != x[i - 1]) cnt++;
        return cnt;
    }

    // =========================================================================
    // MARKET STRUCTURE (confirmed pivots only)
    // =========================================================================
    private static boolean isPivotHigh(double[] h, int i, int k) {
        if (i - k < 0 || i + k >= h.length) return false;
        for (int j = i - k; j <= i + k; j++) {
            if (j == i) continue;
            if (j < i ? h[j] > h[i] : h[j] >= h[i]) return false;
        }
        return true;
    }

    private static boolean isPivotLow(double[] l, int i, int k) {
        if (i - k < 0 || i + k >= l.length) return false;
        for (int j = i - k; j <= i + k; j++) {
            if (j == i) continue;
            if (j < i ? l[j] < l[i] : l[j] <= l[i]) return false;
        }
        return true;
    }

    private static List<Integer> pivotHighs(double[] h, int lookback, int k) {
        List<Integer> idx = new ArrayList<>();
        int n = h.length;
        for (int i = Math.max(k, n - lookback); i <= n - 1 - k; i++) if (isPivotHigh(h, i, k)) idx.add(i);
        return idx;
    }

    private static List<Integer> pivotLows(double[] l, int lookback, int k) {
        List<Integer> idx = new ArrayList<>();
        int n = l.length;
        for (int i = Math.max(k, n - lookback); i <= n - 1 - k; i++) if (isPivotLow(l, i, k)) idx.add(i);
        return idx;
    }

    // +1 = HH+HL, -1 = LL+LH, 0 = unclear
    private static int marketStructure(double[] h, double[] l, int lookback, int k) {
        List<Integer> hs = pivotHighs(h, lookback, k), ls = pivotLows(l, lookback, k);
        if (hs.size() < 2 || ls.size() < 2) return 0;
        double h1 = h[hs.get(hs.size() - 2)], h2 = h[hs.get(hs.size() - 1)];
        double l1 = l[ls.get(ls.size() - 2)], l2 = l[ls.get(ls.size() - 1)];
        if (h2 > h1 && l2 > l1) return 1;
        if (h2 < h1 && l2 < l1) return -1;
        return 0;
    }

    private static double recentSwingLow(double[] l, int lookback, int k) {
        List<Integer> p = pivotLows(l, lookback, k);
        if (!p.isEmpty()) return l[p.get(p.size() - 1)];
        double min = Double.POSITIVE_INFINITY;
        for (int i = Math.max(0, l.length - 8); i < l.length; i++) min = Math.min(min, l[i]);
        return min;
    }

    private static double recentSwingHigh(double[] h, int lookback, int k) {
        List<Integer> p = pivotHighs(h, lookback, k);
        if (!p.isEmpty()) return h[p.get(p.size() - 1)];
        double max = Double.NEGATIVE_INFINITY;
        for (int i = Math.max(0, h.length - 8); i < h.length; i++) max = Math.max(max, h[i]);
        return max;
    }

    private static String structName(int s) { return s == 1 ? "HH/HL" : s == -1 ? "LL/LH" : "unclear"; }

    // =========================================================================
    // EXCHANGE — positions / orders / TP-SL
    // =========================================================================
    private static JSONArray fetchPositionsRaw() throws Exception {
        JSONObject body = new JSONObject();
        body.put("timestamp", Instant.now().toEpochMilli());
        body.put("page", "1");
        body.put("size", "100");
        body.put("margin_currency_short_name", new JSONArray().put("INR").put("USDT"));
        String resp = authPost(BASE_URL + "/exchange/v1/derivatives/futures/positions", body.toString()).trim();
        if (resp.startsWith("[")) return new JSONArray(resp);
        JSONObject o = new JSONObject(resp);
        if (o.has("err_code_dcx") || (o.has("message") && !o.has("pair"))) throw new IOException("positions error: " + o);
        return new JSONArray().put(o);
    }

    // Returns null on API failure (so the caller never assumes "no positions").
    private static Map<String, JSONObject> fetchOpenPositions() {
        try {
            JSONArray arr = fetchPositionsRaw();
            Map<String, JSONObject> out = new LinkedHashMap<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject p = arr.getJSONObject(i);
                String pair = p.optString("pair", "");
                if (pair.isEmpty()) continue;
                boolean isActive = Math.abs(p.optDouble("active_pos", 0)) > 0 || p.optDouble("locked_margin", 0) > 0;
                if (isActive) out.put(pair, p);
            }
            if (!out.isEmpty()) {
                StringBuilder sb = new StringBuilder("=== Open positions (" + out.size() + ") ===");
                for (Map.Entry<String, JSONObject> e : out.entrySet()) {
                    JSONObject p = e.getValue();
                    sb.append(String.format("%n  %s | qty=%s | entry=%s | SL=%s | TP=%s", e.getKey(),
                            p.optString("active_pos", "0"), px(p.optDouble("avg_price", 0)),
                            px(p.optDouble("stop_loss_trigger", 0)), px(p.optDouble("take_profit_trigger", 0))));
                }
                log(sb.toString());
            }
            return out;
        } catch (Exception e) {
            logErr("fetchOpenPositions: " + e.getMessage());
            return null;
        }
    }

    private static JSONObject findPositionSafe(String pair) {
        try {
            JSONArray arr = fetchPositionsRaw();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject p = arr.getJSONObject(i);
                if (pair.equals(p.optString("pair"))) return p;
            }
        } catch (Exception e) {
            logErr("findPosition(" + pair + "): " + e.getMessage());
        }
        return null;
    }

    private static JSONObject waitForFill(String pair) {
        for (int k = 0; k < FILL_CHECK_ATTEMPTS; k++) {
            sleep(FILL_CHECK_DELAY_MS);
            JSONObject pos = findPositionSafe(pair);
            if (pos != null && pos.optDouble("avg_price", 0) > 0 && Math.abs(pos.optDouble("active_pos", 0)) > 0) return pos;
        }
        return null;
    }

    private static String getPositionIdWithRetry(String pair) {
        for (int k = 0; k < 5; k++) {
            JSONObject p = findPositionSafe(pair);
            if (p != null && p.has("id")) return p.optString("id");
            sleep(1500);
        }
        return null;
    }

    private static JSONObject placeLimitEntryOrder(Direction dir, String pair, BigDecimal qty, double refPrice, double tick) {
        try {
            boolean L = dir.isLong();
            double raw = L ? refPrice * (1 + LIMIT_ORDER_BUFFER_PCT) : refPrice * (1 - LIMIT_ORDER_BUFFER_PCT);
            BigDecimal limit = roundToTickBD(raw, tick, L ? RoundingMode.CEILING : RoundingMode.FLOOR);
            JSONObject order = new JSONObject();
            order.put("side", L ? "buy" : "sell");
            order.put("pair", pair);
            order.put("order_type", "limit_order");
            order.put("price", limit);
            order.put("total_quantity", qty);
            order.put("leverage", LEVERAGE);
            order.put("notification", "email_notification");
            order.put("time_in_force", "good_till_cancel");
            order.put("hidden", false);
            order.put("post_only", false);
            order.put("position_margin_type", MARGIN_TYPE);
            order.put("margin_currency_short_name", MARGIN_CURRENCY);
            JSONObject body = new JSONObject();
            body.put("timestamp", Instant.now().toEpochMilli());
            body.put("order", order);
            String resp = authPost(BASE_URL + "/exchange/v1/derivatives/futures/orders/create", body.toString()).trim();
            return resp.startsWith("[") ? new JSONArray(resp).getJSONObject(0) : new JSONObject(resp);
        } catch (Exception e) {
            logErr("placeLimitEntryOrder(" + pair + "): " + e.getMessage());
            return null;
        }
    }

    // NOTE: verify endpoint/payload against CoinDCX Futures "Cancel Order" docs.
    private static void cancelOrder(String orderId, String pair) {
        try {
            JSONObject body = new JSONObject();
            body.put("timestamp", Instant.now().toEpochMilli());
            body.put("id", orderId);
            String resp = authPost(BASE_URL + "/exchange/v1/derivatives/futures/orders/cancel", body.toString());
            log("  cancel remainder " + pair + " order " + orderId + " -> " + resp.trim()
                    + " (an error here usually means it was already fully filled)");
        } catch (Exception e) {
            logErr("cancelOrder(" + pair + "): " + e.getMessage());
        }
    }

    // Used ONLY when a position is found unprotected with price already past its
    // planned SL/TP — i.e. the fixed SL/TP level has effectively been hit.
    // NOTE: verify endpoint/payload against CoinDCX Futures "Exit Position" docs.
    private static void exitPositionNow(String posId, String pair) {
        try {
            JSONObject body = new JSONObject();
            body.put("timestamp", Instant.now().toEpochMilli());
            body.put("id", posId);
            String resp = authPost(BASE_URL + "/exchange/v1/derivatives/futures/positions/exit", body.toString());
            log("  [EXIT] " + pair + " -> " + resp.trim());
        } catch (Exception e) {
            logErr("exitPositionNow(" + pair + "): " + e.getMessage());
        }
    }

    private static boolean setTpSl(String posId, double tp, double sl, double tick, String pair) {
        try {
            BigDecimal rtp = roundToTickBD(tp, tick, RoundingMode.HALF_UP);
            BigDecimal rsl = roundToTickBD(sl, tick, RoundingMode.HALF_UP);
            JSONObject tpObj = new JSONObject();
            tpObj.put("stop_price", rtp);
            tpObj.put("limit_price", rtp);
            tpObj.put("order_type", "take_profit_market");
            JSONObject slObj = new JSONObject();
            slObj.put("stop_price", rsl);
            slObj.put("limit_price", rsl);
            slObj.put("order_type", "stop_market");
            JSONObject payload = new JSONObject();
            payload.put("timestamp", Instant.now().toEpochMilli());
            payload.put("id", posId);
            payload.put("take_profit", tpObj);
            payload.put("stop_loss", slObj);
            String resp = authPost(BASE_URL + "/exchange/v1/derivatives/futures/positions/create_tpsl", payload.toString()).trim();
            boolean ok = !resp.contains("err_code_dcx");
            if (!ok) logErr("  TP/SL error " + pair + ": " + resp);
            return ok;
        } catch (Exception e) {
            logErr("setTpSl(" + pair + "): " + e.getMessage());
            return false;
        }
    }

    public static double getLastPrice(String pair) {
        try {
            HttpURLConnection conn = openGet(PUBLIC_API_URL + "/market_data/trade_history?pair=" + pair + "&limit=1");
            if (conn.getResponseCode() == 200) {
                String r = readStream(conn.getInputStream()).trim();
                return r.startsWith("[") ? new JSONArray(r).getJSONObject(0).getDouble("p") : new JSONObject(r).getDouble("p");
            }
        } catch (Exception e) {
            logErr("getLastPrice(" + pair + "): " + e.getMessage());
        }
        return 0;
    }

    // =========================================================================
    // INSTRUMENTS
    // =========================================================================
    private static void refreshInstrumentCacheIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastInstrumentRefresh < INSTRUMENT_CACHE_TTL_MS && !instrumentCache.isEmpty()) return;
        try {
            log("Refreshing instrument cache...");
            JSONArray pairs = new JSONArray(publicGet(BASE_URL + "/exchange/v1/derivatives/futures/data/active_instruments"));
            Map<String, JSONObject> fresh = new HashMap<>();
            for (int i = 0; i < pairs.length(); i++) {
                String p = pairs.getString(i);
                try {
                    String raw = publicGet(BASE_URL + "/exchange/v1/derivatives/futures/data/instrument?pair=" + p);
                    fresh.put(p, new JSONObject(raw).getJSONObject("instrument"));
                } catch (Exception ignored) { }
            }
            if (!fresh.isEmpty()) {
                instrumentCache.clear();
                instrumentCache.putAll(fresh);
                lastInstrumentRefresh = now;
            }
            log("Instruments cached: " + instrumentCache.size());
        } catch (Exception e) {
            logErr("refreshInstrumentCache: " + e.getMessage());
        }
    }

    private static double getTickSize(String pair) {
        JSONObject d = instrumentCache.get(pair);
        return d != null ? d.optDouble("price_increment", 0) : 0;
    }

    // =========================================================================
    // HTTP / SIGNING
    // =========================================================================
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
        try (OutputStream os = c.getOutputStream()) {
            os.write(json.getBytes(StandardCharsets.UTF_8));
        }
        InputStream is = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
        return readStream(is);
    }

    private static String readStream(InputStream is) throws IOException {
        if (is == null) return "";
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            return br.lines().collect(Collectors.joining("\n"));
        }
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

    // =========================================================================
    // UTILITIES
    // =========================================================================
    private static BigDecimal roundToTickBD(double price, double tick, RoundingMode mode) {
        if (tick <= 0) return BigDecimal.valueOf(price);
        BigDecimal t = BigDecimal.valueOf(tick);
        int scale = Math.max(0, t.stripTrailingZeros().scale());
        return BigDecimal.valueOf(price).divide(t, 0, mode).multiply(t).setScale(scale, RoundingMode.HALF_UP);
    }

    private static double roundToTick(double price, double tick, RoundingMode mode) {
        return roundToTickBD(price, tick, mode).doubleValue();
    }

    private static int b(boolean x) { return x ? 1 : 0; }

    private static String px(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return String.valueOf(v);
        if (v == 0) return "0";
        return new BigDecimal(v).round(new MathContext(8)).stripTrailingZeros().toPlainString();
    }

    private static boolean sleep(long ms) {
        try {
            TimeUnit.MILLISECONDS.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void log(String msg)    { System.out.println("[" + TS.format(Instant.now()) + "] " + msg); }
    private static void logErr(String msg) { System.err.println("[" + TS.format(Instant.now()) + "] " + msg); }

    private static void skip(String pair, String stage, String reason) {
        if (LOG_ALL_SKIPS) log("SKIP " + pair + " @" + stage + " — " + reason);
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
// import java.time.Instant;
// import java.util.*;
// import java.util.concurrent.ConcurrentHashMap;
// import java.util.concurrent.TimeUnit;
// import java.util.stream.Collectors;
// import java.util.stream.Stream;

// public class CoinDCXFuturesTrader8C_BUY_SELL_NEW_LOGIC_THREE {

//     // =========================================================================
//     // API Configuration (unchanged)
//     // =========================================================================
//     private static final String API_KEY    = System.getenv("DELTA_API_KEY");
//     private static final String API_SECRET = System.getenv("DELTA_API_SECRET");
//     private static final String BASE_URL       = "https://api.coindcx.com";
//     private static final String PUBLIC_API_URL = "https://public.coindcx.com";

//     private static final int LEVERAGE = 5;

//     private static final int MAX_ENTRY_PRICE_CHECKS = 20;
//     private static final int ENTRY_CHECK_DELAY_MS    = 1000;

//     private static final int  TPSL_MAX_RETRIES    = 3;
//     private static final long TPSL_RETRY_DELAY_MS = 2000L;

//     private static final long TICK_CACHE_TTL_MS = 3_600_000L;

//     private static final int MAX_OPEN_POSITIONS = 12;

//     private static final int  POSITION_ID_MAX_RETRIES = 5;
//     private static final long POSITION_ID_RETRY_DELAY_MS = 1500L;

//     // =========================================================================
//     // EARLY-EXIT SYSTEM — single on/off switch. Flip this to true/false to
//     // enable/disable the entire 3-level early-exit layer without touching
//     // anything else. When false, positions exit purely via TP/SL/trailing,
//     // exactly like before.
//     // =========================================================================
//     private static final boolean EARLY_EXIT_ENABLED = false;

//     // =========================================================================
//     // Fixes for early-exit closing positions too fast / too often.
//     //  1. GRACE PERIOD — a trade gets EARLY_EXIT_GRACE_BARS_5M worth of 5M
//     //     candles (measured from actual fill time) before early-exit is
//     //     allowed to act at all.
//     //  2. SCORE-BASED 15M CONFIRMATION — all 4 signals (ST flip,
//     //     close-below-EMA21, structure break, slope lost) are combined into a
//     //     0-4 score, and EARLY_EXIT_15M_MIN_SIGNALS of them must agree.
//     // =========================================================================
//     private static final int EARLY_EXIT_GRACE_BARS_5M     = 3; // ~15 minutes grace after entry before early-exit can act
//     private static final int EARLY_EXIT_15M_MIN_SIGNALS   = 3; // out of 4 sub-signals required

//     // =========================================================================
//     // Indicator periods (unchanged — no new indicators added)
//     // =========================================================================
//     private static final int EMA_FAST = 9;
//     private static final int EMA_MID  = 21;
//     private static final int ATR_PERIOD = 14;
//     private static final int ST_PERIOD     = 10;
//     private static final double ST_MULTIPLIER = 3.0;
//     private static final int RSI_PERIOD = 14;
//     private static final int VOLUME_MA_PERIOD = 20;
//     private static final int VWAP_LOOKBACK = 20;

//     private static final String RES_5M = "5";
//     private static final String RES_1H = "60";

//     private static final int BASE_5M_FETCH_COUNT = 450;
//     private static final int GROUP_15M_FROM_5M = 3;
//     private static final int GROUP_30M_FROM_5M = 6;
//     private static final int BASE_1H_FETCH_COUNT = 90;

//     private static final int EMA_SLOPE_LOOKBACK_BARS   = 5;
//     private static final double HTF_EMA_SLOPE_MIN_ATR   = 0.10;
//     private static final double ENTRY_EMA_SLOPE_MIN_ATR = 0.15;

//     private static final int STRUCTURE_SWING_LOOKBACK = 30;
//     private static final int SL_SWING_LOOKBACK         = 20;
//     private static final int TP_LEVEL_LOOKBACK         = 40;

//     // =========================================================================
//     // 1H macro direction / 30M confirmation, scored out of 6.
//     // =========================================================================
//     private static final int MACRO_1H_MIN_SCORE     = 4;
//     private static final int CONFIRM_30M_STRONG_MIN = 5;
//     private static final int CONFIRM_30M_ACCEPTABLE = 4;
//     private static final int CLEAN_15M_MIN_FOR_ACCEPTABLE = 4;

//     // =========================================================================
//     // 15M setup, scored out of 5.
//     // =========================================================================
//     private static final int SETUP_15M_MIN_SCORE = 3;

//     // =========================================================================
//     // 5M pullback + rejection (mandatory) + slope (mandatory).
//     // =========================================================================
//     private static final double PULLBACK_MAX_ATR       = 1.5;
//     private static final double OVEREXTENSION_SKIP_ATR  = 2.0;
//     private static final double CANDLE_BODY_RATIO_MIN   = 0.40;

//     // =========================================================================
//     // 5M supporting confirmation score out of 5 (min 3/5).
//     // =========================================================================
//     private static final int    ENTRY_CONFIRMATION_MIN_SCORE = 3;
//     private static final double RSI_LONG_MIN  = 40, RSI_LONG_MAX  = 70;
//     private static final double RSI_SHORT_MIN = 30, RSI_SHORT_MAX = 60;

//     // =========================================================================
//     // Pending breakout-entry signal.
//     // =========================================================================
//     private static final long SIGNAL_MAX_VALID_MS = 15L * 60 * 1000L; // 15 minutes

//     // =========================================================================
//     // Structural SL: anchored to the pullback swing captured when the signal
//     // was ARMED (not re-searched at breakout time), + ATR buffer, then
//     // CLAMPED into [SL_MIN_PERCENT, SL_MAX_PERCENT].
//     //
//     // CHANGED (v2): the clamp is now only used to WIDEN a too-tight SL up to
//     // SL_MIN_PERCENT (noise floor). If the RAW structural SL is wider than
//     // SL_MAX_PERCENT (or farther than MAX_STRUCTURAL_SL_ATR), the trade is
//     // SKIPPED instead of force-clamping the SL inside the structure — see
//     // structuralSlTooWide(). The upper clamp in slTpFromLevel() now only
//     // matters for the safety-sweep / reconstruction path.
//     // =========================================================================
//     private static final double SL_BUFFER_ATR   = 0.35; // buffer off the swing
//     private static final double SL_MIN_PERCENT  = 1.0;  // CHANGED: was 3.0 — noise floor
//     private static final double SL_MAX_PERCENT  = 2.5;  // CHANGED: was 4.0 — structure wider than this -> trade skipped
//     private static final double SL_HARD_PERCENT_CAP  = 8.0;  // CHANGED: was 6.0 — fallback ONLY (ATR unavailable); 6% @12x = 72% of margin

//     // If the raw structural SL distance (before the clamp above) exceeds this
//     // many ATRs, the entry location is treated as poor and the trade is
//     // skipped entirely rather than clamped.
//     private static final double MAX_STRUCTURAL_SL_ATR = 2.5;

//     // =========================================================================
//     // RR-based TP. CHANGED (v2): TP is now a far CEILING, not the normal exit.
//     // Previously RR_DEFAULT = 0.75 meant the exchange TP closed the FULL
//     // position at 0.75R — before partial booking (0.9R), stage-2 (1R),
//     // stage-3 (2R) or TP extension could ever trigger. Those features were
//     // effectively dead code. With TP at 3R/4R, the full lifecycle runs:
//     //   0.6R lock -> 0.9R partial -> 1R stage-2 trail -> 2R stage-3 trail
//     //   -> ~2.7R+ TP extension. Most trades exit via the trailing SL.
//     // =========================================================================
//     private static final double RR_DEFAULT = 1.6;  // CHANGED: was 0.75
//     private static final double RR_STRONG  = 2.2;  // CHANGED: was 0.9 — used only for a clean 30M=6/6 setup

//     // NEW (v2): tpBlockedByLevel() used to check for resistance/support
//     // along 70% of the path to TP. With TP now at 3R that would block almost
//     // every trade. The check now uses a fixed virtual target of
//     // TP_BLOCK_CHECK_R x risk, so (x 0.7 inside the check) it looks for
//     // obstacles up to ~0.9R — i.e. up to the partial-booking zone.
//     private static final double TP_BLOCK_CHECK_R = 1.3;

//     // =========================================================================
//     // Trailing system — 4 stages, measured in R-multiples
//     // (move-in-favor / initialRisk).
//     // =========================================================================
//     private static final double BREAKEVEN_TRIGGER_R    = 0.60; // R
//     // Lock is a FRACTION of the move already made (moveInFavor), so it grows
//     // every cycle the position stays in stage>=1.
//     private static final double BREAKEVEN_LOCK_R_FRACTION = 0.35; // lock 35% of the move-in-favor as profit
//     private static final double TRAIL_STAGE2_TRIGGER_R = 1.00;
//     private static final double TRAIL_STAGE2_ATR        = 2.00;
//     private static final double TRAIL_STAGE3_TRIGGER_R = 2.00;
//     private static final double TRAIL_STAGE3_ATR        = 1.50;
//     private static final double MIN_SL_IMPROVEMENT_ATR  = 0.10; // don't spam the API on tiny moves

//     // =========================================================================
//     // Partial profit booking. Independent of the SL trailing above: once
//     // price has moved PARTIAL_BOOKING_TRIGGER_R in favor, close
//     // PARTIAL_BOOKING_CLOSE_FRACTION of the position at market, and let the
//     // remaining quantity keep running under the trailing/TP system.
//     // =========================================================================
//     private static final boolean PARTIAL_BOOKING_ENABLED = false;
//     private static final double  PARTIAL_BOOKING_TRIGGER_R      = 0.90;
//     private static final double  PARTIAL_BOOKING_CLOSE_FRACTION = 0.33;

//     // =========================================================================
//     // TP extension — only past stage 3, only while the trend is still valid,
//     // and only once price has covered TP_EXTENSION_TRIGGER_FRACTION of the
//     // distance to the (fixed) original TP.
//     // =========================================================================
//     private static final int    MAX_TP_EXTENSIONS = 6;
//     private static final double TP_EXTENSION_ATR  = 1.5;
//     private static final double TP_EXTENSION_TRIGGER_FRACTION = 0.90;

//     // =========================================================================
//     // Master on/off switches:
//     //   RISK_BASED_SIZING_ENABLED = false -> fixed MAX_MARGIN_CAP notional
//     //       for every trade (SL distance ignored).
//     //   PARTIAL_BOOKING_ENABLED   (above)
//     //   TRAILING_ENABLED          = false -> initial SL/TP kept for the whole
//     //       trade (no lock, no trail, no TP extension).
//     // =========================================================================
//     private static final boolean RISK_BASED_SIZING_ENABLED = false;
//     private static final boolean TRAILING_ENABLED          = false;

//     // =========================================================================
//     // Position sizing. Risk-based (account risk %) when enabled, always
//     // hard-capped at MAX_MARGIN_CAP notional (~100rs margin @12x leverage).
//     // =========================================================================
//     private static final double ACCOUNT_BALANCE          = 2000.0; // total account balance (INR)
//     private static final double RISK_PER_TRADE_PERCENT    = 2.0;    // risk 2% of account (~40rs) per trade
//     private static final double MAX_MARGIN_CAP            = 2100.0; // hard notional ceiling (INR)
//     private static final double USDT_INR_RATE             = 102.0;

//     private static final double LIMIT_ORDER_BUFFER_PCT = 0.0005;

//     private static final long SCALP_COOLDOWN_MS            = 15 * 60 * 1000L;
//     private static final long SCALP_ENTRY_SCAN_INTERVAL_MS = 20 * 1000L;

//     private static class PendingSignal {
//         boolean isLong;
//         double confirmHigh, confirmLow;
//         double setupSwingLevel; // the pullback swing captured AT ARM TIME — never re-searched later
//         boolean strongTrend;    // 30M scored a clean 6/6 at arm time -> eligible for RR_STRONG
//         long   createdAtMs;
//     }
//     private static final Map<String, PendingSignal> pendingSignals = new ConcurrentHashMap<>();

//     private static class TrailInfo {
//         boolean isLong;
//         double entryPrice;
//         double initialSL, initialTP, initialRisk;
//         double currentSL, currentTP;
//         double peakPrice;
//         int    stage;           // 0..3
//         int    extensionsUsed;
//         long   lastTrailCandleTime; // 5M candle timestamp when the structure+ATR hybrid trail was last evaluated
//         double  originalQty;         // full position quantity at entry, used to size the partial-booking close
//         boolean partialBookingDone;  // true once the partial-profit slice has been closed for this trade
//         long    entryTimeMs;         // wall-clock fill time, used for the early-exit grace period
//     }
//     private static final Map<String, TrailInfo> trailState = new ConcurrentHashMap<>();

//     private static final Map<String, JSONObject> instrumentCache = new ConcurrentHashMap<>();
//     private static long lastCacheUpdate = 0;
//     private static final Map<String, Long> lastTradeTime = new ConcurrentHashMap<>();

//     private static final String[] COIN_SYMBOLS = {
//        "PIEVERSE","XAU","APE","ERA","US","RAVE","EDEN","LIT","BREV","MAGMA","BLESS","ZAMA",
//         "FRAX","ACU","1000FLOKI","ELSA","LINEA","SPACE","CLO","FIGHT","UMA","MEGA","MAV","TRIA",
//         "YGG","OPN","ROBO","SUI","GLM","MANTRA","SEI","CAKE","AUCTION","SENT","BSB","BASED","IRYS",
//         "ACE","WET","CL","PRL","GENIUS","WIF","MANTA","LSK","AIGENSYN","PHAROS","JUP","AXL","BOME",
//         "SLX","ZEST","AIOT","VVV","CAP","DATAIP","GRVT","TAO","BR","TURBO","BTC","ETH","ZK","LISTA",
//         "A","LTC","XAG","COAI","HANA","ZRO","SKYAI","COPPER","RARE","ETC","M","AKE","XLM","PIXEL",
//         "XAN","ADA","CROSS","XMR","G","DASH","ZEC","ATOM","TRUTH","BCH","NEO","IOST","FLUX","ALGO",
//         "ZRX","COMP","WLFI","POL","DOGE","BAND","OPG","FIDA","PROM","SANTOS","RLC","1000000MOG",
//         "GRASS","PNUT","TRB","KAIA","ARX","XAI","S","4","COTI","CHR","SOLV","SAGA","ORCA","1000LUNC",
//         "MOVE","VIRTUAL","ME","IOTX","GIGGLE","AVA","VELODROME","AIXBT","KMNO","LA","DEXE","ZBT",
//         "GRIFFAIN","BLUAI","CTSI","ROSE","TURTLE","IMX","SUN","APR","TA","ON","BIO","COOKIE",
//         "AVAAI","DOT","TRUMP","MELANIA","GMT","FLOCK","CLANKER","CYS","SUSHI","VTHO","DIA",
//         "SLP","GOAT","BMT","KGEN","GWEI","MUBARAK","LDO","ESP","DRIFT","FORM","PLUME","NIL",
//         "UNI","ZORA","RECALL","INIT","BZ","PARTI","NATGAS","SPX","BANK","AVAX","RIVER","BILL",
//         "ATH","XRP","KERNEL","JST","PUNDIX","HAEDAL","ALPINE","SOON","SOPH","HUMA","TRX","LINK",
//         "HYPE","HIVE","TAIKO","TAG","MYX","NEWT","AIN","USUAL","PUMP","ICNT","BNB","H","BAT",
//         "QTUM","ARC","AIO","BEAT","BTR","ALCH","THETA","VELVET","ARIA","PTB","UB","LIGHT","FF",
//         "EVAAI","GMX","LYN","TAC","LAB","ENJ","AT","MMT","UAI","AAVE","JCT","KSM","HEI","JASMY",
//         "NEAR","TST","SOL","OP","PLAY","INJ","STG","HOLO","ASR","B","LUNA2","RSR","INX","KAT",
//         "ICP","QNT","MAGIC","T","MINA","STX","ACH","LQTY","ID","GRT","NEIRO","XVS","1INCH","SAND",
//         "ANKR","RVN","SFP","KAVA","MANA","HBAR","ARB","MTL","C98","TUT","SIREN","MASK","1000XEC",
//         "AR","ARPA","FIL","LPT","ENS","PEOPLE","LUMIA","DUSK","FLOW","XVG","ARKM","POPCAT","ARK",
//         "MOODENG","SAFE","AXS","BICO","BIGTIME","WAXP","GAS","POWR","TIA","CHIP","STO","ORDI",
//         "BEAMX","1000BONK","PYTH","ETHW","1000RATS","ANIME","OPEN","DYM","BERA","PORTAL","BB",
//         "BANANAS31","CFX","SSV","TNSR","EDU","JELLYJELLY","BLUR","WAL","FHE","WCT","DEEP","SXT",
//         "NAORIS","OG","CVC","AWE","O","BEL","JOE","SQD","1000PEPE","CARV","FET","SAPIEN","MEME",
//         "AVNT","XPIN","ILV","KAS","BNT","STBL","BSV","RIF","SUPER","USTC","METIS","ETHFI","ENA",
//         "1MBABYDOGE","CATI","HMSTR","GPS","SHELL","KAITO","ACT","RPL","BAN","THE","AKT","MORPHO",
//         "CHILLGUY","AERO","MOCA","PENGU","PHA","RED","EPIC","TREE","1000CAT","MAVIA","FARTCOIN",
//         "PAXG","IN","ORDER","VET","ZEN","STABLE","CHZ","NIGHT","NOM","ZKP","SKR","GRAM","BIRB",
//         "CTR","KNC","ZIL","YFI","EGLD","RUNE","ASTR","ONE","1000SHIB","API3","SPELL","WOO","APT",
//         "PENDLE","AGLD","CYBER","CKB","ONG","MOVR","POLYX","TWT","STEEM","ALT","ZETA","REZ","RENDER",
//         "RONIN","STRK","W","SCR","CETUS","IO","MEW","SWARMS","SONIC","PIPPIN","PROMPT","MERL","F",
//         "ESPORTS","PROVE","XNY","USELESS","HEMI","Q","SKY","ZKC","FLUID","MITO","CFG","EDGE","RE",
//         "YB","MET","DOS","FOGO","BTW","ALLO","BROCCOLI714","HYPER","XPL","RESOLV","ASTER","KITE",
//         "SIGN","HOME","MON","CC","SAHARA","MIRA","EUL","TOWNS","SYRUP","C","DOLO","ALICE","BABY",
//         "SOMI","NOT","BARD","SPK","POWER","2Z","BANANA","ENSO","SYN","NXPC","GUN","XTZ","ONT","SKL",
//         "HOT","JTO","DOGS","EIGEN","GTC","GALA","NMR","CGPT","ZEREBRO","VANA","OGN","CELO","USDC",
//         "COW","0G","IOTA","SNX","DYDX","WLD","1000SATS","ONDO","AEVO","BRETT","LAYER","CRV","TLM","KOMA"
//     };

//     private static final Set<String> INTEGER_QTY_PAIRS = Stream.of(COIN_SYMBOLS)
//             .flatMap(s -> Stream.of("B-" + s + "_USDT", s + "_USDT"))
//             .collect(Collectors.toCollection(HashSet::new));

//     private static final String[] COINS_TO_TRADE = Stream.of(COIN_SYMBOLS)
//             .map(s -> "B-" + s + "_USDT")
//             .toArray(String[]::new);

//     // =========================================================================
//     // Market structure (HH/HL vs LL/LH). Returns +1 bullish, -1 bearish, 0.
//     // =========================================================================
//     private static int detectSwingStructure(double[] hi, double[] lo, int lookback) {
//         int n = hi.length;
//         if (n < lookback + 3) return 0;
//         int start = Math.max(1, n - lookback);
//         List<Integer> swingHighIdx = new ArrayList<>();
//         List<Integer> swingLowIdx  = new ArrayList<>();
//         for (int i = start; i < n - 1; i++) {
//             if (hi[i] > hi[i - 1] && hi[i] > hi[i + 1]) swingHighIdx.add(i);
//             if (lo[i] < lo[i - 1] && lo[i] < lo[i + 1]) swingLowIdx.add(i);
//         }
//         boolean hh = false, hl = false, ll = false, lh = false;
//         if (swingHighIdx.size() >= 2) {
//             double h1 = hi[swingHighIdx.get(swingHighIdx.size() - 2)];
//             double h2 = hi[swingHighIdx.get(swingHighIdx.size() - 1)];
//             hh = h2 > h1; lh = h2 < h1;
//         }
//         if (swingLowIdx.size() >= 2) {
//             double l1 = lo[swingLowIdx.get(swingLowIdx.size() - 2)];
//             double l2 = lo[swingLowIdx.get(swingLowIdx.size() - 1)];
//             hl = l2 > l1; ll = l2 < l1;
//         }
//         if (hh && hl) return 1;
//         if (ll && lh) return -1;
//         return 0;
//     }

//     private static double findRecentSwingLow(double[] lo, int lookback) {
//         int n = lo.length;
//         int start = Math.max(1, n - lookback);
//         for (int i = n - 2; i >= start; i--) {
//             if (lo[i] < lo[i - 1] && lo[i] < lo[i + 1]) return lo[i];
//         }
//         int fallbackStart = Math.max(0, n - 8);
//         double min = Double.POSITIVE_INFINITY;
//         for (int i = fallbackStart; i < n; i++) min = Math.min(min, lo[i]);
//         return min;
//     }

//     private static double findRecentSwingHigh(double[] hi, int lookback) {
//         int n = hi.length;
//         int start = Math.max(1, n - lookback);
//         for (int i = n - 2; i >= start; i--) {
//             if (hi[i] > hi[i - 1] && hi[i] > hi[i + 1]) return hi[i];
//         }
//         int fallbackStart = Math.max(0, n - 8);
//         double max = Double.NEGATIVE_INFINITY;
//         for (int i = fallbackStart; i < n; i++) max = Math.max(max, hi[i]);
//         return max;
//     }

//     // Only a MEANINGFUL, CLOSE obstacle blocks the trade — a level sitting
//     // right next to the target itself is not treated as blocking.
//     // NOTE (v2): callers now pass a virtual target (TP_BLOCK_CHECK_R x risk)
//     // rather than the real far-away TP.
//     private static boolean tpBlockedByLevel(boolean isLong, double entry, double tp, double[] hi, double[] lo, int lookback) {
//         double path = Math.abs(tp - entry);
//         double nearThreshold = isLong ? entry + 0.7 * path : entry - 0.7 * path;
//         int n = isLong ? hi.length : lo.length;
//         int start = Math.max(1, n - lookback);
//         if (isLong) {
//             for (int i = n - 2; i >= start; i--) {
//                 if (hi[i] > hi[i - 1] && hi[i] > hi[i + 1]) {
//                     double level = hi[i];
//                     if (level > entry && level <= nearThreshold) return true;
//                 }
//             }
//         } else {
//             for (int i = n - 2; i >= start; i--) {
//                 if (lo[i] < lo[i - 1] && lo[i] < lo[i + 1]) {
//                     double level = lo[i];
//                     if (level < entry && level >= nearThreshold) return true;
//                 }
//             }
//         }
//         return false;
//     }

//     private static class ScoredDirection {
//         boolean valid;
//         int bullScore, bearScore;
//     }

//     private static ScoredDirection analyzeScored6(JSONArray candles) {
//         ScoredDirection r = new ScoredDirection();
//         if (candles == null || candles.length() < EMA_MID + Math.max(ATR_PERIOD, ST_PERIOD) + STRUCTURE_SWING_LOOKBACK) {
//             r.valid = false;
//             return r;
//         }
//         double[] cl = extractCloses(candles);
//         double[] hi = extractHighs(candles);
//         double[] lo = extractLows(candles);

//         double ema9  = calcEMA(cl, EMA_FAST);
//         double ema21 = calcEMA(cl, EMA_MID);
//         double atr   = calcATR(hi, lo, cl, ATR_PERIOD);
//         double price = cl[cl.length - 1];

//         boolean[] stSeries = calcSupertrend(hi, lo, cl, ST_PERIOD, ST_MULTIPLIER);
//         boolean stGreen = stSeries[stSeries.length - 1];

//         double[] ema9Series = calcEMASeries(cl, EMA_FAST);
//         int n = ema9Series.length;
//         int lookback = Math.min(EMA_SLOPE_LOOKBACK_BARS, n - 1);
//         double emaSlope = ema9Series[n - 1] - ema9Series[n - 1 - lookback];
//         boolean slopeUp   = atr > 0 && emaSlope >= HTF_EMA_SLOPE_MIN_ATR * atr;
//         boolean slopeDown = atr > 0 && emaSlope <= -HTF_EMA_SLOPE_MIN_ATR * atr;

//         int structure = detectSwingStructure(hi, lo, STRUCTURE_SWING_LOOKBACK);

//         int bull = 0, bear = 0;
//         if (ema9 > ema21) bull++; else if (ema9 < ema21) bear++;
//         if (price > ema9) bull++; else bear++;
//         if (price > ema21) bull++; else bear++;
//         if (stGreen) bull++; else bear++;
//         if (slopeUp) bull++; else if (slopeDown) bear++;
//         if (structure == 1) bull++; else if (structure == -1) bear++;

//         r.valid = true;
//         r.bullScore = bull;
//         r.bearScore = bear;
//         return r;
//     }

//     private static class Setup15Result {
//         boolean valid;
//         int bullScore, bearScore;
//         double stLower, stUpper;
//     }

//     private static Setup15Result analyzeSetup15M(JSONArray candles) {
//         Setup15Result r = new Setup15Result();
//         if (candles == null || candles.length() < EMA_MID + Math.max(ATR_PERIOD, ST_PERIOD)) {
//             r.valid = false;
//             return r;
//         }
//         double[] cl = extractCloses(candles);
//         double[] hi = extractHighs(candles);
//         double[] lo = extractLows(candles);

//         double ema9  = calcEMA(cl, EMA_FAST);
//         double ema21 = calcEMA(cl, EMA_MID);
//         double atr   = calcATR(hi, lo, cl, ATR_PERIOD);
//         double price = cl[cl.length - 1];

//         boolean[] stSeries = calcSupertrend(hi, lo, cl, ST_PERIOD, ST_MULTIPLIER);
//         boolean stGreen = stSeries[stSeries.length - 1];
//         double[] bands = calcSupertrendBands(hi, lo, cl, ST_PERIOD, ST_MULTIPLIER);
//         r.stLower = bands[0];
//         r.stUpper = bands[1];

//         double[] ema9Series = calcEMASeries(cl, EMA_FAST);
//         int n = ema9Series.length;
//         int lookback = Math.min(EMA_SLOPE_LOOKBACK_BARS, n - 1);
//         double emaSlope = ema9Series[n - 1] - ema9Series[n - 1 - lookback];
//         boolean slopeUp   = atr > 0 && emaSlope >= HTF_EMA_SLOPE_MIN_ATR * atr;
//         boolean slopeDown = atr > 0 && emaSlope <= -HTF_EMA_SLOPE_MIN_ATR * atr;

//         int bull = 0, bear = 0;
//         if (ema9 > ema21) bull++; else if (ema9 < ema21) bear++;
//         if (price > ema9) bull++; else bear++;
//         if (stGreen) bull++; else bear++;
//         if (slopeUp) bull++; else if (slopeDown) bear++;
//         if (price > ema9 && price > ema21) bull++; else if (price < ema9 && price < ema21) bear++;

//         r.valid = true;
//         r.bullScore = bull;
//         r.bearScore = bear;
//         return r;
//     }

//     private static class EntryResult {
//         boolean valid;
//         boolean setupFound;
//         double confirmHigh, confirmLow;
//         double atr5m;
//         double distanceAtr;
//         String reason;
//     }

//     private static EntryResult analyzeEntry5M(JSONArray raw5m, boolean trendUp) {
//         EntryResult t = new EntryResult();
//         int minBars = EMA_MID + Math.max(ATR_PERIOD, Math.max(RSI_PERIOD, VOLUME_MA_PERIOD)) + 5;
//         if (raw5m == null || raw5m.length() < minBars) {
//             t.valid = false;
//             return t;
//         }

//         double[] cl  = extractCloses(raw5m);
//         double[] op  = extractOpens(raw5m);
//         double[] hi  = extractHighs(raw5m);
//         double[] lo  = extractLows(raw5m);
//         double[] vol = extractVolumes(raw5m);
//         int n = cl.length;

//         double ema9  = calcEMA(cl, EMA_FAST);
//         double ema21 = calcEMA(cl, EMA_MID);
//         double atr5m = calcATR(hi, lo, cl, ATR_PERIOD);
//         t.atr5m = atr5m;

//         double entryClose = cl[n - 1], entryOpen = op[n - 1];
//         double entryHigh  = hi[n - 1], entryLow  = lo[n - 1];

//         double nearestEmaDist = Math.min(Math.abs(entryClose - ema9), Math.abs(entryClose - ema21));
//         double distanceAtr = atr5m > 0 ? nearestEmaDist / atr5m : 0;
//         t.distanceAtr = distanceAtr;
//         boolean pulledBack = distanceAtr <= PULLBACK_MAX_ATR;

//         boolean directionalCandle = trendUp ? (entryClose > entryOpen) : (entryClose < entryOpen);
//         double body  = Math.abs(entryClose - entryOpen);
//         double range = entryHigh - entryLow;
//         boolean notDoji = range > 0 && (body / range) >= CANDLE_BODY_RATIO_MIN;

//         double closePositionInRange = range > 0
//                 ? (trendUp ? (entryClose - entryLow) / range : (entryHigh - entryClose) / range)
//                 : 0;
//         boolean rejectionOk = closePositionInRange >= 0.60;

//         double[] ema9Series5m = calcEMASeries(cl, EMA_FAST);
//         int lookback5m = Math.min(EMA_SLOPE_LOOKBACK_BARS, n - 1);
//         double emaSlope5m = ema9Series5m[n - 1] - ema9Series5m[n - 1 - lookback5m];
//         boolean slope5mOk = trendUp
//                 ? (atr5m > 0 && emaSlope5m >= ENTRY_EMA_SLOPE_MIN_ATR * atr5m)
//                 : (atr5m > 0 && emaSlope5m <= -ENTRY_EMA_SLOPE_MIN_ATR * atr5m);

//         boolean mandatoryOk = pulledBack && directionalCandle && notDoji && rejectionOk && slope5mOk;

//         int volStart = Math.max(0, n - 1 - VOLUME_MA_PERIOD);
//         double avgVol = 0; int cnt = 0;
//         for (int i = volStart; i < n - 1; i++) { avgVol += vol[i]; cnt++; }
//         avgVol = cnt > 0 ? avgVol / cnt : 0;
//         boolean volumeOk = avgVol > 0 && vol[n - 1] >= avgVol;

//         double rsi = calcRSI(cl, RSI_PERIOD);
//         boolean rsiOk = trendUp
//                 ? (rsi >= RSI_LONG_MIN && rsi <= RSI_LONG_MAX)
//                 : (rsi >= RSI_SHORT_MIN && rsi <= RSI_SHORT_MAX);

//         int vwapStart = Math.max(0, n - VWAP_LOOKBACK);
//         double cumPV = 0, cumV = 0;
//         for (int i = vwapStart; i < n; i++) {
//             double typical = (hi[i] + lo[i] + cl[i]) / 3.0;
//             cumPV += typical * vol[i];
//             cumV  += vol[i];
//         }
//         double vwap = cumV > 0 ? cumPV / cumV : entryClose;
//         boolean vwapOk = trendUp ? entryClose >= vwap : entryClose <= vwap;

//         boolean[] stSeries5m = calcSupertrend(hi, lo, cl, ST_PERIOD, ST_MULTIPLIER);
//         boolean st5mOk = trendUp ? stSeries5m[stSeries5m.length - 1] : !stSeries5m[stSeries5m.length - 1];

//         boolean emaAlignOk = trendUp ? (entryClose > ema9 && entryClose > ema21) : (entryClose < ema9 && entryClose < ema21);

//         int confirmationScore = (volumeOk ? 1 : 0) + (rsiOk ? 1 : 0) + (vwapOk ? 1 : 0)
//                 + (st5mOk ? 1 : 0) + (emaAlignOk ? 1 : 0);

//         t.setupFound = mandatoryOk && confirmationScore >= ENTRY_CONFIRMATION_MIN_SCORE;
//         t.confirmHigh = entryHigh;
//         t.confirmLow  = entryLow;
//         t.valid = true;
//         t.reason = String.format(
//                 "pullback=%.2fATR(ok=%s) rejection=%s(pos=%.2f) directional=%s notDoji=%s slope=%s vol=%s rsi=%.1f(ok=%s) vwap=%s st5m=%s emaAlign=%s score=%d/5",
//                 distanceAtr, pulledBack, rejectionOk, closePositionInRange, directionalCandle, notDoji, slope5mOk,
//                 volumeOk, rsi, rsiOk, vwapOk, st5mOk, emaAlignOk, confirmationScore);
//         return t;
//     }

//     private static JSONArray dropLastIfForming(JSONArray arr) {
//         if (arr == null || arr.length() < 2) return arr;
//         JSONArray out = new JSONArray();
//         for (int i = 0; i < arr.length() - 1; i++) out.put(arr.getJSONObject(i));
//         return out;
//     }

//     // Shared SL/TP construction from a given anchor level. The final SL
//     // distance is CLAMPED into [SL_MIN_PERCENT, SL_MAX_PERCENT] of the entry
//     // price. Callers on the live-entry path call structuralSlTooWide() FIRST
//     // and skip the trade if it returns true.
//     private static double[] slTpFromLevel(boolean isLong, double entryPrice, double swingLevel,
//                                            double atr, double tickSize, boolean strongTrend) {
//         double sl;
//         if (atr > 0) {
//             sl = isLong ? swingLevel - SL_BUFFER_ATR * atr : swingLevel + SL_BUFFER_ATR * atr;
//         } else {
//             sl = isLong ? entryPrice * (1 - SL_MAX_PERCENT / 100.0) : entryPrice * (1 + SL_MAX_PERCENT / 100.0);
//         }

//         // If the structural side ended up wrong, fall back to a plain
//         // percentage SL on the correct side.
//         boolean slSideValid = isLong ? sl < entryPrice : sl > entryPrice;
//         if (!slSideValid) {
//             sl = isLong ? entryPrice * (1 - SL_MAX_PERCENT / 100.0) : entryPrice * (1 + SL_MAX_PERCENT / 100.0);
//         }

//         double slPercent = Math.abs(entryPrice - sl) / entryPrice * 100.0;
//         if (slPercent < SL_MIN_PERCENT) {
//             sl = isLong ? entryPrice * (1 - SL_MIN_PERCENT / 100.0) : entryPrice * (1 + SL_MIN_PERCENT / 100.0);
//         } else if (slPercent > SL_MAX_PERCENT) {
//             sl = isLong ? entryPrice * (1 - SL_MAX_PERCENT / 100.0) : entryPrice * (1 + SL_MAX_PERCENT / 100.0);
//         }

//         double rrTarget = strongTrend ? RR_STRONG : RR_DEFAULT;
//         double risk = Math.abs(entryPrice - sl);
//         double tp = isLong ? entryPrice + rrTarget * risk : entryPrice - rrTarget * risk;

//         sl = roundToTick(sl, tickSize);
//         tp = roundToTick(tp, tickSize);
//         double finalSlPercent = Math.abs(entryPrice - sl) / entryPrice * 100.0;
//         return new double[]{sl, tp, finalSlPercent, rrTarget};
//     }

//     // CHANGED (v2) — decides whether the entry location itself is poor. The
//     // trade is skipped if the RAW structural SL (before clamping) is either
//     // farther than MAX_STRUCTURAL_SL_ATR, OR wider than SL_MAX_PERCENT. The
//     // second check replaces the old behaviour of force-clamping a wide
//     // structural SL down to SL_MAX_PERCENT (which put the SL INSIDE the
//     // structure, where normal noise would hit it).
//     private static boolean structuralSlTooWide(boolean isLong, double entryPrice, double swingLevel, double atr) {
//         if (atr <= 0) return false; // can't judge — let the ATR-unavailable fallback path handle it
//         double rawSl = isLong ? swingLevel - SL_BUFFER_ATR * atr : swingLevel + SL_BUFFER_ATR * atr;

//         boolean sideValid = isLong ? rawSl < entryPrice : rawSl > entryPrice;
//         if (!sideValid) return false; // wrong side -> slTpFromLevel's fallback handles it

//         double dist = Math.abs(entryPrice - rawSl);
//         if (dist / atr > MAX_STRUCTURAL_SL_ATR) return true;

//         // NEW: structure farther than SL_MAX_PERCENT -> skip instead of clamping
//         double rawSlPercent = dist / entryPrice * 100.0;
//         return rawSlPercent > SL_MAX_PERCENT;
//     }

//     // Fresh swing search — only used when there is no captured setup anchor
//     // (safety sweep on an externally/pre-existing position, or a restart).
//     private static double[] computeFreshStructuralSlTp(boolean isLong, double entryPrice,
//                                                          double[] hi5m, double[] lo5m, double atr,
//                                                          double tickSize, boolean strongTrend) {
//         double swingLevel = isLong ? findRecentSwingLow(lo5m, SL_SWING_LOOKBACK) : findRecentSwingHigh(hi5m, SL_SWING_LOOKBACK);
//         return slTpFromLevel(isLong, entryPrice, swingLevel, atr, tickSize, strongTrend);
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

//     // Risk-based sizing (when enabled), always capped at MAX_MARGIN_CAP
//     // notional.
//     private static double calcQuantity(double price, double slPrice, String pair) {
//         double capBasedQty = MAX_MARGIN_CAP / (price * USDT_INR_RATE);

//         if (!RISK_BASED_SIZING_ENABLED) {
//             return roundQtyForPair(capBasedQty, pair);
//         }

//         double stopDistance = Math.abs(price - slPrice);
//         if (stopDistance <= 0) return 0;

//         double riskAmountInr = ACCOUNT_BALANCE * (RISK_PER_TRADE_PERCENT / 100.0);
//         double riskBasedQty  = riskAmountInr / (stopDistance * USDT_INR_RATE);

//         double qty = Math.min(riskBasedQty, capBasedQty);
//         return roundQtyForPair(qty, pair);
//     }

//     // Shared quantity-rounding logic (used by calcQuantity and the
//     // partial-booking close).
//     private static double roundQtyForPair(double qty, String pair) {
//         double finalQty = INTEGER_QTY_PAIRS.contains(pair) ? Math.floor(qty) : Math.floor(qty * 100) / 100.0;
//         return Math.max(finalQty, 0);
//     }

//     public static void main(String[] args) {
//         System.out.println("=== Bot starting (trend-continuation cascade + structure-skip SL + hybrid structure/ATR trailing "
//                 + "+ R-multiple staging + candle-gated trail + early-exit=" + EARLY_EXIT_ENABLED + ") ===");
//         System.out.println("=== Config: SL=[" + SL_MIN_PERCENT + "%, " + SL_MAX_PERCENT + "%] RR=" + RR_DEFAULT + "/" + RR_STRONG
//                 + " partial@" + PARTIAL_BOOKING_TRIGGER_R + "R lock@" + BREAKEVEN_TRIGGER_R + "R"
//                 + " stage2@" + TRAIL_STAGE2_TRIGGER_R + "R stage3@" + TRAIL_STAGE3_TRIGGER_R + "R ===");

//         // NEW (v2) — sanity checks: TP must sit beyond the partial/trail
//         // triggers, otherwise the exchange TP closes the full position first
//         // and those features never run.
//         if (PARTIAL_BOOKING_ENABLED && PARTIAL_BOOKING_TRIGGER_R >= RR_DEFAULT) {
//             System.err.println("WARNING: PARTIAL_BOOKING_TRIGGER_R (" + PARTIAL_BOOKING_TRIGGER_R
//                     + ") >= RR_DEFAULT (" + RR_DEFAULT + ") — TP pehle hit hoga, partial booking kabhi nahi chalegi!");
//         }
//         if (TRAILING_ENABLED && TRAIL_STAGE2_TRIGGER_R >= RR_DEFAULT) {
//             System.err.println("WARNING: TRAIL_STAGE2_TRIGGER_R (" + TRAIL_STAGE2_TRIGGER_R
//                     + ") >= RR_DEFAULT (" + RR_DEFAULT + ") — stage 2/3 trailing kabhi nahi chalega!");
//         }

//         initInstrumentCache();

//         while (true) {
//             try {
//                 runEntryScan();
//             } catch (Throwable t) {
//                 System.err.println("[MAIN-LOOP] Uncaught error, continuing: " + t.getMessage());
//                 t.printStackTrace();
//             }
//             try {
//                 TimeUnit.MILLISECONDS.sleep(SCALP_ENTRY_SCAN_INTERVAL_MS);
//             } catch (InterruptedException ignored) {
//                 Thread.currentThread().interrupt();
//                 break;
//             }
//         }
//     }

//     private static void runEntryScan() {
//         Set<String> active = getActivePositions();
//         System.out.println("Active positions: " + active);
//         pendingSignals.keySet().removeIf(active::contains);
//         trailState.keySet().removeIf(p -> !active.contains(p));

//         if (active.size() >= MAX_OPEN_POSITIONS) {
//             System.out.println("MAX_OPEN_POSITIONS reached — skipping scan.");
//             updateTrailing();
//             ensureTpSlForOpenPositions();
//             return;
//         }

//         for (String pair : COINS_TO_TRADE) {
//             try {
//                 if (active.size() >= MAX_OPEN_POSITIONS) break;
//                 if (active.contains(pair)) continue;

//                 long lastTrade = lastTradeTime.getOrDefault(pair, 0L);
//                 if (System.currentTimeMillis() - lastTrade < SCALP_COOLDOWN_MS) continue;

//                 JSONArray raw5m = dropLastIfForming(getCandlestickData(pair, RES_5M, BASE_5M_FETCH_COUNT));
//                 if (raw5m == null || raw5m.length() < EMA_MID + ST_PERIOD + STRUCTURE_SWING_LOOKBACK) continue;

//                 PendingSignal pending = pendingSignals.get(pair);

//                 if (pending != null) {
//                     boolean cancelled = false;
//                     if (System.currentTimeMillis() - pending.createdAtMs > SIGNAL_MAX_VALID_MS) {
//                         System.out.println("  Signal expired (stale setup): " + pair);
//                         cancelled = true;
//                     } else {
//                         EntryResult quickCheck = analyzeEntry5M(raw5m, pending.isLong);
//                         if (quickCheck.valid && quickCheck.distanceAtr > OVEREXTENSION_SKIP_ATR) {
//                             System.out.println("  Signal cancelled (price overextended "
//                                     + String.format("%.2f", quickCheck.distanceAtr) + " ATR): " + pair);
//                             cancelled = true;
//                         }
//                     }
//                     if (!cancelled) {
//                         JSONArray raw1h = dropLastIfForming(getCandlestickData(pair, RES_1H, BASE_1H_FETCH_COUNT));
//                         ScoredDirection dir1h = analyzeScored6(raw1h);
//                         if (dir1h.valid) {
//                             boolean stillAgrees = pending.isLong ? dir1h.bullScore >= MACRO_1H_MIN_SCORE : dir1h.bearScore >= MACRO_1H_MIN_SCORE;
//                             if (!stillAgrees) {
//                                 System.out.println("  Signal cancelled (1H direction changed): " + pair);
//                                 cancelled = true;
//                             }
//                         }
//                     }

//                     if (cancelled) {
//                         pendingSignals.remove(pair);
//                     } else {
//                         double currentPrice = getLastPrice(pair);
//                         if (currentPrice > 0) {
//                             boolean breakoutHit = pending.isLong
//                                     ? currentPrice > pending.confirmHigh
//                                     : currentPrice < pending.confirmLow;
//                             if (breakoutHit) {
//                                 tryEnterOnBreakout(pair, pending, raw5m, currentPrice, active);
//                             }
//                         }
//                         continue;
//                     }
//                 }

//                 // ---- No pending signal — look for a fresh one ----

//                 JSONArray raw1h = dropLastIfForming(getCandlestickData(pair, RES_1H, BASE_1H_FETCH_COUNT));
//                 ScoredDirection dir1h = analyzeScored6(raw1h);
//                 if (!dir1h.valid) continue;
//                 boolean trendUp;
//                 if (dir1h.bullScore >= MACRO_1H_MIN_SCORE) trendUp = true;
//                 else if (dir1h.bearScore >= MACRO_1H_MIN_SCORE) trendUp = false;
//                 else continue;

//                 JSONArray raw30m = aggregateCandles(raw5m, GROUP_30M_FROM_5M);
//                 ScoredDirection dir30m = analyzeScored6(raw30m);
//                 if (!dir30m.valid) continue;
//                 int score30 = trendUp ? dir30m.bullScore : dir30m.bearScore;
//                 int opposite30 = trendUp ? dir30m.bearScore : dir30m.bullScore;
//                 if (opposite30 > score30) continue;

//                 boolean strong30 = score30 >= CONFIRM_30M_STRONG_MIN;
//                 boolean acceptable30 = score30 == CONFIRM_30M_ACCEPTABLE;
//                 if (!strong30 && !acceptable30) continue;

//                 JSONArray raw15m = aggregateCandles(raw5m, GROUP_15M_FROM_5M);
//                 Setup15Result setup15 = analyzeSetup15M(raw15m);
//                 if (!setup15.valid) continue;
//                 int score15 = trendUp ? setup15.bullScore : setup15.bearScore;
//                 if (score15 < SETUP_15M_MIN_SCORE) continue;

//                 EntryResult entry5m = analyzeEntry5M(raw5m, trendUp);
//                 if (!entry5m.valid) continue;

//                 if (acceptable30) {
//                     boolean clean15 = score15 >= CLEAN_15M_MIN_FOR_ACCEPTABLE;
//                     boolean clean5 = entry5m.setupFound;
//                     if (!clean15 || !clean5) continue;
//                 }

//                 if (!entry5m.setupFound) continue;

//                 // Capture the pullback swing NOW — this is the anchor the SL
//                 // will use at breakout.
//                 double[] hi5mArm = extractHighs(raw5m);
//                 double[] lo5mArm = extractLows(raw5m);
//                 double swingLevel = trendUp
//                         ? findRecentSwingLow(lo5mArm, SL_SWING_LOOKBACK)
//                         : findRecentSwingHigh(hi5mArm, SL_SWING_LOOKBACK);

//                 PendingSignal newSignal = new PendingSignal();
//                 newSignal.isLong = trendUp;
//                 newSignal.confirmHigh = entry5m.confirmHigh;
//                 newSignal.confirmLow  = entry5m.confirmLow;
//                 newSignal.setupSwingLevel = swingLevel;
//                 newSignal.strongTrend = (score30 == 6);
//                 newSignal.createdAtMs = System.currentTimeMillis();
//                 pendingSignals.put(pair, newSignal);
//                 System.out.println("  Pending " + (trendUp ? "LONG" : "SHORT") + " signal armed: " + pair
//                         + " | 1H=" + (trendUp ? dir1h.bullScore : dir1h.bearScore) + "/6 30M=" + score30 + "/6 15M=" + score15 + "/5"
//                         + " | trigger=" + (trendUp ? ("break " + newSignal.confirmHigh) : ("break " + newSignal.confirmLow))
//                         + " | " + entry5m.reason);

//             } catch (Exception e) {
//                 System.err.println("Error on " + pair + ": " + e.getMessage());
//             }
//         }

//         System.out.println("\n=== Scan complete ===");
//         updateTrailing();
//         ensureTpSlForOpenPositions();
//     }

//     // Handles an armed pending signal's breakout: uses the ANCHOR captured at
//     // arm time, checks whether the structural SL is too wide to trade (skip
//     // rather than clamp), applies the nearby-level check, sizes the
//     // position, places the order, confirms the fill, and re-derives final
//     // SL/TP off the actual fill price (same anchor).
//     private static void tryEnterOnBreakout(String pair, PendingSignal pending, JSONArray raw5m,
//                                             double currentPrice, Set<String> active) {
//         try {
//             double tickSize = getTickSize(pair);
//             double[] hi5m = extractHighs(raw5m);
//             double[] lo5m = extractLows(raw5m);
//             double atr = calcATR(hi5m, lo5m, extractCloses(raw5m), ATR_PERIOD);

//             if (structuralSlTooWide(pending.isLong, currentPrice, pending.setupSwingLevel, atr)) {
//                 System.out.println("  NO TRADE: " + pair + " — structural SL too wide (>" + MAX_STRUCTURAL_SL_ATR
//                         + " ATR or >" + SL_MAX_PERCENT + "% from entry) — skipping");
//                 pendingSignals.remove(pair);
//                 return;
//             }

//             double[] preSlTp = slTpFromLevel(pending.isLong, currentPrice, pending.setupSwingLevel, atr, tickSize, pending.strongTrend);

//             JSONArray raw15m = aggregateCandles(raw5m, GROUP_15M_FROM_5M);
//             if (raw15m != null) {
//                 double[] hi15m = extractHighs(raw15m);
//                 double[] lo15m = extractLows(raw15m);
//                 // CHANGED (v2): check obstacles against a virtual near target
//                 // (TP_BLOCK_CHECK_R x risk), not the real far-away TP.
//                 double preRisk = Math.abs(currentPrice - preSlTp[0]);
//                 double blockCheckTp = pending.isLong
//                         ? currentPrice + TP_BLOCK_CHECK_R * preRisk
//                         : currentPrice - TP_BLOCK_CHECK_R * preRisk;
//                 if (tpBlockedByLevel(pending.isLong, currentPrice, blockCheckTp, hi15m, lo15m, TP_LEVEL_LOOKBACK)) {
//                     System.out.println("  NO TRADE: " + pair + " — meaningful nearby " + (pending.isLong ? "resistance" : "support") + " blocks the path");
//                     pendingSignals.remove(pair);
//                     return;
//                 }
//             }

//             double qty = calcQuantity(currentPrice, preSlTp[0], pair);
//             if (qty <= 0) { pendingSignals.remove(pair); return; }

//             System.out.println("\n==== " + pair + " — BREAKOUT ENTRY ====");
//             String side = pending.isLong ? "buy" : "sell";
//             JSONObject orderResp = placeFuturesOrder(side, pair, qty, LEVERAGE,
//                     "email_notification", "isolated", "INR", currentPrice);
//             if (orderResp == null || !orderResp.has("id")) {
//                 System.out.println("  Order failed: " + orderResp);
//                 pendingSignals.remove(pair);
//                 return;
//             }

//             System.out.println("  Order placed! id=" + orderResp.getString("id"));
//             lastTradeTime.put(pair, System.currentTimeMillis());
//             double swingAnchor = pending.setupSwingLevel;
//             boolean strongTrend = pending.strongTrend;
//             boolean isLong = pending.isLong;
//             pendingSignals.remove(pair);

//             double entry = getEntryPrice(pair, orderResp.getString("id"));
//             if (entry <= 0) {
//                 System.out.println("  Could not confirm entry within window — TP/SL handled by safety sweep");
//                 active.add(pair);
//                 return;
//             }
//             System.out.printf("  Entry confirmed: %.6f%n", entry);

//             double[] slTp = slTpFromLevel(isLong, entry, swingAnchor, atr, tickSize, strongTrend);
//             double slPrice = slTp[0], tpPrice = slTp[1], rrUsed = slTp[3];
//             double[] clamped = sanityClampSlTp(isLong, entry, slPrice, tpPrice, tickSize);
//             slPrice = clamped[0]; tpPrice = clamped[1];

//             double risk = Math.abs(entry - slPrice);
//             System.out.println("[ENTRY] " + pair + " " + (isLong ? "LONG" : "SHORT")
//                     + " Entry=" + entry + " SL=" + slPrice + " TP=" + tpPrice
//                     + " Risk=" + risk + " RR=" + String.format("%.2f", rrUsed)
//                     + " SL%=" + String.format("%.2f", slTp[2]) + " (range [" + SL_MIN_PERCENT + "%, " + SL_MAX_PERCENT + "%])");

//             String posId = getPositionId(pair);
//             if (posId != null) {
//                 setTpSlWithRetry(posId, tpPrice, slPrice, pair);
//             } else {
//                 System.out.println("  Position ID not found after retries — safety sweep will handle it");
//             }

//             TrailInfo ti = new TrailInfo();
//             ti.isLong = isLong;
//             ti.entryPrice = entry;
//             ti.initialSL = slPrice;
//             ti.initialTP = tpPrice;
//             ti.initialRisk = risk;
//             ti.currentSL = slPrice;
//             ti.currentTP = tpPrice;
//             ti.peakPrice = entry;
//             ti.stage = 0;
//             ti.extensionsUsed = 0;
//             ti.lastTrailCandleTime = 0L;
//             ti.originalQty = qty;
//             ti.partialBookingDone = false;
//             ti.entryTimeMs = System.currentTimeMillis();
//             trailState.put(pair, ti);

//             active.add(pair);
//         } catch (Exception e) {
//             System.err.println("tryEnterOnBreakout(" + pair + "): " + e.getMessage());
//         }
//     }

//     // =========================================================================
//     // Trailing monitor — runs every cycle for every open position.
//     //   Stage 1 (0.6R):  profit lock (35% of move) — immediate, every cycle.
//     //   Partial (0.9R):  close 33% at market.
//     //   Stage 2 (1.0R):  hybrid structure+ATR trail (2.0 ATR).
//     //   Stage 3 (2.0R):  same hybrid trail, tighter (1.5 ATR).
//     //   Stage 2/3 trail decisions are CANDLE-CLOSE GATED.
//     //   TP extension only past stage 3, only while trend still valid.
//     //   SL only ever moves in the profitable direction (ratchet-only).
//     // =========================================================================
//     private static void updateTrailing() {
//         Set<String> stillOpen = getActivePositions();
//         for (String pair : stillOpen) {
//             try {
//                 JSONObject pos = findPosition(pair);
//                 if (pos == null) continue;

//                 TrailInfo ti = trailState.get(pair);
//                 if (ti == null) {
//                     // Reconstruct from the exchange position — never reset a
//                     // profitable trade's SL/TP back to some fresh initial guess.
//                     double avgPrice = pos.optDouble("avg_price", 0);
//                     double tpTrig = pos.optDouble("take_profit_trigger", 0);
//                     double slTrig = pos.optDouble("stop_loss_trigger", 0);
//                     if (avgPrice <= 0) continue;
//                     boolean isLong = pos.optDouble("active_pos", 0) >= 0;
//                     ti = new TrailInfo();
//                     ti.isLong = isLong;
//                     ti.entryPrice = avgPrice;
//                     ti.currentSL = slTrig;
//                     ti.currentTP = tpTrig;
//                     ti.initialSL = slTrig;
//                     ti.initialTP = tpTrig;
//                     ti.initialRisk = slTrig > 0 ? Math.abs(avgPrice - slTrig) : 0;
//                     ti.peakPrice = avgPrice;
//                     ti.stage = 0;
//                     ti.extensionsUsed = 0;
//                     ti.lastTrailCandleTime = 0L;
//                     // Reconstructed after a restart — true original qty and
//                     // partial-booking state unknown; use current active_pos.
//                     ti.originalQty = Math.abs(pos.optDouble("active_pos", 0));
//                     ti.partialBookingDone = false;
//                     ti.entryTimeMs = System.currentTimeMillis();
//                     trailState.put(pair, ti);
//                     if (ti.currentSL <= 0 || ti.currentTP <= 0) continue; // let the safety sweep set initial protection first
//                 }

//                 JSONArray raw5m = dropLastIfForming(getCandlestickData(pair, RES_5M, BASE_5M_FETCH_COUNT));
//                 if (raw5m == null || raw5m.length() < EMA_MID + ST_PERIOD + 5) continue;
//                 double[] hi5m = extractHighs(raw5m), lo5m = extractLows(raw5m), cl5m = extractCloses(raw5m);
//                 double atr5m = calcATR(hi5m, lo5m, cl5m, ATR_PERIOD);
//                 if (atr5m <= 0) continue;

//                 // ---- Early exit (toggleable, independent of trailing below) ----
//                 checkEarlyExitForPosition(pair, ti, raw5m);

//                 // ---- Trailing (only if we have a valid initial baseline) ----
//                 if (ti.initialTP <= 0 || ti.initialRisk <= 0) continue;

//                 double currentPrice = getLastPrice(pair);
//                 if (currentPrice <= 0) continue;
//                 ti.peakPrice = ti.isLong ? Math.max(ti.peakPrice, currentPrice) : Math.min(ti.peakPrice, currentPrice);

//                 double moveInFavor = ti.isLong ? (currentPrice - ti.entryPrice) : (ti.entryPrice - currentPrice);
//                 double rMultiple = moveInFavor / ti.initialRisk;

//                 double tpDistance = ti.isLong ? ti.initialTP - ti.entryPrice : ti.entryPrice - ti.initialTP;
//                 double tpProgress = tpDistance > 0 ? (moveInFavor / tpDistance) : 0;

//                 // ---- Partial profit booking ----
//                 if (PARTIAL_BOOKING_ENABLED && !ti.partialBookingDone
//                         && rMultiple >= PARTIAL_BOOKING_TRIGGER_R
//                         && ti.originalQty > 0) {
//                     double closeQty = roundQtyForPair(ti.originalQty * PARTIAL_BOOKING_CLOSE_FRACTION, pair);
//                     if (closeQty > 0) {
//                         boolean booked = partialExitPosition(pair, closeQty, ti.isLong, currentPrice);
//                         if (booked) {
//                             System.out.printf("  [PARTIAL-BOOK] %s %s R=%.2f closed %.4f of %.4f qty — remainder keeps trailing%n",
//                                     pair, ti.isLong ? "LONG" : "SHORT", rMultiple, closeQty, ti.originalQty);
//                             ti.partialBookingDone = true;
//                             ti.originalQty -= closeQty; // track what's left, for reference
//                         } else {
//                             System.out.println("  [PARTIAL-BOOK] " + pair + " attempt failed — will retry next cycle");
//                         }
//                     }
//                 }

//                 // ---- Trailing / breakeven / TP-extension (all gated by TRAILING_ENABLED) ----
//                 if (TRAILING_ENABLED) {
//                 int newStage = rMultiple >= TRAIL_STAGE3_TRIGGER_R ? 3
//                         : rMultiple >= TRAIL_STAGE2_TRIGGER_R ? 2
//                         : rMultiple >= BREAKEVEN_TRIGGER_R ? 1 : 0;
//                 if (newStage > ti.stage) ti.stage = newStage;

//                 double tick = getTickSize(pair);
//                 double candidateSL = ti.currentSL;

//                 // Stage 1 — profit lock scaling with moveInFavor. Immediate, every cycle.
//                 if (ti.stage >= 1) {
//                     double lockSL = ti.isLong
//                             ? ti.entryPrice + (moveInFavor * BREAKEVEN_LOCK_R_FRACTION)
//                             : ti.entryPrice - (moveInFavor * BREAKEVEN_LOCK_R_FRACTION);
//                     if (ti.isLong ? lockSL > candidateSL : lockSL < candidateSL) candidateSL = lockSL;
//                 }

//                 // Stage 2/3 — hybrid structure+ATR trail. Candle-close gated.
//                 long lastCandleTime = raw5m.length() > 0
//                         ? raw5m.getJSONObject(raw5m.length() - 1).optLong("time", 0)
//                         : 0;
//                 boolean newCandleClosed = lastCandleTime > 0 && lastCandleTime != ti.lastTrailCandleTime;

//                 if (ti.stage >= 2 && (newCandleClosed || ti.lastTrailCandleTime == 0)) {
//                     double trailMult = ti.stage >= 3 ? TRAIL_STAGE3_ATR : TRAIL_STAGE2_ATR;
//                     double atrSL = ti.isLong ? currentPrice - trailMult * atr5m : currentPrice + trailMult * atr5m;
//                     double structureSL = ti.isLong
//                             ? findRecentSwingLow(lo5m, SL_SWING_LOOKBACK)
//                             : findRecentSwingHigh(hi5m, SL_SWING_LOOKBACK);
//                     double hybridCandidate = ti.isLong ? Math.max(atrSL, structureSL) : Math.min(atrSL, structureSL);
//                     if (ti.isLong ? hybridCandidate > candidateSL : hybridCandidate < candidateSL) candidateSL = hybridCandidate;
//                     ti.lastTrailCandleTime = lastCandleTime;
//                 }

//                 boolean changed = false;
//                 double minImprovement = Math.max(MIN_SL_IMPROVEMENT_ATR * atr5m, tick);
//                 boolean meaningfulImprovement = ti.isLong
//                         ? (candidateSL - ti.currentSL) >= minImprovement
//                         : (ti.currentSL - candidateSL) >= minImprovement;

//                 if (meaningfulImprovement) {
//                     candidateSL = roundToTick(candidateSL, tick);
//                     // Never move SL backward — ratchet-only.
//                     if (ti.isLong ? candidateSL > ti.currentSL : candidateSL < ti.currentSL) {
//                         System.out.printf("  [TRAIL] %s %s R=%.2f stage=%d SL moved %.6f -> %.6f%n",
//                                 pair, ti.isLong ? "LONG" : "SHORT", rMultiple, ti.stage, ti.currentSL, candidateSL);
//                         ti.currentSL = candidateSL;
//                         changed = true;
//                     } else {
//                         System.out.println("  [TRAIL] " + pair + " candidate SL would worsen existing SL — ignoring update");
//                     }
//                 }

//                 // ---- TP extension (only past stage 3, close to original TP, trend still valid) ----
//                 if (ti.stage >= 3 && ti.extensionsUsed < MAX_TP_EXTENSIONS && tpProgress >= TP_EXTENSION_TRIGGER_FRACTION) {
//                     JSONArray raw15m = aggregateCandles(raw5m, GROUP_15M_FROM_5M);
//                     boolean trendValid = checkTrendValidForExtension(ti.isLong, raw15m, raw5m);
//                     if (trendValid) {
//                         double newTP = ti.isLong ? currentPrice + atr5m * TP_EXTENSION_ATR : currentPrice - atr5m * TP_EXTENSION_ATR;
//                         newTP = roundToTick(newTP, tick);
//                         if (ti.isLong ? newTP > ti.currentTP : newTP < ti.currentTP) {
//                             System.out.println("  [TP EXTENSION] " + pair + " " + (ti.isLong ? "LONG" : "SHORT")
//                                     + " TP " + ti.currentTP + " -> " + newTP
//                                     + " Extension " + (ti.extensionsUsed + 1) + "/" + MAX_TP_EXTENSIONS);
//                             ti.currentTP = newTP;
//                             ti.extensionsUsed++;
//                             changed = true;
//                         }
//                     }
//                 }

//                 if (changed) {
//                     String posId = pos.optString("id", null);
//                     if (posId != null) setTpSlWithRetry(posId, ti.currentTP, ti.currentSL, pair);
//                 }
//                 } // end if (TRAILING_ENABLED)
//             } catch (Exception e) {
//                 System.err.println("updateTrailing(" + pair + "): " + e.getMessage());
//             }
//         }
//     }

//     // TP-extension trend check — only 15M structure and 5M momentum
//     // (EMA9 slope) need to still be healthy.
//     private static boolean checkTrendValidForExtension(boolean isLong, JSONArray raw15m, JSONArray raw5m) {
//         Setup15Result s15 = analyzeSetup15M(raw15m);
//         if (s15.valid) {
//             int score15 = isLong ? s15.bullScore : s15.bearScore;
//             if (score15 < SETUP_15M_MIN_SCORE) return false;
//         }
//         if (raw5m != null && raw5m.length() >= EMA_FAST + EMA_SLOPE_LOOKBACK_BARS + 1) {
//             double[] cl5 = extractCloses(raw5m);
//             double[] hi5 = extractHighs(raw5m);
//             double[] lo5 = extractLows(raw5m);
//             double atr5 = calcATR(hi5, lo5, cl5, ATR_PERIOD);
//             double[] ema9Series = calcEMASeries(cl5, EMA_FAST);
//             int n = ema9Series.length;
//             int lb = Math.min(EMA_SLOPE_LOOKBACK_BARS, n - 1);
//             double slope = ema9Series[n - 1] - ema9Series[n - 1 - lb];
//             boolean momentumOk = isLong
//                     ? (atr5 <= 0 || slope >= -ENTRY_EMA_SLOPE_MIN_ATR * atr5)
//                     : (atr5 <= 0 || slope <= ENTRY_EMA_SLOPE_MIN_ATR * atr5);
//             if (!momentumOk) return false;
//         }
//         return true;
//     }

//     // =========================================================================
//     // Early-exit system (toggleable via EARLY_EXIT_ENABLED).
//     //   5M  -> warning only, NEVER exits
//     //   15M -> score-based confirmed reversal (>= EARLY_EXIT_15M_MIN_SIGNALS of 4) -> exit
//     //   30M -> Supertrend flip + EMA9/EMA21 cross against the position -> exit
//     // =========================================================================
//     private static void checkEarlyExitForPosition(String pair, TrailInfo ti, JSONArray raw5m) {
//         if (!EARLY_EXIT_ENABLED || ti == null) return;
//         try {
//             long barsElapsed = (System.currentTimeMillis() - ti.entryTimeMs) / (5L * 60 * 1000);
//             if (barsElapsed < EARLY_EXIT_GRACE_BARS_5M) return;

//             double[] hi5 = extractHighs(raw5m), lo5 = extractLows(raw5m), cl5 = extractCloses(raw5m);
//             if (hi5.length < EMA_MID + ST_PERIOD + 5) return;

//             // ---- 5M — warning only ----
//             boolean[] st5 = calcSupertrend(hi5, lo5, cl5, ST_PERIOD, ST_MULTIPLIER);
//             boolean st5Green = st5[st5.length - 1];
//             boolean warn5m = ti.isLong ? !st5Green : st5Green;
//             if (warn5m) {
//                 System.out.println("  [EARLY-EXIT][5M-WARN] " + pair + " Supertrend against position — watching only, no action");
//             }

//             // ---- 15M — confirmed exit ----
//             JSONArray raw15m = aggregateCandles(raw5m, GROUP_15M_FROM_5M);
//             if (raw15m != null && raw15m.length() >= EMA_MID + ST_PERIOD + 3) {
//                 double[] hi15 = extractHighs(raw15m), lo15 = extractLows(raw15m), cl15 = extractCloses(raw15m);
//                 boolean[] st15 = calcSupertrend(hi15, lo15, cl15, ST_PERIOD, ST_MULTIPLIER);
//                 boolean st15Green = st15[st15.length - 1];
//                 double ema21_15 = calcEMA(cl15, EMA_MID);
//                 double lastClose15 = cl15[cl15.length - 1];
//                 int structure15 = detectSwingStructure(hi15, lo15, STRUCTURE_SWING_LOOKBACK);

//                 double[] ema9Series15 = calcEMASeries(cl15, EMA_FAST);
//                 int n15 = ema9Series15.length;
//                 int lb15 = Math.min(EMA_SLOPE_LOOKBACK_BARS, n15 - 1);
//                 double slope15 = ema9Series15[n15 - 1] - ema9Series15[n15 - 1 - lb15];
//                 double atr15 = calcATR(hi15, lo15, cl15, ATR_PERIOD);
//                 boolean slopeLost15 = ti.isLong
//                         ? (atr15 > 0 && slope15 <= -HTF_EMA_SLOPE_MIN_ATR * atr15)
//                         : (atr15 > 0 && slope15 >= HTF_EMA_SLOPE_MIN_ATR * atr15);

//                 boolean stFlip15 = ti.isLong ? !st15Green : st15Green;
//                 boolean closeBreak15 = ti.isLong ? lastClose15 < ema21_15 : lastClose15 > ema21_15;
//                 boolean structureBreak15 = ti.isLong ? structure15 == -1 : structure15 == 1;

//                 int reversalScore15 = (stFlip15 ? 1 : 0) + (closeBreak15 ? 1 : 0)
//                         + (structureBreak15 ? 1 : 0) + (slopeLost15 ? 1 : 0);
//                 boolean exit15 = reversalScore15 >= EARLY_EXIT_15M_MIN_SIGNALS;
//                 if (exit15) {
//                     System.out.println("  [EARLY-EXIT][15M] " + pair + " " + (ti.isLong ? "LONG" : "SHORT")
//                             + " reversal confirmed (score=" + reversalScore15 + "/4) — exiting position");
//                     exitTrackedPosition(pair);
//                     return;
//                 }
//             }

//             // ---- 30M — emergency exit ----
//             JSONArray raw30m = aggregateCandles(raw5m, GROUP_30M_FROM_5M);
//             if (raw30m != null && raw30m.length() >= EMA_MID + ST_PERIOD + 3) {
//                 double[] cl30 = extractCloses(raw30m), hi30 = extractHighs(raw30m), lo30 = extractLows(raw30m);
//                 boolean[] st30 = calcSupertrend(hi30, lo30, cl30, ST_PERIOD, ST_MULTIPLIER);
//                 boolean st30Green = st30[st30.length - 1];
//                 double ema9_30 = calcEMA(cl30, EMA_FAST);
//                 double ema21_30 = calcEMA(cl30, EMA_MID);
//                 boolean stFlip30 = ti.isLong ? !st30Green : st30Green;
//                 boolean emaCrossAgainst30 = ti.isLong ? ema9_30 < ema21_30 : ema9_30 > ema21_30;
//                 if (stFlip30 && emaCrossAgainst30) {
//                     System.out.println("  [EARLY-EXIT][30M-EMERGENCY] " + pair + " " + (ti.isLong ? "LONG" : "SHORT")
//                             + " major reversal — exiting position immediately");
//                     exitTrackedPosition(pair);
//                 }
//             }
//         } catch (Exception e) {
//             System.err.println("checkEarlyExitForPosition(" + pair + "): " + e.getMessage());
//         }
//     }

//     private static void exitTrackedPosition(String pair) {
//         try {
//             JSONObject pos = findPosition(pair);
//             if (pos == null) return;
//             String posId = pos.optString("id", null);
//             if (posId == null) {
//                 System.out.println("  [EARLY-EXIT] " + pair + " — position ID missing, cannot exit");
//                 return;
//             }
//             exitPositionNow(posId, pair);
//         } catch (Exception e) {
//             System.err.println("exitTrackedPosition(" + pair + "): " + e.getMessage());
//         }
//     }

//     // NOTE: verify this exact endpoint/payload shape against CoinDCX's current
//     // Futures "Exit Position" API docs before relying on it live.
//     private static boolean exitPositionNow(String posId, String pair) {
//         try {
//             JSONObject body = new JSONObject();
//             body.put("timestamp", Instant.now().toEpochMilli());
//             body.put("id", posId);
//             String resp = authPost(BASE_URL + "/exchange/v1/derivatives/futures/positions/exit", body.toString());
//             JSONObject r = new JSONObject(resp);
//             boolean ok = !r.has("err_code_dcx");
//             System.out.println("  [EARLY-EXIT] " + pair + " exit request " + (ok ? "sent successfully" : "FAILED: " + r));
//             return ok;
//         } catch (Exception e) {
//             System.err.println("exitPositionNow(" + pair + "): " + e.getMessage());
//             return false;
//         }
//     }

//     // Never overwrite an existing better (possibly trailed) SL/TP. Only fills
//     // in whichever side is genuinely missing.
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
//                 if (tpTrig > 0 && slTrig > 0) continue; // both present — trailing owns updates from here, never touch

//                 System.out.println("  [SWEEP] " + pair + " missing TP and/or SL — computing fallback protection...");
//                 boolean isLong = pos.optDouble("active_pos", 0) >= 0;
//                 double tick = getTickSize(pair);
//                 TrailInfo ti = trailState.get(pair);

//                 double sl, tp;
//                 if (ti != null && ti.currentSL > 0 && ti.currentTP > 0) {
//                     sl = ti.currentSL; tp = ti.currentTP; // trust our own tracked (possibly trailed) values
//                 } else {
//                     JSONArray raw5m = dropLastIfForming(getCandlestickData(pair, RES_5M, BASE_5M_FETCH_COUNT));
//                     double[] hi5m = null, lo5m = null; double atr5m = 0;
//                     if (raw5m != null && raw5m.length() >= ATR_PERIOD + SL_SWING_LOOKBACK) {
//                         hi5m = extractHighs(raw5m); lo5m = extractLows(raw5m);
//                         atr5m = calcATR(hi5m, lo5m, extractCloses(raw5m), ATR_PERIOD);
//                     }
//                     double[] slTp = (hi5m != null && atr5m > 0)
//                             ? computeFreshStructuralSlTp(isLong, avgPrice, hi5m, lo5m, atr5m, tick, false)
//                             : null;
//                     if (slTp == null) {
//                         sl = isLong ? avgPrice * (1 - SL_HARD_PERCENT_CAP / 100.0) : avgPrice * (1 + SL_HARD_PERCENT_CAP / 100.0);
//                         tp = isLong ? avgPrice + RR_DEFAULT * (avgPrice - sl) : avgPrice - RR_DEFAULT * (sl - avgPrice);
//                         System.out.println("  [SWEEP] structural SL unavailable for " + pair + " — using hard % fallback cap");
//                     } else {
//                         sl = slTp[0]; tp = slTp[1];
//                     }
//                 }

//                 // Preserve whichever side the exchange already has set — never overwrite it.
//                 if (slTrig > 0) sl = slTrig;
//                 if (tpTrig > 0) tp = tpTrig;

//                 double[] clamped = sanityClampSlTp(isLong, avgPrice, sl, tp, tick);
//                 sl = clamped[0]; tp = clamped[1];

//                 String posId = pos.optString("id", null);
//                 if (posId != null) {
//                     System.out.printf("  [SWEEP] %s fallback SL=%.6f TP=%.6f%n", pair, sl, tp);
//                     setTpSlWithRetry(posId, tp, sl, pair);
//                     if (ti == null) {
//                         TrailInfo nt = new TrailInfo();
//                         nt.isLong = isLong; nt.entryPrice = avgPrice;
//                         nt.currentSL = sl; nt.currentTP = tp;
//                         nt.initialSL = sl; nt.initialTP = tp;
//                         nt.initialRisk = Math.abs(avgPrice - sl);
//                         nt.peakPrice = avgPrice; nt.stage = 0; nt.extensionsUsed = 0;
//                         nt.lastTrailCandleTime = 0L;
//                         nt.originalQty = Math.abs(pos.optDouble("active_pos", 0));
//                         nt.partialBookingDone = false;
//                         nt.entryTimeMs = System.currentTimeMillis();
//                         trailState.put(pair, nt);
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

//     private static double[] calcEMASeries(double[] d, int period) {
//         double[] out = new double[d.length];
//         if (d.length < period) {
//             double last = d.length > 0 ? d[d.length - 1] : 0;
//             Arrays.fill(out, last);
//             return out;
//         }
//         double k = 2.0 / (period + 1);
//         double ema = 0;
//         for (int i = 0; i < period; i++) ema += d[i];
//         ema /= period;
//         for (int i = 0; i < period; i++) out[i] = ema;
//         out[period - 1] = ema;
//         for (int i = period; i < d.length; i++) {
//             ema = d[i] * k + ema * (1 - k);
//             out[i] = ema;
//         }
//         return out;
//     }

//     private static double calcEMA(double[] d, int period) {
//         if (d.length < period) return 0;
//         double k = 2.0 / (period + 1), ema = 0;
//         for (int i = 0; i < period; i++) ema += d[i];
//         ema /= period;
//         for (int i = period; i < d.length; i++) ema = d[i] * k + ema * (1 - k);
//         return ema;
//     }

//     private static double calcRSI(double[] closes, int period) {
//         if (closes.length < period + 1) return 50.0;

//         double avgGain = 0, avgLoss = 0;
//         for (int i = 1; i <= period; i++) {
//             double change = closes[i] - closes[i - 1];
//             if (change > 0) avgGain += change;
//             else avgLoss += -change;
//         }
//         avgGain /= period;
//         avgLoss /= period;

//         for (int i = period + 1; i < closes.length; i++) {
//             double change = closes[i] - closes[i - 1];
//             double gain = Math.max(change, 0);
//             double loss = Math.max(-change, 0);
//             avgGain = (avgGain * (period - 1) + gain) / period;
//             avgLoss = (avgLoss * (period - 1) + loss) / period;
//         }

//         if (avgLoss == 0) return 100.0;
//         double rs = avgGain / avgLoss;
//         return 100.0 - (100.0 / (1.0 + rs));
//     }

//     private static BigDecimal roundToTickBD(double price, double tick) {
//         if (tick <= 0) return BigDecimal.valueOf(price);
//         BigDecimal bdPrice = BigDecimal.valueOf(price);
//         BigDecimal bdTick  = BigDecimal.valueOf(tick);
//         BigDecimal multiples = bdPrice.divide(bdTick, 0, RoundingMode.HALF_UP);
//         BigDecimal result = multiples.multiply(bdTick);
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
//     private static double[] extractVolumes(JSONArray a) {
//         double[] o = new double[a.length()];
//         for (int i = 0; i < a.length(); i++) o[i] = a.getJSONObject(i).optDouble("volume", 0);
//         return o;
//     }

//     private static JSONArray getCandlestickData(String pair, String resolution, int count) {
//         try {
//             long minsPerBar;
//             switch (resolution) {
//                 case "1":   minsPerBar = 1;   break;
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

//     // Immediate market order on the SAME pair, OPPOSITE side of the open
//     // position — reduces (partially exits) the existing position. Used only
//     // for partial profit booking.
//     public static JSONObject placeMarketOrder(String side, String pair, double qty, int lev,
//                                                String notif, String marginType, String marginCcy,
//                                                double currentPrice) {
//         try {
//             JSONObject order = new JSONObject();
//             order.put("side",                       side.toLowerCase());
//             order.put("pair",                       pair);
//             order.put("order_type",                 "market_order");
//             order.put("price",                      currentPrice); // reference price; ignored for market orders per API docs
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
//             System.err.println("placeMarketOrder: " + e.getMessage());
//             return null;
//         }
//     }

//     // Closes `closeQty` of an open position at market, for partial profit
//     // booking (sell to reduce a long, buy to reduce a short).
//     private static boolean partialExitPosition(String pair, double closeQty, boolean isLong, double currentPrice) {
//         try {
//             String side = isLong ? "sell" : "buy";
//             JSONObject resp = placeMarketOrder(side, pair, closeQty, LEVERAGE,
//                     "email_notification", "isolated", "INR", currentPrice);
//             return resp != null && resp.has("id");
//         } catch (Exception e) {
//             System.err.println("partialExitPosition(" + pair + "): " + e.getMessage());
//             return false;
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
//             double volSum = 0;
//             for (int j = i; j < i + groupSize; j++) {
//                 JSONObject c = source.getJSONObject(j);
//                 high = Math.max(high, c.getDouble("high"));
//                 low  = Math.min(low,  c.getDouble("low"));
//                 volSum += c.optDouble("volume", 0);
//             }
//             JSONObject merged = new JSONObject();
//             merged.put("open", open);
//             merged.put("close", close);
//             merged.put("high", high);
//             merged.put("low", low);
//             merged.put("volume", volSum);
//             result.put(merged);
//         }
//         return result;
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
// import java.time.Instant;
// import java.util.*;
// import java.util.concurrent.ConcurrentHashMap;
// import java.util.concurrent.TimeUnit;
// import java.util.stream.Collectors;
// import java.util.stream.Stream;

// public class CoinDCXFuturesTrader8C_BUY_SELL_NEW_LOGIC_THREE {

//     // =========================================================================
//     // API Configuration (unchanged)
//     // =========================================================================
//     private static final String API_KEY    = System.getenv("DELTA_API_KEY");
//     private static final String API_SECRET = System.getenv("DELTA_API_SECRET");
//     private static final String BASE_URL       = "https://api.coindcx.com";
//     private static final String PUBLIC_API_URL = "https://public.coindcx.com";

//     private static final int LEVERAGE = 12;

//     private static final int MAX_ENTRY_PRICE_CHECKS = 20;
//     private static final int ENTRY_CHECK_DELAY_MS    = 1000;

//     private static final int  TPSL_MAX_RETRIES    = 3;
//     private static final long TPSL_RETRY_DELAY_MS = 2000L;

//     private static final long TICK_CACHE_TTL_MS = 3_600_000L;

//     private static final int MAX_OPEN_POSITIONS = 120;

//     private static final int  POSITION_ID_MAX_RETRIES = 5;
//     private static final long POSITION_ID_RETRY_DELAY_MS = 1500L;

//     // =========================================================================
//     // EARLY-EXIT SYSTEM — single on/off switch. Flip this to true/false to
//     // enable/disable the entire 3-level early-exit layer without touching
//     // anything else. When false, positions exit purely via TP/SL/trailing,
//     // exactly like before.
//     // =========================================================================
//     private static final boolean EARLY_EXIT_ENABLED = true;

//     // =========================================================================
//     // NEW — Fixes for early-exit closing positions too fast / too often
//     // (reported: lots of small losses, TP almost never gets hit because
//     // early-exit fires before price gets there).
//     //
//     // Root cause: the 15M exit condition was
//     //     (stFlip15 && closeBreak15) || (structureBreak15 && slopeLost15)
//     // — an OR of two pairs, so only 2 of 4 possible signals were needed, and
//     // a Supertrend(10,3) flip on an aggregated 15M candle happens fairly
//     // often even during a normal pullback inside a healthy trend. That was
//     // cutting trades on ordinary noise before they ever reached TP.
//     //
//     // Two independent fixes, both toggleable via the constants below:
//     //  1. GRACE PERIOD — a trade gets EARLY_EXIT_GRACE_BARS_5M worth of 5M
//     //     candles (measured from actual fill time) before early-exit is
//     //     allowed to act at all. This stops it from reacting to the very
//     //     first wobble right after entry.
//     //  2. SCORE-BASED 15M CONFIRMATION — instead of the old OR-of-pairs
//     //     logic, all 4 signals (ST flip, close-below-EMA21, structure break,
//     //     slope lost) are combined into a 0-4 score, and EARLY_EXIT_15M_MIN_SIGNALS
//     //     of them must agree before exiting. Higher = stricter = fewer, more
//     //     confident early exits. 3/4 is a reasonable stricter default (was
//     //     effectively requiring only 2/4 before).
//     // =========================================================================
//     private static final int EARLY_EXIT_GRACE_BARS_5M     = 3; // ~15 minutes grace after entry before early-exit can act
//     private static final int EARLY_EXIT_15M_MIN_SIGNALS   = 3; // out of 4 sub-signals required (was effectively 2 of 4)

//     // =========================================================================
//     // Indicator periods (unchanged — no new indicators added)
//     // =========================================================================
//     private static final int EMA_FAST = 9;
//     private static final int EMA_MID  = 21;
//     private static final int ATR_PERIOD = 14;
//     private static final int ST_PERIOD     = 10;
//     private static final double ST_MULTIPLIER = 3.0;
//     private static final int RSI_PERIOD = 14;
//     private static final int VOLUME_MA_PERIOD = 20;
//     private static final int VWAP_LOOKBACK = 20;

//     private static final String RES_5M = "5";
//     private static final String RES_1H = "60";

//     private static final int BASE_5M_FETCH_COUNT = 450;
//     private static final int GROUP_15M_FROM_5M = 3;
//     private static final int GROUP_30M_FROM_5M = 6;
//     private static final int BASE_1H_FETCH_COUNT = 90;

//     private static final int EMA_SLOPE_LOOKBACK_BARS   = 5;
//     private static final double HTF_EMA_SLOPE_MIN_ATR   = 0.10;
//     private static final double ENTRY_EMA_SLOPE_MIN_ATR = 0.15;

//     private static final int STRUCTURE_SWING_LOOKBACK = 30;
//     private static final int SL_SWING_LOOKBACK         = 20;
//     private static final int TP_LEVEL_LOOKBACK         = 40;

//     // =========================================================================
//     // 1H macro direction / 30M confirmation, scored out of 6.
//     // =========================================================================
//     private static final int MACRO_1H_MIN_SCORE     = 4;
//     private static final int CONFIRM_30M_STRONG_MIN = 5;
//     private static final int CONFIRM_30M_ACCEPTABLE = 4;
//     private static final int CLEAN_15M_MIN_FOR_ACCEPTABLE = 4;

//     // =========================================================================
//     // 15M setup, scored out of 5.
//     // =========================================================================
//     private static final int SETUP_15M_MIN_SCORE = 3;

//     // =========================================================================
//     // 5M pullback + rejection (mandatory) + slope (mandatory).
//     // =========================================================================
//     private static final double PULLBACK_MAX_ATR       = 1.5;
//     private static final double OVEREXTENSION_SKIP_ATR  = 2.0;
//     private static final double CANDLE_BODY_RATIO_MIN   = 0.40;

//     // =========================================================================
//     // 5M supporting confirmation score out of 5 (min 3/5).
//     // =========================================================================
//     private static final int    ENTRY_CONFIRMATION_MIN_SCORE = 3;
//     private static final double RSI_LONG_MIN  = 40, RSI_LONG_MAX  = 70;
//     private static final double RSI_SHORT_MIN = 30, RSI_SHORT_MAX = 60;

//     // =========================================================================
//     // Pending breakout-entry signal.
//     // =========================================================================
//     private static final long SIGNAL_MAX_VALID_MS = 15L * 60 * 1000L; // 15 minutes

//     // =========================================================================
//     // Structural SL: anchored to the pullback swing captured when the signal
//     // was ARMED (not re-searched at breakout time), + ATR buffer, then
//     // CLAMPED into a configurable [SL_MIN_PERCENT, SL_MAX_PERCENT] range so
//     // SL stays small and directly tunable.
//     //
//     // HYBRID PHILOSOPHY: the clamp is kept (so a good, reasonable structural
//     // SL always lands in this tight tunable band) — but it is no longer used
//     // to rescue a genuinely bad entry location. If the RAW structural SL
//     // (swing + buffer, before clamping) is farther than MAX_STRUCTURAL_SL_ATR
//     // from entry, that means the entry itself is poor, and the trade is
//     // SKIPPED instead of being force-clamped into a tiny SL that doesn't
//     // reflect real structure.
//     // =========================================================================
//     private static final double SL_BUFFER_ATR   = 0.35; // widened buffer off the swing (was 0.15)
//     private static final double SL_MIN_PERCENT  = 3.0;  // SL can never be tighter than this (tick-noise floor) — tune here
//     private static final double SL_MAX_PERCENT  = 4.0;  // SL can never be wider than this — "very very small" SL, tune here
//     private static final double SL_HARD_PERCENT_CAP  = 6.0;  // absolute safety-net fallback ONLY (e.g. ATR unavailable)

//     // If the raw structural SL distance (before the clamp above) exceeds this
//     // many ATRs, the entry location is treated as poor and the trade is
//     // skipped entirely rather than clamped.
//     private static final double MAX_STRUCTURAL_SL_ATR = 2.5;

//     // =========================================================================
//     // RR-based TP. Now a VERY HIGH ceiling — most trades will exit via the
//     // trailing stop long before ever reaching this; think of it as an
//     // aspirational target for a runaway trend, not a realistic average.
//     // =========================================================================
//     private static final double RR_DEFAULT = 0.75;  // CHANGED: was 1.2 — average RR with partial booking was landing near ~0.9R after fees, this gives more room
//     private static final double RR_STRONG  = 0.9;  // CHANGED: was 1.5 — used only for a clean 30M=6/6 setup

//     // =========================================================================
//     // Trailing system — 4 stages. Staging is now measured in R-multiples
//     // (move-in-favor / initialRisk) instead of "% of original TP distance" —
//     // this stays meaningful even once TP gets extended, and is the more
//     // standard/readable way to reason about trade progress.
//     // =========================================================================
//     private static final double BREAKEVEN_TRIGGER_R    = 0.60; // R
//     // CHANGED: was a FIXED BREAKEVEN_LOCK_PROFIT_PERCENT = 0.15 (i.e. always
//     // lock only 0.15% of entry price, no matter how far price had moved).
//     // That is what caused the "SL hit for just ₹1-2 profit after going 50%+
//     // of the way to TP" problem — the lock never scaled with how far the
//     // trade had actually moved. Now the lock is a FRACTION of the move
//     // already made (moveInFavor), so it grows every cycle the position stays
//     // in stage>=1, instead of sitting frozen at a tiny fixed number.
//     private static final double BREAKEVEN_LOCK_R_FRACTION = 0.35; // lock 35% of the move-in-favor as profit
//     private static final double TRAIL_STAGE2_TRIGGER_R = 1.00; // CHANGED: was 0.75 — was too close to stage 3, tightening the trail too fast
//     private static final double TRAIL_STAGE2_ATR        = 2.00; // CHANGED: was 1.75
//     private static final double TRAIL_STAGE3_TRIGGER_R = 2.00; // CHANGED: was 1.00 — gives the trend real room before the tighter trail kicks in
//     private static final double TRAIL_STAGE3_ATR        = 1.50; // CHANGED: was 1.35
//     private static final double MIN_SL_IMPROVEMENT_ATR  = 0.10; // don't spam the API on tiny moves

//     // =========================================================================
//     // NEW — Partial profit booking. Independent of the SL trailing above:
//     // once price has moved PARTIAL_BOOKING_TRIGGER_R in favor, close
//     // PARTIAL_BOOKING_CLOSE_FRACTION of the position with an immediate
//     // market order (real, locked-in profit), and let the remaining quantity
//     // keep running under the normal trailing/TP system. This directly
//     // targets the "went 50%+ of the way to TP then reversed" case: even if
//     // the remainder later gets stopped out at breakeven, the booked slice
//     // guarantees a real profit for that trade.
//     // =========================================================================
//     private static final boolean PARTIAL_BOOKING_ENABLED = true;
//     private static final double  PARTIAL_BOOKING_TRIGGER_R      = 0.90; // CHANGED: was 0.60 — booking at 0.6R capped too much upside before the move had room to develop
//     private static final double  PARTIAL_BOOKING_CLOSE_FRACTION = 0.33; // CHANGED: was 0.50 — close a third (scale-out) instead of half, leaving more running for the trend

//     // =========================================================================
//     // TP extension — only past stage 3, only while the trend is still valid,
//     // and only once price has covered TP_EXTENSION_TRIGGER_FRACTION of the
//     // distance to the (fixed) original TP. 1H is deliberately NOT part of
//     // this check anymore (too slow for a per-extension gate) — only 15M
//     // structure + 5M momentum are required to still be healthy.
//     // =========================================================================
//     private static final int    MAX_TP_EXTENSIONS = 6;
//     private static final double TP_EXTENSION_ATR  = 1.5;
//     private static final double TP_EXTENSION_TRIGGER_FRACTION = 0.90; // was implicit ~0.85 via stage-3 gate

//     // =========================================================================
//     // NEW — Master on/off switches for the three features added on top of the
//     // original bot. Flip any of these to false to fall back to the OLD
//     // behaviour for that one feature, without touching anything else:
//     //   RISK_BASED_SIZING_ENABLED = false -> calcQuantity() ignores account
//     //       risk % entirely and just uses the fixed MAX_MARGIN_CAP notional
//     //       for every trade (the original behaviour).
//     //   PARTIAL_BOOKING_ENABLED   = false -> no automatic partial market-exit
//     //       at PARTIAL_BOOKING_TRIGGER_R; position runs as a single quantity
//     //       until SL/TP/early-exit closes it.
//     //   TRAILING_ENABLED          = false -> the position keeps its INITIAL
//     //       SL/TP for the whole trade (no breakeven lock, no stage-2/3
//     //       hybrid trail, no TP extension). Early-exit and partial booking
//     //       still work independently of this switch.
//     // =========================================================================
//     private static final boolean RISK_BASED_SIZING_ENABLED = false;
//     private static final boolean TRAILING_ENABLED          = true;

//     // =========================================================================
//     // CHANGED — Risk-based position sizing (was: fixed MAX_MARGIN notional
//     // for every trade regardless of SL distance, so dollar-risk varied
//     // trade-to-trade). Now sized off account risk %, but still hard-capped
//     // at the same notional ceiling as before so a single trade can never use
//     // more than ~120rs margin @10x leverage — matches Ankit's actual
//     // account (3000rs total, 120rs margin per trade is the most he wants to
//     // commit to any one position).
//     // =========================================================================
//     private static final double ACCOUNT_BALANCE          = 2000.0; // Ankit's total account balance (INR)
//     private static final double RISK_PER_TRADE_PERCENT    = 2.0;    // risk 1% of account (~30rs) per trade
//     private static final double MAX_MARGIN_CAP            = 1200.0; // hard notional ceiling — same value as the old MAX_MARGIN, now a SAFETY CAP rather than the sizing method itself (≈120rs margin @10x leverage)
//     private static final double USDT_INR_RATE             = 102.0;   // moved out of calcQuantity so both the risk and cap terms use the same rate

//     private static final double LIMIT_ORDER_BUFFER_PCT = 0.0005;

//     private static final long SCALP_COOLDOWN_MS            = 15 * 60 * 1000L; // CHANGED: was 5 min — reduce overtrading on the same pair
//     private static final long SCALP_ENTRY_SCAN_INTERVAL_MS = 20 * 1000L;

//     private static class PendingSignal {
//         boolean isLong;
//         double confirmHigh, confirmLow;
//         double setupSwingLevel; // the pullback swing captured AT ARM TIME — never re-searched later
//         boolean strongTrend;    // 30M scored a clean 6/6 at arm time -> eligible for RR_STRONG
//         long   createdAtMs;
//     }
//     private static final Map<String, PendingSignal> pendingSignals = new ConcurrentHashMap<>();

//     private static class TrailInfo {
//         boolean isLong;
//         double entryPrice;
//         double initialSL, initialTP, initialRisk;
//         double currentSL, currentTP;
//         double peakPrice;
//         int    stage;           // 0..3
//         int    extensionsUsed;
//         long   lastTrailCandleTime; // 5M candle timestamp when the structure+ATR hybrid trail was last evaluated
//         double  originalQty;         // NEW — full position quantity at entry, used to size the partial-booking close
//         boolean partialBookingDone;  // NEW — true once the partial-profit slice has been closed for this trade
//         long    entryTimeMs;         // NEW — wall-clock fill time, used for the early-exit grace period
//     }
//     private static final Map<String, TrailInfo> trailState = new ConcurrentHashMap<>();

//     private static final Map<String, JSONObject> instrumentCache = new ConcurrentHashMap<>();
//     private static long lastCacheUpdate = 0;
//     private static final Map<String, Long> lastTradeTime = new ConcurrentHashMap<>();

//     private static final String[] COIN_SYMBOLS = {
//        "PIEVERSE","XAU","APE","ERA","US","RAVE","EDEN","LIT","BREV","MAGMA","BLESS","ZAMA",
//         "FRAX","ACU","1000FLOKI","ELSA","LINEA","SPACE","CLO","FIGHT","UMA","MEGA","MAV","TRIA",
//         "YGG","OPN","ROBO","SUI","GLM","MANTRA","SEI","CAKE","AUCTION","SENT","BSB","BASED","IRYS",
//         "ACE","WET","CL","PRL","GENIUS","WIF","MANTA","LSK","AIGENSYN","PHAROS","JUP","AXL","BOME",
//         "SLX","ZEST","AIOT","VVV","CAP","DATAIP","GRVT","TAO","BR","TURBO","BTC","ETH","ZK","LISTA",
//         "A","LTC","XAG","COAI","HANA","ZRO","SKYAI","COPPER","RARE","ETC","M","AKE","XLM","PIXEL",
//         "XAN","ADA","CROSS","XMR","G","DASH","ZEC","ATOM","TRUTH","BCH","NEO","IOST","FLUX","ALGO",
//         "ZRX","COMP","WLFI","POL","DOGE","BAND","OPG","FIDA","PROM","SANTOS","RLC","1000000MOG",
//         "GRASS","PNUT","TRB","KAIA","ARX","XAI","S","4","COTI","CHR","SOLV","SAGA","ORCA","1000LUNC",
//         "MOVE","VIRTUAL","ME","IOTX","GIGGLE","AVA","VELODROME","AIXBT","KMNO","LA","DEXE","ZBT",
//         "GRIFFAIN","BLUAI","CTSI","ROSE","TURTLE","IMX","SUN","APR","TA","ON","BIO","COOKIE",
//         "AVAAI","DOT","TRUMP","MELANIA","GMT","FLOCK","CLANKER","CYS","SUSHI","VTHO","DIA",
//         "SLP","GOAT","BMT","KGEN","GWEI","MUBARAK","LDO","ESP","DRIFT","FORM","PLUME","NIL",
//         "UNI","ZORA","RECALL","INIT","BZ","PARTI","NATGAS","SPX","BANK","AVAX","RIVER","BILL",
//         "ATH","XRP","KERNEL","JST","PUNDIX","HAEDAL","ALPINE","SOON","SOPH","HUMA","TRX","LINK",
//         "HYPE","HIVE","TAIKO","TAG","MYX","NEWT","AIN","USUAL","PUMP","ICNT","BNB","H","BAT",
//         "QTUM","ARC","AIO","BEAT","BTR","ALCH","THETA","VELVET","ARIA","PTB","UB","LIGHT","FF",
//         "EVAAI","GMX","LYN","TAC","LAB","ENJ","AT","MMT","UAI","AAVE","JCT","KSM","HEI","JASMY",
//         "NEAR","TST","SOL","OP","PLAY","INJ","STG","HOLO","ASR","B","LUNA2","RSR","INX","KAT",
//         "ICP","QNT","MAGIC","T","MINA","STX","ACH","LQTY","ID","GRT","NEIRO","XVS","1INCH","SAND",
//         "ANKR","RVN","SFP","KAVA","MANA","HBAR","ARB","MTL","C98","TUT","SIREN","MASK","1000XEC",
//         "AR","ARPA","FIL","LPT","ENS","PEOPLE","LUMIA","DUSK","FLOW","XVG","ARKM","POPCAT","ARK",
//         "MOODENG","SAFE","AXS","BICO","BIGTIME","WAXP","GAS","POWR","TIA","CHIP","STO","ORDI",
//         "BEAMX","1000BONK","PYTH","ETHW","1000RATS","ANIME","OPEN","DYM","BERA","PORTAL","BB",
//         "BANANAS31","CFX","SSV","TNSR","EDU","JELLYJELLY","BLUR","WAL","FHE","WCT","DEEP","SXT",
//         "NAORIS","OG","CVC","AWE","O","BEL","JOE","SQD","1000PEPE","CARV","FET","SAPIEN","MEME",
//         "AVNT","XPIN","ILV","KAS","BNT","STBL","BSV","RIF","SUPER","USTC","METIS","ETHFI","ENA",
//         "1MBABYDOGE","CATI","HMSTR","GPS","SHELL","KAITO","ACT","RPL","BAN","THE","AKT","MORPHO",
//         "CHILLGUY","AERO","MOCA","PENGU","PHA","RED","EPIC","TREE","1000CAT","MAVIA","FARTCOIN",
//         "PAXG","IN","ORDER","VET","ZEN","STABLE","CHZ","NIGHT","NOM","ZKP","SKR","GRAM","BIRB",
//         "CTR","KNC","ZIL","YFI","EGLD","RUNE","ASTR","ONE","1000SHIB","API3","SPELL","WOO","APT",
//         "PENDLE","AGLD","CYBER","CKB","ONG","MOVR","POLYX","TWT","STEEM","ALT","ZETA","REZ","RENDER",
//         "RONIN","STRK","W","SCR","CETUS","IO","MEW","SWARMS","SONIC","PIPPIN","PROMPT","MERL","F",
//         "ESPORTS","PROVE","XNY","USELESS","HEMI","Q","SKY","ZKC","FLUID","MITO","CFG","EDGE","RE",
//         "YB","MET","DOS","FOGO","BTW","ALLO","BROCCOLI714","HYPER","XPL","RESOLV","ASTER","KITE",
//         "SIGN","HOME","MON","CC","SAHARA","MIRA","EUL","TOWNS","SYRUP","C","DOLO","ALICE","BABY",
//         "SOMI","NOT","BARD","SPK","POWER","2Z","BANANA","ENSO","SYN","NXPC","GUN","XTZ","ONT","SKL",
//         "HOT","JTO","DOGS","EIGEN","GTC","GALA","NMR","CGPT","ZEREBRO","VANA","OGN","CELO","USDC",
//         "COW","0G","IOTA","SNX","DYDX","WLD","1000SATS","ONDO","AEVO","BRETT","LAYER","CRV","TLM","KOMA"
//     };

//     private static final Set<String> INTEGER_QTY_PAIRS = Stream.of(COIN_SYMBOLS)
//             .flatMap(s -> Stream.of("B-" + s + "_USDT", s + "_USDT"))
//             .collect(Collectors.toCollection(HashSet::new));

//     private static final String[] COINS_TO_TRADE = Stream.of(COIN_SYMBOLS)
//             .map(s -> "B-" + s + "_USDT")
//             .toArray(String[]::new);

//     // =========================================================================
//     // Market structure (HH/HL vs LL/LH). Returns +1 bullish, -1 bearish, 0.
//     // =========================================================================
//     private static int detectSwingStructure(double[] hi, double[] lo, int lookback) {
//         int n = hi.length;
//         if (n < lookback + 3) return 0;
//         int start = Math.max(1, n - lookback);
//         List<Integer> swingHighIdx = new ArrayList<>();
//         List<Integer> swingLowIdx  = new ArrayList<>();
//         for (int i = start; i < n - 1; i++) {
//             if (hi[i] > hi[i - 1] && hi[i] > hi[i + 1]) swingHighIdx.add(i);
//             if (lo[i] < lo[i - 1] && lo[i] < lo[i + 1]) swingLowIdx.add(i);
//         }
//         boolean hh = false, hl = false, ll = false, lh = false;
//         if (swingHighIdx.size() >= 2) {
//             double h1 = hi[swingHighIdx.get(swingHighIdx.size() - 2)];
//             double h2 = hi[swingHighIdx.get(swingHighIdx.size() - 1)];
//             hh = h2 > h1; lh = h2 < h1;
//         }
//         if (swingLowIdx.size() >= 2) {
//             double l1 = lo[swingLowIdx.get(swingLowIdx.size() - 2)];
//             double l2 = lo[swingLowIdx.get(swingLowIdx.size() - 1)];
//             hl = l2 > l1; ll = l2 < l1;
//         }
//         if (hh && hl) return 1;
//         if (ll && lh) return -1;
//         return 0;
//     }

//     private static double findRecentSwingLow(double[] lo, int lookback) {
//         int n = lo.length;
//         int start = Math.max(1, n - lookback);
//         for (int i = n - 2; i >= start; i--) {
//             if (lo[i] < lo[i - 1] && lo[i] < lo[i + 1]) return lo[i];
//         }
//         int fallbackStart = Math.max(0, n - 8);
//         double min = Double.POSITIVE_INFINITY;
//         for (int i = fallbackStart; i < n; i++) min = Math.min(min, lo[i]);
//         return min;
//     }

//     private static double findRecentSwingHigh(double[] hi, int lookback) {
//         int n = hi.length;
//         int start = Math.max(1, n - lookback);
//         for (int i = n - 2; i >= start; i--) {
//             if (hi[i] > hi[i - 1] && hi[i] > hi[i + 1]) return hi[i];
//         }
//         int fallbackStart = Math.max(0, n - 8);
//         double max = Double.NEGATIVE_INFINITY;
//         for (int i = fallbackStart; i < n; i++) max = Math.max(max, hi[i]);
//         return max;
//     }

//     // Only a MEANINGFUL, CLOSE obstacle blocks the trade — a level sitting
//     // right next to TP itself is not treated as blocking.
//     private static boolean tpBlockedByLevel(boolean isLong, double entry, double tp, double[] hi, double[] lo, int lookback) {
//         double path = Math.abs(tp - entry);
//         double nearThreshold = isLong ? entry + 0.7 * path : entry - 0.7 * path;
//         int n = isLong ? hi.length : lo.length;
//         int start = Math.max(1, n - lookback);
//         if (isLong) {
//             for (int i = n - 2; i >= start; i--) {
//                 if (hi[i] > hi[i - 1] && hi[i] > hi[i + 1]) {
//                     double level = hi[i];
//                     if (level > entry && level <= nearThreshold) return true;
//                 }
//             }
//         } else {
//             for (int i = n - 2; i >= start; i--) {
//                 if (lo[i] < lo[i - 1] && lo[i] < lo[i + 1]) {
//                     double level = lo[i];
//                     if (level < entry && level >= nearThreshold) return true;
//                 }
//             }
//         }
//         return false;
//     }

//     private static class ScoredDirection {
//         boolean valid;
//         int bullScore, bearScore;
//     }

//     private static ScoredDirection analyzeScored6(JSONArray candles) {
//         ScoredDirection r = new ScoredDirection();
//         if (candles == null || candles.length() < EMA_MID + Math.max(ATR_PERIOD, ST_PERIOD) + STRUCTURE_SWING_LOOKBACK) {
//             r.valid = false;
//             return r;
//         }
//         double[] cl = extractCloses(candles);
//         double[] hi = extractHighs(candles);
//         double[] lo = extractLows(candles);

//         double ema9  = calcEMA(cl, EMA_FAST);
//         double ema21 = calcEMA(cl, EMA_MID);
//         double atr   = calcATR(hi, lo, cl, ATR_PERIOD);
//         double price = cl[cl.length - 1];

//         boolean[] stSeries = calcSupertrend(hi, lo, cl, ST_PERIOD, ST_MULTIPLIER);
//         boolean stGreen = stSeries[stSeries.length - 1];

//         double[] ema9Series = calcEMASeries(cl, EMA_FAST);
//         int n = ema9Series.length;
//         int lookback = Math.min(EMA_SLOPE_LOOKBACK_BARS, n - 1);
//         double emaSlope = ema9Series[n - 1] - ema9Series[n - 1 - lookback];
//         boolean slopeUp   = atr > 0 && emaSlope >= HTF_EMA_SLOPE_MIN_ATR * atr;
//         boolean slopeDown = atr > 0 && emaSlope <= -HTF_EMA_SLOPE_MIN_ATR * atr;

//         int structure = detectSwingStructure(hi, lo, STRUCTURE_SWING_LOOKBACK);

//         int bull = 0, bear = 0;
//         if (ema9 > ema21) bull++; else if (ema9 < ema21) bear++;
//         if (price > ema9) bull++; else bear++;
//         if (price > ema21) bull++; else bear++;
//         if (stGreen) bull++; else bear++;
//         if (slopeUp) bull++; else if (slopeDown) bear++;
//         if (structure == 1) bull++; else if (structure == -1) bear++;

//         r.valid = true;
//         r.bullScore = bull;
//         r.bearScore = bear;
//         return r;
//     }

//     private static class Setup15Result {
//         boolean valid;
//         int bullScore, bearScore;
//         double stLower, stUpper;
//     }

//     private static Setup15Result analyzeSetup15M(JSONArray candles) {
//         Setup15Result r = new Setup15Result();
//         if (candles == null || candles.length() < EMA_MID + Math.max(ATR_PERIOD, ST_PERIOD)) {
//             r.valid = false;
//             return r;
//         }
//         double[] cl = extractCloses(candles);
//         double[] hi = extractHighs(candles);
//         double[] lo = extractLows(candles);

//         double ema9  = calcEMA(cl, EMA_FAST);
//         double ema21 = calcEMA(cl, EMA_MID);
//         double atr   = calcATR(hi, lo, cl, ATR_PERIOD);
//         double price = cl[cl.length - 1];

//         boolean[] stSeries = calcSupertrend(hi, lo, cl, ST_PERIOD, ST_MULTIPLIER);
//         boolean stGreen = stSeries[stSeries.length - 1];
//         double[] bands = calcSupertrendBands(hi, lo, cl, ST_PERIOD, ST_MULTIPLIER);
//         r.stLower = bands[0];
//         r.stUpper = bands[1];

//         double[] ema9Series = calcEMASeries(cl, EMA_FAST);
//         int n = ema9Series.length;
//         int lookback = Math.min(EMA_SLOPE_LOOKBACK_BARS, n - 1);
//         double emaSlope = ema9Series[n - 1] - ema9Series[n - 1 - lookback];
//         boolean slopeUp   = atr > 0 && emaSlope >= HTF_EMA_SLOPE_MIN_ATR * atr;
//         boolean slopeDown = atr > 0 && emaSlope <= -HTF_EMA_SLOPE_MIN_ATR * atr;

//         int bull = 0, bear = 0;
//         if (ema9 > ema21) bull++; else if (ema9 < ema21) bear++;
//         if (price > ema9) bull++; else bear++;
//         if (stGreen) bull++; else bear++;
//         if (slopeUp) bull++; else if (slopeDown) bear++;
//         if (price > ema9 && price > ema21) bull++; else if (price < ema9 && price < ema21) bear++;

//         r.valid = true;
//         r.bullScore = bull;
//         r.bearScore = bear;
//         return r;
//     }

//     private static class EntryResult {
//         boolean valid;
//         boolean setupFound;
//         double confirmHigh, confirmLow;
//         double atr5m;
//         double distanceAtr;
//         String reason;
//     }

//     private static EntryResult analyzeEntry5M(JSONArray raw5m, boolean trendUp) {
//         EntryResult t = new EntryResult();
//         int minBars = EMA_MID + Math.max(ATR_PERIOD, Math.max(RSI_PERIOD, VOLUME_MA_PERIOD)) + 5;
//         if (raw5m == null || raw5m.length() < minBars) {
//             t.valid = false;
//             return t;
//         }

//         double[] cl  = extractCloses(raw5m);
//         double[] op  = extractOpens(raw5m);
//         double[] hi  = extractHighs(raw5m);
//         double[] lo  = extractLows(raw5m);
//         double[] vol = extractVolumes(raw5m);
//         int n = cl.length;

//         double ema9  = calcEMA(cl, EMA_FAST);
//         double ema21 = calcEMA(cl, EMA_MID);
//         double atr5m = calcATR(hi, lo, cl, ATR_PERIOD);
//         t.atr5m = atr5m;

//         double entryClose = cl[n - 1], entryOpen = op[n - 1];
//         double entryHigh  = hi[n - 1], entryLow  = lo[n - 1];

//         double nearestEmaDist = Math.min(Math.abs(entryClose - ema9), Math.abs(entryClose - ema21));
//         double distanceAtr = atr5m > 0 ? nearestEmaDist / atr5m : 0;
//         t.distanceAtr = distanceAtr;
//         boolean pulledBack = distanceAtr <= PULLBACK_MAX_ATR;

//         boolean directionalCandle = trendUp ? (entryClose > entryOpen) : (entryClose < entryOpen);
//         double body  = Math.abs(entryClose - entryOpen);
//         double range = entryHigh - entryLow;
//         boolean notDoji = range > 0 && (body / range) >= CANDLE_BODY_RATIO_MIN;

//         double closePositionInRange = range > 0
//                 ? (trendUp ? (entryClose - entryLow) / range : (entryHigh - entryClose) / range)
//                 : 0;
//         boolean rejectionOk = closePositionInRange >= 0.60;

//         double[] ema9Series5m = calcEMASeries(cl, EMA_FAST);
//         int lookback5m = Math.min(EMA_SLOPE_LOOKBACK_BARS, n - 1);
//         double emaSlope5m = ema9Series5m[n - 1] - ema9Series5m[n - 1 - lookback5m];
//         boolean slope5mOk = trendUp
//                 ? (atr5m > 0 && emaSlope5m >= ENTRY_EMA_SLOPE_MIN_ATR * atr5m)
//                 : (atr5m > 0 && emaSlope5m <= -ENTRY_EMA_SLOPE_MIN_ATR * atr5m);

//         boolean mandatoryOk = pulledBack && directionalCandle && notDoji && rejectionOk && slope5mOk;

//         int volStart = Math.max(0, n - 1 - VOLUME_MA_PERIOD);
//         double avgVol = 0; int cnt = 0;
//         for (int i = volStart; i < n - 1; i++) { avgVol += vol[i]; cnt++; }
//         avgVol = cnt > 0 ? avgVol / cnt : 0;
//         boolean volumeOk = avgVol > 0 && vol[n - 1] >= avgVol;

//         double rsi = calcRSI(cl, RSI_PERIOD);
//         boolean rsiOk = trendUp
//                 ? (rsi >= RSI_LONG_MIN && rsi <= RSI_LONG_MAX)
//                 : (rsi >= RSI_SHORT_MIN && rsi <= RSI_SHORT_MAX);

//         int vwapStart = Math.max(0, n - VWAP_LOOKBACK);
//         double cumPV = 0, cumV = 0;
//         for (int i = vwapStart; i < n; i++) {
//             double typical = (hi[i] + lo[i] + cl[i]) / 3.0;
//             cumPV += typical * vol[i];
//             cumV  += vol[i];
//         }
//         double vwap = cumV > 0 ? cumPV / cumV : entryClose;
//         boolean vwapOk = trendUp ? entryClose >= vwap : entryClose <= vwap;

//         boolean[] stSeries5m = calcSupertrend(hi, lo, cl, ST_PERIOD, ST_MULTIPLIER);
//         boolean st5mOk = trendUp ? stSeries5m[stSeries5m.length - 1] : !stSeries5m[stSeries5m.length - 1];

//         boolean emaAlignOk = trendUp ? (entryClose > ema9 && entryClose > ema21) : (entryClose < ema9 && entryClose < ema21);

//         int confirmationScore = (volumeOk ? 1 : 0) + (rsiOk ? 1 : 0) + (vwapOk ? 1 : 0)
//                 + (st5mOk ? 1 : 0) + (emaAlignOk ? 1 : 0);

//         t.setupFound = mandatoryOk && confirmationScore >= ENTRY_CONFIRMATION_MIN_SCORE;
//         t.confirmHigh = entryHigh;
//         t.confirmLow  = entryLow;
//         t.valid = true;
//         t.reason = String.format(
//                 "pullback=%.2fATR(ok=%s) rejection=%s(pos=%.2f) directional=%s notDoji=%s slope=%s vol=%s rsi=%.1f(ok=%s) vwap=%s st5m=%s emaAlign=%s score=%d/5",
//                 distanceAtr, pulledBack, rejectionOk, closePositionInRange, directionalCandle, notDoji, slope5mOk,
//                 volumeOk, rsi, rsiOk, vwapOk, st5mOk, emaAlignOk, confirmationScore);
//         return t;
//     }

//     private static JSONArray dropLastIfForming(JSONArray arr) {
//         if (arr == null || arr.length() < 2) return arr;
//         JSONArray out = new JSONArray();
//         for (int i = 0; i < arr.length() - 1; i++) out.put(arr.getJSONObject(i));
//         return out;
//     }

//     // Shared SL/TP construction from a given anchor level — used both by the
//     // pending-signal path (anchor captured at arm time) and by the safety
//     // sweep / reconstruction path (anchor searched fresh). The final SL
//     // distance is CLAMPED into [SL_MIN_PERCENT, SL_MAX_PERCENT] of the entry
//     // price. Callers on the live-entry path should call
//     // structuralSlTooWide() FIRST and skip the trade if it returns true —
//     // this function assumes the anchor has already been judged "reasonable".
//     private static double[] slTpFromLevel(boolean isLong, double entryPrice, double swingLevel,
//                                            double atr, double tickSize, boolean strongTrend) {
//         double sl;
//         if (atr > 0) {
//             sl = isLong ? swingLevel - SL_BUFFER_ATR * atr : swingLevel + SL_BUFFER_ATR * atr;
//         } else {
//             sl = isLong ? entryPrice * (1 - SL_MAX_PERCENT / 100.0) : entryPrice * (1 + SL_MAX_PERCENT / 100.0);
//         }

//         // If the structural side ended up wrong, fall back to a plain
//         // percentage SL on the correct side.
//         boolean slSideValid = isLong ? sl < entryPrice : sl > entryPrice;
//         if (!slSideValid) {
//             sl = isLong ? entryPrice * (1 - SL_MAX_PERCENT / 100.0) : entryPrice * (1 + SL_MAX_PERCENT / 100.0);
//         }

//         double slPercent = Math.abs(entryPrice - sl) / entryPrice * 100.0;
//         if (slPercent < SL_MIN_PERCENT) {
//             sl = isLong ? entryPrice * (1 - SL_MIN_PERCENT / 100.0) : entryPrice * (1 + SL_MIN_PERCENT / 100.0);
//         } else if (slPercent > SL_MAX_PERCENT) {
//             sl = isLong ? entryPrice * (1 - SL_MAX_PERCENT / 100.0) : entryPrice * (1 + SL_MAX_PERCENT / 100.0);
//         }

//         double rrTarget = strongTrend ? RR_STRONG : RR_DEFAULT;
//         double risk = Math.abs(entryPrice - sl);
//         double tp = isLong ? entryPrice + rrTarget * risk : entryPrice - rrTarget * risk;

//         sl = roundToTick(sl, tickSize);
//         tp = roundToTick(tp, tickSize);
//         double finalSlPercent = Math.abs(entryPrice - sl) / entryPrice * 100.0;
//         return new double[]{sl, tp, finalSlPercent, rrTarget};
//     }

//     // NEW — decides whether the entry location itself is poor, i.e. the RAW
//     // structural SL (before clamping) is farther than MAX_STRUCTURAL_SL_ATR
//     // from entry. If true, the caller should SKIP the trade instead of
//     // relying on slTpFromLevel()'s clamp to rescue it.
//     private static boolean structuralSlTooWide(boolean isLong, double entryPrice, double swingLevel, double atr) {
//         if (atr <= 0) return false; // can't judge — let the ATR-unavailable fallback path handle it
//         double rawSl = isLong ? swingLevel - SL_BUFFER_ATR * atr : swingLevel + SL_BUFFER_ATR * atr;
//         double distanceAtr = Math.abs(entryPrice - rawSl) / atr;
//         return distanceAtr > MAX_STRUCTURAL_SL_ATR;
//     }

//     // Fresh swing search — only used when there is no captured setup anchor
//     // (safety sweep on an externally/pre-existing position, or a restart).
//     private static double[] computeFreshStructuralSlTp(boolean isLong, double entryPrice,
//                                                          double[] hi5m, double[] lo5m, double atr,
//                                                          double tickSize, boolean strongTrend) {
//         double swingLevel = isLong ? findRecentSwingLow(lo5m, SL_SWING_LOOKBACK) : findRecentSwingHigh(hi5m, SL_SWING_LOOKBACK);
//         return slTpFromLevel(isLong, entryPrice, swingLevel, atr, tickSize, strongTrend);
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

//     // CHANGED — was calcQuantity(price, pair) using ONLY a fixed notional
//     // (MAX_MARGIN), so every trade risked a different amount of money
//     // depending on how far its SL happened to land. Now sized so the
//     // trade's actual dollar risk (entry-to-SL distance * qty) is ~1% of
//     // account, THEN capped so it never exceeds the old MAX_MARGIN_CAP
//     // notional either (protects against the risk-based math sizing up too
//     // large on an unusually tight SL, given the small account here).
//     private static double calcQuantity(double price, double slPrice, String pair) {
//         double capBasedQty = MAX_MARGIN_CAP / (price * USDT_INR_RATE);

//         if (!RISK_BASED_SIZING_ENABLED) {
//             // Toggled off — fall back to the ORIGINAL behaviour: fixed
//             // notional for every trade, SL distance ignored entirely.
//             return roundQtyForPair(capBasedQty, pair);
//         }

//         double stopDistance = Math.abs(price - slPrice);
//         if (stopDistance <= 0) return 0;

//         double riskAmountInr = ACCOUNT_BALANCE * (RISK_PER_TRADE_PERCENT / 100.0);
//         double riskBasedQty  = riskAmountInr / (stopDistance * USDT_INR_RATE);

//         double qty = Math.min(riskBasedQty, capBasedQty);
//         return roundQtyForPair(qty, pair);
//     }

//     // Shared quantity-rounding logic (extracted so both calcQuantity and the
//     // partial-booking close use the exact same increment rules).
//     private static double roundQtyForPair(double qty, String pair) {
//         double finalQty = INTEGER_QTY_PAIRS.contains(pair) ? Math.floor(qty) : Math.floor(qty * 100) / 100.0;
//         return Math.max(finalQty, 0);
//     }

//     public static void main(String[] args) {
//         System.out.println("=== Bot starting (trend-continuation cascade + hybrid SL clamp/skip + hybrid structure/ATR trailing "
//                 + "+ R-multiple staging + candle-gated trail + early-exit=" + EARLY_EXIT_ENABLED + ") ===");
//         initInstrumentCache();

//         while (true) {
//             try {
//                 runEntryScan();
//             } catch (Throwable t) {
//                 System.err.println("[MAIN-LOOP] Uncaught error, continuing: " + t.getMessage());
//                 t.printStackTrace();
//             }
//             try {
//                 TimeUnit.MILLISECONDS.sleep(SCALP_ENTRY_SCAN_INTERVAL_MS);
//             } catch (InterruptedException ignored) {
//                 Thread.currentThread().interrupt();
//                 break;
//             }
//         }
//     }

//     private static void runEntryScan() {
//         Set<String> active = getActivePositions();
//         System.out.println("Active positions: " + active);
//         pendingSignals.keySet().removeIf(active::contains);
//         trailState.keySet().removeIf(p -> !active.contains(p));

//         if (active.size() >= MAX_OPEN_POSITIONS) {
//             System.out.println("MAX_OPEN_POSITIONS reached — skipping scan.");
//             updateTrailing();
//             ensureTpSlForOpenPositions();
//             return;
//         }

//         for (String pair : COINS_TO_TRADE) {
//             try {
//                 if (active.size() >= MAX_OPEN_POSITIONS) break;
//                 if (active.contains(pair)) continue;

//                 long lastTrade = lastTradeTime.getOrDefault(pair, 0L);
//                 if (System.currentTimeMillis() - lastTrade < SCALP_COOLDOWN_MS) continue;

//                 JSONArray raw5m = dropLastIfForming(getCandlestickData(pair, RES_5M, BASE_5M_FETCH_COUNT));
//                 if (raw5m == null || raw5m.length() < EMA_MID + ST_PERIOD + STRUCTURE_SWING_LOOKBACK) continue;

//                 PendingSignal pending = pendingSignals.get(pair);

//                 if (pending != null) {
//                     boolean cancelled = false;
//                     if (System.currentTimeMillis() - pending.createdAtMs > SIGNAL_MAX_VALID_MS) {
//                         System.out.println("  Signal expired (stale setup): " + pair);
//                         cancelled = true;
//                     } else {
//                         EntryResult quickCheck = analyzeEntry5M(raw5m, pending.isLong);
//                         if (quickCheck.valid && quickCheck.distanceAtr > OVEREXTENSION_SKIP_ATR) {
//                             System.out.println("  Signal cancelled (price overextended "
//                                     + String.format("%.2f", quickCheck.distanceAtr) + " ATR): " + pair);
//                             cancelled = true;
//                         }
//                     }
//                     if (!cancelled) {
//                         JSONArray raw1h = dropLastIfForming(getCandlestickData(pair, RES_1H, BASE_1H_FETCH_COUNT));
//                         ScoredDirection dir1h = analyzeScored6(raw1h);
//                         if (dir1h.valid) {
//                             boolean stillAgrees = pending.isLong ? dir1h.bullScore >= MACRO_1H_MIN_SCORE : dir1h.bearScore >= MACRO_1H_MIN_SCORE;
//                             if (!stillAgrees) {
//                                 System.out.println("  Signal cancelled (1H direction changed): " + pair);
//                                 cancelled = true;
//                             }
//                         }
//                     }

//                     if (cancelled) {
//                         pendingSignals.remove(pair);
//                     } else {
//                         double currentPrice = getLastPrice(pair);
//                         if (currentPrice > 0) {
//                             boolean breakoutHit = pending.isLong
//                                     ? currentPrice > pending.confirmHigh
//                                     : currentPrice < pending.confirmLow;
//                             if (breakoutHit) {
//                                 tryEnterOnBreakout(pair, pending, raw5m, currentPrice, active);
//                             }
//                         }
//                         continue;
//                     }
//                 }

//                 // ---- No pending signal — look for a fresh one ----

//                 JSONArray raw1h = dropLastIfForming(getCandlestickData(pair, RES_1H, BASE_1H_FETCH_COUNT));
//                 ScoredDirection dir1h = analyzeScored6(raw1h);
//                 if (!dir1h.valid) continue;
//                 boolean trendUp;
//                 if (dir1h.bullScore >= MACRO_1H_MIN_SCORE) trendUp = true;
//                 else if (dir1h.bearScore >= MACRO_1H_MIN_SCORE) trendUp = false;
//                 else continue;

//                 JSONArray raw30m = aggregateCandles(raw5m, GROUP_30M_FROM_5M);
//                 ScoredDirection dir30m = analyzeScored6(raw30m);
//                 if (!dir30m.valid) continue;
//                 int score30 = trendUp ? dir30m.bullScore : dir30m.bearScore;
//                 int opposite30 = trendUp ? dir30m.bearScore : dir30m.bullScore;
//                 if (opposite30 > score30) continue;

//                 boolean strong30 = score30 >= CONFIRM_30M_STRONG_MIN;
//                 boolean acceptable30 = score30 == CONFIRM_30M_ACCEPTABLE;
//                 if (!strong30 && !acceptable30) continue;

//                 JSONArray raw15m = aggregateCandles(raw5m, GROUP_15M_FROM_5M);
//                 Setup15Result setup15 = analyzeSetup15M(raw15m);
//                 if (!setup15.valid) continue;
//                 int score15 = trendUp ? setup15.bullScore : setup15.bearScore;
//                 if (score15 < SETUP_15M_MIN_SCORE) continue;

//                 EntryResult entry5m = analyzeEntry5M(raw5m, trendUp);
//                 if (!entry5m.valid) continue;

//                 if (acceptable30) {
//                     boolean clean15 = score15 >= CLEAN_15M_MIN_FOR_ACCEPTABLE;
//                     boolean clean5 = entry5m.setupFound;
//                     if (!clean15 || !clean5) continue;
//                 }

//                 if (!entry5m.setupFound) continue;

//                 // Capture the pullback swing NOW — this is the anchor the SL
//                 // will use at breakout, so it never drifts to a newer/farther
//                 // swing while the signal is pending.
//                 double[] hi5mArm = extractHighs(raw5m);
//                 double[] lo5mArm = extractLows(raw5m);
//                 double swingLevel = trendUp
//                         ? findRecentSwingLow(lo5mArm, SL_SWING_LOOKBACK)
//                         : findRecentSwingHigh(hi5mArm, SL_SWING_LOOKBACK);

//                 PendingSignal newSignal = new PendingSignal();
//                 newSignal.isLong = trendUp;
//                 newSignal.confirmHigh = entry5m.confirmHigh;
//                 newSignal.confirmLow  = entry5m.confirmLow;
//                 newSignal.setupSwingLevel = swingLevel;
//                 newSignal.strongTrend = (score30 == 6);
//                 newSignal.createdAtMs = System.currentTimeMillis();
//                 pendingSignals.put(pair, newSignal);
//                 System.out.println("  Pending " + (trendUp ? "LONG" : "SHORT") + " signal armed: " + pair
//                         + " | 1H=" + (trendUp ? dir1h.bullScore : dir1h.bearScore) + "/6 30M=" + score30 + "/6 15M=" + score15 + "/5"
//                         + " | trigger=" + (trendUp ? ("break " + newSignal.confirmHigh) : ("break " + newSignal.confirmLow))
//                         + " | " + entry5m.reason);

//             } catch (Exception e) {
//                 System.err.println("Error on " + pair + ": " + e.getMessage());
//             }
//         }

//         System.out.println("\n=== Scan complete ===");
//         updateTrailing();
//         ensureTpSlForOpenPositions();
//     }

//     // Handles an armed pending signal's breakout: uses the ANCHOR captured at
//     // arm time (never a newer swing), checks whether the structural SL is
//     // too wide to trade (skip rather than clamp), applies the TP-location
//     // check, sizes the position, places the order, confirms the fill, and
//     // re-derives final SL/TP off the actual fill price (same anchor).
//     private static void tryEnterOnBreakout(String pair, PendingSignal pending, JSONArray raw5m,
//                                             double currentPrice, Set<String> active) {
//         try {
//             double tickSize = getTickSize(pair);
//             double[] hi5m = extractHighs(raw5m);
//             double[] lo5m = extractLows(raw5m);
//             double atr = calcATR(hi5m, lo5m, extractCloses(raw5m), ATR_PERIOD);

//             if (structuralSlTooWide(pending.isLong, currentPrice, pending.setupSwingLevel, atr)) {
//                 System.out.println("  NO TRADE: " + pair + " — structural SL too wide (>" + MAX_STRUCTURAL_SL_ATR
//                         + " ATR from entry) — skipping rather than force-clamping a poor entry location");
//                 pendingSignals.remove(pair);
//                 return;
//             }

//             double[] preSlTp = slTpFromLevel(pending.isLong, currentPrice, pending.setupSwingLevel, atr, tickSize, pending.strongTrend);

//             JSONArray raw15m = aggregateCandles(raw5m, GROUP_15M_FROM_5M);
//             if (raw15m != null) {
//                 double[] hi15m = extractHighs(raw15m);
//                 double[] lo15m = extractLows(raw15m);
//                 if (tpBlockedByLevel(pending.isLong, currentPrice, preSlTp[1], hi15m, lo15m, TP_LEVEL_LOOKBACK)) {
//                     System.out.println("  NO TRADE: " + pair + " — meaningful nearby " + (pending.isLong ? "resistance" : "support") + " blocks TP");
//                     pendingSignals.remove(pair);
//                     return;
//                 }
//             }

//             double qty = calcQuantity(currentPrice, preSlTp[0], pair); // CHANGED: risk-based, uses the preliminary SL computed above
//             if (qty <= 0) { pendingSignals.remove(pair); return; }

//             System.out.println("\n==== " + pair + " — BREAKOUT ENTRY ====");
//             String side = pending.isLong ? "buy" : "sell";
//             JSONObject orderResp = placeFuturesOrder(side, pair, qty, LEVERAGE,
//                     "email_notification", "isolated", "INR", currentPrice);
//             if (orderResp == null || !orderResp.has("id")) {
//                 System.out.println("  Order failed: " + orderResp);
//                 pendingSignals.remove(pair);
//                 return;
//             }

//             System.out.println("  Order placed! id=" + orderResp.getString("id"));
//             lastTradeTime.put(pair, System.currentTimeMillis());
//             double swingAnchor = pending.setupSwingLevel;
//             boolean strongTrend = pending.strongTrend;
//             boolean isLong = pending.isLong;
//             pendingSignals.remove(pair);

//             double entry = getEntryPrice(pair, orderResp.getString("id"));
//             if (entry <= 0) {
//                 System.out.println("  Could not confirm entry within window — TP/SL handled by safety sweep");
//                 active.add(pair);
//                 return;
//             }
//             System.out.printf("  Entry confirmed: %.6f%n", entry);

//             double[] slTp = slTpFromLevel(isLong, entry, swingAnchor, atr, tickSize, strongTrend);
//             double slPrice = slTp[0], tpPrice = slTp[1], rrUsed = slTp[3];
//             double[] clamped = sanityClampSlTp(isLong, entry, slPrice, tpPrice, tickSize);
//             slPrice = clamped[0]; tpPrice = clamped[1];

//             double risk = Math.abs(entry - slPrice);
//             System.out.println("[ENTRY] " + pair + " " + (isLong ? "LONG" : "SHORT")
//                     + " Entry=" + entry + " SL=" + slPrice + " TP=" + tpPrice
//                     + " Risk=" + risk + " RR=" + String.format("%.2f", rrUsed)
//                     + " SL%=" + String.format("%.2f", slTp[2]) + " (clamped to [" + SL_MIN_PERCENT + "%, " + SL_MAX_PERCENT + "%])");

//             String posId = getPositionId(pair);
//             if (posId != null) {
//                 setTpSlWithRetry(posId, tpPrice, slPrice, pair);
//             } else {
//                 System.out.println("  Position ID not found after retries — safety sweep will handle it");
//             }

//             TrailInfo ti = new TrailInfo();
//             ti.isLong = isLong;
//             ti.entryPrice = entry;
//             ti.initialSL = slPrice;
//             ti.initialTP = tpPrice;
//             ti.initialRisk = risk;
//             ti.currentSL = slPrice;
//             ti.currentTP = tpPrice;
//             ti.peakPrice = entry;
//             ti.stage = 0;
//             ti.extensionsUsed = 0;
//             ti.lastTrailCandleTime = 0L;
//             ti.originalQty = qty;             // NEW
//             ti.partialBookingDone = false;    // NEW
//             ti.entryTimeMs = System.currentTimeMillis(); // NEW
//             trailState.put(pair, ti);

//             active.add(pair);
//         } catch (Exception e) {
//             System.err.println("tryEnterOnBreakout(" + pair + "): " + e.getMessage());
//         }
//     }

//     // =========================================================================
//     // Trailing monitor — runs every cycle for every open position.
//     //   Stage 0->1 (0.5R):  breakeven-style profit lock — applied IMMEDIATELY,
//     //                       every cycle (this is emergency protection, not
//     //                       subject to candle-close gating).
//     //   Stage 2 (0.75R+):   hybrid structure+ATR trail — MAX(recent 5M swing,
//     //                       ATR-trail, old SL) for long / MIN for short.
//     //   Stage 3 (1.0R+):    same hybrid trail, tighter ATR multiple.
//     //   Both stage-2/3 hybrid trail decisions are CANDLE-CLOSE GATED: they
//     //   only re-evaluate once a new 5M candle has closed, so a single wick
//     //   can't yank the SL in mid-candle.
//     //   TP extension only past stage 3, only once trend still valid.
//     //   SL only ever moves in the profitable direction (ratchet-only).
//     // =========================================================================
//     private static void updateTrailing() {
//         Set<String> stillOpen = getActivePositions();
//         for (String pair : stillOpen) {
//             try {
//                 JSONObject pos = findPosition(pair);
//                 if (pos == null) continue;

//                 TrailInfo ti = trailState.get(pair);
//                 if (ti == null) {
//                     // Reconstruct from the exchange position — never reset a
//                     // profitable trade's SL/TP back to some fresh initial guess.
//                     double avgPrice = pos.optDouble("avg_price", 0);
//                     double tpTrig = pos.optDouble("take_profit_trigger", 0);
//                     double slTrig = pos.optDouble("stop_loss_trigger", 0);
//                     if (avgPrice <= 0) continue;
//                     boolean isLong = pos.optDouble("active_pos", 0) >= 0;
//                     ti = new TrailInfo();
//                     ti.isLong = isLong;
//                     ti.entryPrice = avgPrice;
//                     ti.currentSL = slTrig;
//                     ti.currentTP = tpTrig;
//                     ti.initialSL = slTrig;
//                     ti.initialTP = tpTrig;
//                     ti.initialRisk = slTrig > 0 ? Math.abs(avgPrice - slTrig) : 0;
//                     ti.peakPrice = avgPrice;
//                     ti.stage = 0;
//                     ti.extensionsUsed = 0;
//                     ti.lastTrailCandleTime = 0L;
//                     // NEW: reconstructed after a restart — we don't know the true original
//                     // qty or whether a partial close already happened, so use the
//                     // exchange's current active_pos as the best-known qty and assume
//                     // no partial booking yet done. Worst case this delays partial
//                     // booking by one trade rather than double-booking it.
//                     ti.originalQty = Math.abs(pos.optDouble("active_pos", 0));
//                     ti.partialBookingDone = false;
//                     ti.entryTimeMs = System.currentTimeMillis(); // NEW — true fill time unknown after a restart, so grace period restarts too
//                     trailState.put(pair, ti);
//                     if (ti.currentSL <= 0 || ti.currentTP <= 0) continue; // let the safety sweep set initial protection first
//                 }

//                 JSONArray raw5m = dropLastIfForming(getCandlestickData(pair, RES_5M, BASE_5M_FETCH_COUNT));
//                 if (raw5m == null || raw5m.length() < EMA_MID + ST_PERIOD + 5) continue;
//                 double[] hi5m = extractHighs(raw5m), lo5m = extractLows(raw5m), cl5m = extractCloses(raw5m);
//                 double atr5m = calcATR(hi5m, lo5m, cl5m, ATR_PERIOD);
//                 if (atr5m <= 0) continue;

//                 // ---- Early exit (toggleable, independent of trailing below) ----
//                 checkEarlyExitForPosition(pair, ti, raw5m);

//                 // ---- Trailing (only if we have a valid initial baseline) ----
//                 if (ti.initialTP <= 0 || ti.initialRisk <= 0) continue;

//                 double currentPrice = getLastPrice(pair);
//                 if (currentPrice <= 0) continue;
//                 ti.peakPrice = ti.isLong ? Math.max(ti.peakPrice, currentPrice) : Math.min(ti.peakPrice, currentPrice);

//                 double moveInFavor = ti.isLong ? (currentPrice - ti.entryPrice) : (ti.entryPrice - currentPrice);
//                 double rMultiple = moveInFavor / ti.initialRisk;

//                 double tpDistance = ti.isLong ? ti.initialTP - ti.entryPrice : ti.entryPrice - ti.initialTP;
//                 double tpProgress = tpDistance > 0 ? (moveInFavor / tpDistance) : 0;

//                 // ---- NEW: Partial profit booking ----
//                 // Independent of the SL trailing/staging below. Once price has
//                 // moved PARTIAL_BOOKING_TRIGGER_R in favor, close half the
//                 // position immediately at market so a real profit is locked in
//                 // for THAT slice, no matter what the remaining half does next.
//                 // This directly targets the case where price goes most of the
//                 // way to TP and then reverses before the trailing SL has caught
//                 // up — the reason profits were coming out as ₹1-2 per trade.
//                 if (PARTIAL_BOOKING_ENABLED && !ti.partialBookingDone
//                         && rMultiple >= PARTIAL_BOOKING_TRIGGER_R
//                         && ti.originalQty > 0) {
//                     double closeQty = roundQtyForPair(ti.originalQty * PARTIAL_BOOKING_CLOSE_FRACTION, pair);
//                     if (closeQty > 0) {
//                         boolean booked = partialExitPosition(pair, closeQty, ti.isLong, currentPrice);
//                         if (booked) {
//                             System.out.printf("  [PARTIAL-BOOK] %s %s R=%.2f closed %.4f of %.4f qty — remainder keeps trailing%n",
//                                     pair, ti.isLong ? "LONG" : "SHORT", rMultiple, closeQty, ti.originalQty);
//                             ti.partialBookingDone = true;
//                             ti.originalQty -= closeQty; // track what's left, for reference
//                         } else {
//                             System.out.println("  [PARTIAL-BOOK] " + pair + " attempt failed — will retry next cycle");
//                         }
//                     }
//                 }

//                 // ---- Trailing / breakeven / TP-extension (all gated by TRAILING_ENABLED) ----
//                 // When turned off, the position simply keeps its INITIAL SL/TP
//                 // for the whole trade — no breakeven lock, no stage-2/3 hybrid
//                 // trail, no TP extension. Early-exit and partial booking above
//                 // are unaffected since they don't depend on this switch.
//                 if (TRAILING_ENABLED) {
//                 int newStage = rMultiple >= TRAIL_STAGE3_TRIGGER_R ? 3
//                         : rMultiple >= TRAIL_STAGE2_TRIGGER_R ? 2
//                         : rMultiple >= BREAKEVEN_TRIGGER_R ? 1 : 0;
//                 if (newStage > ti.stage) ti.stage = newStage;

//                 double tick = getTickSize(pair);
//                 double candidateSL = ti.currentSL;

//                 // Stage 1 — breakeven-style profit lock. Immediate, every cycle.
//                 // CHANGED: lock now scales with how far price has actually moved
//                 // (moveInFavor * BREAKEVEN_LOCK_R_FRACTION) instead of a frozen
//                 // 0.15% of entry price. This is what was causing the SL to sit at
//                 // a tiny fixed level regardless of how far the trade had already
//                 // run, so a pullback from 60%+ of the way to TP wiped almost the
//                 // whole move down to a ₹1-2 profit. Now the lock keeps ratcheting
//                 // up with moveInFavor every cycle this stage is active.
//                 if (ti.stage >= 1) {
//                     double lockSL = ti.isLong
//                             ? ti.entryPrice + (moveInFavor * BREAKEVEN_LOCK_R_FRACTION)
//                             : ti.entryPrice - (moveInFavor * BREAKEVEN_LOCK_R_FRACTION);
//                     if (ti.isLong ? lockSL > candidateSL : lockSL < candidateSL) candidateSL = lockSL;
//                 }

//                 // Stage 2/3 — hybrid structure+ATR trail. Candle-close gated.
//                 long lastCandleTime = raw5m.length() > 0
//                         ? raw5m.getJSONObject(raw5m.length() - 1).optLong("time", 0)
//                         : 0;
//                 boolean newCandleClosed = lastCandleTime > 0 && lastCandleTime != ti.lastTrailCandleTime;

//                 if (ti.stage >= 2 && (newCandleClosed || ti.lastTrailCandleTime == 0)) {
//                     double trailMult = ti.stage >= 3 ? TRAIL_STAGE3_ATR : TRAIL_STAGE2_ATR;
//                     double atrSL = ti.isLong ? currentPrice - trailMult * atr5m : currentPrice + trailMult * atr5m;
//                     double structureSL = ti.isLong
//                             ? findRecentSwingLow(lo5m, SL_SWING_LOOKBACK)
//                             : findRecentSwingHigh(hi5m, SL_SWING_LOOKBACK);
//                     // MAX(structure, atr-trail) for long / MIN for short — the
//                     // more conservative-but-still-valid candidate wins.
//                     double hybridCandidate = ti.isLong ? Math.max(atrSL, structureSL) : Math.min(atrSL, structureSL);
//                     if (ti.isLong ? hybridCandidate > candidateSL : hybridCandidate < candidateSL) candidateSL = hybridCandidate;
//                     ti.lastTrailCandleTime = lastCandleTime;
//                 }

//                 boolean changed = false;
//                 double minImprovement = Math.max(MIN_SL_IMPROVEMENT_ATR * atr5m, tick);
//                 boolean meaningfulImprovement = ti.isLong
//                         ? (candidateSL - ti.currentSL) >= minImprovement
//                         : (ti.currentSL - candidateSL) >= minImprovement;

//                 if (meaningfulImprovement) {
//                     candidateSL = roundToTick(candidateSL, tick);
//                     // Never move SL backward — ratchet-only.
//                     if (ti.isLong ? candidateSL > ti.currentSL : candidateSL < ti.currentSL) {
//                         System.out.printf("  [TRAIL] %s %s R=%.2f stage=%d SL moved %.6f -> %.6f%n",
//                                 pair, ti.isLong ? "LONG" : "SHORT", rMultiple, ti.stage, ti.currentSL, candidateSL);
//                         ti.currentSL = candidateSL;
//                         changed = true;
//                     } else {
//                         System.out.println("  [TRAIL] " + pair + " candidate SL would worsen existing SL — ignoring update");
//                     }
//                 }

//                 // ---- TP extension (only past stage 3, close to original TP, trend still valid) ----
//                 if (ti.stage >= 3 && ti.extensionsUsed < MAX_TP_EXTENSIONS && tpProgress >= TP_EXTENSION_TRIGGER_FRACTION) {
//                     JSONArray raw15m = aggregateCandles(raw5m, GROUP_15M_FROM_5M);
//                     boolean trendValid = checkTrendValidForExtension(ti.isLong, raw15m, raw5m);
//                     if (trendValid) {
//                         double newTP = ti.isLong ? currentPrice + atr5m * TP_EXTENSION_ATR : currentPrice - atr5m * TP_EXTENSION_ATR;
//                         newTP = roundToTick(newTP, tick);
//                         if (ti.isLong ? newTP > ti.currentTP : newTP < ti.currentTP) {
//                             System.out.println("  [TP EXTENSION] " + pair + " " + (ti.isLong ? "LONG" : "SHORT")
//                                     + " TP " + ti.currentTP + " -> " + newTP
//                                     + " Extension " + (ti.extensionsUsed + 1) + "/" + MAX_TP_EXTENSIONS);
//                             ti.currentTP = newTP;
//                             ti.extensionsUsed++;
//                             changed = true;
//                         }
//                     }
//                 }

//                 if (changed) {
//                     String posId = pos.optString("id", null);
//                     if (posId != null) setTpSlWithRetry(posId, ti.currentTP, ti.currentSL, pair);
//                 }
//                 } // end if (TRAILING_ENABLED)
//             } catch (Exception e) {
//                 System.err.println("updateTrailing(" + pair + "): " + e.getMessage());
//             }
//         }
//     }

//     // TP-extension trend check — 1H is deliberately NOT part of this (too
//     // slow for a per-extension gate). Only 15M structure and 5M momentum
//     // (EMA9 slope) need to still be healthy.
//     private static boolean checkTrendValidForExtension(boolean isLong, JSONArray raw15m, JSONArray raw5m) {
//         Setup15Result s15 = analyzeSetup15M(raw15m);
//         if (s15.valid) {
//             int score15 = isLong ? s15.bullScore : s15.bearScore;
//             if (score15 < SETUP_15M_MIN_SCORE) return false;
//         }
//         if (raw5m != null && raw5m.length() >= EMA_FAST + EMA_SLOPE_LOOKBACK_BARS + 1) {
//             double[] cl5 = extractCloses(raw5m);
//             double[] hi5 = extractHighs(raw5m);
//             double[] lo5 = extractLows(raw5m);
//             double atr5 = calcATR(hi5, lo5, cl5, ATR_PERIOD);
//             double[] ema9Series = calcEMASeries(cl5, EMA_FAST);
//             int n = ema9Series.length;
//             int lb = Math.min(EMA_SLOPE_LOOKBACK_BARS, n - 1);
//             double slope = ema9Series[n - 1] - ema9Series[n - 1 - lb];
//             boolean momentumOk = isLong
//                     ? (atr5 <= 0 || slope >= -ENTRY_EMA_SLOPE_MIN_ATR * atr5)
//                     : (atr5 <= 0 || slope <= ENTRY_EMA_SLOPE_MIN_ATR * atr5);
//             if (!momentumOk) return false;
//         }
//         return true;
//     }

//     // =========================================================================
//     // Early-exit system (toggleable via EARLY_EXIT_ENABLED). Independent of
//     // TP/SL/trailing — runs alongside it every cycle when enabled.
//     //   5M  -> warning only, NEVER exits (5M is too noisy to act on alone)
//     //   15M -> confirmed reversal -> exit (Supertrend flip + close breaks
//     //          EMA21, OR structure breaks + EMA9 slope is lost)
//     //   30M -> stronger/emergency reversal -> exit immediately (Supertrend
//     //          flip + EMA9/EMA21 cross against the position)
//     // =========================================================================
//     private static void checkEarlyExitForPosition(String pair, TrailInfo ti, JSONArray raw5m) {
//         if (!EARLY_EXIT_ENABLED || ti == null) return;
//         try {
//             // NEW — grace period: don't let early-exit act at all until the
//             // trade has had a few 5M candles to actually develop. Fixes
//             // positions getting cut on the very first wobble right after entry.
//             long barsElapsed = (System.currentTimeMillis() - ti.entryTimeMs) / (5L * 60 * 1000);
//             if (barsElapsed < EARLY_EXIT_GRACE_BARS_5M) return;

//             double[] hi5 = extractHighs(raw5m), lo5 = extractLows(raw5m), cl5 = extractCloses(raw5m);
//             if (hi5.length < EMA_MID + ST_PERIOD + 5) return;

//             // ---- 5M — warning only ----
//             boolean[] st5 = calcSupertrend(hi5, lo5, cl5, ST_PERIOD, ST_MULTIPLIER);
//             boolean st5Green = st5[st5.length - 1];
//             boolean warn5m = ti.isLong ? !st5Green : st5Green;
//             if (warn5m) {
//                 System.out.println("  [EARLY-EXIT][5M-WARN] " + pair + " Supertrend against position — watching only, no action");
//             }

//             // ---- 15M — confirmed exit ----
//             JSONArray raw15m = aggregateCandles(raw5m, GROUP_15M_FROM_5M);
//             if (raw15m != null && raw15m.length() >= EMA_MID + ST_PERIOD + 3) {
//                 double[] hi15 = extractHighs(raw15m), lo15 = extractLows(raw15m), cl15 = extractCloses(raw15m);
//                 boolean[] st15 = calcSupertrend(hi15, lo15, cl15, ST_PERIOD, ST_MULTIPLIER);
//                 boolean st15Green = st15[st15.length - 1];
//                 double ema21_15 = calcEMA(cl15, EMA_MID);
//                 double lastClose15 = cl15[cl15.length - 1];
//                 int structure15 = detectSwingStructure(hi15, lo15, STRUCTURE_SWING_LOOKBACK);

//                 double[] ema9Series15 = calcEMASeries(cl15, EMA_FAST);
//                 int n15 = ema9Series15.length;
//                 int lb15 = Math.min(EMA_SLOPE_LOOKBACK_BARS, n15 - 1);
//                 double slope15 = ema9Series15[n15 - 1] - ema9Series15[n15 - 1 - lb15];
//                 double atr15 = calcATR(hi15, lo15, cl15, ATR_PERIOD);
//                 boolean slopeLost15 = ti.isLong
//                         ? (atr15 > 0 && slope15 <= -HTF_EMA_SLOPE_MIN_ATR * atr15)
//                         : (atr15 > 0 && slope15 >= HTF_EMA_SLOPE_MIN_ATR * atr15);

//                 boolean stFlip15 = ti.isLong ? !st15Green : st15Green;
//                 boolean closeBreak15 = ti.isLong ? lastClose15 < ema21_15 : lastClose15 > ema21_15;
//                 boolean structureBreak15 = ti.isLong ? structure15 == -1 : structure15 == 1;

//                 // CHANGED — was (stFlip15 && closeBreak15) || (structureBreak15 && slopeLost15),
//                 // an OR-of-pairs that only needed 2 of these 4 signals, in a
//                 // fixed pairing. A 15M Supertrend flip on its own is common
//                 // noise during a normal pullback. Now all 4 signals are
//                 // combined into a score and EARLY_EXIT_15M_MIN_SIGNALS (default 3)
//                 // of them must agree — a much stronger bar for confirmed reversal.
//                 int reversalScore15 = (stFlip15 ? 1 : 0) + (closeBreak15 ? 1 : 0)
//                         + (structureBreak15 ? 1 : 0) + (slopeLost15 ? 1 : 0);
//                 boolean exit15 = reversalScore15 >= EARLY_EXIT_15M_MIN_SIGNALS;
//                 if (exit15) {
//                     System.out.println("  [EARLY-EXIT][15M] " + pair + " " + (ti.isLong ? "LONG" : "SHORT")
//                             + " reversal confirmed (score=" + reversalScore15 + "/4) — exiting position");
//                     exitTrackedPosition(pair);
//                     return;
//                 }
//             }

//             // ---- 30M — emergency exit ----
//             JSONArray raw30m = aggregateCandles(raw5m, GROUP_30M_FROM_5M);
//             if (raw30m != null && raw30m.length() >= EMA_MID + ST_PERIOD + 3) {
//                 double[] cl30 = extractCloses(raw30m), hi30 = extractHighs(raw30m), lo30 = extractLows(raw30m);
//                 boolean[] st30 = calcSupertrend(hi30, lo30, cl30, ST_PERIOD, ST_MULTIPLIER);
//                 boolean st30Green = st30[st30.length - 1];
//                 double ema9_30 = calcEMA(cl30, EMA_FAST);
//                 double ema21_30 = calcEMA(cl30, EMA_MID);
//                 boolean stFlip30 = ti.isLong ? !st30Green : st30Green;
//                 boolean emaCrossAgainst30 = ti.isLong ? ema9_30 < ema21_30 : ema9_30 > ema21_30;
//                 if (stFlip30 && emaCrossAgainst30) {
//                     System.out.println("  [EARLY-EXIT][30M-EMERGENCY] " + pair + " " + (ti.isLong ? "LONG" : "SHORT")
//                             + " major reversal — exiting position immediately");
//                     exitTrackedPosition(pair);
//                 }
//             }
//         } catch (Exception e) {
//             System.err.println("checkEarlyExitForPosition(" + pair + "): " + e.getMessage());
//         }
//     }

//     private static void exitTrackedPosition(String pair) {
//         try {
//             JSONObject pos = findPosition(pair);
//             if (pos == null) return;
//             String posId = pos.optString("id", null);
//             if (posId == null) {
//                 System.out.println("  [EARLY-EXIT] " + pair + " — position ID missing, cannot exit");
//                 return;
//             }
//             exitPositionNow(posId, pair);
//         } catch (Exception e) {
//             System.err.println("exitTrackedPosition(" + pair + "): " + e.getMessage());
//         }
//     }

//     // NOTE: verify this exact endpoint/payload shape against CoinDCX's current
//     // Futures "Exit Position" API docs before relying on it live — it is
//     // reconstructed here to match the pattern of the other authenticated
//     // endpoints already used in this file (create_tpsl, positions, orders).
//     private static boolean exitPositionNow(String posId, String pair) {
//         try {
//             JSONObject body = new JSONObject();
//             body.put("timestamp", Instant.now().toEpochMilli());
//             body.put("id", posId);
//             String resp = authPost(BASE_URL + "/exchange/v1/derivatives/futures/positions/exit", body.toString());
//             JSONObject r = new JSONObject(resp);
//             boolean ok = !r.has("err_code_dcx");
//             System.out.println("  [EARLY-EXIT] " + pair + " exit request " + (ok ? "sent successfully" : "FAILED: " + r));
//             return ok;
//         } catch (Exception e) {
//             System.err.println("exitPositionNow(" + pair + "): " + e.getMessage());
//             return false;
//         }
//     }

//     // Never overwrite an existing better (possibly trailed) SL/TP. Only fills
//     // in whichever side is genuinely missing.
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
//                 if (tpTrig > 0 && slTrig > 0) continue; // both present — trailing owns updates from here, never touch

//                 System.out.println("  [SWEEP] " + pair + " missing TP and/or SL — computing fallback protection...");
//                 boolean isLong = pos.optDouble("active_pos", 0) >= 0;
//                 double tick = getTickSize(pair);
//                 TrailInfo ti = trailState.get(pair);

//                 double sl, tp;
//                 if (ti != null && ti.currentSL > 0 && ti.currentTP > 0) {
//                     sl = ti.currentSL; tp = ti.currentTP; // trust our own tracked (possibly trailed) values
//                 } else {
//                     JSONArray raw5m = dropLastIfForming(getCandlestickData(pair, RES_5M, BASE_5M_FETCH_COUNT));
//                     double[] hi5m = null, lo5m = null; double atr5m = 0;
//                     if (raw5m != null && raw5m.length() >= ATR_PERIOD + SL_SWING_LOOKBACK) {
//                         hi5m = extractHighs(raw5m); lo5m = extractLows(raw5m);
//                         atr5m = calcATR(hi5m, lo5m, extractCloses(raw5m), ATR_PERIOD);
//                     }
//                     double[] slTp = (hi5m != null && atr5m > 0)
//                             ? computeFreshStructuralSlTp(isLong, avgPrice, hi5m, lo5m, atr5m, tick, false)
//                             : null;
//                     if (slTp == null) {
//                         sl = isLong ? avgPrice * (1 - SL_HARD_PERCENT_CAP / 100.0) : avgPrice * (1 + SL_HARD_PERCENT_CAP / 100.0);
//                         tp = isLong ? avgPrice + RR_DEFAULT * (avgPrice - sl) : avgPrice - RR_DEFAULT * (sl - avgPrice);
//                         System.out.println("  [SWEEP] structural SL unavailable for " + pair + " — using hard % fallback cap");
//                     } else {
//                         sl = slTp[0]; tp = slTp[1];
//                     }
//                 }

//                 // Preserve whichever side the exchange already has set — never overwrite it.
//                 if (slTrig > 0) sl = slTrig;
//                 if (tpTrig > 0) tp = tpTrig;

//                 double[] clamped = sanityClampSlTp(isLong, avgPrice, sl, tp, tick);
//                 sl = clamped[0]; tp = clamped[1];

//                 String posId = pos.optString("id", null);
//                 if (posId != null) {
//                     System.out.printf("  [SWEEP] %s fallback SL=%.6f TP=%.6f%n", pair, sl, tp);
//                     setTpSlWithRetry(posId, tp, sl, pair);
//                     if (ti == null) {
//                         TrailInfo nt = new TrailInfo();
//                         nt.isLong = isLong; nt.entryPrice = avgPrice;
//                         nt.currentSL = sl; nt.currentTP = tp;
//                         nt.initialSL = sl; nt.initialTP = tp;
//                         nt.initialRisk = Math.abs(avgPrice - sl);
//                         nt.peakPrice = avgPrice; nt.stage = 0; nt.extensionsUsed = 0;
//                         nt.lastTrailCandleTime = 0L;
//                         nt.originalQty = Math.abs(pos.optDouble("active_pos", 0)); // NEW
//                         nt.partialBookingDone = false;                              // NEW
//                         nt.entryTimeMs = System.currentTimeMillis();                // NEW
//                         trailState.put(pair, nt);
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

//     private static double[] calcEMASeries(double[] d, int period) {
//         double[] out = new double[d.length];
//         if (d.length < period) {
//             double last = d.length > 0 ? d[d.length - 1] : 0;
//             Arrays.fill(out, last);
//             return out;
//         }
//         double k = 2.0 / (period + 1);
//         double ema = 0;
//         for (int i = 0; i < period; i++) ema += d[i];
//         ema /= period;
//         for (int i = 0; i < period; i++) out[i] = ema;
//         out[period - 1] = ema;
//         for (int i = period; i < d.length; i++) {
//             ema = d[i] * k + ema * (1 - k);
//             out[i] = ema;
//         }
//         return out;
//     }

//     private static double calcEMA(double[] d, int period) {
//         if (d.length < period) return 0;
//         double k = 2.0 / (period + 1), ema = 0;
//         for (int i = 0; i < period; i++) ema += d[i];
//         ema /= period;
//         for (int i = period; i < d.length; i++) ema = d[i] * k + ema * (1 - k);
//         return ema;
//     }

//     private static double calcRSI(double[] closes, int period) {
//         if (closes.length < period + 1) return 50.0;

//         double avgGain = 0, avgLoss = 0;
//         for (int i = 1; i <= period; i++) {
//             double change = closes[i] - closes[i - 1];
//             if (change > 0) avgGain += change;
//             else avgLoss += -change;
//         }
//         avgGain /= period;
//         avgLoss /= period;

//         for (int i = period + 1; i < closes.length; i++) {
//             double change = closes[i] - closes[i - 1];
//             double gain = Math.max(change, 0);
//             double loss = Math.max(-change, 0);
//             avgGain = (avgGain * (period - 1) + gain) / period;
//             avgLoss = (avgLoss * (period - 1) + loss) / period;
//         }

//         if (avgLoss == 0) return 100.0;
//         double rs = avgGain / avgLoss;
//         return 100.0 - (100.0 / (1.0 + rs));
//     }

//     private static BigDecimal roundToTickBD(double price, double tick) {
//         if (tick <= 0) return BigDecimal.valueOf(price);
//         BigDecimal bdPrice = BigDecimal.valueOf(price);
//         BigDecimal bdTick  = BigDecimal.valueOf(tick);
//         BigDecimal multiples = bdPrice.divide(bdTick, 0, RoundingMode.HALF_UP);
//         BigDecimal result = multiples.multiply(bdTick);
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
//     private static double[] extractVolumes(JSONArray a) {
//         double[] o = new double[a.length()];
//         for (int i = 0; i < a.length(); i++) o[i] = a.getJSONObject(i).optDouble("volume", 0);
//         return o;
//     }

//     private static JSONArray getCandlestickData(String pair, String resolution, int count) {
//         try {
//             long minsPerBar;
//             switch (resolution) {
//                 case "1":   minsPerBar = 1;   break;
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

//     // NEW — places an immediate market order on the SAME pair, OPPOSITE side
//     // of the open position. Per the CoinDCX Futures docs, there is a single
//     // position per pair per user and "all orders would add/subtract the
//     // quantity of this position" — so an opposite-side order here reduces
//     // (partially exits) the existing position rather than opening a new one.
//     // Used only for partial profit booking; qty must be strictly less than
//     // the full position size.
//     public static JSONObject placeMarketOrder(String side, String pair, double qty, int lev,
//                                                String notif, String marginType, String marginCcy,
//                                                double currentPrice) {
//         try {
//             JSONObject order = new JSONObject();
//             order.put("side",                       side.toLowerCase());
//             order.put("pair",                       pair);
//             order.put("order_type",                 "market_order");
//             order.put("price",                      currentPrice); // reference price; ignored for market orders per API docs
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
//             System.err.println("placeMarketOrder: " + e.getMessage());
//             return null;
//         }
//     }

//     // NEW — closes `closeQty` of an open position at market, for partial
//     // profit booking. isLong tells us which side is currently held, so the
//     // closing order goes the opposite way (sell to reduce a long, buy to
//     // reduce a short).
//     private static boolean partialExitPosition(String pair, double closeQty, boolean isLong, double currentPrice) {
//         try {
//             String side = isLong ? "sell" : "buy";
//             JSONObject resp = placeMarketOrder(side, pair, closeQty, LEVERAGE,
//                     "email_notification", "isolated", "INR", currentPrice);
//             return resp != null && resp.has("id");
//         } catch (Exception e) {
//             System.err.println("partialExitPosition(" + pair + "): " + e.getMessage());
//             return false;
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
//             double volSum = 0;
//             for (int j = i; j < i + groupSize; j++) {
//                 JSONObject c = source.getJSONObject(j);
//                 high = Math.max(high, c.getDouble("high"));
//                 low  = Math.min(low,  c.getDouble("low"));
//                 volSum += c.optDouble("volume", 0);
//             }
//             JSONObject merged = new JSONObject();
//             merged.put("open", open);
//             merged.put("close", close);
//             merged.put("high", high);
//             merged.put("low", low);
//             merged.put("volume", volSum);
//             result.put(merged);
//         }
//         return result;
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
// import java.time.Instant;
// import java.util.*;
// import java.util.concurrent.ConcurrentHashMap;
// import java.util.concurrent.TimeUnit;
// import java.util.stream.Collectors;
// import java.util.stream.Stream;

// public class CoinDCXFuturesTrader8C_BUY_SELL_NEW_LOGIC_THREE {

//     // =========================================================================
//     // API Configuration (unchanged)
//     // =========================================================================
//     private static final String API_KEY    = System.getenv("DELTA_API_KEY");
//     private static final String API_SECRET = System.getenv("DELTA_API_SECRET");
//     private static final String BASE_URL       = "https://api.coindcx.com";
//     private static final String PUBLIC_API_URL = "https://public.coindcx.com";

//     private static final int LEVERAGE = 10;

//     private static final int MAX_ENTRY_PRICE_CHECKS = 20;
//     private static final int ENTRY_CHECK_DELAY_MS    = 1000;

//     private static final int  TPSL_MAX_RETRIES    = 3;
//     private static final long TPSL_RETRY_DELAY_MS = 2000L;

//     private static final long TICK_CACHE_TTL_MS = 3_600_000L;

//     private static final int MAX_OPEN_POSITIONS = 120;

//     private static final int  POSITION_ID_MAX_RETRIES = 5;
//     private static final long POSITION_ID_RETRY_DELAY_MS = 1500L;

//     // =========================================================================
//     // EARLY-EXIT SYSTEM — single on/off switch. Flip this to true/false to
//     // enable/disable the entire 3-level early-exit layer without touching
//     // anything else. When false, positions exit purely via TP/SL/trailing,
//     // exactly like before.
//     // =========================================================================
//     private static final boolean EARLY_EXIT_ENABLED = true;

//     // =========================================================================
//     // Indicator periods (unchanged — no new indicators added)
//     // =========================================================================
//     private static final int EMA_FAST = 9;
//     private static final int EMA_MID  = 21;
//     private static final int ATR_PERIOD = 14;
//     private static final int ST_PERIOD     = 10;
//     private static final double ST_MULTIPLIER = 3.0;
//     private static final int RSI_PERIOD = 14;
//     private static final int VOLUME_MA_PERIOD = 20;
//     private static final int VWAP_LOOKBACK = 20;

//     private static final String RES_5M = "5";
//     private static final String RES_1H = "60";

//     private static final int BASE_5M_FETCH_COUNT = 450;
//     private static final int GROUP_15M_FROM_5M = 3;
//     private static final int GROUP_30M_FROM_5M = 6;
//     private static final int BASE_1H_FETCH_COUNT = 90;

//     private static final int EMA_SLOPE_LOOKBACK_BARS   = 5;
//     private static final double HTF_EMA_SLOPE_MIN_ATR   = 0.10;
//     private static final double ENTRY_EMA_SLOPE_MIN_ATR = 0.15;

//     private static final int STRUCTURE_SWING_LOOKBACK = 30;
//     private static final int SL_SWING_LOOKBACK         = 20;
//     private static final int TP_LEVEL_LOOKBACK         = 40;

//     // =========================================================================
//     // 1H macro direction / 30M confirmation, scored out of 6.
//     // =========================================================================
//     private static final int MACRO_1H_MIN_SCORE     = 4;
//     private static final int CONFIRM_30M_STRONG_MIN = 5;
//     private static final int CONFIRM_30M_ACCEPTABLE = 4;
//     private static final int CLEAN_15M_MIN_FOR_ACCEPTABLE = 4;

//     // =========================================================================
//     // 15M setup, scored out of 5.
//     // =========================================================================
//     private static final int SETUP_15M_MIN_SCORE = 3;

//     // =========================================================================
//     // 5M pullback + rejection (mandatory) + slope (mandatory).
//     // =========================================================================
//     private static final double PULLBACK_MAX_ATR       = 1.5;
//     private static final double OVEREXTENSION_SKIP_ATR  = 2.0;
//     private static final double CANDLE_BODY_RATIO_MIN   = 0.40;

//     // =========================================================================
//     // 5M supporting confirmation score out of 5 (min 3/5).
//     // =========================================================================
//     private static final int    ENTRY_CONFIRMATION_MIN_SCORE = 3;
//     private static final double RSI_LONG_MIN  = 40, RSI_LONG_MAX  = 70;
//     private static final double RSI_SHORT_MIN = 30, RSI_SHORT_MAX = 60;

//     // =========================================================================
//     // Pending breakout-entry signal.
//     // =========================================================================
//     private static final long SIGNAL_MAX_VALID_MS = 15L * 60 * 1000L; // 15 minutes

//     // =========================================================================
//     // Structural SL: anchored to the pullback swing captured when the signal
//     // was ARMED (not re-searched at breakout time), + ATR buffer, then
//     // CLAMPED into a configurable [SL_MIN_PERCENT, SL_MAX_PERCENT] range so
//     // SL stays small and directly tunable.
//     //
//     // HYBRID PHILOSOPHY: the clamp is kept (so a good, reasonable structural
//     // SL always lands in this tight tunable band) — but it is no longer used
//     // to rescue a genuinely bad entry location. If the RAW structural SL
//     // (swing + buffer, before clamping) is farther than MAX_STRUCTURAL_SL_ATR
//     // from entry, that means the entry itself is poor, and the trade is
//     // SKIPPED instead of being force-clamped into a tiny SL that doesn't
//     // reflect real structure.
//     // =========================================================================
//     private static final double SL_BUFFER_ATR   = 0.35; // widened buffer off the swing (was 0.15)
//     private static final double SL_MIN_PERCENT  = 4.0;  // SL can never be tighter than this (tick-noise floor) — tune here
//     private static final double SL_MAX_PERCENT  = 6.0;  // SL can never be wider than this — "very very small" SL, tune here
//     private static final double SL_HARD_PERCENT_CAP  = 6.0;  // absolute safety-net fallback ONLY (e.g. ATR unavailable)

//     // If the raw structural SL distance (before the clamp above) exceeds this
//     // many ATRs, the entry location is treated as poor and the trade is
//     // skipped entirely rather than clamped.
//     private static final double MAX_STRUCTURAL_SL_ATR = 2.5;

//     // =========================================================================
//     // RR-based TP. Now a VERY HIGH ceiling — most trades will exit via the
//     // trailing stop long before ever reaching this; think of it as an
//     // aspirational target for a runaway trend, not a realistic average.
//     // =========================================================================
//     private static final double RR_DEFAULT = 1.0;  // was 1.5 — tune this for how "high" you want the ceiling
//     private static final double RR_STRONG  = 1.2; // used only for a clean 30M=6/6 setup — was 1.8

//     // =========================================================================
//     // Trailing system — 4 stages. Staging is now measured in R-multiples
//     // (move-in-favor / initialRisk) instead of "% of original TP distance" —
//     // this stays meaningful even once TP gets extended, and is the more
//     // standard/readable way to reason about trade progress.
//     // =========================================================================
//     private static final double BREAKEVEN_TRIGGER_R    = 0.60; // R
//     private static final double BREAKEVEN_LOCK_PROFIT_PERCENT = 0.15; // %
//     private static final double TRAIL_STAGE2_TRIGGER_R = 0.75; // R — structure+ATR hybrid trail begins
//     private static final double TRAIL_STAGE2_ATR        = 1.75;
//     private static final double TRAIL_STAGE3_TRIGGER_R = 1.00; // R — tighter hybrid trail
//     private static final double TRAIL_STAGE3_ATR        = 1.35;
//     private static final double MIN_SL_IMPROVEMENT_ATR  = 0.10; // don't spam the API on tiny moves

//     // =========================================================================
//     // TP extension — only past stage 3, only while the trend is still valid,
//     // and only once price has covered TP_EXTENSION_TRIGGER_FRACTION of the
//     // distance to the (fixed) original TP. 1H is deliberately NOT part of
//     // this check anymore (too slow for a per-extension gate) — only 15M
//     // structure + 5M momentum are required to still be healthy.
//     // =========================================================================
//     private static final int    MAX_TP_EXTENSIONS = 2;
//     private static final double TP_EXTENSION_ATR  = 1.5;
//     private static final double TP_EXTENSION_TRIGGER_FRACTION = 0.90; // was implicit ~0.85 via stage-3 gate

//     // =========================================================================
//     // Margin-based fixed position sizing (unchanged).
//     // =========================================================================
//     private static final double MAX_MARGIN = 1200.0;

//     private static final double LIMIT_ORDER_BUFFER_PCT = 0.0005;

//     private static final long SCALP_COOLDOWN_MS            = 5 * 60 * 1000L;
//     private static final long SCALP_ENTRY_SCAN_INTERVAL_MS = 20 * 1000L;

//     private static class PendingSignal {
//         boolean isLong;
//         double confirmHigh, confirmLow;
//         double setupSwingLevel; // the pullback swing captured AT ARM TIME — never re-searched later
//         boolean strongTrend;    // 30M scored a clean 6/6 at arm time -> eligible for RR_STRONG
//         long   createdAtMs;
//     }
//     private static final Map<String, PendingSignal> pendingSignals = new ConcurrentHashMap<>();

//     private static class TrailInfo {
//         boolean isLong;
//         double entryPrice;
//         double initialSL, initialTP, initialRisk;
//         double currentSL, currentTP;
//         double peakPrice;
//         int    stage;           // 0..3
//         int    extensionsUsed;
//         long   lastTrailCandleTime; // 5M candle timestamp when the structure+ATR hybrid trail was last evaluated
//     }
//     private static final Map<String, TrailInfo> trailState = new ConcurrentHashMap<>();

//     private static final Map<String, JSONObject> instrumentCache = new ConcurrentHashMap<>();
//     private static long lastCacheUpdate = 0;
//     private static final Map<String, Long> lastTradeTime = new ConcurrentHashMap<>();

//     private static final String[] COIN_SYMBOLS = {
//        "PIEVERSE","XAU","APE","ERA","US","RAVE","EDEN","LIT","BREV","MAGMA","BLESS","ZAMA",
//         "FRAX","ACU","1000FLOKI","ELSA","LINEA","SPACE","CLO","FIGHT","UMA","MEGA","MAV","TRIA",
//         "YGG","OPN","ROBO","SUI","GLM","MANTRA","SEI","CAKE","AUCTION","SENT","BSB","BASED","IRYS",
//         "ACE","WET","CL","PRL","GENIUS","WIF","MANTA","LSK","AIGENSYN","PHAROS","JUP","AXL","BOME",
//         "SLX","ZEST","AIOT","VVV","CAP","DATAIP","GRVT","TAO","BR","TURBO","BTC","ETH","ZK","LISTA",
//         "A","LTC","XAG","COAI","HANA","ZRO","SKYAI","COPPER","RARE","ETC","M","AKE","XLM","PIXEL",
//         "XAN","ADA","CROSS","XMR","G","DASH","ZEC","ATOM","TRUTH","BCH","NEO","IOST","FLUX","ALGO",
//         "ZRX","COMP","WLFI","POL","DOGE","BAND","OPG","FIDA","PROM","SANTOS","RLC","1000000MOG",
//         "GRASS","PNUT","TRB","KAIA","ARX","XAI","S","4","COTI","CHR","SOLV","SAGA","ORCA","1000LUNC",
//         "MOVE","VIRTUAL","ME","IOTX","GIGGLE","AVA","VELODROME","AIXBT","KMNO","LA","DEXE","ZBT",
//         "GRIFFAIN","BLUAI","CTSI","ROSE","TURTLE","IMX","SUN","APR","TA","ON","BIO","COOKIE",
//         "AVAAI","DOT","TRUMP","MELANIA","GMT","FLOCK","CLANKER","CYS","SUSHI","VTHO","DIA",
//         "SLP","GOAT","BMT","KGEN","GWEI","MUBARAK","LDO","ESP","DRIFT","FORM","PLUME","NIL",
//         "UNI","ZORA","RECALL","INIT","BZ","PARTI","NATGAS","SPX","BANK","AVAX","RIVER","BILL",
//         "ATH","XRP","KERNEL","JST","PUNDIX","HAEDAL","ALPINE","SOON","SOPH","HUMA","TRX","LINK",
//         "HYPE","HIVE","TAIKO","TAG","MYX","NEWT","AIN","USUAL","PUMP","ICNT","BNB","H","BAT",
//         "QTUM","ARC","AIO","BEAT","BTR","ALCH","THETA","VELVET","ARIA","PTB","UB","LIGHT","FF",
//         "EVAAI","GMX","LYN","TAC","LAB","ENJ","AT","MMT","UAI","AAVE","JCT","KSM","HEI","JASMY",
//         "NEAR","TST","SOL","OP","PLAY","INJ","STG","HOLO","ASR","B","LUNA2","RSR","INX","KAT",
//         "ICP","QNT","MAGIC","T","MINA","STX","ACH","LQTY","ID","GRT","NEIRO","XVS","1INCH","SAND",
//         "ANKR","RVN","SFP","KAVA","MANA","HBAR","ARB","MTL","C98","TUT","SIREN","MASK","1000XEC",
//         "AR","ARPA","FIL","LPT","ENS","PEOPLE","LUMIA","DUSK","FLOW","XVG","ARKM","POPCAT","ARK",
//         "MOODENG","SAFE","AXS","BICO","BIGTIME","WAXP","GAS","POWR","TIA","CHIP","STO","ORDI",
//         "BEAMX","1000BONK","PYTH","ETHW","1000RATS","ANIME","OPEN","DYM","BERA","PORTAL","BB",
//         "BANANAS31","CFX","SSV","TNSR","EDU","JELLYJELLY","BLUR","WAL","FHE","WCT","DEEP","SXT",
//         "NAORIS","OG","CVC","AWE","O","BEL","JOE","SQD","1000PEPE","CARV","FET","SAPIEN","MEME",
//         "AVNT","XPIN","ILV","KAS","BNT","STBL","BSV","RIF","SUPER","USTC","METIS","ETHFI","ENA",
//         "1MBABYDOGE","CATI","HMSTR","GPS","SHELL","KAITO","ACT","RPL","BAN","THE","AKT","MORPHO",
//         "CHILLGUY","AERO","MOCA","PENGU","PHA","RED","EPIC","TREE","1000CAT","MAVIA","FARTCOIN",
//         "PAXG","IN","ORDER","VET","ZEN","STABLE","CHZ","NIGHT","NOM","ZKP","SKR","GRAM","BIRB",
//         "CTR","KNC","ZIL","YFI","EGLD","RUNE","ASTR","ONE","1000SHIB","API3","SPELL","WOO","APT",
//         "PENDLE","AGLD","CYBER","CKB","ONG","MOVR","POLYX","TWT","STEEM","ALT","ZETA","REZ","RENDER",
//         "RONIN","STRK","W","SCR","CETUS","IO","MEW","SWARMS","SONIC","PIPPIN","PROMPT","MERL","F",
//         "ESPORTS","PROVE","XNY","USELESS","HEMI","Q","SKY","ZKC","FLUID","MITO","CFG","EDGE","RE",
//         "YB","MET","DOS","FOGO","BTW","ALLO","BROCCOLI714","HYPER","XPL","RESOLV","ASTER","KITE",
//         "SIGN","HOME","MON","CC","SAHARA","MIRA","EUL","TOWNS","SYRUP","C","DOLO","ALICE","BABY",
//         "SOMI","NOT","BARD","SPK","POWER","2Z","BANANA","ENSO","SYN","NXPC","GUN","XTZ","ONT","SKL",
//         "HOT","JTO","DOGS","EIGEN","GTC","GALA","NMR","CGPT","ZEREBRO","VANA","OGN","CELO","USDC",
//         "COW","0G","IOTA","SNX","DYDX","WLD","1000SATS","ONDO","AEVO","BRETT","LAYER","CRV","TLM","KOMA"
//     };

//     private static final Set<String> INTEGER_QTY_PAIRS = Stream.of(COIN_SYMBOLS)
//             .flatMap(s -> Stream.of("B-" + s + "_USDT", s + "_USDT"))
//             .collect(Collectors.toCollection(HashSet::new));

//     private static final String[] COINS_TO_TRADE = Stream.of(COIN_SYMBOLS)
//             .map(s -> "B-" + s + "_USDT")
//             .toArray(String[]::new);

//     // =========================================================================
//     // Market structure (HH/HL vs LL/LH). Returns +1 bullish, -1 bearish, 0.
//     // =========================================================================
//     private static int detectSwingStructure(double[] hi, double[] lo, int lookback) {
//         int n = hi.length;
//         if (n < lookback + 3) return 0;
//         int start = Math.max(1, n - lookback);
//         List<Integer> swingHighIdx = new ArrayList<>();
//         List<Integer> swingLowIdx  = new ArrayList<>();
//         for (int i = start; i < n - 1; i++) {
//             if (hi[i] > hi[i - 1] && hi[i] > hi[i + 1]) swingHighIdx.add(i);
//             if (lo[i] < lo[i - 1] && lo[i] < lo[i + 1]) swingLowIdx.add(i);
//         }
//         boolean hh = false, hl = false, ll = false, lh = false;
//         if (swingHighIdx.size() >= 2) {
//             double h1 = hi[swingHighIdx.get(swingHighIdx.size() - 2)];
//             double h2 = hi[swingHighIdx.get(swingHighIdx.size() - 1)];
//             hh = h2 > h1; lh = h2 < h1;
//         }
//         if (swingLowIdx.size() >= 2) {
//             double l1 = lo[swingLowIdx.get(swingLowIdx.size() - 2)];
//             double l2 = lo[swingLowIdx.get(swingLowIdx.size() - 1)];
//             hl = l2 > l1; ll = l2 < l1;
//         }
//         if (hh && hl) return 1;
//         if (ll && lh) return -1;
//         return 0;
//     }

//     private static double findRecentSwingLow(double[] lo, int lookback) {
//         int n = lo.length;
//         int start = Math.max(1, n - lookback);
//         for (int i = n - 2; i >= start; i--) {
//             if (lo[i] < lo[i - 1] && lo[i] < lo[i + 1]) return lo[i];
//         }
//         int fallbackStart = Math.max(0, n - 8);
//         double min = Double.POSITIVE_INFINITY;
//         for (int i = fallbackStart; i < n; i++) min = Math.min(min, lo[i]);
//         return min;
//     }

//     private static double findRecentSwingHigh(double[] hi, int lookback) {
//         int n = hi.length;
//         int start = Math.max(1, n - lookback);
//         for (int i = n - 2; i >= start; i--) {
//             if (hi[i] > hi[i - 1] && hi[i] > hi[i + 1]) return hi[i];
//         }
//         int fallbackStart = Math.max(0, n - 8);
//         double max = Double.NEGATIVE_INFINITY;
//         for (int i = fallbackStart; i < n; i++) max = Math.max(max, hi[i]);
//         return max;
//     }

//     // Only a MEANINGFUL, CLOSE obstacle blocks the trade — a level sitting
//     // right next to TP itself is not treated as blocking.
//     private static boolean tpBlockedByLevel(boolean isLong, double entry, double tp, double[] hi, double[] lo, int lookback) {
//         double path = Math.abs(tp - entry);
//         double nearThreshold = isLong ? entry + 0.7 * path : entry - 0.7 * path;
//         int n = isLong ? hi.length : lo.length;
//         int start = Math.max(1, n - lookback);
//         if (isLong) {
//             for (int i = n - 2; i >= start; i--) {
//                 if (hi[i] > hi[i - 1] && hi[i] > hi[i + 1]) {
//                     double level = hi[i];
//                     if (level > entry && level <= nearThreshold) return true;
//                 }
//             }
//         } else {
//             for (int i = n - 2; i >= start; i--) {
//                 if (lo[i] < lo[i - 1] && lo[i] < lo[i + 1]) {
//                     double level = lo[i];
//                     if (level < entry && level >= nearThreshold) return true;
//                 }
//             }
//         }
//         return false;
//     }

//     private static class ScoredDirection {
//         boolean valid;
//         int bullScore, bearScore;
//     }

//     private static ScoredDirection analyzeScored6(JSONArray candles) {
//         ScoredDirection r = new ScoredDirection();
//         if (candles == null || candles.length() < EMA_MID + Math.max(ATR_PERIOD, ST_PERIOD) + STRUCTURE_SWING_LOOKBACK) {
//             r.valid = false;
//             return r;
//         }
//         double[] cl = extractCloses(candles);
//         double[] hi = extractHighs(candles);
//         double[] lo = extractLows(candles);

//         double ema9  = calcEMA(cl, EMA_FAST);
//         double ema21 = calcEMA(cl, EMA_MID);
//         double atr   = calcATR(hi, lo, cl, ATR_PERIOD);
//         double price = cl[cl.length - 1];

//         boolean[] stSeries = calcSupertrend(hi, lo, cl, ST_PERIOD, ST_MULTIPLIER);
//         boolean stGreen = stSeries[stSeries.length - 1];

//         double[] ema9Series = calcEMASeries(cl, EMA_FAST);
//         int n = ema9Series.length;
//         int lookback = Math.min(EMA_SLOPE_LOOKBACK_BARS, n - 1);
//         double emaSlope = ema9Series[n - 1] - ema9Series[n - 1 - lookback];
//         boolean slopeUp   = atr > 0 && emaSlope >= HTF_EMA_SLOPE_MIN_ATR * atr;
//         boolean slopeDown = atr > 0 && emaSlope <= -HTF_EMA_SLOPE_MIN_ATR * atr;

//         int structure = detectSwingStructure(hi, lo, STRUCTURE_SWING_LOOKBACK);

//         int bull = 0, bear = 0;
//         if (ema9 > ema21) bull++; else if (ema9 < ema21) bear++;
//         if (price > ema9) bull++; else bear++;
//         if (price > ema21) bull++; else bear++;
//         if (stGreen) bull++; else bear++;
//         if (slopeUp) bull++; else if (slopeDown) bear++;
//         if (structure == 1) bull++; else if (structure == -1) bear++;

//         r.valid = true;
//         r.bullScore = bull;
//         r.bearScore = bear;
//         return r;
//     }

//     private static class Setup15Result {
//         boolean valid;
//         int bullScore, bearScore;
//         double stLower, stUpper;
//     }

//     private static Setup15Result analyzeSetup15M(JSONArray candles) {
//         Setup15Result r = new Setup15Result();
//         if (candles == null || candles.length() < EMA_MID + Math.max(ATR_PERIOD, ST_PERIOD)) {
//             r.valid = false;
//             return r;
//         }
//         double[] cl = extractCloses(candles);
//         double[] hi = extractHighs(candles);
//         double[] lo = extractLows(candles);

//         double ema9  = calcEMA(cl, EMA_FAST);
//         double ema21 = calcEMA(cl, EMA_MID);
//         double atr   = calcATR(hi, lo, cl, ATR_PERIOD);
//         double price = cl[cl.length - 1];

//         boolean[] stSeries = calcSupertrend(hi, lo, cl, ST_PERIOD, ST_MULTIPLIER);
//         boolean stGreen = stSeries[stSeries.length - 1];
//         double[] bands = calcSupertrendBands(hi, lo, cl, ST_PERIOD, ST_MULTIPLIER);
//         r.stLower = bands[0];
//         r.stUpper = bands[1];

//         double[] ema9Series = calcEMASeries(cl, EMA_FAST);
//         int n = ema9Series.length;
//         int lookback = Math.min(EMA_SLOPE_LOOKBACK_BARS, n - 1);
//         double emaSlope = ema9Series[n - 1] - ema9Series[n - 1 - lookback];
//         boolean slopeUp   = atr > 0 && emaSlope >= HTF_EMA_SLOPE_MIN_ATR * atr;
//         boolean slopeDown = atr > 0 && emaSlope <= -HTF_EMA_SLOPE_MIN_ATR * atr;

//         int bull = 0, bear = 0;
//         if (ema9 > ema21) bull++; else if (ema9 < ema21) bear++;
//         if (price > ema9) bull++; else bear++;
//         if (stGreen) bull++; else bear++;
//         if (slopeUp) bull++; else if (slopeDown) bear++;
//         if (price > ema9 && price > ema21) bull++; else if (price < ema9 && price < ema21) bear++;

//         r.valid = true;
//         r.bullScore = bull;
//         r.bearScore = bear;
//         return r;
//     }

//     private static class EntryResult {
//         boolean valid;
//         boolean setupFound;
//         double confirmHigh, confirmLow;
//         double atr5m;
//         double distanceAtr;
//         String reason;
//     }

//     private static EntryResult analyzeEntry5M(JSONArray raw5m, boolean trendUp) {
//         EntryResult t = new EntryResult();
//         int minBars = EMA_MID + Math.max(ATR_PERIOD, Math.max(RSI_PERIOD, VOLUME_MA_PERIOD)) + 5;
//         if (raw5m == null || raw5m.length() < minBars) {
//             t.valid = false;
//             return t;
//         }

//         double[] cl  = extractCloses(raw5m);
//         double[] op  = extractOpens(raw5m);
//         double[] hi  = extractHighs(raw5m);
//         double[] lo  = extractLows(raw5m);
//         double[] vol = extractVolumes(raw5m);
//         int n = cl.length;

//         double ema9  = calcEMA(cl, EMA_FAST);
//         double ema21 = calcEMA(cl, EMA_MID);
//         double atr5m = calcATR(hi, lo, cl, ATR_PERIOD);
//         t.atr5m = atr5m;

//         double entryClose = cl[n - 1], entryOpen = op[n - 1];
//         double entryHigh  = hi[n - 1], entryLow  = lo[n - 1];

//         double nearestEmaDist = Math.min(Math.abs(entryClose - ema9), Math.abs(entryClose - ema21));
//         double distanceAtr = atr5m > 0 ? nearestEmaDist / atr5m : 0;
//         t.distanceAtr = distanceAtr;
//         boolean pulledBack = distanceAtr <= PULLBACK_MAX_ATR;

//         boolean directionalCandle = trendUp ? (entryClose > entryOpen) : (entryClose < entryOpen);
//         double body  = Math.abs(entryClose - entryOpen);
//         double range = entryHigh - entryLow;
//         boolean notDoji = range > 0 && (body / range) >= CANDLE_BODY_RATIO_MIN;

//         double closePositionInRange = range > 0
//                 ? (trendUp ? (entryClose - entryLow) / range : (entryHigh - entryClose) / range)
//                 : 0;
//         boolean rejectionOk = closePositionInRange >= 0.60;

//         double[] ema9Series5m = calcEMASeries(cl, EMA_FAST);
//         int lookback5m = Math.min(EMA_SLOPE_LOOKBACK_BARS, n - 1);
//         double emaSlope5m = ema9Series5m[n - 1] - ema9Series5m[n - 1 - lookback5m];
//         boolean slope5mOk = trendUp
//                 ? (atr5m > 0 && emaSlope5m >= ENTRY_EMA_SLOPE_MIN_ATR * atr5m)
//                 : (atr5m > 0 && emaSlope5m <= -ENTRY_EMA_SLOPE_MIN_ATR * atr5m);

//         boolean mandatoryOk = pulledBack && directionalCandle && notDoji && rejectionOk && slope5mOk;

//         int volStart = Math.max(0, n - 1 - VOLUME_MA_PERIOD);
//         double avgVol = 0; int cnt = 0;
//         for (int i = volStart; i < n - 1; i++) { avgVol += vol[i]; cnt++; }
//         avgVol = cnt > 0 ? avgVol / cnt : 0;
//         boolean volumeOk = avgVol > 0 && vol[n - 1] >= avgVol;

//         double rsi = calcRSI(cl, RSI_PERIOD);
//         boolean rsiOk = trendUp
//                 ? (rsi >= RSI_LONG_MIN && rsi <= RSI_LONG_MAX)
//                 : (rsi >= RSI_SHORT_MIN && rsi <= RSI_SHORT_MAX);

//         int vwapStart = Math.max(0, n - VWAP_LOOKBACK);
//         double cumPV = 0, cumV = 0;
//         for (int i = vwapStart; i < n; i++) {
//             double typical = (hi[i] + lo[i] + cl[i]) / 3.0;
//             cumPV += typical * vol[i];
//             cumV  += vol[i];
//         }
//         double vwap = cumV > 0 ? cumPV / cumV : entryClose;
//         boolean vwapOk = trendUp ? entryClose >= vwap : entryClose <= vwap;

//         boolean[] stSeries5m = calcSupertrend(hi, lo, cl, ST_PERIOD, ST_MULTIPLIER);
//         boolean st5mOk = trendUp ? stSeries5m[stSeries5m.length - 1] : !stSeries5m[stSeries5m.length - 1];

//         boolean emaAlignOk = trendUp ? (entryClose > ema9 && entryClose > ema21) : (entryClose < ema9 && entryClose < ema21);

//         int confirmationScore = (volumeOk ? 1 : 0) + (rsiOk ? 1 : 0) + (vwapOk ? 1 : 0)
//                 + (st5mOk ? 1 : 0) + (emaAlignOk ? 1 : 0);

//         t.setupFound = mandatoryOk && confirmationScore >= ENTRY_CONFIRMATION_MIN_SCORE;
//         t.confirmHigh = entryHigh;
//         t.confirmLow  = entryLow;
//         t.valid = true;
//         t.reason = String.format(
//                 "pullback=%.2fATR(ok=%s) rejection=%s(pos=%.2f) directional=%s notDoji=%s slope=%s vol=%s rsi=%.1f(ok=%s) vwap=%s st5m=%s emaAlign=%s score=%d/5",
//                 distanceAtr, pulledBack, rejectionOk, closePositionInRange, directionalCandle, notDoji, slope5mOk,
//                 volumeOk, rsi, rsiOk, vwapOk, st5mOk, emaAlignOk, confirmationScore);
//         return t;
//     }

//     private static JSONArray dropLastIfForming(JSONArray arr) {
//         if (arr == null || arr.length() < 2) return arr;
//         JSONArray out = new JSONArray();
//         for (int i = 0; i < arr.length() - 1; i++) out.put(arr.getJSONObject(i));
//         return out;
//     }

//     // Shared SL/TP construction from a given anchor level — used both by the
//     // pending-signal path (anchor captured at arm time) and by the safety
//     // sweep / reconstruction path (anchor searched fresh). The final SL
//     // distance is CLAMPED into [SL_MIN_PERCENT, SL_MAX_PERCENT] of the entry
//     // price. Callers on the live-entry path should call
//     // structuralSlTooWide() FIRST and skip the trade if it returns true —
//     // this function assumes the anchor has already been judged "reasonable".
//     private static double[] slTpFromLevel(boolean isLong, double entryPrice, double swingLevel,
//                                            double atr, double tickSize, boolean strongTrend) {
//         double sl;
//         if (atr > 0) {
//             sl = isLong ? swingLevel - SL_BUFFER_ATR * atr : swingLevel + SL_BUFFER_ATR * atr;
//         } else {
//             sl = isLong ? entryPrice * (1 - SL_MAX_PERCENT / 100.0) : entryPrice * (1 + SL_MAX_PERCENT / 100.0);
//         }

//         // If the structural side ended up wrong, fall back to a plain
//         // percentage SL on the correct side.
//         boolean slSideValid = isLong ? sl < entryPrice : sl > entryPrice;
//         if (!slSideValid) {
//             sl = isLong ? entryPrice * (1 - SL_MAX_PERCENT / 100.0) : entryPrice * (1 + SL_MAX_PERCENT / 100.0);
//         }

//         double slPercent = Math.abs(entryPrice - sl) / entryPrice * 100.0;
//         if (slPercent < SL_MIN_PERCENT) {
//             sl = isLong ? entryPrice * (1 - SL_MIN_PERCENT / 100.0) : entryPrice * (1 + SL_MIN_PERCENT / 100.0);
//         } else if (slPercent > SL_MAX_PERCENT) {
//             sl = isLong ? entryPrice * (1 - SL_MAX_PERCENT / 100.0) : entryPrice * (1 + SL_MAX_PERCENT / 100.0);
//         }

//         double rrTarget = strongTrend ? RR_STRONG : RR_DEFAULT;
//         double risk = Math.abs(entryPrice - sl);
//         double tp = isLong ? entryPrice + rrTarget * risk : entryPrice - rrTarget * risk;

//         sl = roundToTick(sl, tickSize);
//         tp = roundToTick(tp, tickSize);
//         double finalSlPercent = Math.abs(entryPrice - sl) / entryPrice * 100.0;
//         return new double[]{sl, tp, finalSlPercent, rrTarget};
//     }

//     // NEW — decides whether the entry location itself is poor, i.e. the RAW
//     // structural SL (before clamping) is farther than MAX_STRUCTURAL_SL_ATR
//     // from entry. If true, the caller should SKIP the trade instead of
//     // relying on slTpFromLevel()'s clamp to rescue it.
//     private static boolean structuralSlTooWide(boolean isLong, double entryPrice, double swingLevel, double atr) {
//         if (atr <= 0) return false; // can't judge — let the ATR-unavailable fallback path handle it
//         double rawSl = isLong ? swingLevel - SL_BUFFER_ATR * atr : swingLevel + SL_BUFFER_ATR * atr;
//         double distanceAtr = Math.abs(entryPrice - rawSl) / atr;
//         return distanceAtr > MAX_STRUCTURAL_SL_ATR;
//     }

//     // Fresh swing search — only used when there is no captured setup anchor
//     // (safety sweep on an externally/pre-existing position, or a restart).
//     private static double[] computeFreshStructuralSlTp(boolean isLong, double entryPrice,
//                                                          double[] hi5m, double[] lo5m, double atr,
//                                                          double tickSize, boolean strongTrend) {
//         double swingLevel = isLong ? findRecentSwingLow(lo5m, SL_SWING_LOOKBACK) : findRecentSwingHigh(hi5m, SL_SWING_LOOKBACK);
//         return slTpFromLevel(isLong, entryPrice, swingLevel, atr, tickSize, strongTrend);
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

//     private static double calcQuantity(double price, String pair) {
//         double usdtInrRate = 98.0;
//         double qty = MAX_MARGIN / (price * usdtInrRate);
//         double finalQty = INTEGER_QTY_PAIRS.contains(pair) ? Math.floor(qty) : Math.floor(qty * 100) / 100.0;
//         return Math.max(finalQty, 0);
//     }

//     public static void main(String[] args) {
//         System.out.println("=== Bot starting (trend-continuation cascade + hybrid SL clamp/skip + hybrid structure/ATR trailing "
//                 + "+ R-multiple staging + candle-gated trail + early-exit=" + EARLY_EXIT_ENABLED + ") ===");
//         initInstrumentCache();

//         while (true) {
//             try {
//                 runEntryScan();
//             } catch (Throwable t) {
//                 System.err.println("[MAIN-LOOP] Uncaught error, continuing: " + t.getMessage());
//                 t.printStackTrace();
//             }
//             try {
//                 TimeUnit.MILLISECONDS.sleep(SCALP_ENTRY_SCAN_INTERVAL_MS);
//             } catch (InterruptedException ignored) {
//                 Thread.currentThread().interrupt();
//                 break;
//             }
//         }
//     }

//     private static void runEntryScan() {
//         Set<String> active = getActivePositions();
//         System.out.println("Active positions: " + active);
//         pendingSignals.keySet().removeIf(active::contains);
//         trailState.keySet().removeIf(p -> !active.contains(p));

//         if (active.size() >= MAX_OPEN_POSITIONS) {
//             System.out.println("MAX_OPEN_POSITIONS reached — skipping scan.");
//             updateTrailing();
//             ensureTpSlForOpenPositions();
//             return;
//         }

//         for (String pair : COINS_TO_TRADE) {
//             try {
//                 if (active.size() >= MAX_OPEN_POSITIONS) break;
//                 if (active.contains(pair)) continue;

//                 long lastTrade = lastTradeTime.getOrDefault(pair, 0L);
//                 if (System.currentTimeMillis() - lastTrade < SCALP_COOLDOWN_MS) continue;

//                 JSONArray raw5m = dropLastIfForming(getCandlestickData(pair, RES_5M, BASE_5M_FETCH_COUNT));
//                 if (raw5m == null || raw5m.length() < EMA_MID + ST_PERIOD + STRUCTURE_SWING_LOOKBACK) continue;

//                 PendingSignal pending = pendingSignals.get(pair);

//                 if (pending != null) {
//                     boolean cancelled = false;
//                     if (System.currentTimeMillis() - pending.createdAtMs > SIGNAL_MAX_VALID_MS) {
//                         System.out.println("  Signal expired (stale setup): " + pair);
//                         cancelled = true;
//                     } else {
//                         EntryResult quickCheck = analyzeEntry5M(raw5m, pending.isLong);
//                         if (quickCheck.valid && quickCheck.distanceAtr > OVEREXTENSION_SKIP_ATR) {
//                             System.out.println("  Signal cancelled (price overextended "
//                                     + String.format("%.2f", quickCheck.distanceAtr) + " ATR): " + pair);
//                             cancelled = true;
//                         }
//                     }
//                     if (!cancelled) {
//                         JSONArray raw1h = dropLastIfForming(getCandlestickData(pair, RES_1H, BASE_1H_FETCH_COUNT));
//                         ScoredDirection dir1h = analyzeScored6(raw1h);
//                         if (dir1h.valid) {
//                             boolean stillAgrees = pending.isLong ? dir1h.bullScore >= MACRO_1H_MIN_SCORE : dir1h.bearScore >= MACRO_1H_MIN_SCORE;
//                             if (!stillAgrees) {
//                                 System.out.println("  Signal cancelled (1H direction changed): " + pair);
//                                 cancelled = true;
//                             }
//                         }
//                     }

//                     if (cancelled) {
//                         pendingSignals.remove(pair);
//                     } else {
//                         double currentPrice = getLastPrice(pair);
//                         if (currentPrice > 0) {
//                             boolean breakoutHit = pending.isLong
//                                     ? currentPrice > pending.confirmHigh
//                                     : currentPrice < pending.confirmLow;
//                             if (breakoutHit) {
//                                 tryEnterOnBreakout(pair, pending, raw5m, currentPrice, active);
//                             }
//                         }
//                         continue;
//                     }
//                 }

//                 // ---- No pending signal — look for a fresh one ----

//                 JSONArray raw1h = dropLastIfForming(getCandlestickData(pair, RES_1H, BASE_1H_FETCH_COUNT));
//                 ScoredDirection dir1h = analyzeScored6(raw1h);
//                 if (!dir1h.valid) continue;
//                 boolean trendUp;
//                 if (dir1h.bullScore >= MACRO_1H_MIN_SCORE) trendUp = true;
//                 else if (dir1h.bearScore >= MACRO_1H_MIN_SCORE) trendUp = false;
//                 else continue;

//                 JSONArray raw30m = aggregateCandles(raw5m, GROUP_30M_FROM_5M);
//                 ScoredDirection dir30m = analyzeScored6(raw30m);
//                 if (!dir30m.valid) continue;
//                 int score30 = trendUp ? dir30m.bullScore : dir30m.bearScore;
//                 int opposite30 = trendUp ? dir30m.bearScore : dir30m.bullScore;
//                 if (opposite30 > score30) continue;

//                 boolean strong30 = score30 >= CONFIRM_30M_STRONG_MIN;
//                 boolean acceptable30 = score30 == CONFIRM_30M_ACCEPTABLE;
//                 if (!strong30 && !acceptable30) continue;

//                 JSONArray raw15m = aggregateCandles(raw5m, GROUP_15M_FROM_5M);
//                 Setup15Result setup15 = analyzeSetup15M(raw15m);
//                 if (!setup15.valid) continue;
//                 int score15 = trendUp ? setup15.bullScore : setup15.bearScore;
//                 if (score15 < SETUP_15M_MIN_SCORE) continue;

//                 EntryResult entry5m = analyzeEntry5M(raw5m, trendUp);
//                 if (!entry5m.valid) continue;

//                 if (acceptable30) {
//                     boolean clean15 = score15 >= CLEAN_15M_MIN_FOR_ACCEPTABLE;
//                     boolean clean5 = entry5m.setupFound;
//                     if (!clean15 || !clean5) continue;
//                 }

//                 if (!entry5m.setupFound) continue;

//                 // Capture the pullback swing NOW — this is the anchor the SL
//                 // will use at breakout, so it never drifts to a newer/farther
//                 // swing while the signal is pending.
//                 double[] hi5mArm = extractHighs(raw5m);
//                 double[] lo5mArm = extractLows(raw5m);
//                 double swingLevel = trendUp
//                         ? findRecentSwingLow(lo5mArm, SL_SWING_LOOKBACK)
//                         : findRecentSwingHigh(hi5mArm, SL_SWING_LOOKBACK);

//                 PendingSignal newSignal = new PendingSignal();
//                 newSignal.isLong = trendUp;
//                 newSignal.confirmHigh = entry5m.confirmHigh;
//                 newSignal.confirmLow  = entry5m.confirmLow;
//                 newSignal.setupSwingLevel = swingLevel;
//                 newSignal.strongTrend = (score30 == 6);
//                 newSignal.createdAtMs = System.currentTimeMillis();
//                 pendingSignals.put(pair, newSignal);
//                 System.out.println("  Pending " + (trendUp ? "LONG" : "SHORT") + " signal armed: " + pair
//                         + " | 1H=" + (trendUp ? dir1h.bullScore : dir1h.bearScore) + "/6 30M=" + score30 + "/6 15M=" + score15 + "/5"
//                         + " | trigger=" + (trendUp ? ("break " + newSignal.confirmHigh) : ("break " + newSignal.confirmLow))
//                         + " | " + entry5m.reason);

//             } catch (Exception e) {
//                 System.err.println("Error on " + pair + ": " + e.getMessage());
//             }
//         }

//         System.out.println("\n=== Scan complete ===");
//         updateTrailing();
//         ensureTpSlForOpenPositions();
//     }

//     // Handles an armed pending signal's breakout: uses the ANCHOR captured at
//     // arm time (never a newer swing), checks whether the structural SL is
//     // too wide to trade (skip rather than clamp), applies the TP-location
//     // check, sizes the position, places the order, confirms the fill, and
//     // re-derives final SL/TP off the actual fill price (same anchor).
//     private static void tryEnterOnBreakout(String pair, PendingSignal pending, JSONArray raw5m,
//                                             double currentPrice, Set<String> active) {
//         try {
//             double tickSize = getTickSize(pair);
//             double[] hi5m = extractHighs(raw5m);
//             double[] lo5m = extractLows(raw5m);
//             double atr = calcATR(hi5m, lo5m, extractCloses(raw5m), ATR_PERIOD);

//             if (structuralSlTooWide(pending.isLong, currentPrice, pending.setupSwingLevel, atr)) {
//                 System.out.println("  NO TRADE: " + pair + " — structural SL too wide (>" + MAX_STRUCTURAL_SL_ATR
//                         + " ATR from entry) — skipping rather than force-clamping a poor entry location");
//                 pendingSignals.remove(pair);
//                 return;
//             }

//             double[] preSlTp = slTpFromLevel(pending.isLong, currentPrice, pending.setupSwingLevel, atr, tickSize, pending.strongTrend);

//             JSONArray raw15m = aggregateCandles(raw5m, GROUP_15M_FROM_5M);
//             if (raw15m != null) {
//                 double[] hi15m = extractHighs(raw15m);
//                 double[] lo15m = extractLows(raw15m);
//                 if (tpBlockedByLevel(pending.isLong, currentPrice, preSlTp[1], hi15m, lo15m, TP_LEVEL_LOOKBACK)) {
//                     System.out.println("  NO TRADE: " + pair + " — meaningful nearby " + (pending.isLong ? "resistance" : "support") + " blocks TP");
//                     pendingSignals.remove(pair);
//                     return;
//                 }
//             }

//             double qty = calcQuantity(currentPrice, pair);
//             if (qty <= 0) { pendingSignals.remove(pair); return; }

//             System.out.println("\n==== " + pair + " — BREAKOUT ENTRY ====");
//             String side = pending.isLong ? "buy" : "sell";
//             JSONObject orderResp = placeFuturesOrder(side, pair, qty, LEVERAGE,
//                     "email_notification", "isolated", "INR", currentPrice);
//             if (orderResp == null || !orderResp.has("id")) {
//                 System.out.println("  Order failed: " + orderResp);
//                 pendingSignals.remove(pair);
//                 return;
//             }

//             System.out.println("  Order placed! id=" + orderResp.getString("id"));
//             lastTradeTime.put(pair, System.currentTimeMillis());
//             double swingAnchor = pending.setupSwingLevel;
//             boolean strongTrend = pending.strongTrend;
//             boolean isLong = pending.isLong;
//             pendingSignals.remove(pair);

//             double entry = getEntryPrice(pair, orderResp.getString("id"));
//             if (entry <= 0) {
//                 System.out.println("  Could not confirm entry within window — TP/SL handled by safety sweep");
//                 active.add(pair);
//                 return;
//             }
//             System.out.printf("  Entry confirmed: %.6f%n", entry);

//             double[] slTp = slTpFromLevel(isLong, entry, swingAnchor, atr, tickSize, strongTrend);
//             double slPrice = slTp[0], tpPrice = slTp[1], rrUsed = slTp[3];
//             double[] clamped = sanityClampSlTp(isLong, entry, slPrice, tpPrice, tickSize);
//             slPrice = clamped[0]; tpPrice = clamped[1];

//             double risk = Math.abs(entry - slPrice);
//             System.out.println("[ENTRY] " + pair + " " + (isLong ? "LONG" : "SHORT")
//                     + " Entry=" + entry + " SL=" + slPrice + " TP=" + tpPrice
//                     + " Risk=" + risk + " RR=" + String.format("%.2f", rrUsed)
//                     + " SL%=" + String.format("%.2f", slTp[2]) + " (clamped to [" + SL_MIN_PERCENT + "%, " + SL_MAX_PERCENT + "%])");

//             String posId = getPositionId(pair);
//             if (posId != null) {
//                 setTpSlWithRetry(posId, tpPrice, slPrice, pair);
//             } else {
//                 System.out.println("  Position ID not found after retries — safety sweep will handle it");
//             }

//             TrailInfo ti = new TrailInfo();
//             ti.isLong = isLong;
//             ti.entryPrice = entry;
//             ti.initialSL = slPrice;
//             ti.initialTP = tpPrice;
//             ti.initialRisk = risk;
//             ti.currentSL = slPrice;
//             ti.currentTP = tpPrice;
//             ti.peakPrice = entry;
//             ti.stage = 0;
//             ti.extensionsUsed = 0;
//             ti.lastTrailCandleTime = 0L;
//             trailState.put(pair, ti);

//             active.add(pair);
//         } catch (Exception e) {
//             System.err.println("tryEnterOnBreakout(" + pair + "): " + e.getMessage());
//         }
//     }

//     // =========================================================================
//     // Trailing monitor — runs every cycle for every open position.
//     //   Stage 0->1 (0.5R):  breakeven-style profit lock — applied IMMEDIATELY,
//     //                       every cycle (this is emergency protection, not
//     //                       subject to candle-close gating).
//     //   Stage 2 (0.75R+):   hybrid structure+ATR trail — MAX(recent 5M swing,
//     //                       ATR-trail, old SL) for long / MIN for short.
//     //   Stage 3 (1.0R+):    same hybrid trail, tighter ATR multiple.
//     //   Both stage-2/3 hybrid trail decisions are CANDLE-CLOSE GATED: they
//     //   only re-evaluate once a new 5M candle has closed, so a single wick
//     //   can't yank the SL in mid-candle.
//     //   TP extension only past stage 3, only once trend still valid.
//     //   SL only ever moves in the profitable direction (ratchet-only).
//     // =========================================================================
//     private static void updateTrailing() {
//         Set<String> stillOpen = getActivePositions();
//         for (String pair : stillOpen) {
//             try {
//                 JSONObject pos = findPosition(pair);
//                 if (pos == null) continue;

//                 TrailInfo ti = trailState.get(pair);
//                 if (ti == null) {
//                     // Reconstruct from the exchange position — never reset a
//                     // profitable trade's SL/TP back to some fresh initial guess.
//                     double avgPrice = pos.optDouble("avg_price", 0);
//                     double tpTrig = pos.optDouble("take_profit_trigger", 0);
//                     double slTrig = pos.optDouble("stop_loss_trigger", 0);
//                     if (avgPrice <= 0) continue;
//                     boolean isLong = pos.optDouble("active_pos", 0) >= 0;
//                     ti = new TrailInfo();
//                     ti.isLong = isLong;
//                     ti.entryPrice = avgPrice;
//                     ti.currentSL = slTrig;
//                     ti.currentTP = tpTrig;
//                     ti.initialSL = slTrig;
//                     ti.initialTP = tpTrig;
//                     ti.initialRisk = slTrig > 0 ? Math.abs(avgPrice - slTrig) : 0;
//                     ti.peakPrice = avgPrice;
//                     ti.stage = 0;
//                     ti.extensionsUsed = 0;
//                     ti.lastTrailCandleTime = 0L;
//                     trailState.put(pair, ti);
//                     if (ti.currentSL <= 0 || ti.currentTP <= 0) continue; // let the safety sweep set initial protection first
//                 }

//                 JSONArray raw5m = dropLastIfForming(getCandlestickData(pair, RES_5M, BASE_5M_FETCH_COUNT));
//                 if (raw5m == null || raw5m.length() < EMA_MID + ST_PERIOD + 5) continue;
//                 double[] hi5m = extractHighs(raw5m), lo5m = extractLows(raw5m), cl5m = extractCloses(raw5m);
//                 double atr5m = calcATR(hi5m, lo5m, cl5m, ATR_PERIOD);
//                 if (atr5m <= 0) continue;

//                 // ---- Early exit (toggleable, independent of trailing below) ----
//                 checkEarlyExitForPosition(pair, ti, raw5m);

//                 // ---- Trailing (only if we have a valid initial baseline) ----
//                 if (ti.initialTP <= 0 || ti.initialRisk <= 0) continue;

//                 double currentPrice = getLastPrice(pair);
//                 if (currentPrice <= 0) continue;
//                 ti.peakPrice = ti.isLong ? Math.max(ti.peakPrice, currentPrice) : Math.min(ti.peakPrice, currentPrice);

//                 double moveInFavor = ti.isLong ? (currentPrice - ti.entryPrice) : (ti.entryPrice - currentPrice);
//                 double rMultiple = moveInFavor / ti.initialRisk;

//                 double tpDistance = ti.isLong ? ti.initialTP - ti.entryPrice : ti.entryPrice - ti.initialTP;
//                 double tpProgress = tpDistance > 0 ? (moveInFavor / tpDistance) : 0;

//                 int newStage = rMultiple >= TRAIL_STAGE3_TRIGGER_R ? 3
//                         : rMultiple >= TRAIL_STAGE2_TRIGGER_R ? 2
//                         : rMultiple >= BREAKEVEN_TRIGGER_R ? 1 : 0;
//                 if (newStage > ti.stage) ti.stage = newStage;

//                 double tick = getTickSize(pair);
//                 double candidateSL = ti.currentSL;

//                 // Stage 1 — breakeven-style profit lock. Immediate, every cycle.
//                 if (ti.stage >= 1) {
//                     double lockSL = ti.isLong
//                             ? ti.entryPrice * (1 + BREAKEVEN_LOCK_PROFIT_PERCENT / 100.0)
//                             : ti.entryPrice * (1 - BREAKEVEN_LOCK_PROFIT_PERCENT / 100.0);
//                     if (ti.isLong ? lockSL > candidateSL : lockSL < candidateSL) candidateSL = lockSL;
//                 }

//                 // Stage 2/3 — hybrid structure+ATR trail. Candle-close gated.
//                 long lastCandleTime = raw5m.length() > 0
//                         ? raw5m.getJSONObject(raw5m.length() - 1).optLong("time", 0)
//                         : 0;
//                 boolean newCandleClosed = lastCandleTime > 0 && lastCandleTime != ti.lastTrailCandleTime;

//                 if (ti.stage >= 2 && (newCandleClosed || ti.lastTrailCandleTime == 0)) {
//                     double trailMult = ti.stage >= 3 ? TRAIL_STAGE3_ATR : TRAIL_STAGE2_ATR;
//                     double atrSL = ti.isLong ? currentPrice - trailMult * atr5m : currentPrice + trailMult * atr5m;
//                     double structureSL = ti.isLong
//                             ? findRecentSwingLow(lo5m, SL_SWING_LOOKBACK)
//                             : findRecentSwingHigh(hi5m, SL_SWING_LOOKBACK);
//                     // MAX(structure, atr-trail) for long / MIN for short — the
//                     // more conservative-but-still-valid candidate wins.
//                     double hybridCandidate = ti.isLong ? Math.max(atrSL, structureSL) : Math.min(atrSL, structureSL);
//                     if (ti.isLong ? hybridCandidate > candidateSL : hybridCandidate < candidateSL) candidateSL = hybridCandidate;
//                     ti.lastTrailCandleTime = lastCandleTime;
//                 }

//                 boolean changed = false;
//                 double minImprovement = Math.max(MIN_SL_IMPROVEMENT_ATR * atr5m, tick);
//                 boolean meaningfulImprovement = ti.isLong
//                         ? (candidateSL - ti.currentSL) >= minImprovement
//                         : (ti.currentSL - candidateSL) >= minImprovement;

//                 if (meaningfulImprovement) {
//                     candidateSL = roundToTick(candidateSL, tick);
//                     // Never move SL backward — ratchet-only.
//                     if (ti.isLong ? candidateSL > ti.currentSL : candidateSL < ti.currentSL) {
//                         System.out.printf("  [TRAIL] %s %s R=%.2f stage=%d SL moved %.6f -> %.6f%n",
//                                 pair, ti.isLong ? "LONG" : "SHORT", rMultiple, ti.stage, ti.currentSL, candidateSL);
//                         ti.currentSL = candidateSL;
//                         changed = true;
//                     } else {
//                         System.out.println("  [TRAIL] " + pair + " candidate SL would worsen existing SL — ignoring update");
//                     }
//                 }

//                 // ---- TP extension (only past stage 3, close to original TP, trend still valid) ----
//                 if (ti.stage >= 3 && ti.extensionsUsed < MAX_TP_EXTENSIONS && tpProgress >= TP_EXTENSION_TRIGGER_FRACTION) {
//                     JSONArray raw15m = aggregateCandles(raw5m, GROUP_15M_FROM_5M);
//                     boolean trendValid = checkTrendValidForExtension(ti.isLong, raw15m, raw5m);
//                     if (trendValid) {
//                         double newTP = ti.isLong ? currentPrice + atr5m * TP_EXTENSION_ATR : currentPrice - atr5m * TP_EXTENSION_ATR;
//                         newTP = roundToTick(newTP, tick);
//                         if (ti.isLong ? newTP > ti.currentTP : newTP < ti.currentTP) {
//                             System.out.println("  [TP EXTENSION] " + pair + " " + (ti.isLong ? "LONG" : "SHORT")
//                                     + " TP " + ti.currentTP + " -> " + newTP
//                                     + " Extension " + (ti.extensionsUsed + 1) + "/" + MAX_TP_EXTENSIONS);
//                             ti.currentTP = newTP;
//                             ti.extensionsUsed++;
//                             changed = true;
//                         }
//                     }
//                 }

//                 if (changed) {
//                     String posId = pos.optString("id", null);
//                     if (posId != null) setTpSlWithRetry(posId, ti.currentTP, ti.currentSL, pair);
//                 }
//             } catch (Exception e) {
//                 System.err.println("updateTrailing(" + pair + "): " + e.getMessage());
//             }
//         }
//     }

//     // TP-extension trend check — 1H is deliberately NOT part of this (too
//     // slow for a per-extension gate). Only 15M structure and 5M momentum
//     // (EMA9 slope) need to still be healthy.
//     private static boolean checkTrendValidForExtension(boolean isLong, JSONArray raw15m, JSONArray raw5m) {
//         Setup15Result s15 = analyzeSetup15M(raw15m);
//         if (s15.valid) {
//             int score15 = isLong ? s15.bullScore : s15.bearScore;
//             if (score15 < SETUP_15M_MIN_SCORE) return false;
//         }
//         if (raw5m != null && raw5m.length() >= EMA_FAST + EMA_SLOPE_LOOKBACK_BARS + 1) {
//             double[] cl5 = extractCloses(raw5m);
//             double[] hi5 = extractHighs(raw5m);
//             double[] lo5 = extractLows(raw5m);
//             double atr5 = calcATR(hi5, lo5, cl5, ATR_PERIOD);
//             double[] ema9Series = calcEMASeries(cl5, EMA_FAST);
//             int n = ema9Series.length;
//             int lb = Math.min(EMA_SLOPE_LOOKBACK_BARS, n - 1);
//             double slope = ema9Series[n - 1] - ema9Series[n - 1 - lb];
//             boolean momentumOk = isLong
//                     ? (atr5 <= 0 || slope >= -ENTRY_EMA_SLOPE_MIN_ATR * atr5)
//                     : (atr5 <= 0 || slope <= ENTRY_EMA_SLOPE_MIN_ATR * atr5);
//             if (!momentumOk) return false;
//         }
//         return true;
//     }

//     // =========================================================================
//     // Early-exit system (toggleable via EARLY_EXIT_ENABLED). Independent of
//     // TP/SL/trailing — runs alongside it every cycle when enabled.
//     //   5M  -> warning only, NEVER exits (5M is too noisy to act on alone)
//     //   15M -> confirmed reversal -> exit (Supertrend flip + close breaks
//     //          EMA21, OR structure breaks + EMA9 slope is lost)
//     //   30M -> stronger/emergency reversal -> exit immediately (Supertrend
//     //          flip + EMA9/EMA21 cross against the position)
//     // =========================================================================
//     private static void checkEarlyExitForPosition(String pair, TrailInfo ti, JSONArray raw5m) {
//         if (!EARLY_EXIT_ENABLED || ti == null) return;
//         try {
//             double[] hi5 = extractHighs(raw5m), lo5 = extractLows(raw5m), cl5 = extractCloses(raw5m);
//             if (hi5.length < EMA_MID + ST_PERIOD + 5) return;

//             // ---- 5M — warning only ----
//             boolean[] st5 = calcSupertrend(hi5, lo5, cl5, ST_PERIOD, ST_MULTIPLIER);
//             boolean st5Green = st5[st5.length - 1];
//             boolean warn5m = ti.isLong ? !st5Green : st5Green;
//             if (warn5m) {
//                 System.out.println("  [EARLY-EXIT][5M-WARN] " + pair + " Supertrend against position — watching only, no action");
//             }

//             // ---- 15M — confirmed exit ----
//             JSONArray raw15m = aggregateCandles(raw5m, GROUP_15M_FROM_5M);
//             if (raw15m != null && raw15m.length() >= EMA_MID + ST_PERIOD + 3) {
//                 double[] hi15 = extractHighs(raw15m), lo15 = extractLows(raw15m), cl15 = extractCloses(raw15m);
//                 boolean[] st15 = calcSupertrend(hi15, lo15, cl15, ST_PERIOD, ST_MULTIPLIER);
//                 boolean st15Green = st15[st15.length - 1];
//                 double ema21_15 = calcEMA(cl15, EMA_MID);
//                 double lastClose15 = cl15[cl15.length - 1];
//                 int structure15 = detectSwingStructure(hi15, lo15, STRUCTURE_SWING_LOOKBACK);

//                 double[] ema9Series15 = calcEMASeries(cl15, EMA_FAST);
//                 int n15 = ema9Series15.length;
//                 int lb15 = Math.min(EMA_SLOPE_LOOKBACK_BARS, n15 - 1);
//                 double slope15 = ema9Series15[n15 - 1] - ema9Series15[n15 - 1 - lb15];
//                 double atr15 = calcATR(hi15, lo15, cl15, ATR_PERIOD);
//                 boolean slopeLost15 = ti.isLong
//                         ? (atr15 > 0 && slope15 <= -HTF_EMA_SLOPE_MIN_ATR * atr15)
//                         : (atr15 > 0 && slope15 >= HTF_EMA_SLOPE_MIN_ATR * atr15);

//                 boolean stFlip15 = ti.isLong ? !st15Green : st15Green;
//                 boolean closeBreak15 = ti.isLong ? lastClose15 < ema21_15 : lastClose15 > ema21_15;
//                 boolean structureBreak15 = ti.isLong ? structure15 == -1 : structure15 == 1;

//                 boolean exit15 = (stFlip15 && closeBreak15) || (structureBreak15 && slopeLost15);
//                 if (exit15) {
//                     System.out.println("  [EARLY-EXIT][15M] " + pair + " " + (ti.isLong ? "LONG" : "SHORT")
//                             + " reversal confirmed — exiting position");
//                     exitTrackedPosition(pair);
//                     return;
//                 }
//             }

//             // ---- 30M — emergency exit ----
//             JSONArray raw30m = aggregateCandles(raw5m, GROUP_30M_FROM_5M);
//             if (raw30m != null && raw30m.length() >= EMA_MID + ST_PERIOD + 3) {
//                 double[] cl30 = extractCloses(raw30m), hi30 = extractHighs(raw30m), lo30 = extractLows(raw30m);
//                 boolean[] st30 = calcSupertrend(hi30, lo30, cl30, ST_PERIOD, ST_MULTIPLIER);
//                 boolean st30Green = st30[st30.length - 1];
//                 double ema9_30 = calcEMA(cl30, EMA_FAST);
//                 double ema21_30 = calcEMA(cl30, EMA_MID);
//                 boolean stFlip30 = ti.isLong ? !st30Green : st30Green;
//                 boolean emaCrossAgainst30 = ti.isLong ? ema9_30 < ema21_30 : ema9_30 > ema21_30;
//                 if (stFlip30 && emaCrossAgainst30) {
//                     System.out.println("  [EARLY-EXIT][30M-EMERGENCY] " + pair + " " + (ti.isLong ? "LONG" : "SHORT")
//                             + " major reversal — exiting position immediately");
//                     exitTrackedPosition(pair);
//                 }
//             }
//         } catch (Exception e) {
//             System.err.println("checkEarlyExitForPosition(" + pair + "): " + e.getMessage());
//         }
//     }

//     private static void exitTrackedPosition(String pair) {
//         try {
//             JSONObject pos = findPosition(pair);
//             if (pos == null) return;
//             String posId = pos.optString("id", null);
//             if (posId == null) {
//                 System.out.println("  [EARLY-EXIT] " + pair + " — position ID missing, cannot exit");
//                 return;
//             }
//             exitPositionNow(posId, pair);
//         } catch (Exception e) {
//             System.err.println("exitTrackedPosition(" + pair + "): " + e.getMessage());
//         }
//     }

//     // NOTE: verify this exact endpoint/payload shape against CoinDCX's current
//     // Futures "Exit Position" API docs before relying on it live — it is
//     // reconstructed here to match the pattern of the other authenticated
//     // endpoints already used in this file (create_tpsl, positions, orders).
//     private static boolean exitPositionNow(String posId, String pair) {
//         try {
//             JSONObject body = new JSONObject();
//             body.put("timestamp", Instant.now().toEpochMilli());
//             body.put("id", posId);
//             String resp = authPost(BASE_URL + "/exchange/v1/derivatives/futures/positions/exit", body.toString());
//             JSONObject r = new JSONObject(resp);
//             boolean ok = !r.has("err_code_dcx");
//             System.out.println("  [EARLY-EXIT] " + pair + " exit request " + (ok ? "sent successfully" : "FAILED: " + r));
//             return ok;
//         } catch (Exception e) {
//             System.err.println("exitPositionNow(" + pair + "): " + e.getMessage());
//             return false;
//         }
//     }

//     // Never overwrite an existing better (possibly trailed) SL/TP. Only fills
//     // in whichever side is genuinely missing.
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
//                 if (tpTrig > 0 && slTrig > 0) continue; // both present — trailing owns updates from here, never touch

//                 System.out.println("  [SWEEP] " + pair + " missing TP and/or SL — computing fallback protection...");
//                 boolean isLong = pos.optDouble("active_pos", 0) >= 0;
//                 double tick = getTickSize(pair);
//                 TrailInfo ti = trailState.get(pair);

//                 double sl, tp;
//                 if (ti != null && ti.currentSL > 0 && ti.currentTP > 0) {
//                     sl = ti.currentSL; tp = ti.currentTP; // trust our own tracked (possibly trailed) values
//                 } else {
//                     JSONArray raw5m = dropLastIfForming(getCandlestickData(pair, RES_5M, BASE_5M_FETCH_COUNT));
//                     double[] hi5m = null, lo5m = null; double atr5m = 0;
//                     if (raw5m != null && raw5m.length() >= ATR_PERIOD + SL_SWING_LOOKBACK) {
//                         hi5m = extractHighs(raw5m); lo5m = extractLows(raw5m);
//                         atr5m = calcATR(hi5m, lo5m, extractCloses(raw5m), ATR_PERIOD);
//                     }
//                     double[] slTp = (hi5m != null && atr5m > 0)
//                             ? computeFreshStructuralSlTp(isLong, avgPrice, hi5m, lo5m, atr5m, tick, false)
//                             : null;
//                     if (slTp == null) {
//                         sl = isLong ? avgPrice * (1 - SL_HARD_PERCENT_CAP / 100.0) : avgPrice * (1 + SL_HARD_PERCENT_CAP / 100.0);
//                         tp = isLong ? avgPrice + RR_DEFAULT * (avgPrice - sl) : avgPrice - RR_DEFAULT * (sl - avgPrice);
//                         System.out.println("  [SWEEP] structural SL unavailable for " + pair + " — using hard % fallback cap");
//                     } else {
//                         sl = slTp[0]; tp = slTp[1];
//                     }
//                 }

//                 // Preserve whichever side the exchange already has set — never overwrite it.
//                 if (slTrig > 0) sl = slTrig;
//                 if (tpTrig > 0) tp = tpTrig;

//                 double[] clamped = sanityClampSlTp(isLong, avgPrice, sl, tp, tick);
//                 sl = clamped[0]; tp = clamped[1];

//                 String posId = pos.optString("id", null);
//                 if (posId != null) {
//                     System.out.printf("  [SWEEP] %s fallback SL=%.6f TP=%.6f%n", pair, sl, tp);
//                     setTpSlWithRetry(posId, tp, sl, pair);
//                     if (ti == null) {
//                         TrailInfo nt = new TrailInfo();
//                         nt.isLong = isLong; nt.entryPrice = avgPrice;
//                         nt.currentSL = sl; nt.currentTP = tp;
//                         nt.initialSL = sl; nt.initialTP = tp;
//                         nt.initialRisk = Math.abs(avgPrice - sl);
//                         nt.peakPrice = avgPrice; nt.stage = 0; nt.extensionsUsed = 0;
//                         nt.lastTrailCandleTime = 0L;
//                         trailState.put(pair, nt);
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

//     private static double[] calcEMASeries(double[] d, int period) {
//         double[] out = new double[d.length];
//         if (d.length < period) {
//             double last = d.length > 0 ? d[d.length - 1] : 0;
//             Arrays.fill(out, last);
//             return out;
//         }
//         double k = 2.0 / (period + 1);
//         double ema = 0;
//         for (int i = 0; i < period; i++) ema += d[i];
//         ema /= period;
//         for (int i = 0; i < period; i++) out[i] = ema;
//         out[period - 1] = ema;
//         for (int i = period; i < d.length; i++) {
//             ema = d[i] * k + ema * (1 - k);
//             out[i] = ema;
//         }
//         return out;
//     }

//     private static double calcEMA(double[] d, int period) {
//         if (d.length < period) return 0;
//         double k = 2.0 / (period + 1), ema = 0;
//         for (int i = 0; i < period; i++) ema += d[i];
//         ema /= period;
//         for (int i = period; i < d.length; i++) ema = d[i] * k + ema * (1 - k);
//         return ema;
//     }

//     private static double calcRSI(double[] closes, int period) {
//         if (closes.length < period + 1) return 50.0;

//         double avgGain = 0, avgLoss = 0;
//         for (int i = 1; i <= period; i++) {
//             double change = closes[i] - closes[i - 1];
//             if (change > 0) avgGain += change;
//             else avgLoss += -change;
//         }
//         avgGain /= period;
//         avgLoss /= period;

//         for (int i = period + 1; i < closes.length; i++) {
//             double change = closes[i] - closes[i - 1];
//             double gain = Math.max(change, 0);
//             double loss = Math.max(-change, 0);
//             avgGain = (avgGain * (period - 1) + gain) / period;
//             avgLoss = (avgLoss * (period - 1) + loss) / period;
//         }

//         if (avgLoss == 0) return 100.0;
//         double rs = avgGain / avgLoss;
//         return 100.0 - (100.0 / (1.0 + rs));
//     }

//     private static BigDecimal roundToTickBD(double price, double tick) {
//         if (tick <= 0) return BigDecimal.valueOf(price);
//         BigDecimal bdPrice = BigDecimal.valueOf(price);
//         BigDecimal bdTick  = BigDecimal.valueOf(tick);
//         BigDecimal multiples = bdPrice.divide(bdTick, 0, RoundingMode.HALF_UP);
//         BigDecimal result = multiples.multiply(bdTick);
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
//     private static double[] extractVolumes(JSONArray a) {
//         double[] o = new double[a.length()];
//         for (int i = 0; i < a.length(); i++) o[i] = a.getJSONObject(i).optDouble("volume", 0);
//         return o;
//     }

//     private static JSONArray getCandlestickData(String pair, String resolution, int count) {
//         try {
//             long minsPerBar;
//             switch (resolution) {
//                 case "1":   minsPerBar = 1;   break;
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
//             double volSum = 0;
//             for (int j = i; j < i + groupSize; j++) {
//                 JSONObject c = source.getJSONObject(j);
//                 high = Math.max(high, c.getDouble("high"));
//                 low  = Math.min(low,  c.getDouble("low"));
//                 volSum += c.optDouble("volume", 0);
//             }
//             JSONObject merged = new JSONObject();
//             merged.put("open", open);
//             merged.put("close", close);
//             merged.put("high", high);
//             merged.put("low", low);
//             merged.put("volume", volSum);
//             result.put(merged);
//         }
//         return result;
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
// import java.time.Instant;
// import java.util.*;
// import java.util.concurrent.ConcurrentHashMap;
// import java.util.concurrent.TimeUnit;
// import java.util.stream.Collectors;
// import java.util.stream.Stream;

// public class CoinDCXFuturesTrader8C_BUY_SELL_NEW_LOGIC_THREE {

//     // =========================================================================
//     // API Configuration (unchanged)
//     // =========================================================================
//     private static final String API_KEY    = System.getenv("DELTA_API_KEY");
//     private static final String API_SECRET = System.getenv("DELTA_API_SECRET");
//     private static final String BASE_URL       = "https://api.coindcx.com";
//     private static final String PUBLIC_API_URL = "https://public.coindcx.com";

//     private static final int LEVERAGE = 16;

//     private static final int MAX_ENTRY_PRICE_CHECKS = 20;
//     private static final int ENTRY_CHECK_DELAY_MS    = 1000;

//     private static final int  TPSL_MAX_RETRIES    = 3;
//     private static final long TPSL_RETRY_DELAY_MS = 2000L;

//     private static final long TICK_CACHE_TTL_MS = 3_600_000L;

//     private static final int MAX_OPEN_POSITIONS = 120;

//     private static final int  POSITION_ID_MAX_RETRIES = 5;
//     private static final long POSITION_ID_RETRY_DELAY_MS = 1500L;

//     // =========================================================================
//     // Indicator periods (unchanged — no new indicators added)
//     // =========================================================================
//     private static final int EMA_FAST = 9;
//     private static final int EMA_MID  = 21;
//     private static final int ATR_PERIOD = 14;
//     private static final int ST_PERIOD     = 10;
//     private static final double ST_MULTIPLIER = 3.0;
//     private static final int RSI_PERIOD = 14;
//     private static final int VOLUME_MA_PERIOD = 20;
//     private static final int VWAP_LOOKBACK = 20;

//     private static final String RES_5M = "5";
//     private static final String RES_1H = "60";

//     private static final int BASE_5M_FETCH_COUNT = 450;
//     private static final int GROUP_15M_FROM_5M = 3;
//     private static final int GROUP_30M_FROM_5M = 6;
//     private static final int BASE_1H_FETCH_COUNT = 90;

//     private static final int EMA_SLOPE_LOOKBACK_BARS   = 5;
//     private static final double HTF_EMA_SLOPE_MIN_ATR   = 0.10;
//     private static final double ENTRY_EMA_SLOPE_MIN_ATR = 0.15;

//     private static final int STRUCTURE_SWING_LOOKBACK = 30;
//     private static final int SL_SWING_LOOKBACK         = 20;
//     private static final int TP_LEVEL_LOOKBACK         = 40;

//     // =========================================================================
//     // 1H macro direction / 30M confirmation, scored out of 6.
//     // =========================================================================
//     private static final int MACRO_1H_MIN_SCORE     = 4;
//     private static final int CONFIRM_30M_STRONG_MIN = 5;
//     private static final int CONFIRM_30M_ACCEPTABLE = 4;
//     private static final int CLEAN_15M_MIN_FOR_ACCEPTABLE = 4;

//     // =========================================================================
//     // 15M setup, scored out of 5.
//     // =========================================================================
//     private static final int SETUP_15M_MIN_SCORE = 3;

//     // =========================================================================
//     // 5M pullback + rejection (mandatory) + slope (mandatory).
//     // =========================================================================
//     private static final double PULLBACK_MAX_ATR       = 1.5;
//     private static final double OVEREXTENSION_SKIP_ATR  = 2.0;
//     private static final double CANDLE_BODY_RATIO_MIN   = 0.40;

//     // =========================================================================
//     // 5M supporting confirmation score out of 5 (min 3/5).
//     // =========================================================================
//     private static final int    ENTRY_CONFIRMATION_MIN_SCORE = 3;
//     private static final double RSI_LONG_MIN  = 40, RSI_LONG_MAX  = 70;
//     private static final double RSI_SHORT_MIN = 30, RSI_SHORT_MAX = 60;

//     // =========================================================================
//     // Pending breakout-entry signal.
//     // =========================================================================
//     private static final long SIGNAL_MAX_VALID_MS = 15L * 60 * 1000L; // 15 minutes

//     // =========================================================================
//     // Structural SL: anchored to the pullback swing captured when the signal
//     // was ARMED (not re-searched at breakout time), + ATR buffer, then
//     // CLAMPED into a configurable [SL_MIN_PERCENT, SL_MAX_PERCENT] range so
//     // SL is always very small and directly tunable — regardless of how far
//     // the swing/ATR estimate lands.
//     // =========================================================================
//     private static final double SL_BUFFER_ATR   = 0.15; // small buffer off the swing (tighter than before)
//     private static final double SL_MIN_PERCENT  = 0.3;  // SL can never be tighter than this (tick-noise floor) — tune here
//     private static final double SL_MAX_PERCENT  = 0.5;  // SL can never be wider than this — "very very small" SL, tune here
//     private static final double SL_HARD_PERCENT_CAP  = 5.0;  // absolute safety-net fallback ONLY (e.g. ATR unavailable)

//     // =========================================================================
//     // RR-based TP. Now a VERY HIGH ceiling — most trades will exit via the
//     // trailing stop long before ever reaching this; think of it as an
//     // aspirational target for a runaway trend, not a realistic average.
//     // =========================================================================
//     private static final double RR_DEFAULT = 1.5;  // was 1.5 — tune this for how "high" you want the ceiling
//     private static final double RR_STRONG  = 2.0; // used only for a clean 30M=6/6 setup — was 1.8

//     // =========================================================================
//     // Trailing system — 4 stages, driven by progress toward the ORIGINAL
//     // initial TP (never the extended one).
//     // =========================================================================
//     private static final double BREAKEVEN_TRIGGER            = 0.50;
//     private static final double BREAKEVEN_LOCK_PROFIT_PERCENT = 0.10; // %
//     private static final double TRAIL_STAGE2_TRIGGER = 0.70;
//     private static final double TRAIL_STAGE2_ATR      = 1.75;
//     private static final double TRAIL_STAGE3_TRIGGER = 0.85;
//     private static final double TRAIL_STAGE3_ATR      = 1.35;
//     private static final double MIN_SL_IMPROVEMENT_ATR = 0.10; // don't spam the API on tiny moves

//     // =========================================================================
//     // TP extension — only past stage 3, only while the trend is still valid.
//     // =========================================================================
//     private static final int    MAX_TP_EXTENSIONS = 2;
//     private static final double TP_EXTENSION_ATR  = 1.5;

//     // =========================================================================
//     // Margin-based fixed position sizing (unchanged).
//     // =========================================================================
//     private static final double MAX_MARGIN = 900.0;

//     private static final double LIMIT_ORDER_BUFFER_PCT = 0.0005;

//     private static final long SCALP_COOLDOWN_MS            = 5 * 60 * 1000L;
//     private static final long SCALP_ENTRY_SCAN_INTERVAL_MS = 20 * 1000L;

//     private static class PendingSignal {
//         boolean isLong;
//         double confirmHigh, confirmLow;
//         double setupSwingLevel; // the pullback swing captured AT ARM TIME — never re-searched later
//         boolean strongTrend;    // 30M scored a clean 6/6 at arm time -> eligible for RR_STRONG
//         long   createdAtMs;
//     }
//     private static final Map<String, PendingSignal> pendingSignals = new ConcurrentHashMap<>();

//     private static class TrailInfo {
//         boolean isLong;
//         double entryPrice;
//         double initialSL, initialTP, initialRisk;
//         double currentSL, currentTP;
//         double peakPrice;
//         int    stage;           // 0..3
//         int    extensionsUsed;
//     }
//     private static final Map<String, TrailInfo> trailState = new ConcurrentHashMap<>();

//     private static final Map<String, JSONObject> instrumentCache = new ConcurrentHashMap<>();
//     private static long lastCacheUpdate = 0;
//     private static final Map<String, Long> lastTradeTime = new ConcurrentHashMap<>();

//     private static final String[] COIN_SYMBOLS = {
//        "PIEVERSE","XAU","APE","ERA","US","RAVE","EDEN","LIT","BREV","MAGMA","BLESS","ZAMA",
//         "FRAX","ACU","1000FLOKI","ELSA","LINEA","SPACE","CLO","FIGHT","UMA","MEGA","MAV","TRIA",
//         "YGG","OPN","ROBO","SUI","GLM","MANTRA","SEI","CAKE","AUCTION","SENT","BSB","BASED","IRYS",
//         "ACE","WET","CL","PRL","GENIUS","WIF","MANTA","LSK","AIGENSYN","PHAROS","JUP","AXL","BOME",
//         "SLX","ZEST","AIOT","VVV","CAP","DATAIP","GRVT","TAO","BR","TURBO","BTC","ETH","ZK","LISTA",
//         "A","LTC","XAG","COAI","HANA","ZRO","SKYAI","COPPER","RARE","ETC","M","AKE","XLM","PIXEL",
//         "XAN","ADA","CROSS","XMR","G","DASH","ZEC","ATOM","TRUTH","BCH","NEO","IOST","FLUX","ALGO",
//         "ZRX","COMP","WLFI","POL","DOGE","BAND","OPG","FIDA","PROM","SANTOS","RLC","1000000MOG",
//         "GRASS","PNUT","TRB","KAIA","ARX","XAI","S","4","COTI","CHR","SOLV","SAGA","ORCA","1000LUNC",
//         "MOVE","VIRTUAL","ME","IOTX","GIGGLE","AVA","VELODROME","AIXBT","KMNO","LA","DEXE","ZBT",
//         "GRIFFAIN","BLUAI","CTSI","ROSE","TURTLE","IMX","SUN","APR","TA","ON","BIO","COOKIE",
//         "AVAAI","DOT","TRUMP","MELANIA","GMT","FLOCK","CLANKER","CYS","SUSHI","VTHO","DIA",
//         "SLP","GOAT","BMT","KGEN","GWEI","MUBARAK","LDO","ESP","DRIFT","FORM","PLUME","NIL",
//         "UNI","ZORA","RECALL","INIT","BZ","PARTI","NATGAS","SPX","BANK","AVAX","RIVER","BILL",
//         "ATH","XRP","KERNEL","JST","PUNDIX","HAEDAL","ALPINE","SOON","SOPH","HUMA","TRX","LINK",
//         "HYPE","HIVE","TAIKO","TAG","MYX","NEWT","AIN","USUAL","PUMP","ICNT","BNB","H","BAT",
//         "QTUM","ARC","AIO","BEAT","BTR","ALCH","THETA","VELVET","ARIA","PTB","UB","LIGHT","FF",
//         "EVAAI","GMX","LYN","TAC","LAB","ENJ","AT","MMT","UAI","AAVE","JCT","KSM","HEI","JASMY",
//         "NEAR","TST","SOL","OP","PLAY","INJ","STG","HOLO","ASR","B","LUNA2","RSR","INX","KAT",
//         "ICP","QNT","MAGIC","T","MINA","STX","ACH","LQTY","ID","GRT","NEIRO","XVS","1INCH","SAND",
//         "ANKR","RVN","SFP","KAVA","MANA","HBAR","ARB","MTL","C98","TUT","SIREN","MASK","1000XEC",
//         "AR","ARPA","FIL","LPT","ENS","PEOPLE","LUMIA","DUSK","FLOW","XVG","ARKM","POPCAT","ARK",
//         "MOODENG","SAFE","AXS","BICO","BIGTIME","WAXP","GAS","POWR","TIA","CHIP","STO","ORDI",
//         "BEAMX","1000BONK","PYTH","ETHW","1000RATS","ANIME","OPEN","DYM","BERA","PORTAL","BB",
//         "BANANAS31","CFX","SSV","TNSR","EDU","JELLYJELLY","BLUR","WAL","FHE","WCT","DEEP","SXT",
//         "NAORIS","OG","CVC","AWE","O","BEL","JOE","SQD","1000PEPE","CARV","FET","SAPIEN","MEME",
//         "AVNT","XPIN","ILV","KAS","BNT","STBL","BSV","RIF","SUPER","USTC","METIS","ETHFI","ENA",
//         "1MBABYDOGE","CATI","HMSTR","GPS","SHELL","KAITO","ACT","RPL","BAN","THE","AKT","MORPHO",
//         "CHILLGUY","AERO","MOCA","PENGU","PHA","RED","EPIC","TREE","1000CAT","MAVIA","FARTCOIN",
//         "PAXG","IN","ORDER","VET","ZEN","STABLE","CHZ","NIGHT","NOM","ZKP","SKR","GRAM","BIRB",
//         "CTR","KNC","ZIL","YFI","EGLD","RUNE","ASTR","ONE","1000SHIB","API3","SPELL","WOO","APT",
//         "PENDLE","AGLD","CYBER","CKB","ONG","MOVR","POLYX","TWT","STEEM","ALT","ZETA","REZ","RENDER",
//         "RONIN","STRK","W","SCR","CETUS","IO","MEW","SWARMS","SONIC","PIPPIN","PROMPT","MERL","F",
//         "ESPORTS","PROVE","XNY","USELESS","HEMI","Q","SKY","ZKC","FLUID","MITO","CFG","EDGE","RE",
//         "YB","MET","DOS","FOGO","BTW","ALLO","BROCCOLI714","HYPER","XPL","RESOLV","ASTER","KITE",
//         "SIGN","HOME","MON","CC","SAHARA","MIRA","EUL","TOWNS","SYRUP","C","DOLO","ALICE","BABY",
//         "SOMI","NOT","BARD","SPK","POWER","2Z","BANANA","ENSO","SYN","NXPC","GUN","XTZ","ONT","SKL",
//         "HOT","JTO","DOGS","EIGEN","GTC","GALA","NMR","CGPT","ZEREBRO","VANA","OGN","CELO","USDC",
//         "COW","0G","IOTA","SNX","DYDX","WLD","1000SATS","ONDO","AEVO","BRETT","LAYER","CRV","TLM","KOMA"
//     };

//     private static final Set<String> INTEGER_QTY_PAIRS = Stream.of(COIN_SYMBOLS)
//             .flatMap(s -> Stream.of("B-" + s + "_USDT", s + "_USDT"))
//             .collect(Collectors.toCollection(HashSet::new));

//     private static final String[] COINS_TO_TRADE = Stream.of(COIN_SYMBOLS)
//             .map(s -> "B-" + s + "_USDT")
//             .toArray(String[]::new);

//     // =========================================================================
//     // Market structure (HH/HL vs LL/LH). Returns +1 bullish, -1 bearish, 0.
//     // =========================================================================
//     private static int detectSwingStructure(double[] hi, double[] lo, int lookback) {
//         int n = hi.length;
//         if (n < lookback + 3) return 0;
//         int start = Math.max(1, n - lookback);
//         List<Integer> swingHighIdx = new ArrayList<>();
//         List<Integer> swingLowIdx  = new ArrayList<>();
//         for (int i = start; i < n - 1; i++) {
//             if (hi[i] > hi[i - 1] && hi[i] > hi[i + 1]) swingHighIdx.add(i);
//             if (lo[i] < lo[i - 1] && lo[i] < lo[i + 1]) swingLowIdx.add(i);
//         }
//         boolean hh = false, hl = false, ll = false, lh = false;
//         if (swingHighIdx.size() >= 2) {
//             double h1 = hi[swingHighIdx.get(swingHighIdx.size() - 2)];
//             double h2 = hi[swingHighIdx.get(swingHighIdx.size() - 1)];
//             hh = h2 > h1; lh = h2 < h1;
//         }
//         if (swingLowIdx.size() >= 2) {
//             double l1 = lo[swingLowIdx.get(swingLowIdx.size() - 2)];
//             double l2 = lo[swingLowIdx.get(swingLowIdx.size() - 1)];
//             hl = l2 > l1; ll = l2 < l1;
//         }
//         if (hh && hl) return 1;
//         if (ll && lh) return -1;
//         return 0;
//     }

//     private static double findRecentSwingLow(double[] lo, int lookback) {
//         int n = lo.length;
//         int start = Math.max(1, n - lookback);
//         for (int i = n - 2; i >= start; i--) {
//             if (lo[i] < lo[i - 1] && lo[i] < lo[i + 1]) return lo[i];
//         }
//         int fallbackStart = Math.max(0, n - 8);
//         double min = Double.POSITIVE_INFINITY;
//         for (int i = fallbackStart; i < n; i++) min = Math.min(min, lo[i]);
//         return min;
//     }

//     private static double findRecentSwingHigh(double[] hi, int lookback) {
//         int n = hi.length;
//         int start = Math.max(1, n - lookback);
//         for (int i = n - 2; i >= start; i--) {
//             if (hi[i] > hi[i - 1] && hi[i] > hi[i + 1]) return hi[i];
//         }
//         int fallbackStart = Math.max(0, n - 8);
//         double max = Double.NEGATIVE_INFINITY;
//         for (int i = fallbackStart; i < n; i++) max = Math.max(max, hi[i]);
//         return max;
//     }

//     // Only a MEANINGFUL, CLOSE obstacle blocks the trade — a level sitting
//     // right next to TP itself is not treated as blocking.
//     private static boolean tpBlockedByLevel(boolean isLong, double entry, double tp, double[] hi, double[] lo, int lookback) {
//         double path = Math.abs(tp - entry);
//         double nearThreshold = isLong ? entry + 0.7 * path : entry - 0.7 * path;
//         int n = isLong ? hi.length : lo.length;
//         int start = Math.max(1, n - lookback);
//         if (isLong) {
//             for (int i = n - 2; i >= start; i--) {
//                 if (hi[i] > hi[i - 1] && hi[i] > hi[i + 1]) {
//                     double level = hi[i];
//                     if (level > entry && level <= nearThreshold) return true;
//                 }
//             }
//         } else {
//             for (int i = n - 2; i >= start; i--) {
//                 if (lo[i] < lo[i - 1] && lo[i] < lo[i + 1]) {
//                     double level = lo[i];
//                     if (level < entry && level >= nearThreshold) return true;
//                 }
//             }
//         }
//         return false;
//     }

//     private static class ScoredDirection {
//         boolean valid;
//         int bullScore, bearScore;
//     }

//     private static ScoredDirection analyzeScored6(JSONArray candles) {
//         ScoredDirection r = new ScoredDirection();
//         if (candles == null || candles.length() < EMA_MID + Math.max(ATR_PERIOD, ST_PERIOD) + STRUCTURE_SWING_LOOKBACK) {
//             r.valid = false;
//             return r;
//         }
//         double[] cl = extractCloses(candles);
//         double[] hi = extractHighs(candles);
//         double[] lo = extractLows(candles);

//         double ema9  = calcEMA(cl, EMA_FAST);
//         double ema21 = calcEMA(cl, EMA_MID);
//         double atr   = calcATR(hi, lo, cl, ATR_PERIOD);
//         double price = cl[cl.length - 1];

//         boolean[] stSeries = calcSupertrend(hi, lo, cl, ST_PERIOD, ST_MULTIPLIER);
//         boolean stGreen = stSeries[stSeries.length - 1];

//         double[] ema9Series = calcEMASeries(cl, EMA_FAST);
//         int n = ema9Series.length;
//         int lookback = Math.min(EMA_SLOPE_LOOKBACK_BARS, n - 1);
//         double emaSlope = ema9Series[n - 1] - ema9Series[n - 1 - lookback];
//         boolean slopeUp   = atr > 0 && emaSlope >= HTF_EMA_SLOPE_MIN_ATR * atr;
//         boolean slopeDown = atr > 0 && emaSlope <= -HTF_EMA_SLOPE_MIN_ATR * atr;

//         int structure = detectSwingStructure(hi, lo, STRUCTURE_SWING_LOOKBACK);

//         int bull = 0, bear = 0;
//         if (ema9 > ema21) bull++; else if (ema9 < ema21) bear++;
//         if (price > ema9) bull++; else bear++;
//         if (price > ema21) bull++; else bear++;
//         if (stGreen) bull++; else bear++;
//         if (slopeUp) bull++; else if (slopeDown) bear++;
//         if (structure == 1) bull++; else if (structure == -1) bear++;

//         r.valid = true;
//         r.bullScore = bull;
//         r.bearScore = bear;
//         return r;
//     }

//     private static class Setup15Result {
//         boolean valid;
//         int bullScore, bearScore;
//         double stLower, stUpper;
//     }

//     private static Setup15Result analyzeSetup15M(JSONArray candles) {
//         Setup15Result r = new Setup15Result();
//         if (candles == null || candles.length() < EMA_MID + Math.max(ATR_PERIOD, ST_PERIOD)) {
//             r.valid = false;
//             return r;
//         }
//         double[] cl = extractCloses(candles);
//         double[] hi = extractHighs(candles);
//         double[] lo = extractLows(candles);

//         double ema9  = calcEMA(cl, EMA_FAST);
//         double ema21 = calcEMA(cl, EMA_MID);
//         double atr   = calcATR(hi, lo, cl, ATR_PERIOD);
//         double price = cl[cl.length - 1];

//         boolean[] stSeries = calcSupertrend(hi, lo, cl, ST_PERIOD, ST_MULTIPLIER);
//         boolean stGreen = stSeries[stSeries.length - 1];
//         double[] bands = calcSupertrendBands(hi, lo, cl, ST_PERIOD, ST_MULTIPLIER);
//         r.stLower = bands[0];
//         r.stUpper = bands[1];

//         double[] ema9Series = calcEMASeries(cl, EMA_FAST);
//         int n = ema9Series.length;
//         int lookback = Math.min(EMA_SLOPE_LOOKBACK_BARS, n - 1);
//         double emaSlope = ema9Series[n - 1] - ema9Series[n - 1 - lookback];
//         boolean slopeUp   = atr > 0 && emaSlope >= HTF_EMA_SLOPE_MIN_ATR * atr;
//         boolean slopeDown = atr > 0 && emaSlope <= -HTF_EMA_SLOPE_MIN_ATR * atr;

//         int bull = 0, bear = 0;
//         if (ema9 > ema21) bull++; else if (ema9 < ema21) bear++;
//         if (price > ema9) bull++; else bear++;
//         if (stGreen) bull++; else bear++;
//         if (slopeUp) bull++; else if (slopeDown) bear++;
//         if (price > ema9 && price > ema21) bull++; else if (price < ema9 && price < ema21) bear++;

//         r.valid = true;
//         r.bullScore = bull;
//         r.bearScore = bear;
//         return r;
//     }

//     private static class EntryResult {
//         boolean valid;
//         boolean setupFound;
//         double confirmHigh, confirmLow;
//         double atr5m;
//         double distanceAtr;
//         String reason;
//     }

//     private static EntryResult analyzeEntry5M(JSONArray raw5m, boolean trendUp) {
//         EntryResult t = new EntryResult();
//         int minBars = EMA_MID + Math.max(ATR_PERIOD, Math.max(RSI_PERIOD, VOLUME_MA_PERIOD)) + 5;
//         if (raw5m == null || raw5m.length() < minBars) {
//             t.valid = false;
//             return t;
//         }

//         double[] cl  = extractCloses(raw5m);
//         double[] op  = extractOpens(raw5m);
//         double[] hi  = extractHighs(raw5m);
//         double[] lo  = extractLows(raw5m);
//         double[] vol = extractVolumes(raw5m);
//         int n = cl.length;

//         double ema9  = calcEMA(cl, EMA_FAST);
//         double ema21 = calcEMA(cl, EMA_MID);
//         double atr5m = calcATR(hi, lo, cl, ATR_PERIOD);
//         t.atr5m = atr5m;

//         double entryClose = cl[n - 1], entryOpen = op[n - 1];
//         double entryHigh  = hi[n - 1], entryLow  = lo[n - 1];

//         double nearestEmaDist = Math.min(Math.abs(entryClose - ema9), Math.abs(entryClose - ema21));
//         double distanceAtr = atr5m > 0 ? nearestEmaDist / atr5m : 0;
//         t.distanceAtr = distanceAtr;
//         boolean pulledBack = distanceAtr <= PULLBACK_MAX_ATR;

//         boolean directionalCandle = trendUp ? (entryClose > entryOpen) : (entryClose < entryOpen);
//         double body  = Math.abs(entryClose - entryOpen);
//         double range = entryHigh - entryLow;
//         boolean notDoji = range > 0 && (body / range) >= CANDLE_BODY_RATIO_MIN;

//         double closePositionInRange = range > 0
//                 ? (trendUp ? (entryClose - entryLow) / range : (entryHigh - entryClose) / range)
//                 : 0;
//         boolean rejectionOk = closePositionInRange >= 0.60;

//         double[] ema9Series5m = calcEMASeries(cl, EMA_FAST);
//         int lookback5m = Math.min(EMA_SLOPE_LOOKBACK_BARS, n - 1);
//         double emaSlope5m = ema9Series5m[n - 1] - ema9Series5m[n - 1 - lookback5m];
//         boolean slope5mOk = trendUp
//                 ? (atr5m > 0 && emaSlope5m >= ENTRY_EMA_SLOPE_MIN_ATR * atr5m)
//                 : (atr5m > 0 && emaSlope5m <= -ENTRY_EMA_SLOPE_MIN_ATR * atr5m);

//         boolean mandatoryOk = pulledBack && directionalCandle && notDoji && rejectionOk && slope5mOk;

//         int volStart = Math.max(0, n - 1 - VOLUME_MA_PERIOD);
//         double avgVol = 0; int cnt = 0;
//         for (int i = volStart; i < n - 1; i++) { avgVol += vol[i]; cnt++; }
//         avgVol = cnt > 0 ? avgVol / cnt : 0;
//         boolean volumeOk = avgVol > 0 && vol[n - 1] >= avgVol;

//         double rsi = calcRSI(cl, RSI_PERIOD);
//         boolean rsiOk = trendUp
//                 ? (rsi >= RSI_LONG_MIN && rsi <= RSI_LONG_MAX)
//                 : (rsi >= RSI_SHORT_MIN && rsi <= RSI_SHORT_MAX);

//         int vwapStart = Math.max(0, n - VWAP_LOOKBACK);
//         double cumPV = 0, cumV = 0;
//         for (int i = vwapStart; i < n; i++) {
//             double typical = (hi[i] + lo[i] + cl[i]) / 3.0;
//             cumPV += typical * vol[i];
//             cumV  += vol[i];
//         }
//         double vwap = cumV > 0 ? cumPV / cumV : entryClose;
//         boolean vwapOk = trendUp ? entryClose >= vwap : entryClose <= vwap;

//         boolean[] stSeries5m = calcSupertrend(hi, lo, cl, ST_PERIOD, ST_MULTIPLIER);
//         boolean st5mOk = trendUp ? stSeries5m[stSeries5m.length - 1] : !stSeries5m[stSeries5m.length - 1];

//         boolean emaAlignOk = trendUp ? (entryClose > ema9 && entryClose > ema21) : (entryClose < ema9 && entryClose < ema21);

//         int confirmationScore = (volumeOk ? 1 : 0) + (rsiOk ? 1 : 0) + (vwapOk ? 1 : 0)
//                 + (st5mOk ? 1 : 0) + (emaAlignOk ? 1 : 0);

//         t.setupFound = mandatoryOk && confirmationScore >= ENTRY_CONFIRMATION_MIN_SCORE;
//         t.confirmHigh = entryHigh;
//         t.confirmLow  = entryLow;
//         t.valid = true;
//         t.reason = String.format(
//                 "pullback=%.2fATR(ok=%s) rejection=%s(pos=%.2f) directional=%s notDoji=%s slope=%s vol=%s rsi=%.1f(ok=%s) vwap=%s st5m=%s emaAlign=%s score=%d/5",
//                 distanceAtr, pulledBack, rejectionOk, closePositionInRange, directionalCandle, notDoji, slope5mOk,
//                 volumeOk, rsi, rsiOk, vwapOk, st5mOk, emaAlignOk, confirmationScore);
//         return t;
//     }

//     private static JSONArray dropLastIfForming(JSONArray arr) {
//         if (arr == null || arr.length() < 2) return arr;
//         JSONArray out = new JSONArray();
//         for (int i = 0; i < arr.length() - 1; i++) out.put(arr.getJSONObject(i));
//         return out;
//     }

//     // Shared SL/TP construction from a given anchor level — used both by the
//     // pending-signal path (anchor captured at arm time) and by the safety
//     // sweep / reconstruction path (anchor searched fresh). The final SL
//     // distance is CLAMPED into [SL_MIN_PERCENT, SL_MAX_PERCENT] of the entry
//     // price — this deliberately trades off against the earlier "never
//     // artificially tighten SL" principle, since "keep SL very small and
//     // directly tunable" is now the explicit priority.
//     private static double[] slTpFromLevel(boolean isLong, double entryPrice, double swingLevel,
//                                            double atr, double tickSize, boolean strongTrend) {
//         double sl;
//         if (atr > 0) {
//             sl = isLong ? swingLevel - SL_BUFFER_ATR * atr : swingLevel + SL_BUFFER_ATR * atr;
//         } else {
//             sl = isLong ? entryPrice * (1 - SL_MAX_PERCENT / 100.0) : entryPrice * (1 + SL_MAX_PERCENT / 100.0);
//         }

//         // If the structural side ended up wrong, fall back to a plain
//         // percentage SL on the correct side.
//         boolean slSideValid = isLong ? sl < entryPrice : sl > entryPrice;
//         if (!slSideValid) {
//             sl = isLong ? entryPrice * (1 - SL_MAX_PERCENT / 100.0) : entryPrice * (1 + SL_MAX_PERCENT / 100.0);
//         }

//         double slPercent = Math.abs(entryPrice - sl) / entryPrice * 100.0;
//         if (slPercent < SL_MIN_PERCENT) {
//             sl = isLong ? entryPrice * (1 - SL_MIN_PERCENT / 100.0) : entryPrice * (1 + SL_MIN_PERCENT / 100.0);
//         } else if (slPercent > SL_MAX_PERCENT) {
//             sl = isLong ? entryPrice * (1 - SL_MAX_PERCENT / 100.0) : entryPrice * (1 + SL_MAX_PERCENT / 100.0);
//         }

//         double rrTarget = strongTrend ? RR_STRONG : RR_DEFAULT;
//         double risk = Math.abs(entryPrice - sl);
//         double tp = isLong ? entryPrice + rrTarget * risk : entryPrice - rrTarget * risk;

//         sl = roundToTick(sl, tickSize);
//         tp = roundToTick(tp, tickSize);
//         double finalSlPercent = Math.abs(entryPrice - sl) / entryPrice * 100.0;
//         return new double[]{sl, tp, finalSlPercent, rrTarget};
//     }

//     // Fresh swing search — only used when there is no captured setup anchor
//     // (safety sweep on an externally/pre-existing position, or a restart).
//     private static double[] computeFreshStructuralSlTp(boolean isLong, double entryPrice,
//                                                          double[] hi5m, double[] lo5m, double atr,
//                                                          double tickSize, boolean strongTrend) {
//         double swingLevel = isLong ? findRecentSwingLow(lo5m, SL_SWING_LOOKBACK) : findRecentSwingHigh(hi5m, SL_SWING_LOOKBACK);
//         return slTpFromLevel(isLong, entryPrice, swingLevel, atr, tickSize, strongTrend);
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

//     private static double calcQuantity(double price, String pair) {
//         double usdtInrRate = 98.0;
//         double qty = MAX_MARGIN / (price * usdtInrRate);
//         double finalQty = INTEGER_QTY_PAIRS.contains(pair) ? Math.floor(qty) : Math.floor(qty * 100) / 100.0;
//         return Math.max(finalQty, 0);
//     }

//     public static void main(String[] args) {
//         System.out.println("=== Bot starting (trend-continuation cascade + clamped tight SL + very-high RR ceiling + 4-stage trailing, no early exit) ===");
//         initInstrumentCache();

//         while (true) {
//             try {
//                 runEntryScan();
//             } catch (Throwable t) {
//                 System.err.println("[MAIN-LOOP] Uncaught error, continuing: " + t.getMessage());
//                 t.printStackTrace();
//             }
//             try {
//                 TimeUnit.MILLISECONDS.sleep(SCALP_ENTRY_SCAN_INTERVAL_MS);
//             } catch (InterruptedException ignored) {
//                 Thread.currentThread().interrupt();
//                 break;
//             }
//         }
//     }

//     private static void runEntryScan() {
//         Set<String> active = getActivePositions();
//         System.out.println("Active positions: " + active);
//         pendingSignals.keySet().removeIf(active::contains);
//         trailState.keySet().removeIf(p -> !active.contains(p));

//         if (active.size() >= MAX_OPEN_POSITIONS) {
//             System.out.println("MAX_OPEN_POSITIONS reached — skipping scan.");
//             updateTrailing();
//             ensureTpSlForOpenPositions();
//             return;
//         }

//         for (String pair : COINS_TO_TRADE) {
//             try {
//                 if (active.size() >= MAX_OPEN_POSITIONS) break;
//                 if (active.contains(pair)) continue;

//                 long lastTrade = lastTradeTime.getOrDefault(pair, 0L);
//                 if (System.currentTimeMillis() - lastTrade < SCALP_COOLDOWN_MS) continue;

//                 JSONArray raw5m = dropLastIfForming(getCandlestickData(pair, RES_5M, BASE_5M_FETCH_COUNT));
//                 if (raw5m == null || raw5m.length() < EMA_MID + ST_PERIOD + STRUCTURE_SWING_LOOKBACK) continue;

//                 PendingSignal pending = pendingSignals.get(pair);

//                 if (pending != null) {
//                     boolean cancelled = false;
//                     if (System.currentTimeMillis() - pending.createdAtMs > SIGNAL_MAX_VALID_MS) {
//                         System.out.println("  Signal expired (stale setup): " + pair);
//                         cancelled = true;
//                     } else {
//                         EntryResult quickCheck = analyzeEntry5M(raw5m, pending.isLong);
//                         if (quickCheck.valid && quickCheck.distanceAtr > OVEREXTENSION_SKIP_ATR) {
//                             System.out.println("  Signal cancelled (price overextended "
//                                     + String.format("%.2f", quickCheck.distanceAtr) + " ATR): " + pair);
//                             cancelled = true;
//                         }
//                     }
//                     if (!cancelled) {
//                         JSONArray raw1h = dropLastIfForming(getCandlestickData(pair, RES_1H, BASE_1H_FETCH_COUNT));
//                         ScoredDirection dir1h = analyzeScored6(raw1h);
//                         if (dir1h.valid) {
//                             boolean stillAgrees = pending.isLong ? dir1h.bullScore >= MACRO_1H_MIN_SCORE : dir1h.bearScore >= MACRO_1H_MIN_SCORE;
//                             if (!stillAgrees) {
//                                 System.out.println("  Signal cancelled (1H direction changed): " + pair);
//                                 cancelled = true;
//                             }
//                         }
//                     }

//                     if (cancelled) {
//                         pendingSignals.remove(pair);
//                     } else {
//                         double currentPrice = getLastPrice(pair);
//                         if (currentPrice > 0) {
//                             boolean breakoutHit = pending.isLong
//                                     ? currentPrice > pending.confirmHigh
//                                     : currentPrice < pending.confirmLow;
//                             if (breakoutHit) {
//                                 tryEnterOnBreakout(pair, pending, raw5m, currentPrice, active);
//                             }
//                         }
//                         continue;
//                     }
//                 }

//                 // ---- No pending signal — look for a fresh one ----

//                 JSONArray raw1h = dropLastIfForming(getCandlestickData(pair, RES_1H, BASE_1H_FETCH_COUNT));
//                 ScoredDirection dir1h = analyzeScored6(raw1h);
//                 if (!dir1h.valid) continue;
//                 boolean trendUp;
//                 if (dir1h.bullScore >= MACRO_1H_MIN_SCORE) trendUp = true;
//                 else if (dir1h.bearScore >= MACRO_1H_MIN_SCORE) trendUp = false;
//                 else continue;

//                 JSONArray raw30m = aggregateCandles(raw5m, GROUP_30M_FROM_5M);
//                 ScoredDirection dir30m = analyzeScored6(raw30m);
//                 if (!dir30m.valid) continue;
//                 int score30 = trendUp ? dir30m.bullScore : dir30m.bearScore;
//                 int opposite30 = trendUp ? dir30m.bearScore : dir30m.bullScore;
//                 if (opposite30 > score30) continue;

//                 boolean strong30 = score30 >= CONFIRM_30M_STRONG_MIN;
//                 boolean acceptable30 = score30 == CONFIRM_30M_ACCEPTABLE;
//                 if (!strong30 && !acceptable30) continue;

//                 JSONArray raw15m = aggregateCandles(raw5m, GROUP_15M_FROM_5M);
//                 Setup15Result setup15 = analyzeSetup15M(raw15m);
//                 if (!setup15.valid) continue;
//                 int score15 = trendUp ? setup15.bullScore : setup15.bearScore;
//                 if (score15 < SETUP_15M_MIN_SCORE) continue;

//                 EntryResult entry5m = analyzeEntry5M(raw5m, trendUp);
//                 if (!entry5m.valid) continue;

//                 if (acceptable30) {
//                     boolean clean15 = score15 >= CLEAN_15M_MIN_FOR_ACCEPTABLE;
//                     boolean clean5 = entry5m.setupFound;
//                     if (!clean15 || !clean5) continue;
//                 }

//                 if (!entry5m.setupFound) continue;

//                 // Capture the pullback swing NOW — this is the anchor the SL
//                 // will use at breakout, so it never drifts to a newer/farther
//                 // swing while the signal is pending.
//                 double[] hi5mArm = extractHighs(raw5m);
//                 double[] lo5mArm = extractLows(raw5m);
//                 double swingLevel = trendUp
//                         ? findRecentSwingLow(lo5mArm, SL_SWING_LOOKBACK)
//                         : findRecentSwingHigh(hi5mArm, SL_SWING_LOOKBACK);

//                 PendingSignal newSignal = new PendingSignal();
//                 newSignal.isLong = trendUp;
//                 newSignal.confirmHigh = entry5m.confirmHigh;
//                 newSignal.confirmLow  = entry5m.confirmLow;
//                 newSignal.setupSwingLevel = swingLevel;
//                 newSignal.strongTrend = (score30 == 6);
//                 newSignal.createdAtMs = System.currentTimeMillis();
//                 pendingSignals.put(pair, newSignal);
//                 System.out.println("  Pending " + (trendUp ? "LONG" : "SHORT") + " signal armed: " + pair
//                         + " | 1H=" + (trendUp ? dir1h.bullScore : dir1h.bearScore) + "/6 30M=" + score30 + "/6 15M=" + score15 + "/5"
//                         + " | trigger=" + (trendUp ? ("break " + newSignal.confirmHigh) : ("break " + newSignal.confirmLow))
//                         + " | " + entry5m.reason);

//             } catch (Exception e) {
//                 System.err.println("Error on " + pair + ": " + e.getMessage());
//             }
//         }

//         System.out.println("\n=== Scan complete ===");
//         updateTrailing();
//         ensureTpSlForOpenPositions();
//     }

//     // Handles an armed pending signal's breakout: uses the ANCHOR captured at
//     // arm time (never a newer swing), applies the TP-location check, sizes
//     // the position, places the order, confirms the fill, and re-derives
//     // final SL/TP off the actual fill price (same anchor).
//     private static void tryEnterOnBreakout(String pair, PendingSignal pending, JSONArray raw5m,
//                                             double currentPrice, Set<String> active) {
//         try {
//             double tickSize = getTickSize(pair);
//             double[] hi5m = extractHighs(raw5m);
//             double[] lo5m = extractLows(raw5m);
//             double atr = calcATR(hi5m, lo5m, extractCloses(raw5m), ATR_PERIOD);

//             double[] preSlTp = slTpFromLevel(pending.isLong, currentPrice, pending.setupSwingLevel, atr, tickSize, pending.strongTrend);

//             JSONArray raw15m = aggregateCandles(raw5m, GROUP_15M_FROM_5M);
//             if (raw15m != null) {
//                 double[] hi15m = extractHighs(raw15m);
//                 double[] lo15m = extractLows(raw15m);
//                 if (tpBlockedByLevel(pending.isLong, currentPrice, preSlTp[1], hi15m, lo15m, TP_LEVEL_LOOKBACK)) {
//                     System.out.println("  NO TRADE: " + pair + " — meaningful nearby " + (pending.isLong ? "resistance" : "support") + " blocks TP");
//                     pendingSignals.remove(pair);
//                     return;
//                 }
//             }

//             double qty = calcQuantity(currentPrice, pair);
//             if (qty <= 0) { pendingSignals.remove(pair); return; }

//             System.out.println("\n==== " + pair + " — BREAKOUT ENTRY ====");
//             String side = pending.isLong ? "buy" : "sell";
//             JSONObject orderResp = placeFuturesOrder(side, pair, qty, LEVERAGE,
//                     "email_notification", "isolated", "INR", currentPrice);
//             if (orderResp == null || !orderResp.has("id")) {
//                 System.out.println("  Order failed: " + orderResp);
//                 pendingSignals.remove(pair);
//                 return;
//             }

//             System.out.println("  Order placed! id=" + orderResp.getString("id"));
//             lastTradeTime.put(pair, System.currentTimeMillis());
//             double swingAnchor = pending.setupSwingLevel;
//             boolean strongTrend = pending.strongTrend;
//             boolean isLong = pending.isLong;
//             pendingSignals.remove(pair);

//             double entry = getEntryPrice(pair, orderResp.getString("id"));
//             if (entry <= 0) {
//                 System.out.println("  Could not confirm entry within window — TP/SL handled by safety sweep");
//                 active.add(pair);
//                 return;
//             }
//             System.out.printf("  Entry confirmed: %.6f%n", entry);

//             double[] slTp = slTpFromLevel(isLong, entry, swingAnchor, atr, tickSize, strongTrend);
//             double slPrice = slTp[0], tpPrice = slTp[1], rrUsed = slTp[3];
//             double[] clamped = sanityClampSlTp(isLong, entry, slPrice, tpPrice, tickSize);
//             slPrice = clamped[0]; tpPrice = clamped[1];

//             double risk = Math.abs(entry - slPrice);
//             System.out.println("[ENTRY] " + pair + " " + (isLong ? "LONG" : "SHORT")
//                     + " Entry=" + entry + " SL=" + slPrice + " TP=" + tpPrice
//                     + " Risk=" + risk + " RR=" + String.format("%.2f", rrUsed)
//                     + " SL%=" + String.format("%.2f", slTp[2]) + " (clamped to [" + SL_MIN_PERCENT + "%, " + SL_MAX_PERCENT + "%])");

//             String posId = getPositionId(pair);
//             if (posId != null) {
//                 setTpSlWithRetry(posId, tpPrice, slPrice, pair);
//             } else {
//                 System.out.println("  Position ID not found after retries — safety sweep will handle it");
//             }

//             TrailInfo ti = new TrailInfo();
//             ti.isLong = isLong;
//             ti.entryPrice = entry;
//             ti.initialSL = slPrice;
//             ti.initialTP = tpPrice;
//             ti.initialRisk = risk;
//             ti.currentSL = slPrice;
//             ti.currentTP = tpPrice;
//             ti.peakPrice = entry;
//             ti.stage = 0;
//             ti.extensionsUsed = 0;
//             trailState.put(pair, ti);

//             active.add(pair);
//         } catch (Exception e) {
//             System.err.println("tryEnterOnBreakout(" + pair + "): " + e.getMessage());
//         }
//     }

//     // =========================================================================
//     // Trailing monitor (NO early exit) — runs every cycle for every open
//     // position. Trailing only: profit lock -> ATR trail -> tighter ATR trail
//     // -> TP extension. SL only ever moves in the profitable direction. A
//     // position only ends via TP hit, SL hit, or the trailing stop.
//     // =========================================================================
//     private static void updateTrailing() {
//         Set<String> stillOpen = getActivePositions();
//         for (String pair : stillOpen) {
//             try {
//                 JSONObject pos = findPosition(pair);
//                 if (pos == null) continue;

//                 TrailInfo ti = trailState.get(pair);
//                 if (ti == null) {
//                     // Reconstruct from the exchange position — never reset a
//                     // profitable trade's SL/TP back to some fresh initial guess.
//                     double avgPrice = pos.optDouble("avg_price", 0);
//                     double tpTrig = pos.optDouble("take_profit_trigger", 0);
//                     double slTrig = pos.optDouble("stop_loss_trigger", 0);
//                     if (avgPrice <= 0) continue;
//                     boolean isLong = pos.optDouble("active_pos", 0) >= 0;
//                     ti = new TrailInfo();
//                     ti.isLong = isLong;
//                     ti.entryPrice = avgPrice;
//                     ti.currentSL = slTrig;
//                     ti.currentTP = tpTrig;
//                     ti.initialSL = slTrig;
//                     ti.initialTP = tpTrig;
//                     ti.initialRisk = slTrig > 0 ? Math.abs(avgPrice - slTrig) : 0;
//                     ti.peakPrice = avgPrice;
//                     ti.stage = 0;
//                     ti.extensionsUsed = 0;
//                     trailState.put(pair, ti);
//                     if (ti.currentSL <= 0 || ti.currentTP <= 0) continue; // let the safety sweep set initial protection first
//                 }

//                 JSONArray raw5m = dropLastIfForming(getCandlestickData(pair, RES_5M, BASE_5M_FETCH_COUNT));
//                 if (raw5m == null || raw5m.length() < EMA_MID + ST_PERIOD + 5) continue;
//                 double[] hi5m = extractHighs(raw5m), lo5m = extractLows(raw5m), cl5m = extractCloses(raw5m);
//                 double atr5m = calcATR(hi5m, lo5m, cl5m, ATR_PERIOD);
//                 if (atr5m <= 0) continue;

//                 // ---- Trailing (only if we have a valid initial baseline) ----
//                 if (ti.initialTP <= 0 || ti.initialRisk <= 0) continue;

//                 double currentPrice = getLastPrice(pair);
//                 if (currentPrice <= 0) continue;
//                 ti.peakPrice = ti.isLong ? Math.max(ti.peakPrice, currentPrice) : Math.min(ti.peakPrice, currentPrice);

//                 double tpDistance = ti.isLong ? ti.initialTP - ti.entryPrice : ti.entryPrice - ti.initialTP;
//                 if (tpDistance <= 0) continue;
//                 double progress = (ti.isLong ? (currentPrice - ti.entryPrice) : (ti.entryPrice - currentPrice)) / tpDistance;

//                 int newStage = progress >= TRAIL_STAGE3_TRIGGER ? 3 : progress >= TRAIL_STAGE2_TRIGGER ? 2 : progress >= BREAKEVEN_TRIGGER ? 1 : 0;
//                 if (newStage > ti.stage) ti.stage = newStage;

//                 double tick = getTickSize(pair);
//                 double candidateSL = ti.currentSL;

//                 if (ti.stage >= 1) {
//                     double lockSL = ti.isLong
//                             ? ti.entryPrice * (1 + BREAKEVEN_LOCK_PROFIT_PERCENT / 100.0)
//                             : ti.entryPrice * (1 - BREAKEVEN_LOCK_PROFIT_PERCENT / 100.0);
//                     if (ti.isLong ? lockSL > candidateSL : lockSL < candidateSL) candidateSL = lockSL;
//                 }
//                 double trailMult = ti.stage >= 3 ? TRAIL_STAGE3_ATR : ti.stage == 2 ? TRAIL_STAGE2_ATR : 0;
//                 if (trailMult > 0) {
//                     double atrSL = ti.isLong ? currentPrice - trailMult * atr5m : currentPrice + trailMult * atr5m;
//                     if (ti.isLong ? atrSL > candidateSL : atrSL < candidateSL) candidateSL = atrSL;
//                 }

//                 boolean changed = false;
//                 double minImprovement = Math.max(MIN_SL_IMPROVEMENT_ATR * atr5m, tick);
//                 boolean meaningfulImprovement = ti.isLong
//                         ? (candidateSL - ti.currentSL) >= minImprovement
//                         : (ti.currentSL - candidateSL) >= minImprovement;

//                 if (meaningfulImprovement) {
//                     candidateSL = roundToTick(candidateSL, tick);
//                     // Never move SL backward — ratchet-only.
//                     if (ti.isLong ? candidateSL > ti.currentSL : candidateSL < ti.currentSL) {
//                         System.out.printf("  [TRAIL] %s %s progress=%.0f%% SL moved %.6f -> %.6f%n",
//                                 pair, ti.isLong ? "LONG" : "SHORT", progress * 100, ti.currentSL, candidateSL);
//                         ti.currentSL = candidateSL;
//                         changed = true;
//                     } else {
//                         System.out.println("  [TRAIL] " + pair + " candidate SL would worsen existing SL — ignoring update");
//                     }
//                 }

//                 // ---- TP extension (only past stage 3, trend still valid) ----
//                 if (ti.stage >= 3 && ti.extensionsUsed < MAX_TP_EXTENSIONS) {
//                     JSONArray raw15m = aggregateCandles(raw5m, GROUP_15M_FROM_5M);
//                     JSONArray raw30m = aggregateCandles(raw5m, GROUP_30M_FROM_5M);
//                     JSONArray raw1h  = dropLastIfForming(getCandlestickData(pair, RES_1H, BASE_1H_FETCH_COUNT));
//                     ScoredDirection dir1h = analyzeScored6(raw1h);
//                     boolean trendValid = checkTrendStillValid(ti.isLong, dir1h, raw30m, raw15m);
//                     if (trendValid) {
//                         double newTP = ti.isLong ? currentPrice + atr5m * TP_EXTENSION_ATR : currentPrice - atr5m * TP_EXTENSION_ATR;
//                         newTP = roundToTick(newTP, tick);
//                         if (ti.isLong ? newTP > ti.currentTP : newTP < ti.currentTP) {
//                             System.out.println("  [TP EXTENSION] " + pair + " " + (ti.isLong ? "LONG" : "SHORT")
//                                     + " TP " + ti.currentTP + " -> " + newTP
//                                     + " Extension " + (ti.extensionsUsed + 1) + "/" + MAX_TP_EXTENSIONS);
//                             ti.currentTP = newTP;
//                             ti.extensionsUsed++;
//                             changed = true;
//                         }
//                     }
//                 }

//                 if (changed) {
//                     String posId = pos.optString("id", null);
//                     if (posId != null) setTpSlWithRetry(posId, ti.currentTP, ti.currentSL, pair);
//                 }
//             } catch (Exception e) {
//                 System.err.println("updateTrailing(" + pair + "): " + e.getMessage());
//             }
//         }
//     }

//     private static boolean checkTrendStillValid(boolean isLong, ScoredDirection dir1h, JSONArray raw30m, JSONArray raw15m) {
//         if (dir1h.valid) {
//             boolean ok1h = isLong ? dir1h.bullScore >= MACRO_1H_MIN_SCORE : dir1h.bearScore >= MACRO_1H_MIN_SCORE;
//             if (!ok1h) return false;
//         }
//         ScoredDirection dir30 = analyzeScored6(raw30m);
//         if (dir30.valid) {
//             boolean ok30 = isLong ? dir30.bullScore >= CONFIRM_30M_ACCEPTABLE : dir30.bearScore >= CONFIRM_30M_ACCEPTABLE;
//             if (!ok30) return false;
//         }
//         Setup15Result s15 = analyzeSetup15M(raw15m);
//         if (s15.valid) {
//             int score15 = isLong ? s15.bullScore : s15.bearScore;
//             if (score15 < SETUP_15M_MIN_SCORE) return false;
//         }
//         return true;
//     }

//     // Never overwrite an existing better (possibly trailed) SL/TP. Only fills
//     // in whichever side is genuinely missing.
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
//                 if (tpTrig > 0 && slTrig > 0) continue; // both present — trailing owns updates from here, never touch

//                 System.out.println("  [SWEEP] " + pair + " missing TP and/or SL — computing fallback protection...");
//                 boolean isLong = pos.optDouble("active_pos", 0) >= 0;
//                 double tick = getTickSize(pair);
//                 TrailInfo ti = trailState.get(pair);

//                 double sl, tp;
//                 if (ti != null && ti.currentSL > 0 && ti.currentTP > 0) {
//                     sl = ti.currentSL; tp = ti.currentTP; // trust our own tracked (possibly trailed) values
//                 } else {
//                     JSONArray raw5m = dropLastIfForming(getCandlestickData(pair, RES_5M, BASE_5M_FETCH_COUNT));
//                     double[] hi5m = null, lo5m = null; double atr5m = 0;
//                     if (raw5m != null && raw5m.length() >= ATR_PERIOD + SL_SWING_LOOKBACK) {
//                         hi5m = extractHighs(raw5m); lo5m = extractLows(raw5m);
//                         atr5m = calcATR(hi5m, lo5m, extractCloses(raw5m), ATR_PERIOD);
//                     }
//                     double[] slTp = (hi5m != null && atr5m > 0)
//                             ? computeFreshStructuralSlTp(isLong, avgPrice, hi5m, lo5m, atr5m, tick, false)
//                             : null;
//                     if (slTp == null) {
//                         sl = isLong ? avgPrice * (1 - SL_HARD_PERCENT_CAP / 100.0) : avgPrice * (1 + SL_HARD_PERCENT_CAP / 100.0);
//                         tp = isLong ? avgPrice + RR_DEFAULT * (avgPrice - sl) : avgPrice - RR_DEFAULT * (sl - avgPrice);
//                         System.out.println("  [SWEEP] structural SL unavailable for " + pair + " — using hard % fallback cap");
//                     } else {
//                         sl = slTp[0]; tp = slTp[1];
//                     }
//                 }

//                 // Preserve whichever side the exchange already has set — never overwrite it.
//                 if (slTrig > 0) sl = slTrig;
//                 if (tpTrig > 0) tp = tpTrig;

//                 double[] clamped = sanityClampSlTp(isLong, avgPrice, sl, tp, tick);
//                 sl = clamped[0]; tp = clamped[1];

//                 String posId = pos.optString("id", null);
//                 if (posId != null) {
//                     System.out.printf("  [SWEEP] %s fallback SL=%.6f TP=%.6f%n", pair, sl, tp);
//                     setTpSlWithRetry(posId, tp, sl, pair);
//                     if (ti == null) {
//                         TrailInfo nt = new TrailInfo();
//                         nt.isLong = isLong; nt.entryPrice = avgPrice;
//                         nt.currentSL = sl; nt.currentTP = tp;
//                         nt.initialSL = sl; nt.initialTP = tp;
//                         nt.initialRisk = Math.abs(avgPrice - sl);
//                         nt.peakPrice = avgPrice; nt.stage = 0; nt.extensionsUsed = 0;
//                         trailState.put(pair, nt);
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

//     private static double[] calcEMASeries(double[] d, int period) {
//         double[] out = new double[d.length];
//         if (d.length < period) {
//             double last = d.length > 0 ? d[d.length - 1] : 0;
//             Arrays.fill(out, last);
//             return out;
//         }
//         double k = 2.0 / (period + 1);
//         double ema = 0;
//         for (int i = 0; i < period; i++) ema += d[i];
//         ema /= period;
//         for (int i = 0; i < period; i++) out[i] = ema;
//         out[period - 1] = ema;
//         for (int i = period; i < d.length; i++) {
//             ema = d[i] * k + ema * (1 - k);
//             out[i] = ema;
//         }
//         return out;
//     }

//     private static double calcEMA(double[] d, int period) {
//         if (d.length < period) return 0;
//         double k = 2.0 / (period + 1), ema = 0;
//         for (int i = 0; i < period; i++) ema += d[i];
//         ema /= period;
//         for (int i = period; i < d.length; i++) ema = d[i] * k + ema * (1 - k);
//         return ema;
//     }

//     private static double calcRSI(double[] closes, int period) {
//         if (closes.length < period + 1) return 50.0;

//         double avgGain = 0, avgLoss = 0;
//         for (int i = 1; i <= period; i++) {
//             double change = closes[i] - closes[i - 1];
//             if (change > 0) avgGain += change;
//             else avgLoss += -change;
//         }
//         avgGain /= period;
//         avgLoss /= period;

//         for (int i = period + 1; i < closes.length; i++) {
//             double change = closes[i] - closes[i - 1];
//             double gain = Math.max(change, 0);
//             double loss = Math.max(-change, 0);
//             avgGain = (avgGain * (period - 1) + gain) / period;
//             avgLoss = (avgLoss * (period - 1) + loss) / period;
//         }

//         if (avgLoss == 0) return 100.0;
//         double rs = avgGain / avgLoss;
//         return 100.0 - (100.0 / (1.0 + rs));
//     }

//     private static BigDecimal roundToTickBD(double price, double tick) {
//         if (tick <= 0) return BigDecimal.valueOf(price);
//         BigDecimal bdPrice = BigDecimal.valueOf(price);
//         BigDecimal bdTick  = BigDecimal.valueOf(tick);
//         BigDecimal multiples = bdPrice.divide(bdTick, 0, RoundingMode.HALF_UP);
//         BigDecimal result = multiples.multiply(bdTick);
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
//     private static double[] extractVolumes(JSONArray a) {
//         double[] o = new double[a.length()];
//         for (int i = 0; i < a.length(); i++) o[i] = a.getJSONObject(i).optDouble("volume", 0);
//         return o;
//     }

//     private static JSONArray getCandlestickData(String pair, String resolution, int count) {
//         try {
//             long minsPerBar;
//             switch (resolution) {
//                 case "1":   minsPerBar = 1;   break;
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
//             double volSum = 0;
//             for (int j = i; j < i + groupSize; j++) {
//                 JSONObject c = source.getJSONObject(j);
//                 high = Math.max(high, c.getDouble("high"));
//                 low  = Math.min(low,  c.getDouble("low"));
//                 volSum += c.optDouble("volume", 0);
//             }
//             JSONObject merged = new JSONObject();
//             merged.put("open", open);
//             merged.put("close", close);
//             merged.put("high", high);
//             merged.put("low", low);
//             merged.put("volume", volSum);
//             result.put(merged);
//         }
//         return result;
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
// import java.time.Instant;
// import java.util.*;
// import java.util.concurrent.ConcurrentHashMap;
// import java.util.concurrent.TimeUnit;
// import java.util.stream.Collectors;
// import java.util.stream.Stream;

// // =============================================================================
// // SWING-TRADING STRATEGY — 4H bias / 1H entry, fixed margin & leverage.
// // SL is TIGHT and clamped into a configurable [SL_MIN_PERCENT, SL_MAX_PERCENT]
// // range. TP starts as a very high RR target, but a staged ATR-based trailing
// // system (ratchet-only) protects profit and extends TP while the trend holds
// // — so most trades realistically exit via trailing long before the very-high
// // initial TP is ever reached. No early-exit system (none existed before,
// // none added now) — the only ways out are TP, SL, or the trailing stop.
// // =============================================================================
// public class CoinDCXFuturesTrader8C_BUY_SELL_NEW_LOGIC_THREE {

//     // =========================================================================
//     // API Configuration (unchanged)
//     // =========================================================================
//     private static final String API_KEY    = System.getenv("DELTA_API_KEY");
//     private static final String API_SECRET = System.getenv("DELTA_API_SECRET");
//     private static final String BASE_URL       = "https://api.coindcx.com";
//     private static final String PUBLIC_API_URL = "https://public.coindcx.com";

//     private static final int MAX_ENTRY_PRICE_CHECKS = 20;
//     private static final int ENTRY_CHECK_DELAY_MS    = 1000;

//     private static final int  TPSL_MAX_RETRIES    = 3;
//     private static final long TPSL_RETRY_DELAY_MS = 2000L;

//     private static final long TICK_CACHE_TTL_MS = 3_600_000L;

//     private static final int MAX_OPEN_POSITIONS = 120;

//     private static final int  POSITION_ID_MAX_RETRIES = 5;
//     private static final long POSITION_ID_RETRY_DELAY_MS = 1500L;

//     // =========================================================================
//     // Capital configuration
//     // =========================================================================
//     private static final double FIXED_MARGIN = 1000.0;
//     private static final int    LEVERAGE     = 15;

//     // =========================================================================
//     // Indicators — unchanged from before.
//     // =========================================================================
//     private static final int EMA_50  = 50;
//     private static final int EMA_200 = 200;

//     private static final int EMA_FAST = 9;
//     private static final int EMA_SLOW = 21;

//     private static final int ST_PERIOD     = 10;
//     private static final double ST_MULTIPLIER = 3.0;

//     private static final int ATR_PERIOD = 14;

//     private static final String RES_1H = "60";
//     private static final int GROUP_4H_FROM_1H = 4;
//     private static final int BASE_1H_FETCH_COUNT = 900;

//     // =========================================================================
//     // 1H entry (unchanged).
//     // =========================================================================
//     private static final int PULLBACK_LOOKBACK_BARS = 3;
//     private static final double MAX_EMA_EXTENSION_ATR = 1.0;

//     // =========================================================================
//     // NEW — tight SL, clamped into a configurable [MIN%, MAX%] range. The
//     // structural swing+ATR-buffer level still decides WHICH SIDE of price the
//     // SL sits on and gives a starting estimate, but the final SL distance is
//     // always forced into this range so you have direct, tunable control over
//     // how tight/wide it can ever be — regardless of how far the swing is.
//     // =========================================================================
//     private static final int    SWING_LOOKBACK = 10;
//     private static final double SL_ATR_BUFFER  = 0.15; // tighter buffer than before (was 0.25)
//     private static final double SL_MIN_PERCENT = 0.6;  // SL can never be tighter than this (tick-noise floor)
//     private static final double SL_MAX_PERCENT = 0.9;  // SL can never be wider than this — "very very small" SL, tune here

//     // =========================================================================
//     // NEW — TP starts as a very high RR target. In practice, most trades will
//     // exit via the trailing stop (below) long before price ever reaches this,
//     // so think of this as a ceiling / aspirational target for a runaway trend,
//     // not a realistic average outcome.
//     // =========================================================================
//     private static final double TARGET_RR = 3.0; // was 2.0 — tune this for how "high" you want the ceiling

//     // =========================================================================
//     // NEW — staged trailing system (reintroduced), driven by progress toward
//     // the ORIGINAL initial TP. Uses 1H ATR since this bot only ever looks at
//     // 1H/4H data. Ratchet-only: SL never moves backward.
//     // =========================================================================
//     private static final double TRAIL_STAGE1_TRIGGER = 0.20; // lock a small profit
//     private static final double BREAKEVEN_LOCK_PROFIT_PERCENT = 0.10; // %
//     private static final double TRAIL_STAGE2_TRIGGER = 0.40; // wide ATR trail activates
//     private static final double TRAIL_STAGE2_ATR = 2.5;
//     private static final double TRAIL_STAGE3_TRIGGER = 0.60; // tighter ATR trail
//     private static final double TRAIL_STAGE3_ATR = 1.5;
//     private static final double MIN_SL_IMPROVEMENT_ATR = 0.10; // don't spam the API on tiny moves

//     private static final double TP_EXTENSION_TRIGGER = 0.80; // past this, extend TP if the trend is still valid
//     private static final int    MAX_TP_EXTENSIONS = 3;
//     private static final double TP_EXTENSION_ATR = 2.0;

//     // =========================================================================
//     // Duplicate-position protection & cooldown (unchanged).
//     // =========================================================================
//     private static final long COOLDOWN_MS = 60L * 60 * 1000L; // 60 minutes
//     private static final long SCAN_INTERVAL_MS = 5L * 60 * 1000L;

//     private static final double LIMIT_ORDER_BUFFER_PCT = 0.0005;

//     // =========================================================================
//     // Trailing state — replaces the old "fixed forever" TradeInfo. Holds the
//     // ORIGINAL entry/SL/TP (used to compute progress %) plus the CURRENT
//     // (possibly trailed/extended) SL/TP that's actually live on the exchange.
//     // =========================================================================
//     private static class TrailInfo {
//         boolean isLong;
//         double entryPrice;
//         double initialSL, initialTP, initialRisk;
//         double currentSL, currentTP;
//         double peakPrice;
//         int    stage;           // 0..3
//         int    extensionsUsed;
//     }
//     private static final Map<String, TrailInfo> trailState = new ConcurrentHashMap<>();

//     private static final Map<String, JSONObject> instrumentCache = new ConcurrentHashMap<>();
//     private static long lastCacheUpdate = 0;
//     private static final Map<String, Long> lastTradeTime = new ConcurrentHashMap<>();

//     private static final String[] COIN_SYMBOLS = {
//        "PIEVERSE","XAU","APE","ERA","US","RAVE","EDEN","LIT","BREV","MAGMA","BLESS","ZAMA",
//         "FRAX","ACU","1000FLOKI","ELSA","LINEA","SPACE","CLO","FIGHT","UMA","MEGA","MAV","TRIA",
//         "YGG","OPN","ROBO","SUI","GLM","MANTRA","SEI","CAKE","AUCTION","SENT","BSB","BASED","IRYS",
//         "ACE","WET","CL","PRL","GENIUS","WIF","MANTA","LSK","AIGENSYN","PHAROS","JUP","AXL","BOME",
//         "SLX","ZEST","AIOT","VVV","CAP","DATAIP","GRVT","TAO","BR","TURBO","BTC","ETH","ZK","LISTA",
//         "A","LTC","XAG","COAI","HANA","ZRO","SKYAI","COPPER","RARE","ETC","M","AKE","XLM","PIXEL",
//         "XAN","ADA","CROSS","XMR","G","DASH","ZEC","ATOM","TRUTH","BCH","NEO","IOST","FLUX","ALGO",
//         "ZRX","COMP","WLFI","POL","DOGE","BAND","OPG","FIDA","PROM","SANTOS","RLC","1000000MOG",
//         "GRASS","PNUT","TRB","KAIA","ARX","XAI","S","4","COTI","CHR","SOLV","SAGA","ORCA","1000LUNC",
//         "MOVE","VIRTUAL","ME","IOTX","GIGGLE","AVA","VELODROME","AIXBT","KMNO","LA","DEXE","ZBT",
//         "GRIFFAIN","BLUAI","CTSI","ROSE","TURTLE","IMX","SUN","APR","TA","ON","BIO","COOKIE",
//         "AVAAI","DOT","TRUMP","MELANIA","GMT","FLOCK","CLANKER","CYS","SUSHI","VTHO","DIA",
//         "SLP","GOAT","BMT","KGEN","GWEI","MUBARAK","LDO","ESP","DRIFT","FORM","PLUME","NIL",
//         "UNI","ZORA","RECALL","INIT","BZ","PARTI","NATGAS","SPX","BANK","AVAX","RIVER","BILL",
//         "ATH","XRP","KERNEL","JST","PUNDIX","HAEDAL","ALPINE","SOON","SOPH","HUMA","TRX","LINK",
//         "HYPE","HIVE","TAIKO","TAG","MYX","NEWT","AIN","USUAL","PUMP","ICNT","BNB","H","BAT",
//         "QTUM","ARC","AIO","BEAT","BTR","ALCH","THETA","VELVET","ARIA","PTB","UB","LIGHT","FF",
//         "EVAAI","GMX","LYN","TAC","LAB","ENJ","AT","MMT","UAI","AAVE","JCT","KSM","HEI","JASMY",
//         "NEAR","TST","SOL","OP","PLAY","INJ","STG","HOLO","ASR","B","LUNA2","RSR","INX","KAT",
//         "ICP","QNT","MAGIC","T","MINA","STX","ACH","LQTY","ID","GRT","NEIRO","XVS","1INCH","SAND",
//         "ANKR","RVN","SFP","KAVA","MANA","HBAR","ARB","MTL","C98","TUT","SIREN","MASK","1000XEC",
//         "AR","ARPA","FIL","LPT","ENS","PEOPLE","LUMIA","DUSK","FLOW","XVG","ARKM","POPCAT","ARK",
//         "MOODENG","SAFE","AXS","BICO","BIGTIME","WAXP","GAS","POWR","TIA","CHIP","STO","ORDI",
//         "BEAMX","1000BONK","PYTH","ETHW","1000RATS","ANIME","OPEN","DYM","BERA","PORTAL","BB",
//         "BANANAS31","CFX","SSV","TNSR","EDU","JELLYJELLY","BLUR","WAL","FHE","WCT","DEEP","SXT",
//         "NAORIS","OG","CVC","AWE","O","BEL","JOE","SQD","1000PEPE","CARV","FET","SAPIEN","MEME",
//         "AVNT","XPIN","ILV","KAS","BNT","STBL","BSV","RIF","SUPER","USTC","METIS","ETHFI","ENA",
//         "1MBABYDOGE","CATI","HMSTR","GPS","SHELL","KAITO","ACT","RPL","BAN","THE","AKT","MORPHO",
//         "CHILLGUY","AERO","MOCA","PENGU","PHA","RED","EPIC","TREE","1000CAT","MAVIA","FARTCOIN",
//         "PAXG","IN","ORDER","VET","ZEN","STABLE","CHZ","NIGHT","NOM","ZKP","SKR","GRAM","BIRB",
//         "CTR","KNC","ZIL","YFI","EGLD","RUNE","ASTR","ONE","1000SHIB","API3","SPELL","WOO","APT",
//         "PENDLE","AGLD","CYBER","CKB","ONG","MOVR","POLYX","TWT","STEEM","ALT","ZETA","REZ","RENDER",
//         "RONIN","STRK","W","SCR","CETUS","IO","MEW","SWARMS","SONIC","PIPPIN","PROMPT","MERL","F",
//         "ESPORTS","PROVE","XNY","USELESS","HEMI","Q","SKY","ZKC","FLUID","MITO","CFG","EDGE","RE",
//         "YB","MET","DOS","FOGO","BTW","ALLO","BROCCOLI714","HYPER","XPL","RESOLV","ASTER","KITE",
//         "SIGN","HOME","MON","CC","SAHARA","MIRA","EUL","TOWNS","SYRUP","C","DOLO","ALICE","BABY",
//         "SOMI","NOT","BARD","SPK","POWER","2Z","BANANA","ENSO","SYN","NXPC","GUN","XTZ","ONT","SKL",
//         "HOT","JTO","DOGS","EIGEN","GTC","GALA","NMR","CGPT","ZEREBRO","VANA","OGN","CELO","USDC",
//         "COW","0G","IOTA","SNX","DYDX","WLD","1000SATS","ONDO","AEVO","BRETT","LAYER","CRV","TLM","KOMA"
//     };

//     private static final Set<String> INTEGER_QTY_PAIRS = Stream.of(COIN_SYMBOLS)
//             .flatMap(s -> Stream.of("B-" + s + "_USDT", s + "_USDT"))
//             .collect(Collectors.toCollection(HashSet::new));

//     private static final String[] COINS_TO_TRADE = Stream.of(COIN_SYMBOLS)
//             .map(s -> "B-" + s + "_USDT")
//             .toArray(String[]::new);

//     // =========================================================================
//     // 4H major trend/bias (unchanged).
//     // =========================================================================
//     private static class Bias4H {
//         boolean valid;
//         boolean bullish, bearish;
//         double ema50, ema200;
//         boolean stGreen;
//     }

//     private static Bias4H analyze4HBias(JSONArray candles4h) {
//         Bias4H r = new Bias4H();
//         if (candles4h == null || candles4h.length() < EMA_200 + ST_PERIOD + 5) {
//             r.valid = false;
//             return r;
//         }
//         double[] cl = extractCloses(candles4h);
//         double[] hi = extractHighs(candles4h);
//         double[] lo = extractLows(candles4h);

//         r.ema50 = calcEMA(cl, EMA_50);
//         r.ema200 = calcEMA(cl, EMA_200);
//         boolean[] st = calcSupertrend(hi, lo, cl, ST_PERIOD, ST_MULTIPLIER);
//         r.stGreen = st[st.length - 1];

//         r.valid = true;
//         r.bullish = r.ema50 > r.ema200 && r.stGreen;
//         r.bearish = r.ema50 < r.ema200 && !r.stGreen;
//         return r;
//     }

//     // =========================================================================
//     // 1H entry (unchanged) — trend conditions + pullback/reclaim + extension.
//     // =========================================================================
//     private static class Entry1H {
//         boolean valid;
//         boolean triggered;
//         double close, ema9, ema21, atr;
//         double swingLevel;
//         String reason;
//     }

//     private static Entry1H analyze1HEntry(JSONArray candles1h, boolean trendUp) {
//         Entry1H t = new Entry1H();
//         int minBars = EMA_SLOW + Math.max(ATR_PERIOD, SWING_LOOKBACK) + PULLBACK_LOOKBACK_BARS + 5;
//         if (candles1h == null || candles1h.length() < minBars) {
//             t.valid = false;
//             return t;
//         }

//         double[] cl = extractCloses(candles1h);
//         double[] op = extractOpens(candles1h);
//         double[] hi = extractHighs(candles1h);
//         double[] lo = extractLows(candles1h);
//         int n = cl.length;

//         double ema9  = calcEMA(cl, EMA_FAST);
//         double ema21 = calcEMA(cl, EMA_SLOW);
//         double atr   = calcATR(hi, lo, cl, ATR_PERIOD);
//         boolean[] st = calcSupertrend(hi, lo, cl, ST_PERIOD, ST_MULTIPLIER);
//         boolean stGreen = st[st.length - 1];

//         double close = cl[n - 1];
//         double open  = op[n - 1];

//         t.close = close; t.ema9 = ema9; t.ema21 = ema21; t.atr = atr;

//         boolean trendCondOk = trendUp
//                 ? (ema9 > ema21 && close > ema9 && close > ema21 && stGreen)
//                 : (ema9 < ema21 && close < ema9 && close < ema21 && !stGreen);

//         boolean touchedEma9 = false;
//         int pullbackStart = Math.max(0, n - 1 - PULLBACK_LOOKBACK_BARS);
//         for (int i = pullbackStart; i <= n - 1; i++) {
//             if (trendUp ? lo[i] <= ema9 : hi[i] >= ema9) { touchedEma9 = true; break; }
//         }

//         boolean directionalCandle = trendUp ? (close > open) : (close < open);

//         double distanceAtr = atr > 0 ? Math.abs(close - ema9) / atr : 0;
//         boolean extensionOk = distanceAtr <= MAX_EMA_EXTENSION_ATR;

//         t.swingLevel = trendUp ? recentLow(lo, SWING_LOOKBACK) : recentHigh(hi, SWING_LOOKBACK);
//         t.triggered = trendCondOk && touchedEma9 && directionalCandle && extensionOk;
//         t.valid = true;
//         t.reason = String.format(
//                 "trendCond=%s pullbackTouch=%s directional=%s extension=%.2fATR(ok=%s)",
//                 trendCondOk, touchedEma9, directionalCandle, distanceAtr, extensionOk);
//         return t;
//     }

//     private static double recentLow(double[] lo, int lookback) {
//         int n = lo.length;
//         int start = Math.max(0, n - lookback);
//         double min = Double.POSITIVE_INFINITY;
//         for (int i = start; i < n; i++) min = Math.min(min, lo[i]);
//         return min;
//     }

//     private static double recentHigh(double[] hi, int lookback) {
//         int n = hi.length;
//         int start = Math.max(0, n - lookback);
//         double max = Double.NEGATIVE_INFINITY;
//         for (int i = start; i < n; i++) max = Math.max(max, hi[i]);
//         return max;
//     }

//     private static JSONArray dropLastIfForming(JSONArray arr) {
//         if (arr == null || arr.length() < 2) return arr;
//         JSONArray out = new JSONArray();
//         for (int i = 0; i < arr.length() - 1; i++) out.put(arr.getJSONObject(i));
//         return out;
//     }

//     // =========================================================================
//     // NEW — structural direction from swing+ATR-buffer, but the FINAL SL
//     // distance is always clamped into [SL_MIN_PERCENT, SL_MAX_PERCENT]. TP is
//     // the very-high RR target off that clamped SL. Never rejects the trade
//     // for SL width — it clamps instead, since "keep SL very small" is now the
//     // explicit priority (this deliberately trades off against the earlier
//     // "never artificially tighten SL" principle used in previous versions).
//     // =========================================================================
//     private static double[] computeClampedSlTp(boolean isLong, double entryPrice, double swingLevel, double atr, double tickSize) {
//         double sl;
//         if (atr > 0) {
//             sl = isLong ? swingLevel - SL_ATR_BUFFER * atr : swingLevel + SL_ATR_BUFFER * atr;
//         } else {
//             sl = isLong ? entryPrice * (1 - SL_MAX_PERCENT / 100.0) : entryPrice * (1 + SL_MAX_PERCENT / 100.0);
//         }

//         boolean slSideValid = isLong ? sl < entryPrice : sl > entryPrice;
//         if (!slSideValid) {
//             sl = isLong ? entryPrice * (1 - SL_MAX_PERCENT / 100.0) : entryPrice * (1 + SL_MAX_PERCENT / 100.0);
//         }

//         double slPercent = Math.abs(entryPrice - sl) / entryPrice * 100.0;
//         if (slPercent < SL_MIN_PERCENT) {
//             sl = isLong ? entryPrice * (1 - SL_MIN_PERCENT / 100.0) : entryPrice * (1 + SL_MIN_PERCENT / 100.0);
//         } else if (slPercent > SL_MAX_PERCENT) {
//             sl = isLong ? entryPrice * (1 - SL_MAX_PERCENT / 100.0) : entryPrice * (1 + SL_MAX_PERCENT / 100.0);
//         }

//         double risk = Math.abs(entryPrice - sl);
//         double tp = isLong ? entryPrice + risk * TARGET_RR : entryPrice - risk * TARGET_RR;

//         sl = roundToTick(sl, tickSize);
//         tp = roundToTick(tp, tickSize);
//         double finalSlPercent = Math.abs(entryPrice - sl) / entryPrice * 100.0;
//         return new double[]{sl, tp, finalSlPercent};
//     }

//     private static double calcFixedQuantity(double entryPrice, String pair) {
//         double usdtInrRate = 98.0;
//         double qty = FIXED_MARGIN / (entryPrice * usdtInrRate);
//         double finalQty = INTEGER_QTY_PAIRS.contains(pair) ? Math.floor(qty) : Math.floor(qty * 100) / 100.0;
//         return Math.max(finalQty, 0);
//     }

//     public static void main(String[] args) {
//         System.out.println("=== Bot starting (4H/1H swing entry, tight clamped SL, very-high RR ceiling, staged ATR trailing, no early exit) ===");
//         initInstrumentCache();

//         while (true) {
//             try {
//                 runEntryScan();
//             } catch (Throwable t) {
//                 System.err.println("[MAIN-LOOP] Uncaught error, continuing: " + t.getMessage());
//                 t.printStackTrace();
//             }
//             try {
//                 TimeUnit.MILLISECONDS.sleep(SCAN_INTERVAL_MS);
//             } catch (InterruptedException ignored) {
//                 Thread.currentThread().interrupt();
//                 break;
//             }
//         }
//     }

//     private static void runEntryScan() {
//         Set<String> active = getActivePositions();
//         System.out.println("Active positions: " + active);
//         trailState.keySet().removeIf(p -> !active.contains(p));

//         if (active.size() >= MAX_OPEN_POSITIONS) {
//             System.out.println("MAX_OPEN_POSITIONS reached — skipping scan.");
//             updateTrailing();
//             ensureTpSlForOpenPositions();
//             return;
//         }

//         for (String pair : COINS_TO_TRADE) {
//             try {
//                 if (active.size() >= MAX_OPEN_POSITIONS) break;

//                 if (active.contains(pair)) continue; // SKIP - Existing position

//                 long lastTrade = lastTradeTime.getOrDefault(pair, 0L);
//                 if (System.currentTimeMillis() - lastTrade < COOLDOWN_MS) continue; // SKIP - Cooldown active

//                 JSONArray raw1h = dropLastIfForming(getCandlestickData(pair, RES_1H, BASE_1H_FETCH_COUNT));
//                 if (raw1h == null || raw1h.length() < EMA_SLOW + ATR_PERIOD + SWING_LOOKBACK + 10) continue;

//                 JSONArray raw4h = aggregateCandles(raw1h, GROUP_4H_FROM_1H);
//                 Bias4H bias4h = analyze4HBias(raw4h);
//                 if (!bias4h.valid) continue;

//                 boolean trendUp;
//                 if (bias4h.bullish) trendUp = true;
//                 else if (bias4h.bearish) trendUp = false;
//                 else {
//                     System.out.println("SKIP - " + pair + " - 4H trend not bullish/bearish");
//                     continue;
//                 }

//                 Entry1H entry1h = analyze1HEntry(raw1h, trendUp);
//                 if (!entry1h.valid) continue;
//                 if (!entry1h.triggered) {
//                     System.out.println("SKIP - " + pair + " - 1H entry conditions not met | " + entry1h.reason);
//                     continue;
//                 }

//                 double currentPrice = getLastPrice(pair);
//                 if (currentPrice <= 0) continue;
//                 double tickSize = getTickSize(pair);

//                 double qty = calcFixedQuantity(currentPrice, pair);
//                 if (qty <= 0) {
//                     System.out.println("SKIP - " + pair + " - Invalid quantity");
//                     continue;
//                 }

//                 System.out.println("\n==== " + pair + " — " + (trendUp ? "LONG" : "SHORT") + " (4H/1H swing setup) ====");
//                 System.out.printf("  4H: EMA50=%.6f EMA200=%.6f Supertrend=%s Bias=%s%n",
//                         bias4h.ema50, bias4h.ema200, bias4h.stGreen ? "GREEN" : "RED", trendUp ? "BULLISH" : "BEARISH");
//                 System.out.printf("  1H: Close=%.6f EMA9=%.6f EMA21=%.6f ATR=%.6f | %s%n",
//                         entry1h.close, entry1h.ema9, entry1h.ema21, entry1h.atr, entry1h.reason);

//                 String side = trendUp ? "buy" : "sell";
//                 JSONObject orderResp = placeFuturesOrder(side, pair, qty, LEVERAGE,
//                         "email_notification", "isolated", "INR", currentPrice);
//                 if (orderResp == null || !orderResp.has("id")) {
//                     System.out.println("  Order failed: " + orderResp);
//                     continue;
//                 }

//                 System.out.println("  Order placed! id=" + orderResp.getString("id"));
//                 lastTradeTime.put(pair, System.currentTimeMillis());

//                 double entry = getEntryPrice(pair, orderResp.getString("id"));
//                 if (entry <= 0) {
//                     System.out.println("  Could not confirm entry within window — TP/SL handled by safety sweep");
//                     active.add(pair);
//                     continue;
//                 }
//                 System.out.printf("  Entry confirmed: %.6f%n", entry);

//                 double[] slTp = computeClampedSlTp(trendUp, entry, entry1h.swingLevel, entry1h.atr, tickSize);
//                 double slPrice = slTp[0], tpPrice = slTp[1], slPercent = slTp[2];

//                 double risk = Math.abs(entry - slPrice);
//                 System.out.println("[ENTRY] " + pair);
//                 System.out.println("  Side=" + (trendUp ? "LONG" : "SHORT"));
//                 System.out.printf("  Entry=%.6f SL=%.6f TP=%.6f%n", entry, slPrice, tpPrice);
//                 System.out.printf("  Risk=%.6f RR=%.2f SL%%=%.2f (clamped to [%.2f%%, %.2f%%])%n",
//                         risk, TARGET_RR, slPercent, SL_MIN_PERCENT, SL_MAX_PERCENT);
//                 System.out.printf("  Fixed Margin=%.2f Leverage=%dx Quantity=%.4f%n", FIXED_MARGIN, LEVERAGE, qty);

//                 String posId = getPositionId(pair);
//                 if (posId != null) {
//                     setTpSlWithRetry(posId, tpPrice, slPrice, pair);
//                 } else {
//                     System.out.println("  Position ID not found after retries — safety sweep will handle it");
//                 }

//                 TrailInfo ti = new TrailInfo();
//                 ti.isLong = trendUp;
//                 ti.entryPrice = entry;
//                 ti.initialSL = slPrice;
//                 ti.initialTP = tpPrice;
//                 ti.initialRisk = risk;
//                 ti.currentSL = slPrice;
//                 ti.currentTP = tpPrice;
//                 ti.peakPrice = entry;
//                 ti.stage = 0;
//                 ti.extensionsUsed = 0;
//                 trailState.put(pair, ti);

//                 active.add(pair);

//             } catch (Exception e) {
//                 System.err.println("Error on " + pair + ": " + e.getMessage());
//             }
//         }

//         System.out.println("\n=== Scan complete ===");
//         updateTrailing();
//         ensureTpSlForOpenPositions();
//     }

//     // =========================================================================
//     // NEW — staged trailing system (reintroduced). Runs every cycle for every
//     // open position. Progress is measured against the ORIGINAL (very-high)
//     // initial TP, so even reaching "100%" is rare — most trades exit earlier
//     // via the ratchet-only trailing stop as the trend runs out of steam.
//     //   STAGE 0: initial SL/TP, no trailing yet.
//     //   STAGE 1 (>=20% progress): small locked profit.
//     //   STAGE 2 (>=40% progress): wide ATR trail (2.5x 1H ATR).
//     //   STAGE 3 (>=60% progress): tighter ATR trail (1.5x 1H ATR).
//     //   TP extension (>=80% progress, trend still valid): push TP further.
//     // =========================================================================
//     private static void updateTrailing() {
//         Set<String> stillOpen = getActivePositions();
//         for (String pair : stillOpen) {
//             try {
//                 JSONObject pos = findPosition(pair);
//                 if (pos == null) continue;

//                 TrailInfo ti = trailState.get(pair);
//                 if (ti == null) {
//                     double avgPrice = pos.optDouble("avg_price", 0);
//                     double tpTrig = pos.optDouble("take_profit_trigger", 0);
//                     double slTrig = pos.optDouble("stop_loss_trigger", 0);
//                     if (avgPrice <= 0) continue;
//                     boolean isLong = pos.optDouble("active_pos", 0) >= 0;
//                     ti = new TrailInfo();
//                     ti.isLong = isLong;
//                     ti.entryPrice = avgPrice;
//                     ti.currentSL = slTrig;
//                     ti.currentTP = tpTrig;
//                     ti.initialSL = slTrig;
//                     ti.initialTP = tpTrig;
//                     ti.initialRisk = slTrig > 0 ? Math.abs(avgPrice - slTrig) : 0;
//                     ti.peakPrice = avgPrice;
//                     ti.stage = 0;
//                     ti.extensionsUsed = 0;
//                     trailState.put(pair, ti);
//                     if (ti.currentSL <= 0 || ti.currentTP <= 0) continue;
//                 }

//                 if (ti.initialTP <= 0 || ti.initialRisk <= 0) continue;

//                 double currentPrice = getLastPrice(pair);
//                 if (currentPrice <= 0) continue;
//                 ti.peakPrice = ti.isLong ? Math.max(ti.peakPrice, currentPrice) : Math.min(ti.peakPrice, currentPrice);

//                 double tpDistance = ti.isLong ? ti.initialTP - ti.entryPrice : ti.entryPrice - ti.initialTP;
//                 if (tpDistance <= 0) continue;
//                 double progress = (ti.isLong ? (currentPrice - ti.entryPrice) : (ti.entryPrice - currentPrice)) / tpDistance;

//                 int newStage = progress >= TRAIL_STAGE3_TRIGGER ? 3 : progress >= TRAIL_STAGE2_TRIGGER ? 2 : progress >= TRAIL_STAGE1_TRIGGER ? 1 : 0;
//                 if (newStage > ti.stage) ti.stage = newStage;

//                 JSONArray raw1h = dropLastIfForming(getCandlestickData(pair, RES_1H, BASE_1H_FETCH_COUNT));
//                 double atr1h = 0;
//                 if (raw1h != null && raw1h.length() >= ATR_PERIOD + 5) {
//                     atr1h = calcATR(extractHighs(raw1h), extractLows(raw1h), extractCloses(raw1h), ATR_PERIOD);
//                 }

//                 double tick = getTickSize(pair);
//                 double candidateSL = ti.currentSL;
//                 boolean changed = false;

//                 if (ti.stage >= 1) {
//                     double lockSL = ti.isLong
//                             ? ti.entryPrice * (1 + BREAKEVEN_LOCK_PROFIT_PERCENT / 100.0)
//                             : ti.entryPrice * (1 - BREAKEVEN_LOCK_PROFIT_PERCENT / 100.0);
//                     if (ti.isLong ? lockSL > candidateSL : lockSL < candidateSL) candidateSL = lockSL;
//                 }
//                 double trailMult = ti.stage >= 3 ? TRAIL_STAGE3_ATR : ti.stage == 2 ? TRAIL_STAGE2_ATR : 0;
//                 if (trailMult > 0 && atr1h > 0) {
//                     double atrSL = ti.isLong ? currentPrice - trailMult * atr1h : currentPrice + trailMult * atr1h;
//                     if (ti.isLong ? atrSL > candidateSL : atrSL < candidateSL) candidateSL = atrSL;
//                 }

//                 double minImprovement = Math.max(MIN_SL_IMPROVEMENT_ATR * atr1h, tick);
//                 boolean meaningfulImprovement = ti.isLong
//                         ? (candidateSL - ti.currentSL) >= minImprovement
//                         : (ti.currentSL - candidateSL) >= minImprovement;

//                 if (meaningfulImprovement) {
//                     candidateSL = roundToTick(candidateSL, tick);
//                     if (ti.isLong ? candidateSL > ti.currentSL : candidateSL < ti.currentSL) {
//                         System.out.printf("  [TRAIL] %s %s progress=%.0f%% SL moved %.6f -> %.6f%n",
//                                 pair, ti.isLong ? "LONG" : "SHORT", progress * 100, ti.currentSL, candidateSL);
//                         ti.currentSL = candidateSL;
//                         changed = true;
//                     } else {
//                         System.out.println("  [TRAIL] " + pair + " candidate SL would worsen existing SL — ignoring update");
//                     }
//                 }

//                 if (progress >= TP_EXTENSION_TRIGGER && ti.extensionsUsed < MAX_TP_EXTENSIONS && atr1h > 0) {
//                     Bias4H bias4h = analyze4HBias(aggregateCandles(raw1h, GROUP_4H_FROM_1H));
//                     boolean trendValid = bias4h.valid && (ti.isLong ? bias4h.bullish : bias4h.bearish);
//                     if (trendValid) {
//                         double newTP = ti.isLong ? currentPrice + atr1h * TP_EXTENSION_ATR : currentPrice - atr1h * TP_EXTENSION_ATR;
//                         newTP = roundToTick(newTP, tick);
//                         if (ti.isLong ? newTP > ti.currentTP : newTP < ti.currentTP) {
//                             System.out.println("  [TP EXTENSION] " + pair + " " + (ti.isLong ? "LONG" : "SHORT")
//                                     + " TP " + ti.currentTP + " -> " + newTP
//                                     + " Extension " + (ti.extensionsUsed + 1) + "/" + MAX_TP_EXTENSIONS);
//                             ti.currentTP = newTP;
//                             ti.extensionsUsed++;
//                             changed = true;
//                         }
//                     }
//                 }

//                 if (changed) {
//                     String posId = pos.optString("id", null);
//                     if (posId != null) setTpSlWithRetry(posId, ti.currentTP, ti.currentSL, pair);
//                 }
//             } catch (Exception e) {
//                 System.err.println("updateTrailing(" + pair + "): " + e.getMessage());
//             }
//         }
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

//                 System.out.println("  [SWEEP] " + pair + " missing TP and/or SL — computing fallback protection...");
//                 boolean isLong = pos.optDouble("active_pos", 0) >= 0;
//                 double tick = getTickSize(pair);
//                 TrailInfo ti = trailState.get(pair);

//                 double sl, tp;
//                 if (ti != null && ti.currentSL > 0 && ti.currentTP > 0) {
//                     sl = ti.currentSL; tp = ti.currentTP;
//                 } else {
//                     JSONArray raw1h = dropLastIfForming(getCandlestickData(pair, RES_1H, BASE_1H_FETCH_COUNT));
//                     double[] fallback = null;
//                     if (raw1h != null && raw1h.length() >= EMA_SLOW + ATR_PERIOD + SWING_LOOKBACK + 10) {
//                         double[] hi = extractHighs(raw1h), lo = extractLows(raw1h), cl = extractCloses(raw1h);
//                         double atr = calcATR(hi, lo, cl, ATR_PERIOD);
//                         double swingLevel = isLong ? recentLow(lo, SWING_LOOKBACK) : recentHigh(hi, SWING_LOOKBACK);
//                         fallback = computeClampedSlTp(isLong, avgPrice, swingLevel, atr, tick);
//                     }
//                     if (fallback == null) {
//                         sl = isLong ? avgPrice * (1 - SL_MAX_PERCENT / 100.0) : avgPrice * (1 + SL_MAX_PERCENT / 100.0);
//                         tp = isLong ? avgPrice + TARGET_RR * (avgPrice - sl) : avgPrice - TARGET_RR * (sl - avgPrice);
//                         System.out.println("  [SWEEP] structural SL unavailable for " + pair + " — using SL_MAX_PERCENT fallback cap");
//                     } else {
//                         sl = fallback[0]; tp = fallback[1];
//                     }
//                 }

//                 if (slTrig > 0) sl = slTrig;
//                 if (tpTrig > 0) tp = tpTrig;

//                 String posId = pos.optString("id", null);
//                 if (posId != null) {
//                     System.out.printf("  [SWEEP] %s SL=%.6f TP=%.6f%n", pair, sl, tp);
//                     setTpSlWithRetry(posId, tp, sl, pair);
//                     if (ti == null) {
//                         TrailInfo nt = new TrailInfo();
//                         nt.isLong = isLong; nt.entryPrice = avgPrice;
//                         nt.currentSL = sl; nt.currentTP = tp;
//                         nt.initialSL = sl; nt.initialTP = tp;
//                         nt.initialRisk = Math.abs(avgPrice - sl);
//                         nt.peakPrice = avgPrice; nt.stage = 0; nt.extensionsUsed = 0;
//                         trailState.put(pair, nt);
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

//     private static BigDecimal roundToTickBD(double price, double tick) {
//         if (tick <= 0) return BigDecimal.valueOf(price);
//         BigDecimal bdPrice = BigDecimal.valueOf(price);
//         BigDecimal bdTick  = BigDecimal.valueOf(tick);
//         BigDecimal multiples = bdPrice.divide(bdTick, 0, RoundingMode.HALF_UP);
//         BigDecimal result = multiples.multiply(bdTick);
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
//                 case "1":   minsPerBar = 1;   break;
//                 case "5":   minsPerBar = 5;   break;
//                 case "15":  minsPerBar = 15;  break;
//                 case "30":  minsPerBar = 30;  break;
//                 case "60":  minsPerBar = 60;  break;
//                 case "120": minsPerBar = 120; break;
//                 case "240": minsPerBar = 240; break;
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
//             double volSum = 0;
//             for (int j = i; j < i + groupSize; j++) {
//                 JSONObject c = source.getJSONObject(j);
//                 high = Math.max(high, c.getDouble("high"));
//                 low  = Math.min(low,  c.getDouble("low"));
//                 volSum += c.optDouble("volume", 0);
//             }
//             JSONObject merged = new JSONObject();
//             merged.put("open", open);
//             merged.put("close", close);
//             merged.put("high", high);
//             merged.put("low", low);
//             merged.put("volume", volSum);
//             result.put(merged);
//         }
//         return result;
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
// import java.time.Instant;
// import java.util.*;
// import java.util.concurrent.ConcurrentHashMap;
// import java.util.concurrent.TimeUnit;
// import java.util.stream.Collectors;
// import java.util.stream.Stream;

// public class CoinDCXFuturesTrader8C_BUY_SELL_NEW_LOGIC_THREE {

//     // =========================================================================
//     // API Configuration (unchanged)
//     // =========================================================================
//     private static final String API_KEY    = System.getenv("DELTA_API_KEY");
//     private static final String API_SECRET = System.getenv("DELTA_API_SECRET");
//     private static final String BASE_URL       = "https://api.coindcx.com";
//     private static final String PUBLIC_API_URL = "https://public.coindcx.com";

//     private static final int LEVERAGE = 3;

//     private static final int MAX_ENTRY_PRICE_CHECKS = 20;
//     private static final int ENTRY_CHECK_DELAY_MS    = 1000;

//     private static final int  TPSL_MAX_RETRIES    = 3;
//     private static final long TPSL_RETRY_DELAY_MS = 2000L;

//     private static final long TICK_CACHE_TTL_MS = 3_600_000L;

//     private static final int MAX_OPEN_POSITIONS = 120;

//     private static final int  POSITION_ID_MAX_RETRIES = 5;
//     private static final long POSITION_ID_RETRY_DELAY_MS = 1500L;

//     // =========================================================================
//     // Indicator periods
//     // =========================================================================
//     private static final int EMA_FAST = 9;
//     private static final int EMA_MID  = 21;
//     private static final int ATR_PERIOD = 14;
//     private static final int ST_PERIOD     = 10;
//     private static final double ST_MULTIPLIER = 3.0;
//     private static final int RSI_PERIOD = 14;
//     private static final int VOLUME_MA_PERIOD = 20;
//     private static final int VWAP_LOOKBACK = 20;

//     private static final String RES_5M = "5";
//     private static final String RES_1H = "60";

//     // Generous fetch counts so every analysis function (which needs
//     // EMA_MID + ST/ATR_PERIOD + STRUCTURE_SWING_LOOKBACK bars) always has
//     // enough data after dropLastIfForming() trims a candle and after
//     // 30M/15M aggregation divides the 5M series.
//     private static final int BASE_5M_FETCH_COUNT = 450;
//     private static final int GROUP_15M_FROM_5M = 3;
//     private static final int GROUP_30M_FROM_5M = 6;
//     private static final int BASE_1H_FETCH_COUNT = 90;

//     private static final int EMA_SLOPE_LOOKBACK_BARS   = 5;
//     private static final double HTF_EMA_SLOPE_MIN_ATR   = 0.10; // 1H/30M/15M slope threshold
//     private static final double ENTRY_EMA_SLOPE_MIN_ATR = 0.15; // 5M slope threshold

//     private static final int STRUCTURE_SWING_LOOKBACK = 30; // HH/HL detection on 1H/30M
//     private static final int SL_SWING_LOOKBACK         = 20; // "last valid pullback swing" on 5M
//     private static final int TP_LEVEL_LOOKBACK         = 40; // nearby resistance/support scan on 15M

//     // =========================================================================
//     // SECTION 3/4 — 1H macro direction / 30M confirmation, scored out of 6.
//     // =========================================================================
//     private static final int MACRO_1H_MIN_SCORE     = 4; // out of 6
//     private static final int CONFIRM_30M_STRONG_MIN = 5; // 5 or 6 -> strong
//     private static final int CONFIRM_30M_ACCEPTABLE = 4; // acceptable ONLY if 15M+5M are clean
//     private static final int CLEAN_15M_MIN_FOR_ACCEPTABLE = 4; // out of 5
//     private static final int CLEAN_5M_MIN_FOR_ACCEPTABLE  = 4; // out of 5

//     // =========================================================================
//     // SECTION 5 — 15M setup, scored out of 5 (NOT a hard AND — "prefer", not
//     // "require every condition perfect").
//     // =========================================================================
//     private static final int SETUP_15M_MIN_SCORE = 3; // out of 5

//     // =========================================================================
//     // SECTION 6/7 — 5M pullback + rejection (mandatory) + slope (mandatory).
//     // =========================================================================
//     private static final double PULLBACK_MAX_ATR      = 1.5;  // beyond this: no real pullback, SKIP
//     private static final double OVEREXTENSION_SKIP_ATR = 2.0; // cancel any pending signal beyond this
//     private static final double CANDLE_BODY_RATIO_MIN = 0.40;

//     // =========================================================================
//     // SECTION 8/9 — 5M supporting confirmation score out of 5 (min 3/5).
//     // =========================================================================
//     private static final int    ENTRY_CONFIRMATION_MIN_SCORE = 3; // out of 5
//     private static final double RSI_LONG_MIN  = 40, RSI_LONG_MAX  = 70;
//     private static final double RSI_SHORT_MIN = 30, RSI_SHORT_MAX = 60;

//     // =========================================================================
//     // SECTION 10/14 — pending breakout-entry signal.
//     // =========================================================================
//     private static final long SIGNAL_MAX_VALID_MS = 3L * 5 * 60 * 1000L; // ~3 x 5M candles

//     // =========================================================================
//     // SECTION 18-21 — structural SL off the LAST VALID PULLBACK SWING (not the
//     // farthest of swing-low/Supertrend, and not the absolute lowest of a big
//     // lookback window).
//     // =========================================================================
//     private static final double SL_BUFFER_ATR          = 0.8;
//     private static final double SL_MIN_DISTANCE_ATR    = 1.2;  // tighter -> SKIP (noise risk)
//     private static final double SL_PREFERRED_MAX_ATR   = 2.5;  // up to here: GOOD
//     private static final double SL_STRONG_MAX_ATR      = 3.0;  // 2.5-3.0 allowed only for strong setups
//     private static final double SL_HARD_PERCENT_CAP    = 5.0;  // safety-net fallback ONLY, never primary

//     // =========================================================================
//     // SECTION 22-24 — RR-based TP.
//     // =========================================================================
//     private static final double RR_DEFAULT = 1.0;
//     private static final double RR_STRONG  = 1.2; // used only for a clean 30M=6/6 + strong 1H + clean 15M setup

//     // =========================================================================
//     // Margin-based fixed position sizing.
//     // =========================================================================
//     private static final double MAX_MARGIN = 1200.0; // INR margin per trade

//     private static final double LIMIT_ORDER_BUFFER_PCT = 0.0005;

//     private static final long SCALP_COOLDOWN_MS            = 5 * 60 * 1000L;
//     private static final long SCALP_ENTRY_SCAN_INTERVAL_MS = 20 * 1000L;

//     private static class PendingSignal {
//         boolean isLong;
//         double confirmHigh, confirmLow;
//         boolean strongTrend; // 30M scored a clean 6/6 at arm time -> eligible for RR_STRONG
//         long   createdAtMs;
//     }
//     private static final Map<String, PendingSignal> pendingSignals = new ConcurrentHashMap<>();

//     private static final Map<String, JSONObject> instrumentCache = new ConcurrentHashMap<>();
//     private static long lastCacheUpdate = 0;
//     private static final Map<String, Long> lastTradeTime = new ConcurrentHashMap<>();

//     private static final String[] COIN_SYMBOLS = {
//        "PIEVERSE","XAU","APE","ERA","US","RAVE","EDEN","LIT","BREV","MAGMA","BLESS","ZAMA",
//         "FRAX","ACU","1000FLOKI","ELSA","LINEA","SPACE","CLO","FIGHT","UMA","MEGA","MAV","TRIA",
//         "YGG","OPN","ROBO","SUI","GLM","MANTRA","SEI","CAKE","AUCTION","SENT","BSB","BASED","IRYS",
//         "ACE","WET","CL","PRL","GENIUS","WIF","MANTA","LSK","AIGENSYN","PHAROS","JUP","AXL","BOME",
//         "SLX","ZEST","AIOT","VVV","CAP","DATAIP","GRVT","TAO","BR","TURBO","BTC","ETH","ZK","LISTA",
//         "A","LTC","XAG","COAI","HANA","ZRO","SKYAI","COPPER","RARE","ETC","M","AKE","XLM","PIXEL",
//         "XAN","ADA","CROSS","XMR","G","DASH","ZEC","ATOM","TRUTH","BCH","NEO","IOST","FLUX","ALGO",
//         "ZRX","COMP","WLFI","POL","DOGE","BAND","OPG","FIDA","PROM","SANTOS","RLC","1000000MOG",
//         "GRASS","PNUT","TRB","KAIA","ARX","XAI","S","4","COTI","CHR","SOLV","SAGA","ORCA","1000LUNC",
//         "MOVE","VIRTUAL","ME","IOTX","GIGGLE","AVA","VELODROME","AIXBT","KMNO","LA","DEXE","ZBT",
//         "GRIFFAIN","BLUAI","CTSI","ROSE","TURTLE","IMX","SUN","APR","TA","ON","BIO","COOKIE",
//         "AVAAI","DOT","TRUMP","MELANIA","GMT","FLOCK","CLANKER","CYS","SUSHI","VTHO","DIA",
//         "SLP","GOAT","BMT","KGEN","GWEI","MUBARAK","LDO","ESP","DRIFT","FORM","PLUME","NIL",
//         "UNI","ZORA","RECALL","INIT","BZ","PARTI","NATGAS","SPX","BANK","AVAX","RIVER","BILL",
//         "ATH","XRP","KERNEL","JST","PUNDIX","HAEDAL","ALPINE","SOON","SOPH","HUMA","TRX","LINK",
//         "HYPE","HIVE","TAIKO","TAG","MYX","NEWT","AIN","USUAL","PUMP","ICNT","BNB","H","BAT",
//         "QTUM","ARC","AIO","BEAT","BTR","ALCH","THETA","VELVET","ARIA","PTB","UB","LIGHT","FF",
//         "EVAAI","GMX","LYN","TAC","LAB","ENJ","AT","MMT","UAI","AAVE","JCT","KSM","HEI","JASMY",
//         "NEAR","TST","SOL","OP","PLAY","INJ","STG","HOLO","ASR","B","LUNA2","RSR","INX","KAT",
//         "ICP","QNT","MAGIC","T","MINA","STX","ACH","LQTY","ID","GRT","NEIRO","XVS","1INCH","SAND",
//         "ANKR","RVN","SFP","KAVA","MANA","HBAR","ARB","MTL","C98","TUT","SIREN","MASK","1000XEC",
//         "AR","ARPA","FIL","LPT","ENS","PEOPLE","LUMIA","DUSK","FLOW","XVG","ARKM","POPCAT","ARK",
//         "MOODENG","SAFE","AXS","BICO","BIGTIME","WAXP","GAS","POWR","TIA","CHIP","STO","ORDI",
//         "BEAMX","1000BONK","PYTH","ETHW","1000RATS","ANIME","OPEN","DYM","BERA","PORTAL","BB",
//         "BANANAS31","CFX","SSV","TNSR","EDU","JELLYJELLY","BLUR","WAL","FHE","WCT","DEEP","SXT",
//         "NAORIS","OG","CVC","AWE","O","BEL","JOE","SQD","1000PEPE","CARV","FET","SAPIEN","MEME",
//         "AVNT","XPIN","ILV","KAS","BNT","STBL","BSV","RIF","SUPER","USTC","METIS","ETHFI","ENA",
//         "1MBABYDOGE","CATI","HMSTR","GPS","SHELL","KAITO","ACT","RPL","BAN","THE","AKT","MORPHO",
//         "CHILLGUY","AERO","MOCA","PENGU","PHA","RED","EPIC","TREE","1000CAT","MAVIA","FARTCOIN",
//         "PAXG","IN","ORDER","VET","ZEN","STABLE","CHZ","NIGHT","NOM","ZKP","SKR","GRAM","BIRB",
//         "CTR","KNC","ZIL","YFI","EGLD","RUNE","ASTR","ONE","1000SHIB","API3","SPELL","WOO","APT",
//         "PENDLE","AGLD","CYBER","CKB","ONG","MOVR","POLYX","TWT","STEEM","ALT","ZETA","REZ","RENDER",
//         "RONIN","STRK","W","SCR","CETUS","IO","MEW","SWARMS","SONIC","PIPPIN","PROMPT","MERL","F",
//         "ESPORTS","PROVE","XNY","USELESS","HEMI","Q","SKY","ZKC","FLUID","MITO","CFG","EDGE","RE",
//         "YB","MET","DOS","FOGO","BTW","ALLO","BROCCOLI714","HYPER","XPL","RESOLV","ASTER","KITE",
//         "SIGN","HOME","MON","CC","SAHARA","MIRA","EUL","TOWNS","SYRUP","C","DOLO","ALICE","BABY",
//         "SOMI","NOT","BARD","SPK","POWER","2Z","BANANA","ENSO","SYN","NXPC","GUN","XTZ","ONT","SKL",
//         "HOT","JTO","DOGS","EIGEN","GTC","GALA","NMR","CGPT","ZEREBRO","VANA","OGN","CELO","USDC",
//         "COW","0G","IOTA","SNX","DYDX","WLD","1000SATS","ONDO","AEVO","BRETT","LAYER","CRV","TLM","KOMA"
//     };

//     private static final Set<String> INTEGER_QTY_PAIRS = Stream.of(COIN_SYMBOLS)
//             .flatMap(s -> Stream.of("B-" + s + "_USDT", s + "_USDT"))
//             .collect(Collectors.toCollection(HashSet::new));

//     private static final String[] COINS_TO_TRADE = Stream.of(COIN_SYMBOLS)
//             .map(s -> "B-" + s + "_USDT")
//             .toArray(String[]::new);

//     // =========================================================================
//     // Market structure (HH/HL vs LL/LH) — used only in the 1H/30M 6-point
//     // score, per Section 3/4. Returns +1 bullish, -1 bearish, 0 none/mixed.
//     // =========================================================================
//     private static int detectSwingStructure(double[] hi, double[] lo, int lookback) {
//         int n = hi.length;
//         if (n < lookback + 3) return 0;
//         int start = Math.max(1, n - lookback);
//         List<Integer> swingHighIdx = new ArrayList<>();
//         List<Integer> swingLowIdx  = new ArrayList<>();
//         for (int i = start; i < n - 1; i++) {
//             if (hi[i] > hi[i - 1] && hi[i] > hi[i + 1]) swingHighIdx.add(i);
//             if (lo[i] < lo[i - 1] && lo[i] < lo[i + 1]) swingLowIdx.add(i);
//         }
//         boolean hh = false, hl = false, ll = false, lh = false;
//         if (swingHighIdx.size() >= 2) {
//             double h1 = hi[swingHighIdx.get(swingHighIdx.size() - 2)];
//             double h2 = hi[swingHighIdx.get(swingHighIdx.size() - 1)];
//             hh = h2 > h1; lh = h2 < h1;
//         }
//         if (swingLowIdx.size() >= 2) {
//             double l1 = lo[swingLowIdx.get(swingLowIdx.size() - 2)];
//             double l2 = lo[swingLowIdx.get(swingLowIdx.size() - 1)];
//             hl = l2 > l1; ll = l2 < l1;
//         }
//         if (hh && hl) return 1;
//         if (ll && lh) return -1;
//         return 0;
//     }

//     // Most recent VALID pullback swing low/high (a local extremum), not the
//     // absolute lowest/highest of the whole lookback window — Sections 18/19.
//     private static double findRecentSwingLow(double[] lo, int lookback) {
//         int n = lo.length;
//         int start = Math.max(1, n - lookback);
//         for (int i = n - 2; i >= start; i--) {
//             if (lo[i] < lo[i - 1] && lo[i] < lo[i + 1]) return lo[i];
//         }
//         int fallbackStart = Math.max(0, n - 8); // no clean swing found: use the recent pullback window itself
//         double min = Double.POSITIVE_INFINITY;
//         for (int i = fallbackStart; i < n; i++) min = Math.min(min, lo[i]);
//         return min;
//     }

//     private static double findRecentSwingHigh(double[] hi, int lookback) {
//         int n = hi.length;
//         int start = Math.max(1, n - lookback);
//         for (int i = n - 2; i >= start; i--) {
//             if (hi[i] > hi[i - 1] && hi[i] > hi[i + 1]) return hi[i];
//         }
//         int fallbackStart = Math.max(0, n - 8);
//         double max = Double.NEGATIVE_INFINITY;
//         for (int i = fallbackStart; i < n; i++) max = Math.max(max, hi[i]);
//         return max;
//     }

//     // Section 25 — is there a swing high/low sitting between entry and TP that
//     // would realistically block price from reaching TP?
//     private static boolean tpBlockedByLevel(boolean isLong, double entry, double tp, double[] hi, double[] lo, int lookback) {
//         int n = isLong ? hi.length : lo.length;
//         int start = Math.max(1, n - lookback);
//         if (isLong) {
//             for (int i = n - 2; i >= start; i--) {
//                 if (hi[i] > hi[i - 1] && hi[i] > hi[i + 1]) {
//                     double level = hi[i];
//                     if (level > entry && level < tp) return true;
//                 }
//             }
//         } else {
//             for (int i = n - 2; i >= start; i--) {
//                 if (lo[i] < lo[i - 1] && lo[i] < lo[i + 1]) {
//                     double level = lo[i];
//                     if (level < entry && level > tp) return true;
//                 }
//             }
//         }
//         return false;
//     }

//     // =========================================================================
//     // SECTION 3/4 — scored direction, out of 6. Used for both 1H and 30M.
//     // =========================================================================
//     private static class ScoredDirection {
//         boolean valid;
//         int bullScore, bearScore;
//     }

//     private static ScoredDirection analyzeScored6(JSONArray candles) {
//         ScoredDirection r = new ScoredDirection();
//         if (candles == null || candles.length() < EMA_MID + Math.max(ATR_PERIOD, ST_PERIOD) + STRUCTURE_SWING_LOOKBACK) {
//             r.valid = false;
//             return r;
//         }
//         double[] cl = extractCloses(candles);
//         double[] hi = extractHighs(candles);
//         double[] lo = extractLows(candles);

//         double ema9  = calcEMA(cl, EMA_FAST);
//         double ema21 = calcEMA(cl, EMA_MID);
//         double atr   = calcATR(hi, lo, cl, ATR_PERIOD);
//         double price = cl[cl.length - 1];

//         boolean[] stSeries = calcSupertrend(hi, lo, cl, ST_PERIOD, ST_MULTIPLIER);
//         boolean stGreen = stSeries[stSeries.length - 1];

//         double[] ema9Series = calcEMASeries(cl, EMA_FAST);
//         int n = ema9Series.length;
//         int lookback = Math.min(EMA_SLOPE_LOOKBACK_BARS, n - 1);
//         double emaSlope = ema9Series[n - 1] - ema9Series[n - 1 - lookback];
//         boolean slopeUp   = atr > 0 && emaSlope >= HTF_EMA_SLOPE_MIN_ATR * atr;
//         boolean slopeDown = atr > 0 && emaSlope <= -HTF_EMA_SLOPE_MIN_ATR * atr;

//         int structure = detectSwingStructure(hi, lo, STRUCTURE_SWING_LOOKBACK);

//         int bull = 0, bear = 0;
//         if (ema9 > ema21) bull++; else if (ema9 < ema21) bear++;
//         if (price > ema9) bull++; else bear++;
//         if (price > ema21) bull++; else bear++;
//         if (stGreen) bull++; else bear++;
//         if (slopeUp) bull++; else if (slopeDown) bear++;
//         if (structure == 1) bull++; else if (structure == -1) bear++;

//         r.valid = true;
//         r.bullScore = bull;
//         r.bearScore = bear;
//         return r;
//     }

//     // =========================================================================
//     // SECTION 5 — 15M setup, scored out of 5 (no structure, no hard AND).
//     // =========================================================================
//     private static class Setup15Result {
//         boolean valid;
//         int bullScore, bearScore;
//         double stLower, stUpper; // kept only as an informational reference, never the primary SL anchor
//     }

//     private static Setup15Result analyzeSetup15M(JSONArray candles) {
//         Setup15Result r = new Setup15Result();
//         if (candles == null || candles.length() < EMA_MID + Math.max(ATR_PERIOD, ST_PERIOD)) {
//             r.valid = false;
//             return r;
//         }
//         double[] cl = extractCloses(candles);
//         double[] hi = extractHighs(candles);
//         double[] lo = extractLows(candles);

//         double ema9  = calcEMA(cl, EMA_FAST);
//         double ema21 = calcEMA(cl, EMA_MID);
//         double atr   = calcATR(hi, lo, cl, ATR_PERIOD);
//         double price = cl[cl.length - 1];

//         boolean[] stSeries = calcSupertrend(hi, lo, cl, ST_PERIOD, ST_MULTIPLIER);
//         boolean stGreen = stSeries[stSeries.length - 1];
//         double[] bands = calcSupertrendBands(hi, lo, cl, ST_PERIOD, ST_MULTIPLIER);
//         r.stLower = bands[0];
//         r.stUpper = bands[1];

//         double[] ema9Series = calcEMASeries(cl, EMA_FAST);
//         int n = ema9Series.length;
//         int lookback = Math.min(EMA_SLOPE_LOOKBACK_BARS, n - 1);
//         double emaSlope = ema9Series[n - 1] - ema9Series[n - 1 - lookback];
//         boolean slopeUp   = atr > 0 && emaSlope >= HTF_EMA_SLOPE_MIN_ATR * atr;
//         boolean slopeDown = atr > 0 && emaSlope <= -HTF_EMA_SLOPE_MIN_ATR * atr;

//         int bull = 0, bear = 0;
//         if (ema9 > ema21) bull++; else if (ema9 < ema21) bear++;
//         if (price > ema9) bull++; else bear++;
//         if (stGreen) bull++; else bear++;
//         if (slopeUp) bull++; else if (slopeDown) bear++;
//         if (price > ema9 && price > ema21) bull++; else if (price < ema9 && price < ema21) bear++;

//         r.valid = true;
//         r.bullScore = bull;
//         r.bearScore = bear;
//         return r;
//     }

//     // =========================================================================
//     // SECTION 6-9 — 5M pullback + rejection + slope (mandatory) plus a 5-point
//     // supporting confirmation score (min 3/5).
//     // =========================================================================
//     private static class EntryResult {
//         boolean valid;
//         boolean setupFound;
//         double confirmHigh, confirmLow;
//         double atr5m;
//         double distanceAtr;
//         String reason;
//     }

//     private static EntryResult analyzeEntry5M(JSONArray raw5m, boolean trendUp) {
//         EntryResult t = new EntryResult();
//         int minBars = EMA_MID + Math.max(ATR_PERIOD, Math.max(RSI_PERIOD, VOLUME_MA_PERIOD)) + 5;
//         if (raw5m == null || raw5m.length() < minBars) {
//             t.valid = false;
//             return t;
//         }

//         double[] cl  = extractCloses(raw5m);
//         double[] op  = extractOpens(raw5m);
//         double[] hi  = extractHighs(raw5m);
//         double[] lo  = extractLows(raw5m);
//         double[] vol = extractVolumes(raw5m);
//         int n = cl.length;

//         double ema9  = calcEMA(cl, EMA_FAST);
//         double ema21 = calcEMA(cl, EMA_MID);
//         double atr5m = calcATR(hi, lo, cl, ATR_PERIOD);
//         t.atr5m = atr5m;

//         double entryClose = cl[n - 1], entryOpen = op[n - 1];
//         double entryHigh  = hi[n - 1], entryLow  = lo[n - 1];

//         double nearestEmaDist = Math.min(Math.abs(entryClose - ema9), Math.abs(entryClose - ema21));
//         double distanceAtr = atr5m > 0 ? nearestEmaDist / atr5m : 0;
//         t.distanceAtr = distanceAtr;
//         boolean pulledBack = distanceAtr <= PULLBACK_MAX_ATR; // "there was a pullback, price isn't chasing an extended move"

//         boolean directionalCandle = trendUp ? (entryClose > entryOpen) : (entryClose < entryOpen);
//         double body  = Math.abs(entryClose - entryOpen);
//         double range = entryHigh - entryLow;
//         boolean notDoji = range > 0 && (body / range) >= CANDLE_BODY_RATIO_MIN;

//         double closePositionInRange = range > 0
//                 ? (trendUp ? (entryClose - entryLow) / range : (entryHigh - entryClose) / range)
//                 : 0;
//         boolean rejectionOk = closePositionInRange >= 0.60;

//         double[] ema9Series5m = calcEMASeries(cl, EMA_FAST);
//         int lookback5m = Math.min(EMA_SLOPE_LOOKBACK_BARS, n - 1);
//         double emaSlope5m = ema9Series5m[n - 1] - ema9Series5m[n - 1 - lookback5m];
//         boolean slope5mOk = trendUp
//                 ? (atr5m > 0 && emaSlope5m >= ENTRY_EMA_SLOPE_MIN_ATR * atr5m)
//                 : (atr5m > 0 && emaSlope5m <= -ENTRY_EMA_SLOPE_MIN_ATR * atr5m);

//         boolean mandatoryOk = pulledBack && directionalCandle && notDoji && rejectionOk && slope5mOk;

//         // ---- Section 8/9: 5-point supporting confirmation score ----
//         int volStart = Math.max(0, n - 1 - VOLUME_MA_PERIOD);
//         double avgVol = 0; int cnt = 0;
//         for (int i = volStart; i < n - 1; i++) { avgVol += vol[i]; cnt++; }
//         avgVol = cnt > 0 ? avgVol / cnt : 0;
//         boolean volumeOk = avgVol > 0 && vol[n - 1] >= avgVol; // "prefer >= average", not extreme volume

//         double rsi = calcRSI(cl, RSI_PERIOD);
//         boolean rsiOk = trendUp
//                 ? (rsi >= RSI_LONG_MIN && rsi <= RSI_LONG_MAX)
//                 : (rsi >= RSI_SHORT_MIN && rsi <= RSI_SHORT_MAX);

//         int vwapStart = Math.max(0, n - VWAP_LOOKBACK);
//         double cumPV = 0, cumV = 0;
//         for (int i = vwapStart; i < n; i++) {
//             double typical = (hi[i] + lo[i] + cl[i]) / 3.0;
//             cumPV += typical * vol[i];
//             cumV  += vol[i];
//         }
//         double vwap = cumV > 0 ? cumPV / cumV : entryClose;
//         boolean vwapOk = trendUp ? entryClose >= vwap : entryClose <= vwap;

//         boolean[] stSeries5m = calcSupertrend(hi, lo, cl, ST_PERIOD, ST_MULTIPLIER);
//         boolean st5mOk = trendUp ? stSeries5m[stSeries5m.length - 1] : !stSeries5m[stSeries5m.length - 1];

//         boolean emaAlignOk = trendUp ? (entryClose > ema9 && entryClose > ema21) : (entryClose < ema9 && entryClose < ema21);

//         int confirmationScore = (volumeOk ? 1 : 0) + (rsiOk ? 1 : 0) + (vwapOk ? 1 : 0)
//                 + (st5mOk ? 1 : 0) + (emaAlignOk ? 1 : 0);

//         t.setupFound = mandatoryOk && confirmationScore >= ENTRY_CONFIRMATION_MIN_SCORE;
//         t.confirmHigh = entryHigh;
//         t.confirmLow  = entryLow;
//         t.valid = true;
//         t.reason = String.format(
//                 "pullback=%.2fATR(ok=%s) rejection=%s(pos=%.2f) directional=%s notDoji=%s slope=%s vol=%s rsi=%.1f(ok=%s) vwap=%s st5m=%s emaAlign=%s score=%d/5",
//                 distanceAtr, pulledBack, rejectionOk, closePositionInRange, directionalCandle, notDoji, slope5mOk,
//                 volumeOk, rsi, rsiOk, vwapOk, st5mOk, emaAlignOk, confirmationScore);
//         return t;
//     }

//     private static JSONArray dropLastIfForming(JSONArray arr) {
//         if (arr == null || arr.length() < 2) return arr;
//         JSONArray out = new JSONArray();
//         for (int i = 0; i < arr.length() - 1; i++) out.put(arr.getJSONObject(i));
//         return out;
//     }

//     // =========================================================================
//     // SECTIONS 18-21 — structural SL off the LAST VALID PULLBACK SWING + ATR
//     // buffer. SL distance is then checked against the 0.7 / 2.5 / 3.0 ATR
//     // bands. Returns {sl, tp, slDistanceAtr, rrUsed}, or null if rejected.
//     // =========================================================================
//     private static double[] computeStructuralSlTp(boolean isLong, double entryPrice,
//                                                     double[] hi5m, double[] lo5m, double atr,
//                                                     double tickSize, boolean strongTrend) {
//         if (atr <= 0) return null;
//         double sl;
//         if (isLong) {
//             double swingLow = findRecentSwingLow(lo5m, SL_SWING_LOOKBACK);
//             sl = swingLow - SL_BUFFER_ATR * atr;
//         } else {
//             double swingHigh = findRecentSwingHigh(hi5m, SL_SWING_LOOKBACK);
//             sl = swingHigh + SL_BUFFER_ATR * atr;
//         }
//         double slDistanceAtr = Math.abs(entryPrice - sl) / atr;

//         if (slDistanceAtr < SL_MIN_DISTANCE_ATR) return null;              // too tight — noise risk — SKIP
//         if (slDistanceAtr > SL_STRONG_MAX_ATR) return null;                // too wide regardless — SKIP
//         if (slDistanceAtr > SL_PREFERRED_MAX_ATR && !strongTrend) return null; // 2.5-3.0 ATR only for strong setups

//         double rrTarget = strongTrend ? RR_STRONG : RR_DEFAULT;
//         double risk = Math.abs(entryPrice - sl);
//         double tp = isLong ? entryPrice + rrTarget * risk : entryPrice - rrTarget * risk;

//         sl = roundToTick(sl, tickSize);
//         tp = roundToTick(tp, tickSize);
//         return new double[]{sl, tp, slDistanceAtr, rrTarget};
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
//     // Margin-based fixed position sizing.
//     // =========================================================================
//     private static double calcQuantity(double price, String pair) {
//         double usdtInrRate = 98.0;
//         double qty = MAX_MARGIN / (price * usdtInrRate);
//         double finalQty = INTEGER_QTY_PAIRS.contains(pair) ? Math.floor(qty) : Math.floor(qty * 100) / 100.0;
//         return Math.max(finalQty, 0);
//     }

//     public static void main(String[] args) {
//         System.out.println("=== Bot starting (trend-continuation framework: scored 1H/30M, scored 15M setup, 5M pullback+rejection, structural SL, RR-based TP with level check) ===");
//         initInstrumentCache();

//         while (true) {
//             try {
//                 runEntryScan();
//             } catch (Throwable t) {
//                 System.err.println("[MAIN-LOOP] Uncaught error, continuing: " + t.getMessage());
//                 t.printStackTrace();
//             }
//             try {
//                 TimeUnit.MILLISECONDS.sleep(SCALP_ENTRY_SCAN_INTERVAL_MS);
//             } catch (InterruptedException ignored) {
//                 Thread.currentThread().interrupt();
//                 break;
//             }
//         }
//     }

//     private static void runEntryScan() {
//         Set<String> active = getActivePositions();
//         System.out.println("Active positions: " + active);
//         pendingSignals.keySet().removeIf(active::contains);

//         if (active.size() >= MAX_OPEN_POSITIONS) {
//             System.out.println("MAX_OPEN_POSITIONS reached — skipping scan.");
//             ensureTpSlForOpenPositions();
//             return;
//         }

//         for (String pair : COINS_TO_TRADE) {
//             try {
//                 if (active.size() >= MAX_OPEN_POSITIONS) break;
//                 if (active.contains(pair)) continue;

//                 long lastTrade = lastTradeTime.getOrDefault(pair, 0L);
//                 if (System.currentTimeMillis() - lastTrade < SCALP_COOLDOWN_MS) continue;

//                 JSONArray raw5m = dropLastIfForming(getCandlestickData(pair, RES_5M, BASE_5M_FETCH_COUNT));
//                 if (raw5m == null || raw5m.length() < EMA_MID + ST_PERIOD + STRUCTURE_SWING_LOOKBACK) continue;

//                 PendingSignal pending = pendingSignals.get(pair);

//                 // Pending signals are checked independently of the full cascade
//                 // re-passing every cycle — a higher-timeframe flicker must not
//                 // starve a signal that's already armed and waiting to trigger.
//                 if (pending != null) {
//                     boolean cancelled = false;
//                     if (System.currentTimeMillis() - pending.createdAtMs > SIGNAL_MAX_VALID_MS) {
//                         System.out.println("  Signal expired (stale setup): " + pair);
//                         cancelled = true;
//                     } else {
//                         EntryResult quickCheck = analyzeEntry5M(raw5m, pending.isLong);
//                         if (quickCheck.valid && quickCheck.distanceAtr > OVEREXTENSION_SKIP_ATR) {
//                             System.out.println("  Signal cancelled (price overextended "
//                                     + String.format("%.2f", quickCheck.distanceAtr) + " ATR): " + pair);
//                             cancelled = true;
//                         }
//                     }
//                     if (!cancelled) {
//                         JSONArray raw1h = dropLastIfForming(getCandlestickData(pair, RES_1H, BASE_1H_FETCH_COUNT));
//                         ScoredDirection dir1h = analyzeScored6(raw1h);
//                         if (dir1h.valid) {
//                             boolean stillAgrees = pending.isLong ? dir1h.bullScore >= MACRO_1H_MIN_SCORE : dir1h.bearScore >= MACRO_1H_MIN_SCORE;
//                             if (!stillAgrees) {
//                                 System.out.println("  Signal cancelled (1H direction changed): " + pair);
//                                 cancelled = true;
//                             }
//                         }
//                     }

//                     if (cancelled) {
//                         pendingSignals.remove(pair);
//                     } else {
//                         double currentPrice = getLastPrice(pair);
//                         if (currentPrice > 0) {
//                             boolean breakoutHit = pending.isLong
//                                     ? currentPrice > pending.confirmHigh
//                                     : currentPrice < pending.confirmLow;
//                             if (breakoutHit) {
//                                 tryEnterOnBreakout(pair, pending, raw5m, currentPrice, active);
//                             }
//                         }
//                         continue;
//                     }
//                 }

//                 // ---- No pending signal — look for a fresh one ----

//                 // ---- Section 3: 1H macro direction, >=4/6 ----
//                 JSONArray raw1h = dropLastIfForming(getCandlestickData(pair, RES_1H, BASE_1H_FETCH_COUNT));
//                 ScoredDirection dir1h = analyzeScored6(raw1h);
//                 if (!dir1h.valid) continue;
//                 boolean trendUp;
//                 if (dir1h.bullScore >= MACRO_1H_MIN_SCORE) trendUp = true;
//                 else if (dir1h.bearScore >= MACRO_1H_MIN_SCORE) trendUp = false;
//                 else continue; // neither reaches minimum -> SKIP

//                 // ---- Section 4: 30M confirmation ----
//                 JSONArray raw30m = aggregateCandles(raw5m, GROUP_30M_FROM_5M);
//                 ScoredDirection dir30m = analyzeScored6(raw30m);
//                 if (!dir30m.valid) continue;
//                 int score30 = trendUp ? dir30m.bullScore : dir30m.bearScore;
//                 int opposite30 = trendUp ? dir30m.bearScore : dir30m.bullScore;
//                 if (opposite30 > score30) continue; // 30M disagrees with 1H -> SKIP

//                 boolean strong30 = score30 >= CONFIRM_30M_STRONG_MIN;
//                 boolean acceptable30 = score30 == CONFIRM_30M_ACCEPTABLE;
//                 if (!strong30 && !acceptable30) continue; // <4/6 -> SKIP

//                 // ---- Section 5: 15M setup, scored (not hard AND) ----
//                 JSONArray raw15m = aggregateCandles(raw5m, GROUP_15M_FROM_5M);
//                 Setup15Result setup15 = analyzeSetup15M(raw15m);
//                 if (!setup15.valid) continue;
//                 int score15 = trendUp ? setup15.bullScore : setup15.bearScore;
//                 if (score15 < SETUP_15M_MIN_SCORE) continue;

//                 // ---- Section 6-9: 5M pullback + rejection + slope + confirmation ----
//                 EntryResult entry5m = analyzeEntry5M(raw5m, trendUp);
//                 if (!entry5m.valid) continue;

//                 // If 30M was only "acceptable" (4/6), require 15M and 5M to be
//                 // particularly clean before allowing the trade at all.
//                 if (acceptable30) {
//                     int confScore5m = 0;
//                     // crude re-extraction of the 5M confirmation score from the reason string is avoidable —
//                     // recompute the same five booleans quickly is unnecessary; setupFound already required >=3/5,
//                     // so treat "clean" as score15 and setupFound both at their higher bars.
//                     boolean clean15 = score15 >= CLEAN_15M_MIN_FOR_ACCEPTABLE;
//                     boolean clean5 = entry5m.setupFound; // already passed mandatory + >=3/5; treat as the practical "clean" bar here
//                     if (!clean15 || !clean5) continue;
//                 }

//                 if (!entry5m.setupFound) continue;

//                 PendingSignal newSignal = new PendingSignal();
//                 newSignal.isLong = trendUp;
//                 newSignal.confirmHigh = entry5m.confirmHigh;
//                 newSignal.confirmLow  = entry5m.confirmLow;
//                 newSignal.strongTrend = (score30 == 6);
//                 newSignal.createdAtMs = System.currentTimeMillis();
//                 pendingSignals.put(pair, newSignal);
//                 System.out.println("  Pending " + (trendUp ? "LONG" : "SHORT") + " signal armed: " + pair
//                         + " | 1H=" + (trendUp ? dir1h.bullScore : dir1h.bearScore) + "/6 30M=" + score30 + "/6 15M=" + score15 + "/5"
//                         + " | trigger=" + (trendUp ? ("break " + newSignal.confirmHigh) : ("break " + newSignal.confirmLow))
//                         + " | " + entry5m.reason);

//             } catch (Exception e) {
//                 System.err.println("Error on " + pair + ": " + e.getMessage());
//             }
//         }

//         System.out.println("\n=== Scan complete ===");
//         ensureTpSlForOpenPositions();
//     }

//     // Section 10/14 — handles an armed pending signal's breakout: recomputes
//     // structural SL (last valid pullback swing + ATR buffer), applies the SL
//     // distance bands, applies the TP-location check against nearby levels,
//     // sizes the position (margin-based), places the order, confirms the fill,
//     // and re-derives final SL/TP off the actual fill price.
//     private static void tryEnterOnBreakout(String pair, PendingSignal pending, JSONArray raw5m,
//                                             double currentPrice, Set<String> active) {
//         try {
//             double tickSize = getTickSize(pair);
//             double[] hi5m = extractHighs(raw5m);
//             double[] lo5m = extractLows(raw5m);
//             double atr = calcATR(hi5m, lo5m, extractCloses(raw5m), ATR_PERIOD);

//             double[] preSlTp = computeStructuralSlTp(pending.isLong, currentPrice, hi5m, lo5m, atr, tickSize, pending.strongTrend);
//             if (preSlTp == null) {
//                 System.out.println("  NO TRADE: " + pair + " — structural SL distance outside acceptable ATR bands (rejected, not tightened)");
//                 pendingSignals.remove(pair);
//                 return;
//             }

//             // Section 25 — nearby resistance/support check before committing to this TP.
//             JSONArray raw15m = aggregateCandles(raw5m, GROUP_15M_FROM_5M);
//             if (raw15m != null) {
//                 double[] hi15m = extractHighs(raw15m);
//                 double[] lo15m = extractLows(raw15m);
//                 if (tpBlockedByLevel(pending.isLong, currentPrice, preSlTp[1], hi15m, lo15m, TP_LEVEL_LOOKBACK)) {
//                     System.out.println("  NO TRADE: " + pair + " — nearby " + (pending.isLong ? "resistance" : "support") + " sits before TP");
//                     pendingSignals.remove(pair);
//                     return;
//                 }
//             }

//             double qty = calcQuantity(currentPrice, pair);
//             if (qty <= 0) { pendingSignals.remove(pair); return; }

//             System.out.println("\n==== " + pair + " — BREAKOUT ENTRY ====");
//             String side = pending.isLong ? "buy" : "sell";
//             JSONObject orderResp = placeFuturesOrder(side, pair, qty, LEVERAGE,
//                     "email_notification", "isolated", "INR", currentPrice);
//             if (orderResp == null || !orderResp.has("id")) {
//                 System.out.println("  Order failed: " + orderResp);
//                 pendingSignals.remove(pair);
//                 return;
//             }

//             System.out.println("  Order placed! id=" + orderResp.getString("id"));
//             lastTradeTime.put(pair, System.currentTimeMillis());
//             pendingSignals.remove(pair);

//             double entry = getEntryPrice(pair, orderResp.getString("id"));
//             if (entry <= 0) {
//                 System.out.println("  Could not confirm entry within window — TP/SL handled by safety sweep");
//                 active.add(pair);
//                 return;
//             }
//             System.out.printf("  Entry confirmed: %.6f%n", entry);

//             double[] slTp = computeStructuralSlTp(pending.isLong, entry, hi5m, lo5m, atr, tickSize, pending.strongTrend);
//             double slPrice, tpPrice, rrUsed;
//             if (slTp == null) {
//                 // rare: slippage pushed the confirmed fill into reject territory — do not leave the
//                 // position unprotected; fall back to the hard % safety cap instead of skipping protection.
//                 slPrice = pending.isLong ? entry * (1 - SL_HARD_PERCENT_CAP / 100.0) : entry * (1 + SL_HARD_PERCENT_CAP / 100.0);
//                 tpPrice = pending.isLong ? entry + RR_DEFAULT * (entry - slPrice) : entry - RR_DEFAULT * (slPrice - entry);
//                 rrUsed = RR_DEFAULT;
//                 System.out.println("  WARNING: post-fill structural SL rejected — using hard % safety cap instead");
//             } else {
//                 slPrice = slTp[0]; tpPrice = slTp[1]; rrUsed = slTp[3];
//             }
//             double[] clamped = sanityClampSlTp(pending.isLong, entry, slPrice, tpPrice, tickSize);
//             slPrice = clamped[0]; tpPrice = clamped[1];

//             System.out.printf("  SL=%.6f | TP=%.6f | RR=%.2f | QTY=%.4f%n", slPrice, tpPrice, rrUsed, qty);

//             String posId = getPositionId(pair);
//             if (posId != null) {
//                 setTpSlWithRetry(posId, tpPrice, slPrice, pair);
//             } else {
//                 System.out.println("  Position ID not found after retries — safety sweep will handle it");
//             }

//             active.add(pair);
//         } catch (Exception e) {
//             System.err.println("tryEnterOnBreakout(" + pair + "): " + e.getMessage());
//         }
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
//                 JSONArray raw5m = dropLastIfForming(getCandlestickData(pair, RES_5M, BASE_5M_FETCH_COUNT));
//                 double[] hi5m = null, lo5m = null;
//                 double atr5m = 0;
//                 if (raw5m != null && raw5m.length() >= ATR_PERIOD + SL_SWING_LOOKBACK) {
//                     hi5m = extractHighs(raw5m);
//                     lo5m = extractLows(raw5m);
//                     atr5m = calcATR(hi5m, lo5m, extractCloses(raw5m), ATR_PERIOD);
//                 }

//                 double posQty = pos.optDouble("active_pos", 0);
//                 boolean isLong = posQty >= 0;
//                 double tick = getTickSize(pair);

//                 double[] slTp = (hi5m != null && atr5m > 0)
//                         ? computeStructuralSlTp(isLong, avgPrice, hi5m, lo5m, atr5m, tick, false)
//                         : null;
//                 double sl, tp;
//                 if (slTp == null) {
//                     sl = isLong ? avgPrice * (1 - SL_HARD_PERCENT_CAP / 100.0) : avgPrice * (1 + SL_HARD_PERCENT_CAP / 100.0);
//                     tp = isLong ? avgPrice + RR_DEFAULT * (avgPrice - sl) : avgPrice - RR_DEFAULT * (sl - avgPrice);
//                     System.out.println("  [SWEEP] structural SL unavailable/rejected for " + pair + " — using hard % fallback cap");
//                 } else {
//                     sl = slTp[0]; tp = slTp[1];
//                 }
//                 double[] clamped = sanityClampSlTp(isLong, avgPrice, sl, tp, tick);
//                 sl = clamped[0]; tp = clamped[1];

//                 String posId = pos.optString("id", null);
//                 if (posId != null) {
//                     System.out.printf("  [SWEEP] %s fallback SL=%.6f TP=%.6f%n", pair, sl, tp);
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

//     private static double[] calcEMASeries(double[] d, int period) {
//         double[] out = new double[d.length];
//         if (d.length < period) {
//             double last = d.length > 0 ? d[d.length - 1] : 0;
//             Arrays.fill(out, last);
//             return out;
//         }
//         double k = 2.0 / (period + 1);
//         double ema = 0;
//         for (int i = 0; i < period; i++) ema += d[i];
//         ema /= period;
//         for (int i = 0; i < period; i++) out[i] = ema;
//         out[period - 1] = ema;
//         for (int i = period; i < d.length; i++) {
//             ema = d[i] * k + ema * (1 - k);
//             out[i] = ema;
//         }
//         return out;
//     }

//     private static double calcEMA(double[] d, int period) {
//         if (d.length < period) return 0;
//         double k = 2.0 / (period + 1), ema = 0;
//         for (int i = 0; i < period; i++) ema += d[i];
//         ema /= period;
//         for (int i = period; i < d.length; i++) ema = d[i] * k + ema * (1 - k);
//         return ema;
//     }

//     private static double calcRSI(double[] closes, int period) {
//         if (closes.length < period + 1) return 50.0;

//         double avgGain = 0, avgLoss = 0;
//         for (int i = 1; i <= period; i++) {
//             double change = closes[i] - closes[i - 1];
//             if (change > 0) avgGain += change;
//             else avgLoss += -change;
//         }
//         avgGain /= period;
//         avgLoss /= period;

//         for (int i = period + 1; i < closes.length; i++) {
//             double change = closes[i] - closes[i - 1];
//             double gain = Math.max(change, 0);
//             double loss = Math.max(-change, 0);
//             avgGain = (avgGain * (period - 1) + gain) / period;
//             avgLoss = (avgLoss * (period - 1) + loss) / period;
//         }

//         if (avgLoss == 0) return 100.0;
//         double rs = avgGain / avgLoss;
//         return 100.0 - (100.0 / (1.0 + rs));
//     }

//     private static BigDecimal roundToTickBD(double price, double tick) {
//         if (tick <= 0) return BigDecimal.valueOf(price);
//         BigDecimal bdPrice = BigDecimal.valueOf(price);
//         BigDecimal bdTick  = BigDecimal.valueOf(tick);
//         BigDecimal multiples = bdPrice.divide(bdTick, 0, RoundingMode.HALF_UP);
//         BigDecimal result = multiples.multiply(bdTick);
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
//     private static double[] extractVolumes(JSONArray a) {
//         double[] o = new double[a.length()];
//         for (int i = 0; i < a.length(); i++) o[i] = a.getJSONObject(i).optDouble("volume", 0);
//         return o;
//     }

//     private static JSONArray getCandlestickData(String pair, String resolution, int count) {
//         try {
//             long minsPerBar;
//             switch (resolution) {
//                 case "1":   minsPerBar = 1;   break;
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
//             double volSum = 0;
//             for (int j = i; j < i + groupSize; j++) {
//                 JSONObject c = source.getJSONObject(j);
//                 high = Math.max(high, c.getDouble("high"));
//                 low  = Math.min(low,  c.getDouble("low"));
//                 volSum += c.optDouble("volume", 0);
//             }
//             JSONObject merged = new JSONObject();
//             merged.put("open", open);
//             merged.put("close", close);
//             merged.put("high", high);
//             merged.put("low", low);
//             merged.put("volume", volSum);
//             result.put(merged);
//         }
//         return result;
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
// import java.time.Instant;
// import java.util.*;
// import java.util.concurrent.ConcurrentHashMap;
// import java.util.concurrent.TimeUnit;
// import java.util.stream.Collectors;
// import java.util.stream.Stream;

// public class CoinDCXFuturesTrader8C_BUY_SELL_NEW_LOGIC_THREE {

//     // =========================================================================
//     // API Configuration (unchanged)
//     // =========================================================================
//     private static final String API_KEY    = System.getenv("DELTA_API_KEY");
//     private static final String API_SECRET = System.getenv("DELTA_API_SECRET");
//     private static final String BASE_URL       = "https://api.coindcx.com";
//     private static final String PUBLIC_API_URL = "https://public.coindcx.com";

//     private static final int LEVERAGE = 6;

//     private static final int MAX_ENTRY_PRICE_CHECKS = 20;
//     private static final int ENTRY_CHECK_DELAY_MS    = 1000;

//     private static final int  TPSL_MAX_RETRIES    = 3;
//     private static final long TPSL_RETRY_DELAY_MS = 2000L;

//     private static final long TICK_CACHE_TTL_MS = 3_600_000L;

//     private static final int MAX_OPEN_POSITIONS = 120;

//     private static final int  POSITION_ID_MAX_RETRIES = 5;
//     private static final long POSITION_ID_RETRY_DELAY_MS = 1500L;

//     // =========================================================================
//     // Indicator periods (unchanged)
//     // =========================================================================
//     private static final int EMA_FAST = 9;
//     private static final int EMA_MID  = 21;
//     private static final int ATR_PERIOD = 14;
//     private static final int ST_PERIOD     = 10;
//     private static final double ST_MULTIPLIER = 3.0;

//     private static final String RES_5M = "5";
//     private static final String RES_1H = "60";

//     // NOTE: BASE_5M_FETCH_COUNT and BASE_1H_FETCH_COUNT must stay comfortably
//     // above what analyzeMacro1H/analyzeConfirmation30M/analyzeSetup15M require
//     // (EMA_MID + ST_PERIOD/ATR_PERIOD + STRUCTURE_SWING_LOOKBACK = ~61-65 bars),
//     // AFTER dropLastIfForming() removes one candle and, for 30M, after dividing
//     // by GROUP_30M_FROM_5M. The old values (250 / 55, left over from before the
//     // HH/HL structure check was added) fell short — every pair was failing the
//     // very first "insufficient data" check regardless of market conditions.
//     private static final int BASE_5M_FETCH_COUNT = 450; // -> ~74 bars for the 30M aggregate, safely above ~61 needed
//     private static final int GROUP_15M_FROM_5M = 3;
//     private static final int GROUP_30M_FROM_5M = 6;
//     private static final int BASE_1H_FETCH_COUNT = 90;  // -> ~89 bars after drop, safely above ~65 needed

//     private static final int RSI_PERIOD = 14;
//     private static final double RSI_LONG_MIN  = 45, RSI_LONG_MAX  = 68;
//     private static final double RSI_SHORT_MIN = 32, RSI_SHORT_MAX = 55;

//     private static final int    ENTRY_VOLUME_LOOKBACK   = 20;
//     private static final double ENTRY_VOLUME_MULTIPLIER = 1.20;
//     private static final int    ENTRY_VWAP_LOOKBACK      = 20;

//     private static final int EMA_SLOPE_LOOKBACK_BARS = 5;
//     private static final double HTF_EMA_SLOPE_MIN_ATR   = 0.10;
//     private static final double ENTRY_EMA_SLOPE_MIN_ATR = 0.15;

//     private static final int SWING_LOOKBACK_BARS      = 20;
//     private static final int STRUCTURE_SWING_LOOKBACK = 30; // for HH/HL - LL/LH detection

//     private static final double ENTRY_MIN_BODY_RATIO = 0.40;

//     // =========================================================================
//     // NEW (PART 1/4) — quality-based multi-timeframe direction thresholds
//     // =========================================================================
//     private static final int MACRO_1H_MIN_TRUE      = 4; // "most of 6 conditions true" on 1H
//     private static final int CONFIRM_30M_MIN_SCORE  = 5; // out of 6 -> strong confirmation
//     private static final int CONFIRM_30M_BORDERLINE = 4; // allowed only if 15M is exceptionally clean

//     // =========================================================================
//     // NEW (PART 3/14) — congestion / overextension, ATR-normalized (not fixed %)
//     // =========================================================================
//     private static final double EMA_CONGESTION_MIN_ATR = 0.30; // reject 15M setup if EMA9/21 closer than this
//     private static final double PULLBACK_MIN_ATR       = 0.20;
//     private static final double PULLBACK_MAX_ATR       = 0.80;
//     private static final double OVEREXTENDED_ATR       = 1.50; // beyond this: mandatoryOk fails
//     private static final double CAUTION_ATR            = 2.00; // beyond this: cancel any pending signal

//     // =========================================================================
//     // NEW (PART 2) — 5M confirmation score out of 5 measurable factors.
//     // The 6th spec factor ("breaks confirmation candle high/low") is enforced
//     // separately as the actual entry trigger (see PendingSignal below), not
//     // scored here.
//     // =========================================================================
//     private static final int ENTRY_CONFIRMATION_MIN_SCORE = 3; // out of 5

//     // =========================================================================
//     // NEW (PART 2, Step 4) — pending breakout-entry signal validity window
//     // =========================================================================
//     private static final long SIGNAL_MAX_VALID_MS = 3L * 5 * 60 * 1000L; // ~3 x 5M candles

//     // =========================================================================
//     // NEW (PART 5/6) — structural, reject-not-tighten stop loss
//     // =========================================================================
//     private static final double SL_ATR_BUFFER_MULT_MIN  = 0.30;
//     private static final double SL_ATR_BUFFER_MULT_MAX  = 0.60;
//     private static final double SL_MIN_ATR_DISTANCE       = 1.0; // tighter than this -> likely noise-stopped, reject
//     private static final double SL_MAX_ATR_DISTANCE       = 3.5; // wider than this -> reject trade, do not tighten
//     private static final double SL_HARD_PERCENT_CAP       = 6.0; // absolute safety-net fallback ONLY (sweep / post-fill edge case)

//     // =========================================================================
//     // NEW (PART 7) — dynamic risk/reward
//     // =========================================================================
//     private static final double RR_TARGET_BASE   = 1.5;
//     private static final double RR_TARGET_STRONG = 1.9; // used only when 30M scores a clean 6/6

//     // =========================================================================
//     // NEW (PART 15) — risk-based position sizing (replaces fixed-margin sizing)
//     // =========================================================================
//     private static final double TOTAL_CAPITAL_BASE     = 5000.0; // INR — placeholder, set to your real capital
//     private static final double RISK_PERCENT_PER_TRADE = 1.0;     // % of capital risked per trade
//     private static final double MAX_MARGIN             = 1000.0;  // hard safety ceiling, never exceeded regardless of risk sizing

//     private static final double LIMIT_ORDER_BUFFER_PCT = 0.0005;

//     private static final long SCALP_COOLDOWN_MS            = 5 * 60 * 1000L;
//     private static final long SCALP_ENTRY_SCAN_INTERVAL_MS = 20 * 1000L;

//     // =========================================================================
//     // NEW (PART 9/10/11) — state-based trailing (STATE 0..4), ratchet-only,
//     // plus a health-checked TP extension.
//     // =========================================================================
//     private static final boolean TRAILING_ENABLED = true;
//     private static final double TRAIL_ATR_EARLY  = 2.25; // state 1 (>=0.5R)
//     private static final double TRAIL_ATR_STAGE2 = 1.75; // state 2+ (>=1R)
//     private static final double TRAIL_ATR_STAGE3 = 1.35; // state 3/4 (>=1.5R / >=2R)
//     private static final double BREAKEVEN_BUFFER_PCT = 0.10; // small locked profit at +1R, not pure breakeven

//     private static final double TP_EXTEND_TRIGGER_FRACTION = 0.85;
//     private static final int    TP_MAX_EXTENSIONS = 4;

//     private static class TrailInfo {
//         boolean isLong;
//         double entryPrice;
//         double initialRisk;
//         double initialReward;
//         double currentSl;
//         double currentTp;
//         double peak;
//         int    state;          // 0..4, PART 9 states
//         int    extensionsUsed;
//     }
//     private static final Map<String, TrailInfo> trailState = new ConcurrentHashMap<>();

//     // =========================================================================
//     // NEW (PART 2, Step 4) — pending breakout signal per pair. A 5M setup that
//     // passes mandatory+confirmation conditions does NOT enter immediately; it
//     // arms a pending signal and waits for price to break the confirmation
//     // candle's high (long) / low (short), with a max validity window and
//     // several cancellation conditions.
//     // =========================================================================
//     private static class PendingSignal {
//         boolean isLong;
//         double confirmHigh, confirmLow;
//         double atrAtSignal;
//         long   createdAtMs;
//     }
//     private static final Map<String, PendingSignal> pendingSignals = new ConcurrentHashMap<>();

//     private static final Map<String, JSONObject> instrumentCache = new ConcurrentHashMap<>();
//     private static long lastCacheUpdate = 0;
//     private static final Map<String, Long> lastTradeTime = new ConcurrentHashMap<>();

//     // =========================================================================
//     // NEW — diagnostic funnel counters. Reset every scan cycle, printed at the
//     // end. This tells us exactly which gate is blocking trades (e.g. "30M
//     // score too low: 287, EMA congestion: 40, pullback zone missed: 55...")
//     // instead of guessing whether the strategy is "too strict" from the code.
//     // =========================================================================
//     private static final Map<String, Integer> funnelStats = new LinkedHashMap<>();
//     private static void bump(String stage) {
//         funnelStats.merge(stage, 1, Integer::sum);
//     }

//     private static final String[] COIN_SYMBOLS = {
//        "PIEVERSE","XAU","APE","ERA","US","RAVE","EDEN","LIT","BREV","MAGMA","BLESS","ZAMA",
//         "FRAX","ACU","1000FLOKI","ELSA","LINEA","SPACE","CLO","FIGHT","UMA","MEGA","MAV","TRIA",
//         "YGG","OPN","ROBO","SUI","GLM","MANTRA","SEI","CAKE","AUCTION","SENT","BSB","BASED","IRYS",
//         "ACE","WET","CL","PRL","GENIUS","WIF","MANTA","LSK","AIGENSYN","PHAROS","JUP","AXL","BOME",
//         "SLX","ZEST","AIOT","VVV","CAP","DATAIP","GRVT","TAO","BR","TURBO","BTC","ETH","ZK","LISTA",
//         "A","LTC","XAG","COAI","HANA","ZRO","SKYAI","COPPER","RARE","ETC","M","AKE","XLM","PIXEL",
//         "XAN","ADA","CROSS","XMR","G","DASH","ZEC","ATOM","TRUTH","BCH","NEO","IOST","FLUX","ALGO",
//         "ZRX","COMP","WLFI","POL","DOGE","BAND","OPG","FIDA","PROM","SANTOS","RLC","1000000MOG",
//         "GRASS","PNUT","TRB","KAIA","ARX","XAI","S","4","COTI","CHR","SOLV","SAGA","ORCA","1000LUNC",
//         "MOVE","VIRTUAL","ME","IOTX","GIGGLE","AVA","VELODROME","AIXBT","KMNO","LA","DEXE","ZBT",
//         "GRIFFAIN","BLUAI","CTSI","ROSE","TURTLE","IMX","SUN","APR","TA","ON","BIO","COOKIE",
//         "AVAAI","DOT","TRUMP","MELANIA","GMT","FLOCK","CLANKER","CYS","SUSHI","VTHO","DIA",
//         "SLP","GOAT","BMT","KGEN","GWEI","MUBARAK","LDO","ESP","DRIFT","FORM","PLUME","NIL",
//         "UNI","ZORA","RECALL","INIT","BZ","PARTI","NATGAS","SPX","BANK","AVAX","RIVER","BILL",
//         "ATH","XRP","KERNEL","JST","PUNDIX","HAEDAL","ALPINE","SOON","SOPH","HUMA","TRX","LINK",
//         "HYPE","HIVE","TAIKO","TAG","MYX","NEWT","AIN","USUAL","PUMP","ICNT","BNB","H","BAT",
//         "QTUM","ARC","AIO","BEAT","BTR","ALCH","THETA","VELVET","ARIA","PTB","UB","LIGHT","FF",
//         "EVAAI","GMX","LYN","TAC","LAB","ENJ","AT","MMT","UAI","AAVE","JCT","KSM","HEI","JASMY",
//         "NEAR","TST","SOL","OP","PLAY","INJ","STG","HOLO","ASR","B","LUNA2","RSR","INX","KAT",
//         "ICP","QNT","MAGIC","T","MINA","STX","ACH","LQTY","ID","GRT","NEIRO","XVS","1INCH","SAND",
//         "ANKR","RVN","SFP","KAVA","MANA","HBAR","ARB","MTL","C98","TUT","SIREN","MASK","1000XEC",
//         "AR","ARPA","FIL","LPT","ENS","PEOPLE","LUMIA","DUSK","FLOW","XVG","ARKM","POPCAT","ARK",
//         "MOODENG","SAFE","AXS","BICO","BIGTIME","WAXP","GAS","POWR","TIA","CHIP","STO","ORDI",
//         "BEAMX","1000BONK","PYTH","ETHW","1000RATS","ANIME","OPEN","DYM","BERA","PORTAL","BB",
//         "BANANAS31","CFX","SSV","TNSR","EDU","JELLYJELLY","BLUR","WAL","FHE","WCT","DEEP","SXT",
//         "NAORIS","OG","CVC","AWE","O","BEL","JOE","SQD","1000PEPE","CARV","FET","SAPIEN","MEME",
//         "AVNT","XPIN","ILV","KAS","BNT","STBL","BSV","RIF","SUPER","USTC","METIS","ETHFI","ENA",
//         "1MBABYDOGE","CATI","HMSTR","GPS","SHELL","KAITO","ACT","RPL","BAN","THE","AKT","MORPHO",
//         "CHILLGUY","AERO","MOCA","PENGU","PHA","RED","EPIC","TREE","1000CAT","MAVIA","FARTCOIN",
//         "PAXG","IN","ORDER","VET","ZEN","STABLE","CHZ","NIGHT","NOM","ZKP","SKR","GRAM","BIRB",
//         "CTR","KNC","ZIL","YFI","EGLD","RUNE","ASTR","ONE","1000SHIB","API3","SPELL","WOO","APT",
//         "PENDLE","AGLD","CYBER","CKB","ONG","MOVR","POLYX","TWT","STEEM","ALT","ZETA","REZ","RENDER",
//         "RONIN","STRK","W","SCR","CETUS","IO","MEW","SWARMS","SONIC","PIPPIN","PROMPT","MERL","F",
//         "ESPORTS","PROVE","XNY","USELESS","HEMI","Q","SKY","ZKC","FLUID","MITO","CFG","EDGE","RE",
//         "YB","MET","DOS","FOGO","BTW","ALLO","BROCCOLI714","HYPER","XPL","RESOLV","ASTER","KITE",
//         "SIGN","HOME","MON","CC","SAHARA","MIRA","EUL","TOWNS","SYRUP","C","DOLO","ALICE","BABY",
//         "SOMI","NOT","BARD","SPK","POWER","2Z","BANANA","ENSO","SYN","NXPC","GUN","XTZ","ONT","SKL",
//         "HOT","JTO","DOGS","EIGEN","GTC","GALA","NMR","CGPT","ZEREBRO","VANA","OGN","CELO","USDC",
//         "COW","0G","IOTA","SNX","DYDX","WLD","1000SATS","ONDO","AEVO","BRETT","LAYER","CRV","TLM","KOMA"
//     };

//     private static final Set<String> INTEGER_QTY_PAIRS = Stream.of(COIN_SYMBOLS)
//             .flatMap(s -> Stream.of("B-" + s + "_USDT", s + "_USDT"))
//             .collect(Collectors.toCollection(HashSet::new));

//     private static final String[] COINS_TO_TRADE = Stream.of(COIN_SYMBOLS)
//             .map(s -> "B-" + s + "_USDT")
//             .toArray(String[]::new);

//     // =========================================================================
//     // NEW — market structure (HH/HL vs LL/LH), used by the 1H/30M/15M scoring.
//     // Returns +1 bullish structure, -1 bearish structure, 0 none/mixed.
//     // =========================================================================
//     private static int detectSwingStructure(double[] hi, double[] lo, int lookback) {
//         int n = hi.length;
//         if (n < lookback + 3) return 0;
//         int start = Math.max(1, n - lookback);
//         List<Integer> swingHighIdx = new ArrayList<>();
//         List<Integer> swingLowIdx  = new ArrayList<>();
//         for (int i = start; i < n - 1; i++) {
//             if (hi[i] > hi[i - 1] && hi[i] > hi[i + 1]) swingHighIdx.add(i);
//             if (lo[i] < lo[i - 1] && lo[i] < lo[i + 1]) swingLowIdx.add(i);
//         }
//         boolean hh = false, hl = false, ll = false, lh = false;
//         if (swingHighIdx.size() >= 2) {
//             double h1 = hi[swingHighIdx.get(swingHighIdx.size() - 2)];
//             double h2 = hi[swingHighIdx.get(swingHighIdx.size() - 1)];
//             hh = h2 > h1;
//             lh = h2 < h1;
//         }
//         if (swingLowIdx.size() >= 2) {
//             double l1 = lo[swingLowIdx.get(swingLowIdx.size() - 2)];
//             double l2 = lo[swingLowIdx.get(swingLowIdx.size() - 1)];
//             hl = l2 > l1;
//             ll = l2 < l1;
//         }
//         if (hh && hl) return 1;
//         if (ll && lh) return -1;
//         return 0;
//     }

//     // =========================================================================
//     // PART 1 — 1H macro bias. "Most of 6 conditions true" (>= MACRO_1H_MIN_TRUE),
//     // not a hard all-or-nothing EMA-cross check.
//     // =========================================================================
//     private static class DirectionResult {
//         boolean valid;
//         boolean bullish;
//         boolean bearish;
//         int score;
//     }

//     private static DirectionResult analyzeMacro1H(JSONArray candles) {
//         DirectionResult r = new DirectionResult();
//         if (candles == null || candles.length() < EMA_MID + Math.max(ATR_PERIOD, ST_PERIOD) + STRUCTURE_SWING_LOOKBACK) {
//             r.valid = false;
//             return r;
//         }
//         double[] cl = extractCloses(candles);
//         double[] hi = extractHighs(candles);
//         double[] lo = extractLows(candles);

//         double ema9  = calcEMA(cl, EMA_FAST);
//         double ema21 = calcEMA(cl, EMA_MID);
//         double atr   = calcATR(hi, lo, cl, ATR_PERIOD);
//         double price = cl[cl.length - 1];

//         boolean[] stSeries = calcSupertrend(hi, lo, cl, ST_PERIOD, ST_MULTIPLIER);
//         boolean stGreen = stSeries[stSeries.length - 1];

//         double[] ema9Series = calcEMASeries(cl, EMA_FAST);
//         int n = ema9Series.length;
//         int lookback = Math.min(EMA_SLOPE_LOOKBACK_BARS, n - 1);
//         double emaSlope = ema9Series[n - 1] - ema9Series[n - 1 - lookback];
//         boolean slopeUp   = atr > 0 && emaSlope >= HTF_EMA_SLOPE_MIN_ATR * atr;
//         boolean slopeDown = atr > 0 && emaSlope <= -HTF_EMA_SLOPE_MIN_ATR * atr;

//         int structure = detectSwingStructure(hi, lo, STRUCTURE_SWING_LOOKBACK);

//         int bull = 0, bear = 0;
//         if (ema9 > ema21) bull++; else if (ema9 < ema21) bear++;
//         if (price > ema9) bull++; else bear++;
//         if (price > ema21) bull++; else bear++;
//         if (stGreen) bull++; else bear++;
//         if (slopeUp) bull++; else if (slopeDown) bear++;
//         if (structure == 1) bull++; else if (structure == -1) bear++;

//         r.valid = true;
//         r.bullish = bull >= MACRO_1H_MIN_TRUE;
//         r.bearish = bear >= MACRO_1H_MIN_TRUE;
//         if (r.bullish && r.bearish) { // guard against a degenerate tie
//             if (bull >= bear) r.bearish = false; else r.bullish = false;
//         }
//         r.score = r.bullish ? bull : (r.bearish ? bear : Math.max(bull, bear));
//         return r;
//     }

//     // =========================================================================
//     // PART 1 — 30M trend confirmation, scored out of 6 (needs 5/6, or 4/6 if
//     // 15M is exceptionally strong — enforced by the caller).
//     // =========================================================================
//     private static class ConfirmResult {
//         boolean valid;
//         int bullScore, bearScore;
//     }

//     private static ConfirmResult analyzeConfirmation30M(JSONArray candles) {
//         ConfirmResult r = new ConfirmResult();
//         if (candles == null || candles.length() < EMA_MID + ST_PERIOD + STRUCTURE_SWING_LOOKBACK) {
//             r.valid = false;
//             return r;
//         }
//         double[] cl = extractCloses(candles);
//         double[] hi = extractHighs(candles);
//         double[] lo = extractLows(candles);

//         double ema9  = calcEMA(cl, EMA_FAST);
//         double ema21 = calcEMA(cl, EMA_MID);
//         double atr   = calcATR(hi, lo, cl, ATR_PERIOD);
//         double price = cl[cl.length - 1];

//         boolean[] stSeries = calcSupertrend(hi, lo, cl, ST_PERIOD, ST_MULTIPLIER);
//         boolean stGreen = stSeries[stSeries.length - 1];

//         double[] ema9Series = calcEMASeries(cl, EMA_FAST);
//         int n = ema9Series.length;
//         int lookback = Math.min(EMA_SLOPE_LOOKBACK_BARS, n - 1);
//         double emaSlope = ema9Series[n - 1] - ema9Series[n - 1 - lookback];
//         boolean slopeUp   = atr > 0 && emaSlope >= HTF_EMA_SLOPE_MIN_ATR * atr;
//         boolean slopeDown = atr > 0 && emaSlope <= -HTF_EMA_SLOPE_MIN_ATR * atr;

//         int structure = detectSwingStructure(hi, lo, STRUCTURE_SWING_LOOKBACK);

//         int bull = 0, bear = 0;
//         if (ema9 > ema21) bull++; else if (ema9 < ema21) bear++;
//         if (price > ema9) bull++; else bear++;
//         if (price > ema21) bull++; else bear++;
//         if (stGreen) bull++; else bear++;
//         if (slopeUp) bull++; else if (slopeDown) bear++;
//         if (structure == 1) bull++; else if (structure == -1) bear++;

//         r.valid = true;
//         r.bullScore = bull;
//         r.bearScore = bear;
//         return r;
//     }

//     // =========================================================================
//     // PART 1 — 15M setup, with ATR-normalized EMA-congestion filter.
//     // =========================================================================
//     private static class SetupResult {
//         boolean valid;
//         boolean bullish;
//         boolean bearish;
//         double  atr;
//         double  stLower, stUpper;
//         double  emaDistanceAtr;
//         boolean congested;
//     }

//     private static SetupResult analyzeSetup15M(JSONArray candles) {
//         SetupResult r = new SetupResult();
//         if (candles == null || candles.length() < EMA_MID + ST_PERIOD + STRUCTURE_SWING_LOOKBACK) {
//             r.valid = false;
//             return r;
//         }
//         double[] cl = extractCloses(candles);
//         double[] hi = extractHighs(candles);
//         double[] lo = extractLows(candles);

//         double ema9  = calcEMA(cl, EMA_FAST);
//         double ema21 = calcEMA(cl, EMA_MID);
//         double price = cl[cl.length - 1];
//         r.atr = calcATR(hi, lo, cl, ATR_PERIOD);

//         boolean[] stSeries = calcSupertrend(hi, lo, cl, ST_PERIOD, ST_MULTIPLIER);
//         boolean stGreen = stSeries[stSeries.length - 1];
//         double[] bands = calcSupertrendBands(hi, lo, cl, ST_PERIOD, ST_MULTIPLIER);
//         r.stLower = bands[0];
//         r.stUpper = bands[1];

//         double[] ema9Series = calcEMASeries(cl, EMA_FAST);
//         int n = ema9Series.length;
//         int lookback = Math.min(EMA_SLOPE_LOOKBACK_BARS, n - 1);
//         double emaSlope = ema9Series[n - 1] - ema9Series[n - 1 - lookback];
//         boolean slopeUp   = r.atr > 0 && emaSlope >= HTF_EMA_SLOPE_MIN_ATR * r.atr;
//         boolean slopeDown = r.atr > 0 && emaSlope <= -HTF_EMA_SLOPE_MIN_ATR * r.atr;

//         int structure = detectSwingStructure(hi, lo, STRUCTURE_SWING_LOOKBACK);

//         r.emaDistanceAtr = r.atr > 0 ? Math.abs(ema9 - ema21) / r.atr : 0;
//         r.congested = r.emaDistanceAtr < EMA_CONGESTION_MIN_ATR;

//         boolean priceAboveBoth = price > ema9 && price > ema21;
//         boolean priceBelowBoth = price < ema9 && price < ema21;

//         r.valid = true;
//         r.bullish = !r.congested && (ema9 > ema21) && stGreen && slopeUp && priceAboveBoth && structure >= 0;
//         r.bearish = !r.congested && (ema9 < ema21) && !stGreen && slopeDown && priceBelowBoth && structure <= 0;
//         return r;
//     }

//     // =========================================================================
//     // PART 2/3 — 5M: pullback zone (ATR-normalized) + rejection + slope as
//     // mandatory conditions, plus a 5-factor confirmation score. Produces the
//     // confirmation candle's high/low for the breakout-entry trigger.
//     // =========================================================================
//     private static class EntryResult {
//         boolean valid;
//         boolean setupFound;
//         double confirmHigh, confirmLow;
//         double atr5m;
//         double distanceAtr;
//         String reason;
//     }

//     private static EntryResult analyzeEntry5M(JSONArray raw5m, boolean trendUp) {
//         EntryResult t = new EntryResult();
//         int minBars = Math.max(EMA_MID, Math.max(ENTRY_VOLUME_LOOKBACK, ENTRY_VWAP_LOOKBACK)) + RSI_PERIOD + 5;
//         if (raw5m == null || raw5m.length() < minBars) {
//             t.valid = false;
//             return t;
//         }

//         double[] cl  = extractCloses(raw5m);
//         double[] op  = extractOpens(raw5m);
//         double[] hi  = extractHighs(raw5m);
//         double[] lo  = extractLows(raw5m);
//         double[] vol = extractVolumes(raw5m);
//         int n = cl.length;

//         double ema9  = calcEMA(cl, EMA_FAST);
//         double ema21 = calcEMA(cl, EMA_MID);
//         double atr5m = calcATR(hi, lo, cl, ATR_PERIOD);
//         t.atr5m = atr5m;

//         double entryClose = cl[n - 1], entryOpen = op[n - 1];
//         double entryHigh  = hi[n - 1], entryLow  = lo[n - 1];

//         double nearestEmaDist = Math.min(Math.abs(entryClose - ema9), Math.abs(entryClose - ema21));
//         double distanceAtr = atr5m > 0 ? nearestEmaDist / atr5m : 0;
//         t.distanceAtr = distanceAtr;

//         boolean pulledBack   = distanceAtr >= PULLBACK_MIN_ATR && distanceAtr <= PULLBACK_MAX_ATR;
//         boolean overextended = distanceAtr > OVEREXTENDED_ATR;

//         boolean directionalCandle = trendUp ? (entryClose > entryOpen) : (entryClose < entryOpen);
//         double body  = Math.abs(entryClose - entryOpen);
//         double range = entryHigh - entryLow;
//         boolean notDoji = range > 0 && (body / range) >= ENTRY_MIN_BODY_RATIO;

//         double closePositionInRange = range > 0
//                 ? (trendUp ? (entryClose - entryLow) / range : (entryHigh - entryClose) / range)
//                 : 0;
//         boolean rejectionOk = closePositionInRange >= 0.60;

//         double[] ema9Series5m = calcEMASeries(cl, EMA_FAST);
//         int lookback5m = Math.min(EMA_SLOPE_LOOKBACK_BARS, n - 1);
//         double emaSlope5m = ema9Series5m[n - 1] - ema9Series5m[n - 1 - lookback5m];
//         boolean slope5mOk = trendUp
//                 ? (atr5m > 0 && emaSlope5m >= ENTRY_EMA_SLOPE_MIN_ATR * atr5m)
//                 : (atr5m > 0 && emaSlope5m <= -ENTRY_EMA_SLOPE_MIN_ATR * atr5m);

//         boolean mandatoryOk = pulledBack && !overextended && rejectionOk && directionalCandle && notDoji && slope5mOk;

//         int volStart = Math.max(0, n - 1 - ENTRY_VOLUME_LOOKBACK);
//         double avgVol = 0; int cnt = 0;
//         for (int i = volStart; i < n - 1; i++) { avgVol += vol[i]; cnt++; }
//         avgVol = cnt > 0 ? avgVol / cnt : 0;
//         boolean volumeOk = avgVol > 0 && vol[n - 1] >= avgVol * ENTRY_VOLUME_MULTIPLIER;

//         double rsi = calcRSI(cl, RSI_PERIOD);
//         boolean rsiOk = trendUp
//                 ? (rsi >= RSI_LONG_MIN && rsi <= RSI_LONG_MAX)
//                 : (rsi >= RSI_SHORT_MIN && rsi <= RSI_SHORT_MAX);

//         int vwapStart = Math.max(0, n - ENTRY_VWAP_LOOKBACK);
//         double cumPV = 0, cumV = 0;
//         for (int i = vwapStart; i < n; i++) {
//             double typical = (hi[i] + lo[i] + cl[i]) / 3.0;
//             cumPV += typical * vol[i];
//             cumV  += vol[i];
//         }
//         double vwap = cumV > 0 ? cumPV / cumV : entryClose;
//         boolean vwapOk = trendUp ? entryClose >= vwap : entryClose <= vwap;

//         int confirmationScore = (volumeOk ? 1 : 0) + (rsiOk ? 1 : 0) + (vwapOk ? 1 : 0)
//                 + (slope5mOk ? 1 : 0) + (notDoji ? 1 : 0);

//         t.setupFound = mandatoryOk && confirmationScore >= ENTRY_CONFIRMATION_MIN_SCORE;
//         t.confirmHigh = entryHigh;
//         t.confirmLow  = entryLow;
//         t.valid = true;
//         t.reason = String.format(
//                 "pullback=%.2fATR(inZone=%s,overext=%s) rejection=%s(pos=%.2f) directional=%s notDoji=%s slope=%s vol=%s(%.2fx) rsi=%.1f(ok=%s) vwap=%s score=%d/5",
//                 distanceAtr, pulledBack, overextended, rejectionOk, closePositionInRange, directionalCandle, notDoji,
//                 slope5mOk, volumeOk, avgVol > 0 ? vol[n - 1] / avgVol : 0, rsi, rsiOk, vwapOk, confirmationScore);
//         return t;
//     }

//     private static JSONArray dropLastIfForming(JSONArray arr) {
//         if (arr == null || arr.length() < 2) return arr;
//         JSONArray out = new JSONArray();
//         for (int i = 0; i < arr.length() - 1; i++) out.put(arr.getJSONObject(i));
//         return out;
//     }

//     // =========================================================================
//     // PART 5/6 — structural SL. Buffer is 0.3-0.6 ATR off the true invalidation
//     // level (NOT a blind 4x ATR). If the resulting SL distance is outside the
//     // acceptable ATR band, the trade is REJECTED — never force-tightened.
//     // Returns {sl, tp, slDistanceAtr, rrUsed}, or null if rejected.
//     // =========================================================================
//     private static double[] computeStructuralSlTp(boolean isLong, double entryPrice,
//                                                     double[] hi, double[] lo, double atr,
//                                                     double stLevel, double tickSize,
//                                                     boolean strongTrend) {
//         if (atr <= 0) return null;
//         double bufferMult = (SL_ATR_BUFFER_MULT_MIN + SL_ATR_BUFFER_MULT_MAX) / 2.0; // 0.45 ATR
//         double sl;
//         if (isLong) {
//             double swingLow = recentLow(lo, SWING_LOOKBACK_BARS);
//             double structural = Math.min(swingLow, stLevel);
//             sl = structural - bufferMult * atr;
//         } else {
//             double swingHigh = recentHigh(hi, SWING_LOOKBACK_BARS);
//             double structural = Math.max(swingHigh, stLevel);
//             sl = structural + bufferMult * atr;
//         }
//         double slDistanceAtr = Math.abs(entryPrice - sl) / atr;
//         if (slDistanceAtr > SL_MAX_ATR_DISTANCE || slDistanceAtr < SL_MIN_ATR_DISTANCE) {
//             return null; // REJECT — do not tighten or widen artificially
//         }
//         double risk = Math.abs(entryPrice - sl);
//         double rrTarget = strongTrend ? RR_TARGET_STRONG : RR_TARGET_BASE;
//         double tp = isLong ? entryPrice + rrTarget * risk : entryPrice - rrTarget * risk;

//         sl = roundToTick(sl, tickSize);
//         tp = roundToTick(tp, tickSize);
//         return new double[]{sl, tp, slDistanceAtr, rrTarget};
//     }

//     private static double recentLow(double[] lo, int lookback) {
//         int n = lo.length;
//         int start = Math.max(0, n - lookback);
//         double min = Double.POSITIVE_INFINITY;
//         for (int i = start; i < n; i++) min = Math.min(min, lo[i]);
//         return min;
//     }

//     private static double recentHigh(double[] hi, int lookback) {
//         int n = hi.length;
//         int start = Math.max(0, n - lookback);
//         double max = Double.NEGATIVE_INFINITY;
//         for (int i = start; i < n; i++) max = Math.max(max, hi[i]);
//         return max;
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
//     // PART 15 — risk-based position sizing. Position size comes from the SL
//     // distance, not a fixed margin: wider SL -> smaller size, tighter SL ->
//     // larger size, always capped by MAX_MARGIN.
//     // =========================================================================
//     private static double calcRiskBasedQuantity(double entryPrice, double slPrice, String pair) {
//         double slDistancePercent = Math.abs(entryPrice - slPrice) / entryPrice * 100.0;
//         if (slDistancePercent <= 0) return 0;

//         double riskAmount = TOTAL_CAPITAL_BASE * (RISK_PERCENT_PER_TRADE / 100.0);
//         double positionNotional = riskAmount / (slDistancePercent / 100.0);

//         double usdtInrRate = 98.0;
//         double marginRequiredInr = positionNotional / LEVERAGE;
//         if (marginRequiredInr > MAX_MARGIN) {
//             positionNotional = MAX_MARGIN * LEVERAGE; // hard ceiling always wins
//         }
//         double qty = positionNotional / (entryPrice * usdtInrRate);
//         double finalQty = INTEGER_QTY_PAIRS.contains(pair) ? Math.floor(qty) : Math.floor(qty * 100) / 100.0;
//         return Math.max(finalQty, 0);
//     }

//     public static void main(String[] args) {
//         System.out.println("=== Bot starting (quality-scored multi-TF cascade + structural SL + state trailing) ===");
//         initInstrumentCache();

//         while (true) {
//             try {
//                 runEntryScan();
//             } catch (Throwable t) {
//                 System.err.println("[MAIN-LOOP] Uncaught error, continuing: " + t.getMessage());
//                 t.printStackTrace();
//             }
//             try {
//                 TimeUnit.MILLISECONDS.sleep(SCALP_ENTRY_SCAN_INTERVAL_MS);
//             } catch (InterruptedException ignored) {
//                 Thread.currentThread().interrupt();
//                 break;
//             }
//         }
//     }

//     private static void runEntryScan() {
//         funnelStats.clear();
//         Set<String> active = getActivePositions();
//         System.out.println("Active positions: " + active);

//         trailState.keySet().removeIf(pair -> !active.contains(pair));
//         pendingSignals.keySet().removeIf(active::contains); // once filled, drop the pending signal

//         if (active.size() >= MAX_OPEN_POSITIONS) {
//             System.out.println("MAX_OPEN_POSITIONS reached — skipping scan.");
//             ensureTpSlForOpenPositions();
//             if (TRAILING_ENABLED) updateTrailingForOpenPositions();
//             return;
//         }

//         for (String pair : COINS_TO_TRADE) {
//             try {
//                 if (active.size() >= MAX_OPEN_POSITIONS) break;
//                 if (active.contains(pair)) continue;

//                 long lastTrade = lastTradeTime.getOrDefault(pair, 0L);
//                 if (System.currentTimeMillis() - lastTrade < SCALP_COOLDOWN_MS) continue;

//                 JSONArray raw5m = dropLastIfForming(getCandlestickData(pair, RES_5M, BASE_5M_FETCH_COUNT));
//                 if (raw5m == null || raw5m.length() < EMA_MID + ST_PERIOD + STRUCTURE_SWING_LOOKBACK) continue;

//                 PendingSignal pending = pendingSignals.get(pair);

//                 // =========================================================
//                 // BUGFIX: a pending signal's breakout check used to sit
//                 // BEHIND the full 1H/30M/15M cascade, which had to re-pass
//                 // on every single 20s cycle just to reach the breakout
//                 // check below. Any flicker in a higher-timeframe condition
//                 // (very common) meant the loop hit "continue" earlier and
//                 // the pending signal was silently starved — it never
//                 // expired, never cancelled, never triggered. That is why
//                 // effectively zero orders were being placed. Pending
//                 // signals are now handled first and independently.
//                 // =========================================================
//                 if (pending != null) {
//                     boolean cancelled = false;
//                     if (System.currentTimeMillis() - pending.createdAtMs > SIGNAL_MAX_VALID_MS) {
//                         System.out.println("  Signal expired: " + pair);
//                         cancelled = true;
//                     } else {
//                         EntryResult quickCheck = analyzeEntry5M(raw5m, pending.isLong);
//                         if (quickCheck.valid && quickCheck.distanceAtr > CAUTION_ATR) {
//                             System.out.println("  Signal cancelled (overextended "
//                                     + String.format("%.2f", quickCheck.distanceAtr) + " ATR): " + pair);
//                             cancelled = true;
//                         }
//                     }

//                     if (cancelled) {
//                         pendingSignals.remove(pair);
//                     } else {
//                         double currentPrice = getLastPrice(pair);
//                         if (currentPrice > 0) {
//                             boolean breakoutHit = pending.isLong
//                                     ? currentPrice > pending.confirmHigh
//                                     : currentPrice < pending.confirmLow;
//                             if (breakoutHit) {
//                                 tryEnterOnBreakout(pair, pending, raw5m, currentPrice, active);
//                             }
//                         }
//                         continue; // still waiting (or just acted) on this pending signal this cycle
//                     }
//                 }

//                 // ---- No pending signal (or it just expired/cancelled) — look for a new one ----

//                 // ---- PART 1: 1H macro bias ----
//                 JSONArray raw1h = dropLastIfForming(getCandlestickData(pair, RES_1H, BASE_1H_FETCH_COUNT));
//                 DirectionResult macro1h = analyzeMacro1H(raw1h);
//                 if (!macro1h.valid) { bump("1H insufficient data"); continue; }
//                 if (!macro1h.bullish && !macro1h.bearish) { bump("1H no directional quality (<4/6)"); continue; }
//                 boolean trendUp = macro1h.bullish;

//                 // ---- PART 1: 30M confirmation, scored ----
//                 JSONArray raw30m = aggregateCandles(raw5m, GROUP_30M_FROM_5M);
//                 ConfirmResult confirm30 = analyzeConfirmation30M(raw30m);
//                 if (!confirm30.valid) { bump("30M insufficient data"); continue; }

//                 int score30    = trendUp ? confirm30.bullScore : confirm30.bearScore;
//                 int opposite30 = trendUp ? confirm30.bearScore : confirm30.bullScore;
//                 if (opposite30 > score30) {
//                     bump("1H/30M disagreement");
//                     continue;
//                 }
//                 boolean strong30     = score30 >= CONFIRM_30M_MIN_SCORE;
//                 boolean borderline30 = score30 == CONFIRM_30M_BORDERLINE;
//                 if (!strong30 && !borderline30) {
//                     bump("30M score too low (<4/6)");
//                     continue;
//                 }

//                 // ---- PART 1: 15M setup + EMA congestion filter ----
//                 JSONArray raw15m = aggregateCandles(raw5m, GROUP_15M_FROM_5M);
//                 SetupResult setup15 = analyzeSetup15M(raw15m);
//                 if (!setup15.valid) { bump("15M insufficient data"); continue; }
//                 if (setup15.congested) {
//                     bump("15M EMA congestion");
//                     continue;
//                 }
//                 boolean setupMatches = trendUp ? setup15.bullish : setup15.bearish;
//                 if (!setupMatches) {
//                     bump(borderline30 ? "15M not aligned (30M was borderline 4/6)" : "15M not aligned");
//                     continue;
//                 }

//                 // ---- PART 2: 5M pullback/rejection/momentum ----
//                 EntryResult entry5m = analyzeEntry5M(raw5m, trendUp);
//                 if (!entry5m.valid) { bump("5M insufficient data"); continue; }

//                 if (entry5m.setupFound) {
//                     PendingSignal newSignal = new PendingSignal();
//                     newSignal.isLong = trendUp;
//                     newSignal.confirmHigh = entry5m.confirmHigh;
//                     newSignal.confirmLow  = entry5m.confirmLow;
//                     newSignal.atrAtSignal = entry5m.atr5m;
//                     newSignal.createdAtMs = System.currentTimeMillis();
//                     pendingSignals.put(pair, newSignal);
//                     bump("SIGNAL ARMED");
//                     System.out.println("  Pending " + (trendUp ? "LONG" : "SHORT") + " signal armed: " + pair
//                             + " | trigger=" + (trendUp ? ("break " + newSignal.confirmHigh) : ("break " + newSignal.confirmLow))
//                             + " | " + entry5m.reason);
//                 } else {
//                     bump("5M setup not found (pullback/rejection/slope/score)");
//                 }

//             } catch (Exception e) {
//                 System.err.println("Error on " + pair + ": " + e.getMessage());
//             }
//         }

//         System.out.println("\n=== Scan complete — funnel breakdown (" + COINS_TO_TRADE.length + " pairs scanned) ===");
//         for (Map.Entry<String, Integer> e : funnelStats.entrySet()) {
//             System.out.println("  " + e.getKey() + ": " + e.getValue());
//         }
//         ensureTpSlForOpenPositions();
//         if (TRAILING_ENABLED) updateTrailingForOpenPositions();
//     }

//     // Handles an armed pending signal's breakout: computes structural SL/TP off
//     // current data, sizes the position, places the order, confirms the fill,
//     // re-derives final SL/TP off the actual fill price, and seeds trailing.
//     // Extracted out of runEntryScan so pending signals can be checked every
//     // cycle without needing the full 1H/30M/15M cascade to re-pass first.
//     private static void tryEnterOnBreakout(String pair, PendingSignal pending, JSONArray raw5m,
//                                             double currentPrice, Set<String> active) {
//         try {
//             double tickSize = getTickSize(pair);
//             JSONArray raw15m = aggregateCandles(raw5m, GROUP_15M_FROM_5M);
//             SetupResult setup15 = analyzeSetup15M(raw15m);
//             if (!setup15.valid) return;
//             double stLevel = pending.isLong ? setup15.stLower : setup15.stUpper;
//             double[] hi5m = extractHighs(raw5m);
//             double[] lo5m = extractLows(raw5m);
//             double atr = calcATR(hi5m, lo5m, extractCloses(raw5m), ATR_PERIOD);

//             // ---- PART 5/6: pre-trade structural SL check — reject BEFORE placing the order ----
//             double[] preSlTp = computeStructuralSlTp(pending.isLong, currentPrice, hi5m, lo5m, atr, stLevel, tickSize, false);
//             if (preSlTp == null) {
//                 System.out.println("  NO TRADE: " + pair + " — structural SL outside acceptable ATR band (rejected, not tightened)");
//                 pendingSignals.remove(pair);
//                 return;
//             }

//             // ---- PART 15: risk-based sizing off the pre-trade SL estimate ----
//             double qty = calcRiskBasedQuantity(currentPrice, preSlTp[0], pair);
//             if (qty <= 0) { pendingSignals.remove(pair); return; }

//             System.out.println("\n==== " + pair + " — BREAKOUT ENTRY ====");
//             String side = pending.isLong ? "buy" : "sell";
//             JSONObject orderResp = placeFuturesOrder(side, pair, qty, LEVERAGE,
//                     "email_notification", "isolated", "INR", currentPrice);
//             if (orderResp == null || !orderResp.has("id")) {
//                 System.out.println("  Order failed: " + orderResp);
//                 pendingSignals.remove(pair);
//                 return;
//             }

//             System.out.println("  Order placed! id=" + orderResp.getString("id"));
//             lastTradeTime.put(pair, System.currentTimeMillis());
//             pendingSignals.remove(pair);

//             double entry = getEntryPrice(pair, orderResp.getString("id"));
//             if (entry <= 0) {
//                 System.out.println("  Could not confirm entry within window — TP/SL handled by safety sweep");
//                 active.add(pair);
//                 return;
//             }
//             System.out.printf("  Entry confirmed: %.6f%n", entry);

//             // ---- Recompute SL/TP off the ACTUAL fill price ----
//             double[] slTp = computeStructuralSlTp(pending.isLong, entry, hi5m, lo5m, atr, stLevel, tickSize, false);
//             double slPrice, tpPrice, rrUsed;
//             if (slTp == null) {
//                 // rare: slippage pushed the confirmed fill into reject territory. Do not leave the
//                 // position unprotected — apply the hard % safety cap instead of skipping protection.
//                 slPrice = pending.isLong ? entry * (1 - SL_HARD_PERCENT_CAP / 100.0) : entry * (1 + SL_HARD_PERCENT_CAP / 100.0);
//                 tpPrice = pending.isLong ? entry + RR_TARGET_BASE * (entry - slPrice) : entry - RR_TARGET_BASE * (slPrice - entry);
//                 rrUsed = RR_TARGET_BASE;
//                 System.out.println("  WARNING: post-fill structural SL rejected — using hard % safety cap instead");
//             } else {
//                 slPrice = slTp[0];
//                 tpPrice = slTp[1];
//                 rrUsed  = slTp[3];
//             }
//             double[] clamped = sanityClampSlTp(pending.isLong, entry, slPrice, tpPrice, tickSize);
//             slPrice = clamped[0];
//             tpPrice = clamped[1];

//             System.out.printf("  SL=%.6f | TP=%.6f | RR=%.2f | QTY=%.4f%n", slPrice, tpPrice, rrUsed, qty);
//             System.out.println("  " + (pending.isLong ? "LONG" : "SHORT") + " SIGNAL: 5M=PULLBACK_REJECTION_BREAKOUT"
//                     + " SL=" + slPrice + " TP=" + tpPrice + " RR=" + rrUsed + " QTY=" + qty);

//             String posId = getPositionId(pair);
//             if (posId != null) {
//                 setTpSlWithRetry(posId, tpPrice, slPrice, pair);
//             } else {
//                 System.out.println("  Position ID not found after retries — safety sweep will handle it");
//             }

//             if (TRAILING_ENABLED) {
//                 TrailInfo ti = new TrailInfo();
//                 ti.isLong = pending.isLong;
//                 ti.entryPrice = entry;
//                 ti.initialRisk = Math.abs(entry - slPrice);
//                 ti.initialReward = Math.abs(tpPrice - entry);
//                 ti.currentSl = slPrice;
//                 ti.currentTp = tpPrice;
//                 ti.peak = entry;
//                 ti.state = 0;
//                 ti.extensionsUsed = 0;
//                 trailState.put(pair, ti);
//             }

//             active.add(pair);
//         } catch (Exception e) {
//             System.err.println("tryEnterOnBreakout(" + pair + "): " + e.getMessage());
//         }
//     }

//     // =========================================================================
//     // PART 9/10/11 — state-based trailing. Ratchet-only (never loosens SL).
//     // STATE 0: initial SL.
//     // STATE 1 (>=0.5R): wide trail active (2.25 ATR) but no aggressive move yet.
//     // STATE 2 (>=1R):   small locked-profit buffer set (not pure breakeven),
//     //                   trail tightens to 1.75 ATR.
//     // STATE 3 (>=1.5R): trail tightens further to 1.35 ATR.
//     // STATE 4 (>=2R):   same tight trail — lets strong trends keep running.
//     // TP extension only fires if trendStillHealthy() confirms 15M still agrees.
//     // =========================================================================
//     private static void updateTrailingForOpenPositions() {
//         for (Map.Entry<String, TrailInfo> e : trailState.entrySet()) {
//             String pair = e.getKey();
//             TrailInfo ti = e.getValue();
//             try {
//                 JSONObject pos = findPosition(pair);
//                 if (pos == null) continue;

//                 double currentPrice = getLastPrice(pair);
//                 if (currentPrice <= 0) continue;

//                 JSONArray raw5m = dropLastIfForming(getCandlestickData(pair, RES_5M, BASE_5M_FETCH_COUNT));
//                 if (raw5m == null || raw5m.length() < ATR_PERIOD + 5) continue;
//                 double[] hi5m = extractHighs(raw5m);
//                 double[] lo5m = extractLows(raw5m);
//                 double[] cl5m = extractCloses(raw5m);
//                 double atr5m = calcATR(hi5m, lo5m, cl5m, ATR_PERIOD);
//                 if (atr5m <= 0) continue;

//                 double favorableMove = ti.isLong ? currentPrice - ti.entryPrice : ti.entryPrice - currentPrice;
//                 double favorableR = ti.initialRisk > 0 ? favorableMove / ti.initialRisk : 0;

//                 if (ti.isLong) ti.peak = Math.max(ti.peak, currentPrice);
//                 else ti.peak = Math.min(ti.peak, currentPrice);

//                 int newState = favorableR >= 2.0 ? 4 : favorableR >= 1.5 ? 3 : favorableR >= 1.0 ? 2 : favorableR >= 0.5 ? 1 : 0;
//                 boolean changed = false;
//                 double tick = getTickSize(pair);

//                 if (newState >= 2 && ti.state < 2) {
//                     // Reaching +1R: lock a small profit rather than pure breakeven.
//                     double lockedSl = ti.isLong
//                             ? roundToTick(ti.entryPrice * (1 + BREAKEVEN_BUFFER_PCT / 100.0), tick)
//                             : roundToTick(ti.entryPrice * (1 - BREAKEVEN_BUFFER_PCT / 100.0), tick);
//                     if ((ti.isLong && lockedSl > ti.currentSl) || (!ti.isLong && lockedSl < ti.currentSl)) {
//                         ti.currentSl = lockedSl;
//                         changed = true;
//                     }
//                 }
//                 if (newState > ti.state) ti.state = newState;

//                 double trailMult = ti.state >= 3 ? TRAIL_ATR_STAGE3 : ti.state == 2 ? TRAIL_ATR_STAGE2 : TRAIL_ATR_EARLY;
//                 if (ti.state >= 1) {
//                     double candidateSl = ti.isLong
//                             ? roundToTick(ti.peak - trailMult * atr5m, tick)
//                             : roundToTick(ti.peak + trailMult * atr5m, tick);
//                     if (ti.isLong && candidateSl > ti.currentSl) { ti.currentSl = candidateSl; changed = true; }
//                     if (!ti.isLong && candidateSl < ti.currentSl) { ti.currentSl = candidateSl; changed = true; }
//                 }

//                 if (ti.extensionsUsed < TP_MAX_EXTENSIONS) {
//                     double distToTp = ti.isLong ? ti.currentTp - ti.entryPrice : ti.entryPrice - ti.currentTp;
//                     double triggerLevel = ti.isLong
//                             ? ti.entryPrice + TP_EXTEND_TRIGGER_FRACTION * distToTp
//                             : ti.entryPrice - TP_EXTEND_TRIGGER_FRACTION * distToTp;
//                     boolean nearTp = ti.isLong ? currentPrice >= triggerLevel : currentPrice <= triggerLevel;
//                     if (distToTp > 0 && nearTp && trendStillHealthy(pair, ti.isLong)) {
//                         double newTp = ti.isLong
//                                 ? roundToTick(currentPrice + ti.initialReward, tick)
//                                 : roundToTick(currentPrice - ti.initialReward, tick);
//                         if ((ti.isLong && newTp > ti.currentTp) || (!ti.isLong && newTp < ti.currentTp)) {
//                             ti.currentTp = newTp;
//                             ti.extensionsUsed++;
//                             changed = true;
//                         }
//                     }
//                 }

//                 if (changed) {
//                     double[] clamped = sanityClampSlTp(ti.isLong, currentPrice, ti.currentSl, ti.currentTp, tick);
//                     ti.currentSl = clamped[0];
//                     ti.currentTp = clamped[1];
//                     String posId = pos.optString("id", null);
//                     if (posId != null) {
//                         System.out.printf("  [TRAIL] %s state=%d SL=%.6f TP=%.6f (ext=%d/%d, R=%.2f)%n",
//                                 pair, ti.state, ti.currentSl, ti.currentTp, ti.extensionsUsed, TP_MAX_EXTENSIONS, favorableR);
//                         setTpSlWithRetry(posId, ti.currentTp, ti.currentSl, pair);
//                     }
//                 }
//             } catch (Exception ex) {
//                 System.err.println("updateTrailingForOpenPositions(" + pair + "): " + ex.getMessage());
//             }
//         }
//     }

//     // PART 11 — before extending TP, re-check that the 15M trend still agrees.
//     // If unsure (data issue), do NOT extend — let the trailing SL protect what's banked.
//     private static boolean trendStillHealthy(String pair, boolean isLong) {
//         try {
//             JSONArray raw5m = dropLastIfForming(getCandlestickData(pair, RES_5M, BASE_5M_FETCH_COUNT));
//             if (raw5m == null || raw5m.length() < EMA_MID + ST_PERIOD + STRUCTURE_SWING_LOOKBACK) return false;
//             JSONArray raw15m = aggregateCandles(raw5m, GROUP_15M_FROM_5M);
//             SetupResult s15 = analyzeSetup15M(raw15m);
//             if (!s15.valid) return false;
//             return isLong ? s15.bullish : s15.bearish;
//         } catch (Exception e) {
//             return false;
//         }
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
//                 JSONArray raw5m = dropLastIfForming(getCandlestickData(pair, RES_5M, BASE_5M_FETCH_COUNT));
//                 if (raw5m == null || raw5m.length() < EMA_MID + ST_PERIOD + STRUCTURE_SWING_LOOKBACK) {
//                     System.out.println("  [SWEEP] insufficient 5M data for " + pair + " — will retry next run");
//                     continue;
//                 }

//                 JSONArray raw15mSweep = aggregateCandles(raw5m, GROUP_15M_FROM_5M);
//                 SetupResult setup15Sweep = analyzeSetup15M(raw15mSweep);
//                 if (!setup15Sweep.valid) {
//                     System.out.println("  [SWEEP] insufficient 15M data for " + pair + " — will retry next run");
//                     continue;
//                 }

//                 double[] hi5m = extractHighs(raw5m);
//                 double[] lo5m = extractLows(raw5m);
//                 double[] cl5m = extractCloses(raw5m);
//                 double atr5m = calcATR(hi5m, lo5m, cl5m, ATR_PERIOD);
//                 if (atr5m <= 0) {
//                     System.out.println("  [SWEEP] invalid 5M ATR for " + pair + " — will retry next run");
//                     continue;
//                 }

//                 double posQty = pos.optDouble("active_pos", 0);
//                 boolean isLong = posQty >= 0;
//                 double stLevel = isLong ? setup15Sweep.stLower : setup15Sweep.stUpper;
//                 double tick = getTickSize(pair);

//                 double[] slTp = computeStructuralSlTp(isLong, avgPrice, hi5m, lo5m, atr5m, stLevel, tick, false);
//                 double sl, tp;
//                 if (slTp == null) {
//                     sl = isLong ? avgPrice * (1 - SL_HARD_PERCENT_CAP / 100.0) : avgPrice * (1 + SL_HARD_PERCENT_CAP / 100.0);
//                     tp = isLong ? avgPrice + RR_TARGET_BASE * (avgPrice - sl) : avgPrice - RR_TARGET_BASE * (sl - avgPrice);
//                     System.out.println("  [SWEEP] structural SL rejected for " + pair + " — using hard % fallback cap");
//                 } else {
//                     sl = slTp[0];
//                     tp = slTp[1];
//                 }
//                 double[] clamped = sanityClampSlTp(isLong, avgPrice, sl, tp, tick);
//                 sl = clamped[0];
//                 tp = clamped[1];

//                 String posId = pos.optString("id", null);
//                 if (posId != null) {
//                     System.out.printf("  [SWEEP] %s fallback SL=%.6f TP=%.6f%n", pair, sl, tp);
//                     setTpSlWithRetry(posId, tp, sl, pair);

//                     if (TRAILING_ENABLED && !trailState.containsKey(pair)) {
//                         TrailInfo ti = new TrailInfo();
//                         ti.isLong = isLong;
//                         ti.entryPrice = avgPrice;
//                         ti.initialRisk = Math.abs(avgPrice - sl);
//                         ti.initialReward = Math.abs(tp - avgPrice);
//                         ti.currentSl = sl;
//                         ti.currentTp = tp;
//                         ti.peak = avgPrice;
//                         ti.state = 0;
//                         ti.extensionsUsed = 0;
//                         trailState.put(pair, ti);
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

//     private static double[] calcEMASeries(double[] d, int period) {
//         double[] out = new double[d.length];
//         if (d.length < period) {
//             double last = d.length > 0 ? d[d.length - 1] : 0;
//             Arrays.fill(out, last);
//             return out;
//         }
//         double k = 2.0 / (period + 1);
//         double ema = 0;
//         for (int i = 0; i < period; i++) ema += d[i];
//         ema /= period;
//         for (int i = 0; i < period; i++) out[i] = ema;
//         out[period - 1] = ema;
//         for (int i = period; i < d.length; i++) {
//             ema = d[i] * k + ema * (1 - k);
//             out[i] = ema;
//         }
//         return out;
//     }

//     private static double calcEMA(double[] d, int period) {
//         if (d.length < period) return 0;
//         double k = 2.0 / (period + 1), ema = 0;
//         for (int i = 0; i < period; i++) ema += d[i];
//         ema /= period;
//         for (int i = period; i < d.length; i++) ema = d[i] * k + ema * (1 - k);
//         return ema;
//     }

//     private static double calcRSI(double[] closes, int period) {
//         if (closes.length < period + 1) return 50.0;

//         double avgGain = 0, avgLoss = 0;
//         for (int i = 1; i <= period; i++) {
//             double change = closes[i] - closes[i - 1];
//             if (change > 0) avgGain += change;
//             else avgLoss += -change;
//         }
//         avgGain /= period;
//         avgLoss /= period;

//         for (int i = period + 1; i < closes.length; i++) {
//             double change = closes[i] - closes[i - 1];
//             double gain = Math.max(change, 0);
//             double loss = Math.max(-change, 0);
//             avgGain = (avgGain * (period - 1) + gain) / period;
//             avgLoss = (avgLoss * (period - 1) + loss) / period;
//         }

//         if (avgLoss == 0) return 100.0;
//         double rs = avgGain / avgLoss;
//         return 100.0 - (100.0 / (1.0 + rs));
//     }

//     private static BigDecimal roundToTickBD(double price, double tick) {
//         if (tick <= 0) return BigDecimal.valueOf(price);
//         BigDecimal bdPrice = BigDecimal.valueOf(price);
//         BigDecimal bdTick  = BigDecimal.valueOf(tick);
//         BigDecimal multiples = bdPrice.divide(bdTick, 0, RoundingMode.HALF_UP);
//         BigDecimal result = multiples.multiply(bdTick);
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
//     private static double[] extractVolumes(JSONArray a) {
//         double[] o = new double[a.length()];
//         for (int i = 0; i < a.length(); i++) o[i] = a.getJSONObject(i).optDouble("volume", 0);
//         return o;
//     }

//     private static JSONArray getCandlestickData(String pair, String resolution, int count) {
//         try {
//             long minsPerBar;
//             switch (resolution) {
//                 case "1":   minsPerBar = 1;   break;
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
//             double volSum = 0;
//             for (int j = i; j < i + groupSize; j++) {
//                 JSONObject c = source.getJSONObject(j);
//                 high = Math.max(high, c.getDouble("high"));
//                 low  = Math.min(low,  c.getDouble("low"));
//                 volSum += c.optDouble("volume", 0);
//             }
//             JSONObject merged = new JSONObject();
//             merged.put("open", open);
//             merged.put("close", close);
//             merged.put("high", high);
//             merged.put("low", low);
//             merged.put("volume", volSum);
//             result.put(merged);
//         }
//         return result;
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

//     private static final double MAX_MARGIN = 1200.0;
//     private static final int LEVERAGE = 6;

//     private static final int MAX_ENTRY_PRICE_CHECKS = 20;
//     private static final int ENTRY_CHECK_DELAY_MS    = 1000;

//     private static final int  TPSL_MAX_RETRIES    = 3;
//     private static final long TPSL_RETRY_DELAY_MS = 2000L;

//     private static final long TICK_CACHE_TTL_MS = 3_600_000L;

//     private static final int MAX_OPEN_POSITIONS = 120;

//     private static final int  POSITION_ID_MAX_RETRIES = 5;
//     private static final long POSITION_ID_RETRY_DELAY_MS = 1500L;

//     // =========================================================================
//     // Direction -> 1H+30M, Setup -> 15M, Entry -> 5M.
//     // =========================================================================
//     private static final int EMA_FAST = 9;
//     private static final int EMA_MID  = 21;
//     private static final int ATR_PERIOD = 14;
//     private static final int ST_PERIOD     = 10;
//     private static final double ST_MULTIPLIER = 3.0;

//     private static final String RES_5M = "5";
//     private static final String RES_1H = "60";

//     private static final int BASE_5M_FETCH_COUNT = 250;
//     private static final int GROUP_15M_FROM_5M = 3;
//     private static final int GROUP_30M_FROM_5M = 6;
//     private static final int BASE_1H_FETCH_COUNT = 55;

//     // ---- RSI (momentum filter) ----
//     private static final int RSI_PERIOD = 14;
//     private static final double RSI_LONG_MIN  = 45, RSI_LONG_MAX  = 68;
//     private static final double RSI_SHORT_MIN = 32, RSI_SHORT_MAX = 55;

//     // ---- Entry (5M) filter thresholds ----
//     private static final int    ENTRY_VOLUME_LOOKBACK   = 20;
//     private static final double ENTRY_VOLUME_MULTIPLIER = 1.20;

//     private static final double ENTRY_PULLBACK_MAX_ATR  = 0.6;
//     private static final double ENTRY_MIN_BODY_RATIO    = 0.40;

//     private static final int    ENTRY_VWAP_LOOKBACK      = 20;
//     private static final double ENTRY_MAX_VWAP_DIST_ATR  = 0.4;

//     private static final int ENTRY_CONFIRMATION_MIN_SCORE = 2;

//     // ---- SL/TP sizing — set once at entry, then handed off to the
//     // trailing layer below (see TRAIL_* constants). The initial values
//     // stay exactly as before. ----
//     private static final int    SWING_LOOKBACK_BARS = 20;
//     private static final double SL_ATR_BUFFER_MULT  = 4.0;
//     private static final double SL_MAX_PERCENT = 6.0;
//     private static final double RR_TARGET = 1.2;

//     private static final double LIMIT_ORDER_BUFFER_PCT = 0.0005;

//     private static final long SCALP_COOLDOWN_MS            = 5 * 60 * 1000L;
//     private static final long SCALP_ENTRY_SCAN_INTERVAL_MS = 20 * 1000L;

//     private static final int    EMA_SLOPE_LOOKBACK_BARS = 5;
//     private static final double HTF_EMA_SLOPE_MIN_ATR   = 0.10;
//     private static final double ENTRY_EMA_SLOPE_MIN_ATR = 0.15;

//     // =========================================================================
//     // NEW — Trailing layer (Chandelier SL + step-wise TP extension).
//     // This sits ON TOP of the existing fixed SL/TP (which is still set once
//     // at entry via computeSlTp(), unchanged). This layer only ever moves
//     // SL/TP in the favorable direction — never loosens them.
//     //
//     // Kept deliberately "balanced": not tight enough to get clipped by
//     // ordinary 5M noise, not so wide that it barely trails at all.
//     // =========================================================================
//     private static final boolean TRAILING_ENABLED = true;

//     // How far behind the peak/trough the trailing SL sits, in ATR multiples.
//     // 2.0x is wider than the entry-side pullback checks (0.6x) — trailing SL
//     // is meant to survive normal pullbacks within a live trend, not react to
//     // every small wick like an entry filter does.
//     private static final double TRAIL_ATR_MULT = 2.0;

//     // Trailing only starts once price has moved this many ATRs in favor of
//     // the trade. Without this, the very first tick of favorable movement
//     // would already start dragging SL up/down, effectively tightening the
//     // original SL far too early (before the trade has proven itself).
//     private static final double TRAIL_ACTIVATION_ATR = 0.5;

//     // Once price covers this fraction of the distance from entry to the
//     // CURRENT TP, TP is pushed forward by exactly one more "original reward"
//     // step and then re-fixed. This check is always against the CURRENT
//     // (possibly already-extended) TP — never the original one — so each
//     // extension requires fresh, continued progress. This is what avoids the
//     // earlier "TP recomputed every cycle -> never reachable" bug.
//     private static final double TP_EXTEND_TRIGGER_FRACTION = 0.85;

//     // Safety cap so a runaway trend can't extend TP indefinitely inside one
//     // trade (kept generous — this is a circuit breaker, not a target).
//     private static final int TP_MAX_EXTENSIONS = 5;

//     // Per-pair trailing state, held in memory. Reset whenever a pair has no
//     // open position (checked each cycle against getActivePositions()).
//     private static class TrailInfo {
//         boolean isLong;
//         double entryPrice;
//         double initialRisk;   // |entry - initial SL| at trade open
//         double initialReward; // |initial TP - entry| at trade open
//         double currentSl;
//         double currentTp;
//         double peak;          // best price seen so far (high for long, low for short)
//         int    extensionsUsed;
//     }
//     private static final Map<String, TrailInfo> trailState = new ConcurrentHashMap<>();

//     private static final Map<String, JSONObject> instrumentCache = new ConcurrentHashMap<>();
//     private static long lastCacheUpdate = 0;
//     private static final Map<String, Long> lastTradeTime = new ConcurrentHashMap<>();

//     private static final String[] COIN_SYMBOLS = {
//        "PIEVERSE","XAU","APE","ERA","US","RAVE","EDEN","LIT","BREV","MAGMA","BLESS","ZAMA",
//         "FRAX","ACU","1000FLOKI","ELSA","LINEA","SPACE","CLO","FIGHT","UMA","MEGA","MAV","TRIA",
//         "YGG","OPN","ROBO","SUI","GLM","MANTRA","SEI","CAKE","AUCTION","SENT","BSB","BASED","IRYS",
//         "ACE","WET","CL","PRL","GENIUS","WIF","MANTA","LSK","AIGENSYN","PHAROS","JUP","AXL","BOME",
//         "SLX","ZEST","AIOT","VVV","CAP","DATAIP","GRVT","TAO","BR","TURBO","BTC","ETH","ZK","LISTA",
//         "A","LTC","XAG","COAI","HANA","ZRO","SKYAI","COPPER","RARE","ETC","M","AKE","XLM","PIXEL",
//         "XAN","ADA","CROSS","XMR","G","DASH","ZEC","ATOM","TRUTH","BCH","NEO","IOST","FLUX","ALGO",
//         "ZRX","COMP","WLFI","POL","DOGE","BAND","OPG","FIDA","PROM","SANTOS","RLC","1000000MOG",
//         "GRASS","PNUT","TRB","KAIA","ARX","XAI","S","4","COTI","CHR","SOLV","SAGA","ORCA","1000LUNC",
//         "MOVE","VIRTUAL","ME","IOTX","GIGGLE","AVA","VELODROME","AIXBT","KMNO","LA","DEXE","ZBT",
//         "GRIFFAIN","BLUAI","CTSI","ROSE","TURTLE","IMX","SUN","APR","TA","ON","BIO","COOKIE",
//         "AVAAI","DOT","TRUMP","MELANIA","GMT","FLOCK","CLANKER","CYS","SUSHI","VTHO","DIA",
//         "SLP","GOAT","BMT","KGEN","GWEI","MUBARAK","LDO","ESP","DRIFT","FORM","PLUME","NIL",
//         "UNI","ZORA","RECALL","INIT","BZ","PARTI","NATGAS","SPX","BANK","AVAX","RIVER","BILL",
//         "ATH","XRP","KERNEL","JST","PUNDIX","HAEDAL","ALPINE","SOON","SOPH","HUMA","TRX","LINK",
//         "HYPE","HIVE","TAIKO","TAG","MYX","NEWT","AIN","USUAL","PUMP","ICNT","BNB","H","BAT",
//         "QTUM","ARC","AIO","BEAT","BTR","ALCH","THETA","VELVET","ARIA","PTB","UB","LIGHT","FF",
//         "EVAAI","GMX","LYN","TAC","LAB","ENJ","AT","MMT","UAI","AAVE","JCT","KSM","HEI","JASMY",
//         "NEAR","TST","SOL","OP","PLAY","INJ","STG","HOLO","ASR","B","LUNA2","RSR","INX","KAT",
//         "ICP","QNT","MAGIC","T","MINA","STX","ACH","LQTY","ID","GRT","NEIRO","XVS","1INCH","SAND",
//         "ANKR","RVN","SFP","KAVA","MANA","HBAR","ARB","MTL","C98","TUT","SIREN","MASK","1000XEC",
//         "AR","ARPA","FIL","LPT","ENS","PEOPLE","LUMIA","DUSK","FLOW","XVG","ARKM","POPCAT","ARK",
//         "MOODENG","SAFE","AXS","BICO","BIGTIME","WAXP","GAS","POWR","TIA","CHIP","STO","ORDI",
//         "BEAMX","1000BONK","PYTH","ETHW","1000RATS","ANIME","OPEN","DYM","BERA","PORTAL","BB",
//         "BANANAS31","CFX","SSV","TNSR","EDU","JELLYJELLY","BLUR","WAL","FHE","WCT","DEEP","SXT",
//         "NAORIS","OG","CVC","AWE","O","BEL","JOE","SQD","1000PEPE","CARV","FET","SAPIEN","MEME",
//         "AVNT","XPIN","ILV","KAS","BNT","STBL","BSV","RIF","SUPER","USTC","METIS","ETHFI","ENA",
//         "1MBABYDOGE","CATI","HMSTR","GPS","SHELL","KAITO","ACT","RPL","BAN","THE","AKT","MORPHO",
//         "CHILLGUY","AERO","MOCA","PENGU","PHA","RED","EPIC","TREE","1000CAT","MAVIA","FARTCOIN",
//         "PAXG","IN","ORDER","VET","ZEN","STABLE","CHZ","NIGHT","NOM","ZKP","SKR","GRAM","BIRB",
//         "CTR","KNC","ZIL","YFI","EGLD","RUNE","ASTR","ONE","1000SHIB","API3","SPELL","WOO","APT",
//         "PENDLE","AGLD","CYBER","CKB","ONG","MOVR","POLYX","TWT","STEEM","ALT","ZETA","REZ","RENDER",
//         "RONIN","STRK","W","SCR","CETUS","IO","MEW","SWARMS","SONIC","PIPPIN","PROMPT","MERL","F",
//         "ESPORTS","PROVE","XNY","USELESS","HEMI","Q","SKY","ZKC","FLUID","MITO","CFG","EDGE","RE",
//         "YB","MET","DOS","FOGO","BTW","ALLO","BROCCOLI714","HYPER","XPL","RESOLV","ASTER","KITE",
//         "SIGN","HOME","MON","CC","SAHARA","MIRA","EUL","TOWNS","SYRUP","C","DOLO","ALICE","BABY",
//         "SOMI","NOT","BARD","SPK","POWER","2Z","BANANA","ENSO","SYN","NXPC","GUN","XTZ","ONT","SKL",
//         "HOT","JTO","DOGS","EIGEN","GTC","GALA","NMR","CGPT","ZEREBRO","VANA","OGN","CELO","USDC",
//         "COW","0G","IOTA","SNX","DYDX","WLD","1000SATS","ONDO","AEVO","BRETT","LAYER","CRV","TLM","KOMA"
//     };

//     private static final Set<String> INTEGER_QTY_PAIRS = Stream.of(COIN_SYMBOLS)
//             .flatMap(s -> Stream.of("B-" + s + "_USDT", s + "_USDT"))
//             .collect(Collectors.toCollection(HashSet::new));

//     private static final String[] COINS_TO_TRADE = Stream.of(COIN_SYMBOLS)
//             .map(s -> "B-" + s + "_USDT")
//             .toArray(String[]::new);

//     private static class DirectionResult {
//         boolean valid;
//         boolean bullish;
//         boolean bearish;
//     }

//     private static double[] calcEMASeries(double[] d, int period) {
//         double[] out = new double[d.length];
//         if (d.length < period) {
//             double last = d.length > 0 ? d[d.length - 1] : 0;
//             Arrays.fill(out, last);
//             return out;
//         }
//         double k = 2.0 / (period + 1);
//         double ema = 0;
//         for (int i = 0; i < period; i++) ema += d[i];
//         ema /= period;
//         for (int i = 0; i < period; i++) out[i] = ema;
//         out[period - 1] = ema;
//         for (int i = period; i < d.length; i++) {
//             ema = d[i] * k + ema * (1 - k);
//             out[i] = ema;
//         }
//         return out;
//     }

//     private static DirectionResult analyzeDirection(JSONArray candles) {
//         DirectionResult r = new DirectionResult();
//         if (candles == null || candles.length() < EMA_MID + Math.max(ATR_PERIOD, ST_PERIOD) + 5) {
//             r.valid = false;
//             return r;
//         }
//         double[] cl = extractCloses(candles);
//         double[] hi = extractHighs(candles);
//         double[] lo = extractLows(candles);

//         double ema9  = calcEMA(cl, EMA_FAST);
//         double ema21 = calcEMA(cl, EMA_MID);
//         double atr   = calcATR(hi, lo, cl, ATR_PERIOD);
//         double price = cl[cl.length - 1];

//         boolean[] stSeries = calcSupertrend(hi, lo, cl, ST_PERIOD, ST_MULTIPLIER);
//         boolean stGreen = stSeries[stSeries.length - 1];

//         double[] ema9Series = calcEMASeries(cl, EMA_FAST);
//         int n = ema9Series.length;
//         int lookback = Math.min(EMA_SLOPE_LOOKBACK_BARS, n - 1);
//         double emaSlope = ema9Series[n - 1] - ema9Series[n - 1 - lookback];
//         boolean slopeUp   = atr > 0 && emaSlope >= HTF_EMA_SLOPE_MIN_ATR * atr;
//         boolean slopeDown = atr > 0 && emaSlope <= -HTF_EMA_SLOPE_MIN_ATR * atr;

//         boolean priceAboveBoth = price > ema9 && price > ema21;
//         boolean priceBelowBoth = price < ema9 && price < ema21;

//         r.valid = true;
//         r.bullish = (ema9 > ema21) && slopeUp && stGreen && priceAboveBoth;
//         r.bearish = (ema9 < ema21) && slopeDown && !stGreen && priceBelowBoth;
//         return r;
//     }

//     private static class SetupResult {
//         boolean valid;
//         boolean bullish;
//         boolean bearish;
//         double  atr;
//         double  stLower;
//         double  stUpper;
//     }

//     private static SetupResult analyzeSetup(JSONArray candles) {
//         SetupResult r = new SetupResult();
//         if (candles == null || candles.length() < EMA_MID + ST_PERIOD + 5) {
//             r.valid = false;
//             return r;
//         }
//         double[] cl = extractCloses(candles);
//         double[] hi = extractHighs(candles);
//         double[] lo = extractLows(candles);

//         double ema9  = calcEMA(cl, EMA_FAST);
//         double ema21 = calcEMA(cl, EMA_MID);
//         double price = cl[cl.length - 1];
//         r.atr = calcATR(hi, lo, cl, ATR_PERIOD);

//         boolean[] stSeries = calcSupertrend(hi, lo, cl, ST_PERIOD, ST_MULTIPLIER);
//         boolean stGreen = stSeries[stSeries.length - 1];

//         double[] bands = calcSupertrendBands(hi, lo, cl, ST_PERIOD, ST_MULTIPLIER);
//         r.stLower = bands[0];
//         r.stUpper = bands[1];

//         double[] ema9Series = calcEMASeries(cl, EMA_FAST);
//         int n = ema9Series.length;
//         int lookback = Math.min(EMA_SLOPE_LOOKBACK_BARS, n - 1);
//         double emaSlope = ema9Series[n - 1] - ema9Series[n - 1 - lookback];
//         boolean slopeUp   = r.atr > 0 && emaSlope >= HTF_EMA_SLOPE_MIN_ATR * r.atr;
//         boolean slopeDown = r.atr > 0 && emaSlope <= -HTF_EMA_SLOPE_MIN_ATR * r.atr;

//         boolean priceAboveBoth = price > ema9 && price > ema21;
//         boolean priceBelowBoth = price < ema9 && price < ema21;

//         r.valid = true;
//         r.bullish = (ema9 > ema21) && stGreen && slopeUp && priceAboveBoth;
//         r.bearish = (ema9 < ema21) && !stGreen && slopeDown && priceBelowBoth;
//         return r;
//     }

//     private static class EntryResult {
//         boolean valid;
//         boolean triggered;
//         double entryClose, entryOpen, entryHigh, entryLow;
//         double atr5m;
//         String reason;
//     }

//     private static EntryResult analyzeEntry(JSONArray raw5m, boolean trendUp) {
//         EntryResult t = new EntryResult();
//         int minBars = Math.max(EMA_MID, Math.max(ENTRY_VOLUME_LOOKBACK, ENTRY_VWAP_LOOKBACK)) + RSI_PERIOD + 5;
//         if (raw5m == null || raw5m.length() < minBars) {
//             t.valid = false;
//             return t;
//         }

//         double[] cl  = extractCloses(raw5m);
//         double[] op  = extractOpens(raw5m);
//         double[] hi  = extractHighs(raw5m);
//         double[] lo  = extractLows(raw5m);
//         double[] vol = extractVolumes(raw5m);
//         int n = cl.length;

//         double ema9  = calcEMA(cl, EMA_FAST);
//         double ema21 = calcEMA(cl, EMA_MID);
//         double atr5m = calcATR(hi, lo, cl, ATR_PERIOD);
//         t.atr5m = atr5m;

//         double entryClose = cl[n - 1], entryOpen = op[n - 1];
//         double entryHigh  = hi[n - 1], entryLow  = lo[n - 1];
//         t.entryClose = entryClose; t.entryOpen = entryOpen;
//         t.entryHigh = entryHigh;   t.entryLow = entryLow;

//         double distToEma9  = Math.abs(entryClose - ema9);
//         double distToEma21 = Math.abs(entryClose - ema21);
//         double nearestEmaDist = Math.min(distToEma9, distToEma21);
//         boolean pulledBack = atr5m > 0 && nearestEmaDist <= ENTRY_PULLBACK_MAX_ATR * atr5m;

//         boolean directionalCandle = trendUp ? (entryClose > entryOpen) : (entryClose < entryOpen);
//         double body  = Math.abs(entryClose - entryOpen);
//         double range = entryHigh - entryLow;
//         boolean notDoji = range > 0 && (body / range) >= ENTRY_MIN_BODY_RATIO;

//         double wickToEma = trendUp
//                 ? Math.min(Math.abs(entryLow - ema9), Math.abs(entryLow - ema21))
//                 : Math.min(Math.abs(entryHigh - ema9), Math.abs(entryHigh - ema21));
//         boolean wickTouchedZone = atr5m > 0 && wickToEma <= ENTRY_PULLBACK_MAX_ATR * atr5m;
//         double closePositionInRange = range > 0
//                 ? (trendUp ? (entryClose - entryLow) / range : (entryHigh - entryClose) / range)
//                 : 0;
//         boolean rejectionOk = wickTouchedZone && closePositionInRange >= 0.6;

//         double[] ema9Series5m = calcEMASeries(cl, EMA_FAST);
//         int lookback5m = Math.min(EMA_SLOPE_LOOKBACK_BARS, n - 1);
//         double emaSlope5m = ema9Series5m[n - 1] - ema9Series5m[n - 1 - lookback5m];
//         boolean slope5mOk = trendUp
//                 ? (atr5m > 0 && emaSlope5m >= ENTRY_EMA_SLOPE_MIN_ATR * atr5m)
//                 : (atr5m > 0 && emaSlope5m <= -ENTRY_EMA_SLOPE_MIN_ATR * atr5m);

//         boolean mandatoryOk = pulledBack && rejectionOk && directionalCandle && notDoji && slope5mOk;

//         int volStart = Math.max(0, n - 1 - ENTRY_VOLUME_LOOKBACK);
//         double avgVol = 0; int cnt = 0;
//         for (int i = volStart; i < n - 1; i++) { avgVol += vol[i]; cnt++; }
//         avgVol = cnt > 0 ? avgVol / cnt : 0;
//         boolean volumeOk = avgVol > 0 && vol[n - 1] >= avgVol * ENTRY_VOLUME_MULTIPLIER;

//         double rsi = calcRSI(cl, RSI_PERIOD);
//         boolean rsiOk = trendUp
//                 ? (rsi >= RSI_LONG_MIN && rsi <= RSI_LONG_MAX)
//                 : (rsi >= RSI_SHORT_MIN && rsi <= RSI_SHORT_MAX);

//         int vwapStart = Math.max(0, n - ENTRY_VWAP_LOOKBACK);
//         double cumPV = 0, cumV = 0;
//         for (int i = vwapStart; i < n; i++) {
//             double typical = (hi[i] + lo[i] + cl[i]) / 3.0;
//             cumPV += typical * vol[i];
//             cumV  += vol[i];
//         }
//         double vwap = cumV > 0 ? cumPV / cumV : entryClose;
//         double distFromVwap = Math.abs(entryClose - vwap);
//         boolean vwapOk = atr5m > 0 && distFromVwap <= ENTRY_MAX_VWAP_DIST_ATR * atr5m;

//         int confirmationScore = (volumeOk ? 1 : 0) + (rsiOk ? 1 : 0) + (vwapOk ? 1 : 0);

//         t.triggered = mandatoryOk && confirmationScore >= ENTRY_CONFIRMATION_MIN_SCORE;
//         t.valid = true;
//         t.reason = String.format(
//                 "mandatory[pulledBack=%s rejection=%s(closePos=%.2f) directional=%s notDoji=%s slope5m=%s(%.6f)] confirmation[volume=%s(%.2fx) rsi=%.1f(ok=%s) vwap=%s] score=%d/3",
//                 pulledBack, rejectionOk, closePositionInRange, directionalCandle, notDoji, slope5mOk, emaSlope5m,
//                 volumeOk, avgVol > 0 ? vol[n - 1] / avgVol : 0,
//                 rsi, rsiOk, vwapOk, confirmationScore);
//         return t;
//     }

//     private static JSONArray dropLastIfForming(JSONArray arr) {
//         if (arr == null || arr.length() < 2) return arr;
//         JSONArray out = new JSONArray();
//         for (int i = 0; i < arr.length() - 1; i++) out.put(arr.getJSONObject(i));
//         return out;
//     }

//     // =========================================================================
//     // Initial fixed SL/TP — UNCHANGED. This is still the one-time value set
//     // right after entry. The trailing layer (further below) takes over from
//     // here but never touches this calculation.
//     // =========================================================================
//     private static double[] computeSlTp(boolean isLong, double entryPrice,
//                                          double[] hi5m, double[] lo5m, double atr5m,
//                                          double stLevel, double tickSize) {
//         double sl, tp;
//         if (isLong) {
//             double swingLow = recentLow(lo5m, SWING_LOOKBACK_BARS);
//             double structuralLow = Math.min(swingLow, stLevel);
//             double raw = structuralLow - SL_ATR_BUFFER_MULT * atr5m;
//             double hardFloor = entryPrice * (1 - SL_MAX_PERCENT / 100.0);
//             sl = Math.max(raw, hardFloor);
//             double risk = entryPrice - sl;
//             tp = entryPrice + RR_TARGET * risk;
//         } else {
//             double swingHigh = recentHigh(hi5m, SWING_LOOKBACK_BARS);
//             double structuralHigh = Math.max(swingHigh, stLevel);
//             double raw = structuralHigh + SL_ATR_BUFFER_MULT * atr5m;
//             double hardCeil = entryPrice * (1 + SL_MAX_PERCENT / 100.0);
//             sl = Math.min(raw, hardCeil);
//             double risk = sl - entryPrice;
//             tp = entryPrice - RR_TARGET * risk;
//         }
//         sl = roundToTick(sl, tickSize);
//         tp = roundToTick(tp, tickSize);
//         return new double[]{sl, tp};
//     }

//     private static double recentLow(double[] lo, int lookback) {
//         int n = lo.length;
//         int start = Math.max(0, n - lookback);
//         double min = Double.POSITIVE_INFINITY;
//         for (int i = start; i < n; i++) min = Math.min(min, lo[i]);
//         return min;
//     }

//     private static double recentHigh(double[] hi, int lookback) {
//         int n = hi.length;
//         int start = Math.max(0, n - lookback);
//         double max = Double.NEGATIVE_INFINITY;
//         for (int i = start; i < n; i++) max = Math.max(max, hi[i]);
//         return max;
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

//     private static double calcQuantity(double price, String pair) {
//         double usdtInrRate = 98.0;
//         double qty = MAX_MARGIN / (price * usdtInrRate);
//         double finalQty = INTEGER_QTY_PAIRS.contains(pair)
//                 ? Math.floor(qty)
//                 : Math.floor(qty * 100) / 100.0;
//         return Math.max(finalQty, 0);
//     }

//     public static void main(String[] args) {
//         System.out.println("=== Scalp bot starting (fixed-entry SL/TP + Chandelier trailing layer) ===");
//         initInstrumentCache();

//         while (true) {
//             try {
//                 runEntryScan();
//             } catch (Throwable t) {
//                 System.err.println("[MAIN-LOOP] Uncaught error, continuing: " + t.getMessage());
//                 t.printStackTrace();
//             }

//             try {
//                 TimeUnit.MILLISECONDS.sleep(SCALP_ENTRY_SCAN_INTERVAL_MS);
//             } catch (InterruptedException ignored) {
//                 Thread.currentThread().interrupt();
//                 break;
//             }
//         }
//     }

//     private static void runEntryScan() {
//         Set<String> active = getActivePositions();
//         System.out.println("Active positions: " + active);

//         // NEW — drop trailing state for any pair that is no longer open
//         // (position closed via TP/SL/manual since last cycle).
//         trailState.keySet().removeIf(pair -> !active.contains(pair));

//         if (active.size() >= MAX_OPEN_POSITIONS) {
//             System.out.println("MAX_OPEN_POSITIONS (" + MAX_OPEN_POSITIONS +
//                     ") already reached (" + active.size() + " open) — skipping scan entirely.");
//             ensureTpSlForOpenPositions();
//             if (TRAILING_ENABLED) updateTrailingForOpenPositions();
//             return;
//         }

//         for (String pair : COINS_TO_TRADE) {
//             try {
//                 if (active.size() >= MAX_OPEN_POSITIONS) {
//                     System.out.println("MAX_OPEN_POSITIONS reached mid-scan — stopping.");
//                     break;
//                 }
//                 if (active.contains(pair)) continue;

//                 long lastTrade = lastTradeTime.getOrDefault(pair, 0L);
//                 if (System.currentTimeMillis() - lastTrade < SCALP_COOLDOWN_MS) continue;

//                 JSONArray raw5m = dropLastIfForming(
//                         getCandlestickData(pair, RES_5M, BASE_5M_FETCH_COUNT));
//                 if (raw5m == null || raw5m.length() < EMA_MID + ST_PERIOD + 5) continue;

//                 JSONArray raw30m = aggregateCandles(raw5m, GROUP_30M_FROM_5M);
//                 DirectionResult dir30m = analyzeDirection(raw30m);
//                 if (!dir30m.valid || (!dir30m.bullish && !dir30m.bearish)) continue;

//                 JSONArray raw15m = aggregateCandles(raw5m, GROUP_15M_FROM_5M);
//                 SetupResult setup15m = analyzeSetup(raw15m);
//                 if (!setup15m.valid) continue;

//                 boolean setupMatches30m = (dir30m.bullish && setup15m.bullish)
//                         || (dir30m.bearish && setup15m.bearish);
//                 if (!setupMatches30m) continue;

//                 boolean trendUp = dir30m.bullish;

//                 JSONArray raw1h = dropLastIfForming(
//                         getCandlestickData(pair, RES_1H, BASE_1H_FETCH_COUNT));
//                 DirectionResult dir1h = analyzeDirection(raw1h);
//                 if (!dir1h.valid) continue;

//                 boolean dir1hMatches = (trendUp && dir1h.bullish) || (!trendUp && dir1h.bearish);
//                 if (!dir1hMatches) continue;

//                 EntryResult entry5m = analyzeEntry(raw5m, trendUp);
//                 if (!entry5m.valid) continue;
//                 if (!entry5m.triggered) {
//                     continue;
//                 }

//                 System.out.println("\n==== " + pair + " ====");
//                 System.out.printf("  [1H] %s | [30M] %s | [15M] %s | [5M-Entry] %s%n",
//                         dir1h.bullish ? "BULLISH" : "BEARISH",
//                         trendUp ? "BULLISH" : "BEARISH",
//                         setup15m.bullish ? "BULLISH" : "BEARISH",
//                         entry5m.reason);

//                 String side = trendUp ? "buy" : "sell";
//                 System.out.println("  ╔══════════════════════════════════════════════════╗");
//                 System.out.println("  ║  SCALP TRIGGER → " + side.toUpperCase() + " " + pair);
//                 System.out.println("  ╚══════════════════════════════════════════════════╝");

//                 double currentPrice = getLastPrice(pair);
//                 if (currentPrice <= 0) continue;
//                 double qty = calcQuantity(currentPrice, pair);
//                 if (qty <= 0) continue;
//                 double tickSize = getTickSize(pair);

//                 System.out.printf("  Placing %s | price=%.6f | qty=%.4f | lev=%dx%n",
//                         side.toUpperCase(), currentPrice, qty, LEVERAGE);

//                 JSONObject resp = placeFuturesOrder(side, pair, qty, LEVERAGE,
//                         "email_notification", "isolated", "INR", currentPrice);
//                 if (resp == null || !resp.has("id")) {
//                     System.out.println("  Order failed: " + resp);
//                     continue;
//                 }

//                 System.out.println("  Order placed! id=" + resp.getString("id"));
//                 lastTradeTime.put(pair, System.currentTimeMillis());

//                 double entry = getEntryPrice(pair, resp.getString("id"));
//                 if (entry <= 0) {
//                     System.out.println("  Could not confirm entry within window — TP/SL will be handled by safety sweep");
//                     active.add(pair);
//                     continue;
//                 }

//                 System.out.printf("  Entry confirmed: %.6f%n", entry);

//                 double[] hi5m = extractHighs(raw5m);
//                 double[] lo5m = extractLows(raw5m);
//                 double stLevel = trendUp ? setup15m.stLower : setup15m.stUpper;
//                 double[] slTp = computeSlTp(trendUp, entry, hi5m, lo5m, entry5m.atr5m, stLevel, tickSize);
//                 double[] clamped = sanityClampSlTp(trendUp, entry, slTp[0], slTp[1], tickSize);
//                 double slPrice = clamped[0], tpPrice = clamped[1];
//                 double slPct = Math.abs(entry - slPrice) / entry * 100;
//                 double tpPct = Math.abs(tpPrice - entry) / entry * 100;

//                 System.out.printf("  SL=%.6f (%.3f%%) | TP=%.6f (%.3f%%) | RR target=%.2f%n",
//                         slPrice, slPct, tpPrice, tpPct, RR_TARGET);

//                 String posId = getPositionId(pair);
//                 if (posId != null) {
//                     setTpSlWithRetry(posId, tpPrice, slPrice, pair);
//                 } else {
//                     System.out.println("  Position ID not found after retries — TP/SL will be handled by safety sweep");
//                 }

//                 // NEW — seed trailing state for this pair with the initial
//                 // fixed SL/TP. The trailing layer will only move these
//                 // further in the favorable direction from here on.
//                 if (TRAILING_ENABLED) {
//                     TrailInfo ti = new TrailInfo();
//                     ti.isLong = trendUp;
//                     ti.entryPrice = entry;
//                     ti.initialRisk = Math.abs(entry - slPrice);
//                     ti.initialReward = Math.abs(tpPrice - entry);
//                     ti.currentSl = slPrice;
//                     ti.currentTp = tpPrice;
//                     ti.peak = entry;
//                     ti.extensionsUsed = 0;
//                     trailState.put(pair, ti);
//                 }

//                 active.add(pair);

//             } catch (Exception e) {
//                 System.err.println("Error on " + pair + ": " + e.getMessage());
//             }
//         }

//         System.out.println("\n=== Scalp scan complete ===");
//         ensureTpSlForOpenPositions();
//         if (TRAILING_ENABLED) updateTrailingForOpenPositions();
//     }

//     // =========================================================================
//     // NEW — Trailing layer.
//     //
//     // For every currently open, TrailInfo-tracked position:
//     //   1. Refresh ATR (5M) so the trail distance adapts to current volatility.
//     //   2. Update peak (best price since entry).
//     //   3. Once price has moved TRAIL_ACTIVATION_ATR beyond entry in the
//     //      favorable direction, compute a candidate Chandelier SL
//     //      (peak - TRAIL_ATR_MULT*ATR for longs, peak + ... for shorts) and
//     //      only move currentSl if it is BETTER (tighter risk) than what's
//     //      already set — SL never loosens.
//     //   4. If price has covered TP_EXTEND_TRIGGER_FRACTION of the distance
//     //      from entry to the CURRENT TP, push TP forward by one
//     //      initialReward step (capped at TP_MAX_EXTENSIONS) and re-fix it.
//     //   5. If either value changed, push the update to the exchange via
//     //      setTpSlWithRetry(), same as the rest of the bot already does.
//     // =========================================================================
//     private static void updateTrailingForOpenPositions() {
//         for (Map.Entry<String, TrailInfo> e : trailState.entrySet()) {
//             String pair = e.getKey();
//             TrailInfo ti = e.getValue();
//             try {
//                 JSONObject pos = findPosition(pair);
//                 if (pos == null) continue;

//                 double currentPrice = getLastPrice(pair);
//                 if (currentPrice <= 0) continue;

//                 JSONArray raw5m = dropLastIfForming(
//                         getCandlestickData(pair, RES_5M, BASE_5M_FETCH_COUNT));
//                 if (raw5m == null || raw5m.length() < ATR_PERIOD + 5) continue;
//                 double[] hi5m = extractHighs(raw5m);
//                 double[] lo5m = extractLows(raw5m);
//                 double[] cl5m = extractCloses(raw5m);
//                 double atr5m = calcATR(hi5m, lo5m, cl5m, ATR_PERIOD);
//                 if (atr5m <= 0) continue;

//                 boolean changed = false;
//                 double tick = getTickSize(pair);

//                 if (ti.isLong) {
//                     ti.peak = Math.max(ti.peak, currentPrice);
//                     double favorableMove = ti.peak - ti.entryPrice;

//                     if (favorableMove >= TRAIL_ACTIVATION_ATR * atr5m) {
//                         double candidateSl = roundToTick(ti.peak - TRAIL_ATR_MULT * atr5m, tick);
//                         if (candidateSl > ti.currentSl) {
//                             ti.currentSl = candidateSl;
//                             changed = true;
//                         }
//                     }

//                     if (ti.extensionsUsed < TP_MAX_EXTENSIONS) {
//                         double distToTp = ti.currentTp - ti.entryPrice;
//                         double triggerLevel = ti.entryPrice + TP_EXTEND_TRIGGER_FRACTION * distToTp;
//                         if (distToTp > 0 && currentPrice >= triggerLevel) {
//                             double newTp = roundToTick(currentPrice + ti.initialReward, tick);
//                             if (newTp > ti.currentTp) {
//                                 ti.currentTp = newTp;
//                                 ti.extensionsUsed++;
//                                 changed = true;
//                             }
//                         }
//                     }
//                 } else {
//                     ti.peak = Math.min(ti.peak, currentPrice); // "peak" = lowest price for shorts
//                     double favorableMove = ti.entryPrice - ti.peak;

//                     if (favorableMove >= TRAIL_ACTIVATION_ATR * atr5m) {
//                         double candidateSl = roundToTick(ti.peak + TRAIL_ATR_MULT * atr5m, tick);
//                         if (candidateSl < ti.currentSl) {
//                             ti.currentSl = candidateSl;
//                             changed = true;
//                         }
//                     }

//                     if (ti.extensionsUsed < TP_MAX_EXTENSIONS) {
//                         double distToTp = ti.entryPrice - ti.currentTp;
//                         double triggerLevel = ti.entryPrice - TP_EXTEND_TRIGGER_FRACTION * distToTp;
//                         if (distToTp > 0 && currentPrice <= triggerLevel) {
//                             double newTp = roundToTick(currentPrice - ti.initialReward, tick);
//                             if (newTp < ti.currentTp) {
//                                 ti.currentTp = newTp;
//                                 ti.extensionsUsed++;
//                                 changed = true;
//                             }
//                         }
//                     }
//                 }

//                 if (changed) {
//                     double[] clamped = sanityClampSlTp(ti.isLong, currentPrice, ti.currentSl, ti.currentTp, tick);
//                     ti.currentSl = clamped[0];
//                     ti.currentTp = clamped[1];
//                     String posId = pos.optString("id", null);
//                     if (posId != null) {
//                         System.out.printf("  [TRAIL] %s new SL=%.6f TP=%.6f (extensions=%d/%d)%n",
//                                 pair, ti.currentSl, ti.currentTp, ti.extensionsUsed, TP_MAX_EXTENSIONS);
//                         setTpSlWithRetry(posId, ti.currentTp, ti.currentSl, pair);
//                     }
//                 }
//             } catch (Exception ex) {
//                 System.err.println("updateTrailingForOpenPositions(" + pair + "): " + ex.getMessage());
//             }
//         }
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
//                 JSONArray raw5m = dropLastIfForming(
//                         getCandlestickData(pair, RES_5M, BASE_5M_FETCH_COUNT));

//                 if (raw5m == null || raw5m.length() < EMA_MID + ST_PERIOD + 5) {
//                     System.out.println("  [SWEEP] insufficient 5M data for " + pair
//                             + " — will retry next run");
//                     continue;
//                 }

//                 JSONArray raw15mSweep = aggregateCandles(raw5m, GROUP_15M_FROM_5M);
//                 SetupResult setup15mSweep = analyzeSetup(raw15mSweep);
//                 if (!setup15mSweep.valid) {
//                     System.out.println("  [SWEEP] insufficient 15M data for " + pair
//                             + " — will retry next run");
//                     continue;
//                 }

//                 double[] hi5m = extractHighs(raw5m);
//                 double[] lo5m = extractLows(raw5m);
//                 double[] cl5m = extractCloses(raw5m);
//                 double atr5m = calcATR(hi5m, lo5m, cl5m, ATR_PERIOD);
//                 if (atr5m <= 0) {
//                     System.out.println("  [SWEEP] invalid 5M ATR for " + pair
//                             + " — will retry next run");
//                     continue;
//                 }

//                 double posQty = pos.optDouble("active_pos", 0);
//                 boolean isLong = posQty >= 0;
//                 double stLevel = isLong ? setup15mSweep.stLower : setup15mSweep.stUpper;

//                 double tick = getTickSize(pair);
//                 double[] slTp = computeSlTp(isLong, avgPrice, hi5m, lo5m, atr5m, stLevel, tick);
//                 double[] clamped = sanityClampSlTp(isLong, avgPrice, slTp[0], slTp[1], tick);
//                 double sl = clamped[0], tp = clamped[1];

//                 String posId = pos.optString("id", null);
//                 if (posId != null) {
//                     System.out.printf("  [SWEEP] %s fallback SL=%.6f TP=%.6f (RR target=%.2f)%n", pair, sl, tp, RR_TARGET);
//                     setTpSlWithRetry(posId, tp, sl, pair);

//                     // NEW — if this pair had no trailing state yet (e.g. bot
//                     // restarted, or entry confirmation earlier failed and
//                     // fell through to the sweep), seed it now so trailing
//                     // still applies going forward.
//                     if (TRAILING_ENABLED && !trailState.containsKey(pair)) {
//                         TrailInfo ti = new TrailInfo();
//                         ti.isLong = isLong;
//                         ti.entryPrice = avgPrice;
//                         ti.initialRisk = Math.abs(avgPrice - sl);
//                         ti.initialReward = Math.abs(tp - avgPrice);
//                         ti.currentSl = sl;
//                         ti.currentTp = tp;
//                         ti.peak = avgPrice;
//                         ti.extensionsUsed = 0;
//                         trailState.put(pair, ti);
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

//     private static double calcRSI(double[] closes, int period) {
//         if (closes.length < period + 1) return 50.0;

//         double avgGain = 0, avgLoss = 0;
//         for (int i = 1; i <= period; i++) {
//             double change = closes[i] - closes[i - 1];
//             if (change > 0) avgGain += change;
//             else avgLoss += -change;
//         }
//         avgGain /= period;
//         avgLoss /= period;

//         for (int i = period + 1; i < closes.length; i++) {
//             double change = closes[i] - closes[i - 1];
//             double gain = Math.max(change, 0);
//             double loss = Math.max(-change, 0);
//             avgGain = (avgGain * (period - 1) + gain) / period;
//             avgLoss = (avgLoss * (period - 1) + loss) / period;
//         }

//         if (avgLoss == 0) return 100.0;
//         double rs = avgGain / avgLoss;
//         return 100.0 - (100.0 / (1.0 + rs));
//     }

//     private static BigDecimal roundToTickBD(double price, double tick) {
//         if (tick <= 0) return BigDecimal.valueOf(price);
//         BigDecimal bdPrice = BigDecimal.valueOf(price);
//         BigDecimal bdTick  = BigDecimal.valueOf(tick);
//         BigDecimal multiples = bdPrice.divide(bdTick, 0, RoundingMode.HALF_UP);
//         BigDecimal result = multiples.multiply(bdTick);
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
//     private static double[] extractVolumes(JSONArray a) {
//         double[] o = new double[a.length()];
//         for (int i = 0; i < a.length(); i++) o[i] = a.getJSONObject(i).optDouble("volume", 0);
//         return o;
//     }

//     private static JSONArray getCandlestickData(String pair, String resolution, int count) {
//         try {
//             long minsPerBar;
//             switch (resolution) {
//                 case "1":   minsPerBar = 1;   break;
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
//             double volSum = 0;
//             for (int j = i; j < i + groupSize; j++) {
//                 JSONObject c = source.getJSONObject(j);
//                 high = Math.max(high, c.getDouble("high"));
//                 low  = Math.min(low,  c.getDouble("low"));
//                 volSum += c.optDouble("volume", 0);
//             }
//             JSONObject merged = new JSONObject();
//             merged.put("open", open);
//             merged.put("close", close);
//             merged.put("high", high);
//             merged.put("low", low);
//             merged.put("volume", volSum);
//             result.put(merged);
//         }
//         return result;
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

//     private static final double MAX_MARGIN = 1200.0;
//     private static final int LEVERAGE = 5;

//     private static final int MAX_ENTRY_PRICE_CHECKS = 20;
//     private static final int ENTRY_CHECK_DELAY_MS    = 1000;

//     private static final int  TPSL_MAX_RETRIES    = 3;
//     private static final long TPSL_RETRY_DELAY_MS = 2000L;

//     private static final long TICK_CACHE_TTL_MS = 3_600_000L;

//     private static final int MAX_OPEN_POSITIONS = 120;

//     private static final int  POSITION_ID_MAX_RETRIES = 5;
//     private static final long POSITION_ID_RETRY_DELAY_MS = 1500L;

//     // =========================================================================
//     // Direction -> 1H+30M, Setup -> 15M, Entry -> 5M.
//     // =========================================================================
//     private static final int EMA_FAST = 9;
//     private static final int EMA_MID  = 21;
//     private static final int ATR_PERIOD = 14;
//     private static final int ST_PERIOD     = 10;
//     private static final double ST_MULTIPLIER = 3.0;

//     private static final String RES_5M = "5";
//     private static final String RES_1H = "60";

//     private static final int BASE_5M_FETCH_COUNT = 250;
//     private static final int GROUP_15M_FROM_5M = 3;
//     private static final int GROUP_30M_FROM_5M = 6;
//     private static final int BASE_1H_FETCH_COUNT = 55;

//     // ---- RSI (momentum filter) — narrowed away from the 40-70/30-60
//     // extremes so entries sit closer to genuine mid-momentum, not near
//     // the edge of overbought/oversold. ----
//     private static final int RSI_PERIOD = 14;
//     private static final double RSI_LONG_MIN  = 45, RSI_LONG_MAX  = 68;
//     private static final double RSI_SHORT_MIN = 32, RSI_SHORT_MAX = 55;

//     // ---- Entry (5M) filter thresholds — TIGHTENED for stricter entries ----
//     private static final int    ENTRY_VOLUME_LOOKBACK   = 20;
//     private static final double ENTRY_VOLUME_MULTIPLIER = 1.20; // was 1.05 — genuine spike required

//     private static final double ENTRY_PULLBACK_MAX_ATR  = 0.6; // was 0.45 — loosened for more trade frequency
//     private static final double ENTRY_MIN_BODY_RATIO    = 0.40; // was 0.30 — more solid candle body

//     private static final int    ENTRY_VWAP_LOOKBACK      = 20;
//     private static final double ENTRY_MAX_VWAP_DIST_ATR  = 0.4; // was 0.6 — closer to fair value

//     // Already at max (3-of-3) — cannot be tightened further via score, only
//     // via the underlying volume/RSI/VWAP thresholds themselves (above/below).
//     private static final int ENTRY_CONFIRMATION_MIN_SCORE = 2;

//     // ---- SL/TP sizing — FIXED, set once at entry, never adjusted after. ----
//     private static final int    SWING_LOOKBACK_BARS = 20;
//     private static final double SL_ATR_BUFFER_MULT  = 3.0; // was 1.50 — widened to compensate for the
//     // loosened entry-pullback threshold above: entries taken slightly
//     // farther from EMA carry a bit more natural noise around the
//     // structural level, so SL gets a little more room to avoid being
//     // clipped by that noise instead of a genuine reversal.
//     private static final double SL_MAX_PERCENT = 7.0;
//     private static final double RR_TARGET = 2.0;

//     private static final double LIMIT_ORDER_BUFFER_PCT = 0.0005;

//     private static final long SCALP_COOLDOWN_MS            = 5 * 60 * 1000L;
//     private static final long SCALP_ENTRY_SCAN_INTERVAL_MS = 20 * 1000L;

//     // EMA9 slope/angle check — used on 1H/30M (Direction), 15M (Setup),
//     // and NOW also 5M (Entry) — see analyzeEntry() below.
//     // EMA9 slope/angle check — SPLIT into two thresholds:
//     //   HTF (1H/30M/15M) — loosened, since these only verify "is the trend
//     //   genuinely moving" not the trend-direction itself (Supertrend/EMA-
//     //   cross/price-position still fully enforce that) — a safe lever to
//     //   loosen for more trade frequency without weakening direction-quality.
//     //   Entry (5M) — kept strict, since this was tightened deliberately
//     //   in the previous round to fix the rejection/timing-quality issue.
//     private static final int    EMA_SLOPE_LOOKBACK_BARS = 5;
//     private static final double HTF_EMA_SLOPE_MIN_ATR   = 0.10; // was 0.15 (shared) — loosened for 1H/30M/15M
//     private static final double ENTRY_EMA_SLOPE_MIN_ATR = 0.15; // unchanged for 5M

//     private static final Map<String, JSONObject> instrumentCache = new ConcurrentHashMap<>();
//     private static long lastCacheUpdate = 0;
//     private static final Map<String, Long> lastTradeTime = new ConcurrentHashMap<>();

//     private static final String[] COIN_SYMBOLS = {
//        "PIEVERSE","XAU","APE","ERA","US","RAVE","EDEN","LIT","BREV","MAGMA","BLESS","ZAMA",
//         "FRAX","ACU","1000FLOKI","ELSA","LINEA","SPACE","CLO","FIGHT","UMA","MEGA","MAV","TRIA",
//         "YGG","OPN","ROBO","SUI","GLM","MANTRA","SEI","CAKE","AUCTION","SENT","BSB","BASED","IRYS",
//         "ACE","WET","CL","PRL","GENIUS","WIF","MANTA","LSK","AIGENSYN","PHAROS","JUP","AXL","BOME",
//         "SLX","ZEST","AIOT","VVV","CAP","DATAIP","GRVT","TAO","BR","TURBO","BTC","ETH","ZK","LISTA",
//         "A","LTC","XAG","COAI","HANA","ZRO","SKYAI","COPPER","RARE","ETC","M","AKE","XLM","PIXEL",
//         "XAN","ADA","CROSS","XMR","G","DASH","ZEC","ATOM","TRUTH","BCH","NEO","IOST","FLUX","ALGO",
//         "ZRX","COMP","WLFI","POL","DOGE","BAND","OPG","FIDA","PROM","SANTOS","RLC","1000000MOG",
//         "GRASS","PNUT","TRB","KAIA","ARX","XAI","S","4","COTI","CHR","SOLV","SAGA","ORCA","1000LUNC",
//         "MOVE","VIRTUAL","ME","IOTX","GIGGLE","AVA","VELODROME","AIXBT","KMNO","LA","DEXE","ZBT",
//         "GRIFFAIN","BLUAI","CTSI","ROSE","TURTLE","IMX","SUN","APR","TA","ON","BIO","COOKIE",
//         "AVAAI","DOT","TRUMP","MELANIA","GMT","FLOCK","CLANKER","CYS","SUSHI","VTHO","DIA",
//         "SLP","GOAT","BMT","KGEN","GWEI","MUBARAK","LDO","ESP","DRIFT","FORM","PLUME","NIL",
//         "UNI","ZORA","RECALL","INIT","BZ","PARTI","NATGAS","SPX","BANK","AVAX","RIVER","BILL",
//         "ATH","XRP","KERNEL","JST","PUNDIX","HAEDAL","ALPINE","SOON","SOPH","HUMA","TRX","LINK",
//         "HYPE","HIVE","TAIKO","TAG","MYX","NEWT","AIN","USUAL","PUMP","ICNT","BNB","H","BAT",
//         "QTUM","ARC","AIO","BEAT","BTR","ALCH","THETA","VELVET","ARIA","PTB","UB","LIGHT","FF",
//         "EVAAI","GMX","LYN","TAC","LAB","ENJ","AT","MMT","UAI","AAVE","JCT","KSM","HEI","JASMY",
//         "NEAR","TST","SOL","OP","PLAY","INJ","STG","HOLO","ASR","B","LUNA2","RSR","INX","KAT",
//         "ICP","QNT","MAGIC","T","MINA","STX","ACH","LQTY","ID","GRT","NEIRO","XVS","1INCH","SAND",
//         "ANKR","RVN","SFP","KAVA","MANA","HBAR","ARB","MTL","C98","TUT","SIREN","MASK","1000XEC",
//         "AR","ARPA","FIL","LPT","ENS","PEOPLE","LUMIA","DUSK","FLOW","XVG","ARKM","POPCAT","ARK",
//         "MOODENG","SAFE","AXS","BICO","BIGTIME","WAXP","GAS","POWR","TIA","CHIP","STO","ORDI",
//         "BEAMX","1000BONK","PYTH","ETHW","1000RATS","ANIME","OPEN","DYM","BERA","PORTAL","BB",
//         "BANANAS31","CFX","SSV","TNSR","EDU","JELLYJELLY","BLUR","WAL","FHE","WCT","DEEP","SXT",
//         "NAORIS","OG","CVC","AWE","O","BEL","JOE","SQD","1000PEPE","CARV","FET","SAPIEN","MEME",
//         "AVNT","XPIN","ILV","KAS","BNT","STBL","BSV","RIF","SUPER","USTC","METIS","ETHFI","ENA",
//         "1MBABYDOGE","CATI","HMSTR","GPS","SHELL","KAITO","ACT","RPL","BAN","THE","AKT","MORPHO",
//         "CHILLGUY","AERO","MOCA","PENGU","PHA","RED","EPIC","TREE","1000CAT","MAVIA","FARTCOIN",
//         "PAXG","IN","ORDER","VET","ZEN","STABLE","CHZ","NIGHT","NOM","ZKP","SKR","GRAM","BIRB",
//         "CTR","KNC","ZIL","YFI","EGLD","RUNE","ASTR","ONE","1000SHIB","API3","SPELL","WOO","APT",
//         "PENDLE","AGLD","CYBER","CKB","ONG","MOVR","POLYX","TWT","STEEM","ALT","ZETA","REZ","RENDER",
//         "RONIN","STRK","W","SCR","CETUS","IO","MEW","SWARMS","SONIC","PIPPIN","PROMPT","MERL","F",
//         "ESPORTS","PROVE","XNY","USELESS","HEMI","Q","SKY","ZKC","FLUID","MITO","CFG","EDGE","RE",
//         "YB","MET","DOS","FOGO","BTW","ALLO","BROCCOLI714","HYPER","XPL","RESOLV","ASTER","KITE",
//         "SIGN","HOME","MON","CC","SAHARA","MIRA","EUL","TOWNS","SYRUP","C","DOLO","ALICE","BABY",
//         "SOMI","NOT","BARD","SPK","POWER","2Z","BANANA","ENSO","SYN","NXPC","GUN","XTZ","ONT","SKL",
//         "HOT","JTO","DOGS","EIGEN","GTC","GALA","NMR","CGPT","ZEREBRO","VANA","OGN","CELO","USDC",
//         "COW","0G","IOTA","SNX","DYDX","WLD","1000SATS","ONDO","AEVO","BRETT","LAYER","CRV","TLM","KOMA"
//     };

//     private static final Set<String> INTEGER_QTY_PAIRS = Stream.of(COIN_SYMBOLS)
//             .flatMap(s -> Stream.of("B-" + s + "_USDT", s + "_USDT"))
//             .collect(Collectors.toCollection(HashSet::new));

//     private static final String[] COINS_TO_TRADE = Stream.of(COIN_SYMBOLS)
//             .map(s -> "B-" + s + "_USDT")
//             .toArray(String[]::new);

//     private static class DirectionResult {
//         boolean valid;
//         boolean bullish;
//         boolean bearish;
//     }

//     private static double[] calcEMASeries(double[] d, int period) {
//         double[] out = new double[d.length];
//         if (d.length < period) {
//             double last = d.length > 0 ? d[d.length - 1] : 0;
//             Arrays.fill(out, last);
//             return out;
//         }
//         double k = 2.0 / (period + 1);
//         double ema = 0;
//         for (int i = 0; i < period; i++) ema += d[i];
//         ema /= period;
//         for (int i = 0; i < period; i++) out[i] = ema;
//         out[period - 1] = ema;
//         for (int i = period; i < d.length; i++) {
//             ema = d[i] * k + ema * (1 - k);
//             out[i] = ema;
//         }
//         return out;
//     }

//     private static DirectionResult analyzeDirection(JSONArray candles) {
//         DirectionResult r = new DirectionResult();
//         if (candles == null || candles.length() < EMA_MID + Math.max(ATR_PERIOD, ST_PERIOD) + 5) {
//             r.valid = false;
//             return r;
//         }
//         double[] cl = extractCloses(candles);
//         double[] hi = extractHighs(candles);
//         double[] lo = extractLows(candles);

//         double ema9  = calcEMA(cl, EMA_FAST);
//         double ema21 = calcEMA(cl, EMA_MID);
//         double atr   = calcATR(hi, lo, cl, ATR_PERIOD);
//         double price = cl[cl.length - 1];

//         boolean[] stSeries = calcSupertrend(hi, lo, cl, ST_PERIOD, ST_MULTIPLIER);
//         boolean stGreen = stSeries[stSeries.length - 1];

//         double[] ema9Series = calcEMASeries(cl, EMA_FAST);
//         int n = ema9Series.length;
//         int lookback = Math.min(EMA_SLOPE_LOOKBACK_BARS, n - 1);
//         double emaSlope = ema9Series[n - 1] - ema9Series[n - 1 - lookback];
//         boolean slopeUp   = atr > 0 && emaSlope >= HTF_EMA_SLOPE_MIN_ATR * atr;
//         boolean slopeDown = atr > 0 && emaSlope <= -HTF_EMA_SLOPE_MIN_ATR * atr;

//         boolean priceAboveBoth = price > ema9 && price > ema21;
//         boolean priceBelowBoth = price < ema9 && price < ema21;

//         r.valid = true;
//         r.bullish = (ema9 > ema21) && slopeUp && stGreen && priceAboveBoth;
//         r.bearish = (ema9 < ema21) && slopeDown && !stGreen && priceBelowBoth;
//         return r;
//     }

//     private static class SetupResult {
//         boolean valid;
//         boolean bullish;
//         boolean bearish;
//         double  atr;
//         double  stLower;
//         double  stUpper;
//     }

//     private static SetupResult analyzeSetup(JSONArray candles) {
//         SetupResult r = new SetupResult();
//         if (candles == null || candles.length() < EMA_MID + ST_PERIOD + 5) {
//             r.valid = false;
//             return r;
//         }
//         double[] cl = extractCloses(candles);
//         double[] hi = extractHighs(candles);
//         double[] lo = extractLows(candles);

//         double ema9  = calcEMA(cl, EMA_FAST);
//         double ema21 = calcEMA(cl, EMA_MID);
//         double price = cl[cl.length - 1];
//         r.atr = calcATR(hi, lo, cl, ATR_PERIOD);

//         boolean[] stSeries = calcSupertrend(hi, lo, cl, ST_PERIOD, ST_MULTIPLIER);
//         boolean stGreen = stSeries[stSeries.length - 1];

//         double[] bands = calcSupertrendBands(hi, lo, cl, ST_PERIOD, ST_MULTIPLIER);
//         r.stLower = bands[0];
//         r.stUpper = bands[1];

//         double[] ema9Series = calcEMASeries(cl, EMA_FAST);
//         int n = ema9Series.length;
//         int lookback = Math.min(EMA_SLOPE_LOOKBACK_BARS, n - 1);
//         double emaSlope = ema9Series[n - 1] - ema9Series[n - 1 - lookback];
//         boolean slopeUp   = r.atr > 0 && emaSlope >= HTF_EMA_SLOPE_MIN_ATR * r.atr;
//         boolean slopeDown = r.atr > 0 && emaSlope <= -HTF_EMA_SLOPE_MIN_ATR * r.atr;

//         boolean priceAboveBoth = price > ema9 && price > ema21;
//         boolean priceBelowBoth = price < ema9 && price < ema21;

//         r.valid = true;
//         r.bullish = (ema9 > ema21) && stGreen && slopeUp && priceAboveBoth;
//         r.bearish = (ema9 < ema21) && !stGreen && slopeDown && priceBelowBoth;
//         return r;
//     }

//     // =========================================================================
//     // Entry result (5M) — NEW: EMA9-slope check added as a MANDATORY
//     // condition, matching the same pattern already used on 1H/30M/15M. This
//     // catches cases where price is technically "pulled back near EMA9/21"
//     // (the existing pullback check) but the EMA9 itself has gone flat or
//     // started curving the other way — i.e. momentum on the entry timeframe
//     // itself is fading, not just a healthy pullback within an intact trend.
//     // =========================================================================
//     private static class EntryResult {
//         boolean valid;
//         boolean triggered;
//         double entryClose, entryOpen, entryHigh, entryLow;
//         double atr5m;
//         String reason;
//     }

//     private static EntryResult analyzeEntry(JSONArray raw5m, boolean trendUp) {
//         EntryResult t = new EntryResult();
//         int minBars = Math.max(EMA_MID, Math.max(ENTRY_VOLUME_LOOKBACK, ENTRY_VWAP_LOOKBACK)) + RSI_PERIOD + 5;
//         if (raw5m == null || raw5m.length() < minBars) {
//             t.valid = false;
//             return t;
//         }

//         double[] cl  = extractCloses(raw5m);
//         double[] op  = extractOpens(raw5m);
//         double[] hi  = extractHighs(raw5m);
//         double[] lo  = extractLows(raw5m);
//         double[] vol = extractVolumes(raw5m);
//         int n = cl.length;

//         double ema9  = calcEMA(cl, EMA_FAST);
//         double ema21 = calcEMA(cl, EMA_MID);
//         double atr5m = calcATR(hi, lo, cl, ATR_PERIOD);
//         t.atr5m = atr5m;

//         double entryClose = cl[n - 1], entryOpen = op[n - 1];
//         double entryHigh  = hi[n - 1], entryLow  = lo[n - 1];
//         t.entryClose = entryClose; t.entryOpen = entryOpen;
//         t.entryHigh = entryHigh;   t.entryLow = entryLow;

//         double distToEma9  = Math.abs(entryClose - ema9);
//         double distToEma21 = Math.abs(entryClose - ema21);
//         double nearestEmaDist = Math.min(distToEma9, distToEma21);
//         boolean pulledBack = atr5m > 0 && nearestEmaDist <= ENTRY_PULLBACK_MAX_ATR * atr5m;

//         boolean directionalCandle = trendUp ? (entryClose > entryOpen) : (entryClose < entryOpen);
//         double body  = Math.abs(entryClose - entryOpen);
//         double range = entryHigh - entryLow;
//         boolean notDoji = range > 0 && (body / range) >= ENTRY_MIN_BODY_RATIO;

//         // NEW — genuine rejection/bounce check. "Pullback near EMA" alone
//         // only measures the CLOSE's distance from EMA — it doesn't confirm
//         // the candle actually rejected off that zone versus just drifting
//         // near it. This adds: (a) the wick (low for LONG / high for SHORT)
//         // must have genuinely reached the EMA-zone, and (b) the close must
//         // sit in the far portion of the candle's range, away from that
//         // wick — i.e. price dipped in and was pushed back out, not just
//         // hovering.
//         double wickToEma = trendUp
//                 ? Math.min(Math.abs(entryLow - ema9), Math.abs(entryLow - ema21))
//                 : Math.min(Math.abs(entryHigh - ema9), Math.abs(entryHigh - ema21));
//         boolean wickTouchedZone = atr5m > 0 && wickToEma <= ENTRY_PULLBACK_MAX_ATR * atr5m;
//         double closePositionInRange = range > 0
//                 ? (trendUp ? (entryClose - entryLow) / range : (entryHigh - entryClose) / range)
//                 : 0;
//         boolean rejectionOk = wickTouchedZone && closePositionInRange >= 0.6;

//         // NEW — 5M EMA9-slope check (same ATR-scaled method used on the
//         // other three timeframes).
//         double[] ema9Series5m = calcEMASeries(cl, EMA_FAST);
//         int lookback5m = Math.min(EMA_SLOPE_LOOKBACK_BARS, n - 1);
//         double emaSlope5m = ema9Series5m[n - 1] - ema9Series5m[n - 1 - lookback5m];
//         boolean slope5mOk = trendUp
//                 ? (atr5m > 0 && emaSlope5m >= ENTRY_EMA_SLOPE_MIN_ATR * atr5m)
//                 : (atr5m > 0 && emaSlope5m <= -ENTRY_EMA_SLOPE_MIN_ATR * atr5m);

//         boolean mandatoryOk = pulledBack && rejectionOk && directionalCandle && notDoji && slope5mOk;

//         int volStart = Math.max(0, n - 1 - ENTRY_VOLUME_LOOKBACK);
//         double avgVol = 0; int cnt = 0;
//         for (int i = volStart; i < n - 1; i++) { avgVol += vol[i]; cnt++; }
//         avgVol = cnt > 0 ? avgVol / cnt : 0;
//         boolean volumeOk = avgVol > 0 && vol[n - 1] >= avgVol * ENTRY_VOLUME_MULTIPLIER;

//         double rsi = calcRSI(cl, RSI_PERIOD);
//         boolean rsiOk = trendUp
//                 ? (rsi >= RSI_LONG_MIN && rsi <= RSI_LONG_MAX)
//                 : (rsi >= RSI_SHORT_MIN && rsi <= RSI_SHORT_MAX);

//         int vwapStart = Math.max(0, n - ENTRY_VWAP_LOOKBACK);
//         double cumPV = 0, cumV = 0;
//         for (int i = vwapStart; i < n; i++) {
//             double typical = (hi[i] + lo[i] + cl[i]) / 3.0;
//             cumPV += typical * vol[i];
//             cumV  += vol[i];
//         }
//         double vwap = cumV > 0 ? cumPV / cumV : entryClose;
//         double distFromVwap = Math.abs(entryClose - vwap);
//         boolean vwapOk = atr5m > 0 && distFromVwap <= ENTRY_MAX_VWAP_DIST_ATR * atr5m;

//         int confirmationScore = (volumeOk ? 1 : 0) + (rsiOk ? 1 : 0) + (vwapOk ? 1 : 0);

//         t.triggered = mandatoryOk && confirmationScore >= ENTRY_CONFIRMATION_MIN_SCORE;
//         t.valid = true;
//         t.reason = String.format(
//                 "mandatory[pulledBack=%s rejection=%s(closePos=%.2f) directional=%s notDoji=%s slope5m=%s(%.6f)] confirmation[volume=%s(%.2fx) rsi=%.1f(ok=%s) vwap=%s] score=%d/3",
//                 pulledBack, rejectionOk, closePositionInRange, directionalCandle, notDoji, slope5mOk, emaSlope5m,
//                 volumeOk, avgVol > 0 ? vol[n - 1] / avgVol : 0,
//                 rsi, rsiOk, vwapOk, confirmationScore);
//         return t;
//     }

//     private static JSONArray dropLastIfForming(JSONArray arr) {
//         if (arr == null || arr.length() < 2) return arr;
//         JSONArray out = new JSONArray();
//         for (int i = 0; i < arr.length() - 1; i++) out.put(arr.getJSONObject(i));
//         return out;
//     }

//     // =========================================================================
//     // FIXED SL/TP — UNCHANGED.
//     // =========================================================================
//     private static double[] computeSlTp(boolean isLong, double entryPrice,
//                                          double[] hi5m, double[] lo5m, double atr5m,
//                                          double stLevel, double tickSize) {
//         double sl, tp;
//         if (isLong) {
//             double swingLow = recentLow(lo5m, SWING_LOOKBACK_BARS);
//             double structuralLow = Math.min(swingLow, stLevel);
//             double raw = structuralLow - SL_ATR_BUFFER_MULT * atr5m;
//             double hardFloor = entryPrice * (1 - SL_MAX_PERCENT / 100.0);
//             sl = Math.max(raw, hardFloor);
//             double risk = entryPrice - sl;
//             tp = entryPrice + RR_TARGET * risk;
//         } else {
//             double swingHigh = recentHigh(hi5m, SWING_LOOKBACK_BARS);
//             double structuralHigh = Math.max(swingHigh, stLevel);
//             double raw = structuralHigh + SL_ATR_BUFFER_MULT * atr5m;
//             double hardCeil = entryPrice * (1 + SL_MAX_PERCENT / 100.0);
//             sl = Math.min(raw, hardCeil);
//             double risk = sl - entryPrice;
//             tp = entryPrice - RR_TARGET * risk;
//         }
//         sl = roundToTick(sl, tickSize);
//         tp = roundToTick(tp, tickSize);
//         return new double[]{sl, tp};
//     }

//     private static double recentLow(double[] lo, int lookback) {
//         int n = lo.length;
//         int start = Math.max(0, n - lookback);
//         double min = Double.POSITIVE_INFINITY;
//         for (int i = start; i < n; i++) min = Math.min(min, lo[i]);
//         return min;
//     }

//     private static double recentHigh(double[] hi, int lookback) {
//         int n = hi.length;
//         int start = Math.max(0, n - lookback);
//         double max = Double.NEGATIVE_INFINITY;
//         for (int i = start; i < n; i++) max = Math.max(max, hi[i]);
//         return max;
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

//     private static double calcQuantity(double price, String pair) {
//         double usdtInrRate = 98.0;
//         double qty = MAX_MARGIN / (price * usdtInrRate);
//         double finalQty = INTEGER_QTY_PAIRS.contains(pair)
//                 ? Math.floor(qty)
//                 : Math.floor(qty * 100) / 100.0;
//         return Math.max(finalQty, 0);
//     }

//     public static void main(String[] args) {
//         System.out.println("=== Scalp bot starting (fixed margin, fixed SL/TP, no exit-monitoring) ===");
//         initInstrumentCache();

//         while (true) {
//             try {
//                 runEntryScan();
//             } catch (Throwable t) {
//                 System.err.println("[MAIN-LOOP] Uncaught error, continuing: " + t.getMessage());
//                 t.printStackTrace();
//             }

//             try {
//                 TimeUnit.MILLISECONDS.sleep(SCALP_ENTRY_SCAN_INTERVAL_MS);
//             } catch (InterruptedException ignored) {
//                 Thread.currentThread().interrupt();
//                 break;
//             }
//         }
//     }

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
//                 if (active.contains(pair)) continue;

//                 long lastTrade = lastTradeTime.getOrDefault(pair, 0L);
//                 if (System.currentTimeMillis() - lastTrade < SCALP_COOLDOWN_MS) continue;

//                 JSONArray raw5m = dropLastIfForming(
//                         getCandlestickData(pair, RES_5M, BASE_5M_FETCH_COUNT));
//                 if (raw5m == null || raw5m.length() < EMA_MID + ST_PERIOD + 5) continue;

//                 JSONArray raw30m = aggregateCandles(raw5m, GROUP_30M_FROM_5M);
//                 DirectionResult dir30m = analyzeDirection(raw30m);
//                 if (!dir30m.valid || (!dir30m.bullish && !dir30m.bearish)) continue;

//                 JSONArray raw15m = aggregateCandles(raw5m, GROUP_15M_FROM_5M);
//                 SetupResult setup15m = analyzeSetup(raw15m);
//                 if (!setup15m.valid) continue;

//                 boolean setupMatches30m = (dir30m.bullish && setup15m.bullish)
//                         || (dir30m.bearish && setup15m.bearish);
//                 if (!setupMatches30m) continue;

//                 boolean trendUp = dir30m.bullish;

//                 JSONArray raw1h = dropLastIfForming(
//                         getCandlestickData(pair, RES_1H, BASE_1H_FETCH_COUNT));
//                 DirectionResult dir1h = analyzeDirection(raw1h);
//                 if (!dir1h.valid) continue;

//                 boolean dir1hMatches = (trendUp && dir1h.bullish) || (!trendUp && dir1h.bearish);
//                 if (!dir1hMatches) continue;

//                 EntryResult entry5m = analyzeEntry(raw5m, trendUp);
//                 if (!entry5m.valid) continue;
//                 if (!entry5m.triggered) {
//                     continue;
//                 }

//                 System.out.println("\n==== " + pair + " ====");
//                 System.out.printf("  [1H] %s | [30M] %s | [15M] %s | [5M-Entry] %s%n",
//                         dir1h.bullish ? "BULLISH" : "BEARISH",
//                         trendUp ? "BULLISH" : "BEARISH",
//                         setup15m.bullish ? "BULLISH" : "BEARISH",
//                         entry5m.reason);

//                 String side = trendUp ? "buy" : "sell";
//                 System.out.println("  ╔══════════════════════════════════════════════════╗");
//                 System.out.println("  ║  SCALP TRIGGER → " + side.toUpperCase() + " " + pair);
//                 System.out.println("  ╚══════════════════════════════════════════════════╝");

//                 double currentPrice = getLastPrice(pair);
//                 if (currentPrice <= 0) continue;
//                 double qty = calcQuantity(currentPrice, pair);
//                 if (qty <= 0) continue;
//                 double tickSize = getTickSize(pair);

//                 System.out.printf("  Placing %s | price=%.6f | qty=%.4f | lev=%dx%n",
//                         side.toUpperCase(), currentPrice, qty, LEVERAGE);

//                 JSONObject resp = placeFuturesOrder(side, pair, qty, LEVERAGE,
//                         "email_notification", "isolated", "INR", currentPrice);
//                 if (resp == null || !resp.has("id")) {
//                     System.out.println("  Order failed: " + resp);
//                     continue;
//                 }

//                 System.out.println("  Order placed! id=" + resp.getString("id"));
//                 lastTradeTime.put(pair, System.currentTimeMillis());

//                 double entry = getEntryPrice(pair, resp.getString("id"));
//                 if (entry <= 0) {
//                     System.out.println("  Could not confirm entry within window — TP/SL will be handled by safety sweep");
//                     active.add(pair);
//                     continue;
//                 }

//                 System.out.printf("  Entry confirmed: %.6f%n", entry);

//                 double[] hi5m = extractHighs(raw5m);
//                 double[] lo5m = extractLows(raw5m);
//                 double stLevel = trendUp ? setup15m.stLower : setup15m.stUpper;
//                 double[] slTp = computeSlTp(trendUp, entry, hi5m, lo5m, entry5m.atr5m, stLevel, tickSize);
//                 double[] clamped = sanityClampSlTp(trendUp, entry, slTp[0], slTp[1], tickSize);
//                 double slPrice = clamped[0], tpPrice = clamped[1];
//                 double slPct = Math.abs(entry - slPrice) / entry * 100;
//                 double tpPct = Math.abs(tpPrice - entry) / entry * 100;

//                 System.out.printf("  SL=%.6f (%.3f%%) | TP=%.6f (%.3f%%) | RR target=%.2f%n",
//                         slPrice, slPct, tpPrice, tpPct, RR_TARGET);

//                 String posId = getPositionId(pair);
//                 if (posId != null) {
//                     setTpSlWithRetry(posId, tpPrice, slPrice, pair);
//                 } else {
//                     System.out.println("  Position ID not found after retries — TP/SL will be handled by safety sweep");
//                 }

//                 active.add(pair);

//             } catch (Exception e) {
//                 System.err.println("Error on " + pair + ": " + e.getMessage());
//             }
//         }

//         System.out.println("\n=== Scalp scan complete ===");
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
//                 JSONArray raw5m = dropLastIfForming(
//                         getCandlestickData(pair, RES_5M, BASE_5M_FETCH_COUNT));

//                 if (raw5m == null || raw5m.length() < EMA_MID + ST_PERIOD + 5) {
//                     System.out.println("  [SWEEP] insufficient 5M data for " + pair
//                             + " — will retry next run");
//                     continue;
//                 }

//                 JSONArray raw15mSweep = aggregateCandles(raw5m, GROUP_15M_FROM_5M);
//                 SetupResult setup15mSweep = analyzeSetup(raw15mSweep);
//                 if (!setup15mSweep.valid) {
//                     System.out.println("  [SWEEP] insufficient 15M data for " + pair
//                             + " — will retry next run");
//                     continue;
//                 }

//                 double[] hi5m = extractHighs(raw5m);
//                 double[] lo5m = extractLows(raw5m);
//                 double[] cl5m = extractCloses(raw5m);
//                 double atr5m = calcATR(hi5m, lo5m, cl5m, ATR_PERIOD);
//                 if (atr5m <= 0) {
//                     System.out.println("  [SWEEP] invalid 5M ATR for " + pair
//                             + " — will retry next run");
//                     continue;
//                 }

//                 double posQty = pos.optDouble("active_pos", 0);
//                 boolean isLong = posQty >= 0;
//                 double stLevel = isLong ? setup15mSweep.stLower : setup15mSweep.stUpper;

//                 double tick = getTickSize(pair);
//                 double[] slTp = computeSlTp(isLong, avgPrice, hi5m, lo5m, atr5m, stLevel, tick);
//                 double[] clamped = sanityClampSlTp(isLong, avgPrice, slTp[0], slTp[1], tick);
//                 double sl = clamped[0], tp = clamped[1];

//                 String posId = pos.optString("id", null);
//                 if (posId != null) {
//                     System.out.printf("  [SWEEP] %s fallback SL=%.6f TP=%.6f (RR target=%.2f)%n", pair, sl, tp, RR_TARGET);
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

//     private static double calcRSI(double[] closes, int period) {
//         if (closes.length < period + 1) return 50.0;

//         double avgGain = 0, avgLoss = 0;
//         for (int i = 1; i <= period; i++) {
//             double change = closes[i] - closes[i - 1];
//             if (change > 0) avgGain += change;
//             else avgLoss += -change;
//         }
//         avgGain /= period;
//         avgLoss /= period;

//         for (int i = period + 1; i < closes.length; i++) {
//             double change = closes[i] - closes[i - 1];
//             double gain = Math.max(change, 0);
//             double loss = Math.max(-change, 0);
//             avgGain = (avgGain * (period - 1) + gain) / period;
//             avgLoss = (avgLoss * (period - 1) + loss) / period;
//         }

//         if (avgLoss == 0) return 100.0;
//         double rs = avgGain / avgLoss;
//         return 100.0 - (100.0 / (1.0 + rs));
//     }

//     private static BigDecimal roundToTickBD(double price, double tick) {
//         if (tick <= 0) return BigDecimal.valueOf(price);
//         BigDecimal bdPrice = BigDecimal.valueOf(price);
//         BigDecimal bdTick  = BigDecimal.valueOf(tick);
//         BigDecimal multiples = bdPrice.divide(bdTick, 0, RoundingMode.HALF_UP);
//         BigDecimal result = multiples.multiply(bdTick);
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
//     private static double[] extractVolumes(JSONArray a) {
//         double[] o = new double[a.length()];
//         for (int i = 0; i < a.length(); i++) o[i] = a.getJSONObject(i).optDouble("volume", 0);
//         return o;
//     }

//     private static JSONArray getCandlestickData(String pair, String resolution, int count) {
//         try {
//             long minsPerBar;
//             switch (resolution) {
//                 case "1":   minsPerBar = 1;   break;
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
//             double volSum = 0;
//             for (int j = i; j < i + groupSize; j++) {
//                 JSONObject c = source.getJSONObject(j);
//                 high = Math.max(high, c.getDouble("high"));
//                 low  = Math.min(low,  c.getDouble("low"));
//                 volSum += c.optDouble("volume", 0);
//             }
//             JSONObject merged = new JSONObject();
//             merged.put("open", open);
//             merged.put("close", close);
//             merged.put("high", high);
//             merged.put("low", low);
//             merged.put("volume", volSum);
//             result.put(merged);
//         }
//         return result;
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

//     private static final double MAX_MARGIN = 1500.0;
//     private static final int LEVERAGE = 12;

//     private static final int MAX_ENTRY_PRICE_CHECKS = 20;
//     private static final int ENTRY_CHECK_DELAY_MS    = 1000;

//     private static final int  TPSL_MAX_RETRIES    = 3;
//     private static final long TPSL_RETRY_DELAY_MS = 2000L;

//     private static final long TICK_CACHE_TTL_MS = 3_600_000L;

//     private static final int MAX_OPEN_POSITIONS = 120;

//     private static final int  POSITION_ID_MAX_RETRIES = 5;
//     private static final long POSITION_ID_RETRY_DELAY_MS = 1500L;

//     // =========================================================================
//     // Direction -> 1H+30M, Setup -> 15M, Entry -> 5M.
//     // =========================================================================
//     private static final int EMA_FAST = 9;
//     private static final int EMA_MID  = 21;
//     private static final int ATR_PERIOD = 14;
//     private static final int ST_PERIOD     = 10;
//     private static final double ST_MULTIPLIER = 3.0;

//     private static final String RES_5M = "5";
//     private static final String RES_1H = "60";

//     private static final int BASE_5M_FETCH_COUNT = 250;
//     private static final int GROUP_15M_FROM_5M = 3;
//     private static final int GROUP_30M_FROM_5M = 6;
//     private static final int BASE_1H_FETCH_COUNT = 55;

//     // ---- RSI (momentum filter) — narrowed away from the 40-70/30-60
//     // extremes so entries sit closer to genuine mid-momentum, not near
//     // the edge of overbought/oversold. ----
//     private static final int RSI_PERIOD = 14;
//     private static final double RSI_LONG_MIN  = 45, RSI_LONG_MAX  = 68;
//     private static final double RSI_SHORT_MIN = 32, RSI_SHORT_MAX = 55;

//     // ---- Entry (5M) filter thresholds — TIGHTENED for stricter entries ----
//     private static final int    ENTRY_VOLUME_LOOKBACK   = 20;
//     private static final double ENTRY_VOLUME_MULTIPLIER = 1.20; // was 1.05 — genuine spike required

//     private static final double ENTRY_PULLBACK_MAX_ATR  = 0.6; // was 0.6 — price closer to EMA
//     private static final double ENTRY_MIN_BODY_RATIO    = 0.40; // was 0.30 — more solid candle body

//     private static final int    ENTRY_VWAP_LOOKBACK      = 20;
//     private static final double ENTRY_MAX_VWAP_DIST_ATR  = 0.4; // was 0.6 — closer to fair value

//     // Already at max (3-of-3) — cannot be tightened further via score, only
//     // via the underlying volume/RSI/VWAP thresholds themselves (above/below).
//     private static final int ENTRY_CONFIRMATION_MIN_SCORE = 3;

//     // ---- SL/TP sizing — FIXED, set once at entry, never adjusted after. ----
//     private static final int    SWING_LOOKBACK_BARS = 20;
//     private static final double SL_ATR_BUFFER_MULT  = 2.0;
//     private static final double SL_MAX_PERCENT = 4.5;
//     private static final double RR_TARGET = 1.5;

//     private static final double LIMIT_ORDER_BUFFER_PCT = 0.0005;

//     private static final long SCALP_COOLDOWN_MS            = 5 * 60 * 1000L;
//     private static final long SCALP_ENTRY_SCAN_INTERVAL_MS = 20 * 1000L;

//     // EMA9 slope/angle check — used on 1H/30M (Direction), 15M (Setup),
//     // and NOW also 5M (Entry) — see analyzeEntry() below.
//     private static final int    EMA_SLOPE_LOOKBACK_BARS = 5;
//     private static final double EMA_SLOPE_MIN_ATR        = 0.15;

//     private static final Map<String, JSONObject> instrumentCache = new ConcurrentHashMap<>();
//     private static long lastCacheUpdate = 0;
//     private static final Map<String, Long> lastTradeTime = new ConcurrentHashMap<>();

//     private static final String[] COIN_SYMBOLS = {
//        "PIEVERSE","XAU","APE","ERA","US","RAVE","EDEN","LIT","BREV","MAGMA","BLESS","ZAMA",
//         "FRAX","ACU","1000FLOKI","ELSA","LINEA","SPACE","CLO","FIGHT","UMA","MEGA","MAV","TRIA",
//         "YGG","OPN","ROBO","SUI","GLM","MANTRA","SEI","CAKE","AUCTION","SENT","BSB","BASED","IRYS",
//         "ACE","WET","CL","PRL","GENIUS","WIF","MANTA","LSK","AIGENSYN","PHAROS","JUP","AXL","BOME",
//         "SLX","ZEST","AIOT","VVV","CAP","DATAIP","GRVT","TAO","BR","TURBO","BTC","ETH","ZK","LISTA",
//         "A","LTC","XAG","COAI","HANA","ZRO","SKYAI","COPPER","RARE","ETC","M","AKE","XLM","PIXEL",
//         "XAN","ADA","CROSS","XMR","G","DASH","ZEC","ATOM","TRUTH","BCH","NEO","IOST","FLUX","ALGO",
//         "ZRX","COMP","WLFI","POL","DOGE","BAND","OPG","FIDA","PROM","SANTOS","RLC","1000000MOG",
//         "GRASS","PNUT","TRB","KAIA","ARX","XAI","S","4","COTI","CHR","SOLV","SAGA","ORCA","1000LUNC",
//         "MOVE","VIRTUAL","ME","IOTX","GIGGLE","AVA","VELODROME","AIXBT","KMNO","LA","DEXE","ZBT",
//         "GRIFFAIN","BLUAI","CTSI","ROSE","TURTLE","IMX","SUN","APR","TA","ON","BIO","COOKIE",
//         "AVAAI","DOT","TRUMP","MELANIA","GMT","FLOCK","CLANKER","CYS","SUSHI","VTHO","DIA",
//         "SLP","GOAT","BMT","KGEN","GWEI","MUBARAK","LDO","ESP","DRIFT","FORM","PLUME","NIL",
//         "UNI","ZORA","RECALL","INIT","BZ","PARTI","NATGAS","SPX","BANK","AVAX","RIVER","BILL",
//         "ATH","XRP","KERNEL","JST","PUNDIX","HAEDAL","ALPINE","SOON","SOPH","HUMA","TRX","LINK",
//         "HYPE","HIVE","TAIKO","TAG","MYX","NEWT","AIN","USUAL","PUMP","ICNT","BNB","H","BAT",
//         "QTUM","ARC","AIO","BEAT","BTR","ALCH","THETA","VELVET","ARIA","PTB","UB","LIGHT","FF",
//         "EVAAI","GMX","LYN","TAC","LAB","ENJ","AT","MMT","UAI","AAVE","JCT","KSM","HEI","JASMY",
//         "NEAR","TST","SOL","OP","PLAY","INJ","STG","HOLO","ASR","B","LUNA2","RSR","INX","KAT",
//         "ICP","QNT","MAGIC","T","MINA","STX","ACH","LQTY","ID","GRT","NEIRO","XVS","1INCH","SAND",
//         "ANKR","RVN","SFP","KAVA","MANA","HBAR","ARB","MTL","C98","TUT","SIREN","MASK","1000XEC",
//         "AR","ARPA","FIL","LPT","ENS","PEOPLE","LUMIA","DUSK","FLOW","XVG","ARKM","POPCAT","ARK",
//         "MOODENG","SAFE","AXS","BICO","BIGTIME","WAXP","GAS","POWR","TIA","CHIP","STO","ORDI",
//         "BEAMX","1000BONK","PYTH","ETHW","1000RATS","ANIME","OPEN","DYM","BERA","PORTAL","BB",
//         "BANANAS31","CFX","SSV","TNSR","EDU","JELLYJELLY","BLUR","WAL","FHE","WCT","DEEP","SXT",
//         "NAORIS","OG","CVC","AWE","O","BEL","JOE","SQD","1000PEPE","CARV","FET","SAPIEN","MEME",
//         "AVNT","XPIN","ILV","KAS","BNT","STBL","BSV","RIF","SUPER","USTC","METIS","ETHFI","ENA",
//         "1MBABYDOGE","CATI","HMSTR","GPS","SHELL","KAITO","ACT","RPL","BAN","THE","AKT","MORPHO",
//         "CHILLGUY","AERO","MOCA","PENGU","PHA","RED","EPIC","TREE","1000CAT","MAVIA","FARTCOIN",
//         "PAXG","IN","ORDER","VET","ZEN","STABLE","CHZ","NIGHT","NOM","ZKP","SKR","GRAM","BIRB",
//         "CTR","KNC","ZIL","YFI","EGLD","RUNE","ASTR","ONE","1000SHIB","API3","SPELL","WOO","APT",
//         "PENDLE","AGLD","CYBER","CKB","ONG","MOVR","POLYX","TWT","STEEM","ALT","ZETA","REZ","RENDER",
//         "RONIN","STRK","W","SCR","CETUS","IO","MEW","SWARMS","SONIC","PIPPIN","PROMPT","MERL","F",
//         "ESPORTS","PROVE","XNY","USELESS","HEMI","Q","SKY","ZKC","FLUID","MITO","CFG","EDGE","RE",
//         "YB","MET","DOS","FOGO","BTW","ALLO","BROCCOLI714","HYPER","XPL","RESOLV","ASTER","KITE",
//         "SIGN","HOME","MON","CC","SAHARA","MIRA","EUL","TOWNS","SYRUP","C","DOLO","ALICE","BABY",
//         "SOMI","NOT","BARD","SPK","POWER","2Z","BANANA","ENSO","SYN","NXPC","GUN","XTZ","ONT","SKL",
//         "HOT","JTO","DOGS","EIGEN","GTC","GALA","NMR","CGPT","ZEREBRO","VANA","OGN","CELO","USDC",
//         "COW","0G","IOTA","SNX","DYDX","WLD","1000SATS","ONDO","AEVO","BRETT","LAYER","CRV","TLM","KOMA"
//     };

//     private static final Set<String> INTEGER_QTY_PAIRS = Stream.of(COIN_SYMBOLS)
//             .flatMap(s -> Stream.of("B-" + s + "_USDT", s + "_USDT"))
//             .collect(Collectors.toCollection(HashSet::new));

//     private static final String[] COINS_TO_TRADE = Stream.of(COIN_SYMBOLS)
//             .map(s -> "B-" + s + "_USDT")
//             .toArray(String[]::new);

//     private static class DirectionResult {
//         boolean valid;
//         boolean bullish;
//         boolean bearish;
//     }

//     private static double[] calcEMASeries(double[] d, int period) {
//         double[] out = new double[d.length];
//         if (d.length < period) {
//             double last = d.length > 0 ? d[d.length - 1] : 0;
//             Arrays.fill(out, last);
//             return out;
//         }
//         double k = 2.0 / (period + 1);
//         double ema = 0;
//         for (int i = 0; i < period; i++) ema += d[i];
//         ema /= period;
//         for (int i = 0; i < period; i++) out[i] = ema;
//         out[period - 1] = ema;
//         for (int i = period; i < d.length; i++) {
//             ema = d[i] * k + ema * (1 - k);
//             out[i] = ema;
//         }
//         return out;
//     }

//     private static DirectionResult analyzeDirection(JSONArray candles) {
//         DirectionResult r = new DirectionResult();
//         if (candles == null || candles.length() < EMA_MID + Math.max(ATR_PERIOD, ST_PERIOD) + 5) {
//             r.valid = false;
//             return r;
//         }
//         double[] cl = extractCloses(candles);
//         double[] hi = extractHighs(candles);
//         double[] lo = extractLows(candles);

//         double ema9  = calcEMA(cl, EMA_FAST);
//         double ema21 = calcEMA(cl, EMA_MID);
//         double atr   = calcATR(hi, lo, cl, ATR_PERIOD);
//         double price = cl[cl.length - 1];

//         boolean[] stSeries = calcSupertrend(hi, lo, cl, ST_PERIOD, ST_MULTIPLIER);
//         boolean stGreen = stSeries[stSeries.length - 1];

//         double[] ema9Series = calcEMASeries(cl, EMA_FAST);
//         int n = ema9Series.length;
//         int lookback = Math.min(EMA_SLOPE_LOOKBACK_BARS, n - 1);
//         double emaSlope = ema9Series[n - 1] - ema9Series[n - 1 - lookback];
//         boolean slopeUp   = atr > 0 && emaSlope >= EMA_SLOPE_MIN_ATR * atr;
//         boolean slopeDown = atr > 0 && emaSlope <= -EMA_SLOPE_MIN_ATR * atr;

//         boolean priceAboveBoth = price > ema9 && price > ema21;
//         boolean priceBelowBoth = price < ema9 && price < ema21;

//         r.valid = true;
//         r.bullish = (ema9 > ema21) && slopeUp && stGreen && priceAboveBoth;
//         r.bearish = (ema9 < ema21) && slopeDown && !stGreen && priceBelowBoth;
//         return r;
//     }

//     private static class SetupResult {
//         boolean valid;
//         boolean bullish;
//         boolean bearish;
//         double  atr;
//         double  stLower;
//         double  stUpper;
//     }

//     private static SetupResult analyzeSetup(JSONArray candles) {
//         SetupResult r = new SetupResult();
//         if (candles == null || candles.length() < EMA_MID + ST_PERIOD + 5) {
//             r.valid = false;
//             return r;
//         }
//         double[] cl = extractCloses(candles);
//         double[] hi = extractHighs(candles);
//         double[] lo = extractLows(candles);

//         double ema9  = calcEMA(cl, EMA_FAST);
//         double ema21 = calcEMA(cl, EMA_MID);
//         double price = cl[cl.length - 1];
//         r.atr = calcATR(hi, lo, cl, ATR_PERIOD);

//         boolean[] stSeries = calcSupertrend(hi, lo, cl, ST_PERIOD, ST_MULTIPLIER);
//         boolean stGreen = stSeries[stSeries.length - 1];

//         double[] bands = calcSupertrendBands(hi, lo, cl, ST_PERIOD, ST_MULTIPLIER);
//         r.stLower = bands[0];
//         r.stUpper = bands[1];

//         double[] ema9Series = calcEMASeries(cl, EMA_FAST);
//         int n = ema9Series.length;
//         int lookback = Math.min(EMA_SLOPE_LOOKBACK_BARS, n - 1);
//         double emaSlope = ema9Series[n - 1] - ema9Series[n - 1 - lookback];
//         boolean slopeUp   = r.atr > 0 && emaSlope >= EMA_SLOPE_MIN_ATR * r.atr;
//         boolean slopeDown = r.atr > 0 && emaSlope <= -EMA_SLOPE_MIN_ATR * r.atr;

//         boolean priceAboveBoth = price > ema9 && price > ema21;
//         boolean priceBelowBoth = price < ema9 && price < ema21;

//         r.valid = true;
//         r.bullish = (ema9 > ema21) && stGreen && slopeUp && priceAboveBoth;
//         r.bearish = (ema9 < ema21) && !stGreen && slopeDown && priceBelowBoth;
//         return r;
//     }

//     // =========================================================================
//     // Entry result (5M) — NEW: EMA9-slope check added as a MANDATORY
//     // condition, matching the same pattern already used on 1H/30M/15M. This
//     // catches cases where price is technically "pulled back near EMA9/21"
//     // (the existing pullback check) but the EMA9 itself has gone flat or
//     // started curving the other way — i.e. momentum on the entry timeframe
//     // itself is fading, not just a healthy pullback within an intact trend.
//     // =========================================================================
//     private static class EntryResult {
//         boolean valid;
//         boolean triggered;
//         double entryClose, entryOpen, entryHigh, entryLow;
//         double atr5m;
//         String reason;
//     }

//     private static EntryResult analyzeEntry(JSONArray raw5m, boolean trendUp) {
//         EntryResult t = new EntryResult();
//         int minBars = Math.max(EMA_MID, Math.max(ENTRY_VOLUME_LOOKBACK, ENTRY_VWAP_LOOKBACK)) + RSI_PERIOD + 5;
//         if (raw5m == null || raw5m.length() < minBars) {
//             t.valid = false;
//             return t;
//         }

//         double[] cl  = extractCloses(raw5m);
//         double[] op  = extractOpens(raw5m);
//         double[] hi  = extractHighs(raw5m);
//         double[] lo  = extractLows(raw5m);
//         double[] vol = extractVolumes(raw5m);
//         int n = cl.length;

//         double ema9  = calcEMA(cl, EMA_FAST);
//         double ema21 = calcEMA(cl, EMA_MID);
//         double atr5m = calcATR(hi, lo, cl, ATR_PERIOD);
//         t.atr5m = atr5m;

//         double entryClose = cl[n - 1], entryOpen = op[n - 1];
//         double entryHigh  = hi[n - 1], entryLow  = lo[n - 1];
//         t.entryClose = entryClose; t.entryOpen = entryOpen;
//         t.entryHigh = entryHigh;   t.entryLow = entryLow;

//         double distToEma9  = Math.abs(entryClose - ema9);
//         double distToEma21 = Math.abs(entryClose - ema21);
//         double nearestEmaDist = Math.min(distToEma9, distToEma21);
//         boolean pulledBack = atr5m > 0 && nearestEmaDist <= ENTRY_PULLBACK_MAX_ATR * atr5m;

//         boolean directionalCandle = trendUp ? (entryClose > entryOpen) : (entryClose < entryOpen);
//         double body  = Math.abs(entryClose - entryOpen);
//         double range = entryHigh - entryLow;
//         boolean notDoji = range > 0 && (body / range) >= ENTRY_MIN_BODY_RATIO;

//         // NEW — genuine rejection/bounce check. "Pullback near EMA" alone
//         // only measures the CLOSE's distance from EMA — it doesn't confirm
//         // the candle actually rejected off that zone versus just drifting
//         // near it. This adds: (a) the wick (low for LONG / high for SHORT)
//         // must have genuinely reached the EMA-zone, and (b) the close must
//         // sit in the far portion of the candle's range, away from that
//         // wick — i.e. price dipped in and was pushed back out, not just
//         // hovering.
//         double wickToEma = trendUp
//                 ? Math.min(Math.abs(entryLow - ema9), Math.abs(entryLow - ema21))
//                 : Math.min(Math.abs(entryHigh - ema9), Math.abs(entryHigh - ema21));
//         boolean wickTouchedZone = atr5m > 0 && wickToEma <= ENTRY_PULLBACK_MAX_ATR * atr5m;
//         double closePositionInRange = range > 0
//                 ? (trendUp ? (entryClose - entryLow) / range : (entryHigh - entryClose) / range)
//                 : 0;
//         boolean rejectionOk = wickTouchedZone && closePositionInRange >= 0.6;

//         // NEW — 5M EMA9-slope check (same ATR-scaled method used on the
//         // other three timeframes).
//         double[] ema9Series5m = calcEMASeries(cl, EMA_FAST);
//         int lookback5m = Math.min(EMA_SLOPE_LOOKBACK_BARS, n - 1);
//         double emaSlope5m = ema9Series5m[n - 1] - ema9Series5m[n - 1 - lookback5m];
//         boolean slope5mOk = trendUp
//                 ? (atr5m > 0 && emaSlope5m >= EMA_SLOPE_MIN_ATR * atr5m)
//                 : (atr5m > 0 && emaSlope5m <= -EMA_SLOPE_MIN_ATR * atr5m);

//         boolean mandatoryOk = pulledBack && rejectionOk && directionalCandle && notDoji && slope5mOk;

//         int volStart = Math.max(0, n - 1 - ENTRY_VOLUME_LOOKBACK);
//         double avgVol = 0; int cnt = 0;
//         for (int i = volStart; i < n - 1; i++) { avgVol += vol[i]; cnt++; }
//         avgVol = cnt > 0 ? avgVol / cnt : 0;
//         boolean volumeOk = avgVol > 0 && vol[n - 1] >= avgVol * ENTRY_VOLUME_MULTIPLIER;

//         double rsi = calcRSI(cl, RSI_PERIOD);
//         boolean rsiOk = trendUp
//                 ? (rsi >= RSI_LONG_MIN && rsi <= RSI_LONG_MAX)
//                 : (rsi >= RSI_SHORT_MIN && rsi <= RSI_SHORT_MAX);

//         int vwapStart = Math.max(0, n - ENTRY_VWAP_LOOKBACK);
//         double cumPV = 0, cumV = 0;
//         for (int i = vwapStart; i < n; i++) {
//             double typical = (hi[i] + lo[i] + cl[i]) / 3.0;
//             cumPV += typical * vol[i];
//             cumV  += vol[i];
//         }
//         double vwap = cumV > 0 ? cumPV / cumV : entryClose;
//         double distFromVwap = Math.abs(entryClose - vwap);
//         boolean vwapOk = atr5m > 0 && distFromVwap <= ENTRY_MAX_VWAP_DIST_ATR * atr5m;

//         int confirmationScore = (volumeOk ? 1 : 0) + (rsiOk ? 1 : 0) + (vwapOk ? 1 : 0);

//         t.triggered = mandatoryOk && confirmationScore >= ENTRY_CONFIRMATION_MIN_SCORE;
//         t.valid = true;
//         t.reason = String.format(
//                 "mandatory[pulledBack=%s rejection=%s(closePos=%.2f) directional=%s notDoji=%s slope5m=%s(%.6f)] confirmation[volume=%s(%.2fx) rsi=%.1f(ok=%s) vwap=%s] score=%d/3",
//                 pulledBack, rejectionOk, closePositionInRange, directionalCandle, notDoji, slope5mOk, emaSlope5m,
//                 volumeOk, avgVol > 0 ? vol[n - 1] / avgVol : 0,
//                 rsi, rsiOk, vwapOk, confirmationScore);
//         return t;
//     }

//     private static JSONArray dropLastIfForming(JSONArray arr) {
//         if (arr == null || arr.length() < 2) return arr;
//         JSONArray out = new JSONArray();
//         for (int i = 0; i < arr.length() - 1; i++) out.put(arr.getJSONObject(i));
//         return out;
//     }

//     // =========================================================================
//     // FIXED SL/TP — UNCHANGED.
//     // =========================================================================
//     private static double[] computeSlTp(boolean isLong, double entryPrice,
//                                          double[] hi5m, double[] lo5m, double atr5m,
//                                          double stLevel, double tickSize) {
//         double sl, tp;
//         if (isLong) {
//             double swingLow = recentLow(lo5m, SWING_LOOKBACK_BARS);
//             double structuralLow = Math.min(swingLow, stLevel);
//             double raw = structuralLow - SL_ATR_BUFFER_MULT * atr5m;
//             double hardFloor = entryPrice * (1 - SL_MAX_PERCENT / 100.0);
//             sl = Math.max(raw, hardFloor);
//             double risk = entryPrice - sl;
//             tp = entryPrice + RR_TARGET * risk;
//         } else {
//             double swingHigh = recentHigh(hi5m, SWING_LOOKBACK_BARS);
//             double structuralHigh = Math.max(swingHigh, stLevel);
//             double raw = structuralHigh + SL_ATR_BUFFER_MULT * atr5m;
//             double hardCeil = entryPrice * (1 + SL_MAX_PERCENT / 100.0);
//             sl = Math.min(raw, hardCeil);
//             double risk = sl - entryPrice;
//             tp = entryPrice - RR_TARGET * risk;
//         }
//         sl = roundToTick(sl, tickSize);
//         tp = roundToTick(tp, tickSize);
//         return new double[]{sl, tp};
//     }

//     private static double recentLow(double[] lo, int lookback) {
//         int n = lo.length;
//         int start = Math.max(0, n - lookback);
//         double min = Double.POSITIVE_INFINITY;
//         for (int i = start; i < n; i++) min = Math.min(min, lo[i]);
//         return min;
//     }

//     private static double recentHigh(double[] hi, int lookback) {
//         int n = hi.length;
//         int start = Math.max(0, n - lookback);
//         double max = Double.NEGATIVE_INFINITY;
//         for (int i = start; i < n; i++) max = Math.max(max, hi[i]);
//         return max;
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

//     private static double calcQuantity(double price, String pair) {
//         double usdtInrRate = 98.0;
//         double qty = MAX_MARGIN / (price * usdtInrRate);
//         double finalQty = INTEGER_QTY_PAIRS.contains(pair)
//                 ? Math.floor(qty)
//                 : Math.floor(qty * 100) / 100.0;
//         return Math.max(finalQty, 0);
//     }

//     public static void main(String[] args) {
//         System.out.println("=== Scalp bot starting (fixed margin, fixed SL/TP, no exit-monitoring) ===");
//         initInstrumentCache();

//         while (true) {
//             try {
//                 runEntryScan();
//             } catch (Throwable t) {
//                 System.err.println("[MAIN-LOOP] Uncaught error, continuing: " + t.getMessage());
//                 t.printStackTrace();
//             }

//             try {
//                 TimeUnit.MILLISECONDS.sleep(SCALP_ENTRY_SCAN_INTERVAL_MS);
//             } catch (InterruptedException ignored) {
//                 Thread.currentThread().interrupt();
//                 break;
//             }
//         }
//     }

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
//                 if (active.contains(pair)) continue;

//                 long lastTrade = lastTradeTime.getOrDefault(pair, 0L);
//                 if (System.currentTimeMillis() - lastTrade < SCALP_COOLDOWN_MS) continue;

//                 JSONArray raw5m = dropLastIfForming(
//                         getCandlestickData(pair, RES_5M, BASE_5M_FETCH_COUNT));
//                 if (raw5m == null || raw5m.length() < EMA_MID + ST_PERIOD + 5) continue;

//                 JSONArray raw30m = aggregateCandles(raw5m, GROUP_30M_FROM_5M);
//                 DirectionResult dir30m = analyzeDirection(raw30m);
//                 if (!dir30m.valid || (!dir30m.bullish && !dir30m.bearish)) continue;

//                 JSONArray raw15m = aggregateCandles(raw5m, GROUP_15M_FROM_5M);
//                 SetupResult setup15m = analyzeSetup(raw15m);
//                 if (!setup15m.valid) continue;

//                 boolean setupMatches30m = (dir30m.bullish && setup15m.bullish)
//                         || (dir30m.bearish && setup15m.bearish);
//                 if (!setupMatches30m) continue;

//                 boolean trendUp = dir30m.bullish;

//                 JSONArray raw1h = dropLastIfForming(
//                         getCandlestickData(pair, RES_1H, BASE_1H_FETCH_COUNT));
//                 DirectionResult dir1h = analyzeDirection(raw1h);
//                 if (!dir1h.valid) continue;

//                 boolean dir1hMatches = (trendUp && dir1h.bullish) || (!trendUp && dir1h.bearish);
//                 if (!dir1hMatches) continue;

//                 EntryResult entry5m = analyzeEntry(raw5m, trendUp);
//                 if (!entry5m.valid) continue;
//                 if (!entry5m.triggered) {
//                     continue;
//                 }

//                 System.out.println("\n==== " + pair + " ====");
//                 System.out.printf("  [1H] %s | [30M] %s | [15M] %s | [5M-Entry] %s%n",
//                         dir1h.bullish ? "BULLISH" : "BEARISH",
//                         trendUp ? "BULLISH" : "BEARISH",
//                         setup15m.bullish ? "BULLISH" : "BEARISH",
//                         entry5m.reason);

//                 String side = trendUp ? "buy" : "sell";
//                 System.out.println("  ╔══════════════════════════════════════════════════╗");
//                 System.out.println("  ║  SCALP TRIGGER → " + side.toUpperCase() + " " + pair);
//                 System.out.println("  ╚══════════════════════════════════════════════════╝");

//                 double currentPrice = getLastPrice(pair);
//                 if (currentPrice <= 0) continue;
//                 double qty = calcQuantity(currentPrice, pair);
//                 if (qty <= 0) continue;
//                 double tickSize = getTickSize(pair);

//                 System.out.printf("  Placing %s | price=%.6f | qty=%.4f | lev=%dx%n",
//                         side.toUpperCase(), currentPrice, qty, LEVERAGE);

//                 JSONObject resp = placeFuturesOrder(side, pair, qty, LEVERAGE,
//                         "email_notification", "isolated", "INR", currentPrice);
//                 if (resp == null || !resp.has("id")) {
//                     System.out.println("  Order failed: " + resp);
//                     continue;
//                 }

//                 System.out.println("  Order placed! id=" + resp.getString("id"));
//                 lastTradeTime.put(pair, System.currentTimeMillis());

//                 double entry = getEntryPrice(pair, resp.getString("id"));
//                 if (entry <= 0) {
//                     System.out.println("  Could not confirm entry within window — TP/SL will be handled by safety sweep");
//                     active.add(pair);
//                     continue;
//                 }

//                 System.out.printf("  Entry confirmed: %.6f%n", entry);

//                 double[] hi5m = extractHighs(raw5m);
//                 double[] lo5m = extractLows(raw5m);
//                 double stLevel = trendUp ? setup15m.stLower : setup15m.stUpper;
//                 double[] slTp = computeSlTp(trendUp, entry, hi5m, lo5m, entry5m.atr5m, stLevel, tickSize);
//                 double[] clamped = sanityClampSlTp(trendUp, entry, slTp[0], slTp[1], tickSize);
//                 double slPrice = clamped[0], tpPrice = clamped[1];
//                 double slPct = Math.abs(entry - slPrice) / entry * 100;
//                 double tpPct = Math.abs(tpPrice - entry) / entry * 100;

//                 System.out.printf("  SL=%.6f (%.3f%%) | TP=%.6f (%.3f%%) | RR target=%.2f%n",
//                         slPrice, slPct, tpPrice, tpPct, RR_TARGET);

//                 String posId = getPositionId(pair);
//                 if (posId != null) {
//                     setTpSlWithRetry(posId, tpPrice, slPrice, pair);
//                 } else {
//                     System.out.println("  Position ID not found after retries — TP/SL will be handled by safety sweep");
//                 }

//                 active.add(pair);

//             } catch (Exception e) {
//                 System.err.println("Error on " + pair + ": " + e.getMessage());
//             }
//         }

//         System.out.println("\n=== Scalp scan complete ===");
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
//                 JSONArray raw5m = dropLastIfForming(
//                         getCandlestickData(pair, RES_5M, BASE_5M_FETCH_COUNT));

//                 if (raw5m == null || raw5m.length() < EMA_MID + ST_PERIOD + 5) {
//                     System.out.println("  [SWEEP] insufficient 5M data for " + pair
//                             + " — will retry next run");
//                     continue;
//                 }

//                 JSONArray raw15mSweep = aggregateCandles(raw5m, GROUP_15M_FROM_5M);
//                 SetupResult setup15mSweep = analyzeSetup(raw15mSweep);
//                 if (!setup15mSweep.valid) {
//                     System.out.println("  [SWEEP] insufficient 15M data for " + pair
//                             + " — will retry next run");
//                     continue;
//                 }

//                 double[] hi5m = extractHighs(raw5m);
//                 double[] lo5m = extractLows(raw5m);
//                 double[] cl5m = extractCloses(raw5m);
//                 double atr5m = calcATR(hi5m, lo5m, cl5m, ATR_PERIOD);
//                 if (atr5m <= 0) {
//                     System.out.println("  [SWEEP] invalid 5M ATR for " + pair
//                             + " — will retry next run");
//                     continue;
//                 }

//                 double posQty = pos.optDouble("active_pos", 0);
//                 boolean isLong = posQty >= 0;
//                 double stLevel = isLong ? setup15mSweep.stLower : setup15mSweep.stUpper;

//                 double tick = getTickSize(pair);
//                 double[] slTp = computeSlTp(isLong, avgPrice, hi5m, lo5m, atr5m, stLevel, tick);
//                 double[] clamped = sanityClampSlTp(isLong, avgPrice, slTp[0], slTp[1], tick);
//                 double sl = clamped[0], tp = clamped[1];

//                 String posId = pos.optString("id", null);
//                 if (posId != null) {
//                     System.out.printf("  [SWEEP] %s fallback SL=%.6f TP=%.6f (RR target=%.2f)%n", pair, sl, tp, RR_TARGET);
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

//     private static double calcRSI(double[] closes, int period) {
//         if (closes.length < period + 1) return 50.0;

//         double avgGain = 0, avgLoss = 0;
//         for (int i = 1; i <= period; i++) {
//             double change = closes[i] - closes[i - 1];
//             if (change > 0) avgGain += change;
//             else avgLoss += -change;
//         }
//         avgGain /= period;
//         avgLoss /= period;

//         for (int i = period + 1; i < closes.length; i++) {
//             double change = closes[i] - closes[i - 1];
//             double gain = Math.max(change, 0);
//             double loss = Math.max(-change, 0);
//             avgGain = (avgGain * (period - 1) + gain) / period;
//             avgLoss = (avgLoss * (period - 1) + loss) / period;
//         }

//         if (avgLoss == 0) return 100.0;
//         double rs = avgGain / avgLoss;
//         return 100.0 - (100.0 / (1.0 + rs));
//     }

//     private static BigDecimal roundToTickBD(double price, double tick) {
//         if (tick <= 0) return BigDecimal.valueOf(price);
//         BigDecimal bdPrice = BigDecimal.valueOf(price);
//         BigDecimal bdTick  = BigDecimal.valueOf(tick);
//         BigDecimal multiples = bdPrice.divide(bdTick, 0, RoundingMode.HALF_UP);
//         BigDecimal result = multiples.multiply(bdTick);
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
//     private static double[] extractVolumes(JSONArray a) {
//         double[] o = new double[a.length()];
//         for (int i = 0; i < a.length(); i++) o[i] = a.getJSONObject(i).optDouble("volume", 0);
//         return o;
//     }

//     private static JSONArray getCandlestickData(String pair, String resolution, int count) {
//         try {
//             long minsPerBar;
//             switch (resolution) {
//                 case "1":   minsPerBar = 1;   break;
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
//             double volSum = 0;
//             for (int j = i; j < i + groupSize; j++) {
//                 JSONObject c = source.getJSONObject(j);
//                 high = Math.max(high, c.getDouble("high"));
//                 low  = Math.min(low,  c.getDouble("low"));
//                 volSum += c.optDouble("volume", 0);
//             }
//             JSONObject merged = new JSONObject();
//             merged.put("open", open);
//             merged.put("close", close);
//             merged.put("high", high);
//             merged.put("low", low);
//             merged.put("volume", volSum);
//             result.put(merged);
//         }
//         return result;
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

//     private static final double MAX_MARGIN = 1500.0;
//     private static final int LEVERAGE = 12;

//     private static final int MAX_ENTRY_PRICE_CHECKS = 20;
//     private static final int ENTRY_CHECK_DELAY_MS    = 1000;

//     private static final int  TPSL_MAX_RETRIES    = 3;
//     private static final long TPSL_RETRY_DELAY_MS = 2000L;

//     private static final long TICK_CACHE_TTL_MS = 3_600_000L;

//     private static final int MAX_OPEN_POSITIONS = 120;

//     private static final int  POSITION_ID_MAX_RETRIES = 5;
//     private static final long POSITION_ID_RETRY_DELAY_MS = 1500L;

//     // =========================================================================
//     // Direction -> 1H+30M, Setup -> 15M, Entry -> 5M.
//     // =========================================================================
//     private static final int EMA_FAST = 9;
//     private static final int EMA_MID  = 21;
//     private static final int ATR_PERIOD = 14;
//     private static final int ST_PERIOD     = 10;
//     private static final double ST_MULTIPLIER = 3.0;

//     private static final String RES_5M = "5";
//     private static final String RES_1H = "60";

//     private static final int BASE_5M_FETCH_COUNT = 250;
//     private static final int GROUP_15M_FROM_5M = 3;
//     private static final int GROUP_30M_FROM_5M = 6;
//     private static final int BASE_1H_FETCH_COUNT = 55;

//     // ---- RSI (momentum filter) ----
//     private static final int RSI_PERIOD = 14;
//     private static final double RSI_LONG_MIN  = 40, RSI_LONG_MAX  = 70;
//     private static final double RSI_SHORT_MIN = 30, RSI_SHORT_MAX = 60;

//     // ---- Entry (5M) filter thresholds ----
//     private static final int    ENTRY_VOLUME_LOOKBACK   = 20;
//     private static final double ENTRY_VOLUME_MULTIPLIER = 1.05;

//     private static final double ENTRY_PULLBACK_MAX_ATR  = 0.6;
//     private static final double ENTRY_MIN_BODY_RATIO    = 0.30;

//     private static final int    ENTRY_VWAP_LOOKBACK      = 20;
//     private static final double ENTRY_MAX_VWAP_DIST_ATR  = 0.6;

//     private static final int ENTRY_CONFIRMATION_MIN_SCORE = 3;

//     // ---- SL/TP sizing — FIXED, set once at entry, never adjusted after. ----
//     private static final int    SWING_LOOKBACK_BARS = 20;
//     private static final double SL_ATR_BUFFER_MULT  = 2.0;
//     private static final double SL_MAX_PERCENT = 4.5;
//     private static final double RR_TARGET = 1.5;

//     private static final double LIMIT_ORDER_BUFFER_PCT = 0.0005;

//     private static final long SCALP_COOLDOWN_MS            = 5 * 60 * 1000L;
//     private static final long SCALP_ENTRY_SCAN_INTERVAL_MS = 20 * 1000L;

//     // EMA9 slope/angle check — used on 1H/30M (Direction), 15M (Setup),
//     // and NOW also 5M (Entry) — see analyzeEntry() below.
//     private static final int    EMA_SLOPE_LOOKBACK_BARS = 5;
//     private static final double EMA_SLOPE_MIN_ATR        = 0.15;

//     private static final Map<String, JSONObject> instrumentCache = new ConcurrentHashMap<>();
//     private static long lastCacheUpdate = 0;
//     private static final Map<String, Long> lastTradeTime = new ConcurrentHashMap<>();

//     private static final String[] COIN_SYMBOLS = {
//        "PIEVERSE","XAU","APE","ERA","US","RAVE","EDEN","LIT","BREV","MAGMA","BLESS","ZAMA",
//         "FRAX","ACU","1000FLOKI","ELSA","LINEA","SPACE","CLO","FIGHT","UMA","MEGA","MAV","TRIA",
//         "YGG","OPN","ROBO","SUI","GLM","MANTRA","SEI","CAKE","AUCTION","SENT","BSB","BASED","IRYS",
//         "ACE","WET","CL","PRL","GENIUS","WIF","MANTA","LSK","AIGENSYN","PHAROS","JUP","AXL","BOME",
//         "SLX","ZEST","AIOT","VVV","CAP","DATAIP","GRVT","TAO","BR","TURBO","BTC","ETH","ZK","LISTA",
//         "A","LTC","XAG","COAI","HANA","ZRO","SKYAI","COPPER","RARE","ETC","M","AKE","XLM","PIXEL",
//         "XAN","ADA","CROSS","XMR","G","DASH","ZEC","ATOM","TRUTH","BCH","NEO","IOST","FLUX","ALGO",
//         "ZRX","COMP","WLFI","POL","DOGE","BAND","OPG","FIDA","PROM","SANTOS","RLC","1000000MOG",
//         "GRASS","PNUT","TRB","KAIA","ARX","XAI","S","4","COTI","CHR","SOLV","SAGA","ORCA","1000LUNC",
//         "MOVE","VIRTUAL","ME","IOTX","GIGGLE","AVA","VELODROME","AIXBT","KMNO","LA","DEXE","ZBT",
//         "GRIFFAIN","BLUAI","CTSI","ROSE","TURTLE","IMX","SUN","APR","TA","ON","BIO","COOKIE",
//         "AVAAI","DOT","TRUMP","MELANIA","GMT","FLOCK","CLANKER","CYS","SUSHI","VTHO","DIA",
//         "SLP","GOAT","BMT","KGEN","GWEI","MUBARAK","LDO","ESP","DRIFT","FORM","PLUME","NIL",
//         "UNI","ZORA","RECALL","INIT","BZ","PARTI","NATGAS","SPX","BANK","AVAX","RIVER","BILL",
//         "ATH","XRP","KERNEL","JST","PUNDIX","HAEDAL","ALPINE","SOON","SOPH","HUMA","TRX","LINK",
//         "HYPE","HIVE","TAIKO","TAG","MYX","NEWT","AIN","USUAL","PUMP","ICNT","BNB","H","BAT",
//         "QTUM","ARC","AIO","BEAT","BTR","ALCH","THETA","VELVET","ARIA","PTB","UB","LIGHT","FF",
//         "EVAAI","GMX","LYN","TAC","LAB","ENJ","AT","MMT","UAI","AAVE","JCT","KSM","HEI","JASMY",
//         "NEAR","TST","SOL","OP","PLAY","INJ","STG","HOLO","ASR","B","LUNA2","RSR","INX","KAT",
//         "ICP","QNT","MAGIC","T","MINA","STX","ACH","LQTY","ID","GRT","NEIRO","XVS","1INCH","SAND",
//         "ANKR","RVN","SFP","KAVA","MANA","HBAR","ARB","MTL","C98","TUT","SIREN","MASK","1000XEC",
//         "AR","ARPA","FIL","LPT","ENS","PEOPLE","LUMIA","DUSK","FLOW","XVG","ARKM","POPCAT","ARK",
//         "MOODENG","SAFE","AXS","BICO","BIGTIME","WAXP","GAS","POWR","TIA","CHIP","STO","ORDI",
//         "BEAMX","1000BONK","PYTH","ETHW","1000RATS","ANIME","OPEN","DYM","BERA","PORTAL","BB",
//         "BANANAS31","CFX","SSV","TNSR","EDU","JELLYJELLY","BLUR","WAL","FHE","WCT","DEEP","SXT",
//         "NAORIS","OG","CVC","AWE","O","BEL","JOE","SQD","1000PEPE","CARV","FET","SAPIEN","MEME",
//         "AVNT","XPIN","ILV","KAS","BNT","STBL","BSV","RIF","SUPER","USTC","METIS","ETHFI","ENA",
//         "1MBABYDOGE","CATI","HMSTR","GPS","SHELL","KAITO","ACT","RPL","BAN","THE","AKT","MORPHO",
//         "CHILLGUY","AERO","MOCA","PENGU","PHA","RED","EPIC","TREE","1000CAT","MAVIA","FARTCOIN",
//         "PAXG","IN","ORDER","VET","ZEN","STABLE","CHZ","NIGHT","NOM","ZKP","SKR","GRAM","BIRB",
//         "CTR","KNC","ZIL","YFI","EGLD","RUNE","ASTR","ONE","1000SHIB","API3","SPELL","WOO","APT",
//         "PENDLE","AGLD","CYBER","CKB","ONG","MOVR","POLYX","TWT","STEEM","ALT","ZETA","REZ","RENDER",
//         "RONIN","STRK","W","SCR","CETUS","IO","MEW","SWARMS","SONIC","PIPPIN","PROMPT","MERL","F",
//         "ESPORTS","PROVE","XNY","USELESS","HEMI","Q","SKY","ZKC","FLUID","MITO","CFG","EDGE","RE",
//         "YB","MET","DOS","FOGO","BTW","ALLO","BROCCOLI714","HYPER","XPL","RESOLV","ASTER","KITE",
//         "SIGN","HOME","MON","CC","SAHARA","MIRA","EUL","TOWNS","SYRUP","C","DOLO","ALICE","BABY",
//         "SOMI","NOT","BARD","SPK","POWER","2Z","BANANA","ENSO","SYN","NXPC","GUN","XTZ","ONT","SKL",
//         "HOT","JTO","DOGS","EIGEN","GTC","GALA","NMR","CGPT","ZEREBRO","VANA","OGN","CELO","USDC",
//         "COW","0G","IOTA","SNX","DYDX","WLD","1000SATS","ONDO","AEVO","BRETT","LAYER","CRV","TLM","KOMA"
//     };

//     private static final Set<String> INTEGER_QTY_PAIRS = Stream.of(COIN_SYMBOLS)
//             .flatMap(s -> Stream.of("B-" + s + "_USDT", s + "_USDT"))
//             .collect(Collectors.toCollection(HashSet::new));

//     private static final String[] COINS_TO_TRADE = Stream.of(COIN_SYMBOLS)
//             .map(s -> "B-" + s + "_USDT")
//             .toArray(String[]::new);

//     private static class DirectionResult {
//         boolean valid;
//         boolean bullish;
//         boolean bearish;
//     }

//     private static double[] calcEMASeries(double[] d, int period) {
//         double[] out = new double[d.length];
//         if (d.length < period) {
//             double last = d.length > 0 ? d[d.length - 1] : 0;
//             Arrays.fill(out, last);
//             return out;
//         }
//         double k = 2.0 / (period + 1);
//         double ema = 0;
//         for (int i = 0; i < period; i++) ema += d[i];
//         ema /= period;
//         for (int i = 0; i < period; i++) out[i] = ema;
//         out[period - 1] = ema;
//         for (int i = period; i < d.length; i++) {
//             ema = d[i] * k + ema * (1 - k);
//             out[i] = ema;
//         }
//         return out;
//     }

//     private static DirectionResult analyzeDirection(JSONArray candles) {
//         DirectionResult r = new DirectionResult();
//         if (candles == null || candles.length() < EMA_MID + Math.max(ATR_PERIOD, ST_PERIOD) + 5) {
//             r.valid = false;
//             return r;
//         }
//         double[] cl = extractCloses(candles);
//         double[] hi = extractHighs(candles);
//         double[] lo = extractLows(candles);

//         double ema9  = calcEMA(cl, EMA_FAST);
//         double ema21 = calcEMA(cl, EMA_MID);
//         double atr   = calcATR(hi, lo, cl, ATR_PERIOD);
//         double price = cl[cl.length - 1];

//         boolean[] stSeries = calcSupertrend(hi, lo, cl, ST_PERIOD, ST_MULTIPLIER);
//         boolean stGreen = stSeries[stSeries.length - 1];

//         double[] ema9Series = calcEMASeries(cl, EMA_FAST);
//         int n = ema9Series.length;
//         int lookback = Math.min(EMA_SLOPE_LOOKBACK_BARS, n - 1);
//         double emaSlope = ema9Series[n - 1] - ema9Series[n - 1 - lookback];
//         boolean slopeUp   = atr > 0 && emaSlope >= EMA_SLOPE_MIN_ATR * atr;
//         boolean slopeDown = atr > 0 && emaSlope <= -EMA_SLOPE_MIN_ATR * atr;

//         boolean priceAboveBoth = price > ema9 && price > ema21;
//         boolean priceBelowBoth = price < ema9 && price < ema21;

//         r.valid = true;
//         r.bullish = (ema9 > ema21) && slopeUp && stGreen && priceAboveBoth;
//         r.bearish = (ema9 < ema21) && slopeDown && !stGreen && priceBelowBoth;
//         return r;
//     }

//     private static class SetupResult {
//         boolean valid;
//         boolean bullish;
//         boolean bearish;
//         double  atr;
//         double  stLower;
//         double  stUpper;
//     }

//     private static SetupResult analyzeSetup(JSONArray candles) {
//         SetupResult r = new SetupResult();
//         if (candles == null || candles.length() < EMA_MID + ST_PERIOD + 5) {
//             r.valid = false;
//             return r;
//         }
//         double[] cl = extractCloses(candles);
//         double[] hi = extractHighs(candles);
//         double[] lo = extractLows(candles);

//         double ema9  = calcEMA(cl, EMA_FAST);
//         double ema21 = calcEMA(cl, EMA_MID);
//         double price = cl[cl.length - 1];
//         r.atr = calcATR(hi, lo, cl, ATR_PERIOD);

//         boolean[] stSeries = calcSupertrend(hi, lo, cl, ST_PERIOD, ST_MULTIPLIER);
//         boolean stGreen = stSeries[stSeries.length - 1];

//         double[] bands = calcSupertrendBands(hi, lo, cl, ST_PERIOD, ST_MULTIPLIER);
//         r.stLower = bands[0];
//         r.stUpper = bands[1];

//         double[] ema9Series = calcEMASeries(cl, EMA_FAST);
//         int n = ema9Series.length;
//         int lookback = Math.min(EMA_SLOPE_LOOKBACK_BARS, n - 1);
//         double emaSlope = ema9Series[n - 1] - ema9Series[n - 1 - lookback];
//         boolean slopeUp   = r.atr > 0 && emaSlope >= EMA_SLOPE_MIN_ATR * r.atr;
//         boolean slopeDown = r.atr > 0 && emaSlope <= -EMA_SLOPE_MIN_ATR * r.atr;

//         boolean priceAboveBoth = price > ema9 && price > ema21;
//         boolean priceBelowBoth = price < ema9 && price < ema21;

//         r.valid = true;
//         r.bullish = (ema9 > ema21) && stGreen && slopeUp && priceAboveBoth;
//         r.bearish = (ema9 < ema21) && !stGreen && slopeDown && priceBelowBoth;
//         return r;
//     }

//     // =========================================================================
//     // Entry result (5M) — NEW: EMA9-slope check added as a MANDATORY
//     // condition, matching the same pattern already used on 1H/30M/15M. This
//     // catches cases where price is technically "pulled back near EMA9/21"
//     // (the existing pullback check) but the EMA9 itself has gone flat or
//     // started curving the other way — i.e. momentum on the entry timeframe
//     // itself is fading, not just a healthy pullback within an intact trend.
//     // =========================================================================
//     private static class EntryResult {
//         boolean valid;
//         boolean triggered;
//         double entryClose, entryOpen, entryHigh, entryLow;
//         double atr5m;
//         String reason;
//     }

//     private static EntryResult analyzeEntry(JSONArray raw5m, boolean trendUp) {
//         EntryResult t = new EntryResult();
//         int minBars = Math.max(EMA_MID, Math.max(ENTRY_VOLUME_LOOKBACK, ENTRY_VWAP_LOOKBACK)) + RSI_PERIOD + 5;
//         if (raw5m == null || raw5m.length() < minBars) {
//             t.valid = false;
//             return t;
//         }

//         double[] cl  = extractCloses(raw5m);
//         double[] op  = extractOpens(raw5m);
//         double[] hi  = extractHighs(raw5m);
//         double[] lo  = extractLows(raw5m);
//         double[] vol = extractVolumes(raw5m);
//         int n = cl.length;

//         double ema9  = calcEMA(cl, EMA_FAST);
//         double ema21 = calcEMA(cl, EMA_MID);
//         double atr5m = calcATR(hi, lo, cl, ATR_PERIOD);
//         t.atr5m = atr5m;

//         double entryClose = cl[n - 1], entryOpen = op[n - 1];
//         double entryHigh  = hi[n - 1], entryLow  = lo[n - 1];
//         t.entryClose = entryClose; t.entryOpen = entryOpen;
//         t.entryHigh = entryHigh;   t.entryLow = entryLow;

//         double distToEma9  = Math.abs(entryClose - ema9);
//         double distToEma21 = Math.abs(entryClose - ema21);
//         double nearestEmaDist = Math.min(distToEma9, distToEma21);
//         boolean pulledBack = atr5m > 0 && nearestEmaDist <= ENTRY_PULLBACK_MAX_ATR * atr5m;

//         boolean directionalCandle = trendUp ? (entryClose > entryOpen) : (entryClose < entryOpen);
//         double body  = Math.abs(entryClose - entryOpen);
//         double range = entryHigh - entryLow;
//         boolean notDoji = range > 0 && (body / range) >= ENTRY_MIN_BODY_RATIO;

//         // NEW — 5M EMA9-slope check (same ATR-scaled method used on the
//         // other three timeframes).
//         double[] ema9Series5m = calcEMASeries(cl, EMA_FAST);
//         int lookback5m = Math.min(EMA_SLOPE_LOOKBACK_BARS, n - 1);
//         double emaSlope5m = ema9Series5m[n - 1] - ema9Series5m[n - 1 - lookback5m];
//         boolean slope5mOk = trendUp
//                 ? (atr5m > 0 && emaSlope5m >= EMA_SLOPE_MIN_ATR * atr5m)
//                 : (atr5m > 0 && emaSlope5m <= -EMA_SLOPE_MIN_ATR * atr5m);

//         boolean mandatoryOk = pulledBack && directionalCandle && notDoji && slope5mOk;

//         int volStart = Math.max(0, n - 1 - ENTRY_VOLUME_LOOKBACK);
//         double avgVol = 0; int cnt = 0;
//         for (int i = volStart; i < n - 1; i++) { avgVol += vol[i]; cnt++; }
//         avgVol = cnt > 0 ? avgVol / cnt : 0;
//         boolean volumeOk = avgVol > 0 && vol[n - 1] >= avgVol * ENTRY_VOLUME_MULTIPLIER;

//         double rsi = calcRSI(cl, RSI_PERIOD);
//         boolean rsiOk = trendUp
//                 ? (rsi >= RSI_LONG_MIN && rsi <= RSI_LONG_MAX)
//                 : (rsi >= RSI_SHORT_MIN && rsi <= RSI_SHORT_MAX);

//         int vwapStart = Math.max(0, n - ENTRY_VWAP_LOOKBACK);
//         double cumPV = 0, cumV = 0;
//         for (int i = vwapStart; i < n; i++) {
//             double typical = (hi[i] + lo[i] + cl[i]) / 3.0;
//             cumPV += typical * vol[i];
//             cumV  += vol[i];
//         }
//         double vwap = cumV > 0 ? cumPV / cumV : entryClose;
//         double distFromVwap = Math.abs(entryClose - vwap);
//         boolean vwapOk = atr5m > 0 && distFromVwap <= ENTRY_MAX_VWAP_DIST_ATR * atr5m;

//         int confirmationScore = (volumeOk ? 1 : 0) + (rsiOk ? 1 : 0) + (vwapOk ? 1 : 0);

//         t.triggered = mandatoryOk && confirmationScore >= ENTRY_CONFIRMATION_MIN_SCORE;
//         t.valid = true;
//         t.reason = String.format(
//                 "mandatory[pulledBack=%s directional=%s notDoji=%s slope5m=%s(%.6f)] confirmation[volume=%s(%.2fx) rsi=%.1f(ok=%s) vwap=%s] score=%d/3",
//                 pulledBack, directionalCandle, notDoji, slope5mOk, emaSlope5m,
//                 volumeOk, avgVol > 0 ? vol[n - 1] / avgVol : 0,
//                 rsi, rsiOk, vwapOk, confirmationScore);
//         return t;
//     }

//     private static JSONArray dropLastIfForming(JSONArray arr) {
//         if (arr == null || arr.length() < 2) return arr;
//         JSONArray out = new JSONArray();
//         for (int i = 0; i < arr.length() - 1; i++) out.put(arr.getJSONObject(i));
//         return out;
//     }

//     // =========================================================================
//     // FIXED SL/TP — UNCHANGED.
//     // =========================================================================
//     private static double[] computeSlTp(boolean isLong, double entryPrice,
//                                          double[] hi5m, double[] lo5m, double atr5m,
//                                          double stLevel, double tickSize) {
//         double sl, tp;
//         if (isLong) {
//             double swingLow = recentLow(lo5m, SWING_LOOKBACK_BARS);
//             double structuralLow = Math.min(swingLow, stLevel);
//             double raw = structuralLow - SL_ATR_BUFFER_MULT * atr5m;
//             double hardFloor = entryPrice * (1 - SL_MAX_PERCENT / 100.0);
//             sl = Math.max(raw, hardFloor);
//             double risk = entryPrice - sl;
//             tp = entryPrice + RR_TARGET * risk;
//         } else {
//             double swingHigh = recentHigh(hi5m, SWING_LOOKBACK_BARS);
//             double structuralHigh = Math.max(swingHigh, stLevel);
//             double raw = structuralHigh + SL_ATR_BUFFER_MULT * atr5m;
//             double hardCeil = entryPrice * (1 + SL_MAX_PERCENT / 100.0);
//             sl = Math.min(raw, hardCeil);
//             double risk = sl - entryPrice;
//             tp = entryPrice - RR_TARGET * risk;
//         }
//         sl = roundToTick(sl, tickSize);
//         tp = roundToTick(tp, tickSize);
//         return new double[]{sl, tp};
//     }

//     private static double recentLow(double[] lo, int lookback) {
//         int n = lo.length;
//         int start = Math.max(0, n - lookback);
//         double min = Double.POSITIVE_INFINITY;
//         for (int i = start; i < n; i++) min = Math.min(min, lo[i]);
//         return min;
//     }

//     private static double recentHigh(double[] hi, int lookback) {
//         int n = hi.length;
//         int start = Math.max(0, n - lookback);
//         double max = Double.NEGATIVE_INFINITY;
//         for (int i = start; i < n; i++) max = Math.max(max, hi[i]);
//         return max;
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

//     private static double calcQuantity(double price, String pair) {
//         double usdtInrRate = 98.0;
//         double qty = MAX_MARGIN / (price * usdtInrRate);
//         double finalQty = INTEGER_QTY_PAIRS.contains(pair)
//                 ? Math.floor(qty)
//                 : Math.floor(qty * 100) / 100.0;
//         return Math.max(finalQty, 0);
//     }

//     public static void main(String[] args) {
//         System.out.println("=== Scalp bot starting (fixed margin, fixed SL/TP, no exit-monitoring) ===");
//         initInstrumentCache();

//         while (true) {
//             try {
//                 runEntryScan();
//             } catch (Throwable t) {
//                 System.err.println("[MAIN-LOOP] Uncaught error, continuing: " + t.getMessage());
//                 t.printStackTrace();
//             }

//             try {
//                 TimeUnit.MILLISECONDS.sleep(SCALP_ENTRY_SCAN_INTERVAL_MS);
//             } catch (InterruptedException ignored) {
//                 Thread.currentThread().interrupt();
//                 break;
//             }
//         }
//     }

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
//                 if (active.contains(pair)) continue;

//                 long lastTrade = lastTradeTime.getOrDefault(pair, 0L);
//                 if (System.currentTimeMillis() - lastTrade < SCALP_COOLDOWN_MS) continue;

//                 JSONArray raw5m = dropLastIfForming(
//                         getCandlestickData(pair, RES_5M, BASE_5M_FETCH_COUNT));
//                 if (raw5m == null || raw5m.length() < EMA_MID + ST_PERIOD + 5) continue;

//                 JSONArray raw30m = aggregateCandles(raw5m, GROUP_30M_FROM_5M);
//                 DirectionResult dir30m = analyzeDirection(raw30m);
//                 if (!dir30m.valid || (!dir30m.bullish && !dir30m.bearish)) continue;

//                 JSONArray raw15m = aggregateCandles(raw5m, GROUP_15M_FROM_5M);
//                 SetupResult setup15m = analyzeSetup(raw15m);
//                 if (!setup15m.valid) continue;

//                 boolean setupMatches30m = (dir30m.bullish && setup15m.bullish)
//                         || (dir30m.bearish && setup15m.bearish);
//                 if (!setupMatches30m) continue;

//                 boolean trendUp = dir30m.bullish;

//                 JSONArray raw1h = dropLastIfForming(
//                         getCandlestickData(pair, RES_1H, BASE_1H_FETCH_COUNT));
//                 DirectionResult dir1h = analyzeDirection(raw1h);
//                 if (!dir1h.valid) continue;

//                 boolean dir1hMatches = (trendUp && dir1h.bullish) || (!trendUp && dir1h.bearish);
//                 if (!dir1hMatches) continue;

//                 EntryResult entry5m = analyzeEntry(raw5m, trendUp);
//                 if (!entry5m.valid) continue;
//                 if (!entry5m.triggered) {
//                     continue;
//                 }

//                 System.out.println("\n==== " + pair + " ====");
//                 System.out.printf("  [1H] %s | [30M] %s | [15M] %s | [5M-Entry] %s%n",
//                         dir1h.bullish ? "BULLISH" : "BEARISH",
//                         trendUp ? "BULLISH" : "BEARISH",
//                         setup15m.bullish ? "BULLISH" : "BEARISH",
//                         entry5m.reason);

//                 String side = trendUp ? "buy" : "sell";
//                 System.out.println("  ╔══════════════════════════════════════════════════╗");
//                 System.out.println("  ║  SCALP TRIGGER → " + side.toUpperCase() + " " + pair);
//                 System.out.println("  ╚══════════════════════════════════════════════════╝");

//                 double currentPrice = getLastPrice(pair);
//                 if (currentPrice <= 0) continue;
//                 double qty = calcQuantity(currentPrice, pair);
//                 if (qty <= 0) continue;
//                 double tickSize = getTickSize(pair);

//                 System.out.printf("  Placing %s | price=%.6f | qty=%.4f | lev=%dx%n",
//                         side.toUpperCase(), currentPrice, qty, LEVERAGE);

//                 JSONObject resp = placeFuturesOrder(side, pair, qty, LEVERAGE,
//                         "email_notification", "isolated", "INR", currentPrice);
//                 if (resp == null || !resp.has("id")) {
//                     System.out.println("  Order failed: " + resp);
//                     continue;
//                 }

//                 System.out.println("  Order placed! id=" + resp.getString("id"));
//                 lastTradeTime.put(pair, System.currentTimeMillis());

//                 double entry = getEntryPrice(pair, resp.getString("id"));
//                 if (entry <= 0) {
//                     System.out.println("  Could not confirm entry within window — TP/SL will be handled by safety sweep");
//                     active.add(pair);
//                     continue;
//                 }

//                 System.out.printf("  Entry confirmed: %.6f%n", entry);

//                 double[] hi5m = extractHighs(raw5m);
//                 double[] lo5m = extractLows(raw5m);
//                 double stLevel = trendUp ? setup15m.stLower : setup15m.stUpper;
//                 double[] slTp = computeSlTp(trendUp, entry, hi5m, lo5m, entry5m.atr5m, stLevel, tickSize);
//                 double[] clamped = sanityClampSlTp(trendUp, entry, slTp[0], slTp[1], tickSize);
//                 double slPrice = clamped[0], tpPrice = clamped[1];
//                 double slPct = Math.abs(entry - slPrice) / entry * 100;
//                 double tpPct = Math.abs(tpPrice - entry) / entry * 100;

//                 System.out.printf("  SL=%.6f (%.3f%%) | TP=%.6f (%.3f%%) | RR target=%.2f%n",
//                         slPrice, slPct, tpPrice, tpPct, RR_TARGET);

//                 String posId = getPositionId(pair);
//                 if (posId != null) {
//                     setTpSlWithRetry(posId, tpPrice, slPrice, pair);
//                 } else {
//                     System.out.println("  Position ID not found after retries — TP/SL will be handled by safety sweep");
//                 }

//                 active.add(pair);

//             } catch (Exception e) {
//                 System.err.println("Error on " + pair + ": " + e.getMessage());
//             }
//         }

//         System.out.println("\n=== Scalp scan complete ===");
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
//                 JSONArray raw5m = dropLastIfForming(
//                         getCandlestickData(pair, RES_5M, BASE_5M_FETCH_COUNT));

//                 if (raw5m == null || raw5m.length() < EMA_MID + ST_PERIOD + 5) {
//                     System.out.println("  [SWEEP] insufficient 5M data for " + pair
//                             + " — will retry next run");
//                     continue;
//                 }

//                 JSONArray raw15mSweep = aggregateCandles(raw5m, GROUP_15M_FROM_5M);
//                 SetupResult setup15mSweep = analyzeSetup(raw15mSweep);
//                 if (!setup15mSweep.valid) {
//                     System.out.println("  [SWEEP] insufficient 15M data for " + pair
//                             + " — will retry next run");
//                     continue;
//                 }

//                 double[] hi5m = extractHighs(raw5m);
//                 double[] lo5m = extractLows(raw5m);
//                 double[] cl5m = extractCloses(raw5m);
//                 double atr5m = calcATR(hi5m, lo5m, cl5m, ATR_PERIOD);
//                 if (atr5m <= 0) {
//                     System.out.println("  [SWEEP] invalid 5M ATR for " + pair
//                             + " — will retry next run");
//                     continue;
//                 }

//                 double posQty = pos.optDouble("active_pos", 0);
//                 boolean isLong = posQty >= 0;
//                 double stLevel = isLong ? setup15mSweep.stLower : setup15mSweep.stUpper;

//                 double tick = getTickSize(pair);
//                 double[] slTp = computeSlTp(isLong, avgPrice, hi5m, lo5m, atr5m, stLevel, tick);
//                 double[] clamped = sanityClampSlTp(isLong, avgPrice, slTp[0], slTp[1], tick);
//                 double sl = clamped[0], tp = clamped[1];

//                 String posId = pos.optString("id", null);
//                 if (posId != null) {
//                     System.out.printf("  [SWEEP] %s fallback SL=%.6f TP=%.6f (RR target=%.2f)%n", pair, sl, tp, RR_TARGET);
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

//     private static double calcRSI(double[] closes, int period) {
//         if (closes.length < period + 1) return 50.0;

//         double avgGain = 0, avgLoss = 0;
//         for (int i = 1; i <= period; i++) {
//             double change = closes[i] - closes[i - 1];
//             if (change > 0) avgGain += change;
//             else avgLoss += -change;
//         }
//         avgGain /= period;
//         avgLoss /= period;

//         for (int i = period + 1; i < closes.length; i++) {
//             double change = closes[i] - closes[i - 1];
//             double gain = Math.max(change, 0);
//             double loss = Math.max(-change, 0);
//             avgGain = (avgGain * (period - 1) + gain) / period;
//             avgLoss = (avgLoss * (period - 1) + loss) / period;
//         }

//         if (avgLoss == 0) return 100.0;
//         double rs = avgGain / avgLoss;
//         return 100.0 - (100.0 / (1.0 + rs));
//     }

//     private static BigDecimal roundToTickBD(double price, double tick) {
//         if (tick <= 0) return BigDecimal.valueOf(price);
//         BigDecimal bdPrice = BigDecimal.valueOf(price);
//         BigDecimal bdTick  = BigDecimal.valueOf(tick);
//         BigDecimal multiples = bdPrice.divide(bdTick, 0, RoundingMode.HALF_UP);
//         BigDecimal result = multiples.multiply(bdTick);
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
//     private static double[] extractVolumes(JSONArray a) {
//         double[] o = new double[a.length()];
//         for (int i = 0; i < a.length(); i++) o[i] = a.getJSONObject(i).optDouble("volume", 0);
//         return o;
//     }

//     private static JSONArray getCandlestickData(String pair, String resolution, int count) {
//         try {
//             long minsPerBar;
//             switch (resolution) {
//                 case "1":   minsPerBar = 1;   break;
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
//             double volSum = 0;
//             for (int j = i; j < i + groupSize; j++) {
//                 JSONObject c = source.getJSONObject(j);
//                 high = Math.max(high, c.getDouble("high"));
//                 low  = Math.min(low,  c.getDouble("low"));
//                 volSum += c.optDouble("volume", 0);
//             }
//             JSONObject merged = new JSONObject();
//             merged.put("open", open);
//             merged.put("close", close);
//             merged.put("high", high);
//             merged.put("low", low);
//             merged.put("volume", volSum);
//             result.put(merged);
//         }
//         return result;
//     }
// }





























// // ye kaun sa code ka part hai claude se uska information hai, ye code ke just upar ka last line hai
// // kaisa perform kar raha hai, bina extra-layers ke confusion ke.


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

//     // =========================================================================
//     // Position sizing — back to FIXED MARGIN (simpler than risk-based
//     // sizing, no pre-order SL estimate / currency-conversion chain needed).
//     // This is the actual margin used per trade — edit to match how much you
//     // want to commit per position.
//     // =========================================================================
//     private static final double MAX_MARGIN = 1500.0;
//     private static final int LEVERAGE = 15;

//     private static final int MAX_ENTRY_PRICE_CHECKS = 20;
//     private static final int ENTRY_CHECK_DELAY_MS    = 1000;

//     private static final int  TPSL_MAX_RETRIES    = 3;
//     private static final long TPSL_RETRY_DELAY_MS = 2000L;

//     private static final long TICK_CACHE_TTL_MS = 3_600_000L;

//     private static final int MAX_OPEN_POSITIONS = 120;

//     private static final int  POSITION_ID_MAX_RETRIES = 5;
//     private static final long POSITION_ID_RETRY_DELAY_MS = 1500L;

//     // =========================================================================
//     // Direction -> 1H+30M, Setup -> 15M, Entry -> 5M. UNCHANGED from your
//     // last working version — this is the part you asked to keep exactly
//     // as-is.
//     // =========================================================================
//     private static final int EMA_FAST = 9;
//     private static final int EMA_MID  = 21;
//     private static final int ATR_PERIOD = 14;
//     private static final int ST_PERIOD     = 10;
//     private static final double ST_MULTIPLIER = 3.0;

//     private static final String RES_5M = "5";
//     private static final String RES_1H = "60";

//     private static final int BASE_5M_FETCH_COUNT = 250;
//     private static final int GROUP_15M_FROM_5M = 3;
//     private static final int GROUP_30M_FROM_5M = 6;
//     private static final int BASE_1H_FETCH_COUNT = 55;

//     // ---- RSI (momentum filter) ----
//     private static final int RSI_PERIOD = 14;
//     private static final double RSI_LONG_MIN  = 40, RSI_LONG_MAX  = 70;
//     private static final double RSI_SHORT_MIN = 30, RSI_SHORT_MAX = 60;

//     // ---- Entry (5M) filter thresholds ----
//     private static final int    ENTRY_VOLUME_LOOKBACK   = 20;
//     private static final double ENTRY_VOLUME_MULTIPLIER = 1.05;

//     private static final double ENTRY_PULLBACK_MAX_ATR  = 0.6;
//     private static final double ENTRY_MIN_BODY_RATIO    = 0.30;

//     private static final int    ENTRY_VWAP_LOOKBACK      = 20;
//     private static final double ENTRY_MAX_VWAP_DIST_ATR  = 0.6;

//     // Mandatory (pullback + directional-candle + notDoji) vs confirmation
//     // score (2-of-3: volume/RSI/VWAP) — unchanged.
//     private static final int ENTRY_CONFIRMATION_MIN_SCORE = 3;

//     // ---- SL/TP sizing — FIXED, set once at entry, never adjusted after,
//     // and never monitored for early-exit either (that system is removed
//     // in this version). SL uses the structural level (swing extreme or
//     // 15M Supertrend band, whichever is closer) plus an ATR buffer. ----
//     private static final int    SWING_LOOKBACK_BARS = 20;
//     private static final double SL_ATR_BUFFER_MULT  = 1.5;
//     private static final double SL_MAX_PERCENT = 3.0;
//     private static final double RR_TARGET = 1.5;

//     private static final double LIMIT_ORDER_BUFFER_PCT = 0.0005;

//     private static final long SCALP_COOLDOWN_MS            = 5 * 60 * 1000L;
//     private static final long SCALP_ENTRY_SCAN_INTERVAL_MS = 20 * 1000L;

//     // EMA9 slope/angle check — used on 1H/30M (Direction) and 15M (Setup).
//     private static final int    EMA_SLOPE_LOOKBACK_BARS = 5;
//     private static final double EMA_SLOPE_MIN_ATR        = 0.15;

//     private static final Map<String, JSONObject> instrumentCache = new ConcurrentHashMap<>();
//     private static long lastCacheUpdate = 0;
//     private static final Map<String, Long> lastTradeTime = new ConcurrentHashMap<>();

//     private static final String[] COIN_SYMBOLS = 
//     { 
//     "MEME","ORDI","XPIN","1000BONK","1000RATS","LSK","AIXBT","NAORIS","XAI","SAGA","DEXE","TAO","GRIFFAIN",
//     "TURBO","RAVE","LISTA","RARE","G","POPCAT","TA","AIO","POL","BIO","FIDA","MAGMA","GRASS","XAG","SPX","ORCA",
//     "JUP","MOVE","DEEP","FRAX","BTC","JST","ME","LUMIA","ACU","ALCH","ETH","MUBARAK","SAPIEN","TST","ASR","ARC",
//     "AVAAI","FIGHT","TRUMP","ADA","ANIME","NEWT","BANANAS31","4","BR","HEI","GPS","BLUAI","AIN","KAITO","ERA",
//     "BNB","TUT","ENJ","ELSA","ESP","PLAY","SPACE","NEO","PLUME","NIL","QTUM","JELLYJELLY","MYX","CVC","PARTI",
//     "BICO","LIGHT","BIGTIME","ATH","SLP","ALGO","DOGE","KAVA","STO","FHE","KAIA","ZORA","WCT","RIVER","SUSHI",
//     "BANK","HUMA","GAS","PUNDIX","AIOT","NEAR","OPEN","SXT","OG","BCH","SENT","WET","SKYAI","BREV","AWE","SOL",
//     "B","TAG","MEGA","XRP","A","SOPH","TRIA","HYPE","KAT","TAIKO","SQD","NATGAS","PRL","CARV","WLFI","H","GENIUS",
//     "CHIP","M","CLO","AIGENSYN","PUMP","ZRX","SLX","COMP","ARX","BTR","AVA","LINEA","O","JCT","KAS","ARIA","DOT",
//     "FLOCK","LTC","AVNT","KMNO","STBL","BLESS","UB","ZK","XAN","INX","TRX","PYTH","GIGGLE","1INCH","LYN","ANKR",
//     "ETC","COAI","TURTLE","ON","LINK","XLM","XMR","LIT","SFP","ICNT","LAB","RECALL","APR","AT","SUPER","UAI","MMT",
//     "OPN","HANA","CLANKER","PIEVERSE","BAND","BEAT","PAXG","DASH","IRYS","ZEC","US","CYS","KERNEL","ATOM","INIT",
//     "MANTRA","COPPER","CL","BZ","DUSK","FLOW","OPG","PHAROS","HAEDAL","CAP","AKE","FF","DATAIP","GRVT","BAT",
//     "MARSCOIN","BERA","ALPINE","MANA","ACE","XAU","SOON","IMX","IOST","VELVET","THETA","EDEN","TRUTH","EVAA",
//     "KGEN","RLC","ZBT","TNSR","APE","TAC","TRB","MTL","UNI","GMX","BB","JOE","HIVE","XVG","AVAX","SEI","HOLO",
//     "SOLV","ZRO","CAKE","KSM","VTHO","SUN","AAVE","FIL","MOODENG","RSR","BEL","AXS","WAL","GRT","SAND","FORM",
//     "RVN","COTI","VELODROME","CHR","MANTA","HBAR","IOTX","C98","1000XEC","AR","BSV","USUAL","ARPA","PTB","SIREN",
//     "CTSI","LPT","COOKIE","VVV","OP","INJ","1000LUNC","LUNA2","FET","LDO","ICP","QNT","LA","LQTY","T",
//     "SSV","MINA","VIRTUAL","MASK","BLUR","STX","ACH","CROSS","EDU","ID","XVS","ENS","PEOPLE","ROSE",
//     "1000PEPE","DYM","GLM","1000FLOKI","GMT","POWR","PORTAL","JASMY","STG","BEAMX","BNT","ETHW","ARB",
//     "MAGIC","SUI","WAXP","CFX","MAV","TIA","ZAMA","AUCTION","ARK","GWEI","ILV","ROBO","PIXEL","BSB",
//     "AXL","BOME","BASED","UMA","FLUX","BILL","ARKM","ZEST","YGG","NEIRO","DIA","WIF","RIF","GOAT",
//     "USTC","METIS","ETHFI","ENA","1MBABYDOGE","SAFE","CATI","HMSTR","SANTOS","1000000MOG","DRIFT",
//     "PNUT","ACT","RPL","PROM","S","BAN","THE","MELANIA","AKT","BMT","SHELL","MORPHO","CHILLGUY",
//     "AERO","MOCA","PENGU","PHA","RED","EPIC","TREE","1000CAT","MAVIA","FARTCOIN","IN","ORDER",
//     "VET","ZEN","STABLE","CHZ","NIGHT","NOM","ZKP","SKR","GRAM","BIRB","CTR","KNC","ZIL","YFI",
//     "EGLD","RUNE","ASTR","ONE","1000SHIB","API3","SPELL","WOO","APT","PENDLE","AGLD","CYBER",
//     "CKB","ONG","MOVR","POLYX","TWT","STEEM","ALT","ZETA","REZ","RENDER","RONIN","STRK","W",
//     "SCR","CETUS","IO","MEW","SWARMS","SONIC","PIPPIN","PROMPT","MERL","F","ESPORTS","PROVE",
//     "XNY","USELESS","HEMI","Q","SKY","ZKC","FLUID","MITO","CFG","EDGE","RE","YB","MET","DOS",
//     "FOGO","BTW","ALLO","BROCCOLI714","HYPER","XPL","RESOLV","ASTER","KITE","SIGN","HOME","MON",
//     "CC","SAHARA","MIRA","EUL","TOWNS","SYRUP","C","DOLO","ALICE","BABY","SOMI","NOT","BARD",
//     "SPK","POWER","2Z","BANANA","ENSO","SYN","NXPC","GUN","XTZ","ONT","SKL","HOT","JTO","DOGS",
//     "EIGEN","GTC","GALA","NMR","CGPT","ZEREBRO","VANA","OGN","CELO","USDC","COW","0G","IOTA",
//     "SNX","DYDX","WLD","1000SATS","ONDO","AEVO","BRETT","LAYER","CRV","TLM","KOMA" 
//     };
    
//     private static final Set<String> INTEGER_QTY_PAIRS = Stream.of(COIN_SYMBOLS)
//             .flatMap(s -> Stream.of("B-" + s + "_USDT", s + "_USDT"))
//             .collect(Collectors.toCollection(HashSet::new));

//     private static final String[] COINS_TO_TRADE = Stream.of(COIN_SYMBOLS)
//             .map(s -> "B-" + s + "_USDT")
//             .toArray(String[]::new);

//     // =========================================================================
//     // Direction result — used for BOTH 1H and 30M. UNCHANGED.
//     // =========================================================================
//     private static class DirectionResult {
//         boolean valid;
//         boolean bullish;
//         boolean bearish;
//     }

//     private static double[] calcEMASeries(double[] d, int period) {
//         double[] out = new double[d.length];
//         if (d.length < period) {
//             double last = d.length > 0 ? d[d.length - 1] : 0;
//             Arrays.fill(out, last);
//             return out;
//         }
//         double k = 2.0 / (period + 1);
//         double ema = 0;
//         for (int i = 0; i < period; i++) ema += d[i];
//         ema /= period;
//         for (int i = 0; i < period; i++) out[i] = ema; // backfill undefined region
//         out[period - 1] = ema;
//         for (int i = period; i < d.length; i++) {
//             ema = d[i] * k + ema * (1 - k);
//             out[i] = ema;
//         }
//         return out;
//     }

//     private static DirectionResult analyzeDirection(JSONArray candles) {
//         DirectionResult r = new DirectionResult();
//         if (candles == null || candles.length() < EMA_MID + Math.max(ATR_PERIOD, ST_PERIOD) + 5) {
//             r.valid = false;
//             return r;
//         }
//         double[] cl = extractCloses(candles);
//         double[] hi = extractHighs(candles);
//         double[] lo = extractLows(candles);

//         double ema9  = calcEMA(cl, EMA_FAST);
//         double ema21 = calcEMA(cl, EMA_MID);
//         double atr   = calcATR(hi, lo, cl, ATR_PERIOD);
//         double price = cl[cl.length - 1];

//         boolean[] stSeries = calcSupertrend(hi, lo, cl, ST_PERIOD, ST_MULTIPLIER);
//         boolean stGreen = stSeries[stSeries.length - 1];

//         double[] ema9Series = calcEMASeries(cl, EMA_FAST);
//         int n = ema9Series.length;
//         int lookback = Math.min(EMA_SLOPE_LOOKBACK_BARS, n - 1);
//         double emaSlope = ema9Series[n - 1] - ema9Series[n - 1 - lookback];
//         boolean slopeUp   = atr > 0 && emaSlope >= EMA_SLOPE_MIN_ATR * atr;
//         boolean slopeDown = atr > 0 && emaSlope <= -EMA_SLOPE_MIN_ATR * atr;

//         boolean priceAboveBoth = price > ema9 && price > ema21;
//         boolean priceBelowBoth = price < ema9 && price < ema21;

//         r.valid = true;
//         r.bullish = (ema9 > ema21) && slopeUp && stGreen && priceAboveBoth;
//         r.bearish = (ema9 < ema21) && slopeDown && !stGreen && priceBelowBoth;
//         return r;
//     }

//     // =========================================================================
//     // Setup result (15M) — UNCHANGED, still carries the Supertrend band
//     // levels for SL sizing.
//     // =========================================================================
//     private static class SetupResult {
//         boolean valid;
//         boolean bullish;
//         boolean bearish;
//         double  atr;
//         double  stLower;
//         double  stUpper;
//     }

//     private static SetupResult analyzeSetup(JSONArray candles) {
//         SetupResult r = new SetupResult();
//         if (candles == null || candles.length() < EMA_MID + ST_PERIOD + 5) {
//             r.valid = false;
//             return r;
//         }
//         double[] cl = extractCloses(candles);
//         double[] hi = extractHighs(candles);
//         double[] lo = extractLows(candles);

//         double ema9  = calcEMA(cl, EMA_FAST);
//         double ema21 = calcEMA(cl, EMA_MID);
//         double price = cl[cl.length - 1];
//         r.atr = calcATR(hi, lo, cl, ATR_PERIOD);

//         boolean[] stSeries = calcSupertrend(hi, lo, cl, ST_PERIOD, ST_MULTIPLIER);
//         boolean stGreen = stSeries[stSeries.length - 1];

//         double[] bands = calcSupertrendBands(hi, lo, cl, ST_PERIOD, ST_MULTIPLIER);
//         r.stLower = bands[0];
//         r.stUpper = bands[1];

//         double[] ema9Series = calcEMASeries(cl, EMA_FAST);
//         int n = ema9Series.length;
//         int lookback = Math.min(EMA_SLOPE_LOOKBACK_BARS, n - 1);
//         double emaSlope = ema9Series[n - 1] - ema9Series[n - 1 - lookback];
//         boolean slopeUp   = r.atr > 0 && emaSlope >= EMA_SLOPE_MIN_ATR * r.atr;
//         boolean slopeDown = r.atr > 0 && emaSlope <= -EMA_SLOPE_MIN_ATR * r.atr;

//         boolean priceAboveBoth = price > ema9 && price > ema21;
//         boolean priceBelowBoth = price < ema9 && price < ema21;

//         r.valid = true;
//         r.bullish = (ema9 > ema21) && stGreen && slopeUp && priceAboveBoth;
//         r.bearish = (ema9 < ema21) && !stGreen && slopeDown && priceBelowBoth;
//         return r;
//     }

//     // =========================================================================
//     // Entry result (5M) — UNCHANGED: mandatory (pullback+candle+notDoji) +
//     // confirmation-score (2-of-3: volume/RSI/VWAP).
//     // =========================================================================
//     private static class EntryResult {
//         boolean valid;
//         boolean triggered;
//         double entryClose, entryOpen, entryHigh, entryLow;
//         double atr5m;
//         String reason;
//     }

//     private static EntryResult analyzeEntry(JSONArray raw5m, boolean trendUp) {
//         EntryResult t = new EntryResult();
//         int minBars = Math.max(EMA_MID, Math.max(ENTRY_VOLUME_LOOKBACK, ENTRY_VWAP_LOOKBACK)) + RSI_PERIOD + 5;
//         if (raw5m == null || raw5m.length() < minBars) {
//             t.valid = false;
//             return t;
//         }

//         double[] cl  = extractCloses(raw5m);
//         double[] op  = extractOpens(raw5m);
//         double[] hi  = extractHighs(raw5m);
//         double[] lo  = extractLows(raw5m);
//         double[] vol = extractVolumes(raw5m);
//         int n = cl.length;

//         double ema9  = calcEMA(cl, EMA_FAST);
//         double ema21 = calcEMA(cl, EMA_MID);
//         double atr5m = calcATR(hi, lo, cl, ATR_PERIOD);
//         t.atr5m = atr5m;

//         double entryClose = cl[n - 1], entryOpen = op[n - 1];
//         double entryHigh  = hi[n - 1], entryLow  = lo[n - 1];
//         t.entryClose = entryClose; t.entryOpen = entryOpen;
//         t.entryHigh = entryHigh;   t.entryLow = entryLow;

//         double distToEma9  = Math.abs(entryClose - ema9);
//         double distToEma21 = Math.abs(entryClose - ema21);
//         double nearestEmaDist = Math.min(distToEma9, distToEma21);
//         boolean pulledBack = atr5m > 0 && nearestEmaDist <= ENTRY_PULLBACK_MAX_ATR * atr5m;

//         boolean directionalCandle = trendUp ? (entryClose > entryOpen) : (entryClose < entryOpen);
//         double body  = Math.abs(entryClose - entryOpen);
//         double range = entryHigh - entryLow;
//         boolean notDoji = range > 0 && (body / range) >= ENTRY_MIN_BODY_RATIO;

//         boolean mandatoryOk = pulledBack && directionalCandle && notDoji;

//         int volStart = Math.max(0, n - 1 - ENTRY_VOLUME_LOOKBACK);
//         double avgVol = 0; int cnt = 0;
//         for (int i = volStart; i < n - 1; i++) { avgVol += vol[i]; cnt++; }
//         avgVol = cnt > 0 ? avgVol / cnt : 0;
//         boolean volumeOk = avgVol > 0 && vol[n - 1] >= avgVol * ENTRY_VOLUME_MULTIPLIER;

//         double rsi = calcRSI(cl, RSI_PERIOD);
//         boolean rsiOk = trendUp
//                 ? (rsi >= RSI_LONG_MIN && rsi <= RSI_LONG_MAX)
//                 : (rsi >= RSI_SHORT_MIN && rsi <= RSI_SHORT_MAX);

//         int vwapStart = Math.max(0, n - ENTRY_VWAP_LOOKBACK);
//         double cumPV = 0, cumV = 0;
//         for (int i = vwapStart; i < n; i++) {
//             double typical = (hi[i] + lo[i] + cl[i]) / 3.0;
//             cumPV += typical * vol[i];
//             cumV  += vol[i];
//         }
//         double vwap = cumV > 0 ? cumPV / cumV : entryClose;
//         double distFromVwap = Math.abs(entryClose - vwap);
//         boolean vwapOk = atr5m > 0 && distFromVwap <= ENTRY_MAX_VWAP_DIST_ATR * atr5m;

//         int confirmationScore = (volumeOk ? 1 : 0) + (rsiOk ? 1 : 0) + (vwapOk ? 1 : 0);

//         t.triggered = mandatoryOk && confirmationScore >= ENTRY_CONFIRMATION_MIN_SCORE;
//         t.valid = true;
//         t.reason = String.format(
//                 "mandatory[pulledBack=%s directional=%s notDoji=%s] confirmation[volume=%s(%.2fx) rsi=%.1f(ok=%s) vwap=%s] score=%d/3",
//                 pulledBack, directionalCandle, notDoji,
//                 volumeOk, avgVol > 0 ? vol[n - 1] / avgVol : 0,
//                 rsi, rsiOk, vwapOk, confirmationScore);
//         return t;
//     }

//     private static JSONArray dropLastIfForming(JSONArray arr) {
//         if (arr == null || arr.length() < 2) return arr;
//         JSONArray out = new JSONArray();
//         for (int i = 0; i < arr.length() - 1; i++) out.put(arr.getJSONObject(i));
//         return out;
//     }

//     // =========================================================================
//     // FIXED SL/TP — computed once at entry, never adjusted afterward, and
//     // (in this version) never monitored for early-exit either. Whatever
//     // gets hit first — the take_profit_market or stop_market order on the
//     // exchange — closes the trade. That's the entire exit mechanism now.
//     // =========================================================================
//     private static double[] computeSlTp(boolean isLong, double entryPrice,
//                                          double[] hi5m, double[] lo5m, double atr5m,
//                                          double stLevel, double tickSize) {
//         double sl, tp;
//         if (isLong) {
//             double swingLow = recentLow(lo5m, SWING_LOOKBACK_BARS);
//             double structuralLow = Math.min(swingLow, stLevel);
//             double raw = structuralLow - SL_ATR_BUFFER_MULT * atr5m;
//             double hardFloor = entryPrice * (1 - SL_MAX_PERCENT / 100.0);
//             sl = Math.max(raw, hardFloor);
//             double risk = entryPrice - sl;
//             tp = entryPrice + RR_TARGET * risk;
//         } else {
//             double swingHigh = recentHigh(hi5m, SWING_LOOKBACK_BARS);
//             double structuralHigh = Math.max(swingHigh, stLevel);
//             double raw = structuralHigh + SL_ATR_BUFFER_MULT * atr5m;
//             double hardCeil = entryPrice * (1 + SL_MAX_PERCENT / 100.0);
//             sl = Math.min(raw, hardCeil);
//             double risk = sl - entryPrice;
//             tp = entryPrice - RR_TARGET * risk;
//         }
//         sl = roundToTick(sl, tickSize);
//         tp = roundToTick(tp, tickSize);
//         return new double[]{sl, tp};
//     }

//     private static double recentLow(double[] lo, int lookback) {
//         int n = lo.length;
//         int start = Math.max(0, n - lookback);
//         double min = Double.POSITIVE_INFINITY;
//         for (int i = start; i < n; i++) min = Math.min(min, lo[i]);
//         return min;
//     }

//     private static double recentHigh(double[] hi, int lookback) {
//         int n = hi.length;
//         int start = Math.max(0, n - lookback);
//         double max = Double.NEGATIVE_INFINITY;
//         for (int i = start; i < n; i++) max = Math.max(max, hi[i]);
//         return max;
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
//     // Fixed-margin position sizing — RESTORED. Every trade uses the same
//     // margin (MAX_MARGIN), regardless of SL distance. Simple, no pre-order
//     // SL estimate needed, no currency-conversion chain.
//     // =========================================================================
//     private static double calcQuantity(double price, String pair) {
//         double usdtInrRate = 98.0;
//         double qty = MAX_MARGIN / (price * usdtInrRate);
//         double finalQty = INTEGER_QTY_PAIRS.contains(pair)
//                 ? Math.floor(qty)
//                 : Math.floor(qty * 100) / 100.0;
//         return Math.max(finalQty, 0);
//     }

//     // =========================================================================
//     // Orchestrator — SIMPLE, no exit-monitoring loop. Just: scan for
//     // entries, place trades with fixed SL/TP, safety sweep for anything
//     // that ended up without protection.
//     // =========================================================================
//     public static void main(String[] args) {
//         System.out.println("=== Scalp bot starting (fixed margin, fixed SL/TP, no exit-monitoring) ===");
//         initInstrumentCache();

//         while (true) {
//             try {
//                 runEntryScan(); // also runs the safety sweep at the end
//             } catch (Throwable t) {
//                 System.err.println("[MAIN-LOOP] Uncaught error, continuing: " + t.getMessage());
//                 t.printStackTrace();
//             }

//             try {
//                 TimeUnit.MILLISECONDS.sleep(SCALP_ENTRY_SCAN_INTERVAL_MS);
//             } catch (InterruptedException ignored) {
//                 Thread.currentThread().interrupt();
//                 break;
//             }
//         }
//     }

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
//                 if (active.contains(pair)) continue;

//                 long lastTrade = lastTradeTime.getOrDefault(pair, 0L);
//                 if (System.currentTimeMillis() - lastTrade < SCALP_COOLDOWN_MS) continue;

//                 JSONArray raw5m = dropLastIfForming(
//                         getCandlestickData(pair, RES_5M, BASE_5M_FETCH_COUNT));
//                 if (raw5m == null || raw5m.length() < EMA_MID + ST_PERIOD + 5) continue;

//                 JSONArray raw30m = aggregateCandles(raw5m, GROUP_30M_FROM_5M);
//                 DirectionResult dir30m = analyzeDirection(raw30m);
//                 if (!dir30m.valid || (!dir30m.bullish && !dir30m.bearish)) continue;

//                 JSONArray raw15m = aggregateCandles(raw5m, GROUP_15M_FROM_5M);
//                 SetupResult setup15m = analyzeSetup(raw15m);
//                 if (!setup15m.valid) continue;

//                 boolean setupMatches30m = (dir30m.bullish && setup15m.bullish)
//                         || (dir30m.bearish && setup15m.bearish);
//                 if (!setupMatches30m) continue;

//                 boolean trendUp = dir30m.bullish;

//                 JSONArray raw1h = dropLastIfForming(
//                         getCandlestickData(pair, RES_1H, BASE_1H_FETCH_COUNT));
//                 DirectionResult dir1h = analyzeDirection(raw1h);
//                 if (!dir1h.valid) continue;

//                 boolean dir1hMatches = (trendUp && dir1h.bullish) || (!trendUp && dir1h.bearish);
//                 if (!dir1hMatches) continue;

//                 EntryResult entry5m = analyzeEntry(raw5m, trendUp);
//                 if (!entry5m.valid) continue;
//                 if (!entry5m.triggered) {
//                     continue;
//                 }

//                 System.out.println("\n==== " + pair + " ====");
//                 System.out.printf("  [1H] %s | [30M] %s | [15M] %s | [5M-Entry] %s%n",
//                         dir1h.bullish ? "BULLISH" : "BEARISH",
//                         trendUp ? "BULLISH" : "BEARISH",
//                         setup15m.bullish ? "BULLISH" : "BEARISH",
//                         entry5m.reason);

//                 String side = trendUp ? "buy" : "sell";
//                 System.out.println("  ╔══════════════════════════════════════════════════╗");
//                 System.out.println("  ║  SCALP TRIGGER → " + side.toUpperCase() + " " + pair);
//                 System.out.println("  ╚══════════════════════════════════════════════════╝");

//                 double currentPrice = getLastPrice(pair);
//                 if (currentPrice <= 0) continue;
//                 double qty = calcQuantity(currentPrice, pair);
//                 if (qty <= 0) continue;
//                 double tickSize = getTickSize(pair);

//                 System.out.printf("  Placing %s | price=%.6f | qty=%.4f | lev=%dx%n",
//                         side.toUpperCase(), currentPrice, qty, LEVERAGE);

//                 JSONObject resp = placeFuturesOrder(side, pair, qty, LEVERAGE,
//                         "email_notification", "isolated", "INR", currentPrice);
//                 if (resp == null || !resp.has("id")) {
//                     System.out.println("  Order failed: " + resp);
//                     continue;
//                 }

//                 System.out.println("  Order placed! id=" + resp.getString("id"));
//                 lastTradeTime.put(pair, System.currentTimeMillis());

//                 double entry = getEntryPrice(pair, resp.getString("id"));
//                 if (entry <= 0) {
//                     System.out.println("  Could not confirm entry within window — TP/SL will be handled by safety sweep");
//                     active.add(pair);
//                     continue;
//                 }

//                 System.out.printf("  Entry confirmed: %.6f%n", entry);

//                 double[] hi5m = extractHighs(raw5m);
//                 double[] lo5m = extractLows(raw5m);
//                 double stLevel = trendUp ? setup15m.stLower : setup15m.stUpper;
//                 double[] slTp = computeSlTp(trendUp, entry, hi5m, lo5m, entry5m.atr5m, stLevel, tickSize);
//                 double[] clamped = sanityClampSlTp(trendUp, entry, slTp[0], slTp[1], tickSize);
//                 double slPrice = clamped[0], tpPrice = clamped[1];
//                 double slPct = Math.abs(entry - slPrice) / entry * 100;
//                 double tpPct = Math.abs(tpPrice - entry) / entry * 100;

//                 System.out.printf("  SL=%.6f (%.3f%%) | TP=%.6f (%.3f%%) | RR target=%.2f%n",
//                         slPrice, slPct, tpPrice, tpPct, RR_TARGET);

//                 String posId = getPositionId(pair);
//                 if (posId != null) {
//                     setTpSlWithRetry(posId, tpPrice, slPrice, pair);
//                 } else {
//                     System.out.println("  Position ID not found after retries — TP/SL will be handled by safety sweep");
//                 }

//                 active.add(pair);

//             } catch (Exception e) {
//                 System.err.println("Error on " + pair + ": " + e.getMessage());
//             }
//         }

//         System.out.println("\n=== Scalp scan complete ===");
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
//                 JSONArray raw5m = dropLastIfForming(
//                         getCandlestickData(pair, RES_5M, BASE_5M_FETCH_COUNT));

//                 if (raw5m == null || raw5m.length() < EMA_MID + ST_PERIOD + 5) {
//                     System.out.println("  [SWEEP] insufficient 5M data for " + pair
//                             + " — will retry next run");
//                     continue;
//                 }

//                 JSONArray raw15mSweep = aggregateCandles(raw5m, GROUP_15M_FROM_5M);
//                 SetupResult setup15mSweep = analyzeSetup(raw15mSweep);
//                 if (!setup15mSweep.valid) {
//                     System.out.println("  [SWEEP] insufficient 15M data for " + pair
//                             + " — will retry next run");
//                     continue;
//                 }

//                 double[] hi5m = extractHighs(raw5m);
//                 double[] lo5m = extractLows(raw5m);
//                 double[] cl5m = extractCloses(raw5m);
//                 double atr5m = calcATR(hi5m, lo5m, cl5m, ATR_PERIOD);
//                 if (atr5m <= 0) {
//                     System.out.println("  [SWEEP] invalid 5M ATR for " + pair
//                             + " — will retry next run");
//                     continue;
//                 }

//                 double posQty = pos.optDouble("active_pos", 0);
//                 boolean isLong = posQty >= 0;
//                 double stLevel = isLong ? setup15mSweep.stLower : setup15mSweep.stUpper;

//                 double tick = getTickSize(pair);
//                 double[] slTp = computeSlTp(isLong, avgPrice, hi5m, lo5m, atr5m, stLevel, tick);
//                 double[] clamped = sanityClampSlTp(isLong, avgPrice, slTp[0], slTp[1], tick);
//                 double sl = clamped[0], tp = clamped[1];

//                 String posId = pos.optString("id", null);
//                 if (posId != null) {
//                     System.out.printf("  [SWEEP] %s fallback SL=%.6f TP=%.6f (RR target=%.2f)%n", pair, sl, tp, RR_TARGET);
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

//     private static double calcRSI(double[] closes, int period) {
//         if (closes.length < period + 1) return 50.0;

//         double avgGain = 0, avgLoss = 0;
//         for (int i = 1; i <= period; i++) {
//             double change = closes[i] - closes[i - 1];
//             if (change > 0) avgGain += change;
//             else avgLoss += -change;
//         }
//         avgGain /= period;
//         avgLoss /= period;

//         for (int i = period + 1; i < closes.length; i++) {
//             double change = closes[i] - closes[i - 1];
//             double gain = Math.max(change, 0);
//             double loss = Math.max(-change, 0);
//             avgGain = (avgGain * (period - 1) + gain) / period;
//             avgLoss = (avgLoss * (period - 1) + loss) / period;
//         }

//         if (avgLoss == 0) return 100.0;
//         double rs = avgGain / avgLoss;
//         return 100.0 - (100.0 / (1.0 + rs));
//     }

//     private static BigDecimal roundToTickBD(double price, double tick) {
//         if (tick <= 0) return BigDecimal.valueOf(price);
//         BigDecimal bdPrice = BigDecimal.valueOf(price);
//         BigDecimal bdTick  = BigDecimal.valueOf(tick);
//         BigDecimal multiples = bdPrice.divide(bdTick, 0, RoundingMode.HALF_UP);
//         BigDecimal result = multiples.multiply(bdTick);
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
//     private static double[] extractVolumes(JSONArray a) {
//         double[] o = new double[a.length()];
//         for (int i = 0; i < a.length(); i++) o[i] = a.getJSONObject(i).optDouble("volume", 0);
//         return o;
//     }

//     private static JSONArray getCandlestickData(String pair, String resolution, int count) {
//         try {
//             long minsPerBar;
//             switch (resolution) {
//                 case "1":   minsPerBar = 1;   break;
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
//             double volSum = 0;
//             for (int j = i; j < i + groupSize; j++) {
//                 JSONObject c = source.getJSONObject(j);
//                 high = Math.max(high, c.getDouble("high"));
//                 low  = Math.min(low,  c.getDouble("low"));
//                 volSum += c.optDouble("volume", 0);
//             }
//             JSONObject merged = new JSONObject();
//             merged.put("open", open);
//             merged.put("close", close);
//             merged.put("high", high);
//             merged.put("low", low);
//             merged.put("volume", volSum);
//             result.put(merged);
//         }
//         return result;
//     }
// }
