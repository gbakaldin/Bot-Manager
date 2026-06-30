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
 * {@code environmentId}, {@code gameType}, {@code gameId}, {@code gameName}) from
 * MDC and attaches them as tags on
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
 * GRAFANA_PER_GAME_ENV_DASHBOARDS AD-9): the exposed identity tags are
 * {@code botGroupId}, {@code environmentId}, {@code gameType}, {@code gameId}, and
 * {@code gameName}. The latter two were added to enable the per-Game dashboard.
 * They are bounded and add near-zero real cardinality: {@code gameId}/{@code gameName}
 * are functionally dependent on {@code botGroupId} (a group maps to exactly one game),
 * so they are constant within a group's series. {@code gameType} now correctly carries
 * the {@code GameType} enum (~5 values), {@code gameName} the readable display name,
 * and {@code gameId} the stable Mongo {@code _id} UUID. The prohibition on unbounded
 * per-bot tags ({@code botId}, {@code botUserName}) still stands.
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
        List<Tag> tags = new ArrayList<>(5);
        addIfPresent(tags, BotMdc.BOT_GROUP_ID);
        addIfPresent(tags, BotMdc.ENVIRONMENT_ID);
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
}
