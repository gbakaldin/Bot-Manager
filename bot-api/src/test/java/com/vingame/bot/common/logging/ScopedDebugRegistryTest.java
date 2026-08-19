package com.vingame.bot.common.logging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LOG_VOLUME_TIERING AD-11 — the scoped-DEBUG registry, and in particular the properties
 * that make "turn on detail" safe to hand to an operator on a box that has already died of
 * a full disk once: the TTL is mandatory and enforced, the number of simultaneous scopes is
 * capped, and the steady-state read is a single volatile.
 */
@DisplayName("ScopedDebugRegistry (AD-11)")
class ScopedDebugRegistryTest {

    /** Manual clock, so TTL behaviour is asserted without sleeping. */
    private final AtomicLong now = new AtomicLong(1_000_000L);

    private ScopedDebugRegistry registry(int maxScopes) {
        return new ScopedDebugRegistry(maxScopes, now::get);
    }

    @Test
    @DisplayName("an enabled group is enabled until its expiry, and not one millisecond longer")
    void enableExpiresExactlyAtTheDeadline() {
        ScopedDebugRegistry registry = registry(10);

        Optional<Instant> expiry = registry.enable("g1", Duration.ofMinutes(5));

        assertThat(expiry).isPresent();
        assertThat(expiry.get()).isEqualTo(Instant.ofEpochMilli(1_000_000L + 300_000L));
        assertThat(registry.isEnabled("g1")).isTrue();
        assertThat(registry.isAnyEnabled()).isTrue();

        now.set(1_000_000L + 299_999L);
        assertThat(registry.isEnabled("g1")).as("one ms before expiry").isTrue();

        now.set(1_000_000L + 300_000L);
        assertThat(registry.isEnabled("g1")).as("at the expiry instant").isFalse();
        // Expiry is enforced lazily on read as well as by the sweep, so a stopped sweeper
        // can never leave a scope on forever.
        assertThat(registry.isAnyEnabled()).isFalse();
    }

    @Test
    @DisplayName("only the named group is enabled — every other group is untouched")
    void scopeIsPerGroup() {
        ScopedDebugRegistry registry = registry(10);
        registry.enable("g1", Duration.ofMinutes(5));

        assertThat(registry.isEnabled("g1")).isTrue();
        assertThat(registry.isEnabled("g2")).isFalse();
        assertThat(registry.isEnabled(null)).isFalse();
    }

    @Test
    @DisplayName("isAnyEnabled is the fast path: false with no scopes, true the moment one exists")
    void fastPathTracksTheMap() {
        ScopedDebugRegistry registry = registry(10);
        assertThat(registry.isAnyEnabled()).isFalse();
        // The filter short-circuits on this, so a false positive costs a map lookup and a
        // false negative would make the whole feature silently inert.
        assertThat(registry.isEnabled("g1")).isFalse();

        registry.enable("g1", Duration.ofMinutes(5));
        assertThat(registry.isAnyEnabled()).isTrue();

        registry.disable("g1");
        assertThat(registry.isAnyEnabled()).isFalse();
    }

    @Test
    @DisplayName("sweep drops expired scopes and names them, so the expiry can be logged")
    void sweepReturnsExpiredGroups() {
        ScopedDebugRegistry registry = registry(10);
        registry.enable("short", Duration.ofMinutes(1));
        registry.enable("long", Duration.ofMinutes(30));

        assertThat(registry.sweep()).isEmpty();

        now.addAndGet(Duration.ofMinutes(2).toMillis());
        assertThat(registry.sweep()).containsExactly("short");
        assertThat(registry.isEnabled("long")).isTrue();
        assertThat(registry.isAnyEnabled()).isTrue();

        now.addAndGet(Duration.ofMinutes(30).toMillis());
        assertThat(registry.sweep()).containsExactly("long");
        assertThat(registry.isAnyEnabled()).isFalse();
    }

    @Test
    @DisplayName("re-enabling extends the window and never counts against the cap twice")
    void reEnableExtends() {
        ScopedDebugRegistry registry = registry(1);
        registry.enable("g1", Duration.ofMinutes(5));

        Optional<Instant> extended = registry.enable("g1", Duration.ofMinutes(30));

        assertThat(extended).isPresent();
        now.addAndGet(Duration.ofMinutes(10).toMillis());
        assertThat(registry.isEnabled("g1")).as("the longer window won").isTrue();
    }

