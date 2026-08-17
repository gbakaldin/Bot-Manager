package com.vingame.bot.domain.alert.service;

import com.vingame.bot.domain.alert.dto.AlertmanagerWebhook;
import com.vingame.bot.domain.alert.dto.AlertmanagerWebhook.AlertmanagerAlert;
import com.vingame.bot.domain.alert.model.Alert;
import com.vingame.bot.domain.alert.model.AlertAudience;
import com.vingame.bot.domain.alert.model.AlertRegister;
import com.vingame.bot.domain.alert.model.AlertSeverity;
import com.vingame.bot.domain.alert.service.AlertRouter.RoutedMessage;
import com.vingame.bot.domain.brand.model.ProductCode;
import com.vingame.bot.domain.environment.service.EnvironmentService;
import com.vingame.bot.infrastructure.notification.VipTalkClient;
import com.vingame.bot.infrastructure.notification.VipTalkSendResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
 * Adversarial cover for the one path that can put operator wording in front of a
 * product's audience: the {@link AlertRegister#CUSTOMER} register (VIPTALK_ALERTING_V2
 * AD-V6/AD-V7).
 * <p>
 * The contract under test is <b>fail-closed</b>: a customer-facing message is rendered
 * <em>only</em> from a rule's own {@code public_summary}, and is otherwise not rendered
 * at all. It is never derived, degraded or approximated from the technical title, body,
 * severity or source — those are written for operators, and every one of them is a way
 * a stack-trace-shaped string or an environment id reaches a product room.
 * <p>
 * Each test below attacks that from a different direction: hostile technical content, the
 * hand-authored operator HTTP path, an {@code audience: product} rule that happens to
 * carry a summary, and the room-collision configuration.
 */
@DisplayName("customer register — fail-closed against operator wording leaking into a product room")
class CustomerRegisterFailClosedTest {

    private static final String OPS_ROOM = "!ops:matrix-uat.viptalk.org";
    private static final String PUBLIC_SUMMARY =
            "ALERT! Bot Management application is experiencing issues, backend team is aware "
                    + "and will deliver fixes soon.";

    /** Everything an operator would write and a product room must never see. */
    private static final String HOSTILE_TITLE = "BotManagerDown (×7)";
    private static final String HOSTILE_BODY =
            "• java.lang.OutOfMemoryError: unable to create native thread (env 0c9a93cb-20d6)\n"
                    + "• scrape of bot-manager:8085/actuator/prometheus failed";

    private AlertRouter router(String opsRoom, boolean customerNotices) {
        AlertRoomRegistry rooms = new AlertRoomRegistry(opsRoom);
        return new AlertRouter(rooms, new AlertMessageFormatter("prod"), customerNotices);
    }

    private String productRoom(ProductCode product) {
        return new AlertRoomRegistry(OPS_ROOM).roomFor(product).orElseThrow();
    }

    private List<RoutedMessage> delivered(List<RoutedMessage> routed) {
        return routed.stream().filter(RoutedMessage::isDelivered).toList();
    }

    /* ---------------- the technical register must not bleed through ---------------- */

    @Test
    @DisplayName("the customer copy is the public_summary and nothing else, however hostile the technical text")
    void customerCopyCarriesNoneOfTheOperatorText() {
        Alert alert = new Alert(AlertSeverity.CRITICAL, HOSTILE_TITLE, HOSTILE_BODY,
                ProductCode.P_116, "prometheus", AlertAudience.BOTH, PUBLIC_SUMMARY);

        RoutedMessage customer = delivered(router(OPS_ROOM, true).route(alert)).stream()
                .filter(m -> m.register() == AlertRegister.CUSTOMER)
                .findFirst().orElseThrow();

        assertThat(customer.roomId()).isEqualTo(productRoom(ProductCode.P_116));
        assertThat(customer.text())
                .isEqualTo("🔴 TIP (116) · prod\n" + PUBLIC_SUMMARY)
                .doesNotContain("OutOfMemoryError")
                .doesNotContain("0c9a93cb-20d6")
                .doesNotContain("actuator")
                .doesNotContain("CRITICAL")
                .doesNotContain("BotManagerDown")
                .doesNotContain("via prometheus");
    }

    @Test
    @DisplayName("the public_summary is rendered verbatim — the formatter neither trims content nor rewrites it")
    void publicSummaryIsVerbatim() {
        // Deliberate: sanitising here would be a silent, invisible edit of copy someone
        // signed off on. The rule author owns the wording; this pins that we do not
        // second-guess it, so any future filtering has to be a conscious change.
        String summary = "Bảo trì hệ thống 22:00–23:00. Xin lỗi vì sự bất tiện.";
        Alert alert = new Alert(AlertSeverity.WARNING, "t", "b", ProductCode.P_116,
                "prometheus", AlertAudience.BOTH, "  " + summary + "  ");

        assertThat(new AlertMessageFormatter("prod").format(alert, AlertRegister.CUSTOMER))
                .isEqualTo("⚠️ TIP (116) · prod\n" + summary);
    }

