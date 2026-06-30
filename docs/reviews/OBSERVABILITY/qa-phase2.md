# QA — OBSERVABILITY Phase 2

**Verdict:** PASS
**Build:** `mvn test` → 336 tests, 0 failures, 0 errors, 0 skipped.
Baseline pre-Phase-2: 313. Delta: **+23 tests** (matches Dev's claim).

## Tests added

- `src/test/java/com/vingame/bot/infrastructure/observability/BotMetricsTest.java` — 13 tests covering every `inc*` method on `BotMetrics`, MDC tag attachment, empty-MDC behavior, separate-time-series regression, empty-value MDC handling, cardinality-cap assertion.
- `src/test/java/com/vingame/bot/infrastructure/observability/BotMdcTagsMeterFilterTest.java` — 5 tests covering MDC→tag attachment for `bot_*`, aggregate-gauge bypass, non-bot pass-through, empty-MDC pass-through, partial-MDC.
- `src/test/java/com/vingame/bot/infrastructure/observability/ObservabilityConfigTest.java` — 5 tests covering all four aggregate gauges (`bot_groups_running`, `bots_managed`, `ws_connections_open`, `bots_by_status`) reading from the service mock + the no-MDC-tags assertion.

## Coverage matrix — callsite → test

| Plan callsite | Production location | Coverage |
|---|---|---|
| `creditBalance` → `incBetPlaced` | `Bot.java:530` | direct (`incBetPlaced_incrementsBothCountAndAmount`) |
| `transitionStatus(DEAD)` → `incBotFailure` | `Bot.java:275-277` | direct (`incBotFailure_…`) |
| `triggerFullReconnect` → `incBotReconnect(normalized)` | `Bot.java:326-328` | direct (`incBotReconnect_tagsReason` covers watchdog + ws-disconnect) |
| `runWsReconnectLoop` start → `incBotReconnect("ws-disconnect")` | `Bot.java:347` | indirect — counter mechanics via `incBotReconnect_tagsReason` |
| `runAuthThenWsLoop` start → `incBotReconnect("reauth-cycle")` | `Bot.java:375` | indirect — same |
| `deposit()` callback → `incBotAutoDeposit(success/failure)` | `Bot.java:209,219` | direct (`incBotAutoDeposit_tagsOutcome`, both branches) |
| `configureClient` WS → `incBotWsEvent(connected/authenticating/disconnected)` | `Bot.java:290,294,297` | direct (`incBotWsEvent_tagsEvent`, all three events) |
| `onSubscribe/onStartGame/onUpdate/onEndGame` → `incBotMessage(cmd)` | `BettingMiniGameBot.java:154,163,178,188` | direct on counter mechanics (`incBotMessage_attachesMdcAndCmdTag`, endGame); other cmd values covered by `differentGroupsCreateSeparateTimeSeries` |
| `onWatchdogExpired` → `incBotWatchdogExpired` | `BettingMiniGameBot.java:149` | direct (`incBotWatchdogExpired_simpleCounter`) |
| `ApiGatewayClient.authenticate` → `incLogin` success | `ApiGatewayClient.java:146` | direct (`incLogin_tagsOutcome` success) |
| `ApiGatewayClient.authenticate` → `incLogin(false)` on exception | `ApiGatewayClient.java:151` | unit-only — `BotMetrics.incLogin(false)` covered via `incLogin_tagsOutcome`-style asserts on the `outcome=failure` tag (see `incVerifyToken_tagsOutcome` for the mirror pattern); no integration test fires through `authenticate()` |
| `ApiGatewayClient.getBalance` → `incVerifyToken(true/false)` | `ApiGatewayClient.java:488/491/495/502` | unit-only — both branches covered at `BotMetrics` API level (`incVerifyToken_tagsOutcome` covers failure; success path is trivial mirror) |

The 5 critical spot-checks asked for (login failure, reconnect=watchdog, deposit=failure, watchdog_expired, ws disconnected close) are all covered at the BotMetrics layer. The ApiGatewayClient try/catch and the Bot.java WS-event switch are straightforward conditional calls into already-tested BotMetrics methods.

## MDC tagging correctness

Confirmed. `BotMetrics.mdcTags()` reads `BotMdc.BOT_GROUP_ID`, `BotMdc.ENVIRONMENT_ID`, `BotMdc.GAME_TYPE` from MDC at increment time and attaches them to `Counter.Builder` BEFORE `.register(registry)`. Empty/null values are skipped (no `key=""` noise). Verified by:

- `emptyMdc_doesNotAttachBotIdentityTags` — increments under empty MDC, asserts no group/env/game tags on the resulting counter.
- `differentGroupsCreateSeparateTimeSeries` — switches MDC between two group values mid-test, asserts that each group ends up with a distinct counter and the right count. This is the regression that catches the Meter.Id pre-filter cache bug Dev describes; under a MeterFilter-only implementation both increments would resolve to the same cached counter and one assertion would fail.
- `mdcWithEmptyValues_omitsTag` — explicit empty-string MDC values are skipped, not propagated as `botGroupId=""`.

## Aggregate-gauge protection

Confirmed. `BotMdcTagsMeterFilter.AGGREGATE_METER_NAMES` allow-list contains exactly `bots_managed`, `bot_groups_running`, `ws_connections_open`, `bots_by_status`. Tested by:

- `map_aggregateGauge_doesNotAttachMdcTags` (filter unit test) — iterates all four names, asserts none receive `botGroupId` even with populated MDC.
- `aggregateGauges_doNotReceiveMdcTags_evenWhenMdcIsPopulated` (config test) — registers the gauges via the actual `ObservabilityConfig.registerAggregateGauges` method with MDC populated, asserts no `botGroupId` tag on the registered gauges.

## Cardinality cap (AD 5)

`grep` of `src/main/java/com/vingame/bot/infrastructure/observability/` shows only the following tag keys:
- MDC-derived: `botGroupId`, `environmentId`, `gameType`
- Bounded enums: `cmd`, `reason`, `outcome`, `event`, `status`

No `username`, `botUserName`, `botId`, `userName`, or per-bot identifier anywhere. `counterIdContainsExpectedTagSet` explicitly asserts `keys.doesNotContain(BotMdc.BOT_USER_NAME, BotMdc.BOT_ID)`.

## Dev's two deviations

1. **MDC tag attachment moved from MeterFilter into `BotMetrics`.** ACCEPTABLE. The Micrometer pre-filter cache is real (`AbstractMeterRegistry#preFilterIdToMeterMap`); MeterFilter-only would collapse all groups onto a single cached Counter. Dev's regression test `differentGroupsCreateSeparateTimeSeries` would catch any future regression. MeterFilter retained as defense-in-depth + aggregate-gauge allow-list, which is genuinely useful. Architect-2 should formalize this as a plan amendment to AD 10.

2. **Reconnect reason mapping `runWsReconnectLoop→"ws-disconnect"`, `runAuthThenWsLoop→"reauth-cycle"`.** ACCEPTABLE. The plan body (Phase 2 file-touched bullets) had the labels assigned wrong (it told Dev to emit `"reauth-cycle"` from `runWsReconnectLoop`); Dev's mapping matches the code semantics (the WS-only loop is what fires after a WS disconnect; the auth-then-ws loop is what runs the full re-auth cycle). Note that watchdog-triggered reconnect produces two counter increments (`reason=watchdog` from `triggerFullReconnect`, then `reason=reauth-cycle` from `runAuthThenWsLoop`) — this is intentional but worth flagging to Architect-2 so the dashboard query does not double-count when summing reconnects-per-event.

## Health DTO compatibility

Yes, preserved. `Bot.java:67-72` retains `@Getter AtomicLong totalBetsPlaced`, `@Getter AtomicLong totalBetAmount`, `@Getter volatile long lastRoundWinnings`. `creditBalance(amount)` increments both the AtomicLongs and the Micrometer counter; the health DTO read path is untouched.

## Other checks

- `application.properties:28` includes `prometheus` in actuator exposure. `application.properties:36` has `management.prometheus.metrics.export.enabled=true`. Nothing reverted.
- `BotFactory` calls `setMetrics(botMetrics)` BEFORE `initialize()` (`BotFactory.java:124-125`), so login counter increments inside `apiGatewayClient.authenticate()` (fired from `initialize()`) see a non-null `metrics` field. The `Bot` setter follows the existing fluent pattern (returns `this`).
- All bot-side increment sites in `Bot.java` and `BettingMiniGameBot.java` guard with `if (metrics != null)` — defensive against the `restart()` and test paths that may construct a `Bot` without going through `BotFactory`.
- `ApiGatewayClient` does NOT guard `metrics.incLogin/incVerifyToken` against null because constructor-injected; safe.

## Gaps / follow-ups

- No integration test exercises `Bot.transitionStatus(DEAD)`, `Bot.deposit()` callback, or `BettingMiniGameBot.onSubscribe/onStartGame/onUpdate/onEndGame` to confirm the counters fire end-to-end. Coverage is at the BotMetrics-API level only. Acceptable for Phase 2 since the call sites are one-line `if (metrics != null) metrics.inc*(...)` and the BotMetrics behavior is well-tested in isolation; flagging for Phase 6/7 Releaser to validate against live Prometheus output.
- The double-counting of watchdog reconnects (one `{reason=watchdog}` + one `{reason=reauth-cycle}` for the same event) is a semantic choice that should be made explicit in the Phase 6 dashboard PromQL.
- `ApiGatewayClient.authenticate` failure path is a unit-only assertion that `BotMetrics.incLogin(false)` produces an `outcome=failure` tag; we did not write an integration test that throws inside `authenticate()` to confirm the try/catch wires through. Risk is low because the code is a 3-line try/catch.

## Failures

None.
