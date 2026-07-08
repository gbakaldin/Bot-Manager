package com.vingame.bot.domain.bot.core;

import com.vingame.bot.config.bot.BotBehaviorConfig;
import com.vingame.bot.config.bot.BotConfiguration;
import com.vingame.bot.config.bot.BotCredentials;
import com.vingame.bot.domain.bot.coordination.BetCoordinator;
import com.vingame.bot.domain.bot.coordination.ReservationOutcome;
import com.vingame.bot.domain.bot.message.EndGameMessage;
import com.vingame.bot.domain.bot.message.StartGameMessage;
import com.vingame.bot.domain.bot.message.UpdateBetMessage;
import com.vingame.bot.domain.bot.message.g3.tip.TipEndGameMessage;
import com.vingame.bot.domain.bot.message.g3.tip.TipSubscribeMessage;
import com.vingame.bot.domain.bot.message.g3.tip.TipUpdateBetMessage;
import com.vingame.bot.domain.bot.strategy.BetContext;
import com.vingame.bot.domain.bot.strategy.BetDecision;
import com.vingame.bot.domain.bot.strategy.BettingStrategy;
import com.vingame.bot.domain.bot.strategy.BettingStrategyFactory;
import com.vingame.bot.domain.bot.strategy.RoundResult;
import com.vingame.bot.domain.bot.strategy.StrategyId;
import com.vingame.bot.domain.bot.util.BettingMiniGameState;
import com.vingame.bot.domain.bot.util.SessionIdStore;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.infrastructure.client.ApiGatewayClient;
import com.vingame.bot.infrastructure.client.ClientFactory;
import com.vingame.bot.infrastructure.client.GameMsClient;
import com.vingame.websocketparser.message.properties.MessageCategory;
import com.vingame.websocketparser.message.response.ActionResponseMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CROWD_AWARE_COORDINATION Phase 3 bot seam: {@code onUpdate} reads a
 * {@code bs}-bearing UpdateBet frame (Tip's {@link TipUpdateBetMessage}) and feeds
 * {@code coordinator.observeCrowd(sid, crowdBets())}.
 *
 * <p>Two cases, both observed through the public {@code reserve} API (a shifted
 * per-round budget is what {@code reserve} clamps against):
 * <ul>
 *   <li><b>crowd-aware ON</b> — an UpdateBet whose crowd heavily over-fills option 0
 *       drives that option's fleet budget to 0 (a subsequent {@code reserve} on it
 *       REJECTs) while the under-filled option 1's budget grows (a {@code reserve}
 *       up to the full cap APPROVEs). Without the crowd, both were 500.</li>
 *   <li><b>crowd-aware OFF</b> — the same frame does NOT alter the budget:
 *       {@code observeCrowd} is inert (AD-C6), so option 0 still holds the internal
 *       500 budget and a {@code reserve(0, 500)} APPROVEs byte-for-byte.</li>
 * </ul>
 */
@DisplayName("BettingMiniGameBot crowd seam (CROWD_AWARE_COORDINATION Phase 3)")
class BettingMiniGameBotCrowdSeamTest {

    private BettingMiniGameBot bot;

    @BeforeEach
    void setUp() throws Exception {
        // 2 equal-weight options; minBet=100, betIncrement=100.
        Map<Integer, Integer> affinities = new LinkedHashMap<>();
        affinities.put(0, 1);
        affinities.put(1, 1);
        Game game = Game.builder()
                .id("g-crowd").name("BauCua").pluginName("BauCua")
                .offset(2000).optionAffinities(affinities).build();
        BotBehaviorConfig behavior = BotBehaviorConfig.builder()
                .minBet(100).maxBet(1000).betIncrement(100)
                .maxTotalBetPerRound(10_000).minBetsPerRound(1).maxBetsPerRound(5)
                .chatEnabled(false).autoDepositEnabled(false).betSkipPercentage(0)
                .build();
        BotConfiguration cfg = BotConfiguration.builder()
                .credentials(BotCredentials.builder().username("crowdbot").password("pw").fingerprint("fp").build())
                .environmentId("env-1").botGroupId("group-1").botIndex(1)
                .game(game).behaviorConfig(behavior)
                .zoneName("MiniGame3").timeoutMillis(60_000L)
                .watchdogTimeoutSeconds(120L)
                .strategyId(StrategyId.RANDOM)
                .build();

        BettingStrategyFactory factory = mock(BettingStrategyFactory.class);
        when(factory.create(StrategyId.RANDOM)).thenReturn(new NoopStrategy());

        bot = new BettingMiniGameBot();
        bot.setClients(mock(ApiGatewayClient.class), mock(GameMsClient.class), mock(ClientFactory.class));
        bot.setConfiguration(cfg);
        bot.setStrategyFactory(factory);
        bot.initializeSubclass();
        // onEndGame → onNewSession → checkBalance() reads getClient().getAuthToken();
        // inject a mocked WS client so the EndGame seam path does not NPE. The mocked
        // ApiGatewayClient.getBalance returns 0, so no real network occurs.
        setField("client", mock(com.vingame.websocketparser.VingameWebSocketClient.class));
        // Install the deterministic RNG AFTER initializeSubclass — the latter
        // overwrites this.rng with a nanoTime-seeded Random (BettingMiniGameBot:170),
        // so setting it earlier would be clobbered. Mirrors the de-flaked ramp/jackpot
        // seam harnesses. (This test's NoopStrategy never consults the RNG and the
        // only scheduler in play is a 120s one-shot watchdog torn down in tearDown, so
        // the harness is deterministic regardless — but keep the ordering correct so a
        // future rng-dependent edit cannot silently reintroduce a flake.)
        bot.setRandom(new Random(0xC0FFEEL));
    }

