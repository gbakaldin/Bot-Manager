package com.vingame.bot.plugin.it;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.NamedType;
import com.vingame.bot.config.bot.BotBehaviorConfig;
import com.vingame.bot.domain.bot.message.MessageTypesRegistry;
import com.vingame.bot.domain.bot.strategy.BetContext;
import com.vingame.bot.domain.bot.strategy.BettingStrategy;
import com.vingame.bot.domain.bot.strategy.BotMemory;
import com.vingame.bot.domain.bot.strategy.RoundResult;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.infrastructure.plugin.IsolatedPluginBundleLoader;
import com.vingame.bot.infrastructure.plugin.PluginRegistries;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.stream.Stream;

/**
 * The bundle this build ships — {@code target/plugins-dist/<version>/}, exactly what 4c
 * bakes into the image — and the engine-shaped exercise the ITs run against it
 * (PLUGIN_HOT_RELOAD_3_4 Phase 4b).
 * <p>
 * Nothing here names a plugin class: this module's classpath has none (that is its whole
 * point), so the bundle is reached only through {@link IsolatedPluginBundleLoader} and the
 * contracts in {@code bot-api}.
 */
public final class ShippedBundle {

    /**
     * The catalogue every mode must expose (L-13), rendered by
     * {@link PluginRegistries#catalogue()}. The same literal is asserted against classpath
     * mode — the real {@code Starter} scan — by {@code ApplicationContextLoadsTest
     * .classpathModeCatalogueIsTheShippedOne} in {@code bot-app}, so the two modes are
     * pinned to one string. It also matches the three boot lines release-d1 captured on
     * Bot-1 ({@code registered 9} / {@code registered 2} / the product sets). A new
     * strategy or provider changes both tests, deliberately.
     */
    public static final String CATALOGUE = "betting=[DALEMBERT_AGGRESSIVE, DALEMBERT_CAUTIOUS, "
            + "FIBONACCI_AGGRESSIVE, FIBONACCI_CAUTIOUS, MARTINGALE_CLASSIC_AGGRESSIVE, "
            + "MARTINGALE_CLASSIC_CAUTIOUS, PAROLI_AGGRESSIVE, PAROLI_CAUTIOUS, RANDOM]; "
            + "slot=[FIXED, RANDOM]; "
            + "BETTING_MINI={097=BomGameMessageTypes, 098=BomGameMessageTypes, "
            + "114=RikGameMessageTypes, 116=TipGameMessageTypes, 118=NohuGameMessageTypes, "
            + "119=Win79GameMessageTypes}; "
            + "TAI_XIU={114=JackpotTaiXiuMessageTypes, 116=MiniGameTaiXiuMessageTypes, "
            + "119=Win79TaiXiuMessageTypes}; "
            + "SLOT=SlotMessageTypesImpl; "
            + "CASHOUT={119=Win79CashoutMessageTypes}; "
            + "CRASH={119=Win79CrashMessageTypes}";

    /** Offset used for every offset-keyed provider in the frame exercise. */
    static final int OFFSET = 2000;

    private ShippedBundle() {
    }

    /** {@code target/plugins-dist}, from failsafe's {@code plugins.dist} property. */
    public static Path distDir() {
        return Path.of(System.getProperty("plugins.dist", "target/plugins-dist"));
    }

    /** The one version directory under {@link #distDir()}. */
    public static Path versionDir() {
        try (Stream<Path> dirs = Files.list(distDir())) {
            List<Path> all = dirs.filter(Files::isDirectory).toList();
            if (all.size() != 1) {
                throw new IllegalStateException("expected exactly one bundle directory in "
                        + distDir() + ", found " + all);
            }
            return all.get(0);
        } catch (IOException e) {
            throw new IllegalStateException("cannot list " + distDir(), e);
        }
    }

    /** The shipped bundle's jars, sorted by name. */
    public static List<Path> jars() {
        try (Stream<Path> files = Files.list(versionDir())) {
            return files.filter(p -> p.getFileName().toString().endsWith(".jar")).sorted().toList();
        } catch (IOException e) {
            throw new IllegalStateException("cannot list " + versionDir(), e);
        }
    }

    /**
     * Load the shipped bundle the way isolated mode does at boot: {@code plugins-dist} as
     * the mounted directory, nothing baked. The caller owns the result and must close its
     * bundle.
     */
    public static PluginRegistries load() {
        return new IsolatedPluginBundleLoader(distDir(), distDir().resolve("_no-builtin")).load();
    }

