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

/**
 * CoinDCX Futures Trader â€” V4 FINAL STRATEGY
 *
 * FULL FILTER STACK (HARD filters â€” ALL must pass):
 *
 *   H0a. VOLATILITY GATE:
 *        ATR must be 0.3%â€“5.0% of price.
 *        Skips dead flat markets and chaotic spike events.
 *
 *   H0b. VOLUME SPIKE (NEW â€” MUST DO):
 *        Current 15m candle volume > 1.5Ã— average of last 20 closed bars.
 *        Avoids entering on low-conviction, low-volume moves.
 *
 *   H1.  4H EMA50 Macro Direction (NEW â€” SHOULD DO):
 *        Price must be above 4H EMA50 for LONG, below for SHORT.
 *        Highest timeframe anchor â€” filters against macro counter-trend trades.
 *
 *   H1b. 1H EMA50 Trend Confirmation:
 *        Price above 1H EMA50 = bull. Below = bear.
 *        Both H1 and H1b must agree for entry.
 *
 *   H2.  15m 3-EMA Stack:
 *        LONG:  EMA9 > EMA21 > EMA50 on 15m (strong bullish structure)
 *        SHORT: EMA9 < EMA21 < EMA50 on 15m (strong bearish structure)
 *
 *   H2b. EMA21 Slope:
 *        EMA21 must be rising for LONG, falling for SHORT.
 *        Eliminates flat/range-bound market entries.
 *
 *   H3.  MACD â€” Improved (MUST DO):
 *        (a) MACD line must be on the correct side of signal line.
 *        (b) Histogram must be on correct sign (positive for LONG, negative for SHORT).
 *        (c) Histogram absolute value must be growing for 2 consecutive bars.
 *        This prevents entering on fading or diverging MACD momentum.
 *
 *   H4.  Pullback Zone â€” Tightened (SHOULD DO):
 *        Price must be within 1.5Ã—ATR of EMA21.
 *        ADDITIONALLY: at least one of the last 3 candle lows (LONG) or highs (SHORT)
 *        must have touched within 1.5Ã—ATR of EMA21 â€” confirming a genuine pullback
 *        to the EMA, not price just hovering above/below it.
 *
 * SOFT FILTERS (at least 1 of 3 must pass):
 *   S1. RSI in valid zone (Long: 40â€“68, Short: 32â€“60)
 *   S2. Previous closed candle matches direction
 *   S3. 5m EMA9 vs EMA21 aligned with trend (if candles available)
 *
 * EXIT LOGIC:
 *   Initial SL: structural swing Â± 0.5Ã—ATR, clamped to 4.5â€“7.0Ã—ATR from entry
 *   Initial TP: entry Â± 2.2 Ã— risk (2.2:1 RR)
 *   Trailing SL (runs every 10-min cycle):
 *     +1R â†’ move SL to entry (breakeven)
 *     +2R â†’ trail SL at current price Â± 1.5Ã—ATR
 */
public class CoinDCXFuturesTrader8C_BUY_SELL_NEW_LOGIC_THREE {

    // =========================================================================
    // Configuration
    // =========================================================================
    private static final String API_KEY    = System.getenv("DELTA_API_KEY");
    private static final String API_SECRET = System.getenv("DELTA_API_SECRET");
    private static final String BASE_URL       = "https://api.coindcx.com";
    private static final String PUBLIC_API_URL = "https://public.coindcx.com";

    private static final double MAX_MARGIN             = 1200.0;
    private static final int    LEVERAGE               = 20;
    private static final int    MAX_ENTRY_PRICE_CHECKS = 10;
    private static final int    ENTRY_CHECK_DELAY_MS   = 1000;
    private static final long   TICK_CACHE_TTL_MS      = 3_600_000L;
    private static final int MAX_CONCURRENT_TRADES = 5; // or your limit

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
private static final Map<String, Long> lastTradeTime = new ConcurrentHashMap<>();
private static final long TRADE_COOLDOWN_MS = 10 * 60 * 1000; // 10 minutes

    // H0a â€” Volatility gate: ATR as a % of price must stay in this range
    // Below ATR_MIN_PCT â†’ market is dead/flat (high SL hit risk on random noise)
    // Above ATR_MAX_PCT â†’ market is chaotic/spiking (SL too close to be safe)
    private static final double ATR_MIN_PCT = 0.003;  // 0.3% of price
    private static final double ATR_MAX_PCT = 0.05;   // 5.0% of price

    // H0b â€” Volume spike filter: current candle vol must exceed this multiple of avg
    private static final double VOLUME_SPIKE_MULT = 1.5;
    private static final int    VOLUME_AVG_BARS   = 20;

    // H4 â€” Pullback zone: max distance from EMA21 allowed (in ATR multiples)
    // LONG:  price must be in [EMA21, EMA21 + 1.5*ATR]
    // SHORT: price must be in [EMA21 - 1.5*ATR, EMA21]
    private static final double PULLBACK_ATR_BAND = 1.5;

