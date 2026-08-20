package com.vingame.bot.infrastructure.logging;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.ThreadContext;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AsyncAppender;
import org.apache.logging.log4j.core.appender.RollingFileAppender;
import org.apache.logging.log4j.core.appender.rolling.DefaultRolloverStrategy;
import org.apache.logging.log4j.core.appender.rolling.action.Action;
import org.apache.logging.log4j.core.appender.rolling.action.DeleteAction;
import org.apache.logging.log4j.core.appender.rolling.action.IfAccumulatedFileSize;
import org.apache.logging.log4j.core.appender.rolling.action.IfAny;
import org.apache.logging.log4j.core.appender.rolling.action.IfFileName;
import org.apache.logging.log4j.core.appender.rolling.action.IfLastModified;
import org.apache.logging.log4j.core.appender.rolling.action.PathCondition;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.ConfigurationSource;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.properties.PropertiesConfigurationFactory;
import org.apache.logging.log4j.status.StatusData;
import org.apache.logging.log4j.status.StatusListener;
import org.apache.logging.log4j.status.StatusLogger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LOG_VOLUME_TIERING Phase 4 (AD-22 … AD-27) — start the <b>shipped</b>
 * {@code log4j2.properties} in a real {@link LoggerContext} and assert where events
 * actually land.
 * <p>
 * <b>Why this exists.</b> Every other guard in this feature reads the properties files as
 * <em>text</em>: {@link Log4j2TwinConfigTest} diffs the two shipped copies,
 * {@link Log4j2TestConfigShapeTest} compares <em>keys</em> against the test copy,
 * {@link AsyncQueuePolicyTest} and {@link EvidenceRetentionEscapeTest} assert individual
 * values. None of them starts the file. So a <em>value-level</em> defect — a layout pattern
 * that does not compile, a {@code Delete} element whose {@code .type} is misspelled, an
 * {@code appenderRef} naming an appender that does not exist, a missing
 * {@code .type = AppenderRef} — passes the whole build and fails at container start. On
 * log4j-core 2.24.1 that failure is not soft: {@code AsyncAppender.start()} throws when its
 * referenced appender is unavailable, the exception propagates out of
 * {@code LoggerContext.start()}, and bot-manager does not come up at all. On Bot-1 that is
 * an outage, discovered by a human reading {@code docker compose logs}.
 * <p>
 * The <em>test</em> copy ({@code log4j2-test.properties}) is started by every
 * Spring-context test in this module and therefore is exercised — but it is deliberately
 * <b>not</b> the shipped file: it redirects both tracks off {@code /app} and drops both
 * {@code Delete} blocks, which is precisely the region a value-level typo would hide in.
 * <p>
 * <b>What this test does.</b> Rewrites the container path {@code /app/logs} in each shipped
 * twin to a directory under {@code target/}, starts a <em>private</em> {@code LoggerContext}
 * against the rewritten copy (never {@code LogManager}'s, so no other test's logging is
 * disturbed), emits one event per interesting logger, stops the context to flush, and reads
 * the two files back. That makes the routing contract of Phase 4 — and not merely its
 * spelling — a build-time assertion:
 * <ul>
 *   <li>{@code com.vingame.websocketparser} reaches track 2 <b>only</b> (AD-23);</li>
 *   <li>DEBUG never reaches track 1, even with the application logger at DEBUG the way
 *       staging runs it (AD-24 — this is what keeps DEBUG out of Loki);</li>
 *   <li>the console carries no ws-parser line (AD-27);</li>
 *   <li>an application INFO line appears in <b>both</b> files, so the detail track is
 *       self-contained (AD-27).</li>
 * </ul>
 */
@DisplayName("AD-22..AD-27 — the shipped log4j2.properties starts, and events land on the right track")
class ShippedLog4j2ConfigRoutingTest {

    /** The container path both shipped twins are written against. */
    private static final String CONTAINER_LOGS = "/app/logs";

