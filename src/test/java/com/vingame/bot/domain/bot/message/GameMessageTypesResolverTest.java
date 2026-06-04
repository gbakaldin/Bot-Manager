package com.vingame.bot.domain.bot.message;

import com.fasterxml.jackson.databind.jsontype.NamedType;
import com.vingame.bot.domain.bot.message.g2.bom.BomGameMessageTypes;
import com.vingame.bot.domain.bot.message.g2.bom.BomStartGameMd5Message;
import com.vingame.bot.domain.bot.message.g4.nohu.NohuGameMessageTypes;
import com.vingame.bot.domain.brand.model.ProductCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("GameMessageTypesResolver")
class GameMessageTypesResolverTest {

    @Test
    @DisplayName("Should resolve P_097 to BomGameMessageTypes")
    void shouldResolveP097ToBom() {
        GameMessageTypes result = GameMessageTypesResolver.resolve(ProductCode.P_097);

        assertThat(result).isInstanceOf(BomGameMessageTypes.class);
    }

    @Test
    @DisplayName("Should resolve P_098 to BomGameMessageTypes")
    void shouldResolveP098ToBom() {
        GameMessageTypes result = GameMessageTypesResolver.resolve(ProductCode.P_098);

        assertThat(result).isInstanceOf(BomGameMessageTypes.class);
    }

    @Test
    @DisplayName("Should resolve P_118 to NohuGameMessageTypes")
    void shouldResolveP118ToNohu() {
        GameMessageTypes result = GameMessageTypesResolver.resolve(ProductCode.P_118);

        assertThat(result).isInstanceOf(NohuGameMessageTypes.class);
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when productCode is null")
    void shouldThrowWhenNull() {
        assertThatThrownBy(() -> GameMessageTypesResolver.resolve(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ProductCode cannot be null");
    }

    @ParameterizedTest(name = "resolve({0}) -> IllegalArgumentException")
    @EnumSource(value = ProductCode.class, names = {"P_066", "P_103", "P_105", "P_114", "P_116", "P_119", "P_222"})
    @DisplayName("Should throw IllegalArgumentException for unimplemented product codes")
    void shouldThrowForUnimplementedProductCodes(ProductCode productCode) {
        assertThatThrownBy(() -> GameMessageTypesResolver.resolve(productCode))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(productCode.getCode())
                .hasMessageContaining("not yet implemented");
    }

    @Test
    @DisplayName("getTypeRegistrations produces correct CMD = CODE + OFFSET names and swaps StartGame on md5")
    void getTypeRegistrationsProducesCorrectCmdValues() {
        GameMessageTypes types = new BomGameMessageTypes();

        // 4 entries: SUBSCRIBE (3000+2000), UPDATE_BET (3002+2000), START_GAME (3005+2000), END_GAME (3006+2000)
        NamedType[] regs = types.getTypeRegistrations(2000, false);
        assertThat(regs).hasSize(4);
        Set<String> names = Arrays.stream(regs).map(NamedType::getName).collect(Collectors.toSet());
        assertThat(names).containsExactlyInAnyOrder("5000", "5002", "5005", "5006");

        // md5=true swaps the START_GAME entry to BomStartGameMd5Message; cmd value is unchanged.
        NamedType[] regsMd5 = types.getTypeRegistrations(2000, true);
        assertThat(regsMd5).hasSize(4);
        NamedType startGameReg = Arrays.stream(regsMd5)
                .filter(r -> "5005".equals(r.getName()))
                .findFirst().orElseThrow();
        assertThat(startGameReg.getType()).isEqualTo(BomStartGameMd5Message.class);
    }
}
