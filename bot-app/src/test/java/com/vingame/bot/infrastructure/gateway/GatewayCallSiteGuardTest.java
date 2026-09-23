package com.vingame.bot.infrastructure.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The funnel invariant, enforced against the source (GATEWAY_REQUEST_BUDGET AD-21):
 * <b>there is exactly one place a gateway HTTP request is sent from, and exactly one place a
 * WebSocket upgrade is performed from.</b>
 * <p>
 * <b>Why a source scan.</b> A second {@code httpClient.send(...)} added to
 * {@code ApiGatewayClient}, or a fourth {@code connect()} added to {@code Bot}, would work
 * perfectly. Every test would pass, the app would behave identically, and the only observable
 * difference would be that some of the fleet's traffic stopped appearing in
 * {@code gateway_budget_window_requests} — so the window would read low, the near-cap alert
 * would not fire, and the first symptom would be a whole-brand Cloudflare block that the
 * dashboard says cannot be happening. Nothing dynamic can catch "a request that was made
 * without being counted"; the absence is the defect.
 * <p>
 * It is a tripwire on the spellings this codebase actually uses, not a proof. Comments and
 * string literals are stripped first (these files carry long comments <em>about</em> the
 * funnel, and a comment quoting {@code httpClient.send(} is documentation), following the
 * {@code PerBotInfoLogGuardTest} idiom.
 */
@DisplayName("Gateway call sites: one funnel for HTTP, one for WS upgrades")
class GatewayCallSiteGuardTest {

    private static final List<Path> ROOT_CANDIDATES = List.of(Path.of(".."), Path.of("."));

    private static final String API_GATEWAY_CLIENT =
            "bot-engine/src/main/java/com/vingame/bot/infrastructure/client/ApiGatewayClient.java";
    private static final String BOT =
            "bot-engine/src/main/java/com/vingame/bot/domain/bot/core/Bot.java";
    private static final String ENVIRONMENT_CLIENT_REGISTRY =
            "bot-app/src/main/java/com/vingame/bot/config/client/EnvironmentClientRegistry.java";

    private static Path repoRoot() {
        Path root = ROOT_CANDIDATES.stream()
                .filter(candidate -> Files.isDirectory(candidate.resolve("bot-engine")))
                .findFirst()
                .orElse(null);
        assumeTrue(root != null, "repo root not found from " + Path.of("").toAbsolutePath());
        return root;
    }

    private static List<String> codeLines(Path path) {
        assertThat(path).as("%s has moved — update this guard rather than deleting it", path).exists();
        List<String> raw;
        try {
            raw = Files.readAllLines(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return stripCommentsAndLiterals(raw);
    }

    /**
     * Remove block comments, line comments and string-literal contents, so only real code is
     * scanned. Literal <em>contents</em> go but the surrounding quotes stay, which keeps a
     * quoted {@code "httpClient.send("} inside a message from failing the build while leaving
     * an actual call untouched.
     */
    private static List<String> stripCommentsAndLiterals(List<String> raw) {
        List<String> out = new ArrayList<>(raw.size());
        boolean inBlockComment = false;
        for (String line : raw) {
            StringBuilder sb = new StringBuilder();
            boolean inString = false;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (inBlockComment) {
                    if (c == '*' && i + 1 < line.length() && line.charAt(i + 1) == '/') {
                        inBlockComment = false;
                        i++;
                    }
                    continue;
                }
                if (inString) {
                    if (c == '\\') {
                        i++;
                    } else if (c == '"') {
                        inString = false;
                        sb.append('"');
                    }
                    continue;
                }
                if (c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '*') {
                    inBlockComment = true;
                    i++;
                    continue;
                }
                if (c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/') {
                    break;
                }
                if (c == '"') {
                    inString = true;
                    sb.append('"');
                    continue;
                }
                sb.append(c);
            }
            out.add(sb.toString());
        }
        return out;
    }

    private static long occurrences(List<String> code, String needle) {
        return code.stream().filter(line -> line.contains(needle)).count();
    }

    @Test
    @DisplayName("ApiGatewayClient sends HTTP from exactly one place — the budget funnel")
    void oneHttpSendSite() {
        List<String> code = codeLines(repoRoot().resolve(API_GATEWAY_CLIENT));

        assertThat(occurrences(code, "httpClient.send("))
                .as("every gateway request must go through send(tier, scope, request), which is "
                        + "the only site that stamps the window. A second send() is traffic that "
                        + "no metric and no alert can see.")
                .isEqualTo(1);
        // Anti-vacuity: prove the scan sees the funnel rather than an empty file.
        assertThat(code).anyMatch(line ->
                line.contains("private HttpResponse<String> send(RequestTier"));
    }

    @Test
    @DisplayName("ApiGatewayClient constructs exactly one AuthClient — until Phase 4 removes it")
    void oneAuthClientConstruction() {
        List<String> code = codeLines(repoRoot().resolve(API_GATEWAY_CLIENT));

        // AD-12: the login still goes through the library, whose AuthClient parses the body as
        // JSON before anything else — so a Cloudflare HTML block page becomes a JsonParseException
        // and neither the status code nor the cf-ray survives. Phase 4 moves the login in-repo and
        // this expectation becomes 0 (AuthClient surviving only for generateFingerprint, which is
        // a static call and does not match this needle).
        assertThat(occurrences(code, "new AuthClient("))
                .as("one login construction site today; Phase 4 makes this 0")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Bot performs a WebSocket upgrade from exactly one place")
    void oneWsUpgradeSite() {
        List<String> code = codeLines(repoRoot().resolve(BOT));

        long connects = code.stream()
                .filter(line -> line.contains(".connect()") || line.contains("::connect"))
                .count();
        assertThat(connects)
                .as("all three upgrades (initialize=ESSENTIAL, restart=DEFAULT, "
                        + "tryReconnectWs=PRIORITIZED) go through connectUnderBudget, which is the "
                        + "only site that honours count-ws-upgrades and the only one a cancelled "
                        + "scope can be refused before")
                .isEqualTo(1);
        assertThat(code).anyMatch(line ->
                line.contains("private void connectUnderBudget(RequestTier"));
        assertThat(code).anyMatch(line -> line.contains("runWsUpgrade("));
    }

    @Test
    @DisplayName("Bot never builds its own HttpClient")
    void botDoesNotOwnAnHttpClient() {
        assertThat(occurrences(codeLines(repoRoot().resolve(BOT)), "HttpClient"))
                .as("a bot's HTTP goes through ApiGatewayClient, which is where the funnel is")
                .isZero();
    }

    @Test
    @DisplayName("only the three known classes build a JDK HttpClient at all")
    void onlyKnownClassesBuildAnHttpClient() {
        Path root = repoRoot();
        List<String> offenders = new ArrayList<>();
        // The allow-list, each with a reason:
        //  - ApiGatewayClient: the funnel itself.
        //  - EnvironmentWsProbe: the anonymous WS reachability probe; counts via budget.count().
        //  - HttpPrometheusQueryClient / VipTalkClient: other hosts entirely (Prometheus, Matrix),
        //    not a gwms gateway and not subject to the Cloudflare rule.
        //  - GameMsClient: the legacy gamems agency-transfer client. A different host, and it has
        //    no live callers at all — the only reference to it is the constructor call in
        //    EnvironmentClientRegistry (plan Open Item 12: dead code, to be filed as a follow-up).
        //    If it is ever revived against a gwms host it must be routed through the budget.
        List<String> allowed = List.of(
                "ApiGatewayClient.java", "EnvironmentWsProbe.java",
                "HttpPrometheusQueryClient.java", "VipTalkClient.java", "GameMsClient.java");
        for (String module : List.of("bot-api", "bot-engine", "bot-messages", "bot-strategies", "bot-app")) {
            Path main = root.resolve(module).resolve("src/main/java");
            if (!Files.isDirectory(main)) {
                continue;
            }
            try (var walk = Files.walk(main)) {
                walk.filter(p -> p.getFileName().toString().endsWith(".java"))
                        .filter(p -> !allowed.contains(p.getFileName().toString()))
                        .forEach(p -> {
                            if (occurrences(codeLines(p), "HttpClient.newHttpClient()") > 0
                                    || occurrences(codeLines(p), "HttpClient.newBuilder()") > 0) {
                                offenders.add(root.relativize(p).toString());
                            }
                        });
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        assertThat(offenders)
                .as("a new JDK HttpClient pointed at a gwms gateway would send traffic the budget "
                        + "cannot see. If the new client talks to a different host, add it to the "
                        + "allow-list above with that reason written down.")
                .isEmpty();
    }

    @Test
    @DisplayName("the production ApiGatewayClient is initialised WITH a budget")
    void theProductionClientIsInitialisedWithABudget() {
        List<String> code = codeLines(repoRoot().resolve(ENVIRONMENT_CLIENT_REGISTRY));

        // There is a package-private three-argument init() for fixtures, which resolves to
        // GatewayBudget.UNLIMITED. If the single production call site ever slipped onto it, every
        // request in the fleet would be uncounted while every gateway_budget_* series sat at
        // exactly zero — indistinguishable, on a dashboard, from an idle instance.
        assertThat(code).anyMatch(line -> line.contains("forEnvironment("));
        assertThat(code).anyMatch(line -> line.contains("authStrategyFactory.getAuthProfile(env), gatewayBudget)"));
    }
}
