# Release — PLUGIN_HOT_RELOAD_3_4, D2 (Phases 4a + 4b)

Mode: bot (image only)
Target: Bot-1 (staging) only. Prod-Bot was not touched.
Branch: `feature/plugin-hot-reload-3-4` @ `a991303`
Image: `vingame-bot:latest` = `5242d922d4ad` (built 2026-10-08 17:14:52 +07 = 10:14:52Z)
Rollback tag: `vingame-bot:rollback-20261008` = `ab44fb1dec2a` (the D1 image that was running before)
Plugin mode: `classpath` (`BOT_PLUGINS_MODE` unset, the default; compliance-4ab requires this for D2)
Date: deploy about 10:15–10:18Z, verification 10:53Z – 10:56Z, 2026-10-08

**A different run did the deploy.** An earlier releaser run built, shipped and deployed this image around 10:15–10:18Z. The user's Mac died before that run could carry out or record verification. **This run was verification only.** It did not build, ship, `compose down`, load, recreate or roll back anything. The Build / Ship / Deploy facts below come from the box itself (image metadata, container state, logs), not from a build log. The only actions this run took on the box were read-only queries and one `SIGQUIT` for the V4b-1 thread dump, which does not stop the JVM. The container was still `running`/`healthy` afterwards, with the same `StartedAt`.

## Verdict: PASS

All D2 checks passed: C-1..C-6, V3-1, V4a-1, V4a-2, V4a-3, metaspace (informational) and V4b-1. Before the deploy, 9 groups were healthy and ACTIVE. All 9 came back ACTIVE at full bot count. The two 119-Staging probe groups were already DEAD before the deploy (see Groups). The earlier run REST-started one of them after the deploy, and it is playing. The other is still DEAD, as it was before. No app ERROR has been logged since the new JVM started.

## Build / Ship / Deploy (done by the earlier run; verified from the box)

- Running container `bot-java-bot-manager-1` (`f2c81163…`): image `sha256:5242d922d4adb0a68485447dc3c56cee928e650f1f7801522f44a0bc71031ded`, i.e. `5242d922d4ad` as expected. `StartedAt 2026-10-08T10:18:35.02Z`, health `healthy`.
- `vingame-bot:latest` = `5242d922d4ad`, created 2026-10-08 17:14:52 +07.
- `vingame-bot:rollback-20261008` = `ab44fb1dec2a`, the D1 image (`release-d1.md`).
- Every compose service was Up and had been for about 35 min at 10:53Z. bot-manager, evidence-shim, viptalk-shim and mongo were `healthy`. Port mapping: host `8080` → container `8085`, so on the host `BOT=http://localhost:8080`.
- The old JVM shut down at 10:18:12Z (registration worker, aggregators and event loop closed in order). The new JVM logged `Started Starter in 4.584 seconds` at 10:18:40.756Z.

## Smoke test: PASS

- `docker ps`: `Up 34 minutes (healthy)`: PASS.
- `10:18:40.756 Started Starter in 4.584 seconds`: PASS.
- `10:18:40.954 Bot Manager startup: 9 bot groups queued for daisy-chained start`: PASS.
- (Non-blocking) `10:18:45.792 Bot Manager startup complete. 9 bot groups running (9 started by this chain)`.

## P-0 — before state, reconstructed after the fact

The earlier run's P-0 captures were lost with the Mac. They were rebuilt from Prometheus history at **10:12:00Z**, which is before the deploy:

- `OLD_START` = `process_start_time_seconds{job="bot-manager"}` @ 10:12Z = **1791292790** (2026-10-06T13:19:50Z). This matches D1's StartedAt, so the old JVM was the D1 JVM (`ab44fb1dec2a`). `changes(process_start_time_seconds[24h]) = 1`, so the only restart in the window is this deploy.
- `bots_managed` @ 10:12Z = 265. `bots_by_plugin_version` had 11 series, all `builtin`.
- Firing alerts @ 10:12Z: `GameNoRounds` ×3 (Balloon/CASHOUT, Soccer/CASHOUT, Aviator/CRASH, all on 119 Club); `GroupBalanceLow` ×2 (Angry Birds `16bff8ba`, Kong Godzilla `3cc3c334`); and, for env `d005157f` (119 Staging), `EnvironmentSocketDegraded`, `EnvironmentSocketDown`, `EnvironmentGroupDead` and `EnvironmentDeadBotRatioHigh`.
- The boot registry lines for C-2 come from `release-d1.md` § P-0 / C-2. That is the same JVM, which was never restarted between D1 and this deploy.
- The strategy md5 baseline is the plan constant `a72c40f56057cda5434b273ea36315ea`, which D1 measured on that JVM.

