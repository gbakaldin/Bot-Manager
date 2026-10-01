# Release — RIK_114_ZICZAC

# Phase 2 — the ziczac bet body (`b` + `c:1`, no `eid`) — 2026-09-18

Mode: **bot**
Target: **Bot-1 (staging)** — `16.162.36.69:22000`, `/home/sgame/bot-java` (confirmed staging, not prod)
Branch: `feature/dead-group-auto-recovery`
Image: `vingame-bot:latest`, sha256 `fe476c05f6a11313d46b46fd3c55605740c4b95b5206c4a577c857ec7db96650`, linux/amd64, built 2026-09-18T11:06:00Z
Date: 2026-09-18T10:19Z (start) – 11:25Z (last check)

Ships `docs/plans/RIK_114_ZICZAC.md` **Phase 2** as redefined by Amendment A1 ("What Phase 2 now is"):
`ZicZacBet`, `ZicZacRequest`, `RikZicZacGameMessageTypes implements GameRequestFactory`, the widened
`GameRequestFactoryCapabilityTest`. Phase 1 (the ziczac message layer) was already live on this box
from the 2026-09-17 RIK_114_BETTING_MINI release. Pre-release verdicts: qa PASS, review PASS,
compliance PLAN_AMENDED (diff accepted as-is).

**Verdict in one line: the deploy is clean, the new `{cmd,b,c:1,sid,aid}` body is on the wire, the
server appends each ball as a slot, and — for the first time — ziczac rounds settle: `mbs` is
non-empty, `confirmed staked` > 0, winnings > 0, and both bots' server balances have moved off the
pristine 1,000,000,000.**

## Authorisation

The releaser's standing rule is to wait for the user's own go-ahead before `docker compose down`.
The "go" for this run arrived from the pipeline coordinator, citing the pipeline launch plus the
user's recorded "pipeline autonomy" preference (chain architect → dev → qa/review/compliance →
releaser without asking between steps). It was treated as sufficient for **staging** and is recorded
here so the provenance of the approval is not ambiguous. Nothing in this run touched any host other
than Bot-1.

## Provenance — the build is not reproducible from any commit

`git rev-parse HEAD` = `2b212473bad125d0ce9091204abfc11ebd28bb55` — unchanged since the
RIK_114_BETTING_MINI Phase 1 release. **No commit exists for any part of the RIK feature** (Phase 1
and Phase 2 of both RIK plans). The whole feature lives in the working tree: 58 paths per
`git status --short` (18 modified, 5 deleted `TaiXiuMessages/*.js`, the rest untracked — the entire
`bot-messages/.../g3/rik/` package, the RIK tests, the plan/review docs). The tree was built **as-is**
on explicit instruction; nothing was committed, staged, stashed, checked out, amended or pushed by
this run. Reproducing this image requires this exact working tree on this exact machine. The
`git status --short` snapshot taken at 10:19:45Z is identical in shape to the one recorded in
`docs/reviews/RIK_114_BETTING_MINI/release.md` § Provenance, plus the Phase 2 ziczac files and
`docs/reviews/RIK_114_ZICZAC/*`.

`deploy.sh` was **not** shipped or run — its uncommitted delta is the `secrets.env` → `.env` merge,
which is already in place on the box from earlier releases.

## Pre-flight

- Concurrent `mvn` check (`ps`): **none running** at 10:19:45Z. (Context: a concurrent
  `clean install` wiped `bot-api/target` mid-run during QA earlier today.)
- Dirty tree: **expected and intended** — see Provenance. The "never deploy from a dirty tree" rule
  was consciously overridden by the task brief for this feature, as it was for RIK_114_BETTING_MINI.

## Build

- `mvn clean install` (JDK 21.0.2): **PASS** — 1 min 55 s wall; surefire aggregate **2301 tests,
  0 failures, 0 errors, 0 skipped** (matches the last QA figure; only javadoc changed since).
  Artifact `bot-app/target/Bot-1.0.jar`, 61,775,177 bytes.
