# Code Review — OBSERVABILITY Phase 2

Branch: `feat/observability-metrics`
Reviewed diff: `git diff c11fe3c..9df9011`
Files in review: 3 new production, 5 modified production, 3 new test.

## Verdict

**CHANGES_REQUESTED**

One real bug (ConcurrentModificationException on gauge sampling), one
behavioural bug worth a discussion (double-counted reconnect events), plus
some smells around the verify-token catch chain. No security findings.

---

## Findings

### 1. [bug] Aggregate gauges iterate non-thread-safe `ArrayList` from the scrape thread
`src/main/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorService.java:508-535`
`src/main/java/com/vingame/bot/infrastructure/runtime/BotGroupRuntime.java:48,75,136`

`BotGroupRuntime.botInstances` is a plain `ArrayList`. Phase 2 introduces
three new readers (`getTotalManagedBots`, `getOpenWsConnectionCount`,
`countBotsByStatus`) that iterate it via enhanced-for from whatever thread
Prometheus uses to sample gauges. Meanwhile, `BotGroupBehaviorService.start`
calls `runtime.startBot(bot)` in a loop (line 217-220) — each call performs
`botInstances.add(bot)` (`BotGroupRuntime.java:136`). `runningGroups.put(id, runtime)`
happens *before* this loop (line 208), so the runtime is published to the
gauge readers while bots are still being appended.

`ArrayList`'s iterator is fail-fast — a concurrent `add` during a scrape
will throw `ConcurrentModificationException` from inside the gauge sampler,
which Micrometer logs and treats as a missing sample. Race window: the
entire bot-creation loop (10s+ for large groups).

Pre-existing iterations of the same list (e.g. `stopAllBots`, line 189)
ran on the same thread that owns the mutations, so this is a new
concurrency surface introduced by Phase 2.

**Fix shape:** switch `botInstances` to `CopyOnWriteArrayList`, OR snapshot
in each accessor (`new ArrayList<>(runtime.getBotInstances())`), OR
synchronize on the list. CopyOnWrite is cleanest given the read-heavy
gauge usage and infrequent writes.

---

### 2. [bug] Watchdog-triggered reconnect double-counts on `bot_reconnects_total`
`src/main/java/com/vingame/bot/domain/bot/core/Bot.java:319-333,374-389`

The watchdog flow fires two reconnect increments for one event:
1. `triggerFullReconnect("watchdog timeout ...")` → `incBotReconnect("watchdog")` (line 327).
2. The same method then spawns `runAuthThenWsLoop` → which immediately does
   `incBotReconnect("reauth-cycle")` (line 375).

Net effect on a single watchdog event: `+1{reason=watchdog}` and
`+1{reason=reauth-cycle}`. Sum across reasons (which is what most dashboards
chart for "reconnect rate") is doubled.

Same shape for the fall-through at `runAuthThenWsLoop` line 388: a failed
reauth path drops into `runWsReconnectLoop`, which then increments
`+1{reason=ws-disconnect}` on top of the `+1{reason=reauth-cycle}` already
charged at the top of `runAuthThenWsLoop`.

Plan (`docs/plans/OBSERVABILITY.md:205-206`) calls for one increment per
*reconnect event*. The current code is one increment per *loop entry*.

**Fix shape:** pick one model and stick to it. Either (a) drop the
top-of-loop `runWsReconnectLoop` / `runAuthThenWsLoop` increments and rely
on the caller (`triggerFullReconnect`, `onWsDisconnected`) to fire exactly
one increment with the correct `reason` tag, or (b) drop the increment in
`triggerFullReconnect` and let the loops own the counter. Option (a) lines
up better with the plan's wording ("after the status transition").

---

### 3. [smell] `getBalance` exception flow is brittle
`src/main/java/com/vingame/bot/infrastructure/client/ApiGatewayClient.java:485-505`

The catch chain double-handles failure counting via a string-prefix check:

```java
} catch (RuntimeException e) {
    if (e.getMessage() == null || !e.getMessage().startsWith("User: ")) {
        metrics.incVerifyToken(false);
    }
    throw e;
}
```

