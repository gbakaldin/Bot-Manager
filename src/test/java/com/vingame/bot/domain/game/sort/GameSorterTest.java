package com.vingame.bot.domain.game.sort;

import com.vingame.bot.common.exception.BadRequestException;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.domain.game.model.GameType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BOTGROUP_GAME_MANAGEMENT Phase 5 — {@link GameSorter} / {@link GameSortKey}. Pins:
 * <ul>
 *   <li>AD-11: {@code equalsIgnoreCase} key resolution; default {@code CREATED_TIME}
 *       desc; unknown key → 400; direction reuses the Phase 4 {@code SortDirection}.</li>
 *   <li>AD-12: present values ordered by direction, N/A (null-extracted) always to the
 *       bottom regardless of direction, ties/N/A broken by NAME asc then id.</li>
 *   <li>Per-key value extraction incl. the active-* aggregates gated to N/A when the
 *       game is inactive.</li>
 * </ul>
 */
@DisplayName("GameSorter (Phase 5)")
class GameSorterTest {

    @Nested
    @DisplayName("Key resolution (AD-11)")
    class KeyResolution {

        @Test
        @DisplayName("equalsIgnoreCase — any casing resolves to the key")
        void caseInsensitive() {
            assertThat(GameSortKey.resolve("name")).isEqualTo(GameSortKey.NAME);
            assertThat(GameSortKey.resolve("NAME")).isEqualTo(GameSortKey.NAME);
            assertThat(GameSortKey.resolve("NaMe")).isEqualTo(GameSortKey.NAME);
            assertThat(GameSortKey.resolve("  bot_group_count  ")).isEqualTo(GameSortKey.BOT_GROUP_COUNT);
            assertThat(GameSortKey.resolve("active_bot_count")).isEqualTo(GameSortKey.ACTIVE_BOT_COUNT);
        }

        @Test
        @DisplayName("null / blank → default CREATED_TIME")
        void defaultKey() {
            assertThat(GameSortKey.resolve(null)).isEqualTo(GameSortKey.CREATED_TIME);
            assertThat(GameSortKey.resolve("")).isEqualTo(GameSortKey.CREATED_TIME);
            assertThat(GameSortKey.resolve("   ")).isEqualTo(GameSortKey.CREATED_TIME);
            assertThat(GameSortKey.DEFAULT).isEqualTo(GameSortKey.CREATED_TIME);
        }

        @Test
        @DisplayName("unknown key → BadRequestException (400)")
        void unknownKey() {
            assertThatThrownBy(() -> GameSortKey.resolve("nonsense"))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("nonsense");
        }

        @Test
        @DisplayName("No BRAND / PRODUCT key (list already scoped)")
        void noBrandProductKey() {
            assertThatThrownBy(() -> GameSortKey.resolve("BRAND")).isInstanceOf(BadRequestException.class);
            assertThatThrownBy(() -> GameSortKey.resolve("PRODUCT")).isInstanceOf(BadRequestException.class);
        }
    }

    @Nested
    @DisplayName("Aggregate + attribute keys")
    class ValueKeys {

        @Test
        @DisplayName("NAME asc / desc")
        void nameOrdering() {
            List<GameSortRow> rows = List.of(
                    row(game("b", "Charlie", null, null), 0, 0, 0, 0),
                    row(game("a", "Alpha", null, null), 0, 0, 0, 0),
                    row(game("c", "Bravo", null, null), 0, 0, 0, 0));
            assertThat(ids(GameSorter.sort(rows, "NAME", "asc"))).containsExactly("a", "c", "b");
            assertThat(ids(GameSorter.sort(rows, "NAME", "desc"))).containsExactly("b", "c", "a");
        }

        @Test
        @DisplayName("BOT_GROUP_COUNT / BOT_COUNT numeric ordering — both directions (never N/A)")
        void countOrdering() {
            List<GameSortRow> rows = List.of(
                    row(game("a", "a", null, null), 3, 30, 0, 0),
                    row(game("b", "b", null, null), 1, 10, 0, 0),
                    row(game("c", "c", null, null), 2, 20, 0, 0));
            assertThat(ids(GameSorter.sort(rows, "BOT_GROUP_COUNT", "asc"))).containsExactly("b", "c", "a");
            assertThat(ids(GameSorter.sort(rows, "BOT_GROUP_COUNT", "desc"))).containsExactly("a", "c", "b");
            assertThat(ids(GameSorter.sort(rows, "BOT_COUNT", "asc"))).containsExactly("b", "c", "a");
            assertThat(ids(GameSorter.sort(rows, "BOT_COUNT", "desc"))).containsExactly("a", "c", "b");
        }

        @Test
        @DisplayName("CREATED_TIME desc (explicit) — newest first")
        void createdTimeDesc() {
            Instant older = Instant.parse("2026-01-01T00:00:00Z");
            Instant newer = Instant.parse("2026-06-01T00:00:00Z");
            List<GameSortRow> rows = List.of(
                    row(game("old", "old", null, older), 0, 0, 0, 0),
                    row(game("new", "new", null, newer), 0, 0, 0, 0));
            assertThat(ids(GameSorter.sort(rows, "CREATED_TIME", "desc"))).containsExactly("new", "old");
        }

