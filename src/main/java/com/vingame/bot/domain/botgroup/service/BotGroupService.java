package com.vingame.bot.domain.botgroup.service;

import com.vingame.bot.config.client.EnvironmentClientRegistry;
import com.vingame.bot.config.client.EnvironmentClients;
import com.vingame.bot.domain.botgroup.dto.BotGroupDTO;
import com.vingame.bot.common.exception.ResourceNotFoundException;
import com.vingame.bot.domain.botgroup.mapper.BotGroupMapper;
import com.vingame.bot.domain.botgroup.model.BotGroup;
import com.vingame.bot.domain.botgroup.model.BotGroupFilter;
import com.vingame.bot.infrastructure.client.dto.UserRegistrationResult;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class BotGroupService {

    private final List<BotGroup> botGroups = new ArrayList<>();
    private final BotGroupMapper mapper;
    private final EnvironmentClientRegistry clientRegistry;
    private final AtomicInteger idGenerator = new AtomicInteger(1);

    public BotGroupService(BotGroupMapper mapper, EnvironmentClientRegistry clientRegistry) {
        this.mapper = mapper;
        this.clientRegistry = clientRegistry;
    }

    @PostConstruct
    public void prepopulateBotGroups() {
        botGroups.add(BotGroup.builder()
                .id("111")
                .name("Test group 097")
                .environmentId("3cda38f9-2c3d-465f-a52a-18ce83207768")
                .namePrefix("bcB0Ttest")
                .password("123bcB0Ttest123")
                .gameId("3cda38f9-2c3d-465f-a52a-18ce83207761")
                .botCount(1)
                .minBet(5_000L)
                .maxBet(100_000L)
                .betIncrement(5_000L)
                .maxTotalBetPerRound(2_000_000L)
                .minBetsPerRound(0)
                .maxBetsPerRound(25)
                .timeBased(false)
                .chatEnabled(false)
                .autoDepositEnabled(true)
                .build());

        botGroups.add(BotGroup.builder()
                .id("222")
                .name("Test group 118")
                .environmentId("3cda38f9-2c3d-465f-a52a-18ce83207769")
                .namePrefix("gleb00") //TODO
                .password("123123") //TODO
                .gameId("3cda38f9-2c3d-465f-a52a-18ce83207762")
                .botCount(1)
                .minBet(5_000L)
                .maxBet(100_000L)
                .betIncrement(5_000L)
                .maxTotalBetPerRound(2_000_000L)
                .minBetsPerRound(0)
                .maxBetsPerRound(25)
                .timeBased(false)
                .chatEnabled(false)
                .autoDepositEnabled(true)
                .build());
    }

    public BotGroup findById(int id) {
        return botGroups.stream()
                .filter(bg -> bg.getId().equals(String.valueOf(id)))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("BotGroup not found"));
    }

    public List<BotGroup> findAll() {
        return botGroups;
    }

    public List<BotGroup> filter(BotGroupFilter filter) {
        return botGroups.stream()
                .filter(bg -> filter.getEnvironmentId() == null || bg.getEnvironmentId().equals(filter.getEnvironmentId()))
                .filter(bg -> filter.getName() == null || bg.getName().equalsIgnoreCase(filter.getName()))
                .filter(bg -> filter.getGameId() == null || filter.getGameId().equals(bg.getGameId()))
                .toList();
    }

    /**
     * Save or update a bot group.
     * <p>
     * If the bot group is NEW (ID is null):
     * - Registers all bot users on the authentication server
     * - Generates a new ID
     * - Adds to the bot groups list
     * - Throws exception if user registration completely fails
     * <p>
     * If the bot group ALREADY EXISTS (ID is set):
     * - Skips user registration (users already registered)
     * - Updates the existing entry in place
     * - No new ID generation
     *
     * @param botGroup The bot group to save or update
     * @return The saved/updated bot group
     * @throws IllegalStateException if user registration completely fails (new groups only)
     * @throws ResourceNotFoundException if the environment doesn't exist
     */
    public BotGroup save(BotGroup botGroup) {
        // Check if this is a new bot group or an update to an existing one
        boolean isNewGroup = (botGroup.getId() == null || botGroup.getId().isEmpty());

        if (isNewGroup) {
            // NEW BOT GROUP - Register users and add to list
            log.info("Creating new bot group '{}' with {} bots in environment {}",
                     botGroup.getName(), botGroup.getBotCount(), botGroup.getEnvironmentId());

            // Get the environment's ApiGatewayClient to register users
            EnvironmentClients clients = clientRegistry.getClients(botGroup.getEnvironmentId());

            // Register bot users on the auth server
            log.info("Registering {} users with prefix '{}' and password '{}'",
                     botGroup.getBotCount(), botGroup.getNamePrefix(), botGroup.getPassword());

            UserRegistrationResult registrationResult = clients.getApiGatewayClient().registerUsers(
                    botGroup.getNamePrefix(),
                    botGroup.getPassword(),
                    botGroup.getBotCount());

            // Handle registration results
            if (registrationResult.isCompleteFailure()) {
                String errorMsg = String.format(
                    "Failed to register any users for bot group '%s'. Errors: %s",
                    botGroup.getName(),
                    String.join("; ", registrationResult.getErrors())
                );
                log.error(errorMsg);
                throw new IllegalStateException(errorMsg);
            }

            if (registrationResult.isPartialSuccess()) {
                log.warn("Partial user registration for bot group '{}': {}/{} succeeded. Errors: {}",
                         botGroup.getName(),
                         registrationResult.getSuccessCount(),
                         registrationResult.getTotalRequested(),
                         String.join("; ", registrationResult.getErrors()));
            } else {
                log.info("Successfully registered all {} users for bot group '{}'",
                         registrationResult.getSuccessCount(),
                         botGroup.getName());
            }

            // Generate ID and add to list
            botGroup.setId(String.valueOf(idGenerator.getAndIncrement()));
            botGroups.add(botGroup);

            log.info("Bot group '{}' created with ID {}", botGroup.getName(), botGroup.getId());
        } else {
            // EXISTING BOT GROUP - Just update in place (no user registration)
            log.debug("Updating existing bot group '{}' (ID: {})", botGroup.getName(), botGroup.getId());
            // The bot group object is already in the list, just update its fields
            // No need to re-add or re-register users
        }

        return botGroup;
    }

    public BotGroup update(int id, BotGroupDTO updateDTO) {
        BotGroup existing = findById(id);
        mapper.updateEntityFromDTO(updateDTO, existing);
        return existing;
    }

    public void delete(int id) {
        botGroups.removeIf(bg -> bg.getId().equals(String.valueOf(id)));
    }
}

