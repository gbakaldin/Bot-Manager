# Release — AVIATOR_BOT

Mode: bot
Target: Bot-1 (staging) only
Branch: `feature/aviator-bot` @ e7fbe93 (worktree `.claude/worktrees/aviator-bot`, cut from `feature/cashout-bot`, which Bot-1 ran before this release)
Image: `vingame-bot:latest` = `a843eaddbd39` (built 2026-10-06T08:24Z). bot.tar md5 `cc528265f2c05db18e2588f0ee3a469b`, 396,356,608 bytes. The md5 on the box matches.
Date: 2026-10-06T08:26:10Z (recreate)

## Verdict

**PASS.** Build, ship, deploy and smoke all pass. V-0 through V-11 pass, with AM-1 used as
V-5's registration gate. The new CRASH group bets, cashes out on its own rocket, loses on the
other, and auto-deposits nothing. Both incumbent CASHOUT groups on the same env are still ACTIVE
and their cash-out counters keep rising.

## Build

- `mvn clean install` (JDK 21.0.2): PASS, about 2m40s (08:20:53Z to 08:23:33Z). **3,216 tests, 0 failures, 0 errors, 0 skipped.** These were summed from the surefire reports of all modules.
- `docker build --no-cache --platform linux/amd64`: PASS. It used `DOCKER_CONFIG` pointing at an empty-auths config, the workaround for the credential-helper hang.
- `docker save`: PASS (396,356,608 bytes).

## Ship

- `sftp put bot.tar`: PASS. The remote md5 matches.
- `prometheus/alerts.yml`: this is the only deploy-relevant file that changed since `feature/cashout-bot`. Phase 4 adds `CrashBetsUnacked`. `docker-compose.yml` and `logging/log4j2.properties` on the box are byte-identical to the branch.
  - The live file was backed up to `prometheus/alerts.yml.bak-20261006`, then the new one was put with sftp. The md5 matches on the host and inside the container.
  - `promtool check rules`: `SUCCESS: 29 rules found`.
  - Prometheus was reloaded with `docker kill -s HUP`. `/api/v1/rules` now lists `CrashBetsUnacked`.

## Deploy (bot-manager-only recreate, as in CASHOUT_BOT / BOT_PROVISIONING)

- Rollback tag: `vingame-bot:rollback-20261006` = `3a1bbf981a0e`, the image Bot-1 ran until now (the CASHOUT release).
- `docker load -i bot.tar`: PASS. `latest` is now `a843eaddbd39`.
- `docker compose up -d --no-deps --force-recreate bot-manager`: PASS. Nothing else was recreated. Grafana, Prometheus, Alertmanager, promtail, Loki, Mongo, node-exporter and both shims stayed "Up 4 days".
- `deploy.sh` was not run. No compose, env or secrets change was needed, and a bot-manager-only recreate is the less disruptive path. `.env` and `secrets.env` were not touched.

Rollback:
```
ssh Bot-1 'cd /home/sgame/bot-java && docker tag vingame-bot:rollback-20261006 vingame-bot:latest && docker compose up -d --no-deps --force-recreate bot-manager'
# optional, alert rule only:
ssh Bot-1 'cd /home/sgame/bot-java && cp prometheus/alerts.yml.bak-20261006 prometheus/alerts.yml && docker kill -s HUP bot-java-prometheus-1'
```

## Smoke test

- `docker ps`: `Up 53 seconds (healthy)` at 08:27:05Z. PASS.
- `Started Starter in 4.48 seconds` at 08:26:17Z. PASS.
- `Bot Manager startup: 8 bot groups queued for daisy-chained start`. PASS.
- Chain completion (non-blocking): `startup complete. 8 bot groups running (8 started by this chain)` at 08:26:22Z.
- VipTalk rooms are `[P_114, P_116, P_119]`, unchanged. There were 0 ` ERROR ` lines in `docker logs` at +23 min.
- Observability: Grafana `/api/health` 200, Loki `ready`, Prometheus `/-/ready` 200.

