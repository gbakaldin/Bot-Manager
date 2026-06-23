package com.vingame.bot.domain.botgroup.validation;

import com.vingame.bot.domain.game.model.GameType;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Spring-managed registry that resolves the {@link GameConfigValidator} for a
 * {@link GameType}. Mirrors the bean-collection shape of
 * {@code BettingStrategyFactory} / {@code SlotStrategyFactory}: a
 * {@code @Component} that takes {@code List<GameConfigValidator>} in its
 * constructor and builds an {@link EnumMap} keyed by an enum in
 * {@link #init()}, rejecting duplicate keys with {@link IllegalStateException}.
 *
 * <p>Intentional deviation from the strategy factories (AD-2): the key is read
 * from {@link GameConfigValidator#supportedType()} on the bean itself rather
 * than from a marker annotation, because the mapping is 1:1 {@code GameType} →
 * validator and the key is the coarse enum, not a fine-grained id.
 *
 * <p>{@link #forType(GameType)} returns the registered validator (unknown type
 * → {@link IllegalArgumentException} — should never happen once every
 * {@link GameType} has a validator; the boot-time completeness assertion in
 * {@link #init()} turns a missed validator into a deploy-time failure rather
 * than a first-bad-request failure).
 *
 * <p>See {@code docs/plans/BOT_GROUP_CONFIG_VALIDATION.md}, Architecture
 * Decisions 1, 2.
 */
@Slf4j
@Component
public class GameConfigValidatorFactory {

    private final List<GameConfigValidator> discoveredValidators;
    private final Map<GameType, GameConfigValidator> registry =
            new EnumMap<>(GameType.class);

    public GameConfigValidatorFactory(List<GameConfigValidator> discoveredValidators) {
        this.discoveredValidators = discoveredValidators;
    }

    @PostConstruct
    void init() {
        for (GameConfigValidator bean : discoveredValidators) {
            GameType type = bean.supportedType();
            if (type == null) {
                throw new IllegalStateException(
                        "GameConfigValidator " + bean.getClass().getName()
                                + " returned null from supportedType()");
            }
            GameConfigValidator existing = registry.put(type, bean);
            if (existing != null) {
                throw new IllegalStateException(
                        "Duplicate GameConfigValidator for " + type + ": "
                                + existing.getClass().getName()
                                + " and " + bean.getClass().getName());
            }
        }

        // Fail fast at boot if any GameType lacks a validator, rather than
        // throwing IllegalArgumentException at the first request for that type.
        for (GameType type : GameType.values()) {
            if (!registry.containsKey(type)) {
                throw new IllegalStateException(
                        "No GameConfigValidator registered for " + type
                                + " — registered types: " + registry.keySet());
            }
        }

        log.info("GameConfigValidatorFactory initialized: registered {} validators — {}",
                registry.size(), registry.keySet());
    }

    /**
     * Resolve the validator for the given game type.
     *
     * @param type the {@link GameType} resolved from the group's {@code gameId}.
     * @return the registered {@link GameConfigValidator}.
     * @throws IllegalArgumentException if {@code type} has no registered
     *         validator (a deploy bug — guarded against at boot in {@link #init()}).
     */
    public GameConfigValidator forType(GameType type) {
        GameConfigValidator validator = registry.get(type);
        if (validator == null) {
            throw new IllegalArgumentException("No GameConfigValidator registered for " + type
                    + " — validators present: " + registry.keySet());
        }
        return validator;
    }

    /**
     * @return the set of registered game types. Used by tests to assert full
     *         {@link GameType} coverage.
     */
    public Set<GameType> registeredTypes() {
        return Collections.unmodifiableSet(registry.keySet());
    }
}
