package com.vingame.bot.domain.alert.controller;

import com.vingame.bot.domain.alert.dto.AlertmanagerWebhook;
import com.vingame.bot.domain.alert.model.Alert;
import com.vingame.bot.domain.alert.model.AlertDispatch;
import com.vingame.bot.domain.alert.model.AlertSeverity;
import com.vingame.bot.domain.alert.service.AlertRoomRegistry;
import com.vingame.bot.domain.alert.service.AlertService;
import com.vingame.bot.domain.alert.service.AlertmanagerWebhookService;
import com.vingame.bot.domain.brand.model.ProductCode;
import com.vingame.bot.infrastructure.notification.VipTalkSendResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP contract of the alert endpoints, with the status mapping as the point.
 * <p>
 * <b>Why this matters more than a usual controller test.</b> Alertmanager decides whether
 * an alert has been delivered from the status code alone: it retries 5xx and drops 2xx
 * (VIPTALK_ALERTING AD-8). So {@code 502 on a VipTalk failure} is what keeps an alert
 * alive across a transient outage of the messenger, and {@code 200 when the channel is
 * disabled} is what stops a staging box with alerting off from making Alertmanager retry
 * forever. Neither is expressible in the service layer, and neither was covered.
 */
@WebMvcTest(AlertController.class)
@DisplayName("AlertController — status mapping Alertmanager's retry behaviour depends on")
class AlertControllerTest {

    private static final String WEBHOOK_BODY = """
            {
              "version": "4",
              "status": "firing",
              "commonLabels": {"audience": "internal"},
              "alerts": [
                {"status": "firing",
                 "labels": {"alertname": "BotManagerDown", "severity": "critical"},
                 "annotations": {"summary": "bot-manager is not scrapeable"}}
              ]
            }""";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AlertService alertService;

    @MockitoBean
    private AlertmanagerWebhookService webhookService;

    @MockitoBean
    private AlertRoomRegistry rooms;

    /* ---------------- the webhook path (AD-8) ---------------- */

    @Test
    @DisplayName("a VipTalk failure answers 502, so Alertmanager retries the alert")
    void webhookAnswers502WhenDeliveryFailed() throws Exception {
        when(webhookService.handle(any())).thenReturn(new AlertDispatch(
                VipTalkSendResult.Outcome.FAILED, 1, "VipTalk returned 503"));

        mockMvc.perform(post("/api/v1/alerts/alertmanager")
                        .contentType(MediaType.APPLICATION_JSON).content(WEBHOOK_BODY))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.outcome").value("FAILED"))
                .andExpect(jsonPath("$.detail").value("VipTalk returned 503"));
    }

