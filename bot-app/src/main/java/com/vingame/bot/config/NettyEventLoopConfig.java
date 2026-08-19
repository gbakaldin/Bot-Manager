package com.vingame.bot.config;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.netty.channel.MultiThreadIoEventLoopGroup;

import jakarta.annotation.PreDestroy;

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

    @PreDestroy
    public void shutdown() {
        if (eventLoopGroup != null && !eventLoopGroup.isShutdown()) {
            log.info("Shutting down Netty EventLoopGroup...");
            eventLoopGroup.shutdownGracefully();
        }
    }
}
