package com.vingame.bot.domain.botgroup.service;

import com.vingame.bot.common.gateway.GatewayRequestScope;
import com.vingame.bot.common.gateway.RequestTier;
import com.vingame.bot.domain.bot.core.Bot;
import com.vingame.bot.domain.bot.service.BotFactory;
import com.vingame.bot.domain.botgroup.model.BotGroup;
import com.vingame.bot.domain.environment.model.Environment;
import com.vingame.bot.domain.environment.service.EnvironmentService;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.domain.game.service.GameService;
import com.vingame.bot.infrastructure.gateway.GatewayBudget;
import com.vingame.bot.infrastructure.gateway.GatewayBudgetMode;
import com.vingame.bot.infrastructure.gateway.GatewayBudgetRegistry;
import com.vingame.bot.infrastructure.observability.BotMetrics;
import com.vingame.bot.infrastructure.observability.GroupLifecycleAggregator;
import com.vingame.bot.infrastructure.observability.ScopedDebugEscalator;
import com.vingame.bot.infrastructure.observability.SessionAggregationService;
import com.vingame.bot.infrastructure.runtime.BotGroupRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * <b>A group start declares its demand before it floods the window, and always gives the
 * remainder back</b> (GATEWAY_REQUEST_BUDGET AD-7, A15.1, A20.4).
 * <p>
 * Three properties, each of which fails silently if it is wrong:
 * <ul>
 *   <li><b>The number.</b> {@code botCount × 3} — login, WebSocket upgrade, first balance read.
 *       A15 closed Open Item 1 (the WS hosts sit behind the same Cloudflare rule), so the third
 *       term is fact rather than a conservative guess. Getting it wrong low makes the reservation
 *       pointless; getting it wrong high only costs the lower tiers some ceiling until it is
 *       consumed or released.</li>
 *   <li><b>The release.</b> On every path, success or failure. A reservation that outlives its
 *       build shrinks DEFAULT and PRIORITIZED for the life of the JVM, so registration and drift
 *       reads would be starved by a start that finished hours ago.</li>
 *   <li><b>The ordering.</b> The reservation is taken <em>after</em> the runtime is published,
 *       because {@code cancelStartInFlight} resolves the environment from
 *       {@code runningGroups} — a reservation taken at the top of {@code startLocked} would be
 *       unreleasable by a {@code /stop} landing in that window. Compliance flagged this
 *       explicitly, and it is asserted here rather than left to a comment.</li>
 * </ul>
 * <p>
 * No socket and no gateway: {@code BotFactory} is a mock, so no bot is authenticated, and the
 * budget is a recording double.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Bot group start — declared gateway demand")
class BotGroupBehaviorServiceReservationTest {

    @Mock private BotGroupService botGroupService;
    @Mock private EnvironmentService environmentService;
    @Mock private GameService gameService;
    @Mock private BotFactory botFactory;
    @Mock private BotMetrics botMetrics;
    @Mock private SessionAggregationService sessionAggregationService;
    @Mock private GroupLifecycleAggregator groupLifecycleAggregator;
    @Mock private ScopedDebugEscalator scopedDebugEscalator;
    @Mock private GatewayBudgetRegistry gatewayBudgetRegistry;

    @InjectMocks
    private BotGroupBehaviorService service;

    private RecordingBudget budget;

    @BeforeEach
    void setUp() {
        budget = new RecordingBudget();
        when(gatewayBudgetRegistry.forEnvironment(any(), any(), any())).thenReturn(budget);
        when(botGroupService.save(any(BotGroup.class))).thenAnswer(inv -> inv.getArgument(0));
        when(environmentService.findById(anyString())).thenReturn(environment());
        when(gameService.findById(anyString())).thenReturn(game());

        ReflectionTestUtils.setField(service, "deadBotGroupThreshold", 0.80);
        ReflectionTestUtils.setField(service, "botCreationParallelism", 4);
        ReflectionTestUtils.setField(service, "watchdogTimeoutSeconds", 180L);
        ReflectionTestUtils.setField(service, "periodicLogoutEnabled", false);
        ReflectionTestUtils.setField(service, "periodicLogoutIntervalMinutes", 60);
        ReflectionTestUtils.setField(service, "reconnectDelaySeconds", 5);
        ReflectionTestUtils.setField(service, "activationZone", "Asia/Ho_Chi_Minh");
    }

