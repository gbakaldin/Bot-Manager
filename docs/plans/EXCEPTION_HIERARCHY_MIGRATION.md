# Exception-Hierarchy Migration — Completion Pass

Branch: `chore/exception-hierarchy`

## Goal

The prior pass (merged to main) established the domain exception hierarchy: an
abstract `BotManagerException extends RuntimeException` base with three concrete
subtypes reparented onto it (`ResourceNotFoundException`→404,
`BadRequestException`→400, `UpstreamGatewayException`→502), plus a
`RestExceptionHandler` that owns the HTTP mapping and keeps a deliberate
`IllegalArgumentException→400` fallback arm. This completion pass surveys the
~30 remaining raw `throw new IllegalArgumentException(...)` /
`throw new IllegalStateException(...)` sites in `src/main/java`, classifies each
as **(a) migrate to a typed domain exception** or **(b) keep raw
(programming-error guard)**, and executes only the (a) migrations while keeping
zero HTTP-behavior change for everything the controller/handler tests assert.

**Headline finding: after a full survey, all 30 remaining raw throws classify
as (b) KEEP.** None is a client/config error surfaced through the REST layer
where a typed exception improves HTTP semantics or the hierarchy; every one is
an internal invariant, factory/registry guard, enum resolver, or builder
precondition on a non-REST path, and each already has a test that locks in its
raw type. The migration is therefore already complete at the point where typing
adds value. The substantive deliverable of this pass is the **evidence** for
that conclusion (so a reviewer can confirm the hierarchy is coherent and not
under-migrated), plus a single documentation phase that records the invariant so
the boundary doesn't silently erode. There are **zero (a) migrations** and
**zero new subtypes**.

## Findings — Current State

Base hierarchy and handler (unchanged by this pass):

- `com.vingame.bot.common.exception.BotManagerException` — abstract, `extends RuntimeException`, message/cause only (`BotManagerException.java:19`).
- `BadRequestException extends BotManagerException` (`BadRequestException.java:12`); `ResourceNotFoundException extends BotManagerException` (`ResourceNotFoundException.java:3`); `UpstreamGatewayException` abstract base + `UpstreamLoginException` / `UpstreamRegistrationException` / `UpstreamPrometheusException` leaves.
- `RestExceptionHandler` (`RestExceptionHandler.java`): `ResourceNotFoundException`→404 bodyless (`:71`); `BadRequestException`→400 with body (`:80`); `IllegalArgumentException`→400 **fallback** (`:88`); `MethodArgumentTypeMismatchException`→400 (`:96`); `UpstreamGatewayException`→502 (`:112`); `IllegalStateException`→500 sanitised (`:133`); `HttpMessageNotReadableException`→400 (`:147`); `MethodArgumentNotValidException`→400 aggregated (`:172`); `Exception`→500 sanitised terminal (`:204`).

Key reachability facts that drive the classification:

- **`MetricsController.resolveMetric` (`MetricsController.java:122`)** guards scope validity with `BadRequestException` (`:125`, `:128`) **before** any call reaches `MetricKey.promql`. `MetricKey.promql`'s `IllegalArgumentException` (`MetricKey.java:165`) is unreachable from the controller — it is a defensive contract guard for out-of-order internal callers.
- **`ProductCode.fromCode` (`ProductCode.java:83`)** is a Jackson `@JsonCreator` (`:76`). It is a field on `EnvironmentDTO` (`EnvironmentDTO.java:26`). An invalid code in a request body is caught by Jackson during body binding and surfaces as `HttpMessageNotReadableException`→400 via the handler override (`RestExceptionHandler.java:147`) — never as a raw `IllegalArgumentException` reaching the controller. Typing it would be an HTTP no-op and would fight the `@JsonCreator` contract.
- **Factory throws in the bot-start path** (`AuthStrategyFactory.java:22`, `BotFactory.java:120`/`:178`, `GameMessageTypesResolver.java`) run inside `createSingleBot` on per-bot virtual threads (`BotGroupBehaviorService.java:482`–`:497`), collected into an `errors` list and counted as `bot_creation_failures_total` — they are not propagated to the controller as the triggering exception type. The controller returns 500 on aggregate failure; the raw per-bot type never selects a handler arm.
- **`BotGroupBehaviorService.java:859`** is an `IllegalStateException` that the code comment (`:851`–`:854`) documents as a *deliberate* 500-level loud-failure signal for a restart that produced zero bots — intended to hit the terminal 500 arm, not a 4xx.
- **Every remaining throw has a test asserting its raw type** (see the "Tests that lock raw types" section). These tests are the existing contract; they confirm the throws are established programming-error guards, not incidental.

