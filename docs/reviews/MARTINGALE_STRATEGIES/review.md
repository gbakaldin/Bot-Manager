# Code Review — MARTINGALE_STRATEGIES

Branch: `feat/martingale-strategies`
Reviewed diff: `git diff main..feat/martingale-strategies` (merge-base `fd62d3d`, 23 files, +3295 lines)

Scope: 8 new `BettingStrategy` implementations (4 progressions × 2 risk profiles), a shared
`MartingaleStrategySupport` abstract base, an `AffinityOptionPicker` helper, and a `RiskProfile`
enum. Reviewed against `docs/plans/MARTINGALE_STRATEGIES.md` (A1–A9) and the canonical-style
reference `RandomBehaviorStrategy`.

## Verdict

PASS

No `bug` or `security` findings. Two `smell`s and two `style` nits — all advisory; the
progression math, threading model, cap-hit semantics, and overflow handling are correct as
implemented.

## Findings

### [smell] Behavior bounds re-cached on every `decide()` even though the docs say "first call"
`src/main/java/com/vingame/bot/domain/bot/strategy/martingale/MartingaleStrategySupport.java:140-143`

```java
cachedMinBet = behavior.getMinBet();
cachedMaxBet = behavior.getMaxBet();
cachedBetIncrement = behavior.getBetIncrement();
boundsCached = true;
```

The class javadoc (line 92-95) and the inline comment (line 137-139) describe this as a
lazy-init that fires on the first `decide`. The code actually writes the cache on every
call. `BotBehaviorConfig` is `@Value @Builder` (immutable) and a bot's config doesn't change
across ticks, so this is functionally harmless — same value written each time — but the
documented intent ("captured on the first decide call") and the actual code don't line up.
Two cheap fixes either way: gate the assignment with `if (!boundsCached)`, or correct the
comment to "refreshed on every decide; harmless because the config is immutable for the
bot's lifetime."

This is the kind of comment/code drift that confuses a future reader looking at the
"defensive guard" branch in `onRoundEnd` (line 182-191) and wondering when `boundsCached`
could ever be false. Today the answer is "only between construction and the first decide;"
the comment implies it might also be false after a never-decide-yet bot, which is exactly
the case the guard handles.

### [smell] Capped-Martingale alignment math can theoretically overflow for `Long.MAX_VALUE` sentinel
`src/main/java/com/vingame/bot/domain/bot/strategy/martingale/MartingaleStrategySupport.java:298-301`

```java
long steps = (rawTarget - cachedMinBet) / cachedBetIncrement;
aligned = cachedMinBet + steps * cachedBetIncrement;
```

When `Classic`/`Paroli`/`Fibonacci` surfaces the `Long.MAX_VALUE` overflow sentinel
(`ClassicMartingaleStrategy.java:58`, `ParoliStrategy.java:90`, `FibonacciStrategy.java:139`),
this branch runs with `rawTarget = Long.MAX_VALUE`. The cap-hit-reset path relies on
`aligned > cachedMaxBet` to fire on line 303. In the existing tests it does — but only
because the test parameters happen to keep `steps * cachedBetIncrement` within long range.

Worked example that breaks the guard arithmetically (not exercised by any test):
`cachedMinBet=100, cachedBetIncrement=10^15, rawTarget=Long.MAX_VALUE`.
- `steps = (Long.MAX_VALUE - 100) / 10^15` ≈ 9223.
- `steps * cachedBetIncrement` ≈ 9.2 × 10^18 — overflows long and wraps to a small or
  negative number.
- `aligned = cachedMinBet + wrapped-value` — anywhere from negative to small positive.
- `aligned > cachedMaxBet` may be false → cap-hit doesn't fire → strategy returns a garbage
  bet.

In practice no operator will configure `betIncrement` anywhere near 10^15, so this is a
theoretical leak rather than a live bug. But the `Long.MAX_VALUE` sentinel is sold (in the
plan and the class-level javadoc on `ClassicMartingaleStrategy`) as a *complete* defense
against overflow, and the abstraction is leaky: it works because the alignment math
happens to also be safe on realistic inputs, not because the design is closed under the
sentinel contract.

Cleanup shape: in `applyClampAlignReset`, short-circuit on `rawTarget == Long.MAX_VALUE`
(or `rawTarget > cachedMaxBet` before the alignment math runs) to trip the cap-hit branch
unconditionally. Single line, surfaces the intent directly.

### [style] `decide` log line passes `amount` twice; placeholder name is misleading
`src/main/java/com/vingame/bot/domain/bot/strategy/martingale/MartingaleStrategySupport.java:174-176`

```java
log.debug("{}.decide: bet option={}, amount={} (currentBet={}, profile={})",
        getClass().getSimpleName(), option, amount, amount, profile);
```

`amount` is supplied for both the `amount={}` and `currentBet={}` placeholders. The two
are equal at this point in the method because `amount = currentBet` was captured inside
the monitor (line 170) — and logging the captured value is the *right* choice (reading
`currentBet` again after the monitor releases would race the netty thread). But the
placeholder name in the format string then misleads: it reads as "the live state of
`currentBet`," when in fact it's the same captured snapshot already shown by `amount=`.