/*
2026-01-08T15:27:19.566+04:00 ERROR 42275 --- [ntLoopGroup-3-1] c.v.w.NettyVingameWebSocketClient        : Client null: Error: Connection reset

java.net.SocketException: Connection reset
	at java.base/sun.nio.ch.SocketChannelImpl.throwConnectionReset(SocketChannelImpl.java:401) ~[na:na]
	at java.base/sun.nio.ch.SocketChannelImpl.read(SocketChannelImpl.java:434) ~[na:na]
	at io.netty.buffer.PooledByteBuf.setBytes(PooledByteBuf.java:255) ~[netty-buffer-4.1.115.Final.jar:4.1.115.Final]
	at io.netty.buffer.AbstractByteBuf.writeBytes(AbstractByteBuf.java:1132) ~[netty-buffer-4.1.115.Final.jar:4.1.115.Final]
	at io.netty.channel.socket.nio.NioSocketChannel.doReadBytes(NioSocketChannel.java:356) ~[netty-transport-4.1.115.Final.jar:4.1.115.Final]
	at io.netty.channel.nio.AbstractNioByteChannel$NioByteUnsafe.read(AbstractNioByteChannel.java:151) ~[netty-transport-4.1.115.Final.jar:4.1.115.Final]
	at io.netty.channel.nio.NioEventLoop.processSelectedKey(NioEventLoop.java:788) ~[netty-transport-4.1.115.Final.jar:4.1.115.Final]
	at io.netty.channel.nio.NioEventLoop.processSelectedKeysOptimized(NioEventLoop.java:724) ~[netty-transport-4.1.115.Final.jar:4.1.115.Final]
	at io.netty.channel.nio.NioEventLoop.processSelectedKeys(NioEventLoop.java:650) ~[netty-transport-4.1.115.Final.jar:4.1.115.Final]
	at io.netty.channel.nio.NioEventLoop.run(NioEventLoop.java:562) ~[netty-transport-4.1.115.Final.jar:4.1.115.Final]
	at io.netty.util.concurrent.SingleThreadEventExecutor$4.run(SingleThreadEventExecutor.java:997) ~[netty-common-4.1.115.Final.jar:4.1.115.Final]
	at io.netty.util.internal.ThreadExecutorMap$2.run(ThreadExecutorMap.java:74) ~[netty-common-4.1.115.Final.jar:4.1.115.Final]
	at io.netty.util.concurrent.FastThreadLocalRunnable.run(FastThreadLocalRunnable.java:30) ~[netty-common-4.1.115.Final.jar:4.1.115.Final]
	at java.base/java.lang.Thread.run(Thread.java:1583) ~[na:na]

* */


