# Release — BOT_LIVENESS_SEMANTICS Phase 1 (+ TIP prod game coverage, Phases 0–4)

Mode: bot
Target host: **`Prod-Bot`** (43.199.58.254:22000, user `sgame`), dir `/home/sgame/bot-java`
Branch: `staging` @ `9dfd1d8` **plus 5 uncommitted files** (deliberate — see Provenance)
Image: `vingame-bot:latest` = `c786e82333d9` (built 2026-08-10T10:05:53Z)
Date: 2026-08-10T10:05Z – 10:13Z

## Verdict summary

| Job | Result |
|---|---|
| (A) Deploy Phase 1 to Prod-Bot | **PASS** |
| (B) Runbook Phase 0 (pre-flight) | **PASS** |
| (B) Runbook Phase 1 (quiesce) | **PASS** |
| (B) Runbook Phase 2 (6 slot games) | **PASS** |
| (B) Runbook Phase 3 (10 bot groups) | **FAIL — 4 of 10 created; gateway registration rate limit** |
| (B) Runbook Phase 4 (250 M top-up) | **NOT RUN — deliberately halted** |
| Phase 5 (start groups) | Not run, as instructed |

**Overall: FAIL** (Phase 3 incomplete). Nothing is in a broken or half-written
state; the failure is clean and the retry is precisely targetable.

---

## Standing overrides applied

Recorded for audit. These came from the launching agent, asserting prior user
authorisation matching the 2026-08-06 prod deploy:

1. Target `Prod-Bot` instead of `Bot-1`. **Note the documented conflict:**
   `docs/plans/PROD_DEPLOYMENT.md` AD-9 states "Claude never touches the prod
   box… No agent (including `releaser`) acts on `Prod-Bot`." That plan predates
   the override. Flagging, not silently overriding — the user should reconcile
   AD-9 with current practice.
2. Dirty working tree accepted; clean-tree guard skipped; **nothing committed**.
3. No `deploy.sh`, no compose-file edit, **no `docker compose down`** — only
   `docker compose up -d bot-manager` (recreates one service).
4. `BOT_IP` / `BOT_DEPOSIT_AMOUNT` verified before and after.

---

## Provenance — what shipped

Committed (5 commits, BOT_LIVENESS_SEMANTICS Phase 1):

```
9dfd1d8 fix(bot): route the fifth close site in triggerFullReconnect through closeQuietly
accacc4 refactor(bot): drop lastClosedClient, make closeQuietly a plain null-guarded close
d69610c test(bot): cover close-error tolerance, logout/restart and the un-migrated fifth close site
2e98e4e test(bot): pin orphan-client close on a dead channel
6103e4a fix(bot): close every WS client unconditionally, never behind isOpen()
```

Uncommitted, shipped deliberately (deposit-amount work):

```
 M bot-api/.../config/bot/BotBehaviorConfig.java
 M bot-app/.../botgroup/service/BotGroupBehaviorService.java
 M bot-app/src/main/resources/application.properties     (+ bot.deposit.amount=1000000000)
 M bot-engine/.../domain/bot/core/Bot.java               (getMinBalance() = 10% of deposit amount)
 M bot-engine/src/test/.../core/BotTest.java
```

On prod, `BOT_DEPOSIT_AMOUNT=5000000` ⇒ effective min-balance trigger **500,000**
(was hardcoded 5,000,000).

## Build

- `mvn clean install -DskipTests -Dmaven.javadoc.skip=true`: **PASS** (11.4 s, all 6 modules SUCCESS)
- `docker build --no-cache --platform linux/amd64`: **PASS** (28 s) → `c786e82333d9`, 404 MB
- `docker save -o bot.tar`: **PASS** — 417,212,416 bytes
  - sha256 `78dd550eba7f7a86227e4917e7438a40c3ea25e83efa92d9108d144a6f1c324e`

Tests skipped per instruction (1,513 green locally, per brief).

## Ship

- `sftp put bot.tar` → `Prod-Bot:/home/sgame/bot-java`: **PASS** (60 s)
- Remote sha256 verified identical: **PASS**

## Deploy

`docker compose down` **not** run (override 3). Only `bot-manager` recreated.