## Groups: before and after

| Group | Type | Before (10:12Z, old JVM) | After (10:53Z) |
|---|---|---|---|
| Coins `90064a8d` (114) | BETTING_MINI | ACTIVE 100 | ACTIVE 100/100 |
| Zic Zac `1804a704` (114) | BETTING_MINI | ACTIVE 100 | ACTIVE 100/100 |
| Slot group 120 `2bf237bd` (116) | SLOT | ACTIVE 20 | ACTIVE 20/20 |
| Aviator `13c2b858` (119 Club) | CRASH | ACTIVE 3 | ACTIVE 3/3 |
| Angry Birds `16bff8ba` (119 Club) | SLOT | ACTIVE 3 | ACTIVE 3/3 |
| Kong Godzilla `3cc3c334` (119 Club) | BETTING_MINI | ACTIVE 3 | ACTIVE 3/3 |
| Soccer cashout `52b733e6` (119 Club) | CASHOUT | ACTIVE 3 | ACTIVE 3/3 |
| Balloon cashout `e051433a` (119 Club) | CASHOUT | ACTIVE 3 | ACTIVE 3/3 |
| 119 Club Tai Xiu KM `c7d19d23` | BETTING_MINI | ACTIVE 10 | ACTIVE 10/10 |
| 119 Tai Xiu probe `57b99074` (119 Staging) | TAI_XIU | **DEAD** (`dead=10/10, groupDead=true` in every rollup up to 10:14:54Z) | ACTIVE 10/10. Started at 10:21:47Z by `origin REST`, presumably the earlier run |
| 119 Xoc Dia probe `c93c82a5` (119 Staging) | BETTING_MINI | **DEAD** (`dead=10/10, groupDead=true` from at least 09:39Z through 10:14:54Z) | `targetStatus=DEAD`, no runtime. Not restarted, so unchanged |

`bot.recovery.enabled=false` on this box (`DeadGroupRecoveryScheduler … enabled=false`), so the 119-Staging groups were not auto-recovered. The startup chain started only ACTIVE targets (9). That is why the series count went from 11 to 10. Both missing series belong to groups that were already dead. The deploy did not lose any group.

No test groups were created by this run. The full group list (38 groups across 4 envs) shows no group created for D2. Since the new JVM started, the only REST lifecycle action in `console.log` is the 10:21:47Z start of `57b99074`. Nothing needed cleaning up.

## Plan verification — D2

### C-1: health of the app and the observability stack. PASS
Command: `curl -sf $BOT/actuator/health`; `curl -sf localhost:9090/-/ready`; `curl -s -o /dev/null -w %{http_code} localhost:3000/api/health`; also `localhost:3100/ready` (Loki).
Expected: `{"status":"UP"…}`, `Prometheus Server is Ready`, Grafana 200.
Actual: `{"status":"UP",…diskSpace UP, mongo UP, ping UP, ssl UP…}`; `Prometheus Server is Ready.`; Grafana `200`; Loki `200`.

### C-2: boot registry lines unchanged. PASS
Command: `docker logs "$(docker compose ps -q bot-manager)" 2>&1 | grep -oE '(Betting|Slot)StrategyFactory initialized.*|MessageTypesRegistry initialized.*'`, compared against release-d1's capture.
Expected: no diff.
Actual: byte-identical to D1, each printed once, in order:
```
BettingStrategyFactory initialized: registered 9 strategies — [DALEMBERT_AGGRESSIVE, DALEMBERT_CAUTIOUS, FIBONACCI_AGGRESSIVE, FIBONACCI_CAUTIOUS, MARTINGALE_CLASSIC_AGGRESSIVE, MARTINGALE_CLASSIC_CAUTIOUS, PAROLI_AGGRESSIVE, PAROLI_CAUTIOUS, RANDOM]
SlotStrategyFactory initialized: registered 2 strategies — [FIXED, RANDOM]
MessageTypesRegistry initialized: BETTING_MINI 6 products [097, 098, 114, 116, 118, 119], TAI_XIU 3 products [114, 116, 119], SLOT provider SlotMessageTypesImpl, CASHOUT 1 products [119], CRASH 1 products [119]
```

### C-3: strategy catalogue unchanged. PASS
Command: `curl -sf $BOT/api/v1/strategy/ | md5sum`; `curl -sf "$BOT/api/v1/strategy/?gameType=SLOT"`.
Expected: `a72c40f56057cda5434b273ea36315ea`, `[]`.
Actual: `a72c40f56057cda5434b273ea36315ea`, `[]`.

