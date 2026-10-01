package com.vingame.bot.infrastructure.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA, GATEWAY_REQUEST_BUDGET Phase 5: <b>no raw gateway response body is an argument of an INFO,
 * WARN or ERROR line in {@link ApiGatewayClient}</b>.
 * <p>
 * {@code ApiGatewayClientBlockTest} proves it behaviourally for the request kinds it drives through
 * the stub. This is the structural half: a source scan, so a WARN added next month that logs
 * {@code response.body()} or {@code responseBody} fails the build rather than waiting for a block to
 * put five kilobytes of Cloudflare HTML on track 1 once per bot per request — the exact shape the
 * Phase 5 {@code bodyForLog} exists to prevent. Bodies at DEBUG are fine (track 2 only, AD-24);
 * {@code bodyForLog(...)} is the sanctioned way to put one in a WARN.
 * <p>
 * {@code PerBotInfoLogGuardTest} does not cover this class (it is environment-scoped, not per-bot),
 * which is why the rule needs its own guard.
 */
@DisplayName("QA — ApiGatewayClient never logs a raw response body at INFO or above")
class ApiGatewayClientLogBodyGuardTest {

    private static final Path SOURCE =
            Path.of("src/main/java/com/vingame/bot/infrastructure/client/ApiGatewayClient.java");

    /** {@code log.info(}/{@code log.warn(}/{@code log.error(} up to the statement's semicolon. */
    private static final Pattern LOUD_LOG = Pattern.compile("\\blog\\.(info|warn|error)\\s*\\((.*?)\\);",
            Pattern.DOTALL);
    /** A raw body: {@code responseBody} or {@code .body()} ({@code bodyForLog(response)} is neither). */
    private static final Pattern RAW_BODY = Pattern.compile("\\bresponseBody\\b|\\.body\\(\\)");

    static List<String> offenders(String source) {
        String code = source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
        List<String> found = new ArrayList<>();
        Matcher m = LOUD_LOG.matcher(code);
        while (m.find()) {
            if (RAW_BODY.matcher(m.group(2)).find()) {
                found.add(m.group().replaceAll("\\s+", " "));
            }
        }
        return found;
    }

    @Test
    @DisplayName("every INFO/WARN/ERROR call in ApiGatewayClient is free of raw response bodies")
    void noRawBodyAtInfoOrAbove() throws IOException {
        assertThat(SOURCE).as("run from the bot-engine module directory").exists();
        String source = Files.readString(SOURCE);

        assertThat(LOUD_LOG.matcher(source).find()).as("the scan matched at least one loud log call").isTrue();
        assertThat(offenders(source))
                .as("a raw body at INFO+ lands on track 1 and in Loki; use bodyForLog(response)")
                .isEmpty();
    }

    @Test
    @DisplayName("the scanner has teeth: a raw body in a WARN is caught, at DEBUG it is not")
    void theScannerHasTeeth() {
        String offending = """
                log.warn("[BotDeposit] non-200 for {} — body: {}",
                        username, responseBody);
                log.error("x {}", response.body());
                log.debug("[VerifyToken] response HTTP {} | body: {}", status, responseBody);
                log.warn("ok {}", bodyForLog(response));
                """;

        assertThat(offenders(offending)).hasSize(2);
    }
}
