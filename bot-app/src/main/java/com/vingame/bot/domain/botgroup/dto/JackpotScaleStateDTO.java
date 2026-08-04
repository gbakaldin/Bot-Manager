package com.vingame.bot.domain.botgroup.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Read-side view of a running group's jackpot volume scaler (JACKPOT_SCALE_AND_RAMP
 * Phase J4, AD-J10). Nullable block on {@link BotGroupHealthDTO}: present only when
 * the group is running <em>and</em> a {@code JackpotScaler} is active (jackpot-scale
 * enabled and the game is eligible) — absent (null) otherwise.
 * <p>
 * Built from the scaler's coherent {@code snapshot()} accessor (read under a single
 * lock acquisition so the view is never torn against a concurrent {@code observePool}).
 * This is strictly read-only — the health path never mutates the scaler.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JackpotScaleStateDTO {

    /** True when a scaler is active for the running group. */
    private boolean enabled;

    /** Operator-configured per-game ceiling; at/above it the factor is {@code 1.0}. */
    private long jackpotCeiling;

    /** The jackpot reset value; at/below it the factor is {@code minMultiplier}. */
    private long seedFloor;

    /** The last non-zero live pool meter ({@code tJpV}) folded into the scaler. */
    private long lastObservedPool;

    /** The current volume scale factor {@code f ∈ [minMultiplier, 1.0]} for the next round. */
    private double currentFactor;

    /** The factor's floor (baseline quiet-table presence, e.g. 0.25). */
    private double minMultiplier;
}