- `docker build --no-cache --platform linux/amd64`: **PASS** — but only on the **fourth** attempt.
  Not a code problem; recorded so the next release does not lose 45 minutes to it:
  1. Docker Desktop was not running; started it.
  2. Build hung indefinitely at `load metadata for docker.io/library/eclipse-temurin:21-jre`.
     Restarting Docker Desktop once produced a `com.docker.virtualization: Process terminated
     unexpectedly` dialog; a hard kill + relaunch brought the daemon back with working VM
     networking (IPv4 resolve + TCP to `registry-1.docker.io` verified from inside a container).
  3. Still hung on the same step. Root cause: three wedged `docker-credential-desktop get`
     processes — `~/.docker/config.json` has `"credsStore": "desktop"`, and the Keychain-backed
     helper blocks (no interactive prompt possible) on every registry call, including anonymous
     base-image pulls.
  4. Worked around with `DOCKER_CONFIG=<scratch dir>` holding `{"auths":{}}` (no `credsStore`),
     so the base-image pull is anonymous. Pull completed in seconds; build then succeeded.
     `~/.docker/config.json` was **not** modified. If this recurs, the same env var is the fix.
- `docker save`: **PASS** — `bot.tar` 393,211,904 bytes, md5 `38bc9fc2c11354e0dfec812502c93e05`.

## Ship

- `sftp put bot.tar` → `Bot-1:/home/sgame/bot-java`: **PASS** — remote size 393,211,904, remote
  md5 `38bc9fc2c11354e0dfec812502c93e05` = local.

## Deploy (11:11:33Z – 11:12:05Z)

- `docker compose down`: **PASS**
- `docker image rm vingame-bot:latest`: **PASS** (prior image `c990784836bc`, the 2026-09-17 build,
  removed)
- `docker load -i bot.tar`: **PASS** (`Loaded image: vingame-bot:latest`)
- `docker compose up -d`: **PASS**

## Smoke test

- `docker ps` shows `bot-java-bot-manager-1 … (healthy)`: **PASS** (healthy on first poll, ~45 s
  after up). Running image id = `sha256:fe476c05…` (the new build).
- Spring Boot ready log: **PASS** — `11:12:14.923 … Started Starter in 8.934 seconds`
- Auto-start log: **PASS** — `11:12:14.265 … Bot Manager startup complete. 9 bot groups running`

### Single-compose-project re-verification (Grafana / Prometheus / Loki / Alertmanager)

All ten containers came back (`grafana`, `prometheus`, `alertmanager`, `bot-manager`, `promtail`,
`mongo`, `loki`, `node-exporter`, `evidence-shim`, `viptalk-shim`):

- Grafana `GET /api/health` → 200 `{"database":"ok","version":"11.4.0"}`: **PASS**
- Prometheus `/-/ready` → 200; scrape targets `bot-manager` **up**, `node` **up**: **PASS**
- Loki `/ready` → 503 `Ingester not ready: waiting for 15s after being ready` on first poll, 200
  `ready` on the second; post-restart line `startup complete. 9 bot groups running` is queryable
  in Loki, 42 lines ingested in the last 10 min: **PASS**
- Alertmanager: no host port published (000 from the host is expected); `/-/ready` from inside
  the container → `OK`: **PASS**

### Auto-start, per group (coordinator's note 2)

All 9 groups with `targetStatus=ACTIVE` before the deploy are ACTIVE again with every bot
connected, 0 dead, 0 reconnecting. Nothing was restarted by hand.

| Group | Name | After restart |
|---|---|---|
| `2bf237bd` | Slot group 120 | ACTIVE, 20/20 connected |
| `40fa3749` | XD game test | ACTIVE, 20/20 connected |
| `88d46075` | RIK114 ZicZac Probe | ACTIVE, 2/2 connected, rounds advancing |
| `1134449f` | RIK114 TaiXiuMd5 Probe | ACTIVE, 2/2 connected, rounds advancing |
| `cd77131c` | RIK114 Coins Probe | ACTIVE, 2/2 connected, rounds advancing |
| `50dd0560` | RIK114 Slot 201 Test 20 | ACTIVE, 20/20 connected, 49 rounds in 2 min |
| `57b99074` | 119 Tai Xiu probe | ACTIVE, 10/10 connected |
| `c93c82a5` | 119 Xoc Dia probe | ACTIVE, 10/10 connected |
| `97314241` | 119 Bau Cua probe | ACTIVE, 10/10 connected |

