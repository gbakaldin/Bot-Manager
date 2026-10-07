package com.vingame.bot.config;

import com.vingame.bot.domain.bot.strategy.RandomBehaviorStrategy;
import com.vingame.bot.infrastructure.plugin.PluginRuntime;
import com.vingame.bot.infrastructure.plugin.PluginRuntimeConfiguration;
import io.netty.channel.EventLoopGroup;
import io.netty.util.concurrent.EventExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PLUGIN_HOT_RELOAD_3_4 <b>L-11</b> (spike rule 5): every executor of the shared Netty
 * group has a live thread before {@code PluginRuntime} is constructed. Netty starts an
 * executor's thread on its first task; left lazy, the first bot started after a bundle loads
 * would construct those threads under a plugin frame, and each would capture the plugin
 * loader in its inherited access-control context for life (spike 7b).
 * <ul>
 *   <li>the bean itself comes back fully started — {@code websocket.eventloop.threads} live
 *       {@code multiThreadIoEventLoopGroup-*} threads, one per executor;</li>
 *   <li>the ordering is a bean dependency, not a convention: in a real context,
 *       {@code PluginRuntimeConfiguration.pluginRuntime} sees every executor started when it
 *       runs.</li>
 * </ul>
 * The visible difference this makes (D-1 (f)) is that the threads exist from boot; V4b-1
 * counts them on the box with a SIGQUIT dump.
 */
@DisplayName("Netty pre-start: every event-loop thread is live before PluginRuntime exists (L-11)")
class NettyPrestartTest {

    @Test
    @DisplayName("the EventLoopGroup bean is returned with websocket.eventloop.threads live threads")
    void beanIsPrestarted() {
        NettyEventLoopConfig config = new NettyEventLoopConfig();
        ReflectionTestUtils.setField(config, "eventLoopThreads", 3);
        EventLoopGroup group = config.eventLoopGroup();
        try {
            assertThat(NettyEventLoopConfig.executorCount(group)).isEqualTo(3);
            assertThat(NettyEventLoopConfig.startedExecutorCount(group)).isEqualTo(3);
            assertThat(liveThreadsOf(group))
                    .as("live multiThreadIoEventLoopGroup-* threads owned by this group")
                    .isEqualTo(3);
        } finally {
            config.shutdown();
        }
    }

    @Test
    @DisplayName("in a real context, the group is built and fully started before PluginRuntime, even when declared after it")
    void pluginRuntimeIsBuiltAfterThePrestart() {
        AtomicInteger startedWhenPluginRuntimeWasBuilt = new AtomicInteger(-1);
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",
                    Map.of("websocket.eventloop.threads", "2", "bot.plugins.mode", "classpath")));
            context.registerBean(RandomBehaviorStrategy.class);
            // Declared BEFORE NettyEventLoopConfig on purpose: singletons are created in
            // registration order, so only PluginRuntimeConfiguration.pluginRuntime resolving
            // the group before it opens a bundle can make the group come first.
            context.register(PluginRuntimeConfiguration.class);
            context.register(NettyEventLoopConfig.class);
            context.getBeanFactory().addBeanPostProcessor(new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (bean instanceof PluginRuntime) {
                        ConfigurableListableBeanFactory factory = context.getBeanFactory();
                        // containsSingleton, not getBean: asking would create the group here.
                        startedWhenPluginRuntimeWasBuilt.set(factory.containsSingleton("eventLoopGroup")
                                ? NettyEventLoopConfig.startedExecutorCount(
                                        factory.getBean("eventLoopGroup", EventLoopGroup.class))
                                : 0);
                    }
                    return bean;
                }
            });
            context.refresh();

            assertThat(context.getBean(PluginRuntime.class)).isNotNull();
            assertThat(startedWhenPluginRuntimeWasBuilt.get())
                    .as("executors with a live thread when the PluginRuntime bean was built")
                    .isEqualTo(2);
        }
    }

    private static long liveThreadsOf(EventLoopGroup group) {
        Set<Thread> live = Thread.getAllStackTraces().keySet();
        return live.stream()
                .filter(Thread::isAlive)
                .filter(t -> t.getName().startsWith("multiThreadIoEventLoopGroup-"))
                .filter(t -> {
                    for (EventExecutor executor : group) {
                        if (executor.inEventLoop(t)) {
                            return true;
                        }
                    }
                    return false;
                })
                .count();
    }
}
