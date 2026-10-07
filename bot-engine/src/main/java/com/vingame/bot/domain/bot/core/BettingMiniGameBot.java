package com.vingame.bot.domain.bot.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.NamedType;
import com.vingame.bot.config.bot.BotBehaviorConfig;
import com.vingame.bot.domain.bot.coordination.BetCoordinator;
import com.vingame.bot.domain.bot.coordination.ReservationOutcome;
import com.vingame.bot.domain.bot.message.request.GameRequest;
import com.vingame.bot.domain.bot.message.request.GameRequestFactory;
import com.vingame.bot.domain.bot.message.request.Request;
import com.vingame.bot.domain.bot.strategy.BetContext;
import com.vingame.bot.domain.bot.strategy.BetDecision;
import com.vingame.bot.domain.bot.strategy.BettingStrategy;
import com.vingame.bot.domain.bot.strategy.BettingStrategyFactory;
import com.vingame.bot.domain.bot.strategy.BotMemory;
import com.vingame.bot.domain.bot.strategy.RoundResult;
import com.vingame.bot.domain.bot.strategy.StrategyId;
import com.vingame.bot.domain.bot.util.BettingMiniGameState;
import com.vingame.bot.domain.bot.util.GameState;
import com.vingame.bot.domain.bot.util.OutputPrinter;
import com.vingame.bot.domain.bot.util.SessionIdStore;
import com.vingame.bot.domain.bot.message.EndGameMessage;
import com.vingame.bot.domain.bot.message.GameMessageTypes;
import com.vingame.bot.domain.bot.message.HasBetTotals;
import com.vingame.bot.domain.bot.message.HasBotWinnings;
import com.vingame.bot.domain.bot.message.HasCrowdBets;
import com.vingame.bot.domain.bot.message.HasJackpot;
import com.vingame.bot.domain.bot.message.HasJackpotPool;
import com.vingame.bot.domain.bot.message.StartGameMd5Message;
import com.vingame.bot.domain.bot.message.StartGameMessage;
import com.vingame.bot.domain.bot.message.SubscribeMessage;
import com.vingame.bot.domain.bot.message.UpdateBetMessage;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.infrastructure.observability.BettingSessionStrategy;
import com.vingame.bot.infrastructure.observability.SessionAggregationStrategy;
import com.vingame.websocketparser.ObjectMapperProvider;
import com.vingame.websocketparser.VingameWebSocketClient;
import com.vingame.websocketparser.message.request.ActionRequestMessage;
import com.vingame.websocketparser.message.response.ActionResponseMessage;
import com.vingame.websocketparser.scenario.PipelineContext;
import com.vingame.websocketparser.scenario.Scenario;
import com.vingame.websocketparser.scenario.processors.SentMessageContext;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.vingame.websocketparser.message.properties.MessageType.RECEIVED;
import static com.vingame.websocketparser.scenario.Scenario.pipeline;
import static com.vingame.websocketparser.scenario.matchers.Qualifier.cmd;
import static com.vingame.websocketparser.scenario.matchers.Qualifier.typeOf;
import static com.vingame.websocketparser.scenario.processors.OutboundMessage.buildMessage;
import static com.vingame.websocketparser.scenario.processors.SendMode.INFINITE;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

@Slf4j
public class BettingMiniGameBot extends Bot {

    // JACKPOT_SCALE_AND_RAMP (AD-R3): accept probability at window-open. A small
    // non-zero floor so the early window is not dead (some early bettors are
    // realistic). Held as a class constant, not a per-group field (AD-R3).
    private static final double RAMP_P_MIN = 0.15;

    // RIK_114_BETTING_MINI AD-33: the per-bet commit CODE (offset + 3022 on stock =
    // 13022). Print-filter only — this integer is added to OutputPrinter's cmd list in
    // onStart so a TRACE window shows our outbound commit AND whatever the server
    // answers to it; nothing deserializes this CODE (AD-7 stands: no inbound model).
    // The frame itself is built by GameRequest.commit, not from this constant.
    private static final int COMMIT_CODE = 3022;

    // Game configuration
    @Setter
    private GameMessageTypes messageTypes;

    private GameRequest request;

    // Game state
    @Getter
    private SessionIdStore sidStore;
    // The BettingMini game offset (CMD = CODE + offset). Read only by the default
    // CMD-derivation seams (subscribeCmd/updateBetCmd/startGameCmd/endGameCmd/
    // messageTypeRegistrations/buildRequest). A fixed-CMD subclass (e.g. Tai Xiu)
    // overrides those seams and never reads this field.
    protected int offset;

    private long blockBetTime = 3_000L;
    private volatile GameState gameState;
    private long timeForBetting = 0L;
    private final AtomicLong remainingTime = new AtomicLong(0L);

    private ScheduledExecutorService scheduler;

    // Watchdog — fires if no game messages arrive within the configured timeout
    private ScheduledExecutorService watchdogScheduler;
    private volatile ScheduledFuture<?> watchdogTask;
    private long watchdogTimeoutMillis;

    // Betting state — per-bot RNG, owned by the bot and threaded into BetContext
    // on every tick. The strategy never holds its own RNG (see Decision 13 and
    // BettingStrategyFactory javadoc); the bot owns lifecycle and seeding.
    private Random rng = new Random();

    // JACKPOT_SCALE_AND_RAMP (AD-J4): the volume scale factor for the current round,
    // snapshotted once at onStartGame from the group-scoped jackpotScaler. Read on
    // the scenario thread when building the BetContext's effectiveMaxBetsPerRound;
    // written on the netty thread at StartGame. Volatile so the scenario thread sees
    // the round's factor. 1.0 (neutral) when jackpotScaler is null (feature off).
    private volatile double currentJackpotFactor = 1.0;

    // Factual rolling history (Phase 2 of BETTING_STRATEGIES). Populated from
    // incoming WS messages in onStartGame/onEndGame and from outbound bets in
    // bet(). Read by the strategy via BetContext on every tick.
    @Getter
    private BotMemory memory;

    // Strategy factory (injected by BotFactory) used at initializeSubclass to
    // build the per-bot BettingStrategy instance for this.strategyId.
    @Setter
    private BettingStrategyFactory strategyFactory;

    // Per-bot strategy instance (built in initializeSubclass via strategyFactory).
    // One instance per bot per restart; state-carrying (Decision 1, 10).
    @Getter
    private BettingStrategy strategy;

    // Pre-computed decision shared between the sendAsync condition and the
    // supplier. The scenario engine throws if the supplier returns null
    // (SendAsync.processInternal:135), so the condition computes decide(ctx)
    // and parks the Optional here; the supplier reads it back to build the bet.
    // See BETTING_STRATEGIES.md Implementation Note 1.
    private final AtomicReference<Optional<BetDecision>> pendingDecision =
            new AtomicReference<>(Optional.empty());

