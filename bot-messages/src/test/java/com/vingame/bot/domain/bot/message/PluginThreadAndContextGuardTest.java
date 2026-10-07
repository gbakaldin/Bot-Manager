package com.vingame.bot.domain.bot.message;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PLUGIN_HOT_RELOAD_3_4 <b>L-6</b> and <b>L-11</b> for {@code bot-messages}: plugin code runs in a
 * classloader that step 6 must be able to release, so it may leave nothing behind on a
 * thread it does not own.
 * <ul>
 *   <li><b>L-11 — no threads or executors.</b> A platform thread constructed under a plugin
 *       frame captures the plugin loader in its inherited access-control context for life
 *       (spike 7b), and the common {@code ForkJoinPool} starts its workers lazily on
 *       whichever thread submits first. So: no {@code new Thread}, {@code Thread.of*},
 *       {@code Executors}, thread pools, {@code ThreadFactory}, {@code Timer},
 *       {@code ForkJoinPool}, {@code CompletableFuture.*Async} or parallel streams. (A
 *       virtual thread would be allowed by the rule, but no plugin needs one.)</li>
 *   <li><b>L-6 — no ThreadLocals, String-only MDC.</b> A plugin {@code ThreadLocal} value on
 *       an engine-owned long-lived thread pins the loader through the thread's map (spike
 *       4a). MDC with {@code String} values is harmless (4d), so an MDC write is allowed
 *       only as {@code MDC.put("literal", "literal")}; anything else must be reviewed
 *       against L-6 and this guard widened deliberately.</li>
 * </ul>
 * This module has the same guard as its sibling plugin module. Syntactic, over
 * {@code src/main/java} with comments stripped.
 */
@DisplayName("L-6 / L-11: plugin code creates no threads and leaves no thread state (bot-messages)")
class PluginThreadAndContextGuardTest {

    private static final Map<String, Pattern> FORBIDDEN = Map.ofEntries(
            Map.entry("new Thread", Pattern.compile("\\bnew\\s+Thread\\s*\\(")),
            Map.entry("Thread.of*", Pattern.compile("\\bThread\\s*\\.\\s*of(Platform|Virtual)\\b")),
            Map.entry("Thread.startVirtualThread", Pattern.compile("\\bThread\\s*\\.\\s*startVirtualThread\\b")),
            Map.entry("Executors", Pattern.compile("\\bExecutors\\s*\\.")),
            Map.entry("thread pool", Pattern.compile("\\b(ThreadPoolExecutor|ScheduledThreadPoolExecutor|ForkJoinPool)\\b")),
            Map.entry("ThreadFactory", Pattern.compile("\\bThreadFactory\\b")),
            Map.entry("Timer", Pattern.compile("\\bnew\\s+Timer\\s*\\(")),
            Map.entry("CompletableFuture async", Pattern.compile("\\bCompletableFuture\\s*\\.\\s*(run|supply)Async\\b")),
            Map.entry("parallel stream", Pattern.compile("\\.\\s*parallelStream\\s*\\(|\\.\\s*parallel\\s*\\(\\s*\\)")),
            Map.entry("ThreadLocal", Pattern.compile("\\b(Inheritable)?ThreadLocal\\b")),
            Map.entry("ThreadContext write", Pattern.compile("\\bThreadContext\\s*\\.\\s*(put|putAll|push)\\b")),
            Map.entry("MDC map write", Pattern.compile("\\bMDC\\s*\\.\\s*(setContextMap|putCloseable|pushByKey)\\b")));

    /** {@code MDC.put("key", "value")} with two string literals: the one MDC write L-6 admits unreviewed. */
    private static final Pattern MDC_PUT = Pattern.compile("\\bMDC\\s*\\.\\s*put\\s*\\(");
    private static final Pattern MDC_PUT_LITERALS = Pattern.compile(
            "\\bMDC\\s*\\.\\s*put\\s*\\(\\s*\"[^\"]*\"\\s*,\\s*\"[^\"]*\"\\s*\\)");

    @Test
    @DisplayName("no thread, executor, ThreadLocal or non-literal MDC write in plugin main code")
    void pluginCodeCreatesNoThreadsAndLeavesNoThreadState() throws IOException {
        Path main = Path.of("src/main/java");
        assertThat(main).as("run from the module directory (surefire's default)").isDirectory();
        List<String> violations = new ArrayList<>();
        int scanned = 0;
        try (Stream<Path> files = Files.walk(main)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                scanned++;
                List<String> lines = withoutComments(Files.readAllLines(file));
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    for (Map.Entry<String, Pattern> rule : FORBIDDEN.entrySet()) {
                        if (rule.getValue().matcher(line).find()) {
                            violations.add(rule.getKey() + " at " + file + ":" + (i + 1) + ": " + line.trim());
                        }
                    }
                    if (MDC_PUT.matcher(line).find() && !MDC_PUT_LITERALS.matcher(line).find()) {
                        violations.add("non-literal MDC.put at " + file + ":" + (i + 1) + ": " + line.trim());
                    }
                }
            }
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        assertThat(violations)
                .as("L-6 / L-11: plugin code must not create threads or leave thread-bound state "
                        + "(PLUGIN_HOT_RELOAD_3_4 D-14, spike rules 5 and 7)")
                .isEmpty();
        assertThat(scanned).as("the guard scanned something").isGreaterThan(5);
    }

    /** Strips line and block comments; keeps string literals (the MDC rule reads them). */
    static List<String> withoutComments(List<String> raw) {
        List<String> out = new ArrayList<>(raw.size());
        boolean inBlock = false;
        for (String line : raw) {
            StringBuilder code = new StringBuilder();
            boolean inString = false;
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
                if (inString) {
                    code.append(c);
                    if (c == '\\' && next != '\0') {
                        code.append(next);
                        i++;
                    } else if (c == '"') {
                        inString = false;
                    }
                    continue;
                }
                if (c == '/' && next == '*') {
                    inBlock = true;
                    i++;
                } else if (c == '/' && next == '/') {
                    break;
                } else {
                    if (c == '"') {
                        inString = true;
                    }
                    code.append(c);
                }
            }
            out.add(code.toString());
        }
        return out;
    }
}