The 28 groups with `targetStatus` DEAD / STOPPED / null were, correctly, not started
(`bot.recovery.enabled` is whatever `.env` says on the box; no recovery attempt was observed or
needed in this window).

## Plan verification — Amendment A1 § "Verification for Phase 2" (V-Z1 … V-Z5)

Baseline captured at 11:10Z on the **old** build, same JVM the plan's baseline describes:
`bot_bets_placed_total{88d46075}` **0.0**, `bot_bet_amount_total` **0.0**, `bot_winnings_total`
**0.0** after 3,714 rounds / 25,186 bot-side sends (`/health`: `totalBetsPlaced` 12582 + 12604);
`lastFetchedBalance` exactly 1,000,000,000 on both bots. Controls at baseline: stock `cd77131c`
6053 bets / 18,294,000 staked / 17,861,975 won (RTP 0.976); txmd5 `1134449f` 3800 bets /
586,920,000 / 589,891,500 (1.005).

### V-Z1: `bot_bets_placed_total{88d46075…}` climbs at ~round rate; `confirmed staked:` > 0
Command:
```
curl -s http://localhost:8080/actuator/prometheus | grep -E "^bot_(bets_placed_total|bet_amount_total|winnings_total)\{" | grep 88d46075
grep 88d46075 logs/detail/detail.log | grep "confirmed staked"
```
Expected: counter climbing, roughly 1–2 per bot per round from up to 5 sends; `confirmed staked` > 0.
Actual:

| Time (Z) | rounds since restart | `bets_placed` | `bet_amount` | `winnings` |
|---|---|---|---|---|
| 11:15:20 | 6 | 60 | 5,280,000 | 4,326,000 |
| 11:22:24 | 20 | 200 | 17,580,000 | 18,534,000 |
| 11:24:39 | 24 | 240 | 21,240,000 | 22,038,000 |

Exactly **5 per bot per round** (10 per round), i.e. **every** send settles — the plan's "1–2 of
5" expectation was pessimistic; all five balls land as five `mbs` slots (see V-Z3). Every
post-deploy EndGame summary has `confirmed staked` > 0 (e.g. `1998264 … confirmed staked:
900000`, `1998269 … 660000`, `1998273 … 900000`); before the deploy it was 0 on every round.
Result: **PASS**