    private static final List<Path> MOUNTED_CANDIDATES = List.of(
            Path.of("logging", "log4j2.properties"),
            Path.of("..", "logging", "log4j2.properties"));

    private static final List<Path> IN_JAR_CANDIDATES = List.of(
            Path.of("src", "main", "resources", "log4j2.properties"),
            Path.of("bot-app", "src", "main", "resources", "log4j2.properties"));

    // Unique per-event markers. Distinct strings rather than levels alone, so an
    // assertion cannot be satisfied by some unrelated line that happens to be present.
    private static final String APP_INFO = "qa-marker-app-info";
    private static final String APP_DEBUG = "qa-marker-app-debug";
    private static final String WS_CLIENT = "qa-marker-wsparser-client";
    private static final String WS_AUTH = "qa-marker-wsparser-auth";
    private static final String ROOT_INFO = "qa-marker-root-info";
    private static final String GROUP_ID = "qa-group-42";

    private static final String APP_LOGGER = "com.vingame.bot";
    private static final String LIBRARY_LOGGER = "com.vingame.websocketparser";
    /** log4j2 spells the root LoggerConfig's name as the empty string. */
    private static final String ROOT_LOGGER = "";

    /** Every appender the shipped graph names, checked for "started" while it still was. */
    private static final List<String> DECLARED_APPENDERS = List.of(
            "ConsoleAppender", "RollingFileAppender", "AsyncRolling",
            "DetailFileAppender", "AsyncDetail");

    /**
     * One logger's resolved wiring, captured while the context is still up. Both
     * {@code LoggerConfig.stop()} and {@code LoggerContext.stop()} clear what they own — the
     * appender map empties and the configuration is swapped for a {@code NullConfiguration}
     * — so anything read afterwards is the teardown's answer, not the configuration's.
     */
    private record LoggerWiring(String name, boolean additive, List<String> appenders) {
    }

    /** What one shipped twin produced when it was actually run. */
    private record Routed(String where, Configuration configuration, List<String> statusErrors,
                          List<String> unstartedAppenders, Map<String, LoggerWiring> loggers,
                          String track1, String track2, Path detailDirectory) {
    }

    private static final List<Routed> ROUTED = new ArrayList<>();

    @BeforeAll
    static void runBothShippedTwins() throws IOException {
        // Both, deliberately. The mounted copy is what LOGGING_CONFIG selects on the box;
        // the in-jar copy is the fallback that runs when the mount is missing, and a host
        // running a broken fallback is exactly the "the app went quiet" scenario AD-2 warns
        // about. Log4j2TwinConfigTest proves they are identical below the header fence —
        // this proves that whichever one is in effect, it starts.
        ROUTED.add(run(resolve(MOUNTED_CANDIDATES, "logging/log4j2.properties"), "mounted"));
        ROUTED.add(run(resolve(IN_JAR_CANDIDATES, "in-jar log4j2.properties"), "in-jar"));
    }

    @AfterAll
    static void forgetContexts() {
        ROUTED.clear();
    }

    // ------------------------------------------------------------------ the run

