package com.vingame.bot.infrastructure.client;

import com.vingame.websocketparser.auth.AuthContext;
import com.vingame.websocketparser.auth.LoginRequest;
import com.vingame.websocketparser.auth.TokensProvider;
import com.vingame.websocketparser.exception.WebSocketParserException;
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
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>{@link BoundedLogin} really does give up, and really does release the exchange it gave up
 * on</b> (GATEWAY_REQUEST_BUDGET A19, A20.1).
 * <p>
 * <b>Why QA is adding this.</b> The bound is the prerequisite for enforcement being correct:
 * {@code ESSENTIAL}'s {@code max-wait} is {@code 0} — unbounded — on the deliberate premise that
 * the only thing waited on unboundedly is a window that drains by construction. Underneath it sat
 * a login with <b>no request timeout and no connect timeout</b> (verified in the
 * {@code websocket-parser-core-3.0.5} sources: {@code AuthClient} builds its {@code HttpRequest}
 * with no {@code .timeout(...)} on an {@code HttpClient.newHttpClient()}), so a stalled TCP
 * connection parked a bot-creation thread for the life of the JVM — holding a
 * {@code bot.creation.parallelism} permit and, through the group lock, every later {@code /stop}
 * and {@code DELETE} for that group. That is {@code FOLLOWUPS.md} P13, and Phase 3 is where it had
 * to stop being reachable.
 * <p>
 * What shipped to cover it was a <b>source guard</b>: {@code GatewayCallSiteGuardTest} greps
 * {@code BoundedLogin.java} for the strings {@code extends AuthClient}, {@code LOGIN_TIMEOUT} and
 * {@code shutdownNow()}. That proves the mechanism is <em>mentioned</em>, not that it works — a
 * {@code login()} that never consulted its bound, or an {@code abort()} whose exception was
 * swallowed before {@code shutdownNow()} was reached, passes it. This file exercises the behaviour.
 * <p>
 * <b>On the socket.</b> A {@link ServerSocket} bound to the <b>loopback address on an ephemeral
 * port</b>, which accepts the connection and never answers — the exact failure being defended
 * against. Nothing here contains a hostname, and nothing here can reach a gwms host: a login
 * against one could block a whole brand at the Cloudflare edge, for which the user has confirmed
 * there is no tolerable cooldown (possibly ~24 hours, possibly until someone clears it by hand).
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@DisplayName("BoundedLogin — the login cannot park a build thread for ever")
class BoundedLoginTest {

    /** Short enough to keep the suite fast, long enough that the bound is what expires. */
    private static final Duration BOUND = Duration.ofMillis(400);

    private ServerSocket server;
    /** Completes when the server observes the client hanging up — i.e. when the abort landed. */
    private CompletableFuture<Void> clientHungUp;
    private Thread acceptor;

    @BeforeEach
    void setUp() throws Exception {
        server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        clientHungUp = new CompletableFuture<>();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (acceptor != null) {
            acceptor.interrupt();
        }
        server.close();
    }

    /** A server that accepts, reads the request, and then answers nothing at all. */
    private void serveSilence() {
        acceptor = Thread.ofVirtual().name("silent-gateway").start(() -> {
            try (Socket socket = server.accept(); InputStream in = socket.getInputStream()) {
                byte[] buffer = new byte[4096];
                // Read until the peer goes away. A well-behaved client parked in send() keeps the
                // connection open indefinitely; the abort is what makes this return -1 or throw.
                while (in.read(buffer) >= 0) {
                    // keep reading, answer nothing
                }
                clientHungUp.complete(null);
            } catch (IOException e) {
                // A reset is a hang-up too — shutdownNow() may close abruptly.
                clientHungUp.complete(null);
            }
        });
    }

