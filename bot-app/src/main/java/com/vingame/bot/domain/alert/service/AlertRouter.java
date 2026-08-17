package com.vingame.bot.domain.alert.service;

import com.vingame.bot.domain.alert.model.Alert;
import com.vingame.bot.domain.alert.model.AlertAudience;
import com.vingame.bot.domain.alert.model.AlertRegister;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Decides which rooms one alert reaches and in which register
 * (VIPTALK_ALERTING_V2 AD-V3, AD-V5, AD-V6, AD-V7).
 * <p>
 * A <b>pure function</b>: {@link #route(Alert)} reads the alert, the room registry and
 * two config flags, and returns decisions. It sends nothing, counts nothing and never
 * throws — {@link AlertService} performs the delivery and owns the
 * {@code alert_dispatch_total} counter, using the outcome/reason each decision carries.
 * That split is what makes routing unit-testable with no Spring context and no mocks.
 * <p>
 * The rules:
 * <ul>
 *   <li>{@link AlertAudience#INTERNAL} → ops room, technical. No ops room ⇒ dropped.</li>
 *   <li>{@link AlertAudience#PRODUCT} → that product's room, technical. No room ⇒
 *       <b>misrouted to the ops room</b>, prefixed with an explicit misroute marker
 *       (AD-V5 as amended 2026-08-17). Only 1 of 10 products is wired, so dropping
 *       would silently discard every alert for the other 9 — including P_097/BOM.
 *       The fallback is self-retiring: filling in a product's {@code vipTalkRoomId}
 *       moves its alerts to its own room with no code change.</li>
 *   <li>{@link AlertAudience#BOTH} → ops room technical, <b>plus</b> the product room in
 *       the customer register — and only when {@code viptalk.customer-notices-enabled}
 *       is on (AD-V7: staging must not push "the application is experiencing issues"
 *       into a live product room three times a day) and there is customer copy to
 *       publish (AD-V6, fail-closed). The copy for the <b>resolved</b> half is the
 *       rule's {@code public_resolved_summary} / the configured default, never the
 *       firing wording — see {@link AlertMessageFormatter#customerSummary(Alert)}.</li>
 * </ul>
 * <b>Dedupe.</b> Decisions are deduplicated on room before they leave this class, first
 * one wins. That is the guard AD-V5 calls for: a {@code both} alert whose product has no
 * room would otherwise misroute its customer register into the ops room that is already
 * receiving the technical copy of the same alert — two messages for one condition. The
 * suppressed decision keeps its {@code misrouted} outcome for the counter (the content
 * did reach ops, via its sibling) but delivers nothing.
 */
@Slf4j
@Component
public class AlertRouter {

    /** Dispatch counter name. Deliberately not {@code bot_*}, so {@code BotMdcTagsMeterFilter} leaves it alone. */
    public static final String ALERT_DISPATCH_TOTAL = "alert_dispatch_total";

    public static final String OUTCOME_SENT = "sent";
    /** Delivered, but to the ops room instead of the product room it belongs in (AD-V5). */
    public static final String OUTCOME_MISROUTED = "misrouted";
    public static final String OUTCOME_DROPPED = "dropped";
    /** Set by {@link AlertService} when the transport rejected the message. */
    public static final String OUTCOME_FAILED = "failed";

    public static final String REASON_OK = "ok";
    public static final String REASON_NO_ROOM = "no_room";
    public static final String REASON_NO_PRODUCT = "no_product";
    public static final String REASON_NO_OPS_ROOM = "no_ops_room";
    public static final String REASON_CUSTOMER_NOTICES_DISABLED = "customer_notices_disabled";
    public static final String REASON_NO_PUBLIC_SUMMARY = "no_public_summary";
    /**
     * The recovery half of an {@code audience: both} incident had no {@code
     * public_resolved_summary} and no configured default, so nothing was published rather
     * than the firing copy being replayed under a {@code ✅} marker.
     */
    public static final String REASON_NO_RESOLVED_SUMMARY = "no_resolved_summary";
    /**
     * Set by {@link AlertService} when the transport skipped a routed message —
     * {@code viptalk.enabled=false} or a blank token. Distinguishes "we chose not to
     * route this" from "the channel is off", which otherwise both read as a non-delivery.
     */
    public static final String REASON_CHANNEL_DISABLED = "channel_disabled";

    private final AlertRoomRegistry rooms;
    private final AlertMessageFormatter formatter;
    private final boolean customerNoticesEnabled;

    public AlertRouter(AlertRoomRegistry rooms,
                       AlertMessageFormatter formatter,
                       @Value("${viptalk.customer-notices-enabled:false}") boolean customerNoticesEnabled) {
        this.rooms = rooms;
        this.formatter = formatter;
        this.customerNoticesEnabled = customerNoticesEnabled;
        log.info("VipTalk customer-facing notices {}", customerNoticesEnabled ? "enabled" : "disabled");
    }

    /**
     * @param alert the alert to route; {@code null} ⇒ no decisions.
     * @return one decision per intended destination, room-deduplicated and order-stable
     *         (internal register first). Never {@code null}; may be empty only for a
     *         {@code null} alert.
     */
    public List<RoutedMessage> route(Alert alert) {
        if (alert == null) {
            return List.of();
        }
        AlertAudience audience = alert.audience() == null ? AlertAudience.INTERNAL : alert.audience();

        // A switch EXPRESSION, deliberately with no `default`: the compiler then refuses to
        // build until a newly added AlertAudience constant is routed here. A statement with
        // no default would compile clean and produce an empty decision list — no room, no
        // WARN, no counter row, and a 200 back to Alertmanager, i.e. the alert vanishes.
        // That is exactly the silent loss AD-V4 exists to prevent.
        List<RoutedMessage> decisions = switch (audience) {
            case INTERNAL -> List.of(toOpsRoom(alert));
            case PRODUCT -> List.of(toProductRoom(alert, AlertRegister.TECHNICAL));
            case BOTH -> List.of(toOpsRoom(alert), customerCopy(alert));
        };
        return dedupeByRoom(decisions);
    }

    /** Whether customer-facing copy is allowed on this instance (AD-V7). Prod only. */
    public boolean isCustomerNoticesEnabled() {
        return customerNoticesEnabled;
    }

    private RoutedMessage toOpsRoom(Alert alert) {
        Optional<String> ops = rooms.opsRoom();
        if (ops.isEmpty()) {
            log.warn("Alert dropped — audience needs the ops room but viptalk.ops-room-id is unset: {}",
                    alert.title());
            return RoutedMessage.dropped(AlertRegister.TECHNICAL, REASON_NO_OPS_ROOM);
        }
        return RoutedMessage.delivered(ops.get(), formatter.format(alert, AlertRegister.TECHNICAL),
                AlertRegister.TECHNICAL, OUTCOME_SENT, REASON_OK);
    }

    /**
     * The product room, or the AD-V5 misroute fallback. The marker is part of the message
     * body on purpose: a reader in the ops room has to be able to tell "this is really
     * ours" from "this belongs to a product whose room does not exist yet".
     */
    private RoutedMessage toProductRoom(Alert alert, AlertRegister register) {
        Optional<String> room = rooms.roomFor(alert.product());
        if (room.isPresent()) {
            return RoutedMessage.delivered(room.get(), formatter.format(alert, register),
                    register, OUTCOME_SENT, REASON_OK);
        }

        String reason = alert.product() == null ? REASON_NO_PRODUCT : REASON_NO_ROOM;
        Optional<String> ops = rooms.opsRoom();
        if (ops.isEmpty()) {
            log.warn("Alert dropped — no VipTalk room for product {} and no ops room configured: {}",
                    alert.product(), alert.title());
            return RoutedMessage.dropped(register, reason);
        }

        log.warn("Alert misrouted to the ops room — {} for product {}: {}",
                reason, alert.product(), alert.title());
        String text = formatter.format(alert, register);
        return RoutedMessage.delivered(ops.get(), misrouteMarker(alert, reason) + text,
                register, OUTCOME_MISROUTED, reason);
    }

    /**
     * The product-room half of an {@link AlertAudience#BOTH} alert. Two independent gates,
     * both fail-closed, both counted so a silent non-delivery is still visible.
     */
    private RoutedMessage customerCopy(Alert alert) {
        if (!customerNoticesEnabled) {
            // AD-V7: not an anomaly on staging/loadtest, it is the configured posture.
            log.debug("Customer notice suppressed — viptalk.customer-notices-enabled=false: {}", alert.title());
            return RoutedMessage.dropped(AlertRegister.CUSTOMER, REASON_CUSTOMER_NOTICES_DISABLED);
        }
        // The formatter owns which copy a product room may see — firing wording for the
        // outage, recovery wording for the resolution, and nothing at all when neither is
        // available. Gating on it here (rather than on hasPublicSummary) is what keeps the
        // resolved half from replaying "…is experiencing issues…" under a ✅ marker.
        if (formatter.customerSummary(alert) == null) {
            String reason = alert.isResolved() ? REASON_NO_RESOLVED_SUMMARY : REASON_NO_PUBLIC_SUMMARY;
            log.warn("Customer notice suppressed ({}) — audience=both, severity {}: {}",
                    reason, alert.severity(), alert.title());
            return RoutedMessage.dropped(AlertRegister.CUSTOMER, reason);
        }
        return toProductRoom(alert, AlertRegister.CUSTOMER);
    }

    private String misrouteMarker(Alert alert, String reason) {
        String what = REASON_NO_PRODUCT.equals(reason)
                ? "product-scoped alert with no resolvable product"
                : "no VipTalk room for " + alert.product().getDisplayName()
                        + " (" + alert.product().getCode() + ")";
        return "↪️ MISROUTED — " + what + "; delivered here instead of a product room.\n";
    }

    /**
     * First decision per room wins. Suppressed duplicates keep their outcome and reason
     * for the counter but carry no room, so nothing is sent twice.
     */
    private List<RoutedMessage> dedupeByRoom(List<RoutedMessage> decisions) {
        Set<String> seen = new LinkedHashSet<>();
        List<RoutedMessage> deduped = new ArrayList<>(decisions.size());
        for (RoutedMessage decision : decisions) {
            if (!decision.isDelivered()) {
                deduped.add(decision);
                continue;
            }
            if (seen.add(decision.roomId())) {
                deduped.add(decision);
            } else if (decision.register() == AlertRegister.CUSTOMER) {
                // A customer notice that reaches nobody is the loss AD-V5's amendment
                // exists to avoid, so it is never a DEBUG line. Two ways to get here: the
                // product has no room yet (rollout state, self-retiring), or ops-room-id
                // has been pointed at a product room (misconfiguration — AlertRoomRegistry
                // says so at startup too).
                log.warn("Customer notice not published — room {} already holds the technical "
                                + "copy of this alert (unwired product room, or viptalk.ops-room-id "
                                + "points at a product room)", decision.roomId());
                deduped.add(decision.suppressed());
            } else {
                log.debug("Suppressed a duplicate message for room {} ({} register)",
                        decision.roomId(), decision.register());
                deduped.add(decision.suppressed());
            }
        }
        return List.copyOf(deduped);
    }

    /**
     * One routing decision.
     *
     * @param roomId   the destination, or {@code null} when nothing is to be delivered
     *                 (dropped, or deduplicated away).
     * @param text     the rendered message; {@code null} when nothing is delivered.
     * @param register which voice it was rendered in.
     * @param outcome  {@code sent} | {@code misrouted} | {@code dropped}, for the counter.
     * @param reason   why, for the counter.
     */
    public record RoutedMessage(String roomId, String text, AlertRegister register,
                                String outcome, String reason) {

        static RoutedMessage delivered(String roomId, String text, AlertRegister register,
                                       String outcome, String reason) {
            return new RoutedMessage(roomId, text, register, outcome, reason);
        }

        static RoutedMessage dropped(AlertRegister register, String reason) {
            return new RoutedMessage(null, null, register, OUTCOME_DROPPED, reason);
        }

        /** This decision with delivery removed — the room already has a message for this alert. */
        RoutedMessage suppressed() {
            return new RoutedMessage(null, null, register, outcome, reason);
        }

        public boolean isDelivered() {
            return roomId != null && text != null && !text.isBlank();
        }
    }
}
