package com.vingame.bot.domain.botgroup.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vingame.bot.domain.botgroup.dto.BotGroupDTO;
import com.vingame.bot.domain.botgroup.mapper.BotGroupMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

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
 * (GATEWAY_REQUEST_BUDGET A1), enforced from <b>both</b> sides: a source scan for literal
 * writes, in the {@code PerBotInfoLogGuardTest} / {@code GatewayCallSiteGuardTest} idiom, and a
 * value-level check that the DTO boundary cannot carry such a value at all.
 * <p>
 * <b>What actually goes wrong, stated correctly.</b> {@code targetStatus} is persisted as the
 * enum's {@code name()} string with no {@code MongoCustomConversions}, so a document holding
 * {@code "STARTING"} is unreadable by any jar built before this feature: Spring Data's
 * {@code MappingMongoConverter} lets {@link Enum#valueOf}'s
 * {@link IllegalArgumentException} ("No enum constant …") out of
 * {@code getPotentiallyConvertedSimpleRead} — measured in
 * {@code BotGroupStatusRollbackSafetyTest}, and <b>not</b> the
 * {@code ConversionFailedException} that A1 and this class used to name.
 * <p>
 * <b>Which reads break, also stated correctly.</b> Not the boot query:
 * {@code findByTargetStatus(ACTIVE)} filters server-side on the string {@code "ACTIVE"}, so a
 * poisoned document is never returned and never converted. What breaks is every read that
 * <em>does</em> convert the group — {@code GET /{id}} and, worse,
 * {@code POST /{envId}/filter}, the UI's list view for a whole environment, where one poisoned
 * group 500s the list for every healthy group beside it. And on the <em>current</em> jar the
 * same document silently leaves {@code findByTargetStatus(ACTIVE)} and
 * {@code RecoveryEligibility}'s {@code ACTIVE}/{@code STOPPED}/{@code DEAD} branches, so the
 * group never auto-starts and never auto-recovers again: unmanaged, with nothing logged. Either
 * way a rollback to {@code vingame-bot:rollback-*} stops being a safe action, which is the one
 * thing this guard exists to keep true.
 * <p>
 * <b>Why a source scan at all.</b> No dynamic test can see the absence of a write; a
 * {@code setTargetStatus} call added in a year's time with the "obvious" argument would simply
 * work, until the day someone rolls back.
 * <p>
 * <b>Why the source scan is not enough on its own, and what closes the gap.</b> The hole QA
 * found was reachable through two shapes a scan structurally cannot judge: a Lombok
 * <em>builder</em> call ({@code .targetStatus(dto.getTargetStatus())} in {@code toEntity}) and a
 * setter taking a <em>variable</em> ({@code setTargetStatus(Optional.ofNullable(...).orElse(...))}
 * in {@code updateEntityFromDTO}) — where the variable was whatever a client had sent. So this
 * class also asserts, by value, that {@link BotGroupMapper} copies {@code targetStatus} in
 * neither write direction, for <b>every</b> constant rather than for today's three. That
 * formulation survives the next appended constant without an edit, which the enumerate-the-bad-
 * values formulation does not.
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
                                + "jar cannot deserialise it (IllegalArgumentException out of "
                                + "Enum.valueOf), which 500s GET /{id} and the whole env list "
                                + "view and makes a rollback unsafe. STARTING belongs to the "
                                + "runtime; the registration states are derived at the DTO "
                                + "boundary.",
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

    @Test
    @DisplayName("the mapper copies targetStatus in neither write direction — builder or setter")
    void theMapperNeverCarriesTargetStatus() {
        BotGroupMapper mapper = Mappers.getMapper(BotGroupMapper.class);

        for (BotGroupStatus status : BotGroupStatus.values()) {
            // POST / — the create path, through the Lombok builder. A scan cannot see this at
            // all: it is not a setTargetStatus( call.
            BotGroupDTO created = new BotGroupDTO();
            created.setName("n");
            created.setTargetStatus(status);

            assertThat(mapper.toEntity(created).getTargetStatus())
                    .as("POST /api/v1/bot-group/ must not carry %s into the entity", status)
                    .isNull();

            // PATCH /{id} — the merge path, through a setter whose argument is a variable. A
            // scan can see the call but not the value, and the value is whatever a client sent.
            BotGroup existing = BotGroup.builder().id("g-1").name("n")
                    .targetStatus(BotGroupStatus.ACTIVE).build();
            BotGroupDTO patch = new BotGroupDTO();
            patch.setTargetStatus(status);
            mapper.updateEntityFromDTO(patch, existing);

            assertThat(existing.getTargetStatus())
                    .as("PATCH /api/v1/bot-group/{id} must not carry %s into the entity", status)
                    .isEqualTo(BotGroupStatus.ACTIVE);
        }
    }

    @Test
    @DisplayName("the DTO field is READ_ONLY, so Jackson never populates it from a request body")
    void theDtoFieldIsReadOnlyInbound() throws Exception {
        // Belt to the mapper's braces, and the half that also covers any future in-process
        // mapper. Asserted through a real ObjectMapper rather than by reading the annotation,
        // because the annotation is only worth what Jackson does with it.
        ObjectMapper objectMapper = new ObjectMapper();

        BotGroupDTO read = objectMapper.readValue(
                "{\"name\":\"n\",\"targetStatus\":\"STARTING\"}", BotGroupDTO.class);

        assertThat(read.getTargetStatus())
                .as("a request body must not be able to set targetStatus at all; lifecycle is "
                        + "what POST /{id}/start and /stop are for")
                .isNull();
        assertThat(read.getName()).as("the rest of the body still binds").isEqualTo("n");

        // Still rendered outbound — A3 has POST / answer with targetStatus, and Phase 4 renders
        // REGISTRATION_PENDING there. READ_ONLY is what makes that safe for a read-modify-write
        // client: the value comes back, is handed back, and is ignored.
        BotGroupDTO rendered = new BotGroupDTO();
        rendered.setTargetStatus(BotGroupStatus.STARTING);
        assertThat(objectMapper.writeValueAsString(rendered)).contains("\"targetStatus\":\"STARTING\"");
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
