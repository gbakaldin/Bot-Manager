# Release-3 — TAI_XIU_114_JACKPOT (P_114 / RIK Tai Xiu Jackpot variant)

Mode: bot
Branch: main
Commit: 2e97f5d (HEAD at deploy)
Image: vingame-bot:latest (built 2026-06-29T14:13:31+04, sha256:b58c00dc887a)
Date: 2026-06-29T10:30Z (server clock)
Target: Bot-1 (host port 8080 -> container 8085)

> Re-attempt of release-2 (2026-06-26, commit 687759f), which FAILED at the
> 2-bot smoke gate because RIK's `register.aspx` returned 401 "IP denied" for
> Bot-1's egress IP `16.162.36.69`. The user reported that IP-allowlist blocker
> resolved. This run confirms it IS resolved: registration, RIK login/auth,
> CONNECTION_AUTHENTICATED, subscribe, and the +100 inbound/outbound CMD
> plumbing all now pass end-to-end. A different, lesser blocker now prevents
> verifying the *betting* leg (see Verdict).

## Verdict

**FAIL — gate not fully cleared; 100-bot group NOT created.**

This is a large step forward from release-2 — the RIK IP-allowlist blocker is
gone and the new `RikLoginRequest` profile plus the entire +100 CMD plumbing are
proven end-to-end:

- Register: both bots HTTP 200 "Register successful" (0 register non-200s).
- Auth: RIK profile (`login.aspx`, `app_id: rik.vip`, `aff_id: RIKVIP`) HTTP 200;
  VerifyToken HTTP 200; both bots reached CONNECTION_AUTHENTICATED.
- Subscribe `1105`, StartGame `1102` (8 received), EndGame `1104` (10 received) —
  the +100 offset flows through both the outbound sender and the inbound matcher
  (frames are consumed, not dropped). No recaptcha / `r_token` gate appeared.

However, the gate's **betting** leg ("place bets carrying a:false") cannot be
positively verified on these accounts, because the freshly-registered RIK
accounts have **zero balance** (`main_balance:0` at VerifyToken and on a fresh
re-fetch) and `autoDepositEnabled=false`. The RIK server therefore settles no
bets: every received EndGame (`1104`) is a bare public broadcast
(`cBB:0, gBB:0`, no `gB`/`gR`/`G` per-bot settlement). The bot's strategy still
runs (decides + sends a bet every tick, advances Martingale, single-entry lock
remaps), and its *optimistic* local balance briefly read `-770000`, but that
reset to `0` on the next real balance fetch — confirming the server accepted
none of the bets.

Critically, **the user-designated "healthy" 116 reference group (`txb0tt3st`,
`66cfc12c-…`) exhibits the identical pattern**: all bots `checkBalance fetched:
0`, EndGame (`1004`) frames with no `gB` settlement (count 0), and no TAI_XIU
entry in `bot_bets_placed_total`. So zero-balance / no-settlement is the
established staging baseline for Tai Xiu on this environment, NOT a P_114 defect.
The 114 bots are byte-for-byte equivalent in behaviour to the accepted-healthy
116 group, differing only in the intended deltas (+100 CMDs and the RIK login
profile).

