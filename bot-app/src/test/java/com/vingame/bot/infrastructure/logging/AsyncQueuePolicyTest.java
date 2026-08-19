package com.vingame.bot.infrastructure.logging;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LOG_VOLUME_TIERING AD-3 / AD-4 / AD-5 — the appender graph that decides what happens to a
 * log line under pressure, and which of them a bot thread pays for.
 * <p>
 * Three settings in two files have to agree, and none of them fails loudly when they stop
 * agreeing:
 * <ol>
 *   <li><b>The async wrap actually being in the path.</b> AD-3 exists so a bot thread never
 *       waits on a disk write. If a logger's {@code appenderRef} points back at
 *       {@code RollingFileAppender} directly, the {@code AsyncAppender} is still built,
 *       still starts, still shows up as a thread in {@code /proc} (Verification P0-2 would
 *       pass) — and no event ever goes through it.</li>
 *   <li><b>{@code blocking = true}.</b> This is the correction Dev made to AD-3's own
 *       snippet, and it is the one that matters most: {@code AsyncAppender.append()} only
 *       consults {@code log4j2.asyncQueueFullPolicy} when {@code blocking} is true. With
 *       {@code blocking = false} a full queue drops the event at <em>every</em> level —
 *       ERROR included — and reports it only to log4j2's own status logger. WARN and ERROR
 *       are invisibly coupled to Alertmanager, so that is a silent alerting outage under
 *       exactly the load that would cause one.</li>
 *   <li><b>The console {@code ThresholdFilter}.</b> AD-5 is what halves the cost of the
 *       DEBUG tier by writing it once instead of twice, and it is also what keeps DEBUG off
 *       the docker json-file cap. It lives in the shipped copies only (a build wants to see
 *       the DEBUG it deliberately enabled), which is precisely why nothing in the running
 *       test suite would notice it disappearing.</li>
 * </ol>
 */
@DisplayName("AD-3/AD-4/AD-5 — the async queue policy and the console threshold hold together")
class AsyncQueuePolicyTest {

    private static final List<List<Path>> TWINS = List.of(
            List.of(Path.of("..", "logging", "log4j2.properties"),
                    Path.of("logging", "log4j2.properties")),
            List.of(Path.of("src", "main", "resources", "log4j2.properties"),
                    Path.of("bot-app", "src", "main", "resources", "log4j2.properties")));

    private static final List<Path> COMPONENT_CANDIDATES = List.of(
            Path.of("src", "main", "resources", "log4j2.component.properties"),
            Path.of("bot-app", "src", "main", "resources", "log4j2.component.properties"));

    private static Properties load(List<Path> candidates, String what) {
        Path path = candidates.stream().filter(Files::isRegularFile).findFirst().orElse(null);
        Assumptions.assumeTrue(path != null,
                what + " not found from " + Path.of("").toAbsolutePath());
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            properties.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return properties;
    }

    @Test
    @DisplayName("both loggers write through the async wrapper, not straight at the rolling file")
    void everyLoggerGoesThroughTheAsyncAppender() {
        for (List<Path> twin : TWINS) {
            Properties p = load(twin, "log4j2.properties");
            String async = p.getProperty("appender.async.name");

            assertThat(async).as("the Async appender must be declared").isEqualTo("AsyncRolling");
            assertThat(p.getProperty("appender.async.appenderRef.type"))
                    .as("in the properties format a nested AppenderRef inside an appender needs "
                            + "this explicit type line; without it log4j-core 2.24.1 throws at "
                            + "context start and the JVM never gets a logger")
                    .isEqualTo("AppenderRef");
            assertThat(p.getProperty("appender.async.appenderRef.ref"))
                    .isEqualTo(p.getProperty("appender.rolling.name"));

            assertThat(p.getProperty("rootLogger.appenderRef.async.ref"))
                    .as("root must reach the file THROUGH the async wrapper (AD-3)")
                    .isEqualTo(async);
            assertThat(p.getProperty("logger.app.appenderRef.async.ref"))
                    .as("and so must com.vingame.bot — this is the logger every bot thread uses, "
                            + "and the whole point is that it never waits on a disk write")
                    .isEqualTo(async);
        }
    }

    @Test
    @DisplayName("blocking = true, which is what makes the discard policy exist at all (AD-4)")
    void theDiscardPolicyIsActuallyReachable() {
        Properties component = load(COMPONENT_CANDIDATES, "log4j2.component.properties");

        assertThat(component.getProperty("log4j2.asyncQueueFullPolicy")).isEqualTo("Discard");
        assertThat(component.getProperty("log4j2.discardThreshold"))
                .as("DEBUG means 'at or below DEBUG' — INFO, WARN and ERROR are never discarded")
                .isEqualTo("DEBUG");

        for (List<Path> twin : TWINS) {
            assertThat(load(twin, "log4j2.properties").getProperty("appender.async.blocking"))
                    .as("log4j2.component.properties asks for Discard/DEBUG, but "
                            + "AsyncAppender.append() only consults asyncQueueFullPolicy when "
                            + "blocking is true. With blocking = false a full queue silently "
                            + "drops WARN and ERROR too — the levels Alertmanager is wired to. "
                            + "(AD-3's own snippet says false; AD-4 is the one that governs.)")
                    .isEqualTo("true");
        }
    }

    @Test
    @DisplayName("the shipped console appender is capped at INFO+ (AD-5)")
    void theConsoleIsCappedAtInfo() {
        for (List<Path> twin : TWINS) {
            Properties p = load(twin, "log4j2.properties");

            assertThat(p.getProperty("appender.console.filter.threshold.type"))
                    .as("without this every DEBUG line is serialized and written twice, and "
                            + "DEBUG pressures the docker json-file cap it has no business "
                            + "being near")
                    .isEqualTo("ThresholdFilter");
            assertThat(p.getProperty("appender.console.filter.threshold.level")).isEqualTo("info");
            assertThat(p.getProperty("appender.console.filter.threshold.onMatch"))
                    .as("NEUTRAL, not ACCEPT: an ACCEPT here would bypass every other filter "
                            + "on the console path")
                    .isEqualTo("NEUTRAL");
            assertThat(p.getProperty("appender.console.filter.threshold.onMismatch")).isEqualTo("DENY");
        }
    }

    @Test
    @DisplayName("the rolling file keeps the JSON layout Loki's pipeline parses")
    void theRollingFileStaysJson() {
        // promtail-config.yml's json stage — and therefore the `level` label the whole
        // retention split rests on — assumes this layout. A pattern layout here would leave
        // Loki with unparsed lines and no labels, silently.
        for (List<Path> twin : TWINS) {
            Properties p = load(twin, "log4j2.properties");
            assertThat(p.getProperty("appender.rolling.layout.type")).isEqualTo("JsonTemplateLayout");
            assertThat(p.getProperty("appender.rolling.layout.eventTemplateUri"))
                    .isEqualTo("classpath:log4j2-json-template.json");
        }
    }
}
