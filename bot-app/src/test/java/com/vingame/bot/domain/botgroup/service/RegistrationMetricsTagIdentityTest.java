package com.vingame.bot.domain.botgroup.service;

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
import com.vingame.bot.infrastructure.gateway.GatewayBudgetRegistry;
import com.vingame.bot.infrastructure.observability.BotMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The pre-registered series and the incremented series must be the <b>same</b> series
 * (GATEWAY_REQUEST_BUDGET A28.7 / A30.3; CLAUDE.md, {@code group_recovery_*}).
 *
 * <p><b>Why this is not covered by the shipped assertion.</b>
 * {@code RegistrationWorkerTest.outcomeSeriesArePreRegistered} reads the counters through
 * {@code meterRegistry.find(name).tag("outcome", outcome).counter()}, which matches on the
 * {@code outcome} tag <em>alone</em> and returns <em>a</em> match. So it proves the three
 * outcomes exist, and it proves nothing whatever about their tags — the precise failure
 * CLAUDE.md warns about for {@code group_recovery_*} is that pre-registration reads its tags
 * from somewhere other than the increment does, which registers a <b>second</b> series and
 * fixes nothing: the zero-valued one is never incremented, the incremented one still first
 * appears at {@code 1}, and {@code increase(...) > 0} still cannot fire on a first occurrence.
 * Under a loose {@code find}, that broken arrangement passes.
 *
 * <p>So this asserts the thing that actually has to hold: <b>exactly one</b> series per outcome,
 * carrying the group MDC's tags, with the un-incremented outcomes present at zero.
 *
 * <p>No gateway is touched: {@link ApiGatewayClient} is a mock and no socket is opened.
 */
@DisplayName("registration_accounts_total — pre-registered and incremented under the same tags")
class RegistrationMetricsTagIdentityTest {

    private static final String GROUP = "group-1";
    private static final String ENV = "env-1";

    private BotGroupRepository repository;
    private ApiGatewayClient client;
    private SimpleMeterRegistry meterRegistry;
    private RegistrationWorker worker;

    @BeforeEach
    void setUp() {
        repository = mock(BotGroupRepository.class);
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        client = mock(ApiGatewayClient.class);
        meterRegistry = new SimpleMeterRegistry();

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
                .thenReturn(null);

        worker = new RegistrationWorker(repository, mongoTemplate, clientRegistry,
                mock(GatewayBudgetRegistry.class), environmentService, new BotMetrics(meterRegistry),
                10, 3, 5, 30);
    }

    private void pending() {
        BotGroup group = BotGroup.builder()
                .id(GROUP).name("G").environmentId(ENV)
                .namePrefix("bot").password("pw")
                .botCount(1).registeredCount(0).namedCount(0)
                .registrationState(RegistrationState.PENDING)
                .createdAt(Instant.now())
                .build();
        when(repository.findByRegistrationState(RegistrationState.PENDING))
                .thenReturn(List.of(group));
        when(repository.findByRegistrationState(RegistrationState.FAILED)).thenReturn(List.of());
    }

    private Collection<Counter> series() {
        return meterRegistry.find(BotMetrics.REGISTRATION_ACCOUNTS_TOTAL).counters();
    }

    @Test
    @DisplayName("exactly three series, one per outcome — not six under two tag sets")
    void thePreRegisteredAndIncrementedSeriesAreTheSame() throws Exception {
        pending();
        when(client.registerOne(anyString(), anyString(), anyInt(), any(), any()))
                .thenReturn(RegistrationOutcome.CREATED);

        worker.tick();

        Collection<Counter> counters = series();
        assertThat(counters)
                .as("three outcomes, three series. Six would mean pre-registration and increment "
                        + "read their tags from different places — the group_recovery_* trap, "
                        + "which leaves the incremented series still first appearing at 1")
                .hasSize(3);

        Map<String, Double> byOutcome = counters.stream().collect(Collectors.toMap(
                c -> c.getId().getTag("outcome"), Counter::count));
        assertThat(byOutcome).containsOnlyKeys("success", "exists", "failed");
        assertThat(byOutcome.get("success")).isEqualTo(1.0);
        assertThat(byOutcome.get("exists"))
                .as("materialised at zero, so increase() over a first occurrence is not 0")
                .isZero();
        assertThat(byOutcome.get("failed")).isZero();
    }

    @Test
    @DisplayName("every series carries the group MDC's tags, the incremented one included")
    void everySeriesCarriesTheGroupTags() throws Exception {
        pending();
        when(client.registerOne(anyString(), anyString(), anyInt(), any(), any()))
                .thenReturn(RegistrationOutcome.CREATED);

        worker.tick();

        for (Counter counter : series()) {
            Map<String, String> tags = counter.getId().getTags().stream()
                    .collect(Collectors.toMap(Tag::getKey, Tag::getValue));
            assertThat(tags)
                    .as("series %s must be addressable by the group an alert names",
                            counter.getId().getTag("outcome"))
                    .containsEntry("botGroupId", GROUP)
                    .containsEntry("environmentId", ENV)
                    .containsEntry("product", ProductCode.P_116.getCode());
        }
    }

    @Test
    @DisplayName("an unresolvable environment substitutes a tag value rather than dropping the key")
    void anUnresolvableEnvironmentStillCarriesEveryTagKey() throws Exception {
        // Prometheus exposition keeps only the FIRST label-key set it sees under a metric name
        // and silently omits every later shape for the life of the JVM, so one group with a
        // missing environment must not be allowed to emit a narrower series — it would delete
        // every other group's from the scrape.
        BotGroup orphan = BotGroup.builder()
                .id(GROUP).name("G").environmentId(null)
                .namePrefix("bot").password("pw")
                .botCount(1).registeredCount(0).namedCount(0)
                .registrationState(RegistrationState.PENDING)
                .createdAt(Instant.now())
                .build();
        when(repository.findByRegistrationState(RegistrationState.PENDING))
                .thenReturn(List.of(orphan));
        when(repository.findByRegistrationState(RegistrationState.FAILED)).thenReturn(List.of());

        // The pass itself fails (no client for a null environment) and is swallowed per-group.
        // What must survive is the series shape.
        worker.tick();

        assertThat(series())
                .as("the pass failed, but the series were materialised before it could")
                .hasSize(3);
        for (Counter counter : series()) {
            Map<String, String> tags = counter.getId().getTags().stream()
                    .collect(Collectors.toMap(Tag::getKey, Tag::getValue));
            assertThat(tags)
                    .as("a substituted value, never a dropped key")
                    .containsEntry("botGroupId", GROUP)
                    .containsEntry("environmentId", "unknown")
                    .containsEntry("product", "unknown");
        }
    }
}
