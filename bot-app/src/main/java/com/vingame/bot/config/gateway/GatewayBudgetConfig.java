package com.vingame.bot.config.gateway;

import com.vingame.bot.common.gateway.RequestTier;
import com.vingame.bot.infrastructure.gateway.GatewayBudgetMode;
import com.vingame.bot.infrastructure.gateway.GatewayBudgetSettings;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

/**
 * Binds {@code bot.gateway.budget.*} into the one {@link GatewayBudgetSettings} bean that
 * every per-environment budget is built from (GATEWAY_REQUEST_BUDGET AD-5).
 * <p>
 * <b>Validation happens in {@link GatewayBudgetSettings}' constructor, and therefore here,
 * at context refresh.</b> A non-monotonic set of ceilings or a hard cap at Cloudflare's own
 * limit is a policy that cannot protect anything, and the only acceptable moment to find
 * that out is startup — not the moment the window fills.
 * <p>
 * The tier keys are <b>lower-case</b> ({@code tier.default.ceiling}) precisely so they
 * relaxed-bind from compose the way {@code BOT_RECOVERY_ENABLED} does:
 * {@code BOT_GATEWAY_BUDGET_TIER_DEFAULT_CEILING}. {@code mode} is the one an operator
 * actually flips, through {@code BOT_GATEWAY_BUDGET_MODE=${GATEWAY_BUDGET_MODE:-observe}} in
 * {@code docker-compose.yml} — set it per box in the uncommitted {@code secrets.env} /
 * {@code .env} merge, never in the compose file.
 */
@Configuration
public class GatewayBudgetConfig {

    @Bean
    public GatewayBudgetSettings gatewayBudgetSettings(
            @Value("${bot.gateway.budget.mode:observe}") String mode,
            @Value("${bot.gateway.budget.window:5m}") Duration window,
            @Value("${bot.gateway.budget.hard-cap:900}") int hardCap,
            @Value("${bot.gateway.budget.tier.default.ceiling:500}") int defaultCeiling,
            @Value("${bot.gateway.budget.tier.prioritized.ceiling:750}") int prioritizedCeiling,
            @Value("${bot.gateway.budget.tier.essential.ceiling:900}") int essentialCeiling,
            @Value("${bot.gateway.budget.tier.default.max-wait:30s}") Duration defaultMaxWait,
            @Value("${bot.gateway.budget.tier.prioritized.max-wait:10m}") Duration prioritizedMaxWait,
            @Value("${bot.gateway.budget.tier.essential.max-wait:0}") Duration essentialMaxWait,
            @Value("${bot.gateway.budget.registration.max-wait:15m}") Duration registrationMaxWait,
            @Value("${bot.gateway.budget.count-ws-upgrades:true}") boolean countWsUpgrades,
            @Value("${bot.gateway.budget.block-cooldown:15m}") Duration blockCooldown) {

        Map<RequestTier, Integer> ceilings = new EnumMap<>(RequestTier.class);
        ceilings.put(RequestTier.DEFAULT, defaultCeiling);
        ceilings.put(RequestTier.PRIORITIZED, prioritizedCeiling);
        ceilings.put(RequestTier.ESSENTIAL, essentialCeiling);

        Map<RequestTier, Duration> maxWaits = new EnumMap<>(RequestTier.class);
        maxWaits.put(RequestTier.DEFAULT, defaultMaxWait);
        maxWaits.put(RequestTier.PRIORITIZED, prioritizedMaxWait);
        maxWaits.put(RequestTier.ESSENTIAL, essentialMaxWait);

        return new GatewayBudgetSettings(
                GatewayBudgetMode.parse(mode),
                window,
                hardCap,
                ceilings,
                maxWaits,
                registrationMaxWait,
                countWsUpgrades,
                blockCooldown);
    }
}
