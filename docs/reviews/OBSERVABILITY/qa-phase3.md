# QA — OBSERVABILITY Phase 3

**Verdict:** PASS
**Build:** `mvn test` -> 355 tests, 0 failures, 0 errors (baseline 336; Dev shipped +18 at 354; QA added +1 bonus test for multi-cycle DEAD-revive)

## Tests added / updated

- `src/test/java/com/vingame/bot/domain/bot/core/BotDeadSecondsTest.java` (Dev: 6 tests; QA: +1 `multipleDeadReviveCyclesSumIndependently` covering AD 3 multi-window sum)
- `src/test/java/com/vingame/bot/infrastructure/runtime/BotGroupRuntimeDeadSecondsTest.java` (Dev: 5 tests)
- `src/test/java/com/vingame/bot/infrastructure/observability/BotMetricsTest.java` (Dev: +4 tests — `incBotDeadSeconds` add+tag, non-positive guard, `incGroupDeadSeconds` add+tag, non-positive guard)
- `src/test/java/com/vingame/bot/infrastructure/observability/ObservabilityConfigTest.java` (Dev: +3 tests — gauges read service, exclusion-list under populated MDC)
- `src/test/java/com/vingame/bot/infrastructure/observability/BotMdcTagsMeterFilterTest.java` (Dev: +2 allow-list entries)
- `src/test/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorServiceTest.java` (Dev: `@Mock BotMetrics` wired into constructor)

## Credit-semantics matrix

| Path | Stamp set | Counter credited | Stamp cleared | Test |
|---|---|---|---|---|
| `transitionStatus(* -> DEAD)` first entry | yes | no (entry only) | n/a | `enterDeadStampsTimestamp` |
| `transitionStatus(DEAD -> DEAD)` re-entry | no (guard `prev == next` returns early) | no | n/a | `deadToDeadIsNoOp` |
| `transitionStatus(DEAD -> non-DEAD)` revive | n/a | yes (elapsed seconds) | yes | `exitDeadCreditsCounterAndClears`, `deadToTerminalDirectCreditsCounter` |
| Multiple DEAD-revive cycles | yes each cycle | yes each cycle, summed | yes each cycle | `multipleDeadReviveCyclesSumIndependently` (QA-added) |
| `cleanup()` while `deadSince != null` | n/a | yes (terminal window) | yes | `cleanupOnDeadBotCreditsTerminalWindow` |
| `cleanup()` while `deadSince == null` | n/a | no | n/a | `cleanupOnLiveBotDoesNotCredit` |
| `transitionStatus(* -> STOPPED-like)` from non-DEAD | no | no | n/a | covered by enum invariant (no STOPPED in `BotStatus`; AD 3 holds for any non-DEAD entry path) |
| `BotMetrics.incBotDeadSeconds(<=0)` | n/a | no (counter not registered) | n/a | `incBotDeadSeconds_nonPositiveValuesAreIgnored` |
| `markAsDead()` first call | yes (`groupDeadSince`) | no | n/a | `markAsDeadStampsAndIsIdempotent` |
| `markAsDead()` re-entry | preserved (idempotent) | no | n/a | `markAsDeadStampsAndIsIdempotent` |
| `stopAllBots(metrics)` with open window | n/a | yes | yes | `stopAllBotsCreditsDeadWindow` |
| `stopAllBots(metrics)` no DEAD window | n/a | no (counter not registered) | n/a | `stopAllBotsNoDeadWindowDoesNotCredit` |
| `stopAllBots()` (no-arg legacy) with open window | n/a | no (null metrics) | yes (stamp cleared to avoid double-credit) | `stopAllBotsNullMetricsSkipsIncrement` |
| Double `stopAllBots(metrics)` | n/a | once, second call no-op | yes | `doubleStopDoesNotDoubleCredit` |

All AD-3 invariants hold: only DEAD windows count, STOPPED is intentional and excluded.

## MDC propagation

