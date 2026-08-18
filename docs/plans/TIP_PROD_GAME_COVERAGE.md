# TIP Prod Game Coverage — Releaser Runbook

**Type:** operational runbook (no code changes). Executed by the **Releaser** on
`Prod-Bot` immediately after a successful deploy + smoke test.
**Author:** Architect. **Executor:** Releaser. Architect executes nothing.

---

## Goal

Stand up **one bot group per TIP game** in production so every game that exists
in the TIP staging environment has a live, funded, deliberately-tiny bot presence
in prod. Each group runs **exactly 5 accounts** with **auto-deposit off** and a
**one-time 5,000,000 manual top-up per account** performed outside the
application, so the total production exposure is a fixed, known, non-replenishing
number. Betting groups are configured with *vastly different* strategy /
coordination / ramp settings inside a common small-stake envelope
(individual bet 500–5,000; per-bot per-round ceiling 5,000–20,000) so that this
first prod fleet also doubles as a broad behavioural soak test. Slot groups take
their stakes from the server and are therefore configured minimally by design.

---

## Findings — Current State

### Environment / host (from the task brief; Releaser must re-verify Step 0)

| Fact | Value |
|---|---|
| Prod environment `_id` | `78b6eefb-8930-4e2d-ba56-2c245ffc8551` ("TIP Production") |
| Brand / product | `G3` / `P_116` |
| `usernameMaxLength` for P_116 | `12` — `/Users/gleb/IdeaProjects/Bot/bot-api/src/main/java/com/vingame/bot/domain/brand/model/ProductCode.java:15` |
| Host / deploy dir | `Prod-Bot`, `/home/sgame/bot-java` |
| App port (host) | `8080` |
| Mongo container / DB | `bot-java-mongo-1` / `botmanager` |

### Games already in prod (4) — reused, not recreated

| Game | Prod `_id` | Type |
|---|---|---|
| Bau Cua | `928ba36f-7d32-407c-9456-ea5df9d64e63` | BETTING_MINI |
| Xoc Dia | `e3fb0020-c76b-4888-9ea3-9af41a9341ee` | BETTING_MINI |
| Fruit Shop | `9e8dd86e-48ac-439a-8493-cb5f158f5694` | BETTING_MINI |
| Tai Xiu | `c0a54255-b73d-485e-ba00-4418b378a8b9` | TAI_XIU |

All 7 staging SLOT records are missing from prod. They collapse to **6 distinct
`gameId`s** (204, 224, 118, 119, 120, 117) — the second `SlotTipTest` is a
duplicate of `gameId 204` (see AD-1).

### Game creation does **not** need raw Mongo — there is a REST create

`GameController.save` is `POST /api/v1/game/{brandCode}/{productCode}/{envId}`
(`/Users/gleb/IdeaProjects/Bot/bot-app/src/main/java/com/vingame/bot/domain/game/controller/GameController.java:118-133`).
It sets `brandCode` / `productCode` / `environmentId` **from the path**, never
from the body. `GameService.save`
(`/Users/gleb/IdeaProjects/Bot/bot-app/src/main/java/com/vingame/bot/domain/game/service/GameService.java:113-125`)
generates a fresh UUID `_id`, stamps `createdAt` (once) and `updatedAt` (always)
as real `Instant`s, and Spring Data writes the `_class` discriminator and BSON
types itself. **This makes the raw-Mongo insert path in the task brief
unnecessary and strictly worse** — hand-written `NumberLong` / `_class` /
`ISODate` fields are exactly the class of error the REST path eliminates.
See AD-3.

`GameDTO` covers every field the staging records carry — `name`, `description`,
`gameType`, `pluginName`, `gameId`, `offset`, `md5`, `optionAffinities` /
`numberOfOptions`, `jackpotScaleEnabled`, `jackpotCeiling`, `crowdCountSemantic`
(`/Users/gleb/IdeaProjects/Bot/bot-app/src/main/java/com/vingame/bot/domain/game/dto/GameDTO.java:22-84`).

A SLOT game with **no** option config is safe: `GameMapper.toDTO` catches the
`IllegalStateException` from `Game.getEffectiveOptionAffinities()`
(`/Users/gleb/IdeaProjects/Bot/bot-app/src/main/java/com/vingame/bot/domain/game/mapper/GameMapper.java:30-37`)
and no slot code path reads options — the coordinator/scaler wiring is gated on
`BETTING_MINI || TAI_XIU`
(`/Users/gleb/IdeaProjects/Bot/bot-app/src/main/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorService.java:338-372`).
This matches staging exactly.

### `maxTotalBetPerRound` is inert — **confirmed**

The only readers are the mapper, the `MAX_PER_ROUND` sort key, the health DTO and
create-time validation:

- `/Users/gleb/IdeaProjects/Bot/bot-app/src/main/java/com/vingame/bot/domain/botgroup/sort/BotSortKey.java:62`
- `/Users/gleb/IdeaProjects/Bot/bot-app/src/main/java/com/vingame/bot/domain/botgroup/mapper/BotGroupMapper.java:34,77,118`
- `/Users/gleb/IdeaProjects/Bot/bot-app/src/main/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorService.java:667` (health DTO only)
- `/Users/gleb/IdeaProjects/Bot/bot-app/src/main/java/com/vingame/bot/domain/botgroup/validation/BettingGridRules.java:68`

It is copied into `BotBehaviorConfig`
(`/Users/gleb/IdeaProjects/Bot/bot-api/src/main/java/com/vingame/bot/config/bot/BotBehaviorConfig.java:30`)
and **never read by any strategy or bot**. The task brief is correct.

### The two ceilings that *are* real

1. **Per-bot, per-round:** `maxBetsPerRound × highest reachable amount`.
   `RandomBehaviorStrategy.decide`
   (`/Users/gleb/IdeaProjects/Bot/bot-strategies/src/main/java/com/vingame/bot/domain/bot/strategy/RandomBehaviorStrategy.java:105-112`):
   ```java
   int maxSteps = Math.toIntExact((maxBet - minBet) / betStep);
   long steps   = ctx.rng().nextInt(maxSteps + 1);
   long amount  = minBet + (steps * betStep);
   ```
   Highest reachable = `minBet + floor((maxBet-minBet)/betIncrement) * betIncrement`.
   The Martingale family is clamped identically — it aligns down to the
   `minBet + k·betIncrement` grid and **resets to `minBet` on a `maxBet` cap hit**
   (`MartingaleStrategySupport`, javadoc lines 56-60 and the clamp at 284-308), so
   **no strategy can ever stake above `maxBet`.**

