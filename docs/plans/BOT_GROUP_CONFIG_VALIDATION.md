# BOT_GROUP_CONFIG_VALIDATION

## Goal

`POST /api/v1/bot-group` and `PATCH /api/v1/bot-group/{id}` accept bot-group
behavior/config without any meaningful validation: `BotGroupDTO` carries zero
Bean Validation annotations, the controller never declares `@Valid`, and
`BotGroupMapper.toEntity` silently defaults every missing numeric field to `0`.
Bad input (e.g. `minBet > maxBet`, `betIncrement = 0`, missing `gameId`) is
accepted, persisted, and only blows up much later at `start()` as an NPE or a
nonsensical bet loop. We want game-type-specific validation of the betting
behavior fields at create/update time, returning a clean aggregated `400`. The
validation must be implemented as a **strategy + factory** keyed on `GameType`
(no `if`/`switch`-on-`GameType` in the service), mirroring the existing
`BettingStrategyFactory` / `SlotStrategyFactory` bean-collection pattern, so
adding a new game type's rules is a new `@Component`, not an edited switch.

## Findings — Current State

### The unvalidated write path
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/botgroup/dto/BotGroupDTO.java`
  — behavior fields are all boxed (`Long minBet`, `Long maxBet`, `Long betIncrement`,
  `Long maxTotalBetPerRound`, `Integer minBetsPerRound`, `Integer maxBetsPerRound`)
  plus universal fields (`environmentId`, `gameId`, `namePrefix`, `password`,
  `botCount`). **Zero** validation annotations (BotGroupDTO.java:20-78).
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/botgroup/controller/BotGroupController.java`
  — `save` (line 91-99) and `update` (line 104-111) take `@RequestBody BotGroupDTO`
  with **no `@Valid`**. `save` calls `mapper.toEntity(dto)` then `service.save(...)`.
  `update` calls `service.update(id, dto)`.
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/botgroup/mapper/BotGroupMapper.java`
  — `toEntity` (line 56-88) defaults every null numeric behavior field to `0`/`0L`
  via `Optional.ofNullable(...).orElse(0L)`. `updateEntityFromDTO` (line 94-140) is
  full-replace-on-non-null per field; it already throws `BadRequestException` for an
  empty `strategyMix` (line 125-128) — precedent for mapper-level guards, but
  numeric guards do **not** belong there (no `GameType` in scope).
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/botgroup/model/BotGroup.java`
  — entity fields are primitives (`long minBet`, `int botCount`, …), so the
  "was it supplied?" signal is lost the moment we map DTO → entity. Validation must
  run against the **DTO** (nullable) or carry the supplied-vs-default distinction.

### Where the fields are consumed
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorService.java:461-470`
  — `BotBehaviorConfig` is built straight from the persisted group at start time;
  garbage-in surfaces here, far from the request.
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/config/bot/BotBehaviorConfig.java`
  — the consuming value object (`minBet`, `maxBet`, `betIncrement`,
  `maxTotalBetPerRound`, `minBetsPerRound`, `maxBetsPerRound`, plus
  `betSkipPercentage` which is **not** a DTO field today).