| Surface | Mechanism | Verified |
|---|---|---|
| `Bot.transitionStatus(DEAD -> non-DEAD)` revive (defensive branch) | runs on whatever thread triggered the transition; MDC already established by Phase 2 wraps | implicit (existing Phase 2 coverage) |
| `Bot.cleanup()` terminal credit | `mdcWrap(this::creditDeadSeconds).run()` — null-safe on missing snapshot (verified at `Bot.java:494-510`) | yes (cleanup test asserts tagged registration; mdcWrap reviewed) |
| `BotGroupBehaviorService.stop()` -> `stopAllBots(metrics)` | wrapped `BotMdc.setGroupContext(groupId, environmentId)` + `try/finally BotMdc.clear()` | code reviewed; `stopAllBotsCreditsDeadWindow` exercises identical MDC setup |
| Start-failure rollback path | same wrap inside catch in `start()` | code reviewed; behavior identical |

Group counter is tagged with `botGroupId` and `environmentId` per `incGroupDeadSeconds_addsAmountAndAttachesMdcTags`. Bot counter is tagged with `botGroupId`/`environmentId`/`gameType` per `incBotDeadSeconds_addsAmountAndAttachesMdcTags`.

## Aggregate gauge protection

| Gauge | Registered empty-tagged | Allow-list excludes from MDC tagging | Test |
|---|---|---|---|
| `bots_dead_currently` | yes | yes | `botsDeadCurrentlyGauge_readsFromService`, `deadCurrentlyGauges_areOnTheAggregateExclusionList`, `map_aggregateGauge_doesNotAttachMdcTags` |
| `groups_dead_currently` | yes | yes | `groupsDeadCurrentlyGauge_readsFromService`, `deadCurrentlyGauges_areOnTheAggregateExclusionList`, `map_aggregateGauge_doesNotAttachMdcTags` |

`deadCurrentlyGauges_areOnTheAggregateExclusionList` populates MDC before gauge registration and asserts no `botGroupId` tag — the requested coverage holds.

## Cardinality

Confirmed only `botGroupId`, `environmentId`, `gameType` (plus the existing bounded enum/string tags) appear on `bot_dead_seconds_total` and `group_dead_seconds_total`. `counterIdContainsExpectedTagSet` asserts absence of `botUserName`/`botId`. AD 5 holds.

## Health DTO compatibility

`Bot.totalBetsPlaced` / `totalBetAmount` AtomicLongs untouched; UI continues to consume them via `BotHealthDTO`. `bot_dead_seconds_total` and `group_dead_seconds_total` live only in Prometheus — intentional.

## Dev's naming deviation

Dev shipped `incBotDeadSeconds(long)` / `incGroupDeadSeconds(long)` rather than the plan's `addBotDeadSeconds(double)` / `addGroupDeadSeconds(double)`. Internally calls `Counter.increment(long)`. **Acceptable**:

- Method-name convention matches every other Phase 2 method (`incBotMessage`, `incBotFailure`, ...). Consistency wins.
- `long`-typed seconds matches `Duration.toSeconds()` return type at the callsite, eliminates implicit cast.
- Counter semantics identical — Micrometer's `Counter.increment(double)` accepts the widened value either way.
- Non-positive guard at the public boundary (rather than relying on Counter rejecting negatives — which it does not; it silently accepts and breaks monotonicity).

## Concerns / follow-ups

- **Misleading comment in `transitionStatus(* -> DEAD)`**: "seeing a non-null value here would mean the previous exit branch missed; we still re-stamp defensively." This is technically unreachable on `DEAD -> DEAD` (guarded by the `prev == next` early return), and on a true revive cycle the exit branch clears `deadSince`. The defensive re-stamp would only fire if external reflection mutated `deadSince`. Cosmetic; not a bug. Suggest tightening the comment in a future polish pass.
- **`runWsReconnectLoop` / `runAuthThenWsLoop` success paths do not currently transition out of DEAD** (per Implementation Notes in plan — DEAD is terminal in code). The defensive transition-out branch is therefore dead code in production but exercised by tests. Fine; leave in per Architect's explicit "keep the exit branch anyway" note in the plan.
- **No BehaviorService-surface test** asserting that `stop()` actually wraps the `stopAllBots(metrics)` call with `BotMdc.setGroupContext`. Coverage is split: `BotGroupRuntimeDeadSecondsTest.stopAllBotsCreditsDeadWindow` exercises the identical MDC-wrapping recipe with `BotMdc.setGroupContext()` directly. Acceptable but a small integration test that drives `service.stop(groupId)` and asserts a tagged counter could close the gap. Optional follow-up.

## Failures

None.
