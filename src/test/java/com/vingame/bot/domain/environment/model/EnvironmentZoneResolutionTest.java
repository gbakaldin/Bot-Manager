package com.vingame.bot.domain.environment.model;

import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.domain.game.model.GameType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link Environment#resolveZoneName(Game)}.
 * <p>
 * The resolver replaces the unconditional {@code env.getMiniZoneName()} read sites in
 * {@code BotFactory}, {@code EnvironmentClientRegistry}, and {@code BotGroupBehaviorService}.
 * Rule (RESTART_LIFECYCLE_FIX, Architecture Decision 1):
 * <pre>
 *   customZone=true  → mini? miniZoneName : cardZoneName
 *   customZone=false → mini? "MiniGame"   : "Simms"
 * </pre>
 * where {@code mini == (game.getGameType() == GameType.BETTING_MINI
 * || game.getGameType() == GameType.SLOT)} — slot games share the mini zone.
 */
@DisplayName("Environment.resolveZoneName")
class EnvironmentZoneResolutionTest {

    @Test
    @DisplayName("customZone=false + BETTING_MINI returns the default mini zone name")
    void resolveZoneName_usesDefaultWhenCustomZoneFalse_mini() {
        Environment env = Environment.builder()
                .customZone(false)
                .miniZoneName(null)
                .cardZoneName(null)
                .build();
        Game game = Game.builder().gameType(GameType.BETTING_MINI).build();

        assertThat(env.resolveZoneName(game)).isEqualTo("MiniGame");
    }

    @Test
    @DisplayName("customZone=false + CARD_GAME returns the default card zone name")
    void resolveZoneName_usesDefaultWhenCustomZoneFalse_card() {
        Environment env = Environment.builder()
                .customZone(false)
                .miniZoneName(null)
                .cardZoneName(null)
                .build();
        Game game = Game.builder().gameType(GameType.CARD_GAME).build();

        assertThat(env.resolveZoneName(game)).isEqualTo("Simms");
    }

    @Test
    @DisplayName("customZone=true + BETTING_MINI returns the env's custom miniZoneName")
    void resolveZoneName_usesCustomWhenCustomZoneTrue_mini() {
        Environment env = Environment.builder()
                .customZone(true)
                .miniZoneName("MyMiniZone")
                .cardZoneName("UnusedCard")
                .build();
        Game game = Game.builder().gameType(GameType.BETTING_MINI).build();

        assertThat(env.resolveZoneName(game)).isEqualTo("MyMiniZone");
    }

    @Test
    @DisplayName("customZone=true + CARD_GAME returns the env's custom cardZoneName")
    void resolveZoneName_usesCustomWhenCustomZoneTrue_card() {
        Environment env = Environment.builder()
                .customZone(true)
                .miniZoneName("UnusedMini")
                .cardZoneName("MyCardZone")
                .build();
        Game game = Game.builder().gameType(GameType.CARD_GAME).build();

        assertThat(env.resolveZoneName(game)).isEqualTo("MyCardZone");
    }

    @Test
    @DisplayName("customZone=false + SLOT returns the default mini zone name (slot shares the mini zone)")
    void resolveZoneName_usesDefaultWhenCustomZoneFalse_slot() {
        Environment env = Environment.builder()
                .customZone(false)
                .miniZoneName(null)
                .cardZoneName(null)
                .build();
        Game game = Game.builder().gameType(GameType.SLOT).build();

        assertThat(env.resolveZoneName(game)).isEqualTo("MiniGame");
    }

    @Test
    @DisplayName("customZone=true + SLOT returns the env's custom miniZoneName (slot shares the mini zone)")
    void resolveZoneName_usesCustomWhenCustomZoneTrue_slot() {
        Environment env = Environment.builder()
                .customZone(true)
                .miniZoneName("MyMiniZone")
                .cardZoneName("UnusedCard")
                .build();
        Game game = Game.builder().gameType(GameType.SLOT).build();

        assertThat(env.resolveZoneName(game)).isEqualTo("MyMiniZone");
    }

    @ParameterizedTest(name = "{0} → \"Simms\" default")
    @EnumSource(value = GameType.class, names = {"TAI_XIU", "UP_DOWN"})
    @DisplayName("TAI_XIU / UP_DOWN are treated as card games (default Simms)")
    void resolveZoneName_treatsTaiXiuAndUpDownAsCard(GameType type) {
        Environment env = Environment.builder()
                .customZone(false)
                .miniZoneName(null)
                .cardZoneName(null)
                .build();
        Game game = Game.builder().gameType(type).build();

        assertThat(env.resolveZoneName(game)).isEqualTo("Simms");
    }
}