## Plan verification (`docs/plans/AVIATOR_BOT.md` § Verification + Amendment AM-1..AM-3)

`BASE=http://localhost:8080`, `ENV=8ca14218-c98e-4734-a79e-c53732626337` (119 Staging Club).
The group was started at 08:28:42Z. The "15 min" reading was taken at 08:44:34Z, and the "+5 min"
reading at 08:49:48Z.

### V-0: target env is the Club env
Command: `curl -s $BASE/api/v1/environment/$ENV`
Expected: `P_119`, `wss://w79.sgame.club/websocket_mini`.
Actual: name `119 Staging Club`, `productCode.code` `"119"`, `webSocketMiniUrl` `wss://w79.sgame.club/websocket_mini`.
`productCode` serialises as an object (`{"code":"119","name":"WIN79",…}`), not as the string `P_119`
that the plan's `jq -r .productCode` assumed. It is the same product. **PASS**

### V-1: app is up
Command: `curl -s $BASE/actuator/health`
Expected: `UP`, 200. Actual: `UP`, 200. **PASS**

### V-2: registry line
Command: `docker logs bot-java-bot-manager-1 2>&1 | grep "MessageTypesRegistry initialized"`
Expected: one line. Everything up to `CASHOUT 1 products [119]` identical to the baseline, and the line ends `, CRASH 1 products [119]`.
Actual: 1 line.
- Pre-deploy: `BETTING_MINI 6 products [097, 098, 114, 116, 118, 119], TAI_XIU 3 products [114, 116, 119], SLOT provider SlotMessageTypesImpl, CASHOUT 1 products [119]`
- Post-deploy: the same string plus `, CRASH 1 products [119]`.

**PASS**

### V-3: type listed
Command: `curl -s $BASE/api/v1/game/types | jq -r '.[].code' | grep -cx CRASH`
Expected: 1. Actual: 1. The full list is `BETTING_MINI, SLOT, TAI_XIU, CARD_GAME, UP_DOWN, CASHOUT, CRASH`. **PASS**

### V-4: game record
No `aviatorPlugin` game existed on the env before this step. The env had Tai Xiu KM, Balloon and Soccer only.
`POST $BASE/api/v1/game/G4/P_119/$ENV` with `{"name":"Aviator","gameType":"CRASH","pluginName":"aviatorPlugin","offset":1700,"md5":false}`
returned HTTP 200, `gameType:"CRASH"`, `offset:1700`.
**`$GAME = e22cce9d-4290-459a-9d7f-d4cc2f955071`**. **PASS**

### V-5: group registers, funds without auto-deposit, starts and subscribes (with AM-1)
Command: `POST $BASE/api/v1/bot-group/` with name `Aviator 119 Club`, 3 bots, prefix `w79avt`,
password `Bot@12345`, `autoDepositEnabled:false`, `initialDeposit:20000000`, `minBet:20000`,
`maxBet:200000`, `betIncrement:5000`.
- The response was 200 with `autoDepositEnabled:false` and `targetStatus:"REGISTRATION_PENDING"`. **`$G = 13c2b858-8131-4426-a496-bf1dc1ebc381`**.
- AM-1 poll: `targetStatus` left `REGISTRATION_PENDING` with `registeredCount 3, namedCount 3, depositedCount 3` and no `registrationError`, about 11 s after creation. The log line was `registration complete, 3/3 accounts (3 named), 3 funded x 20000000 = 60000000`.
- Scoped DEBUG armed: `POST /api/v1/logging/debug/$G?minutes=60` returned 200, expiring 09:28:42Z.
- `POST /$G/start` returned 200 (`actualStatus: STARTING`).
- Health at +90 s (08:30:15Z): **`connectedBots 3, deadBots 0`, 3 × `CONNECTION_AUTHENTICATED`**. The detail log shows `subscribed — stake ladder [20000, 25000, …]`.
- There were 0 `subscribed=false` lines and 0 `not permitted` frames. 119 accepted the `aviatorPlugin` subscribe on the Club socket.

