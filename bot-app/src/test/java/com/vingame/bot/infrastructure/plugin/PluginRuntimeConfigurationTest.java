package com.vingame.bot.infrastructure.plugin;

import com.vingame.bot.common.plugin.PluginVersionResolver;
import com.vingame.bot.config.NettyEventLoopConfig;
import com.vingame.bot.domain.bot.strategy.RandomBehaviorStrategy;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * PLUGIN_HOT_RELOAD_3_4 Phase 4a step 4 / 4b step 2 / D-12: {@code bot.plugins.mode} selects
 * the bundle — {@code classpath} or {@code isolated} — and anything else fails startup
 * instead of silently picking a loader.
 * <p>
 * The isolated path proper (selection, loading, rejection) is exercised against the real
 * shipped jars by the ITs in {@code bot-plugin-dist}, whose classpath has no plugin classes;
 * this module's test classpath has them, so here isolated mode is driven only up to the
 * points that do not need a loadable bundle.
 */
@DisplayName("PluginRuntimeConfiguration — bot.plugins.mode (4a/4b, D-12)")
class PluginRuntimeConfigurationTest {

    private final PluginRuntimeConfiguration configuration = new PluginRuntimeConfiguration();
    private final EventLoopGroup unusedGroup = mock(EventLoopGroup.class);
    private AnnotationConfigApplicationContext context;

    @TempDir
    Path tmp;

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

    private PluginRuntime classpath(String mode) {
        return configuration.buildRuntime(context, mode, "/nonexistent", "/nonexistent", unusedGroup);
    }

    @Test
    @DisplayName("classpath mode publishes the context's plugin beans as the builtin bundle")
    void classpathModeBuildsTheClasspathBundle() {
        PluginRuntime runtime = classpath("classpath");

        assertThat(runtime.current().bundle()).isInstanceOf(ClasspathPluginBundle.class);
        assertThat(runtime.current().bundle().isolated()).isFalse();
        assertThat(runtime.current().bettingStrategies().registeredKeys()).containsExactly("RANDOM");
    }

    @Test
    @DisplayName("the mode is matched ignoring case and surrounding blanks (an env var is still `classpath`)")
    void modeIsCaseAndBlankInsensitive() {
        assertThat(classpath(" CLASSPATH ").current().bundle())
                .isInstanceOf(ClasspathPluginBundle.class);
    }

    @Test
    @DisplayName("an unknown mode fails startup and names the accepted ones")
    void unknownModeFailsStartup() {
        assertThatThrownBy(() -> classpath("isolatd"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Unknown bot.plugins.mode 'isolatd' — expected one of [classpath, isolated]");
    }

    @Test
    @DisplayName("`isolated` is a mode since 4b: with no bundle in either directory it fails startup naming both")
    void isolatedWithNoBundleFailsNamingBothDirectories() throws Exception {
        Path mounted = Files.createDirectories(tmp.resolve("plugins"));
        Path builtin = tmp.resolve("plugins-builtin"); // absent: counts as empty
        EventLoopGroup group = startedGroup();
        try {
            assertThatThrownBy(() -> configuration.buildRuntime(context, " Isolated ",
                    mounted.toString(), builtin.toString(), group))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("no valid plugin bundle in " + mounted)
                    .hasMessageContaining(builtin.toString());
        } finally {
            group.shutdownGracefully().syncUninterruptibly();
        }
    }

    @Test
    @DisplayName("isolated mode refuses to load anything while the shared Netty group is not fully started (L-11)")
    void isolatedRefusesAColdEventLoopGroup() {
        EventLoopGroup cold = new MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory());
        try {
            assertThatThrownBy(() -> configuration.buildRuntime(context, "isolated",
                    tmp.toString(), tmp.toString(), cold))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("0/2 executors started");
            assertThat(NettyEventLoopConfig.startedExecutorCount(cold))
                    .as("the check itself must not start the threads it is counting")
                    .isZero();
        } finally {
            cold.shutdownGracefully().syncUninterruptibly();
        }
    }

    @Test
    @DisplayName("isolated mode refuses to run in a context without the shared Netty group (L-11)")
    void isolatedRequiresTheGroup() {
        assertThatThrownBy(() -> configuration.buildRuntime(context, "isolated",
                tmp.toString(), tmp.toString(), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("needs the shared Netty EventLoopGroup bean");
    }

    @Test
    @DisplayName("classpath mode starts in a context without the shared Netty group (web-slice tests)")
    void classpathModeTolerantOfNoGroup() {
        assertThat(configuration.buildRuntime(context, "classpath", "/nonexistent", "/nonexistent", null)
                .current().bundle()).isInstanceOf(ClasspathPluginBundle.class);
    }

    @Test
    @DisplayName("the version resolver answers from the runtime's bundle, per call")
    void versionResolverReadsTheBundle() {
        PluginRuntime runtime = classpath("classpath");

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

    private static EventLoopGroup startedGroup() {
        EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        NettyEventLoopConfig.prestart(group);
        return group;
    }
}
