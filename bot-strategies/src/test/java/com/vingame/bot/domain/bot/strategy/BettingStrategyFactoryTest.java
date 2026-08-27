package com.vingame.bot.domain.bot.strategy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 3 of {@code docs/plans/BETTING_STRATEGIES.md}.
 *
 * <p>Pins the registry / lookup contract:
 * <ul>
 *   <li>Beans annotated {@link StrategyImpl} discovered at startup, keyed by
 *       {@link StrategyId}.</li>
 *   <li>{@code create(id, seed)} returns a fresh instance each call (prototype
 *       semantics — delegated to {@link ApplicationContext#getBean(Class)}).</li>
 *   <li>Unknown {@link StrategyId} throws {@link IllegalArgumentException}.</li>
 *   <li>Duplicate {@code @StrategyImpl} keys on two beans throw at startup.</li>
 *   <li>A {@link BettingStrategy} bean missing {@link StrategyImpl} is skipped
 *       with a WARN — neither registers nor crashes init.</li>
 * </ul>
 */
@DisplayName("BettingStrategyFactory")
class BettingStrategyFactoryTest {

    @Test
    @DisplayName("init() registers all @StrategyImpl-annotated beans")
    void initRegistersAnnotated() {
        RandomBehaviorStrategy random = new RandomBehaviorStrategy();
        ApplicationContext context = mock(ApplicationContext.class);

        BettingStrategyFactory factory = new BettingStrategyFactory(context, List.of(random));
        factory.init();

        assertThat(factory.registeredKeys()).containsExactly("RANDOM");
    }

    @Test
    @DisplayName("create(RANDOM, seed) returns a fresh instance per call")
    void createReturnsFreshInstances() {
        ApplicationContext context = mock(ApplicationContext.class);
        RandomBehaviorStrategy a = new RandomBehaviorStrategy();
        RandomBehaviorStrategy b = new RandomBehaviorStrategy();
        when(context.getBean(RandomBehaviorStrategy.class)).thenReturn(a, b);

        BettingStrategyFactory factory = new BettingStrategyFactory(context, List.of(a));
        factory.init();

        BettingStrategy s1 = factory.create(StrategyId.RANDOM.name());
        BettingStrategy s2 = factory.create(StrategyId.RANDOM.name());

        assertThat(s1).isNotSameAs(s2);
        assertThat(s1).isInstanceOf(RandomBehaviorStrategy.class);
        assertThat(s2).isInstanceOf(RandomBehaviorStrategy.class);
    }

    @Test
    @DisplayName("create with an unregistered key throws IllegalArgumentException")
    void unknownIdThrows() {
        ApplicationContext context = mock(ApplicationContext.class);

        BettingStrategyFactory factory = new BettingStrategyFactory(context, List.of());
        factory.init();

        assertThatThrownBy(() -> factory.create(StrategyId.RANDOM.name()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RANDOM");
    }

    @Test
    @DisplayName("a null key is rejected with IllegalArgumentException, not NPE")
    void nullKeyRejectedWithIllegalArgumentException() {
        // AD-23 (Phase 2 changes no behaviour). Before Phase 2a the registry was
        // an EnumMap and EnumMap.get(null) returns null, so create(null) threw
        // IllegalArgumentException rather than NPE. Phase 2a preserved that with
        // an `id == null` guard on the deprecated enum overload; Phase 2b deletes
        // that overload, and the property now belongs to create(String) alone —
        // LinkedHashMap.get(null) is also null-tolerant, so the message is the
        // same. Pinned here because it is one `Objects.requireNonNull` away from
        // silently changing.
        ApplicationContext context = mock(ApplicationContext.class);

        BettingStrategyFactory factory =
                new BettingStrategyFactory(context, List.of(new RandomBehaviorStrategy()));
        factory.init();

        assertThatThrownBy(() -> factory.create(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null");
    }

    @Test
    @DisplayName("the strategies-present tail is sorted, not in scan order")
    void lookupFailureTailIsSorted() {
        // review-2a: the tail is the string an operator pastes into a ticket when a
        // group fails to start, and registry order is Spring's scan order — not
        // stable across an exploded-classes run and a jar run (Amendment A4). This
        // fixture is deliberately handed its beans in an order that is NOT sorted,
        // so an unsorted render would reproduce it and fail here.
        ApplicationContext context = mock(ApplicationContext.class);
        BettingStrategyFactory factory = new BettingStrategyFactory(
                context, List.of(new RandomBehaviorStrategy(), new FakeAaaStrategy()));
        factory.init();

        assertThat(factory.registeredKeys()).containsExactly("RANDOM", "AAA_FIRST_WHEN_SORTED");

        assertThatThrownBy(() -> factory.create("NOPE"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strategies present: [AAA_FIRST_WHEN_SORTED, RANDOM]");
    }

    @Test
    @DisplayName("a blank key is visible in the lookup failure, not swallowed by the message")
    void blankKeyIsQuotedInTheMessage() {
        // Since Phase 2b the key is a String, so "" and "   " are representable.
        // They cannot arrive through the API (AD-15 rejects them as unknown keys)
        // but they can arrive from a direct Mongo write, and unquoted the message
        // read "No BettingStrategy registered for  — strategies present: [...]".
        // Quoting is the whole fix: no isBlank() fallback, because silently turning
        // a corrupt config into a RANDOM bot is what BETTING_STRATEGIES AD-12
        // forbids.
        ApplicationContext context = mock(ApplicationContext.class);
        BettingStrategyFactory factory =
                new BettingStrategyFactory(context, List.of(new RandomBehaviorStrategy()));
        factory.init();

        assertThatThrownBy(() -> factory.create("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("registered for '   '");
        assertThatThrownBy(() -> factory.create(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("registered for ''");
    }

    @Test
    @DisplayName("the annotation is read through the class hierarchy, not off getClass()")
    void annotationIsResolvedThroughTheHierarchy() {
        // review-2c F3: the lookup moved to AopUtils.getTargetClass +
        // AnnotationUtils.findAnnotation so a proxied bean is not silently skipped.
        // A proxy cannot be built here without pulling in an AOP fixture, but
        // findAnnotation's other effect — searching superclasses, which
        // getAnnotation did not do because @StrategyImpl is not @Inherited — is
        // observable directly and is what would regress if someone reverted the
        // line.
        ApplicationContext context = mock(ApplicationContext.class);
        BettingStrategyFactory factory =
                new BettingStrategyFactory(context, List.of(new SubclassOfAnnotatedBase()));
        factory.init();

        assertThat(factory.registeredKeys()).containsExactly("INHERITED_KEY");
    }

    @Test
    @DisplayName("Duplicate @StrategyImpl on two beans throws at init")
    void duplicateImplThrows() {
        ApplicationContext context = mock(ApplicationContext.class);
        RandomBehaviorStrategy a = new RandomBehaviorStrategy();
        // A second bean class with the same @StrategyImpl(RANDOM) annotation —
        // simulates a deploy bug where two strategies claim the same id.
        FakeRandomDuplicate b = new FakeRandomDuplicate();

        BettingStrategyFactory factory = new BettingStrategyFactory(context, List.of(a, b));
        assertThatThrownBy(factory::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate");
    }

    @Test
    @DisplayName("BettingStrategy bean without @StrategyImpl is skipped (not registered)")
    void unannotatedBeanSkipped() {
        ApplicationContext context = mock(ApplicationContext.class);
        UnannotatedStrategy stray = new UnannotatedStrategy();

        BettingStrategyFactory factory = new BettingStrategyFactory(context, List.of(stray));
        factory.init();

        assertThat(factory.registeredKeys()).isEmpty();
    }

    /**
     * review-2d finding 3. {@code registeredKeys()} used to hand back
     * {@code unmodifiableSet(registry.keySet())} — an unmodifiable <em>view</em>,
     * safe only while the map is written once and never touched again. Phase 2d put
     * an HTTP request thread on this method ({@code StrategyCatalog} streams and
     * sorts it per request) and step 5 is where the registry starts being mutated
     * after refresh, at which point a view throws
     * {@link java.util.ConcurrentModificationException} mid-stream on that thread —
     * a 500 on the picker that reads like an endpoint bug.
     *
     * <p>The mutation is done by reflection because no public path can mutate the
     * registry today; that is the whole point, and it is why the property is
     * otherwise unobservable and would be reverted as a pointless copy.
     */
    @Test
    @DisplayName("registeredKeys returns a snapshot, not a live view of the registry")
    @SuppressWarnings("unchecked")
    void registeredKeysIsASnapshot() throws Exception {
        ApplicationContext context = mock(ApplicationContext.class);
        BettingStrategyFactory factory =
                new BettingStrategyFactory(context, List.of(new RandomBehaviorStrategy()));
        factory.init();

        Set<String> taken = factory.registeredKeys();

        Field registryField = BettingStrategyFactory.class.getDeclaredField("registry");
        registryField.setAccessible(true);
        Map<String, Class<? extends BettingStrategy>> registry =
                (Map<String, Class<? extends BettingStrategy>>) registryField.get(factory);
        registry.put("LATE_ARRIVAL", RandomBehaviorStrategy.class);

        assertThat(taken)
                .as("a set handed out before the mutation must not see it")
                .containsExactly("RANDOM");
        assertThat(factory.registeredKeys())
                .as("and the next read must, since it is a fresh snapshot")
                .containsExactly("RANDOM", "LATE_ARRIVAL");
    }

    @Test
    @DisplayName("the snapshot is still unmodifiable and still in discovery order")
    void snapshotKeepsItsOldGuarantees() {
        // Not Set.copyOf: its iteration order is unspecified and salted per JVM run,
        // which would discard the documented discovery order and make
        // lookupFailureTailIsSorted — whose premise is that this method reproduces
        // the *unsorted* scan order — flake.
        ApplicationContext context = mock(ApplicationContext.class);
        BettingStrategyFactory factory = new BettingStrategyFactory(
                context, List.of(new RandomBehaviorStrategy(), new FakeAaaStrategy()));
        factory.init();

        Set<String> keys = factory.registeredKeys();

        assertThat(keys).containsExactly("RANDOM", "AAA_FIRST_WHEN_SORTED");
        assertThatThrownBy(() -> keys.add("NOPE"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /**
     * Standalone strategy that duplicates {@link StrategyId#RANDOM} to test
     * the duplicate-registration guard. Kept inside the test class so the
     * production scan never picks it up.
     */
    @StrategyImpl("RANDOM")
    private static final class FakeRandomDuplicate implements BettingStrategy {
        @Override public void onRoundEnd(RoundResult result) { }
        @Override public Optional<BetDecision> decide(BetContext ctx) { return Optional.empty(); }
    }

    /**
     * Strategy without {@code @StrategyImpl} — the factory should skip it
     * with a WARN, not crash. Note it implements the interface directly rather
     * than subclassing an annotated strategy: since the annotation lookup moved to
     * {@code AnnotationUtils.findAnnotation} it searches superclasses, so a
     * subclass would inherit a key and this fixture would stop testing its name.
     */
    private static final class UnannotatedStrategy implements BettingStrategy {
        @Override public void onRoundEnd(RoundResult result) { }
        @Override public Optional<BetDecision> decide(BetContext ctx) { return Optional.empty(); }
    }

    /**
     * A second key that sorts before {@code RANDOM} alphabetically but is
     * registered after it, so {@link #lookupFailureTailIsSorted} can tell a sorted
     * render apart from an insertion-ordered one.
     */
    @StrategyImpl("AAA_FIRST_WHEN_SORTED")
    private static final class FakeAaaStrategy implements BettingStrategy {
        @Override public void onRoundEnd(RoundResult result) { }
        @Override public Optional<BetDecision> decide(BetContext ctx) { return Optional.empty(); }
    }

    /** An annotated base, so a subclass has something to inherit. */
    @StrategyImpl("INHERITED_KEY")
    private static class AnnotatedBase implements BettingStrategy {
        @Override public void onRoundEnd(RoundResult result) { }
        @Override public Optional<BetDecision> decide(BetContext ctx) { return Optional.empty(); }
    }

    /** Carries no annotation of its own; inherits {@code @StrategyImpl("INHERITED_KEY")}. */
    private static final class SubclassOfAnnotatedBase extends AnnotatedBase {
    }
}