    @AfterEach
    void tearDown() throws Exception {
        ScheduledExecutorService w = (ScheduledExecutorService) readField("watchdogScheduler");
        if (w != null) w.shutdownNow();
        ScheduledExecutorService s = (ScheduledExecutorService) readField("scheduler");
        if (s != null) s.shutdownNow();
    }

    @Test
    @DisplayName("crowd-aware ON: a bs-bearing UpdateBet shifts the budget (option 0 → 0, option 1 → cap)")
    void crowdUpdateShiftsBudget() throws Exception {
        BetCoordinator coordinator = new BetCoordinator(affinities(), 1000L, 100L, 100L, true, "UNKNOWN");
        bot.setCoordinator(coordinator);
        long sid = 8001L;
        openRound(sid, coordinator);

        // Baseline internal-tier budget (no crowd yet): each option 500.
        // Heavy crowd on option 0 (v=10000), none on option 1.
        TipUpdateBetMessage frame = tipUpdate(
                new TipSubscribeMessage.BetInfoWithTotal(0, 0, 0L, 10_000L),
                new TipSubscribeMessage.BetInfoWithTotal(1, 0, 0L, 0L));
        invokeOnUpdate(frame);

        // Option 0's fleet budget collapsed to 0 → reserve rejects even minBet.
        assertThat(coordinator.reserve(sid, 0, 100L).decision())
                .as("crowd over-fills option 0 → fleet budget 0 → REJECT")
                .isEqualTo(ReservationOutcome.Decision.REJECT);
        // Option 1's budget grew (5500 clamped to cap 1000) → the full cap approves.
        assertThat(coordinator.reserve(sid, 1, 1000L).decision())
                .as("crowd under-fills option 1 → fleet budget grew to the cap → APPROVE")
                .isEqualTo(ReservationOutcome.Decision.APPROVE);
    }

    @Test
    @DisplayName("crowd-aware OFF: the same UpdateBet does NOT alter the budget (internal tier verbatim)")
    void crowdOffLeavesBudgetUnchanged() throws Exception {
        BetCoordinator coordinator = new BetCoordinator(affinities(), 1000L, 100L, 100L, false, "UNKNOWN");
        bot.setCoordinator(coordinator);
        long sid = 9001L;
        openRound(sid, coordinator);

        // Same heavy-crowd frame — but crowd-aware is OFF, so observeCrowd is inert.
        TipUpdateBetMessage frame = tipUpdate(
                new TipSubscribeMessage.BetInfoWithTotal(0, 0, 0L, 10_000L),
                new TipSubscribeMessage.BetInfoWithTotal(1, 0, 0L, 0L));
        invokeOnUpdate(frame);

        // Budget is the untouched internal split: option 0 still holds 500.
        // reserve(0, 500) approves the full amount (would REJECT if crowd had applied).
        assertThat(coordinator.reserve(sid, 0, 500L).decision())
                .as("crowd-aware off → budget is the internal 500 → APPROVE, byte-for-byte")
                .isEqualTo(ReservationOutcome.Decision.APPROVE);
    }