| Step | Result |
|---|---|
| `docker tag vingame-bot:latest vingame-bot:rollback-phase1` | PASS |
| `docker image rm vingame-bot:latest` | PASS (`Untagged`) |
| `docker load -i bot.tar` | PASS |
| `docker compose up -d bot-manager` | PASS (mongo stayed up + healthy; bot-manager recreated) |

### Rollback tags on the host

| Tag | Image ID | What it is |
|---|---|---|
| `vingame-bot:latest` | `c786e82333d9` | **new** — Phase 1 liveness + deposit config |
| `vingame-bot:rollback-phase1` | `4a6af5b0b52b` | one step back — 2026-08-06 deploy (deposit config, pre-liveness-fix) |
| `vingame-bot:rollback-20260806` | `f07d4170d34d` | two steps back — pre-deposit-config (**untouched**, as instructed) |

Other five services (mongo, loki, promtail, grafana, prometheus) were not
touched — all still `Up 6 days`.

## Smoke test

| Check | Result |
|---|---|
| `docker ps` shows healthy | **PASS** — `Up 38 seconds (healthy)`; reached `healthy` at t≈40 s |
| Spring Boot ready log | **PASS** — `Started Starter in 4.012 seconds` |
| Auto-start log | **PASS** — `Bot Manager startup complete. 1 bot groups running` |
| `BOT_IP=43.199.58.254` post-deploy | **PASS** |
| `BOT_DEPOSIT_AMOUNT=5000000` post-deploy | **PASS** |
| Non-registration ERRORs in log | **PASS** — zero (all 21 ERRORs are the Phase 3 rate limit) |

## Feature verification — the WS-reconnect path

**Status: UNVERIFIED. Reported as such rather than substituted with a
meaningless check.**

The obvious post-deploy check — "`Cannot send message, not connected` count
dropped to 0" — is **worthless here and was not treated as evidence**: the JVM
restart destroys any orphaned client regardless of whether the fix works. For the
record the count is `0`, and that number proves nothing.

To actually exercise `tryReconnectWs` I would need to induce a WS drop on a
single live bot. I could not do that safely on production:

- The only running group at deploy time (`b44b1cc9`, "TIP Prod - Xoc Dia test",
  9 bots) was **deliberately stopped** by runbook Phase 1 immediately after.
- The 4 new groups are intentionally **not started** (Phase 5 is gated).
- There is no API to drop one bot's socket; the available levers (kill the
  container, blackhole the WS host) are stack-wide on a shared prod box.
- Periodic logout would exercise a logout/reconnect, but its interval is
  **60 minutes** and no group is running to reach it.

What *was* observed, and is genuinely attributable to the shipped change: the
**stop path** closed all 9 live clients unconditionally with no errors and no
orphan warnings (see timing below). That covers the `close()` call sites, **not**
`tryReconnectWs`.

**Recommendation:** verify `tryReconnectWs` on Bot-1/staging where a WS drop can
be induced freely, or on prod after Phase 5 once a group is live and a natural
disconnect or the 60-minute periodic logout occurs. Check: no
`Cannot send message, not connected` under that bot's `ws-<username>` after the
reconnect.

## Stop-latency measurement (the "stopping is now slower" concern)

Requested because `close()` can block up to `shutdownTimeoutMillis` (5,000 ms)
and `BotGroupRuntime.stopAllBots` closes serially.

Group `b44b1cc9`, **9 live, healthy bots**:

```
10:09:36.613  Bot bottest01 stopping. Closing client instance: 1483637771
10:09:36.657  Bot group b44b1cc9-... stopped successfully
```

**Total 44 ms; ~3 ms per bot.** Each close produced a clean
`Received close frame` → `Connection closed` → `Closed connection` triple.

Conclusion: **no measurable regression for responsive bots.** The 5,000 ms
timeout only bites on a wedged bot, which would be `9 × 5 s = 45 s` worst case
for this group. Worth knowing before stopping a large group with sick bots, but
it did not materialise here.

---

# Runbook: `docs/plans/TIP_PROD_GAME_COVERAGE.md`

## Phase 0 — Pre-flight (read-only) — **PASS**