    // H4 â€” Recent EMA21 touch: at least one of last N candles must have touched EMA21
    // For LONG: one of last N candle lows must be within this ATR distance of EMA21
    // For SHORT: one of last N candle highs must be within this ATR distance of EMA21
    private static final int    PULLBACK_TOUCH_BARS    = 3;
    private static final double PULLBACK_TOUCH_ATR_TOL = 1.5;

    // SL / TP parameters
    private static final double SL_SWING_BUFFER = 0.5;  // ATR buffer beyond swing low/high
    private static final double SL_MIN_ATR      = 4.5;  // minimum SL distance (Ã—ATR)
    private static final double SL_MAX_ATR      = 7.0;  // maximum SL distance (Ã—ATR)
    private static final double RR              = 2.2;  // TP = entry Â± RR Ã— risk

    // Trailing SL thresholds (unchanged â€” already optimal)
    private static final double TRAIL_BREAKEVEN_R = 1.0;  // +1R â†’ move SL to entry (breakeven)
    private static final double TRAIL_LOCK_R      = 2.0;  // +2R â†’ trail SL with ATR
    private static final double TRAIL_ATR_DIST    = 1.5;  // trailing distance = 1.5 Ã— ATR

    private static final int CANDLE_5M  = 60;   // 5m candles for S3 soft filter
    private static final int CANDLE_15M = 120;
    private static final int CANDLE_1H  = 70;
    private static final int CANDLE_4H  = 60;   // 60 Ã— 4H bars â‰ˆ 10 days

    private static final Map<String, JSONObject> instrumentCache = new ConcurrentHashMap<>();
    private static long lastCacheUpdate = 0;

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
        initInstrumentCache();

        // â”€â”€ PHASE 1: Trailing SL update for all active positions â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        System.out.println("\n=== PHASE 1: Trailing SL Update for Active Positions ===");
        List<JSONObject> activePositions = getActivePositionsFull();
        System.out.println("Active positions found: " + activePositions.size());
        if (activePositions.size() >= MAX_CONCURRENT_TRADES) {
    System.out.println("Max active trades reached â€” skipping new entries");
    return;
}

        for (JSONObject pos : activePositions) {
            try {
                updateTrailingStopLoss(pos);
            } catch (Exception e) {
                System.err.println("Trail SL error for " + pos.optString("pair") + ": " + e.getMessage());
            }
        }

