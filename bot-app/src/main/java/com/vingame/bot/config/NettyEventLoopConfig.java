package com.vingame.bot.config;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.util.concurrent.EventExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.netty.channel.MultiThreadIoEventLoopGroup;

import jakarta.annotation.PreDestroy;

import java.util.Set;

/**
 * Configuration for Netty EventLoopGroup used by WebSocket clients.
 * <p>
 * Uses MultiThreadIoEventLoopGroup (Netty 4.2+) for better performance and scalability.
 * The EventLoopGroup is shared across all bot instances for optimal resource utilization.
 * <p>
 * Thread count is configurable via application.properties:
 * - Default profile: 4 threads (suitable for few hundred bots)
 * - Loadtest profile: 32+ threads (suitable for thousands of bots)
 * <p>
 * Scale targets:
 * - Production: up to 2,000 concurrent bots
 * - Load testing: up to 100,000 concurrent bots
 * <p>
 * <b>Every event-loop thread is started here, at boot</b> (PLUGIN_HOT_RELOAD_3_4 D-13,
 * L-11, spike rule 5). Netty starts an executor's thread lazily, on its first task, and on
 * JDK 21 a platform thread captures the protection domains of every frame on the stack
 * that constructs it ({@code Thread.inheritedAccessControlContext}). Left lazy, the first
 * bot started after a plugin bundle loads can construct these threads under a plugin
 * frame, and each would then pin that bundle's classloader for the life of the JVM. So the
 * bean submits a no-op to every executor and waits for it before it is published, and
 * {@code PluginRuntimeConfiguration} injects the group, which makes "started before any
 * bundle loads" a bean dependency rather than a convention. The visible difference
 * (D-1 (f)): the {@code multiThreadIoEventLoopGroup-*} threads exist from boot instead of
 * from the first client.
 */
@Slf4j
@Configuration
public class NettyEventLoopConfig {

    @Value("${websocket.eventloop.threads:4}")
    private int eventLoopThreads;

    private EventLoopGroup eventLoopGroup;

    @Bean
    public EventLoopGroup eventLoopGroup() {
        eventLoopGroup = new MultiThreadIoEventLoopGroup(eventLoopThreads, NioIoHandler.newFactory());
        prestart(eventLoopGroup);
        // The identity hash is here so the sharing question stays answerable at INFO.
        // ClientFactory used to print it per client — per bot at start, per restart and per
        // re-auth — which LOG_VOLUME_TIERING demoted to DEBUG. There is exactly one of these
        // groups per JVM and its identity never changes, so one line carries the same fact:
        // any DEBUG "Setting shared EventLoopGroup on client: N" with a different N means
        // the sharing is broken.
        log.info("Creating Netty MultiThreadIoEventLoopGroup with {} threads (NioIoHandler), shared instance {}",
                eventLoopThreads, System.identityHashCode(eventLoopGroup));
        return eventLoopGroup;
    }

    /**
     * Start every executor's thread now, on the calling (boot) thread, and wait until each
     * has run a no-op — so no event-loop thread is ever first constructed under a plugin
     * frame (spike 7b).
     */
    public static void prestart(EventLoopGroup group) {
        for (EventExecutor executor : group) {
            executor.submit(() -> { }).syncUninterruptibly();
        }
    }

    /** @return how many executors the group has. */
    public static int executorCount(EventLoopGroup group) {
        int count = 0;
        for (EventExecutor ignored : group) {
            count++;
        }
        return count;
    }

    /**
     * @return how many of the group's executors have a live thread. Read from the live
     *         threads rather than by asking the executors, because asking (e.g.
     *         {@code threadProperties()}) would itself start a lazy executor's thread.
     */
    public static int startedExecutorCount(EventLoopGroup group) {
        Set<Thread> live = Thread.getAllStackTraces().keySet();
        int started = 0;
        for (EventExecutor executor : group) {
            for (Thread thread : live) {
                if (thread.isAlive() && executor.inEventLoop(thread)) {
                    started++;
                    break;
                }
            }
        }
        return started;
    }

    @PreDestroy
    public void shutdown() {
        if (eventLoopGroup != null && !eventLoopGroup.isShutdown()) {
            log.info("Shutting down Netty EventLoopGroup...");
            eventLoopGroup.shutdownGracefully();
        }
    }
}
