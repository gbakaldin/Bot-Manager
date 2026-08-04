package com.vingame.bot.domain.metrics;

import java.util.EnumMap;
import java.util.Map;

/**
 * Closed catalog of metric keys the public metrics API exposes, with the PromQL
 * lifted <b>verbatim</b> from the Grafana dashboards
 * ({@code grafana/provisioning/dashboards/per-game.json} and
 * {@code per-environment.json}). bot-manager owns all PromQL; the UI only ever
 * sends a key + scope id + time window (METRICS_API AD-2). There is no
 * free-text query path — this enum is both the single source of truth (so the
 * API and the dashboards cannot drift) and the security boundary.
 * <p>
 * <b>Drift guard:</b> {@code MetricKeyDashboardParityTest} asserts each key's
 * built PromQL string-equals the dashboard {@code expr}. If a dashboard query
 * changes, the template here must move with it.
 * <p>
 * Each key holds one PromQL template per applicable {@link MetricScope}. The
 * template carries a single {@code %s} slot for the resolved selector predicate
 * (e.g. {@code gameId="abc"} / {@code environmentId="abc"}). The metric name and
 * selector both vary by scope for the status-derived keys
 * ({@code bots_by_game_status} vs {@code bots_by_env_status}), so the catalog is
 * keyed by scope rather than by selector substitution alone.
 * <p>
 * Most rate/increase windows ({@code [5m]}/{@code [1m]}/{@code [1h]}) are
 * intrinsic to each metric's definition and are <b>not</b> the user's chart
 * range — the user's {@code from}/{@code to}/{@code step} only parameterize
 * {@code query_range}. The sole exception is RTP, whose template stores the
 * literal {@code $__range} Grafana token; {@code MetricsQueryService} substitutes
 * a concrete window per endpoint before dispatch (AD-6) so the token never
 * reaches Prometheus.
 */
public enum MetricKey {

    // ---- Cross-scope (GAME + ENVIRONMENT) ----

    /** Distinct bot groups active for the scope. per-game.json:132 / per-environment.json:132. */
    BOT_GROUPS("bot_groups", false, Map.of(
            MetricScope.GAME, "count(count by (botGroupId) (bot_messages_total{%s}))",
            MetricScope.ENVIRONMENT, "count(count by (botGroupId) (bot_messages_total{%s}))")),

    /** Total bots for the scope. per-game.json:191 / per-environment.json:191. */
    TOTAL_BOTS("total_bots", false, Map.of(
            MetricScope.GAME, "sum(bots_by_game_status{%s})",
            MetricScope.ENVIRONMENT, "sum(bots_by_env_status{%s})")),

    /** Bot counts per status (multi-series, one per {@code status}). per-game.json:385 / per-environment.json:385. */
    BOTS_BY_STATUS("bots_by_status", true, Map.of(
            MetricScope.GAME, "bots_by_game_status{%s}",
            MetricScope.ENVIRONMENT, "bots_by_env_status{%s}")),

    /**
     * Stake-weighted RTP (winnings/bet-amount) as a ratio-of-sums over the query
     * window, with the mandatory {@code or vector(0)} guard. The {@code $__range}
     * Grafana token is stored verbatim so the dashboard expr matches byte-for-byte;
     * {@code MetricsQueryService} substitutes a concrete window per endpoint before
     * dispatch (summary {@code metrics.rtp.summary-window}, timeseries
     * {@code metrics.rtp.timeseries-window} — AD-6). per-game.json:259 /
     * per-environment.json:259.
     */
    RTP("rtp", false, Map.of(
            MetricScope.GAME, "(sum(increase(bot_winnings_total{%s}[$__range])) / sum(increase(bot_bet_amount_total{%s}[$__range]))) or vector(0)",
            MetricScope.ENVIRONMENT, "(sum(increase(bot_winnings_total{%s}[$__range])) / sum(increase(bot_bet_amount_total{%s}[$__range]))) or vector(0)")),

    /**
     * Per-bot average money drain over the last 24h: total real-balance depletion
     * for the scope divided by the current bot count. The divisor metric varies by
     * scope ({@code bots_by_game_status} vs {@code bots_by_env_status}), mirroring
     * {@link #TOTAL_BOTS}. The {@code [24h]} window is fixed (it IS "per day", not
     * the user's chart range — AD-4). Drain is floored at 0 per fetch, so net-gain
     * windows contribute 0 and the value is biased UPWARD vs true net depletion
     * (a burn gauge, not net P&L — AD-2). {@code or vector(0)} guards the
     * no-bots / no-series case. per-game.json:775 / per-environment.json:776.
     */
    MONEY_DRAIN_PER_DAY("money_drain_per_day", false, Map.of(
            MetricScope.GAME, "(sum(increase(bot_money_drained_total{%s}[24h])) / sum(bots_by_game_status{%s})) or vector(0)",
            MetricScope.ENVIRONMENT, "(sum(increase(bot_money_drained_total{%s}[24h])) / sum(bots_by_env_status{%s})) or vector(0)")),

