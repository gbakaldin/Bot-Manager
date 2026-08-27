# Code Review — PLUGIN_HOT_RELOAD Phase 2c (message-types registry)

Branch: `feature/plugin-hot-reload-2c` @ `65672cb`
Reviewed diff: `git diff c3fac8a..feature/plugin-hot-reload-2c` (5 commits, 28 files,
+1126/−244)

Base `c3fac8a` is byte-identical to `feature/plugin-hot-reload`, so this is a clean
standalone evaluation of 2c against the integration branch.

Verified locally (inspection only, nothing pushed):

```
mvn -pl bot-api,bot-messages,bot-engine -am test        → BUILD SUCCESS, 426 tests, 0 failures
mvn -pl bot-app test -Dtest=ApplicationContextLoadsTest,BotFactory*Test,PerBotInfoLogGuardTest
                                                        → BUILD SUCCESS, 17 tests, 0 failures
```

## Verdict

PASS

No `bug` and no `security` findings. Six advisory findings follow, ranked. Three of
them (F1, F3, F4) are **forward-looking against step 5** rather than defects in this
diff — they are recorded here because the reviewer was asked to judge step-5 fitness
and because the same shapes were already flagged in `review-2a.md`; leaving them
implicit twice is how they become load-bearing by accident.

## Findings

### [smell] F1 — Three separate `final` tables cannot be swapped atomically when step 5 arrives
`bot-messages/src/main/java/com/vingame/bot/domain/bot/message/MessageTypesRegistry.java:59-70`

```java
private final Map<String, GameMessageTypes> bettingMiniByProduct;
private final Map<String, TaiXiuMessageTypes> taiXiuByProduct;
private final SlotMessageTypes slotProvider;
```

**Correct today, and correct in a stronger sense than the strategy factories.** All
three are assigned once in the constructor and the two maps are wrapped in
`Collections.unmodifiableMap`. `final`-field semantics give safe publication, so the
lock-free reads from bot-creation virtual threads are sound with no `volatile` and no
memory barrier — there is nothing to tear.

Compared to `BettingStrategyFactory` (`bot-strategies/.../BettingStrategyFactory.java:60`,
a bare `new LinkedHashMap<>()` instance field populated in `@PostConstruct` and handed
out only through an `unmodifiableSet` view of its key set), this registry is **better
positioned on the mutation axis**: there is no mutator at all, so step 5 has to *add*
one deliberately rather than relax an existing one. `review-2a.md`'s warning — "do not
add a `register()` that mutates the `LinkedHashMap` in place" — is structurally harder
to violate here.

It is **slightly worse positioned on the atomic-swap axis**, and that is the finding.
The 2a recommendation was "swap the whole map behind a `volatile`". There is no *whole
map* here; there are three fields. A reload that assigns them one at a time publishes
an interleaved state in which `bettingMiniByProduct` is v2 while `taiXiuByProduct` is
still v1 — a bot group started in that window gets a mixed-version wiring, which is
exactly the failure mode a versioned plugin system exists to prevent, and it is
invisible in logs.

Fix shape, cheap to do now while there is one writer and it is a constructor: fold the
three into one private immutable carrier and hold a single reference.

```java
private record Tables(Map<String, GameMessageTypes> bettingMini,
                      Map<String, TaiXiuMessageTypes> taiXiu,
                      SlotMessageTypes slot) {}

private final Tables tables;   // becomes `private volatile Tables tables;` in step 5
```

Every lookup then reads the field exactly once into a local, and step 5's reload is one
volatile write of a fully-built `Tables`. Doing it now costs an hour and no behaviour;
doing it in step 5 means touching every accessor under time pressure.

### [smell] F2 — A missing `@MessageTypesImpl` is a soft skip while every other misconfiguration is a hard refresh failure
`MessageTypesRegistry.java:225-235` (soft) vs `:180-195` and `:237-244` (hard)

Inside one class there are two opposite postures for four sibling mistakes:

| Mistake | Outcome |
|---|---|
| `gameType` disagrees with the contract interface | `IllegalStateException` → context refresh fails |
| non-SLOT provider declares `products = {}` | `IllegalStateException` → context refresh fails |
| SLOT provider declares products | `IllegalStateException` → context refresh fails |
| **`@MessageTypesImpl` missing entirely** | `log.warn(...)`, `continue`, app starts |

