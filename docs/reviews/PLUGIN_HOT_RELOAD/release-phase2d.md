# Release — PLUGIN_HOT_RELOAD Phase 2d

Mode: **bot** (jar-only; no `prometheus/`, no `grafana/`, no compose file changed)
Branch: `feature/plugin-hot-reload`
HEAD: `36dfa60` — *docs: correct the registry-order divergence figure and the slotStrategyId claim*
Image: `vingame-bot:latest`, sha256 `8c18638052b2…dab06db27`, built 2026-08-27T11:06:19Z
Target: **Bot-1 (staging)**
Date: 2026-08-27T11:05:03Z → 2026-08-27T11:20Z

**Verdict: PASS.** Plan verification 12 of 13 executed steps passed; 1 (P2-7) was **not
exercisable** and is recorded as such rather than as a pass, per the plan's own
instruction.

---

## Step 1 — the pre-deploy capture (the hard gate)

Taken from the **running 2c build** before anything was touched. This is the only
baseline that could ever exist: 2c enumerates `StrategyId.values()`, 2d re-sources the
endpoint from the registry.

```
$ ssh Bot-1 'curl -sf http://localhost:8080/api/v1/strategy/'   > strategy-before.json
$ ssh Bot-1 'curl -sf ".../api/v1/strategy/?gameType=SLOT"'     > strategy-slot-before.json

bytes = 1822
md5   = a72c40f56057cda5434b273ea36315ea
SLOT  = []
```

**Amendment A11 cross-check: AGREES.**

| | A11 (computed off-box by the compliance architect) | Measured on Bot-1 |
|---|---|---|
| bytes | 1822 | **1822** |
| md5 | `a72c40f56057cda5434b273ea36315ea` | **`a72c40f56057cda5434b273ea36315ea`** |
| `?gameType=SLOT` | `[]` | **`[]`** |

Written to **`docs/reviews/PLUGIN_HOT_RELOAD/phase2d-capture-before.txt`** and committed.

**Gate confirmed on disk before proceeding** — the failure mode A11 documents was
explicitly re-tested, not assumed:

```
$ grep -o displayName docs/reviews/PLUGIN_HOT_RELOAD/phase2d-capture-before.txt | wc -l
9                    # non-zero -> gate PASS

# the same, on the file the previous release left behind:
$ grep -o displayName docs/reviews/PLUGIN_HOT_RELOAD/phase2-capture-before.txt | wc -l
0                    # the defect A11 describes, reproduced
```

Stronger than the grep: the raw body was re-extracted **from the committed file** and
re-hashed, so the evidence is the body itself and not a hash quoted in prose.

```
$ sed -n '/BEGIN GET .*strategy\/ (raw/,/END GET .*strategy\/ ===/p' phase2d-capture-before.txt \
    | sed '1d;$d' | tr -d '\n' | md5
a72c40f56057cda5434b273ea36315ea
```

---

## Build

- `mvn clean install`: **PASS** (48 s, 11:05:03Z → 11:05:51Z). `BUILD SUCCESS`, all five
  modules.
- `docker build --no-cache --platform linux/amd64`: **PASS**
- `docker save`: **PASS** — 393,103,360 bytes, md5 `76c21fe12e5e9af1e5883d27ab09269a`

### Test-count gate — matches the expectation exactly

```
Bot - API (contract module)  =>  Tests run: 138, Failures: 0, Errors: 0, Skipped: 0
Bot - Strategies             =>  Tests run: 126, Failures: 0, Errors: 0, Skipped: 0
Bot - Messages               =>  Tests run: 158, Failures: 0, Errors: 0, Skipped: 0
Bot - Engine                 =>  Tests run: 426, Failures: 0, Errors: 0, Skipped: 0
Bot - Application            =>  Tests run: 1158, Failures: 0, Errors: 0, Skipped: 0
TOTAL  tests=2006 failures=0 errors=0 skipped=0
```

Expected 2006 (138/126/158/426/1158). **Agrees on the total and on every module.**

Contract-module reverse-edge gate:

```
$ mvn -q -pl bot-api dependency:tree | grep -E 'bot-app|bot-engine|bot-strategies|bot-messages'
(no output)  -> PASS
```

