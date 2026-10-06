# Compliance — AVIATOR_BOT

Branch: feature/aviator-bot
Plan reviewed: `docs/plans/AVIATOR_BOT.md` (at commit b260afc, with the 2026-10-06 amendment added by this review)
Diff reviewed: `git diff 045670c..bd24e8f` (13 implementation commits after the plan commit b260afc)

## Verdict

PLAN_AMENDED

The code implements all four phases faithfully. I accept every deviation the devs recorded. Two
of them are minor API choices. The rest fix gaps or assumptions in the plan's own design. I
amended the plan for two genuine oversights:

- **AM-1, verification:** V-5's `registeredCount == 3` poll does not prove the group can be
  started.
- **AM-2, AD-11:** the rule "every 1707 runs the session check" assumed frames arrive in order.

Neither amendment needs a code change.

The worktree build output (surefire reports written after HEAD at 17:01) shows 0 failures and 0
errors: bot-api 148, bot-messages 352, bot-engine 916, bot-app 1607. I did not re-run the build;
that is QA's job.

## Phase-by-phase

### Phase 1 — Protocol layer, game type, registry
Status: implemented
Notes:
- **AD-3:** `GameType.CRASH("Crash")` is placed after CASHOUT and has javadoc.
- **AD-4 / AD-5, contracts (bot-api):**
  - `CrashMessageTypes` has codes 0/2/3/5/6/7/9, `runnerCount()` defaulting to 1, and default
    implementations of `cmds(offset)` and `getTypeRegistrations(offset)`.
  - The `crash/` package has the abstract frames and `HasRunnerMultiplier`, with the two members
    the AD-2 extension requires. `CrashRequest` has three methods.
  - 1708 and 1716 are not registered.
- **AD-2 / F-1 / F-2, 119 provider (`g4/win79/crash/`):**
  - eid 1 maps to `jOdd`/`jFi` and eid 2 to `nOdd`/`nFi`.
  - An unknown eid returns `0` / `true`.
  - Values are parsed as Double and stored as `Math.round(×100)`.
  - `runnerCount() = 2`.
  - Subscribe reuses `SubscribeToLobbyMessage`. The bet and cash-out bodies match AD-10 key for
    key.
- **Registry:**
  - Adds a fifth table, `crash(String)` and `registeredCrashProducts()`.
  - The startup segment is appended after CASHOUT and the line stays one line.
  - Every constructor call site gets `List.of()`.
  - `MessageTypesCoverageTest` has a CRASH inventory of every product except 119.
- **Other items:**
  - Zone (AD-14): CRASH resolves to `MiniGame`.
  - `CrashStakes` lives in bot-engine, which both the validator and the bot can reach.
  - `CrashConfigValidator` implements exactly the AD-15 rules.
  - `BotFactory` put CRASH in the throw arm in `1c10c8c`, as Phase 1 required. Phase 3 replaced
    it with the real arm.
- **Tests:** every listed Phase 1 test exists: fixtures, the L4/L12 assertions, eid 3,
  serialization, the registry "CRASH 1 products [119]" line, ladder 37/default/empty, and zone.
- **"No CASHOUT test changed except constructor sites and coverage":** satisfied in spirit. The
  extra edits are all additive and all forced by the new type:
  - `CrashConfigValidator` added to three `@Import` lists, because without it
    `GameConfigValidatorFactory` fails boot;
  - CRASH asserted in `ApplicationContextLoadsTest` and `GameConfigValidatorFactoryTest`;
  - new CRASH cases in the registry validation and error-text tests.

  No existing assertion was changed.

### Phase 2 — Pure round machine, silence watch, ladder
Status: implemented, with accepted deviations (below)
Notes:
- **`CrashRoundStateMachine`:**
  - Uses one `AtomicReference` over immutable records, and every transition is a CAS.
  - Clock and `Random` are injected. No I/O, no logging. The class is public.
  - Every AD-8 row is implemented. The crashed flag is checked before the multiplier.
  - Only the plan's own eid is read, so the legacy either-runner bug cannot be expressed.
  - Acks bind only in PLACED or CASHING, on `(eid, stake)`.
  - SETTLED ignores ticks.
  - `reset()` emits nothing.
