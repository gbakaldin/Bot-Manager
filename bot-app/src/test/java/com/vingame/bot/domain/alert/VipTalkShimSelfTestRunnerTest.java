package com.vingame.bot.domain.alert;

import com.vingame.bot.common.testsupport.ShimSelfTest;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs {@code viptalk-shim/selftest.py} as part of {@code mvn test}.
 * <p>
 * The shim is a real service on a real delivery path, and it has a real test suite —
 * but that suite lives outside the Maven reactor, so nothing ran it. A test nobody runs
 * is documentation: it goes stale the first time the script changes, and it goes stale
 * silently. This bridges the two, so a change to {@code shim.py} that breaks the outage
 * path fails the same build as a change to any Java file would.
 * <p>
 * Deterministic and network-free: the self-test binds loopback sockets on ephemeral
 * ports and stands up its own stub for {@code api.viptalk.org}.
 * <p>
 * <b>A missing {@code python3} FAILS the build</b> — see {@link ShimSelfTest} for why that
 * changed from a silent skip. Opting out is possible but must be deliberate:
 * {@code -Dshim.selftest.skip=true}.
 */
@DisplayName("viptalk-shim/selftest.py runs green (Phase 6 out-of-band delivery)")
class VipTalkShimSelfTestRunnerTest {

    private static final List<Path> CANDIDATE_PATHS = List.of(
            Path.of("..", "viptalk-shim", "selftest.py"),
            Path.of("viptalk-shim", "selftest.py"));

    @Test
    @DisplayName("every check in the shim's own suite passes")
    void shimSelfTestPasses() throws IOException, InterruptedException {
        Assumptions.assumeFalse(ShimSelfTest.optedOut(), ShimSelfTest.OPT_OUT_REASON);
        Path script = CANDIDATE_PATHS.stream().filter(Files::isRegularFile).findFirst().orElse(null);
        Assumptions.assumeTrue(script != null,
                "viptalk-shim/selftest.py not found from " + Path.of("").toAbsolutePath());
        ShimSelfTest.requirePython3();

        Process process = new ProcessBuilder("python3", script.toAbsolutePath().toString())
                .directory(script.toAbsolutePath().getParent().toFile())
                .redirectErrorStream(true)
                .start();

        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean finished = process.waitFor(120, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new AssertionError("viptalk-shim/selftest.py did not finish within 120s:\n" + output);
        }

        assertThat(process.exitValue())
                .as("viptalk-shim/selftest.py failed:\n%s", output)
                .isZero();
        assertThat(output)
                .as("the suite must actually have run its checks, not exited early")
                .contains("all checks passed")
                .doesNotContain("  FAIL ");
    }
}
