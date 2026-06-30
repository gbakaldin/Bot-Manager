# Compliance — OBSERVABILITY Phase 2

Branch: `feat/observability-metrics`
Plan reviewed: `docs/plans/OBSERVABILITY.md` (amended in this review)
Diff reviewed: `git diff c11fe3c..9df9011`

## Verdict

**PLAN_AMENDED**

Phase 2 implementation is otherwise compliant. Two surgical amendments to the plan are required: AD 10 (per-callsite MDC read replaces filter-only) and the Phase 2 reconnect-loop reason mapping (two loops were inverted).

---

## Per-deliverable status

| # | Deliverable | Status | Notes |
|---|---|---|---|
| 1 | `BotMetrics` central holder | PRESENT | `infrastructure/observability/BotMetrics.java`. Exposes `inc*` methods listed in plan. `@Component`. Constructor-injects `MeterRegistry`. |
| 2 | MDC-aware tag mechanism | DEVIATED (approved — see Deviation 1) | `BotMdcTagsMeterFilter` exists as defense-in-depth + aggregate allow-list. Per-call MDC read is done in `BotMetrics.mdcTags()` on each `inc*`. This is the correct mechanism; the plan's filter-only AD 10 was wrong. |
| 3 | Aggregate gauges (`bot_groups_running`, `bots_managed`, `ws_connections_open`, `bots_by_status`) | PRESENT | `ObservabilityConfig.registerAggregateGauges` registers all four. `bots_by_status` is one gauge per `BotStatus` value, tagged `status=...`. Accessor methods added to `BotGroupBehaviorService`. |
| 4 | `Bot.java` increments at all listed callsites | PRESENT | `transitionStatus(DEAD)` → `incBotFailure`; `triggerFullReconnect` → `incBotReconnect(normalizeReconnectReason(reason))`; `runWsReconnectLoop` start → `incBotReconnect("ws-disconnect")`; `runAuthThenWsLoop` start → `incBotReconnect("reauth-cycle")`; `deposit` callback → `incBotAutoDeposit(success)`; `creditBalance` → `incBetPlaced(amount)`; `onWsStatusChange` `CONNECTED/AUTHENTICATING_WS/DISCONNECTED` → `incBotWsEvent`. All guarded by `metrics != null` null-check (BotFactory wires non-null, but defensive). |
| 5 | `BettingMiniGameBot` increments | PRESENT | `onSubscribe / onStartGame / onUpdate / onEndGame` → `incBotMessage(cmd)`. `onWatchdogExpired` → `incBotWatchdogExpired()` BEFORE `triggerFullReconnect(...)` (matches plan note about signal ordering). |
| 6 | `ApiGatewayClient` login + verify-token instrumentation | PRESENT | `authenticate(...)` wraps the `AuthClient.authenticate()` call in try/catch — success → `incLogin(true)`, exception → `incLogin(false)` then rethrow (preserves `BotFactory` error semantics). `getBalance(...)` increments `incVerifyToken(true/false)` on success / `IOException` / other exceptions, with a `startsWith("User: ")` guard to avoid double-incrementing the same error path. |
| 7 | `BotFactory` wires `BotMetrics` | PRESENT | Constructor takes `BotMetrics botMetrics`; `.setMetrics(botMetrics)` is added to the builder chain before `.initialize()`. |
| 8 | Tests for each + aggregate gauges | PRESENT | `BotMetricsTest` (11 tests) covers each `inc*` method, empty-MDC path, partial MDC, per-group time series, cardinality cap (no `botUserName` / `botId`). `BotMdcTagsMeterFilterTest` (5 tests) covers `bot_*` tagging, aggregate exclusion, non-bot pass-through, empty MDC, partial MDC. `ObservabilityConfigTest` (5 tests) verifies the four aggregate gauges read from the service and do not get MDC tags. |

All 8 deliverables landed.

---

## Architecture Decision coverage

| AD | Status | Notes |
|---|---|---|
| AD 5 (Cardinality cap: `groupId × environmentId × gameType`, plus bounded enums) | RESPECTED | `BotMetrics.addIfPresent` only emits the three identity tags. Bounded enum tags: `cmd ∈ {subscribe, startGame, updateBet, endGame}`, `outcome ∈ {success, failure}`, `reason ∈ {watchdog, ws-disconnect, reauth-cycle}`, `event ∈ {connected, authenticating, disconnected}`. No `username` / `botId` anywhere in `BotMetrics` or call sites. Test `counterIdContainsExpectedTagSet` asserts the no-per-bot-tag invariant. |
| AD 10 (MDC-as-tags) | NEEDS_AMENDMENT → AMENDED | Plan said "MeterFilter reads MDC". Verified against `micrometer-core 1.14.5` `MeterRegistry.java:637-647` — `getOrCreateMeter` consults `preFilterIdToMeterMap` by the pre-filter Id BEFORE invoking the filter chain, so the filter-only mechanism cannot produce per-group time series. Dev's per-callsite `Counter.Builder` MDC tagging is the correct mechanism. AD 10 amended in plan. |
| AD 11 (Naming: `bot_*` / `bots_*` / `group_*`, `_total` suffix, lowercase + underscores, plural for aggregates) | RESPECTED | All 10 counter names in `BotMetrics` end in `_total`, prefix `bot_`. Aggregate gauges `bot_groups_running`, `bots_managed`, `ws_connections_open` plus per-status `bots_by_status`. Lowercase + underscores throughout. The aggregate gauge `bot_groups_running` is `bot_` not `bots_` — minor naming inconsistency vs the plan's "plural for aggregate gauges" rule, but matches the plan's own Phase 2 spec verbatim. Not flagged as a violation. |

