# Release — PLUGIN_HOT_RELOAD Phase 2 (2a + 2b + 2c + review fix pass)

Mode: bot (jar only — no `prometheus/`, no `grafana/`, no `infra-images.tar.gz`)
Branch: `feature/plugin-hot-reload`
HEAD: `99e0534566252f80a5ad71a35508d30dfcee6ec3` — *docs: land the fix-pass compliance verdict and record F4 as a plan amendment*
Target: **Bot-1 (staging)**
Image: `vingame-bot:latest`, `sha256:6873c8b16b6fb4cf008ac8d4fd31badc4894a19692449ce4940cbfee5bfaf77a`, built `2026-08-27T09:21:14Z`
Date: 2026-08-27T09:20Z → 09:44Z

**Verdict: PASS.** Plan verification 8 of 8 steps passed (P2-7 passed on 2 of its 3 pinned
strings; the third is not exercisable on staging — detail in its section).

---

## Pre-deploy capture (done first, before anything else)

Nothing at build time exercises the real controller responses through Spring's
`ObjectMapper`, so endpoint byte-identity rests entirely on this capture. It was taken
against the **running pre-deploy instance** (uptime 19 h) at 2026-08-27T09:18Z.

Raw bodies preserved (referenced by the diffs below):

- `docs/reviews/PLUGIN_HOT_RELOAD/phase2-capture-before.txt`
- `docs/reviews/PLUGIN_HOT_RELOAD/phase2-capture-after.txt`

Six groups were captured — `GET /api/v1/bot-group/{id}` **and**
`GET /api/v1/bot-group/{id}/health` for each — chosen to cover every shape the
enum→`String` change touches:

| Group | Why it was chosen | `strategyMix` / `slotStrategyId` |
|---|---|---|
| `66cfc12c-8c5e-4cb1-b5c1-6f7da59d3136` | non-default strategy key | `[{MARTINGALE_CLASSIC_CAUTIOUS, 1.0}]` / `null` |
| `ab81f9e6-764b-4785-9039-84add04e2fb0` | ACTIVE, 100 bots | `[{RANDOM, 1.0}]` / `null` |
| `846d2966-2652-4599-baba-aa2485d39541` | the **slot** field | `null` / `"FIXED"` |
| `4342888f-996a-4e39-a838-ebd9a9334efe` | **empty-list** short-circuit 2b added | `[]` / `null` |
| `7a3716ed-c0b6-4a8f-a4d8-a122473be3e7` | ACTIVE, 097 | `[{RANDOM, 1.0}]` / `null` |
| `c45b3b16-bd34-4b9b-94f0-2d37b8c3c8e3` | 114 / TAI_XIU | `[{MARTINGALE_CLASSIC_CAUTIOUS, 1.0}]` / `null` |

Other pre-deploy baselines recorded for differential comparison:

```
bot_bets_placed_total series ............ 4      (all values 0.0 — see P2-6)
lines carrying pluginVersion ............ 7
plugin_classloaders_live ................ 1.0   (pluginVersion="builtin")
plugin_classloaders_registered_total .... 1.0
plugin_classloaders_reclaimed_total ..... 0.0
bots_managed ............................ 155.0
jvm_memory_used_bytes{...Metaspace} ..... 8.7416704E7
jvm_classes_loaded_classes .............. 16622.0
firing alerts ........................... GameNoRounds x2 (TaiXiu Seven, Xoc Dia mini; 097)
```

---

## Build

Command:

```bash
export JAVA_HOME=/Users/gleb/Library/Java/JavaVirtualMachines/openjdk-21.0.2/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
mvn clean install
```

- `mvn clean install`: **PASS** — `BUILD SUCCESS`, total time 47.307 s, all five modules built.
- JDK confirmed `openjdk version "21.0.2" 2024-01-16`.

Test counts, aggregated from the surefire XML reports rather than trusting the handoff:

| Module | tests | failures | errors | skipped |
|---|---|---|---|---|
| bot-api | 138 | 0 | 0 | 0 |
| bot-strategies | 124 | 0 | 0 | 0 |
| bot-messages | 158 | 0 | 0 | 0 |
| bot-engine | 426 | 0 | 0 | 0 |
| bot-app | 1138 | 0 | 0 | 0 |
| **total** | **1984** | **0** | **0** | **0** |

**Matches the stated 1984 / 0 / 0 / 0 exactly**, per module. No discrepancy, so the build
was not stopped.

Contract-module reverse-edge gate:

```bash
mvn -q -pl bot-api dependency:tree | grep -E 'bot-app|bot-engine|bot-strategies|bot-messages'
```
→ **no output** (grep exit 1). **PASS** — `bot-api` still has no reverse edge.

- `docker build --no-cache --platform linux/amd64`: **PASS**
- `docker save`: **PASS** — `bot.tar`, **393,100,800 bytes**

## Ship

- `sftp put bot.tar`: **PASS** — 4 m 57 s.
- Integrity verified rather than assumed — sha256 identical on both ends:
  `b131f3202e0e129b48144a2b582db96198a1d5f0b04b948935326f7b2f085865`
- `deploy.sh`: **not shipped, and nothing to ship.** The host copy and the working-tree
  copy are byte-identical (md5 `9fe2dea89dcf1a5332cd73f21c94b7d0` both sides), so the
  load-bearing `secrets.env` → `.env` merge is already on the box. Nothing committed.

## Deploy

Deliberately **not** run via `deploy.sh`, whose first act is `docker kill` — an unclean
stop triggers the evidence-shim's boot retro-promotion and can lose the detail
appender's last ≤ 8 KB (`immediateFlush = false`). A graceful `docker compose down` was
used instead. That choice is confirmed to have worked in the smoke section below.

- `docker compose down`: **PASS** — 2026-08-27T09:27:01Z
- `docker image rm vingame-bot:latest`: **PASS** — untagged + 12 layers deleted
- `docker load -i bot.tar`: **PASS** — `Loaded image: vingame-bot:latest`
- `docker compose up -d`: **PASS** — 2026-08-27T09:27:16Z → 09:27:30Z

**`.env` was left alone, as instructed.** It survived `compose down` byte-identical —
md5 `f38000296c52b0fa8918a5126a3f3f9a`, 300 bytes, all 9 keys present
(`HOST_UID`, `HOST_GID`, `VIPTALK_ENABLED`, `VIPTALK_BOT_TOKEN`, `VIPTALK_OPS_ROOM_ID`,
`VIPTALK_INSTANCE_LABEL`, `VIPTALK_CUSTOMER_NOTICES_ENABLED`, `VIPTALK_DOWN_ROOM_IDS`,
`BOT_LOG_LEVEL`). **It was neither missing nor truncated, so it was not regenerated.**

## Smoke test

- bot-manager `(healthy)`: **PASS** — healthy at 2026-08-27T09:28:04Z (34 s after up)
- Spring Boot ready log: **PASS** — `Started Starter in 7.447 seconds (process running for 8.553)`
- Auto-start log: **PASS** — `Bot Manager startup complete. 4 bot groups running`

**Whole-stack re-verification** (bot-manager shares one Compose project with observability,
so the redeploy restarted all ten containers):

| Container | Status | Readiness probe |
|---|---|---|
| `bot-java-bot-manager-1` | Up (healthy) | `/actuator/health` → `{"status":"UP"...}` |
| `bot-java-prometheus-1` | Up | `/-/ready` → `Prometheus Server is Ready.` |
| `bot-java-grafana-1` | Up | `/api/health` → `"database": "ok"`, v11.4.0 |
| `bot-java-loki-1` | Up | `/ready` → `ready` |
| `bot-java-alertmanager-1` | Up | `/-/ready` → `OK` |
| `bot-java-mongo-1` | Up (healthy) | app `mongo` health component UP |
| `bot-java-promtail-1` | Up | — |
| `bot-java-evidence-shim-1` | Up (healthy) | `/health` 200 |
| `bot-java-viptalk-shim-1` | Up (healthy) | `/health` 200 |
| `bot-java-node-exporter-1` | Up | Prometheus target `health: up` |