        // â”€â”€ PHASE 2: Scan for new entry signals â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        System.out.println("\n=== PHASE 2: Scanning for New Entry Opportunities ===");
        Set<String> activeSet = new HashSet<>();
        for (JSONObject pos : activePositions) activeSet.add(pos.optString("pair"));
        System.out.println("Active pairs (will skip): " + activeSet);

for (String pair : COINS_TO_TRADE) {
    long now = System.currentTimeMillis();

    if (lastTradeTime.containsKey(pair)) {
        long lastTime = lastTradeTime.get(pair);
        if (now - lastTime < TRADE_COOLDOWN_MS) {
            System.out.println("Skip " + pair + " â€” cooldown active");
            continue;
        }
    }
            try {
                if (activeSet.contains(pair)) {
                    System.out.println("Skip " + pair + " â€” active position");
                    continue;
                }

                System.out.println("\n==== " + pair + " ====");

                // â”€â”€ Fetch candles â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
                JSONArray raw15m = getCandlestickData(pair, "15",  CANDLE_15M);
                JSONArray raw1h  = getCandlestickData(pair, "60",  CANDLE_1H);
                JSONArray raw4h  = getCandlestickData(pair, "240", CANDLE_4H);

                if (raw15m == null || raw15m.length() < 60) {
                    System.out.println("  Insufficient 15m candles (" +
                            (raw15m == null ? 0 : raw15m.length()) + ") â€” skip");
                    continue;
                }
                if (raw1h == null || raw1h.length() < EMA_MACRO) {
                    System.out.println("  Insufficient 1H candles (" +
                            (raw1h == null ? 0 : raw1h.length()) + ") â€” skip");
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

                // â”€â”€ H0a: Volatility Gate â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
                // ATR as a % of price must be in a healthy range.
                // Too low â†’ flat dead market (random noise kills SL).
                // Too high â†’ explosive spike (cannot place rational SL).
                double atrPct = (lastClose > 0) ? atr / lastClose : 0;
                boolean volatilityOk = atrPct >= ATR_MIN_PCT && atrPct <= ATR_MAX_PCT;
                System.out.printf("  [H0a] ATR%%=%.4f%% (range %.1f%%â€“%.1f%%) -> %s%n",
                        atrPct * 100, ATR_MIN_PCT * 100, ATR_MAX_PCT * 100,
                        volatilityOk ? "PASS" : "FAIL");
                if (!volatilityOk) {
                    if (atrPct < ATR_MIN_PCT)
                        System.out.println("  H0a FAIL â€” market too flat/quiet (ATR too low) â€” skip");
                    else
                        System.out.println("  H0a FAIL â€” market too volatile/spiking (ATR too high) â€” skip");
                    continue;
                }
                System.out.println("  H0a OK â€” volatility in healthy range");

                // â”€â”€ H0b: Volume Spike Filter â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
                // Current 15m candle volume must be > 1.5Ã— the average of the last 20 closed bars.
                // Rejects low-conviction moves that are likely to fade or reverse.
               double currentVol = vol15[vol15.length - 2]; // last CLOSED candle
double avgVol     = calcAvgVolume(vol15, VOLUME_AVG_BARS); // avg of CLOSED candles

                boolean volumeOk  = avgVol > 0 && currentVol > VOLUME_SPIKE_MULT * avgVol;
                System.out.printf("  [H0b] Volume: current=%.2f | avg(last%d)=%.2f | threshold=%.2f -> %s%n",
                        currentVol, VOLUME_AVG_BARS, avgVol, VOLUME_SPIKE_MULT * avgVol,
                        volumeOk ? "PASS" : "FAIL");
                if (!volumeOk) {
                    System.out.println("  H0b FAIL â€” volume spike not confirmed (low conviction) â€” skip");
                    continue;
                }
                System.out.println("  H0b OK â€” volume spike confirmed");

                // â”€â”€ H1: 4H Macro Trend â€” highest timeframe anchor â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
                // 4H EMA50 gives the longest-range bias. If price disagrees with 4H,
                // we are trading counter-trend on the dominant timeframe â€” skip.
                boolean macro4hUp   = false;
                boolean macro4hDown = false;
                if (raw4h != null && raw4h.length() >= EMA_MACRO) {
                    double[] cl4h    = extractCloses(raw4h);
                    double   ema4h50 = calcEMA(cl4h, EMA_MACRO);
                    macro4hUp   = lastClose > ema4h50;
                    macro4hDown = lastClose < ema4h50;
                    System.out.printf("  [H1] 4H EMA50=%.6f | Price %s EMA -> %s%n",
                            ema4h50, macro4hUp ? ">" : "<", macro4hUp ? "BULL" : "BEAR");
                    if (!macro4hUp && !macro4hDown) {
                        System.out.println("  H1 FAIL (4H) â€” skip");
                        continue;
                    }
                } else {
                    System.out.println("  [H1] 4H candles unavailable â€” skipping 4H filter");
                    macro4hUp   = true;  // allow both directions when 4H data is absent
                    macro4hDown = true;
                }

                // â”€â”€ H1b: 1H Macro Trend â€” secondary confirmation â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
                double  ema1h     = calcEMA(cl1h, EMA_MACRO);
                boolean macro1hUp   = lastClose > ema1h;
                boolean macro1hDown = lastClose < ema1h;
                System.out.printf("  [H1b] 1H EMA50=%.6f | Price %s EMA -> %s%n",
                        ema1h, macro1hUp ? ">" : "<", macro1hUp ? "BULL" : "BEAR");
                if (!macro1hUp && !macro1hDown) {
                    System.out.println("  H1b FAIL â€” skip");
                    continue;
                }

                // Both 4H and 1H must agree on direction
                //boolean macroUp   = (macro4hUp   || !macro4hDown) && macro1hUp;
                //boolean macroDown = (macro4hDown  || !macro4hUp)  && macro1hDown;                   boolean macroUp   = macro4hUp && macro1hUp;
		    boolean macroDown = macro4hDown && macro1hDown;

        if (!(macroUp || macroDown)) {
    System.out.println("  H1/H1b FAIL â€” 4H and 1H not aligned â€” skip");
    continue;
}
                System.out.println("  H1/H1b OK â€” 4H and 1H aligned: " + (macroUp ? "BULLISH" : "BEARISH"));

                // â”€â”€ H2: 15m 3-EMA Stack (EMA9 > EMA21 > EMA50) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
                double ema9     = calcEMA(cl15, EMA_FAST);
                double ema21    = calcEMA(cl15, EMA_MID);
                double ema50_15 = calcEMA(cl15, EMA_MACRO);

                boolean structBull = ema9 > ema21 && ema21 > ema50_15;
                boolean structBear = ema9 < ema21 && ema21 < ema50_15;

                System.out.printf("  [H2] 15m EMA9=%.6f EMA21=%.6f EMA50=%.6f -> %s%n",
                        ema9, ema21, ema50_15,
                        structBull ? "BULL STACK" : structBear ? "BEAR STACK" : "NO STACK");

                boolean trendUp   = macroUp   && structBull;
                boolean trendDown = macroDown && structBear;

                if (!trendUp && !trendDown) {
                    System.out.println("  H2 FAIL â€” 15m EMA structure not aligned with macro â€” skip");
                    continue;
                }
                System.out.println("  H2 OK â€” " + (trendUp ? "BULLISH" : "BEARISH") + " EMA stack confirmed");

                // â”€â”€ H2b: EMA21 Slope â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
                double[] cl15Prev  = Arrays.copyOfRange(cl15, 0, cl15.length - 1);
                double   ema21Prev = calcEMA(cl15Prev, EMA_MID);
                boolean  ema21Rising  = ema21 > ema21Prev;
                boolean  ema21Falling = ema21 < ema21Prev;

                System.out.printf("  [H2b] EMA21 slope: current=%.6f prev=%.6f -> %s%n",
                        ema21, ema21Prev,
                        ema21Rising ? "RISING" : ema21Falling ? "FALLING" : "FLAT");

                if (trendUp && !ema21Rising) {
                    System.out.println("  H2b FAIL â€” EMA21 not rising (flat/ranging market) â€” skip");
                    continue;
                }
                if (trendDown && !ema21Falling) {
                    System.out.println("  H2b FAIL â€” EMA21 not falling (flat/ranging market) â€” skip");
                    continue;
                }
                System.out.println("  H2b OK â€” EMA21 slope confirms trend direction");
                // â”€â”€ H2c: Micro Trend Strength (NEW) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
// Ensures recent candles are actually pushing in trend direction
boolean microTrendOk = true;

int checkBars = 3;
int bullishCount = 0;
int bearishCount = 0;

for (int i = cl15.length - checkBars; i < cl15.length; i++) {
    if (cl15[i] > op15[i]) bullishCount++;
    if (cl15[i] < op15[i]) bearishCount++;
}

if (trendUp) {
    microTrendOk = bullishCount >= 2;
} else {
    microTrendOk = bearishCount >= 2;
}

System.out.printf("  [H2c] Micro trend candles (last %d): bull=%d bear=%d -> %s%n",
        checkBars, bullishCount, bearishCount, microTrendOk ? "PASS" : "FAIL");

if (!microTrendOk) {
    System.out.println("  H2c FAIL â€” no recent momentum candles â€” skip");
    continue;
}
System.out.println("  H2c OK â€” micro trend confirmed");


                // â”€â”€ H3: MACD â€” Improved Histogram Logic â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
                //
                // Three conditions ALL required:
                //   (a) MACD line on correct side of signal line
                //   (b) Histogram has correct sign (positive=bull, negative=bear)
                //       â€” avoids entries where momentum is working against the trade
                //   (c) Histogram growing in absolute value for 2 consecutive bars
                //       â€” requires momentum to be accelerating, not just present
                //
                double[] mv      = calcMACD(cl15, MACD_FAST, MACD_SLOW, MACD_SIG);
                double[] mvPrev  = calcMACDPrev(cl15, MACD_FAST, MACD_SLOW, MACD_SIG);
                double[] mvPrev2 = calcMACDPrev2(cl15, MACD_FAST, MACD_SLOW, MACD_SIG);

                double macdLine     = mv[0],     macdSigV    = mv[1],     macdHist     = mv[2];
                double macdHistPrev = mvPrev[2],  macdHistPrev2 = mvPrev2[2];

                System.out.printf("  [H3] MACD=%.6f Sig=%.6f | Hist: now=%.6f prev=%.6f prev2=%.6f%n",
                        macdLine, macdSigV, macdHist, macdHistPrev, macdHistPrev2);

                // (a) MACD line alignment
                boolean macdLineOk = trendUp ? (macdLine > macdSigV) : (macdLine < macdSigV);
                // (b) Histogram sign: must be positive for LONG, negative for SHORT
                boolean histSignOk = trendUp ? (macdHist > 0) : (macdHist < 0);
                // (c) Histogram growing for 2 consecutive bars
                boolean histGrowing2 = Math.abs(macdHist) > Math.abs(macdHistPrev)
                        && Math.abs(macdHistPrev) > Math.abs(macdHistPrev2);

                System.out.printf("  [H3] LineOk=%s HistSign=%s HistGrowing2bars=%s%n",
                        macdLineOk, histSignOk, histGrowing2);

                if (!macdLineOk) {
                    System.out.println("  H3 FAIL â€” MACD line on wrong side of signal â€” skip");
                    continue;
                }
                if (!histSignOk) {
                    System.out.println("  H3 FAIL â€” MACD histogram wrong sign (momentum against trade) â€” skip");
                    continue;
                }
                if (!histGrowing2) {
                    System.out.println("  H3 FAIL â€” MACD histogram not accelerating for 2 bars â€” skip");
                    continue;
                }
                System.out.println("  H3 OK â€” MACD aligned, correct sign, accelerating momentum");

                // â”€â”€ H4: Pullback Zone â€” tightened with EMA21 touch check â”€â”€â”€â”€â”€â”€
                //
                // Part A (price zone): current price must be within 1.5Ã—ATR of EMA21
                // Part B (touch check): at least 1 of last 3 candles must have had
                //   its low (LONG) or high (SHORT) within 1.5Ã—ATR of EMA21.
                //   This confirms price actually pulled back TO EMA21, not just near it.
                //
                double  pullbackBand    = PULLBACK_ATR_BAND * atr;
                boolean inPullbackLong  = trendUp   && lastClose >= ema21 && lastClose <= (ema21 + pullbackBand);
                boolean inPullbackShort = trendDown && lastClose <= ema21 && lastClose >= (ema21 - pullbackBand);
                boolean inPullbackZone  = inPullbackLong || inPullbackShort;

                if (trendUp) {
                    System.out.printf("  [H4a] Long pullback zone: [%.6f , %.6f] | Price=%.6f -> %s%n",
                            ema21, ema21 + pullbackBand, lastClose, inPullbackLong ? "PASS" : "FAIL");
                } else {
                    System.out.printf("  [H4a] Short pullback zone: [%.6f , %.6f] | Price=%.6f -> %s%n",
                            ema21 - pullbackBand, ema21, lastClose, inPullbackShort ? "PASS" : "FAIL");
                }
                if (!inPullbackZone) {
                    System.out.println("  H4a FAIL â€” price not in pullback zone near EMA21 â€” skip");
                    continue;
                }

                // Part B: recent EMA21 touch check
                double touchTolerance = PULLBACK_TOUCH_ATR_TOL * atr;
                boolean recentTouch   = false;
                int     touchStart    = Math.max(0, lo15.length - 1 - PULLBACK_TOUCH_BARS);
                int     touchEnd      = lo15.length - 1;   // exclude current bar
                for (int i = touchStart; i < touchEnd; i++) {
                    if (trendUp) {
                        if (Math.abs(lo15[i] - ema21) <= touchTolerance) { recentTouch = true; break; }
                    } else {
                        if (Math.abs(hi15[i] - ema21) <= touchTolerance) { recentTouch = true; break; }
                    }
                }
                System.out.printf("  [H4b] EMA21 touch check (last %d bars, tol=%.6f) -> %s%n",
                        PULLBACK_TOUCH_BARS, touchTolerance, recentTouch ? "PASS" : "FAIL");
                if (!recentTouch) {
                    System.out.println("  H4b FAIL â€” no recent candle touched EMA21 (price just hovering) â€” skip");
                    continue;
                }
                System.out.println("  H4 OK â€” genuine pullback to EMA21 confirmed");

                // â”€â”€ SOFT FILTERS: at least 1 of 3 must pass â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
                double  rsi        = calcRSI(cl15, RSI_PERIOD);
                boolean rsiOkLong  = trendUp   && rsi >= RSI_LONG_MIN  && rsi <= RSI_LONG_MAX;
                boolean rsiOkShort = trendDown && rsi >= RSI_SHORT_MIN && rsi <= RSI_SHORT_MAX;
                boolean softRsi    = rsiOkLong || rsiOkShort;
                System.out.printf("  [S1] RSI=%.2f (Long:%.0f-%.0f | Short:%.0f-%.0f) -> %s%n",
                        rsi, RSI_LONG_MIN, RSI_LONG_MAX, RSI_SHORT_MIN, RSI_SHORT_MAX,
                        softRsi ? "PASS" : "fail");

                boolean prevBull   = prevClose > prevOpen;
                boolean prevBear   = prevClose < prevOpen;
                boolean softCandle = (trendUp && prevBull) || (trendDown && prevBear);
                System.out.printf("  [S2] Prev candle: open=%.6f close=%.6f -> %s -> %s%n",
                        prevOpen, prevClose,
                        prevBull ? "BULL" : prevBear ? "BEAR" : "DOJI",
                        softCandle ? "PASS" : "fail");

                // S3: Optional 5m EMA alignment
                boolean soft5m  = false;
                JSONArray raw5m = getCandlestickData(pair, "5", CANDLE_5M);
                if (raw5m != null && raw5m.length() >= EMA_MID) {
                    double[] cl5m     = extractCloses(raw5m);
                    double   ema9_5m  = calcEMA(cl5m, EMA_FAST);
                    double   ema21_5m = calcEMA(cl5m, EMA_MID);
                    soft5m = (trendUp && ema9_5m > ema21_5m) || (trendDown && ema9_5m < ema21_5m);
                    System.out.printf("  [S3] 5m EMA9=%.6f EMA21=%.6f -> %s%n",
                            ema9_5m, ema21_5m, soft5m ? "PASS" : "fail");
                } else {
                    System.out.println("  [S3] 5m candles unavailable â€” skipping soft filter");
                }

                if (!softRsi && !softCandle && !soft5m) {
                    System.out.println("  SOFT FAIL â€” no soft filter confirms (RSI / Candle / 5m EMA) â€” skip");
                    continue;
                }

                List<String> softPassed = new ArrayList<>();
                if (softRsi)    softPassed.add("RSI");
                if (softCandle) softPassed.add("Candle");
                if (soft5m)     softPassed.add("5m EMA");
                System.out.println("  SOFT OK â€” confirmed by: " + String.join(" + ", softPassed));

                // â”€â”€ All filters passed â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
                String side = trendUp ? "buy" : "sell";
                System.out.println("  >>> ALL FILTERS PASSED -> " + side.toUpperCase() + " " + pair);

                // â”€â”€ Place market order â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
                double currentPrice = getLastPrice(pair);
                if (currentPrice <= 0) {
                    System.out.println("  Invalid price â€” skip");
                    continue;
                }
// â”€â”€ Slippage Protection â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
double lastClosedPrice = cl15[cl15.length - 2]; // last CLOSED candle
double maxAllowedMove = atr * 1.2;

if (Math.abs(currentPrice - lastClosedPrice) > maxAllowedMove) {
    System.out.printf("  SLIPPAGE FAIL â€” price moved too far (%.6f vs %.6f) â€” skip%n",
            currentPrice, lastClosedPrice);
    continue;
}
 
                
                // â”€â”€ First calculate SL/TP using LAST CLOSE as temporary entry â”€â”€
// â”€â”€ STEP 1: Pre-calc SL using TEMP ENTRY (for qty only) â”€â”€
double tempEntry = lastClose;

double slPrice;

if ("buy".equalsIgnoreCase(side)) {
    double swLow = swingLow(lo15, SWING_BARS);

    double rawSL = swLow - Math.max(SL_SWING_BUFFER * atr, tickSize * 5);

    double minSL = tempEntry - SL_MIN_ATR * atr;
    double maxSL = tempEntry - SL_MAX_ATR * atr;

    slPrice = Math.max(Math.min(rawSL, minSL), maxSL);

} else {
    double swHigh = swingHigh(hi15, SWING_BARS);

    double rawSL = swHigh + Math.max(SL_SWING_BUFFER * atr, tickSize * 5);

    double minSL = tempEntry + SL_MIN_ATR * atr;
    double maxSL = tempEntry + SL_MAX_ATR * atr;

    slPrice = Math.min(Math.max(rawSL, minSL), maxSL);
}

// Round SL
slPrice = roundToTick(slPrice, tickSize);

// â”€â”€ STEP 2: Calculate quantity â”€â”€
double qty = calcQuantity(tempEntry, slPrice, pair);

if (qty <= 0) {
    System.out.println("  Invalid qty â€” skip");
    continue;
}

// â”€â”€ STEP 3: Place Order â”€â”€
System.out.printf("  Placing %s | entry=%.6f | qty=%.4f | lev=%dx%n",
        side.toUpperCase(), tempEntry, qty, LEVERAGE);

JSONObject resp = placeFuturesMarketOrder(
        side, pair, qty, LEVERAGE,
        "email_notification", "isolated", "INR"
);

if (resp == null || !resp.has("id")) {
    System.out.println("  Order failed: " + resp);
    continue;
}

System.out.println("  Order placed! id=" + resp.getString("id"));
lastTradeTime.put(pair, System.currentTimeMillis());

// â”€â”€ STEP 4: Get ACTUAL entry price â”€â”€
double entry = getEntryPrice(pair, resp.getString("id"));
if (entry <= 0) {
    System.out.println("  Could not confirm entry â€” TP/SL skipped");
    continue;
}

System.out.printf("  Entry confirmed: %.6f%n", entry);

// â”€â”€ STEP 5: FINAL SL/TP based on REAL entry â”€â”€
double tpPrice;

if ("buy".equalsIgnoreCase(side)) {
    double swLow = swingLow(lo15, SWING_BARS);
double rawSL = swLow - Math.max(SL_SWING_BUFFER * atr, tickSize * 5);
    double minSL = entry - SL_MIN_ATR * atr;
    double maxSL = entry - SL_MAX_ATR * atr;
    slPrice = Math.max(Math.min(rawSL, minSL), maxSL);

    double risk = entry - slPrice;
    tpPrice = entry + RR * risk;

} else {
    double swHigh = swingHigh(hi15, SWING_BARS);
double rawSL = swHigh + Math.max(SL_SWING_BUFFER * atr, tickSize * 5);
    double minSL = entry + SL_MIN_ATR * atr;
    double maxSL = entry + SL_MAX_ATR * atr;
    slPrice = Math.min(Math.max(rawSL, minSL), maxSL);

    double risk = slPrice - entry;
    tpPrice = entry - RR * risk;
}

// Round final values
// Recalculate TP based on actual entry (SL remains same)
double risk = Math.abs(entry - slPrice);

if ("buy".equalsIgnoreCase(side)) {
    tpPrice = entry + RR * risk;
} else {
    tpPrice = entry - RR * risk;
}

slPrice = roundToTick(slPrice, tickSize);
tpPrice = roundToTick(tpPrice, tickSize);

double reward = Math.abs(tpPrice - entry);

System.out.printf("  SL=%.6f | TP=%.6f | Risk=%.6f | Reward=%.6f | R:R=1:%.1f%n",
        slPrice, tpPrice, risk, reward, (risk > 0 ? reward / risk : 0));
                // â”€â”€ Set TP/SL on the position â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
                String posId = getPositionId(pair);
                if (posId != null) {
                    setTpSl(posId, tpPrice, slPrice, pair);
                } else {
                    System.out.println("  Position ID not found â€” TP/SL not set");
                }

            } catch (Exception e) {
                System.err.println("Error on " + pair + ": " + e.getMessage());
            }
        }
        System.out.println("\n=== Scan complete ===");
    }

