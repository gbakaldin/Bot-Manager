# QA — CASHOUT_BOT (Phases 1–3)

**Verdict:** FAIL. One confirmed defect, Q-1, is red. Everything else passes. QA flips to PASS
when Q-1 is fixed and its test is enabled and green.
**Build:** `mvn clean install` (worktree `feature/cashout-bot`, JDK 21) → **2999 tests, 0 failures,
0 errors, 2 skipped**. The 2 skipped are the `@Disabled` red tests below. Per module: api 148,
strategies 126, messages 324, engine 808, app 1593. The baseline at `ed85d86`, before QA added
anything, was 2947 tests, all green.

Commits under test: `ed85d86` and its ancestors back to `feature/bot-provisioning`. QA commits:
`de70e55`, `76efb21`, `e33ac8d`.

## Tests added / updated

QA added 52 tests and changed no existing ones. No production code was touched.

- `bot-engine/.../core/cashout/CashoutBetStateMachineEdgeCaseTest.java` (+25):
  - **Late and out-of-order terminals.** A duplicate terminal of the previous bet (X501
    `crd==0` followed by X502 `blS:-1`, both possible per F-4) does not end the next bet,
    whether that bet is PLACED or LIVE. A terminal followed by a reordered progress frame
    sends no cash-out. A terminal whose stake does not match the plan does not bind. A late
    win for a timed-out CASHING bet is ignored.
  - **The recently-ended ring.** It holds exactly 4 sids, and the 5th-oldest can bind again.
    The ring survives `reset()`.
  - **Watchdog boundary.** At exactly `frameTimeout` the bet times out; 1 ms earlier it does
    not. In PLACED the timeout is measured from send. Progress in CASHING refreshes the
    clock, and ignored frames do not. A backoff larger than the pause is used as the exact
    delay. A 0 backoff falls back to the [500, 4500] ms pause. `frameTimeout <= 0` is rejected.
  - **Ladder.** With R=1, reconnects fire at 1,2,4,8,16,32,64,96,128. R<=0 never reconnects.
    Ignored frames do not reset the count. An immediate terminal from PLACED does reset it.
  - **Probes.** A probe takes the minimum of an *unsorted* stake list. An unaffordable probe
    places nothing.
  - **Concurrency.** A watchdog-vs-terminal hammer (200 rounds) gets exactly one of
    TIMED_OUT or ENDED every round.
  - **`crd` boundary.** `crd` > 0 is CASHOUT and `crd` == 0 is BURST. `CashoutBehavior`
    validation is covered.
- `bot-engine/.../core/CashoutBotEdgeCaseTest.java` (+22):
  - **Outcome accounting.** A duplicate burst or a duplicate win is counted and credited
    once. A burst with no `b` is accounted at the plan's stake. A timeout keeps the local
    debit, is not a confirmed bet and is not a completed round. A late win after a timeout
    is not credited (drift re-sync owns the correction). 30 mixed bets give outcomes
    summing to 10/10/10, 20 confirmed bets and 10 cash-outs sent.
  - **Cash-out send.** A throwing channel or an unbound channel never escapes the frame
    handler, and the bet still ends normally.
  - **Bet loop.** If a reconnect lands between condition and supplier, the race fallback
    still sends exactly one bet, debited once. With no stakes, the fallback sends a
    re-subscribe and debits nothing. Over 400 draws at a 150k balance, stakes stay within
    server bets ∩ [minBet, maxBet] ∩ balance. A re-subscribe mid-bet drops the bet and
    keeps the ladder.
  - **`eligibleStakes` (AD-6).** Bounds are inclusive and the result is sorted. Null, 0 and
    negative stakes are dropped. Single-value and between-values windows work. Setting
    `minBet` with `maxBet` unset still gives the default window. A subscribe with null
    `bets` authenticates the connection and pre-registers the 3 series.
  - **Ladder through the bot.** Reconnects fire at 3 and 6. A bound frame resets the count,
    so the next reconnect comes 3 timeouts later. `bot_watchdog_expired_total` matches the
    reconnects. A throwing `ScopedDebugEscalator` does not block the reconnect. Idle and
    stopped bots are never timed out.
  - **Real watchdog scheduler (wall clock, 1–2 s timeouts).** Sending a bet arms the
    watchdog. A watchdog that fires after a mid-bet frame re-arms for the remainder. An
    idle bot is never timed out.
- `bot-engine/.../observability/BotMetricsCashoutSeriesTest.java` (+3): `initCashoutSeries`
  creates the 3 outcomes at 0 and is idempotent. Increments land on the pre-registered
  series, carry the MDC `botGroupId`, and never create a second series. No `botId` tag.
- `bot-engine/.../core/cashout/CashoutKnownDefectsTest.java` (+2, **`@Disabled`, confirmed
  red** with the annotation removed): Q-1 and Q-2 below.

## Mutation check

25 mutants were hand-applied, one at a time, to `CashoutBetStateMachine` and `CashoutBot`.
For each one QA ran `Cashout*` and `BotMetricsCashout*`, then reverted it. **All 25 were
killed.**

