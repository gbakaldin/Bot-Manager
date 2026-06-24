# Release (re-deploy #2) — TAI_XIU_BOT

Mode: bot
Branch: main
Image: vingame-bot:latest (built at 2026-06-24 14:56 local / target/Bot-1.0.jar)
Date: 2026-06-24T11:05Z
Source commit: 4f80d63 (HEAD == main == origin/main)
Target: Bot-1 (/home/sgame/bot-java)

> This is the re-deploy after the `game.getOffset()` NPE fix. The prior release
> log (`release.md`, commit 4819c9f) is kept intact. This deploy ships commit
> `4f80d63`, which includes `1143af9` "fix(taixiu): null-safe offset unbox in
> shared bot init path".

## What changed since the previous deploy

`BettingMiniGameBot.initializeSubclass()` previously did
`this.offset = game.getOffset();` (eager unbox of a nullable `Integer` into a
primitive `int`), which NPE'd for every Tai Xiu bot because Tai Xiu games carry
a null offset by design (AD-9). Commit `1143af9` guards the unbox:
```java
Integer gameOffset = game.getOffset();
this.offset = gameOffset != null ? gameOffset : 0;
```
BettingMini offset is always non-null so its value is byte-for-byte identical;
for Tai Xiu the `0` is a never-read dead store (the overridden CMD seams ignore it).

## Build

- `mvn clean install`: PASS (~21s). Tests run: 879, Failures: 0, Errors: 0,
  Skipped: 0. BUILD SUCCESS. Built with
  JAVA_HOME=/Users/gleb/Library/Java/JavaVirtualMachines/openjdk-21.0.2/Contents/Home.
- `docker build --no-cache --platform linux/amd64`: PASS. Image
  sha256:93256537da4a…, tagged vingame-bot:latest.
- `docker save -o bot.tar`: PASS (392,799,744 bytes).

Working tree note: `git status` showed only untracked files (docs/plans,
docs/reviews, build artifacts, `TaiXiuMessages/`, `app/`, `logs/`). No tracked
production source was modified. HEAD == main == origin/main == 4f80d63. Deploy permitted.

## Ship

- `sftp put bot.tar`: PASS. Verified on server: 392,799,744 bytes (exact match with local).

## Deploy

- `docker compose down`: PASS (exit 0). Took down bot-manager + co-located
  mongo/grafana/prometheus/loki/promtail (single-compose project, expected).
- `docker image rm vingame-bot:latest`: PASS (prior image
  sha256:a1b294d535ee untagged + deleted).
- `docker load -i bot.tar`: PASS (exit 0, "Loaded image: vingame-bot:latest").
- `docker compose up -d`: PASS (exit 0). All containers recreated; mongo reached
  Healthy before bot-manager started.

## Smoke test

- `docker ps` bot-manager healthy: PASS — "Up About a minute (healthy)".
- Spring Boot ready log: PASS — `Started Starter in 6.418 seconds`.
- Auto-start log: PASS — `Bot Manager startup complete. 4 bot groups running`.
- Co-located observability stack re-verified (per Bot-1 single-compose note):
  - Grafana `GET :3000/api/health` → 200 PASS
  - Prometheus `GET :9090/-/ready` → 200 PASS
  - Loki `GET :3100/ready` → 503 at first (warming up), 200 within ~30s PASS
  - promtail container Up PASS; mongo Up (healthy) PASS
- Bot actuator `GET :8080/actuator/health` → `{"status":"UP"}` (mongo UP) PASS

Smoke: PASS. Proceeded to plan-driven verification.

## Plan verification

Plan: docs/plans/TAI_XIU_BOT.md § Verification.
Environment used: "116 Staging" (P_116 / TIP, brand G3),
id ad4e7948-fe24-4ef3-bd73-81f8956a94f0.

### Step 1: App boots with Tai Xiu validator and strategy listing wired
Command: `curl -s :8080/actuator/health` + grep `GameConfigValidatorFactory initialized`
Expected: health 200 {"status":"UP"}; factory boot line lists all 5 game types incl TAI_XIU.
Actual: health = {"status":"UP", mongo UP}. Boot line:
`GameConfigValidatorFactory initialized: registered 5 validators — [BETTING_MINI, SLOT, TAI_XIU, CARD_GAME, UP_DOWN]`.
Result: PASS

### Step 2: Tai Xiu strategies are listed
Command: `curl -s ':8080/api/v1/strategy/?gameType=TAI_XIU'` (vs BETTING_MINI)
Expected: HTTP 200, non-empty array equal to the BETTING_MINI response.
Actual: HTTP 200, 9-element array (RANDOM, MARTINGALE_CLASSIC_*, PAROLI_*, DALEMBERT_*,
FIBONACCI_*); byte-for-byte equal to BETTING_MINI response.
Result: PASS

