# Release runbook — BETTING_STRATEGIES (Phase 6)

Mode: bot
Branch: `feat/betting-strategies`
Plan: `docs/plans/BETTING_STRATEGIES.md`
Phase: 6 — Mongo migrations for `games` and `botGroups`
Author: Dev (handoff to Releaser)
Status: Documentation only — no production code changes ship in Phase 6.

---

## Scope

Phase 6 migrates two Mongo collections so that the read-side fallback paths
introduced in Phases 1 and 4 become inert:

1. **`games`** — synthesize `optionAffinities` from legacy `numberOfOptions`,
   then drop the legacy fields (`numberOfOptions`, `bettingOptions`).
2. **`botGroups`** — default any document missing `strategyMix` to
   `[{strategyId: "RANDOM", weight: 1.0}]`.

The read-side fallbacks (`Game.getEffectiveOptionAffinities()` and
`BotGroupBehaviorService.effectiveStrategyMix()`) already cover unmigrated
documents, so the app code can be deployed in advance of the migration with
**zero downtime** between the two steps.

## Order of operations (mandatory)

The two steps below MUST be executed in this order:

1. **Deploy the new bot-manager image first.** Phases 1–5 ship the read-side
   fallback. With the new image running, unmigrated documents still work.
2. **Run the Mongo migrations second.** The migration `$set`s the new fields
   and `$unset`s the legacy ones. After migration, the fallback code paths
   are inert but remain in place as belt-and-braces.

The reverse order is also tolerated (migration first, then deploy) because
the old image (pre-Phase 1) reads `numberOfOptions` directly and ignores
`optionAffinities` — but this leaves the old image running against
half-migrated data, which is harder to reason about. Stick to the
documented order.

**Do not restart bot groups for the migration.** `Game` and `BotGroup` are
read at group-start time, not on every tick. Running bots are unaffected by
the migration; they continue to use the snapshot loaded at start.

---

## Pre-flight (immediately after deploy, before migration)

Run from any host that can reach the bot-manager API. Replace
`<host>` with the staging or production host (e.g. `Bot-1`) and `<port>`
with `8080`.

> **`jq` not on Bot-1.** Replace any `| jq '<filter>'` with
> `| python3 -m json.tool` for raw pretty-print, or pipe through a small
> `python3 -c "import json,sys; d=json.load(sys.stdin); print(...)"` for
> field extraction. The exact substitutions are noted inline below.

```bash
# 1. App health
curl -fsS http://<host>:<port>/actuator/health
# expect: HTTP 200, body contains "status":"UP"

# 2. Existing groups still readable (read-side fallback for strategyMix)
curl -fsS http://<host>:<port>/api/v1/bot-group/ \
  | jq '.[0] | {id, name, strategyMix}'
# expect: HTTP 200; strategyMix is either present (already migrated /
#         freshly written) or absent (the app synthesizes
#         [(RANDOM, 1.0)] on the read path for assignment).

# 3. Existing games still readable (read-side fallback for optionAffinities)
#    NOTE: GameController has no GET "/" — use the filter endpoint with {}.
curl -fsS -X POST http://<host>:<port>/api/v1/game/filter/ \
  -H 'Content-Type: application/json' -d '{}' \
  | jq '.[0] | {id, name, optionAffinities}'
# expect: HTTP 200; optionAffinities is non-null and non-empty
#         (synthesized from numberOfOptions if the Mongo doc has not been
#         migrated yet).

# 4. Pick or start a small test group and verify per-bot strategy.
GROUP_ID=<staging-test-group-id>
curl -fsS -X POST http://<host>:<port>/api/v1/bot-group/$GROUP_ID/start
sleep 10
curl -fsS http://<host>:<port>/api/v1/bot-group/$GROUP_ID/health \
  | jq '.bots | map(.strategyId) | group_by(.) | map({key: .[0], count: length})'
# expect: every bot has strategyId "RANDOM". For [(RANDOM, 1.0)] a group
#         of N bots returns [{"key":"RANDOM","count":N}].
```

