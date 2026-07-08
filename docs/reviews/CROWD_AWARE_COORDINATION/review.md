# Code Review — CROWD_AWARE_COORDINATION

Branch: `feat/crowd-aware-coordination`
Reviewed diff: `git diff main...HEAD`

## Verdict

PASS

PASS = no `bug` or `security` findings. The smells and style below are advisory.

The load-bearing invariants hold: the crowd-off reduction is bit-for-bit the shipped
internal tier (same code path, same integer flooring), the aggregate cap remains the
hard ceiling under the flooring, the mid-round budget swap preserves `committed`, the
lock discipline is intact, and the off-path is genuinely inert.

## Findings

### [smell] EndGame `observeCrowd` mutates a round that is already finished, and never seeds the next round
`src/main/java/com/vingame/bot/domain/bot/core/BettingMiniGameBot.java:549-551`
`src/main/java/com/vingame/bot/domain/bot/coordination/BetCoordinator.java:358-398`

The onEndGame hook feeds `observeCrowd(endGameSessionId(msg), …)` — the *just-finished*
round's sid. In the coordinator, `observeCrowd` drops any observation whose sid does not
match `current.sessionId()`. At this point `current` is still the finishing round (the
swap to the next round only happens in the next `onRound`), so the observation is NOT
dropped: it recomputes and swaps the **finished** round's budget. But no more `reserve`
calls will ever consume that budget (betting is over), and on the next `onRound` the
crowd map is `clear()`ed and `current` is replaced with the fresh `targetBudget`
(`BetCoordinator.java:255-259`). Net effect: the EndGame crowd read does real work
(a lock acquisition + full recompute + `RoundBudget` allocation per bot per round) whose
result is immediately discarded and never influences a bet.

The comment at line 541-543 asserts this is "the one-round-lagged prior for products
without an intra-round signal (BOM/B52/Nohu)," but the code does not carry the EndGame
distribution across the `onRound` boundary — the plan's AD-C3 lagged-prior seed is not
actually realized. Runtime impact is benign (no wrong bets, cap still enforced), so this
is a smell, not a bug: for Tip it is a redundant recompute, and for BOM/B52/Nohu it is
the *entire* intended crowd signal silently going nowhere. Fix shape: either drop the
EndGame `observeCrowd` call (and the misleading comment) since it is a no-op in effect,
or implement the lagged prior explicitly by stashing the EndGame distribution and
applying it in the next `onRound` (guarded so it never inflates spend). Flagging so the
"BOM has crowd steering" claim isn't taken at face value.

### [smell] Crowd snapshot is last-write-wins, not monotonic-by-arrival
`src/main/java/com/vingame/bot/domain/bot/coordination/BetCoordinator.java:369-378`

AD-C3 specifies the observation should be "monotonic-by-arrival (a later, larger crowd
snapshot supersedes an earlier one)." The implementation unconditionally `clear()`s and
replaces the stored crowd map with whatever the latest caller passed. All N bots receive
the same UpdateBet frame on their own IO threads and each calls `observeCrowd` with the
same sid; if a slow bot's frame-N (smaller aggregate) is processed after a fast bot's
frame-(N+1) (larger aggregate), the older/smaller snapshot overwrites the newer one until
the next frame arrives. Because `v` is a running server-side aggregate and the next frame
corrects it, this is a transient, self-healing glitch — the budget momentarily loosens
then re-tightens. Correctness of the cap is never affected. Fix shape (if you want the
stated monotonicity): only replace when the incoming `Σ v` exceeds the stored `Σ v` for
the current sid, or track a per-sid frame/sequence high-water mark. Low priority given
the self-correction.

### [smell] `computeCrowdBudget` javadoc for the `crowdStake` param is garbled
`src/main/java/com/vingame/bot/domain/bot/coordination/BetCoordinator.java:203-209`