This depends on the exact wording of the `RuntimeException("User: ...")`
thrown at line 492 not changing, and is hard to follow. If anyone tweaks
the error message in the future, the counter starts double-incrementing
silently.

**Fix shape:** restructure into a single increment site. e.g.,

```java
boolean ok = false;
try {
    ...
    ok = true;
    return balance;
} finally {
    metrics.incVerifyToken(ok);
}
```

or a single-counter-per-exit-path layout that doesn't require message
sniffing.

Also: `Thread.sleep(500)` at line 466 has long been swallowing the interrupt
flag — pre-existing, not Phase 2's job to fix, but the new try/catch on top
of it is a good moment to flag it.

---

### 4. [smell] `transitionStatus` increments `bot_failures_total` on idempotent DEAD re-entry
`src/main/java/com/vingame/bot/domain/bot/core/Bot.java:271-278`

`if (next == BotStatus.DEAD && metrics != null) metrics.incBotFailure()`
fires regardless of `prev`. Today only `performReauth` (line 400) transitions
into DEAD and it's not in a loop, so this doesn't fire twice in practice;
Phase 3's plan (`docs/plans/OBSERVABILITY.md:271-275`) already anticipates a
revive-from-DEAD path which will require the same guard. Add it now while
the change is small:

```java
if (next == BotStatus.DEAD && prev != BotStatus.DEAD && metrics != null) {
    metrics.incBotFailure();
}
```

---

### 5. [smell] `BotMdcTagsMeterFilter` allow-list is a footgun for future `bot_*` aggregate names
`src/main/java/com/vingame/bot/infrastructure/observability/BotMdcTagsMeterFilter.java:47-52,55-62`

The allow-list is exact-match against a hard-coded `Set.of(...)`. Any future
aggregate gauge introduced with a `bot_*` name (e.g. `bot_groups_dead`,
`bot_dead_seconds_total` from Phase 3) will silently start receiving MDC
tags from whichever thread happens to sample it, polluting an aggregate
series with per-group cardinality.

This is a smell rather than a bug because Phase 3 isn't here yet — but a
comment on `AGGREGATE_METER_NAMES` saying "add every new aggregate meter
to this list" is cheap insurance. Even better: invert it. Use an explicit
per-bot allow-list (`bot_messages_total`, `bot_failures_total`, ...) so
that *new* meters default to "no tag attachment" and the developer has to
opt in. Out of scope for Phase 2 but worth raising in Architect-2.

---

### 6. [nit] `BotMetrics` method naming is inconsistent
`src/main/java/com/vingame/bot/infrastructure/observability/BotMetrics.java`

Most methods are `incBotX` (`incBotMessage`, `incBotFailure`,
`incBotReconnect`, `incBotAutoDeposit`, `incBotWatchdogExpired`,
`incBotWsEvent`) but three are `incX` without the `Bot` prefix
(`incBetPlaced`, `incLogin`, `incVerifyToken`). Plan
(`docs/plans/OBSERVABILITY.md:198`) actually lists both shapes, so this is
mirror-of-plan, but reading the call sites the inconsistency is visible:

```java
metrics.incBotMessage("subscribe");
metrics.incBetPlaced(amount);
metrics.incLogin(true);
metrics.incBotFailure();
```

Pick one prefix and stick to it. Trivial.

---

### 7. [nit] `metrics` is null-checked everywhere in `Bot.java` but not in `ApiGatewayClient.java`
`src/main/java/com/vingame/bot/domain/bot/core/Bot.java` (every `if (metrics != null)` line)
vs. `src/main/java/com/vingame/bot/infrastructure/client/ApiGatewayClient.java:146,151,488,491,495,502`

`Bot.metrics` is set via `setMetrics(...)` so the null guard is defensive
against a forgotten wire-up. `ApiGatewayClient.metrics` is required by
constructor injection (final field), so it can never be null at runtime —
yet the null guard pattern in `Bot.java` makes the asymmetry surprising on
read. Either drop the null guards in `Bot.java` (and require Phase 2 to
fully wire `setMetrics`) or document why one place has them and the other
doesn't.

