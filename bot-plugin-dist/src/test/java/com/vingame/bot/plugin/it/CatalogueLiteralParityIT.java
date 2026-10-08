package com.vingame.bot.plugin.it;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PLUGIN_HOT_RELOAD_3_4 <b>L-13</b>, the link between its two halves (QA-4b).
 * <p>
 * The modes are proven equivalent by pinning each one to a catalogue literal:
 * isolated mode to {@link ShippedBundle#CATALOGUE} ({@code IsolatedEquivalenceIT}), and
 * classpath mode to {@code ApplicationContextLoadsTest.SHIPPED_CATALOGUE} in
 * {@code bot-app}. The literal is in two places because this module cannot see
 * {@code bot-app}'s test classes and must not see the plugin classes (compliance-4ab,
 * 4b-v). Both copies say "keep identical", but nothing checked that they are. If a
 * bundle lost a provider in isolated mode only and someone "fixed" the failing IT by
 * editing {@code CATALOGUE}, both tests would pass while the modes differ. That is the
 * divergence L-13 exists to catch. This test reads the {@code bot-app} source and requires
 * the two literals to be equal.
 */
@DisplayName("L-13: the isolated and classpath catalogue literals are one string")
class CatalogueLiteralParityIT {

    private static final Path APP_TEST = Path.of(
            "../bot-app/src/test/java/com/vingame/bot/ApplicationContextLoadsTest.java");

    private static final Pattern DECLARATION = Pattern.compile(
            // The literal itself contains "; ", so the declaration ends at the first ';' that
            // follows a run of "..." parts joined by '+', not at the first ';' anywhere.
            "static\\s+final\\s+String\\s+SHIPPED_CATALOGUE\\s*="
                    + "((?:\\s*\\+?\\s*\"(?:[^\"\\\\]|\\\\.)*\")+)\\s*;");

    private static final Pattern STRING_LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

    @Test
    @DisplayName("ApplicationContextLoadsTest.SHIPPED_CATALOGUE equals ShippedBundle.CATALOGUE")
    void classpathLiteralEqualsIsolatedLiteral() throws IOException {
        assertThat(APP_TEST).as("bot-app's catalogue test, read from the reactor checkout").isRegularFile();
        String source = Files.readString(APP_TEST);

        Matcher declaration = DECLARATION.matcher(source);
        assertThat(declaration.find()).as("SHIPPED_CATALOGUE is declared in %s", APP_TEST).isTrue();
        StringBuilder literal = new StringBuilder();
        Matcher part = STRING_LITERAL.matcher(declaration.group(1));
        while (part.find()) {
            literal.append(part.group(1));
        }

        assertThat(literal.toString())
                .as("the classpath-mode literal in %s; keep it identical to ShippedBundle.CATALOGUE",
                        APP_TEST.getFileName())
                .isEqualTo(ShippedBundle.CATALOGUE);
    }
}
