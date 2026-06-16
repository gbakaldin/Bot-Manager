# QA — RESTART_LIFECYCLE_FIX

**Verdict:** PASS
**Build:** `mvn test` → 400 tests, 0 failures, 0 errors

Pre-QA the suite was 390/0/0 (dev's claim verified). Added 10 tests this round,
all green.

## Tests added / updated

- `src/test/java/com/vingame/bot/domain/bot/service/BotFactoryFailLoudTest.java` (new, 3 tests)
  — covers Architecture Decision 4 (`BotFactory.createBot` fail-loud path when
  `env.resolveZoneName(game)` returns null/blank). Three scenarios:
    1. `customZone=true` + `BETTING_MINI` + empty `miniZoneName` → IllegalStateException with envId, gameType, customZone flag, username
    2. `customZone=true` + `CARD_GAME` + null `cardZoneName` → IllegalStateException
    3. `customZone=true` + whitespace-only `miniZoneName` → IllegalStateException (verifies `isBlank` not `isEmpty`)
- `src/test/java/com/vingame/bot/infrastructure/observability/BotMetricsTest.java` (3 tests appended)
  — covers `incBotCreationFailure(reason)`:
    1. Reason tag plus MDC tags both applied to the counter
    2. Distinct reasons land on distinct time series, counts accumulate independently
    3. No-MDC call still records (no phantom tags, no crash)
- `src/test/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorServiceRestartTest.java` (4 tests appended)
  — covers the integration of the metric + per-bot ERROR log inside
  `createBotsInParallel`:
    1. `IllegalStateException` from factory → `incBotCreationFailure("validation")` invoked per failed bot
    2. `RuntimeException("auth failed: ...")` → `incBotCreationFailure("auth")` invoked
    3. Generic `RuntimeException` with no auth markers → `incBotCreationFailure("unknown")` invoked
    4. Per-bot failure ERROR log line contains `Failed to create bot`, the group id, the env id, and the root cause message (uses a captive log4j2 appender attached to the service logger)

Dev's pre-existing test files were not modified (only appended to in the case of
`BotMetricsTest` and `BotGroupBehaviorServiceRestartTest`).

## Coverage of the diff

