# Release — PLUGIN_HOT_RELOAD_3_4, D1 (Phases 3a + 3b)

Mode: bot (image only; full `compose down` + working-tree `deploy.sh`)
Target: Bot-1 (staging) only. Prod-Bot was not touched.
Branch: `feature/plugin-hot-reload-3-4` @ `836f66f`. The worktree was clean at build time.
Image: `vingame-bot:latest` = `ab44fb1dec2a` (built 2026-10-06T13:16:57Z)
Rollback tag: `vingame-bot:rollback-20261006b` = `a843eaddbd39` (the image that was running before)
Date: 2026-10-06T13:13Z – 13:32Z

## Verdict: PASS

All D1 checks passed: V3-0, smoke, C-1..C-6 and V3-1. All 11 previously ACTIVE groups came back ACTIVE at full bot count. The fleet behaves the same as before the deploy. The anomalies listed at the end are either pre-existing or a one-off at group start.

## Build (local)

- `mvn clean package -DskipTests`, full reactor, JDK 21.0.2: PASS (24 s). Produced `Bot-1.0.jar` (62,047,692 B) plus the `bot-api`, `bot-engine`, `bot-strategies` and `bot-messages` jars, all from the same reactor run.
- `docker build --no-cache --platform linux/amd64`: PASS. Run with `DOCKER_CONFIG` pointing at an empty-auths config (the known credential-helper workaround) → `ab44fb1dec2a`.
- `docker save -o bot.tar`: PASS. 396,360,192 B, md5 `aef797a61f07dc847758f9130449258c`.

### V3-0 (local, before shipping): PASS

```
$ unzip -l bot-app/target/Bot-1.0.jar | grep -cE 'BOOT-INF/lib/bot-(strategies|messages)-1.0.jar'
2
$ for j in bot-strategies bot-messages; do unzip -p $j/target/$j-1.0.jar META-INF/MANIFEST.MF | grep -E 'Bot-Plugin-(Version|Name)'; done
Bot-Plugin-Name: bot-strategies
Bot-Plugin-Version: 20261006.131539
Bot-Plugin-Name: bot-messages
Bot-Plugin-Version: 20261006.131539
# copies nested in the fat jar's BOOT-INF/lib:
bot-api-1.0.jar:        (none)
bot-engine-1.0.jar:     (none)
bot-messages-1.0.jar:   Bot-Plugin-Version: 20261006.131539
bot-strategies-1.0.jar: Bot-Plugin-Version: 20261006.131539
```
Expected: 2 jars and identical stamps matching `^[0-9]{8}\.[0-9]{6}`. Actual: 2 jars, identical `20261006.131539` (UTC, the build start time). Neither non-plugin jar carries the stamp.

## Pre-flight (read-only on Bot-1)

Compared sha256 of the worktree files against `/home/sgame/bot-java`. **All identical, so no config was shipped:** `docker-compose.yml` (`feb80e95…`), `logging/log4j2.properties` (`d2d51b36…`), `prometheus/alerts.yml` (`60704a27…`), `prometheus/prometheus.yml`, `alertmanager/alertmanager.yml`, `evidence-shim/shim.py`, all 5 Grafana dashboards (including `plugin-runtime.json`), and `deploy.sh`. For `deploy.sh` the comparison was against the main checkout's working-tree copy, `8eccc6bf…`. It was not modified. The `Dockerfile` is unchanged by 3a/3b (D-7: the fat jar still contains both plugin jars).

Upstream health right before the deploy (13:13Z rollup): 114 Staging `connected=200/200, gateway=85/900`. The 119, 119 Club and 116 envs were all connected, with gateway counts ≤ 4/900 and the circuit closed. Restarting therefore carried no Cloudflare-window risk.

## P-0 — before capture (13:13Z, old JVM `a843eaddbd39`, up 5 h)

Note: the container is named `bot-java-bot-manager-1`, not `bot-manager`, so the plan's `docker logs bot-manager` must be run against the real name.

