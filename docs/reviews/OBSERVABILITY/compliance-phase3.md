# Compliance — OBSERVABILITY Phase 3

Branch: `feat/observability-metrics`
Plan reviewed: `docs/plans/OBSERVABILITY.md` (Section 5 Phase 3 + AD 3, 5, 10, 11)
Diff reviewed: `git diff 7591f77..44ba23b`

## Verdict

**COMPLIANT** (PASS)

## Per-deliverable table

| # | Plan deliverable | Status | Evidence |
|---|---|---|---|
| 1 | `BotMetrics`: `addBotDeadSeconds` / `addGroupDeadSeconds` | PRESENT — naming deviates (`incBotDeadSeconds` / `incGroupDeadSeconds`); see Naming verdict | `BotMetrics.java:195-215` |
| 2a | `Bot.java`: `deadSince` field, stamp on entry to DEAD | PRESENT | `Bot.java:81-82`, `Bot.java:288-295` |
| 2b | `Bot.java`: credit on exit (prev=DEAD, next!=DEAD) | PRESENT | `Bot.java:295-301` |
| 2c | `Bot.java`: credit in `cleanup()` for terminal DEAD | PRESENT, via `mdcWrap` so the cleanup-thread tags resolve correctly | `Bot.java:167-172` |
| 3a | `BotGroupRuntime.java`: `groupDeadSince` + stamp in `markAsDead()` | PRESENT (idempotent on re-entry — preserves first stamp, matches plan AD 3) | `BotGroupRuntime.java:70-72`, `:174-177` |
| 3b | Credit in `stopAllBots` close-out | PRESENT — added `stopAllBots(BotMetrics)` overload + null-safe legacy `stopAllBots()` | `BotGroupRuntime.java:195-228`, `:267-281` |
| 4 | `BotGroupBehaviorService`: wire `BotMetrics`, set group MDC around credit call | PRESENT — `setGroupContext` / `clear` wrapped around both stop sites (normal stop + start-failure cleanup) | `BotGroupBehaviorService.java:247-258`, `:391-400` |
| 5 | `ObservabilityConfig`: `bots_dead_currently` / `groups_dead_currently` aggregate gauges | PRESENT | `ObservabilityConfig.java:61-74` |
| 6 | `BotMdcTagsMeterFilter`: allow-list extended | PRESENT | `BotMdcTagsMeterFilter.java:51-52` |
| 7 | Tests | PRESENT — `BotDeadSecondsTest` (7 cases), `BotGroupRuntimeDeadSecondsTest` (5 cases), 4 new `BotMetricsTest` cases, 3 new `ObservabilityConfigTest` cases, extended filter and behavior-service tests | new test files + diff |

## AD coverage

- **AD 3 (Downtime semantics: DEAD only, STOPPED excluded).** RESPECTED. Counter is only credited on DEAD-window close-out; STOPPED is excluded by construction (no DEAD stamp exists outside a real failure path). `markAsDead` is idempotent (does not overwrite the original entry stamp on re-entry) — consistent with the "group cannot enter DEAD without first being deliberately started" invariant.
- **AD 5 (Cardinality: no `username`/`botId`).** RESPECTED. `BotMetrics.incBotDeadSeconds` / `incGroupDeadSeconds` use the shared `mdcTags()` helper, which only emits `botGroupId`, `environmentId`, `gameType`. Tests assert this explicitly (`BotMetricsTest:240-274`).
- **AD 10 (MDC-via-`mdcTags()` per call; MeterFilter as allow-list/defense-in-depth).** RESPECTED. New counters go through `mdcTags()` exactly like Phase 2 counters. Aggregate gauges added to the filter allow-list. `Bot.cleanup()` re-applies the bot's MDC snapshot via `mdcWrap(this::creditDeadSeconds)` (cleanup is invoked from the BehaviorService caller thread with no MDC). `BotGroupBehaviorService` wraps both `stopAllBots(botMetrics)` callsites in `setGroupContext` / `clear`.
- **AD 11 (Naming: `bot_*` / `group_*` / `bots_*` / `groups_*`, lowercase + underscores, plural for aggregate gauges).** RESPECTED. `bot_dead_seconds_total`, `group_dead_seconds_total`, `bots_dead_currently`, `groups_dead_currently`. Counters end in `_total`; aggregates use plural form.

## Scope check

Production Java diff is exactly the six expected files: `Bot.java`, `BotGroupRuntime.java`, `BotGroupBehaviorService.java`, `BotMetrics.java`, `ObservabilityConfig.java`, `BotMdcTagsMeterFilter.java`. Tests are the two new (`BotDeadSecondsTest`, `BotGroupRuntimeDeadSecondsTest`) plus the four extensions (`BotMetricsTest`, `ObservabilityConfigTest`, `BotMdcTagsMeterFilterTest`, `BotGroupBehaviorServiceTest`). No `pom.xml`, `application.properties`, `docker-compose.yml`, `BotMdc.java`, Promtail, log4j, or other unexpected files touched.

## Phase 2 carry-over check

