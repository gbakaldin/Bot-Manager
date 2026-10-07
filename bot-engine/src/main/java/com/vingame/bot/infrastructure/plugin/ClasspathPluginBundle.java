package com.vingame.bot.infrastructure.plugin;

import com.vingame.bot.common.plugin.PluginVersions;
import com.vingame.bot.domain.bot.message.CashoutMessageTypes;
import com.vingame.bot.domain.bot.message.CrashMessageTypes;
import com.vingame.bot.domain.bot.message.GameMessageTypes;
import com.vingame.bot.domain.bot.message.SlotMessageTypes;
import com.vingame.bot.domain.bot.message.TaiXiuMessageTypes;
import com.vingame.bot.domain.bot.strategy.BettingStrategy;
import com.vingame.bot.domain.bot.strategy.slot.SlotStrategy;
import org.springframework.context.ApplicationContext;

import java.util.List;
import java.util.Objects;

/**
 * The plugin bundle of classpath mode (PLUGIN_HOT_RELOAD_3_4 Phase 4a, D-12): the plugin
 * beans the application context already holds, because the plugin jars are on the
 * application classpath and {@code Starter}'s component scan found them.
 * <ul>
 *   <li>version {@link PluginVersions#BUILTIN}, source {@code classpath}, no jars of its
 *       own;</li>
 *   <li>the application classloader, which is what defined the plugin classes and what
 *       {@code PluginClassLoaderMetrics} registered before this class existed;</li>
 *   <li>{@link #close()} is a no-op. The root context is Spring's to close and the
 *       application loader lives as long as the JVM, so there is nothing to release.</li>
 * </ul>
 * Behaviour is exactly what the {@code @Component} registries had (D-1): the same beans,
 * in the order a {@code List<T>} injection point received them, and {@code getBean(Class)}
 * for a fresh prototype per strategy.
 *
 * <p><b>Zero plugin beans fails fast (D-12).</b> A context with no strategy and no
 * message-types bean at all is not a degraded start, it is a jar without its plugins,
 * which from 4c is exactly the fat jar run outside compose. Without this check that
 * starts clean and fails per group, hours later, with "No BettingStrategy registered".
 */
public class ClasspathPluginBundle extends PluginBundle {

    /** The {@code source=} value of a classpath bundle. */
    public static final String SOURCE = "classpath";

    /** D-12's text, byte for byte. */
    static final String NO_PLUGIN_BEANS =
            "no plugin beans on the classpath — set bot.plugins.mode=isolated (see CLAUDE.md)";

    /** Every contract a plugin bean implements. Any one bean of any of them is enough. */
    static final List<Class<?>> PLUGIN_CONTRACTS = List.of(
            BettingStrategy.class,
            SlotStrategy.class,
            GameMessageTypes.class,
            SlotMessageTypes.class,
            TaiXiuMessageTypes.class,
            CashoutMessageTypes.class,
            CrashMessageTypes.class);

    private final ApplicationContext context;

    /**
     * @param context the application context whose plugin beans make up the bundle.
     * @throws IllegalStateException if it holds no plugin bean at all (D-12).
     */
    public ClasspathPluginBundle(ApplicationContext context) {
        this.context = Objects.requireNonNull(context, "context");
        // Bean names only: counting must not instantiate anything.
        boolean anyPluginBean = PLUGIN_CONTRACTS.stream()
                .anyMatch(contract -> context.getBeanNamesForType(contract).length > 0);
        if (!anyPluginBean) {
            throw new IllegalStateException(NO_PLUGIN_BEANS);
        }
    }

    @Override
    public String version() {
        return PluginVersions.BUILTIN;
    }

    @Override
    public String source() {
        return SOURCE;
    }

    /**
     * The application classloader. In the fat jar that is Boot's launcher loader, which
     * defines {@code BOOT-INF/classes} and every {@code BOOT-INF/lib} jar alike, the plugin
     * jars included; in tests it is the system loader. Either way it is the loader of this
     * class and of {@code PluginClassLoaderMetrics}.
     */
    @Override
    public ClassLoader classLoader() {
        return ClasspathPluginBundle.class.getClassLoader();
    }

    @Override
    public List<PluginJar> jars() {
        return List.of();
    }

    /**
     * {@code getBeanProvider(type).orderedStream()}: the candidates a {@code List<T>}
     * constructor parameter received, sorted by the same order comparator. No plugin class
     * carries {@code @Order}, so in practice that is bean-definition order, as before.
     */
    @Override
    public <T> List<T> beansOfType(Class<T> type) {
        return context.getBeanProvider(type).orderedStream().toList();
    }

    @Override
    public <T> T newInstance(Class<T> type) {
        return context.getBean(type);
    }

    /** A no-op: see the class javadoc. The {@link #onUnpublish} hooks never run. */
    @Override
    public void close() {
        // Deliberately empty.
    }

    @Override
    protected void closeContext() {
        // Unreachable (close() is overridden); the root context is Spring's.
    }

    @Override
    protected void closeClassLoader() {
        // Unreachable (close() is overridden); the application loader is the JVM's.
    }
}
