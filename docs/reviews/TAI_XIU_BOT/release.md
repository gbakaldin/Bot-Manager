# Release — TAI_XIU_BOT

Mode: bot
Branch: main
Image: vingame-bot:latest (built at 2026-06-24 14:36:49 local / target/Bot-1.0.jar)
Date: 2026-06-24T10:49:01Z
Source commit: 4819c9f34ee83b845bbd3d70d0b3ec2c087c96a1 (HEAD == origin/main)
Target: Bot-1 (/home/sgame/bot-java)

## Build

- `mvn clean install`: PASS (~22s). Tests run: 877, Failures: 0, Errors: 0, Skipped: 0. BUILD SUCCESS.
  Built with JAVA_HOME=/Users/gleb/Library/Java/JavaVirtualMachines/openjdk-21.0.2/Contents/Home.
- `docker build --no-cache --platform linux/amd64`: PASS. Image sha256:a1b294d535ee…, tagged vingame-bot:latest.
- `docker save -o bot.tar`: PASS (392,799,744 bytes).

Working tree note: `git status` showed only untracked files (docs/plans, docs/reviews, build
artifacts, `TaiXiuMessages/`, `app/`, `logs/`). No tracked production source was modified
(working tree clean for tracked files). HEAD == main == origin/main == 4819c9f. Deploy permitted.

## Ship

- `sftp put bot.tar`: PASS. Verified on server: 392,799,744 bytes (exact match with local).

## Deploy

- `docker compose down`: PASS (exit 0). Took down bot-manager + co-located mongo/grafana/
  prometheus/loki/promtail (single-compose project, expected). Pre-deploy state: bot-manager
  was Exited (139) (crashed ~17h earlier); observability + mongo were Up.
- `docker image rm vingame-bot:latest`: PASS (prior image 228807ee9611 untagged + deleted).
- `docker load -i bot.tar`: PASS (exit 0, "Loaded image: vingame-bot:latest").
- `docker compose up -d`: PASS (exit 0). All containers created and started; mongo reached
  Healthy before bot-manager started.

## Smoke test

- `docker ps` bot-manager healthy: PASS — "Up 35 seconds (healthy)" reached ~35s after start;
  steady at "Up 9 minutes (healthy)".
- Spring Boot ready log: PASS — `Started Starter in 6.374 seconds`.
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

Environment used: a P_116 / TIP env exists on staging — "116 Staging"
(id ad4e7948-fe24-4ef3-bd73-81f8956a94f0). Per Constraint 1, the Tai-Xiu-specific on-server
steps (4–7) were attempted on this P_116 env.

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
`{"name":"TaiXiuMiniGameRelease","gameType":"TAI_XIU","pluginName":"taixiuPlugin","gameId":1}`
Expected: HTTP 200/201 echoing gameType:TAI_XIU; GET returns the same.
Actual: HTTP 200, echoed `"gameType":"TAI_XIU"`, `"pluginName":"taixiuPlugin"`, product 116/TIP.
GET /api/v1/game/{id} returned the same. (Note: the plan's `POST /api/v1/game/` path is stale;
the current API is `POST /api/v1/game/{brandCode}/{productCode}` — brand/product come from the
path, so they must be omitted from the body.)
Result: PASS

### Step 4: A Tai Xiu bot group passes config validation and starts; bots reach CONNECTION_AUTHENTICATED
Commands: invalid-grid create (expect 400); valid 2-bot group create + `/start` + `/health`.
Expected: invalid grid → HTTP 400; valid group starts; bots show CONNECTION_AUTHENTICATED;
RECEIVED subscribe frame at the fixed Tai Xiu CMD per bot.
Actual:
  - Invalid grid (minBet 1000 > maxBet 500) → HTTP 400
    `Invalid bot-group config: minBet (1000) must be <= maxBet (500)`. Confirms TaiXiuConfigValidator
    now enforces the BettingMini grid rules (AD-8). PASS (validation sub-check).
  - Valid 2-bot group create → HTTP 200; `/start` → HTTP 200; but **/health showed totalBots=0,
    connectedBots=0, empty bots array** — NO bot reached CONNECTION_AUTHENTICATED.
  - Root cause in logs: both bots authenticated against the auth gateway, then bot CREATION
    failed with:
    `java.lang.NullPointerException: Cannot invoke "java.lang.Integer.intValue()" because the
    return value of "com.vingame.bot.domain.game.model.Game.getOffset()" is null`
    → `Created 0/2 bots successfully (2 failures)`.
Result: **FAIL**

### Step 5: Rounds drive bets and end-game accounting
Command: `curl bot_bets_placed_total?tag=gameType:TAI_XIU` and `bot_bet_amount_total?...`
Expected: both metrics > 0; StartGame/EndGame frames at fixed Tai Xiu CMDs.
Actual: not reachable — no Tai Xiu bot ever started a round (0 bots created, step 4 fail).
Result: **FAIL (blocked by step 4)**

### Step 6: Winnings counter populated (RTP feeds), refund-aware sanity
Command: `curl bot_winnings_total?tag=gameType:TAI_XIU`
Expected: measurement >= 0; plausible refund-aware RTP.
Actual: not reachable — no Tai Xiu rounds occurred.
Result: **FAIL (blocked by step 4)**

