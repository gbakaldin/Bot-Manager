package com.vingame.bot.domain.botgroup.sort;

import com.vingame.bot.common.exception.BadRequestException;
import com.vingame.bot.domain.botgroup.dto.BotGroupStatsDTO;
import com.vingame.bot.domain.botgroup.model.BotGroup;
import com.vingame.bot.domain.botgroup.model.BotGroupStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BOTGROUP_GAME_MANAGEMENT Phase 4 — {@link BotGroupSorter} / {@link BotSortKey} /
 * {@link SortDirection}. Pins:
 * <ul>
 *   <li>AD-11: {@code equalsIgnoreCase} key/direction resolution; default
 *       {@code CREATED_TIME} desc; unknown key → 400.</li>
 *   <li>AD-12: present values ordered by direction, N/A (null-extracted) always to
 *       the bottom regardless of direction, ties/N/A broken by NAME asc then id.</li>
 *   <li>Per-key value extraction (configured + runtime-only keys).</li>
 * </ul>
 */
@DisplayName("BotGroupSorter (Phase 4)")
class BotGroupSorterTest {

    @Nested
    @DisplayName("Key resolution (AD-11)")
    class KeyResolution {

        @Test
        @DisplayName("equalsIgnoreCase — any casing resolves to the key")
        void caseInsensitive() {
            assertThat(BotSortKey.resolve("name")).isEqualTo(BotSortKey.NAME);
            assertThat(BotSortKey.resolve("NAME")).isEqualTo(BotSortKey.NAME);
            assertThat(BotSortKey.resolve("NaMe")).isEqualTo(BotSortKey.NAME);
            assertThat(BotSortKey.resolve("  bot_count  ")).isEqualTo(BotSortKey.BOT_COUNT);
        }

        @Test
        @DisplayName("null / blank → default CREATED_TIME")
        void defaultKey() {
            assertThat(BotSortKey.resolve(null)).isEqualTo(BotSortKey.CREATED_TIME);
            assertThat(BotSortKey.resolve("")).isEqualTo(BotSortKey.CREATED_TIME);
            assertThat(BotSortKey.resolve("   ")).isEqualTo(BotSortKey.CREATED_TIME);
            assertThat(BotSortKey.DEFAULT).isEqualTo(BotSortKey.CREATED_TIME);
        }

        @Test
        @DisplayName("unknown key → BadRequestException (400)")
        void unknownKey() {
            assertThatThrownBy(() -> BotSortKey.resolve("nonsense"))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("nonsense");
        }

        @Test
        @DisplayName("No ENVIRONMENT key (list already env-scoped)")
        void noEnvironmentKey() {
            assertThatThrownBy(() -> BotSortKey.resolve("ENVIRONMENT"))
                    .isInstanceOf(BadRequestException.class);
        }
    }

    @Nested
    @DisplayName("Direction resolution (AD-11)")
    class DirectionResolution {

        @Test
        @DisplayName("equalsIgnoreCase asc/desc; null/blank/unknown → DESC")
        void resolveDir() {
            assertThat(SortDirection.resolve("asc")).isEqualTo(SortDirection.ASC);
            assertThat(SortDirection.resolve("ASC")).isEqualTo(SortDirection.ASC);
            assertThat(SortDirection.resolve("desc")).isEqualTo(SortDirection.DESC);
            assertThat(SortDirection.resolve(null)).isEqualTo(SortDirection.DESC);
            assertThat(SortDirection.resolve("")).isEqualTo(SortDirection.DESC);
            assertThat(SortDirection.resolve("garbage")).isEqualTo(SortDirection.DESC);
        }
    }

    @Nested
    @DisplayName("Configured (never-N/A) keys")
    class ConfiguredKeys {

        @Test
        @DisplayName("NAME asc / desc")
        void nameOrdering() {
            List<BotGroupSortRow> rows = List.of(named("b", "Charlie"), named("a", "Alpha"), named("c", "Bravo"));
            assertThat(ids(BotGroupSorter.sort(rows, "NAME", "asc"))).containsExactly("a", "c", "b");
            assertThat(ids(BotGroupSorter.sort(rows, "NAME", "desc"))).containsExactly("b", "c", "a");
        }

        @Test
        @DisplayName("BOT_COUNT numeric ordering")
        void botCountOrdering() {
            List<BotGroupSortRow> rows = List.of(
                    row(BotGroup.builder().id("a").name("a").botCount(30).build(), stats().build(), null, null),
                    row(BotGroup.builder().id("b").name("b").botCount(10).build(), stats().build(), null, null),
                    row(BotGroup.builder().id("c").name("c").botCount(20).build(), stats().build(), null, null));
            assertThat(ids(BotGroupSorter.sort(rows, "BOT_COUNT", "asc"))).containsExactly("b", "c", "a");
            assertThat(ids(BotGroupSorter.sort(rows, "BOT_COUNT", "desc"))).containsExactly("a", "c", "b");
        }

