package com.vingame.bot.domain.bot.message;

import com.fasterxml.jackson.databind.jsontype.NamedType;
import com.vingame.bot.domain.game.model.Game;

/**
 * Provider interface for product-specific message types.
 * Each product implements this to supply its concrete message classes
 * for deserialization in the bot scenario pipeline.
 * <p>
 * Terminology:
 * - CODE: Message type identifier (e.g., 3000 for subscribe) - fixed per game type
 * - OFFSET: Game identifier (e.g., 2000 for BauCua, 8000 for TaiXiuSeven) - game-specific
 * - CMD: CODE + OFFSET (e.g., 3000 + 8000 = 11000) - actual value in JSON
 */
public interface GameMessageTypes {

    // Message codes for BettingMini game type
    int SUBSCRIBE_CODE = 3000;
    int UPDATE_BET_CODE = 3002;
    int START_GAME_CODE = 3005;
    int END_GAME_CODE = 3006;

    /**
     * Resolve the provider that serves <b>this particular game</b>.
     *
     * <h2>Why this exists — the game dimension the registry key does not have</h2>
     *
     * {@code @MessageTypesImpl} keys a provider on {@code (gameType, product)} and
     * nothing else, so one product gets exactly one betting-mini provider. That held
     * until P_114 / RIK turned out to run games whose <b>frames genuinely differ</b>
     * under the same product: {@code stockPlugin} (offset 10000) reports its own win
     * on {@code mbs[].wm}, while {@code ziczacPlugin} (Plinko, offset 9000) reports
     * per-ball results on {@code mbs[].r} and has no {@code wm} anywhere — so a
     * single provider claiming the whole product reports
     * {@code bot_winnings_total = 0} for one of them. Widening the registry key to
     * {@code (gameType, product, game)} would touch every provider, the registry and
     * five tests for one game, and it is entangled with the version axis that
     * {@code docs/plans/PLUGIN_HOT_RELOAD.md} Amendment A8 defers to step 5.
     * <p>
     * So the game enters as a <b>resolution parameter, not as a registry key</b>: the
     * registry still answers one bean per {@code (gameType, product)}, and that bean
     * decides — knowing its own product's games — whether it serves this game itself
     * or hands back a specialised sibling. Nothing is registered as a bean for the
     * sibling, so the registry's inventory, the boot line and the coverage tests are
     * unchanged, which is the point.
     *
     * <h2>Contract for implementers</h2>
     *
     * <ul>
     *   <li>The default is {@code this} — a provider that serves its whole product
     *       needs no override, and five of the six betting-mini providers do not have
     *       one.</li>
     *   <li>Dispatch on {@link Game#getPluginName()}, <b>case-insensitively</b>, and
     *       fall back to {@code this} for any unrecognised or {@code null} name. A
     *       misspelled plugin name cannot mis-route silently: the same string is what
     *       {@code Request.subscribe()} puts in the outbound frame's plugin slot, so
     *       a typo means the game never receives a round at all — a loud failure, not
     *       a quietly wrong message class.</li>
     *   <li>Returned providers must be stateless and safe to share; a
     *       {@code private static final} instance is the expected shape.</li>
     * </ul>
     *
     * <b>Call it at exactly one place.</b> {@code BotFactory}'s BETTING_MINI branch
     * is the only production call site of
     * {@code MessageTypesRegistry.bettingMini(...)}. A second call site that forgets
     * {@code forGame} silently falls back to the generic provider, which for ziczac
     * means winnings quietly return to zero.
     *
     * @param game the game the bot is about to play; never {@code null} at the
     *             production call site
     * @return the provider to use for this game — {@code this} unless the
     *         implementation specialises
     */
    default GameMessageTypes forGame(Game game) {
        return this;
    }

    /**
     * @return Class for deserializing subscribe response messages
     */
    Class<? extends SubscribeMessage> subscribeType();

    /**
     * @return Class for deserializing start game messages (non-MD5)
     */
    Class<? extends StartGameMessage> startGameType();

    /**
     * @return Class for deserializing start game messages (MD5 variant)
     */
    Class<? extends StartGameMd5Message> startGameMd5Type();

    /**
     * @return Class for deserializing bet update messages
     */
    Class<? extends UpdateBetMessage> updateBetType();

    /**
     * @return Class for deserializing end game messages
     */
    Class<? extends EndGameMessage> endGameType();

    /**
     * Generate Jackson type registrations for polymorphic deserialization.
     * CMD values are computed as CODE + offset.
     *
     * @param offset The game's offset (e.g., 2000 for BauCua, 8000 for TaiXiuSeven)
     * @param md5 Whether to use MD5 start game class
     * @return Array of NamedType registrations for ObjectMapper.registerSubtypes()
     */
    default NamedType[] getTypeRegistrations(int offset, boolean md5) {
        Class<? extends StartGameMessage> startClass = md5 ? startGameMd5Type() : startGameType();
        return new NamedType[] {
            new NamedType(subscribeType(), String.valueOf(SUBSCRIBE_CODE + offset)),
            new NamedType(updateBetType(), String.valueOf(UPDATE_BET_CODE + offset)),
            new NamedType(startClass, String.valueOf(START_GAME_CODE + offset)),
            new NamedType(endGameType(), String.valueOf(END_GAME_CODE + offset)),
        };
    }
}