- Boot registry lines (`docker logs`):
  ```
  BettingStrategyFactory initialized: registered 9 strategies — [DALEMBERT_AGGRESSIVE, DALEMBERT_CAUTIOUS, FIBONACCI_AGGRESSIVE, FIBONACCI_CAUTIOUS, MARTINGALE_CLASSIC_AGGRESSIVE, MARTINGALE_CLASSIC_CAUTIOUS, PAROLI_AGGRESSIVE, PAROLI_CAUTIOUS, RANDOM]
  SlotStrategyFactory initialized: registered 2 strategies — [FIXED, RANDOM]
  MessageTypesRegistry initialized: BETTING_MINI 6 products [097, 098, 114, 116, 118, 119], TAI_XIU 3 products [114, 116, 119], SLOT provider SlotMessageTypesImpl, CASHOUT 1 products [119], CRASH 1 products [119]
  plugin runtime: version=builtin
  ```
- `GET /api/v1/strategy/` md5 = `a72c40f56057cda5434b273ea36315ea` (the expected value, so the box was running what we thought). `?gameType=SLOT` → `[]`.
- `plugin_classloaders_live=1`, `registered_total=1`, `reclaimed_total=0`, all with `pluginVersion="builtin"`. `bots_by_plugin_version` had 11 series, all `builtin` (268 bots). `bots_managed` was not captured: the P-0 grep required a space after the name, and the `{application="bot-manager"}` tag follows it directly, so it matched nothing. It *is* exported (qa-4a Finding 1).
- `jvm_threads_live=71`, `peak=104`. Metaspace used = 93,547,208 B (committed 96,141,312), `jvm_classes_loaded=17,904`.
- Firing alerts: `GameNoRounds` ×3 (Aviator/CRASH, Balloon/CASHOUT, Soccer/CASHOUT, all 119 Club) and `GroupBalanceLow` ×3 (Angry Birds, Aviator, Kong Godzilla 119 Club).
- Groups: 11 ACTIVE/ACTIVE at full count. Coins 100/100 and Zic Zac 100/100 (114); 119 Tai Xiu probe 10/10 and 119 Xoc Dia probe 10/10 (119); 119 Club Tai Xiu KM probe 10/10; Balloon cashout 3/3 and Soccer cashout 3/3; Aviator 3/3; Angry Birds 3/3 and Kong Godzilla 3/3 (119 Club); Slot group 120 20/20 (116). The other 41 groups were DEAD-target/STOPPED, STOPPED, or had a null target, and none had a runtime.

## Ship

- `sftp put bot.tar`: PASS (13:17:24 → 13:18:47Z). The remote md5 `aef797a61f07dc847758f9130449258c` matches the local one.

## Deploy (13:19:26Z → 13:19:50Z)

- `docker tag vingame-bot:latest vingame-bot:rollback-20261006b`: PASS (`a843eaddbd39`).
- `docker compose down`: PASS. All 10 containers and the network were removed, without `-v`.
- `docker image rm`: skipped on purpose. The old image is kept under the rollback tag, and `docker load` untagged it in place.
- `docker load -i bot.tar`: PASS → `latest` = `ab44fb1dec2a`.
- `./deploy.sh` (the working-tree copy, byte-identical on the host): PASS. It printed `Merging secrets.env into .env`, and `compose up -d` started all 10 containers.

## Smoke test: PASS

- `docker ps` (13:20:24Z): `bot-java-bot-manager-1 Up 34 seconds (healthy)`, image `sha256:ab44fb1dec2a…`, StartedAt `2026-10-06T13:19:50.02Z`. The other 9 containers (grafana, prometheus, alertmanager, promtail, loki, mongo, node-exporter, viptalk-shim, evidence-shim) were all Up, and the shims and mongo were healthy.
- `13:19:55.810 Started Starter in 4.65 seconds`: PASS.
- `13:19:56.012 Bot Manager startup: 11 bot groups queued for daisy-chained start`: PASS.
- (Non-blocking) `13:20:01.729 Bot Manager startup complete. 11 bot groups running (11 started by this chain)`. The chain finished in about 6 s.

## Plan verification — D1

### V3-0: PASS (see Build)

### C-1: health of the app and the observability stack. PASS
Command: `curl -sf $BOT/actuator/health`; `curl -sf localhost:9090/-/ready`; `curl localhost:3000/api/health`; also `localhost:3100/ready` (Loki, re-checked because Bot-1 runs everything as a single compose project).
Expected: `{"status":"UP"…}`, `Prometheus Server is Ready`, Grafana 200.
Actual: `{"status":"UP",…mongo UP, diskSpace UP}`; `Prometheus Server is Ready.`; Grafana `200`. Loki returned `503` at about T+60 s (its normal ingester warm-up) and `200` at about T+3 min. At 13:31:52Z all 10 containers showed `Up 12 minutes`.

