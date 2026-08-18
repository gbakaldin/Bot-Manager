# Release — BOT_LIVENESS_SEMANTICS Phase 1 (PRODUCTION)

Mode: bot
Target host: **`Prod-Bot`** (43.199.58.254), dir `/home/sgame/bot-java`, host `s009-botgame-general-01`
Branch: `staging` @ `9dfd1d8` **plus the 5 uncommitted DEPOSIT_AMOUNT_CONFIG files** (deliberate, AD-1)
Image: `vingame-bot:latest` = `0d53ed7b7a2f` (built 2026-08-11T14:04:45Z)
Rollback image: `vingame-bot:rollback-20260811` = `c786e82333d9` (the prior `latest`)
Date: 2026-08-11T13:56Z – 14:12Z

**`Bot-1` (staging) was not contacted at any point in this release.** Every `ssh`,
`sftp` and `scp` invocation in this log targets the `Prod-Bot` alias only; there is
no `Bot-1` command anywhere in the session.

## Verdict summary

| Job | Result |
|---|---|
| Task 1 — read-only prod inventory | **PASS** (no state changed) |
| Build (`mvn clean install`, full suite) | **PASS** — 1,516 tests, 0 failures |
| ws-parser `startWithDelay` integrity check | **PASS** |
| Ship (`sftp put bot.tar`) | **PASS** — SHA256 identical |
| Deploy (tag → rm → load → up -d bot-manager) | **PASS** |
| Smoke (healthy / `Started Starter` / startup complete / actuator 200) | **PASS** |
| Post-deploy betting re-verification | **PASS** — all 3 betting groups betting again |
| Collateral (other 5 containers) | **PASS** — uptime unbroken at 8 days |

**Overall: PASS.**

---

## Standing overrides applied

Recorded for audit, all carried over from the 2026-08-06 prod release
(`docs/reviews/DEPOSIT_AMOUNT_CONFIG/release.md`):

1. **Target `Prod-Bot` instead of `Bot-1`.** `docs/plans/PROD_DEPLOYMENT.md` AD-9
   ("no agent acts on Prod-Bot") was declared stale and overridden by the user for
   this release. Flagging rather than silently overriding — **AD-9 still reads as a
   prohibition and should be reconciled with actual practice**, since this is now the
   third releaser-run prod deploy against it.
2. **Dirty working tree deployed without committing.** Clean-tree guard skipped per
   AD-1. Nothing committed, stashed, reverted, cleaned or pushed.
3. **No `deploy.sh`, no compose-file overwrite.** Only `bot.tar` shipped. The
   hand-edited remote `docker-compose.yml` was left untouched — md5
   `40629c35e5b6bf9349a5d743261d275e` **before and after**, identical, and identical
   to the 2026-08-06 release value.
4. **No `docker compose down`.** Only `bot-manager` recreated.
5. **Rollback tag applied before the `rm`.**

---

# TASK 1 — Read-only prod inventory (pre-deploy)

Taken 2026-08-11 13:56–14:00 UTC against `http://localhost:8080` on Prod-Bot.
Purely read-only. **No group, environment, game or config was created, modified,
started or stopped.**

## Environment

One environment only:

| Field | Value |
|---|---|
| id | `78b6eefb-8930-4e2d-ba56-2c245ffc8551` |
| name | TIP Production |
| type | PRODUCTION |
| brand / product | `G3` / `116` (TIP), appId `bc115116` |
| WS | `wss://mynigems.socktipgmes.com/websocket` |
| gateway | `https://getquazations.tidclupgws.com` |
| totals | 6 bot groups, 32 bots, 3 running groups |

## Bot group inventory

All 6 groups live in the single TIP Production environment (product `116`/TIP).
`activationMode` / `activationWindow` are **absent on every group** — the API
returns no such fields for any of the 6, i.e. all are legacy/unscheduled and
governed purely by `targetStatus`.

| Group id | Name | Game (type, offset) | botCount | targetStatus | actualStatus | autoDeposit | activationMode / window |
|---|---|---|---|---|---|---|---|
| `dee8fabd-ebe0-4c9c-af8f-984c480cf9f0` | TIP Prod - Tai Xiu | Tai Xiu (`TAI_XIU`, `taixiuPlugin`) | 5 | *(null — never started)* | STOPPED | `false` | none |
| `e24b1f7b-ab1f-4d28-bf08-e408fba1e6de` | TIP Prod - Fruit Shop | Fruit Shop (`BETTING_MINI`, off 6000) | 5 | ACTIVE | ACTIVE | `false` | none |
| `0241ac89-6bbf-4e43-9026-19a9a528882d` | TIP Prod - Xoc Dia | Xoc Dia (`BETTING_MINI`, off 0) | 5 | ACTIVE | ACTIVE | `false` | none |
| `6ab3b80b-af67-424e-b932-9d610940966a` | TIP Prod - Bau Cua | Bau Cua (`BETTING_MINI`, off 5000) | 5 | ACTIVE | ACTIVE | `false` | none |
| `fc39ee39-e52a-4c70-92a0-9ff900eb04fa` | TIP Prod - Tai Xiu autodeposit test | Tai Xiu (`TAI_XIU`) | 3 | STOPPED | STOPPED | **`true`** | none |
| `b44b1cc9-1eea-41ba-a125-1f876d2dcb9e` | TIP Prod - Xoc Dia test | Xoc Dia (`BETTING_MINI`, off 0) | 9 | STOPPED | STOPPED | `false` | none |

Per-group health at inventory time:

| Group | totalBots | connected | reconnecting | dead | disconnected | playingStatus |
|---|---|---|---|---|---|---|
| Tai Xiu | 0 | 0 | 0 | 0 | 0 | null |
| Fruit Shop | 5 | 5 | 0 | 0 | 0 | IDLE |
| Xoc Dia | 5 | 5 | 0 | 0 | 0 | IDLE |
| Bau Cua | 5 | 5 | 0 | 0 | 0 | IDLE |
| Tai Xiu autodeposit test | 0 | 0 | 0 | 0 | 0 | null |
| Xoc Dia test | 0 | 0 | 0 | 0 | 0 | null |

`playingStatus == IDLE` on all three live groups is **not** a signal — Phase 5 of
this plan has not shipped, so `BotGroupRuntime.playingStatus` is still the dormant
field that is initialised to `IDLE` and never written (plan § Findings 5). Do not
read it as "not playing"; the betting evidence below is the real measurement.

## FINDING — `autoDepositEnabled=true` on a prod group

**`fc39ee39-e52a-4c70-92a0-9ff900eb04fa` — "TIP Prod - Tai Xiu autodeposit test" has
`autoDepositEnabled: true`.**

Mitigating facts, in the user's favour:

- It is **STOPPED** (`targetStatus=STOPPED`, `actualStatus=STOPPED`, 0 bots
  instantiated), last started 2026-08-06T11:10:18 and stopped 11:21:42 — i.e. it has
  been down for 5 days.
- It has no `activationMode`, so nothing will start it on a schedule. It can only
  come up via an explicit `POST /{id}/start` **or** via the auto-start path on an app
  restart — and auto-start keys off `targetStatus`, which is STOPPED. It did **not**
  auto-start during this release (confirmed: only 3 groups auto-started, all
  `autoDeposit=false`).
- Post-deploy deposit activity across the whole app: **0 log lines matching
  `deposit`** (case-insensitive).

So it is currently inert — but it is a **loaded gun**: one click of `start` on that
group, or anyone flipping its `targetStatus`, and it will auto-deposit at
`BOT_DEPOSIT_AMOUNT=5000000` per top-up. **Not changed by this release, as
instructed.** Recommend the user either set `autoDepositEnabled=false` or delete the
group, since its stated purpose (auto-deposit testing) is already served on staging.

The other 5 groups are all `autoDepositEnabled: false`, including all 3 live ones.

## Which groups are placing bets right now?

Derived from `SessionAggregationService` output at the production DEBUG default
(INFO session-entry / EndGame summaries + the 5 s DEBUG UpdateBet running summary),
cross-checked against a 90-second delta on the `totalBetsPlaced` / `totalBetAmount`
counters — the counter delta is the decisive measurement, the logs supply the
per-round detail.

### Genuinely betting (3 of 6)

30-minute log window, 13:27–13:57 UTC:

