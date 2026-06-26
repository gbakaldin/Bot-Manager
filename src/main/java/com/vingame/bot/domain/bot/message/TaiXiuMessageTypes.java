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
 * exactly the per-environment OFFSET arithmetic Tai Xiu must avoid. The two providers
 * have disjoint registration shapes and are resolved through separate
 * {@code GameMessageTypesResolver} methods.
 * <p>
 * <b>Per-provider CMD offset (TAI_XIU_114_JACKPOT plan AD-1).</b> The four base CMDs
 * resolved from the original captures are subscribe {@code 1005} (outbound), startGame
 * {@code 1002} (inbound), endGame {@code 1004} (inbound), bet {@code 1000} (outbound).
 * A second Tai Xiu product (P_114 / jackpot) uses the <b>same four CMDs +100</b>
 * (1105/1102/1104/1100). To support both without forking the request/registration
 * code, the four CMDs are <b>instance-resolved</b>: the base literals are kept as
 * {@code *_CMD_BASE} constants and the effective CMD is {@code base + cmdOffset()}.
 * {@link #cmdOffset()} defaults to {@code 0}, so the original product (P_116) is
 * byte-for-byte unchanged; the 114 provider overrides it to {@code 100}.
 * {@code UPDATE_BET_CMD} is <b>omitted in v1</b> — no updateBet frame was captured
 * (residual OI-5).
 */
public interface TaiXiuMessageTypes {

    // Base Tai Xiu CMD literals — no per-environment OFFSET arithmetic. The effective
    // CMD is base + cmdOffset() (AD-1); offset 0 (P_116) keeps these exact values.
    int SUBSCRIBE_CMD_BASE = 1005;   // outbound
    int START_GAME_CMD_BASE = 1002;  // inbound
    int END_GAME_CMD_BASE = 1004;    // inbound
    int BET_CMD_BASE = 1000;         // outbound-only (built by the request layer, AD-12)

    /**
     * Per-provider CMD offset applied to all four base CMDs (AD-1). Defaults to
     * {@code 0} (the captured P_116 product). The P_114 jackpot provider overrides
     * this to {@code 100}, yielding 1105/1102/1104/1100.
     *
     * @return the offset added to each base CMD
     */
    default int cmdOffset() {
        return 0;
    }

    /** @return the effective subscribe CMD ({@code SUBSCRIBE_CMD_BASE + cmdOffset()}, outbound). */
    default int subscribeCmd() {
        return SUBSCRIBE_CMD_BASE + cmdOffset();
    }

    /** @return the effective startGame CMD ({@code START_GAME_CMD_BASE + cmdOffset()}, inbound). */
    default int startGameCmd() {
        return START_GAME_CMD_BASE + cmdOffset();
    }

    /** @return the effective endGame CMD ({@code END_GAME_CMD_BASE + cmdOffset()}, inbound). */
    default int endGameCmd() {
        return END_GAME_CMD_BASE + cmdOffset();
    }

    /** @return the effective bet CMD ({@code BET_CMD_BASE + cmdOffset()}, outbound-only). */
    default int betCmd() {
        return BET_CMD_BASE + cmdOffset();
    }

    /**
     * @return Class for deserializing subscribe response messages ({@link #subscribeCmd()})
     */
    Class<? extends SubscribeMessage> subscribeType();

    /**
     * @return Class for deserializing start game messages (non-MD5, {@link #startGameCmd()})
     */
    Class<? extends StartGameMessage> startGameType();

    /**
     * @return Class for deserializing start game messages (MD5 variant), or
     *         {@code null} if the captured product has no md5 StartGame variant
     *         (AD-10). The effective {@link #startGameCmd()} is shared by both variants,
     *         so v1 does not register a separate md5 entry.
     */
    Class<? extends StartGameMd5Message> startGameMd5Type();

    /**
     * @return Class for deserializing bet update messages, or {@code null} in v1 —
     *         no updateBet frame was captured (OI-5), so updateBet is not registered.
     */
    Class<? extends UpdateBetMessage> updateBetType();

    /**
     * @return Class for deserializing end game messages ({@link #endGameCmd()})
     */
    Class<? extends EndGameMessage> endGameType();

    /**
     * Generate Jackson type registrations for polymorphic deserialization.
     * Registers each inbound class against its <b>effective</b> CMD string
     * ({@link #subscribeCmd()} / {@link #startGameCmd()} / {@link #endGameCmd()}) —
     * i.e. base + {@link #cmdOffset()} — so the P_116 provider registers against
     * {@code "1005"/"1002"/"1004"} and the P_114 provider against
     * {@code "1105"/"1102"/"1104"} (AD-1). No per-environment OFFSET is applied,
     * mirroring {@link SlotMessageTypes#getTypeRegistrations()}.
     * <p>
     * The bet ({@link #betCmd()}) is outbound-only and built by the request layer
     * (AD-12), so it is <b>not</b> registered as an inbound polymorphic subtype.
     * {@code updateBet} is omitted in v1 (uncaptured, OI-5).
     *
     * @return Array of NamedType registrations for ObjectMapper.registerSubtypes()
     */
    default NamedType[] getTypeRegistrations() {
        return new NamedType[] {
            new NamedType(subscribeType(), String.valueOf(subscribeCmd())),
            new NamedType(startGameType(), String.valueOf(startGameCmd())),
            new NamedType(endGameType(), String.valueOf(endGameCmd())),
        };
    }
}
