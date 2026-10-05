# Release — CASHOUT_BOT

Mode: bot
Target: Bot-1 (staging) only
Branch: `feature/cashout-bot` @ b12c7b9 (worktree `.claude/worktrees/cashout-bot`, built on `feature/bot-provisioning`)
Image: `vingame-bot:latest` = `3a1bbf981a0e` (built 2026-10-05T09:59Z), bot.tar md5 `266e0003325b016440ace5d511c8e39e` (396,285,440 bytes, md5 matches on the box)
Date: 2026-10-05T10:02:19Z (recreate)

## Verdict

**FAIL. The deploy is healthy, but the plan verification is blocked by the server.**

The build, ship, deploy and smoke all pass. V-1..V-4 pass: the image is live, the registry
carries `CASHOUT 1 products [119]`, the type is listed and both Game records exist.
V-5..V-8 fail for one reason outside this code. **119 staging rejects the cash-out subscribe
for our bot accounts:**

```
IN  [7,2,"Invalid request: access to this game is not permitted",{"cmd":1500}]   (Balloon, all 3 bots)
IN  [7,2,"Invalid request: access to this game is not permitted",{"cmd":2500}]   (Soccer,  all 3 bots)
```

AUTH is accepted (`[1,true,0,…,"MiniGame",null]`) and the `cmd:100` handshake arrives
(gold = the funded balance). The subscribe then gets a type-7 error about 5 s later and no
`X500` comes. So no bot ever reaches `onSubscribe`. Without it there is no stake set, no
bet, and no terminal frame to capture. The rejection names the zone/plugin/cmd we sent
(`MiniGame` / `balloonPlugin` / 1500), which suggests the frame shape is right and the
account/game is gated server-side: game not enabled on 119 staging, or not enabled for bot
(`type_id 3`) accounts. **Only the back office can lift it.** It looks like the per-brand
whitelisting class of problem: the same rejection on both games, and the incumbent 119
groups (Tai Xiu / Xoc Dia / Club TX KM) play normally on the same socket.

## Build

- `mvn clean install` (JDK 21.0.2): PASS, 2m38s. **3,009 tests, 0 failures, 0 errors, 0 skipped**
  (summed from surefire reports).
- `docker build --no-cache --platform linux/amd64`: PASS (`DOCKER_CONFIG` with empty auths, the
  credential-helper hang workaround).
- `docker save`: PASS (396,285,440 bytes).

## Ship

- `sftp put bot.tar`: PASS (remote md5 identical).

## Deploy (bot-manager-only recreate, as BOT_PROVISIONING)

- Rollback tag: `vingame-bot:rollback-20261005b` = `b5710416bdd2` (the image Bot-1 ran until now).
- `docker load -i bot.tar`: PASS. `latest` is now `3a1bbf981a0e`.
- `docker compose up -d --no-deps --force-recreate bot-manager`: PASS. Nothing else was recreated.
  Grafana, Loki, Prometheus, Alertmanager, Mongo, promtail and both shims stayed "Up 4 days".
- `secrets.env` / `.env` / compose / `logging/` not touched. This branch changes no compose or logging file.
  `deploy.sh` was not needed.

Rollback:
```
ssh Bot-1 'cd /home/sgame/bot-java && docker tag vingame-bot:rollback-20261005b vingame-bot:latest && docker compose up -d --no-deps --force-recreate bot-manager'
```

## Smoke test

- `docker ps`: `Up 33 seconds (healthy)` at 10:02:54Z: PASS.
- `Started Starter in 4.059 seconds` at 10:02:25Z: PASS.
- `Bot Manager startup: 6 bot groups queued for daisy-chained start`: PASS.
- VipTalk rooms `[P_114, P_116, P_119]` (unchanged). 0 ERROR lines in `docker logs` at +28 min.
- Observability: Grafana `/api/health` 200, Prometheus ready 200 (`up{job="bot-manager"}=1`,
  `up{job="node"}=1`), Loki `ready`, Prometheus → `alertmanager:9093` active.
- Incumbents after the deploy (same as the pre-deploy baseline):

