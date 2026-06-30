# Code Review — BOT_GROUP_CONFIG_VALIDATION

Branch: feat/bot-group-config-validation
Reviewed diff: `git diff 6ef0d29..25360fa` (validation commits `02f7cd2`, `d410e97`, `0dd9cd4`, `b4c99a5`, `738ccf8`, `c1aad6a`, `25360fa`; slot commits beneath on this stacked branch were excluded from review scope)

## Verdict

PASS

No `bug` or `security` findings. The smell/style items below are advisory.

## Findings

### [style] Inline fully-qualified `java.util.*` references in `RestExceptionHandler.handleMethodArgumentNotValid`
`src/main/java/com/vingame/bot/common/exception/RestExceptionHandler.java:178-184`

`java.util.Comparator.comparing(...)`, `java.util.stream.Collectors.joining(...)` are written fully-qualified inline rather than imported. The class already imports several `org.springframework.*` and `jakarta.*` types at the top, so the established style here is to import. Same pattern appears in two test files (`BotGroupDTOValidationTest` line 194 `java.util.stream.Collectors.toSet()`, `BotGroupConfigValidationServiceTest` line 46 `org.mockito.ArgumentMatchers.anyString()`). Purely cosmetic; behavior is identical. Flagging only because it deviates from the surrounding import-everything convention.

### [smell] Negative strictly-positive fields are double-reported in the aggregated message
`src/main/java/com/vingame/bot/domain/botgroup/validation/BettingMiniConfigValidator.java:64-92`

For `maxBet`, `betIncrement`, and `maxTotalBetPerRound`, a negative value trips both the non-negativity check ("must be >= 0") and the strictly-positive check ("must be > 0"), so the operator sees two lines for the same field, e.g. `betIncrement (-10) must be >= 0; betIncrement (-10) must be > 0`. This is deliberate and explicitly asserted by the test (`BettingMiniConfigValidatorTest.negativeBetIncrement`, lines 148-156), and the stricter "> 0" message alone is arguably the only one a user needs. Not wrong — both statements are true and the aggregation contract is "report everything" — but the redundancy is slightly noisy. If you wanted single-line-per-field, the non-negativity block for the three strictly-positive fields is redundant with the `<= 0` block and could be dropped (the `<= 0` check already covers negatives). Leaving as-is is defensible; calling it out so it's a conscious choice rather than an oversight.

### [smell] Theoretical `long` overflow in the `minBet * minBetsPerRound` cross-field cap
`src/main/java/com/vingame/bot/domain/botgroup/validation/BettingMiniConfigValidator.java:119`

`long minTotal = minBet * (long) minBetsPerRound;` correctly promotes the `int` `minBetsPerRound` to avoid the documented `int` overflow (well covered by `minTotalUsesLongMath`, which uses `3_000_000_000L * 2`). However `long * long` can still overflow `Long.MAX_VALUE` (≈9.2e18) for pathological inputs — e.g. `minBet = 5e18`, `minBetsPerRound = 4` wraps negative, and the `maxTotalBetPerRound < minTotal` comparison then silently passes (no rejection) instead of failing. This is purely theoretical for real bot configs (bet amounts are small), and `maxBet` is already a `long` that would have to be enormous, so no realistic request reaches it. If you want full robustness, `Math.multiplyExact` wrapped to convert `ArithmeticException` into a violation message would close it. Advisory only — not a bug for any plausible input.

## Notes

Strong submission overall. Specific things worth calling out:

- **Switch-free design holds.** `BotGroupService` contains zero `GameType` branching; the seam is a single `configValidation.validate(...)` call in both `save` (create path) and `update`. `BotGroupConfigValidationService` does the `gameId` → `Game` → `GameType` → validator resolution, and `GameConfigValidatorFactory.forType` is a pure `EnumMap` lookup. The factory faithfully mirrors `SlotStrategyFactory`/`BettingStrategyFactory`: `@Component`, `List<...>` constructor injection, `EnumMap` built in `@PostConstruct`, `IllegalStateException` on duplicate key, `IllegalArgumentException` on unknown key, an `unmodifiableSet` accessor for tests. The deliberate deviation (key read from `supportedType()` on the bean rather than a marker annotation) is justified and documented — a 1:1 `GameType` → validator mapping does not need a separate id enum.

