package com.vingame.bot.domain.botgroup.service;

import com.vingame.bot.common.exception.GatewayBudgetExhaustedException;
import com.vingame.bot.common.gateway.GatewayRequestScope;
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
import com.vingame.bot.infrastructure.client.dto.RegistrationOutcome;
import com.vingame.bot.infrastructure.gateway.GatewayBudget;
import com.vingame.bot.infrastructure.gateway.GatewayBudgetRegistry;
import com.vingame.bot.infrastructure.observability.BotMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RegistrationWorker} — asynchronous account registration
 * (GATEWAY_REQUEST_BUDGET Phase 4, A2 / A28).
 *
 * <p><b>No gateway is ever touched.</b> {@link ApiGatewayClient} is a mock throughout, so no
 * socket is opened at all. That rule is absolute on this branch: a Cloudflare block has no
 * tolerable cooldown — possibly ~24 hours, possibly until someone clears it by hand — so a test
 * that fired real registration traffic could take a brand's staging down for a day.
 *
 * <p>The tick is driven directly rather than through the scheduler, because every property here
 * is about <em>what one pass does</em> and a fixed-delay scheduler would only add flakiness to
 * the question.
 */
@DisplayName("RegistrationWorker")
class RegistrationWorkerTest {

    private static final String GROUP = "group-1";
    private static final String ENV = "env-1";

    private BotGroupRepository repository;
    private MongoTemplate mongoTemplate;
    private ApiGatewayClient client;
    private GatewayBudgetRegistry budgetRegistry;
    private BotMetrics metrics;
    private SimpleMeterRegistry meterRegistry;
    private RegistrationWorker worker;

    /** Every {@code $set} the worker issued, in order — the persisted-progress ledger. */
    private final List<Update> updates = new ArrayList<>();