| Group | Bots | Connected / dead | Status mix |
|---|---|---|---|
| Coins (114) | 100 | 100 / 0 | 100 CONNECTION_AUTHENTICATED |
| Zic Zac (114) | 100 | 100 / 0 | 100 CONNECTION_AUTHENTICATED |
| 119 Tai Xiu probe | 10 | 10 / 0 | 10 CONNECTION_AUTHENTICATED |
| 119 Xoc Dia probe | 10 | 10 / 0 | 10 CONNECTION_AUTHENTICATED |
| 119 Club Tai Xiu KM probe | 10 | 10 / 0 | 10 CONNECTION_AUTHENTICATED |
| Slot group 120 | 20 | 20 / 0 | 13 STARTED / 7 AUTHENTICATING_CONNECTION (same mix as before the deploy) |

  The `ws-w79xd*: Error processing message` ERRORs in the detail log are older than this
  release (5-19 per 2 h file back to 2026-10-04 23h) and are unrelated.

## Plan verification (`docs/plans/CASHOUT_BOT.md` § Verification + Amendment)

`ENV=d005157f-4dfb-479d-a39a-36a3892439b7`. The groups were created and started one after another.
The gateway window peaked at 84/900.

### V-1: the app is up
Command: `curl -s -o /dev/null -w '%{http_code}' $BASE/actuator/health`
Expected: 200, status UP. Actual: 200, `"status":"UP"`. **PASS**

### V-2: registry line
Command: `docker logs bot-java-bot-manager-1 | grep "MessageTypesRegistry initialized"`
Expected: exactly one line. BETTING_MINI/TAI_XIU the same as the baseline, plus a trailing `CASHOUT 1 products [119]`.
Actual: 1 line: `BETTING_MINI 6 products [097, 098, 114, 116, 118, 119], TAI_XIU 3 products [114, 116, 119], SLOT provider SlotMessageTypesImpl, CASHOUT 1 products [119]`.
The pre-deploy baseline was the same string without the CASHOUT segment. **PASS**

### V-3: game type listed
Expected 1. Actual: 1 (`{"code":"CASHOUT","displayName":"Cash-out"}`). **PASS**

### V-4: games created
Neither game existed before. Both POSTs returned 200 with `gameType:"CASHOUT"`:
- Balloon `8e913edf-9e6b-46b2-9303-54b7cfbf52b5`: `balloonPlugin`, offset 1500
- Soccer `698365e5-30cc-43de-a7c9-9ae8696b7b42`: `soccerPlugin`, offset 2500

**PASS**

### V-5: groups start, authenticate and subscribe
Groups (3 bots each, `autoDepositEnabled:true`, password `Bot@12345`):
- **Balloon** `a55e7d94-a6d0-42d4-939b-db85668061e7`, prefix `w79bln`, min/max 1,000/100,000,
  `initialDeposit` 20,000,000. Result: `registration complete, 3/3 accounts (3 named), 3 funded x 20000000`.
- **Soccer** `d0fdd8ec-70dd-44df-994e-b45ae0c6cbcb`, prefix `w79scr`, min/max 1,000/1,000,000,
  `initialDeposit` 50,000,000. Result: `registration complete, 3/3 accounts (3 named), 3 funded x 50000000`.

Expected: start 200, and within 90 s 3/3 `CONNECTION_AUTHENTICATED`, 0 dead.
Actual: start 200 for both. Health shows `connectedBots 3, deadBots 0` but the status is **3/3 `STARTED`**
and stays there (still the same at +25 min). The raw frames, captured by raising
`com.vingame.websocketparser.VingameWebSocketClient` to DEBUG for about 25 s and then resetting it to INFO, show
the server's type-7 rejection above for every bot on both games. **FAIL. The cause is the server gate, not this code.**

### V-6: bets placed and confirmed (15 min)
Expected: `bot_bets_placed_total > 50`, amount > 0, winnings > 0.
Actual (10:25Z, both groups): 0.0 / 0.0 / 0.0. **FAIL** (blocked by V-5)

### V-7: outcome mix; timeout series pre-registered
Expected: cashout > 0, burst > 0, ratio in [0.20, 0.55], timeouts < 5%, `timeout` series exists.
Actual: `bot_cashout_bets_total` returns **404 for every tag combination**. Pre-registration
(`initCashoutSeries`) runs in `onSubscribe`, which never ran. **FAIL** (blocked by V-5)

### V-8: winning terminal frame shape (OI-1)
Expected: at least one `first cash-out terminal frame` and one `first burst terminal frame` line.
Actual: 0 and 0 across all `detail*.log`. **FAIL** (blocked by V-5). **OI-1 stays open.** There
is nothing to report on `blS`, `crd` vs `b × odds`, the `unmapped` map, progress-frame cadence or
bet duration, because no bet was placed.

