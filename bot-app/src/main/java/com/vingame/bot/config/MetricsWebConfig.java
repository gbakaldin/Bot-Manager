package com.vingame.bot.config;

import com.vingame.bot.domain.metrics.ratelimit.MetricsRateLimitInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the {@link MetricsRateLimitInterceptor} on the public metrics
 * endpoints only (METRICS_API Phase 5 / AD-9). Scoped to
 * {@code /api/v1/metrics/**} so the rate limit never touches the rest of the
 * (internal/admin) API surface.
 */
@Configuration
public class MetricsWebConfig implements WebMvcConfigurer {

    private final MetricsRateLimitInterceptor rateLimitInterceptor;

    public MetricsWebConfig(MetricsRateLimitInterceptor rateLimitInterceptor) {
        this.rateLimitInterceptor = rateLimitInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/api/v1/metrics/**");
    }
}