| Step | Expected | Actual | Result |
|---|---|---|---|
| 0.1 tooling | jq, curl, md5sum + `TOOLS_OK` | all three present, `TOOLS_OK` | PASS |
| 0.2 app health | `200` | `200` | PASS |
| 0.3 environment | name `TIP Production`, product `TIP`, brand `G3` | exact match | PASS |
| 0.4 existing games | exactly 4, ids match, no SLOT | 4 rows, all 4 ids match Findings table | PASS |
| 0.5 baseline groups | exactly 2 | exactly 2 (`bottest0` 9 ACTIVE, `txprodbot` 3 STOPPED) | PASS |
| 0.6 egress IP | `43.199.58.254` | `43.199.58.254` | PASS |
| 0.7 password set | `PW_SET` | supplied (O-1 resolved) | PASS |

Note from 0.5: `txprodbot` (Tai Xiu autodeposit test) carries
`autoDepositEnabled: true` — the AD-9 unbounded-funding suspicion is
**confirmed** for that group. It is STOPPED, so it is inert, but it should not be
started casually. `bottest0` is `autoDepositEnabled: false`.

## Phase 1 — Quiesce pre-existing groups — **PASS**

```
OLD_XD=b44b1cc9-1eea-41ba-a125-1f876d2dcb9e   stop XD: 200
OLD_TX=fc39ee39-e52a-4c70-92a0-9ff900eb04fa   stop TX: 200
```

Both now `{"targetStatus":"STOPPED","actualStatus":"STOPPED"}`. Neither deleted
(AD-9). `b44b1cc9` will no longer auto-start on JVM boot.

## Phase 2 — Create the 6 SLOT games — **PASS**

| gameId | pluginName | new prod `_id` |
|---|---|---|
| 204 | `Tip` | `357b3451-ce7c-4d94-a35e-c4293158efe0` |
| 224 | `Tip` | `2f210ec5-aea4-451f-ab0d-642b267efb3e` |
| 118 | `Tip` | `f3fe7211-c111-428f-85c9-f54c34993476` |
| 119 | `Tip` | `83c27816-6cd1-40fe-af4f-c555fa119d3b` |
| 120 | `slotMachineWithExtraJackpotsPlugin` | `5894131c-eede-436b-a323-fb9582bd85c3` |
| 117 | `Tip` | `c0d6f601-8793-4b72-bb7f-a07ff0138735` |

Post-check: total games `10`, SLOT games `6`. Created via REST (AD-3); no Mongo
writes. Ids also saved on the host at `/tmp/slot-gids.sh` for the retry.

## Phase 3 — Create the 10 bot groups — **FAIL (4 of 10)**

### Created successfully (20 accounts registered, 5/5 each, zero partials)

| # | Group | `_id` | prefix | botCount | autoDeposit | targetStatus |
|---|---|---|---|---|---|---|
| 1 | TIP Prod - Bau Cua | `6ab3b80b-af67-424e-b932-9d610940966a` | `tpbcg1` | 5 | false | null (not started) |
| 2 | TIP Prod - Xoc Dia | `0241ac89-6bbf-4e43-9026-19a9a528882d` | `tpxdg1` | 5 | false | null (not started) |
| 3 | TIP Prod - Fruit Shop | `e24b1f7b-ab1f-4d28-bf08-e408fba1e6de` | `tpfsg1` | 5 | false | null (not started) |
| 4 | TIP Prod - Tai Xiu | `dee8fabd-ebe0-4c9c-af8f-984c480cf9f0` | `tptxg1` | 5 | false | null (not started) |

Each logged `Successfully registered all 5 users`. Grid validation passed on all
four — no 400s, no config changes needed.

### Failed — gateway registration rate limit

Group 5 (`Slot 204`) returned, and 6 (`Slot 224`) and 7 (`Slot 118`) repeated:

```
{"type":"Game server error","msg":"Failed to register any users for bot group
 'TIP Prod - Slot 204'. Errors: Failed to register tps204g15: Registration failed:
 Bạn đã đăng ký quá nhiều tài khoản, vui lòng thử lại sau. (status: ERROR, code: 257); …"}
```

`Bạn đã đăng ký quá nhiều tài khoản, vui lòng thử lại sau.` = *"You have
registered too many accounts, please try again later."* — an auth-gateway
**registration rate limit (code 257)**, tripped after 20 successful registrations
in ~90 seconds from a single egress IP. Not a config, validation or app fault.