### Existing service-layer validation
- `BotGroupService.validateUsernameLength`
  (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/botgroup/service/BotGroupService.java:184-203`)
  — game-type-agnostic pre-flight, runs in `save` only on the non-skip path
  (line 120). It dereferences `botGroup.getBotCount()` (primitive `int`, line 195)
  and treats a null prefix as length 0 (line 196) — it does not NPE on null prefix
  today, but it does **not** reject a missing/blank prefix either. The CLAUDE.md
  backlog item "Pre-flight username length validation" is this method.
- `BotGroupService.save` resolves the `Environment` (line 122, 185) but **never the
  `Game`** — there is no `GameService` dependency in the service today. The
  validator framework needs one to resolve `GameType` from `gameId`.

### Exception → HTTP wiring (reuse, do not change)
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/common/exception/BadRequestException.java`
  — single-message `RuntimeException`, mapped to `400` with `{type:"Bad request", msg}`
  by `RestExceptionHandler.handleBadRequest`
  (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/common/exception/RestExceptionHandler.java:78-84`).
  `msg` is forwarded verbatim — safe for aggregated violation text.
- `MethodArgumentNotValidException` (thrown by `@Valid` failures) is **not** mapped
  today. `RestExceptionHandler` extends `ResponseEntityExceptionHandler`, whose
  default `handleMethodArgumentNotValid` returns `400`, and our
  `handleExceptionInternal` override (line 194-209) reshapes the body to
  `{type:"Bad request", msg}` — but `msg` would be Spring's generic text unless we
  override the arm. Relevant for Phase 4 (`@Valid`), which is in scope (AD-10).

### The factory pattern to mirror (no-switch bean collection)
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/strategy/BettingStrategyFactory.java`
  and
  `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/strategy/slot/SlotStrategyFactory.java`
  — both `@Component`, both take `List<Strategy>` in the constructor, both build an
  `EnumMap` keyed by an enum in `@PostConstruct init()` reading a marker annotation
  off each bean, both reject duplicate keys with `IllegalStateException`, both
  expose `create(key)` throwing `IllegalArgumentException` on unknown key, and both
  expose `registeredIds()`. This is the exact shape to copy.
- The thing we are deliberately **not** copying: `BotFactory`
  (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/service/BotFactory.java:143-163`)
  switches on `GameType` inline. Validation must not replicate that.

### Build
- `/Users/gleb/IdeaProjects/Bot/pom.xml` — has `spring-boot-starter-web` but **no**
  `spring-boot-starter-validation`. Jakarta Bean Validation annotations
  (`@NotBlank`, `@Positive`) and `@Valid` enforcement are unavailable until that
  dependency is added (Phase 4 only).

## Per-aspect readiness / mapping

| Aspect | Readiness | Notes |
|---|---|---|
| `BadRequestException` → 400 wiring | ready | Reuse as-is; `msg` forwarded verbatim. |
| Factory bean-collection pattern | ready | Copy `BettingStrategyFactory`/`SlotStrategyFactory` shape exactly. |
| `GameType` enum | ready | 5 values; only `BETTING_MINI` + `SLOT` are implemented in `BotFactory`. |
| `GameService` for `gameId`→`GameType` | ready | Exists; `findById` throws `ResourceNotFoundException`. Must be injected into `BotGroupService`. |
| Validate on create (`save`) | partial | Seam exists; `Game` not resolved there today, no `GameService` dep. |
| Validate on update (`PATCH`) | ready | `update` merges DTO into persisted entity; validator runs on the **merged** entity, not the partial DTO (AD-6). |
| Numeric rules for BETTING_MINI | ready | Thresholds locked: STRICT GRID, zero valid for MINIMUM fields only (AD-3). |
| SLOT field handling | ready | No-op on betting fields, confirmed (AD-4). |
| TAI_XIU / CARD_GAME / UP_DOWN | ready | Permissive no-op validator, confirmed (AD-5). |
| Universal `@Valid` / `@NotBlank` layer | ready (in scope) | Phase 4 IN SCOPE; add `spring-boot-starter-validation`, `@Valid` on POST, annotations on universal basics (AD-10). |

## Architecture Decisions

1. **Strategy + factory, keyed by `GameType`, no switch.** A `GameConfigValidator`
   interface with `GameType supportedType()` and `void validate(BotGroup group)`.
   Per-type `@Component` implementations. A `GameConfigValidatorFactory`
   (`@Component`) takes `List<GameConfigValidator>` in its constructor and builds an
   `EnumMap<GameType, GameConfigValidator>` in `@PostConstruct`, rejecting duplicate
   `supportedType()` keys with `IllegalStateException` — byte-for-byte the
   `BettingStrategyFactory` pattern. Resolution is `factory.forType(gameType)`.

2. **Key on `GameType` directly, not a marker annotation.** The strategy factories
   read a marker annotation (`@StrategyImpl`) because their key is a fine-grained
   `StrategyId` decoupled from the class. Here the key is the coarse `GameType` and
   there is exactly one validator per type, so `supportedType()` returning the enum
   from the interface is simpler and equally switch-free. (This is the one
   intentional deviation from the strategy factories; it is more idiomatic for a
   1:1 type→validator mapping.)

3. **BETTING_MINI rules = STRICT GRID, zero valid for MINIMUM fields only
   (RESOLVED — definitive Phase 2 rules).** The `BettingMiniConfigValidator`
   accumulates **all** violations into a `List<String>` and throws one aggregated
   `BadRequestException` joining them (e.g.
   `"Invalid bot-group config: minBet (500) must be <= maxBet (100); betIncrement must be > 0"`).
   Never throw on the first violation. The validate signature is
   `void validate(BotGroup group)` — the validator already knows its own
   `supportedType()`, and the entity carries the numeric fields as primitives. The
   complete rule set:
   - **Non-negativity (all fields):** no field may be negative.
   - **Minimum fields — zero is explicitly valid:** `minBet >= 0`,
     `minBetsPerRound >= 0`.
   - **Maximum / step / cap fields — must be strictly positive:** `maxBet > 0`,
     `betIncrement > 0`, `maxBetsPerRound > 0` (i.e. `>= 1`),
     `maxTotalBetPerRound > 0`.
   - **Ordering:** `minBet <= maxBet`; `minBetsPerRound <= maxBetsPerRound`.
   - **Grid:** `(maxBet - minBet)` must be exactly divisible by `betIncrement`
     (`(maxBet - minBet) % betIncrement == 0`).
   - **Cross-field caps:** `maxTotalBetPerRound >= maxBet`, AND
     `maxTotalBetPerRound >= minBet * minBetsPerRound`. The second clause is
     trivially satisfied when either min is 0; keep the check anyway so it bites
     once the mins are non-zero.
   Do **not** invent server-side discrete bet-value tables or per-product caps —
   those are out of scope and not part of these rules.

4. **SLOT validator is a no-op on the betting fields (ignore, do not reject) —
   RESOLVED.** Slot bet values come from the server Js set / `cmd:1300` subscribe
   response at runtime (`Game.gameId` javadoc, Game.java:39-47; SLOT_MACHINE_BOT
   plan AD-8/AD-11), so `minBet`/`betIncrement`/round-bet-counts are meaningless.
   Rejecting a group because a UI sent leftover zeros would be hostile. The
   `SlotConfigValidator` validates only what slots actually use (nothing in v1) and
   ignores the betting fields. Confirmed: no-op, not reject-if-set.

5. **Unimplemented types (TAI_XIU/CARD_GAME/UP_DOWN) get a permissive no-op
   validator, NOT a hard rejection — RESOLVED.** These types cannot
   start a bot anyway (`BotFactory` throws `IllegalArgumentException`,
   BotFactory.java:161-162), so blocking *group creation* adds no safety and breaks
   the ability to pre-stage config. One shared `NoOpConfigValidator` is registered
   for all three via three thin subclasses (or one validator that the factory maps
   to multiple keys — see Implementation Notes). Confirmed: permissive no-op, do
   not block creation.

6. **PATCH validates the post-merge entity, not the partial DTO.** `update` merges
   the partial DTO into the persisted entity (`updateEntityFromDTO`), then resolves
   `GameType` from the merged `gameId` and runs the validator on the merged result —
   **before** `repository.save`. This makes a PATCH that sets only `maxBet=50` on a
   group with persisted `minBet=100` correctly fail. A partial-DTO-only validator
   could not see the cross-field relationship.

7. **The validator resolves `GameType` via `GameService.findById(gameId)`.** A
   null/blank/unresolvable `gameId` is a *universal required-field* failure
   (`gameId is required` / `Game not found`), surfaced as `BadRequestException`
   **before** the game-type validator is selected — the factory cannot pick a
   validator without a `GameType`. `GameService.findById` throws
   `ResourceNotFoundException` (404) on unknown id; the orchestrator catches that
   and rethrows as `BadRequestException` (400) so a bad `gameId` in a create body is
   a client error, not a 404. RESOLVED: unknown `gameId` resolves to a 400.

8. **`validateUsernameLength` stays separate from the game-type framework.** It is
   genuinely game-type-agnostic (product username cap) and already correctly placed
   in `save`. Folding it into a per-`GameType` validator would duplicate it across
   every validator. It stays exactly where it is and as-is; the universal
   required-field checks (blank `namePrefix`, `botCount < 1`, etc.) are added in
   Phase 4 at the controller boundary via Bean Validation, not by extending this
   method.

9. **A new `BotGroupConfigValidationService` (or method on `BotGroupService`) is the
   single integration seam.** Both `save` and `update` call it. It (a) resolves the
   `Game`/`GameType`, (b) selects the validator via the factory, (c) invokes
   `validate`. This keeps `BotGroupService.save`/`update` free of any `GameType`
   branching. Recommendation: a small dedicated `@Service`
   (`BotGroupConfigValidator` orchestrator) injected into `BotGroupService`, so the
   service stays thin and the orchestrator is independently testable.

10. **Universal required-field validation (Phase 4) is IN SCOPE — RESOLVED.**
    Add `spring-boot-starter-validation`, `@Valid` on the **POST** controller method,
    and Bean Validation annotations on the universal required fields: `environmentId`
    (`@NotBlank`), `gameId` (`@NotBlank`), `namePrefix` (`@NotBlank`), `password`
    (`@NotBlank`), `botCount` (`@Positive` / `>= 1`). This closes the CLAUDE.md
    backlog item on pre-flight validation. This layer is the universal,
    game-type-agnostic basics and is kept clearly separated from the game-type
    strategy validators (which own all betting-behavior-field rules).
    **Interaction with AD-3:** because the betting-mini grid rules treat `minBet=0`
    and `minBetsPerRound=0` as valid ("omitted → 0" is acceptable for those mins),
    the DTO-level annotations MUST NOT force those behavior mins to be positive. The
    `@Valid` layer covers ONLY the universal non-behavior basics listed above; the
    behavior-field rules live entirely in the game-type validator. `@Valid` is
    applied to the POST path only (PATCH is intentionally partial — use a validation
    group, e.g. `OnCreate`, or restrict the annotated path to POST); PATCH continues
    to rely on the post-merge entity validation of Phase 1/AD-6.

## Plan

### Phase 1 — Validator interface, factory/registry, integration seam (no-op validators)

**Goal:** Land the switch-free framework and wire it into `save`/`update` with
no-op validators, so the seam is proven and committed before any rule exists.

**Create:**
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/botgroup/validation/GameConfigValidator.java`
  — interface: `GameType supportedType()`, `void validate(BotGroup group)`.
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/botgroup/validation/GameConfigValidatorFactory.java`
  — `@Component`, constructor `List<GameConfigValidator>`, `@PostConstruct` builds
  `EnumMap<GameType, GameConfigValidator>` (duplicate key → `IllegalStateException`),
  `forType(GameType)` returns the validator (unknown → `IllegalArgumentException`,
  which should not happen once every `GameType` has a validator),
  `registeredTypes()`. Mirror `BettingStrategyFactory.java` exactly.
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/botgroup/validation/NoOpConfigValidator.java`
  — temporary placeholder(s) so the `EnumMap` has every `GameType` key and the
  factory's "every type registered" invariant holds. One class returning a single
  type, or register the same logic per type (see Implementation Notes for the
  multi-key wrinkle). These get replaced/specialised in Phases 2-3.
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/botgroup/validation/BotGroupConfigValidationService.java`
  — `@Service`; injects `GameConfigValidatorFactory` + `GameService`; method
  `validate(BotGroup group)`: resolve `Game` from `group.getGameId()` (catch
  `ResourceNotFoundException` → `BadRequestException` "Game not found: <id>"; null/blank
  gameId → `BadRequestException` "gameId is required"), `factory.forType(game.getGameType())`,
  `validator.validate(group)`.

**Modify:**
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/botgroup/service/BotGroupService.java`
  — inject `BotGroupConfigValidationService`. In `save(...)`, call
  `configValidation.validate(botGroup)` for new groups before persistence (after
  `validateUsernameLength`, before/after registration — recommend **before**
  registration so a bad config doesn't fan out N auth calls). In `update(...)`,
  call it on `existing` **after** `mapper.updateEntityFromDTO` and **before**
  `repository.save`.

**Verification (Phase 1):** `mvn -q compile` succeeds. App boots; log line
`GameConfigValidatorFactory initialized: registered N validators — [...]` lists all
5 `GameType` values. A `POST` with any body still behaves exactly as before (no-op
validators reject nothing). Unit test: factory resolves each `GameType` to a
non-null validator; duplicate-key wiring throws `IllegalStateException`.

### Phase 2 — `BettingMiniConfigValidator` with numeric rules + tests

**Goal:** Replace the BETTING_MINI no-op with the real cross-field numeric rules.

**Create:**
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/botgroup/validation/BettingMiniConfigValidator.java`
  — `@Component`, `supportedType() == BETTING_MINI`. Accumulates violations into a
  `List<String>`; throws one aggregated `BadRequestException` if non-empty.
  Definitive rules (locked, per AD-3 — STRICT GRID, zero valid for minimum fields
  only):
  - **Non-negativity:** no field may be negative (`minBet`, `maxBet`, `betIncrement`,
    `minBetsPerRound`, `maxBetsPerRound`, `maxTotalBetPerRound` all `>= 0`).
  - **Minimum fields — zero explicitly valid:** `minBet >= 0`, `minBetsPerRound >= 0`.
  - **Strictly positive fields:** `maxBet > 0`, `betIncrement > 0`,
    `maxBetsPerRound > 0` (`>= 1`), `maxTotalBetPerRound > 0`.
  - **Ordering:** `minBet <= maxBet`; `minBetsPerRound <= maxBetsPerRound`.
  - **Grid:** `(maxBet - minBet) % betIncrement == 0`.
  - **Cross-field caps:** `maxTotalBetPerRound >= maxBet` AND
    `maxTotalBetPerRound >= minBet * minBetsPerRound` (the second clause is trivially
    satisfied when a min is 0; keep it for the non-zero-min case).
  - **Do NOT** invent server-side bet-table constraints (allowed discrete bet
    values, per-product caps) — out of scope.

**Modify:** Remove BETTING_MINI from the no-op coverage (delete its key
registration / its no-op subclass) so the real validator is the only one for that
type.

**Verification (Phase 2):** Unit tests (`BettingMiniConfigValidatorTest`) cover each
rule: a valid config passes; each violation produces the expected message; multiple
simultaneous violations are all reported in one exception (assert the message
contains every expected fragment). `mvn -q test` green.

### Phase 3 — `SlotConfigValidator` + default/unimplemented validators

**Goal:** Real SLOT validator (no-op on betting fields per AD-4) and the
permissive default for unimplemented types (AD-5).

**Create:**
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/botgroup/validation/SlotConfigValidator.java`
  — `supportedType() == SLOT`; validates nothing in v1 (documents AD-4 in javadoc:
  betting fields ignored, bet values are server-sourced).
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/botgroup/validation/UnimplementedGameTypeConfigValidator.java`
  (or keep `NoOpConfigValidator`) covering TAI_XIU/CARD_GAME/UP_DOWN — permissive
  no-op (AD-5). Resolve the multi-key registration wrinkle (Implementation Notes).

**Modify:** Ensure every `GameType` is covered by exactly one validator; the
factory's startup invariant (all types registered, no duplicates) passes.

**Verification (Phase 3):** App boots, factory lists all 5 types. Unit test: SLOT
group with garbage betting fields passes validation; TAI_XIU group passes. `mvn -q
test` green.

### Phase 4 — Universal `@Valid` / required-field layer (IN SCOPE)

**Goal:** Fail-fast `400` on game-type-agnostic basics at the controller boundary,
complementary to the strategy layer. Closes the CLAUDE.md "Pre-flight username
length validation" backlog item's required-field aspect. IN SCOPE (AD-10).

**Modify:**
- `/Users/gleb/IdeaProjects/Bot/pom.xml` — add `spring-boot-starter-validation`.
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/botgroup/dto/BotGroupDTO.java`
  — `@NotBlank environmentId/gameId/namePrefix/password`, `@Positive` (`>= 1`) on
  `botCount` (POST view only). Use a validation group (e.g. `OnCreate`) so PATCH —
  which is intentionally partial — is not subjected to required-field checks.
  **Do NOT** annotate the betting-behavior mins (`minBet`, `minBetsPerRound`) as
  positive: per AD-3 those are valid at 0 and their rules belong to the game-type
  validator, not this universal layer.
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/botgroup/controller/BotGroupController.java`
  — `@Validated(OnCreate.class) @RequestBody` (or `@Valid`) on `save` only; **not**
  on `update`.
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/common/exception/RestExceptionHandler.java`
  — override `handleMethodArgumentNotValid` to flatten field errors into the
  `{type:"Bad request", msg}` envelope with an aggregated message (matches the
  service-layer aggregation style).

**Verification (Phase 4):** `POST` with missing `gameId` → `400` body
`{"type":"Bad request","msg":"gameId must not be blank; ..."}`. `PATCH` with a
single field still succeeds (no required-field enforcement). `mvn -q test` green.

### Phase 5 — Integration tests (POST/PATCH bad configs → 400 with messages)

**Goal:** End-to-end MockMvc tests proving the wired behavior.

**Create:**
- `/Users/gleb/IdeaProjects/Bot/src/test/java/com/vingame/bot/domain/botgroup/controller/BotGroupConfigValidationIT.java`
  (or a `@WebMvcTest` slice with mocked `GameService` returning a BETTING_MINI /
  SLOT game). Cases: POST BETTING_MINI with `minBet>maxBet` → 400 + message; POST
  with `betIncrement=0` → 400; POST with multiple violations → 400 listing all;
  POST SLOT with garbage betting fields → 200; PATCH that drives the merged entity
  invalid (e.g. lower `maxBet` below persisted `minBet`) → 400; POST with unknown
  `gameId` → 400.

**Verification (Phase 5):** `mvn -q test` green; the suite asserts both status codes
and `msg` fragments.

## Implementation Notes / Concerns

- **Multi-key validator wiring wrinkle.** The strategy factories assume one bean ↔
  one key. Here three `GameType`s (TAI_XIU/CARD_GAME/UP_DOWN) want the *same*
  permissive behavior. Cleanest options, in order of preference: (a) three trivial
  subclasses each returning one type (`class TaiXiuConfigValidator extends
  NoOpConfigValidator { supportedType()=TAI_XIU }`); (b) change `supportedType()`
  to `Set<GameType> supportedTypes()` so one bean registers multiple keys — but this
  diverges from the strategy-factory shape, so only do it if the owner prefers it.
  Recommend (a) for closest mirror.
- **Validate against the entity, not the DTO.** Entity fields are primitives, so by
  the time the validator runs every numeric is concrete (mapper defaulted nulls to
  0). Per AD-3 this is intentional and correct: an omitted `minBet`/`minBetsPerRound`
  maps to 0, and 0 is a valid value for those minimum fields — so "omitted" and
  "explicit 0" are deliberately treated identically. No omitted-vs-zero distinction
  is needed; the strictly-positive fields (`maxBet`, `betIncrement`,
  `maxBetsPerRound`, `maxTotalBetPerRound`) reject a defaulted-0 anyway.
- **PATCH ordering is load-bearing (AD-6).** Validate after merge, before save. If
  Dev validates the raw DTO, cross-field PATCH rules silently pass.
- **`save` already resolves `Environment`; now also resolves `Game`.** Two Mongo
  reads on the create path. Acceptable; both are by-id point reads.
- **Do not move numeric guards into `BotGroupMapper`.** The mapper has no `GameType`
  and shouldn't gain a `GameService` dependency. The empty-`strategyMix` guard there
  is a pre-existing exception, not a pattern to extend.
- **Aggregated message safety.** `BadRequestException.msg` is forwarded verbatim to
  the client (RestExceptionHandler.java:82-83). Keep messages operator-facing and
  free of internal identifiers — they are by construction (field names + values).
- **Factory startup invariant.** If a `GameType` value is added later without a
  validator, `forType` throws `IllegalArgumentException` at request time. Consider a
  startup assertion in `@PostConstruct` that every `GameType.values()` has a
  registered validator (fail fast at boot rather than at first bad request) — but
  this couples the factory to the full enum; decide with owner. Recommend the boot
  assertion: it turns a missed validator into a deploy-time failure.

## Open Items

- **[RESOLVED] BETTING_MINI numeric thresholds:** STRICT GRID with zero valid for
  MINIMUM fields only. `minBet >= 0`, `minBetsPerRound >= 0`; `maxBet > 0`,
  `betIncrement > 0`, `maxBetsPerRound >= 1`, `maxTotalBetPerRound > 0`; ordering
  `minBet <= maxBet` and `minBetsPerRound <= maxBetsPerRound`; grid
  `(maxBet-minBet) % betIncrement == 0`; cross-field `maxTotalBetPerRound >= maxBet`
  AND `>= minBet*minBetsPerRound`; no negatives; aggregate all violations into one
  `BadRequestException`. No server-side discrete bet-value tables (out of scope). See
  AD-3 / Phase 2.
- **[RESOLVED] SLOT field handling:** AD-4 — no-op on betting fields (bet values come
  from the server Js set). Confirmed, not reject-if-set.
- **[RESOLVED] Unimplemented types:** AD-5 — permissive no-op validator. Confirmed,
  do not block creation.
- **[RESOLVED] Bad `gameId` status:** AD-7 — resolves to a 400 (`BadRequestException`).
  Confirmed.
- **[RESOLVED] Phase 4 in scope:** AD-10 — IN SCOPE. Add `spring-boot-starter-validation`,
  `@Valid` on POST, `@NotBlank` on `environmentId`/`gameId`/`namePrefix`/`password`,
  `@Positive` on `botCount`. POST-only; PATCH stays on post-merge entity validation.
  The behavior mins (`minBet`/`minBetsPerRound`) are NOT annotated positive — they
  remain valid at 0 and are owned by the game-type validator.
- **Out of scope:** re-validating already-running bots on PATCH (mirrors existing
  strategyMix/slotStrategyId mid-flight semantics — no re-assignment); `betSkipPercentage`
  (not a DTO field today); changing `BotBehaviorConfig` or the start path.

## Verification

These run on staging after deploy. The feature is request-path validation; verify
via the REST API. Replace `$HOST` with the staging base URL and use a real
BETTING_MINI `gameId` (`$BM_GAME`) and SLOT `gameId` (`$SLOT_GAME`) from
`GET /api/v1/game/`.

1. **Framework booted (Phase 1).** In the application log after startup, expect a
   line matching `GameConfigValidatorFactory initialized: registered 5 validators`.
   ```
   grep "GameConfigValidatorFactory initialized" <app-log>
   ```
   Expect a match listing all 5 `GameType` values.

2. **BETTING_MINI invalid config rejected (Phase 2).** `minBet > maxBet`:
   ```
   curl -s -o /dev/null -w '%{http_code}' -X POST "$HOST/api/v1/bot-group/" \
     -H 'Content-Type: application/json' \
     -d '{"name":"vtest","environmentId":"<env>","gameId":"'$BM_GAME'","namePrefix":"vt","password":"x","botCount":1,"minBet":500,"maxBet":100,"betIncrement":10,"minBetsPerRound":1,"maxBetsPerRound":1,"maxTotalBetPerRound":1000,"existingGroup":true}'
   ```
   Expect HTTP `400`. Re-run without `-o /dev/null` and expect the JSON body
   `type` to be `"Bad request"` and `msg` to contain `minBet` and `maxBet`.

3. **BETTING_MINI multiple violations aggregated (Phase 2).** Send `minBet>maxBet`
   AND `betIncrement=0`; expect HTTP `400` and a `msg` containing **both** the
   `minBet`/`maxBet` fragment and the `betIncrement` fragment (proves aggregation,
   not first-fail).

4. **BETTING_MINI valid config accepted (Phase 2).** Same call as step 2 with
   `minBet:100,maxBet:500`; expect HTTP `200`. (Use `existingGroup":true` to skip
   upstream registration, or delete the created group afterward via
   `DELETE /api/v1/bot-group/{id}`.)

5. **SLOT garbage betting fields ignored (Phase 3).** POST a SLOT group with
   `gameId:$SLOT_GAME`, `minBet:999,maxBet:1,betIncrement:0`; expect HTTP `200`
   (AD-4 — betting fields not validated for slots).

6. **Unknown gameId rejected (AD-7).** POST with `gameId:"does-not-exist"`; expect
   HTTP `400` (not 404) and `msg` referencing the game/gameId.

7. **PATCH post-merge validation (AD-6).** On the group created in step 4, PATCH
   only `{"maxBet":50}` (below persisted `minBet:100`):
   ```
   curl -s -o /dev/null -w '%{http_code}' -X PATCH "$HOST/api/v1/bot-group/<id>" \
     -H 'Content-Type: application/json' -d '{"maxBet":50}'
   ```
   Expect HTTP `400`.

8. **Universal required-field on POST (Phase 4).** POST with `gameId` omitted; expect
   HTTP `400` with `msg` referencing `gameId`. POST with `botCount:0`; expect HTTP
   `400` referencing `botCount`. PATCH with a single unrelated field still returns
   `200` (PATCH not subject to required-field checks).

9. **BETTING_MINI zero-min boundary accepted (Phase 2, AD-3).** POST a BETTING_MINI
   group with `minBet:0, minBetsPerRound:0, maxBet:100, betIncrement:10,
   maxBetsPerRound:5, maxTotalBetPerRound:1000`; expect HTTP `200` (zero is valid for
   the minimum fields, grid `(100-0)%10==0` holds).

Steps 1-9 are the full on-server verification beyond the universal smoke test.
