# QA — GRAFANA_PER_GAME_ENV_DASHBOARDS

**Verdict:** PASS
**Build:** mvn test → 916 tests, 0 failures, 0 errors (baseline on `main`-equivalent state was 909; +7 added here)

## Scope reviewed

Branch `feat/metrics-game-labels` vs `main`. Backend label/metric phases (1–3),
the `gameType` bug fix, the two info/status `MultiGauge` families and their
scheduled refresher, the `BotGroupRuntime` env-name threading, and the three
dashboard JSON files (per-game.json, per-environment.json, bots.json repoint).

## Tests added / updated

- `src/test/java/com/vingame/bot/domain/bot/core/BotTest.java` — added
  `initializeSetsCorrectedGameMdcLabels`: drives the **real** `Bot.initialize()`
  call site (highest-risk change) and asserts the captured `mdcSnapshot` carries
  `gameType=BETTING_MINI` (enum, not name), `gameName=BauCua`, `gameId=g1`, and
  that the name does NOT leak into `gameType`.
- `src/test/java/com/vingame/bot/infrastructure/runtime/BotGroupRuntimeTest.java`
  — added `startBotSetsCorrectedGameMdcLabels`: drives the **second** real call
  site (`BotGroupRuntime.startBot`) on its virtual thread and asserts the same
  corrected label set is visible in MDC where the metric series is born.
- `src/test/java/com/vingame/bot/infrastructure/observability/InfoGaugeRefresherTest.java`
  — added 5 tests: `refresh_isNullSafe_whenInfoTuplesCarryNullFields`,
  `refresh_withEmptyLiveState_registersNoRows`,
  `scheduler_startsAndInvokesRefresh_thenStopsCleanly` (PostConstruct start +
  PreDestroy shutdown), `stop_isNullSafe_whenSchedulerNeverStarted`,
  `refreshQuietly_swallowsExceptions_soSchedulerSurvives`.

Pre-existing dev-authored tests verified and kept green: `BotMetricsTest`
(gameType-enum regression guard + per-counter gameId/gameName), the original
8 `InfoGaugeRefresherTest` cases (label sets, stale-row drop, exclusion list,
status breakdowns), `BotMdcTagsMeterFilterTest`,
`BotGroupBehaviorServiceTest.GameEnvSnapshotTests`,
`BotGroupRuntimeTest.ConstructorTests` (3-arg/4-arg).

## Coverage of the diff

- `BotMdc.java` (new `GAME_ID`/`GAME_NAME`, extended `set`/`clear`) ←
  `BotMetricsTest`, `BotMdcTagsMeterFilterTest`, and the two new MDC-set-site
  tests (`BotTest`, `BotGroupRuntimeTest`).
- `Bot.initialize` `gameType=getGameType().name()` fix ←
  `BotTest.initializeSetsCorrectedGameMdcLabels` (direct, real path).
- `BotGroupRuntime.startBot` same fix ←
  `BotGroupRuntimeTest.startBotSetsCorrectedGameMdcLabels` (direct, real path).
- `BotGroupRuntime` env-name field + 3-arg/4-arg constructors ←
  `BotGroupRuntimeTest.ConstructorTests`.
- `BotMetrics.mdcTags` + `BotMdcTagsMeterFilter.map`/exclusion list ←
  `BotMetricsTest`, `BotMdcTagsMeterFilterTest`, `InfoGaugeRefresherTest`
  (`*_areOnTheAggregateExclusionList`).
- `InfoGaugeRefresher` (4 MultiGauges, refresh, scheduler) ←
  `InfoGaugeRefresherTest` (13 cases: labels, value 1, stale-row drop,
  null-safety, empty state, scheduler start/stop, exception isolation).
- `BotGroupBehaviorService` info/status snapshot methods ←
  `BotGroupBehaviorServiceTest.GameEnvSnapshotTests` (distinct dedupe, env-name
  fallback to id, per-game and cross-group per-env status breakdown).
- Dashboards (per-game/per-environment/bots repoint) ← static validation below
  (no unit-testable surface; Grafana-side verification deferred to Releaser).

## Findings on the focus areas

1. **`gameType` bug fix (highest risk) — VERIFIED CORRECT.** Both call sites
   (`Bot.java:142`, `BotGroupRuntime.java:154`) now pass
   `getGame().getGameType().name()` into the `gameType` slot and
   `getGame().getName()` into the new `gameName` slot; `getGame().getId()` into
   `gameId`. `BotMetrics.mdcTags()` and `BotMdcTagsMeterFilter.map()` both emit
   the new keys, so the label set is consistent regardless of which thread
   created the meter. The name no longer leaks into `gameType` (asserted
   negatively in `BotTest`, `BotGroupRuntimeTest`, `BotMetricsTest`). The
   production MDC snapshot is captured at `Bot.java:152` immediately after
   `BotMdc.set(...)`, so the corrected enum propagates across virtual threads
   (reconnect, watchdog, countdown, onMessage).
   - **`BotReconnectMdcTest` / `BettingMiniGameBotMdcTest` confirmed harmless.**
     Both inject a **hand-built** MDC map (`"gameType","BauCua"`) as a test seam
     and assert only that the snapshot is *propagated* to a spawned thread
     (`captured.get("gameType")` equals whatever was put in). They never call
     `Bot.initialize`/`BotMdc.set`, so the stale `"BauCua"` value is arbitrary
     propagation-channel data, NOT a production label assertion. No real path
     emits this map. Not a defect.