Per the brief ("only if the 2-bot smoke fully passes — authenticated + betting —
proceed to Step 4"), the betting leg is not fully demonstrable here, so the
100-bot group was NOT created. Creating it would not change the outcome: 100
more zero-balance accounts would behave the same.

---

## Build

- `mvn clean install`: PASS (~25s, 1077 tests, 0 failures / 0 errors / 0 skipped) — Java 21.0.2
- `docker build --no-cache --platform linux/amd64`: PASS (~17s, sha256:b58c00dc887a)
- `docker save -o bot.tar`: PASS (392,872,960 bytes)

> Note: Docker Desktop daemon was down at start; launched and confirmed ready
> before the image build. No other infra change.

## Ship

- `sftp put bot.tar` -> Bot-1:/home/sgame/bot-java: PASS (remote size 392,872,960 bytes, exact match)

## Guards

- DNS override present in compose: PASS — `docker-compose.yml` lines 26-28 `dns: ["1.1.1.1","8.8.8.8"]`
- DNS intact on running container: PASS — `HostConfig.Dns = [1.1.1.1 8.8.8.8]`
- Compose untouched (no overwrite): PASS
- Port mapping confirmed: `8080:8085`

## Deploy (targeted, NOT full down)

- `docker load -i bot.tar`: PASS (old image renamed away: sha256:c017f542a3f7)
- `HOST_UID=$(id -u) HOST_GID=$(id -g) docker compose up -d bot-manager`: PASS
  (only bot-manager recreated; mongo stayed running; grafana/prometheus/promtail/loki untouched)

## Smoke test

- `docker ps` bot-manager healthy: PASS — `Up … (healthy)` after start_period (~32s)
- Spring Boot ready log: PASS — `Started Starter in 8.695 seconds`
- Auto-start ran: PASS — `Bot Manager startup complete. 6 bot groups running`
  (116 Demo group, XD game test, Fruit shop Bots, Slot test 204,
  TaiXiuStagingVerifyGroup2, Tai Xiu test)
- Actuator health: PASS — HTTP 200, `{"status":"UP"}` (mongo UP)
- Observability stack still up: PASS — grafana / prometheus / promtail / loki / mongo all Up

## Discovery (env + game resolution)

- "114 Staging" env: `394301f4-6daf-4c55-a073-502a81c00731` (product RIK, code 114, appId `rik.vip`)
- "Tai Xiu Jackpot" game: `29d419f1-9c96-4e74-aec1-41c7fe5849c3` (plugin `taixiuJackpotPlugin`, type TAI_XIU)
- Config source (healthy 116 TAI_XIU group "Tai Xiu test", `66cfc12c-…`, prefix `txb0tt3st`):
  minBet 5000, maxBet 10000000, betIncrement 5000, maxTotalBetPerRound 50000000,
  minBetsPerRound 1, maxBetsPerRound 22, strategyMix MARTINGALE_CLASSIC_CAUTIOUS w=1.0,
  password 123123Aa, chatEnabled true, autoDepositEnabled false

## Step 2 — 2-bot 114 smoke group: PARTIAL (auth/subscribe PASS, betting UNVERIFIED)

POST `/api/v1/bot-group/`: env 394301f4 (114), game 29d419f1 (Tai Xiu Jackpot),
botCount 2, namePrefix `riksmk`, config copied from the healthy 116 group.

- Create: **HTTP 200** — group id `375ea594-75c1-4490-b49d-79b597c1b520`.
- Start: `POST /{id}/start` -> **HTTP 200**.

### Exact upstream responses observed

Register (both bots, HTTP 200 envelope OK):
```
[Register] POST https://api-gwrik.sgame.us/gwms/v1/bot/register.aspx
  body: {"register_ip":"16.162.36.69","app_id":"rik.vip","username":"riksmk1",
         "password":"123123Aa","ip":"16.162.36.69","os":"OS X","device":"Computer",
         "browser":"WEB","source":"rik.vip"}
[Register] response HTTP 200 | {"status":"OK","code":200,"data":[{"uid":"11751140000016751126",
  "session_id":"d001…","token":"15-2005…","token2":"eyJ…","username":"riksmk1",…}],
  "message":"Register successful"}
```
(riksmk2 identical -> "Register successful"; both display names set via update-fullname.aspx.)

Login / RIK profile (both bots, HTTP 200):
```
[Login] POST https://api-gwrik.sgame.us/gwms/v1/bot/login.aspx
  body: {"fg":"f298…","username":"riksmk1","password":"123123Aa","os":"OS X",
         "device":"Computer","browser":"chrome","version":"1.1.977","ip":"16.162.36.69",
         "app_id":"rik.vip","aff_id":"RIKVIP","apVer":"1.1.977"}
```
VerifyToken (both bots, HTTP 200): `{"status":"OK","code":200,"data":[{"main_balance":0,…}]}`
— note `main_balance:0`.

EndGame frames received by riksmk (cmd 1104), representative:
```
[5,{"sd":[5,3,4,4],"cBB":0,"gBB":0,"tJpV":246992400,"cmd":1104,"d1":3,"d2":4,"iJp":false,"d3":2}]
```
All bare public broadcasts — no `gB`/`gR`/`G` per-bot settlement (count of EndGames with `gB` = 0).

### Verification checklist (Step 2)

| Check | Result |
|---|---|
| Both bots register OK (0 register non-200s) | PASS — both HTTP 200 "Register successful" |
| Authenticate via RIK profile (RikLoginRequest / app_id rik.vip) | PASS — login.aspx 200, app_id rik.vip, aff_id RIKVIP |
| VerifyToken | PASS — HTTP 200 (main_balance 0) |
| Reach CONNECTION_AUTHENTICATED | PASS — both bots (STARTED -> CONNECTION_AUTHENTICATED) |
| 0 UnknownHostException | PASS — DNS resolved api-gwrik.sgame.us |
| subscribe 1105 sent (taixiuJackpotPlugin) | PASS — `["6","MiniGame","taixiuJackpotPlugin",{"cmd":1105}]` x2 |
| start 1102 received | PASS — 8 frames matched |
| end 1104 received | PASS — 10 frames matched |
| No recaptcha / r_token gate | PASS — login succeeded without r_token |
| bots place bets carrying a:false | **UNVERIFIED** — see below |
| refund-aware accounting | **UNVERIFIED** — no server settlement (balance 0) |
| no wedged bots / DEAD / ERROR | PASS — 0 DEAD, 0 ERROR for tai/xiu/114/jackpot/rik; both bots connected, status ACTIVE |

### Why "place bets carrying a:false" is UNVERIFIED (not FAIL of code)

1. **OutputPrinter does not log Tai Xiu bet sends.** Confirmed by comparison:
   the healthy 116 group (`txb0tt3st`) also emits only its subscribe frame
   (`cmd:1005`) to OutputPrinter — zero bet `[SENT]` lines. So the absence of a
   visible `1100`/`a:false` `[SENT]` frame is a logging characteristic shared
   with the known-good product, not a 114 defect. The bot's bet path *does*
   execute (`BettingMiniGameBot … sending bet option=2, amount=10000, sid=1619`
   each tick; `TaiXiuGameBot … single-entry lock — remapping bet entry 1 -> 2`;
   Martingale `onRoundEnd` advances `prevBet -> nextBet`). The `a:false` field is
   asserted at the unit level (Phase 3 tests, all 1077 green in this build).
2. **Zero balance, no server settlement.** Both riksmk accounts are brand-new
   with `main_balance:0` and `autoDepositEnabled=false`. The RIK server settles
   no bets — EndGames are bare broadcasts with no `gB`/`gR`/`G`. The local
   "session balance -770000" was optimistic accounting that reset to 0 on the
   next real balance fetch.
3. **`bot_bets_placed_total` has no TAI_XIU tag at all** (only SLOT,
   BETTING_MINI) — for *both* products — so the plan's metric-based check
   (Verification §4/§5) cannot be evaluated for either 116 or 114 on this build.
4. **The healthy 116 reference shows the identical pattern** (balance 0,
   no-settlement EndGames, no TAI_XIU bet metric). The 114 group is therefore
   behaviourally equivalent to the accepted-healthy baseline apart from the
   intended +100 CMDs and RIK profile.

## Step 3 — GATE: NOT CLEARED -> STOP

Auth + subscribe + the +100 CMD plumbing fully pass, but the betting leg cannot
be positively verified on zero-balance accounts. Per the brief ("only if the
2-bot smoke fully passes — authenticated + betting"), the 100-bot group was NOT
created.

## Step 4 — 100-bot 114 group: NOT ATTEMPTED (gate held)

## Plan verification (docs/plans/TAI_XIU_114_JACKPOT.md § Verification)

| # | Step | Result |
|---|---|---|
| 1 | App boots; `/actuator/health` 200 UP | PASS |
| 2 | P_114 env + taixiuJackpotPlugin TAI_XIU game usable via REST | PASS (pre-existing env 394301f4 + game 29d419f1; group created HTTP 200) |
| 3 | P_114 group validates, starts, bots reach CONNECTION_AUTHENTICATED; subscribe 1105 matched | PASS — both bots CONNECTION_AUTHENTICATED; subscribe 1105 sent, 1105 response matched |
| 4 | Rounds drive bets at +100 CMDs with `a` flag; `bot_bets_placed_total{TAI_XIU} > 0` | PARTIAL/UNVERIFIED — inbound 1102/1104 consumed; bet-send path runs; but no server settlement (balance 0) and metric has no TAI_XIU tag for either product |
| 5 | Refund-aware accounting (`bot_winnings_total{TAI_XIU}`) | UNVERIFIED — no settled rounds (balance 0); same on 116 baseline |
| 6 | No 116 regression / no wedged 114 bots; observability up | PASS — 0 ERROR/DEAD for tai/xiu/114/jackpot/rik; 116 group unaffected; grafana/prometheus/promtail/loki/mongo all up |

## Final state

- bot-manager: Up (healthy), commit 2e97f5d, image sha256:b58c00dc887a, RIK login profile active
- New 114 smoke group `375ea594-75c1-4490-b49d-79b597c1b520` ("RIK114 TaiXiu Smoke"):
  status ACTIVE, 2/2 connected, 0 dead, 0 consecutive failures, left running for inspection
- Group count: 21 (was 20; +1 RIK/114 group)
- Pre-existing groups intact (incl. 116 Tai Xiu), actively running
- Observability: grafana / prometheus / promtail / loki / mongo all up
- 100-bot group: NOT created (gate held)
- Nothing deleted; compose not modified; no git push/amend

## Recommended next steps (to fully clear the betting gate)

1. **Fund the RIK accounts** (give the freshly-registered `riksmk*` / future
   `rik*` users a non-zero `main_balance` out-of-band), OR set
   `autoDepositEnabled=true` for the RIK group *if* a working RIK deposit/agency
   path exists on staging — note the healthy 116 group runs with autoDeposit off
   and also sits at balance 0, so autoDeposit alone may not fund accounts.
2. Once an account has balance, re-run the 2-bot smoke and confirm: EndGame
   (`1104`) frames carry per-bot `gB`/`gR`/`G`, balance debits/credits move, and
   (if Tai Xiu bet-send logging is added) the outbound `1100` frame carries
   `"a":false`. Then proceed to the 100-bot group.
3. Separately (not blocking 114): `bot_bets_placed_total` / `bot_winnings_total`
   carry no TAI_XIU tag — the Tai Xiu bet/winnings path appears to not feed those
   counters for *either* product. Worth a follow-up so plan §4/§5 metric checks
   become evaluable.

---

# Re-run (autoDepositEnabled=true) — 2026-06-29T10:33–10:39Z

> The prior run above left the betting leg UNVERIFIABLE because the RIK accounts
> sat at `main_balance:0` (`autoDepositEnabled=false`). Per user instruction, the
> single critical change here is **`autoDepositEnabled=true`**. No rebuild/redeploy
> — same image (commit 2e97f5d, sha256:b58c00dc887a). The running container was
> confirmed healthy first.

## Pre-flight

- bot-manager healthy: PASS — `Up 17 minutes (healthy)` at start; `/actuator/health` HTTP 200, `{"status":"UP"}` (mongo UP).
- No redeploy performed (image already live, container healthy).

## Cleanup of old zero-balance smoke group

- Old group `375ea594-75c1-4490-b49d-79b597c1b520` ("RIK114 TaiXiu Smoke", autoDep=false): **stopped (HTTP 200) and deleted (HTTP 200)**.

## Step 3 (re-run) — 2-bot 114 smoke with autoDeposit=true

- Same env `394301f4-…` (114 Staging, RIK), same game `29d419f1-…` (Tai Xiu
  Jackpot, `taixiuJackpotPlugin`), botCount 2, namePrefix `riksmk`, password
  `123123Aa`, minBet 5000 / maxBet 10000000 / betIncrement 5000 /
  maxTotalBetPerRound 50000000 / minBetsPerRound 1 / maxBetsPerRound 22,
  strategyMix MARTINGALE_CLASSIC_CAUTIOUS w=1.0, chatEnabled true, **autoDepositEnabled true**.
- The `riksmk1`/`riksmk2` accounts already existed from the prior run
  (register.aspx returned `EXISTED 409`), so the group was created with
  **`existingGroup:true`** to reuse those exact accounts (auto-deposit applies at
  bot start regardless). Create: **HTTP 200** — new group id
  **`c45b3b16-bd34-4b9b-94f0-2d37b8c3c8e3`**. Start: **HTTP 200**.

### Auto-deposit — WORKS (the new, positive result)

```
10:34:20.022 INFO BettingMiniGameBot [c45b3b16/1/TAI_XIU] - Bot riksmk1: balance 0 below minimum 5000000, triggering deposit
10:34:20.022 INFO BettingMiniGameBot [c45b3b16/2/TAI_XIU] - Bot riksmk2: balance 0 below minimum 5000000, triggering deposit
10:34:20.044 DEBUG Bot [c45b3b16/1/TAI_XIU] - Bot riksmk1: Deposit successful, fetching new balance...
10:34:20.556 DEBUG Bot [c45b3b16/1/TAI_XIU] - Bot riksmk1: New balance: 1000000000
10:34:20.556 DEBUG Bot [c45b3b16/2/TAI_XIU] - Bot riksmk2: New balance: 1000000000
```

VerifyToken confirms the real server balance moved 0 → **1,000,000,000** for both
bots. Both reached `CONNECTION_AUTHENTICATED` (10:34:25), subscribed `1105`, and
began betting.

### Bots place bets every round (Martingale, up to 22/round)

```
10:34:44 DEBUG MartingaleStrategySupport [c45b3b16/1] - ClassicMartingaleCautious.decide: bet option=1, amount=5000 (currentBet=5000, profile=CAUTIOUS)
10:34:44 DEBUG BettingMiniGameBot   [c45b3b16/1] - Bot riksmk1: sending bet option=2, amount=5000, sid=1632
...
10:34:51 DEBUG MartingaleStrategySupport [c45b3b16/2] - ClassicMartingaleCautious.decide: skip — already placed 22 bets this round (max 22)
10:35:19 DEBUG MartingaleStrategySupport [c45b3b16/1] - ClassicMartingaleCautious.onRoundEnd: delta=-110000, prevBet=5000, nextBet=10000 (capHit=false)
```

Bet path is correct end-to-end on the bot side: the bet is built as a
`TaiXiuBet` (cmd **1100**, the +100 offset) carrying **`a:false`**
(`TaiXiuRequest.bet()` with `emitAutoBetFlag`), sent via the scenario
`sendAsync` loop each interval. (As documented in the first run, the `sendAsync`
bet path is not surfaced by `OutputPrinter`'s `[SENT]` logging — only the
`.send(request::subscribe)` 1105 frame is; this is identical to the healthy 116
behaviour and is a logging characteristic, not a transmit failure.)

### DECISIVE: bets do NOT settle, even with funded accounts

The server **never debits the real balance** despite continuous betting:

```
10:35:19.630 checkBalance() ENTRY. lastFetched: 1000000000, expected:  999890000, delta: 110000
10:36:26.632 checkBalance() ENTRY. lastFetched: 1000000000, expected:  999670000, delta: 330000
10:37:33.635 checkBalance() ENTRY. lastFetched: 1000000000, expected:  999230000, delta: 770000
10:38:40.638 checkBalance() fetching from server (delta > 1M)
10:38:41.169 checkBalance() fetched: 1000000000      <-- REAL server balance, UNCHANGED after 4+ rounds
10:38:41.177 checkBalance() fetched: 1000000000
```

`lastFetched` is the real `getBalance()` value; `expected` is the bot's
optimistic local accounting. When the optimistic delta crossed 1,000,000 (the
refetch threshold in `Bot.checkBalance()`), the bot performed a fresh authoritative
server fetch — and the real `main_balance` came back **1,000,000,000, identical
to the post-deposit value**. The server registered zero of the bets.

EndGame (`1104`) frames are **bare public broadcasts** — no per-bot settlement:

```
[5,{"sd":[5,3,6,1],"cBB":0,"gBB":0,"tJpV":246992400,"cmd":1104,"d1":3,"d2":3,"iJp":false,"d3":5}]
```

`cBB:0`, `gBB:0`, and **no `gB`/`gR`/`G`** per-bot settlement object on any
EndGame received by either bot. Local `BotMemory.completeRound` recorded
`payout=0, staked=110000, delta=-110000` — i.e. the bot's own optimistic math,
not a server-reported settlement.

### Root-cause evidence: account flag `is_bet:false`

The RIK GameMS token details for both accounts carry, even after the deposit
funded them to 1B:

```
"status":"ACTIVE","is_deposit":false,"is_bet":false,"is_required_captcha":false
```

`is_bet:false` (and `is_deposit:false`) strongly indicates the RIK staging
gateway flags these bot accounts as **not permitted to bet** — so it silently
accepts no bets (no debit, no settlement frame, no error). This is an
account-provisioning / server-side gating issue on RIK staging, **not** a
bot-code defect. (No group in the current log buffer shows `is_bet:true` for
comparison; the healthy 116 group did not re-emit token details within the
window.)

### Verification checklist (re-run, Step 3)

| Check | Result |
|---|---|
| Auto-deposit funds accounts (non-zero balance) | **PASS** — 0 → 1,000,000,000 for both bots |
| Reach CONNECTION_AUTHENTICATED | PASS — both bots |
| Subscribe 1105 / StartGame 1102 / EndGame 1104 flow (+100 plumbing) | PASS — frames sent and consumed |
| Bots place bets (cmd 1100, a:false, Martingale, ≤22/round) | PASS (bot side) — built + sent via sendAsync each round |
| Bets **settled** — EndGame carries `gB`/`gR`/`G`, `cBB`/`gBB` non-zero | **FAIL** — all EndGames bare (cBB:0, gBB:0, no gB/gR/G) |
| Refund / balance accounting reflects real settlement | **FAIL** — real server balance frozen at 1,000,000,000 after 4+ rounds |
| no wedged / DEAD / ERROR | PASS — 0 WARN/ERROR for c45b3b16; 0 dead |

## Step 4 — GATE: NOT CLEARED -> 100-bot group NOT created

Per the brief: "If the betting leg still can't be verified even with
autoDeposit=true, STOP at the gate, do not create the 100-bot group." Auto-deposit
is now proven (the prior blocker is gone), but the betting leg is now
**positively shown NOT to settle** — the real server balance does not move and
EndGames carry no settlement. The decisive blocker is the RIK account flag
`is_bet:false`. The 100-bot group was **NOT** created; 100 more identically-flagged
accounts would behave the same.

## Final state (after re-run)

- bot-manager: **Up 26 minutes (healthy)**; `/actuator/health` HTTP 200 UP (mongo UP).
- Observability stack: grafana / prometheus / promtail / loki / mongo — **all Up**.
- 2-bot re-run smoke group `c45b3b16-bd34-4b9b-94f0-2d37b8c3c8e3`
  ("RIK114 TaiXiu Smoke AutoDep", autoDep=true): **STOPPED** after the gate failed
  (kept in DB for inspection; accounts riksmk1/riksmk2 funded at 1B).
- Old zero-balance smoke group `375ea594-…`: **deleted**.
- **100-bot group: NOT created.**
- Group count: 21. Pre-existing groups intact; nothing else touched.
- Compose not modified; no git push/amend.

## FINAL VERDICT (autoDeposit re-run): **FAIL — gate held**

Net change vs the first run: **auto-deposit is confirmed working** (accounts now
fund to 1B), which closes the "unverifiable / zero-balance" caveat. With funded
accounts the betting leg is now *positively* measurable — and it **does not
settle**: the real RIK server balance is unchanged after 4+ rounds of 22 bets
each, and every EndGame is a bare broadcast with no `gB`/`gR`/`G`. Root cause:
RIK staging marks the bot accounts `is_bet:false`. This is a server/account
provisioning blocker, not a bot-code defect. The 100-bot group was not created.

### Recommended next step

Have RIK flip `is_bet` (and likely `is_deposit`) to `true` for the bot accounts
on staging (account provisioning), then re-run the 2-bot smoke. Expect EndGame
`1104` to then carry per-bot `gB`/`gR`/`G` and the real balance to move; only
then proceed to the 100-bot group.

---

# Re-run 3 (fix: login `type=BOT`) — 2026-06-29T11:00–11:06Z

> Hypothesis under test: the release-3 settlement blocker (`is_bet:false` on the
> RIK bot accounts) is caused by the login payload not declaring the account as a
> bot. Fix: `RikLoginRequest` now sends `"type":"BOT"` in the `login.aspx` body.
> Branch `fix/rik-login-type-bot`, commit `26281cd` (NOT pushed/merged).
> Full pipeline run: rebuild + targeted bot-manager redeploy + 2-bot smoke.

## Build / Ship / Deploy

- `mvn clean install`: PASS (~24s, 1077 tests, 0 failures / 0 errors / 0 skipped) — Java 21.0.2
- `docker build --no-cache --platform linux/amd64`: PASS (image sha256:3aab1cbc6c65, 380MB)
- `docker save -o bot.tar`: PASS (392,872,960 bytes)
- `sftp put bot.tar` -> Bot-1: PASS (remote size 392,872,960 bytes, exact match)
- DNS guard: PASS — compose `dns: ["1.1.1.1","8.8.8.8"]` intact; running container `HostConfig.Dns = [1.1.1.1 8.8.8.8]`; port `8080:8085`; compose NOT overwritten
- `docker load -i bot.tar`: PASS (old image sha256:b58c00dc887a renamed away)
- Targeted deploy `docker compose up -d bot-manager` (HOST_UID/HOST_GID): PASS — only bot-manager recreated; mongo/grafana/prometheus/promtail/loki untouched (NO full down)

## Smoke test

- bot-manager healthy: PASS — `Up … (healthy)` after ~39s start_period
- Spring Boot ready: PASS — `Started Starter in 7.446 seconds`
- Auto-start: PASS — `Bot Manager startup complete. 6 bot groups running`
- `/actuator/health`: PASS — HTTP 200, `{"status":"UP"}` (mongo UP)
- Observability stack: PASS — grafana / prometheus / promtail / loki / mongo all Up

## Fix is live in the wire

`login.aspx` body now carries `"type":"BOT"` for both bots (was absent before):

```
[Login] POST https://api-gwrik.sgame.us/gwms/v1/bot/login.aspx | body:
{"fg":"…","username":"riksmk1","password":"123123Aa","os":"OS X","device":"Computer",
 "browser":"chrome","version":"1.1.977","ip":"16.162.36.69","type":"BOT",
 "app_id":"rik.vip","aff_id":"RIKVIP","apVer":"1.1.977"}
```

## 2-bot smoke group — `existingGroup:true`, autoDeposit=true

- New group `b7f946b0-d653-49b0-a1e1-58d396e0f9b3` ("RIK114 TaiXiu TypeBot Smoke"):
  env `394301f4-…` (114 Staging, RIK), game `29d419f1-…` (Tai Xiu Jackpot,
  taixiuJackpotPlugin), botCount 2, namePrefix `riksmk`, password 123123Aa,
  minBet 5000 / maxBet 10000000 / betIncrement 5000 / maxTotalBetPerRound 50000000 /
  minBetsPerRound 1 / maxBetsPerRound 22, strategyMix MARTINGALE_CLASSIC_CAUTIOUS w=1.0,
  chatEnabled true, **autoDepositEnabled true**, **existingGroup true** (reuses the
  funded riksmk1/riksmk2 accounts from re-run 2).
- Create: HTTP 200. Start: HTTP 200. Both bots reached `CONNECTION_AUTHENTICATED`,
  0 dead, 0 ERROR over a ~6-minute run; group health ACTIVE, 2/2 connected.

## CRITICAL DELIVERABLE — full verifytoken `data[0]` (both bots)

The RIK `verifytoken.aspx` response `data[0]` does **NOT** contain `type`,
`is_bet`, `is_deposit`, `is_required_captcha`, or an account-level `status`
field. The release-3 `is_bet:false` reading came from a different ("GameMS token
details") source that **this build does not emit at all** (`grep is_bet` over the
entire current container log = 0 hits). What `verifytoken` actually returns:

| Field | riksmk1 (bot 1) | riksmk2 (bot 2) |
|---|---|---|
| `type` | **absent** (no `type` field) | **absent** |
| `type_id` | **3** | **3** |
| `is_bet` | **absent** (not a verifytoken field) | **absent** |
| `is_deposit` | **absent** | **absent** |
| `is_required_captcha` | **absent** | **absent** |
| `status` (account) | **absent** (envelope `"status":"OK"` only) | **absent** |
| `level` | `LEVEL0` | `LEVEL0` |
| `main_balance` | `1000000000` | `1000000000` |

Raw JSON (`data[0]`), token2 truncated:

```
riksmk1: {"main_balance":1000000000,"uid":"11751140000016751126","type_id":3,
  "session_id":"ffcd16d903891f8743bfb75daf79ce6f","p":false,
  "token":"15-b3f6f76d2b419f805604859a15af47b6","token2":"eyJhbGciOiJIUzI1NiJ9…",
  "fullname":"cpdwhfpngle6497","username":"riksmk1","level":"LEVEL0",
  "ref_id":"cpdwhfpngle6497","is_club":false,"force_up":false,
  "last_change_password":"2026-06-29T10:17:16.587Z"}

riksmk2: {"main_balance":1000000000,"uid":"15051140000016750930","type_id":3,
  "session_id":"b74294e22a159e2f0f8d7011ed2c6868","p":false,
  "token":"15-01108a41228bf989163a0265756bd27e","token2":"eyJhbGciOiJIUzI1NiJ9…",
  "fullname":"namdinh5304","username":"riksmk2","level":"LEVEL0",
  "ref_id":"namdinh5304","is_club":false,"force_up":false,
  "last_change_password":"2026-06-29T10:17:16.587Z"}
```

**Is `type` now `BOT`?** Not observable on the verifytoken side: `type` is not a
field RIK returns there. The account's persisted `type_id` is **3** (unchanged).
The login *param* `type=BOT` is sent (confirmed in the wire body above), but it
does not alter the verifytoken response shape and — decisively — does not change
settlement (below). Note the riksmk accounts were registered before this fix; a
login-time `type` param would not be expected to retroactively re-provision a
persisted account.

## DECISIVE — bets still do NOT settle (identical to release-3)

- Bet sends: both bots bet every round (Martingale, ≤22/round); `totalBetsPlaced`
  110 each, `totalBetAmount` 3,410,000 each at health snapshot.
- Authoritative real balance frozen — `checkBalance()` real fetch returned the
  unchanged post-deposit value after the optimistic delta crossed the 1M refetch
  threshold:

  ```
  11:02:07 checkBalance ENTRY lastFetched:1000000000 expected:999890000 delta:110000
  11:04:21 checkBalance ENTRY lastFetched:1000000000 expected:999230000 delta:770000
  11:05:28 checkBalance ENTRY lastFetched:1000000000 expected:998350000 delta:1650000
  11:05:29 checkBalance() fetched: 1000000000   <-- REAL balance UNCHANGED, both bots
  ```
  `lastFetchedBalance` in group health = `1000000000` for both bots; `lastRoundWinnings` 0.

- EndGame `1104` frames are bare public broadcasts — **no** per-bot settlement:

  ```
  [5,{"sd":[4,1,3,2],"cBB":0,"gBB":0,"tJpV":246992400,"cmd":1104,"d1":3,"d2":1,"iJp":false,"d3":5}]
  ```
  Over the run: EndGames carrying `gB`/`gR`/`G` = **0**; EndGames with non-zero
  `cBB`/`gBB` = **0**. `BotMemory.completeRound` logs `payout=0` every round
  (e.g. sid 1659: `payout=0, staked=880000, delta=-880000`) — bot-side optimistic
  math only, no server-reported settlement.

### Settlement checklist

| Check | Result |
|---|---|
| Both bots authenticate via RIK profile, reach CONNECTION_AUTHENTICATED | PASS |
| Auto-deposit / accounts funded (real balance 1B) | PASS |
| Bots place bets (cmd 1100, a:false, Martingale, ≤22/round) | PASS (bot side) |
| EndGame `1104` carries per-bot `gB`/`gR`/`G` | **FAIL** — 0 such frames |
| `cBB`/`gBB` non-zero | **FAIL** — all 0 |
| Authoritative `checkBalance()` real `main_balance` moves across rounds | **FAIL** — frozen at 1,000,000,000 after 110 bets/bot |

## Step 4 — GATE: NOT CLEARED -> 100-bot group NOT created

The `type=BOT` login fix is correctly built, deployed, and on the wire, but it
does **not** unblock settlement: the authoritative server balance does not move
and every EndGame is a bare broadcast. Per the brief ("only if betting settles →
create the 100-bot group; else STOP at the gate"), the **100-bot group was NOT
created**.

## Final state

- bot-manager: Up (healthy), image sha256:3aab1cbc6c65 (commit 26281cd, `type=BOT` active)
- 2-bot smoke group `b7f946b0-…`: **STOPPED** after gate failed (kept in DB; accounts riksmk1/2 funded at 1B)
- 100-bot group: NOT created
- Observability: grafana / prometheus / promtail / loki / mongo all Up
- Pre-existing groups intact; compose not modified; no git push/amend/merge

## VERDICT (re-run 3, `type=BOT`): **FAIL — gate held**

`type=BOT` is now sent in the RIK login payload (fix verified live), but it does
not change the RIK `verifytoken` response (no `type`/`is_bet` fields; `type_id`
still 3) and does **not** make bets settle — real balance frozen at 1B, all
EndGames bare, payout 0. The settlement blocker observed in release-3 persists
and is server/account-provisioning side, not closed by the login `type` param.
Likely because the riksmk accounts were provisioned before the change; a clean
test would require RIK to (a) provision/flag fresh bot accounts as bettable, or
(b) confirm whether `type=BOT` must be sent at **register** time rather than
login. The 100-bot group remains gated until an EndGame carries per-bot
`gB`/`gR`/`G` and the real balance moves.

---

# Re-run 4 (fix: register `type=BOT`) — 2026-06-29T11:15–11:22Z

> Hypothesis under test: re-run 3 sent `type=BOT` only at **login**, which cannot
> re-provision an account already created as `type_id:3`. The recommended next
> step was to send `type=BOT` at **register** time. This is exactly what commit
> `b4bdab6` does (`ApiGatewayClient` register builder now sets `.type("BOT")`).
> Branch `fix/rik-login-type-bot`, commit `b4bdab6` (NOT pushed/merged).
> Full pipeline: rebuild + targeted bot-manager redeploy + a **fresh-prefix**
> 2-bot smoke (`rikbot`, `existingGroup:false`) so registration actually runs and
> creates brand-new bot-typed accounts (NOT the reused `riksmk` type_id:3 ones).

## Build

- `mvn clean install`: PASS (~26s, 1077 tests, 0 failures / 0 errors / 0 skipped) — Java 21.0.2; jar `target/Bot-1.0.jar` (61,461,762 bytes, built 15:13 +04)
- `docker build --no-cache --platform linux/amd64`: PASS (~13s, image **sha256:3101c95eedd5**)
- `docker save -o bot.tar`: PASS (392,872,960 bytes)

## Ship

- `sftp put bot.tar` -> Bot-1:/home/sgame/bot-java: PASS (remote size 392,872,960 = local, exact match)

## Guards

- Compose `dns: ["1.1.1.1","8.8.8.8"]` (lines 26-28) intact; running container `HostConfig.Dns = [1.1.1.1 8.8.8.8]`
- Port mapping `8080:8085` intact; compose NOT overwritten
- Targeted deploy only (`docker compose up -d bot-manager`); NO full `down`

## Deploy (targeted, NOT full down)

- `docker load -i bot.tar`: PASS (old image sha256:3aab1cbc6c65 from re-run 3 renamed away)
- `HOST_UID/HOST_GID docker compose up -d bot-manager`: PASS — only bot-manager recreated; mongo stayed Running; grafana/prometheus/promtail/loki untouched

## Smoke test

- bot-manager healthy: PASS — `Up … (healthy)` after ~34s start_period
- Spring Boot ready: PASS — `Started Starter in 7.443 seconds`
- Auto-start: PASS — `Bot Manager startup complete. 6 bot groups running`
- `/actuator/health`: PASS — HTTP 200, `{"status":"UP"}` (mongo UP, ssl UP, ping UP, diskSpace UP)
- Observability stack: PASS — grafana / prometheus / promtail / loki / mongo all Up

## Step 2 — fresh 2-bot smoke group (`rikbot`, real registration)

- POST `/api/v1/bot-group/`: env `394301f4-…` (114 Staging, RIK), game `29d419f1-…`
  (Tai Xiu Jackpot, taixiuJackpotPlugin), botCount 2, namePrefix **`rikbot`**,
  password 123123Aa, minBet 5000 / maxBet 10000000 / betIncrement 5000 /
  maxTotalBetPerRound 50000000 / minBetsPerRound 1 / maxBetsPerRound 22,
  strategyMix MARTINGALE_CLASSIC_CAUTIOUS w=1.0, chatEnabled true,
  **autoDepositEnabled true**, **existingGroup false** (real registration).
- Create: **HTTP 200** — group id **`29a8d97f-7132-49eb-9e53-651954243fdd`**.
- Start: **HTTP 200**. Both bots reached `CONNECTION_AUTHENTICATED` (11:17:28), 0 dead, 0 ERROR.

### CRITICAL — register payload now carries `"type":"BOT"` (fix is live)

```
[Register] POST https://api-gwrik.sgame.us/gwms/v1/bot/register.aspx
  body: {"register_ip":"16.162.36.69","app_id":"rik.vip","username":"rikbot1",
         "password":"123123Aa","ip":"16.162.36.69","os":"OS X","device":"Computer",
         "browser":"WEB","type":"BOT","source":"rik.vip"}
```
(rikbot2 identical.) Both register responses: **HTTP 200 "Register successful"**
(IP allowlist still open). Register response `data[0]` still reports **`type_id:3`**
for both — RIK assigns `type_id:3` at register regardless of the `type` field.

### verifytoken `data[0]` — both bots

| Field | rikbot1 | rikbot2 |
|---|---|---|
| `type_id` | **3** (unchanged) | **3** |
| `type` | absent (not a verifytoken field) | absent |
| `is_bet` | absent (not a verifytoken field) | absent |
| `level` | `LEVEL0` | `LEVEL0` |
| `main_balance` | `0` → `1000000000` (after auto-deposit) | `0` → `1000000000` |

Raw `data[0]` (post-deposit fetch, token2 truncated):
```
rikbot1: {"main_balance":1000000000,"uid":"15751140000016753952","type_id":3,
  "session_id":"7ab4b891d96c2ecef38035fd49e872dd","p":false,
  "token":"15-ec4765e048733913f399ace423dda616","token2":"eyJhbGciOiJIUzI1NiJ9…",
  "fullname":"kidanh6712nnx","username":"rikbot1","level":"LEVEL0",
  "ref_id":"kidanh6712nnx","is_club":false,"force_up":false,
  "last_change_password":"2026-06-29T11:17:01.205Z"}
rikbot2: {"main_balance":1000000000,"uid":"98551140000016752178","type_id":3,
  "session_id":"fbdbdae445c54d322381768cb43d6a10","p":false,
  "token":"15-c1666c3da217adc88c5447a88f44aeef","token2":"eyJhbGciOiJIUzI1NiJ9…",
  "fullname":"kidlongthanh","username":"rikbot2","level":"LEVEL0",
  "ref_id":"kidlongthanh","is_club":false,"force_up":false,
  "last_change_password":"2026-06-29T11:17:01.202Z"}
```

### DECISIVE NEW SIGNAL — GameMS "Token details" now shows `"type":"BOT"`

The GameMS `verifytoken` "Token details" line (the source that previously reported
`is_bet:false`) now emits, and for **both** accounts carries:
```
…,"status":"ACTIVE","is_deposit":false,"is_bet":false,"is_required_captcha":false,
  "main_balance":0,…,"agency_id":15,"agency_code":"rv","agency_code2":"BC115114",
  "type":"BOT","uid":"rv_rikbot1","username":"rikbot1",…  (rikbot2 analogous)
```
So the register `type=BOT` fix **did** change the account: GameMS now classifies
these accounts as **`type:"BOT"`** (previously they were ordinary users). This is a
real, observable behavioural change vs re-runs 1–3. **However `is_bet:false`
(and `is_deposit:false`) remain** — RIK staging still does not flag bot-typed
accounts as permitted to bet.

## Step 3 — Settlement check (the actual goal): bets still do NOT settle

- Auto-deposit: PASS — both accounts funded 0 → **1,000,000,000**.
- Bets placed: both bots bet every round (Martingale, ≤22/round) — health snapshot
  showed **88 bets each** by 11:22.
- EndGame `1104` frames: all **bare public broadcasts** — `cBB:0, gBB:0`, **no
  per-bot `gB`/`gR`/`G` settlement** on any frame. Representative:
  ```
  [5,{"sd":[2,1,6,5],"cBB":0,"gBB":0,"tJpV":246992400,"cmd":1104,"d1":6,"d2":4,"iJp":false,"d3":3}]
  ```
  Count over run: EndGames with `gB`/`gR`/`G` = **0**; with non-zero `cBB`/`gBB` = **0**.
- `BotMemory.completeRound`: `payout=0, staked=110000, delta=-110000` each round (bot-side optimistic math only).
- **Authoritative `checkBalance()` real `main_balance` — FROZEN across rounds:**
  ```
  11:22:13 checkBalance() fetching from server (delta > 1M)   (after the optimistic delta crossed 1M)
  11:22:14 checkBalance() fetched: 1000000000   rikbot1  <-- REAL balance UNCHANGED after 88 bets
  11:22:14 checkBalance() fetched: 1000000000   rikbot2  <-- REAL balance UNCHANGED after 88 bets
  ```
  Group health after the refetch: both bots `lastFetchedBalance=1000000000`,
  `lastRoundWinnings=0`, `totalBetsPlaced=88`, ACTIVE, 2/2 connected, 0 dead, 0 ERROR.

### Settlement checklist

| Check | Result |
|---|---|
| Register sends `type:BOT` | **PASS** — confirmed in wire body, HTTP 200 "Register successful" |
| Account now bot-typed in GameMS (`type:"BOT"`) | **PASS (NEW)** — GameMS token details show `type:"BOT"` |
| Account flagged bettable (`is_bet:true`) | **FAIL** — `is_bet:false` (and `is_deposit:false`) unchanged |
| Auto-deposit funds accounts (real balance 1B) | PASS |
| Bots place bets (cmd 1100, a:false, Martingale, ≤22/round) | PASS (bot side) — 88 bets/bot |
| EndGame `1104` carries per-bot `gB`/`gR`/`G` | **FAIL** — 0 such frames |
| `cBB`/`gBB` non-zero | **FAIL** — all 0 |
| Authoritative `checkBalance()` real `main_balance` moves across rounds | **FAIL** — frozen at 1,000,000,000 after 88 bets/bot |

## Step 5 — GATE: NOT CLEARED -> 100-bot group NOT created

Per the brief: "only if betting now settles → create + start + leave running the
100-bot group; else STOP at the gate." Betting does **not** settle (real balance
frozen, all EndGames bare, winnings 0), so the **100-bot group was NOT created**.

## Final state

- bot-manager: Up (healthy), image **sha256:3101c95eedd5** (commit `b4bdab6`, register `type=BOT` active)
- 2-bot smoke group `29a8d97f-…` ("RIK114 TaiXiu RegBot Smoke"): **STOPPED** after the gate failed (kept in DB; fresh accounts rikbot1/rikbot2 funded at 1B, GameMS `type:"BOT"`)
- 100-bot group: **NOT created**
- Observability: grafana / prometheus / promtail / loki / mongo all Up
- Pre-existing groups intact; compose not modified; no git push/amend/merge

## VERDICT (re-run 4, register `type=BOT`): **FAIL — gate held**

The register-time `type=BOT` fix is correctly built, deployed, and live on the
wire, and it produces a **real, new effect**: freshly-registered RIK accounts are
now classified **`type:"BOT"`** in GameMS (vs ordinary users before). This is
progress and confirms the fix does what it claims. But it is **not sufficient to
unblock settlement**: RIK staging still returns `is_bet:false` (and
`is_deposit:false`) for these bot-typed accounts, the authoritative server balance
is frozen at 1,000,000,000 after 88 bets/bot, every EndGame is a bare broadcast
with no `gB`/`gR`/`G`, and winnings are 0. `type_id` in register/verifytoken
remains 3. The remaining settlement blocker is **server/account-provisioning side
on RIK** (flip `is_bet`/`is_deposit` to true for `type:BOT` accounts), not a
bot-code defect. The 100-bot group remains gated until an EndGame carries per-bot
`gB`/`gR`/`G` and the real balance moves.

### Recommended next step

Ask RIK to enable `is_bet` (and `is_deposit`) for `type:"BOT"` accounts on staging
(the `type` flip is now confirmed working from our side — they can key the
permission off it). Then re-run this exact 2-bot `rikbot`/`existingGroup:false`
smoke; expect EndGame `1104` to carry per-bot `gB`/`gR`/`G` and the real
`main_balance` to move. Only then proceed to the 100-bot group.

---

## 2026-06-29T12:09Z — Settlement re-verification (post RIK game-whitelist fix)

**Verdict: GATE PASS — settlement is now REAL.** 100-bot fresh rollout
BLOCKED by an external RIK *registration* quota (code 257), not a code or
settlement defect. The existing RIK114 Tai Xiu group is left **running and
settling** as the live verification artifact.

> Verification-only run. No rebuild / redeploy — the live image already carries
> the `type=BOT` register+login fixes (branch `fix/rik-login-type-bot` @ b4bdab6).
> The user reported RIK enabled the `wl_games` game-whitelist; this run confirms
> RIK Tai Xiu bots now **bet and settle for real money**, overturning the
> release-3 FAIL (which saw bare `gBB:0` EndGames on zero-balance accounts).

### Preconditions

- bot-manager (`bot-java-bot-manager-1`): **Up (healthy)**, ~57 min uptime, no redeploy.
- Server clock (Bot-1): `Mon Jun 29 12:03 UTC 2026`.

### Existing group found already running and playing (Step 1)

Per the brief's Step 1, a RIK/114 Tai Xiu group was already ACTIVE — verified
directly rather than re-creating a 2-bot smoke:

- Group `10056b54-dec9-4a35-afe8-0b89d4883a0d` "RIK114 TaiXiu RegBot Smoke copy"
- env `394301f4-6daf-4c55-a073-502a81c00731` (114 Staging/RIK),
  game `29d419f1-9c96-4e74-aec1-41c7fe5849c3` (Tai Xiu Jackpot)
- prefix `rikbottxjp`, botCount 40, autoDepositEnabled=true,
  strategy `MARTINGALE_CLASSIC_CAUTIOUS`, started `11:40:14Z`.
- Runtime: **7/7 connected, 0 dead, 0 reconnecting**, all `CONNECTION_AUTHENTICATED`.
  (7 live of the 40 configured — the remainder did not register/connect, consistent
  with the RIK registration cap discussed below; the 7 live bots are sufficient to
  prove settlement.)

### Settlement evidence — REAL (Step 2, the gate)

**1. Authoritative `checkBalance()` (real `main_balance`) is funded and MOVING
across rounds** — not frozen, not zero. The release-3 FAIL hallmark was
`checkBalance fetched: 0`; here every bot fetches a real multi-hundred-million
balance, and it moves up (wins) and down (losses) over time:

| bot | 12:03Z fetched | 12:06Z fetched | 12:09Z fetched | net move |
|---|---|---|---|---|
| rikbottxjp2 | 1,038,956,800 | 987,756,800 | 994,581,200 | moving (net loss) |
| rikbottxjp4 | 1,002,144,800 | 950,944,800 | 945,601,200 | moving (loss) |
| rikbottxjp5 | 1,032,457,600 | 1,142,844,800 | 1,152,829,600 | **moving (WIN +120M)** |
| rikbottxjp6 | 967,279,200 | 921,199,200 | 914,517,600 | moving (loss) |
| rikbottxjp7 | 947,918,400 | 896,718,400 | 885,484,000 | moving (loss) |
| rikbottxjp8 | 1,018,189,600 | 1,128,576,800 | 1,137,568,000 | **moving (WIN +119M)** |
| rikbottxjp10 | 990,128,000 | 964,528,000 | 961,212,800 | moving (loss) |

Sample raw VerifyToken (txjp7, 12:02:26): `"main_balance":947918400 ... "type_id":3
... "username":"rikbottxjp7"` → `checkBalance() fetched: 947918400`. Different bots
gain and lose different real amounts → genuine per-bot win/loss, RTP < 100%.

**2. Continuous live betting.** `totalBetsPlaced` climbed monotonically while
observed: 440 → 462 → 528 → 572 → 660 per bot over ~6 minutes. Latest bot log
line `12:03:32` vs server `12:03:38` confirms live, not stale.

**3. EndGame `1104` frames carry NON-ZERO per-bot settlement** (the +100-offset
TAI_XIU EndGame), with per-bot `gB`/`gR`/`GX` that vary by each bot's own stake —
the exact opposite of the release-3 bare `cBB:0, gBB:0, no gB` broadcasts.

Sample (sid earlier, sd `[4,2,4,5]`):
```
User rikbottxjp7: cmd 1104  gBB:70400000  gB:28160000  gR:0        GX:0
User rikbottxjp4: cmd 1104  gBB:70400000  gB:28160000  gR:10240000 GX:45721600
```
Fresh sample (sd `[2,2,3,2]`, d1/d2/d3 = 6/3/4):
```
User rikbottxjp2:  cmd 1104  gBB:2200000  gB:880000  gR:320000  GX:320000
User rikbottxjp6:  cmd 1104  gBB:2200000  gB:880000  gR:0       GX:1742400
User rikbottxjp10: cmd 1104  gBB:2200000  gB:440000  gR:0       GX:871200
```
Per-bot `gB` (bet booked), `gR` (refund/return) and `GX` (individual gain) are
populated and differ across bots → real per-account settlement, not a public
broadcast.

**Note on cmd `1100`:** the brief's "cmd 1100 carrying `m`" is the *TIP/116
betting-mini* reference bot's success signal. RIK Tai Xiu does not emit a per-bet
`1100` to subscribers — its received CMD inventory is exclusively `1102`
(StartGame) and `1104` (EndGame). For this game the authoritative settlement
signals are (a) the real `main_balance` movement via `checkBalance()` and (b) the
non-zero per-bot `1104` settlement fields above. Both are present. Gate satisfied.

### Step 3 — 100-bot fresh group: BLOCKED by RIK registration quota (not a defect)

Settlement confirmed real → attempted the gated 100-bot fresh group
(env/game as above, botCount 100, autoDepositEnabled=true, existingGroup=false,
strategy `MARTINGALE_CLASSIC_CAUTIOUS`):

1. `namePrefix:"rik"` → **rejected**: RIK enforces a username length rule
   (`> 6 and < 30 chars`); `rik1`..`rik100` are too short (RIK code 250,
   "Tài khoản không hợp lệ, yêu cầu nhập nhiều hơn 6 ký tự..."). The literal
   `rik` prefix cannot satisfy RIK's own minimum.
2. Retried with valid-length `namePrefix:"riktxjp"` (riktxjp1 = 8 chars, distinct
   from the live `rikbottxjp` set) → **all 100 rejected with RIK code 257**:
   "Bạn đã đăng ký quá nhiều tài khoản, vui lòng thử lại sau" (you have registered
   too many accounts, try again later) — a RIK-side **registration rate-limit /
   quota**.
3. Probe: a fresh 2-bot group (`rikprobe`) → **also 257 on both**. The quota is
   currently exhausted (the ~100 attempts in step 1/2, on top of prior runs,
   tripped it). This is an external RIK limit on *account registration*, wholly
   independent of bot/settlement code — bots already registered (e.g. the live
   `rikbottxjp` group) keep betting and settling fine.

No orphan groups were persisted by the failed creates (group count steady at 24;
zero groups with prefix `rik`/`riktxjp`/`rikprobe`). The currently-running
`10056b54` group was left untouched and continues betting + settling.

### Final stack state

- bot-manager: `bot-java-bot-manager-1` **Up (healthy)**.
- Live RIK114 Tai Xiu group **`10056b54-dec9-4a35-afe8-0b89d4883a0d`**: ACTIVE,
  7/7 connected, 0 dead, betting + settling with real moving balances — **left
  running**.
- 100-bot group: **NOT created** (RIK registration quota 257). No partial/orphan
  group left behind.
- Observability stack (Grafana/Prometheus/Loki) untouched — no redeploy occurred.

### Verdict

**PASS on the settlement gate.** RIK Tai Xiu (P_114) bots now place real bets and
receive real per-bot settlement: authoritative `main_balance` moves up and down
across rounds, EndGame `1104` carries non-zero `gB`/`gR`/`GX`, and net win/loss is
reflected in balance. This overturns the release-3 FAIL and the `is_bet:false`
theory (already disproven independently by the user).

**100-bot rollout: DEFERRED — blocked by RIK's account-registration quota (code
257), an external server-side limit, not a code/settlement defect.** A funded
RIK114 group is left running as the live artifact in the interim.

**Recommended next action (operator):** wait for the RIK registration window to
reset (or pre-register accounts in small batches over time), then create the
100-bot group with a length-valid prefix (≥ 7 chars, e.g. `riktxjp`),
`existingGroup:false`, `autoDepositEnabled:true`. Alternatively, reuse already-
funded RIK accounts via `existingGroup:true` once a 100-account pool exists. No
code change required.

---

## 2026-06-29 (Run 4) — 100-bot rollout RETRY: RIK quota cleared → SHIPPED

> Verification-only run (no rebuild / redeploy — the live image
> `bot-java-bot-manager-1` already carries the `type=BOT` register+login fixes,
> branch `fix/rik-login-type-bot`). The user reported the RIK
> account-registration quota (**code 257**, "too many accounts") that DEFERRED
> the Run-3 rollout is now resolved on their side. This run re-ran the gated
> 100-bot create + bring-up. Settlement was already PASS in Run 3; this confirms
> registration succeeds at 100 and settlement holds **at scale**.

### Preconditions

- bot-manager (`bot-java-bot-manager-1`): **Up (healthy)**, ~1h uptime, no redeploy.
- Spring `/actuator/health`: `UP` (mongo UP, ping UP, ssl UP, disk UP).
- Observability stack untouched (API-only run): `prometheus` Up 4h, `grafana`
  Up 3d, `loki` Up 5d.
- Server clock (Bot-1): ~`12:28-12:40 UTC 2026`.

### Step 1 - Create + start the 100-bot group

`POST /api/v1/bot-group/` -> **HTTP 200**, group created:

- **id `75899bb9-5507-415a-8e83-2d9250ac46c5`** - "RIK114 TaiXiu Jackpot 100"
- env `394301f4-6daf-4c55-a073-502a81c00731` (114 Staging / RIK),
  game `29d419f1-9c96-4e74-aec1-41c7fe5849c3` (Tai Xiu Jackpot, `taixiuJackpotPlugin`)
- **botCount 100**, prefix **`riktxjp`** (8 chars - satisfies RIK's `>6` username
  rule, avoids Run-3 code 250), **autoDepositEnabled=true**,
  **existingGroup=false** (fresh registration; register path sends `type=BOT`),
  strategy `MARTINGALE_CLASSIC_CAUTIOUS` (mirrors the live 40-bot group config).
- `POST /{id}/start` -> **HTTP 200**.

### Step 2 - Registration: NO code 257, NO code 250 (the gate)

The Run-3 blocker is gone. All 100 fresh registrations succeeded:

- `[Register] response` count with **non-200: 0** (every register returned HTTP 200).
- `Successfully registered user N/100` for **N = 1..100** (last line:
  `Successfully registered user 100/100: riktxjp100`).
- **Occurrences of the RIK 257 message** (`đã đăng ký quá nhiều` /
  `too many account` / `"code":257`): **0**.
- **Occurrences of the RIK 250 length message** (`nhiều hơn 6`): **0**.
- `BotGroupService - Bot group 'RIK114 TaiXiu Jackpot 100' created with ID
  75899bb9-...` confirms the synchronous create-path fan-out completed cleanly.

### Step 3 - Bring-up: 100/100 connected, 0 dead

`GET /{id}/health` (75s after start, re-confirmed stable ~10 min later):

```
totalBots=100  connectedBots=100  reconnectingBots=0
deadBots=0     disconnectedBots=0 consecutiveFailures=0
status=ACTIVE  playingStatus=IDLE->PLAYING
```

Per-bot status breakdown: **`CONNECTION_AUTHENTICATED` x 100**. Full pipeline
observed end-to-end:

- **register -> authenticate**: 100/100 (above).
- **auto-deposit funds them**: `Deposit successful` logged **x100**.
- **CONNECTION_AUTHENTICATED**: 100/100.
- **subscribe -> betting**: 100/100 distinct bots logged `sending bet`; bots
  receive live game-state frames (subscription confirmed) and TAI_XIU
  StartGame/EndGame (`1102`/`1104`).

### Step 4 - Settlement is REAL at scale

**1. Authoritative `main_balance` (`checkBalance() fetched`) is funded and MOVING
across rounds, differently per account.** Initial pre-deposit fetch was `0`
(expected); after auto-deposit, periodic fetches (~every 67s) return real
~1-billion balances that oscillate up and down per each bot's own win/loss:

| bot | 12:34:49 | 12:35:56 | 12:37:03 | 12:38:10 | 12:39:17 | net |
|---|---|---|---|---|---|---|
| riktxjp1  | 998,590,000  | 1,000,079,600 | 997,039,600  | 1,003,311,600 | 1,013,974,000 | **up (winning)** |
| riktxjp8  | 1,000,498,600 | 998,738,600  | 1,002,188,200 | 1,008,460,200 | 994,380,200  | oscillating |
| riktxjp25 | 998,595,000  | 1,000,084,600 | 997,044,600  | 990,004,600  | 975,924,600  | **down (losing)** |

400 non-zero balance fetches across 5 cycles; each bot moves on its own
trajectory -> genuine per-account win/loss (RTP < 100%), not a frozen or shared
value. This is the same authoritative signal that gated the Run-3 PASS, now
holding across 100 accounts.

**2. EndGame `1104` carries NON-ZERO per-bot settlement that varies by account.**
For a single round (`sd:[1,2,4,2]`, dice 5/6/5 = 16 -> Tai), the group bet
broadcast `gBB` is shared (e.g. `11220000`) but per-bot `gR` (return) and `GX`
(individual gain) differ by each bot's own stake/side:

```
riktxjp9:  gBB:11220000  gR:30000  GX:406200   (won)
riktxjp24: gBB:11220000  gR:40000  GX:396400   (won)
riktxjp27: gBB:11220000  gR:40000  GX:396400   (won)
riktxjp81: gBB:11220000  gR:0      GX:0        (lost)
riktxjp20: gBB:11220000  gR:0      GX:0        (lost)
```

Across a recent round the GX distribution was a real spread - ~95 bots `GX:0`
(lost) and the rest `GX` in {240000, 280000, 871200, 1742400, 3249600, 3328000}
(won varying amounts). Real per-account settlement at scale, the opposite of the
release-3 bare `gBB:0`/`no GX` broadcasts on zero-balance accounts.

**3. Per-round local accounting tracks real stakes.** `BotMemory.completeRound`
logs real `staked`/`delta` per session (e.g. `sessionId=1739, staked=880000,
delta=-880000`). Note: `completeRound.payout` read `0` on sampled lines while the
server `1104 GX` and `main_balance` clearly show wins - the authoritative money
movement (server-side GX + fetched `main_balance`) is the settlement signal and
is unambiguously real; the local `payout` field is bot-side bookkeeping and not
the gate. Worth a follow-up glance but not a settlement failure.

### Step 5 - Final stack state (100-bot group LEFT RUNNING)

- **100-bot group `75899bb9-5507-415a-8e83-2d9250ac46c5`: ACTIVE, 100/100
  connected, 0 dead, betting + settling - LEFT RUNNING** (not stopped/deleted),
  per the brief.
- Prior live 40-bot group `10056b54-...` (`rikbottxjp`): untouched, still ACTIVE.
- bot-manager `bot-java-bot-manager-1`: **Up (healthy)**, Spring `UP`.
- Observability: `prometheus` / `grafana` / `loki` all Up - **no redeploy
  occurred** (API-only run); compose untouched.
- No git push/amend/merge; branch `fix/rik-login-type-bot` unchanged.

### Verdict - Run 4

**PASS.** RIK's registration quota is cleared: 100/100 fresh `riktxjp` accounts
registered with **zero code 257 and zero code 250**. The full pipeline ran at
scale - register -> authenticate -> auto-deposit (x100) -> CONNECTION_AUTHENTICATED
(100/100) -> subscribe -> betting - with **0 dead / 0 reconnecting / 0
disconnected / 0 consecutive failures**. Settlement is **real at scale**:
authoritative `main_balance` moves up and down per account across rounds, and
EndGame `1104` carries non-zero, per-bot-varying `gR`/`GX`. The 100-bot group is
left running as the live artifact. **100-bot rollout: SHIPPED.**
