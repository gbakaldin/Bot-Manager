package com.vingame.bot.config.bot;

import com.vingame.bot.domain.bot.strategy.StrategyId;
import com.vingame.bot.domain.bot.strategy.slot.SlotStrategyId;
import com.vingame.bot.domain.game.model.Game;
import lombok.Builder;
import lombok.Value;

/**
 * Immutable value object containing complete bot configuration.
 * Combines runtime credentials with behavior settings and environment linkage.
 * <p>
 * This configuration object is passed to the bot factory to create
 * properly configured bot instances.
 */
@Value
@Builder
public class BotConfiguration {
    /**
     * Bot authentication credentials (username, password, fingerprint)
     */
    BotCredentials credentials;

    /**
     * ID of the environment this bot should connect to
     * Used to resolve environment-specific shared clients
     */
    String environmentId;

    /**
     * ID of the bot group this bot belongs to
     */
    String botGroupId;

    /**
     * Numeric product code ({@code ProductCode.getCode()}, e.g. {@code "116"}) this bot
     * runs under — the {@code product} MDC key / metric label of VIPTALK_ALERTING_V2
     * AD-V1.
     * <p>
     * Resolved <b>from the {@code Environment}</b> (a bot-app entity)
     * at group start, which is the single authority for the label: an environment is a
     * brand's gateway, so its product is the one alert routing must agree on. {@code Game}
     * carries its own {@code productCode}, but it is a nullable convenience copy that can
     * contradict the environment — and a game-scoped alert and an environment-scoped alert
     * for the same bots landing in two different product rooms is worse than either being
     * wrong. {@link #resolveProductCode()} is the only reader; it falls back to the game
     * so configurations built without an environment (tests, ad-hoc tooling) still label.
     * <p>
     * Nullable: an {@code Environment} document may predate {@code productCode}.
     */
    String productCode;

    /**
     * Index of this bot within its group (1-based).
     * Used for MDC logging context.
     */
    int botIndex;

    /**
     * Game configuration — offset, pluginName, md5, and the option-affinity
     * map ({@link Game#getEffectiveOptionAffinities()}) the assigned strategy
     * uses to pick a betting option.
     */
    Game game;

    /**
     * Betting behavior configuration
     */
    BotBehaviorConfig behaviorConfig;

    /**
     * Zone name for WebSocket messages (e.g., "MiniGame3")
     * Retrieved from environment configuration
     */
    String zoneName;

    long timeoutMillis;

    long watchdogTimeoutSeconds;

    /**
     * Strategy id assigned to this bot by the group's strategy mix at start.
     * <p>
     * Populated by {@code BotGroupBehaviorService.createSingleBot()} from the
     * fill-to-target assignment over {@code BotGroup.strategyMix}. Read by the
     * bot lifecycle to instantiate the per-bot {@code BettingStrategy} instance
     * (Phase 5) and surfaced on {@code BotHealthDTO} for observability.
     * <p>
     * May be {@code null} on legacy code paths that bypass the assignment
     * (no production caller); the {@code Bot} accessor tolerates that.
     */
    StrategyId strategyId;

    /**
     * Slot strategy id assigned to this bot (SLOT game type only).
     * <p>
     * Read by {@code SlotMachineBot.initializeSubclass()} to instantiate the
     * per-bot {@code SlotStrategy} via {@code SlotStrategyFactory}. A separate
     * field from {@link #strategyId} because the slot strategy family is
     * parallel and disjoint from the betting one (AD-9 of
     * {@code docs/plans/SLOT_MACHINE_BOT.md}).
     * <p>
     * Nullable — defaults to {@code SlotStrategyId.FIXED} when unset. Slot
     * strategy is out of the group strategy-mix UI for v1 (AD-10), so this is
     * only ever set directly, not via the fill-to-target assignment.
     */
    SlotStrategyId slotStrategyId;

    /**
     * The numeric product code to label this bot's meters and MDC with, or {@code null}
     * when neither the environment nor the game knows one.
     * <p>
     * One implementation, three callers ({@code Bot.initialize}, {@code
     * BotGroupRuntime.startBot} and the per-game gauge rows), so the null-guard over a
     * nullable Mongo field cannot drift between them and split a metric series.
     */
    public String resolveProductCode() {
        if (productCode != null && !productCode.isEmpty()) {
            return productCode;
        }
        if (game == null || game.getProductCode() == null) {
            return null;
        }
        return game.getProductCode().getCode();
    }
}
