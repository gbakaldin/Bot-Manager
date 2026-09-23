package com.vingame.bot.common.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RequestTier}'s <b>declaration order is the policy</b> (GATEWAY_REQUEST_BUDGET AD-3):
 * the admission pass walks the tiers by {@code ordinal()}, highest priority first, and there
 * are deliberately no numeric priority constants anywhere for it to disagree with.
 * <p>
 * So an innocuous-looking reorder — alphabetising the constants, inserting a new tier in the
 * middle — silently inverts which requests get the window when it fills. Nothing else in the
 * build would notice: every admission test would still pass, because they would all be
 * measuring the new order. This test is the one place the intended order is written down as
 * an assertion.
 */
@DisplayName("RequestTier — declaration order is the priority order")
class RequestTierTest {

    @Test
    @DisplayName("ESSENTIAL, then PRIORITIZED, then DEFAULT")
    void declarationOrderIsThePriorityOrder() {
        assertThat(RequestTier.values())
                .as("ordinal() IS the comparison the admission pass makes; reordering these "
                        + "constants reorders the policy")
                .containsExactly(RequestTier.ESSENTIAL, RequestTier.PRIORITIZED, RequestTier.DEFAULT);
    }

    @Test
    @DisplayName("higher priority compares lower, so a natural sort is a priority sort")
    void ordinalsAreStrictlyIncreasingWithDecreasingPriority() {
        assertThat(RequestTier.ESSENTIAL.ordinal()).isLessThan(RequestTier.PRIORITIZED.ordinal());
        assertThat(RequestTier.PRIORITIZED.ordinal()).isLessThan(RequestTier.DEFAULT.ordinal());
    }

    @Test
    @DisplayName("there are exactly three tiers")
    void thereAreExactlyThreeTiers() {
        // The per-tier config keys, the three ceilings, the three max-waits, the rollup line's
        // `queued=E/P/D` fragment and the queue/reserved gauges are all written for three. A
        // fourth tier is a change to all of them, not a one-line enum edit.
        assertThat(RequestTier.values()).hasSize(3);
    }

    @Test
    @DisplayName("a scope with no cancel predicate is never cancelled, not a NullPointerException")
    void scopeToleratesANullCancelPredicate() {
        // The budget asks isCancelled() on every waiter on every admission pass. A null
        // predicate there would throw on the hot path, inside a lock, on a bot thread.
        GatewayRequestScope scope = new GatewayRequestScope("group-1", "bot1", null);

        assertThat(scope.isCancelled()).isFalse();
        assertThat(scope.describe()).isEqualTo("group-1/bot1");
    }

    @Test
    @DisplayName("a registration scope carries no group, so it is not cancellable by group")
    void registrationScopeHasNoGroup() {
        // Registration runs inside BotGroupService.save, before any bot of the group exists —
        // there is no botGroupId to cancel it by, and pretending otherwise would make
        // cancelScope() look like it covered registration when it cannot.
        GatewayRequestScope scope = GatewayRequestScope.registration("authtestws");

        assertThat(scope.botGroupId()).isNull();
        assertThat(scope.botId()).isEqualTo("authtestws");
        assertThat(scope.isCancelled()).isFalse();
        assertThat(scope.describe()).isEqualTo("-/authtestws");
    }
}