    /** A server that answers one canned body and closes. */
    private void serve(String body) {
        acceptor = Thread.ofVirtual().name("answering-gateway").start(() -> {
            try (Socket socket = server.accept();
                 InputStream in = socket.getInputStream();
                 OutputStream out = socket.getOutputStream()) {
                byte[] buffer = new byte[8192];
                in.read(buffer);
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                        + body.getBytes().length + "\r\nConnection: close\r\n\r\n" + body).getBytes());
                out.flush();
            } catch (IOException e) {
                clientHungUp.completeExceptionally(e);
            }
        });
    }

    private AuthContext ctx() {
        return new AuthContext("http://127.0.0.1:" + server.getLocalPort(),
                "authtestws1", "123123a", "app-1", "fp", "/gwms/v1/bot/login.aspx", null);
    }

    /** Public getters are what the library's ObjectMapper serialises. */
    public static final class StubLoginRequest implements LoginRequest {
        public String getUsername() {
            return "authtestws1";
        }
    }

    @Test
    @DisplayName("a gateway that never answers costs the caller the bound, not the JVM's lifetime")
    void aSilentGatewayCostsTheBoundAndNotForever() {
        serveSilence();

        long before = System.nanoTime();
        assertThatThrownBy(() -> BoundedLogin.login(ctx(), c -> new StubLoginRequest(),
                "authtestws1", BOUND))
                .as("an IOException on purpose: it lands in ApiGatewayClient.authenticate's "
                        + "existing IOException arm and becomes an UpstreamLoginException, which "
                        + "is the truth — the request was sent and the gateway did not answer")
                .isInstanceOf(HttpTimeoutException.class)
                .isInstanceOf(IOException.class)
                .hasMessageContaining("authtestws1")
                .hasMessageContaining("did not answer");
        Duration waited = Duration.ofNanos(System.nanoTime() - before);

        assertThat(waited)
                .as("it really waited for the bound rather than failing for some other reason")
                .isGreaterThanOrEqualTo(BOUND);
        assertThat(waited)
                .as("and it really gave up — this is the assertion the class timeout would "
                        + "otherwise have to make")
                .isLessThan(Duration.ofSeconds(15));
    }

    @Test
    @DisplayName("the abandoned exchange is aborted, not orphaned — the socket is really closed")
    void theAbandonedExchangeIsAborted() throws Exception {
        // The half that the bound alone does not buy. Without abort() the worker thread stays
        // parked in send() holding this socket for the life of the JVM: the leak would move from
        // the caller to a thread nobody can see, still holding a connection against a gateway we
        // have given up on, and still holding one of the library's SelectorManager threads. The
        // server observing the hang-up is the only externally visible proof that shutdownNow()
        // reached the client the parked send() was using.
        serveSilence();

        assertThatThrownBy(() -> BoundedLogin.login(ctx(), c -> new StubLoginRequest(),
                "authtestws1", BOUND))
                .isInstanceOf(HttpTimeoutException.class);

        assertThat(clientHungUp)
                .as("the connection must be gone shortly after the timeout; a still-open socket "
                        + "means the worker is still parked on it")
                .succeedsWithin(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("a successful login is returned unchanged, tokens and all")
    void aSuccessfulLoginIsUnchanged() throws Exception {
        // The bound must not change the happy path: this is every bot's login on every brand.
        // `token` is the AGENCY token and `session_id` is the auth token — the opposite way round
        // from the internal names, and the row in CLAUDE.md that was wrong for a long time.
        serve("{\"status\":\"OK\",\"code\":200,\"data\":[{\"token\":\"18-agency\","
                + "\"session_id\":\"session-1\",\"token2\":\"jwt-1\"}],\"message\":\"OK\"}");

        TokensProvider tokens = BoundedLogin.login(ctx(), c -> new StubLoginRequest(),
                "authtestws1", Duration.ofSeconds(10));

        assertThat(tokens.getAgencyToken()).isEqualTo("18-agency");
        assertThat(tokens.getAuthToken()).isEqualTo("session-1");
        assertThat(tokens.getJwtToken()).isEqualTo("jwt-1");
    }

    @Test
    @DisplayName("a library failure keeps its own type rather than becoming IllegalStateException")
    void aLibraryFailureKeepsItsType() {
        // rethrowUnchecked's contract, and it is load-bearing two frames up: every brand's login
        // failure is handled by ApiGatewayClient.authenticate's `catch (RuntimeException)` arm,
        // which turns it into an UpstreamLoginException carrying the upstream text. Wrapping here
        // would have sent every login failure on every brand into the IllegalStateException
        // bucket — a 500 with a sanitised body instead of a 502 naming the cause, invisible in a
        // diff and total in production.
        serve("<!DOCTYPE html><html>Sorry, you have been blocked</html>");

        assertThatThrownBy(() -> BoundedLogin.login(ctx(), c -> new StubLoginRequest(),
                "authtestws1", Duration.ofSeconds(10)))
                .isInstanceOf(WebSocketParserException.class)
                .isNotInstanceOf(IllegalStateException.class);
    }
}
