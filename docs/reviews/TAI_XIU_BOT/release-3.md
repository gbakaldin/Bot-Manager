# Release — TAI_XIU_BOT (re-deploy 3)

Mode: bot
Branch: main
Image: vingame-bot:latest (built at 2026-06-24T11:17:39Z)
Date: 2026-06-24T11:25:49Z
Commit deployed: `79299cb` (HEAD of main)
Previous deploy commit: `4f80d63` (release-2.md)

This is the final re-deploy of the Tai Xiu feature to staging (Bot-1). The change
since `4f80d63`: Tai Xiu now auto-defaults to its 2 entries (Tài/Xỉu) when a
Tai Xiu game has no option config (`79299cb`), fixing the
`IllegalStateException: has neither optionAffinities nor legacy numberOfOptions`
seen on the last two deploys. A Tai Xiu game now needs NO manual option config.

## Build

- `mvn clean install`: PASS (~23s; Tests run: 883, Failures: 0, Errors: 0, Skipped: 0; BUILD SUCCESS)
- `docker build --no-cache --platform linux/amd64`: PASS (image sha256:d886ba8cc2c5; layers 7–10 executed, target/Bot-1.0.jar copied from fresh build; 379MB)
- `docker save -o bot.tar`: PASS (392,799,744 bytes)

## Ship

- `sftp put bot.tar` → Bot-1:/home/sgame/bot-java: PASS (exit 0; only post-quantum SSH advisory noise on stderr)

## Deploy

- `docker compose down`: PASS (full single-compose project torn down: bot-manager + mongo + loki + promtail + prometheus + grafana)
- `docker image rm vingame-bot:latest`: PASS (prior image untagged + 12 layers deleted)
- `docker load -i bot.tar`: PASS (Loaded image: vingame-bot:latest)
- `docker compose up -d`: PASS (network recreated; mongo healthy → bot-manager started; observability stack started)

## Smoke test

- `docker ps` shows bot-manager healthy: PASS ("Up (healthy)")
- Spring Boot ready log: PASS — `Started Starter in 6.596 seconds`
- Auto-start log: PASS — `Bot Manager startup complete. 4 bot groups running`
- Co-located observability stack (per bot-1 single-compose memory note): PASS
  - grafana `/api/health` → 200
  - prometheus `/-/healthy` → 200
  - loki `/ready` → 200 (briefly 503 immediately after restart while warming, then 200 within ~6s)
  - All 6 containers Up; bot-manager and mongo (healthy).

## Plan verification (docs/plans/TAI_XIU_BOT.md § Verification)

### Step 1: App boots with the Tai Xiu validator and strategy listing wired
Command: `curl -s http://localhost:8080/actuator/health` and `grep "GameConfigValidatorFactory initialized" <app-log>`
Expected: HTTP 200 `{"status":"UP"}`; validator factory line includes TAI_XIU, count 5
Actual: health `{"status":"UP",...}` (mongo UP, ping UP); log line:
`GameConfigValidatorFactory initialized: registered 5 validators — [BETTING_MINI, SLOT, TAI_XIU, CARD_GAME, UP_DOWN]`
Result: PASS

### Step 2: Tai Xiu strategies are listed
Command: `curl -s 'http://localhost:8080/api/v1/strategy/?gameType=TAI_XIU'`
Expected: HTTP 200, non-empty array equal to gameType=BETTING_MINI response
Actual: 9-element array (RANDOM, MARTINGALE_CLASSIC_CAUTIOUS/AGGRESSIVE, PAROLI_CAUTIOUS/AGGRESSIVE, DALEMBERT_CAUTIOUS/AGGRESSIVE, FIBONACCI_CAUTIOUS/AGGRESSIVE) — byte-identical to the BETTING_MINI response
Result: PASS

