package com.vingame.bot.domain.botgroup.controller;

import com.vingame.bot.config.client.EnvironmentClientRegistry;
import com.vingame.bot.common.exception.RestExceptionHandler;
import com.vingame.bot.domain.bot.strategy.BettingStrategyFactory;
import com.vingame.bot.domain.bot.strategy.StrategyId;
import com.vingame.bot.domain.bot.strategy.WeightedStrategy;
import com.vingame.bot.domain.bot.strategy.slot.SlotStrategyFactory;
import com.vingame.bot.domain.bot.strategy.slot.SlotStrategyId;
import com.vingame.bot.domain.botgroup.mapper.BotGroupMapperImpl;
import com.vingame.bot.domain.botgroup.model.BotGroup;
import com.vingame.bot.domain.botgroup.repository.BotGroupRepository;
import com.vingame.bot.domain.botgroup.service.BotGroupBehaviorService;
import com.vingame.bot.domain.botgroup.service.BotGroupService;
import com.vingame.bot.domain.botgroup.validation.BettingMiniConfigValidator;
import com.vingame.bot.domain.botgroup.validation.BotGroupConfigValidationService;
import com.vingame.bot.domain.botgroup.validation.CardGameConfigValidator;
import com.vingame.bot.domain.botgroup.validation.GameConfigValidatorFactory;
import com.vingame.bot.domain.botgroup.validation.SlotConfigValidator;
import com.vingame.bot.domain.botgroup.validation.TaiXiuConfigValidator;
import com.vingame.bot.domain.botgroup.validation.UpDownConfigValidator;
import com.vingame.bot.domain.environment.service.EnvironmentService;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.domain.game.model.GameType;
import com.vingame.bot.domain.game.service.GameService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PLUGIN_HOT_RELOAD Phase 2b, AD-15 — the API contract this phase could
 * silently regress.
 *
 * <p>Before 2b, {@code strategyMix[].strategyId} and {@code slotStrategyId} were
 * enum-typed, so a body naming a key no bean claims failed Jackson's enum
 * deserialization and came back a <b>400</b>. Retyping them to {@code String}
 * deletes that guard <em>silently</em>: nothing fails, the bad key is persisted,
 * and the group blows up later on a bot thread at
 * {@code BettingStrategyFactory.create}. AD-15 moves the check into
 * {@code BotGroupConfigValidationService.validate}, which runs on <b>both</b>
 * create and PATCH. This test pins the status and the fact that a rejected
 * request persists nothing.
 *
 * <p><b>The registries here are the real ones.</b> {@link RealStrategyRegistries}
 * runs a component scan over the strategy package, so
 * {@code registeredKeys()} is the production catalogue rather than a stubbed
 * set — a mocked factory would let the test pass while the real key set was
 * empty. Only the leaf collaborators that would otherwise reach Mongo or the
 * auth gateway are mocked, as in {@code BotGroupConfigValidationIT}.
 *
 * <p><b>Why this is not in {@code BotGroupConfigValidationIT}.</b> That class is
 * the natural home, and it never runs: this build configures no failsafe plugin,
 * and surefire's default includes are {@code *Test} / {@code Test*} /
 * {@code *Tests} / {@code *TestCase}, so an {@code *IT} suffix means "compiled,
 * never executed". Pre-existing, and out of scope to change here — but a guard
 * for a persisted-identity change cannot live in a class that does not execute.
 */
@WebMvcTest(BotGroupController.class)
@Import({
        RestExceptionHandler.class,
        BotGroupService.class,
        BotGroupMapperImpl.class,
        BotGroupConfigValidationService.class,
        GameConfigValidatorFactory.class,
        BettingMiniConfigValidator.class,
        SlotConfigValidator.class,
        TaiXiuConfigValidator.class,
        CardGameConfigValidator.class,
        UpDownConfigValidator.class,
        BotGroupStrategyKeyValidationTest.RealStrategyRegistries.class
})
@DisplayName("Unknown strategy keys are a 400 on create and PATCH (AD-15)")
class BotGroupStrategyKeyValidationTest {

    /**
     * Real {@link BettingStrategyFactory} / {@link SlotStrategyFactory} over a
     * real component scan of the strategy package. {@code @RestController} is
     * excluded because {@code StrategyController} shares that package root in
     * bot-app and this slice only maps {@link BotGroupController}.
     */
    @TestConfiguration
    @ComponentScan(
            basePackages = "com.vingame.bot.domain.bot.strategy",
            excludeFilters = @ComponentScan.Filter(
                    type = FilterType.ANNOTATION, classes = RestController.class))
    static class RealStrategyRegistries {
    }

