# QA — TAI_XIU_BOT AD-13 (single-entry-per-round lock + 1-based eids)

**Verdict:** PASS
**Build:** `mvn -o test` → 894 tests, 0 failures, 0 errors

Scope: the AD-13 change on `feat/taixiu-single-entry-lock`
(`git diff main..feat/taixiu-single-entry-lock`). Production diff:
`BettingMiniGameBot.decideBet(BetContext)` seam (default = identity) routed
through by both betting call sites (`betCondition()` parks, `bet()` pops/re-derives),
and `TaiXiuGameBot.decideBet` enforcing the single-entry lock derived from
`memory.snapshotCurrentRoundBets()`, plus the 1-based `TAI_EID=1` / `XIU_EID=2`
constants.

## Tests added / updated

`src/test/java/com/vingame/bot/domain/bot/core/TaiXiuGameBotSingleEntryLockTest.java`
— 5 new cases added on top of the 5 Dev-authored ones (class now 10 tests):

- `reDeriveFallbackHonorsLock` — drives the `bet()` supplier with `pendingDecision`
  cleared (emulating the beforeReconnect race that wipes the parked value). Proves
  the re-derive fallback path also honors the lock and preserves the strategy amount.
  This is the path the Dev `placeBet()` helper never exercised (it always pops a
  parked value).
- `parkAndReDeriveAgreeWithinRound` — for the SAME tick, compares the entry parked
  by `betCondition()` against the entry produced by the re-derive supplier; pins
  they are identical (both read the same round memory → cannot disagree).
- `lockHoldsAcrossManyBets` — 1 first bet + 5 further ticks, strategy flipping side
  and doubling the stake every tick (martingale-on-the-wrong-side). Every emitted
  bet stays on the locked entry; every strategy amount is preserved verbatim.
- `staleLockDoesNotLeakAcrossStartGame` — bets round 1 (locks Tài), parks a stale
  remapped decision, fires `onStartGame` with a new sessionId (which intentionally
  does NOT clear `pendingDecision`), and asserts the round-2 first bet is free
  (strategy's Xỉu emitted). Guards the sessionId-change branch.
- `defensiveMultiKeyMapLocksToFirstEntry` — forces the should-never-happen state
  where both entries are recorded in the in-flight round; asserts the lock still
  resolves to a 1-based id in {1,2} and never collapses to 0/null.

## Coverage of the diff

- `BettingMiniGameBot.decideBet` (identity default) ← `BettingMiniGameBotMultiEntrySeamTest`
  (Dev): a BettingMini bot bets 3 distinct options in one round — confirms the lock
  did not leak into the base class. No regression in multi-entry betting.
- `BettingMiniGameBot.bet()` re-derive branch (`decideBet(buildBetContext())`) ←
  `reDeriveFallbackHonorsLock`, `parkAndReDeriveAgreeWithinRound` (new). This is the
  riskiest seam call site and is now exercised end-to-end with the lock active.
- `BettingMiniGameBot.betCondition()` park branch ← all `placeBet()`-based tests.
- `TaiXiuGameBot.decideBet` / `lockedEntryThisRound`:
  - first-bet pass-through ← `firstBetUnconstrained` (Dev)
  - lock holds both directions ← `lockHoldsStartingTai/Xiu` (Dev), `lockHoldsAcrossManyBets` (new)
  - per-round reset ← `lockResetsOnNewRound` (Dev), `staleLockDoesNotLeakAcrossStartGame` (new)
  - defensive multi-key iterator branch ← `defensiveMultiKeyMapLocksToFirstEntry` (new)
- `TAI_EID=1` / `XIU_EID=2`, no eid-0 leak ← `emittedEidsAreOneBased` (Dev) +
  `notZero` assertions in the new defensive test.

## Risk assessment (the behaviors flagged for focus)

1. **Park/re-derive race** — Cannot disagree. Both call sites re-read the same
   `BotMemory.snapshotCurrentRoundBets()`, and the lock is set only by
   `recordBetSent` (which runs in `bet()` AFTER the entry is chosen), so within a
   round both paths converge on the same locked entry. Now covered by two new tests.
2. **Many bets in one round** — Holds; verified to 6 bets with adversarial flipping
   and martingale increase on the locked side.
3. **Per-round reset** — Exact. The lock derives from the round bet map, which
   `beginRound` clears on every new sessionId; the deliberately-uncleared
   `pendingDecision` does NOT cause a stale-lock leak (the parked value is replaced
   by the next condition tick, and the lock derivation reads the freshly-cleared
   map). Verified.
4. **1-based invariant** — Emitted eid is always in {1,2}, never 0, including the
   default option-affinities path (`numberOfOptions(2)` → `{1:1,2:1}`) and the
   defensive multi-key path.
5. **No BettingMini regression** — Seam is identity for the base class; multi-entry
   per round still works.
6. **First bet unconstrained / defensive multi-key** — Both covered.

## Gaps

- True concurrency (two threads actually interleaving `betCondition()` and `bet()`
  on the netty/scenario boundary) is not driven with real threads — the tests model
  the race deterministically by manipulating `pendingDecision` directly. This is the
  correct trade-off for a deterministic suite; the underlying safety argument
  (shared memory, lock set post-choice) is structural, not timing-dependent.
- `recordBetSent` drops bets on sessionId mismatch (its own WARN path) — out of
  AD-13 scope; covered by existing BotMemory tests.

## Failures

None.
