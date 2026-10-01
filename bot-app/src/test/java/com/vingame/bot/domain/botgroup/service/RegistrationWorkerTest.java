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
    /** A second environment, so "one starved environment" can be told from "the worker stopped". */
    private static final String OTHER_ENV = "env-2";

    private BotGroupRepository repository;
    private MongoTemplate mongoTemplate;
    private ApiGatewayClient client;
    private ApiGatewayClient otherClient;
    private GatewayBudgetRegistry budgetRegistry;
    private BotMetrics metrics;
    private SimpleMeterRegistry meterRegistry;
    private RegistrationWorker worker;

    /** Every {@code $set} the worker issued, in order — the persisted-progress ledger. */
    private final List<Update> updates = new ArrayList<>();

    @BeforeEach
    void setUp() {
        repository = mock(BotGroupRepository.class);
        // The worker re-reads its target per index and treats a MISSING document as deleted
        // (review-phase4-fixround), so the fixture's repository answers findById the way Mongo
        // would: with whatever this test has made PENDING.
        when(repository.findById(anyString())).thenAnswer(inv -> repository
                .findByRegistrationState(RegistrationState.PENDING).stream()
                .filter(g -> g.getId().equals(inv.getArgument(0)))
                .findFirst());
        mongoTemplate = mock(MongoTemplate.class);
        client = mock(ApiGatewayClient.class);
        budgetRegistry = mock(GatewayBudgetRegistry.class);
        meterRegistry = new SimpleMeterRegistry();
        metrics = new BotMetrics(meterRegistry);

        otherClient = mock(ApiGatewayClient.class);

        EnvironmentClients clients = mock(EnvironmentClients.class);
        when(clients.getApiGatewayClient()).thenReturn(client);
        EnvironmentClients otherClients = mock(EnvironmentClients.class);
        when(otherClients.getApiGatewayClient()).thenReturn(otherClient);
        EnvironmentClientRegistry clientRegistry = mock(EnvironmentClientRegistry.class);
        when(clientRegistry.getClients(ENV)).thenReturn(clients);
        when(clientRegistry.getClients(OTHER_ENV)).thenReturn(otherClients);

        EnvironmentService environmentService = mock(EnvironmentService.class);
        when(environmentService.findById(ENV)).thenReturn(
                Environment.builder().id(ENV).productCode(ProductCode.P_116).build());
        when(environmentService.findById(OTHER_ENV)).thenReturn(
                Environment.builder().id(OTHER_ENV).productCode(ProductCode.P_097).build());

        for (ApiGatewayClient each : List.of(client, otherClient)) {
            when(each.registrationMaxWait()).thenReturn(Duration.ofMinutes(15));
            when(each.observeModePacing()).thenReturn(Duration.ZERO);
            when(each.hasDisplayNames()).thenReturn(false);
        }

        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(BotGroup.class)))
                .thenAnswer(inv -> {
                    updates.add(inv.getArgument(1));
                    // A real MongoTemplate never returns null, and recordCompletion reads
                    // getMatchedCount() to decide whether the document was still complete
                    // (QA F-1). "1 matched" is the normal case; the tests that care about the
                    // other one stub it themselves.
                    return com.mongodb.client.result.UpdateResult.acknowledged(1, 1L, null);
                });

        worker = new RegistrationWorker(repository, mongoTemplate, clientRegistry, budgetRegistry,
                environmentService, metrics, 10, 3, 5, 30, 10, 60);
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

    /**
     * The two budget refusals the worker can meet: our own window (Phase 3) and an open Cloudflare
     * circuit (Phase 5, A31.8). The second is the one that matters most — a block may last a day —
     * and it reaches the worker through the same {@code GatewayBudgetException} arm, so it must cost
     * the same nothing.
     */
    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> budgetRefusals() {
        return java.util.stream.Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("window exhausted",
                        new GatewayBudgetExhaustedException(RequestTier.DEFAULT, ENV, null)),
                org.junit.jupiter.params.provider.Arguments.of("circuit open (Cloudflare edge block)",
                        new com.vingame.bot.common.exception.GatewayCircuitOpenException(
                                ENV, "a3c7e4004acc850e-HKG", Duration.ofMinutes(60))));
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.MethodSource("budgetRefusals")
    @DisplayName("a budget refusal re-queues the group and consumes no attempt")
    void aBudgetRefusalCostsNothing(String kind,
                                    com.vingame.bot.common.exception.GatewayBudgetException refusal)
            throws Exception {
        pending(group(5, 0, 0));
        when(client.registerOne(anyString(), anyString(), anyInt(), any(), any()))
                .thenThrow(refusal);

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
        verify(client, org.mockito.Mockito.atLeastOnce())
                .registerOne(anyString(), anyString(), anyInt(), any(), any());
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

    // ------------------------------------------------------------ RIK review G1: name exhaustion

    @Test
    @DisplayName("a name that ran out of re-rolls does NOT advance namedCount, and the group is not complete")
    void nameExhaustionDoesNotAdvanceNamedCount() throws Exception {
        // RIK review G1. namedCount used to advance anyway, so a nameless account was invisible to
        // the counter, to /registration/retry and to completion — and one nameless account in a
        // RIK ziczac room stalls the round for everyone in it.
        when(client.hasDisplayNames()).thenReturn(true);
        when(client.setDisplayNameWithRetry(eq("bot1"), anyInt(), any(), any())).thenReturn(null);
        when(client.registerOne(anyString(), anyString(), anyInt(), any(), any()))
                .thenReturn(RegistrationOutcome.CREATED);
        pending(group(3, 0, 0));

        worker.tick();

        assertThat(lastInt("registeredCount")).as("the account itself exists").isEqualTo(1);
        assertThat(lastInt("namedCount")).as("but it has no name, and the counter says so").isZero();
        assertThat(lastValue("registrationState"))
                .as("not complete — the pass stopped at the nameless index")
                .isEqualTo("<never set>");
        assertThat(counter("failed")).as("charged as an attempt, not silently skipped").isEqualTo(1);
        verify(client, never()).registerOne(anyString(), anyString(), eq(2), any(), any());
    }

    @Test
    @DisplayName("the next pass re-rolls names for the same account without re-registering it, and completes")
    void aLaterPassNamesTheAccountAndCompletes() throws Exception {
        when(client.hasDisplayNames()).thenReturn(true);
        when(client.setDisplayNameWithRetry(anyString(), anyInt(), any(), any()))
                .thenReturn(null)          // pass 1: bot1 exhausts
                .thenReturn("Gấu Bự");     // pass 2 onwards: names land
        when(client.registerOne(anyString(), anyString(), anyInt(), any(), any()))
                .thenReturn(RegistrationOutcome.CREATED);
        pending(group(3, 0, 0));

        worker.tick();
        // What the document now holds: bot1 registered (persisted by the pass), nobody named.
        pending(group(3, lastInt("registeredCount"), lastInt("namedCount")));
        setBackoffElapsed();
        worker.tick();

        verify(client, org.mockito.Mockito.times(1))
                .registerOne(anyString(), anyString(), eq(1), any(), any());
        verify(client, org.mockito.Mockito.times(2))
                .setDisplayNameWithRetry(eq("bot1"), anyInt(), any(), any());
        assertThat(lastInt("namedCount")).isEqualTo(3);
        assertThat(lastValue("registrationState")).as("complete only once every account is named").isNull();
    }

    @Test
    @DisplayName("an account that stays nameless stops the group REGISTRATION_FAILED, naming it")
    void repeatedNameExhaustionFailsTheGroup() throws Exception {
        when(client.hasDisplayNames()).thenReturn(true);
        when(client.setDisplayNameWithRetry(anyString(), anyInt(), any(), any())).thenReturn(null);
        when(client.registerOne(anyString(), anyString(), anyInt(), any(), any()))
                .thenReturn(RegistrationOutcome.CREATED);
        pending(group(3, 0, 0));

        for (int i = 0; i < 3; i++) {
            worker.tick();
            setBackoffElapsed();
        }

        assertThat(lastValue("registrationState")).isEqualTo(RegistrationState.FAILED);
        assertThat(lastValue("registrationError")).asString()
                .contains("account 1 of 3")
                .contains("bot1")
                .contains("nameless")
                .contains("3 attempts")
                .doesNotContain("transport");
        assertThat(lastInt("namedCount")).isZero();
    }

    @Test
    @DisplayName("/registration/retry revisits the nameless account (resume from namedCount + 1)")
    void retryRevisitsTheNamelessAccount() throws Exception {
        when(client.hasDisplayNames()).thenReturn(true);
        when(client.setDisplayNameWithRetry(anyString(), anyInt(), any(), any())).thenReturn(null);
        when(client.registerOne(anyString(), anyString(), anyInt(), any(), any()))
                .thenReturn(RegistrationOutcome.CREATED);
        pending(group(3, 0, 0));
        for (int i = 0; i < 3; i++) {
            worker.tick();
            pending(group(3, lastInt("registeredCount"), lastInt("namedCount")));
            setBackoffElapsed();
        }
        assertThat(lastValue("registrationState"))
                .as("the nameless bot1 stopped the group, visibly")
                .isEqualTo(RegistrationState.FAILED);

        // POST /registration/retry: the state goes back to PENDING (as persisted: registered 1,
        // named 0) and the group is enqueued, which clears the attempt state.
        when(client.setDisplayNameWithRetry(anyString(), anyInt(), any(), any())).thenReturn("Name");
        pending(group(3, lastInt("registeredCount"), lastInt("namedCount")));
        worker.enqueue(GROUP);
        worker.tick();

        verify(client, org.mockito.Mockito.times(4))
                .setDisplayNameWithRetry(eq("bot1"), anyInt(), any(), any());
        verify(client, org.mockito.Mockito.times(1))
                .registerOne(anyString(), anyString(), eq(1), any(), any());
        assertThat(lastInt("namedCount")).isEqualTo(3);
        assertThat(lastValue("registrationState")).isNull();
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
        // countBy, not findBy (review T7): the gauge only ever needed the number, and this query
        // runs every tick-seconds for the life of the JVM.
        when(repository.countByRegistrationState(RegistrationState.FAILED)).thenReturn(2L);

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

    @Test
    @DisplayName("a deferred group steps aside so another environment's group can register")
    void aBudgetDeferralDoesNotStarveTheOtherGroups() throws Exception {
        // Two groups. The older one is on the starved environment and will be refused by the
        // budget; the younger one is on a healthy environment and must still get its accounts.
        BotGroup starved = group(5, 0, 0);
        BotGroup healthy = BotGroup.builder()
                .id("group-2").name("H").environmentId(OTHER_ENV)
                .namePrefix("other").password("pw")
                .botCount(2).registeredCount(0).namedCount(0)
                .registrationState(RegistrationState.PENDING)
                .createdAt(Instant.now().plusSeconds(60))
                .build();
        pending(starved, healthy);

        when(client.registerOne(eq("bot"), anyString(), anyInt(), any(), any()))
                .thenThrow(new GatewayBudgetExhaustedException(RequestTier.DEFAULT, ENV, null));
        when(otherClient.registerOne(eq("other"), anyString(), anyInt(), any(), any()))
                .thenReturn(RegistrationOutcome.CREATED);

        // Pass 1 picks the oldest group and is deferred by its environment's budget.
        worker.tick();
        verify(otherClient, never()).registerOne(anyString(), anyString(), anyInt(), any(), any());

        // Pass 2 must NOT pick the same group again. Before review B2 it did, forever: a budget
        // deferral deliberately spends no attempt, and isDue consulted only the attempt state, so
        // the starved group was the oldest DUE group on every tick — for up to
        // registration.max-wait per pass — and no group on any other environment registered a
        // single account for the whole duration of the starvation. Under Phase 5 the same arm
        // catches GatewayCircuitOpenException, whose own message says the block "may last a day
        // or more".
        worker.tick();

        verify(otherClient, org.mockito.Mockito.times(2))
                .registerOne(eq("other"), anyString(), anyInt(), any(), any());
        assertThat(notBeforeOf(GROUP))
                .as("the deferral is what makes the group skippable, and it is a notBefore rather "
                        + "than a spent attempt")
                .isNotNull()
                .isAfter(Instant.now());
    }

    @Test
    @DisplayName("two or more starved groups cannot take turns ahead of a younger healthy group")
    void severalStarvedGroupsDoNotStarveAHealthyOne() throws Exception {
        // review-phase4-fixround B2. The test above has ONE starved group, and passes with or
        // without this defect. In production a starved pass holds the worker for registration.max-
        // wait (15 m), so by the time it ends every OTHER group's 60 s deferral has long expired.
        // Simulated here by expiring every deferral except the one the pass just wrote. Before the
        // fix, A and B — both older than H — took the thread in turn forever and H never ran.
        BotGroup starvedA = group(5, 0, 0);
        BotGroup starvedB = BotGroup.builder()
                .id("group-b").name("B").environmentId(ENV)
                .namePrefix("bot").password("pw")
                .botCount(5).registeredCount(0).namedCount(0)
                .registrationState(RegistrationState.PENDING)
                .createdAt(Instant.now().plusSeconds(30))
                .build();
        BotGroup healthy = BotGroup.builder()
                .id("group-2").name("H").environmentId(OTHER_ENV)
                .namePrefix("other").password("pw")
                .botCount(2).registeredCount(0).namedCount(0)
                .registrationState(RegistrationState.PENDING)
                .createdAt(Instant.now().plusSeconds(60))
                .build();
        pending(starvedA, starvedB, healthy);
        when(client.registerOne(eq("bot"), anyString(), anyInt(), any(), any()))
                .thenThrow(new com.vingame.bot.common.exception.GatewayCircuitOpenException(
                        ENV, "a3c7e4004acc850e-HKG", Duration.ofMinutes(60)));
        when(otherClient.registerOne(eq("other"), anyString(), anyInt(), any(), any()))
                .thenReturn(RegistrationOutcome.CREATED);

        for (int pass = 0; pass < 8; pass++) {
            worker.tick();
            expireAllDeferralsButTheLatest(GROUP, "group-b");
        }

        verify(otherClient, org.mockito.Mockito.atLeastOnce())
                .registerOne(eq("other"), anyString(), anyInt(), any(), any());
    }

    /** What a 15-minute starved pass does to every other group's 60 s deferral: expires it. */
    private void expireAllDeferralsButTheLatest(String... groupIds) {
        String latest = null;
        Instant latestAt = null;
        for (String id : groupIds) {
            Instant at = notBeforeOf(id);
            if (at != null && (latestAt == null || at.isAfter(latestAt))) {
                latest = id;
                latestAt = at;
            }
        }
        for (String id : groupIds) {
            if (!id.equals(latest)) {
                setNotBefore(id, Instant.now().minusSeconds(1));
            }
        }
    }

    @Test
    @DisplayName("a group whose document disappears mid-pass stops registering, like a cancel")
    void aDeletedDocumentStopsTheLoop() throws Exception {
        // review-phase4-fixround. findById returning EMPTY is Mongo saying the document is gone — a
        // delete by some path other than BotGroupService.delete, which cancels first. It used to be
        // read as "keep the old target", so accounts kept being created for a group nobody could
        // see until the loop ran out.
        pending(group(5, 0, 0));
        when(client.registerOne(anyString(), anyString(), anyInt(), any(), any()))
                .thenAnswer(inv -> {
                    // The first account lands; then the document is deleted out from under the pass.
                    org.mockito.Mockito.doReturn(java.util.Optional.empty()).when(repository).findById(GROUP);
                    return RegistrationOutcome.CREATED;
                });

        worker.tick();

        verify(client, org.mockito.Mockito.times(1))
                .registerOne(anyString(), anyString(), anyInt(), any(), any());
        assertThat(lastValue("registrationState"))
                .as("no completion claimed for a group that no longer exists")
                .isEqualTo("<never set>");
    }

    @Test
    @DisplayName("the startup line prints the effective transport budget and deferral backoff, after their floors")
    void theStartupLineCarriesTheEffectiveSettings() {
        // review-phase4-fixround. Configured below their floors: transport 1 (floored to
        // max-attempts-per-user=3) and deferral 0 s (floored to one 10 s tick).
        RegistrationWorker floored = new RegistrationWorker(repository, mongoTemplate,
                mock(EnvironmentClientRegistry.class), budgetRegistry,
                mock(EnvironmentService.class), metrics, 10, 3, 5, 30, 1, 0);
        List<String> lines = new java.util.concurrent.CopyOnWriteArrayList<>();
        var appender = new org.apache.logging.log4j.core.appender.AbstractAppender("rw-start-capture", null,
                org.apache.logging.log4j.core.layout.PatternLayout.createDefaultLayout(), true, null) {
            @Override
            public void append(org.apache.logging.log4j.core.LogEvent event) {
                lines.add(event.getMessage().getFormattedMessage());
            }
        };
        appender.start();
        var ctx = (org.apache.logging.log4j.core.LoggerContext) org.apache.logging.log4j.LogManager.getContext(false);
        var config = ctx.getConfiguration().getLoggerConfig(RegistrationWorker.class.getName());
        config.addAppender(appender, org.apache.logging.log4j.Level.INFO, null);
        ctx.updateLoggers();
        try {
            floored.start();
        } finally {
            floored.shutdown();
            config.removeAppender("rw-start-capture");
            ctx.updateLoggers();
            appender.stop();
        }

        assertThat(lines).anyMatch(line -> line.startsWith("Registration worker started")
                && line.contains("max-transport-attempts-per-user=3")
                && line.contains("deferral-backoff=10s"));
    }

    @Test
    @DisplayName("a budget deferral still costs no attempt, only a turn")
    void aBudgetDeferralSpendsNoAttempt() throws Exception {
        pending(group(5, 0, 0));
        when(client.registerOne(anyString(), anyString(), anyInt(), any(), any()))
                .thenThrow(new GatewayBudgetExhaustedException(RequestTier.DEFAULT, ENV, null));

        // Six deferrals — twice max-attempts-per-user — each made due again by hand, which is what
        // the deferral backoff elapsing looks like.
        for (int i = 0; i < 6; i++) {
            worker.tick();
            setBackoffElapsed();
        }

        assertThat(lastValue("registrationState")).isEqualTo("<never set>");
        assertThat(counter("failed")).isZero();
    }

    @Test
    @DisplayName("a transport failure is not charged as a gateway refusal")
    void aTransportFailureHasItsOwnBudget() throws Exception {
        pending(group(5, 0, 0));
        // What a 60-second network blip looks like from here: the wrapped IOException the naming
        // path produces, and the bare one registerOne declares.
        when(client.registerOne(anyString(), anyString(), anyInt(), any(), any()))
                .thenThrow(new RuntimeException("Failed to register",
                        new java.net.http.HttpTimeoutException("request timed out")));

        // Four attempts, one more than max-attempts-per-user=3. Before review S3 the group was
        // FAILED after the third — a socket message in registrationError, a human needed, and a
        // 500-account create stopped by a blip.
        for (int i = 0; i < 4; i++) {
            worker.tick();
            setBackoffElapsed();
        }

        assertThat(lastValue("registrationState"))
                .as("a transport failure says neither yes nor no, so it gets the larger "
                        + "max-transport-attempts-per-user budget")
                .isEqualTo("<never set>");

        // It is bounded, though: a gateway that cannot be reached at all must not be invisible.
        for (int i = 0; i < 8; i++) {
            worker.tick();
            setBackoffElapsed();
        }
        assertThat(lastValue("registrationState")).isEqualTo(RegistrationState.FAILED);
        assertThat(lastValue("registrationError")).asString()
                .contains("transport attempt")
                .contains("account 1 of 5");
    }

    @Test
    @DisplayName("a response that is not JSON is a refusal with the 3-attempt budget, not a transport failure")
    void aNonJsonAnswerIsNotATransportFailure() throws Exception {
        // review-phase4-fixround S3. Jackson's parse exceptions ARE IOExceptions, so an HTML page
        // from the edge or an origin used to be charged as "we could not ask": ten retries against
        // something refusing us, and an error that pointed the operator at the network. Both shapes
        // the client produces: the bare parse failure registerOne declares, and the RuntimeException
        // the naming path wraps it in.
        pending(group(5, 0, 0));
        when(client.registerOne(anyString(), anyString(), anyInt(), any(), any()))
                .thenThrow(new com.fasterxml.jackson.core.JsonParseException(null,
                        "Unexpected character ('<' (code 60)): expected a valid value"));

        for (int i = 0; i < 3; i++) {
            worker.tick();
            setBackoffElapsed();
        }

        assertThat(lastValue("registrationState"))
                .as("max-attempts-per-user=3: the gateway answered, so the answer's budget applies")
                .isEqualTo(RegistrationState.FAILED);
        assertThat(lastValue("registrationError")).asString()
                .contains("3 attempts")
                .doesNotContain("transport");
    }

    @Test
    @DisplayName("a wrapped non-JSON answer is classified the same way")
    void aWrappedNonJsonAnswerIsNotATransportFailure() throws Exception {
        pending(group(5, 0, 0));
        when(client.registerOne(anyString(), anyString(), anyInt(), any(), any()))
                .thenThrow(new RuntimeException("Failed to set display name: x",
                        new com.fasterxml.jackson.core.JsonParseException(null, "Unexpected character ('<')")));

        for (int i = 0; i < 3; i++) {
            worker.tick();
            setBackoffElapsed();
        }

        assertThat(lastValue("registrationState")).isEqualTo(RegistrationState.FAILED);
    }

    @Test
    // SEPARATE_THREAD: a regression here is a busy loop, which an interrupt cannot stop, so the
    // default same-thread timeout would hang the build instead of failing it (measured).
    @org.junit.jupiter.api.Timeout(value = 10, unit = java.util.concurrent.TimeUnit.SECONDS,
            threadMode = org.junit.jupiter.api.Timeout.ThreadMode.SEPARATE_THREAD)
    @DisplayName("a cause cycle does not hang the registration thread")
    void aCauseCycleTerminates() throws Exception {
        // review-phase4-fixround: getCause() returns null only for a SELF-cause, so A -> B -> A
        // (possible through initCause) used to spin the JVM's only registration thread forever.
        RuntimeException a = new RuntimeException("a");
        RuntimeException b = new RuntimeException("b", a);
        a.initCause(b);
        pending(group(5, 0, 0));
        when(client.registerOne(anyString(), anyString(), anyInt(), any(), any())).thenThrow(a);

        worker.tick();

        assertThat(notBeforeOf(GROUP)).as("classified and backed off, i.e. it returned").isNotNull();
    }

    /**
     * Pretend the backoff (or the budget deferral) has elapsed, so the next tick reconsiders the
     * group.
     * <p>
     * Rebuilt from the record's own components rather than from a fixed constructor arity: the
     * {@code Attempt} record gained a second counter in review S3 and a deferral writer in review
     * B2, and a helper pinned to {@code (int, int, Instant)} silently stops working — it returns
     * early on the {@code ReflectiveOperationException} and the test then passes for the wrong
     * reason.
     */
    private void setBackoffElapsed() {
        setNotBefore(GROUP, Instant.now().minusSeconds(1));
    }

    private void setNotBefore(String groupId, Instant notBefore) {
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> attempts = (java.util.Map<String, Object>)
                org.springframework.test.util.ReflectionTestUtils.getField(worker, "attempts");
        Object attempt = attempts.get(groupId);
        if (attempt == null) {
            return;
        }
        var components = attempt.getClass().getRecordComponents();
        assertThat(components)
                .as("Attempt is expected to stay a record — this helper rebuilds it component-wise")
                .isNotNull();
        Object[] args = new Object[components.length];
        Class<?>[] types = new Class<?>[components.length];
        for (int i = 0; i < components.length; i++) {
            types[i] = components[i].getType();
            args[i] = components[i].getType() == Instant.class
                    ? notBefore
                    : org.springframework.test.util.ReflectionTestUtils
                            .invokeGetterMethod(attempt, components[i].getName());
        }
        try {
            var ctor = attempt.getClass().getDeclaredConstructor(types);
            ctor.setAccessible(true);
            attempts.put(groupId, ctor.newInstance(args));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not rebuild Attempt", e);
        }
    }

    /** The {@code notBefore} the worker is currently holding for {@code groupId}, or null. */
    private Instant notBeforeOf(String groupId) {
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> attempts = (java.util.Map<String, Object>)
                org.springframework.test.util.ReflectionTestUtils.getField(worker, "attempts");
        Object attempt = attempts.get(groupId);
        return attempt == null ? null : (Instant) org.springframework.test.util.ReflectionTestUtils
                .invokeGetterMethod(attempt, "notBefore");
    }
}
