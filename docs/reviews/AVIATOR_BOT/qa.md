# QA — AVIATOR_BOT

**Verdict:** FAIL — the build is green, but three production bugs are still open. QA reproduced
all three as executable tests, which are committed `@Disabled`. They are code review B1, B2 and
B3 (`docs/reviews/AVIATOR_BOT/review.md`, CHANGES_REQUESTED). QA found B2 independently. No new
bug beyond the review's three. None of them can lose real money, because the server settles
every bet. They corrupt the bot's own balance, outcome, winnings and escalation signals. Once
Dev fixes them and the three tests are enabled and green, this becomes PASS. No other QA
finding blocks.

**Build:** `mvn -o clean install` in the worktree (JDK 21.0.2) → **3,207 tests, 0 failures,
0 errors, 3 skipped** (the three bug repros). Per module: api 148, strategies 126, messages 352,
engine 968, app 1,613. Baseline at `bd24e8f` was 3,149. QA added 58: 55 run, 3 are `@Disabled`
repros.

Reviewed: `git diff 045670c..HEAD` (production code unchanged since `bd24e8f`; `cc23ef5` and
`0202f3b` are review/compliance docs only). Plan `docs/plans/AVIATOR_BOT.md` incl. the
compliance amendments. CASHOUT behaviour is untouched. QA changed only `src/test/`.

## Tests added / updated

All new files. No existing test was modified.