    private static Routed run(Path properties, String label) throws IOException {
        Path root = Path.of("target", "log4j2-routing", label).toAbsolutePath();
        deleteRecursively(root);
        Files.createDirectories(root);

        String shipped = Files.readString(properties, StandardCharsets.UTF_8);
        Path rewritten = root.resolve("log4j2.properties");
        Files.writeString(rewritten,
                shipped.replace(CONTAINER_LOGS, root.resolve("logs").toString()),
                StandardCharsets.UTF_8);

        // Non-vacuity, both directions: the shipped file really does write into the
        // container path (so this rewrite is doing something), and after the rewrite no
        // VALUE names /app any more — a value that survived would make an appender fail to
        // start here for a reason that has nothing to do with the configuration's health.
        // Comments are exempt: the header legitimately names /app/config/log4j2.properties.
        assertThat(valuesOf(properties))
                .as("%s: the shipped config must write both tracks into %s", label, CONTAINER_LOGS)
                .anyMatch(value -> value.startsWith(CONTAINER_LOGS));
        assertThat(valuesOf(rewritten))
                .as("%s: the rewritten copy must name no container path", label)
                .noneMatch(value -> value.contains("/app/"));

        StatusCollector collector = new StatusCollector();
        StatusLogger.getLogger().registerListener(collector);
        LoggerContext context = new LoggerContext("qa-routing-" + label);
        List<String> unstarted = new ArrayList<>();
        Map<String, LoggerWiring> wiring = new LinkedHashMap<>();
        // Held outside the try: LoggerContext.stop() swaps in a NullConfiguration, so
        // asking the context for its configuration afterwards yields an empty graph and
        // every structural assertion below would pass or fail for the wrong reason.
        Configuration configuration;
        try {
            try (InputStream in = Files.newInputStream(rewritten)) {
                configuration = new PropertiesConfigurationFactory()
                        .getConfiguration(context, new ConfigurationSource(in, rewritten.toFile()));
            }
            assertThat(configuration)
                    .as("%s: log4j2 could not build a Configuration from the shipped file at all",
                            label)
                    .isNotNull();
            // Throws on a mis-built appender graph — which is the single largest
            // silent-failure class this test exists to close.
            context.start(configuration);

            // Recorded while the context is up: after stop() every appender reports
            // !isStarted() and the check would pass for the wrong reason.
            for (String name : DECLARED_APPENDERS) {
                Appender appender = configuration.getAppender(name);
                if (appender == null) {
                    unstarted.add(name + " (absent from the built configuration)");
                } else if (!appender.isStarted()) {
                    unstarted.add(name + " (built but never started)");
                }
            }

            emitOneEventPerInterestingLogger(context);

            for (String logger : List.of(APP_LOGGER, LIBRARY_LOGGER, ROOT_LOGGER)) {
                LoggerConfig resolved = configuration.getLoggerConfig(logger);
                wiring.put(logger, new LoggerWiring(resolved.getName(), resolved.isAdditive(),
                        List.copyOf(resolved.getAppenders().keySet())));
            }
        } finally {
            // Flushes the async queues and closes both files. immediateFlush = false on
            // track 2 means reading before this would under-report.
            context.stop();
            StatusLogger.getLogger().removeListener(collector);
        }

        return new Routed(label, configuration, collector.errors(), unstarted, wiring,
                read(root.resolve("logs").resolve("console.log")),
                read(root.resolve("logs").resolve("detail").resolve("detail.log")),
                root.resolve("logs").resolve("detail"));
    }

    private static void emitOneEventPerInterestingLogger(LoggerContext context) {
        ThreadContext.put("botGroupId", GROUP_ID);
        try {
            context.getLogger("com.vingame.bot.qa.RoutingProbe").info(APP_INFO);

            // The staging posture: BOT_LOG_LEVEL=DEBUG, applied the way Spring Boot's
            // Log4J2LoggingSystem.setLogLevel applies it — in place, on the LoggerConfig
            // that exists by exact name. Without this the DEBUG assertion below would be
            // vacuous: the shipped level is `info`, so no DEBUG event would exist to
            // misroute, and P4-3 makes exactly the same point about running it on a
            // prod-like instance.
            context.getConfiguration().getLoggerConfig("com.vingame.bot").setLevel(Level.DEBUG);
            context.updateLoggers();
            context.getLogger("com.vingame.bot.qa.RoutingProbe").debug(APP_DEBUG);

            context.getLogger("com.vingame.websocketparser.VingameWebSocketClient").info(WS_CLIENT);
            context.getLogger("com.vingame.websocketparser.auth.AuthClient").info(WS_AUTH);
            // A third-party root logger, standing in for Spring's `Started Starter in …`:
            // AD-27 keeps root wired to the console and to track 1 on purpose.
            context.getLogger("org.springframework.boot.QaStartupProbe").info(ROOT_INFO);
        } finally {
            ThreadContext.clearAll();
        }
    }