The build is green and `BotFactory.createBot` does call `.setMetrics(botMetrics)`
unconditionally (`BotFactory.java:124`), so the `Bot.java` guards are dead
code in practice.

---

## MDC tagging mechanism correctness

Dev's deviation from the plan (move MDC tag attachment from `MeterFilter`
to per-call `Counter.builder.tags(...)`) is **correct and well-justified**.
Verified each property the prompt called out:

| Property | Verified |
|---|---|
| `mdcTags()` reads MDC every call, no field caching | YES — `BotMetrics.java:68-74`, the `List<Tag>` is constructed fresh per call. |
| All `inc*` methods use `Counter.builder(name).tags(mdcTags()).register(registry).increment()` | YES — every method, verified at `BotMetrics.java:88-178`. |
| No `private final Counter foo` field caching | YES — `BotMetrics` has only one field, `private final MeterRegistry registry`. No Counter caching that would freeze the pre-filter Id. |
| `BotMdcTagsMeterFilter` exact-match allow-list (not prefix) for aggregate names | YES — `Set.contains(name)` on line 57. A hypothetical `bot_groups_running_total` would NOT be matched. |
| Filter is registered (`MeterRegistry.config().meterFilter(...)`) | YES — via Spring Boot's `MeterRegistryPostProcessor` auto-wiring of `MeterFilter` beans. The `@Bean MeterFilter botMdcTagsMeterFilter()` in `ObservabilityConfig:29-31` is picked up. |
| `mdcTags()` skips empty-string MDC values | YES — `BotMetrics.java:78`, `BotMdcTagsMeterFilter.java:77`. |
| Per-call `Counter.register` is cheap (interned by post-filter Id) | YES — `AbstractMeterRegistry.getOrCreateMeter` interns. |

## Callsite-by-callsite correctness check

