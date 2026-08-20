package com.vingame.bot.infrastructure.logging;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LOG_VOLUME_TIERING AD-2 / AD-7 — the compose wiring that makes the externalized log
 * config and the env-driven level real.
 * <p>
 * {@link Log4j2TwinConfigTest} proves the mounted twin exists and matches the in-jar copy.
 * That is only half of AD-2: a twin nobody mounts is a file, not a configuration. And the
 * half that is missing fails <em>invisibly</em>, because the two copies are identical by
 * construction — drop the {@code LOGGING_CONFIG} variable or the bind mount and the app
 * runs the in-jar copy with exactly the same content, looking perfect, right up until
 * someone edits {@code logging/log4j2.properties} on the box, restarts, and nothing
 * changes. The entire value of AD-2 is "the next tuning pass is not a redeploy"; nothing
 * else observes whether that is true.
 * <p>
 * The same applies to {@code LOGGING_LEVEL_COM_VINGAME_BOT}. Without it in compose,
 * {@code BOT_LOG_LEVEL=DEBUG} in staging's {@code secrets.env} is a variable nothing
 * reads: staging silently runs at the prod INFO floor and the per-bot detail people
 * expect there is simply absent.
 */
@DisplayName("AD-2/AD-7 — docker-compose actually mounts the log config and passes the level")
class LoggingComposeWiringTest {

    private static final List<Path> CANDIDATES = List.of(
            Path.of("..", "docker-compose.yml"),
            Path.of("docker-compose.yml"));

    @SuppressWarnings("unchecked")
    private static Map<String, Object> botManager() {
        Path path = CANDIDATES.stream().filter(Files::isRegularFile).findFirst().orElse(null);
        // Absent only in a module-only build from an unusual CWD; same posture as
        // AlertmanagerRoutingTest.
        Assumptions.assumeTrue(path != null,
                "docker-compose.yml not found from " + Path.of("").toAbsolutePath());
        Map<String, Object> compose;
        try (InputStream in = Files.newInputStream(path)) {
            compose = (Map<String, Object>) new Yaml().load(in);
        } catch (Exception e) {
            throw new AssertionError("docker-compose.yml is not readable YAML", e);
        }
        Map<String, Object> services = (Map<String, Object>) compose.get("services");
        Map<String, Object> botManager = (Map<String, Object>) services.get("bot-manager");
        assertThat(botManager).as("the bot-manager service must exist").isNotNull();
        return botManager;
    }

    private static List<String> environment() {
        List<String> values = new ArrayList<>();
        for (Object entry : (List<?>) botManager().getOrDefault("environment", List.of())) {
            values.add(Objects.toString(entry));
        }
        return values;
    }

    private static List<String> volumes() {
        List<String> values = new ArrayList<>();
        for (Object entry : (List<?>) botManager().getOrDefault("volumes", List.of())) {
            values.add(Objects.toString(entry));
        }
        return values;
    }

    private static String valueOf(String name) {
        for (String entry : environment()) {
            if (entry.startsWith(name + "=")) {
                return entry.substring(name.length() + 1);
            }
        }
        return null;
    }

    @Test
    @DisplayName("LOGGING_CONFIG points at the mounted twin, and the twin is actually mounted there")
    void theMountedConfigIsSelectedAndPresent() {
        String loggingConfig = valueOf("LOGGING_CONFIG");
        assertThat(loggingConfig)
                .as("without this the app runs the in-jar copy and every edit to "
                        + "logging/log4j2.properties on the box is silently ignored — AD-2 "
                        + "buys nothing and nobody finds out until a tuning pass does nothing")
                .isNotNull();

        // Strip the ${VAR:-default} wrapper to get the container path it resolves to.
        String containerPath = loggingConfig.replaceAll("^\\$\\{[A-Z_]+:-", "").replaceAll("}$", "");
        assertThat(containerPath).startsWith("/app/");

        assertThat(volumes())
                .as("LOGGING_CONFIG names %s, so the mounted twin has to land exactly there. "
                        + "A path mismatch is not an error: log4j2 falls back and the app "
                        + "starts perfectly.", containerPath)
                .anyMatch(volume -> volume.startsWith("./logging/log4j2.properties:" + containerPath));
        assertThat(volumes())
                .as("read-only — the app must never rewrite its own log configuration")
                .anyMatch(volume -> volume.equals("./logging/log4j2.properties:" + containerPath + ":ro"));
    }

    @Test
    @DisplayName("the level is passed from BOT_LOG_LEVEL and defaults to INFO (AD-7)")
    void theLevelComesFromTheEnvironmentAndDefaultsToInfo() {
        assertThat(valueOf("LOGGING_LEVEL_COM_VINGAME_BOT"))
                .as("this is the whole of AD-7's delivery mechanism. Absent, staging's "
                        + "BOT_LOG_LEVEL=DEBUG is a variable nothing reads and staging quietly "
                        + "runs at the prod INFO floor; misspelled, the same, with no warning. "
                        + "LoggingLevelOverrideTest proves the name binds — this proves it is "
                        + "the name compose actually passes.")
                .isEqualTo("${BOT_LOG_LEVEL:-INFO}");
    }

    @Test
    @DisplayName("the ws-parser level is passed too, and defaults to INFO (AD-32)")
    void theLibraryLevelComesFromTheEnvironmentAndDefaultsToInfo() {
        // Phase 4's escape hatch. Track 2 is bounded by the log4j2 caps, but on a box where
        // disk binds before a ramp, WSPARSER_LOG_LEVEL=WARN removes ~99% of its volume with
        // a restart and no rebuild. Absent from compose, that variable is a line in
        // secrets.env that nothing reads — and the only remaining lever is a redeploy.
        //
        // INFO is the default on purpose: it is the level the 14.67 lines/s measurement and
        // every retention projection in AD-26 are built on, and it is the per-bot
        // connection/auth narrative that root-caused the PING-before-AUTH regression.
        assertThat(valueOf("LOGGING_LEVEL_COM_VINGAME_WEBSOCKETPARSER"))
                .as("compose must pass the library level through, defaulting to INFO. The "
                        + "logger name is also load-bearing: Spring Boot's setLogLevel against "
                        + "a name that does not exist in log4j2.properties creates a fresh "
                        + "LoggerConfig with NO appenders, silently deleting the detail track.")
                .isEqualTo("${WSPARSER_LOG_LEVEL:-INFO}");
    }

    @Test
    @DisplayName("the logs bind mount is unchanged and read-write, since evidence/ lives inside it")
    void theLogsMountStillGivesTheShimSomewhereToLink() {
        // Hardlinks cannot cross devices. logs/evidence/ is a subdirectory of the same
        // host directory bot-manager writes console.log into; if bot-manager ever wrote
        // its logs somewhere else, or read-only, every promotion in Phase 3 would fail
        // with EXDEV or EACCES and the only sign would be an ERROR line in a container
        // nobody tails.
        assertThat(volumes())
                .as("bot-manager and evidence-shim must share one filesystem for os.link")
                .contains("./logs:/app/logs");
    }
}