2. **Group-wide, per-round — but only when `coordinationEnabled`.**
   `BetCoordinator.reserve`
   (`/Users/gleb/IdeaProjects/Bot/bot-engine/src/main/java/com/vingame/bot/domain/bot/coordination/BetCoordinator.java:318-344`)
   hard-trims every proposal to `min(remainingOption, remainingAggregate)`, so
   `maxAggregateStakePerRound` **is** enforced. This is the *only* configurable
   hard group-wide ceiling in the product. It is wired only for
   `BETTING_MINI`/`TAI_XIU`
   (`BotGroupBehaviorService.java:338-356`).
   Side effect (`BotGroupBehaviorService.java:674-679`): under coordination
   `betSkipPercentage` is pinned to `0`, so bots propose on **every** tick.

`betSkipPercentage` is otherwise **never set** (builder default `0`) — there is
no property or DTO field for it. So on non-coordinated groups every bot proposes
a bet on every 1 s tick until it hits `maxBetsPerRound`.

### Cadence

- Betting/Tai Xiu bet tick: `1_000 ms` — `BettingMiniGameBot.resolveIntervalBetweenBets`
  (`/Users/gleb/IdeaProjects/Bot/bot-engine/src/main/java/com/vingame/bot/domain/bot/core/BettingMiniGameBot.java:856-858`), used at `:915`.
- Slot spin tick: `3_000 ms` — `SlotMachineBot.resolveSpinInterval`
  (`/Users/gleb/IdeaProjects/Bot/bot-engine/src/main/java/com/vingame/bot/domain/bot/core/SlotMachineBot.java:364-366`), used at `:398`.

### SLOT: `slotStrategyId` is **force-overridden**, not selectable

Correction to the task brief. `BotGroupBehaviorService.createSingleBot`
(`/Users/gleb/IdeaProjects/Bot/bot-app/src/main/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorService.java:720-728`):

```java
SlotStrategyId slotStrategyId = null;
if (game.getGameType() == GameType.SLOT) {
    slotStrategyId = SlotStrategyId.FIXED;   // client value silently ignored
}
```

So a group-level `slotStrategyId` is accepted by the API and then discarded.
Every slot bot runs `FixedBetStrategy`, which stakes `allowedBetValues.get(0)` —
**the smallest server-allowed bet, always**
(`/Users/gleb/IdeaProjects/Bot/bot-strategies/src/main/java/com/vingame/bot/domain/bot/strategy/slot/FixedBetStrategy.java:25-30`).
That is already the smallest-stake behaviour the user asked for; nothing to
configure and nothing that *can* be varied.

Total stake per spin is **`chosenBet × numLines`**, both server-sourced from the
`cmd:1300` subscribe response (`SlotMachineBot.java:188-201`, `:240-249`,
`:297-298`). Neither is knowable before the first subscribe. This drives
Concern C-1 below.

### Group creation surface

`POST /api/v1/bot-group/` `@Validated(OnCreate.class)`
(`/Users/gleb/IdeaProjects/Bot/bot-app/src/main/java/com/vingame/bot/domain/botgroup/controller/BotGroupController.java:107-118`).
Required non-blank: `environmentId`, `namePrefix`, `password`, `gameId`;
`botCount` `@Positive`
(`/Users/gleb/IdeaProjects/Bot/bot-app/src/main/java/com/vingame/bot/domain/botgroup/dto/BotGroupDTO.java:35-48`).

- Omitting `existingGroup` ⇒ `skipRegistration=false` ⇒ **real registration runs**
  (`BotGroupService.save`, `/Users/gleb/IdeaProjects/Bot/bot-app/src/main/java/com/vingame/bot/domain/botgroup/service/BotGroupService.java:130-190`).
- Order on create: `configValidation.validate` → `validateGameEnvironmentMatch` →
  `validateUsernameLength` → registration. A bad grid or a cross-env `gameId`
  fails as one clean 400 **before** any account is created.
- Username = `namePrefix + i`, `i = 1..botCount`, **no zero-padding**
  (`ApiGatewayClient.registerUsers:197-199`). With 5 bots the longest username is
  `prefix + "5"`, so `namePrefix ≤ 11` chars for P_116.
- `validateUsernameLength` (`BotGroupService.java:230-247`) already enforces that
  as a 400 — the CLAUDE.md backlog entry claiming it is missing is stale.
- **Creation does not start anything.** The only auto-start is
  `BotGroupBehaviorService.onStartup` `@PostConstruct`, which starts groups with
  `targetStatus == ACTIVE` (`:214-234`). Creating with `targetStatus` omitted
  leaves it `null` ⇒ never auto-started.
- `activationMode` omitted ⇒ `null` ⇒ legacy, non-scheduled group; the activation
  reconciler ignores it and `/start` / `/stop` do not flip any mode
  (`BotGroupController.runWithManualOverride:180-183`).

### Grid validation (`BettingGridRules.validate`, lines 64-180)

Aggregated `BadRequestException`. Applies to **BETTING_MINI and TAI_XIU only**
(`BettingMiniConfigValidator` / `TaiXiuConfigValidator`). `SlotConfigValidator`
is a deliberate, finalized no-op
(`/Users/gleb/IdeaProjects/Bot/bot-app/src/main/java/com/vingame/bot/domain/botgroup/validation/SlotConfigValidator.java:24-29`).
Rules as stated in the brief, all verified.

### Existing prod bot groups (2)

| `_id` | Name | Prefix | Bots | targetStatus |
|---|---|---|---|---|
| `b44b1cc9…` | TIP Prod - Xoc Dia test | `bottest0` ×9 | 9 | **ACTIVE** |
| `fc39ee39…` | TIP Prod - Tai Xiu autodeposit test | `txprodbot` ×3 | 3 | STOPPED |

`b44b1cc9` being ACTIVE means it **re-starts itself on every JVM boot** and it
targets the same Xoc Dia game as our new group. Its `autoDepositEnabled` is
unverified, and its name (`…autodeposit test` on its sibling) suggests at least
one of them was created to exercise auto-deposit — i.e. unbounded funding.
See AD-9.

### Gateway endpoints (verified in code)

| Purpose | Method + path | Auth |
|---|---|---|
| Login | `POST {apiGateway}/gwms/v1/bot/login.aspx` | `X-TOKEN: 58bc2820612d23c34fe43d0b2c6f7223` (`AuthStrategyFactory.java:40-47`) |
| Deposit | `POST {apiGateway}/gwms/v1/bot/deposit.aspx` body `{"username","amount"}` | same `X-TOKEN` (`ApiGatewayClient.java:52, 425-440`) |
| Balance | `GET {apiGateway}/gwms/v1/verifytoken.aspx?token=<authToken>&fg=<fingerprint>` → `data[0].main_balance` | none (`ApiGatewayClient.java:51, 472-492`) |

