# Crowd-Aware Coordination — Extending the Bet Coordinator with the Live Player Feed (Crowd Tier)

## Goal

Extend the shipped group-scoped `BetCoordinator` (WP#4, `BET_COORDINATION.md`) so
its per-option budget is steered not only by the game's own `optionAffinities` (the
"internal tier") but by **real players' live betting** — the crowd. The game server
broadcasts per-option crowd stake on the game frames in a **`bs` array**; folding
that observed crowd distribution into the coordinator's per-round targets lets the
fleet steer the **combined bots+crowd realized distribution** toward the house
target (or counter-balance the crowd's imbalance) rather than steering the bots in
isolation. This is work package **#6 ("Coordination gate — crowd-aware tier")** of
`docs/plans/BETTING_INTELLIGENCE_ROADMAP.md:107`, and it is the concrete realization
of the `reserve(...)` extension seam BET_COORDINATION deliberately left open (its
Open Item: "a future crowd-aware budget would adjust `RoundBudget` from feed data
before proposals arrive").

It is **opt-in, OFF by default** (a new enable flag, existing groups unchanged
byte-for-byte), composes with — does not replace — the internal tier, applies to
`BETTING_MINI`/`TAI_XIU` only, keeps a Spring-free scope-agnostic core for Fleet
reuse, and mirrors the shipped concurrency model.

**The crowd feed exists (user-confirmed 2026-07-08, resolving the WP#5 spike):** the
`bs` array is already parsed in the codebase (see Findings). What is *not* yet nailed
down without live captures is (a) exactly which frame carries `bs` per product at
which point in the round, and (b) the per-game semantic of the `bc` count field.
This plan cleanly separates **plannable-now** (coordinator math, seam, config,
composition, fail-safes) from **capture-gated** (exact field binding, per-game count
semantic) — see "Captures needed".

## Findings — Current State

### `bs` IS already parsed — the crowd feed is in the codebase today

The per-option array `bs` is parsed on multiple message types. The per-option entry
carries four fields; two map directly to the brief's `v`/`b`/`c`:

| Field | Type | Meaning (per `docs/plans/ROUND_DATA_COLLECTION_FINDINGS.md:26-28`) | Brief's name |
|---|---|---|---|
| `eid` | `int` | option / position id — **same id space as the outbound `Bet` `eid`** (`request/Bet.java:23`) and the coordinator's option key | — |
| `v` | `long` | **total of all users' bets on this option** (crowd aggregate stake) — CONFIRMED | `v` (value) |
| `b` | `long` | **this user's (this bot's) own bet on this option** — CONFIRMED. **Only present on the `WithTotal` variant** | `b` (bet of current player) |
| `bc` | `int` | count — "likely bet count on this option" (`ROUND_DATA_COLLECTION_FINDINGS.md:28` hedges "for this user"). **This is the brief's `c`; its bets-vs-players semantic is UNVERIFIED and the central design risk.** | `c` (count) |

Two entry shapes exist:
- **`BetInfo`** (`BomEndGameMessage.BetInfo`, `g2/bom/BomEndGameMessage.java:85`):
  `{eid, bc, v}` — **no `b`**. Used by BOM/B52/Nohu **EndGame** `bs`.
- **`BetInfoWithTotal`** (`g2/bom/BomSubscribeMessage.java:98`, `g3/tip/TipSubscribeMessage.java:128`):
  `{b, eid, bc, v}` — adds this-player's `b`. Used by BOM/B52/Nohu **Subscribe** and
  all Tip frames.

**`v` (crowd aggregate) has no semantic ambiguity — it is the safe primary signal.**
`b` (own bet) lets us subtract the fleet's own contribution to recover the pure-crowd
stake if desired. `bc` is the only ambiguous field.

### Which frame carries `bs`, per product (the load-bearing map)

Established by reading each message class (`grep '@JsonProperty("bs")'`). **This is
the decisive product-divergence finding for where the feed enters:**

| Product | Subscribe `bs` | **UpdateBet `bs`** | EndGame `bs` | Entry shape |
|---|---|---|---|---|
| **Tip** (`P_116` family, `g3/tip`) | yes (`TipSubscribeMessage:51`) | **YES — `TipUpdateBetMessage:21`** | yes (`TipEndGameMessage:144`) | `BetInfoWithTotal` (`{b,eid,bc,v}`) |
| **BOM** (`g2/bom`) | yes (`BomSubscribeMessage:57`) | **NO** — `BomUpdateBetMessage` carries only `{gS, rmT}` (`:16-24`) | yes (`BomEndGameMessage:67`) | Sub: `BetInfoWithTotal`; End: `BetInfo` (no `b`) |
| **B52** (`g2/b52`) | yes (`B52SubscribeMessage:57`) | **NO** (mirrors BOM) | yes (`B52EndGameMessage:67`) | same as BOM |
| **Nohu** (`g4/nohu`) | yes (`NohuSubscribeMessage:57`) | **NO** (mirrors BOM) | yes (`NohuEndGameMessage:67`) | same as BOM |
| **Tai Xiu** (`taixiu`, P_114/P_116) | **NO** `bs` (`TaiXiuSubscribeMessage` models only timing, `ignoreUnknown=true` — `:42`) | **NO** | **NO** `bs` — `TaiXiuEndGameMessage` carries only aggregate `gB`/`gR` per bot, no per-option array (`betCountFor` returns `1` because "the captured EndGame frame carries no per-bet count" — `:174-184`) | — |

**Two decisive consequences:**
1. **Intra-round crowd growth is observable only on Tip** (the crowd volume grows
   during the bet window; Tip's `UpdateBet` re-broadcasts `bs` each tick —
   `TipUpdateBetMessage:15` — while BOM/B52/Nohu only broadcast `bs` at Subscribe
   (round start, pre-crowd) and EndGame (round over, too late to steer). So for
   BOM/B52/Nohu the *live* signal in the current wire model is **only the round-open
   Subscribe snapshot** (near-zero crowd) and the **prior round's EndGame** (a
   one-round-lagged full-round crowd distribution). This is a capture question: BOM
   may broadcast `bs` on a frame we do not yet parse (see Captures needed).
2. **Tai Xiu has no `bs` on any frame we parse.** Crowd-aware steering for Tai Xiu is
   **not supported by the current wire model** — it degrades to the internal tier
   (AD-C7). Whether Tai Xiu broadcasts crowd data under a different key is a capture
   question, explicitly deferred.

### The coordinator seam (what we extend)

- `BetCoordinator` (`.../domain/bot/coordination/BetCoordinator.java`) computes a
  **round-independent** `targetBudget` map once at construction:
  `B(o) = floor(w(o)/W * cap)` (`:91-103`), snapshotted into each round's
  `RoundBudget` at `onRound(sessionId)` (`:112-126`). `reserve(sid, option, amount)`
  (`:139-165`) clamps the proposal to `remainingOption(o)` and `remainingAggregate()`,
  grid-aligns down, commits, returns APPROVE/TRIM/REJECT.
- `RoundBudget` (`.../coordination/RoundBudget.java`) holds the **immutable** per-round
  `budget` map + mutable `committed`/`committedAggregate`. `remainingOption(o) =
  budget(o) - committed(o)` (`:63-65`). **The crowd tier changes what `budget(o)` is
  per round** — today it is the affinity split; crowd-aware makes it a *crowd-adjusted*
  split recomputed each round from the observed crowd distribution.
- The seam is exactly where BET_COORDINATION Open Item pointed: the per-round budget
  is computed in `onRound` and consumed by `reserve`. A crowd observation must land
  **before/as the round's budget is computed and while it is in flight**.

### Where a crowd observation enters (the thread/hook question)

- `onRound(sid)` and `onRoundComplete(sid)` run on the **netty message-processor
  thread** (called from `onStartGame:416` / `onEndGame:519`), first-seen idempotent
  across the group's N bots. `reserve(...)` runs on the **scenario thread**.
- The precedent for reading a meter off a frame is JACKPOT_SCALE's `observePool(sid,
  pool)` in `onEndGame` (`BettingMiniGameBot.java:527`) — but that is a *round-boundary*
  read. **Crowd volume is intra-round and grows during the bet window**, so unlike the
  jackpot pool it needs an **in-round update hook on `onUpdate`** (the UpdateBet frame),
  not only a round-boundary read — *for the products that carry `bs` on UpdateBet
  (Tip today)*. `onUpdate` (`BettingMiniGameBot.java:435-443`) currently reads only
  `getGameState()`; it is the natural place to add a crowd read.
- **Staleness/ordering:** all N bots' `onUpdate` fire for the same frame on the netty
  thread; the crowd observation must be **first-seen idempotent per (sid, frame)** and
  **monotonic-by-arrival** (a later, larger crowd snapshot supersedes an earlier one;
  a straddling frame for a stale sid is dropped, mirroring `reserve`'s sid guard).

### Config, runtime, health precedent (all reused verbatim)

- **Config placement:** `coordinationEnabled` + `maxAggregateStakePerRound` already on
  `BotGroup` (BET_COORDINATION AD-1). Crowd-awareness is a **coordination sub-mode**, so
  its enable flag lands on the **BotGroup** next to `coordinationEnabled`. The per-game
  **count semantic** is a **game-intrinsic protocol trait** → it lands on the **Game**
  (mirroring the game-intrinsic vs behavior-preference split in
  `AFFINITY_AWARE_PROPOSAL.md` / `JACKPOT_SCALE_AND_RAMP.md` AD-R4).
- **Runtime:** the coordinator is a nullable field on `BotGroupRuntime`, built in
  `BotGroupBehaviorService.start()` (`:288-293`), injected into each bot before
  `startBot` (`:328`). No new runtime object — the crowd tier is state *inside* the
  existing coordinator.
- **Health:** `CoordinationStateDTO` (`.../dto/CoordinationStateDTO.java`) is the
  nullable block on `BotGroupHealthDTO`, built by `buildCoordinationState(...)`
  (`BotGroupBehaviorService.java:937`). The crowd tier adds fields to the existing
  block (per-option observed crowd stake, adjusted budget, count semantic) — no new
  top-level block.

## Per-aspect readiness / mapping

| Aspect | Readiness | Notes |
|---|---|---|
| `bs` parsing | **ready** | Already parsed on Tip/BOM/B52/Nohu Subscribe+EndGame, Tip UpdateBet. `v`/`b`/`bc` fields present (AD-C1). |
| Live intra-round crowd (Tip) | **ready** | `TipUpdateBetMessage.bs` re-broadcasts each tick — the only confirmed intra-round crowd source (AD-C3). |
| Live intra-round crowd (BOM/B52/Nohu) | **capture-gated** | UpdateBet frame carries no `bs` today; may exist under an unparsed frame/field. Degrades to Subscribe-snapshot + EndGame-lag until captured (AD-C3, Captures §1). |
| Crowd for Tai Xiu | **blocked** | No `bs` on any parsed Tai Xiu frame; degrades to internal tier (AD-C7). Capture-gated (Captures §2). |
| Crowd-target math (`v`) | **ready** | Fold observed `v(o)` into the per-round budget; `v` is semantically unambiguous (AD-C2). |
| Count semantic (`bc`) | **capture-gated** | bets-vs-players varies per game; new `Game.crowdCountSemantic` enum, fail-safe to `v`-only (AD-C5). |
| Coordinator seam | **ready** | Recompute `RoundBudget.budget` per round from crowd; `reserve` unchanged (AD-C2/AD-C4). |
| Feed entry / thread | **ready** | `observeCrowd(sid, List<CrowdOption>)` on the coordinator, called from `onUpdate` (Tip) + `onRound` seed / `onRoundComplete` lag (AD-C3). |
| Composition w/ internal tier | **ready** | Crowd adjusts the *target*; internal split is the crowd-off case (AD-C4). |
| Composition w/ affinity proposal / jackpot / ramp | **ready** | Orthogonal axes; no shared state (AD-C8). |
| Opt-in / off = today | **ready** | New `crowdAwareCoordination` flag; off ⇒ internal tier verbatim (AD-C6). |
| Concurrency | **ready** | Reuse coordinator `ReentrantLock` + volatile `current` (AD-C9). |
| Health | **ready** | Extend `CoordinationStateDTO` (AD-C10). |

## Architecture Decisions

**AD-C1 — The crowd signal is the parsed `bs` array; `v` is primary, `b` optional,
`bc` conditional.** The coordinator consumes a per-round, per-option observation
`crowd(o) = { v, b, bc }` derived from `bs`, keyed on `eid` (== the option id space).
The primary steering quantity is **`v` (crowd aggregate stake)** — it is
semantically unambiguous and present on every `bs` frame. `b` (this bot's own stake)
is available on the `WithTotal` variants and MAY be subtracted to isolate pure-crowd
stake (the fleet's own stake is already tracked by the internal committed totals, so
double-counting must be avoided — see AD-C4). `bc` (count) is used **only** when the
game's count semantic is known (AD-C5); otherwise the coordinator steers on `v` alone.

**AD-C2 — Crowd-target math: steer the combined bots+crowd realized distribution
toward the house target.** The house target shape is the affinity distribution
`P(o) = w(o)/W` (the internal tier's source, unchanged). Let:
- `C = maxAggregateStakePerRound` (the fleet's own per-round budget, unchanged).
- `X(o)` = observed **pure crowd** stake on option `o` for the in-flight round
  (`= v(o)` minus the fleet's own `b`-sum contribution on `o` if isolating; see AD-C4),
  `X = Σ X(o)`.
- The **desired combined** stake on `o` across crowd+fleet is `P(o) · (X + C)` (the
  house wants the *total* table to sit at the affinity shape).
- The **fleet's crowd-adjusted per-option budget** is therefore:
  ```
  B_crowd(o) = clamp( P(o)·(X + C) − X(o), 0, C )
  ```
  i.e. "how much the fleet must add on `o` so that crowd+fleet reaches the target
  share on `o`", clamped to `[0, C]`. The aggregate cap `C` remains the **hard total
  ceiling** — the fleet never spends more than `C` in a round regardless of crowd
  size. Because `Σ B_crowd(o)` may fall below `C` (when the crowd already fills some
  options) or be clamped, the coordinator **re-normalizes only downward** to keep
  `Σ committed ≤ C`; it never inflates the fleet's spend above `C`. This is the
  "counter-balance the crowd's imbalance" behavior: options the crowd over-fills get a
  **smaller** fleet budget (possibly 0), options the crowd under-fills get a **larger**
  one — pushing the *combined* table toward the affinity shape.
- **Crowd-off (internal tier) is the special case `X(o) = 0 ∀o`:** then
  `B_crowd(o) = P(o)·C = B(o)`, the exact internal-tier budget. So the crowd tier is a
  strict generalization; with no crowd it *is* the internal tier (AD-C4).

**AD-C3 — Feed entry: `observeCrowd(sid, options)` on the coordinator, driven from
the message hooks; intra-round on UpdateBet, round-boundary as fallback.** Add
`void observeCrowd(long sessionId, List<CrowdOption> options)` to `BetCoordinator`
(`CrowdOption` = `record(int optionId, long value, long ownBet, int count)`). It is
called from `BettingMiniGameBot`:
- **`onUpdate`** (`:435`) — when the UpdateBet frame carries `bs` (Tip today): read
  the per-option crowd and call `observeCrowd(currentSid, ...)`. **This is the live,
  intra-round update** that tracks the crowd building through the bet window.
- **`onRound`/`onStartGame`** — seed from the Subscribe snapshot's `bs` if available
  (round-open, typically near-zero crowd) so the first ticks are not blind. *(A
  Subscribe `bs` is captured on the SubscribeMessage but `onSubscribe:376` does not
  re-broadcast per round; seeding here is optional and capture-gated — see Notes.)*
- **`onEndGame`** (`:445`) — the EndGame `bs` is the **full-round crowd distribution**
  and is used to seed the *next* round's budget as a **one-round-lagged prior** when no
  intra-round signal is available (the BOM/B52/Nohu case). This mirrors JACKPOT_SCALE's
  one-round-lag `observePool` and is the graceful degradation for products without
  `bs`-on-UpdateBet.
- **Semantics under the lock (AD-C9):** `observeCrowd` acquires the coordinator lock;
  if `sessionId != current.sessionId()` for an intra-round update it is dropped
  (stale/straddling — mirrors `reserve`'s sid guard); the crowd map is **replaced**
  (not accumulated) by the latest snapshot for the current sid (`bs` is already the
  running aggregate, not a delta), and the round's `B_crowd(o)` budget is **recomputed**
  from AD-C2 and swapped into `current`. First-seen idempotency: N bots reporting the
  same UpdateBet frame converge on the same crowd map (same input → same recompute); the
  lock guards the paired update + the once-per-frame TRACE.

**AD-C4 — Composition with the internal tier: crowd adjusts the target, `reserve` is
unchanged.** `reserve(...)` still clamps to `remainingOption(o)` and
`remainingAggregate()` and grid-aligns down — **no change**. The crowd tier changes
only how `RoundBudget.budget(o)` is computed (`B(o)` → `B_crowd(o)` per AD-C2), and it
is recomputed intra-round by `observeCrowd`. **Avoiding double-count:** the coordinator
already tracks the fleet's own committed stake per option (`committed(o)`). The crowd
`v(o)` from the feed **includes the fleet's own confirmed bets** (the bots are
subscribers too). To isolate pure crowd `X(o)`, subtract the fleet's own contribution:
either (a) use the per-bot `b` field summed across the fleet (only exact on Tip's
`WithTotal` frames), or (b) subtract the coordinator's own `committed(o)` as a proxy.
**Decision (Open, D3):** default to **(b) subtract `committed(o)`** — it is always
available, scope-agnostic, and self-consistent with what the coordinator has actually
approved; `b`-subtraction is a Tip-only refinement deferred. When
`X(o) = max(0, v(o) − committed(o))` the recompute stays coherent as the fleet fills
in during the round (the budget shrinks as the fleet approaches the target, which is
the intended closed-loop behavior).

**AD-C5 — Per-game count semantic: a new `Game.crowdCountSemantic` enum, fail-safe to
value-only.** Add `enum CrowdCountSemantic { BETS, PLAYERS, UNKNOWN }` and a
`crowdCountSemantic` field on `Game` (default `UNKNOWN`). The coordinator is
constructed with the resolved semantic. **`bc` (count) is used ONLY when the semantic
is `BETS` or `PLAYERS`; when `UNKNOWN` (or unset), the coordinator steers on `v` alone
and never reads `bc`** — `v` has no ambiguity, so this is a safe, correct degradation,
not a disablement. What the count *adds* (Open, D4): even when known, v1's target math
(AD-C2) is **stake-based (`v`), not count-based** — the count is carried into the
health block and TRACE for observability and future count-aware steering, but the v1
budget does not depend on it. This keeps the ambiguous field **out of the load-bearing
math entirely** in v1: a wrong `crowdCountSemantic` cannot corrupt steering because
steering uses `v`. (Rationale: the brief's risk — "a bets-count and a players-count
are not interchangeable" — is neutralized by not letting the count drive the budget in
v1. If a future version wants count-weighted steering, the semantic must first be
captured and set correctly.)

**AD-C6 — Opt-in, off = internal tier verbatim.** Add `boolean
crowdAwareCoordination` to `BotGroup`. The crowd tier is active only when
`coordinationEnabled && crowdAwareCoordination` (crowd-aware is a **sub-mode of
coordination** — it cannot run without the coordinator). When
`crowdAwareCoordination` is false, the coordinator is built exactly as today
(internal tier), `observeCrowd` is never called, and behavior is **byte-for-byte
BET_COORDINATION**. When `coordinationEnabled` is false, no coordinator exists at all
(the flag is moot). Existing groups are unchanged.

**AD-C7 — Tai Xiu degrades to the internal tier (no crowd `bs` on the wire).** Tai
Xiu carries no `bs` on any parsed frame (Findings). A `TAI_XIU` group with
`crowdAwareCoordination=true` builds a crowd-capable coordinator, but `observeCrowd`
is simply never fed (no `bs` to read), so `X(o) = 0` and `B_crowd(o) = B(o)` — the
internal tier (AD-C2 special case). **No throttle, no error — it is the internal
tier with the crowd hook dormant.** Whether Tai Xiu broadcasts crowd data under a
different key is a capture question (Captures §2); until then, Tai Xiu crowd-awareness
is a no-op that degrades safely.

**AD-C8 — Composition with the other shipped features (orthogonal).**
- *Affinity-aware proposal (WP#7):* biases *which option* bots propose (supply side);
  the crowd tier adjusts *which options the coordinator has budget for* (demand side).
  Both point the fleet at the same house target; affinity-weighted proposal is exactly
  the raw material the crowd-adjusted budget needs to fill under-crowded options.
  No coupling.
- *Jackpot-scale (WP#3):* scales *how many* bets (fleet volume) via
  `effectiveMaxBetsPerRound`; if AD-J9 is wired, it also scales the aggregate cap `C`.
  The crowd math uses whatever `C` is in effect (jackpot-scaled or not) — they compose
  multiplicatively with no special case.
- *Ramp (WP#2):* shapes *when* within the window bots bet; the crowd tier's intra-round
  recompute (AD-C3) naturally sees the crowd build as ramped bots pile in later — they
  reinforce rather than conflict. No shared state.

**AD-C9 — Concurrency: reuse the coordinator's `ReentrantLock` + volatile `current`.**
`observeCrowd` acquires the same single `ReentrantLock` that guards `reserve` /
`onRound` / `onRoundComplete`, recomputes the budget, and swaps a fresh `RoundBudget`
(new immutable `budget` map, carrying over `committed` totals — see Note) into
`current`. `reserve` reads `current` (volatile) as today. `ReentrantLock` does not pin
virtual threads. The recompute is O(N options) arithmetic held for microseconds; even
with N bots reporting each UpdateBet frame the contention is negligible (bounded by the
UpdateBet cadence, not per-bot). Committed totals must be **preserved** across an
intra-round budget swap (the fleet's spend so far is real) — so the swap replaces only
the `budget` targets, not the `committed` accumulators (a small `RoundBudget`
addition: `withBudget(newBudget)` that carries `committed`/`committedAggregate`).

**AD-C10 — Observability: extend `CoordinationStateDTO`, not a new block.** Add to
`CoordinationStateDTO`: `boolean crowdAware`, `String crowdCountSemantic`, and per
`OptionStateDTO`: `long observedCrowdStake` (the latest `v(o)`), `long crowdAdjustedBudget`
(`B_crowd(o)`), and (when semantic known) `long observedCrowdCount` (`bc(o)`). Populated
in `buildCoordinationState` from the coordinator's `snapshot()` (extended to carry the
crowd view under the same lock). Logging per CLAUDE.md: the coordinator's existing
**one DEBUG line per group per round** in `onRoundComplete` gains a crowd histogram
segment (`crowd=[opt=o v=… adj=…]`); the intra-round `observeCrowd` recompute is
**TRACE only** (never DEBUG — it fires per UpdateBet frame). Group-lifecycle INFO gains
one line at `start()`: `"Crowd-aware coordination enabled for group … (countSemantic=…)"`.

## Plan

### Phase 1 — Config (2 fields: 1 on BotGroup, 1 on Game)
- `BotGroup` (`.../botgroup/model/BotGroup.java`): add `boolean crowdAwareCoordination;`.
- `BotGroupDTO`: add `Boolean crowdAwareCoordination;` (boxed, PATCH-null = keep).
- `BotGroupMapper` (hand-written): add to `toDTO`, `toEntity`
  (`Optional.ofNullable(...).orElse(false)`), `updateEntityFromDTO` (replace-if-present),
  mirroring `coordinationEnabled`.
- New `enum CrowdCountSemantic { BETS, PLAYERS, UNKNOWN }` in `.../domain/game/model/`.
- `Game` (`Game.java`): add `CrowdCountSemantic crowdCountSemantic;` (default `UNKNOWN`
  via getter or `getEffectiveCrowdCountSemantic()` returning `UNKNOWN` when null, so
  legacy Mongo docs resolve safely).
- `GameDTO` + `GameMapper` (`toDTO`/`toEntity`/`updateEntityFromDTO`) +
  `GameController` create/PATCH: surface `crowdCountSemantic`.
- Validation: when `crowdAwareCoordination` is true, require `coordinationEnabled` true
  (a soft 400 — crowd-aware is a coordination sub-mode, AD-C6). `crowdCountSemantic` is
  unvalidated (any enum, `UNKNOWN` is the safe default).
- Update `BotGroupMapperTest` / `GameMapperTest` for the new fields.

### Phase 2 — Coordinator crowd core (scope-agnostic, unit-tested)
- `CrowdOption` — `record(int optionId, long value, long ownBet, int count)` in
  `.../coordination`.
- `RoundBudget`: add `RoundBudget withBudget(Map<Integer,Long> newBudget)` that returns
  a new instance carrying the **same `committed`/`committedAggregate`** but replacing
  `budget` (AD-C9), and a `budget()` accessor for the recompute.
- `BetCoordinator`:
  - Constructor gains `boolean crowdAware` (or a `CrowdCountSemantic`), stored final.
    (Internal-tier construction passes `crowdAware=false` — existing call site adds one
    arg; keep the existing 4-arg path working via an overload so BET_COORDINATION tests
    are untouched.)
  - `void observeCrowd(long sessionId, List<CrowdOption> options)` (AD-C3/AD-C9):
    under the lock, drop if `crowdAware` off or `sessionId != current.sessionId()`;
    store the latest crowd map; recompute `B_crowd(o)` per AD-C2 (using
    `X(o) = max(0, v(o) − committed(o))`, D3 default); swap via `current.withBudget(...)`;
    TRACE the recompute.
  - `snapshot()` (`:260`): extend `Snapshot`/`OptionSnapshot` with `observedCrowdStake`,
    `crowdAdjustedBudget`, `observedCrowdCount`, and top-level `crowdAware` +
    `crowdCountSemantic`.
  - `onRoundComplete` DEBUG line (`:217`) gains the crowd histogram segment.
- Unit tests (`BetCoordinatorCrowdTest`): crowd-off ⇒ `B_crowd(o) == B(o)` for all o
  (internal-tier equivalence — the load-bearing regression pin); crowd on one option
  ⇒ that option's fleet budget shrinks and under-crowded options' budgets grow, with
  `Σ committed ≤ C` preserved; a crowd larger than the target on an option drives its
  budget to 0 (clamp); stale-sid `observeCrowd` is dropped; recompute is idempotent
  across N same-frame calls; committed totals survive an intra-round budget swap;
  `bc`/count never affects the budget (AD-C5) — a test with garbage counts yields the
  same budget as with zero counts.

### Phase 3 — Wiring + message read
- `BettingMiniGameBot`:
  - `onUpdate` (`:435`): if the frame is a `bs`-bearing UpdateBet (Tip's
    `TipUpdateBetMessage`, via a new marker `HasCrowdBets { List<CrowdOption> crowdBets(); }`
    implemented on the `bs`-bearing message types — see Note), and `coordinator != null`,
    call `coordinator.observeCrowd(sidStore.get(), msg.crowdBets())`.
  - `onEndGame` (`:445`): if the EndGame carries `bs`, call `observeCrowd` with the
    finished round's sid as the one-round-lagged prior seed for the next round
    (degradation path for products without `bs`-on-UpdateBet; AD-C3).
  - (Optional) `onSubscribe` seed — deferred (capture-gated, Notes).
- New marker `HasCrowdBets` in `.../domain/bot/message`, implemented on
  `TipUpdateBetMessage`, `TipEndGameMessage`, `BomEndGameMessage`, `B52EndGameMessage`,
  `NohuEndGameMessage` (and the Subscribe variants if seeding is added) — mapping each
  product's `bs` entry shape (`{eid, bc, v}` or `{b, eid, bc, v}`) to `CrowdOption`.
  Mirrors the `HasJackpotPool` / `HasBetTotals` marker-dispatch precedent.
- `BotGroupBehaviorService.start()` (`:288`): build the coordinator with
  `crowdAware = group.isCrowdAwareCoordination()` and the game's
  `getEffectiveCrowdCountSemantic()`; add the INFO line (AD-C10). No new runtime object.
- No change to the bot↔coordinator injection (`:328`) — the coordinator already carries
  the crowd state.

### Phase 4 — Read-side observability
- Extend `CoordinationStateDTO` + `OptionStateDTO` (AD-C10).
- Extend `buildCoordinationState` (`:937`) to populate the crowd fields from the
  extended `snapshot()`.
- No new top-level health block.

## Implementation Notes / Concerns

- **The crowd `v(o)` already includes the fleet's own bets.** The bots are subscribers,
  so `bs[].v` counts their stake. AD-C4 subtracts `committed(o)` to isolate pure crowd;
  do **not** feed raw `v` as pure crowd or the fleet double-counts itself and
  under-fills. The `b` field (own bet) is the exact per-frame self-contribution on Tip
  but is only on `WithTotal` frames — the `committed(o)` proxy is the portable default
  (D3).
- **BOM/B52/Nohu have NO intra-round `bs`.** Their UpdateBet frame is `{gS, rmT}` only
  (`BomUpdateBetMessage`). In the current wire model the only crowd signals for these
  products are the round-open Subscribe snapshot (near-zero) and the prior round's
  EndGame (one-round lag). **Do not assume live intra-round crowd for BOM until a
  capture proves a `bs`-bearing frame exists** (Captures §1). The plan is correct
  without it (EndGame-lag degradation) but weaker for BOM than for Tip.
- **Tai Xiu has NO `bs` at all** — crowd-aware is a safe no-op there (AD-C7). Do not
  invent a `bs` reader for Tai Xiu frames without a capture (Captures §2).
- **`bc` must stay out of the v1 budget math.** AD-C5 uses `v` for steering; `bc` is
  observability/future-only. A misconfigured `crowdCountSemantic` must be incapable of
  corrupting steering — enforce by never branching the budget on `bc` in v1.
- **Intra-round budget swap must preserve `committed`.** The fleet's spend so far is
  real money; `withBudget` replaces only the targets (AD-C9). A naive `new RoundBudget`
  would zero `committed` and let the fleet overspend `C`.
- **Recompute cost / cadence.** `observeCrowd` fires per UpdateBet frame per bot;
  first-seen idempotency + O(N options) keeps it cheap, but keep the TRACE gated (per
  CLAUDE.md) — never DEBUG on this hot path.
- **eid ↔ option id.** The `bs` `eid` is the same id space as the coordinator's option
  keys and the outbound `Bet.eid` — no remap needed. An `eid` not in the affinity key
  set (unexpected option) should be ignored (defensive), not crash the recompute.
- **Composition regression surface.** The one regression-sensitive pin is
  crowd-off-equals-internal-tier (Phase 2 test) and off-flag-equals-BET_COORDINATION
  (the four-corners smoke). Everything else is additive.

## Open Items

- **BOM/B52/Nohu intra-round crowd — capture-gated (Captures §1).** May exist under an
  unparsed frame; if not, these products steer on Subscribe-seed + EndGame-lag only.
- **Tai Xiu crowd data — capture-gated (Captures §2).** No `bs` on parsed frames;
  degrades to internal tier until/unless a crowd key is captured.
- **`b`-based self-subtraction (D3 alternative) — deferred.** Tip-only refinement over
  the portable `committed(o)` proxy.
- **Count-weighted steering — deferred (AD-C5).** v1 steers on `v`; `bc` is carried but
  not load-bearing. Requires a captured, verified `crowdCountSemantic` first.
- **Subscribe-snapshot seeding — deferred.** Optional round-open seed; capture-gated on
  whether the Subscribe `bs` is meaningfully non-zero at round open.
- **Fleet ownership — future.** The crowd state lives inside the scope-agnostic
  `BetCoordinator` (AD-8 of BET_COORDINATION), so a Fleet owns it identically.

## Captures needed (capture-gated — cannot finalize without real frames)

The coordinator math, seam, config, composition, and fail-safes above are **plannable
and buildable now**. The following are **blocked on real captures** and gate only the
field-binding details and the per-game count table — NOT the architecture.

| # | Capture | Product / plugin | Frame / message | Point in round | Disambiguates |
|---|---|---|---|---|---|
| 1 | Full-frame WS dump of every bet-window frame | **BOM** (`g2/bom`, e.g. B52 or Nohu) with **real players present** | Every `UpdateBet` (cmd = product's updateBet) across one bet window, plus the Subscribe and EndGame | From round-open through window-close | **Does BOM/B52/Nohu broadcast `bs` (or any per-option crowd array) on an intra-round frame?** If yes → its field names + whether it grows during the window (confirms live intra-round steering for BOM). If no → confirms BOM is Subscribe-seed + EndGame-lag only. |
| 2 | Full-frame WS dump of the Tai Xiu bet window | **Tai Xiu** — both `taixiuPlugin` (P_116) and `taixiuJackpotPlugin` (P_114) | Subscribe (cmd 1005/1105), all UpdateBet, EndGame (cmd 1004/1104), with **real players** | Whole round | **Does Tai Xiu broadcast any per-option crowd array under a non-`bs` key?** (`TaiXiuSubscribeMessage` uses `ignoreUnknown=true`, so a crowd field would be silently dropped today.) Determines whether Tai Xiu can ever be crowd-aware or stays internal-tier (AD-C7). |
| 3 | `bs[].bc` interpretation, on **two different games**, with a known small number of real players | **Tip** AND **one BOM product** (B52/Nohu) — the two that carry `bs` | Subscribe / UpdateBet / EndGame `bs` | Any | **Is `bc` the number of BETS or the number of distinct PLAYERS on that option?** Cross-check `bc` against the known player count (e.g. 1 human placing 3 bets on an option: `bc=3` ⇒ BETS, `bc=1` ⇒ PLAYERS). **This fills the per-game `crowdCountSemantic` table** and is the primary design-risk resolution. Until captured, every game stays `UNKNOWN` → `v`-only steering (AD-C5), which is safe. |
| 4 | Confirm `bs[].v` is total-all-players (not per-user) on a **BOM** EndGame | BOM (B52/Nohu) | EndGame `bs` (`BetInfo`, no `b`) | Round end | Confirms `v` is the crowd aggregate on the `no-b` entry shape (Tip's `v` is documented; BOM's `BetInfo` lacks `b`, so `v` must be the all-players total for AD-C2 to hold). Low-risk (ROUND_DATA_COLLECTION_FINDINGS.md:27 already states this for Tip) but worth a BOM confirmation. |

**Plannable now (not capture-gated):** the AD-C2 crowd-target math; the `observeCrowd`
seam + thread model (AD-C3/AD-C9); the `crowdAwareCoordination` flag + `CrowdCountSemantic`
enum + fail-safe (AD-C5/AD-C6); composition (AD-C4/AD-C8); the `HasCrowdBets` marker
scaffold (the *shape* mapping `{eid,v,b,bc}`→`CrowdOption` is known from the parsed
classes); health/DEBUG/TRACE surfaces (AD-C10). Dev can build Phases 1–2 and the
Tip-path of Phase 3 (Tip's `bs` binding is fully known today) before captures land;
captures #1/#2 only extend the product coverage and #3 only fills the semantic table.

## Verification

Run on staging after deploy. **Prerequisite that makes this meaningfully testable: a
live-round game with a REAL player crowd** (bots alone produce no crowd — the whole
point is steering relative to human bettors). The natural vehicle is a **Tip
BETTING_MINI/Tai-Xiu-family group** (Tip is the only product with confirmed
intra-round `bs` on UpdateBet) on an environment with organic human traffic — **but
note (per MEMORY): staging Tip WS-connect may be DNS-blocked
(`tipclubgw-sock.stgame.win`)**; if so, crowd verification is blocked until either the
DNS block is lifted or a BOM/B52/Nohu capture (Captures §1) confirms an intra-round
crowd frame for a reachable product. Substitute the staging base URL, a real group id
`$GID`, and a real game id `$GAMEID`.

**1. Config round-trips (Phase 1).**
```
curl -s -X PATCH $BASE/api/v1/bot-group -H 'Content-Type: application/json' \
  -d '{"id":"'$GID'","coordinationEnabled":true,"crowdAwareCoordination":true,"maxAggregateStakePerRound":500000}'
curl -s $BASE/api/v1/bot-group/$GID | jq '{coordinationEnabled, crowdAwareCoordination, maxAggregateStakePerRound}'
curl -s -X PATCH $BASE/api/v1/game/$GAMEID -H 'Content-Type: application/json' \
  -d '{"crowdCountSemantic":"BETS"}'
curl -s $BASE/api/v1/game/$GAMEID | jq '.crowdCountSemantic'
```
Expect `{"coordinationEnabled":true,"crowdAwareCoordination":true,"maxAggregateStakePerRound":500000}`
and `"BETS"`. Also expect a 400 if `crowdAwareCoordination:true` is PATCHed with
`coordinationEnabled:false` (AD-C6 validation).

**2. Crowd-aware coordinator builds on start (Phase 3, AD-C10).**
```
curl -s -X POST $BASE/api/v1/bot-group/$GID/start
grep -E "Crowd-aware coordination enabled for group .* countSemantic=" <app-log> | grep "$GID"
```
Expect one INFO line with the count semantic. Expect **no** crowd line for a group with
`crowdAwareCoordination=false` (it still gets the internal-tier "Bet coordinator
created" line — AD-C6).

**3. Crowd observations flow intra-round (Phase 3, AD-C3, TRACE).** Requires a live
crowd on a `bs`-on-UpdateBet product (Tip).
```
curl -s -X POST $BASE/actuator/loggers/com.vingame.bot \
  -H 'Content-Type: application/json' -d '{"configuredLevel":"TRACE"}'
grep -E "observeCrowd|crowd recompute" <app-log> | grep "$GID" | tail
```
Expect per-window recompute lines showing per-option `v` growing through the bet
window (only on a product carrying `bs` on UpdateBet with real players present).

**4. Per-round crowd DEBUG summary (Phase 2/3, AD-C10).**
```
curl -s -X POST $BASE/actuator/loggers/com.vingame.bot \
  -H 'Content-Type: application/json' -d '{"configuredLevel":"DEBUG"}'
grep -E "Coordination sid=.* crowd=\[" <app-log> | grep "$GID" | tail
```
Expect ~one line per group per round, with a `crowd=[opt=… v=… adj=…]` segment
alongside the existing realized/target histogram.

**5. Health block carries the crowd view (Phase 4, AD-C10).**
```
curl -s $BASE/api/v1/bot-group/$GID/health | jq '.coordination | {crowdAware, crowdCountSemantic, options}'
```
Expect `crowdAware=true`, the configured `crowdCountSemantic`, and per-option
`observedCrowdStake` + `crowdAdjustedBudget` (and `observedCrowdCount` when the
semantic is known). For a group with `crowdAwareCoordination=false`, expect
`crowdAware=false` and the crowd per-option fields absent/zero (internal tier).

**6. Fleet counter-balances the crowd (AD-C2, best-effort, ≥20 rounds, real crowd).**
On a skewed-affinity game with a live crowd concentrated on one option:
```
curl -s $BASE/api/v1/bot-group/$GID/health | jq '.coordination.options'
```
Expect the option the **crowd over-fills** to show a **lower `crowdAdjustedBudget`**
(fleet backs off — possibly toward 0) and options the crowd under-fills to show a
**higher** one, with `currentAggregateStake <= maxAggregateStakePerRound` always
holding. Contrast against `crowdAwareCoordination=false` on the same game (budget
tracks pure affinity, ignores the crowd) — the difference is the WP's proof.

**7. Count semantic never corrupts steering (AD-C5).** Set the game's
`crowdCountSemantic` to the *wrong* value (e.g. `PLAYERS` when it is really `BETS`)
and confirm the `crowdAdjustedBudget` values are **unchanged** versus `UNKNOWN` (v1
steers on `v`, not `bc`). This proves the fail-safe: a mis-set semantic is an
observability-only error, not a steering error.

**8. Off path is unchanged (AD-C6).** A group with `crowdAwareCoordination=false`
(coordination on) behaves byte-for-byte as BET_COORDINATION: `crowdAware=false` on
health, no crowd DEBUG/TRACE lines, internal-tier budgets. A group with
`coordinationEnabled=false` has `.coordination == null`. This is the only
regression-sensitive surface beyond the universal smoke test.

Plan written: docs/plans/CROWD_AWARE_COORDINATION.md
Ready for user approval before Dev begins.