    @Test
    @DisplayName("a null register falls back to technical, never to customer")
    void nullRegisterIsTechnical() {
        Alert alert = new Alert(AlertSeverity.CRITICAL, HOSTILE_TITLE, HOSTILE_BODY,
                ProductCode.P_116, "prometheus", AlertAudience.BOTH, PUBLIC_SUMMARY);
        AlertMessageFormatter formatter = new AlertMessageFormatter("prod");

        assertThat(formatter.format(alert, null))
                .isEqualTo(formatter.format(alert, AlertRegister.TECHNICAL))
                .contains("OutOfMemoryError");
    }

    /* ---------------- absent summary ⇒ nothing, at every layer ---------------- */

    @Test
    @DisplayName("null / empty / blank public_summary all suppress the customer copy")
    void everyEmptyFormOfPublicSummaryFailsClosed() {
        for (String summary : new String[]{null, "", "   ", "\n\t "}) {
            Alert alert = new Alert(AlertSeverity.CRITICAL, HOSTILE_TITLE, HOSTILE_BODY,
                    ProductCode.P_116, "prometheus", AlertAudience.BOTH, summary);

            List<RoutedMessage> routed = router(OPS_ROOM, true).route(alert);

            assertThat(delivered(routed))
                    .as("public_summary=%s must deliver the ops copy only", summary)
                    .singleElement()
                    .satisfies(m -> {
                        assertThat(m.roomId()).isEqualTo(OPS_ROOM);
                        assertThat(m.register()).isEqualTo(AlertRegister.TECHNICAL);
                    });
            assertThat(routed).anySatisfy(m ->
                    assertThat(m.reason()).isEqualTo(AlertRouter.REASON_NO_PUBLIC_SUMMARY));
        }
    }

    @Test
    @DisplayName("a blank customer render is not deliverable, so a bare header can never be posted")
    void blankCustomerRenderIsNotDeliverable() {
        // Backstop below the router's gate: even if a future caller asked the formatter
        // for a customer render directly, the empty result is not a sendable message.
        String rendered = new AlertMessageFormatter("prod").format(
                Alert.forProduct(ProductCode.P_116, AlertSeverity.CRITICAL, "t", "b", "prometheus"),
                AlertRegister.CUSTOMER);

        assertThat(rendered).isEmpty();
        assertThat(new RoutedMessage(productRoom(ProductCode.P_116), rendered,
                AlertRegister.CUSTOMER, AlertRouter.OUTCOME_SENT, AlertRouter.REASON_OK)
                .isDelivered()).isFalse();
    }

    /* ---------------- the hand-authored operator path ---------------- */

    @Test
    @DisplayName("the operator HTTP path cannot produce a customer-register message")
    void operatorAuthoredAlertsAreAlwaysTechnical() {
        // POST /api/v1/alerts/product/{product} builds exactly this shape. Whatever an
        // operator types lands in title/body, i.e. in the technical register only —
        // there is no request field that can set audience=both or a public summary.
        Alert operator = Alert.forProduct(ProductCode.P_116, AlertSeverity.CRITICAL,
                "please ignore, testing", HOSTILE_BODY, "operator");

        assertThat(operator.audience()).isEqualTo(AlertAudience.PRODUCT);
        assertThat(operator.publicSummary()).isNull();
        assertThat(operator.hasPublicSummary()).isFalse();
        // ...and survives the re-product hop the webhook path uses.
        assertThat(operator.withProduct(ProductCode.P_097).publicSummary()).isNull();

        assertThat(delivered(router(OPS_ROOM, true).route(operator)))
                .singleElement()
                .satisfies(m -> assertThat(m.register()).isEqualTo(AlertRegister.TECHNICAL));
    }

    @Test
    @DisplayName("audience=product with a public_summary still sends the technical register only")
    void productAudienceNeverRendersTheCustomerCopy() {
        // AD-V3: only `both` forks the register. A stray public_summary on a
        // product-scoped rule must not quietly switch the room's voice.
        Alert alert = new Alert(AlertSeverity.CRITICAL, HOSTILE_TITLE, HOSTILE_BODY,
                ProductCode.P_116, "prometheus", AlertAudience.PRODUCT, PUBLIC_SUMMARY);

        assertThat(delivered(router(OPS_ROOM, true).route(alert)))
                .singleElement()
                .satisfies(m -> {
                    assertThat(m.register()).isEqualTo(AlertRegister.TECHNICAL);
                    assertThat(m.text()).contains("OutOfMemoryError").doesNotContain(PUBLIC_SUMMARY);
                });
    }

