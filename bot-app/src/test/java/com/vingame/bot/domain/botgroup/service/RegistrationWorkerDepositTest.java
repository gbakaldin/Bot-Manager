package com.vingame.bot.domain.botgroup.service;

import com.vingame.bot.common.exception.GatewayBudgetExhaustedException;
import com.vingame.bot.common.gateway.RequestTier;
import com.vingame.bot.config.client.EnvironmentClientRegistry;
import com.vingame.bot.config.client.EnvironmentClients;
import com.vingame.bot.domain.botgroup.model.BotGroup;
import com.vingame.bot.domain.botgroup.model.RegistrationState;
import com.vingame.bot.domain.botgroup.repository.BotGroupRepository;
import com.vingame.bot.domain.brand.model.ProductCode;
import com.vingame.bot.domain.environment.model.Environment;
import com.vingame.bot.domain.environment.service.EnvironmentService;
import com.vingame.bot.infrastructure.client.ApiGatewayClient;
import com.vingame.bot.infrastructure.client.dto.DepositOutcome;
import com.vingame.bot.infrastructure.client.dto.RegistrationOutcome;
import com.vingame.bot.infrastructure.gateway.GatewayBudgetRegistry;
import com.vingame.bot.infrastructure.observability.BotMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The registration-time deposit (BOT_PROVISIONING Phase 2, AD-5 / AD-6 / AD-7 / AD-8).
 *
 * <p><b>The property this class exists for: no account is ever funded twice.</b> Everything else
 * — the ordering of marker, send and credit, the outcome table, the crash cases, the operator's
 * resolution, a stale whole-document save — is a way that property could fail, and each has a
 * test here. The last test drives randomized outcomes, crashes and resolutions against a model of
 * the gateway's ledger and asserts every account ends up funded <em>exactly</em> once.
 *
 * <p>No gateway and no Mongo: the client is a mock whose deposit answer runs the worker's marker
 * hook exactly where the real client does (after admission, before the send), the ledger is
 * {@link InMemoryDepositLedger} with the Mongo ledger's conditional semantics, and the group
 * document is a single in-memory object that the worker's {@code $set}s are applied to.
 */
@DisplayName("RegistrationWorker — registration-time deposit")
class RegistrationWorkerDepositTest {

    private static final String GROUP = "group-dep";
    private static final String ENV = "env-1";
    private static final long AMOUNT = 1_000_000L;

    private BotGroupRepository repository;
    private MongoTemplate mongoTemplate;
    private ApiGatewayClient client;
    private SimpleMeterRegistry meters;
    private RegistrationWorker worker;
    private InMemoryDepositLedger ledger;
    private BotGroup group;

    /** Deposit requests that reached the "gateway", per index — the thing that must never exceed 1 per credit. */
    private final Map<Integer, Integer> sends = new ConcurrentHashMap<>();
    /** The outcome to give each send; null = refuse at the budget, before admission. */
    private IntFunction<Step> script;

    /** What one simulated deposit call does. */
    enum Step { CREDIT, REFUSE, NOT_SENT, UNKNOWN, CRASH_AFTER_SEND, BUDGET_REFUSAL }

