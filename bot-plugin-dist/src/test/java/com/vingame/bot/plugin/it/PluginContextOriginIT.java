package com.vingame.bot.plugin.it;

import com.vingame.bot.infrastructure.plugin.IsolatedPluginBundle;
import com.vingame.bot.infrastructure.plugin.IsolatedPluginBundleLoader;
import com.vingame.bot.infrastructure.plugin.PluginRegistries;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.AbstractApplicationContext;

import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PLUGIN_HOT_RELOAD_3_4 <b>L-3</b> and <b>L-4</b>:
 * <ul>
 *   <li>L-3 — every application bean of the child context is a class the plugin loader
 *       defined; no engine {@code @Component} exists in it, although {@code bot-api} and
 *       {@code bot-engine} (both full of {@code com.vingame.bot} classes, the engine's with
 *       {@code @Component}s) are on the parent classpath of a {@code com.vingame.bot}
 *       scan;</li>
 *   <li>L-4 — the child context has no parent and no shutdown hook, and the loading
 *       thread's context class loader is what it was before, after an accepted bundle and
 *       after a rejected one (spike rule 6).</li>
 * </ul>
 */
@DisplayName("L-3/L-4: the child context holds only plugin beans, has no parent or hook, and TCCL is restored")
class PluginContextOriginIT {

    @Test
    @DisplayName("every application bean is defined by the plugin loader, and they are all there")
    void childBeansAreAllPluginClasses() {
        PluginRegistries registries = ShippedBundle.load();
        try {
            IsolatedPluginBundle bundle = (IsolatedPluginBundle) registries.bundle();
            AnnotationConfigApplicationContext context = bundle.context();
            List<String> application = new ArrayList<>();
            List<String> foreign = new ArrayList<>();
            for (String name : context.getBeanDefinitionNames()) {
                BeanDefinition definition = context.getBeanDefinition(name);
                if (definition.getRole() == BeanDefinition.ROLE_INFRASTRUCTURE) {
                    assertThat(definition.getBeanClassName())
                            .as("infrastructure beans are Spring's annotation-config processors")
                            .startsWith("org.springframework.");
                    continue;
                }
                application.add(name);
                Class<?> type = context.getType(name);
                if (type == null || type.getClassLoader() != bundle.classLoader()) {
                    foreign.add(name + " -> " + (type == null ? null : type.getName() + " @ " + type.getClassLoader()));
                }
            }
            assertThat(foreign).as("beans not defined by the plugin loader (engine @Components leaking in?)").isEmpty();
            assertThat(application)
                    .as("9 betting + 2 slot strategies + 11 message-types providers (one bean may serve"
                            + " several products: BOM is 097 and 098)")
                    .hasSizeGreaterThanOrEqualTo(9 + 2 + 11);
            assertThat(application).noneMatch(n -> n.toLowerCase().contains("registry")
                    || n.toLowerCase().contains("factory"));
        } finally {
            registries.bundle().close();
        }
    }

    @Test
    @DisplayName("the child context has no parent and registers no shutdown hook")
    void noParentNoShutdownHook() throws Exception {
        PluginRegistries registries = ShippedBundle.load();
        try {
            AnnotationConfigApplicationContext context = ((IsolatedPluginBundle) registries.bundle()).context();
            assertThat(context.getParent()).isNull();
            assertThat(context.getClassLoader()).isSameAs(registries.bundle().classLoader());
            Field hook = AbstractApplicationContext.class.getDeclaredField("shutdownHook");
            hook.setAccessible(true);
            assertThat(hook.get(context)).as("PluginRuntime closes the bundle; a JVM hook would race it").isNull();
        } finally {
            registries.bundle().close();
        }
    }

    @Test
    @DisplayName("TCCL is unchanged after an accepted bundle")
    void tcclRestoredAfterSuccess() {
        ClassLoader marker = new URLClassLoader("tccl-marker", new URL[0], getClass().getClassLoader());
        Thread thread = Thread.currentThread();
        ClassLoader original = thread.getContextClassLoader();
        thread.setContextClassLoader(marker);
        try {
            PluginRegistries registries = ShippedBundle.load();
            try {
                assertThat(thread.getContextClassLoader()).isSameAs(marker);
            } finally {
                registries.bundle().close();
            }
        } finally {
            thread.setContextClassLoader(original);
        }
    }

    @Test
    @DisplayName("TCCL is unchanged after a candidate whose context refresh failed, and the next candidate is used")
    void tcclRestoredAfterARefreshFailure() throws Exception {
        Path mount = BundleFixtures.freshDir("tccl-refresh-failure");
        BundleFixtures.shippedCopy(mount, "20990101.000000");
        Path exploding = BundleFixtures.shippedCopy(mount, "20990109.000000");
        BundleFixtures.compiledJar(exploding.resolve("bot-exploding-1.0.jar"), "20990109.000000",
                BundleFixtures.EXPLODING_BEAN);

        ClassLoader marker = new URLClassLoader("tccl-marker", new URL[0], getClass().getClassLoader());
        Thread thread = Thread.currentThread();
        ClassLoader original = thread.getContextClassLoader();
        thread.setContextClassLoader(marker);
        try (LogCapture log = new LogCapture(IsolatedPluginBundleLoader.class.getName(), Level.ALL)) {
            PluginRegistries registries = new IsolatedPluginBundleLoader(mount,
                    mount.resolve("_none")).load();
            try {
                assertThat(thread.getContextClassLoader())
                        .as("set to the rejected bundle's loader for refresh(), restored in finally")
                        .isSameAs(marker);
                assertThat(registries.bundle().version()).isEqualTo("20990101.000000");
                assertThat(log.messages(Level.ERROR)).singleElement().asString()
                        .contains("plugin bundle " + exploding + " rejected:");
            } finally {
                registries.bundle().close();
            }
        } finally {
            thread.setContextClassLoader(original);
        }
        assertThat(Files.isDirectory(exploding)).as("the loader never writes to the bundle directories").isTrue();
    }
}
