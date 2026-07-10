# QA — TECH_DEBT_CLEANUP_2026_07

**Verdict:** PASS
**Build:** `mvn test` → 1478 tests, 0 failures, 0 errors (Java 21)

## Tests added / updated

All additions are on the feature branch under `src/test/java/`. Dev already
shipped baseline tests for every item; QA strengthened four of them and added
three new cases to close boundary/behavioral gaps.

- `src/test/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorServiceTest.java`
  - Strengthened `shouldMarkGroupDeadWhenAllCreateBotsFail`: now asserts `start()`
    does **not** throw (`assertThatCode(...).doesNotThrowAnyException()`), the
    in-memory runtime is flipped DEAD (`runtime.isGroupDead()` /
    `getActualStatus() == DEAD`, not just the persisted entity), `lastStoppedAt`
    is cleared, and `lastFailureReason` carries the `0/3` operator signal.
  - Added `shouldKeepZeroBotCountGroupActive`: a legitimately zero-`botCount`
    group stays ACTIVE and is not marked DEAD — pins the `botCount > 0` half of
    AD-2's guard `bots.isEmpty() && group.getBotCount() > 0`.
- `src/test/java/com/vingame/bot/domain/environment/service/EnvironmentServiceTest.java`
  - Added `shouldBuildContainsRegexMatchingPartialSubstring`: compiles the
    produced `name` Pattern and asserts it actually `find()`-matches a partial,
    differently-cased substring and rejects a non-match — makes the contains
    semantics load-bearing, not just a pattern-string equality pin.
- `src/test/java/com/vingame/bot/domain/botgroup/service/BotGroupServiceTest.java`
  - Added the equivalent `shouldBuildContainsRegexMatchingPartialSubstring` for
    the env-scoped bot-group filter.
- `src/test/java/com/vingame/bot/domain/environment/mapper/EnvironmentMapperTest.java`
  - Added `shouldOverwritePeriodicLogoutWhenDtoProvidesThem`: covers the
    DTO-provides-a-value overwrite path, including the boxed-`false` case
    (`Optional.ofNullable(Boolean.FALSE).orElse(...)` must yield FALSE, not fall
    through to the entity's TRUE) — the case most likely to regress. Dev's
    existing test only covered absent-field-preserves-entity.
- `src/test/java/com/vingame/bot/domain/session/service/SessionHistoryServiceTest.java`
  - Strengthened `shouldGenerateIdWhenNull` / `shouldGenerateIdWhenBlank`: the
    generated id must parse as a real `UUID.fromString(...)` (matching the
    sibling `GameService`/`BotGroupService` idiom), and the id is stamped on the
    entity that reaches `repository.save` (captured), not merely on the return.

## Coverage of the diff

- `EnvironmentMapper.java` (item 1) ← `EnvironmentMapperTest`
  - `toDTO`/`toEntity`/`updateEntityFromDTO` round-trip all three fields.
  - `useJwtAuth` primitive/boxed handling: entity→DTO auto-box, DTO→entity
    `Optional.ofNullable(...).orElse(false)`, partial-update flip.
  - Periodic-logout boxed-null carry-through: null in → null preserved (defaults
    test), absent-in-update → entity untouched, provided-in-update → overwrites
    (incl. false).
- `EnvironmentService.java` + `BotGroupService.java` (item 5) ← their `*Test`s
  - Pattern is unanchored `Pattern.quote(name)`, case-insensitive; new
    behavioral tests confirm a partial substring matches. Game left unchanged —
    `GameServiceTest` (already contains) still green in the full-suite run.
- `SessionHistoryService.java` (item 6) ← `SessionHistoryServiceTest`
  - null id → generated valid UUID; empty id → generated valid UUID; non-empty
    id → preserved untouched; id stamped before persist.
- `BotGroupBehaviorService.java` (item 2) ← `BotGroupBehaviorServiceTest`
  - All-create-failures with `botCount > 0` → runtime DEAD + persisted
    `targetStatus=DEAD`, no throw, runtime stays registered.
  - Boundary: `botCount == 0` → stays ACTIVE (guard half pinned).
  - `restart()` still throws on zero bots — covered by the pre-existing
    `BotGroupBehaviorServiceRestartTest` (`restart() throws IllegalStateException
    when zero bots ...`), confirming the two paths intentionally differ. That
    test passed in the full-suite run; QA did not modify it.

## Gaps

- **Item 2 — on-server zero-bot DEAD.** Only manifests under a real auth-gateway
  outage; fully covered at the unit level here. The optional staging check
  (misconfigured `apiGatewayUrl` → `GET .../status == "DEAD"`,
  `bot_creation_failures_total` non-zero) is left to the Releaser per the plan's
  Verification §; not a unit-testable path.
- **Items 3 & 4 (restart lifecycle, `RestExceptionHandler`)** are already shipped
  and out of scope for this bundle. Verified only by the standing suite passing
  (1478/0/0), which includes `BotGroupBehaviorServiceRestartTest` (item 3 guard)
  and the controller `isBadRequest()`/404 assertions (item 4 guard). No new tests
  added for them — verification-only per AD-1.
- **Filter regex is asserted against the client-side `java.util.regex` engine**,
  not MongoDB's `$regex`. `Pattern.quote` + the `"i"` flag is standard and used
  identically by the already-shipped `GameService`, so the mapping to Mongo
  semantics is unchanged from a known-good sibling; a Testcontainers Mongo
  round-trip was judged unnecessary for a one-line anchor removal.

## Failures (if any)

None. Full suite: `Tests run: 1478, Failures: 0, Errors: 0, Skipped: 0` —
BUILD SUCCESS.