## Per-aspect readiness / mapping

| Aspect | Readiness | Notes |
|---|---|---|
| Base hierarchy (`BotManagerException` + 3 subtypes) | ready | Done in prior pass; locked by `BotManagerExceptionHierarchyTest`. |
| `RestExceptionHandler` mapping incl. `IAE→400` fallback | ready | No change. Fallback stays (protects all kept-raw throws). |
| REST-surfaced validation → typed | ready | Already migrated in prior pass (service `save`/validators throw `BadRequestException`). No raw throws remain on REST validation paths. |
| Remaining 30 raw throws | ready (keep) | All (b). Classification table below. No code change. |
| New subtypes | n/a | None warranted (see Architecture Decisions). |
| Documentation of the raw-vs-typed boundary | partial | Add a short rationale doc/section so the boundary is explicit. Phase 1. |

## Architecture Decisions

1. **No new exception subtypes.** The hierarchy stays shallow: `BadRequestException`, `ResourceNotFoundException`, `UpstreamGatewayException`(+leaves). No remaining throw shares a genuine category needing a distinct HTTP status that the existing two 4xx types don't cover.
2. **All 30 remaining raw throws are KEPT.** They are programming-error guards / config invariants / enum resolvers / builder preconditions on non-REST paths. `IllegalArgumentException` / `IllegalStateException` is idiomatic and is NOT part of the API contract for any of them. Over-typing a correct programming-error guard adds noise (AD from task brief).
3. **The `IllegalArgumentException→400` and `IllegalStateException→500` fallback arms stay** (`RestExceptionHandler.java:88`, `:133`). They are the safety net for any kept-raw throw that does incidentally reach the REST layer (e.g. an out-of-order internal caller), and for future raw throws added before they're triaged.
4. **`ProductCode.fromCode` and `MetricKey.promql` are explicitly kept raw**, with reachability rationale (findings above): the first is Jackson-wrapped into a 400 already; the second is dead-code-guarded behind `BadRequestException` in the controller.
5. **Zero HTTP-behavior change.** No status code asserted by `RestExceptionHandlerTest`, `BotGroupControllerTest`, `GameControllerTest`, `EnvironmentControllerTest`, `MetricsControllerTest`, `StrategyControllerTest`, or `SessionHistoryControllerTest` changes. No existing type-assertion test is modified.
6. **This pass writes no production code.** The only change is documentation (Phase 1) recording the boundary. If a reviewer disagrees with a specific KEEP, that becomes a scoped follow-up, not part of this pass.

## The 30-throw classification table

All 30 are classified **(b) KEEP**. `Cur` = current type (`IAE` =
`IllegalArgumentException`, `ISE` = `IllegalStateException`).

