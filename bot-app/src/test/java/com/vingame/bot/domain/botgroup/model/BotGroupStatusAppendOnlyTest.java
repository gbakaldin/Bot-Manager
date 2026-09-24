package com.vingame.bot.domain.botgroup.model;

import com.vingame.bot.domain.botgroup.dto.BotGroupStatsDTO;
import com.vingame.bot.domain.botgroup.sort.BotGroupSortRow;
import com.vingame.bot.domain.botgroup.sort.BotSortKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link BotGroupStatus} is append-only (GATEWAY_REQUEST_BUDGET A1).
 * <p>
 * {@code BotSortKey.STATUS} extracts the enum itself and {@code BotGroupSorter} compares it
 * as a {@link Comparable}, i.e. on {@link Enum#ordinal()}. So the declaration order of this
 * enum <b>is</b> the semantics of the {@code STATUS} sort that
 * {@code POST /api/v1/bot-group/{envId}/filter} exposes. Inserting {@code STARTING} between
 * {@code STOPPED} and {@code DEAD} would compile, pass every other test, and silently
 * reorder every existing group's position in an operator's list — the sort would still look
 * plausible, which is exactly why it needs pinning here rather than being noticed.
 */
@DisplayName("BotGroupStatus is append-only")
class BotGroupStatusAppendOnlyTest {

    @Test
    @DisplayName("the original three keep ordinals 0/1/2")
    void originalOrdinalsAreUntouched() {
        assertThat(BotGroupStatus.ACTIVE.ordinal()).isZero();
        assertThat(BotGroupStatus.STOPPED.ordinal()).isEqualTo(1);
        assertThat(BotGroupStatus.DEAD.ordinal()).isEqualTo(2);
    }

    @Test
    @DisplayName("the enum is exactly the six constants A1 declares, in that order")
    void declarationOrderIsFixed() {
        assertThat(BotGroupStatus.values()).containsExactly(
                BotGroupStatus.ACTIVE,
                BotGroupStatus.STOPPED,
                BotGroupStatus.DEAD,
                BotGroupStatus.STARTING,
                BotGroupStatus.REGISTRATION_PENDING,
                BotGroupStatus.REGISTRATION_FAILED);
    }

    @Test
    @DisplayName("the STATUS sort still orders ACTIVE before STOPPED before DEAD")
    void statusSortOrderIsUnchanged() {
        // Exercised through the real extractor + the real Comparable contract, so this fails
        // for an insertion even if someone "fixes" the ordinal assertions above.
        BotGroupStatus active = extract(BotGroupStatus.ACTIVE);
        BotGroupStatus stopped = extract(BotGroupStatus.STOPPED);
        BotGroupStatus dead = extract(BotGroupStatus.DEAD);
        BotGroupStatus starting = extract(BotGroupStatus.STARTING);

        assertThat(List.of(active, stopped, dead, starting)).isSorted();
    }

    /** Run a status through {@link BotSortKey#STATUS} exactly as the filter endpoint does. */
    private static BotGroupStatus extract(BotGroupStatus status) {
        BotGroupSortRow row = new BotGroupSortRow(
                BotGroup.builder().id("g").name("g").build(),
                // activeTimeSeconds non-null ⇒ "running", so STATUS is not N/A (AD-12).
                BotGroupStatsDTO.builder().activeTimeSeconds(1L).build(),
                status,
                "BETTING_MINI");
        return (BotGroupStatus) BotSortKey.STATUS.extract(row);
    }
}
