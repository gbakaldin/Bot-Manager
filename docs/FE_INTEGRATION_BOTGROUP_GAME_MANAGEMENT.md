# Frontend Integration Guide — Bot Group & Game Management

**Feature:** `BOTGROUP_GAME_MANAGEMENT`
**Status:** Deployed to **Bot-1 staging** (2026-07-06). Not yet on production.
**Audience:** Frontend team integrating the Bot Manager admin UI.
**Base URLs:** all paths below are relative to the bot-manager host, prefixed `/api/v1`.

> ⚠️ **This release contains BREAKING changes.** Several Game and BotGroup endpoints changed
> shape (environment moved into the URL path), one endpoint was **removed**, and create now
> enforces a new validation. Read §1 first, then the per-resource sections.

---

## 1. TL;DR — what changed

| # | Change | Type | Section |
|---|--------|------|---------|
| 1 | **Games are now scoped to an Environment.** Game list/create/filter take an `{envId}` path segment. | 🔴 Breaking | §3 |
| 2 | **BotGroup filter is now env-in-path:** `POST /bot-group/{envId}/filter`. The old body `environmentId` is gone. | 🔴 Breaking | §4 |
| 3 | **`GET /bot-group/` (list-all) was REMOVED.** Use the env-scoped filter instead. | 🔴 Breaking | §4 |
| 4 | **BotGroup create now validates that the chosen game belongs to the group's environment** → `400` on mismatch. | 🔴 Breaking | §4.4 |
| 5 | **Sorting** added to both filters via `sortBy` + `sortDir`; two new sort-key lookup endpoints. | 🟢 Additive | §5 |
| 6 | **BotGroup statistics** (`stats` object) now returned on group detail / health / filter items. | 🟢 Additive | §6 |
| 7 | **New read-only fields on Game:** `environmentId`, `createdAt`, `updatedAt`. | 🟢 Additive | §3.5 |
| 8 | **Deleting a Game or Environment now CASCADES** (stops + deletes referencing bot groups). | 🟠 Behavior | §7 |

**Minimum FE work to not break:** update the Game list/create/filter calls to include `envId` (§3),
switch the BotGroup filter call to `POST /bot-group/{envId}/filter` and drop `environmentId` from the
body (§4), and remove any use of `GET /bot-group/` (§4).

---

## 2. Concept: the Environment axis

An **Environment** (e.g. `"114 Staging"`, `"097 Staging"`) is the deployment target a game/bot group
runs against. It already exists as a resource (`/api/v1/environment`) and BotGroups already carried an
`environmentId`. This release extends the **same scoping to Games**, because a game can exist in one
environment but not another (e.g. ready in staging, not in prod).

Practical consequence for the UI: **the user must pick an environment before browsing or creating
games and bot groups.** Every Game and BotGroup listing is now "within an environment". The
environment id (`envId`) is the Environment's `id` (a UUID string), obtained from
`GET /api/v1/environment`.

> The same logical game in two environments is now **two separate Game records**, each with its own
> `id` and `environmentId`. Do not assume a single game id spans environments.

---

## 3. Game API changes

Base path: `/api/v1/game`

### 3.1 Endpoint map (old → new)

| Operation | OLD | NEW |
|-----------|-----|-----|
| List games | `GET /game/{brandCode}/{productCode}` | `GET /game/{brandCode}/{productCode}/{envId}` |
| Filter games | `POST /game/filter/` (body had brand/product) | `POST /game/{brandCode}/{productCode}/{envId}/filter` |
| Create game | `POST /game/{brandCode}/{productCode}` | `POST /game/{brandCode}/{productCode}/{envId}` |
| Get by id | `GET /game/{id}` | *(unchanged)* |
| Update | `PATCH /game/{id}` | *(unchanged)* |
| Delete | `DELETE /game/{id}` | *(unchanged path — now cascades, see §7)* |
| Game types | `GET /game/types` | *(unchanged)* |
| **Sort keys** | — | `GET /game/sort-keys` *(new, see §5)* |

`brandCode` ∈ `{G2, G3, G4, ...}`, `productCode` ∈ `{P_097, P_116, ...}` (unchanged enums;
`GET /api/v1/brand/products` still returns the brand→products map). `envId` is an Environment UUID.

### 3.2 List games — `GET /game/{brandCode}/{productCode}/{envId}`

Returns all games for that brand+product **in that environment**.

```
GET /api/v1/game/G3/P_116/394301f4-1a2b-4c3d-9e8f-651954243fdd
→ 200 OK
[ { GameDTO }, { GameDTO }, ... ]
```