| Group | `entered session` | `session … ended` | UpdateBet flushes | Σ staked (30 min) | 90 s Δ bets | 90 s Δ stake |
|---|---|---|---|---|---|---|
| Fruit Shop `e24b1f7b` | 72 | 73 | 260 | 1,386,750 | **+60** | +79,500 |
| Bau Cua `6ab3b80b` | 62 | 62 | 273 | 726,000 | **+22** | +28,500 |
| Xoc Dia `0241ac89` | 43 | **0** | 546 | **0** (no EndGame) | **+17** | +30,000 |

Representative evidence lines:

```
13:56:24.055 INFO  SessionAggregationService [6ab3b80b…/1/BETTING_MINI] -
  BotGroup Bau Cua/6ab3b80b… session 2403920 ended | total staked: 14000 |
  total win: 0 | bettors: 3 | confirmed staked: 0
13:56:31.093 INFO  SessionAggregationService [6ab3b80b…/2/BETTING_MINI] -
  BotGroup Bau Cua/6ab3b80b… entered session 2403921
13:56:27.127 DEBUG SessionAggregationService [e24b1f7b…/5/BETTING_MINI] -
  UpdateBet #3 | new bettors since last: 0 | total bettors this round: 5 |
  total staked: 13750 | options: [0]x1 [1]x2 [5]x1 | amount min/avg/max: 500/1250/2000
```

Cumulative since their 2026-08-10T10:22–10:23 start (~27.5 h uptime):
Fruit Shop 59,933 bets / 76,351,000 staked; Bau Cua 32,538 / 40,717,500;
Xoc Dia 20,984 / 29,366,000.

### Connected-but-idle (0 of 6)

**None.** All three running groups are genuinely betting — there is no
running-but-idle group on prod. The other three groups are STOPPED with zero bots
instantiated, so they are not "idle", they are simply not deployed:

- Tai Xiu `dee8fabd` — never started (`targetStatus` is null, no bots ever created).
- Tai Xiu autodeposit test `fc39ee39` — stopped 2026-08-06.
- Xoc Dia test `b44b1cc9` — stopped 2026-08-10, carries
  `lastFailureReason: "Started 0/10 bots — all bot creations failed"`.

## Pre-existing anomalies observed during the inventory

These are **pre-existing on prod, not caused or fixed by this release.** Recorded
because the user asked for the real state of the box.

1. **Xoc Dia receives StartGame but never EndGame — 522 rounds entered, 0 ended in
   6 h, 0 `BotMemory.completeRound`.** Consequently `roundsSinceRestart` reads `0`
   despite ~21k bets placed, `averageWinning` is 0, and no round is ever settled in
   the app's own accounting. The group *does* bet (counters advance, UpdateBet
   flushes fire), so this is a **message-parsing/settlement-visibility gap, not a
   betting outage**. Xoc Dia is the only game on the box with `offset = 0`, which
   makes an offset/CMD registration collision the obvious first hypothesis. Still
   present after the deploy (5 entered / 0 ended), exactly as expected since this
   release touches no message code.
2. **`total win: 0` and `confirmed staked: 0` on every EndGame summary, all games.**
   Same shape as the known BOM `HasBotWinnings` gap in MEMORY — the TIP EndGame
   message likely does not implement the winnings interface, so payout/RTP metrics
   read 0 fleet-wide on prod. `payout=0` here is **not** evidence of rejected bets.
