package com.vingame.bot.domain.botgroup.service;

import com.vingame.bot.infrastructure.observability.BotMetrics;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DEAD_GROUP_AUTO_RECOVERY AD-15 — the Phase 4 alert rules say what the code does.
 * <p>
 * <b>Why this lives next to the scheduler rather than in the alert package.</b> The one
 * coupling nothing else can see is the label <em>value</em>:
 * {@code EnvironmentGroupRecoveryFlapping} selects
 * {@code group_recovery_attempts_total{outcome="success"}} and the only thing that ever
 * writes that label is {@link DeadGroupRecoveryScheduler#OUTCOME_SUCCESS}, which is
 * package-private. {@code AlertRuleMetricsTest} proves the metric NAME exists and that
 * the series carries the {@code outcome} label, but its fixture emits the literal string
 * {@code "success"} — so renaming the constant to {@code "succeeded"} leaves that test
 * green while the rule selects an empty vector forever and the flap it exists to expose
 * becomes permanently invisible. Pinning the two against each other requires seeing the
 * constant, so the test sits in the constant's package.
 * <p>
 * The rest of the file guards the Phase 4 claims that nothing else checks: that the two
 * rules exist at the severities the plan assigns them, that the exhaustion rule reads the
 * counter {@link BotMetrics} actually publishes, and that
 * {@code EnvironmentGroupDead}'s description no longer tells operators a DEAD group never
 * comes back — the sentence this whole feature falsifies, and the one an operator reads
 * at 3am while deciding whether to act on an alert that is about to resolve by itself.
 * <p>
 * Generic properties of these rules are covered elsewhere and are deliberately not
 * repeated here: {@code AlertRulesAudienceTest} requires {@code labels.audience},
 * {@code AlertmanagerRoutingTest.everyAlertRuleStillReachesVipTalk} proves both new rules
 * still reach the VipTalk receiver through the parent route, and
 * {@code AlertRuleMetricsTest} proves their metrics and {@code {{ $labels.X }}}
 * references resolve against a real exposition.
 */
@DisplayName("AD-15 — the recovery alert rules match the counters and the corrected copy")
class RecoveryAlertRuleWiringTest {

    private static final List<Path> CANDIDATES = List.of(
            Path.of("..", "prometheus", "alerts.yml"),
            Path.of("prometheus", "alerts.yml"));

    private record Rule(String name, String expr, String forWindow,
                        Map<String, Object> labels, Map<String, Object> annotations) {

        String label(String key) {
            return labels == null ? null : Objects.toString(labels.get(key), null);
        }

        String annotation(String key) {
            return annotations == null ? null : Objects.toString(annotations.get(key), null);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Rule> rules() {
        Path path = CANDIDATES.stream().filter(Files::isRegularFile).findFirst().orElse(null);
        Assumptions.assumeTrue(path != null,
                "prometheus/alerts.yml not found from " + Path.of("").toAbsolutePath());
        Map<String, Object> root;
        try (InputStream in = Files.newInputStream(path)) {
            root = new Yaml().load(in);
        } catch (Exception e) {
            throw new AssertionError("prometheus/alerts.yml is not readable YAML", e);
        }
        List<Rule> rules = new ArrayList<>();
        for (Map<String, Object> group : (List<Map<String, Object>>) root.get("groups")) {
            for (Map<String, Object> rule :
                    (List<Map<String, Object>>) group.getOrDefault("rules", List.of())) {
                if (rule.get("alert") == null) continue;
                rules.add(new Rule(rule.get("alert").toString(),
                        Objects.toString(rule.get("expr"), ""),
                        Objects.toString(rule.get("for"), null),
                        (Map<String, Object>) rule.get("labels"),
                        (Map<String, Object>) rule.get("annotations")));
            }
        }
        assertThat(rules)
                .as("the enumeration must have found rules, or every assertion here is vacuous")
                .isNotEmpty();
        return rules;
    }

    private static Rule rule(String name) {
        return rules().stream()
                .filter(r -> name.equals(r.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(name + " is not in prometheus/alerts.yml"));
    }

    @Test
    @DisplayName("the flapping rule selects the outcome value the scheduler actually writes")
    void theFlappingRuleSelectsTheOutcomeLabelTheSchedulerWrites() {
        Rule flapping = rule("EnvironmentGroupRecoveryFlapping");

        assertThat(flapping.expr())
                .as("the rule must read %s — a success counter by any other name is a "
                        + "vector this rule will never match",
                        BotMetrics.GROUP_RECOVERY_ATTEMPTS_TOTAL)
                .contains(BotMetrics.GROUP_RECOVERY_ATTEMPTS_TOTAL);

        Matcher selector = Pattern
                .compile(Pattern.quote(BotMetrics.GROUP_RECOVERY_ATTEMPTS_TOTAL)
                        + "\\{\\s*outcome\\s*=\\s*\"([^\"]*)\"\\s*}")
                .matcher(flapping.expr());
        assertThat(selector.find())
                .as("the rule must select a single outcome. Without the selector it counts "
                        + "failed and errored attempts as self-heals, and a group that "
                        + "retries six times and never comes up reads as a group that "
                        + "recovered six times.")
                .isTrue();
        assertThat(selector.group(1))
                .as("the selected outcome must be the string DeadGroupRecoveryScheduler "
                        + "passes to incGroupRecoveryAttempt on the success branch. A drift "
                        + "here is silent in both directions: the rule selects nothing, "
                        + "forever, and nothing reports that it is doing so.")
                .isEqualTo(DeadGroupRecoveryScheduler.OUTCOME_SUCCESS);

        assertThat(flapping.label("severity")).isEqualTo("warning");
        assertThat(flapping.label("audience")).isEqualTo("product");
    }

    @Test
    @DisplayName("the exhaustion rule reads the exhaustion counter, and is critical")
    void theExhaustionRuleReadsTheCounterAndPages() {
        Rule exhausted = rule("EnvironmentGroupRecoveryExhausted");

        assertThat(exhausted.expr())
                .as("this is the ONLY signal for the one recovery state nothing else "
                        + "surfaces: the environment answered, the group still would not "
                        + "come up, and nothing will try again until a human acts")
                .contains(BotMetrics.GROUP_RECOVERY_EXHAUSTED_TOTAL);

        // increase() over a range, not a bare gauge comparison: the counter moves at most
        // once per death episode, so a rule that could only fire on a rising edge it
        // happened to scrape would miss it.
        assertThat(exhausted.expr())
                .as("exhaustion is an event on a counter — it has to be read over a range")
                .containsPattern("increase\\(\\s*" + Pattern.quote(BotMetrics.GROUP_RECOVERY_EXHAUSTED_TOTAL));

        assertThat(exhausted.label("severity"))
                .as("AD-8 hands off to a human here; a warning would bury the hand-off "
                        + "next to the flapping rule, which is the state where recovery is "
                        + "still working")
                .isEqualTo("critical");
        assertThat(exhausted.label("audience")).isEqualTo("product");
    }

    @Test
    @DisplayName("EnvironmentGroupDead no longer claims a DEAD group never recovers")
    void theDeadGroupDescriptionMatchesWhatTheCodeNowDoes() {
        // The build-time twin of verification step V11b. The old copy told the reader that
        // only they can end the outage, so a firing-then-resolving pair — the normal shape
        // of a self-heal — reads as an alert that was ignored rather than one that fixed
        // itself, and an operator restarts a group that is already back.
        String description = rule("EnvironmentGroupDead").annotation("description");
        assertThat(description).as("EnvironmentGroupDead must carry a description").isNotNull();

        assertThat(description)
                .as("this claim is what DEAD_GROUP_AUTO_RECOVERY falsified. If recovery is "
                        + "ever REMOVED, restore the sentence deliberately — do not let it "
                        + "drift back in while the reconciler still runs, or vice versa.")
                .doesNotContainPattern("(?i)(does not|doesn't|never|cannot|can't|won't) recover");
        assertThat(description)
                .as("and it must say what does happen instead, or the reader has no way to "
                        + "know that waiting is a legitimate response to this page")
                .containsPattern("(?i)auto-recovery");
        assertThat(description)
                .as("the opt-out is the other half: an operator who wants the group to STAY "
                        + "down has to be told that /stop is how that is expressed, because "
                        + "a DEAD group will otherwise be restarted under them")
                .contains("STOPPED");
    }

    @Test
    @DisplayName("EnvironmentGroupDead's debounce window is unchanged (AD-15)")
    void theDeadGroupWindowIsNotWidenedToHideSelfHeals() {
        // AD-15 keeps `for: 5m` deliberately. Widening it to swallow a slow self-heal
        // would equally swallow a group that never comes back at all -- the failure this
        // alert exists for. That trade is a plan decision, not a tuning knob, and the
        // temptation to take it is highest right after someone is woken by an alert that
        // had already resolved.
        assertThat(rule("EnvironmentGroupDead").forWindow())
                .as("AD-15: a self-heal inside 5 minutes silently prevents the page (the "
                        + "point of the feature); one after 5 minutes fires and then "
                        + "resolves, which is the honest record. Repetition is made visible "
                        + "by EnvironmentGroupRecoveryFlapping, not by widening this.")
                .isEqualTo("5m");
    }
}
