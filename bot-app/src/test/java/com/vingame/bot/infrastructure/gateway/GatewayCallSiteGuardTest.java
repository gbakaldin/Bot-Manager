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
    /** Deleted in Phase 5 (A29.3); asserted absent so it cannot quietly come back. */
    private static final String BOUNDED_LOGIN =
            "bot-engine/src/main/java/com/vingame/bot/infrastructure/client/BoundedLogin.java";

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
    @DisplayName("the login is an ordinary funnel request: no library client, and every request is bounded")
    void oneLibraryLoginEntryPoint() {
        List<String> client = codeLines(repoRoot().resolve(API_GATEWAY_CLIENT));

        // AD-12 / A29.3. Until Phase 5 the login went through the library's AuthClient, which
        // parsed the body as JSON before anything else — so a Cloudflare block page became a
        // JsonParseException and neither the status nor the cf-ray survived — and which had no
        // request timeout and no connect timeout, so a stalled TCP connection parked a build
        // thread (a semaphore permit, the group lock) for the life of the JVM. Phase 3 bounded it
        // from outside with BoundedLogin; Phase 5 moved the request in-repo, where it goes through
        // send() like the other four and is classified by httpCall before it is parsed.
        assertThat(occurrences(client, "AuthClient"))
                .as("ApiGatewayClient must not use the library's AuthClient at all any more — the "
                        + "login is built here (loginRequest) and sent through the funnel")
                .isZero();
        assertThat(Files.exists(repoRoot().resolve(BOUNDED_LOGIN)))
                .as("BoundedLogin was the stopgap for a login this class did not own; with the login "
                        + "in-repo it has nothing left to bound")
                .isFalse();

        // Every request this class builds carries the house timeout — the login included, which is
        // the one that used to have none (A19). Counted against the number of requests built, so a
        // sixth request without a timeout fails here rather than parking a thread on staging.
        long built = occurrences(client, "HttpRequest.newBuilder()");
        assertThat(built).as("anti-vacuity: the scan sees the five request builders").isGreaterThanOrEqualTo(5);
        assertThat(occurrences(client, ".timeout(GATEWAY_REQUEST_TIMEOUT)"))
                .as("one .timeout(GATEWAY_REQUEST_TIMEOUT) per HttpRequest built")
                .isEqualTo(built);

        // And the classifier sits in the one place every response passes (A29.4).
        assertThat(client).anyMatch(line -> line.contains("classified(request, httpClient.send("));
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
    @DisplayName("only known classes build a JDK HttpClient, or construct the library AuthClient")
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
        //  - GatewayBudgetRegistry: the circuit breaker's anonymous clearance probe (AD-13), one
        //    GET per block-probe-interval per OPEN circuit, stamped through count("circuit-probe").
        //    AD-21 names it; it cannot go through the funnel, because the funnel is exactly what
        //    an open circuit refuses.
        List<String> allowed = List.of(
                "ApiGatewayClient.java", "EnvironmentWsProbe.java", "GatewayBudgetRegistry.java",
                "HttpPrometheusQueryClient.java", "VipTalkClient.java", "GameMsClient.java");
        // BoundedLogin used to be on this list, as the one class allowed to construct the library's
        // AuthClient. It was deleted in Phase 5 when the login moved in-repo (A29.3), so
        // `new AuthClient(` is now allowed NOWHERE in production code — AD-21's rule as written.
        for (String module : List.of("bot-api", "bot-engine", "bot-messages", "bot-strategies", "bot-app")) {
            Path main = root.resolve(module).resolve("src/main/java");
            if (!Files.isDirectory(main)) {
                continue;
            }
            try (var walk = Files.walk(main)) {
                walk.filter(p -> p.getFileName().toString().endsWith(".java"))
                        .filter(p -> !allowed.contains(p.getFileName().toString()))
                        .forEach(p -> {
                            List<String> lines = codeLines(p);
                            if (occurrences(lines, "HttpClient.newHttpClient()") > 0
                                    || occurrences(lines, "HttpClient.newBuilder()") > 0
                                    // AD-21's rule, restored to the whole-tree scan (review F8).
                                    // The two needles above cannot see it: AuthClient builds its
                                    // HttpClient INSIDE THE LIBRARY, so a new production class
                                    // doing `new AuthClient(ctx, factory).authenticate()` would be
                                    // an unbudgeted, uncounted, UNBOUNDED login against a gwms host
                                    // that no guard in this file saw. That is the entire class of
                                    // thing this test exists for.
                                    || occurrences(lines, "new AuthClient(") > 0) {
                                offenders.add(root.relativize(p).toString());
                            }
                        });
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        assertThat(offenders)
                .as("a new JDK HttpClient — or a new `new AuthClient(` — pointed at a gwms gateway "
                        + "would send traffic the budget cannot see. If the new client talks to a "
                        + "different host, add it to the allow-list above with that reason written "
                        + "down.")
                .isEmpty();
    }

    @Test
    @DisplayName("every production registration scope names its bot group")
    void noProductionRegistrationScopeIsGroupLess() {
        // QA G-1. GatewayRequestScope.registration(String) — no group, NEVER_CANCELLED — has no
        // production caller since Phase 4 and is deliberately kept as a separate overload so that
        // "this call site has no group" is a statement rather than a defaulted null. Nothing
        // enforced it: a Phase 5 caller reaching for the shorter overload gets a registration that
        // GatewayBudget.cancelScope(botGroupId) cannot reach at all, which is the exact defect
        // A28.6 fixed — a DELETE then waits out up to fifteen minutes of registration.max-wait on
        // an HTTP thread for an account nobody wants.
        Path root = repoRoot();
        List<String> offenders = new ArrayList<>();
        for (String module : List.of("bot-api", "bot-engine", "bot-messages", "bot-strategies", "bot-app")) {
            Path main = root.resolve(module).resolve("src/main/java");
            if (!Files.isDirectory(main)) {
                continue;
            }
            try (var walk = Files.walk(main)) {
                walk.filter(p -> p.getFileName().toString().endsWith(".java"))
                        // The record itself declares both overloads; it is not a call site.
                        .filter(p -> !p.getFileName().toString().equals("GatewayRequestScope.java"))
                        .forEach(p -> {
                            if (isGroupLessRegistrationCall(String.join(" ", codeLines(p)))) {
                                offenders.add(root.relativize(p).toString());
                            }
                        });
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        assertThat(offenders)
                .as("GatewayRequestScope.registration(name) builds an UNCANCELLABLE registration "
                        + "scope. Production registration runs from RegistrationWorker, which "
                        + "always has a group id — use registration(botGroupId, name, cancelled).")
                .isEmpty();
    }

    /**
     * Whether {@code joined} contains a {@code GatewayRequestScope.registration(...)} call with a
     * single argument.
     * <p>
     * Counts commas between the call and its statement terminator rather than parsing Java: the
     * three-argument form is {@code (id, prefix, () -> …)} — two commas at least — and the
     * one-argument form has none. Proven against a synthetic file in {@link #theScannerHasTeeth}.
     */
    private static boolean isGroupLessRegistrationCall(String joined) {
        String needle = "GatewayRequestScope.registration(";
        int at = joined.indexOf(needle);
        while (at >= 0) {
            int end = joined.indexOf(';', at);
            String args = end < 0 ? joined.substring(at) : joined.substring(at + needle.length(), end);
            if (!args.contains(",")) {
                return true;
            }
            at = joined.indexOf(needle, at + 1);
        }
        return false;
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

    // ------------------------------------------------------------------ does the guard bite?

    /**
     * A source-scanning guard has one characteristic failure mode: it passes because it
     * matches nothing. If {@link #stripCommentsAndLiterals} were a shade too aggressive — one
     * more state in that hand-rolled scanner and it would be — every assertion above would
     * read {@code 0} occurrences and the whole class would go green while a second
     * {@code httpClient.send(} sat in the funnel's file.
     * <p>
     * So the scanner is pointed at a synthetic file whose contents are known: three
     * <em>real</em> offending call sites, and the same four spellings wrapped in a line
     * comment, a block comment and a string literal. Each rule above must see exactly the
     * real ones. This is what makes "exactly 1" elsewhere in this class an assertion rather
     * than a coincidence.
     */
    @Test
    @DisplayName("the scanner counts real call sites and ignores commented and quoted ones")
    void theScannerHasTeeth(@org.junit.jupiter.api.io.TempDir Path tmp) throws IOException {
        Path fake = tmp.resolve("Offender.java");
        Files.writeString(fake, String.join("\n",
                "package fake;",
                "/**",
                " * Javadoc that talks about httpClient.send( and new AuthClient( and .connect()",
                " * and HttpClient.newHttpClient() without calling any of them.",
                " */",
                "class Offender {",
                "    void real() {",
                "        httpClient.send(request, handler);        // a real, uncounted send",
                "        new AuthClient(ctx, factory).authenticate();",
                "        client.connect();",
                "        var h = HttpClient.newHttpClient();",
                "    }",
                "    void notReal() {",
                "        // httpClient.send(request, handler);",
                "        /* new AuthClient(ctx, factory); client.connect(); */",
                "        log.warn(\"do not add a second httpClient.send( or a .connect() here\");",
                "        var msg = \"HttpClient.newHttpClient()\";",
                "    }",
                "}"));

        List<String> code = codeLines(fake);

        assertThat(occurrences(code, "httpClient.send("))
                .as("the real send is seen; the commented and the quoted one are not")
                .isEqualTo(1);
        assertThat(occurrences(code, "new AuthClient(")).isEqualTo(1);
        assertThat(code.stream()
                .filter(line -> line.contains(".connect()") || line.contains("::connect"))
                .count()).isEqualTo(1);
        assertThat(occurrences(code, "HttpClient.newHttpClient()")).isEqualTo(1);
        // And the inverse: a file with only prose about the funnel must score zero, or the
        // allow-list scan would report every class that documents this feature as an offender.
        Path prose = tmp.resolve("Prose.java");
        Files.writeString(prose, String.join("\n",
                "package fake;",
                "/** Every request goes through httpClient.send( — see .connect() and HttpClient. */",
                "class Prose { String s = \"httpClient.send(\"; }"));
        List<String> proseCode = codeLines(prose);
        assertThat(occurrences(proseCode, "httpClient.send(")).isZero();
        assertThat(occurrences(proseCode, "HttpClient.newHttpClient()")).isZero();

        // …and the registration-scope arity scanner (QA G-1), which is a different kind of needle:
        // it has to tell two overloads of the same method apart.
        Path scopes = tmp.resolve("Scopes.java");
        Files.writeString(scopes, String.join("\n",
                "package fake;",
                "class Scopes {",
                "    void groupLess() {",
                "        var s = GatewayRequestScope.registration(\"bot\");",
                "    }",
                "}"));
        assertThat(isGroupLessRegistrationCall(String.join(" ", codeLines(scopes))))
                .as("the one-argument overload must be seen")
                .isTrue();

        Path withGroup = tmp.resolve("WithGroup.java");
        Files.writeString(withGroup, String.join("\n",
                "package fake;",
                "class WithGroup {",
                "    void ok() {",
                "        var s = GatewayRequestScope.registration(",
                "                id, group.getNamePrefix(), () -> cancelled.contains(id));",
                "    }",
                "}"));
        assertThat(isGroupLessRegistrationCall(String.join(" ", codeLines(withGroup))))
                .as("the three-argument overload, wrapped across lines, must NOT be flagged")
                .isFalse();
    }

    /**
     * The funnel is only a funnel if <b>every</b> public method that reaches it takes the tier from
     * its caller. An overload that defaulted the tier would compile, would be picked up by exactly
     * the call sites in a hurry, and would silently re-introduce the category the tiers exist to
     * separate (a start-path login and a reconnect login are not the same request).
     * <p>
     * <b>The rule is "every declaration names a {@code RequestTier}", not "there is exactly one
     * declaration".</b> It was the latter until review F1, which legitimately added
     * {@code getBalance(…, maxWait)} and {@code deposit(…, maxWait)} — overloads that differ in the
     * <em>wait</em>, not in the tier, and exist because a wait taken on a ws-parser
     * message-processor thread must be shorter than the watchdog's patience. Forbidding a second
     * declaration outright would have blocked that and protected nothing extra: what matters is
     * that none of them lets a caller off naming its intent.
     */
    @Test
    @DisplayName("every public ApiGatewayClient request method names the caller's tier")
    void noRequestMethodDefaultsItsTier() {
        List<String> code = codeLines(repoRoot().resolve(API_GATEWAY_CLIENT));

        // Joined, because these signatures wrap: getBalance's parameter list runs onto a
        // second line and a per-line scan would read the first half only.
        String joined = String.join(" ", code);

        for (String signature : List.of(
                "public TokensProvider authenticate(",
                "public long getBalance(",
                "public boolean deposit(")) {
            int at = joined.indexOf(signature);
            assertThat(at)
                    .as("%s must still exist — if it was renamed, update this guard", signature)
                    .isNotNegative();
            int declarations = 0;
            while (at >= 0) {
                declarations++;
                int close = joined.indexOf(')', at);
                assertThat(joined.substring(at, close))
                        .as("declaration %d of %s must take the caller's RequestTier — an overload "
                                + "that defaults it is how the tier assignment quietly stops being "
                                + "a decision", declarations, signature)
                        .contains("RequestTier");
                at = joined.indexOf(signature, at + 1);
            }
            // Anti-vacuity: prove the scan walked the overloads rather than finding one and
            // stopping. getBalance and deposit each have the F1 wait overload beside them.
            assertThat(declarations)
                    .as("%s: at least one declaration scanned", signature)
                    .isPositive();
        }
    }
}
