# Release — SLOT_MACHINE_BOT

Mode: bot
Branch: feat/slot-machine-bot (tip 9eea006)
Image: vingame-bot:latest (built at 2026-06-22T10:01:50Z)
Date: 2026-06-22T10:18:00Z

## Build

- `mvn clean install`: PASS (~20s; 748 tests run, 0 failures, 0 errors, BUILD SUCCESS)
- `docker build --no-cache --platform linux/amd64`: PASS (image sha256:d3cb1905e8ee…)
- `docker save -o bot.tar`: PASS (391315968 bytes)

## Ship

- `sftp put bot.tar` → `Bot-1:/home/sgame/bot-java`: PASS (remote size 391315968 == local, verified by stat)
- (mode=infra) `sftp put infra-images.tar.gz`: N/A — mode=bot, infra image deliberately NOT shipped

## Deploy

- `docker compose down`: PASS (exit 0; took down bot-manager, mongo, grafana, prometheus, loki, promtail)
- `docker image rm vingame-bot:latest`: PASS (prior image existed and was removed, exit 0)
- `docker load -i bot.tar`: PASS (Loaded image: vingame-bot:latest, exit 0)
- `docker compose up -d`: PASS (all 6 containers created+started, exit 0)

## Smoke test

- `docker ps` shows healthy: PASS — `bot-java-bot-manager-1 ... Up (healthy)`; mongo healthy; grafana/prometheus/loki/promtail Up
- Spring Boot ready log: PASS — `Started Starter in 6.704 seconds`; `Tomcat started on port 8085`
- Auto-start log: PASS — `Bot Manager startup complete. 3 bot groups running`
- Actuator health (port 8085): PASS — `{"status":"UP"}`, mongo UP

Note on ports: the application server (and actuator) is on **8085**, not 8080. All
plan-verification curls below were issued against `http://localhost:8085` from
inside the `bot-java-bot-manager-1` container. The plan text references 8080;
8085 is the actual running port on staging.

## Observability stack (Bot-1 single-compose redeploy re-verification)

The redeploy briefly took Grafana/Prometheus/Loki down with the bot. All came back:

- Grafana `:3000/api/health`: 200 (UP)
- Prometheus `:9090/-/healthy`: 200 (UP)
- Loki `:3100/ready`: 200 (UP — was transiently 503 "Ingester not ready: waiting 15s after being ready" during its normal post-start grace window, then flipped to 200)

## Plan verification

Source: `docs/plans/SLOT_MACHINE_BOT.md` § Verification (steps 1–6). The slot
game-management REST API has been refactored since the plan was written: the
create endpoint is now `POST /api/v1/game/{brandCode}/{productCode}` (brand/
product-scoped), not `POST /api/v1/game/`, and `productCode` is a structured
object. Adapted the calls accordingly (brandCode=G3, productCode=P_116 for TIP).

### Step 1: App boots with slot strategies registered ([FIXED, RANDOM])
Command:
- `curl http://localhost:8085/actuator/health`
- `docker logs bot-java-bot-manager-1 | grep SlotStrategyFactory`
Expected: health HTTP 200 `{"status":"UP"}`; a startup line listing `[FIXED, RANDOM]` (count 2).
Actual: health HTTP 200 `{"status":"UP"}` (mongo UP). Log:
`SlotStrategyFactory initialized: registered 2 strategies — [FIXED, RANDOM]`.
Result: PASS

### Step 2: A SLOT game can be created via REST
Command (adapted to current API): `POST /api/v1/game/G3/P_116` with body
`{"name":"SlotTipTest","gameType":"SLOT","gameId":204,"pluginName":"Tip"}`, then
`GET /api/v1/game/{id}`.
Expected: HTTP 200/201, persisted game echoing `gameType:SLOT`, `gameId:204`; GET
returns the same; no `allowedBetValues`/`slotWinlines` fields.
Actual: HTTP 200. Created id `c5a22c44-a848-4ec7-9136-342ed8e5cdea`:
`gameType:SLOT, gameId:204, pluginName:Tip, brandCode:G3, productCode.code:116`.
No `allowedBetValues`/`slotWinlines` fields present (server-sourced, as designed).
GET round-trip identical.
Result: PASS

### Step 3: A slot bot group starts and bots reach CONNECTION_AUTHENTICATED
Command:
- `POST /api/v1/bot-group/` create group `SlotSmokeGroup` (botCount=2, env TIP
  `ad4e7948-…`, gameId `c5a22c44-…`, minBet/maxBet 500) → id `74feeaed-09a5-48db-8f05-6822d76e0773`
- `POST /api/v1/bot-group/74feeaed-…/start`
- `GET  /api/v1/bot-group/74feeaed-…/health` after ~35s
Expected: HTTP 200; `BotGroupHealthDTO` showing bots in `CONNECTION_AUTHENTICATED`
(proves the 1300 subscribe response was received and `markConnectionAuthenticated()`
fired). In logs, a RECEIVED `cmd 1300` frame per slot bot **before** any `cmd 1302`.
Actual: create + start returned HTTP 200. Health after 35s showed **both bots
stuck in `AUTHENTICATING_CONNECTION`, `connected:false`** — `connectedBots:0`,
`disconnectedBots:2`. Bots never reached `CONNECTION_AUTHENTICATED`. The strategy
assigned was `RANDOM`.

Log analysis (the failure):
- Both slot bots authenticate cleanly at the HTTP gateway (tokens resolved,
  balance fetched 1,000,000,000), connect the WebSocket, and complete the
  handshake (`WebSocket handshake completed`, `CONNECTING → CONNECTED`).
- They then transition `CONNECTED → AUTHENTICATING_CONNECTION` and the server
  **immediately drops the TCP connection**:
  `Client ws-slotsmkN: Disconnected — TCP connection dropped (no close frame received)`.
- They loop in the reconnect path (`reconnect attempt 2/3 did not hold`) and never
  authenticate.
- **No WS AUTH frame is ever emitted by the slot bots.** Working betting-mini bots
  log `AUTH [1,"MiniGame","","",{"accessToken":...}]` right after handshake; the
  slot bots (`ws-slotsmk1`/`ws-slotsmk2`) log **no AUTH frame at all** — the log
  goes straight from "Connected to server" / CONNECTED to AUTHENTICATING_CONNECTION
  to the server-side TCP drop. The server closes the unauthenticated socket.
- Consequently **no SENT cmd 1300 (subscribe), no RECEIVED cmd 1300, and no SENT
  cmd 1302 (spin)** ever occur for the slot bots.
Result: FAIL

