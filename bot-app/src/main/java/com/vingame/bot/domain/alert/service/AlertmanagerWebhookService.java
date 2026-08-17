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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Turns an Alertmanager webhook payload into VipTalk messages, one per target room.
 * <p>
 * <b>Routing</b> (VIPTALK_ALERTING AD-5) resolves each alert's product from, in order:
 * <ol>
 *   <li>the {@code product} label — {@code "116"}, {@code "P_116"} or {@code "TIP"};</li>
 *   <li>the {@code environmentId} label, looked up in Mongo and read off
 *       {@link Environment#getProductCode()};</li>
 *   <li>nothing — the product could not be resolved, which {@link AlertRouter} handles
 *       per audience (AD-V5).</li>
 * </ol>
 * Step 2 is what makes environment-scoped rules routable: several meters carry
 * {@code environmentId} without a {@code product}. Lookups are memoised per payload —
 * one Alertmanager group commonly carries many alerts from the same environment.
 * <p>
 * <b>Audience</b> (VIPTALK_ALERTING_V2 AD-V3/AD-V4) comes from the rule's
 * {@code audience} label, falling back to the payload's {@code commonLabels} and then to
 * {@link AlertAudience#INTERNAL}. The customer-facing copy for an
 * {@link AlertAudience#BOTH} alert comes from the {@code public_summary} annotation — and,
 * for the {@code send_resolved} half of the same incident, from
 * {@code public_resolved_summary} — and is never synthesised here (AD-V6); the formatter
 * and router decide whether it is rendered at all.
 * <p>
 * <b>Batching</b>: alerts that resolve to the same {@code (product, audience)} are
 * rendered into a single message rather than one message per alert. Alertmanager already
 * groups and repeats; fanning each group back out into N messages would undo that (AD-6).
 * Audience is part of the key because an {@code internal} and a {@code product} alert in
 * one Alertmanager group belong in different rooms and different registers — merging them
 * would send product detail to ops or operator detail to a product room.
 * <p>
 * Never throws — a malformed payload produces a skipped dispatch, not a 500 that
 * Alertmanager would retry forever.
 */
@Slf4j
@Service
public class AlertmanagerWebhookService {

    private static final String LABEL_PRODUCT = "product";
    private static final String LABEL_ENVIRONMENT = "environmentId";
    private static final String LABEL_ALERTNAME = "alertname";
    private static final String LABEL_SEVERITY = "severity";
    private static final String LABEL_AUDIENCE = "audience";
    private static final String ANNOTATION_PUBLIC_SUMMARY = "public_summary";
    /**
     * Recovery copy for the {@code send_resolved} half of an {@code audience: both} rule.
     * Read exactly like {@code public_summary} and never derived from it (AD-V6).
     */
    private static final String ANNOTATION_PUBLIC_RESOLVED_SUMMARY = "public_resolved_summary";

    /** Cap on alerts rendered into one message; the rest are summarised as a count. */
    private static final int MAX_RENDERED_ALERTS = 20;

    private final AlertService alertService;
    private final EnvironmentService environmentService;

    public AlertmanagerWebhookService(AlertService alertService, EnvironmentService environmentService) {
        this.alertService = alertService;
        this.environmentService = environmentService;
    }

    /**
     * Routes and publishes every alert in the payload.
     *
     * @param payload the Alertmanager webhook body.
     * @return the aggregate outcome across all rooms written to. FAILED here means the
     *         controller answers 5xx and Alertmanager retries (AD-8).
     */
    public AlertDispatch handle(AlertmanagerWebhook payload) {
        if (payload == null || payload.alerts() == null || payload.alerts().isEmpty()) {
            log.debug("Alertmanager webhook carried no alerts");
            return AlertDispatch.skipped("payload contained no alerts");
        }

        Map<String, String> commonLabels = payload.commonLabels() == null ? Map.of() : payload.commonLabels();
        Map<String, String> commonAnnotations =
                payload.commonAnnotations() == null ? Map.of() : payload.commonAnnotations();
        Map<String, ProductCode> environmentCache = new HashMap<>();

        // Nullable product in the key = "product could not be resolved". LinkedHashMap keeps
        // batch order stable (and therefore message order deterministic) for a given payload.
        Map<BatchKey, List<AlertmanagerAlert>> batches = new LinkedHashMap<>();
        for (AlertmanagerAlert alert : payload.alerts()) {
            ProductCode product = resolveProduct(alert, commonLabels, environmentCache).orElse(null);
            AlertAudience audience = resolveAudience(alert, commonLabels);
            batches.computeIfAbsent(new BatchKey(product, audience), key -> new ArrayList<>()).add(alert);
        }

        List<VipTalkSendResult> results = new ArrayList<>(batches.size());
        batches.forEach((key, alerts) -> {
            AlertDispatch dispatch = alertService.send(toAlert(key, alerts, commonAnnotations));
            results.add(new VipTalkSendResult(dispatch.outcome(), dispatch.roomCount(), 0, dispatch.detail()));
        });

        AlertDispatch dispatch = AlertDispatch.aggregate(results);
        log.debug("Alertmanager webhook: {} alert(s) → {} room(s), outcome {}",
                payload.alerts().size(), dispatch.roomCount(), dispatch.outcome());
        return dispatch;
    }

    /** What one message is batched over: same product, same audience (AD-V3). */
    private record BatchKey(ProductCode product, AlertAudience audience) {}

    /**
     * Collapses one batch into a single {@link Alert}. The title names the alert when the
     * batch is homogeneous and counts them when it is not; the body is one bullet per alert.
     *
     * @param commonAnnotations payload-level annotations, consulted for
     *                          {@code public_summary} when the individual alerts carry none.
     */
    private Alert toAlert(BatchKey key, List<AlertmanagerAlert> alerts, Map<String, String> commonAnnotations) {
        AlertSeverity severity = worstSeverity(alerts);

        List<String> names = alerts.stream()
                .map(a -> value(a.label(LABEL_ALERTNAME), "alert"))
                .distinct()
                .toList();
        String title = names.size() == 1
                ? names.getFirst() + (alerts.size() > 1 ? " (×" + alerts.size() + ")" : "")
                : alerts.size() + " alerts: " + String.join(", ", names);

        StringBuilder body = new StringBuilder();
        int rendered = Math.min(alerts.size(), MAX_RENDERED_ALERTS);
        for (int i = 0; i < rendered; i++) {
            if (i > 0) {
                body.append('\n');
            }
            body.append("• ").append(describe(alerts.get(i)));
        }
        if (alerts.size() > rendered) {
            body.append("\n… and ").append(alerts.size() - rendered).append(" more");
        }

        return new Alert(severity, title, body.toString(), key.product(), "prometheus",
                key.audience(),
                annotation(alerts, commonAnnotations, ANNOTATION_PUBLIC_SUMMARY),
                annotation(alerts, commonAnnotations, ANNOTATION_PUBLIC_RESOLVED_SUMMARY));
    }

    /**
     * A customer-facing annotation for the batch: the first non-blank per-alert value, else
     * the payload-level one, else {@code null}. A batch is one alertname in practice
     * (Alertmanager groups by it), so "first" is not arbitrary; and AD-V6 makes the absence
     * of a summary mean "render nothing", not "fall back to the technical text".
     */
    private String annotation(List<AlertmanagerAlert> alerts,
                              Map<String, String> commonAnnotations, String name) {
        for (AlertmanagerAlert alert : alerts) {
            String value = alert.annotation(name);
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        String common = commonAnnotations.get(name);
        return common == null || common.isBlank() ? null : common.strip();
    }

    /**
     * The audience the rule declared. Falls back to the payload's common labels — the
     * audience is constant per rule, so Alertmanager frequently hoists it there — and then
     * to {@link AlertAudience#INTERNAL} (AD-V4).
     */
    private AlertAudience resolveAudience(AlertmanagerAlert alert, Map<String, String> commonLabels) {
        String label = alert.label(LABEL_AUDIENCE);
        if (label == null || label.isBlank()) {
            label = commonLabels.get(LABEL_AUDIENCE);
        }
        return AlertAudience.fromLabel(label);
    }

    /**
     * One line for one alert: its state marker, the human text from annotations, and the
     * environment it came from when that is not already implied by the room.
     */
    private String describe(AlertmanagerAlert alert) {
        String text = value(alert.annotation("summary"),
                value(alert.annotation("description"),
                        value(alert.label(LABEL_ALERTNAME), "(no summary)")));

        StringBuilder line = new StringBuilder();
        if (alert.isResolved()) {
            line.append("[resolved] ");
        }
        line.append(text);

        String environmentId = alert.label(LABEL_ENVIRONMENT);
        if (environmentId != null && !environmentId.isBlank()) {
            line.append(" (env ").append(environmentId).append(')');
        }
        return line.toString();
    }

    /**
     * The severity the batch is presented at. Resolved-only batches render as RESOLVED;
     * otherwise the highest severity among the firing alerts wins, so one critical in a
     * batch of warnings is not softened.
     */
    private AlertSeverity worstSeverity(List<AlertmanagerAlert> alerts) {
        boolean allResolved = alerts.stream().allMatch(AlertmanagerAlert::isResolved);
        if (allResolved) {
            return AlertSeverity.RESOLVED;
        }
        AlertSeverity worst = AlertSeverity.INFO;
        for (AlertmanagerAlert alert : alerts) {
            if (alert.isResolved()) {
                continue;
            }
            AlertSeverity severity = AlertSeverity.fromLabel(alert.label(LABEL_SEVERITY));
            if (severity == AlertSeverity.CRITICAL) {
                return AlertSeverity.CRITICAL;
            }
            if (severity == AlertSeverity.WARNING) {
                worst = AlertSeverity.WARNING;
            }
        }
        return worst;
    }

    /**
     * @param cache per-payload memo of environmentId → product, so a group of 50 alerts
     *              from one environment costs one Mongo read rather than 50.
     */
    private Optional<ProductCode> resolveProduct(AlertmanagerAlert alert,
                                                 Map<String, String> commonLabels,
                                                 Map<String, ProductCode> cache) {
        Optional<ProductCode> fromLabel = ProductCodes.parse(alert.label(LABEL_PRODUCT))
                .or(() -> ProductCodes.parse(commonLabels.get(LABEL_PRODUCT)));
        if (fromLabel.isPresent()) {
            return fromLabel;
        }

        String environmentId = alert.label(LABEL_ENVIRONMENT);
        if (environmentId == null || environmentId.isBlank()) {
            environmentId = commonLabels.get(LABEL_ENVIRONMENT);
        }
        if (environmentId == null || environmentId.isBlank()) {
            return Optional.empty();
        }
        if (cache.containsKey(environmentId)) {
            return Optional.ofNullable(cache.get(environmentId));
        }

        ProductCode product = null;
        try {
            Environment environment = environmentService.findById(environmentId);
            product = environment == null ? null : environment.getProductCode();
        } catch (Exception e) {
            // A deleted or unknown environment must not fail the webhook — the alert
            // still has to reach someone, so it falls through to the ops room.
            log.debug("Could not resolve product for environment {}: {}", environmentId, e.getMessage());
        }
        cache.put(environmentId, product);
        return Optional.ofNullable(product);
    }

    private static String value(String candidate, String fallback) {
        return candidate == null || candidate.isBlank() ? fallback : candidate.strip();
    }
}
