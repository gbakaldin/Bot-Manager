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
 * rather than handing out registries whose loader is gone. A classpath bundle's close is
 * a no-op, so in classpath mode none of this is reachable.
 */
public final class PluginRuntime implements AutoCloseable {

    private final PluginRegistries current;

    private volatile boolean unpublished;

    public PluginRuntime(PluginRegistries registries) {
        this.current = Objects.requireNonNull(registries, "registries");
        registries.bundle().onUnpublish(() -> unpublished = true);
    }

    /**
     * A runtime serving exactly these registries. The test helper D-9's plan step 7 asks
     * for ({@code BotFactory*} tests build registries by hand); production goes through
     * {@code PluginRuntimeConfiguration}.
     */
    public static PluginRuntime of(PluginRegistries registries) {
        return new PluginRuntime(registries);
    }

    /**
     * The registries new work is built from. Read it once per operation and take every
     * registry from the same value; never cache it in a field.
     *
     * @throws IllegalStateException once the bundle has been closed.
     */
    public PluginRegistries current() {
        if (unpublished) {
            throw new IllegalStateException("plugin bundle " + current.bundle().version()
                    + " has been closed — its registries are no longer published");
        }
        return current;
    }

    /** Close the bundle, in its D-13 order. Idempotent. */
    @Override
    public void close() {
        current.bundle().close();
    }
}