- **Other classes:**
  - `CrashBehavior.LEGACY` is (1.1, 5.0, 0, 4500).
  - `RoundSilenceWatch` is pure. `onFrame` is one volatile write. A reconnect does not reset the
    count.
  - `ReconnectLadder.isRung(count, r, topShift)` is new and shared. CASHOUT is untouched
    (OI-5), and `ReconnectLadderParityTest` pins the two implementations together.
- **Tests:** every listed Phase 2 test exists, including:
  - both capture replays;
  - the F-1 2.85/2.86 case and the legacy-bug case;
  - target frozen;
  - the 4-thread hammer and crossing-vs-crash race;
  - the stale pre-crash tick;
  - the ack binding matrix;
  - the `tryPlace` gates;
  - the missed 1707;
  - the eid, target and amount distributions;
  - the ladder rungs 1/2/4/8/16/32/64/96.

  The `TransitionTable` nested class covers every AD-8 row.

### Phase 3 — CrashBot, factory wiring, metrics
Status: implemented, with accepted deviations (below)
Notes:
- **`initializeSubclass`:**
  - Requires an offset; a missing one throws `IllegalStateException`.
  - Builds the per-bot RNG, the machine, the watch and one virtual `crash-<user>` scheduler.
  - Calls `metrics.initCrashSeries()` before any frame.
  - Writes the aggregator line `game=…, type=CRASH, offset=…, runners=…`.
- **Scenario:** captures the client once, then `waitFor(1000)` → `send(subscribe)` →
  `waitForMessage(cmd(offset)&RECEIVED)`, then seven MDC-wrapped `onMessage` handlers. There is
  no `sendAsync`.
- **Handlers:**
  - Every handler starts with `watch.onFrame()` and `incBotMessage`, using exactly the seven
    AD-11 labels.
  - `onSubscribe` calls `markConnectionAuthenticated()` first, then `reset`, then computes the
    ladder.
  - The bet task follows AD-10's order: the `sessionCheckInProgress` skip, `tryPlace`,
    `creditBalance`, then a send on the captured channel.
  - The cash-out is sent straight from the tick handler. A send failure logs WARN once per bot,
    then DEBUG.
  - Accounting matches AD-11: placed is counted at the 1702 ack, and gross `wm` winnings are
    credited at the 1703 ack. Outcomes go through one private method.
- **Lifecycle methods:**
  - `onNewSession` is copied from CASHOUT, with its gate.
  - `beforeReconnect` cancels the bet task and resets the machine. It keeps the silence count.
  - `onStart` runs `onNewSession`, then the OutputPrinter over `cmds(offset)`, then the
    scenario, then `armSilenceWatch`. Arming in `onStart` is what AD-9 requires.
  - `cleanup` shuts the scheduler down.
- **Wiring and metrics:**
  - The `BotFactory` CRASH arm matches the plan verbatim.
  - `BotMetrics` gains `BOT_CRASH_BETS_TOTAL`, `CRASH_OUTCOMES`, the idempotent
    `initCrashSeries()` and `incCrashOutcome`, and its javadoc lists the new labels.
- **Tests:** `CrashBotDispatchTest` covers every listed case: series at 0 right after
  `initializeSubclass`, the capture round, the F-1 crash, unacked, the bet task after close,
  `beforeReconnect`, and silence reconnecting at windows 1 and 2 but not 3 with one
  `subscribed=false` WARN. `BotFactoryCrashWiringTest` exists. `PerBotInfoLogGuardTest` lists
  all three classes. `CrashBot` contains no `log.info`.

### Phase 4 — Alert and docs
Status: implemented
Notes:
- **`CrashBetsUnacked`:** the expression, `for: 15m`, `severity: warning` and `job: bot-manager`
  all match the plan. `AlertRuleMetricsTest` is extended, and `AlertRulesAudienceTest` passes.
- **CLAUDE.md "Crash bot" subsection:** covers all five points (runner as metadata, the F-1
  flag-first rule, the AD-9 ladder armed in `onStart`, the outcome metric, never betting off the
  snapshot). It also corrects the draft's claim that the CASHOUT watchdog catches a refused
  subscribe.

## Recorded deviations — rulings

