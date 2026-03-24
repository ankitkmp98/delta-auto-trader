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
 * CoinDCX Futures Trader — V2 IMPROVED
 *
 * IMPROVEMENTS OVER PREVIOUS VERSION:
 *
 * 1. PULLBACK ENTRY FILTER (H4 — new hard filter):
 *    Price must be near EMA21 before entering, not at an extended top/bottom.
 *    Long:  EMA21 <= price <= EMA21 + 2*ATR
 *    Short: EMA21 - 2*ATR <= price <= EMA21
 *    This is the MAIN fix for reducing SL hits — stops chasing breakouts.
 *
 * 2. MACD HISTOGRAM GROWING CHECK (H3 improved):
 *    MACD histogram must be growing (momentum building, not fading).
 *    Prevents entering on reversing MACD signals.
 *
 * 3. WIDER SL (2.0–4.0x ATR instead of 1.5–3.0x ATR):
 *    More breathing room so market noise does not stop you out prematurely.
 *
 * 4. BETTER R:R RATIO (3:1 instead of 2:1):
 *    TP = 3 x risk. Profitable at only 26% win rate (was 34%).
 *    Winners now far outweigh losers even if SL hits more often.
 *
 * 5. SOFTWARE TRAILING STOP LOSS (runs every 10-min cycle):
 *    Before scanning new coins, all active positions are checked:
 *      +1R profit → SL moved to entry (breakeven — no loss possible)
 *      +2R profit → SL trailed at current price ± 1.5*ATR (locks profit)
 *    Uses the same create_tpsl API, just called again with the updated SL.
 *
 * ENTRY LOGIC (all HARD + at least 1 SOFT must pass):
 *   H1. 1H EMA50 macro trend (price above = bull, below = bear)
 *   H2. 15m EMA9 vs EMA21 aligned with macro
 *   H3. MACD aligned AND histogram growing
 *   H4. Price in pullback zone near EMA21 (within 2*ATR)
 *   S1. RSI in valid zone (Long: 40–68, Short: 32–60)
 *   S2. Previous closed candle matches direction
 *
 * EXIT LOGIC:
 *   Initial SL : structural swing ± 0.5*ATR, clamped to 2.0–4.0*ATR from entry
 *   Initial TP : entry ± 3.0 * risk (3:1 RR)
 *   Trailing SL: updated every 10-min run via create_tpsl API
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

    // H4 — Pullback zone: max distance from EMA21 allowed at entry (in ATR multiples)
    // Long:  price must be in [EMA21, EMA21 + PULLBACK_ATR_BAND*ATR]
    // Short: price must be in [EMA21 - PULLBACK_ATR_BAND*ATR, EMA21]
    private static final double PULLBACK_ATR_BAND = 2.0;

    // SL / TP parameters
    private static final double SL_SWING_BUFFER = 0.5;  // ATR buffer beyond swing low/high
    private static final double SL_MIN_ATR      = 2.0;  // minimum SL distance from entry
    private static final double SL_MAX_ATR      = 4.0;  // maximum SL distance from entry
    private static final double RR              = 3.0;  // TP = entry ± RR * risk

    // Trailing SL thresholds
    private static final double TRAIL_BREAKEVEN_R = 1.0;  // +1R → move SL to entry (breakeven)
    private static final double TRAIL_LOCK_R      = 2.0;  // +2R → trail SL with ATR
    private static final double TRAIL_ATR_DIST    = 1.5;  // trail distance = 1.5 * ATR

    private static final int CANDLE_15M = 120;
    private static final int CANDLE_1H  = 70;

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

        // ── PHASE 1: Trailing SL update for all active positions ──────────────
        // Always runs FIRST so that existing profits are locked before new trades open
        System.out.println("\n=== PHASE 1: Trailing SL Update for Active Positions ===");
        List<JSONObject> activePositions = getActivePositionsFull();
        System.out.println("Active positions found: " + activePositions.size());

        for (JSONObject pos : activePositions) {
            try {
                updateTrailingStopLoss(pos);
            } catch (Exception e) {
                System.err.println("Trail SL error for " + pos.optString("pair") + ": " + e.getMessage());
            }
        }

        // ── PHASE 2: Scan for new entry signals ───────────────────────────────
        System.out.println("\n=== PHASE 2: Scanning for New Entry Opportunities ===");
        Set<String> activeSet = new HashSet<>();
        for (JSONObject pos : activePositions) activeSet.add(pos.optString("pair"));
        System.out.println("Active pairs (will skip): " + activeSet);

        for (String pair : COINS_TO_TRADE) {
            try {
                if (activeSet.contains(pair)) {
                    System.out.println("Skip " + pair + " — active position");
                    continue;
                }

                System.out.println("\n==== " + pair + " ====");

                // ── Fetch candles ─────────────────────────────────────────────
                JSONArray raw15m = getCandlestickData(pair, "15", CANDLE_15M);
                JSONArray raw1h  = getCandlestickData(pair, "60", CANDLE_1H);

                if (raw15m == null || raw15m.length() < 60) {
                    System.out.println("  Insufficient 15m candles (" +
                            (raw15m == null ? 0 : raw15m.length()) + ") — skip");
                    continue;
                }
                if (raw1h == null || raw1h.length() < EMA_MACRO) {
                    System.out.println("  Insufficient 1H candles (" +
                            (raw1h == null ? 0 : raw1h.length()) + ") — skip");
                    continue;
                }

                double[] cl15 = extractCloses(raw15m);
                double[] op15 = extractOpens(raw15m);
                double[] hi15 = extractHighs(raw15m);
                double[] lo15 = extractLows(raw15m);
                double[] cl1h = extractCloses(raw1h);

                double lastClose = cl15[cl15.length - 1];
                double prevClose = cl15[cl15.length - 2];
                double prevOpen  = op15[op15.length - 2];
                double tickSize  = getTickSize(pair);
                double atr       = calcATR(hi15, lo15, cl15, ATR_PERIOD);

                System.out.printf("  Price=%.6f  ATR=%.6f  Tick=%.8f%n", lastClose, atr, tickSize);

                // ── H1: 1H Macro Trend ────────────────────────────────────────
                double  ema1h     = calcEMA(cl1h, EMA_MACRO);
                boolean macroUp   = lastClose > ema1h;
                boolean macroDown = lastClose < ema1h;
                System.out.printf("  [H1] 1H EMA50=%.6f | Price %s EMA -> %s%n",
                        ema1h, macroUp ? ">" : "<", macroUp ? "BULL" : "BEAR");
                if (!macroUp && !macroDown) {
                    System.out.println("  H1 FAIL — skip");
                    continue;
                }

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

                if (trendUp   && !macdBull) {
                    System.out.println("  H3 FAIL — MACD bearish — skip");
                    continue;
                }
                if (trendDown && !macdBear) {
                    System.out.println("  H3 FAIL — MACD bullish — skip");
                    continue;
                }
                if (!histGrowing) {
                    System.out.println("  H3 FAIL — MACD histogram shrinking (momentum fading) — skip");
                    continue;
                }
                System.out.println("  H3 OK — MACD aligned + momentum growing");

                // ── H4: Pullback Zone (KEY NEW FILTER) ───────────────────────
                // Only enter when price has pulled back near EMA21.
                // Prevents buying at the top or selling at the bottom of a move.
                //
                // Long:  EMA21  <=  price  <=  EMA21 + 2*ATR
                // Short: EMA21 - 2*ATR  <=  price  <=  EMA21
                double pullbackBand     = PULLBACK_ATR_BAND * atr;
                boolean inPullbackLong  = trendUp   && lastClose >= ema21 && lastClose <= (ema21 + pullbackBand);
                boolean inPullbackShort = trendDown && lastClose <= ema21 && lastClose >= (ema21 - pullbackBand);
                boolean inPullbackZone  = inPullbackLong || inPullbackShort;

                if (trendUp) {
                    System.out.printf("  [H4] Long pullback zone: [%.6f , %.6f] | Price=%.6f -> %s%n",
                            ema21, ema21 + pullbackBand, lastClose, inPullbackLong ? "PASS" : "FAIL");
                } else {
                    System.out.printf("  [H4] Short pullback zone: [%.6f , %.6f] | Price=%.6f -> %s%n",
                            ema21 - pullbackBand, ema21, lastClose, inPullbackShort ? "PASS" : "FAIL");
                }
                if (!inPullbackZone) {
                    System.out.println("  H4 FAIL — price not in pullback zone (too extended from EMA21) — skip");
                    continue;
                }
                System.out.println("  H4 OK — price in pullback zone near EMA21");

                // ── SOFT FILTERS: at least 1 of 2 must pass ──────────────────
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

                if (!softRsi && !softCandle) {
                    System.out.println("  SOFT FAIL — neither RSI nor candle confirms — skip");
                    continue;
                }
                System.out.println("  SOFT OK — " + (softRsi ? "RSI" : "") +
                        (softRsi && softCandle ? " + " : "") +
                        (softCandle ? "Candle" : "") + " confirmed");

                // ── All filters passed ────────────────────────────────────────
                String side = trendUp ? "buy" : "sell";
                System.out.println("  >>> ALL FILTERS PASSED -> " + side.toUpperCase() + " " + pair);

                // ── Place market order ────────────────────────────────────────
                double currentPrice = getLastPrice(pair);
                if (currentPrice <= 0) {
                    System.out.println("  Invalid price — skip");
                    continue;
                }

                double qty = calcQuantity(currentPrice, pair);
                if (qty <= 0) {
                    System.out.println("  Invalid qty — skip");
                    continue;
                }

                System.out.printf("  Placing %s | price=%.6f | qty=%.4f | lev=%dx%n",
                        side.toUpperCase(), currentPrice, qty, LEVERAGE);

                JSONObject resp = placeFuturesMarketOrder(side, pair, qty, LEVERAGE,
                        "email_notification", "isolated", "INR");

                if (resp == null || !resp.has("id")) {
                    System.out.println("  Order failed: " + resp);
                    continue;
                }
                System.out.println("  Order placed! id=" + resp.getString("id"));

                // ── Confirm actual fill price ─────────────────────────────────
                double entry = getEntryPrice(pair, resp.getString("id"));
                if (entry <= 0) {
                    System.out.println("  Could not confirm entry — TP/SL skipped");
                    continue;
                }
                System.out.printf("  Entry confirmed: %.6f%n", entry);

                // ── Calculate SL and TP ───────────────────────────────────────
                //
                // LONG:
                //   rawSL = swing low - 0.5*ATR        (structural SL just below key level)
                //   minSL = entry - 2.0*ATR             (never CLOSER than this — breathing room)
                //   maxSL = entry - 4.0*ATR             (never FARTHER than this — risk cap)
                //   final = clamp(rawSL, maxSL, minSL)
                //   TP    = entry + 3.0 * (entry - final SL)
                //
                // SHORT: exact mirror
                //
                double slPrice, tpPrice;
                if ("buy".equalsIgnoreCase(side)) {
                    double swLow = swingLow(lo15, SWING_BARS);
                    double rawSL = swLow  - SL_SWING_BUFFER * atr;
                    double minSL = entry  - SL_MIN_ATR * atr;   // can't be closer than this
                    double maxSL = entry  - SL_MAX_ATR * atr;   // can't be farther than this
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
                System.out.printf("  SL=%.6f | TP=%.6f | Risk=%.6f | Reward=%.6f | R:R=1:%.1f%n",
                        slPrice, tpPrice, risk, reward, (risk > 0 ? reward / risk : 0));

                // ── Set TP/SL on the position ─────────────────────────────────
                String posId = getPositionId(pair);
                if (posId != null) {
                    setTpSl(posId, tpPrice, slPrice, pair);
                } else {
                    System.out.println("  Position ID not found — TP/SL not set");
                }

            } catch (Exception e) {
                System.err.println("Error on " + pair + ": " + e.getMessage());
            }
        }
        System.out.println("\n=== Scan complete ===");
    }

    // =========================================================================
    // TRAILING STOP LOSS
    // =========================================================================

    /**
     * Called every 10-min scan for each active position.
     *
     * Logic:
     *   profit >= +1R (TRAIL_BREAKEVEN_R) → move SL to entry price (guaranteed breakeven)
     *   profit >= +2R (TRAIL_LOCK_R)      → trail SL at current price ± 1.5*ATR
     *
     * SL is only moved in the favourable direction, never worsened.
     * The existing TP is kept unchanged; only SL is updated via create_tpsl API.
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
            System.out.println("  [TRAIL] No SL or position ID — skip");
            return;
        }

        // Get live price
        double livePrice = getLastPrice(pair);
        if (livePrice <= 0) {
            System.out.println("  [TRAIL] Cannot get live price — skip");
            return;
        }

        // Get current ATR from recent 15m candles
        JSONArray raw15m = getCandlestickData(pair, "15", 30);
        if (raw15m == null || raw15m.length() < ATR_PERIOD + 1) {
            System.out.println("  [TRAIL] Cannot get candles for ATR — skip");
            return;
        }
        double atr = calcATR(extractHighs(raw15m), extractLows(raw15m), extractCloses(raw15m), ATR_PERIOD);
        if (atr <= 0) {
            System.out.println("  [TRAIL] ATR is 0 — skip");
            return;
        }

        // Initial risk: distance from entry to original SL
        double initialRisk = isLong ? (entry - currentSL) : (currentSL - entry);
        if (initialRisk <= 0) {
            // SL already at or past breakeven — use ATR-based fallback for further trailing
            initialRisk = SL_MIN_ATR * atr;
        }

        double profit    = isLong ? (livePrice - entry) : (entry - livePrice);
        double rMultiple = (initialRisk > 0) ? profit / initialRisk : 0;

        System.out.printf("  [TRAIL] Live=%.6f | ATR=%.6f | InitRisk=%.6f | Profit=%.6f (%.2fR)%n",
                livePrice, atr, initialRisk, profit, rMultiple);

        double newSL = currentSL;

        if (rMultiple >= TRAIL_LOCK_R) {
            // +2R or better: trail SL at live price ± 1.5*ATR
            double trailSL = isLong
                    ? livePrice - TRAIL_ATR_DIST * atr
                    : livePrice + TRAIL_ATR_DIST * atr;

            boolean improved = isLong ? (trailSL > currentSL) : (trailSL < currentSL);
            if (improved) {
                newSL = trailSL;
                System.out.printf("  [TRAIL] +%.2fR → Trail SL: %.6f -> %.6f%n",
                        rMultiple, currentSL, newSL);
            } else {
                System.out.printf("  [TRAIL] +%.2fR → Trail SL %.6f already better than %.6f — no change%n",
                        rMultiple, currentSL, trailSL);
                return;
            }

        } else if (rMultiple >= TRAIL_BREAKEVEN_R) {
            // +1R: move SL to entry (breakeven — no loss possible on this trade)
            boolean needsMove = isLong ? (currentSL < entry) : (currentSL > entry);
            if (needsMove) {
                newSL = entry;
                System.out.printf("  [TRAIL] +%.2fR → Move SL to breakeven: %.6f -> %.6f%n",
                        rMultiple, currentSL, newSL);
            } else {
                System.out.printf("  [TRAIL] +%.2fR → SL already at or past breakeven — no change%n",
                        rMultiple);
                return;
            }

        } else {
            System.out.printf("  [TRAIL] Profit=%.2fR — need +%.1fR for breakeven — no action%n",
                    rMultiple, TRAIL_BREAKEVEN_R);
            return;
        }

        newSL = roundToTick(newSL, tickSize);

        // Skip API call if SL is unchanged after tick rounding
        if (Math.abs(newSL - currentSL) < tickSize * 0.5) {
            System.out.println("  [TRAIL] SL unchanged after rounding — skip API call");
            return;
        }

        // Update SL via API — keep existing TP, only change SL
        if (currentTP > 0) {
            setTpSl(posId, currentTP, newSL, pair);
            System.out.printf("  [TRAIL] SL updated to %.6f (TP kept at %.6f)%n", newSL, currentTP);
        } else {
            System.out.println("  [TRAIL] No existing TP value — skipping API call to avoid clearing TP");
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

    /**
     * Returns MACD computed on all candles EXCEPT the last one.
     * Used to compare the histogram bar-over-bar (growing vs shrinking).
     */
    private static double[] calcMACDPrev(double[] cl, int fast, int slow, int sig) {
        if (cl.length < 2) return new double[]{0, 0, 0};
        double[] clPrev = Arrays.copyOf(cl, cl.length - 1);
        return calcMACD(clPrev, fast, slow, sig);
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
     * Sets or updates TP and SL on an existing position.
     * Calling this again OVERWRITES the previous TP/SL values.
     * Used both at entry and by the trailing SL logic every cycle.
     */
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
    } **...**

_This response is too long to display in full._