### The guards that matter for 2d, confirmed green in *this* build

| Test | Result | What it protects |
|---|---|---|
| `StrategyCatalogResponseContractTest` | 9 tests, 0F/0E | AD-21 byte-identity of the response |
| `StrategyCatalogTest$MissingBuiltinWarning` | 6 tests, 0F/0E | the new startup WARN |
| `StrategyCatalogTest$Builtins` / `$NonBuiltins` | 3 + 2, 0F/0E | A10's `unregisteredBuiltinIsNotListed` |
| **`ApplicationContextLoadsTest.strategyRegistriesAreFullyPopulated`** | **ran, passed** | **A10's load-bearing guard** — exact set equality under the real `Starter` scan |
| `StrategyCatalogParityTest` | 7 tests, 0F/0E | the weaker bare-package-scan parity check |
| `MessageTypesErrorTextTest` | 5 tests, 0F/0E | AD-20 unsupported-product text (the P2-7 fallback) |

A10 names `ApplicationContextLoadsTest` as the load-bearing guard and warns it is the one
a future edit could weaken silently, so its presence was verified by name in the surefire
XML rather than inferred from the module total:

```
$ grep -ho 'testcase name="strategyRegistriesAreFullyPopulated"[^>]*' bot-app/target/surefire-reports/*.xml
testcase name="strategyRegistriesAreFullyPopulated" classname="com.vingame.bot.ApplicationContextLoadsTest" time="0.004"
```

---

## Ship

- `sftp put bot.tar`: **PASS** (11:06:47Z → 11:10:16Z, ~3.5 min)
- Integrity end-to-end: local md5 `76c21fe12e5e9af1e5883d27ab09269a` == on-host
  `76c21fe12e5e9af1e5883d27ab09269a` — **PASS**
- `infra-images.tar.gz`: **not shipped** (mode=bot)

### `.env` — not regenerated

Per the brief. Verified present and intact before the deploy and again **inside the
running container** afterwards:

```
$ ls -l .env      ->  300 bytes, Aug 20 19:14, 9 lines, 6 VIPTALK keys  (survived down/up)

$ docker exec bot-java-bot-manager-1 printenv | grep VIPTALK
VIPTALK_CUSTOMER_NOTICES_ENABLED=false
VIPTALK_ENABLED=true
VIPTALK_BOT_TOKEN=QGJv****            (masked; present and non-empty)
VIPTALK_OPS_ROOM_ID=!WfhvMgIVQUzZUoqDmm:matrix-uat.viptalk.org
VIPTALK_PUBLIC_RESOLVED_SUMMARY=
VIPTALK_INSTANCE_LABEL=staging
```

`deploy.sh` was **not** run and **not** committed. `docker compose down` was graceful,
not `docker kill`.

---

## Deploy

11:10:29Z → 11:11:01Z. All ten containers of the shared Compose project cycled.

- `docker compose down`: **PASS** (all 10 containers stopped + removed, network removed)
- `docker image rm vingame-bot:latest`: **PASS** (`RM_OK`, 12 layers deleted)
- `docker load -i bot.tar`: **PASS** (`Loaded image: vingame-bot:latest`)
- `docker compose up -d`: **PASS** (mongo gated healthy before bot-manager started)

---

## Smoke test

bot-manager reported healthy at **11:11:34Z**, 34 s after start.

```
NAMES                      STATUS
bot-java-grafana-1         Up 7 minutes
bot-java-prometheus-1      Up 7 minutes
bot-java-alertmanager-1    Up 7 minutes
bot-java-bot-manager-1     Up 7 minutes (healthy)
bot-java-promtail-1        Up 7 minutes
bot-java-loki-1            Up 7 minutes
bot-java-mongo-1           Up 7 minutes (healthy)
bot-java-evidence-shim-1   Up 7 minutes (healthy)
bot-java-viptalk-shim-1    Up 7 minutes (healthy)
bot-java-node-exporter-1   Up 7 minutes
```

- `docker ps` shows healthy: **PASS**
- Spring Boot ready log: **PASS** — `11:11:09.687 [main] INFO Starter - Started Starter in 7.741 seconds`
- Auto-start log: **PASS** — `11:11:08.985 INFO BotGroupBehaviorService - Bot Manager startup complete. 4 bot groups running`

