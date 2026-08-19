package com.vingame.bot.infrastructure.logging;

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
 * Runs {@code evidence-shim/selftest.py} as part of {@code mvn test}, exactly as
 * {@code VipTalkShimSelfTestRunnerTest} does for the other shim.
 * <p>
 * Same argument: a test suite outside the Maven reactor is documentation — it goes stale
 * the first time the script changes, and it goes stale silently. The evidence shim runs
 * only when something has already gone wrong, so the cost of a silent regression is that
 * the logs for the one incident anyone cared about were swept on schedule.
 * <p>
 * Deterministic and network-free: the suite builds a temporary logs directory, binds
 * loopback sockets on ephemeral ports, and checks inodes rather than mocking the
 * filesystem. Skipped (not failed) where {@code python3} is absent — the container that
 * actually runs the shim is {@code python:3.12-alpine}, and its presence there is asserted
 * by {@code AlertPipelineWiringTest}.
 */
@DisplayName("evidence-shim/selftest.py runs green (LOG_VOLUME_TIERING Phase 3)")
class EvidenceShimSelfTestRunnerTest {

    private static final List<Path> CANDIDATE_PATHS = List.of(
            Path.of("..", "evidence-shim", "selftest.py"),
            Path.of("evidence-shim", "selftest.py"));

    @Test
    @DisplayName("every check in the evidence shim's own suite passes")
    void shimSelfTestPasses() throws IOException, InterruptedException {
        Path script = CANDIDATE_PATHS.stream().filter(Files::isRegularFile).findFirst().orElse(null);
        Assumptions.assumeTrue(script != null,
                "evidence-shim/selftest.py not found from " + Path.of("").toAbsolutePath());
        Assumptions.assumeTrue(python3Available(), "python3 not on PATH");

        Process process = new ProcessBuilder("python3", script.toAbsolutePath().toString())
                .directory(script.toAbsolutePath().getParent().toFile())
                .redirectErrorStream(true)
                .start();

        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean finished = process.waitFor(120, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new AssertionError("evidence-shim/selftest.py did not finish within 120s:\n" + output);
        }

        assertThat(process.exitValue())
                .as("evidence-shim/selftest.py failed:\n%s", output)
                .isZero();
        assertThat(output)
                .as("the suite must actually have run its checks, not exited early")
                .contains("all checks passed")
                .doesNotContain("  FAIL ");
    }

    private static boolean python3Available() {
        try {
            Process p = new ProcessBuilder("python3", "--version").redirectErrorStream(true).start();
            return p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }
}
