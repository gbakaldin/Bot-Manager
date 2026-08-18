# TIP Prod Tai Xiu — Layer Ramp Runbook (L1–L9)

**Type:** operational runbook (no code changes). Executed on `Prod-Bot`.
**Status:** L0 live since 2026-08-11. **L1–L9 not created — deliberately parked**
pending a monitoring period on L0.

---

## Goal

Scale TIP prod Tai Xiu from the current 50 bots to **500**, as **10 uniform layers
of 50** whose activation windows stack into a daily curve. Group size *is* the ramp
granularity — activation is binary per group — so uniform 50-bot layers keep every
transition a clean ±50 step.

---

## Current state (verified 2026-08-12)

| Fact | Value |
|---|---|
| Prod host | `s009-botgame-general-01` (ssh alias `Prod-Bot`) |
| Environment `_id` | `78b6eefb-8930-4e2d-ba56-2c245ffc8551` ("TIP Production") |
| Tai Xiu game `_id` | `c0a54255-b73d-485e-ba00-4418b378a8b9` (`TAI_XIU`, `taixiuPlugin`) |
| **L0 group `_id`** | `ef3b61af-12af-4791-b94c-ff521a04cfd5` |
| L0 prefix / accounts | `tptxg2` → `tptxg21`…`tptxg250` |
| L0 funding | 5,000,000 × 50 = **250,000,000** |

L0's first 20 hours, which is what the remaining layers are being judged against:

- 50/50 connected, 0 dead, **0 watchdog expiries, no subscriber pruning**
- 51,104 bets, **150,300,000** turnover, net **−1,043,180**
- **Implied edge 0.694% → RTP ≈ 99.31%**, runway ≈ **190 days**
- Coordinator **hard-binding**: exactly 150,000 staked/round, `reject=1482` vs `approve=311`
- Rounds ~68 s, settling cleanly (9 entered / 8 ended in the sample window)

---

## Gates — do not create L1+ until these hold

1. **L0 has run a full daily cycle** including the overnight trough, with no decay in
   bettors-per-round and no watchdog churn.
2. **`total win` populates.** As of 2026-08-12 stake settles but it is unconfirmed
   whether the winnings metric reads non-zero, or whether that is still the separate
   `HasBotWinnings` gap. RTP is currently *inferred* from balance deltas, not read.
3. **The funding decision is made deliberately.** L1–L9 at the same 5,000,000/account
   is **2,250,000,000** more, for **2,500,000,000** total fleet exposure. See
   *Funding scale* below before assuming that is the right number.
4. **Coordinator caps decided.** Each group gets its **own** coordinator — the cap is
   per-group, so 10 layers at 150,000 means a fleet worst case of **1,500,000/round**.
   There is no cross-group ceiling; that is what the Fleet abstraction is for.

---

## The layer schedule

All times **Asia/Ho_Chi_Minh** (`bot.activation.zone`). L0 is `MANUAL_ON`, never
reconciler-driven, so a schedule bug cannot zero the floor.

| Layer | Prefix | Window | Accounts |
|---|---|---|---|
| L0 *(live)* | `tptxg2` | `MANUAL_ON` — 24/7 | `tptxg21`…`tptxg250` |
| L1 | `txl1` | 08:30 → 01:30 | `txl11`…`txl150` |
| L2 | `txl2` | 10:00 → 01:00 | `txl21`…`txl250` |
| L3 | `txl3` | 11:30 → 00:30 | `txl31`…`txl350` |
| L4 | `txl4` | 13:00 → 00:00 | `txl41`…`txl450` |
| L5 | `txl5` | 15:00 → 23:40 | `txl51`…`txl550` |
| L6 | `txl6` | 17:00 → 23:20 | `txl61`…`txl650` |
| L7 | `txl7` | 18:30 → 23:00 | `txl71`…`txl750` |
| L8 | `txl8` | 19:30 → 22:40 | `txl81`…`txl850` |
| L9 | `txl9` | 20:30 → 22:20 | `txl91`…`txl950` |

Resulting fleet curve: floor **50** (01:30–08:30), 12 h up-ramp, **500** at
20:30–22:20, 3 h down-ramp.

