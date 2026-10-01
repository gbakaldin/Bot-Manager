package com.vingame.bot.infrastructure.observability;

import com.vingame.bot.common.logging.BotMdc;
import com.vingame.bot.domain.bot.core.BotStatus;
import com.vingame.bot.domain.botgroup.service.BotGroupBehaviorService;
import com.vingame.bot.domain.botgroup.service.RegistrationWorker;
import com.vingame.bot.domain.botgroup.service.BotGroupBehaviorService.EnvInfo;
import com.vingame.bot.domain.botgroup.service.BotGroupBehaviorService.EnvKey;
import com.vingame.bot.domain.botgroup.service.BotGroupBehaviorService.EnvStatusKey;
import com.vingame.bot.domain.botgroup.service.BotGroupBehaviorService.GameInfo;
import com.vingame.bot.domain.botgroup.service.BotGroupBehaviorService.GameStatusKey;
import com.vingame.bot.domain.botgroup.service.BotGroupBehaviorService.GroupBalance;
import com.vingame.bot.infrastructure.gateway.GatewayBudgetRegistry;
import com.vingame.bot.infrastructure.gateway.GatewayBudgetSettings;
import com.vingame.bot.infrastructure.logging.AsyncQueueMeterFixture;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Do the Phase 3–6 rules select metrics and labels that the application actually emits?
 * <p>
 * A PromQL rule is not type-checked against anything. {@code promtool check rules} proves
 * the expression parses; it cannot know whether {@code ws_connections_open_by_env} is a
 * meter we register or a plausible-looking typo, whether the series carries the
 * {@code product} label the alert is routed by, or whether
 * {@code {{ $labels.groupName }}} survives the rule's own aggregation. Each of those
 * failures is completely silent: a rule over a non-existent metric evaluates to an empty
 * vector forever, and an annotation over an absent label renders {@code <no value>} into
 * a product room.
 * <p>
 * So this test renders the exposition the way production does — the same
 * {@code InfoGaugeRefresher} gauges, the same {@link BotMetrics} counters, the same
 * {@link BotMdcTagsMeterFilter} — and holds every rule in {@code prometheus/alerts.yml}
 * against it.
 * <p>
 * Metrics from outside the application ({@code node_*} from node-exporter, {@code jvm_*}
 * and {@code process_*} from actuator, {@code up} from Prometheus) are out of this
 * registry's reach by construction; they are covered by
 * {@code RuntimeMetricsExposedToAlertsTest} and {@code AlertPipelineWiringTest}.
 */
@DisplayName("prometheus/alerts.yml — every rule selects a metric and labels we emit")
class AlertRuleMetricsTest {

    private static final List<Path> CANDIDATE_PATHS = List.of(
            Path.of("..", "prometheus", "alerts.yml"),
            Path.of("prometheus", "alerts.yml"));

    /** PromQL keywords, functions and aggregation operators — never metric names. */
    private static final Set<String> PROMQL_WORDS = Set.of(
            "sum", "avg", "min", "max", "count", "count_values", "stddev", "stdvar",
            "topk", "bottomk", "quantile", "group",
            "by", "without", "on", "ignoring", "group_left", "group_right", "offset", "bool",
            "and", "or", "unless",
            "rate", "irate", "increase", "changes", "delta", "idelta", "resets",
            "clamp", "clamp_min", "clamp_max", "abs", "ceil", "floor", "round", "sqrt", "exp",
            "ln", "log2", "log10", "absent", "absent_over_time", "present_over_time",
            "predict_linear", "deriv", "histogram_quantile", "time", "timestamp",
            "vector", "scalar", "sort", "sort_desc", "label_replace", "label_join",
            "avg_over_time", "sum_over_time", "max_over_time", "min_over_time",
            "last_over_time", "count_over_time", "quantile_over_time", "stddev_over_time");

    /** Series produced outside this application; presence is verified elsewhere. */
    private static final Set<String> EXTERNAL_PREFIXES = Set.of("node_", "jvm_", "process_", "up");

    private PrometheusMeterRegistry registry;
    private String scrape;

    private record Rule(String name, String expr, Map<String, Object> annotations,
                        Map<String, Object> labels) {

        /** AD-V4: an absent audience defaults to internal. */
        String audience() {
            return labels == null ? "internal"
                    : Objects.toString(labels.getOrDefault("audience", "internal"));
        }
    }

