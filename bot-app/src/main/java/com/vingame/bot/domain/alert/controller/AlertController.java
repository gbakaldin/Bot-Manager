package com.vingame.bot.domain.alert.controller;

import com.vingame.bot.common.exception.BadRequestException;
import com.vingame.bot.domain.alert.dto.AlertDispatchDTO;
import com.vingame.bot.domain.alert.dto.AlertRequest;
import com.vingame.bot.domain.alert.dto.AlertRoomsDTO;
import com.vingame.bot.domain.alert.dto.AlertmanagerWebhook;
import com.vingame.bot.domain.alert.model.Alert;
import com.vingame.bot.domain.alert.model.AlertDispatch;
import com.vingame.bot.domain.alert.service.AlertRoomRegistry;
import com.vingame.bot.domain.alert.service.AlertService;
import com.vingame.bot.domain.alert.service.AlertmanagerWebhookService;
import com.vingame.bot.domain.alert.service.ProductCodes;
import com.vingame.bot.domain.brand.model.ProductCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * Publishing surface for VipTalk alerts and announcements (VIPTALK_ALERTING Phase 2).
 * <ul>
 *   <li>{@code POST /product/{product}} — publish to one product's room.</li>
 *   <li>{@code POST /broadcast} — publish to every wired room. The maintenance-notice path.</li>
 *   <li>{@code POST /alertmanager} — Prometheus Alertmanager webhook receiver.</li>
 *   <li>{@code GET  /rooms} — which products are wired (room IDs masked).</li>
 * </ul>
 * Exposure mirrors the existing public {@code /api/v1/metrics/**} and
 * {@code /bot-group/{id}/health}: unauthenticated. Note this endpoint can write into
 * team chat rooms, so it is a stronger argument for the auth work tracked against those
 * than they are on their own.
 * <p>
 * <b>Status codes are load-bearing on the webhook path</b> (AD-8): Alertmanager retries
 * 5xx and drops 2xx. A VipTalk delivery failure therefore answers 502 (retry — the alert
 * has not reached anyone), while a disabled channel answers 200 (nothing to retry).
 */
@RestController
@RequestMapping("api/v1/alerts")
public class AlertController {

    private final AlertService alertService;
    private final AlertmanagerWebhookService webhookService;
    private final AlertRoomRegistry rooms;

    public AlertController(AlertService alertService,
                           AlertmanagerWebhookService webhookService,
                           AlertRoomRegistry rooms) {
        this.alertService = alertService;
        this.webhookService = webhookService;
        this.rooms = rooms;
    }

    @Operation(
            summary = "Publish a message to one product's VipTalk room",
            description = "The product accepts its numeric code (116), enum name (P_116) "
                    + "or product name (TIP). A product with no room wired misroutes to the "
                    + "ops room, tagged as misrouted (VIPTALK_ALERTING_V2 AD-V5).")
    @PostMapping("/product/{product}")
    public ResponseEntity<AlertDispatchDTO> sendToProduct(
            @PathVariable @Parameter(description = "Product code, e.g. 116") String product,
            @Valid @RequestBody AlertRequest request) {

        ProductCode productCode = ProductCodes.parse(product)
                .orElseThrow(() -> new BadRequestException("Unknown product: " + product));

        AlertDispatch dispatch = alertService.send(Alert.forProduct(
                productCode, request.severityOrDefault(), request.title(), request.body(), "operator"));
        return respond(dispatch);
    }

    @Operation(
            summary = "Publish a message to every wired VipTalk room",
            description = "Fleet-wide announcement — all product rooms plus the ops room, "
                    + "delivered in a single request. Use for maintenance notices.")
    @PostMapping("/broadcast")
    public ResponseEntity<AlertDispatchDTO> broadcast(@Valid @RequestBody AlertRequest request) {
        AlertDispatch dispatch = alertService.broadcast(Alert.announcement(
                request.severityOrDefault(), request.title(), request.body(), "operator"));
        return respond(dispatch);
    }

    @Operation(
            summary = "Alertmanager webhook receiver",
            description = "Consumes the standard Alertmanager v4 webhook payload and routes each "
                    + "alert by its 'audience' label: internal → ops room, product → that "
                    + "product's room (by the 'product' label, else by resolving 'environmentId' "
                    + "to a product), both → ops room technically plus the product room in a "
                    + "customer-facing register. Absent audience defaults to internal. Alerts "
                    + "sharing a (product, audience) are batched into one message. Answers 502 on "
                    + "delivery failure so Alertmanager retries.")
    @PostMapping("/alertmanager")
    public ResponseEntity<AlertDispatchDTO> alertmanager(@RequestBody AlertmanagerWebhook payload) {
        return respond(webhookService.handle(payload));
    }

    @Operation(
            summary = "VipTalk room wiring",
            description = "Whether alerting is enabled and which products have a room configured. "
                    + "Room IDs are not returned.")
    @GetMapping("/rooms")
    public ResponseEntity<AlertRoomsDTO> rooms() {
        List<AlertRoomsDTO.ProductRoom> products = new ArrayList<>();
        rooms.coverage().forEach((product, wired) ->
                products.add(new AlertRoomsDTO.ProductRoom(product.getCode(), product.getName(), wired)));

        return ResponseEntity.ok(new AlertRoomsDTO(
                alertService.isEnabled(),
                rooms.opsRoom().isPresent(),
                rooms.broadcastRooms().size(),
                products));
    }

    /**
     * Maps a dispatch onto a status code. FAILED ⇒ 502 (upstream VipTalk did not accept
     * the message); SENT and SKIPPED ⇒ 200 with the reason in the body.
     */
    private ResponseEntity<AlertDispatchDTO> respond(AlertDispatch dispatch) {
        AlertDispatchDTO dto = AlertDispatchDTO.from(dispatch);
        return dispatch.isFailed()
                ? ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(dto)
                : ResponseEntity.ok(dto);
    }
}
