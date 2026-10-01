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
 * <p><b>The second and third tests cover QA F-1, which was a real defect and is now fixed.</b>
 * A30.5 documented the residual race as self-correcting on the grounds that a re-registered index
 * answers {@code EXISTED} at the cost of one request. That was true of the counter and false of the
 * <em>completion decision</em>: {@code register(group)} captured {@code target =
 * group.getBotCount()} once, from a document it may hold for the entire duration of a 500-account
 * job, and {@code recordCompletion} then {@code $set} {@code registrationState: null} when the loop
 * reached that stale target. A raise landing mid-pass was <b>erased</b> — {@code botCount} 20,
 * {@code registeredCount} 10, no longer {@code PENDING}, so no tick ever selected the group again;
 * the extra accounts were never created, the group reported complete, and the bots built on the
 * missing indices failed to authenticate at start, presenting as an auth outage rather than as a
 * create that lied. The window was not a millisecond: it was the whole duration of a pass, which
 * for a 500-account group under the budget is measured in hours — i.e. it contradicted A2.7's
 * "raise botCount to ask for more accounts" exactly under load.
 *
 * <p>Two changes, and both are asserted below: the loop <b>re-reads the target on every index</b>,
 * and the completion write is <b>conditional on the document's own {@code botCount}</b> so the
 * microseconds-wide remainder cannot clear the state of a group that is not complete.
 *
 * <p>No gateway is touched: {@link ApiGatewayClient} is a mock and no socket is opened.
 */
@DisplayName("Registration vs. a concurrent PATCH")
class RegistrationConcurrentPatchTest {

    private static final String GROUP = "group-1";
    private static final String ENV = "env-1";

    private BotGroupRepository repository;
    private MongoTemplate mongoTemplate;
    private ApiGatewayClient client;
    private RegistrationWorker worker;
    private final List<Update> updates = new ArrayList<>();
    /** Every {@code Query} the worker issued, positionally paired with {@link #updates}. */
    private final List<Query> queries = new ArrayList<>();

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
                    queries.add(invocation.getArgument(0));
                    updates.add(invocation.getArgument(1));
                    // A real MongoTemplate never returns null, and recordCompletion reads
                    // getMatchedCount() (QA F-1).
                    return com.mongodb.client.result.UpdateResult.acknowledged(1, 1L, null);
                });

        worker = new RegistrationWorker(repository, mongoTemplate, clientRegistry,
                mock(GatewayBudgetRegistry.class), environmentService,
                new BotMetrics(new SimpleMeterRegistry()), 10, 3, 5, 30, 10, 60);
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
    @DisplayName("a botCount raise that lands mid-pass is picked up, not erased (QA F-1)")
    void aMidPassBotCountRaiseIsHonoured() throws Exception {
        // The group the worker picked up asks for 2 accounts.
        pending(2);
        // …and the operator PATCHes botCount to 4 while the pass is running. That is what the
        // document says from the worker's next re-read onwards.
        patchedTo(4);

        when(client.registerOne(anyString(), anyString(), anyInt(), any(), any()))
                .thenReturn(RegistrationOutcome.CREATED);

        worker.tick();

        org.mockito.Mockito.verify(client, org.mockito.Mockito.times(4))
                .registerOne(anyString(), anyString(), anyInt(), any(), any());
        assertThat(lastInt("registeredCount")).isEqualTo(4);
        assertThat(lastValue("registrationState"))
                .as("complete at the NEW target, which is what A2.7 promises")
                .isNull();
    }

    @Test
    @DisplayName("the completion write asks Mongo to check botCount, and survives a no-match")
    void theCompletionWriteIsConditionalOnTheDocumentsBotCount() throws Exception {
        pending(2);
        when(client.registerOne(anyString(), anyString(), anyInt(), any(), any()))
                .thenReturn(RegistrationOutcome.CREATED);
        // Mongo's answer when `botCount <= done` no longer holds: nothing matched. That is the
        // raise landing between the loop's last re-read and the completion write — the window the
        // re-read cannot close.
        completionMatchesNothing();

        worker.tick();

        // THE ASSERTION WITH TEETH is the query, because the query is the mechanism: the condition
        // is evaluated by Mongo against the document, which is the only party that knows what
        // botCount is at the instant of the write. Deleting `.and("botCount").lte(done)` fails
        // here and nowhere else.
        Query completion = queries.get(queries.size() - 1);
        assertThat(completion.getQueryObject().toJson())
                .as("the state may only be cleared for a document whose own botCount is met")
                .contains("\"_id\": \"" + GROUP + "\"")
                .contains("botCount")
                .contains("$lte");

        // And the no-match arm itself: the pass ends without claiming completion, and the per-index
        // progress that DID land is untouched. The group stays PENDING in Mongo — by the document's
        // own authority, not ours — so the next tick resumes it.
        assertThat(lastValue("registrationState"))
                .as("nothing was written, so nothing claims the group is complete")
                .isEqualTo("<never set>");
        assertThat(lastInt("registeredCount"))
                .as("the per-index progress still landed — only the completion did not")
                .isEqualTo(2);
    }

    /** The last value {@code field} was {@code $set} to, or a sentinel if it never was. */
    private Object lastValue(String field) {
        Object value = "<never set>";
        for (Update update : updates) {
            Object set = update.getUpdateObject().get("$set");
            if (set instanceof org.bson.Document doc && doc.containsKey(field)) {
                value = doc.get(field);
            }
        }
        return value;
    }

    private int lastInt(String field) {
        Object value = lastValue(field);
        return value instanceof Integer i ? i : -1;
    }

    /** What the document says its {@code botCount} is from now on — the concurrent PATCH. */
    private void patchedTo(int botCount) {
        // doReturn, not when(): the fixture's default findById answer calls back into the mock,
        // and stubbing through when() would invoke it mid-stubbing.
        org.mockito.Mockito.doReturn(java.util.Optional.of(BotGroup.builder()
                .id(GROUP).name("G").environmentId(ENV)
                .namePrefix("bot").password("pw")
                .botCount(botCount)
                .registrationState(RegistrationState.PENDING)
                .createdAt(Instant.now())
                .build())).when(repository).findById(GROUP);
    }

    /** Make the conditional completion write match no document, as a mid-flight raise would. */
    private void completionMatchesNothing() {
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(BotGroup.class)))
                .thenAnswer(invocation -> {
                    // The QUERY is always recorded — it is the assertion below — but a write that
                    // matched nothing must not appear in the $set ledger, because nothing was
                    // written.
                    queries.add(invocation.getArgument(0));
                    Update update = invocation.getArgument(1);
                    Object set = update.getUpdateObject().get("$set");
                    boolean completion = set instanceof org.bson.Document doc
                            && doc.containsKey("registrationState");
                    if (completion) {
                        return com.mongodb.client.result.UpdateResult.acknowledged(0, 0L, null);
                    }
                    updates.add(update);
                    return com.mongodb.client.result.UpdateResult.acknowledged(1, 1L, null);
                });
    }
}
