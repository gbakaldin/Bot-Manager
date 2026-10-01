# RIK 114 probe bots — display-name check (gateway side)

Date: 2026-09-17T12:52Z
Host: Bot-1 (staging), read-only. No build, no deploy, no compose, no registration, no deposit.
Method: replayed each bot's own most recent `[Login] POST` line from
`/home/sgame/bot-java/logs/detail/detail*.log` verbatim (same URL, same `X-TOKEN`,
same JSON body including the logged `fg` fingerprint) with `curl` **from Bot-1**.
Gateway: `https://api-gwrik.sgame.us/gwms/v1/bot/login.aspx`. All six replays
answered HTTP 200 / `"status":"OK","code":200,"message":"Login successful"`.

Question: does each account have a display name set? The answer is `data[0].fullname`
in the login response.

## Result

| username | group | `fullname` returned | login status | matches our registration log? |
|---|---|---|---|---|
| `rikzzc1`  | RIK114 ZicZac Probe (`88d46075`)   | **field absent** (no `fullname`, no `ref_id`) | 200 OK | yes — `Failed to set display name for rikzzc1: ... (status: EXISTED)` |
| `rikzzc2`  | RIK114 ZicZac Probe (`88d46075`)   | `"baoxnv"`         | 200 OK | yes — set at registration (success line was DEBUG, aged out; gateway confirms) |
| `rikcoin1` | RIK114 Coins Probe (`cd77131c`)    | **field absent** (no `fullname`, no `ref_id`) | 200 OK | yes — `Failed to set display name for rikcoin1: ... (status: EXISTED)` |
| `rikcoin2` | RIK114 Coins Probe (`cd77131c`)    | `"ueernxszy2985"`  | 200 OK | yes — set at registration (as above) |
| `riktxm1`  | RIK114 TaiXiuMd5 Probe (`1134449f`) | `"viet4702xn"`    | 200 OK | yes — set at registration (as above) |
| `riktxm2`  | RIK114 TaiXiuMd5 Probe (`1134449f`) | **field absent** (no `fullname`, no `ref_id`) | 200 OK | yes — `Failed to set display name for riktxm2: ... (status: EXISTED)` |

**6 of 6 agree with our registration log.** Exactly one bot per probe group is nameless,
and it is the one whose `SetName` call returned `EXISTED` on 2026-09-16 13:53Z.

### ZicZac pair

- **`rikzzc1` is the nameless bot.** Its login response has no `fullname` key at all.
- `rikzzc2` has display name `baoxnv`.

If the RIK backend misbehaves when a nameless user enters a game, `rikzzc1` is the
bot whose presence may be killing the ZicZac round feed. Note the ZicZac group's
`/health` shows `totalBetsPlaced: 0` for **both** bots and the pair has been in the
180 s watchdog reconnect loop since 2026-09-16 13:56Z, so if the theory holds, one
nameless member is enough to starve the whole room, not just itself.

### One correction to the brief

