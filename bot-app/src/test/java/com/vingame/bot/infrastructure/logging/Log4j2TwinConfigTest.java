package com.vingame.bot.infrastructure.logging;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LOG_VOLUME_TIERING AD-2 — the two {@code log4j2.properties} copies must not drift.
 * <p>
 * AD-2 keeps the log configuration in two places on purpose: {@code logging/log4j2.properties}
 * is bind-mounted at {@code /app/config/log4j2.properties} and selected by
 * {@code LOGGING_CONFIG}, so a level or retention change is a {@code docker compose restart}
 * rather than a rebuild and redeploy of the whole fleet;
 * {@code bot-app/src/main/resources/log4j2.properties} stays in the jar as the fallback for
 * tests, local runs and any host without the mount (log4j2 with no configuration at all falls
 * back to {@code DefaultConfiguration} — ERROR to console only, which looks exactly like "the
 * app went quiet").
 * <p>
 * The cost of that is a drift hazard the Implementation Notes call out by name: the two files
 * are edited by hand and nothing enforces that both were edited. Drift is silent and
 * asymmetric — the mounted copy wins on the box, so a fix applied only to the in-jar copy is
 * green in every test and absent in production, and a fix applied only to the mounted copy
 * vanishes the moment a host deploys without the mount. This test is the cheap insurance the
 * plan asks for.
 * <p>
 * <b>Headers are excluded, and only headers.</b> Each file opens with a {@code =====}-fenced
 * block naming the other as its twin, so the two headers are deliberately different. Below
 * that fence they must be byte-identical, which is exactly what both files' headers promise.
 */
@DisplayName("log4j2.properties — the mounted and in-jar twins are identical below their headers")
class Log4j2TwinConfigTest {

    /** Surefire runs with the module directory as CWD; one file lives at the repo root. */
    private static final List<Path> MOUNTED_CANDIDATES = List.of(
            Path.of("..", "logging", "log4j2.properties"),
            Path.of("logging", "log4j2.properties"));

    private static final List<Path> IN_JAR_CANDIDATES = List.of(
            Path.of("src", "main", "resources", "log4j2.properties"),
            Path.of("bot-app", "src", "main", "resources", "log4j2.properties"));

    /** The fence that closes each file's twin-naming header comment. */
    private static final String HEADER_FENCE = "# ====";

    private static Path resolve(List<Path> candidates, String what) {
        Path path = candidates.stream().filter(Files::isRegularFile).findFirst().orElse(null);
        // Absent only in a module-only build from an unusual CWD; skipping beats failing a
        // build over a path assumption. Same posture as AlertmanagerRoutingTest.
        Assumptions.assumeTrue(path != null,
                what + " not found from " + Path.of("").toAbsolutePath());
        return path;
    }

    /**
     * Everything after the closing {@code =====} fence of the leading header comment, with
     * trailing whitespace on each line stripped so an invisible trailing space cannot fail
     * the build. Falls back to the whole file if a header is missing, which then fails the
     * comparison loudly rather than passing vacuously.
     */
    private static List<String> bodyOf(Path path) {
        List<String> lines;
        try {
            lines = Files.readAllLines(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        int lastFence = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith(HEADER_FENCE)) {
                lastFence = i;
            } else if (lastFence >= 0 && !lines.get(i).startsWith("#")) {
                break; // header block is over; do not chase fences later in the file
            }
        }
        return lines.subList(lastFence + 1, lines.size()).stream()
                .map(String::stripTrailing)
                .toList();
    }

    @Test
    @DisplayName("both twins carry the same configuration below their headers")
    void twinsAreIdenticalBelowTheHeader() {
        Path mounted = resolve(MOUNTED_CANDIDATES, "logging/log4j2.properties");
        Path inJar = resolve(IN_JAR_CANDIDATES, "bot-app/src/main/resources/log4j2.properties");

        assertThat(bodyOf(mounted))
                .as("logging/log4j2.properties (mounted, wins on the box) has drifted from "
                        + "bot-app/src/main/resources/log4j2.properties (in-jar fallback). "
                        + "Every change must be applied to BOTH files — see LOG_VOLUME_TIERING AD-2.")
                .containsExactlyElementsOf(bodyOf(inJar));
    }

    @Test
    @DisplayName("each twin names the other in its header, so an editor is told there are two")
    void eachTwinNamesTheOther() {
        Path mounted = resolve(MOUNTED_CANDIDATES, "logging/log4j2.properties");
        Path inJar = resolve(IN_JAR_CANDIDATES, "bot-app/src/main/resources/log4j2.properties");

        assertThat(read(mounted)).contains("bot-app/src/main/resources/log4j2.properties");
        assertThat(read(inJar)).contains("logging/log4j2.properties");
    }

    /**
     * The body must actually contain the settings this feature turns on — otherwise a pair
     * of identically-empty files would pass {@link #twinsAreIdenticalBelowTheHeader()}.
     */
    @Test
    @DisplayName("the shared body carries the Phase 0/1 posture: async appender and INFO default")
    void bodyCarriesThePhasePosture() {
        List<String> body = bodyOf(resolve(MOUNTED_CANDIDATES, "logging/log4j2.properties"));

        assertThat(body).contains("appender.async.appenderRef.type = AppenderRef");
        // AD-7: INFO is the shipped default; DEBUG is opt-in via LOGGING_LEVEL_COM_VINGAME_BOT.
        assertThat(body).contains("logger.app.level = info");
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
