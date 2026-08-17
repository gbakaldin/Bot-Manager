package com.vingame.bot.domain.alert.service;

import com.vingame.bot.domain.alert.model.Alert;
import com.vingame.bot.domain.alert.model.AlertAudience;
import com.vingame.bot.domain.alert.model.AlertRegister;
import com.vingame.bot.domain.alert.model.AlertSeverity;
import com.vingame.bot.domain.brand.model.ProductCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlertMessageFormatterTest {

    @Test
    void format_rendersHeaderTitleBodyAndSource() {
        String message = new AlertMessageFormatter("staging").format(Alert.forProduct(
                ProductCode.P_116, AlertSeverity.CRITICAL, "Bot group DEAD",
                "Group tptxg2 — 18/18 bots dead", "prometheus"));

        assertEquals("""
                🔴 CRITICAL · TIP (116) · staging
                Bot group DEAD
                Group tptxg2 — 18/18 bots dead
                — via prometheus""", message);
    }

    @Test
    void format_omitsInstanceSegmentWhenLabelIsBlank() {
        String message = new AlertMessageFormatter("  ").format(
                Alert.announcement(AlertSeverity.INFO, "Maintenance window", null, null));

        assertEquals("""
                ℹ️ INFO
                Maintenance window""", message);
    }

    @Test
    void format_omitsProductSegmentForAnnouncements() {
        String message = new AlertMessageFormatter("prod").format(
                Alert.announcement(AlertSeverity.WARNING, "Deploy starting", "~5 minutes", "operator"));

        assertFalse(message.contains("("), "no product parenthetical on a fleet-wide announcement");
        assertEquals("""
                ⚠️ WARNING · prod
                Deploy starting
                ~5 minutes
                — via operator""", message);
    }

    @Test
    void format_customerRegisterRendersOnlyTheMarkerProductInstanceAndPublicSummary() {
        Alert alert = new Alert(AlertSeverity.WARNING, "BotManagerDown",
                "Prometheus has failed to scrape bot-manager for 2 minutes (env env-1)",
                ProductCode.P_116, "prometheus", AlertAudience.BOTH,
                "ALERT! Bot Management application is experiencing issues, backend team is aware "
                        + "and will deliver fixes soon.");

        String message = new AlertMessageFormatter("prod").format(alert, AlertRegister.CUSTOMER);

        assertEquals("""
                ⚠️ TIP (116) · prod
                ALERT! Bot Management application is experiencing issues, backend team is aware \
                and will deliver fixes soon.""", message);
        // AD-V6: none of the technical register may bleed through.
        assertFalse(message.contains("WARNING"), message);
        assertFalse(message.contains("env-1"), message);
        assertFalse(message.contains("via prometheus"), message);
    }

    @Test
    void format_customerRegisterIsBlankWithoutAPublicSummary() {
        // Fail-closed backstop: AlertRouter gates on this, and a blank body makes
        // VipTalkClient skip rather than post a bare header into a product room.
        String message = new AlertMessageFormatter("prod").format(
                Alert.forProduct(ProductCode.P_116, AlertSeverity.CRITICAL, "BotManagerDown", "detail", "prometheus"),
                AlertRegister.CUSTOMER);

        assertTrue(message.isEmpty(), message);
    }

    @Test
    void format_defaultsToTheTechnicalRegister() {
        Alert alert = Alert.forProduct(ProductCode.P_116, AlertSeverity.CRITICAL,
                "Bot group DEAD", "18/18 bots dead", "prometheus");
        AlertMessageFormatter formatter = new AlertMessageFormatter("staging");

        assertEquals(formatter.format(alert, AlertRegister.TECHNICAL), formatter.format(alert));
    }
}