2. **Null-safety — adequate, with one noted asymmetry (not a defect).**
   - The `InfoGaugeRefresher` wraps every label value in `nullSafe(...)` → `""`
     (covered by `refresh_isNullSafe_whenInfoTuplesCarryNullFields`).
   - `BotGroupBehaviorService.listRunningGameInfo` null-guards `getGameType()`
     and skips bots with a null `Game`.
   - The 3-arg `BotGroupRuntime` constructor leaves `environmentName` null;
     `listRunningEnvironmentInfo` falls back to `environmentId` as display, so
     the `environment_info` series still resolves (covered).
   - **Asymmetry (low severity, accepted):** the two MDC set sites call
     `getGame().getGameType().name()` with NO null guard, unlike the service.
     A `Game` with a null `gameType` reaching `initialize()`/`startBot()` would
     NPE. This is mitigated upstream: `BotGroupConfigValidationService`
     (lines 62–64) throws `BadRequestException("...has no gameType configured")`
     at group **create/update** (`BotGroupService:124`/`:220`), so a null-gameType
     group cannot be persisted and therefore cannot be started. The pre-fix code
     was already unguarded (`getGame().getName()`), so this introduces no new
     crash surface. Flagged for awareness only — if a gameType is ever nulled
     directly in Mongo post-create, start would throw; the existing validation
     boundary is the guarantee.

3. **InfoGaugeRefresher correctness — VERIFIED.** `MultiGauge.register(rows)`
   replaces the row set each cycle: stale rows for stopped games/envs drop on
   the next refresh (`infoGauges_dropStaleRowsOnRefresh`). Status counts match
   actual bot states per game and per env, aggregating across groups in the same
   env (`countBotsByEnvAndStatus_breakdown`). No leak/duplication across cycles
   (register replaces, not appends). Scheduler is a single-thread virtual-thread
   executor, started in `@PostConstruct`, `shutdownNow()` in `@PreDestroy`
   (verified shut down), null-safe stop, and `refreshQuietly` isolates
   exceptions so a transient service failure does not cancel the schedule.

4. **Cardinality — VERIFIED BOUNDED.** New labels are `gameId`/`gameName`
   (1:1, functionally dependent on `botGroupId`, ~tens), `gameType` (~5 enum
   values), `environmentName` (info-gauge only, not on counters). MultiGauge
   families are `O(games × statuses)` and `O(envs × statuses)` — low hundreds.
   `BotMetricsTest` pins the counter identity tag set to exactly
   `{cmd, botGroupId, environmentId, gameType, gameId, gameName}` and asserts
   per-bot tags (`botId`, `botUserName`) are excluded. No unbounded label
   introduced.

5. **Backward-compat — VERIFIED.** Retained 3-arg `BotGroupRuntime` constructor
   delegates to the 4-arg with `environmentName=null`; info-gauge emission falls
   back to the id and does not break (covered by
   `listRunningEnvironmentInfo_usesThreadedName` and the constructor tests).

## Dashboard JSON sanity-check

All three JSON files parse. Template variables and panel PromQL reviewed:

- **per-game.json** (uid `per-game`): `$game` =
  `label_values(game_info, gameName)` (visible), `$gameId` (hidden, `hide:2`) =
  `label_values(game_info{gameName="$game"}, gameId)`, both `refresh:2`. All 11
  panels filter `{gameId="$gameId"}`. RTP panel wraps the ratio in `or vector(0)`.
  `bots_by_game_status` and the `bot_*` counters all carry `gameId` — labels
  referenced all exist.
- **per-environment.json** (uid `per-environment`): `$environment` =
  `label_values(environment_info, environmentName)`, `$environmentId` (hidden) =
  `label_values(environment_info{environmentName="$environment"}, environmentId)`.
  Panels filter `{environmentId="$environmentId"}`. "RTP per game within env"
  uses `sum by (gameName)` (label present on counters) and `or vector(0)`.
  "Reconnect rate" uses `sum by (reason)` — `reason` is a real tag on
  `bot_reconnects_total` (`BotMetrics.java:135`).
- **bots.json repoint:** version bumped 3→4. The five per-individual-game panels
  (RTP, winnings, money-drain, jackpot wins, jackpot amount — ids 14–18)
  correctly repointed `by (gameType)` → `by (gameName)` with matching
  `legendFormat` and updated descriptions. The two genuinely per-game-*type*
  panels (ids 6, 7, "per game type") correctly KEEP `by (gameType)` and gained
  descriptions clarifying the label now carries the enum. Consistent with AD-1
  and the resolved Open Item.

## Gaps (not covered, by design)

- **Grafana/Prometheus live verification** (plan `## Verification` steps 1–8:
  `label_values` dropdown population, dashboard `/api/dashboards/uid/...` 200,
  selected-game RTP returning a value): requires a running stack and is
  on-server. Deferred to the Releaser's staging smoke. JSON structure validated
  statically here.
- **`BotGroupBehaviorService.startGroup` 4-arg constructor wiring with a real
  `Environment.getName()`**: the create path at `BotGroupBehaviorService:238`
  passing `environment.getName()` is exercised only via full integration; the
  unit tests construct runtimes directly. The constructor and downstream
  emission are covered; the single wiring line is read-verified.
- **Null-gameType NPE at the MDC set sites**: intentionally not made red — it is
  unreachable through the validated create/start path (see Finding 2). Recorded
  as a low-severity hardening note, not a blocking defect.

## Failures

None.

## Defects

| Severity | Item |
|---|---|
| Low (advisory) | MDC set sites (`Bot.java:142`, `BotGroupRuntime.java:154`) call `getGameType().name()` without a null guard, unlike the service-layer helpers. Currently unreachable due to create-time validation; consider mirroring the service's null-tolerant pattern for defense-in-depth. No code change required for this feature to ship. |
