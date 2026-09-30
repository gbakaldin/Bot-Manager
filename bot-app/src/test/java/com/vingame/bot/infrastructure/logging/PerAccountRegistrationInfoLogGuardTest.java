package com.vingame.bot.infrastructure.logging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tier-1 invariant, applied to the asynchronous-registration path
 * (CLAUDE.md "Logging Guidelines"; GATEWAY_REQUEST_BUDGET A28, A30).
 *
 * <p><b>Why a separate guard.</b> {@code PerBotInfoLogGuardTest} scans the bot cores, the
 * strategies, {@code ClientFactory} and {@code BotFactory} — the classes whose logging is per
 * <em>bot</em>. Phase 4 introduced a second rate that is a function of a count:
 * <b>per account</b>. A 500-account create runs {@code registerOne} 500 times and
 * {@code setDisplayName} up to 2,500 times, from a Spring singleton
 * ({@code ApiGatewayClient} is one per environment) that no existing guard looks at. One
 * {@code log.info} added inside either would put 3,000 lines through track 1 and therefore
 * through Loki for a single button press, which is precisely the shape the tier model exists to
 * remove:
 *
 * <blockquote>INFO must not contain anything whose rate is a function of bot count or round
 * rate. If a line fires once per bot, once per round, or once per message, it is DEBUG or
 * TRACE.</blockquote>
 *
 * <p>Per-account is that rule's third case, and the reviewer's finding F2 on the previous phase
 * was an instance of it (a budget refusal logged per user at ERROR, so a starved 300-user
 * registration produced 300 page-worthy lines for the budget working as designed).
 *
 * <p><b>What this permits.</b> {@code RegistrationWorker} keeps INFO, because its INFO is
 * group-scoped or process-scoped and is pinned below by count. What it may not do is grow a
 * line inside the per-account loop.
 *
 * <p>Method bodies are extracted by brace matching from the source text rather than by
 * reflection, for the same reason {@code PerBotInfoLogGuardTest} scans source: the property
 * is about what a future edit may add, and a compiled class cannot be asked that. Line comments
 * and block comments are stripped first, so a comment quoting an old call does not fail the
 * build.
 */
@DisplayName("Tier invariant: no per-ACCOUNT INFO logging on the registration path")
class PerAccountRegistrationInfoLogGuardTest {

    private static final List<Path> ROOT_CANDIDATES = List.of(Path.of(".."), Path.of("."));

    private static final String API_GATEWAY_CLIENT =
            "bot-engine/src/main/java/com/vingame/bot/infrastructure/client/ApiGatewayClient.java";
    private static final String REGISTRATION_WORKER =
            "bot-app/src/main/java/com/vingame/bot/domain/botgroup/service/RegistrationWorker.java";

    /**
     * Declaration prefixes of the methods that run <b>once per account</b>, or more often.
     * {@code setDisplayName} runs up to {@code display-name-retries} times per account — the
     * name pool collides ~43% of the time at 100 accounts, so the retry is the normal path.
     */
    private static final List<String> PER_ACCOUNT_METHODS = List.of(
            "public RegistrationOutcome registerOne(",
            "public boolean setDisplayName(",
            "public String setDisplayNameWithRetry(");

    /**
     * Messages demoted by this phase, pinned individually so a revert is a build failure rather
     * than a quiet return to per-account volume. Fragment → the level it must now carry.
     */
    private static final Map<String, String> DEMOTED = new LinkedHashMap<>(Map.of(
            "No display names available", "log.debug",
            "Failed to get random display name on attempt", "log.debug",
            "already exists — index", "log.debug"));

    private static Path repoRoot() {
        Path root = ROOT_CANDIDATES.stream()
                .filter(candidate -> Files.isDirectory(candidate.resolve("bot-engine")))
                .findFirst()
                .orElse(null);
        assumeTrue(root != null, "repo root not found from " + Path.of("").toAbsolutePath());
        return root;
    }

    private static String sourceOf(String relativePath) {
        Path path = repoRoot().resolve(relativePath);
        assertThat(path).as("guarded file has moved: %s", relativePath).exists();
        try {
            return stripComments(Files.readString(path));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Remove {@code //} and block comments so quoted old call sites cannot fail the build. */
    private static String stripComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        boolean inLine = false;
        boolean inBlock = false;
        boolean inString = false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
            if (inLine) {
                if (c == '\n') {
                    inLine = false;
                    out.append(c);
                }
            } else if (inBlock) {
                if (c == '*' && next == '/') {
                    inBlock = false;
                    i++;
                } else if (c == '\n') {
                    out.append(c);
                }
            } else if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
                out.append(c);
            } else if (c == '/' && next == '/') {
                inLine = true;
                i++;
            } else if (c == '/' && next == '*') {
                inBlock = true;
                i++;
            } else {
                if (c == '"') {
                    inString = true;
                }
                out.append(c);
            }
        }
        return out.toString();
    }

