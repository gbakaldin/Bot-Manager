package com.vingame.bot.infrastructure.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * PLUGIN_HOT_RELOAD_3_4 L-3, enforced at load time: a bundle whose classes are also on the
 * application classpath is <b>rejected</b>, not accepted and quietly not isolated.
 * <p>
 * This module's test classpath is exactly that situation — {@code bot-strategies} is a
 * test dependency — so loading the real {@code bot-strategies} jar in a child loader makes
 * parent-first delegation hand back the parent's copy of every strategy class. That is
 * also what an isolated start on a pre-4c fat jar (which still carries both plugin jars in
 * {@code BOOT-INF/lib}) would do, and the rejection message has to say so.
 * <p>
 * Needs the packaged jar ({@code ../bot-strategies/target/bot-strategies-1.0.jar}), which a
 * reactor {@code install}/{@code verify} builds before this module; a bare {@code mvn test}
 * skips.
 */
@DisplayName("IsolatedPluginBundle — a bundle shadowed by the application classpath is rejected (L-3)")
class IsolatedPluginBundleShadowingTest {

    @Test
    @DisplayName("plugin classes also on the parent classpath: rejected, the message names the cause, TCCL untouched")
    void shadowedBundleIsRejected() throws Exception {
        Path jar = Path.of("../bot-strategies/target/bot-strategies-1.0.jar");
        assumeTrue(Files.isRegularFile(jar), "bot-strategies is not packaged yet (bare `mvn test`)");
        ClassLoader tccl = Thread.currentThread().getContextClassLoader();

        assertThatThrownBy(() -> IsolatedPluginBundle.open(jar.getParent(), "20990101.000000",
                List.of(jar), List.of(new PluginJar(jar.getFileName().toString(), "0".repeat(64)))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("beans not defined by the plugin loader")
                .hasMessageContaining("randomBehaviorStrategy")
                .hasMessageContaining("also on the application classpath");

        assertThat(Thread.currentThread().getContextClassLoader()).isSameAs(tccl);
    }
}