    @Test
    @DisplayName("crowd-aware ON: a bs-bearing EndGame feeds observeCrowd against the finished sid (the one-round-lag path, AD-C3)")
    void crowdEndGameShiftsBudget() throws Exception {
        BetCoordinator coordinator = new BetCoordinator(affinities(), 1000L, 100L, 100L, true, "UNKNOWN");
        bot.setCoordinator(coordinator);
        long sid = 8101L;
        openRound(sid, coordinator);

        // The EndGame `bs` is the full-round crowd distribution — the degradation
        // path for products without an intra-round frame (BOM/B52/Nohu). It is fed
        // against the finished round's sid (endGameSessionId), which is still the
        // coordinator's current round until the next onRound. Heavy crowd on option 0.
        TipEndGameMessage frame = tipEndGame(sid,
                new TipSubscribeMessage.BetInfoWithTotal(0, 0, 0L, 10_000L),
                new TipSubscribeMessage.BetInfoWithTotal(1, 0, 0L, 0L));
        invokeOnEndGame(frame);

        // Same shift as the UpdateBet seam: option 0 → 0 (REJECT), option 1 → cap (APPROVE).
        assertThat(coordinator.reserve(sid, 0, 100L).decision())
                .as("EndGame crowd over-fills option 0 → fleet budget 0 → REJECT")
                .isEqualTo(ReservationOutcome.Decision.REJECT);
        assertThat(coordinator.reserve(sid, 1, 1000L).decision())
                .as("EndGame crowd under-fills option 1 → fleet budget grew to the cap → APPROVE")
                .isEqualTo(ReservationOutcome.Decision.APPROVE);
    }

    @Test
    @DisplayName("crowd-aware OFF: a bs-bearing EndGame does NOT alter the budget (observeCrowd inert, AD-C6)")
    void crowdEndGameOffLeavesBudgetUnchanged() throws Exception {
        BetCoordinator coordinator = new BetCoordinator(affinities(), 1000L, 100L, 100L, false, "UNKNOWN");
        bot.setCoordinator(coordinator);
        long sid = 9101L;
        openRound(sid, coordinator);

        TipEndGameMessage frame = tipEndGame(sid,
                new TipSubscribeMessage.BetInfoWithTotal(0, 0, 0L, 10_000L),
                new TipSubscribeMessage.BetInfoWithTotal(1, 0, 0L, 0L));
        invokeOnEndGame(frame);

        // Internal 500 budget untouched → reserve(0, 500) still APPROVEs byte-for-byte.
        assertThat(coordinator.reserve(sid, 0, 500L).decision())
                .as("crowd-aware off → EndGame budget is the internal 500 → APPROVE")
                .isEqualTo(ReservationOutcome.Decision.APPROVE);
    }

    @Test
    @DisplayName("crowd-aware ON: an EndGame crowd read on round N seeds round N+1's opening budget (BOM/B52/Nohu one-round-lag, AD-C3)")
    void endGameSeedsNextRoundOpeningBudget() throws Exception {
        BetCoordinator coordinator = new BetCoordinator(affinities(), 1000L, 100L, 100L, true, "UNKNOWN");
        bot.setCoordinator(coordinator);
        long sidN = 8201L;
        openRound(sidN, coordinator);

        // Round N EndGame: full-round crowd heavily over-fills option 0. For BOM/B52/
        // Nohu this is the ONLY crowd signal (no intra-round bs) and must carry forward.
        TipEndGameMessage endN = tipEndGame(sidN,
                new TipSubscribeMessage.BetInfoWithTotal(0, 0, 0L, 10_000L),
                new TipSubscribeMessage.BetInfoWithTotal(1, 0, 0L, 0L));
        invokeOnEndGame(endN);

        // Round N+1 opens with NO fresh intra-round crowd (BOM/B52/Nohu case). The
        // opening budget must be SEEDED from round N's EndGame distribution, not reset
        // to the internal 500/500 tier.
        long sidNext = 8202L;
        invokeOnStartGame(sidNext);
        setField("gameState", BettingMiniGameState.BET);
        ((SessionIdStore) readField("sidStore")).set(sidNext);

        // Option 0 (crowd-over-filled last round) opens at budget 0 → REJECT.
        assertThat(coordinator.reserve(sidNext, 0, 100L).decision())
                .as("N+1 opens seeded from N's EndGame crowd → option 0 budget 0 → REJECT")
                .isEqualTo(ReservationOutcome.Decision.REJECT);
        // Option 1 (under-filled) opens grown to the cap → APPROVE the full cap.
        assertThat(coordinator.reserve(sidNext, 1, 1000L).decision())
                .as("N+1 option 1 opens grown to the cap from the lagged prior → APPROVE")
                .isEqualTo(ReservationOutcome.Decision.APPROVE);
    }