**Why `txl*` and not `tptxg3…`:** `tptxg1` already exists (`tptxg11`…`tptxg15`, the
5-account group `dee8fabd`). Continuing that scheme risks visually ambiguous names at
50-account scale. Registered usernames are **permanent upstream** and cannot be
released, so collisions are unrecoverable. `txl9` + 2 digits = 6 chars, well inside
TIP's 12-char cap.

**Why the edge times never collide and are ≥20 min apart:** `ActivationScheduler`
runs on a **single thread** and calls `behaviorService.start(id)` **inline**
(`ActivationScheduler.java:141`). A slow or hung start blocks every other group's
transition behind it. Do not "tidy" these to round hours.

---

## ⚠️ Sequencing — the trap

**Create the group with NO activation config. Fund it. Only then PATCH the
activation in.**

A group created with `activationMode: SCHEDULED` inside an already-open window will
be started by the reconciler **within 60 seconds** — unfunded. Fifty bots that
connect and cannot bet, with `autoDepositEnabled: false` meaning they never recover.

So the order is always: **create (dormant) → fund → arm**.

---

## Step 1 — create the layer groups (dormant)

Run **on Prod-Bot**. Creates all nine with no activation config and no
`targetStatus`, so nothing starts.

```bash
#!/usr/bin/env bash
set -euo pipefail

ENV_ID="78b6eefb-8930-4e2d-ba56-2c245ffc8551"
GAME_ID="c0a54255-b73d-485e-ba00-4418b378a8b9"
BOT_PW="${TIP_PROD_BOT_PASSWORD:?set TIP_PROD_BOT_PASSWORD first}"
API="http://localhost:8080/api/v1/bot-group"

# layer:prefix
LAYERS="1:txl1 2:txl2 3:txl3 4:txl4 5:txl5 6:txl6 7:txl7 8:txl8 9:txl9"

for entry in $LAYERS; do
  n="${entry%%:*}"; prefix="${entry##*:}"
  echo "=== creating L${n} (${prefix}) ==="
  curl -fsS -X POST "$API/" -H 'Content-Type: application/json' -d '{
    "name": "TIP Prod - Tai Xiu L'"$n"'",
    "environmentId": "'"$ENV_ID"'",
    "gameId": "'"$GAME_ID"'",
    "namePrefix": "'"$prefix"'",
    "password": "'"$BOT_PW"'",
    "botCount": 50,
    "minBet": 1000,
    "maxBet": 5000,
    "betIncrement": 1000,
    "minBetsPerRound": 0,
    "maxBetsPerRound": 6,
    "maxTotalBetPerRound": 30000,
    "autoDepositEnabled": false,
    "chatEnabled": false,
    "coordinationEnabled": true,
    "maxAggregateStakePerRound": 150000,
    "crowdAwareCoordination": false,
    "rampEnabled": false,
    "affinityWeightedProposal": false,
    "strategyMix": [{"strategyId": "RANDOM", "weight": 1.0}]
  }' | tee "/tmp/txl${n}-created.json"
  echo
done

echo "--- created ids ---"
for n in 1 2 3 4 5 6 7 8 9; do
  printf 'L%s ' "$n"; grep -o '"id":"[^"]*"' "/tmp/txl${n}-created.json" | head -1
done
```

**Do them one at a time if you prefer** — registration is synchronous and fans out at
`user.registration.parallelism=10`, so each call takes ~0.5 s and registers 50
accounts. Confirm `Success: 50, Failures: 0` in the logs for each before continuing:

```bash
docker logs bot-java-bot-manager-1 2>&1 | grep "Parallel user registration completed" | tail -9
```

If any layer reports fewer than 50, **stop** — do not re-POST, that would claim a
second block of usernames.

---

## Step 2 — fund each layer

**⚠️ The deposit endpoint is NOT idempotent. Every POST credits again. Never retry
blindly.** This is the step that moves real money.

