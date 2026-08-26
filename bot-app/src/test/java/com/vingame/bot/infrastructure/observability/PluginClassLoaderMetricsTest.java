package com.vingame.bot.infrastructure.observability;

import com.vingame.bot.common.plugin.PluginVersions;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.net.URL;
import java.net.URLClassLoader;
import java.time.Duration;

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
 * <p>
 * <b>The escape is bounded, and the bound is the point.</b> An unqualified
 * {@code assumeTrue(reclaimed)} would skip in two situations that look identical from
 * inside the polling loop and are not remotely the same: the JVM declined to collect
 * (inconclusive, fine) and <em>the JVM collected it but our accounting never noticed</em>
 * (the defect). The second is not hypothetical — it is what happens the moment
 * {@code PluginClassLoaderMetrics} stops strongly holding its own {@code VersionedRef}s,
 * and it is the failure this whole class was written against, because in production it
 * presents as a leak detector that reads flat forever. So the test keeps a
 * <b>control</b> {@link java.lang.ref.WeakReference} to the same loader, registered with no
 * queue: if the control has been cleared and {@code drain()} still saw nothing, that is a
 * hard failure and only a genuinely uncollected loader may skip.
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
        assertThat(counter(PluginClassLoaderMetrics.REGISTERED, PluginVersions.BUILTIN)).isEqualTo(1d);
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

        // The control. A second weak reference to the same loader, deliberately registered
        // with NO ReferenceQueue and held only by this frame, so it answers one question the
        // instrument under test cannot be trusted to answer about itself: did the JVM
        // actually collect the loader? See CONTROL below for why that distinction is the
        // whole point of this test.
        WeakReference<ClassLoader> control = new WeakReference<>(throwaway);
        metrics.register(version, throwaway);

        assertThat(gauge(PluginClassLoaderMetrics.LIVE, version))
                .as("registration must be visible before the drop, or the test proves nothing")
                .isEqualTo(1d);
        assertThat(counter(PluginClassLoaderMetrics.REGISTERED, version)).isEqualTo(1d);

        // Drop the last strong reference. Everything after this point is the JVM's choice.
        throwaway = null;
        assertThat(throwaway).isNull(); // keep the null assignment from being optimised away

        boolean reclaimed = false;
        long collectedAt = 0;
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            System.gc();
            if (metrics.drain() > 0) {
                reclaimed = true;
                break;
            }
            // refersTo(null), not get() == null: get() hands the referent back out, which
            // under some collectors is enough to keep it alive for another cycle.
            if (collectedAt == 0 && control.refersTo(null)) {
                collectedAt = System.nanoTime();
            }
            // Clearing and enqueueing are not the same instant — ReferenceHandler runs on its
            // own thread — so once the control says "collected", allow a bounded grace period
            // for the queue before concluding anything.
            if (collectedAt != 0
                    && System.nanoTime() - collectedAt > Duration.ofSeconds(5).toNanos()) {
                break;
            }
            Thread.sleep(50);
        }

        if (!reclaimed) {
            // CONTROL. Without this branch the Assumptions escape below swallows the exact
            // regression this class exists to catch. If PluginClassLoaderMetrics stops
            // strongly holding its own VersionedRefs, they become unreachable, the GC never
            // enqueues them, drain() returns 0 forever, reclaimed_total never moves — and the
            // instrument reports "no leaks" by having lost its bookkeeping. That looks
            // identical, from inside the loop above, to "the JVM declined to run a GC", so a
            // bare assumeTrue(reclaimed) turns it into a green build with a silent skip.
            // Verified by mutation: deleting `tracked.add(...)` in register() leaves this test
            // SKIPPED, not failed. The control reference distinguishes the two cases, because
            // it is cleared by the same collection that should have enqueued ours.
            assertThat(control.refersTo(null))
                    .as("the JVM collected the throwaway classloader but drain() never observed "
                            + "it. That is not GC non-determinism — it is the ReferenceQueue "
                            + "wiring, or the strong-reference set that keeps VersionedRefs "
                            + "enqueueable, being broken. In production that failure mode is a "
                            + "leak detector that reads flat forever.")
                    .isFalse();
            Assumptions.abort(
                    "the JVM did not collect the throwaway classloader within 10s — System.gc() "
                            + "is a hint, so this is inconclusive rather than a failure. The "
                            + "production gauge is a LOWER BOUND on retention for the same "
                            + "reason. Note the accounting itself was NOT let off: the control "
                            + "weak reference above proves the loader was still reachable.");
        }

        assertThat(metrics.liveCount(version))
                .as("`live` is registered − reclaimed; a loader that was collected must leave it")
                .isZero();
        assertThat(gauge(PluginClassLoaderMetrics.LIVE, version)).isEqualTo(0d);
        assertThat(counter(PluginClassLoaderMetrics.RECLAIMED, version)).isEqualTo(1d);
        assertThat(counter(PluginClassLoaderMetrics.REGISTERED, version))
                .as("registered_total is cumulative — it must not decrease when a loader goes")
                .isEqualTo(1d);
    }

    @Test
    @DisplayName("two loaders on one version share one meter set and both are counted")
    void repeatedRegistrationUnderOneVersionIsIdempotentOnTheMeters() {
        // Guards the shape registerMeters() was changed to: computeIfAbsent mints only the
        // AtomicLong and the meters are registered OUTSIDE the mapping function (the shape
        // AsyncQueueMetrics already uses), because meter registration takes the registry's
        // locks and runs every MeterFilter — arbitrary third-party work inside a CHM mapping
        // function is the documented recursive-update hazard.
        //
        // The cost of moving it out is a duplicate registration call on the second
        // register(), so this pins that the duplicate is harmless: one gauge, bound to the
        // AtomicLong every caller increments, not a second series and not a rebind that
        // freezes the first at its initial value.
        String version = "shared-v1";
        ClassLoader first = new URLClassLoader("plugin-a", new URL[0], getClass().getClassLoader());
        ClassLoader second = new URLClassLoader("plugin-b", new URL[0], getClass().getClassLoader());

        metrics.register(version, first);
        metrics.register(version, second);

        // Two DISTINCT loaders under one version is the fact the meters are meant to show —
        // "this version was loaded twice" — as opposed to the same loader registered twice,
        // which is a caller bug and reads identically to a retention.
        assertThat(registry.find(PluginClassLoaderMetrics.LIVE)
                .tag(PluginClassLoaderMetrics.TAG, version).gauges())
                .as("one gauge series per version, not one per registration")
                .hasSize(1);
        assertThat(gauge(PluginClassLoaderMetrics.LIVE, version)).isEqualTo(2d);
        assertThat(counter(PluginClassLoaderMetrics.REGISTERED, version)).isEqualTo(2d);
        assertThat(counter(PluginClassLoaderMetrics.RECLAIMED, version)).isEqualTo(0d);
    }

    @Test
    @DisplayName("meter names are not bot_-prefixed, so the MDC tag filter cannot touch them")
    void meterNamesAreOutsideTheMdcFilterSPrefix() {
        // Same reasoning as AsyncQueueMetrics: these are JVM-wide facts with no owning
        // group, and BotMdcTagsMeterFilter would otherwise stamp them with whatever MDC the
        // 10 s sampler thread happened to inherit.
        assertThat(PluginClassLoaderMetrics.LIVE).doesNotStartWith("bot_");
        assertThat(PluginClassLoaderMetrics.REGISTERED).doesNotStartWith("bot_");
        assertThat(PluginClassLoaderMetrics.RECLAIMED).doesNotStartWith("bot_");
    }

    private double gauge(String name, String version) {
        return registry.get(name).tag(PluginClassLoaderMetrics.TAG, version).gauge().value();
    }

    private double counter(String name, String version) {
        return registry.get(name).tag(PluginClassLoaderMetrics.TAG, version).counter().count();
    }
}