### Step 3: A TAI_XIU game can be created via REST
Command: `POST /api/v1/game/G3/P_116` with body `{"name":"TaiXiuStagingVerify","gameType":"TAI_XIU","gameId":1,"pluginName":"taixiuPlugin"}` (brand/product supplied by path; **NO option config** — no optionAffinities, no numberOfOptions). Then `GET /api/v1/game/{id}`.
Expected: 200/201 echoing gameType:TAI_XIU; GET returns the same
Actual: created id `9d05c039-48ac-44c0-8658-cdb803329bb5`, echoed `gameType:TAI_XIU`, productCode resolved to {code 116, TIP}; GET returns identical record.
(Note: the body must NOT carry `productCode` as a string — the ProductCode enum @JsonCreator expects the path-supplied value; including it returns "Malformed request body". Path-only supply works.)
Result: PASS

### Step 4: A Tai Xiu bot group passes config validation and starts; bots reach init + auth
Command: create 2-bot group bound to game `9d05c039` on P_116/TIP env `ad4e7948` with valid grid config (minBet 10000, maxBet 500000, betIncrement 10000, maxTotalBetPerRound 10000000, minBetsPerRound 4, maxBetsPerRound 27, strategy RANDOM), then `POST /{id}/start`.
Expected: group passes validation and starts; bots reach CONNECTION_AUTHENTICATED (proving fixed-CMD subscribe matched onSubscribe).
Actual: group `401f4c63-369f-4f8d-8b64-7e41210d3fff` created and started.
- **Config validation PASSED** (valid grid accepted; group created).
- Both bots **authenticated** at `apigw-tipclub.sgame.us` (AUTHENTICATING → AUTHENTICATED, agency + auth + jwt tokens obtained).
- Both bots **initialized cleanly**:
  `BettingMiniGameBot initialized: game=TaiXiuStagingVerify, offset=0, options=2, md5=false, watchdog=180s, strategy=RANDOM`
  → **resolved exactly 2 options (Tài/Xỉu) with NO option config on the game** — this is the `79299cb` fix working.
- Both bots advanced AUTHENTICATED → CONNECTING → `VingameWebSocketClient ... Authenticated with token` → **reached the WS-connect step**.
- WS-connect then failed with `java.net.UnknownHostException: tipclubgw-sock.stgame.win: Name or service not known` — the known DNS env caveat (see below), NOT a code defect.
- CONNECTION_AUTHENTICATED (the WS subscribe-response state) was therefore NOT reached, because the socket host is unresolvable. This is the documented success-criterion fallback for this deploy.
Result: PARTIAL — init + auth + WS-connect reached with both prior crashes gone; CONNECTION_AUTHENTICATED blocked solely by the DNS env caveat. (Invalid-config 400 sub-check not separately exercised; the valid-config acceptance path passed.)

### Step 5: Rounds drive bets and end-game accounting
Command: `curl .../actuator/metrics/bot_bets_placed_total?tag=gameType:TAI_XIU` and `bot_bet_amount_total?tag=gameType:TAI_XIU`
Expected: counters > 0
Actual: no measurement (series absent) — no round ever started because WS-connect is DNS-blocked, so no StartGame/EndGame/bets occurred.
Result: NOT REACHABLE (DNS-blocked; not a code defect)

### Step 6: Winnings counter populated (RTP feeds)
Command: `curl .../actuator/metrics/bot_winnings_total?tag=gameType:TAI_XIU`
Expected: measurement >= 0
Actual: no measurement (series absent) — same DNS root cause as step 5.
Result: NOT REACHABLE (DNS-blocked; not a code defect)

### Step 7: No regression in BettingMini and no wedged Tai Xiu bots
Command: `grep -E "ERROR|marking DEAD" <app-log> | grep -iE "tai|xiu"`; observability re-verify
Expected: no Tai Xiu ERROR/DEAD lines (other than the env caveat); observability UP
Actual:
- Zero Tai Xiu ERROR/DEAD lines except the expected DNS `UnknownHostException`.
- Zero `IllegalStateException` of any kind in the whole run (0 matches for "neither optionAffinities"); zero offset NullPointerExceptions.
- Tai Xiu group status: targetStatus ACTIVE / actualStatus ACTIVE / playingStatus IDLE (not DEAD, not wedged).
- BettingMini live-betting spot-check not possible on this box: all 4 auto-started ACTIVE groups are on the same P_116/TIP env and hit the identical `tipclubgw-sock.stgame.win` DNS wall. Phase-1 seam-refactor regression is covered by the 883 green tests (incl. the full betting-mini suite) and by all BettingMini bots still initializing via the same unchanged `BettingMiniGameBot initialized` path with no new exceptions.
- Observability re-verified UP (grafana 200, prometheus 200, loki 200; all 6 containers up).
Result: PASS (no regression observed; no wedged/DEAD Tai Xiu bots; observability healthy)

