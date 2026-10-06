package com.vingame.bot.domain.botgroup.validation;

import com.vingame.bot.common.exception.BadRequestException;
import com.vingame.bot.domain.bot.core.crash.CrashStakes;
import com.vingame.bot.domain.botgroup.model.BotGroup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * AVIATOR_BOT QA: {@link CrashConfigValidator} edges beyond {@code CrashConfigValidatorTest}
 * — step versus window, the unset sentinels, several violations at once, a huge window,
 * and a property sweep that the validator and the bot's own ladder agree on every config:
 * a group the validator accepts always has at least one stake, and one it rejects for an
 * empty ladder really has none (AD-6, AD-15).
 */
@DisplayName("CrashConfigValidator edges (QA)")
class CrashConfigValidatorEdgeTest {

    private final CrashConfigValidator validator = new CrashConfigValidator();

    private static BotGroup group(long min, long max, long step) {
        return BotGroup.builder().minBet(min).maxBet(max).betIncrement(step).build();
    }

    @Test
    @DisplayName("betIncrement above maxBet is a 400 naming that step")
    void stepAboveMax() {
        assertThatThrownBy(() -> validator.validate(group(0, 40_000, 50_000)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("no stake fits")
                .hasMessageContaining("step 50000")
                .hasMessageContaining("[0, 40000]");
    }

    @Test
    @DisplayName("minBet == maxBet passes on a multiple of the step and is a 400 off it")
    void pointWindow() {
        assertThatCode(() -> validator.validate(group(50_000, 50_000, 5_000))).doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.validate(group(52_000, 52_000, 5_000)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("no stake fits");
    }

    @Test
    @DisplayName("a negative maxBet is 'unset': accepted, legacy window — and minBet above it is then silently not honoured")
    void unsetMax() {
        assertThatCode(() -> validator.validate(group(0, -1, 0))).doesNotThrowAnyException();
        // Pinned, not endorsed: AD-6 says maxBet <= 0 means the legacy window, so a minBet of
        // 500k is accepted and the bot bets 20k..200k. Reported in qa.md as an observation.
        assertThatCode(() -> validator.validate(group(500_000, 0, 0))).doesNotThrowAnyException();
        assertThat(CrashStakes.ladder(500_000, 0, 0).get(0)).isEqualTo(20_000L);
    }

    @Test
    @DisplayName("several violations are all reported in one 400, and the empty-ladder line is suppressed")
    void allViolationsAtOnce() {
        Throwable t = catchThrowable(() -> validator.validate(group(-5, -10, -1)));
        assertThat(t).isInstanceOf(BadRequestException.class)
                .hasMessageContaining("minBet (-5) must be >= 0")
                .hasMessageContaining("betIncrement (-1) must be >= 0")
                .hasMessageContaining("; ")
                .hasMessageNotContaining("no stake fits");

        assertThatThrownBy(() -> validator.validate(group(300_000, 200_000, -1)))
                .hasMessageContaining("betIncrement (-1)")
                .hasMessageContaining("minBet (300000) must be <= maxBet (200000)")
                .as("the window [300000, 200000] is also empty, but that restates min > max")
                .hasMessageNotContaining("no stake fits");
    }

    @Test
    @DisplayName("a huge window (maxBet 1e15, step 1) validates in well under a second — nothing is materialised")
    void hugeWindowIsCheap() {
        assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> validator.validate(group(1, 1_000_000_000_000_000L, 1)));
    }

    @Test
    @DisplayName("property: over 5000 random configs the validator accepts exactly the sane ones with a non-empty ladder")
    void agreesWithTheLadder() {
        Random r = new Random(119L);
        for (int i = 0; i < 5_000; i++) {
            long min = r.nextInt(8) == 0 ? -r.nextInt(1_000) : r.nextInt(300_000);
            long max = r.nextInt(8) == 0 ? -r.nextInt(10) : r.nextInt(300_000);
            long step = r.nextInt(8) == 0 ? -r.nextInt(10) : r.nextInt(80_000);
            boolean sane = min >= 0 && step >= 0 && (max <= 0 || min <= max);
            boolean expectAccept = sane && !CrashStakes.ladder(min, max, step).isEmpty();

            Throwable t = catchThrowable(() -> validator.validate(group(min, max, step)));
            String ctx = "min=" + min + " max=" + max + " step=" + step;
            if (expectAccept) {
                assertThat(t).as(ctx).isNull();
            } else {
                assertThat(t).as(ctx).isInstanceOf(BadRequestException.class);
            }
        }
    }
}