| Production file | Test file | What's covered |
|---|---|---|
| `Environment.java` — `resolveZoneName(Game)` + `DEFAULT_MINI_ZONE_NAME` / `DEFAULT_CARD_ZONE_NAME` | `EnvironmentZoneResolutionTest` (dev, 5 cases) | Full 2×2 matrix (customZone × mini/card) + parameterized SLOT/TAI_XIU/UP_DOWN pin |
| `BotFactory.java` — null/blank resolvedZoneName guard | `BotFactoryFailLoudTest` (QA, 3 cases) | mini + card branches of the null/blank check; whitespace-only also covered |
| `BotFactory.java` — happy path zoneName setter swap | Indirectly via `BotGroupBehaviorServiceRestartTest#restart_recreatesBotsAfterStop` (dev mocks the factory itself, so the in-factory swap is not unit-tested with a real `Environment` — but `EnvironmentZoneResolutionTest` covers the resolver and Decision 4 covers the guard; the in-factory call is a one-line `env.resolveZoneName(game)` whose result feeds the same `setZoneName`, so coverage is split across the two test files) | — |
| `BotGroupBehaviorService.createBot` — `.zoneName(env.resolveZoneName(game))` swap | `EnvironmentZoneResolutionTest` covers the resolver semantics; the call site swap is mechanical | — |
| `BotGroupBehaviorService.createBotsInParallel` — per-failure log + metric | `BotGroupBehaviorServiceRestartTest` (QA, 4 cases) | All three reasons classified; ERROR log content verified |
| `BotGroupBehaviorService.classifyCreationFailure` (private) | `BotGroupBehaviorServiceRestartTest` (QA, 3 cases) | `validation` (IllegalStateException/IllegalArgumentException), `auth` (message substring), `unknown` (fallback) — each via integration through `createBotsInParallel` |
| `BotGroupBehaviorService.restart` — fail-loud on zero bots | `BotGroupBehaviorServiceRestartTest#restart_failsLoudlyWhenZeroBotsCreated` (dev) | Throws `IllegalStateException` with `"0/3"` substring after a zero-bot post-start |
| `BotGroupBehaviorService.restart` — happy-path symmetry | `BotGroupBehaviorServiceRestartTest#restart_recreatesBotsAfterStop` (dev) | Verifies factory invoked `botCount × 2` times across initial start + restart |
| `EnvironmentClientRegistry.createClients` — deleted `setZoneName` line | Not directly tested (registry isn't read after creation per Phase 2 step 0 grep; behavior change is a deletion, not new logic). The Spring context load that already happens across `@SpringBootTest`-style tests in the suite would surface a regression at startup; build remains green. | — |
| `BotMetrics.incBotCreationFailure(String)` | `BotMetricsTest` (QA, 3 cases) | Tag wiring, distinct series per reason, no-MDC tolerance |

## Verification of asks (per QA-of-QA checklist)

1. **Resolver coverage**: PASS. Dev's 5 tests in `EnvironmentZoneResolutionTest` cover the 2×2 (customZone × mini/card) matrix plus the parameterized SLOT/TAI_XIU/UP_DOWN pin from the plan. JUnit reports 7 cases for that file (4 simple + 3 parameterized).
2. **Fail-loud null-zone**: Was missing. Added `BotFactoryFailLoudTest` (3 cases) covering mini + card + whitespace. Asserts `IllegalStateException` with `environmentId=...`, `gameType=...`, `customZone=...`, and username substrings — matches the plan's Decision 4 spec.
3. **Restart zero-bot fail-loud**: PASS. Dev's `restart_failsLoudlyWhenZeroBotsCreated` asserts `IllegalStateException` and `hasMessageContaining("0/3")`. Message format is `Restart of group <id> produced <alive>/<botCount> bots; check logs and bot_creation_failures_total metric for cause` — substring check is meaningful for the alive/total pair.
4. **Per-failure metric**: Was missing direct test coverage of the integration. Added 3 cases in `BotGroupBehaviorServiceRestartTest` exercising the three classifier branches (`validation` / `auth` / `unknown`), plus 3 cases in `BotMetricsTest` for the metric registration/tagging contract.
5. **Per-failure log**: Was missing. Added `createBotsInParallel_logsErrorWithFullContextOnEveryFailure` using a captive log4j2 appender to capture the ERROR event and assert it contains `Failed to create bot`, group id, env id, and the root cause message.
6. **Existing test (`StartCreateBotFailureTests`)**: PASS. Confirmed it still exercises `service.start("g-1")` (not restart) at line 244 of `BotGroupBehaviorServiceTest.java`. Doc comment dev added (lines 213-220) clarifies the start-vs-restart split per Decision 6 and matches the asserted behavior (zero bots OK on direct start, only restart throws). Comment text matches assertions exactly.

## Gaps

- **Production zoneName setter swap in `BotFactory.java:119`** is not directly unit-tested with a real `Environment` (only the resolver and the null-guard are). This is acceptable because `EnvironmentZoneResolutionTest` covers the resolver semantically and the call-site swap is a mechanical one-liner; a Spring-context-load test would be redundant with the suite's other `@SpringBootTest` coverage. Adding an end-to-end test would require either Testcontainers or hand-stubbing `EnvironmentClients` / `ApiGatewayClient` / `GameMsClient` and stretching into the WebSocket builder — high cost, low marginal value relative to the existing split coverage.
- **`EnvironmentClientRegistry.createClients` (deleted line)**: no targeted test; behavior change is purely a deletion of `clientFactory.setZoneName(env.getMiniZoneName())` on the cached factory. The plan's Phase 2 step 0 explicitly justified this on the grounds that no production reader consults the cached factory's `zoneName`. The full test suite passing is sufficient regression coverage.
- **Staging-only signals (Phase 4 verification)**: the actuator/Prometheus checks in `## Verification` (steps 7-10) cannot be exercised in unit tests; these are Releaser-owned post-deploy. Out of scope per the plan.

## Failures (if any)

None. `mvn test` → 400/0/0 after all QA-added tests.
