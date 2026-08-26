package com.vingame.bot.common.plugin;

/**
 * The catalogue of plugin-version identifiers, and the single source of truth for the
 * one that exists today (PLUGIN_HOT_RELOAD AD-11).
 * <p>
 * A "plugin version" names the classloader a bot's product implementation was loaded
 * from. Until step 4 of the hot-reload sequence introduces a child {@code ClassLoader}
 * there is exactly one — the application classloader — and every bot, every meter row
 * and every log line carries {@link #BUILTIN}. That is degenerate on purpose: the label,
 * the meters, the dashboard panel and the tests all exist and are proven <em>before</em>
 * the mechanism that can make the value interesting, because a leak detector shipped in
 * the same release as the leak proves nothing about the release (AD-1; the 2026-07/08
 * native-thread leak was diagnosable only because {@code jvm_threads_live_threads}
 * predated it).
 * <p>
 * <b>Cardinality bound to carry forward (AD-5):</b> this is 1 value today and steps 5–7
 * must hold it at <b>at most 2 concurrent values</b> (N and N+1). Step 7's forced
 * cutover deadline is the mechanism that enforces that — it is not a nice-to-have.
 */
public final class PluginVersions {

    /**
     * The version identifier for implementations loaded from the application
     * classloader, i.e. from the shipped jar. The only value in Phase 1.
     */
    public static final String BUILTIN = "builtin";

    private PluginVersions() {}
}
