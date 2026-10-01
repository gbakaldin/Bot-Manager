package com.vingame.bot.domain.botgroup.service;

import com.vingame.bot.common.gateway.GatewayRequestScope;
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
import org.junit.jupiter.api.Timeout;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cancellation is <b>two mechanisms</b>, and the shipped test only reaches one of them
 * (GATEWAY_REQUEST_BUDGET A28.6).
 *
 * <p>{@code RegistrationWorkerTest.cancelStopsTheLoopAndWakesTheBudget} calls
 * {@code worker.cancel(...)} <em>before</em> the tick and then asserts
 * {@code verify(budget).cancelScope(GROUP)}. That verification is discharged by the direct call
 * to {@code cancel} itself — it would pass against a worker that built a group-less, predicate-less
 * scope and never looked at the flag again. What it actually proves is the between-accounts half:
 * a group already marked cancelled is not started.
 *
 * <p><b>The half it does not reach is the one with live consequences.</b> A {@code DELETE} that
 * arrives while a registration is already parked <em>inside</em> the budget cannot be served by a
 * flag the worker only reads between accounts: the worker thread is not at that check, it is
 * blocked in {@code registerOne} for up to {@code registration.max-wait} (15 minutes). Two things
 * have to be true for that request to come off:
 * <ol>
 *   <li>the scope the budget is holding must have a <b>live</b> cancellation predicate — one that
 *       starts answering {@code true} the moment {@code cancel} runs, rather than a boolean
 *       snapshotted when the scope was built; and</li>
 *   <li>{@code cancelScope} must be delivered to the budget that is holding it.</li>
 * </ol>
 * This class asserts both <em>against a scope captured mid-flight</em>, which is the only position
 * from which the difference between a live predicate and a snapshot is visible at all.
 *
 * <p>No gateway is touched: {@link ApiGatewayClient} is a mock and no socket is opened.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@DisplayName("RegistrationWorker — cancelling a registration that is already parked in the budget")
class RegistrationWorkerCancellationScopeTest {

    private static final String GROUP = "group-1";
    private static final String ENV = "env-1";

    private BotGroupRepository repository;
    private ApiGatewayClient client;
    private GatewayBudgetRegistry budgetRegistry;
    private GatewayBudget budget;
    private RegistrationWorker worker;

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
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        client = mock(ApiGatewayClient.class);
        budgetRegistry = mock(GatewayBudgetRegistry.class);
        budget = mock(GatewayBudget.class);
        when(budgetRegistry.find(ENV)).thenReturn(budget);

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
        // A real MongoTemplate never returns null, and recordCompletion reads getMatchedCount()
        // to decide whether the document was still complete when the pass ended (QA F-1).
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(BotGroup.class)))
                .thenReturn(com.mongodb.client.result.UpdateResult.acknowledged(1, 1L, null));

        worker = new RegistrationWorker(repository, mongoTemplate, clientRegistry, budgetRegistry,
                environmentService, new BotMetrics(new SimpleMeterRegistry()),
                10, 3, 5, 30, 10, 60);
    }

    private void pending(int botCount) {
        BotGroup group = BotGroup.builder()
                .id(GROUP).name("G").environmentId(ENV)
                .namePrefix("bot").password("pw")
                .botCount(botCount).registeredCount(0).namedCount(0)
                .registrationState(RegistrationState.PENDING)
                .createdAt(Instant.now())
                .build();
        when(repository.findByRegistrationState(RegistrationState.PENDING))
                .thenReturn(List.of(group));
        when(repository.findByRegistrationState(RegistrationState.FAILED)).thenReturn(List.of());
    }

    @Test
    @DisplayName("the scope's cancellation predicate is live — a DELETE mid-flight flips the scope "
            + "the budget is already holding")
    void theScopePredicateIsLiveNotASnapshot() throws Exception {
        pending(5);

        // The scope as the budget sees it: captured from INSIDE the first registerOne, while the
        // worker thread is still blocked in it. A scope read after the call returned would be
        // read after cancel() had already run, and would prove nothing about liveness.
        AtomicReference<GatewayRequestScope> inFlight = new AtomicReference<>();
        CountDownLatch parked = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();

        when(client.registerOne(anyString(), anyString(), anyInt(), any(), any()))
                .thenAnswer(invocation -> {
                    calls.incrementAndGet();
                    inFlight.set(invocation.getArgument(3));
                    parked.countDown();
                    // Stand in for a request queued inside the budget: the worker thread is here
                    // and is NOT at the between-accounts flag check.
                    assertThat(cancelled.await(20, TimeUnit.SECONDS))
                            .as("the cancelling thread must have run before this returns")
                            .isTrue();
                    return RegistrationOutcome.CREATED;
                });

        Thread tick = Thread.ofVirtual().name("worker-tick").start(worker::tick);
        assertThat(parked.await(20, TimeUnit.SECONDS))
                .as("the worker must actually reach registerOne").isTrue();

        GatewayRequestScope scope = inFlight.get();
        assertThat(scope).isNotNull();
        assertThat(scope.botGroupId())
                .as("cancelScope keys on the group id and nothing else")
                .isEqualTo(GROUP);
        assertThat(scope.isCancelled())
                .as("not cancelled yet — establishes that the assertion below is a change of "
                        + "state and not a constant")
                .isFalse();

        worker.cancel(GROUP, ENV);
        cancelled.countDown();

        // (1) The live predicate. This is what a budget waiter re-reads while deciding whether to
        // keep holding a slot; a scope built with a snapshotted boolean would still answer false
        // here, and the parked request would wait out the full fifteen minutes.
        assertThat(scope.isCancelled())
                .as("the scope the budget is ALREADY holding must answer true once cancel runs — "
                        + "a snapshot taken when the scope was built cannot do this")
                .isTrue();

        // (2) The wake-up. The predicate alone only helps a waiter that is re-polling;
        // cancelScope is what unparks one that is blocked on the budget's condition.
        verify(budget).cancelScope(GROUP);

        tick.join(TimeUnit.SECONDS.toMillis(20));
        assertThat(tick.isAlive()).isFalse();

        // ...and the between-accounts half, from a pass that was genuinely mid-flight rather than
        // cancelled before it began: index 2 of 5 is never started.
        assertThat(calls.get())
                .as("the loop stops at the next account rather than finishing the group")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("cancel is a no-op for an environment with no budget, and does not conjure one")
    void cancelDoesNotConjureABudget() {
        when(budgetRegistry.find(ENV)).thenReturn(null);

        // Must not throw, and must not call forEnvironment(...): materialising a budget — and a
        // fresh set of gateway_budget_* series — as a side effect of a DELETE would put an
        // environment on the dashboard that has never queued anything.
        worker.cancel(GROUP, ENV);
        worker.cancel(GROUP, null);

        verify(budgetRegistry, org.mockito.Mockito.never())
                .forEnvironment(anyString(), anyString(), anyString());
    }
}