## Prior-crash regression check (the point of this deploy)

| Prior crash | Status this deploy | Evidence |
|---|---|---|
| `IllegalStateException: has neither optionAffinities nor legacy numberOfOptions` (option config) | **GONE** | Bot inits with NO option config → `options=2`; 0 IllegalStateException in run |
| Offset NPE (NullPointerException on game.getOffset) | **GONE** | `offset=0` resolved cleanly; 0 offset NPEs in run |

Both crashes that blocked the last two deploys are eliminated. A Tai Xiu game with
no manual option config now reaches bot init (2 options, eids implicitly {1,2}),
authenticates, and proceeds to WS-connect.

## DNS / runtime status

`tipclubgw-sock.stgame.win` is **still unresolvable** from inside the container
(`docker exec ... getent hosts tipclubgw-sock.stgame.win` → exit 2). The auth host
`apigw-tipclub.sgame.us` resolves fine (which is why auth + registration succeed).
This is the unchanged, infra-side env caveat — NOT our code. It blocks WS-connect
for ALL staging groups on the P_116/TIP env (the 4 auto-started groups and the new
Tai Xiu verify group all fail bot creation at WS-connect with the same
`UnknownHostException`).

Consequence: full live betting (plan steps 5–6: bets / endgame / refund-aware RTP)
is **NOT reachable on staging** until DNS for `tipclubgw-sock.stgame.win` is fixed
on the host. The refund-aware accounting (AD-11) remains verified by the unit/stream
test suite (full/partial/zero-refund fixtures) that is green in the build.

## Verdict

PASS (deploy + smoke + both prior crashes eliminated)

The release pipeline, universal smoke (incl. observability), and the code-level
success criterion for this deploy all PASS: the option `IllegalStateException` and
the offset NPE are both gone, and a no-option-config Tai Xiu game reaches bot init
(2 options) + auth + WS-connect. Plan steps 1–3 PASS; step 4 PARTIAL (init/auth/
WS-connect reached, CONNECTION_AUTHENTICATED blocked only by the DNS caveat); steps
5–6 NOT REACHABLE (DNS-blocked, not a code defect); step 7 PASS. Full live-betting
verification stays blocked on the unchanged `tipclubgw-sock.stgame.win` DNS issue,
which is an infrastructure item outside this feature.

## Logs (key excerpts)

Clean Tai Xiu init (no option config, 2 options resolved):
```
11:23:57.527 BettingMiniGameBot [401f4c63.../1/TaiXiuStagingVerify] - BettingMiniGameBot initialized: game=TaiXiuStagingVerify, offset=0, options=2, md5=false, watchdog=180s, strategy=RANDOM
11:23:57.527 Bot [.../1/TaiXiuStagingVerify] - Bot txverify1: AUTHENTICATED → CONNECTING
11:23:57.527 VingameWebSocketClient [.../1/TaiXiuStagingVerify] - Client ws-txverify1: Authenticated with token 189-3bb0......
```

WS-connect blocked by DNS (env caveat, not code):
```
11:23:57.535 BotGroupBehaviorService [401f4c63...] - Failed to create bot 2/2 ... java.net.UnknownHostException: tipclubgw-sock.stgame.win: Name or service not known
```

Container DNS probe:
```
docker exec bot-java-bot-manager-1 getent hosts tipclubgw-sock.stgame.win  → exit 2 (no record)
docker exec bot-java-bot-manager-1 getent hosts apigw-tipclub.sgame.us     → 2606:4700:... (resolves)
```