    @BeforeEach
    void setUp() {
        MDC.clear();
        registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        registry.config().meterFilter(new BotMdcTagsMeterFilter());

        // --- the MultiGauges, refreshed exactly as the 10 s scheduler does ---
        InfoGaugeRefresher.InfoGauges gauges = InfoGaugeRefresher.registerInfoGauges(registry);
        BotGroupBehaviorService behaviorService = mock(BotGroupBehaviorService.class);
        when(behaviorService.listRunningGameInfo()).thenReturn(List.of(
                new GameInfo("game-uuid-1", "BauCua", "BETTING_MINI", "env-uuid-1", "116")));
        when(behaviorService.listRunningEnvironmentInfo()).thenReturn(List.of(
                new EnvInfo("env-uuid-1", "Staging", "116")));
        when(behaviorService.countBotsByGameAndStatus()).thenReturn(Map.of(
                new GameStatusKey("game-uuid-1", "BauCua", BotStatus.CONNECTION_AUTHENTICATED,
                        "env-uuid-1", "BETTING_MINI", "116"), 12));
        when(behaviorService.countBotsByEnvAndStatus()).thenReturn(Map.of(
                new EnvStatusKey("env-uuid-1", BotStatus.DEAD, "116"), 3));
        when(behaviorService.countManagedBotsByEnv()).thenReturn(Map.of(
                new EnvKey("env-uuid-1", "116"), 12));
        when(behaviorService.countOpenWsByEnv()).thenReturn(Map.of(
                new EnvKey("env-uuid-1", "116"), 9));
        when(behaviorService.countDeadGroupsByEnv()).thenReturn(Map.of(
                new EnvKey("env-uuid-1", "116"), 1));
        when(behaviorService.listGroupBalances()).thenReturn(List.of(
                new GroupBalance("group-uuid-1", "tptxg2", "env-uuid-1", "116",
                        "game-uuid-1", "Tai Xiu", 25_000_000L, 250_000_000L)));
        InfoGaugeRefresher.refresh(behaviorService, gauges);

        // --- the bot_* counters, emitted under a bot's MDC exactly as a live bot does ---
        BotMdc.set("group-uuid-1", 1, "env-uuid-1", "116", "BETTING_MINI",
                "game-uuid-1", "BauCua", "authtestws1");
        BotMetrics metrics = new BotMetrics(registry);
        metrics.incBotMessage("startGame");
        metrics.incBotMessage("spin");
        metrics.incLogin(true);
        metrics.incLogin(false);
        metrics.incVerifyToken(true);
        metrics.incVerifyToken(false);
        MDC.clear();

        // --- the group-scoped recovery counters (DEAD_GROUP_AUTO_RECOVERY AD-13),
        // emitted under the recovery scheduler's GROUP MDC: botGroupId, environmentId
        // and product, and no bot identity, because no bot exists yet when a dead group
        // is being recovered. EnvironmentGroupRecoveryExhausted and
        // EnvironmentGroupRecoveryFlapping read them BARE, so their tag set is the
        // alert's label set — which is what makes `audience: product` routable and
        // {{ $labels.botGroupId }} render a group id instead of `<no value>`.
        BotMdc.setGroupContext("group-uuid-1", "env-uuid-1", "116");
        // Exactly the order DeadGroupRecoveryScheduler.attempt() uses: materialise
        // every series at zero first, then increment the one that happened. See
        // theRecoveryCountersExistBeforeTheyMove below for why that order is the
        // difference between these two rules working and reading 0 forever.
        metrics.initGroupRecoverySeries("success", "failed", "error");
        metrics.incGroupRecoveryAttempt("success");
        metrics.incGroupRecoveryExhausted();
        MDC.clear();

        // --- the per-environment gateway request budget (GATEWAY_REQUEST_BUDGET AD-20),
        // created exactly as EnvironmentClientRegistry creates it: one per environment id,
        // tagged EXPLICITLY with {environmentId, product} rather than from bot MDC, because it
        // is published from the scrape thread and from the probe scheduler, neither of which
        // has a bot MDC. GatewayBudgetNearCap reads gateway_budget_window_requests BARE and
        // renders {{ $labels.environmentId }}, so both the metric and that label have to be in
        // this exposition or the rule is unverifiable here.
        new GatewayBudgetRegistry(GatewayBudgetSettings.defaults(), registry)
                .forEnvironment("env-uuid-1", "Staging", "116")
                .count("ws-probe");

        // --- the asynchronous-registration gauges (GATEWAY_REQUEST_BUDGET Phase 4), registered
        // exactly as ObservabilityConfig.registrationGauges does: unlabelled fleet aggregates
        // over the worker's cached counts. RegistrationStalled reads registration_failed_groups
        // BARE, so it has to be in this exposition or the rule is unverifiable here — and a rule
        // whose metric does not exist evaluates to an empty vector forever without saying so,
        // which is the exact failure this test class exists to make impossible.
        RegistrationWorker worker = mock(RegistrationWorker.class);
        when(worker.getPendingGroupCount()).thenReturn(2);
        when(worker.getFailedGroupCount()).thenReturn(1);
        new ObservabilityConfig().registrationGauges(worker).bindTo(registry);

        // …and the per-account counter beside them, materialised the way RegistrationWorker does
        // at the top of every pass: all three outcomes at zero under the GROUP MDC, then the one
        // that happened. RegistrationNotProgressing reads it as a fleet-wide sum against the
        // unlabelled gauge, so both have to be in this exposition or that rule is unverifiable
        // here — and a rule over a metric that does not exist is an empty vector forever.
        BotMdc.setGroupContext("group-uuid-1", "env-uuid-1", "116");
        metrics.initRegistrationSeries("success", "exists", "failed");
        metrics.incRegistrationAccount("success");
        // A FAILED attempt too, so the exposition carries the series RegistrationNotProgressing
        // must exclude (review-phase4-fixround) — see registrationNotProgressingIgnoresFailures.
        metrics.incRegistrationAccount("failed");
        MDC.clear();

        // --- the log4j2 queue meters, registered exactly as AsyncQueueMetrics does at
        // startup, against the real LoggerContext this build runs on. LogQueueSaturated
        // reads them bare and renders {{ $labels.appender }}, so both the metric names
        // and the tag have to be in this exposition or the rule is unverifiable here.
        AsyncQueueMeterFixture.registerAgainstRunningContext(registry);

        scrape = registry.scrape();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        registry.close();
    }

