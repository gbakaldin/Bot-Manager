package com.vingame.bot.domain.bot.strategy.dto;

import com.vingame.bot.domain.bot.strategy.StrategyId;
import com.vingame.bot.domain.bot.strategy.slot.SlotStrategyId;

/**
 * Wire shape for the {@code GET /api/v1/strategy/} listing. The frontend
 * populates the strategy picker on the bot-group form from this — each entry is
 * shown as {@code displayName} with {@code description} as the tooltip /
 * secondary text, and {@code id} is what is POSTed back inside the
 * {@code strategyMix} list.
 *
 * <p><b>{@code id} is the registry key</b> (PLUGIN_HOT_RELOAD Phase 2d), not an
 * enum name. For a built-in the two coincide — {@code "RANDOM"} is both
 * {@link StrategyId#RANDOM}'s name and the key on its {@code @StrategyImpl} — but
 * a plugin-supplied key is not an enum constant at all, and being able to offer
 * one is the entire point of the phase. Treat {@code id} as an opaque string;
 * anything that round-trips it through {@code StrategyId.valueOf} reintroduces
 * the coupling AD-12 removed.
 *
 * <p><b>Only the betting family is served.</b>
 * {@code GET /api/v1/strategy/?gameType=SLOT} returns {@code []} and did so before
 * 2d as well, so {@link #of(SlotStrategyId)} has <b>no production caller</b>. It is
 * retained, not wired: slots always run {@code FIXED} and the slot strategy is not
 * selectable in the UI (see {@code StrategyCatalog}'s "Why the slot registry is not
 * joined here"). If that ever becomes selectable this overload is the shape it
 * takes; until then it is reserved, and wiring it up is a product decision rather
 * than a tidy-up.
 */
public record StrategyInfoDTO(String id, String displayName, String description) {

    public static StrategyInfoDTO of(StrategyId id) {
        return new StrategyInfoDTO(id.name(), id.getDisplayName(), id.getDescription());
    }

    /**
     * Reserved for the day slot strategy becomes selectable — no production caller
     * today; see the class javadoc.
     */
    public static StrategyInfoDTO of(SlotStrategyId id) {
        return new StrategyInfoDTO(id.name(), id.getDisplayName(), id.getDescription());
    }
}
