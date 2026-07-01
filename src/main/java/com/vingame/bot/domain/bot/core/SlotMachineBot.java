package com.vingame.bot.domain.bot.core;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vingame.bot.config.bot.BotBehaviorConfig;
import com.vingame.bot.domain.bot.message.HasBetTotals;
import com.vingame.bot.domain.bot.message.HasBotWinnings;
import com.vingame.bot.domain.bot.message.SlotMessageTypes;
import com.vingame.bot.domain.bot.message.request.SlotRequest;
import com.vingame.bot.domain.bot.message.slot.SlotSpinResultMessage;
import com.vingame.bot.domain.bot.message.slot.SlotSubscribeResponse;
import com.vingame.bot.domain.bot.strategy.slot.FixedBetStrategy;
import com.vingame.bot.domain.bot.strategy.slot.SlotBetContext;
import com.vingame.bot.domain.bot.strategy.slot.SlotStrategy;
import com.vingame.bot.domain.bot.strategy.slot.SlotStrategyFactory;
import com.vingame.bot.domain.bot.strategy.slot.SlotStrategyId;
import com.vingame.bot.domain.bot.util.OutputPrinter;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.infrastructure.observability.SlotSessionStrategy;
import com.vingame.websocketparser.ObjectMapperProvider;
import com.vingame.websocketparser.message.request.ActionRequestMessage;
import com.vingame.websocketparser.message.response.ActionResponseMessage;
import com.vingame.websocketparser.scenario.PipelineContext;
import com.vingame.websocketparser.scenario.Scenario;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static com.vingame.websocketparser.message.properties.MessageType.RECEIVED;
import static com.vingame.websocketparser.scenario.Scenario.pipeline;
import static com.vingame.websocketparser.scenario.matchers.Qualifier.cmd;
import static com.vingame.websocketparser.scenario.matchers.Qualifier.typeOf;
import static com.vingame.websocketparser.scenario.processors.OutboundMessage.buildMessage;
import static com.vingame.websocketparser.scenario.processors.SendMode.INFINITE;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * Slot-machine bot: request/response per spin (subscribe {@code cmd:1300} →
 * spin loop {@code cmd:1302}), with no shared round clock, no BET/PAYOUT phase
 * and no broadcast watchdog. See {@code docs/plans/SLOT_MACHINE_BOT.md}.
 * <p>
 * The number of winlines and the allowed bet values are <b>server-sourced</b>
 * from the subscribe ({@code cmd:1300}) response at runtime (AD-8/AD-11/AD-12),
 * not from Game config. {@code canSpin()} gates the first spin on having
 * received that response.
 * <p>
 * <b>Phase 6.</b> Bet selection is delegated to the pluggable slot-strategy
 * family: the bot reads {@code BotConfiguration.slotStrategyId} (defaulting to
 * {@link SlotStrategyId#FIXED}) and instantiates a {@link SlotStrategy} via the
 * injected {@link SlotStrategyFactory} in {@link #initializeSubclass()}. The
 * strategy only picks the bet <em>amount</em> from the server-sourced allowed
 * set (AD-9); cadence, one-spin-in-flight and balance gating remain the bot's
 * job (AD-6/AD-13).
 */
@Slf4j
public class SlotMachineBot extends Bot {

    // Slot message-types provider (injected by BotFactory in Phase 5).
    @Setter
    private SlotMessageTypes messageTypes;

    private SlotRequest request;

    // Slot game id (gid). Lives on Game.gameId (AD-2); fixed per bot.
    private int gid;

    // Server-sourced config, populated in onSubscribe from the 1300 response.
    // numLines = ls.size() (AD-8); allowedBetValues = Js[].b sorted asc (AD-11).
    // null/0 until the subscribe response is received — canSpin() gates on this.
    private volatile int numLines = 0;
    private volatile List<Long> allowedBetValues;

    // One-spin-in-flight gate (AD-6/AD-14). Set in spin(), cleared in
    // onSpinResult() and beforeReconnect().
    private final AtomicBoolean spinInFlight = new AtomicBoolean(false);

    // Park-and-pop: the scenario engine throws if the sendAsync supplier returns
    // null, so spinCondition() parks the chosen bet and spin() pops it. Mirrors
    // BettingMiniGameBot.pendingDecision.
    private final AtomicReference<Optional<Long>> pendingBet =
            new AtomicReference<>(Optional.empty());

    // Per-bot RNG, owned by the bot (seeded in initializeSubclass). Test fixtures
    // override via setRandom().
    private Random rng = new Random();

    // Slot strategy factory (injected by BotFactory in Phase 5). May be null on
    // standalone-test code paths — initializeSubclass() then falls back to an
    // inline FixedBetStrategy (mirrors the betting bot's RandomBehaviorStrategy
    // fallback).
    @Setter
    private SlotStrategyFactory slotStrategyFactory;

    // Per-bot bet-amount picker, built in initializeSubclass() from
    // configuration.getSlotStrategyId() (default FIXED). Picks the staked amount
    // from the server-sourced allowed set (AD-9).
    private SlotStrategy strategy;

    /**
     * Visible for testing — inject a seeded/mocked {@link Random} for
     * deterministic bet selection (mirrors {@code BettingMiniGameBot.setRandom}).
     */
    void setRandom(Random random) {
        this.rng = random;
    }

    public SlotMachineBot() {
        super();
    }

    @Override
    protected void initializeSubclass() {
        Game game = configuration.getGame();
        Integer gameId = game.getGameId();
        if (gameId == null) {
            // A SLOT game with no gameId is a misconfiguration — fail loud here
            // rather than NPE in the message builder (Implementation Notes).
            throw new IllegalStateException(
                    "SLOT game '" + game.getName() + "' has no gameId — gameId carries the slot gid (AD-2)");
        }
        this.gid = gameId;

        // Slot frames route to the fixed cross-product slot extension
        // (SlotMessageTypes.SLOT_PLUGIN_NAME), NOT game.getPluginName() — that is
        // the brand's betting-mini extension and the server never answers a slot
        // subscribe routed there. Only the zone is supplied here. (SLOT_MACHINE_BOT
        // staging fix.)
        this.request = new SlotRequest(configuration.getZoneName());

        // Deterministic-ish per user, distinct per process (mirrors betting bot).
        // Test fixtures override via setRandom().
        this.rng = new Random(((long) getUserName().hashCode()) ^ System.nanoTime());

        // Build the per-bot slot strategy (AD-9). Default to FIXED when the
        // config leaves slotStrategyId null (AD-10: slot strategy is out of the
        // group strategy-mix UI for v1). When the factory is absent (standalone
        // tests), fall back to an inline FixedBetStrategy so the bot is testable
        // without a Spring context (mirrors the betting bot's fallback).
        SlotStrategyId strategyId = configuration.getSlotStrategyId() != null
                ? configuration.getSlotStrategyId()
                : SlotStrategyId.FIXED;
        this.strategy = slotStrategyFactory != null
                ? slotStrategyFactory.create(strategyId)
                : new FixedBetStrategy();

        // numLines / allowedBetValues are NOT known yet — they arrive with the
        // 1300 response and are captured in onSubscribe (AD-12).
        log.info("SlotMachineBot initialized: game={}, gid={}, strategy={}",
                game.getName(), gid, strategyId);
    }

    private void onNewSession() {
        long balance = checkBalance();
        BotBehaviorConfig behavior = configuration.getBehaviorConfig();
        if (behavior.isAutoDepositEnabled() && balance < getMinBalance()) {
            log.info("Bot {}: balance {} below minimum {}, triggering deposit", getUserName(), balance, getMinBalance());
            deposit();
        } else {
            log.debug("Bot {}: session balance {}", getUserName(), balance);
        }
    }

    /**
     * Handle the subscribe ({@code cmd:1300}) response. Captures the
     * server-sourced winline count and allowed bet-value set (AD-11/AD-12), then
     * marks the connection authenticated and runs the new-session balance check.
     */
    private void onSubscribe(ActionResponseMessage<? extends SlotSubscribeResponse> data) {
        if (metrics != null) metrics.incBotMessage("subscribe");

        // Mark the connection authenticated FIRST, unconditionally — mirroring
        // BettingMiniGameBot.onSubscribe. The bot has received and acknowledged a
        // subscribe response on a live socket, so the connection IS authenticated.
        // Gating this behind validation would wedge the bot in AUTHENTICATING_CONNECTION
        // forever on a degenerate 1300 (live socket, no disconnect, no watchdog).
        // The spin gate (canSpin / spinCondition) still keeps a misconfigured bot
        // from spinning blind because numLines/allowedBetValues stay unset.
        markConnectionAuthenticated();

        SlotSubscribeResponse resp = data.getData();
        int lines = resp.numLines();
        List<Long> betValues = resp.allowedBetValues();
        if (lines <= 0 || betValues == null || betValues.isEmpty()) {
            // A 1300 with no winline definitions or no jackpot tiers is a
            // misconfigured game — WARN and skip the capture so canSpin() keeps
            // the bot from spinning blind (AD-12).
            log.warn("Bot {}: subscribe response missing winlines/bet values (lines={}, betValues={}) — not spinning",
                    getUserName(), lines, betValues);
            return;
        }
        this.numLines = lines;
        this.allowedBetValues = betValues;
        log.debug("Bot {}: subscribed — numLines={}, allowedBetValues={}",
                getUserName(), numLines, allowedBetValues);

        onNewSession();
    }

    /**
     * Handle the spin ({@code cmd:1302}) result. Routes through the same
     * marker-interface dispatch as the betting-mini {@code onEndGame} (AD-7):
     * winnings are gross ({@code sum(wls[].crd)}), credited back to the balance;
     * bet totals are a single spin. Finally clears the one-spin-in-flight gate.
     */
    private void onSpinResult(ActionResponseMessage<? extends SlotSpinResultMessage> data) {
        if (metrics != null) metrics.incBotMessage("spin");

        SlotSpinResultMessage msg = data.getData();
        // Defensive gid guard (Implementation Notes): ignore a stray foreign-gid
        // frame rather than mis-accounting it. Each bot owns its own WS client,
        // so this should never fire in v1.
        if (msg.getGid() != gid) {
            log.warn("Bot {}: ignoring spin result for gid={} (expected {})",
                    getUserName(), msg.getGid(), gid);
            spinInFlight.set(false);
            return;
        }

        // Extraction runs unconditionally (it backs BotHealthDTO); only metric
        // emission is gated on metrics != null (match betting bot).
        long winnings = 0L;
        if (msg instanceof HasBotWinnings hw) {
            winnings = hw.winningsFor(getUserName());
            lastRoundWinnings = winnings;
            if (winnings > 0) {
                expectedCurrentBalance.addAndGet(winnings);
                if (metrics != null) metrics.incBotWinnings(winnings);
            }
        }
        if (metrics != null && msg instanceof HasBetTotals bt) {
            // Bet-amount metric must reflect TOTAL stake = per-line b * numLines,
            // matching the gate and the debit (AD-13). betAmountFor() returns only
            // the per-line b (numLines is not on the result message), so multiply
            // here by the bot's server-sourced numLines. betCountFor() stays 1.
            long totalStake = bt.betAmountFor(getUserName()) * numLines;
            metrics.incBetsPlaced(bt.betCountFor(getUserName()), totalStake);
        }

        // AGGREGATED_SESSION_LOGGING (AD-12): feed the spin result into the same
        // synthetic window as the outbound spin — gross winnings + jackpot hit
        // (`iJ`). Mirrors BettingMiniGameBot.onEndGame → onSessionEnd; here there is
        // no round boundary, so recordSpinResult never marks the window ended (it is
        // reclaimed by TTL/group-stop). Null-tolerant for standalone tests.
        if (sessionAggregator != null) {
            sessionAggregator.recordSpinResult(winnings, msg.isIJ());
        }

        log.debug("Bot {}: spin result b={}, winnings={}, sid={}, balance={}",
                getUserName(), msg.getB(), winnings, msg.getSid(), expectedCurrentBalance.get());

        spinInFlight.set(false);
    }

    /**
     * Ask the assigned {@link SlotStrategy} for the next bet amount, building a
     * fresh {@link SlotBetContext} over the current server-sourced config and
     * balance. The strategy only picks the amount from the allowed set (AD-9);
     * cadence/in-flight/balance gating stays in {@link #spinCondition()}.
     */
    private long chooseBet() {
        SlotBetContext ctx = new SlotBetContext(
                allowedBetValues, numLines, expectedCurrentBalance.get(), rng);
        return strategy.chooseBet(ctx);
    }

    /**
     * The {@code sendAsync} condition. Returns {@code false} until the subscribe
     * response has populated the server-sourced config (AD-12), while a spin is
     * in flight (AD-6), or when the balance cannot cover the staked spin
     * (AD-13: {@code chosenBet * numLines}). Otherwise parks the chosen bet and
     * returns {@code true} so the supplier never sees an empty parked value.
     */
    private Supplier<Boolean> spinCondition() {
        return () -> {
            // AD-12 gate: do not spin before the 1300 response is processed.
            if (numLines == 0 || allowedBetValues == null) {
                return false;
            }
            // AD-6 gate: one spin in flight at a time.
            if (spinInFlight.get()) {
                return false;
            }
            long chosenBet = chooseBet();
            // AD-13 balance gate: a spin stakes chosenBet on each of numLines.
            long cost = chosenBet * numLines;
            if (expectedCurrentBalance.get() < cost) {
                log.debug("Bot {}: balance {} below spin cost {} ({} x {}) — skipping tick",
                        getUserName(), expectedCurrentBalance.get(), cost, chosenBet, numLines);
                return false;
            }
            pendingBet.set(Optional.of(chosenBet));
            log.debug("Bot {}: parked spin bet={} ({} lines)", getUserName(), chosenBet, numLines);
            return true;
        };
    }

    /**
     * The {@code sendAsync} supplier. Pops the bet parked by
     * {@link #spinCondition()} (re-deriving via {@link #chooseBet()} if a
     * concurrent {@code beforeReconnect} cleared it, mirroring the betting bot),
     * sets the one-spin-in-flight gate, debits the staked amount, and builds the
     * spin over all server-sourced winlines ({@code [0..numLines-1]}).
     */
    private Supplier<ActionRequestMessage> spin() {
        return () -> {
            Optional<Long> popped = pendingBet.getAndSet(Optional.empty());
            long amount;
            if (popped.isPresent()) {
                amount = popped.get();
            } else {
                // Race fallback: a concurrent beforeReconnect cleared the parked
                // bet between condition and supplier. Re-derive rather than
                // return null (the engine forbids null suppliers).
                log.debug("Bot {}: spin() found no parked bet — re-deriving", getUserName());
                amount = chooseBet();
            }

            spinInFlight.set(true);
            // Debit the TOTAL stake on send (AD-7/AD-13): the per-line bet `amount`
            // is staked across each of `numLines` winlines, so the wallet drops by
            // amount * numLines — the same figure the balance gate reserved. The
            // request still carries the per-line `amount` (the server multiplies by
            // the line set). Winnings (gross) are credited on result.
            long totalStake = amount * numLines;
            creditBalance(totalStake);

            // AGGREGATED_SESSION_LOGGING (AD-12): feed the synthetic per-(group,gameId)
            // slot window from the outbound spin, mirroring BettingMiniGameBot.bet() →
            // recordBet. Runs on the mdcSupplier-wrapped scenario thread, so the service
            // reads this bot's MDC identity and the window is created lazily on the first
            // spin. Null-tolerant for standalone tests.
            // STRATEGY_DECISION_AGGREGATION (Phase 2, AD-6): the per-line `amount` IS the
            // slot decision — pass it as the bet-size histogram key (bet values fit int),
            // alongside the total stake, into the same per-window histogram the betting
            // option distribution uses.
            if (sessionAggregator != null) {
                sessionAggregator.recordSpin(SlotSessionStrategy.INSTANCE, getUserName(), (int) amount, totalStake);
            }

            List<Integer> ls = new ArrayList<>(numLines);
            for (int i = 0; i < numLines; i++) {
                ls.add(i);
            }

            log.debug("Bot {}: sending spin gid={}, bet={} ({} lines, totalStake={})",
                    getUserName(), gid, amount, numLines, totalStake);
            return request.spin(gid, amount, ls);
        };
    }

    private long resolveSpinInterval() {
        return 3_000L;
    }

    private PipelineContext buildContext(String tag, ObjectMapper mapper) {
        return PipelineContext.buildContext()
                .timeoutMillis(configuration.getTimeoutMillis())
                .client(client)
                .objectMapper(mapper)
                .tag(tag)
                .build();
    }

    @Override
    protected Scenario botBehaviorScenario() {
        Game game = configuration.getGame();

        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.registerSubtypes(messageTypes.getTypeRegistrations());

        // onMessage handlers run on the per-client netty-ws-message-processor pool;
        // sendAsync's supplier + condition run on a scenario-owned pool thread.
        // None carry MDC by default — wrap each callback so its log lines carry
        // the bot's identity.
        return pipeline(buildContext("[Slot][" + game.getName() + "]", mapper))
                .waitFor(1_000L)
                .send(() -> request.subscribe(gid))
                .waitForMessage(cmd(SlotMessageTypes.SUBSCRIBE_CMD).and(typeOf(RECEIVED)))
                .onMessage(SlotSubscribeResponse.class, mdcConsumer(this::onSubscribe))
                .sendAsync(buildMessage()
                        .messageSupplier(mdcSupplier(spin()))
                        .mode(INFINITE)
                        .condition(mdcSupplier(spinCondition()))
                        .interval(resolveSpinInterval(), MILLISECONDS)
                        .build())
                .onMessage(SlotSpinResultMessage.class, mdcConsumer(this::onSpinResult))
                .compile();
    }

    @Override
    protected void beforeReconnect() {
        spinInFlight.set(false);
        pendingBet.set(Optional.empty());
        // numLines / allowedBetValues are left as-is: they are re-set on the
        // re-subscribe's 1300 response and re-gate the first post-reconnect spin
        // (AD-12). Resetting them is optional but harmless.
    }

    @Override
    protected void onStart() {
        try {
            onNewSession();
        } catch (Exception e) {
            log.error("Bot {}: initial session setup failed", getUserName(), e);
            throw e;
        }

        List<Integer> cmdList = List.of(
                SlotMessageTypes.SUBSCRIBE_CMD,
                SlotMessageTypes.SPIN_CMD
        );
        getClient().addScenario(OutputPrinter.debugOutputPrinter(
                cmdList,
                getUserName(),
                buildContext("OutputPrinter", ObjectMapperProvider.getDefault()),
                mdcSnapshot
        ));

        getClient().addScenario(botBehaviorScenario());
    }
}