    private static final String BM_GAME = "game-bm";
    private static final String SLOT_GAME = "game-slot";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BettingStrategyFactory bettingStrategyFactory;

    @Autowired
    private SlotStrategyFactory slotStrategyFactory;

    @MockitoBean
    private BotGroupRepository repository;

    @MockitoBean
    private EnvironmentClientRegistry clientRegistry;

    @MockitoBean
    private EnvironmentService environmentService;

    @MockitoBean
    private MongoTemplate mongoTemplate;

    @MockitoBean
    private GameService gameService;

    @MockitoBean
    private BotGroupBehaviorService behaviorService;

    @BeforeEach
    void stubGames() {
        when(gameService.findById(BM_GAME))
                .thenReturn(Game.builder().id(BM_GAME).gameType(GameType.BETTING_MINI).build());
        when(gameService.findById(SLOT_GAME))
                .thenReturn(Game.builder().id(SLOT_GAME).gameType(GameType.SLOT).build());
        when(repository.save(any(BotGroup.class))).thenAnswer(inv -> inv.getArgument(0));
        // The @MockitoBean singletons are shared across the @Nested classes in
        // this slice's context, so save() invocations accumulate. The "nothing
        // was persisted" assertions below are per-test, so clear the ledger.
        clearInvocations(repository);
    }

    /**
     * A create body that is valid apart from whatever the caller injects. Built
     * as raw JSON rather than through {@code BotGroupDTO} so the test can send a
     * strategy key that no longer has a type to be rejected by — which is the
     * whole point of the phase.
     */
    private String createBody(String strategyMixJson, String slotStrategyIdJson) {
        return "{"
                + "\"name\":\"vtest\",\"environmentId\":\"env-1\",\"gameId\":\"" + BM_GAME + "\","
                + "\"namePrefix\":\"vt\",\"password\":\"secret\",\"botCount\":1,"
                + "\"existingGroup\":true,"
                + "\"minBet\":100,\"maxBet\":500,\"betIncrement\":10,"
                + "\"minBetsPerRound\":1,\"maxBetsPerRound\":5,\"maxTotalBetPerRound\":1000"
                + (strategyMixJson == null ? "" : ",\"strategyMix\":" + strategyMixJson)
                + (slotStrategyIdJson == null ? "" : ",\"slotStrategyId\":" + slotStrategyIdJson)
                + "}";
    }

    private BotGroup persistedBettingMiniGroup(String id) {
        return BotGroup.builder()
                .id(id).name("vtest").environmentId("env-1").gameId(BM_GAME)
                .namePrefix("vt").password("secret").botCount(1)
                .minBet(100L).maxBet(500L).betIncrement(10L)
                .minBetsPerRound(1).maxBetsPerRound(5).maxTotalBetPerRound(1000L)
                .strategyMix(List.of(new WeightedStrategy("RANDOM", 1.0)))
                .build();
    }

    @Nested
    @DisplayName("the registries the check runs against are the production ones")
    class Catalogue {

        @Test
        @DisplayName("every StrategyId / SlotStrategyId constant name is registered")
        void catalogueIsReal() {
            assertThat(bettingStrategyFactory.registeredKeys())
                    .containsExactlyInAnyOrderElementsOf(
                            Arrays.stream(StrategyId.values()).map(Enum::name).toList());
            assertThat(slotStrategyFactory.registeredKeys())
                    .containsExactlyInAnyOrderElementsOf(
                            Arrays.stream(SlotStrategyId.values()).map(Enum::name).toList());
        }
    }

    @Nested
    @DisplayName("POST /api/v1/bot-group/")
    class Create {

        @Test
        @DisplayName("an unknown strategyMix key is a 400 and nothing is saved")
        void unknownStrategyMixKeyRejected() throws Exception {
            mockMvc.perform(post("/api/v1/bot-group/")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody("[{\"strategyId\":\"NONSENSE\",\"weight\":1.0}]", null)))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().string(containsString("NONSENSE")));

