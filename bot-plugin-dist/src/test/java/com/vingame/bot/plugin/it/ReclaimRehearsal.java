package com.vingame.bot.plugin.it;

import com.vingame.bot.infrastructure.plugin.IsolatedPluginBundleLoader;
import com.vingame.bot.infrastructure.plugin.PluginRegistries;
import com.vingame.bot.infrastructure.plugin.PluginRuntime;
import com.vingame.websocketparser.ObjectMapperProvider;

import java.lang.ref.WeakReference;
import java.nio.file.Path;
import java.util.Random;

/**
 * The step-6 rehearsal of L-14 / L-15 (PLUGIN_HOT_RELOAD_3_4): load the shipped bundle, use
 * it the way the engine does, drop every reference, close it in D-13's order, and see
 * whether its loader is collected. Shared by {@link PluginBundleReclaimIT} (flat parent, in
 * the failsafe JVM), {@link ReclaimControlMain} (the forked negative control) and
 * {@link BootParentReclaimMain} (Spring Boot's launcher as the parent, 4c).
 */
final class ReclaimRehearsal {

    /** The plan's bound: up to 20 × ({@code System.gc()} + 150 ms). */
    static final int GC_ATTEMPTS = 20;
    static final long GC_PAUSE_MILLIS = 150;

    private ReclaimRehearsal() {
    }

    /**
     * One load / use / close cycle. Everything strong stays inside this frame; only a weak
     * reference to the loader leaves it.
     *
     * @param dist the plugins-dist directory (one bundle subdirectory).
     * @param leak the negative control: also register the bundle's message subtypes on
     *             ws-parser's static {@code ObjectMapperProvider.getDefault()} — spike 3c, a
     *             pin nothing can undo, so it must only ever run in a JVM that ends right after.
     */
    static WeakReference<ClassLoader> cycle(Path dist, boolean leak) {
        PluginRegistries registries = new IsolatedPluginBundleLoader(dist, dist.resolve("_no-builtin")).load();
        PluginRuntime runtime = new PluginRuntime(registries);
        WeakReference<ClassLoader> loader = new WeakReference<>(registries.bundle().classLoader());

        // L-13's exercise, plus every strategy × decide / onRoundEnd.
        ShippedBundle.deserializeEveryRegistration(runtime.current());
        ShippedBundle.exerciseStrategies(runtime.current(), new Random(17));
        if (leak) {
            ObjectMapperProvider.getDefault().registerSubtypes(ShippedBundle.allRegistrations(runtime.current()));
        }

        // Unpublish, close the context, clear both type caches, close the loader (D-13).
        runtime.close();
        return loader;
    }

    /** Up to {@link #GC_ATTEMPTS} × ({@code System.gc()} + {@link #GC_PAUSE_MILLIS} ms). */
    static boolean collected(WeakReference<?> reference) throws InterruptedException {
        for (int i = 0; i < GC_ATTEMPTS; i++) {
            System.gc();
            if (reference.get() == null) {
                return true;
            }
            Thread.sleep(GC_PAUSE_MILLIS);
        }
        return reference.get() == null;
    }
}
