package com.vingame.bot.domain.metrics;

/**
 * The selector scope a {@link MetricKey} query is templated against:
 * a single Game ({@code gameId}) or a single Environment ({@code environmentId}).
 */
public enum MetricScope {

    GAME("gameId"),
    ENVIRONMENT("environmentId");

    private final String selectorLabel;

    MetricScope(String selectorLabel) {
        this.selectorLabel = selectorLabel;
    }

    /** The Prometheus label used to select by id for this scope. */
    public String selectorLabel() {
        return selectorLabel;
    }

    /** Build the selector predicate, e.g. {@code gameId="abc-123"}. */
    public String selector(String id) {
        return selectorLabel + "=\"" + id + "\"";
    }
}