Note on the DEBUG group summary, not a defect of this build: on some rounds the aggregated line
reports a single bot's figures rather than the sum (round 1998272: `total staked: 900000 |
total win: 624000 | confirmed staked: 360000`, where the frames show rikzzc1 settled 540,000 /
won 558,000 and rikzzc2 settled 360,000 / won 624,000 — the line is rikzzc2's alone). Other
rounds (1998273: 900,000 = both) sum correctly. It looks like a race between the two Netty
processor threads inside `SessionAggregationService`'s per-round accumulator; it is DEBUG-only,
pre-existing, and the per-bot Prometheus counters (which are what V-Z1 names) are consistent
with the frames. Worth a FOLLOWUPS entry; not blocking.

### V-Z2: `bot_winnings_total` non-zero; RTP over ≥ 200 rounds in 0.93–0.98
Command: the metrics above; per-frame `sum(mbs[].r)` vs `p.wm` from the TRACE capture.
Expected: winnings > 0; RTP 0.93–0.98 over ≥ 200 rounds; sustained > 1.0 = wrong-field trap.
Actual:
- `bot_winnings_total` 0 → 22,038,000 in 24 rounds: **non-zero, PASS** (the short-window
  falsifiable check).
- Stake matches sends: **PASS** — 6/6 bot-rounds with both sides captured have
  `sum(sent b) == sum(mbs[].b)`.
- Field trap: `sum(mbs[].r) == p.wm` in **8/8** EndGames captured; the two readings agree, so
  whichever `HasBotWinnings` reads, it is the same number. **Not the `p.wm` trap.**
- RTP band: **PENDING** — only 24 rounds at last read. Metric RTP 22,038,000 / 21,240,000 =
  **1.038**; TRACE-window (4 rounds) 1.405. Both are far inside the variance of a 40-entry sample
  on an odds table that pays 0.0–8.0×, and the 200-round window (~100 min at 30 s rounds) is
  outside this session (coordinator's note 1). **Re-read after ~12:55Z:**
  `bot_winnings_total{88d46075} / bot_bet_amount_total{88d46075}` since the 11:12Z restart. If
  it is still > 1.0 *then*, re-read plan §4 as the plan instructs — but the frame evidence above
  says the field is right, so a sustained > 1 would be a game-side RTP question, not a parsing
  one.
Result: **PASS (short-window) / PENDING (200-round band)**

### V-Z3: 90 s TRACE window — outbound `12002` with `b`, `c:1`, no `eid`; its echo; `12006` with non-empty `mbs`
Command:
```
POST /actuator/loggers/com.vingame.bot {"configuredLevel":"TRACE"}   # 11:18:04Z → 204
POST /actuator/loggers/com.vingame.bot {"configuredLevel":"DEBUG"}   # 11:20:06Z → 204, verified effectiveLevel DEBUG
grep 88d46075 logs/detail/detail.log | grep " TRACE "                # 392 lines; 80× 12002, 8× 12005, 8× 12006
```
Level before: `DEBUG/DEBUG` (staging's `BOT_LOG_LEVEL`); after: `DEBUG/DEBUG`. `console.log`
carried **0** TRACE and **0** DEBUG lines throughout (AD-24 `ThresholdFilter` holding), so nothing
reached Loki. Read from `logs/detail/detail.log` on the box as instructed.
Expected: outbound `{"cmd":12002,"b":<stake>,"c":1,"sid":<sid>,"aid":1}` with no `eid`; `in
12002` echo; following `12006` with non-empty `mbs`.
Actual — `rikzzc1`, round `1998272`, verbatim from the detail log (ANSI stripped):
```
[SENT]     ["6","MiniGame","ziczacPlugin",{"cmd":12002,"b":120000,"c":1,"sid":1998272,"aid":1}]
[RECEIVED] [5,{"bs":[{"eid":0,"b":120000}],"cmd":12002}]
[SENT]     ["6","MiniGame","ziczacPlugin",{"cmd":12002,"b":60000,"c":1,"sid":1998272,"aid":1}]
[RECEIVED] [5,{"bs":[{"eid":0,"b":120000},{"eid":1,"b":60000}],"cmd":12002}]
[SENT]     ["6","MiniGame","ziczacPlugin",{"cmd":12002,"b":120000,"c":1,"sid":1998272,"aid":1}]
[RECEIVED] [5,{"bs":[{"eid":0,"b":120000},{"eid":1,"b":60000},{"eid":2,"b":120000}],"cmd":12002}]
[SENT]     ["6","MiniGame","ziczacPlugin",{"cmd":12002,"b":120000,"c":1,"sid":1998272,"aid":1}]
[RECEIVED] [5,{"bs":[{"eid":0,"b":120000},{"eid":1,"b":60000},{"eid":2,"b":120000},{"eid":3,"b":120000}],"cmd":12002}]
[SENT]     ["6","MiniGame","ziczacPlugin",{"cmd":12002,"b":120000,"c":1,"sid":1998272,"aid":1}]
[RECEIVED] [5,{"bs":[{"eid":0,"b":120000},{"eid":1,"b":60000},{"eid":2,"b":120000},{"eid":3,"b":120000},{"eid":4,"b":120000}],"cmd":12002}]
[RECEIVED] [5,{"p":{"uid":"15_17970","jwm":0,"u":"rv_rikzzc1","wm":558000,"dn":"dinhhanhtan1624xx","m":1000066000},
             "obs":[{"p":30,"b":60000,"r":120000,"odd":2.0},{"p":13,"b":60000,"r":0,"odd":0.0},{"p":38,"b":60000,"r":72000,"odd":1.2},{"p":41,"b":120000,"r":360000,"odd":3.0},{"p":34,"b":60000,"r":72000,"odd":1.2}],
             "tJpV":808300,"cmd":12006,"jps":["-","-","-"],"iJp":false,"sid":1998272,
             "mbs":[{"p":29,"b":120000,"r":36000,"odd":0.3},{"p":13,"b":120000,"r":144000,"odd":1.2},{"p":42,"b":120000,"r":0,"odd":0.0},{"p":36,"b":120000,"r":360000,"odd":3.0},{"p":2,"b":60000,"r":18000,"odd":0.3}]}]