| Mutant | Killed by |
|---|---|
| M1 ring check removed (PLACED) | `EdgeCase$LateTerminal.duplicateTerminalWhilePlaced`, `$Ring.*`, existing `Binding.recentlyEndedSidDoesNotBind` |
| M2 stake-mismatch guard removed | `$LateTerminal.terminalStakeMismatchWhilePlaced`, existing `stakeMismatchDoesNotBind` |
| M3 CASHING re-emits SEND_CASHOUT | existing concurrency hammers, `firstCrossing`, `winStream` |
| M4 watchdog `<` → `<=` | `$Watchdog.exactBoundary` and 5 others |
| M5 timeout backoff dropped | `$Watchdog.backoffIsExactWhenItDominates`, existing `backoff` |
| M6 probe = first stake, not min | `$Probes.probeTakesMinOfUnsorted` (new only) |
| M7 / M7b LIVE / CASHING timeout forgets sid | existing `timedOutSidRemembered` / `$LateTerminal.lateWinAfterTimeoutFromCashing` (new only) |
| M8 `reset()` clears ladder | existing ladder tests |
| M9 / M9b ladder every R / no steady state | `$Ladder.rOne`, existing `reconnectRungs` |
| M10 ring capacity 4 → 8 | `$Ring.capacityIsFour` (new only) |
| M11 terminal from PLACED doesn't reset ladder | `$Ladder.terminalFromPlacedResets` (new only) |
| M12 LIVE final ignored | `$LateTerminal.*`, existing end tests |
| B1 no watchdog re-arm after early fire | `RealScheduler.reArmsAfterFrame` (new only) |
| B2 `bet()` doesn't arm watchdog | `RealScheduler.armedOnSend` (new only) |
| B3 timeout counted as confirmed bet | existing `watchdogLadder`, `Accounting.timeoutKeepsDebit`, `outcomesSum` |
| B4 cash-out send failure rethrown | `CashoutSend.*` (new only) |
| B5 `eligibleStakes` unsorted | `EligibleStakes.filterAndSort` (new only) |
| B6 `eligibleStakes` max exclusive | existing window tests |
| B7 no `initCashoutSeries` on subscribe | existing `winStream`, `subscribeWithoutBets` |
| B8 escalator failure not caught | `Ladder.escalatorFailureDoesNotBlockReconnect` (new only) |
| B9 default window keyed on `minBet` not `maxBet` | `EligibleStakes.maxUnsetIgnoresMin` (new only) |
| B10 stopped-bot guard removed | `Ladder.idleAndStopped` (new only) |
| B11 fallback re-subscribe branch removed | `BetLoop.raceFallbackWithoutStakes` (new only) |

"New only" marks mutants that the Dev suite alone let survive: 14 of the 25.

## Coverage of the diff

- `CashoutBetStateMachine` ← `CashoutBetStateMachineTest` (Dev) +
  `CashoutBetStateMachineEdgeCaseTest` + `CashoutKnownDefectsTest`. Every transition, the
  ring, the ladder and the races are covered.
- `CashoutBot` ← `CashoutBotDispatchTest` (Dev) + `CashoutBotEdgeCaseTest`. Covered:
  subscribe, frames, accounting, watchdog (including the real scheduler), ladder
  escalation, bet loop and race fallback, cash-out send failure.
- `BotMetrics.initCashoutSeries` / `incCashoutOutcome` ← `BotMetricsCashoutSeriesTest` and
  the dispatch tests.
- Message layer, registry, zone, validator, factory and `PerBotInfoLogGuardTest` ← Dev's
  Phase 1/3 tests (`Win79CashoutMessageTypesTest`, `Win79CashoutRequestTest`,
  `MessageTypesRegistry*`, `EnvironmentZoneResolutionTest`, `CashoutConfigValidatorTest`,
  `GameConfigValidatorFactoryTest`, `BotFactoryCashoutWiringTest`). QA reviewed these
  against plan Phase 1 §8 and Phase 3 §5. They match, so nothing was added.

## Defects

- **Q-1 [bug, money/RTP] `reset()` forgets the bound sid.** This is the reviewer's finding,
  now confirmed red. Test: `CashoutKnownDefectsTest.resetRemembersBoundSid`. After
  `beforeReconnect()` or `onSubscribe()` mid-bet, a late frame of the abandoned bet binds
  the next bet. That happens for any X502 (no `b`) or any progress frame with an equal stake.
  The bot then cashes out or credits the wrong bet, and the real bet runs uncashed.
  Reconnects mid-bet are routine (F-7), so this will happen on staging. Fix: in `reset()`,
  remember a LIVE/CASHING sid the way `onTimeout()` does. Then remove `@Disabled`.
- **Q-2 [smell] A frame with no `sid` binds as sid 0.** This is the reviewer's finding,
  confirmed red. Test: `CashoutKnownDefectsTest.sidZeroDoesNotBind`. A rejected-bet reply
  without a sid (OI-2) would capture the PLACED plan. Fix: refuse to bind `sid <= 0`.
- **Q-3 [style, not tested] `Win79CashoutSubscribeResponse.allowedBets()` sorts before
  filtering.** A `null` in `bets` throws an NPE inside `onSubscribe`, after
  `markConnectionAuthenticated()`. The bot would then sit authenticated and never bet.
  `CashoutBot.eligibleStakes` handles nulls itself (tested), but it never gets the chance.

## Gaps

- **Wire-level behaviour is staging-only.** These depend on the live server and are V-5..V-10:
  the real winning-frame shape (OI-1 / V-8), frame cadence (OI-3), whether a rejected bet
  gets a reply (OI-2), outcome mix, and timeout rate.
- **The `onBetEnded` cancel race** (review smell: the terminal can cancel the next bet's
  watchdog) is not tested. It needs a handler stall between the CAS and `cancelWatchdog()`,
  and that can't be reproduced deterministically without a production seam. Code review
  should cover it.
- **`sessionCheckInProgress` gating the bet condition** is not tested directly. It is only
  observable while `onNewSession()` blocks inside a deposit.
- **Phase 4** (`CashoutWindow`, `CashoutBetsUnanswered` alert, CLAUDE.md) is not
  implemented, so it is not covered.

## Failures

None in the build. Q-1 and Q-2 fail with the annotation removed: in each case
`onFrame(...)` returns `Progress`/`Ended` where `NONE` is expected (lines 68 and 79).
