package com.vingame.bot.infrastructure.plugin;

import com.vingame.bot.domain.bot.message.MessageTypesImpl;
import com.vingame.bot.domain.bot.message.g2.bom.BomGameMessageTypes;
import com.vingame.bot.domain.bot.message.g3.tip.TipGameMessageTypes;
import com.vingame.bot.domain.bot.message.slot.SlotMessageTypesImpl;
import com.vingame.bot.domain.bot.strategy.BetContext;
import com.vingame.bot.domain.bot.strategy.BetDecision;
import com.vingame.bot.domain.bot.strategy.BettingStrategy;
import com.vingame.bot.domain.bot.strategy.RandomBehaviorStrategy;
import com.vingame.bot.domain.bot.strategy.RoundResult;
import com.vingame.bot.domain.bot.strategy.StrategyImpl;
import com.vingame.bot.domain.bot.strategy.slot.FixedBetStrategy;
import com.vingame.bot.domain.bot.strategy.slot.RandomBetStrategy;
import com.vingame.bot.domain.game.model.GameType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PLUGIN_HOT_RELOAD_3_4 D-9 / D-10: {@link PluginRegistries#build} builds the three
 * registries from one bundle, together, and is the single point at which a bundle is
 * accepted or rejected <em>as a whole</em>.
 */
@DisplayName("PluginRegistries.build — one bundle, three registries, one verdict (D-9, D-10)")
class PluginRegistriesBuildTest {

    @Test
    @DisplayName("every registry and the type factory come from the one bundle")
    void everythingComesFromTheOneBundle() {
        StubPluginBundle bundle = StubPluginBundle.of(null, List.of(
                new RandomBehaviorStrategy(), new FixedBetStrategy(), new RandomBetStrategy(),
                new BomGameMessageTypes(), new SlotMessageTypesImpl()));

        PluginRegistries registries = PluginRegistries.build(bundle);

        assertThat(registries.bundle()).isSameAs(bundle);
        assertThat(registries.typeFactory())
                .as("the bundle owns the factory; the record only exposes it")
                .isSameAs(bundle.typeFactory());
        assertThat(registries.bettingStrategies().registeredKeys()).containsExactly("RANDOM");
        assertThat(registries.slotStrategies().registeredKeys()).containsExactly("FIXED", "RANDOM");
        assertThat(registries.messageTypes().registeredBettingMiniProducts()).containsExactly("097", "098");
        assertThat(registries.messageTypes().hasSlotProvider()).isTrue();
    }

    @Test
    @DisplayName("the bundle's type factory is private, not the shared default")
    void theTypeFactoryIsNotTheSharedDefault() {
        PluginRegistries registries = PluginRegistries.build(StubPluginBundle.of(null, List.of()));

        assertThat(registries.typeFactory())
                .isNotSameAs(com.fasterxml.jackson.databind.type.TypeFactory.defaultInstance());
    }

    @Test
    @DisplayName("a duplicate strategy key rejects the whole bundle")
    void duplicateStrategyKeyRejectsTheBundle() {
        StubPluginBundle bundle = StubPluginBundle.of(null, List.of(
                new RandomBehaviorStrategy(), new SecondRandom(), new BomGameMessageTypes()));

        assertThatThrownBy(() -> PluginRegistries.build(bundle))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate @StrategyImpl(RANDOM)");
    }

    @Test
    @DisplayName("a duplicate product claim rejects the whole bundle, even with valid strategies")
    void duplicateProductRejectsTheBundle() {
        StubPluginBundle bundle = StubPluginBundle.of(null, List.of(
                new RandomBehaviorStrategy(), new BomGameMessageTypes(), new TipClaiming097()));

        assertThatThrownBy(() -> PluginRegistries.build(bundle))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate @MessageTypesImpl")
                .hasMessageContaining("097");
    }

    @Test
    @DisplayName("a strategy bean with no annotation stays a WARN-and-skip, not a rejection")
    void unannotatedStrategyIsSkippedNotRejected() {
        StubPluginBundle bundle = StubPluginBundle.of(null, List.of(
                new RandomBehaviorStrategy(), new Unannotated()));

        assertThatCode(() -> PluginRegistries.build(bundle)).doesNotThrowAnyException();
        assertThat(PluginRegistries.build(bundle).bettingStrategies().registeredKeys())
                .containsExactly("RANDOM");
    }

    @Test
    @DisplayName("no registry or component may be null")
    void componentsAreRequired() {
        PluginRegistries built = PluginRegistries.build(StubPluginBundle.of(null, List.of()));

        assertThatThrownBy(() -> new PluginRegistries(null, built.bettingStrategies(),
                built.slotStrategies(), built.messageTypes()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("bundle");
        assertThatThrownBy(() -> new PluginRegistries(built.bundle(), built.bettingStrategies(),
                built.slotStrategies(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("messageTypes");
    }

    @Test
    @DisplayName("the type factory is always the bundle's own — a record cannot pair it with another cache (review-4a)")
    void typeFactoryIsTheBundles() {
        StubPluginBundle bundle = StubPluginBundle.of(null, List.of());
        PluginRegistries built = PluginRegistries.build(bundle);
        PluginRegistries handBuilt = new PluginRegistries(bundle, built.bettingStrategies(),
                built.slotStrategies(), built.messageTypes());

        assertThat(built.typeFactory()).isSameAs(bundle.typeFactory());
        assertThat(handBuilt.typeFactory()).isSameAs(bundle.typeFactory());
        assertThat(built.typeFactory())
                .as("never the shared default: that cache pins plugin types (spike 3a)")
                .isNotSameAs(com.fasterxml.jackson.databind.type.TypeFactory.defaultInstance());
    }

    // ------------------------------------------------------------------ fixtures

    @StrategyImpl("RANDOM")
    private static final class SecondRandom implements BettingStrategy {
        @Override public void onRoundEnd(RoundResult result) { }
        @Override public Optional<BetDecision> decide(BetContext ctx) { return Optional.empty(); }
    }

    private static final class Unannotated implements BettingStrategy {
        @Override public void onRoundEnd(RoundResult result) { }
        @Override public Optional<BetDecision> decide(BetContext ctx) { return Optional.empty(); }
    }

    @MessageTypesImpl(gameType = GameType.BETTING_MINI, products = "097")
    static class TipClaiming097 extends TipGameMessageTypes {
    }
}