- `bot-engine/src/test/java/com/vingame/bot/domain/bot/core/CrashBotEdgeCaseTest.java` (32 tests: 29 pass, 3 `@Disabled`).
  It uses the same harness as `CrashBotDispatchTest`: real 119 frames go through the production
  Jackson registrations, scheduled tasks are run by hand on a fake clock, and the real Micrometer
  counters are read back. The fixture stubs `channel.isOpen() = true`, so B3's suggested fix 1
  will not turn the green tests red.
  - **Send failure:**
    - a throwing bet send does not escape the task. The local debit stands, the WARN fires once
      and later failures go to DEBUG, and 1707 counts `unacked`;
    - an unbound channel behaves the same;
    - a throwing cash-out send is tried exactly once per bet, with no retry on later ticks, and
      ends as `crash` with no winnings.
  - **Bet task vs 1706, inside the CAS:** a clock hook delivers `onBettingClosed` between
    `tryPlace`'s state read and its CAS. Result: no bet, no debit, no reschedule.
  - **Early-firing bet task:** it re-arms for exactly `betAt - now`, then places.
  - **Session-check skip:** a bet task that fires during `onNewSession` is skipped.
  - **Superseded bet task:** a task for an old round places nothing.
  - **Stale and duplicate frames:**
    - a duplicate 1705 adds no second task and no second bet;
    - duplicate bet acks, cash-out acks and 1707 count once each: 1 placed, 24,000 won,
      1 outcome;
    - ticks before the ack never cash out, even above target;
    - a late tick from the previous round is ignored;
    - an ack with the wrong eid or stake confirms nothing.
  - **Crash vs cash-out:**
    - the first tick seen is the bot's own crash, frozen above target: `crash`, no 1703;
    - the other runner crashing never settles the bot's bet;
    - once the cash-out is sent, a crash flag sends nothing more, and a late ack still pays;
    - a refused cash-out (never acked) counts `crash`;
    - B2 guard: an unacked cash-out still resolves as `crash` by the next round start at the
      latest.
  - **Reconnect mid-bet:**
    - PLACED: a late ack confirms nothing, there is no outcome, the local debit stands, and the
      next round plays;
    - CASHING: a late ack is dropped and no outcome is recorded (the plan's accepted gap);
    - the silence count survives `beforeReconnect` + `onStart`: no reconnect at window 3, a
      reconnect at window 4.
  - **Stake vs balance:**
    - a balance exactly equal to the cheapest rung bets that rung;
    - over 300 seeded rounds at a balance of 27,500 the bot bets only 10k, 15k, 20k and 25k,
      and uses all four;
    - pause and resume are each logged once.
  - **Silence task:**
    - a throwing `ScopedDebugEscalator` never stops the reconnect;
    - `subscribed=true` appears in the WARN after a subscribe;
    - one WARN per silence episode, and the ladder restarts at window 1 after a frame;
    - no reconnect and no re-arm after `cleanup()`;
    - no bet after `cleanup()`;
    - the silence task and the bet task both carry the bot's MDC onto a clean thread, so the
      watchdog counter keeps its `botGroupId` tag.
  - **`@Disabled` repros of review B1 / B2 / B3** (see Failures).
- `bot-engine/src/test/java/com/vingame/bot/domain/bot/core/crash/CrashRoundStateMachineInvariantTest.java` (5 tests).
  - **Seeded random walks:** 400 × 1,500 events with two runners and 100 × 400 with one. Every
    entry point is fed current, older, newer and invalid sids, mismatched acks, every
    crash-flag combination, and resets. Bets are tracked by `Plan` identity, and the test
    checks:
    - at most one `Confirmed`, one `SendCashout` and one `Ended` per bet;
    - no `SendCashout` from a tick with the bot's own runner crashed, or after its crash flag
      was seen while LIVE, or after the bet ended;
    - a cash-out only at or above target, on the plan's own eid;
    - `CASHOUT` only after this bet's `SendCashout`, `UNACKED` only without a `Confirmed`, and
      `CRASH` only with one;
    - winnings only on `CASHOUT`.

    Coverage floors make sure the walk actually reaches cash-out, crash and unacked endings.
  - **Concurrent race, 500 runs:** crossing tick, own crash tick, a double cash-out ack and the
    round end run on 4 threads. Exactly one outcome every time, and `CASHOUT` only if the
    cash-out was decided.
  - `sid <= 0` is ignored and leaves a live bet untouched.
  - A 5,000-round sweep: the stake never exceeds the balance.
- `bot-engine/src/test/java/com/vingame/bot/domain/bot/core/crash/RoundSilenceWatchEdgeTest.java` (7 tests):
  - the exact deadline: Silent at `W`, `Remaining(1)` at `W-1`;
  - a frame on the deadline instant;
  - a task that fires 2.5 windows late counts one silent window, never three;
  - a wall-clock step back an hour gives no false Silent while frames flow;
  - ladder rungs up to 200: 1, 2, 4, 8, 16, 32, 64, 96, 128, 160, 192;
  - `beginWindow` never shortens the window a recent frame earned;
  - `subscribed` follows the connection.
- `bot-engine/src/test/java/com/vingame/bot/domain/bot/core/crash/CrashStakesBoundsTest.java` (5 tests):
  - step equal to `maxBet` vs step above it, and point windows;
  - the unset sentinels (negative or zero `maxBet`, negative step);
  - a negative `minBet`;
  - values near `Long.MAX_VALUE`, with no overflow and a truncated lazy ladder;
  - a property sweep over 5,000 configs: the ladder is exactly `{k·step : k≥1, min≤k·step≤max}`,
    checked against brute force.
- `bot-engine/src/test/java/com/vingame/bot/infrastructure/observability/BotMetricsCrashSeriesTagsTest.java` (3 tests):
  - under the full 8-key `BotMdc`, every increment lands on a pre-registered series with an
    identical tag set;
  - two groups each pre-register their own three series, and an increment stays in its group;
  - the outcome vocabulary is exact, and crash pre-registration creates no CASHOUT series.
- `bot-app/src/test/java/com/vingame/bot/domain/botgroup/validation/CrashConfigValidatorEdgeTest.java` (6 tests):
  - a step above `maxBet` is a 400 that names the step;
  - point windows;
  - an unset or negative `maxBet` is accepted (see Gaps, O-2);
  - all violations are reported in one 400, and the empty-ladder line is suppressed;
  - `maxBet` 1e15 with step 1 validates in under 2 s;
  - a property sweep over 5,000 configs: the validator accepts a config exactly when the
    config is sane and `CrashStakes.ladder` is non-empty.

### Mutation checks (all 18 killed)

Each mutation was applied to production code, the named test run, and the code restored. Final
`git status` shows no production change.

| # | Mutation | Killed by |
|---|---|---|
| M1 | `tryPlace` CAS → plain `set` | `bettingClosedRacesTryPlace` |
| M2 | drop the early-fire reschedule in `placeBet` | `earlyBetTaskReschedules` |
| M3 | drop the `sessionCheckInProgress` skip | `sessionCheckSkipsTheBet` |
| M4 | escalator call not guarded by try/catch | `escalatorFailureStillReconnects` |
| M5 | silence task scheduled without `mdcWrap` | `silenceTaskCarriesMdc` |
| M6 | bet task scheduled without `mdcWrap` | `betTaskCarriesMdc` |
| M7 | crashed flag ignored when multiplier ≥ target (F-1 inverted) | invariant walk, `firstTickSeenIsOwnCrashAboveTarget` |
| M8 | SETTLED round end re-emits an outcome | invariant walk, `duplicateAcksAndRoundEnd` |
| M9 | `affordableCount` `<=` → `<` | `balanceExactlyCheapestRung` |
| M10 | `affordableCount` ignores balance | `stakeBoundedByBalance`, `stakeNeverAboveBalance` |
| M11 | silence deadline `> 0` → `>= 0` | `exactDeadline` |
| M12 | `beginWindow` resets the silent count | `reconnectKeepsSilenceCount` |
| M13 | ladder first rung by floor, not ceil | `CrashStakesBoundsTest` |
| M14 | validator stops suppressing the empty-ladder line | `allViolationsAtOnce` (first survived; assertion strengthened) |
| M15 | `incCrashOutcome` without MDC tags | `BotMetricsCrashSeriesTagsTest` |
| M17 | duplicate round start accepted | `duplicateRoundStart` |
| M18 | `send` rethrows | `betSendFailure`, `cashoutSendFailure` |
| M19 | cash-out ack binds in LIVE | invariant walk |

(There is no M16: that number was dropped while the list was drafted.)

## Coverage of the diff

- `CrashBot.java` ← `CrashBotDispatchTest` (existing) + `CrashBotEdgeCaseTest`: every frame
  handler, `placeBet` (all branches: stopped, session check, empty or unaffordable ladder, CAS
  loss, early fire, send), `send` failure latch, `scheduleBet`/`armSilenceWatch` MDC wrapping,
  `onSilenceCheck` (Remaining, first/later Silent, rung, stopped), `escalate` (escalator
  throws), `beforeReconnect`, `cleanup`, `onStart` arming.
- `CrashRoundStateMachine.java` ← `CrashRoundStateMachineTest` (existing, every AD-8 row) +
  `CrashRoundStateMachineInvariantTest` (arbitrary orders, concurrency, balance bound).
- `RoundSilenceWatch.java` ← `RoundSilenceWatchTest` (existing) + `RoundSilenceWatchEdgeTest`.
- `CrashStakes.java` ← `CrashStakesTest` (existing) + `CrashStakesBoundsTest`.
- `CrashConfigValidator.java` ← `CrashConfigValidatorTest` (existing) + `CrashConfigValidatorEdgeTest`.
- `BotMetrics` (crash series) ← `BotMetricsCrashSeriesTest` (existing) +
  `BotMetricsCrashSeriesTagsTest` + `CrashBotDispatchTest.seriesPreRegisteredBeforeAnyFrame`.
- Message layer, registry, zone, factory wiring, alert rule ← existing tests
  (`Win79CrashMessageTypesTest`, `Win79CrashRequestTest`, `MessageTypesRegistry*Test`,
  `EnvironmentZoneResolutionTest`, `BotFactoryCrashWiringTest`, `AlertRuleMetricsTest`). Not
  re-tested; QA read them and found them adequate.

## Gaps

- **Scenario wiring is not driven end to end.** `botBehaviorScenario()` is compiled by the
  existing `onStart` tests but no test pushes raw frames through the ws-parser pipeline; the
  handlers are invoked directly. The `mdcConsumer` wrapping of the 7 `onMessage` callbacks is
  therefore read, not executed. Release V-5/V-7 is the gate (needs a live socket).
- **Out-of-order delivery across the 4 inbound workers** is modelled by calling handlers in a
  chosen order, not by real concurrency in `CrashBot`; the machine-level concurrency is covered
  by the 4-thread races.
- **B1's reconnect-in-progress half** (escalating while `Bot.reconnecting` is set) is not in the
  repro: there is no seam to observe it without the API change the review proposes. The DEAD
  half is covered.
- **Observations, not blocking** (no test asserts these as correct behaviour):
  - O-1: a duplicate 1707 for the *current* sid (not a stale one) increments `roundsObserved`
    and runs `onNewSession` a second time. The outcome is not double-counted (asserted). The
    server is not known to re-send a 1707.
  - O-2: with `maxBet <= 0` (unset) the ladder is the legacy `[20k, 200k]` and `minBet` is
    ignored, so `minBet=500000, maxBet=0` passes validation and bets 20k-200k. This matches
    AD-6 as written, but an operator might be surprised. Pinned in `CrashStakesBoundsTest` and
    `CrashConfigValidatorEdgeTest` with a comment.
  - O-3: the machine and watch clock is `System::currentTimeMillis`. After a wall-clock step
    back of X, silence detection is late by up to X and that round's bet can miss its window.
    This is safe: it causes no false reconnect (`clockStepsBack`).
  - O-4: ws-parser 3.0.5 `VingameWebSocketClient.send` never throws on a closed socket. It
    logs its own WARN and returns. So `CrashBot`'s "send failure WARN once per bot" only fires
    for an unbound channel or a serialization error. This affects B3: today a send to a closed
    client looks like success.

## Failures (if any)

No test fails in the committed state. The three `@Disabled` tests below were run enabled and
are **red on the current code, for the stated reason**. Enable each one with its fix.

- `CrashBotEdgeCaseTest.cashoutAckAfterRoundEnd`, **review B2, medium**.
  `CrashRoundStateMachine.java:417` (the `Cashing` arm of `onRoundEnd`) together with
  `:387-399`. If 1707 is handled before 1703, the bet records `outcome=crash` and the ack is
  dropped. Enabled, the test fails with `expected cashout 1.0 but was 0.0`.
- `CrashBotEdgeCaseTest.betTaskAfterLogout`, **review B3, medium**. `CrashBot.java:388-428` and
  `:580-590`. `logout()` closes the client, but the pending bet task still places, debits the
  local balance and sends to the closed client. Enabled, the test fails at
  `verify(channel, never()).send(..)`, because one bet was sent.
- `CrashBotEdgeCaseTest.silenceTaskOnDeadBot`, **review B1, high**. `CrashBot.java:497-533`.
  On a DEAD bot, 32 silent windows give `bot_watchdog_expired_total = 6` (rungs 1, 2, 4, 8, 16,
  32), call `onWatchdogExpiry` six times, and keep re-arming the task. Enabled, the test fails
  with `expected 0 but was 6.0`.
