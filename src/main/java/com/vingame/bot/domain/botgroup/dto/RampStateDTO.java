package com.vingame.bot.domain.botgroup.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Read-side view of a running group's late-window bet ramp (JACKPOT_SCALE_AND_RAMP
 * Phase R3, AD-R7). Nullable block on {@link BotGroupHealthDTO}: present only when
 * the group is running <em>and</em> {@code rampEnabled} is set on the group entity —
 * absent (null) otherwise.
 * <p>
 * Thin by design: the ramp is stateless per-bot (a per-tick accept gate, AD-R7), so
 * there is no runtime object to snapshot. The block simply reflects the group's
 * configured ramp params; its actual per-round effect is already visible in the
 * existing 5s UpdateBet aggregate (bet-timing distribution).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RampStateDTO {

    /** True when the ramp is enabled for the running group. */
    private boolean enabled;

    /** Operator-configured ramp shape (&gt; 0); higher values push bets later in the window. */
    private double rampShape;
}
