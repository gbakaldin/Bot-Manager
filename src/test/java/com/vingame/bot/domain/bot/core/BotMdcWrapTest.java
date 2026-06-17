package com.vingame.bot.domain.bot.core;

import com.vingame.websocketparser.scenario.Scenario;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link Bot}'s MDC wrap helpers: {@code mdcWrap}, {@code mdcCall},
 * {@code mdcSupplier}, {@code mdcConsumer}.
 * <p>
 * Each helper must (Architecture Decision 7 in docs/plans/LOGGING_PIPELINE_FIX.md):
 *  1. Stash the caller's current MDC.
 *  2. Apply the bot's {@code mdcSnapshot} (no-op if snapshot is null).
 *  3. Run the wrapped action with the snapshot visible.
 *  4. In finally: restore the stashed MDC, or {@code clear()} if the stash was null.
 * <p>
 * The wrap must also propagate exceptions and remain re-entrant when the
 * outer thread already has a different MDC populated.
 */
@DisplayName("Bot MDC wrap helpers")
class BotMdcWrapTest {

    private static final Map<String, String> SNAPSHOT = Map.of(
            "botGroupId", "group-A",
            "botId", "1",
            "environmentId", "env-A",
            "gameType", "BauCua",
            "botUserName", "bot_A"
    );

    private TestBot bot;

