package com.vingame.bot.domain.brand.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the {@link ProductCode} enum table that is read by {@code AuthStrategyFactory},
 * {@code BotGroupService.validateUsernameLength}, and
 * {@code EnvironmentClientRegistry.createClients}. A regression in any single row
 * (e.g. flipping {@code P_116}'s username cap, or dropping an {@code appId}) would
 * silently change behaviour in production today — this test makes such changes
 * fail loudly at build time.
 */
@DisplayName("ProductCode enum table")
class ProductCodeTest {

    /**
     * Provides (ProductCode, expected appId) tuples. {@code null} means "not yet
     * populated"; callers fall back to {@code Environment.getAppId()}.
     * Matches CLAUDE.md backlog as of 2026-06-16.
     */
    private static Stream<Arguments> appIdExpectations() {
        return Stream.of(
                Arguments.of(ProductCode.P_066, null),
                Arguments.of(ProductCode.P_097, "bc114097"),
                Arguments.of(ProductCode.P_098, "bc114098"),
                Arguments.of(ProductCode.P_103, null),
                Arguments.of(ProductCode.P_105, null),
                Arguments.of(ProductCode.P_114, null),
                Arguments.of(ProductCode.P_116, "bc115116"),
                Arguments.of(ProductCode.P_118, null),
                Arguments.of(ProductCode.P_119, null),
                Arguments.of(ProductCode.P_222, null)
        );
    }

    /**
     * Provides (ProductCode, expected usernameMaxLength). Only Tip has a known cap.
     */
    private static Stream<Arguments> usernameMaxLengthExpectations() {
        return Stream.of(
                Arguments.of(ProductCode.P_066, null),
                Arguments.of(ProductCode.P_097, null),
                Arguments.of(ProductCode.P_098, null),
                Arguments.of(ProductCode.P_103, null),
                Arguments.of(ProductCode.P_105, null),
                Arguments.of(ProductCode.P_114, null),
                Arguments.of(ProductCode.P_116, 12),
                Arguments.of(ProductCode.P_118, null),
                Arguments.of(ProductCode.P_119, null),
                Arguments.of(ProductCode.P_222, null)
        );
    }

    /**
     * Provides (ProductCode, expected name) — human-readable labels exposed to UI.
     */
    private static Stream<Arguments> nameExpectations() {
        return Stream.of(
                Arguments.of(ProductCode.P_066, "KCLUB"),
                Arguments.of(ProductCode.P_097, "BOM"),
                Arguments.of(ProductCode.P_098, "B52"),
                Arguments.of(ProductCode.P_103, "HIT"),
                Arguments.of(ProductCode.P_105, "IWIN"),
                Arguments.of(ProductCode.P_114, "RIK"),
                Arguments.of(ProductCode.P_116, "TIP"),
                Arguments.of(ProductCode.P_118, "NOHU"),
                Arguments.of(ProductCode.P_119, "WIN79"),
                Arguments.of(ProductCode.P_222, "BKK WIN")
        );
    }

    @ParameterizedTest(name = "{0} → appId={1}")
    @MethodSource("appIdExpectations")
    @DisplayName("appId is set as expected per product")
    void appId_isSetExpectedlyPerProduct(ProductCode pc, String expected) {
        assertThat(pc.getAppId()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} → usernameMaxLength={1}")
    @MethodSource("usernameMaxLengthExpectations")
    @DisplayName("usernameMaxLength is set as expected per product")
    void usernameMaxLength_isSetExpectedlyPerProduct(ProductCode pc, Integer expected) {
        assertThat(pc.getUsernameMaxLength()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} → name={1}")
    @MethodSource("nameExpectations")
    @DisplayName("name is the documented human-readable label")
    void name_isHumanReadableLabel(ProductCode pc, String expectedName) {
        assertThat(pc.getName()).isEqualTo(expectedName);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ProductCode.class)
    @DisplayName("fromCode resolves every product back to itself by its short code")
    void fromCode_resolvesByShortCode(ProductCode pc) {
        assertThat(ProductCode.fromCode(pc.getCode())).isSameAs(pc);
    }

    @Test
    @DisplayName("fromCode rejects unknown codes with a message that includes the bad value")
    void fromCode_throwsForUnknownCode() {
        assertThatThrownBy(() -> ProductCode.fromCode("999"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("999");
    }

    @Test
    @DisplayName("ProductCode declares exactly the documented set of products")
    void enum_declaresExpectedProducts() {
        // Lock the size so a new product addition forces an update to all the
        // tables above. A silent addition would otherwise sneak past the
        // parameterized tests (they only assert on the entries they enumerate).
        assertThat(ProductCode.values()).hasSize(10);
    }
}