    @BeforeEach
    void setUp() {
        repository = mock(BotGroupRepository.class);
        mongoTemplate = mock(MongoTemplate.class);
        client = mock(ApiGatewayClient.class);
        budgetRegistry = mock(GatewayBudgetRegistry.class);
        meterRegistry = new SimpleMeterRegistry();
        metrics = new BotMetrics(meterRegistry);

        EnvironmentClients clients = mock(EnvironmentClients.class);
        when(clients.getApiGatewayClient()).thenReturn(client);
        EnvironmentClientRegistry clientRegistry = mock(EnvironmentClientRegistry.class);
        when(clientRegistry.getClients(ENV)).thenReturn(clients);

        EnvironmentService environmentService = mock(EnvironmentService.class);
        when(environmentService.findById(ENV)).thenReturn(
                Environment.builder().id(ENV).productCode(ProductCode.P_116).build());

        when(client.registrationMaxWait()).thenReturn(Duration.ofMinutes(15));
        when(client.observeModePacing()).thenReturn(Duration.ZERO);
        when(client.hasDisplayNames()).thenReturn(false);

        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(BotGroup.class)))
                .thenAnswer(inv -> {
                    updates.add(inv.getArgument(1));
                    return null;
                });

        worker = new RegistrationWorker(repository, mongoTemplate, clientRegistry, budgetRegistry,
                environmentService, metrics, 10, 3, 5, 30);
    }

    private BotGroup group(int botCount, int registered, int named) {
        return BotGroup.builder()
                .id(GROUP).name("G").environmentId(ENV)
                .namePrefix("bot").password("pw")
                .botCount(botCount)
                .registeredCount(registered)
                .namedCount(named)
                .registrationState(RegistrationState.PENDING)
                .createdAt(Instant.now())
                .build();
    }

    private void pending(BotGroup... groups) {
        when(repository.findByRegistrationState(RegistrationState.PENDING))
                .thenReturn(List.of(groups));
        when(repository.findByRegistrationState(RegistrationState.FAILED)).thenReturn(List.of());
    }

    private double counter(String outcome) {
        var found = meterRegistry.find(BotMetrics.REGISTRATION_ACCOUNTS_TOTAL)
                .tag("outcome", outcome).counter();
        return found == null ? -1 : found.count();
    }

    /** The last value {@code field} was {@code $set} to, or -1 if it never was. */
    private int lastInt(String field) {
        int value = -1;
        for (Update update : updates) {
            Object set = update.getUpdateObject().get("$set");
            if (set instanceof org.bson.Document doc && doc.get(field) instanceof Integer i) {
                value = i;
            }
        }
        return value;
    }

    private Object lastValue(String field) {
        Object value = null;
        boolean seen = false;
        for (Update update : updates) {
            Object set = update.getUpdateObject().get("$set");
            if (set instanceof org.bson.Document doc && doc.containsKey(field)) {
                value = doc.get(field);
                seen = true;
            }
        }
        return seen ? value : "<never set>";
    }

    @Test
    @DisplayName("registers every index in order and clears the state when the group is complete")
    void registersEveryIndexInOrderThenCompletes() throws Exception {
        pending(group(3, 0, 0));
        when(client.registerOne(anyString(), anyString(), anyInt(), any(), any()))
                .thenReturn(RegistrationOutcome.CREATED);

        worker.tick();

        ArgumentCaptor<Integer> indices = ArgumentCaptor.forClass(Integer.class);
        verify(client, org.mockito.Mockito.times(3))
                .registerOne(eq("bot"), eq("pw"), indices.capture(), any(), any());
        // In order, from 1, with no gaps. Skipping an index would make registeredCount stop
        // meaning "indices 1..k exist", which is the invariant every resume in this class rests
        // on — so the ORDER is the assertion, not merely the count.
        assertThat(indices.getAllValues()).containsExactly(1, 2, 3);
        assertThat(lastInt("registeredCount")).isEqualTo(3);
        assertThat(lastValue("registrationState"))
                .as("a complete group carries no registration state at all — byte-for-byte what a "
                        + "synchronously registered group has looked like since day one")
                .isNull();
        assertThat(counter("success")).isEqualTo(3);
    }

    @Test
    @DisplayName("resumes from registeredCount rather than starting over — the restart case")
    void resumesFromTheHighWaterMark() throws Exception {
        // What a JVM restart mid-registration leaves behind: the document says 2 of 5 exist.
        pending(group(5, 2, 0));
        when(client.registerOne(anyString(), anyString(), anyInt(), any(), any()))
                .thenReturn(RegistrationOutcome.CREATED);

        worker.tick();

        ArgumentCaptor<Integer> indices = ArgumentCaptor.forClass(Integer.class);
        verify(client, org.mockito.Mockito.times(3))
                .registerOne(anyString(), anyString(), indices.capture(), any(), any());
        assertThat(indices.getAllValues())
                .as("indices 1 and 2 already exist; re-creating them would spend the Cloudflare "
                        + "window on accounts that are already there")
                .containsExactly(3, 4, 5);
    }

    @Test
    @DisplayName("an already-exists envelope advances the counter and is not a failure")
    void alreadyExistsIsSuccessEquivalent() throws Exception {
        pending(group(2, 0, 0));
        when(client.registerOne(anyString(), anyString(), eq(1), any(), any()))
                .thenReturn(RegistrationOutcome.ALREADY_EXISTED);
        when(client.registerOne(anyString(), anyString(), eq(2), any(), any()))
                .thenReturn(RegistrationOutcome.CREATED);

        worker.tick();

        // This is the whole of A2.2's caveat: "a retry of index k is idempotent" is a statement
        // about OUR state, and it only becomes true of the gateway because EXISTED is read as
        // done. Read as a failure instead, a resumed registration would stop on the very index it
        // was interrupted on, every single time.
        assertThat(lastInt("registeredCount")).isEqualTo(2);
        assertThat(counter("exists")).isEqualTo(1);
        assertThat(counter("success")).isEqualTo(1);
        assertThat(counter("failed")).isZero();
    }

    @Test
    @DisplayName("a budget refusal re-queues the group and consumes no attempt")
    void aBudgetRefusalCostsNothing() throws Exception {
        pending(group(5, 0, 0));
        when(client.registerOne(anyString(), anyString(), anyInt(), any(), any()))
                .thenThrow(new GatewayBudgetExhaustedException(RequestTier.DEFAULT, ENV, null));

        // Three passes. Under max-attempts-per-user=3 a failure classification would have marked
        // the group FAILED by now.
        worker.tick();
        worker.tick();
        worker.tick();

        assertThat(lastValue("registrationState"))
                .as("A2.6: a budget timeout or an open circuit says 'not now', not 'this account "
                        + "cannot be created'. A group that went FAILED because the fleet was busy "
                        + "would need a human for no reason at all.")
                .isEqualTo("<never set>");
        assertThat(counter("failed"))
                .as("our own pacing must not be countable as an upstream refusal — that "
                        + "conflation is what produced a 502 about a healthy gateway")
                .isZero();
        assertThat(counter("success")).isZero();
    }

    @Test
    @DisplayName("a gateway refusal costs an attempt, and the budget spent marks the group FAILED")
    void aGatewayRefusalSpendsTheAttemptBudget() throws Exception {
        pending(group(5, 0, 0));
        when(client.registerOne(anyString(), anyString(), anyInt(), any(), any()))
                .thenThrow(new RuntimeException("Registration failed for bot1: Username is not allowed"));

        // failure-backoff is 30 s, so drive the passes directly rather than waiting: each tick
        // after the first is skipped by the backoff, which is itself the point of the third
        // assertion below.
        worker.tick();
        assertThat(lastValue("registrationState")).isEqualTo("<never set>");

        setBackoffElapsed();
        worker.tick();
        assertThat(lastValue("registrationState")).isEqualTo("<never set>");

        setBackoffElapsed();
        worker.tick();

        assertThat(lastValue("registrationState")).isEqualTo(RegistrationState.FAILED);
        assertThat(lastValue("registrationError"))
                .asString()
                .as("the operator has to be able to tell WHICH account stopped it without "
                        + "reading the log")
                .contains("account 1 of 5")
                .contains("bot1")
                .contains("Username is not allowed");
        assertThat(counter("failed")).isEqualTo(3);
    }

    @Test
    @DisplayName("a refused account backs off instead of hot-looping the gateway")
    void aRefusedAccountBacksOff() throws Exception {
        pending(group(5, 0, 0));
        when(client.registerOne(anyString(), anyString(), anyInt(), any(), any()))
                .thenThrow(new RuntimeException("nope"));

        worker.tick();
        worker.tick();
        worker.tick();

        // Three passes, one request: the backoff is what stops a group that a gateway keeps
        // refusing from spending its whole attempt budget inside a second — and, worse, from
        // hammering an edge that has just said no, which is how a brand gets blocked.
        verify(client, org.mockito.Mockito.times(1))
                .registerOne(anyString(), anyString(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("the scope carries the group id, so a DELETE can call a queued request off")
    void theScopeCarriesTheGroupId() throws Exception {
        pending(group(1, 0, 0));
        when(client.registerOne(anyString(), anyString(), anyInt(), any(), any()))
                .thenReturn(RegistrationOutcome.CREATED);

        worker.tick();

        ArgumentCaptor<GatewayRequestScope> scope = ArgumentCaptor.forClass(GatewayRequestScope.class);
        verify(client).registerOne(anyString(), anyString(), anyInt(), scope.capture(), any());
        // A28.6. GatewayBudget.cancelScope keys on botGroupId and nothing else, so a group-less
        // scope makes a queued registration unreachable by the DELETE that wants the group gone —
        // which then waits out up to fifteen minutes of registration wait on an HTTP thread.
        assertThat(scope.getValue().botGroupId()).isEqualTo(GROUP);
        assertThat(scope.getValue().isCancelled()).isFalse();
    }

    @Test
    @DisplayName("cancel() stops the loop between accounts and wakes a request already parked")
    void cancelStopsTheLoopAndWakesTheBudget() throws Exception {
        GatewayBudget budget = mock(GatewayBudget.class);
        when(budgetRegistry.find(ENV)).thenReturn(budget);

        worker.cancel(GROUP, ENV);
        pending(group(5, 0, 0));
        worker.tick();

        // Both halves. The flag alone cannot reach a request that is already parked INSIDE the
        // budget; cancelScope alone cannot stop the next account being started.
        verify(budget).cancelScope(GROUP);
        verify(client, never()).registerOne(anyString(), anyString(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("one group at a time — a second pending group is not interleaved")
    void theWorkerIsSerial() throws Exception {
        BotGroup first = group(2, 0, 0);
        BotGroup second = BotGroup.builder()
                .id("group-2").name("H").environmentId(ENV).namePrefix("other").password("pw")
                .botCount(2).registrationState(RegistrationState.PENDING)
                .createdAt(Instant.now().plusSeconds(60))
                .build();
        pending(first, second);
        when(client.registerOne(anyString(), anyString(), anyInt(), any(), any()))
                .thenReturn(RegistrationOutcome.CREATED);

        worker.tick();

        ArgumentCaptor<String> prefixes = ArgumentCaptor.forClass(String.class);
        verify(client, org.mockito.Mockito.times(2))
                .registerOne(prefixes.capture(), anyString(), anyInt(), any(), any());
        // Serial and in-order is a CORRECTNESS property, not politeness: registeredCount = k only
        // means "indices 1..k are done" while one group's indices are worked one at a time, and
        // every resume in this class reads it that way. Oldest createdAt first, so a 500-account
        // group cannot be starved by a stream of small ones.
        assertThat(prefixes.getAllValues()).containsExactly("bot", "bot");
    }

    @Test
    @DisplayName("with a display-name pool, each index is registered and then named")
    void namesEachAccountWhenAPoolExists() throws Exception {
        when(client.hasDisplayNames()).thenReturn(true);
        when(client.setDisplayNameWithRetry(anyString(), anyInt(), any(), any()))
                .thenReturn("Gấu Bự");
        pending(group(2, 0, 0));
        when(client.registerOne(anyString(), anyString(), anyInt(), any(), any()))
                .thenReturn(RegistrationOutcome.CREATED);

        worker.tick();

        verify(client).setDisplayNameWithRetry(eq("bot1"), eq(5), any(), any());
        verify(client).setDisplayNameWithRetry(eq("bot2"), eq(5), any(), any());
        assertThat(lastInt("namedCount")).isEqualTo(2);
    }

    @Test
    @DisplayName("an index that registered but was never named resumes at update-fullname only")
    void resumesTheNamingHalfWithoutReRegistering() throws Exception {
        when(client.hasDisplayNames()).thenReturn(true);
        when(client.setDisplayNameWithRetry(anyString(), anyInt(), any(), any())).thenReturn("Name");
        // The interrupted state A17.3 exists to express: 2 accounts created, 1 named.
        pending(group(2, 2, 1));

        worker.tick();

        // A30: two counters, and the resume costs ONE request — not the register + login +
        // update-fullname the plan budgeted, because update-fullname needs no session token in
        // this codebase. Re-registering here would be a wasted request per resumed index.
        verify(client, never()).registerOne(anyString(), anyString(), anyInt(), any(), any());
        verify(client).setDisplayNameWithRetry(eq("bot2"), anyInt(), any(), any());
        assertThat(lastInt("namedCount")).isEqualTo(2);
    }

    @Test
    @DisplayName("with no display-name pool the group still completes, and namedCount stays 0")
    void completesWithoutANamePool() throws Exception {
        pending(group(2, 0, 0));
        when(client.registerOne(anyString(), anyString(), anyInt(), any(), any()))
                .thenReturn(RegistrationOutcome.CREATED);

        worker.tick();

        verify(client, never()).setDisplayNameWithRetry(anyString(), anyInt(), any(), any());
        assertThat(lastValue("registrationState")).isNull();
        assertThat(lastInt("namedCount"))
                .as("0, not registeredCount: claiming N accounts were named when there was no "
                        + "pool to name them from would be a worse lie than an honest zero")
                .isZero();
    }

    @Test
    @DisplayName("observe mode paces the worker; enforce mode does not")
    void observeModePacingIsApplied() throws Exception {
        when(client.observeModePacing()).thenReturn(Duration.ofMillis(120));
        pending(group(3, 0, 0));
        when(client.registerOne(anyString(), anyString(), anyInt(), any(), any()))
                .thenReturn(RegistrationOutcome.CREATED);

        long start = System.nanoTime();
        worker.tick();
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        // A2.3: one serial worker at ~100-300 ms per call is ~200-600 requests per window on its
        // own, and in observe mode NOTHING else paces it. Three accounts at 120 ms is 360 ms;
        // asserted loosely because this is about the sleep happening at all.
        assertThat(elapsedMillis)
                .as("the one self-paced caller in the system must actually sleep")
                .isGreaterThanOrEqualTo(300);
    }

    @Test
    @DisplayName("the gauges track the persisted state, not an in-memory queue")
    void gaugesReadThePersistedState() {
        when(repository.findByRegistrationState(RegistrationState.PENDING))
                .thenReturn(List.of(group(1, 1, 0)));
        when(repository.findByRegistrationState(RegistrationState.FAILED))
                .thenReturn(List.of(group(1, 0, 0), group(1, 0, 0)));

        worker.tick();

        // registration_failed_groups is what RegistrationStalled reads. Driving it from Mongo
        // rather than from memory is what makes a group that failed in a PREVIOUS JVM visible —
        // and that is the group with the longest time-to-notice, so it is the one the alert must
        // not miss.
        assertThat(worker.getPendingGroupCount()).isEqualTo(1);
        assertThat(worker.getFailedGroupCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("the three outcome series exist at zero before any of them moves")
    void outcomeSeriesArePreRegistered() throws Exception {
        pending(group(1, 0, 0));
        when(client.registerOne(anyString(), anyString(), anyInt(), any(), any()))
                .thenReturn(RegistrationOutcome.CREATED);

        worker.tick();

        // A counter registered lazily at increment time has 1 as its FIRST scraped sample, and
        // increase() over samples that are all 1 is last - first == 0 — Prometheus' counter-start
        // extrapolation does not rescue it either, being gated on resultValue > 0. So a rule
        // reading increase(...{outcome="failed"}[15m]) > 0 could never fire on a group's first
        // failed account. CLAUDE.md records the same trap for group_recovery_*.
        assertThat(counter("failed"))
                .as("must exist at 0, not be absent")
                .isZero();
        assertThat(counter("exists")).isZero();
        assertThat(counter("success")).isEqualTo(1);
    }

    /** Pretend the failure backoff has elapsed, so the next tick reconsiders the group. */
    private void setBackoffElapsed() {
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> attempts = (java.util.Map<String, Object>)
                org.springframework.test.util.ReflectionTestUtils.getField(worker, "attempts");
        Object attempt = attempts.get(GROUP);
        if (attempt == null) {
            return;
        }
        int index = (int) org.springframework.test.util.ReflectionTestUtils
                .invokeGetterMethod(attempt, "index");
        int count = (int) org.springframework.test.util.ReflectionTestUtils
                .invokeGetterMethod(attempt, "count");
        try {
            var ctor = attempt.getClass().getDeclaredConstructor(int.class, int.class, Instant.class);
            ctor.setAccessible(true);
            attempts.put(GROUP, ctor.newInstance(index, count, Instant.now().minusSeconds(1)));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
