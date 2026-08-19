package com.vingame.bot.common.logging;

import org.slf4j.MDC;

import java.util.List;

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
    /**
     * Numeric product code (VIPTALK_ALERTING_V2 AD-V1), e.g. {@code "116"} /
     * {@code "097"} — {@code ProductCode.getCode()}, not the enum name and not
     * the display name. Carried on MDC so every {@code bot_*} meter picks it up
     * as a Micrometer tag and Alertmanager can route by product. Adds no real
     * cardinality: {@code product} is functionally determined by
     * {@code environmentId} / {@code gameId}, both already on those series.
     */
    public static final String PRODUCT = "product";
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
     * <p>
     * {@code product} (VIPTALK_ALERTING_V2 AD-V1) carries the numeric product code
     * ({@code ProductCode.getCode()}); null is tolerated — older {@code Game}
     * documents may not have a {@code productCode} yet.
     */
    public static void set(String botGroupId, int botIndex,
                           String environmentId, String product, String gameType,
                           String gameId, String gameName,
                           String userName) {
        MDC.put(BOT_GROUP_ID, botGroupId);
        MDC.put(BOT_ID, String.valueOf(botIndex));
        MDC.put(ENVIRONMENT_ID, environmentId);
        if (product != null) MDC.put(PRODUCT, product);
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
        setGroupContext(botGroupId, environmentId, null);
    }

    /**
     * Set partial MDC context for group-level operations, additionally carrying the
     * numeric product code (VIPTALK_ALERTING_V2 AD-V1).
     * <p>
     * A null value is <b>skipped, not written</b>, for every key. "The key is absent" and
     * "the key is present and null" are different documents in the JSON layout and different
     * renderings in the console pattern, and which one a null produces also depends on the
     * active {@code ThreadContextMap} implementation. The environment-level rollup line
     * legitimately has no {@code botGroupId}, and it should carry no such field rather than
     * {@code "botGroupId": null}.
     */
    public static void setGroupContext(String botGroupId, String environmentId, String product) {
        if (botGroupId != null) MDC.put(BOT_GROUP_ID, botGroupId);
        if (environmentId != null) MDC.put(ENVIRONMENT_ID, environmentId);
        if (product != null) MDC.put(PRODUCT, product);
    }

    /**
     * The group-level MDC keys, in the order {@link #setGroupContext} writes them — the
     * subset of {@link #set}'s keys that describe a <em>group</em> rather than a bot.
     * Aggregated lines snapshot these and only these: a line about 47 bots must not carry
     * the {@code botId} / {@code botUserName} of whichever bot happened to contribute first.
     */
    public static final List<String> GROUP_LEVEL_KEYS =
            List.of(BOT_GROUP_ID, ENVIRONMENT_ID, PRODUCT, GAME_TYPE, GAME_ID, GAME_NAME);

    /**
     * Clear all bot-related MDC keys.
     */
    public static void clear() {
        MDC.remove(BOT_GROUP_ID);
        MDC.remove(BOT_ID);
        MDC.remove(ENVIRONMENT_ID);
        MDC.remove(PRODUCT);
        MDC.remove(GAME_TYPE);
        MDC.remove(GAME_ID);
        MDC.remove(GAME_NAME);
        MDC.remove(BOT_USER_NAME);
    }
}
