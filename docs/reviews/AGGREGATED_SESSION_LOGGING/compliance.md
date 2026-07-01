# Compliance — AGGREGATED_SESSION_LOGGING

Branch: `feat/aggregated-session-logging`
Plan reviewed: `docs/plans/AGGREGATED_SESSION_LOGGING.md`
Diff reviewed: `git diff main..feat/aggregated-session-logging`
Commits: `0be84e2`, `ea1fd89` (P1) · `0b0ef08`, `2a6d987`, `cf72d4c` (P2) · `c296e49`, `f163df1`, `23de481` (P3) · `9dca437` (P4)

## Verdict

COMPLIANT

All four phases are implemented faithfully to the plan. The 18 session-aggregation
tests build and pass (`mvn test -Dtest='SessionAggregation*,SlotMachineBotSessionAggregationTest'`
→ BUILD SUCCESS, 18 run / 0 fail). No production-code deviations rise to
SEND_BACK_TO_DEV, and the plan contained no technical error requiring amendment.

## Phase-by-phase

### Phase 1 — Core service + betting/Tai Xiu StartGame & EndGame
Status: implemented
- `SessionAccumulator`, `SessionAggregationStrategy`, `BettingSessionStrategy`,
  `SessionContext`, `SessionAggregationService` all created under
  `infrastructure/observability/` (AD-1..AD-5, AD-8).
- Injection mirrors `setMetrics`: `Bot.setSessionAggregator` (null-tolerant,
  `Bot.java:150`), wired in `BotFactory` constructor + `createBot` immediately after
  `.setMetrics(botMetrics)` (`BotFactory.java:189`). Plan asked for `:184`; semantic
  location is exact (line numbers drifted from plan-era).
- Feeds: `onStartGame` after `sidStore.set` → `onSessionStart` (lazy raw supplier);
  `bet()` supplier → `recordBet(currentSid, getUserName(), amount)`;
  `onEndGame` → `onSessionEnd(endGameSessionId(msg), payout, confirmedBet, supplier)`.
- First-seen guards correct: StartGame `putIfAbsent` (only winner logs), EndGame
  per-accumulator `AtomicBoolean` CAS (`markEndLogged`).

### Phase 2 — 5s UpdateBet flush + eviction sweep
Status: implemented
- Single app-wide virtual-thread `ScheduledExecutorService` in `@PostConstruct`
  (`scheduleAtFixedRate(5,5,SECONDS)`), `@PreDestroy` `shutdownNow` (AD-7). Mirrors
  the `BotGroupBehaviorService.startHealthMonitoring` factory pattern.
- `flushOnce(nowNanos)` is clock-injected/package-private for tests. Per live
  not-ended session it emits ONE DEBUG flush line under the captured `mdcSnapshot`,
  then advances baseline + flushSeq (single-threaded, no CAS — AD-6).
- All four eviction mechanisms present (see Anti-leak below).
- `evictGroup` wired into `BotGroupBehaviorService.stop` (`:573`).

### Phase 3 — Slot aggregation
Status: implemented
- `SlotSessionStrategy` (`hasRoundBoundary()=false`), synthetic per-`(group,gameId)`
  window under sentinel `SLOT_WINDOW_SID = Long.MIN_VALUE` (AD-12).
- `recordSpin` (lazy `computeIfAbsent` window creation) fed from `SlotMachineBot.spin()`
  with `totalStake = amount*numLines`; `recordSpinResult(winnings, msg.isIJ())` fed
  from `onSpinResult`. Window never marks `ended` → reclaimed by TTL/group-stop only.
- Flush renders `spins since last | total staked | total win | jackpot hits`;
  `spins since last` resets each tick via `advanceSpinBaseline`, totals cumulative.
- No StartGame/EndGame lines for slots (bot never calls `onSessionStart/End`).

### Phase 4 — Demote raw frames to TRACE
Status: implemented
- `OutputPrinter.debugOutputPrinter` printer: `log.debug` → `log.trace` (`:65`).
  Javadoc updated to TRACE and explicitly notes it finalizes/supersedes
  RESILIENCE_HARDENING P0a (which had left this wired printer at DEBUG).
- Phase-4 grep assertion satisfied: `grep -nE "log\.debug|log\.info" OutputPrinter.java`
  → no matches (all TRACE).

## Verification of the seven checks requested

1. **Emit levels (verified in code, not claims):**
   - StartGame `SessionAggregationService.onSessionStart` → `log.info` (`:264/:266`). PASS
   - EndGame `onSessionEnd` → `log.info` (`:370/:372`). PASS
   - UpdateBet + slot flush `emitFlush` → `log.debug` (`:210`). PASS
   - Raw frames `OutputPrinter:65` → `log.trace`. PASS