If any of the four pre-flight checks fail, **stop**. Do not run the
migrations against a broken deploy. The fallback code paths only protect
the read side; the migration is unforgiving.

---

## Step 1 — `games` collection migration

Connect with `mongosh` to the bot-manager Mongo instance. The default
database name is `botmanager`.

```bash
mongosh "mongodb://<mongo-host>:<mongo-port>/botmanager"
```

Run the following block. Each query is independent — the count queries
are pure reads and can be re-run safely.

```javascript
// 1.1 Pre-migration count — how many docs need migrating.
db.games.countDocuments({ optionAffinities: { $exists: false } })
// Record the value as N_GAMES_BEFORE.

// 1.2 Sanity: no doc should have BOTH numberOfOptions and optionAffinities
//     (would mean a previous half-run or manual edit).
db.games.countDocuments({
  optionAffinities: { $exists: true },
  numberOfOptions:  { $exists: true }
})
// Expect: 0. If non-zero, investigate before continuing.

// 1.3a Migration — phase A: synthesize optionAffinities from the legacy
//      bettingOptions list when it's present and non-empty. This preserves
//      explicit option-id sets (e.g. odd-only dice [1, 3, 5]) that would
//      otherwise be silently re-mapped to [0, 1, 2] by the numberOfOptions
//      range fallback. Run this BEFORE the numberOfOptions fallback below so
//      the explicit list wins when both legacy fields are present.
db.games.updateMany(
  {
    optionAffinities: { $exists: false },
    bettingOptions: { $exists: true, $not: { $size: 0 } }
  },
  [
    {
      $set: {
        optionAffinities: {
          $arrayToObject: {
            $map: {
              input: "$bettingOptions",
              as: "opt",
              in: { k: { $toString: "$$opt" }, v: 1 }
            }
          }
        }
      }
    },
    { $unset: ["numberOfOptions", "bettingOptions"] }
  ]
)
// Expect: matchedCount == (count of docs with legacy bettingOptions and no
//         optionAffinities), modifiedCount equal.

// 1.3b Migration — phase B: synthesize {0:1, 1:1, ..., n-1:1} from
//      numberOfOptions for any remaining docs (no bettingOptions list, only
//      a count), then unset the legacy fields.
db.games.updateMany(
  { optionAffinities: { $exists: false }, numberOfOptions: { $gt: 0 } },
  [
    {
      $set: {
        optionAffinities: {
          $arrayToObject: {
            $map: {
              input: { $range: [0, "$numberOfOptions"] },
              as: "i",
              in: { k: { $toString: "$$i" }, v: 1 }
            }
          }
        }
      }
    },
    { $unset: ["numberOfOptions", "bettingOptions"] }
  ]
)
// Expect: matchedCount + modifiedCount of 1.3a + 1.3b == N_GAMES_BEFORE.
```

### Step 1 — Verification

All three counts MUST return `0`. If any is non-zero, **stop and
investigate** — do not re-run the migration blindly (see Rollback below).

```javascript
// 1.4 No game should be missing optionAffinities after migration.
db.games.countDocuments({ optionAffinities: { $exists: false } })
// Expect: 0

// 1.5 No game should still carry the legacy numberOfOptions field.
db.games.countDocuments({ numberOfOptions: { $exists: true } })
// Expect: 0

// 1.6 No game should still carry the legacy bettingOptions field.
db.games.countDocuments({ bettingOptions: { $exists: true } })
// Expect: 0

// 1.7 Spot-check one document by hand.
db.games.findOne({}, { name: 1, optionAffinities: 1 })
// Expect: optionAffinities is a non-empty object with integer-string keys
//         (e.g. {"0":1, "1":1, "2":1, "3":1}) and value 1 for every key.
```

---

## Step 2 — `botGroups` collection migration