The brief said the gateway returns the literal placeholder `"_undefined"` for an
unnamed account. On this gateway (`api-gwrik.sgame.us`, `bot/login.aspx`) that is
**not** what happens: the `fullname` key is **omitted entirely** from `data[0]`, and so
is `ref_id`. For the three named accounts both keys are present and `ref_id` equals
`fullname`. Any check that greps for `_undefined` will read all six as named; check
for **presence of the `fullname` key** instead. (`"_undefined"` may be what a different
endpoint or a different brand's gateway emits; it was not observed here.)

## Verbatim `data[0]` per nameless account (tokens redacted)

`rikzzc1`:
```
{"main_balance":1000000000,"uid":"42851140000017970738","type_id":3,"session_id":"ba1eb9…","p":false,
 "token":"15-16d…","token2":"eyJhbG…","username":"rikzzc1","level":"LEVEL0","is_club":false,"type":"BOT",
 "force_up":false,"member_id":1802423,"uuid":"42851140000017970738",
 "last_change_password":"2026-09-16T13:53:03.017Z","id":17970}
```

`rikcoin1`:
```
{"main_balance":999997000,"uid":"86451140000017967962","type_id":3,"session_id":"a30a9d…","p":false,
 "token":"15-d4f…","token2":"eyJhbG…","username":"rikcoin1","level":"LEVEL0","is_club":false,"type":"BOT",
 "force_up":false,"member_id":1802420,"uuid":"86451140000017967962",
 "last_change_password":"2026-09-16T13:53:02.600Z","id":17967}
```

`riktxm2`:
```
{"main_balance":1012508500,"uid":"07651140000017968109","type_id":3,"session_id":"ff04a7…","p":false,
 "token":"15-150…","token2":"eyJhbG…","username":"riktxm2","level":"LEVEL0","is_club":false,"type":"BOT",
 "force_up":false,"member_id":1802421,"uuid":"07651140000017968109",
 "last_change_password":"2026-09-16T13:53:02.830Z","id":17968}
```

For comparison, a named account (`rikzzc2`) carries the two extra keys:
`..., "fullname":"baoxnv","username":"rikzzc2","level":"LEVEL0","ref_id":"baoxnv", ...`

## Replay inputs (redacted)

| username | source `[Login]` line timestamp (UTC) | thread | `X-TOKEN` | `fg` |
|---|---|---|---|---|
| `rikzzc1`  | 2026-09-17T12:50:24.472 | `reconnect-rikzzc1`  | `58bc28…` | `f298e7a233c88c9980a7d90dc707fbe788954` |
| `rikzzc2`  | 2026-09-17T12:51:37.116 | `reconnect-rikzzc2`  | `58bc28…` | `f298e7a233c88c9980a7d90dc707fbe7937` |
| `rikcoin1` | 2026-09-17T12:32:00.690 | `reconnect-rikcoin1` | `58bc28…` | `f298e7a233c88c9980a7d90dc707fbe727370` |
| `rikcoin2` | 2026-09-17T11:28:54.333 | `bot-creation-91`    | `58bc28…` | `f298e7a233c88c9980a7d90dc707fbe71249` |
| `riktxm1`  | 2026-09-17T11:28:54.517 | `bot-creation-92`    | `58bc28…` | `f298e7a233c88c9980a7d90dc707fbe71169` |
| `riktxm2`  | 2026-09-17T11:28:54.517 | `bot-creation-93`    | `58bc28…` | `f298e7a233c88c9980a7d90dc707fbe759365` |

All six share the same `X-TOKEN` (the per-environment gateway key). Body fields other
than `fg`/`username`/`password` were identical across all six: `os "OS X"`,
`device "Computer"`, `browser "chrome"`, `version`/`apVer "1.1.977"`,
`ip "16.162.36.69"`, `type "BOT"`, `app_id "rik.vip"`, `aff_id "RIKVIP"`.
The `fg` values were replayed exactly as logged; none were invented.

## Registration-log evidence (track 1, `console*.log`, 2026-09-16)

```
13:53:02.722 WARN ApiGatewayClient user-registration-0  Failed to set display name for rikcoin1: Failed to set display name: Tên hiển thị đã được sử dụng (status: EXISTED)
13:53:02.887 WARN ApiGatewayClient user-registration-1  Failed to set display name for riktxm2: Failed to set display name: Tên hiển thị đã được sử dụng (status: EXISTED)
13:53:03.097 WARN ApiGatewayClient user-registration-0  Failed to set display name for rikzzc1: Failed to set display name: Tên hiển thị đã được sử dụng (status: EXISTED)
```

The success path is `log.debug("Set display name '{}' for user {}", ...)`
(`ApiGatewayClient.java:264`), i.e. track 2 only, and track 2's 12 h retention has
already dropped 2026-09-16 13:53Z. So the three *named* values above come solely from
the gateway; there is no surviving local record of what name was chosen for them.
`Tên hiển thị đã được sử dụng` = "display name already in use".

## Side effects check

A login issues a fresh `session_id`/`token`; in principle that could invalidate the
bot's live session. Checked after all six replays (12:53:46Z):

- `/health` for all three groups: `status ACTIVE`, `connectedBots 2/2`,
  `reconnectingBots 0`, `deadBots 0`.
- `rikcoin1`/`rikcoin2` received a session message at 12:53:37Z, `riktxm1`/`riktxm2`
  began a round at 12:53:33Z — i.e. after the replays. No WARN/ERROR, no
  `VerifyToken` non-200, no disconnect for any of the four in the trailing log.
- The ZicZac pair was already in its 3-minute watchdog reconnect loop before this
  check and is unchanged by it.

## Incidental observations (not the question asked)

- The RIK login response **does** carry `token2` (a JWT whose payload embeds
  `username`, the agency `token`, `member_id`, `agency_id: 15`). CLAUDE.md's Token
  Naming table says `jwtToken (token2)` is "not yet in register response"; on this
  brand's *login* response it is present. Redacted here; not written anywhere.
- All six accounts still sit at essentially their deposited `main_balance`
  (`~1.000e9`), consistent with the RIK_114 release's open "confirmed staked=0 /
  balances pristine" question; `riktxm1`/`riktxm2` show small drift
  (`1002091400`, `1012508500`), so TaiXiuMd5 at least is settling.

## Suggested next step (for the parent, not done here)

If the nameless-user theory is to be tested rather than inferred: set a display name
on `rikzzc1` (the app's `SetName` path with a fresh random name, or by hand) and watch
whether the ZicZac round feed starts. This report deliberately did **not** do that —
the task was read-only and setting a name is a write against the account.

---

# Follow-up: names set by hand, groups restarted, two experiments (2026-09-17T13:00–13:09Z)

Host: Bot-1 (staging). Authorised writes only: (1) `update-fullname.aspx` on three accounts,
(2) `POST /restart` on the three RIK probe groups. No build, no deploy, no compose, no
registration, no deposit, no bet-config change (Coins stays `minBet = maxBet = 1000`).
Everything ran **from Bot-1** (`ssh Bot-1 'bash -s' < script`). Tokens redacted to 6 chars.

## Step 1 — display names

Endpoint: `POST https://api-gwrik.sgame.us/gwms/v1/bot/update-fullname.aspx`,
`Content-Type: application/json`, `X-TOKEN: 58bc28…`, body `{"username","fullname"}`.
Retry policy was up to 8 attempts per user on `EXISTED`/`INVALID` with a fresh stem +
random 4-digit tail each time; **none was needed — all three stuck on attempt 1.**

| username | attempts | names tried | final name | gateway response | gateway-verified (login replay) |
|---|---|---|---|---|---|
| `rikzzc1`  | 1 | `dinhhanhtan1624xx`   | `dinhhanhtan1624xx`   | HTTP 200 `{"status":"OK","code":200,"message":"Cập nhật tên hiển thị thành công"}` | yes — `data[0].fullname = "dinhhanhtan1624xx"`, `ref_id` same |
| `rikcoin1` | 1 | `dinhthuytrang5985xx` | `dinhthuytrang5985xx` | HTTP 200, same `OK` body | yes — `fullname = "dinhthuytrang5985xx"`, `ref_id` same |
| `riktxm2`  | 1 | `viethoang4319`       | `viethoang4319`       | HTTP 200, same `OK` body | yes — `fullname = "viethoang4319"`, `ref_id` same |

(`Cập nhật tên hiển thị thành công` = "display name updated successfully".)

Verification method: replayed each user's most recent `[Login] POST` line from
`detail*.log` verbatim (same URL, `X-TOKEN: 58bc28…`, same body incl. logged `fg`:
`…88954` / `…27370` / `…59365`), all `"status":"OK","code":200`. The `fullname` key,
**absent** in the 12:52Z check, is now **present** on all three and equals the value set.

Second, independent confirmation from the app itself after the restart: the bots' own
`[VerifyToken] response` lines in `detail.log` carry the new names —
`rikzzc1` @13:01:06.919Z `"fullname":"dinhhanhtan1624xx"`,
`rikcoin1` @13:01:09.239Z `"fullname":"dinhthuytrang5985xx"`,
`riktxm2` @13:01:11.474Z `"fullname":"viethoang4319"`.
So the game side saw named users on the sessions that followed.

## Step 2 — restart

| group | id | `POST /restart` | time (UTC) |
|---|---|---|---|
| RIK114 ZicZac Probe    | `88d46075…` | HTTP 200 | 13:01:04 |
| RIK114 Coins Probe     | `cd77131c…` | HTTP 200 | 13:01:06 |
| RIK114 TaiXiuMd5 Probe | `1134449f…` | HTTP 200 | 13:01:08 |

`/health` at 13:02:29Z (T0, ~85 s after the first restart) and again at 13:08:35Z: all
three `status: ACTIVE`, `connectedBots 2/2`, `reconnectingBots 0`, `deadBots 0`, every
bot `CONNECTION_AUTHENTICATED`. Per-bot `totalBetsPlaced` (this counter *does* reset on
restart): ZicZac `0/0 → 0/0`; Coins `15/15 → 70/70`; TaiXiuMd5 `5/5 → 37/36`.

## Step 3 — readings

Actuator counters are JVM-lifetime and tagged by group; they do not reset on a group
restart, so only the **delta** column matters. A pre-restart baseline (13:00:34Z) is
included so the restart itself is not mistaken for signal.

| metric | pre-restart 13:00:34Z | T0 13:02:29Z | T0+6 13:08:35Z | **delta T0→T0+6** |
|---|---|---|---|---|
| Coins `bot_bets_placed_total` | 0 | 0 | 0 | **0** |
| Coins `bot_messages_total{cmd=endGame}` | 348 | 354 | 378 | **+24** (rounds covered) |
| Coins `bot_messages_total{cmd=startGame}` | 349 | 357 | 379 | +22 |
| Coins `confirmed staked:` (last 5 EndGame lines in `detail.log`) | `0` ×5 | `0` ×5 | `0` ×5 (sessions 3798537–3798541) | unchanged |
| ZicZac `bot_messages_total{cmd=startGame}` | series absent (404) | absent (404) | absent (404) | **no series** |
| ZicZac `bot_messages_total{cmd=endGame}` | series absent (404) | absent (404) | absent (404) | **no series** |
| ZicZac `bot_bets_placed_total` | 0 | 0 | 0 | 0 |
| ZicZac `full reconnect triggered` count (`grep -c`, `rikzzc`, live `detail.log`) | 38 | 39 (the 39th is 13:00:41Z, *before* the restart) | 43 | **+4** post-restart: 13:04:10 (both bots), 13:07:12 (both bots) — the 180 s watchdog cadence, unchanged |
| TaiXiuMd5 `bot_bets_placed_total` (control) | 182 | 186 | 198 | **+12** |
| TaiXiuMd5 `bot_messages_total{cmd=endGame}` (control) | 184 | 188 | 200 | **+12** |

Notes on the table, stated not interpreted:
- The brief expected ZicZac `startGame` to read `2`; on the current JVM the series does
  not exist at all (HTTP 404 from `/actuator/metrics`), before and after. Nothing
  created it during the window.
- The Coins group's `/health` `totalBetsPlaced` rose 15→70 per bot over the same window
  in which the Prometheus `bot_bets_placed_total{botGroupId=cd77131c…}` stayed at 0.0,
  and every EndGame line still reports `total staked: 10000 | bettors: 2 | confirmed
  staked: 0 | total win: 0`. Same shape as before today.
- The Coins EndGame cadence in this window was ~32 s (24 rounds in 6 min), not the ~11
  the brief estimated; the delta is what counts.
- Raw WS frames are TRACE and staging runs at DEBUG, so no frame-level dump of the
  ZicZac subscribe exists in `detail.log` to cite; the only ZicZac activity after
  13:01:10Z (`STARTED → CONNECTION_AUTHENTICATED`) is the four watchdog reconnects.

## Verdicts

- **Experiment A (Coins stock settlement with no nameless member): still zero.**
  `bot_bets_placed_total` delta 0 over 24 EndGame rounds; `confirmed staked: 0` on every
  round; `total win: 0`.
- **Experiment B (ZicZac round feed with no nameless member): still dead.** No
  `startGame`/`endGame` series appeared, 0 bets, and the watchdog fired 4 times
  (2 per bot) at its usual 180 s cadence inside the window.
- **Control (TaiXiuMd5): holds.** `bets_placed` delta 12 vs `endGame` delta 12 (1:1),
  as it has always been.

Both nameless-user hypotheses were tested as specified and neither metric moved. The
three accounts remain named (the write is persistent on the gateway), so the fleet is
now in the state the registration retry would have produced had it handled `EXISTED`.

---

# ZicZac relaunch after server-side game restart (2026-09-17T14:07–14:26Z)

Host: Bot-1 (staging). Authorised writes: `POST /start` on the ZicZac probe group
(`88d46075…`) once, and two `/actuator/loggers/com.vingame.bot` toggles (TRACE on at
14:07:29.871Z, DEBUG restored at 14:09:00.189Z, verified `{"configuredLevel":"DEBUG",
"effectiveLevel":"DEBUG"}`). No build, no deploy, no compose, no registration, no deposit.
Coins and TaiXiuMd5 groups untouched. Group **left running** at the end. Box clock is
UTC+7; all times below are UTC.

Context: at 11:41Z a TRACE frame showed the ziczac subscribe ack with `"sid":1995721,
"gS":2,"rmT":-77029518` — the round engine had been stuck on one session since ~14:15Z
Sep 16. The user restarted the game server-side; this section is the relaunch.

## Verdicts

- **Game alive.** The first frame our bots received after subscribing was the EndGame of
  session **1995731** at 14:07:35.761Z — ten sessions past the frozen `1995721` — and
  StartGame/EndGame have arrived every 30 s since (session 1995768 by 14:26:05Z, +37
  sessions in 18.5 min). The `rmT`/`tFB`/`tFP` fields of the `12000` ack were **not
  captured** (see "Step 1 gap" below); the verdict rests on the advancing `sid` and the
  30 s round cadence, not on `rmT`.
- **Feed alive.** `startGame` 26 → 74 (+48 = 24 rounds × 2 bots) and `endGame` 26 → 76
  (+50) over the 12-minute window; **0** watchdog reconnects since the start (baseline 0
  in the live `detail.log`, still 0 at 14:26Z); 37 distinct sessions entered in an
  unbroken 30 s cadence. The pre-freeze "2 StartGames then nothing" cut-off did **not**
  recur.
- **Did not re-freeze after join** (as of 14:26Z, 18.5 min after the group joined —
  yesterday's freeze began ~20 min after join, so the window covers it only barely).
  Step 4 was not triggered: the feed never died and no reconnect fired.

## Step 1 — start and TRACE window

| | |
|---|---|
| `POST /actuator/loggers/com.vingame.bot {"configuredLevel":"TRACE"}` | 14:07:29.871Z → `{"configuredLevel":"TRACE","effectiveLevel":"TRACE"}` |
| `POST /api/v1/bot-group/88d46075…/start` | 14:07:29.896Z → HTTP **200**; `startedAt` `14:07:29.908Z` |
| `sleep 90`, then `{"configuredLevel":"DEBUG"}` | 14:09:00.189Z → `{"configuredLevel":"DEBUG","effectiveLevel":"DEBUG"}` (re-verified 14:26Z and 14:37Z) |

Login → WS handshake → `CONNECTION_AUTHENTICATED` for both bots in 1.9 s
(`rikzzc1` 14:07:29.909 → 14:07:31.782, `rikzzc2` likewise). Both `VerifyToken` reads
returned `main_balance 1000000000`.

### Step 1 gap — no raw frames were printed, and why

`grep -h 'rikzzc' detail.log | grep -E '\[SENT\]|\[RECEIVED\]'` returned **nothing**.
`com.vingame.bot.domain.bot.util.OutputPrinter` emitted **zero** lines during the window
for *any* of the ~26 running bots, while the other `com.vingame.bot` TRACE loggers did
(3,644 `BettingMiniGameBot`, 2,443 `RandomBehaviorStrategy`, 1,200 `SlotMachineBot`,
600 `FixedBetStrategy` lines in 14:07:29–14:09:00). Not a queue drop:
`log4j2_async_queue_full_samples_total = 0`, `_pressure_samples_total = 0`,
`log4j2_async_queue_remaining = 24576`.

Cause: the `OutputPrinter` logger carries an **explicit level**:

```
GET /actuator/loggers/com.vingame.bot.domain.bot.util.OutputPrinter
{"configuredLevel":"INFO","effectiveLevel":"INFO"}
```

An explicit child level overrides the parent, so `com.vingame.bot=TRACE` never reaches
the frame printer. It is a leftover from the 11:39–11:41Z capture: that window contains
**5,178 `OutputPrinter` TRACE lines and no other TRACE logger at all** — i.e. it was
produced by raising `…util.OutputPrinter` individually, and it was then restored to
`INFO` instead of cleared (`configuredLevel: null`). The state pre-dates this task and
was **left as found** (the coordinator asked that nothing else be touched). Anyone
following the CLAUDE.md recipe ("drill `com.vingame.bot` to TRACE for raw frames") will
get nothing until that override is cleared:
`POST /actuator/loggers/com.vingame.bot.domain.bot.util.OutputPrinter {"configuredLevel":null}`.

Consequently the requested `12000` ack fields (`sid`, `gS`, `rmT`, `tFB`, `tFP`), the
verbatim `12005`/`12006` frames and the `12002` echoes are **not available** for this
window. What *is* available from DEBUG/TRACE lines of our own loggers:

| item | value |
|---|---|
| first game message after subscribe | **EndGame (12006) session 1995731 @ 14:07:35.761Z** — the tail of the round in progress when we joined (`BotMemory.completeRound: sessionId mismatch (EndGame sessionId=1995731, in-flight sessionId=0)`, expected on a cold join) |
| **first StartGame (12005)** | **session 1995732 @ 14:07:48.760Z** (`SessionAggregationService … entered session 1995732`) |
| subsequent StartGames in window | 1995733 @ 14:08:18.764Z, 1995734 @ 14:08:48.768Z |
| EndGames in window | 1995731 @ 14:07:35.761, 1995732 @ 14:08:05.763, 1995733 @ 14:08:35.766 |
| round shape | StartGame → EndGame **17 s**, EndGame → next StartGame **13 s**; period **30.0 s** |
| outbound bets (`sending bet`, TRACE) | 5 per bot per round at 1 s spacing, t+0…t+4 s after StartGame, `option=1`, amounts 12,000 / 60,000 (RANDOM strategy). e.g. session 1995732: `rikzzc2` 60000,60000,60000,12000,60000; `rikzzc1` 12000,12000,60000,12000,12000 |
| server-side UpdateBet echo (5 s aggregate) | `UpdateBet #1 … total bettors this round: 2 | total staked: 480000 | options: [1]x6`, `#2 … total staked: 900000 | [1]x4` — the server **is** echoing our bets back as bet-updates |
| EndGame summary, every round | `total staked: <sum of our bets> | total win: 0 | bettors: 2 | confirmed staked: 0` |

## Step 2 — health (T0 = 14:09:00Z, end of TRACE window)

`status ACTIVE`, `playingStatus IDLE`, `connectedBots 2/2`, `reconnectingBots 0`,
`deadBots 0`, both `CONNECTION_AUTHENTICATED`, `roundsSinceRestart 3`,
`totalBetsPlaced 15/15`, `totalBetAmount 1,560,000 / 1,260,000`.

## Step 3 — 12-minute observation

Readings at 14:14:01Z (T0′) and 14:26:06Z (T0′+12). Actuator series are JVM-lifetime and
were **absent (404) before this start** on this JVM — everything in them is from today's
relaunch.

| metric | 14:14:01Z | 14:26:06Z | **delta** |
|---|---|---|---|
| `bot_messages_total{cmd=startGame}` | 26 | 74 | **+48** (24 rounds × 2 bots) |
| `bot_messages_total{cmd=endGame}` | 26 | 76 | **+50** (25 rounds × 2 bots) |
| `bot_bets_placed_total` | 0.0 | 0.0 | 0 (known-broken counter — see memory note "bot_bets_placed_total never increments"; `/health` says 185/185) |
| `full reconnect triggered` (`grep -c`, `rikzzc`, live `detail.log`) | 0 | **0** | **0** |
| distinct sessions entered since start | — | 37 (1995732 … 1995768) | 30.0 s cadence, last six StartGames 14:23:18.874, :48.878, 14:24:18.882, :48.885, 14:25:18.889, :48.891 |
| WARN/ERROR for `rikzzc*` since start (excl. the cold-join `sessionId mismatch`) | 0 | 0 | none |
| `/health` `totalBetsPlaced` | 15 / 15 | **185 / 185** | +170 each (5 per round × 34 rounds) |
| `/health` `totalBetAmount` | 1.56 M / 1.26 M | 16.14 M / 17.28 M | |
| `/health` local `balance` vs `lastFetchedBalance` | 998.44 M / 998.74 M vs 1,000 M | 994.12 M / 992.86 M vs **1,000 M** | local drift only; below the 10 M re-fetch threshold, so the server balance has not been re-read since start |
| `roundsSinceRestart` | 13 | 38 | |

Last 3 EndGame summaries at 14:26Z, verbatim minus the sample class:

```
14:25:05.888 session 1995766 ended | total staked: 900000  | total win: 0 | bettors: 2 | confirmed staked: 0
14:25:35.890 session 1995767 ended | total staked: 1140000 | total win: 0 | bettors: 2 | confirmed staked: 0
14:26:05.893 session 1995768 ended | total staked: 840000  | total win: 0 | bettors: 2 | confirmed staked: 0
```

`BotMemory.completeRound` for every round: `payout=0, staked=<our sum>, delta=-<staked>`
(e.g. 1995768: `rikzzc1` staked 420000, `rikzzc2` staked 420000). **Zero of 38 rounds
had `confirmed staked` ≠ 0 or `total win` ≠ 0.** Whether that is the wrong ziczac bet
body (`eid` present, no `c` — Phase 2 unbuilt), RIK's `HasBotWinnings` parsing, or
settlement, is not decidable from these lines; the server does echo the bets as
UpdateBets, and the server-side balance has not been re-fetched yet.

## Step 4 — not run

Conditions not met: the feed did not die and no watchdog reconnect fired. No second
TRACE window was opened. (One was queued for the group's first periodic-logout cycle at
15:07:30Z — `scheduleAtFixedRate(…, interval, interval)` — to catch a fresh `12000` ack
via the `OutputPrinter` logger alone; it was **cancelled before it armed**, on the
coordinator's instruction, and the orphaned remote shell was killed. Verified at 14:37Z:
no lingering `bash -s` on Bot-1, `com.vingame.bot` DEBUG, `OutputPrinter` INFO as found.)

## End state

- ZicZac probe group `88d46075…`: **running**, ACTIVE 2/2, not stopped.
- Coins `cd77131c…` and TaiXiuMd5 `1134449f…`: not touched.
- Loggers: `com.vingame.bot` = DEBUG (staging default, restored 14:09:00Z);
  `com.vingame.bot.domain.bot.util.OutputPrinter` = explicit INFO (pre-existing, left
  as found — see Step 1 gap).
- Nothing committed or pushed.

## Open items for the parent

1. The `12000` ack fields (`rmT`, `tFB`, `tFP`, `gS`) are still unread for the relaunched
   game. Cheapest non-disruptive capture: clear the `OutputPrinter` override, then raise
   `…util.OutputPrinter` (not the parent) to TRACE for ~90 s around the group's next
   periodic-logout re-subscribe (15:07:30Z, then hourly), or around any `/restart`.
2. The re-freeze question is answered only to 18.5 min after join; yesterday's freeze
   started ~20 min after join. A later `rmT` read (item 1) or a watchdog-reconnect count
   at ~15:10Z would close it.
3. `confirmed staked: 0` on all 38 rounds despite the server echoing the bets — the
   ziczac bet-body (Phase 2) question is now testable on a live game.
