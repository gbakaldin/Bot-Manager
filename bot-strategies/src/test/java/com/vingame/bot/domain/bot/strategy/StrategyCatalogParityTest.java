package com.vingame.bot.domain.bot.strategy;

import com.vingame.bot.domain.bot.strategy.slot.SlotStrategy;
import com.vingame.bot.domain.bot.strategy.slot.SlotStrategyFactory;
import com.vingame.bot.domain.bot.strategy.slot.SlotStrategyId;
import com.vingame.bot.domain.bot.strategy.slot.SlotStrategyImpl;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PLUGIN_HOT_RELOAD Phase 2a, AD-12 — the guard that replaces the enum's type
 * check.
 *
 * <p>Before Phase 2a the registry key was a {@link StrategyId} and
 * {@code @StrategyImpl(StrategyId.RANDOM)} could not be misspelled. It is now a
 * string literal, so {@code @StrategyImpl("RANDOM ")} compiles, registers a
 * phantom key nothing can ever look up, and silently removes RANDOM from the
 * catalogue. This test is what fails instead — and it is strictly stronger than
 * the type check it replaces, because it also catches a <em>bean</em> that has
 * gone missing (lost {@code @Component}, lost from the scan, renamed package),
 * which the enum never could.
 *
 * <p><b>Discovery here is real Spring component scanning, not a hand-built bean
 * list.</b> The sibling {@link BettingStrategyFactoryTest} /
 * {@link MartingaleStrategyFactoryWiringTest} construct the factory with
 * {@code new BettingStrategyFactory(mockContext, List.of(new Xxx(), ...))},
 * which cannot notice a strategy that stopped being a bean — the test hands it
 * the instance itself. Here the beans arrive the way they arrive in production:
 * classpath scan → {@code @Component} → {@code List<BettingStrategy>} injection
 * → {@code @PostConstruct init()} → {@code getBean(Class)} for a prototype.
 *
 * <p>What this still does <em>not</em> exercise is Spring Boot's own context
 * (bot-app's {@code Starter} scans {@code com.vingame.bot} with auto-configuration
 * on top). If the strategies ever stop being reachable from <em>that</em> scan
 * while remaining reachable from this one, the operator-facing signal is
 * verification P2-2: {@code BettingStrategyFactory initialized: registered 9
 * strategies} in the boot log.
 */
@DisplayName("Strategy catalogue parity — every built-in enum name is a registered key")
class StrategyCatalogParityTest {

    /** The package both factories and all eleven strategy beans live under. */
    private static final String SCAN_BASE = "com.vingame.bot.domain.bot.strategy";

    private static AnnotationConfigApplicationContext context;
    private static BettingStrategyFactory bettingFactory;
    private static SlotStrategyFactory slotFactory;

    @BeforeAll
    static void bootRealContext() {
        context = new AnnotationConfigApplicationContext();
        context.scan(SCAN_BASE);
        context.refresh();
        bettingFactory = context.getBean(BettingStrategyFactory.class);
        slotFactory = context.getBean(SlotStrategyFactory.class);
    }

    @AfterAll
    static void closeContext() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    @DisplayName("Every StrategyId constant name is a registered betting key")
    void everyBettingBuiltinNameIsRegistered() {
        Set<String> keys = bettingFactory.registeredKeys();
        for (StrategyId id : StrategyId.values()) {
            assertThat(keys)
                    .as("StrategyId.%s has no bean claiming @StrategyImpl(\"%s\") — "
                            + "either the bean is missing or its literal is misspelled", id, id.name())
                    .contains(id.name());
        }
    }

    @Test
    @DisplayName("Every SlotStrategyId constant name is a registered slot key")
    void everySlotBuiltinNameIsRegistered() {
        Set<String> keys = slotFactory.registeredKeys();
        for (SlotStrategyId id : SlotStrategyId.values()) {
            assertThat(keys)
                    .as("SlotStrategyId.%s has no bean claiming @SlotStrategyImpl(\"%s\")", id, id.name())
                    .contains(id.name());
        }
    }