### V-9: no per-bet INFO; aggregate DEBUG present
Command: `grep -c '"botGroupId":"$G"' logs/console.log`, plus the `CashoutWindow` count in detail.log.
Expected: console < 20, lifecycle only. `CashoutWindow` > 0 (Phase 4).
Actual:
- Balloon: 21. That is 9 start-lifecycle lines plus 12 lines from the diagnostic `/restart` I ran.
  Every line is lifecycle (registration complete, strategy mix, executor, initialized, periodic
  logout, started, auto-deposited, and the stop/start pair).
- Soccer: 9.
- This half passes in substance but proves nothing, because there were no bets.
- `CashoutWindow`: 0. **Expected: Phase 4 is not implemented.**

**Console half: PASS (vacuous). CashoutWindow half: expected FAIL, not a blocker.**

### V-10: request budget and reconnect churn
Expected: window < 100 and flat (±20) over 5 min. Reconnects ≤ 3 or absent.
Actual: `gateway_budget_window_requests` for 119 was 0.0 at 10:25Z and 4.0 at 10:30Z.
`bot_reconnects_total` is absent (404) for both groups. **PASS.** This is also vacuous for the bet
path. It does show that a wedged cash-out bot makes no HTTP calls and does not churn.

## Findings for the developer

1. **Blocker (environment):** 119 staging answers the cash-out subscribe with
   `[7,2,"Invalid request: access to this game is not permitted",{"cmd":<offset>}]` for freshly
   registered bot accounts, on both `balloonPlugin` and `soccerPlugin`. Ask the back office to
   enable Balloon and Soccer on 119 staging for the bot host / bot accounts, then re-run V-5..V-10.
   The groups and accounts exist, so a re-test is one `/start` (or `/restart`) per group.
2. **Bot-side gap: a rejected subscribe is silent.** `CashoutBot`'s pipeline waits for
   `cmd(offset+0)` and does not handle the type-7 error frame. The bot sits at `STARTED`
   forever with no WARN and no metric, and `/health` reports it as connected with 0 dead.
   No timeout or retry fired in 25 minutes. That is the same "wedged with nothing to time it out"
   shape the `onSubscribe` comment warns about, one stage earlier. Consider handling
   `[7,…,{"cmd":offset}]` with one WARN per bot per subscribe and a status or metric that
   `/health` can show.
3. **Auto-deposit makes `initialDeposit` redundant here.** With `autoDepositEnabled:true`, each bot
   immediately topped up 1,000,000,000 on start, because its 20M/50M balance was below the
   100M minimum. Six `[BotDeposit]` calls of 1e9 each; balances are now about 1.02B and 1.05B.
   That is existing behaviour, not CASHOUT's, but the plan's V-5 body combined with
   `initialDeposit` moves 6B of staging money that is never staked. Separately, the
   `GroupLifecycleAggregator` line has two cosmetic problems. It renders the group name as `(?)`.
   And its "total" is the sum of *shortfalls below the minimum* (240,000,000 / 150,000,000), not
   the 3,000,000,000 per group actually deposited, which is misleading.

## Money moved (119 staging)

- Registration funding: 3 × 20,000,000 (`w79bln1..3`) + 3 × 50,000,000 (`w79scr1..3`) = 210,000,000.
- Auto-deposit on start: 6 × 1,000,000,000 = 6,000,000,000. Balloon was restarted once, but no second
  deposit, because the balance was already above the minimum.
- Nothing was staked. The accounts remain upstream.

## State left on Bot-1

- Image `3a1bbf981a0e` live. Rollback tag `rollback-20261005b`.
- **Both test groups are still ACTIVE, connected and idle (wedged at STARTED).** I tried
  `POST /{id}/stop` once it was clear they could never bet, and the permission system denied it.
  They are harmless: no HTTP, no bets, pings only. But `/health` shows them as healthy and they
  will auto-start (6 logins) on every JVM restart. **The user should decide** whether to stop them:
  `curl -X POST localhost:8080/api/v1/bot-group/{a55e7d94-a6d0-42d4-939b-db85668061e7,d0fdd8ec-70dd-44df-994e-b45ae0c6cbcb}/stop`.
  The other option is to leave them for a `/restart` once the back office enables the games.
- `com.vingame.websocketparser.VingameWebSocketClient` is back to INFO: `configuredLevel` null,
  effective INFO, checked after each of the two 20-25 s windows.
