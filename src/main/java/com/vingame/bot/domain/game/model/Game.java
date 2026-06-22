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
