package com.vingame.bot.domain.alert.service;

import com.vingame.bot.domain.alert.dto.AlertmanagerWebhook;
import com.vingame.bot.domain.alert.dto.AlertmanagerWebhook.AlertmanagerAlert;
import com.vingame.bot.domain.alert.model.Alert;
import com.vingame.bot.domain.alert.model.AlertAudience;
import com.vingame.bot.domain.alert.model.AlertDispatch;
import com.vingame.bot.domain.alert.model.AlertSeverity;
import com.vingame.bot.domain.brand.model.ProductCode;
import com.vingame.bot.domain.environment.model.Environment;
import com.vingame.bot.domain.environment.service.EnvironmentService;
import com.vingame.bot.infrastructure.notification.VipTalkSendResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Routing and batching of Alertmanager payloads (VIPTALK_ALERTING AD-5, AD-6; audience
 * and public_summary per VIPTALK_ALERTING_V2 AD-V3/AD-V4/AD-V6).
 */
class AlertmanagerWebhookServiceTest {

    private AlertService alertService;
    private EnvironmentService environmentService;
    private AlertmanagerWebhookService service;

    @BeforeEach
    void setUp() {
        alertService = mock(AlertService.class);
        environmentService = mock(EnvironmentService.class);
        when(alertService.send(any())).thenReturn(
                new AlertDispatch(VipTalkSendResult.Outcome.SENT, 1, "delivered to 1 room(s)"));
        service = new AlertmanagerWebhookService(alertService, environmentService);
    }

    private static AlertmanagerAlert alert(String status, Map<String, String> labels, String summary) {
        return new AlertmanagerAlert(status, labels, Map.of("summary", summary), "2026-08-12T10:00:00Z", "");
    }

    private static AlertmanagerAlert alert(String status, Map<String, String> labels,
                                           Map<String, String> annotations) {
        return new AlertmanagerAlert(status, labels, annotations, "2026-08-12T10:00:00Z", "");
    }

    private static AlertmanagerWebhook payload(Map<String, String> commonLabels, AlertmanagerAlert... alerts) {
        return new AlertmanagerWebhook("4", "firing", Map.of(), commonLabels, Map.of(),
                "http://alertmanager:9093", List.of(alerts));
    }

    @Test
    void routesByProductLabel() {
        service.handle(payload(Map.of(),
                alert("firing", Map.of("alertname", "BotGroupDead", "severity", "critical", "product", "116"),
                        "1 group DEAD")));

        Alert sent = captureSent();
        assertEquals(ProductCode.P_116, sent.product());
        assertEquals(AlertSeverity.CRITICAL, sent.severity());
        assertEquals("BotGroupDead", sent.title());
        assertTrue(sent.body().contains("1 group DEAD"));
        verify(environmentService, never()).findById(anyString());
    }

    @Test
    void productLabelAcceptsNameAndEnumForms() {
        service.handle(payload(Map.of(),
                alert("firing", Map.of("alertname", "A", "product", "TIP"), "s")));
        assertEquals(ProductCode.P_116, captureSent().product());

        setUp();
        service.handle(payload(Map.of(),
                alert("firing", Map.of("alertname", "A", "product", "P_097"), "s")));
        assertEquals(ProductCode.P_097, captureSent().product());
    }

    @Test
    void fallsBackToEnvironmentLookupWhenNoProductLabel() {
        when(environmentService.findById("env-1")).thenReturn(
                Environment.builder().id("env-1").productCode(ProductCode.P_098).build());

        service.handle(payload(Map.of(),
                alert("firing", Map.of("alertname", "DeadBotRatioHigh", "severity", "warning",
                        "environmentId", "env-1"), "20% dead")));

        Alert sent = captureSent();
        assertEquals(ProductCode.P_098, sent.product());
        assertTrue(sent.body().contains("(env env-1)"));
    }

    @Test
    void environmentLookupIsMemoisedPerPayload() {
        when(environmentService.findById("env-1")).thenReturn(
                Environment.builder().id("env-1").productCode(ProductCode.P_098).build());

        service.handle(payload(Map.of(),
                alert("firing", Map.of("alertname", "A", "environmentId", "env-1"), "one"),
                alert("firing", Map.of("alertname", "A", "environmentId", "env-1"), "two"),
                alert("firing", Map.of("alertname", "A", "environmentId", "env-1"), "three")));

        verify(environmentService, times(1)).findById("env-1");
        // Same room ⇒ one batched message, not three (AD-6).
        verify(alertService, times(1)).send(any());
    }

    @Test
    void unknownEnvironmentFallsThroughToNoProductInsteadOfThrowing() {
        when(environmentService.findById("gone")).thenThrow(new RuntimeException("Environment not found"));

        AlertDispatch dispatch = service.handle(payload(Map.of(),
                alert("firing", Map.of("alertname", "A", "environmentId", "gone"), "s")));

        assertEquals(VipTalkSendResult.Outcome.SENT, dispatch.outcome());
        assertEquals(null, captureSent().product(), "unresolvable product ⇒ ops-room routing");
    }