    @Test
    @DisplayName("a disabled channel answers 200 — nothing was lost, so there is nothing to retry")
    void webhookAnswers200WhenChannelDisabled() throws Exception {
        when(webhookService.handle(any())).thenReturn(
                AlertDispatch.skipped("VipTalk channel is disabled"));

        mockMvc.perform(post("/api/v1/alerts/alertmanager")
                        .contentType(MediaType.APPLICATION_JSON).content(WEBHOOK_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("SKIPPED"))
                .andExpect(jsonPath("$.roomCount").value(0))
                .andExpect(jsonPath("$.detail").value("VipTalk channel is disabled"));
    }

    @Test
    @DisplayName("a delivered alert answers 200 with the room count")
    void webhookAnswers200WhenSent() throws Exception {
        when(webhookService.handle(any())).thenReturn(
                AlertDispatch.of(VipTalkSendResult.sent(2, 200)));

        mockMvc.perform(post("/api/v1/alerts/alertmanager")
                        .contentType(MediaType.APPLICATION_JSON).content(WEBHOOK_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("SENT"))
                .andExpect(jsonPath("$.roomCount").value(2));
    }

    @Test
    @DisplayName("the payload reaches the webhook service parsed, not swallowed by binding")
    void webhookPayloadIsDeserialisedAndPassedThrough() throws Exception {
        when(webhookService.handle(any())).thenReturn(
                AlertDispatch.of(VipTalkSendResult.sent(1, 200)));

        mockMvc.perform(post("/api/v1/alerts/alertmanager")
                        .contentType(MediaType.APPLICATION_JSON).content(WEBHOOK_BODY))
                .andExpect(status().isOk());

        ArgumentCaptor<AlertmanagerWebhook> captor = ArgumentCaptor.forClass(AlertmanagerWebhook.class);
        verify(webhookService).handle(captor.capture());
        AlertmanagerWebhook payload = captor.getValue();
        assertThat(payload.alerts()).hasSize(1);
        assertThat(payload.alerts().getFirst().label("alertname")).isEqualTo("BotManagerDown");
        assertThat(payload.commonLabels()).containsEntry("audience", "internal");
    }

    /* ---------------- the operator publish paths ---------------- */

    @Test
    @DisplayName("POST /product/{product} accepts the numeric code, the enum name and the product name")
    void productPathAcceptsEveryProductSpelling() throws Exception {
        when(alertService.send(any())).thenReturn(AlertDispatch.of(VipTalkSendResult.sent(1, 200)));

        for (String spelling : new String[]{"116", "P_116", "TIP"}) {
            mockMvc.perform(post("/api/v1/alerts/product/" + spelling)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"probe\",\"severity\":\"WARNING\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.outcome").value("SENT"));
        }

        ArgumentCaptor<Alert> captor = ArgumentCaptor.forClass(Alert.class);
        verify(alertService, times(3)).send(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(alert -> {
            assertThat(alert.product()).isEqualTo(ProductCode.P_116);
            assertThat(alert.severity()).isEqualTo(AlertSeverity.WARNING);
            assertThat(alert.source()).isEqualTo("operator");
            // The operator path can never author customer-facing copy (AD-V6).
            assertThat(alert.publicSummary()).isNull();
            assertThat(alert.publicResolvedSummary()).isNull();
        });
    }

    @Test
    @DisplayName("an unknown product is a 400, and nothing is published")
    void unknownProductIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/alerts/product/999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"probe\"}"))
                .andExpect(status().isBadRequest());

        verify(alertService, never()).send(any());
    }

    @Test
    @DisplayName("a blank title is a 400 before anything is published")
    void blankTitleIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/alerts/product/116")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"  \"}"))
                .andExpect(status().isBadRequest());

        verify(alertService, never()).send(any());
    }

    @Test
    @DisplayName("broadcast maps a failure to 502 exactly like the webhook path")
    void broadcastFailureIsAlso502() throws Exception {
        when(alertService.broadcast(any())).thenReturn(new AlertDispatch(
                VipTalkSendResult.Outcome.FAILED, 2, "VipTalk returned 500"));

        mockMvc.perform(post("/api/v1/alerts/broadcast")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Scheduled maintenance\",\"body\":\"22:00-23:00\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.outcome").value("FAILED"));

        ArgumentCaptor<Alert> captor = ArgumentCaptor.forClass(Alert.class);
        verify(alertService).broadcast(captor.capture());
        // An announcement is fleet-wide: no product, and INFO unless one is given.
        assertThat(captor.getValue().product()).isNull();
        assertThat(captor.getValue().severity()).isEqualTo(AlertSeverity.INFO);
    }

    /* ---------------- introspection ---------------- */

    @Test
    @DisplayName("GET /rooms reports wiring without ever returning a room ID")
    void roomsMasksTheRoomIds() throws Exception {
        when(alertService.isEnabled()).thenReturn(true);
        when(rooms.opsRoom()).thenReturn(Optional.of("!ops:matrix-uat.viptalk.org"));
        when(rooms.broadcastRooms()).thenReturn(List.of("!ops:matrix-uat.viptalk.org"));
        when(rooms.coverage()).thenReturn(new EnumMap<>(Map.of(
                ProductCode.P_116, true, ProductCode.P_097, false)));

        String body = mockMvc.perform(get("/api/v1/alerts/rooms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.opsRoomConfigured").value(true))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("matrix-uat.viptalk.org");
    }
}
