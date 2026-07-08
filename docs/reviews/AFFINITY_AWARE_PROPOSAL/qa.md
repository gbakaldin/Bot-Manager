# QA — AFFINITY_AWARE_PROPOSAL

**Verdict:** PASS
**Build:** `mvn clean install` → 1415 tests, 0 failures, 0 errors (baseline pre-branch 1388; branch adds ~27 tests to reach 1415). Packaging `BUILD SUCCESS`.

## Tests added / updated

None added by QA. Dev's Phase-1/2/3 test suite already covers every case the
brief asked me to verify or fill; there were no gaps to close. Verified and
re-ran the existing coverage below.

## Coverage of the diff

- `WeightedOptionPicker.java` ← `WeightedOptionPickerTest.java` — skewed `{0:5,1:1}`
  distribution (option 0 ≈ 5/6), negative-clamp (never picked), equal weights =
  exactly one `nextInt(n)` draw with bound = Σw asserted + uniform distribution,
  seeded determinism (same seed → same 100-pick sequence), single-key returns that
  key, all-zero uniform fallback + exactly-one-`nextInt(keys)` draw + exactly-one
  WARN (log4j appender capture), null/empty → `IllegalArgumentException`, and full
  `weightsAreEqual` classification (single, identical, skewed, all-zero, negatives-
  clamp-equal, empty, null).
- `AffinityOptionPicker.java` (refactored to delegate) ← `AffinityOptionPickerTest.java`,
  `MartingaleStrategySupportTest.java`, `MartingaleEndToEndTest.java` — all pass
  verbatim with no assertion changes, confirming the delegation is behavior- and
  RNG-stream-preserving.
- `RandomBehaviorStrategy.java` (AD-3 branch) ← `RandomBehaviorStrategyTest.java` —
  `$Equivalence` (off path byte-for-byte vs legacy `options.get(rng.nextInt(n))`
  replay), `$AffinityWeighted.toggleOnEqualWeightsIsUniform` (toggle-on + default
  `{i:1}` weights byte-identical to toggle-off at same seed over 40 ticks),
  `$AffinityWeighted.skewedWeightsBias` (option 0 share ≈ w0/(w0+n-1) = 5/10, and
  materially > uniform 1/6).
- `BetContext.java` / `BettingMiniGameBot.java` — exercised through the strategy
  tests via the new canonical + terse (default-false) constructors.
- `BotGroup`/`BotGroupDTO`/`BotGroupMapper` ← `BotGroupMapperTest.java` — toDTO
  emit, toEntity persist, toEntity null-defaults-false, PATCH full-replace, PATCH
  null-keeps-existing.
- `BotGroupBehaviorService.createSingleBot` gating ← `BotGroupBehaviorServiceTest.java` —
  BETTING_MINI sets flag, TAI_XIU sets flag, SLOT leaves it false even when the
  group leaks `affinityWeightedProposal=true` (AD-7 guard).
- TaiXiu single-entry-lock composition ← `TaiXiuGameBotSingleEntryLockTest.java`
  (10 tests) — unchanged by this branch and green; weighted pick chooses the
  locking entry, lock still constrains one entry/round.

## RNG-invariant confirmation (Priority-1, AD-3)

The load-bearing regression property holds and is airtight:

- **Off path (flag false):** `RandomBehaviorStrategy.decide` runs the unchanged
  `else` branch — `List.copyOf(affinities.keySet()).get(rng.nextInt(n))`, one draw,
  same bound. Pinned byte-for-byte by `$Equivalence` (skip=0 and skip=40 variants).
- **Toggle-on + EQUAL weights:** `weightsAreEqual` short-circuits to the same `else`
  branch. `toggleOnEqualWeightsIsUniform` asserts the flag-on run is byte-identical
  to the flag-off run at the same seed — this test is real and meaningful (two
  independent memories/strategies, same seed, output list equality). Confirmed.
- **Only toggle-on + SKEWED weights** draws differently (`picker.pick` → one
  `nextInt(Σw)`). No existing pin reaches this path: `$Equivalence` runs flag-off,
  the Martingale `*DeterministicStream` pins never touch `RandomBehaviorStrategy`,
  and the delegation keeps `AffinityOptionPicker`'s draw byte-for-byte
  (`nextInt((int)Σw)` over the same insertion-order key view). Verified all four
  `MartingaleEndToEndTest$*DeterministicStream` classes (Classic/Paroli/DAlembert/
  Fibonacci) and `AffinityOptionPickerTest` pass verbatim — the extraction did not
  shift Martingale's RNG stream.

## Gaps

None material.

- Live-round / staging behavior (option histogram shift under skewed affinities,
  coordinator floor relaxation, TaiXiu default-unchanged) is deferred to the plan's
  `## Verification` section — integration-only, requires a skewed-affinity game on a
  reachable environment, out of scope for the unit/component suite.
- The `int` cast on Σw (`WeightedOptionPicker.pick`) is documented as safe for
  realistic small-int affinity sums (≤ ~10 options); not defended by a test because
  real weights cannot overflow. Acceptable per plan Implementation Notes.

## Failures

None. The stack trace visible in `mvn` stdout is the deliberately-thrown
`RuntimeException("intentional — captures only")` injected into the mocked
`botFactory.createBot` by the `BotGroupBehaviorServiceTest` ArgumentCaptor tests —
those tests pass; Spring merely logs the induced failure.

## Residual risk

Low. The change is a behavior-preserving refactor (P1) plus a strictly opt-in,
double-gated (flag AND skewed-weights) new path (P2/P3). On every path an operator
can reach today — flag off, or flag on with the default equal weights that all
current games ship — RNG consumption is byte-for-byte identical, so no live group
changes behavior until an operator both flips the flag and skews a game's
`optionAffinities`. Determinism is fully pinned; no live-bot reflection harness or
scheduler was touched, so no flakiness exposure.
