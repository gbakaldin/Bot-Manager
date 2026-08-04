package com.vingame.bot.domain.botgroup.sort;

import com.vingame.bot.domain.botgroup.dto.BotGroupStatsDTO;
import com.vingame.bot.domain.botgroup.model.BotGroup;
import com.vingame.bot.domain.botgroup.model.BotGroupStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * BOTGROUP_GAME_MANAGEMENT Phase 4 — exhaustive both-directions coverage for the
 * whole {@link BotSortKey} catalog (12 keys). The Dev suite pins most keys but only
 * exercises several of them in a single direction, and proves N/A-to-bottom only for
 * BALANCE and GAME_TYPE. This test closes the load-bearing gaps the QA task called
 * out:
 * <ul>
 *   <li><b>every</b> key sorts correctly ascending AND descending (AD-11);</li>
 *   <li>for every runtime-only key, the N/A (null-extracted) row lands at the
 *       <b>bottom in BOTH directions</b> (AD-12);</li>
 *   <li>the primary value — not the NAME/id tie-break — drives the ordering (present
 *       rows are deliberately named so that NAME order is the reverse of value
 *       order).</li>
 * </ul>
 * Each case feeds the rows to the sorter <em>shuffled</em> so a no-op sort cannot
 * pass by accident.
 */
@DisplayName("BotGroupSorter — all keys, both directions, N/A-to-bottom (Phase 4)")
class BotGroupSorterExhaustiveTest {

    private static final Instant OLDER = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant NEWER = Instant.parse("2026-06-01T00:00:00Z");

