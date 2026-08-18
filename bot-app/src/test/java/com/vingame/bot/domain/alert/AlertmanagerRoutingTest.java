package com.vingame.bot.domain.alert;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AD-V9 — {@code alertmanager/alertmanager.yml} must fan {@code BotManagerDown} out to
 * <b>both</b> delivery paths.
 * <p>
 * This is the one alert whose delivery cannot be exercised by running the system: it fires
 * only when bot-manager is down, and proving it end to end means taking the fleet offline
 * for two minutes. Everything about that path that <em>can</em> be checked statically is
 * checked here instead, in the build, rather than being discovered during the outage it
 * exists to report.
 * <p>
 * The routing walk below is not decoration. Alertmanager's rule is that a child route which
 * matches <b>consumes</b> the alert — {@code continue: true} continues to the next
 * <em>sibling</em>, not to the parent's own receiver. So the natural-looking
 * "one route, {@code continue: true}" spelling sends {@code BotManagerDown} to the shim and
 * <em>never</em> to bot-manager, silently losing the RESOLVED half that AD-V9 pairs the two
 * receivers to obtain. That failure is invisible in review and invisible until an outage
 * recovers without saying so. Hence a test that walks the tree the way Alertmanager does.
 * <p>
 * It also pins AD-2: this file names only in-network compose services, so no VipTalk token
 * or hostname may appear in it. That is what lets it stay committed rather than being
 * rendered from a template at deploy time.
 */
@DisplayName("alertmanager/alertmanager.yml — the app-down alert reaches both paths (AD-V9)")
class AlertmanagerRoutingTest {

    /** Surefire runs with the module directory as CWD; the file lives at the repo root. */
    private static final List<Path> CANDIDATE_PATHS = List.of(
            Path.of("..", "alertmanager", "alertmanager.yml"),
            Path.of("alertmanager", "alertmanager.yml"));

    private static final String APP_DOWN_ALERT = "BotManagerDown";
    private static final String APP_RECEIVER = "viptalk";
    private static final String OUT_OF_BAND_RECEIVER = "viptalk-static-down";

    @SuppressWarnings("unchecked")
    private static Map<String, Object> config() {
        Path path = CANDIDATE_PATHS.stream().filter(Files::isRegularFile).findFirst().orElse(null);
        // Absent only in a module-only build from an unusual CWD; skipping beats failing a
        // build for a file this module does not own.
        Assumptions.assumeTrue(path != null,
                "alertmanager/alertmanager.yml not found from " + Path.of("").toAbsolutePath());
        try (InputStream in = Files.newInputStream(path)) {
            return (Map<String, Object>) new Yaml().load(in);
        } catch (Exception e) {
            throw new AssertionError("alertmanager/alertmanager.yml is not readable YAML", e);
        }
    }

    private static String rawFile() {
        Path path = CANDIDATE_PATHS.stream().filter(Files::isRegularFile).findFirst().orElse(null);
        Assumptions.assumeTrue(path != null, "alertmanager/alertmanager.yml not found");
        try {
            return Files.readString(path);
        } catch (Exception e) {
            throw new AssertionError("alertmanager/alertmanager.yml is not readable", e);
        }
    }

    /**
     * Which receivers an alert with these labels reaches, walking the tree exactly as
     * Alertmanager does: children in order; a matching child consumes the alert and only
     * {@code continue: true} lets the walk reach the next sibling; the node's own receiver
     * applies <b>only</b> when no child matched.
     */
    @SuppressWarnings("unchecked")
    private static List<String> receiversFor(Map<String, Object> route, Map<String, String> labels) {
        List<String> matched = new ArrayList<>();
        List<Map<String, Object>> children = (List<Map<String, Object>>) route.get("routes");
        if (children != null) {
            for (Map<String, Object> child : children) {
                if (!matches(child, labels)) continue;
                matched.addAll(receiversFor(child, labels));
                if (!Boolean.TRUE.equals(child.get("continue"))) break;
            }
        }
        if (matched.isEmpty()) {
            matched.add(Objects.toString(route.get("receiver"), null));
        }
        return matched;
    }

