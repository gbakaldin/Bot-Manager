package com.vingame.bot.domain.alert;

import com.vingame.bot.domain.alert.model.AlertAudience;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AD-V4 — <b>every</b> rule in {@code prometheus/alerts.yml} must declare an
 * {@code audience} label.
 * <p>
 * {@link AlertAudience#fromLabel(String)} defaults an absent or unrecognised audience to
 * {@link AlertAudience#INTERNAL} so an unlabelled rule can never silently vanish. That
 * default is a safety net for hand-crafted webhook payloads — it must not become a
 * loophole our own rules ride on, because "forgot the label" and "meant the ops room"
 * would then be indistinguishable, and a product-scoped alert would quietly pile into the
 * ops room, which is exactly what VIPTALK_ALERTING_V2's requirement B2 forbids. Hence this
 * test: the file is the contract, and the build enforces it.
 * <p>
 * It also pins the two adjacent guarantees that only hold at the file level:
 * <ul>
 *   <li>an {@code audience: both} rule carries a {@code public_summary} — without one the
 *       customer copy is suppressed at runtime (AD-V6 fail-closed), so a {@code both} rule
 *       lacking it is an {@code internal} rule wearing the wrong label;</li>
 *   <li>the three fleet rules retired in Phase 3 stay retired — they were built on
 *       unlabelled aggregates and could only ever reach the ops room (AD-V2 / B2).</li>
 * </ul>
 * PromQL validity is <em>not</em> checked here; that is {@code promtool check rules}, run
 * against the bind-mounted file on the host.
 */
@DisplayName("prometheus/alerts.yml — every rule declares its audience (AD-V4)")
class AlertRulesAudienceTest {

    /** Surefire runs with the module directory as CWD; the file lives at the repo root. */
    private static final List<Path> CANDIDATE_PATHS = List.of(
            Path.of("..", "prometheus", "alerts.yml"),
            Path.of("prometheus", "alerts.yml"));

    private static final Set<String> VALID_AUDIENCES = Set.of("internal", "product", "both");

    /** Retired by VIPTALK_ALERTING_V2 Phase 3 (user decision, 2026-08-17). */
    private static final Set<String> RETIRED_RULES =
            Set.of("BotGroupDead", "DeadBotRatioHigh", "WsConnectionsBelowFleet");

    private record Rule(String name, Map<String, Object> labels, Map<String, Object> annotations) {

        String audience() {
            return string(labels, "audience");
        }

        String annotation(String key) {
            return string(annotations, key);
        }

        private static String string(Map<String, Object> map, String key) {
            Object value = map == null ? null : map.get(key);
            return value == null ? null : value.toString();
        }
    }

    /**
     * Every alerting rule in the file. Recording rules (no {@code alert} key) are skipped —
     * they produce no notification and so have no audience.
     */
    @SuppressWarnings("unchecked")
    private static List<Rule> alertingRules() {
        Path path = CANDIDATE_PATHS.stream().filter(Files::isRegularFile).findFirst().orElse(null);
        // Absent only in a module-only build from an unusual CWD; skipping beats failing a
        // build for a file this module does not own.
        Assumptions.assumeTrue(path != null, "prometheus/alerts.yml not found from " + Path.of("").toAbsolutePath());

        Map<String, Object> root;
        try (InputStream in = Files.newInputStream(path)) {
            root = new Yaml().load(in);
        } catch (Exception e) {
            throw new AssertionError("prometheus/alerts.yml is not readable YAML", e);
        }

        List<Rule> rules = new ArrayList<>();
        for (Map<String, Object> group : (List<Map<String, Object>>) root.get("groups")) {
            for (Map<String, Object> rule : (List<Map<String, Object>>) group.get("rules")) {
                Object name = rule.get("alert");
                if (name == null) continue;
                rules.add(new Rule(name.toString(),
                        (Map<String, Object>) rule.get("labels"),
                        (Map<String, Object>) rule.get("annotations")));
            }
        }
        return rules;
    }

    @Test
    @DisplayName("the file parses and actually contains rules")
    void theFileIsNotEmpty() {
        // Guards the rest of the suite: a YAML shape change that yielded zero rules would
        // otherwise make every assertion below vacuously true.
        assertThat(alertingRules()).isNotEmpty();
    }

    @Test
    @DisplayName("no rule relies on the absent-audience default")
    void everyRuleDeclaresAnAudience() {
        for (Rule rule : alertingRules()) {
            assertThat(rule.audience())
                    .as("rule %s must declare labels.audience (AD-V4)", rule.name())
                    .isNotNull()
                    .isNotBlank();
        }
    }

    @Test
    @DisplayName("every declared audience is one the router understands")
    void everyAudienceIsRecognised() {
        for (Rule rule : alertingRules()) {
            String audience = rule.audience();
            assertThat(audience)
                    .as("rule %s declares an unknown audience — it would fall back to INTERNAL", rule.name())
                    .isIn(VALID_AUDIENCES);
            // The label is what AlertmanagerWebhookService feeds to fromLabel; assert the
            // round trip rather than trusting the string set alone.
            assertThat(AlertAudience.fromLabel(audience).name())
                    .isEqualTo(audience.toUpperCase(Locale.ROOT));
        }
    }

    @Test
    @DisplayName("an audience=both rule carries the customer wording it promises")
    void everyBothRuleCarriesAPublicSummary() {
        for (Rule rule : alertingRules()) {
            if (!"both".equals(rule.audience())) continue;
            assertThat(rule.annotation("public_summary"))
                    .as("rule %s is audience=both, so its customer copy must come from "
                            + "public_summary — it is never synthesised from summary (AD-V6)", rule.name())
                    .isNotNull()
                    .isNotBlank();
        }
    }

    @Test
    @DisplayName("an audience=both rule says something different when it recovers")
    void everyBothRuleCarriesItsOwnRecoveryCopy() {
        for (Rule rule : alertingRules()) {
            if (!"both".equals(rule.audience())) continue;
            String resolved = rule.annotation("public_resolved_summary");
            // AlertMessageFormatter renders public_summary VERBATIM, so without its own
            // recovery copy a resolving `both` alert publishes the outage wording under a
            // ✅ marker — a green tick over text saying the application is broken.
            // viptalk.public-resolved-summary would catch this generically, but the same
            // argument as AD-V4 applies: a configured fallback is a safety net for payloads
            // we did not author, not a licence for our own file to leave it out.
            assertThat(resolved)
                    .as("rule %s is audience=both, so it must declare public_resolved_summary — "
                            + "otherwise its recovery message reads as an outage", rule.name())
                    .isNotNull()
                    .isNotBlank();
            assertThat(resolved)
                    .as("rule %s recovers with the same words it fired with, which is the exact "
                            + "defect public_resolved_summary exists to prevent", rule.name())
                    .isNotEqualTo(rule.annotation("public_summary"));
        }
    }

    @Test
    @DisplayName("BotManagerDown still carries the contract the out-of-band path depends on")
    void theAppDownRuleIsTheCustomerFacingOne() {
        Rule rule = alertingRules().stream()
                .filter(r -> "BotManagerDown".equals(r.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "BotManagerDown is gone — the app-down path (shim + AD-V9 fan-out in "
                                + "alertmanager.yml) is routed on this alertname and is now dead code"));

        // Pinned by name because Phase 6 wires real infrastructure to this one rule:
        // alertmanager.yml matches its alertname to reach the shim, and the shim's fixed
        // text is written to match its public_summary. Renaming it silently unhooks both.
        assertThat(rule.audience())
                .as("BotManagerDown must stay audience=both — product rooms hearing about the "
                        + "outage in their own register is the whole reason the shim exists")
                .isEqualTo("both");
        assertThat(rule.annotation("public_summary")).isNotBlank();
        assertThat(rule.annotation("public_resolved_summary")).isNotBlank();
    }

    @Test
    @DisplayName("every rule still carries a severity and a summary for the technical register")
    void everyRuleIsRenderable() {
        for (Rule rule : alertingRules()) {
            assertThat(Rule.string(rule.labels(), "severity"))
                    .as("rule %s must declare labels.severity — Alertmanager's inhibit rules match on it", rule.name())
                    .isNotBlank();
            assertThat(rule.annotation("summary"))
                    .as("rule %s must declare annotations.summary — it is the message body", rule.name())
                    .isNotBlank();
        }
    }

    @Test
    @DisplayName("the retired fleet rules have not come back")
    void retiredFleetRulesStayRetired() {
        List<String> names = alertingRules().stream().map(Rule::name).toList();
        assertThat(names)
                .as("BotGroupDead / DeadBotRatioHigh / WsConnectionsBelowFleet are built on "
                        + "unlabelled fleet aggregates and can only reach the ops room; their "
                        + "per-environment replacements are Environment* (AD-V2, Phase 3)")
                .doesNotContainAnyElementsOf(RETIRED_RULES);
    }
}
