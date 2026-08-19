package com.vingame.bot.infrastructure.logging;

import com.vingame.bot.common.logging.ScopedDebugRegistry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

/**
 * {@code ScopedDebugInstaller.stop()} must be ordered against the sweeper it starts.
 * <p>
 * <b>Why this test exists.</b> The sweeper re-asserts attachment
 * ({@code if (installed && !isAttached()) install()}) so that a re-install lost on the
 * re-entrancy CAS is not permanent. {@code shutdownNow()} interrupts but does not wait, and a
 * sweep blocks on nothing interruptible, so a sweep in flight when {@code stop()} runs
 * executes <em>concurrently with teardown</em> and sees its intermediate states. With the
 * flags cleared last, the intermediate state between "filter detached" and
 * "{@code installed = false}" reads exactly like the live-but-detached state the re-assert
 * was written for — so the straggler re-attached the filter to the JVM-global
 * {@code Configuration} after {@code stop()} had removed it, permanently (removal is
 * identity-scoped, so no later installer takes it off), bound to a registry already
 * {@code clear()}ed. A straggler landing after the flag clear instead took the
 * {@code !installed} branch and registered a second {@code PropertyChangeListener} on the
 * global context that nothing would ever remove.
 * <p>
 * <b>Deterministic, not timed.</b> Nothing here races a real thread against a real
 * {@code stop()} and hopes to hit a window. The straggler sweep is injected <em>at</em> the
 * interleaving point instead: {@code stop()} calls {@code registry.clear()} after it has
 * detached the filter, so a registry whose {@code clear()} runs a sweep reproduces the worst
 * ordering on every run, on any box. {@link #theReAssertReallyDoesReattachWhileTheInstallerIsLive()}
 * is the control that keeps the other cases from passing vacuously.
 */
@DisplayName("ScopedDebugInstaller teardown ordering")
class ScopedDebugInstallerTeardownTest {

    private LoggerContext ctx;
    private ScopedDebugInstaller installer;

    /** Installers created inside a test, so a failed assertion cannot leak a filter. */
    private final List<ScopedDebugInstaller> extras = new ArrayList<>();

    @BeforeEach
    void setUp() {
        ctx = (LoggerContext) LogManager.getContext(false);
        installer = new ScopedDebugInstaller(new ScopedDebugRegistry(), true);
        // install() rather than start(): no sweeper thread is wanted here, the sweep is
        // driven by hand. stop() handles a null sweeper.
        installer.install();
        assertThat(installer.isInstalled()).as("precondition: the filter is attached").isTrue();
    }

    @AfterEach
    void tearDown() {
        installer.stop();
        for (ScopedDebugInstaller extra : extras) {
            extra.stop();
            // Belt and braces: if the behaviour under test regressed, stop() is exactly the
            // method that failed to detach, and the leaked filter would follow the shared
            // LoggerContext into every later test in this JVM.
            ctx.getConfiguration().removeFilter(extra.installedFilter());
        }
        assertThat(installer.isAttached()).isFalse();
    }

    @Test
    @DisplayName("control: while the installer is live, a sweep DOES re-attach a lost filter")
    void theReAssertReallyDoesReattachWhileTheInstallerIsLive() {
        detachSilently(installer);
        assertThat(installer.isAttached()).isFalse();

        installer.sweepOnce();

        assertThat(installer.isAttached())
                .as("if this ever goes false the teardown tests below prove nothing")
                .isTrue();
    }

    @Test
    @DisplayName("a sweep landing mid-teardown cannot re-attach the filter")
    void anInFlightSweepCannotReattachDuringStop() {
        ScopedDebugRegistry racyRegistry = spy(new ScopedDebugRegistry());
        ScopedDebugInstaller racing = new ScopedDebugInstaller(racyRegistry, true);
        extras.add(racing);
        // registry.clear() is stop()'s last step, and it runs AFTER the filter has been
        // detached — the exact window the sweeper thread used to win.
        doAnswer(invocation -> {
            invocation.callRealMethod();
            racing.sweepOnce();
            return null;
        }).when(racyRegistry).clear();
        racing.install();
        assertThat(racing.isAttached()).isTrue();

        racing.stop();

        assertThat(racing.isAttached())
                .as("the straggler re-attached a filter bound to a cleared registry, and "
                        + "identity-scoped removal means nobody would ever take it off again")
                .isFalse();
        assertThat(racing.isInstalled()).isFalse();
    }

    @Test
    @DisplayName("a sweep that lands after stop() has returned resurrects nothing")
    void aStragglerSweepAfterStopResurrectsNothing() {
        installer.stop();

        installer.sweepOnce();

        assertThat(installer.isAttached()).isFalse();
        assertThat(installer.isInstalled()).isFalse();
    }

    @Test
    @DisplayName("install() is refused for good after stop(), so no second listener is left behind")
    void installIsRefusedAfterStop() {
        installer.stop();

        installer.install();

        assertThat(installer.isAttached())
                .as("after stop() returns, no code path may call install() on this instance")
                .isFalse();
        assertThat(installer.isInstalled()).isFalse();

        // The stale-listener tell: updateLoggers() fires the context's `config` property
        // change, which is what a surviving listener would answer by re-installing.
        ctx.updateLoggers();

        assertThat(installer.isAttached())
                .as("stop() must take its reconfiguration listener with it")
                .isFalse();
    }

    /**
     * Strip the installer's filter without {@code updateLoggers()} — that fires the
     * {@code config} property change, which the installer's own listener answers by
     * re-installing, so a test that called it would repair the state it is trying to create.
     */
    private void detachSilently(ScopedDebugInstaller target) {
        ctx.getConfiguration().removeFilter(target.installedFilter());
    }
}
