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
import java.util.stream.Stream;

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
 *   <li>{@link #perBotClassesHaveNoInfoLogging()} — a set of classes in which <em>every</em>
 *       line is per-bot by construction may not contain {@code log.info(} at all. Coarse, but
 *       that is what makes it hold without maintenance.</li>
 *   <li>{@link #theDemotedSitesStayDemoted()} — the specific messages demoted by this
 *       feature, in files that legitimately keep group-level INFO lines and so cannot be
 *       covered by a whole-file ban.</li>
 * </ol>
 * A genuinely group-scoped line that has to live in one of the banned classes should be moved
 * to its group-level caller (which is what the "strategy mix" line did), not exempted here.
 * <p>
 * <b>Directories, not a file list.</b> The banned set is two <em>directories</em>
 * ({@link #PER_BOT_DIRECTORIES}) plus a short list of individually-named files, because a
 * hand-maintained file list is the same manual enumeration that came up short twice: the Up
 * Down bot on the Q3 roadmap lands in {@code domain/bot/core/} and a per-bot strategy lands in
 * {@code bot-strategies/}, and neither would inherit a guard. A directory scan fails
 * <em>closed</em> on a new class. {@link #GROUP_LEVEL_EXEMPTIONS} carries any file in
 * those trees that is not per-bot, each with the reason, and
 * {@link #theGuardIsNotVacuous()} asserts every exemption still exists so a rename cannot
 * quietly widen the hole.
 * <p>
 * <b>What this cannot see.</b> The scan is syntactic: {@code log.atInfo().log(...)}, a
 * logger field under another name, and a {@code LoggerFactory.getLogger} local all pass it.
 * It is a tripwire on the one spelling this codebase actually uses ({@code @Slf4j} +
 * {@code log.info(}), not a proof. Comments and string literals are stripped before the scan
 * (see {@link #sanitized}) — these files carry long comments <em>about</em> the demotions, and
 * a comment quoting the old call must not fail the build.
 */
@DisplayName("Tier-1 invariant: no per-bot INFO logging")
class PerBotInfoLogGuardTest {

    /** Surefire runs with the module directory as CWD; the repo root is one level up. */
    private static final List<Path> ROOT_CANDIDATES = List.of(Path.of(".."), Path.of("."));

    /**
     * Trees in which every class is per-bot: the bot cores (one instance per bot, running on
     * that bot's own thread) and the strategies (the factories hand each bot its own instance,
     * and the strategy is consulted per round per bot). Scanned recursively, so a class added
     * to either tree is guarded on the day it lands.
     */
    private static final List<String> PER_BOT_DIRECTORIES = List.of(
            "bot-engine/src/main/java/com/vingame/bot/domain/bot/core",
            "bot-strategies/src/main/java/com/vingame/bot/domain/bot/strategy");

    /** Per-bot classes that live in trees which are not per-bot as a whole. */
    private static final List<String> PER_BOT_FILES = List.of(
            "bot-engine/src/main/java/com/vingame/bot/infrastructure/client/ClientFactory.java",
            "bot-app/src/main/java/com/vingame/bot/domain/bot/service/BotFactory.java");

    /**
     * Files inside {@link #PER_BOT_DIRECTORIES} that are not per-bot. Anything added here
     * needs the argument the two strategy factories used to carry: fires a bounded number of
     * times per <em>process</em>, never per bot.
     * <p>
     * Empty since PLUGIN_HOT_RELOAD_3_4 Phase 3a: {@code BettingStrategyFactory} and
     * {@code SlotStrategyFactory} — the only two entries, each with one startup-only INFO line
     * — moved to {@code bot-engine/…/domain/bot/strategy/}, which is not a banned tree, so
     * there is nothing left to exempt. Kept, with the existence check in
     * {@link #theGuardIsNotVacuous()}, so a future exemption gets the same protection.
     */
    private static final List<String> GROUP_LEVEL_EXEMPTIONS = List.of();

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

    /**
     * Every guarded file, repo-root-relative: the recursive contents of
     * {@link #PER_BOT_DIRECTORIES} minus {@link #GROUP_LEVEL_EXEMPTIONS}, plus
     * {@link #PER_BOT_FILES}. Sorted, so a failure names files in a stable order.
     */
    private static List<String> guardedFiles(Path root) {
        List<String> guarded = new ArrayList<>();
        for (String directory : PER_BOT_DIRECTORIES) {
            Path path = root.resolve(directory);
            assertThat(path)
                    .as("%s has moved — update this guard rather than deleting it", directory)
                    .isDirectory();
            try (Stream<Path> walk = Files.walk(path)) {
                walk.filter(candidate -> candidate.getFileName().toString().endsWith(".java"))
                        .map(candidate -> root.relativize(candidate).toString())
                        .filter(relative -> !GROUP_LEVEL_EXEMPTIONS.contains(relative))
                        .forEach(guarded::add);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        for (String relative : PER_BOT_FILES) {
            assertThat(root.resolve(relative))
                    .as("%s has moved — update this guard rather than deleting it", relative)
                    .exists();
            guarded.add(relative);
        }
        guarded.sort(String::compareTo);
        return guarded;
    }

    @Test
    @DisplayName("classes that only ever log per bot contain no log.info at all")
    void perBotClassesHaveNoInfoLogging() {
        Path root = repoRoot();
        List<String> offenders = new ArrayList<>();
        for (String relative : guardedFiles(root)) {
            // Comments and string literals stripped: these files carry long comments about
            // the demotions, and one that quotes `log.info("…")` is documentation, not a
            // defect. A real call survives the strip, because only the literal's *contents*
            // are removed — `log.info(` itself is code.
            List<String> raw = linesOf(root.resolve(relative));
            List<String> code = sanitized(raw, false);
            for (int i = 0; i < code.size(); i++) {
                if (code.get(i).contains("log.info(")) {
                    offenders.add(relative + ":" + (i + 1) + " -> " + raw.get(i).trim());
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
            // Comments stripped, string literals KEPT — the pin is on the message text, which
            // only exists inside a literal. A comment quoting the old INFO call is still not
            // a call, and must not fail the build.
            List<String> lines = sanitized(linesOf(root.resolve(site.getValue())), true);
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
        // Anti-vacuity: if every scanned path silently stopped existing the first test would
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

        // …and that the directory scan really resolves to the bots and the strategies. A
        // renamed package would otherwise leave `guardedFiles` returning the two named files
        // and nothing else, which still passes the ban.
        List<String> guarded = guardedFiles(root);
        assertThat(guarded)
                .as("the bot cores and the per-bot strategies are in the scan")
                .contains("bot-engine/src/main/java/com/vingame/bot/domain/bot/core/Bot.java",
                        "bot-engine/src/main/java/com/vingame/bot/domain/bot/core/"
                                + "BettingMiniGameBot.java",
                        "bot-engine/src/main/java/com/vingame/bot/domain/bot/core/"
                                + "SlotMachineBot.java",
                        "bot-engine/src/main/java/com/vingame/bot/domain/bot/core/"
                                + "TaiXiuGameBot.java",
                        // CASHOUT_BOT AD-13: nothing per bet or per bot at INFO.
                        "bot-engine/src/main/java/com/vingame/bot/domain/bot/core/"
                                + "CashoutBot.java",
                        "bot-engine/src/main/java/com/vingame/bot/domain/bot/core/"
                                + "cashout/CashoutBetStateMachine.java",
                        // AVIATOR_BOT AD-13: nothing per bot, per round or per bet at INFO.
                        "bot-engine/src/main/java/com/vingame/bot/domain/bot/core/"
                                + "CrashBot.java",
                        "bot-engine/src/main/java/com/vingame/bot/domain/bot/core/"
                                + "crash/CrashRoundStateMachine.java",
                        "bot-engine/src/main/java/com/vingame/bot/domain/bot/core/"
                                + "crash/RoundSilenceWatch.java",
                        "bot-strategies/src/main/java/com/vingame/bot/domain/bot/strategy/"
                                + "RandomBehaviorStrategy.java",
                        "bot-strategies/src/main/java/com/vingame/bot/domain/bot/strategy/"
                                + "martingale/ClassicMartingaleStrategy.java",
                        "bot-strategies/src/main/java/com/vingame/bot/domain/bot/strategy/"
                                + "slot/FixedBetStrategy.java");
        // An exemption that no longer names a real file is a silently widened hole: the
        // filter stops matching anything and nobody notices until the class it was written
        // for comes back under another name.
        for (String exemption : GROUP_LEVEL_EXEMPTIONS) {
            assertThat(root.resolve(exemption))
                    .as("%s is exempted from the per-bot ban but does not exist — re-justify "
                            + "the exemption or drop it", exemption)
                    .exists();
            assertThat(guarded).doesNotContain(exemption);
        }
    }

    @Test
    @DisplayName("the scanner reads code, not comments — a quoted log.info( is documentation")
    void theScannerIgnoresCommentsAndLiterals() {
        List<String> source = List.of(
                "/**",                                                                    // 0
                " * <p>This was {@code log.info(\"assigned strategy\")} before AD-14.",    // 1
                " */",                                                                    // 2
                "// log.info(\"the old per-bot line, quoted in a comment\");",             // 3
                "log.debug(\"replaced log.info( at this site\");",                         // 4
                "/* log.info(\"block\"); */ log.debug(\"real\");",                         // 5
                "log.info(\"a genuine offender\");",                                       // 6
                "String s = \"log.info(\"; // a literal, not a call");                     // 7

        List<String> code = sanitized(source, false);

        assertThat(code.subList(0, 6))
                .as("javadoc, line comments, block comments and literal text are not calls")
                .allSatisfy(line -> assertThat(line).doesNotContain("log.info("));
        assertThat(code.get(4)).contains("log.debug(");
        assertThat(code.get(5)).contains("log.debug(");
        assertThat(code.get(6))
                .as("the strip must not cost detection power — a real call is still a call")
                .contains("log.info(");
        assertThat(code.get(7)).doesNotContain("log.info(");

        // Literals kept: the demoted-site pin matches on message text, which lives in one.
        assertThat(sanitized(source, true).get(6)).contains("a genuine offender");
    }

    /**
     * Java source with comments removed, so the scan sees code rather than prose. Line count
     * and line numbering are preserved; only the removed characters go.
     *
     * @param keepStringLiterals keep the <em>contents</em> of string and char literals. The
     *                           delimiters are dropped either way, so an unterminated literal
     *                           cannot swallow the file. Kept when the check matches on
     *                           message text, dropped when it matches on call syntax (a
     *                           literal containing the text {@code log.info(} is not a call).
     */
    private static List<String> sanitized(List<String> raw, boolean keepStringLiterals) {
        List<String> out = new ArrayList<>(raw.size());
        boolean inBlockComment = false;
        boolean inTextBlock = false;
        for (String line : raw) {
            StringBuilder code = new StringBuilder();
            boolean inString = false;
            boolean inChar = false;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                char next = i + 1 < line.length() ? line.charAt(i + 1) : '\0';
                if (inBlockComment) {
                    if (c == '*' && next == '/') {
                        inBlockComment = false;
                        i++;
                    }
                } else if (inTextBlock) {
                    if (line.startsWith("\"\"\"", i)) {
                        inTextBlock = false;
                        i += 2;
                    } else if (keepStringLiterals) {
                        code.append(c);
                    }
                } else if (inString || inChar) {
                    if (c == '\\') {
                        i++;
                    } else if (inString && c == '"') {
                        inString = false;
                    } else if (inChar && c == '\'') {
                        inChar = false;
                    } else if (keepStringLiterals) {
                        code.append(c);
                    }
                } else if (c == '/' && next == '*') {
                    inBlockComment = true;
                    i++;
                } else if (c == '/' && next == '/') {
                    break;
                } else if (line.startsWith("\"\"\"", i)) {
                    inTextBlock = true;
                    i += 2;
                } else if (c == '"') {
                    inString = true;
                } else if (c == '\'') {
                    inChar = true;
                } else {
                    code.append(c);
                }
            }
            // A string literal cannot span lines in Java (a text block can), so reset here
            // rather than carrying a mis-parse into the next line.
            out.add(code.toString());
        }
        return out;
    }
}