### Step 3: A TAI_XIU game can be created via REST
Command: `POST /api/v1/game/G3/P_116` body
`{"name":"TaiXiuMiniGameRelease2","gameType":"TAI_XIU","pluginName":"taixiuPlugin","gameId":1}`
Expected: HTTP 200/201 echoing gameType:TAI_XIU; GET returns the same.
Actual: HTTP 200, echoed `"gameType":"TAI_XIU"`, `"pluginName":"taixiuPlugin"`, product 116/TIP,
`offset` = null (plan-correct per AD-9). GET returned the same.
(Plan's `POST /api/v1/game/` path is stale; current API is
`POST /api/v1/game/{brandCode}/{productCode}`.)
Result: PASS

### Step 4: A Tai Xiu bot group passes config validation and starts; bots reach CONNECTION_AUTHENTICATED
Commands: invalid-grid create (expect 400); valid 2-bot group create + `/start` + `/health`.
Expected: invalid grid → HTTP 400; valid group starts; bots show CONNECTION_AUTHENTICATED;
RECEIVED subscribe frame at the fixed Tai Xiu CMD per bot.
Actual:
  - **Validation sub-check — PASS.** Invalid grid (minBet 1000 > maxBet 500) → HTTP 400
    `Invalid bot-group config: minBet (1000) must be <= maxBet (500)`. Confirms
    TaiXiuConfigValidator enforces the BettingMini grid rules (AD-8).
  - **NPE fix CONFIRMED.** Valid 2-bot group on the plan-correct null-offset game (step-3 game):
    `/start` → HTTP 200; both bots authenticated (`AUTHENTICATING → AUTHENTICATED`, clients
    created) — **no `Game.getOffset()` NPE** (zero occurrences of that exception in the entire
    log; previously it fired for every bot). The shared init path no longer dies on a null offset.
  - **New downstream blocker surfaced (see Defect below):** with the plan-correct game (no offset
    AND no option config), creation now fails one step later with
    `IllegalStateException: Game ... has neither optionAffinities nor legacy numberOfOptions set`.
    `/health` showed totalBots=0.
  - **Bot creation fully proven on an option-configured game.** A second TAI_XIU game created
    with `numberOfOptions:2` (mapper-expanded to `optionAffinities:{0:1,1:1}`; offset still null)
    drove a 2-bot group all the way through init:
    `BettingMiniGameBot initialized: game=TaiXiuRel2WithOptions, offset=0, options=2, ...`
    → `AUTHENTICATED → CONNECTING` → WS client `Resolving authentication tokens... / Authenticated
    with token`. Creation then failed at the **WS-connect** stage on
    `java.net.UnknownHostException: tipclubgw-sock.stgame.win: Name or service not known`
    (the known staging-DNS caveat — env, not feature). Confirmed unresolvable from inside the
    container: `getent hosts tipclubgw-sock.stgame.win` → UNRESOLVABLE.
Result: PARTIAL — validation PASS, offset-NPE-fix PASS, bot init PASS; full
CONNECTION_AUTHENTICATED not reachable because (a) plan-correct game needs option config and
(b) staging DNS blocks WS connect. Neither is the offset NPE this deploy targeted.

### Step 5: Rounds drive bets and end-game accounting
Command: `bot_bets_placed_total?tag=gameType:TAI_XIU`, `bot_bet_amount_total?...`
Expected: both metrics > 0; StartGame/EndGame frames at fixed Tai Xiu CMDs.
Actual: both metric series empty (no measurement) — no Tai Xiu round ran because WS never
connected (staging DNS). BLOCKED BY ENV.
Result: FAIL (blocked by env — staging DNS, not the feature)

### Step 6: Winnings counter populated (RTP feeds), refund-aware sanity
Command: `bot_winnings_total?tag=gameType:TAI_XIU`
Expected: measurement >= 0; plausible refund-aware RTP.
Actual: series empty — no Tai Xiu rounds occurred. BLOCKED BY ENV.
Result: FAIL (blocked by env — staging DNS, not the feature)

### Step 7: No regression in BettingMini and no wedged Tai Xiu bots; observability still UP
Command: `grep -E "ERROR|marking DEAD" <log> | grep -iE "tai|xiu"`; spot-check BettingMini;
re-verify Grafana/Prometheus/Loki.
Actual:
  - Tai Xiu ERROR/DEAD: only the two deliberate probe failures (options-missing on game 1;
    nothing else). No `marking DEAD`, no offset NPE, no wedged bots.
  - Regression: NO regression. 4 groups auto-started on boot. All staging TIP/slot groups
    (116 Demo, XD game test, Fruit shop Bots, Slot test 204) started with **0 bots** for the
    SAME `UnknownHostException: tipclubgw-sock.stgame.win` DNS reason — i.e. the whole staging
    env currently cannot reach its game-server WS gateways; this is not specific to Tai Xiu and
    not introduced by the Phase-1 seam refactor. No NPE/DEAD in any group.
  - Observability re-verified UP: Grafana 200, Prometheus 200, Loki 200, promtail Up,
    mongo Up (healthy), bot-manager Up (healthy). PASS.
