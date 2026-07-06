package com.vingame.bot.domain.botgroup.service;

import com.vingame.bot.config.client.EnvironmentClientRegistry;
import com.vingame.bot.domain.botgroup.mapper.BotGroupMapper;
import com.vingame.bot.domain.botgroup.model.BotGroup;
import com.vingame.bot.domain.botgroup.repository.BotGroupRepository;
import com.vingame.bot.domain.botgroup.validation.BotGroupConfigValidationService;
import com.vingame.bot.domain.environment.mapper.EnvironmentMapper;
import com.vingame.bot.domain.environment.repository.EnvironmentRepository;
import com.vingame.bot.domain.environment.service.EnvironmentService;
import com.vingame.bot.domain.game.mapper.GameMapper;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.domain.game.repository.GameRepository;
import com.vingame.bot.domain.game.service.GameService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * End-to-end cascade-delete wiring test (BOTGROUP_GAME_MANAGEMENT Phase 7, AD-15).
 * <p>
 * Unlike {@link EnvironmentServiceTest}/{@link GameServiceTest}, which re-mock the
 * downstream {@code service.delete(...)} and therefore only prove the top-level
 * delegation, this test wires the <b>real</b> {@link EnvironmentService},
 * {@link GameService} and {@link BotGroupService} together (repositories and the
 * behaviour service are the only mocks) so the actual production cascade chain
 * Environment → Games → BotGroups is exercised through every hop. That is what
 * proves the "no orphan left behind" and "full chain deletes everything"
 * guarantees for real.
 * <p>
 * The {@code @Lazy} back-references that break the Spring cycles at runtime are
 * mirrored here by constructing {@link BotGroupService} with a <i>mock</i>
 * {@link GameService} (its delete path never touches {@code gameService}) and
 * handing the <i>real</i> {@link BotGroupService} to the real {@link GameService}
 * and {@link EnvironmentService} — so no construction cycle is needed.
 */
@DisplayName("Cascade delete — full Environment → Games → BotGroups chain")
class CascadeDeleteChainTest {

    private GameRepository gameRepo;
    private BotGroupRepository botGroupRepo;
    private EnvironmentRepository envRepo;
    private BotGroupBehaviorService behaviorService;

    private GameService gameService;
    private BotGroupService botGroupService;
    private EnvironmentService environmentService;

    @BeforeEach
    void wireRealServices() {
        gameRepo = mock(GameRepository.class);
        botGroupRepo = mock(BotGroupRepository.class);
        envRepo = mock(EnvironmentRepository.class);
        behaviorService = mock(BotGroupBehaviorService.class);

        // BotGroupService.delete only uses behaviorService + repository, so every
        // other collaborator can be an unused mock (the GameService here is a mock
        // to avoid a construction cycle — the real GameService below points back at
        // this same BotGroupService instance).
        botGroupService = new BotGroupService(
                botGroupRepo,
                mock(BotGroupMapper.class),
                mock(EnvironmentClientRegistry.class),
                mock(EnvironmentService.class),
                mock(GameService.class),
                mock(org.springframework.data.mongodb.core.MongoTemplate.class),
                mock(BotGroupConfigValidationService.class),
                behaviorService);

        gameService = new GameService(
                gameRepo,
                mock(GameMapper.class),
                mock(org.springframework.data.mongodb.core.MongoTemplate.class),
                botGroupService);

        environmentService = new EnvironmentService(
                envRepo,
                mock(EnvironmentMapper.class),
                mock(org.springframework.data.mongodb.core.MongoTemplate.class),
                gameService,
                botGroupService);
    }

    @Test
    @DisplayName("Deleting an env stops+logs-out+deletes each group of each game, then the game, then the env — in order")
    void fullChainDeletesEverythingInOrder() {
        // env-1 owns game-a; game-a is referenced by group-1.
        when(gameRepo.findByEnvironmentId("env-1"))
                .thenReturn(List.of(Game.builder().id("game-a").environmentId("env-1").build()));
        when(botGroupRepo.findByGameId("game-a"))
                .thenReturn(List.of(BotGroup.builder().id("group-1").gameId("game-a").environmentId("env-1").build()));
        // After the game cascade removed group-1, the env-scoped orphan sweep finds nothing.
        when(botGroupRepo.findByEnvironmentId("env-1")).thenReturn(List.of());

        environmentService.delete("env-1");

        // The real delegation chain must fire: group stopped+logged-out, group doc
        // deleted, game doc deleted, env doc deleted — strictly in that order so no
        // parent is removed while a child still references it (AD-15).
        var inOrder = inOrder(behaviorService, botGroupRepo, gameRepo, envRepo);
        inOrder.verify(behaviorService).stopAndLogout("group-1");
        inOrder.verify(botGroupRepo).deleteById("group-1");
        inOrder.verify(gameRepo).deleteById("game-a");
        inOrder.verify(envRepo).deleteById("env-1");
    }

    @Test
    @DisplayName("A group that references the env directly (no game / cross-env game) is swept before the env — no orphan")
    void orphanGroupSweptBeforeEnv() {
        // env-1 has no games, but a group still points at it (e.g. null gameId, or a
        // gameId that resolves to a game in another env). It must be torn down and
        // deleted before the env document is removed.
        when(gameRepo.findByEnvironmentId("env-1")).thenReturn(List.of());
        when(botGroupRepo.findByEnvironmentId("env-1"))
                .thenReturn(List.of(BotGroup.builder().id("orphan-group").environmentId("env-1").build()));

        environmentService.delete("env-1");

        var inOrder = inOrder(behaviorService, botGroupRepo, envRepo);
        inOrder.verify(behaviorService).stopAndLogout("orphan-group");
        inOrder.verify(botGroupRepo).deleteById("orphan-group");
        inOrder.verify(envRepo).deleteById("env-1");
    }

    @Test
    @DisplayName("Idempotent for already-stopped groups: a no-op stopAndLogout still deletes the group and cascades")
    void idempotentWhenGroupsAlreadyStopped() {
        // Model an already-stopped group: stopAndLogout is a no-op for a group that
        // is not running (verified in BotGroupBehaviorServiceTest). The delete must
        // still remove the document and complete the cascade.
        when(gameRepo.findByEnvironmentId("env-1"))
                .thenReturn(List.of(Game.builder().id("game-a").environmentId("env-1").build()));
        when(botGroupRepo.findByGameId("game-a"))
                .thenReturn(List.of(BotGroup.builder().id("stopped-group").gameId("game-a").build()));
        when(botGroupRepo.findByEnvironmentId("env-1")).thenReturn(List.of());
        // behaviorService.stopAndLogout is a plain mock → does nothing (the no-op path).

        environmentService.delete("env-1");

        verify(behaviorService).stopAndLogout("stopped-group");
        verify(botGroupRepo).deleteById("stopped-group");
        verify(gameRepo).deleteById("game-a");
        verify(envRepo).deleteById("env-1");
    }

    @Test
    @DisplayName("Deleting a game with no referencing groups deletes only the game (no stray group teardown)")
    void gameWithNoGroupsDeletesOnlyGame() {
        when(botGroupRepo.findByGameId("game-solo")).thenReturn(List.of());

        gameService.delete("game-solo");

        verify(gameRepo).deleteById("game-solo");
        verify(behaviorService, never()).stopAndLogout(anyString());
        verify(botGroupRepo, never()).deleteById(anyString());
    }
}