### C-4: end to end, every game type on the box. PASS
Command: `bot_messages_total` summed per group at 10:54:48Z and again at 10:55:18Z (T+36 min); `grep 'entered session' logs/detail/detail.log`.
Expected: > 0 and rising across the two reads, plus recent `entered session` lines.
Actual: every group was already running. None needed starting, and tx7 was not touched.

| Group | Type | 10:54:48 | 10:55:18 | |
|---|---|---|---|---|
| Zic Zac `1804a704` | BETTING_MINI | 49654 | 50354 | rising |
| Coins `90064a8d` | BETTING_MINI | 46085 | 46783 | rising |
| Club Tai Xiu KM `c7d19d23` | BETTING_MINI | 1452 | 1492 | rising |
| Kong Godzilla `3cc3c334` | BETTING_MINI | 813 | 828 | rising |
| 119 Tai Xiu probe `57b99074` | TAI_XIU | 610 | 630 | rising |
| Aviator `13c2b858` | CRASH | 9584 | 9759 | rising |
| Balloon cashout `e051433a` | CASHOUT | 139 | 141 | rising |
| Soccer cashout `52b733e6` | CASHOUT | 140 | 141 | rising |
| Angry Birds `16bff8ba` | SLOT | 2163 | 2193 | rising |

`entered session` lines since 10:18Z per group: `1804a704` 74, `90064a8d` 68, `3cc3c334` 55, `c7d19d23` 38, `57b99074` (TAI_XIU) 31. The latest was at 10:55:18Z. As at D1, the CRASH, CASHOUT and SLOT groups emit no `entered session` lines (pre-existing), so their rising counters are the evidence. As at D1, Slot group 120 `2bf237bd` (116) has no `bot_messages_total` series (pre-existing, release-d1 note 2). It is ACTIVE 20/20, and the 116 rollup reads `connected=20/20`.

The env rollups at the last tick were all `dead=0, deadGroups=0, circuit=closed`: 114 `connected=200/200`, 119 Club `25/25`, 119 Staging `10/10`, 116 `20/20`.

### C-5: no classloading or wiring errors since the restart. PASS
Command: console.log lines timestamped at or after 10:18Z (647 lines) `| grep -cE 'NoClassDefFoundError|ClassNotFoundException|LinkageError|IllegalAccessError|ClassCastException|not wired'`.
Expected: 0.
Actual: **0**. The whole `console.log` is also 0, `docker logs` of the new container is 0, and `logs/detail/detail.log` is 0.

App ERRORs since the deploy: `"level":"ERROR"` in the same 647-line window = **0**, and lines matching ` ERROR ` in `docker logs` of the new container = **0**.

### C-6: firing alerts unchanged. PASS
Command: Prometheus `ALERTS` at 10:53Z and 10:56Z (T+35–38 min).
Expected: the same set as before; no new `EnvironmentDeadBotRatioHigh` / `GameNoRounds`.
Actual: `GameNoRounds` ×3 (Balloon, Soccer, Aviator) and `GroupBalanceLow` ×2 (Angry Birds, Kong Godzilla). These are the same series as at 10:12Z and as at D1, and are pre-existing. The four 119-Staging env alerts that were firing before the deploy have **resolved**, because `57b99074` is up. The current set is a strict subset of the before set: nothing new and nothing pending. `BotManagerRestarted` has already aged out of its 15 min window.

### V3-1: plugin runtime unchanged. PASS
Command: `docker logs … | grep -c 'plugin runtime: version=builtin'`; `curl -sf $BOT/actuator/prometheus | grep -E '^plugin_classloaders_'`.
Expected: `1`; live=1, registered_total=1, reclaimed_total=0, all `builtin`.
Actual: `1`. `plugin_classloaders_live{pluginVersion="builtin"} 1.0`, `registered_total 1.0`, `reclaimed_total 0.0`.

### V4a-1: the boot line names the classpath source. PASS
Command: `docker logs … | grep 'plugin runtime: version=builtin' | grep -c 'source=classpath'`.
Expected: `1`.
Actual: `1`. The line is `plugin runtime: version=builtin, classloader=598446861, source=classpath, jars=[]`. The classloader identity is the same value D1 logged, as expected for the app loader.

### V4a-2: `BotFactory` stamps every bot `builtin`. PASS
Command: `curl -sf $BOT/actuator/prometheus | grep -E '^bots_by_plugin_version|^bots_managed[{ ]'` (groups running for more than 30 min).
Expected: every row `pluginVersion="builtin"`, and their sum equal to `bots_managed`.
Actual: 10 rows, all `builtin`: 100 + 100 + 20 + 10 + 10 + 3×5 = **255**. `bots_managed{application="bot-manager"} 255.0`. They match.

### V4a-3: the isolated loader does not run. PASS
Command: `docker logs … | grep -c 'plugin bundle'`.
Expected: `0`.
Actual: `0`.

