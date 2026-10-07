package com.vingame.bot.infrastructure.plugin;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.fasterxml.jackson.databind.util.LRUMap;
import com.fasterxml.jackson.databind.util.LookupCache;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One plugin bundle: the strategy and message-type implementations of a single version,
 * together with the loader that defined them and the Jackson type cache that resolves
 * them (PLUGIN_HOT_RELOAD_3_4 D-8, D-9, D-13).
 * <p>
 * A bundle is never consumed directly by engine or app code. {@link PluginRegistries#build}
 * turns it into the three registries, and {@link PluginRuntime} is the only root-held
 * reference to the result (D-9, L-10). Two implementations exist or will exist:
 * <ul>
 *   <li>{@link ClasspathPluginBundle} (4a): the plugin beans of the application context
 *       itself, version {@code builtin}, the application classloader;</li>
 *   <li>{@link IsolatedPluginBundle} (4b): a child {@code URLClassLoader} plus a parentless
 *       child context over the jars of one directory, chosen by
 *       {@link IsolatedPluginBundleLoader}.</li>
 * </ul>
 *
 * <h2>The bundle owns its Jackson {@link TypeFactory} (D-9, spike rule 2)</h2>
 * Every {@code new ObjectMapper()} shares {@code TypeFactory.defaultInstance()}, whose type
 * cache is a strong 200-entry LRU, so a plugin type resolved through any default mapper
 * stays reachable after the bot that resolved it is gone, and with it the plugin
 * classloader (spike scenario 3a). The bundle therefore carries one factory with a
 * <b>private</b> cache, exposed through {@link PluginRegistries#typeFactory()}, and every
 * per-bot mapper is built on it. {@code withClassLoader()} is not a substitute: it shares
 * the cache. Classpath mode uses one too, so the two modes resolve types identically.
 *
 * <h2>{@link #close()} order (spike rule 3), fixed now so step 6 inherits it</h2>
 * <ol>
 *   <li>unpublish the bundle's registries (the {@link #onUnpublish} hooks; today that is
 *       {@link PluginRuntime} refusing further {@code current()} calls);</li>
 *   <li>close the child context, whose {@code resetCommonCaches()} clears Spring's soft
 *       annotation caches. Dropping an unclosed context does not;</li>
 *   <li>clear this bundle's type cache and {@code TypeFactory.defaultInstance()}'s;</li>
 *   <li>close the classloader.</li>
 * </ol>
 * <b>Each step runs even if an earlier one threw — anything at all, {@link Error}s
 * included.</b> Step 2 runs plugin destroy callbacks, whose typical failure is a
 * {@code NoClassDefFoundError} / {@code LinkageError}, and stopping there would leave the
 * type caches full and the loader open with {@code closed} already latched, i.e. leaked
 * for the life of the JVM (review-4a). After the last step:
 * <ul>
 *   <li>if any step threw a {@link VirtualMachineError} (out of memory, stack overflow,
 *       an internal error), the first such error is rethrown <em>as is</em>, with every
 *       other failure suppressed on it — a fatal VM condition must not be disguised as
 *       a close failure;</li>
 *   <li>otherwise the first failure is rethrown wrapped in an
 *       {@link IllegalStateException} naming the bundle and the step, with the later
 *       ones (each wrapped the same way) suppressed on it.</li>
 * </ul>
 * At step 4 {@code close()} runs only for a rejected
 * candidate and at JVM shutdown. {@link ClasspathPluginBundle} overrides it with a no-op,
 * because it owns neither its context nor its loader.
 */
public abstract class PluginBundle implements AutoCloseable {

    /**
     * Initial capacity and size bound of the bundle's private type cache. The same values
     * as Jackson's own default cache ({@code TypeFactory.DEFAULT_MAX_CACHE_SIZE} = 200), so
     * resolution behaves exactly as it did on the shared one, and what the spike measured
     * (scenario 3b).
     */
    static final int TYPE_CACHE_INITIAL_ENTRIES = 16;
    static final int TYPE_CACHE_MAX_ENTRIES = 200;

    /**
     * {@code defaultInstance().withCache(new LRUMap<>(16, 200))}, as D-9 writes it. The
     * argument is typed as {@link LookupCache} only to select the non-deprecated
     * {@code withCache} overload; the cache is the same {@link LRUMap}.
     */
    private final TypeFactory typeFactory = TypeFactory.defaultInstance()
            .withCache((LookupCache<Object, JavaType>)
                    new LRUMap<Object, JavaType>(TYPE_CACHE_INITIAL_ENTRIES, TYPE_CACHE_MAX_ENTRIES));

    private final List<Runnable> unpublishHooks = new CopyOnWriteArrayList<>();

    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * @return the bundle version: {@code builtin} in classpath mode, otherwise the
     *         {@code Bot-Plugin-Version} every jar in the bundle carries (D-7).
     */
    public abstract String version();

    /**
     * @return where the bundle came from, as the boot line prints it after
     *         {@code source=}: {@code classpath}, or the bundle directory.
     */
    public abstract String source();

    /** @return the loader that defined the bundle's classes. */
    public abstract ClassLoader classLoader();

    /** @return the bundle's jars, in a stable order; empty in classpath mode. */
    public abstract List<PluginJar> jars();

    /**
     * Every bean of the bundle assignable to {@code type}, in the order Spring would inject
     * a {@code List<T>} of them.
     */
    public abstract <T> List<T> beansOfType(Class<T> type);

    /**
     * A bean of the bundle's context by class. For a prototype-scoped strategy that is a
     * fresh instance per call, which is what {@code BettingStrategyFactory.create} relies on.
     */
    public abstract <T> T newInstance(Class<T> type);

    /**
     * Whether the bundle's classes live in a loader of their own (isolated mode, 4b).
     * {@code BotFactory} reads it to make the shared Netty group mandatory for that bundle's
     * bots (L-11): a WebSocket client that fell back to ws-parser's private group would
     * start platform threads under a plugin frame, which pins the loader (spike 7b).
     */
    public boolean isolated() {
        return false;
    }

    /** The bundle's Jackson type factory, with its private cache (D-9). */
    public final TypeFactory typeFactory() {
        return typeFactory;
    }

    /**
     * Register something to run as step 1 of {@link #close()}: whatever published this
     * bundle's registries withdraws them. Hooks run in registration order.
     */
    public final void onUnpublish(Runnable hook) {
        unpublishHooks.add(Objects.requireNonNull(hook, "hook"));
    }

    /** @return whether {@link #close()} has run. */
    public final boolean isClosed() {
        return closed.get();
    }

    /**
     * Release the bundle in D-13's order. Idempotent: a second call does nothing.
     *
     * @throws IllegalStateException carrying the first step failure, if any step threw
     *                               and none of the failures was fatal.
     * @throws VirtualMachineError   the first fatal VM error a step threw, after every
     *                               remaining step has run.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        Failures failures = new Failures();
        // 1. Unpublish: nothing may hand out this bundle's registries any more.
        for (Runnable hook : unpublishHooks) {
            runStep("unpublish", hook::run, failures);
        }
        // 2. The child context. Its close() runs resetCommonCaches().
        runStep("close context", this::closeContext, failures);
        // 3. Both type caches. The default one is cleared too, because a plugin type can
        //    reach it through any mapper that was not built on this bundle's factory.
        runStep("clear type caches", () -> {
            typeFactory.clearCache();
            TypeFactory.defaultInstance().clearCache();
        }, failures);
        // 4. The classloader, last: closing it first would leave steps 2-3 unable to load
        //    a class they need.
        runStep("close classloader", this::closeClassLoader, failures);
        failures.rethrow();
    }

    /** Step 2 of {@link #close()}: close the bundle's own context. */
    protected abstract void closeContext() throws Exception;

    /** Step 4 of {@link #close()}: close the bundle's own classloader. */
    protected abstract void closeClassLoader() throws Exception;

    @FunctionalInterface
    private interface Step {
        void run() throws Exception;
    }

    private void runStep(String name, Step step, Failures failures) {
        try {
            step.run();
        } catch (Throwable t) {
            // Throwable, not Exception: see the class javadoc. Nothing is rethrown here, so
            // the remaining steps always run; Failures decides what escapes at the end.
            failures.add(name, t);
        }
    }

    /** What the close steps threw, and what {@link #close()} rethrows after the last one. */
    private final class Failures {
        private IllegalStateException first;
        private VirtualMachineError fatal;
        private final List<Throwable> all = new ArrayList<>();

        void add(String step, Throwable thrown) {
            IllegalStateException wrapped = new IllegalStateException(
                    "plugin bundle " + version() + ": " + step + " failed", thrown);
            if (first == null) {
                first = wrapped;
            }
            if (fatal == null && thrown instanceof VirtualMachineError vmError) {
                fatal = vmError;
            }
            all.add(thrown instanceof VirtualMachineError ? thrown : wrapped);
        }

        void rethrow() {
            if (fatal != null) {
                for (Throwable other : all) {
                    if (other != fatal) {
                        fatal.addSuppressed(other);
                    }
                }
                throw fatal;
            }
            if (first != null) {
                for (Throwable other : all) {
                    if (other != first) {
                        first.addSuppressed(other);
                    }
                }
                throw first;
            }
        }
    }
}
