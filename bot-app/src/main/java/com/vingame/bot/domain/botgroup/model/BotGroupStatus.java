package com.vingame.bot.domain.botgroup.model;

/**
 * The lifecycle state of a bot group.
 * <p>
 * <b>The three constants after {@link #DEAD} are appended, never inserted</b>
 * (GATEWAY_REQUEST_BUDGET Amendment A1 / AD-17a). {@code BotSortKey.STATUS} sorts on
 * {@code actualStatus()} as a {@link Comparable} enum, i.e. on {@link #ordinal()}, so
 * appending keeps {@code ACTIVE(0) < STOPPED(1) < DEAD(2)} and every existing
 * {@code STATUS} sort result byte-identical; inserting would silently reorder the sort for
 * every group that already exists. {@code BotGroupStatusAppendOnlyTest} fails the build if
 * that ordering is ever disturbed.
 * <p>
 * <b>Which field may hold which value</b> — this is the load-bearing half of A1, and it is
 * a rollback requirement rather than tidiness. {@code targetStatus} is persisted as the
 * enum {@code name()} string with no {@code MongoCustomConversions}, so a document holding
 * a constant an <em>older</em> jar does not know is unreadable: Spring Data's
 * {@code MappingMongoConverter} lets {@link Enum#valueOf}'s {@link IllegalArgumentException}
 * ("No enum constant …") out of {@code getPotentiallyConvertedSimpleRead}. Measured by
 * {@code BotGroupStatusRollbackSafetyTest} — earlier versions of this javadoc, and A1 itself,
 * named {@code ConversionFailedException}, which is not what happens.
 * <p>
 * <b>What that costs, precisely.</b> Not the boot query:
 * {@code findByTargetStatus(ACTIVE)} filters server-side on the string {@code "ACTIVE"}, so a
 * poisoned document is never returned and never converted. The damage is on every read that
 * <em>does</em> convert the group — {@code GET /{id}} and especially
 * {@code POST /{envId}/filter}, the UI's list view for a whole environment, where one poisoned
 * group 500s the list for every healthy group beside it — and, on the <em>current</em> jar,
 * the group silently drops out of {@code findByTargetStatus(ACTIVE)} and out of
 * {@code RecoveryEligibility}'s branches, so it never auto-starts and never auto-recovers
 * again. Rolling back to {@code vingame-bot:rollback-*} must stay a safe action at all times.
 * <p>
 * The perimeter is two things, both pinned by {@code BotGroupStatusPersistenceGuardTest}:
 * {@code BotGroupDTO.targetStatus} is {@code @JsonProperty(access = READ_ONLY)} so a request
 * body cannot carry one of these values, and {@code BotGroupMapper} copies the field in
 * neither write direction so an in-process caller cannot either.
 *
 * <table border="1">
 *   <caption>Field eligibility</caption>
 *   <tr><th>Value</th><th>{@code BotGroup.targetStatus} (Mongo)</th>
 *       <th>{@code BotGroupRuntime.actualStatus} (memory)</th><th>DTOs</th></tr>
 *   <tr><td>{@code ACTIVE} / {@code STOPPED} / {@code DEAD}</td><td>yes</td><td>yes</td><td>yes</td></tr>
 *   <tr><td>{@code STARTING}</td><td><b>never</b></td><td>yes</td><td>yes</td></tr>
 *   <tr><td>{@code REGISTRATION_PENDING} / {@code REGISTRATION_FAILED}</td>
 *       <td><b>never</b></td><td><b>never</b></td><td>yes — derived only</td></tr>
 * </table>
 *
 * {@code BotGroupStatusPersistenceGuardTest} is the source guard that keeps the "never"
 * column true: no production call site may pass one of the three new constants to
 * {@code setTargetStatus}.
 */
public enum BotGroupStatus {

    ACTIVE, //The bot group is currently running
    STOPPED, //The bot group has been stopped by the admin user
    DEAD, //The bot group has crashed or become unresponsive

    /**
     * A start is in flight: the group has been accepted for start and its bots are being
     * authenticated and connected. <b>In-memory only</b> — {@code BotGroupRuntime} is born
     * {@code STARTING} and flipped to {@link #ACTIVE} at the end of a successful
     * {@code startLocked}. A JVM restart loses it, which is correct: an interrupted start is
     * not a state to resume.
     * <p>
     * Under a paced start (GATEWAY_REQUEST_BUDGET Phase 3) this state can legitimately last
     * tens of minutes for a large group — that is the Cloudflare rule working, not a hang.
     */
    STARTING,

    /**
     * Accounts are still being registered for this group. <b>Derived at the DTO boundary
     * only</b>, from the additive {@code registrationState} document field that arrives with
     * asynchronous registration (Phase 4); never persisted in {@code targetStatus}, never
     * held by a runtime. Declared here now so the enum's final order is fixed in one place
     * and Phase 4 cannot be tempted to insert.
     */
    REGISTRATION_PENDING,

    /**
     * Registration stopped on a failed account and will not resume without an operator
     * action. Same derived-only rules as {@link #REGISTRATION_PENDING}; produced from Phase 4
     * onward.
     */
    REGISTRATION_FAILED
}