| # | Deviation | Ruling | Why |
|---|---|---|---|
| 1 | `CrashStakes.ladder` returns a lazily computed `List` | **Accept** | AD-6/AD-15 ask for a pure function that returns the ladder, and it still does: ascending, random access, same contents. The validator accepts `maxBet 10^12, betIncrement 1`, and a materialised list would then be terabytes per bot. Binary-search `affordableCount` keeps `tryPlace` O(log n). This is an implementation detail, not drift. |
| 2 | `minBet` ignored when `maxBet` is unset | **Accept** | This is the literal AD-6 rule: "If `maxBet ≤ 0` (unset), the window is `[20_000, 200_000]`". The validator still enforces `minBet ≥ 0` and checks `minBet ≤ maxBet` only when `maxBet > 0`, which is exactly AD-15. Operator note, not a defect: a group with `minBet 50000, maxBet 0` stakes from 20k. |
| 3 | `onRoundStart` returns `Optional<Opened>` and accepts a lower sid | **Accept** | The Optional is an API detail: empty means a duplicate sid or `sid ≤ 0`, and in both cases no second bet task is scheduled. On the lower sid: the plan's table lists only `onRoundStart(newer sid)` from the in-flight states and does not cover OPEN, SETTLED or a lower sid at all. The implementation keeps the sid across `reset()` so that late frames of an abandoned round stay stale (plan AD-8 bullet 1). If it also rejected lower sids, a server sid reset would stop the bot betting for the rest of the JVM's life, and a reconnect would not help. Over 1705 → 1707 → 1705 at ~50 s spacing, a stale 1705 cannot overtake a newer one. Pinned by `SidRules` "a lower sid round start is accepted… and is then playable". |
| 4 | Extra fields on actions (`Confirmed.sid`, `SendCashout.multiplierH`, `Ended.sid/multiplierH/crashed`, `RoundClosed.sid/plan`) | **Accept** | Additive only. AD-13's one-shot `first crash loss: eid=, targetH=, multiplierH=, crashed=` cannot be logged without `multiplierH` and `crashed` on `Ended`. The sids feed the TRACE lines. No field the plan named was removed or renamed. |
| 5 | A stale 1707 neither counts a round nor runs the balance check | **Accept, plan amended (AM-2)** | AD-11's "every 1707" rested on "1707 is between rounds and no bet is in flight". That is false for a 1707 that the newer round start has already overtaken, and the plan's own §2 says frames are handled out of order. Running `onNewSession` there can skip the next round's bet through `sessionCheckInProgress`. The `crashRoundEnd` message counter and the silence watch still record the frame, and the old bet's outcome was already emitted as `abandoned`. |
| 6 | An early-firing bet task reschedules itself | **Accept** | The scheduler counts its delay on `nanoTime`, while the machine's `betAt` is on `currentTimeMillis`. A task that fires a millisecond early would otherwise lose the round, because `tryPlace` returns empty before `betAt`. The reschedule happens only while the machine is still `OPEN(sid)` with `betAt` in the future, so it is bounded and keeps AD-10's "CAS fails harmlessly" semantics. It adds no flag beside the machine. |
| 7 | Silence WARN once per episode, not once per bot per JVM | **Accept** | AD-9 ("the first silent window per bot logs one WARN") and Phase 3 ("WARN for the first window or DEBUG after that") are ambiguous, and per-episode is the reading that keeps a refused subscribe visible after any recovery. Volume is bounded: an episode needs ≥ 180 s of silence, and window 1 is also a reconnect rung, where `triggerFullReconnect` already emits its own WARN. So this adds at most one WARN per reconnect that already happens. It is the WARN tier CLAUDE.md assigns to watchdog expiry. |
| 8 | `onSubscribe` skips the balance check | **Accept** | AD-12 specifies `markConnectionAuthenticated()` → `reset()` → ladder compute/log, with no balance check. `onStart` already runs `onNewSession()`, so a subscribe-time check would be a second HTTP-capable read at every (re)connect, against AD-16. This follows the plan. |
| 9 | Alert `audience: internal` | **Accept** | The plan requires only "an audience label". The plan's `sum by (environmentId, botGroupId)` carries no `product`, so the alert cannot route to a product room (AD-V3). The remedy, group stake config or a back-office request, is ours. `internal` is in `AlertRulesAudienceTest.VALID_AUDIENCES` and the test passes. |