    @Test
    void alertsForDifferentProductsGetSeparateMessages() {
        service.handle(payload(Map.of(),
                alert("firing", Map.of("alertname", "A", "product", "116"), "tip"),
                alert("firing", Map.of("alertname", "A", "product", "097"), "bom")));

        verify(alertService, times(2)).send(any());
    }

    @Test
    void batchTitleCountsRepeatsAndSeverityTakesTheWorst() {
        service.handle(payload(Map.of("product", "116"),
                alert("firing", Map.of("alertname", "A", "severity", "warning"), "one"),
                alert("firing", Map.of("alertname", "A", "severity", "critical"), "two")));

        Alert sent = captureSent();
        assertEquals("A (×2)", sent.title());
        assertEquals(AlertSeverity.CRITICAL, sent.severity(), "one critical must not be softened by a warning");
    }

    @Test
    void allResolvedBatchRendersAsResolved() {
        service.handle(payload(Map.of("product", "116"),
                alert("resolved", Map.of("alertname", "A", "severity", "critical"), "cleared")));

        Alert sent = captureSent();
        assertEquals(AlertSeverity.RESOLVED, sent.severity());
        assertTrue(sent.body().contains("[resolved]"));
    }

    @Test
    void audienceLabelIsReadOffTheRule() {
        service.handle(payload(Map.of(),
                alert("firing", Map.of("alertname", "GameNoRounds", "audience", "product",
                        "product", "116"), "no rounds")));

        assertEquals(AlertAudience.PRODUCT, captureSent().audience());
    }

    @Test
    void audienceFallsBackToCommonLabelsThenToInternal() {
        service.handle(payload(Map.of("audience", "both"),
                alert("firing", Map.of("alertname", "BotManagerDown"), "down")));
        assertEquals(AlertAudience.BOTH, captureSent().audience());

        // AD-V4: an unlabelled rule defaults to the room that is guaranteed to exist,
        // which is also exactly today's pre-Phase-3 behaviour for every existing rule.
        setUp();
        service.handle(payload(Map.of(),
                alert("firing", Map.of("alertname", "JvmThreadsHigh"), "threads")));
        assertEquals(AlertAudience.INTERNAL, captureSent().audience());
    }

    @Test
    void publicSummaryAnnotationIsCarriedOntoTheAlert() {
        service.handle(payload(Map.of(),
                alert("firing", Map.of("alertname", "BotManagerDown", "audience", "both", "product", "116"),
                        Map.of("summary", "bot-manager is not scrapeable",
                                "public_summary", "ALERT! Bot Management application is experiencing issues."))));

        Alert sent = captureSent();
        assertEquals("ALERT! Bot Management application is experiencing issues.", sent.publicSummary());
        assertTrue(sent.body().contains("bot-manager is not scrapeable"),
                "the technical body is unaffected by the customer copy");
    }

    @Test
    void missingPublicSummaryLeavesItNullRatherThanSynthesised() {
        service.handle(payload(Map.of(),
                alert("firing", Map.of("alertname", "BotManagerDown", "audience", "both"), "technical text")));

        // AD-V6 is fail-closed at the router; the webhook must not invent a customer copy.
        assertEquals(null, captureSent().publicSummary());
    }

    @Test
    void sameProductWithDifferentAudiencesIsNotMergedIntoOneMessage() {
        service.handle(payload(Map.of("product", "116"),
                alert("firing", Map.of("alertname", "HostDiskSpaceLow", "audience", "internal"), "disk"),
                alert("firing", Map.of("alertname", "GameNoRounds", "audience", "product"), "rounds")));

        // Different rooms and different registers — merging them would put product detail
        // in the ops room, or operator detail in a product room.
        verify(alertService, times(2)).send(any());
    }

    @Test
    void sameProductAndAudienceStillBatchesIntoOneMessage() {
        service.handle(payload(Map.of("product", "116", "audience", "product"),
                alert("firing", Map.of("alertname", "GameNoRounds"), "game a"),
                alert("firing", Map.of("alertname", "GameNoRounds"), "game b")));

        verify(alertService, times(1)).send(any());
        assertEquals("GameNoRounds (×2)", captureSent().title());
    }

    @Test
    void emptyPayloadIsSkippedNotFailed() {
        AlertDispatch dispatch = service.handle(
                new AlertmanagerWebhook("4", "firing", Map.of(), Map.of(), Map.of(), null, List.of()));

        assertEquals(VipTalkSendResult.Outcome.SKIPPED, dispatch.outcome());
        verify(alertService, never()).send(any());
    }

    @Test
    void nullPayloadIsSkippedNotFailed() {
        assertEquals(VipTalkSendResult.Outcome.SKIPPED, service.handle(null).outcome());
    }

    private Alert captureSent() {
        ArgumentCaptor<Alert> captor = ArgumentCaptor.forClass(Alert.class);
        verify(alertService, org.mockito.Mockito.atLeastOnce()).send(captor.capture());
        return captor.getValue();
    }
}
