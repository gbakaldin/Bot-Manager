# Code Review — TIP_ENDGAME

Branch: `feat/observability-metrics`
Reviewed diff: commits `b5a8d52`, `4180bc0`, `0a029b9`, `17c8d50` (per scope brief).

## Verdict

PASS

No `bug` or `security` findings. A handful of smells/styles that are worth fixing but do not block ship. The four-commit slice executes the marker-interface design cleanly, the deletion cascade is clean (zero orphan callers, verified by grep), and the fixture set is structurally honest.

## Findings

### [smell] `lastRoundWinnings` update gated by `metrics != null`
`src/main/java/com/vingame/bot/domain/bot/core/BettingMiniGameBot.java:200-204`

```java
if (metrics != null) {
    if (msg instanceof HasBotWinnings hw) {
        long w = hw.winningsFor(getUserName());
        if (w > 0) metrics.incBotWinnings(w);
        lastRoundWinnings = w;       // <-- inside metrics-null guard
    }
    ...
}
```

`lastRoundWinnings` is a per-bot field surfaced via `BotHealthDTO.lastRoundWinnings` (read in `BotGroupBehaviorService.java:601`). Its value is functionally independent of whether Prometheus metrics are wired — it powers the public-facing health UI. Locating the assignment inside the `metrics != null` guard couples two concerns that the rest of the design carefully separates:

- The Phase B/C narrative is "local accumulators (`totalBetsPlaced` / `totalBetAmount`) stay regardless of metrics; only Prometheus counters move." `lastRoundWinnings` is the third member of that local-accumulator family and should follow the same rule.
- The unit test `BettingMiniGameBotTest.shouldNoOpOnAllInterfacesWhenMetricsNull` asserts no NPE and PAYOUT transition, but does **not** assert `lastRoundWinnings` is updated. With metrics null and a `HasBotWinnings` payload, the DTO will silently miss the per-round winnings update.

In production this never bites today (Spring always wires `BotMetrics`), but the seam is brittle. Fix shape: hoist the `lastRoundWinnings = w` assignment out of the `metrics != null` block, or split the message-extraction from the metric-emission so the extraction always runs.

Related observation (not a finding): the legacy block being deleted in Phase C unconditionally wrote `lastRoundWinnings = winnings` (always 0 for vanilla, set per-game for subclasses). The new code only updates when the message implements `HasBotWinnings`. For non-Tip games that's identical (stays 0 forever). For Tip-on-Tip every EndGame implements it, so values stay fresh. The behavior is fine in steady state; the worry is only the `metrics == null` corner.

### [smell] Stale Javadoc reference to deleted `getJackpot()` method
`src/test/java/com/vingame/bot/domain/bot/message/g3/tip/TipGameMessageTypesTest.java:151`

```java
// iJp + jpV / tJpV — drives HasJackpot. jpV chosen as the per-user jackpot value
// per Javadoc on TipEndGameMessage.getJackpot() (default pending iJp=false sample).
```

`TipEndGameMessage.getJackpot()` does not exist — the method is `jackpotFor(String)`. The reference was likely copy-pasted from the legacy capability-hook name (`BettingMiniGameBot.getJackpot()`) which was deleted in this same branch (commit `17c8d50`). Replace with `TipEndGameMessage.jackpotFor()`.

### [smell] `t`-prefix convention claim doesn't generalize across the codebase
`src/main/java/com/vingame/bot/domain/bot/message/g3/tip/TipEndGameMessage.java:42-44`

The Javadoc argues:

> consistent with Tip's `t`-prefix convention on fields like `tFB`, `tFD`, `tTU` meaning "time for/total ..."

That reasoning is internally consistent for Tip (the listed fields are all `time-for-X` aggregates), but **Bom and Nohu use `tJpV` as the per-user jackpot payout**, not the pool/tier:

- `BomEndGameMessage.java:18` — `return iJp ? tJpV : 0L;`
- `NohuEndGameMessage.java:18` — `return iJp ? tJpV : 0L;`

So the same field name `tJpV` carries opposite meanings across products. The Javadoc as written could mislead a future reader into thinking the convention is codebase-wide. Two acceptable fixes:

1. Scope the convention claim to Tip explicitly: "consistent with Tip-specific `t`-prefix usage on `tFB`/`tFD`/`tTU` — note Bom/Nohu use `tJpV` differently."
2. Drop the convention argument entirely and lean on the empirical observation (sample shows `jpV=1603000` ≫ `tJpV=200000`, `iJp=true` → `jpV` is the larger value and the more plausible per-user payout).

The open question (need an `iJp=false` sample) is correctly flagged. Verdict-irrelevant — the implementation is a defensible choice given the data, and the staging follow-up is the right escape valve.

### [smell] `incBetsPlaced` early-return on `count <= 0` loses non-zero amount
`src/main/java/com/vingame/bot/infrastructure/observability/BotMetrics.java:195-206`

```java
public void incBetsPlaced(int count, long totalAmount) {
    if (count <= 0) return;
    ...
}
```

For Tip's current implementation this is safe: both `betCountFor` and `betAmountFor` derive from `bs[]` and are zero/non-zero together. But the contract on `HasBetTotals` doesn't require the two values to be coupled, and any future implementer that emits `(count=0, amount=N>0)` will silently lose the amount. The guard is documented in the Javadoc, but the asymmetry is a footgun.

Two safe shapes:

1. Guard each counter independently: `if (count > 0) bets.increment(count); if (totalAmount > 0) amount.increment(totalAmount);` — preserves data, allows divergence.
2. Tighten the contract on `HasBetTotals` Javadoc: "implementations MUST return 0 for both methods when the bot placed no bets; mixed returns are undefined."