    @BeforeEach
    void setUp() throws Exception {
        repository = mock(BotGroupRepository.class);
        mongoTemplate = mock(MongoTemplate.class);
        client = mock(ApiGatewayClient.class);
        ledger = new InMemoryDepositLedger();
        meters = new SimpleMeterRegistry();

        when(repository.findByRegistrationState(anyString())).thenAnswer(inv ->
                inv.getArgument(0).equals(group.getRegistrationState()) ? List.of(group) : List.of());
        when(repository.countByRegistrationState(anyString())).thenAnswer(inv ->
                inv.getArgument(0).equals(group.getRegistrationState()) ? 1L : 0L);
        when(repository.findById(anyString())).thenAnswer(inv -> Optional.of(group));
        // Apply every $set the worker issues to the one in-memory document, like Mongo would.
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(BotGroup.class)))
                .thenAnswer(inv -> {
                    apply(inv.getArgument(1));
                    return com.mongodb.client.result.UpdateResult.acknowledged(1, 1L, null);
                });

        EnvironmentClients clients = mock(EnvironmentClients.class);
        when(clients.getApiGatewayClient()).thenReturn(client);
        EnvironmentClientRegistry clientRegistry = mock(EnvironmentClientRegistry.class);
        when(clientRegistry.getClients(ENV)).thenReturn(clients);
        EnvironmentService environmentService = mock(EnvironmentService.class);
        when(environmentService.findById(ENV)).thenReturn(
                Environment.builder().id(ENV).productCode(ProductCode.P_097).build());

        when(client.registrationMaxWait()).thenReturn(Duration.ofMinutes(15));
        when(client.observeModePacing()).thenReturn(Duration.ZERO);
        when(client.hasDisplayNames()).thenReturn(false);
        when(client.registerOne(anyString(), anyString(), anyInt(), any(), any()))
                .thenReturn(RegistrationOutcome.CREATED);
        when(client.depositForRegistration(anyString(), anyLong(), any(), any(), any()))
                .thenAnswer(inv -> {
                    String username = inv.getArgument(0);
                    int index = Integer.parseInt(username.substring("bot".length()));
                    Step step = script.apply(index);
                    if (step == Step.BUDGET_REFUSAL) {
                        // Refused before admission: the real client never runs the hook.
                        throw new GatewayBudgetExhaustedException(RequestTier.DEFAULT, ENV, Duration.ofSeconds(30));
                    }
                    Runnable marker = inv.getArgument(4);
                    try {
                        marker.run();
                    } catch (RuntimeException e) {
                        throw new ApiGatewayClient.DepositMarkerException(username, e);
                    }
                    if (step == Step.NOT_SENT) {
                        return new DepositOutcome.Result(DepositOutcome.NOT_SENT, "stream wait");
                    }
                    ledger.journal.add("send:" + index);
                    sends.merge(index, 1, Integer::sum);
                    onSend(index, step);
                    return switch (step) {
                        case CREDIT -> new DepositOutcome.Result(DepositOutcome.CREDITED, "HTTP 200");
                        case REFUSE -> new DepositOutcome.Result(DepositOutcome.REFUSED, "HTTP 403");
                        case UNKNOWN -> new DepositOutcome.Result(DepositOutcome.UNKNOWN, "HTTP 502");
                        // The process "dies" after the send: nothing the worker does next runs as
                        // designed. Modelled as an exception escaping the client.
                        case CRASH_AFTER_SEND -> throw new IllegalStateException("simulated crash after send");
                        default -> throw new AssertionError(step);
                    };
                });

        // failure backoff 0 so a retry is due on the very next tick; transport budget 10.
        worker = new RegistrationWorker(repository, mongoTemplate, clientRegistry,
                mock(GatewayBudgetRegistry.class), environmentService, new BotMetrics(meters), ledger,
                1, 3, 5, 0, 10, 1);
        group = group(3, AMOUNT);
        script = index -> Step.CREDIT;
    }

    /** Hook for the randomized model test. */
    private void onSend(int index, Step step) {
        if (model != null) {
            model.onSend(index, step);
        }
    }

    private static BotGroup group(int botCount, long initialDeposit) {
        return BotGroup.builder()
                .id(GROUP).name("G").environmentId(ENV).namePrefix("bot").password("pw")
                .botCount(botCount).initialDeposit(initialDeposit)
                .registrationState(RegistrationState.PENDING)
                .createdAt(Instant.now())
                .build();
    }

    private void apply(Update update) {
        Object set = update.getUpdateObject().get("$set");
        if (!(set instanceof Document doc)) {
            return;
        }
        if (doc.containsKey("registrationState")) {
            group.setRegistrationState((String) doc.get("registrationState"));
        }
        if (doc.containsKey("registrationError")) {
            group.setRegistrationError((String) doc.get("registrationError"));
        }
        if (doc.get("registeredCount") instanceof Integer i) {
            group.setRegisteredCount(i);
        }
        if (doc.get("namedCount") instanceof Integer i) {
            group.setNamedCount(i);
        }
        if (doc.get("depositedCount") instanceof Integer i) {
            group.setDepositedCount(i);
        }
        if (doc.containsKey("depositInFlight")) {
            group.setDepositInFlight((Integer) doc.get("depositInFlight"));
        }
    }

    /** Ticks until the group stops being PENDING or {@code max} ticks have run. */
    private void tickUntilSettled(int max) {
        // No enqueue here: enqueue() resets the attempt budget, and the refusal tests count it.
        // Failure backoff is 0 in this fixture, so a retry is due on the next tick anyway.
        for (int i = 0; i < max && RegistrationState.isPending(group.getRegistrationState()); i++) {
            worker.tick();
        }
    }

    private double deposits(String outcome) {
        var c = meters.find(BotMetrics.REGISTRATION_DEPOSITS_TOTAL).tag("outcome", outcome).counter();
        return c == null ? -1 : c.count();
    }

    // ------------------------------------------------------------------ the happy path and the off switch

    @Test
    @DisplayName("each account is registered then funded, in order, and the group completes funded")
    void fundsEveryAccountOnceInOrder() {
        worker.tick();

        assertThat(group.getRegistrationState()).as("complete").isNull();
        assertThat(ledger.read(GROUP)).isEqualTo(new DepositLedger.State(3, null));
        assertThat(group.getDepositedCount()).isEqualTo(3);
        assertThat(sends).containsExactlyInAnyOrderEntriesOf(Map.of(1, 1, 2, 1, 3, 1));
        assertThat(ledger.journal).containsExactly(
                "mark:1", "send:1", "credit:1",
                "mark:2", "send:2", "credit:2",
                "mark:3", "send:3", "credit:3");
        assertThat(deposits("credited")).isEqualTo(3);
        assertThat(deposits("unknown")).isZero();
        assertThat(meters.find(BotMetrics.REGISTRATION_DEPOSIT_AMOUNT_TOTAL).counter().count())
                .isEqualTo(3 * AMOUNT);
    }

    @Test
    @DisplayName("initialDeposit = 0 makes zero deposit calls")
    void zeroAmountMeansNoDeposit() throws Exception {
        group = group(3, 0);

        worker.tick();

        assertThat(group.getRegistrationState()).isNull();
        verify(client, never()).depositForRegistration(anyString(), anyLong(), any(), any(), any());
        assertThat(ledger.journal).isEmpty();
        assertThat(deposits("credited")).as("pre-registered at zero, never incremented").isZero();
    }

    @Test
    @DisplayName("the deposit runs at the registration wait, and a resumed group skips stages already done")
    void resumesAtTheFirstUnfundedIndex() throws Exception {
        // Registered 3, funded 1: the pass funds 2 and 3 and registers nothing.
        group.setRegisteredCount(3);
        ledger.put(GROUP, 1, null);

        worker.tick();

        verify(client, never()).registerOne(anyString(), anyString(), anyInt(), any(), any());
        assertThat(sends).containsOnlyKeys(2, 3);
        verify(client, org.mockito.Mockito.times(2)).depositForRegistration(
                anyString(), eq(AMOUNT), any(), eq(Duration.ofMinutes(15)), any());
    }

    // ------------------------------------------------------------------ AD-6: the marker

    @Test
    @DisplayName("a budget refusal before admission writes no marker and defers without an attempt")
    void budgetRefusalLeavesNoMarker() {
        script = index -> index == 2 ? Step.BUDGET_REFUSAL : Step.CREDIT;

        worker.tick();

        assertThat(ledger.read(GROUP)).isEqualTo(new DepositLedger.State(1, null));
        assertThat(ledger.journal).doesNotContain("mark:2");
        assertThat(group.getRegistrationState()).as("deferred, not failed").isEqualTo(RegistrationState.PENDING);
        assertThat(group.getRegistrationError()).isNull();
    }

    @Test
    @DisplayName("a marker that cannot be written means nothing is sent, and nothing is cleared")
    void markerWriteFailureSendsNothing() {
        script = index -> Step.CREDIT;
        ledger.failNextMark = true;

        worker.tick();

        assertThat(sends).as("no marker, no send").isEmpty();
        assertThat(ledger.journal).containsExactly("mark:1");
        assertThat(group.getRegistrationState()).isEqualTo(RegistrationState.PENDING);

        // And the next pass funds it normally, once.
        tickUntilSettled(5);
        assertThat(sends).containsExactlyInAnyOrderEntriesOf(Map.of(1, 1, 2, 1, 3, 1));
    }

    // ------------------------------------------------------------------ the crash cases

    @Test
    @DisplayName("CRASH: a group whose ledger carries a marker is failed on selection and never sent to")
    void markerOnSelectionFailsWithoutSending() throws Exception {
        // Exactly what a process death between the send for index 2 and its credit write leaves.
        group.setRegisteredCount(3);
        ledger.put(GROUP, 1, 2);

        worker.tick();

        verify(client, never()).depositForRegistration(anyString(), anyLong(), any(), any(), any());
        verify(client, never()).registerOne(anyString(), anyString(), anyInt(), any(), any());
        assertThat(group.getRegistrationState()).isEqualTo(RegistrationState.FAILED);
        assertThat(group.getRegistrationError())
                .contains("deposit outcome unknown for bot2")
                .contains("index 2")
                .contains("amount " + AMOUNT)
                .contains("depositOutcome=credited|not-credited");
        assertThat(group.getDepositInFlight()).as("mirrored for GET /{id}").isEqualTo(2);
        assertThat(ledger.read(GROUP)).as("the marker is left exactly where it is")
                .isEqualTo(new DepositLedger.State(1, 2));
    }

    @Test
    @DisplayName("CRASH: whatever happens after the send, that index is never sent again")
    void crashAfterSendIsNeverResent() {
        script = index -> index == 2 ? Step.CRASH_AFTER_SEND : Step.CREDIT;

        // Many ticks: the first sends 1 and 2 and "crashes"; every later one must refuse to send.
        for (int i = 0; i < 10; i++) {
            worker.enqueue(GROUP);
            worker.tick();
        }

        assertThat(sends.get(2)).as("index 2 was sent exactly once").isEqualTo(1);
        assertThat(sends).doesNotContainKey(3);
        assertThat(group.getRegistrationState()).isEqualTo(RegistrationState.FAILED);
        assertThat(ledger.read(GROUP)).isEqualTo(new DepositLedger.State(1, 2));
    }

    @Test
    @DisplayName("CRASH: credited, but the credit write fails — the group stops, the index is not re-sent")
    void creditWriteFailureStopsTheGroup() {
        ledger.failNextCredit = true;

        tickUntilSettled(5);

        assertThat(sends).containsExactly(Map.entry(1, 1));
        assertThat(group.getRegistrationState()).isEqualTo(RegistrationState.FAILED);
        assertThat(group.getRegistrationError()).contains("HTTP 200 but recording it failed");
        assertThat(ledger.read(GROUP).depositInFlight()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ AD-7: each outcome

    @Test
    @DisplayName("REFUSED: marker cleared, an attempt charged, retried, FAILED after max attempts")
    void refusedIsRetriedThenFails() {
        script = index -> index == 2 ? Step.REFUSE : Step.CREDIT;

        tickUntilSettled(10);

        assertThat(sends.get(2)).as("max-attempts-per-user = 3").isEqualTo(3);
        assertThat(ledger.read(GROUP)).as("never credited, marker cleared each time")
                .isEqualTo(new DepositLedger.State(1, null));
        assertThat(group.getRegistrationState()).isEqualTo(RegistrationState.FAILED);
        assertThat(group.getRegistrationError()).contains("deposit refused for bot2");
        assertThat(group.getDepositInFlight()).as("a refusal is not an unknown outcome").isNull();
        assertThat(deposits("refused")).isEqualTo(3);
    }

    @Test
    @DisplayName("NOT_SENT: marker cleared, a transport attempt charged, and the next pass funds it once")
    void notSentIsATransportAttempt() {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        script = index -> index == 1 && calls.getAndIncrement() == 0 ? Step.NOT_SENT : Step.CREDIT;

        tickUntilSettled(5);

        assertThat(group.getRegistrationState()).isNull();
        assertThat(sends).containsExactlyInAnyOrderEntriesOf(Map.of(1, 1, 2, 1, 3, 1));
        assertThat(deposits("not_sent")).isEqualTo(1);
    }

    @Test
    @DisplayName("UNKNOWN: marker kept, FAILED immediately, one ERROR, never retried")
    void unknownFailsImmediately() {
        script = index -> index == 2 ? Step.UNKNOWN : Step.CREDIT;

        worker.tick();
        // The fixture would select it again if it were PENDING; it must not be.
        worker.enqueue(GROUP);
        worker.tick();

        assertThat(sends.get(2)).isEqualTo(1);
        assertThat(group.getRegistrationState()).isEqualTo(RegistrationState.FAILED);
        assertThat(group.getRegistrationError()).contains("deposit outcome unknown for bot2").contains("HTTP 502");
        assertThat(ledger.read(GROUP)).isEqualTo(new DepositLedger.State(1, 2));
        assertThat(deposits("unknown")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ AD-8: the operator's answer

    @Test
    @DisplayName("resolved as credited: the index is never sent again; the rest are funded")
    void resolvedCreditedSkipsTheIndex() {
        group.setRegisteredCount(3);
        group.setRegistrationState(RegistrationState.FAILED);
        ledger.put(GROUP, 1, 2);

        assertThat(ledger.resolve(GROUP, 2, true)).isTrue();
        group.setRegistrationState(RegistrationState.PENDING);
        tickUntilSettled(5);

        assertThat(sends).containsOnlyKeys(3);
        assertThat(ledger.read(GROUP)).isEqualTo(new DepositLedger.State(3, null));
        assertThat(group.getRegistrationState()).isNull();
    }

    @Test
    @DisplayName("resolved as not credited: the index is sent exactly once more")
    void resolvedNotCreditedResendsOnce() {
        group.setRegisteredCount(3);
        group.setRegistrationState(RegistrationState.FAILED);
        ledger.put(GROUP, 1, 2);

        assertThat(ledger.resolve(GROUP, 2, false)).isTrue();
        group.setRegistrationState(RegistrationState.PENDING);
        tickUntilSettled(5);

        assertThat(sends).containsExactlyInAnyOrderEntriesOf(Map.of(2, 1, 3, 1));
        assertThat(ledger.read(GROUP)).isEqualTo(new DepositLedger.State(3, null));
    }

    // ------------------------------------------------------------------ the stale-save race

    @Test
    @DisplayName("a stale whole-document save that reverts depositedCount does not cause a second deposit")
    void staleDocumentDoesNotDrive() {
        // A PATCH read the group before indices 1-2 were funded and wrote it back after: the
        // document says 0, the ledger says 2. The plan put the counter on the document; this is
        // why the worker reads the ledger instead.
        group.setRegisteredCount(3);
        group.setDepositedCount(0);
        ledger.put(GROUP, 2, null);

        worker.tick();

        assertThat(sends).containsOnlyKeys(3);
    }

    @Test
    @DisplayName("a raise mid-job funds only the new indices, each once")
    void raiseMidJobFundsTheNewIndicesOnce() {
        java.util.concurrent.atomic.AtomicBoolean raised = new java.util.concurrent.atomic.AtomicBoolean();
        script = index -> {
            if (index == 2 && raised.compareAndSet(false, true)) {
                group.setBotCount(5);
            }
            return Step.CREDIT;
        };

        tickUntilSettled(5);

        assertThat(sends).containsExactlyInAnyOrderEntriesOf(Map.of(1, 1, 2, 1, 3, 1, 4, 1, 5, 1));
        assertThat(group.getRegistrationState()).isNull();
    }

    // ------------------------------------------------------------------ the model test

    /**
     * A model of what the gateway actually did: every send that could have credited is decided
     * here (a CRASH or an UNKNOWN credits with probability 1/2), and the "operator" answers with
     * that truth — exactly what checking the account's balance gives a human.
     */
    private Model model;

    private static final class Model {
        final Random random;
        final Map<Integer, Integer> credits = new ConcurrentHashMap<>();
        final Map<Integer, Boolean> truthOfLastAmbiguousSend = new ConcurrentHashMap<>();

        Model(long seed) {
            random = new Random(seed);
        }

        void onSend(int index, Step step) {
            boolean credited = switch (step) {
                case CREDIT -> true;
                case UNKNOWN, CRASH_AFTER_SEND -> {
                    boolean truth = random.nextBoolean();
                    truthOfLastAmbiguousSend.put(index, truth);
                    yield truth;
                }
                default -> false;
            };
            if (credited) {
                credits.merge(index, 1, Integer::sum);
            }
        }
    }

    @RepeatedTest(25)
    @DisplayName("MODEL: random outcomes, crashes and operator resolutions fund every account exactly once")
    void everyAccountIsFundedExactlyOnce(org.junit.jupiter.api.RepetitionInfo repetition) {
        model = new Model(7919L * repetition.getCurrentRepetition());
        Random random = model.random;
        int accounts = 12;
        group = group(accounts, AMOUNT);
        Step[] steps = Step.values();
        script = index -> steps[random.nextInt(steps.length)];

        for (int round = 0; round < 400 && group.getRegistrationState() != null; round++) {
            if (RegistrationState.isFailed(group.getRegistrationState())) {
                // The operator: resolve an unknown outcome with the truth, or plain-retry.
                Integer inFlight = ledger.read(GROUP).depositInFlight();
                if (inFlight != null) {
                    boolean truth = model.truthOfLastAmbiguousSend.getOrDefault(inFlight, false);
                    assertThat(ledger.resolve(GROUP, inFlight, truth)).isTrue();
                }
                group.setRegistrationState(RegistrationState.PENDING);
                group.setRegistrationError(null);
            }
            if (round % 2 == 0) {
                worker.enqueue(GROUP);  // clears a budget deferral; odd rounds let refusals accumulate
            }
            worker.tick();
        }

        assertThat(group.getRegistrationState()).as("the job finished").isNull();
        for (int i = 1; i <= accounts; i++) {
            assertThat(model.credits.getOrDefault(i, 0))
                    .as("account %d funded exactly once (sends %s, credits %s)", i, sends, model.credits)
                    .isEqualTo(1);
        }
        assertThat(ledger.read(GROUP)).isEqualTo(new DepositLedger.State(accounts, null));
    }
}
