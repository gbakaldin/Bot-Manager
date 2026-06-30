# Compliance — BOT_GROUP_CONFIG_VALIDATION

Branch: `feat/bot-group-config-validation`
Plan reviewed: `docs/plans/BOT_GROUP_CONFIG_VALIDATION.md`
Diff reviewed: commits `02f7cd2`, `d410e97`, `0dd9cd4`, `b4c99a5`, `738ccf8`, `c1aad6a`, `25360fa`
(scoped via `git diff 6ef0d29..25360fa` — the prior commits on the branch are the
Martingale/Slot features this branch sits on top of and are out of scope here)

## Verdict

PASS

## Phase-by-phase

### Phase 1 — Validator interface, factory/registry, integration seam (no-op validators)
Status: implemented
Notes: `GameConfigValidator` interface (`supportedType()` + `validate(BotGroup)`),
`GameConfigValidatorFactory` (`@Component`, ctor `List<GameConfigValidator>`,
`@PostConstruct init()` building an `EnumMap<GameType,…>`, duplicate key →
`IllegalStateException`, `forType()` → `IllegalArgumentException` on unknown,
`registeredTypes()`) all present and mirror the `BettingStrategyFactory`/
`SlotStrategyFactory` shape. The plan-recommended boot-time completeness assertion
(every `GameType.values()` has a validator) is implemented. `NoOpConfigValidator`
abstract base + per-type thin subclasses (Implementation-Notes option (a)).
`BotGroupConfigValidationService` orchestrator resolves Game→GameType and dispatches.
`BotGroupService` injects it and calls `validate` in `save` (new groups) and `update`
(post-merge). Boot log confirmed at runtime:
`GameConfigValidatorFactory initialized: registered 5 validators — [BETTING_MINI, SLOT, TAI_XIU, CARD_GAME, UP_DOWN]`.

### Phase 2 — BettingMiniConfigValidator with numeric rules + tests
Status: implemented
Notes: All AD-3 rules present and exact — non-negativity (all 6 fields), zero valid
for `minBet`/`minBetsPerRound` only, strictly-positive `maxBet`/`betIncrement`/
`maxBetsPerRound`(>=1)/`maxTotalBetPerRound`, ordering `minBet<=maxBet` &
`minBetsPerRound<=maxBetsPerRound`, grid `(maxBet-minBet)%betIncrement==0`, cross-field
`maxTotalBetPerRound>=maxBet` AND `>=minBet*minBetsPerRound` (long math, overflow-safe).
Violations accumulated into a `List<String>` and thrown as one aggregated
`BadRequestException` ("Invalid bot-group config: …"); never first-fail. No invented
server-side bet tables. `BettingMiniConfigValidatorTest` covers every rule class
(28 cases) including the zero-min boundary and multi-violation aggregation.

### Phase 3 — SlotConfigValidator + default/unimplemented validators
Status: implemented
Notes: `SlotConfigValidator` (SLOT) is a deliberate no-op on betting fields (AD-4),
extends `NoOpConfigValidator`, javadoc documents server-sourced bet values.
`TaiXiuConfigValidator`/`CardGameConfigValidator`/`UpDownConfigValidator` are permissive
no-ops (AD-5), one thin subclass per type. Every `GameType` covered exactly once;
factory startup invariant passes. `PermissiveConfigValidatorsTest` proves SLOT and the
three unimplemented types accept garbage betting fields.

### Phase 4 — Universal @Valid / required-field layer
Status: implemented
Notes: `spring-boot-starter-validation` added to pom. `BotGroupDTO` carries
`@NotBlank` on `environmentId`/`gameId`/`namePrefix`/`password` and `@Positive` on
`botCount`, all scoped to the `OnCreate` group. Behavior mins (`minBet`,
`minBetsPerRound`) deliberately NOT annotated (AD-3/AD-10 interaction honored).
Controller `save` is `@Validated(OnCreate.class)`; `update` (PATCH) is untouched.
`RestExceptionHandler.handleMethodArgumentNotValid` overridden to flatten field errors
(sorted by field for deterministic order) into the `{type:"Bad request", msg}` envelope.
Verified at runtime: a missing-basics POST yields
`"botCount must be >= 1; environmentId must not be blank; gameId must not be blank; namePrefix must not be blank; password must not be blank"`.