    @Test
    @DisplayName("a shorter re-enable never shortens an existing window")
    void reEnableNeverShortens() {
        ScopedDebugRegistry registry = registry(10);
        registry.enable("g1", Duration.ofMinutes(30));

        registry.enable("g1", Duration.ofMinutes(1));

        now.addAndGet(Duration.ofMinutes(2).toMillis());
        assertThat(registry.isEnabled("g1"))
                .as("an auto-escalation must not cut short an operator's longer window")
                .isTrue();
    }

    @Nested
    @DisplayName("the limits that stop scoped DEBUG becoming global DEBUG")
    class Limits {

        @Test
        @DisplayName("the concurrent-scope cap refuses further groups rather than throwing")
        void capRefuses() {
            ScopedDebugRegistry registry = registry(2);
            assertThat(registry.enable("g1", Duration.ofMinutes(5))).isPresent();
            assertThat(registry.enable("g2", Duration.ofMinutes(5))).isPresent();

            // Refusal, not an exception: the REST path turns it into a 400 and the
            // auto-escalation path into a WARN. Neither wants a stack trace.
            assertThat(registry.enable("g3", Duration.ofMinutes(5))).isEmpty();
            assertThat(registry.isEnabled("g3")).isFalse();
        }

        @Test
        @DisplayName("an expired-but-unswept entry does not hold a slot against the cap")
        void capReclaimsExpiredEntries() {
            ScopedDebugRegistry registry = registry(1);
            registry.enable("g1", Duration.ofMinutes(1));

            now.addAndGet(Duration.ofMinutes(2).toMillis());

            assertThat(registry.enable("g2", Duration.ofMinutes(5)))
                    .as("the 30s sweeper is a backstop, not the only reclaim path")
                    .isPresent();
        }

        @Test
        @DisplayName("a TTL above the hard maximum is clamped, never honoured")
        void ttlIsClamped() {
            ScopedDebugRegistry registry = registry(10);

            Optional<Instant> expiry = registry.enable("g1", Duration.ofDays(7));

            assertThat(expiry).isPresent();
            assertThat(expiry.get())
                    .isEqualTo(Instant.ofEpochMilli(1_000_000L + ScopedDebugRegistry.MAX_TTL.toMillis()));
        }

        @Test
        @DisplayName("a non-positive TTL and a blank group are refused — there is no permanent form")
        void refusesNonsense() {
            ScopedDebugRegistry registry = registry(10);

            assertThat(registry.enable("g1", Duration.ZERO)).isEmpty();
            assertThat(registry.enable("g1", Duration.ofMinutes(-5))).isEmpty();
            assertThat(registry.enable("  ", Duration.ofMinutes(5))).isEmpty();
            assertThat(registry.enable(null, Duration.ofMinutes(5))).isEmpty();
            assertThat(registry.isAnyEnabled()).isFalse();
        }
    }

    @Test
    @DisplayName("activeScopes lists live windows soonest-first and hides expired ones")
    void activeScopesReportsWhatIsActuallyInEffect() {
        ScopedDebugRegistry registry = registry(10);
        registry.enable("later", Duration.ofMinutes(30));
        registry.enable("sooner", Duration.ofMinutes(5));
        registry.enable("gone", Duration.ofMinutes(1));

        now.addAndGet(Duration.ofMinutes(2).toMillis());

        assertThat(registry.activeScopes()).containsExactly(
                java.util.Map.entry("sooner", Instant.ofEpochMilli(1_000_000L + 300_000L)),
                java.util.Map.entry("later", Instant.ofEpochMilli(1_000_000L + 1_800_000L)));
    }

    @Test
    @DisplayName("disable reports whether there was anything to disable")
    void disableReportsHit() {
        ScopedDebugRegistry registry = registry(10);
        registry.enable("g1", Duration.ofMinutes(5));

        assertThat(registry.disable("g1")).isTrue();
        assertThat(registry.disable("g1")).isFalse();
        assertThat(registry.disable(null)).isFalse();
    }
}
