package com.vingame.bot.plugin.it;

import com.vingame.bot.infrastructure.plugin.PluginBundle;
import com.vingame.bot.infrastructure.plugin.PluginRegistries;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PLUGIN_HOT_RELOAD_3_4 <b>L-13</b>: isolated mode resolves exactly what classpath mode
 * resolves.
 * <ul>
 *   <li>The same catalogue: the 9 betting / 2 slot strategy keys and the same provider per
 *       product in every message-types table. {@link ShippedBundle#CATALOGUE} is the single
 *       literal both modes are pinned to — classpath mode by
 *       {@code ApplicationContextLoadsTest.classpathModeCatalogueIsTheShippedOne} under the
 *       real {@code Starter} scan, isolated mode here.</li>
 *   <li>The same types on the wire: one frame per registered message type of every
 *       provider decodes, through a per-bot mapper built as the bots build it (the bundle's
 *       {@code TypeFactory}), to exactly the class the provider registered.</li>
 * </ul>
 * <b>Deviation from the plan's wording, recorded:</b> the plan asks for "one recorded frame
 * per provider". The frames here are synthetic — {@code {"cmd": N}}, the one property the
 * subtype dispatch reads — and there is one per <em>registration</em> rather than per
 * provider. That is strictly more dispatch coverage (every cmd of every provider, not one),
 * and it needs no capture files that would rot as brands change. Field-level decoding of a
 * real frame is the message modules' own tests' job and does not differ between loaders.
 */
@DisplayName("L-13: isolated mode exposes the classpath catalogue and decodes every registered type")
class IsolatedEquivalenceIT {

    private static PluginRegistries registries;

    @BeforeAll
    static void load() {
        registries = ShippedBundle.load();
    }

    @AfterAll
    static void close() {
        registries.bundle().close();
    }

    @Test
    @DisplayName("the catalogue is classpath mode's, key for key and provider for provider")
    void catalogueMatchesClasspathMode() {
        assertThat(registries.bettingStrategies().registeredKeys()).hasSize(9);
        assertThat(registries.slotStrategies().registeredKeys()).hasSize(2);
        assertThat(registries.catalogue()).isEqualTo(ShippedBundle.CATALOGUE);
    }

    @Test
    @DisplayName("every registered message type of every provider decodes to the registered class via the bundle's TypeFactory")
    void everyRegistrationDecodesToItsType() {
        List<ShippedBundle.Decoded> decoded = ShippedBundle.deserializeEveryRegistration(registries);

        assertThat(decoded).hasSizeGreaterThan(30);
        assertThat(decoded).allSatisfy(d -> assertThat(d.decodedAs())
                .as("%s cmd %s", d.provider(), d.cmd())
                .isSameAs(d.registered()));
        ClassLoader pluginLoader = registries.bundle().classLoader();
        ClassLoader parent = PluginBundle.class.getClassLoader();
        assertThat(decoded).allSatisfy(d -> assertThat(d.registered().getClassLoader())
                .as("%s: a plugin message type or a bot-api one, nothing else", d.registered().getName())
                .isIn(pluginLoader, parent));
        assertThat(decoded).anySatisfy(d -> assertThat(d.registered().getClassLoader()).isSameAs(pluginLoader));
        assertThat(decoded).extracting(ShippedBundle.Decoded::provider)
                .contains("BETTING_MINI/116", "TAI_XIU/114", "SLOT", "CASHOUT/119", "CRASH/119");
    }

    @Test
    @DisplayName("every betting strategy builds and plays from the isolated bundle")
    void everyStrategyPlays() {
        assertThat(ShippedBundle.exerciseStrategies(registries, new Random(11))).hasSize(9);
        assertThat(registries.slotStrategies().create("FIXED")).isNotNull();
        assertThat(registries.slotStrategies().create("RANDOM")).isNotNull();
    }
}
