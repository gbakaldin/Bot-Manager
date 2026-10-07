package com.vingame.bot.infrastructure.plugin;

import java.util.Objects;

/**
 * The one root-held reference to the plugin registries (PLUGIN_HOT_RELOAD_3_4 D-9, L-10).
 * <p>
 * <b>The rule this class exists to enforce:</b> root consumers ({@code BotFactory},
 * {@code BotGroupConfigValidationService}, {@code StrategyCatalog}, the
 * {@code PluginVersionResolver} bean) inject {@code PluginRuntime} and call
 * {@link #current()} <em>per operation</em>. None of them keeps a registry, a bundle or
 * the bundle's {@code TypeFactory} in a field; {@code RootContextHoldsNoPluginRefsTest}
 * fails the build if one does. A bot is the deliberate exception: it receives its
 * bundle's factories and type factory from {@code BotFactory} and keeps them for life,
 * which is exactly the drain semantics step 5 needs — a bot built on N keeps creating N
 * strategies until it is recycled through {@code BotFactory}.
 * <p>
 * <b>Step 4 holds one bundle, in a {@code final} field.</b> Step 5 adds version
 * selection here and changes no consumer; that is what the per-call read buys.
 * <p>
 * <b>Shutdown.</b> {@link #close()} closes the bundle, which Spring calls as the bean's
 * inferred destroy method. Step 1 of the bundle's close order is "unpublish", and this
 * class is what gets unpublished: once its bundle has closed, {@link #current()} throws
 * {@link PluginUnpublishedException} rather than handing out registries whose loader is
 * gone. A classpath bundle's close is a no-op, so in classpath mode none of this is
 * reachable.
 * <p>
 * <b>Two ways in, one answer</b> (review-4a). The unpublish hook flips a flag as step 1 of
 * the bundle's close, and {@link #current()} additionally asks the bundle itself
 * ({@link PluginBundle#isClosed()}, latched before step 1 runs). The second check is what
 * makes the hook's two blind spots harmless: a hook registered while {@code close()} is
 * already iterating its snapshot of the hook list is never run, and a hook registered
 * after {@code close()} finished is never run either. The constructor refuses an
 * already-closed bundle outright, so a runtime can never start out serving one.
 */
public final class PluginRuntime implements AutoCloseable {

    private final PluginRegistries current;

    private volatile boolean unpublished;

    /**
     * @throws IllegalArgumentException if the registries' bundle is already closed.
     */
    public PluginRuntime(PluginRegistries registries) {
        this.current = Objects.requireNonNull(registries, "registries");
        PluginBundle bundle = registries.bundle();
        if (bundle.isClosed()) {
            throw new IllegalArgumentException("plugin bundle " + bundle.version()
                    + " is already closed — a PluginRuntime cannot publish it");
        }
        bundle.onUnpublish(() -> unpublished = true);
    }

    /**
     * The registries new work is built from. Read it once per operation and take every
     * registry from the same value; never cache it in a field.
     *
     * @throws PluginUnpublishedException once the bundle has been closed.
     */
    public PluginRegistries current() {
        if (unpublished || current.bundle().isClosed()) {
            throw new PluginUnpublishedException(current.bundle().version());
        }
        return current;
    }

    /** Close the bundle, in its D-13 order. Idempotent. */
    @Override
    public void close() {
        current.bundle().close();
    }
}