    @Test
    @DisplayName("crowd-aware OFF: an EndGame crowd read never seeds the next round (internal tier verbatim, AD-C6)")
    void endGameDoesNotSeedNextRoundWhenOff() throws Exception {
        BetCoordinator coordinator = new BetCoordinator(affinities(), 1000L, 100L, 100L, false, "UNKNOWN");
        bot.setCoordinator(coordinator);
        long sidN = 9201L;
        openRound(sidN, coordinator);

        TipEndGameMessage endN = tipEndGame(sidN,
                new TipSubscribeMessage.BetInfoWithTotal(0, 0, 0L, 10_000L),
                new TipSubscribeMessage.BetInfoWithTotal(1, 0, 0L, 0L));
        invokeOnEndGame(endN);

        long sidNext = 9202L;
        invokeOnStartGame(sidNext);
        setField("gameState", BettingMiniGameState.BET);
        ((SessionIdStore) readField("sidStore")).set(sidNext);

        // Crowd-off: no carry-forward seed, N+1 opens at the internal 500 tier.
        assertThat(coordinator.reserve(sidNext, 0, 500L).decision())
                .as("crowd-off → N+1 opens at the internal 500 budget → APPROVE, byte-for-byte")
                .isEqualTo(ReservationOutcome.Decision.APPROVE);
    }

    /* ---- helpers ---- */

    private Map<Integer, Integer> affinities() {
        Map<Integer, Integer> a = new LinkedHashMap<>();
        a.put(0, 1);
        a.put(1, 1);
        return a;
    }

    private TipUpdateBetMessage tipUpdate(TipSubscribeMessage.BetInfoWithTotal... entries) {
        return new TipUpdateBetMessage(11002, List.of(entries), 42);
    }

    private TipEndGameMessage tipEndGame(long sid, TipSubscribeMessage.BetInfoWithTotal... entries) {
        // cmd, iJ, gid, ps, tJpV, eIn, d1, d2, d3, iJp, sid, bs, jpV, tJpv2, jPTp, jpCD, wm, sDi
        return new TipEndGameMessage(11004, false, 42, List.of(), 0L, null,
                0, 0, 0, false, sid, List.of(entries), 0L, 0L, 0, null, 0L, null);
    }

    private void invokeOnEndGame(EndGameMessage msg) throws Exception {
        ActionResponseMessage<EndGameMessage> resp =
                new ActionResponseMessage<>(MessageCategory.ACTION_RESPONSE, msg);
        Method m = BettingMiniGameBot.class.getDeclaredMethod("onEndGame", ActionResponseMessage.class);
        m.setAccessible(true);
        m.invoke(bot, resp);
    }

    private void openRound(long sid, BetCoordinator coordinator) throws Exception {
        invokeOnStartGame(sid);
        setField("gameState", BettingMiniGameState.BET);
        ((SessionIdStore) readField("sidStore")).set(sid);
        // onStartGame already called coordinator.onRound(sid); re-affirm for safety.
        coordinator.onRound(sid);
    }

    private void invokeOnStartGame(long sid) throws Exception {
        StartGameMessage msg = mock(StartGameMessage.class);
        when(msg.getSessionId()).thenReturn(sid);
        ActionResponseMessage<StartGameMessage> resp =
                new ActionResponseMessage<>(MessageCategory.ACTION_RESPONSE, msg);
        Method m = BettingMiniGameBot.class.getDeclaredMethod("onStartGame", ActionResponseMessage.class);
        m.setAccessible(true);
        m.invoke(bot, resp);
    }

    private void invokeOnUpdate(UpdateBetMessage msg) throws Exception {
        ActionResponseMessage<UpdateBetMessage> resp =
                new ActionResponseMessage<>(MessageCategory.ACTION_RESPONSE, msg);
        Method m = BettingMiniGameBot.class.getDeclaredMethod("onUpdate", ActionResponseMessage.class);
        m.setAccessible(true);
        m.invoke(bot, resp);
    }

    private Object readField(String name) throws Exception {
        Field f;
        try {
            f = BettingMiniGameBot.class.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            f = Bot.class.getDeclaredField(name);
        }
        f.setAccessible(true);
        return f.get(bot);
    }

    private void setField(String name, Object value) throws Exception {
        Field f;
        try {
            f = BettingMiniGameBot.class.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            f = Bot.class.getDeclaredField(name);
        }
        f.setAccessible(true);
        f.set(bot, value);
    }

    /** Strategy that never proposes — this test drives the coordinator directly. */
    private static final class NoopStrategy implements BettingStrategy {
        @Override
        public Optional<BetDecision> decide(BetContext ctx) {
            return Optional.empty();
        }

        @Override
        public void onRoundEnd(RoundResult result) {
        }
    }
}