    // ------------------------------------------------------------------ assertions

    @Test
    @DisplayName("the shipped configuration starts cleanly — no ConfigurationException, no status errors")
    void theShippedConfigurationStarts() {
        for (Routed routed : ROUTED) {
            assertThat(routed.statusErrors())
                    .as("%s: log4j2's status logger reported an error while building the "
                            + "shipped appender graph. Nothing else in the build starts this "
                            + "file, so an error here is one that would first appear as "
                            + "bot-manager failing to come up on the box.", routed.where())
                    .isEmpty();
            assertThat(routed.unstartedAppenders())
                    .as("%s: every appender the shipped graph names must exist AND have "
                            + "started. An AsyncAppender whose referenced appender is absent "
                            + "does not degrade — it throws out of LoggerContext.start() and "
                            + "the JVM comes up with no logger at all.", routed.where())
                    .isEmpty();

            Object trackTwoAsync = routed.configuration().getAppender("AsyncDetail");
            Object trackTwoFile = routed.configuration().getAppender("DetailFileAppender");
            assertThat(trackTwoAsync)
                    .as("%s: track 2's async wrapper. A missing `.type = AppenderRef` line "
                            + "makes this null and takes the JVM's whole logger with it.",
                            routed.where())
                    .isInstanceOf(AsyncAppender.class);
            assertThat(trackTwoFile)
                    .as("%s: track 2's rolling file", routed.where())
                    .isInstanceOf(RollingFileAppender.class);
        }
    }

    @Test
    @DisplayName("com.vingame.websocketparser reaches track 2 and nothing else (AD-23)")
    void theLibraryLandsInTrackTwoOnly() {
        for (Routed routed : ROUTED) {
            assertThat(routed.track2())
                    .as("%s: the library's own lines are the whole point of track 2 — 4,402 "
                            + "lines per 300 s on a 155-bot fleet, 98.7%% of INFO volume",
                            routed.where())
                    .contains(WS_CLIENT)
                    .contains(WS_AUTH);
            assertThat(routed.track1())
                    .as("%s: not one ws-parser line may reach console.log, because console.log "
                            + "is the only thing promtail ships to Loki. If additivity = false "
                            + "is missing, this is where 98.7%% of INFO volume walks back in.",
                            routed.where())
                    .doesNotContain(WS_CLIENT)
                    .doesNotContain(WS_AUTH)
                    .doesNotContain("websocketparser");

            // The console is proved from the resolved graph rather than by capturing
            // System.out: log4j2 caches one OutputStreamManager per console target for the
            // whole JVM, so a private context's ConsoleAppender may write through the
            // manager the module's main LoggerContext already created. The graph is the
            // stronger statement anyway — it holds for every event, not for a sample.
            LoggerWiring library = routed.loggers().get(LIBRARY_LOGGER);
            assertThat(library.name())
                    .as("%s: the LoggerConfig must exist BY EXACT NAME — a lookup that walks "
                            + "up to root means the block is missing or misspelled, and Spring "
                            + "Boot's setLogLevel would then create an appender-less config",
                            routed.where())
                    .isEqualTo("com.vingame.websocketparser");
            assertThat(library.additive())
                    .as("%s: additivity = false is the mechanical fix for Finding 1",
                            routed.where())
                    .isFalse();
            assertThat(library.appenders())
                    .as("%s: ws-parser must reach AsyncDetail and nothing else — not the "
                            + "console (`docker logs` is capped 50m x 5 and the AUTH flood "
                            + "would dominate it), not AsyncRolling (Loki)", routed.where())
                    .containsExactly("AsyncDetail");
        }
    }