**PASS**

### V-6: no auto-deposit
Command: `curl -s "$BASE/actuator/metrics/bot_auto_deposits_total?tag=botGroupId:$G&tag=outcome:success"`
Expected: `0.0`, and a 404 is a FAIL. Actual: **HTTP 200, `0.0`** at both +16 min and +21 min. **PASS**

### V-7: bets acked, cash-outs acked, losses counted
Expected: bets ≥ 30, amount ≥ 600,000, winnings > 0, all three outcome series 200, cashout > 0, unacked share < 5%.

| Metric | 08:40:57Z (+12 min, interim) | 08:44:34Z (+16 min) | 08:49:48Z (+21 min) |
|---|---|---|---|
| `bot_bets_placed_total` | 42 | **57** | 75 |
| `bot_bet_amount_total` | 5,090,000 | **6,715,000** | 8,225,000 |
| `bot_winnings_total` | 10,780,200 | **14,053,349** | 15,791,549 |
| `bot_crash_bets_total{outcome=cashout}` | 29 | **39** | 51 |
| `…{outcome=crash}` | 13 | **15** | 22 |
| `…{outcome=unacked}` | 0 (HTTP 200) | **0** (HTTP 200) | 0 (HTTP 200) |

- unacked share: 0 / 54 = **0%**.
- Informational, not a gate (F-5): `crash / (cashout + crash)` = 15/54 = **0.28**, and 22/73 = **0.30** at +21 min. Both are inside [0.10, 0.45] and match the expected ≈ 0.27.
- There are 3 more bets placed than terminal outcomes at +16 min (57 vs 54). That is consistent with bets in flight at the moment of reading, plus the one live bet the reconnect abandoned without an outcome (see V-11).

**PASS**

### V-8: cash-out payouts are gross and match the ack
Command: `grep -h "first crash cash-out ack" logs/detail/detail*.log | head -3`, and the same for `first crash loss`.
Actual:
- `w79avt1: eid=1, b=140000, odd=2.88, wm=403200.0`. 140,000 × 2.88 = 403,200, exact.
- `w79avt3: eid=1, b=130000, odd=3.97, wm=516100.0`. 130,000 × 3.97 = 516,100, exact.
- `w79avt2: eid=1, b=130000, odd=3.78, wm=491400.0`. 130,000 × 3.78 = 491,400, exact.
- Losses: `w79avt2 eid=2 targetH=427 multiplierH=286 crashed=true`, `w79avt1 eid=2 targetH=400 multiplierH=286 crashed=true`, `w79avt3 eid=2 targetH=383 multiplierH=286 crashed=true`.

Sanity check that each bot reads its own rocket: every loss is on eid 2 (Neytiri) at multiplier 2.86, the
fixed staging crash point. The targets were 3.83 to 4.27, above it. Every cash-out sampled is on eid 1
(Jake, crashes at 11.59) at a target below 11.59. **PASS**

### V-9: rounds reset
Command: `curl -s "$BASE/actuator/metrics/bot_messages_total?tag=botGroupId:$G&tag=cmd:crashRoundEnd"`
Expected: ≥ 45 at 15 min, and +≥ 12 five minutes later.
Actual: **57** at +16 min, **75** at +21 min (+18). **PASS**

### V-10: no WARN/ERROR storm, no per-bet INFO
Commands: the two `grep "\"botGroupId\":\"$G\"" logs/console.log` counts.
Expected: < 20 and flat. WARN/ERROR ≤ 3.
Actual: 9 and 9 (flat between +16 and +21 min). WARN/ERROR: **1**. That one is
`Bot w79avt1: WS disconnected — starting retrial flow` at 08:43:59Z, the single server-side close
described in V-11. **PASS**