P_116 login body shape = `TipLoginRequest`
(`/Users/gleb/IdeaProjects/Bot/bot-engine/src/main/java/com/vingame/bot/domain/bot/auth/TipLoginRequest.java`):
`username, password, app_id="bc115116", os="OS X", device="Computer",
browser="chrome", fg, aff_id="", apVer="0.0.912", version="0.0.912", ip`.

---

## Per-aspect readiness / mapping

| Aspect | Status | Notes |
|---|---|---|
| Create 6 SLOT games in prod | **ready** | REST create (AD-3); no Mongo writes needed |
| Reuse 4 existing prod games | **ready** | ids confirmed in brief; re-verified in Step 0 |
| Create 10 bot groups via REST | **ready** | registration + validation both run on this path |
| `namePrefix ≤ 11` for 5 bots | **ready** | enforced as 400 by `validateUsernameLength` |
| `autoDepositEnabled: false` | **ready** | explicit field, defaults false anyway |
| Individual bet 500–5,000 | **ready** | grid config; reachable set stated per group (AD-5) |
| "5–20k per round" ceiling | **ready** | derived as `maxBetsPerRound × maxReachable`, **not** from `maxTotalBetPerRound` (AD-4) |
| Hard group-wide per-round cap | **partial** | only via `coordinationEnabled` + `maxAggregateStakePerRound`; used on 2 of 4 betting groups (AD-6) |
| "Vastly different settings" — betting | **ready** | 4 groups, disjoint levers (AD-5 table) |
| "Vastly different settings" — slot | **blocked by design** | nothing varies: bets server-sourced, `slotStrategyId` force-overridden (AD-2) |
| One-time 5,000,000 top-up ×50 | **ready** | `deposit.aspx` from Prod-Bot (egress `43.199.58.254` whitelisted) |
| Top-up idempotency | **blocked** | endpoint is **additive**, not idempotent — Concern C-2 |
| Balance verification pre-start | **partial** | needs the group password; requires user input (Open Item O-1) |
| Slot per-spin stake | **unknown until runtime** | `minBet × numLines` both server-sourced — Concern C-1 gates the slot start |
| Existing 2 prod groups | **decision** | stop, do not delete (AD-9) |

---

## Architecture Decisions

**AD-1 — 10 groups, not 11. Skip the duplicate `SlotTipTest`.**
The staging duplicate is two documents with the same `gameId: 204`, same
`pluginName`, same everything. Reproducing it in prod would create two groups
betting on one slot channel with no way to tell their traffic apart, doubling
exposure on a single game for zero information. Create **6** slot games
(`204, 224, 118, 119, 120, 117`) and **10** groups total (6 slot + 4 betting).
If the duplicate turns out to be load-bearing in staging, it is a one-command
addition later.

**AD-2 — Slot groups are configured minimally and identically; "vastly
different settings" does not apply to them.**
Slot stake = `allowedBetValues.get(0) × numLines`, both server-sourced;
`slotStrategyId` is silently overridden to `FIXED`
(`BotGroupBehaviorService.java:720-728`); the whole betting grid is inert
(`SlotConfigValidator` no-op). There is no lever left that changes slot
behaviour. Slot groups therefore differ **only by the game they play**, and their
betting-grid fields are **omitted entirely** (mapped to `0`) rather than filled
with misleading values. This is a deliberate finding, not laziness — do not
"fix" it by inventing values.

**AD-3 — Create games over REST, not by inserting into Mongo.**
`POST /api/v1/game/G3/P_116/{envId}` produces exactly the document Spring Data
would write: UUID `_id`, correct `_class`, `createdAt`/`updatedAt` as BSON dates,
correct BSON numeric types, `environmentId` from the path. Hand-writing
`NumberLong`/`ISODate`/`_class` into a raw insert reintroduces every type-drift
bug the REST path avoids. Mongo is used **read-only** in this runbook, for
verification only.

**AD-4 — Every per-round figure is `maxBetsPerRound × highest reachable amount`.
`maxTotalBetPerRound` is set to that same number as a *label only*.**
It is required to be `> 0` and `>= maxBet` by validation but is read by nothing
in the betting path. Setting it equal to the real ceiling keeps the UI/health DTO
honest without pretending it is enforcing anything. Each group's row below states
the **real** ceiling next to it.

**AD-5 — "5–20k per round" is read as a *per-bot* ceiling, and as a **range
across groups**, not a floor.**
Per-bot matches the field's own semantics and the user's pairing of it with
"individual bet between 500 and 5k" (a per-bot quantity). It is a ceiling only:
the brief's own arithmetic shows a ≥5,000 *floor* is unsatisfiable —
guaranteeing it needs `minBetsPerRound × minBet >= 5000`, i.e. ≥10 bets at
`minBet 500`, whose ceiling is `10 × 5,000 = 50,000`, far above 20,000. So
`minBetsPerRound = 0` on every group and the four betting groups' per-bot
ceilings are spread across the band: **8,000 / 20,000 / 10,500 / 10,000**.
Group-wide worst case under a per-bot reading is 5× that; see the exposure table.

**AD-6 — Two of the four betting groups run `coordinationEnabled` so that at
least part of the fleet has a *hard*, enforced group-wide per-round ceiling.**
`BetCoordinator.reserve` is the only mechanism in the product that can enforce
one. Xoc Dia (cap 20,000) and Tai Xiu (cap 15,000) get it; Bau Cua and Fruit
Shop deliberately run uncoordinated so the un-capped path is also exercised in
prod. **Safety does not depend on coordination:** the uncoordinated groups' bet
grids are chosen so their unenforced worst case (40,000 and 52,500 per round
group-wide) is already acceptable.

**AD-7 — `chatEnabled: false` on all 10 groups.**
Chat output is visible to real players in a production casino. Not a lever worth
varying on day one.

**AD-8 — `activationMode` and `scheduledRestartTime` omitted; groups are legacy
non-scheduled and created STOPPED (`targetStatus` omitted ⇒ `null`).**
Starting is a **separate, explicit, gated phase** (Phase 5). Note the
consequence: once started, `targetStatus` becomes `ACTIVE` and
`onStartup` will auto-start the group on the next JVM boot — that is intended,
but the Releaser must know it.

**AD-9 — The two pre-existing prod groups are *stopped*, not deleted, and the
new set coexists with them.**
`b44b1cc9` is ACTIVE, has 9 bots on the same Xoc Dia game, unverified
`autoDepositEnabled`, and auto-restarts on every boot — leaving it running would
contaminate the new fleet's metrics and could re-open unbounded funding.
Deleting it destroys evidence and its 9 registered accounts. Stop both
(`fc39ee39` is already STOPPED; the call is a harmless no-op that also pins
`targetStatus`), keep the documents.

**AD-10 — `namePrefix` scheme: `tp` + game token + `g1`.**
All ≤ 8 chars (so `prefix + "5"` ≤ 9 ≤ 12), globally distinctive, and sharing no
prefix with the existing `bottest0*` / `txprodbot*` accounts. The trailing `g1`
means "generation 1" — a future re-run uses `g2` and cannot collide with
already-registered usernames.

