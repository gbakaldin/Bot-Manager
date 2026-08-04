package com.vingame.bot.domain.game.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.vingame.bot.domain.brand.model.BrandCode;
import com.vingame.bot.domain.brand.model.ProductCode;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

@Getter
@Setter
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "games")
public class Game {

    @Id
    private String id;
    private BrandCode brandCode;
    private ProductCode productCode;

    /**
     * Environment this game belongs to (the {@code _id} of an
     * {@link com.vingame.bot.domain.environment.model.Environment}). The same
     * logical game in two environments is two separate {@link Game} documents,
     * each with its own {@code _id} and {@code environmentId}.
     *
     * <p>Nullable in the entity so pre-migration docs still deserialize during
     * the deploy window. The BOTGROUP_GAME_MANAGEMENT Phase 1 backfill script
     * (Releaser-run) sets it on every existing game via the deterministic
     * {@code (brandCode, productCode) -> environment._id} mapping. Read-side has
     * a defensive fallback treating a null value as matching any env — it should
     * never fire post-backfill (AD-1/AD-3).
     */
    private String environmentId;

    /**
     * Instant this game was first persisted. Stamped by
     * {@link com.vingame.bot.domain.game.service.GameService#save(Game)} when
     * null; never overwritten on update. Existing docs are backfilled to a fixed
     * timestamp by the Phase 1 migration script so nothing sorts as N/A on
     * {@code CREATED_TIME} (AD-14).
     */
    private Instant createdAt;

    /**
     * Instant of the most recent persist. Stamped by
     * {@link com.vingame.bot.domain.game.service.GameService#save(Game)} on every
     * save (create and update). Backfilled to the same fixed timestamp as
     * {@link #createdAt} by the Phase 1 migration script (AD-16).
     */
    private Instant updatedAt;

    private String name;
    private String description;
    private GameType gameType;

    private String pluginName;

    /**
     * Numeric game identifier. For {@link GameType#BETTING_MINI} games this is the
     * subscribe channel id (e.g. {@code 11005} for BauCua). For
     * {@link GameType#SLOT} games this carries the slot {@code gid} — the env-scoped
     * slot game id placed on every subscribe ({@code cmd:1300}) and spin
     * ({@code cmd:1302}) message. Winline count and allowed bet values for slots are
     * <b>not</b> config fields — they are sourced from the {@code cmd:1300} subscribe
     * response at runtime (see SLOT_MACHINE_BOT plan, AD-8/AD-11).
     */
    private Integer gameId;
    private Integer offset;
    private boolean md5;

    /**
     * Option id → affinity weight. Affinity is a neutral prior (default 1);
     * strategies decide how to use it. The map size implicitly defines the
     * number of bettable options for this game.
     *
     * <p>Persisted as a BSON document with integer-string keys (Mongo limitation —
     * BSON document keys are always strings). Spring Data MongoDB transparently
     * converts {@code Map<Integer, Integer>} round-trip.
     */
    private Map<Integer, Integer> optionAffinities;

    /**
     * Whether the jackpot-based volume scale lever is enabled for this game
     * (JACKPOT_SCALE_AND_RAMP AD-J1). When true, a group of a jackpot-bearing
     * game type ({@link GameType#BETTING_MINI}/{@link GameType#TAI_XIU}) reads the
     * live jackpot pool meter ({@code tJpV}) at round end and derives a per-round
     * volume scale factor. Jackpot presence is a game-intrinsic trait, so this
     * lives on the Game (not the BotGroup). Off = today's flat volume (AD-S3).
     */
    private boolean jackpotScaleEnabled;

    /**
     * Operator-configured upper bound of the jackpot pool at which bots bet at
     * full intensity (JACKPOT_SCALE_AND_RAMP AD-J1). The transfer function ramps
     * the volume factor linearly from the ~500k seed floor to this ceiling
     * (AD-J5). Only meaningful when {@link #jackpotScaleEnabled} is true;
     * validated to exceed the seed floor (must be {@code > 500000}) so the
     * transfer function is non-degenerate.
     */
    private long jackpotCeiling;