        @Test
        @DisplayName("BET_AMOUNT = configured maxBet; MAX_PER_ROUND = configured maxTotalBetPerRound")
        void betAmountAndMaxPerRound() {
            List<BotGroupSortRow> rows = List.of(
                    row(BotGroup.builder().id("a").name("a").maxBet(500).maxTotalBetPerRound(9000).build(), stats().build(), null, null),
                    row(BotGroup.builder().id("b").name("b").maxBet(100).maxTotalBetPerRound(1000).build(), stats().build(), null, null));
            assertThat(ids(BotGroupSorter.sort(rows, "BET_AMOUNT", "asc"))).containsExactly("b", "a");
            assertThat(ids(BotGroupSorter.sort(rows, "MAX_PER_ROUND", "desc"))).containsExactly("a", "b");
        }

        @Test
        @DisplayName("CREATED_TIME / UPDATED_TIME read the Instant fields")
        void timestamps() {
            Instant older = Instant.parse("2026-01-01T00:00:00Z");
            Instant newer = Instant.parse("2026-06-01T00:00:00Z");
            List<BotGroupSortRow> rows = List.of(
                    row(BotGroup.builder().id("new").name("new").createdAt(newer).updatedAt(newer).build(), stats().build(), null, null),
                    row(BotGroup.builder().id("old").name("old").createdAt(older).updatedAt(older).build(), stats().build(), null, null));
            // default: CREATED_TIME desc → newest first
            assertThat(ids(BotGroupSorter.sort(rows, null, null))).containsExactly("new", "old");
            assertThat(ids(BotGroupSorter.sort(rows, "UPDATED_TIME", "asc"))).containsExactly("old", "new");
        }

        @Test
        @DisplayName("GAME_TYPE by resolved name; null (missing game) → N/A bottom")
        void gameType() {
            List<BotGroupSortRow> rows = List.of(
                    row(BotGroup.builder().id("slot").name("slot").build(), stats().build(), null, "SLOT"),
                    row(BotGroup.builder().id("betting").name("betting").build(), stats().build(), null, "BETTING_MINI"),
                    row(BotGroup.builder().id("missing").name("missing").build(), stats().build(), null, null));
            // asc: BETTING_MINI < SLOT, then N/A last
            assertThat(ids(BotGroupSorter.sort(rows, "GAME_TYPE", "asc"))).containsExactly("betting", "slot", "missing");
            // desc: SLOT, BETTING_MINI, then N/A STILL last
            assertThat(ids(BotGroupSorter.sort(rows, "GAME_TYPE", "desc"))).containsExactly("slot", "betting", "missing");
        }
    }

    @Nested
    @DisplayName("Runtime-only keys + N/A-to-bottom (AD-12)")
    class RuntimeKeys {

        @Test
        @DisplayName("BALANCE — N/A always bottom for BOTH directions")
        void balanceNaBottomBothDirs() {
            BotGroupSortRow low = row(BotGroup.builder().id("low").name("low").build(),
                    stats().activeTimeSeconds(10L).averageBalance(100L).build(), BotGroupStatus.ACTIVE, null);
            BotGroupSortRow high = row(BotGroup.builder().id("high").name("high").build(),
                    stats().activeTimeSeconds(10L).averageBalance(900L).build(), BotGroupStatus.ACTIVE, null);
            BotGroupSortRow na = row(BotGroup.builder().id("na").name("na").build(),
                    stats().build(), BotGroupStatus.STOPPED, null); // averageBalance null → N/A

            List<BotGroupSortRow> rows = List.of(high, na, low);
            assertThat(ids(BotGroupSorter.sort(rows, "BALANCE", "asc"))).containsExactly("low", "high", "na");
            assertThat(ids(BotGroupSorter.sort(rows, "BALANCE", "desc"))).containsExactly("high", "low", "na");
        }