    @Test
    @DisplayName("DEBUG never reaches track 1, even with the app logger at DEBUG (AD-24)")
    void debugNeverReachesTheTrackLokiIngests() {
        for (Routed routed : ROUTED) {
            assertThat(routed.track1())
                    .as("%s: the ThresholdFilter on AsyncRolling is the single thing keeping "
                            + "DEBUG out of Loki — including Phase 2's scoped-debug ACCEPT, "
                            + "which beats the level check but not an appender's own filter. "
                            + "This assertion is the build's copy of verification P4-3.",
                            routed.where())
                    .doesNotContain(APP_DEBUG);
            assertThat(routed.track2())
                    .as("%s: and the DEBUG line must still exist somewhere, or the assertion "
                            + "above passes because the app went quiet rather than because the "
                            + "filter works (P4-3 makes both halves mandatory for the same "
                            + "reason)", routed.where())
                    .contains(APP_DEBUG);
            assertThat(routed.track2())
                    .as("%s: track 2 renders MDC as [botGroupId/botId/gameType] under "
                            + "PatternLayout, which is what AD-24's drill-in grep "
                            + "`grep \"\\[<GID>/\"` depends on — NOT as JSON fields",
                            routed.where())
                    .contains("[" + GROUP_ID + "/");
        }
    }

    @Test
    @DisplayName("an application INFO line appears in both files, so track 2 is self-contained (AD-27)")
    void applicationInfoIsWrittenToBothTracks() {
        for (Routed routed : ROUTED) {
            assertThat(routed.track1())
                    .as("%s: our own INFO is what Loki and Grafana see", routed.where())
                    .contains(APP_INFO);
            assertThat(routed.track2())
                    .as("%s: and it must ALSO be in the detail file, or an incident cannot be "
                            + "read from one file — this is the misroute P4-4's second half "
                            + "looks for (`grep -c FleetRollupLogger logs/detail/detail.log`)",
                            routed.where())
                    .contains(APP_INFO);

            assertThat(routed.track1())
                    .as("%s: root stays wired to track 1 on purpose (AD-27) — losing Spring's "
                            + "startup lines would break the releaser's smoke test",
                            routed.where())
                    .contains(ROOT_INFO);
            assertThat(routed.track2())
                    .as("%s: and to track 2, for the same self-containment reason",
                            routed.where())
                    .contains(ROOT_INFO);

            // The resolved form of what AsyncQueuePolicyTest asserts as text. A ref naming
            // an appender that does not exist resolves to nothing at all, and the text
            // assertion cannot tell that apart from a working one.
            assertThat(routed.loggers().get(APP_LOGGER).appenders())
                    .as("%s: com.vingame.bot must reach all three appenders", routed.where())
                    .containsExactlyInAnyOrder("ConsoleAppender", "AsyncRolling", "AsyncDetail");
            assertThat(routed.loggers().get(APP_LOGGER).additive())
                    .as("%s: additivity = false, or every application line double-writes "
                            + "through root as well", routed.where())
                    .isFalse();
            assertThat(routed.loggers().get(ROOT_LOGGER).appenders())
                    .as("%s: and root keeps the same three (AD-27)", routed.where())
                    .containsExactlyInAnyOrder("ConsoleAppender", "AsyncRolling", "AsyncDetail");
        }
    }

    @Test
    @DisplayName("the layouts are the ones each track's consumer needs (AD-25)")
    void eachTrackIsWrittenInItsOwnLayout() {
        for (Routed routed : ROUTED) {
            String firstTrack1Line = routed.track1().lines().findFirst().orElse("");
            assertThat(firstTrack1Line)
                    .as("%s: track 1 is JsonTemplateLayout — promtail's json stage parses it "
                            + "and promotes level/botGroupId to Loki labels", routed.where())
                    .startsWith("{")
                    .contains("\"level\":\"INFO\"");

            assertThat(routed.track2())
                    .as("%s: track 2 is PatternLayout and keeps the FULL logger name (%%c), so "
                            + "a misroute between the tracks is greppable", routed.where())
                    .contains("com.vingame.websocketparser.VingameWebSocketClient");
            assertThat(routed.track2().lines().anyMatch(line -> line.startsWith("{")))
                    .as("%s: no JSON in track 2 — JSON costs ~400 B/line against ~250 B, and it "
                            + "would drag the library's ANSI escapes back into a structured "
                            + "`message` field where they are corruption rather than colour",
                            routed.where())
                    .isFalse();
            assertThat(routed.track2())
                    .as("%s: %%-5level pads to five characters, which every verification grep "
                            + "against track 2 relies on (`grep -E ' (DEBUG|TRACE) '`)",
                            routed.where())
                    .contains(" DEBUG ");
        }
    }