    // ------------------------------------------------------------------ the rule file

    @SuppressWarnings("unchecked")
    private static List<Rule> rules() {
        Path path = CANDIDATE_PATHS.stream().filter(Files::isRegularFile).findFirst().orElse(null);
        Assumptions.assumeTrue(path != null, "prometheus/alerts.yml not found");
        Map<String, Object> root;
        try (InputStream in = Files.newInputStream(path)) {
            root = new Yaml().load(in);
        } catch (Exception e) {
            throw new AssertionError("prometheus/alerts.yml is not readable YAML", e);
        }
        List<Rule> rules = new ArrayList<>();
        for (Map<String, Object> group : (List<Map<String, Object>>) root.get("groups")) {
            for (Map<String, Object> rule : (List<Map<String, Object>>) group.get("rules")) {
                if (rule.get("alert") == null) continue;
                rules.add(new Rule(rule.get("alert").toString(),
                        Objects.toString(rule.get("expr"), ""),
                        (Map<String, Object>) rule.get("annotations"),
                        (Map<String, Object>) rule.get("labels")));
            }
        }
        assertThat(rules).isNotEmpty();
        return rules;
    }

    /** Metric names in an expression: label selectors, ranges and groupings removed first. */
    private static Set<String> metricNames(String expr) {
        String stripped = expr
                .replaceAll("\\{[^}]*}", " ")              // {status="DEAD"}
                .replaceAll("\\[[^]]*]", " ")              // [10m]
                .replaceAll("(?i)\\b(by|without|on|ignoring|group_left|group_right)\\s*\\([^)]*\\)", " ");
        Set<String> names = new LinkedHashSet<>();
        Matcher m = Pattern.compile("[a-zA-Z_:][a-zA-Z0-9_:]*").matcher(stripped);
        while (m.find()) {
            String token = m.group();
            if (!PROMQL_WORDS.contains(token)) names.add(token);
        }
        return names;
    }

