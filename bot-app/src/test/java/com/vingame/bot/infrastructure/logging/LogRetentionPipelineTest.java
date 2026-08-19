package com.vingame.bot.infrastructure.logging;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LOG_VOLUME_TIERING AD-6 — the level-aware retention split, and the promtail label it
 * silently depends on.
 * <p>
 * Phase 0's cost control is not a single setting; it is a chain across two files that fails
 * open at every link:
 * <ul>
 *   <li>Loki's {@code retention_stream} selectors match on {@code level}. {@code level} is a
 *       Loki <b>label</b> only because promtail's {@code labels} stage promotes it. Demote
 *       it back to a plain extracted field and the selectors match nothing — DEBUG then
 *       inherits the global {@code retention_period}, which this phase <em>raised</em> to
 *       30 d. So the failure mode of removing one line from promtail is not "retention stops
 *       working", it is "DEBUG is now kept four times longer than it was before the phase
 *       that was supposed to make it cheap". Loki logs nothing about it.</li>
 *   <li>The DEBUG {@code drop} stage is committed but commented out on purpose. Enabling it
 *       makes Phase 2's scoped per-group DEBUG invisible in Grafana — the endpoint returns
 *       200, the lines are written to disk, and the operator who asked for them sees an
 *       empty panel.</li>
 *   <li>Retention is only enforced at all because the compactor has
 *       {@code retention_enabled: true}. Without it every number here is decorative.</li>
 * </ul>
 * None of this is provable on the build machine at runtime (it needs Loki, 25 h and a
 * compaction cycle — Verification P0-7/P0-8), so what the build can do is pin the
 * configuration those steps will check.
 */
@DisplayName("AD-6 — Loki's level-aware retention and the promtail label it rests on")
class LogRetentionPipelineTest {