Two probe results worth writing down so they are not re-investigated as faults:

- **Loki answered 503 on the first `/ready`** and `ready` ~10 s later. That is its normal
  15 s post-start ingester warm-up, not a failure.
- **Alertmanager is not published on the host** (`9093/tcp`, no host binding), so
  `curl localhost:9093` from the host returns `000`. It answers `OK` on the Compose
  network (`http://alertmanager:9093/-/ready`). Pre-existing layout, not a regression.

**Graceful stop confirmed effective** — the evidence-shim logged, at 09:27:19:

```
previous run shut down cleanly - no boot promotion
```

No boot-tagged evidence file was created today (`logs/evidence/` contains boot promotions
from Aug 19 and Aug 20 only). The detail log's final bytes were not at risk.

**VipTalk verified inside the running container** (the silent-inert failure mode this
guards against):

```
VIPTALK_ENABLED=true
VIPTALK_BOT_TOKEN len=56, prefix=QGJvdF
VIPTALK_OPS_ROOM_ID_set=yes
VIPTALK_INSTANCE_LABEL=staging
VIPTALK_CUSTOMER_NOTICES_ENABLED=false
LOGGING_LEVEL_COM_VINGAME_BOT=DEBUG
LOGGING_LEVEL_COM_VINGAME_WEBSOCKETPARSER=INFO
LOGGING_CONFIG=/app/config/log4j2.properties
```

Note `BOT_LOG_LEVEL` itself is empty *inside* the container — that is correct and not a
defect. It is a compose-interpolation variable; what reaches the JVM is
`LOGGING_LEVEL_COM_VINGAME_BOT=DEBUG`, which is present.

---

## Plan verification — `docs/plans/PLUGIN_HOT_RELOAD.md` § Phase 2

### P2-1 — health and stack
Command: `curl -sf $BOT/actuator/health`, `:9090/-/ready`, `:3000/api/health`
Expected: three 200s.
Actual: all three returned as tabled in the smoke section, plus Loki and Alertmanager.
**Result: PASS**

### P2-2 — the strategy registries came up with the full catalogue
Command: `docker logs bot-java-bot-manager-1 2>&1 | grep -E '(Betting|Slot)StrategyFactory initialized'`
Expected: two lines, **counts 9 and 2**, same *set*, sequence explicitly not compared.
Actual:

```
BettingStrategyFactory initialized: registered 9 strategies — [DALEMBERT_AGGRESSIVE,
DALEMBERT_CAUTIOUS, FIBONACCI_AGGRESSIVE, FIBONACCI_CAUTIOUS,
MARTINGALE_CLASSIC_AGGRESSIVE, MARTINGALE_CLASSIC_CAUTIOUS, PAROLI_AGGRESSIVE,
PAROLI_CAUTIOUS, RANDOM]
SlotStrategyFactory initialized: registered 2 strategies — [FIXED, RANDOM]
```

Counts 9 and 2 ✓. Set identical to the pre-deploy line ✓.

**The ordering changed and that is Amendment A9 landing exactly as predicted, not a
regression.** Pre-deploy the line read `[RANDOM, MARTINGALE_CLASSIC_CAUTIOUS, ...]`
(ordinal / discovery order); it is now sorted. A9 quotes the expected sorted sequence and
this matches it character for character, including `[FIXED, RANDOM]` for slots.
**Result: PASS**

