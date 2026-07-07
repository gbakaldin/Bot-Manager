package com.vingame.bot.domain.botgroup.validation;

import com.vingame.bot.common.exception.BadRequestException;
import com.vingame.bot.common.exception.ResourceNotFoundException;
import com.vingame.bot.domain.botgroup.model.BotGroup;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.domain.game.model.GameType;
import com.vingame.bot.domain.game.service.GameService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("BotGroupConfigValidationService")
class BotGroupConfigValidationServiceTest {

    @Mock
    private GameConfigValidatorFactory validatorFactory;

    @Mock
    private GameService gameService;

    @Mock
    private GameConfigValidator validator;

    @InjectMocks
    private BotGroupConfigValidationService service;

    @Test
    @DisplayName("null gameId throws BadRequestException")
    void nullGameIdThrows() {
        BotGroup group = BotGroup.builder().build();

        assertThatThrownBy(() -> service.validate(group))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("gameId is required");

        verify(gameService, never()).findById(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("blank gameId throws BadRequestException")
    void blankGameIdThrows() {
        BotGroup group = BotGroup.builder().gameId("   ").build();

        assertThatThrownBy(() -> service.validate(group))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("gameId is required");
    }

    @Test
    @DisplayName("unresolvable gameId is rethrown as BadRequestException (400, not 404)")
    void unknownGameIdBecomes400() {
        BotGroup group = BotGroup.builder().gameId("missing").build();
        when(gameService.findById("missing"))
                .thenThrow(new ResourceNotFoundException("Game not found with id: missing"));

        assertThatThrownBy(() -> service.validate(group))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Game not found: missing");
    }

    @Test
    @DisplayName("resolves GameType, selects validator, and delegates")
    void delegatesToValidator() {
        BotGroup group = BotGroup.builder().gameId("g1").build();
        Game game = Game.builder().id("g1").gameType(GameType.BETTING_MINI).build();
        when(gameService.findById("g1")).thenReturn(game);
        when(validatorFactory.forType(GameType.BETTING_MINI)).thenReturn(validator);

        service.validate(group);

        verify(validator).validate(group);
    }

    @Test
    @DisplayName("SCHEDULED group with no activation window is rejected by the activation seam (400) before gameId resolution")
    void scheduledWithoutWindowRejectedBeforeGameLookup() {
        // ActivationRules runs first in validate() (TIMED_ACTIVATION AD-7), so a
        // bad activation config is a 400 even when the gameId would otherwise be
        // resolved — and the game/validator path is never reached.
        BotGroup group = BotGroup.builder()
                .gameId("g1")
                .activationMode(com.vingame.bot.domain.botgroup.model.ActivationMode.SCHEDULED)
                .build();

        assertThatThrownBy(() -> service.validate(group))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("activationWindow is required");

        verify(gameService, never()).findById(org.mockito.ArgumentMatchers.anyString());
        verify(validatorFactory, never()).forType(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("valid SCHEDULED window passes the activation seam and proceeds to the game-type validator")
    void validScheduledWindowProceedsToValidator() {
        BotGroup group = BotGroup.builder()
                .gameId("g1")
                .activationMode(com.vingame.bot.domain.botgroup.model.ActivationMode.SCHEDULED)
                .activationWindow(com.vingame.bot.domain.botgroup.model.ActivationWindow.builder()
                        .from(java.time.LocalTime.of(18, 0))
                        .to(java.time.LocalTime.of(23, 0))
                        .build())
                .build();
        Game game = Game.builder().id("g1").gameType(GameType.BETTING_MINI).build();
        when(gameService.findById("g1")).thenReturn(game);
        when(validatorFactory.forType(GameType.BETTING_MINI)).thenReturn(validator);

        service.validate(group);

        verify(validator).validate(group);
    }

    @Test
    @DisplayName("game with no gameType throws BadRequestException")
    void noGameTypeThrows() {
        BotGroup group = BotGroup.builder().gameId("g1").build();
        Game game = Game.builder().id("g1").gameType(null).build();
        when(gameService.findById("g1")).thenReturn(game);

        assertThatThrownBy(() -> service.validate(group))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("no gameType");
    }
}