The soft one is the **most likely mistake** (someone copies a provider, remembers
`@Component`, forgets the second annotation) and has the **worst symptom**. The app
starts clean. Nothing is wrong until a bot group for that brand is started, at which
point an operator sees

```
GameMessageTypes not yet implemented for product code: 116. Please create a
GameMessageTypes implementation for this product.
```

…for a brand that demonstrably *is* implemented and has been in production for months.
That message actively points the reader away from the cause. The one WARN that would
explain it was emitted at startup, hours or days earlier, and is one line in the boot
log.

The class javadoc justifies this as "same posture as `BettingStrategyFactory`", and
that is true. But `BettingStrategyFactory` has no *hard* branch to be inconsistent
with — this class does, three of them, and the annotation the soft branch tolerates is
the one all three hard branches read from. Recommend making it a hard failure too:

```java
if (annotation == null) {
    throw new IllegalStateException(
            provider.getClass().getName() + " is a discovered message-types bean but "
                    + "carries no @MessageTypesImpl — the registry has no key for it.");
}
```

If the soft posture is intentional (e.g. to tolerate a future non-annotated bean shape),
say so in the javadoc rather than pointing at a class whose situation differs.

### [smell] F3 — `annotationOf` reads the annotation off `provider.getClass()`, which is proxy- and classloader-fragile
`MessageTypesRegistry.java:226`

```java
MessageTypesImpl annotation = provider.getClass().getAnnotation(MessageTypesImpl.class);
```

**Not a live defect.** I checked: the codebase has no `spring-boot-starter-aop`, no
`aspectjweaver`, no `@EnableAspectJAutoProxy`, no `@Transactional`/`@Async`/`@Validated`,
and no `TimedAspect`/`CountedAspect` bean. Nothing proxies these beans today, and
`ApplicationContextLoadsTest.messageTypesRegistryIsFullyPopulated` would go red if
anything started to.

Two future ways it breaks, both landing in F2's confusing failure:

1. **Proxying.** All six providers implement an interface, so the first piece of advice
   applied to any of them (a `@Timed` on a provider, a `@Validated`, an
   `@EnableAspectJAutoProxy` added for something else entirely) yields a JDK dynamic
   proxy whose `getClass()` is `$Proxy42` — no `@MessageTypesImpl`, silent skip, brand
   unroutable. `@MessageTypesImpl` is not `@Inherited`, and `@Inherited` would not help
   an interface-based proxy anyway.
2. **Step 5 classloaders.** If a plugin classloader loads its own copy of
   `MessageTypesImpl`, `getAnnotation(engineCopy.class)` returns `null` for a provider
   that is correctly annotated against its own copy. Same silent skip.

Fix shape for (1) — Spring already ships the two helpers:

```java
MessageTypesImpl annotation = AnnotationUtils.findAnnotation(
        AopProxyUtils.ultimateTargetClass(provider), MessageTypesImpl.class);
```

