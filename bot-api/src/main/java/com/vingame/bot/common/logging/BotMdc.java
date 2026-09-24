package com.vingame.bot.common.logging;

import org.slf4j.MDC;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    /**
     * The plugin version this bot's product implementation was loaded from
     * (PLUGIN_HOT_RELOAD AD-11) — {@code "builtin"} until step 4 introduces a child
     * classloader. Written by {@link #setPluginVersion(String)} rather than by
     * {@link #set}, so the eight-argument signature does not grow a ninth.
     * <p>
     * <b>Deliberately not on {@link #GROUP_LEVEL_KEYS}</b>: during a drain a group is
     * mixed-version — some of its bots on N, some on N+1 — so it is not a group-level
     * fact and an aggregated line must not claim one version for the whole group.
     * <p>
     * <b>Not on {@code BotMdcTagsMeterFilter}'s tag list either</b> (AD-5). It reaches
     * metrics through exactly one family, {@code bots_by_plugin_version}; putting it on
     * the filter would stamp it on every {@code bot_*} series, doubling the cardinality
     * of every bot counter during a drain and leaving a stale N-labelled copy of each
     * for Prometheus' full retention window.
     */
    public static final String PLUGIN_VERSION = "pluginVersion";

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
     * Set the {@link #PLUGIN_VERSION} key for the current thread.
     * <p>
     * A separate helper rather than a ninth parameter on {@link #set}: every caller of
     * that method would have to be touched to add an argument that is a constant today,
     * and the two call sites that need this ({@code Bot.initialize} and
     * {@code BotGroupRuntime.startBot}) already sit immediately after it.
     * <p>
     * A null or blank value is skipped, not written, for the reason spelled out on
     * {@link #setGroupContext(String, String, String)}: "absent" and "present and null"
     * are different documents in the JSON layout.
     */
    public static void setPluginVersion(String pluginVersion) {
        if (pluginVersion != null && !pluginVersion.isEmpty()) {
            MDC.put(PLUGIN_VERSION, pluginVersion);
        }
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

    /** Every key this class owns, i.e. exactly what {@link #clear()} removes. */
    private static final List<String> ALL_KEYS = List.of(
            BOT_GROUP_ID, BOT_ID, ENVIRONMENT_ID, PRODUCT, GAME_TYPE, GAME_ID, GAME_NAME,
            BOT_USER_NAME, PLUGIN_VERSION);

    /**
     * Clear all bot-related MDC keys.
     * <p>
     * <b>Use {@link #snapshot()} + {@link #restore(Map)} instead when the scope you are leaving
     * is nested inside another one.</b> {@code clear()} is absolute: a {@code finally} that calls
     * it drops whatever the caller had set, and every line emitted after it on that thread is
     * untagged — which is how the group context of an asynchronous start went missing from the
     * one ERROR line that reports the start's failure (GATEWAY_REQUEST_BUDGET Q3).
     */
    public static void clear() {
        ALL_KEYS.forEach(MDC::remove);
    }

    /**
     * The MDC keys this class owns that are currently set, for a scope that has to be restored
     * rather than cleared. Absent keys stay absent (see {@link #setGroupContext(String, String,
     * String)} on why "absent" and "present and null" are different).
     */
    public static Map<String, String> snapshot() {
        Map<String, String> saved = new LinkedHashMap<>();
        for (String key : ALL_KEYS) {
            String value = MDC.get(key);
            if (value != null) {
                saved.put(key, value);
            }
        }
        return saved;
    }

    /**
     * Put back exactly the context {@link #snapshot()} returned, dropping anything set in
     * between. {@code restore(snapshot())} around an inner scope is the nesting-safe form of
     * {@code clear()}.
     */
    public static void restore(Map<String, String> saved) {
        clear();
        if (saved != null) {
            saved.forEach(MDC::put);
        }
    }
}
