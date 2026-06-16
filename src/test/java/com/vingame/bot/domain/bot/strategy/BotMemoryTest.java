package com.vingame.bot.domain.bot.strategy;

import com.vingame.bot.domain.game.model.Game;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 2 unit coverage for the factual rolling-history holder added by
 * {@code docs/plans/BETTING_STRATEGIES.md}. Tests pin:
 * <ul>
 *   <li>Bounded FIFO buffer eviction at capacity (default 50, overridable).</li>
 *   <li>sessionId-keyed bet→result correlation: matched IDs accumulate, mismatched
 *       IDs are dropped with a WARN log line and an empty {@code betsByOption}.</li>
 *   <li>{@code recordBetSent} accumulates by option id (same option twice → sum).</li>
 *   <li>{@code completeRound} computes {@code balanceDelta = payout - sum(bets)}.</li>
 *   <li>Defensive snapshots return immutable copies.</li>
 * </ul>
 */
@DisplayName("BotMemory")
class BotMemoryTest {

    private Game game;
    private CapturingAppender appender;
    private Logger memoryLogger;

    @BeforeEach
    void setUp() {
        Map<Integer, Integer> affinities = new LinkedHashMap<>();
        for (int i = 0; i < 6; i++) affinities.put(i, 1);
        game = Game.builder()
                .id("g1").name("BauCua").pluginName("BauCua")
                .offset(2000).optionAffinities(affinities).build();

        // Attach a Log4j2 appender to the BotMemory logger so the WARN-line
        // assertion in MismatchWarnsAtWarn can read events directly. WARN
        // events on sessionId mismatch are documented in
        // docs/plans/BETTING_STRATEGIES.md, Architecture Decision 14.
        memoryLogger = (Logger) LogManager.getLogger(BotMemory.class);
        appender = new CapturingAppender("BotMemoryTestAppender");
        appender.start();
        memoryLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        if (memoryLogger != null && appender != null) {
            memoryLogger.removeAppender(appender);
            appender.stop();
        }
    }

    /* ----- construction / invariants ----- */

    @Nested
    @DisplayName("construction")
    class Construction {

        @Test
        @DisplayName("Default capacity is 50; getCapacity reflects it")
        void defaultCapacity() {
            BotMemory mem = new BotMemory(game);
            assertThat(mem.getCapacity()).isEqualTo(50);
        }

        @Test
        @DisplayName("Custom capacity is honored")
        void customCapacity() {
            BotMemory mem = new BotMemory(game, 7);
            assertThat(mem.getCapacity()).isEqualTo(7);
        }

        @Test
        @DisplayName("Capacity <= 0 throws IllegalArgumentException")
        void invalidCapacity() {
            assertThatThrownBy(() -> new BotMemory(game, 0))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new BotMemory(game, -1))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Fresh memory has empty buffers and zero-sentinel current round")
        void freshIsEmpty() {
            BotMemory mem = new BotMemory(game);
            assertThat(mem.snapshotLastResults()).isEmpty();
            assertThat(mem.snapshotGlobalRecentWins()).isEmpty();
            assertThat(mem.getCurrentRound().getSessionId()).isZero();
            assertThat(mem.snapshotCurrentRoundBets()).isEmpty();
        }
    }

    /* ----- bounded buffers ----- */

    @Nested
    @DisplayName("bounded rolling buffers")
    class BoundedBuffers {

        @Test
        @DisplayName("lastResults capacity = 50: pushing a 51st evicts the oldest (FIFO)")
        void lastResultsCapacity50() {
            BotMemory mem = new BotMemory(game, 50);
            // Push 51 results, sessionIds 1..51
            for (long sid = 1; sid <= 51; sid++) {
                mem.beginRound(sid, 1_000_000L);
                mem.completeRound(sid, Optional.empty(), 0L);
            }
            List<RoundResult> results = mem.snapshotLastResults();
            assertThat(results).hasSize(50);
            // Oldest evicted = sid 1; newest at tail = sid 51
            assertThat(results.get(0).sessionId()).isEqualTo(2L);
            assertThat(results.get(49).sessionId()).isEqualTo(51L);
        }

        @Test
        @DisplayName("globalRecentWins evicts FIFO at capacity")
        void globalWinsCapacity() {
            BotMemory mem = new BotMemory(game, 3);
            mem.recordGlobalWin(Optional.of(1));
            mem.recordGlobalWin(Optional.of(2));
            mem.recordGlobalWin(Optional.of(3));
            mem.recordGlobalWin(Optional.of(4));
            assertThat(mem.snapshotGlobalRecentWins()).containsExactly(2, 3, 4);
        }

