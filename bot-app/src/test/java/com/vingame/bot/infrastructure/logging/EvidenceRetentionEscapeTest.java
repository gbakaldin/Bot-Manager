package com.vingame.bot.infrastructure.logging;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LOG_VOLUME_TIERING Phase 3 / AD-18 and AD-16 — {@code logs/evidence/} must stay outside
 * both sweepers, and the shim must know how often log4j2 rolls over.
 * <p>
 * The evidence shim's entire value is that a promoted file is not swept. That rests on two
 * facts in two files it does not own, each of which is one careless edit away from being
 * false, and neither of which fails loudly when it changes:
 * <ul>
 *   <li>promtail's {@code __path__: /logs/*.log} is <b>non-recursive</b>. Make it
 *       {@code /logs/**} or add a second scrape and every promoted file is re-ingested into
 *       Loki — where it obeys the same 24 h DEBUG retention it was promoted to escape, at
 *       double the ingest cost.</li>
 *   <li>log4j2's {@code Delete} uses {@code basePath /app/logs} with {@code maxDepth = 1},
 *       so it sees the top level only. Raise that depth and the sweeper deletes the evidence
 *       it was supposed to be unable to reach — silently, because the shim would go on
 *       reporting successful promotions.</li>
 * </ul>
 * The third check is the shim's rollover period. AD-16's tail pass fires at the next
 * rollover boundary + 120 s so the file that was live when the alert fired is also pinned
 * under its rolled name once it has closed. Phase 0 (AD-20) moved log4j2 to a 2 h interval;
 * a shim still assuming an hour would schedule that pass on the wrong side of the boundary.
 * That is a degraded pass rather than lost data — pass 1 already hardlinked the live inode —
 * but the two values are meant to be one value, so the build says so.
 */
@DisplayName("logs/evidence/ escapes both sweepers, and the shim knows the rollover period")
class EvidenceRetentionEscapeTest {

    private static Path repoFile(String... parts) {
        Path relative = Path.of(parts[0], java.util.Arrays.copyOfRange(parts, 1, parts.length));
        Path fromModule = Path.of("..").resolve(relative);
        Path path = Files.isRegularFile(fromModule) ? fromModule
                : Files.isRegularFile(relative) ? relative : null;
        // Absent only in a module-only build from an unusual CWD; same posture as
        // AlertmanagerRoutingTest and Log4j2TwinConfigTest.
        Assumptions.assumeTrue(path != null,
                relative + " not found from " + Path.of("").toAbsolutePath());
        return path;
    }

    private static String text(String... parts) {
        try {
            return Files.readString(repoFile(parts));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("promtail's scrape path is non-recursive, so promoted files are not re-ingested")
    void promtailDoesNotScrapeSubdirectoriesOfTheLogsMount() {
        String promtail = text("promtail-config.yml");

        assertThat(promtail)
                .as("the evidence directory is a SUBDIRECTORY precisely because this glob "
                        + "matches one level only (AD-18)")
                .contains("__path__: /logs/*.log");
        assertThat(promtail)
                .as("a recursive path would re-ingest every promoted file into Loki, where it "
                        + "obeys the retention it was promoted to escape — at double the cost")
                .doesNotContain("/logs/**")
                .doesNotContain("/logs/*/");
    }

    @Test
    @DisplayName("log4j2's Delete sweeps the top level only, so it cannot reach logs/evidence/")
    void log4j2DeleteCannotReachTheEvidenceSubdirectory() {
        // Both twins, because the mounted copy is what runs on the box and the in-jar copy
        // is what runs everywhere else. Log4j2TwinConfigTest proves they agree; this proves
        // what they agree ON.
        for (String[] file : List.of(new String[]{"logging", "log4j2.properties"},
                new String[]{"bot-app", "src", "main", "resources", "log4j2.properties"})) {
            String properties = text(file);
            assertThat(properties)
                    .as("%s: Delete must stay anchored at the logs root", String.join("/", file))
                    .contains("appender.rolling.strategy.delete.basePath = /app/logs");
            assertThat(properties)
                    .as("%s: maxDepth = 1 is what keeps the sweeper out of logs/evidence/. "
                            + "Raising it deletes the evidence silently — the shim would go on "
                            + "reporting successful promotions (AD-18)", String.join("/", file))
                    .contains("appender.rolling.strategy.delete.maxDepth = 1");
        }
    }

    @Test
    @DisplayName("the shim's rollover period is the one log4j2 actually rolls on")
    void theShimRolloverPeriodMatchesLog4j2() {
        Matcher interval = Pattern
                .compile("appender\\.rolling\\.policies\\.time\\.interval\\s*=\\s*(\\d+)")
                .matcher(text("logging", "log4j2.properties"));
        assertThat(interval.find()).as("log4j2.properties declares a rollover interval").isTrue();

        Matcher composeDefault = Pattern
                .compile("EVIDENCE_ROLLOVER_HOURS=\\$\\{EVIDENCE_ROLLOVER_HOURS:-(\\d+)}")
                .matcher(text("docker-compose.yml"));
        assertThat(composeDefault.find())
                .as("docker-compose.yml passes evidence-shim a rollover period").isTrue();

        assertThat(composeDefault.group(1))
                .as("log4j2 rolls every %s h but the shim is told %s h — AD-16's tail pass "
                        + "would fire on the wrong side of the boundary",
                        interval.group(1), composeDefault.group(1))
                .isEqualTo(interval.group(1));

        // And the script's own fallback, for a host whose .env predates the variable.
        Matcher scriptDefault = Pattern
                .compile("\"EVIDENCE_ROLLOVER_HOURS\",\\s*\"(\\d+)\"")
                .matcher(text("evidence-shim", "shim.py"));
        assertThat(scriptDefault.find()).as("shim.py declares a default rollover period").isTrue();
        assertThat(scriptDefault.group(1)).isEqualTo(interval.group(1));
    }

    @Test
    @DisplayName("the shim links and never copies")
    void theShimHardlinksRatherThanCopying() {
        // AD-14 in one assertion. A copy doubles the bytes at exactly the moment disk is the
        // constraint — the 2026-06-30 failure shape — and would pass every behavioural test
        // in the shim's own suite except the inode check.
        String script = text("evidence-shim", "shim.py");
        assertThat(script)
                .as("promotion is os.link (a hardlink costs zero blocks and still defeats "
                        + "log4j2's Delete, because unlink removes a name, not the inode)")
                .contains("os.link(");
        assertThat(script)
                .as("nothing in the shim may copy a log file")
                .doesNotContain("shutil.copy")
                .doesNotContain("copyfile");
    }
}
