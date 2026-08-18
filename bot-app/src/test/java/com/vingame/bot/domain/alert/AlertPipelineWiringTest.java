package com.vingame.bot.domain.alert;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Phases 5–6 delivery path spans five files that nothing else compares:
 * {@code prometheus/alerts.yml}, {@code prometheus/prometheus.yml},
 * {@code alertmanager/alertmanager.yml}, {@code docker-compose.yml} and
 * {@code viptalk-shim/shim.py}. Each is individually plausible; the failure mode is that
 * they disagree — a rule selecting {@code job="node"} that no scrape job produces, a
 * receiver pointing at a compose service that is not there, a shim listening on a port
 * Alertmanager does not post to, an environment variable spelled one way in compose and
 * another in the script.
 * <p>
 * Every one of those is silent. Prometheus does not warn about a rule that can never
 * match; Alertmanager logs a connection error into a container nobody is tailing; the
 * shim happily starts up disabled because the token arrived under a name it does not
 * read. And they are all only exercised during the outage the whole path exists to
 * report, which is the worst possible time to discover any of them.
 * <p>
 * This does <b>not</b> substitute for the release-time checks — that Alertmanager can
 * actually reach the shim over the compose network, and that the shim can actually reach
 * {@code api.viptalk.org}. Those are host facts and are called out as such in the QA
 * verdict. What it does is remove every reason for those checks to fail that is knowable
 * from the repository.
 */
@DisplayName("alerting pipeline — the five files agree with each other")
class AlertPipelineWiringTest {

