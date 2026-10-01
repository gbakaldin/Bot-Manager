# Release — RIK_114_BETTING_MINI (+ RIK_114_ZICZAC)

# Phase 2 — the RIK stock bet body (`v` + `iAc`) — 2026-09-17

Mode: **bot**
Target: **Bot-1 (staging)** — `/home/sgame/bot-java` (confirmed staging, not prod)
Branch: `feature/dead-group-auto-recovery`
Image: `vingame-bot:latest`, sha256 `c990784836bc`, built 2026-09-17T11:27:00Z
Date: 2026-09-17T11:24Z – 11:45Z

Ships `docs/plans/RIK_114_BETTING_MINI.md` **Phase 2** only. Phases 1/1b are already live on
this box from the 2026-09-16 release logged below. Phase 3 is **not** in this build.

**Verdict in one line: the deploy is clean, the new frame is on the wire and the server
acknowledges every bet in-round — and the round still settles as if we never bet.**

## Provenance — the build is not reproducible from any commit

`git rev-parse HEAD` = `2b212473bad125d0ce9091204abfc11ebd28bb55` (unchanged from the Phase 1
release; **no commit was made for Phase 2**). The entire feature lives in the working tree.
Nothing was committed, amended or pushed by this run.

```
$ git status --short
 M Aviator.js
 D TaiXiuMessages/Bet.js
 D TaiXiuMessages/End.js
 D TaiXiuMessages/Start.js
 D TaiXiuMessages/Subscribe.js
 D TaiXiuMessages/SubscribeResponse.js
 M bc.js
 M bot-api/src/main/java/com/vingame/bot/domain/bot/message/GameMessageTypes.java
 M bot-app/src/main/java/com/vingame/bot/domain/bot/service/BotFactory.java
 M bot-app/src/test/java/com/vingame/bot/ApplicationContextLoadsTest.java
 M bot-engine/src/main/java/com/vingame/bot/domain/bot/core/BettingMiniGameBot.java
 M bot-messages/src/test/java/com/vingame/bot/domain/bot/message/MessageTypesCoverageTest.java
 M bot-messages/src/test/java/com/vingame/bot/domain/bot/message/MessageTypesRegistryTest.java
 M bot-messages/src/test/java/com/vingame/bot/domain/bot/message/request/RequestTest.java
 M deploy.sh
 M docs/plans/AVIATOR_BOT.md
 M docs/plans/DEAD_GROUP_AUTO_RECOVERY.md
 M docs/reviews/DEAD_GROUP_AUTO_RECOVERY/compliance.md
 M docs/reviews/DEAD_GROUP_AUTO_RECOVERY/review.md
 M docs/reviews/VIPTALK_ALERTING_V2/release.md
?? bot-app/src/test/java/com/vingame/bot/domain/bot/service/BettingMiniLookupCallSiteGuardTest.java
?? bot-app/src/test/java/com/vingame/bot/domain/bot/service/BotFactoryForGameResolutionTest.java
?? bot-engine/src/test/java/com/vingame/bot/domain/bot/core/BettingMiniGameBotRikDispatchTest.java
?? bot-engine/src/test/java/com/vingame/bot/domain/bot/core/BettingMiniGameBotRikRequestDispatchTest.java
?? bot-engine/src/test/java/com/vingame/bot/domain/bot/core/BettingMiniGameBotZicZacDispatchTest.java
?? bot-messages/src/main/java/com/vingame/bot/domain/bot/message/g3/rik/
?? bot-messages/src/main/java/com/vingame/bot/domain/bot/message/request/GameRequestFactory.java
?? bot-messages/src/main/java/com/vingame/bot/domain/bot/message/request/RikStockBet.java
?? bot-messages/src/main/java/com/vingame/bot/domain/bot/message/request/RikStockRequest.java
?? bot-messages/src/test/java/com/vingame/bot/domain/bot/message/GameMessageTypesForGameContractTest.java
?? bot-messages/src/test/java/com/vingame/bot/domain/bot/message/GameRequestFactoryCapabilityTest.java
?? bot-messages/src/test/java/com/vingame/bot/domain/bot/message/g3/rik/
?? bot-messages/src/test/java/com/vingame/bot/domain/bot/message/request/RikStockBetCaptureFidelityTest.java
?? bot-messages/src/test/java/com/vingame/bot/domain/bot/message/request/RikStockBetTest.java
?? bot-messages/src/test/java/com/vingame/bot/domain/bot/message/request/RikStockRequestTest.java
?? bot-messages/src/test/resources/captures/
?? bot-messages/src/test/resources/messages/rik/
?? docs/plans/RIK_114_BETTING_MINI.md
?? docs/plans/RIK_114_ZICZAC.md
?? docs/reviews/DEAD_GROUP_AUTO_RECOVERY/release.md
?? docs/reviews/PLUGIN_HOT_RELOAD/review-2d.md
?? docs/reviews/RIK_114_BETTING_MINI/
?? docs/reviews/RIK_114_ZICZAC/
?? docs/reviews/WIN79_119_PROD_ACCOUNTS/
?? scripts/bulk-create-accounts.py
?? scripts/capture/
```