    /**
     * Every betting strategy × {@code decide} / {@code onRoundEnd}, the way
     * {@code BettingMiniGameBot} drives them: a fresh prototype per key, five rounds of
     * twenty ticks each.
     *
     * @return the keys exercised.
     */
    public static List<String> exerciseStrategies(PluginRegistries registries, Random rng) {
        List<String> keys = new ArrayList<>(registries.bettingStrategies().registeredKeys());
        for (String key : keys) {
            exercise(registries.bettingStrategies().create(key), rng);
        }
        return keys;
    }

    /** Five rounds of twenty {@code decide} ticks and one {@code onRoundEnd} each. */
    public static void exercise(BettingStrategy strategy, Random rng) {
        Game game = Game.builder().name("plugin-it").optionAffinities(Map.of(1, 1, 2, 3)).build();
        BotBehaviorConfig behavior = BotBehaviorConfig.builder()
                .minBet(1000).maxBet(10000).betIncrement(1000).maxBetsPerRound(5)
                .betSkipPercentage(10).build();
        BotMemory memory = new BotMemory(game);
        for (long sid = 1; sid <= 5; sid++) {
            memory.beginRound(sid, 1_000_000);
            for (int tick = 0; tick < 20; tick++) {
                strategy.decide(new BetContext(memory, behavior, game, 1_000_000,
                        memory.getCurrentRound(), rng, 5, tick % 2 == 0));
            }
            strategy.onRoundEnd(new RoundResult(sid, Optional.of(1), Map.of(1, 1000L), 0, -1000,
                    Instant.now()));
        }
    }

    /**
     * One frame per registered message type of every provider, deserialized through a
     * per-bot mapper built as {@code Bot.newMessageMapper()} builds it — fresh
     * {@code ObjectMapper}, unknown properties ignored, the bundle's {@code TypeFactory} —
     * with the provider's registrations on it (L-13). The frame is the minimal one the
     * subtype dispatch reads, {@code {"cmd": N}}.
     *
     * @return one entry per registration: what was registered and what came back.
     */
    public static List<Decoded> deserializeEveryRegistration(PluginRegistries registries) {
        MessageTypesRegistry messages = registries.messageTypes();
        List<Decoded> decoded = new ArrayList<>();
        for (String product : messages.registeredBettingMiniProducts()) {
            decodeAll(registries, "BETTING_MINI/" + product,
                    messages.bettingMini(product).getTypeRegistrations(OFFSET, false), decoded);
        }
        for (String product : messages.registeredTaiXiuProducts()) {
            decodeAll(registries, "TAI_XIU/" + product,
                    messages.taiXiu(product).getTypeRegistrations(), decoded);
        }
        if (messages.hasSlotProvider()) {
            decodeAll(registries, "SLOT", messages.slot().getTypeRegistrations(), decoded);
        }
        for (String product : messages.registeredCashoutProducts()) {
            decodeAll(registries, "CASHOUT/" + product,
                    messages.cashout(product).getTypeRegistrations(OFFSET), decoded);
        }
        for (String product : messages.registeredCrashProducts()) {
            decodeAll(registries, "CRASH/" + product,
                    messages.crash(product).getTypeRegistrations(OFFSET), decoded);
        }
        return decoded;
    }

    /** All registrations of every provider, flattened (what L-14's negative control leaks). */
    public static NamedType[] allRegistrations(PluginRegistries registries) {
        List<NamedType> all = new ArrayList<>();
        MessageTypesRegistry messages = registries.messageTypes();
        for (String product : messages.registeredBettingMiniProducts()) {
            all.addAll(List.of(messages.bettingMini(product).getTypeRegistrations(OFFSET, false)));
        }
        return all.toArray(NamedType[]::new);
    }

    private static void decodeAll(PluginRegistries registries, String provider, NamedType[] regs,
                                  List<Decoded> out) {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.setTypeFactory(registries.typeFactory());
        mapper.registerSubtypes(regs);
        for (NamedType reg : regs) {
            Class<?> base = polymorphicBase(reg.getType());
            String frame = "{\"cmd\":" + reg.getName() + "}";
            try {
                Object value = mapper.readValue(frame, base);
                out.add(new Decoded(provider, reg.getName(), reg.getType(), value.getClass()));
            } catch (IOException e) {
                throw new IllegalStateException(provider + ": frame " + frame + " did not decode as "
                        + reg.getType().getName(), e);
            }
        }
    }

    /** The nearest supertype carrying {@code @JsonTypeInfo} — what a pipeline reads as. */
    static Class<?> polymorphicBase(Class<?> type) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            if (c.isAnnotationPresent(JsonTypeInfo.class)) {
                return c;
            }
            for (Class<?> i : c.getInterfaces()) {
                if (i.isAnnotationPresent(JsonTypeInfo.class)) {
                    return i;
                }
            }
        }
        return type;
    }

    /** One frame's outcome. */
    public record Decoded(String provider, String cmd, Class<?> registered, Class<?> decodedAs) {
    }
}