```javascript
// 2.1 Pre-migration count.
db.botGroups.countDocuments({ strategyMix: { $exists: false } })
// Record the value as M_GROUPS_BEFORE.

// 2.2 Migration: default any document missing strategyMix to a single
//     RANDOM entry with weight 1.0.
db.botGroups.updateMany(
  { strategyMix: { $exists: false } },
  { $set: { strategyMix: [ { strategyId: "RANDOM", weight: 1.0 } ] } }
)
// Expect: matchedCount == M_GROUPS_BEFORE, modifiedCount == M_GROUPS_BEFORE.
```

### Step 2 — Verification

```javascript
// 2.3 No bot group should be missing strategyMix after migration.
db.botGroups.countDocuments({ strategyMix: { $exists: false } })
// Expect: 0

// 2.4 Every bot group has a non-empty strategyMix.
db.botGroups.countDocuments({
  strategyMix: { $exists: true, $not: { $size: 0 } }
})
// Expect: db.botGroups.countDocuments({}) — i.e. the total count of
//         botGroups documents. Compare:
db.botGroups.countDocuments({})
// The two MUST be equal.

// 2.5 Spot-check one document.
db.botGroups.findOne({}, { name: 1, strategyMix: 1 })
// Expect: strategyMix is a non-empty array. For an unmigrated-then-migrated
//         doc it is exactly [{ strategyId: "RANDOM", weight: 1.0 }].
```

---

## Post-migration smoke

Run from any host that can reach the bot-manager API.

```bash
# 3.1 Health endpoint reports RANDOM per bot.
curl -fsS http://<host>:<port>/api/v1/bot-group/$GROUP_ID/health \
  | jq '.bots[0].strategyId'
# Expect: "RANDOM"

# 3.2 Read back a game — verify only the new shape on the wire.
#     NOTE: use the filter endpoint, not GET "/" (it doesn't exist).
curl -fsS -X POST http://<host>:<port>/api/v1/game/filter/ \
  -H 'Content-Type: application/json' -d '{}' \
  | jq '.[0] | keys'
# Expect: keys include "optionAffinities" and do NOT include
#         "numberOfOptions" or "bettingOptions".

# 3.3 PATCH-update a bot group strategyMix end-to-end.
#     NOTE: id is in the path; PATCH "/api/v1/bot-group" without id is 404.
curl -fsS -X PATCH http://<host>:<port>/api/v1/bot-group/$GROUP_ID \
  -H 'Content-Type: application/json' \
  -d '{"strategyMix":[{"strategyId":"RANDOM","weight":1.0}]}'
# Expect: HTTP 200

curl -fsS http://<host>:<port>/api/v1/bot-group/$GROUP_ID | jq '.strategyMix'
# Expect: [{"strategyId":"RANDOM","weight":1.0}]

# 3.4 Run-time behavior — bots are still placing bets.
curl -fsS http://<host>:<port>/actuator/prometheus \
  | grep -E '^bot_bets_placed_total' | head -3
# Expect: at least one line with a non-zero counter value for a
#         recently-active group.
```

---

## Rollback

The migration is **forward-only**. The code that ships in Phases 1–5 always
prefers the new shape over the legacy fallback, so once `optionAffinities`
or `strategyMix` is set on a document there is no way for the app to "forget"
them. There are two independent rollback scenarios:

### A. A verification count after Step 1 or Step 2 is non-zero

This means the `updateMany` did not migrate every document, or a document
ended up in a half-migrated state.

1. **Do NOT re-run the migration.** Re-running is technically idempotent
   on the `$exists` guard, but a non-zero residual means something is off
   (e.g. a stray document with `optionAffinities: null` rather than missing,
   or a concurrent writer). Diagnose first.
2. Dump a representative bad document for inspection:
   ```javascript
   db.games.findOne({ optionAffinities: { $exists: false } })
   db.botGroups.findOne({ strategyMix: { $exists: false } })
   ```
3. The application is safe to leave running. The read-side fallback in
   `Game.getEffectiveOptionAffinities()` continues to synthesize from
   `numberOfOptions`, and `BotGroupBehaviorService.effectiveStrategyMix()`
   continues to default to `[(RANDOM, 1.0)]` for `null` / empty
   `strategyMix`.

### B. The new image must be rolled back

