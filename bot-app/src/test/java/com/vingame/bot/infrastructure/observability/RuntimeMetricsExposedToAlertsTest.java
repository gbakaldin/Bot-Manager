package com.vingame.bot.infrastructure.observability;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.JvmMetricsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.SystemMetricsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.export.prometheus.PrometheusMetricsExportAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * VIPTALK_ALERTING_V2 Phase 6 / AD-V10 — {@code BotManagerRestarted} is built on
 * {@code changes(process_start_time_seconds{job="bot-manager"}[15m]) > 0}, and
 * {@code JvmThreadsHigh} on {@code jvm_threads_live_threads}. <b>Neither series is
 * produced by any of our code.</b> They come from Spring Boot's actuator metrics
 * auto-configuration, which means the rules are correct only for as long as that
 * auto-configuration stays on the classpath, stays enabled, and keeps naming them the
 * way the rules spell them.
 * <p>
 * The Phase 6 dev recorded "{@code process_start_time_seconds} is not confirmed present
 * in a live scrape" as an open gap. This test closes as much of it as can be closed off
 * the host: it boots the same actuator metrics auto-configurations the application does,
 * scrapes the resulting {@link PrometheusMeterRegistry}, and asserts the exposition
 * carries the exact metric names the rules select on. A rule pointed at a metric that
 * does not exist never fires and never says so, which is the failure mode this guards.
 * <p>
 * What is still release-time-only: that the running container actually exposes
 * {@code /actuator/prometheus}, and that Prometheus scrapes it under
 * {@code job="bot-manager"} (that half is a {@code prometheus.yml} fact, pinned by
 * {@link com.vingame.bot.domain.alert.AlertPipelineWiringTest}). What is closed here is
 * the part everyone assumed: that the metric exists at all, under that name.
 */