    /**
     * Per-game interpretation of the crowd feed's {@code bs[].bc} count field
     * (CROWD_AWARE_COORDINATION AD-C5). The bets-vs-players meaning is a
     * game-intrinsic protocol trait, so it lives on the Game (not the BotGroup).
     * Default {@link CrowdCountSemantic#UNKNOWN} — a null in a legacy Mongo doc
     * resolves to {@code UNKNOWN} via {@link #getEffectiveCrowdCountSemantic()}.
     *
     * <p><b>Observability-only in v1:</b> the crowd steering math is stake-based
     * ({@code v}), never count-based, so this field never drives the per-round
     * budget — a mis-set value cannot corrupt steering.
     */
    @Builder.Default
    private CrowdCountSemantic crowdCountSemantic = CrowdCountSemantic.UNKNOWN;

    /**
     * Legacy field — pre-BETTING_STRATEGIES Phase 1 docs persisted
     * {@code numberOfOptions} as a primitive int and {@code bettingOptions} as
     * an array of allowed option ids. Read-side fallback only: kept so old
     * Mongo docs continue to deserialize and {@link #getEffectiveOptionAffinities()}
     * can synthesize a flat-prior map until the Phase 6 migration runs.
     *
     * <p>Marked {@link JsonIgnore} so the REST DTO never round-trips it on
     * the wire. The Phase 1 GameDTO surface is {@code optionAffinities}-only
     * on reads; on writes the DTO accepts {@code numberOfOptions} as a
     * convenience shorthand that the mapper expands into {@code optionAffinities}
     * before persisting (the entity itself never re-uses this field on write).
     */
    @JsonIgnore
    @Getter(AccessLevel.PACKAGE)
    @Setter(AccessLevel.PACKAGE)
    private Integer numberOfOptions;

    /**
     * Legacy field — explicit list of allowed option ids (e.g. {@code [1, 3, 5]}
     * for odd-only dice faces). Pre-BETTING_STRATEGIES Phase 1 docs that
     * customised the playable option set used this field instead of the implicit
     * {@code [0..numberOfOptions-1]} range.
     *
     * <p>Read-side fallback only — like {@link #numberOfOptions} this is never
     * round-tripped to the wire. {@link #getEffectiveOptionAffinities()} prefers
     * this list over {@code numberOfOptions} when present so a game with
     * {@code bettingOptions = [1, 3, 5]} produces {@code {1:1, 3:1, 5:1}}
     * affinities — preserving the option-id semantics the operator chose.
     *
     * <p>If both {@code bettingOptions} and {@code numberOfOptions} are set the
     * explicit list wins (mirrors the legacy {@code getEffectiveBettingOptions()}
     * preference).
     */
    @JsonIgnore
    @Getter(AccessLevel.PACKAGE)
    @Setter(AccessLevel.PACKAGE)
    private List<Integer> bettingOptions;

    /**
     * Returns the effective option-affinity map.
     * <ul>
     *   <li>If {@code optionAffinities} is non-null and non-empty, returns it as-is.</li>
     *   <li>Else if legacy {@code bettingOptions} is non-null and non-empty,
     *       synthesizes a flat-prior map keyed by those explicit option ids —
     *       e.g. {@code [1, 3, 5]} → {@code {1:1, 3:1, 5:1}}. This preserves
     *       custom option-id sets (odd-only dice, sparse rewards) that would
     *       otherwise be silently replaced by the {@code numberOfOptions}
     *       range fallback.</li>
     *   <li>Else if legacy {@code numberOfOptions > 0}, synthesizes a flat-prior map
     *       {@code {0:1, 1:1, ..., n-1:1}} from the legacy field.</li>
     *   <li>Else throws {@link IllegalStateException} — a Game with neither field
     *       set is misconfigured.</li>
     * </ul>
     * Read-side fallback only; never written back to Mongo. The Phase 6 migration
     * persists the synthesized map and {@code $unset}s the legacy fields.
     */
    /**
     * The two fixed Tài/Xỉu entries a Tai Xiu game always offers, as a flat-prior
     * affinity map {@code {1:1, 2:1}}. Tai Xiu is intrinsically a 2-option game
     * (Tài vs Xỉu), so an operator never configures options for it. The eids
     * {@code 1} and {@code 2} are the values the inherited
     * {@code resolveNextEntryToBet}/strategy emit as the bet {@code eid} (the
     * captured Tai Xiu bet used {@code eid:1}), so a defaulted Tai Xiu game bets
     * over {@code {1, 2}}.
     *
     * <p>Insertion-ordered ({@link LinkedHashMap}) to match the deterministic
     * option iteration {@link #getEffectiveOptionAffinities()} guarantees for the
     * synthesized legacy paths.
     */
    public static Map<Integer, Integer> defaultTaiXiuOptionAffinities() {
        Map<Integer, Integer> defaults = new LinkedHashMap<>(2);
        defaults.put(1, 1);
        defaults.put(2, 1);
        return defaults;
    }