Whole-stack re-verification (the Compose project is shared, so a bot redeploy takes the
observability stack with it):

| Component | Check | Result |
|---|---|---|
| bot-manager | `/actuator/health` | `{"status":"UP"}`, mongo UP, disk UP — **PASS** |
| Prometheus | `:9090/-/ready` | `Prometheus Server is Ready.` — **PASS** |
| Grafana | `:3000/api/health` | 200 — **PASS** |
| Loki | `:3100/ready` | 503 at 11:11, **200 at 11:12:08Z** — **PASS** (slow start, not a fault) |
| Alertmanager | `:9093` | **not published to the host** (`9093/tcp`, no host binding) — a host curl returns 000 by design. Verified in-network instead: `docker exec prometheus wget -qO- http://alertmanager:9093/-/ready` → `OK`, and Prometheus lists it under `activeAlertmanagers`. — **PASS** |
| Mongo / evidence-shim / viptalk-shim | container health | all `(healthy)` — **PASS** |

ERROR volume since boot: **2 lines, both self-inflicted by the P2-7 probe below.** No
other ERROR from any logger.

---

## Plan verification — `docs/plans/PLUGIN_HOT_RELOAD.md` § Verification, Phase 2

### P2-1 — health and stack
Command: the five endpoint checks above.
Expected: three 200s (app, Prometheus, Grafana).
Actual: all present; Loki and Alertmanager additionally verified as above.
**Result: PASS**

---

### P2-3 — `GET /api/v1/strategy/` is byte-identical  ← the single most important check
Command:
```bash
diff strategy-before.json strategy-after.json
md5 strategy-before.json ; md5 strategy-after.json
```
Expected: `diff` produces no output; both sides md5 `a72c40f56057cda5434b273ea36315ea`;
`?gameType=SLOT` still `[]`.
Actual:
```
BEFORE bytes=1822 md5=a72c40f56057cda5434b273ea36315ea
AFTER  bytes=1822 md5=a72c40f56057cda5434b273ea36315ea
A11 constant   1822        a72c40f56057cda5434b273ea36315ea
diff -> no output
SLOT: before=[]  after=[]  diff -> no output
```
Re-checked once more at ~10 min uptime: still `a72c40f56057cda5434b273ea36315ea`, still `[]`.

**Byte-identity result, stated explicitly: the response is byte-identical.** Same 9
entries, same ids, same displayNames, same descriptions, **same order**. All three
hashes — the pre-change capture off the box, the post-change capture off the box, and
A11's independently computed constant — are the same value. AD-21 holds under the
registry-backed implementation, and `?gameType=SLOT` still returns `[]` despite the slot
registry carrying two keys with display copy for both.
**Result: PASS**

---

### P2-2 — the strategy registries came up with the full catalogue
Command: `docker logs bot-java-bot-manager-1 2>&1 | grep -E '(Betting|Slot)StrategyFactory initialized'`
Expected: counts **9** and **2**; compare **set**, not sequence (A9).
Actual:
```
11:11:05.310 BettingStrategyFactory initialized: registered 9 strategies — [DALEMBERT_AGGRESSIVE,
  DALEMBERT_CAUTIOUS, FIBONACCI_AGGRESSIVE, FIBONACCI_CAUTIOUS, MARTINGALE_CLASSIC_AGGRESSIVE,
  MARTINGALE_CLASSIC_CAUTIOUS, PAROLI_AGGRESSIVE, PAROLI_CAUTIOUS, RANDOM]
11:11:05.313 SlotStrategyFactory initialized: registered 2 strategies — [FIXED, RANDOM]
```
Counts 9 and 2. The bracketed sets are **identical to the pre-deploy lines**, character
for character. Per A10 a count below 9 would be a UI regression as well as a wiring one;
it is 9.
**Result: PASS**

---

### Message-types registry startup line appears exactly once
Command: `docker logs … | grep -c 'MessageTypesRegistry initialized'`
Expected: exactly 1, content unchanged.
Actual: `1` —
`MessageTypesRegistry initialized: BETTING_MINI 4 products [097, 098, 116, 118], TAI_XIU 2 products [114, 116], SLOT provider SlotMessageTypesImpl`
Identical to the pre-deploy line.
**Result: PASS**

