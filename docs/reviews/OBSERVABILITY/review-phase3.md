# Code Review — OBSERVABILITY Phase 3 (bot/group DEAD-seconds accumulators)

Branch: `feat/observability-metrics`
Reviewed diff: `git diff 7591f77..44ba23b`
HEAD: `44ba23b Phase 3 OBSERVABILITY: bot/group DEAD-seconds accumulators`

## Verdict

**APPROVE WITH NITS.** No blocker or major findings. Credit mechanics are correct on the dominant single-writer paths; the residual races are benign (small over- or under-credit of a few seconds, never double-registration of phantom data). All Phase 3 acceptance points (AD 3 STOPPED-excluded, lazy-registered counters, MDC-tagged increments, no-MDC aggregate gauges, no scope-creep into Phase 4/5) hold.

## Credit-mechanics correctness

| Path | Stamp set | Stamp cleared | Increment site | Tags source | Verdict |
|---|---|---|---|---|---|
| Bot enters DEAD via `transitionStatus(DEAD)` | `this.deadSince = Instant.now()` (L294) | — | (none) | — | OK — defensive re-stamp on re-entry is dead code (`prev==next` early-return guards it); harmless |
| Bot exits DEAD via `transitionStatus(non-DEAD)` | — | inside `creditDeadSeconds()` | `metrics.incBotDeadSeconds(seconds)` | MDC of the transitioning thread (Netty IO / reconnect VT / scenario pool) | OK — but tag set is whatever MDC the caller happens to carry; today every reconnect path is wrapped in `mdcWrap`/`mdcConsumer`, so the snapshot is applied. Defensive branch (DEAD is terminal in current code). |
| Bot terminal DEAD via `cleanup()` | — | inside `creditDeadSeconds()` | `metrics.incBotDeadSeconds(seconds)` | `mdcWrap(this::creditDeadSeconds)` applies `mdcSnapshot` | OK |
| Group enters DEAD via `markAsDead()` | `groupDeadSince = Instant.now()` (idempotent on re-entry) | — | (none) | — | OK |
| Group close-out via `stopAllBots(metrics)` | — | inside `creditGroupDeadSeconds(metrics)` | `metrics.incGroupDeadSeconds(seconds)` | `BotMdc.setGroupContext(groupId, envId)` re-applied at both REST callsites | OK — credit happens *before* per-bot cleanup, so the window is measured up to the close-out moment, not extended by executor-shutdown wait |
| Legacy `stopAllBots()` no-arg | — | inside `creditGroupDeadSeconds(null)` | (skipped: metrics==null short-circuit) | — | OK — stamp still cleared so a later `stopAllBots(metrics)` is not double-counted |

## Race-condition analysis

Concurrent transitions out of DEAD are theoretically possible but practically benign:

1. **`Bot.creditDeadSeconds()`.** Volatile read-then-write is not atomic. Two threads can each see `since != null`, each null the field, each call `incBotDeadSeconds(seconds)` — double credit of the same window (small over-count). However, `transitionStatus` itself is already racy on `this.status = next` without a CAS guard (this pre-dates Phase 3), and DEAD is terminal in current code (`prev == DEAD` only fires from the defensive branch). The only realistic concurrent firing is `cleanup()` racing with a residual `transitionStatus` from a still-running reconnect VT; the worst case is one extra `incBotDeadSeconds(N)` of the same DEAD window. Acceptable per the reviewer brief.
2. **`BotGroupRuntime.creditGroupDeadSeconds()`.** Same shape — volatile read-clear-credit. The realistic racer is `BehaviorService.stop()` from a REST thread overlapping the start-failure rollback path on a different invocation. Both are user-action-driven; concurrent invocations are gated upstream by `runningGroups.containsKey(id)`. The race window is very narrow. Acceptable.
3. **`markAsDead()` vs. `stopAllBots(metrics)`.** Health monitor stamps `groupDeadSince` from one thread while the REST `stop` thread enters `creditGroupDeadSeconds`. The volatile field's happens-before semantics mean each thread sees a coherent value (either null or a stamp). At worst a stamp set *after* the credit reads `null` is observed as "no DEAD window" — a tiny under-credit if the group entered DEAD micro-seconds before stop was clicked. Acceptable.