---

## Scope check

Production-code touched files (all within allowed packages):
- `domain/bot/core/Bot.java`, `domain/bot/core/BettingMiniGameBot.java`
- `domain/bot/service/BotFactory.java`
- `domain/botgroup/service/BotGroupBehaviorService.java` (aggregate accessors only — read-only methods, no behavior change)
- `infrastructure/client/ApiGatewayClient.java`
- `infrastructure/observability/BotMetrics.java`, `BotMdcTagsMeterFilter.java`, `ObservabilityConfig.java` (new package, all new files)

NOT touched (as required): `BotMdc`, log4j config, Promtail, Dockerfile, `pom.xml`, `docker-compose.yml`, application.properties, any other production source.

Phase 1 carry-overs verified intact:
- `pom.xml:111-112` still has `io.micrometer:micrometer-registry-prometheus`.
- `application.properties:28` still exposes `prometheus` actuator endpoint.
- `grafana/provisioning/datasources/{loki.yml,prometheus.yml}` UIDs still pinned.

Scope is clean.

---

## Deviation verdicts

### Deviation 1 — MeterFilter doesn't work; MDC reading moved into BotMetrics

**Verdict: APPROVED. Plan amended.**

Dev's diagnosis is correct and technically grounded. Verified the cited Micrometer behavior directly against `micrometer-core 1.14.5` source (`MeterRegistry.java:637-647`):

```java
private Meter getOrCreateMeter(...) {
    Meter m = preFilterIdToMeterMap.get(originalId);  // ← consulted BEFORE filter
    ...
    Id mappedId = getMappedId(originalId);             // ← filter runs only on first miss
    ...
}
```

The pre-filter id is keyed by `name + supplied tags`. If two callsites both register `bot_messages_total{cmd=endGame}` (no group tag attached at builder time), both hit the cache from the first registration onward — the filter's MDC read for the second call is dead code. Per-group time series cannot emerge from this design.

Dev's test `BotMetricsTest.differentGroupsCreateSeparateTimeSeries` exercises exactly this case (two MDC values, same `inc*` call, asserts two distinct counters). It passes because Dev attaches the MDC tags to `Counter.Builder` before `register(registry)`, making the pre-filter id distinct per group.

This is a genuine technical mistake in the original AD 10, not a Dev preference. AD 10 has been amended in `docs/plans/OBSERVABILITY.md` to describe the corrected mechanism, and the original Phase 2 file-touch list is left unchanged (the filter still exists, just with a narrower role).

### Deviation 2 — reconnect reason mapping

**Verdict: APPROVED. Plan body corrected to match Dev's mapping.**

Read `Bot.java:309-389`. Confirmed:
- `runWsReconnectLoop` is invoked by `onWsDisconnected` (line 315) — a WebSocket-level disconnect. Reason `ws-disconnect` is correct.
- `runAuthThenWsLoop` is invoked by `triggerFullReconnect` (line 332) — full reauth-then-WS-reconnect cycle. Reason `reauth-cycle` is correct.
- `triggerFullReconnect("watchdog timeout …")` → `normalizeReconnectReason` → `watchdog`. Correct.

The plan body in Phase 2 originally said `runWsReconnectLoop` start → `reauth-cycle`, which inverts the semantics. Dev's mapping is right; the plan body has been corrected in place to match, with a back-reference to the Amendment block at the bottom of the plan.

---

## Out-of-scope changes

None observed.

---

## Plan amendments

1. **AD 10 (Architecture Decisions section)** — rewritten from filter-only to "per-callsite MDC read on Counter.Builder; MeterFilter is defense-in-depth only". Explicit pointer at the `micrometer-core` source quirk that justifies the change.
2. **Phase 2 file-touched bullet for `Bot.java`** — `runWsReconnectLoop` start increment corrected from `reauth-cycle` to `ws-disconnect`; new bullet added for `runAuthThenWsLoop` start → `reauth-cycle`.
3. **Amendment block appended at the bottom of `OBSERVABILITY.md` (`## Amendment — 2026-06-04`)** documenting both changes, with the Micrometer source-line reference for #1 and the `Bot.java` semantics for #2.

Both amendments are surgical: original phase structure, acceptance criteria, verification commands, and ADs 5/11 are untouched. The plan now matches the code, and the code matches the (corrected) plan.