            verify(repository, never()).save(any(BotGroup.class));
        }

        @Test
        @DisplayName("the 400 body lists the registered strategies so an operator can self-serve")
        void rejectionNamesTheCatalogue() throws Exception {
            mockMvc.perform(post("/api/v1/bot-group/")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody("[{\"strategyId\":\"NONSENSE\",\"weight\":1.0}]", null)))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().string(containsString("MARTINGALE_CLASSIC_CAUTIOUS")));
        }

        @Test
        @DisplayName("a bad key anywhere in the mix is caught, not just the first entry")
        void unknownKeyInLaterMixEntryRejected() throws Exception {
            mockMvc.perform(post("/api/v1/bot-group/")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody(
                                    "[{\"strategyId\":\"RANDOM\",\"weight\":1.0},"
                                            + "{\"strategyId\":\"NONSENSE\",\"weight\":1.0}]", null)))
                    .andExpect(status().isBadRequest());

            verify(repository, never()).save(any(BotGroup.class));
        }

        @Test
        @DisplayName("case matters — 'random' is rejected exactly as the enum used to reject it")
        void lowercaseKeyRejected() throws Exception {
            mockMvc.perform(post("/api/v1/bot-group/")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody("[{\"strategyId\":\"random\",\"weight\":1.0}]", null)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("an unknown slotStrategyId is a 400")
        void unknownSlotStrategyIdRejected() throws Exception {
            mockMvc.perform(post("/api/v1/bot-group/")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody(null, "\"NONSENSE\"")))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().string(containsString("slotStrategyId")));

            verify(repository, never()).save(any(BotGroup.class));
        }

        @Test
        @DisplayName("a known key is accepted — the check is not rejecting everything")
        void knownKeysAccepted() throws Exception {
            mockMvc.perform(post("/api/v1/bot-group/")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody(
                                    "[{\"strategyId\":\"MARTINGALE_CLASSIC_CAUTIOUS\",\"weight\":1.0}]",
                                    "\"FIXED\"")))
                    .andExpect(status().isOk());

            verify(repository).save(any(BotGroup.class));
        }

        @Test
        @DisplayName("omitting both fields is still accepted — null is not 'unknown'")
        void absentFieldsAccepted() throws Exception {
            mockMvc.perform(post("/api/v1/bot-group/")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody(null, null)))
                    .andExpect(status().isOk());

            verify(repository).save(any(BotGroup.class));
        }
    }

    @Nested
    @DisplayName("PATCH /api/v1/bot-group/{id}")
    class Patch {

        @Test
        @DisplayName("an unknown strategyMix key is a 400 and the group is not rewritten")
        void unknownStrategyMixKeyRejected() throws Exception {
            when(repository.findById("grp-1")).thenReturn(Optional.of(persistedBettingMiniGroup("grp-1")));

            mockMvc.perform(patch("/api/v1/bot-group/{id}", "grp-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"strategyMix\":[{\"strategyId\":\"NONSENSE\",\"weight\":1.0}]}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().string(containsString("NONSENSE")));

            // The rejection must not partially apply — verification P2-5.
            verify(repository, never()).save(any(BotGroup.class));
        }

        @Test
        @DisplayName("an unknown slotStrategyId is a 400 and the group is not rewritten")
        void unknownSlotStrategyIdRejected() throws Exception {
            when(repository.findById("grp-2")).thenReturn(Optional.of(persistedBettingMiniGroup("grp-2")));

            mockMvc.perform(patch("/api/v1/bot-group/{id}", "grp-2")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"slotStrategyId\":\"NONSENSE\"}"))
                    .andExpect(status().isBadRequest());

            verify(repository, never()).save(any(BotGroup.class));
        }

        @Test
        @DisplayName("a PATCH that touches neither field still passes — the persisted mix is re-validated and valid")
        void unrelatedPatchStillPasses() throws Exception {
            when(repository.findById("grp-3")).thenReturn(Optional.of(persistedBettingMiniGroup("grp-3")));

            mockMvc.perform(patch("/api/v1/bot-group/{id}", "grp-3")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"renamed\"}"))
                    .andExpect(status().isOk());

            verify(repository).save(any(BotGroup.class));
        }

        @Test
        @DisplayName("a valid key replaces the mix — the check does not block legitimate updates")
        void knownKeyAccepted() throws Exception {
            when(repository.findById("grp-4")).thenReturn(Optional.of(persistedBettingMiniGroup("grp-4")));

            mockMvc.perform(patch("/api/v1/bot-group/{id}", "grp-4")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"strategyMix\":[{\"strategyId\":\"FIBONACCI_AGGRESSIVE\",\"weight\":3.0}]}"))
                    .andExpect(status().isOk());

            verify(repository).save(any(BotGroup.class));
        }
    }
}
