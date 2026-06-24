package com.vingame.bot.domain.bot.message;

import com.fasterxml.jackson.databind.jsontype.NamedType;

/**
 * Provider interface for TAI_XIU game message types.
 * <p>
 * Tai Xiu sits structurally <b>between</b> the betting-mini {@link GameMessageTypes}
 * and the {@link SlotMessageTypes} (TAI_XIU_BOT plan AD-3):
 * <ul>
 *   <li>Like {@link GameMessageTypes}, the concrete message <b>bodies vary per
 *       product</b>, so this interface keeps the same five typed accessors
 *       ({@code subscribeType}/{@code startGameType}/{@code startGameMd5Type}/
 *       {@code updateBetType}/{@code endGameType}) — each product supplies its own
 *       concrete classes.</li>
 *   <li>Like {@link SlotMessageTypes}, the <b>CMD is a fixed constant</b> across every
 *       environment (Tai Xiu is always a single game instance, so there is no
 *       per-env OFFSET). {@link #getTypeRegistrations()} therefore takes no offset and
 *       registers each inbound class against its literal fixed-CMD string.</li>
 * </ul>
 * This interface does <b>not</b> extend {@link GameMessageTypes}: the betting-mini
 * {@code getTypeRegistrations(int, boolean)} adds {@code CODE + offset}, which is
 * exactly the arithmetic Tai Xiu must avoid. The two providers have disjoint
 * registration shapes and are resolved through separate
 * {@code GameMessageTypesResolver} methods.
 * <p>
 * Fixed CMD constants resolved from captures (TAI_XIU_BOT plan AD-3, OI-2):
 * subscribe {@code 1005} (outbound), startGame {@code 1002} (inbound), endGame
 * {@code 1004} (inbound), bet {@code 1000} (outbound). {@code UPDATE_BET_CMD} is
 * <b>omitted in v1</b> — no updateBet frame was captured (residual OI-5).
 */
public interface TaiXiuMessageTypes {

    // Fixed Tai Xiu CMD constants — no CODE + offset arithmetic (AD-3).
    int SUBSCRIBE_CMD = 1005;   // outbound
    int START_GAME_CMD = 1002;  // inbound
    int END_GAME_CMD = 1004;    // inbound
    int BET_CMD = 1000;         // outbound-only (built by the request layer, AD-12)

    /**
     * @return Class for deserializing subscribe response messages (cmd 1005)
     */
    Class<? extends SubscribeMessage> subscribeType();

    /**
     * @return Class for deserializing start game messages (non-MD5, cmd 1002)
     */
    Class<? extends StartGameMessage> startGameType();

    /**
     * @return Class for deserializing start game messages (MD5 variant), or
     *         {@code null} if the captured product has no md5 StartGame variant
     *         (AD-10). The fixed {@code START_GAME_CMD} is shared by both variants,
     *         so v1 does not register a separate md5 entry.
     */
    Class<? extends StartGameMd5Message> startGameMd5Type();

    /**
     * @return Class for deserializing bet update messages, or {@code null} in v1 —
     *         no updateBet frame was captured (OI-5), so updateBet is not registered.
     */
    Class<? extends UpdateBetMessage> updateBetType();

    /**
     * @return Class for deserializing end game messages (cmd 1004)
     */
    Class<? extends EndGameMessage> endGameType();

    /**
     * Generate Jackson type registrations for polymorphic deserialization.
     * Registers each inbound class against its literal fixed CMD string
     * ({@code "1005"} / {@code "1002"} / {@code "1004"}) — no offset is applied
     * (AD-3), mirroring {@link SlotMessageTypes#getTypeRegistrations()}.
     * <p>
     * The bet ({@code 1000}) is outbound-only and built by the request layer
     * (AD-12), so it is <b>not</b> registered as an inbound polymorphic subtype.
     * {@code updateBet} is omitted in v1 (uncaptured, OI-5).
     *
     * @return Array of NamedType registrations for ObjectMapper.registerSubtypes()
     */
    default NamedType[] getTypeRegistrations() {
        return new NamedType[] {
            new NamedType(subscribeType(), String.valueOf(SUBSCRIBE_CMD)),
            new NamedType(startGameType(), String.valueOf(START_GAME_CMD)),
            new NamedType(endGameType(), String.valueOf(END_GAME_CMD)),
        };
    }
}