### Subscribe-before-spin assertion (RECEIVED 1300 precedes SENT 1302 per bot)
Expected: per bot, a RECEIVED `cmd 1300` frame appears before any SENT `cmd 1302`.
Actual: **UNVERIFIABLE / VIOLATED PREMISE** — neither a 1300 nor a 1302 frame was
ever produced. `grep` for `cmd:1300|cmd:1302|"1300"|"1302"` across the full
bot-manager log returned zero matches. The bots never get far enough (they are
dropped before sending WS AUTH, hence before subscribe) to exercise the ordering.
Result: FAIL (no subscribe and no spin traffic exists to assert ordering over)

### Step 4: Spins are sent and results accounted
Command:
- `GET /actuator/metrics/bot_bets_placed_total?tag=gameType:SLOT`
- `GET /actuator/metrics/bot_bet_amount_total?tag=gameType:SLOT`
- `grep "cmd.*1302"` in logs
Expected: `bot_bets_placed_total{gameType=SLOT} > 0`, `bot_bet_amount_total{gameType=SLOT} > 0`;
SENT spin frames + RECEIVED result frames.
Actual: both metric queries returned **empty bodies** — no `gameType:SLOT` series
exists at all (zero spins). No `cmd 1302` frames in logs.
Result: FAIL

### Step 5: Winnings counter populated (RTP feeds)
Command: `GET /actuator/metrics/bot_winnings_total?tag=gameType:SLOT`
Expected: a measurement value `>= 0` (series exists); RTP computable and plausible.
Actual: empty body — no `bot_winnings_total{gameType=SLOT}` series exists. RTP not
computable (no bet-amount and no winnings series).
Result: FAIL

### Step 6: No log errors / no wedged bots; observability stack still UP
Command: `grep -E "ERROR|marking DEAD" | grep -i slot`; re-verify Grafana/Prometheus/Loki.
Expected: no slot ERROR/DEAD lines during a healthy run; observability stack UP.
Actual: No slot `ERROR` or `DEAD` lines were logged (the bots fail by silent
server-side TCP drop + reconnect loop, not by exception or DEAD marking). However
this is NOT a healthy run: both slot bots are effectively wedged in an
auth→drop→reconnect loop and never become operational. Observability stack
re-verified UP (Grafana 200, Prometheus 200, Loki 200). The failing slot group was
stopped (`POST .../stop` → HTTP 200) to leave staging clean.
Result: PARTIAL — no ERROR/DEAD lines and observability stack healthy, but the
slot bots are non-functional (wedged), so this does not represent a healthy slot run.

## Regression check (existing betting-mini bots)

No regression. During the slot-bot failure window, the existing betting-mini groups
(`116 Demo group`, `XD game test`, `Fruit shop Bots`) remained ACTIVE and were
actively spinning — `[SENT]` cmd 8002 (Bau Cua / gourdCrabWithExtraBonusPlugin)
and cmd 9002 (Fruit Shop / miniFruitPlugin) frames flowed continuously. The
resolver rename / SLOT wiring did not break betting-mini.

## Verdict

FAIL

Build, ship, deploy, the universal smoke test, the observability-stack re-verify,
and plan steps 1 and 2 all PASS. The feature itself does NOT work on staging: slot
bots authenticate at the gateway and complete the WebSocket handshake, but the
server immediately drops the connection because the slot bot **never sends its
WebSocket AUTH frame**. As a result no subscribe (cmd 1300) and no spin (cmd 1302)
ever occur, the bots never reach `CONNECTION_AUTHENTICATED`, no slot metrics are
emitted, and the subscribe-before-spin assertion cannot be satisfied (neither
frame exists). Plan steps 3, 4, 5 FAIL and the subscribe-before-spin assertion is
a violated premise.

## Logs (FAIL evidence)

Slot-bot lifecycle (representative, slotsmk1/slotsmk2), showing handshake →
AUTHENTICATING_CONNECTION → server TCP drop, with NO AUTH frame in between:

```
10:09:05.367 [ioGroup-2-3] INFO  VingameWebSocketClient - Client ws-slotsmk1: WebSocket handshake completed
10:09:05.367 [bot-creation-90] INFO  VingameWebSocketClient - Client ws-slotsmk1: Connected to server
10:09:05.367 [ioGroup-2-3] DEBUG Bot [.../1/SlotTipTest] - Bot slotsmk1: CONNECTING → CONNECTED
10:09:05.468 [ioGroup-2-3] DEBUG Bot [.../1/SlotTipTest] - Bot slotsmk1: CONNECTED → AUTHENTICATING_CONNECTION
10:09:05.494 [ioGroup-2-3] WARN  VingameWebSocketClient - Client ws-slotsmk1: Disconnected — TCP connection dropped (no close frame received)
10:09:05.495 [reconnect-ws-slotsmk1] DEBUG Bot [.../1/SlotTipTest] - Bot slotsmk1: AUTHENTICATING_CONNECTION → RECONNECTING
...
10:09:26.592 [reconnect-slotsmk1] DEBUG Bot [.../1/SlotTipTest] - Bot slotsmk1: reconnect attempt 2 did not hold
10:09:59.639 [reconnect-slotsmk1] DEBUG Bot [.../1/SlotTipTest] - Bot slotsmk1: reconnect attempt 3 did not hold
```

Contrast — a working betting-mini bot emits an AUTH frame right after handshake
(the slot bots emit no such line):

```
10:03:47.695 [ioGroup-2-1] INFO VingameWebSocketClient - AUTH [1,"MiniGame","","",{"accessToken":"189-fb853e…","agentId":"1","reconnect":false}]
```

Metric queries (all empty — no gameType:SLOT series exists):

```
bot_bets_placed_total?tag=gameType:SLOT   -> (empty)
bot_bet_amount_total?tag=gameType:SLOT    -> (empty)
bot_winnings_total?tag=gameType:SLOT      -> (empty)
```

1300/1302 frame search across full log: zero matches.

## Likely root cause (for the dev/architect follow-up)