@DisplayName("actuator runtime metrics that alert rules select on (AD-V10)")
class RuntimeMetricsExposedToAlertsTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    MetricsAutoConfiguration.class,
                    CompositeMeterRegistryAutoConfiguration.class,
                    PrometheusMetricsExportAutoConfiguration.class,
                    // The binder that actually registers process.start.time / process.uptime.
                    SystemMetricsAutoConfiguration.class,
                    JvmMetricsAutoConfiguration.class))
            // The one metrics property the application sets that touches every meter.
            .withPropertyValues("management.metrics.tags.application=bot-manager");

    @Test
    @DisplayName("process_start_time_seconds is in the exposition, spelled as BotManagerRestarted spells it")
    void processStartTimeIsExposed() {
        runner.run(context -> {
            String scrape = context.getBean(PrometheusMeterRegistry.class).scrape();

            assertThat(scrape)
                    .as("BotManagerRestarted is changes(process_start_time_seconds[15m]) > 0; "
                            + "without this series the rule is silent forever and nothing reports it")
                    .contains("process_start_time_seconds");
            // The rule uses changes(), so the value has to be a real, stable epoch second —
            // a 0 or a NaN would make changes() meaningless.
            assertThat(seriesValue(scrape, "process_start_time_seconds"))
                    .as("must be a plausible JVM start epoch (seconds), not 0")
                    .isGreaterThan(1_600_000_000d);
        });
    }

    @Test
    @DisplayName("jvm_threads_live_threads is in the exposition, spelled as JvmThreadsHigh spells it")
    void jvmLiveThreadsIsExposed() {
        runner.run(context -> {
            String scrape = context.getBean(PrometheusMeterRegistry.class).scrape();

            assertThat(scrape)
                    .as("JvmThreadsHigh is the regression guard for the thread-leak sawtooth; "
                            + "it is only a guard if the series exists")
                    .contains("jvm_threads_live_threads");
            assertThat(seriesValue(scrape, "jvm_threads_live_threads")).isGreaterThan(0d);
        });
    }

    @Test
    @DisplayName("jvm_memory_used_bytes carries id=\"Metaspace\", spelled as MetaspaceGrowth spells it")
    void metaspaceSeriesIsExposed() {
        // PLUGIN_HOT_RELOAD AD-2: the metaspace series is ADOPTED, not built — it comes from
        // Micrometer's JvmMemoryMetrics via JvmMetricsAutoConfiguration, and writing our own
        // gauge would duplicate it and silently diverge. The cost of adopting is that the
        // rule now depends on a name nothing in this repo produces, and AlertRuleMetricsTest
        // deliberately skips `jvm_*` through EXTERNAL_PREFIXES. This is the only place the
        // MetaspaceGrowth rule's metric — and, decisively, its `id="Metaspace"` selector —
        // is pinned. A renamed pool or a dropped binder would leave the rule selecting an
        // empty vector forever, which is indistinguishable from "no leak".
        runner.run(context -> {
            String scrape = context.getBean(PrometheusMeterRegistry.class).scrape();

            assertThat(scrape)
                    .as("MetaspaceGrowth is delta(jvm_memory_used_bytes{area=\"nonheap\","
                            + "id=\"Metaspace\"}[24h]) and on(job) (time() - "
                            + "process_start_time_seconds > 86400); both label values are part "
                            + "of the name as far as the rule is concerned, and the uptime gate "
                            + "means the rule ALSO depends on process_start_time_seconds, "
                            + "pinned by processStartTimeIsExposed above")
                    .contains("jvm_memory_used_bytes")
                    .containsPattern("jvm_memory_used_bytes\\{[^}]*area=\"nonheap\"")
                    .containsPattern("jvm_memory_used_bytes\\{[^}]*id=\"Metaspace\"");
            assertThat(metaspaceValue(scrape))
                    .as("a metaspace reading of 0 would make delta() meaningless")
                    .isGreaterThan(0d);
        });
    }

    @Test
    @DisplayName("the class-count series the plugin-runtime dashboard reads are exposed")
    void classCountSeriesAreExposed() {
        // The JVM-side corroboration for plugin_classloaders_live: our weak-reference gauge
        // says a loader became unreachable, these say the JVM actually unloaded classes.
        // Panelled in grafana/provisioning/dashboards/plugin-runtime.json, so the same
        // "renamed binder is silent" hazard applies even though no alert reads them yet.
        runner.run(context -> {
            String scrape = context.getBean(PrometheusMeterRegistry.class).scrape();

            assertThat(scrape)
                    .contains("jvm_classes_loaded_classes")
                    .contains("jvm_classes_unloaded_classes_total");
            assertThat(seriesValue(scrape, "jvm_classes_loaded_classes"))
                    .as("a JVM with zero loaded classes is not a JVM")
                    .isGreaterThan(0d);
        });
    }

    @Test
    @DisplayName("the application's own config does not disable the meters the rules need")
    void applicationPropertiesDoesNotDisableThem() throws IOException {
        // The auto-configuration above proves the metric exists by default; this proves the
        // deployed configuration has not turned it off behind the rule's back. Both halves
        // are needed — either one alone is a false reassurance.
        Path properties = List.of(
                        Path.of("src", "main", "resources", "application.properties"),
                        Path.of("bot-app", "src", "main", "resources", "application.properties"))
                .stream().filter(Files::isRegularFile).findFirst()
                .orElseThrow(() -> new AssertionError("application.properties not found from "
                        + Path.of("").toAbsolutePath()));

        String text = Files.readString(properties);

        assertThat(text)
                .as("management.metrics.enable.* switches off whole meter families by prefix; "
                        + "any of these would silently blind BotManagerRestarted / JvmThreadsHigh")
                .doesNotContain("management.metrics.enable.process=false")
                .doesNotContain("management.metrics.enable.jvm=false")
                .doesNotContain("management.metrics.enable.all=false");
        assertThat(text)
                .as("Prometheus cannot scrape what actuator does not expose")
                .contains("management.prometheus.metrics.export.enabled=true")
                .contains("prometheus");
    }

    /** Value of the {@code jvm_memory_used_bytes} sample whose {@code id} is Metaspace. */
    private static double metaspaceValue(String scrape) {
        for (String line : scrape.split("\n")) {
            if (line.startsWith("#") || !line.startsWith("jvm_memory_used_bytes")) continue;
            if (!line.contains("id=\"Metaspace\"")) continue;
            String[] parts = line.trim().split("\\s+");
            return Double.parseDouble(parts[parts.length - 1]);
        }
        throw new AssertionError("no jvm_memory_used_bytes sample with id=\"Metaspace\"");
    }

    /** First sample value of {@code name} in a Prometheus exposition. */
    private static double seriesValue(String scrape, String name) {
        for (String line : scrape.split("\n")) {
            if (line.startsWith("#") || !line.startsWith(name)) continue;
            String[] parts = line.trim().split("\\s+");
            return Double.parseDouble(parts[parts.length - 1]);
        }
        throw new AssertionError("no sample line for " + name + " in the exposition");
    }
}
