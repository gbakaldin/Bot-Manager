package com.vingame.bot.infrastructure.plugin;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.fasterxml.jackson.databind.util.LookupCache;
import com.vingame.bot.domain.bot.strategy.RandomBehaviorStrategy;
import com.vingame.bot.domain.bot.strategy.slot.FixedBetStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PLUGIN_HOT_RELOAD_3_4 <b>L-8 (second half)</b> and D-13: {@link PluginBundle#close()}
 * releases a bundle in the spike's order (rule 3) and clears both Jackson type caches
 * (rule 2). Step 4 only closes rejected candidates and runs this at shutdown; step 6
 * inherits the order, so it is pinned now, while getting it wrong costs nothing.
 * <ol>
 *   <li>unpublish — {@link PluginRuntime} stops handing the registries out;</li>
 *   <li>close the context;</li>
 *   <li>clear the bundle's type cache and {@code TypeFactory.defaultInstance()}'s;</li>
 *   <li>close the classloader.</li>
 * </ol>
 * Each step is observed from inside the next one, so the test fails on a reordering, not
 * just on a missing step.
 */
@DisplayName("PluginBundle.close() — D-13 order, both type caches cleared")
class PluginBundleCloseTest {

    /** Resolved into the bundle's private cache. */
    static final class BundleProbe {
    }

    /** Resolved into the shared default cache, the one a stray default mapper would fill. */
    static final class DefaultProbe {
    }

    @Test
    @DisplayName("unpublish, context, type caches, classloader — in that order")
    void closeRunsInTheD13Order() throws Exception {
        RecordingBundle bundle = new RecordingBundle();
        bundle.onUnpublish(() -> bundle.events.add("unpublish"));
        bundle.typeFactory().constructType(BundleProbe.class);
        TypeFactory.defaultInstance().constructType(DefaultProbe.class);
        assertThat(cached(bundle.typeFactory(), BundleProbe.class)).isTrue();
        assertThat(cached(TypeFactory.defaultInstance(), DefaultProbe.class)).isTrue();

        bundle.close();

        assertThat(bundle.events).containsExactly("unpublish", "context", "loader");
        assertThat(bundle.bundleCacheHeldProbeAtContextClose)
                .as("the caches are cleared AFTER the context closes (step 3 follows step 2)")
                .isTrue();
        assertThat(bundle.bundleCacheHeldProbeAtLoaderClose)
                .as("the bundle cache is clear BEFORE the loader closes (step 3 precedes step 4)")
                .isFalse();
        assertThat(bundle.defaultCacheHeldProbeAtLoaderClose)
                .as("the shared default cache is cleared too, before the loader closes")
                .isFalse();
        assertThat(bundle.isClosed()).isTrue();
    }

    @Test
    @DisplayName("a second close() does nothing")
    void closeIsIdempotent() {
        RecordingBundle bundle = new RecordingBundle();
        bundle.onUnpublish(() -> bundle.events.add("unpublish"));

        bundle.close();
        bundle.close();

        assertThat(bundle.events).containsExactly("unpublish", "context", "loader");
    }

    @Test
    @DisplayName("a failing step does not stop the later ones; the first failure is rethrown")
    void laterStepsRunAfterAFailure() {
        RecordingBundle bundle = new RecordingBundle();
        bundle.failContextClose = true;
        bundle.typeFactory().constructType(BundleProbe.class);

        assertThatThrownBy(bundle::close)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("close context failed")
                .hasRootCauseMessage("context refused to close");

        // The loader is still closed and the caches still cleared: a context that throws
        // on close must not leave the bundle's classloader open and pinned.
        assertThat(bundle.events).containsExactly("context", "loader");
        assertThat(bundle.bundleCacheHeldProbeAtLoaderClose).isFalse();
    }

    @Test
    @DisplayName("once its bundle closes, PluginRuntime stops publishing the registries")
    void runtimeUnpublishesOnBundleClose() {
        RecordingBundle bundle = new RecordingBundle();
        PluginRuntime runtime = new PluginRuntime(PluginRegistries.build(bundle));
        assertThat(runtime.current().bundle()).isSameAs(bundle);

        runtime.close();

        assertThat(bundle.events).containsExactly("context", "loader");
        assertThatThrownBy(runtime::current)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("has been closed");
    }

    @Test
    @DisplayName("a classpath bundle's close() is a no-op: the root context and the app loader are not its to release")
    void classpathBundleCloseIsANoOp() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(RandomBehaviorStrategy.class);
            context.registerBean(FixedBetStrategy.class);
            context.refresh();
            ClasspathPluginBundle bundle = new ClasspathPluginBundle(context);
            PluginRuntime runtime = new PluginRuntime(PluginRegistries.build(bundle));
            List<String> hooks = new ArrayList<>();
            bundle.onUnpublish(() -> hooks.add("unpublish"));

            runtime.close();

            assertThat(hooks).as("unpublish hooks never run in classpath mode").isEmpty();
            assertThat(context.isActive()).as("the root context is Spring's to close").isTrue();
            assertThat(runtime.current().bettingStrategies().registeredKeys()).containsExactly("RANDOM");
            assertThat(bundle.isClosed()).isFalse();
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Whether {@code type} sits in {@code factory}'s own type cache. */
    static boolean cached(TypeFactory factory, Class<?> type) throws Exception {
        Field field = TypeFactory.class.getDeclaredField("_typeCache");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        LookupCache<Object, JavaType> cache = (LookupCache<Object, JavaType>) field.get(factory);
        // TypeFactory keys a binding-free class by the Class itself.
        return cache.get(type) != null;
    }

    /** Records which close step ran, and what the caches held at each step. */
    private static final class RecordingBundle extends PluginBundle {
        final List<String> events = new ArrayList<>();
        boolean failContextClose;
        boolean bundleCacheHeldProbeAtContextClose;
        boolean bundleCacheHeldProbeAtLoaderClose = true;
        boolean defaultCacheHeldProbeAtLoaderClose = true;

        @Override
        public String version() {
            return "20261007.000000";
        }

        @Override
        public String source() {
            return "recording";
        }

        @Override
        public ClassLoader classLoader() {
            return getClass().getClassLoader();
        }

        @Override
        public List<PluginJar> jars() {
            return List.of();
        }

        @Override
        public <T> List<T> beansOfType(Class<T> type) {
            return List.of();
        }

        @Override
        public <T> T newInstance(Class<T> type) {
            throw new UnsupportedOperationException();
        }

        @Override
        protected void closeContext() throws Exception {
            events.add("context");
            bundleCacheHeldProbeAtContextClose = cached(typeFactory(), BundleProbe.class);
            if (failContextClose) {
                throw new IllegalStateException("context refused to close");
            }
        }

        @Override
        protected void closeClassLoader() throws Exception {
            events.add("loader");
            bundleCacheHeldProbeAtLoaderClose = cached(typeFactory(), BundleProbe.class);
            defaultCacheHeldProbeAtLoaderClose =
                    cached(TypeFactory.defaultInstance(), DefaultProbe.class);
        }
    }
}