The `SlotMachineBot` / slot WS client is not sending a WebSocket AUTH frame after
the handshake completes. Betting-mini bots send `AUTH [1,"MiniGame",...]` and stay
connected; slot bots send nothing and the server tears down the unauthenticated
socket within ~30–130 ms. The slot path appears to be missing the post-handshake
AUTH-send wiring (the analogue of the betting-mini AUTH that precedes the cmd 1300
subscribe). Because AUTH never fires, the scenario never reaches `send(subscribe)`,
so cmd 1300 / cmd 1302 never appear and `markConnectionAuthenticated()` never runs.
This needs a code fix on the slot WS auth/connect path, not a release retry.
```

---

# Release (RETRY 2) — SLOT_MACHINE_BOT

Mode: bot
Branch: feat/slot-machine-bot (tip 5f2e62d — `fix(environment): resolve SLOT games to the mini WS zone`)
Image: vingame-bot:latest (built at 2026-06-22T10:31Z; image sha256:8839ec9bb233…)
Date: 2026-06-22T10:43:39Z

Context: redeploy after the WS-zone bug fix. The previous deploy (tip 9eea006) failed
because slot bots authenticated to the wrong WebSocket zone ("Simms" instead of
"MiniGame") and the server dropped them before AUTH. Commit 5f2e62d makes
`Environment.resolveZoneName` route SLOT games to the mini zone ("MiniGame"). This
retry was intended to re-reach plan verification (AUTH success + subscribe-before-spin).

## Build

- `mvn clean install`: PASS (~20s; 749 tests run, 0 failures, 0 errors, BUILD SUCCESS)
- `docker build --no-cache --platform linux/amd64`: PASS (image sha256:8839ec9bb233a9d4425e1009050c17e33f21e8c83f0b64eddfd3fcf8406f08b9)
- `docker save -o bot.tar`: PASS (391315968 bytes)

## Ship

- `sftp put bot.tar` → `Bot-1:/home/sgame/bot-java`: PASS (remote size 391315968 == local, verified by stat)
- (mode=infra) `sftp put infra-images.tar.gz`: N/A — mode=bot, infra image deliberately NOT shipped (an infra-images.tar.gz exists in the repo root but was NOT uploaded)

## Deploy

- `docker compose down`: PASS (exit 0; took down bot-manager, mongo, grafana, prometheus, loki, promtail)
- `docker image rm vingame-bot:latest`: PASS (prior image existed and was removed, exit 0)
- `docker load -i bot.tar`: PASS (Loaded image: vingame-bot:latest, exit 0)
- `docker compose up -d`: PASS (all 6 containers created+started, exit 0; mongo reported Healthy before bot-manager started)

## Smoke test — FAIL (STOP)

- `docker ps` shows healthy: **FAIL** — `bot-java-bot-manager-1 ... Up 10 minutes (unhealthy)`. Docker healthcheck history: 5 consecutive failures, all exit code 7 (`[7][7][7][7][7]` = curl "failed to connect" — nothing listening on the actuator port).
- Spring Boot ready log: **FAIL** — neither `Started Starter` nor `startup complete` nor `Tomcat started on port 8085` ever appear in the log. Tomcat was *initialized* (`Tomcat initialized with port 8085`) but never *started*.
- Auto-start log: **FAIL** — auto-start began (`Auto-starting bot group: 116 Demo group`, `Creating 30 bots ... (game: Bau Cua)`) but never completed.
- Actuator health (port 8085, from inside the container via wget): **FAIL** — `CURL_FAIL`, the actuator endpoint does not respond (web server never came up).

### Failure detail

The container started at 10:33:16. The log reached exactly **51 lines** and then **froze
at 10:33:19** with zero further output for the next 10+ minutes (verified at 10:40 and
again at 10:43; line count stayed 51, last timestamp stayed 10:33:19). The application's
`main` thread is **blocked during auto-start of the existing betting-mini group
"116 Demo group" (Bau Cua)** — the last lines are 10 `bot-creation-N` threads logging
`Creating bot demob0t1aN ... (game: Bau Cua)` and `Creating shared clients for
environment ad4e7948-…`, after which all go silent. Auto-start runs **before** the
Spring context finishes and the web server starts, so Tomcat never begins serving,
the actuator healthcheck never passes, and the container stays `unhealthy`.

This is **NOT a slot failure** and **NOT related to the WS-zone fix under test**. The
hang occurs in the existing betting-mini auto-start path (the Bau Cua group), which
the slot code does not touch. The most likely cause is a blocking call to the auth
gateway / game server during bot creation that is not returning (gateway slow or
unreachable from the staging host at deploy time), with no timeout unwinding it.

Notably: there are **zero WARN and zero ERROR lines** in the entire 51-line log — the
hang is a silent block, not an exception.

Per the Releaser smoke-test rule, the run is **stopped here**: plan-driven verification
was **not executed**, because the application never reached a serving state. The slot
WS-zone fix could therefore **not be exercised on staging** — AUTH success and the
subscribe-before-spin (RECEIVED 1300 before SENT 1302) assertion remain **unverified**
on this deploy (the app never started, no bot group could be created, no slot bots ran).

## Observability stack (Bot-1 single-compose redeploy re-verification)

The other five containers in the shared compose project came back up normally; only
bot-manager is wedged.

- Grafana `:3000/api/health`: 200 (UP)
- Prometheus `:9090/-/healthy`: 200 (UP)
- Loki `:3100/ready`: 503 at the 10-min check, body `Ingester not ready: waiting for 15s after being ready` — this is Loki's normal post-start grace window (same transient seen in the prior release), expected to flip to 200 shortly; not a redeploy regression.
- mongo: healthy
- promtail: Up

## Verdict

**FAIL**

Build, ship, and deploy all PASS. The universal smoke test FAILS: the bot-manager
container is `unhealthy` (5/5 healthchecks failed, exit 7), Spring never logged
`Started Starter` / `startup complete`, Tomcat never started, and the actuator does
not respond. The application is hung on the `main` thread during auto-start of the
existing betting-mini "116 Demo group" (Bau Cua), before the web server comes up —
an existing-bot-startup blocking call that does not return, unrelated to the SLOT
feature or the WS-zone fix. Because the app never served, plan-driven verification
was not run and the slot WS-zone fix is unverified on staging.

Recommended next step: investigate why betting-mini auto-start of "116 Demo group"
blocks the main thread at deploy time (auth gateway reachability / missing timeout
on the bot-creation network call). This is a startup-availability problem, not a
release-retry problem. Consider whether auto-start should be moved off the main
thread or guarded with a timeout so the web server can start regardless.

## Logs (FAIL evidence)

Full log is only 51 lines; it freezes at 10:33:19. Representative tail (auto-start
of the existing Bau Cua group, after which the log goes silent):

```
10:33:18.385 [main] INFO  TomcatWebServer - Tomcat initialized with port 8085 (http)   # initialized, never "started"
10:33:19.379 [main] INFO  BettingStrategyFactory - registered 9 strategies — [RANDOM, MARTINGALE_CLASSIC_CAUTIOUS, ... FIBONACCI_AGGRESSIVE]
10:33:19.383 [main] INFO  SlotStrategyFactory - SlotStrategyFactory initialized: registered 2 strategies — [FIXED, RANDOM]   # slot strategies DID register
10:33:19.432 [main] INFO  BotGroupBehaviorService - Bot Manager starting up - checking for bot groups to auto-start
10:33:19.574 [main] INFO  BotGroupBehaviorService - Auto-starting bot group: 116 Demo group (ID: b1e80470-…)
10:33:19.622 [main] INFO  BotGroupBehaviorService - Creating 30 bots for group 116 Demo group with parallel execution (parallelism=10)
10:33:19.644 [bot-creation-4] INFO  EnvironmentClientRegistry - Creating shared clients for environment: ad4e7948-…
10:33:19.64x [bot-creation-N] DEBUG BotFactory - Creating bot demob0t1aN ... (game: Bau Cua)     # x10, then SILENCE for 10+ min
```

Healthcheck history: `unhealthy :: [7][7][7][7][7]` (5× exit 7, connection refused).
Total log lines: 51 (unchanged across the 10:40 and 10:43 checks).

Note: `SlotStrategyFactory initialized: registered 2 strategies — [FIXED, RANDOM]`
DID log this boot (plan Verification step 1, partial), confirming the slot beans wire
into the Spring context — but the context never finished, so step 1's actuator-health
half and all of steps 2–6 could not be reached.

---

# Release (RETRY 3) — SLOT_MACHINE_BOT

Mode: bot
Branch: feat/slot-machine-bot (tip 5f2e62d — `fix(environment): resolve SLOT games to the mini WS zone`)
Image: vingame-bot:latest (already loaded on Bot-1; sha256:8839ec9bb233, built 2026-06-22T10:31Z — UNCHANGED from RETRY 2)
Date: 2026-06-22T11:03:00Z

Context / scope: RETRY 2 deploy FAILED only because the app wedged on startup (main
thread blocked during auto-start of the existing betting-mini "116 Demo group" / Bau
Cua — a transient blocking gateway call). Per the user, the code is UNCHANGED since
RETRY 2, so this retry **re-attempted startup only** — no rebuild, no re-ship.

## Image verification (no rebuild / no re-ship)

- Loaded image on Bot-1: `vingame-bot:latest` = `sha256:8839ec9bb233`, 378MB, built
  2026-06-22 17:31:19 +07. This is byte-identical to what RETRY 2 shipped
  (`bot.tar` local == remote, 391315968 bytes; sha 8839ec9bb233 matches RETRY 2's
  `image sha256:8839ec9bb233a9d4…`). Build/ship steps **skipped** as instructed.
- infra-images.tar.gz: deliberately NOT shipped (mode=bot).

## Deploy (re-attempt startup)

- `docker compose down`: PASS (exit 0; took down all 6 containers)
- `docker image rm`: not run (image is correct and reused; no removal needed)
- `docker load`: not run (image already loaded and verified correct)
- `docker compose up -d`: PASS (all 6 containers created+started, exit 0; mongo went
  Healthy before bot-manager started)

## Smoke test — PASS (startup hang was transient)

- `docker ps` shows healthy: PASS — `bot-java-bot-manager-1 ... Up (healthy)` ~50s
  after start. Log grew from 8362 → 15199 lines in 22s (vs RETRY 2's frozen 51 lines).
- Spring Boot ready log: PASS —
  `Bot Manager startup complete. 3 bot groups running` (10:54:51),
  `Tomcat started on port 8085 (http)` (10:54:52),
  `Started Starter in 6.22 seconds` (10:54:52).
- Auto-start log: PASS — completed cleanly; the betting-mini "116 Demo group" (Bau Cua)
  auto-start that wedged the main thread in RETRY 2 **completed normally this time**,
  confirming that hang was transient (gateway reachability at deploy time).
- Slot strategies registered: PASS —
  `SlotStrategyFactory initialized: registered 2 strategies — [FIXED, RANDOM]`.
- Actuator health (port 8085): PASS — `{"status":"UP"}`, mongo UP.

## Observability stack (Bot-1 single-compose re-verify)

- Grafana `:3000/api/health`: 200 (UP)
- Prometheus `:9090/-/healthy`: 200 (UP)
- Loki `:3100/ready`: 503 during post-start grace window, then 200 (UP) — normal
  transient, not a regression.

## Plan verification

Source: `docs/plans/SLOT_MACHINE_BOT.md` § Verification. Ports/endpoints as adapted in
the original log (8085; `POST /api/v1/game/{brandCode}/{productCode}`).

### Step 1: App boots with slot strategies registered ([FIXED, RANDOM])
Command: `actuator/health` + `grep SlotStrategyFactory`.
Expected: health 200 UP; startup line `[FIXED, RANDOM]` (2 strategies).
Actual: health 200 UP; `SlotStrategyFactory initialized: registered 2 strategies — [FIXED, RANDOM]`.
Result: PASS

### Step 2: A SLOT game can be created via REST
Note: the game from RETRY 1 (`c5a22c44…`) was no longer present in mongo this boot,
so a fresh SLOT game was created (reused identical config).
Command: `POST /api/v1/game/G3/P_116` body
`{"name":"SlotTipTest","gameType":"SLOT","gameId":204,"pluginName":"Tip"}`.
Expected: HTTP 200; persisted SLOT game, gameId 204, P_116/TIP.
Actual: HTTP 200. Created id `143b2e64-137d-410a-ba90-b7f1c200bbf3`:
`gameType:SLOT, gameId:204, pluginName:Tip, brandCode:G3, productCode.code:116, appId:bc115116`.
Result: PASS

### Step 3: A slot bot group starts; bots send WS AUTH [1,"MiniGame",...], reach CONNECTION_AUTHENTICATED, stay connected (the WS-zone fix under test)
Command: create group `SlotSmokeGroup3` (id `99a266f3-e847-4bcc-9e82-350adc656c69`,
botCount=2, env TIP `ad4e7948…`, gameId `143b2e64…`, minBet/maxBet 500, prefix
`slotr3b`) → `POST .../start` → `GET .../health`.
Expected: both bots connected and authenticated; WS AUTH `[1,"MiniGame",...]` emitted.
Actual: **MAJOR PROGRESS vs RETRY 1** — the WS-zone fix WORKS:
- Both slot bots emit the WS AUTH frame in the **MiniGame** zone (the exact thing
  missing in RETRY 1):
  `AUTH [1,"MiniGame","","",{"accessToken":"189-71aa…","agentId":"1","reconnect":false}]`
  and `AUTH [1,"MiniGame","","",{"accessToken":"189-214a…",…}]`.
- Both bots complete the handshake, go `CONNECTED → AUTHENTICATING_CONNECTION → STARTED`,
  deposit (balance 1,000,000,000), and **stay connected** — the server does NOT drop
  them (RETRY 1 had a server TCP drop within ~30–130 ms). Health: `connectedBots:2`,
  `disconnectedBots:0`, `deadBots:0`, both `STARTED`, `connected:true`.
- Both bots then send the subscribe: `[SENT] ["6","MiniGame","Tip",{"cmd":1300,"gid":204}]`.

Caveat on `CONNECTION_AUTHENTICATED`: the bots transition to `STARTED` after
`AUTHENTICATING_CONNECTION` (the build's status flow), and the WS stays up; no explicit
`CONNECTION_AUTHENTICATED` log marker was emitted in this build, but the connection is
held and AUTH succeeded.
Result: PASS (AUTH success + stay-connected, the WS-zone fix is validated)

### Subscribe-before-spin assertion (RECEIVED cmd 1300 precedes SENT cmd 1302 per bot)
Expected: per bot, a RECEIVED `cmd 1300` (subscribe response) before any SENT `cmd 1302` (spin).
Actual: **UNVERIFIABLE / VIOLATED PREMISE (NEW failure mode)** — both bots SENT cmd 1300
(subscribe) at 10:57:31, then received **ZERO frames** for the entire observation window
(10:57:31 → 11:03:07, ~5.5 min). No RECEIVED cmd 1300 subscribe response ever arrived,
and consequently no SENT cmd 1302 spin ever occurred. The WS connection remained alive
the whole time (connected:true, no disconnect, no DEAD). RECEIVED-frame count for slot
bots = 0; cmd-1302 count = 0.
Result: FAIL (no subscribe response and no spin traffic exists to assert ordering over)

### Step 4: Spins are sent and results accounted
Command: `actuator/metrics/bot_bets_placed_total?tag=gameType:SLOT`,
`bot_bet_amount_total?tag=gameType:SLOT`, `grep cmd":1302`.
Expected: `bot_bets_placed_total{gameType=SLOT} > 0`, bet_amount > 0, SENT 1302 frames.
Actual: all three metric queries returned **empty bodies** (no gameType:SLOT series
exists — zero spins). No cmd 1302 frames in the log.
Result: FAIL

