# Code Review — JACKPOT_SCALE_AND_RAMP

Branch: feat/jackpot-scale-ramp
Reviewed diff: `git diff main...HEAD`

## Verdict

PASS

PASS = no `bug` or `security` findings; the two `smell` items below are advisory.

## Findings

### [smell] `observePool` does not short-circuit on the `sessionId == 0` sentinel

`src/main/java/com/vingame/bot/domain/bot/coordination/JackpotScaler.java:70`

The first-seen guard is `if (sessionId != 0L && sessionId == lastObservedSessionId) return;`.
The shipped sibling it mirrors, `BetCoordinator`, treats a `0` sid as a no-op at
the top of both `onRound` (`BetCoordinator.java:113`) and `reserve`
(`onRoundComplete` guards on `b.sessionId() == 0L`). `JackpotScaler.observePool`
instead falls through for `sessionId == 0`: it sets `lastObservedSessionId = 0`,
recomputes the factor, and emits the AD-J10 DEBUG line on *every* bot's call for
that malformed round (N lines instead of 1), because the `sessionId != 0L`
short-circuit never fires when the incoming sid is 0.

Failure scenario: a game emits (or a parse yields) an EndGame with `sid=0` — the
scaler logs N DEBUG lines for the round instead of one, and momentarily records
`lastObservedSessionId = 0`, meaning a *subsequent* real round that also happened
to carry `sid=0` would not be deduped. This is not a correctness bug for the pool
math (recompute is idempotent and `pool > 0` still gates the `observed` flag), and
`endGameSessionId` today returns the real server sid, so the path is not exercised
in practice — hence smell, not bug. Fix shape: add `if (sessionId == 0L) return;`
as the first statement under the lock, matching `BetCoordinator`'s sentinel
handling, so the 0-sid case is a clean no-op with no log spray.

### [smell] `timeForBetting` read cross-thread without `volatile` in `rampAccepts`

`src/main/java/com/vingame/bot/domain/bot/core/BettingMiniGameBot.java:774`

`rampAccepts()` reads the plain (non-`volatile`) `long timeForBetting` on the
scenario thread; the field is written on the netty thread in `onSubscribe`
(`:381`). The codebase leans hard on the netty-writes / scenario-reads split and
elsewhere marks such fields `volatile` (e.g. `currentJackpotFactor` added in this
very diff, `:108`, and `gameState`, `:87`). A `long` read is not guaranteed
tear-free by the JLS without `volatile`, and there is no happens-before edge here.

In practice this is pre-existing: `timeForBetting` is *already* read cross-thread
by the countdown scheduler (`startRemainingTimeCountDown` → `remainingTime.set(timeForBetting)`)
on main, so R2 does not introduce a new class of hazard — it adds a second reader
of an already-shared plain field. Worst case a bot uses a stale/half-updated
`window` for one tick of `pAccept`; the value is clamped `[0,1]` and the effect is
one slightly-mis-weighted accept probability, self-correcting next tick. Failure
scenario is therefore cosmetic, not a lost/duplicated bet. Fix shape (advisory,
and arguably belongs to a separate cleanup since it predates this branch): mark
`timeForBetting` `volatile` to match `currentJackpotFactor`/`gameState` and make
the cross-thread contract explicit.

## Notes

Strong, low-risk change. Specific things verified and worth calling out as done right:

- **Off-path identity (AD-S3) holds.** With both features off: `jackpotScaler`
  stays null so `effectiveMaxBetsPerRound` short-circuits on `factor == 1.0` and
  returns `behavior.getMaxBetsPerRound()` unchanged; the `BetContext` convenience
  constructor defaults `effectiveMaxBetsPerRound` to `behavior.getMaxBetsPerRound()`;
  and `rampAccepts()` returns `true` *before* any `rng` touch on the
  `!rampEnabled || rampShape <= 0` path — so the strategy RNG stream is byte-for-byte
  preserved. The `rng.nextDouble()` draw is confined strictly to the ramp-on branch.

- **Both strategy readers switched.** `RandomBehaviorStrategy` and
  `MartingaleStrategySupport` both now read `ctx.effectiveMaxBetsPerRound()`;
  a `git grep` of `getMaxBetsPerRound()` under `strategy/` finds only javadoc and
  the convenience-constructor default — no missed live reader.

- **Transfer function is sound.** `computeFactor` fails safe to neutral `1.0` on
  `!observed` *and* on degenerate `ceiling <= seedFloor`; `t` is clamped `[0,1]`;
  raw `0` pool never trips `observed`, so a meterless Tai Xiu with the flag on
  degrades to neutral rather than throttling to the floor. The `max(1, round(...))`
  lever floors at one bet and cannot overflow int (factor ≤ 1.0, `configured` is int).

- **Concurrency mirrors `BetCoordinator` correctly.** Single `ReentrantLock`
  (VT-safe, no pinning), volatile factor for the lock-free scenario-thread read,
  paired write of `lastObservedPool`/`currentFactor`/`observed` under the lock,
  first-seen dedup keyed on sid, and `snapshot()` reads the whole view under one
  lock acquisition so the health DTO never sees a torn state. No logging under the
  lock (the DEBUG line is emitted after `unlock`).

- **Seam ordering is right.** The ramp gate runs *before* `decideBet`/coordination
  in `betCondition` (`:806`), so a deferred tick reserves nothing and does not touch
  the strategy's per-round counter. `observePool` is hooked in `onEndGame` behind an
  `instanceof HasJackpotPool` guard (null-safe). The factor is snapshotted once per
  round in `onStartGame` into a volatile, giving the intended one-round lag (AD-J7).

- **Logging levels conform to CLAUDE.md.** Scaler creation is group-lifecycle INFO
  (matches the coordinator line), the per-round pool fold is one DEBUG line on the
  first-seen path, and the per-tick ramp deferral is TRACE. No token logging and no
  per-frame flood introduced.

- **Validation and PATCH semantics are consistent.** `GameService.update` routes
  through `save`, so `validateJackpotScale` (ceiling > 500k when enabled) applies to
  PATCH as well as create; the `BettingGridRules` rampShape>0 rule only bites when
  `rampEnabled`; both mappers use the boxed PATCH-null-keeps convention already
  established for coordination/activation fields.

Question for the author (non-blocking): the `minMultiplier = 0.25` seed floor and
`RAMP_P_MIN = 0.15` are hardcoded at the wiring/class-constant level rather than
config-driven. That is per AD-R3/AD-J and fine for this ship; just flagging that
tuning them later means a code change, not an operator PATCH.
