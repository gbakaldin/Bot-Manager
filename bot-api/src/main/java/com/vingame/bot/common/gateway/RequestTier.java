package com.vingame.bot.common.gateway;

/**
 * Priority tier of one outbound gateway request (GATEWAY_REQUEST_BUDGET AD-3).
 * <p>
 * Cloudflare rate-limits every gwms gateway at <b>1,000 requests / 5 minutes per source
 * IP</b>, and a breach refuses <em>every</em> request from this host for that gateway —
 * register, login, re-auth, verifytoken, deposit — for at least an hour. The budget that
 * enforces our own lower cap has to choose what to drop when the window fills, and this
 * enum is that choice, declared at the call site by <b>intent</b>.
 * <p>
 * <b>Declaration order is the priority order</b> and is load-bearing: {@code ordinal()} is
 * the comparison the admission pass uses (strict priority ESSENTIAL → PRIORITIZED →
 * DEFAULT, FIFO within a tier). There are deliberately no numeric constants anywhere in
 * the code — reorder the constants and you have changed the policy.
 * <p>
 * The assignment, verbatim from the plan's inventory table:
 * <ul>
 *   <li>{@link #ESSENTIAL} — {@code Bot.initialize}'s login and WebSocket upgrade, and the
 *       <b>first</b> balance read ({@code lastFetchedBalance < 0}). The first read is on the
 *       start path, not the first round: {@code BettingMiniGameBot.onStart} calls
 *       {@code onNewSession()} before it installs its scenario, so a bot whose first read
 *       fails never plays at all.</li>
 *   <li>{@link #PRIORITIZED} — re-authentication, the reconnect WebSocket upgrade, a
 *       deposit and the read that confirms it. A fleet that is already up and needs to
 *       stay up.</li>
 *   <li>{@link #DEFAULT} — user registration, display-name assignment, drift balance reads,
 *       and the periodic-logout {@code restart()} upgrade. Work whose deferral costs
 *       nothing that is not recoverable on the next round.</li>
 * </ul>
 * Probes (the anonymous environment WebSocket probe, and the circuit breaker's own
 * clearance probe) carry no tier at all: they call {@code GatewayBudget.count(reason)},
 * which stamps the window without ever asking for admission.
 */
public enum RequestTier {

    /**
     * A request without which a bot cannot come up at all. Highest ceiling, and its wait
     * is unbounded (but cancellable) — a group start that takes 35 minutes because of the
     * rule is the rule working, not a failure.
     */
    ESSENTIAL,

    /**
     * A request that keeps an already-running fleet running: re-auth, reconnect, deposit.
     * Bounded wait, and a budget failure here is never terminal for the bot — it counts as
     * one failed attempt of the existing backoff loop (AD-9).
     */
    PRIORITIZED,

    /**
     * Everything else. First to be squeezed out when the window fills, and the tier whose
     * deferral is designed to be invisible.
     */
    DEFAULT
}