(2) is not fixable here — it is a parent-first delegation requirement on the plugin
classloader (`com.vingame.bot.domain.bot.message.MessageTypesImpl` must resolve to the
engine's copy). Worth recording as a step-5 constraint while the reason is fresh.

Note `BettingStrategyFactory` has the identical line and the identical exposure; a fix
should land in both or neither.

### [smell] F4 — The `(gameType, product)` key has no version dimension, and a duplicate takes the engine down
`MessageTypesRegistry.java:186-195`

```java
T existing = registry.put(product, provider);
if (existing != null) {
    throw new IllegalStateException("Duplicate @MessageTypesImpl(...)");
}
```

Thrown from a `@Component` constructor, so it fails context refresh. **Right for our
keys** — two built-in providers claiming `"116"` for `BETTING_MINI` is a programming
error and the build should not produce a startable artifact. The
`(gameType, product)` two-table split is also the right call and is well argued: 116 is
claimed by `TipGameMessageTypes` for BETTING_MINI and `MiniGameTaiXiuMessageTypes` for
TAI_XIU without colliding, and `MessageTypesRegistryTest.taiXiuAndBettingMiniShareAProductCodeWithoutColliding`
pins it.

Two step-5 consequences, restating `review-2a.md` because the mechanism is now
duplicated in a second registry and the same policy decision now has two call sites:

- **Two live plugin versions are not expressible.** The end goal is v1 and v2 of a
  brand's plugin serving different groups simultaneously. Both versions' providers
  claim `"116"`; under this key they are a duplicate and the container refuses to
  refresh. Step 5 needs either a version dimension in the key or a registry instance
  per plugin version in a child context, with `BotFactory` selecting on the group's
  pinned version. Neither is required by 2c and neither is precluded by it — but the
  `MessageTypesRegistry` singleton-in-the-root-context shape is the thing that will
  have to change, and it is worth knowing that before step 5 is designed around it.
- **A third-party plugin colliding with a built-in kills the engine.** A bad plugin
  should be rejected and logged; it should not stop the other nine brands from
  starting. Policy decision needed before plugins can register, not after.

### [smell] F5 — `B52GameMessageTypes` staying unannotated is right, but the javadoc characterises the situation too weakly
`bot-messages/src/main/java/com/vingame/bot/domain/bot/message/g2/b52/B52GameMessageTypes.java:10-21`

**The decision is correct and I would have made it the same way.** A refactor must not
change which classes a product's frames parse with; the old switch mapped
`P_098 -> new BomGameMessageTypes()`, so 2c must too, and
`MessageTypesRegistryTest.b52IsNotRegistered` pins it in both directions (not a
discovered bean, and no registered product resolves to it). That is exactly the right
guard.

What I disagree with is the javadoc's framing — "this class was reachable only from
tests" — which reads as *vestigial*. The evidence says the opposite: **098 looks
mis-wired, and this change preserves a latent bug.**

- `ProductCode.P_098("098", "B52", "bc114098", null, null)` — 098 *is* the B52 brand,
  with its own appId, not an alias of 097/BOM.
- `bot-app/.../infrastructure/auth/AuthStrategyFactory.java:54-60` gives P_098 its own
  `B52LoginRequest`, deliberately distinct from P_097's `BomLoginRequest` at `:47-53`.
  So the **auth layer already treats 098 as its own brand** while the message layer
  sends it to BOM's classes.
- A complete parallel message family exists and is actively tested:
  `B52SubscribeMessage`, `B52StartGameMessage`, `B52StartGameMd5Message`,
  `B52UpdateBetMessage`, `B52EndGameMessage`, exercised by `HasCrowdBetsTest`,
  `HasJackpotPoolTest` and `EndGameMessageSessionIdTest`. Someone captured 098's frames
  and wrote five classes against them.

That leaves exactly two possibilities, and both deserve to be written down:

1. B52's wire shapes are identical to BOM's, in which case the whole `g2/b52` message
   family is duplicate dead code carrying its own test suite; or
2. 098's bots have been parsing B52 frames with BOM classes for as long as 098 has been
   wired — the same *class* of silent-misparse defect as the BOM-winnings gap already
   on record (`BomEndGameMessage` implements `HasJackpot` but not `HasBotWinnings`, so
   BOM payout/RTP always read 0).

Neither is 2c's job to resolve. Recommended fix is documentation only: replace
"reachable only from tests" with the two-possibility framing above, and raise it as an
explicit open item. Pre-existing, **not introduced by this diff**.

### [style] F6 — Field javadoc promises a `providers present:` tail the lookup message does not have
`MessageTypesRegistry.java:52-58` vs `:163-168`

> `LinkedHashMap` keeps iteration deterministic within a JVM run so the startup line
> and the **`providers present:` tail of a lookup failure** do not shuffle between runs.

There is no such tail. `lookup()` throws
`"<Contract> not yet implemented for product code: <code>. Please create a <Contract> implementation for this product."`
and never renders the key set. The `providers present:` tail belongs to
`BettingStrategyFactory.create` — a copy-paste that survived.

The determinism argument still holds for the startup INFO line, which *does* render both
key sets, so only the second half of the sentence is wrong. **Fix the sentence, not the
message**: AD-20 pins that string byte-for-byte and `MessageTypesErrorTextTest` asserts
it as a literal, so adding the tail would be a deliberate, separate decision.

### [style] F7 — `BotFactory`'s ninth argument is proportionate; the boilerplate around it is not
`bot-app/src/main/java/com/vingame/bot/domain/bot/service/BotFactory.java:76-96`

**This is a preference, not a defect.** Answering the question directly: the injection
*is* proportionate. `BotFactory` is the only place that knows a bot's `GameType`, and
the provider must be chosen per branch of that switch — resolving it anywhere else
means either resolving betting-mini eagerly (which throws for a SLOT game on a product
with no betting-mini provider, the exact bug the `AD-4` comment at `:155-159` records)
or pushing registry knowledge into the bot classes. There is no smaller *dependency*
seam. Nine is the honest count.

The boilerplate is a different question: nine final fields, a nine-parameter
constructor and nine assignments, ~24 lines to say "hold these". Lombok
`@RequiredArgsConstructor` is already the idiom in this codebase
(`config/client/EnvironmentClients`, `infrastructure/client/GameMsClient`,
`domain/bot/auth/*LoginRequest`) and would collapse it to the field declarations, which
is where the explanatory comments actually want to live. Spring resolves a single
constructor without `@Autowired`, and Lombok generates exactly one — so this does not
reintroduce the two-ambiguous-constructors shape that crash-looped `VipTalkClient`.

If a tenth argument arrives, the seam that actually reduces the count is bundling
`BettingStrategyFactory` + `SlotStrategyFactory` + `MessageTypesRegistry` into one
`BotWiring` holder — they are three registries consumed by three arms of one switch and
they travel together. Premature at nine; the right move at ten.

## Notes

**Things this diff does well, called out because they are non-obvious and easy to lose
in a later pass.**

- **The null-safe `productKey(env)` fix is complete and correctly motivated.**
  `Environment.productCode` is a plain nullable Lombok field. The literal
  `env.getProductCode().getCode()` would have converted a documented
  `IllegalArgumentException("ProductCode cannot be null")` into an NPE at a different
  frame, and `BotFactory:232-235` forwards the null so the registry still produces the
  original message. Both product-keyed lookups (`:164` BETTING_MINI, `:185` TAI_XIU) go
  through the helper; `slot()` takes no product, so all three call sites are covered.
  `env` itself cannot be null here — `env.resolveZoneName(game)` dereferences it 40
  lines earlier. `MessageTypesErrorTextTest.nullTextIsUnchanged` pins both messages.
  Verified complete.

- **The three disjoint lookups are preserved exactly.** `bettingMini(String)` →
  `GameMessageTypes`, `slot()` → `SlotMessageTypes` (no argument), `taiXiu(String)` →
  `TaiXiuMessageTypes`. Neither `SlotMessageTypes` nor `TaiXiuMessageTypes` extends
  `GameMessageTypes`, so the three constructor-injected `List<T>`s are genuinely
  disjoint and no bean lands in two tables. Nothing was collapsed into an `Object`
  return; SLOT_MACHINE_BOT AD-4 and TAI_XIU_BOT AD-3/AD-4 survive intact, and the
  reasoning is recorded on the class rather than left for the next reader to
  reconstruct. Confirmed.

- **The `gameType()` cross-check is sound.** It is genuinely redundant with the
  interface for the three types that exist — which is the point: it turns a copy-paste
  (`@MessageTypesImpl(gameType = SLOT)` left on a class changed to implement
  `GameMessageTypes`) from a silent mis-file into a startup failure. It cannot produce
  a *wrong* verdict: `requireGameType` is only ever called with the expected type of the
  list the bean arrived in, and no provider implements two of the three contracts.

  Two ways it can produce a *confusing* failure, neither serious:
  - A class implementing two of the three contracts would appear in two lists and one
    check would necessarily throw, with a message naming only one contract. No such
    class exists and one would be a design error anyway.
  - `GameType` has five constants. A future `CARD_GAME`, `UP_DOWN` or crash-game
    provider that wants to reuse the `GameMessageTypes` shape cannot: it would be
    injected into `bettingMiniProviders` and rejected with
    *"declares gameType = UP_DOWN but the bean implements the BETTING_MINI contract"*.
    The message is accurate; the constraint is real. So the "adding a product is a pure
    addition" property holds **within** the three wired game types and not across a
    fourth — a fourth needs a new interface, a new list and a new lookup, i.e. a central
    edit. Worth knowing; `MessageTypesCoverageTest.everyGameTypeIsClassified` already
    forces the decision to be made rather than defaulted, which is the right guard.

  Both failures arrive as a `BeanCreationException` chain at refresh rather than a bare
  message, so the operator has a stack to read — acceptable given the releaser's
  `docker logs` smoke test, and identical to `BettingStrategyFactory`'s existing
  posture.

- **Logging is compliant.** One `log.info` per JVM in the constructor, five `{}`
  placeholders against five arguments, no token or credential material, rate independent
  of bot count and round rate. This is squarely tier 1 ("application startup") and
  mirrors the strategy factories' "registered N strategies" line, including the
  `slotProvider == null ? "none"` guard so the line cannot NPE while reporting a broken
  registry. `PerBotInfoLogGuardTest` passes.

- **The provider-singleton change is safe and was checked, not assumed.** Providers were
  `new`-ed per bot before and are now one shared instance. I verified all six classes
  have zero instance fields — they are pure tables of class literals, so there is no
  shared mutable state and no `volatile` question. `MessageTypesRegistryTest`
  additionally pins `isSameAs` for both the 097/098 pair and repeated `slot()` calls,
  which is the right way to make the change visible rather than incidental.

- **`spring-context` in `bot-messages` is the right call, and its cost should be
  named.** The pom comment correctly notes it introduces no reverse edge in
  `bot-app → bot-engine → {bot-strategies, bot-messages} → bot-api`. The cost is that
  `bot-messages` is no longer a plain POJO jar: when the message layer is eventually
  extracted to a plugin, that plugin must be Spring-aware. That is the accepted
  trade-off (one discovery model instead of two), but it is the kind of thing that gets
  re-litigated later, so the pom comment earning its length is a good outcome.

- **`slot()`'s `IllegalStateException` is unreachable from a Spring context.** Spring
  fails a required `List<T>` constructor dependency with `NoSuchBeanDefinitionException`
  before the constructor runs, so a zero-slot-provider deploy fails refresh with a
  *less* clear message than the one this class prepared. The guard still earns its place
  — the bot-engine test constructs the registry directly with `List.of()` and relies on
  `slotProvider == null` being tolerated — but the javadoc's "the build guards it"
  slightly overstates which mechanism does the guarding.

- **Test shape is the strongest part of this diff.** `MessageTypesRegistryTest` and
  `MessageTypesCoverageTest` boot a real `AnnotationConfigApplicationContext` scan
  rather than hand-assembling a provider list, so a lost `@Component` or a moved package
  goes red; `ApplicationContextLoadsTest.messageTypesRegistryIsFullyPopulated` then pins
  the same thing under `Starter`'s own scan, which the package-scoped test cannot see,
  and is deliberately a *superset* check so it is not a second copy of the inventory.
  The coverage test's derived counts (registry size + inventory size ==
  `ProductCode.values().length`) instead of literals is the right instinct — a hardcoded
  4 and 2 would be a third copy of the same fact and a third file every new brand has to
  touch. The three-way split between "does discovery work", "is every product accounted
  for" and "is the operator text unchanged" means a failure tells you which one broke.

