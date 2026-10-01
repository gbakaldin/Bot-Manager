package com.vingame.bot.domain.bot.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GATEWAY_REQUEST_BUDGET A33 review: a game bot's {@code onStart} must not log its own session-setup
 * failure.
 * <p>
 * {@code BettingMiniGameBot} and {@code SlotMachineBot} used to catch it, log ERROR with a stack
 * trace and rethrow. Since a failed first read is handed to the reconnect loop, that line fired on
 * <b>every</b> reconnect attempt — ~70 stack traces per bot over the 51-minute budget, ~7,000 for a
 * 100-bot group, all on track 1. {@code Bot.start()} now decides whether the failure is called off,
 * retried or final and logs it once at the matching level ({@code BotFirstBalanceReadFailureTest}
 * pins those levels). A source scan, because exercising either subclass's {@code onStart} needs a
 * full game fixture and the regression is a single re-added line.
 */
@DisplayName("Game bots leave session-setup failure logging to Bot.start() (A33)")
class SessionSetupLogGuardTest {

    private static final Path CORE = Path.of("src/main/java/com/vingame/bot/domain/bot/core");

    @ParameterizedTest
    @ValueSource(strings = {"BettingMiniGameBot.java", "SlotMachineBot.java"})
    void onStartDoesNotLogItsOwnFailure(String file) throws IOException {
        String source = Files.readString(CORE.resolve(file));
        int start = source.indexOf("protected void onStart()");
        assertThat(start).as("anti-vacuity: the scan finds onStart in %s", file).isNotNegative();
        int end = source.indexOf("\n    }\n", start);
        String onStart = source.substring(start, end);

        assertThat(onStart).contains("onNewSession();");
        assertThat(onStart)
                .as("%s.onStart must let the failure propagate to Bot.start() unlogged", file)
                .doesNotContain("log.error(")
                .doesNotContain("log.warn(")
                .doesNotContain("catch (");
    }
}
