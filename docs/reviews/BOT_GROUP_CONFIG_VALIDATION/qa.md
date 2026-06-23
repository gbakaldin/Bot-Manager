# QA — BOT_GROUP_CONFIG_VALIDATION

**Verdict:** PASS
**Build:** mvn test → 828 tests, 0 failures, 0 errors (baseline before QA additions: 815)

## Scope

Branch `feat/bot-group-config-validation`, 5-phase config-validation feature:
validator framework + factory, BETTING_MINI strict-grid rules, SLOT/default no-op
validators, universal `@Valid(OnCreate)` layer + 400 handler, integration tests.
Diff covered (production):

- `validation/GameConfigValidator.java`, `GameConfigValidatorFactory.java`,
  `BotGroupConfigValidationService.java`, `BettingMiniConfigValidator.java`,
  `SlotConfigValidator.java`, `NoOpConfigValidator.java`,
  `TaiXiu/CardGame/UpDownConfigValidator.java`
- `dto/BotGroupDTO.java`, `dto/OnCreate.java`
- `controller/BotGroupController.java` (`@Validated(OnCreate.class)` on POST only)
- `common/exception/RestExceptionHandler.java` (`handleMethodArgumentNotValid`)
- `service/BotGroupService.java` (validate before registration on create; post-merge on update)
- `pom.xml` (spring-boot-starter-validation)

## Existing coverage assessment

The Dev-authored suite is thorough and the assertions are sound — no existing
assertions were weakened. Pre-existing tests already cover: every BETTING_MINI
rule (non-negativity, strictly-positive, ordering, grid, both cross-field caps),
the long-math overflow path, multiple-violation aggregation, the zero-min
boundary, all four permissive validators, the factory duplicate-key and
missing-type boot failures, the orchestrator null/blank/unresolvable-gameId
paths, the DTO `@NotBlank`/`@Positive` constraints under `OnCreate` vs default
group, and the end-to-end MockMvc chain for POST/PATCH/SLOT/unknown-gameId.

## Tests added / updated

- `src/test/java/com/vingame/bot/domain/botgroup/validation/BettingMiniConfigValidatorTest.java`
  — new `@Nested BoundaryCases` (9 tests): off-by-one grid (`111-100 % 10`),
  `betIncrement` > range, range exactly == increment (passes), `betIncrement==1`
  degenerate grid, `maxTotalBetPerRound` exactly == `maxBet` (passes) and one
  below (rejected), cap exactly == `minBet*minBetsPerRound` with both mins
  non-zero (passes) and one below (rejected, isolating the cross-field clause
  from the maxBet clause), and a second long-overflow guard at the 1.2e10 scale.
- `src/test/java/com/vingame/bot/domain/botgroup/validation/GameConfigValidatorFactoryTest.java`
  — 2 tests: `supportedType()` returning null → `IllegalStateException` at boot
  (previously uncovered `init()` arm); `registeredTypes()` is an unmodifiable
  defensive copy.
- `src/test/java/com/vingame/bot/common/exception/RestExceptionHandlerTest.java`
  — 2 tests + a `ValidatedBody` DTO and `/__test/validated` endpoint:
  `handleMethodArgumentNotValid` sorts field errors alphabetically by field name
  (declared order zebra/alpha/mango → emitted alpha; mango; zebra — pins the
  ordering/stability contract end-to-end through real MVC), and a single-field
  violation produces exactly that message with no stray `"; "` separators.

## Coverage of the diff (gaps the task flagged → where now covered)

- Grid `maxBet−minBet` exceeding increment by exactly 1 ← `BoundaryCases.offByOneGridRejected`
  (pre-existing test only covered off-by-5).
- `minBet==maxBet` with increment ← already green (`Grid.equalBoundsPassGrid`).
- `maxTotal` exactly == `maxBet` boundary ← `BoundaryCases.capEqualsMaxBetPasses` /
  `capOneBelowMaxBetRejected`.
- `minBet*minBetsPerRound` cross-check, both non-zero & near overflow ←
  `BoundaryCases.capEqualsMinTotalPasses`, `capOneBelowMinTotalRejected`,
  `minTotalNoSilentOverflow` (the pre-existing `CrossField.minTotalUsesLongMath`
  covered overflow once; the exact-`>=`-edge and clause-isolation were the gaps).
- Factory duplicate-supportedType / missing-type boot failures ← already green;
  added the third boot arm (null `supportedType()`).
- OnCreate leaving PATCH unconstrained while POST constrained ← already green
  (`BotGroupDTOValidationTest.defaultGroupNoEnforcement`,
  `BotGroupConfigValidationIT.patchPartialNotSubjectToUniversalLayer`).
- Aggregated-message ordering/stability ← `RestExceptionHandlerTest`
  (deterministic alphabetical sort by field name now pinned; the BETTING_MINI
  validator's own insertion-order aggregation is asserted by fragment + `"; "`
  join in the pre-existing `aggregatedMessageFormat`).
- Orchestrator null vs blank vs unresolvable gameId ← already green
  (`BotGroupConfigValidationServiceTest` + IT `GameIdResolution`).

## Gaps (intentionally not covered)

- The factory's `forType` `IllegalArgumentException` arm can only be reached in a
  test by bypassing the boot completeness check; the deploy-time guarantee is the
  boot assertion, which is covered. Existing test exercises the guard directly —
  adequate.
- `botCount == null` on POST passes `@Positive` (Bean Validation treats null as
  valid) and the mapper defaults it to 0; this is documented behavior per AD-10
  and asserted in `BotGroupDTOValidationTest.botCountNull`. Not a defect — the
  required-ness of botCount is intentionally only `@Positive`, not `@NotNull`.
- Live MongoDB / auth-gateway paths are mocked throughout (Mockito), per the test
  idiom; no Testcontainers needed for request-path validation.

## Failures

None.

## Notes for the Releaser

- The `mvn test` console shows a recurring Log4j2 `RollingFileAppender` ERROR
  ("unable to create manager for /app/logs/console.log"). This is a pre-existing
  environment artifact (the JSON log config targets a container path absent on
  the dev box), present on `main`, unrelated to this feature, and does not affect
  test outcomes — BUILD SUCCESS, 0 failures.
- No production code was modified by QA.