```bash
#!/usr/bin/env bash
set -euo pipefail

PREFIX="${1:?usage: fund-layer.sh <prefix>   e.g. fund-layer.sh txl1}"
AMOUNT=5000000
GATEWAY="${TIP_PROD_GATEWAY:?prod TIP apiGateway base URL}"
XTOKEN="${TIP_PROD_XTOKEN:?per-env admin X-TOKEN}"

for i in $(seq 1 50); do
  u="${PREFIX}${i}"
  code=$(curl -s -o "/tmp/dep_${u}.json" -w '%{http_code}' \
    -X POST "${GATEWAY}/gwms/v1/bot/deposit.aspx" \
    -H 'Content-Type: application/json' \
    -H 'User-Agent: PostmanRuntime/7.15.2' \
    -H "X-TOKEN: ${XTOKEN}" \
    -d "{\"username\":\"${u}\",\"amount\":${AMOUNT}}")
  echo "${u} -> HTTP ${code} $(cat /tmp/dep_${u}.json)"
  [ "$code" = "200" ] || { echo "STOP: ${u} returned ${code} — do NOT retry, verify balance first"; exit 1; }
done
```

**Rules, learned the hard way on L0:**

- **Pre-check** every account reads `main_balance: 0` before starting. If any is
  already non-zero, stop — do not fund a single one.
- **Canary first.** Fund `<prefix>1` alone, verify it reads exactly 5,000,000, and
  only then run the remaining 49.
- **Sequential, one call each.** Not parallel.
- **On any timeout or non-200: stop.** An ambiguous call may have credited. Re-read
  the balance and decide manually; never re-issue on reflex.
- **Post-check** all 50 read exactly 5,000,000 and the layer total is 250,000,000.

Balances are read from **Prod-Bot itself** via `verifytoken.aspx?token=&fg=` →
`data[0].main_balance`. The session tokens are **VPS-bound and TCP-validated**, so
the same read from anywhere else fails and looks like a funding failure.

---

## Step 3 — arm the activation windows

Only after the layer is funded and verified.

```bash
#!/usr/bin/env bash
set -euo pipefail
API="http://localhost:8080/api/v1/bot-group"

# id:from:to  — fill the ids in from Step 1
arm() {
  local id="$1" from="$2" to="$3"
  curl -fsS -X PATCH "$API/$id" -H 'Content-Type: application/json' \
    -d "{\"activationMode\":\"SCHEDULED\",\"activationWindow\":{\"from\":\"$from\",\"to\":\"$to\",\"days\":null}}"
  echo
}

arm "<L1-id>" "08:30:00" "01:30:00"
arm "<L2-id>" "10:00:00" "01:00:00"
arm "<L3-id>" "11:30:00" "00:30:00"
arm "<L4-id>" "13:00:00" "00:00:00"
arm "<L5-id>" "15:00:00" "23:40:00"
arm "<L6-id>" "17:00:00" "23:20:00"
arm "<L7-id>" "18:30:00" "23:00:00"
arm "<L8-id>" "19:30:00" "22:40:00"
arm "<L9-id>" "20:30:00" "22:20:00"
```

**Verify the `LocalTime` JSON format on the first PATCH before running the rest.**
Jackson may expect `"08:30:00"` or an array form depending on the `JavaTimeModule`
config. GET the group back and confirm `activationWindow` round-tripped intact:

```bash
curl -s "$API/<L1-id>" | python3 -m json.tool | grep -A5 activationWindow
```

`PATCH` is safe to send minimally — `BotGroupMapper.updateEntityFromDto` boxes every
field with `Optional.ofNullable(...).orElse(existing)`, so omitted fields keep their
values. Validation requires `from != to` when `SCHEDULED`; all nine satisfy it.

Once armed, the reconciler starts the group at the next window open (within 60 s of
the boundary) and stops it at close. **No manual start is needed or wanted** — a
manual `start`/`stop` parks a SCHEDULED group as `MANUAL_ON`/`MANUAL_OFF` and takes
it off the schedule until you PATCH `activationMode` back to `SCHEDULED`.

---

## Verification

