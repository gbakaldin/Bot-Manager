package com.vingame.bot.infrastructure.gateway;

import com.vingame.bot.common.gateway.RequestTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link GatewayBudgetSettings} validation — the check that turns a policy that cannot work
 * into a startup failure instead of a Cloudflare block (GATEWAY_REQUEST_BUDGET AD-5).
 * <p>
 * Every case below is a real misconfiguration an operator can produce with one environment
 * variable, and each one is silent at runtime: a hard cap of 1,000 paces nothing because the
 * edge blocks at 1,000; ceilings out of order let DEFAULT consume the room ESSENTIAL was
 * counting on, so a group start starves behind user registration; an unbounded DEFAULT wait
 * parks a registration thread for the life of the process. The only acceptable moment to
 * discover any of them is context refresh.
 */
@DisplayName("GatewayBudgetSettings validation")
class GatewayBudgetSettingsTest {

    private static Map<RequestTier, Integer> ceilings(int def, int prio, int ess) {
        Map<RequestTier, Integer> map = new EnumMap<>(RequestTier.class);
        map.put(RequestTier.DEFAULT, def);
        map.put(RequestTier.PRIORITIZED, prio);
        map.put(RequestTier.ESSENTIAL, ess);
        return map;
    }

    private static Map<RequestTier, Duration> waits(Duration def, Duration prio, Duration ess) {
        Map<RequestTier, Duration> map = new EnumMap<>(RequestTier.class);
        map.put(RequestTier.DEFAULT, def);
        map.put(RequestTier.PRIORITIZED, prio);
        map.put(RequestTier.ESSENTIAL, ess);
        return map;
    }

    private static GatewayBudgetSettings build(int hardCap, Map<RequestTier, Integer> ceilings,
                                               Map<RequestTier, Duration> waits) {
        return new GatewayBudgetSettings(GatewayBudgetMode.OBSERVE, Duration.ofMinutes(5), hardCap,
                ceilings, waits, Duration.ofMinutes(15), true, Duration.ofMinutes(15));
    }

    private static final Map<RequestTier, Duration> SHIPPED_WAITS =
            waits(Duration.ofSeconds(30), Duration.ofMinutes(10), Duration.ZERO);

    @Test
    @DisplayName("the shipped defaults are valid and are the numbers the plan reasoned about")
    void theShippedDefaultsAreValid() {
        GatewayBudgetSettings settings = GatewayBudgetSettings.defaults();

        assertThat(settings.mode()).isEqualTo(GatewayBudgetMode.OBSERVE);
        assertThat(settings.window()).isEqualTo(Duration.ofMinutes(5));
        assertThat(settings.hardCap()).isEqualTo(900);
        assertThat(settings.ceiling(RequestTier.DEFAULT)).isEqualTo(500);
        assertThat(settings.ceiling(RequestTier.PRIORITIZED)).isEqualTo(750);
        assertThat(settings.ceiling(RequestTier.ESSENTIAL)).isEqualTo(900);
        assertThat(settings.countWsUpgrades())
                .as("true is the conservative default while Open Item 1 is open")
                .isTrue();
        // ESSENTIAL is the only tier allowed to wait forever, and it does.
        assertThat(settings.isUnboundedWait(RequestTier.ESSENTIAL)).isTrue();
        assertThat(settings.isUnboundedWait(RequestTier.PRIORITIZED)).isFalse();
        assertThat(settings.isUnboundedWait(RequestTier.DEFAULT)).isFalse();
    }

    @Test
    @DisplayName("the startup line renders the ceilings in a fixed, low-to-high order")
    void ceilingsRenderInAFixedOrder() {
        // The release verification greps this string. A map's iteration order is not a
        // rendering order, so the order is explicit in the code and pinned here.
        assertThat(GatewayBudgetSettings.defaults().describeCeilings())
                .isEqualTo("default=500 prioritized=750 essential=900");
    }

