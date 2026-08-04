package com.vingame.bot.domain.metrics;

import com.vingame.bot.common.exception.BadRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Security coverage for the scope-id trust boundary (METRICS_API review fix):
 * {@link MetricScope#selector(String)} interpolates the path-variable id raw into
 * the PromQL label matcher, so it must reject anything outside the
 * {@code [A-Za-z0-9_-]+} allow-list with {@link BadRequestException} → 400. This
 * is the single chokepoint both summary and timeseries (both scopes) flow through.
 */
@DisplayName("MetricScope.selector — scope-id validation")
class MetricScopeTest {

    @ParameterizedTest
    @EnumSource(MetricScope.class)
    @DisplayName("a normal UUID-shaped id passes and builds the expected matcher")
    void validUuidPasses(MetricScope scope) {
        String id = "0c9a93cb-20d6-4f57-9dbc-5c315dcf52e2";
        assertThat(scope.selector(id)).isEqualTo(scope.selectorLabel() + "=\"" + id + "\"");
    }

    @ParameterizedTest
    @EnumSource(MetricScope.class)
    @DisplayName("a plain alphanumeric/underscore id passes")
    void validSlugPasses(MetricScope scope) {
        assertThat(scope.selector("game_g1")).isEqualTo(scope.selectorLabel() + "=\"game_g1\"");
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "x\"} or bot_winnings_total{foo=\"bar",  // quote-breakout PromQL injection
            "g1\"",                                    // bare trailing quote
            "g1\\",                                    // backslash escape attempt
            "g1{foo}",                                 // brace injection
            "g1 or up",                                // whitespace + operator
            "g1,g2",                                   // comma
            "",                                        // empty
    })
    @DisplayName("a PromQL-breakout / invalid id is rejected with 400 (GAME)")
    void promqlBreakoutRejectedGame(String malicious) {
        assertThatThrownBy(() -> MetricScope.GAME.selector(malicious))
                .isInstanceOf(BadRequestException.class);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"e1\"} or up{a=\"b", "e1\"", "e1 ", " e1", "e1\t"})
    @DisplayName("a PromQL-breakout / invalid id is rejected with 400 (ENVIRONMENT)")
    void promqlBreakoutRejectedEnv(String malicious) {
        assertThatThrownBy(() -> MetricScope.ENVIRONMENT.selector(malicious))
                .isInstanceOf(BadRequestException.class);
    }

    @ParameterizedTest
    @EnumSource(MetricScope.class)
    @DisplayName("a null id is rejected with 400")
    void nullRejected(MetricScope scope) {
        assertThatThrownBy(() -> scope.selector(null))
                .isInstanceOf(BadRequestException.class);
    }
}