## Drift

None that needs Dev. The two plan corrections are documentation, and the code already matches
them.

## Out-of-scope changes

None. The diff only adds:

- `MessageTypesImpl` javadoc mentions `CrashMessageTypes`;
- `RoundSilenceWatch` gains `clearSubscribed()`, called from `beforeReconnect`, so the
  `subscribed=` text describes the current connection. This supports AD-9's WARN text.

No CASHOUT production code changed, and no game implementation was deleted.

## Verification section — runnable against Club env `8ca14218`?

I checked every step against the code on the branch:

- **V-0:** `EnvironmentDTO` exposes `productCode` and `webSocketMiniUrl`. The UUID
  `8ca14218-c98e-4734-a79e-c53732626337` and `wss://w79.sgame.club/websocket_mini` match the
  119 bring-up record.
- **V-1, V-2:** the CASHOUT release used the same `docker logs … | grep "MessageTypesRegistry
  initialized"`.
- **V-3:** `GET /api/v1/game/types` returns `GameTypeDTO(code, displayName)`.
- **V-4:** `GET` and `POST /api/v1/game/{brand}/{product}/{envId}`. `G4` is a valid `BrandCode`.
  The body fields `name/gameType/pluginName/offset/md5` exist on `GameDTO`.
- **V-5:** every create-body field exists on `BotGroupDTO`, including `initialDeposit`, which is
  within `bot.provisioning.max-initial-deposit=1e9`. P_119 has no username cap. The `health`
  fields `connectedBots/deadBots/bots[].status` exist, and `CONNECTION_AUTHENTICATED` is set
  only through `markConnectionAuthenticated()`.
  **Defect, fixed in the plan as AM-1:** polling `registeredCount == 3` before `/start` races
  the naming and funding of index 3. `/start` returns 400 while `REGISTRATION_PENDING`. The
  amended gate is `targetStatus != REGISTRATION_PENDING && depositedCount == 3`.
- **V-6:** `bot_auto_deposits_total{outcome=success}` is pre-registered per bot, so a 404
  correctly means FAIL.
- **V-7, V-9:** the metric names and tags (`botGroupId`, `outcome`, `cmd=crashRoundEnd`) match
  `BotMetrics`. The informational ≈ 0.27 is right: P(target > 2.86 | U(1.1, 5.0)) × ½ = 0.274.
- **V-8:** the one-shot DEBUG texts match `CrashBot` exactly. Staging runs DEBUG to
  `logs/detail/`.
- **V-10:** the JSON template renders `"botGroupId":"…"` and `"level":"WARN"` without spaces, so
  the greps match.
- **V-11:** `bot_reconnects_total`, `bot_watchdog_expired_total` and
  `gateway_budget_window_requests{environmentId}` exist. Note: the budget window is per
  environment and is shared with any other group on the Club env. The GATEWAY release measured
  119-Club at 32, so `< 100` is reachable, but a busy sibling group could move the "flat ±20"
  reading by itself. Treat a breach there as "check the siblings" before calling a FAIL.

## Amendments to the plan

I added `## Amendment — 2026-10-06 (Compliance Architect)` at the bottom of
`docs/plans/AVIATOR_BOT.md`. The original text is untouched.

- **AM-1 (Verification V-5):** the plan assumed `registeredCount == 3` means the group can be
  started. In fact `RegistrationWorker.persistProgress` writes `registeredCount` before naming
  and funding that index. `registrationState` is cleared only in `recordCompletion`, once
  `depositedCount` meets the target, and `BotGroupBehaviorService` returns 400 on `/start` while
  it is pending. Replacement poll: `targetStatus` no longer `REGISTRATION_PENDING` and
  `depositedCount == 3`; stop on `REGISTRATION_FAILED`.
- **AM-2 (AD-11):** the plan assumed every 1707 lies between rounds. Under ws-parser's 4
  out-of-order inbound workers, which the plan itself documents in §2, a stale 1707 can arrive
  after the next 1705, and a balance check there can skip that round's bet. Corrected rule: a
  1707 that the machine rejects as stale counts no round and runs no session check. Message
  counter and silence watch are unaffected.
