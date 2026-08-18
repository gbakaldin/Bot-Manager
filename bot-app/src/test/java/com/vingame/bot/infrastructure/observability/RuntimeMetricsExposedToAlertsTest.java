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
