package com.vingame.bot.infrastructure.logging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * LOG_VOLUME_TIERING's tier-1 invariant, enforced against the source rather than against a
 * guideline: <b>INFO must not contain anything whose rate is a function of bot count.</b>
 * <p>
 * <b>Why a source scan and not a behavioural test.</b> That invariant has now been enumerated
 * twice — once by the plan (five sites) and once by the implementation (six) — and both
 * enumerations came up short. The sites that were missed were missed precisely because they
 * live in classes nobody thinks of as "logging" classes: a Netty builder lambda in
 * {@code ClientFactory}, a virtual-thread body in {@code BotGroupRuntime}, a strategy
 * assignment in {@code BotGroupBehaviorService}. {@code ClientFactory}'s was the expensive
 * one: {@code newClient()} is called from {@code Bot.initialize}, {@code Bot.restart()} and
 * the re-auth path, so it fired per bot at start, per periodic-logout cycle and per reconnect
 * — cancelling out the demotion of {@code restart requested} three frames earlier. A test
 * that fails the build is the only version of this rule that survives the next feature.
 * <p>
 * Two complementary checks:
 * <ol>
 *   <li>{@link #perBotClassesHaveNoInfoLogging()} — a small set of classes in which
 *       <em>every</em> line is per-bot by construction may not contain {@code log.info(} at
 *       all. Coarse, but that is what makes it hold without maintenance.</li>
 *   <li>{@link #theDemotedSitesStayDemoted()} — the specific messages demoted by this
 *       feature, in files that legitimately keep group-level INFO lines and so cannot be
 *       covered by a whole-file ban.</li>
 * </ol>
 * A genuinely group-scoped line that has to live in one of the banned classes should be moved
 * to its group-level caller (which is what the "strategy mix" line did), not exempted here.
 */
@DisplayName("Tier-1 invariant: no per-bot INFO logging")
class PerBotInfoLogGuardTest {

    /** Surefire runs with the module directory as CWD; the repo root is one level up. */
    private static final List<Path> ROOT_CANDIDATES = List.of(Path.of(".."), Path.of("."));

    /**
     * Classes whose logging is per-bot in its entirety. Every one of these runs on a bot's
     * own thread or once per bot instance, so any INFO line in them scales with bot count.
     */
    private static final List<String> PER_BOT_CLASSES = List.of(
            "bot-engine/src/main/java/com/vingame/bot/domain/bot/core/Bot.java",
            "bot-engine/src/main/java/com/vingame/bot/domain/bot/core/BettingMiniGameBot.java",
            "bot-engine/src/main/java/com/vingame/bot/domain/bot/core/SlotMachineBot.java",
            "bot-engine/src/main/java/com/vingame/bot/domain/bot/core/TaiXiuGameBot.java",
            "bot-engine/src/main/java/com/vingame/bot/infrastructure/client/ClientFactory.java",
            "bot-app/src/main/java/com/vingame/bot/domain/bot/service/BotFactory.java");

    /**
     * message fragment → the file it lives in. These files keep legitimate group-level INFO
     * lines, so only the named messages are pinned.
     */
    private static final Map<String, String> DEMOTED_SITES = Map.of(
            "Bot starting in virtual thread",
            "bot-app/src/main/java/com/vingame/bot/infrastructure/runtime/BotGroupRuntime.java",
            "assigned strategy",
            "bot-app/src/main/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorService.java",
            "assigned slot strategy",
            "bot-app/src/main/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorService.java");

    private static Path repoRoot() {
        Path root = ROOT_CANDIDATES.stream()
                .filter(candidate -> Files.isDirectory(candidate.resolve("bot-engine")))
                .findFirst()
                .orElse(null);
        // Absent only in a module-only build from an unusual CWD; skipping beats failing the
        // build over a path assumption. Same posture as Log4j2TwinConfigTest.
        assumeTrue(root != null, "repo root not found from " + Path.of("").toAbsolutePath());
        return root;
    }

    private static List<String> linesOf(Path path) {
        try {
            return Files.readAllLines(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("classes that only ever log per bot contain no log.info at all")
    void perBotClassesHaveNoInfoLogging() {
        Path root = repoRoot();
        List<String> offenders = new ArrayList<>();
        for (String relative : PER_BOT_CLASSES) {
            Path path = root.resolve(relative);
            assertThat(path)
                    .as("%s has moved — update this guard rather than deleting it", relative)
                    .exists();
            List<String> lines = linesOf(path);
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).contains("log.info(")) {
                    offenders.add(relative + ":" + (i + 1) + " -> " + lines.get(i).trim());
                }
            }
        }
        assertThat(offenders)
                .as("INFO must not contain anything whose rate is a function of bot count "
                        + "(LOG_VOLUME_TIERING Phase 1 step 5). Fold the fact into a "
                        + "group-level line at the caller, or log it at DEBUG.")
                .isEmpty();
    }

    @Test
    @DisplayName("the per-bot sites demoted by LOG_VOLUME_TIERING are still at DEBUG")
    void theDemotedSitesStayDemoted() {
        Path root = repoRoot();
        for (Map.Entry<String, String> site : DEMOTED_SITES.entrySet()) {
            List<String> lines = linesOf(root.resolve(site.getValue()));
            List<String> matching = lines.stream()
                    .filter(line -> line.contains(site.getKey()) && line.contains("log."))
                    .toList();
            assertThat(matching)
                    .as("%s vanished from %s — this guard is now proving nothing",
                            site.getKey(), site.getValue())
                    .isNotEmpty();
            assertThat(matching)
                    .as("%s fires once per bot at group start", site.getKey())
                    .allSatisfy(line -> assertThat(line).doesNotContain("log.info("));
        }
    }

    @Test
    @DisplayName("the guard covers the modules the bots actually live in")
    void theGuardIsNotVacuous() {
        Path root = repoRoot();
        // Anti-vacuity: if every listed path silently stopped existing the first test would
        // still be green, and the second only pins three messages. Prove the scan sees real
        // logging code by finding the DEBUG lines that replaced the INFO ones.
        assertThat(linesOf(root.resolve(
                "bot-engine/src/main/java/com/vingame/bot/infrastructure/client/ClientFactory.java")))
                .as("ClientFactory still logs the shared EventLoopGroup, just not at INFO")
                .anyMatch(line -> line.contains("log.debug(")
                        && line.contains("Setting shared EventLoopGroup"));
        assertThat(linesOf(root.resolve(
                "bot-app/src/main/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorService.java")))
                .as("the group-level replacement for the per-bot strategy lines")
                .anyMatch(line -> line.contains("log.info(") && line.contains("strategy mix"));
    }
}
