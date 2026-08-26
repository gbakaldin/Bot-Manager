package com.vingame.bot.common.plugin;

/**
 * The seam that answers "which plugin version is this JVM currently serving?"
 * (PLUGIN_HOT_RELOAD AD-11).
 * <p>
 * Phase 1 implements this as a constant returning {@link PluginVersions#BUILTIN}; step 4,
 * which introduces a child {@code ClassLoader} per plugin version, changes <b>this one
 * class</b> and nothing else moves. Callers must therefore treat the answer as
 * time-varying even though it cannot vary yet — read it per use, never cache it in a
 * field that outlives a reload.
 */
@FunctionalInterface
public interface PluginVersionResolver {

    /**
     * @return the identifier of the plugin version new bots are currently built from;
     *         never {@code null}.
     */
    String currentVersion();
}