    /**
     * Supports the one matcher form this file uses, {@code label = "value"}, and fails loudly
     * on anything else rather than quietly treating it as a non-match — a test that silently
     * stops matching would pass while the routing it guards had changed underneath it.
     */
    @SuppressWarnings("unchecked")
    private static boolean matches(Map<String, Object> route, Map<String, String> labels) {
        List<String> matchers = (List<String>) route.get("matchers");
        if (matchers == null || matchers.isEmpty()) {
            return true;
        }
        for (String matcher : matchers) {
            int eq = matcher.indexOf('=');
            assertThat(eq)
                    .as("unsupported matcher syntax in alertmanager.yml: %s", matcher)
                    .isGreaterThan(0);
            String operator = matcher.substring(eq - 1, eq + 1).strip();
            assertThat(operator)
                    .as("matcher %s uses an operator this test cannot evaluate — extend the test "
                            + "rather than letting it silently report a non-match", matcher)
                    .isEqualTo("=");
            String name = matcher.substring(0, eq - 1).strip();
            String value = matcher.substring(eq + 1).strip().replaceAll("^\"|\"$", "");
            if (!value.equals(labels.get(name))) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> receiver(String name) {
        for (Map<String, Object> receiver : (List<Map<String, Object>>) config().get("receivers")) {
            if (name.equals(receiver.get("name"))) {
                return receiver;
            }
        }
        throw new AssertionError("no receiver named " + name + " in alertmanager.yml");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> webhook(String receiverName) {
        List<Map<String, Object>> webhooks =
                (List<Map<String, Object>>) receiver(receiverName).get("webhook_configs");
        assertThat(webhooks).as("receiver %s must have a webhook_config", receiverName).isNotEmpty();
        return webhooks.get(0);
    }

    private static Map<String, String> labels(String alertname) {
        Map<String, String> labels = new HashMap<>();
        labels.put("alertname", alertname);
        return labels;
    }

    @Test
    @DisplayName("BotManagerDown reaches the out-of-band shim AND the app webhook")
    void theAppDownAlertReachesBothPaths() {
        List<String> receivers = receiversFor(castRoute(), labels(APP_DOWN_ALERT));

        assertThat(receivers)
                .as("the shim delivers the FIRING half while bot-manager is dead — without it "
                        + "the app-down notice is delivered by the app that is down (AD-V8b)")
                .contains(OUT_OF_BAND_RECEIVER);
        assertThat(receivers)
                .as("the app webhook delivers the RESOLVED half, which the shim's fixed text "
                        + "cannot say. A matching child route CONSUMES the alert, so this only "
                        + "holds if a second sibling route sends it here too (AD-V9)")
                .contains(APP_RECEIVER);
    }

    @Test
    @DisplayName("every other alert still goes to the app webhook only")
    void ordinaryAlertsAreUnaffected() {
        assertThat(receiversFor(castRoute(), labels("EnvironmentSocketDown")))
                .as("the app-down route must match on alertname alone and steal nothing else")
                .containsExactly(APP_RECEIVER);
    }

    @Test
    @DisplayName("the shim receiver never sends the resolved half, the app receiver always does")
    void resolvedHalfComesFromTheAppOnly() {
        assertThat(webhook(OUT_OF_BAND_RECEIVER).get("send_resolved"))
                .as("the shim's text is a fixed string held in the container; publishing it on "
                        + "recovery would announce an outage at the moment it ended (AD-V9)")
                .isEqualTo(false);
        assertThat(webhook(APP_RECEIVER).get("send_resolved"))
                .as("the recovery notice for BotManagerDown — and for every other alert — comes "
                        + "from here; the app is reachable again by definition when it resolves")
                .isEqualTo(true);
    }

    @Test
    @DisplayName("both receivers are in-network compose services, so the file holds no secret")
    void theFileNamesOnlyInNetworkServices() {
        assertThat(webhook(APP_RECEIVER).get("url"))
                .isEqualTo("http://bot-manager:8085/api/v1/alerts/alertmanager");
        assertThat(webhook(OUT_OF_BAND_RECEIVER).get("url").toString())
                .as("the shim must be reached over the compose network, not via a host port")
                .startsWith("http://viptalk-shim:");

        // AD-2. The VipTalk token and base URL live in the shim container's environment,
        // from secrets.env — never in a committed file. This is what lets alertmanager.yml
        // stay a plain committed file instead of an envsubst-rendered, gitignored one.
        assertThat(rawFile().toLowerCase())
                .as("alertmanager.yml must never name the VipTalk API or carry a token")
                .doesNotContain("api.viptalk.org")
                .doesNotContain("sendmessage");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castRoute() {
        return (Map<String, Object>) config().get("route");
    }
}