### 3.3 Filter games — `POST /game/{brandCode}/{productCode}/{envId}/filter`

Body is now **`GameFilter`** (brand/product/env removed — they're in the path):

```jsonc
// GameFilter
{
  "gameType": "BETTING_MINI",   // optional; enum, exact match
  "name":     "Bau",            // optional; case-insensitive SUBSTRING match
  "sortBy":   "BOT_COUNT",      // optional; see §5. null/blank → CREATED_TIME
  "sortDir":  "desc"            // optional; asc|desc (case-insensitive). null/blank/unknown → desc
}
```

Empty body `{}` returns **all games in that brand/product/env**, sorted by `CREATED_TIME desc`.

```
POST /api/v1/game/G3/P_116/394301f4-.../filter
Body: { "sortBy": "BOT_GROUP_COUNT", "sortDir": "desc" }
→ 200 OK  [ { GameDTO }, ... ]
```

### 3.4 Create game — `POST /game/{brandCode}/{productCode}/{envId}`

Body is `GameDTO` **as before**, EXCEPT `brandCode`, `productCode`, and `environmentId` are now taken
from the **path** and ignored/overwritten if sent in the body. `id`, `createdAt`, `updatedAt`,
`environmentId` are server-assigned — do not send them.

```
POST /api/v1/game/G3/P_116/394301f4-...
Body: { "name": "Bau Cua", "gameType": "BETTING_MINI", "pluginName": "...", "offset": 2000, "md5": true, "numberOfOptions": 6 }
→ 200 OK  { GameDTO with id + environmentId + createdAt + updatedAt }
```

### 3.5 `GameDTO` — new read-only fields

Three fields were added (all **read-only** — returned on responses, ignored on create/update bodies):

| Field | Type | Notes |
|-------|------|-------|
| `environmentId` | `string` (UUID) | The environment this game belongs to. |
| `createdAt` | `string` (ISO-8601 `Instant`, e.g. `2026-07-06T09:00:00Z`) | First-persist timestamp. |
| `updatedAt` | `string` (ISO-8601 `Instant`) | Last-persist timestamp. |

Existing `GameDTO` fields are unchanged (`id`, `brandCode`, `productCode`, `name`, `description`,
`gameType`, `pluginName`, `gameId`, `offset`, `md5`, `optionAffinities`, `numberOfOptions`). Response
serialization omits `null` fields (`@JsonInclude(NON_NULL)`).

---

## 4. BotGroup API changes

Base path: `/api/v1/bot-group`

### 4.1 Endpoint map (old → new)

| Operation | OLD | NEW |
|-----------|-----|-----|
| **Filter groups** | `POST /bot-group/filter/` (body had `environmentId`) | `POST /bot-group/{envId}/filter` |
| **List all groups** | `GET /bot-group/` | 🔴 **REMOVED** — use the env-scoped filter |
| Get by id | `GET /bot-group/{id}` | *(unchanged — now includes `stats`, §6)* |
| Create | `POST /bot-group/` | *(unchanged path — now validates game∈env, §4.4)* |
| Update | `PATCH /bot-group/{id}` | *(unchanged)* |
| Delete | `DELETE /bot-group/{id}` | *(unchanged path — now stops+logs-out bots first, §7)* |
| Start/Stop/Restart | `POST /bot-group/{id}/{start\|stop\|restart}` | *(unchanged)* |
| Schedule restart | `POST /bot-group/{id}/schedule-restart` | *(unchanged)* |
| Health | `GET /bot-group/{id}/health` | *(unchanged — now includes `stats`, §6)* |
| Status | `GET /bot-group/{id}/status` | *(unchanged)* |
| **Sort keys** | — | `GET /bot-group/sort-keys` *(new, §5)* |

### 4.2 Filter groups — `POST /bot-group/{envId}/filter`

Environment moves to the path (mandatory). Body is now **`BotGroupFilter`** without `environmentId`:

```jsonc
// BotGroupFilter
{
  "name":    "RIK114",       // optional; case-insensitive WHOLE-STRING match (not substring)
  "gameId":  "3cda38f9-...", // optional; the Game's id (UUID), exact match
  "sortBy":  "BALANCE",      // optional; see §5. null/blank → CREATED_TIME
  "sortDir": "desc"          // optional; asc|desc. null/blank/unknown → desc
}
```

Empty body `{}` returns **every group in that environment**, sorted `CREATED_TIME desc`.

```
POST /api/v1/bot-group/394301f4-.../filter
Body: { "sortBy": "ACTIVE_BOTS", "sortDir": "desc" }
→ 200 OK  [ { BotGroupDTO with stats }, ... ]
```

> **`name` match differs by resource:** BotGroup `name` is whole-string (case-insensitive); Game
> `name` is substring. (Known inconsistency, tracked as tech debt — don't rely on BotGroup name being
> a substring search.)

### 4.3 Removed: `GET /bot-group/`

The unscoped "list all bot groups across all environments" endpoint is **gone** (404/no route). To
list groups, always go through an environment: `POST /bot-group/{envId}/filter` with `{}`.

If your UI has a global "all groups" view, build it by iterating the environments
(`GET /api/v1/environment`) and calling the filter per environment — this matches the intended
env-first navigation.

### 4.4 Create now validates game ∈ environment

`POST /bot-group/` is unchanged in shape, but the server now checks that the referenced game
(`gameId`, which is the Game's `id`) belongs to the group's `environmentId`. On mismatch it returns
**HTTP 400** (see §8) before creating anything. Guarded cases that do **not** 400: no `gameId` on the
group, or a legacy game whose `environmentId` is still null.

**FE action:** when the user picks a game for a new group, pick it from the **same environment** the
group is being created in (the env-scoped game list from §3.2 already gives you exactly that set).

---

## 5. Sorting (both resources)

Both filter endpoints accept `sortBy` + `sortDir` in the body.

- `sortDir`: `asc` | `desc`, case-insensitive. Anything null/blank/unrecognized → **`desc`** (lenient).
- `sortBy`: case-insensitive key name. null/blank → **`CREATED_TIME`**. An **unrecognized key → HTTP 400**.
- **N/A ordering:** runtime-only values that are unavailable (e.g. balance of a stopped group) always
  sort to the **bottom**, regardless of `sortDir`.

### 5.1 Sort-key lookup endpoints (build dropdowns from these)

Don't hardcode the key lists — fetch them so the UI never drifts from the server:

```
GET /api/v1/bot-group/sort-keys   → 200  ["STATUS","BOT_COUNT","CREATED_TIME","NAME","BET_AMOUNT","BALANCE","ACTIVE_BOTS","UPDATED_TIME","GAME_TYPE","AVG_WINNING","ACTIVE_TIME","MAX_PER_ROUND"]
GET /api/v1/game/sort-keys        → 200  ["CREATED_TIME","BOT_GROUP_COUNT","BOT_COUNT","GAME_TYPE","NAME","ACTIVE_GROUP_COUNT","ACTIVE_BOT_COUNT"]
```

Both return a plain `string[]` of enum names, in display order.

### 5.2 BotGroup sort keys — meaning

| Key | Sorts by | N/A when |
|-----|----------|----------|
| `STATUS` | Runtime status (ACTIVE/DEAD/…) | group not running |
| `BOT_COUNT` | Configured bot count | never |
| `CREATED_TIME` | Creation timestamp | never |
| `NAME` | Group name | never |
| `BET_AMOUNT` | Configured `maxBet` | never |
| `BALANCE` | Group-average balance over active bots | not running / no active bots |
| `ACTIVE_BOTS` | Live active-bot count | not running |
| `UPDATED_TIME` | Last-update timestamp | never |
| `GAME_TYPE` | Resolved game-type name | referenced game missing |
| `AVG_WINNING` | Group-average cumulative winnings over active bots | not running / no active bots |
| `ACTIVE_TIME` | Seconds since last start/restart | not running |
| `MAX_PER_ROUND` | Configured `maxTotalBetPerRound` | never |

*(No `ENVIRONMENT` key — the list is already env-scoped.)*

### 5.3 Game sort keys — meaning

| Key | Sorts by | N/A when |
|-----|----------|----------|
| `CREATED_TIME` | Creation timestamp | never |
| `BOT_GROUP_COUNT` | # bot groups referencing the game | never (0 if none) |
| `BOT_COUNT` | Σ configured bot count over referencing groups | never (0 if none) |
| `GAME_TYPE` | Game-type name | game has no gameType |
| `NAME` | Game name | never |
| `ACTIVE_GROUP_COUNT` | # currently-running referencing groups | game inactive (no running group) |
| `ACTIVE_BOT_COUNT` | Σ running bots over referencing groups | game inactive |

*(No `BRAND`/`PRODUCT` key — already scoped by the path.)*

---

## 6. BotGroup statistics (`stats`)

`BotGroupDTO` now carries a nested **`stats`** object (type `BotGroupStatsDTO`), populated on:
`GET /bot-group/{id}`, `GET /bot-group/{id}/health`, and each item of `POST /bot-group/{envId}/filter`.

```jsonc
// BotGroupDTO.stats  (BotGroupStatsDTO)
{
  "roundsSinceRestart": 1234,   // Long  — rounds observed since last start/restart (max across bots)
  "activeTimeSeconds":  3600,   // Long  — uptime since last start/restart, seconds
  "activeBots":         100,    // Integer — live connected bot count
  "averageBalance":     50000,  // Long  — mean balance over ACTIVE bots
  "averageWinning":     1200    // Long  — mean cumulative winnings over ACTIVE bots
}
```

**N/A semantics — important for the UI:** every field is nullable. A **stopped / not-running group
yields all-null** (`stats` present, all fields `null`). `averageBalance`/`averageWinning` are `null`
(never `0`) when the group has zero active bots. **Render `null` as "N/A"** — this maps directly to
the Statistics panel in the group detail screen (Rounds since last restart, Active time, Active bots,
Average balance, Average winning).

*(The existing `BotGroupHealthDTO` — per-bot connection/balance/bet detail — is unchanged; `stats` is
an added sibling on it.)*

---

## 7. Behavior change: cascading deletes

Deletes are now **safe/cascading** (no orphaned running bots or dangling references):

- **`DELETE /bot-group/{id}`** — first **stops** the group and **logs out** its bots (ends their
  server sessions cleanly), removes it from runtime management, then deletes the record. A running
  group can be deleted directly; the server handles the teardown.
- **`DELETE /game/{id}`** — cascades to every bot group referencing that game (each stopped → logged
  out → deleted) **before** the game is deleted.
- **`DELETE /environment/{id}`** — cascades the whole tree: all games in the env → all their bot
  groups (and any remaining groups in the env) → then the environment.

**FE implications:**
- These deletes can now tear down **many** running bots and are **not instantaneous**. Consider a
  confirmation dialog that states the blast radius (e.g. "This will stop and delete N bot groups").
- After a Game/Environment delete, **refresh any cached group/game lists** — referenced entities are
  gone.
- Registered bot accounts are **not** removed from the game server (there is no such API) — that's
  expected; only the bot-manager records + live sessions are torn down.

---

## 8. Error responses

Error body shape (from the global exception handler) — unchanged format, listed for completeness.
4xx errors return an **`ErrorResponse`** envelope: `{ "type": string, "msg": string }` (the HTTP
status is on the response line, not in the body).

```jsonc
// HTTP 400 — e.g. unknown sort key, or game∉environment on create
{ "type": "BadRequestException", "msg": "Unknown bot-group sort key 'foo'. Valid keys: STATUS, BOT_COUNT, ..." }
```

> **404 is a special case:** `GET /{id}` on a missing resource returns **HTTP 404 with an EMPTY body**
> (no `ErrorResponse` envelope). Don't try to parse a body on 404.

New/changed 400 triggers introduced by this release:
- Unknown `sortBy` on either filter (unknown `sortDir` does **not** 400 — it defaults to `desc`).
- Creating a bot group whose `gameId` game belongs to a **different** environment (§4.4).

---

## 9. FE integration checklist

- [ ] Add an **environment selector** as a prerequisite for the Games and Bot Groups views.
- [ ] Game list → `GET /game/{brand}/{product}/{envId}`.
- [ ] Game filter → `POST /game/{brand}/{product}/{envId}/filter`; remove `brandCode`/`productCode` from the body.
- [ ] Game create → `POST /game/{brand}/{product}/{envId}`; stop sending brand/product/env in the body.
- [ ] Show new Game fields `environmentId` / `createdAt` / `updatedAt` where useful.
- [ ] BotGroup filter → `POST /bot-group/{envId}/filter`; **remove `environmentId` from the body**.
- [ ] Remove all usage of `GET /bot-group/` (list-all); rebuild any global view by iterating environments.
- [ ] On new-group creation, restrict the game picker to the group's environment (avoids the §4.4 400).
- [ ] Populate the group **Statistics** panel from `stats` (render `null` → "N/A").
- [ ] Add **sort controls**: fetch `/{resource}/sort-keys`, send `sortBy`/`sortDir` in the filter body.
- [ ] Update delete confirmations to reflect **cascading** behavior; refresh lists after Game/Env delete.
- [ ] Handle **400** on unknown sort key and on game∉env create.

---

## 10. Quick reference — all changed/new endpoints

```
# Game (base /api/v1/game)
GET    /{brandCode}/{productCode}/{envId}                 # CHANGED: +envId
POST   /{brandCode}/{productCode}/{envId}/filter          # CHANGED: +envId, body=GameFilter{gameType,name,sortBy,sortDir}
POST   /{brandCode}/{productCode}/{envId}                 # CHANGED: +envId (create)
GET    /sort-keys                                         # NEW → string[]
DELETE /{id}                                              # same path, now CASCADES

# BotGroup (base /api/v1/bot-group)
POST   /{envId}/filter                                    # CHANGED: env in path, body=BotGroupFilter{name,gameId,sortBy,sortDir}
GET    /                                                  # REMOVED
GET    /sort-keys                                         # NEW → string[]
GET    /{id}                                              # same, response now includes stats
GET    /{id}/health                                       # same, response now includes stats
POST   /                                                  # same, now validates game∈env → 400
DELETE /{id}                                              # same path, now stops+logs-out then deletes
```

---

## 11. Timed activation (recurring time-of-day windows)

**Feature:** `TIMED_ACTIVATION`. A bot group can now run on a **recurring time-of-day
schedule** instead of being manually started/stopped. A backend reconciler evaluates the
window every minute and drives the existing start/stop lifecycle so the group is up only
while its window is open.

**No new endpoints.** Activation is set through the existing `POST /bot-group/` (create) and
`PATCH /bot-group/{id}` (update); `start`/`stop`/`restart` are unchanged. Two new
`BotGroupDTO` fields carry the contract:

### 11.1 New `BotGroupDTO` fields

| Field | Type | Notes |
|-------|------|-------|
| `activationMode` | enum `SCHEDULED` \| `MANUAL_ON` \| `MANUAL_OFF`, or omitted | `SCHEDULED` = follow the window. `MANUAL_ON` = always up. `MANUAL_OFF` = parked/down. **Omit** (null) for a legacy non-timed group — behaves exactly as today. |
| `activationWindow` | object `{ from, to, days }` or omitted | Required when `activationMode == SCHEDULED`. |

`activationWindow` shape:

```jsonc
// activationWindow
{
  "from": "18:00:00",              // "HH:mm:ss" LocalTime — window opens (required when SCHEDULED)
  "to":   "00:00:00",              // "HH:mm:ss" LocalTime — window closes (required when SCHEDULED)
  "days": ["FRIDAY", "SATURDAY"]   // array of DayOfWeek enum names; [] or omitted = all seven days
}
```

- `days` values are `java.time.DayOfWeek` names, uppercase: `MONDAY`…`SUNDAY`.
- Windows may cross midnight (`from > to`, e.g. `"22:00:00"`→`"02:00:00"`). For a
  midnight-crossing window the `days` set is anchored to the day the window **opened**
  (a Fri 22:00–02:00 window that is `days:["FRIDAY"]` is still active Sat 01:30).
- Times are interpreted in a single backend-configured business timezone
  (`Asia/Ho_Chi_Minh`), not the browser's — the FE just sends wall-clock `HH:mm:ss`.

### 11.2 Two gotchas

1. **"Until midnight" is `"to": "00:00:00"`, not `"24:00:00"`.** `24:00:00` is not a valid
   `LocalTime` and will be rejected. A window that runs to end-of-day uses `to: "00:00:00"`.
2. **`from == to` is rejected with HTTP 400** (ambiguous zero-length vs all-day). For an
   always-up group use `activationMode: "MANUAL_ON"` with **no** window — do not send a
   `00:00:00`→`00:00:00` window.

Also: `SCHEDULED` with no `activationWindow` (or a window missing `from`/`to`) → **400**.

### 11.3 Manual override

Hitting `POST /bot-group/{id}/stop` or `.../start` on a `SCHEDULED` group **parks** it:
the mode flips to `MANUAL_OFF` (stop) / `MANUAL_ON` (start) so the reconciler stops
managing it. To hand it back to the schedule, `PATCH` `activationMode: "SCHEDULED"`.
Groups with `activationMode == null` (legacy) are untouched by this — their start/stop
behave as before.

### 11.4 FE action

- Add **activation mode** + **window** (`from`/`to` time pickers, `days` multi-select) inputs
  to the bot-group create/edit form; send them as `activationMode` / `activationWindow`.
- Use `to: "00:00:00"` for "until midnight"; block `from == to` client-side (or surface the
  400) and steer "always on" users to `MANUAL_ON`.
- **Remove the removed fields `timeBased` / `timeFrom` / `timeUntil` from the form** — they
  no longer exist on `BotGroupDTO` (dropped by this release; the server ignores them).

---

*Questions on the contract: see `docs/plans/BOTGROUP_GAME_MANAGEMENT.md` and
`docs/plans/TIMED_ACTIVATION.md` (design + Architecture Decisions), and the OpenAPI/Swagger UI
at `/swagger-ui.html` on the running instance.*