```bash
# List groups in the prod env — NOTE: GET /api/v1/bot-group/ returns 405.
curl -s -X POST http://localhost:8080/api/v1/bot-group/78b6eefb-8930-4e2d-ba56-2c245ffc8551/filter \
  -H 'Content-Type: application/json' -d '{}' | python3 -m json.tool | head -60

# Per-group health
curl -s http://localhost:8080/api/v1/bot-group/<id>/health | python3 -m json.tool

# Fleet-wide: connected bots, stake and balance across every layer
curl -s http://localhost:8080/api/v1/bot-group/<id>/health | python3 -c "
import sys,json
d=json.load(sys.stdin); b=d.get('bots') or []
print(d['groupName'], '|', d['status'], '| bots', len(b),
      '| staked', f\"{sum(x['totalBetAmount'] for x in b):,}\",
      '| balance', f\"{sum(x['balance'] for x in b):,}\")"

# Did the reconciler fire?
docker logs bot-java-bot-manager-1 2>&1 | grep "Activation reconcile" | tail -20

# Coordinator built with the right option count (proves the game config is intact)
docker logs bot-java-bot-manager-1 2>&1 | grep "Bet coordinator created" | tail -10
```

**The pruning check** — the one that matters as layers stack onto the same channel.
Count distinct usernames appearing per round; a decaying count means the server is
silently evicting subscribers while their sockets stay open:

```bash
docker logs bot-java-bot-manager-1 --since 15m 2>&1 \
  | grep "StartGame" | grep -o 'txl[0-9]*' | sort -u | wc -l
```

---

## Funding scale — read before committing

L1–L9 at 5,000,000/account is **2,250,000,000** additional, **2,500,000,000** fleet
total. Against L0's measured 0.694% edge that is roughly 190 days of runway, but the
layers are not all up 24/7, so real burn is lower.

**Consider funding the layers more thinly than L0.** L0 was funded at 5,000,000 to
match the existing prod convention, before the real edge was known. At 0.694%, a
50-bot layer running an 8-hour window turns over ~60,000,000/day and loses ~420,000 —
so even 1,000,000/account (50,000,000/layer) is months of runway. That would cut
fleet exposure from 2.5 B to ~700 M for the same behaviour.

The counter-argument is operational: **a drained bot does not stop cleanly.**
`BettingMiniGameBot.canBet()` never consults balance and `Bot.creditBalance()` walks
straight past zero into negative, so a dry bot keeps firing bets the server rejects,
with no alert and no backoff. Thinner funding means that failure mode arrives sooner.
Either fund generously, or fix the balance gate first.

---

## Gotchas

- **Whitelisting is per brand.** The single hardest-won lesson: balance funds fine,
  bots bet, and *nothing settles* until the host IP is whitelisted with back office
  for that brand. It looks exactly like a wallet-partition bug and has been
  misdiagnosed as one twice. See CLAUDE.md § Known Bugs. Only relevant if a layer
  ever moves to a new brand/product — P_116 is done.
- **FOLLOWUPS P8** — `BetCoordinator` is built in `startLocked` *before* the Tai Xiu
  option default is applied, so any `coordinationEnabled` Tai Xiu group on an
  **unconfigured** Game fails to start with HTTP 500. The prod game is already
  PATCHed with `optionAffinities {1:1, 2:1}`; a new environment or game needs the
  same, or the code fix.
- **Display-name collisions** already appeared at 50 accounts (`tptxg246` kept its
  default). Expect them to be routine across 450 more. Cosmetic, but bots sharing a
  default display name are conspicuous to real players — worth fixing before, not
  after.
- **The coordinator cap, not strategy, sets the stake.** `reject=1482` vs
  `approve=311` on L0. Raising `botCount` or `maxBetsPerRound` changes nothing until
  `maxAggregateStakePerRound` moves.
- **Username cap 12 chars for TIP**, unvalidated in code — a violation surfaces as 50
  upstream auth errors, not a clean 400. `txl9` + 2 digits = 6, safe.
- **Balance reads lag.** `checkBalance()` only re-reads the server when local drift
  exceeds 1% of the deposit amount (50,000 at 5,000,000). Expect a *spread* of
  `lastFetchedBalance` values, not uniform movement — that is not a bug.

---

## Rollback

Each layer is independent. To take one out of the schedule without deleting it:

```bash
curl -fsS -X PATCH http://localhost:8080/api/v1/bot-group/<id> \
  -H 'Content-Type: application/json' -d '{"activationMode":"MANUAL_OFF"}'
curl -fsS -X POST http://localhost:8080/api/v1/bot-group/<id>/stop
```

`MANUAL_OFF` takes it off the reconciler permanently until re-armed. Funds stay on
the accounts; nothing is lost. To restore, PATCH `activationMode` back to
`SCHEDULED` — the window is still stored.
