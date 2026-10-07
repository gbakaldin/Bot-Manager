package com.vingame.bot.infrastructure.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Engine-side source guards for the plugin loader rules (PLUGIN_HOT_RELOAD_3_4 D-14), over
 * {@code bot-engine} and {@code bot-app} main sources (and {@code bot-api}'s
 * {@code OutputPrinter}, the one static mapper that sits on a per-bot pipeline).
 *
 * <h2>L-7 — no static or process-lifetime mapper ever touches a plugin type</h2>
 * Spike scenarios 3c/3d: subtypes registered on ws-parser's static
 * {@code ObjectMapperProvider.getDefault()} pin a plugin loader <b>permanently</b> (there is
 * no unregister), and merely (de)serializing a plugin type through one pins it until its
 * caches are flushed by reflection. So:
 * <ul>
 *   <li>{@code ObjectMapperProvider} appears only as the mapper of an {@code OutputPrinter}
 *       pipeline context — {@code buildContext("OutputPrinter", ObjectMapperProvider.getDefault()…};
 *       never {@code strict()} / {@code lenient()}, never a call on {@code getDefault()};</li>
 *   <li>{@code registerSubtypes} runs only on a per-bot mapper, built by
 *       {@code newMessageMapper()} in the same method;</li>
 *   <li>{@code OutputPrinter}'s context gets no typed matcher, and its own static mapper only
 *       ever reads {@code Object.class};</li>
 *   <li>no static {@code ActionResponseMessage.serialize/deserialize};</li>
 *   <li>ws-parser's {@code AuthClient} is used for {@code generateFingerprint()} only — its
 *       static mapper serializes login requests, so those stay engine-side;</li>
 *   <li>the only static {@code ObjectMapper}s are {@code ApiGatewayClient}'s and
 *       {@code GameMsClient}'s, and neither file imports a plugin-facing type
 *       ({@code domain.bot.message} / {@code domain.bot.strategy}).</li>
 * </ul>
 *
 * <h2>L-11 (engine half) — no platform thread constructed per call</h2>
 * {@code GameMsClient}'s per-deposit {@code new Thread} became a virtual thread in 4b; a
 * platform thread built on a stack that can hold plugin frames captures their protection
 * domains for life (spike 7b). No {@code new Thread(} or {@code Thread.ofPlatform()} in
 * {@code bot-engine} main.
 * <p>
 * Syntactic tripwires on the spellings this codebase uses, not proofs; comments are
 * stripped first so a comment quoting a forbidden call does not fail the build.
 */
@DisplayName("Plugin loader rules L-7 / L-11: engine-side source guards")
class PluginLoaderRulesGuardTest {

    private static final List<Path> ROOT_CANDIDATES = List.of(Path.of(".."), Path.of("."));

    private static final List<String> ENGINE_AND_APP = List.of(
            "bot-engine/src/main/java", "bot-app/src/main/java");

    private static final String OUTPUT_PRINTER =
            "bot-api/src/main/java/com/vingame/bot/domain/bot/util/OutputPrinter.java";

    private static final List<String> STATIC_MAPPER_OWNERS = List.of(
            "bot-engine/src/main/java/com/vingame/bot/infrastructure/client/ApiGatewayClient.java",
            "bot-engine/src/main/java/com/vingame/bot/infrastructure/client/GameMsClient.java");

    @Test
    @DisplayName("ObjectMapperProvider is only ever an OutputPrinter context's mapper")
    void objectMapperProviderOnlyFeedsOutputPrinter() {
        Pattern use = Pattern.compile("ObjectMapperProvider\\s*\\.");
        Pattern allowed = Pattern.compile(
                "buildContext\\(\\s*\"OutputPrinter\"\\s*,\\s*ObjectMapperProvider\\s*\\.\\s*getDefault\\(\\)\\s*[,)]");
        List<String> violations = new ArrayList<>();
        int allowedUses = 0;
        for (SourceFile file : sources(ENGINE_AND_APP)) {
            for (int i = 0; i < file.lines.size(); i++) {
                String line = file.lines.get(i);
                if (line.trim().startsWith("import ")) {
                    continue;
                }
                Matcher m = use.matcher(line);
                while (m.find()) {
                    if (allowed.matcher(line).find()) {
                        allowedUses++;
                    } else {
                        violations.add(file.where(i) + ": " + line.trim());
                    }
                }
            }
        }
        assertThat(violations)
                .as("L-7: a static ws-parser mapper that registers or (de)serializes a plugin type "
                        + "pins its loader (spike 3c/3d). Plugin types go through per-bot mappers only.")
                .isEmpty();
        assertThat(allowedUses).as("the four OutputPrinter contexts exist (guard not vacuous)").isGreaterThanOrEqualTo(4);
    }

    @Test
    @DisplayName("registerSubtypes only on a per-bot mapper built by newMessageMapper() in the same method")
    void registerSubtypesOnlyOnPerBotMappers() {
        Pattern register = Pattern.compile("\\.\\s*registerSubtypes\\s*\\(");
        Pattern onLocalMapper = Pattern.compile("^\\s*mapper\\s*\\.\\s*registerSubtypes\\s*\\(");
        Pattern builtPerBot = Pattern.compile("ObjectMapper\\s+mapper\\s*=\\s*newMessageMapper\\(\\)\\s*;");
        List<String> violations = new ArrayList<>();
        int perBotSites = 0;
        for (SourceFile file : sources(ENGINE_AND_APP)) {
            for (int i = 0; i < file.lines.size(); i++) {
                String line = file.lines.get(i);
                if (!register.matcher(line).find()) {
                    continue;
                }
                boolean precededByPerBotMapper = false;
                for (int back = i - 1; back >= Math.max(0, i - 3); back--) {
                    if (builtPerBot.matcher(file.lines.get(back)).find()) {
                        precededByPerBotMapper = true;
                    }
                }
                if (onLocalMapper.matcher(line).find() && precededByPerBotMapper) {
                    perBotSites++;
                } else {
                    violations.add(file.where(i) + ": " + line.trim());
                }
            }
        }
        assertThat(violations)
                .as("L-7: subtypes may only be registered on the mapper `ObjectMapper mapper = "
                        + "newMessageMapper();` built in the same method (per bot, bundle TypeFactory)")
                .isEmpty();
        assertThat(perBotSites).as("BettingMini, Slot, Cashout, Crash (guard not vacuous)").isGreaterThanOrEqualTo(4);
    }

    @Test
    @DisplayName("OutputPrinter has no typed matcher and its static mapper reads Object.class only")
    void outputPrinterStaysUntyped() {
        SourceFile printer = source(OUTPUT_PRINTER);
        List<String> violations = new ArrayList<>();
        Pattern typed = Pattern.compile("\\.\\s*(onMessage|as|tryAs)\\s*\\(|Qualifier\\s*\\.\\s*is\\s*\\(");
        Pattern readValue = Pattern.compile("readValue\\s*\\(");
        Pattern readObject = Pattern.compile("readValue\\s*\\([^;]*,\\s*Object\\.class\\s*\\)");
        for (int i = 0; i < printer.lines.size(); i++) {
            String line = printer.lines.get(i);
            if (typed.matcher(line).find()) {
                violations.add(printer.where(i) + ": " + line.trim());
            }
            if (readValue.matcher(line).find() && !readObject.matcher(line).find()) {
                violations.add(printer.where(i) + ": " + line.trim());
            }
        }
        assertThat(violations)
                .as("L-7: OutputPrinter's pipeline runs on getDefault(); a typed matcher there would "
                        + "deserialize a plugin type through the static mapper")
                .isEmpty();
    }

    @Test
    @DisplayName("no static ActionResponseMessage.serialize/deserialize, AuthClient for fingerprints only")
    void noStaticWsParserMapperPaths() {
        Pattern actionResponseStatic = Pattern.compile("ActionResponseMessage\\s*\\.\\s*(serialize|deserialize|MAPPER)\\b");
        Pattern authClient = Pattern.compile("\\bAuthClient\\b");
        Pattern authClientAllowed = Pattern.compile("AuthClient\\s*\\.\\s*generateFingerprint\\s*\\(");
        List<String> violations = new ArrayList<>();
        for (SourceFile file : sources(ENGINE_AND_APP)) {
            for (int i = 0; i < file.lines.size(); i++) {
                String line = file.lines.get(i);
                if (line.trim().startsWith("import ")) {
                    continue;
                }
                if (actionResponseStatic.matcher(line).find()) {
                    violations.add(file.where(i) + ": " + line.trim());
                }
                if (authClient.matcher(line).find() && !authClientAllowed.matcher(line).find()) {
                    violations.add(file.where(i) + ": " + line.trim());
                }
            }
        }
        assertThat(violations)
                .as("L-7: ws-parser's static ActionResponseMessage / AuthClient mappers must never see "
                        + "a plugin type; per-brand LoginRequests stay engine-side")
                .isEmpty();
    }

    @Test
    @DisplayName("the only static ObjectMappers are ApiGatewayClient's and GameMsClient's, and they import no plugin-facing type")
    void staticMappersAreKnownAndPluginFree() {
        Pattern staticMapper = Pattern.compile("\\bstatic\\b[^;=(]*\\bObjectMapper\\b\\s+\\w+\\s*=");
        Pattern pluginFacingImport = Pattern.compile(
                "^\\s*import\\s+com\\.vingame\\.bot\\.domain\\.bot\\.(message|strategy)\\.");
        List<String> violations = new ArrayList<>();
        List<String> owners = new ArrayList<>();
        for (SourceFile file : sources(ENGINE_AND_APP)) {
            boolean owner = STATIC_MAPPER_OWNERS.contains(file.relative);
            for (int i = 0; i < file.lines.size(); i++) {
                String line = file.lines.get(i);
                if (staticMapper.matcher(line).find()) {
                    if (owner) {
                        owners.add(file.relative);
                    } else {
                        violations.add(file.where(i) + ": " + line.trim());
                    }
                }
                if (owner && pluginFacingImport.matcher(line).find()) {
                    violations.add(file.where(i) + ": " + line.trim());
                }
            }
        }
        assertThat(violations)
                .as("L-7: a new process-lifetime mapper, or a static-mapper owner reaching for "
                        + "plugin-facing types")
                .isEmpty();
        assertThat(owners).as("both known owners still have their static mapper (guard not vacuous)")
                .containsExactlyInAnyOrderElementsOf(STATIC_MAPPER_OWNERS);
    }

    @Test
    @DisplayName("L-11: no `new Thread(` or Thread.ofPlatform() in bot-engine main")
    void noPlatformThreadsInTheEngine() {
        Pattern platform = Pattern.compile("\\bnew\\s+Thread\\s*\\(|Thread\\s*\\.\\s*ofPlatform\\s*\\(");
        List<String> violations = new ArrayList<>();
        for (SourceFile file : sources(List.of("bot-engine/src/main/java"))) {
            for (int i = 0; i < file.lines.size(); i++) {
                if (platform.matcher(file.lines.get(i)).find()) {
                    violations.add(file.where(i) + ": " + file.lines.get(i).trim());
                }
            }
        }
        assertThat(violations)
                .as("L-11: a platform thread constructed on a stack that can hold plugin frames "
                        + "captures their protection domains for life (spike 7b); use Thread.ofVirtual()")
                .isEmpty();
    }

    // ------------------------------------------------------------------ source access

    private record SourceFile(String relative, List<String> lines) {
        String where(int index) {
            return relative + ":" + (index + 1);
        }
    }

    private static Path repoRoot() {
        Path root = ROOT_CANDIDATES.stream()
                .filter(candidate -> Files.isDirectory(candidate.resolve("bot-engine/src/main/java")))
                .findFirst()
                .orElse(null);
        assumeTrue(root != null, "repo root not found from " + Path.of("").toAbsolutePath());
        return root;
    }

    private static SourceFile source(String relative) {
        Path file = repoRoot().resolve(relative);
        assertThat(file).as("guarded file moved?").isRegularFile();
        try {
            return new SourceFile(relative, withoutComments(Files.readAllLines(file)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<SourceFile> sources(List<String> trees) {
        Path root = repoRoot();
        List<SourceFile> out = new ArrayList<>();
        for (String tree : trees) {
            Path dir = root.resolve(tree);
            assertThat(dir).as("guarded tree moved?").isDirectory();
            try (Stream<Path> files = Files.walk(dir)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                    String relative = root.relativize(file).toString().replace('\\', '/');
                    out.add(new SourceFile(relative, withoutComments(Files.readAllLines(file))));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return out;
    }

    /** Strips line and block comments; keeps string literals (some patterns read them). */
    static List<String> withoutComments(List<String> raw) {
        List<String> out = new ArrayList<>(raw.size());
        boolean inBlock = false;
        boolean inTextBlock = false;
        for (String line : raw) {
            StringBuilder code = new StringBuilder();
            boolean inString = false;
            boolean inChar = false;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                char next = i + 1 < line.length() ? line.charAt(i + 1) : '\0';
                if (inBlock) {
                    if (c == '*' && next == '/') {
                        inBlock = false;
                        i++;
                    }
                    continue;
                }
                if (inTextBlock) {
                    code.append(c);
                    if (line.startsWith("\"\"\"", i)) {
                        code.append("\"\"");
                        inTextBlock = false;
                        i += 2;
                    }
                    continue;
                }
                if (inString || inChar) {
                    code.append(c);
                    if (c == '\\' && next != '\0') {
                        code.append(next);
                        i++;
                    } else if (inString && c == '"') {
                        inString = false;
                    } else if (inChar && c == '\'') {
                        inChar = false;
                    }
                    continue;
                }
                if (c == '/' && next == '*') {
                    inBlock = true;
                    i++;
                } else if (c == '/' && next == '/') {
                    break;
                } else if (line.startsWith("\"\"\"", i)) {
                    code.append("\"\"\"");
                    inTextBlock = true;
                    i += 2;
                } else {
                    if (c == '"') {
                        inString = true;
                    } else if (c == '\'') {
                        inChar = true;
                    }
                    code.append(c);
                }
            }
            out.add(code.toString());
        }
        return out;
    }
}
