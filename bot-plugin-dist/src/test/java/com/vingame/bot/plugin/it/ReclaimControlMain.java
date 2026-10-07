package com.vingame.bot.plugin.it;

import java.lang.ref.WeakReference;
import java.nio.file.Path;

/**
 * L-14's negative control, run by {@link PluginBundleReclaimIT} in a forked JVM because
 * the pin it creates is permanent (spike 3c). Prints {@code PINNED} when the loader
 * survives — the expected outcome — or {@code RECLAIMED} when it does not, which would mean
 * the census in {@link ReclaimRehearsal#collected} cannot tell a pinned loader from a free
 * one. Exit code 0 when the run completed, whatever the outcome; the IT judges the output.
 */
public final class ReclaimControlMain {

    private ReclaimControlMain() {
    }

    public static void main(String[] args) throws Exception {
        WeakReference<ClassLoader> loader = ReclaimRehearsal.cycle(Path.of(args[0]), true);
        System.out.println(ReclaimRehearsal.collected(loader) ? "RECLAIMED" : "PINNED");
        System.exit(0);
    }
}
