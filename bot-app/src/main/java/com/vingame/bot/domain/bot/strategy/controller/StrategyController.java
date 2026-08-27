package com.vingame.bot.domain.bot.strategy.controller;

import com.vingame.bot.domain.bot.strategy.catalog.StrategyCatalog;
import com.vingame.bot.domain.bot.strategy.dto.StrategyInfoDTO;
import com.vingame.bot.domain.game.model.GameType;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Since PLUGIN_HOT_RELOAD Phase 2d the listing is sourced from
 * {@link StrategyCatalog} — the registered strategy keys joined to display
 * metadata — instead of enumerating {@code StrategyId.values()}. Path, DTO shape,
 * id strings, display copy and order are all unchanged (AD-21); only the
 * <em>source</em> of the list moved, so that a plugin-supplied key the engine does
 * not declare can appear in the picker.
 */
@RestController
@RequestMapping("api/v1/strategy")
public class StrategyController {

    private final StrategyCatalog catalog;

    @Autowired
    public StrategyController(StrategyCatalog catalog) {
        this.catalog = catalog;
    }

    @Operation(
            summary = "List available strategies for a game type",
            description = "Returns each registered strategy key paired with its display name and description, "
                    + "scoped to the supplied gameType. BETTING_MINI and TAI_XIU — or an absent gameType "
                    + "(backward-compatible default) — return the betting strategies (Tai Xiu reuses the betting "
                    + "strategy family, TAI_XIU_BOT plan AD-6), built-ins first in their canonical order. SLOT "
                    + "exposes no selectable strategies (slots always run the basic FIXED strategy server-side) "
                    + "and returns an empty list, as do game types with no strategies implemented yet. The "
                    + "frontend uses this to populate the strategy picker on the bot-group form.")
    @GetMapping("/")
    public ResponseEntity<List<StrategyInfoDTO>> list(@RequestParam(required = false) GameType gameType) {
        // TAI_XIU reuses the betting strategy family (AD-6), so it lists the same
        // strategies as BETTING_MINI.
        if (gameType == null || gameType == GameType.BETTING_MINI || gameType == GameType.TAI_XIU) {
            return ResponseEntity.ok(catalog.bettingStrategies());
        }
        // SLOT exposes no selectable strategy (always FIXED); CARD_GAME / UP_DOWN —
        // no strategies implemented yet.
        return ResponseEntity.ok(List.of());
    }
}
