package com.vingame.bot.domain.bot.strategy.controller;

import com.vingame.bot.domain.bot.strategy.StrategyId;
import com.vingame.bot.domain.bot.strategy.dto.StrategyInfoDTO;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("api/v1/strategy")
public class StrategyController {

    @Operation(
            summary = "List available betting strategies",
            description = "Returns each StrategyId paired with its display name and description. Frontend uses this to populate the strategy-mix picker on the bot-group form.")
    @GetMapping("/")
    public ResponseEntity<List<StrategyInfoDTO>> list() {
        return ResponseEntity.ok(Arrays.stream(StrategyId.values()).map(StrategyInfoDTO::of).toList());
    }
}