---

### The new `StrategyCatalog` startup WARN — silence is the pass condition
Command:
```bash
docker logs bot-java-bot-manager-1 2>&1 | grep -ci StrategyCatalog
docker logs bot-java-bot-manager-1 2>&1 | grep -icE "WARN.*(strateg|no registered bean)"
```
Expected: **0** — the warning fires once per JVM if a built-in `StrategyId` has no
registered bean, and a healthy deploy is silent.
Actual: **`0` and `0`**, both immediately after boot and again at ~10 min uptime.

This was treated as a gate, not a formality: had it fired it would mean the shipped
artifact is missing a strategy bean, whose visible symptoms are a shortened dropdown, a
400 on every PATCH of affected groups, and bots dying on bot threads. Corroborated
independently — the boot line reports 9 (P2-2), and the endpoint's own body is the
full 9 (P2-3), so the registry is provably complete rather than merely un-warned-about.
**Result: PASS**

---

### P2-4 — an existing group's persisted strategy mix survives a round trip
Command: `GET /api/v1/bot-group/{id}` before and after, for three groups
(`7a3716ed…` tx7/097, `ab81f9e6…` Xoc Dia mini/097, `2bf237bd…` Slot 120/116), then the
Mongo read.
Expected: no diff; `strategyId` values still uppercase, no rewrite/nulls/lowercase.
Actual: the raw diff showed **only** `lastStartedAt` and `activeTimeSeconds`, which are
restart-volatile by construction (the deploy restarts every group). With those two fields
normalised:
```
diff <(norm groups-before.txt) <(norm groups-after.txt)
-> NO DIFF: config + strategyMix + slotStrategyId identical
```
Every other field — including `strategyMix":[{"strategyId":"RANDOM","weight":1.0}]` and
`"slotStrategyId":"FIXED"` — is byte-identical. Mongo (`db.botGroups`, the real
collection name; the plan says `db.botGroup`) confirms no rewrite:
```
strategyMix: [ { strategyId: 'RANDOM', weight: 1 } ]   x6 groups
slotStrategyId: 'RANDOM' / 'FIXED'                     x6 groups
```
Uppercase, no nulls, no lowercase.
**Result: PASS**

---

### P2-5 — a bad key is still a 400, and does not partially apply
Command:
```bash
curl -X PATCH .../api/v1/bot-group/111 -d '{"strategyMix":[{"strategyId":"NONSENSE","weight":1.0}]}'
```
Expected: 400, then the group unchanged.
Actual:
```
HTTP 400
{"type":"Bad request","msg":"Unknown strategyId 'NONSENSE' in strategyMix — registered
 strategies: [DALEMBERT_AGGRESSIVE, DALEMBERT_CAUTIOUS, FIBONACCI_AGGRESSIVE,
 FIBONACCI_CAUTIOUS, MARTINGALE_CLASSIC_AGGRESSIVE, MARTINGALE_CLASSIC_CAUTIOUS,
 PAROLI_AGGRESSIVE, PAROLI_CAUTIOUS, RANDOM]"}

re-GET -> NO DIFF: rejection did not partially apply
```
Worth recording beyond the pass: the rejection message enumerates the **registry**, and
its nine keys are exactly the nine the picker serves. That is A10's ruling — registry
owns the set — observable at runtime, with picker and validator demonstrably agreeing.
**Result: PASS**

---