2. **Bot-sourced aggregates:** stake/winnings never re-parsed from UpdateBet frames.
   - Betting stake: `recordBet(amount)` from the outbound `bet()` supplier.
   - Betting/Tai Xiu winnings: `payout = HasBotWinnings.winningsFor(getUserName())`
     — the value the bot already computed. For Tai Xiu this returns `G` directly
     (per OI-7 in `TaiXiuGameBot`), which is the correct gross "total win" figure for
     the summary line. `endGameSessionId(msg)` (overridden in `TaiXiuGameBot:253` to
     read `sidStore`) supplies the tracked sid since the Tai Xiu frame carries no sid.
     `confirmedBet` sourced from `HasBetTotals.betAmountFor`. Correct per game type.
     (Note: the balance-credit `gR + G` in `TaiXiuGameBot.balanceCreditFor:270` is a
     distinct concern; the log line correctly uses gross winnings `G`, not the net
     balance credit.)
   - Slots: stake `totalStake = amount*numLines` from outbound `spin()`; winnings +
     `iJ` jackpot flag from inbound `onSpinResult`. PASS

3. **Anti-leak completeness — all four mechanisms present:**
   - Hard cap `MAX_SESSIONS=10_000` via `enforceSizeCap()` on every insert + each flush.
   - TTL sweep `TTL_NANOS=60s` on `lastActivityNanos` in `flushOnce`.
   - Grace-then-evict `GRACE_NANOS=5s` for `ended` sessions in `flushOnce`.
   - `evictGroup(botGroupId)`.
   Value-guarded `remove(k,v)` used throughout so a flush racing a re-insert can't
   drop a fresh entry. `evictGroup` is called on **every** production teardown path:
   `stop()` (`:573`), `restart()` (delegates to `stop()`, `:613`), and failed-start
   (`:299`, added beyond the plan's explicit list — in-spirit with AD-8). Both
   production `stopAllBots` callsites are paired with an `evictGroup`. `markAsDead`
   (`:965`) does not stop bots, so no insert-without-evict path leaks; TTL is the
   documented backstop regardless. PASS

4. **Scope = all game types via strategy interface:** betting-mini + Tai Xiu share
   `BettingSessionStrategy` through the `BettingMiniGameBot.sessionStrategy()` override
   seam (Tai Xiu inherits unchanged); slots use `SlotSessionStrategy`. The service holds
   zero game-type knowledge and contains no `instanceof` on game type. PASS

5. **Plan integration points:** all present at the correct semantic locations
   (`BotFactory` setSessionAggregator after setMetrics; `BettingMiniGameBot.bet()`
   feed; `SlotMachineBot.spin()`/`onSpinResult` feeds; the health-monitor
   scheduled-executor pattern reused in `@PostConstruct`; `TaiXiuGameBot`
   `endGameSessionId` + `HasBotWinnings` winnings). Line numbers drifted from the
   plan-era anchors but the wiring is exact. PASS

6. **Open Items resolution:**
   - Emit level → resolved to INFO (StartGame/EndGame) + DEBUG (flush). Recorded in
     `SessionAggregationService` class javadoc ("resolved decision") and in the
     updated CLAUDE.md logging guidelines.
   - Slot round-keying → resolved to a single per-`(group,gameId)` tumbling 5s window
     under sentinel sid. Recorded in `SlotSessionStrategy` javadoc.
   - `evictGroup` hook → resolved to the `BotGroupBehaviorService` layer (not
     `BotGroupRuntime`), with TTL as backstop. Consistent with plan AD-8.
   All resolved consistently. PASS

7. **Plan technical oversight:** none material. The plan was implementable as written;
   no falsifiable assumption about the codebase/API was contradicted by the diff, so
   no PLAN_AMENDED is warranted.

## CLAUDE.md logging guidelines

Updated consistently with the new classification: INFO now names the per-session
StartGame/EndGame summaries (with the explicit note that they *replace* the frame
flood and scale ~2/round/group), DEBUG names the 5s UpdateBet + slot window flush,
and TRACE now names the `debugOutputPrinter` `User <name>:` printer moved down in
Phase 4 (finalizing P0a). Consistent with the code. PASS

## Minor notes (non-blocking, not deviations)

- Documentation drift only: the plan's own `## Open Items` section was not
  back-annotated to mark the three items resolved; the resolutions live in code
  javadoc + CLAUDE.md instead. No code impact.
- The stop method is `BotGroupBehaviorService.stop(id)` (plan referred to it as
  `stopBotGroup`); harmless naming difference.
- Failed-start `evictGroup` (`:299`) is an addition beyond the plan's enumerated
  hooks — a correct, in-scope strengthening of the anti-leak invariant, not scope creep.

## Out-of-scope changes

None. Every non-test file touched is on the plan's integration list; test-fixture
edits (`BotFactory*Test`, `BotGroupBehaviorService*Test`) are the constructor-signature
adjustments the new injected dependency requires.