    // =========================================================================
    // TRAILING STOP LOSS (unchanged â€” already optimal)
    // =========================================================================

    /**
     * Called every 10-min scan for each active position.
     *
     * Logic:
     *   profit >= +1R â†’ move SL to entry (breakeven)
     *   profit >= +2R â†’ trail SL at current price Â± 1.5Ã—ATR
     *
     * SL is only moved in the favourable direction, never worsened.
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

        System.out.printf("\n  [TRAIL] %s %s | entry=%.6f | currentSL=%.6f | currentTP=%.6f%n",
                isLong ? "LONG" : "SHORT", pair, entry, currentSL, currentTP);

        if (currentSL <= 0 || posId.isEmpty()) {
            System.out.println("  [TRAIL] No SL or position ID â€” skip");
            return;
        }

        double livePrice = getLastPrice(pair);
        if (livePrice <= 0) {
            System.out.println("  [TRAIL] Cannot get live price â€” skip");
            return;
        }

        JSONArray raw15m = getCandlestickData(pair, "15", 30);
        if (raw15m == null || raw15m.length() < ATR_PERIOD + 1) {
            System.out.println("  [TRAIL] Cannot get candles for ATR â€” skip");
            return;
        }
        double atr = calcATR(extractHighs(raw15m), extractLows(raw15m), extractCloses(raw15m), ATR_PERIOD);

  if (atr <= 0) {
    System.out.println("  [TRAIL] ATR invalid â€” skip");
    return;
}

        double initialRisk = isLong ? (entry - currentSL) : (currentSL - entry);
        if (initialRisk <= 0) initialRisk = SL_MIN_ATR * atr;

        double profit    = isLong ? (livePrice - entry) : (entry - livePrice);
        double rMultiple = (initialRisk > 0) ? profit / initialRisk : 0;

        System.out.printf("  [TRAIL] Live=%.6f | ATR=%.6f | InitRisk=%.6f | Profit=%.6f (%.2fR)%n",
                livePrice, atr, initialRisk, profit, rMultiple);

        double newSL = currentSL;

        if (rMultiple >= TRAIL_LOCK_R) {
            double trailSL  = isLong
                    ? livePrice - TRAIL_ATR_DIST * atr
                    : livePrice + TRAIL_ATR_DIST * atr;
            boolean improved = isLong ? (trailSL > currentSL) : (trailSL < currentSL);
            if (improved) {
                newSL = trailSL;
                System.out.printf("  [TRAIL] +%.2fR â†’ Trail SL: %.6f -> %.6f%n",
                        rMultiple, currentSL, newSL);
            } else {
                System.out.printf("  [TRAIL] +%.2fR â†’ Trail SL %.6f already better â€” no change%n",
                        rMultiple, currentSL);
                return;
            }

        } else if (rMultiple >= TRAIL_BREAKEVEN_R) {
            boolean needsMove = isLong ? (currentSL < entry) : (currentSL > entry);
            if (needsMove) {
                newSL = entry;
                System.out.printf("  [TRAIL] +%.2fR â†’ Move SL to breakeven: %.6f -> %.6f%n",
                        rMultiple, currentSL, newSL);
            } else {
                System.out.printf("  [TRAIL] +%.2fR â†’ SL already at/past breakeven â€” no change%n",
                        rMultiple);
                return;
            }

        } else {
            System.out.printf("  [TRAIL] Profit=%.2fR â€” need +%.1fR for breakeven â€” no action%n",
                    rMultiple, TRAIL_BREAKEVEN_R);
            return;
        }

        newSL = roundToTick(newSL, tickSize);

        if (Math.abs(newSL - currentSL) < tickSize * 0.5) {
            System.out.println("  [TRAIL] SL unchanged after rounding â€” skip API call");
            return;
        }

        if (currentTP > 0) {
            setTpSl(posId, currentTP, newSL, pair);
            System.out.printf("  [TRAIL] SL updated to %.6f (TP kept at %.6f)%n", newSL, currentTP);
        } else {
            System.out.println("  [TRAIL] No existing TP â€” skipping to avoid clearing TP");
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

    /** MACD computed on all bars except the last (1 bar back). */
    private static double[] calcMACDPrev(double[] cl, int fast, int slow, int sig) {
        if (cl.length < 2) return new double[]{0, 0, 0};
        return calcMACD(Arrays.copyOf(cl, cl.length - 1), fast, slow, sig);
    }

