package com.vingame.bot.domain.botgroup.service;

import com.vingame.bot.domain.botgroup.model.StartOrigin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link StartAttemptRegistry} — the three properties the async start depends on
 * (GATEWAY_REQUEST_BUDGET Phase 2): {@code begin} is idempotent while an attempt is open,
 * {@code finish} retains what happened, and {@code cancel} is visible to the build.
 */
@DisplayName("StartAttemptRegistry")
class StartAttemptRegistryTest {

    private final StartAttemptRegistry registry = new StartAttemptRegistry();

    @Nested
    @DisplayName("begin")
    class Begin {

        @Test
        @DisplayName("is idempotent while an attempt is open — a double /start submits one task")
        void beginIsIdempotentWhileOpen() {
            assertThat(registry.begin("g1", StartOrigin.REST)).isTrue();

            assertThat(registry.begin("g1", StartOrigin.SCHEDULE))
                    .as("the second caller must learn it lost, or two builds race the same group")
                    .isFalse();
            assertThat(registry.origin("g1")).contains(StartOrigin.REST);
            assertThat(registry.isOpen("g1")).isTrue();
        }

        @Test
        @DisplayName("succeeds again once the attempt has finished")
        void beginSucceedsAfterFinish() {
            registry.begin("g1", StartOrigin.REST);
            registry.finish("g1", null);

            assertThat(registry.isOpen("g1")).isFalse();
            assertThat(registry.begin("g1", StartOrigin.STARTUP)).isTrue();
            assertThat(registry.origin("g1")).contains(StartOrigin.STARTUP);
        }

        @Test
        @DisplayName("a new attempt drops the previous attempt's progress and error")
        void beginSupersedesTheRetainedAttempt() {
            registry.begin("g1", StartOrigin.REST);
            registry.botUp("g1");
            registry.finish("g1", new IllegalStateException("boom"));
            assertThat(registry.lastError("g1")).contains("boom");

            registry.begin("g1", StartOrigin.REST);

            assertThat(registry.lastError("g1"))
                    .as("an hour-old failure must not be read as this attempt's outcome")
                    .isNull();
            assertThat(registry.botsUp("g1")).isZero();
        }

        @Test
        @DisplayName("groups are independent")
        void groupsAreIndependent() {
            registry.begin("g1", StartOrigin.REST);

            assertThat(registry.begin("g2", StartOrigin.REST)).isTrue();
            assertThat(registry.isOpen("g2")).isTrue();
        }
    }

    @Nested
    @DisplayName("progress")
    class Progress {

        @Test
        @DisplayName("counts bots up and failed against the open attempt")
        void countsBots() {
            registry.begin("g1", StartOrigin.REST);
            registry.botUp("g1");
            registry.botUp("g1");
            registry.botFailed("g1");

            assertThat(registry.botsUp("g1")).isEqualTo(2);
            assertThat(registry.botsFailed("g1")).isEqualTo(1);
        }

        @Test
        @DisplayName("is a no-op with nothing open, so the creation task never needs a null guard")
        void noOpWhenNothingIsOpen() {
            registry.botUp("nope");
            registry.botFailed("nope");
            registry.progress("nope", StartAttemptRegistry.Phase.BUILDING);

            assertThat(registry.botsUp("nope")).isNull();
            assertThat(registry.isOpen("nope")).isFalse();
        }

        @Test
        @DisplayName("returns null counts for a group never started in this JVM")
        void nullForAnUnknownGroup() {
            assertThat(registry.botsUp("unknown")).isNull();
            assertThat(registry.botsFailed("unknown")).isNull();
            assertThat(registry.lastError("unknown")).isNull();
            assertThat(registry.origin("unknown")).isEmpty();
        }
    }

    @Nested
    @DisplayName("finish")
    class Finish {

        @Test
        @DisplayName("retains the counts and the error for /status")
        void retainsLastError() {
            registry.begin("g1", StartOrigin.REST);
            registry.botUp("g1");
            registry.finish("g1", new IllegalStateException("Restart of group g1 produced 0/50 bots"));

            assertThat(registry.isOpen("g1")).isFalse();
            assertThat(registry.botsUp("g1")).isEqualTo(1);
            assertThat(registry.lastError("g1")).contains("produced 0/50 bots");
        }