    /** Labels the expression's aggregation keeps, or {@code null} when it does not aggregate. */
    private static Set<String> preservedLabels(String expr) {
        Matcher m = Pattern.compile("\\bby\\s*\\(([^)]*)\\)").matcher(expr);
        Set<String> kept = null;
        while (m.find()) {
            Set<String> here = new LinkedHashSet<>();
            for (String label : m.group(1).split(",")) {
                if (!label.isBlank()) here.add(label.strip());
            }
            // Multiple `by` clauses in one expression (GameNoRounds has two): a label
            // survives only if EVERY aggregation keeps it.
            if (kept == null) kept = here;
            else kept.retainAll(here);
        }
        return kept;
    }

    /** All values the scraped series of {@code metric} carry for label {@code label}. */
    private Set<String> scrapedLabelValues(String metric, String label) {
        Set<String> values = new LinkedHashSet<>();
        for (String line : scrape.split("\n")) {
            if (line.startsWith("#")) continue;
            Matcher m = Pattern.compile("^" + Pattern.quote(metric) + "\\{([^}]*)}").matcher(line);
            if (!m.find()) continue;
            for (String pair : m.group(1).split(",")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) continue;
                if (!pair.substring(0, eq).strip().equals(label)) continue;
                values.add(pair.substring(eq + 1).strip().replaceAll("^\"|\"$", ""));
            }
        }
        return values;
    }

    /** All label names present on the scraped series of {@code metric}. */
    private Set<String> scrapedLabels(String metric) {
        Set<String> labels = new LinkedHashSet<>();
        for (String line : scrape.split("\n")) {
            if (line.startsWith("#")) continue;
            Matcher m = Pattern.compile("^" + Pattern.quote(metric) + "\\{([^}]*)}").matcher(line);
            if (!m.find()) continue;
            for (String pair : m.group(1).split(",")) {
                int eq = pair.indexOf('=');
                if (eq > 0) labels.add(pair.substring(0, eq).strip());
            }
        }
        return labels;
    }

    private boolean isExposed(String metric) {
        return Pattern.compile("(?m)^" + Pattern.quote(metric) + "[{ ]").matcher(scrape).find();
    }

    private static boolean isExternal(String metric) {
        return EXTERNAL_PREFIXES.stream().anyMatch(metric::startsWith);
    }

    // ------------------------------------------------------------------------ the tests

    @Test
    @DisplayName("sanity: the rules do reference application metrics, and the fixture emits them")
    void theFixtureRendersTheApplicationSeries() {
        // Guards every assertion below: a scrape that rendered nothing, or a rule file that
        // referenced nothing of ours, would make them all vacuously true.
        assertThat(isExposed("bots_managed_by_env")).isTrue();
        assertThat(isExposed("bot_messages_total")).isTrue();

        Set<String> appMetrics = new LinkedHashSet<>();
        for (Rule rule : rules()) {
            metricNames(rule.expr()).stream().filter(m -> !isExternal(m)).forEach(appMetrics::add);
        }
        assertThat(appMetrics).hasSizeGreaterThanOrEqualTo(8);
    }

    @Test
    @DisplayName("every application metric a rule selects is one the application exposes")
    void everyReferencedApplicationMetricExists() {
        Map<String, List<String>> missing = new LinkedHashMap<>();
        for (Rule rule : rules()) {
            for (String metric : metricNames(rule.expr())) {
                if (isExternal(metric) || isExposed(metric)) continue;
                missing.computeIfAbsent(metric, k -> new ArrayList<>()).add(rule.name());
            }
        }
        assertThat(missing)
                .as("these metric names appear in alerts.yml but in no exposition this "
                        + "application produces — such a rule evaluates to an empty vector "
                        + "forever and never reports that it is doing so")
                .isEmpty();
    }

    @Test
    @DisplayName("RegistrationNotProgressing counts created accounts only — a failed attempt is not progress")
    void registrationNotProgressingIgnoresFailures() {
        // review-phase4-fixround. registration_accounts_total{outcome="failed"} increments on every
        // refused or transport attempt, so a rule over the unfiltered counter is satisfied by any
        // group retrying anywhere in the JVM and never fires for the starved group it is about.
        // Nothing here evaluates PromQL, so the property is pinned on the expression, against an
        // exposition that really carries both kinds of series.
        Rule rule = rules().stream()
                .filter(r -> "RegistrationNotProgressing".equals(r.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("RegistrationNotProgressing is not in alerts.yml"));

        assertThat(rule.expr())
                .contains("registration_accounts_total{outcome!=\"failed\"}");
        assertThat(scrapedLabelValues("registration_accounts_total", "outcome"))
                .as("the exposition has a failed series to exclude and a success series to keep")
                .contains("failed", "success");
    }

    @Test
    @DisplayName("an edge block pages under observe too, and does not double-page with GatewayEdgeBlocked")
    void anObservedEdgeBlockIsAlertedWithoutDoublePaging() {
        // A32.3 S2. GatewayEdgeBlocked reads gateway_circuit_open, which only moves under enforce;
        // prod runs observe until Phase 6. This pins the companion rule's three load-bearing parts.
        Rule rule = rules().stream()
                .filter(r -> "GatewayEdgeBlockObserved".equals(r.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("GatewayEdgeBlockObserved is not in alerts.yml — "
                        + "an observe-mode box would have no block alert at all"));

        assertThat(rule.expr())
                .as("reads the counter that counts in both modes, minus the hourly clearance probe")
                .contains("increase(gateway_edge_blocks_total{endpoint!=\"circuit-probe\"}")
                .as("scoped to a closed circuit, so GatewayEdgeBlocked owns the enforce page")
                .contains("and on (environmentId) gateway_circuit_open == 0");
        assertThat(rule.labels()).containsEntry("severity", "critical").containsEntry("audience", "product");
        assertThat(scrapedLabelValues("gateway_edge_blocks_total", "endpoint"))
                .as("pre-registered at zero, so increase() fires on the FIRST block page")
                .contains("login", "verifytoken", "ws-upgrade", "circuit-probe");
        assertThat(isExposed("gateway_circuit_open")).isTrue();
    }

    @Test
    @DisplayName("MetaspaceGrowth is gated on JVM uptime, so it cannot fire on the boot ramp")
    void metaspaceGrowthCarriesItsUptimeGate() {
        // PLUGIN_HOT_RELOAD Amendment A3. Without the gate this rule is continuously true
        // from ~T+1h to T+24h after EVERY restart — deploys, crash-restarts, OOM-kills, and
        // the deploy that shipped it. Metaspace ramps ~0 -> ~90-140 MB at boot (several
        // times the 50 MiB threshold) and delta() extrapolates a partially-covered range,
        // so at 2h of uptime a real +70 MB reports as ~840 MB.
        //
        // That is not merely noisy. The rule's own comment tells the operator a firing in
        // the first week is a pre-existing leak; ungated, the first week's firings are the
        // boot ramp, so the operator chases a phantom or mutes the rule — and with no
        // -XX:MaxMetaspaceSize (AD-6) this is the ONLY warning a classloader leak gets
        // before a silent kernel OOM-kill. AlertRuleMetricsTest's other tests cannot see
        // this: MetaspaceGrowth reads only jvm_*, so EXTERNAL_PREFIXES skips it everywhere
        // else in this file. Hence a rule-shape assertion rather than a metric one.
        Rule metaspace = rules().stream()
                .filter(r -> "MetaspaceGrowth".equals(r.name())).findFirst()
                .orElseThrow(() -> new AssertionError("MetaspaceGrowth is not in alerts.yml"));

        assertThat(metaspace.expr())
                .as("MetaspaceGrowth reads a 24h range over a series that starts near zero "
                        + "at every JVM start, so it must not evaluate until the JVM has been "
                        + "up longer than that range. Expected the process_start_time_seconds "
                        + "idiom BotManagerRestarted already uses.")
                .contains("process_start_time_seconds")
                .contains("86400");
    }

    @Test
    @DisplayName("every product-routed rule keeps the product label its routing depends on")
    void productRoutedRulesKeepTheProductLabel() {
        // AD-V3: the room is chosen from the alert's `product` label (or `environmentId`
        // resolved via Mongo). A rule that aggregates those away lands in the ops room
        // tagged as a misroute — for every product, forever, with no other symptom.
        //
        // `audience: internal` rules are exempt, and the exemption is the AD, not a
        // loosening: an internal alert goes to the ops room by construction and is never
        // resolved to a product, so demanding a product label on it would forbid
        // application-sourced infrastructure alerts outright. Until LogQueueSaturated
        // every internal rule happened to read an EXTERNAL metric (jvm_*, node_*) and was
        // skipped one line below, which is why this never had to be stated.
        int checked = 0;
        for (Rule rule : rules()) {
            Set<String> kept = preservedLabels(rule.expr());
            List<String> appMetrics = metricNames(rule.expr()).stream()
                    .filter(m -> !isExternal(m)).toList();
            if (appMetrics.isEmpty()) continue;
            if ("internal".equals(rule.audience())) continue;
            checked++;

            if (kept != null) {
                assertThat(kept)
                        .as("rule %s aggregates with by(%s), dropping the labels it is "
                                + "routed by", rule.name(), String.join(",", kept))
                        .contains("product", "environmentId");
            } else {
                for (String metric : appMetrics) {
                    assertThat(scrapedLabels(metric))
                            .as("rule %s reads %s bare, so %s's own labels are the alert's "
                                    + "labels", rule.name(), metric, metric)
                            .contains("product", "environmentId");
                }
            }
        }
        assertThat(checked)
                .as("the audience exemption must not be able to empty this test — if every "
                        + "product-routed rule stopped reading an application metric, the "
                        + "loop above would pass by doing nothing")
                .isGreaterThanOrEqualTo(5);
    }

    @Test
    @DisplayName("every {{ $labels.X }} in an annotation survives to the firing alert")
    void annotationLabelsSurviveTheRulesAggregation() {
        // An absent label does not fail — Go templates render `<no value>`, so the message
        // reaches the room with a hole in it. Only visible by reading a real alert.
        Pattern labelRef = Pattern.compile("\\$labels\\.([a-zA-Z_][a-zA-Z0-9_]*)");
        for (Rule rule : rules()) {
            Set<String> kept = preservedLabels(rule.expr());
            List<String> appMetrics = metricNames(rule.expr()).stream()
                    .filter(m -> !isExternal(m)).toList();
            if (appMetrics.isEmpty()) continue; // node/jvm rules: `instance` is Prometheus'

            Set<String> available = new LinkedHashSet<>();
            if (kept != null) {
                available.addAll(kept);
            } else {
                appMetrics.forEach(m -> available.addAll(scrapedLabels(m)));
            }

            for (Object annotation : rule.annotations().values()) {
                Matcher m = labelRef.matcher(annotation.toString());
                while (m.find()) {
                    assertThat(available)
                            .as("rule %s renders {{ $labels.%s }} but that label is not on "
                                    + "the alert — the message publishes `<no value>`",
                                    rule.name(), m.group(1))
                            .contains(m.group(1));
                }
            }
        }
    }

    @Test
    @DisplayName("a rule that renders $value as a percentage computes a ratio first")
    void percentageRenderingRulesPutTheRatioFirst() {
        // The Phase 3 defect, as a standing guard. `a and b` yields a's SAMPLE VALUES, so
        // writing the guard first (`bots_managed_by_env > 0 and ratio < 0.5`) makes $value
        // the managed-bot count — and `{{ $value | humanizePercentage }}` then renders
        // "2000%" for 20 bots. The plan's own draft of EnvironmentSocketDown had it this
        // way round; the implementation corrected it. Nothing else would notice.
        int checked = 0;
        for (Rule rule : rules()) {
            boolean rendersPercentage = rule.annotations().values().stream()
                    .anyMatch(a -> a.toString().contains("$value | humanizePercentage"));
            // The hazard exists only where `and` picks between two operands' values; a
            // single-expression rule has nothing to get the wrong way round.
            if (!rendersPercentage || !hasTopLevelAnd(rule.expr())) continue;
            checked++;

            String first = firstTopLevelAndOperand(rule.expr());
            // A fraction, either computed here or already one by construction — the app
            // publishes group_balance_ratio pre-divided, which is the point of AD-V13.
            boolean firstOperandIsAFraction = first.contains("/")
                    || metricNames(first).stream().anyMatch(m -> m.endsWith("_ratio"));
            assertThat(firstOperandIsAFraction)
                    .as("rule %s renders $value as a percentage, so the FIRST operand of "
                            + "`and` must be the ratio — `a and b` keeps a's values, not b's. "
                            + "First operand was: %s", rule.name(), first.strip())
                    .isTrue();
        }
        assertThat(checked)
                .as("the guard must not go vacuous: the four Environment* ratio rules and "
                        + "any successor are the reason it exists")
                .isGreaterThanOrEqualTo(4);
    }

    /** Whether the expression joins two operands with a top-level {@code and}. */
    private static boolean hasTopLevelAnd(String expr) {
        String flat = expr.replace('\n', ' ');
        return !firstTopLevelAndOperand(expr).equals(flat);
    }

    @Test
    @DisplayName("every equality label matcher selects a value the application really emits")
    void everyLabelMatcherSelectsAnEmittedValue() {
        // The sibling of everyReferencedApplicationMetricExists, one level down. A rule
        // over a metric that exists but a label VALUE that does not — `outcome="succeeded"`,
        // `status="DEAD_BOT"` — is exactly as silent as a typo'd metric name: an empty
        // vector, forever, reported by nothing. promtool cannot see it either, because the
        // expression parses perfectly.
        //
        // DEAD_GROUP_AUTO_RECOVERY made this worth pinning generically:
        // EnvironmentGroupRecoveryFlapping's whole meaning lives in
        // `{outcome="success"}` — without the selector it counts failed attempts as
        // self-heals, and with the wrong one it counts nothing at all. The same shape
        // already carries EnvironmentAuthDown, EnvironmentLoginFailing and
        // EnvironmentDeadBotRatioHigh.
        //
        // Equality matchers only. `!=`, `=~` and `!~` are deliberately out of scope: a
        // regex may legitimately name values this fixture does not render (bot_messages_total
        // cmd=~"startGame|spin" is a union across two game families), and a negative matcher
        // is about absence.
        Pattern selector = Pattern.compile("([a-zA-Z_:][a-zA-Z0-9_:]*)\\{([^}]*)}");
        Pattern equality = Pattern.compile("([a-zA-Z_][a-zA-Z0-9_]*)\\s*=\\s*\"([^\"]*)\"");
        int checked = 0;
        for (Rule rule : rules()) {
            Matcher m = selector.matcher(rule.expr());
            while (m.find()) {
                String metric = m.group(1);
                if (PROMQL_WORDS.contains(metric) || isExternal(metric) || !isExposed(metric)) continue;
                Matcher pair = equality.matcher(m.group(2));
                while (pair.find()) {
                    String label = pair.group(1);
                    String value = pair.group(2);
                    checked++;
                    assertThat(scrapedLabelValues(metric, label))
                            .as("rule %s selects %s{%s=\"%s\"}, but no series this "
                                    + "application emits carries that value for that label "
                                    + "— the rule evaluates to an empty vector forever and "
                                    + "never reports that it is doing so", rule.name(),
                                    metric, label, value)
                            .contains(value);
                }
            }
        }
        assertThat(checked)
                .as("the guard must not go vacuous: the outcome/status selectors on "
                        + "EnvironmentAuthDown, EnvironmentLoginFailing, "
                        + "EnvironmentDeadBotRatioHigh, GameNoRounds and "
                        + "EnvironmentGroupRecoveryFlapping are why it exists")
                .isGreaterThanOrEqualTo(4);
    }

    @Test
    @DisplayName("a rule that renders $value as a bare number does not compute a ratio")
    void bareValueRulesAreNotRatios() {
        // The mirror of the test above: EnvironmentGroupDead says "{{ $value }} bot
        // group(s) DEAD", which is a count. If that expression ever grew a division, the
        // message would read "0.5 bot group(s) DEAD".
        for (Rule rule : rules()) {
            boolean bareValue = rule.annotations().values().stream()
                    .anyMatch(a -> a.toString().contains("{{ $value }}"));
            if (!bareValue) continue;
            assertThat(firstTopLevelAndOperand(rule.expr()))
                    .as("rule %s renders a bare $value, so it must not be a fraction", rule.name())
                    .doesNotContain("/");
        }
    }

    @Test
    @DisplayName("both sides of an `unless` reduce to the same label set, or the rule never fires")
    void unlessOperandsReduceToTheSameLabelSet() {
        // Implementation Note 1, as a standing guard. `unless` matches on the FULL label
        // set: one stray label on either side and the right operand excludes nothing, so
        // GameNoRounds would fire on every healthy game — or, the other way round, never.
        // Both failure modes are silent, and the rule is the one that catches the tx7
        // "0 sessions ever" case, i.e. the one nobody would notice was broken.
        int checked = 0;
        for (Rule rule : rules()) {
            String flat = rule.expr().replace('\n', ' ');
            int idx = topLevelIndexOf(flat, " unless ");
            if (idx < 0) continue;
            checked++;

            String left = flat.substring(0, idx);
            String right = flat.substring(idx + " unless ".length());
            Set<String> leftBy = preservedLabels(left);
            Set<String> rightBy = preservedLabels(right);

            assertThat(leftBy)
                    .as("rule %s: the two `unless` operands aggregate by different labels, "
                            + "so they can never pair up", rule.name())
                    .isEqualTo(rightBy)
                    .isNotNull();

            // And every label in that shared set must actually exist on both metrics —
            // a by() clause naming a label the series does not carry silently produces a
            // different (smaller) label set on that side.
            for (String side : new String[]{left, right}) {
                for (String metric : metricNames(side)) {
                    if (isExternal(metric)) continue;
                    assertThat(scrapedLabels(metric))
                            .as("rule %s aggregates %s by %s, but %s does not carry all of them",
                                    rule.name(), metric, leftBy, metric)
                            .containsAll(leftBy);
                }
            }
        }
        assertThat(checked).as("GameNoRounds is the reason this guard exists").isEqualTo(1);
    }

    /** Index of {@code needle} outside any parentheses, or {@code -1}. */
    private static int topLevelIndexOf(String expr, String needle) {
        int depth = 0;
        for (int i = 0; i < expr.length(); i++) {
            char c = expr.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (depth == 0 && expr.startsWith(needle, i)) return i;
        }
        return -1;
    }

    /** The expression up to the first top-level {@code and} — what {@code $value} comes from. */
    private static String firstTopLevelAndOperand(String expr) {
        String flat = expr.replace('\n', ' ');
        int depth = 0;
        for (int i = 0; i < flat.length(); i++) {
            char c = flat.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (depth == 0 && flat.startsWith(" and ", i)) {
                return flat.substring(0, i);
            }
        }
        return flat;
    }

    @Test
    @DisplayName("the recovery counters are scraped at 0 before they move, or their rules read 0 forever")
    void theRecoveryCountersExistBeforeTheyMove() {
        // Round-3 review finding 1. EnvironmentGroupRecoveryExhausted is
        // `increase(group_recovery_exhausted_total[15m]) > 0` over a counter that moves
        // at most ONCE per death episode. Registered lazily at increment time, that
        // series is absent, then 1, then 1 — increase() is last-first = 0, and
        // Prometheus' counter-start extrapolation is gated on resultValue > 0, so it
        // never applies. The rule yields 0 for ever and the hand-off ("auto-recovery
        // has given up, a human must act") is never delivered for a group's FIRST
        // exhaustion, which is the only one that normally happens: the budget is
        // in-memory and a JVM restart resets it. EnvironmentGroupRecoveryFlapping's
        // `>= 3` had the milder form of the same defect — from a series first seen at
        // 1 it needed a fourth self-heal.
        //
        // Nothing else in this file can see that: every other test here asks whether a
        // series and its labels EXIST, and the defect is about WHEN. So this one
        // renders a fresh exposition the way the scheduler's first attempt does — only
        // the pre-registration, no increment at all — and asserts the zero samples are
        // really scraped.
        PrometheusMeterRegistry fresh = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        fresh.config().meterFilter(new BotMdcTagsMeterFilter());
        try {
            BotMdc.setGroupContext("group-uuid-1", "env-uuid-1", "116");
            new BotMetrics(fresh).initGroupRecoverySeries("success", "failed", "error");
            MDC.clear();
            String firstAttempt = fresh.scrape();

            assertThat(firstAttempt)
                    .as("the hand-off counter must be scrapeable at 0 from the first "
                            + "attempt — a counter whose first sample is 1 is invisible "
                            + "to increase()")
                    .containsPattern("(?m)^group_recovery_exhausted_total\\{[^}]*} 0\\.0$");
            assertThat(firstAttempt)
                    .as("outcome=\"success\" too, or EnvironmentGroupRecoveryFlapping's "
                            + ">= 3 silently means 4")
                    .containsPattern(
                            "(?m)^group_recovery_attempts_total\\{[^}]*outcome=\"success\"[^}]*} 0\\.0$");

            // And the zero series must carry the labels the rules route and render on,
            // which is also the label set the later increment will use.
            for (String line : firstAttempt.split("\n")) {
                if (!line.startsWith("group_recovery_")) continue;
                assertThat(line).contains("botGroupId=\"group-uuid-1\"",
                        "environmentId=\"env-uuid-1\"", "product=\"116\"");
            }
        } finally {
            MDC.clear();
            fresh.close();
        }
    }
}