        @Test
        @DisplayName("CREATED_TIME reads the Instant field; default is CREATED_TIME desc")
        void createdTime() {
            Instant older = Instant.parse("2026-01-01T00:00:00Z");
            Instant newer = Instant.parse("2026-06-01T00:00:00Z");
            List<GameSortRow> rows = List.of(
                    row(game("new", "new", null, newer), 0, 0, 0, 0),
                    row(game("old", "old", null, older), 0, 0, 0, 0));
            // default: CREATED_TIME desc → newest first
            assertThat(ids(GameSorter.sort(rows, null, null))).containsExactly("new", "old");
            assertThat(ids(GameSorter.sort(rows, "CREATED_TIME", "asc"))).containsExactly("old", "new");
        }

        @Test
        @DisplayName("GAME_TYPE by resolved name; null (no gameType) → N/A bottom")
        void gameType() {
            List<GameSortRow> rows = List.of(
                    row(game("slot", "slot", GameType.SLOT, null), 0, 0, 0, 0),
                    row(game("betting", "betting", GameType.BETTING_MINI, null), 0, 0, 0, 0),
                    row(game("none", "none", null, null), 0, 0, 0, 0));
            assertThat(ids(GameSorter.sort(rows, "GAME_TYPE", "asc"))).containsExactly("betting", "slot", "none");
            assertThat(ids(GameSorter.sort(rows, "GAME_TYPE", "desc"))).containsExactly("slot", "betting", "none");
        }
    }

    @Nested
    @DisplayName("Active-* aggregates + N/A-to-bottom (AD-12)")
    class ActiveKeys {

        @Test
        @DisplayName("ACTIVE_GROUP_COUNT — inactive game (0 running groups) → N/A bottom for BOTH dirs")
        void activeGroupCountNaBottom() {
            GameSortRow low = row(game("low", "low", null, null), 3, 30, 1, 4);
            GameSortRow high = row(game("high", "high", null, null), 3, 30, 2, 9);
            GameSortRow inactive = row(game("na", "na", null, null), 3, 30, 0, 0); // inactive → N/A

            List<GameSortRow> rows = List.of(high, inactive, low);
            assertThat(ids(GameSorter.sort(rows, "ACTIVE_GROUP_COUNT", "asc"))).containsExactly("low", "high", "na");
            assertThat(ids(GameSorter.sort(rows, "ACTIVE_GROUP_COUNT", "desc"))).containsExactly("high", "low", "na");
        }

        @Test
        @DisplayName("ACTIVE_BOT_COUNT — present for active games, N/A for inactive")
        void activeBotCount() {
            GameSortRow a = row(game("a", "a", null, null), 2, 20, 1, 3);
            GameSortRow b = row(game("b", "b", null, null), 2, 20, 1, 7);
            GameSortRow inactive = row(game("na", "na", null, null), 2, 20, 0, 0);

            List<GameSortRow> rows = List.of(a, inactive, b);
            assertThat(ids(GameSorter.sort(rows, "ACTIVE_BOT_COUNT", "asc"))).containsExactly("a", "b", "na");
            assertThat(ids(GameSorter.sort(rows, "ACTIVE_BOT_COUNT", "desc"))).containsExactly("b", "a", "na");
        }
    }

    @Nested
    @DisplayName("Secondary tie-break (AD-12)")
    class TieBreak {

        @Test
        @DisplayName("Equal primary values break by NAME ascending, then id — independent of direction")
        void tieBreakByNameThenId() {
            List<GameSortRow> rows = List.of(
                    row(game("3", "Zeta", null, null), 10, 100, 0, 0),
                    row(game("1", "Alpha", null, null), 10, 100, 0, 0),
                    row(game("2", "Mike", null, null), 10, 100, 0, 0));
            assertThat(ids(GameSorter.sort(rows, "BOT_COUNT", "asc"))).containsExactly("1", "2", "3");
            assertThat(ids(GameSorter.sort(rows, "BOT_COUNT", "desc"))).containsExactly("1", "2", "3");
        }

        @Test
        @DisplayName("N/A block ordered by NAME asc too")
        void naBlockOrderedByName() {
            GameSortRow present = row(game("p", "Present", null, null), 1, 5, 1, 2);
            GameSortRow naZ = row(game("z", "Zebra", null, null), 1, 5, 0, 0);
            GameSortRow naA = row(game("a", "Ant", null, null), 1, 5, 0, 0);

            List<GameSortRow> rows = List.of(naZ, present, naA);
            assertThat(ids(GameSorter.sort(rows, "ACTIVE_BOT_COUNT", "desc")))
                    .containsExactly("p", "a", "z"); // present first, then N/A by NAME asc (Ant<Zebra)
        }
    }

    /* ---- helpers ---- */

    private static GameSortRow row(Game game, int botGroupCount, int botCount,
                                   int activeGroupCount, int activeBotCount) {
        return new GameSortRow(game, botGroupCount, botCount, activeGroupCount, activeBotCount);
    }

    private static Game game(String id, String name, GameType type, Instant createdAt) {
        return Game.builder().id(id).name(name).gameType(type).createdAt(createdAt).build();
    }

    private static List<String> ids(List<GameSortRow> rows) {
        return rows.stream().map(r -> r.game().getId()).toList();
    }
}
