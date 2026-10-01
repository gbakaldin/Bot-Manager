package com.vingame.bot.infrastructure.client.stub;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.vingame.bot.infrastructure.gateway.CapturedBlockPage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * An in-process gwms gateway, on {@code 127.0.0.1}, for tests that need to <b>count real
 * requests</b> (GATEWAY_REQUEST_BUDGET AD-22).
 * <p>
 * <b>Why this has to exist.</b> The whole feature is one assertion — that no more than
 * {@code hard-cap} requests leave this JVM per window — and every test that stops short of an
 * actual HTTP call is measuring our own bookkeeping rather than the thing the Cloudflare edge
 * counts. {@link #countInLastWindow()} is the independent witness: it is the stub's own sliding
 * count of requests it actually received, computed from its own arrival stamps, with no code
 * shared with {@code SlidingWindowGatewayBudget}.
 * <p>
 * <b>It is never pointed at a real host, and nothing here can reach one.</b> The server binds
 * the loopback address on an ephemeral port and tests configure {@code ApiGatewayClient} with
 * {@code http://127.0.0.1:<port>}. That rule is not tidiness: the user has confirmed a
 * Cloudflare block has <b>no tolerable cooldown</b> — possibly ~24 hours, possibly until
 * someone clears it by hand — so a test that fired real traffic could take a brand down for a
 * day. {@code websocket-parser-test} / {@code websocket-parser-simple-test} were checked and
 * offer nothing usable here: both are harnesses for driving <em>real</em> game servers.
 * <p>
 * <b>What it answers</b>, with the envelope shapes captured from live staging traffic
 * ({@code docs/reviews/GATEWAY_REQUEST_BUDGET/gwms-register-envelope.md}):
 * <ul>
 *   <li>the login path (whatever {@code AuthProfile.loginPath()} is) — {@code data[0]} with
 *       {@code token} (agency), {@code session_id} (auth) and {@code token2} (JWT), because
 *       those three names are the opposite way round from the internal ones and a stub that
 *       got them wrong would make every login test pass for the wrong reason;</li>
 *   <li>{@code register.aspx} — {@code status:"OK"} with tokens, or {@code status:"EXISTED",
 *       code:409} at HTTP <b>200</b> once a username has been seen before. Both answers are
 *       200 on the real gateway, which is the fact Phase 4's resumability depends on. A username
 *       starting with {@link #REFUSED_USERNAME_PREFIX} is always refused with an {@code ERROR}
 *       envelope, so a test can make one index fail without failing the fixture;</li>
 *   <li>{@code update-fullname.aspx} — {@code status:"OK"};</li>
 *   <li>{@code verifytoken.aspx} — {@code data[0].main_balance};</li>
 *   <li>{@code deposit.aspx} — {@code status:"OK"}.</li>
 * </ul>
 * <p>
 * <b>Block mode</b> (Phase 5, AD-22). {@link #block()} — or {@code POST /__stub/block} from a shell,
 * which is how the manual V4d check drives {@link StubGatewayMain} — makes every gateway path answer
 * with the Cloudflare page this host really received on 2026-09-17, status and headers included
 * ({@code CapturedBlockPage}); {@link #unblock()} / {@code POST /__stub/unblock} restores normal
 * answers. A blocked request is still recorded and still stamped: the edge counts what arrives,
 * whatever it answers. The {@code /__stub/} control paths are neither.
 * <p>
 * <b>Every request is recorded</b> — method, path, query, headers, body — so the in-repo login can
 * be proven byte-identical to what the library's {@code AuthClient} sent (AD-12), by recording both
 * against this stub in the same test. The Netty WebSocket endpoint AD-22 also describes is still not
 * built: nothing tests through it, and the WS half of block detection is covered by classifying a
 * handshake exception directly.
 */
public final class StubGateway implements AutoCloseable {

    /** Arrival stamps of every request, oldest first. Guarded by {@link #stampLock}. */
    private final Deque<Long> stamps = new ArrayDeque<>();
    private final Object stampLock = new Object();

    private final List<String> paths = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Map<String, AtomicInteger> countsByPath = new ConcurrentHashMap<>();
    /**
     * The username prefix this stub always refuses, with an {@code ERROR} envelope at HTTP 200.
     * <p>
     * There has to be a way to make one index fail without making the whole fixture fail: "an
     * index that costs an attempt" and "an index that does not" are different code paths in
     * {@code RegistrationWorker}, and the difference between them is the difference between a
     * group that stops on a real refusal and a group that stops on our own pacing.
     */
    public static final String REFUSED_USERNAME_PREFIX = "reject";

    /** Usernames already registered, so a re-register can answer {@code EXISTED} like the real one. */
    private final Map<String, Boolean> registered = new ConcurrentHashMap<>();

    /** Every gateway request received, in arrival order. Control paths are not recorded. */
    private final List<RecordedRequest> requests = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Whether every gateway path currently answers with the captured Cloudflare block page. */
    private volatile boolean blocked;

    /** When non-null, the login path answers this body instead of a token envelope. */
    private volatile String loginResponseOverride;

    /** One request as the stub received it. Header names are as {@code HttpServer} normalised them. */
    public record RecordedRequest(String method, String path, String query,
                                  Map<String, List<String>> headers, String body) {

        /** The first value of a header, case-insensitively; {@code null} if absent. */
        public String header(String name) {
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                if (e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty()) {
                    return e.getValue().get(0);
                }
            }
            return null;
        }
    }

    private final AtomicLong tokenSeq = new AtomicLong();
    private final HttpServer server;
    private final Duration window;
    private final LongSupplier nanos;
    private volatile long balance = 1_000_000_000L;

    private StubGateway(Duration window, LongSupplier nanos, int port) throws IOException {
        this.window = window;
        this.nanos = nanos;
        // Loopback, explicitly, and (for tests) an ephemeral port so parallel test classes cannot
        // collide. Only StubGatewayMain asks for a fixed one.
        this.server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        this.server.createContext("/", this::handle);
        // Virtual threads: the escalation IT drives hundreds of concurrent requests, and a fixed
        // pool would make the stub the bottleneck being measured instead of the budget.
        this.server.setExecutor(Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("stub-gateway-", 0).factory()));
        this.server.start();
    }

    /** Start a stub with the real 5-minute window and the real clock. */
    public static StubGateway start() throws IOException {
        return new StubGateway(Duration.ofMinutes(5), System::nanoTime, 0);
    }

    /**
     * Start a stub on a fixed loopback port, real window and real clock — for {@link StubGatewayMain},
     * where a locally running app's {@code Environment} has to be pointed at a known URL.
     */
    public static StubGateway startOnPort(int port) throws IOException {
        return new StubGateway(Duration.ofMinutes(5), System::nanoTime, port);
    }

    /**
     * Start a stub with an explicit window and clock, so a test can assert the sliding count
     * without waiting five real minutes.
     */
    public static StubGateway start(Duration window, LongSupplier nanos) throws IOException {
        return new StubGateway(window, nanos, 0);
    }

    /** The base URL to hand {@code ApiGatewayClient.init}. Always a loopback address. */
    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /**
     * How many requests this stub has <b>actually received</b> in the last window.
     * <p>
     * The independent witness, and the reason it shares no code with the budget: if the app's own
     * accounting and the stub's count could only ever agree, the assertion would be circular.
     */
    public int countInLastWindow() {
        long now = nanos.getAsLong();
        synchronized (stampLock) {
            long cutoff = now - window.toNanos();
            while (!stamps.isEmpty() && stamps.peekFirst() - cutoff <= 0) {
                stamps.pollFirst();
            }
            return stamps.size();
        }
    }

    /** Every request this stub has received, in order, as its path. */
    public List<String> receivedPaths() {
        return List.copyOf(paths);
    }

    /** How many requests hit a path whose URI contains {@code fragment}. */
    public int countFor(String fragment) {
        int total = 0;
        for (Map.Entry<String, AtomicInteger> e : countsByPath.entrySet()) {
            if (e.getKey().contains(fragment)) {
                total += e.getValue().get();
            }
        }
        return total;
    }

    /** Total requests received since start, irrespective of the window. */
    public int totalReceived() {
        return paths.size();
    }

    /** Every gateway request this stub has received, in order. */
    public List<RecordedRequest> requests() {
        return List.copyOf(requests);
    }

    /** Answer every gateway path with the captured Cloudflare block page from now on. */
    public void block() {
        blocked = true;
    }

    /** Answer normally again. */
    public void unblock() {
        blocked = false;
    }

    public boolean isBlocked() {
        return blocked;
    }

    /**
     * Make the login path answer {@code body} (HTTP 200, JSON content type) instead of a token
     * envelope — the gateway's own refusal shapes. {@code null} restores the token envelope.
     */
    public void setLoginResponse(String body) {
        this.loginResponseOverride = body;
    }

    /** The balance {@code verifytoken.aspx} reports. */
    public void setBalance(long balance) {
        this.balance = balance;
    }

    public void reset() {
        synchronized (stampLock) {
            stamps.clear();
        }
        paths.clear();
        countsByPath.clear();
        registered.clear();
        requests.clear();
        blocked = false;
        loginResponseOverride = null;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    // ------------------------------------------------------------------ the handler

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.startsWith("/__stub/")) {
            handleControl(exchange, path);
            return;
        }
        // Stamp FIRST, before any branching: what this class measures is arrivals, and a stamp
        // taken after a response is written would be a stamp taken at completion. That is the
        // exact distinction the budget itself is careful about, and the witness must match it.
        synchronized (stampLock) {
            stamps.addLast(nanos.getAsLong());
        }
        paths.add(path);
        countsByPath.computeIfAbsent(path, p -> new AtomicInteger()).incrementAndGet();

        String body = readBody(exchange);
        requests.add(new RecordedRequest(exchange.getRequestMethod(), path,
                exchange.getRequestURI().getRawQuery(), copyHeaders(exchange), body));
        if (blocked) {
            respondBlocked(exchange);
            return;
        }
        String response;
        if (path.contains("verifytoken")) {
            response = "{\"status\":\"OK\",\"code\":200,\"data\":[{\"main_balance\":" + balance
                    + ",\"username\":\"stub\"}],\"message\":\"OK\"}";
        } else if (path.contains("register")) {
            response = registerResponse(body);
        } else if (path.contains("update-fullname") || path.contains("update")) {
            response = "{\"status\":\"OK\",\"code\":200,\"message\":\"OK\"}";
        } else if (path.contains("deposit")) {
            response = "{\"status\":\"OK\",\"code\":200,\"message\":\"OK\"}";
        } else {
            // Everything else is the login path, whose name comes from the brand's AuthProfile
            // (/gwms/v1/bot/login.aspx, /user/login.aspx, …) so it cannot be matched by fragment.
            String override = loginResponseOverride;
            response = override != null ? override : loginResponse();
        }
        respond(exchange, 200, response);
    }

    /**
     * The register envelope, including {@code EXISTED} at HTTP <b>200</b> for a username this
     * stub has already seen.
     * <p>
     * The status code is the part worth pinning: both answers are 200 on the real gateway, so a
     * classifier that switched on the HTTP status would treat a re-registration as a success on
     * one brand and a failure on another. Phase 4's resumability rests on it.
     */
    private String registerResponse(String body) {
        String username = extract(body, "username");
        if (username != null && username.startsWith(REFUSED_USERNAME_PREFIX)) {
            // A genuine upstream refusal, at HTTP 200 like everything else gwms answers. Reserved
            // by username rather than by a mode flag so one test can exercise a refusal and a
            // success in the same fixture — which is what the worker's "this index costs an
            // attempt, that one does not" logic needs to be tested against.
            return "{\"status\":\"ERROR\",\"code\":400,"
                    + "\"message\":\"Username is not allowed\"}";
        }
        if (username != null && registered.putIfAbsent(username, Boolean.TRUE) != null) {
            return "{\"status\":\"EXISTED\",\"code\":409,\"message\":\"Tài khoản đã tồn tại\"}";
        }
        long n = tokenSeq.incrementAndGet();
        return "{\"status\":\"OK\",\"code\":200,\"data\":[{\"main_balance\":0,"
                + "\"uid\":\"stub-" + n + "\",\"type_id\":3,"
                + "\"session_id\":\"session-" + n + "\","
                + "\"token\":\"18-agency-" + n + "\",\"token2\":\"jwt-" + n + "\","
                + "\"username\":\"" + (username == null ? "stub" : username) + "\"}],"
                + "\"message\":\"Register successful\"}";
    }

    /**
     * The login envelope.
     * <p>
     * {@code token} is the <b>agency</b> token and {@code session_id} is the <b>auth</b> token —
     * the opposite way round from the internal names (CLAUDE.md, "Token Naming Reference"). The
     * prefixes mirror the real shapes so a test that asserts on them is asserting on something
     * real: the agency value is the {@code 18-…}-prefixed one that the WS AUTH frame carries.
     */
    private String loginResponse() {
        long n = tokenSeq.incrementAndGet();
        return "{\"status\":\"OK\",\"code\":200,\"data\":[{"
                + "\"session_id\":\"session-" + n + "\","
                + "\"token\":\"18-agency-" + n + "\","
                + "\"token2\":\"jwt-" + n + "\","
                + "\"main_balance\":" + balance + "}],\"message\":\"OK\"}";
    }

    /** {@code POST /__stub/block} and {@code /__stub/unblock}: the shell switch for V4d. */
    private void handleControl(HttpExchange exchange, String path) throws IOException {
        readBody(exchange);
        switch (path) {
            case "/__stub/block" -> block();
            case "/__stub/unblock" -> unblock();
            default -> {
                respond(exchange, 404, "{\"error\":\"unknown stub control " + path + "\"}");
                return;
            }
        }
        respond(exchange, 200, "{\"blocked\":" + blocked + "}");
    }

    /** The captured page, with its own status and headers — not a JSON envelope. */
    private static void respondBlocked(HttpExchange exchange) throws IOException {
        CapturedBlockPage page = CapturedBlockPage.load();
        byte[] bytes = page.body().getBytes(StandardCharsets.UTF_8);
        page.headers().forEach((name, value) -> {
            // The JDK server owns framing and the date; everything else is replayed verbatim.
            if (!name.equalsIgnoreCase("content-length") && !name.equalsIgnoreCase("date")) {
                exchange.getResponseHeaders().add(name, value);
            }
        });
        exchange.sendResponseHeaders(page.status(), bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static Map<String, List<String>> copyHeaders(HttpExchange exchange) {
        Map<String, List<String>> copy = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        exchange.getRequestHeaders().forEach((name, values) -> copy.put(name, List.copyOf(values)));
        return java.util.Collections.unmodifiableMap(copy);
    }

    /** Crude single-field JSON read. Enough for a stub, and it keeps Jackson out of test scope. */
    private static String extract(String body, String field) {
        if (body == null) {
            return null;
        }
        String needle = "\"" + field + "\"";
        int at = body.indexOf(needle);
        if (at < 0) {
            return null;
        }
        int colon = body.indexOf(':', at + needle.length());
        int open = body.indexOf('"', colon + 1);
        int close = open < 0 ? -1 : body.indexOf('"', open + 1);
        return (open < 0 || close < 0) ? null : body.substring(open + 1, close);
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** Diagnostics for a failing assertion: which paths were hit, how often, sorted. */
    public String describe() {
        List<String> lines = new ArrayList<>();
        countsByPath.forEach((path, count) -> lines.add(path + "=" + count.get()));
        lines.sort(String::compareTo);
        return String.join(", ", lines);
    }
}