### P2-6 — a group actually starts and bets (proves the 2c registry resolves the right provider)
Command: health, `bot_messages_total`, and the session lines in track 2.
Expected: bots connected/playing; session-entry and round lines in `logs/detail/detail.log`;
`bot_messages_total > 0` with `bot_bets_placed_total` present.
Actual — group `40fa3749…` (Xoc Dia, product 116) is live and playing:
```
bot_messages_total{...cmd="startGame"...} 80
bot_messages_total{...cmd="subscribe"...} 20
bot_messages_total{...cmd="updateBet"...} 1997
sum(bot_messages_total) = 2950          # > 0
```
```
11:14:33 DEBUG SessionAggregationService [40fa3749…/17/BETTING_MINI]
  - BotGroup Xoc Dia/40fa3749… entered session 3221772 | sample:
    com.vingame.bot.domain.bot.message.g3.tip.TipStartGameMd5Message@6053e398
11:15:00 DEBUG SessionAggregationService [40fa3749…/17/BETTING_MINI]
  - UpdateBet #6 | new bettors since last: 0 | total bettors this round: 20
  | total staked: 131220000 | options: [0]x12 [1]x10 [2]x18 [3]x13 [4]x18 [5]x8
  | amount min/avg/max: 1000/281886/496000
```
The `sample:` class is `TipStartGameMd5Message` — the **116/TIP** provider on a 116
group. That is the check's whole point: a wrong provider parses nothing and these lines
never appear. 155 bots managed, 0 dead.
**Result: PASS**

Two honest caveats, neither a 2d regression:

1. **The plan's grep string is stale.** It greps `StartGame session-entry|EndGame results`;
   the shipped wording is `entered session <id>` and `UpdateBet #N`. Zero matches for the
   plan's pattern is a documentation drift, not an absence of signal — the lines are
   there under different text. Worth correcting in the plan.
2. **`bot_bets_placed_total` is present (4 series) but reads 0**, while
   `SessionAggregationService` simultaneously reports `total staked: 131,220,000` across
   20 bots. Bets are demonstrably on the wire. I checked whether the deploy caused this
   by querying Prometheus **across** the restart rather than trusting a post-deploy
   reading:
   ```
   max_over_time(bot_bets_placed_total[6h])
     Slot 120 => 0 ; Xoc Dia => 0 ; TaiXiu Seven => 0 ; Xoc Dia mini => 0
   ```
   Zero for the entire 6-hour window, i.e. on **both** sides of the deploy.
   **Pre-existing instrumentation gap, not introduced here** (it matches the known
   staging anomaly where `bot_bets_placed_total` disagrees with the money counters).
   The plan's literal expectation — the series be *present* — is met.

---

### P2-7 — the unsupported-product error text is unchanged
Command: attempted to reach the path by starting group `111` (a STOPPED 1-bot test group
that produced this exact error organically at 09:33 on the previous boot), then
`docker logs … | grep 'not yet implemented for product code'`.
Expected: the message text unchanged.
Actual: **0 occurrences — the path was never reached.** The start now fails one stage
earlier, in authentication:
```
11:16:44 ERROR BotGroupBehaviorService [111//] - Failed to create bot 1/1 for group 111:
  UpstreamLoginException: Login failed for user 'bcB0Ttest1': Data array is missing or
  empty in auth response | Payload: {"status":"INVALID","code":400,"message":"Dữ liệu không hợp lệ"}
```
That is a stale upstream test account, unrelated to this release, and it short-circuits
before the message-types lookup.

**Result: NOT EXERCISABLE** — recorded as such rather than marked passed, which is what
the plan explicitly instructs for this case. It does **not** count toward the pass tally.

Fallback evidence, per AD-20:
- The **pre-deploy** logs (previous boot, 2c) carried both variants verbatim:
  `GameMessageTypes not yet implemented for product code: 114. Please create a
  GameMessageTypes implementation for this product.` (x4) and the `TaiXiuMessageTypes` /
  `097` variant (x2).
- The string is produced by a single site,
  `MessageTypesRegistry.java:212`, unchanged in this diff.
- `MessageTypesErrorTextTest` (5 tests) pins both variants by exact `hasMessage` and
  passed in this build.

**Probe cleanup:** group `111` was returned to its exact pre-deploy state —
`targetStatus=STOPPED`, `actualStatus=STOPPED`, same `lastFailureReason` string as
before. `groups_dead_currently = 0`. The two ERROR lines in this release's log are from
this probe and nothing else.

---

### P2-8 — no new alerts fired across the deploy
Command: `ALERTS{alertstate="firing"}`, compared against the pre-deploy set.
Expected: the same firing set as before the deploy, judged against the two known
pre-existing `GameNoRounds` alerts, not against empty.