| Site | Pattern | Verified |
|---|---|---|
| `Bot.transitionStatus(DEAD)` | `incBotFailure()` | YES; idempotency nit (#4). |
| `Bot.triggerFullReconnect(reason)` | `incBotReconnect(normalizeReconnectReason(reason))` — bounded to `{watchdog, ws-disconnect}` | YES; but contributes to double-count (#2). |
| `Bot.runWsReconnectLoop` | `incBotReconnect("ws-disconnect")` at top | YES; semantically OK but causes #2 in fall-through scenario. |
| `Bot.runAuthThenWsLoop` | `incBotReconnect("reauth-cycle")` at top | YES; Dev swapped the two loop labels vs. plan literal text but the semantic mapping is sensible. Architect-2's call. |
| `Bot.deposit` mdcConsumer callback | both `success` and `failure` branches increment | YES — lines 209 and 219. |
| `Bot.creditBalance` | `incBetPlaced(amount)` increments both count and amount via single helper | YES; `BotMetrics.incBetPlaced` registers both `bot_bets_placed_total` (+1) and `bot_bet_amount_total` (+amount). |
| `Bot.configureClient` `onWsStatusChange` | CONNECTED→connected, AUTHENTICATING_WS→authenticating, DISCONNECTED→disconnected | YES, mapping confirmed against `VingameWebSocketClient$WsConnectionStatus` enum (`websocket-parser-core-2.3.10.jar`). `CONNECTING` is silently dropped — pre-existing behaviour, not Phase 2's problem. |
| `BettingMiniGameBot.onSubscribe` | `incBotMessage("subscribe")` | YES, line 154. |
| `BettingMiniGameBot.onStartGame` | `incBotMessage("startGame")` | YES, line 163. |
| `BettingMiniGameBot.onUpdate` | `incBotMessage("updateBet")` | YES, line 178. |
| `BettingMiniGameBot.onEndGame` | `incBotMessage("endGame")` | YES, line 188. |
| `BettingMiniGameBot.onWatchdogExpired` | `incBotWatchdogExpired()` **before** `triggerFullReconnect` | YES, line 149 precedes line 150. |
| `ApiGatewayClient.authenticate` | success → `incLogin(true)`; `catch (RuntimeException)` → `incLogin(false)` then rethrow | YES — see smell #3 for verify-token symmetry caveat, but `authenticate` itself is clean. |
| `ApiGatewayClient.getBalance` | success → `incVerifyToken(true)`; failure → `incVerifyToken(false)` | YES, but brittle — smell #3. |

## Aggregate gauges

- `bot_groups_running` → `BotGroupBehaviorService::getRunningGroupCount` → `runningGroups.size()`. OK.
- `bots_managed` → sums `botInstances.size()` across runtimes. OK arithmetically; thread-safety see finding #1.
- `ws_connections_open` → counts `bot.isConnected()` across all bots. OK arithmetically; same thread-safety issue.
- `bots_by_status{status=...}` → one Gauge per `BotStatus.values()` entry. Cardinality bounded by enum size (~6). OK arithmetically; same thread-safety issue.

All four are registered with no per-group tags. `BotMdcTagsMeterFilter`'s
allow-list excludes them. `ObservabilityConfigTest.aggregateGauges_doNotReceiveMdcTags_evenWhenMdcIsPopulated`
covers this; verified pass.

## Wiring

- `BotFactory.createBot` calls `.setMetrics(botMetrics)` between `setConfiguration` and `.initialize()` — verified, line 121-125. Metrics are available before the first `transitionStatus` and `authenticate` calls fire.
- `ApiGatewayClient` constructor takes `BotMetrics` — verified, `final` field, no null-check needed at use sites (cf. nit #7).
- `BotGroupBehaviorService` only adds public read accessors. No new dependency on `BotMetrics`. Scope minimal as expected.

## Scope check

Scope is clean. Diff touches exactly the 8 production files in Phase 2's
stated set + 3 new tests. No changes to `BotMdc.java`, log4j2 config,
Promtail config, Dockerfile, `pom.xml`, `docker-compose.yml`, `application.properties`,
or the `user:` directive on `bot-manager`. The unchanged `pom.xml` correctly
relies on the Micrometer dep added in Phase 1.

## Cardinality verified

`grep` confirms no `username` / `botId` / `botIndex` / `userName` Tag.of
constructions in the observability or bot folders. Only `BotMdc.BOT_GROUP_ID`,
`BotMdc.ENVIRONMENT_ID`, `BotMdc.GAME_TYPE` are used as tag keys. AD 5
satisfied.

`BotMetricsTest.counterIdContainsExpectedTagSet` explicitly asserts
`!keys.contains(BotMdc.BOT_USER_NAME, BotMdc.BOT_ID)` — good regression
guard against re-introducing per-bot tags.

## Notes

- The "Why MDC at increment time" Javadoc on `BotMetrics` (lines 22-32) and `BotMdcTagsMeterFilter` (lines 19-26) is excellent — clearly explains the `preFilterIdToMeterMap` caching trap and why the filter is kept anyway. Worth keeping verbatim.
- `BotMetricsTest.differentGroupsCreateSeparateTimeSeries` is exactly the regression test the prompt was asking for: it demonstrates that two different MDC contexts produce two different counters via the increment-time path. If the bug came back (e.g., Counter caching as a field), this test would catch it.
- The pre-existing `[Login] response: agencyToken=... | authToken=... | jwtToken=...` log at `ApiGatewayClient.java:144` still logs full tokens, contrary to the CLAUDE.md guideline. Phase 2 does not introduce or worsen this, but the surrounding lines were touched, so this is a natural moment to also flag it for cleanup in a future change.
- Reconnect-reason mapping (`runWsReconnectLoop` → `ws-disconnect`, `runAuthThenWsLoop` → `reauth-cycle`) reads as semantically correct against the actual method bodies (the ws loop handles WS-level retries, the auth loop always re-auths first). Plan literal wording (`docs/plans/OBSERVABILITY.md:206`) puts "reauth-cycle" in `runWsReconnectLoop`; Dev's swap is the better mapping — flag to Architect-2 as a deliberate deviation for sign-off, but do not block on it.
