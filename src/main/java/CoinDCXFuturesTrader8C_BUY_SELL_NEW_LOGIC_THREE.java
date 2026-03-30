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

public class CoinDCXFuturesTrader8C_BUY_SELL_NEW_LOGIC_THREE {

    // =========================================================================
    // Configuration
    // =========================================================================
    private static final String API_KEY    = System.getenv("DELTA_API_KEY");
    private static final String API_SECRET = System.getenv("DELTA_API_SECRET");
    private static final String BASE_URL       = "https://api.coindcx.com";
    private static final String PUBLIC_API_URL = "https://public.coindcx.com";

    private static final double MAX_MARGIN             = 4000.0;
    private static final int    LEVERAGE               = 5;
    private static final int    MAX_ENTRY_PRICE_CHECKS = 10;

    // BTC pair used as global market bias filter
    private static final String BTC_PAIR = "B-BTC_USDT";
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
    private static final double PULLBACK_ATR_BAND = 5.0;

    // SL / TP
    private static final double SL_SWING_BUFFER = 1.5;
    private static final double SL_MIN_ATR      = 6.0;   // raised 2→3: no SL inside ATR noise
    private static final double SL_MAX_ATR      = 9.0;   // raised 4→6: allow structural levels
    private static final double RR              = 2.0;


    // Volume filter — last COMPLETED candle volume must be VOL_MIN_RATIO x the 20-candle average
    private static final int    VOL_LOOKBACK  = 20;
    private static final double VOL_MIN_RATIO = 1.0;

    // Regime filter — ATR lookback to determine trending vs ranging
    private static final int REGIME_LOOKBACK = 20;

    // Log file
    private static final String LOG_FILE = "bot_log.txt";

    private static final int CANDLE_15M = 120;
    private static final int CANDLE_1H  = 70;

    private static final Map<String, JSONObject> instrumentCache = new ConcurrentHashMap<>();
    private static long lastCacheUpdate = 0;

    // State
    private static PrintWriter logWriter = null;

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
            .filter(s -> s.startsWith("1000") || s.startsWith("1MBABY") || s.startsWith("1000000"))
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

        // ── Get active positions (used to skip already-open pairs) ────────────
        List<JSONObject> activePositions = getActivePositionsFull();
        log("Active positions found: " + activePositions.size());

        // ── BTC Global Market Bias Filter ─────────────────────────────────────
        // Check BTC 1H trend before scanning any coin.
        // BULLISH → only LONG trades allowed
        // BEARISH → only SHORT trades allowed
        // NEUTRAL → no new trades placed
        String marketTrend = getBTCMarketTrend();
        log("=== BTC Market Bias: " + marketTrend + " ===");
        if (marketTrend.equals("NEUTRAL")) {
            log("Market is NEUTRAL — skipping all new entries today");
            closeLogger();
            return;
        }

        // ── Scan for new entry signals ────────────────────────────────────────
        log("=== Scanning for New Entry Opportunities ===");

        Set<String> activeSet = new HashSet<>();
        for (JSONObject pos : activePositions) activeSet.add(pos.optString("pair"));
        log("Active pairs (will skip): " + activeSet);