    @Test
    @DisplayName("Every registered betting key naming a built-in is claimed by exactly one bean")
    void everyBettingBuiltinKeyIsClaimedByExactlyOneBean() {
        Map<Class<?>, String> claimedBy = new HashMap<>();
        for (String key : bettingFactory.registeredKeys()) {
            if (!isBuiltinBettingKey(key)) {
                continue; // a plugin-supplied key — out of the enum's contract
            }
            BettingStrategy strategy = bettingFactory.create(key);
            assertThat(strategy)
                    .as("create(\"%s\") must resolve a bean", key)
                    .isNotNull();

            StrategyImpl annotation = strategy.getClass().getAnnotation(StrategyImpl.class);
            assertThat(annotation)
                    .as("%s resolved for key \"%s\" but carries no @StrategyImpl",
                            strategy.getClass().getName(), key)
                    .isNotNull();
            assertThat(annotation.value())
                    .as("key \"%s\" resolved a bean that claims \"%s\"", key, annotation.value())
                    .isEqualTo(key);

            String previous = claimedBy.put(strategy.getClass(), key);
            assertThat(previous)
                    .as("%s is registered under both \"%s\" and \"%s\"",
                            strategy.getClass().getName(), previous, key)
                    .isNull();
        }
        assertThat(claimedBy).hasSize(StrategyId.values().length);
    }

    @Test
    @DisplayName("Every registered slot key naming a built-in is claimed by exactly one bean")
    void everySlotBuiltinKeyIsClaimedByExactlyOneBean() {
        Map<Class<?>, String> claimedBy = new HashMap<>();
        for (String key : slotFactory.registeredKeys()) {
            if (!isBuiltinSlotKey(key)) {
                continue;
            }
            SlotStrategy strategy = slotFactory.create(key);
            assertThat(strategy).as("create(\"%s\") must resolve a bean", key).isNotNull();

            SlotStrategyImpl annotation = strategy.getClass().getAnnotation(SlotStrategyImpl.class);
            assertThat(annotation)
                    .as("%s resolved for key \"%s\" but carries no @SlotStrategyImpl",
                            strategy.getClass().getName(), key)
                    .isNotNull();
            assertThat(annotation.value()).isEqualTo(key);

            String previous = claimedBy.put(strategy.getClass(), key);
            assertThat(previous)
                    .as("%s is registered under both \"%s\" and \"%s\"",
                            strategy.getClass().getName(), previous, key)
                    .isNull();
        }
        assertThat(claimedBy).hasSize(SlotStrategyId.values().length);
    }

    /**
     * The typo guard, stated directly. {@code "RANDOM "} would satisfy nothing
     * above except by making {@code everyBettingBuiltinNameIsRegistered} fail
     * with a confusing "RANDOM is missing" message; this test names the actual
     * fault. Blank and untrimmed keys are the two shapes a copy-paste produces.
     */
    @Test
    @DisplayName("No registered key is blank or carries stray whitespace")
    void keysAreCleanLiterals() {
        for (String key : bettingFactory.registeredKeys()) {
            assertThat(key).isNotBlank().isEqualTo(key.trim());
        }
        for (String key : slotFactory.registeredKeys()) {
            assertThat(key).isNotBlank().isEqualTo(key.trim());
        }
    }

    /**
     * The counts verification P2-2 greps for in the boot log, asserted at build
     * time so a lost bean fails here rather than on the box.
     */
    @Test
    @DisplayName("Real component scan discovers 9 betting and 2 slot strategies")
    void scanDiscoversTheWholeCatalogue() {
        assertThat(bettingFactory.registeredKeys()).hasSize(9);
        assertThat(slotFactory.registeredKeys()).hasSize(2);
    }

    /**
     * AD-12 keeps the enums as the compile-time home of the UI copy that
     * {@code StrategyController} serves (and that Phase 2d's catalogue will join
     * to the registry). Pin that it is actually there.
     */
    @Test
    @DisplayName("Every built-in key has display metadata to join to")
    void builtinsCarryDisplayMetadata() {
        for (StrategyId id : StrategyId.values()) {
            assertThat(id.getDisplayName()).as("%s displayName", id).isNotBlank();
            assertThat(id.getDescription()).as("%s description", id).isNotBlank();
        }
        for (SlotStrategyId id : SlotStrategyId.values()) {
            assertThat(id.getDisplayName()).as("%s displayName", id).isNotBlank();
            assertThat(id.getDescription()).as("%s description", id).isNotBlank();
        }
    }

    private static boolean isBuiltinBettingKey(String key) {
        for (StrategyId id : StrategyId.values()) {
            if (id.name().equals(key)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBuiltinSlotKey(String key) {
        for (SlotStrategyId id : SlotStrategyId.values()) {
            if (id.name().equals(key)) {
                return true;
            }
        }
        return false;
    }
}
