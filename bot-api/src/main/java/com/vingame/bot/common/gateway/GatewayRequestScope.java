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
 * wakes every waiter carrying it. A scope with a {@code null} group id (ad-hoc tooling, a
 * probe) is therefore not cancellable by group, which is correct — nothing owns it to cancel.
 * <b>Registration is no longer in that list</b>: since Phase 4 the group document exists before
 * its accounts do, so a registration request names its group and a {@code DELETE} can call it
 * off (A28.6).
 *
 * @param botGroupId the group this request is being made on behalf of, or {@code null}
 *                   when the request genuinely has no owner — a probe, ad-hoc tooling
 * @param botId      the bot's identity for the DEBUG line — the username, which is what an
 *                   operator greps; {@code null} for non-bot requests
 * @param cancelled  asked (cheaply, possibly repeatedly, possibly from another thread)
 *                   whether this request should still be sent. Never {@code null}; use
 *                   {@link #NEVER_CANCELLED} for a request nobody can call off.
 *                   <p>
 *                   <b>It is asked while the budget's own lock is held</b> (review F4), which is
 *                   the constraint that matters and was not written down. It must therefore not
 *                   block, must not log, and must not take another lock: a predicate that does any
 *                   of those is a stall — or a lock-ordering deadlock — on <em>every</em> gateway
 *                   request of that environment, not just on its own. The two live
 *                   implementations ({@code Bot.requestCancelled} and a start attempt's cancel
 *                   flag) are plain volatile reads.
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
     * The scope of a user-registration request (register / update-fullname) made on behalf of a
     * bot group — GATEWAY_REQUEST_BUDGET A28.6, and the shape every registration request uses
     * since Phase 4.
     * <p>
     * <b>The group id is the whole point.</b> {@code GatewayBudget.cancelScope(botGroupId)} keys
     * on it, so without it a {@code DELETE} arriving while a 500-account create is queued cannot
     * reach the queued request at all: the delete would park behind up to fifteen minutes of
     * registration wait for an account nobody wants any more. The same key is what lets a
     * reservation be consumed by the group that declared it.
     * <p>
     * Registration <em>used</em> to have no group to name — it ran synchronously inside
     * {@code BotGroupService.save}, before the group document was persisted. Asynchronous
     * registration reverses that: the group exists first and the accounts are created for it
     * afterwards, so the group is available at every registration call site and there is no
     * longer an honest reason to claim otherwise.
     *
     * @param botGroupId the group whose accounts are being created
     * @param name       the registration identity for the DEBUG line: the username prefix at
     *                   {@code register.aspx}, the full username at {@code update-fullname.aspx}
     * @param cancelled  asked while the budget's lock is held — see {@link #cancelled}. The
     *                   worker passes a plain volatile/set read
     */
    public static GatewayRequestScope registration(String botGroupId, String name,
                                                   BooleanSupplier cancelled) {
        return new GatewayRequestScope(botGroupId, name, cancelled);
    }

    /**
     * A registration scope with no group and no way to call it off.
     * <p>
     * <b>Nothing in production uses this since Phase 4</b> — {@code RegistrationWorker} always
     * has a group — and that is the point of keeping it separate rather than defaulting the
     * group id to {@code null} in the method above: a call site with no group is now a deliberate
     * statement (ad-hoc tooling, a fixture), not an oversight that silently costs a {@code DELETE}
     * its promptness.
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
