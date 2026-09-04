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
    // API Configuration
    // =========================================================================
    private static final String API_KEY    = System.getenv("DELTA_API_KEY");
    private static final String API_SECRET = System.getenv("DELTA_API_SECRET");
    private static final String BASE_URL       = "https://api.coindcx.com";
    private static final String PUBLIC_API_URL = "https://public.coindcx.com";

    private static final double MAX_MARGIN = 1500.0;
    private static final int LEVERAGE = 15;

    private static final int MAX_ENTRY_PRICE_CHECKS = 20;
    private static final int ENTRY_CHECK_DELAY_MS    = 1000;

    private static final int  TPSL_MAX_RETRIES    = 3;
    private static final long TPSL_RETRY_DELAY_MS = 2000L;

    private static final long TICK_CACHE_TTL_MS = 3_600_000L;

    private static final int MAX_OPEN_POSITIONS = 120;

    private static final int  POSITION_ID_MAX_RETRIES = 5;
    private static final long POSITION_ID_RETRY_DELAY_MS = 1500L;

    // =========================================================================
    // Direction -> 1H+30M, Setup -> 15M, Entry -> 5M.
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

    // ---- RSI (momentum filter) ----
    private static final int RSI_PERIOD = 14;
    private static final double RSI_LONG_MIN  = 40, RSI_LONG_MAX  = 70;
    private static final double RSI_SHORT_MIN = 30, RSI_SHORT_MAX = 60;

    // ---- Entry (5M) filter thresholds ----
    private static final int    ENTRY_VOLUME_LOOKBACK   = 20;
    private static final double ENTRY_VOLUME_MULTIPLIER = 1.05;

    private static final double ENTRY_PULLBACK_MAX_ATR  = 0.6;
    private static final double ENTRY_MIN_BODY_RATIO    = 0.30;

    private static final int    ENTRY_VWAP_LOOKBACK      = 20;
    private static final double ENTRY_MAX_VWAP_DIST_ATR  = 0.6;

    private static final int ENTRY_CONFIRMATION_MIN_SCORE = 3;

    // ---- SL/TP sizing — FIXED, set once at entry, never adjusted after. ----
    private static final int    SWING_LOOKBACK_BARS = 20;
    private static final double SL_ATR_BUFFER_MULT  = 1.50;
    private static final double SL_MAX_PERCENT = 2.5;
    private static final double RR_TARGET = 1.5;

    private static final double LIMIT_ORDER_BUFFER_PCT = 0.0005;

    private static final long SCALP_COOLDOWN_MS            = 5 * 60 * 1000L;
    private static final long SCALP_ENTRY_SCAN_INTERVAL_MS = 20 * 1000L;

    // EMA9 slope/angle check — used on 1H/30M (Direction), 15M (Setup),
    // and NOW also 5M (Entry) — see analyzeEntry() below.
    private static final int    EMA_SLOPE_LOOKBACK_BARS = 5;
    private static final double EMA_SLOPE_MIN_ATR        = 0.15;

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

    private static class DirectionResult {
        boolean valid;
        boolean bullish;
        boolean bearish;
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

    private static DirectionResult analyzeDirection(JSONArray candles) {
        DirectionResult r = new DirectionResult();
        if (candles == null || candles.length() < EMA_MID + Math.max(ATR_PERIOD, ST_PERIOD) + 5) {
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
        boolean slopeUp   = atr > 0 && emaSlope >= EMA_SLOPE_MIN_ATR * atr;
        boolean slopeDown = atr > 0 && emaSlope <= -EMA_SLOPE_MIN_ATR * atr;

        boolean priceAboveBoth = price > ema9 && price > ema21;
        boolean priceBelowBoth = price < ema9 && price < ema21;

        r.valid = true;
        r.bullish = (ema9 > ema21) && slopeUp && stGreen && priceAboveBoth;
        r.bearish = (ema9 < ema21) && slopeDown && !stGreen && priceBelowBoth;
        return r;
    }

    private static class SetupResult {
        boolean valid;
        boolean bullish;
        boolean bearish;
        double  atr;
        double  stLower;
        double  stUpper;
    }

    private static SetupResult analyzeSetup(JSONArray candles) {
        SetupResult r = new SetupResult();
        if (candles == null || candles.length() < EMA_MID + ST_PERIOD + 5) {
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
        boolean slopeUp   = r.atr > 0 && emaSlope >= EMA_SLOPE_MIN_ATR * r.atr;
        boolean slopeDown = r.atr > 0 && emaSlope <= -EMA_SLOPE_MIN_ATR * r.atr;

        boolean priceAboveBoth = price > ema9 && price > ema21;
        boolean priceBelowBoth = price < ema9 && price < ema21;

        r.valid = true;
        r.bullish = (ema9 > ema21) && stGreen && slopeUp && priceAboveBoth;
        r.bearish = (ema9 < ema21) && !stGreen && slopeDown && priceBelowBoth;
        return r;
    }

    // =========================================================================
    // Entry result (5M) — NEW: EMA9-slope check added as a MANDATORY
    // condition, matching the same pattern already used on 1H/30M/15M. This
    // catches cases where price is technically "pulled back near EMA9/21"
    // (the existing pullback check) but the EMA9 itself has gone flat or
    // started curving the other way — i.e. momentum on the entry timeframe
    // itself is fading, not just a healthy pullback within an intact trend.
    // =========================================================================
    private static class EntryResult {
        boolean valid;
        boolean triggered;
        double entryClose, entryOpen, entryHigh, entryLow;
        double atr5m;
        String reason;
    }

    private static EntryResult analyzeEntry(JSONArray raw5m, boolean trendUp) {
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
        t.entryClose = entryClose; t.entryOpen = entryOpen;
        t.entryHigh = entryHigh;   t.entryLow = entryLow;

        double distToEma9  = Math.abs(entryClose - ema9);
        double distToEma21 = Math.abs(entryClose - ema21);
        double nearestEmaDist = Math.min(distToEma9, distToEma21);
        boolean pulledBack = atr5m > 0 && nearestEmaDist <= ENTRY_PULLBACK_MAX_ATR * atr5m;

        boolean directionalCandle = trendUp ? (entryClose > entryOpen) : (entryClose < entryOpen);
        double body  = Math.abs(entryClose - entryOpen);
        double range = entryHigh - entryLow;
        boolean notDoji = range > 0 && (body / range) >= ENTRY_MIN_BODY_RATIO;

        // NEW — 5M EMA9-slope check (same ATR-scaled method used on the
        // other three timeframes).
        double[] ema9Series5m = calcEMASeries(cl, EMA_FAST);
        int lookback5m = Math.min(EMA_SLOPE_LOOKBACK_BARS, n - 1);
        double emaSlope5m = ema9Series5m[n - 1] - ema9Series5m[n - 1 - lookback5m];
        boolean slope5mOk = trendUp
                ? (atr5m > 0 && emaSlope5m >= EMA_SLOPE_MIN_ATR * atr5m)
                : (atr5m > 0 && emaSlope5m <= -EMA_SLOPE_MIN_ATR * atr5m);

        boolean mandatoryOk = pulledBack && directionalCandle && notDoji && slope5mOk;

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
        double distFromVwap = Math.abs(entryClose - vwap);
        boolean vwapOk = atr5m > 0 && distFromVwap <= ENTRY_MAX_VWAP_DIST_ATR * atr5m;

        int confirmationScore = (volumeOk ? 1 : 0) + (rsiOk ? 1 : 0) + (vwapOk ? 1 : 0);

        t.triggered = mandatoryOk && confirmationScore >= ENTRY_CONFIRMATION_MIN_SCORE;
        t.valid = true;
        t.reason = String.format(
                "mandatory[pulledBack=%s directional=%s notDoji=%s slope5m=%s(%.6f)] confirmation[volume=%s(%.2fx) rsi=%.1f(ok=%s) vwap=%s] score=%d/3",
                pulledBack, directionalCandle, notDoji, slope5mOk, emaSlope5m,
                volumeOk, avgVol > 0 ? vol[n - 1] / avgVol : 0,
                rsi, rsiOk, vwapOk, confirmationScore);
        return t;
    }

    private static JSONArray dropLastIfForming(JSONArray arr) {
        if (arr == null || arr.length() < 2) return arr;
        JSONArray out = new JSONArray();
        for (int i = 0; i < arr.length() - 1; i++) out.put(arr.getJSONObject(i));
        return out;
    }

    // =========================================================================
    // FIXED SL/TP — UNCHANGED.
    // =========================================================================
    private static double[] computeSlTp(boolean isLong, double entryPrice,
                                         double[] hi5m, double[] lo5m, double atr5m,
                                         double stLevel, double tickSize) {
        double sl, tp;
        if (isLong) {
            double swingLow = recentLow(lo5m, SWING_LOOKBACK_BARS);
            double structuralLow = Math.min(swingLow, stLevel);
            double raw = structuralLow - SL_ATR_BUFFER_MULT * atr5m;
            double hardFloor = entryPrice * (1 - SL_MAX_PERCENT / 100.0);
            sl = Math.max(raw, hardFloor);
            double risk = entryPrice - sl;
            tp = entryPrice + RR_TARGET * risk;
        } else {
            double swingHigh = recentHigh(hi5m, SWING_LOOKBACK_BARS);
            double structuralHigh = Math.max(swingHigh, stLevel);
            double raw = structuralHigh + SL_ATR_BUFFER_MULT * atr5m;
            double hardCeil = entryPrice * (1 + SL_MAX_PERCENT / 100.0);
            sl = Math.min(raw, hardCeil);
            double risk = sl - entryPrice;
            tp = entryPrice - RR_TARGET * risk;
        }
        sl = roundToTick(sl, tickSize);
        tp = roundToTick(tp, tickSize);
        return new double[]{sl, tp};
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

    private static double calcQuantity(double price, String pair) {
        double usdtInrRate = 98.0;
        double qty = MAX_MARGIN / (price * usdtInrRate);
        double finalQty = INTEGER_QTY_PAIRS.contains(pair)
                ? Math.floor(qty)
                : Math.floor(qty * 100) / 100.0;
        return Math.max(finalQty, 0);
    }

    public static void main(String[] args) {
        System.out.println("=== Scalp bot starting (fixed margin, fixed SL/TP, no exit-monitoring) ===");
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
                if (active.contains(pair)) continue;

                long lastTrade = lastTradeTime.getOrDefault(pair, 0L);
                if (System.currentTimeMillis() - lastTrade < SCALP_COOLDOWN_MS) continue;

                JSONArray raw5m = dropLastIfForming(
                        getCandlestickData(pair, RES_5M, BASE_5M_FETCH_COUNT));
                if (raw5m == null || raw5m.length() < EMA_MID + ST_PERIOD + 5) continue;

                JSONArray raw30m = aggregateCandles(raw5m, GROUP_30M_FROM_5M);
                DirectionResult dir30m = analyzeDirection(raw30m);
                if (!dir30m.valid || (!dir30m.bullish && !dir30m.bearish)) continue;

                JSONArray raw15m = aggregateCandles(raw5m, GROUP_15M_FROM_5M);
                SetupResult setup15m = analyzeSetup(raw15m);
                if (!setup15m.valid) continue;

                boolean setupMatches30m = (dir30m.bullish && setup15m.bullish)
                        || (dir30m.bearish && setup15m.bearish);
                if (!setupMatches30m) continue;

                boolean trendUp = dir30m.bullish;

                JSONArray raw1h = dropLastIfForming(
                        getCandlestickData(pair, RES_1H, BASE_1H_FETCH_COUNT));
                DirectionResult dir1h = analyzeDirection(raw1h);
                if (!dir1h.valid) continue;

                boolean dir1hMatches = (trendUp && dir1h.bullish) || (!trendUp && dir1h.bearish);
                if (!dir1hMatches) continue;

                EntryResult entry5m = analyzeEntry(raw5m, trendUp);
                if (!entry5m.valid) continue;
                if (!entry5m.triggered) {
                    continue;
                }

                System.out.println("\n==== " + pair + " ====");
                System.out.printf("  [1H] %s | [30M] %s | [15M] %s | [5M-Entry] %s%n",
                        dir1h.bullish ? "BULLISH" : "BEARISH",
                        trendUp ? "BULLISH" : "BEARISH",
                        setup15m.bullish ? "BULLISH" : "BEARISH",
                        entry5m.reason);

                String side = trendUp ? "buy" : "sell";
                System.out.println("  ╔══════════════════════════════════════════════════╗");
                System.out.println("  ║  SCALP TRIGGER → " + side.toUpperCase() + " " + pair);
                System.out.println("  ╚══════════════════════════════════════════════════╝");

                double currentPrice = getLastPrice(pair);
                if (currentPrice <= 0) continue;
                double qty = calcQuantity(currentPrice, pair);
                if (qty <= 0) continue;
                double tickSize = getTickSize(pair);

                System.out.printf("  Placing %s | price=%.6f | qty=%.4f | lev=%dx%n",
                        side.toUpperCase(), currentPrice, qty, LEVERAGE);

                JSONObject resp = placeFuturesOrder(side, pair, qty, LEVERAGE,
                        "email_notification", "isolated", "INR", currentPrice);
                if (resp == null || !resp.has("id")) {
                    System.out.println("  Order failed: " + resp);
                    continue;
                }

                System.out.println("  Order placed! id=" + resp.getString("id"));
                lastTradeTime.put(pair, System.currentTimeMillis());

                double entry = getEntryPrice(pair, resp.getString("id"));
                if (entry <= 0) {
                    System.out.println("  Could not confirm entry within window — TP/SL will be handled by safety sweep");
                    active.add(pair);
                    continue;
                }

                System.out.printf("  Entry confirmed: %.6f%n", entry);

                double[] hi5m = extractHighs(raw5m);
                double[] lo5m = extractLows(raw5m);
                double stLevel = trendUp ? setup15m.stLower : setup15m.stUpper;
                double[] slTp = computeSlTp(trendUp, entry, hi5m, lo5m, entry5m.atr5m, stLevel, tickSize);
                double[] clamped = sanityClampSlTp(trendUp, entry, slTp[0], slTp[1], tickSize);
                double slPrice = clamped[0], tpPrice = clamped[1];
                double slPct = Math.abs(entry - slPrice) / entry * 100;
                double tpPct = Math.abs(tpPrice - entry) / entry * 100;

                System.out.printf("  SL=%.6f (%.3f%%) | TP=%.6f (%.3f%%) | RR target=%.2f%n",
                        slPrice, slPct, tpPrice, tpPct, RR_TARGET);

                String posId = getPositionId(pair);
                if (posId != null) {
                    setTpSlWithRetry(posId, tpPrice, slPrice, pair);
                } else {
                    System.out.println("  Position ID not found after retries — TP/SL will be handled by safety sweep");
                }

                active.add(pair);

            } catch (Exception e) {
                System.err.println("Error on " + pair + ": " + e.getMessage());
            }
        }

        System.out.println("\n=== Scalp scan complete ===");
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
                JSONArray raw5m = dropLastIfForming(
                        getCandlestickData(pair, RES_5M, BASE_5M_FETCH_COUNT));

                if (raw5m == null || raw5m.length() < EMA_MID + ST_PERIOD + 5) {
                    System.out.println("  [SWEEP] insufficient 5M data for " + pair
                            + " — will retry next run");
                    continue;
                }

                JSONArray raw15mSweep = aggregateCandles(raw5m, GROUP_15M_FROM_5M);
                SetupResult setup15mSweep = analyzeSetup(raw15mSweep);
                if (!setup15mSweep.valid) {
                    System.out.println("  [SWEEP] insufficient 15M data for " + pair
                            + " — will retry next run");
                    continue;
                }

                double[] hi5m = extractHighs(raw5m);
                double[] lo5m = extractLows(raw5m);
                double[] cl5m = extractCloses(raw5m);
                double atr5m = calcATR(hi5m, lo5m, cl5m, ATR_PERIOD);
                if (atr5m <= 0) {
                    System.out.println("  [SWEEP] invalid 5M ATR for " + pair
                            + " — will retry next run");
                    continue;
                }

                double posQty = pos.optDouble("active_pos", 0);
                boolean isLong = posQty >= 0;
                double stLevel = isLong ? setup15mSweep.stLower : setup15mSweep.stUpper;

                double tick = getTickSize(pair);
                double[] slTp = computeSlTp(isLong, avgPrice, hi5m, lo5m, atr5m, stLevel, tick);
                double[] clamped = sanityClampSlTp(isLong, avgPrice, slTp[0], slTp[1], tick);
                double sl = clamped[0], tp = clamped[1];

                String posId = pos.optString("id", null);
                if (posId != null) {
                    System.out.printf("  [SWEEP] %s fallback SL=%.6f TP=%.6f (RR target=%.2f)%n", pair, sl, tp, RR_TARGET);
                    setTpSlWithRetry(posId, tp, sl, pair);
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
