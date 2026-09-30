package com.vingame.bot.infrastructure.gateway;

import com.vingame.bot.common.gateway.RequestTier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

/**
 * The whole policy of the gateway request budget, as one validated value object
 * (GATEWAY_REQUEST_BUDGET AD-5).
 * <p>
 * <b>Validated in the constructor, so a bad combination fails context refresh</b> rather
 * than being discovered when the window fills. The invariant is
 * {@code 0 < default.ceiling <= prioritized.ceiling <= essential.ceiling <= hard-cap < 1000}:
 * the <em>gap</em> between two ceilings is the reservation for the tier above, so a
 * non-monotonic set of ceilings does not mean "unusual policy", it means the tier below can
 * starve the tier above — the exact inversion the tiers exist to prevent. {@code < 1000} is
 * the Cloudflare rule itself; a hard cap at or above it cannot protect anything.
 * <p>
 * A {@code hardCap} above 900 is legal but logs one WARN: 900 is the margin the plan chose
 * against a limit of 1,000 that we do not control the accounting of (does the edge count
 * 4xx? does a WebSocket upgrade count? — Open Item 1, since answered yes), and the one observed
 * breach (~1,140 requests) outlived a full hour.
 * <p>
 * <b>{@code hardCap} is what the ceilings are validated against, not a second gate they pass
 * through</b> (QA Q4). Because {@code essential.ceiling <= hardCap} is enforced here and ESSENTIAL
 * has no tier above it to reserve for, ESSENTIAL's ceiling is <em>always</em> the binding limit and
 * the hard-cap check in the admission rule is unreachable by construction — kept as belt and
 * braces, not as the mechanism. The consequence worth knowing is that the documented
 * {@code essential.ceiling=850} escape hatch <b>lowers the effective cap to 850</b> rather than
 * reserving 50 for the tiers below; and it does not reserve anything for PRIORITIZED either,
 * because {@code prioritized.ceiling=750} binds first. {@code GatewayBudgetSettingsTest} pins that
 * arithmetic.
 *
 * @param mode                 observe (count only) or enforce (pace). See {@link GatewayBudgetMode}.
 * @param window               width of the sliding window. The Cloudflare rule is 5 minutes.
 * @param hardCap              no tier is ever admitted past this many requests in one window.
 * @param ceilings             per-tier ceiling, complete for every {@link RequestTier}.
 * @param maxWaits             per-tier maximum wait; {@link Duration#ZERO} means
 *                             "unbounded (but cancellable)" for {@link RequestTier#ESSENTIAL},
 *                             which is the only tier allowed to wait forever.
 * @param registrationMaxWait  wait override for registration (AD-19), so an admitted
 *                             registration finishes rather than half-finishes when a group
 *                             start floods the window mid-way. <b>Must be positive.</b> It is
 *                             passed to the budget as an explicit caller wait, where
 *                             {@link Duration#ZERO} means <em>now or never</em> — the opposite of
 *                             what a zero in {@code maxWaits} means (review F5).
 * @param countWsUpgrades      whether a WebSocket upgrade is stamped into the window.
 *                             {@code true} is the conservative default — Open Item 1 is
 *                             whether the WS hosts sit behind the same rule at all.
 * @param blockProbeInterval   how often, while a Cloudflare edge block is in force, the
 *                             circuit issues exactly one anonymous clearance probe (AD-13 as
 *                             amended by A16.1; the state machine lands in Phase 5). It was
 *                             called {@code block-cooldown} and that name was the wrong
 *                             assumption written into the code: the user's answer on a block's
 *                             lifetime is "possibly ~24 hours, possibly until someone clears it
 *                             manually", so nothing "cools down" and traffic never resumes on a
 *                             timer. 60 minutes costs 24 probes a day against a wall and still
 *                             detects a human-cleared block within the hour; 15 bought nothing
 *                             but 96.
 */