### Phase 5 — Integration tests
Status: implemented
Notes: `BotGroupConfigValidationIT` (MockMvc, mocked `GameService`/repository) covers
the full chain: missing basics → 400 listing every field; valid create → 200;
`minBet>maxBet` → 400 w/ fragment; `betIncrement=0` → 400; multiple violations → 400
listing all; zero-min boundary → 200; SLOT garbage → 200; PATCH driving merged entity
invalid → 400 (AD-6); partial PATCH omitting basics → 200; unknown gameId → 400 (not
404, AD-7); blank gameId on PATCH-merged entity → 400. 63 tests across the validation
suite pass (`mvn test`, BUILD SUCCESS).

## Architecture Decision checks

- **AD-1 switch-free strategy+factory (hard requirement):** PASS. `grep` of
  `BotGroupService.java` for `GameType|switch|case|instanceof|BETTING_MINI|SLOT`
  returns only a comment line — zero branching. Factory uses bean-collection into an
  `EnumMap`, mirroring `SlotStrategyFactory`/`BettingStrategyFactory`. The service
  delegates entirely to the orchestrator + factory.
- **AD-2 key on GameType directly:** PASS. `supportedType()` returns the enum from the
  interface; no marker annotation. Documented as the intentional deviation.
- **AD-3 BETTING_MINI strict-grid rules:** PASS (see Phase 2). All clauses present and
  exact; aggregated `BadRequestException`.
- **AD-4 SLOT no-op:** PASS.
- **AD-5 TAI_XIU/CARD_GAME/UP_DOWN permissive no-op:** PASS.
- **AD-6 PATCH validates post-merge entity:** PASS. `update` validates `existing`
  after `updateEntityFromDTO`, before `repository.save`. IT proves it.
- **AD-7 unknown/blank gameId → 400 (not 404):** PASS. Orchestrator catches
  `ResourceNotFoundException` and rethrows `BadRequestException`; blank gameId →
  `"gameId is required"`. IT asserts `isBadRequest`.
- **AD-9 single integration seam:** PASS. `BotGroupConfigValidationService` is the only
  caller path; both `save`/`update` route through it.
- **AD-10 universal @Valid layer:** PASS (see Phase 4). POST-only via `OnCreate`,
  PATCH unaffected, behavior mins unannotated, `MethodArgumentNotValidException` → clean 400.

## Deviations the Dev flagged — judged

1. **`OnCreate` validation-group approach for POST-only scoping.** The plan
   (AD-10 / Phase 4) explicitly offered "use a validation group, e.g. `OnCreate`, or
   restrict the annotated path to POST" as acceptable alternatives. Dev chose the
   `OnCreate` group with `@Validated(OnCreate.class)` on `save` only. Faithful — this
   is a plan-sanctioned option, and it correctly leaves PATCH free of required-field
   checks (verified by the partial-PATCH-200 IT case).

2. **`BettingMiniConfigValidator` does NOT extend `NoOpConfigValidator`.** It directly
   `implements GameConfigValidator`. The plan's Phase 2 instruction was to "remove
   BETTING_MINI from the no-op coverage … so the real validator is the only one for
   that type." A real validator with rules has no reason to inherit a no-op base;
   dropping the superclass is the natural realization of that instruction, not drift.
   Faithful. (`SlotConfigValidator` still extends `NoOpConfigValidator` because it
   genuinely is a no-op — consistent.)

3. **Config-validation ordering in `save`: before `validateUsernameLength` rather
   than after.** The plan gave two slightly conflicting recommendations: "after
   `validateUsernameLength`" and "before registration so a bad config doesn't fan out
   N auth calls." Since `validateUsernameLength` only runs on the non-skip
   registration path, the only way to honor the stronger fan-out-avoidance goal AND
   run config validation on the `existingGroup=true` (skip) path is to place it first.
   Dev placed `configValidation.validate(botGroup)` immediately inside the new-group
   block, before the skip/register branch. This is the more correct of the two
   recommendations and satisfies the binding intent (fail fast, both paths). Faithful
   and arguably better than a literal reading; not drift.

None of these are deviations from plan intent; all are within explicitly-offered
latitude or are the natural consequence of plan instructions. No plan amendment is
warranted.

## Out-of-scope changes

None within the config-validation commit range. The diff touches exactly the files the
plan named (plus the planned `OnCreate.java` marker and the test files). Out-of-scope
items the plan listed (re-validating running bots, `betSkipPercentage`,
`BotBehaviorConfig`/start path) were correctly left untouched.

## Amendments to the plan

None.
