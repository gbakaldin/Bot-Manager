package com.vingame.bot.infrastructure.plugin;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Stream;

/**
 * Chooses and loads the plugin bundle of isolated mode (PLUGIN_HOT_RELOAD_3_4 Phase 4b,
 * D-10, D-11, D-13).
 *
 * <h2>Layout and order (D-11)</h2>
 * {@code <dir>/<anything>/*.jar}: each subdirectory is one candidate bundle, and a
 * subdirectory whose name starts with {@code _} or {@code .} is ignored — the operator's
 * disable / rollback switch. Candidates are tried in this order, and the first valid one
 * wins:
 * <ol>
 *   <li>every bundle in {@code bot.plugins.dir} (the read-only mount), version descending;</li>
 *   <li>every bundle in {@code bot.plugins.builtin-dir} (baked into the image), version
 *       descending.</li>
 * </ol>
 * A missing directory counts as empty. The loader never writes to either.
 *
 * <h2>Validity</h2>
 * A candidate is valid when it has at least one jar, every jar's manifest carries the same
 * non-blank {@code Bot-Plugin-Version} (D-7 — that value <em>is</em> the bundle version;
 * the directory name is cosmetic), {@link IsolatedPluginBundle#open} loads it, and
 * {@link PluginRegistries#build} accepts it (D-10: a duplicate key or any other registry
 * misconfiguration rejects the bundle as a whole).
 *
 * <h2>What it logs (tier 1, one-shot)</h2>
 * <ul>
 *   <li>one ERROR per rejected candidate: {@code plugin bundle <path> rejected: <reason>};</li>
 *   <li>one WARN when the running bundle came from the builtin directory: the mount had
 *       nothing valid, which is safe (fallback, not failure) but usually means a missed
 *       ship;</li>
 *   <li>nothing on success — the {@code plugin runtime:} boot line names the source.</li>
 * </ul>
 * No candidate valid in either directory throws, naming both.
 * <p>
 * A rejected candidate is closed (its loader released) before the next one is tried, and a
 * failure to close it is attached to the rejection, never in its place (review-4a).
 */
@Slf4j
public final class IsolatedPluginBundleLoader {

    private final Path pluginsDir;
    private final Path builtinDir;

    /**
     * @param pluginsDir {@code bot.plugins.dir}, the mounted bundles (D-11 step 1).
     * @param builtinDir {@code bot.plugins.builtin-dir}, the image's own bundles (step 2).
     */
    public IsolatedPluginBundleLoader(Path pluginsDir, Path builtinDir) {
        this.pluginsDir = Objects.requireNonNull(pluginsDir, "pluginsDir");
        this.builtinDir = Objects.requireNonNull(builtinDir, "builtinDir");
    }

    /**
     * Try every candidate in D-11's order and return the registries of the first valid one.
     * Nothing is logged for the accepted bundle here; its registry lines are the caller's
     * {@link PluginRegistries#logInitialized()}.
     *
     * @throws IllegalStateException if no candidate in either directory is valid.
     */
    public PluginRegistries load() {
        PluginRegistries mounted = firstValid(pluginsDir);
        if (mounted != null) {
            return mounted;
        }
        PluginRegistries builtin = firstValid(builtinDir);
        if (builtin != null) {
            log.warn("plugin bundle: no valid bundle in {} — running the image's built-in bundle {} from {}",
                    pluginsDir, builtin.bundle().version(), builtin.bundle().source());
            return builtin;
        }
        throw new IllegalStateException("no valid plugin bundle in " + pluginsDir + " or "
                + builtinDir + " — every candidate was rejected (see the ERROR lines above),"
                + " or there were none");
    }

    // ------------------------------------------------------------------ selection

    private PluginRegistries firstValid(Path dir) {
        for (Candidate candidate : candidates(dir)) {
            if (candidate.rejection != null) {
                reject(candidate.directory, candidate.rejection, null);
                continue;
            }
            PluginRegistries accepted = tryLoad(candidate);
            if (accepted != null) {
                return accepted;
            }
        }
        return null;
    }

