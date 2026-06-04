package com.vingame.bot.domain.bot.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the MDC propagation logic in {@link OutputPrinter#debugOutputPrinter}.
 * <p>
 * The {@code peek} consumer fed into the scenario runs on the per-client
 * {@code netty-ws-message-processor-ws-<userName>} pool, which has no MDC of its
 * own. Without the wrap, every {@code User <name>: ...} log line was MDC-less —
 * that was the dominant source of un-labelled lines in the diagnosis.
 * <p>
 * We exercise the private {@code withMdc} adapter via reflection because it is
 * the unit of behaviour we care about and the public surface (a {@link
 * com.vingame.websocketparser.scenario.Scenario}) does not expose the consumer
 * directly. Same wrap method, same snapshot reference — proves the propagation.
 */
@DisplayName("OutputPrinter MDC propagation")
class OutputPrinterMdcTest {

    private static final Map<String, String> SNAPSHOT = Map.of(
            "botGroupId", "group-out-test",
            "botId", "1",
            "environmentId", "env-out-test",
            "gameType", "BauCua",
            "botUserName", "bot_out"
    );

    @BeforeEach
    void setUp() {
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @SuppressWarnings("unchecked")
    private static Consumer<String> invokeWithMdc(Consumer<String> delegate,
                                                  Map<String, String> snapshot) throws Exception {
        Method m = OutputPrinter.class.getDeclaredMethod(
                "withMdc", Consumer.class, Map.class);
        m.setAccessible(true);
        return (Consumer<String>) m.invoke(null, delegate, snapshot);
    }

    @Test
    @DisplayName("withMdc applies the snapshot inside the delegate Consumer on a fresh thread")
    void withMdcAppliesSnapshotOnFreshThread() throws Exception {
        Map<String, String> capturedInside = new HashMap<>();
        Consumer<String> delegate = s -> capturedInside.putAll(MDC.getCopyOfContextMap());
        Consumer<String> wrapped = invokeWithMdc(delegate, SNAPSHOT);

        CompletableFuture<Map<String, String>> mdcAfter = new CompletableFuture<>();
        Thread t = new Thread(() -> {
            // Fresh thread: starts with empty MDC.
            assertThat(MDC.getCopyOfContextMap()).satisfiesAnyOf(
                    m -> assertThat(m).isNull(),
                    m -> assertThat(m).isEmpty()
            );
            wrapped.accept("User bot_out: hello");
            Map<String, String> after = MDC.getCopyOfContextMap();
            mdcAfter.complete(after == null ? new HashMap<>() : after);
        }, "OutputPrinterMdcTest-fresh");
        t.start();
        t.join(5000);

        // Inside the delegate, the snapshot was visible.
        assertThat(capturedInside).isEqualTo(SNAPSHOT);
        // After the wrapped Consumer returns on a thread that had no MDC, the
        // MDC is cleared (not leaked).
        assertThat(mdcAfter.get(5, TimeUnit.SECONDS)).isEmpty();
    }

    @Test
    @DisplayName("withMdc restores the prior MDC on a thread that already had MDC")
    void withMdcRestoresPriorMdc() throws Exception {
        Map<String, String> outerMdc = Map.of(
                "botGroupId", "outer-group",
                "botUserName", "outer_user"
        );
        Map<String, String> capturedInside = new HashMap<>();
        Consumer<String> delegate = s -> capturedInside.putAll(MDC.getCopyOfContextMap());
        Consumer<String> wrapped = invokeWithMdc(delegate, SNAPSHOT);

        CompletableFuture<Map<String, String>> mdcAfter = new CompletableFuture<>();
        Thread t = new Thread(() -> {
            MDC.setContextMap(outerMdc);
            wrapped.accept("User bot_out: hello");
            Map<String, String> after = MDC.getCopyOfContextMap();
            mdcAfter.complete(after == null ? new HashMap<>() : after);
        }, "OutputPrinterMdcTest-outer");
        t.start();
        t.join(5000);

        // Inside the delegate, the snapshot replaced the outer MDC.
        assertThat(capturedInside).isEqualTo(SNAPSHOT);
        // After the wrapped Consumer returns, the outer MDC is restored verbatim
        // (no leak of snapshot keys, no loss of outer keys).
        assertThat(mdcAfter.get(5, TimeUnit.SECONDS)).isEqualTo(outerMdc);
    }

    @Test
    @DisplayName("withMdc with null snapshot returns the delegate unchanged (no MDC manipulation)")
    void withMdcWithNullSnapshotIsPassthrough() throws Exception {
        Map<String, String> capturedInside = new HashMap<>();
        Consumer<String> delegate = s -> {
            Map<String, String> snap = MDC.getCopyOfContextMap();
            if (snap != null) capturedInside.putAll(snap);
        };
        Consumer<String> wrapped = invokeWithMdc(delegate, null);

        // When the snapshot is null we expect the same Consumer reference back
        // (no wrapping at all). Documented behaviour: "fall through with whatever
        // MDC the calling thread has."
        assertThat(wrapped).isSameAs(delegate);

        CompletableFuture<Void> done = new CompletableFuture<>();
        Thread t = new Thread(() -> {
            MDC.put("preexisting", "value");
            wrapped.accept("User bot_out: hello");
            done.complete(null);
        }, "OutputPrinterMdcTest-null");
        t.start();
        done.get(5, TimeUnit.SECONDS);
        t.join(1000);

        assertThat(capturedInside).containsEntry("preexisting", "value");
    }
}
