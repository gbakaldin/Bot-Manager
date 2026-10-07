package com.vingame.bot.infrastructure.plugin;

/**
 * {@link PluginRuntime#current()} was asked for registries after their bundle closed
 * (PLUGIN_HOT_RELOAD_3_4, review-4a).
 * <p>
 * At step 4 a published bundle closes only at JVM shutdown, so this means "the
 * application is shutting down" — not a defect in the caller and not a plugin
 * misconfiguration. It has its own type so each caller can say so instead of reporting
 * a raw {@link IllegalStateException}:
 * <ul>
 *   <li>the REST layer answers {@code 503 Service Unavailable}, not a 500
 *       ({@code RestExceptionHandler});</li>
 *   <li>a bot build on a bot-creation thread is still counted as a creation failure, under
 *       its own bounded reason {@code shutdown}, with one WARN line naming the cause and
 *       no stack trace ({@code BotGroupBehaviorService}).</li>
 * </ul>
 * It extends {@link IllegalStateException} so code written against the 4a contract
 * ("{@code current()} throws {@code IllegalStateException} once closed") keeps working.
 */
public class PluginUnpublishedException extends IllegalStateException {

    private final String version;

    public PluginUnpublishedException(String version) {
        super("plugin bundle " + version + " has been closed — its registries are no longer "
                + "published (the application is shutting down)");
        this.version = version;
    }

    /** @return the version of the bundle that was closed. */
    public String getVersion() {
        return version;
    }
}
