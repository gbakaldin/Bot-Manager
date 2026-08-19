package com.vingame.bot.infrastructure.logging;

import com.vingame.bot.common.logging.ScopedDebugRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares the one {@link ScopedDebugRegistry} singleton (LOG_VOLUME_TIERING Phase 2).
 * <p>
 * The registry itself lives in {@code bot-api} and is deliberately Spring-free — it is
 * shared by three consumers in three modules ({@code ScopedDebugFilter} in bot-api, the
 * installer and REST surface here, {@code ScopedDebugEscalator} in bot-engine), and the
 * contract module has no other reason to depend on a stereotype. Wiring it as a
 * {@code @Bean} keeps that property while still giving every consumer the same instance
 * through ordinary constructor injection.
 */
@Configuration
public class ScopedDebugConfig {

    @Bean
    public ScopedDebugRegistry scopedDebugRegistry(
            @Value("${bot.logging.scoped-debug.max-scopes:50}") int maxScopes) {
        return new ScopedDebugRegistry(maxScopes);
    }
}
