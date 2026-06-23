package com.vingame.bot.domain.bot.strategy.controller;

import com.vingame.bot.common.exception.RestExceptionHandler;
import com.vingame.bot.domain.bot.strategy.StrategyId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(StrategyController.class)
@Import(RestExceptionHandler.class)
@DisplayName("StrategyController")
class StrategyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("GET /api/v1/strategy/ returns every StrategyId with id, displayName, description")
    void shouldReturnAllStrategiesWithDescriptions() throws Exception {
        mockMvc.perform(get("/api/v1/strategy/"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(StrategyId.values().length))
                .andExpect(jsonPath("$[*].id").value(hasItem("RANDOM")))
                .andExpect(jsonPath("$[*].displayName").value(hasItem("Random")))
                .andExpect(jsonPath("$[*].description").value(hasItem(StrategyId.RANDOM.getDescription())));
    }

    /**
     * MARTINGALE_STRATEGIES Architecture Decision A1 + Verification step A.2/A.3:
     * every Martingale enum entry surfaces in the listing with the verbatim
     * displayName and description strings the plan locked in. The frontend
     * dropdown reads these directly, so a typo in {@code StrategyId} would
     * silently change a user-visible string — pinning each one here means a
     * future copy edit shows up as a test failure and forces explicit
     * acknowledgement.
     */
    @Test
    @DisplayName("GET /api/v1/strategy/ exposes the 8 Martingale entries with their locked-in displayName / description")
    void shouldExposeMartingaleStrategiesWithLockedStrings() throws Exception {
        var perform = mockMvc.perform(get("/api/v1/strategy/"))
                .andExpect(status().isOk());

        StrategyId[] martingaleIds = {
                StrategyId.MARTINGALE_CLASSIC_CAUTIOUS,
                StrategyId.MARTINGALE_CLASSIC_AGGRESSIVE,
                StrategyId.PAROLI_CAUTIOUS,
                StrategyId.PAROLI_AGGRESSIVE,
                StrategyId.DALEMBERT_CAUTIOUS,
                StrategyId.DALEMBERT_AGGRESSIVE,
                StrategyId.FIBONACCI_CAUTIOUS,
                StrategyId.FIBONACCI_AGGRESSIVE,
        };
        for (StrategyId id : martingaleIds) {
            perform.andExpect(jsonPath("$[*].id").value(hasItem(id.name())))
                    .andExpect(jsonPath("$[*].displayName").value(hasItem(id.getDisplayName())))
                    .andExpect(jsonPath("$[*].description").value(hasItem(id.getDescription())));
        }
    }

    @Test
    @DisplayName("GET /api/v1/strategy/?gameType=BETTING_MINI returns the betting strategies (same as no-param)")
    void shouldReturnBettingStrategiesWhenGameTypeBettingMini() throws Exception {
        mockMvc.perform(get("/api/v1/strategy/").param("gameType", "BETTING_MINI"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(StrategyId.values().length))
                .andExpect(jsonPath("$[*].id").value(hasItem("RANDOM")))
                .andExpect(jsonPath("$[*].id").value(hasItem(StrategyId.MARTINGALE_CLASSIC_CAUTIOUS.name())));
    }

    @Test
    @DisplayName("GET /api/v1/strategy/?gameType=SLOT returns an empty list (slot strategy is not selectable, always FIXED)")
    void shouldReturnEmptyListForSlotGameType() throws Exception {
        mockMvc.perform(get("/api/v1/strategy/").param("gameType", "SLOT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("GET /api/v1/strategy/?gameType=TAI_XIU returns an empty list (no strategies implemented yet)")
    void shouldReturnEmptyListForUnimplementedGameType() throws Exception {
        mockMvc.perform(get("/api/v1/strategy/").param("gameType", "TAI_XIU"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