- **Boot-time completeness check is the right call.** The `init()` loop over `GameType.values()` turns a missing validator into a deploy-time `IllegalStateException` rather than a first-bad-request `IllegalArgumentException`. All 5 `GameType` values have a registered validator. The `forType` guard is redundant with the boot check but is correct defense-in-depth and is unit-tested directly.

- **Thread-safety of the factory map is fine.** The `registry` `EnumMap` is mutated only inside `@PostConstruct init()` (single-threaded Spring init) and is read-only thereafter; reads under the request-handling virtual threads see a fully-published, never-mutated map. No `volatile`/synchronization needed. This matches the existing strategy factories.

- **`OnCreate` group scoping is correct.** `@Validated(OnCreate.class)` is on `save` (POST) only; `update` (PATCH) has no `@Validated`, so the `@NotBlank`/`@Positive` required-field constraints do not fire on partial updates. This is verified end-to-end (`patchPartialNotSubjectToUniversalLayer`) and at the unit level (`defaultGroupNoEnforcement`). Behavior mins (`minBet`/`minBetsPerRound`) are correctly NOT annotated — their rules live entirely in `BettingMiniConfigValidator`, and `BehaviorMinsUnconstrained` proves the universal layer ignores them.

- **`MethodArgumentNotValidException` handler is consistent with the service-layer shape.** Both paths return `{type:"Bad request", msg:"...; ..."}` with `"; "`-joined violations. The `@Valid` handler sorts field errors by field name for deterministic output and falls back to `"Validation failed"` if the joined message is blank — a sensible guard. Field-error messages are the explicit `message =` text on the DTO constraints, not raw exception internals, so nothing sensitive leaks (consistent with the handler's documented information-disclosure posture).

- **AD-7 (`unknown gameId` → 400 not 404) is implemented cleanly.** `BotGroupConfigValidationService` catches `ResourceNotFoundException` from `gameService.findById` and rethrows as `BadRequestException` with the cause preserved (`BadRequestException(String, Throwable)`). Verified at both unit and IT levels. Blank/null `gameId` → `"gameId is required"` 400 before any validator is selected.

- **Validation seam placement is correct and the apparent "existing-group save isn't validated" is intentional, not a gap.** In `save`, `configValidation.validate` runs inside the `isNewGroup` branch *before* `validateUsernameLength` and the registration fan-out, so a bad config costs one 400 and zero auth calls (AD-9). The `else` branch of `save` (id already set) is the internal status-persistence path used by `BotGroupBehaviorService.save(group)` (4 call sites: lines 264, 566, 641, 854) — re-running config validation there would be wrong (it would re-reject nothing new and add a `GameService` lookup to every status write). The create path goes through the controller's two-arg `save`; the update path validates explicitly post-merge. No write path that accepts user-supplied config bypasses validation.

- **SLOT/permissive no-ops are correct and clearly documented as finalized (not placeholders).** `NoOpConfigValidator` is an `abstract` base; `SlotConfigValidator`, `TaiXiuConfigValidator`, `CardGameConfigValidator`, `UpDownConfigValidator` are thin subclasses overriding only `supportedType()`. The rationale (slot bet values come from the server's subscribe response; TAI_XIU/CARD_GAME/UP_DOWN can't start a bot today) is captured in javadoc with AD references. `PermissiveConfigValidatorsTest` proves permissiveness against a config BETTING_MINI would reject, and includes a sanity test asserting that shared garbage config IS rejected by BETTING_MINI — without which the permissive assertions would be vacuous. Good test hygiene.

- **Test quality is high.** Coverage spans the validator rules (boundary, ordering, grid, cross-field, long-math overflow, aggregation/format), the factory (full coverage, duplicate, missing-type, unknown-type), the orchestrator (null/blank/unknown gameId, delegation, missing gameType), the DTO layer (per-field, group scoping, behavior-mins-unconstrained, PATCH default group), and a real-wiring MockMvc IT exercising the whole chain including the 400 envelope on both POST and PATCH. The `botCountNull` test honestly documents that `@Positive` treats `null` as valid (a null `botCount` passes the universal layer and is defaulted by the mapper) — that is a known, documented limitation of `@Positive`, not a defect; if rejecting an omitted `botCount` is ever desired it would need `@NotNull(groups = OnCreate.class)` added alongside `@Positive`. Out of scope to require here.