3. **Bau Cua watchdog churn: ~230 full reconnects per bot per 24 h** (one every
   ~6 min, ×5 bots) — `no game message in 180s — triggering full reconnect`. This is
   the single dominant source of the 19,676 WARN lines in 24 h. The group bets fine
   despite it, so it reads like server-side subscriber pruning (CLAUDE.md "silent
   zombie") rather than transport failure.
4. **84 ERROR lines in 24 h**, dominated by
   `Unexpected FullHttpResponse (status=101 Switching Protocols)` during periodic
   logout/reconnect, on both Fruit Shop and Xoc Dia.
5. **Stale `lastFailureReason`** on `b44b1cc9` still says "Started 0/10 bots" while
   `botCount` is 9 — carried over from the 2026-08-06 release notes, still not
   cleared on success.
6. **`docker-compose.yml.bak-20260806-170853`** still sits beside the live compose
   file, as flagged on 2026-08-06.

---

# TASK 2 — Build, ship, deploy

## FINDING — prod was **not** running the current tree (this deploy was substantive)

The task brief framed this release as shipping `6103e4a..9dfd1d8` to prod for the
first time. That premise was **partly wrong and worth recording**, because the
correct conclusion happens to point the same way:

- Prod was **already** running an image built 2026-08-10T10:05:53Z from the same
  HEAD `9dfd1d8`, deployed by the run recorded in
  `docs/reviews/BOT_LIVENESS_SEMANTICS/release-phase1.md`. A
  `vingame-bot:rollback-phase1` tag from that run was present on the box. So
  BOT_LIVENESS_SEMANTICS Phase 1 itself was **already live on prod** before today.
- **However, the uncommitted portion of the tree changed after that build.**
  `Bot.java` and `BotTest.java` have mtimes of 2026-08-10 10:51Z — 46 minutes *after*
  the prod image was built (10:05Z) and 10 minutes *before* the staging image was
  built (11:01Z). So prod was running a Bot.java that **staging never validated**,
  and the staging-validated version was **not** on prod.

Verified by byte comparison rather than inference — `Bot.class` extracted from the
running prod jar vs the freshly built one:

```
prod  Bot.class md5 = 4bbacbf068e4b696b28f10f92b79e058
local Bot.class md5 = 9d3904bbfcbc671e2c5a1379c976926d
javap method-set diff:  > private long balanceSyncThreshold();
```

The single method-level delta is `balanceSyncThreshold()`, and its effect on prod is
real, not cosmetic:

| Behaviour | Prod before | Prod after |
|---|---|---|
| Balance re-sync trigger | hardcoded `1_000_000` drift | `1 %` of deposit = **50,000** drift |
| Auto-deposit trigger (`getMinBalance`) | hardcoded `5_000_000` | `10 %` of deposit = **500,000** |

This is exactly the bug the source javadoc describes against real prod data ("staked
up to 358,000 each … never crossed the 1,000,000 drift, so the app never noticed the
server balance had not moved at all"), and the pre-deploy inventory reproduced the
symptom precisely: **every** bot on all three live groups reported
`lastFetchedBalance` pinned at exactly `5,000,000` while its local `balance` had
drifted to 4.1–4.9 M. So the deploy was warranted, and the `getMinBalance` half is
inert on prod today because all three live groups have `autoDepositEnabled=false`.

**Net: this release moved prod from an unvalidated intermediate build to the exact
build staging signed off on 2026-08-10.**

## Build

- `mvn clean install -Dmaven.javadoc.skip=true` (**tests NOT skipped**): **PASS** (33.8 s)

  | Module | Tests | Failures | Errors |
  |---|---|---|---|
  | Bot - API | 98 | 0 | 0 |
  | Bot - Strategies | 111 | 0 | 0 |
  | Bot - Messages | 136 | 0 | 0 |
  | Bot - Engine | 363 | 0 | 0 |
  | Bot - Application | 808 | 0 | 0 |
  | **Total** | **1,516** | **0** | **0** |

  All 6 reactor modules SUCCESS. (Baseline in the plan is "~1,497 tests"; 1,516 is
  consistent with the Phase 1 tests added by `d69610c` / `2e98e4e`.)

- `docker build --no-cache --platform linux/amd64`: **PASS** → `0d53ed7b7a2f`, 404 MB
- `docker save -o bot.tar`: **PASS** — 417,212,416 bytes,
  sha256 `71f5a768cc8c375fb434fcd50d1cee94928044ccacf7c2422775135a8c0252c4`

### ws-parser integrity check (mandatory, ping-before-AUTH)

The 3.0.5 rebuild-in-place is version-indistinguishable, so the fix was verified in
the shipped artifact rather than by version:

```
$ unzip -p bot-app/target/Bot-1.0.jar 'BOOT-INF/lib/websocket-parser-core-3.0.5.jar' > wsp.jar
$ javap -p -c com/vingame/websocketparser/VingameWebSocketClient.class | grep PingScheduler
  METHOD: public void connect();
    699: invokevirtual  // Method com/vingame/websocketparser/PingScheduler.startWithDelay:(JJLjava/util/function/Consumer;)V
```

Call census inside `VingameWebSocketClient`: `startWithDelay` ×1 (in `connect()`),
`stop` ×2, **bare `start()` ×0**. **PASS** — the delayed ping is in the artifact, so
AUTH wins the race. Not aborted.

## Ship

- `sftp put bot.tar`: **PASS** (50 s)
- Integrity: remote sha256 `71f5a768cc8c…0252c4` == local — **byte-identical**, and
  size `417212416` matches exactly.

## Deploy

Single chained invocation, exit 0:

```bash
cd /home/sgame/bot-java && \
  docker tag vingame-bot:latest vingame-bot:rollback-20260811 && \
  docker image rm vingame-bot:latest && \
  docker load -i bot.tar && \
  docker compose up -d bot-manager
```

- `docker compose down`: **SKIPPED** (authorized — shared compose project)
- `docker tag … rollback-20260811`: **PASS** (before the `rm`)
- `docker image rm vingame-bot:latest`: **PASS**
- `docker load -i bot.tar`: **PASS**
- `docker compose up -d bot-manager`: **PASS** — one service recreated

### Compose file untouched

| Point | md5 of `/home/sgame/bot-java/docker-compose.yml` |
|---|---|
| Pre-deploy | `40629c35e5b6bf9349a5d743261d275e` |
| Post-deploy | `40629c35e5b6bf9349a5d743261d275e` |

Identical, and identical to the 2026-08-06 baseline. Abort condition not triggered.
`.env` was never read, written or referenced.

### Rollback

Confirmed present after the deploy:

```
vingame-bot   latest              0d53ed7b7a2f   2026-08-11 21:04:45 +07
vingame-bot   rollback-20260811   c786e82333d9   2026-08-10 17:05:53 +07   <- prior latest
vingame-bot   rollback-phase1     4a6af5b0b52b   2026-08-06 18:04:27 +07
vingame-bot   rollback-20260806   f07d4170d34d   2026-08-05 20:04:01 +07
```

One-command revert:

```bash
docker tag vingame-bot:rollback-20260811 vingame-bot:latest && docker compose up -d bot-manager
```

### Collateral — other services untouched

| Container | Status after deploy |
|---|---|
| `bot-java-bot-manager-1` | Up 33 seconds (healthy) — **recreated, intended** |
| `bot-java-mongo-1` | Up 8 days (healthy) — untouched |
| `bot-java-loki-1` | Up 8 days — untouched |
| `bot-java-promtail-1` | Up 8 days — untouched |
| `bot-java-grafana-1` | Up 8 days — untouched |
| `bot-java-prometheus-1` | Up 8 days — untouched |

Avoiding `docker compose down` worked: uptime on the other five is unbroken.

## Smoke test

- **Container healthy**: **PASS** — `Up 33 seconds (healthy)`, well inside the window
- **Spring Boot ready**: **PASS** —
  `14:06:25.865 [main] INFO Starter - Started Starter in 4.194 seconds (process running for 4.943)`
- **Auto-start / startup complete**: **PASS** —
  `14:06:25.312 [main] INFO BotGroupBehaviorService - Bot Manager startup complete. 3 bot groups running`

  **N = 3 matches the Task 1 inventory exactly** (Fruit Shop, Xoc Dia, Bau Cua — the
  three `targetStatus=ACTIVE` groups). The three STOPPED groups did **not** start,
  including the `autoDepositEnabled=true` one:

  ```
  14:06:24.020 Auto-starting bot group: TIP Prod - Bau Cua (6ab3b80b…)
  14:06:24.957 Auto-starting bot group: TIP Prod - Xoc Dia (0241ac89…)
  14:06:25.119 Auto-starting bot group: TIP Prod - Fruit Shop (e24b1f7b…)
  ```

- **Actuator health**: **PASS** — HTTP `200`,
  `{"status":"UP"}` with `diskSpace` UP (97.6 GB free), `mongo` UP, `ping` UP, `ssl` UP
- **Env vars survived**: **PASS** — `BOT_IP=43.199.58.254`, `BOT_DEPOSIT_AMOUNT=5000000`
  present in the running container

## Verification

`docs/plans/BOT_LIVENESS_SEMANTICS.md` § Verification is written for Bot-1/staging
and its Phase 1 steps 4–5 depend on a Loki query endpoint and on restarting a group
(explicitly out of scope here). The applicable checks are run below; substitutions
are noted per step.

### Step 1 (§ Universal smoke 1): app up
Command: `curl -sf http://localhost:8080/actuator/health`
Expected: `"status":"UP"` within 60 s
Actual: HTTP 200, `{"status":"UP", …}` — mongo/ping/ssl/diskSpace all UP
Result: **PASS**

### Step 2 (§ Universal smoke 2): observability stack survived the co-located redeploy
Command: `docker ps` uptime check on the other five services
Expected: Prometheus/Grafana/Loki/Promtail/Mongo still up
Actual: all five at `Up 8 days` — unbroken across the redeploy (`down` was skipped
precisely to achieve this)
Result: **PASS**
*Substitution:* verified by container uptime rather than the plan's
`:9090/-/healthy` + `:3000/api/health` probes, which are not exposed on this host.

### Step 3 (§ Universal smoke 3): a group is running and playing
Command: `curl -sf http://localhost:8080/api/v1/bot-group/$G/health`
Expected: `connectedBots >= floor(totalBots*0.8)`, `deadBots == 0`
Actual, all three live groups: `connectedBots 5/5`, `reconnectingBots 0`,
`deadBots 0`, `disconnectedBots 0`
Result: **PASS** (5/5 exceeds the 4/5 floor on every group)

### Step 4 (§ Phase 1, step 4): orphan-client flood eliminated
Command: `docker logs bot-java-bot-manager-1 | grep -c "Cannot send message, not connected"`
Expected: `< 1 %` of the pre-deploy baseline, ideally 0
Actual: **0** since startup. Pre-deploy (old image, 60-minute window): **1** line —
so prod was already effectively clean, and remains clean.
Result: **PASS**
*Substitution:* counted directly from container logs rather than via the plan's Loki
`count_over_time` query (Loki HTTP is not exposed on Prod-Bot). Note this is a
weaker before/after than staging's 98.6 → 0, because prod already carried Phase 1.

### Step 5 (§ Phase 1, step 5): no orphan survives a group restart
Expected: restart a group, confirm 0 orphan lines
Actual: **NOT RUN — deliberately out of scope.** The task explicitly forbids
starting, stopping or restarting any bot group. The container recreate exercised the
equivalent teardown path (`stopAllBots` → `Bot.cleanup()` → unconditional `close()`)
across all 3 live groups with 0 resulting orphan lines, which is partial cover.
Result: **NOT RUN** (not a failure — excluded by instruction)

### Step 6 (§ Phase 1, step 6): no thread/heap regression
Command: `curl -sf http://localhost:8080/actuator/metrics/jvm.threads.live`
Expected: within ±10 % of pre-deploy
Actual: **47** live threads. The 2026-08-06 prod baseline was 51; 47 is −7.8 %,
inside the band, and consistent with a freshly started JVM at 15 bots.
Result: **PASS**

### Step 7 (§ Phase 2, step 7): zombie bucket drained
Command: `… /health | jq '.disconnectedBots'`
Expected: `0` on a healthy group
Actual: `0` on all three live groups
Result: **PASS** (informational — Phase 2 has not shipped; this is the pre-condition
canary, not a test of the sweep)

### Step 8 (user-supplied): post-deploy betting re-verification
Command: 100-second delta on `totalBetsPlaced` / `totalBetAmount` per group, plus
`SessionAggregationService` summary counts
Expected: the groups that were betting before the deploy are betting again after
Actual:

| Group | pre-deploy Δ/90 s | post-deploy Δ/100 s | session entries (3 min) |
|---|---|---|---|
| Fruit Shop `e24b1f7b` | +60 bets / +79,500 | **+59 bets / +84,250** | 7 |
| Xoc Dia `0241ac89` | +17 bets / +30,000 | **+33 bets / +44,000** | 4 |
| Bau Cua `6ab3b80b` | +22 bets / +28,500 | **+60 bets / +77,500** | 6 |

All three resumed betting within ~90 s of container start, at rates equal to or
above their pre-deploy rates, with 5/5 bots connected on each.
Result: **PASS**

### Step 9 (user-supplied, critical): no auto-deposit fired
Command: `docker logs bot-java-bot-manager-1 | grep -ciE "deposit"`
Expected: 0 — all three running groups have `autoDepositEnabled=false`
Actual: **0** matching lines of any kind
Result: **PASS**

### Step 10 (release-specific): the shipped delta is actually live
Command: `docker logs … | grep "checkBalance() ENTRY"`
Expected: the new proportional threshold in effect (1 % of `BOT_DEPOSIT_AMOUNT` =
50,000), replacing the old hardcoded 1,000,000
Actual:
```
14:09:07.736 DEBUG Bot [e24b1f7b…/2/BETTING_MINI] - checkBalance() ENTRY.
  lastFetched: 5000000, expected: 4991000, delta: 9000, threshold: 50000
```
`threshold: 50000` is emitted by code that does not exist in the prior prod image
(the log line itself gained the `threshold:` field in this change). 15 server balance
re-fetches fired in the first ~3 minutes; under the old 1,000,000 threshold, deltas
of 9,000–31,500 would have triggered **none**.
Result: **PASS** — the deploy demonstrably delivered the intended behaviour change.

### Step 11 (supplementary): error/warn budget since deploy
Actual: **0 ERROR**. **10 WARN**, all benign startup transients from a single round
(`sessionId=1366572`) on Fruit Shop: 5× `BotMemory.completeRound: sessionId
mismatch … in-flight round discarded` and 5× `<Strategy>.onRoundEnd: behavior bounds
not yet cached — skipping progression update` — i.e. the first EndGame arrived before
each bot had cached bounds or opened an in-flight round. One-shot, did not recur.
Result: **PASS**

## Verdict

**PASS**

Build green with the full 1,516-test suite; ws-parser ping-before-AUTH fix verified
present in the shipped artifact; byte-identical transfer; compose file and `.env`
untouched; only `bot-manager` recreated with the other five services' uptime
unbroken; healthy in 33 s; 3 of 3 expected groups auto-started and all three are
betting again at pre-deploy rates or better; zero ERROR, zero auto-deposits, zero
orphan-client lines; rollback image tagged and confirmed.

No bot group, environment, game or config was created, modified, started or stopped
by this release. `Bot-1` was never contacted.

## Follow-ups for the user (no action taken)

1. **`autoDepositEnabled=true` on `fc39ee39` ("TIP Prod - Tai Xiu autodeposit
   test")** — inert while STOPPED, but one `start` away from live auto-deposits at
   5,000,000 per top-up. Disable the flag or delete the group.
2. **Xoc Dia never receives EndGame** (522 rounds entered / 0 ended in 6 h; 0
   `completeRound`). Bets are placed but never settled in the app's accounting, so
   `roundsSinceRestart=0` and winnings are unmeasurable. It is the only `offset = 0`
   game on the box — check the CMD/offset registration for a collision.
3. **`total win: 0` fleet-wide on TIP** — same shape as the known BOM
   `HasBotWinnings` gap; prod RTP/payout metrics are currently meaningless.
4. **Bau Cua watchdog churn** — ~230 full reconnects per bot per 24 h, the dominant
   source of ~19.7k WARN/24 h. Looks like server-side subscriber pruning.
5. **`PROD_DEPLOYMENT.md` AD-9 is now contradicted by three successive prod
   deploys.** Either amend the AD or stop overriding it per-release.
6. **The 5 DEPOSIT_AMOUNT_CONFIG files remain uncommitted** and have now been built
   into three prod images. Their content changed between the 2026-08-10 prod build
   and the 2026-08-10 staging build, which is exactly how prod ended up running an
   unvalidated variant. Committing them would make prod provenance reproducible from
   a SHA instead of from file mtimes.
7. **Stale `lastFailureReason`** on `b44b1cc9`, and the stray
   `docker-compose.yml.bak-20260806-170853` — both still open from 2026-08-06.

---

# Post-release prod operations (2026-08-11)

Two API-only operations on **Prod-Bot**, authorized by the user after the release
above. **No build, no ship, no container restart, no compose/`.env` change, no
`docker compose down`.** All calls were `curl` against `http://localhost:8080` on the
box. `Bot-1` was not contacted.

Executed 14:16–14:20 UTC, against the same app instance deployed earlier in this
document (`vingame-bot:latest` = `0d53ed7b7a2f`, container up and healthy throughout).

## Operation 1 — disable auto-deposit on `fc39ee39`

Closes follow-up #1 from this release: the one prod group carrying
`autoDepositEnabled: true`.

```bash
curl -fsS -X PATCH http://localhost:8080/api/v1/bot-group/fc39ee39-e52a-4c70-92a0-9ff900eb04fa \
  -H 'Content-Type: application/json' -d '{"autoDepositEnabled": false}'
```

Result: **HTTP 200 — PASS.**

Field-by-field before/after. The minimal PATCH body did **not** null out any other
field, confirming the `Optional.ofNullable(...).orElse(existing)` boxed-type mapper
behaves as expected on a partial update:

| Field | Before | After | Changed? |
|---|---|---|---|
| `autoDepositEnabled` | `true` | **`false`** | **YES (intended)** |
| `botCount` | 3 | 3 | no |
| `minBet` | 1000 | 1000 | no |
| `maxBet` | 10000 | 10000 | no |
| `betIncrement` | 1000 | 1000 | no |
| `maxTotalBetPerRound` | 50000 | 50000 | no |
| `minBetsPerRound` / `maxBetsPerRound` | 1 / 5 | 1 / 5 | no |
| `gameId` | `c0a54255-b73d-485e-ba00-4418b378a8b9` | same | no |
| `environmentId` | `78b6eefb-…-2c245ffc8551` | same | no |
| `namePrefix` | `txprodbot` | `txprodbot` | no |
| `password` | `123321` | `123321` | no |
| `name` | TIP Prod - Tai Xiu autodeposit test | same | no |
| `coordinationEnabled` / `maxAggregateStakePerRound` | false / 0 | false / 0 | no |
| `crowdAwareCoordination` / `rampEnabled` / `rampShape` | false / false / 0.0 | same | no |
| `affinityWeightedProposal` / `chatEnabled` | false / false | same | no |
| `strategyMix` | `[RANDOM 1.0]` | `[RANDOM 1.0]` | no |
| `targetStatus` | STOPPED | STOPPED | no |
| `lastStartedAt` / `lastStoppedAt` | 2026-08-06T11:10:18 / 11:21:42 | unchanged | no |

Exactly one field changed. The group is STOPPED with 0 bots instantiated, so there
was no runtime effect.

**Fleet-wide result: no bot group on prod has `autoDepositEnabled=true` any more.**
Verified across all 7 groups after the operation.

## Operation 2 — create the 50-bot production Tai Xiu group

### Pre-check (mandatory, passed)

| Check | Result |
|---|---|
| No existing group uses `tptxg2` | **PASS** — existing prefixes are `tptxg1`, `tpfsg1`, `tpxdg1`, `tpbcg1`, `txprodbot`, `bottest0` |
| No account-name collision with `tptxg21`…`tptxg250` | **PASS** — nearest neighbour is `tptxg1` (botCount 5 ⇒ `tptxg11`…`tptxg15`), disjoint from the `tptxg2`+N range. No existing prefix is `tptxg2`, `tptxg25`, or any other ambiguous truncation |
| Username within TIP cap | **PASS** — `tptxg2` (6) + max 2 digits = **8 chars**, cap `usernameMaxLength=12` for product `116` |
| `environmentId` correct | **PASS** — `78b6eefb-…-2c245ffc8551` = TIP Production |
| `gameId` is the TAI_XIU game | **PASS** — `c0a54255-…` = `name: Tai Xiu`, `gameType: TAI_XIU`, `pluginName: taixiuPlugin`, same game as `dee8fabd`; **not** any BETTING_MINI entry |

### The POST

`POST /api/v1/bot-group/` with the payload as supplied, `targetStatus` deliberately
omitted.

Result: **HTTP 200 in 0.52 s** — group id **`ef3b61af-12af-4791-b94c-ff521a04cfd5`**.

The fast response is not a sign registration was skipped: registration is
synchronous, and 50 accounts at `user.registration.parallelism=10` is 5 waves of ~100 ms.

### Registration outcome — 50 of 50

```
14:18:14.013 INFO  BotGroupService   - Registering 50 users with prefix 'tptxg2' and password '123123'
14:18:14.013 INFO  ApiGatewayClient  - Starting parallel user registration: 50 users with prefix 'tptxg2' (parallelism=10)
14:18:14.469 INFO  ApiGatewayClient  - Parallel user registration completed. Success: 50, Failures: 0
```

- `Successfully registered user N/50` lines: **50**
- Distinct usernames: **50** (`tptxg21` … `tptxg250`)
- `Failures: 0`; no `failed to register` / partial-registration WARN
- All upstream `POST /gwms/v1/bot/register.aspx` calls returned `HTTP 200` with
  `{"status":"OK","code":200,...,"message":"Register successful"}`, each carrying a
  fresh `session_id`, `token`, `token2` and `"level":"LEVEL0"`
- All accounts registered with `main_balance: 0` — **funding is still required**

**One non-blocking WARN — a display name, not an account:**

```
14:18:14.440 WARN ApiGatewayClient - Failed to set display name for tptxg246:
  Failed to set display name: Tên hiển thị đã được sử dụng (status: EXISTED)
```

The randomly-drawn display name for `tptxg246` was already taken upstream. The
**account itself registered fine** (`Successfully registered user 46/50: tptxg246`);
only the cosmetic `update-fullname` call failed, so that bot keeps its default
display name. This is a name-pool collision, not a registration failure, and does
not meet the "<50 registered" abort condition. No retry attempted.

### Created group — post-conditions

| Post-condition | Expected | Actual | Result |
|---|---|---|---|
| `botCount` | 50 | 50 | PASS |
| `autoDepositEnabled` | false | false | PASS |
| `coordinationEnabled` | true | true | PASS |
| `maxAggregateStakePerRound` | 150000 | 150000 | PASS |
| `targetStatus` | null / absent | **absent** (`/status` → `targetStatus: null`) | PASS |
| `actualStatus` | STOPPED | STOPPED | PASS |
| `minBet` / `maxBet` / `betIncrement` | 1000 / 5000 / 1000 | 1000 / 5000 / 1000 | PASS |
| `minBetsPerRound` / `maxBetsPerRound` | 0 / 6 | 0 / 6 | PASS |
| `maxTotalBetPerRound` | 30000 | 30000 | PASS |
| `crowdAwareCoordination` / `rampEnabled` / `affinityWeightedProposal` / `chatEnabled` | all false | all false | PASS |
| `strategyMix` | `[RANDOM 1.0]` | `[RANDOM 1.0]` | PASS |
| `gameId` / `environmentId` | Tai Xiu / TIP Production | as posted | PASS |
| Bots instantiated | none | `totalBots: 0`, `connectedBots: 0`, `deadBots: 0` | PASS |

**The group was not started, and no bots exist for it in memory.**

### Live groups undisturbed by the fan-out

The 10-way concurrent registration hit the same auth gateway the running bots use.
Before (14:17:55Z) and after (14:20Z):

| Group | connected (pre → post) | dead | disconnected | bets (pre → post) |
|---|---|---|---|---|
| Fruit Shop `e24b1f7b` | 5/5 → **5/5** | 0 | 0 | 398 → **444** |
| Xoc Dia `0241ac89` | 5/5 → **5/5** | 0 | 0 | 142 → **147** |
| Bau Cua `6ab3b80b` | 5/5 → **5/5** | 0 | 0 | 240 → **250** |

No bot dropped, and all three kept betting straight through the fan-out. **PASS.**

### Password decision — `123123`, deliberate and user-confirmed

`123123` is a **deliberate choice made by the user**, not a placeholder or an
oversight, and it matches the three existing production groups (`tptxg1`, `tpfsg1`,
`tpxdg1`, `tpbcg1` all use `123123`).

User's stated rationale: **bot authentication requires a token that is only valid
from the same VPS, validated at the TCP level.** Possession of the password alone is
therefore not sufficient to authenticate as one of these accounts from anywhere
else — the credential is bound to the originating host, so password entropy is not
the control that protects these accounts. Recorded here so it is not "corrected" by
a later reviewer.

### Final prod group inventory (7 groups)

| Name | prefix | botCount | targetStatus | autoDeposit |
|---|---|---|---|---|
| **TIP Prod - Tai Xiu L0 base** (new) | `tptxg2` | 50 | *null (STOPPED)* | false |
| TIP Prod - Tai Xiu | `tptxg1` | 5 | *null (STOPPED)* | false |
| TIP Prod - Fruit Shop | `tpfsg1` | 5 | ACTIVE | false |
| TIP Prod - Xoc Dia | `tpxdg1` | 5 | ACTIVE | false |
| TIP Prod - Bau Cua | `tpbcg1` | 5 | ACTIVE | false |
| TIP Prod - Tai Xiu autodeposit test | `txprodbot` | 3 | STOPPED | **false (changed in Op 1)** |
| TIP Prod - Xoc Dia test | `bottest0` | 9 | STOPPED | false |

### Verdict — both operations

**PASS.** No abort condition was met. Nothing was started, deleted or retried.

### Next step (user's, not automated)

The 50 accounts registered with `main_balance: 0`. **Funding — 5,000,000 per
account × 50 accounts, performed outside the application — must happen before the
group is started.** Auto-deposit is off by design, so the bots cannot top themselves
up; an unfunded start would produce 50 bots that connect and never place a bet.

## Operation 3 — fund the 50 new accounts (250,000,000 real money)

Authorized by the user. Executed 14:24–14:33 UTC on **Prod-Bot**. No container,
compose or `.env` change; no group started. `Bot-1` not contacted.

### Mechanism (reproduced from application code, not improvised)

No REST endpoint exists for this, so `ApiGatewayClient.deposit(username, amount)`
was reproduced by hand:

```
POST https://getquazations.tidclupgws.com/gwms/v1/bot/deposit.aspx
  Content-Type: application/json
  User-Agent: PostmanRuntime/7.15.2
  X-TOKEN: 58bc2820612d23c34fe43d0b2c6f7223
  {"username":"<name>","amount":5000000}
```

Parameters resolved **from source, then cross-checked against the live registration
logs** rather than taken on trust:

| Parameter | Value | Source |
|---|---|---|
| endpoint | `/gwms/v1/bot/deposit.aspx` | `ApiGatewayClient:52` `BOT_DEPOSIT_ENDPOINT` |
| gateway | `https://getquazations.tidclupgws.com` | env `78b6eefb…` `apiGatewayUrl` |
| `X-TOKEN` | `58bc2820612d23c34fe43d0b2c6f7223` | `AuthStrategyFactory` `case P_116` AuthProfile; identical to the token observed in the successful register / update-fullname calls |
| amount | `5000000` **passed explicitly in the body** | per instruction — `BOT_DEPOSIT_AMOUNT` deliberately not relied on, since this bypasses the app |

This is the **gwms game-spendable partition**, not the legacy gamems agency-transfer
path (which credits a partition `verifytoken.aspx` reports but the game engine never
debits — the P_097/BOM "balance visible but bets rejected" symptom).

### Balance read path

Balances were read exactly as `ApiGatewayClient.getBalance` does
(`GET /gwms/v1/verifytoken.aspx?token=<sessionId>&fg=<fingerprint>` → `data[0].main_balance`).
The 50 `(sessionId, fingerprint)` pairs came from the registration log lines emitted
during Operation 2. All reads were issued **from Prod-Bot itself**, since these
tokens are VPS-bound and TCP-validated. Extraction was verified to yield exactly 50
distinct usernames spanning `tptxg21`…`tptxg250` with no strays.

### Pre-check — PASS

All 50 accounts read `main_balance: 0`:

```
BALANCE DISTRIBUTION (PRE):  50 × 0
accounts non-zero:           (none)
```

No account was pre-funded, so no risk of double-crediting on entry.

### Canary — PASS

`tptxg21` funded **alone**, one call:

```
POST …/bot/deposit.aspx {"username":"tptxg21","amount":5000000}
→ HTTP 200, 0.035 s
  {"status":"OK","code":200,"message":"Nạp tiền thành công"}   ("deposit successful")
```

Balance re-read immediately:

```
main_balance = 5000000      username = tptxg21
wallet_101   = 0            wallet_102 = 0
```

Exactly `5000000`, on the correct account. Only then were the remaining 49 funded.

### Bulk funding — 49 accounts, sequential

One call per username, strictly sequential (0.3 s apart), each logging username,
curl exit, HTTP status and body. The loop was written to **halt immediately** on any
non-zero curl exit or non-200 status. It did not halt.

```
FUNDED_THIS_RUN=49    SCRIPT_EXIT=0
http=200 responses:   49
non-200 / aborts:     (none)
```

**No retry was ever issued**, and no call was repeated: a duplicate check over the
funding log found **no username called more than once**, and no username outside
`tptxg21`…`tptxg250` was touched — in particular `tptxg11`…`tptxg15` (the separate
`dee8fabd` group) and the three live betting groups were never contacted.

Total deposit calls: **50** (1 canary + 49 loop) for 50 accounts — exactly one each.

### Post-check — PASS

```
BALANCE DISTRIBUTION (POST):  50 × 5000000
accounts != 5000000:          (none)
TOTAL:                        250000000
account count:                50
```

| Username | Balance before | Deposit HTTP | Balance after |
|---|---|---|---|
| `tptxg21` | 0 | 200 (canary) | 5,000,000 |
| `tptxg22` – `tptxg210` | 0 | 200 | 5,000,000 |
| `tptxg211` – `tptxg220` | 0 | 200 | 5,000,000 |
| `tptxg221` – `tptxg230` | 0 | 200 | 5,000,000 |
| `tptxg231` – `tptxg240` | 0 | 200 | 5,000,000 |
| `tptxg241` – `tptxg250` | 0 | 200 | 5,000,000 |

All 50 rows are identical (`0 → 200 → 5,000,000`); collapsed above for readability.
**Zero deviations.** Total credited: **250,000,000** — exactly 50 × 5,000,000, with
no over- or under-credit.

### Post-conditions

| Check | Result |
|---|---|
| Group `ef3b61af…` still `targetStatus: null` / `actualStatus: STOPPED` | PASS |
| No bots instantiated (`totalBots: 0`) | PASS |
| `autoDepositEnabled` still `false` on every group | PASS |
| Live groups undisturbed | PASS — Fruit Shop 5/5 (bets 763), Xoc Dia 5/5 (236), Bau Cua 5/5 (480), 0 dead |

### Hygiene

The temporary files holding the 50 session tokens/fingerprints and the admin
`X-TOKEN` (`/tmp/tptxg2_creds.txt`, `/tmp/fund.sh`, `/tmp/read_balances.sh`) were
deleted from Prod-Bot after the run. The retained evidence files
(`/tmp/bal_pre.txt`, `/tmp/bal_post.txt`, `/tmp/fund_log.txt`) contain usernames,
statuses and balances only — verified to hold no admin token.

### Verdict — Operation 3

**PASS.** 50 of 50 accounts funded, each with exactly one non-idempotent call, each
reading exactly `5,000,000`, totalling exactly `250,000,000`. No deviations, no
retries, no ambiguous calls, nothing started.

**The group `ef3b61af-12af-4791-b94c-ff521a04cfd5` is now funded and ready to start
on the user's command.** Starting it remains explicitly out of scope for the
releaser.

## Operation 4 — start the funded group — **BLOCKED, did not run**

Attempted 14:33:10 UTC. **The group never started, so none of the six observations
(bring-up, denomination validation, settlement, pruning, coordinator, burn rate)
could be made.** Reporting the blocker instead of the observations.

### What happened

```bash
POST http://localhost:8080/api/v1/bot-group/ef3b61af-12af-4791-b94c-ff521a04cfd5/start
→ HTTP 500  {"type":"Internal error","msg":"Internal server error — see server logs"}
```

Root cause, from the container log:

```
14:33:10.864 ERROR BotGroupBehaviorService - Failed to start bot group TIP Prod - Tai Xiu L0 base:
java.lang.IllegalStateException: Game c0a54255-b73d-485e-ba00-4418b378a8b9 (Tai Xiu)
  has neither optionAffinities nor legacy numberOfOptions set
    at Game.getEffectiveOptionAffinities(Game.java:267)
    at BotGroupBehaviorService.startLocked(BotGroupBehaviorService.java:348)
    at BotGroupBehaviorService.start(BotGroupBehaviorService.java:254)
    at BotGroupController.start(BotGroupController.java:146)
```

**This is a `Game`-record data gap, not a bot-group problem and not a regression from
this release.** The bot group, its 50 accounts and their funding are all correct.

### The gap, in context

`Game.getEffectiveOptionAffinities()` accepts **any one** of three sources, in order:
`optionAffinities` → `bettingOptions` → `numberOfOptions`. The prod Tai Xiu record
has **all three unset**, so it falls through to the throw. Compared against the three
games that do work on this box:

| Game | type | `optionAffinities` | `numberOfOptions` | `offset` | starts? |
|---|---|---|---|---|---|
| **Tai Xiu** `c0a54255` | TAI_XIU | **null** | **null** | null | **NO** |
| Fruit Shop `9e8dd86e` | BETTING_MINI | `{0:1,1:1,2:1,3:1,4:1,5:1}` | null | 6000 | yes |
| Xoc Dia `e3fb0020` | BETTING_MINI | `{0:1,1:1,2:1,3:1,4:1,5:1}` | null | 0 | yes |
| Bau Cua `928ba36f` | BETTING_MINI | `{0:1,1:1,2:1,3:1,4:1,5:1}` | null | 5000 | yes |

The Tai Xiu record is effectively a stub — `name`, `gameType` and `pluginName` are
set, the betting configuration is not.

**The null `offset` is a red herring — do not "fix" it.** Per `TaiXiuGameBot`
(AD-9, `:47`), the Tai Xiu bot *never* reads `game.getOffset()`; its CMDs are
provider-resolved (`cmdOffset()`), unlike BETTING_MINI. A null offset is correct for
TAI_XIU. The missing option config is the sole blocker.

### Why it fired: the coordinator needs the option set

The throwing call is **gated on `coordinationEnabled`**
(`BotGroupBehaviorService:338`): a coordinator is built only for a
`coordinationEnabled` group on a BETTING_MINI/TAI_XIU game, and `BetCoordinator`'s
first constructor argument is `game.getEffectiveOptionAffinities()` — it needs the
per-option budget split. This group was created with `coordinationEnabled: true` and
`maxAggregateStakePerRound: 150000`, exactly as specified.

This also explains a standing oddity from the Task 1 inventory: the pre-existing
`dee8fabd` "TIP Prod - Tai Xiu" group has **never been started** (`targetStatus`
null). It is `coordinationEnabled: true` on the same Game record, so it would fail
identically. Tai Xiu has never run on this box because the game was never fully
configured — not by coincidence.

### State after the failed start — clean, nothing consumed

The failure was atomic; the group was left exactly as it was:

| Check | Value |
|---|---|
| `targetStatus` | `null` (unchanged — the manual-override wrapper did not persist a status) |
| `actualStatus` | `STOPPED` |
| `totalBots` / `connectedBots` / `deadBots` | 0 / 0 / 0 |
| `lastStartedAt` | `null` |
| `lastFailureReason` | `null` |

No bot was instantiated, no WS connection opened, no account touched, **no money
moved**. The 50 accounts remain funded at 5,000,000 each. The group is fully intact
and will start as-is the moment the Game record is completed.

### Collateral — unaffected

All three live groups were untouched by the failed start, throughout:

| Group | connected | dead | bets (14:33:10 → 14:36) |
|---|---|---|---|
| Fruit Shop `e24b1f7b` | 5/5 | 0 | 916 → 964 |
| Bau Cua `6ab3b80b` | 5/5 | 0 | 560 → 600 |
| Xoc Dia `0241ac89` | 5/5 | 0 | 306 → 314 |

Container `Up 28 minutes (healthy)`. No ERROR flood — the two ERROR lines logged were
the single handled `IllegalStateException` and its `RestExceptionHandler` echo.

`POST /stop` was **not** issued: no stop condition was met, and there was nothing
running to stop.

### Verdict on the two decisive questions

Both are **UNDETERMINED — not answered, not partially answered.** No bet was ever
sent to prod Tai Xiu, and no bot ever connected.

1. **Does prod Tai Xiu accept the 1000-step grid?** Unknown. Zero bets sent.
2. **Does 50-on-one-channel hold without pruning?** Unknown. Zero bots connected, so
   the ws-parser ping fix also remains untested at 50-bot scale.

Nothing about the layer plan can be concluded from this run.

### What unblocks it (user's decision — deliberately NOT done here)

Editing a `Game` record is out of scope for the releaser, so no change was made. Two
options, of which the first is the correct one:

1. **Populate the Tai Xiu Game's option config** (recommended) — `PATCH
   /api/v1/game/c0a54255-b73d-485e-ba00-4418b378a8b9` setting `optionAffinities` (or
   `bettingOptions` / `numberOfOptions`). The value is a **game-semantics decision the
   user should make, not one to guess**: Tai Xiu's bet carries Tài/Xỉu `eid`/`aid` via
   `TaiXiuRequest`, which suggests the natural option set is the Tài/Xỉu pair
   (`numberOfOptions: 2` → `{0:1, 1:1}`), but if the prod table also exposes
   additional bet positions the affinity map should reflect them. Fixing the Game
   also unblocks the existing `dee8fabd` group.
2. Set `coordinationEnabled: false` on the group — it would then start, since the
   throwing call is gated on that flag. **Not recommended:** it discards the
   coordinator and with it the `maxAggregateStakePerRound: 150000` cap, which is one
   of the things this run was meant to validate.

Once the Game is completed, the group is funded and ready; the observation run can be
retried unchanged.

## Operation 5 — PATCH the Tai Xiu Game option config

Executed 14:45:40 UTC. Unblocks Operation 4.

### Value provenance — materialized from the app's own default, not invented

Verified in source before touching prod data:
`Game.defaultTaiXiuOptionAffinities()` (`Game.java:200-205`) returns exactly
`{1:1, 2:1}`, documented as "Tai Xiu is intrinsically a 2-option game, so this
spares the operator from ever setting `numberOfOptions` on it". Eids `1`/`2` are the
Tài/Xỉu pair. The PATCH writes the application's own intrinsic constant.

```bash
curl -fsS -X PATCH http://localhost:8080/api/v1/game/c0a54255-b73d-485e-ba00-4418b378a8b9 \
  -H 'Content-Type: application/json' -d '{"optionAffinities": {"1": 1, "2": 1}}'
→ HTTP 200
```

Full before/after diff of the Game record — **exactly two lines changed**:

```diff
-     "updatedAt": "2026-08-06T10:48:53.046Z",
+     "updatedAt": "2026-08-11T14:45:40.032Z",
+     "optionAffinities": { "1": 1, "2": 1 },
```

Untouched and verified: `offset` still **absent/null** (correct — `TaiXiuGameBot`
AD-9 never reads it, CMDs are provider-resolved), plus `gameType: TAI_XIU`,
`pluginName: taixiuPlugin`, `gameId: 1`, `md5: false`, `crowdCountSemantic: UNKNOWN`,
`jackpotScaleEnabled`, `jackpotCeiling`, `brandCode`, `productCode`, `environmentId`,
`createdAt`, `name`.

### Product bug recorded (not a data-entry oversight)

`Game.applyTaiXiuOptionDefaults()` exists and would have supplied `{1:1, 2:1}`
automatically — but its javadoc states it is "**Only applied on the Tai Xiu bot
path** (see `TaiXiuGameBot.initializeSubclass`)". `BotGroupBehaviorService:347-348`
constructs `BetCoordinator` from `game.getEffectiveOptionAffinities()` inside
`startLocked`, **before any bot is constructed**. So the default never gets a chance
to run.

**Consequence:** a `coordinationEnabled` Tai Xiu group on an unconfigured Game
*always* throws, while an uncoordinated one starts fine. This is an **ordering
defect in the product**, not bad data. Suggested fix: call
`applyTaiXiuOptionDefaults()` (or fall back to the Tai Xiu default inside
`getEffectiveOptionAffinities()` when `gameType == TAI_XIU`) before the coordinator
is built. The Game record is now populated, so the defect is masked on this box —
but it will recur on any new Tai Xiu environment.

The Game record is shared with `dee8fabd` (5-account Tai Xiu group), which is
STOPPED with 0 bots — no runtime effect, and it is now unblocked too. **It was not
started.**

---

## Operation 4 (retry) — start the 50-bot group and observe

Started 14:45:58 UTC, `POST /start` → **HTTP 200**. Observed to 15:00 UTC
(~14 min, **9 rounds entered / 8 settled**).

### Coordinator proof — the PATCH took effect on the exact failing path

```
14:45:58.283 INFO BotGroupBehaviorService -
  Bet coordinator created for group TIP Prod - Tai Xiu L0 base (2 options, aggregate cap 150000)
```

Health confirms the coordinator carries `options: [1, 2]`, `cap 150000`.

### 1. Bot bring-up — 50/50

| Metric | Value |
|---|---|
| Authenticated | **50 of 50** (`CONNECTION_AUTHENTICATED`, 50 distinct usernames) |
| Connected | **50/50**, sustained for the entire window |
| Dead / disconnected / reconnecting | **0 / 0 / 0** at every 30 s sample |
| Bring-up time | ~5 s for all 5 waves (`bot.creation.parallelism=10`) |

Three transient `WS disconnected — starting retrial flow` WARNs at 14:45:59
(`tptxg220`, `tptxg245`, `tptxg242`) during the connect storm; all three recovered
immediately and the group was 50/50 by the first sample at 14:46:54.

**AUTH-race check (first 50-bot test of the ws-parser ping fix): PASS.** Only **3**
`Cannot send message, not connected` lines total, matching the 3 transient
disconnects — not the historic pattern of a persistent per-bot flood. No bot was
dropped ~30 s after handshake. **Zero watchdog expiries** (`no game message in 180s`)
for this group across the whole window, versus Bau Cua's ~230/bot/24 h at 5 bots.
**Zero ERROR lines** for the group.

### 2. Denomination — sent perfectly on grid; server acceptance NOT confirmed

**Send side — PASS.** Every `SessionAggregationService` UpdateBet summary shows
options restricted to the two configured eids and amounts inside the configured
band, `min 1000 / max 5000`, with `betIncrement 1000`:

```
14:46:43 UpdateBet #2 bettors=49 staked=150000 options: [1]x24 [2]x26 | min/avg/max: 1000/2940/5000
14:47:48 UpdateBet #1 bettors=48 staked=150000 options: [1]x23 [2]x25 | min/avg/max: 1000/3125/5000
14:48:58 UpdateBet #1 bettors=50 staked=150000 options: [1]x31 [2]x26 | min/avg/max: 1000/2631/5000
14:51:13 UpdateBet #1 bettors=50 staked=150000 options: [1]x30 [2]x27 | min/avg/max: 1000/2631/5000
```

No option outside `{1,2}` and no amount outside `[1000, 5000]` was ever emitted.
(Non-round *averages* are means of mixed on-grid values, not off-grid bets.)

**Accepted side — reads ZERO, but the signal is NOT Tai-Xiu-specific.** Two
independent server-side reads both show no effect:

1. `confirmed staked: 0` on **all 8** settled rounds (and `total win: 0`).
2. **Authoritative wallet read** — `verifytoken.aspx` `main_balance` for all 50
   accounts, queried directly from Prod-Bot after 318 bets / 1,050,000 locally
   staked: **50 × 5,000,000 = 250,000,000, entirely untouched.**

Before concluding "the grid is rejected", the incumbents were tested the same way —
and they behave **identically**:

| Group | Uptime | Bets/bot | Staked/bot (local) | Server `main_balance` |
|---|---|---|---|---|
| **Tai Xiu (new)** | 14 min | ~6 | ~21,000 | **5,000,000 (untouched)** |
| Fruit Shop `tpfsg1*` | ~27 h | ~12,000 | ~6,000,000 | **5,000,000 (untouched)** |

A Fruit Shop bot that locally staked **6,000,000** out of a 5,000,000 balance would
be bankrupt if debits were landing. It reads exactly 5,000,000. So the zero-effect
signal is **fleet-wide and pre-existing**, not something the 1000-step Tai Xiu grid
caused.

Note `TipEndGameMessage` and `TaiXiuEndGameMessage` **both implement `HasBetTotals`**,
so `confirmed staked: 0` is not a missing-interface gap — either the server genuinely
records no stake for these accounts, or the field mapping is wrong, or
`verifytoken.aspx` reports a different wallet partition from the one the game engine
debits (the documented P_097/BOM confusion). **This cannot be resolved from the bot
side** — it needs the game's own ledger / back office, per the standing rule that
`payout=0` is not proof of rejection.

### 3. Settlement — Tai Xiu settles CLEANLY (unlike Xoc Dia)

**9 rounds entered, 8 ended**, one in flight — a clean 1:1 pairing, and
`roundsSinceRestart` advanced to **9**. This is the opposite of Xoc Dia on the same
box (522 entered / **0** ended, `roundsSinceRestart` stuck at 0). Round cadence is a
steady **~68 s**.

The only settlement noise was one burst of `BotMemory.completeRound: sessionId
mismatch (EndGame sessionId=0, in-flight sessionId=0)` at 14:46:19 — an EndGame for
a round the bots joined mid-flight, before their first StartGame at 14:46:38.
Start-up transient; did not recur.

### 4. Subscriber pruning at 50-on-one-channel — NO PRUNING

Distinct bettors per settled round:

| Session | 1004863 | 1004864 | 1004865 | 1004866 | 1004867 | 1004868 | 1004869 | 1004870 |
|---|---|---|---|---|---|---|---|---|
| bettors | 49 | 48 | **50** | 49 | **50** | **50** | 47 | 47 |

The count oscillates in a 47–50 band with **no monotonic decay**, repeatedly
returning to the full 50 — the signature of ordinary per-round strategy/coordinator
variation, not eviction. Had the ~10-subscriber limit suspected in CLAUDE.md applied,
the count would have collapsed toward ~10 within 1–2 rounds and stayed there.
`connectedBots` held **50/50** at all 13 samples, with 0 disconnected, 0
reconnecting, and **0 watchdog expiries**.

**50 bots on one game channel held cleanly for the full window.**

### 5. Coordinator — the cap is hard-binding

`maxAggregateStakePerRound=150000` is the **active constraint every single round**:
all 8 settled rounds staked **exactly 150,000**. Counters at 14:52:54:

```
approveCount=311   trimCount=7   rejectCount=1482   currentAggregateStake=150000
```

Rejections outnumber approvals ~4.8:1 — the fleet's demand far exceeds the cap, so
the coordinator fills the budget early each round and refuses the rest. Round
1004866 shows the trim path explicitly: staked 139,000 at flush #1, then a partial
`[2]x4` top-up to land exactly on 150,000. Working as designed, but **the cap, not
strategy, is currently setting the stake.**

### 6. Burn rate

| Measure | Value |
|---|---|
| Aggregate stake per round | 150,000 (cap-bound) |
| Round cadence | ~68 s ⇒ ~53 rounds/h |
| Local burn | ~**7,950,000 / hour** |
| Local drawdown observed | 250,000,000 → 248,950,000 in 14 min |
| Avg local balance/bot | 4,973,000 |
| **Implied runway on 250,000,000** | **~31 hours** — *if* the debits were real |
| **Actual server drawdown** | **0** — wallet still at 250,000,000 |

The runway figure is therefore theoretical. On current evidence the bots are not
spending anything, so the funding is not being consumed.

### 7. Collateral — all three live groups untouched

| Group | connected | dead | disc | bets (14:45:58 → 15:00) |
|---|---|---|---|---|
| Fruit Shop `e24b1f7b` | 5/5 | 0 | 0 | 1,365 → **1,717** |
| Xoc Dia `0241ac89` | 5/5 | 0 | 0 | 437 → **518** |
| Bau Cua `6ab3b80b` | 5/5 | 0 | 0 | 800 → **960** |

All three kept betting throughout, unaffected by 50 extra bots hitting the same auth
gateway and game server. Container `Up 49 minutes (healthy)`. **No stop condition was
met; `/stop` was not issued and the group is still running.**

### Verdict on the two decisive questions

1. **Does prod Tai Xiu accept the 1000-step grid?**
   **Send side proven, accept side UNPROVEN — and the blocker is not Tai Xiu.**
   The bots emit only eids `{1,2}` at amounts strictly on the 1000 grid within
   `[1000, 5000]`, and the game accepts the frames, runs rounds and settles them.
   But server-confirmed stake is 0 and the wallet is undebited — **identically to
   Fruit Shop, which has run 27 h with 12,000 bets/bot and the same zero effect.**
   So there is **no evidence the 1000 grid is rejected**, and strong evidence of a
   **pre-existing, fleet-wide bet-acceptance or wallet-partition problem** that
   predates this group and must be settled against the game's own ledger.

2. **Does 50-on-one-channel hold without pruning?**
   **YES.** 50/50 connected for the entire window, 47–50 distinct bettors per round
   with no decay, 0 watchdog expiries, 0 dead, 0 ERROR, and only 3 transient
   start-up disconnects that self-healed. The ws-parser ping-before-AUTH fix held at
   50-bot scale on its first real test.

### Follow-ups raised by this run

1. **Fleet-wide zero bet-acceptance (highest priority).** Server wallet undebited and
   `confirmed staked: 0` for *every* group, including ones running 27 h. Either bets
   are not being accepted at all, or `verifytoken.aspx` reports a partition the game
   engine does not debit. Resolve against the game's own ledger before drawing any
   RTP/behaviour conclusion from this fleet.
2. **`applyTaiXiuOptionDefaults()` ordering defect** — see Operation 5; will recur on
   any new Tai Xiu environment.
3. **Coordinator cap is the binding constraint**, not strategy. If the intent is to
   observe strategy behaviour, `maxAggregateStakePerRound` needs raising; at 150,000
   the coordinator rejects ~83 % of proposals.
4. **Xoc Dia settlement gap unchanged** — Tai Xiu settling cleanly on the same box
   confirms it is Xoc Dia/offset-0-specific, not a platform-wide issue.

## Operation 6 — post-whitelist settlement check — **BLOCKED, could not observe**

Attempted 2026-08-12. Purely read-only; nothing was changed, and no PATCH, start,
stop or funding was attempted.

### Verdict: UNKNOWN — are bets settling? Could not be determined.

**Prod-Bot became unreachable from this workstation**, so none of the five checks
(confirmed stake per round, 50 server balances, incumbent-group balances, winnings,
RTP) could be run. No settlement conclusion is available — neither positive nor
negative.

### Evidence

| Probe | Result |
|---|---|
| `ssh Prod-Bot` (43.199.58.254:22000) | **Operation timed out** — 4 consecutive attempts |
| `nc -z 43.199.58.254 22000` | no connection (timeout, **not** refused) |
| `nc -z 43.199.58.254 22` | no connection (timeout) |
| ICMP `ping -c 3` | **100 % packet loss** |
| Control: `nc -z 1.1.1.1 443` | **succeeded** — local outbound connectivity is fine |

The failure is **not** on this workstation: unrelated outbound traffic works. Every
probe **times out** rather than being **refused**, and ICMP is fully dropped — the
signature of packets being silently discarded by a firewall / security group, rather
than a host that is up with a stopped `sshd` (which would refuse) or a service crash.

### Leading hypothesis (stated as hypothesis, not fact)

The loss of access coincides exactly with the IP-whitelisting change made for P_116
prod. A plausible reading is that the security-group / firewall edit that added the
back-office whitelist also altered inbound rules on 43.199.58.254, dropping SSH
(port 22000) from this source address. That is **suspicion based on timing plus the
drop-not-refuse signature — it is not verified**, and a simple host reboot or an
unrelated network change would look identical from outside.

Concrete checks for whoever has console/provider access:

1. Inbound security-group / firewall rules for 43.199.58.254 — is port **22000**
   still open to the operator source range, or was it narrowed during the whitelist
   change?
2. Is the instance running (provider console), and did it reboot?
3. From the console, is `bot-java-bot-manager-1` still up and is the Tai Xiu group
   still running?

### Operational note — this matters more than usual right now

The group `ef3b61af…` was **left running** at the end of Operation 4 (retry), by
design. If the whitelist did take effect and settlement is now real, the 50 accounts
have been drawing down at roughly **7.95 M/hour** against 250,000,000 — about **31 h
of runway** from 14:46 UTC on 2026-08-11, i.e. depletion around **22:00 UTC on
2026-08-12**. `autoDepositEnabled` is `false` by design, and per the standing
finding a drained bot does **not** stop cleanly (`BettingMiniGameBot.canBet()` never
consults balance), so it will keep emitting bets the server rejects with its local
balance going negative and no alert raised.

**This is unverified** — if settlement is still not working, nothing has been spent
at all. But it is the reason regaining access should not wait: the two scenarios
diverge sharply and only one of them is benign.

### State left behind

Nothing was changed in this operation. Last confirmed state (2026-08-11T15:00Z):
group `ef3b61af…` ACTIVE, 50/50 connected, 0 dead, coordinator cap-bound at 150,000
per round; the three incumbent groups 5/5 and betting; container healthy.
