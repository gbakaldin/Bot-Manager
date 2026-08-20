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
    @DisplayName("track 2's own Delete is anchored where it cannot reach logs/ or evidence/")
    void theDetailDeleteCannotReachAnythingButItsOwnArchives() {
        // Phase 4 / AD-25(1). logs/detail/ escapes promtail and track 1's sweeper for the
        // same reason logs/evidence/ does — but it brings a SECOND sweeper into the logs
        // mount, and that one has to be boxed in just as tightly. Raise its basePath to
        // /app/logs, or its maxDepth above 1, and a sweeper whose glob is `detail-*.log`
        // starts walking the directory holding the promoted evidence.
        for (String[] file : List.of(new String[]{"logging", "log4j2.properties"},
                new String[]{"bot-app", "src", "main", "resources", "log4j2.properties"})) {
            String properties = text(file);
            String where = String.join("/", file);
            assertThat(properties)
                    .as("%s: track 2's Delete must stay anchored INSIDE logs/detail. At "
                            + "/app/logs it would walk the same directory tree track 1's "
                            + "sweeper does, next to logs/evidence/", where)
                    .contains("appender.detail.strategy.delete.basePath = /app/logs/detail");
            assertThat(properties)
                    .as("%s: maxDepth = 1 keeps it off any subdirectory of logs/detail — the "
                            + "same guard track 1's Delete has, for the same reason", where)
                    .contains("appender.detail.strategy.delete.maxDepth = 1");
            assertThat(properties)
                    .as("%s: and the glob keeps it off the live detail.log as well as off "
                            + "anything named console-*", where)
                    .contains("appender.detail.strategy.delete.ifFileName.glob = detail-*.log");
        }
    }

    @Test
    @DisplayName("track 2 is written to a SUBDIRECTORY, which is the only thing keeping it out of Loki")
    void theDetailTrackLivesBelowTheScrapedDirectory() {
        // There is no filter, no drop stage and no Loki-side rule keeping track 2 out of
        // Loki: the entire mechanism is that promtail's __path__ matches one level only
        // (asserted above) and detail.log is one level down. Move it up to
        // /app/logs/detail.log and promtail ingests ~1,900 lines/s at 20k bots into a
        // 720 h retention horizon — silently, and at the highest-volume tier in the system.
        for (String[] file : List.of(new String[]{"logging", "log4j2.properties"},
                new String[]{"bot-app", "src", "main", "resources", "log4j2.properties"})) {
            String where = String.join("/", file);
            Matcher fileName = Pattern
                    .compile("appender\\.detail\\.fileName\\s*=\\s*(\\S+)")
                    .matcher(text(file));
            assertThat(fileName.find()).as("%s declares track 2's file", where).isTrue();

            String path = fileName.group(1);
            assertThat(path)
                    .as("%s: track 2 must live under the logs mount (hardlinks for evidence "
                            + "promotion cannot cross filesystems)", where)
                    .startsWith("/app/logs/");
            assertThat(path.substring("/app/logs/".length()))
                    .as("%s: and it must be in a SUBDIRECTORY of it. promtail's "
                            + "__path__: /logs/*.log is non-recursive, and that is the whole "
                            + "of what keeps track 2 out of Loki (AD-25(1))", where)
                    .contains("/");
        }
    }

    @Test
    @DisplayName("both tracks roll on the same boundary, which the shim's single tail pass assumes")
    void theTwoTracksShareOneRolloverBoundary() {
        // AD-22 makes "one rollover interval" a requirement rather than a coincidence:
        // AD-28's promotion runs ONE tail pass at the next boundary + 120 s and expects it
        // to close BOTH live files. Split the intervals and the tail pass fires while one
        // track's live file is still open — the post-incident tail of the track that
        // actually holds the per-bot detail is then the part nobody has.
        for (String[] file : List.of(new String[]{"logging", "log4j2.properties"},
                new String[]{"bot-app", "src", "main", "resources", "log4j2.properties"})) {
            String properties = text(file);
            String where = String.join("/", file);
            String track1 = intervalOf(properties, "rolling", where);
            String track2 = intervalOf(properties, "detail", where);

            assertThat(track2)
                    .as("%s: track 1 rolls every %s h and track 2 every %s h. AD-16's single "
                            + "tail pass can only close both live files if there is one "
                            + "boundary (AD-22).", where, track1, track2)
                    .isEqualTo(track1);
        }
    }

    private static String intervalOf(String properties, String appender, String where) {
        Matcher matcher = Pattern
                .compile("appender\\." + appender + "\\.policies\\.time\\.interval\\s*=\\s*(\\d+)")
                .matcher(properties);
        assertThat(matcher.find())
                .as("%s declares a rollover interval for appender.%s", where, appender).isTrue();
        return matcher.group(1);
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

    @Test
    @DisplayName("the shim looks for track 2 in the directory log4j2 actually writes it to")
    void theShimsDetailDirectoryMatchesLog4j2s() {
        // Same class of coupling as EVIDENCE_ROLLOVER_HOURS above, and the same silent
        // failure: a shim pointed at the wrong subdirectory keeps working, keeps
        // answering 200, and keeps promoting — only the half of the evidence that
        // forensics actually reads line by line is quietly missing from every incident.
        // The count on /health is what would show it, and nobody reads that until it is
        // too late to re-run the incident.
        Matcher fileName = Pattern
                .compile("appender\\.detail\\.fileName\\s*=\\s*(\\S+)")
                .matcher(text("logging", "log4j2.properties"));
        assertThat(fileName.find()).as("log4j2.properties declares track 2's file").isTrue();

        String path = fileName.group(1);
        String directory = path.substring(0, path.lastIndexOf('/'));
        String leaf = directory.substring(directory.lastIndexOf('/') + 1);

        Matcher shimDefault = Pattern
                .compile("os\\.path\\.join\\(self\\.logs_dir,\\s*([A-Z_]+|\"[^\"]+\")\\)")
                .matcher(text("evidence-shim", "shim.py"));
        assertThat(shimDefault.find())
                .as("shim.py must derive its detail directory from the logs mount — a "
                        + "different filesystem makes every hardlink fail with EXDEV")
                .isTrue();

        // The shim joins logs_dir with the DETAIL constant, whose value is the leaf of
        // log4j2's path. Assert the constant rather than the join, so the two spellings
        // are compared and not merely both present.
        assertThat(text("evidence-shim", "shim.py"))
                .as("log4j2 writes track 2 to .../%s/, so the shim's DETAIL constant must "
                        + "be exactly %s", leaf, leaf)
                .contains("DETAIL = \"" + leaf + "\"");
    }
}
