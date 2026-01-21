package com.vingame.bot.config;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PreDestroy;

/**
 * Configuration for Netty EventLoopGroup used by WebSocket clients.
 * <p>
 * The EventLoopGroup is shared across all bot instances for optimal resource utilization.
 * Thread count is configurable via application.properties:
 * - Default profile: 4 threads (suitable for few hundred bots)
 * - Loadtest profile: 32 threads (suitable for hundreds of thousands of bots)
 */
@Slf4j
@Configuration
public class NettyEventLoopConfig {

    @Value("${websocket.eventloop.threads:4}")
    private int eventLoopThreads;

    private EventLoopGroup eventLoopGroup;

    @Bean
    public EventLoopGroup eventLoopGroup() {
        log.info("Creating Netty EventLoopGroup with {} threads", eventLoopThreads);
        eventLoopGroup = new NioEventLoopGroup(eventLoopThreads);
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
