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
}