### Step 5: Winnings counter populated (RTP feeds)
Command: `actuator/metrics/bot_winnings_total?tag=gameType:SLOT`.
Expected: a measurement value `>= 0`; RTP computable/plausible.
Actual: empty body — no `bot_winnings_total{gameType=SLOT}` series. RTP not computable
(no stake and no winnings series).
Result: FAIL

### Step 6: No log errors / no wedged bots; observability stack UP
Command: `grep -iE 'ERROR|DEAD|exception' slot lines`; re-verify Grafana/Prometheus/Loki.
Expected: no slot ERROR/DEAD; observability UP.
Actual: **No slot `ERROR`, `DEAD`, or exception lines** — the bots fail silently (server
never answers the subscribe), no watchdog/re-subscribe fired. Observability stack UP
(Grafana 200, Prometheus 200, Loki 200). Bots are connected but inert (no game data),
so this is not a fully healthy slot run, but there are no error/DEAD log lines.
Result: PARTIAL (clean logs + observability UP, but slot bots receive no game data)

## Regression check (existing betting-mini bots)

No regression. Throughout the slot test the existing betting-mini groups stayed ACTIVE
and were actively spinning — Fruit Shop (`miniFruitPlugin`, cmd 9002) `[SENT]` frames
flowed continuously (e.g. `ws-fru1tsh0p2/18/30` at 11:01:58). Bau Cua / XD groups
running. The SLOT wiring and WS-zone change did not break betting-mini.