### P2-3 — `GET /api/v1/strategy/` is byte-identical
Command: `diff strategy-before.json strategy-after.json`
Expected: no output; nine entries, same ids/displayNames/descriptions, **same order**;
`?gameType=SLOT` still `[]`.
Actual: `diff` produced **no output**. md5 identical both sides:
`a72c40f56057cda5434b273ea36315ea`. `?gameType=SLOT` returned `[]`, also identical.
Confirms A9's claim that the sort applies to diagnostics only and never to the UI
contract — `StrategyController` still enumerates the enum until 2d.
**Result: PASS**

### P2-4 — an existing group's persisted strategy mix survives a round trip (the critical 2b check)
Command: `diff` of the six captured `GET /api/v1/bot-group/{id}` + `/health` bodies;
then the Mongo read.

**Byte-identity diff result, stated explicitly as requested:**

The raw end-to-end `diff` was **not** empty. Every difference was isolated and every one
is a restart artefact, not a serialization change:

- `lastStartedAt` — `2026-08-26T14:08:28.909` → `2026-08-27T09:27:37.494` (the groups
  auto-started again)
- `stats.activeTimeSeconds` — e.g. `68989` → `111`
- live `/health` fields (`startedAt`, per-bot `balance` / `lastFetchedBalance`)

With only those restart-volatile fields masked, the group bodies are **identical**:

```
diff <(mask before) <(mask after)
>>> GROUP BODIES IDENTICAL once restart-volatile fields are masked <<<
```

Three sharper, decisive comparisons were run on top:

1. **The changed field itself is byte-identical** across all six groups —
   `diff` of `{id, strategyMix, slotStrategyId}` produced **no output**, including
   `"MARTINGALE_CLASSIC_CAUTIOUS"`, `"RANDOM"`, `"FIXED"`, the `null` and the `[]` cases.
2. **JSON key order is identical** — `keys_unsorted` diff empty for **both** the group
   bodies and the `/health` bodies. The enum→`String` change did not perturb the
   serialization shape.
3. **HTTP status codes identical** — all twelve requests 200 before and after.

Mongo half:

```
distinct strategyMix.strategyId  →  ["MARTINGALE_CLASSIC_CAUTIOUS","RANDOM"]
distinct slotStrategyId          →  ["FIXED","RANDOM"]
docs with non-string strategyId  →  0
docs with non-string slotStrategyId → 0
total botGroups                  →  30
```

Still the same uppercase names — **no rewrite, no nulls, no lowercase, no type change.**
Confirms the "no migration needed" claim against real data.
**Result: PASS**

### P2-5 — a bad key is a 400 on both create and PATCH
Commands and actual responses:

| Case | Method | HTTP | Body |
|---|---|---|---|
| bogus `strategyId` | PATCH | **400** | `Unknown strategyId 'NONSENSE' in strategyMix — registered strategies: [DALEMBERT_AGGRESSIVE, … , RANDOM]` |
| bogus `strategyId` | POST | **400** | same message |
| bogus `slotStrategyId` | PATCH | **400** | `Unknown slotStrategyId 'NOPE' — registered slot strategies: [FIXED, RANDOM]` |
| ordinal `{"strategyId": 0}` | PATCH | **400** | `Unknown strategyId '0' …` |
| `{"strategyId": null}` | PATCH | **400** | `Unknown strategyId 'null' …` |

400 not 500 in every case ✓. The last two are the **known, by-design narrowing** — both
were 200 before 2b — and they behave as documented. Error messages render their key lists
sorted, consistent with A9.

Nothing was persisted:

```
botGroups count before .......... 30
botGroups count after ........... 30
docs named /REL-PHASE2-BOGUS/ ... 0
docs with NONSENSE or NOPE ...... 0
GET of the PATCHed group: diff before/after → >>> GROUP UNCHANGED <<<
```

The rejection did not partially apply.
**Result: PASS**

### P2-6 — a group actually starts and bets (end-to-end proof of the 2c registry)
Command: group health, detail-log session lines, `bot_messages_total` / `bot_bets_placed_total`.

Health of `40fa3749` (Xoc Dia, product 116) — the group that is actually receiving rounds:

