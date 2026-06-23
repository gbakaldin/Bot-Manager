package com.vingame.bot.domain.botgroup.validation;

import com.vingame.bot.domain.botgroup.model.BotGroup;
import com.vingame.bot.domain.game.model.GameType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("GameConfigValidatorFactory")
class GameConfigValidatorFactoryTest {

    /** Minimal stub validator keyed on an arbitrary type. */
    private static GameConfigValidator stub(GameType type) {
        return new GameConfigValidator() {
            @Override
            public GameType supportedType() {
                return type;
            }

            @Override
            public void validate(BotGroup group) {
                // no-op
            }
        };
    }

    private static List<GameConfigValidator> allTypes() {
        return List.of(
                stub(GameType.BETTING_MINI),
                stub(GameType.SLOT),
                stub(GameType.TAI_XIU),
                stub(GameType.CARD_GAME),
                stub(GameType.UP_DOWN));
    }

    @Test
    @DisplayName("registers a non-null validator for every GameType")
    void resolvesEveryGameType() {
        GameConfigValidatorFactory factory = new GameConfigValidatorFactory(allTypes());
        factory.init();

        assertThat(factory.registeredTypes()).containsExactlyInAnyOrder(GameType.values());
        for (GameType type : GameType.values()) {
            assertThat(factory.forType(type)).isNotNull();
            assertThat(factory.forType(type).supportedType()).isEqualTo(type);
        }
    }

    @Test
    @DisplayName("duplicate supportedType throws IllegalStateException")
    void duplicateTypeThrows() {
        GameConfigValidatorFactory factory = new GameConfigValidatorFactory(List.of(
                stub(GameType.BETTING_MINI),
                stub(GameType.BETTING_MINI)));

        assertThatThrownBy(factory::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate GameConfigValidator")
                .hasMessageContaining(GameType.BETTING_MINI.name());
    }

    @Test
    @DisplayName("missing a GameType throws IllegalStateException at boot")
    void missingTypeThrows() {
        GameConfigValidatorFactory factory = new GameConfigValidatorFactory(List.of(
                stub(GameType.BETTING_MINI)));

        assertThatThrownBy(factory::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No GameConfigValidator registered for");
    }

    @Test
    @DisplayName("forType on an unregistered type throws IllegalArgumentException")
    void forTypeUnknownThrows() {
        // Build a factory with only one type, bypassing init()'s completeness
        // check, to exercise the forType guard directly.
        GameConfigValidatorFactory factory = new GameConfigValidatorFactory(List.of(
                stub(GameType.BETTING_MINI)));

        assertThatThrownBy(() -> factory.forType(GameType.SLOT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No GameConfigValidator registered for");
    }
}
