package com.vingame.bot.plugin.it;

import com.vingame.bot.infrastructure.plugin.IsolatedPluginBundleLoader;
import com.vingame.bot.infrastructure.plugin.PluginRegistries;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PLUGIN_HOT_RELOAD_3_4 Phase 4b step 3, the D-10 / D-11 selection tests, against copies
 * of the shipped jars built under {@code target/it-fixtures}:
 * <ul>
 *   <li>the greatest valid version wins;</li>
 *   <li>an {@code _}- or {@code .}-prefixed directory is ignored (and not reported);</li>
 *   <li>mismatched jar versions are rejected, and the next candidate is used;</li>
 *   <li>a duplicate-key bundle is rejected as a whole, and the next candidate is used;</li>
 *   <li>the builtin directory is used when the mount is empty, with one WARN;</li>
 *   <li>no valid candidate anywhere fails, naming both directories.</li>
 * </ul>
 * Each rejection is exactly one ERROR line, {@code plugin bundle <path> rejected: <reason>}.
 */
@DisplayName("D-11 selection: version order, disabled dirs, rejection and fallback")
class IsolatedBundleSelectionIT {

    private LogCapture log;

    @BeforeEach
    void captureLoaderLog() {
        log = new LogCapture(IsolatedPluginBundleLoader.class.getName(), Level.ALL);
    }

    @AfterEach
    void releaseLog() {
        log.close();
    }

    @Test
    @DisplayName("the greatest valid version wins")
    void greatestValidVersionWins() throws Exception {
        Path mount = BundleFixtures.freshDir("greatest-wins");
        BundleFixtures.shippedCopy(mount, "20990101.000000");
        Path newest = BundleFixtures.shippedCopy(mount, "20990102.000000");
        // The directory name is cosmetic (D-7): the manifest version decides, so a directory
        // that sorts first by name but carries an older version must lose.
        BundleFixtures.shippedCopy(mount, "zz-older-by-manifest", "20981231.000000", "20981231.000000");

        PluginRegistries registries = load(mount, mount.resolve("_none"));
        try {
            assertThat(registries.bundle().version()).isEqualTo("20990102.000000");
            assertThat(registries.bundle().source()).isEqualTo(newest.toString());
            assertThat(registries.bundle().jars()).extracting(j -> j.name())
                    .containsExactly("bot-messages-1.0.jar", "bot-strategies-1.0.jar");
            assertThat(registries.bundle().jars()).allSatisfy(j -> assertThat(j.sha256()).hasSize(64));
            assertThat(log.events()).isEmpty();
        } finally {
            registries.bundle().close();
        }
    }

    @Test
    @DisplayName("`_` and `.` prefixed directories are ignored, even when newer, and are not reported")
    void disabledDirectoriesAreIgnored() throws Exception {
        Path mount = BundleFixtures.freshDir("disabled-dirs");
        BundleFixtures.shippedCopy(mount, "20990101.000000");
        BundleFixtures.shippedCopy(mount, "_20991231.000000", "20991231.000000", "20991231.000000");
        BundleFixtures.shippedCopy(mount, ".20991230.000000", "20991230.000000", "20991230.000000");

        PluginRegistries registries = load(mount, mount.resolve("_none"));
        try {
            assertThat(registries.bundle().version()).isEqualTo("20990101.000000");
            assertThat(log.events()).as("an operator's disable switch is not an error").isEmpty();
        } finally {
            registries.bundle().close();
        }
    }

    @Test
    @DisplayName("a bundle whose jars carry different versions is rejected with one ERROR; the next one is used")
    void mismatchedJarVersionsAreRejected() throws Exception {
        Path mount = BundleFixtures.freshDir("mismatched");
        BundleFixtures.shippedCopy(mount, "20990101.000000");
        Path mismatched = BundleFixtures.shippedCopy(mount, "20990103.000000",
                "20990103.000000", "20990104.000000");

        PluginRegistries registries = load(mount, mount.resolve("_none"));
        try {
            assertThat(registries.bundle().version()).isEqualTo("20990101.000000");
            assertThat(log.messages(Level.ERROR)).singleElement().asString()
                    .startsWith("plugin bundle " + mismatched + " rejected: ")
                    .contains("different Bot-Plugin-Version values")
                    .contains("bot-strategies-1.0.jar=20990103.000000", "bot-messages-1.0.jar=20990104.000000");
        } finally {
            registries.bundle().close();
        }
    }

