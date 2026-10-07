package com.vingame.bot.plugin.it;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PLUGIN_HOT_RELOAD_3_4 <b>L-14</b>, the reclaim rehearsal. Step 4 never releases a loader,
 * so this is the proof that step 4 adds no new pin before step 6 starts relying on
 * release: load the shipped bundle, run L-13's exercise plus every strategy × {@code decide}
 * / {@code onRoundEnd}, drop every reference, close in D-13's order, then up to 20 ×
 * ({@code System.gc()} + 150 ms) — the loader's {@code WeakReference} must clear.
 * <p>
 * <b>Negative control</b>: the same flow plus {@code getDefault().registerSubtypes(...)}
 * must stay pinned (spike 3c). That pin is permanent, so the control runs in a <b>forked
 * JVM</b> ({@link ReclaimControlMain}) and this JVM never sees it. Strict (D-14): do not
 * weaken; {@code @Disabled} only with the user's sign-off.
 */
@DisplayName("L-14: a closed bundle's loader is collected; the static-mapper control stays pinned")
class PluginBundleReclaimIT {

    @Test
    @DisplayName("load, use, close in D-13 order: the plugin loader is collected")
    void closedBundleIsReclaimed() throws Exception {
        WeakReference<ClassLoader> loader = ReclaimRehearsal.cycle(ShippedBundle.distDir(), false);

        assertThat(ReclaimRehearsal.collected(loader))
                .as("something still holds the plugin loader after close() — a new pin step 6 would inherit")
                .isTrue();
    }

    @Test
    @DisplayName("negative control (forked JVM): registering on ObjectMapperProvider.getDefault() pins it")
    void staticMapperRegistrationPins() throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Process process = new ProcessBuilder(List.of(
                java.toString(),
                "-cp", System.getProperty("java.class.path"),
                ReclaimControlMain.class.getName(),
                ShippedBundle.distDir().toAbsolutePath().toString()))
                .redirectErrorStream(true)
                .start();
        String output = readAll(process);
        assertThat(process.waitFor(2, TimeUnit.MINUTES)).as("forked control finished").isTrue();

        assertThat(output).as("forked control output:%n%s", output).contains("PINNED");
        assertThat(output).doesNotContain("RECLAIMED");
        assertThat(process.exitValue()).as("forked control output:%n%s", output).isZero();
    }

    private static String readAll(Process process) throws IOException {
        return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
}