### Step 7: No regression in BettingMini and no wedged Tai Xiu bots; observability still UP
Command: `grep -E "ERROR|marking DEAD" <log> | grep -iE "tai|xiu"`; spot-check a BettingMini
group; re-verify Grafana/Prometheus/Loki.
Actual:
  - 4 Tai-Xiu ERROR lines, all from the deliberate verification attempts (the offset NPE x2,
    the options error x1 equivalent, the WS UnknownHostException x2). No Tai-Xiu group reached
    a runnable state to be "wedged" or marked DEAD.
  - BettingMini/Slot regression: NO regression. 4 groups auto-started on boot; existing
    BettingMini ("116 Demo group", "XD game test") and Slot ("Slot test 204") groups remain
    ACTIVE. The Phase-1 seam refactor did not break the inherited betting path.
  - Observability re-verified UP (Grafana 200, Prometheus 200, Loki 200, promtail Up, mongo
    Up healthy). PASS for this sub-check.
Result: PARTIAL — observability + no-BettingMini-regression PASS; but step 7 is reported FAIL
overall because its premise (a healthy Tai Xiu run) never occurred and the only Tai Xiu log
lines are ERRORs.

Steps run: 1, 2, 3, 4, 7 (and 4's validation sub-check). Steps deferred/blocked: 5, 6 (and the
runtime half of 4 and 7) — blocked by the bot-creation defect, NOT by environment availability
(a P_116 env was present, so these were attempted, not deferred).

## Verdict

**FAIL**

Deploy/ship/smoke all PASS. The image is live and healthy on Bot-1, the observability stack is
back up, the validator factory registers TAI_XIU, strategies list for TAI_XIU, a TAI_XIU game
creates and validates, and invalid grids are rejected. **However, no Tai Xiu bot can be created
at runtime**, so the feature's core (a Tai Xiu bot subscribing, betting, and doing refund-aware
endgame accounting on a P_116 env) is unverifiable and effectively non-functional with a
correctly-configured Tai Xiu game.

### Defect (deployed build) — fatal `game.getOffset()` NPE on Tai Xiu bot creation

`TaiXiuGameBot` (src/main/java/com/vingame/bot/domain/bot/core/TaiXiuGameBot.java) is correctly
written per the plan — it overrides every CMD seam to return fixed constants and its own class
doc states it "never reads game.getOffset()" (AD-9). **But it does not override the inherited
`initializeSubclass()`**, and that inherited method
(src/main/java/com/vingame/bot/domain/bot/core/BettingMiniGameBot.java:131) eagerly does:

```java
this.offset = game.getOffset();   // this.offset is primitive int → auto-unbox NPE when null
```

The plan prescribes Tai Xiu games carry **no offset** (AD-9: "offset is unused for Tai Xiu
because the CMD is fixed"). A Tai Xiu game created the way the plan/Step-3 prescribes therefore
has `offset == null`, and bot creation NPEs at init before the bot exists — both bots in the
test group failed (`Created 0/2 bots successfully`).

Probes confirming the chain (all on the P_116 env):
- offset = null (plan-correct config): `NullPointerException ... Game.getOffset() is null`.
- offset = 0 (workaround): NPE gone, next blocker →
  `IllegalStateException: Game ... has neither optionAffinities nor legacy numberOfOptions set`.
- offset = 0 + numberOfOptions = 2: both prior blockers gone; bot got to WS connect, then failed
  on `java.net.UnknownHostException: tipclubgw-sock.stgame.win` (a staging DNS/network issue for
  the TIP socket gateway — environmental, not the feature). The init log line at this point was
  `BettingMiniGameBot initialized: game=..., offset=0, options=2` (the inherited init logger).

Net: the inherited `initializeSubclass` forces Tai Xiu to supply an `offset` and option config it
is designed not to need, contradicting AD-9/Phase 7 ("must not read game.getOffset()"). The
Phase-1 seam refactor moved the CMD math behind seams but left the eager `offset` unbox in the
shared init path, so the fixed-CMD subclass still dies on a null offset. Recommended fix
direction (for dev, not done here): make the inherited `offset` assignment null-safe (e.g. a seam
`offsetOrZero()` / store `Integer` / guard the unbox) and let Tai Xiu skip the
option-config requirement, so a no-offset Tai Xiu game can create bots.

No code was changed by this releaser (releaser writes only this release log). Test
games/groups created during verification were stopped and deleted; staging left clean.

## Logs (FAIL excerpts)

bot-java-bot-manager-1, group 36f9864a-3de6-44e5-b720-1fe34f3efa5a (offset = null, plan-correct):
```
10:43:21.106 AuthClient - Authenticating user txrel1 ...
10:43:21.204 Bot txrel1: AUTHENTICATING → AUTHENTICATED
10:43:21.205 ClientFactory - Created client ws-txrel1 for txrel1
10:43:21.206 ERROR BotGroupBehaviorService - Failed to create bot 1/2 for group 36f9864a... :
  java.lang.NullPointerException: Cannot invoke "java.lang.Integer.intValue()" because the
  return value of "com.vingame.bot.domain.game.model.Game.getOffset()" is null
10:43:21.207 WARN  BotGroupBehaviorService - Created 0/2 bots successfully (2 failures) ...
10:43:21.209 INFO  BotGroupBehaviorService - Bot group TaiXiuRelease2 started successfully with 0 bots
```

group de6d56a6... (offset = 0):
```
10:44:58.151 ERROR BotGroupBehaviorService - Failed to create bot 1/2 ... :
  java.lang.IllegalStateException: Game 730b5983... (TaiXiuOffsetProbe) has neither
  optionAffinities nor legacy numberOfOptions set
```

group 100cc761... (offset = 0, numberOfOptions = 2):
```
10:46:28.339 INFO  BettingMiniGameBot - BettingMiniGameBot initialized: game=TaiXiuProbe3,
  offset=0, options=2, md5=false, watchdog=180s, strategy=RANDOM
10:46:28.345 ERROR BotGroupBehaviorService - Failed to create bot 1/2 ... :
  java.net.UnknownHostException: tipclubgw-sock.stgame.win   (environmental staging DNS issue)
```