    // RIK_114_BETTING_MINI AD-32: the commit frame parked by the bet supplier and popped
    // by afterBetSent (the sendAsync onSent callback) immediately after client.send(bet)
    // returns, on the same thread. Optional.empty() for every product but 114 stock,
    // so the callback is a no-op there. Written and consumed inside one runnable on one
    // thread — the only external clear is beforeReconnect, for symmetry with
    // pendingDecision; there is deliberately no onEndGame clear (it cannot survive to
    // a round boundary, and a clear there would suggest it can).
    private final AtomicReference<Optional<ActionRequestMessage>> pendingCommit =
            new AtomicReference<>(Optional.empty());

    // Latched by afterBetSent the first time a commit fails to serialize. That failure is
    // a deterministic property of the class and the mapper, so it recurs on EVERY bet at
    // the 1 s bet interval: one WARN per bot is the signal, the rest is DEBUG (CLAUDE.md
    // tiering rule — nothing at WARN+ whose rate is a function of bot count or bet rate).
    private final AtomicBoolean commitFailureWarned = new AtomicBoolean(false);

    // Visible for testing — allows deterministic randomness in unit tests by
    // injecting a mocked or seeded Random. Preserves the legacy test seam used
    // by BettingMiniGameBotTest / BettingMiniGameBotTipDispatchTest.
    void setRandom(Random random) {
        this.rng = random;
    }

    public BettingMiniGameBot() {
        super();
    }

    @Override
    protected void initializeSubclass() {
        Game game = configuration.getGame();
        // Null-safe unbox: BettingMini games always carry a non-null offset, so
        // this is value-identical for them. A fixed-CMD game type (Tai Xiu, AD-9)
        // leaves offset null by design and overrides every CMD seam, so the 0
        // fallback is a never-read dead store rather than a behavioral change.
        // Eagerly unboxing game.getOffset() here NPEs for the null-offset case
        // (staging: a Tai Xiu group created 0 bots — every createBot NPE'd here).
        Integer gameOffset = game.getOffset();
        this.offset = gameOffset != null ? gameOffset : 0;
        this.sidStore = new SessionIdStore(0L);

        this.request = buildRequest(game);

        // BotMemory is built after `game` is set so its captured Game reference is
        // non-null. Capacity defaults to BotMemory.DEFAULT_CAPACITY (50, per
        // BETTING_STRATEGIES Architecture Decision 3 — hardcoded for v1).
        this.memory = new BotMemory(game);

        // Phase 5: seed the per-bot RNG. Decision 13 — deterministic-ish per
        // user (hash of userName) but distinct per process (XOR with nanoTime)
        // so two restarts of the same bot don't replay an identical sequence.
        // Test fixtures override this via setRandom().
        this.rng = new Random(((long) getUserName().hashCode()) ^ System.nanoTime());

        // Phase 5: build the per-bot strategy via the factory. strategyId is
        // populated by BotGroupBehaviorService.createSingleBot via the
        // fill-to-target assignment over BotGroup.strategyMix; legacy/test
        // fixtures that bypass the assignment default to RANDOM (Decision 7).
        // The default is spelled StrategyId.RANDOM.name() rather than "RANDOM":
        // the enum survives as the compile-time catalogue of the built-in keys
        // (PLUGIN_HOT_RELOAD AD-12), so reading the name off it keeps the
        // default tied to the catalogue instead of to a loose literal.
        String effectiveId = strategyId != null ? strategyId : StrategyId.RANDOM.name();
        // No fallback to a concrete strategy (PLUGIN_HOT_RELOAD_3_4 D-4). There used
        // to be a test-seam `new RandomBehaviorStrategy()` here, which tied the engine
        // to a plugin class. BotFactory always wires the factory; a fixture that does
        // not must wire one itself (TestStrategyFactories in bot-engine's tests).
        if (strategyFactory == null) {
            throw new IllegalStateException(
                    "BettingStrategyFactory not wired — BotFactory always sets it");
        }
        this.strategy = strategyFactory.create(effectiveId);

        this.watchdogTimeoutMillis = configuration.getWatchdogTimeoutSeconds() * 1000L;
        this.watchdogScheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("watchdog-" + getUserName()).factory()
        );

