package com.vingame.bot.infrastructure.plugin;

import com.vingame.bot.common.plugin.PluginVersionResolver;
import com.vingame.bot.config.NettyEventLoopConfig;
import io.netty.channel.EventLoopGroup;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Builds the plugin bundle for {@code bot.plugins.mode}, the {@link PluginRuntime} that
 * publishes its registries, and the {@link PluginVersionResolver} that reports its version
 * (PLUGIN_HOT_RELOAD_3_4 Phases 4a/4b, D-9..D-13, D-15).
 * <p>
 * <b>Modes.</b>
 * <ul>
 *   <li>{@code classpath} (the default here and in {@code application.properties}): the
 *       bundle is the plugin beans of this application context
 *       ({@link ClasspathPluginBundle}), so nothing changes at runtime (D-1);</li>
 *   <li>{@code isolated} (4b): {@link IsolatedPluginBundleLoader} picks the first valid
 *       bundle from {@value #DIR_PROPERTY}, then {@value #BUILTIN_DIR_PROPERTY}, and loads it
 *       in a classloader of its own. Reachable only through the property; compose selects
 *       it from 4c.</li>
 * </ul>
 * Any other value fails startup rather than falling back, because a typo in a mode switch
 * that silently picks a different loader is the worst way to find out which one is
 * running. {@code isolated} must never be the default in {@code application.properties}:
 * every {@code @SpringBootTest} runs in classpath mode (D-12).
 * <p>
 * <b>The shared Netty group is a dependency, not a convention</b> (D-13, L-11, spike rule
 * 5). The {@link EventLoopGroup} parameter makes Spring build — and
 * {@code NettyEventLoopConfig} pre-start — the group before any bundle loads, so no
 * event-loop thread is ever first constructed under a plugin frame, where it would capture
 * the plugin loader in its inherited access-control context for life. In isolated mode the
 * pre-start is also <em>checked</em>: a group with an executor that has no live thread
 * fails startup.
 * <p>
 * <b>Rejection.</b> {@link PluginRegistries#build} is the single validation point (D-10).
 * In classpath mode a rejected bundle is a context-refresh failure, which is what keeps a
 * broken build from producing a startable artifact — the behaviour the {@code @Component}
 * registries had. In isolated mode the loader logs it and tries the next candidate.
 * <p>
 * <b>Registry lines.</b> The three "initialized" INFO lines are printed here, once, for the
 * accepted bundle ({@link PluginRegistries#logInitialized()}), so a boot that rejected a
 * candidate does not log a catalogue it is not running (review-4a).
 * <p>
 * <b>This class holds nothing.</b> The bundle and the registries are reachable only
 * through the {@link PluginRuntime} bean (L-10).
 */
@Configuration
public class PluginRuntimeConfiguration {

    /** The property that selects the bundle source. */
    public static final String MODE_PROPERTY = "bot.plugins.mode";

    /** Isolated mode's mounted bundle directory (D-11 step 1). */
    public static final String DIR_PROPERTY = "bot.plugins.dir";

    /** Isolated mode's image-baked bundle directory (D-11 step 2). */
    public static final String BUILTIN_DIR_PROPERTY = "bot.plugins.builtin-dir";

    static final String CLASSPATH = "classpath";
    static final String ISOLATED = "isolated";

    /** The modes this build accepts, in the order the startup error lists them. */
    static final List<String> MODES = List.of(CLASSPATH, ISOLATED);

    /**
     * @param context        the application context; in classpath mode, the bundle itself.
     * @param mode           {@value #MODE_PROPERTY}; case and surrounding blanks are ignored.
     * @param pluginsDir     {@value #DIR_PROPERTY}; read in isolated mode only.
     * @param builtinDir     {@value #BUILTIN_DIR_PROPERTY}; read in isolated mode only.
     * @param eventLoopGroup the shared Netty group. Resolved <b>first</b>, before any bundle
     *                       is opened, so it exists — pre-started by
     *                       {@code NettyEventLoopConfig} — before plugin code runs (D-13).
     *                       An {@link ObjectProvider} rather than the bean itself only so
     *                       that web-slice test contexts, which import this configuration
     *                       without {@code NettyEventLoopConfig}, still start in classpath
     *                       mode; isolated mode refuses to run without it.
     * @throws IllegalStateException on an unknown mode, on a classpath without plugin beans
     *                               (D-12), on a rejected classpath bundle (D-10), on an
     *                               isolated start with no valid bundle, or on an isolated
     *                               start without a fully started shared Netty group.
     */
    @Bean
    public PluginRuntime pluginRuntime(ApplicationContext context,
                                       @Value("${" + MODE_PROPERTY + ":classpath}") String mode,
                                       @Value("${" + DIR_PROPERTY + ":/app/plugins}") String pluginsDir,
                                       @Value("${" + BUILTIN_DIR_PROPERTY + ":/app/plugins-builtin}") String builtinDir,
                                       ObjectProvider<EventLoopGroup> eventLoopGroup) {
        return buildRuntime(context, mode, pluginsDir, builtinDir, eventLoopGroup.getIfAvailable());
    }

    /**
     * {@link #pluginRuntime} with the group already resolved (null when the context has
     * none). Separate so tests can call it with a group of their choosing.
     */
    PluginRuntime buildRuntime(ApplicationContext context, String mode, String pluginsDir,
                               String builtinDir, EventLoopGroup eventLoopGroup) {
        PluginRegistries registries = switch (normalize(mode)) {
            case CLASSPATH -> buildClasspath(context);
            case ISOLATED -> {
                requireStarted(eventLoopGroup);
                yield new IsolatedPluginBundleLoader(Path.of(pluginsDir), Path.of(builtinDir)).load();
            }
            default -> throw new IllegalStateException("Unknown " + MODE_PROPERTY + " '" + mode
                    + "' — expected one of " + MODES);
        };
        registries.logInitialized();
        return new PluginRuntime(registries);
    }

    /**
     * The version new bots are built from, read from {@link PluginRuntime} per call — never
     * captured, so step 5's version selection reaches it without a change here. Replaces
     * {@code BuiltinPluginVersionResolver} (D-15).
     */
    @Bean
    public PluginVersionResolver pluginVersionResolver(PluginRuntime pluginRuntime) {
        return () -> pluginRuntime.current().bundle().version();
    }

    /**
     * Close a bundle that {@link PluginRegistries#build} rejected, without losing the reason.
     * {@code rejected} is the D-10 cause ("duplicate key '116'", "missing
     * {@code @MessageTypesImpl}" …), the one thing an operator needs; a close that throws
     * too is attached to it as suppressed rather than replacing it (review-4a).
     */
    static void closeAfterRejection(PluginBundle bundle, RuntimeException rejected) {
        try {
            bundle.close();
        } catch (RuntimeException | Error closeFailure) {
            rejected.addSuppressed(closeFailure);
        }
    }

    private static PluginRegistries buildClasspath(ApplicationContext context) {
        PluginBundle bundle = new ClasspathPluginBundle(context);
        try {
            return PluginRegistries.build(bundle);
        } catch (RuntimeException rejected) {
            // A no-op for a classpath bundle, kept so every rejection path closes what it opened.
            closeAfterRejection(bundle, rejected);
            throw rejected;
        }
    }

    /**
     * Isolated mode refuses to load a bundle while any executor of the shared group lacks a
     * live thread: that thread would be started later, by whichever bot first touches it,
     * possibly under a plugin frame (L-11, spike 7b).
     */
    static void requireStarted(EventLoopGroup eventLoopGroup) {
        if (eventLoopGroup == null) {
            throw new IllegalStateException("isolated plugin mode needs the shared Netty "
                    + "EventLoopGroup bean, and this context has none (PLUGIN_HOT_RELOAD_3_4 L-11)");
        }
        int total = NettyEventLoopConfig.executorCount(eventLoopGroup);
        int started = NettyEventLoopConfig.startedExecutorCount(eventLoopGroup);
        if (started < total) {
            throw new IllegalStateException("the shared Netty EventLoopGroup has " + started + "/"
                    + total + " executors started — refusing to load a plugin bundle before every"
                    + " event-loop thread exists (PLUGIN_HOT_RELOAD_3_4 L-11)");
        }
    }

    private static String normalize(String mode) {
        return mode == null ? "" : mode.trim().toLowerCase(Locale.ROOT);
    }
}
