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
     * Returns the effective option-affinity map.
     * <ul>
     *   <li>If {@code optionAffinities} is non-null and non-empty, returns it as-is.</li>
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
        if (numberOfOptions != null && numberOfOptions > 0) {
            Map<Integer, Integer> synthesized = new LinkedHashMap<>(numberOfOptions);
            IntStream.range(0, numberOfOptions).forEach(i -> synthesized.put(i, 1));
            return synthesized;
        }
        throw new IllegalStateException(
                "Game " + id + " (" + name + ") has neither optionAffinities nor legacy numberOfOptions set");
    }
}