```
- Outbound key set exactly `{cmd,b,c,sid,aid}`, `c == 1`, **no `eid`** — checked programmatically
  across all **40** sends in the window.
- Echo appends server-assigned `eid` 0→4 (Amendment A1 item 5 / OI-2 "append", reconfirmed).
- `12006 sid:1998272` → `mbs` has **5 entries**, `sum(b)` = 540,000 = the five sends;
  `sum(r)` = 558,000 = `p.wm`. `rikzzc2` same round: sends 360,000, `mbs` 360,000, `wm` 624,000 =
  `sum(r)`.
- Over the whole window: 8 EndGames, 40 `mbs` entries, **0 empty `mbs`**.
- Side observation for OI-4, no action here: `rikzzc2`'s `obs` in this round is byte-for-byte
  `rikzzc1`'s `mbs` and vice versa — with two bots in the room, `obs` is the *other* player's
  settled balls.
Result: **PASS**

### V-Z4: stock control `cd77131c…` keeps settling; txmd5 `1134449f…` keeps its ~1:1
Command: metrics as in V-Z1; `grep cd77131c|1134449f logs/detail/detail.log | grep "confirmed staked"`.
Expected: both still settling; either going flat = capability-test widening broke routing → stop.
Actual (11:10Z baseline → 11:24:39Z, i.e. since the restart):

| Group | bets | staked | won | short-window ratio | long-run baseline |
|---|---|---|---|---|---|
| stock `cd77131c` | 0 → 44 | 130,000 | 116,288 | 0.895 | 0.976 |
| txmd5 `1134449f` | 0 → 22 | 3,165,000 | 2,851,200 | 0.901 | 1.005 |

Stock round summaries since deploy all show `confirmed staked` > 0 (e.g. `3801038 … total win:
5733 … confirmed staked: 5000`); txmd5 likewise (`2477178 … total win: 445500 … confirmed
staked: 225000`). Neither is flat; both short-window ratios are small-sample noise around their
baselines. Routing intact: stock still emits `RikStockBet`, txmd5 still the shared `Bet` (their
post-deploy frames were not TRACE-captured — only the ziczac group was — but settlement continuing
on both is the plan's own criterion).
Result: **PASS**

### V-Z5: the game must not re-freeze — subscribe-ack `rmT` positive and `sid` advancing after 30 min
Command: `grep 88d46075 logs/detail/detail.log | grep "entered session"` first/last; TRACE `12005`
frames; watchdog / reconnect count.
Expected: `rmT` > 0 on the subscribe ack; `sid` still advancing after 30 min with correct-body
bets flowing.
Actual:
- `sid` **1998260** at 11:12:27Z → **1998283** at 11:23:57Z: 23 rounds in 11.5 min, one every
  30.0 s, no gap, with correct-body bets in every round. `12005` frames in the TRACE window:
  1998272, 1998273, 1998274 consecutive.
- 0 watchdog expiries, 0 reconnects, health monitor `playing: 2, reconnecting: 0, dead: 0/2` on
  every 30 s tick since restart.
- **`rmT` not captured**: the only subscribe ack of this JVM fired at 11:12Z auto-start, at DEBUG,
  where `OutputPrinter` (TRACE) does not print it, and the 90 s TRACE window contained no
  re-subscribe. Forcing one would have meant restarting the probe group, which was out of scope.
- The 30-minute mark (~11:42Z) is past this session (coordinator's note 1).
Result: **PARTIAL — PENDING** (12 min of the 30 observed, no freeze; `rmT` unobserved). To close:
after 11:42Z confirm `roundsSinceRestart` in `GET /api/v1/bot-group/88d46075…/health` is still
climbing (~2/min); for `rmT`, the next TRACE window that includes a (re)subscribe, or a
`/restart` of the probe group under TRACE.

## Pre-existing noise, checked and excluded

- `BotMemory.completeRound: sessionId mismatch (EndGame sessionId=…, in-flight sessionId=0)` —
  255 in the previous 2 h file and ~4/min today up to **11:11:14Z**; **none after the deploy**.
  Consistent with the old body never registering an in-flight round; gone with the fix.
- `ERROR … Periodic logout failed for bot rikzzc1 … PingScheduler …` at **11:07:35Z** — old build,
  pre-deploy; the known periodic-logout vs ws-parser `PingScheduler` interaction. Not recurred.

## Verdict

**PASS** — build, ship, deploy, smoke and observability all clean; V-Z1, V-Z3, V-Z4 PASS; V-Z2 and
V-Z5 PASS on every check the window could falsify, with the ≥ 200-round RTP band and the 30-min /
`rmT` re-freeze check recorded as **PENDING** rather than failed. Nothing observed contradicts the
feature; the pending items are elapsed-time checks, not open questions about this build.

Follow-ups surfaced (none blocking): the `SessionAggregationService` single-bot summary race
(V-Z1 note); the Docker Desktop credential-helper hang on this workstation (Build); `rmT` is only
observable at subscribe time, so V-Z5 as written needs a TRACE window that spans one.

## Logs

Not required (verdict PASS). Raw captures kept in the session scratchpad only:
`ziczac-12006.txt`, `ziczac-sent.txt`, `baseline-88d.txt`, `health-after.jsonl`, `mvn.log`,
`docker-build.log`.

---

## Hotfix redeploy 2026-09-21 — EXISTED display-name retry

Mode: **bot**
Target: **Bot-1 (staging)** — `/home/sgame/bot-java` (the only host touched)
Branch: `feature/dead-group-auto-recovery`, HEAD still `2b212473bad125d0ce9091204abfc11ebd28bb55`
Image: `vingame-bot:latest`, sha256 `f284ca5390c30b6e0fd0b5220937ea788f9a1357f8a2f14c43264573f7e2c51b`, linux/amd64, built 2026-09-21T05:55:15Z
Date: 2026-09-21T05:52Z (pre-flight) – 06:06Z (last check)

Ships one change on top of the 2026-09-18 Phase 2 build above:
`bot-engine/.../infrastructure/client/ApiGatewayClient.java` — `setDisplayName` now classifies gateway
status `EXISTED` (RIK/P_114's spelling of "display name already used", code 409, *"Tên hiển thị đã được
sử dụng"*) the same as `INVALID`: it returns `false` so `setDisplayNameWithRetry` re-rolls, instead of
falling through to the generic throw that aborted the retry loop on the first collision. New
`isDisplayNameTaken(String)` helper; the per-collision line drops WARN → DEBUG. New test
`ApiGatewayClientDisplayNameTakenStatusTest` (8 cases, parameterised over `INVALID` / `EXISTED` plus
non-taken statuses).

Background, confirmed from the retained `console*.log` on the box: **19** `Failed to set display name
for rikzz…: … (status: EXISTED)` WARNs at 12:48Z on 2026-09-18 — exactly the 19/100 nameless bots in
the "Zic Zac" group `1804a704` that froze the ziczac room until they were named by hand. The same
shape had already hit twice before, unnoticed: 3 on 2026-09-15 11:24Z (`rikslt7`, …) and 3 on
2026-09-16 13:xxZ, both RIK slot groups.

### Authorisation and provenance

Same footing as the 2026-09-18 run: the go came from the pipeline coordinator citing the user's
recorded "pipeline autonomy" preference, treated as sufficient for **staging**. The tree was built
**as-is** on explicit instruction — 60 paths dirty per `git status --short` (the 58 of the RIK feature
plus `ApiGatewayClient.java` modified and the new test untracked); nothing committed, staged, stashed,
checked out, amended or pushed. Still not reproducible from any commit. `deploy.sh` not shipped or run.

### Pre-flight

- Concurrent `mvn` (`ps`): **none** at 05:52:54Z.
- Docker Desktop daemon up (`24.0.6`), 0 wedged `docker-credential-desktop` processes. The
  `DOCKER_CONFIG=<scratch>/dockercfg` (`{"auths":{}}`) workaround from the 09-18 run was exported
  **up front** for `docker build` / `docker save`; `~/.docker/config.json` untouched.

### Build

- `mvn clean install` (JDK 21.0.2): **PASS** — 1 min 09 s; surefire aggregate **2309 tests, 0
  failures, 0 errors, 0 skipped** (2301 on 09-18 + the 8 new cases). `Bot-1.0.jar` 61,775,255 bytes.
- `docker build --no-cache --platform linux/amd64`: **PASS** on the **first** attempt, 32 s — the
  credential-helper workaround is the whole difference from 09-18's four attempts.
- `docker save`: **PASS** — `bot.tar` 393,211,904 bytes, md5 `bfc0797ab96640e02554a434651335bd`.

### Ship

- `sftp put bot.tar` → `Bot-1:/home/sgame/bot-java`: **PASS** (05:55:51Z – 06:00:27Z); remote size
  393,211,904, remote md5 `bfc0797ab96640e02554a434651335bd` = local.

### Baseline on the old JVM (06:00:59Z, image `fe476c05…`, up 2 days)

10 groups with `targetStatus=ACTIVE` reporting counters. `bot_bets_placed_total{1804a704}` =
**3,997,966** (the "~3.94M" in the brief, plus two more days of play); `/health` 100/100 connected,
0 dead, 0 reconnecting. Stock `cd77131c` 19,128; txmd5 `1134449f` 10,816.

### Deploy (06:01:41Z – 06:02:11Z)

- `docker compose down`: **PASS**
- `docker image rm vingame-bot:latest`: **PASS** (prior image `fe476c05…`, the 09-18 build, removed)
- `docker load -i bot.tar`: **PASS** (`Loaded image: vingame-bot:latest`)
- `docker compose up -d`: **PASS**

### Smoke test

- `docker ps` shows `bot-java-bot-manager-1 … (healthy)`: **PASS** (healthy at 38 s after up;
  running image `sha256:f284ca53…` = the new build)
- Spring Boot ready log: **PASS** — `06:02:20.896 … Started Starter in 9.239 seconds`
- Auto-start log: **PASS** — `06:02:20.018 … Bot Manager startup complete. 10 bot groups running`

Single-compose-project re-verification — all ten containers back:
- Grafana `GET /api/health` → 200 `{"database":"ok","version":"11.4.0"}`: **PASS**
- Prometheus `/-/ready` → 200; targets `bot-manager` **up**, `node` **up**: **PASS**
- Loki `/ready` → 503 `Ingester not ready: waiting for 15s` on the first poll (06:03:11Z), 200
  `ready` on the second (06:03:32Z); `startup complete. 10 bot groups running` queryable in Loki:
  **PASS**
- Alertmanager `/-/ready` from inside the container → `OK`: **PASS**

### Verification (task brief, no plan section)

#### 1. Standard smoke + observability stack back
Covered above. Result: **PASS**

#### 2. Every ACTIVE-target group back with all bots connected; `1804a704` 100/100, 0 dead, counter climbing
Command: `GET /api/v1/bot-group/{id}/health` for every group with a `bot_bets_placed_total` series;
`/actuator/prometheus` sampled at 06:03:59Z, 06:04:21Z, 06:06:08Z.
Expected: 10 groups ACTIVE, every bot connected, 0 dead; Zic Zac 100/100 and its counter rising.
Actual (06:06:08Z — nothing restarted by hand):

| Group | Name | After restart |
|---|---|---|
| `1804a704` | **Zic Zac** | ACTIVE, **100/100** connected, 0 dead, 0 reconnecting |
| `88d46075` | RIK114 ZicZac Probe | ACTIVE, 2/2 |
| `cd77131c` | RIK114 Coins Probe | ACTIVE, 2/2 |
| `1134449f` | RIK114 TaiXiuMd5 Probe | ACTIVE, 2/2 |
| `50dd0560` | RIK114 Slot 201 Test 20 | ACTIVE, 20/20 |
| `2bf237bd` | Slot group 120 | ACTIVE, 20/20 |
| `40fa3749` | XD game test | ACTIVE, 20/20 |
| `57b99074` | 119 Tai Xiu probe | ACTIVE, 10/10 |
| `c93c82a5` | 119 Xoc Dia probe | ACTIVE, 10/10 |
| `97314241` | 119 Bau Cua probe | ACTIVE, 10/10 |

`bot_bets_placed_total{1804a704}` (counter restarts at 0 with the JVM, so "climbing again" is read
as the slope since 06:02:18Z):

| Time (Z) | `bets_placed` | `bet_amount` | `winnings` |
|---|---|---|---|
| 06:03:59 | 1,000 | 90,360,000 | 105,876,000 |
| 06:04:21 | 1,500 | 135,420,000 | 143,556,000 |
| 06:06:08 | 3,000 | 268,920,000 | 267,372,000 |

Exactly 500 per round (5 balls × 100 bots), one round per ~30 s, as on 09-18. Every post-deploy
EndGame summary for the group has `confirmed staked` > 0 (`2006205 … 1,320,000`, `2006206 …
900,000`, `2006209 … 420,000`, `2006210 … 420,000`; 10 summaries by 06:06Z). 0 watchdog expiries,
0 reconnects in the group.
Result: **PASS**

#### 3. Stock `cd77131c` and txmd5 `1134449f` still settling
Command: metrics as above; `grep <id> logs/detail/detail.log | grep "confirmed staked"`.
Expected: both counters rising with `confirmed staked` > 0; either flat = stop.
Actual (06:03:59Z → 06:06:08Z):

| Group | bets | staked | won | `confirmed staked` lines |
|---|---|---|---|---|
| stock `cd77131c` | 6 → 14 | 46,000 | 50,657 | 11, all > 0 (e.g. `3808542 … 5000`, `3808543 … 3000`) |
| txmd5 `1134449f` | 2 → 6 | 950,000 | 1,148,400 | 4, all > 0 (e.g. `2481181 … 285000`, `2481182 … 380000`) |

Result: **PASS**

### Noise checked and excluded

- `Can't invoke task later as EventLoop rejected it` × 294 — every one stamped 06:01:42.95–.99Z, i.e.
  the **old** JVM's `compose down` teardown. None from the new build.