**A correction I have to record, because it nearly produced a false baseline.** My
pre-deploy alert query piped Prometheus' JSON through `python3` — **`python3` does not
exist on Bot-1** (it exits 127). The command printed nothing, which looks exactly like
"no alerts firing". Had I trusted it, `GameNoRounds` would have appeared to be *newly*
firing after the deploy and I would have reported a regression that did not happen. The
real pre-deploy set was recovered from Prometheus' own retained history — which survives
the app restart — by querying at an instant before `compose down`:

```
ALERTS @ 1787828700  (~11:05Z, ~5 min BEFORE compose down)
  GameNoRounds  firing   gameName="Xoc Dia mini"    env 3cda38f9… (097)
  GameNoRounds  pending  gameName="TaiXiu Seven"    env 3cda38f9… (097)

ALERTS now (post-deploy)
  GameNoRounds  firing   gameName="Xoc Dia mini"    env 3cda38f9… (097)
  GameNoRounds  pending  gameName="TaiXiu Seven"    env 3cda38f9… (097)
  BotManagerRestarted  firing  severity=warning
```

The two `GameNoRounds` entries are **identical in name, game, environment and state on
both sides**, and they are exactly the pair named as **pre-existing**: TaiXiu Seven and
Xoc Dia mini on env `3cda38f9…` (097), long-standing, server-side, confirmed by the user
as not a bots issue. `max_over_time` over 6 h shows both firing continuously across the
whole window.

The only addition is **`BotManagerRestarted`**, which this deploy caused by definition
and which self-resolves. No `EnvironmentDeadBotRatioHigh`, no new `GameNoRounds`, nothing
on the 116 environment.
**Result: PASS**

---

## Phase 1 regression checks (the meters must survive 2d)

### P1-3 — adopted JVM series present
```
jvm_memory_used_bytes{area="nonheap",id="Metaspace"} 8.390204E7     # > 0
jvm_classes_loaded_classes 16937.0
jvm_classes_unloaded_classes_total 216.0
```
**Result: PASS**

### P1-4 — classloader meters at baseline
```
plugin_classloaders_live{pluginVersion="builtin"}             1.0
plugin_classloaders_registered_total{pluginVersion="builtin"} 1.0
plugin_classloaders_reclaimed_total{pluginVersion="builtin"}  0.0
```
Exactly three families, all tagged `pluginVersion="builtin"`, at the expected 1 / 1 / 0.
Name is `_registered_total` (A2's rename intact, not `_created_total`, not
`plugin_classloaders_total`).
**Result: PASS**

### P1-5 — every bot accounted for under `builtin`
Four `bots_by_plugin_version` rows (20 + 20 + 15 + 100), all `pluginVersion="builtin"`.
Sum = **155**; `bots_managed` = **155**. No shortfall, so no bot resolved a null version.
`bots_dead_currently` = 0.
**Result: PASS**

### P1-6 — `pluginVersion` reached track 1 only
```
grep -c pluginVersion logs/console.log        -> 5354
grep -c pluginVersion logs/detail/detail.log  -> 0
grep -m1 -o '"pluginVersion":"[a-z]*"' logs/console.log -> "pluginVersion":"builtin"
```
JSON field in track 1 (the only track promtail ships), absent from track 2 per AD-8.
**Result: PASS**

### P1-7 — no metric-cardinality regression
`bot_bets_placed_total` series count: **4 before, 4 after** — unchanged.
Families mentioning `pluginVersion`, exhaustively: `bots_by_plugin_version`,
`plugin_classloaders_live`, `plugin_classloaders_registered_total`,
`plugin_classloaders_reclaimed_total`. **No `bot_*` line carries the label** (AD-5).
**Result: PASS**

### P1-8 — `MetaspaceGrowth` loaded, not firing, uptime gate present
Rule `MetaspaceGrowth` present in the loaded rules; `ALERTS{alertname="MetaspaceGrowth"}`
returns `"result":[]`. The A3 uptime gate is confirmed **in the loaded rule**, not merely
in the file — `process_start_time_seconds{job=…}` appears in the rule's expression as
served by `/api/v1/rules`. Per A3 the empty vector proves only that the rule loaded,
since it cannot evaluate below 24 h of uptime.
**Result: PASS**