    /* ---------------- room-collision configuration ---------------- */

    @Test
    @DisplayName("ops room configured as a product's room: one message, technical, customer copy dropped")
    void opsRoomEqualToTheProductRoomStillSendsOnce() {
        // A misconfiguration, not a rollout state — but it must degrade to "one message"
        // rather than posting the technical and customer copies into the same room.
        String tipRoom = productRoom(ProductCode.P_116);
        List<RoutedMessage> routed = router(tipRoom, true).route(new Alert(
                AlertSeverity.CRITICAL, HOSTILE_TITLE, HOSTILE_BODY, ProductCode.P_116,
                "prometheus", AlertAudience.BOTH, PUBLIC_SUMMARY));

        assertThat(delivered(routed))
                .singleElement()
                .satisfies(m -> {
                    assertThat(m.roomId()).isEqualTo(tipRoom);
                    assertThat(m.register()).isEqualTo(AlertRegister.TECHNICAL);
                });
    }

    /* ---------------- webhook: the summary is never invented ---------------- */

    @Test
    @DisplayName("the webhook never promotes summary/description/alertname into public_summary")
    void webhookDoesNotSynthesiseACustomerSummary() {
        List<String> texts = new ArrayList<>();
        VipTalkClient client = mock(VipTalkClient.class);
        when(client.send(anyString(), anyList())).thenAnswer(invocation -> {
            texts.add(invocation.getArgument(0));
            return VipTalkSendResult.sent(1, 200);
        });
        AlertRoomRegistry rooms = new AlertRoomRegistry(OPS_ROOM);
        AlertMessageFormatter formatter = new AlertMessageFormatter("prod");
        AlertService service = new AlertService(client, rooms, formatter,
                new AlertRouter(rooms, formatter, true), new SimpleMeterRegistry());
        AlertmanagerWebhookService webhook =
                new AlertmanagerWebhookService(service, mock(EnvironmentService.class));

        webhook.handle(new AlertmanagerWebhook("4", "firing", Map.of(),
                Map.of("audience", "both", "product", "116"),
                // A payload-level `summary` is NOT a public summary, however tempting.
                Map.of("summary", "bot-manager is not scrapeable"),
                "http://alertmanager:9093",
                List.of(new AlertmanagerAlert("firing",
                        Map.of("alertname", "BotManagerDown", "severity", "critical"),
                        Map.of("summary", "bot-manager is not scrapeable",
                                "description", HOSTILE_BODY),
                        "2026-08-17T10:00:00Z", ""))));

        assertThat(texts).hasSize(1);
        assertThat(texts.getFirst()).contains("CRITICAL");
    }

    @Test
    @DisplayName("a mixed batch takes a declared public_summary, never a merge of technical text")
    void batchedCustomerCopyIsOneOfTheDeclaredSummaries() {
        List<String> texts = new ArrayList<>();
        VipTalkClient client = mock(VipTalkClient.class);
        when(client.send(anyString(), anyList())).thenAnswer(invocation -> {
            texts.add(invocation.getArgument(0));
            return VipTalkSendResult.sent(1, 200);
        });
        AlertRoomRegistry rooms = new AlertRoomRegistry(OPS_ROOM);
        AlertMessageFormatter formatter = new AlertMessageFormatter("prod");
        AlertService service = new AlertService(client, rooms, formatter,
                new AlertRouter(rooms, formatter, true), new SimpleMeterRegistry());
        AlertmanagerWebhookService webhook =
                new AlertmanagerWebhookService(service, mock(EnvironmentService.class));

        webhook.handle(new AlertmanagerWebhook("4", "firing", Map.of(),
                Map.of("audience", "both", "product", "116"), Map.of(),
                "http://alertmanager:9093",
                List.of(new AlertmanagerAlert("firing",
                                Map.of("alertname", "BotManagerDown", "severity", "critical"),
                                Map.of("summary", "not scrapeable", "public_summary", PUBLIC_SUMMARY),
                                "2026-08-17T10:00:00Z", ""),
                        new AlertmanagerAlert("firing",
                                Map.of("alertname", "GameNoRounds", "severity", "critical"),
                                Map.of("summary", HOSTILE_BODY),
                                "2026-08-17T10:00:00Z", ""))));

        // Two texts: technical to ops, customer to the product room. The customer one is
        // exactly a declared summary — the second alert's technical text is not blended in
        // (it is a known limitation that the notice then under-describes the batch; it is
        // still fail-closed, which is the property that matters).
        assertThat(texts).hasSize(2);
        assertThat(texts.get(1))
                .isEqualTo("🔴 TIP (116) · prod\n" + PUBLIC_SUMMARY)
                .doesNotContain("OutOfMemoryError");
    }
}