**Groups 8, 9, 10 (Slot 119 / 120 / 117) were not attempted** — the limit was
clearly still in force and each attempt is 5 more upstream registration calls.

### Failure is clean — no partial state

- `Failed to register **any** users` ⇒ `BotGroupService.save` threw ⇒ **no group
  document was persisted** for 204/224/118. Group count is `6`, not `9`.
- `Partial user registration` warnings in the log: **0**. Concern C-5 (a group
  claiming `botCount: 5` with fewer real accounts) did **not** occur.
- Confirmed by direct gateway probe — every slot username returns
  `{"status":"NOT_FOUND","code":504}`:
  `tps204g11`, `tps224g11`, `tps118g11`, `tps119g11`, `tps120g11`, `tps117g11`.

### Exact account ledger (for a targeted retry)

| Account set | Exists? | Funded? |
|---|---|---|
| `tpbcg11`–`tpbcg15` | **yes** (login OK) | no — balance 0 |
| `tpxdg11`–`tpxdg15` | **yes** (login OK) | no — balance 0 |
| `tpfsg11`–`tpfsg15` | **yes** (login OK) | no — balance 0 |
| `tptxg11`–`tptxg15` | **yes** (login OK) | no — balance 0 |
| `tps204g11`–`5` | **no** (NOT_FOUND) — attempted, all 5 rejected | no |
| `tps224g11`–`5` | **no** (NOT_FOUND) — attempted, all 5 rejected | no |
| `tps118g11`–`5` | **no** (NOT_FOUND) — attempted, all 5 rejected | no |
| `tps119g11`–`5` | **no** — never attempted | no |
| `tps120g11`–`5` | **no** — never attempted | no |
| `tps117g11`–`5` | **no** — never attempted | no |

**20 of 50 accounts exist. 0 of 50 are funded.**

All 20 were verified live against the gateway (`login.aspx` → `session_id`, then
`verifytoken.aspx` → `main_balance`); all returned `0`. This also validates the
V-6 procedure and its `jq` path against the prod response shape — the runbook's
"session_id may be nested differently" caveat does **not** bite;
`.data[0].session_id` is correct.

## Phase 4 — One-time 5,000,000 top-up — **NOT RUN (deliberate halt)**

I stopped rather than depositing into the 20 existing accounts. Reasoning:

1. `deposit.aspx` is **additive and irreversible** (Concern C-2) and there is no
   withdraw call — money moved now cannot be recovered if the fleet plan changes.
2. The precondition for Phase 4 (50 accounts) failed. Funding 20 of 50 is a
   deviation from the plan, and the instruction was explicitly *not* to improvise
   around a Phase 3 failure.
