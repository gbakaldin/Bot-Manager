package com.vingame.bot.infrastructure.plugin;

import com.vingame.bot.common.plugin.PluginVersions;
import org.springframework.context.ApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A hand-built {@link PluginBundle} for registry unit tests (PLUGIN_HOT_RELOAD_3_4 Phase
 * 4a). Before 4a a registry test constructed {@code new BettingStrategyFactory(context,
 * List.of(beans…))} and called {@code init()}; the registries now take a bundle, and this
 * is the same two inputs behind the bundle interface:
 * <ul>
 *   <li>{@link #beansOfType} returns the given beans assignable to the type, in the order
 *       given — what the {@code List<T>} injection used to receive;</li>
 *   <li>{@link #newInstance} delegates to the given context's {@code getBean(Class)}, which
 *       tests mock to return fresh instances.</li>
 * </ul>
 * Version {@code builtin}, source {@code stub}, no jars, the test's own loader; close
 * releases nothing.
 */
public final class StubPluginBundle extends PluginBundle {

    private final ApplicationContext context;
    private final List<Object> beans;

    private StubPluginBundle(ApplicationContext context, List<?> beans) {
        this.context = context;
        this.beans = List.copyOf(beans);
    }

    /**
     * @param context answers {@link #newInstance}; may be {@code null} if the test never
     *                calls {@code create}.
     * @param beans   the bundle's beans, in discovery order.
     */
    public static StubPluginBundle of(ApplicationContext context, List<?> beans) {
        return new StubPluginBundle(context, Objects.requireNonNull(beans, "beans"));
    }

    @Override
    public String version() {
        return PluginVersions.BUILTIN;
    }

    @Override
    public String source() {
        return "stub";
    }

    @Override
    public ClassLoader classLoader() {
        return StubPluginBundle.class.getClassLoader();
    }

    @Override
    public List<PluginJar> jars() {
        return List.of();
    }

    @Override
    public <T> List<T> beansOfType(Class<T> type) {
        List<T> matching = new ArrayList<>();
        for (Object bean : beans) {
            if (type.isInstance(bean)) {
                matching.add(type.cast(bean));
            }
        }
        return matching;
    }

    @Override
    public <T> T newInstance(Class<T> type) {
        return context.getBean(type);
    }

    @Override
    protected void closeContext() {
    }

    @Override
    protected void closeClassLoader() {
    }
}
