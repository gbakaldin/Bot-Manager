package com.vingame.bot.infrastructure.plugin;

import com.vingame.bot.common.plugin.PluginVersions;
import com.vingame.bot.domain.bot.message.GameMessageTypes;
import com.vingame.bot.domain.bot.message.g2.bom.BomGameMessageTypes;
import com.vingame.bot.domain.bot.strategy.BettingStrategy;
import com.vingame.bot.domain.bot.strategy.RandomBehaviorStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PLUGIN_HOT_RELOAD_3_4 Phase 4a step 3 / D-12: the classpath bundle is the application
 * context's own plugin beans, reported as {@code builtin} from the application loader, and
 * a context with no plugin bean at all is a startup failure rather than a silent start.
 */
@DisplayName("ClasspathPluginBundle — the root context's plugin beans (4a, D-12)")
class ClasspathPluginBundleTest {

    @Test
    @DisplayName("a context with no plugin bean fails fast with D-12's text")
    void zeroPluginBeansFailsFast() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(String.class, () -> "not a plugin");
            context.refresh();

            assertThatThrownBy(() -> new ClasspathPluginBundle(context))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("no plugin beans on the classpath — set bot.plugins.mode=isolated (see CLAUDE.md)");
        }
    }

    @Test
    @DisplayName("one plugin bean of any contract is enough — a strategy-only or messages-only context starts")
    void anySinglePluginBeanIsEnough() {
        try (AnnotationConfigApplicationContext strategiesOnly = new AnnotationConfigApplicationContext();
             AnnotationConfigApplicationContext messagesOnly = new AnnotationConfigApplicationContext()) {
            strategiesOnly.registerBean(RandomBehaviorStrategy.class);
            strategiesOnly.refresh();
            messagesOnly.registerBean(BomGameMessageTypes.class);
            messagesOnly.refresh();

            assertThat(new ClasspathPluginBundle(strategiesOnly).beansOfType(BettingStrategy.class)).hasSize(1);
            assertThat(new ClasspathPluginBundle(messagesOnly).beansOfType(GameMessageTypes.class)).hasSize(1);
        }
    }

    @Test
    @DisplayName("identity: builtin, source=classpath, the application loader, no jars")
    void identityIsTheBuiltinClasspathBundle() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(RandomBehaviorStrategy.class);
            context.refresh();

            ClasspathPluginBundle bundle = new ClasspathPluginBundle(context);

            assertThat(bundle.version()).isEqualTo(PluginVersions.BUILTIN);
            assertThat(bundle.source()).isEqualTo("classpath");
            assertThat(bundle.jars()).isEmpty();
            assertThat(bundle.classLoader())
                    .as("the loader that defined the plugin classes in classpath mode")
                    .isSameAs(RandomBehaviorStrategy.class.getClassLoader());
        }
    }

    @Test
    @DisplayName("newInstance hands out a fresh prototype per call, from the context")
    void newInstanceIsAFreshPrototype() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(RandomBehaviorStrategy.class);
            context.refresh();
            ClasspathPluginBundle bundle = new ClasspathPluginBundle(context);

            RandomBehaviorStrategy first = bundle.newInstance(RandomBehaviorStrategy.class);
            RandomBehaviorStrategy second = bundle.newInstance(RandomBehaviorStrategy.class);

            assertThat(first).isNotNull().isNotSameAs(second);
        }
    }

    @Test
    @DisplayName("beansOfType returns what a List<T> injection point received, in the same order")
    void beansOfTypeMatchesListInjection() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(BomGameMessageTypes.class);
            context.registerBean(com.vingame.bot.domain.bot.message.g3.tip.TipGameMessageTypes.class);
            context.registerBean(ListHolder.class);
            context.refresh();

            List<GameMessageTypes> injected = context.getBean(ListHolder.class).providers;
            List<GameMessageTypes> fromBundle = new ClasspathPluginBundle(context)
                    .beansOfType(GameMessageTypes.class);

            assertThat(fromBundle).containsExactlyElementsOf(injected);
        }
    }

    /** What the {@code @Component} registry's constructor used to receive. */
    static final class ListHolder {
        final List<GameMessageTypes> providers;

        ListHolder(List<GameMessageTypes> providers) {
            this.providers = providers;
        }
    }
}