## Verdict

**FAIL** (but materially advanced past RETRY 1/2)

What this retry proved:
- Startup hang from RETRY 2 was **transient** — the app booted cleanly this time
  (Started Starter, Tomcat 8085, 3 groups running). Smoke PASS.
- The WS-zone fix (5f2e62d) **WORKS**: slot bots now send WS AUTH `[1,"MiniGame",…]`,
  stay connected (no server TCP drop), deposit, and SEND the subscribe (cmd 1300).
  This is exactly the RETRY 1 failure that is now resolved.

What still fails (NEW failure mode, deeper in the flow):
- After SENT cmd 1300 (subscribe), the slot bots receive **nothing** from the game
  server for 5.5+ min — no RECEIVED cmd 1300 (subscribe response), hence no spins
  (cmd 1302), no SLOT metrics. The connection stays alive but inert.
- The subscribe-before-spin assertion is therefore a **violated premise** (no RECEIVED
  1300 and no SENT 1302 exist). Plan steps 4 and 5 FAIL; step 6 PARTIAL.

This is NOT a startup wedge and NOT a release-retry problem — startup succeeded. It is
a feature-level issue past AUTH/subscribe: the game server does not respond to the slot
`{"cmd":1300,"gid":204}` subscribe (possible gid/zone/subchannel mismatch, missing
post-subscribe handshake step, or server not provisioning gid 204 for this product).
Needs a dev/architect follow-up on the slot subscribe path — not another redeploy.

## Staging state left clean

Slot group `SlotSmokeGroup3` (`99a266f3-e847-4bcc-9e82-350adc656c69`) was **STOPPED**
(`POST .../stop` → HTTP 200). The SLOT game `143b2e64-137d-410a-ba90-b7f1c200bbf3`
remains in mongo for reuse. bot-manager is `Up (healthy)`. Observability UP. The
existing betting-mini groups remain ACTIVE and spinning (their normal state).

## Logs (key evidence)

Slot AUTH now fires in the MiniGame zone (the RETRY-1 fix, working):
```
10:57:29.146 AUTH [1,"MiniGame","","",{"accessToken":"189-71aa…","agentId":"1","reconnect":false}]
10:57:29.150 AUTH [1,"MiniGame","","",{"accessToken":"189-214a…","agentId":"1","reconnect":false}]
```
Bots stay connected and subscribe:
```
10:57:30.090 Bot slotr3b1: AUTHENTICATING_CONNECTION → STARTED
10:57:30.090 Bot slotr3b2: AUTHENTICATING_CONNECTION → STARTED
10:57:31.541 User slotr3b1: [SENT] ["6","MiniGame","Tip",{"cmd":1300,"gid":204}]
10:57:31.543 User slotr3b2: [SENT] ["6","MiniGame","Tip",{"cmd":1300,"gid":204}]
```
…then silence — RECEIVED-frame count for slot bots = 0, cmd-1302 count = 0, over
10:57:31 → 11:03:07. SLOT metric series (bets_placed / bet_amount / winnings) all empty.

---

# RETRY 4 — slot subscribe/spin routed to fixed `slotMachinePlugin` extension

Mode: bot
Branch: feat/slot-machine-bot
Tip: 47126bf — `fix(slot): route slot frames to fixed slotMachinePlugin extension`
Image: vingame-bot:latest (built 2026-06-22 15:22:47 +04)
Date: 2026-06-22T11:25–11:32Z (deploy → verify)

## What changed since RETRY 3

RETRY 3 confirmed the zone fix (5f2e62d): slot bots AUTH in the `MiniGame`
zone, reach CONNECTION_AUTHENTICATED, and stay connected. But the subscribe
(`cmd 1300`) was routed to the SmartFox extension `"Tip"`, which the server
never answered — RECEIVED-1300 count was 0 and no spins ever fired.