```
"status":"ACTIVE","playingStatus":"IDLE","totalBots":20,"connectedBots":20,
"reconnectingBots":0,"deadBots":0,"disconnectedBots":0
```

`bot_messages_total` after ~7 minutes:

```
cmd="startGame" …gameName="Xoc Dia"…   96.0
cmd="updateBet" …gameName="Xoc Dia"… 2497.0
cmd="subscribe" …                      20.0 / 25.0 / 151.0
```

**The registry (2c) is proven to resolve the right provider.** The detail log carries
session entry and fully-parsed round aggregates for product 116:

```
DEBUG SessionAggregationService [40fa3749…/9/BETTING_MINI] - BotGroup Xoc Dia/40fa3749…
  entered session 3221614 | sample:
  com.vingame.bot.domain.bot.message.g3.tip.TipStartGameMd5Message@3d5cf2fd

DEBUG SessionAggregationService [40fa3749…/7/BETTING_MINI] - UpdateBet #3 |
  new bettors since last: 0 | total bettors this round: 20 | total staked: 64985000 |
  options: [0]x16 [1]x12 [2]x20 [3]x16 [4]x17 [5]x19 |
  amount min/avg/max: 1000/241000/496000
```

Sessions advance (3221614 → 3221619) and the payload deserialises into the correct
product-specific type (`g3.tip.TipStartGameMd5Message`). A wrong provider parses nothing
and these lines never appear. **That is the check this step exists for, and it passes.**

Two honest qualifications, neither caused by this deploy:

1. **The plan's literal grep strings do not match the shipped wording.**
   `grep -E 'StartGame session-entry|EndGame results'` returns 0. The session-entry line
   is really worded `BotGroup <game>/<id> entered session <sid> | sample: …`. The signal
   is present; the plan quotes a label that was never the log text.
2. **`bot_bets_placed_total` is 0.0 on all four groups, and no EndGame summary is
   logged — but both were already true before this deploy.** Measured, not assumed:

   | | pre-deploy (19 h uptime) | post-deploy |
   |---|---|---|
   | `bot_bets_placed_total` series | 4, all `0.0` | 4, all `0.0` |
   | `bot_bet_amount_total` | all `0.0` | all `0.0` |
   | our-logger `EndGame` lines per 2 h detail file | **0** (checked in three separate pre-deploy rolled files: `…-03`, `…-05`, `…-07`) | 0 |
   | `entered session` lines per 2 h file | 176 / 176 / 177 | same rate |
   | `UpdateBet` aggregate lines per 2 h file | 3014 / 3018 / 3054 | same rate |

   The pre-deploy instance had processed **1,007,074** `updateBet` messages and still
   reported zero bets placed. So rounds start and update but never settle, fleet-wide,
   and did so before Phase 2 went anywhere near the box. This is the long-standing
   staging condition already recorded in `CLAUDE.md` (*Bets Never Settle — Host IP Not
   Whitelisted*, and the 097 games being dead server-side); it is the same root cause as
   the two pre-existing `GameNoRounds` alerts. **Not fallout from this release.**

   Consequently the "**and bets**" half of P2-6 is **not demonstrable on this staging
   box** — no group places bets regardless of build. The "starts / connects / receives
   and parses rounds" half is fully demonstrated.

**Result: PASS** (registry proof satisfied; the un-demonstrable half is a pre-existing
environment condition, evidenced above rather than asserted)

### P2-7 — the unsupported-product error text is unchanged, per Amendment A6
A6 pins **three** strings, not one, and requires a Tai Xiu miss to name
`TaiXiuMessageTypes`. Staging carries products 097, 098, 118, 116, 114; the registry
reported `BETTING_MINI [097, 098, 116, 118]` and `TAI_XIU [114, 116]`, so **two** of the
three misses are reproducible by pairing an existing group with a mismatched game type.
Both were exercised on already-DEAD/STOPPED throwaway groups and reverted immediately.

