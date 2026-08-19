package com.vingame.bot.domain.logging.controller;

import com.vingame.bot.common.logging.ScopedDebugRegistry;
import com.vingame.bot.infrastructure.logging.ScopedDebugInstaller;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP contract of {@code /api/v1/logging} (LOG_VOLUME_TIERING Phase 2, AD-11).
 * <p>
 * <b>What is worth pinning here</b> is the TTL policy, because it is the whole safety
 * argument for handing this endpoint to an operator: there is no request shape that leaves
 * DEBUG on indefinitely. A caller who names no TTL gets the configured default; one who
 * names an excessive TTL is refused rather than quietly clamped (a silent clamp teaches
 * operators that the number they typed is what they got); one who names zero or a negative
 * is refused; and one who asks while the mechanism is not armed gets a 400 instead of a 200
 * that changes nothing.
 */
@WebMvcTest(LogLevelController.class)
@DisplayName("LogLevelController — scoped DEBUG over HTTP (AD-11)")
class LogLevelControllerTest {

    private static final String GROUP = "0c9a93cb-20d6-4f57-9dbc-5c315dcf52e2";

    @TestConfiguration
    static class RealRegistry {
        /** A real registry: its refusal behaviour is half of what this controller answers. */
        @Bean
        ScopedDebugRegistry scopedDebugRegistry() {
            return new ScopedDebugRegistry(2);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ScopedDebugRegistry registry;

    @MockitoBean
    private ScopedDebugInstaller installer;

    @BeforeEach
    void setUp() {
        registry.clear();
        when(installer.isInstalled()).thenReturn(true);
    }

    @Test
    @DisplayName("POST enables the group and answers 200 with the expiry")
    void enableReturnsExpiry() throws Exception {
        mockMvc.perform(post("/api/v1/logging/debug/{id}", GROUP).param("minutes", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.botGroupId").value(GROUP))
                .andExpect(jsonPath("$.expiresAt").exists());

        assertThat(registry.isEnabled(GROUP)).isTrue();
    }

    @Test
    @DisplayName("POST with no minutes applies the configured default TTL")
    void defaultTtlIsApplied() throws Exception {
        mockMvc.perform(post("/api/v1/logging/debug/{id}", GROUP))
                .andExpect(status().isOk());

        assertThat(registry.activeScopes()).containsKey(GROUP);
    }

    @Test
    @DisplayName("POST above the TTL cap is refused — 400, not a silent clamp")
    void ttlAboveCapIsRefused() throws Exception {
        mockMvc.perform(post("/api/v1/logging/debug/{id}", GROUP).param("minutes", "600"))
                .andExpect(status().isBadRequest());

        assertThat(registry.isEnabled(GROUP)).isFalse();
    }

    @Test
    @DisplayName("POST with a non-positive TTL is refused — there is no permanent form")
    void nonPositiveTtlIsRefused() throws Exception {
        mockMvc.perform(post("/api/v1/logging/debug/{id}", GROUP).param("minutes", "0"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/logging/debug/{id}", GROUP).param("minutes", "-1"))
                .andExpect(status().isBadRequest());

        assertThat(registry.isAnyEnabled()).isFalse();
    }

    @Test
    @DisplayName("POST past the concurrent-scope cap is refused")
    void concurrentScopeCapIsEnforced() throws Exception {
        mockMvc.perform(post("/api/v1/logging/debug/{id}", "g1")).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/logging/debug/{id}", "g2")).andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/logging/debug/{id}", "g3"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST while the mechanism is not armed answers 400, not a lying 200")
    void refusesWhenNotInstalled() throws Exception {
        when(installer.isInstalled()).thenReturn(false);

        mockMvc.perform(post("/api/v1/logging/debug/{id}", GROUP))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("DELETE turns a scope off; DELETE of an unknown group is 404")
    void deleteDisables() throws Exception {
        registry.enable(GROUP, Duration.ofMinutes(5));

        mockMvc.perform(delete("/api/v1/logging/debug/{id}", GROUP))
                .andExpect(status().isNoContent());
        assertThat(registry.isEnabled(GROUP)).isFalse();

        mockMvc.perform(delete("/api/v1/logging/debug/{id}", GROUP))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET lists the policy and the active scopes")
    void listReportsPolicyAndScopes() throws Exception {
        registry.enable(GROUP, Duration.ofMinutes(5));

        mockMvc.perform(get("/api/v1/logging/debug"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.defaultMinutes").value(15))
                .andExpect(jsonPath("$.maxMinutes").value(120))
                .andExpect(jsonPath("$.maxScopes").value(2))
                .andExpect(jsonPath("$.scopes[0].botGroupId").value(GROUP))
                .andExpect(jsonPath("$.scopes[0].expiresAt").exists());
    }

    @Test
    @DisplayName("GET on an idle instance reports no scopes rather than failing")
    void listWithNoScopes() throws Exception {
        mockMvc.perform(get("/api/v1/logging/debug"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scopes").isEmpty());
    }
}