47126bf routes **both** the slot subscribe (1300) and spin (1302) frames to the
fixed extension `"slotMachinePlugin"` (product owner confirmed correct for all
products/envs). The `pluginName` Game field is no longer used for slot routing.

## Build

- `mvn clean install`: PASS (22s) — 753 tests, 0 failures, 0 errors, 0 skipped.
  All slot test classes present and green (SlotMessageDeserializationTest,
  SlotRequestTest, SlotMachineBot{Subscribe,SpinAccounting,SpinStream,GateEdgeCases}Test,
  SlotStrategyFactoryTest, BotFactorySlotWiringTest, GameMapperTest$SlotGameTypeTests).
- `docker build --no-cache --platform linux/amd64`: PASS (14s) — image 378MB, id 21e172516dfc.
- `docker save -o bot.tar`: PASS — 391315968 bytes.

## Ship

- `sftp put bot.tar` → Bot-1:/home/sgame/bot-java: PASS (110s).
  Remote size 391315968 bytes — exact match.

## Deploy

- `docker compose down`: PASS — all 6 services (mongo, loki, promtail,
  bot-manager, prometheus, grafana) stopped/removed, network removed.
- `docker image rm vingame-bot:latest`: PASS — prior image untagged/deleted.
- `docker load -i bot.tar`: PASS — "Loaded image: vingame-bot:latest".
- `docker compose up -d`: PASS — full stack recreated; mongo Healthy before
  bot-manager start. No startup wedge; no retry needed.

## Smoke test

- `docker ps` bot-manager healthy: PASS — "Up About a minute (healthy)".
- Spring Boot ready: PASS — `Started Starter in 6.58 seconds`;
  `Tomcat started on port 8085 (http)` (port 8085 as expected).
- Auto-start / factory init: PASS —
  `SlotStrategyFactory initialized: registered 2 strategies — [FIXED, RANDOM]`
  and `BettingStrategyFactory ... 9 strategies`. Betting-mini auto-start groups
  resumed cleanly.
- `GET /actuator/health` (port 8080 internal): 200, `{"status":"UP"}`
  (diskSpace/mongo/ping/ssl all UP).

## Observability (single-compose stack)

- grafana `:3000/api/health` → 200; prometheus `:9090/-/healthy` → 200;
  loki `:3100/ready` → 200 (503 during first ~30s readiness window, then 200);
  promtail Up; mongo Up (healthy). All UP.

## Plan verification (docs/plans/SLOT_MACHINE_BOT.md § Verification)

### Step 1: App boots with slot strategies registered
Command: `curl -s http://localhost:8080/actuator/health` + log grep `SlotStrategyFactory`
Expected: health UP; factory line lists `[FIXED, RANDOM]` (count 2).
Actual: health 200 UP; `SlotStrategyFactory initialized: registered 2 strategies — [FIXED, RANDOM]`.
Result: PASS

### Step 2: A SLOT game exists (reused) with gameType=SLOT, gameId=204, P_116
Command: `GET /api/v1/game/c5a22c44-a848-4ec7-9136-342ed8e5cdea`
Note: game API is brand/product scoped (`/api/v1/game/{brandCode}/{productCode}`,
TIP = brand G3 / product 116). Reused existing SLOT game `SlotTipTest`
(id c5a22c44, gameType=SLOT, gameId=204, product 116, pluginName="Tip" — no
longer used for routing).
Expected: HTTP 200, gameType:SLOT, gameId:204.
Actual: GET 200; gameType=SLOT, gameId=204, product=116.
Result: PASS

### Step 3: Slot bot group starts; bots reach CONNECTION_AUTHENTICATED; RECEIVED 1300 before any 1302
Command: `POST /api/v1/bot-group/74feeaed-…/start` (group "SlotSmokeGroup", 2 bots,
bound to the SLOT game) + `GET …/health` + log frame inspection.
Expected: bots CONNECTION_AUTHENTICATED; a RECEIVED cmd 1300 per bot before any cmd 1302.
Actual:
- POST start = 200; group status ACTIVE; both bots (slotsmk1, slotsmk2)
  CONNECTION_AUTHENTICATED, 2/2 connected, 0 dead.
- **Subscribe now routes to the fixed extension and the server RESPONDS:**
  ```
  [SENT]     ["6","MiniGame","slotMachinePlugin",{"cmd":1300,"gid":204}]
  [RECEIVED] [5,{"as":false,"gid":204,...,"ls":[{lid:0..24}],"Js":[{b:500..10000}],"cmd":1300,...}]
  ```
  **RECEIVED cmd 1300 = 2 frames (one per bot)** — this is the thing that failed
  in RETRY 3 (was 0). The server answered with the full 25-winline `ls` and the
  5-tier `Js` bet set [500,1000,2000,5000,10000].
- **Subscribe-before-spin assertion: PASS (both bots).** Causal chain for
  slotsmk2 (DEBUG markers, source order):
  ```
  11:29:27.854 [SENT] cmd 1300 (subscribe)
  11:29:27.866 STARTED → CONNECTION_AUTHENTICATED   (set inside onSubscribe)
  11:29:27.866 SlotMachineBot: subscribed — numLines=25, allowedBetValues=[500,1000,2000,5000,10000]
  11:29:27.868 [SENT] cmd 1302 (first spin)
  ```
  The onSubscribe handler (which consumes the RECEIVED 1300) completed at .866,
  before the first spin at .868. slotsmk1: RECEIVED 1300 @ .860 < SENT 1302 @ .862.
  (A same-millisecond log-buffer interleave between the netty-WS thread and the
  pool thread put one SENT-1302 line textually above its RECEIVED-1300 line for
  slotsmk2; the onSubscribe DEBUG marker establishes the true causal order — the
  spin is gated on numLines>0 && allowedBetValues!=null, set only in onSubscribe.)
Result: PASS

### Step 4: Spins sent and results accounted; bot_bets_placed_total{SLOT} > 0
Command: spin metrics by group + log frame inspection.
Note: the `gameType` metric tag carries the game *name* ("SlotTipTest"), not the
literal "SLOT"; values below are scoped to the slot group id to be exact.
Expected: bets_placed > 0, bet_amount > 0; SENT and RECEIVED 1302 frames.
Actual:
- Spin frames route to the fixed extension and the server returns results:
  ```
  [SENT]     ["6","MiniGame","slotMachinePlugin",{"cmd":1302,"aid":1,"b":500,"gid":204,"ls":[0..24]}]
  [RECEIVED] [5,{...,"b":500,"gid":204,"sid":..,"cmd":1302,"wls":[{"crd":8000,...},...]}]
  ```
  SENT 1302 = 34, RECEIVED 1302 = 34 in the first ~30s window (1:1).
