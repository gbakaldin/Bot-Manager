package com.vingame.bot.common.logging;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * The registry behind LOG_VOLUME_TIERING's <b>scoped per-group DEBUG</b> (AD-11): which
 * {@code botGroupId}s are currently allowed to emit DEBUG through a logger whose level is
 * INFO, and until when.
 * <p>
 * <b>Why scoped and why a TTL.</b> Global DEBUG at the target fleet size is ~5.2 GB/hour
 * and ~7,200 events/s — no retention policy makes that survivable, and it is the shape that
 * filled the disk on 2026-06-30. A single group's worth of DEBUG is tens of lines a minute.
 * The TTL is <em>mandatory</em>, not a convenience: the failure mode of "turn on detail"
 * has always been forgetting to turn it off, and here forgetting costs disk on a box that
 * has already died of it once. There is no "until I say stop" form of {@link #enable}.
 * <p>
 * <b>Read cost.</b> {@link #isAnyEnabled()} is a single volatile read, and it is what the
 * filter consults first (AD-10). With no scopes active — the permanent steady state — a log
 * call therefore costs one volatile read and nothing else: no map lookup, no MDC copy, no
 * allocation.
 * <p>
 * <b>Home.</b> {@code bot-api}, beside {@link BotMdc}, because all three consumers live in
 * different modules: the filter (here), the installer / REST surface ({@code bot-app}) and
 * the auto-escalator ({@code bot-engine}). Deliberately Spring-free — the singleton is
 * declared as a {@code @Bean} in {@code bot-app}.
 * <p>
 * Thread-safe. Every method may be called from any thread, including the log path.
 */
public final class ScopedDebugRegistry {

    /** TTL applied when a caller does not name one. Matches the REST default. */
    public static final Duration DEFAULT_TTL = Duration.ofMinutes(15);

    /** Hard ceiling on any single TTL, enforced here so no caller can opt out of AD-11. */
    public static final Duration MAX_TTL = Duration.ofHours(2);

    /**
     * Default ceiling on simultaneously enabled groups. Scoped DEBUG exists precisely
     * because fleet-wide DEBUG is unaffordable; without a cap, enabling it group by group
     * would reconstruct fleet-wide DEBUG one call at a time.
     */
    public static final int DEFAULT_MAX_SCOPES = 50;

    private final ConcurrentHashMap<String, Long> expiries = new ConcurrentHashMap<>();
    private final int maxScopes;
    private final LongSupplier clock;

    /**
     * The AD-10 fast path. Written on every mutation, read on every log call. A stale
     * {@code true} costs one wasted map lookup until the next sweep; a stale {@code false}
     * cannot happen, because it is set to {@code true} before the entry is published.
     */
    private volatile boolean anyEnabled;

    public ScopedDebugRegistry() {
        this(DEFAULT_MAX_SCOPES);
    }

    public ScopedDebugRegistry(int maxScopes) {
        this(maxScopes, System::currentTimeMillis);
    }

    /** Clock-injecting constructor — the test seam for TTL expiry without sleeping. */
    ScopedDebugRegistry(int maxScopes, LongSupplier clock) {
        this.maxScopes = maxScopes > 0 ? maxScopes : DEFAULT_MAX_SCOPES;
        this.clock = clock;
    }

    /**
     * Enable DEBUG for one group for a bounded time.
     *
     * @param botGroupId the group; {@code null}/blank is refused
     * @param ttl        how long for; {@code null} means {@link #DEFAULT_TTL}, non-positive
     *                   is refused, and anything above {@link #MAX_TTL} is clamped down to it
     * @return the expiry instant, or {@link Optional#empty()} when the request was refused
     *         (bad group id, non-positive TTL, or the {@code maxScopes} cap is full). Refusal
     *         is returned rather than thrown so the REST path can answer 400 and the
     *         auto-escalation path can WARN, neither of which wants an exception.
     */
    public Optional<Instant> enable(String botGroupId, Duration ttl) {
        if (botGroupId == null || botGroupId.isBlank()) {
            return Optional.empty();
        }
        Duration effective = ttl == null ? DEFAULT_TTL : ttl;
        if (effective.isZero() || effective.isNegative()) {
            return Optional.empty();
        }
        if (effective.compareTo(MAX_TTL) > 0) {
            effective = MAX_TTL;
        }

        long now = clock.getAsLong();
        // Sweep before the cap check so expired-but-unswept entries never block a new
        // scope: the 30 s sweeper is a backstop, not the only reclaim path.
        sweepAt(now);
        // A re-enable of an already-scoped group extends it and must not count as new.
        if (!expiries.containsKey(botGroupId) && expiries.size() >= maxScopes) {
            return Optional.empty();
        }

        long expiryMillis = now + effective.toMillis();
        anyEnabled = true; // publish BEFORE the entry, so the fast path can never miss it
        expiries.merge(botGroupId, expiryMillis, Math::max);
        return Optional.of(Instant.ofEpochMilli(expiryMillis));
    }

    /** Convenience for the common case. */
    public Optional<Instant> enable(String botGroupId) {
        return enable(botGroupId, DEFAULT_TTL);
    }

    /**
     * Turn a scope off early.
     *
     * @return {@code true} if the group had an active scope
     */
    public boolean disable(String botGroupId) {
        if (botGroupId == null) {
            return false;
        }
        boolean removed = expiries.remove(botGroupId) != null;
        anyEnabled = !expiries.isEmpty();
        return removed;
    }

    /**
     * Is DEBUG currently enabled for this group? The hot-path query, called from the log
     * filter, and therefore written to do as little as possible in the common case.
     */
    public boolean isEnabled(String botGroupId) {
        if (!anyEnabled || botGroupId == null) {
            return false;
        }
        Long expiry = expiries.get(botGroupId);
        if (expiry == null) {
            return false;
        }
        if (clock.getAsLong() >= expiry) {
            // Expire lazily as well as on the sweep, so a stopped sweeper can never leave
            // a scope on forever.
            expiries.remove(botGroupId, expiry);
            anyEnabled = !expiries.isEmpty();
            return false;
        }
        return true;
    }

    /** The AD-10 fast path: one volatile read, no map access. */
    public boolean isAnyEnabled() {
        return anyEnabled;
    }

    /**
     * Drop every expired scope.
     *
     * @return the group ids whose scope just expired, so the caller can log the expiry —
     *         one line per group, at the moment detail stops flowing
     */
    public List<String> sweep() {
        return sweepAt(clock.getAsLong());
    }

    private List<String> sweepAt(long now) {
        if (expiries.isEmpty()) {
            return List.of();
        }
        List<String> expired = new ArrayList<>();
        for (Map.Entry<String, Long> entry : expiries.entrySet()) {
            if (now >= entry.getValue() && expiries.remove(entry.getKey(), entry.getValue())) {
                expired.add(entry.getKey());
            }
        }
        anyEnabled = !expiries.isEmpty();
        return expired;
    }

    /**
     * The currently active scopes and their expiries, soonest first. Expired-but-unswept
     * entries are excluded, so this never reports a scope that is no longer in effect.
     */
    public Map<String, Instant> activeScopes() {
        long now = clock.getAsLong();
        Map<String, Instant> active = new LinkedHashMap<>();
        expiries.entrySet().stream()
                .filter(e -> e.getValue() > now)
                .sorted(Comparator.comparingLong(Map.Entry::getValue))
                .forEach(e -> active.put(e.getKey(), Instant.ofEpochMilli(e.getValue())));
        return active;
    }

    /** Ceiling on simultaneously enabled groups, for the REST surface to report. */
    public int getMaxScopes() {
        return maxScopes;
    }

    /** Drop every scope. Used by tests and by the installer on shutdown. */
    public void clear() {
        expiries.clear();
        anyEnabled = false;
    }
}