**AD-11 — One shared password for all 50 accounts, supplied by the user at run
time via `$TIP_PROD_BOT_PASSWORD`.**
The Architect does not invent a production credential. It is stored in cleartext
in Mongo (`BotGroup.password`) — pre-existing behaviour, flagged, not changed
here.

---

## Plan

Every phase below is copy-pasteable **on `Prod-Bot`**. Run them in order. Do not
proceed past a failed expectation.

### Phase 0 — Prerequisites and pre-flight (read-only)

```bash
set -u
export API=http://localhost:8080
export ENV_ID=78b6eefb-8930-4e2d-ba56-2c245ffc8551
export GW=https://getquazations.tidclupgws.com
export XTOKEN=58bc2820612d23c34fe43d0b2c6f7223
export MONGO='docker exec bot-java-mongo-1 mongosh botmanager --quiet --eval'
```

**0.1 — tooling present**
```bash
command -v jq && command -v curl && command -v md5sum && echo TOOLS_OK
```
Expect: three paths printed, then `TOOLS_OK`. If `jq` is missing, stop and
install it (or substitute `python3 -m json.tool`) — every verification below
depends on it.

**0.2 — app is up and healthy**
```bash
curl -sS -o /dev/null -w '%{http_code}\n' $API/actuator/health
```
Expect: `200`.

**0.3 — prod environment exists and is the right one**
```bash
curl -sS $API/api/v1/environment/$ENV_ID | jq '{id,name,productCode:.productCode.name,brandCode}'
```
Expect: `name` = `"TIP Production"`, `productCode` = `"TIP"`, `brandCode` = `"G3"`.

**0.4 — the 4 existing games are present with the expected ids**
```bash
curl -sS $API/api/v1/game/G3/P_116/$ENV_ID | jq -r '.[] | "\(.id)\t\(.gameType)\t\(.name)"' | sort
```
Expect: exactly 4 lines, ids matching the Findings table, no `SLOT` rows.

**0.5 — snapshot the pre-change state for rollback**
```bash
curl -sS $API/api/v1/game/G3/P_116/$ENV_ID > /tmp/prod-games-before.json
curl -sS -X POST $API/api/v1/bot-group/$ENV_ID/filter -H 'Content-Type: application/json' -d '{}' > /tmp/prod-groups-before.json
jq -r '.[] | "\(.id)\t\(.name)\t\(.botCount)\t\(.targetStatus)"' /tmp/prod-groups-before.json
```
Expect: exactly 2 lines (`…Xoc Dia test` ACTIVE 9, `…Tai Xiu autodeposit test`
STOPPED 3). **If more than 2 groups exist, stop and report — the brief's
baseline is wrong.**

**0.6 — egress IP is the whitelisted one** (the deposit gateway filters on TCP
source IP; the `ip` body field is ignored)
```bash
curl -sS https://api.ipify.org; echo
```
Expect: `43.199.58.254`. **If it differs, stop** — Phase 4 will silently fail or
be rejected.

**0.7 — password supplied by user** (see Open Item O-1)
```bash
test -n "${TIP_PROD_BOT_PASSWORD:-}" && echo PW_SET
```
Expect: `PW_SET`. This value must come from the user, not from this document.

---

### Phase 1 — Quiesce the two pre-existing prod groups (AD-9)

```bash
export OLD_XD=$(jq -r '.[] | select(.name|test("Xoc Dia test")) | .id' /tmp/prod-groups-before.json)
export OLD_TX=$(jq -r '.[] | select(.name|test("autodeposit test")) | .id' /tmp/prod-groups-before.json)
echo "OLD_XD=$OLD_XD OLD_TX=$OLD_TX"

curl -sS -o /dev/null -w 'stop XD: %{http_code}\n' -X POST $API/api/v1/bot-group/$OLD_XD/stop
curl -sS -o /dev/null -w 'stop TX: %{http_code}\n' -X POST $API/api/v1/bot-group/$OLD_TX/stop
```
Expect: `stop XD: 200`, `stop TX: 200`.

**Check**
```bash
for g in $OLD_XD $OLD_TX; do curl -sS $API/api/v1/bot-group/$g/status | jq -c '{id,targetStatus,actualStatus}'; done
```
Expect: both show `"targetStatus":"STOPPED"`.

---

### Phase 2 — Create the 6 missing SLOT games (AD-1, AD-3)

Mirrors staging exactly: `gameType: SLOT`, `md5: false`, no `offset`, no option
config, `jackpotScaleEnabled:false`, `jackpotCeiling:0`,
`crowdCountSemantic:"UNKNOWN"`. `pluginName` is `Tip` for every slot **except
gameId 120**, which is `slotMachineWithExtraJackpotsPlugin`.

```bash
mk_slot () {  # $1=gameId  $2=pluginName
  curl -sS -X POST "$API/api/v1/game/G3/P_116/$ENV_ID" \
    -H 'Content-Type: application/json' \
    -d "{\"name\":\"Slot $1\",\"description\":\"TIP prod slot $1\",\"gameType\":\"SLOT\",\"pluginName\":\"$2\",\"gameId\":$1,\"md5\":false,\"jackpotScaleEnabled\":false,\"jackpotCeiling\":0,\"crowdCountSemantic\":\"UNKNOWN\"}" \
  | jq -r '"\(.id)\t\(.gameId)\t\(.pluginName)\t\(.name)"'
}

mk_slot 204 Tip
mk_slot 224 Tip
mk_slot 118 Tip
mk_slot 119 Tip
mk_slot 120 slotMachineWithExtraJackpotsPlugin
mk_slot 117 Tip
```
Expect: 6 lines, each with a fresh UUID, the right `gameId`, the right
`pluginName`. A `null` id or an error body means the create failed — stop.

**Capture the new ids**
```bash
curl -sS $API/api/v1/game/G3/P_116/$ENV_ID > /tmp/prod-games-after.json
for g in 204 224 118 119 120 117; do
  export GID_$g=$(jq -r --argjson n $g '.[]|select(.gameType=="SLOT" and .gameId==$n)|.id' /tmp/prod-games-after.json)
done
env | grep '^GID_'
```
Expect: 6 lines, each a distinct UUID, none empty.

**Check**
```bash
jq 'length' /tmp/prod-games-after.json
jq -r '[.[]|select(.gameType=="SLOT")]|length' /tmp/prod-games-after.json
```
Expect: `10` and `6`.

---

### Phase 3 — Create the 10 bot groups (registration runs here)

Fixed on every group: `botCount: 5`, `autoDepositEnabled: false`,
`chatEnabled: false`, `environmentId` = prod env, no `existingGroup`, no
`targetStatus`, no `activationMode`.