    @Test
    @DisplayName("ceilings must be monotonic — DEFAULT above PRIORITIZED is rejected")
    void nonMonotonicCeilingsAreRejected() {
        assertThatThrownBy(() -> build(900, ceilings(800, 750, 900), SHIPPED_WAITS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("monotonic")
                // The message names the pair that disagrees, from the perspective of the tier
                // whose ceiling is too LOW — that is the one an operator has to raise.
                .hasMessageContaining("prioritized=750 is below the tier under it (800)");
    }

    @Test
    @DisplayName("a ceiling above the hard cap is rejected")
    void aCeilingAboveTheHardCapIsRejected() {
        assertThatThrownBy(() -> build(900, ceilings(500, 750, 950), SHIPPED_WAITS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("essential.ceiling=950")
                .hasMessageContaining("hard-cap=900");
    }

    @Test
    @DisplayName("a zero or negative ceiling is rejected")
    void aNonPositiveCeilingIsRejected() {
        assertThatThrownBy(() -> build(900, ceilings(0, 750, 900), SHIPPED_WAITS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("default.ceiling must be positive");
    }

    @Test
    @DisplayName("hard-cap at Cloudflare's own limit is rejected — it would protect nothing")
    void hardCapAtTheCloudflareLimitIsRejected() {
        assertThatThrownBy(() -> build(1000, ceilings(500, 750, 1000), SHIPPED_WAITS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cloudflare limit of 1000");
    }

    @Test
    @DisplayName("hard-cap above 900 is legal — it warns rather than failing")
    void hardCapAboveTheRecommendedMaximumIsAllowed() {
        // The distinction matters: 901..999 is a deliberate (if thin) margin choice an
        // operator may need, whereas >= 1000 cannot pace anything at all. Rejecting the
        // former would make the knob useless; accepting the latter would make it a lie.
        assertThatCode(() -> build(950, ceilings(500, 750, 950), SHIPPED_WAITS))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a missing tier is rejected rather than defaulted")
    void anIncompleteCeilingMapIsRejected() {
        Map<RequestTier, Integer> incomplete = new EnumMap<>(RequestTier.class);
        incomplete.put(RequestTier.DEFAULT, 500);
        incomplete.put(RequestTier.PRIORITIZED, 750);

        assertThatThrownBy(() -> build(900, incomplete, SHIPPED_WAITS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("essential.ceiling is not configured");
    }

    @Test
    @DisplayName("only ESSENTIAL may wait forever")
    void anUnboundedWaitIsRejectedForEveryTierButEssential() {
        assertThatThrownBy(() -> build(900, ceilings(500, 750, 900),
                waits(Duration.ZERO, Duration.ofMinutes(10), Duration.ZERO)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("default.max-wait=0 means unbounded");
    }

    @Test
    @DisplayName("a negative wait or window is rejected")
    void negativeDurationsAreRejected() {
        assertThatThrownBy(() -> build(900, ceilings(500, 750, 900),
                waits(Duration.ofSeconds(-1), Duration.ofMinutes(10), Duration.ZERO)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("default.max-wait must not be negative");

        assertThatThrownBy(() -> new GatewayBudgetSettings(GatewayBudgetMode.OBSERVE,
                Duration.ZERO, 900, ceilings(500, 750, 900), SHIPPED_WAITS,
                Duration.ofMinutes(15), true, Duration.ofMinutes(15)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("window must be positive");
    }

    @Test
    @DisplayName("mode parsing is case-insensitive and refuses anything it does not understand")
    void modeParsing() {
        assertThat(GatewayBudgetMode.parse("observe")).isEqualTo(GatewayBudgetMode.OBSERVE);
        assertThat(GatewayBudgetMode.parse(" ENFORCE ")).isEqualTo(GatewayBudgetMode.ENFORCE);
        // Not defaulted: an operator who typos the value must find out at startup, not from a
        // block page. "believing you are protected when you are not" is the failure mode this
        // whole feature exists to remove.
        assertThatThrownBy(() -> GatewayBudgetMode.parse("enforced"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("observe|enforce");
        assertThatThrownBy(() -> GatewayBudgetMode.parse(null))
                .isInstanceOf(IllegalStateException.class);
    }
}