| # | file:line | Cur | Trigger | Class | Why keep |
|---|---|---|---|---|---|
| 1 | `infrastructure/auth/AuthStrategyFactory.java:22` | IAE | Env has no `productCode` | b | Config invariant hit on per-bot start path (virtual thread), collected as creation error; not REST-typed. Test locks IAE. |
| 2 | `infrastructure/client/ApiGatewayClient.java:113` | ISE | Client used before `init()` | b | Classic "not initialized" programming error. Handler doc (`:120`) names this as the archetype for the sanitised-500 arm. |
| 3 | `infrastructure/client/ClientFactory.java:38` | IAE | `TokensProvider` null | b | Builder precondition / method arg guard. Internal caller contract. |
| 4 | `domain/metrics/MetricKey.java:165` | IAE | Metric key lacks template for scope | b | Unreachable from controller — `resolveMetric` guards with `BadRequestException` first (`MetricsController.java:127`). Defensive internal contract. |
| 5 | `domain/brand/model/ProductCode.java:83` | IAE | Unknown product code string | b | Jackson `@JsonCreator`; invalid request-body values become `HttpMessageNotReadableException`→400 already. Test locks IAE. |
| 6 | `domain/game/model/Game.java:267` | ISE | Neither `optionAffinities` nor `numberOfOptions` set | b | Domain invariant on effective-config accessor, called on the bot decide path. Test locks ISE. |
| 7 | `domain/botgroup/service/BotGroupBehaviorService.java:859` | ISE | Restart produced 0 bots | b | Deliberate loud 500 signal (comment `:851`). Intended for terminal 500 arm. |
| 8 | `domain/botgroup/validation/GameConfigValidatorFactory.java:53` | ISE | Validator `supportedType()` null | b | Boot-time registry integrity guard (`@PostConstruct`). Deploy bug. Test locks ISE. |
| 9 | `domain/botgroup/validation/GameConfigValidatorFactory.java:59` | ISE | Duplicate validator for a `GameType` | b | Boot-time registry integrity guard. Test locks ISE. |
| 10 | `domain/botgroup/validation/GameConfigValidatorFactory.java:70` | ISE | A `GameType` has no validator | b | Boot-time completeness assertion (fail-fast at deploy). Test locks ISE. |
| 11 | `domain/botgroup/validation/GameConfigValidatorFactory.java:91` | IAE | `forType` on unregistered type | b | Guarded against at boot (`:70`); "should never happen". Test locks IAE. |
| 12 | `domain/bot/core/SlotMachineBot.java:125` | ISE | SLOT game has no `gameId` | b | Misconfiguration fail-loud at bot init (per-bot thread). Not REST-typed. |
| 13 | `domain/bot/core/BettingMiniGameBot.java:738` | ISE | Strategy declined re-derivation after reconnect race | b | "Effectively unreachable" internal-invariant guard on the scenario thread. |
| 14 | `domain/bot/message/GameMessageTypesResolver.java:43` | IAE | `productCode` null (betting-mini) | b | Method arg guard on a resolver, per-bot path. Test locks IAE. |
| 15 | `domain/bot/message/GameMessageTypesResolver.java:52` | IAE | Product not yet implemented (betting-mini) | b | "Not yet implemented" resolver guard, per-bot path. Test locks IAE. |
| 16 | `domain/bot/message/GameMessageTypesResolver.java:89` | IAE | `productCode` null (Tai Xiu) | b | Method arg guard. Test locks IAE. |
| 17 | `domain/bot/message/GameMessageTypesResolver.java:98` | IAE | Product not yet implemented (Tai Xiu) | b | "Not yet implemented" resolver guard. Test locks IAE. |
| 18 | `domain/bot/service/BotFactory.java:120` | ISE | Resolved `zoneName` null/blank | b | Env misconfiguration fail-loud on per-bot creation path. Test (`BotFactoryFailLoudTest`) locks ISE. |
| 19 | `domain/bot/service/BotFactory.java:178` | IAE | Game type not yet implemented (CARD_GAME/UP_DOWN) | b | Enum-switch exhaustiveness guard on per-bot path. Not REST-typed. |
| 20 | `domain/bot/strategy/StrategyAssignment.java:90` | IAE | Weighted strategy weight `<= 0` | b | Pure-function arg precondition. Test locks IAE. |
| 21 | `domain/bot/strategy/StrategyAssignment.java:173` | IAE | Duplicate bot identifier | b | Pure-function invariant guard. Test locks IAE. |
| 22 | `domain/bot/strategy/StrategyAssignment.java:191` | ISE | Apportionment math bug (cursor != botCount) | b | "This should never happen" arithmetic-invariant guard. |
| 23 | `domain/bot/strategy/BotMemory.java:84` | IAE | `capacity <= 0` | b | Constructor precondition. Test (`BotMemoryTest`) locks IAE. |
| 24 | `domain/bot/strategy/BettingStrategyFactory.java:68` | ISE | Duplicate `@StrategyImpl` | b | Boot-time registry integrity guard. Test locks ISE. |
| 25 | `domain/bot/strategy/BettingStrategyFactory.java:88` | IAE | Unknown `StrategyId` in `create` | b | Registry-miss guard (assignment is server-derived, not client input). Test locks IAE. |
| 26 | `domain/bot/strategy/WeightedOptionPicker.java:64` | IAE | `weights` null/empty | b | Pure-function arg precondition. Test locks IAE. |
| 27 | `domain/bot/strategy/slot/SlotStrategyFactory.java:64` | ISE | Duplicate `@SlotStrategyImpl` | b | Boot-time registry integrity guard. Test locks ISE. |
| 28 | `domain/bot/strategy/slot/SlotStrategyFactory.java:85` | IAE | Unknown `SlotStrategyId` in `create` | b | Registry-miss guard, server-derived id. Test locks IAE. |
| 29 | `domain/bot/strategy/martingale/AffinityOptionPicker.java:72` | IAE | `profile` null | b | Constructor precondition. Test locks IAE. |
| 30 | `domain/bot/strategy/martingale/AffinityOptionPicker.java:97` | IAE | `affinities` null/empty | b | Pure-function arg precondition. Test locks IAE. |

## New subtypes

