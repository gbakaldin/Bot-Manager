# Release — PLUGIN_HOT_RELOAD (Phase 1)

Mode: bot
Target: **Bot-1 (staging)**
Branch: `feature/plugin-hot-reload`
Commit: `4d3bca7` — *docs(metrics): say why countBotsByPluginVersion has no null-env skip*
Image: `vingame-bot:latest` — `sha256:9e8df6d919d6975211c9b5b38100051404d282be2ceb37276159adee32ef7a80`, `linux/amd64`, built `2026-08-26T14:06:05Z`
Date: `2026-08-26T14:08:18Z` (deploy completed)

> **7-day metaspace baseline starts `2026-08-26T14:08:18Z` (UTC).** Re-read at or after
> **`2026-09-02T14:08:18Z`**. This is the deliverable that sizes `-XX:MaxMetaspaceSize`
> at AD-6 / step 4. See § P1-10.

Built from `4d3bca7` as-is. No merge, no rebase, nothing committed, nothing pushed.
The pre-existing working-tree dirt (staged `TaiXiuMessages/*.js` deletions, modified
`deploy.sh` / `docs/plans/AVIATOR_BOT.md` / `docs/reviews/VIPTALK_ALERTING_V2/release.md`,
untracked `docs/reviews/PLUGIN_HOT_RELOAD/review.md`, untracked `.claude/worktrees/`) was
left untouched. The only file written by this release is this one.

---

## What shipped

This is **not** a jar-only deploy. Phase 1's deliverable is observability artefacts, so
three things had to land. All three were verified by checksum on the host **before** the
stack was restarted.

| # | Artefact | Host path | Pre-deploy md5 | Post-deploy md5 | Result |
|---|---|---|---|---|---|
| 1 | App image | `bot.tar` | (previous build) | `dd1bc7a0b4be5881eab90fbdbb7b6944` | matches local |
| 2 | Alert rules | `prometheus/alerts.yml` | `28fdd193e05ef9c9d0a7c8d81ffc6fa6` | `6452ea26ee4f1799a89ff21cadaaf70e` | matches local |
| 3 | Dashboard | `grafana/provisioning/dashboards/plugin-runtime.json` | **absent** | `ae4515c38987e401c82f1e4655debfa1` | matches local |

The host's previous `alerts.yml` was backed up to
`prometheus/alerts.yml.bak-predeploy-20260826-210637` before being overwritten.

### Config-drift audit (pre-flight)

Every other bind-mounted config was checksummed local-vs-host **before** deploying, to be
sure nothing else was silently stale or silently about to change:

```
deploy.sh                                    9fe2dea89dcf1a5332cd73f21c94b7d0  IDENTICAL
docker-compose.yml                           3b97667bb94ebdefcddfadfcd54b70ae  IDENTICAL
prometheus/prometheus.yml                    4fb02c864e236f068252f4fe5937f18d  IDENTICAL
promtail-config.yml                          f104a4d408d71947c5e115c840b1e7df  IDENTICAL
logging/log4j2.properties                    2f750f5fed1cbe0e0efa156666295ec2  IDENTICAL
grafana/provisioning/dashboards/dashboards.yml  2eb66fe6f049d1de1edbdb60094266ce  IDENTICAL
prometheus/alerts.yml                        DIFFERED  -> shipped
grafana/.../plugin-runtime.json              ABSENT    -> shipped
```

`logging/log4j2.properties` already matching is worth stating explicitly: that is the known
deploy hazard where a missing file stops bot-manager booting. It needed no action.

`deploy.sh` was **not** shipped and **not** committed — the working-tree copy is already
byte-identical to the host's. Its uncommitted `secrets.env` → `.env` merge was therefore
untouched.

### Stale-name guard on the shipped artefacts (Amendment A2)

Both artefacts were checked for the unreachable `_created_total` spelling before shipping:

- `prometheus/alerts.yml` — valid YAML, 6 groups, 18 rules, `MetaspaceGrowth` present.
- `plugin-runtime.json` — valid JSON, title `Plugin runtime`, uid `plugin-runtime`, 8 panels.
- All 11 panel queries enumerated. Every classloader query uses
  `plugin_classloaders_registered_total` / `_live` / `_reclaimed_total`.
