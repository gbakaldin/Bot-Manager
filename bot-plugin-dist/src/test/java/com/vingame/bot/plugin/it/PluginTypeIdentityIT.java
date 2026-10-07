package com.vingame.bot.plugin.it;

import com.vingame.bot.infrastructure.plugin.PluginBundle;
import com.vingame.bot.infrastructure.plugin.PluginRegistries;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PLUGIN_HOT_RELOAD_3_4 <b>L-1</b>: in the isolated bundle,
 * <ul>
 *   <li>every class in the jars loads and links, and its defining loader is the plugin
 *       loader — not shadowed by a copy on the parent classpath;</li>
 *   <li>every type a plugin class references that is <em>not</em> in the jars (contracts,
 *       Spring, Jackson, ws-parser, slf4j, the JDK) resolves to the parent's {@code Class}
 *       — so a plugin's {@code BettingStrategy} is the engine's {@code BettingStrategy};</li>
 *   <li>every loader URL is a plain {@code file:} URL, never {@code jar:nested:}.</li>
 * </ul>
 * The first assertion is what keeps "isolation" from passing vacuously: if this module ever
 * gained a plugin dependency, parent-first delegation would load every plugin class from
 * the parent and it would fail here.
 */
@DisplayName("L-1: plugin classes come from the plugin loader, contracts from the parent")
class PluginTypeIdentityIT {

    private static PluginRegistries registries;
    private static ClassLoader pluginLoader;
    private static final ClassLoader PARENT = PluginBundle.class.getClassLoader();

    @BeforeAll
    static void load() {
        registries = ShippedBundle.load();
        pluginLoader = registries.bundle().classLoader();
    }

    @AfterAll
    static void close() {
        registries.bundle().close();
    }

    @Test
    @DisplayName("this module's own classpath has no plugin class (the premise of every other assertion)")
    void testClasspathIsPluginFree() {
        assertThatThrownBy(() -> Class.forName(
                "com.vingame.bot.domain.bot.strategy.RandomBehaviorStrategy", false, PARENT))
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    @DisplayName("every loader URL is file:, one per shipped jar")
    void loaderUrlsAreFileUrls() {
        URL[] urls = ((URLClassLoader) pluginLoader).getURLs();
        assertThat(urls).hasSize(ShippedBundle.jars().size());
        assertThat(urls).allSatisfy(url -> assertThat(url.getProtocol()).isEqualTo("file"));
        assertThat(pluginLoader.getParent()).isSameAs(PARENT);
        assertThat(pluginLoader.getName()).isEqualTo("plugin-" + registries.bundle().version());
    }

    @Test
    @DisplayName("every class in the jars loads, links, and is defined by the plugin loader; every outside type is the parent's")
    void everyClassIsThePluginsAndEveryContractTheParents() throws Exception {
        Set<String> jarClasses = jarClassNames();
        List<String> shadowed = new ArrayList<>();
        List<String> foreignContracts = new ArrayList<>();
        int checkedReferences = 0;
        for (String name : jarClasses) {
            Class<?> type = Class.forName(name, false, pluginLoader);
            if (type.getClassLoader() != pluginLoader) {
                shadowed.add(name + " defined by " + type.getClassLoader());
            }
            for (Class<?> referenced : referencedTypes(type)) {
                if (referenced.isPrimitive() || jarClasses.contains(referenced.getName())) {
                    continue;
                }
                checkedReferences++;
                Class<?> parentCopy = Class.forName(referenced.getName(), false, PARENT);
                if (referenced != parentCopy) {
                    foreignContracts.add(name + " -> " + referenced.getName() + " ("
                            + referenced.getClassLoader() + ")");
                }
            }
        }
        assertThat(shadowed).as("plugin classes shadowed by a parent copy").isEmpty();
        assertThat(foreignContracts).as("referenced types that are not the parent's Class").isEmpty();
        assertThat(jarClasses).hasSizeGreaterThan(100);
        assertThat(checkedReferences).isPositive();
    }

    @Test
    @DisplayName("a live strategy is an instance of the engine's BettingStrategy, and its class is the plugin's")
    void liveBeansCrossTheBoundaryAsContracts() {
        Object strategy = registries.bettingStrategies().create("RANDOM");
        assertThat(strategy).isInstanceOf(com.vingame.bot.domain.bot.strategy.BettingStrategy.class);
        assertThat(strategy.getClass().getClassLoader()).isSameAs(pluginLoader);
        assertThat(registries.messageTypes().slot().getClass().getClassLoader()).isSameAs(pluginLoader);
    }

    /** Supertypes, field types and member signatures: getDeclared* forces their resolution (linking). */
    private static Set<Class<?>> referencedTypes(Class<?> type) {
        Set<Class<?>> out = new LinkedHashSet<>();
        if (type.getSuperclass() != null) {
            out.add(type.getSuperclass());
        }
        out.addAll(List.of(type.getInterfaces()));
        for (Field field : type.getDeclaredFields()) {
            out.add(component(field.getType()));
        }
        for (Method method : type.getDeclaredMethods()) {
            out.add(component(method.getReturnType()));
            for (Class<?> p : method.getParameterTypes()) {
                out.add(component(p));
            }
        }
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            for (Class<?> p : constructor.getParameterTypes()) {
                out.add(component(p));
            }
        }
        for (var annotation : type.getAnnotations()) {
            out.add(annotation.annotationType());
        }
        return out;
    }

    private static Class<?> component(Class<?> type) {
        Class<?> c = type;
        while (c.isArray()) {
            c = c.getComponentType();
        }
        return c;
    }

    private static Set<String> jarClassNames() throws IOException {
        Set<String> names = new LinkedHashSet<>();
        for (Path jar : ShippedBundle.jars()) {
            try (JarFile file = new JarFile(jar.toFile())) {
                Enumeration<JarEntry> entries = file.entries();
                while (entries.hasMoreElements()) {
                    String entry = entries.nextElement().getName();
                    if (entry.endsWith(".class") && !entry.endsWith("module-info.class")) {
                        names.add(entry.substring(0, entry.length() - ".class".length()).replace('/', '.'));
                    }
                }
            }
        }
        return names;
    }
}