### C-2: boot registry lines unchanged. PASS
Command: `docker logs bot-java-bot-manager-1 | grep -oE '(Betting|Slot)StrategyFactory initialized.*|MessageTypesRegistry initialized.*'`, then `diff` against the before capture with the `plugin runtime` line removed.
Expected: no diff (9 strategies, 2 strategies, the same product sets).
Actual: **no diff**. 9 betting strategies, 2 slot strategies, BETTING_MINI [097, 098, 114, 116, 118, 119], TAI_XIU [114, 116, 119], SLOT provider `SlotMessageTypesImpl`, CASHOUT [119], CRASH [119].

### C-3: strategy catalogue unchanged. PASS
Command: `curl -sf $BOT/api/v1/strategy/ | md5sum`; `curl -sf "$BOT/api/v1/strategy/?gameType=SLOT"`.
Expected: `a72c40f56057cda5434b273ea36315ea`, `[]`.
Actual: `a72c40f56057cda5434b273ea36315ea`, `[]`.

### C-4: end to end, every game type on the box. PASS
Command: two reads of `bot_messages_total`, summed per group, at 13:23:04 and 13:23:34Z (T+3 min, so more than 120 s after the groups came up); then `grep 'entered session' logs/detail/detail.log`.
Expected: `bot_messages_total` > 0 and rising across the two reads, plus recent `entered session` lines.
Actual (the groups were already running; none needed starting; tx7 was not touched):

| Group | Type | 13:23:04 | 13:23:34 | |
|---|---|---|---|---|
| Zic Zac `1804a704` (114) | BETTING_MINI | 4298 | 4998 | rising |
| Coins `90064a8d` (114) | BETTING_MINI | 4194 | 4894 | rising |
| 119 Xoc Dia probe `c93c82a5` | BETTING_MINI | 347 | 392 | rising |
| 119 Club Tai Xiu KM `c7d19d23` | BETTING_MINI | 130 | 160 | rising |
| 119 Tai Xiu probe `57b99074` | TAI_XIU | 60 | 70 | rising |
| Aviator `13c2b858` (119 Club) | CRASH | 826 | 950 | rising |
| Balloon cashout `e051433a` | CASHOUT | 3050 | 3525 | rising |
| Soccer cashout `52b733e6` | CASHOUT | 3000 | 3525 | rising |
| Angry Birds `16bff8ba` | SLOT | 186 | 216 | rising |
| Kong Godzilla `3cc3c334` | BETTING_MINI-tagged | 66 | 78 | rising |

The last read showed `sum(bot_messages_total)` = 62,055 at 13:31:52Z. Fresh `BotGroup <game>/<id> entered session <sid>` lines appeared at 13:23:10–13:23:26Z for Xoc Dia, Tai Xiu KM, Tai Xiu, RIK Coins and RIK ZicZac, and Kong Godzilla also has them. The CRASH, CASHOUT and Angry Birds slot groups emit **no** `entered session` lines. The same is true in every rolled `detail-*.log` from before the deploy (count 0), so this is pre-existing and not a regression. For those groups the rising message counters are the evidence.

### C-5: no classloading or wiring errors since the restart. PASS
Command: `grep -E '^\{"timestamp":"2026-10-06T(13:(19:5|[2-5][0-9])|1[4-9]:)' logs/console.log | grep -cE 'NoClassDefFoundError|ClassNotFoundException|LinkageError|IllegalAccessError|ClassCastException|not wired'`. The filter keeps only lines timestamped after StartedAt 13:19:50Z.
Expected: 0.
Actual: **0** (out of 310 console lines since the restart). The whole `console.log` is also 0, `docker logs` is also 0, and `logs/detail/detail.log` since the restart is also 0.