        @Test
        @DisplayName("ACTIVE_BOTS / ACTIVE_TIME / AVG_WINNING extract from stats; null → bottom")
        void otherRuntimeKeys() {
            BotGroupSortRow a = row(BotGroup.builder().id("a").name("a").build(),
                    stats().activeTimeSeconds(50L).activeBots(3).averageWinning(200L).build(), BotGroupStatus.ACTIVE, null);
            BotGroupSortRow b = row(BotGroup.builder().id("b").name("b").build(),
                    stats().activeTimeSeconds(80L).activeBots(5).averageWinning(100L).build(), BotGroupStatus.ACTIVE, null);
            BotGroupSortRow na = row(BotGroup.builder().id("na").name("na").build(),
                    stats().build(), BotGroupStatus.STOPPED, null);

            List<BotGroupSortRow> rows = List.of(a, na, b);
            assertThat(ids(BotGroupSorter.sort(rows, "ACTIVE_BOTS", "asc"))).containsExactly("a", "b", "na");
            assertThat(ids(BotGroupSorter.sort(rows, "ACTIVE_TIME", "desc"))).containsExactly("b", "a", "na");
            assertThat(ids(BotGroupSorter.sort(rows, "AVG_WINNING", "asc"))).containsExactly("b", "a", "na");
        }

        @Test
        @DisplayName("STATUS — running groups ordered by status enum; stopped (no runtime) → N/A bottom")
        void statusKey() {
            // Running groups carry a runtime (activeTimeSeconds != null) so STATUS is present.
            BotGroupSortRow active = row(BotGroup.builder().id("active").name("active").build(),
                    stats().activeTimeSeconds(5L).build(), BotGroupStatus.ACTIVE, null);
            BotGroupSortRow dead = row(BotGroup.builder().id("dead").name("dead").build(),
                    stats().activeTimeSeconds(5L).build(), BotGroupStatus.DEAD, null);
            // Stopped group has no runtime → activeTimeSeconds null → STATUS is N/A.
            BotGroupSortRow stopped = row(BotGroup.builder().id("stopped").name("stopped").build(),
                    stats().build(), BotGroupStatus.STOPPED, null);

            List<BotGroupSortRow> rows = List.of(dead, stopped, active);
            // ACTIVE(0) < DEAD(2) by ordinal; stopped N/A last regardless of dir.
            assertThat(ids(BotGroupSorter.sort(rows, "STATUS", "asc"))).containsExactly("active", "dead", "stopped");
            assertThat(ids(BotGroupSorter.sort(rows, "STATUS", "desc"))).containsExactly("dead", "active", "stopped");
        }
    }

    @Nested
    @DisplayName("Secondary tie-break (AD-12)")
    class TieBreak {

        @Test
        @DisplayName("Equal primary values break by NAME ascending, then id — independent of direction")
        void tieBreakByNameThenId() {
            // All same botCount → primary tie. Names determine order, both dirs.
            List<BotGroupSortRow> rows = List.of(
                    row(BotGroup.builder().id("3").name("Zeta").botCount(10).build(), stats().build(), null, null),
                    row(BotGroup.builder().id("1").name("Alpha").botCount(10).build(), stats().build(), null, null),
                    row(BotGroup.builder().id("2").name("Mike").botCount(10).build(), stats().build(), null, null));
            assertThat(ids(BotGroupSorter.sort(rows, "BOT_COUNT", "asc"))).containsExactly("1", "2", "3");
            assertThat(ids(BotGroupSorter.sort(rows, "BOT_COUNT", "desc"))).containsExactly("1", "2", "3");
        }

        @Test
        @DisplayName("N/A block ordered by NAME asc too")
        void naBlockOrderedByName() {
            BotGroupSortRow present = row(BotGroup.builder().id("p").name("Present").build(),
                    stats().activeTimeSeconds(1L).averageBalance(5L).build(), BotGroupStatus.ACTIVE, null);
            BotGroupSortRow naZ = row(BotGroup.builder().id("z").name("Zebra").build(), stats().build(), BotGroupStatus.STOPPED, null);
            BotGroupSortRow naA = row(BotGroup.builder().id("a").name("Ant").build(), stats().build(), BotGroupStatus.STOPPED, null);

            List<BotGroupSortRow> rows = List.of(naZ, present, naA);
            assertThat(ids(BotGroupSorter.sort(rows, "BALANCE", "desc")))
                    .containsExactly("p", "a", "z"); // present first, then N/A by NAME asc (Ant<Zebra)
        }
    }

    /* ---- helpers ---- */

    private static BotGroupSortRow row(BotGroup group, BotGroupStatsDTO stats, BotGroupStatus status, String gameType) {
        return new BotGroupSortRow(group, stats, status, gameType);
    }

    private static BotGroupSortRow named(String id, String name) {
        return row(BotGroup.builder().id(id).name(name).build(), stats().build(), null, null);
    }

    private static BotGroupStatsDTO.BotGroupStatsDTOBuilder stats() {
        return BotGroupStatsDTO.builder();
    }

    private static List<String> ids(List<BotGroupSortRow> rows) {
        return rows.stream().map(r -> r.group().getId()).toList();
    }
}
