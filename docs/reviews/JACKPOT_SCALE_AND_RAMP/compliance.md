# Compliance — JACKPOT_SCALE_AND_RAMP

Branch: `feat/jackpot-scale-ramp` (HEAD `296072e`)
Plan reviewed: `docs/plans/JACKPOT_SCALE_AND_RAMP.md` (at commit `296072e`, incl. the
mid-flight Tai-Xiu-supported course-correction: AD-J2/AD-J3/AD-S1 amended so the
eligible set is `{BETTING_MINI, TAI_XIU}` and `tJpV` is parsed on the shared
`TaiXiuEndGameMessage`).
Diff reviewed: `git diff main..feat/jackpot-scale-ramp` (base `4afd19c`)

## Verdict

PASS

## Phase-by-phase

### Phase J1 — Jackpot pool marker (message layer)
Status: implemented
Notes: New `HasJackpotPool { long jackpotPool(); }` with javadoc explicitly marking
it DISTINCT from `HasJackpot` (running meter `tJpV` vs per-bot payout `jpV`/`iJp`).
Implemented on `TipEndGameMessage`, `BomEndGameMessage`, `B52EndGameMessage`,
`NohuEndGameMessage` (each `return tJpV;`) **and on the shared
`TaiXiuEndGameMessage`** — the amended AD-J2. `tJpV` was added as the final
`@JsonProperty("tJpV")` constructor param + field on the shared class (no jackpot
subclass; `JackpotTaiXiuMessageTypes.endGameType()` untouched), so P_114 gets a live
meter and plain P_116 defaults it to `0` → neutral. The per-win path is untouched:
`jackpotFor` still reads `jpV`/`iJp` (Tip/TaiXiu) or `iJp?tJpV:0` (G2/G4) exactly as
pre-branch. No confusion of the two values. The two existing TaiXiu tests were
correctly updated for the new trailing `tJpV` constructor arg; `HasJackpotPoolTest`
adds positive per-product cases + the P_114 vs P_116 neutral cases.

### Phase J2 — Jackpot Game config (2 fields)
Status: implemented
Notes: Exactly two Game fields — `boolean jackpotScaleEnabled` + `long jackpotCeiling`.
`GameDTO` boxes both (`Boolean`/`Long`, PATCH-null = keep). `GameMapper` handles all
three methods (`toDTO`, `toEntity` with `orElse(false/0L)`, `updateEntityFromDTO`
replace-if-present). Validation in `GameService.validateJackpotScale`: when enabled,
`jackpotCeiling > 500000` else `BadRequestException` (soft 400). Seed floor is a
constant `JACKPOT_SEED_FLOOR = 500_000L` in the validator, NOT a Game field (AD-J6
honored). No default baked for the ceiling — operator-set only (confirmed decision 3).

### Phase J3 — Jackpot scaler core + wiring
Status: implemented
Notes: `JackpotScaler` in `com.vingame.bot.domain.bot.coordination` — scope-agnostic,
no Spring / `BotGroup` / `BotGroupRuntime` import (AD-S2). Constructor
`(long ceiling, long seedFloor, double minMultiplier)`; `DEFAULT_SEED_FLOOR = 500_000L`.
Transfer function is AD-J5 verbatim (fail-safe-to-1.0 when unobserved / degenerate
`ceiling<=seedFloor`; clamped linear seed→ceiling otherwise; `f=m` at floor, `f=1.0`
at ceiling). Raw `0` treated as not-observed (`observed` flag only trips on `pool>0`)
— never throttles to floor. First-seen idempotent per sid under a `ReentrantLock`
(virtual-thread-safe); `getCurrentFactor()` lock-free volatile read; `snapshot()` for
the DTO. DEBUG line format matches AD-J10.
Wiring: `BotGroupRuntime` nullable `jackpotScaler`; `Bot.setJackpotScaler(...)` fluent
null-tolerant mirror of `setCoordinator`. `onEndGame` reads the meter via the
`HasJackpotPool` marker AFTER existing dispatch (distinct from the `HasJackpot` block).
`onStartGame` snapshots `currentJackpotFactor` (one-round lag, AD-J7). Lever is
`maxBetsPerRound` attenuation only (AD-J4) — `effectiveMaxBetsPerRound` threaded via a
new `BetContext` field (`max(1, round(configured × factor))`, identity when factor
1.0), read by both strategy call sites (`RandomBehaviorStrategy`,
`MartingaleStrategySupport`) instead of `behavior.getMaxBetsPerRound()`. NOT
`betSkipPercentage`, NOT bet amount. `start()` builds the scaler under
`jackpotScaleEnabled && (BETTING_MINI || TAI_XIU)` — same guard as ramp, no per-type
skip-log (AD-J3/AD-S1). `minMultiplier=0.25` passed at construction (confirmed
decision 2, constant not a field). Injected before `startBot` like the coordinator.
AD-J9 coordinator-cap composition is explicitly DEFERRED with an in-code comment — not
silently implemented differently.

