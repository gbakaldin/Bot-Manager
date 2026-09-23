package com.vingame.bot.common.gateway;

import java.util.function.BooleanSupplier;

/**
 * Who a queued gateway request belongs to, and how to find out that it no longer wants to
 * be sent (GATEWAY_REQUEST_BUDGET AD-8).
 * <p>
 * <b>Cancellation is a predicate, never a thread interrupt</b>, and that is not a
 * preference — an interrupt-based design would be a design that does not work here:
 * <ul>
 *   <li>{@code CompletableFuture.join()} is not interruptible, and
 *       {@code BotGroupBehaviorService.createBotsInParallel} joins its per-bot futures, so
 *       interrupting the start thread would not abort a paced start;</li>
 *   <li>{@code VingameWebSocketClient.connect()} <em>swallows</em>
 *       {@code InterruptedException} (logs it, restores the flag, returns a half-built
 *       client), so a WebSocket upgrade must be abandoned <b>before</b> {@code connect()}
 *       is entered.</li>
 * </ul>
 * So the budget asks: a waiter whose {@link #cancelled} supplier answers {@code true} is
 * completed with {@code GatewayRequestCancelledException} and is <b>never stamped into the
 * window</b> — it never left the JVM, so it never cost the edge a request.
 * <p>
 * {@code botGroupId} is the cancellation key: {@code GatewayBudget.cancelScope(groupId)}
 * wakes every waiter carrying it. A scope with a {@code null} group id (registration,
 * ad-hoc tooling) is therefore not cancellable by group, which is correct — nothing owns it
 * to cancel.
 *
 * @param botGroupId the group this request is being made on behalf of, or {@code null}
 *                   when the request has no group (registration runs before the group's
 *                   bots exist)
 * @param botId      the bot's identity for the DEBUG line — the username, which is what an
 *                   operator greps; {@code null} for non-bot requests
 * @param cancelled  asked (cheaply, possibly repeatedly, possibly from another thread)
 *                   whether this request should still be sent. Never {@code null}; use
 *                   {@link #NEVER_CANCELLED} for a request nobody can call off.
 */
public record GatewayRequestScope(String botGroupId, String botId, BooleanSupplier cancelled) {

    /** A request nobody can call off — the honest answer for probes and ad-hoc tooling. */
    public static final BooleanSupplier NEVER_CANCELLED = () -> false;

    public GatewayRequestScope {
        if (cancelled == null) {
            cancelled = NEVER_CANCELLED;
        }
    }

    /**
     * A bot's own scope. {@code cancelled} is normally
     * {@code () -> bot.isStopped() || startCancelled(groupId)} — the second half arrives
     * with the async start in Phase 2.
     */
    public static GatewayRequestScope forBot(String botGroupId, String botId, BooleanSupplier cancelled) {
        return new GatewayRequestScope(botGroupId, botId, cancelled);
    }

    /**
     * The scope of a user-registration request (register / update-fullname). It carries no
     * {@code botGroupId} because the group's bots do not exist yet — registration runs
     * inside {@code BotGroupService.save}, before any {@code Bot} is built — and so it is
     * not cancellable by group. {@code name} is the registration identity available at the
     * call site: the username prefix at {@code register.aspx}, the full username at
     * {@code update-fullname.aspx}.
     */
    public static GatewayRequestScope registration(String name) {
        return new GatewayRequestScope(null, name, NEVER_CANCELLED);
    }

    /**
     * A scope for infrastructure that belongs to no group and no bot — a probe, a
     * scheduler, a test fixture.
     */
    public static GatewayRequestScope internal(String reason) {
        return new GatewayRequestScope(null, reason, NEVER_CANCELLED);
    }

    /** Whether this request has been called off since it was queued. */
    public boolean isCancelled() {
        return cancelled.getAsBoolean();
    }

    /** Short {@code group/bot} rendering for a DEBUG line; never null, never throws. */
    public String describe() {
        return (botGroupId == null ? "-" : botGroupId) + "/" + (botId == null ? "-" : botId);
    }
}