Either drop the redundant placeholder or rename it (e.g. `(captured={}, profile={})`) so
the format string reflects what's actually being shown.

### [style] `MartingaleStrategySupport.applyClampAlignReset` floor branch is correct but the comment understates it
`src/main/java/com/vingame/bot/domain/bot/strategy/martingale/MartingaleStrategySupport.java:289-291`

```java
if (rawTarget <= cachedMinBet) {
    aligned = cachedMinBet;
}
```

The javadoc (line 282-283) describes this as handling "Negative or sub-`minBet` raw
targets are floored to `minBet` before alignment." That's the right behavior, but the
condition also catches `rawTarget == cachedMinBet`, which isn't strictly a floor case —
it's the "no-op already aligned" case. The current branch happens to compute the right
answer for both, so this is purely cosmetic, but the javadoc would read more accurately
as "raw targets at or below `minBet` are clamped to `minBet`."

Lowest-priority nit; flag only because the file is heavily documented elsewhere and the
inconsistency stands out.

## Notes

Good patterns worth calling out:

- **Threading discipline.** Every mutable field in `MartingaleStrategySupport`,
  `ParoliStrategy` (`consecutiveWins`), and `FibonacciStrategy` (`fibIndex`) is read or
  written exclusively inside `synchronized (this)`. The test-only accessors
  (`getCurrentBet`, `getConsecutiveWins`, `getFibIndex`, `setFibIndexForTest`) all acquire
  the monitor — no leaks of unsynchronized state to the test harness. The two threads
  (scenario `decide` vs netty `onRoundEnd`) are correctly isolated.
- **Final hook seams.** `decide` and `onRoundEnd` are `final` on the base class; the three
  progression hooks (`nextBetAfterWin/Loss/NoBet`) and `onCapHitReset()` are the only
  override seams. This makes it impossible for a subclass to accidentally re-implement
  the synchronization or the clamp/align/reset path. The eight concrete strategy classes
  are 4-line thin subclasses, exactly per A4.
- **Push routing.** `MartingaleStrategySupport.onRoundEnd` (line 204-211) routes
  `balanceDelta == 0 && staked > 0` through `nextBetAfterLoss` consistently across all
  four progressions. The per-progression unit tests (`pushTreatedAsLoss` in each) plus
  the end-to-end stream test pin this behavior at the public level too.
- **`onCapHitReset` hook.** Cleanly used by `ParoliStrategy.onCapHitReset` (clears
  `consecutiveWins`) and `FibonacciStrategy.onCapHitReset` (clears `fibIndex`). Classic
  and D'Alembert correctly inherit the base no-op. The hook fires inside the synchronized
  block, so subclass mutations are safely published.
- **`AffinityOptionPicker` uniform-fallback path.** Triggers correctly when total weight
  is ≤ 0 (`AffinityOptionPicker.java:101-107`); the `warned` flag is `volatile boolean`
  and the WARN log is idempotent. The picker rejects null profile / null / empty
  affinities at the boundary with `IllegalArgumentException` — clean fail-fast.
- **Overflow handling under `Math.multiplyExact`.** Used in all three places it can fire
  (Classic doubling, Paroli doubling, Fibonacci `minBet * fib(i)`); each catches
  `ArithmeticException` and surfaces `Long.MAX_VALUE` so the base class trips the
  cap-hit-reset branch. WARN log on overflow uses SLF4J `{}` form throughout. See the
  `[smell]` above for the one place this contract is leaky.
- **Logging level discipline.** Matches `RandomBehaviorStrategy` and the CLAUDE.md
  guidance: DEBUG per `decide` / per `onRoundEnd` / per cap-hit; WARN reserved for
  recoverable anomalies (overflow, defensive guard, all-zero affinities). No INFO from
  the strategy package — startup INFO continues to come from
  `BettingStrategyFactory.init` only.
- **Test coverage shape.** The per-progression tests pin each branch (loss, win, push,
  no-bet, cap-hit, alignment, overflow); the end-to-end test drives all eight concrete
  strategies through the same nine-round script and pins the bet trajectory at every
  step; the factory wiring test asserts every `StrategyId` resolves to its expected
  concrete class. `everyStrategyIdResolvesEndToEnd` in particular is a nice
  self-updating safety net for future enum additions.

Questions for the author (non-blocking):

- The `FibonacciStrategy.setFibIndexForTest` package-private setter (line 90-94) is one
  of two places where the production class has a test-only seam. The alternative — driving
  to the cap by feeding 63 losses — was ruled out by the plan ("pick the simpler"). Fine;
  flagging only so the seam stays documented. The `setFibIndexForTest` method has a
  doc-comment that already says "production callers must not depend on this method," so
  the intent is clear.
- The `decide` log line uses `getClass().getSimpleName()` to disambiguate the eight
  concrete classes in a shared log file. For very chatty logs this is slightly slower
  than a static prefix (reflection per log line), but the call is gated by a DEBUG check
  inside SLF4J's parameterized logging, so the cost only kicks in when DEBUG is enabled.
  Matches the pattern other strategies in the family will need anyway. No change asked.
