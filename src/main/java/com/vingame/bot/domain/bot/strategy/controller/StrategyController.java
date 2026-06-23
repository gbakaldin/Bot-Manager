package com.vingame.bot.domain.bot.strategy.controller;

import com.vingame.bot.domain.bot.strategy.StrategyId;
import com.vingame.bot.domain.bot.strategy.dto.StrategyInfoDTO;
import com.vingame.bot.domain.game.model.GameType;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("api/v1/strategy")
public class StrategyController {

    @Operation(
            summary = "List available strategies for a game type",
            description = "Returns each strategy id paired with its display name and description, scoped to the "
                    + "supplied gameType. BETTING_MINI — or an absent gameType (backward-compatible default) — "
                    + "returns the betting strategies. SLOT exposes no selectable strategies (slots always run the "
                    + "basic FIXED strategy server-side) and returns an empty list, as do game types with no "
                    + "strategies implemented yet. The frontend uses this to populate the strategy picker on the "
                    + "bot-group form.")
    @GetMapping("/")
    public ResponseEntity<List<StrategyInfoDTO>> list(@RequestParam(required = false) GameType gameType) {
        if (gameType == null || gameType == GameType.BETTING_MINI) {
            return ResponseEntity.ok(Arrays.stream(StrategyId.values()).map(StrategyInfoDTO::of).toList());
        }
        // SLOT exposes no selectable strategy (always FIXED); TAI_XIU / CARD_GAME / UP_DOWN —
        // no strategies implemented yet.
        return ResponseEntity.ok(List.of());
    }
}
