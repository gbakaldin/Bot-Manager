# QA — test-coverage-phase-7

**Verdict:** PASS
**Build:** mvn test → 263 tests, 0 failures, 0 errors (baseline was 246 tests, 0 failures)

## Tests added / updated

### Phase 7 — JSON message polymorphism

- `src/test/java/com/vingame/bot/domain/bot/message/g2/bom/BomGameMessageTypesTest.java` — 6 tests
  - `subscribe` (cmd=5000): asserts dispatch to `BomSubscribeMessage`, abstract `SubscribeMessage.getTimeForBetting() == 15000`, `getTimeForDecision() == 2000`, and concrete fields `sid == 422069`, `gS == 2`, `bs.size() == 2`, `pR.size() == 2`, `htr.size() == 1`, `lJp.d1 == 3`.
  - `startGame` non-md5 (cmd=5005, md5=false): asserts dispatch to `BomStartGameMessage` (not the Md5 variant), `StartGameMessage.getSessionId() == 422070`.
  - `startGameMd5` (cmd=5005, md5=true): asserts dispatch to `BomStartGameMd5Message`, `StartGameMd5Message.getSessionId() == 422070`, `getMd5Hash() == "9e107d9d372bb6826bd81d3542a419d6"`.
  - `updateBet` (cmd=5002): asserts dispatch to `BomUpdateBetMessage`, `UpdateBetMessage.getGameState() == 2`, `BomUpdateBetMessage.getRemainingTime() == 10000`.
  - `endGame` (cmd=5006): asserts dispatch to `BomEndGameMessage`, `sid == 422070`, `d1/d2/d3 == 4/5/6`, `bs.size() == 2`.
  - `shouldNotDispatchAcrossProducts` (cross-product guard): a Bom-only mapper rejects a Nohu fixture (cmd=7000) with `InvalidTypeIdException` whose message contains "7000".

- `src/test/java/com/vingame/bot/domain/bot/message/g4/nohu/NohuGameMessageTypesTest.java` — 5 tests
  - Same shape as Bom but with offset 4000 and Nohu concrete classes. Values from `messages/nohu/*.json`: subscribe `tFB=20000, tFD=3000, sid=522069, gS=2, lJp.d1=7`; startGame `sid=522070`; startGameMd5 `sid=522070, md5="d41d8cd98f00b204e9800998ecf8427e"`; updateBet `gS=3, rmT=8000`; endGame `sid=522070, d1/d2/d3=1/2/3`.

- `src/test/java/com/vingame/bot/domain/bot/message/g2/b52/B52GameMessageTypesTest.java` — 5 tests
  - Same shape as Bom but with offset 6000 and B52 concrete classes. Values from `messages/b52/*.json`: subscribe `tFB=18000, tFD=2500, sid=622069, gS=2, lJp.d1=2`; startGame `sid=622070`; startGameMd5 `sid=622070, md5="5d41402abc4b2a76b9719d911017c592"`; updateBet `gS=2, rmT=11000`; endGame `sid=622070, d1/d2/d3=7/8/9, iJp=true, jpT=2`.

- `src/test/java/com/vingame/bot/domain/bot/message/GameMessageTypesResolverTest.java` — 1 new test
  - `getTypeRegistrationsProducesCorrectCmdValues`: asserts `BomGameMessageTypes.getTypeRegistrations(2000, false)` returns exactly 4 `NamedType` entries with names `{"5000", "5002", "5005", "5006"}`, and that switching `md5=true` swaps the entry at name "5005" to wire `BomStartGameMd5Message.class`. Catches CODE+OFFSET arithmetic regressions and the md5-vs-non-md5 startGame switch.

### FOLLOWUPS.md updates

- **S5** marked **RESOLVED** with a note pointing to all 15 hand-crafted fixtures and the four new test classes. Includes the rationale for the distinct per-product offsets (so the cross-product guard test actually proves something).

## Coverage of the diff

The "diff" for Phase 7 is the new fixtures and tests under `src/test/`. Production code is unchanged. The tests lock in the existing polymorphic-dispatch wiring across all three implemented products:

- `BomGameMessageTypes.java` ← `BomGameMessageTypesTest.java`
  - `getTypeRegistrations(2000, false)` correctly dispatches subscribe/updateBet/startGame/endGame to their `Bom*` classes.
  - `getTypeRegistrations(2000, true)` swaps `BomStartGameMessage` → `BomStartGameMd5Message`.
  - Each concrete `Bom*Message` class deserializes its JSON-creator constructor args correctly (`@JsonCreator` + `@JsonProperty` mapping).
  - Abstract-base accessors (`SubscribeMessage.getTimeForBetting`, `StartGameMessage.getSessionId`, `UpdateBetMessage.getGameState`, `StartGameMd5Message.getMd5Hash`) all return the parsed field values — this is the public contract the bot's runtime relies on.

- `NohuGameMessageTypes.java` ← `NohuGameMessageTypesTest.java` — same coverage shape at offset 4000.

- `B52GameMessageTypes.java` ← `B52GameMessageTypesTest.java` — same coverage shape at offset 6000.