        @Test
        @DisplayName("recordGlobalWin with Optional.empty is a no-op")
        void globalWinsEmptyNoOp() {
            BotMemory mem = new BotMemory(game);
            mem.recordGlobalWin(Optional.empty());
            mem.recordGlobalWin(Optional.empty());
            assertThat(mem.snapshotGlobalRecentWins()).isEmpty();
        }
    }

    /* ----- bet recording ----- */

    @Nested
    @DisplayName("recordBetSent")
    class RecordBetSent {

        @Test
        @DisplayName("Accumulates by option id: two bets on same option sum")
        void accumulatesByOption() {
            BotMemory mem = new BotMemory(game);
            mem.beginRound(100L, 1_000_000L);

            mem.recordBetSent(100L, 0, 500L);
            mem.recordBetSent(100L, 0, 300L);
            mem.recordBetSent(100L, 2, 1000L);

            Map<Integer, Long> bets = mem.snapshotCurrentRoundBets();
            assertThat(bets).containsEntry(0, 800L).containsEntry(2, 1000L);
        }

        @Test
        @DisplayName("Bet with mismatched sessionId is dropped (round not contaminated)")
        void mismatchedSessionDropped() {
            BotMemory mem = new BotMemory(game);
            mem.beginRound(100L, 1_000_000L);

            mem.recordBetSent(100L, 0, 500L);
            // Stale bet from previous round
            mem.recordBetSent(99L, 1, 9999L);

            Map<Integer, Long> bets = mem.snapshotCurrentRoundBets();
            assertThat(bets).containsExactly(Map.entry(0, 500L));
        }

        @Test
        @DisplayName("Bet recorded with no active round (sessionId==0) is dropped")
        void noActiveRoundDropped() {
            BotMemory mem = new BotMemory(game);
            // beginRound never called
            mem.recordBetSent(42L, 0, 500L);

            assertThat(mem.snapshotCurrentRoundBets()).isEmpty();
        }
    }

    /* ----- completeRound ----- */

    @Nested
    @DisplayName("completeRound")
    class CompleteRound {

        @Test
        @DisplayName("Matched sessionId: RoundResult carries the in-flight bets and computed delta")
        void matchedRoundResult() {
            BotMemory mem = new BotMemory(game);
            mem.beginRound(123L, 5_000_000L);
            mem.recordBetSent(123L, 0, 500L);
            mem.recordBetSent(123L, 1, 1000L);

            RoundResult result = mem.completeRound(123L, Optional.of(0), 3000L);

            assertThat(result.sessionId()).isEqualTo(123L);
            assertThat(result.winningOption()).contains(0);
            assertThat(result.betsByOption()).containsExactlyInAnyOrderEntriesOf(
                    Map.of(0, 500L, 1, 1000L));
            assertThat(result.payout()).isEqualTo(3000L);
            // 3000 (payout) - 1500 (staked) = 1500
            assertThat(result.balanceDelta()).isEqualTo(1500L);
            assertThat(result.endedAt()).isNotNull();

            // Pushed onto lastResults
            assertThat(mem.snapshotLastResults()).containsExactly(result);
            // currentRound reset (sessionId back to 0)
            assertThat(mem.getCurrentRound().getSessionId()).isZero();
            assertThat(mem.snapshotCurrentRoundBets()).isEmpty();
        }

        @Test
        @DisplayName("Mismatched sessionId discards in-flight bets but still pushes a RoundResult")
        void mismatchedSessionDiscarded() {
            BotMemory mem = new BotMemory(game);
            mem.beginRound(100L, 5_000_000L);
            mem.recordBetSent(100L, 0, 500L);

            // EndGame for a different session id
            RoundResult result = mem.completeRound(200L, Optional.empty(), 0L);

            assertThat(result.sessionId()).isEqualTo(200L);
            assertThat(result.betsByOption()).isEmpty();
            assertThat(result.payout()).isZero();
            assertThat(result.balanceDelta()).isZero();
            assertThat(mem.snapshotLastResults()).containsExactly(result);
            // The in-flight round is reset regardless
            assertThat(mem.getCurrentRound().getSessionId()).isZero();
        }

        @Test
        @DisplayName("Bot that skipped the round (no bets) still produces a RoundResult with empty bets")
        void skippedRoundStillRecorded() {
            BotMemory mem = new BotMemory(game);
            mem.beginRound(100L, 5_000_000L);

            RoundResult result = mem.completeRound(100L, Optional.empty(), 0L);

            assertThat(result.betsByOption()).isEmpty();
            assertThat(result.balanceDelta()).isZero();
            assertThat(mem.snapshotLastResults()).hasSize(1);
        }

