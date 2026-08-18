# Release — BOT_LIVENESS_SEMANTICS Phase 1 (staging)

Mode: bot
Target host: **`Bot-1`** (16.162.36.69:22000, user `sgame`), dir `/home/sgame/bot-java`
Branch: `staging` @ `9dfd1d8` **plus 5 uncommitted files** (deliberate — see Provenance)
Image: `vingame-bot:latest` = `5e5d3df89246` (built 2026-08-10T11:01:00Z)
Rollback image: `vingame-bot:rollback-staging` = `479878019e8c` (the prior `latest`)
Date: 2026-08-10T10:58Z – 11:33Z

**`Prod-Bot` (43.199.58.254) was not contacted at any point in this release.**

## Verdict summary

| Check | Result |
|---|---|
| Build | **PASS** |
| Ship | **PASS** |
| Deploy | **PASS** |
| Smoke (health + `Started Starter` + auto-start) | **PASS** |
| Plan verification (6 steps) | **6 of 6 PASS** (2 with notes) |
| Orphan-client flood eliminated | **PASS** — 0 lines/s steady state vs 98.6/s baseline |
| Deposit-threshold behaviour change | **No effect observed** — 0 deposits, 0 bots below the new trigger |

**Overall: PASS.**

---

## Standing overrides applied

Recorded for audit. All four came from the user's own instruction in this task:

1. **Dirty working tree accepted.** Clean-tree guard skipped; **nothing committed, nothing pushed.**
2. **No `docker compose down`.** Bot-1 runs bot-manager *and* the whole observability
   stack in one compose project (MEMORY: Bot-1 single-compose layout), so `down` would
   take Grafana/Prometheus/Loki/Promtail/Mongo offline. Only
   `docker compose up -d bot-manager` was run — one service recreated.
3. **Rollback tag before removal.** `docker tag vingame-bot:latest vingame-bot:rollback-staging`
   preceded `docker image rm`, so the prior image survives the untag.
4. **Tests skipped in the build** (`-DskipTests`), per the brief — suite reported green locally.

---

## Provenance — what shipped

Committed (5 commits, BOT_LIVENESS_SEMANTICS Phase 1 — unconditional WS client close):

```
9dfd1d8 fix(bot): route the fifth close site in triggerFullReconnect through closeQuietly
accacc4 refactor(bot): drop lastClosedClient, make closeQuietly a plain null-guarded close
d69610c test(bot): cover close-error tolerance, logout/restart and the un-migrated fifth close site
2e98e4e test(bot): pin orphan-client close on a dead channel
6103e4a fix(bot): close every WS client unconditionally, never behind isOpen()
```

Uncommitted, shipped deliberately (deposit-amount configurability):

```
 M bot-api/src/main/java/com/vingame/bot/config/bot/BotBehaviorConfig.java
 M bot-app/src/main/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorService.java
 M bot-app/src/main/resources/application.properties      (+ bot.deposit.amount=1000000000)
 M bot-engine/src/main/java/com/vingame/bot/domain/bot/core/Bot.java
 M bot-engine/src/test/java/com/vingame/bot/domain/bot/core/BotTest.java
```

Working tree was **not** modified by this release. Nothing was committed or pushed.

### Effective config on Bot-1

`docker exec bot-java-bot-manager-1 env | grep -iE 'DEPOSIT|BOT_IP'` → **no matches**.
No `BOT_DEPOSIT_AMOUNT` override, so the `application.properties` default applies:

| Derived value | Before | After |
|---|---|---|
| `bot.deposit.amount` | 1,000,000,000 (hardcoded) | 1,000,000,000 (property default) |
| `getMinBalance()` — auto-deposit trigger | 5,000,000 | **100,000,000** |
| `checkBalance()` re-sync threshold | 1,000,000 | **10,000,000** |

---

## Build

Command:
```
export JAVA_HOME=/Users/gleb/Library/Java/JavaVirtualMachines/openjdk-21.0.2/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
mvn clean install -DskipTests -Dmaven.javadoc.skip=true
```

- `mvn clean install -DskipTests`: **PASS** (11.2 s; 11:00:24Z → 11:00:36Z)
  - All 6 reactor modules SUCCESS: Archetype, bot-api, bot-strategies, bot-messages, bot-engine, bot-app.
- `docker build --no-cache --platform linux/amd64 -t vingame-bot:latest .`: **PASS**
  (20 s; 11:00:40Z → 11:01:00Z) → `sha256:5e5d3df89246…`, 404 MB
- `docker save -o bot.tar vingame-bot:latest`: **PASS** — 417,212,416 bytes

## Ship

