package com.vingame.bot.domain.botgroup.validation;

import com.vingame.bot.domain.botgroup.model.BotGroup;
import com.vingame.bot.domain.game.model.GameType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Phase 3 tests for the permissive no-op validators: {@link SlotConfigValidator}
 * (AD-4 — betting fields are server-sourced and ignored) and the unimplemented
 * game-type validators {@link CardGameConfigValidator} / {@link UpDownConfigValidator}
 * (AD-5 — do not block group creation for types that cannot start a bot anyway).
 *
 * <p>{@code TaiXiuConfigValidator} was promoted out of the permissive set
 * (TAI_XIU_BOT plan AD-8 — Tai Xiu can now start a bot and enforces the same
 * STRICT-GRID rules as BETTING_MINI); its coverage lives in
 * {@link TaiXiuConfigValidatorTest}.
 *
 * <p>Each is proven permissive by feeding it a configuration that
 * {@link BettingMiniConfigValidator} <em>would</em> reject (every betting field
 * garbage: {@code minBet > maxBet}, {@code betIncrement = 0}, negative and
 * inverted round counts, cap below maxBet) and asserting it does not throw.
 */
@DisplayName("permissive (no-op) config validators")
class PermissiveConfigValidatorsTest {

    private final SlotConfigValidator slot = new SlotConfigValidator();
    private final CardGameConfigValidator cardGame = new CardGameConfigValidator();
    private final UpDownConfigValidator upDown = new UpDownConfigValidator();

    /**
     * A config that violates essentially every BETTING_MINI rule at once:
     * minBet > maxBet, betIncrement = 0 (also breaks the grid), negative
     * minBetsPerRound, minBetsPerRound > maxBetsPerRound (which is also 0), and
     * a cap below maxBet. {@link BettingMiniConfigValidator} rejects this; the
     * permissive validators must accept it.
     */
    private static BotGroup garbageBettingConfig() {
        return BotGroup.builder()
                .minBet(999)
                .maxBet(1)
                .betIncrement(0)
                .minBetsPerRound(-5)
                .maxBetsPerRound(0)
                .maxTotalBetPerRound(0)
                .build();
    }

    @Test
    @DisplayName("the garbage config is genuinely rejected by BETTING_MINI (sanity)")
    void garbageConfigRejectedByBettingMini() {
        assertThatCode(() -> new BettingMiniConfigValidator().validate(garbageBettingConfig()))
                .as("the shared garbage config must be invalid for BETTING_MINI, "
                        + "otherwise the permissive assertions below prove nothing")
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("SLOT accepts a config BETTING_MINI would reject (AD-4)")
    void slotAcceptsGarbage() {
        assertThatCode(() -> slot.validate(garbageBettingConfig()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("CARD_GAME accepts a config BETTING_MINI would reject (AD-5)")
    void cardGameAcceptsGarbage() {
        assertThatCode(() -> cardGame.validate(garbageBettingConfig()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("UP_DOWN accepts a config BETTING_MINI would reject (AD-5)")
    void upDownAcceptsGarbage() {
        assertThatCode(() -> upDown.validate(garbageBettingConfig()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("permissive validators accept an empty/default config too")
    void acceptDefaultConfig() {
        BotGroup empty = BotGroup.builder().build();
        assertThatCode(() -> slot.validate(empty)).doesNotThrowAnyException();
        assertThatCode(() -> cardGame.validate(empty)).doesNotThrowAnyException();
        assertThatCode(() -> upDown.validate(empty)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("each validator reports its own supportedType")
    void supportedTypes() {
        assertThat(slot.supportedType()).isEqualTo(GameType.SLOT);
        assertThat(cardGame.supportedType()).isEqualTo(GameType.CARD_GAME);
        assertThat(upDown.supportedType()).isEqualTo(GameType.UP_DOWN);
    }
}