- `BotMetrics.mdcTags()` still in place and used by all Phase 2 counters (lines 93, 101, 114, 123, 131, 144, 154, 169, 178, 200, 214). New `inc*DeadSeconds` calls follow the same pattern.
- `BotMdcTagsMeterFilter` exclusion allow-list still contains the original four aggregates plus the two new ones.
- `Bot.transitionStatus` `if (prev == next) return` polish guard from Phase 2 is intact (`Bot.java:287`). The DEAD-on-entry stamp / DEAD-on-exit credit branches both sit AFTER this guard, so re-entering DEAD does not double-stamp or double-credit. Tested in `BotDeadSecondsTest.deadToDeadIsNoOp`.
- `Bot.mdcWrap` helper still present (`Bot.java:494`) and now used for the cleanup credit path (`Bot.java:172`).

## Naming verdict — `inc*` vs `add*`

Dev shipped `incBotDeadSeconds(long)` / `incGroupDeadSeconds(long)` instead of the `addBotDeadSeconds(double)` / `addGroupDeadSeconds(double)` that Section 5 Phase 3's bullet 3 literally specifies. Both produce monotonically-increasing counter behavior (`Counter.increment(amount)` is what `Counter.add(amount)` would compile down to anyway in Micrometer).

Decision: **accept as consistent with Phase 2.** Every other method on `BotMetrics` is `incXxx(...)` — `incBotMessage`, `incBotFailure`, `incBotReconnect`, `incBotAutoDeposit`, `incBotWatchdogExpired`, `incBotWsEvent`, `incBetPlaced`, `incBotBetAmount`, `incLogin`, `incVerifyToken`. Renaming to `add*` for only these two would be inconsistent and harder for callers to discover. Type signature `long` is correct (seconds are an integer quantity; `Duration.toSeconds()` returns `long`). No amendment needed — the plan body wording is a minor drafting inconsistency, not a contract.

## Aggregate gauges verdict

Plan line 285 and Verification Step 10 explicitly request `bots_dead_currently` and `groups_dead_currently`. Dev shipped both. The plan text is authoritative; user's earlier softer guidance about "deriving from `bots_by_status`" is reasonable but does not override what was written into the plan. **In-scope; shipped correctly.** No amendment.

The `groups_dead_currently` gauge is wired against `BotGroupBehaviorService.countGroupsDeadCurrently()` which counts runtimes with a non-null `groupDeadSince` — this is the right denominator (matches the runtime-side semantics), distinct from `actualStatus == DEAD` because the stamp is cleared at `stopAllBots` even if the status lingers.

## Concurrency verdict

The credit pattern in `Bot.creditDeadSeconds` and `BotGroupRuntime.creditGroupDeadSeconds` is the classic non-atomic read-clear-credit:

```java
Instant since = this.deadSince;
if (since == null) return;
this.deadSince = null;
long seconds = Duration.between(since, Instant.now()).toSeconds();
metrics.incBotDeadSeconds(seconds);
```

Theoretical race: two threads both observe `since != null`, both null it out, both credit — double-credit. Or one thread credits and clears while another is mid-`transitionStatus(DEAD)` stamping a new `since`, losing the new stamp.

**Analysis of actual callers:**

1. **`Bot` side.** Entry to DEAD happens at exactly one callsite: `performReauth` line 448, on the bot's single reauth virtual thread (one such thread per bot, sequential per bot). Exit-from-DEAD is dead code today (`BotStatus.DEAD` is terminal — see plan Implementation Notes line 430). The only real close-out is `cleanup()` from `BotGroupRuntime.stopAllBots` (called sequentially per-bot from the BehaviorService thread). So in practice: DEAD entry on thread A, then cleanup on thread B — strictly happens-after the bot has been stopped. No concurrent entry-into-DEAD with cleanup is reachable without the bot being mid-flight, and `stopped=true` is set before cleanup credits.

2. **`BotGroupRuntime` side.** `markAsDead()` runs on the health-monitor scheduler thread. `stopAllBots(metrics)` runs on the BehaviorService caller thread. They are not concurrent in any normal flow: health monitor calls `handleBotGroupDeath` → `markAsDead`, then the admin (or auto-stop logic) calls `stopAllBots` later. Even if they did interleave once, the worst case is a missed credit of <1 round (a few seconds) — not a correctness issue for a downtime counter. The double-stopAllBots case is explicitly tested (`doubleStopDoesNotDoubleCredit`).

**Verdict: race acceptable. No `AtomicReference` / synchronization required. Plan does not need a concurrency note.** The `volatile` declaration is sufficient because (a) the in-practice callers are sequential per-bot/per-group, and (b) the counter is a monotonic estimator of downtime, not an audit ledger — under-/over-counting by single-digit seconds at race boundaries is within the measurement's intrinsic precision (1-second truncation from `Duration.toSeconds()`).

The plan's existing Implementation Note already flags "DEAD is currently terminal" and "keep the exit branch defensively" — Dev's volatile-only choice is the right shape for that scope.

## Out-of-scope changes

None. The diff is exactly the deliverables listed.

## Amendments to the plan

None.

---

Compliance verdict: **PASS**