    private static Path repoFile(String... parts) {
        Path relative = Path.of(parts[0], java.util.Arrays.copyOfRange(parts, 1, parts.length));
        Path fromModule = Path.of("..").resolve(relative);
        Path path = Files.isRegularFile(fromModule) ? fromModule
                : Files.isRegularFile(relative) ? relative : null;
        // Absent only in a module-only build from an unusual CWD; same posture as
        // AlertmanagerRoutingTest and EvidenceRetentionEscapeTest.
        Assumptions.assumeTrue(path != null,
                relative + " not found from " + Path.of("").toAbsolutePath());
        return path;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> yaml(String... parts) {
        Path path = repoFile(parts);
        try (InputStream in = Files.newInputStream(path)) {
            return (Map<String, Object>) new Yaml().load(in);
        } catch (Exception e) {
            throw new AssertionError(path + " is not readable YAML", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        assertThat(value).as("%s is missing", key).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }

    /** The one scrape job, as promtail will read it. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> botManagerScrape() {
        List<Map<String, Object>> scrapes =
                (List<Map<String, Object>>) yaml("promtail-config.yml").get("scrape_configs");
        assertThat(scrapes).as("promtail must declare a scrape job").isNotEmpty();
        for (Map<String, Object> scrape : scrapes) {
            if ("bot-manager".equals(scrape.get("job_name"))) {
                return scrape;
            }
        }
        throw new AssertionError("no scrape_config named bot-manager in promtail-config.yml");
    }

    /** Every {@code __path__} promtail actually scrapes, across every job and target. */
    @SuppressWarnings("unchecked")
    private static List<String> scrapedPaths() {
        List<String> paths = new ArrayList<>();
        for (Map<String, Object> scrape :
                (List<Map<String, Object>>) yaml("promtail-config.yml").get("scrape_configs")) {
            for (Map<String, Object> target :
                    (List<Map<String, Object>>) scrape.getOrDefault("static_configs", List.of())) {
                Object labels = target.get("labels");
                if (labels instanceof Map<?, ?> m && m.get("__path__") != null) {
                    paths.add(m.get("__path__").toString());
                }
            }
        }
        return paths;
    }

    @Test
    @DisplayName("Loki splits retention by level: DEBUG/TRACE 24 h, WARN/ERROR 30 d, INFO 30 d")
    void lokiRetentionIsLevelAware() {
        Map<String, Object> limits = map(yaml("loki", "loki-config.yaml"), "limits_config");

        assertThat(Objects.toString(limits.get("retention_period")))
                .as("the default for everything matching no selector below — i.e. INFO, the "
                        + "tier that has to stay queryable for weeks")
                .isEqualTo("720h");

        Map<String, String> bySelector = new LinkedHashMap<>();
        for (Object entry : (List<?>) limits.get("retention_stream")) {
            Map<?, ?> stream = (Map<?, ?>) entry;
            bySelector.put(Objects.toString(stream.get("selector")),
                    Objects.toString(stream.get("period")));
        }

        assertThat(bySelector)
                .as("DEBUG/TRACE project to 46-124 GB/day at fleet scale and are only ever read "
                        + "within minutes of the event; without this entry they inherit the 720h "
                        + "default this phase raised, which is worse than before the phase")
                .containsEntry("{level=~\"DEBUG|TRACE\"}", "24h");
        assertThat(bySelector)
                .as("WARN/ERROR are cheap and are what an incident is reconstructed from")
                .containsEntry("{level=~\"WARN|ERROR\"}", "720h");
    }

    @Test
    @DisplayName("the compactor is what actually enforces any of it")
    void retentionIsEnforced() {
        Map<String, Object> compactor = map(yaml("loki", "loki-config.yaml"), "compactor");

        assertThat(compactor.get("retention_enabled"))
                .as("with this false every retention number in loki-config.yaml is decorative "
                        + "and the disk fills exactly as it did on 2026-06-30")
                .isEqualTo(true);
        assertThat(compactor.get("delete_request_store"))
                .as("mandatory in Loki 3.x once retention_enabled is true, or the server "
                        + "refuses to start — a hard failure, but on the box, not here")
                .isNotNull();
    }

    @Test
    @DisplayName("promtail promotes `level` to a label — the selectors match nothing otherwise")
    void levelIsAPromotedLabel() {
        List<?> stages = (List<?>) botManagerScrape().get("pipeline_stages");
        assertThat(stages).as("promtail must have a pipeline").isNotNull();

        List<String> promoted = new ArrayList<>();
        for (Object stage : stages) {
            Object labels = ((Map<?, ?>) stage).get("labels");
            if (labels instanceof Map<?, ?> m) {
                m.keySet().forEach(k -> promoted.add(Objects.toString(k)));
            }
        }

        assertThat(promoted)
                .as("loki-config.yaml's retention_stream selectors match on {level=...}. Remove "
                        + "this promotion and the split degrades to the global 720h period "
                        + "silently — Loki does not warn about a selector that matches no stream")
                .contains("level");
        assertThat(promoted)
                .as("botGroupId is how a scoped-DEBUG drill-in is actually found in Grafana "
                        + "(Verification P2-2 greps on exactly this)")
                .contains("botGroupId");
    }

    @Test
    @DisplayName("the DEBUG drop stage stays disabled — enabling it blinds Phase 2 (AD-6)")
    void theDebugDropStageIsNotActive() {
        for (Object stage : (List<?>) botManagerScrape().get("pipeline_stages")) {
            Object match = ((Map<?, ?>) stage).get("match");
            if (match instanceof Map<?, ?> m) {
                assertThat(Objects.toString(m.get("action")))
                        .as("an active `drop` on DEBUG/TRACE makes scoped per-group DEBUG "
                                + "invisible in Grafana while /api/v1/logging keeps answering "
                                + "200 — the operator sees an empty panel and concludes the "
                                + "mechanism is broken. Loki's 24 h per-stream retention is the "
                                + "control instead. Uncomment ONLY on an instance that is "
                                + "provably drowning.")
                        .isNotEqualTo("drop");
            }
        }

        // …and the disabled stage must still be *present*, or the option quietly stops
        // existing as an option.
        try {
            assertThat(Files.readString(repoFile("promtail-config.yml")))
                    .as("keep the commented-out drop stage: it is the documented escape hatch "
                            + "for an instance that is drowning")
                    .contains("action: drop");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    @DisplayName("promtail scrapes the logs root only, so logs/evidence/ stays out of Loki")
    void promtailScrapesOnlyTheTopLevel() {
        // The structural half of EvidenceRetentionEscapeTest's textual check: that test
        // asserts the glob's spelling, this one asserts nothing ELSE was added that reaches
        // under logs/ — a second scrape job pointed at /logs/evidence/*.log would re-ingest
        // every promoted file while leaving the original line untouched.
        assertThat(scrapedPaths())
                .as("exactly one scrape path, and it is the non-recursive logs root (AD-18)")
                .containsExactly("/logs/*.log");
    }
}
