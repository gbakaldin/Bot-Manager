package com.vingame.bot.common.testsupport;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.fail;

/**
 * Shared preconditions for the two Python shim self-test runners
 * ({@code VipTalkShimSelfTestRunnerTest}, {@code EvidenceShimSelfTestRunnerTest}).
 * <p>
 * <b>Why this exists.</b> Both runners used to guard on
 * {@code Assumptions.assumeTrue(python3Available())}, so a build machine without
 * {@code python3} went green having run <em>neither</em> suite — and those two Python files
 * are the only test coverage either shim has. `viptalk-shim` is what delivers alerts and
 * `evidence-shim` is what preserves the logs of an incident; a silent "we did not test the
 * thing that runs when everything else is broken" is the worst shape that gap can take.
 * <p>
 * So a missing {@code python3} is now a <b>failure</b>, and skipping is an explicit,
 * recorded decision: {@code -Dshim.selftest.skip=true} or {@code SHIM_SELFTEST_SKIP=true}.
 */
public final class ShimSelfTest {

    public static final String PROPERTY = "shim.selftest.skip";
    public static final String ENVIRONMENT_VARIABLE = "SHIM_SELFTEST_SKIP";

    public static final String OPT_OUT_REASON =
            "shim self-tests skipped by explicit request (-D" + PROPERTY + "=true)";

    private ShimSelfTest() {
    }

    /** Deliberate opt-out. Deliberate is the point: absence of python3 is not one. */
    public static boolean optedOut() {
        return Boolean.parseBoolean(System.getProperty(PROPERTY))
                || Boolean.parseBoolean(System.getenv(ENVIRONMENT_VARIABLE));
    }

    /** Fail loudly — not skip — when python3 is unavailable. */
    public static void requirePython3() {
        if (!python3Available()) {
            fail("python3 is not on PATH, so the shim self-tests cannot run. They are the only "
                    + "coverage viptalk-shim and evidence-shim have, so this is a build failure "
                    + "rather than a silent skip. Install python3, or opt out deliberately with "
                    + "-D" + PROPERTY + "=true / " + ENVIRONMENT_VARIABLE + "=true.");
        }
    }

    private static boolean python3Available() {
        try {
            Process process = new ProcessBuilder("python3", "--version")
                    .redirectErrorStream(true).start();
            return process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }
}
