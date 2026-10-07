package com.vingame.bot.plugin.it;

import com.vingame.bot.domain.bot.strategy.BettingStrategy;
import com.vingame.bot.infrastructure.plugin.PluginRegistries;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.util.concurrent.EventExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.security.AccessControlContext;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PLUGIN_HOT_RELOAD_3_4 <b>L-12</b>, the platform-thread census (spike rule 5, scenarios
 * 7b/7c). On JDK 21 a platform thread captures {@code AccessController.getContext()} at
 * construction — the protection domains of every frame on the constructing stack — in
 * {@code Thread.inheritedAccessControlContext}, and keeps it for life. A platform thread
 * first constructed while a plugin frame is on the stack therefore pins the plugin loader
 * until it dies, whatever its context class loader.
 * <p>
 * After L-13's exercise (every strategy played, every registered message type decoded),
 * no live platform thread may hold a protection domain of the plugin loader, nor have it
 * as its context class loader.
 * <p>
 * <b>The negative control proves the census can fail</b>: a <em>cold</em>
 * {@code MultiThreadIoEventLoopGroup} whose first task is submitted from inside plugin code
 * — the {@link Random} handed to a real plugin strategy's {@code decide} starts it — must
 * be detected. That is the exact shape {@code NettyEventLoopConfig}'s pre-start prevents
 * for the shared group. Strict (D-14): do not weaken; {@code @Disabled} only with the
 * user's sign-off.
 * <p>
 * Needs {@code --add-opens java.base/java.lang} and {@code java.base/java.security}
 * (failsafe {@code argLine}).
 */
@DisplayName("L-12: no platform thread holds the plugin loader after the engine-shaped exercise")
class PlatformThreadPinIT {

    @Test
    @DisplayName("after the exercise, no platform thread's inherited ACC or TCCL is the plugin loader")
    void noPlatformThreadPinsThePluginLoader() throws Exception {
        PluginRegistries registries = ShippedBundle.load();
        try {
            ShippedBundle.exerciseStrategies(registries, new Random(3));
            ShippedBundle.deserializeEveryRegistration(registries);

            assertThat(census(registries.bundle().classLoader()))
                    .as("platform threads that would pin the plugin loader for their whole life")
                    .isEmpty();
        } finally {
            registries.bundle().close();
        }
    }

    @Test
    @DisplayName("negative control: a cold Netty group started from a plugin frame IS detected")
    void coldGroupStartedUnderAPluginFrameIsDetected() throws Exception {
        PluginRegistries registries = ShippedBundle.load();
        EventLoopGroup cold = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        try {
            ClassLoader pluginLoader = registries.bundle().classLoader();
            BettingStrategy strategy = registries.bettingStrategies().create("RANDOM");
            assertThat(strategy.getClass().getClassLoader()).isSameAs(pluginLoader);
            assertThat(census(pluginLoader)).as("clean before the control").isEmpty();

            ShippedBundle.exercise(strategy, new GroupStartingRandom(cold));

            List<String> detected = census(pluginLoader);
            assertThat(detected)
                    .as("the census must see the event-loop thread constructed under the plugin's decide()")
                    .isNotEmpty()
                    .anySatisfy(line -> assertThat(line).startsWith("multiThreadIoEventLoopGroup-"));
        } finally {
            cold.shutdownGracefully().syncUninterruptibly();
            registries.bundle().close();
        }
    }

    /**
     * Every live platform thread whose inherited access-control context carries a
     * protection domain of {@code pluginLoader}, or whose context class loader is it.
     * {@code Thread.getAllStackTraces()} lists platform threads only.
     */
    static List<String> census(ClassLoader pluginLoader) throws Exception {
        Field inherited = Thread.class.getDeclaredField("inheritedAccessControlContext");
        inherited.setAccessible(true);
        Field context = AccessControlContext.class.getDeclaredField("context");
        context.setAccessible(true);
        List<String> pinned = new ArrayList<>();
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (!thread.isAlive() || thread.isVirtual()) {
                continue;
            }
            if (thread.getContextClassLoader() == pluginLoader) {
                pinned.add(thread.getName() + " (TCCL)");
            }
            AccessControlContext acc = (AccessControlContext) inherited.get(thread);
            ProtectionDomain[] domains = acc == null ? null : (ProtectionDomain[]) context.get(acc);
            if (domains == null) {
                continue;
            }
            for (ProtectionDomain domain : domains) {
                if (domain != null && domain.getClassLoader() == pluginLoader) {
                    pinned.add(thread.getName() + " (inheritedAccessControlContext)");
                    break;
                }
            }
        }
        return pinned;
    }

    /**
     * A {@link Random} whose first draw starts every executor of a cold group. Called from
     * inside the plugin strategy's {@code decide}, so the plugin frame is on the stack when
     * Netty constructs the event-loop thread.
     */
    private static final class GroupStartingRandom extends Random {
        private final transient EventLoopGroup group;
        private boolean started;

        GroupStartingRandom(EventLoopGroup group) {
            super(5);
            this.group = group;
        }

        @Override
        public int nextInt(int bound) {
            startOnce();
            return super.nextInt(bound);
        }

        @Override
        public double nextDouble() {
            startOnce();
            return super.nextDouble();
        }

        private void startOnce() {
            if (!started) {
                started = true;
                for (EventExecutor executor : group) {
                    executor.submit(() -> { }).syncUninterruptibly();
                }
            }
        }
    }
}