#### 3a — Per-group configuration (AD-5, AD-6)

**Betting groups.** "Reachable" is the exact set `RandomBehaviorStrategy` /
Martingale can emit — note that for Fruit Shop and Tai Xiu it *does* include
`maxBet`, and for all four it is the true bet-size universe.

| # | Game | prefix | minBet | maxBet | betIncr | min/maxBetsPerRound | reachable amounts | **real per-bot ceiling** | nominal `maxTotalBetPerRound` | distinguishing levers |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | Bau Cua | `tpbcg1` | 500 | 2000 | 500 | 0 / 4 | 500,1000,1500,2000 | **8,000** | 8000 | plain baseline: no coordination, no ramp, no affinity; `strategyMix=[RANDOM 1.0]` |
| 2 | Xoc Dia | `tpxdg1` | 1000 | 5000 | 1000 | 0 / 4 | 1000…5000 step 1000 | **20,000** (group capped to 20,000, see below) | 20000 | `coordinationEnabled=true`, `maxAggregateStakePerRound=20000`, `crowdAwareCoordination=true`, `affinityWeightedProposal=true`; `strategyMix=[MARTINGALE_CLASSIC_CAUTIOUS 0.5, RANDOM 0.5]` |
| 3 | Fruit Shop | `tpfsg1` | 500 | 3500 | 750 | 0 / 3 | 500,1250,2000,2750,3500 | **10,500** | 10500 | `rampEnabled=true`, `rampShape=2.0`; 3-way cautious mix `[PAROLI_CAUTIOUS 0.4, DALEMBERT_CAUTIOUS 0.3, FIBONACCI_CAUTIOUS 0.3]`; no coordination |
| 4 | Tai Xiu | `tptxg1` | 1000 | 5000 | 2000 | 0 / 2 | 1000,3000,5000 | **10,000** (group capped to 15,000) | 10000 | `coordinationEnabled=true`, `maxAggregateStakePerRound=15000`, `crowdAwareCoordination=false`; 3-way **aggressive** mix `[MARTINGALE_CLASSIC_AGGRESSIVE 0.34, PAROLI_AGGRESSIVE 0.33, FIBONACCI_AGGRESSIVE 0.33]` |

Grid-validation pre-check (all pass): `(2000-500)%500=0`, `(5000-1000)%1000=0`,
`(3500-500)%750=0`, `(5000-1000)%2000=0`; every `maxTotalBetPerRound >= maxBet`;
every `minBet*minBetsPerRound = 0`; both coordinated groups have
`maxAggregateStakePerRound >= minBet`; `rampShape 2.0 > 0`;
`crowdAwareCoordination` only where `coordinationEnabled`.

**Slot groups (AD-2)** — identical config, differing only in game and prefix.
Betting fields omitted entirely.

| # | Game | prefix |
|---|---|---|
| 5 | Slot 204 | `tps204g1` |
| 6 | Slot 224 | `tps224g1` |
| 7 | Slot 118 | `tps118g1` |
| 8 | Slot 119 | `tps119g1` |
| 9 | Slot 120 | `tps120g1` |
| 10 | Slot 117 | `tps117g1` |

#### 3b — Commands

```bash
export PW="$TIP_PROD_BOT_PASSWORD"
export BC=928ba36f-7d32-407c-9456-ea5df9d64e63
export XD=e3fb0020-c76b-4888-9ea3-9af41a9341ee
export FS=9e8dd86e-48ac-439a-8493-cb5f158f5694
export TX=c0a54255-b73d-485e-ba00-4418b378a8b9

mk_group () {  # $1 = full JSON body
  curl -sS -X POST "$API/api/v1/bot-group/" -H 'Content-Type: application/json' -d "$1" \
  | jq -r 'if .id then "OK \(.id)\t\(.name)\tbots=\(.botCount)\tautoDep=\(.autoDepositEnabled)" else "FAIL \(.)" end'
}
```

Run these **one at a time**, checking each result before the next — each one
registers 5 real accounts upstream.

```bash
# 1 — Bau Cua
mk_group '{"name":"TIP Prod - Bau Cua","environmentId":"'"$ENV_ID"'","gameId":"'"$BC"'","namePrefix":"tpbcg1","password":"'"$PW"'","botCount":5,
"minBet":500,"maxBet":2000,"betIncrement":500,"minBetsPerRound":0,"maxBetsPerRound":4,"maxTotalBetPerRound":8000,
"coordinationEnabled":false,"crowdAwareCoordination":false,"rampEnabled":false,"affinityWeightedProposal":false,
"chatEnabled":false,"autoDepositEnabled":false,
"strategyMix":[{"strategyId":"RANDOM","weight":1.0}]}'

# 2 — Xoc Dia
mk_group '{"name":"TIP Prod - Xoc Dia","environmentId":"'"$ENV_ID"'","gameId":"'"$XD"'","namePrefix":"tpxdg1","password":"'"$PW"'","botCount":5,
"minBet":1000,"maxBet":5000,"betIncrement":1000,"minBetsPerRound":0,"maxBetsPerRound":4,"maxTotalBetPerRound":20000,
"coordinationEnabled":true,"maxAggregateStakePerRound":20000,"crowdAwareCoordination":true,
"rampEnabled":false,"affinityWeightedProposal":true,
"chatEnabled":false,"autoDepositEnabled":false,
"strategyMix":[{"strategyId":"MARTINGALE_CLASSIC_CAUTIOUS","weight":0.5},{"strategyId":"RANDOM","weight":0.5}]}'

# 3 — Fruit Shop
mk_group '{"name":"TIP Prod - Fruit Shop","environmentId":"'"$ENV_ID"'","gameId":"'"$FS"'","namePrefix":"tpfsg1","password":"'"$PW"'","botCount":5,
"minBet":500,"maxBet":3500,"betIncrement":750,"minBetsPerRound":0,"maxBetsPerRound":3,"maxTotalBetPerRound":10500,
"coordinationEnabled":false,"crowdAwareCoordination":false,
"rampEnabled":true,"rampShape":2.0,"affinityWeightedProposal":false,
"chatEnabled":false,"autoDepositEnabled":false,
"strategyMix":[{"strategyId":"PAROLI_CAUTIOUS","weight":0.4},{"strategyId":"DALEMBERT_CAUTIOUS","weight":0.3},{"strategyId":"FIBONACCI_CAUTIOUS","weight":0.3}]}'

# 4 — Tai Xiu
mk_group '{"name":"TIP Prod - Tai Xiu","environmentId":"'"$ENV_ID"'","gameId":"'"$TX"'","namePrefix":"tptxg1","password":"'"$PW"'","botCount":5,
"minBet":1000,"maxBet":5000,"betIncrement":2000,"minBetsPerRound":0,"maxBetsPerRound":2,"maxTotalBetPerRound":10000,
"coordinationEnabled":true,"maxAggregateStakePerRound":15000,"crowdAwareCoordination":false,
"rampEnabled":false,"affinityWeightedProposal":false,
"chatEnabled":false,"autoDepositEnabled":false,
"strategyMix":[{"strategyId":"MARTINGALE_CLASSIC_AGGRESSIVE","weight":0.34},{"strategyId":"PAROLI_AGGRESSIVE","weight":0.33},{"strategyId":"FIBONACCI_AGGRESSIVE","weight":0.33}]}'
```

