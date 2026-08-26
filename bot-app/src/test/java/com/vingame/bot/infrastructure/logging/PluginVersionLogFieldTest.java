package com.vingame.bot.infrastructure.logging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vingame.bot.common.logging.BotMdc;
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
 * PLUGIN_HOT_RELOAD AD-8 — {@code pluginVersion} reaches Loki, and <b>only</b> track 1.
 * <p>
 * Both halves of that sentence are silent when they break, which is why they are worth a
 * build-time test rather than only the staging check (verification P1-6):
 * <ul>
 *   <li><b>Track 1.</b> The MDC key existing does not put it in the log. Track 1's
 *       {@code JsonTemplateLayout} renders a <em>closed</em> list of MDC keys from
 *       {@code log4j2-json-template.json} — there is no wildcard resolver — so a key that is
 *       set on every bot's MDC and absent from the template is simply never written. Nothing
 *       fails; the Grafana query {@code | json | pluginVersion="builtin"} just returns
 *       nothing, forever, which reads as "no bots" rather than as "no field".</li>
 *   <li><b>Only track 1.</b> Track 2 is the highest-volume output in the system and its
 *       pattern is deliberately untouched, both because ~28 bytes/line is not immaterial
 *       there and because the {@code grep "\[<GROUP_ID>/"} recipes in {@code CLAUDE.md}
 *       parse that bracket group positionally. Adding a fourth {@code %X{}} to it would
 *       break every one of those recipes with no error anywhere.</li>
 * </ul>
 * The template is also parsed as JSON here, which nothing else in the build does: it is
 * loaded by log4j2 at startup from {@code classpath:}, so a trailing comma in it is a
 * runtime logging failure discovered on the box, not a compile error.
 */
@DisplayName("AD-8 — pluginVersion is a track-1 log field and nothing else")
class PluginVersionLogFieldTest {

    private static final List<Path> TEMPLATE_CANDIDATES = List.of(
            Path.of("src", "main", "resources", "log4j2-json-template.json"),
            Path.of("bot-app", "src", "main", "resources", "log4j2-json-template.json"));

    /** The shipped twin and the bind-mounted twin; Log4j2TwinConfigTest keeps them equal. */
    private static final List<List<Path>> PROPERTIES_TWINS = List.of(
            List.of(Path.of("src", "main", "resources", "log4j2.properties"),
                    Path.of("bot-app", "src", "main", "resources", "log4j2.properties")),
            List.of(Path.of("..", "logging", "log4j2.properties"),
                    Path.of("logging", "log4j2.properties")));

    private static Path resolve(List<Path> candidates, String what) {
        Path path = candidates.stream().filter(Files::isRegularFile).findFirst().orElse(null);
        Assumptions.assumeTrue(path != null,
                what + " not found from " + Path.of("").toAbsolutePath());
        return path;
    }

    private static JsonNode template() {
        try (InputStream in = Files.newInputStream(resolve(TEMPLATE_CANDIDATES,
                "log4j2-json-template.json"))) {
            return new ObjectMapper().readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException("log4j2-json-template.json is not parseable JSON — "
                    + "log4j2 reads it at startup, so this is a runtime logging failure", e);
        }
    }

    private static Properties properties(List<Path> candidates) {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(resolve(candidates, "log4j2.properties"))) {
            p.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return p;
    }

    @Test
    @DisplayName("track 1's JSON template resolves the pluginVersion MDC key")
    void jsonTemplateCarriesPluginVersion() {
        JsonNode field = template().get(BotMdc.PLUGIN_VERSION);

        assertThat(field)
                .as("log4j2-json-template.json has no wildcard MDC resolver, so a key absent "
                        + "from it never reaches console.log and therefore never reaches Loki")
                .isNotNull();
        assertThat(field.path("$resolver").asText()).isEqualTo("mdc");
        assertThat(field.path("key").asText())
                .as("the template key must be the MDC key BotMdc actually writes")
                .isEqualTo(BotMdc.PLUGIN_VERSION);
    }

    @Test
    @DisplayName("the template still carries every MDC field it carried before")
    void jsonTemplateDidNotLoseAnyExistingField() {
        // The edit that adds a field is also the edit that can drop one, and a dropped MDC
        // field is invisible: the line still ships, just without the label every dashboard
        // and every Loki filter is keyed on.
        JsonNode template = template();
        assertThat(template.fieldNames()).toIterable().contains(
                "timestamp", "level", "logger", "thread", "message", "exception",
                BotMdc.BOT_GROUP_ID, BotMdc.BOT_ID, BotMdc.ENVIRONMENT_ID,
                BotMdc.GAME_TYPE, BotMdc.BOT_USER_NAME, BotMdc.PLUGIN_VERSION);
    }

    @Test
    @DisplayName("track 2's pattern is untouched: three MDC keys, and pluginVersion is not one")
    void detailPatternDoesNotRenderPluginVersion() {
        for (List<Path> twin : PROPERTIES_TWINS) {
            String pattern = properties(twin).getProperty("appender.detail.layout.pattern");
            assertThat(pattern).as("track 2's pattern must exist").isNotNull();
            assertThat(pattern)
                    .as("AD-8: track 2 is the highest-volume track and its MDC keys are a "
                            + "closed list the CLAUDE.md grep recipes parse positionally")
                    .contains("[%X{botGroupId}/%X{botId}/%X{gameType}]")
                    .doesNotContain("%X{" + BotMdc.PLUGIN_VERSION + "}");
        }
    }
}
