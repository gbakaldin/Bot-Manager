package com.vingame.bot.domain.bot.message;

import com.fasterxml.jackson.databind.jsontype.NamedType;
import com.vingame.bot.domain.bot.message.taixiu.MiniGameTaiXiuMessageTypes;
import com.vingame.bot.domain.bot.message.taixiu.TaiXiuEndGameMessage;
import com.vingame.bot.domain.bot.message.taixiu.TaiXiuStartGameMessage;
import com.vingame.bot.domain.bot.message.taixiu.TaiXiuSubscribeMessage;
import com.vingame.bot.domain.brand.model.ProductCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 3 verification (TAI_XIU_BOT plan AD-3/AD-4): the per-product Tai Xiu
 * provider registers its three inbound classes against the literal fixed cmd
 * strings {@code "1005"} / {@code "1002"} / {@code "1004"} with no offset
 * arithmetic (and no updateBet/bet registration), and the resolver exposes the
 * captured product via {@code resolveTaiXiu(ProductCode)}, throwing for the rest.
 */
@DisplayName("TaiXiuMessageTypes provider + resolver split")
class TaiXiuMessageTypesTest {

    /** The captured {@code MiniGame}/{@code taixiuPlugin} product is wired here (AD-4). */
    private static final ProductCode CAPTURED_PRODUCT = ProductCode.P_116;

    @Test
    @DisplayName("Fixed cmd constants are 1005/1002/1004 (inbound) + 1000 (bet, outbound)")
    void fixedCmdConstants() {
        assertThat(TaiXiuMessageTypes.SUBSCRIBE_CMD).isEqualTo(1005);
        assertThat(TaiXiuMessageTypes.START_GAME_CMD).isEqualTo(1002);
        assertThat(TaiXiuMessageTypes.END_GAME_CMD).isEqualTo(1004);
        assertThat(TaiXiuMessageTypes.BET_CMD).isEqualTo(1000);
    }

    @Test
    @DisplayName("Impl exposes the captured-product Tai Xiu classes; md5/updateBet are null in v1")
    void implAccessors() {
        TaiXiuMessageTypes types = new MiniGameTaiXiuMessageTypes();
        assertThat(types.subscribeType()).isEqualTo(TaiXiuSubscribeMessage.class);
        assertThat(types.startGameType()).isEqualTo(TaiXiuStartGameMessage.class);
        assertThat(types.endGameType()).isEqualTo(TaiXiuEndGameMessage.class);
        // No md5 StartGame variant captured (AD-10); no updateBet frame captured (OI-5).
        assertThat(types.startGameMd5Type()).isNull();
        assertThat(types.updateBetType()).isNull();
    }

    @Test
    @DisplayName("getTypeRegistrations has 3 inbound entries keyed on literal cmd strings, no offset, no bet/updateBet")
    void typeRegistrations() {
        NamedType[] regs = new MiniGameTaiXiuMessageTypes().getTypeRegistrations();

        assertThat(regs).hasSize(3);
        Map<String, Class<?>> byName = Arrays.stream(regs)
                .collect(Collectors.toMap(NamedType::getName, NamedType::getType));
        assertThat(byName).containsOnlyKeys("1005", "1002", "1004");
        assertThat(byName.get("1005")).isEqualTo(TaiXiuSubscribeMessage.class);
        assertThat(byName.get("1002")).isEqualTo(TaiXiuStartGameMessage.class);
        assertThat(byName.get("1004")).isEqualTo(TaiXiuEndGameMessage.class);
        // The bet (1000) is outbound-only (AD-12) and must NOT be registered.
        assertThat(byName).doesNotContainKey("1000");
    }

    @Test
    @DisplayName("resolveTaiXiu(captured product) returns the captured-product impl")
    void resolveTaiXiuReturnsImpl() {
        TaiXiuMessageTypes resolved = GameMessageTypesResolver.resolveTaiXiu(CAPTURED_PRODUCT);

        assertThat(resolved).isInstanceOf(MiniGameTaiXiuMessageTypes.class);
        assertThat(resolved.getTypeRegistrations()).hasSize(3);
    }

    @Test
    @DisplayName("resolveTaiXiu(null) throws IllegalArgumentException")
    void resolveTaiXiuNullThrows() {
        assertThatThrownBy(() -> GameMessageTypesResolver.resolveTaiXiu(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ProductCode cannot be null");
    }

    @ParameterizedTest(name = "resolveTaiXiu({0}) -> IllegalArgumentException")
    @EnumSource(value = ProductCode.class,
            names = {"P_066", "P_097", "P_098", "P_103", "P_105", "P_114", "P_118", "P_119", "P_222"})
    @DisplayName("resolveTaiXiu throws 'not yet implemented' for unimplemented product codes")
    void resolveTaiXiuThrowsForUnimplemented(ProductCode productCode) {
        assertThatThrownBy(() -> GameMessageTypesResolver.resolveTaiXiu(productCode))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(productCode.getCode())
                .hasMessageContaining("not yet implemented");
    }
}