    @Test
    @DisplayName("a duplicate-key bundle is rejected as a whole with one ERROR; the next candidate is used")
    void duplicateKeyBundleIsRejected() throws Exception {
        Path mount = BundleFixtures.freshDir("duplicate-key");
        BundleFixtures.shippedCopy(mount, "20990101.000000");
        Path duplicate = BundleFixtures.shippedCopy(mount, "20990105.000000");
        BundleFixtures.compiledJar(duplicate.resolve("bot-dup-1.0.jar"), "20990105.000000",
                BundleFixtures.DUPLICATE_RANDOM);

        PluginRegistries registries = load(mount, mount.resolve("_none"));
        try {
            assertThat(registries.bundle().version()).isEqualTo("20990101.000000");
            assertThat(registries.bettingStrategies().create("RANDOM").getClass().getSimpleName())
                    .isEqualTo("RandomBehaviorStrategy");
            assertThat(log.messages(Level.ERROR)).singleElement().asString()
                    .startsWith("plugin bundle " + duplicate + " rejected: ")
                    .contains("Duplicate @StrategyImpl(RANDOM)");
        } finally {
            registries.bundle().close();
        }
    }

    @Test
    @DisplayName("with nothing valid mounted, the builtin bundle runs, with one WARN naming the mount and the version")
    void builtinIsUsedWhenTheMountIsEmpty() throws Exception {
        Path root = BundleFixtures.freshDir("builtin-fallback");
        Path mount = Files.createDirectories(root.resolve("plugins"));
        Path builtin = Files.createDirectories(root.resolve("plugins-builtin"));
        Path baked = BundleFixtures.shippedCopy(builtin, "20990101.000000");

        PluginRegistries registries = load(mount, builtin);
        try {
            assertThat(registries.bundle().source()).isEqualTo(baked.toString());
            assertThat(log.messages(Level.WARN)).singleElement().asString()
                    .contains(mount.toString())
                    .contains("running the image's built-in bundle 20990101.000000");
            assertThat(log.messages(Level.ERROR)).isEmpty();
        } finally {
            registries.bundle().close();
        }
    }

    @Test
    @DisplayName("an absent mount counts as empty: the builtin bundle runs")
    void absentMountCountsAsEmpty() throws Exception {
        Path root = BundleFixtures.freshDir("absent-mount");
        Path builtin = Files.createDirectories(root.resolve("plugins-builtin"));
        BundleFixtures.shippedCopy(builtin, "20990101.000000");

        PluginRegistries registries = load(root.resolve("never-created"), builtin);
        try {
            assertThat(registries.bundle().version()).isEqualTo("20990101.000000");
            assertThat(log.messages(Level.WARN)).hasSize(1);
        } finally {
            registries.bundle().close();
        }
    }

    @Test
    @DisplayName("no valid candidate in either directory fails startup naming both; each rejection is one ERROR")
    void noValidCandidateFails() throws Exception {
        Path root = BundleFixtures.freshDir("nothing-valid");
        Path mount = Files.createDirectories(root.resolve("plugins"));
        Path builtin = Files.createDirectories(root.resolve("plugins-builtin"));
        Files.createDirectories(mount.resolve("20990101.000000")); // no jars
        BundleFixtures.shippedCopy(builtin, "20990102.000000", "20990102.000000", "20990109.000000");

        assertThatThrownBy(() -> load(mount, builtin))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(mount.toString())
                .hasMessageContaining(builtin.toString());
        assertThat(log.messages(Level.ERROR)).hasSize(2)
                .anySatisfy(m -> assertThat(m).contains("rejected: no jars"))
                .anySatisfy(m -> assertThat(m).contains("different Bot-Plugin-Version values"));
        assertThat(log.messages(Level.WARN)).isEmpty();
    }

    private static PluginRegistries load(Path mount, Path builtin) {
        return new IsolatedPluginBundleLoader(mount, builtin).load();
    }
}
