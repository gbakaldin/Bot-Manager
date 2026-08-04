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
                    + "supplied gameType. BETTING_MINI and TAI_XIU — or an absent gameType (backward-compatible "
                    + "default) — return the betting strategies (Tai Xiu reuses the betting strategy family, "
                    + "TAI_XIU_BOT plan AD-6). SLOT exposes no selectable strategies (slots always run the "
                    + "basic FIXED strategy server-side) and returns an empty list, as do game types with no "
                    + "strategies implemented yet. The frontend uses this to populate the strategy picker on the "
                    + "bot-group form.")
    @GetMapping("/")
    public ResponseEntity<List<StrategyInfoDTO>> list(@RequestParam(required = false) GameType gameType) {
        // TAI_XIU reuses the betting strategy family (AD-6), so it lists the same
        // strategies as BETTING_MINI.
        if (gameType == null || gameType == GameType.BETTING_MINI || gameType == GameType.TAI_XIU) {
            return ResponseEntity.ok(Arrays.stream(StrategyId.values()).map(StrategyInfoDTO::of).toList());
        }
        // SLOT exposes no selectable strategy (always FIXED); CARD_GAME / UP_DOWN —
        // no strategies implemented yet.
        return ResponseEntity.ok(List.of());
    }
}