### Metaspace (informational, the D3 input). PASS
Command: as amended by compliance-4ab: `OLD_START=1791292790`; `NEW_START=1791454715`; `U = now − NEW_START = 2225 s` (37 min, at least the required 30 min). Query `jvm_memory_used_bytes{area="nonheap",id="Metaspace"}` at now, and again with `&time=OLD_START+U` (= 1791295015, 2026-10-06T13:56:55Z).
Expected: the two values agree within about 2 MiB.
Actual:

| | Uptime | Metaspace used | `jvm_classes_loaded` |
|---|---|---|---|
| New JVM (`5242d922d4ad`, 4a+4b) | 2225 s | **88,579,360 B** | 17,615 |
| Old JVM (`ab44fb1dec2a`, 3b) | 2225 s | **89,094,024 B** | 17,701 |
| Δ (new − old) | | **−514,664 B (−0.49 MiB)** | −86 |

The two agree within 0.5 MiB at a matched uptime. 4a and 4b add no measurable metaspace. Group mixes differ slightly: 10 running groups now, against D1's 11 at that uptime.

### V4b-1: Netty event loop pre-started (L-11, S5). PASS
Command:
```bash
docker kill --signal=QUIT "$(docker compose ps -q bot-manager)" && sleep 2
docker logs --since 1m "$(docker compose ps -q bot-manager)" 2>&1 \
  | grep -E '^"(multiThreadIoEventLoopGroup-[0-9]+-[0-9]+|http-nio-[0-9]+-Acceptor)"' \
  | grep -oE '^"[^"]+"|elapsed=[0-9.]+s' | paste - -
```
Expected: exactly 4 distinct `multiThreadIoEventLoopGroup-*` threads from one `-N-` family, each with `elapsed=` ≥ the Tomcat Acceptor's.
Actual:
```
"multiThreadIoEventLoopGroup-2-1"	elapsed=2221.54s
"multiThreadIoEventLoopGroup-2-2"	elapsed=2221.54s
"multiThreadIoEventLoopGroup-2-3"	elapsed=2221.54s
"multiThreadIoEventLoopGroup-2-4"	elapsed=2221.54s
"http-nio-8085-Acceptor"	elapsed=2220.37s
```
There are 4 threads, all from the `-2-` family, and each is **1.17 s older** than the Acceptor. So they were created during singleton construction, before Tomcat started, not lazily by the first bot client (which runs after `ApplicationReadyEvent`). No other `multiThreadIoEventLoopGroup` family exists. After the dump the container was still `running`, `healthy`, with `StartedAt` unchanged, and `/actuator/health` = UP.

## Anomalies / notes

1. **The earlier run did the deploy, and its P-0 captures are lost.** The before state was rebuilt from Prometheus at 10:12Z and from `release-d1.md`, which covers the same JVM. That is sufficient for every D2 comparison. The only thing it cannot reproduce is the old JVM's `docker logs` registry lines. Those were captured at D1, on a JVM that stayed up from D1 until this deploy.
2. **119 Staging (`d005157f`) was already dead before the deploy.** `57b99074` and `c93c82a5` were both `groupDead=true`, with the env alerts firing. At 10:21:47Z the earlier run REST-started `57b99074`, which came up 10/10 and plays (TAI_XIU `entered session` ×31). `c93c82a5` (119 Xoc Dia probe) was left DEAD. Its state is unchanged by this deploy. An operator decides whether to restart it, not D2.
3. **A burst of `BotMemory.completeRound: sessionId mismatch (EndGame sessionId=0, in-flight sessionId=0)` WARNs** (all 10 bots) appeared at 10:22:02Z on `57b99074`, its first round after the start, plus one bot after a reconnect at 10:45Z. These are TAI_XIU first-round warnings, not class or wiring problems, and the group plays normally afterwards. I am noting them, not treating them as a finding against 4a/4b.
4. A single `WARN GatewayBudgetRegistry … apigw-w79.sgame.us is shared by 2 environments` at 10:21:47Z. This is the known by-design notice from GATEWAY_REQUEST_BUDGET.
5. Lines timestamped 10:18:12Z (`Can't invoke task later as EventLoop rejected it`) belong to the **old** JVM's shutdown, before the new JVM's StartedAt (10:18:35Z). None is ERROR-level.

## Rollback (not executed)

```bash
ssh Bot-1 'cd /home/sgame/bot-java && docker tag vingame-bot:rollback-20261008 vingame-bot:latest && docker compose up -d bot-manager'
```
4a and 4b persist nothing and migrate nothing. Classpath mode reads no host `plugins-dist`, so a rollback carries no data risk.
