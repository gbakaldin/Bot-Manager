package com.vingame.bot.common.exception;

/**
 * The one place that decides whether a {@link Throwable}'s message may be shown to a client
 * (GATEWAY_REQUEST_BUDGET R2).
 * <p>
 * {@link RestExceptionHandler} has had this policy since API_ERROR_FORWARDING, stated in its own
 * javadoc: <i>"{@code e.getMessage()} for an unhandled exception can carry Mongo hostnames,
 * Spring bean wiring failures, JDK {@code HttpClient} infra details, etc. These belong only in
 * the server log, never in the response. Typed exception arms … continue to forward their
 * messages verbatim — those messages are operator-safe by construction."</i> That policy used to
 * live only in the handler because an error could only reach a client <em>as</em> an HTTP status.
 * <p>
 * Asynchronous starts broke that assumption: a build that fails forty minutes after its
 * {@code 200} has no response left to fail, so the failure surfaces as
 * {@code BotGroupStatusDTO.lastError} on {@code GET /{id}/status} instead — an unauthenticated
 * endpoint, fed by the most exception-rich path in the application. Publishing
 * {@code Throwable.toString()} there would have been a second, contradictory policy on the same
 * repository's most sensitive question, so this class is the first one, extracted.
 * <p>
 * <b>The rule: our own hierarchy, whose messages we wrote.</b> {@link BotManagerException} carries
 * text this codebase authored — {@code BadRequestException}'s validation message,
 * {@code UpstreamGatewayException}'s forwarded gateway envelope, {@code GatewayBudgetException}'s
 * tier and retry-after. {@link IllegalArgumentException} is included for the same reason (the
 * handler forwards it at 400). Everything else is foreign: a driver, the JDK, Spring, or
 * ws-parser, whose login-failure text embeds a fragment of the upstream response body on a
 * library separately known to log agency-token material.
 * <p>
 * <b>The rule is ours, not one inherited from the handler</b>, and the distinction matters
 * because the handler's typed arms are <em>narrower</em> than {@code BotManagerException}
 * (review RR4). Two members are forwarded here that the HTTP layer does not expose the same way:
 * <ul>
 *   <li>{@link ResourceNotFoundException} gets a <b>bodyless 404</b>, so its message has never
 *       reached a client through HTTP at all. It is reachable on this path — a group deleted
 *       mid-build makes {@code startLocked}'s {@code findById} throw it — and
 *       {@code Bot group not found with id: …} is a strictly better {@code lastError} than
 *       {@code Internal server error (ResourceNotFoundException)}. Forwarded deliberately.</li>
 *   <li>{@link GatewayBudgetException} had <b>no arm at all</b> until GATEWAY_REQUEST_BUDGET
 *       Phase 3 gave it 429/503. Its messages are self-authored and name only a tier, a duration
 *       and an environment id, so forwarding them was right before the arm existed — but it was a
 *       decision made here, not a policy inherited from there.</li>
 * </ul>
 * Stating it as "whatever the handler forwards" is how the next widening gets justified by a
 * sentence that was never true.
 * <p>
 * <b>What is added over the handler, deliberately.</b> The sanitised form carries the exception's
 * {@code getSimpleName()}. The handler does not, because an HTTP client has a request URI and a
 * timestamp to correlate a server log against; the holder of a {@code lastError} has neither, and
 * "Internal server error" alone would leave an operator unable to tell an unreachable Mongo from
 * a misconfigured environment without shell access to the box. A bare class name carries none of
 * the three things the policy names — no hostname, no wiring detail, no infra internals.
 */
public final class ClientSafeMessage {

    /**
     * The fixed string for anything whose real message may not be shown. Shared with
     * {@link RestExceptionHandler} so the two cannot drift into saying different things about
     * the same failure.
     */
    public static final String INTERNAL_ERROR = "Internal server error — see server logs";

    private ClientSafeMessage() {
    }

    /**
     * A message for {@code t} that is safe to put in a response body.
     *
     * @return the exception's own message when it is one this codebase authored, otherwise
     *         {@link #INTERNAL_ERROR} qualified by the exception's simple class name. Never
     *         {@code null}.
     */
    public static String of(Throwable t) {
        if (t == null) {
            return INTERNAL_ERROR;
        }
        if (isOperatorSafeByConstruction(t) && t.getMessage() != null && !t.getMessage().isBlank()) {
            return t.getMessage();
        }
        return INTERNAL_ERROR + " (" + t.getClass().getSimpleName() + ")";
    }

    /**
     * Whether {@code t}'s message was written by this codebase rather than by a library, the JDK
     * or an upstream server.
     * <p>
     * {@link BotManagerException} is the whole of our own hierarchy: every one of its messages was
     * written in this repository, which is the property that matters, rather than the weaker claim
     * that {@link RestExceptionHandler} happens to forward each of them (it does not — see the
     * class javadoc for the two exceptions). {@link IllegalArgumentException} is included because
     * the handler forwards it at 400. Note what is
     * <em>not</em> here: {@link IllegalStateException}, which the handler sanitises explicitly
     * because its call sites in this codebase "carry internal class names that should not reach
     * the client" — a start failure of that type is therefore reported by class name only, and a
     * site that has something operator-safe to say says it through
     * {@code StartAttemptRegistry.recordFailure} instead of relying on its exception message.
     */
    private static boolean isOperatorSafeByConstruction(Throwable t) {
        return t instanceof BotManagerException || t instanceof IllegalArgumentException;
    }
}