### V-11: no reconnect churn, no extra HTTP
Expected: reconnects ≤ 3 (or 404), watchdog absent or 0, budget window < 100 and flat (±20).
Actual:
- `bot_reconnects_total`: **1**.
  - The server closed `ws-w79avt1` at 08:43:59Z (`Channel became inactive`, remote `w79.sgame.club`).
  - The bot reconnected on attempt 1 and re-subscribed at 08:44:08Z.
  - This is background behaviour on the shared 119 socket, not something specific to this group. In the same detail file the sibling 119 groups show `w79ktx` 11, `w79scr` 11, `w79tx` 17 and `w79xd` 17 disconnects.
- `bot_watchdog_expired_total`: 404 (absent).
- `gateway_budget_window_requests{environmentId=$ENV}`: **4** at +16 min and **3** at +21 min. That is flat and far below 100. The window is shared with the KM probe and the two CASHOUT groups.

**PASS**

## Regression: incumbent CASHOUT groups on the same env

The counters reset with the JVM restart, so the comparison is between readings taken after the deploy.

| Group | Status | `bot_cashout_bets_total` (all outcomes) at 08:40:57 / 08:44:34 / 08:49:48 |
|---|---|---|
| Balloon `e051433a-5bf7-40cb-82fb-b1a9378f47c5` | ACTIVE | 152 / 193 / 248 |
| Soccer `52b733e6-d3b5-47b5-9eea-37820f34be68` | ACTIVE | 114 / 144 / 188 |

The pre-deploy cumulative counters were Balloon cashout/burst/timeout 10,047/3,488/45 and Soccer
6,949/2,455/1,377. Both groups are still betting. The 119 Club Tai Xiu KM probe (`c7d19d23…`) is ACTIVE.
The stopped proxy groups `a55e7d94…` and `d0fdd8ec…` were left alone and are still STOPPED.
**PASS**

## Observations (not gates)

1. **RTP is far above 1 on staging.** At +21 min the group had staked 8.225M and won 15.79M (about 1.9×). That follows from the fixed staging crash points: Jake always runs to 11.59, so every Jake bet that cashes out below it wins. This says nothing about prod and is not a gate (see the plan, F-5).
2. **V-0 command shape.** `productCode` is an object in the environment DTO, so the plan's `jq -r '.productCode'` prints JSON rather than `P_119`. A future plan should use `.productCode.code` and expect `119`.
3. **Another scoped-DEBUG scope was open while this ran.** It was auto-escalated for slot group `2bf237bd…` at 08:41:29Z ("5 reconnects in 5m") and expires 08:56:29Z. It is unrelated to this release (the Slot group 120 reconnect churn is older than this release).
4. The plan's V-5 example prefix was `w79avi`. `w79avt` was used, as instructed.

## Money moved (119 staging)

- Registration funding: 3 × 20,000,000 (`w79avt1..3`) = **60,000,000**.
- Auto-deposit: **none** (`autoDepositEnabled:false`, and V-6 reads 0).
- Play so far: about 8.2M staked and 15.8M won (staging crash points are fixed).

## State left on Bot-1

- Image `a843eaddbd39` is live. The rollback tag is `vingame-bot:rollback-20261006` (= `3a1bbf981a0e`). The previous tag `rollback-20261005b` is kept.
- `prometheus/alerts.yml` carries `CrashBetsUnacked`. The backup is `alerts.yml.bak-20261006`.
- Game `e22cce9d-4290-459a-9d7f-d4cc2f955071` (Aviator, CRASH, `aviatorPlugin`, 1700) on the 119 Club env.
- Group `13c2b858-8131-4426-a496-bf1dc1ebc381` ("Aviator 119 Club", 3 bots, `w79avt`) is **ACTIVE** and stays as the 119 staging Aviator group. Its scoped DEBUG expires on its own at 09:28:42Z.
