package com.vingame.bot.domain.metrics;

import com.vingame.bot.common.exception.BadRequestException;

import java.util.regex.Pattern;

/**
 * The selector scope a {@link MetricKey} query is templated against:
 * a single Game ({@code gameId}) or a single Environment ({@code environmentId}).
 */
public enum MetricScope {

    GAME("gameId"),
    ENVIRONMENT("environmentId");

    /**
     * Allow-list for scope ids interpolated into the PromQL label matcher. Game
     * and environment ids in this system are UUID / slug shaped, so a strict
     * {@code [A-Za-z0-9_-]+} class is sufficient and — crucially — excludes the
     * double-quote and backslash that would let a caller break out of the
     * {@code label="<id>"} matcher and inject arbitrary PromQL. This is the trust
     * boundary that closes the open {@code %s} slot in the otherwise-closed
     * {@link MetricKey} catalog; an invalid id is rejected with 400 before any
     * query string is built.
     * <p>
     * An optional leading {@code $} is permitted solely so the same {@code promql}
     * path can render the Grafana template-variable form ({@code $gameId} /
     * {@code $environmentId}) that the dashboards use and that
     * {@code MetricKeyDashboardParityTest} pins against. {@code $} carries no
     * breakout risk inside a quoted label value; the quote/backslash/brace
     * exclusion is what closes the injection vector.
     */
    private static final Pattern VALID_ID = Pattern.compile("\\$?[A-Za-z0-9_-]+");

    private final String selectorLabel;

    MetricScope(String selectorLabel) {
        this.selectorLabel = selectorLabel;
    }

    /** The Prometheus label used to select by id for this scope. */
    public String selectorLabel() {
        return selectorLabel;
    }

    /**
     * Build the selector predicate, e.g. {@code gameId="abc-123"}.
     * <p>
     * The {@code id} originates from a path variable and is interpolated raw into
     * the PromQL label matcher, so it is validated against {@link #VALID_ID}
     * first. Anything containing a quote, backslash, brace or whitespace (i.e. a
     * PromQL-breakout attempt) is rejected with {@link BadRequestException} → 400.
     * Both summary and timeseries, both scopes, route through here.
     */
    public String selector(String id) {
        if (id == null || !VALID_ID.matcher(id).matches()) {
            throw new BadRequestException(
                    "Invalid " + selectorLabel + " — must match [A-Za-z0-9_-]+.");
        }
        return selectorLabel + "=\"" + id + "\"";
    }
}
