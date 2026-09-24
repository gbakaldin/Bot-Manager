package com.vingame.bot.infrastructure.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins which {@code update-fullname} statuses {@link ApiGatewayClient#setDisplayName}
 * classifies as "name taken, re-roll". The RIK/P_114 gateway answers {@code EXISTED}
 * where others answer {@code INVALID}; treating only the latter as a conflict aborted
 * the retry loop on the first collision (2026-09-18, 19/100 bots nameless, room frozen).
 */
@DisplayName("ApiGatewayClient.isDisplayNameTaken")
class ApiGatewayClientDisplayNameTakenStatusTest {

    @ParameterizedTest
    @ValueSource(strings = {"INVALID", "EXISTED"})
    @DisplayName("both gateway spellings of 'already used' are a re-rollable conflict")
    void conflictStatusesAreTaken(String status) {
        assertThat(ApiGatewayClient.isDisplayNameTaken(status)).isTrue();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"OK", "ERROR", "existed", "invalid", ""})
    @DisplayName("success, unknown errors and case variants are not conflicts")
    void otherStatusesAreNotTaken(String status) {
        assertThat(ApiGatewayClient.isDisplayNameTaken(status)).isFalse();
    }
}