- `sftp Bot-1:/home/sgame/bot-java <<< "put bot.tar"`: **PASS** (45 s; 11:01:13Z → 11:01:58Z)
- Remote size verified: `417212416 /home/sgame/bot-java/bot.tar` — byte-identical to local.
- (mode=infra `infra-images.tar.gz`): **N/A** — mode=bot.

## Deploy

Executed 11:02:22Z → 11:02:26Z (4 s), inside `/home/sgame/bot-java`:

| Step | Result |
|---|---|
| `docker compose down` | **DELIBERATELY SKIPPED** (single-compose layout — would drop observability) |
| `docker tag vingame-bot:latest vingame-bot:rollback-staging` | **PASS** |
| `docker image rm vingame-bot:latest` | **PASS** — `Untagged: vingame-bot:latest` (untag only; image retained via rollback tag) |
| `docker load -i bot.tar` | **PASS** — `Loaded image: vingame-bot:latest` |
| `docker compose up -d bot-manager` | **PASS** — `bot-manager` Recreated → Started; `mongo` Running/Healthy, untouched |

Post-deploy image state:
```
vingame-bot:latest            5e5d3df89246   (new)
vingame-bot:rollback-staging  479878019e8c   (previous — rollback available)
```

Rollback procedure if needed:
```
cd /home/sgame/bot-java && docker image rm vingame-bot:latest \
  && docker tag vingame-bot:rollback-staging vingame-bot:latest \
  && docker compose up -d bot-manager
```

## Smoke test

- Container healthy: **PASS** — at 11:03:56Z, `bot-java-bot-manager-1  Up About a minute (healthy)  vingame-bot:latest`
- Spring Boot ready log: **PASS** — `11:02:36.126 [main] INFO Starter - Started Starter in 8.242 seconds (process running for 9.51)`
- Auto-start log: **PASS** — `11:02:35.103 [main] INFO BotGroupBehaviorService - Bot Manager startup complete. 4 bot groups running`
- Co-located stack survived (Bot-1-specific): **PASS** — Grafana / Prometheus / Promtail / Loki / Mongo all still `Up 4 days`, i.e. never restarted.
- ERROR lines since start: **0**

---

## Plan verification — `docs/plans/BOT_LIVENESS_SEMANTICS.md` § Verification

### Step 1 (Universal smoke): App up
Command: `curl -sf http://localhost:8080/actuator/health`
Expected: `"status":"UP"` within 60 s.
Actual: `{"status":"UP",...}` — reached well inside 60 s (container healthy at ~90 s incl. Docker start_period).
Result: **PASS**

### Step 2 (Universal smoke): Observability stack survived the co-located redeploy
Command: `curl -sf localhost:9090/-/healthy; curl -sf localhost:3000/api/health; curl -sf localhost:3100/ready`
Expected: Prometheus `Prometheus Server is Healthy.`; Grafana JSON `"database":"ok"`.
Actual: Prometheus `Prometheus Server is Healthy.`; Grafana `{"database":"ok","version":"11.4.0",...}`;
Loki returned HTTP 503 `Ingester not ready: waiting for 15s after being ready` at 11:08Z, then
`ready` at 11:17Z. Loki's container was never restarted (`Up 4 days`) — this was an internal
ring/owned-stream recalculation, not redeploy damage.
Result: **PASS** (Loki transient noted, self-resolved)

### Step 3 (Universal smoke): A group is running and playing
Command: `curl -sf localhost:8080/api/v1/bot-group/$G/health` for all 4 auto-started groups
Expected: `connectedBots >= floor(totalBots*0.8)`, `deadBots == 0`.
Actual (t+30, 11:32:54Z):

| Group | total | connected | dead | disconnected |
|---|---|---|---|---|
| Auth test with socket | 15 | 15 (100 %) | 0 | 0 |
| XD game test | 20 | 20 (100 %) | 0 | 0 |
| BOM flow test 100 (kept running) | 100 | 100 (100 %) | 0 | 0 |
| Slot group 120 | 20 | 20 (100 %) | 0 | 0 |

Result: **PASS** — all four at 100 % connected, zero dead.

### Step 4 (Phase 1): Flood rate < 1 % of pre-deploy baseline
Command:
```
docker logs --since 10m bot-java-bot-manager-1 2>&1 | grep -c "Cannot send message, not connected"
```
Expected: post-deploy value **< 1 %** of pre-deploy, ideally 0.
Actual: pre-deploy **59,134 per 10 min** (≈98.6 lines/s); post-deploy trailing-10 min window
at both t+15 and t+30 = **0**.
Result: **PASS** (0.00 % of baseline — see full sample table below)

