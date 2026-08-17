package com.vingame.bot.domain.alert.service;

import com.vingame.bot.domain.alert.dto.AlertDispatchDTO;
import com.vingame.bot.domain.alert.dto.AlertmanagerWebhook;
import com.vingame.bot.domain.alert.dto.AlertmanagerWebhook.AlertmanagerAlert;
import com.vingame.bot.domain.brand.model.ProductCode;
import com.vingame.bot.domain.environment.model.Environment;
import com.vingame.bot.domain.environment.service.EnvironmentService;
import com.vingame.bot.infrastructure.notification.VipTalkClient;
import com.vingame.bot.infrastructure.notification.VipTalkSendResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The three user requirements behind VIPTALK_ALERTING_V2, asserted <b>at the room</b>
 * rather than at any one collaborator:
 * <ol>
 *   <li><b>A</b> — a product room receives everything for its product, from <em>every</em>
 *       environment, whether the product came off the {@code product} label or the
 *       {@code environmentId} → Mongo hop;</li>
 *   <li><b>B</b> — the ops room receives infrastructure only: no product-scoped alert for
 *       a <em>wired</em> product ever lands there;</li>
 *   <li><b>C</b> — one serious outage renders two different texts for two audiences, and
 *       the customer-facing one is never synthesised from the operator one.</li>
 * </ol>
 * Everything below the webhook is real — {@link AlertmanagerWebhookService},
 * {@link AlertService}, {@link AlertRouter}, {@link AlertRoomRegistry} and
 * {@link AlertMessageFormatter} are the production objects; only the transport
 * ({@link VipTalkClient}) and the Mongo lookup are stubbed. The unit suites pin each
 * class's own contract; this one pins the composition, which is where a routing
 * regression would actually be felt (e.g. an audience that survives the router but is
 * merged away by webhook batching).
 * <p>
 * Rooms are read out of {@link AlertRoomRegistry}, never written as literals, so wiring
 * another product's {@code vipTalkRoomId} cannot break this suite by itself.
 */
@DisplayName("VipTalk audience routing — user requirements A/B/C, end to end")
class AlertRoutingRequirementsTest {

    private static final String OPS_ROOM = "!ops:matrix-uat.viptalk.org";
    private static final String PUBLIC_SUMMARY =
            "ALERT! Bot Management application is experiencing issues, backend team is aware "
                    + "and will deliver fixes soon.";

    /** The room of a product that IS wired, and of one that is not (AD-V5 misroute). */
    private static final ProductCode WIRED = ProductCode.P_116;
    private static final ProductCode UNWIRED = ProductCode.P_097;

    private final List<Send> sends = new ArrayList<>();
    private EnvironmentService environmentService;
    private String productRoom;

    private record Send(String text, List<String> rooms) {}

    @BeforeEach
    void setUp() {
        sends.clear();
        environmentService = mock(EnvironmentService.class);
        productRoom = new AlertRoomRegistry(OPS_ROOM).roomFor(WIRED).orElseThrow();
    }

    /** Wires the real chain with the given instance posture (AD-V7). */
    private AlertmanagerWebhookService webhook(boolean customerNoticesEnabled) {
        VipTalkClient client = mock(VipTalkClient.class);
        when(client.send(anyString(), anyList())).thenAnswer(invocation -> {
            List<String> rooms = invocation.getArgument(1);
            sends.add(new Send(invocation.getArgument(0), List.copyOf(rooms)));
            return VipTalkSendResult.sent(rooms.size(), 200);
        });

        AlertRoomRegistry rooms = new AlertRoomRegistry(OPS_ROOM);
        AlertMessageFormatter formatter = new AlertMessageFormatter("prod");
        AlertRouter router = new AlertRouter(rooms, formatter, customerNoticesEnabled);
        AlertService alertService =
                new AlertService(client, rooms, formatter, router, new SimpleMeterRegistry());
        return new AlertmanagerWebhookService(alertService, environmentService);
    }