**No mitigation required** — at worst a counter is off by single-digit seconds on a contended close-out. The counter is monotonic and non-decreasing in all cases (no negative increments, no resets).

## Findings

### 1. [nit] Redundant `BOT_*` prefix allow-list entry for non-`bot_*` meter names — `BotMdcTagsMeterFilter.java:47-54`

`bots_dead_currently` and `groups_dead_currently` were added to `AGGREGATE_METER_NAMES`. The filter is gated by `name.startsWith("bot_")` (note the trailing underscore — line 40 `PREFIX_BOT = "bot_"`). Neither new name starts with `bot_`: they start with `bots_` and `groups_`. The allow-list short-circuit is therefore unreachable for these two names — the prefix check on L62 returns the id unchanged anyway. This is defensive-in-depth and matches the existing entries (`bots_managed`, `bot_groups_running`, `ws_connections_open`, `bots_by_status`), three of which are also already not `bot_`-prefixed. Cost is zero; symmetry with existing entries is the point. Keep as-is.

### 2. [nit] `Bot.transitionStatus` "defensive re-stamp" comment is misleading — `Bot.java:291-294`

```java
if (next == BotStatus.DEAD) {
    // Stamp the start of this DEAD window. deadSince is cleared on exit
    // (below) or at cleanup() — so seeing a non-null value here would mean
    // the previous exit branch missed; we still re-stamp defensively.
    this.deadSince = Instant.now();
```

The "we still re-stamp defensively" wording suggests this branch can be reached with a non-null `deadSince`. It cannot under the current code path: the `prev == next` early-return on L287 short-circuits DEAD→DEAD, and any DEAD→X→DEAD path clears `deadSince` on the first transition. The defensive re-stamp would only fire if a future revive path forgets to clear. Reword to "Stamp the start of this DEAD window. `deadSince` is cleared on every exit, so under correct usage it is null here" or similar. No functional change.

### 3. [nit] `creditDeadSeconds` JavaDoc claims "Idempotent" but the code is only idempotent on a *new* DEAD window — `Bot.java:305-308`

> Idempotent — calling twice without a new DEAD entry is a no-op.

