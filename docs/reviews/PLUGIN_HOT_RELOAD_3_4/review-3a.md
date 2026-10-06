# Code Review — PLUGIN_HOT_RELOAD_3_4, Phase 3a

Branch: feature/plugin-hot-reload-3-4
Reviewed diff: `git diff 30b426a..HEAD` (branch point = `feature/aviator-bot` tip; commits
`8a159b7..5f4f633`, the 3a code being `a9df393..5f4f633`)

## Verdict

PASS

No `bug` or `security` findings. The two smells and two style findings are all stale
comments or a dead dependency left behind by the moves. None of them changes behaviour.

## Findings

### [smell] `GameRequestFactory`'s reason for existing no longer holds
`bot-api/src/main/java/com/vingame/bot/domain/bot/message/request/GameRequestFactory.java:10-17`

The section "Why it is a separate interface and not a method on `GameMessageTypes`" says
that `GameRequest` and every concrete request body live in **bot-messages**, so a
`default GameRequest requestFor(...)` on the bot-api interface "does not compile". Since
`c003237`, `GameRequest`, `Request`, `Bet` and this interface all live in **bot-api**, next
to `GameMessageTypes`. Only `RikStock*`/`ZicZac*` are still in bot-messages. The
dependency-direction argument is now false. The second argument ("`instanceof` is the
right shape anyway") still stands on its own.

Behaviour is not affected. But this is the javadoc someone will read when deciding whether
to fold the capability into `GameMessageTypes`, and it gives them a constraint that no
longer exists. Fix: delete or rewrite the first `<h2>` section so the interface rests on
the capability-pattern argument alone. Do not move code.

### [smell] Module poms still list dependencies for classes that moved out
`bot-strategies/pom.xml` (`jakarta.annotation-api`, commented "@PostConstruct on the factories."),
`bot-messages/pom.xml` (the `spring-context` and `jackson-annotations` comments)

- **bot-strategies:** `jakarta.annotation-api` exists only for the factories'
  `@PostConstruct`. Both factories are now in bot-engine, and nothing under
  `bot-strategies/src/main` imports `jakarta.annotation`. The dependency is dead and its
  comment is wrong. That matters more than usual here: Phase 3b is about to turn these
  poms into the plugin-artifact contract (D-6 scopes and enforcer). A leftover compile
  dependency is the kind of thing that gets copied into a plugin pom template.
- **bot-messages:** the `spring-context` comment justifies the dependency with
  "`@Component` on the message-types providers **and on `MessageTypesRegistry`**". The
  jackson comment cites "the registry round-trip tests", which also moved to bot-engine in
  `1dd4d7b`. The dependencies are still needed (the providers are `@Component`, the
  messages use Jackson annotations). Only the comments are stale.

Fix: drop `jakarta.annotation-api` from bot-strategies (it compiles without it, since no
source references it), and trim the two bot-messages comments.

### [style] Test javadocs still point at the registries' old homes
`bot-app/src/test/java/com/vingame/bot/infrastructure/logging/MessageTypesRegistryStartupLogTest.java:44-51`,
`bot-app/src/test/java/com/vingame/bot/domain/bot/service/TestMessageTypes.java:24`

- `MessageTypesRegistryStartupLogTest` says the factories' startup line is one "which
  `PerBotInfoLogGuardTest` exempts by name". `bf61dc8` removed those exemptions; the list
  is now `List.of()`. It also argues that bot-messages "is also the commit after which a
  per-product or per-bot INFO line can be added there". After 3a, bot-messages has no
  logger: the registry was its only `@Slf4j` class.
- `TestMessageTypes` cites `MessageTypesRegistryTest` / `MessageTypesCoverageTest` as
  "(bot-messages)". They are in bot-engine now.

Fix: change the wording only.

### [style] `HasRefund` calls itself a "Marker"
`bot-api/src/main/java/com/vingame/bot/domain/bot/message/HasRefund.java:4`

A marker interface has no members, and this one declares `refundFor(String)`. Its siblings
(`HasBotWinnings` and others) are capability interfaces, and the class's own second
paragraph uses the word "capability". Fix: use "Capability" in the first sentence.

## Notes

**D-1 behaviour identity: `HasRefund` vs `getGR()`.** The behaviour is identical.
- `TaiXiuEndGameMessage.refundFor` returns the raw `long gR` field, which is what
  Lombok's `getGR()` returned. It is not floored, so a negative wire value passes through
  exactly as before.
- `TaiXiuEndGameMessage` is the only `HasRefund` implementor and has no subclasses.
- All three Tai Xiu providers (`MiniGame`, `Jackpot`, `Win79`) return
  `TaiXiuEndGameMessage.class` as their end type. So the set of messages that get a
  non-zero refund in `TaiXiuGameBot.balanceCreditFor` is exactly the old
  `instanceof TaiXiuEndGameMessage` set.
- The `userName` argument comes from `getUserName()`, the same identifier
  `HasBotWinnings.winningsFor` receives three calls earlier.
- P_114's missing `gR` still deserializes to `0`, through the same creator parameter.
- `refundFor(String)` takes a parameter, so Jackson does not treat it as a property and
  the serialized shape is unchanged.

The capability is honoured only in `TaiXiuGameBot`'s override. A future BETTING_MINI end
message that implements `HasRefund` would be silently ignored by
`BettingMiniGameBot.balanceCreditFor`. That matches today's behaviour and is not a finding,
but if a second implementor appears, the place to widen is the base class.

**D-4: can a production bot hit the new `IllegalStateException`?** No. `BotFactory.createBot`
is the only production construction site for `BettingMiniGameBot`, `SlotMachineBot` and
`TaiXiuGameBot`. I grepped `new …Bot(` and `…Bot.builder(` across all `src/main`:
- Each of the three switch arms sets its factory before `initialize()`, from `final`
  constructor-injected fields.
- `CashoutBot` and `CrashBot` extend `Bot` directly, have no strategy field, and never
  reach either throw.
- `UP_DOWN` and `CARD_GAME` throw "not yet implemented" before any bot is built.
- `TaiXiuGameBot.initializeSubclass` calls `super`, so it gets the betting check, and its
  arm wires the factory.
- On restart and re-auth the same instance is kept, and the `@Setter` field persists.

Both throws come before the watchdog executor is created and before any socket is
opened, so a mis-wired fixture leaks nothing.

**Spring scan reach.** `Starter` is a bare `@SpringBootApplication` in `com.vingame.bot`.
The registries' FQNs did not change and bot-app depends on bot-engine, so the scan finds
them exactly as before. No bean is double-registered: each class exists in one jar only,
and `git mv` left no copy behind. Confirmed by running `ApplicationContextLoadsTest`
(including the exact-set registry checks), `StrategyControllerTest` (narrowed
`@ComponentScan`) and `PerBotInfoLogGuardTest` after an offline `mvn test-compile` of the
whole reactor: 19/19 green. `TestStrategyFactories`' scan of
`com.vingame.bot.domain.bot.strategy` on the bot-engine test classpath picks up only the
two factories and the eleven strategy beans. `StrategyCatalog` is in bot-app and is not
on that classpath.

**Split packages.** 3a does not add a new split package, but it adds a module to two
existing ones and swaps one:
- `domain.bot.strategy` and `.strategy.slot` go from api+strategies to
  api+strategies+engine.
- `domain.bot.message` goes from api+messages to api+engine; bot-messages now holds only
  subpackages of it.
- `.message.request` and `.message.slot` stay split api+messages, with most of their
  classes now on the api side.

This is what the plan's split-package paragraph and D-13 expect. All three registries
reference only `public` api types (`StrategyImpl`, `SlotStrategyImpl`, `MessageTypesImpl`,
`SlotStrategy`, `BettingStrategy`), so the moves create no package-private access across
modules that would turn into an `IllegalAccessError` once 4b puts the plugins in a child
loader.

**bot-api's new dependency surface.** None added. The 19 moved classes import only
Jackson annotations, Lombok, `websocket-parser-core`'s `ActionRequestMessage`/`Body`, and
bot-api's own `Game`, `SlotMessageTypes`, `TaiXiuMessageTypes`, `HasBotWinnings` and
`HasBetTotals`. bot-api already declared all of these, and `bot-api/pom.xml` is untouched.

**"The engine names no concrete class."** The new CLAUDE.md line says this. It is true
today: no `com.vingame.bot.domain.bot.message.{g*,taixiu.*,…}` or
`strategy.{martingale,RandomBehavior…,slot.FixedBet…}` reference remains anywhere in
`bot-engine/src/main`, including in javadoc. But it is not yet enforced: bot-engine
still has compile scope on bot-strategies and bot-messages until 3b's D-6 scopes and the
enforcer land. Until then, an import added in review would compile.

**`SlotStrategyFactory.registeredKeys()` snapshot.** Correct, and it mirrors the betting
twin exactly (it uses `LinkedHashSet`, not `Set.copyOf`, for the reason the javadoc
gives).

**Good pattern.** Each move commit is a pure rename with 100% similarity. The one content
change to a moved file (`SlotStrategyFactory`) is in a separate commit (`a9df393`) made
*before* the move, so `git log --follow` reads cleanly.
