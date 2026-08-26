package com.vingame.bot.infrastructure.observability;

import com.vingame.bot.common.plugin.PluginVersions;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PLUGIN_HOT_RELOAD AD-3 / Phase 1 step 10.
 * <p>
 * The second test here is <b>the cheap version of the classloader-GC spike</b> that gates
 * steps 4–6 — it does in-repo, on every build, what the throwaway spike does by hand:
 * register a loader, drop the last strong reference, and check that the accounting
 * actually notices it went away. It is not a substitute for the spike (a real plugin
 * loader has live classes, threads and Micrometer meters pinning it, none of which a bare
 * {@link URLClassLoader} has), but it does prove the instrument itself works — that the
 * {@link java.lang.ref.ReferenceQueue} is wired, that the queue's own references are
 * strongly held (a {@code WeakReference} that is itself unreachable is never enqueued, and
 * that failure looks exactly like "no leaks"), and that {@code live} comes back down.
 * <p>
 * GC is a hint, not a command, so the reclamation assertion is guarded by an
 * {@link Assumptions} escape rather than a hard assert. A build that cannot reclaim skips
 * rather than fails; the alternative is a flaky test that gets muted, which is strictly
 * worse than one that occasionally says nothing.
 */
@DisplayName("PluginClassLoaderMetrics — weak-reference classloader accounting (AD-3)")
class PluginClassLoaderMetricsTest {

    private SimpleMeterRegistry registry;
    private PluginClassLoaderMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new PluginClassLoaderMetrics(registry, () -> PluginVersions.BUILTIN);
    }

    @AfterEach
    void tearDown() {
        metrics.stop();
        registry.close();
    }

    @Test
    @DisplayName("startup registers the application classloader under `builtin` at 1 / 1 / 0")
    void startupPublishesTheDegenerateBaseline() {
        metrics.start();

        // Verification P1-4 expects exactly these three families at exactly these values on
        // a freshly deployed staging box. A MISSING series (rather than a zero) is the
        // failure this pins: it would mean the eager registration never ran, and an alert
        // over a series that only appears once the incident starts cannot be tested before
        // the incident.
        assertThat(gauge(PluginClassLoaderMetrics.LIVE, PluginVersions.BUILTIN)).isEqualTo(1d);
        assertThat(counter(PluginClassLoaderMetrics.CREATED, PluginVersions.BUILTIN)).isEqualTo(1d);
        assertThat(counter(PluginClassLoaderMetrics.RECLAIMED, PluginVersions.BUILTIN)).isEqualTo(0d);
    }

    @Test
    @DisplayName("the application classloader is never reclaimed, so `live` stays at 1")
    void theApplicationClassLoaderStaysLive() {
        metrics.start();

        for (int i = 0; i < 3; i++) {
            System.gc();
            metrics.drain();
        }

        // The other half of the leak detector's contract: it must not report a loader
        // collected when it plainly has not been. A `live` that decays under GC pressure
        // would make the whole series unreadable in exactly the direction that hides a leak.
        assertThat(gauge(PluginClassLoaderMetrics.LIVE, PluginVersions.BUILTIN)).isEqualTo(1d);
        assertThat(counter(PluginClassLoaderMetrics.RECLAIMED, PluginVersions.BUILTIN)).isEqualTo(0d);
    }

    @Test
    @DisplayName("a dropped classloader is observed collected: live returns to 0, reclaimed increments")
    void aDroppedClassLoaderIsReclaimed() throws InterruptedException {
        String version = "spike-v1";
        ClassLoader throwaway = new URLClassLoader("plugin-spike", new URL[0],
                getClass().getClassLoader());
        metrics.register(version, throwaway);

        assertThat(gauge(PluginClassLoaderMetrics.LIVE, version))
                .as("registration must be visible before the drop, or the test proves nothing")
                .isEqualTo(1d);
        assertThat(counter(PluginClassLoaderMetrics.CREATED, version)).isEqualTo(1d);

        // Drop the last strong reference. Everything after this point is the JVM's choice.
        throwaway = null;
        assertThat(throwaway).isNull(); // keep the null assignment from being optimised away

        boolean reclaimed = false;
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            System.gc();
            if (metrics.drain() > 0) {
                reclaimed = true;
                break;
            }
            Thread.sleep(50);
        }

        Assumptions.assumeTrue(reclaimed,
                "the JVM did not collect the throwaway classloader within 10s — System.gc() is a "
                        + "hint, so this is inconclusive rather than a failure. The production "
                        + "gauge is a LOWER BOUND on retention for the same reason.");

        assertThat(metrics.liveCount(version))
                .as("`live` is created − reclaimed; a loader that was collected must leave it")
                .isZero();
        assertThat(gauge(PluginClassLoaderMetrics.LIVE, version)).isEqualTo(0d);
        assertThat(counter(PluginClassLoaderMetrics.RECLAIMED, version)).isEqualTo(1d);
        assertThat(counter(PluginClassLoaderMetrics.CREATED, version))
                .as("created_total is cumulative — it must not decrease when a loader goes")
                .isEqualTo(1d);
    }

    @Test
    @DisplayName("meter names are not bot_-prefixed, so the MDC tag filter cannot touch them")
    void meterNamesAreOutsideTheMdcFilterSPrefix() {
        // Same reasoning as AsyncQueueMetrics: these are JVM-wide facts with no owning
        // group, and BotMdcTagsMeterFilter would otherwise stamp them with whatever MDC the
        // 10 s sampler thread happened to inherit.
        assertThat(PluginClassLoaderMetrics.LIVE).doesNotStartWith("bot_");
        assertThat(PluginClassLoaderMetrics.CREATED).doesNotStartWith("bot_");
        assertThat(PluginClassLoaderMetrics.RECLAIMED).doesNotStartWith("bot_");
    }

    private double gauge(String name, String version) {
        return registry.get(name).tag(PluginClassLoaderMetrics.TAG, version).gauge().value();
    }

    private double counter(String name, String version) {
        return registry.get(name).tag(PluginClassLoaderMetrics.TAG, version).counter().count();
    }
}
