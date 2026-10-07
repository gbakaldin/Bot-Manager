package com.vingame.bot.infrastructure.plugin;

import com.vingame.bot.common.plugin.PluginVersionResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Locale;

/**
 * Builds the plugin bundle for {@code bot.plugins.mode}, the {@link PluginRuntime} that
 * publishes its registries, and the {@link PluginVersionResolver} that reports its version
 * (PLUGIN_HOT_RELOAD_3_4 Phase 4a, D-9, D-12, D-15).
 * <p>
 * <b>Modes.</b> Only {@code classpath} exists in 4a: the bundle is the plugin beans of this
 * application context ({@link ClasspathPluginBundle}), so nothing changes at runtime (D-1).
 * Phase 4b adds {@code isolated}. Any other value fails startup rather than falling back,
 * because a typo in a mode switch that silently picks a different loader is the worst way
 * to find out which one is running. The property defaults to {@code classpath} here and
 * in {@code application.properties}, and must stay that way there: every
 * {@code @SpringBootTest} runs in classpath mode (D-12).
 * <p>
 * <b>Rejection.</b> {@link PluginRegistries#build} is the single validation point (D-10).
 * In classpath mode a rejected bundle is a context-refresh failure, which is what keeps a
 * broken build from producing a startable artifact — the behaviour the {@code @Component}
 * registries had.
 * <p>
 * <b>This class holds nothing.</b> The bundle and the registries are reachable only
 * through the {@link PluginRuntime} bean (L-10).
 */
@Configuration
public class PluginRuntimeConfiguration {

    /** The property that selects the bundle source. */
    public static final String MODE_PROPERTY = "bot.plugins.mode";

    /** The modes this build accepts, in the order the startup error lists them. */
    static final List<String> MODES = List.of("classpath");

    /**
     * @param context the application context; in classpath mode, the bundle itself.
     * @param mode    {@value #MODE_PROPERTY}; case and surrounding blanks are ignored.
     * @throws IllegalStateException on an unknown mode, on a classpath without plugin beans
     *                               (D-12), or on a rejected bundle (D-10).
     */
    @Bean
    public PluginRuntime pluginRuntime(ApplicationContext context,
                                       @Value("${" + MODE_PROPERTY + ":classpath}") String mode) {
        PluginBundle bundle = openBundle(context, mode);
        try {
            return new PluginRuntime(PluginRegistries.build(bundle));
        } catch (RuntimeException rejected) {
            // A no-op for a classpath bundle; releases an isolated one's loader (4b).
            bundle.close();
            throw rejected;
        }
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

    static PluginBundle openBundle(ApplicationContext context, String mode) {
        String normalized = mode == null ? "" : mode.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("classpath")) {
            return new ClasspathPluginBundle(context);
        }
        throw new IllegalStateException("Unknown " + MODE_PROPERTY + " '" + mode
                + "' — expected one of " + MODES);
    }
}
