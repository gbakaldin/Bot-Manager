# Bet Coordination — Group-Scoped Coordinator (Internal Tier)

## Goal

Build a central, in-memory, **group-scoped bet coordinator** that acts as the
source of truth over a running group's in-flight round. Each bot's strategy
proposes a bet `(optionId, amount)`; the coordinator returns
**APPROVE / TRIM(smaller amount) / REJECT** so the fleet's *realized aggregate*
betting stays near a target distribution over the game's N options and under an
aggregate per-round stake cap. The coordinator steers **by suppression / trim
only — it never manufactures bets**. This is the "internal tier": it coordinates
a group's OWN bots against a target derived from the game's existing
`optionAffinities`; it does NOT read real players' betting (that crowd-aware tier
is deferred behind a separate feed spike, out of scope here). The decision/
reservation core is written scope-agnostic so a future **Fleet** can own the
coordinator instead of a single group, mirroring the TIMED_ACTIVATION reuse seam.

This is work package #4 ("Coordination gate — internal tier") of
`docs/plans/BETTING_INTELLIGENCE_ROADMAP.md`.

## Findings — Current State

### The bet path (the seam we insert into)
- `BettingMiniGameBot.betCondition()`
  (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/core/BettingMiniGameBot.java:660`)
  is the `sendAsync` condition. It gates on `canBet()` (session live, BET phase,
  time remaining), calls `decideBet(buildBetContext())`, and if a decision is
  present **parks it** in `pendingDecision` (`:116`) and returns `true`.
- `bet()` (`:592`) is the `sendAsync` supplier. It pops `pendingDecision`,
  calls `creditBalance(amount)`, records to `memory`/`sessionAggregator`, and
  builds the WS message via `request.bet(amount, optionId, currentSid)` (`:633`).
- `decideBet(BetContext)` (`:567`) is an already-existing protected seam:
  default returns `strategy.decide(ctx)` verbatim; `TaiXiuGameBot` overrides it
  to enforce the single-entry-per-round lock (constrains the *entry*, preserves
  the *amount*). **The coordinator must run after `decideBet` so it sees the
  final, TaiXiu-remapped option.**
- `BetDecision` (`.../strategy/BetDecision.java`) is an immutable
  `record(int optionId, long amount)` — trim = a new record with a smaller amount.
- `betSkipPercentage` gate lives inside the strategies
  (`RandomBehaviorStrategy.decide:82`, `MartingaleStrategySupport.decide:160`),
  read from `BotBehaviorConfig.betSkipPercentage`.

### Round lifecycle
- `onStartGame` (`:370`) sets `sidStore`, calls `memory.beginRound(sid, balance)`
  (`BotMemory.beginRound`, `.../strategy/BotMemory.java:130`), transitions to
  `BET`. This runs on the netty message-processor thread, **once per bot per
  round** — every bot in the group sees its own `onStartGame` for the same `sid`.
- `onEndGame` (`:419`) finalizes the round (`memory.completeRound`), increments
  `roundsObserved`, transitions to `PAYOUT`.
- `RoundState` (`.../strategy/RoundState.java`) holds `sessionId` (sentinel `0`),
  `volatile` primitive fields, and a per-option `betsByOption` map.

### Target distribution source (locked)
- `Game.getEffectiveOptionAffinities()`
  (`.../domain/game/model/Game.java:203`) → `Map<Integer,Integer>` option id →
  weight. Its `keySet()` is the authoritative N-option set (BauCua N=6, TaiXiu
  N=2 via `defaultTaiXiuOptionAffinities`). Today `RandomBehaviorStrategy` picks
  **uniformly** over `keySet()` and ignores the weights (`:100`); the coordinator
  is the first consumer to enforce the weights, at the aggregate.

### Config surfaces
- `BotGroup` (`.../botgroup/model/BotGroup.java`) already carries `minBet`,
  `maxBet`, `betIncrement`, and a **per-bot** `maxTotalBetPerRound:40` (today
  validation-only — `BettingGridRules`; not enforced in the runtime bet path).
  The new `maxAggregateStakePerRound` is a **group/fleet-level** cap, distinct
  from that per-bot field.
- `BotGroupDTO` (`.../botgroup/dto/BotGroupDTO.java`) + `BotGroupMapper`
  (`.../botgroup/mapper/BotGroupMapper.java`, hand-written `toDTO`/`toEntity`/
  `updateEntityFromDTO`) are the write surface. `BotBehaviorConfig` +
  `BotConfiguration` are the per-bot immutable snapshots built in
  `BotGroupBehaviorService.createSingleBot:485`.
- **`betSkipPercentage` is never set in `createSingleBot` (`:499-508`)** — it
  defaults to `0`. So in production bots already propose on **every** eligible
  tick up to `maxBetsPerRound`. Design point 1 is therefore already satisfied by
  the status quo; we only need to *pin* the invariant (see AD-7).

### Runtime & injection precedent
- `BotGroupRuntime` (`.../infrastructure/runtime/BotGroupRuntime.java`) is the
  one-per-running-group holder, created in `BotGroupBehaviorService.start:273`
  *before* `createBotsInParallel:281` and the `startBot` loop `:284`.
- `SessionAggregationService` is the injection precedent: an app-scoped
  collaborator set on every bot via `Bot.setSessionAggregator(...)` inside
  `BotFactory.createBot` (`.../bot/service/BotFactory.java:188`), null-tolerant on
  the bot side. Our coordinator differs: it is **group-scoped** (one instance per
  running group), so it is created in `start()`, stored on the runtime, and
  injected into each bot in the `startBot` loop (before `start()` runs).

### Health read surface
- `BotGroupHealthDTO` (`.../botgroup/dto/BotGroupHealthDTO.java`) is built in
  `BotGroupBehaviorService.getHealth:755`, which already reads live runtime state
  (`BotStatsDTO` via `computeStats:827`). Adding a nullable coordination block
  there is additive and mirrors `stats`.

### Reuse-seam precedent
- TIMED_ACTIVATION AD-11 (`docs/plans/TIMED_ACTIVATION.md:209`) splits logic into
  pure functions + a thin Spring shell so Fleet reuses the core verbatim. We
  mirror that: `BetCoordinator` is a plain domain object constructed from
  `(affinities, aggregateCap, minBet, betIncrement)` — no Spring, no `BotGroup`
  dependency — so a Fleet builds one the same way from fleet-level fields.

## Per-aspect readiness / mapping

| Aspect | Readiness | Notes |
|---|---|---|
| Target distribution source | ready | `Game.getEffectiveOptionAffinities()` reused as-is; no new per-option field. |
| Write-side config (2 fields) | ready | Add `coordinationEnabled`, `maxAggregateStakePerRound` to entity/DTO/mapper (Phase 1). |
| Bot↔coordinator seam | ready | Insert after `decideBet` in `betCondition()`; composes with TaiXiu lock (AD-2). |
| Concurrency / reservation | ready | Single `ReentrantLock` + per-round budget snapshot; VT-safe (AD-3). |
| Round lifecycle hook | ready | First-seen guard on `onStartGame` `sid`; reset per round (AD-4). |
| Over-proposal / headroom | ready | `betSkipPercentage` already `0`; pin to `0` when coordination on (AD-7). |
| Read-side observability | ready | Nullable `coordination` block on `BotGroupHealthDTO` (Phase 4). |
| Fleet scope-agnosticism | ready | `BetCoordinator` is Spring-free, group-agnostic (AD-8). |
| Crowd-aware tier | out of scope | Deferred behind feed spike (roadmap #5/#6). |

## Architecture Decisions

**AD-1 — Two write-side fields, nothing else.** Add exactly `coordinationEnabled`
(boolean) and `maxAggregateStakePerRound` (long) to `BotGroup`, `BotGroupDTO`,
and all three `BotGroupMapper` methods. The target distribution is **not** a new
field — it is `Game.getEffectiveOptionAffinities()`.

**AD-2 — Seam location: after `decideBet`, inside `betCondition()`.** Insert a
new protected seam `applyCoordination(BetContext ctx, BetDecision proposed)` that
returns `Optional<BetDecision>` (present = APPROVE/TRIM with possibly-reduced
amount; empty = REJECT → skip tick). `betCondition()` calls it immediately after
`decideBet(ctx)` returns a non-empty decision, and parks the *result*:
```
Optional<BetDecision> decision = decideBet(ctx);
if (decision.isEmpty()) return false;
Optional<BetDecision> gated = applyCoordination(ctx, decision.get());
if (gated.isEmpty()) return false;      // REJECT → skip this tick
pendingDecision.set(gated);             // APPROVE or TRIM
return true;
```
Default `applyCoordination` is **identity when `coordinator == null`** (coordination
off → byte-for-byte current behavior). Running after `decideBet` means the option
is already TaiXiu-remapped, so the coordinator gates the final entry; TaiXiu's
single-entry lock and the coordinator compose without either knowing about the
other. `bet()` (the supplier) is **unchanged** — it just sends the parked
decision. The `bet()` race-fallback re-derive path (`:602`, effectively
unreachable — only fires if `beforeReconnect` cleared the park mid-tick) does
**not** re-run coordination, to avoid double-reserving; documented as a benign
edge (a reservation already committed in `betCondition` is simply not sent, and
resets next round).

**AD-3 — Concurrency: one `ReentrantLock` guarding a per-round budget snapshot.**
The coordinator holds a `volatile RoundBudget current` (immutable per-round
targets + mutable committed totals) and a single `ReentrantLock`. `reserve(...)`
acquires the lock, does O(N=options → actually O(1) per call) arithmetic, updates
`committed[option]` and `committedAggregate`, releases. Justification: the
critical section is a handful of `long` comparisons/additions held for
nanoseconds; even at hundreds of bots proposing within a multi-second bet window
(each ≤1 proposal/sec via `resolveIntervalBetweenBets:680`), lock contention is
negligible. `ReentrantLock` (unlike `synchronized`) does **not** pin virtual
threads, so it is safe on the VT-per-bot model. We deliberately avoid a lock-free
two-resource (per-option budget + aggregate cap) CAS scheme — reserving two
bounded resources atomically needs rollback logic that buys nothing at this scale.

**AD-4 — Round lifecycle: first-seen `beginRound`, reset per `sid`.** The
coordinator exposes `onRound(long sessionId)`, called from `onStartGame` after
`memory.beginRound`. It is idempotent across the group's bots: under the lock, if
`sessionId != current.sessionId`, it swaps in a fresh `RoundBudget` computed from
the affinities + cap; otherwise it is a no-op (the other N−1 bots that see the
same `sid`). `reserve(sessionId, ...)` rejects any proposal whose `sessionId`
does not match `current` (stale/straddling tick). No explicit end hook is
required for correctness (the next `onRound` swaps state), but `onEndGame` calls
`onRoundComplete(sessionId)` to (a) snapshot the finished round for observability
and (b) emit the one-per-round DEBUG summary (AD-6).

**AD-5 — Per-option budget + trim/reject policy.** For a round with cap
`C = maxAggregateStakePerRound`, affinity weights `w(o)` and `W = Σ w(o)`:
- per-option budget `B(o) = floor(w(o) / W * C)` (long math; rounding slack
  falls under the aggregate cap, which remains the hard total ceiling).
- On `reserve(sid, o, amount)`:
  1. `remainingOption = B(o) - committed(o)`; `remainingAgg = C - committedAggregate`.
  2. `allow = min(amount, remainingOption, remainingAgg)`.
  3. **Grid-align down** to the group's bet grid:
     `aligned = minBet + floor((allow - minBet) / betIncrement) * betIncrement`
     when `allow >= minBet`, else `aligned = 0`.
  4. If `aligned <= 0` (or `aligned < minBet`) → **REJECT** (commit nothing).
  5. Else commit `aligned` to `committed(o)` and `committedAggregate`, and return
     **APPROVE** if `aligned == amount`, else **TRIM(aligned)**.
  `minBet`/`betIncrement` are group-uniform and stored on the coordinator at
  construction, so a trimmed amount is always a valid, server-acceptable grid
  value ≥ `minBet`.

**AD-6 — Observability & logging.** Add a nullable `CoordinationStateDTO
coordination` to `BotGroupHealthDTO`, populated in `getHealth` from
`runtime.getCoordinator()` (null when coordination off or group not running):
per-option `{optionId, targetWeight, targetBudget, committedStake,
realizedFraction}`, `currentAggregateStake`, `maxAggregateStakePerRound`, and
cumulative `approveCount`/`trimCount`/`rejectCount` (AtomicLongs since restart,
mirroring `roundsObserved`). Logging respects CLAUDE.md: the coordinator emits
**one DEBUG line per group per round** in `onRoundComplete` — realized-vs-target
histogram + trim/reject/approve counts (group-level aggregate, ~1 line/round).
Per-proposal decisions are **TRACE only** (opt-in), never DEBUG. No INFO
additions (the roadmap's session summaries already own the INFO per-round budget).

**AD-7 — Over-proposal / headroom.** Trim-only steering needs bots to propose ≥
the target on each option; the coordinator can suppress but never create. In
production `betSkipPercentage` is already `0` (never set in `createSingleBot`),
so bots already propose every eligible tick up to `maxBetsPerRound` — maximal
headroom. To make this robust against a future operator raising the field, when
`coordinationEnabled` is true `createSingleBot` **forces `betSkipPercentage = 0`**
in the bot's `BotBehaviorConfig` (per-bot skip is redundant under coordination —
the coordinator is the throttle). `maxBetsPerRound` stays the per-bot proposal
ceiling. **Consequence (documented honestly):** the coordinator guarantees the
*ceiling* (no option exceeds its budget, aggregate ≤ cap) but not the *floor* —
if bots collectively under-propose a high-affinity option (e.g. RANDOM picks
uniformly while affinity concentrates budget on one option), that option stays
under-filled and the realized distribution is pushed *toward* affinity but cannot
fully reach it. Affinity-aware *proposal* (strategies biasing option choice by
weight) is a separate future strategy concern, explicitly out of scope.

**AD-8 — Scope-agnostic core for Fleet reuse.** `BetCoordinator` is a plain
domain class in a new `com.vingame.bot.domain.bot.coordination` package,
constructed from `(Map<Integer,Integer> optionAffinities, long
maxAggregateStakePerRound, long minBet, long betIncrement)` — no Spring, no
`BotGroup`/`BotGroupRuntime` import. The group wiring builds it from `BotGroup`
fields; a future Fleet builds one identically from fleet-level fields and owns it
instead of the runtime. Do not inline coordinator logic into `BotGroupRuntime` or
`BettingMiniGameBot`.

**AD-9 — Off = today.** When `coordinationEnabled` is false, `start()` creates
**no** coordinator; each bot's coordinator reference is `null`;
`applyCoordination` is identity. Existing per-bot strategy behavior is unchanged
end-to-end (no reservation, no logging, no DTO block).

**AD-10 — Bot game-type scope.** The coordinator applies to betting-mini and
TaiXiu bots (both extend `BettingMiniGameBot` and share the `betCondition`/
`decideBet` seam). SLOT bots (`SlotMachineBot`) have no shared-round betting model
and are excluded — `start()` only builds a coordinator for `BETTING_MINI` /
`TAI_XIU` game types.

## Plan

### Phase 1 — Write-side config (2 fields)
- `BotGroup` (`.../botgroup/model/BotGroup.java`): add `boolean
  coordinationEnabled;` and `long maxAggregateStakePerRound;`.
- `BotGroupDTO` (`.../botgroup/dto/BotGroupDTO.java`): add `Boolean
  coordinationEnabled;` and `Long maxAggregateStakePerRound;` (boxed, PATCH-null =
  keep).
- `BotGroupMapper`: add the two fields to `toDTO`, `toEntity`
  (`Optional.ofNullable(...).orElse(false/0L)`), and `updateEntityFromDTO`
  (full-replace-if-present, mirroring existing scalars).
- Validation (`.../botgroup/validation/BettingGridRules.java`): when
  `coordinationEnabled`, require `maxAggregateStakePerRound >= minBet` (a cap
  below one min-bet can never approve anything). Keep it a soft one-liner; do not
  couple it to per-bot `maxTotalBetPerRound`.
- Update `BotGroupMapperTest` assertions for the two new fields.

### Phase 2 — Coordinator core (scope-agnostic, unit-tested)
- New package `com.vingame.bot.domain.bot.coordination`:
  - `ReservationOutcome` — `record(Decision decision, long amount)` with enum
    `Decision { APPROVE, TRIM, REJECT }` (amount = committed amount; 0 for REJECT).
  - `RoundBudget` — immutable per-round targets (`Map<Integer,Long> budget`,
    `long cap`, `long sessionId`) + mutable committed totals
    (`Map<Integer,Long> committed`, `long committedAggregate`). Package-private
    mutation, only touched under the coordinator lock.
  - `BetCoordinator` — constructor `(Map<Integer,Integer> optionAffinities, long
    maxAggregateStakePerRound, long minBet, long betIncrement)`; methods
    `onRound(long sessionId)`, `ReservationOutcome reserve(long sessionId, int
    optionId, long amount)`, `onRoundComplete(long sessionId)`, and read accessors
    for the DTO (per-option committed/budget, aggregate committed, cumulative
    approve/trim/reject counters). Implements AD-3/AD-4/AD-5. Emits the AD-6 DEBUG
    summary in `onRoundComplete`.
- Unit tests (`BetCoordinatorTest`): budget split across N options; trim to
  remaining budget with grid alignment; reject below `minBet`; aggregate cap
  binds before per-option budget; stale-`sid` reject; concurrent `reserve` from
  many threads never exceeds cap or any option budget (stress with a
  latch-released thread pool); `onRound` idempotent across repeated same-`sid`
  calls.

### Phase 3 — Runtime wiring + bot seam
- `BotGroupRuntime`: add a `@Getter/@Setter BetCoordinator coordinator` field
  (nullable).
- `Bot` (`.../bot/core/Bot.java`): add null-tolerant `Bot setCoordinator(...)`
  (fluent, mirroring `setSessionAggregator`) and a `protected BetCoordinator
  coordinator` field.
- `BettingMiniGameBot`:
  - Add `protected Optional<BetDecision> applyCoordination(BetContext ctx,
    BetDecision proposed)` (AD-2): if `coordinator == null` return
    `Optional.of(proposed)`; else `reserve(sidStore.get(), option, amount)` and
    map APPROVE→same, TRIM→new `BetDecision(option, trimmedAmount)`,
    REJECT→`empty`. TRACE-log the per-proposal outcome.
  - Call it in `betCondition()` after `decideBet` (AD-2 snippet).
  - In `onStartGame`, after `memory.beginRound`, call `if (coordinator != null)
    coordinator.onRound(msg.getSessionId());`.
  - In `onEndGame`, after `memory.completeRound`, call `if (coordinator != null)
    coordinator.onRoundComplete(endGameSessionId(msg));`.
- `BotGroupBehaviorService.start()`: after loading `game` (`:257`) and before/at
  runtime creation, if `group.coordinationEnabled` **and** game type is
  `BETTING_MINI`/`TAI_XIU`, build `new BetCoordinator(game
  .getEffectiveOptionAffinities(), group.getMaxAggregateStakePerRound(),
  group.getMinBet(), group.getBetIncrement())` and `runtime.setCoordinator(...)`.
- `createSingleBot`: when `group.coordinationEnabled`, set
  `.betSkipPercentage(0)` on the `BotBehaviorConfig` builder (AD-7). (Currently
  unset → already 0; this makes it explicit and future-proof.)
- Inject the coordinator into each bot in the `startBot` loop (`:284`):
  `bot.setCoordinator(runtime.getCoordinator());` **before** `runtime.startBot(bot)`
  (setter runs pre-`start()`, so the scenario never sees a half-wired bot). Null
  coordinator when off → bypass.
- Note: `BotFactory.createBot` is app-scoped/per-bot and has no group reference,
  so injection is done in the runtime loop, not the factory (unlike the
  app-scoped `sessionAggregator`).

### Phase 4 — Read-side observability
- New `CoordinationStateDTO` (`.../botgroup/dto/CoordinationStateDTO.java`):
  `boolean enabled; long maxAggregateStakePerRound; long currentAggregateStake;
  long approveCount; long trimCount; long rejectCount; List<OptionStateDTO>
  options;` where `OptionStateDTO { int optionId; int targetWeight; long
  targetBudget; long committedStake; double realizedFraction; }`.
- Add nullable `CoordinationStateDTO coordination` to `BotGroupHealthDTO`.
- In `getHealth` (`:755`), populate it from `runtime.getCoordinator()` when
  non-null; leave null (absent) otherwise. Read-only accessors only — no
  mutation from the read path.
- Do **not** add coordination to the write DTO responses or `BotGroupStatsDTO`.

## Implementation Notes / Concerns

- **Reserve commits, tick may not send.** A reservation happens in
  `betCondition()`; if `beforeReconnect` clears the parked decision before the
  supplier runs (the documented unreachable race), the committed stake is not
  actually sent. This is a tiny per-round over-count that resets on the next
  `onRound`. Do not attempt to "un-reserve" — the added complexity is not worth
  the sub-round transient.
- **`sid == 0` sentinel.** `sidStore.get()` is `0` before the first StartGame and
  after `beforeReconnect`. `reserve` must reject `sessionId == 0` and any `sid`
  mismatch — do not begin a budget for the sentinel.
- **Affinity map key type.** Persisted affinities use integer-string BSON keys
  (`Game.java:94`); `getEffectiveOptionAffinities()` returns `Map<Integer,Integer>`
  already normalized — the coordinator keys on the boxed `Integer` option ids
  exactly as `RandomBehaviorStrategy` does.
- **Do not confuse the two caps.** `BotBehaviorConfig.maxTotalBetPerRound` is a
  **per-bot** field (validation-only today); the new
  `maxAggregateStakePerRound` is the **group/fleet** cap the coordinator
  enforces at runtime. Keep them separate in code and DTOs.
- **TaiXiu N=2.** No special-casing — `defaultTaiXiuOptionAffinities()` yields
  `{1:1, 2:1}`, so the coordinator splits the cap 50/50 across the two entries
  automatically. The TaiXiu single-entry lock in `decideBet` runs before
  `applyCoordination`, so the coordinator only ever sees the locked entry.
- **Thread of `reserve`.** `reserve` is called on the scenario thread
  (`pool-N-thread-1`); `onRound`/`onRoundComplete` on the netty
  message-processor thread. All three take the coordinator lock — the DTO read
  accessors must also read under the lock (or via volatile snapshots) to avoid a
  torn view.
- **Grid alignment must not round up.** Always floor to the grid so a trim never
  *exceeds* the remaining budget/cap (rounding up could breach the cap by up to
  one increment across many bots).

## Open Items

- **Crowd-aware tier — out of scope.** Steering relative to real players'
  per-side volume/user counts depends on a game-server feed we do not ingest
  (roadmap #5 feed spike / #6). Build none of it here. The `reserve` signature is
  the natural extension point (a future crowd-aware budget would adjust
  `RoundBudget` from feed data before proposals arrive) but is not designed now.
- **Fleet ownership — future.** `BetCoordinator` is scope-agnostic (AD-8) so a
  Fleet can own one spanning multiple groups. The Fleet entity itself
  (roadmap #1, `TIMED_ACTIVATION`/Fleet plans) is out of scope here; today the
  coordinator is strictly one-per-group.
- **Cap sizing guidance.** Choosing a sensible `maxAggregateStakePerRound`
  relative to `botCount × maxBet × maxBetsPerRound` is an operator concern; no
  auto-derivation in v1. Validation only guards `>= minBet`.
- **`maxBetsPerRound` interplay.** If the aggregate cap is generous but
  `maxBetsPerRound` is low, the fleet may under-propose and never reach the cap —
  expected (AD-7 floor caveat); not a bug.

## Verification

Run on staging after deploy. Prerequisites: a BETTING_MINI group (e.g. BauCua,
N=6) or TaiXiu group (N=2) whose environment reaches live rounds. Substitute the
staging base URL and a real group id `$GID`.

**1. Config round-trips (Phase 1).** Create/patch a group with the two fields and
read it back.
```
curl -s -X PATCH $BASE/api/v1/bot-group -H 'Content-Type: application/json' \
  -d '{"id":"'$GID'","coordinationEnabled":true,"maxAggregateStakePerRound":500000}'
curl -s $BASE/api/v1/bot-group/$GID | jq '{coordinationEnabled, maxAggregateStakePerRound}'
```
Expect: `{"coordinationEnabled": true, "maxAggregateStakePerRound": 500000}`.

**2. Coordinator builds on start (Phase 3).** Start the group and grep the app
log.
```
curl -s -X POST $BASE/api/v1/bot-group/$GID/start
# then, on the app host / via Loki:
grep "coordinator" <app-log> | grep "$GID"
```
Expect a startup line indicating a coordinator was created for the group with the
option budgets (matches `Game.getEffectiveOptionAffinities().size()` options and
cap `500000`). Expect **no** such line for a group started with
`coordinationEnabled=false`.

**3. Per-round coordination summary at DEBUG (Phase 3, AD-6).** Ensure
`com.vingame.bot` is at DEBUG, then observe one summary line per round:
```
curl -s -X POST $BASE/actuator/loggers/com.vingame.bot \
  -H 'Content-Type: application/json' -d '{"configuredLevel":"DEBUG"}'
grep -E "Coordination .* realized .* target .* trim=.* reject=.* approve=" <app-log> | tail
```
Expect: roughly one line per group per completed round; `approve+trim+reject`
counts are non-zero once bets flow; realized fractions listed per option.

**4. Aggregate cap is respected (Phase 3, AD-5).** From the health endpoint over
a few rounds:
```
curl -s $BASE/api/v1/bot-group/$GID/health | jq '.coordination.currentAggregateStake, .coordination.maxAggregateStakePerRound'
```
Expect `currentAggregateStake <= maxAggregateStakePerRound` on every poll.

**5. Health block shape (Phase 4).** 
```
curl -s $BASE/api/v1/bot-group/$GID/health | jq '.coordination'
```
Expect a non-null object with `enabled=true`, `maxAggregateStakePerRound`,
`currentAggregateStake`, `approveCount`/`trimCount`/`rejectCount`, and an
`options` array of length N with per-option `targetBudget`, `committedStake`,
`realizedFraction`. For a group with `coordinationEnabled=false`, expect
`.coordination == null` (absent).

**6. Realized distribution tracks target (AD-5/AD-7, best-effort).** Over a
sustained run (≥20 rounds) with a skewed affinity map (e.g. one option weight 5,
others weight 1):
```
curl -s $BASE/api/v1/bot-group/$GID/health | jq '.coordination.options'
```
Expect the low-affinity options' `committedStake` to sit at/below their smaller
`targetBudget` (trims visible via `trimCount > 0`), i.e. realized share is pushed
toward the target shape versus uniform. Note the floor caveat (AD-7): the
high-affinity option may sit *below* its budget if bots under-propose it — that
is expected, not a failure.

**7. Off path is unchanged (AD-9).** Start a second group with
`coordinationEnabled=false`; confirm `.coordination == null` on its health, no
coordination DEBUG summary lines for its id, and bets flow as before. This is the
only regression-sensitive path; the universal smoke test otherwise applies.

Plan written: docs/plans/BET_COORDINATION.md
Ready for user approval before Dev begins.