### C-6: firing alerts unchanged. PASS
Command: Prometheus `ALERTS` at 13:31:34Z (T+12 min; Prometheus itself restarted at about 13:19:40Z, so the `for:` windows had time to elapse).
Expected: the same set as `alerts-before`.
Actual: the same six alerts are firing (`GameNoRounds`: Aviator, Balloon, Soccer; `GroupBalanceLow`: Angry Birds, Aviator, Kong Godzilla). One addition, `BotManagerRestarted` (`changes(process_start_time_seconds[15m]) > 0`), is the expected consequence of the deploy and clears by itself after 15 min. Nothing is pending. No new `EnvironmentDeadBotRatioHigh` or `GameNoRounds`.

### V3-1: plugin runtime unchanged. PASS
Command: `docker logs bot-java-bot-manager-1 | grep -c 'plugin runtime: version=builtin'`; `curl -sf $BOT/actuator/prometheus | grep -E '^plugin_classloaders_'`.
Expected: `1`; `live=1`, `registered_total=1`, `reclaimed_total=0`, all with `pluginVersion="builtin"`.
Actual: `1` (`plugin runtime: version=builtin, classloader=598446861`); `plugin_classloaders_live=1.0`, `registered_total=1.0`, `reclaimed_total=0.0`, all `builtin`. `bots_by_plugin_version` has 11 series, all `builtin`.

## Before / after

| | Before (old JVM, 5 h up) | After T+0:35 | After T+12 min |
|---|---|---|---|
| Strategy md5 | `a72c40f5…` | `a72c40f5…` | |
| ACTIVE groups / bots up | 11 / 268 | 11 / 268 (by T+3 min) | 11 / 268 |
| `jvm_threads_live` (peak) | 71 (104) | 100 (100) | 76 (103) |
| Metaspace used | 93,547,208 B | 84,294,440 B | 87,243,408 B |
| `jvm_classes_loaded` | 17,904 | 17,374 | 17,652 |
| `plugin_classloaders_*` | 1 / 1 / 0 builtin | 1 / 1 / 0 builtin | 1 / 1 / 0 builtin |

The metaspace figures are not like for like (5 h uptime against 12 min). The new JVM is climbing toward the old figure as lazily loaded classes arrive. Nothing here suggests extra metaspace from 3a/3b, but the D2 baseline should be taken at a comparable uptime. Thread count settled back to the old JVM's level after the start burst.

## Anomalies / notes

1. **Start-time WS disconnect burst.** 47 `WS disconnected — starting retrial flow` WARNs between 13:19:57 and 13:20:00Z: 20 rikcoins, 20 rikzz, 5 w79 Tai Xiu, 2 slt. These fell inside the 3 s in which the groups were being brought up. There were none afterwards (0 from 13:21Z to 13:32Z), and every group reached full `botsUp`. Two w79 cash-out bots (`w79scr*`, `w79bln*`) also triggered one watchdog full reconnect each. The previous deploy (08:26Z) shows no such burst in `console-2026-10-06-07.log`. The most plausible cause is the upstream dropping the previous JVM's still-registered sessions, since `compose down` killed it about 25 s earlier. It recovered on its own. It is not class-related (C-5 = 0), and I am recording it for comparison at D2.
2. **Slot group 120 `2bf237bd` (116) has no `bot_messages_total` series.** It was the same before the deploy: the Prometheus query `bot_messages_total{botGroupId="2bf237bd…"} offset 20m` returns empty, and the 116 rollup showed `rounds=0 staked=0` before the restart. The group is ACTIVE with 20/20 bots, and the health monitor reports `playing: 20`. This is pre-existing and was therefore excluded from C-4.
3. **The plan's commands use container name `bot-manager`**. On Bot-1 the name is `bot-java-bot-manager-1`, so `docker logs bot-manager` matches nothing (my first P-0 capture came back empty for that reason). D2/D3 should use `docker logs $(docker ps --filter name=bot-manager --format '{{.Names}}' | head -1)`.
4. Bot-1 has no `python3`. All JSON post-processing was done locally.
5. **Corrected by qa-4a Finding 1:** `bots_managed` is exported by both builds (`bots_managed{application="bot-manager"} …`). The P-0 grep `bots_managed ` required a space after the name and so matched nothing; the plan now greps `'^bots_managed[{ ]'`.

## Rollback

```bash
ssh Bot-1 'cd /home/sgame/bot-java && docker tag vingame-bot:rollback-20261006b vingame-bot:latest && docker compose up -d bot-manager'
```
Nothing was persisted or migrated by 3a/3b, so the rollback carries no data risk.
