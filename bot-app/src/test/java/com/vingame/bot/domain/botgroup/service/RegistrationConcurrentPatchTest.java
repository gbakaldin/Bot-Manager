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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import static org.mockito.Mockito.when;

/**
 * What a concurrent PATCH does to a registration that is already in flight
 * (GATEWAY_REQUEST_BUDGET A2.7, A30.5).
 *
 * <p>The worker persists progress with a targeted {@code $set} rather than
 * {@code repository.save(group)}, precisely so it does not revert a PATCH made while it was
 * working — A30.5 names the {@code botCount} raise as the case that matters, because A2.7 makes
 * "register 200 more accounts for this group" a product feature rather than a script.
 *
 * <p>The counter half of that holds, and the first test pins it: the worker writes
 * {@code registeredCount} / {@code namedCount} / {@code updatedAt} and <b>nothing else</b>, so a
 * {@code botCount} the operator raised in the meantime survives the pass.
 *
 * <p><b>The second test pins a defect, deliberately, and it will fail when the defect is fixed.
 * Read this before "fixing" the test.</b> A30.5 documents the residual race as self-correcting on
 * the grounds that a re-registered index answers {@code EXISTED} at the cost of one request. That
 * is true of the counter, and it is not true of the <em>completion decision</em>.
 * {@code register(group)} captures {@code target = group.getBotCount()} once, from a document it
 * may hold for the entire duration of a 500-account job, and {@code recordCompletion} then
 * {@code $set}s {@code registrationState: null} when the loop reaches that stale target. A raise
 * that lands mid-pass is therefore <b>erased</b>: {@code botCount} is 20, {@code registeredCount}
 * is 10, and the group is no longer {@code PENDING}, so no tick ever selects it again. The ten
 * accounts are never created, the group reports complete, and the bots built on the missing
 * indices fail to authenticate at start — which presents as an auth outage, not as a create that
 * lied. It is recoverable only because an operator who notices can PATCH {@code botCount} again.
 *
 * <p>The window is not a millisecond: it is the whole duration of a registration pass, which for
 * a 500-account group under the budget is measured in hours. See {@code qa-phase4.md}.
 *
 * <p>No gateway is touched: {@link ApiGatewayClient} is a mock and no socket is opened.
 */
@DisplayName("Registration vs. a concurrent PATCH")
class RegistrationConcurrentPatchTest {

    private static final String GROUP = "group-1";
    private static final String ENV = "env-1";

    private BotGroupRepository repository;
    private ApiGatewayClient client;
    private RegistrationWorker worker;
    private final List<Update> updates = new ArrayList<>();

    @BeforeEach
    void setUp() {
        repository = mock(BotGroupRepository.class);
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        client = mock(ApiGatewayClient.class);

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
                .thenAnswer(invocation -> {
                    updates.add(invocation.getArgument(1));
                    return null;
                });

        worker = new RegistrationWorker(repository, mongoTemplate, clientRegistry,
                mock(GatewayBudgetRegistry.class), environmentService,
                new BotMetrics(new SimpleMeterRegistry()), 10, 3, 5, 30);
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

    /** Every field name the worker ever {@code $set}, across the whole pass. */
    private List<String> fieldsWritten() {
        List<String> fields = new ArrayList<>();
        for (Update update : updates) {
            Object set = update.getUpdateObject().get("$set");
            if (set instanceof org.bson.Document doc) {
                doc.keySet().stream().filter(k -> !fields.contains(k)).forEach(fields::add);
            }
        }
        return fields;
    }

    @Test
    @DisplayName("the worker never writes botCount, so a concurrent raise is not reverted")
    void theWorkerWritesOnlyItsOwnFields() throws Exception {
        pending(3);
        when(client.registerOne(anyString(), anyString(), anyInt(), any(), any()))
                .thenReturn(RegistrationOutcome.CREATED);

        worker.tick();

        // The whole reason for updateFirst($set ...) over repository.save(group): the worker holds
        // a document it read minutes ago. Saving it would revert every field an operator changed
        // in between — botCount above all, since raising it IS how more accounts are asked for.
        assertThat(fieldsWritten())
                .as("a field here that the operator can also PATCH is a field the worker reverts")
                .containsExactlyInAnyOrder(
                        "registeredCount", "namedCount", "updatedAt",
                        "registrationState", "registrationError");
        assertThat(fieldsWritten()).doesNotContain("botCount", "name", "password", "namePrefix");
    }

    @Test
    @DisplayName("DEFECT: a botCount raise that lands mid-pass is erased by the completion write")
    void aMidPassBotCountRaiseIsErased() throws Exception {
        // The group the worker picked up asks for 2 accounts.
        pending(2);

        // The operator PATCHes botCount to 4 after account 1 lands. The worker's `target` was
        // captured before the loop and does not see it — which is the defect: the loop exits at 2.
        when(client.registerOne(anyString(), anyString(), anyInt(), any(), any()))
                .thenReturn(RegistrationOutcome.CREATED);

        worker.tick();

        // A sentinel, because "$set registrationState -> null" and "never written" are opposite
        // outcomes here and both read as a Java null.
        Object finalState = "<never written>";
        int finalRegistered = -1;
        for (Update update : updates) {
            Object set = update.getUpdateObject().get("$set");
            if (set instanceof org.bson.Document doc) {
                if (doc.containsKey("registrationState")) {
                    finalState = doc.get("registrationState");
                }
                if (doc.get("registeredCount") instanceof Integer i) {
                    finalRegistered = i;
                }
            }
        }

        assertThat(finalRegistered).isEqualTo(2);
        // THIS is the defect. The completion write clears registrationState unconditionally,
        // against a target captured before the pass began. Once it is null the group is not
        // PENDING, so no later tick selects it and the raise never registers anything — the
        // group simply reports complete at the old count.
        //
        // A fix would re-read botCount inside the loop (or make the completion write conditional
        // on `registeredCount >= botCount` in the query). When it lands, this assertion flips and
        // this test is the place to record the new behaviour, not the place to delete.
        assertThat(finalState)
                .as("the completion write is unconditional and uses a stale target — see "
                        + "docs/reviews/GATEWAY_REQUEST_BUDGET/qa-phase4.md")
                .isNull();
    }
}
