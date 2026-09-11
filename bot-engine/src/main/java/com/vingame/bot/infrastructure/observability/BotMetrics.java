package com.vingame.bot.infrastructure.observability;

import com.vingame.bot.common.logging.BotMdc;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Central holder for all bot-emitted Micrometer counters.
 * <p>
 * On every {@code inc*} call, this class reads bot identity ({@code botGroupId},
 * {@code environmentId}, {@code product}, {@code gameType}, {@code gameId},
 * {@code gameName}) from MDC and attaches them as tags on
 * the Counter builder. The registry interns counters by {@code name + tags}, so
 * each unique combination of MDC values produces a distinct time series with
 * effectively zero per-call overhead after first creation.
 * <p>
 * <b>Why read MDC here instead of letting {@link BotMdcTagsMeterFilter} do it?</b>
 * Micrometer's {@code MeterRegistry} caches Counter handles by the <i>pre-filter</i>
 * {@code Meter.Id} (see {@code AbstractMeterRegistry#getOrCreateMeter}: it consults
 * {@code preFilterIdToMeterMap} before applying any filter). That means if the
 * pre-filter id is identical across calls (e.g. {@code bot_messages_total{cmd=endGame}}),
 * Micrometer returns the same cached Counter regardless of what the filter would do
 * with the current MDC. To get per-group time series, the MDC tags must be present
 * on the Counter.Builder BEFORE registration. The {@link BotMdcTagsMeterFilter}
 * still serves as defense-in-depth for any future {@code bot_*} meter created outside
 * this class, and enforces the aggregate-gauge exclusion list.
 * <p>
 * Naming convention (Architecture Decision 11):
 * <ul>
 *   <li>{@code bot_*} — per-bot semantics (carries MDC-driven group/env/game tags).</li>
 *   <li>All counters end in {@code _total}.</li>
 * </ul>
 * Cardinality cap (Architecture Decision 5, as amended by
 * GRAFANA_PER_GAME_ENV_DASHBOARDS AD-9 and VIPTALK_ALERTING_V2 AD-V1): the exposed
 * identity tags are {@code botGroupId}, {@code environmentId}, {@code product},
 * {@code gameType}, {@code gameId}, and {@code gameName}.
 * {@code gameId}/{@code gameName} were added to enable the per-Game dashboard.
 * They are bounded and add near-zero real cardinality: {@code gameId}/{@code gameName}
 * are functionally dependent on {@code botGroupId} (a group maps to exactly one game),
 * so they are constant within a group's series. {@code gameType} now correctly carries
 * the {@code GameType} enum (~5 values), {@code gameName} the readable display name,
 * and {@code gameId} the stable Mongo {@code _id} UUID. The prohibition on unbounded
 * per-bot tags ({@code botId}, {@code botUserName}) still stands.
 * <p>
 * {@code product} (VIPTALK_ALERTING_V2 AD-V1) carries the numeric product code
 * ({@code ProductCode.getCode()}, e.g. {@code "116"}) and costs <b>zero new time
 * series</b>: {@code Game} carries both {@code productCode} and {@code environmentId},
 * so {@code gameId → environmentId → product} is a chain of functional dependencies
 * over labels already on every one of these series. The same series simply gain an
 * extra label. The only cost is series identity churn at the deploy that introduces
 * the label — irrelevant for counters (they reset on restart anyway) and harmless for
 * {@code sum by(...)} dashboard queries and {@code metric&#123;a="x"&#125;} selectors.
 */
@Component
public class BotMetrics {

    public static final String BOT_MESSAGES_TOTAL = "bot_messages_total";
    public static final String BOT_FAILURES_TOTAL = "bot_failures_total";
    public static final String BOT_RECONNECTS_TOTAL = "bot_reconnects_total";
    public static final String BOT_AUTO_DEPOSITS_TOTAL = "bot_auto_deposits_total";
    public static final String BOT_BETS_PLACED_TOTAL = "bot_bets_placed_total";
    public static final String BOT_BET_AMOUNT_TOTAL = "bot_bet_amount_total";
    public static final String BOT_LOGIN_TOTAL = "bot_login_total";
    public static final String BOT_VERIFY_TOKEN_TOTAL = "bot_verify_token_total";
    public static final String BOT_WATCHDOG_EXPIRED_TOTAL = "bot_watchdog_expired_total";
    public static final String BOT_WS_CONNECTIONS_TOTAL = "bot_ws_connections_total";
    public static final String BOT_DEAD_SECONDS_TOTAL = "bot_dead_seconds_total";
    public static final String GROUP_DEAD_SECONDS_TOTAL = "group_dead_seconds_total";

    // Phase 4 — bot's own winnings + jackpot. Same per-bot tag shape as the
    // existing bot_* meters (botGroupId, environmentId, gameType via mdcTags()).
    public static final String BOT_WINNINGS_TOTAL = "bot_winnings_total";
    public static final String BOT_JACKPOTS_TOTAL = "bot_jackpots_total";
    public static final String BOT_JACKPOT_AMOUNT_TOTAL = "bot_jackpot_amount_total";

    // RESTART_LIFECYCLE_FIX — per-bot creation failures during group start.
    // Tag {@code reason} is bounded: validation | auth | unknown (Architecture
    // Decision 5). Same MDC-driven per-bot tag shape as the rest.
    public static final String BOT_CREATION_FAILURES_TOTAL = "bot_creation_failures_total";

    // METRICS_IMPROVEMENT Phase 1 — observed real-balance depletion ("money
    // drain"). Same per-bot tag shape as the other bot_* counters (AD-3).
    public static final String BOT_MONEY_DRAINED_TOTAL = "bot_money_drained_total";

    // DEAD_GROUP_AUTO_RECOVERY AD-13 — group-scoped recovery counters. Same shape as
    // GROUP_DEAD_SECONDS_TOTAL above: tagged from mdcTags() under the recovery
    // scheduler's per-group MDC, and NOT bot_-prefixed, so BotMdcTagsMeterFilter
    // leaves them alone. Their rate is a function of incidents, never of fleet size.
    public static final String GROUP_RECOVERY_ATTEMPTS_TOTAL = "group_recovery_attempts_total";
    public static final String GROUP_RECOVERY_EXHAUSTED_TOTAL = "group_recovery_exhausted_total";

    private final MeterRegistry registry;

    public BotMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * Read bot identity tags from MDC. Returns an empty {@link Tags} if no MDC keys
     * are populated (e.g. when called from a non-bot-owned thread). Empty-string MDC
     * values are skipped — they would prometheus-format as {@code key=""} which is
     * legal but noisy.
     */
    private Tags mdcTags() {
        List<Tag> tags = new ArrayList<>(6);
        addIfPresent(tags, BotMdc.BOT_GROUP_ID);
        addIfPresent(tags, BotMdc.ENVIRONMENT_ID);
        addIfPresent(tags, BotMdc.PRODUCT);
        addIfPresent(tags, BotMdc.GAME_TYPE);
        addIfPresent(tags, BotMdc.GAME_ID);
        addIfPresent(tags, BotMdc.GAME_NAME);
        return tags.isEmpty() ? Tags.empty() : Tags.of(tags);
    }

    private static void addIfPresent(List<Tag> tags, String key) {
        String value = MDC.get(key);
        if (value != null && !value.isEmpty()) {
            tags.add(Tag.of(key, value));
        }
    }

    /**
     * Increment the per-bot message counter for the given protocol command.
     *
     * @param cmd one of {@code subscribe|startGame|updateBet|endGame}
     */
    public void incBotMessage(String cmd) {
        Counter.builder(BOT_MESSAGES_TOTAL)
                .tag("cmd", cmd)
                .tags(mdcTags())
                .register(registry)
                .increment();
    }

    /** Increment the per-bot failure counter (fires on transition into DEAD). */
    public void incBotFailure() {
        Counter.builder(BOT_FAILURES_TOTAL)
                .tags(mdcTags())
                .register(registry)
                .increment();
    }

    /**
     * Increment the per-bot reconnect counter.
     *
     * @param reason normalized reason: {@code watchdog|ws-disconnect|reauth-cycle}
     */
    public void incBotReconnect(String reason) {
        Counter.builder(BOT_RECONNECTS_TOTAL)
                .tag("reason", reason)
                .tags(mdcTags())
                .register(registry)
                .increment();
    }

    /** Increment the per-bot auto-deposit counter, tagged with outcome. */
    public void incBotAutoDeposit(boolean success) {
        Counter.builder(BOT_AUTO_DEPOSITS_TOTAL)
                .tag("outcome", success ? "success" : "failure")
                .tags(mdcTags())
                .register(registry)
                .increment();
    }

    /**
     * Increment the per-bot creation-failure counter. Fired from
     * {@code BotGroupBehaviorService.createBotsInParallel}'s catch block when a
     * single bot fails to be created during group start.
     *
     * @param reason bounded label: {@code validation | auth | unknown}.
     */
    public void incBotCreationFailure(String reason) {
        Counter.builder(BOT_CREATION_FAILURES_TOTAL)
                .tag("reason", reason)
                .tags(mdcTags())
                .register(registry)
                .increment();
    }

    /** Increment the watchdog-expired counter. */
    public void incBotWatchdogExpired() {
        Counter.builder(BOT_WATCHDOG_EXPIRED_TOTAL)
                .tags(mdcTags())
                .register(registry)
                .increment();
    }

    /**
     * Increment the per-bot WebSocket lifecycle event counter.
     *
     * @param event one of {@code connected|authenticating|disconnected}
     */
    public void incBotWsEvent(String event) {
        Counter.builder(BOT_WS_CONNECTIONS_TOTAL)
                .tag("event", event)
                .tags(mdcTags())
                .register(registry)
                .increment();
    }

    /**
     * Record a batch of bets confirmed by the server's {@code EndGame} payload:
     * increments {@code bot_bets_placed_total} by {@code count} and
     * {@code bot_bet_amount_total} by {@code totalAmount}.
     * <p>
     * Per-bet average is {@code bot_bet_amount_total / bot_bets_placed_total}
     * in PromQL and is accurate as long as both sums are accurate — this method
     * preserves both sums exactly without making any per-bet-amount assumption.
     * No loop, no averaging.
     * <p>
     * Dispatched from {@link com.vingame.bot.domain.bot.core.BettingMiniGameBot}'s
     * {@code onEndGame} when the message implements {@code HasBetTotals}. The
     * local AtomicLong accumulators on {@code Bot} (read by {@code BotHealthDTO})
     * still count bets sent, so the two values can legitimately diverge when the
     * server rejects bets — see {@code docs/plans/ENDGAME_METRICS.md} AD-4.
     * <p>
     * Caller-side contract: the two arguments are guarded independently. The count
     * counter is incremented iff {@code count > 0}; the amount counter is
     * incremented iff {@code totalAmount > 0}. This deliberately tolerates future
     * {@code HasBetTotals} implementers that emit one without the other — e.g. a
     * {@code (count=0, totalAmount=N>0)} payload still records the amount instead
     * of silently dropping it. A {@code (0, 0)} call creates no counters. Negative
     * values are treated as zero (silent drop).
     */
    public void incBetsPlaced(int count, long totalAmount) {
        Tags tags = null;
        if (count > 0) {
            tags = mdcTags();
            Counter.builder(BOT_BETS_PLACED_TOTAL)
                    .tags(tags)
                    .register(registry)
                    .increment(count);
        }
        if (totalAmount > 0) {
            if (tags == null) tags = mdcTags();
            Counter.builder(BOT_BET_AMOUNT_TOTAL)
                    .tags(tags)
                    .register(registry)
                    .increment(totalAmount);
        }
    }

    /**
     * Per-bot gross winnings counter; increments {@code bot_winnings_total} by
     * {@code amount} (Phase 4). Same per-bot tag shape as the bet counters.
     * <p>
     * Called from {@code BettingMiniGameBot.onEndGame} when the EndGame message
     * implements {@link com.vingame.bot.domain.bot.message.HasBotWinnings}. Caller
     * guards on {@code amount > 0}.
     */
    public void incBotWinnings(long amount) {
        Counter.builder(BOT_WINNINGS_TOTAL)
                .tags(mdcTags())
                .register(registry)
                .increment(amount);
    }

    /**
     * Create the round-outcome counters at <b>zero</b> for the current MDC tag set,
     * without incrementing them. Called once per bot from {@code Bot.initialize()};
     * the registry interns by {@code name + tags} and the tags are group-scoped, so
     * this costs <b>five time series per group</b> — not per bot — and every call
     * after the first for a given group is a map lookup.
     * <p>
     * <b>Why this exists.</b> Micrometer only exposes a counter once something has
     * incremented it, so a group that has never settled a round emits <i>no series
     * at all</i> rather than a series reading {@code 0}. Those two states are
     * operationally opposite and were indistinguishable from the outside: on
     * 2026-08-24 staging had four groups running for three days with
     * {@code bot_bets_placed_total}, {@code bot_bet_amount_total},
     * {@code bot_winnings_total} and {@code bot_auto_deposits_total} <i>entirely
     * absent</i> from {@code /actuator/prometheus}, which reads identically to the
     * metrics never having been wired up. Telling the difference took reading this
     * source. The real cause was upstream — no {@code EndGame} frame ever arrived,
     * and all three bet/winnings counters are fed exclusively from
     * {@code BettingMiniGameBot.onEndGame} / {@code SlotMachineBot}'s spin result —
     * but nothing in the metrics said so.
     * <p>
     * Pre-registering makes the absence expressible: the dashboard panels that read
     * these four counters render {@code 0} instead of <i>No data</i> (only some of
     * them carry an {@code or vector(0)}), a plain
     * {@code rate(bot_bets_placed_total[30m]) == 0} becomes a usable expression, and
     * an operator reading {@code /actuator/prometheus} can tell a quiet group from an
     * unwired one without opening this file.
     * <p>
     * It is <b>not</b> what makes alerting on the condition possible — {@code
     * GameNoRounds} in {@code prometheus/alerts.yml} is {@code unless}-shaped and so
     * already treats an absent series as "no rounds". The gap that rule does have is
     * a different one, and pre-registration does not close it either: it keys on
     * {@code bot_messages_total{cmd=~"startGame|spin"}}, so a game that starts rounds
     * and never settles them is invisible to it. That is the live 2026-08-24 state of
     * the {@code 116} Xoc Dia group — 203,079 {@code startGame}, zero {@code
     * endGame}.
     * <p>
     * This also repairs the premise of LOG_VOLUME_TIERING AD-8, which demoted the
     * per-round session summaries INFO&rarr;DEBUG on the stated grounds that "prod
     * covers the same facts with {@code bot_bets_placed_total} /
     * {@code bot_bet_amount_total} / {@code bot_winnings_total}". A series that
     * never materialises covers nothing.
     * <p>
     * Scope is deliberately the four counters observed empty. Notably <b>not</b>
     * included are {@code bot_jackpots_total} / {@code bot_jackpot_amount_total}
     * (permanently zero for the many games that have no jackpot, where a zero
     * series reads as a broken feature rather than a quiet one) and
     * {@code bot_messages_total} (its {@code cmd} values are game-type-specific —
     * pre-registering {@code startGame}/{@code endGame} for a SLOT group would
     * assert traffic that game never produces).
     */
    public void preRegisterOutcomeCounters() {
        Tags tags = mdcTags();
        Counter.builder(BOT_BETS_PLACED_TOTAL).tags(tags).register(registry);
        Counter.builder(BOT_BET_AMOUNT_TOTAL).tags(tags).register(registry);
        Counter.builder(BOT_WINNINGS_TOTAL).tags(tags).register(registry);
        Counter.builder(BOT_AUTO_DEPOSITS_TOTAL).tag("outcome", "success").tags(tags).register(registry);
        Counter.builder(BOT_AUTO_DEPOSITS_TOTAL).tag("outcome", "failure").tags(tags).register(registry);
    }

    /**
     * Per-bot jackpot: increments {@code bot_jackpots_total} by 1 (count of
     * jackpot-winning rounds) AND {@code bot_jackpot_amount_total} by
     * {@code amount} (sum of jackpot value won). Same per-bot tag shape.
     * <p>
     * Called from {@code BettingMiniGameBot.onEndGame} when the EndGame message
     * implements {@link com.vingame.bot.domain.bot.message.HasJackpot}. Caller
     * guards on {@code amount > 0}.
     */
    public void incBotJackpot(long amount) {
        Tags tags = mdcTags();
        Counter.builder(BOT_JACKPOTS_TOTAL)
                .tags(tags)
                .register(registry)
                .increment();
        Counter.builder(BOT_JACKPOT_AMOUNT_TOTAL)
                .tags(tags)
                .register(registry)
                .increment(amount);
    }

    /** Increment the login counter tagged with outcome. */
    public void incLogin(boolean success) {
        Counter.builder(BOT_LOGIN_TOTAL)
                .tag("outcome", success ? "success" : "failure")
                .tags(mdcTags())
                .register(registry)
                .increment();
    }

    /** Increment the verify-token counter tagged with outcome. */
    public void incVerifyToken(boolean success) {
        Counter.builder(BOT_VERIFY_TOKEN_TOTAL)
                .tag("outcome", success ? "success" : "failure")
                .tags(mdcTags())
                .register(registry)
                .increment();
    }

    /**
     * Accumulate per-bot DEAD downtime in seconds. Called when a bot exits the DEAD
     * state (via {@code transitionStatus(prev=DEAD, next != DEAD)}) or when a DEAD
     * bot is cleaned up (terminal DEAD window). Counter is monotonically increasing
     * across the bot's lifetime; multiple DEAD windows are summed into the same
     * series.
     * <p>
     * Architecture Decision 3: STOPPED is intentional and does NOT contribute to
     * this counter — only DEAD windows do. A bot that goes DEAD → STOPPED still
     * credits the elapsed DEAD-seconds up to the STOPPED transition.
     * <p>
     * Non-positive durations are silently dropped (defensive: clock skew or a DEAD
     * window of <1s rounds to 0 via {@code Duration.toSeconds()}).
     */
    public void incBotDeadSeconds(long seconds) {
        if (seconds <= 0) return;
        Counter.builder(BOT_DEAD_SECONDS_TOTAL)
                .tags(mdcTags())
                .register(registry)
                .increment(seconds);
    }

    /**
     * Accumulate observed real-balance depletion ("money drain"), in currency
     * units, by {@code amount} (METRICS_IMPROVEMENT Phase 1). Same per-bot tag
     * shape as the other {@code bot_*} counters ({@code botGroupId},
     * {@code environmentId}, {@code gameType}, {@code gameId}, {@code gameName}
     * via {@code mdcTags()} — AD-3). No {@code botId}, so all bots in a group
     * share one series.
     * <p>
     * Driven by {@code Bot.recordFetchedBalance}: on every authoritative server
     * balance fetch it increments by {@code max(0, previousFetched - newFetched)}.
     * Non-positive amounts are silently dropped — the caller floors at 0, so the
     * deposit top-up jump (a large NEGATIVE delta) never registers as drain and
     * needs no special-casing (AD-1).
     * <p>
     * <b>Intended upward bias (AD-2):</b> net-gain windows contribute 0 rather
     * than offsetting prior drain, so this counter over-states true net depletion.
     * That is deliberate — "money drain" is a burn gauge, not net P&amp;L. Net
     * P&amp;L would be {@code bet - winnings}, which was explicitly rejected for
     * this metric because balance is independent ground truth.
     */
    public void incMoneyDrained(long amount) {
        if (amount <= 0) return;
        Counter.builder(BOT_MONEY_DRAINED_TOTAL)
                .tags(mdcTags())
                .register(registry)
                .increment(amount);
    }

    /**
     * Accumulate per-group DEAD downtime in seconds. Called when a group exits the
     * DEAD state (via stop / restart / cleanup at the runtime level). Counter is
     * monotonically increasing across the group's lifetime. STOPPED is intentional
     * and excluded (Architecture Decision 3).
     */
    public void incGroupDeadSeconds(long seconds) {
        if (seconds <= 0) return;
        Counter.builder(GROUP_DEAD_SECONDS_TOTAL)
                .tags(mdcTags())
                .register(registry)
                .increment(seconds);
    }

    /**
     * Count one auto-recovery attempt on a DEAD bot group
     * (DEAD_GROUP_AUTO_RECOVERY AD-13). {@code outcome} is bounded:
     * {@code success | failed | error} — respectively "the group came back up: an
     * ACTIVE runtime with at least one running bot, which includes a rebuild that
     * only authenticated a fraction of the group", "the start path ran and the group
     * is still not up", and "the start path threw".
     * <p>
     * Called under the recovery scheduler's per-group MDC, so the series carries
     * {@code botGroupId} / {@code environmentId} / {@code product} exactly like
     * {@code group_dead_seconds_total}. <b>No series exists until an attempt is
     * actually made</b>, which is what makes "shipped inert" observable: with
     * {@code bot.recovery.enabled=false} this is never called. Once an attempt does
     * happen, <em>all</em> of this counter's outcomes appear at once, at zero — see
     * {@link #initGroupRecoverySeries(String...)}.
     */
    public void incGroupRecoveryAttempt(String outcome) {
        Counter.builder(GROUP_RECOVERY_ATTEMPTS_TOTAL)
                .tag("outcome", outcome)
                .tags(mdcTags())
                .register(registry)
                .increment();
    }

    /**
     * Materialise every {@code group_recovery_*} series for the group whose MDC is
     * currently set, at zero, before any of them can be incremented. Called once at
     * the top of every recovery attempt.
     *
     * <p><b>This is what makes the Phase 4 alert rules able to fire at all, and it is
     * not tidiness.</b> A Micrometer counter does not exist until it is registered,
     * and registering it at increment time means its very first scraped sample is
     * {@code 1}. {@code increase(group_recovery_exhausted_total[15m])} over a window
     * whose samples are all {@code 1} is {@code last - first == 0}; Prometheus'
     * counter-start extrapolation cannot rescue it either, because that correction is
     * gated on {@code resultValue > 0}. So {@code EnvironmentGroupRecoveryExhausted}
     * ({@code > 0}) could never fire on a group's <em>first</em> exhaustion — the only
     * one that normally happens, since the state is in-memory and a JVM restart resets
     * it — and {@code EnvironmentGroupRecoveryFlapping} ({@code >= 3}) silently needed
     * a fourth self-heal. Pre-registering at zero makes the first real increment a
     * visible {@code 0 -> 1} step, which is what both rules read.
     *
     * <p><b>The tags must match the later increments exactly</b> or this registers a
     * second series and fixes nothing: {@link #mdcTags()} derives them from MDC, so
     * this must be called under the same group MDC ({@code botGroupId} /
     * {@code environmentId} / {@code product}) the increments run under. The
     * {@code outcome} values are passed in rather than hard-coded here because the
     * scheduler owns that vocabulary.
     *
     * <p><b>The tag <em>keys</em> must also be the same on every group's series, and
     * that is the caller's job</b>: {@code mdcTags()} skips an absent or empty MDC
     * value, so a group whose {@code product} could not be resolved would register a
     * two-key shape. Micrometer does not object — it registers both — but the
     * Prometheus exposition keeps only the first key set seen under a metric name and
     * silently omits every later shape, for the life of the JVM. That is why
     * {@code DeadGroupRecoveryScheduler} substitutes a placeholder
     * ({@code TAG_UNRESOLVED}) instead of passing a null through.
     *
     * <p>Nothing is registered while {@code bot.recovery.enabled} is false — the
     * reconciler never reaches an attempt, so "shipped inert" is unchanged.
     */
    public void initGroupRecoverySeries(String... outcomes) {
        Tags tags = mdcTags();
        for (String outcome : outcomes) {
            // register() alone creates the counter at 0.0 and is idempotent: the
            // second call returns the same meter rather than resetting it.
            Counter.builder(GROUP_RECOVERY_ATTEMPTS_TOTAL)
                    .tag("outcome", outcome)
                    .tags(tags)
                    .register(registry);
        }
        Counter.builder(GROUP_RECOVERY_EXHAUSTED_TOTAL)
                .tags(tags)
                .register(registry);
    }

    /**
     * Count one bot group whose recovery attempt budget is spent
     * (DEAD_GROUP_AUTO_RECOVERY AD-8). Fires once per death episode, at the end of
     * it: the group is left alone from here until an operator acts, so this is the
     * hand-off signal a human is expected to answer.
     * <p>
     * The series it moves already exists at zero — see
     * {@link #initGroupRecoverySeries(String...)}, without which the alert that reads
     * this counter cannot fire on a first exhaustion.
     */
    public void incGroupRecoveryExhausted() {
        Counter.builder(GROUP_RECOVERY_EXHAUSTED_TOTAL)
                .tags(mdcTags())
                .register(registry)
                .increment();
    }
}