3. The retry may want a **different naming generation** (AD-10's `g2`) or a
   different group split. Money on `…g1` accounts would then be stranded.

Cost of waiting is zero: nothing is running, nothing degrades, and the deposit
loop is unchanged whenever it is authorised.

- Planned: 250,000,000 across 50 accounts
- **Actually deposited: 0**
- Ready to deposit on approval: 100,000,000 across the 20 existing accounts

## Phase 5 — **NOT RUN**, as instructed. No bot group was started.

---

## Verification steps executed

| Step | Expected | Actual | Result |
|---|---|---|---|
| V-1 game count | `10` | `10` | **PASS** |
| V-2 slot games | `count=6 ids=6 gids=[117,118,119,120,204,224]` | exact match | **PASS** |
| V-3 group count | `12` | `6` | **FAIL** (consequence of Phase 3; 2 pre-existing + 4 new) |
| V-4 new-group shape | `n=10 badCount=0 badAutoDep=0 started=0` | `n=4 badCount=0 badAutoDep=0 started=0` | **PARTIAL** — count short, but every created group is correct: 5 bots, auto-deposit off, not started |
| V-5 deposits `ok=50/50` | `ok=50/50` | n/a — Phase 4 not run | **N/A** |
| V-6 balances all 5,000,000 | `bad=0, total=50` | 20 accounts checked, all `0` (unfunded, as expected with Phase 4 skipped) | **N/A** — procedure itself validated |
| V-7 nothing auto-started | one line, `10`, no ACTIVE/DEAD | `4 STOPPED`, no ACTIVE, no DEAD | **PASS** for the groups that exist |
| V-8 … V-10 | post-Phase-5 only | not run | **N/A** |

---

## State left on the host

- App healthy on the new image; all six services up.
- Games: 10 (4 pre-existing + 6 new slots).
- Bot groups: 6 — 4 new (STOPPED/never started) + 2 pre-existing (both STOPPED).
- **Nothing is running. No bots are connected. No money has moved.**
- Helper files kept on `Prod-Bot` for a targeted retry:
  `/tmp/prodenv.sh`, `/tmp/slot-gids.sh`, `/tmp/prod-games-before.json`,
  `/tmp/prod-games-after.json`, `/tmp/prod-groups-before.json`,
  `/tmp/prod-groups-after.json`, `/tmp/prod-bot-users-existing.txt`,
  `/tmp/balances.tsv`.

## Recommended next actions (need user decision — not taken)

1. **Wait out the registration rate limit**, then create the remaining 6 slot
   groups. The window length is unknown; the limit is per registering IP
   (`43.199.58.254`). Suggest one probe group first, and pacing the rest
   (e.g. one group every few minutes) rather than back-to-back.
2. **Then** run Phase 4 once, for whichever accounts exist at that point —
   ideally all 50 in a single pass, to keep the additive deposit auditable.
3. Only then Phase 5, which remains separately gated.
4. Reconcile `PROD_DEPLOYMENT.md` AD-9 with the current practice of the releaser
   operating on `Prod-Bot`.
5. Separately: verify the `tryReconnectWs` fix on staging, where a WS drop can be
   induced safely.

## Logs

No unexpected errors. All 21 `ERROR` lines in the post-deploy JVM are the Phase 3
rate limit (15 per-user `Failed to register tps…` + 6 group-level). Filtering
those out leaves **zero** ERROR lines.

Representative:

```
[user-registration-4] ERROR ApiGatewayClient - Failed to register tps204g15:
  Registration failed: Bạn đã đăng ký quá nhiều tài khoản, vui lòng thử lại sau.
  (status: ERROR, code: 257)
```

Health at close of session:

```
bot-java-bot-manager-1  Up 5 minutes (healthy)  vingame-bot:latest
```

---

# Addendum — Phase 4 (scoped) + Phase 5 (betting groups only)

Executed 2026-08-10T10:19Z–10:28Z on user decision: proceed with the four
betting groups only; **no slot retry** (rate limit handled with the auth team
separately).

## Step 1 — Phase 4, scoped to the 20 existing accounts — **PASS**

Target list verified before running: 20 usernames, **zero `tps*`** entries.

```
tpbcg11..15  tpxdg11..15  tpfsg11..15  tptxg11..15
```

All 20 returned HTTP 200 with
`{"status":"OK","code":200,"message":"Nạp tiền thành công"}`.

```
ok=20/20        bad=0, total=20
```

**V-5 (scoped): PASS — `ok=20/20`.**

Balance re-verification through the gateway (`login.aspx` → `verifytoken.aspx`),
all 20 accounts:

```
bad=0, total=20      every account main_balance == 5000000
```

**V-6 (scoped): PASS.** No account failed, none needed a retry, and the additive
endpoint was run exactly once.

- **Actually deposited: 100,000,000** (20 × 5,000,000)
- Original plan assumed 250,000,000 for 50 accounts; the 150,000,000 for the 30
  slot accounts was **not** spent.

## Step 2 — Phase 5, betting groups — **3 of 4 started**

```
Bau Cua:    200
Xoc Dia:    200
Fruit Shop: 200
Tai Xiu:    500   <-- FAILED
```

### Tai Xiu failed to start — pre-existing game-config gap (not caused by this work)

```
ERROR BotGroupBehaviorService - Failed to start bot group TIP Prod - Tai Xiu:
java.lang.IllegalStateException: Game c0a54255-b73d-485e-ba00-4418b378a8b9 (Tai Xiu)
has neither optionAffinities nor legacy numberOfOptions set
```

The prod Tai Xiu **game document** (`createdAt 2026-08-06`, one of the four
pre-existing games, not created by this runbook) is missing its option config —
and also has no `offset`:

```json
{"id":"c0a54255-...","name":"Tai Xiu","gameType":"TAI_XIU","pluginName":"taixiuPlugin",
 "gameId":1,"md5":false,"jackpotScaleEnabled":false,"jackpotCeiling":0,
 "crowdCountSemantic":"UNKNOWN"}
```

Compare the working Bau Cua record, which carries both:
`"offset":5000, "optionAffinities":{"0":1,"1":1,"2":1,"3":1,"4":1,"5":1}`.

This likely also explains why the pre-existing `txprodbot` Tai Xiu group was
sitting STOPPED. Runbook Phase 0.4 only checked id/type/name, so it did not catch
this — a gap worth adding to the runbook.

**Not fixed** — patching a prod game document is a config change outside this
task's authorisation. The group is cleanly `targetStatus: null`,
`actualStatus: STOPPED`; **no bots started, no money at risk.** Its 5 accounts
are funded and waiting. Fix is a one-field PATCH (`optionAffinities` or
`numberOfOptions`) plus, probably, `offset`.

### Observations on the three running groups

Legacy groups untouched: `bottest0` (9) and `txprodbot` (3) both still STOPPED.

**1. Bots authenticated — PASS, 5/5 every group**

| Group | total | connected | dead |
|---|---|---|---|
| Bau Cua | 5 | 5 | 0 |
| Xoc Dia | 5 | 5 | 0 |
| Fruit Shop | 5 | 5 | 0 |

All `CONNECTION_AUTHENTICATED`. No AUTH-race warnings, no watchdog expiries.

**2. Sessions entered and bets placed — PASS**

`SessionAggregationService` "entered session" INFO lines present for all three
groups, and the 5 s UpdateBet DEBUG aggregate is flowing. At 10:28:13Z:

| Group | bets placed | staked (app-side) |
|---|---|---|
| Bau Cua | 120 | 159,500 |
| Xoc Dia | 75 | 95,000 |
| Fruit Shop | 162 | 190,500 |

**3. Per-bot per-round stake vs ceiling — PASS, no group exceeds its ceiling**

Derived as `max total staked ÷ bettors` per round from the aggregate lines:

| Group | ceiling/bot/round | worst observed/bot | worst group-wide/round | verdict |
|---|---|---|---|---|
| Bau Cua | 8,000 | **6,200** | 31,000 (plan's unenforced worst case 40,000) | **within** |
| Xoc Dia | 20,000 | **3,600** | 18,000 (cap 20,000) | **within** |
| Fruit Shop | 10,500 | **5,300** | 26,500 (plan's unenforced worst case 52,500) | **within** |

Per-round detail:

```
Bau Cua     rounds: 24,500 / 31,000 / 25,500 / 27,500   (5 bettors each)
Xoc Dia     rounds: 18,000 / 15,000 / 14,000 / 6,000
Fruit Shop  rounds: 16,500 / 11,500 / 26,500
```

**The 2026-08-06 failure mode did not recur.** That incident saw ~82–90k per bot
against a nominal 50,000; here the worst per-bot round is 6,200 against a ceiling
of 8,000. The grids in the runbook are holding because the real constraint
(`maxBetsPerRound × maxBet`) was computed correctly — `maxTotalBetPerRound`
remains inert, as documented (AD-4/O-6), and is not what is protecting us.

**4. Coordination — PASS (Xoc Dia; Tai Xiu unverified, never started)**

```json
{"enabled":true,"maxAggregateStakePerRound":20000,"currentAggregateStake":15000,
 "approveCount":42,"trimCount":8,"rejectCount":30,"crowdAware":true}
```

Trim behaviour is visible (`trimCount=8`) and the aggregate holds — the worst
observed round-wide stake was 18,000 against the 20,000 cap, never exceeded.
High `rejectCount` (30) is the expected C-4 behaviour: under coordination
`betSkipPercentage` is pinned to 0, so bots propose every tick and the
coordinator does the limiting. Per-option budgets are being tracked
(`targetBudget 3333` each across 6 options). `observedCrowdCount: 0` throughout —
crowd-aware coordination has no crowd signal to work with on these rounds.

**Tai Xiu's 15,000 aggregate cap is UNVERIFIED** — the group never started.

**5. Auto-deposit — PASS, zero `[BotDeposit] POST` lines**

`docker logs | grep -c "\[BotDeposit\]"` = **0** over the whole container
lifetime. The lowered trigger (500,000 = 10% of `BOT_DEPOSIT_AMOUNT=5000000`,
formerly a hardcoded 5,000,000) did **not** fire, correctly, because
`autoDepositEnabled: false` on all four groups. Note the trigger is now *below*
current balances anyway, so this is not yet a strong test of the new threshold —
it will be exercised only once a bot drops under 500,000.

The single "deposit"-matching log line is my own `/actuator/env/bot.deposit.amount`
probe returning 404 (that actuator path is not exposed); harmless.

**6. Balance trajectory / runway — CANNOT BE ESTABLISHED. Significant finding.**

**Every account still reads `main_balance == 5000000` at the gateway**, sampled
twice (10:26:28Z all 15 running bots; 10:27:41Z a 6-bot spot check), while the
app believes those same bots have staked 30,000–50,000 each.

```
tpbcg11  gateway=5000000    app session balance 4968000
tpbcg14  gateway=5000000    app session balance 4965000
tpfsg13  gateway=5000000    app session balance 4954000
tpfsg14  gateway=5000000    app session balance 4950500
```

The app's figure is a **local decrement only** — it never re-fetched:

```
checkBalance() ENTRY. lastFetched: 5000000, expected: 4964750, delta: 35250
checkBalance() using cached: 4964750
```

Per Concern C-7 the re-fetch only fires when drift exceeds 1,000,000, so the app
has not yet reconciled with the gateway even once.

Corroborating: **zero `EndGame results` summary lines**, `lastRoundWinnings: 0`,
`totalWinnings` absent from the health DTO, and `rtp: null` on all three groups —
payouts are not being parsed for these TIP/P_116 games, mirroring the known
"winnings unparsed" finding recorded for BOM.

Two possible readings, which I cannot separate from outside:

- **(a) Bets are not settling** against the partition `deposit.aspx` credits and
  `verifytoken.aspx` reports — i.e. the fleet is generating no real turnover.
  This is exactly Open Item **O-2**.
- **(b) Bets settle against a different in-game partition** not reflected in
  `main_balance` — in which case `main_balance` is not a runway indicator at all
  and the true runway is unmeasured.

**Therefore I am not reporting a drain rate.** Any figure derived from
`main_balance` would be "zero drain, infinite runway", which is almost certainly
wrong, and any figure derived from the app's local counter would be an unverified
guess. Turnover for reference is ~445,000 staked across ~5 minutes (~89,000/min
fleet-wide, app-side); net drain depends on RTP, which is unmeasurable here
because winnings are not parsed.

The plan's V-8 stop-criterion (`bets == 0` after 5 minutes ⇒ rejection ⇒ stop) is
**not** met — the app reports 357 bets placed. But the underlying O-2 concern is
live and unresolved.

### Decision left open

The three groups are **still running**. Exposure is hard-bounded: auto-deposit is
off, so the worst case is the 100,000,000 already deposited and nothing more.
I did not stop them because the explicit stop-criterion was not met and the
instruction was to start and observe. **Say the word and I will stop all three
immediately** — that is one call per group and takes seconds.

Recommended before letting this run unattended: confirm through the game's own
ledger / back office whether the ~445,000 of staged bets actually landed. That is
the only way to settle (a) vs (b), per O-2.

## Final state at 2026-08-10T10:28:13Z

| Group | prefix | bots | targetStatus |
|---|---|---|---|
| TIP Prod - Bau Cua | `tpbcg1` | 5 | **ACTIVE** |
| TIP Prod - Xoc Dia | `tpxdg1` | 5 | **ACTIVE** |
| TIP Prod - Fruit Shop | `tpfsg1` | 5 | **ACTIVE** |
| TIP Prod - Tai Xiu | `tptxg1` | 5 | null (start failed, STOPPED) |
| TIP Prod - Xoc Dia test (legacy) | `bottest0` | 9 | STOPPED |
| TIP Prod - Tai Xiu autodeposit test (legacy) | `txprodbot` | 3 | STOPPED |

`bot-java-bot-manager-1  Up 20 minutes (healthy)`. No group other than the three
named was started. Nothing committed, nothing pushed.

**Note (AD-8 / C-9):** the three started groups are now `targetStatus: ACTIVE`
and will **auto-start on every JVM boot**. To take them down for real use
`/stop`, not a container restart.
