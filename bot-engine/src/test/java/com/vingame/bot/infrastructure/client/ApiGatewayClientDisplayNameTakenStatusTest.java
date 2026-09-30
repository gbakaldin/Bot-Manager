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
 *
 * <p><b>Matched case-insensitively since review T2</b>, which is a behaviour change and is
 * recorded as one: this was the only case-sensitive comparison against
 * {@code ApiGatewayClient.STATUS_EXISTED} in the class ({@code registerOne} has always used
 * {@code equalsIgnoreCase}), and the asymmetry pointed the wrong way. A brand answering
 * {@code existed} instead of {@code EXISTED} fell through to the generic throw and reproduced the
 * 2026-09-18 freeze; read as a conflict it costs one re-rolled name. We do not control the
 * envelope's casing, so the fail-safe reading wins.
 */
@DisplayName("ApiGatewayClient.isDisplayNameTaken")
class ApiGatewayClientDisplayNameTakenStatusTest {

    @ParameterizedTest
    @ValueSource(strings = {"INVALID", "EXISTED", "existed", "invalid", "Existed"})
    @DisplayName("both gateway spellings of 'already used' are a re-rollable conflict, any casing")
    void conflictStatusesAreTaken(String status) {
        assertThat(ApiGatewayClient.isDisplayNameTaken(status)).isTrue();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"OK", "ERROR", "", "EXIST", "INVALID_TOKEN"})
    @DisplayName("success and unknown errors are not conflicts")
    void otherStatusesAreNotTaken(String status) {
        // Not a prefix or substring match either: EXIST and INVALID_TOKEN are different
        // envelopes, and re-rolling a name in response to an auth failure would hide it behind
        // five collisions' worth of retries.
        assertThat(ApiGatewayClient.isDisplayNameTaken(status)).isFalse();
    }
}