public record GatewayBudgetSettings(
        GatewayBudgetMode mode,
        Duration window,
        int hardCap,
        Map<RequestTier, Integer> ceilings,
        Map<RequestTier, Duration> maxWaits,
        Duration registrationMaxWait,
        boolean countWsUpgrades,
        Duration blockProbeInterval) {

    private static final Logger log = LoggerFactory.getLogger(GatewayBudgetSettings.class);

    /** The Cloudflare rule this budget exists to stay under: 1,000 requests / 5 min / IP. */
    public static final int CLOUDFLARE_LIMIT = 1000;

    /** The hard cap above which the margin against {@link #CLOUDFLARE_LIMIT} is thin enough to warn. */
    public static final int RECOMMENDED_MAX_HARD_CAP = 900;

    /**
     * How often an open circuit asks whether a Cloudflare block has been cleared (A16.1). One
     * hour, constant, never exponential — see the {@code blockProbeInterval} parameter doc.
     */
    public static final Duration BLOCK_PROBE_INTERVAL_DEFAULT = Duration.ofMinutes(60);

    public GatewayBudgetSettings {
        if (mode == null) {
            throw new IllegalStateException("bot.gateway.budget.mode is required");
        }
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalStateException("bot.gateway.budget.window must be positive, was " + window);
        }
        if (hardCap <= 0) {
            throw new IllegalStateException("bot.gateway.budget.hard-cap must be positive, was " + hardCap);
        }
        if (hardCap >= CLOUDFLARE_LIMIT) {
            throw new IllegalStateException("bot.gateway.budget.hard-cap must stay under the "
                    + "Cloudflare limit of " + CLOUDFLARE_LIMIT + " requests per window, was " + hardCap);
        }
        ceilings = requireComplete(ceilings, "ceiling");
        maxWaits = requireComplete(maxWaits, "max-wait");
        if (registrationMaxWait == null || registrationMaxWait.isZero() || registrationMaxWait.isNegative()) {
            // Zero rejected, and for the same reason its per-tier siblings reject it — except that
            // HERE zero means the opposite (review F5). A tier's `max-wait=0` means "unbounded" and
            // is legal for ESSENTIAL; this value is handed to a caller as an explicit wait, where
            // ZERO means NOW OR NEVER, so a zero here would make every registration a 429 the
            // moment the DEFAULT queue is non-empty. Two fields, same type, same literal, opposite
            // meanings — one of them was validated and one was not.
            throw new IllegalStateException("bot.gateway.budget.registration.max-wait must be "
                    + "positive (here, unlike the per-tier waits, 0 would mean 'never wait' and "
                    + "would make every registration fail the moment the DEFAULT queue is "
                    + "non-empty), was " + registrationMaxWait);
        }
        if (blockProbeInterval == null || blockProbeInterval.isZero() || blockProbeInterval.isNegative()) {
            // Zero is rejected, not just negatives: a zero probe interval would mean probing
            // continuously into an edge that is refusing us, which is the behaviour that kept
            // the observed block alive.
            throw new IllegalStateException("bot.gateway.budget.block-probe-interval must be "
                    + "positive, was " + blockProbeInterval);
        }

        // Monotonic, bottom-up: DEFAULT <= PRIORITIZED <= ESSENTIAL <= hardCap. Walked in
        // reverse declaration order so the message names the two keys that disagree.
        int previous = 0;
        for (int i = RequestTier.values().length - 1; i >= 0; i--) {
            RequestTier tier = RequestTier.values()[i];
            int ceiling = ceilings.get(tier);
            Duration maxWait = maxWaits.get(tier);
            if (ceiling <= 0) {
                throw new IllegalStateException("bot.gateway.budget.tier." + key(tier)
                        + ".ceiling must be positive, was " + ceiling);
            }
            if (ceiling < previous) {
                throw new IllegalStateException("bot.gateway.budget tier ceilings must be "
                        + "monotonic (default <= prioritized <= essential <= hard-cap): "
                        + key(tier) + "=" + ceiling + " is below the tier under it (" + previous + ")");
            }
            if (ceiling > hardCap) {
                throw new IllegalStateException("bot.gateway.budget.tier." + key(tier)
                        + ".ceiling=" + ceiling + " exceeds hard-cap=" + hardCap);
            }
            if (maxWait == null || maxWait.isNegative()) {
                throw new IllegalStateException("bot.gateway.budget.tier." + key(tier)
                        + ".max-wait must not be negative, was " + maxWait);
            }
            // Only ESSENTIAL may wait forever, and the rule behind that is A16.2: an
            // unbounded wait is admissible only where progress is GUARANTEED. The sliding
            // window guarantees it — stamps expire, so a 3,000-bot start is ~10 windows of
            // monotonic progress. An unbounded DEFAULT wait would park a registration thread
            // (or, worse, a library message-processor thread) for the life of the process, and
            // nothing about a DEFAULT request's admission is guaranteed to arrive while higher
            // tiers keep taking the room.
            if (maxWait.isZero() && tier != RequestTier.ESSENTIAL) {
                throw new IllegalStateException("bot.gateway.budget.tier." + key(tier)
                        + ".max-wait=0 means unbounded, which only " + RequestTier.ESSENTIAL
                        + " may be; give " + key(tier) + " a finite wait");
            }
            previous = ceiling;
        }

        if (hardCap > RECOMMENDED_MAX_HARD_CAP) {
            log.warn("bot.gateway.budget.hard-cap={} is above the recommended {} — the margin "
                            + "against Cloudflare's {}/window rule is thin, and the app does not "
                            + "control whether the edge counts 4xx responses or WebSocket upgrades",
                    hardCap, RECOMMENDED_MAX_HARD_CAP, CLOUDFLARE_LIMIT);
        }
    }

    /**
     * The shipped policy, as a value — the same numbers {@code application.properties}
     * declares and {@link com.vingame.bot.config.gateway.GatewayBudgetConfig}'s {@code @Value}
     * defaults repeat.
     * <p>
     * It exists for two reasons. First, so that {@code ApplicationContextLoadsTest} can assert
     * that the <b>bound</b> settings bean equals this — three independent copies of the
     * defaults (this method, the {@code @Value} fallbacks, the properties file) is exactly the
     * shape in which one of them silently drifts, and a box whose real ceiling is not the one
     * the plan reasoned about is a box that can still be blocked. Second, so every test
     * fixture across both modules builds the real policy rather than a plausible-looking one.
     * <p>
     * Not used by production wiring: the bean comes from configuration, so an operator's
     * override is never quietly replaced by a compiled constant.
     */
    public static GatewayBudgetSettings defaults() {
        Map<RequestTier, Integer> ceilings = new EnumMap<>(RequestTier.class);
        ceilings.put(RequestTier.DEFAULT, 500);
        ceilings.put(RequestTier.PRIORITIZED, 750);
        ceilings.put(RequestTier.ESSENTIAL, RECOMMENDED_MAX_HARD_CAP);
        Map<RequestTier, Duration> maxWaits = new EnumMap<>(RequestTier.class);
        maxWaits.put(RequestTier.DEFAULT, Duration.ofSeconds(30));
        maxWaits.put(RequestTier.PRIORITIZED, Duration.ofMinutes(10));
        maxWaits.put(RequestTier.ESSENTIAL, Duration.ZERO);
        return new GatewayBudgetSettings(
                GatewayBudgetMode.OBSERVE,
                Duration.ofMinutes(5),
                RECOMMENDED_MAX_HARD_CAP,
                ceilings,
                maxWaits,
                Duration.ofMinutes(15),
                true,
                BLOCK_PROBE_INTERVAL_DEFAULT);
    }

    /**
     * The same policy in the other mode — the fixture helper the tests were all open-coding,
     * and the reason it lives here is that an eight-argument copy constructor written out by
     * hand in a dozen test files is how one of those copies ends up with a different ceiling
     * than the policy it claims to be testing.
     */
    public GatewayBudgetSettings withMode(GatewayBudgetMode other) {
        return new GatewayBudgetSettings(other, window, hardCap, ceilings, maxWaits,
                registrationMaxWait, countWsUpgrades, blockProbeInterval);
    }

    /** The same policy with {@code count-ws-upgrades} flipped — Open Item 1's kill switch. */
    public GatewayBudgetSettings withCountWsUpgrades(boolean counted) {
        return new GatewayBudgetSettings(mode, window, hardCap, ceilings, maxWaits,
                registrationMaxWait, counted, blockProbeInterval);
    }

    /** The same policy with one tier's max-wait replaced. Tests only. */
    public GatewayBudgetSettings withMaxWait(RequestTier tier, Duration maxWait) {
        Map<RequestTier, Duration> replaced = new EnumMap<>(maxWaits);
        replaced.put(tier, maxWait);
        return new GatewayBudgetSettings(mode, window, hardCap, ceilings, replaced,
                registrationMaxWait, countWsUpgrades, blockProbeInterval);
    }

    /** The same policy with a different window. Tests only — the real window is the rule. */
    public GatewayBudgetSettings withWindow(Duration other) {
        return new GatewayBudgetSettings(mode, other, hardCap, ceilings, maxWaits,
                registrationMaxWait, countWsUpgrades, blockProbeInterval);
    }

    /** The same policy with a different hard cap. Tests only. */
    public GatewayBudgetSettings withHardCap(int cap) {
        return new GatewayBudgetSettings(mode, window, cap, ceilings, maxWaits,
                registrationMaxWait, countWsUpgrades, blockProbeInterval);
    }

    /** The same policy with one tier's ceiling replaced. Tests only. */
    public GatewayBudgetSettings withCeiling(RequestTier tier, int ceiling) {
        Map<RequestTier, Integer> replaced = new EnumMap<>(ceilings);
        replaced.put(tier, ceiling);
        return new GatewayBudgetSettings(mode, window, hardCap, replaced, maxWaits,
                registrationMaxWait, countWsUpgrades, blockProbeInterval);
    }

    /** The configured ceiling for {@code tier}; never null (validated complete). */
    public int ceiling(RequestTier tier) {
        return ceilings.get(tier);
    }

    /** The configured maximum wait for {@code tier}; {@code ZERO} = unbounded (ESSENTIAL only). */
    public Duration maxWait(RequestTier tier) {
        return maxWaits.get(tier);
    }

    /** {@code true} when this tier is allowed to wait forever (cancellably). */
    public boolean isUnboundedWait(RequestTier tier) {
        return maxWait(tier).isZero();
    }

    /** The lower-case property key of a tier: {@code bot.gateway.budget.tier.<key>.ceiling}. */
    public static String key(RequestTier tier) {
        return tier.name().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * The startup line's ceiling rendering, in declaration order:
     * {@code default=500 prioritized=750 essential=900}. Explicitly ordered rather than
     * taken from the map's iteration order — an {@code EnumMap} would agree by luck, a
     * {@code HashMap} would not, and this string is what the release verification greps.
     */
    public String describeCeilings() {
        StringBuilder out = new StringBuilder();
        RequestTier[] tiers = RequestTier.values();
        for (int i = tiers.length - 1; i >= 0; i--) {
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(key(tiers[i])).append('=').append(ceiling(tiers[i]));
        }
        return out.toString();
    }

    private static <V> Map<RequestTier, V> requireComplete(Map<RequestTier, V> values, String what) {
        if (values == null) {
            throw new IllegalStateException("bot.gateway.budget tier " + what + "s are required");
        }
        Map<RequestTier, V> copy = new EnumMap<>(RequestTier.class);
        copy.putAll(values);
        for (RequestTier tier : RequestTier.values()) {
            if (copy.get(tier) == null) {
                throw new IllegalStateException("bot.gateway.budget.tier." + key(tier) + "." + what
                        + " is not configured — every tier must declare one, or the tier it is "
                        + "missing for would silently have no policy at all");
            }
        }
        return Map.copyOf(copy);
    }
}
