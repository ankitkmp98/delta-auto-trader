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
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class CoinDCXFuturesTrader8C_BUY_SELL_NEW_LOGIC_THREE {

    // =========================================================================
    // API Configuration (unchanged)
    // =========================================================================
    private static final String API_KEY    = System.getenv("DELTA_API_KEY");
    private static final String API_SECRET = System.getenv("DELTA_API_SECRET");
    private static final String BASE_URL       = "https://api.coindcx.com";
    private static final String PUBLIC_API_URL = "https://public.coindcx.com";

    private static final int LEVERAGE = 6;

    private static final int MAX_ENTRY_PRICE_CHECKS = 20;
    private static final int ENTRY_CHECK_DELAY_MS    = 1000;

    private static final int  TPSL_MAX_RETRIES    = 3;
    private static final long TPSL_RETRY_DELAY_MS = 2000L;

    private static final long TICK_CACHE_TTL_MS = 3_600_000L;

    private static final int MAX_OPEN_POSITIONS = 120;

    private static final int  POSITION_ID_MAX_RETRIES = 5;
    private static final long POSITION_ID_RETRY_DELAY_MS = 1500L;

    // =========================================================================
    // Indicator periods (unchanged)
    // =========================================================================
    private static final int EMA_FAST = 9;
    private static final int EMA_MID  = 21;
    private static final int ATR_PERIOD = 14;
    private static final int ST_PERIOD     = 10;
    private static final double ST_MULTIPLIER = 3.0;

    private static final String RES_5M = "5";
    private static final String RES_1H = "60";

    private static final int BASE_5M_FETCH_COUNT = 250;
    private static final int GROUP_15M_FROM_5M = 3;
    private static final int GROUP_30M_FROM_5M = 6;
    private static final int BASE_1H_FETCH_COUNT = 55;

    private static final int RSI_PERIOD = 14;
    private static final double RSI_LONG_MIN  = 45, RSI_LONG_MAX  = 68;
    private static final double RSI_SHORT_MIN = 32, RSI_SHORT_MAX = 55;

    private static final int    ENTRY_VOLUME_LOOKBACK   = 20;
    private static final double ENTRY_VOLUME_MULTIPLIER = 1.20;
    private static final int    ENTRY_VWAP_LOOKBACK      = 20;

    private static final int EMA_SLOPE_LOOKBACK_BARS = 5;
    private static final double HTF_EMA_SLOPE_MIN_ATR   = 0.10;
    private static final double ENTRY_EMA_SLOPE_MIN_ATR = 0.15;

    private static final int SWING_LOOKBACK_BARS      = 20;
    private static final int STRUCTURE_SWING_LOOKBACK = 30; // for HH/HL - LL/LH detection

    private static final double ENTRY_MIN_BODY_RATIO = 0.40;

    // =========================================================================
    // NEW (PART 1/4) — quality-based multi-timeframe direction thresholds
    // =========================================================================
    private static final int MACRO_1H_MIN_TRUE      = 4; // "most of 6 conditions true" on 1H
    private static final int CONFIRM_30M_MIN_SCORE  = 5; // out of 6 -> strong confirmation
    private static final int CONFIRM_30M_BORDERLINE = 4; // allowed only if 15M is exceptionally clean

    // =========================================================================
    // NEW (PART 3/14) — congestion / overextension, ATR-normalized (not fixed %)
    // =========================================================================
    private static final double EMA_CONGESTION_MIN_ATR = 0.30; // reject 15M setup if EMA9/21 closer than this
    private static final double PULLBACK_MIN_ATR       = 0.20;
    private static final double PULLBACK_MAX_ATR       = 0.80;
    private static final double OVEREXTENDED_ATR       = 1.50; // beyond this: mandatoryOk fails
    private static final double CAUTION_ATR            = 2.00; // beyond this: cancel any pending signal

    // =========================================================================
    // NEW (PART 2) — 5M confirmation score out of 5 measurable factors.
    // The 6th spec factor ("breaks confirmation candle high/low") is enforced
    // separately as the actual entry trigger (see PendingSignal below), not
    // scored here.
    // =========================================================================
    private static final int ENTRY_CONFIRMATION_MIN_SCORE = 3; // out of 5

    // =========================================================================
    // NEW (PART 2, Step 4) — pending breakout-entry signal validity window
    // =========================================================================
    private static final long SIGNAL_MAX_VALID_MS = 3L * 5 * 60 * 1000L; // ~3 x 5M candles

    // =========================================================================
    // NEW (PART 5/6) — structural, reject-not-tighten stop loss
    // =========================================================================
    private static final double SL_ATR_BUFFER_MULT_MIN  = 0.30;
    private static final double SL_ATR_BUFFER_MULT_MAX  = 0.60;
    private static final double SL_MIN_ATR_DISTANCE       = 1.0; // tighter than this -> likely noise-stopped, reject
    private static final double SL_MAX_ATR_DISTANCE       = 3.5; // wider than this -> reject trade, do not tighten
    private static final double SL_HARD_PERCENT_CAP       = 6.0; // absolute safety-net fallback ONLY (sweep / post-fill edge case)

    // =========================================================================
    // NEW (PART 7) — dynamic risk/reward
    // =========================================================================
    private static final double RR_TARGET_BASE   = 1.5;
    private static final double RR_TARGET_STRONG = 1.9; // used only when 30M scores a clean 6/6

    // =========================================================================
    // NEW (PART 15) — risk-based position sizing (replaces fixed-margin sizing)
    // =========================================================================
    private static final double TOTAL_CAPITAL_BASE     = 50000.0; // INR — placeholder, set to your real capital
    private static final double RISK_PERCENT_PER_TRADE = 1.0;     // % of capital risked per trade
    private static final double MAX_MARGIN             = 1200.0;  // hard safety ceiling, never exceeded regardless of risk sizing

    private static final double LIMIT_ORDER_BUFFER_PCT = 0.0005;

    private static final long SCALP_COOLDOWN_MS            = 5 * 60 * 1000L;
    private static final long SCALP_ENTRY_SCAN_INTERVAL_MS = 20 * 1000L;

    // =========================================================================
    // NEW (PART 9/10/11) — state-based trailing (STATE 0..4), ratchet-only,
    // plus a health-checked TP extension.
    // =========================================================================
    private static final boolean TRAILING_ENABLED = true;
    private static final double TRAIL_ATR_EARLY  = 2.25; // state 1 (>=0.5R)
    private static final double TRAIL_ATR_STAGE2 = 1.75; // state 2+ (>=1R)
    private static final double TRAIL_ATR_STAGE3 = 1.35; // state 3/4 (>=1.5R / >=2R)
    private static final double BREAKEVEN_BUFFER_PCT = 0.10; // small locked profit at +1R, not pure breakeven

    private static final double TP_EXTEND_TRIGGER_FRACTION = 0.85;
    private static final int    TP_MAX_EXTENSIONS = 4;

    private static class TrailInfo {
        boolean isLong;
        double entryPrice;
        double initialRisk;
        double initialReward;
        double currentSl;
        double currentTp;
        double peak;
        int    state;          // 0..4, PART 9 states
        int    extensionsUsed;
    }
    private static final Map<String, TrailInfo> trailState = new ConcurrentHashMap<>();

    // =========================================================================
    // NEW (PART 2, Step 4) — pending breakout signal per pair. A 5M setup that
    // passes mandatory+confirmation conditions does NOT enter immediately; it
    // arms a pending signal and waits for price to break the confirmation
    // candle's high (long) / low (short), with a max validity window and
    // several cancellation conditions.
    // =========================================================================
    private static class PendingSignal {
        boolean isLong;
        double confirmHigh, confirmLow;
        double atrAtSignal;
        long   createdAtMs;
    }
    private static final Map<String, PendingSignal> pendingSignals = new ConcurrentHashMap<>();

    private static final Map<String, JSONObject> instrumentCache = new ConcurrentHashMap<>();
    private static long lastCacheUpdate = 0;
    private static final Map<String, Long> lastTradeTime = new ConcurrentHashMap<>();

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

    private static final Set<String> INTEGER_QTY_PAIRS = Stream.of(COIN_SYMBOLS)
            .flatMap(s -> Stream.of("B-" + s + "_USDT", s + "_USDT"))
            .collect(Collectors.toCollection(HashSet::new));

    private static final String[] COINS_TO_TRADE = Stream.of(COIN_SYMBOLS)
            .map(s -> "B-" + s + "_USDT")
            .toArray(String[]::new);

    // =========================================================================
    // NEW — market structure (HH/HL vs LL/LH), used by the 1H/30M/15M scoring.
    // Returns +1 bullish structure, -1 bearish structure, 0 none/mixed.
    // =========================================================================
    private static int detectSwingStructure(double[] hi, double[] lo, int lookback) {
        int n = hi.length;
        if (n < lookback + 3) return 0;
        int start = Math.max(1, n - lookback);
        List<Integer> swingHighIdx = new ArrayList<>();
        List<Integer> swingLowIdx  = new ArrayList<>();
        for (int i = start; i < n - 1; i++) {
            if (hi[i] > hi[i - 1] && hi[i] > hi[i + 1]) swingHighIdx.add(i);
            if (lo[i] < lo[i - 1] && lo[i] < lo[i + 1]) swingLowIdx.add(i);
        }
        boolean hh = false, hl = false, ll = false, lh = false;
        if (swingHighIdx.size() >= 2) {
            double h1 = hi[swingHighIdx.get(swingHighIdx.size() - 2)];
            double h2 = hi[swingHighIdx.get(swingHighIdx.size() - 1)];
            hh = h2 > h1;
            lh = h2 < h1;
        }
        if (swingLowIdx.size() >= 2) {
            double l1 = lo[swingLowIdx.get(swingLowIdx.size() - 2)];
            double l2 = lo[swingLowIdx.get(swingLowIdx.size() - 1)];
            hl = l2 > l1;
            ll = l2 < l1;
        }
        if (hh && hl) return 1;
        if (ll && lh) return -1;
        return 0;
    }

    // =========================================================================
    // PART 1 — 1H macro bias. "Most of 6 conditions true" (>= MACRO_1H_MIN_TRUE),
    // not a hard all-or-nothing EMA-cross check.
    // =========================================================================
    private static class DirectionResult {
        boolean valid;
        boolean bullish;
        boolean bearish;
        int score;
    }

    private static DirectionResult analyzeMacro1H(JSONArray candles) {
        DirectionResult r = new DirectionResult();
        if (candles == null || candles.length() < EMA_MID + Math.max(ATR_PERIOD, ST_PERIOD) + STRUCTURE_SWING_LOOKBACK) {
            r.valid = false;
            return r;
        }
        double[] cl = extractCloses(candles);
        double[] hi = extractHighs(candles);
        double[] lo = extractLows(candles);

        double ema9  = calcEMA(cl, EMA_FAST);
        double ema21 = calcEMA(cl, EMA_MID);
        double atr   = calcATR(hi, lo, cl, ATR_PERIOD);
        double price = cl[cl.length - 1];

        boolean[] stSeries = calcSupertrend(hi, lo, cl, ST_PERIOD, ST_MULTIPLIER);
        boolean stGreen = stSeries[stSeries.length - 1];

        double[] ema9Series = calcEMASeries(cl, EMA_FAST);
        int n = ema9Series.length;
        int lookback = Math.min(EMA_SLOPE_LOOKBACK_BARS, n - 1);
        double emaSlope = ema9Series[n - 1] - ema9Series[n - 1 - lookback];
        boolean slopeUp   = atr > 0 && emaSlope >= HTF_EMA_SLOPE_MIN_ATR * atr;
        boolean slopeDown = atr > 0 && emaSlope <= -HTF_EMA_SLOPE_MIN_ATR * atr;

        int structure = detectSwingStructure(hi, lo, STRUCTURE_SWING_LOOKBACK);

        int bull = 0, bear = 0;
        if (ema9 > ema21) bull++; else if (ema9 < ema21) bear++;
        if (price > ema9) bull++; else bear++;
        if (price > ema21) bull++; else bear++;
        if (stGreen) bull++; else bear++;
        if (slopeUp) bull++; else if (slopeDown) bear++;
        if (structure == 1) bull++; else if (structure == -1) bear++;

        r.valid = true;
        r.bullish = bull >= MACRO_1H_MIN_TRUE;
        r.bearish = bear >= MACRO_1H_MIN_TRUE;
        if (r.bullish && r.bearish) { // guard against a degenerate tie
            if (bull >= bear) r.bearish = false; else r.bullish = false;
        }
        r.score = r.bullish ? bull : (r.bearish ? bear : Math.max(bull, bear));
        return r;
    }

    // =========================================================================
    // PART 1 — 30M trend confirmation, scored out of 6 (needs 5/6, or 4/6 if
    // 15M is exceptionally strong — enforced by the caller).
    // =========================================================================
    private static class ConfirmResult {
        boolean valid;
        int bullScore, bearScore;
    }

    private static ConfirmResult analyzeConfirmation30M(JSONArray candles) {
        ConfirmResult r = new ConfirmResult();
        if (candles == null || candles.length() < EMA_MID + ST_PERIOD + STRUCTURE_SWING_LOOKBACK) {
            r.valid = false;
            return r;
        }
        double[] cl = extractCloses(candles);
        double[] hi = extractHighs(candles);
        double[] lo = extractLows(candles);

        double ema9  = calcEMA(cl, EMA_FAST);
        double ema21 = calcEMA(cl, EMA_MID);
        double atr   = calcATR(hi, lo, cl, ATR_PERIOD);
        double price = cl[cl.length - 1];

        boolean[] stSeries = calcSupertrend(hi, lo, cl, ST_PERIOD, ST_MULTIPLIER);
        boolean stGreen = stSeries[stSeries.length - 1];

        double[] ema9Series = calcEMASeries(cl, EMA_FAST);
        int n = ema9Series.length;
        int lookback = Math.min(EMA_SLOPE_LOOKBACK_BARS, n - 1);
        double emaSlope = ema9Series[n - 1] - ema9Series[n - 1 - lookback];
        boolean slopeUp   = atr > 0 && emaSlope >= HTF_EMA_SLOPE_MIN_ATR * atr;
        boolean slopeDown = atr > 0 && emaSlope <= -HTF_EMA_SLOPE_MIN_ATR * atr;

        int structure = detectSwingStructure(hi, lo, STRUCTURE_SWING_LOOKBACK);

        int bull = 0, bear = 0;
        if (ema9 > ema21) bull++; else if (ema9 < ema21) bear++;
        if (price > ema9) bull++; else bear++;
        if (price > ema21) bull++; else bear++;
        if (stGreen) bull++; else bear++;
        if (slopeUp) bull++; else if (slopeDown) bear++;
        if (structure == 1) bull++; else if (structure == -1) bear++;

        r.valid = true;
        r.bullScore = bull;
        r.bearScore = bear;
        return r;
    }

    // =========================================================================
    // PART 1 — 15M setup, with ATR-normalized EMA-congestion filter.
    // =========================================================================
    private static class SetupResult {
        boolean valid;
        boolean bullish;
        boolean bearish;
        double  atr;
        double  stLower, stUpper;
        double  emaDistanceAtr;
        boolean congested;
    }

    private static SetupResult analyzeSetup15M(JSONArray candles) {
        SetupResult r = new SetupResult();
        if (candles == null || candles.length() < EMA_MID + ST_PERIOD + STRUCTURE_SWING_LOOKBACK) {
            r.valid = false;
            return r;
        }
        double[] cl = extractCloses(candles);
        double[] hi = extractHighs(candles);
        double[] lo = extractLows(candles);

        double ema9  = calcEMA(cl, EMA_FAST);
        double ema21 = calcEMA(cl, EMA_MID);
        double price = cl[cl.length - 1];
        r.atr = calcATR(hi, lo, cl, ATR_PERIOD);

        boolean[] stSeries = calcSupertrend(hi, lo, cl, ST_PERIOD, ST_MULTIPLIER);
        boolean stGreen = stSeries[stSeries.length - 1];
        double[] bands = calcSupertrendBands(hi, lo, cl, ST_PERIOD, ST_MULTIPLIER);
        r.stLower = bands[0];
        r.stUpper = bands[1];

        double[] ema9Series = calcEMASeries(cl, EMA_FAST);
        int n = ema9Series.length;
        int lookback = Math.min(EMA_SLOPE_LOOKBACK_BARS, n - 1);
        double emaSlope = ema9Series[n - 1] - ema9Series[n - 1 - lookback];
        boolean slopeUp   = r.atr > 0 && emaSlope >= HTF_EMA_SLOPE_MIN_ATR * r.atr;
        boolean slopeDown = r.atr > 0 && emaSlope <= -HTF_EMA_SLOPE_MIN_ATR * r.atr;

        int structure = detectSwingStructure(hi, lo, STRUCTURE_SWING_LOOKBACK);

        r.emaDistanceAtr = r.atr > 0 ? Math.abs(ema9 - ema21) / r.atr : 0;
        r.congested = r.emaDistanceAtr < EMA_CONGESTION_MIN_ATR;

        boolean priceAboveBoth = price > ema9 && price > ema21;
        boolean priceBelowBoth = price < ema9 && price < ema21;

        r.valid = true;
        r.bullish = !r.congested && (ema9 > ema21) && stGreen && slopeUp && priceAboveBoth && structure >= 0;
        r.bearish = !r.congested && (ema9 < ema21) && !stGreen && slopeDown && priceBelowBoth && structure <= 0;
        return r;
    }

    // =========================================================================
    // PART 2/3 — 5M: pullback zone (ATR-normalized) + rejection + slope as
    // mandatory conditions, plus a 5-factor confirmation score. Produces the
    // confirmation candle's high/low for the breakout-entry trigger.
    // =========================================================================
    private static class EntryResult {
        boolean valid;
        boolean setupFound;
        double confirmHigh, confirmLow;
        double atr5m;
        double distanceAtr;
        String reason;
    }

    private static EntryResult analyzeEntry5M(JSONArray raw5m, boolean trendUp) {
        EntryResult t = new EntryResult();
        int minBars = Math.max(EMA_MID, Math.max(ENTRY_VOLUME_LOOKBACK, ENTRY_VWAP_LOOKBACK)) + RSI_PERIOD + 5;
        if (raw5m == null || raw5m.length() < minBars) {
            t.valid = false;
            return t;
        }

        double[] cl  = extractCloses(raw5m);
        double[] op  = extractOpens(raw5m);
        double[] hi  = extractHighs(raw5m);
        double[] lo  = extractLows(raw5m);
        double[] vol = extractVolumes(raw5m);
        int n = cl.length;

        double ema9  = calcEMA(cl, EMA_FAST);
        double ema21 = calcEMA(cl, EMA_MID);
        double atr5m = calcATR(hi, lo, cl, ATR_PERIOD);
        t.atr5m = atr5m;

        double entryClose = cl[n - 1], entryOpen = op[n - 1];
        double entryHigh  = hi[n - 1], entryLow  = lo[n - 1];

        double nearestEmaDist = Math.min(Math.abs(entryClose - ema9), Math.abs(entryClose - ema21));
        double distanceAtr = atr5m > 0 ? nearestEmaDist / atr5m : 0;
        t.distanceAtr = distanceAtr;

        boolean pulledBack   = distanceAtr >= PULLBACK_MIN_ATR && distanceAtr <= PULLBACK_MAX_ATR;
        boolean overextended = distanceAtr > OVEREXTENDED_ATR;

        boolean directionalCandle = trendUp ? (entryClose > entryOpen) : (entryClose < entryOpen);
        double body  = Math.abs(entryClose - entryOpen);
        double range = entryHigh - entryLow;
        boolean notDoji = range > 0 && (body / range) >= ENTRY_MIN_BODY_RATIO;

        double closePositionInRange = range > 0
                ? (trendUp ? (entryClose - entryLow) / range : (entryHigh - entryClose) / range)
                : 0;
        boolean rejectionOk = closePositionInRange >= 0.60;

        double[] ema9Series5m = calcEMASeries(cl, EMA_FAST);
        int lookback5m = Math.min(EMA_SLOPE_LOOKBACK_BARS, n - 1);
        double emaSlope5m = ema9Series5m[n - 1] - ema9Series5m[n - 1 - lookback5m];
        boolean slope5mOk = trendUp
                ? (atr5m > 0 && emaSlope5m >= ENTRY_EMA_SLOPE_MIN_ATR * atr5m)
                : (atr5m > 0 && emaSlope5m <= -ENTRY_EMA_SLOPE_MIN_ATR * atr5m);

        boolean mandatoryOk = pulledBack && !overextended && rejectionOk && directionalCandle && notDoji && slope5mOk;

        int volStart = Math.max(0, n - 1 - ENTRY_VOLUME_LOOKBACK);
        double avgVol = 0; int cnt = 0;
        for (int i = volStart; i < n - 1; i++) { avgVol += vol[i]; cnt++; }
        avgVol = cnt > 0 ? avgVol / cnt : 0;
        boolean volumeOk = avgVol > 0 && vol[n - 1] >= avgVol * ENTRY_VOLUME_MULTIPLIER;

        double rsi = calcRSI(cl, RSI_PERIOD);
        boolean rsiOk = trendUp
                ? (rsi >= RSI_LONG_MIN && rsi <= RSI_LONG_MAX)
                : (rsi >= RSI_SHORT_MIN && rsi <= RSI_SHORT_MAX);

        int vwapStart = Math.max(0, n - ENTRY_VWAP_LOOKBACK);
        double cumPV = 0, cumV = 0;
        for (int i = vwapStart; i < n; i++) {
            double typical = (hi[i] + lo[i] + cl[i]) / 3.0;
            cumPV += typical * vol[i];
            cumV  += vol[i];
        }
        double vwap = cumV > 0 ? cumPV / cumV : entryClose;
        boolean vwapOk = trendUp ? entryClose >= vwap : entryClose <= vwap;

        int confirmationScore = (volumeOk ? 1 : 0) + (rsiOk ? 1 : 0) + (vwapOk ? 1 : 0)
                + (slope5mOk ? 1 : 0) + (notDoji ? 1 : 0);

        t.setupFound = mandatoryOk && confirmationScore >= ENTRY_CONFIRMATION_MIN_SCORE;
        t.confirmHigh = entryHigh;
        t.confirmLow  = entryLow;
        t.valid = true;
        t.reason = String.format(
                "pullback=%.2fATR(inZone=%s,overext=%s) rejection=%s(pos=%.2f) directional=%s notDoji=%s slope=%s vol=%s(%.2fx) rsi=%.1f(ok=%s) vwap=%s score=%d/5",
                distanceAtr, pulledBack, overextended, rejectionOk, closePositionInRange, directionalCandle, notDoji,
                slope5mOk, volumeOk, avgVol > 0 ? vol[n - 1] / avgVol : 0, rsi, rsiOk, vwapOk, confirmationScore);
        return t;
    }

    private static JSONArray dropLastIfForming(JSONArray arr) {
        if (arr == null || arr.length() < 2) return arr;
        JSONArray out = new JSONArray();
        for (int i = 0; i < arr.length() - 1; i++) out.put(arr.getJSONObject(i));
        return out;
    }

    // =========================================================================
    // PART 5/6 — structural SL. Buffer is 0.3-0.6 ATR off the true invalidation
    // level (NOT a blind 4x ATR). If the resulting SL distance is outside the
    // acceptable ATR band, the trade is REJECTED — never force-tightened.
    // Returns {sl, tp, slDistanceAtr, rrUsed}, or null if rejected.
    // =========================================================================
    private static double[] computeStructuralSlTp(boolean isLong, double entryPrice,
                                                    double[] hi, double[] lo, double atr,
                                                    double stLevel, double tickSize,
                                                    boolean strongTrend) {
        if (atr <= 0) return null;
        double bufferMult = (SL_ATR_BUFFER_MULT_MIN + SL_ATR_BUFFER_MULT_MAX) / 2.0; // 0.45 ATR
        double sl;
        if (isLong) {
            double swingLow = recentLow(lo, SWING_LOOKBACK_BARS);
            double structural = Math.min(swingLow, stLevel);
            sl = structural - bufferMult * atr;
        } else {
            double swingHigh = recentHigh(hi, SWING_LOOKBACK_BARS);
            double structural = Math.max(swingHigh, stLevel);
            sl = structural + bufferMult * atr;
        }
        double slDistanceAtr = Math.abs(entryPrice - sl) / atr;
        if (slDistanceAtr > SL_MAX_ATR_DISTANCE || slDistanceAtr < SL_MIN_ATR_DISTANCE) {
            return null; // REJECT — do not tighten or widen artificially
        }
        double risk = Math.abs(entryPrice - sl);
        double rrTarget = strongTrend ? RR_TARGET_STRONG : RR_TARGET_BASE;
        double tp = isLong ? entryPrice + rrTarget * risk : entryPrice - rrTarget * risk;

        sl = roundToTick(sl, tickSize);
        tp = roundToTick(tp, tickSize);
        return new double[]{sl, tp, slDistanceAtr, rrTarget};
    }

    private static double recentLow(double[] lo, int lookback) {
        int n = lo.length;
        int start = Math.max(0, n - lookback);
        double min = Double.POSITIVE_INFINITY;
        for (int i = start; i < n; i++) min = Math.min(min, lo[i]);
        return min;
    }

    private static double recentHigh(double[] hi, int lookback) {
        int n = hi.length;
        int start = Math.max(0, n - lookback);
        double max = Double.NEGATIVE_INFINITY;
        for (int i = start; i < n; i++) max = Math.max(max, hi[i]);
        return max;
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
    // PART 15 — risk-based position sizing. Position size comes from the SL
    // distance, not a fixed margin: wider SL -> smaller size, tighter SL ->
    // larger size, always capped by MAX_MARGIN.
    // =========================================================================
    private static double calcRiskBasedQuantity(double entryPrice, double slPrice, String pair) {
        double slDistancePercent = Math.abs(entryPrice - slPrice) / entryPrice * 100.0;
        if (slDistancePercent <= 0) return 0;

        double riskAmount = TOTAL_CAPITAL_BASE * (RISK_PERCENT_PER_TRADE / 100.0);
        double positionNotional = riskAmount / (slDistancePercent / 100.0);

        double usdtInrRate = 98.0;
        double marginRequiredInr = positionNotional / LEVERAGE;
        if (marginRequiredInr > MAX_MARGIN) {
            positionNotional = MAX_MARGIN * LEVERAGE; // hard ceiling always wins
        }
        double qty = positionNotional / (entryPrice * usdtInrRate);
        double finalQty = INTEGER_QTY_PAIRS.contains(pair) ? Math.floor(qty) : Math.floor(qty * 100) / 100.0;
        return Math.max(finalQty, 0);
    }

    public static void main(String[] args) {
        System.out.println("=== Bot starting (quality-scored multi-TF cascade + structural SL + state trailing) ===");
        initInstrumentCache();

        while (true) {
            try {
                runEntryScan();
            } catch (Throwable t) {
                System.err.println("[MAIN-LOOP] Uncaught error, continuing: " + t.getMessage());
                t.printStackTrace();
            }
            try {
                TimeUnit.MILLISECONDS.sleep(SCALP_ENTRY_SCAN_INTERVAL_MS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private static void runEntryScan() {
        Set<String> active = getActivePositions();
        System.out.println("Active positions: " + active);

        trailState.keySet().removeIf(pair -> !active.contains(pair));
        pendingSignals.keySet().removeIf(active::contains); // once filled, drop the pending signal

        if (active.size() >= MAX_OPEN_POSITIONS) {
            System.out.println("MAX_OPEN_POSITIONS reached — skipping scan.");
            ensureTpSlForOpenPositions();
            if (TRAILING_ENABLED) updateTrailingForOpenPositions();
            return;
        }

        for (String pair : COINS_TO_TRADE) {
            try {
                if (active.size() >= MAX_OPEN_POSITIONS) break;
                if (active.contains(pair)) continue;

                long lastTrade = lastTradeTime.getOrDefault(pair, 0L);
                if (System.currentTimeMillis() - lastTrade < SCALP_COOLDOWN_MS) continue;

                JSONArray raw5m = dropLastIfForming(getCandlestickData(pair, RES_5M, BASE_5M_FETCH_COUNT));
                if (raw5m == null || raw5m.length() < EMA_MID + ST_PERIOD + STRUCTURE_SWING_LOOKBACK) continue;

                PendingSignal pending = pendingSignals.get(pair);

                // =========================================================
                // BUGFIX: a pending signal's breakout check used to sit
                // BEHIND the full 1H/30M/15M cascade, which had to re-pass
                // on every single 20s cycle just to reach the breakout
                // check below. Any flicker in a higher-timeframe condition
                // (very common) meant the loop hit "continue" earlier and
                // the pending signal was silently starved — it never
                // expired, never cancelled, never triggered. That is why
                // effectively zero orders were being placed. Pending
                // signals are now handled first and independently.
                // =========================================================
                if (pending != null) {
                    boolean cancelled = false;
                    if (System.currentTimeMillis() - pending.createdAtMs > SIGNAL_MAX_VALID_MS) {
                        System.out.println("  Signal expired: " + pair);
                        cancelled = true;
                    } else {
                        EntryResult quickCheck = analyzeEntry5M(raw5m, pending.isLong);
                        if (quickCheck.valid && quickCheck.distanceAtr > CAUTION_ATR) {
                            System.out.println("  Signal cancelled (overextended "
                                    + String.format("%.2f", quickCheck.distanceAtr) + " ATR): " + pair);
                            cancelled = true;
                        }
                    }

                    if (cancelled) {
                        pendingSignals.remove(pair);
                    } else {
                        double currentPrice = getLastPrice(pair);
                        if (currentPrice > 0) {
                            boolean breakoutHit = pending.isLong
                                    ? currentPrice > pending.confirmHigh
                                    : currentPrice < pending.confirmLow;
                            if (breakoutHit) {
                                tryEnterOnBreakout(pair, pending, raw5m, currentPrice, active);
                            }
                        }
                        continue; // still waiting (or just acted) on this pending signal this cycle
                    }
                }

                // ---- No pending signal (or it just expired/cancelled) — look for a new one ----

                // ---- PART 1: 1H macro bias ----
                JSONArray raw1h = dropLastIfForming(getCandlestickData(pair, RES_1H, BASE_1H_FETCH_COUNT));
                DirectionResult macro1h = analyzeMacro1H(raw1h);
                if (!macro1h.valid || (!macro1h.bullish && !macro1h.bearish)) {
                    continue; // NO TRADE: 1H has no directional quality
                }
                boolean trendUp = macro1h.bullish;

                // ---- PART 1: 30M confirmation, scored ----
                JSONArray raw30m = aggregateCandles(raw5m, GROUP_30M_FROM_5M);
                ConfirmResult confirm30 = analyzeConfirmation30M(raw30m);
                if (!confirm30.valid) continue;

                int score30    = trendUp ? confirm30.bullScore : confirm30.bearScore;
                int opposite30 = trendUp ? confirm30.bearScore : confirm30.bullScore;
                if (opposite30 > score30) {
                    System.out.println("  NO TRADE: " + pair + " — 1H/30M disagreement");
                    continue;
                }
                boolean strong30     = score30 >= CONFIRM_30M_MIN_SCORE;
                boolean borderline30 = score30 == CONFIRM_30M_BORDERLINE;
                if (!strong30 && !borderline30) {
                    System.out.println("  NO TRADE: " + pair + " — 30M score " + score30 + "/6 too low");
                    continue;
                }

                // ---- PART 1: 15M setup + EMA congestion filter ----
                JSONArray raw15m = aggregateCandles(raw5m, GROUP_15M_FROM_5M);
                SetupResult setup15 = analyzeSetup15M(raw15m);
                if (!setup15.valid) continue;
                if (setup15.congested) {
                    System.out.println("  NO TRADE: " + pair + " — EMA congestion ("
                            + String.format("%.2f", setup15.emaDistanceAtr) + " ATR)");
                    continue;
                }
                boolean setupMatches = trendUp ? setup15.bullish : setup15.bearish;
                if (!setupMatches) {
                    System.out.println("  NO TRADE: " + pair + " — 15M setup not aligned"
                            + (borderline30 ? " (30M was borderline 4/6, needed exceptional 15M)" : ""));
                    continue;
                }

                // ---- PART 2: 5M pullback/rejection/momentum ----
                EntryResult entry5m = analyzeEntry5M(raw5m, trendUp);
                if (!entry5m.valid) continue;

                if (entry5m.setupFound) {
                    PendingSignal newSignal = new PendingSignal();
                    newSignal.isLong = trendUp;
                    newSignal.confirmHigh = entry5m.confirmHigh;
                    newSignal.confirmLow  = entry5m.confirmLow;
                    newSignal.atrAtSignal = entry5m.atr5m;
                    newSignal.createdAtMs = System.currentTimeMillis();
                    pendingSignals.put(pair, newSignal);
                    System.out.println("  Pending " + (trendUp ? "LONG" : "SHORT") + " signal armed: " + pair
                            + " | trigger=" + (trendUp ? ("break " + newSignal.confirmHigh) : ("break " + newSignal.confirmLow))
                            + " | " + entry5m.reason);
                } else {
                    System.out.println("  NO TRADE: " + pair + " — 5M setup not found | " + entry5m.reason);
                }

            } catch (Exception e) {
                System.err.println("Error on " + pair + ": " + e.getMessage());
            }
        }

        System.out.println("\n=== Scan complete ===");
        ensureTpSlForOpenPositions();
        if (TRAILING_ENABLED) updateTrailingForOpenPositions();
    }

    // Handles an armed pending signal's breakout: computes structural SL/TP off
    // current data, sizes the position, places the order, confirms the fill,
    // re-derives final SL/TP off the actual fill price, and seeds trailing.
    // Extracted out of runEntryScan so pending signals can be checked every
    // cycle without needing the full 1H/30M/15M cascade to re-pass first.
    private static void tryEnterOnBreakout(String pair, PendingSignal pending, JSONArray raw5m,
                                            double currentPrice, Set<String> active) {
        try {
            double tickSize = getTickSize(pair);
            JSONArray raw15m = aggregateCandles(raw5m, GROUP_15M_FROM_5M);
            SetupResult setup15 = analyzeSetup15M(raw15m);
            if (!setup15.valid) return;
            double stLevel = pending.isLong ? setup15.stLower : setup15.stUpper;
            double[] hi5m = extractHighs(raw5m);
            double[] lo5m = extractLows(raw5m);
            double atr = calcATR(hi5m, lo5m, extractCloses(raw5m), ATR_PERIOD);

            // ---- PART 5/6: pre-trade structural SL check — reject BEFORE placing the order ----
            double[] preSlTp = computeStructuralSlTp(pending.isLong, currentPrice, hi5m, lo5m, atr, stLevel, tickSize, false);
            if (preSlTp == null) {
                System.out.println("  NO TRADE: " + pair + " — structural SL outside acceptable ATR band (rejected, not tightened)");
                pendingSignals.remove(pair);
                return;
            }

            // ---- PART 15: risk-based sizing off the pre-trade SL estimate ----
            double qty = calcRiskBasedQuantity(currentPrice, preSlTp[0], pair);
            if (qty <= 0) { pendingSignals.remove(pair); return; }

            System.out.println("\n==== " + pair + " — BREAKOUT ENTRY ====");
            String side = pending.isLong ? "buy" : "sell";
            JSONObject orderResp = placeFuturesOrder(side, pair, qty, LEVERAGE,
                    "email_notification", "isolated", "INR", currentPrice);
            if (orderResp == null || !orderResp.has("id")) {
                System.out.println("  Order failed: " + orderResp);
                pendingSignals.remove(pair);
                return;
            }

            System.out.println("  Order placed! id=" + orderResp.getString("id"));
            lastTradeTime.put(pair, System.currentTimeMillis());
            pendingSignals.remove(pair);

            double entry = getEntryPrice(pair, orderResp.getString("id"));
            if (entry <= 0) {
                System.out.println("  Could not confirm entry within window — TP/SL handled by safety sweep");
                active.add(pair);
                return;
            }
            System.out.printf("  Entry confirmed: %.6f%n", entry);

            // ---- Recompute SL/TP off the ACTUAL fill price ----
            double[] slTp = computeStructuralSlTp(pending.isLong, entry, hi5m, lo5m, atr, stLevel, tickSize, false);
            double slPrice, tpPrice, rrUsed;
            if (slTp == null) {
                // rare: slippage pushed the confirmed fill into reject territory. Do not leave the
                // position unprotected — apply the hard % safety cap instead of skipping protection.
                slPrice = pending.isLong ? entry * (1 - SL_HARD_PERCENT_CAP / 100.0) : entry * (1 + SL_HARD_PERCENT_CAP / 100.0);
                tpPrice = pending.isLong ? entry + RR_TARGET_BASE * (entry - slPrice) : entry - RR_TARGET_BASE * (slPrice - entry);
                rrUsed = RR_TARGET_BASE;
                System.out.println("  WARNING: post-fill structural SL rejected — using hard % safety cap instead");
            } else {
                slPrice = slTp[0];
                tpPrice = slTp[1];
                rrUsed  = slTp[3];
            }
            double[] clamped = sanityClampSlTp(pending.isLong, entry, slPrice, tpPrice, tickSize);
            slPrice = clamped[0];
            tpPrice = clamped[1];

            System.out.printf("  SL=%.6f | TP=%.6f | RR=%.2f | QTY=%.4f%n", slPrice, tpPrice, rrUsed, qty);
            System.out.println("  " + (pending.isLong ? "LONG" : "SHORT") + " SIGNAL: 5M=PULLBACK_REJECTION_BREAKOUT"
                    + " SL=" + slPrice + " TP=" + tpPrice + " RR=" + rrUsed + " QTY=" + qty);

            String posId = getPositionId(pair);
            if (posId != null) {
                setTpSlWithRetry(posId, tpPrice, slPrice, pair);
            } else {
                System.out.println("  Position ID not found after retries — safety sweep will handle it");
            }

            if (TRAILING_ENABLED) {
                TrailInfo ti = new TrailInfo();
                ti.isLong = pending.isLong;
                ti.entryPrice = entry;
                ti.initialRisk = Math.abs(entry - slPrice);
                ti.initialReward = Math.abs(tpPrice - entry);
                ti.currentSl = slPrice;
                ti.currentTp = tpPrice;
                ti.peak = entry;
                ti.state = 0;
                ti.extensionsUsed = 0;
                trailState.put(pair, ti);
            }

            active.add(pair);
        } catch (Exception e) {
            System.err.println("tryEnterOnBreakout(" + pair + "): " + e.getMessage());
        }
    }

    // =========================================================================
    // PART 9/10/11 — state-based trailing. Ratchet-only (never loosens SL).
    // STATE 0: initial SL.
    // STATE 1 (>=0.5R): wide trail active (2.25 ATR) but no aggressive move yet.
    // STATE 2 (>=1R):   small locked-profit buffer set (not pure breakeven),
    //                   trail tightens to 1.75 ATR.
    // STATE 3 (>=1.5R): trail tightens further to 1.35 ATR.
    // STATE 4 (>=2R):   same tight trail — lets strong trends keep running.
    // TP extension only fires if trendStillHealthy() confirms 15M still agrees.
    // =========================================================================
    private static void updateTrailingForOpenPositions() {
        for (Map.Entry<String, TrailInfo> e : trailState.entrySet()) {
            String pair = e.getKey();
            TrailInfo ti = e.getValue();
            try {
                JSONObject pos = findPosition(pair);
                if (pos == null) continue;

                double currentPrice = getLastPrice(pair);
                if (currentPrice <= 0) continue;

                JSONArray raw5m = dropLastIfForming(getCandlestickData(pair, RES_5M, BASE_5M_FETCH_COUNT));
                if (raw5m == null || raw5m.length() < ATR_PERIOD + 5) continue;
                double[] hi5m = extractHighs(raw5m);
                double[] lo5m = extractLows(raw5m);
                double[] cl5m = extractCloses(raw5m);
                double atr5m = calcATR(hi5m, lo5m, cl5m, ATR_PERIOD);
                if (atr5m <= 0) continue;

                double favorableMove = ti.isLong ? currentPrice - ti.entryPrice : ti.entryPrice - currentPrice;
                double favorableR = ti.initialRisk > 0 ? favorableMove / ti.initialRisk : 0;

                if (ti.isLong) ti.peak = Math.max(ti.peak, currentPrice);
                else ti.peak = Math.min(ti.peak, currentPrice);

                int newState = favorableR >= 2.0 ? 4 : favorableR >= 1.5 ? 3 : favorableR >= 1.0 ? 2 : favorableR >= 0.5 ? 1 : 0;
                boolean changed = false;
                double tick = getTickSize(pair);

                if (newState >= 2 && ti.state < 2) {
                    // Reaching +1R: lock a small profit rather than pure breakeven.
                    double lockedSl = ti.isLong
                            ? roundToTick(ti.entryPrice * (1 + BREAKEVEN_BUFFER_PCT / 100.0), tick)
                            : roundToTick(ti.entryPrice * (1 - BREAKEVEN_BUFFER_PCT / 100.0), tick);
                    if ((ti.isLong && lockedSl > ti.currentSl) || (!ti.isLong && lockedSl < ti.currentSl)) {
                        ti.currentSl = lockedSl;
                        changed = true;
                    }
                }
                if (newState > ti.state) ti.state = newState;

                double trailMult = ti.state >= 3 ? TRAIL_ATR_STAGE3 : ti.state == 2 ? TRAIL_ATR_STAGE2 : TRAIL_ATR_EARLY;
                if (ti.state >= 1) {
                    double candidateSl = ti.isLong
                            ? roundToTick(ti.peak - trailMult * atr5m, tick)
                            : roundToTick(ti.peak + trailMult * atr5m, tick);
                    if (ti.isLong && candidateSl > ti.currentSl) { ti.currentSl = candidateSl; changed = true; }
                    if (!ti.isLong && candidateSl < ti.currentSl) { ti.currentSl = candidateSl; changed = true; }
                }

                if (ti.extensionsUsed < TP_MAX_EXTENSIONS) {
                    double distToTp = ti.isLong ? ti.currentTp - ti.entryPrice : ti.entryPrice - ti.currentTp;
                    double triggerLevel = ti.isLong
                            ? ti.entryPrice + TP_EXTEND_TRIGGER_FRACTION * distToTp
                            : ti.entryPrice - TP_EXTEND_TRIGGER_FRACTION * distToTp;
                    boolean nearTp = ti.isLong ? currentPrice >= triggerLevel : currentPrice <= triggerLevel;
                    if (distToTp > 0 && nearTp && trendStillHealthy(pair, ti.isLong)) {
                        double newTp = ti.isLong
                                ? roundToTick(currentPrice + ti.initialReward, tick)
                                : roundToTick(currentPrice - ti.initialReward, tick);
                        if ((ti.isLong && newTp > ti.currentTp) || (!ti.isLong && newTp < ti.currentTp)) {
                            ti.currentTp = newTp;
                            ti.extensionsUsed++;
                            changed = true;
                        }
                    }
                }

                if (changed) {
                    double[] clamped = sanityClampSlTp(ti.isLong, currentPrice, ti.currentSl, ti.currentTp, tick);
                    ti.currentSl = clamped[0];
                    ti.currentTp = clamped[1];
                    String posId = pos.optString("id", null);
                    if (posId != null) {
                        System.out.printf("  [TRAIL] %s state=%d SL=%.6f TP=%.6f (ext=%d/%d, R=%.2f)%n",
                                pair, ti.state, ti.currentSl, ti.currentTp, ti.extensionsUsed, TP_MAX_EXTENSIONS, favorableR);
                        setTpSlWithRetry(posId, ti.currentTp, ti.currentSl, pair);
                    }
                }
            } catch (Exception ex) {
                System.err.println("updateTrailingForOpenPositions(" + pair + "): " + ex.getMessage());
            }
        }
    }

    // PART 11 — before extending TP, re-check that the 15M trend still agrees.
    // If unsure (data issue), do NOT extend — let the trailing SL protect what's banked.
    private static boolean trendStillHealthy(String pair, boolean isLong) {
        try {
            JSONArray raw5m = dropLastIfForming(getCandlestickData(pair, RES_5M, BASE_5M_FETCH_COUNT));
            if (raw5m == null || raw5m.length() < EMA_MID + ST_PERIOD + STRUCTURE_SWING_LOOKBACK) return false;
            JSONArray raw15m = aggregateCandles(raw5m, GROUP_15M_FROM_5M);
            SetupResult s15 = analyzeSetup15M(raw15m);
            if (!s15.valid) return false;
            return isLong ? s15.bullish : s15.bearish;
        } catch (Exception e) {
            return false;
        }
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
                JSONArray raw5m = dropLastIfForming(getCandlestickData(pair, RES_5M, BASE_5M_FETCH_COUNT));
                if (raw5m == null || raw5m.length() < EMA_MID + ST_PERIOD + STRUCTURE_SWING_LOOKBACK) {
                    System.out.println("  [SWEEP] insufficient 5M data for " + pair + " — will retry next run");
                    continue;
                }

                JSONArray raw15mSweep = aggregateCandles(raw5m, GROUP_15M_FROM_5M);
                SetupResult setup15Sweep = analyzeSetup15M(raw15mSweep);
                if (!setup15Sweep.valid) {
                    System.out.println("  [SWEEP] insufficient 15M data for " + pair + " — will retry next run");
                    continue;
                }

                double[] hi5m = extractHighs(raw5m);
                double[] lo5m = extractLows(raw5m);
                double[] cl5m = extractCloses(raw5m);
                double atr5m = calcATR(hi5m, lo5m, cl5m, ATR_PERIOD);
                if (atr5m <= 0) {
                    System.out.println("  [SWEEP] invalid 5M ATR for " + pair + " — will retry next run");
                    continue;
                }

                double posQty = pos.optDouble("active_pos", 0);
                boolean isLong = posQty >= 0;
                double stLevel = isLong ? setup15Sweep.stLower : setup15Sweep.stUpper;
                double tick = getTickSize(pair);

                double[] slTp = computeStructuralSlTp(isLong, avgPrice, hi5m, lo5m, atr5m, stLevel, tick, false);
                double sl, tp;
                if (slTp == null) {
                    sl = isLong ? avgPrice * (1 - SL_HARD_PERCENT_CAP / 100.0) : avgPrice * (1 + SL_HARD_PERCENT_CAP / 100.0);
                    tp = isLong ? avgPrice + RR_TARGET_BASE * (avgPrice - sl) : avgPrice - RR_TARGET_BASE * (sl - avgPrice);
                    System.out.println("  [SWEEP] structural SL rejected for " + pair + " — using hard % fallback cap");
                } else {
                    sl = slTp[0];
                    tp = slTp[1];
                }
                double[] clamped = sanityClampSlTp(isLong, avgPrice, sl, tp, tick);
                sl = clamped[0];
                tp = clamped[1];

                String posId = pos.optString("id", null);
                if (posId != null) {
                    System.out.printf("  [SWEEP] %s fallback SL=%.6f TP=%.6f%n", pair, sl, tp);
                    setTpSlWithRetry(posId, tp, sl, pair);

                    if (TRAILING_ENABLED && !trailState.containsKey(pair)) {
                        TrailInfo ti = new TrailInfo();
                        ti.isLong = isLong;
                        ti.entryPrice = avgPrice;
                        ti.initialRisk = Math.abs(avgPrice - sl);
                        ti.initialReward = Math.abs(tp - avgPrice);
                        ti.currentSl = sl;
                        ti.currentTp = tp;
                        ti.peak = avgPrice;
                        ti.state = 0;
                        ti.extensionsUsed = 0;
                        trailState.put(pair, ti);
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

    private static double[] calcEMASeries(double[] d, int period) {
        double[] out = new double[d.length];
        if (d.length < period) {
            double last = d.length > 0 ? d[d.length - 1] : 0;
            Arrays.fill(out, last);
            return out;
        }
        double k = 2.0 / (period + 1);
        double ema = 0;
        for (int i = 0; i < period; i++) ema += d[i];
        ema /= period;
        for (int i = 0; i < period; i++) out[i] = ema;
        out[period - 1] = ema;
        for (int i = period; i < d.length; i++) {
            ema = d[i] * k + ema * (1 - k);
            out[i] = ema;
        }
        return out;
    }

    private static double calcEMA(double[] d, int period) {
        if (d.length < period) return 0;
        double k = 2.0 / (period + 1), ema = 0;
        for (int i = 0; i < period; i++) ema += d[i];
        ema /= period;
        for (int i = period; i < d.length; i++) ema = d[i] * k + ema * (1 - k);
        return ema;
    }

    private static double calcRSI(double[] closes, int period) {
        if (closes.length < period + 1) return 50.0;

        double avgGain = 0, avgLoss = 0;
        for (int i = 1; i <= period; i++) {
            double change = closes[i] - closes[i - 1];
            if (change > 0) avgGain += change;
            else avgLoss += -change;
        }
        avgGain /= period;
        avgLoss /= period;

        for (int i = period + 1; i < closes.length; i++) {
            double change = closes[i] - closes[i - 1];
            double gain = Math.max(change, 0);
            double loss = Math.max(-change, 0);
            avgGain = (avgGain * (period - 1) + gain) / period;
            avgLoss = (avgLoss * (period - 1) + loss) / period;
        }

        if (avgLoss == 0) return 100.0;
        double rs = avgGain / avgLoss;
        return 100.0 - (100.0 / (1.0 + rs));
    }

    private static BigDecimal roundToTickBD(double price, double tick) {
        if (tick <= 0) return BigDecimal.valueOf(price);
        BigDecimal bdPrice = BigDecimal.valueOf(price);
        BigDecimal bdTick  = BigDecimal.valueOf(tick);
        BigDecimal multiples = bdPrice.divide(bdTick, 0, RoundingMode.HALF_UP);
        BigDecimal result = multiples.multiply(bdTick);
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
    private static double[] extractVolumes(JSONArray a) {
        double[] o = new double[a.length()];
        for (int i = 0; i < a.length(); i++) o[i] = a.getJSONObject(i).optDouble("volume", 0);
        return o;
    }

    private static JSONArray getCandlestickData(String pair, String resolution, int count) {
        try {
            long minsPerBar;
            switch (resolution) {
                case "1":   minsPerBar = 1;   break;
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
            double volSum = 0;
            for (int j = i; j < i + groupSize; j++) {
                JSONObject c = source.getJSONObject(j);
                high = Math.max(high, c.getDouble("high"));
                low  = Math.min(low,  c.getDouble("low"));
                volSum += c.optDouble("volume", 0);
            }
            JSONObject merged = new JSONObject();
            merged.put("open", open);
            merged.put("close", close);
            merged.put("high", high);
            merged.put("low", low);
            merged.put("volume", volSum);
            result.put(merged);
        }
        return result;
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