    private static Path repoFile(String... parts) {
        Path relative = Path.of(parts[0], java.util.Arrays.copyOfRange(parts, 1, parts.length));
        Path fromModule = Path.of("..").resolve(relative);
        Path path = Files.isRegularFile(fromModule) ? fromModule
                : Files.isRegularFile(relative) ? relative : null;
        Assumptions.assumeTrue(path != null,
                relative + " not found from " + Path.of("").toAbsolutePath());
        return path;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> yaml(String... parts) {
        Path path = repoFile(parts);
        try (InputStream in = Files.newInputStream(path)) {
            return (Map<String, Object>) new Yaml().load(in);
        } catch (Exception e) {
            throw new AssertionError(path + " is not readable YAML", e);
        }
    }

    private static String text(String... parts) {
        try {
            return Files.readString(repoFile(parts));
        } catch (Exception e) {
            throw new AssertionError("unreadable: " + String.join("/", parts), e);
        }
    }

    private static Map<String, Object> alerts() {
        return yaml("prometheus", "alerts.yml");
    }

    private static Map<String, Object> prometheus() {
        return yaml("prometheus", "prometheus.yml");
    }

    private static Map<String, Object> alertmanager() {
        return yaml("alertmanager", "alertmanager.yml");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> composeServices() {
        return (Map<String, Object>) yaml("docker-compose.yml").get("services");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> composeService(String name) {
        Object service = composeServices().get(name);
        assertThat(service).as("docker-compose.yml declares a `%s` service", name).isNotNull();
        return (Map<String, Object>) service;
    }

    /** Every {@code expr} in alerts.yml, flattened. */
    @SuppressWarnings("unchecked")
    private static List<String> expressions() {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> group : (List<Map<String, Object>>) alerts().get("groups")) {
            for (Map<String, Object> rule : (List<Map<String, Object>>) group.get("rules")) {
                Object expr = rule.get("expr");
                if (expr != null) out.add(expr.toString());
            }
        }
        assertThat(out).isNotEmpty();
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<String> scrapeJobNames() {
        List<String> names = new ArrayList<>();
        for (Map<String, Object> job : (List<Map<String, Object>>) prometheus().get("scrape_configs")) {
            names.add(Objects.toString(job.get("job_name"), null));
        }
        return names;
    }

    @SuppressWarnings("unchecked")
    private static List<String> scrapeTargets(String jobName) {
        for (Map<String, Object> job : (List<Map<String, Object>>) prometheus().get("scrape_configs")) {
            if (!jobName.equals(job.get("job_name"))) continue;
            List<String> targets = new ArrayList<>();
            for (Map<String, Object> sc : (List<Map<String, Object>>) job.get("static_configs")) {
                targets.addAll((List<String>) sc.get("targets"));
            }
            return targets;
        }
        throw new AssertionError("no scrape job named " + jobName);
    }

    // ------------------------------------------------------------------ rules ↔ scrape

    @Test
    @DisplayName("every job= a rule selects on is a job Prometheus actually scrapes")
    void ruleJobSelectorsMatchScrapeJobs() {
        // A rule selecting a job that does not exist matches nothing, fires never, and
        // reports nothing about its own uselessness. BotManagerDown, BotManagerRestarted
        // and NodeExporterDown are all of this shape.
        Pattern jobSelector = Pattern.compile("job\\s*=\\s*\"([^\"]+)\"");
        Set<String> selected = new LinkedHashSet<>();
        for (String expr : expressions()) {
            Matcher m = jobSelector.matcher(expr);
            while (m.find()) selected.add(m.group(1));
        }

        assertThat(selected)
                .as("sanity: the rules do select on job at all")
                .contains("bot-manager", "node");
        assertThat(scrapeJobNames()).containsAll(selected);
    }

    @Test
    @DisplayName("the node job targets the node-exporter service on its own port")
    void nodeScrapeJobTargetsTheNodeExporterService() {
        // Phase 5's whole point is a source of host metrics that survives the app dying,
        // so this target must be the separate container and NOT bot-manager.
        assertThat(scrapeTargets("node")).containsExactly("node-exporter:9100");
        Map<String, Object> exporter = composeService("node-exporter");

        assertThat(Objects.toString(exporter.get("image"), "")).startsWith("prom/node-exporter");
        assertThat(exporter.get("pid"))
                .as("without the host PID namespace the filesystem collector reads this "
                        + "CONTAINER's mount table, and mountpoint=\"/\" is then the wrong "
                        + "filesystem — the disk rules would silently watch nothing")
                .isEqualTo("host");
        assertThat(exporter.get("volumes").toString())
                .as("the host root must be bind-mounted for --path.rootfs to resolve")
                .contains("/:/host:ro");
        assertThat(exporter.get("command").toString()).contains("--path.rootfs=/host");
        assertThat(exporter)
                .as("node-exporter must not depend on bot-manager: host metrics are most "
                        + "needed exactly when the app is gone")
                .doesNotContainKey("depends_on");
    }

    @Test
    @DisplayName("the host disk rules select the mountpoint the exporter is not told to exclude")
    void hostDiskRulesSelectAMountpointTheExporterStillReports() {
        // Read the flag from the raw file: the parsed command list stringifies with
        // brackets and commas that would corrupt the regex.
        Matcher m = Pattern.compile("--collector\\.filesystem\\.mount-points-exclude=([^\"\\s]+)")
                .matcher(text("docker-compose.yml"));
        assertThat(m.find()).as("the exclusion flag is present in the command").isTrue();
        // Compose escapes a literal `$` as `$$`; the exporter sees a single one.
        String regex = m.group(1).replace("$$", "$");

        // node-exporter matches this regex unanchored-at-the-end (Go MatchString), so the
        // leading `^` anchors the start and `($|/)` lets it exclude a whole subtree.
        Pattern exclusion = Pattern.compile(regex);

        // Every host rule selects mountpoint="/" — if the exclusion regex matched it, the
        // series would not exist and HostDiskSpaceLow/Critical would be permanently silent.
        assertThat(exclusion.matcher("/").find())
                .as("mount-points-exclude regex %s must NOT match \"/\"", regex)
                .isFalse();
        // And the exclusions Phase 5 says it keeps: per-container overlay noise stays out.
        assertThat(exclusion.matcher("/var/lib/docker/overlay2/abc/merged").find())
                .as("per-container overlay mounts must stay excluded, or every container "
                        + "layer becomes its own near-full filesystem series")
                .isTrue();
        assertThat(exclusion.matcher("/proc/sys/fs/binfmt_misc").find())
                .as("upstream's own defaults must be preserved: naming this flag REPLACES "
                        + "them, so dropping one silently re-adds a family of noise series")
                .isTrue();
        assertThat(exclusion.matcher("/host").find())
                .as("the exporter's own bind target must not report as a filesystem of its own")
                .isTrue();
        // The /host prefix is stripped before the mountpoint label is set, so a rule that
        // selected mountpoint="/host" would find nothing — this is why the rules say "/".
        assertThat(text("prometheus", "alerts.yml")).doesNotContain("mountpoint=\"/host\"");
    }

    // ---------------------------------------------------- rules file ↔ prometheus ↔ compose

    @Test
    @DisplayName("the rules file Prometheus loads is the one in this repository")
    void theRuleFileIsMountedWherePrometheusLooksForIt() {
        Object ruleFiles = prometheus().get("rule_files");
        assertThat(ruleFiles.toString()).contains("/etc/prometheus/alerts.yml");

        // Bind mount, not baked into the image: an alerts.yml that is edited here but
        // never reaches the container is indistinguishable from a rule that does not fire.
        assertThat(composeService("prometheus").get("volumes").toString())
                .contains("./prometheus/alerts.yml:/etc/prometheus/alerts.yml");
    }

    @Test
    @DisplayName("Prometheus hands alerts to the alertmanager service compose declares")
    void prometheusPointsAtTheAlertmanagerService() {
        assertThat(prometheus().get("alerting").toString()).contains("alertmanager:9093");
        Map<String, Object> service = composeService("alertmanager");
        assertThat(Objects.toString(service.get("image"), "")).startsWith("prom/alertmanager");
        assertThat(service.get("volumes").toString())
                .contains("./alertmanager/alertmanager.yml:/etc/alertmanager/alertmanager.yml");
    }

    // ------------------------------------------------------------- alertmanager ↔ shim

    @Test
    @DisplayName("the shim receiver URL names the compose service, on the port the shim defaults to")
    @SuppressWarnings("unchecked")
    void theShimReceiverUrlMatchesTheShimService() {
        // The closest a build can get to "Alertmanager has actually POSTed to the shim":
        // the hostname must be the compose service name, and the port must be the one the
        // script binds by default, since compose sets no VIPTALK_SHIM_PORT override.
        String url = null;
        for (Map<String, Object> receiver : (List<Map<String, Object>>) alertmanager().get("receivers")) {
            if ("viptalk-static-down".equals(receiver.get("name"))) {
                url = ((List<Map<String, Object>>) receiver.get("webhook_configs"))
                        .get(0).get("url").toString();
            }
        }
        assertThat(url).as("the viptalk-static-down receiver exists").isNotNull();

        Matcher m = Pattern.compile("http://([^:/]+):(\\d+)(/\\S*)?").matcher(url);
        assertThat(m.matches()).as("receiver url %s is host:port form", url).isTrue();
        String host = m.group(1);
        String port = m.group(2);

        assertThat(composeServices()).containsKey(host);

        Matcher defaultPort = Pattern.compile("VIPTALK_SHIM_PORT\"?\\s*,\\s*\"(\\d+)\"")
                .matcher(text("viptalk-shim", "shim.py"));
        assertThat(defaultPort.find()).as("shim.py declares a default port").isTrue();
        assertThat(port)
                .as("Alertmanager posts to :%s but shim.py binds :%s by default and compose "
                        + "sets no override — the outage notice would hit a closed port",
                        port, defaultPort.group(1))
                .isEqualTo(defaultPort.group(1));
        assertThat(composeService(host).get("environment").toString())
                .as("compose must not override the port out from under the receiver URL")
                .doesNotContain("VIPTALK_SHIM_PORT");
    }

    @Test
    @DisplayName("the shim container shares nothing with bot-manager, which is its entire purpose")
    void theShimIsIndependentOfTheApp() {
        Map<String, Object> shim = composeService("viptalk-shim");
        assertThat(shim)
                .as("a depends_on here would recreate the bootstrap problem AD-V8b exists "
                        + "to remove: the app-down notifier must not wait on the app")
                .doesNotContainKey("depends_on");
        assertThat(shim.get("volumes").toString())
                .as("the script is bind-mounted, so deploy.sh's plain `compose up` is enough "
                        + "— an image that needed building is one more thing to get wrong")
                .contains("./viptalk-shim/shim.py:/app/shim.py");
        assertThat(shim.get("restart")).isEqualTo("unless-stopped");
    }

    @Test
    @DisplayName("every VIPTALK_* variable compose passes the shim is one the shim reads")
    void composeAndShimAgreeOnEnvironmentVariableNames() {
        // A typo here is invisible: the shim self-disables on blank config and logs that it
        // did, which looks exactly like a deliberately unconfigured non-prod instance.
        String script = text("viptalk-shim", "shim.py");
        List<String> declared = new ArrayList<>();
        for (Object entry : (List<?>) composeService("viptalk-shim").get("environment")) {
            String name = entry.toString().split("=", 2)[0];
            if (name.startsWith("VIPTALK_")) declared.add(name);
        }

        assertThat(declared)
                .as("compose passes the shim its whole configuration surface")
                .contains("VIPTALK_BOT_TOKEN", "VIPTALK_OPS_ROOM_ID", "VIPTALK_DOWN_ROOM_IDS",
                        "VIPTALK_INSTANCE_LABEL", "VIPTALK_BASE_URL");
        for (String name : declared) {
            assertThat(script)
                    .as("docker-compose.yml passes %s to viptalk-shim, but shim.py never reads "
                            + "it — the value is silently discarded", name)
                    .contains("\"" + name + "\"");
        }
    }

    @Test
    @DisplayName("GAP, pinned: VIPTALK_ENABLED does not gate the shim")
    void theMasterSwitchDoesNotReachTheShim() {
        // secrets.env.example calls VIPTALK_ENABLED the "master switch", and it is — for the
        // app. The shim has its own notion of enabled (a token plus at least one room) and
        // never reads it, so on a host with the token and ops room filled in but alerting
        // deliberately switched off, BotManagerDown still reaches VipTalk through the shim
        // while every other alert is silent.
        //
        // Defensible: the app-down notice is the one alert you least want a stale switch to
        // suppress. But it is a surprise — "alerting is off" is not true while it holds, and
        // a maintenance window that deliberately silences the rooms will still hear from
        // every bot-manager restart that lasts past `for: 2m`. Pinned so it stays a decision
        // rather than an accident; the fix, if wanted, is one env var and one `if`.
        assertThat(composeService("viptalk-shim").get("environment").toString())
                .doesNotContain("VIPTALK_ENABLED");
        assertThat(text("viptalk-shim", "shim.py")).doesNotContain("VIPTALK_ENABLED");
        assertThat(text("secrets.env.example"))
                .as("the template does describe VIPTALK_ENABLED as a master switch, which is "
                        + "what makes the asymmetry worth writing down")
                .contains("VIPTALK_ENABLED");
    }

    @Test
    @DisplayName("the shim's customer text is byte-identical to BotManagerDown's public_summary")
    @SuppressWarnings("unchecked")
    void theTwoDeliveryPathsUseTheSameCustomerWording() {
        // Both paths describe the SAME outage — the shim delivers it while the app is down,
        // the app delivers the recovery. Two different wordings would read as two different
        // incidents in a product room. Both files say they are kept in step; this is what
        // makes that a fact rather than an intention.
        String publicSummary = null;
        for (Map<String, Object> group : (List<Map<String, Object>>) alerts().get("groups")) {
            for (Map<String, Object> rule : (List<Map<String, Object>>) group.get("rules")) {
                if (!"BotManagerDown".equals(rule.get("alert"))) continue;
                publicSummary = ((Map<String, Object>) rule.get("annotations"))
                        .get("public_summary").toString().strip();
            }
        }
        assertThat(publicSummary).isNotNull();

        // DEFAULT_DOWN_TEXT is an implicitly-concatenated Python string literal.
        Matcher m = Pattern.compile("DEFAULT_DOWN_TEXT\\s*=\\s*\\((.*?)\\)", Pattern.DOTALL)
                .matcher(text("viptalk-shim", "shim.py"));
        assertThat(m.find()).as("shim.py declares DEFAULT_DOWN_TEXT").isTrue();
        StringBuilder shimText = new StringBuilder();
        Matcher chunk = Pattern.compile("\"([^\"]*)\"").matcher(m.group(1));
        while (chunk.find()) shimText.append(chunk.group(1));

        assertThat(shimText.toString()).isEqualTo(publicSummary);
    }

    @Test
    @DisplayName("secrets.env.example documents every variable the shim needs from the host")
    void thePipelineSecretsAreDocumented() {
        // deploy.sh renders .env from secrets.env; anything absent from the template is a
        // variable an operator has no way to know they were supposed to set.
        String example = text("secrets.env.example");
        assertThat(example).contains("VIPTALK_BOT_TOKEN")
                .contains("VIPTALK_OPS_ROOM_ID")
                .contains("VIPTALK_DOWN_ROOM_IDS")
                .contains("VIPTALK_INSTANCE_LABEL");
        // A template that carried a real value would put a token or a live room ID into
        // git the moment someone filled it in on the wrong file. Every secret-bearing key
        // must be assigned empty; illustrative room IDs are fine inside comments.
        for (String line : example.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("#") || !trimmed.contains("=")) continue;
            String key = trimmed.split("=", 2)[0];
            if (!key.equals("VIPTALK_BOT_TOKEN")
                    && !key.equals("VIPTALK_OPS_ROOM_ID")
                    && !key.equals("VIPTALK_DOWN_ROOM_IDS")) continue;
            assertThat(trimmed)
                    .as("%s must be blank in the committed template", key)
                    .isEqualTo(key + "=");
        }
    }
}
