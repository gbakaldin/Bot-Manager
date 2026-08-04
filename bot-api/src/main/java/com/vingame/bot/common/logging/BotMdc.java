package com.vingame.bot.common.logging;

import org.slf4j.MDC;

/**
 * Utility for managing bot-specific MDC (Mapped Diagnostic Context) keys.
 * <p>
 * MDC values are stored in ThreadLocal and automatically included in
 * every log statement. For JSON output, they appear as top-level fields.
 * <p>
 * Virtual thread safety: Log4j2's ThreadContext (backing SLF4J MDC)
 * uses ThreadLocal, which works correctly with virtual threads since
 * each virtual thread gets its own ThreadLocal storage.
 */
public final class BotMdc {

    public static final String BOT_GROUP_ID = "botGroupId";
    public static final String BOT_ID = "botId";
    public static final String ENVIRONMENT_ID = "environmentId";
    public static final String GAME_TYPE = "gameType";
    public static final String GAME_ID = "gameId";
    public static final String GAME_NAME = "gameName";
    public static final String BOT_USER_NAME = "botUserName";

    private BotMdc() {}

    /**
     * Set all bot-related MDC keys.
     * Call this at the start of a bot's thread execution.
     * <p>
     * Note on game keys (GRAFANA_PER_GAME_ENV_DASHBOARDS AD-1): {@code gameType}
     * carries the {@code GameType} enum (e.g. {@code SLOT}, {@code BETTING_MINI}),
     * {@code gameName} carries the readable display name (e.g. {@code BauCua}), and
     * {@code gameId} carries the Mongo {@code _id} UUID string (stable per-Game key,
     * NOT the numeric {@code Game.gameId} gid which collides across products — AD-8).
     */
    public static void set(String botGroupId, int botIndex,
                           String environmentId, String gameType,
                           String gameId, String gameName,
                           String userName) {
        MDC.put(BOT_GROUP_ID, botGroupId);
        MDC.put(BOT_ID, String.valueOf(botIndex));
        MDC.put(ENVIRONMENT_ID, environmentId);
        if (gameType != null) MDC.put(GAME_TYPE, gameType);
        if (gameId != null) MDC.put(GAME_ID, gameId);
        if (gameName != null) MDC.put(GAME_NAME, gameName);
        if (userName != null) MDC.put(BOT_USER_NAME, userName);
    }

    /**
     * Set partial MDC context for group-level operations where
     * individual bot identity is not yet known.
     */
    public static void setGroupContext(String botGroupId, String environmentId) {
        MDC.put(BOT_GROUP_ID, botGroupId);
        MDC.put(ENVIRONMENT_ID, environmentId);
    }

    /**
     * Clear all bot-related MDC keys.
     */
    public static void clear() {
        MDC.remove(BOT_GROUP_ID);
        MDC.remove(BOT_ID);
        MDC.remove(ENVIRONMENT_ID);
        MDC.remove(GAME_TYPE);
        MDC.remove(GAME_ID);
        MDC.remove(GAME_NAME);
        MDC.remove(BOT_USER_NAME);
    }
}