Option (1) is cheaper and more forgiving. Option (2) keeps the current code but the constraint should be loud.

### [style] `betCountFor` returns `int`; large rounds risk overflow
`src/main/java/com/vingame/bot/domain/bot/message/g3/tip/TipEndGameMessage.java:84-91`
`src/main/java/com/vingame/bot/domain/bot/message/HasBetTotals.java:25`

`HasBetTotals.betCountFor` returns `int`. Tip's implementation sums `bs[].bc` (an `int`) into an `int` accumulator. A single bot placing > 2^31 bets in one round is impossible in practice — `bs[]` is bounded by the number of game positions (6 for BauCua-shaped games). Not a real concern. Mentioned only because the symmetric `betAmountFor` is `long` for the same reason it could matter there; the API would feel more uniform if both were `long`. Low priority — flagging for awareness, not action.

### [style] Test coverage for `userName == null` is asymmetric
`src/test/java/com/vingame/bot/domain/bot/message/g3/tip/TipGameMessageTypesTest.java:169-217`

`hasBotWinnings()` exercises `winningsFor(null)`. The matching tests for `HasJackpot` and `HasBetTotals` (`hasJackpot`, `hasBetTotals`, `hasBetTotalsEmpty`) only exercise `"any-bot"`. All three Tip implementations ignore the `userName` arg (the payload is recipient-personalized, documented in Javadoc), so the symmetry doesn't matter for correctness — but it does for documenting the contract. Adding `null` to the two missing tests is one line each.

## Notes

**Fixture realism.** Fixtures are constructed from POJO constructors + the docs/plans/ROUND_DATA_COLLECTION_FINDINGS.md sample, not real wire captures. They are appropriately "every field populated" — that's the right call for a first fixture set because it (a) exercises every Jackson constructor binding, (b) catches missing `@JsonProperty` regressions, and (c) matches the Bom/B52/Nohu fixture style. The `endGame.json` reproduces the sample values from the findings doc faithfully (`jpV=1603000`, `tJpV=200000`, `iJp=true`). The `subscribe.json` populates fields not strictly needed by `TipSubscribeMessage` (`htr`, `cH`) — appropriate for catching constructor binding drift. The two staging-verification gaps (real UpdateBet frame for `gS`/`rmT`, `iJp=false` EndGame frame) are correctly deferred.

**Capability semantics for jackpot null/zero.** `jackpotFor` returns `iJp ? jpV : 0L`. When `iJp == false`, the return is unconditionally 0; the caller in `BettingMiniGameBot.onEndGame` guards `if (j > 0) metrics.incBotJackpot(j)`, so no spurious increment. Contract matches `BotMetrics.incBotJackpot`'s expectation that callers pre-filter zero (documented in `incBotJackpot` Javadoc). Clean.

**`bs[]` aggregation correctness.** Tip's `betAmountFor` sums `bs[].b` (per-user stake per option), explicitly NOT `bs[].v` (round-wide aggregate). The Javadoc calls this out. `betCountFor` sums `bs[].bc`. Both null-safe (`bs == null` short-circuits). Matches what Bom/Nohu would need if they implemented the same interface — except Bom/B52/Nohu's `bs[].BetInfo` has no `b` field (only `eid`, `bc`, `v`), so they cannot implement `HasBetTotals` without payload changes. That data-availability gap is correctly documented in ROUND_DATA_COLLECTION_FINDINGS.md and is out of scope here.

**Phase C deletion cascade verified clean.** `grep -rn "getWinnings\|canCheckTotalWinnings\|getTotalWinnings\|getRoundTotalBetAmount"` against `src/main/java` returns only the cleanup-comment in `BettingMiniGameBot:197-198`. `grep "getJackpot"` matches an unrelated `SessionHistoryDTO.getJackpot()` Lombok accessor — not the deleted method. No production subclasses of `BettingMiniGameBot` exist (`grep "extends BettingMiniGameBot" src/main/java` → empty). The five deleted hooks had zero external callers; the only override sites were in the test file, which the same commit rewrote. Safe.

**Local-accumulator vs metric ordering.** No race. `creditBalance` runs on the scenario pool (`pool-N-thread-1`) when the bet supplier fires; `onEndGame`'s `HasBetTotals` branch runs on the netty-ws-message-processor thread. Both update independent atomic counters (`totalBetsPlaced` etc.) and Prometheus counters (`bot_bets_placed_total`) respectively. The legitimate divergence between bets-sent (local) and bets-confirmed (Prometheus) when the server rejects bets is explicit and documented in the `incBetsPlaced` Javadoc + `HasBetTotals` interface Javadoc. The under-counting concern raised in the scope brief ("disconnects mid-round before EndGame") is real and intentional — server-authoritative recording trades that edge for accuracy on rejected bets. Documented design choice (AD-4), not a finding.

**Style consistency with Bom/Nohu impls.** `BomEndGameMessage` / `NohuEndGameMessage` place their `jackpotFor` at the top of the class with one-line bodies and no Javadoc. Tip's `TipEndGameMessage` places three capability methods at the top with extensive Javadoc. The asymmetry is justified by data complexity (Tip's `jpV` vs `tJpV` ambiguity, `bs[]` iteration, the documented open question) and by Tip being the first game to exercise the full capability surface. Future games adding `HasBetTotals` should mirror Tip's docstring shape, not Bom/Nohu's minimal shape.

**Test stub for capability dispatch (`StubEndGameMessage` in BettingMiniGameBotTest:689-744).** Implements all three markers in a single named class — correct (anonymous Java classes can't add interfaces at instantiation, per the Phase A plan note). Wired only into the marker-dispatch unit tests; not exported. Good fit for the seam.