    /** Every message text that actually reached the given room. */
    private List<String> textsDeliveredTo(String room) {
        return sends.stream().filter(s -> s.rooms().contains(room)).map(Send::text).toList();
    }

    private static AlertmanagerAlert firing(Map<String, String> labels, Map<String, String> annotations) {
        return new AlertmanagerAlert("firing", labels, annotations, "2026-08-17T10:00:00Z", "");
    }

    private static AlertmanagerWebhook payload(Map<String, String> commonLabels, AlertmanagerAlert... alerts) {
        return new AlertmanagerWebhook("4", "firing", Map.of(), commonLabels, Map.of(),
                "http://alertmanager:9093", List.of(alerts));
    }

    private void environment(String id, ProductCode product) {
        when(environmentService.findById(id))
                .thenReturn(Environment.builder().id(id).productCode(product).build());
    }

    /* ------------------------------------------------------------------ *
     * Requirement A — the product room gets its product, every environment
     * ------------------------------------------------------------------ */

    @Test
    @DisplayName("A: alerts from two different environments of one product all reach that product's room")
    void productRoomReceivesEveryEnvironmentOfItsProduct() {
        environment("env-tip-a", WIRED);
        environment("env-tip-b", WIRED);

        webhook(false).handle(payload(Map.of("audience", "product"),
                firing(Map.of("alertname", "GameNoRounds", "severity", "critical",
                                "environmentId", "env-tip-a"),
                        Map.of("summary", "no rounds on Bau Cua")),
                firing(Map.of("alertname", "GameNoRounds", "severity", "critical",
                                "environmentId", "env-tip-b"),
                        Map.of("summary", "no rounds on Tai Xiu"))));

        // Routing is by product, NOT by environment: both environments land in the one room.
        List<String> productTexts = textsDeliveredTo(productRoom);
        assertThat(productTexts).hasSize(1);
        assertThat(productTexts.getFirst())
                .contains("no rounds on Bau Cua")
                .contains("no rounds on Tai Xiu")
                .contains("env env-tip-a")
                .contains("env env-tip-b");
        assertThat(textsDeliveredTo(OPS_ROOM)).isEmpty();
    }

    @Test
    @DisplayName("A: a product resolved only through the environmentId → Mongo hop still reaches its room")
    void productResolvedViaEnvironmentLookupReachesTheProductRoom() {
        environment("env-tip-a", WIRED);

        webhook(false).handle(payload(Map.of(),
                firing(Map.of("alertname", "EnvironmentSocketDown", "severity", "critical",
                                "audience", "product", "environmentId", "env-tip-a"),
                        Map.of("summary", "0% of bots hold a WebSocket"))));

        assertThat(textsDeliveredTo(productRoom)).hasSize(1);
        assertThat(textsDeliveredTo(OPS_ROOM)).isEmpty();
    }

    /* ---------------------------------------------------- *
     * Requirement B — the ops room takes infrastructure only
     * ---------------------------------------------------- */

    @Test
    @DisplayName("B: an infra alert and a product alert in one Alertmanager group do not cross rooms")
    void opsRoomTakesInfrastructureOnlyAndProductNoiseStaysOut() {
        webhook(false).handle(payload(Map.of("product", "116"),
                firing(Map.of("alertname", "HostDiskSpaceLow", "severity", "warning",
                                "audience", "internal"),
                        Map.of("summary", "Host disk 12% free")),
                firing(Map.of("alertname", "GameNoRounds", "severity", "critical",
                                "audience", "product"),
                        Map.of("summary", "No rounds on game Bau Cua"))));

        assertThat(textsDeliveredTo(OPS_ROOM))
                .singleElement().asString()
                .contains("Host disk 12% free")
                .doesNotContain("No rounds on game");
        assertThat(textsDeliveredTo(productRoom))
                .singleElement().asString()
                .contains("No rounds on game Bau Cua")
                .doesNotContain("Host disk");
    }