- One literal `plugin_classloaders_total` appears in the file — it is inside a panel
  **`description` string** that documents this exact trap ("`_created` is a reserved
  Prometheus suffix … so this panel queried an empty vector forever"). It is prose, not a
  query. Not stale.

---

## Build

- `mvn clean install`: **PASS** (46.123 s, `BUILD SUCCESS`, all 6 reactor modules)
- Test totals, from the surefire XML rather than the console tail:

| Module | Tests | Failures | Errors | Skipped |
|---|---|---|---|---|
| bot-api | 128 | 0 | 0 | 0 |
| bot-strategies | 111 | 0 | 0 | 0 |
| bot-messages | 136 | 0 | 0 | 0 |
| bot-engine | 426 | 0 | 0 | 0 |
| bot-app | 1091 | 0 | 0 | 0 |
| **TOTAL** | **1892** | **0** | **0** | **0** |

  This is an **exact match** to the coordinator's independent run (128/111/136/426/1091 =
  1892, 0/0/0). The build was re-run rather than trusted; had it disagreed, the instruction
  was to stop.
- Contract-module reverse-edge check (plan's local gate):
  `mvn -q -pl bot-api dependency:tree | grep -E 'bot-app|bot-engine|bot-strategies|bot-messages'`
  → **no output**. **PASS** — `bot-api` still has no reverse dependency.
- `docker build --no-cache --platform linux/amd64`: **PASS**
  (`2026-08-26T14:05:44Z` → `14:06:06Z`). Image confirmed `arch=amd64/linux`.
  *Note: the Docker daemon was not running at first attempt and was started; the failed
  attempt produced no artefact and the build was re-run clean.*
- `docker save`: **PASS** — `bot.tar`, 393,095,680 bytes.

## Ship

- `sftp put bot.tar`: **PASS**
- `sftp put prometheus/alerts.yml`: **PASS**
- `sftp put grafana/provisioning/dashboards/plugin-runtime.json`: **PASS**
- Post-transfer checksum verification of all three: **PASS** (table above)

## Deploy

Graceful `docker compose down`, not `deploy.sh`'s `docker kill` — an unclean kill is what
the evidence-shim's boot retro-promotion (AD-21) triggers on, and
`appender.detail.immediateFlush = false` means an unclean stop can lose the final ≤8 KB of
the detail file.

- `docker compose down`: **PASS** (exit 0; all 10 containers stopped and removed cleanly)
- `docker image rm vingame-bot:latest`: **PASS** (untagged `sha256:99681eb09dfd…`, 12 layers deleted)
- `docker load -i bot.tar`: **PASS** (`Loaded image: vingame-bot:latest`)
- `docker compose up -d`: **PASS** — mongo gated on `Healthy` before bot-manager started
- Host clock: down began `14:07:50Z`, up completed `14:08:18Z`. **~28 s of stack downtime.**

### `.env` / VipTalk merge survived

`docker compose down` does not remove `.env`, and `deploy.sh` was not re-run, so the
load-bearing `secrets.env` → `.env` merge was preserved. Confirmed **inside the running
container**, not merely on disk:

```
VIPTALK_ENABLED=true
VIPTALK_BOT_TOKEN=<present>
VIPTALK_OPS_ROOM_ID=!WfhvMgIVQUzZUoqDmm:matrix-uat.viptalk.org
VIPTALK_INSTANCE_LABEL=staging
VIPTALK_CUSTOMER_NOTICES_ENABLED=false
BOT_LOG_LEVEL -> LOGGING_LEVEL_COM_VINGAME_BOT=DEBUG
LOGGING_CONFIG=/app/config/log4j2.properties
LOGGING_LEVEL_COM_VINGAME_WEBSOCKETPARSER=INFO
```

Alerting is **not** inert.

## Smoke test

bot-manager and the whole observability stack share one Compose project, so a bot redeploy
restarts all of it. All of it was re-verified, not just bot-manager.

- Container healthy after `start_period`: **PASS** — `starting` at t=10 s and t=20 s,
  **`healthy` at t=30 s**.
- Spring Boot ready log: **PASS** —
  `14:08:30.527 [main] INFO Starter - Started Starter in 11.085 seconds (process running for 12.206)`
- Auto-start log: **PASS** —
  `14:08:29.738 [main] INFO BotGroupBehaviorService - Bot Manager startup complete. 4 bot groups running`

  All four groups re-initialised at full strength (15 + 20 + 100 + 20 = **155 bots**, equal
  to the pre-deploy `bots_managed`):

  | Group | Game | Type | Bots |
  |---|---|---|---|
  | `7a3716ed…` Auth test with socket | TaiXiu Seven | BETTING_MINI | 15/15 |
  | `40fa3749…` XD game test | Xoc Dia | BETTING_MINI | 20/20 |
  | `ab81f9e6…` BOM flow test 100 | Xoc Dia mini | BETTING_MINI | 100/100 |
  | `2bf237bd…` Slot group 120 | Slot 120 (clone of 204) | SLOT | 20/20 |

- Whole stack came back: **PASS** — all 10 containers up.

| Container | Status | Readiness probe |
|---|---|---|
| bot-manager | Up (healthy) | `/actuator/health` → `{"status":"UP"}` |
| prometheus | Up | `/-/ready` → `Prometheus Server is Ready.` |
| grafana | Up | `/api/health` → `{"database":"ok","version":"11.4.0"}` |
| loki | Up | `/ready` → `ready` (in-network) |
| alertmanager | Up | `/-/ready` → `OK` (in-network) |
| promtail | Up | tailing `/logs/console*.log` |
| mongo | Up (healthy) | health-gated before bot-manager start |
| evidence-shim | Up (healthy) | `GET /health` 200 |
| viptalk-shim | Up (healthy) | container health |
| node-exporter | Up | — |

  *Loki and Alertmanager publish no host port; both were probed from inside
  `bot-java_default` rather than reported as unverified.*

- `docker logs bot-java-bot-manager-1 | grep -c " ERROR "` → **0**.
- Evidence-shim boot behaviour: `2026-08-26 14:08:07 previous run shut down cleanly - no
  boot promotion`. The graceful stop was correctly detected — **no spurious AD-21
  retro-promotion**, which is the direct payoff of not using `deploy.sh`'s `docker kill`.

---

## Pre-deploy baseline (captured before the restart, for comparison)

| Reading | Pre-deploy value |
|---|---|
| `bots_managed` | 155 (097 = 115, 116 = 40) |
| `bot_bets_placed_total` series count | **0** |
| lines carrying `pluginVersion` | 0 |
| `plugin_classloaders_*` / `bots_by_plugin_version` | absent |
| Metaspace | 87,460,032 B (87.46 MB) |
| `jvm_classes_loaded_classes` | 16,494 |
| JVM uptime | 6.07 days |
| **`delta(jvm_memory_used_bytes{id="Metaspace"}[6d])`** | **+920,090 B (~0.9 MB / 6 d)** |
| `MetaspaceGrowth` in loaded rules | not present |

---

## Plan verification — `docs/plans/PLUGIN_HOT_RELOAD.md` § Phase 1

Container name on this host is `bot-java-bot-manager-1`, not `bot-manager`; commands were
adjusted accordingly. `$BOT = http://localhost:8080`.

### P1-1 — the app is up and the stack came back with it
Command: `curl -sf $BOT/actuator/health` ; `curl -sf http://localhost:9090/-/ready` ; `curl -sf http://localhost:3000/api/health`
Expected: HTTP 200 `{"status":"UP"…}`; `Prometheus Server is Ready`; Grafana 200.
Actual:
```
{"status":"UP","components":{"diskSpace":{"status":"UP","details":{"total":107362627584,
"free":71835897856,"threshold":10485760,"path":"/app/.","exists":true}},
"mongo":{"status":"UP","details":{"maxWireVersion":25}},"ping":{"status":"UP"},
"ssl":{"status":"UP",...}}}          [exit=0]
Prometheus Server is Ready.          [exit=0]
{"database":"ok","version":"11.4.0","commit":"b58701869e…"}   [exit=0]
```
Plus Loki `ready` and Alertmanager `OK` in-network (see smoke table).
Result: **PASS**

### P1-2 — the one-shot plugin-runtime INFO line is present
Command: `docker logs bot-java-bot-manager-1 2>&1 | grep -c 'plugin runtime: version=builtin'`
Expected: exactly **1**.
Actual: `1`. The line:
```
14:08:29.783 [main] INFO  PluginClassLoaderMetrics [//] - plugin runtime: version=builtin, classloader=598446861
```
Re-counted ~5 minutes later: still `1`. It fires once at startup and does not repeat.
Result: **PASS**

### P1-3 — the adopted JVM series are in the scrape under the names the alert uses
Command: `curl -sf $BOT/actuator/prometheus | grep -E '^jvm_memory_used_bytes\{.*id="Metaspace"'` and `… | grep -E '^jvm_classes_(loaded_classes|unloaded_classes_total)'`
Expected: one `Metaspace` sample > 0; both class-count series present.
Actual:
```
jvm_memory_used_bytes{application="bot-manager",area="nonheap",id="Metaspace"} 8.1757E7
jvm_classes_loaded_classes{application="bot-manager"} 16887.0
jvm_classes_unloaded_classes_total{application="bot-manager"} 183.0
```
Result: **PASS**

### P1-4 — the new classloader meters exist at their baseline values
Command: `curl -sf $BOT/actuator/prometheus | grep -E '^plugin_classloaders_'`
Expected: exactly three families, all `pluginVersion="builtin"`; `live = 1`,
`registered_total = 1`, `reclaimed_total = 0`.
Actual:
```
plugin_classloaders_live{application="bot-manager",pluginVersion="builtin"} 1.0
plugin_classloaders_reclaimed_total{application="bot-manager",pluginVersion="builtin"} 0.0
plugin_classloaders_registered_total{application="bot-manager",pluginVersion="builtin"} 1.0
```
Values are exactly 1 / 1 / 0 — present as zeros rather than missing, so eager registration
ran. Amendment A2 confirmed live: `grep -E 'plugin_classloaders_total|plugin_classloaders_created'`
against the scrape returns **nothing**, so the rename shipped and nothing stale is deployed.
HELP/TYPE strings are present and correct (`live` = gauge, both `_total` = counter).
Result: **PASS**

### P1-5 — every running bot is accounted for under `builtin`
Command: `curl -sf $BOT/actuator/prometheus | grep '^bots_by_plugin_version'` ; `… | grep -E '^bots_managed'`
Expected: one row per running group, all `pluginVersion="builtin"`, sum == `bots_managed`.
Actual — four rows, one per auto-started group, every one tagged `builtin`:
```
bots_by_plugin_version{…botGroupId="2bf237bd…",environmentId="ad4e7948…",pluginVersion="builtin",product="116"} 20.0
bots_by_plugin_version{…botGroupId="40fa3749…",environmentId="ad4e7948…",pluginVersion="builtin",product="116"} 20.0
bots_by_plugin_version{…botGroupId="7a3716ed…",environmentId="3cda38f9…",pluginVersion="builtin",product="097"} 15.0
bots_by_plugin_version{…botGroupId="ab81f9e6…",environmentId="3cda38f9…",pluginVersion="builtin",product="097"} 100.0

bots_managed{application="bot-manager"} 155.0
bots_managed_by_env{…environmentId="3cda38f9…",product="097"} 115.0
bots_managed_by_env{…environmentId="ad4e7948…",product="116"} 40.0
```
**Sum = 20+20+15+100 = 155.0 == `bots_managed` 155.0.** Invariant holds exactly; no
shortfall, so no bot's `pluginVersion` resolved to null. Per-environment also reconciles
(097: 15+100 = 115 ✓; 116: 20+20 = 40 ✓). A filter for rows *not* tagged `builtin` returns
nothing.
This was verified against the groups auto-start brought back; **no group was manually
started**, per instruction.
Result: **PASS**

### P1-6 — `pluginVersion` reached Loki, and only track 1
Command: `grep -m1 pluginVersion logs/console.log` ; `grep -c pluginVersion logs/detail/detail.log`
Expected: ≥1 JSON line in `console.log` containing `"pluginVersion":"builtin"`; **0** in
`detail.log`; and the Grafana/Loki query returns lines.
Actual — track 1, as a **JSON field**:
```
{"timestamp":"2026-08-26T14:08:24.762+0000","level":"WARN",
 "logger":"com.vingame.bot.domain.bot.core.Bot","thread":"reconnect-ws-authtestws9789",
 "message":"Bot authtestws9789: WS disconnected — starting retrial flow",
 "botGroupId":"7a3716ed-…","botId":"9","environmentId":"3cda38f9-…",
 "gameType":"BETTING_MINI","botUserName":"authtestws9789","pluginVersion":"builtin"}
```
`grep -c pluginVersion logs/console.log` → **19**.
`grep -c pluginVersion logs/detail/detail.log` → **0** (track 2's pattern deliberately
unchanged, AD-8).
Loki, `{job="bot-manager"} | json | pluginVersion="builtin"` over the last 30 min →
`"status":"success"`, streams returned with `"pluginVersion":"builtin"` extracted.
Result: **PASS**

**Additionally confirmed — `pluginVersion` is NOT a Loki stream label.** The full label set is:
```
["__stream_shard__","botGroupId","environmentId","filename","gameType","job","level","service_name"]
```
`pluginVersion` is absent, i.e. it is extracted at query time by `| json` and carries no
stream cardinality. Also confirmed promtail is tailing only `/logs/console*.log` and
**zero** files under `logs/detail/` — the non-recursive `__path__` subdirectory escape is
intact.

### P1-7 — no metric-cardinality regression on the bot counters
Command: `curl -sf $BOT/actuator/prometheus | grep -c '^bot_bets_placed_total'` ; `… | grep -c 'pluginVersion'`
Expected: `bot_bets_placed_total` series count unchanged from the pre-deploy baseline, and
no `bot_*` line carrying a `pluginVersion` label.
Actual:
- `bot_bets_placed_total` series count: **4** (pre-deploy baseline was **0**).
- Every metric family mentioning `pluginVersion`, exhaustively:
  `bots_by_plugin_version`, `plugin_classloaders_live`, `plugin_classloaders_reclaimed_total`,
  `plugin_classloaders_registered_total` — **exactly the four AD-5 permits**.
- `grep '^bot_' | grep 'pluginVersion'` → **no output**. No `bot_*` series carries the label.
- Total `pluginVersion`-bearing sample lines in the whole scrape: **7**.

Result: **PASS, with a deviation that is explained and is not this feature's**

The literal "series count unchanged" sub-condition reads 0 → 4, so it deserves a straight
answer rather than a tick. The cause is **not** PLUGIN_HOT_RELOAD:

- Commit `1e359a1` *feat(metrics): materialise the round-outcome counters at zero* is an
  ancestor of `4d3bca7` (verified with `git merge-base --is-ancestor`) but was **not** in
  the Aug-20 build running on the box. It deliberately pre-registers the four round-outcome
  counters at zero from `Bot.initialize()`.
- The four new series read **`0.0`** and are **one per group, for the four running groups** —
  not per bot. Their labels are `botGroupId / environmentId / gameId / gameName / gameType /
  product`; **no `pluginVersion`, no `botId`, no `botUserName`**.
- So this is bounded, group-scoped, and is the *intended fix* for the pre-existing staging
  condition below — an improvement in observability, not a cardinality regression.

The AD-5 intent P1-7 exists to protect — that `pluginVersion` must not leak onto the
high-cardinality bot counters — holds cleanly and unconditionally.

> **Known pre-existing staging condition, recorded so nobody rediscovers it as a
> regression:** `bot_bets_placed_total` had **zero series** before this deploy. That is the
> long-standing staging condition in which no `EndGame` frame ever arrives for these groups,
> and it predates PLUGIN_HOT_RELOAD entirely. This deploy did not cause it and does not fix
> it — `1e359a1` only makes the difference between "never wired up" and "wired up, never
> incremented" visible from outside, which is why the counters now read a materialised `0.0`.

### P1-8 — the new alert rule loaded and is not firing
Command: `curl -sf http://localhost:9090/api/v1/rules | grep -o 'MetaspaceGrowth'` ; `curl -sf 'http://localhost:9090/api/v1/query?query=ALERTS{alertname="MetaspaceGrowth"}'`
Expected: rule present in the loaded rules; `ALERTS` returns an empty result vector.
Actual:
```
MetaspaceGrowth
{"status":"success","data":{"resultType":"vector","result":[]}}
```
Full loaded-rule state, parsed from the API:
```
group   : bot-manager-jvm
state   : inactive
health  : ok
lastErr : None
for     : 3600 s
alerts  : []
expr    : delta(jvm_memory_used_bytes{area="nonheap",id="Metaspace"}[1d]) > 5.24288e+07
          and on (job) (time() - process_start_time_seconds{job="bot-manager"} > 86400)
```
**Amendment A3 confirmed in the *loaded* rule, not merely in the file** — the uptime gate
`time() - process_start_time_seconds{job="bot-manager"} > 86400` is present in the
expression Prometheus is actually evaluating.
Result: **PASS**

> **The empty `ALERTS` vector is expected and proves only that the rule loaded.** Per
> Amendment A3 the rule cannot evaluate at all below 24 h of uptime, so on a box restarted
> minutes ago an empty vector is guaranteed by construction. **This is by design and is not
> a defect.** The rule becomes meaningful from **`2026-08-27T14:08:18Z`** onward; a firing
> rule after the box has been up a day would mean the threshold is wrong, not that there is
> a leak.

Corroborating: `18` rules loaded in total, matching the 18 in the local `alerts.yml`, so the
whole file parsed — this was not a partial load that happened to include the new rule.
Prometheus logged **0** `level=error` / parse / load-failure lines, and
`Completed loading of configuration file … rules=5.07961ms`.

### P1-9 — the dashboard provisioned
Command: `curl -sf -u admin:admin http://localhost:3000/api/search?query=plugin`
Expected: a hit for the `plugin-runtime` dashboard.
Actual:
```json
[{"id":5,"uid":"plugin-runtime","title":"Plugin runtime","uri":"db/plugin-runtime",
  "url":"/d/plugin-runtime/plugin-runtime","type":"dash-db",
  "tags":["bot-manager","observability","plugin"],"isDeleted":false}]
```
`GET /api/dashboards/uid/plugin-runtime` → 200, `created/updated: 2026-08-26T14:07:38Z`.
Grafana's own log: `provisioning.dashboard … "starting to provision dashboards"` →
`"finished to provision dashboards"`, with **no** dashboard provisioning error.
Result: **PASS** (via the authenticated API; the `admin:admin` fallback was not needed —
it worked first time)

*Two unrelated Grafana `level=error` lines exist and are pre-existing: missing
`/etc/grafana/provisioning/plugins` and `/etc/grafana/provisioning/alerting` directories,
plus repeated `grafana.com` DNS failures (the box has no outbound internet). Neither touches
dashboard provisioning.*

**Also confirmed the dashboard will actually render**: Prometheus has *ingested* the new
series, not merely the app exposing them —
```
plugin_classloaders_live               -> 1
plugin_classloaders_registered_total   -> 1
plugin_classloaders_reclaimed_total    -> 0
bots_by_plugin_version                 -> 20, 20, 15, 100
```

### P1-10 — the 7-day metaspace baseline (deliverable, not a check)

**Baseline start: `2026-08-26T14:08:18Z`.** First reading taken `2026-08-26T14:12:33Z` at
255 s of JVM uptime:

| Reading | Value at baseline start |
|---|---|
| `jvm_memory_used_bytes{area="nonheap",id="Metaspace"}` | **82,963,440 B** (82.96 MB) |
| `jvm_memory_used_bytes{area="nonheap",id="Compressed Class Space"}` | 10,487,240 B (10.49 MB) |
| `jvm_classes_loaded_classes` | **16,870** |
| `jvm_classes_unloaded_classes_total` | 228 |
| JVM uptime at reading | 255 s |

**Re-read at or after `2026-09-02T14:08:18Z`** with the plan's three queries
(`jvm_memory_used_bytes{…Metaspace}`, `delta(…[7d])`, `jvm_classes_loaded_classes`) and write
both numbers back into this file. AD-6 sizes `-XX:MaxMetaspaceSize` from them at step 4, and
a non-flat 7-day delta *today* is a pre-existing leak that must be understood before any
child classloader is introduced.

Result: **baseline started and recorded. The 7-day delta is by definition outstanding and
cannot be reported by this release.**

#### Named comparator — indicative pre-deploy reading

The outgoing JVM, immediately before it was stopped, had been up **6.07 days** and showed:

```
delta(jvm_memory_used_bytes{area="nonheap",id="Metaspace"}[6d]) = 920,089.75 B
Metaspace                                                       = 87,460,032 B (87.46 MB)
jvm_classes_loaded_classes                                      = 16,494
```

**~0.9 MB of metaspace growth over six days — essentially flat**, ≈0.15 MB/day against a
52.4 MB/24 h alert threshold, i.e. roughly 1/350th of the rate that would fire
`MetaspaceGrowth`. This is early evidence **against** a pre-existing metaspace leak, which
is one of the two questions Phase 1 exists to answer.

**It is explicitly *not* a substitute for the 7-day baseline this deploy starts.** It was
measured on the previous build, over 6 days rather than 7, on a JVM that had not been
restarted since Aug 20, and `delta()` over a 6-day window is extrapolated from range
endpoints rather than being a true min-to-max. Treat it as a strong prior, and let the
`2026-09-02` reading be the number of record.

---

## Extra confirmations requested

| Confirmation | Result | Evidence |
|---|---|---|
| Every new series carries `pluginVersion="builtin"` | **PASS** | All 3 `plugin_classloaders_*` and all 4 `bots_by_plugin_version` rows tagged `builtin`; filter for non-`builtin` rows returns nothing |
| `pluginVersion` is a JSON field in `logs/console.log` (track 1) | **PASS** | 19 occurrences, e.g. `"pluginVersion":"builtin"` in a JSON-layout line; 0 in `logs/detail/detail.log` |
| `pluginVersion` is **not** a Loki stream label | **PASS** | Loki `/loki/api/v1/labels` returns 8 labels, `pluginVersion` absent; still queryable via `\| json` |
| Exactly one new INFO line at startup, once | **PASS** | `grep -c` → `1` at t≈1 min and again at t≈5 min |

---

## Verdict

**PASS** — 10 of 10 plan steps (P1-1 … P1-9 verified; P1-10 is a baseline deliverable,
started and recorded as specified). All four extra confirmations pass.

All three artefacts landed and were each proved *effective*, not merely present:
the jar exposes the four new meters, Prometheus **loaded and is evaluating** the
`MetaspaceGrowth` rule with its Amendment A3 uptime gate, and Grafana **provisioned** the
`plugin-runtime` dashboard against series Prometheus has already ingested. This deploy is
not inert.

### Anything that did not come back healthy

**Nothing.** All 10 containers returned; bot-manager, mongo, evidence-shim and viptalk-shim
report `healthy`; Prometheus, Grafana, Loki and Alertmanager all answer their readiness
endpoints; all 4 bot groups auto-started at full strength (155/155 bots, matching
pre-deploy); bot-manager logged **0** ERROR lines; and the evidence-shim confirmed
`previous run shut down cleanly - no boot promotion`.

### Carried forward

1. **The 7-day metaspace re-read on `2026-09-02T14:08:18Z`** — the deliverable that sizes
   `-XX:MaxMetaspaceSize` at AD-6 / step 4.
2. **`MetaspaceGrowth` is inert until `2026-08-27T14:08:18Z`** by design (24 h uptime gate).
   Its first meaningful evaluation is a day after this deploy.
3. **`bot_bets_placed_total` reads a materialised `0.0` on all four groups** — the
   pre-existing "no `EndGame` ever arrives on staging" condition, unchanged by this deploy
   and now merely visible. Not a regression, and not in Phase 1's scope.
4. **Host backup left in place**: `prometheus/alerts.yml.bak-predeploy-20260826-210637`.