The `@param crowdStake` block trails off into an unfinished thought ("…but still counted
into `X_total` only if present in the map keyed by an affinity option — see below;
unknown-eid crowd is filtered by the caller…"). The behavior it tries to describe is
actually correct and simpler than the prose: the method only sums/uses keys that are in
the affinity set (`affinities.keySet()` / `affinities.entrySet()` drive both loops), so
any non-affinity key in `crowdStake` is inert here regardless of the caller's filtering.
The caller (`observeCrowd:373`) *also* filters unknown eids, so the defense is doubled and
safe. Tighten the javadoc to match the two-loops-over-affinities reality; the "see below"
has nothing below it.

### [style] Import ordering: `HasCrowdBets` placed out of alphabetical order
`src/main/java/com/vingame/bot/domain/bot/core/BettingMiniGameBot.java:28`
`src/main/java/com/vingame/bot/domain/bot/message/g3/tip/TipEndGameMessage.java:8`

`import …message.HasCrowdBets;` is inserted after `HasJackpot` in BettingMiniGameBot
(should sort before `HasJackpot`/`HasJackpotPool`), and similarly the surrounding blocks
elsewhere keep alphabetical order. Trivial; only flagged because the rest of these import
blocks are consistently alphabetized.

## Notes

- **Crowd-off reduction identity — confirmed exact.** `computeTargetBudget` now delegates
  to `computeCrowdBudget(affinities, cap, Map.of())` (`BetCoordinator.java:180-182`), so
  the internal-tier budget and the `X=0` crowd budget are literally the same code with the
  same `weight * combinedPool / totalWeight` integer floor and the same iteration order.
  With an empty crowd map `combinedPool == cap`, the subtraction term is 0, and the clamp
  is a no-op — bit-for-bit `floor(w·cap/W)`. An unobserved or crowd-off coordinator is
  today's behavior. This is the strongest part of the change.

- **`Σ B_crowd` can exceed `C` under the clamp — and that is fine.** When the crowd
  over-fills an option, its `B_crowd` clamps to 0 (dropping a negative term), which can
  push the per-option sum above `cap` (e.g. 3 equal options, cap 90, all crowd on one
  option → budgets 0/60/60, sum 120). This does not breach the fleet cap: `reserve`
  still clamps every commit against `remainingAggregate()` (`RoundBudget.java:99-101`),
  which is the hard `cap` ceiling. The per-option budget is a soft target; the aggregate
  is the invariant, and it holds. Matches AD-C2's "re-normalize only downward via the
  aggregate cap."

- **Integer overflow of `P(o)·(X_total+C)` — theoretically possible, practically not.**
  `weight * combinedPool` (`BetCoordinator.java:231`) is a `long` multiply of a small int
  affinity weight by `crowdTotal + cap`. Overflow needs `combinedPool > Long.MAX/weight`,
  i.e. crowd pools near 9.2e18 / weight. Real crowd pools are many orders below that. Not
  worth a guard; noting for completeness since it was called out in the brief.

- **Map-ordering caveat — correctly handled.** `snapshot()` (`BetCoordinator.java:518`)
  and both DEBUG histograms (`:428`, `:436`) iterate the ordered `optionAffinities`
  keyset (sorted for the log, insertion-order for the DTO) and look crowd/budget up by
  key — never `RoundBudget.budget().values()` (a `Map.copyOf`, unspecified order). The
  constructor also wraps the incoming affinities in a `LinkedHashMap` (`:144`) to preserve
  source order rather than `Map.copyOf`. The caveat is respected.

- **Concurrency — clean.** `observeCrowd`, `reserve`, `onRound`, `onRoundComplete`, and
  every DTO read all serialize on the one `ReentrantLock`; `current` is `volatile`; the
  mid-round swap uses `RoundBudget.withBudget`, whose private copy-ctor carries
  `committed`/`committedAggregate` forward (`RoundBudget.java:50-56`), so the fleet's
  in-round spend survives the swap and the fleet cannot overspend `C`. `ReentrantLock`
  does not pin virtual threads. No new cross-thread hazard is introduced: the onUpdate
  `sidStore.get()` read runs on the same netty thread as the writer (`onStartGame`), and
  `observeCrowd` re-validates the sid against `current` under the lock anyway.
  (`SessionIdStore` remains a non-volatile plain holder, but that is pre-existing
  BET_COORDINATION code, not touched by this diff.)

- **Off-path identity — genuinely inert.** `observeCrowd` early-returns on `!crowdAware`
  (`:359`) before touching any state; the DTO `crowdStake`/`crowdAware`/`crowdCountSemantic`
  fields serialize as `0`/`false`/`"UNKNOWN"`; a non-coordinated group builds no
  coordinator at all. The 4-arg constructor still delegates with `crowdAware=false`, so
  BET_COORDINATION call sites and tests are untouched.

- **Marker impls — correct per shape.** Tip's three frames map `BetInfoWithTotal
  {b,eid,bc,v}` → `CrowdOption(eid, v, b, bc)`; BOM/B52/Nohu EndGame map `BetInfo
  {eid,bc,v}` (no `b`) → `CrowdOption(eid, v, 0L, bc)`; all null-guard `bs`. Tai Xiu
  implements nothing (verified: no Tai Xiu message class references `HasCrowdBets`), so
  its onUpdate/onEndGame branches never fire — the AD-C7 no-op. The bot dispatch guards
  both call sites with `coordinator != null && msg instanceof HasCrowdBets`.

- **Subscribe marker is currently dead capability.** `TipSubscribeMessage` /
  `BomSubscribeMessage` / etc. implement `HasCrowdBets`, but `onSubscribe`
  (`BettingMiniGameBot.java:377`) does not call `observeCrowd` — Subscribe seeding is
  deferred. Harmless (the extra `crowdBets()` method is never invoked), but the marker on
  the Subscribe classes implies a wiring that does not exist; a one-line comment on those
  overrides would prevent a future reader assuming Subscribe seeds the round.

- **Dev-flagged health gap (real, but not a defect).** AD-C10 lists three per-option
  crowd fields — `observedCrowdStake`, `crowdAdjustedBudget`, `observedCrowdCount` — but
  P4 surfaces only `crowdStake` (the pure `X(o)`) on `OptionStateDTO`; `Snapshot.
  OptionSnapshot` carries no `crowdAdjustedBudget` (`B_crowd(o)`) nor `observedCrowdCount`
  (`bc`). The adjusted budget *is* visible on the per-round DEBUG line (`adj=…`,
  `BetCoordinator.java:438`) and the count is captured internally (`crowdCount` map) but
  not exported. Since `bc` is explicitly observability-only and the DEBUG line covers the
  adjusted budget, the runtime is correct and steering is unaffected — this is a
  read-side completeness gap, not a bug. Whether to close it is a product call, not a
  code-quality blocker; the code as written is coherent.

- **Logging conforms.** The crowd view is folded into the *existing* one-per-round DEBUG
  line as a same-line `crowd=[…]` segment (not a second line), the intra-round recompute
  is TRACE-gated behind `log.isTraceEnabled()`, and the one new INFO line is a
  group-lifecycle event at `start()` — all consistent with CLAUDE.md. No tokens or
  secrets are logged.

---

## Re-review — fix commit `c6b50be` (2026-07-08)

Scope: re-check ONLY the changed areas of `c6b50be`
(`fix(crowd-aware): restore full AD-C10 per-option health set + realize AD-C3
EndGame one-round-lag`). This fix pass addresses the two smells flagged above
(EndGame no-op / non-monotonic snapshot) plus the AD-C10 health-field gap noted
in the dev-flagged Notes. Verdict unchanged: **PASS**. No new `bug` or `security`
findings. The three prior smells are now resolved; the two style items
(`HasCrowdBets` import order) are addressed for `BettingMiniGameBot`.

### New findings

None.

### What was re-verified (changed areas only)

- **Reduction identity still bit-for-bit — confirmed.** In `onRound`
  (`BetCoordinator.java:288-297`) the seed branch is gated on
  `crowdAware && !lastObservedCrowdStake.isEmpty()`; the else branch assigns
  `seededBudget = targetBudget` — the *same* precomputed internal-tier map object
  (`floor(w/W·C)`), not a recompute. `computeCrowdBudget` is never entered on the
  crowd-off / never-observed path, so the crowd-off and internal-tier rollover
  behavior is unperturbed. Covered by `endGameDoesNotSeedNextRoundWhenOff` and
  the `ReductionIdentity` nested suite.

- **Carry-forward uses the right distribution and the right baseline.** The seed
  is `lastObservedCrowdStake` — the last *accepted* (monotonic-passing) observed
  distribution, which for BOM/B52/Nohu is the round-N EndGame `bs`. It is fed to
  `computeCrowdBudget` as raw `v(o)`, correct because at round open
  `committed(o)=0 ⇒ X(o)=v(o)` (comment at `:292` matches the math). First round
  of a session: `lastObservedCrowdStake` is empty ⇒ internal tier. No stale-sid
  hazard breaches the invariant: a stale lagged prior can only mis-shape the
  *per-option* opening distribution for one round; the fleet aggregate cap is
  still enforced by `reserve` against `remainingAggregate()`, and the first fresh
  frame overwrites the seed. Session-boundary: the `lastObserved*` maps live for
  the coordinator's lifetime, but a non-coordinated / restarted group builds a
  fresh coordinator, so no cross-session leak. Covered by
  `endGameSeedsNextRoundOpeningBudget` and the `CarryForward` suite.

- **Monotonic high-water logic correct.** `currentCrowdSum` resets to `-1` in
  `onRound` (`:287`); the gate `if (incomingSum <= currentCrowdSum) return;`
  (`:428`) means the first fresh frame of the round (sum `≥ 0 > -1`) always
  overwrites the lagged seed *even if its Σv is smaller* — the gate compares
  against `-1`, not against the seed's magnitude (the seed never sets
  `currentCrowdSum`). An all-zero opening snapshot (Σv=0 > -1) applies and
  correctly collapses the crowd map back to the internal tier. Because `v` is a
  server-side running aggregate that only grows within a round, strictly-greater
  never drops a legitimately-growing frame; only a reordered straggler
  (`Σv ≤` mark) is dropped, which is the intent. Covered by the
  `MonotonicByArrival` suite.

- **Health fields.** `snapshot()` (`:575-617`) iterates the ordered
  `optionAffinities.entrySet()` and reads every field by key — never the crowd /
  budget map `values()`. `crowdAdjustedBudget` = `b.budgetOf(optionId)` reads the
  in-flight `RoundBudget` (`b = current`, captured once under the lock).
  `observedCrowdStake` = raw `v(o)` from `crowdStake`; `crowdStake` (pure X) =
  `max(0, v − committed)`. `observedCrowdCount` is gated by
  `countKnown = crowdAware && !"UNKNOWN".equals(crowdCountSemantic)` → `0` on the
  UNKNOWN / crowd-off semantic, matching AD-C5's obs-only, semantic-gated rule.
  Covered by the `HealthFields` suite. DTO / builder wiring
  (`CoordinationStateDTO`, `BotGroupBehaviorService:965-967`) is a straight
  field pass-through, no ordering or null hazard.

- **No torn read.** All of `snapshot()` runs under one `lock.lock()` acquisition;
  every value read (`current`, `crowdStake`, `crowdCount`, `crowdCountSemantic`,
  `crowdAware`, `targetBudget`) is either `final` or mutated only under the same
  lock. No read escapes the lock.

- **Concurrency of the new mutable state — clean.** `lastObservedCrowdStake`,
  `lastObservedCrowdCount`, and `currentCrowdSum` are read and written
  exclusively inside `onRound` / `observeCrowd`, both fully under the existing
  `ReentrantLock`. No `volatile` is needed (they are never touched outside the
  lock) and no new cross-thread hazard is introduced. `ReentrantLock` still does
  not pin virtual threads.

- **Local verification.** `BetCoordinatorCrowdTest` (28) +
  `BettingMiniGameBotCrowdSeamTest` (6) run green under Java 21.