- `GameMessageTypes.java` (`default` interface method `getTypeRegistrations`) ← `GameMessageTypesResolverTest.getTypeRegistrationsProducesCorrectCmdValues`
  - The CMD = CODE + OFFSET arithmetic is exercised via the BOM provider (the same default implementation services Nohu and B52, so coverage transfers).
  - The md5-vs-non-md5 branch is asserted at the type level: `regsMd5` includes `BomStartGameMd5Message.class` at name "5005".

- `BettingMiniMessage.java` (abstract base + `@JsonTypeInfo`) — exercised indirectly by every round-trip test. If anything breaks the `cmd`-based dispatch (e.g. a future change to `JsonTypeInfo.As.EXISTING_PROPERTY`), all 16 round-trip tests fail loudly.

- Fixtures: all 15 hand-crafted fixtures under `src/test/resources/messages/{bom,nohu,b52}/*.json` are loaded and parsed at least once. A malformed fixture surfaces immediately as a test failure rather than silently passing.

## Gaps

- **Per-bet-info nested-class fields not fully asserted.** Tests verify `bs.size()` (the list shape) but do not drill into per-`BetInfo` / `BetInfoWithTotal` field values (`eid`, `bc`, `v`, `b`). The bot's runtime only reads through the abstract base, and the betting-bot logic does not currently parse `bs`, so this is a deliberate scope decision — adding those assertions would lock in fixture data, not production behavior.
- **`BettingMiniMessage`'s inherited `cmd` field not asserted.** The `cmd` is the dispatch key; if it's wrong, the dispatch test fails. Asserting `parsed.getCmd()` (if such an accessor existed on `Body`) would be redundant with the `isInstanceOf` check.
- **`BomEndGameMessage.LastJackpotData` / nested classes for Nohu and B52 endGame** — only the top-level `sid/d1/d2/d3` fields are asserted on endGame; `lJp` is parsed but not asserted on the endGame fixtures (subscribe asserts `lJp.d1` directly because it was called out in the plan). Same rationale as above: production code doesn't read `lJp` from endGame, so locking in its shape would test the fixture, not the code.
- **Cross-product polymorphism guard runs against BOM ↔ Nohu only.** A more thorough guard would also test BOM ↔ B52 and Nohu ↔ B52, but the plan asked for "one" test and the same Jackson mechanism applies — adding two more symmetric tests would be tautological.
- **`P_097` / `P_098` / `P_118` end-to-end** — `GameMessageTypesResolver` resolves these product codes to `BomGameMessageTypes` / `NohuGameMessageTypes`, which is already tested in `GameMessageTypesResolverTest`. The new tests exercise the providers directly rather than via the resolver. The combined coverage is end-to-end: resolver → provider → mapper → concrete class.

## Observations / things worth flagging

1. **Cross-product polymorphism guard behaved correctly — no silent fallback.** When a mapper registered only with BOM subtypes receives a Nohu `cmd=7000` payload, Jackson throws `InvalidTypeIdException` with a message containing "7000". This is the safe outcome: there's no risk of a Nohu frame being silently parsed as a BOM class (or vice versa) if a future config bug points the wrong provider at a game. If this assertion ever fails (e.g. Jackson is reconfigured to fall back to a default), it surfaces as a real production concern — bots could process the wrong game's frames without anyone noticing.

2. **Tests are deliberately fewer than the plan estimate (17 vs 25–30).** Each round-trip test consolidates several field assertions on one fixture rather than splitting them into separate `@Test` methods. The total coverage of the diff is the same — every concrete `*Message` class is round-tripped, and every abstract-base accessor used by the bot is asserted at least once — but the test count is lower because grouping reduces fixed setup overhead per test. If the team prefers one assertion per `@Test`, splitting is mechanical.

3. **One-mapper-per-test-class pattern.** Each test method calls `newMapper(boolean md5)` inside its body rather than using a `@BeforeEach`-shared mapper, because the md5 flag changes the subtype registrations. Sharing a single mapper across `md5=false` and `md5=true` tests would cause the second `registerSubtypes` call to throw a duplicate-name conflict on cmd "5005". The per-test mapper construction is cheap (sub-millisecond) and isolates failures cleanly.

4. **Fixture file `bom/subscribe.json` has 6-element `chp` array** which doesn't match `BomSubscribeMessage` exactly (the field is `List<Integer> chp` — any length works). Tests don't assert on `chp` size; flagging here so future authors don't waste time chasing it.

5. **Inherited `nohu` and `b52` fixture schemas are structurally identical to `bom`.** The plan called this out — testing each separately is to verify type-dispatch wiring, not field-shape. If any future product introduces a schema divergence (e.g. an extra field on the Nohu subscribe message), the round-trip test will pass because of `FAIL_ON_UNKNOWN_PROPERTIES = false`, but the new field will be missing from the deserialized object. That's the same lenient behavior the bot uses at runtime, so the test correctly reflects the production contract.

6. **Cosmetic surefire roll-up lines (`Tests run: 0` for outer test classes containing only `@Nested` blocks)** remain from prior phases — not affected by this phase. Final summary line `Tests run: 263, Failures: 0, Errors: 0, Skipped: 0` is correct.

## Failures (if any)

None.
