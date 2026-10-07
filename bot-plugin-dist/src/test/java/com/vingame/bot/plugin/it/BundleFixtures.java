package com.vingame.bot.plugin.it;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

/**
 * Builds bundle directories under {@code target/it-fixtures} for the D-11 selection tests
 * and the rejection paths of L-4: copies of the shipped jars re-stamped with another
 * {@code Bot-Plugin-Version}, and small jars compiled from source at test time.
 * <p>
 * <b>Why compile at test time.</b> A fixture class compiled with the test sources would sit
 * on this module's test classpath, i.e. in the parent loader; parent-first delegation would
 * then load it from there, and the loader's origin check would reject the bundle for that
 * reason rather than for the one the test is about. Compiled into a jar only, it is
 * reachable through the bundle's loader alone, exactly like a real plugin class.
 */
final class BundleFixtures {

    private BundleFixtures() {
    }

    /** A fresh, empty directory under {@code target/it-fixtures}. */
    static Path freshDir(String name) throws IOException {
        Path root = Path.of(System.getProperty("fixtures.dir", "target/it-fixtures"));
        Path dir = root.resolve(name);
        deleteRecursively(dir);
        return Files.createDirectories(dir);
    }

    /**
     * {@code <parent>/<dirName>/} holding both shipped jars, re-stamped: strategies with
     * {@code strategiesVersion}, messages with {@code messagesVersion}.
     */
    static Path shippedCopy(Path parent, String dirName, String strategiesVersion,
                            String messagesVersion) throws IOException {
        Path dir = Files.createDirectories(parent.resolve(dirName));
        for (Path jar : ShippedBundle.jars()) {
            String name = jar.getFileName().toString();
            String version = name.startsWith("bot-strategies") ? strategiesVersion : messagesVersion;
            restamp(jar, dir.resolve(name), version);
        }
        return dir;
    }

    /** {@link #shippedCopy} with one version for both jars. */
    static Path shippedCopy(Path parent, String version) throws IOException {
        return shippedCopy(parent, version, version, version);
    }

    /** Copy a jar, replacing its manifest's {@code Bot-Plugin-Version}. */
    static void restamp(Path source, Path target, String version) throws IOException {
        try (JarFile in = new JarFile(source.toFile())) {
            Manifest manifest = new Manifest(in.getManifest());
            manifest.getMainAttributes().putValue("Bot-Plugin-Version", version);
            try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(target), manifest)) {
                Enumeration<JarEntry> entries = in.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (entry.getName().equalsIgnoreCase(JarFile.MANIFEST_NAME)
                            || entry.getName().equalsIgnoreCase("META-INF/")) {
                        continue;
                    }
                    out.putNextEntry(new JarEntry(entry.getName()));
                    if (!entry.isDirectory()) {
                        try (InputStream data = in.getInputStream(entry)) {
                            data.transferTo(out);
                        }
                    }
                    out.closeEntry();
                }
            }
        }
    }

    /**
     * Compile {@code sources} (FQCN → source) against this JVM's classpath and write the
     * classes into {@code target}, a jar whose manifest carries {@code version}.
     */
    static void compiledJar(Path target, String version, Map<String, String> sources) throws IOException {
        Path work = Files.createTempDirectory(target.getParent(), "src-");
        Path srcDir = Files.createDirectories(work.resolve("src"));
        Path outDir = Files.createDirectories(work.resolve("classes"));
        List<String> args = new ArrayList<>(List.of(
                "-d", outDir.toString(),
                "-classpath", System.getProperty("java.class.path"),
                "-proc:none", "--release", "21"));
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Path file = srcDir.resolve(source.getKey().replace('.', '/') + ".java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue(), StandardCharsets.UTF_8);
            args.add(file.toString());
        }
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null) {
            throw new IllegalStateException("no system Java compiler — the ITs need a JDK, not a JRE");
        }
        ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
        int rc = javac.run(null, diagnostics, diagnostics, args.toArray(String[]::new));
        if (rc != 0) {
            throw new IllegalStateException("fixture did not compile:\n" + diagnostics);
        }
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Bot-Plugin-Version", version);
        try (OutputStream file = Files.newOutputStream(target);
             JarOutputStream out = new JarOutputStream(file, manifest);
             Stream<Path> classes = Files.walk(outDir)) {
            for (Path path : classes.sorted().toList()) {
                String name = outDir.relativize(path).toString().replace('\\', '/');
                if (name.isEmpty()) {
                    continue;
                }
                if (Files.isDirectory(path)) {
                    out.putNextEntry(new JarEntry(name + "/"));
                } else {
                    out.putNextEntry(new JarEntry(name));
                    Files.copy(path, out);
                }
                out.closeEntry();
            }
        }
        deleteRecursively(work);
    }

    /** A second {@code @StrategyImpl("RANDOM")}: a D-10 duplicate key. */
    static final Map<String, String> DUPLICATE_RANDOM = Map.of(
            "com.vingame.bot.domain.bot.strategy.itfixture.DuplicateRandomStrategy", """
            package com.vingame.bot.domain.bot.strategy.itfixture;

            import com.vingame.bot.domain.bot.strategy.BetContext;
            import com.vingame.bot.domain.bot.strategy.BetDecision;
            import com.vingame.bot.domain.bot.strategy.BettingStrategy;
            import com.vingame.bot.domain.bot.strategy.RoundResult;
            import com.vingame.bot.domain.bot.strategy.StrategyImpl;
            import java.util.Optional;
            import org.springframework.context.annotation.Scope;
            import org.springframework.stereotype.Component;

            @Component
            @Scope("prototype")
            @StrategyImpl("RANDOM")
            public class DuplicateRandomStrategy implements BettingStrategy {
                @Override public void onRoundEnd(RoundResult result) { }
                @Override public Optional<BetDecision> decide(BetContext ctx) { return Optional.empty(); }
            }
            """);

    /** A singleton whose constructor throws: the context refresh itself fails. */
    static final Map<String, String> EXPLODING_BEAN = Map.of(
            "com.vingame.bot.domain.bot.strategy.itfixture.ExplodingBean", """
            package com.vingame.bot.domain.bot.strategy.itfixture;

            import org.springframework.stereotype.Component;

            @Component
            public class ExplodingBean {
                public ExplodingBean() {
                    throw new IllegalStateException("fixture bean refuses to construct");
                }
            }
            """);

    static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
