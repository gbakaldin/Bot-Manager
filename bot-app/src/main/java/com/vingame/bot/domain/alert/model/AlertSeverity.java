package com.vingame.bot.domain.alert.model;

/**
 * Severity of an outbound alert, used only for presentation — it selects the marker
 * and label in the message header so a room reader can triage at a glance.
 * <p>
 * Deliberately mirrors the Prometheus {@code severity} label vocabulary
 * ({@code info} / {@code warning} / {@code critical}) so Alertmanager payloads map
 * across without a translation table, plus {@link #RESOLVED} for the
 * {@code send_resolved} half of the webhook contract.
 */
public enum AlertSeverity {

    INFO("ℹ️", "INFO"),
    WARNING("⚠️", "WARNING"),
    CRITICAL("🔴", "CRITICAL"),
    RESOLVED("✅", "RESOLVED");

    private final String marker;
    private final String label;

    AlertSeverity(String marker, String label) {
        this.marker = marker;
        this.label = label;
    }

    public String getMarker() {
        return marker;
    }

    public String getLabel() {
        return label;
    }

    /**
     * Maps a Prometheus {@code severity} label onto this enum.
     *
     * @param value the raw label value; may be {@code null} or unknown.
     * @return the matching severity, or {@link #WARNING} as the fallback — an
     *         unrecognised severity is still something a human should look at,
     *         so it must not silently degrade to INFO.
     */
    public static AlertSeverity fromLabel(String value) {
        if (value == null) {
            return WARNING;
        }
        return switch (value.strip().toLowerCase()) {
            case "info", "informational", "none" -> INFO;
            case "critical", "fatal", "page", "error" -> CRITICAL;
            case "warning", "warn" -> WARNING;
            default -> WARNING;
        };
    }
}
