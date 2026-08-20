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
 * <p>
 * <b>Phase 4 adds a second track and a fourth setting.</b> AD-22 .. AD-27 split the output
 * into track 1 ({@code console.log}, JSON, INFO+, the only thing Loki ingests) and track 2
 * ({@code logs/detail/detail.log}, PatternLayout, the ws-parser library in full plus all our
 * DEBUG/TRACE, never in Loki). Three more things now have to agree and none of them fails
 * loudly either: that {@code com.vingame.websocketparser} really is pulled out of the root
 * logger with {@code additivity = false} (without it, 98.7% of INFO volume walks straight
 * back into Loki); that the {@code ThresholdFilter} on {@code AsyncRolling} is present, at
 * {@code info}, with {@code onMismatch = DENY} (the single assertion that keeps DEBUG out of
 * Loki, including Phase 2's scoped-debug {@code ACCEPT}, which bypasses the level check but
 * not an appender filter); and that {@code AsyncDetail} is {@code blocking = false} while
 * {@code AsyncRolling} is {@code blocking = true} — a deliberate pair, asserted together so
 * that "fixing" one reads as breaking the other.
 */
@DisplayName("AD-3/AD-4/AD-5/AD-23/AD-24/AD-25 — the two-track appender graph holds together")
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
    @DisplayName("three loggers, and only ws-parser is routed to track 2 alone (AD-23/AD-27)")
    void theThreeLoggersAreWiredToTheTracksPhaseFourIntends() {
        for (List<Path> twin : TWINS) {
            Properties p = load(twin, "log4j2.properties");
            String asyncDetail = p.getProperty("appender.asyncdetail.name");

            assertThat(asyncDetail)
                    .as("track 2's async wrapper must be declared")
                    .isEqualTo("AsyncDetail");

            // Root and com.vingame.bot keep the console and track 1 (AD-27: losing Spring's
            // startup lines would break the releaser's smoke test, and losing third-party
            // ERROR would blind operators) and gain track 2, so the detail file is
            // self-contained.
            for (String logger : List.of("rootLogger", "logger.app")) {
                assertThat(p.getProperty(logger + ".appenderRef.console.ref"))
                        .as("%s must keep the console", logger).isEqualTo("ConsoleAppender");
                assertThat(p.getProperty(logger + ".appenderRef.async.ref"))
                        .as("%s must keep track 1", logger).isEqualTo("AsyncRolling");
                assertThat(p.getProperty(logger + ".appenderRef.detail.ref"))
                        .as("%s must ALSO reach track 2 — AD-27 makes the detail file "
                                + "self-contained, so an incident can be read from one file "
                                + "without cross-referencing console.log", logger)
                        .isEqualTo(asyncDetail);
            }

            assertThat(p.getProperty("logger.wsparser.name"))
                    .as("the library's package root, spelled EXACTLY. Spring Boot's setLogLevel "
                            + "mutates a LoggerConfig that exists by exact name in place; "
                            + "against a name that does not exist it creates a fresh one with "
                            + "NO appenders, silently deleting the detail track (AD-23)")
                    .isEqualTo("com.vingame.websocketparser");
            assertThat(p.getProperty("logger.wsparser.level"))
                    .as("AD-32: INFO is the design, and it is what every projection in AD-26 is "
                            + "built on. WARN is the escape hatch, delivered by "
                            + "WSPARSER_LOG_LEVEL, not by editing this")
                    .isEqualTo("info");
            assertThat(p.getProperty("logger.wsparser.additivity"))
                    .as("this is the whole mechanical fix for Phase 4's Finding 1. With "
                            + "additivity = true the library's 14.67 lines/s go to track 2 AND "
                            + "still propagate to root — i.e. back into console.log and back "
                            + "into Loki, which is 98.7% of INFO volume and the reason Phase 4 "
                            + "exists")
                    .isEqualTo("false");
            assertThat(p.getProperty("logger.wsparser.appenderRef.detail.ref"))
                    .isEqualTo(asyncDetail);
            assertThat(p.getProperty("logger.wsparser.appenderRef.console.ref"))
                    .as("ws-parser must NOT reach the console: `docker logs bot-manager` is "
                            + "capped at 50m x 5 and the AUTH flood would dominate it")
                    .isNull();
            assertThat(p.getProperty("logger.wsparser.appenderRef.async.ref"))
                    .as("and it must NOT reach track 1, which is the only thing Loki ingests")
                    .isNull();

            assertThat(p.getProperty("appender.asyncdetail.appenderRef.type"))
                    .as("same trap as AsyncRolling's, second appender: without the explicit "
                            + "type line log4j-core 2.24.1 throws at context start and the JVM "
                            + "never gets a logger at all")
                    .isEqualTo("AppenderRef");
            assertThat(p.getProperty("appender.asyncdetail.appenderRef.ref"))
                    .isEqualTo(p.getProperty("appender.detail.name"));
        }
    }

    @Test
    @DisplayName("blocking is true on track 1 and false on track 2 — a deliberate pair (AD-25)")
    void theTwoTracksBlockDifferentlyOnPurpose() {
        for (List<Path> twin : TWINS) {
            Properties p = load(twin, "log4j2.properties");

            assertThat(p.getProperty("appender.async.blocking"))
                    .as("track 1 carries INFO+ only, and INFO+ is what Alertmanager and Loki "
                            + "are wired to. It must block rather than lose a line (AD-4).")
                    .isEqualTo("true");
            assertThat(p.getProperty("appender.asyncdetail.blocking"))
                    .as("""
                            THIS IS NOT THE AD-3/AD-4 DEFECT AND MUST NOT BE "FIXED" TO true.
                            AD-25(4): log4j2.discardThreshold is a JVM-WIDE property, so it \
                            cannot discard track 2's INFO without also discarding track 1's — \
                            AD-4's policy simply cannot express what track 2 needs. Track 2 \
                            carries the highest-volume tier in the system (~1,893 lines/s at \
                            20k bots) at INFO; under blocking = true a full detail queue would \
                            park a BOT THREAD on a queue wait, which is precisely the latency \
                            AD-4 exists to prevent, reintroduced through the back door. Track \
                            2 is a best-effort forensic tier: dropping on a full queue is \
                            right for it, and track 1 above is what must never drop.""")
                    .isEqualTo("false");
        }
    }

    @Test
    @DisplayName("track 1 is capped at INFO+, which is what keeps DEBUG out of Loki (AD-24)")
    void trackOneIsCappedAtInfo() {
        for (List<Path> twin : TWINS) {
            Properties p = load(twin, "log4j2.properties");

            assertThat(p.getProperty("appender.async.filter.threshold.type"))
                    .as("the single assertion that keeps DEBUG and TRACE out of Loki by every "
                            + "path. Note it must be on the ASYNC WRAPPER, not on "
                            + "RollingFileAppender: here the event is rejected before it "
                            + "consumes a slot in the 8,192-entry queue")
                    .isEqualTo("ThresholdFilter");
            assertThat(p.getProperty("appender.async.filter.threshold.level")).isEqualTo("info");
            assertThat(p.getProperty("appender.async.filter.threshold.onMatch"))
                    .as("NEUTRAL, not ACCEPT — an ACCEPT would bypass any other filter on the "
                            + "path")
                    .isEqualTo("NEUTRAL");
            assertThat(p.getProperty("appender.async.filter.threshold.onMismatch"))
                    .as("DENY is the half that does the work. Phase 2's scoped-debug filter "
                            + "returns ACCEPT to beat the LEVEL check, but an appender's own "
                            + "filter still runs (AppenderControl.callAppender0 calls "
                            + "isFiltered before append) — measured on the box: 0 DEBUG lines "
                            + "on the filtered path against 14,310 on the unfiltered one")
                    .isEqualTo("DENY");
        }
    }

    @Test
    @DisplayName("track 2 is a pattern file, track 1 stays JSON (AD-25)")
    void theTwoTracksUseTheLayoutsTheirConsumersNeed() {
        for (List<Path> twin : TWINS) {
            Properties p = load(twin, "log4j2.properties");

            assertThat(p.getProperty("appender.detail.layout.type"))
                    .as("nothing parses track 2 — not promtail, not Loki, not Grafana. JSON "
                            + "costs ~400 B/line against ~250 B for a pattern line, i.e. ~38% "
                            + "of track 2's bytes for nothing, and it would drag the "
                            + "ws-parser ANSI escapes back into a structured `message` field "
                            + "where they are corruption rather than colour")
                    .isEqualTo("PatternLayout");
            assertThat(p.getProperty("appender.detail.layout.pattern"))
                    .as("%%c keeps the FULL logger name so a misroute between the two tracks is "
                            + "greppable, and the MDC triple is what a per-group drill-in "
                            + "greps for as [groupId/botId/gameType]")
                    .contains("%c")
                    .contains("%X{botGroupId}");
            assertThat(p.getProperty("appender.detail.immediateFlush"))
                    .as("AD-25(3): log4j2 flushes at endOfBatch, so the file is current within "
                            + "milliseconds anyway and the per-event syscall is gone. Track 1 "
                            + "keeps the default true — a partial trailing buffer is "
                            + "acceptable for a forensic tier and not for the alerting one")
                    .isEqualTo("false");
            assertThat(p.getProperty("appender.detail.bufferSize"))
                    .as("immediateFlush = false makes the exposure THE LAST <= bufferSize BYTES, "
                            + "not 'the last few ms' — and how much time that spans is inversely "
                            + "proportional to the log rate, so a JVM in a death spiral loses "
                            + "minutes of exactly the lines the shim's AD-21 boot promotion is "
                            + "about to pin. Declared rather than inherited so the bound is a "
                            + "decision somebody made and can be found")
                    .isEqualTo("8192");
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
