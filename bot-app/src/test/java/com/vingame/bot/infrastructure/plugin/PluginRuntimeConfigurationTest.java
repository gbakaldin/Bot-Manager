package com.vingame.bot.infrastructure.plugin;

import com.vingame.bot.common.plugin.PluginVersionResolver;
import com.vingame.bot.domain.bot.strategy.RandomBehaviorStrategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PLUGIN_HOT_RELOAD_3_4 Phase 4a step 4 / D-12: {@code bot.plugins.mode} selects the bundle,
 * only {@code classpath} exists in 4a, and anything else fails startup instead of
 * silently picking a loader.
 */
@DisplayName("PluginRuntimeConfiguration — bot.plugins.mode (4a, D-12)")
class PluginRuntimeConfigurationTest {

    private final PluginRuntimeConfiguration configuration = new PluginRuntimeConfiguration();
    private AnnotationConfigApplicationContext context;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigApplicationContext();
        context.registerBean(RandomBehaviorStrategy.class);
        context.refresh();
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    @DisplayName("classpath mode publishes the context's plugin beans as the builtin bundle")
    void classpathModeBuildsTheClasspathBundle() {
        PluginRuntime runtime = configuration.pluginRuntime(context, "classpath");

        assertThat(runtime.current().bundle()).isInstanceOf(ClasspathPluginBundle.class);
        assertThat(runtime.current().bettingStrategies().registeredKeys()).containsExactly("RANDOM");
    }

    @Test
    @DisplayName("the mode is matched ignoring case and surrounding blanks (an env var is still `classpath`)")
    void modeIsCaseAndBlankInsensitive() {
        assertThat(configuration.pluginRuntime(context, " CLASSPATH ").current().bundle())
                .isInstanceOf(ClasspathPluginBundle.class);
    }

    @Test
    @DisplayName("an unknown mode fails startup and names the accepted ones")
    void unknownModeFailsStartup() {
        assertThatThrownBy(() -> configuration.pluginRuntime(context, "isolatd"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Unknown bot.plugins.mode 'isolatd' — expected one of [classpath]");
    }

    @Test
    @DisplayName("`isolated` is not accepted in 4a — it arrives with the loader in 4b")
    void isolatedIsNotYetAMode() {
        assertThatThrownBy(() -> configuration.pluginRuntime(context, "isolated"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unknown bot.plugins.mode 'isolated'");
    }

    @Test
    @DisplayName("the version resolver answers from the runtime's bundle, per call")
    void versionResolverReadsTheBundle() {
        PluginRuntime runtime = configuration.pluginRuntime(context, "classpath");

        PluginVersionResolver resolver = configuration.pluginVersionResolver(runtime);

        assertThat(resolver.currentVersion()).isEqualTo("builtin");
    }

    @Test
    @DisplayName("a rejected bundle whose close() also fails keeps the rejection reason; the close failure is suppressed (review-4a)")
    void closeFailureDoesNotHideTheRejection() {
        IllegalStateException rejected = new IllegalStateException("Duplicate @StrategyImpl(RANDOM)");
        PluginBundle unclosable = new TestPluginRuntimes.InertBundle("20261007.101500") {
            @Override
            protected void closeContext() {
                throw new IllegalStateException("context refused to close");
            }
        };

        PluginRuntimeConfiguration.closeAfterRejection(unclosable, rejected);

        assertThat(rejected).hasMessage("Duplicate @StrategyImpl(RANDOM)");
        assertThat(rejected.getSuppressed()).hasSize(1);
        assertThat(rejected.getSuppressed()[0]).hasMessageContaining("close context failed")
                .hasRootCauseMessage("context refused to close");
        assertThat(unclosable.isClosed()).isTrue();
    }
}