    @BeforeEach
    void setUp() {
        bot = new TestBot();
        bot.mdcSnapshot = new HashMap<>(SNAPSHOT);
        // Each test starts with a clean MDC on this thread; helpers will manage MDC
        // explicitly for the worker threads they spawn.
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    /* ---------------- mdcWrap(Runnable) ---------------- */

    @Nested
    @DisplayName("mdcWrap(Runnable)")
    class MdcWrapRunnable {

        @Test
        @DisplayName("On thread with empty MDC: snapshot visible inside, MDC cleared after")
        void appliesSnapshotAndClearsAfterOnEmptyMdcThread() throws Exception {
            AtomicReference<Map<String, String>> inside = new AtomicReference<>();
            AtomicReference<Map<String, String>> after = new AtomicReference<>();

            Runnable wrapped = bot.mdcWrap(() -> inside.set(MDC.getCopyOfContextMap()));

            runOnFreshThread(() -> {
                // precondition: fresh thread has no MDC entries
                assertEmptyMdc(MDC.getCopyOfContextMap());
                wrapped.run();
                after.set(MDC.getCopyOfContextMap());
            });

            assertEquals(SNAPSHOT, inside.get());
            assertEmptyMdc(after.get());
        }

        @Test
        @DisplayName("On thread with existing MDC: snapshot replaces it, original restored after")
        void restoresOuterMdcAfterRun() throws Exception {
            AtomicReference<Map<String, String>> inside = new AtomicReference<>();
            AtomicReference<Map<String, String>> after = new AtomicReference<>();

            Runnable wrapped = bot.mdcWrap(() -> inside.set(MDC.getCopyOfContextMap()));

            runOnFreshThread(() -> {
                MDC.put("botGroupId", "other-group");
                MDC.put("botUserName", "other-bot");
                wrapped.run();
                after.set(MDC.getCopyOfContextMap());
            });

            assertEquals(SNAPSHOT, inside.get());
            assertEquals("other-group", after.get().get("botGroupId"));
            assertEquals("other-bot", after.get().get("botUserName"));
            assertEquals(2, after.get().size());
        }

        @Test
        @DisplayName("When mdcSnapshot is null, outer MDC is left untouched throughout")
        void noOpWhenSnapshotIsNull() throws Exception {
            bot.mdcSnapshot = null;

            AtomicReference<Map<String, String>> inside = new AtomicReference<>();
            AtomicReference<Map<String, String>> after = new AtomicReference<>();

            Runnable wrapped = bot.mdcWrap(() -> inside.set(MDC.getCopyOfContextMap()));

            runOnFreshThread(() -> {
                MDC.put("k", "v");
                wrapped.run();
                after.set(MDC.getCopyOfContextMap());
            });

            assertEquals("v", inside.get().get("k"));
            assertEquals(1, inside.get().size());
            assertEquals("v", after.get().get("k"));
        }

        @Test
        @DisplayName("Exceptions propagate; outer MDC is still restored")
        void propagatesExceptionAndRestoresMdc() throws Exception {
            Runnable wrapped = bot.mdcWrap(() -> {
                throw new IllegalStateException("boom");
            });

            AtomicReference<Map<String, String>> after = new AtomicReference<>();
            AtomicReference<Throwable> thrown = new AtomicReference<>();

            runOnFreshThread(() -> {
                MDC.put("botGroupId", "outer");
                try {
                    wrapped.run();
                } catch (Throwable t) {
                    thrown.set(t);
                }
                after.set(MDC.getCopyOfContextMap());
            });

            assertTrue(thrown.get() instanceof IllegalStateException);
            assertEquals("boom", thrown.get().getMessage());
            assertEquals("outer", after.get().get("botGroupId"));
            assertEquals(1, after.get().size());
        }
    }

    /* ---------------- mdcCall(Callable) ---------------- */

    @Nested
    @DisplayName("mdcCall(Callable)")
    class MdcCallCallable {

        @Test
        @DisplayName("On thread with empty MDC: snapshot visible, value returned, MDC cleared after")
        void appliesSnapshotAndClearsAfterOnEmptyMdcThread() throws Exception {
            AtomicReference<Map<String, String>> inside = new AtomicReference<>();
            AtomicReference<Map<String, String>> after = new AtomicReference<>();
            AtomicReference<String> result = new AtomicReference<>();

            Callable<String> wrapped = bot.mdcCall(() -> {
                inside.set(MDC.getCopyOfContextMap());
                return "ok";
            });

            runOnFreshThread(() -> {
                assertEmptyMdc(MDC.getCopyOfContextMap());
                try {
                    result.set(wrapped.call());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                after.set(MDC.getCopyOfContextMap());
            });

            assertEquals("ok", result.get());
            assertEquals(SNAPSHOT, inside.get());
            assertEmptyMdc(after.get());
        }

        @Test
        @DisplayName("On thread with existing MDC: snapshot replaces it, original restored after")
        void restoresOuterMdcAfterCall() throws Exception {
            AtomicReference<Map<String, String>> inside = new AtomicReference<>();
            AtomicReference<Map<String, String>> after = new AtomicReference<>();

            Callable<Integer> wrapped = bot.mdcCall(() -> {
                inside.set(MDC.getCopyOfContextMap());
                return 42;
            });

            runOnFreshThread(() -> {
                MDC.put("botGroupId", "other-group");
                try {
                    assertEquals(42, wrapped.call());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                after.set(MDC.getCopyOfContextMap());
            });

            assertEquals(SNAPSHOT, inside.get());
            assertEquals("other-group", after.get().get("botGroupId"));
            assertEquals(1, after.get().size());
        }

        @Test
        @DisplayName("Checked exception propagates; outer MDC is still restored")
        void propagatesCheckedExceptionAndRestoresMdc() throws Exception {
            Callable<Void> wrapped = bot.mdcCall(() -> {
                throw new java.io.IOException("io fail");
            });

            AtomicReference<Map<String, String>> after = new AtomicReference<>();
            AtomicReference<Throwable> thrown = new AtomicReference<>();

            runOnFreshThread(() -> {
                MDC.put("botGroupId", "outer");
                try {
                    wrapped.call();
                } catch (Throwable t) {
                    thrown.set(t);
                }
                after.set(MDC.getCopyOfContextMap());
            });

            assertTrue(thrown.get() instanceof java.io.IOException);
            assertEquals("outer", after.get().get("botGroupId"));
        }
    }

    /* ---------------- mdcSupplier(Supplier) ---------------- */

    @Nested
    @DisplayName("mdcSupplier(Supplier)")
    class MdcSupplierSupplier {

        @Test
        @DisplayName("On thread with empty MDC: snapshot visible, value returned, MDC cleared after")
        void appliesSnapshotAndClearsAfterOnEmptyMdcThread() throws Exception {
            AtomicReference<Map<String, String>> inside = new AtomicReference<>();
            AtomicReference<Map<String, String>> after = new AtomicReference<>();
            Object sentinel = new Object();

            Supplier<Object> wrapped = bot.mdcSupplier(() -> {
                inside.set(MDC.getCopyOfContextMap());
                return sentinel;
            });

            AtomicReference<Object> result = new AtomicReference<>();
            runOnFreshThread(() -> {
                assertEmptyMdc(MDC.getCopyOfContextMap());
                result.set(wrapped.get());
                after.set(MDC.getCopyOfContextMap());
            });

            assertSame(sentinel, result.get());
            assertEquals(SNAPSHOT, inside.get());
            assertEmptyMdc(after.get());
        }

        @Test
        @DisplayName("On thread with existing MDC: snapshot replaces it, original restored after")
        void restoresOuterMdcAfterGet() throws Exception {
            AtomicReference<Map<String, String>> inside = new AtomicReference<>();
            AtomicReference<Map<String, String>> after = new AtomicReference<>();

            Supplier<String> wrapped = bot.mdcSupplier(() -> {
                inside.set(MDC.getCopyOfContextMap());
                return "v";
            });

            runOnFreshThread(() -> {
                MDC.put("botGroupId", "outer-grp");
                MDC.put("environmentId", "outer-env");
                wrapped.get();
                after.set(MDC.getCopyOfContextMap());
            });

            assertEquals(SNAPSHOT, inside.get());
            assertEquals("outer-grp", after.get().get("botGroupId"));
            assertEquals("outer-env", after.get().get("environmentId"));
            assertEquals(2, after.get().size());
        }
    }

    /* ---------------- mdcConsumer(Consumer) ---------------- */

    @Nested
    @DisplayName("mdcConsumer(Consumer)")
    class MdcConsumerConsumer {

        @Test
        @DisplayName("On thread with empty MDC: snapshot visible, value consumed, MDC cleared after")
        void appliesSnapshotAndClearsAfterOnEmptyMdcThread() throws Exception {
            AtomicReference<Map<String, String>> inside = new AtomicReference<>();
            AtomicReference<Map<String, String>> after = new AtomicReference<>();
            AtomicReference<String> consumed = new AtomicReference<>();

            Consumer<String> wrapped = bot.mdcConsumer(s -> {
                inside.set(MDC.getCopyOfContextMap());
                consumed.set(s);
            });

            runOnFreshThread(() -> {
                assertEmptyMdc(MDC.getCopyOfContextMap());
                wrapped.accept("hello");
                after.set(MDC.getCopyOfContextMap());
            });

            assertEquals("hello", consumed.get());
            assertEquals(SNAPSHOT, inside.get());
            assertEmptyMdc(after.get());
        }

        @Test
        @DisplayName("On thread with existing MDC: snapshot replaces it, original restored after")
        void restoresOuterMdcAfterAccept() throws Exception {
            AtomicReference<Map<String, String>> inside = new AtomicReference<>();
            AtomicReference<Map<String, String>> after = new AtomicReference<>();

            Consumer<Integer> wrapped = bot.mdcConsumer(i -> inside.set(MDC.getCopyOfContextMap()));

            runOnFreshThread(() -> {
                MDC.put("botGroupId", "outer");
                wrapped.accept(7);
                after.set(MDC.getCopyOfContextMap());
            });

            assertEquals(SNAPSHOT, inside.get());
            assertEquals("outer", after.get().get("botGroupId"));
            assertEquals(1, after.get().size());
        }
    }

    /* ---------------- helpers ---------------- */

    /**
     * SLF4J's MDC adapter may return either {@code null} or an empty map for a
     * thread with no MDC entries (and the same after {@code MDC.clear()}).
     * Either is acceptable — both mean "no diagnostic context."
     */
    private static void assertEmptyMdc(Map<String, String> mdc) {
        assertTrue(mdc == null || mdc.isEmpty(),
                "expected empty MDC but was: " + mdc);
    }

    /**
     * Run the given action on a freshly spawned platform thread so that MDC starts
     * empty (or is whatever the action sets before invoking the wrap). Propagates
     * the first uncaught Throwable to the test thread.
     */
    private static void runOnFreshThread(Runnable action) throws InterruptedException,
            ExecutionException, TimeoutException {
        CompletableFuture<Void> done = new CompletableFuture<>();
        Thread t = new Thread(() -> {
            try {
                action.run();
                done.complete(null);
            } catch (Throwable th) {
                done.completeExceptionally(th);
            }
        }, "BotMdcWrapTest-worker");
        t.start();
        try {
            done.get(5, TimeUnit.SECONDS);
        } finally {
            t.join(1000);
        }
    }

    /**
     * Concrete {@link Bot} with no-op abstract methods. Used only to access the
     * MDC wrap helpers on a real instance whose {@code mdcSnapshot} we control
     * directly via the protected field.
     */
    static class TestBot extends Bot {
        @Override protected void initializeSubclass() {}
        @Override protected Scenario botBehaviorScenario() { return null; }
        @Override protected void onStart() {}
    }
}
