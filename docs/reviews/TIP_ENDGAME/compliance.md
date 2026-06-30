# Compliance — TIP_ENDGAME

Branch: `feat/tip-endgame`
Plan reviewed: `docs/plans/ENDGAME_METRICS.md` (last amended 2026-06-08)
Diff reviewed: `git diff main..feat/tip-endgame` — four feature commits in scope:
- `b5a8d52` test(tip): Jackson fixture coverage
- `4180bc0` feat(tip): implement `HasBotWinnings`, `HasJackpot`, `HasBetTotals` on `TipEndGameMessage`
- `0a029b9` refactor(metrics): Phase B — move bet counters off `creditBalance`
- `17c8d50` refactor(bot): Phase C — delete obsolete capability hooks

## Verdict

PASS

`mvn test` green at HEAD (438 tests, 0 failures). All plan-mandated checks (`grep -rn "incBetPlaced" src/main/java src/test/java` → 0, `grep -rn "incBetsPlaced" src/main/java` → definition + single caller + Javadoc reference, `grep -n "getWinnings|getJackpot|canCheckTotalWinnings|getTotalWinnings|getRoundTotalBetAmount" BettingMiniGameBot.java` → only the documentation-of-removal comment, `grep -rn "HasRoundTotals|incGameTotal|GAME_TOTAL_"` → 0) hold at HEAD.

## Phase-by-phase

### Phase A — Marker interfaces + `instanceof` dispatch
Status: implemented (already shipped on `feat/observability-metrics`, in `main` before this branch).
Notes: no rework on this branch; the dispatch in `BettingMiniGameBot.onEndGame` at lines 195-216 is the Phase A code with Phase C's cleanup applied.

### Phase A.5 — Drop `HasRoundTotals` + game-aggregate counters
Status: implemented (shipped on `feat/observability-metrics`, in `main`).
Notes: `grep` for `HasRoundTotals|incGameTotal|GAME_TOTAL_` returns zero across `src/main/java` and `src/test/java`.

### Phase B — Move bet counters from `Bot.creditBalance` to `HasBetTotals` dispatch
Status: implemented (commit `0a029b9`).
Notes: Diff against `Bot.java:574-579` is surgical — only the `if (metrics != null) metrics.incBetPlaced(amount);` line is replaced with a multi-line explanatory comment. The three AtomicLong accumulators (`expectedCurrentBalance`, `totalBetsPlaced`, `totalBetAmount`) are preserved verbatim, satisfying AD-4. The `HasBetTotals` dispatch arm in `BettingMiniGameBot.onEndGame:210-213` was already present from Phase A — no new code added there.

Phase B prerequisite check: the plan's AD-4 / AD-8 require at least one production implementer of `HasBetTotals` before Phase B ships, or the moved counters go silent in production. Commit `4180bc0` (Tip implementation) precedes commit `0a029b9` (Phase B) on the branch — verified via `git log --oneline main..HEAD`. After deploy, `bot_bets_placed_total` / `bot_bet_amount_total` continue to grow for Tip groups while reading 0 for Bom/B52/Nohu — exactly the behaviour the plan endorses (AD-8 + Verification step 4).

Test deltas:
- `BotTest.CreditBalanceTests.shouldNotEmitMetricsCounter` (new) — asserts `verifyNoInteractions(metrics)` after `creditBalance(500)`. Matches Plan Phase B step 2 bullet 3.
- The earlier `verify(metrics, never()).incBetPlaced(anyLong())` assertions added in `0a029b9` were dropped in `17c8d50` (Phase C) when the method itself was deleted — covered by the surviving `verifyNoInteractions` contract.

### Phase C — Delete the 5 obsolete capability hooks + dead code
Status: implemented (commit `17c8d50`).
Notes: All five methods explicitly listed in the plan (and CLAUDE.md backlog) are gone from `BettingMiniGameBot.java`:
- `getWinnings()`, `getJackpot()`, `canCheckTotalWinnings()`, `getTotalWinnings()`, `getRoundTotalBetAmount()` — verified by `grep` returning only the documentation-of-removal comment block at lines 197-198.
- The legacy capability-hook dispatch block inside `onEndGame` (the `long winnings = getWinnings(); metrics.incBotWinnings(winnings); ...` block) is removed.
- `BotMetrics.incBetPlaced(long)` is removed (the plan calls this out at Phase C step 2 and AD-4 paragraph 3 — **not** a silent drift). The Javadoc cross-references in `incBetsPlaced`, `incBotWinnings`, and `incBotJackpot` are updated to reference the marker interfaces instead of the deleted single-bet API.