    /**
     * One case per {@link BotSortKey}. Present rows have ids {@code lo}/{@code hi}
     * (lo = smaller extracted value); N/A rows have id {@code na}. The expected
     * ascending order is present-ascending then {@code na}; descending is
     * present-descending then {@code na} (N/A never moves).
     */
    static Stream<Arguments> keyCases() {
        return Stream.of(
                // ---- configured (never-N/A) keys: no na row ----
                arguments("BOT_COUNT",
                        List.of(cfg("hi", "Aaa", b -> b.botCount(20)),
                                cfg("lo", "Zzz", b -> b.botCount(10))),
                        List.of("lo", "hi"), List.of("hi", "lo")),

                arguments("BET_AMOUNT",
                        List.of(cfg("hi", "Aaa", b -> b.maxBet(500)),
                                cfg("lo", "Zzz", b -> b.maxBet(100))),
                        List.of("lo", "hi"), List.of("hi", "lo")),

                arguments("MAX_PER_ROUND",
                        List.of(cfg("hi", "Aaa", b -> b.maxTotalBetPerRound(9000)),
                                cfg("lo", "Zzz", b -> b.maxTotalBetPerRound(1000))),
                        List.of("lo", "hi"), List.of("hi", "lo")),

                arguments("CREATED_TIME",
                        List.of(cfg("hi", "Aaa", b -> b.createdAt(NEWER)),
                                cfg("lo", "Zzz", b -> b.createdAt(OLDER))),
                        List.of("lo", "hi"), List.of("hi", "lo")),

                arguments("UPDATED_TIME",
                        List.of(cfg("hi", "Aaa", b -> b.updatedAt(NEWER)),
                                cfg("lo", "Zzz", b -> b.updatedAt(OLDER))),
                        List.of("lo", "hi"), List.of("hi", "lo")),

                // NAME is its own tie-break; the name IS the value here.
                arguments("NAME",
                        List.of(cfg("hi", "Bravo", b -> b),
                                cfg("lo", "Alpha", b -> b)),
                        List.of("lo", "hi"), List.of("hi", "lo")),

                // ---- runtime-only / resolved keys: include an N/A row ----
                arguments("GAME_TYPE",
                        List.of(gameType("hi", "Aaa", "SLOT"),
                                gameType("na", "Mmm", null),
                                gameType("lo", "Zzz", "BETTING_MINI")),
                        List.of("lo", "hi", "na"), List.of("hi", "lo", "na")),

                arguments("BALANCE",
                        List.of(runtime("hi", "Aaa", s -> s.activeTimeSeconds(5L).averageBalance(900L)),
                                naRuntime("na", "Mmm"),
                                runtime("lo", "Zzz", s -> s.activeTimeSeconds(5L).averageBalance(100L))),
                        List.of("lo", "hi", "na"), List.of("hi", "lo", "na")),

                arguments("ACTIVE_BOTS",
                        List.of(runtime("hi", "Aaa", s -> s.activeTimeSeconds(5L).activeBots(5)),
                                naRuntime("na", "Mmm"),
                                runtime("lo", "Zzz", s -> s.activeTimeSeconds(5L).activeBots(1))),
                        List.of("lo", "hi", "na"), List.of("hi", "lo", "na")),

                arguments("ACTIVE_TIME",
                        List.of(runtime("hi", "Aaa", s -> s.activeTimeSeconds(80L)),
                                naRuntime("na", "Mmm"),
                                runtime("lo", "Zzz", s -> s.activeTimeSeconds(10L))),
                        List.of("lo", "hi", "na"), List.of("hi", "lo", "na")),

                arguments("AVG_WINNING",
                        List.of(runtime("hi", "Aaa", s -> s.activeTimeSeconds(5L).averageWinning(300L)),
                                naRuntime("na", "Mmm"),
                                runtime("lo", "Zzz", s -> s.activeTimeSeconds(5L).averageWinning(50L))),
                        List.of("lo", "hi", "na"), List.of("hi", "lo", "na")),

                // STATUS is N/A when there is no runtime (activeTimeSeconds == null).
                // Present rows carry a runtime; ACTIVE(ordinal 0) < DEAD(ordinal 2).
                arguments("STATUS",
                        List.of(status("hi", "Aaa", BotGroupStatus.DEAD, 5L),
                                status("na", "Mmm", BotGroupStatus.STOPPED, null),
                                status("lo", "Zzz", BotGroupStatus.ACTIVE, 5L)),
                        List.of("lo", "hi", "na"), List.of("hi", "lo", "na"))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("keyCases")
    @DisplayName("sorts asc and desc with N/A pinned to the bottom in both directions")
    void sortsBothDirections(String key, List<BotGroupSortRow> rows,
                             List<String> expectedAsc, List<String> expectedDesc) {
        assertEquals(expectedAsc, ids(BotGroupSorter.sort(rows, key, "asc")),
                key + " ascending order");
        assertEquals(expectedDesc, ids(BotGroupSorter.sort(rows, key, "desc")),
                key + " descending order");

        // N/A rows (id "na") must be at the tail regardless of direction.
        if (expectedAsc.contains("na")) {
            assertThat(ids(BotGroupSorter.sort(rows, key, "asc"))).last().isEqualTo("na");
            assertThat(ids(BotGroupSorter.sort(rows, key, "desc"))).last().isEqualTo("na");
        }
    }

    /* ---- row builders ---- */

    private interface GroupTweak {
        BotGroup.BotGroupBuilder apply(BotGroup.BotGroupBuilder b);
    }

    private interface StatsTweak {
        BotGroupStatsDTO.BotGroupStatsDTOBuilder apply(BotGroupStatsDTO.BotGroupStatsDTOBuilder s);
    }

    private static BotGroupSortRow cfg(String id, String name, GroupTweak tweak) {
        BotGroup group = tweak.apply(BotGroup.builder().id(id).name(name)).build();
        return new BotGroupSortRow(group, BotGroupStatsDTO.builder().build(), null, null);
    }

    private static BotGroupSortRow gameType(String id, String name, String gameType) {
        BotGroup group = BotGroup.builder().id(id).name(name).build();
        return new BotGroupSortRow(group, BotGroupStatsDTO.builder().build(), null, gameType);
    }

    private static BotGroupSortRow runtime(String id, String name, StatsTweak tweak) {
        BotGroup group = BotGroup.builder().id(id).name(name).build();
        BotGroupStatsDTO stats = tweak.apply(BotGroupStatsDTO.builder()).build();
        return new BotGroupSortRow(group, stats, BotGroupStatus.ACTIVE, null);
    }

    private static BotGroupSortRow naRuntime(String id, String name) {
        BotGroup group = BotGroup.builder().id(id).name(name).build();
        return new BotGroupSortRow(group, BotGroupStatsDTO.builder().build(), BotGroupStatus.STOPPED, null);
    }

    private static BotGroupSortRow status(String id, String name, BotGroupStatus status, Long activeTime) {
        BotGroup group = BotGroup.builder().id(id).name(name).build();
        BotGroupStatsDTO stats = BotGroupStatsDTO.builder().activeTimeSeconds(activeTime).build();
        return new BotGroupSortRow(group, stats, status, null);
    }

    private static List<String> ids(List<BotGroupSortRow> rows) {
        return rows.stream().map(r -> r.group().getId()).toList();
    }
}
