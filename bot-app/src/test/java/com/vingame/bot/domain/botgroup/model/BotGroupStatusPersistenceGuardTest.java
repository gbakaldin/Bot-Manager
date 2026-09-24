package com.vingame.bot.domain.botgroup.model;

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
 * The three constants appended to {@link BotGroupStatus} must never reach Mongo
 * (GATEWAY_REQUEST_BUDGET A1), enforced against the source in the
 * {@code PerBotInfoLogGuardTest} / {@code GatewayCallSiteGuardTest} idiom.
 * <p>
 * <b>Why a source scan, and why it is worth one.</b> {@code targetStatus} is persisted as the
 * enum's {@code name()} string with no {@code MongoCustomConversions}. A document holding
 * {@code "STARTING"} is unreadable by any jar built before this feature — the mapper throws
 * {@code ConversionFailedException} — and {@code findByTargetStatus(ACTIVE)} is on the
 * application-ready boot path, so a single such document does not degrade one group, it fails
 * the whole startup query and takes the fleet down. That makes a rollback to
 * {@code vingame-bot:rollback-*} unsafe, which is the one thing this guard exists to keep
 * true. No dynamic test can see the absence of such a write; a {@code setTargetStatus} call
 * added in a year's time with the "obvious" argument would simply work, until the day someone
 * rolls back.
 * <p>
 * <b>What it cannot prove.</b> {@code setTargetStatus} is also called with variables (the
 * restart path restores a status it read from Mongo, so its value can only be one of the
 * original three). The scan therefore pins the literal form, which is every production call
 * site today, and a reviewer has to keep the variable ones honest.
 */
@DisplayName("BotGroupStatus: the appended constants are never persisted")
class BotGroupStatusPersistenceGuardTest {

    private static final List<Path> ROOT_CANDIDATES = List.of(Path.of(".."), Path.of("."));

    /** The values that may never appear as an argument to {@code setTargetStatus}. */
    private static final List<String> NOT_PERSISTABLE =
            List.of("STARTING", "REGISTRATION_PENDING", "REGISTRATION_FAILED");

    @Test
    @DisplayName("no production call site passes an appended constant to setTargetStatus")
    void noAppendedConstantIsEverPersisted() {
        List<String> offenders = new ArrayList<>();
        for (Path file : productionSources()) {
            List<String> code = stripCommentsAndLiterals(readLines(file));
            for (int i = 0; i < code.size(); i++) {
                String line = code.get(i);
                if (!line.contains("setTargetStatus(")) {
                    continue;
                }
                String argument = line.substring(line.indexOf("setTargetStatus(") + "setTargetStatus(".length());
                for (String forbidden : NOT_PERSISTABLE) {
                    if (argument.contains(forbidden)) {
                        offenders.add(file + ":" + (i + 1) + " → " + line.trim());
                    }
                }
            }
        }

        assertThat(offenders)
                .as("BotGroupStatus.%s must never be written to BotGroup.targetStatus: an older "
                                + "jar cannot deserialise it, and findByTargetStatus(ACTIVE) is on "
                                + "the boot path, so one such document fails the whole startup "
                                + "query. STARTING belongs to the runtime; the registration "
                                + "states are derived at the DTO boundary.",
                        NOT_PERSISTABLE)
                .isEmpty();
    }

    @Test
    @DisplayName("the scan actually sees the setTargetStatus call sites it is guarding")
    void theScanIsNotVacuous() {
        long sites = productionSources().stream()
                .flatMap(file -> stripCommentsAndLiterals(readLines(file)).stream())
                .filter(line -> line.contains("setTargetStatus("))
                .count();

        assertThat(sites)
                .as("if this drops to zero the guard above is passing on an empty scan")
                .isGreaterThan(3);
    }

    private static Path repoRoot() {
        Path root = ROOT_CANDIDATES.stream()
                .filter(candidate -> Files.isDirectory(candidate.resolve("bot-app")))
                .findFirst()
                .orElse(null);
        assumeTrue(root != null, "repo root not found from " + Path.of("").toAbsolutePath());
        return root;
    }

    /** Every {@code src/main/java} file in every module — the write could live anywhere. */
    private static List<Path> productionSources() {
        Path root = repoRoot();
        List<Path> files = new ArrayList<>();
        for (String module : List.of("bot-api", "bot-engine", "bot-messages", "bot-strategies", "bot-app")) {
            Path main = root.resolve(module).resolve("src/main/java");
            if (!Files.isDirectory(main)) {
                continue;
            }
            try (var walk = Files.walk(main)) {
                walk.filter(p -> p.toString().endsWith(".java")).forEach(files::add);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        assertThat(files).as("no production sources found — the scan would be vacuous").isNotEmpty();
        return files;
    }

    private static List<String> readLines(Path path) {
        try {
            return Files.readAllLines(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Remove block comments, line comments and string-literal contents so only real code is
     * scanned — these classes carry long comments <em>about</em> which statuses may be
     * persisted, and a comment is documentation, not a call site. Copied from
     * {@code GatewayCallSiteGuardTest} rather than shared: a guard that depends on another
     * guard's helper is one refactor away from silently scanning nothing.
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
}