    /** The body of the method whose declaration starts with {@code declarationPrefix}. */
    private static String bodyOf(String source, String declarationPrefix) {
        int at = source.indexOf(declarationPrefix);
        assertThat(at)
                .as("method has been renamed or its signature changed: %s — update this guard "
                        + "together with it, do not drop it", declarationPrefix)
                .isNotNegative();
        int open = source.indexOf('{', at);
        assertThat(open).isNotNegative();
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(open, i + 1);
                }
            }
        }
        throw new IllegalStateException("unbalanced braces after " + declarationPrefix);
    }

    @Test
    @DisplayName("no per-account method logs at INFO or above")
    void thePerAccountMethodsAreBelowInfo() {
        String source = sourceOf(API_GATEWAY_CLIENT);
        List<String> offenders = new ArrayList<>();
        for (String method : PER_ACCOUNT_METHODS) {
            String body = bodyOf(source, method);
            for (String level : List.of("log.info(", "log.warn(", "log.error(")) {
                if (body.contains(level)) {
                    offenders.add(method + " contains " + level);
                }
            }
        }
        assertThat(offenders)
                .as("a 500-account create calls these 500 to 3,000 times. A line here at INFO "
                        + "reaches track 1 and therefore Loki, at a rate that is a function of "
                        + "account count — the exact shape CLAUDE.md's tier rule forbids, and "
                        + "the shape of review finding F2 on the previous phase. The "
                        + "group-level statement belongs on RegistrationWorker's completion "
                        + "line; the per-account rate belongs to registration_accounts_total.")
                .isEmpty();
    }

    @Test
    @DisplayName("the demoted registration messages stay demoted")
    void theDemotedMessagesStayDemoted() {
        String source = sourceOf(API_GATEWAY_CLIENT);
        DEMOTED.forEach((fragment, level) -> {
            int at = source.indexOf(fragment);
            assertThat(at)
                    .as("message has been reworded: '%s'. If that is deliberate, re-pin it here "
                            + "rather than deleting the entry", fragment)
                    .isNotNegative();
            // The logging call opens at most a couple of lines before the fragment.
            String preceding = source.substring(Math.max(0, at - 200), at);
            int call = preceding.lastIndexOf("log.");
            assertThat(call).as("no logging call precedes '%s'", fragment).isNotNegative();
            assertThat(preceding.substring(call))
                    .as("'%s' fires once per account (or once per naming attempt) and must stay "
                            + "at %s", fragment, level)
                    .startsWith(level);
        });
    }

    @Test
    @DisplayName("RegistrationWorker's INFO stays group-scoped and bounded in count")
    void theWorkersInfoIsGroupScoped() {
        String source = sourceOf(REGISTRATION_WORKER);

        // Counted rather than forbidden: this class is where the feature's legitimate tier-1
        // lines live. The bound is what stops "one more INFO" drifting into the account loop —
        // raising it is a decision, and it should be made with the rate in mind.
        long infoSites = source.lines().filter(line -> line.contains("log.info(")).count();
        assertThat(infoSites)
                .as("RegistrationWorker's INFO budget: worker started, worker shutting down, "
                        + "boot resume announcement, per-group cancelled, per-group complete. "
                        + "Each fires once per process or once per group per registration. A "
                        + "sixth needs the same argument made explicitly.")
                .isEqualTo(5);

        // And none of them is inside the account loop: every one names the group or the worker,
        // never a username. `username` is the per-account identity in this class.
        List<String> perAccountInfo = new ArrayList<>();
        List<String> lines = source.lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            if (!lines.get(i).contains("log.info(")) {
                continue;
            }
            StringBuilder statement = new StringBuilder();
            for (int j = i; j < Math.min(lines.size(), i + 6); j++) {
                statement.append(lines.get(j));
                if (lines.get(j).contains(");")) {
                    break;
                }
            }
            if (statement.toString().contains("username")) {
                perAccountInfo.add(statement.toString().trim());
            }
        }
        assertThat(perAccountInfo)
                .as("an INFO line that names a username is per-account by construction")
                .isEmpty();
    }
}