        for (String pair : COINS_TO_TRADE) {
            try {
                if (activeSet.contains(pair)) {
                    System.out.println("Skip " + pair + " — active position");
                    continue;
                }

                System.out.println("\n==== " + pair + " ====");

                // ── Fetch candles ─────────────────────────────────────────────
                JSONArray raw5m  = getCandlestickData(pair, "5",  30);
                JSONArray raw15m = getCandlestickData(pair, "15", CANDLE_15M);
                JSONArray raw1h  = getCandlestickData(pair, "60", CANDLE_1H);

                if (raw5m == null || raw5m.length() < EMA_MID + 1) {
                    System.out.println("  Insufficient 5m candles ("
                            + (raw5m == null ? 0 : raw5m.length()) + ") — skip");
                    continue;
                }
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

                double[] cl5m  = extractCloses(raw5m);
                double[] cl15  = extractCloses(raw15m);
                double[] op15  = extractOpens(raw15m);
                double[] hi15  = extractHighs(raw15m);
                double[] lo15  = extractLows(raw15m);
                double[] vol15 = extractVolumes(raw15m);
                double[] cl1h  = extractCloses(raw1h);
                double[] hi1h  = extractHighs(raw1h);
                double[] lo1h  = extractLows(raw1h);

                double lastClose = cl15[cl15.length - 1];
                double tickSize  = getTickSize(pair);
                double atr       = calcATR(hi15, lo15, cl15, ATR_PERIOD);
                double atr1h     = calcATR(hi1h, lo1h, cl1h, ATR_PERIOD);

                System.out.printf("  Price=%.6f  ATR15m=%.6f  ATR1h=%.6f  Tick=%.8f%n",
                        lastClose, atr, atr1h, tickSize);

                // ── 1H: Price vs EMA50 ───────────────────────────────────────
                double  ema1h50  = calcEMA(cl1h, EMA_MACRO);
                boolean h1Up     = lastClose > ema1h50;
                boolean h1Down   = lastClose < ema1h50;
                System.out.printf("  [1H]  EMA50=%.6f | Price %s EMA50 -> %s%n",
                        ema1h50, h1Up ? ">" : "<", h1Up ? "BULL" : "BEAR");

                // ── 15m: EMA9 vs EMA21 ───────────────────────────────────────
                double  ema15_9  = calcEMA(cl15, EMA_FAST);
                double  ema15_21 = calcEMA(cl15, EMA_MID);
                boolean tf15Up   = ema15_9 > ema15_21;
                boolean tf15Down = ema15_9 < ema15_21;
                System.out.printf("  [15m] EMA9=%.6f EMA21=%.6f -> %s%n",
                        ema15_9, ema15_21, tf15Up ? "UP" : "DOWN");

                // ── 5m: EMA9 vs EMA21 ────────────────────────────────────────
                double  ema5_9   = calcEMA(cl5m, EMA_FAST);
                double  ema5_21  = calcEMA(cl5m, EMA_MID);
                boolean tf5Up    = ema5_9 > ema5_21;
                boolean tf5Down  = ema5_9 < ema5_21;
                System.out.printf("  [5m]  EMA9=%.6f EMA21=%.6f -> %s%n",
                        ema5_9, ema5_21, tf5Up ? "UP" : "DOWN");

                // ── All 3 timeframes must align — otherwise skip ──────────────
                boolean trendUp   = h1Up   && tf15Up   && tf5Up;
                boolean trendDown = h1Down && tf15Down && tf5Down;

                if (!trendUp && !trendDown) {
                    System.out.println("  Timeframes not aligned — skip");
                    continue;
                }

                // ── BTC bias gate: only allow direction matching global trend ──
                if (marketTrend.equals("BULLISH") && !trendUp) {
                    System.out.println("  BTC is BULLISH — only LONG allowed, skip SHORT signal");
                    continue;
                }
                if (marketTrend.equals("BEARISH") && !trendDown) {
                    System.out.println("  BTC is BEARISH — only SHORT allowed, skip LONG signal");
                    continue;
                }

                // ── Trend strength filter (sideways market rejection) ─────────
                // trendStrength = |EMA9 - EMA21| / price
                // If too small, the EMAs are flat/crossing — likely sideways market
                double trendStrength = Math.abs(ema15_9 - ema15_21) / lastClose;
                double TREND_STR_MIN = 0.001; // 0.1% min separation
                System.out.printf("  [TREND STR] strength=%.5f (min=%.3f) -> %s%n",
                        trendStrength, TREND_STR_MIN,
                        trendStrength >= TREND_STR_MIN ? "TRENDING" : "SIDEWAYS");
                if (trendStrength < TREND_STR_MIN) {
                    System.out.println("  Market is sideways — skip");
                    continue;
                }

                // ── Volume confirmation (last completed candle > 1.5x avg) ────
                double lastVol = vol15.length > 1 ? vol15[vol15.length - 2] : 0;
                double avgVol  = 0;
                int    volCnt  = Math.min(VOL_LOOKBACK, vol15.length - 1);
                for (int v = vol15.length - 1 - volCnt; v < vol15.length - 1; v++) avgVol += vol15[v];
                if (volCnt > 0) avgVol /= volCnt;
                double volRatio = (avgVol > 0) ? lastVol / avgVol : 0;
                System.out.printf("  [VOL] lastVol=%.2f avgVol=%.2f ratio=%.2f (min=%.1f) -> %s%n",
                        lastVol, avgVol, volRatio, VOL_MIN_RATIO,
                        volRatio >= VOL_MIN_RATIO ? "OK" : "LOW");
                if (avgVol > 0 && volRatio < VOL_MIN_RATIO) {
                    System.out.println("  Volume too low — skip");
                    continue;
                }

                // ── Pullback zone: price within PULLBACK_ATR_BAND × ATR of EMA21
                double pullbackBand    = PULLBACK_ATR_BAND * atr;
                boolean inPullbackLong  = trendUp   && lastClose >= (ema15_21 - pullbackBand)
                                                    && lastClose <= (ema15_21 + pullbackBand);
                boolean inPullbackShort = trendDown && lastClose <= (ema15_21 + pullbackBand)
                                                    && lastClose >= (ema15_21 - pullbackBand);
                if (trendUp) {
                    System.out.printf("  [PULLBACK] Long: EMA21=%.6f ±%.6f | Price=%.6f -> %s%n",
                            ema15_21, pullbackBand, lastClose, inPullbackLong ? "PASS" : "FAIL");
                    if (!inPullbackLong) { System.out.println("  Not in pullback zone — skip"); continue; }
                } else {
                    System.out.printf("  [PULLBACK] Short: EMA21=%.6f ±%.6f | Price=%.6f -> %s%n",
                            ema15_21, pullbackBand, lastClose, inPullbackShort ? "PASS" : "FAIL");
                    if (!inPullbackShort) { System.out.println("  Not in pullback zone — skip"); continue; }
                }

                // ── Liquidity sweep detection (smart money edge) ──────────────
                // LONG:  recent candle low < swing low → price swept stops, then recovered
                // SHORT: recent candle high > swing high → price swept stops, then came back
                double swLowFull  = swingLow (lo15, Math.min(SWING_BARS, lo15.length));
                double swHighFull = swingHigh(hi15, Math.min(SWING_BARS, hi15.length));
                double recentLow  = lo15[lo15.length - 2];
                double recentHigh = hi15[hi15.length - 2];
                boolean sweepLong  = trendUp   && recentLow  < swLowFull  && lastClose > swLowFull;
                boolean sweepShort = trendDown && recentHigh > swHighFull && lastClose < swHighFull;
                System.out.printf("  [SWEEP] swLow=%.6f swHigh=%.6f | Long=%s Short=%s%n",
                        swLowFull, swHighFull,
                        sweepLong ? "YES (boost)" : "no",
                        sweepShort ? "YES (boost)" : "no");

                // ── Signal scoring ────────────────────────────────────────────
                // Passed so far: trend aligned (+3), volume OK (+2), pullback (+2)
                int score = 7;
                if (sweepLong || sweepShort) score += 2;     // liquidity sweep bonus
                // Strong momentum candle: last completed candle closes in trend direction
                double prevOpen  = op15[op15.length - 2];
                double prevClose = cl15[cl15.length - 2];
                boolean strongCandle = trendUp   ? (prevClose > prevOpen)   // bullish body
                                                 : (prevClose < prevOpen);  // bearish body
                if (strongCandle) score += 1;
                int SCORE_MIN = 7;  // minimum to trade
                System.out.printf("  [SCORE] %d | sweep=%s strongCandle=%s -> %s%n",
                        score, (sweepLong || sweepShort) ? "YES" : "no",
                        strongCandle ? "YES" : "no",
                        score >= SCORE_MIN ? "TRADE" : "SKIP");
                if (score < SCORE_MIN) { System.out.println("  Score too low — skip"); continue; }

                System.out.println("  SIGNAL OK — " + (trendUp ? "LONG" : "SHORT"));

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
                // SL PHILOSOPHY — place at the point where the trade thesis breaks:
                //   LONG: trend breaks when price falls below EMA21.
                //         emaSL  = EMA21 - 1.0*ATR  (below EMA21 with wick buffer)
                //         swSL   = swing_low - 0.5*ATR  (structural low)
                //         Use whichever is LOWER (further from entry = more protection).
                //         Clamp: must be at least 3 ATR and at most 6 ATR from entry.
                //
                //   SHORT: mirror — trend breaks when price rises above EMA21.
                //
                // TP: entry ± 3 × actual risk (3:1 R:R). One winner covers three losers.
                //
                double slPrice, tpPrice;
                // SL distances clamped using 1H ATR — much wider than 15m ATR,
                // avoids getting stopped by normal intra-bar noise on a fast chart.
                double slAtr = (atr1h > 0) ? atr1h : atr;  // fallback to 15m ATR if 1H unavailable
                if ("buy".equalsIgnoreCase(side)) {
                    double emaSL  = ema15_21 - 1.0 * atr;            // EMA21 structural SL (15m buffer)
                    double swLow  = swingLow(lo15, SWING_BARS);
                    double swSL   = swLow - SL_SWING_BUFFER * atr;   // swing structural SL
                    double rawSL  = Math.min(emaSL, swSL);            // take the lower (safer)
                    double minSL  = entry - SL_MIN_ATR * slAtr;       // not closer than 3 × 1H ATR
                    double maxSL  = entry - SL_MAX_ATR * slAtr;       // not further than 6 × 1H ATR
                    slPrice = Math.max(Math.min(rawSL, minSL), maxSL);
                    System.out.printf("  [SL] emaSL=%.6f swSL=%.6f minSL=%.6f → final=%.6f (1H_ATR=%.6f)%n",
                            emaSL, swSL, minSL, slPrice, slAtr);
                    double risk = entry - slPrice;
                    tpPrice = entry + RR * risk;
                } else {
                    double emaSL  = ema15_21 + 1.0 * atr;            // EMA21 structural SL (15m buffer)
                    double swHigh = swingHigh(hi15, SWING_BARS);
                    double swSL   = swHigh + SL_SWING_BUFFER * atr;  // swing structural SL
                    double rawSL  = Math.max(emaSL, swSL);            // take the higher (safer)
                    double minSL  = entry + SL_MIN_ATR * slAtr;       // not closer than 3 × 1H ATR
                    double maxSL  = entry + SL_MAX_ATR * slAtr;       // not further than 6 × 1H ATR
                    slPrice = Math.min(Math.max(rawSL, minSL), maxSL);
                    System.out.printf("  [SL] emaSL=%.6f swSL=%.6f minSL=%.6f → final=%.6f (1H_ATR=%.6f)%n",
                            emaSL, swSL, minSL, slPrice, slAtr);
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
                    activeSet.add(pair); // track new position for mid-scan cap check
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
    // VOLUME CONFIRMATION
    // =========================================================================

    /**
     * Extracts the volume field from each candlestick.
     * Volume is included in every CoinDCX candlestick API response bar.
     */
    private static double[] extractVolumes(JSONArray a) {
        double[] o = new double[a.length()];
        for (int i = 0; i < a.length(); i++) o[i] = a.getJSONObject(i).getDouble("volume");
        return o;
    }

    /**
     * Returns true if the most recent candle's volume is at least VOL_MIN_RATIO
     * times the average of the previous VOL_LOOKBACK candles.
     *
     * Filters out low-liquidity moves that reverse quickly.
     * VOL_MIN_RATIO=1.2 means current candle must have 20% above-average volume.
     */
    private static boolean isVolumeConfirmed(double[] volumes) {
        if (volumes.length < VOL_LOOKBACK + 2) return true;
        // Use the last COMPLETED candle (index -2) because the current candle
        // (index -1) is still forming and has partial volume — comparing it
        // to complete candles would almost always fail the ratio check.
        double completed = volumes[volumes.length - 2];
        double sum = 0;
        int start = volumes.length - 2 - VOL_LOOKBACK;
        for (int i = start; i < volumes.length - 2; i++) sum += volumes[i];
        double avg = sum / VOL_LOOKBACK;
        System.out.printf("  [VOL] LastCompleted=%.2f Avg=%.2f Ratio=%.2fx (need %.1fx)%n",
                completed, avg, avg > 0 ? completed / avg : 0, VOL_MIN_RATIO);
        return avg > 0 && completed >= avg * VOL_MIN_RATIO;
    }

    // =========================================================================
    // MARKET REGIME FILTER
    // =========================================================================

    /**
     * Returns true if the market is TRENDING (ATR expanding = volatility growing).
     * Returns false if market is RANGING/COMPRESSING (ATR shrinking).
     *
     * Method: current ATR vs average ATR of the last REGIME_LOOKBACK bars.
     * If currentATR > avgATR → volatility is expanding → trending conditions present.
     * If currentATR < avgATR → volatility is compressing → ranging, skip entry.
     *
     * This prevents EMA crossover entries in flat sideways markets where
     * false signals are most frequent.
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

        // Allow currentATR to be up to 15% below average (0.85x) before rejecting —
        // strict equality (currentATR > avgATR) blocks too many valid setups in choppy markets.
        boolean trending = currentATR > avgATR * 0.85;
        System.out.printf("  [REGIME] CurrentATR=%.6f AvgATR=%.6f (threshold=%.6f) -> %s%n",
                currentATR, avgATR, avgATR * 0.85, trending ? "TRENDING" : "RANGING");
        return trending;
    }

    // =========================================================================
    // BTC GLOBAL MARKET BIAS FILTER
    // =========================================================================

    /**
     * Fetches BTC 1H candles and returns the global market trend:
     *   "BULLISH"  → BTC price > EMA50 AND EMA9 > EMA21  (only LONG trades allowed)
     *   "BEARISH"  → BTC price < EMA50 AND EMA9 < EMA21  (only SHORT trades allowed)
     *   "NEUTRAL"  → mixed signals                        (no new trades placed)
     */
    private static String getBTCMarketTrend() {
        try {
            JSONArray raw = getCandlestickData(BTC_PAIR, "60", CANDLE_1H);
            if (raw == null || raw.length() < EMA_MACRO + 5) {
                log("[BTC BIAS] Insufficient BTC 1H candles — defaulting to NEUTRAL");
                return "NEUTRAL";
            }
            double[] closes = extractCloses(raw);
            double btcPrice = closes[closes.length - 1];
            double ema9     = calcEMA(closes, EMA_FAST);
            double ema21    = calcEMA(closes, EMA_MID);
            double ema50    = calcEMA(closes, EMA_MACRO);

            boolean bullish = btcPrice > ema50 && ema9 > ema21;
            boolean bearish = btcPrice < ema50 && ema9 < ema21;

            log(String.format("[BTC BIAS] price=%.2f EMA9=%.2f EMA21=%.2f EMA50=%.2f -> %s",
                    btcPrice, ema9, ema21, ema50,
                    bullish ? "BULLISH" : bearish ? "BEARISH" : "NEUTRAL"));

            if (bullish) return "BULLISH";
            if (bearish) return "BEARISH";
            return "NEUTRAL";
        } catch (Exception e) {
            log("[BTC BIAS] Error fetching BTC data: " + e.getMessage() + " — defaulting to NEUTRAL");
            return "NEUTRAL";
        }
    }

    // =========================================================================
    // LOGGING
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

    /** Writes a timestamped line to both console and bot_log.txt. */
    private static void log(String message) {
        String line = Instant.now() + " | " + message;
        System.out.println(line);
        if (logWriter != null) {
            logWriter.println(line);
            logWriter.flush();
        }
    }

    /** Call at the end of main() to flush and close the log file cleanly. */
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

    /** MACD on all candles except the last — used for histogram growing check. */
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

    /**
     * Returns the number of decimal places in a tick size.
     * e.g. tick=0.00001 → 5, tick=0.001 → 3, tick=0.5 → 1, tick=1.0 → 0
     * Used to format prices as strings with exactly the right precision,
     * avoiding floating-point artifacts like "0.27424000000000002".
     */
    private static int tickDecimals(double tick) {
        if (tick <= 0) return 8;
        return Math.max(0, (int) Math.round(-Math.log10(tick)));
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
        if (price <= 0) return 0;
        // MAX_MARGIN = fixed position SIZE in INR (regardless of leverage).
        // margin locked = MAX_MARGIN / LEVERAGE (e.g. 1200 INR size at 10x = 120 INR margin)
        double notionalUsdt = MAX_MARGIN / 93.0;
        double qty = notionalUsdt / price;
        if (INTEGER_QTY_PAIRS.contains(pair)) {
            return Math.max(Math.floor(qty), 0);
        } else {
            return Math.max(Math.floor(qty * 100.0) / 100.0, 0);
        }
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

    /**
     * Cancels all open (untriggered) SL/TP stop orders for the given position.
     *
     * The CoinDCX create_tpsl endpoint is a pure CREATE — if a SL or TP order already
     * exists it returns "SL already exists" / "TP already exists" and silently does nothing.
     * We must cancel first so the subsequent create_tpsl call actually takes effect.
     */
    private static void cancelPositionOrders(String posId) {
        try {
            JSONObject body = new JSONObject();
            body.put("timestamp", Instant.now().toEpochMilli());
            body.put("id", posId);
            String resp = authPost(
                    BASE_URL + "/exchange/v1/derivatives/futures/positions/cancel_all_open_orders_for_position",
                    body.toString());
            System.out.println("  [CANCEL-ORDERS] " + resp);
        } catch (Exception e) {
            System.err.println("cancelPositionOrders: " + e.getMessage());
        }
    }

    /**
     * Sets TP and SL on an existing position.
     *
     * Because create_tpsl is a CREATE (not upsert), existing stop orders are cancelled
     * first, then fresh ones are placed.
     */
    public static void setTpSl(String posId, double tp, double sl, String pair) {
        try {
            double tick = getTickSize(pair);
            double rsl  = roundToTick(sl, tick);

            // Step 1 — remove any existing SL/TP stop orders so create_tpsl will succeed.
            // create_tpsl is a pure CREATE; calling it again when orders already exist
            // returns "SL already exists" and silently does nothing.
            cancelPositionOrders(posId);

            // Wait 1 second to let the exchange process the cancel before re-creating.
            try { Thread.sleep(1000); } catch (InterruptedException ignored) {}

            // Step 2 — build the payload.
            // IMPORTANT: The CoinDCX API requires stop_price as a STRING, and the value
            // must be EXACTLY divisible by the tick size (e.g. "0.27424" not "0.27424000000000002").
            // We derive the decimal precision from the tick size and format accordingly.
            int dec = tickDecimals(tick);
            String fmtSl = String.format("%." + dec + "f", rsl);

            // stop_trigger_instruction: "last_price" forces the exchange to compare the
            // trigger against the LAST TRADED price, not the mark price. Without this,
            // a small premium/discount between last price and mark price can cause the
            // API to reject with "Trigger price should be less than the current price"
            // even when the SL is correctly below the last traded price.
            JSONObject slObj = new JSONObject();
            slObj.put("stop_price",              fmtSl);
            slObj.put("order_type",              "stop_market");
            slObj.put("stop_trigger_instruction","last_price");

            JSONObject payload = new JSONObject();
            payload.put("timestamp", String.valueOf(Instant.now().toEpochMilli()));
            payload.put("id",        posId);
            payload.put("stop_loss", slObj);

            if (tp > 0) {
                double rtp    = roundToTick(tp, tick);
                String fmtTp  = String.format("%." + dec + "f", rtp);
                JSONObject tpObj = new JSONObject();
                tpObj.put("stop_price",              fmtTp);
                tpObj.put("order_type",              "take_profit_market");
                tpObj.put("stop_trigger_instruction","last_price");
                payload.put("take_profit", tpObj);
                log("TP/SL setting: tick=" + tick + " dec=" + dec
                        + " SL=" + fmtSl + " TP=" + fmtTp);
            } else {
                log("TP/SL setting: tick=" + tick + " dec=" + dec
                        + " SL=" + fmtSl + " (no TP)");
            }

            // Step 3 — create the new orders and log the full response for debugging.
            String resp = authPost(
                    BASE_URL + "/exchange/v1/derivatives/futures/positions/create_tpsl",
                    payload.toString());
            log("create_tpsl response: " + resp);

            // Parse and flag any partial failures so they are visible in logs.
            try {
                JSONObject r = new JSONObject(resp);
                if (r.has("stop_loss")) {
                    JSONObject sl2 = r.getJSONObject("stop_loss");
                    if (sl2.optBoolean("success", true) == false || sl2.has("error")) {
                        log("[WARN] SL placement failed: " + sl2);
                    } else {
                        log("SL placed OK: status=" + sl2.optString("status")
                                + " stop_price=" + sl2.optDouble("stop_price"));
                    }
                }
                if (r.has("take_profit")) {
                    JSONObject tp2 = r.getJSONObject("take_profit");
                    if (tp2.optBoolean("success", true) == false || tp2.has("error")) {
                        log("[WARN] TP placement failed: " + tp2);
                    } else {
                        log("TP placed OK: status=" + tp2.optString("status")
                                + " stop_price=" + tp2.optDouble("stop_price"));
                    }
                }
            } catch (Exception parseEx) {
                log("[WARN] Could not parse create_tpsl response: " + parseEx.getMessage());
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

    // =========================================================================
    // LOW-LEVEL HTTP + HMAC
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