    private PluginRegistries tryLoad(Candidate candidate) {
        IsolatedPluginBundle bundle;
        try {
            bundle = IsolatedPluginBundle.open(candidate.directory, candidate.version,
                    candidate.jarFiles, candidate.jars);
        } catch (RuntimeException | LinkageError e) {
            reject(candidate.directory, describe(e), e);
            return null;
        }
        try {
            return PluginRegistries.build(bundle);
        } catch (RuntimeException | LinkageError e) {
            try {
                bundle.close();
            } catch (RuntimeException | Error closeFailure) {
                e.addSuppressed(closeFailure);
            }
            reject(candidate.directory, describe(e), e);
            return null;
        }
    }

    private static void reject(Path directory, String reason, Throwable cause) {
        if (cause == null) {
            log.error("plugin bundle {} rejected: {}", directory, reason);
        } else {
            log.error("plugin bundle {} rejected: {}", directory, reason, cause);
        }
    }

    private static String describe(Throwable e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.toString() : message;
    }

    // ------------------------------------------------------------------ discovery

    /**
     * The candidates of one directory: invalid ones first (in name order, so every broken
     * bundle is reported even when a valid one wins), then valid ones by version descending
     * (directory name descending on a tie).
     */
    List<Candidate> candidates(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<Candidate> invalid = new ArrayList<>();
        List<Candidate> valid = new ArrayList<>();
        try (Stream<Path> children = Files.list(dir)) {
            for (Path child : children.sorted().toList()) {
                String name = child.getFileName().toString();
                if (!Files.isDirectory(child) || name.startsWith("_") || name.startsWith(".")) {
                    continue;
                }
                Candidate candidate = inspect(child);
                (candidate.rejection == null ? valid : invalid).add(candidate);
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot list plugin directory " + dir, e);
        }
        valid.sort(Comparator.comparing((Candidate c) -> c.version)
                .thenComparing(c -> c.directory.getFileName().toString())
                .reversed());
        List<Candidate> ordered = new ArrayList<>(invalid);
        ordered.addAll(valid);
        return ordered;
    }

    private static Candidate inspect(Path bundleDir) {
        List<Path> jarFiles;
        try (Stream<Path> files = Files.list(bundleDir)) {
            jarFiles = files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return Candidate.invalid(bundleDir, "cannot list it: " + e);
        }
        if (jarFiles.isEmpty()) {
            return Candidate.invalid(bundleDir, "no jars");
        }
        Set<String> versions = new LinkedHashSet<>();
        List<String> perJar = new ArrayList<>();
        List<PluginJar> jars = new ArrayList<>();
        for (Path jar : jarFiles) {
            String name = jar.getFileName().toString();
            String version;
            try {
                version = manifestVersion(jar);
                jars.add(new PluginJar(name, sha256(jar)));
            } catch (IOException e) {
                return Candidate.invalid(bundleDir, "cannot read " + name + ": " + e);
            }
            if (version == null || version.isBlank()) {
                return Candidate.invalid(bundleDir, name + " has no "
                        + IsolatedPluginBundle.VERSION_ATTRIBUTE + " in its manifest");
            }
            versions.add(version.trim());
            perJar.add(name + "=" + version.trim());
        }
        if (versions.size() != 1) {
            return Candidate.invalid(bundleDir, "its jars carry different "
                    + IsolatedPluginBundle.VERSION_ATTRIBUTE + " values " + perJar
                    + " — a bundle is only valid if they agree (D-7)");
        }
        return new Candidate(bundleDir, versions.iterator().next(), jarFiles, jars, null);
    }

    private static String manifestVersion(Path jar) throws IOException {
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            Manifest manifest = jarFile.getManifest();
            return manifest == null ? null
                    : manifest.getMainAttributes().getValue(IsolatedPluginBundle.VERSION_ATTRIBUTE);
        }
    }

    static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory on every JVM", e);
        }
        try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
            in.transferTo(java.io.OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** One bundle directory, inspected but not yet loaded. */
    record Candidate(Path directory, String version, List<Path> jarFiles, List<PluginJar> jars,
                     String rejection) {

        static Candidate invalid(Path directory, String rejection) {
            return new Candidate(directory, null, List.of(), List.of(), rejection);
        }
    }
}
