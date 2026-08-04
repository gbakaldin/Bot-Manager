package com.vingame.bot.domain.bot.message;

import com.fasterxml.jackson.databind.jsontype.NamedType;

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