    /** MACD computed on all bars except the last two (2 bars back). */
    private static double[] calcMACDPrev2(double[] cl, int fast, int slow, int sig) {
        if (cl.length < 3) return new double[]{0, 0, 0};
        return calcMACD(Arrays.copyOf(cl, cl.length - 2), fast, slow, sig);
    }

    private static double calcRSI(double[] cl, int period) {
        if (cl.length < period + 1) return 50;
        double ag = 0, al = 0;
        for (int i = 1; i <= period; i++) {
            double ch = cl[i] - cl[i - 1];
            if (ch > 0) ag += ch;
            else al += Math.abs(ch);
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

    /**
     * Simple average of the last N closed bars volume (excludes the current live bar).
     */
    private static double calcAvgVolume(double[] volumes, int bars) {
        if (volumes.length < 2) return 0;
        int end   = volumes.length - 1;
        int start = Math.max(0, end - bars);
        int count = end - start;
        if (count <= 0) return 0;
        double sum = 0;
        for (int i = start; i < end; i++) sum += volumes[i];
        return sum / count;
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

    private static double[] extractVolumes(JSONArray a) {
        double[] o = new double[a.length()];
        for (int i = 0; i < a.length(); i++) o[i] = a.getJSONObject(i).optDouble("volume", 0);
        return o;
    }

    // =========================================================================
    // COINDCX API
    // =========================================================================

    private static JSONArray getCandlestickData(String pair, String resolution, int count) {
        try {
            long minsPerBar;
            switch (resolution) {
                case "1":   minsPerBar = 1;   break;
                case "5":   minsPerBar = 5;   break;
                case "15":  minsPerBar = 15;  break;
                case "60":  minsPerBar = 60;  break;
                case "240": minsPerBar = 240; break;
                default:    minsPerBar = 15;  break;
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
            int code = conn.getResponseCode();
            if (code == 200) {
                JSONObject r = new JSONObject(readStream(conn.getInputStream()));
                if ("ok".equals(r.optString("s"))) return r.getJSONArray("data");
                System.err.println("  Candle status=" + r.optString("s") + " for " + pair);
            } else {
                System.err.println("  Candle HTTP " + code + " for " + pair);
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
        body.put("margin_currency_short_name", new String[]{"INR", "USDT"});
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

private static double calcQuantity(double entry, double slPrice, String pair) {
    double stopDistance = Math.abs(entry - slPrice);
    if (stopDistance <= 0) return 0;

    // Adaptive risk: tighter SL â†’ higher size, wider SL â†’ lower size
    double riskPercent = 0.015; // base 1.5%
    if (stopDistance / entry > 0.02) {
        riskPercent = 0.01; // reduce risk in volatile pairs
    }

    double riskPerTrade = MAX_MARGIN * riskPercent;
    double qty = riskPerTrade / stopDistance;

    return Math.max(
            INTEGER_QTY_PAIRS.contains(pair)
                    ? Math.floor(qty)
                    : Math.floor(qty * 100) / 100,
            0
    );
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

    public static JSONObject placeFuturesMarketOrder(String side, String pair, double qty,
                                                     int lev, String notif,
                                                     String marginType, String marginCcy) {
        try {
            JSONObject order = new JSONObject();
            order.put("side",                       side.toLowerCase());
            order.put("pair",                       pair);
            order.put("order_type",                 "market_order");
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
            System.err.println("placeFuturesMarketOrder: " + e.getMessage());
            return null;
        }
    }

    public static void setTpSl(String posId, double tp, double sl, String pair) {
        try {
            double tick = getTickSize(pair);
            double rtp  = roundToTick(tp, tick);
            double rsl  = roundToTick(sl, tick);

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
            if (r.has("err_code_dcx")) {
                System.out.println("  TP/SL error: " + r);
            } else {
                System.out.printf("  TP/SL set: SL=%.6f  TP=%.6f%n", rsl, rtp);
            }
        } catch (Exception e) {
            System.err.println("setTpSl: " + e.getMessage());
        }
    }

    public static String getPositionId(String pair) {
        try {
            JSONObject p = findPosition(pair);
            return p != null ? p.getString("id") : null;
        } catch (Exception e) {
            System.err.println("getPositionId: " + e.getMessage());
            return null;
        }
    }

    private static List<JSONObject> getActivePositionsFull() {
        List<JSONObject> result = new ArrayList<>();
        try {
            JSONObject body = new JSONObject();
            body.put("timestamp", Instant.now().toEpochMilli());
            body.put("page", "1");
            body.put("size", "100");
            body.put("margin_currency_short_name", new JSONArray(Arrays.asList("INR", "USDT")));
            String resp = authPost(
                    BASE_URL + "/exchange/v1/derivatives/futures/positions", body.toString());
            JSONArray arr = resp.startsWith("[")
                    ? new JSONArray(resp)
                    : new JSONArray().put(new JSONObject(resp));
            System.out.println("=== Open Positions (" + arr.length() + ") ===");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject p   = arr.getJSONObject(i);
                String    pair = p.optString("pair", "");
                boolean isActive = p.optDouble("active_pos", 0) != 0
                        || p.optDouble("locked_margin", 0) > 0;
                if (isActive) {
                    System.out.printf("  %s | qty=%.4f | entry=%.6f | SL=%.4f | TP=%.4f%n",
                            pair,
                            p.optDouble("active_pos", 0),
                            p.optDouble("avg_price", 0),
                            p.optDouble("stop_loss_trigger", 0),
                            p.optDouble("take_profit_trigger", 0));
                    result.add(p);
                }
            }
        } catch (Exception e) {
            System.err.println("getActivePositionsFull: " + e.getMessage());
        }
        return result;
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
        throw new IOException("HTTP " + c.getResponseCode() + " â€” " + url);
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
        InputStream is = c.getResponseCode() >= 400
                ? c.getErrorStream()
                : c.getInputStream();
        return readStream(is);
    }

    private static String readStream(InputStream is) throws IOException {
        return new BufferedReader(new InputStreamReader(is))
                .lines().collect(Collectors.joining("\n"));
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