Expect for each: one `OK <uuid> …  bots=5  autoDep=false` line.
A `FAIL` body containing `Invalid bot-group config:` is grid validation — do
**not** improvise a fix; report it, the numbers above are pre-checked.

```bash
# 5..10 — slot groups
mk_slot_group () {  # $1=gameDocId  $2=prefix  $3=label
  mk_group '{"name":"TIP Prod - '"$3"'","environmentId":"'"$ENV_ID"'","gameId":"'"$1"'","namePrefix":"'"$2"'","password":"'"$PW"'","botCount":5,"chatEnabled":false,"autoDepositEnabled":false}'
}

mk_slot_group "$GID_204" tps204g1 "Slot 204"
mk_slot_group "$GID_224" tps224g1 "Slot 224"
mk_slot_group "$GID_118" tps118g1 "Slot 118"
mk_slot_group "$GID_119" tps119g1 "Slot 119"
mk_slot_group "$GID_120" tps120g1 "Slot 120"
mk_slot_group "$GID_117" tps117g1 "Slot 117"
```
Expect: 6 × `OK <uuid> … bots=5 autoDep=false`.

**Check**
```bash
curl -sS -X POST $API/api/v1/bot-group/$ENV_ID/filter -H 'Content-Type: application/json' -d '{}' > /tmp/prod-groups-after.json
jq 'length' /tmp/prod-groups-after.json
jq -r '.[]|"\(.namePrefix)\t\(.botCount)\t\(.autoDepositEnabled)\t\(.targetStatus // "null")"' /tmp/prod-groups-after.json | sort
```
Expect: `12`; the 10 new rows all `5 / false / null`; the 2 old rows `STOPPED`.

**Registration-failure check** — a *partial* registration is a WARN, not an
error, and still creates the group:
```bash
docker logs --since 30m $(docker ps --format '{{.Names}}' | grep -i bot-manager) 2>&1 | grep -E 'Partial user registration|Failed to register' || echo NO_REG_FAILURES
```
Expect: `NO_REG_FAILURES`. If any line appears, the affected group has fewer than
5 usable accounts — report before topping up.

---

### Phase 4 — One-time 5,000,000 top-up, 50 accounts (run **on Prod-Bot**)

> **Run exactly once.** `deposit.aspx` is **additive** — re-running credits
> another 5,000,000. See Concern C-2.

```bash
export PREFIXES="tpbcg1 tpxdg1 tpfsg1 tptxg1 tps204g1 tps224g1 tps118g1 tps119g1 tps120g1 tps117g1"
for p in $PREFIXES; do for i in 1 2 3 4 5; do echo "$p$i"; done; done > /tmp/prod-bot-users.txt
wc -l < /tmp/prod-bot-users.txt
```
Expect: `50`.

```bash
: > /tmp/deposit-results.tsv
while read -r u; do
  code=$(curl -sS -o /tmp/dep.json -w '%{http_code}' -X POST "$GW/gwms/v1/bot/deposit.aspx" \
    -H "X-TOKEN: $XTOKEN" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$u\",\"amount\":5000000}")
  printf '%s\t%s\t%s\n' "$u" "$code" "$(tr -d '\n' < /tmp/dep.json)" >> /tmp/deposit-results.tsv
  sleep 0.3
done < /tmp/prod-bot-users.txt

awk -F'\t' '$2!=200 || $3 !~ /"code":200/ {print "BAD: "$0; bad++} END {print "bad="bad+0", total="NR}' /tmp/deposit-results.tsv
```
Expect: no `BAD:` lines, `bad=0, total=50`.

Re-drive **only** the failures (never the whole list):
```bash
awk -F'\t' '$2!=200 || $3 !~ /"code":200/ {print $1}' /tmp/deposit-results.tsv
```
Expect: empty. Any username printed here gets a single individual retry, then
its balance is confirmed at 5,000,000 in Phase V-5 before moving on.

---

### Phase 5 — Start the groups (SEPARATE, EXPLICIT, GATED)

**Do not run Phase 5 until every check in the Verification section below has
passed.** Starting flips `targetStatus` to `ACTIVE`, which also makes the group
auto-start on every subsequent JVM boot (AD-8).

**5a — Betting groups first** (bounded, well-understood per-round exposure):
```bash
for n in "TIP Prod - Bau Cua" "TIP Prod - Xoc Dia" "TIP Prod - Fruit Shop" "TIP Prod - Tai Xiu"; do
  gid=$(jq -r --arg n "$n" '.[]|select(.name==$n)|.id' /tmp/prod-groups-after.json)
  curl -sS -o /dev/null -w "$n: %{http_code}\n" -X POST $API/api/v1/bot-group/$gid/start
  sleep 20
done
```
Expect: four `… : 200` lines.

**5b — ONE slot group, then measure before starting the rest** (Concern C-1):
```bash
gid=$(jq -r '.[]|select(.name=="TIP Prod - Slot 204")|.id' /tmp/prod-groups-after.json)
curl -sS -o /dev/null -w "Slot 204: %{http_code}\n" -X POST $API/api/v1/bot-group/$gid/start
sleep 60
docker logs --since 5m $(docker ps --format '{{.Names}}' | grep -i bot-manager) 2>&1 \
  | grep -oE 'subscribed — numLines=[0-9]+, allowedBetValues=\[[0-9]+' | sort -u
```
Expect: at least one line. **Compute `numLines × firstAllowedBetValue` = stake
per spin.** With 5 bots at one spin per 3 s that is
`5 × stake × 20 = 100 × stake` per minute for this group.
**Gate: if `100 × stake` exceeds 200,000/min (i.e. `stake > 2,000`), stop and
report to the user before starting the remaining 5 slot groups.**

