package com.vingame.bot.infrastructure.plugin;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A plugin bundle loaded in a classloader of its own (PLUGIN_HOT_RELOAD_3_4 Phase 4b,
 * D-13): the jars of one bundle directory, a child {@link URLClassLoader} over them, and a
 * <b>parentless</b> Spring context holding exactly the bundle's beans.
 *
 * <h2>Loader (D-13)</h2>
 * {@code new URLClassLoader("plugin-" + version, jarUrls, PluginBundle.class.getClassLoader())}
 * with standard parent-first delegation, so every contract ({@code bot-api}), Spring,
 * Jackson, ws-parser, slf4j and log4j2 resolve to the application's own {@code Class}
 * objects. The URLs are plain {@code file:} URLs of the on-disk jars, never
 * {@code jar:nested:} (Boot's nested-jar handler caches {@code JarFile}s by URL).
 *
 * <h2>Context and scan</h2>
 * An {@link AnnotationConfigApplicationContext} with no parent and the plugin loader as
 * its class loader, scanning {@value #BASE_PACKAGE} — {@code Starter}'s base package, so a
 * plugin bean reachable in classpath mode is reachable here too. Two loaders take part in
 * the scan, deliberately:
 * <ul>
 *   <li><b>Which resources are candidates</b> comes from a resource-only
 *       {@code URLClassLoader(jarUrls, null)}: it sees the bundle's jar entries and
 *       nothing else, so a scan of a package split with {@code bot-api}
 *       ({@code domain.bot.message}, {@code .strategy}, …) cannot pick up a parent class,
 *       and no engine {@code @Component} is ever instantiated in the child.</li>
 *   <li><b>How each candidate's annotations are read</b> uses the plugin loader. This is a
 *       correction to D-13 as written, which made the resource-only loader the scanner's
 *       whole resource loader: Spring resolves an annotation's <em>type</em> through the
 *       metadata reader's class loader and silently drops any it cannot load, and a
 *       null-parent loader cannot load {@code org.springframework.stereotype.Component}.
 *       That scan finds zero components and the bundle is rejected as empty.</li>
 * </ul>
 *
 * <h2>Origin check (L-3, enforced here as well as tested)</h2>
 * After refresh, every application bean's class must have been <em>defined</em> by the
 * plugin loader. With parent-first delegation, a plugin class that is also on the
 * application classpath is silently loaded from the parent and "isolated" mode would run
 * the application's copy — exactly the pre-4c fat jar, whose {@code BOOT-INF/lib} still
 * carries both plugin jars. Such a bundle is rejected with a message that says so, rather
 * than accepted and quietly not isolated.
 *
 * <h2>TCCL (S6, L-4)</h2>
 * Set to the plugin loader only for the duration of {@code refresh()}, and restored in
 * {@code finally} on the same thread, success or failure.
 *
 * <h2>Close</h2>
 * {@link PluginBundle#close()}'s D-13 order; this class supplies step 2 (the context) and
 * step 4 (the loader). No shutdown hook is registered: {@code PluginRuntime} closes the
 * bundle as a bean destroy method, and a rejected candidate is closed by the loader that
 * rejected it.
 */
public final class IsolatedPluginBundle extends PluginBundle {

    /** {@code Starter}'s scan base (D-13). */
    public static final String BASE_PACKAGE = "com.vingame.bot";

    /** The manifest attribute that carries a plugin jar's bundle version (D-7). */
    public static final String VERSION_ATTRIBUTE = "Bot-Plugin-Version";

    private final String version;
    private final Path directory;
    private final List<PluginJar> jars;
    private final URLClassLoader loader;
    private final AnnotationConfigApplicationContext context;

    private IsolatedPluginBundle(String version, Path directory, List<PluginJar> jars,
                                 URLClassLoader loader, AnnotationConfigApplicationContext context) {
        this.version = version;
        this.directory = directory;
        this.jars = List.copyOf(jars);
        this.loader = loader;
        this.context = context;
    }

    /**
     * Load one bundle: create its loader, scan its jars, refresh its context and check the
     * origin of every bean. On any failure everything created so far is released before
     * the exception propagates, and the thread's context class loader is what it was.
     *
     * @param directory the bundle directory, reported as {@link #source()}.
     * @param version   the bundle version every jar's manifest agreed on (D-7).
     * @param jarFiles  the bundle's jars, in a stable order.
     * @param jars      the same jars as the boot line names them (file name + SHA-256).
     * @throws IllegalStateException if the bundle cannot be loaded or fails the origin check.
     */
    public static IsolatedPluginBundle open(Path directory, String version, List<Path> jarFiles,
                                            List<PluginJar> jars) {
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(version, "version");
        URL[] urls = fileUrls(jarFiles);
        URLClassLoader loader = new URLClassLoader("plugin-" + version, urls,
                PluginBundle.class.getClassLoader());
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        try {
            context.setClassLoader(loader);
            context.setDisplayName("plugin-bundle-" + version);
            int found = scan(context, loader, urls, version);
            if (found == 0) {
                throw new IllegalStateException("no plugin beans under " + BASE_PACKAGE
                        + " in " + jarNames(jarFiles));
            }
            refreshWithPluginTccl(context, loader);
            IsolatedPluginBundle bundle = new IsolatedPluginBundle(version, directory, jars,
                    loader, context);
            bundle.checkOrigin();
            return bundle;
        } catch (RuntimeException | Error failure) {
            release(context, loader, failure);
            throw failure;
        }
    }

    @Override
    public String version() {
        return version;
    }

    /** The bundle directory, e.g. {@code /app/plugins/20261007.101500}. */
    @Override
    public String source() {
        return directory.toString();
    }

    @Override
    public ClassLoader classLoader() {
        return loader;
    }

    @Override
    public List<PluginJar> jars() {
        return jars;
    }

    @Override
    public boolean isolated() {
        return true;
    }

    /** Same contract as classpath mode: what a {@code List<T>} injection would receive. */
    @Override
    public <T> List<T> beansOfType(Class<T> type) {
        return context.getBeanProvider(type).orderedStream().toList();
    }

    @Override
    public <T> T newInstance(Class<T> type) {
        return context.getBean(type);
    }

    /** The bundle's own context; tests read it for L-3 / L-4. */
    public AnnotationConfigApplicationContext context() {
        return context;
    }

    @Override
    protected void closeContext() {
        context.close();
    }

    @Override
    protected void closeClassLoader() throws IOException {
        loader.close();
    }

    // ------------------------------------------------------------------ internals

    private static int scan(AnnotationConfigApplicationContext context, URLClassLoader loader,
                            URL[] urls, String version) {
        try (URLClassLoader resourcesOnly = new URLClassLoader("plugin-scan-" + version, urls, null)) {
            ClassPathBeanDefinitionScanner scanner = new ClassPathBeanDefinitionScanner(
                    context, true, context.getEnvironment(), new DefaultResourceLoader(resourcesOnly));
            // Candidates come from resourcesOnly (bundle jars only); their annotations are
            // read through the plugin loader, which can resolve Spring's annotation types.
            scanner.setMetadataReaderFactory(new CachingMetadataReaderFactory(loader));
            return scanner.scan(BASE_PACKAGE);
        } catch (IOException e) {
            throw new IllegalStateException("could not close the scan loader", e);
        }
    }

    private static void refreshWithPluginTccl(AnnotationConfigApplicationContext context,
                                              ClassLoader loader) {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try {
            context.refresh();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    /** L-3: every application bean is a plugin class, defined by this bundle's loader. */
    private void checkOrigin() {
        List<String> foreign = new ArrayList<>();
        for (String name : context.getBeanDefinitionNames()) {
            BeanDefinition definition = context.getBeanDefinition(name);
            if (definition.getRole() == BeanDefinition.ROLE_INFRASTRUCTURE) {
                continue; // Spring's own annotation-config processors
            }
            Class<?> type = context.getType(name);
            if (type == null || type.getClassLoader() != loader) {
                foreign.add(name + " (" + (type == null ? "unresolvable" : type.getName()
                        + " defined by " + type.getClassLoader()) + ")");
            }
        }
        if (!foreign.isEmpty()) {
            throw new IllegalStateException("beans not defined by the plugin loader: " + foreign
                    + " — the plugin classes are also on the application classpath (a fat jar"
                    + " that still carries bot-strategies/bot-messages?), so parent-first"
                    + " delegation loads them from there and this bundle would not be isolated");
        }
    }

    private static void release(AnnotationConfigApplicationContext context, URLClassLoader loader,
                                Throwable failure) {
        try {
            context.close();
        } catch (RuntimeException | Error e) {
            failure.addSuppressed(e);
        }
        try {
            loader.close();
        } catch (IOException | RuntimeException e) {
            failure.addSuppressed(e);
        }
    }

    private static URL[] fileUrls(List<Path> jarFiles) {
        URL[] urls = new URL[jarFiles.size()];
        for (int i = 0; i < urls.length; i++) {
            try {
                urls[i] = jarFiles.get(i).toAbsolutePath().toUri().toURL();
            } catch (MalformedURLException e) {
                throw new IllegalStateException("not a file URL: " + jarFiles.get(i), e);
            }
        }
        return urls;
    }

    private static List<String> jarNames(List<Path> jarFiles) {
        return jarFiles.stream().map(p -> p.getFileName().toString()).toList();
    }
}
