package com.vingame.bot.domain.botgroup.model;

/**
 * The two values {@code BotGroup.registrationState} may hold, plus the absent third
 * (GATEWAY_REQUEST_BUDGET A1 / A2).
 *
 * <p><b>Constants on a class, not an enum, and that is a rollback requirement.</b> The field is
 * persisted verbatim, and an enum would be stored as its {@code name()} string with no
 * {@code MongoCustomConversions} — which is exactly the shape that makes
 * {@code BotGroupStatus}' appended constants unreadable by an older jar
 * ({@code IllegalArgumentException} out of {@code Enum.valueOf}, see
 * {@code BotGroupStatusRollbackSafetyTest}). A {@code String} field holding an unknown value is
 * read back by any jar as a {@code String}; a pre-Phase-4 jar does not map the field at all and
 * simply leaves it in the document. So a rollback with a registration in flight degrades to "the
 * group looks created" rather than to a whole environment's list view failing.
 *
 * <p>These constants exist so the codebase has one spelling of each value rather than a literal
 * at every comparison. Do not turn them into an enum.
 *
 * <p><b>Absent ({@code null}) means complete or legacy</b>, and those two are deliberately the
 * same state: every group created before asynchronous registration has no field, and a group
 * whose worker finished clears it. Both are startable, and neither is a candidate for the
 * {@code RegistrationWorker}.
 */
public final class RegistrationState {

    /** Accounts are still being created. The group is not startable and the worker owns it. */
    public static final String PENDING = "PENDING";

    /**
     * Registration stopped on an index that failed {@code bot.registration.max-attempts-per-user}
     * times. Nothing retries it silently — {@code POST /{id}/registration/retry} is the way back,
     * and it resumes from {@code registeredCount + 1} rather than starting over.
     */
    public static final String FAILED = "FAILED";

    private RegistrationState() {
    }

    /** Whether {@code state} means the worker still owns this group. */
    public static boolean isPending(String state) {
        return PENDING.equals(state);
    }

    /** Whether {@code state} means registration stopped and only an operator can resume it. */
    public static boolean isFailed(String state) {
        return FAILED.equals(state);
    }

    /**
     * Whether {@code state} means registration is either in flight or stopped — i.e. whether the
     * group's accounts are not all known to exist. The single predicate the start guards use, so
     * "which states block a start" has one answer.
     */
    public static boolean isIncomplete(String state) {
        return isPending(state) || isFailed(state);
    }
}
