package com.vingame.bot.infrastructure.gateway;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * One {@link GatewayBudget} per environment, keyed exactly like
 * {@code EnvironmentClientRegistry} (GATEWAY_REQUEST_BUDGET AD-1).
 * <p>
 * The keying is the design: Cloudflare's rule is <b>per gateway host</b>, and two
 * environments on different hosts must not throttle each other. A single global budget would
 * make a quiet brand's group start wait on a busy brand's reconnect storm for no reason at
 * all.
 * <p>
 * {@code EnvironmentClientRegistry.createClients} resolves the budget here and hands it to
 * {@code ApiGatewayClient.init(...)} and to {@code EnvironmentClients}, from where
 * {@code BotFactory.createBot} wires it into every bot. There is exactly one budget object
 * per environment id for the life of the JVM — {@link #forEnvironment} is a
 * {@code computeIfAbsent} and is never evicted, which costs one small object per environment
 * ever seen and means the window survives an environment's clients being rebuilt.
 * <p>
 * <b>One IP = one JVM.</b> Nothing here coordinates across processes and nothing is designed
 * to: if two instances ever share an egress IP against the same brand they share the 1,000
 * without knowing it, and that is a deployment error. The same caveat applies to
 * {@code scripts/bulk-create-accounts.py}, which is invisible to this registry.
 */
@Slf4j
@Component
public class GatewayBudgetRegistry {

    /** The clearance probe's request (AD-13). Anonymous: the token is literally {@code probe}. */
    static final String CLEARANCE_PROBE_PATH = "/gwms/v1/verifytoken.aspx?token=probe&fg=probe";
    /** The house bound on one gateway round trip — {@code ApiGatewayClient}'s, repeated. */
    private static final java.time.Duration PROBE_TIMEOUT = java.time.Duration.ofSeconds(10);
    private static final String PROBE_USER_AGENT = "PostmanRuntime/7.15.2";

    private final ConcurrentHashMap<String, SlidingWindowGatewayBudget> budgets = new ConcurrentHashMap<>();
    /** Built on first use by {@link #probeClient()}. */
    private volatile HttpClient probeClient;
    /** Gateway host -> the environment ids that send to it. See {@code recordGatewayHost}. */
    private final ConcurrentHashMap<String, Set<String>> environmentsByGatewayHost = new ConcurrentHashMap<>();
    /** Hosts already warned about, so the WARN is once per host per JVM and not once per call. */
    private final Set<String> warnedGatewayHosts = ConcurrentHashMap.newKeySet();
    private final GatewayBudgetSettings settings;
    private final MeterRegistry meterRegistry;
    private final LongSupplier nanos;

    /**
     * {@code @Autowired} is mandatory, not decoration: this class has two constructors, and with
     * neither annotated Spring falls back to a no-arg constructor that does not exist and the
     * whole context fails to refresh. That is exactly the defect that crash-looped
     * {@code VipTalkClient} on staging on 2026-08-18 — and it was caught here by
     * {@code ApplicationContextLoadsTest}, which is the test written in response to it.
     */
    @Autowired
    public GatewayBudgetRegistry(GatewayBudgetSettings settings, MeterRegistry meterRegistry) {
        this(settings, meterRegistry, System::nanoTime);
    }

    /**
     * Clock-injecting seam — the {@code ScopedDebugRegistry.withClock} idiom. Tests drive the
     * window without sleeping; production uses {@link System#nanoTime()} and never the wall
     * clock, so an NTP step cannot move the window.
     */
    public GatewayBudgetRegistry(GatewayBudgetSettings settings, MeterRegistry meterRegistry,
                                 LongSupplier nanos) {
        this.settings = settings;
        this.meterRegistry = meterRegistry;
        this.nanos = nanos;
    }

    /**
     * One startup line, at INFO, once per JVM — tier-1 admissible by construction, and the
     * line the release verification greps to prove which posture a box is in (V3a greps
     * {@code mode=enforce}).
     * <p>
     * <b>There is deliberately no second line qualifying it.</b> Until Phase 3 this method also
     * logged a WARN saying that {@code mode=enforce} was set but nothing was being paced, which
     * was true and important then — an operator who believes the fleet is protected when it is
     * not is the precise failure this feature exists to prevent. Enforcement now exists, so that
     * WARN would be a tier-1, Loki-visible, actively <em>false</em> statement about a production
     * instance's posture, which is the same failure with the sign flipped.
     * {@code GatewayBudgetRegistryTest.enforceModeSaysNothingBeyondThePosture} asserts its
     * absence rather than trusting that it was deleted.
     */
    @PostConstruct
    void logStartupPosture() {
        log.info("Gateway budget registry started (mode={}, window={}, hard-cap={}, ceilings {}, "
                        + "count-ws-upgrades={})",
                settings.mode().name().toLowerCase(java.util.Locale.ROOT),
                settings.window(),
                settings.hardCap(),
                settings.describeCeilings(),
                settings.countWsUpgrades());
    }

    /**
     * Get or create the budget for an environment.
     *
     * @param environmentId   the key, identical to {@code EnvironmentClientRegistry}'s
     * @param environmentName for the operator-facing lines only
     * @param productCode     numeric product code ({@code ProductCode.getCode()}) — the
     *                        {@code product} metric label, nullable exactly as everywhere
     *                        else that labels with it
     */
    public GatewayBudget forEnvironment(String environmentId, String environmentName, String productCode) {
        return forEnvironment(environmentId, environmentName, productCode, null);
    }

    /**
     * Get or create the budget for an environment, declaring the gateway base URL it will send
     * to.
     * <p>
     * <b>The key is still the environment id</b> — {@link #budgetKey} — and that is the user's
     * decision, not an oversight. What the URL buys is the <em>detection</em> of the one case
     * where per-environment keying and the real rule disagree (A16.6a): Cloudflare counts per
     * (egress IP × gateway host), so two {@code Environment} documents pointing at the same
     * {@code apiGateway} each get their own 900 — 1,800 against a 1,000 cap, with neither budget
     * able to see the other. One WARN naming the duplicates is what makes that a five-minute
     * diagnosis instead of a day of a brand's uptime.
     * <p>
     * <b>Switching to a host key is a small change on purpose.</b> It is {@link #budgetKey}'s
     * body plus giving {@link #find} and {@link #snapshotOrEmpty} the same host — and those two
     * are read-only consumers with one caller each. The seam exists because the *rule* is
     * per-host, so the day someone stands up two environments on one gateway deliberately, the
     * answer is a key change rather than a rework.
     *
     * @param apiGatewayUrl the environment's {@code apiGateway} base URL, or {@code null} from
     *                      callers that do not have it (the WS probe scheduler resolves
     *                      environments by socket URL, not by gateway host)
     */
    public GatewayBudget forEnvironment(String environmentId, String environmentName,
                                        String productCode, String apiGatewayUrl) {
        recordGatewayHost(environmentId, apiGatewayUrl);
        SlidingWindowGatewayBudget budget = budgets.computeIfAbsent(budgetKey(environmentId, apiGatewayUrl), key -> {
            log.debug("Creating gateway request budget for environment {} ({}, product {})",
                    environmentId, environmentName, productCode);
            return new SlidingWindowGatewayBudget(
                    environmentId, environmentName, productCode, settings, meterRegistry, nanos);
        });
        // Bound on every call that knows the gateway, not only at creation: the WS probe scheduler
        // can create an environment's budget first (it has no apiGateway to offer), and an
        // environment whose apiGateway is edited gets its clients rebuilt through here.
        CircuitProbe probe = clearanceProbe(apiGatewayUrl);
        if (probe != null) {
            budget.bindCircuitProbe(probe);
        }
        return budget;
    }

    /**
     * The anonymous clearance request for one gateway (AD-13):
     * {@code GET <apiGateway>/gwms/v1/verifytoken.aspx?token=probe&fg=probe}, classified by
     * {@link CloudflareBlockDetector}. No account, no token, no body; the gateway's own JSON error
     * for an unknown token is the expected "cleared" answer.
     * <p>
     * <b>This registry owns the probe's {@link HttpClient}</b> — AD-21 names it as one of the three
     * classes allowed to — and builds it lazily, on the first probe: a JDK client owns a
     * {@code SelectorManager} platform thread, and an instance that never sees a block should not
     * pay for one, nor should every test that builds a registry.
     *
     * @return {@code null} when there is no usable URL, in which case nothing is bound
     */
    private CircuitProbe clearanceProbe(String apiGatewayUrl) {
        if (apiGatewayUrl == null || apiGatewayUrl.isBlank()) {
            return null;
        }
        java.net.URI uri;
        try {
            uri = java.net.URI.create(apiGatewayUrl.trim() + CLEARANCE_PROBE_PATH);
        } catch (IllegalArgumentException e) {
            log.debug("No clearance probe for gateway '{}': {}", apiGatewayUrl, e.getMessage());
            return null;
        }
        return () -> {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .header("Cache-Control", "no-cache")
                    .header("User-Agent", PROBE_USER_AGENT)
                    .GET()
                    .timeout(PROBE_TIMEOUT)
                    .build();
            HttpResponse<String> response = probeClient().send(request, HttpResponse.BodyHandlers.ofString());
            return new CircuitProbe.Answer(response.statusCode(), CloudflareBlockDetector.classify(response));
        };
    }

    /**
     * Bind the WebSocket-host clearance probe for {@code environmentId}'s budget, if the budget
     * exists (review-phase5): an anonymous upgrade against {@code webSocketUrl}, used when the
     * circuit was opened by a WS-upgrade block, so that it is the WS host's block that has to lift.
     * <p>
     * No headers, no token, no AUTH frame — the socket is aborted the moment the upgrade completes.
     * A 101, or any refusal that is not the Cloudflare page (an origin's own 403 for an anonymous
     * upgrade included), means the edge is letting this host through again. No answer at all is
     * reported as an {@code IOException}, which keeps the circuit open.
     */
    public void bindWebSocketProbe(String environmentId, String webSocketUrl) {
        SlidingWindowGatewayBudget budget = environmentId == null ? null : budgets.get(environmentId);
        if (budget == null || webSocketUrl == null || webSocketUrl.isBlank()) {
            return;
        }
        java.net.URI uri;
        try {
            uri = java.net.URI.create(webSocketUrl.trim());
        } catch (IllegalArgumentException e) {
            log.debug("No WS clearance probe for '{}': {}", webSocketUrl, e.getMessage());
            return;
        }
        budget.bindWsCircuitProbe(() -> {
            java.util.concurrent.CompletableFuture<java.net.http.WebSocket> handshake = probeClient()
                    .newWebSocketBuilder()
                    .connectTimeout(PROBE_TIMEOUT)
                    .buildAsync(uri, new java.net.http.WebSocket.Listener() { });
            try {
                java.net.http.WebSocket socket = handshake.get(PROBE_TIMEOUT.toMillis(),
                        java.util.concurrent.TimeUnit.MILLISECONDS);
                socket.abort();
                return new CircuitProbe.Answer(101, CloudflareBlockDetector.Verdict.NOT_A_BLOCK);
            } catch (java.util.concurrent.ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof java.net.http.WebSocketHandshakeException refused
                        && refused.getResponse() != null) {
                    return new CircuitProbe.Answer(refused.getResponse().statusCode(),
                            CloudflareBlockDetector.classifyHandshakeFailure(refused));
                }
                throw new java.io.IOException("WS clearance probe got no answer: " + cause, cause);
            } catch (java.util.concurrent.TimeoutException e) {
                handshake.cancel(true);
                handshake.whenComplete((ws, error) -> {
                    if (ws != null) {
                        ws.abort();
                    }
                });
                throw new java.io.IOException("WS clearance probe timed out", e);
            }
        });
    }

    private HttpClient probeClient() {
        HttpClient client = probeClient;
        if (client == null) {
            synchronized (this) {
                client = probeClient;
                if (client == null) {
                    client = HttpClient.newBuilder()
                            .connectTimeout(PROBE_TIMEOUT)
                            .followRedirects(HttpClient.Redirect.NEVER)
                            .build();
                    probeClient = client;
                }
            }
        }
        return client;
    }

    /**
     * Whether {@code environmentId}'s circuit is open, <b>without creating a budget</b> — read by
     * the recovery reconciler and the environment WS probe, both of which must stand down while
     * the edge is refusing this host (A15.4, A29.2). An environment with no budget has sent
     * nothing, so it cannot have been blocked.
     */
    public boolean isCircuitOpen(String environmentId) {
        if (environmentId == null) {
            return false;
        }
        GatewayBudget budget = budgets.get(environmentId);
        return budget != null && budget.snapshot().circuitOpen();
    }

    /**
     * The budget key: <b>the environment id, and nothing else</b> (AD-1, user-confirmed).
     * <p>
     * {@code apiGatewayUrl} is accepted and ignored so that the one line which would have to
     * change to key by host is this one. See {@link #forEnvironment(String, String, String, String)}
     * for why it is not keyed by host today and what else would have to move with it.
     */
    private static String budgetKey(String environmentId, String apiGatewayUrl) {
        return environmentId;
    }

    /**
     * Remember which environments send to which gateway host, and WARN once per newly-discovered
     * collision (A16.6a).
     * <p>
     * The WARN is the whole point: per-environment keying is only safe while environments do not
     * share a gateway host, and nothing in the data model prevents two documents from pointing at
     * the same one. It fires once per host, at startup-ish time (the first time an environment's
     * clients are built), names every environment involved, and is tier-1 admissible because its
     * rate is bounded by the number of distinct gateway hosts.
     */
    private void recordGatewayHost(String environmentId, String apiGatewayUrl) {
        String host = gatewayHost(apiGatewayUrl);
        if (host == null || environmentId == null) {
            return;
        }
        Set<String> sharing = environmentsByGatewayHost.computeIfAbsent(host,
                h -> ConcurrentHashMap.newKeySet());
        if (!sharing.add(environmentId) || sharing.size() < 2) {
            return;
        }
        if (warnedGatewayHosts.add(host)) {
            log.warn("Gateway host {} is shared by {} environments ({}) — Cloudflare's "
                            + "1,000-requests-per-5-minutes rule is counted per source IP PER "
                            + "GATEWAY HOST, but this app's budget is keyed per environment, so "
                            + "these environments each get their own hard cap of {} against ONE "
                            + "shared limit. Either point them at distinct gateways or lower "
                            + "bot.gateway.budget.hard-cap so the sum stays under 1,000.",
                    host, sharing.size(), sortedForDisplay(sharing), settings.hardCap());
        }
    }

    /**
     * The host of a gateway base URL, lower-cased; {@code null} if it cannot be parsed.
     * <p>
     * Host only, deliberately: the Cloudflare zone follows the hostname, not the scheme, the port
     * or the path, so {@code https://gw.example/} and {@code http://gw.example:8080/x} are one
     * limit. A URL we cannot parse is not worth failing a startup over — the budget still works,
     * it is only the collision WARN that is skipped.
     */
    static String gatewayHost(String apiGatewayUrl) {
        if (apiGatewayUrl == null || apiGatewayUrl.isBlank()) {
            return null;
        }
        try {
            String host = java.net.URI.create(apiGatewayUrl.trim()).getHost();
            return host == null ? null : host.toLowerCase(java.util.Locale.ROOT);
        } catch (IllegalArgumentException e) {
            log.debug("Could not parse a gateway host out of '{}' — skipping the shared-host check",
                    apiGatewayUrl);
            return null;
        }
    }

    /** Sorted at render time, never the set's iteration order, so the WARN is diffable. */
    private static String sortedForDisplay(Set<String> values) {
        return values.stream().sorted().collect(java.util.stream.Collectors.joining(", "));
    }

    /**
     * The budget for an environment <b>without creating one</b>. Read-only consumers (the
     * fleet rollup line, a future status endpoint) must not conjure a budget — and therefore
     * a fresh set of {@code gateway_budget_*} series — as a side effect of reading.
     */
    public GatewayBudget find(String environmentId) {
        return budgets.get(environmentId);
    }

    /**
     * A snapshot for {@code environmentId}, or a zeroed one carrying the configured hard cap
     * when no budget exists yet.
     * <p>
     * The zeroed form keeps the rollup line's shape constant: an operator (or a grep) reading
     * {@code gateway=0/900 queued=0/0/0 circuit=closed} learns "this environment has sent
     * nothing", which is true, rather than finding the fragment missing and having to work
     * out whether the feature is deployed.
     */
    public GatewayBudget.Snapshot snapshotOrEmpty(String environmentId, String environmentName,
                                                  String productCode) {
        GatewayBudget budget = budgets.get(environmentId);
        if (budget != null) {
            return budget.snapshot();
        }
        return new GatewayBudget.Snapshot(environmentId, environmentName, productCode,
                settings.mode(), 0, settings.hardCap(), 0, 0, 0, false);
    }

    /** Every live budget's snapshot, in creation order of the underlying map. */
    public List<GatewayBudget.Snapshot> snapshotAll() {
        List<GatewayBudget.Snapshot> all = new ArrayList<>(budgets.size());
        budgets.values().forEach(budget -> all.add(budget.snapshot()));
        return all;
    }

    /** How many environments have a budget. Diagnostics and tests. */
    public int size() {
        return budgets.size();
    }

    /** The policy every budget in this registry is built with. */
    public GatewayBudgetSettings settings() {
        return settings;
    }
}
