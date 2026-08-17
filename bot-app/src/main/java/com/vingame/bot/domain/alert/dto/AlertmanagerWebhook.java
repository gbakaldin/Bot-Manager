package com.vingame.bot.domain.alert.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * Alertmanager webhook payload (schema {@code version: "4"}).
 * <p>
 * Only the fields bot-manager routes or renders on are modelled; everything else
 * ({@code groupKey}, {@code truncatedAlerts}, {@code receiver}, …) is ignored so a
 * future Alertmanager version cannot break the endpoint with an added field.
 *
 * @param version          payload schema version, currently {@code "4"}.
 * @param status           {@code firing} or {@code resolved} for the group as a whole.
 * @param groupLabels      labels the alerts were grouped by (per {@code group_by}).
 * @param commonLabels     labels shared by every alert in the payload — where a
 *                         {@code product} label usually lands when the whole group
 *                         belongs to one product.
 * @param commonAnnotations annotations shared by every alert in the payload.
 * @param externalURL      base URL of the Alertmanager that sent this.
 * @param alerts           the individual alerts; never empty in practice.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AlertmanagerWebhook(
        String version,
        String status,
        Map<String, String> groupLabels,
        Map<String, String> commonLabels,
        Map<String, String> commonAnnotations,
        String externalURL,
        List<AlertmanagerAlert> alerts) {

    /**
     * A single alert instance inside the payload.
     *
     * @param status      {@code firing} or {@code resolved} for this alert specifically.
     * @param labels      alert labels — carries {@code alertname}, {@code severity} and
     *                    whatever routing labels the rule sets ({@code product},
     *                    {@code environmentId}).
     * @param annotations alert annotations — {@code summary} / {@code description}.
     * @param startsAt    RFC3339 start time.
     * @param endsAt      RFC3339 end time (zero-value while firing).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AlertmanagerAlert(
            String status,
            Map<String, String> labels,
            Map<String, String> annotations,
            String startsAt,
            String endsAt) {

        /** Null-safe label lookup. */
        public String label(String key) {
            return labels == null ? null : labels.get(key);
        }

        /** Null-safe annotation lookup. */
        public String annotation(String key) {
            return annotations == null ? null : annotations.get(key);
        }

        public boolean isResolved() {
            return "resolved".equalsIgnoreCase(status);
        }
    }
}
