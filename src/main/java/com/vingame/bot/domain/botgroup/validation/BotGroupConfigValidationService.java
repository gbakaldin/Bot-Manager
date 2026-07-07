package com.vingame.bot.domain.botgroup.validation;

import com.vingame.bot.common.exception.BadRequestException;
import com.vingame.bot.common.exception.ResourceNotFoundException;
import com.vingame.bot.domain.botgroup.model.BotGroup;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.domain.game.model.GameType;
import com.vingame.bot.domain.game.service.GameService;
import org.springframework.stereotype.Service;

/**
 * Single integration seam for game-type-specific bot-group config validation
 * (AD-9). Both {@code BotGroupService.save} (create) and
 * {@code BotGroupService.update} (PATCH, post-merge) call {@link #validate}.
 *
 * <p>This keeps {@code BotGroupService} free of any {@link GameType} branching:
 * the orchestrator resolves the {@link Game}/{@link GameType} from the group's
 * {@code gameId}, selects the validator via {@link GameConfigValidatorFactory},
 * and invokes it.
 *
 * <p>A null/blank/unresolvable {@code gameId} is a universal required-field
 * failure surfaced as a {@link BadRequestException} (400) <b>before</b> a
 * validator is selected — the factory cannot pick one without a
 * {@link GameType}. {@link GameService#findById} throws a 404
 * {@link ResourceNotFoundException} on an unknown id; per AD-7 that is a client
 * error on a create body, so it is caught and rethrown as a 400.
 */
@Service
public class BotGroupConfigValidationService {

    private final GameConfigValidatorFactory validatorFactory;
    private final GameService gameService;

    public BotGroupConfigValidationService(GameConfigValidatorFactory validatorFactory,
                                           GameService gameService) {
        this.validatorFactory = validatorFactory;
        this.gameService = gameService;
    }

    /**
     * Resolve the group's {@link GameType} and run its validator.
     *
     * @param group the bot group to validate (post-merge on the PATCH path).
     * @throws BadRequestException if {@code gameId} is missing/unresolvable, or
     *         if the game-type validator rejects the configuration.
     */
    public void validate(BotGroup group) {
        // Activation config is game-type-independent (TIMED_ACTIVATION AD-7), so
        // it is checked here for every group rather than in a per-GameType
        // validator. A null/legacy activationMode passes through unchanged.
        ActivationRules.validate(group);

        String gameId = group.getGameId();
        if (gameId == null || gameId.isBlank()) {
            throw new BadRequestException("gameId is required");
        }

        Game game;
        try {
            game = gameService.findById(gameId);
        } catch (ResourceNotFoundException e) {
            // AD-7: an unknown gameId in a create/update body is a client error
            // (400), not a 404.
            throw new BadRequestException("Game not found: " + gameId, e);
        }

        GameType gameType = game.getGameType();
        if (gameType == null) {
            throw new BadRequestException("Game " + gameId + " has no gameType configured");
        }

        validatorFactory.forType(gameType).validate(group);
    }
}