**String 1 — the Tai Xiu miss (the case the original plan got wrong):**
group `111` (1 bot, env `P_097`) repointed at a `TAI_XIU` game, then started:

```
ERROR BotGroupBehaviorService [111//] - Failed to create bot 1/1 for group 111
(env 3cda38f9-…-18ce83207768): java.lang.IllegalArgumentException:
TaiXiuMessageTypes not yet implemented for product code: 097.
Please create a TaiXiuMessageTypes implementation for this product.
```

**Names `TaiXiuMessageTypes`, not `GameMessageTypes`** — byte-for-byte A6's `:98-100` row.
Had the plan been implemented literally this would have read `GameMessageTypes` and been
wrong. **Confirmed correct on the box, not just in a unit test.**

**String 2 — the betting-mini miss:** group `29a8d97f` (2 bots, env `P_114`) repointed at
a `BETTING_MINI` game, then started:

```
java.lang.IllegalArgumentException: GameMessageTypes not yet implemented for
product code: 114. Please create a GameMessageTypes implementation for this product.
```

Byte-for-byte A6's `:52-54` row.

**String 3 — `ProductCode cannot be null`: NOT EXERCISABLE.** All five staging
environments have a non-null `productCode`, and producing this path would require
writing a malformed environment document. Per the plan's own instruction this is
recorded as **not exercisable rather than marked passed**; the fallback evidence is the
build-time pin (`MessageTypesErrorTextTest`, inside the 158 green bot-messages tests).

**Both probe groups were restored and verified restored:**

| Group | gameId restored to | `strategyMix` intact | final `targetStatus` |
|---|---|---|---|
| `111` | `3cda38f9-…-18ce83207761` | `[{RANDOM, 1}]` | `STOPPED` (was `DEAD`) |
| `29a8d97f-…` | `29d419f1-9c96-4e74-aec1-41c7fe5849c3` | `[{MARTINGALE_CLASSIC_CAUTIOUS, 1}]` | `STOPPED` (unchanged) |

The one residual change is group `111`'s `targetStatus` moving `DEAD` → `STOPPED`, a
benign consequence of my `stop` call on a group that was already failing to start. Flagged
here so it is not mistaken for drift.

**Result: PASS** (2 of 3 strings verified live and byte-exact; 3rd not exercisable, as the
plan anticipates)

### P2-8 — no new alerts fired across the deploy
Command: `curl -sf 'http://localhost:9090/api/v1/query?query=ALERTS{alertstate="firing"}'`
Expected: the same firing set as before the deploy.
Actual, at 09:43:48Z:

```
GameNoRounds  —  TaiXiu Seven   (097)
GameNoRounds  —  Xoc Dia mini   (097)
```

**Identical to the pre-deploy set.** Both are **pre-existing and long-standing**: those two
097 games are dead server-side and the user has confirmed it is not a bots issue. They are
named here so nobody rediscovers them as fallout from this release.

Transiently, `BotManagerRestarted` also fired immediately after the deploy. That rule is
`changes(process_start_time_seconds{job="bot-manager"}[15m]) > 0` — it is the *expected*
signal for any deploy, and it **self-resolved** once the restart aged out of its 15 m
window, as re-confirmed above. No `EnvironmentDeadBotRatioHigh`, no new `GameNoRounds`.
**Result: PASS**

---

## Additional checks requested for this release

**Message-types registry logs its startup line once per JVM: PASS.**
`grep -c` returned exactly **1**:

```
INFO MessageTypesRegistry - MessageTypesRegistry initialized:
BETTING_MINI 4 products [097, 098, 116, 118], TAI_XIU 2 products [114, 116],
SLOT provider SlotMessageTypesImpl
```

Product lists render **sorted**, matching A9's `[097, 098, 116, 118]` / `[114, 116]`
exactly.

**Phase 1 meters all still present and tagged `builtin`: PASS.**