    /** Dead time over the last hour (seconds). per-game.json:326 / per-environment.json:326. */
    DEAD_SECONDS_1H("dead_seconds_1h", false, Map.of(
            MetricScope.GAME, "sum(increase(bot_dead_seconds_total{%s}[1h]))",
            MetricScope.ENVIRONMENT, "sum(increase(bot_dead_seconds_total{%s}[1h]))")),

    /** Bets placed rate (1m). per-game.json:440 / per-environment.json:496. */
    BETS_PLACED_RATE_1M("bets_placed_rate_1m", false, Map.of(
            MetricScope.GAME, "sum(rate(bot_bets_placed_total{%s}[1m]))",
            MetricScope.ENVIRONMENT, "sum(rate(bot_bets_placed_total{%s}[1m]))")),

    /** Bet amount rate (1m). per-game.json:495 / per-environment.json:551. */
    BET_AMOUNT_RATE_1M("bet_amount_rate_1m", false, Map.of(
            MetricScope.GAME, "sum(rate(bot_bet_amount_total{%s}[1m]))",
            MetricScope.ENVIRONMENT, "sum(rate(bot_bet_amount_total{%s}[1m]))")),

    /** Winnings rate (5m). per-game.json:550 / per-environment.json:606. */
    WINNINGS_RATE_5M("winnings_rate_5m", false, Map.of(
            MetricScope.GAME, "sum(rate(bot_winnings_total{%s}[5m]))",
            MetricScope.ENVIRONMENT, "sum(rate(bot_winnings_total{%s}[5m]))")),

    /** Failures rate (5m). per-game.json:715 / per-environment.json:716. */
    FAILURES_RATE_5M("failures_rate_5m", false, Map.of(
            MetricScope.GAME, "sum(rate(bot_failures_total{%s}[5m]))",
            MetricScope.ENVIRONMENT, "sum(rate(bot_failures_total{%s}[5m]))")),

    // ---- GAME-only ----

    /** Jackpots rate (5m). per-game.json:605 — per-game only. */
    JACKPOTS_RATE_5M("jackpots_rate_5m", false, Map.of(
            MetricScope.GAME, "sum(rate(bot_jackpots_total{%s}[5m]))")),

    /** Jackpot amount rate (5m). per-game.json:660 — per-game only. */
    JACKPOT_AMOUNT_RATE_5M("jackpot_amount_rate_5m", false, Map.of(
            MetricScope.GAME, "sum(rate(bot_jackpot_amount_total{%s}[5m]))")),

    // ---- ENVIRONMENT-only ----

    /**
     * Per-game stake-weighted RTP within the environment (multi-series by
     * {@code gameName}), as a ratio-of-sums over the query window. The
     * {@code $__range} token is stored verbatim and resolved at dispatch by
     * {@code MetricsQueryService} (AD-6). per-environment.json:441 — env only.
     */
    RTP_PER_GAME("rtp_per_game", true, Map.of(
            MetricScope.ENVIRONMENT, "(sum by (gameName) (increase(bot_winnings_total{%s}[$__range])) / sum by (gameName) (increase(bot_bet_amount_total{%s}[$__range]))) or vector(0)")),

    /** Reconnect rate (5m, multi-series by {@code reason}). per-environment.json:661 — env only. */
    RECONNECT_RATE_5M("reconnect_rate_5m", true, Map.of(
            MetricScope.ENVIRONMENT, "sum by (reason) (rate(bot_reconnects_total{%s}[5m]))"));

    private final String key;
    private final boolean multiSeries;
    private final Map<MetricScope, String> templates;

    MetricKey(String key, boolean multiSeries, Map<MetricScope, String> templates) {
        this.key = key;
        this.multiSeries = multiSeries;
        this.templates = new EnumMap<>(templates);
    }

    /** The wire/API key (e.g. {@code total_bots}) the UI sends. */
    public String key() {
        return key;
    }

    /** True when the query yields multiple series (per status / gameName / reason). */
    public boolean isMultiSeries() {
        return multiSeries;
    }

    /** True when this key is valid for the given scope. */
    public boolean supports(MetricScope scope) {
        return templates.containsKey(scope);
    }

    /**
     * Build the concrete PromQL for the given scope and id by substituting the
     * resolved selector predicate into every {@code %s} slot.
     *
     * @throws IllegalArgumentException if the key does not support the scope
     *         (cross-scope misuse — the controller maps this to HTTP 400).
     */
    public String promql(MetricScope scope, String id) {
        String template = templates.get(scope);
        if (template == null) {
            throw new IllegalArgumentException(
                    "Metric '" + key + "' is not available for scope " + scope);
        }
        String selector = scope.selector(id);
        // Templates carry 1 or 2 %s slots (RTP repeats the selector); fill both.
        long slots = template.chars().filter(c -> c == '%').count();
        Object[] args = new Object[(int) slots];
        java.util.Arrays.fill(args, selector);
        return String.format(template, args);
    }

    /** Look up a key by its wire string, or {@code null} if unknown. */
    public static MetricKey fromKey(String key) {
        for (MetricKey mk : values()) {
            if (mk.key.equals(key)) {
                return mk;
            }
        }
        return null;
    }
}
