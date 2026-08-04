package com.vingame.bot.domain.botgroup.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vingame.bot.common.exception.ResourceNotFoundException;
import com.vingame.bot.common.exception.RestExceptionHandler;
import com.vingame.bot.config.client.EnvironmentClientRegistry;
import com.vingame.bot.domain.botgroup.dto.BotGroupDTO;
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
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end MockMvc coverage for the bot-group config-validation chain
 * (Phase 5 of {@code docs/plans/BOT_GROUP_CONFIG_VALIDATION.md}).
 *
 * <p>Unlike {@code BotGroupControllerTest} — which mocks {@code BotGroupService}
 * to isolate the controller — this slice wires the <b>real</b> chain:
 * {@link BotGroupController} → {@link BotGroupService} → real
 * {@link BotGroupMapperImpl} (DTO→entity defaulting) →
 * {@link BotGroupConfigValidationService} → {@link GameConfigValidatorFactory} →
 * the per-{@link GameType} {@code GameConfigValidator}s, plus the universal
 * {@code @Validated(OnCreate)} Bean Validation layer and the
 * {@link RestExceptionHandler} 400 envelope. Only the leaf collaborators that
 * would otherwise reach Mongo / the auth gateway are mocked
 * ({@link BotGroupRepository}, {@link GameService}, {@link EnvironmentService},
 * {@link EnvironmentClientRegistry}, {@link MongoTemplate}), so the test exercises
 * the whole validation path without real I/O.
 *
 * <p>Every "valid" create uses {@code existingGroup=true} so the service skips
 * user registration entirely (no env-client lookup, no registration fan-out) —
 * the create therefore exercises validation and persistence, never the network.
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
        UpDownConfigValidator.class
})
@DisplayName("BotGroup config-validation (end-to-end)")
class BotGroupConfigValidationIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    // Leaf collaborators of the real BotGroupService — mocked so nothing reaches
    // Mongo or the auth gateway.
    @MockitoBean
    private BotGroupRepository repository;

    @MockitoBean
    private EnvironmentClientRegistry clientRegistry;

    @MockitoBean
    private EnvironmentService environmentService;

    @MockitoBean
    private MongoTemplate mongoTemplate;

    // Leaf collaborator of BotGroupConfigValidationService — mocked to resolve
    // gameId → GameType without Mongo.
    @MockitoBean
    private GameService gameService;

    // Not exercised here, but the controller depends on it.
    @MockitoBean
    private BotGroupBehaviorService behaviorService;

    private static final String BM_GAME = "game-bm";
    private static final String SLOT_GAME = "game-slot";
    private static final String TX_GAME = "game-tx";

    private void stubBettingMiniGame() {
        when(gameService.findById(BM_GAME))
                .thenReturn(Game.builder().id(BM_GAME).gameType(GameType.BETTING_MINI).build());
    }

    private void stubSlotGame() {
        when(gameService.findById(SLOT_GAME))
                .thenReturn(Game.builder().id(SLOT_GAME).gameType(GameType.SLOT).build());
    }

    private void stubTaiXiuGame() {
        when(gameService.findById(TX_GAME))
                .thenReturn(Game.builder().id(TX_GAME).gameType(GameType.TAI_XIU).build());
    }

    /** repository.save echoes its argument so the controller can map it back to a DTO. */
    private void stubRepositorySaveEcho() {
        when(repository.save(any(BotGroup.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    /**
     * A create-valid BETTING_MINI DTO. {@code existingGroup=true} so the service
     * skips registration (no network). minBet=100, maxBet=500, increment=10:
     * grid (500-100)%10==0 holds; all positive/order/cap rules satisfied.
     */
    private BotGroupDTO.BotGroupDTOBuilder validBettingMiniDto() {
        return BotGroupDTO.builder()
                .name("vtest")
                .environmentId("env-1")
                .gameId(BM_GAME)
                .namePrefix("vt")
                .password("secret")
                .botCount(1)
                .existingGroup(true)
                .minBet(100L)
                .maxBet(500L)
                .betIncrement(10L)
                .minBetsPerRound(1)
                .maxBetsPerRound(5)
                .maxTotalBetPerRound(1000L);
    }

    private String json(BotGroupDTO dto) throws Exception {
        return objectMapper.writeValueAsString(dto);
    }

    // ---------------------------------------------------------------------
    // Universal layer (POST) — @Validated(OnCreate) required-field basics.
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("Universal @Valid layer (POST)")
    class UniversalLayer {

        @Test
        @DisplayName("absent/blank basics → 400 listing every missing field, before the service runs")
        void missingBasicsRejected() throws Exception {
            // Everything blank/absent: environmentId, gameId, namePrefix, password
            // blank; botCount 0 (@Positive). The game-type chain never runs because
            // the universal layer short-circuits at the controller boundary.
            BotGroupDTO dto = BotGroupDTO.builder()
                    .name("incomplete")
                    .environmentId("  ")
                    .gameId("")
                    .namePrefix("")
                    .password("")
                    .botCount(0)
                    .build();

            mockMvc.perform(post("/api/v1/bot-group/")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dto)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("Bad request"))
                    .andExpect(jsonPath("$.msg").value(Matchers.allOf(
                            Matchers.containsString("environmentId must not be blank"),
                            Matchers.containsString("gameId must not be blank"),
                            Matchers.containsString("namePrefix must not be blank"),
                            Matchers.containsString("password must not be blank"),
                            Matchers.containsString("botCount must be >= 1"))));

            // The universal layer fires before the service / game-type chain.
            verify(repository, never()).save(any());
            verify(gameService, never()).findById(anyString());
        }

        @Test
        @DisplayName("fully-populated valid create → 200 (passes validation end to end)")
        void validCreateSucceeds() throws Exception {
            stubBettingMiniGame();
            stubRepositorySaveEcho();

            mockMvc.perform(post("/api/v1/bot-group/")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(validBettingMiniDto().build())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.gameId").value(BM_GAME))
                    .andExpect(jsonPath("$.id").value(Matchers.not(Matchers.emptyOrNullString())));

            // existingGroup=true ⇒ no registration fan-out.
            verify(clientRegistry, never()).getClients(anyString());
            verify(repository).save(any(BotGroup.class));
        }
    }

    // ---------------------------------------------------------------------
    // Game-type behavior layer — BETTING_MINI strict-grid rules.
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("Game-type layer (BETTING_MINI)")
    class BettingMiniLayer {

        @Test
        @DisplayName("minBet > maxBet → 400 with minBet/maxBet fragment")
        void minBetGreaterThanMaxBet() throws Exception {
            stubBettingMiniGame();

            BotGroupDTO dto = validBettingMiniDto()
                    .minBet(500L)
                    .maxBet(100L)
                    .build();

            mockMvc.perform(post("/api/v1/bot-group/")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dto)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("Bad request"))
                    .andExpect(jsonPath("$.msg").value(Matchers.allOf(
                            Matchers.containsString("Invalid bot-group config"),
                            Matchers.containsString("minBet (500) must be <= maxBet (100)"))));

            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("betIncrement = 0 → 400 with betIncrement fragment")
        void betIncrementZero() throws Exception {
            stubBettingMiniGame();

            BotGroupDTO dto = validBettingMiniDto()
                    .betIncrement(0L)
                    .build();

            mockMvc.perform(post("/api/v1/bot-group/")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dto)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("Bad request"))
                    .andExpect(jsonPath("$.msg").value(
                            Matchers.containsString("betIncrement (0) must be > 0")));

            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("multiple violations (off-grid, maxTotal<maxBet, inverted bet counts) → 400 listing all")
        void multipleViolationsAggregated() throws Exception {
            stubBettingMiniGame();

            // off-grid: (505-100)%10 != 0; maxTotal (50) < maxBet (505);
            // inverted bet counts: minBetsPerRound (5) > maxBetsPerRound (2).
            BotGroupDTO dto = validBettingMiniDto()
                    .minBet(100L)
                    .maxBet(505L)
                    .betIncrement(10L)
                    .minBetsPerRound(5)
                    .maxBetsPerRound(2)
                    .maxTotalBetPerRound(50L)
                    .build();

            mockMvc.perform(post("/api/v1/bot-group/")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dto)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("Bad request"))
                    .andExpect(jsonPath("$.msg").value(Matchers.allOf(
                            Matchers.containsString("must be exactly divisible by betIncrement"),
                            Matchers.containsString("maxTotalBetPerRound (50) must be >= maxBet (505)"),
                            Matchers.containsString("minBetsPerRound (5) must be <= maxBetsPerRound (2)"))));

            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("valid betting-mini config → 200 (passes the validator)")
        void validConfigSucceeds() throws Exception {
            stubBettingMiniGame();
            stubRepositorySaveEcho();

            mockMvc.perform(post("/api/v1/bot-group/")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(validBettingMiniDto().build())))
                    .andExpect(status().isOk());

            verify(repository).save(any(BotGroup.class));
        }

        @Test
        @DisplayName("zero-min boundary (minBet=0, minBetsPerRound=0) → 200")
        void zeroMinBoundarySucceeds() throws Exception {
            stubBettingMiniGame();
            stubRepositorySaveEcho();

            // grid (100-0)%10==0; zero is valid for the minimum fields (AD-3).
            BotGroupDTO dto = validBettingMiniDto()
                    .minBet(0L)
                    .minBetsPerRound(0)
                    .maxBet(100L)
                    .betIncrement(10L)
                    .maxBetsPerRound(5)
                    .maxTotalBetPerRound(1000L)
                    .build();

            mockMvc.perform(post("/api/v1/bot-group/")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dto)))
                    .andExpect(status().isOk());

            verify(repository).save(any(BotGroup.class));
        }
    }

    // ---------------------------------------------------------------------
    // SLOT / permissive types — betting fields ignored.
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("SLOT (permissive on betting fields)")
    class SlotLayer {

        @Test
        @DisplayName("SLOT create with betting fields BETTING_MINI would reject → 200 (ignored, AD-4)")
        void slotIgnoresGarbageBettingFields() throws Exception {
            stubSlotGame();
            stubRepositorySaveEcho();

            // minBet>maxBet, betIncrement=0, inverted/zero round counts, cap below
            // maxBet — every BETTING_MINI rule violated, all irrelevant for SLOT.
            BotGroupDTO dto = BotGroupDTO.builder()
                    .name("slot-group")
                    .environmentId("env-1")
                    .gameId(SLOT_GAME)
                    .namePrefix("st")
                    .password("secret")
                    .botCount(1)
                    .existingGroup(true)
                    .minBet(999L)
                    .maxBet(1L)
                    .betIncrement(0L)
                    .minBetsPerRound(-5)
                    .maxBetsPerRound(0)
                    .maxTotalBetPerRound(0L)
                    .build();

            mockMvc.perform(post("/api/v1/bot-group/")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dto)))
                    .andExpect(status().isOk());

            verify(repository).save(any(BotGroup.class));
        }
    }

    // ---------------------------------------------------------------------
    // TAI_XIU — same STRICT-GRID rules as BETTING_MINI (TAI_XIU_BOT AD-8).
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("TAI_XIU (strict-grid, same as BETTING_MINI)")
    class TaiXiuLayer {

        /**
         * Same betting-field shape as {@link #validBettingMiniDto()} but pointed at a
         * TAI_XIU game. Used to prove the grid is now enforced for Tai Xiu too.
         */
        private BotGroupDTO.BotGroupDTOBuilder validTaiXiuDto() {
            return BotGroupDTO.builder()
                    .name("txtest")
                    .environmentId("env-1")
                    .gameId(TX_GAME)
                    .namePrefix("tx")
                    .password("secret")
                    .botCount(1)
                    .existingGroup(true)
                    .minBet(100L)
                    .maxBet(500L)
                    .betIncrement(10L)
                    .minBetsPerRound(1)
                    .maxBetsPerRound(5)
                    .maxTotalBetPerRound(1000L);
        }

        @Test
        @DisplayName("TAI_XIU minBet > maxBet → 400 (grid now enforced, AD-8)")
        void taiXiuMinBetGreaterThanMaxBet() throws Exception {
            stubTaiXiuGame();

            BotGroupDTO dto = validTaiXiuDto()
                    .minBet(500L)
                    .maxBet(100L)
                    .build();

            mockMvc.perform(post("/api/v1/bot-group/")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dto)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("Bad request"))
                    .andExpect(jsonPath("$.msg").value(Matchers.allOf(
                            Matchers.containsString("Invalid bot-group config"),
                            Matchers.containsString("minBet (500) must be <= maxBet (100)"))));

            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("TAI_XIU off-grid config that SLOT would have ignored → 400 (no longer permissive, AD-8)")
        void taiXiuOffGridRejected() throws Exception {
            stubTaiXiuGame();

            // (505-100)%10 != 0 — off-grid. Previously the no-op validator let this through.
            BotGroupDTO dto = validTaiXiuDto()
                    .minBet(100L)
                    .maxBet(505L)
                    .betIncrement(10L)
                    .build();

            mockMvc.perform(post("/api/v1/bot-group/")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dto)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("Bad request"))
                    .andExpect(jsonPath("$.msg").value(
                            Matchers.containsString("must be exactly divisible by betIncrement")));

            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("valid TAI_XIU config → 200 (passes the shared grid rules)")
        void validTaiXiuConfigSucceeds() throws Exception {
            stubTaiXiuGame();
            stubRepositorySaveEcho();

            mockMvc.perform(post("/api/v1/bot-group/")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(validTaiXiuDto().build())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.gameId").value(TX_GAME));

            verify(repository).save(any(BotGroup.class));
        }
    }

    // ---------------------------------------------------------------------
    // PATCH path — post-merge game-type validation, no universal layer.
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("PATCH path")
    class PatchLayer {

        @Test
        @DisplayName("PATCH that drives the merged betting-mini entity invalid → 400 (post-merge validation, AD-6)")
        void patchMakesMergedEntityInvalid() throws Exception {
            stubBettingMiniGame();

            // Persisted group is valid: minBet=100, maxBet=500.
            BotGroup persisted = BotGroup.builder()
                    .id("grp-1")
                    .name("existing")
                    .environmentId("env-1")
                    .gameId(BM_GAME)
                    .namePrefix("vt")
                    .password("secret")
                    .botCount(1)
                    .minBet(100L)
                    .maxBet(500L)
                    .betIncrement(10L)
                    .minBetsPerRound(1)
                    .maxBetsPerRound(5)
                    .maxTotalBetPerRound(1000L)
                    .build();
            when(repository.findById("grp-1")).thenReturn(Optional.of(persisted));

            // PATCH only maxBet=50, below the persisted minBet=100.
            BotGroupDTO patch = BotGroupDTO.builder().maxBet(50L).build();

            mockMvc.perform(patch("/api/v1/bot-group/{id}", "grp-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(patch)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("Bad request"))
                    .andExpect(jsonPath("$.msg").value(
                            Matchers.containsString("minBet (100) must be <= maxBet (50)")));

            // Invalid merge never persisted.
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("partial PATCH omitting universal basics → 200 (no @NotBlank layer on PATCH)")
        void patchPartialNotSubjectToUniversalLayer() throws Exception {
            stubBettingMiniGame();
            stubRepositorySaveEcho();

            // Persisted group is valid; the PATCH body omits environmentId,
            // namePrefix, password, botCount entirely — proving OnCreate scoping
            // (the universal @NotBlank/@Positive layer does NOT apply to PATCH).
            BotGroup persisted = BotGroup.builder()
                    .id("grp-2")
                    .name("existing")
                    .environmentId("env-1")
                    .gameId(BM_GAME)
                    .namePrefix("vt")
                    .password("secret")
                    .botCount(1)
                    .minBet(100L)
                    .maxBet(500L)
                    .betIncrement(10L)
                    .minBetsPerRound(1)
                    .maxBetsPerRound(5)
                    .maxTotalBetPerRound(1000L)
                    .build();
            when(repository.findById("grp-2")).thenReturn(Optional.of(persisted));

            // A valid in-range tweak: raise maxBet to 600 ((600-100)%10==0).
            BotGroupDTO patch = BotGroupDTO.builder().maxBet(600L).build();

            mockMvc.perform(patch("/api/v1/bot-group/{id}", "grp-2")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(patch)))
                    .andExpect(status().isOk());

            verify(repository).save(any(BotGroup.class));
        }
    }

    // ---------------------------------------------------------------------
    // Orchestrator — unknown / blank gameId → 400.
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("Orchestrator gameId resolution")
    class GameIdResolution {

        @Test
        @DisplayName("unknown gameId → 400 (not 404) referencing the gameId (AD-7)")
        void unknownGameIdRejected() throws Exception {
            // gameId present (universal layer passes), but unresolvable: GameService
            // throws ResourceNotFoundException, which the orchestrator rethrows as a
            // BadRequestException (400).
            when(gameService.findById("does-not-exist"))
                    .thenThrow(new ResourceNotFoundException("Game not found with id: does-not-exist"));

            BotGroupDTO dto = validBettingMiniDto().gameId("does-not-exist").build();

            mockMvc.perform(post("/api/v1/bot-group/")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(dto)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("Bad request"))
                    .andExpect(jsonPath("$.msg").value(
                            Matchers.containsString("does-not-exist")));

            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("blank gameId on PATCH-merged entity → 400 (no game type to resolve)")
        void blankGameIdOnPatchRejected() throws Exception {
            // A persisted group whose gameId is blank: the post-merge validator
            // surfaces "gameId is required" before a validator can be selected.
            BotGroup persisted = BotGroup.builder()
                    .id("grp-3")
                    .name("no-game")
                    .environmentId("env-1")
                    .gameId("")
                    .build();
            when(repository.findById("grp-3")).thenReturn(Optional.of(persisted));

            BotGroupDTO patch = BotGroupDTO.builder().name("renamed").build();

            mockMvc.perform(patch("/api/v1/bot-group/{id}", "grp-3")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(patch)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("Bad request"))
                    .andExpect(jsonPath("$.msg").value(
                            Matchers.containsString("gameId is required")));

            verify(repository, never()).save(any());
        }
    }
}