Result: PARTIAL — no-regression + observability PASS; runtime half blocked by the same env DNS.

## Verdict

**PASS (for this deploy's success criterion) — with two follow-ups (one infra, one feature gap).**

The success criterion for THIS re-deploy was: *the offset NPE is gone so a correctly-configured
Tai Xiu group can create its bots.* That is **confirmed**:

1. The `Game.getOffset()` NPE that produced the previous 0/N failure is **eliminated** — zero
   occurrences in the full log; the inherited init path now runs cleanly on a null-offset game.
2. Bot creation proceeds all the way through `BettingMiniGameBot initialized` + auth-token
   resolution and only stops at WS connect on the **environmental** staging-DNS failure
   (`UnknownHostException: tipclubgw-sock.stgame.win`) — the exact infra caveat called out in the
   brief. DNS confirmed unresolvable from inside the container, and it affects every staging
   group equally (all 4 auto-started groups also got 0 bots for the same reason).

Build/ship/deploy/smoke all PASS; observability stack came back UP; validator factory registers
TAI_XIU; strategies list for TAI_XIU; a TAI_XIU game creates; invalid grids are rejected (400).

Runtime betting / endgame / refund-aware RTP metrics (steps 5–6) remain **unverifiable on
staging** until the TIP WS gateway DNS is restored — an infra follow-up, not a feature defect.

### Follow-up 1 (INFRA — same as last deploy): staging DNS for the TIP WS gateway
`tipclubgw-sock.stgame.win` does not resolve from the bot-manager container
(`getent hosts` → UNRESOLVABLE). Until staging can resolve/reach the TIP socket gateway, NO bot
on a TIP env (Tai Xiu or BettingMini or Slot) can connect, so rounds/bets/metrics cannot be
observed on staging. Not a code issue.

### Follow-up 2 (FEATURE GAP — new, distinct from the offset NPE): plan-correct Tai Xiu game needs option config
A Tai Xiu game created exactly as the plan's Step 3 prescribes (no offset, no option config)
still cannot create bots — the inherited init calls `Game.getEffectiveOptionAffinities()`
(Game.java:139), which throws
`IllegalStateException: ... has neither optionAffinities nor legacy numberOfOptions set` when a
game has none of `optionAffinities` / `bettingOptions` / `numberOfOptions`. Tai Xiu is a balanced
**2-entry** game (Tai vs Xiu, AD-11), so the natural config is `numberOfOptions:2` (which the
mapper expands to `optionAffinities:{0:1,1:1}`). With that set, init succeeds and the bot reaches
WS connect (proven above). This is a downstream sibling of the offset issue: the shared init path
still imposes a BettingMini-style requirement that AD-9's "reuses option config" did not pin down,
and neither the plan's Step-3 create body nor `TaiXiuConfigValidator` requires/sets it.
Recommended direction (for architect/dev, NOT done here): either (a) make Step 3 / docs require
`numberOfOptions:2` for Tai Xiu games, or (b) have TaiXiuConfigValidator default/require the 2
entries, or (c) default option config for TAI_XIU in the init/factory path. This is a process/
config gap, not a crash regression — it produces a clean validation-style error, not an NPE.

No code was changed by this releaser (releaser writes only this release log). All test
games/groups created during verification (2 games, 2 groups) were stopped and deleted; staging
left clean (0 TaiXiu groups, 0 TaiXiuRel2 games remaining).

## Logs (key excerpts)

Offset-NPE fix confirmed — bot init runs on a null-offset (option-configured) game, group
08a7f294-1ba4-4dcc-90dc-4e7320a05a31:
```
11:04:26.345 BettingMiniGameBot - BettingMiniGameBot initialized: game=TaiXiuRel2WithOptions, offset=0, options=2, md5=false, watchdog=180s, strategy=RANDOM
11:04:26.345 Bot txr2o1: AUTHENTICATED → CONNECTING
11:04:26.345 VingameWebSocketClient - Client ws-txr2o1: Resolving authentication tokens...
11:04:26.345 VingameWebSocketClient - Client ws-txr2o1: Authenticated with token 189-9fa7......
11:04:26.352 ERROR BotGroupBehaviorService - Failed to create bot 1/2 ... : java.net.UnknownHostException: tipclubgw-sock.stgame.win: Name or service not known
```

Follow-up 2 — plan-correct (no-option) Tai Xiu game, group 243a6b77-d322-4af4-8e5e-17c922b80767:
```
11:02:55.132 Bot txr2v1: AUTHENTICATING → AUTHENTICATED
11:02:55.132 ClientFactory - Created client ws-txr2v1 for txr2v1
11:02:55.133 ERROR BotGroupBehaviorService - Failed to create bot 1/2 ... : java.lang.IllegalStateException: Game 9afdcd9f... (TaiXiuMiniGameRelease2) has neither optionAffinities nor legacy numberOfOptions set
```

DNS confirmation (inside bot-manager container):
```
getent hosts tipclubgw-sock.stgame.win  →  UNRESOLVABLE
```
