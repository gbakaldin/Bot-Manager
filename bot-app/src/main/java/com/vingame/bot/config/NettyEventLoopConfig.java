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
        log.info("Creating Netty MultiThreadIoEventLoopGroup with {} threads (NioIoHandler)", eventLoopThreads);
        eventLoopGroup = new MultiThreadIoEventLoopGroup(eventLoopThreads, NioIoHandler.newFactory());
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