### Phase J4 — Jackpot read-side observability
Status: implemented
Notes: `JackpotScaleStateDTO` has exactly the AD-J10 shape
(`enabled, jackpotCeiling, seedFloor, lastObservedPool, currentFactor, minMultiplier`).
Nullable `jackpotScale` on `BotGroupHealthDTO`, populated from
`runtime.getJackpotScaler().snapshot()` via `buildJackpotScaleState` (returns `null`
when the scaler is null). `getHealth` returns early for non-running groups, so the
block is absent when not running — matching the coordination precedent. Not on any
write DTO or on `stats`.

### Phase R1 — Ramp BotGroup config (2 fields)
Status: implemented
Notes: Exactly two BotGroup fields — `boolean rampEnabled` + `double rampShape`.
`BotGroupDTO` boxes both. `BotGroupMapper` handles all three methods. `BotBehaviorConfig`
carries `rampEnabled`/`rampShape`. `createSingleBot` sets them only for
`BETTING_MINI`/`TAI_XIU` (guard; SLOT etc. keep builder defaults) — AD-R4/AD-R6.
Validation `rampShape > 0` when `rampEnabled` lives in `BettingGridRules` (soft 400).

### Phase R2 — Ramp bet-path seam
Status: implemented
Notes: `rampAccepts()` is a pure per-bot stateless method — NO `RampController` on the
runtime (AD-R2). Power curve exact: `pAccept = pMin + (1-pMin)·x^k`, `pMin = RAMP_P_MIN
= 0.15` class constant (confirmed decision 4), `k = rampShape`,
`x = clamp01(1 - remainingTime/timeForBetting)`. Off path (`!rampEnabled ||
rampShape<=0`) returns `true` and draws NO RNG (AD-R5); `window<=0` fail-safe returns
`true`. Gate runs in `betCondition()` AFTER `canBet()`/`strategy==null` and BEFORE
`decideBet`/coordination, so a deferred tick touches neither the strategy counter nor a
coordinator reservation (AD-R1). `RandomBehaviorStrategyTest$Equivalence` (the pinned
RNG-consumption-order test) still passes — confirming the off path is byte-for-byte
today's.

### Phase R3 — Ramp read-side observability
Status: implemented
Notes: `RampStateDTO { boolean enabled; double rampShape; }` — thin, per AD-R7.
Nullable `ramp` on `BotGroupHealthDTO`, populated from the group entity (single
source) via `buildRampState`, `null` when `rampEnabled` is false; absent for
non-running groups (early return). No per-round DEBUG line added (would duplicate the
5s aggregate).

## Drift

None. Every phase matches the amended plan and every stated AD is honored. The
five user-confirmed Open Decisions are all implemented as confirmed:
1. Lever = per-bot `maxBetsPerRound` attenuation — yes (`effectiveMaxBetsPerRound`).
2. `minMultiplier = 0.25` as a constant (not a field) — yes (hardcoded at scaler
   construction).
3. Ceiling operator-set-only, no default, validated `> 500000` — yes.
4. Ramp power curve `pAccept = 0.15 + 0.85·x^k`, `pMin=0.15` constant — yes
   (`RAMP_P_MIN`).
5. Jackpot config on Game, ramp config on BotGroup — yes.
Tai-Xiu-supported course-correction implemented: `tJpV` parsed on the shared
`TaiXiuEndGameMessage`, `HasJackpotPool` implemented there, eligible set
`{BETTING_MINI, TAI_XIU}` for both features, no skip-log. No stale "Tai Xiu
unsupported / gated out" framing remains in code.

## Out-of-scope changes

None. `tJpv2` / secondary tier and Subscribe-meter seeding remain unimplemented, as
the plan's Open Items require. AD-J9 coordinator-cap composition remains deferred (as
permitted). No production code outside the plan's named surfaces was touched.

## Amendments to the plan

None.

## Notes for downstream

- Full feature test suite is green (111 tests across the 7 feature suites +
  regression pins). The `## Verification` section is achievable against this diff:
  every referenced surface (Game/BotGroup PATCH round-trip fields, `Jackpot scaler
  created` INFO line, `JackpotScale sid=… pool=… factor=… ceiling=` DEBUG line, the
  `.jackpotScale`/`.ramp` health blocks with the documented shapes, `ramp deferred
  tick` TRACE line) now exists in code.
- Minor, non-blocking: the seed-floor constant is duplicated
  (`GameService.JACKPOT_SEED_FLOOR` for validation vs
  `JackpotScaler.DEFAULT_SEED_FLOOR` for the transfer function). Both are `500_000L`;
  this is a validation-path vs scaler-core split, not a drift. Not a compliance issue.