    @Test
    @DisplayName("log4j2 creates logs/detail itself, so a missing directory is not an outage")
    void theDetailDirectoryIsCreatedByTheAppender() {
        for (Routed routed : ROUTED) {
            // The deploy note tells the releaser to `mkdir -p logs/detail` before
            // `docker compose up`, precisely because an AsyncAppender whose target cannot
            // start throws at context start. Nothing here pre-creates the directory, so
            // this proves the mkdir is belt-and-braces rather than a load-bearing manual
            // step nobody will remember on the next box.
            assertThat(routed.detailDirectory())
                    .as("%s: FileManager must create track 2's parent directory", routed.where())
                    .isDirectory();
        }
    }

    @Test
    @DisplayName("both tracks' Delete blocks build into real DeleteActions, anchored where they cannot reach each other")
    void bothDeleteBlocksResolveToTheAnchorsTheClaim() {
        for (Routed routed : ROUTED) {
            DeleteAction track1 = deleteActionOf(routed, "RollingFileAppender");
            DeleteAction track2 = deleteActionOf(routed, "DetailFileAppender");

            // EvidenceRetentionEscapeTest asserts these as TEXT. Here they are the objects
            // log4j2 actually built: a `Delete` whose element type is misspelled produces
            // no action at all, and the text assertion cannot tell the difference.
            assertThat(track1.getBasePath().toString())
                    .as("%s: track 1's sweeper stays at the logs root", routed.where())
                    .endsWith("logs");
            assertThat(track1.getMaxDepth())
                    .as("%s: maxDepth 1 is what keeps track 1's sweeper out of logs/evidence/ "
                            + "and logs/detail/", routed.where())
                    .isEqualTo(1);
            assertThat(globOf(track1))
                    .as("%s: and its glob is the second, independent guard", routed.where())
                    .isEqualTo("glob:console-*.log");

            assertThat(track2.getBasePath().toString())
                    .as("%s: track 2's sweeper is anchored INSIDE logs/detail — at the logs "
                            + "root it would walk the directory holding the promoted evidence",
                            routed.where())
                    .endsWith("logs" + java.io.File.separator + "detail");
            assertThat(track2.getMaxDepth()).as("%s: track 2 maxDepth", routed.where()).isEqualTo(1);
            assertThat(globOf(track2))
                    .as("%s: track 2's glob also excludes the live detail.log, which is why "
                            + "ifAccumulatedFileSize counts archives only", routed.where())
                    .isEqualTo("glob:detail-*.log");

            assertThat(ageDaysOf(track2))
                    .as("%s: AD-26 — 12 h, the age half of track 2's retention", routed.where())
                    .isEqualTo(0.5);
            assertThat(thresholdOf(track2))
                    .as("%s: AD-26 — 10 GB, the half that binds above ~2,000 bots",
                            routed.where())
                    .isEqualTo(10L * 1024 * 1024 * 1024);
            assertThat(ageDaysOf(track1))
                    .as("%s: AD-26 raised track 1 from 7 d to 14 d once P0-6 had measured the "
                            + "disk; it now aligns with logs/evidence/'s aggregate age",
                            routed.where())
                    .isEqualTo(14.0);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static DeleteAction deleteActionOf(Routed routed, String appenderName) {
        RollingFileAppender appender =
                (RollingFileAppender) routed.configuration().getAppender(appenderName);
        assertThat(appender).as("%s: %s", routed.where(), appenderName).isNotNull();
        assertThat(appender.getManager().getRolloverStrategy())
                .as("%s: %s must use DefaultRolloverStrategy", routed.where(), appenderName)
                .isInstanceOf(DefaultRolloverStrategy.class);
        List<Action> actions =
                ((DefaultRolloverStrategy) appender.getManager().getRolloverStrategy())
                        .getCustomActions();
        assertThat(actions)
                .as("%s: %s declares a Delete, so log4j2 must have built exactly one custom "
                        + "rollover action from it. Zero means the element did not resolve — "
                        + "the retention this whole feature rests on would then never run, "
                        + "silently.", routed.where(), appenderName)
                .hasSize(1);
        assertThat(actions.get(0)).isInstanceOf(DeleteAction.class);
        return (DeleteAction) actions.get(0);
    }

    private static String globOf(DeleteAction action) {
        return condition(action.getPathConditions(), IfFileName.class).getSyntaxAndPattern();
    }

    private static double ageDaysOf(DeleteAction action) {
        IfAny any = condition(action.getPathConditions(), IfAny.class);
        IfLastModified age = condition(Arrays.asList(any.getDeleteFilters()), IfLastModified.class);
        return age.getAge().toMillis() / 86_400_000.0;
    }

    private static long thresholdOf(DeleteAction action) {
        IfAny any = condition(action.getPathConditions(), IfAny.class);
        return condition(Arrays.asList(any.getDeleteFilters()), IfAccumulatedFileSize.class)
                .getThresholdBytes();
    }

    private static <T extends PathCondition> T condition(List<PathCondition> conditions,
                                                         Class<T> type) {
        return conditions.stream()
                .filter(type::isInstance)
                .map(type::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no " + type.getSimpleName() + " among " + conditions
                                + " — the Delete block did not resolve as written"));
    }

    private static List<String> valuesOf(Path file) {
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return properties.stringPropertyNames().stream()
                .map(properties::getProperty)
                .toList();
    }

    private static Path resolve(List<Path> candidates, String what) {
        Path path = candidates.stream().filter(Files::isRegularFile).findFirst().orElse(null);
        // Absent only in a module-only build from an unusual CWD; same posture as
        // Log4j2TwinConfigTest and Log4j2TestConfigShapeTest.
        Assumptions.assumeTrue(path != null,
                what + " not found from " + Path.of("").toAbsolutePath());
        return path;
    }

    private static String read(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure)
                    throws IOException {
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Collects log4j2's own ERROR-level status output for the current thread while the
     * shipped configuration is being built. That is where a component that failed to
     * resolve — a misspelled {@code .type}, an unparseable layout pattern — announces
     * itself; the appender graph then comes up degraded rather than absent, which is the
     * quieter half of the failure this class exists to catch.
     */
    private static final class StatusCollector implements StatusListener {

        private final List<StatusData> collected = Collections.synchronizedList(new ArrayList<>());
        private final String thread = Thread.currentThread().getName();

        @Override
        public void log(StatusData data) {
            // Other threads' status output is not ours to judge; surefire runs test
            // classes in one thread, but a Spring context cached by another test may still
            // own background threads that log.
            if (thread.equals(data.getThreadName())) {
                collected.add(data);
            }
        }

        @Override
        public Level getStatusLevel() {
            return Level.ERROR;
        }

        @Override
        public void close() {
            // Nothing to release.
        }

        List<String> errors() {
            synchronized (collected) {
                return collected.stream()
                        .filter(data -> data.getLevel().isMoreSpecificThan(Level.ERROR))
                        .map(data -> data.getLevel() + " " + data.getMessage().getFormattedMessage()
                                + (data.getThrowable() == null ? ""
                                : " / " + data.getThrowable()))
                        .toList();
            }
        }
    }
}