No additional methods deleted beyond the five plus `incBetPlaced(long)`. No `BotMetrics` constants touched. No allow-list edits. Scope is exactly what the plan specifies.

Test deltas:
- `OnEndGameMetricsTests` nested class deleted (plan Phase C step 4 bullet 1).
- `newSubclassWithWinnings(...)` helper deleted (plan Phase C step 4 bullet 2).
- `seedBalance` retained — the commit message notes it is still used by `newBareBot`. Plan said "**Verify** with grep before deleting; if `seedBalance` ends up unused elsewhere, drop it too" — Dev followed the verification clause correctly.
- `OnEndGameMarkerDispatchTests.shouldEmitNothingForVanillaEndGameMessage` and `shouldExtractFromHasBotWinnings` rewritten to drop the dual-write expectations (legacy 0 overwrite no longer occurs).
- `BotMetricsTest.incBetPlaced_incrementsBothCountAndAmount` deleted (plan Phase C step 5).

### Tip implementation of the three marker interfaces (commit `4180bc0`)
Status: out-of-scope-per-plan but **in-scope-per-CLAUDE-backlog** — accepted.
Notes: The plan explicitly classifies per-message implementations as OUT OF SCOPE (Open Items #2: "The user said they will write these themselves after Dev finishes"). Dev did the user's follow-up work in the same branch as Phase B/C. This is technically a drift from the plan's stated scope boundary, but it is **the documented prerequisite for Phase B** (per AD-8 and the Tip-jackson backlog item in CLAUDE.md). Shipping it before Phase B is the only way to keep `bot_bets_placed_total` / `bot_bet_amount_total` non-zero in production. Bundling it on the same branch is a reasonable interpretation of the CLAUDE backlog and does not violate any architecture decision.

`jpV` vs `tJpV` resolution: the plan's Open Items #1 left this for a staging sample. Dev resolved it with `jpV` (per-user) and documented the hypothesis + the validation sample needed (`iJp=false` round) in a thorough Javadoc block on `jackpotFor(String)`. The CLAUDE backlog item "Implement HasBotWinnings / HasJackpot / HasBetTotals on TipEndGameMessage" explicitly named this open question; Dev's answer matches Tip's documented `t`-prefix convention (`tFB`, `tFD`, `tTU` = pool / total) and is the most defensible default. The Javadoc would let a staging sample falsify the choice loudly. Acceptable.

### Tip Jackson fixtures (commit `b5a8d52`)
Status: out-of-scope-per-ENDGAME_METRICS-plan, **in-scope-per-CLAUDE-backlog** — accepted.
Notes: Adds 5 JSON fixtures + `TipGameMessageTypesTest`, matching the Bom/B52/Nohu shape. Not in the ENDGAME_METRICS plan but explicitly on the CLAUDE.md "Code Quality" backlog as a standalone item. Useful precondition for the Tip implementation in `4180bc0` (the same test file gains new `endGame` assertions in `4180bc0`). The fixtures pin `cmd` offset 8000 and the hard-coded `gameState=0` behaviour from `TipUpdateBetMessage` so future staging work surfaces deviations as test failures. No production-code impact.

## Drift

None requiring action.

The two Tip-related commits (`b5a8d52`, `4180bc0`) ship work the ENDGAME_METRICS plan classifies as out-of-scope. However:
1. Both items are explicit backlog items in CLAUDE.md.
2. The Tip implementation is the load-bearing prerequisite for Phase B's correctness in production (per AD-8) — without it, the moved counters would read 0 forever.
3. No architecture decision in the plan is contradicted.

This is a reasonable scope expansion, not drift. If anything, the plan's own AD-8 ("the marker interfaces are empty of implementers at end of this redesign") is a softer constraint than reality required for Phase B to be useful — but Dev's bundling here aligns the deploy with what the user needs without re-litigating the plan's architecture.

## Out-of-scope changes

None beyond the two Tip-related commits described above. The diff does not touch any controllers, services, configuration files, dashboards, properties, or unrelated bots.

## Amendments to the plan

None. The plan as written (with the 2026-06-08 Phase A.5 amendment) is correct and was followed faithfully for Phase B and Phase C. The Tip implementation work is endorsed by CLAUDE.md and the plan's own AD-8 reasoning.