**None.** Per AD-1/AD-2, no remaining throw warrants a new type. Reusing
`BadRequestException` / `ResourceNotFoundException` would be wrong for all 30
because none is a REST-surfaced client error; a new type is only justified when
several throws share a category needing a *distinct HTTP status*, which does not
occur here.

## Kept raw — and why (category-b rationale)

The 30 group into five idioms, each of which is correct as raw JDK exceptions:

1. **Boot-time registry integrity** (#8, #9, #10, #24, #27) — duplicate-key /
   completeness assertions in `@PostConstruct` registries. Fail-fast at deploy;
   never reach a request. Idiomatic `ISE`.
2. **Registry-miss on server-derived keys** (#11, #25, #28) — `forType`/`create`
   for an id/type that must have been registered at boot. The lookup key is
   server-computed (strategy assignment, resolved game type), not client input,
   so a miss is a programming/deploy bug, not a 400. Idiomatic `IAE`.
3. **Pure-function / constructor preconditions** (#3, #20, #21, #22, #23, #26,
   #29, #30) — arg guards on internal helpers and value objects. Typing them
   would leak API concerns into leaf utilities. Idiomatic `IAE`/`ISE`.
4. **Enum resolvers & "not yet implemented" switches** (#5, #14, #15, #16, #17,
   #19) — `@JsonCreator` (Jackson-wrapped to 400 already) and product/game-type
   switches on per-bot paths. Not REST-typed; some are Jackson-owned.
5. **"Should never happen" / deliberate loud-fail invariants** (#2, #4, #6, #7,
   #12, #13, #18) — not-initialized guards, unreachable defensive guards, and
   intentional 500-level signals. These are exactly what the sanitised-500 arm
   exists for (handler doc at `RestExceptionHandler.java:120`).

## Tests that lock raw exception TYPE or MESSAGE

These already assert the raw type/message and **must remain unchanged** (they
are the contract confirming the KEEP). Dev must NOT modify them; if any needs to
change, that is a signal the classification is wrong and must be escalated
before touching code.

- `brand/model/ProductCodeTest.java:114` — `isInstanceOf(IllegalArgumentException)` + `hasMessageContaining("999")` (#5).
- `infrastructure/auth/AuthStrategyFactoryTest.java:81` — `IllegalArgumentException` + message `"misconfigured-env"` (#1).
- `bot/message/GameMessageTypesResolverTest.java:60,69` — `IllegalArgumentException` (#14–#17).
- `bot/service/BotFactoryFailLoudTest.java:97,120,143` — `IllegalStateException` naming the missing field (#18).
- `game/model/GameTest.java:87,100` — `IllegalStateException` (#6).
- `bot/strategy/BettingStrategyFactoryTest.java:73,88` — `IllegalArgumentException` (#25), `IllegalStateException` (#24).
- `bot/strategy/slot/SlotStrategyFactoryTest.java:74,86` — `IllegalArgumentException` (#28), `IllegalStateException` (#27).
- `botgroup/validation/GameConfigValidatorFactoryTest.java:61,73,84,107` — `IllegalStateException` (#8–#10), `IllegalArgumentException` (#11).
- `bot/strategy/StrategyAssignmentTest.java:144,152,303` — `IllegalArgumentException` (#20, #21).
- `bot/strategy/BotMemoryTest.java` — `IllegalArgumentException` (#23).
- `bot/strategy/WeightedOptionPickerTest.java` — `IllegalArgumentException` (#26).
- `bot/strategy/martingale/AffinityOptionPickerTest.java` — `IllegalArgumentException` (#29, #30).
- `common/exception/RestExceptionHandlerTest.java:55,96,196` — `IAE→400` and `ISE→500` fallback arms (the safety net; MUST stay).
- `common/exception/BotManagerExceptionHierarchyTest.java` — base + 3 subtypes reparenting.

## Plan

Only one phase, because there are zero (a) migrations. The phase records the
boundary so it doesn't silently erode.

### Phase 1 — Document the raw-vs-typed boundary (no production code)

Goal: make the "these stay raw, on purpose" decision explicit and greppable so a
future contributor doesn't either (a) re-migrate them or (b) add a new raw throw
on a REST path without triaging it.

Steps:

1. Add a short subsection to `CLAUDE.md` (or a dedicated
   `docs/architecture/EXCEPTION_HIERARCHY.md` if the user prefers keeping
   `CLAUDE.md` lean) stating:
   - The three-arm mapping and the `IAE→400` / `ISE→500` fallback contract.
   - The rule: **REST-surfaced client/config errors throw a `BotManagerException`
     subtype; programming-error guards / registry invariants / value-object
     preconditions stay raw `IllegalArgumentException`/`IllegalStateException`.**
   - A one-line pointer to this plan's classification table as the audit of
     record for the 30 kept-raw sites.
2. No code, no test changes.

This phase is one reviewable commit and depends on nothing.

> If, during review, the user decides a *specific* throw among the 30 should in
> fact be typed (e.g. they consider `AuthStrategyFactory:22`'s missing-productCode
> a config error worth a distinct signal), that becomes a **scoped follow-up
> phase**: migrate that single throw to `BadRequestException`, and update its one
> type-assertion test with explicit justification. It is deliberately **not**
> bundled here to keep this pass's scope crisp and its HTTP behavior provably
> unchanged.

## Implementation Notes / Concerns

- **Do not "tidy" the kept-raw throws into typed ones.** The temptation is to
  make everything a `BotManagerException` for uniformity. That is the exact
  over-migration the task warns against and would break ~14 type-assertion tests.
- **The `IAE→400` fallback is load-bearing.** If a future refactor removes it
  (thinking "everything is typed now"), any un-triaged raw throw on a REST path
  silently becomes a 500. Keep it.
- **`ProductCode.fromCode` must stay a raw `IAE` for Jackson.** `@JsonCreator`
  relies on the JDK exception being thrown so Jackson can wrap it into the
  binding-failure path; a `BotManagerException` there would still be wrapped but
  buys nothing and risks changing the 400 body shape from the
  `HttpMessageNotReadableException` arm to the `handleAny` arm.
- **`MetricKey.promql` IAE looks REST-adjacent but isn't** — verify the
  `resolveMetric` guard (`MetricsController.java:122`) is intact before assuming
  it's reachable.

## Open Items

- **Decision for the user (Phase 1 form):** put the boundary doc in `CLAUDE.md`
  or a new `docs/architecture/EXCEPTION_HIERARCHY.md`? Default assumption:
  `CLAUDE.md` short subsection + pointer to this plan.
- **Out of scope:** any change to WARN/ERROR alerting coupling, the
  `UpstreamGatewayException` `type` discriminator, or the sanitised-500 message
  string. None is touched by this pass.
- **Explicitly out of scope:** migrating any of the 30 kept-raw throws. Escalate
  disagreement before coding rather than editing in-flight.

## Verification

This pass changes no production code and no runtime behavior; there is nothing
new to verify on staging beyond the universal smoke test. The verification below
is therefore a **build/test gate the Releaser runs pre-merge**, not an
on-server check.

All Maven invocations must export the Java 21 home first:

```bash
export JAVA_HOME=/Users/gleb/Library/Java/JavaVirtualMachines/openjdk-21.0.2/Contents/Home
```

1. **Full test suite passes unchanged.**
   ```bash
   JAVA_HOME=/Users/gleb/Library/Java/JavaVirtualMachines/openjdk-21.0.2/Contents/Home mvn -q clean test
   ```
   Expect: `BUILD SUCCESS`, zero failures, zero errors. In particular the
   type-locking tests listed above (`RestExceptionHandlerTest`,
   `BotManagerExceptionHierarchyTest`, `ProductCodeTest`,
   `AuthStrategyFactoryTest`, `GameMessageTypesResolverTest`,
   `BotFactoryFailLoudTest`, `GameTest`, the three factory tests,
   `StrategyAssignmentTest`, `BotMemoryTest`, `WeightedOptionPickerTest`,
   `AffinityOptionPickerTest`) all still pass with no edits.

2. **Raw-throw count is unchanged at 30** (confirms no code was migrated or
   added):
   ```bash
   grep -rn "throw new IllegalArgumentException\|throw new IllegalStateException" \
     src/main/java | wc -l
   ```
   Expect: `30`.

3. **The `IllegalArgumentException→400` fallback arm still exists** in the
   handler:
   ```bash
   grep -n "handleIllegalArgument\|IllegalArgumentException.class" \
     src/main/java/com/vingame/bot/common/exception/RestExceptionHandler.java
   ```
   Expect: a match for the `@ExceptionHandler(IllegalArgumentException.class)`
   arm around `RestExceptionHandler.java:88`.

4. **Universal smoke test (post-deploy, standard):** app starts, actuator health
   is UP.
   ```bash
   curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8080/actuator/health
   ```
   Expect: HTTP `200`.

There is no feature-specific on-server verification beyond the universal smoke
test, because this pass ships documentation only.
