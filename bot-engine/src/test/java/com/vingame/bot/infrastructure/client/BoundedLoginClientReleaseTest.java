package com.vingame.bot.infrastructure.client;

import com.vingame.websocketparser.auth.AuthContext;
import com.vingame.websocketparser.auth.LoginRequest;
import com.vingame.websocketparser.auth.TokensProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link BoundedLogin} releases its JDK {@code HttpClient} on the <b>success</b> and
 * <b>library-failure</b> paths, not only on the timeout (GATEWAY_REQUEST_BUDGET Phase 3 review
 * finding F6).
 *
 * <p><b>Why QA is adding this.</b> F6 is the finding with the most direct production history:
 * before it, {@code login()} returned from the success path without touching the client, so every
 * login — the normal path, on every bot, on every start, every periodic-logout cycle and every
 * reconnect — left a JDK {@code HttpClient} and its {@code SelectorManager} <b>platform</b> thread
 * alive until GC happened to reclaim an unreachable object. That is the plan's own Findings item
 * ("a 3k-bot start briefly spawns ~3k platform threads") and the shape MEMORY records as the Bot-1
 * thread-leak sawtooth, where the JVM died roughly daily on native-thread exhaustion.
 *
 * <p>The fix shipped in {@code 5e46af5} and the existing {@code BoundedLoginTest} does not cover
 * it: {@code aSuccessfulLoginIsUnchanged} asserts the returned tokens, which is exactly as true
 * with the leak as without it. {@code theAbandonedExchangeIsAborted} covers the timeout path only
 * — the one path that was never broken.
 *
 * <p><b>How it is measured, and why the measurement is not vacuous.</b> The JDK names that thread
 * {@code HttpClient-<n>-SelectorManager} and {@code HttpClient.shutdownNow()} makes it exit
 * promptly; an unreleased client keeps it until GC. Counting a thread by name is only worth
 * anything if the name is right, so {@link #theProbeCanSeeALiveLoginsHttpClient()} runs first and
 * asserts the count <b>rises</b> while a login is genuinely in flight. Without that, a typo in the
 * pattern would make every assertion below read zero and pass for the worst possible reason.
 *
 * <p><b>Never a real gateway.</b> A {@link ServerSocket} on the loopback address, ephemeral port.
 * Nothing here contains a hostname. A login against a gwms host could block a whole brand at the
 * Cloudflare edge, for which there is no tolerable cooldown.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
@DisplayName("BoundedLogin — the login's HttpClient is released on every path, not just the timeout")
class BoundedLoginClientReleaseTest {

    private static final Pattern SELECTOR =
            Pattern.compile("^HttpClient-\\d+-SelectorManager$");

    private static final String OK_BODY =
            "{\"status\":\"OK\",\"code\":200,\"data\":[{\"token\":\"18-agency\","
                    + "\"session_id\":\"session-1\",\"token2\":\"jwt-1\"}],\"message\":\"OK\"}";

    private ServerSocket server;
    private Thread acceptor;
    private final AtomicBoolean stopping = new AtomicBoolean();

    @BeforeEach
    void setUp() throws Exception {
        server = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
    }

    @AfterEach
    void tearDown() throws Exception {
        stopping.set(true);
        if (acceptor != null) {
            acceptor.interrupt();
        }
        server.close();
    }

    /** Answers {@code body} to every connection until the test ends. */
    private void serveRepeatedly(String body) {
        acceptor = Thread.ofVirtual().name("answering-gateway").start(() -> {
            while (!stopping.get()) {
                try (Socket socket = server.accept();
                     InputStream in = socket.getInputStream();
                     OutputStream out = socket.getOutputStream()) {
                    in.read(new byte[8192]);
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                            + "Content-Length: " + body.getBytes().length
                            + "\r\nConnection: close\r\n\r\n" + body).getBytes());
                    out.flush();
                } catch (IOException e) {
                    return;
                }
            }
        });
    }

    /** Accepts and never answers — a login that is genuinely parked. */
    private void serveSilence() {
        acceptor = Thread.ofVirtual().name("silent-gateway").start(() -> {
            try (Socket socket = server.accept(); InputStream in = socket.getInputStream()) {
                byte[] buffer = new byte[4096];
                while (in.read(buffer) >= 0) {
                    // answer nothing
                }
            } catch (IOException e) {
                // a reset is fine
            }
        });
    }

    private AuthContext ctx() {
        return new AuthContext("http://127.0.0.1:" + server.getLocalPort(),
                "authtestws1", "123123a", "app-1", "fp", "/gwms/v1/bot/login.aspx", null);
    }

    public static final class StubLoginRequest implements LoginRequest {
        public String getUsername() {
            return "authtestws1";
        }
    }

    private static Set<String> liveSelectorThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .map(Thread::getName)
                .filter(name -> SELECTOR.matcher(name).matches())
                .collect(Collectors.toSet());
    }

    /**
     * Wait for the selector-thread set to shrink back to (at most) {@code baseline}. A deadline
     * rather than a sleep: {@code shutdownNow()} is prompt but not synchronous, and a fixed sleep
     * would be either flaky or slow. Crucially, the count does <b>not</b> come down on its own
     * within this window if the client was never released — reclaiming it needs a GC that nothing
     * here triggers.
     */
    private static Set<String> awaitSelectorsDownTo(int baseline) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        Set<String> live = liveSelectorThreads();
        while (live.size() > baseline && System.nanoTime() < deadline) {
            Thread.sleep(50);
            live = liveSelectorThreads();
        }
        return live;
    }

    @Test
    @DisplayName("the probe can see a live login's HttpClient — so a zero below means something")
    void theProbeCanSeeALiveLoginsHttpClient() throws Exception {
        serveSilence();
        int baseline = liveSelectorThreads().size();

        Thread login = Thread.ofVirtual().name("parked-login").start(() -> {
            try {
                BoundedLogin.login(ctx(), c -> new StubLoginRequest(), "authtestws1",
                        Duration.ofSeconds(3));
            } catch (Exception expected) {
                // the bound expires; that is this test's exit, not its subject
            }
        });

        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        int peak = liveSelectorThreads().size();
        while (peak <= baseline && System.nanoTime() < deadline) {
            Thread.sleep(20);
            peak = liveSelectorThreads().size();
        }

        assertThat(peak)
                .as("a login in flight MUST show an extra %s thread. If this fails the pattern "
                        + "is wrong or the JDK renamed the thread, and every other assertion in "
                        + "this class is silently vacuous", SELECTOR.pattern())
                .isGreaterThan(baseline);

        login.join(TimeUnit.SECONDS.toMillis(20));
    }

    @Test
    @DisplayName("a successful login releases its HttpClient — the leak F6 closed")
    void aSuccessfulLoginReleasesItsHttpClient() throws Exception {
        serveRepeatedly(OK_BODY);
        int baseline = liveSelectorThreads().size();

        // Several, because one leaked client is one thread and could be mistaken for noise from
        // another class in the same fork. Four is unambiguous, and four is also what a modest
        // group start does in a second.
        for (int i = 0; i < 4; i++) {
            TokensProvider tokens = BoundedLogin.login(ctx(), c -> new StubLoginRequest(),
                    "authtestws1", Duration.ofSeconds(10));
            assertThat(tokens.getAgencyToken()).isEqualTo("18-agency");
        }

        assertThat(awaitSelectorsDownTo(baseline))
                .as("every successful login must give its HttpClient back. Before F6 this path "
                        + "returned without touching the client, so a 3k-bot start left ~3k "
                        + "SelectorManager PLATFORM threads for GC — the Bot-1 sawtooth")
                .hasSizeLessThanOrEqualTo(baseline);
    }

    @Test
    @DisplayName("a library failure releases its HttpClient too")
    void aLibraryFailureReleasesItsHttpClient() throws Exception {
        // The Cloudflare block page is the realistic shape: a 200-looking HTML body the library's
        // parser rejects. It is also the moment a fleet produces the MOST of them at once, which
        // is precisely when leaking one client per attempt is least survivable.
        serveRepeatedly("<!DOCTYPE html><html>Sorry, you have been blocked</html>");
        int baseline = liveSelectorThreads().size();

        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> BoundedLogin.login(ctx(), c -> new StubLoginRequest(),
                    "authtestws1", Duration.ofSeconds(10)))
                    .isInstanceOf(RuntimeException.class);
        }

        assertThat(awaitSelectorsDownTo(baseline))
                .as("the ExecutionException arm releases the client as well; a fleet-wide login "
                        + "failure is when a per-attempt leak compounds fastest")
                .hasSizeLessThanOrEqualTo(baseline);
    }
}
