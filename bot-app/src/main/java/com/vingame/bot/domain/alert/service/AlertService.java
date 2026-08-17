package com.vingame.bot.domain.alert.service;

import com.vingame.bot.domain.alert.model.Alert;
import com.vingame.bot.domain.alert.model.AlertDispatch;
import com.vingame.bot.domain.alert.service.AlertRouter.RoutedMessage;
import com.vingame.bot.infrastructure.notification.VipTalkClient;
import com.vingame.bot.infrastructure.notification.VipTalkSendResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Publishes alerts and announcements to VipTalk.
 * <p>
 * Two routes:
 * <ul>
 *   <li>{@link #send(Alert)} — audience-routed. {@link AlertRouter} decides the rooms and
 *       registers (VIPTALK_ALERTING_V2 AD-V3); this method performs the delivery and
 *       records the outcome. Note the ops room is <b>no longer a catch-all fallback</b>
 *       for anything unroutable — it takes {@code audience: internal} plus the tagged
 *       AD-V5 misroute of a product alert whose room does not exist yet.</li>
 *   <li>{@link #broadcast(Alert)} — fleet-wide. Goes to every wired room in a single
 *       request (VIPTALK_ALERTING AD-4). This is the maintenance-announcement path, and
 *       is unrouted by design.</li>
 * </ul>
 * One POST per distinct message text, not per room: a {@code BOTH} alert costs two
 * (technical + customer), everything else costs one. That preserves AD-4 while allowing
 * the two registers to differ.
 * <p>
 * Sends are synchronous: every current caller is either an operator waiting on an HTTP
 * response or Alertmanager, which needs a real status code to decide whether to retry
 * (AD-8). Timeouts on {@link VipTalkClient} bound the wait. If an in-app call site ever
 * needs fire-and-forget, wrap the call in a virtual thread at that site rather than
 * making this API async for everyone.
 * <p>
 * Never throws — the transport swallows its own failures (AD-3) and this layer only
 * routes, aggregates and counts.
 */
@Slf4j
@Service
public class AlertService {

    private static final String TAG_OUTCOME = "outcome";
    private static final String TAG_REASON = "reason";
    private static final String TAG_PRODUCT = "product";
    private static final String PRODUCT_NONE = "none";

    private final VipTalkClient client;
    private final AlertRoomRegistry rooms;
    private final AlertMessageFormatter formatter;
    private final AlertRouter router;
    private final MeterRegistry registry;

    public AlertService(VipTalkClient client,
                        AlertRoomRegistry rooms,
                        AlertMessageFormatter formatter,
                        AlertRouter router,
                        MeterRegistry registry) {
        this.client = client;
        this.rooms = rooms;
        this.formatter = formatter;
        this.router = router;
        this.registry = registry;
    }

    /**
     * Routes one alert by its audience and delivers it.
     *
     * @param alert the alert to publish.
     * @return the outcome across every room written to; never {@code null}.
     */
    public AlertDispatch send(Alert alert) {
        List<RoutedMessage> routed = router.route(alert);

        // Group by text so two rooms receiving the identical message cost one POST.
        // Non-delivered decisions (dropped, or deduped away) are counted here and never
        // reach the transport.
        Map<String, List<RoutedMessage>> byText = new LinkedHashMap<>();
        for (RoutedMessage decision : routed) {
            if (decision.isDelivered()) {
                byText.computeIfAbsent(decision.text(), text -> new ArrayList<>()).add(decision);
            } else {
                count(alert, decision.outcome(), decision.reason());
            }
        }

        if (byText.isEmpty()) {
            return AlertDispatch.skipped(describeUndelivered(alert, routed));
        }

        List<VipTalkSendResult> results = new ArrayList<>(byText.size());
        byText.forEach((text, decisions) -> {
            List<String> targets = decisions.stream().map(RoutedMessage::roomId).distinct().toList();
            VipTalkSendResult result = client.send(text, targets);
            for (RoutedMessage decision : decisions) {
                count(alert, transportOutcome(result, decision), transportReason(result, decision));
            }
            results.add(result);
        });
        return AlertDispatch.aggregate(results);
    }

    /**
     * Publishes to every wired room — all product rooms plus the ops room — in one request.
     * Intended for fleet-wide announcements (maintenance windows, deploys), which have no
     * audience: an announcement is for everybody by definition, so it bypasses the router.
     *
     * @param alert the announcement. Any product on it is ignored for routing but still
     *              rendered in the header.
     * @return the outcome; never {@code null}.
     */
    public AlertDispatch broadcast(Alert alert) {
        List<String> targets = rooms.broadcastRooms();
        if (targets.isEmpty()) {
            log.warn("Broadcast dropped — no VipTalk rooms are wired: {}", alert.title());
            return AlertDispatch.skipped("no VipTalk rooms are wired");
        }
        return AlertDispatch.of(client.send(formatter.format(alert), targets));
    }

    /** Whether VipTalk delivery is actually active (enabled and a token is present). */
    public boolean isEnabled() {
        return client.isEnabled();
    }

    /**
     * A delivery that the transport rejected is {@code failed}, not the outcome the router
     * intended. A skipped one (channel disabled, empty body) did not reach anyone either,
     * so it must not be counted as {@code sent}.
     */
    private String transportOutcome(VipTalkSendResult result, RoutedMessage decision) {
        if (result.isFailed()) {
            return AlertRouter.OUTCOME_FAILED;
        }
        return result.isSent() ? decision.outcome() : AlertRouter.OUTCOME_DROPPED;
    }

    private String transportReason(VipTalkSendResult result, RoutedMessage decision) {
        return result.isFailed() || result.isSent() ? decision.reason() : AlertRouter.REASON_CHANNEL_DISABLED;
    }

    /**
     * {@code alert_dispatch_total{outcome, reason, product}} — the single view of where
     * alerts actually land. {@code outcome="misrouted"} is the AD-V5 rollout signal:
     * how much product-scoped traffic is still falling back to the ops room because the
     * product has no room of its own yet.
     */
    private void count(Alert alert, String outcome, String reason) {
        Counter.builder(AlertRouter.ALERT_DISPATCH_TOTAL)
                .description("VipTalk alert dispatches by routing outcome")
                .tag(TAG_OUTCOME, outcome)
                .tag(TAG_REASON, reason)
                .tag(TAG_PRODUCT, alert == null || alert.product() == null
                        ? PRODUCT_NONE : alert.product().getCode())
                .register(registry)
                .increment();
    }

    /** Operator-readable summary of why nothing was delivered — returned in the HTTP body. */
    private String describeUndelivered(Alert alert, List<RoutedMessage> routed) {
        if (routed.isEmpty()) {
            return "nothing to send";
        }
        String reasons = routed.stream()
                .map(RoutedMessage::reason)
                .distinct()
                .collect(Collectors.joining(", "));
        log.warn("Alert not delivered to any room ({}) — audience {}, product {}: {}",
                reasons, alert.audience(), alert.product(), alert.title());
        return "no room for this alert: " + reasons;
    }
}