- `bot_bets_placed_total{slot group}` = 96 → 142 (growing, > 0).
- `bot_bet_amount_total{slot group}` = 1,225,000 → 1,825,000 (> 0).
  ≈ 12,500 per spin = b(500) × numLines(25) — bet amount = total stake, per AD-13.
Result: PASS

### Step 5: Winnings counter populated (RTP feeds)
Command: `bot_winnings_total{slot group}`.
Expected: measurement >= 0; RTP plausible (< 100% sustained).
Actual: `bot_winnings_total{slot group}` = 1,122,000 → 1,631,500 (> 0).
RTP = winnings / bet_amount ≈ 1,631,500 / 1,825,000 ≈ **89–92%** across reads —
plausible, below 100% (not the RTP-anomaly diagnostic). Per-bot
`lastRoundWinnings` populated (slotsmk1=1000, slotsmk2=5000 at first sample).
Result: PASS

### Step 6: No log errors / no wedged bots; observability still UP
Command: log scan for slot ERROR/DEAD; betting-mini group health; obs probes.
Expected: no slot ERROR/DEAD; observability UP.
Actual:
- No slot ERROR/DEAD lines since group start. No app-wide ERROR/DEAD since deploy.
- No betting-mini regression: Fruit shop Bots 40/40, XD game test 20/20,
  116 Demo group 30/30 — all ACTIVE, 0 dead.
- Observability stack all UP (grafana/prometheus/loki 200, promtail/mongo up).
Result: PASS

## Staging state left behind

- Slot group `74feeaed-…` (SlotSmokeGroup, 2 bots): **left RUNNING** (ACTIVE,
  2/2 connected) so the fix can be observed live. Stop via
  `POST /api/v1/bot-group/74feeaed-09a5-48db-8f05-6822d76e0773/stop` when done.
- Pre-existing betting-mini ACTIVE groups left running (unchanged by this deploy).
- A duplicate idle SLOT game (id 143b2e64, same gid 204) and an idle slot group
  (SlotSmokeGroup3, id 99a266f3, STOPPED) from earlier retries remain; harmless.

## Verdict

PASS — the server now RESPONDS to the slot subscribe. RECEIVED cmd 1300 appears
for every slot bot, the subscribe-before-spin ordering holds, spins flow on the
fixed `slotMachinePlugin` extension, and bets/winnings metrics populate with a
plausible RTP. No errors, no betting-mini regression, observability UP.

---

# RETRY 5 — slot strategy selection (GET /api/v1/strategy?gameType + group-level slotStrategyId)

Mode: bot
Branch: feat/slot-machine-bot
Tip: 6ef0d29 — `feat(botgroup): apply slotStrategyId to SLOT bots at build time`
(on top of 4397e10 `persist nullable group-level slotStrategyId` and d8d19f1
`parameterize strategy listing by game type`)
Image: vingame-bot:latest (built 2026-06-23T14:20Z; image sha256:2cc1c7b183a3…)
Date: 2026-06-23T10:22–10:27Z (deploy → verify, container clock UTC)

## What changed since RETRY 4

RETRY 4 proved slot bots AUTH/subscribe/spin end-to-end on the fixed
`slotMachinePlugin` extension. RETRY 5 adds strategy SELECTION:
- (a) `GET /api/v1/strategy/?gameType=SLOT` returns the slot strategies
  ([FIXED, RANDOM]); the listing endpoint is now parameterized by game type.
- (b) a nullable group-level `slotStrategyId` persists on the bot group and
  flows into the slot bots at build time (defaults FIXED, now selectable RANDOM).

Real code change → full rebuild + redeploy (mode=bot, bot image only).

## Build

- `mvn clean install`: PASS (~22s) — `Tests run: 764, Failures: 0, Errors: 0, Skipped: 0`, BUILD SUCCESS.
- `docker build --no-cache --platform linux/amd64`: PASS — image sha256:2cc1c7b183a3e6ba8c06448940861f941d5d05637e515e63f7e0298490ae6875 (378MB).
- `docker save -o bot.tar`: PASS — 391316992 bytes.

## Ship

- `sftp put bot.tar` → Bot-1:/home/sgame/bot-java: PASS. Remote size 391316992 == local (exact match).
- (mode=infra) `sftp put infra-images.tar.gz`: N/A — mode=bot, infra image deliberately NOT shipped.

## Deploy

- `docker compose down`: PASS (exit 0; all 6 services + network removed).
- `docker image rm vingame-bot:latest`: PASS — prior image (RETRY 4 sha256:21e172516dfc) untagged/deleted.
- `docker load -i bot.tar`: PASS — `Loaded image: vingame-bot:latest`; loaded id sha256:2cc1c7b183a3 (== built image).
- `docker compose up -d`: PASS — all 6 containers created+started, exit 0; mongo Healthy before bot-manager start. No startup wedge; no down/up retry needed.

## Smoke test

- `docker ps` bot-manager healthy: PASS — `bot-java-bot-manager-1 ... Up (healthy)`.
- Spring Boot ready: PASS — `Started Starter in 6.9 seconds`; `Tomcat started on port 8085 (http)` (port 8085 as expected).
- Auto-start / factory init: PASS — `Bot Manager startup complete. 4 bot groups running`;
  `SlotStrategyFactory initialized: registered 2 strategies — [FIXED, RANDOM]`;
  `BettingStrategyFactory ... registered 9 strategies`.

## Observability (Bot-1 single-compose stack)

- grafana `:3000/api/health` → 200; prometheus `:9090/-/healthy` → 200;
  loki `:3100/ready` → 503 during the normal post-start grace window, then 200 (UP) on recheck.
  promtail Up; mongo Up (healthy). All UP.

## Verification — the NEW behavior

NOTE on endpoint path: the strategy listing controller maps to `/api/v1/strategy/`
**with a trailing slash** (same convention as `/api/v1/bot-group/`, `/api/v1/game/`,
etc. in this codebase). The no-trailing-slash form `/api/v1/strategy?gameType=…`
returns HTTP 404 `{"type":"Bad request","msg":"No static resource api/v1/strategy."}`
— Spring treats it as a static-resource miss. All checks below use the working
trailing-slash form. This is a path-shape note, not a feature failure: the
gameType-parameterized listing works correctly.

### (a) GET /api/v1/strategy/?gameType=SLOT → [FIXED, RANDOM]
Command: `curl http://localhost:8085/api/v1/strategy/?gameType=SLOT`
Expected: the two slot strategies, ids FIXED and RANDOM, DTO shape {id, displayName, description}.
Actual:
```
[{"id":"FIXED","displayName":"Fixed","description":"Always stakes the smallest allowed bet value every spin."},
 {"id":"RANDOM","displayName":"Random","description":"Picks a bet amount uniformly at random from the allowed set every spin."}]
```
Exactly two entries (FIXED, RANDOM), full {id, displayName, description} shape.
Result: PASS