        @Test
        @DisplayName("a successful finish retains progress and no error")
        void successRetainsNoError() {
            registry.begin("g1", StartOrigin.REST);
            registry.botUp("g1");
            registry.finish("g1", null);

            assertThat(registry.botsUp("g1")).isEqualTo(1);
            assertThat(registry.lastError("g1")).isNull();
        }

        @Test
        @DisplayName("truncates a pathological message rather than putting it all on the wire")
        void truncatesLongErrors() {
            registry.begin("g1", StartOrigin.REST);
            registry.finish("g1", new IllegalStateException("x".repeat(5_000)));

            assertThat(registry.lastError("g1")).hasSizeLessThan(600);
        }

        @Test
        @DisplayName("is a no-op with nothing open")
        void noOpWhenNothingOpen() {
            registry.finish("g1", new IllegalStateException("boom"));

            assertThat(registry.lastError("g1")).isNull();
        }
    }

    @Nested
    @DisplayName("cancel")
    class Cancel {

        @Test
        @DisplayName("flips the flag the build polls, and says whether there was anything to cancel")
        void cancelFlipsTheFlag() {
            assertThat(registry.cancel("g1"))
                    .as("nothing open ⇒ nothing cancelled, and the caller can say so")
                    .isFalse();

            registry.begin("g1", StartOrigin.REST);
            assertThat(registry.isCancelled("g1")).isFalse();

            assertThat(registry.cancel("g1")).isTrue();
            assertThat(registry.isCancelled("g1")).isTrue();
        }

        @Test
        @DisplayName("leaves the attempt open — the build closes it after it has unwound")
        void cancelDoesNotCloseTheAttempt() {
            registry.begin("g1", StartOrigin.REST);
            registry.cancel("g1");

            assertThat(registry.isOpen("g1"))
                    .as("the group must keep reporting STARTING until the teardown completes")
                    .isTrue();
            assertThat(registry.describe("g1")).contains("cancelled");
        }

        @Test
        @DisplayName("cancellation does not survive into the next attempt")
        void cancellationDoesNotLeak() {
            registry.begin("g1", StartOrigin.REST);
            registry.cancel("g1");
            registry.finish("g1", null);

            registry.begin("g1", StartOrigin.REST);

            assertThat(registry.isCancelled("g1")).isFalse();
        }

        @Test
        @DisplayName("only the named group is cancelled")
        void cancelIsPerGroup() {
            registry.begin("g1", StartOrigin.REST);
            registry.begin("g2", StartOrigin.REST);

            registry.cancel("g1");

            assertThat(registry.isCancelled("g1")).isTrue();
            assertThat(registry.isCancelled("g2")).isFalse();
        }
    }

    @Nested
    @DisplayName("clear")
    class Clear {

        @Test
        @DisplayName("forgets the open attempt and the retained one (the stop/delete path)")
        void clearForgetsEverything() {
            registry.begin("g1", StartOrigin.REST);
            registry.botUp("g1");
            registry.finish("g1", new IllegalStateException("boom"));

            registry.clear("g1");

            assertThat(registry.isOpen("g1")).isFalse();
            assertThat(registry.botsUp("g1")).isNull();
            assertThat(registry.lastError("g1")).isNull();
        }
    }

    @Nested
    @DisplayName("describe")
    class Describe {

        @Test
        @DisplayName("names the origin, phase and counts of the attempt in flight")
        void describesTheOpenAttempt() {
            registry.begin("g1", StartOrigin.SCHEDULE);
            registry.progress("g1", StartAttemptRegistry.Phase.BUILDING);
            registry.botUp("g1");

            assertThat(registry.describe("g1"))
                    .contains("SCHEDULE")
                    .contains("BUILDING")
                    .contains("1 up");
        }

        @Test
        @DisplayName("says 'none' rather than throwing when nothing is open")
        void describesNothing() {
            assertThat(registry.describe("g1")).isEqualTo("none");
        }
    }
}
