package com.vingame.bot.infrastructure.logging;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LOG_VOLUME_TIERING — the <b>third</b> copy of the log configuration,
 * {@code bot-app/src/test/resources/log4j2-test.properties}, must keep the same logger
 * shape as the two shipped twins.
 * <p>
 * {@link Log4j2TwinConfigTest} pins the two shipped copies against each other. It cannot see
 * this one, and this one is the copy every test in the build actually runs against — log4j2
 * prefers {@code log4j2-test.properties} on the test classpath. It exists for a single
 * mechanical reason: Phase 0 wrapped the rolling appender in an {@code AsyncAppender}, and
 * {@code AsyncAppender.start()} <em>throws</em> when its referenced appender is unavailable,
 * so the shipped {@code /app/logs/console.log} path kills every Spring-context test on any
 * machine where {@code /app} cannot be created.
 * <p>
 * <b>Why that needs a guard.</b> Two of this feature's most load-bearing tests assert
 * against the live LoggerConfig produced by <em>this</em> file:
 * <ul>
 *   <li>{@link LoggingLevelOverrideTest} proves AD-7 — that {@code logging.level.*} mutates
 *       the existing {@code com.vingame.bot} LoggerConfig <em>in place</em> and preserves
 *       {@code additivity = false} plus both appenderRefs. That proof is only about
 *       production if the shape it is proved against is production's shape. Rename the
 *       logger here, or drop an appenderRef, and the test goes on passing while proving
 *       something about a configuration nobody ships;</li>
 *   <li>{@link ScopedDebugFilterInstallationTest} proves AD-9/AD-10 — that an {@code ACCEPT}
 *       beats the level gate — with the application logger at INFO. If this file drifted to
 *       {@code debug}, the DEBUG line would flow for a reason that has nothing to do with
 *       the filter, and the entire Phase 2 mechanism would be untested.</li>
 * </ul>
 * So: every key that defines the logger/appender <em>graph</em> must match the shipped
 * files, and the differences are enumerated rather than merely tolerated.
 */
@DisplayName("log4j2-test.properties keeps production's logger shape (only the file path differs)")
class Log4j2TestConfigShapeTest {

    private static final List<Path> IN_JAR_CANDIDATES = List.of(
            Path.of("src", "main", "resources", "log4j2.properties"),
            Path.of("bot-app", "src", "main", "resources", "log4j2.properties"));

    private static final List<Path> TEST_CANDIDATES = List.of(
            Path.of("src", "test", "resources", "log4j2-test.properties"),
            Path.of("bot-app", "src", "test", "resources", "log4j2-test.properties"));

    /**
     * The keys that define the graph the two tests above lean on: which loggers exist, at
     * what level, with what additivity, wired to which appenders, and what those appenders
     * are. Deliberately excludes the path/size/retention keys, which are the file's
     * documented reason to differ.
     */
    private static final List<String> SHAPE_KEYS = List.of(
            "rootLogger.level",
            "rootLogger.appenderRef.console.ref",
            "rootLogger.appenderRef.async.ref",
            "logger.app.name",
            "logger.app.level",
            "logger.app.additivity",
            "logger.app.appenderRef.console.ref",
            "logger.app.appenderRef.async.ref",
            "appender.console.type",
            "appender.console.name",
            "appender.console.layout.type",
            "appender.rolling.type",
            "appender.rolling.name",
            "appender.rolling.layout.type",
            "appender.rolling.layout.eventTemplateUri",
            "appender.async.type",
            "appender.async.name",
            // The gotcha the plan calls "the single most likely silent failure in Phase 0".
            // If the test config omitted it, the build would never exercise the spelling
            // production depends on.
            "appender.async.appenderRef.type",
            "appender.async.appenderRef.ref",
            "appender.async.blocking",
            // AD-24. The one filter that keeps DEBUG out of Loki. It is on the ASYNC
            // wrapper, not the rolling file, so the event is rejected before it takes a
            // queue slot; the test copy carries it so the build exercises the spelling.
            "appender.async.filter.threshold.type",
            "appender.async.filter.threshold.level",
            "appender.async.filter.threshold.onMatch",
            "appender.async.filter.threshold.onMismatch",
            "appender.rolling.policies.type",
            "appender.rolling.policies.time.type",
            "appender.rolling.policies.time.interval",
            "appender.rolling.policies.time.modulate",
            // ---- Phase 4: track 2, and the loggers that reach it (AD-22 .. AD-27) ----
            // Forgetting any of these in the test copy does not produce a nice failure:
            // AsyncAppender.start() throws when its target is unavailable, so every
            // Spring-context test in the module dies with ExceptionInInitializerError
            // before its first assertion, looking like a Spring problem.
            "rootLogger.appenderRef.detail.ref",
            "logger.app.appenderRef.detail.ref",
            "logger.wsparser.name",
            "logger.wsparser.level",
            "logger.wsparser.additivity",
            "logger.wsparser.appenderRef.detail.ref",
            "appender.detail.type",
            "appender.detail.name",
            "appender.detail.layout.type",
            "appender.detail.policies.time.interval",
            "appender.detail.policies.time.modulate",
            "appender.asyncdetail.type",
            "appender.asyncdetail.name",
            "appender.asyncdetail.appenderRef.type",
            "appender.asyncdetail.appenderRef.ref",
            // Deliberately the OPPOSITE of appender.async.blocking above (AD-25(4)).
            // Pinned here so the two files cannot drift apart on the single setting most
            // likely to be "corrected" by a reader who remembers Phase 0's Drift 1.
            "appender.asyncdetail.blocking");