        @Test
        @DisplayName("balanceDelta correctly handles loss (payout < staked)")
        void balanceDeltaOnLoss() {
            BotMemory mem = new BotMemory(game);
            mem.beginRound(1L, 10_000_000L);
            mem.recordBetSent(1L, 0, 2_000L);
            mem.recordBetSent(1L, 1, 3_000L);

            RoundResult result = mem.completeRound(1L, Optional.of(5), 0L);

            // 0 payout - 5_000 staked = -5_000
            assertThat(result.balanceDelta()).isEqualTo(-5_000L);
        }
    }

    /* ----- snapshots ----- */

    @Nested
    @DisplayName("defensive snapshots")
    class Snapshots {

        @Test
        @DisplayName("snapshotLastResults returns an immutable copy")
        void lastResultsImmutable() {
            BotMemory mem = new BotMemory(game);
            mem.beginRound(1L, 0L);
            mem.completeRound(1L, Optional.empty(), 0L);

            List<RoundResult> snap = mem.snapshotLastResults();
            assertThatThrownBy(() -> snap.add(null))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("snapshotGlobalRecentWins returns an immutable copy")
        void globalWinsImmutable() {
            BotMemory mem = new BotMemory(game);
            mem.recordGlobalWin(Optional.of(3));

            List<Integer> snap = mem.snapshotGlobalRecentWins();
            assertThatThrownBy(() -> snap.add(99))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("snapshotCurrentRoundBets returns a mutable copy that does not affect the source")
        void currentRoundBetsCopy() {
            BotMemory mem = new BotMemory(game);
            mem.beginRound(1L, 0L);
            mem.recordBetSent(1L, 0, 500L);

            Map<Integer, Long> snap = mem.snapshotCurrentRoundBets();
            snap.put(99, 9999L);

            // Original unchanged
            assertThat(mem.snapshotCurrentRoundBets()).containsExactly(Map.entry(0, 500L));
        }
    }

    /* ----- WARN log on mismatch (Architecture Decision 14) ----- */

    @Nested
    @DisplayName("sessionId mismatch logging")
    class MismatchWarns {

        @Test
        @DisplayName("completeRound with mismatched sessionId emits a WARN line matching the contract pattern")
        void mismatchWarnLine() {
            BotMemory mem = new BotMemory(game);
            mem.beginRound(100L, 0L);

            mem.completeRound(200L, Optional.empty(), 0L);

            List<LogEvent> warns = appender.eventsAt(Level.WARN);
            assertThat(warns).anySatisfy(ev ->
                    assertThat(ev.getMessage().getFormattedMessage())
                            .matches("^.*sessionId mismatch.*$"));
        }

        @Test
        @DisplayName("recordBetSent with mismatched sessionId also emits a WARN line")
        void recordBetSentMismatchWarnLine() {
            BotMemory mem = new BotMemory(game);
            mem.beginRound(100L, 0L);

            mem.recordBetSent(99L, 0, 500L);

            List<LogEvent> warns = appender.eventsAt(Level.WARN);
            assertThat(warns).anySatisfy(ev ->
                    assertThat(ev.getMessage().getFormattedMessage())
                            .matches("^.*sessionId mismatch.*$"));
        }
    }

    /* ----- beginRound semantics ----- */

    @Nested
    @DisplayName("beginRound")
    class BeginRound {

        @Test
        @DisplayName("Clears the previous in-flight round's bets and stamps the new sessionId + balance")
        void clearsAndStamps() {
            BotMemory mem = new BotMemory(game);
            mem.beginRound(1L, 5_000_000L);
            mem.recordBetSent(1L, 0, 500L);

            // Begin a new round without completing the previous (bot crashed / dropped EndGame)
            mem.beginRound(2L, 4_500_000L);

            assertThat(mem.getCurrentRound().getSessionId()).isEqualTo(2L);
            assertThat(mem.snapshotCurrentRoundBets()).isEmpty();
            assertThat(mem.getCurrentBalance()).isEqualTo(4_500_000L);
        }
    }

    /**
     * Log4j2 ListAppender-style helper that captures events into an in-memory
     * buffer. Filter helper exposes events at a given level.
     */
    private static final class CapturingAppender extends AbstractAppender {
        private final List<LogEvent> events = new CopyOnWriteArrayList<>();

        CapturingAppender(String name) {
            super(name, null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }

        List<LogEvent> eventsAt(Level level) {
            List<LogEvent> filtered = new ArrayList<>();
            for (LogEvent e : events) {
                if (e.getLevel().equals(level)) filtered.add(e);
            }
            return filtered;
        }
    }
}