**5c — remaining slot groups** (only after 5b's gate passes):
```bash
for n in "TIP Prod - Slot 224" "TIP Prod - Slot 118" "TIP Prod - Slot 119" "TIP Prod - Slot 120" "TIP Prod - Slot 117"; do
  gid=$(jq -r --arg n "$n" '.[]|select(.name==$n)|.id' /tmp/prod-groups-after.json)
  curl -sS -o /dev/null -w "$n: %{http_code}\n" -X POST $API/api/v1/bot-group/$gid/start
  sleep 20
done
```
Expect: five `… : 200` lines.

---

## Implementation Notes / Concerns

**C-1 — Slot turnover dominates total exposure and is unknown until runtime.**
Betting groups are throttled by the round clock (one round ≈ tens of seconds);
slot bots spin every **3 s** with **no round gate**, and their stake is
`serverMinBet × numLines`. 30 slot bots at one spin per 3 s = **600 spins/min**.
If `serverMinBet × numLines` = 2,000, that is **1.2 M/min ≈ 72 M/hour of
turnover** against 150 M of slot funding — versus roughly 128 K per *round*
across all four betting groups. Slots are the risk, not the betting grid the
user was worried about. Hence the staged slot start and the explicit gate in 5b.

**C-2 — `deposit.aspx` is additive, not idempotent.** There is no "set balance"
call. A re-run of the Phase 4 loop credits another 5,000,000 per account. Always
diff against `/tmp/deposit-results.tsv` and retry individual usernames only.

**C-3 — Highest reachable ≠ `maxBet` in general.** All four grids here were
chosen so `(maxBet-minBet) % betIncrement == 0`, which makes them coincide — but
that is a property of these numbers, not a guarantee. Anyone editing the grid
later must recompute
`minBet + floor((maxBet-minBet)/betIncrement)*betIncrement`.

**C-4 — Under `coordinationEnabled`, bots stop skipping.**
`BotGroupBehaviorService.java:674-679` pins `betSkipPercentage` to 0, so the two
coordinated groups (Xoc Dia, Tai Xiu) propose a bet on **every** 1 s tick. Their
protection is the coordinator's aggregate cap, which is a *trim*, not a skip —
expect a high TRIM count in `/health.coordination`. That is correct behaviour,
not a fault.

**C-5 — A partial registration still yields a created group.**
`BotGroupService.save:170-180` only throws on *complete* failure; a 3-of-5
success logs WARN and persists a group whose `botCount` still says 5. The
top-up loop would then deposit into non-existent usernames. This is why the
log grep at the end of Phase 3 is mandatory, not optional.

**C-6 — `slotStrategyId` in a request body is silently ignored.** Do not send it
and do not conclude anything from it appearing in a GET response.

**C-7 — `/health` is runtime-backed.** For a STOPPED group it carries no bot
rows, so pre-start balance verification must go through the gateway
(`login.aspx` + `verifytoken.aspx`), not the app. Post-start,
`bots[].lastFetchedBalance` only refreshes when local drift exceeds 1,000,000 —
it is a coarse indicator, not a ledger.

**C-8 — Group passwords are stored in cleartext in Mongo.** Pre-existing
behaviour; noted so nobody is surprised by `db.botGroups.find()` output.

**C-9 — Starting a group makes it boot-persistent.** `targetStatus=ACTIVE` +
`onStartup` (`:214-234`). To take the fleet down for real, use `/stop` (which
persists STOPPED), not a container restart.

### Total exposure

| Item | Value |
|---|---|
| Accounts | 10 groups × 5 = **50** |
| Funding per account | 5,000,000 |
| **Total funded exposure** | **250,000,000** (betting 4×5×5M = 100 M; slot 6×5×5M = 150 M) |
| Worst-case stake per round — Bau Cua | 5 × 8,000 = 40,000 (unenforced) |
| Worst-case stake per round — Xoc Dia | **20,000** (coordinator-enforced) |
| Worst-case stake per round — Fruit Shop | 5 × 10,500 = 52,500 (unenforced) |
| Worst-case stake per round — Tai Xiu | **15,000** (coordinator-enforced) |
| **Worst case per round, all betting groups** | **127,500** |
| Worst case per minute, all slot groups | `600 × (serverMinBet × numLines)` — unknown until 5b; gate at 200,000/min/group |

Because `autoDepositEnabled` is false everywhere, this exposure is a **hard
ceiling**: bots cannot pull more funds, and a drained account simply stops
betting.

---

## Open Items

- **O-1 (blocking, needs user input): the shared bot password.** Supplied at run
  time as `$TIP_PROD_BOT_PASSWORD` (Phase 0.7). The Architect does not choose a
  production credential and it is not written into this document.
- **O-2 (cannot be verified non-interactively):** whether the accounts'
  5,000,000 landed in the **game-spendable** partition. `verifytoken.aspx`
  `main_balance` is what the app itself reads, and `deposit.aspx` is the
  known-good game-wallet path (`ApiGatewayClient.java:411-421`) — but the only
  true confirmation is a bet actually settling, i.e. non-zero `totalBetsPlaced`
  after Phase 5. Verification step V-8 covers it.
- **O-3 (out of scope):** the duplicate `SlotTipTest` (AD-1). Add later as a
  single `mk_slot_group` call with prefix `tps204g2` if wanted.
- **O-4 (out of scope):** deleting the two pre-existing prod groups and their 12
  accounts. They are stopped, not removed (AD-9).
- **O-5 (deferred):** per-group activation windows. All 10 groups are created
  non-scheduled (AD-8); time-boxing prod bot activity is a follow-up.
- **O-6 (known product gap, not fixed here):** `maxTotalBetPerRound` is dead
  config. It is set to the true ceiling as a label only (AD-4). Making it
  enforcing is a code change and is out of scope for this runbook.

---

## Verification

Run **V-1 … V-7 before Phase 5**. Run **V-8 … V-10 after Phase 5**.
Every step is a single shell command with an explicit expected result.

**V-1 — game count**
```bash
curl -sS $API/api/v1/game/G3/P_116/$ENV_ID | jq 'length'
```
Expect exactly `10`.

**V-2 — the 6 slot games exist with distinct ids and the right gameIds**
```bash
curl -sS $API/api/v1/game/G3/P_116/$ENV_ID \
 | jq -r '[.[]|select(.gameType=="SLOT")]|"count=\(length) ids=\([.[].id]|unique|length) gids=\([.[].gameId]|sort|tostring)"'
```
Expect exactly: `count=6 ids=6 gids=[117,118,119,120,204,224]`.

**V-3 — group count**
```bash
curl -sS -X POST $API/api/v1/bot-group/$ENV_ID/filter -H 'Content-Type: application/json' -d '{}' | jq 'length'
```
Expect exactly `12` (10 new + 2 pre-existing).

**V-4 — every new group has exactly 5 bots, auto-deposit off, and is not started**
```bash
curl -sS -X POST $API/api/v1/bot-group/$ENV_ID/filter -H 'Content-Type: application/json' -d '{}' \
 | jq -r '[.[]|select(.namePrefix|startswith("tp"))] | "n=\(length) badCount=\([.[]|select(.botCount!=5)]|length) badAutoDep=\([.[]|select(.autoDepositEnabled!=false)]|length) started=\([.[]|select(.targetStatus=="ACTIVE")]|length)"'
```
Expect exactly: `n=10 badCount=0 badAutoDep=0 started=0`.

**V-5 — all 50 deposits returned HTTP 200 with `"code":200`**
```bash
awk -F'\t' '{if($2==200 && $3 ~ /"code":200/) ok++} END {print "ok="ok+0"/"NR}' /tmp/deposit-results.tsv
```
Expect exactly: `ok=50/50`.

**V-6 — every account's balance is exactly 5,000,000** (gateway ledger; must run
on Prod-Bot)
```bash
: > /tmp/balances.tsv
while read -r u; do
  fg=$(printf '%s' "$u" | md5sum | cut -d' ' -f1)
  sid=$(curl -sS -X POST "$GW/gwms/v1/bot/login.aspx" -H "X-TOKEN: $XTOKEN" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$u\",\"password\":\"$PW\",\"app_id\":\"bc115116\",\"os\":\"OS X\",\"device\":\"Computer\",\"browser\":\"chrome\",\"fg\":\"$fg\",\"aff_id\":\"\",\"apVer\":\"0.0.912\",\"version\":\"0.0.912\",\"ip\":\"43.199.58.254\"}" \
    | jq -r '.data[0].session_id // .session_id // empty')
  bal=$(curl -sS "$GW/gwms/v1/verifytoken.aspx?token=$sid&fg=$fg" | jq -r '.data[0].main_balance // "ERR"')
  printf '%s\t%s\n' "$u" "$bal" >> /tmp/balances.tsv
  sleep 0.3
done < /tmp/prod-bot-users.txt

awk -F'\t' '$2!="5000000"{print "BAD "$0; bad++} END {print "bad="bad+0", total="NR}' /tmp/balances.tsv
```
Expect: no `BAD` lines and exactly `bad=0, total=50`.
*Note:* if `session_id` is nested differently in the prod response, the `sid`
extraction will be empty and every balance will read `ERR` — in that case dump
one raw login response and adjust the `jq` path before concluding anything.