    /**
     * Tai-Xiu-scoped default: if this game has neither {@code optionAffinities}
     * nor a legacy option field set, populate {@code optionAffinities} with the
     * two fixed Tài/Xỉu entries ({@link #defaultTaiXiuOptionAffinities()}) so
     * {@link #getEffectiveOptionAffinities()} resolves to 2 equal options instead
     * of throwing. Tai Xiu is intrinsically a 2-option game, so this spares the
     * operator from ever setting {@code numberOfOptions} on it.
     *
     * <p><b>Idempotent and no-op when already configured.</b> If any option
     * field is set (including an explicit {@code numberOfOptions:2}) this leaves
     * the game untouched, so an operator override always wins. Only applied on
     * the Tai Xiu bot path (see {@code TaiXiuGameBot.initializeSubclass}); it
     * does <b>not</b> weaken {@link #getEffectiveOptionAffinities()} validation
     * for any other game type — BettingMini with no option config still throws.
     *
     * <p>{@code synchronized} on {@code this} because the same {@link Game}
     * instance is shared across the parallel bot-creation threads of a group;
     * the guarded check-then-set keeps the shared mutation race-free, and the
     * "only when absent" guard makes concurrent calls converge on the same map.
     */
    public synchronized void applyTaiXiuOptionDefaults() {
        boolean hasOptionConfig =
                (optionAffinities != null && !optionAffinities.isEmpty())
                        || (bettingOptions != null && !bettingOptions.isEmpty())
                        || (numberOfOptions != null && numberOfOptions > 0);
        if (!hasOptionConfig) {
            optionAffinities = defaultTaiXiuOptionAffinities();
        }
    }

    /**
     * Null-safe resolution of {@link #crowdCountSemantic} (CROWD_AWARE_COORDINATION
     * AD-C5). Legacy Mongo docs persisted before this field existed deserialize
     * with a {@code null} value (the {@code @Builder.Default} only applies to
     * builder-constructed instances, not Mongo hydration), so read-side callers
     * must go through this accessor to get the {@link CrowdCountSemantic#UNKNOWN}
     * fail-safe default.
     */
    public CrowdCountSemantic getEffectiveCrowdCountSemantic() {
        return crowdCountSemantic != null ? crowdCountSemantic : CrowdCountSemantic.UNKNOWN;
    }

    public Map<Integer, Integer> getEffectiveOptionAffinities() {
        if (optionAffinities != null && !optionAffinities.isEmpty()) {
            return optionAffinities;
        }
        if (bettingOptions != null && !bettingOptions.isEmpty()) {
            Map<Integer, Integer> synthesized = new LinkedHashMap<>(bettingOptions.size());
            for (Integer opt : bettingOptions) {
                if (opt != null) synthesized.put(opt, 1);
            }
            if (!synthesized.isEmpty()) {
                return synthesized;
            }
        }
        if (numberOfOptions != null && numberOfOptions > 0) {
            Map<Integer, Integer> synthesized = new LinkedHashMap<>(numberOfOptions);
            IntStream.range(0, numberOfOptions).forEach(i -> synthesized.put(i, 1));
            return synthesized;
        }
        throw new IllegalStateException(
                "Game " + id + " (" + name + ") has neither optionAffinities nor legacy numberOfOptions set");
    }
}
