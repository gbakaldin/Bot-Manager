package com.vingame.bot.domain.botgroup.service;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
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
 * DEAD_GROUP_AUTO_RECOVERY AD-6 / Phase 4 — the kill switch for autonomous restarts of
 * DEAD bot groups exists, is reachable from the environment, and is shipped OFF.
 * <p>
 * This feature is the only thing in the application that starts money-spending bots with
 * no human in the loop, so its switch is not ordinary configuration. It is split across
 * two files that must agree and that nothing else compares:
 * <ol>
 *   <li>{@code application.properties} holds the <em>compiled</em> default. Phase 4
 *       deliberately leaves it {@code false} — flipping it is Phase 5's decision, taken
 *       after a soak. A tidy-up to {@code true} would ship an always-on default inside
 *       the jar, on every box, with nothing at the deploy site saying so.</li>
 *   <li>{@code docker-compose.yml} is what makes the switch <em>reachable</em>. Delete or
 *       rename that line and recovery becomes un-switchable everywhere: the compiled
 *       {@code false} wins forever, {@code BOT_RECOVERY_ENABLED=true} in a box's
 *       {@code .env} is a variable nothing reads, and — after Phase 5 — the emergency
 *       stop {@code BOT_RECOVERY_ENABLED=false} silently stops working too.</li>
 * </ol>
 * Every one of those failures is invisible at runtime: the app starts, the scheduler
 * boots, and the only symptom is a boot line reporting an {@code enabled=} the operator
 * did not choose. Hence a build-time pin, in the same idiom as
 * {@code LoggingComposeWiringTest} and {@code Log4j2TwinConfigTest}.
 * <p>
 * The third leg — that the SCREAMING_SNAKE name compose passes really binds to the
 * dotted property {@link DeadGroupRecoveryScheduler} reads — is pinned by
 * {@link #theComposeVariableNameBindsToThePropertyTheSchedulerReads()}, because a name
 * that does not bind fails exactly as silently as a name that is absent.
 */
@DisplayName("AD-6 — the recovery kill switch is wired through compose and ships false")
class RecoveryKillSwitchWiringTest {

    /** The property {@code DeadGroupRecoveryScheduler} binds with {@code @Value}. */
    private static final String PROPERTY = "bot.recovery.enabled";

    /** The environment variable compose passes, and what staging sets in its .env. */
    private static final String ENV_VAR = "BOT_RECOVERY_ENABLED";

    private static final List<Path> COMPOSE_CANDIDATES = List.of(
            Path.of("..", "docker-compose.yml"),
            Path.of("docker-compose.yml"));

    private static final List<Path> PROPERTIES_CANDIDATES = List.of(
            Path.of("src", "main", "resources", "application.properties"),
            Path.of("bot-app", "src", "main", "resources", "application.properties"));

    // ------------------------------------------------------------------ file access

    @SuppressWarnings("unchecked")
    private static List<String> botManagerEnvironment() {
        Path path = COMPOSE_CANDIDATES.stream().filter(Files::isRegularFile).findFirst().orElse(null);
        // Absent only in a module-only build from an unusual CWD; same posture as
        // LoggingComposeWiringTest and AlertmanagerRoutingTest.
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

        List<String> values = new ArrayList<>();
        for (Object entry : (List<?>) botManager.getOrDefault("environment", List.of())) {
            values.add(Objects.toString(entry));
        }
        assertThat(values)
                .as("an empty environment block would make every assertion below vacuous")
                .isNotEmpty();
        return values;
    }

    private static String composeValueOf(String name) {
        for (String entry : botManagerEnvironment()) {
            if (entry.startsWith(name + "=")) {
                return entry.substring(name.length() + 1);
            }
        }
        return null;
    }

    private static List<String> applicationPropertyLines() {
        Path path = PROPERTIES_CANDIDATES.stream().filter(Files::isRegularFile).findFirst().orElse(null);
        Assumptions.assumeTrue(path != null,
                "application.properties not found from " + Path.of("").toAbsolutePath());
        try {
            return Files.readAllLines(path);
        } catch (Exception e) {
            throw new AssertionError("application.properties is not readable", e);
        }
    }

    // ----------------------------------------------------------------------- tests

    @Test
    @DisplayName("compose passes BOT_RECOVERY_ENABLED, defaulting to false")
    void composePassesTheSwitchAndDefaultsItOff() {
        assertThat(composeValueOf(ENV_VAR))
                .as("this line is the entire delivery mechanism for the recovery switch. "
                        + "Absent or misspelled, the compiled default wins on every box: "
                        + "BOT_RECOVERY_ENABLED in secrets.env/.env is read by nothing, "
                        + "staging cannot be enabled without a rebuild, and after Phase 5 "
                        + "flips the compiled default there is no emergency stop at all. "
                        + "The ${...:-false} form is load-bearing on both sides — the "
                        + "default keeps the shipped posture OFF today, and the override "
                        + "keeps the stop working after the flip.")
                .isEqualTo("${" + ENV_VAR + ":-false}");
    }

    @Test
    @DisplayName("the compiled default in application.properties is still false (Phase 5 not taken)")
    void theCompiledDefaultIsStillOff() {
        List<String> declarations = applicationPropertyLines().stream()
                .map(String::strip)
                .filter(line -> line.startsWith(PROPERTY + "="))
                .toList();

        assertThat(declarations)
                .as("%s must be declared exactly once — a second declaration silently wins "
                        + "over the first and neither reads as authoritative", PROPERTY)
                .hasSize(1);
        assertThat(declarations.get(0))
                .as("Phase 4 ships the reconciler ENABLED ON STAGING ONLY, through compose. "
                        + "The jar's default stays false until Phase 5, which is gated on a "
                        + "soak. Flipping it here turns autonomous restarts on for every "
                        + "instance that does not set the environment variable — including "
                        + "prod — with no deploy-time evidence that anything changed. If you "
                        + "are here to take Phase 5, change this line AND this assertion, "
                        + "deliberately, and record it in the plan.")
                .isEqualTo(PROPERTY + "=false");
    }

    @Test
    @DisplayName("the compose variable name relaxed-binds to the property the scheduler reads")
    void theComposeVariableNameBindsToThePropertyTheSchedulerReads() {
        // The two halves are independently wrong-able: compose can pass a name nothing
        // binds, and the scheduler can read a property nothing passes. Both look identical
        // from outside — the switch simply does nothing.
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().replace(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        Map.of(ENV_VAR, "true")));

        assertThat(Binder.get(environment).bind(PROPERTY, Bindable.of(Boolean.class)).orElse(false))
                .as("%s must relaxed-bind to %s — that mapping is what compose's comment "
                        + "claims and what Phase 4's staging enable depends on", ENV_VAR, PROPERTY)
                .isTrue();
    }
}