True at the single-threaded level: the second call sees `since == null` and returns. Under concurrent calls (see race analysis #1), it can double-credit one window. The JavaDoc reads as a stronger thread-safety claim than the implementation provides. Suggest: "Single-threaded idempotent" or note the volatile race. Minor doc nit.

### 4. [nit] `BotGroupRuntime.stopAllBots(BotMetrics)` JavaDoc references `BotMdc.BOT_GROUP_ID` from within infrastructure — `BotGroupRuntime.java:273`

The `creditGroupDeadSeconds` javadoc says "set `BotMdc.BOT_GROUP_ID`… before calling" but the responsibility for setting that MDC actually lives in `BotGroupBehaviorService` (which wraps both `stop()` and the start-failure rollback in `BotMdc.setGroupContext/clear`). The doc is descriptive (not prescriptive of an API contract) and correctly identifies the contract — keep, or move the wording to caller-side. Style only.

### 5. [nit] Two `BotMdc.clear()` blocks in `BotGroupBehaviorService` duplicate the same try/finally pattern — `BotGroupBehaviorService.java:247-257, 391-396`

Both `stop()` and the start-failure rollback now wrap `stopAllBots(botMetrics)` in `setGroupContext`/`clear`. Identical pattern; could be extracted to a private helper `void withGroupContext(BotGroupRuntime r, Runnable body)`. Optional refactor. Not required for correctness.

### 6. [nit] No assertion on counter tags in `BotGroupRuntimeDeadSecondsTest.stopAllBotsCreditsDeadWindow` — `BotGroupRuntimeDeadSecondsTest.java:62-74`

The test sets `BotMdc.setGroupContext` and looks up the counter by name only. A stricter assertion would query `registry.find(...).tag(BotMdc.BOT_GROUP_ID, "g-deadtest").counter()` to verify the tag application. This is exercised independently in `BotMetricsTest.incGroupDeadSeconds_addsAmountAndAttachesMdcTags`, so coverage is intact. Minor nit only.

## Looks good

- **AD 3 (STOPPED excluded) implemented correctly.** The bot's DEAD window is credited up to the moment of `cleanup()` (i.e. up to the user-initiated stop). STOPPED itself contributes no seconds. JavaDoc on both `incBotDeadSeconds` and `stopAllBots` calls this out explicitly.
- **Non-positive guard in both new `BotMetrics` methods.** `if (seconds <= 0) return;` prevents phantom-zero series from registering (a real risk because `Duration.toSeconds()` truncates: a 900 ms DEAD window rounds to 0). Confirmed by the negative-value tests in `BotMetricsTest`.
- **Credit-before-cleanup ordering in `stopAllBots(metrics)`.** Group-level dead window stops the clock at the close-out moment, not at executor shutdown completion. This is the right call — the comment on L223-226 documents the choice.
- **Idempotent `markAsDead` on re-entry.** The stamp does not move forward on repeated DEAD events from the health monitor, preserving the original entry time. Test coverage on L40-47 of `BotGroupRuntimeDeadSecondsTest`.
- **Null-metrics overload preserved.** `stopAllBots()` no-arg delegate keeps `BotGroupRuntimeTest`'s four call sites green, and the new dead-seconds test `stopAllBotsNullMetricsSkipsIncrement` proves the stamp is still cleared so a later metric-aware call does not double-credit.
- **`mdcWrap(this::creditDeadSeconds)` at `Bot.cleanup()`.** Re-applies `mdcSnapshot` on the BehaviorService thread (which lacks the bot's MDC); null-safe if the snapshot was never captured (e.g. cleanup called before `initialize()` completed). Aligns with the Phase 2 wrapper contract.
- **Aggregate gauges follow Phase 2 conventions.** `bots_dead_currently` / `groups_dead_currently` registered via `behaviorService` method references, no MDC tags applied, names added to the meter-filter allow-list for defense-in-depth. Cardinality: 1 series each.
- **Scope discipline.** Diff touches only the six expected production files plus tests. No changes to `BotMdc.java`, log4j config, Promtail, `application.properties`, Dockerfile, `docker-compose.yml`, or `pom.xml`. No winnings (Phase 4) or share metrics (Phase 5) introduced. `grep -i 'winning|share|rtp|payout|jackpot'` on the diff is empty.
- **Naming consistency.** `incBotDeadSeconds` / `incGroupDeadSeconds` match the Phase 2 `inc*` style; AD 11 `bot_*` / `group_*` prefix held.
- **Test discipline.** No real `Thread.sleep` in the dead-seconds tests — `Instant.now().minusSeconds(N)` reflection trick avoids flakiness while still exercising the elapsed-seconds computation. Reflection-based field access is contained to test helpers and clearly marked.
- **Constructor injection in `BotGroupBehaviorService`.** `BotMetrics` added as a final field with constructor parameter; `@InjectMocks` in `BotGroupBehaviorServiceTest` automatically wires the `@Mock BotMetrics botMetrics`. No alternative constructor variant exists that bypasses it.

## Notes

- The `prev == BotStatus.DEAD` branch in `transitionStatus` is unreachable in current production code (DEAD is terminal — no revive path exists). It is correctly documented as defensive on L298-300. If a future REST "revive" endpoint is added, the existing branch will credit correctly without further changes, which is the right design.
- The MeterFilter allow-list now has six entries, four of which already do not start with `bot_`. Consider whether `PREFIX_BOT` should be widened to `bot` (without underscore) to bring `bots_*` and `bot_groups_*` under the filter's prefix check — but doing so would also catch any future `bots_*` / `botX_*` meters that *should* receive MDC tags. Current scheme (explicit allow-list, narrow prefix) is conservative; flag for Phase 6 dashboards only if a third match style is needed.
- The race in `creditDeadSeconds` could be eliminated by switching `deadSince` to `AtomicReference<Instant>` and using `getAndSet(null)`. The single-line change would close the double-credit window. Not required — the worst case is over-counting a few seconds on one specific concurrent path that is unlikely in current code — but it is a cheap upgrade if anyone wants belt-and-suspenders. Same applies to `BotGroupRuntime.groupDeadSince`.