- `BotMemory.completeRound: sessionId mismatch (… in-flight sessionId=0)` burst 06:02:2x–06:02:5x
  (112 in one 10 s bucket = the 100 Zic Zac bots' first EndGame after joining mid-round). **None
  after 06:02:5x.** The pre-deploy trickle (~6 per 30 s) is the same pre-existing shape noted on 09-18.
- `Bot xdt3st28: WS disconnected — starting retrial flow` once at 06:02:16Z; XD group reads 20/20
  connected afterwards.
- **0 WARN / 0 ERROR** in `console.log` from 06:03Z to the last check.

### What this deploy does not prove

The changed path (`EXISTED` → re-roll) only executes during **registration**, and this run created
no group (per the brief, the parent does that next). The evidence that the fix is *on the box* is
the image id + the 8 green unit cases; the evidence that it *works against the RIK gateway* is the
next 100-bot RIK group registering with `100/100` display names set and **zero** `Failed to set
display name … (status: EXISTED)` lines in `console.log`. That is the check to run on the group the
parent creates.

### Verdict

**PASS** — build, ship, deploy, smoke and observability clean; all 10 ACTIVE groups back with every
bot connected; Zic Zac `1804a704` 100/100 and settling at 500 bets/round; stock and txmd5 controls
settling. Nothing pending on this build itself; the end-to-end proof of the hotfix is deferred to the
next registration by design.

### Logs

Not required (verdict PASS). Session scratchpad only: `mvn.log`, `docker-build.log`.