    private static Path resolve(List<Path> candidates, String what) {
        Path path = candidates.stream().filter(Files::isRegularFile).findFirst().orElse(null);
        // Absent only in a module-only build from an unusual CWD; same posture as
        // Log4j2TwinConfigTest.
        Assumptions.assumeTrue(path != null,
                what + " not found from " + Path.of("").toAbsolutePath());
        return path;
    }

    private static Properties load(Path path) {
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            properties.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return properties;
    }

    private static Map<String, String> shapeOf(Properties properties) {
        Map<String, String> shape = new LinkedHashMap<>();
        for (String key : SHAPE_KEYS) {
            shape.put(key, properties.getProperty(key));
        }
        return shape;
    }

    @Test
    @DisplayName("the logger/appender graph is byte-identical to the shipped configuration")
    void theTestConfigDeclaresTheSameGraphAsProduction() {
        Properties shipped = load(resolve(IN_JAR_CANDIDATES, "log4j2.properties"));
        Properties test = load(resolve(TEST_CANDIDATES, "log4j2-test.properties"));

        assertThat(shapeOf(test))
                .as("bot-app/src/test/resources/log4j2-test.properties has drifted from the "
                        + "shipped log4j2.properties in a way that changes the logger graph. "
                        + "LoggingLevelOverrideTest (AD-7) and ScopedDebugFilterInstallationTest "
                        + "(AD-9/AD-10) both assert against the LoggerConfig this file builds — "
                        + "once the shape differs, they prove nothing about production.")
                .containsExactlyEntriesOf(shapeOf(shipped));
    }

    @Test
    @DisplayName("no shape key is silently absent from either file")
    void everyShapeKeyIsActuallyDeclared() {
        // containsExactlyEntriesOf above is satisfied by two identically-missing keys
        // (null == null), so the emptiness has to be ruled out separately — otherwise a
        // typo in SHAPE_KEYS would turn this whole class into a no-op.
        Properties shipped = load(resolve(IN_JAR_CANDIDATES, "log4j2.properties"));
        Properties test = load(resolve(TEST_CANDIDATES, "log4j2-test.properties"));

        assertThat(shapeOf(shipped)).as("shipped config").doesNotContainValue(null);
        assertThat(shapeOf(test)).as("test config").doesNotContainValue(null);
    }

    @Test
    @DisplayName("the application logger runs at INFO here too, which is what makes Phase 2 provable")
    void theApplicationLoggerIsAtInfo() {
        Properties test = load(resolve(TEST_CANDIDATES, "log4j2-test.properties"));

        assertThat(test.getProperty("logger.app.name")).isEqualTo("com.vingame.bot");
        assertThat(test.getProperty("logger.app.level"))
                .as("at DEBUG, ScopedDebugFilterInstallationTest's promoted line would flow for "
                        + "a reason that has nothing to do with the scoped-debug filter")
                .isEqualTo("info");
        assertThat(test.getProperty("logger.app.additivity")).isEqualTo("false");
    }

    @Test
    @DisplayName("the two intended differences are where each track's file is written")
    void theOnlyDifferenceIsTheOutputPath() {
        Properties shipped = load(resolve(IN_JAR_CANDIDATES, "log4j2.properties"));
        Properties test = load(resolve(TEST_CANDIDATES, "log4j2-test.properties"));

        assertThat(shipped.getProperty("appender.rolling.fileName")).isEqualTo("/app/logs/console.log");
        assertThat(test.getProperty("appender.rolling.fileName"))
                .as("the whole reason this file exists: /app is not creatable on a build "
                        + "machine, and since Phase 0 an unavailable rolling appender makes "
                        + "AsyncAppender.start() throw, killing every Spring-context test")
                .isNotNull()
                .doesNotStartWith("/app/");
        assertThat(test.getProperty("appender.rolling.filePattern"))
                .isNotNull()
                .doesNotStartWith("/app/");

        // The SECOND intended difference, added by Phase 4. Track 2 is a second
        // AsyncAppender with a second unavailable-off-box target, so it has exactly the
        // same "kills the whole module" property as track 1 — enumerated here rather
        // than merely tolerated, so a third difference cannot slip in unremarked.
        assertThat(shipped.getProperty("appender.detail.fileName"))
                .isEqualTo("/app/logs/detail/detail.log");
        assertThat(test.getProperty("appender.detail.fileName"))
                .as("track 2's file must be redirected off /app for the same reason track 1's "
                        + "is, or AsyncDetail.start() throws and every Spring-context test in "
                        + "bot-app dies with ExceptionInInitializerError")
                .isNotNull()
                .doesNotStartWith("/app/");
        assertThat(test.getProperty("appender.detail.filePattern"))
                .isNotNull()
                .doesNotStartWith("/app/");
    }

    @Test
    @DisplayName("nothing in the test copy names /app — that is the failure this file prevents")
    void theTestCopyNeverNamesTheContainerPath() {
        Properties test = load(resolve(TEST_CANDIDATES, "log4j2-test.properties"));

        // The retention keys are the file's other documented reason to differ (the
        // shipped Delete blocks are anchored under /app/logs and /app/logs/detail and are
        // replaced here by a small `max`). A stray /app path anywhere else is the same
        // class of defect as the two fileNames above, so it is ruled out wholesale
        // rather than key by key.
        for (String key : test.stringPropertyNames()) {
            assertThat(test.getProperty(key))
                    .as("%s points into the container filesystem; a build machine cannot "
                            + "create /app and an appender that cannot start takes the module "
                            + "down", key)
                    .doesNotContain("/app/");
        }
    }
}