    @Test
    @DisplayName("B: an internal alert carrying a product label still never reaches that product's room")
    void internalAlertWithAProductLabelStaysInOps() {
        webhook(true).handle(payload(Map.of(),
                firing(Map.of("alertname", "BotManagerRestarted", "severity", "warning",
                                "audience", "internal", "product", "116"),
                        Map.of("summary", "bot-manager restarted",
                                "public_summary", PUBLIC_SUMMARY))));

        // Neither the product label nor a stray public_summary can promote an
        // internal rule into a product room.
        assertThat(textsDeliveredTo(OPS_ROOM)).hasSize(1);
        assertThat(textsDeliveredTo(productRoom)).isEmpty();
        assertThat(sends).hasSize(1);
    }

    /* ------------------------------------------------------------ *
     * Requirement C — one outage, two registers, fail-closed customer
     * ------------------------------------------------------------ */

    @Test
    @DisplayName("C: a serious outage reaches ops technically and the product room in plain language")
    void seriousOutageRendersTwoRegistersForTwoAudiences() {
        webhook(true).handle(payload(Map.of(),
                firing(Map.of("alertname", "BotManagerDown", "severity", "critical",
                                "audience", "both", "product", "116"),
                        Map.of("summary", "bot-manager is not scrapeable",
                                "description", "Prometheus has failed to scrape bot-manager for 2 minutes",
                                "public_summary", PUBLIC_SUMMARY))));

        assertThat(textsDeliveredTo(OPS_ROOM))
                .singleElement().asString()
                .contains("CRITICAL")
                .contains("bot-manager is not scrapeable")
                .contains("via prometheus");

        assertThat(textsDeliveredTo(productRoom))
                .singleElement().asString()
                .isEqualTo("🔴 TIP (116) · prod\n" + PUBLIC_SUMMARY);
    }

    @Test
    @DisplayName("C: audience=both with no public_summary sends nothing to the product room (fail-closed)")
    void customerRegisterIsSuppressedRatherThanSynthesised() {
        webhook(true).handle(payload(Map.of(),
                firing(Map.of("alertname", "BotManagerDown", "severity", "critical",
                                "audience", "both", "product", "116"),
                        Map.of("summary", "bot-manager is not scrapeable",
                                "description", "the JVM died on native-thread exhaustion"))));

        // AD-V6: no annotation ⇒ no customer copy. The operator wording must not be
        // reused as a stand-in, which is the one way internal text reaches a product room.
        assertThat(textsDeliveredTo(productRoom)).isEmpty();
        assertThat(textsDeliveredTo(OPS_ROOM)).hasSize(1);
        assertThat(sends).hasSize(1);
    }

    @Test
    @DisplayName("C: a whitespace-only public_summary counts as absent, not as an empty notice")
    void blankPublicSummaryIsTreatedAsAbsent() {
        webhook(true).handle(payload(Map.of(),
                firing(Map.of("alertname", "BotManagerDown", "severity", "critical",
                                "audience", "both", "product", "116"),
                        Map.of("summary", "bot-manager is not scrapeable",
                                "public_summary", "   \n  "))));

        assertThat(textsDeliveredTo(productRoom)).isEmpty();
        assertThat(textsDeliveredTo(OPS_ROOM)).hasSize(1);
    }

    @Test
    @DisplayName("C: on a non-prod instance audience=both degrades to internal (AD-V7)")
    void customerNoticesDisabledKeepsProductRoomsQuiet() {
        webhook(false).handle(payload(Map.of(),
                firing(Map.of("alertname", "BotManagerDown", "severity", "critical",
                                "audience", "both", "product", "116"),
                        Map.of("summary", "bot-manager is not scrapeable",
                                "public_summary", PUBLIC_SUMMARY))));

        // Staging and loadtest run the same artifact into the same rooms; a thrice-daily
        // staging restart must not tell a live product room the app is broken.
        assertThat(textsDeliveredTo(productRoom)).isEmpty();
        assertThat(textsDeliveredTo(OPS_ROOM)).hasSize(1);
    }