If the Phase 1–5 deploy itself needs to be reverted (regardless of whether
the migration ran), redeploy the prior image. The previous image:

- Reads `numberOfOptions` directly on `Game`. Migrated docs no longer carry
  that field, so the previous image will see `numberOfOptions == 0` and
  `BettingMiniGameBot.resolveNextEntryToBet()` will fail with an empty
  betting-options list.
- Does not read `strategyMix` at all, so a migrated `strategyMix` field on
  `botGroups` is harmless to the previous image.

If the migration has already run, restoring the previous image requires
restoring `numberOfOptions` on `games`. A reverse migration is:

```javascript
// REVERSE MIGRATION — only if rolling back the bot-manager image
// after Step 1 has already run. Reconstructs numberOfOptions from
// optionAffinities key count.
db.games.updateMany(
  { optionAffinities: { $exists: true }, numberOfOptions: { $exists: false } },
  [
    {
      $set: {
        numberOfOptions: {
          $size: { $objectToArray: "$optionAffinities" }
        }
      }
    }
  ]
)
// This does NOT restore the legacy `bettingOptions` list. If the previous
// image relies on `bettingOptions` for any read path other than
// `Game.getEffectiveBettingOptions()` (which fell back to a [0..n-1]
// range), a fresh restore from a pre-migration Mongo backup is the
// safer path.
```

Prefer restoring from a pre-migration Mongo backup over running the
reverse migration unless the rollback window is tight.

---

## Operator quick-reference

Copy-paste block for an operator who has read the rest of this document
and wants a single sequence to run:

```javascript
// === bot-manager Phase 6 migration ===
// Pre-deploy: deploy the new bot-manager image first.
// Pre-flight: verify /actuator/health is UP before proceeding.

// --- games ---
print("games to migrate: " + db.games.countDocuments({ optionAffinities: { $exists: false } }));

// Phase A: preserve explicit bettingOptions list (odd-only dice etc.).
db.games.updateMany(
  {
    optionAffinities: { $exists: false },
    bettingOptions: { $exists: true, $not: { $size: 0 } }
  },
  [
    {
      $set: {
        optionAffinities: {
          $arrayToObject: {
            $map: {
              input: "$bettingOptions",
              as: "opt",
              in: { k: { $toString: "$$opt" }, v: 1 }
            }
          }
        }
      }
    },
    { $unset: ["numberOfOptions", "bettingOptions"] }
  ]
);

// Phase B: fall back to numberOfOptions range for the rest.
db.games.updateMany(
  { optionAffinities: { $exists: false }, numberOfOptions: { $gt: 0 } },
  [
    {
      $set: {
        optionAffinities: {
          $arrayToObject: {
            $map: {
              input: { $range: [0, "$numberOfOptions"] },
              as: "i",
              in: { k: { $toString: "$$i" }, v: 1 }
            }
          }
        }
      }
    },
    { $unset: ["numberOfOptions", "bettingOptions"] }
  ]
);

print("games still missing optionAffinities (expect 0): " + db.games.countDocuments({ optionAffinities: { $exists: false } }));
print("games still with numberOfOptions  (expect 0): " + db.games.countDocuments({ numberOfOptions:  { $exists: true } }));
print("games still with bettingOptions   (expect 0): " + db.games.countDocuments({ bettingOptions:   { $exists: true } }));

// --- botGroups ---
print("botGroups to migrate: " + db.botGroups.countDocuments({ strategyMix: { $exists: false } }));

db.botGroups.updateMany(
  { strategyMix: { $exists: false } },
  { $set: { strategyMix: [ { strategyId: "RANDOM", weight: 1.0 } ] } }
);

print("botGroups still missing strategyMix (expect 0): " + db.botGroups.countDocuments({ strategyMix: { $exists: false } }));
print("botGroups total (sanity): " + db.botGroups.countDocuments({}));
```

Three `print` lines should all read `0`; the last reports the total bot
group count for sanity. After the block runs cleanly, complete the
post-migration smoke from §"Post-migration smoke" above and the release
is done.