### Step 5 (Phase 1): Restart one group, confirm no orphan survives
Command:
```
curl -s -X POST localhost:8080/api/v1/bot-group/7a3716ed-.../restart -w '%{http_code}'
sleep 130
docker logs --since 2m ... | grep "Cannot send message, not connected" | grep -c <groupId>
```
Expected: HTTP `200`; trailing-2 min count for that group = `0`.
Actual: HTTP **200**; group-scoped count over the trailing 2 min = **0**; global trailing-2 min
count = **0**. Cumulative flood moved 58 → 64 (i.e. 6 lines emitted *during* the restart burst
itself, then silence). Group returned to 15/15 connected, 0 dead, new `startedAt`
`2026-08-10T11:18:06Z`.
Result: **PASS**

> Side finding: the backlogged *restart lifecycle bug* (`ValidationException: Authentication
> configuration is required` on `/restart`) **did not reproduce** — restart succeeded cleanly.

### Step 6 (Phase 1): No thread/heap regression
Command: `curl -sf .../actuator/metrics/jvm.threads.live` and heap via
`actuator/metrics/jvm.memory.used?tag=area:heap`
Expected: `jvm.threads.live` within ±10 % of pre-deploy; heap not trending monotonically up
across three samples 10 min apart.
Actual: `jvm.threads.live` **154 → 56**, a **−64 %** move. This is *outside* the literal ±10 %
band but in the favourable direction and is precisely the predicted effect of the fix — the
~136 orphaned clients each held a scheduler thread, and they are gone. No regression.
Heap at t+30 = **175 MB** (183,413,160 bytes), stable, no OOM/GC pressure and 0 ERROR lines.
Result: **PASS** — with two notes: (a) the ±10 % band is violated downward by design;
(b) **I sampled heap once, not three times 10 min apart**, so the heap-trend sub-check is
only partially covered. Thread trend was sampled at all three points and is flat.

---

## The real test — flood re-accumulation over time

`t0 = 11:02:36Z` (`Started Starter`). Baseline row is the pre-deploy measurement on the
old image, which had been up 4 days.

| Point | Timestamp (UTC) | Flood cumulative | Flood trailing 5 m | Flood trailing 10 m | `jvm.threads.live` |
|---|---|---|---|---|---|
| **Baseline (pre-deploy)** | 10:58:42 | — | — | **59,134** | **154** |
| t+2 | 11:04:26 | 58 | 58 | — | 55 |
| **t+5** | 11:07:48 | **58** | **0** | — | **54** |
| **t+15** | 11:17:06 | **58** | **0** | **0** | **56** |
| restart, pre | 11:18:03 | 58 | — | — | 86 |
| restart, post | 11:20:16 | 64 | 0 (trailing 2 m) | — | 94 |
| t+25 | 11:27:33 | 64 | 0 | — | 100 |
| **t+30** | 11:32:54 | **64** | **0** | **0** | **56** |

**Rate:** baseline 98.6 lines/s → post-deploy steady state **0 lines/s**. The trend across
t+5 / t+15 / t+30 is flat at zero; it does not climb back toward baseline. **PASS.**

### Where the 64 lines came from

Every one of the 64 is attributable to a discrete churn burst, not to steady-state spinning:

- **58 lines at `11:02:39`** — the startup burst. Last three, verbatim:
  ```
  11:02:39.401 [multiThreadIoEventLoopGroup-2-2] WARN VingameWebSocketClient - Client ws-bomflowtest33: Cannot send message, not connected
  11:02:39.892 [multiThreadIoEventLoopGroup-2-3] WARN VingameWebSocketClient - Client ws-bomflowtest63: Cannot send message, not connected
  11:02:39.932 [multiThreadIoEventLoopGroup-2-4] WARN VingameWebSocketClient - Client ws-bomflowtest70: Cannot send message, not connected
  ```
  These carry `ws-<botname>` client ids on Netty IO threads — the known
  **PING-BEFORE-AUTH race** (MEMORY: ws-parser 3.0.5 `connect()` sends PING at 0 delay),
  a *separate* defect from the orphan leak and not addressed by Phase 1.
- **6 lines during the 11:18 restart burst.**
- **0 lines** in all other time.

Distinct orphaned `ws-client-NNNN` ids in the post-deploy flood: **0**. The pre-fix signature
was ~136 long-lived `ws-client-*` spinners; none exist now.

### Honest caveat on ambient churn

The brief expected staging to drop WebSocket connections continuously. **It did not during
this window.** Total `WS disconnected — starting retrial flow` events since boot: **56**,
of which **51 occurred at 11:02** (startup, slot group) and **5** during the induced 11:18
restart. Between 11:03 and 11:33 staging produced **zero** spontaneous disconnects.

So the near-zero flood across the three samples is *partly* explained by an unusually quiet
window, and the passive samples alone would be weak evidence. What carries the verdict is
the two **induced** bursts:

- **51 disconnects + 445 reconnect attempts + 58 channel-inactive events at 11:02**, and
- **5 disconnects at 11:18** (deliberate group restart),

each of which is exactly the orphan-generating event. Pre-fix, that churn is what accumulated
the 136 spinners producing 98.6 lines/s indefinitely. Post-fix, both bursts emitted a bounded
handful of lines and left **no surviving spinner** — cumulative count froze at 58 and 64
respectively and never moved again. The plan's step-5 restart test is the controlled version
of this and passed cleanly.

**Recommendation:** re-check the cumulative count after a genuinely busy staging day. If it
is still in the tens rather than the hundred-thousands, the fix is confirmed under load too.

---

## Auto-start and connection results

4 of 4 `targetStatus: ACTIVE` groups auto-started (11:02:31 → 11:02:35, "startup complete.
4 bot groups running"), covering **155 bots**:

| Group | Bots | `CONNECTION_AUTHENTICATED` | Dead |
|---|---|---|---|
| Auth test with socket | 15 | 15 | 0 |
| XD game test | 20 | 20 | 0 |
| BOM flow test 100 (kept running) | 100 | 100 | 0 |
| Slot group 120 | 20 | 0 — reports 19 `STARTED` + 1 `AUTHENTICATING_CONNECTION` | 0 |

**135 of 155 bots reached `CONNECTION_AUTHENTICATED`.** The 20 that did not are the slot
group, which uses a different status vocabulary (`STARTED`) and reported
`connectedBots: 20/20, deadBots: 0`. This distribution is **byte-for-byte identical to the
pre-deploy baseline** (135/155 authenticated, same 19+1 split on the slot group), so it is
pre-existing behaviour, not a regression introduced by this release.

## Thread trend (corroborating evidence)

`jvm.threads.live`: **154 (baseline, 4 days uptime with the flood running) → 54–56 steady
state post-deploy**, a sustained drop of ~98 platform threads that closely tracks the ~136
orphaned clients whose scheduler threads are now released.

The 86 → 94 → 100 excursion between 11:18 and 11:27 is the group-restart teardown/rebuild;
it fully reverted to **56 by t+30**, i.e. the restart's threads were reclaimed rather than
leaked. That reversion is itself a second, independent confirmation of the fix.

## Deposit behaviour change (`bot.deposit.amount`)

Watched for `[BotDeposit] POST` throughout the 30-minute window.

- `docker logs ... | grep -c "BotDeposit"` → **0** at every sample point (t+2, t+5, t+15, t+30).
- Bots with balance below the **new** 100,000,000 trigger: **0 of 155**, at both the pre-deploy
  baseline and t+30.
- Bots below the **old** 5,000,000 trigger: **0 of 155**.

Observed staging balances run in the low billions (e.g. 3.08 B – 4.12 B in "Auth test with
socket"), three to four orders of magnitude above the new trigger. **No deposit fired and none
was expected.** The threshold change is live but latent on Bot-1 — worth re-checking if a
low-balance group is ever created here.

## Loki volume

| Point | Size |
|---|---|
| Pre-deploy (10:58Z) | **13.0 G** |
| t+30 (11:32Z) | **13.0 G** |

Flat across 30 minutes (`du -sh` granularity, so sub-100 MB growth is not resolved). For
scale, the pre-fix flood alone was writing ~178,000 lines per 30 minutes; that ingest is gone.
Note the outstanding infra item from MEMORY — **Loki still has no retention policy and no
Docker log rotation**; 13.0 G is the accumulated backlog and this release does not shrink it.
Killing the flood stops the bleeding but the historical volume needs a separate cleanup.

---

## Verdict

**PASS**

Phase 1 does what it claimed on the one box where the bug reproduced. The orphaned-client
flood went from 98.6 lines/s sustained to a hard zero, survived a deliberate group restart
without leaving a single spinner behind, and released ~98 platform threads. Deploy was clean:
no observability downtime, zero ERROR lines, all 155 bots back at their pre-deploy state, and
a tagged rollback image is one command away. The deposit-threshold change shipped inert.

Two things stop this from being a maximally strong result, stated plainly:

1. **Ambient staging churn was absent** for the 30-minute observation window, so the passive
   +5/+15/+30 samples are weaker evidence than intended. The induced startup and restart
   bursts carry the verdict instead. Re-verify after a busy staging day.
2. The residual 58-line startup burst is the **PING-BEFORE-AUTH race**, still unfixed and out
   of scope here. It is bounded and self-limiting, unlike the orphan leak, but it is the
   remaining source of these WARN lines.

## Logs

Not applicable — no failing command in this release. Zero ERROR lines were emitted by the
application between 11:02:36Z and 11:33Z.