    /* ------------------------------------------- *
     * The AD-V5 misroute, and its dedupe guarantee
     * ------------------------------------------- */

    @Test
    @DisplayName("audience=both for an unwired product produces exactly ONE ops message, technical")
    void bothForAnUnwiredProductNeverDoubleSendsToOps() {
        webhook(true).handle(payload(Map.of(),
                firing(Map.of("alertname", "BotManagerDown", "severity", "critical",
                                "audience", "both", "product", "097"),
                        Map.of("summary", "bot-manager is not scrapeable",
                                "public_summary", PUBLIC_SUMMARY))));

        // The customer register would misroute into the ops room, which is already
        // receiving the technical copy of the same alert — one condition, one message.
        assertThat(sends).hasSize(1);
        assertThat(textsDeliveredTo(OPS_ROOM))
                .singleElement().asString()
                .contains("bot-manager is not scrapeable")
                .doesNotContain(PUBLIC_SUMMARY);
    }

    @Test
    @DisplayName("audience=product for an unwired product misroutes to ops, marked, without a customer copy")
    void productAlertForAnUnwiredProductIsMarkedMisrouted() {
        webhook(true).handle(payload(Map.of(),
                firing(Map.of("alertname", "GameNoRounds", "severity", "critical",
                                "audience", "product", "product", "097"),
                        Map.of("summary", "No rounds on game Bau Cua"))));

        assertThat(textsDeliveredTo(OPS_ROOM))
                .singleElement().asString()
                .startsWith("↪️ MISROUTED")
                .contains(UNWIRED.getDisplayName() + " (" + UNWIRED.getCode() + ")")
                .contains("No rounds on game Bau Cua");
        assertThat(textsDeliveredTo(productRoom)).isEmpty();
    }

    @Test
    @DisplayName("the AD-V5 staging probe answers SENT with one room, not SKIPPED")
    void unwiredProductProbeAnswersSent() {
        // What `POST /api/v1/alerts/alertmanager … product=097 | jq -r .outcome` prints.
        // The plan's Phase 2 verification block predates the AD-V5 amendment and still
        // says SKIPPED; the amended behaviour is a tagged delivery to the ops room, so
        // the HTTP answer is SENT. Pinned here so the release check has one true
        // expectation to compare against.
        AlertDispatchDTO dto = AlertDispatchDTO.from(webhook(false).handle(payload(Map.of(),
                firing(Map.of("alertname", "RoutingProbeUnwired", "severity", "warning",
                                "audience", "product", "product", "097"),
                        Map.of("summary", "routing probe — unwired product")))));

        assertThat(dto.outcome()).isEqualTo(VipTalkSendResult.Outcome.SENT.name());
        assertThat(dto.roomCount()).isEqualTo(1);
        assertThat(textsDeliveredTo(OPS_ROOM)).singleElement().asString().startsWith("↪️ MISROUTED");
    }

    @Test
    @DisplayName("a malformed payload delivers nothing rather than throwing at any layer")
    void malformedPayloadIsInert() {
        AlertmanagerWebhookService webhook = webhook(true);

        assertThat(webhook.handle(null).outcome()).isEqualTo(VipTalkSendResult.Outcome.SKIPPED);
        assertThat(webhook.handle(new AlertmanagerWebhook("4", "firing", null, null, null, null,
                List.of(new AlertmanagerAlert("firing", null, null, null, null)))).outcome())
                .isNotNull();
        // A labelless alert defaults to audience=internal (AD-V4) and must not vanish.
        assertThat(textsDeliveredTo(OPS_ROOM)).hasSize(1);
    }
}