**Merge and deploy guidance.**

Safe to merge into `feature/plugin-hot-reload` and deploy alongside 2a and 2b, with two
mechanical caveats:

1. **Expect conflicts in `BotFactory.java` and `ApplicationContextLoadsTest.java`.** 2b
   removes `BettingStrategyFactory.create(StrategyId)` (its own javadoc says so) and
   moves `BotConfiguration.strategyId` to `String`, both of which touch `BotFactory`'s
   neighbourhood, and both phases add cases to `ApplicationContextLoadsTest`. The three
   `BotFactory*Test` fixtures gained a ninth constructor argument here and 2b may
   reshape the same call. All textual, none semantic — but merge 2c *after* 2b so the
   conflict is resolved once, in the file with the smaller diff.
2. **No runtime behaviour change to verify on the box beyond startup.** The observable
   surface is one new INFO line and the unchanged not-yet-implemented text. A smoke test
   is: `docker logs bot-manager | grep "MessageTypesRegistry initialized"` should read
   `BETTING_MINI 4 products [097, 098, 116, 118], TAI_XIU 2 products [114, 116], SLOT
   provider SlotMessageTypesImpl`. A lower count on either table means a provider lost
   its annotation or its package — which is F2's silent-skip path, and until F2 is
   addressed this grep is the only thing standing between that mistake and a brand
   quietly failing to start.
