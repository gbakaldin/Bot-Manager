# QA — JACKPOT_SCALE_AND_RAMP

**Verdict: PASS**

Branch `feat/jackpot-scale-ramp` (7 phases J1–J4 + R1–R3) implementing
`docs/plans/JACKPOT_SCALE_AND_RAMP.md`.

> Note: the QA subagent run terminated on an infrastructure error
> (`ConnectionRefused`) after applying its test changes but before committing or
> writing this verdict. The main agent validated the left-behind changes
> (full suite + flake-stability) and authored this file. All results below were
> re-run and confirmed post-hoc.

## Flaky test — root-caused and fixed

`BettingMiniGameBotRampSeamTest.deferredTickDoesNotConsultStrategy` failed
intermittently (measured **7 pass / 1 fail over 8 isolated runs**, ~12%).

**Two contributing causes, both in the reflection harness (not production code):**
1. **Primary — RNG re-seed ordering.** The harness called `bot.setRandom(rng)`
   *before* `initializeSubclass()`, but `initializeSubclass()` re-seeds `this.rng`
   with a fresh nondeterministic `Random`, discarding the injected one. So
   `betCondition()` drew from a real RNG instead of the test's `CountingRandom` /
   always-defer `Random`, and the forced-defer assertion flipped ~1-in-4. Fix:
   install `setRandom(rng)` **after** `initializeSubclass()`.
2. **Secondary — live scheduler race.** `onStartGame` starts a countdown
   scheduler that decrements `remainingTime` every 1000ms on a background virtual
   thread, which could mutate `remainingTime` (→ `elapsedFraction`/`pAccept`)
   between the test's `set()` and `condition.get()`. Fix: `freezeSchedulers()`
   (shutdown + await + null the countdown ref) right after construction/onStartGame.

The same harness pattern in `BettingMiniGameBotJackpotScaleSeamTest` was hardened
identically (belt-and-suspenders; its effective-cap assertions read the
`onStartGame`-snapshotted `currentJackpotFactor`, not `remainingTime`, so it was
not racy in practice).

**Production code was not touched** — the ramp/`rampAccepts()` logic is
deterministic and correct; this was purely a test-harness defect.

**Stability proof:** `BettingMiniGameBotRampSeamTest` +
`BettingMiniGameBotJackpotScaleSeamTest` run **12/12 green** in isolation after the fix.

## Added coverage (additive; no pin weakened)

- `RandomBehaviorStrategyTest.effectiveCapGovernsWhenScaledDown` — asserts the
  jackpot lever: a scaled-down `effectiveMaxBetsPerRound=2` caps the round below
  the configured max (8). The RNG-order regression pin
  (`RandomBehaviorStrategyTest$Equivalence`) is unchanged and green.
- `BotGroupBehaviorServiceTest` — ~100 lines of additional wiring/health coverage
  for the scaler build/inject guard and the health blocks.
- Harness de-flake in the two seam tests (above).

## Full suite

`mvn clean install` (JDK 21) — **Tests run: 1388, Failures: 0, Errors: 0,
Skipped: 0. BUILD SUCCESS.** (Baseline before branch ~1328; branch adds ~55 net,
plus QA additions.)

## Residual notes (non-blocking)

- Two advisory smells from the Reviewer remain open as documented follow-ups (not
  QA-blocking): `JackpotScaler.observePool` doesn't short-circuit the `sid==0`
  sentinel the way `BetCoordinator` does; `timeForBetting` is read cross-thread in
  `rampAccepts` without `volatile` (pre-existing sharing, self-correcting).