### (b) Backward-compat: BETTING_MINI and no-param still return the betting strategies
Command:
- `curl http://localhost:8085/api/v1/strategy/?gameType=BETTING_MINI`
- `curl http://localhost:8085/api/v1/strategy/` (no param)
Expected: the betting strategy set (RANDOM + 8 progressions = 9).
Actual: both return the identical 9-strategy list
`[RANDOM, MARTINGALE_CLASSIC_CAUTIOUS, MARTINGALE_CLASSIC_AGGRESSIVE, PAROLI_CAUTIOUS,
PAROLI_AGGRESSIVE, DALEMBERT_CAUTIOUS, DALEMBERT_AGGRESSIVE, FIBONACCI_CAUTIOUS,
FIBONACCI_AGGRESSIVE]`. No-param defaults to the betting set (backward-compat preserved).
Result: PASS

### (c) GET /api/v1/strategy/?gameType=TAI_XIU → empty list
Command: `curl http://localhost:8085/api/v1/strategy/?gameType=TAI_XIU`
Expected: empty list.
Actual: `[]`.
Result: PASS

### (d) Create + start a SLOT group with slotStrategyId=RANDOM; bots AUTH, subscribe, spin
Setup:
- Reused existing SLOT game `c5a22c44-a848-4ec7-9136-342ed8e5cdea`
  (gameType=SLOT, gameId=204, brand G3 / product P_116 TIP, pluginName "Tip").
- `POST /api/v1/bot-group/` body included `"slotStrategyId":"RANDOM"` (botCount=2,
  env TIP `ad4e7948-fe24-4ef3-bd73-81f8956a94f0`, prefix `slotrnd`, minBet/maxBet 500).
- Create returned HTTP 200; group id `ec759951-a05c-4092-9dbd-9a1ce4e522fa`; the
  response echoed `"slotStrategyId":"RANDOM"` — the nullable group-level field persists.
- `POST .../ec759951-…/start` → HTTP 200.
Expected: bots come up, AUTH `[1,"MiniGame",…]`, subscribe to slotMachinePlugin, get
the 1300 response, and SPIN.
Actual:
- Both bots reach `CONNECTION_AUTHENTICATED`, `connected:true`, 2/2 connected, 0 dead.
- WS AUTH in the MiniGame zone (one per bot):
  ```
  10:25:47.564 AUTH [1,"MiniGame","","",{"accessToken":"189-cc429527…","agentId":"1","reconnect":false}]
  10:25:47.584 AUTH [1,"MiniGame","","",{"accessToken":"189-5768343c…","agentId":"1","reconnect":false}]
  ```
- Subscribe routes to the fixed extension, server responds:
  ```
  [SENT]     ["6","MiniGame","slotMachinePlugin",{"cmd":1300,"gid":204}]   (per bot)
  RECEIVED cmd 1300 count = 2 (one per bot)
  ```
- Both bots then SPIN (cmd 1302); totalBetsPlaced grew 18 → 36 per bot during observation.
- Health shows `"strategyId":"RANDOM"` on BOTH bots — the group-level slotStrategyId=RANDOM
  flowed into the slot bots at build time.
Result: PASS

### (e) RANDOM strategy took effect — per-spin bet amounts VARY across the server bet set
Expected: with slotStrategyId=RANDOM, the per-line `b` (before ×numLines) should VARY
across {500,1000,2000,5000,10000} rather than being constant (FIXED would always bet the
smallest, b=500).
Actual: SENT 1302 frames per bot show `b` spanning ALL FIVE server bet tiers:
```
slotrnd1:  b=500 ×2,  b=1000 ×5,  b=2000 ×8,  b=5000 ×6,  b=10000 ×5
slotrnd2:  b=500 ×3,  b=1000 ×5,  b=2000 ×5,  b=5000 ×7,  b=10000 ×6
```
Corroborating: at 18 bets each, the two bots had DIFFERENT totalBetAmount
(slotrnd1 = 1,550,000; slotrnd2 = 2,200,000) — impossible under a constant FIXED bet,
expected under RANDOM. (A FIXED/null group would emit b=500 on every spin → a single
value and identical per-bot totals.) RANDOM is unambiguously active.
Result: PASS

### (f) No slot ERROR/DEAD; no betting-mini regression; observability UP
Actual:
- No slot ERROR/DEAD lines since deploy; app-wide ERROR count since deploy = 0.
- Betting-mini groups (116 Demo group, XD game test, Fruit shop Bots) all ACTIVE and
  actively spinning in the last 60s (cmd 8002 Bau Cua, cmd 9002 Fruit Shop, cmd 3002
  updateBet flowing). No regression from the strategy-selection change.
- Observability stack all UP (grafana 200, prometheus 200, loki 200).
Result: PASS

## Staging state left behind

- Previous smoke group `74feeaed-09a5-48db-8f05-6822d76e0773` (SlotSmokeGroup): was
  ACTIVE on boot (auto-started); **STOPPED** (`POST .../stop` → HTTP 200) to leave
  staging clean, as instructed.
- New RANDOM group `ec759951-a05c-4092-9dbd-9a1ce4e522fa` (SlotRandomGroup5,
  slotStrategyId=RANDOM): **left RUNNING** (ACTIVE, 2/2 connected, spinning) so the
  RANDOM strategy can be observed live. Stop via
  `POST /api/v1/bot-group/ec759951-a05c-4092-9dbd-9a1ce4e522fa/stop` when done.
- Pre-existing betting-mini ACTIVE groups (116 Demo group, XD game test, Fruit shop
  Bots) left running (their normal state, untouched by this deploy).
- Idle leftovers from earlier retries (SlotSmokeGroup3 STOPPED, duplicate SLOT game
  143b2e64) remain; harmless. bot-manager Up (healthy). Observability UP.

## Verdict

PASS — the strategy-selection feature works on staging. The gameType-parameterized
listing returns [FIXED, RANDOM] for SLOT, the 9 betting strategies for BETTING_MINI
and for the no-param call (backward-compat), and [] for TAI_XIU. A SLOT group created
with slotStrategyId=RANDOM persists the field, propagates strategyId=RANDOM into both
slot bots, and those bots AUTH, subscribe to slotMachinePlugin, receive the 1300
response, and SPIN with per-line bet amounts varying across all five server tiers
{500,1000,2000,5000,10000} — proving the RANDOM strategy is active (FIXED would be a
constant b=500). No slot ERROR/DEAD, no betting-mini regression, observability UP.

Path note for the UI/API consumer: the listing endpoint is `/api/v1/strategy/`
(trailing slash) — the no-slash form 404s as a static-resource miss. Consistent with
the other controllers' trailing-slash mappings; flagged here so callers use the
correct path.
