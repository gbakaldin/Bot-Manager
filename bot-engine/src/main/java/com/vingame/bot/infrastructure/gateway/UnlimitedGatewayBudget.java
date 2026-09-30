package com.vingame.bot.infrastructure.gateway;

import com.vingame.bot.common.gateway.GatewayRequestScope;
import com.vingame.bot.common.gateway.RequestTier;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * The {@link GatewayBudget#UNLIMITED} pass-through: runs every call immediately, counts
 * nothing, publishes nothing.
 * <p>
 * This is the fixture budget, and the default a {@code Bot} or {@code ApiGatewayClient}
 * built outside Spring carries. It deliberately has <b>no</b> environment identity and
 * therefore no meters: a unit-test fixture must not be able to register a
 * {@code gateway_budget_*} series, and it must not be able to look like a real budget in a
 * snapshot either — {@link #snapshot()} reports a hard cap of {@code 0}, which is not a
 * value any configured budget can hold.
 */
final class UnlimitedGatewayBudget implements GatewayBudget {

    @Override
    public <T> T execute(RequestTier tier, GatewayRequestScope scope, Callable<T> call) throws Exception {
        return call.call();
    }

    @Override
    public <T> T execute(RequestTier tier, GatewayRequestScope scope, Callable<T> call,
                         Duration maxWait) throws Exception {
        return call.call();
    }

    @Override
    public void run(RequestTier tier, GatewayRequestScope scope, Runnable call) {
        call.run();
    }

    @Override
    public void runWsUpgrade(RequestTier tier, GatewayRequestScope scope, Runnable upgrade) {
        upgrade.run();
    }

    @Override
    public <T> Optional<T> tryExecute(RequestTier tier, GatewayRequestScope scope,
                                      Callable<T> call, Duration maxWait) throws Exception {
        return Optional.ofNullable(call.call());
    }

    @Override
    public void count(String reason) {
        // Nothing to count against.
    }

    @Override
    public void countWsUpgrade(String reason) {
        // Nothing to count against.
    }

    @Override
    public Reservation reserve(RequestTier tier, int permits, GatewayRequestScope scope) {
        return NO_RESERVATION;
    }

    @Override
    public void cancelScope(String botGroupId) {
        // Nothing is ever queued here.
    }

    @Override
    public Snapshot snapshot() {
        return new Snapshot(null, null, null, GatewayBudgetMode.OBSERVE, 0, 0, 0, 0, 0, false);
    }

    @Override
    public boolean countsWsUpgrades() {
        return false;
    }

    @Override
    public Duration maxWait(RequestTier tier) {
        // Nothing ever waits here, so every tier is effectively "no wait at all"; null would
        // claim "unbounded", which is the opposite.
        return Duration.ZERO;
    }

    @Override
    public Duration registrationMaxWait() {
        // Nothing ever waits here, so the value is only ever passed back into a no-op.
        return Duration.ZERO;
    }

    @Override
    public Duration observeModePacing() {
        // No policy, so no derived pacing. This is the fixture budget: a test that paced itself
        // at 600 ms per account would take five minutes to register five hundred of them, and
        // there is no window here for the pacing to protect.
        return Duration.ZERO;
    }

    private static final Reservation NO_RESERVATION = new Reservation() {
        @Override
        public void release() {
            // no-op
        }

        @Override
        public int remaining() {
            return 0;
        }
    };
}
