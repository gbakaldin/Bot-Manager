# Code Review — SLOT_MACHINE_BOT

Branch: feat/martingale-strategies
Reviewed diff: `git diff main..feat/martingale-strategies` (slot-related files only)

## Re-review (commit `9eea006`)

Re-reviewed scope: ONLY whether the two delegated findings (bug #1, smell #2) are
correctly resolved by commit `9eea006`. Remaining smells #3/#4 and the style note
are intentionally deferred for v1 and are NOT re-opened here.

### Re-review verdict

PASS — both findings resolved, no regression or new inconsistency introduced.

### Finding #1 (bug — bet debit & metric consistency): RESOLVED

The total stake `chosenBet * numLines` is now consistent across all three points:

- **Gate** — `spinCondition()` (`SlotMachineBot.java:277`): `cost = chosenBet * numLines`.
- **Debit** — `spin()` (`SlotMachineBot.java:316-317`): `totalStake = amount * numLines; creditBalance(totalStake)`. `Bot.creditBalance` debits via `addAndGet(-amount)` and accumulates `totalBetAmount += totalStake`, so the wallet drops by the full `b * numLines`.
- **Metric** — `onSpinResult()` (`SlotMachineBot.java:236-237`): `totalStake = bt.betAmountFor(...) * numLines; incBetsPlaced(betCountFor, totalStake)`.

All three evaluate to `500 * 25 = 12500` for the captured fixture. `betCountFor` stays `1`.

`betAmountFor()` (`SlotSpinResultMessage.java:110`) still returns the per-line `b`,
now explicitly documented as per-line (Javadoc at `:98-108`) with a warning not to
treat it as the total. Because `numLines` is not carried on the 1302 result frame,
the bot correctly multiplies by its own server-sourced `numLines` at result time
rather than baking it into the message accessor — the right separation: the message
object reports only what is on the wire, the bot supplies the line count it learned
from the 1300 response.

Winnings are untouched and NOT scaled: `winningsFor()` still returns
`sum(wls[].crd)` (`SlotSpinResultMessage.java:86-95`), credited as-is via
`expectedCurrentBalance.addAndGet(winnings)` only when `> 0` (`SlotMachineBot.java:226-229`).
No accidental `* numLines` leaked into the winnings path.

The request still carries the per-line `amount`/`b` (`SlotMachineBot.java:326`),
so the server-side line multiplication is unchanged.

### Finding #2 (smell — onSubscribe ordering): RESOLVED

`markConnectionAuthenticated()` is now called unconditionally at the top of
`onSubscribe()` (`SlotMachineBot.java:179`), before the degenerate-1300 validation
guard (`:184-191`). A 1300 with no winlines / empty bet values now WARNs and returns
(`:188-190`) AFTER the connection has been marked authenticated, so the bot can no
longer wedge in `AUTHENTICATING_CONNECTION` on a live socket. The spin gate still
holds it shut because `numLines`/`allowedBetValues` stay unset (`spinCondition`
`:268-270`). Matches `BettingMiniGameBot.onSubscribe` ordering.

### Regression / consistency check on the updated tests

No regression. The test changes match the corrected semantics:

- `SlotMachineBotSpinAccountingTest`: `incBetsPlaced(1, 12_500L)` on both winning
  and losing spins; winnings still `incBotWinnings(6000L)`; balance delta on result
  is `+6000` (credit only, debit happens in `spin()`).
- `SlotMachineBotSpinStreamTest`: per-spin debit asserted at `b * NUM_LINES = 12_500`,
  `totalBetAmount` accumulates the total stake, and the closed-form invariant
  `START_BALANCE - Σstaked + Σwon` still holds with `staked = perLine * NUM_LINES`.
  Winnings stay `6000` per spin (unscaled). The RANDOM seeded sequence is unchanged
  (the fix touched only the staked-amount accounting, not the RNG draw), so the
  deterministic `containsExactly(...)` pin remains valid.
- `SlotMachineBotSubscribeTest`: new `onSubscribe_missingConfig_authenticatesButDoesNotCapture`
  pins authenticate-first ordering (status `CONNECTION_AUTHENTICATED` even on a
  degenerate 1300, config not captured, spin gate stays closed). The happy-path test
  still asserts `CONNECTION_AUTHENTICATED` and gate-opens.

No new inconsistency: the winnings invariant is intact, the debit/gate/metric trio
agrees, and the per-line vs total-stake boundary is documented at the one place it
matters (`SlotSpinResultMessage.betAmountFor`).

---

## Verdict (original review, retained below for history)

CHANGES_REQUESTED

PASS = no `bug` or `security` findings.
CHANGES_REQUESTED = at least one `bug` or `security` finding.

## Findings

### [bug] Per-spin debit (`b`) is inconsistent with the `b * numLines` balance gate — likely understates spend by `numLines`x  — RESOLVED in `9eea006` (see re-review above)

`src/main/java/com/vingame/bot/domain/bot/core/SlotMachineBot.java:264` (gate) and `:299` (debit)

The balance gate reserved `chosenBet * numLines` while the actual debit on send was only the per-line value (`creditBalance(amount)`), and on result the bet-total metric recorded `betAmountFor = b` (per-line). These three numbers must agree. The fix (commit `9eea006`) makes debit, gate, and metric all equal `b * numLines`, keeps the request carrying per-line `b`, and leaves winnings as gross `sum(wls[].crd)`.

### [smell] `onSubscribe` marks the connection authenticated only after a passing validation — a degenerate 1300 leaves the bot stuck pre-AUTHENTICATED forever  — RESOLVED in `9eea006` (see re-review above)

`src/main/java/com/vingame/bot/domain/bot/core/SlotMachineBot.java:169-190`

The handler returned early when `lines <= 0 || betValues empty` and never called `markConnectionAuthenticated()`, so a degenerate 1300 wedged the bot in `AUTHENTICATING_CONNECTION` on a live socket. The fix reorders `markConnectionAuthenticated()` ahead of the validation guard to match `BettingMiniGameBot.onSubscribe`.

### [smell] `chooseBet()` is evaluated twice per accepted tick and burns an RNG draw on rejected ticks (RANDOM strategy)  — DEFERRED for v1

`src/main/java/com/vingame/bot/domain/bot/core/SlotMachineBot.java:262, 270, 285-295`

`spinCondition()` parks the chosen bet and `spin()` pops it (one strategy call on the common path). Two secondary effects for RANDOM: (1) a rejected tick discards the RNG draw, conditioning the realized distribution on affordability; (2) the `beforeReconnect` race fallback takes a second, different RNG draw, silently changing the staked amount versus what the cost gate validated. Acceptable for v1; a one-line comment that the parked value is the source of truth would prevent a future reader from "fixing" the gate to re-derive. No behavior change required.

### [smell] `SlotMessageTypesImpl.spinResultType()` / `subscribeResponseType()` accessors are dead beyond the default registration  — DEFERRED for v1

`src/main/java/com/vingame/bot/domain/bot/message/slot/SlotMessageTypesImpl.java:13-21`, `SlotMessageTypes.java:29-34`

The two typed accessors exist only to feed `getTypeRegistrations()`. Nothing in production reads them directly. Mirrors the betting `GameMessageTypes` accessor style, so defensible as convention; more surface than the slot path needs. Not blocking.

### [style] `Js` / `J` field names violate Java naming but are correct here  — DEFERRED for v1

`src/main/java/com/vingame/bot/domain/bot/message/slot/SlotSubscribeResponse.java:30, 86` and `SlotSpinResultMessage.java:41`

Upper-camel/upper-case field names (`Js`, `J`, `mX`, `hFS`, `hMG`, `iJ`) read as constants/types in Java. They are dictated by the wire protocol and the `@JsonProperty` bindings make them unambiguous to Jackson. The derived accessors (`numLines()`, `allowedBetValues()`) insulate the bot from this. Optional only.

## Notes

- Jackson polymorphism is set up correctly and consistently with `BettingMiniMessage`: `@JsonTypeInfo(use=NAME, include=EXISTING_PROPERTY, property="cmd", visible=true)` on `SlotMessage`, `NamedType` registration against the literal `"1300"`/`"1302"` strings, and `FAIL_ON_UNKNOWN_PROPERTIES=false` on the per-bot mapper. The array framing `[5,{...}]` is left to the library exactly as the plan intended. `@JsonCreator` constructors are complete and null-tolerant where it matters (`winningsFor` null-guards `wls`; `allowedBetValues()` null-guards `Js`; `numLines()` null-guards `ls`).
- Concurrency review is clean: `numLines`/`allowedBetValues` are `volatile`; `spinInFlight` is an `AtomicBoolean`; `pendingBet` is an `AtomicReference<Optional<Long>>` using `getAndSet`; `expectedCurrentBalance` is the inherited `AtomicLong`. The set/clear of `spinInFlight` is correct across all three paths (set in `spin()`, cleared in `onSpinResult()` including the foreign-gid early return, and in `beforeReconnect()`). No blocking calls inside the netty handlers.
- MDC wrapping is applied to every scenario callback and the OutputPrinter gets `mdcSnapshot` — matches the betting bot.
- Logging levels comply with CLAUDE.md: one INFO init line, per-spin/per-result detail at DEBUG, misconfigured-1300 at WARN, `onStart` failure at ERROR + rethrow. No token logging.
- Resource leaks: none introduced. The slot bot carries no scheduler/watchdog/executor (AD-5); reuses the inherited WS lifecycle.
- Bootstrap order: `SlotStrategyFactory` discovery runs in `@PostConstruct init()` and `BotFactory` invokes `create(...)` lazily at bot-build time, so the factory is populated before first use.
- `BotFactory` wiring is correct: betting-mini resolution is inside its own switch branch; `case SLOT` builds a real `SlotMachineBot`; `TAI_XIU/CARD_GAME/UP_DOWN` still throw.
- The factory-null inline fallback in `initializeSubclass` (`new FixedBetStrategy()`) is a test-only seam, documented at the call site.