### P1-9 — the dashboard provisioned
`/api/search?query=plugin` returns 401 unauthenticated (which is why an unauth check
looks empty — worth knowing); authenticated:
```json
[{"uid":"plugin-runtime","title":"Plugin runtime","type":"dash-db",
  "tags":["bot-manager","observability","plugin"]}]
```
All five dashboards present: Bots, Game server, Per-Environment, Per-Game, Plugin runtime.
**Result: PASS**

---

## Metaspace baseline — reset for the third time in two days

| | Value |
|---|---|
| **New JVM start** | **2026-08-27T11:11:00Z** (`process_start_time_seconds = 1787829060.83`) |
| `jvm_memory_used_bytes{area="nonheap",id="Metaspace"}` | **83,902,040 B** (~80.0 MiB) |
| `jvm_classes_loaded_classes` | **16,937** |
| `jvm_classes_unloaded_classes_total` | 216 |
| Reading taken at | 11:17:49Z, uptime 409 s |
| **7-day read due** | **2026-09-03T11:11:00Z** |
| `MetaspaceGrowth` inert until | 2026-08-28T11:11:00Z (A3's 24 h uptime gate) |

**The previous start of `2026-08-27T09:27:30Z` is VOID** — that JVM was replaced by this
deploy after 1 h 44 m and can never reach the 7-day mark. Its last reading, for the
record, was Metaspace 87,749,568 B / 17,165 classes at ~1 h 44 m uptime.

This is the **third reset in two days**. No 7-day metaspace delta has yet been
collectable on this box, and P1-10 remains **undelivered** — it is a deliverable of
Phase 1 that each redeploy pushes out by a week. Note the new figure (80.0 MiB at 7 min)
is *lower* than the void JVM's (83.7 MiB at 1 h 44 m), which is ordinary warm-up
divergence and not a comparable pair; do not read it as a trend. AD-6 sizes
`-XX:MaxMetaspaceSize` off the 7-day number, so that sizing stays blocked until a JVM
survives a week.

---

## Verdict

**PASS**

- Test gate: 2006 / 2006, 0 failures, 0 errors, 0 skipped — matches the expected split exactly.
- **Byte-identity (AD-21): confirmed.** `a72c40f56057cda5434b273ea36315ea` on the
  pre-change box, on the post-change box, and from A11's independent off-box computation.
  Same set, same values, same order. `?gameType=SLOT` still `[]`.
- The new `StrategyCatalog` WARN is **silent**, corroborated by the boot count of 9 and by
  the endpoint body itself.
- Phase 1 meters, MDC field, dashboard and alert rule all survived.
- Alert set unchanged but for the deploy's own `BotManagerRestarted`; the two
  `GameNoRounds` are the named pre-existing pair, verified identical on both sides.
- 12 of 13 executed verification steps passed; **P2-7 not exercisable**, recorded as such
  with AD-20 build-time fallback evidence, and deliberately not counted as a pass.

### Follow-ups for the plan owner (none block this release)

1. **P2-6's grep string is stale** — it looks for `StartGame session-entry|EndGame results`;
   the shipped text is `entered session <id>` / `UpdateBet #N`.
2. **P2-4 names the wrong Mongo collection** — `db.botGroup`; it is `db.botGroups`.
3. **`bot_bets_placed_total` reads 0 while bets are demonstrably flowing** (staked
   131 M on 20 bots). Pre-existing, confirmed across the restart, but it means P2-6's
   metric limb is currently vacuous as written.
4. **P2-7 is no longer reachable on staging** — the only group that hit it now fails at
   upstream login on a stale account. Either fix the account or accept AD-20's build-time
   test as the standing evidence.
5. **Verification commands must not assume `python3` on Bot-1** — it is absent, and a
   silently-empty pipe nearly manufactured a false alert baseline here.
6. **P1-10 is still undelivered** after a third metaspace reset in two days.

---

## Logs

Not applicable — no failure. For completeness, the only two ERROR lines emitted since
boot were both produced by this release's own P2-7 probe on group `111`, which was
restored to its exact pre-deploy state (`targetStatus=STOPPED`, `actualStatus=STOPPED`,
`groups_dead_currently=0`).
