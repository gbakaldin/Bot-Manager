package com.vingame.bot.plugin.it;

import com.vingame.bot.infrastructure.plugin.PluginBundle;

import java.lang.ref.WeakReference;
import java.nio.file.Path;

/**
 * PLUGIN_HOT_RELOAD_3_4 <b>L-15</b>: L-14's flow with Spring Boot's real
 * {@code LaunchedClassLoader} as the parent, on Linux Temurin — the two caveats the spike
 * could not close (flat classpath, macOS). Shipped in 4b's test sources and <b>run from
 * 4c</b> (plan 4c step 7), because only the 4c fat jar is free of the plugin jars; against a
 * pre-4c fat jar the loader rejects every bundle as not isolated, by design.
 * <pre>
 * docker run --rm -v "$PWD":/w -w /w eclipse-temurin:21-jre java \
 *   -Dloader.path=bot-plugin-dist/target/test-classes \
 *   -Dloader.main=com.vingame.bot.plugin.it.BootParentReclaimMain \
 *   -cp bot-app/target/Bot-1.0.jar org.springframework.boot.loader.launch.PropertiesLauncher \
 *   bot-plugin-dist/target/plugins-dist
 * </pre>
 * Prints the parent loader, then {@code RECLAIMED} (or {@code NOT RECLAIMED}) for the clean
 * flow and {@code PINNED} (or {@code NOT PINNED}) for the negative control, which runs last
 * because its pin is permanent. Exit code 0 only for {@code RECLAIMED} + {@code PINNED}.
 */
public final class BootParentReclaimMain {

    private BootParentReclaimMain() {
    }

    public static void main(String[] args) throws Exception {
        Path dist = Path.of(args.length > 0 ? args[0] : "bot-plugin-dist/target/plugins-dist");
        System.out.println("parent loader: " + PluginBundle.class.getClassLoader());

        WeakReference<ClassLoader> clean = ReclaimRehearsal.cycle(dist, false);
        boolean reclaimed = ReclaimRehearsal.collected(clean);
        System.out.println(reclaimed ? "RECLAIMED" : "NOT RECLAIMED");

        WeakReference<ClassLoader> leaked = ReclaimRehearsal.cycle(dist, true);
        boolean pinned = !ReclaimRehearsal.collected(leaked);
        System.out.println(pinned ? "PINNED" : "NOT PINNED");

        System.exit(reclaimed && pinned ? 0 : 1);
    }
}