        // LOG_VOLUME_TIERING tier 1: DEBUG per bot, INFO once per group. This line's
        // rate is a direct function of bot count (30k bots => 30k lines at group
        // start), which INFO may no longer carry; the group-level count, game and
        // strategy come out of GroupLifecycleAggregator as a single line instead.
        log.debug("BettingMiniGameBot initialized: game={}, offset={}, options={}, md5={}, watchdog={}s, strategy={}",
                game.getName(), offset, game.getEffectiveOptionAffinities().size(), game.isMd5(),
                configuration.getWatchdogTimeoutSeconds(), effectiveId);
        if (groupLifecycleAggregator != null) {
            groupLifecycleAggregator.recordInitialized(
                    "game=" + game.getName() + ", type=" + game.getGameType()
                            + ", strategy=" + effectiveId);
        }
    }

    // ----------------------------------------------------------------------
    // CMD-derivation seams (AD-2 of TAI_XIU_BOT.md).
    //
    // BettingMini derives every CMD as CODE + offset and registers polymorphic
    // subtypes against those summed CMDs. The default implementations below
    // reproduce that scheme byte-for-byte. A fixed-CMD game type (e.g. Tai Xiu)
    // overrides these to return literal CMD constants and a no-offset
    // registration set, without touching any inherited handler logic.
    // ----------------------------------------------------------------------

    /** @return the inbound subscribe CMD (default: SUBSCRIBE_CODE + offset). */
    protected int subscribeCmd() {
        return GameMessageTypes.SUBSCRIBE_CODE + offset;
    }

    /** @return the updateBet CMD (default: UPDATE_BET_CODE + offset). */
    protected int updateBetCmd() {
        return GameMessageTypes.UPDATE_BET_CODE + offset;
    }

    /** @return the inbound startGame CMD (default: START_GAME_CODE + offset). */
    protected int startGameCmd() {
        return GameMessageTypes.START_GAME_CODE + offset;
    }

    /** @return the inbound endGame CMD (default: END_GAME_CODE + offset). */
    protected int endGameCmd() {
        return GameMessageTypes.END_GAME_CODE + offset;
    }

    /**
     * Polymorphic subtype registrations for the scenario's ObjectMapper.
     * Default wraps {@code messageTypes.getTypeRegistrations(offset, md5)} —
     * CMD = CODE + offset. A fixed-CMD subclass overrides this to register
     * against literal CMD strings with no offset.
     */
    protected NamedType[] messageTypeRegistrations() {
        return messageTypes.getTypeRegistrations(offset, configuration.getGame().isMd5());
    }

    /**
     * Build the outbound request helper. Default builds the BettingMini
     * {@link Request} keyed on {@code offset} (it adds {@code offset + 3000/3002/...}
     * per outbound). A fixed-CMD subclass overrides this to emit bare literal CMDs
     * (e.g. {@code TaiXiuRequest}, AD-12). Returns the {@link GameRequest} contract
     * so either request shape plugs into the inherited scenario unchanged.
     * <p>
     * RIK_114_BETTING_MINI AD-20: a provider may additionally implement
     * {@link GameRequestFactory} to supply a game-specific outbound shape — P_114's
     * {@code stockPlugin} stakes on {@code v}, not {@code b}, and no other product's
     * frame changes. The capability is optional and tested with {@code instanceof}, the
     * same pattern the inbound EndGame markers use, so <b>the fallback below is the
     * pre-existing behaviour for every provider that does not implement it</b> — which
     * today is all of them but {@code RikGameMessageTypes}. That provider re-applies the
     * game dimension itself (AD-21): it hands back the shared {@link Request} for every
     * 114 game except {@code stockPlugin}, so {@code taixiuMd5Plugin}, which settles
     * through {@link Request} today, is untouched.
     * <p>
     * {@code messageTypes} is injected by {@code BotFactory} before
     * {@code initializeSubclass()} runs, the same ordering {@code TaiXiuGameBot} already
     * relies on; a {@code null} here (test fixtures that bypass the factory) simply
     * fails the {@code instanceof} and takes the fallback.
     */
    protected GameRequest buildRequest(Game game) {
        if (messageTypes instanceof GameRequestFactory factory) {
            return factory.requestFor(game, configuration.getZoneName(), offset);
        }
        return new Request(
            game.getPluginName(),
            configuration.getZoneName(),
            offset
        );
    }

    // Concrete-class accessor seams. Default delegates to the injected
    // GameMessageTypes provider; a fixed-CMD subclass overrides these to
    // delegate to its own per-product provider with the same accessor shape.

    /** @return concrete subscribe message class (default: messageTypes.subscribeType()). */
    protected Class<? extends SubscribeMessage> subscribeType() {
        return messageTypes.subscribeType();
    }

    /** @return concrete startGame message class (default: messageTypes.startGameType()). */
    protected Class<? extends StartGameMessage> startGameType() {
        return messageTypes.startGameType();
    }

    /** @return concrete MD5 startGame message class (default: messageTypes.startGameMd5Type()). */
    protected Class<? extends StartGameMd5Message> startGameMd5Type() {
        return messageTypes.startGameMd5Type();
    }

    /** @return concrete updateBet message class (default: messageTypes.updateBetType()). */
    protected Class<? extends UpdateBetMessage> updateBetType() {
        return messageTypes.updateBetType();
    }

    /** @return concrete endGame message class (default: messageTypes.endGameType()). */
    protected Class<? extends EndGameMessage> endGameType() {
        return messageTypes.endGameType();
    }

    // ----------------------------------------------------------------------
    // EndGame correlation / balance-credit seams (TAI_XIU_BOT.md AD-11, #3).
    //
    // BettingMini reads the session id off the EndGame body and credits nothing
    // extra back to the local balance at round end (winnings are not echoed onto
    // expectedCurrentBalance in BettingMini — the balance is reconciled via
    // checkBalance()). The defaults below reproduce that behavior exactly. A
    // subclass whose EndGame frame carries no sid (e.g. Tai Xiu) overrides
    // endGameSessionId() to correlate against the currently-tracked session, and
    // a subclass that must net a refund/win back into the local balance (Tai Xiu,
    // AD-11) overrides balanceCreditFor().
    // ----------------------------------------------------------------------

    /**
     * Session id used to correlate the just-finished round in {@link #onEndGame}.
     * Default reads {@code msg.getSessionId()} (the betting-mini EndGame carries
     * its own {@code sid}). A subclass whose EndGame frame has no {@code sid}
     * (Tai Xiu) overrides this to return the currently-tracked session
     * ({@code sidStore.get()}).
     */
    protected long endGameSessionId(EndGameMessage msg) {
        return msg.getSessionId();
    }

    /**
     * Amount to credit back to the local {@code expectedCurrentBalance} at round
     * end. Default {@code 0} — BettingMini reconciles its balance via
     * {@code checkBalance()} and does not echo winnings onto the local figure, so
     * behavior is unchanged. Tai Xiu overrides this to net the refund {@code gR}
     * plus winnings back into the running balance (AD-11), since the full bet
     * {@code b} was debited at bet time.
     */
    protected long balanceCreditFor(EndGameMessage msg, long winnings) {
        return 0L;
    }

    /**
     * The per-session aggregation strategy for this game type
     * (AGGREGATED_SESSION_LOGGING AD-4). Betting-mini and Tai Xiu share the
     * round-based {@link BettingSessionStrategy} singleton (Tai Xiu inherits this
     * seam unchanged). A subclass with a different session notion (slot, Phase 3)
     * overrides this to return its own strategy singleton.
     */
    protected SessionAggregationStrategy sessionStrategy() {
        return BettingSessionStrategy.INSTANCE;
    }

    private void onNewSession() {
        long balance = checkBalance();
        BotBehaviorConfig behavior = configuration.getBehaviorConfig();
        // GATEWAY_REQUEST_BUDGET AD-10: depositIsWarranted re-reads the server balance at
        // PRIORITIZED first if — and only if — the drift read that produced `balance` was
        // deferred by the budget. A deposit is money, and it must not be triggered by a figure
        // the budget kept us from refreshing. It also subsumes the old `balance < getMinBalance()`
        // test, so the condition is not evaluated twice.
        if (behavior.isAutoDepositEnabled() && depositIsWarranted(balance)) {
            // LOG_VOLUME_TIERING tier 1: DEBUG per bot, one INFO line per group per
            // deposit round from GroupLifecycleAggregator. One line per bot per
            // top-up is a bot-count-scaled INFO class, which the tier model forbids.
            //
            // Read from expectedCurrentBalance rather than from `balance`: a pre-deposit refresh
            // may have replaced the estimate with the server's figure, and the aggregate should
            // report the number the decision was actually made on.
            long confirmed = expectedCurrentBalance.get();
            log.debug("Bot {}: balance {} below minimum {}, triggering deposit",
                    getUserName(), confirmed, getMinBalance());
            if (groupLifecycleAggregator != null) {
                groupLifecycleAggregator.recordAutoDeposit(getMinBalance() - confirmed);
            }
            deposit();
        } else {
            log.debug("Bot {}: session balance {}", getUserName(), balance);
        }
    }

    private void startRemainingTimeCountDown() {
        scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("countdown-" + getUserName()).factory()
        );

        remainingTime.set(timeForBetting);
        // Wrap with mdcWrap so any future log lines from this countdown task carry the
        // bot's identity. The countdown-<userName> virtual thread is created with an
        // empty MDC; today the body is logging-free but the wrap is cheap insurance.
        scheduler.scheduleAtFixedRate(mdcWrap(() -> {
            if (remainingTime.get() > 0) {
                remainingTime.addAndGet(-1_000L);
            }
        }), 0L, 1_000L, MILLISECONDS);
    }

    private void scheduleWatchdog() {
        if (watchdogTask != null && !watchdogTask.isDone()) {
            watchdogTask.cancel(false);
        }
        // Wrap with mdcWrap so the warn-log + triggerFullReconnect() inside
        // onWatchdogExpired carry the bot's MDC. The watchdog-<userName> virtual
        // thread is created with an empty MDC.
        watchdogTask = watchdogScheduler.schedule(
                mdcWrap(this::onWatchdogExpired),
                watchdogTimeoutMillis,
                MILLISECONDS
        );
    }

    private void onWatchdogExpired() {
        if (isStopped()) return;
        log.warn("Bot {}: no game message in {}s — triggering full reconnect",
                getUserName(), configuration.getWatchdogTimeoutSeconds());
        if (metrics != null) metrics.incBotWatchdogExpired();
        // LOG_VOLUME_TIERING AD-12: the first watchdog expiry is an early-warning signal —
        // the bot is connected but the game has stopped talking to it (the silent-zombie /
        // subscriber-pruning shape). Arm scoped DEBUG for the group NOW, while it is still
        // running; escalating after the group is DEAD would log a group doing nothing.
        // Wrapped because a logging aid must never be able to stop a reconnect.
        if (scopedDebugEscalator != null) {
            try {
                scopedDebugEscalator.onWatchdogExpiry(configuration.getBotGroupId());
            } catch (Exception e) {
                log.warn("Bot {}: scoped-debug escalation failed: {}", getUserName(), e.getMessage());
            }
        }
        triggerFullReconnect("watchdog timeout (" + configuration.getWatchdogTimeoutSeconds() + "s without game message)");
    }

    private void onSubscribe(ActionResponseMessage<? extends SubscribeMessage> data) {
        if (metrics != null) metrics.incBotMessage("subscribe");
        markConnectionAuthenticated();
        SubscribeMessage msg = data.getData();
        blockBetTime = msg.getTimeForDecision();
        timeForBetting = msg.getTimeForBetting();
        scheduleWatchdog();
    }

    private void onStartGame(ActionResponseMessage<? extends StartGameMessage> data) {
        if (metrics != null) metrics.incBotMessage("startGame");
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }

        startRemainingTimeCountDown();
        StartGameMessage msg = data.getData();
        sidStore.set(msg.getSessionId());
        // AGGREGATED_SESSION_LOGGING (AD-5): the FIRST bot to observe this sid for
        // the group logs one session-entry line; the other bots are no-ops. Runs on
        // the mdcConsumer-wrapped netty thread, so the service reads this bot's MDC
        // identity. The raw sample supplier is lazy — invoked only on the first-seen
        // path (AD-9), so the 99 losing bots never serialize the frame.
        if (sessionAggregator != null) {
            sessionAggregator.onSessionStart(msg.getSessionId(), sessionStrategy(),
                    () -> String.valueOf(msg));
        }
        gameState = BettingMiniGameState.BET;
        // Phase 2 of BETTING_STRATEGIES: kick off the in-flight RoundState for
        // bet→result correlation. Balance snapshot mirrors expectedCurrentBalance.
        // Phase 5: the per-round bet counter now lives on the strategy
        // (RandomBehaviorStrategy resets it on sessionId change via
        // currentRound.sessionId, so no explicit reset call is needed here).
        // memory is always non-null post-initializeSubclass (production and
        // tests both call it); see Decision 15.
        memory.beginRound(msg.getSessionId(), expectedCurrentBalance.get());
        // BET_COORDINATION (AD-4): first-seen begins the round budget; the other
        // N-1 bots seeing the same sid are idempotent no-ops. Null when off (AD-9).
        if (coordinator != null) {
            coordinator.onRound(msg.getSessionId());
        }
        // JACKPOT_SCALE_AND_RAMP (AD-J4): snapshot the group's current volume factor
        // for this upcoming round (one-round lag — AD-J7 — the factor reflects the
        // previous round's observed pool). Read once per round here; the strategy
        // consults the derived effectiveMaxBetsPerRound via BetContext. 1.0 (neutral)
        // when jackpot-scale is off.
        currentJackpotFactor = jackpotScaler != null ? jackpotScaler.getCurrentFactor() : 1.0;
        // pendingDecision is intentionally NOT cleared here. Clearing on the
        // netty thread mid-tick raced with the scenario thread between
        // betCondition() (parks decision) and bet() (consumes decision) —
        // see review.md "Race between condition and supplier". The next tick's
        // condition computes a fresh decision in any case; a leftover parked
        // value will be replaced on the next condition call. RandomBehavior
        // resets its per-round counter via the sessionId-change branch in
        // decide().
        scheduleWatchdog();
    }

    private void onUpdate(ActionResponseMessage<? extends UpdateBetMessage> data) {
        if (metrics != null) metrics.incBotMessage("updateBet");
        UpdateBetMessage msg = data.getData();
        int gameStateId = msg.getGameState();

        if (gameStateId > 0) {
            gameState = BettingMiniGameState.from(gameStateId);
        }

        // CROWD_AWARE_COORDINATION (AD-C3): the LIVE, intra-round crowd signal.
        // Tip's UpdateBet re-broadcasts the `bs` crowd array each tick through the
        // bet window (the only product with intra-round `bs`); fold it into the
        // coordinator's per-round budget against the current sid. observeCrowd is a
        // no-op when crowd-aware is off (AD-C6) or the sid is stale, and coordinator
        // is null when coordination is off (AD-9). Tai Xiu's UpdateBet is not a
        // HasCrowdBets (no `bs`), so this branch never fires there (AD-C7).
        if (coordinator != null && msg instanceof HasCrowdBets cb) {
            coordinator.observeCrowd(sidStore.get(), cb.crowdBets());
        }
    }

    private void onEndGame(ActionResponseMessage<? extends EndGameMessage> data) {
        if (metrics != null) metrics.incBotMessage("endGame");

        EndGameMessage msg = data.getData();
        // Marker-interface dispatch (ENDGAME_METRICS plan, Phase A/C).
        // Per-message extraction. Independent `if` checks — a message may
        // implement multiple interfaces. The pre-Phase-A capability hooks
        // (getWinnings / getJackpot / canCheckTotalWinnings / getTotalWinnings
        // / getRoundTotalBetAmount) were deleted in Phase C; the message
        // payload now owns extraction.
        // Extraction (local-accumulator updates) runs unconditionally — those
        // fields back BotHealthDTO and are independent of Prometheus wiring.
        // Only the metric emission is gated on `metrics != null`.
        long payout = 0L;
        if (msg instanceof HasBotWinnings hw) {
            long w = hw.winningsFor(getUserName());
            payout = w;
            lastRoundWinnings = w;
            // Mirror bot_winnings_total value-for-value (BOTGROUP_GAME_MANAGEMENT AD-8):
            // same w>0 guard as the metric, but not gated on metrics != null.
            if (w > 0) cumulativeWinnings.addAndGet(w);
            if (metrics != null && w > 0) metrics.incBotWinnings(w);
        }
        // Refund-aware balance credit (AD-11). Default is 0 (BettingMini
        // unchanged); Tai Xiu nets the refund gR + winnings back into the local
        // balance because the full bet b was debited at bet time.
        long balanceCredit = balanceCreditFor(msg, payout);
        if (balanceCredit != 0L) {
            expectedCurrentBalance.addAndGet(balanceCredit);
        }
        if (metrics != null) {
            if (msg instanceof HasJackpot hj) {
                long j = hj.jackpotFor(getUserName());
                if (j > 0) metrics.incBotJackpot(j);
            }
            if (msg instanceof HasBetTotals bt) {
                // Batch increment: bot_bets_placed_total += count,
                // bot_bet_amount_total += amount. Two-counter math, no average.
                metrics.incBetsPlaced(bt.betCountFor(getUserName()),
                        bt.betAmountFor(getUserName()));
            }
        }

        // AGGREGATED_SESSION_LOGGING (AD-5): accumulate this bot's outcome and let the
        // first bot to observe EndGame log the session summary. Uses endGameSessionId
        // so Tai Xiu (whose frame has no sid) correlates against the tracked session.
        // winnings = the value already extracted via HasBotWinnings (correct per game
        // type, incl. Tai Xiu G); betAmount = server-confirmed stake via HasBetTotals.
        if (sessionAggregator != null) {
            long confirmedBet = (msg instanceof HasBetTotals bt2) ? bt2.betAmountFor(getUserName()) : 0L;
            sessionAggregator.onSessionEnd(endGameSessionId(msg), payout, confirmedBet,
                    () -> String.valueOf(msg));
        }

        // Phase 2 of BETTING_STRATEGIES: finalize the in-flight RoundState into
        // a RoundResult and push onto BotMemory.lastResults. v1 cannot yet
        // extract winningOption (no HasWinningOption marker exists — Implementation
        // Note 4); pass Optional.empty(). globalRecentWins stays empty for v1.
        // Phase 5: route the finalized RoundResult into strategy.onRoundEnd
        // so stateful strategies (Martingale, trend-followers) can update
        // their interpretive state. RandomBehaviorStrategy is a no-op.
        // memory and strategy are both non-null post-initializeSubclass.
        // One completed round observed (BOTGROUP_GAME_MANAGEMENT AD-9). The group
        // "rounds since last restart" stat is the max of this counter across bots.
        roundsObserved.incrementAndGet();

        Optional<Integer> winningOption = Optional.empty();
        RoundResult roundResult = memory.completeRound(endGameSessionId(msg), winningOption, payout);
        memory.recordGlobalWin(winningOption);
        strategy.onRoundEnd(roundResult);
        // BET_COORDINATION (AD-4/AD-6): snapshot the finished round and emit the
        // one-per-round DEBUG summary. Idempotent across the group's bots. Null
        // when coordination is off (AD-9).
        if (coordinator != null) {
            coordinator.onRoundComplete(endGameSessionId(msg));
        }
        // JACKPOT_SCALE_AND_RAMP (AD-J2): read the live pool meter (tJpV via the
        // HasJackpotPool marker — DISTINCT from HasJackpot's per-bot payout) and fold
        // it into the group-scoped scaler. First-seen idempotent across the group's
        // bots. Null when jackpot-scale is off (AD-S3). A meterless frame carries
        // pool=0, which the scaler treats as "not observed" → neutral (AD-J5).
        if (jackpotScaler != null && msg instanceof HasJackpotPool hp) {
            jackpotScaler.observePool(endGameSessionId(msg), hp.jackpotPool());
        }
        // CROWD_AWARE_COORDINATION (AD-C3): the EndGame `bs` is the full-round crowd
        // distribution — the one-round-lagged prior for products without an
        // intra-round signal (BOM/B52/Nohu carry `bs` only on Subscribe+EndGame).
        // Fed against the finished round's sid (still current in the coordinator
        // until the next onRound). observeCrowd is a no-op when crowd-aware is off
        // (AD-C6) or the sid is stale, and coordinator is null when off (AD-9).
        // Tai Xiu's EndGame is not a HasCrowdBets (no `bs`), so this never fires
        // there (AD-C7).
        if (coordinator != null && msg instanceof HasCrowdBets cb) {
            coordinator.observeCrowd(endGameSessionId(msg), cb.crowdBets());
        }

        gameState = BettingMiniGameState.PAYOUT;
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        scheduleWatchdog();
        onNewSession();
    }

    @Override
    protected void beforeReconnect() {
        if (watchdogTask != null) {
            watchdogTask.cancel(false);
            watchdogTask = null;
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        sidStore.set(0L);
        gameState = null;
        remainingTime.set(0L);
        // Strategy state is intentionally not reset here — a reconnect mid-round
        // does not produce a new RoundResult, and strategies that care about
        // cross-round state (e.g. Martingale loss streak) should not lose it on
        // a transient WS disconnect. RandomBehaviorStrategy's per-round counter
        // re-syncs on the next StartGame via the sessionId-change branch.
        pendingDecision.set(Optional.empty());
        pendingCommit.set(Optional.empty());
    }

    @Override
    public void cleanup() {
        super.cleanup();
        if (watchdogScheduler != null && !watchdogScheduler.isShutdown()) {
            watchdogScheduler.shutdownNow();
        }
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
        }
    }

    /**
     * Build a fresh {@link BetContext} snapshot for the current tick. Called
     * from the scenario condition (and indirectly from the supplier via the
     * parked {@link #pendingDecision}) on the {@code pool-N-thread-1} scenario
     * thread. Strategies read this synchronously and must not cache it across
     * calls — the in-flight RoundState, balance, and memory snapshots are all
     * stale by the next tick.
     */
    private BetContext buildBetContext() {
        BotBehaviorConfig behavior = configuration.getBehaviorConfig();
        return new BetContext(
                memory,
                behavior,
                configuration.getGame(),
                expectedCurrentBalance.get(),
                memory.getCurrentRound(),
                rng,
                effectiveMaxBetsPerRound(behavior),
                behavior.isAffinityWeightedProposal());
    }

    /**
     * The per-round bet ceiling for the current round (JACKPOT_SCALE_AND_RAMP AD-J4).
     * With jackpot-scale off ({@code currentJackpotFactor == 1.0}) this returns
     * {@code behavior.getMaxBetsPerRound()} unchanged (byte-for-byte today); when on,
     * it is {@code max(1, round(maxBetsPerRound × factor))} so a bot never drops below
     * one bet per round while betting is enabled.
     */
    private int effectiveMaxBetsPerRound(BotBehaviorConfig behavior) {
        int configured = behavior.getMaxBetsPerRound();
        double factor = currentJackpotFactor;
        if (factor == 1.0) {
            return configured;
        }
        return (int) Math.max(1, Math.round(configured * factor));
    }

    /**
     * Seam over {@code strategy.decide(ctx)} (TAI_XIU_BOT plan AD-13). Both betting
     * call sites — {@link #betCondition()} (parks the decision) and {@link #bet()}
     * (pops/re-derives) — route through this method so a subclass can post-process
     * the strategy's chosen entry without touching the strategy itself.
     *
     * <p><b>Identity unless {@link #singleEntryPerRound()}</b>: by default it returns
     * the strategy's decision verbatim, so BettingMini keeps allowing multiple distinct
     * entries within a single round.
     *
     * <p>When {@link #singleEntryPerRound()} is true (Tai Xiu, 114 stock) the first bet
     * of a round is unconstrained and every later bet in the <i>same</i> round is
     * remapped onto the entry already bet, so a strategy that flips side can still
     * <i>increase</i> the locked side's stake but never bet the other one. The strategy's
     * <i>amount</i> is preserved either way — only the <i>entry</i> is constrained.
     *
     * <p>The lock is <b>derived from round memory</b>, not a separate field: the
     * already-bet entry is read from the in-flight round's per-option bet map, which
     * {@code beginRound} clears on every StartGame. So it resets per round by itself, and
     * both call sites read the same memory, which keeps the park ({@code betCondition})
     * and re-derive ({@code bet()}) paths consistent without extra synchronization. It
     * runs before {@link #applyCoordination}, so the coordinator reserves on the locked
     * entry, not on the one the strategy proposed.
     *
     * @param ctx the per-tick context, also handed to the strategy
     * @return the (possibly remapped) decision, or empty to skip the tick
     */
    protected Optional<BetDecision> decideBet(BetContext ctx) {
        Optional<BetDecision> decision = strategy.decide(ctx);
        if (decision.isEmpty() || !singleEntryPerRound()) {
            return decision;
        }
        Integer lockedEntry = lockedEntryThisRound(ctx);
        BetDecision chosen = decision.get();
        if (lockedEntry == null || chosen.optionId() == lockedEntry) {
            return decision;
        }
        log.trace("Bot {}: single-entry lock — remapping bet entry {} -> {} (amount={})",
                getUserName(), chosen.optionId(), lockedEntry, chosen.amount());
        return Optional.of(new BetDecision(lockedEntry, chosen.amount()));
    }

    /**
     * Whether the server binds a player to one entry per round (see {@link #decideBet}).
     * Default: whatever the game's request says ({@link GameRequest#singleEntryPerRound()}),
     * false for every product except 114 stock. {@link TaiXiuGameBot} overrides it to true.
     */
    protected boolean singleEntryPerRound() {
        return request != null && request.singleEntryPerRound();
    }

    /**
     * The entry already bet in the current round, or {@code null} if no bet has been
     * recorded yet (so the next bet is unconstrained). Under the lock there is at most
     * one key; if more are ever present (defensive), the first by iteration order wins.
     */
    private static Integer lockedEntryThisRound(BetContext ctx) {
        Map<Integer, Long> betsThisRound = ctx.memory().snapshotCurrentRoundBets();
        if (betsThisRound.isEmpty()) {
            return null;
        }
        return betsThisRound.keySet().iterator().next();
    }

    /**
     * BET_COORDINATION (AD-2): gate a strategy-proposed decision through the
     * group-scoped {@link BetCoordinator}. Runs <em>after</em> {@link #decideBet}
     * so the option is already TaiXiu-remapped — the coordinator only ever sees
     * the final, locked entry.
     *
     * <p>When {@code coordinator == null} (coordination off, AD-9) this is the
     * identity: it returns {@code Optional.of(proposed)} and the bet path is
     * byte-for-byte today's. Otherwise it reserves against the in-flight round:
     * APPROVE → the same decision, TRIM → a new {@link BetDecision} with the
     * grid-aligned trimmed amount, REJECT → {@link Optional#empty()} (skip tick).
     *
     * <p>Per CLAUDE.md the per-proposal outcome is TRACE only — never DEBUG (the
     * group-level per-round summary is the coordinator's own DEBUG line).
     *
     * @param ctx      the per-tick context (unused today; kept for a future
     *                 crowd-aware budget that would read it — AD-2 signature).
     * @param proposed the non-empty decision returned by {@link #decideBet}.
     * @return the gated decision, or empty to skip the tick on REJECT.
     */
    protected Optional<BetDecision> applyCoordination(BetContext ctx, BetDecision proposed) {
        if (coordinator == null) {
            return Optional.of(proposed);
        }
        ReservationOutcome outcome = coordinator.reserve(sidStore.get(), proposed.optionId(), proposed.amount());
        switch (outcome.decision()) {
            case APPROVE -> {
                log.trace("Bot {}: coordinator APPROVE option={}, amount={}",
                        getUserName(), proposed.optionId(), proposed.amount());
                return Optional.of(proposed);
            }
            case TRIM -> {
                log.trace("Bot {}: coordinator TRIM option={}, amount {} -> {}",
                        getUserName(), proposed.optionId(), proposed.amount(), outcome.amount());
                return Optional.of(new BetDecision(proposed.optionId(), outcome.amount()));
            }
            default -> {
                log.trace("Bot {}: coordinator REJECT option={}, amount={} (skip tick)",
                        getUserName(), proposed.optionId(), proposed.amount());
                return Optional.empty();
            }
        }
    }

    /**
     * Phase 5: the {@code sendAsync} supplier reads the decision parked by
     * {@link #betCondition()}.
     *
     * <p>In the steady state the condition has already parked a decision via
     * {@link #pendingDecision} and the supplier pops it, book-keeps the bet,
     * and builds the WS message.
     *
     * <p>If the parked decision is absent at supplier time — which can happen
     * only when a netty-thread event ({@link #beforeReconnect}) clears it
     * between the condition and supplier calls — the supplier degrades
     * gracefully: log DEBUG, re-derive a fresh decision via the strategy on
     * the current {@link BetContext}, and use that. The scenario engine's
     * non-null guard (SendAsync.processInternal:135) leaves no way to "skip"
     * the supplier, so if the strategy also declines we have no choice but to
     * throw — but the {@code onStartGame} race is gone (that clear was removed
     * once the strategy's per-round counter started keying on
     * {@code RoundState.sessionId}), and {@code beforeReconnect} only fires
     * while the WS is down, so this path is effectively unreachable in
     * production.
     */
    private Supplier<ActionRequestMessage> bet() {
        return () -> {
            Optional<BetDecision> popped = pendingDecision.getAndSet(Optional.empty());
            if (popped.isEmpty()) {
                // Race fallback. The condition computed and parked a decision
                // but a concurrent netty event (beforeReconnect) cleared it
                // before the supplier ran. Re-derive from the current context
                // instead of throwing — the only call site that can clear
                // mid-tick is beforeReconnect, and a stale-tick decision is
                // strictly less safe than a fresh one.
                log.debug("Bot {}: bet() supplier found no parked decision — re-deriving via strategy", getUserName());
                popped = decideBet(buildBetContext());
                if (popped.isEmpty()) {
                    // Strategy also declined. The scenario engine forbids
                    // returning null from the supplier (it throws
                    // IllegalArgumentException), so we have nothing to send.
                    // This is effectively unreachable: the condition already
                    // exercised decide() this tick and returned true; with no
                    // round boundary in between the strategy should produce
                    // the same outcome. If we ever land here, surface loudly.
                    throw new IllegalStateException(
                            "bet() supplier: strategy declined re-derivation after race with " +
                                    "beforeReconnect — sendAsync cannot skip; engine null-guard would throw");
                }
            }
            BetDecision decision = popped.get();
            long amount = decision.amount();
            int optionId = decision.optionId();
            creditBalance(amount);

            long currentSid = sidStore.get();
            // Phase 2 of BETTING_STRATEGIES: accumulate bet→result correlation.
            memory.recordBetSent(currentSid, optionId, amount);
            // AGGREGATED_SESSION_LOGGING (AD-5): outbound stake is the uniform
            // cross-product source (the UpdateBet frame body carries no stake). Runs
            // on the mdcSupplier-wrapped scenario thread, so MDC identity is present.
            if (sessionAggregator != null) {
                sessionAggregator.recordBet(currentSid, getUserName(), optionId, amount);
            }
            log.trace("Bot {}: sending bet option={}, amount={}, sid={}",
                    getUserName(), optionId, amount, currentSid);
            // RIK_114_BETTING_MINI AD-32: park the per-bet commit (if this game has one)
            // for afterBetSent to pop once the bet has actually left. Empty for every
            // product but 114 stock.
            pendingCommit.set(request.commit(currentSid));
            return request.bet(amount, optionId, currentSid);
        };
    }

    /**
     * RIK_114_BETTING_MINI AD-31/AD-32: the bet stage's {@code onSent} callback. Pops the
     * commit frame {@link #bet()} parked and sends it — on the <b>same</b> thread,
     * immediately after {@code client.send(bet)} has returned, into the same ordered
     * Netty channel — so bet-before-commit is structural, the legacy stock bot's two
     * consecutive {@code socket.send} calls transliterated. A second {@code sendAsync}
     * stage would own its own scheduler thread and make that order a race (AD-31).
     * <p>
     * {@code channel} is the client the <b>bet</b> left on: {@link #botBehaviorScenario()}
     * captures {@code Bot.client} once and hands the same reference to the pipeline
     * context (which {@code SendAsync} sends the bet through) and to this callback. It is
     * deliberately not the mutable {@code Bot.client} field — {@code tryReconnectWs} and
     * {@code restart} reassign that field, and a bet runnable already past its
     * {@code isActive} check when the swap lands would otherwise put its bet on the old
     * socket and its {@code 13022} on the fresh one (stale sid, possibly before the AUTH
     * ack). Bound to the captured client, a post-close commit is dropped by the same
     * closed client that dropped its bet.
     * <p>
     * For every product but 114 stock the parked value is {@link Optional#empty()} and
     * this returns immediately: no frame, no log line.
     * <p>
     * <b>Must never throw.</b> This runs inside the {@code scheduleAtFixedRate} runnable
     * that is the bot's bet loop; an exception escaping it cancels that task for the
     * life of the connection and the bot silently stops betting. {@code client.send}
     * swallows its own exceptions internally, so the {@code catch} guards only
     * {@code serialize} — unreachable on a two-field body, and deterministic if it ever
     * is reached, so it fires on every bet: the first failure is WARNed <b>once per
     * bot</b> with the throwable, every later one is DEBUG. Nothing is logged on
     * success — the {@code OutputPrinter} scenario already prints the SENT frame, with
     * the {@code sId} it actually carries, at TRACE (AD-33).
     * <p>
     * Package-private as a test seam, like {@link #setRandom(Random)}.
     */
    Consumer<SentMessageContext> afterBetSent(ObjectMapper mapper, VingameWebSocketClient channel) {
        return ctx -> {
            Optional<ActionRequestMessage> commit = pendingCommit.getAndSet(Optional.empty());
            if (commit.isEmpty()) {
                return;
            }
            try {
                channel.send(commit.get().serialize(mapper));
            } catch (RuntimeException e) {
                // An escaping exception cancels the fixed-rate bet task for the life of
                // the connection (ScheduledExecutorService semantics) — never let one out.
                if (commitFailureWarned.compareAndSet(false, true)) {
                    log.warn("Bot {}: commit frame not sent (further failures at DEBUG)", getUserName(), e);
                } else {
                    log.debug("Bot {}: commit frame not sent: {}", getUserName(), e.toString());
                }
            }
        };
    }

    private boolean doesEnoughTimeRemain() {
        return remainingTime.get() >= blockBetTime;
    }

    private boolean canBet() {
        boolean sessionExists = sidStore.get() != 0L;
        boolean betPhaseActive = gameState == BettingMiniGameState.BET;

        return sessionExists && betPhaseActive && doesEnoughTimeRemain();
    }

    /**
     * JACKPOT_SCALE_AND_RAMP Phase R2 (AD-R1/AD-R2/AD-R5): the per-tick
     * probabilistic ramp accept gate. Shapes <em>which</em> ticks emit a bet
     * (not the interval, which is immutable — Findings) so aggregate fleet
     * volume builds toward round close like a real-player pile-in.
     *
     * <p>Accept probability follows the power curve
     * {@code pAccept(x) = pMin + (1 - pMin)·x^k} where {@code x} is the window
     * elapsed-fraction ({@code 1 - remainingTime/timeForBetting}) and
     * {@code k = rampShape}. A deferred tick is a <em>deferral</em>, not a lost
     * bet: the strategy's {@code maxBetsPerRound} allotment is untouched, the
     * bot's bets simply concentrate later in the window.
     *
     * <p><strong>Off = today, no RNG drawn (AD-R5).</strong> When ramp is
     * disabled (or {@code rampShape <= 0}) this returns {@code true} without
     * touching {@code rng}, so the strategy's RNG-consumption order (pinned by
     * {@code RandomBehaviorStrategyTest}) is unchanged. The ramp draw happens
     * only when ramp is on, and always before/outside the strategy.
     */
    private boolean rampAccepts() {
        BotBehaviorConfig b = configuration.getBehaviorConfig();
        if (!b.isRampEnabled() || b.getRampShape() <= 0) {
            return true; // AD-R5: off = today, and DRAW NO RNG on this path
        }
        long window = timeForBetting;
        if (window <= 0) {
            return true; // fail-safe: no window → cannot compute elapsed fraction
        }
        double x = 1.0 - Math.max(0L, remainingTime.get()) / (double) window; // elapsed fraction
        x = Math.max(0.0, Math.min(1.0, x)); // clamp01
        double pMin = RAMP_P_MIN;
        double pAccept = pMin + (1 - pMin) * Math.pow(x, b.getRampShape());
        return rng.nextDouble() < pAccept; // draw ONLY when ramp is on
    }

    /**
     * Phase 5: the {@code sendAsync} condition. Gates on the phase-level
     * {@link #canBet()} predicate (session live, BET phase, time remaining),
     * then asks the strategy for a {@link BetDecision} and parks the result
     * in {@link #pendingDecision}. Returns {@code true} only when a decision
     * is present, ensuring the downstream supplier always sees a non-empty
     * parked value.
     *
     * <p>Per CLAUDE.md the per-tick decide() outcome is TRACE-level
     * (STRATEGY_DECISION_AGGREGATION: the signal now rides the 5s aggregate
     * flush; the per-bet drill-in is TRACE).
     */
    private Supplier<Boolean> betCondition() {
        return () -> {
            if (!canBet()) {
                return false;
            }
            if (strategy == null) {
                return false;
            }
            // JACKPOT_SCALE_AND_RAMP Phase R2 (AD-R1): the ramp gate runs BEFORE
            // decideBet/applyCoordination so a deferred tick touches neither the
            // strategy's per-round counter nor a coordinator reservation.
            if (!rampAccepts()) {
                log.trace("Bot {}: ramp deferred tick", getUserName());
                return false;
            }
            BetContext ctx = buildBetContext();
            Optional<BetDecision> decision = decideBet(ctx);
            if (decision.isEmpty()) {
                log.trace("Bot {}: strategy skipped tick (no decision)", getUserName());
                return false;
            }
            // BET_COORDINATION (AD-2): gate the final (TaiXiu-remapped) decision
            // through the coordinator, then park the gated result. Identity when
            // coordination is off; empty result = REJECT → skip this tick.
            Optional<BetDecision> gated = applyCoordination(ctx, decision.get());
            if (gated.isEmpty()) {
                return false;
            }
            pendingDecision.set(gated);
            log.trace("Bot {}: strategy parked decision option={}, amount={}",
                    getUserName(), gated.get().optionId(), gated.get().amount());
            return true;
        };
    }

    private long resolveIntervalBetweenBets() {
        return 1_000L;
    }

    private PipelineContext buildContext(String tag, ObjectMapper mapper) {
        return buildContext(tag, mapper, client);
    }

    /** {@code channel} explicit so the bet stage and its commit callback share one reference (AD-31). */
    private PipelineContext buildContext(String tag, ObjectMapper mapper, VingameWebSocketClient channel) {
        return PipelineContext.buildContext()
                .timeoutMillis(configuration.getTimeoutMillis())
                .client(channel)
                .objectMapper(mapper)
                .tag(tag)
                .build();
    }

    @Override
    protected Scenario botBehaviorScenario() {
        Game game = configuration.getGame();

        // RIK_114 AD-31: capture the client ONCE. The pipeline context sends the bet through
        // this reference and afterBetSent sends the commit through the same one, so the two
        // frames share a channel by construction even if Bot.client is reassigned by a
        // reconnect while a bet runnable is in flight.
        VingameWebSocketClient channel = client;

        // PLUGIN_HOT_RELOAD_3_4 L-8: built on the plugin bundle's TypeFactory (see Bot).
        ObjectMapper mapper = newMessageMapper();
        mapper.registerSubtypes(messageTypeRegistrations());

        Class<? extends SubscribeMessage> subscribeClass = subscribeType();
        Class<? extends StartGameMessage> startGameClass = game.isMd5() ? startGameMd5Type() : startGameType();
        Class<? extends UpdateBetMessage> updateBetClass = updateBetType();
        Class<? extends EndGameMessage> endGameClass = endGameType();

        // onMessage handlers run on the per-client netty-ws-message-processor-ws-<userName>
        // pool. Each sendAsync STAGE owns its own single-thread scheduler
        // (ws-send-async-*, one per SendAsync instance — not one per pipeline, RIK_114
        // AD-31); this pipeline has exactly one such stage, and its condition, supplier
        // and onSent callback run on that one thread, in that order. None of these
        // threads carry MDC by default — wrap each callback so its log lines (and the
        // OutputPrinter-emitted lines that share the pool) carry the bot's identity.
        var stage = pipeline(buildContext("[Betting Mini][" + configuration.getGame().getName() + "]", mapper, channel))
                .waitFor(1_000L)
                .send(request::subscribe)
                .waitForMessage(cmd(subscribeCmd()).and(typeOf(RECEIVED)))
                .onMessage(subscribeClass, mdcConsumer(this::onSubscribe));

        // startGame handler. BettingMini always supplies a concrete class (md5 or
        // non-md5) so this stage is always added (behavior unchanged). A fixed-CMD
        // subclass (Tai Xiu) configured with md5=true but no md5 variant returns
        // null from startGameMd5Type(); skip the handler entirely rather than
        // register a null class — mirrors the updateBet null-guard below.
        if (startGameClass != null) {
            stage = stage.onMessage(startGameClass, mdcConsumer(this::onStartGame));
        }

        // updateBet is optional. BettingMini always supplies a concrete class so this
        // stage is always added (behavior unchanged). A fixed-CMD subclass (Tai Xiu)
        // omits updateBet in v1 — no frame captured, OI-5 — by returning null from
        // updateBetType(); skip the handler entirely rather than register a null class.
        if (updateBetClass != null) {
            stage = stage.onMessage(updateBetClass, mdcConsumer(this::onUpdate));
        }

        return stage
                .sendAsync(buildMessage()
                        .messageSupplier(mdcSupplier(bet()))
                        .mode(INFINITE)
                        .condition(mdcSupplier(betCondition()))
                        .interval(resolveIntervalBetweenBets(), MILLISECONDS)
                        // RIK_114 AD-31: the per-bet commit rides the SAME stage, after
                        // client.send(bet) returns — never a second sendAsync stage.
                        .onSent(mdcConsumer(afterBetSent(mapper, channel)))
                        .build())
                .onMessage(endGameClass, mdcConsumer(this::onEndGame))
                .compile();
    }

    @Override
    protected void onStart() {
        // No try/log here (GATEWAY_REQUEST_BUDGET A33): a failure propagates to Bot.start(), which
        // decides whether it is called off, retried by the reconnect loop or final, and logs it
        // once at the level that matches. This used to log ERROR with a stack trace on every
        // reconnect attempt of a bot whose first read kept failing.
        onNewSession();

        // RIK_114 AD-33: offset + COMMIT_CODE is a print-filter entry only, so a TRACE
        // window shows the outbound 13022 commit and any reply to it. On every other
        // product no such CMD exists and the entry matches nothing. TaiXiuGameBot does
        // not override onStart and never reads offset (it is 0 there), so it inherits
        // a harmless 0 + 3022 entry.
        List<Integer> cmdList = List.of(
            subscribeCmd(),
            updateBetCmd(),
            startGameCmd(),
            endGameCmd(),
            offset + COMMIT_CODE
        );
        // Pass the bot's MDC snapshot so the "User <name>: ..." log lines emitted
        // from the netty-ws-message-processor-ws-<userName> pool carry botGroupId,
        // environmentId, gameType, etc. for Promtail to promote to Loki labels.
        getClient().addScenario(OutputPrinter.debugOutputPrinter(
            cmdList,
            getUserName(),
            buildContext("OutputPrinter", ObjectMapperProvider.getDefault()),
            mdcSnapshot
        ));

        getClient().addScenario(botBehaviorScenario());
    }
}