    @AfterEach
    void tearDown() {
        try {
            service.shutdown();
        } catch (Exception ignored) {
            // the executors are best-effort here
        }
    }

    @Test
    @DisplayName("demand is botCount x 3, declared at ESSENTIAL under the group's own scope")
    void demandIsBotCountTimesThree() {
        when(botGroupService.findById("g-1")).thenReturn(group(50));
        when(botFactory.createBot(anyString(), any())).thenAnswer(inv -> stubBot("bot"));

        service.start("g-1");

        assertThat(budget.reservations).hasSize(1);
        RecordingBudget.Declared declared = budget.reservations.get(0);
        assertThat(declared.permits())
                .as("login + WS upgrade + first balance read, per bot. The third term is fact "
                        + "since A15 — the WS hosts are behind the same Cloudflare rule.")
                .isEqualTo(150);
        assertThat(declared.tier())
                .as("ESSENTIAL: this is the tier the flood will arrive at, and the point of "
                        + "declaring it is to stop the tiers BELOW filling the window first")
                .isEqualTo(RequestTier.ESSENTIAL);
        assertThat(declared.scope().botGroupId())
                .as("keyed by group, because that is what the budget matches an admission "
                        + "against when it draws a permit down")
                .isEqualTo("g-1");
    }

    @Test
    @DisplayName("the helper's estimate is windows, not wishful thinking")
    void theEstimateIsCoarseAndHonest() {
        // AD-18 wants the operator told, in the one INFO line, that a 3,000-bot group is ~50
        // minutes and that this is correct behaviour rather than a regression.
        assertThat(BotGroupBehaviorService.estimatedStartMinutes(300 * 3, 900)).isEqualTo(5);
        assertThat(BotGroupBehaviorService.estimatedStartMinutes(3_000 * 3, 900)).isEqualTo(50);
        assertThat(BotGroupBehaviorService.estimatedStartMinutes(1, 900)).isEqualTo(5);
        assertThat(BotGroupBehaviorService.estimatedStartMinutes(0, 900)).isZero();
        assertThat(BotGroupBehaviorService.estimatedStartMinutes(900, 0))
                .as("a zero cap cannot happen (settings validation rejects it) and must not "
                        + "divide by zero if it ever does")
                .isZero();
    }

    @Test
    @DisplayName("the reservation is released on a successful start")
    void releasedOnSuccess() {
        when(botGroupService.findById("g-1")).thenReturn(group(3));
        when(botFactory.createBot(anyString(), any())).thenAnswer(inv -> stubBot("bot"));

        service.start("g-1");

        assertThat(budget.reservations).hasSize(1);
        assertThat(budget.reservations.get(0).released()).isTrue();
    }

    @Test
    @DisplayName("the reservation is released when the build fails outright")
    void releasedOnFailure() {
        when(botGroupService.findById("g-1")).thenReturn(group(2));
        when(botFactory.createBot(anyString(), any())).thenThrow(new IllegalStateException("no auth"));

        // Zero bots came up, so the group is marked DEAD rather than throwing — either way the
        // reservation must be gone.
        service.start("g-1");

        assertThat(budget.reservations).hasSize(1);
        assertThat(budget.reservations.get(0).released())
                .as("a leaked reservation would starve registration and drift reads for the life "
                        + "of the JVM on behalf of a start that already failed")
                .isTrue();
    }

    @Test
    @DisplayName("the reservation is released when the group document is unusable")
    void releasedWhenTheGroupIsUnstartable() {
        // validateStartable throws before the runtime exists, so no reservation should have been
        // taken at all — the assertion is that the ordering really is "runtime first".
        when(botGroupService.findById("g-1")).thenReturn(
                BotGroup.builder().id("g-1").name("Group").environmentId("env-1")
                        .botCount(2).namePrefix("bot").password("pass").build());

        assertThatThrownBy(() -> service.start("g-1"))
                .isInstanceOf(com.vingame.bot.common.exception.BadRequestException.class);

        assertThat(budget.reservations)
                .as("nothing was declared, because nothing was going to be sent")
                .isEmpty();
    }

