package com.vingame.bot.domain.bot.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A <b>source</b> guard for RIK_114_ZICZAC's one stated failure mode that no behavioural
 * test can reach: a <i>second</i> call site of
 * {@code MessageTypesRegistry.bettingMini(...)} that forgets {@code .forGame(game)}.
 *
 * <p>{@code BotFactoryForGameResolutionTest} pins what the existing call site does and
 * {@code GameMessageTypesForGameContractTest} pins what the providers return. Neither can
 * see a call site that does not exist yet, and the plan's own Implementation Notes
 * concede the point — "the routing test pins the behaviour, not the call site, so
 * reviewers should watch for the second call site". Watching is not a guard. The failure
 * it is watching for is silent: a ziczac bot resolved through the generic 114 provider
 * parses every frame, subscribes, stakes, observes rounds, and reports
 * {@code bot_winnings_total = 0} forever — which {@code CLAUDE.md} records as having
 * been misdiagnosed as a wallet-partition bug twice.
 *
 * <p>The idiom — asserting a rule against the source tree rather than against behaviour —
 * is {@code PerBotInfoLogGuardTest}'s, for the same reason: some invariants are about
 * code that has not been written.
 *
 * <p><b>If this fails, do not add an exemption.</b> Either append {@code .forGame(game)}
 * to the new call site, or — if the new caller genuinely has no {@code Game} — that is
 * the design question to answer before merging, because a caller with no game cannot
 * pick the right message classes for one.
 */
@DisplayName("MessageTypesRegistry.bettingMini has exactly one production call site, and it is forGame-resolved")
class BettingMiniLookupCallSiteGuardTest {

    /** Surefire runs with the module directory as CWD; the repo root is one level up. */
    private static final List<Path> ROOT_CANDIDATES = List.of(Path.of(".."), Path.of("."));

    /** Every module that can hold production code calling the registry. */
    private static final List<String> MAIN_SOURCE_ROOTS = List.of(
            "bot-app/src/main/java",
            "bot-engine/src/main/java",
            "bot-messages/src/main/java",
            "bot-api/src/main/java",
            "bot-strategies/src/main/java");

    /**
     * The lookup whose result must be game-resolved — an invocation <b>with an
     * argument</b>.
     * <p>
     * The zero-arg form is deliberately excluded: {@code MessageTypesRegistry} holds an
     * internal {@code Tables} record whose component accessor is also called
     * {@code bettingMini()}, and {@code tables.bettingMini()} returns the map, not a
     * provider. Matching it would make this guard fail on code that has nothing to do
     * with per-game resolution, which is how a guard gets an exemption list and then
     * gets ignored.
     */
    private static final Pattern LOOKUP = Pattern.compile("\\.bettingMini\\(\\s*[^)\\s]");

    private static Path repoRoot() {
        Path root = ROOT_CANDIDATES.stream()
                .filter(candidate -> Files.isDirectory(candidate.resolve("bot-engine")))
                .findFirst()
                .orElse(null);
        assumeTrue(root != null, "repo root not found from " + Path.of("").toAbsolutePath());
        return root;
    }

    /** Every {@code (file:line, text)} in production code that calls {@code bettingMini(}. */
    private static List<String> lookupCallSites(Path root) {
        List<String> hits = new ArrayList<>();
        for (String sourceRoot : MAIN_SOURCE_ROOTS) {
            Path dir = root.resolve(sourceRoot);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                    List<String> lines;
                    try {
                        lines = Files.readAllLines(p);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    for (int i = 0; i < lines.size(); i++) {
                        String line = lines.get(i);
                        // Skip comment and javadoc lines: the registry's own class
                        // documentation and BotFactory's explanatory comment both name
                        // the method, and neither is a call.
                        String trimmed = line.trim();
                        if (trimmed.startsWith("//") || trimmed.startsWith("*")
                                || trimmed.startsWith("/*")) {
                            continue;
                        }
                        if (LOOKUP.matcher(line).find()) {
                            hits.add(root.relativize(p) + ":" + (i + 1) + " >> " + trimmed);
                        }
                    }
                });
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return hits;
    }

    @Test
    @DisplayName("exactly one production call site, in BotFactory, and it chains .forGame(game)")
    void theOnlyLookupIsGameResolved() {
        Path root = repoRoot();
        List<String> callSites = lookupCallSites(root);

        // The declaration inside MessageTypesRegistry itself is `public GameMessageTypes
        // bettingMini(String ...)` — no leading dot — so it is not matched. What is
        // matched is an invocation on a registry reference.
        assertThat(callSites)
                .as("production call sites of MessageTypesRegistry.bettingMini(...). "
                        + "There must be exactly one (BotFactory's BETTING_MINI branch); a "
                        + "second one that omits .forGame(game) sends ziczac back to the "
                        + "generic provider and bot_winnings_total silently back to zero "
                        + "(RIK_114_ZICZAC AD-3).")
                .hasSize(1);

        String only = callSites.get(0);
        assertThat(only)
                .as("the single call site must live in BotFactory")
                .contains("BotFactory.java");
        assertThat(only)
                .as("the single call site must be game-resolved: %s", only)
                .contains(".forGame(game)");
    }

    @Test
    @DisplayName("the guard is actually scanning source — it found BotFactory at all")
    void theGuardIsNotVacuous() {
        // A typo in a source root, a module rename or a CWD change would turn every
        // assertion above into "0 hits, which is <= 1". Prove the walk sees real files.
        Path root = repoRoot();
        assertThat(root.resolve("bot-app/src/main/java/com/vingame/bot/domain/bot/service/BotFactory.java"))
                .exists();
        assertThat(lookupCallSites(root)).isNotEmpty();
    }
}
