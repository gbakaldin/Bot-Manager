package com.vingame.bot.common.plugin;

/**
 * The seam that answers "which plugin version is this JVM currently serving?"
 * (PLUGIN_HOT_RELOAD AD-11).
 * <p>
 * Phase 1 implemented this as a constant returning {@link PluginVersions#BUILTIN}. Since
 * PLUGIN_HOT_RELOAD_3_4 Phase 4a the bean is defined by {@code PluginRuntimeConfiguration}
 * and answers from {@code PluginRuntime.current()}'s bundle: still {@code builtin} in
 * classpath mode, the bundle's {@code Bot-Plugin-Version} once 4c loads it from a child
 * {@code ClassLoader}. Callers must treat the answer as time-varying — read it per use,
 * never cache it in a field that outlives a reload.
 */
@FunctionalInterface
public interface PluginVersionResolver {

    /**
     * @return the identifier of the plugin version new bots are currently built from;
     *         never {@code null}.
     */
    String currentVersion();
}
