package com.vingame.bot.infrastructure.gateway;

/**
 * Whether the gateway request budget only watches, or actually paces
 * (GATEWAY_REQUEST_BUDGET AD-23).
 * <p>
 * The same switch shape as {@code bot.recovery.enabled}: the compiled default is the safe
 * one, the real posture is an environment variable
 * ({@code BOT_GATEWAY_BUDGET_MODE=${GATEWAY_BUDGET_MODE:-observe}} in compose, set per box
 * in the uncommitted {@code secrets.env}/{@code .env} merge), and the compiled default is
 * flipped only after a staging soak has read the numbers {@link #OBSERVE} produces.
 */
public enum GatewayBudgetMode {

    /**
     * Count, tag, log and publish everything; <b>never wait, reject or open a circuit</b>.
     * This is what Phase 1 ships, and it is what makes the feature safe to deploy to prod
     * unchanged: the only behaviour change is that the window is now measured.
     */
    OBSERVE,

    /**
     * Pace: per-tier ceilings, queueing, cancellable waits, the circuit breaker. Arrives
     * with Phase 3 — until then a budget constructed in this mode still behaves exactly
     * like {@link #OBSERVE} and says so once at startup, because an operator who sets the
     * variable expecting protection must not silently get none.
     */
    ENFORCE;

    /**
     * Parse a configured value, case- and whitespace-insensitively. An unrecognised value
     * is a configuration error and throws — defaulting it would be the one failure mode
     * this whole feature exists to prevent (believing you are protected when you are not).
     */
    public static GatewayBudgetMode parse(String raw) {
        String value = raw == null ? "" : raw.strip();
        for (GatewayBudgetMode mode : values()) {
            if (mode.name().equalsIgnoreCase(value)) {
                return mode;
            }
        }
        throw new IllegalStateException("bot.gateway.budget.mode must be one of "
                + "observe|enforce, but was '" + raw + "'");
    }
}