```
plugin_classloaders_live{…,pluginVersion="builtin"}             1.0
plugin_classloaders_registered_total{…,pluginVersion="builtin"} 1.0
plugin_classloaders_reclaimed_total{…,pluginVersion="builtin"}  0.0
```

Three families, present as values rather than missing series, at their baseline values —
i.e. the eager registration still runs and the name is still
`plugin_classloaders_registered_total` (A2).

**`bots_by_plugin_version` fully accounts for the fleet: PASS.** Four rows, one per running
group, all `pluginVersion="builtin"`; **sum = 155 = `bots_managed` = 155**. No bot resolved
a null `pluginVersion`.

**No cardinality regression: PASS.** `bot_bets_placed_total` = **4 series**, unchanged from
the pre-deploy baseline. Total `pluginVersion`-bearing lines = **7**, unchanged. Lines
matching `^bot_` that carry a `pluginVersion` label = **0** (AD-5 holds).

**Log-track separation intact: PASS.** `pluginVersion` appears in JSON lines in
`logs/console.log` (track 1 → Loki) and **0 times** in `logs/detail/detail.log` (track 2),
as AD-8 requires.

**Application error volume since boot: clean.** The only `ERROR` lines in the entire boot
are the **two I deliberately provoked** for P2-7. Excluding those, zero.

---

## Metaspace baseline — reset by this restart (deliverable)

This deploy **resets the Phase 1 metaspace baseline**. The previous baseline started
`2026-08-26T14:08:18Z` and was due to be read `2026-09-02T14:08:18Z`; **that read is now
void** — the JVM it referred to no longer exists.

**New baseline:**

| | value |
|---|---|
| Baseline start (JVM start) | **2026-08-27T09:27:30Z** (`process_start_time_seconds = 1.787822850117E9`) |
| Sample taken at | 2026-08-27T09:34:42Z (uptime 432.7 s) |
| `jvm_memory_used_bytes{area="nonheap",id="Metaspace"}` | **84,834,512** (`8.4834512E7`, ≈ 80.9 MiB) |
| `jvm_classes_loaded_classes` | **17,105** |
| `jvm_classes_unloaded_classes_total` | 182 |
| **7-day read date** | **2026-09-03T09:27:30Z** |

For reference, the pre-deploy JVM at 19 h uptime read Metaspace `87,416,704` and
`16,622` classes loaded. The new figure is a *boot* reading, so it is not directly
comparable — it is the new zero, and the 7-day delta from it is what P1-10 asks for.

**`MetaspaceGrowth` is inert for 24 h after this restart, by design (A3).** The loaded rule
carries the uptime gate, confirmed in Prometheus's own loaded-rule output rather than only
in the file:

```
delta(jvm_memory_used_bytes{area="nonheap",id="Metaspace"}[1d]) > 5.24288e+07
  and on (job) (time() - process_start_time_seconds{job="bot-manager"} > 86400)
for: 1h
```

It therefore cannot evaluate until **2026-08-28T09:27:30Z**. An empty `ALERTS` vector
before then proves only that the rule loaded.

---

## Deviations from the handed-off instructions

1. **`.env` was not regenerated** — per the coordinator's amendment to step 4. It survived
   `compose down` intact (md5 unchanged, 300 bytes, 9 keys), so the exception clause did
   not apply. `VIPTALK_ENABLED`/`VIPTALK_BOT_TOKEN` were verified inside the running
   container.
2. **Two staging bot groups were temporarily repointed** to exercise P2-7's error paths,
   then restored. Detailed in P2-7, including the one residual `DEAD` → `STOPPED` status
   change on group `111`.
3. **Nothing was committed and nothing was pushed.** The pre-existing working-tree dirt
   (`TaiXiuMessages/*.js` deletions, `deploy.sh`, `docs/plans/AVIATOR_BOT.md`,
   `docs/reviews/VIPTALK_ALERTING_V2/release.md`) was left exactly as found. The only
   files this release added are this log and the two capture artefacts beside it.

## Verdict

**PASS**