**V-7 — no group auto-started itself and nothing is running yet**
```bash
curl -sS -X POST $API/api/v1/bot-group/$ENV_ID/filter -H 'Content-Type: application/json' -d '{}' \
 | jq -r '[.[]|select(.namePrefix|startswith("tp"))|.id] | .[]' \
 | while read -r g; do curl -sS $API/api/v1/bot-group/$g/status | jq -r '.actualStatus // "none"'; done | sort | uniq -c
```
Expect: a single line reading `10` followed by `STOPPED` or `none` — **no
`ACTIVE`, no `DEAD`**.

---

*After Phase 5 only:*

**V-8 — every started group has 5 connected bots and is actually betting**
```bash
curl -sS -X POST $API/api/v1/bot-group/$ENV_ID/filter -H 'Content-Type: application/json' -d '{}' \
 | jq -r '[.[]|select(.namePrefix|startswith("tp"))|.id]|.[]' \
 | while read -r g; do
     curl -sS $API/api/v1/bot-group/$g/health \
      | jq -r '"\(.groupName)\ttotal=\(.totalBots)\tconn=\(.connectedBots)\tdead=\(.deadBots)\tbets=\([.bots[].totalBetsPlaced]|add)"'
   done
```
Expect, per group after ~5 minutes of running: `total=5`, `conn=5`, `dead=0`,
and `bets > 0`. A group with `bets=0` after 5 minutes means bets are being
rejected — that is the O-2 wallet-partition signal; stop it and report.

**V-9 — no bet exceeds its group's `maxBet`** (spot-check on the widest group)
```bash
curl -sS -X POST $API/api/v1/bot-group/$ENV_ID/filter -H 'Content-Type: application/json' -d '{}' \
 | jq -r '.[]|select(.name=="TIP Prod - Fruit Shop")|.id' \
 | xargs -I{} curl -sS $API/api/v1/bot-group/{}/health \
 | jq -r '[.bots[]|select(.totalBetsPlaced>0)|(.totalBetAmount/.totalBetsPlaced)]|"avgBet min=\(min) max=\(max)"'
```
Expect: `max` ≤ `3500`. (This is a per-bot *average*, so any value above `maxBet`
is a hard contradiction and must be reported.)

**V-10 — the coordinator is enforcing its aggregate cap on Xoc Dia**
```bash
curl -sS -X POST $API/api/v1/bot-group/$ENV_ID/filter -H 'Content-Type: application/json' -d '{}' \
 | jq -r '.[]|select(.name=="TIP Prod - Xoc Dia")|.id' \
 | xargs -I{} curl -sS $API/api/v1/bot-group/{}/health | jq -c '.coordination'
```
Expect: a non-null object with `maxAggregateStakePerRound == 20000` and a
non-zero `approveCount`. Non-zero `trimCount`/`rejectCount` is expected and
healthy (Concern C-4), not a failure.

---

## Rollback

Reverses this runbook only. **It does not recover the deposited money** — the
250,000,000 stays on the 50 accounts (there is no withdraw call); the accounts
themselves are permanent on the auth gateway.

```bash
# 1. Stop + delete the 10 new groups (DELETE stops, logs out and unmanages first).
curl -sS -X POST $API/api/v1/bot-group/$ENV_ID/filter -H 'Content-Type: application/json' -d '{}' \
 | jq -r '.[]|select(.namePrefix|startswith("tp"))|.id' \
 | while read -r g; do curl -sS -o /dev/null -w "del group $g: %{http_code}\n" -X DELETE $API/api/v1/bot-group/$g; done
```
Expect: 10 × `200`.

```bash
# 2. Delete the 6 slot games. NOTE: GameService.delete CASCADES to every group
#    referencing the game (GameService.java:141-147) — run step 1 first anyway
#    so the deletion order is explicit and auditable.
for v in "$GID_204" "$GID_224" "$GID_118" "$GID_119" "$GID_120" "$GID_117"; do
  curl -sS -o /dev/null -w "del game $v: %{http_code}\n" -X DELETE $API/api/v1/game/$v
done
```
Expect: 6 × `200`.

```bash
# 3. Confirm the baseline is restored.
curl -sS $API/api/v1/game/G3/P_116/$ENV_ID | jq 'length'
curl -sS -X POST $API/api/v1/bot-group/$ENV_ID/filter -H 'Content-Type: application/json' -d '{}' | jq 'length'
```
Expect: `4` and `2` — matching `/tmp/prod-games-before.json` and
`/tmp/prod-groups-before.json`.

```bash
# 4. Optional: restore the pre-existing Xoc Dia test group to ACTIVE
#    (only if the user wants the prior state back exactly).
curl -sS -o /dev/null -w 'restart old XD: %{http_code}\n' -X POST $API/api/v1/bot-group/$OLD_XD/start
```
Expect: `200`.