**State this plainly: what now runs on Bot-1 cannot be rebuilt from any commit in this
repository.** The branch name (`feature/dead-group-auto-recovery`) is also unrelated to what
was deployed, and the tree carries unrelated modified files (`Aviator.js`, `bc.js`, the
`TaiXiuMessages/*` deletions, three other plans' docs). Reproducing this image requires this
exact working tree on this exact machine.

## Build

- `mvn clean install`: **PASS** (59.5 s wall, `BUILD SUCCESS`)
  - **Tests run: 2244, Failures: 0, Errors: 0, Skipped: 0** — exactly the expected count.
  - Per module: bot-api 138, bot-strategies 126, bot-messages 266, bot-engine 453, bot-app 1261.
  - **Neither known flake occurred**, first run: zero matches for `NoClassDefFoundError` and
    zero for `The forked VM terminated`. No re-run was needed.
- `docker build --no-cache --platform linux/amd64`: **PASS** (19.6 s) → `sha256:c990784836bc…`
- `docker save -o bot.tar`: **PASS** — 393,203,712 bytes.

## Ship

- `sftp put bot.tar`: **PASS** (47 s). Remote size `393203712` — byte-identical to local.
- mode=infra step: not applicable.

## Deploy

All ten containers of the single Compose project went down and came back — this is the
documented Bot-1 hazard (bot-manager *and* Grafana/Prometheus/Loki/Alertmanager/promtail/
Mongo/node-exporter/viptalk-shim/evidence-shim share one project), and it also restarted
every running bot group.

- `docker compose down`: **PASS** — 10 containers + network removed.
- `docker image rm vingame-bot:latest`: **PASS** — untagged `sha256:7e1bfb41c297` (the
  Phase 1 image from 2026-09-16), 12 layers deleted.
- `docker load -i bot.tar`: **PASS** — `Loaded image: vingame-bot:latest`.
- `docker compose up -d`: **PASS** — all 10 recreated; `mongo` gated on its healthcheck
  before `bot-manager` started, as configured.
- **Deployed artefact confirmed to be the new one**, not a stale cache: a binary grep of
  `/app/Bot.jar` inside the running container finds `RikStockBet.class` and
  `GameRequestFactory.class`, neither of which exists in the Phase 1 image.

## Smoke test

- `docker ps` healthy: **PASS** — `bot-java-bot-manager-1 … Up (healthy)` within ~36 s.
- Spring Boot ready log: **PASS** — `Started Starter in 7.593 seconds (process running for 8.702)`.
- Auto-start log: **PASS** — `Bot Manager startup complete. 9 bot groups running`.

## Plan verification — `docs/plans/RIK_114_BETTING_MINI.md` § Phase 2 verification (V-11 … V-15)

### V-11 — pre-deploy baseline
Command: supplied by the launching agent immediately before this run (the counters are
in-memory and were destroyed by `compose down`; **not re-derived**, by instruction).
Expected: `$COINS bets = 0` (the defect), `$TXMD5 bets > 0` (the control).
Actual, env `394301f4-6daf-4c55-a073-502a81c00731`:

| group | id | endGame rounds | `bot_bets_placed_total` |
|---|---|---|---|
| **Coins** (stock, the subject) | `cd77131c…c91` | 6390 | **0.0** |
| **TxMd5** (the control) | `1134449f…95` | 3228 | **3220.0** |

Two further pre-`down` captures taken by this run:

- Registry line, for the before/after: `MessageTypesRegistry initialized: BETTING_MINI 6
  products [097, 098, 114, 116, 118, 119], TAI_XIU 3 products [114, 116, 119], SLOT provider
  SlotMessageTypesImpl`.
- The divergence itself, in `logs/detail/detail.log` at 11:24–11:25Z: coins
  `total staked: 10000 | … | confirmed staked: 0` on every round (sessions 3798345-3798347),
  against txmd5 `total staked: 675000 | … | confirmed staked: 235000`.

Result: **PASS** (baseline is as the plan requires; Amendment A3's premise holds, nothing
changed underneath the plan).

### V-12 — THE GATE: does the coins group's server-confirmed bet count leave zero?
Command:
```
curl -s "$BASE/actuator/metrics/bot_bets_placed_total?tag=botGroupId:$COINS"
```
run at 11:37:30Z, ~9 minutes and **16 settled stock rounds** after start (well past the
plan's ≥10-round bar; rounds observed at a steady 32 s).
Expected: `> 0`, strictly greater than the V-11 baseline of 0.
Actual: **`0.0`**. Group `ACTIVE`, both bots `CONNECTION_AUTHENTICATED`, both betting
(`totalBetsPlaced` 130 each locally).

Result: **FAIL** — and, per the plan's AD-27, a **known and anticipated outcome**, not a
broken deploy. See "What this run decides" below.

### V-13 — do the amount and the payout agree with what we sent?
Command: `bot_bet_amount_total` / `bot_winnings_total` for `$COINS`, plus the detail-log
cross-check.
Expected: both `> 0`; ratio near 0.98.
Actual: `bot_bet_amount_total = 0.0`, `bot_winnings_total = 0.0`. The ratio is undefined and
the RTP-anomaly check is not reachable.

Log cross-check (the reading that made the failure visible originally) — **unchanged from
the baseline**:
```
… session 3798370 ended | total staked: 10000 | total win: 0 | bettors: 2 | confirmed staked: 0
… BotMemory.completeRound: sessionId=3798370, payout=0, staked=5000, delta=-5000
```
The `5000 / 0` divergence the plan says "disappearing is the fix in one line" **has not
disappeared**.

Result: **FAIL** (consequent of V-12, not an independent defect).

### V-14 — the control group and the other products are untouched
Commands: control counters; registry line; a non-114 group's health.
Expected: `$TXMD5` counters still climbing; registry line unchanged; non-114 group unchanged.
Actual:

- `$TXMD5` **still settling at its ~1:1 rate**: `bot_bets_placed_total = 16.0`,
  `bot_bet_amount_total = 2,305,000`, `bot_winnings_total = 1,257,300` in the same 9-minute
  window, and `confirmed staked: 260000 / 205000` on consecutive rounds in the detail log.
  **The allowlist is not inverted** — this is the single most important regression check in
  the phase and it is clean.
- Registry line **byte-identical** to the pre-deploy capture:
  `BETTING_MINI 6 products [097, 098, 114, 116, 118, 119], TAI_XIU 3 products [114, 116, 119],
  SLOT provider SlotMessageTypesImpl`. No provider was added, as Phase 2 intends.
- Non-114 controls: `119 Tai Xiu probe` ACTIVE 10/10 authenticated, 0 dead; `XD game test`
  (116) ACTIVE 20/20 authenticated, 0 dead.

Result: **PASS**.

### V-15 — the observability stack came back
Command: `docker compose ps`, `:3000/api/health`, `:9090/-/healthy`, `:3100/ready`.
Expected: all containers Up; 200 / 200 / `ready`.
Actual: all **10** containers `running`; Grafana **200**; Prometheus **200**; Loki **`ready`**
(first poll returned `Ingester not ready: waiting for 15s after being ready` — transient
startup, clean on re-poll ~90 s later). Prometheus targets `bot-manager` and `node` both
`up`. Alertmanager answers `/-/healthy` = `OK` **on the Compose network**; it returns nothing
on the host because its port is deliberately unpublished (`9093/tcp`, no host binding) — that
is the existing configuration, not a deploy fault.

Result: **PASS**.

## The decisive new evidence — the frame ships, the server accepts it, the round still settles empty

V-12's zero is the same number as before the deploy, so on its own it cannot distinguish
"Phase 2 did not reach the wire" from "Phase 2 reached the wire and was not enough". The plan
provides no runtime log line at the dispatch seam (`BettingMiniGameBot.buildRequest` selects
`GameRequestFactory` silently), so this run took a **bounded wire capture** to settle it:
`com.vingame.bot.domain.bot.util.OutputPrinter` raised to TRACE via `/actuator/loggers` at
11:39:37Z, **reset to INFO at 11:41:25Z** (108 s). Nothing else was changed; no group, game
or bet configuration was touched.

**1. The new Phase 2 body is on the wire.** Every send from both coins bots:
```
[SENT] ["6","MiniGame","stockPlugin",{"cmd":13002,"v":1000,"sid":3798378,"aid":1,"eid":1,"iAc":true}]
```
`v` carries the stake (not `b`) and `iAc` is present — exactly the Phase 2 shape, at the
pinned legal denomination of 1000. 40 such sends were captured across 4 sessions, 10 per
session (2 bots × 5), confirming the send loop is intact.

**2. The server acknowledges each bet, in-round, and attributes it to us.** Every send draws
an immediate `13002` reply whose `bs` array shows this player's own stake `b` and the option
total `v` **accumulating across our sends**:
```
[RECEIVED] [5,{"bs":[{"eid":1,"bc":1,"b":1000,"v":1000},{"eid":0,"bc":0,"b":0,"v":0}],"cmd":13002}]
[RECEIVED] [5,{"bs":[{"eid":1,"bc":2,"b":2000,"v":3000},{"eid":0,"bc":0,"b":0,"v":0}],"cmd":13002}]
[RECEIVED] [5,{"bs":[{"eid":1,"bc":2,"b":3000,"v":6000},{"eid":0,"bc":0,"b":0,"v":0}],"cmd":13002}]
```
This is **not** a silent drop and **not** a malformed-frame rejection. The server parsed the
Phase 2 body, understood the amount, and echoed a per-player running stake back.

**3. And then the round settles as if none of it happened.** The `13006` EndGame for those
very sessions:
```
[RECEIVED] [5,{"obs":[{"eid":1,"bc":0,"v":0},{"eid":0,"bc":0,"v":0}],"ps":[],"cmd":13006,
             "d1":54,"sid":3798376,"bPl":[…],"mbs":[]}]
```
`mbs` is **empty** — hence `betCountFor` = 0, hence `bot_bets_placed_total` = 0 — but note the
stronger fact: **`obs` reports the round's own option totals as zero too**, including the pool
value the server had itself been broadcasting seconds earlier. The round is settled as an
empty round.

**4. The wallet agrees with the EndGame, not with the acknowledgement.** Server-read balances
after 130 bets per bot:

| bot | local balance | server `lastFetchedBalance` |
|---|---|---|
| `rikcoin1` | 999,870,000 | **1,000,000,000** |
| `rikcoin2` | 999,870,000 | **1,000,000,000** |
| `riktxm1` (control) | 998,072,800 | 1,001,502,800 |
| `riktxm2` (control) | 1,007,094,100 | 1,010,884,100 |

The coins bots' server balance is a pristine round 1,000,000,000 with no drift whatsoever,
while the control's has drifted in both directions on the same host, same brand, same
deploy. **Nothing is ever debited for a stock bet.** This also re-confirms that per-brand IP
whitelisting is not the explanation (the control settles from this same host).

**What this rules in and out.** The mid-round acknowledgement is new information that the
plan did not have: it eliminates "the stock frame is rejected as malformed" and "the
denomination is wrong" as live theories for good, and it narrows the failure to *between* the
in-round accept and the settlement commit — the shape you would expect if the client owes the
server a per-game registration/entry the bot never sends. That is **exactly** AD-27's rival
hypothesis (the real client sends a post-subscribe **CODE-3012** frame on stock and ziczac,
and **none** on txmd5, the one 114 game that settles), and this run is the first evidence that
discriminates in its favour rather than merely being consistent with it. It is **not** proof;
Phase 3 remains the experiment that decides.

Captured verbatim above so Phase 3 does not have to re-run the TRACE window.

## Verdict

**FAIL** — on the plan's own terms: V-12, the declared gate, reads zero, and V-13 follows it.

Read the verdict correctly, because it is not a regression and not a bad deploy:

- The **deploy** is clean end to end: build, ship, load, start, smoke, stack, controls.
- **Phase 2 is necessary but not sufficient.** It does what it was written to do — the
  `v` + `iAc` body reaches the wire and the server now parses and acknowledges our stock bets
  in-round, which it demonstrably did not do with the shared `Request` body. It does not make
  the round settle.
- **The Phase 3 gate is therefore open** (AD-27), with materially better evidence than the
  plan anticipated. Phase 3 was **not** attempted, as instructed.
- **No regression anywhere**: control group settling, registry unchanged, five other products
  untouched, observability stack healthy.

Plan verification: **3 of 5 steps passed** (V-11, V-14, V-15 PASS; V-12, V-13 FAIL).

## Post-run state (left as instructed)

- `RIK114 Coins Probe` (`cd77131c…c91`) — **ACTIVE, 2/2 authenticated, untouched**. Still
  pinned `minBet = maxBet = 1000`; its bet configuration was **not** changed, so it remains a
  valid Phase 3 baseline.
- `RIK114 TaiXiuMd5 Probe` (`1134449f…95`) — ACTIVE, 2/2, settling normally. The control.
- `RIK114 ZicZac Probe` (`88d46075…5c`) — ACTIVE, 2/2 authenticated. Its ~3-minute reconnect
  loop is the known, expected behaviour for ziczac and was **not** investigated.
- `RIK114 Slot 201 Test 20` (`50dd0560…71`) — ACTIVE, 20/20 authenticated.
- `RIK114 TaiXiu Jackpot 100` (`75899bb9…c5`) — **noted, not acted on** (unrelated to this
  feature). Its persisted `targetStatus` is still **DEAD**, and after the restart it has **no
  runtime**, so `/health` reports actual status `STOPPED` with 0 bots. It did not come back
  and nothing tried to bring it back — `bot.recovery.enabled` is off on this box by default.
- `OutputPrinter` log level **restored to INFO** (verified `204` on the reset call). No other
  logger, group, game or configuration was modified.
- Nothing was committed, amended, pushed or deleted.

---

# Phase 1 release log — 2026-09-16 (unchanged, below)


Mode: **bot**
Target: **Bot-1 (staging)** — `16.162.36.69`, `/home/sgame/bot-java`
Branch: `feature/dead-group-auto-recovery`
Image: `vingame-bot:latest`, sha256 `7e1bfb41c297`, built 2026-09-16T13:46:36Z
Date: 2026-09-16T13:44Z – 14:06Z

This one deploy ships **both** plans:

- `docs/plans/RIK_114_BETTING_MINI.md` — stock + taixiuMd5, Phases 1 + 1b
- `docs/plans/RIK_114_ZICZAC.md` — ziczac, Phase 1

Neither plan's **Phase 2** is in this build. This run exists to measure whether they are
needed. It produced a different answer for each of the three games.

---

## Provenance — READ THIS FIRST

**The entire feature is uncommitted. This artifact is not reproducible from any commit.**

`git rev-parse HEAD` at build time:

```
2b212473bad125d0ce9091204abfc11ebd28bb55
```

That commit does **not** contain the feature. The RIK message layer, the `forGame`
dispatch, the `BotFactory` call site and every RIK test exist only in the working tree
that produced this image. Rebuilding from `2b21247` yields a *different* artifact — one
where `MessageTypesRegistry.bettingMini("114")` still throws. The only record of what
shipped is the file list below plus the image digest above.

`git status --short` at build time:

```
 M Aviator.js
 D TaiXiuMessages/Bet.js
 D TaiXiuMessages/End.js
 D TaiXiuMessages/Start.js
 D TaiXiuMessages/Subscribe.js
 D TaiXiuMessages/SubscribeResponse.js
 M bc.js
 M bot-api/src/main/java/com/vingame/bot/domain/bot/message/GameMessageTypes.java
 M bot-app/src/main/java/com/vingame/bot/domain/bot/service/BotFactory.java
 M bot-app/src/test/java/com/vingame/bot/ApplicationContextLoadsTest.java
 M bot-messages/src/test/java/com/vingame/bot/domain/bot/message/MessageTypesCoverageTest.java
 M bot-messages/src/test/java/com/vingame/bot/domain/bot/message/MessageTypesRegistryTest.java
 M deploy.sh
 M docs/plans/AVIATOR_BOT.md
 M docs/plans/DEAD_GROUP_AUTO_RECOVERY.md
 M docs/reviews/DEAD_GROUP_AUTO_RECOVERY/compliance.md
 M docs/reviews/DEAD_GROUP_AUTO_RECOVERY/review.md
 M docs/reviews/VIPTALK_ALERTING_V2/release.md
?? bot-app/src/test/java/com/vingame/bot/domain/bot/service/BettingMiniLookupCallSiteGuardTest.java
?? bot-app/src/test/java/com/vingame/bot/domain/bot/service/BotFactoryForGameResolutionTest.java
?? bot-engine/src/test/java/com/vingame/bot/domain/bot/core/BettingMiniGameBotRikDispatchTest.java
?? bot-engine/src/test/java/com/vingame/bot/domain/bot/core/BettingMiniGameBotZicZacDispatchTest.java
?? bot-messages/src/main/java/com/vingame/bot/domain/bot/message/g3/rik/
?? bot-messages/src/test/java/com/vingame/bot/domain/bot/message/GameMessageTypesForGameContractTest.java
?? bot-messages/src/test/java/com/vingame/bot/domain/bot/message/g3/rik/
?? bot-messages/src/test/resources/captures/
?? bot-messages/src/test/resources/messages/rik/
?? docs/plans/RIK_114_BETTING_MINI.md
?? docs/plans/RIK_114_ZICZAC.md
?? docs/reviews/DEAD_GROUP_AUTO_RECOVERY/release.md
?? docs/reviews/PLUGIN_HOT_RELOAD/review-2d.md
?? docs/reviews/RIK_114_BETTING_MINI/
?? docs/reviews/RIK_114_ZICZAC/
?? scripts/bulk-create-accounts.py
?? scripts/capture/
```

The 12 shipped source classes under `bot-messages/.../g3/rik/`:

```
RikBetInfo.java              RikStartGameMd5Message.java     RikZicZacBallResult.java
RikEndGameMessage.java       RikStartGameMessage.java        RikZicZacEndGameMessage.java
RikGameMessageTypes.java     RikSubscribeMessage.java        RikZicZacGameMessageTypes.java
RikMainBetSummary.java       RikUpdateBetMessage.java        RikZicZacUpdateBetMessage.java
```

Dirty-tree deploy was explicitly approved before any command ran. Nothing was committed,
amended or pushed. `deploy.sh` was **not** shipped — its only uncommitted delta is the
`secrets.env` → `.env` merge, which is already present on the box (`.env` carries the
VipTalk keys and `BOT_LOG_LEVEL`), and no compose / logging / prometheus / Dockerfile
file is modified in this diff. This was a pure image swap.

---

## Build

- `mvn clean install`: **PASS** (58.6 s wall, JDK 21.0.2)
- `docker build --no-cache --platform linux/amd64`: **PASS**
- `docker save`: **PASS** — 393,200,128 bytes

Test totals, summed from `target/surefire-reports/*.xml`:

| module | tests | failures | errors | skipped |
|---|---|---|---|---|
| bot-api | 138 | 0 | 0 | 0 |
| bot-strategies | 126 | 0 | 0 | 0 |
| bot-messages | 242 | 0 | 0 | 0 |
| bot-engine | 446 | 0 | 0 | 0 |
| bot-app | 1261 | 0 | 0 | 0 |
| **total** | **2213** | **0** | **0** | **0** |

**2213 is exactly the expected count.** The known 9x `NoClassDefFoundError` on
`...TaiXiu*Test$1` flake **did not occur**, so no re-run was needed and there is only one
build outcome to report.

The 11 RIK test classes plus both dispatch tests are confirmed present in the surefire
output, i.e. the new code was actually exercised rather than silently skipped:
`RikGameMessageTypesTest`, `RikGameMessageTypesRoutingTest`, `RikProviderRegistrationTest`,
`RikEndGamePayoutSemanticsTest`, `RikCrossGameShapeToleranceTest`, `RikFixtureProvenanceTest`,
`RikTaiXiuMd5GameShapeTest`, `RikZicZacGameShapeTest`, `RikZicZacEndGameSemanticsTest`,
`RikZicZacCaptureArithmeticTest`, `GameMessageTypesForGameContractTest`,
`BettingMiniGameBotRikDispatchTest`, `BettingMiniGameBotZicZacDispatchTest`,
`BotFactoryForGameResolutionTest`, `BettingMiniLookupCallSiteGuardTest`.

Note: the Docker daemon was not running at first invocation and was started before the
image build. No effect on the artifact.

## Ship

- `sftp put bot.tar`: **PASS** (13:48:22Z → 13:49:12Z, 50 s)
- Integrity verified beyond the plan's requirement — sha256 matched end to end:
  `db633f26004f8771ec7a915dc36620ecd9e86352dbf6fd3a396917232ebdf9f4`
- Free space on `/` before upload: 66 GB of 100 GB. No ENOSPC risk.

## Deploy

- `docker compose down`: **PASS** — all 10 containers stopped and removed, network removed
- `docker image rm vingame-bot:latest`: **PASS** — prior image `e853b44196cc` untagged and deleted
- `docker load -i bot.tar`: **PASS** — `Loaded image: vingame-bot:latest`
- `docker compose up -d`: **PASS** — all 10 containers recreated and started
- New container: `9150077c9225`, started `2026-09-16T13:49:59.390Z`
- Total service interruption: **~33 s** (13:49:27Z → 13:50:00Z)

## Smoke test

- `docker ps` shows healthy: **PASS** — `bot-java-bot-manager-1  Up About a minute (healthy)`
- Spring Boot ready log: **PASS** — `Started Starter in 7.808 seconds (process running for 8.888)`
- Auto-start log: **PASS** — `Bot Manager startup complete. 6 bot groups running`
- `ERROR` lines in the new container since start: **0**

### Observability stack — the shared-compose hazard, re-verified

bot-manager and Grafana/Prometheus/Loki share one Compose project on Bot-1, so the
`compose down` took the whole stack with it. All of it came back:

| container | status | health endpoint |
|---|---|---|
| bot-java-bot-manager-1 | Up (healthy) | `:8080/actuator/health` → 200 `UP` |
| bot-java-grafana-1 | Up | `:3000/api/health` → 200 |
| bot-java-prometheus-1 | Up | `:9090/-/healthy` → 200 |
| bot-java-loki-1 | Up | `:3100/ready` → `ready` |
| bot-java-alertmanager-1 | Up | `/-/healthy` → `OK` |
| bot-java-mongo-1 | Up (healthy) | — |
| bot-java-promtail-1 | Up | — |
| bot-java-viptalk-shim-1 | Up (healthy) | — |
| bot-java-evidence-shim-1 | Up (healthy) | — |
| bot-java-node-exporter-1 | Up | — |

Two readings that look like failures and are not:

- **Loki returned `Ingester not ready: waiting for 15s after being ready` on the first
  poll** and `ready` 25 s later. Normal warm-up, not a defect.
- **Alertmanager returned HTTP 000 on `localhost:9093`** because it publishes no host
  port in this compose file. Queried inside the container it answers `OK`. This is the
  compose layout, not a regression — but it means the plans' literal
  `GET :9093` instruction cannot work on this box and should be read as "check it
  in-network".

---

## Plan verification

`BASE=http://localhost:8080`, `ENV=394301f4-6daf-4c55-a073-502a81c00731`.

### V-1 — the app is up
Command: `curl -s -o /dev/null -w '%{http_code}' $BASE/actuator/health` and `| jq -r .status`
Expected: 200 and `UP`
Actual: **200**, `"status":"UP"`, mongo component `UP`, diskSpace `UP` (70.5 GB free)
Result: **PASS**

### V-2 — the registry picked the provider up (the decisive build/deploy check)
Command: `docker logs bot-java-bot-manager-1 2>&1 | grep -c "MessageTypesRegistry initialized"` then the line itself
Expected: **exactly one** line, `BETTING_MINI 6 products [097, 098, 114, 116, 118, 119]`, `TAI_XIU 3 products [114, 116, 119]`
Actual: line count **exactly 1**, emitted at 13:50:03.904 against a container started 13:49:59.390 — so it is unambiguously this container's own boot, not a line read across a restart boundary:

```
MessageTypesRegistry initialized: BETTING_MINI 6 products [097, 098, 114, 116, 118, 119],
TAI_XIU 3 products [114, 116, 119], SLOT provider SlotMessageTypesImpl
```

Pre-deploy baseline (supplied, not re-derived): `BETTING_MINI 5 products [097, 098, 116, 118, 119]`.
The 5→6 delta with **114 appearing**, and `TAI_XIU` unchanged at 3, is the decisive proof the new code is live.
Result: **PASS**

This simultaneously satisfies **ZICZAC V-2**, whose requirement is the inverse — that the
product set is *unchanged by the ziczac work*. It is: ziczac registered no new bean, exactly
as ziczac AD-3 requires. Had it registered one, context refresh would have failed on the
duplicate key.

### V-3 — the games can be created (extended to three, per instruction)
Command: `POST $BASE/api/v1/game/G3/P_114/$ENV` x3
Expected: 200/201 with the offsets, plugin names, md5 flags and option maps echoed back
Actual: all three **HTTP 200**:

| game | id | plugin | offset | md5 | optionAffinities | crowdCountSemantic |
|---|---|---|---|---|---|---|
| RIK Coins (stockPlugin) | `8a4e3c49-b8eb-494c-89f2-27c05cc5e586` | `stockPlugin` | 10000 | false | `{0:1, 1:1}` | PLAYERS |
| RIK Tai Xiu MD5 (taixiuMd5Plugin) | `37c23f9f-8260-471b-b4a7-0a415f466087` | `taixiuMd5Plugin` | 4000 | **true** | `{1:1, 2:1}` | UNKNOWN |
| RIK ZicZac Plinko (ziczacPlugin) | `62901e13-7a76-49b5-a3a9-c43fcc95fc26` | `ziczacPlugin` | 9000 | false | `{1:1}` | UNKNOWN |

All resolved `productCode` to `114 / RIK / rik.vip`.
Result: **PASS** (covers both plans' V-3)

**Deviation from both plans, deliberate:** the `"gameId"` field (13000 / 7000 / 12000) was
**omitted**. `Request.subscribe()` emits a bare `Body(offset + 3000)` with no `gid`, so
`gameId` is slot-only; carrying it on a BETTING_MINI game would be misleading metadata, not
a wire change. Subscribe cmds were still 13000 / 7000 / 12000, confirmed by all three groups
reaching `CONNECTION_AUTHENTICATED` (which is called from `onSubscribe`). The plans' V-3
bodies should drop the field.

Second, smaller deviation: the stock game is named "RIK Coins" rather than the plan's "RIK
Stock", and `crowdCountSemantic` for taixiuMd5 is `UNKNOWN` rather than the plan's `PLAYERS`
— both per instruction. The `stockPlugin` mapping for "Coins" is an inference, not a
user-stated fact; V-4 below is what would have caught it if wrong, and it did not fire.

### V-4 — 2-bot groups start and authenticate
Command: `POST /api/v1/bot-group/` x3, then `POST /{id}/start` x3, sleep 60, `GET /{id}/health`
Expected: 200 on start; within 60 s both bots `CONNECTION_AUTHENTICATED`, group `ACTIVE`
Actual: all three created (HTTP 200), all three started (HTTP 200), and at T+60 s:

| group | id | status | bots |
|---|---|---|---|
| RIK114 Coins Probe | `cd77131c-7087-4eff-9a62-b50e3e674c91` | ACTIVE | `rikcoin1`, `rikcoin2` both CONNECTION_AUTHENTICATED |
| RIK114 TaiXiuMd5 Probe | `1134449f-f040-4e95-a1e5-a2a618195cc1` | ACTIVE | `riktxm1`, `riktxm2` both CONNECTION_AUTHENTICATED |
| RIK114 ZicZac Probe | `88d46075-dc8e-476c-b80d-1d0544b29c5c` | ACTIVE | `rikzzc1`, `rikzzc2` both CONNECTION_AUTHENTICATED |

`lastFailureReason` was **null** on all three — no auth/registration failure, and the
subscribe was answered in every case. All six bots funded to 1,000,000,000.
Result: **PASS** for all three, independently.

Only WARN raised during start: `Failed to set display name ... Tên hiển thị đã được sử dụng
(status: EXISTED)` for `rikcoin1`, `riktxm2`, `rikzzc1`. Display-name collision on a
throwaway prefix; cosmetic, does not affect auth or play.

### V-5 — StartGame and EndGame deserialize into the RIK classes
Command: `bot_messages_total{cmd:startGame|endGame}` per group; `docker logs | grep -E "ERROR|Exception"`
Expected: both > 0 (target ≥ 2 each); no `InvalidTypeIdException`, no error naming 114 / RIK / the plugins
Actual, at T+13 min:

| group | subscribe | startGame | endGame | updateBet (inbound) | verdict |
|---|---|---|---|---|---|
| Coins (stock) | 2 | **44** | **46** | 0 | **PASS** |
| TaiXiuMd5 | 2 | **24** | **22** | **77** | **PASS** |
| ZicZac | 8 | **2** | **0** | 0 | **FAIL** |

`ERROR` count in the container since boot: **0**. No `InvalidTypeIdException` anywhere. The
concrete classes are confirmed by name in the detail track, which is the strongest available
evidence that the polymorphic subtype resolved:

```
... entered session 3795934 | sample: com.vingame.bot.domain.bot.message.g3.rik.RikStartGameMessage@71b9b4f2
... session 3795933 ended   | sample: com.vingame.bot.domain.bot.message.g3.rik.RikEndGameMessage@4731c311
... session 2474455 ended   | sample: com.vingame.bot.domain.bot.message.g3.rik.RikEndGameMessage@13b23943   (taixiuMd5)
```

`RikStartGameMd5Message` is what `md5:true` selected for the taixiuMd5 group — it parses a
real 64-hex digest where stock carries `"-"`, and 24 StartGames deserialized without error.

Result: **PASS (stock)**, **PASS (taixiuMd5)**, **FAIL (ziczac)** — see the ziczac section below.

### V-6 — THE GATE: does the server accept our bet body?

The plans ask this be read off raw `"cmd":NNNNN` frames in `logs/detail/detail.log`. **Those
frames are not in the file**: ws-parser's raw frame dump is TRACE (`OutputPrinter`) and the
box runs `BOT_LOG_LEVEL=DEBUG`, so `grep '"cmd":13002'` returns 0 rows on a perfectly healthy
system. Anyone following the plan literally would read that zero as a catastrophic failure.

The equivalent — and strictly better — signal is `SessionAggregationService`'s per-round line,
which is at DEBUG and therefore present. It reports **`confirmed staked`**, computed from the
*inbound* EndGame's own bet arrays, alongside the stake the bot believes it sent. That is
exactly the quantity V-6 asks for, already joined per round.

#### V-6 / stock (cmd 13002) — **FAIL, as predicted**

Ten consecutive rounds, every one of them:

```
sid=3795938 staked=45000 win=0 bettors=2 confirmed=0
sid=3795939 staked=69000 win=0 bettors=2 confirmed=0
sid=3795940 staked=55000 win=0 bettors=2 confirmed=0
sid=3795941 staked=38000 win=0 bettors=2 confirmed=0
sid=3795942 staked=51000 win=0 bettors=2 confirmed=0
sid=3795943 staked=49000 win=0 bettors=2 confirmed=0
sid=3795944 staked=52000 win=0 bettors=2 confirmed=0
sid=3795945 staked=41000 win=0 bettors=2 confirmed=0
sid=3795946 staked=55000 win=0 bettors=2 confirmed=0
sid=3795947 staked=56000 win=0 bettors=2 confirmed=0
```

240 bets sent across 46 settled rounds. `confirmed staked` **0 on every round**, `total win`
**0 on every round**, **zero inbound 13002 echoes**, and `bot_bet_amount_total` /
`bot_bets_placed_total` / `bot_winnings_total` all **0.0**.

We send `{"cmd":13002,"aid":1,"b":<amt>,"eid":<id>,"sid":<sid>}`. The real client sends
`{"cmd":13002,"v":1000,"sid":…,"aid":1,"eid":0,"iAc":true}` — stake in **`v`**, plus `iAc`.
The server accepts the frame, answers nothing, and books nothing.

**Verdict: the server does not accept `b` on stock. RIK_114_BETTING_MINI Phase 2 SHIPS.**

**Whitelisting is ruled out, and this run is what rules it out.** `CLAUDE.md` warns that
fleet-wide zero settlement means the host IP is not whitelisted for the brand, and that this
has been misdiagnosed twice. It cannot be the explanation here: the **taixiuMd5 group settles
normally from the same box, the same brand (114/RIK), the same egress IP, in the same
minutes**. A per-game zero with a per-brand cause is not possible. The fault is the frame.

#### V-6 / taixiuMd5 (cmd 7002) — **PASS. This is the new information.**

This was the genuine unknown: the taixiuMd5 capture contains **zero outbound bet frames**, so
whether our standard body works there had never been tested in either direction. It works.

```
sid=2474457 staked=520000 win=0      bettors=2 confirmed=225000
sid=2474458 staked=520000 win=0      bettors=2 confirmed=165000
sid=2474459 staked=640000 win=504900 bettors=2 confirmed=255000
sid=2474460 staked=635000 win=0      bettors=2 confirmed=135000
sid=2474461 staked=550000 win=306900 bettors=2 confirmed=155000
sid=2474462 staked=735000 win=396000 bettors=2 confirmed=200000
```

`confirmed staked` is **non-zero on every round**, 77 inbound `updateBet` echoes arrived, and
winnings land. **The standard `{"b", "eid", "sid", "aid"}` body is accepted by taixiuMd5.**

**Verdict: no bet-body work is needed for taixiuMd5.** Phase 2, if it ships for stock, must
be scoped so it does not disturb this path — which is precisely what ZICZAC AD-3's per-game
`forGame(Game)` dimension buys, and it is the right mechanism. A *product*-wide
`usesStockBetBody()` flag (the superseded RIK_114_BETTING_MINI AD-10) would have broken this
working game. **This result retroactively justifies Amendment A2.**

Two observations worth recording, neither a blocker:

1. **`confirmed staked` is consistently ~30-45% of `staked`.** Part of each round's stake is
   not booked. Plausibly late bets against the countdown, or a per-round server cap. It
   deserves a look but it is not a Phase 2 question — the body itself is accepted.
2. **Payout is a clean gross 1.98x of confirmed stake on every winning round**
   (504900/255000, 306900/155000, 396000/200000 — all exactly 1.98). That is 2.00 less 1%
   commission, and it confirms `winningsFor()` returns the **gross** return verbatim from the
   top-level `wm`, per AD-15/AD-16. The stake is neither netted out nor double-counted.

#### V-6 / ziczac (cmd 12002) — **NOT REACHED, and for a reason the plan did not anticipate**

`RIK_114_ZICZAC.md` already declares V-6 and V-7 Phase-2-gated, so a failure here is not a
surprise. **The reason is the surprise, and it matters more than the gate.** The plan assumes
rounds flow under Phase 1 and only our *bets* fail to land. They do not flow at all — see the
next section. There is no round in which to observe a bet, so V-6 has no answer either way.

Result: **FAIL (stock — expected)**, **PASS (taixiuMd5 — new)**, **NOT EVALUABLE (ziczac)**

### V-7 — no un-captured 114 game was enabled (AD-11 / OI-2)
Command: `curl -s "$BASE/api/v1/game/G3/P_114/$ENV"` filtered to BETTING_MINI
Expected: only the plan-sanctioned games; in particular nothing at offset 14000-18000
Actual: exactly the three games created in V-3 — `stockPlugin`/10000, `taixiuMd5Plugin`/4000,
`ziczacPlugin`/9000. The two pre-existing 114 games (`Tai Xiu Jackpot`/TAI_XIU,
`Slot 201`/SLOT) are untouched and are not BETTING_MINI. Pre-deploy baseline confirmed there
were **no** BETTING_MINI 114 games at all, so all three are accounted for.
Result: **PASS**

### V-8 — no regression for the other five products
Command: registry line (V-2) + `GET /{id}/health` and stake metric on a running non-114-mini group
Expected: unchanged status and connected count vs pre-deploy baseline
Actual: the registry line already pins the product set. The only pre-existing *running* group
in this environment is the slot group `50dd0560` ("RIK114 Slot 201 Test 20", 20 bots):

| | pre-deploy | post-deploy |
|---|---|---|
| status | ACTIVE | ACTIVE |
| connected | 20/20 CONNECTION_AUTHENTICATED | 20/20 CONNECTION_AUTHENTICATED |
| dead | 0 | 0 |

It came back and is betting again (4,252 bets within 4 min of restart, 5,952 by T+15 min).
Result: **PASS**

The other four groups in this environment were already `STOPPED` (x3) or `DEAD` (x1,
`75899bb9`, the 100-bot TaiXiu group) before the deploy and are unchanged.
`BOT_RECOVERY_ENABLED` is absent from `.env`, so recovery stays off (default `false`) and the
DEAD group was not auto-restarted by this deploy.

### V-9 (RIK_114_BETTING_MINI) — payout metrics non-zero, RTP near 0.98
Command: `bot_bet_amount_total` / `bot_bets_placed_total` / `bot_winnings_total` per group
Expected: all three > 0; ratio near 0.98 and below 1.0

| group | bet_amount | bets_placed | winnings | ratio |
|---|---|---|---|---|
| Coins (stock) | 0.0 | 0.0 | 0.0 | — |
| TaiXiuMd5 | 4,055,000 | 22 | 3,593,700 | **0.886** |
| ZicZac | 0.0 | 0.0 | 0.0 | — |

- **taixiuMd5: PASS.** All three > 0, ratio **0.886 — below 1.0**, consistent with a 0.98
  long-run anchor at a 22-round sample where Tài/Xỉu variance is large. Not the ≈1.98 or
  ≈0.02 pathology, not the >1.0 RTP-anomaly condition.
- **stock: FAIL, and it is a *downstream* failure, not a `winningsFor` defect.** The plan's
  own triage says "exactly 0 winnings with a non-zero stake ⇒ check `winningsFor`". That
  branch does not apply, because the stake is **also** zero at the metric layer: nothing was
  ever confirmed, so there is nothing to pay out. Fixing V-6 is the prerequisite; this check
  cannot be run meaningfully until Phase 2 ships.
- **ziczac: not evaluable** (no rounds).

Result: **PASS (taixiuMd5)**, **FAIL (stock — blocked on V-6)**, **N/A (ziczac)**

Worth noting: `bot_bets_placed_total` read **22** for taixiuMd5 and **5,952** for the slot
group, i.e. the counter that `CLAUDE.md` records as "known-broken on staging (reads 0 while
bots actively bet)" **is working in this build**. Its zero on the stock and ziczac groups is
therefore *substantive* — it means no bet was confirmed, not that the counter is broken. That
is a stronger reading than the brief allowed for, and it is consistent with every other
signal.

### V-10 (RIK_114_BETTING_MINI) — the second captured game, md5 path and top-level `wm`
Folded into V-3/V-4/V-5/V-9 above for the taixiuMd5 group. `gameType` is `BETTING_MINI` (not
`TAI_XIU`), `md5:true`, `optionAffinities` keyed `1`/`2`. StartGame and EndGame both > 0,
`bot_winnings_total` > 0 sourced from the top-level `wm`, at a clean gross 1.98x.
Result: **PASS**

---

## The ziczac finding — Phase 1 does not reach a round, and Phase 2 will not fix it alone

This is the most consequential result of the run and it is not in either plan's failure
taxonomy, so it is written out in full.

**Observed.** Both ziczac bots authenticate, the subscribe on CMD 12000 is answered (they
reach `CONNECTION_AUTHENTICATED`, which is called from `onSubscribe`), **2 StartGame frames
arrive — one per bot — and then nothing, ever.** No EndGame. No UpdateBet. At 180 s the
watchdog fires, the bots fully reconnect and re-authenticate cleanly, and the same silence
follows. 17 watchdog/reconnect events in 13 minutes. The group's message metric carries only
two cmd tags for its entire lifetime:

```
ziczac total messages: 8.0    cmd = ['subscribe', 'startGame']
```

The group still reports `ACTIVE` with 2/2 connected throughout, so **nothing in the health
model notices**. This is the same shape as the "TaiXiu Seven problematic" entry in
`CLAUDE.md` — connect works, zero sessions, watchdog churn.

**My first hypothesis was wrong and I am recording it so it is not re-run.** I supposed
ziczac was player-driven — Plinko rounds triggered by a ball drop — which would make zero
rounds expected for a non-betting bot. The capture refutes it: sessions 1995085, 1995087,
1995088, 1995089 and 1995090 all ran start→end with **no outbound bet at all**, on a steady
~30 s cadence with EndGame ~17 s after StartGame. **Ziczac rounds flow unconditionally for
the real client.** Our bot's silence is a genuine gap.

**The strongest available hypothesis, stated as a hypothesis.** In the ziczac capture the
real client sends one extra outbound frame immediately after the subscribe handshake:

```
14.63s out 12000        <- subscribe
14.86s in  12000        <- subscribe ack
14.87s out 12012        <- CODE 3012, sent once, immediately
14.87s in  12007 ...    <- the per-second feed begins
```

We do not send `12012`. The stock client sends the analogous `13012` and **we do not send
that either, yet stock rounds flow for us** — so `+3012` is not universally required, and
whether ziczac specifically needs it is **unproven**. It is the most economical explanation
of the difference and the first thing to test, not a conclusion. Note also that taixiuMd5 —
the game that works fully — sends **no** `+3012` in its capture, which is consistent with
either reading.

**Consequence for the plan.** `RIK_114_ZICZAC.md` Phase 2 is a bet-body change
(`b` + `c`, no `eid`). Shipping it will not by itself produce a single bet, because the bot
never sees a round to bet into. **Phase 2 is necessary but not sufficient for ziczac; the
round-feed gap must be solved first.** The ziczac plan's V-5 — which it scopes to Phase 1 and
expects to pass — is the check that fails, and it fails for a cause outside the plan's model.

I did not attempt a fix. No production code was modified.

---

## ZICZAC plan — verification summary

| step | expected | actual | result |
|---|---|---|---|
| V-1 app up | 200 / `UP` | 200 / `UP` | **PASS** |
| V-2 registry UNCHANGED | `BETTING_MINI 6 products`, 1 line | exactly 1 line, matches | **PASS** |
| V-3 ziczac Game created | 200, offset 9000, 1-entry affinities | 200, `62901e13…` | **PASS** |
| V-4 2-bot group starts | both CONNECTION_AUTHENTICATED, ACTIVE | both authenticated, ACTIVE | **PASS** |
| V-5 StartGame + EndGame > 0 | both ≥ 2 | startGame 2, **endGame 0** | **FAIL** |
| V-6 ziczac bet body (Phase 2) | n/a — Phase 2 not shipped | no round to bet into | **N/A** |
| V-7 payout metrics (Phase 2) | n/a — Phase 2 not shipped | all 0 | **N/A** |
| V-8 other 114 games unchanged | unchanged | stock + txmd5 + slot unaffected | **PASS** |
| V-9 observability stack back | all Up | all 10 Up | **PASS** |

Phase-1-applicable steps: **6 of 7 passed** (V-5 failed).

## RIK_114_BETTING_MINI plan — verification summary

| step | expected | actual | result |
|---|---|---|---|
| V-1 app up | 200 / `UP` | 200 / `UP` | **PASS** |
| V-2 registry 5→6 products | `BETTING_MINI 6 … 114 …`, 1 line | exactly 1 line, matches | **PASS** |
| V-3 stock Game created | 200, offset 10000 | 200, `8a4e3c49…` | **PASS** |
| V-4 2-bot group starts | both authenticated, ACTIVE | both authenticated, ACTIVE | **PASS** |
| V-5 StartGame/EndGame deserialize | both > 0, no InvalidTypeId | 44 / 46, 0 errors | **PASS** |
| V-6 server accepts our bet body | gate | **confirmed staked 0 on 10/10 rounds** | **FAIL (expected)** |
| V-7 no un-captured 114 game | only sanctioned games | exactly the 3 created | **PASS** |
| V-8 no regression elsewhere | unchanged | slot group ACTIVE 20/20 | **PASS** |
| V-9 payout metrics / RTP | all > 0, ratio < 1.0 | stock all 0 (blocked on V-6) | **FAIL** |
| V-10 taixiuMd5 second game | messages > 0, winnings > 0 | 24/22, winnings 3,593,700 | **PASS** |

**8 of 10 passed.** The two failures are the stock bet body (V-6, the gate — a designed
outcome, not an incident) and V-9, which is strictly downstream of it.

---

## What this run decides

1. **RIK_114_BETTING_MINI Phase 2 SHIPS — for `stockPlugin` only.** The server ignores our
   `b`/`eid` body on stock; it needs `v` + `iAc`. Confirmed over 46 rounds and 240 bets with
   a same-brand, same-box control group proving it is not whitelisting.
2. **taixiuMd5 needs no bet-body work.** The standard body is accepted and settles at a clean
   gross 1.98x. Phase 2 must be scoped per-game so it cannot regress this — which is exactly
   what ZICZAC AD-3's `forGame(Game)` mechanism provides, and is a live vindication of
   Amendment A2 superseding the product-wide AD-10 flag.
3. **RIK_114_ZICZAC needs more than its Phase 2.** The bet body is not the first problem; the
   round feed is. Phase 2 as written cannot move a single metric until ziczac delivers rounds.
   Recommend a spike on the post-subscribe `12012` frame before building Phase 2.
4. **Phase 1 / 1b of RIK_114_BETTING_MINI is sound.** Registry, routing, md5 selection,
   StartGame/EndGame deserialization and the Phase 1b payout model are all confirmed live on
   two independent 114 games, with zero ERROR lines.

## Verdict

**FAIL**

The deploy itself is clean — build, ship, load, smoke and the whole observability stack are
unambiguous passes, and the decisive V-2 registry delta proves the new code is live. The
verdict is FAIL because two verification steps failed against plan expectation:

- **RIK_114_ZICZAC V-5** — a Phase-1 step the plan expects to pass. Real, unanticipated, and
  the single most important finding here.
- **RIK_114_BETTING_MINI V-9** — downstream of the V-6 gate.

RIK_114_BETTING_MINI V-6 is *also* a failure but an **expected** one: it is a measurement,
and the answer it returned (plus taixiuMd5's contrasting pass) is the deliverable.

Nothing needs rolling back. All three probe groups are healthy at the connection layer, the
pre-existing fleet is unaffected, and the only impaired game — ziczac — was not previously
working either.

## Post-run state (left running, per instruction)

Neither plan's Cleanup section was run. Nothing was stopped or deleted.

| group | id | status | note |
|---|---|---|---|
| RIK114 Coins Probe | `cd77131c-7087-4eff-9a62-b50e3e674c91` | ACTIVE, 2/2 | betting; nothing confirmed server-side |
| RIK114 TaiXiuMd5 Probe | `1134449f-f040-4e95-a1e5-a2a618195cc1` | ACTIVE, 2/2 | fully working, settling |
| RIK114 ZicZac Probe | `88d46075-dc8e-476c-b80d-1d0544b29c5c` | ACTIVE, 2/2 | no rounds; ~1 watchdog reconnect/3 min |

The three `Game` records are also retained. Note the ziczac group will keep generating
watchdog reconnect churn indefinitely; stop it if that noise is unwanted.

### The slot group, and the open RTP question

`50dd0560-9fa0-48ff-920b-6f3a44e27571` ("RIK114 Slot 201 Test 20", 20 bots) **came back
ACTIVE with 20/20 bots authenticated** and resumed betting immediately. Since the metric
counters are in-memory, the restart reset them, which gives a clean post-restart RTP window
for the open 1.0532 question:

| | value |
|---|---|
| pre-deploy `bot_bet_amount_total` | 196,739,400 |
| pre-deploy `bot_winnings_total` | 196,742,467 |
| **pre-deploy ratio** | **1.0000156** |
| post-restart (T+15 min) bet_amount | 1,785,600 |
| post-restart (T+15 min) winnings | 1,661,460 |
| **post-restart ratio** | **0.9305** |

Two things follow. The pre-deploy reading was **1.0000**, not 1.0532 — whatever produced
1.0532 was either an earlier window or a different accumulation, so the figure should be
re-sourced before it is reasoned from. And the fresh window opens at **0.9305**, below 1.0.
The ~38,963 accumulated rounds of history are gone from the counters (not from Mongo); if
that history was needed for the RTP question, it now has to come from Prometheus, which
retains it.

## Logs

No failure logs to attach — `ERROR` count in the new container is **0** for the entire run.
The ziczac evidence is WARN-level and is quoted inline above. Full per-round detail is on the
box in `logs/detail/detail.log` (track 2, 12 h retention — pull anything needed from it
promptly):

```bash
grep -h "RIK ZicZac"       /home/sgame/bot-java/logs/detail/detail.log | tail -100
grep -h "\[88d46075-dc8e-476c-b80d-1d0544b29c5c/" /home/sgame/bot-java/logs/detail/detail.log | tail -400
```