    @Test
    @DisplayName("the reservation is taken only after the runtime is published, so a /stop can release it")
    void theRuntimeIsPublishedBeforeTheReservation() {
        // A20.4. cancelStartInFlight resolves the environment from runningGroups, so a
        // reservation taken before the runtime exists would be unreleasable by a /stop landing in
        // that window — the reservation would go on shrinking the lower ceilings with nothing left
        // to release it. The budget's 2 x window TTL is the backstop, not the plan.
        when(botGroupService.findById("g-1")).thenReturn(group(2));
        when(botFactory.createBot(anyString(), any())).thenAnswer(inv -> stubBot("bot"));

        service.start("g-1");

        assertThat(budget.runtimePresentAtReservation)
                .as("the runtime must already be in runningGroups when reserve() is called, or "
                        + "cancelStartInFlight cannot find the environment whose scope to cancel")
                .isTrue();
    }

    // ------------------------------------------------------------------ fixtures

    /**
     * A budget that records what was declared and whether it was released, and runs every call
     * immediately. It deliberately does not enforce: this class is about the call site, and the
     * admission arithmetic is covered against the real budget in
     * {@code SlidingWindowGatewayBudgetReservationTest}.
     */
    private final class RecordingBudget implements GatewayBudget {

        record Declared(RequestTier tier, int permits, GatewayRequestScope scope,
                        AtomicBoolean releasedFlag) {
            boolean released() {
                return releasedFlag.get();
            }
        }

        private final List<Declared> reservations = new CopyOnWriteArrayList<>();
        private volatile boolean runtimePresentAtReservation;

        @Override
        public Reservation reserve(RequestTier tier, int permits, GatewayRequestScope scope) {
            runtimePresentAtReservation = runningGroups().containsKey("g-1");
            AtomicBoolean released = new AtomicBoolean();
            reservations.add(new Declared(tier, permits, scope, released));
            return new Reservation() {
                @Override
                public void release() {
                    released.set(true);
                }

                @Override
                public int remaining() {
                    return released.get() ? 0 : permits;
                }
            };
        }

        @Override
        public <T> T execute(RequestTier tier, GatewayRequestScope scope, Callable<T> call) throws Exception {
            return call.call();
        }

        @Override
        public <T> T execute(RequestTier tier, GatewayRequestScope scope, Callable<T> call,
                             Duration maxWait) throws Exception {
            return call.call();
        }

        @Override
        public void run(RequestTier tier, GatewayRequestScope scope, Runnable call) {
            call.run();
        }

        @Override
        public void runWsUpgrade(RequestTier tier, GatewayRequestScope scope, Runnable upgrade) {
            upgrade.run();
        }

        @Override
        public <T> Optional<T> tryExecute(RequestTier tier, GatewayRequestScope scope,
                                          Callable<T> call, Duration maxWait) throws Exception {
            return Optional.ofNullable(call.call());
        }

        @Override
        public void count(String reason) {
        }

        @Override
        public void countWsUpgrade(String reason) {
        }

        @Override
        public void cancelScope(String botGroupId) {
        }

        @Override
        public Snapshot snapshot() {
            return new Snapshot("env-1", "env", "116", GatewayBudgetMode.ENFORCE,
                    0, 900, 0, 0, 0, false);
        }

        @Override
        public boolean countsWsUpgrades() {
            return true;
        }

        @Override
        public Duration registrationMaxWait() {
            return Duration.ofMinutes(15);
        }
    }

    private static BotGroup group(int botCount) {
        return BotGroup.builder()
                .id("g-1").name("Group").environmentId("env-1").gameId("game-1")
                .botCount(botCount).namePrefix("bot").password("pass")
                .build();
    }

    private static Environment environment() {
        return Environment.builder().id("env-1").name("env").customZone(true)
                .miniZoneName("zone").build();
    }

    private static Game game() {
        return Game.builder().id("game-1").name("BauCua").build();
    }

    private static Bot stubBot(String username) {
        Bot b = mock(Bot.class);
        lenient().when(b.getUserName()).thenReturn(username);
        lenient().when(b.getTotalBetsPlaced()).thenReturn(new AtomicLong(0));
        lenient().when(b.getTotalBetAmount()).thenReturn(new AtomicLong(0));
        return b;
    }

    @SuppressWarnings("unchecked")
    private Map<String, BotGroupRuntime> runningGroups() {
        try {
            java.lang.reflect.Field f =
                    BotGroupBehaviorService.class.getDeclaredField("runningGroups");
            f.setAccessible(true);
            return (Map<String, BotGroupRuntime>) f.get(service);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
