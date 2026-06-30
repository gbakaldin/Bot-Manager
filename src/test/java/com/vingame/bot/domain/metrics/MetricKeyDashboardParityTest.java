package com.vingame.bot.domain.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the PromQL catalog to the Grafana dashboards (METRICS_API AD-2 / Phase 3
 * verification): each {@link MetricKey}'s built PromQL must string-equal the
 * {@code expr} that the corresponding dashboard panel uses. This is the
 * regression guard that the API and the dashboards can never drift — if a
 * dashboard query changes, this test fails until {@link MetricKey} moves with it.
 * <p>
 * The dashboards template the selector as {@code $gameId} / {@code $environmentId};
 * substituting those literal ids here makes the built PromQL string-equal the
 * dashboard {@code expr} byte-for-byte.
 */
class MetricKeyDashboardParityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path PER_GAME =
            Path.of("grafana/provisioning/dashboards/per-game.json");
    private static final Path PER_ENV =
            Path.of("grafana/provisioning/dashboards/per-environment.json");

    @Test
    void everyGameKeyMatchesPerGameDashboard() throws IOException {
        Set<String> exprs = collectExprs(PER_GAME);
        for (MetricKey key : MetricKey.values()) {
            if (!key.supports(MetricScope.GAME)) {
                continue;
            }
            String built = key.promql(MetricScope.GAME, "$gameId");
            assertThat(exprs)
                    .as("MetricKey %s GAME PromQL must exist verbatim in per-game.json", key)
                    .contains(built);
        }
    }

    @Test
    void everyEnvironmentKeyMatchesPerEnvironmentDashboard() throws IOException {
        Set<String> exprs = collectExprs(PER_ENV);
        for (MetricKey key : MetricKey.values()) {
            if (!key.supports(MetricScope.ENVIRONMENT)) {
                continue;
            }
            String built = key.promql(MetricScope.ENVIRONMENT, "$environmentId");
            assertThat(exprs)
                    .as("MetricKey %s ENVIRONMENT PromQL must exist verbatim in per-environment.json", key)
                    .contains(built);
        }
    }

    @Test
    void gameOnlyKeysAreNotAvailableForEnvironment() {
        assertThat(MetricKey.JACKPOTS_RATE_5M.supports(MetricScope.ENVIRONMENT)).isFalse();
        assertThat(MetricKey.JACKPOT_AMOUNT_RATE_5M.supports(MetricScope.ENVIRONMENT)).isFalse();
        assertThat(MetricKey.JACKPOTS_RATE_5M.supports(MetricScope.GAME)).isTrue();
    }

    @Test
    void environmentOnlyKeysAreNotAvailableForGame() {
        assertThat(MetricKey.RTP_PER_GAME.supports(MetricScope.GAME)).isFalse();
        assertThat(MetricKey.RECONNECT_RATE_5M.supports(MetricScope.GAME)).isFalse();
        assertThat(MetricKey.RTP_PER_GAME.supports(MetricScope.ENVIRONMENT)).isTrue();
    }

    /** Collect every panel {@code expr} string from a dashboard JSON. */
    private Set<String> collectExprs(Path dashboard) throws IOException {
        JsonNode root = MAPPER.readTree(Files.readString(dashboard));
        Set<String> exprs = new HashSet<>();
        collect(root, exprs);
        return exprs;
    }

    private void collect(JsonNode node, Set<String> exprs) {
        if (node.isObject()) {
            JsonNode expr = node.get("expr");
            if (expr != null && expr.isTextual()) {
                exprs.add(expr.asText());
            }
            node.fields().forEachRemaining(e -> collect(e.getValue(), exprs));
        } else if (node.isArray()) {
            node.forEach(child -> collect(child, exprs));
        }
    }
}
