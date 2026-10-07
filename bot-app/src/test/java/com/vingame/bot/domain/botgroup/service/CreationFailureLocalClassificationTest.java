package com.vingame.bot.domain.botgroup.service;

import com.vingame.bot.common.exception.UpstreamLoginException;
import com.vingame.bot.infrastructure.client.ApiGatewayClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GATEWAY_REQUEST_BUDGET A33 fix round (FR-3): a bot whose login found no free stream on our own
 * HTTP/2 connection was never refused by the gateway, so {@code bot_creation_failures_total} labels
 * it {@code local}, not {@code auth}. It arrives as an {@link UpstreamLoginException}, which is why
 * the arm has to come first.
 */
@DisplayName("classifyCreationFailure — a login that never left the JVM is 'local', not 'auth' (A33)")
class CreationFailureLocalClassificationTest {

    @Test
    void aStreamWaitTimeoutIsLocal() {
        UpstreamLoginException neverSent = new UpstreamLoginException(
                "Login failed for user 'rikcoins54': no free gateway stream",
                new ApiGatewayClient.StreamWaitTimeoutException("no free gateway stream within PT10S"));
        assertThat(BotGroupBehaviorService.classifyCreationFailure(neverSent)).isEqualTo("local");
    }

    @Test
    void aGatewayRefusalIsStillAuth() {
        assertThat(BotGroupBehaviorService.classifyCreationFailure(new UpstreamLoginException(
                "Login failed for user 'rikcoins54': wrong password", null))).isEqualTo("auth");
    }

    @Test
    @DisplayName("an unpublished plugin bundle (JVM shutdown) is 'shutdown', not the 'validation' of its supertype (review-4a)")
    void anUnpublishedBundleIsShutdown() {
        assertThat(BotGroupBehaviorService.classifyCreationFailure(
                new com.vingame.bot.infrastructure.plugin.PluginUnpublishedException("builtin")))
                .isEqualTo("shutdown");
        assertThat(BotGroupBehaviorService.classifyCreationFailure(new IllegalStateException("x")))
                .isEqualTo("validation");
    }
}
