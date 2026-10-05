package com.vingame.bot.domain.botgroup.controller;

import com.vingame.bot.common.exception.RestExceptionHandler;
import com.vingame.bot.config.client.EnvironmentClientRegistry;
import com.vingame.bot.domain.bot.strategy.WeightedStrategy;
import com.vingame.bot.domain.botgroup.mapper.BotGroupMapperImpl;
import com.vingame.bot.domain.botgroup.model.BotGroup;
import com.vingame.bot.domain.botgroup.repository.BotGroupRepository;
import com.vingame.bot.domain.botgroup.service.BotGroupBehaviorService;
import com.vingame.bot.domain.botgroup.service.RegistrationWorker;
import com.vingame.bot.domain.botgroup.service.BotGroupService;
import com.vingame.bot.domain.botgroup.validation.BettingMiniConfigValidator;
import com.vingame.bot.domain.botgroup.validation.BotGroupConfigValidationService;
import com.vingame.bot.domain.botgroup.validation.CardGameConfigValidator;
import com.vingame.bot.domain.botgroup.validation.CashoutConfigValidator;
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

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PLUGIN_HOT_RELOAD Phase 2b, AD-15 — the input classes that
 * {@code BotGroupStrategyKeyValidationTest} does not cover.
 *
 * <p>AD-15's contract is that the explicit check "rejects the same input set the
 * enum deserializer did". That is a claim about a <b>set</b>, so it is only
 * verified by probing the boundary, not by re-testing {@code "NONSENSE"}. QA
 * probed the actual pre-change deserializer (branch {@code feature/plugin-hot-reload}
 * at {@code c3fac8a}, jackson-databind <b>2.15.2</b> — note the version this
 * application really resolves, which is not Spring Boot 3.4.0's managed 2.18.1)
 * against the enum-typed {@code BotGroupDTO}. The results:
 *
 * <table>
 *   <caption>pre-change vs post-change disposition</caption>
 *   <tr><th>body fragment</th><th>enum-typed (c3fac8a)</th><th>String-typed (2b)</th></tr>
 *   <tr><td>{@code "slotStrategyId":""}</td><td>400 (cannot coerce empty String)</td><td>400</td></tr>
 *   <tr><td>{@code "slotStrategyId":"   "}</td><td>400 (trimmed, then empty)</td><td>400</td></tr>
 *   <tr><td>{@code "strategyId":"random"}</td><td>400 (case-sensitive)</td><td>400</td></tr>
 *   <tr><td>{@code "strategyId":"RANDOM "}</td><td>400 (trailing space)</td><td>400</td></tr>
 *   <tr><td>{@code "slotStrategyId":0}</td><td><b>200</b> — Jackson maps the ordinal to {@code FIXED}</td><td><b>400</b></td></tr>
 *   <tr><td>{@code "strategyId":0}</td><td><b>200</b> — ordinal maps to {@code RANDOM}</td><td><b>400</b></td></tr>
 *   <tr><td>{@code "strategyId":null}</td><td><b>200</b> — persisted as a null key</td><td><b>400</b></td></tr>
 *   <tr><td>{@code strategyId} omitted</td><td><b>200</b> — persisted as a null key</td><td><b>400</b></td></tr>
 * </table>
 *
 * <p><b>The bottom four rows are behaviour changes</b> — 200 becomes 400 — and
 * AD-23 says Phase 2 changes no behaviour. QA's judgement is that they are
 * acceptable and should ship: enum-by-ordinal binding is a Jackson accident no
 * client can be using (the DTO has always <em>emitted</em> names, so no
 * round-trip produces an ordinal), and a null key was only ever accepted into
 * persistence to blow up later on a bot thread at
 * {@code BettingStrategyFactory.create}. They are pinned here so that the
 * narrowing is a recorded decision rather than something a future reader
 * rediscovers from a support ticket.
 *
 * <p>The class also pins the one operational consequence of running the check on
 * the <b>post-merge</b> group: see {@link PersistedKeyNoLongerRegistered}.
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
        CashoutConfigValidator.class,
        BotGroupStrategyKeyCoercionTest.RealStrategyRegistries.class
})
@DisplayName("Strategy-key validation: the coercions Jackson used to perform (AD-15)")
class BotGroupStrategyKeyCoercionTest {

    /** Real registries over a real scan — a stub would pass against an empty catalogue. */
    @TestConfiguration
    @ComponentScan(
            basePackages = "com.vingame.bot.domain.bot.strategy",
            excludeFilters = @ComponentScan.Filter(
                    type = FilterType.ANNOTATION, classes = RestController.class))
    static class RealStrategyRegistries {
    }

    private static final String BM_GAME = "game-bm";

    @Autowired
    private MockMvc mockMvc;

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

    /**
     * Required since GATEWAY_REQUEST_BUDGET Phase 4: {@code BotGroupService} takes the
     * registration worker as a constructor dependency, so this slice cannot build without it.
     * Unused by these tests — validation happens before anything is enqueued.
     */
    @MockitoBean
    private RegistrationWorker registrationWorker;

    @MockitoBean
    private com.vingame.bot.domain.botgroup.service.DepositLedger depositLedger;

    @BeforeEach
    void stubGames() {
        when(gameService.findById(BM_GAME))
                .thenReturn(Game.builder().id(BM_GAME).gameType(GameType.BETTING_MINI).build());
        when(repository.save(any(BotGroup.class))).thenAnswer(inv -> inv.getArgument(0));
        clearInvocations(repository);
    }

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

    private void expectCreateRejected(String strategyMixJson, String slotStrategyIdJson) throws Exception {
        mockMvc.perform(post("/api/v1/bot-group/")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(strategyMixJson, slotStrategyIdJson)))
                .andExpect(status().isBadRequest());
        verify(repository, never()).save(any(BotGroup.class));
    }

    @Nested
    @DisplayName("blank and near-miss keys — unchanged from the enum deserializer")
    class Blanks {

        @Test
        @DisplayName("an empty strategyMix key is a 400, as the enum's empty-String coercion failure was")
        void emptyMixKeyRejected() throws Exception {
            expectCreateRejected("[{\"strategyId\":\"\",\"weight\":1.0}]", null);
        }

        @Test
        @DisplayName("a whitespace-only strategyMix key is a 400 — Jackson trimmed it to empty and also failed")
        void blankMixKeyRejected() throws Exception {
            expectCreateRejected("[{\"strategyId\":\"   \",\"weight\":1.0}]", null);
        }

        @Test
        @DisplayName("a key with a trailing space is a 400 — no trimming is performed on either side")
        void untrimmedMixKeyRejected() throws Exception {
            expectCreateRejected("[{\"strategyId\":\"RANDOM \",\"weight\":1.0}]", null);
        }

        @Test
        @DisplayName("an empty slotStrategyId is a 400, not a silent fall-back to FIXED")
        void emptySlotStrategyIdRejected() throws Exception {
            // "" must not be conflated with null. Null means "fall back to FIXED
            // at bot-build time"; "" is a key nothing claims.
            expectCreateRejected(null, "\"\"");
        }

        @Test
        @DisplayName("a whitespace-only slotStrategyId is a 400")
        void blankSlotStrategyIdRejected() throws Exception {
            expectCreateRejected(null, "\"   \"");
        }
    }

    @Nested
    @DisplayName("inputs the enum deserializer accepted and the string check now rejects")
    class NarrowedInputs {

        @Test
        @DisplayName("an ordinal slotStrategyId is now a 400 — it used to bind to FIXED")
        void ordinalSlotStrategyIdRejected() throws Exception {
            // Pre-change: {"slotStrategyId":0} deserialised to SlotStrategyId.FIXED
            // by Jackson's index-based enum binding and the group was created.
            // Post-change the number coerces to the String "0", which no bean
            // claims. Recorded as a deliberate narrowing (see class javadoc).
            expectCreateRejected(null, "0");
        }

        @Test
        @DisplayName("an ordinal strategyMix key is now a 400 — it used to bind to RANDOM")
        void ordinalMixKeyRejected() throws Exception {
            expectCreateRejected("[{\"strategyId\":0,\"weight\":1.0}]", null);
        }

        @Test
        @DisplayName("an explicit null strategyMix key is a 400 — it used to be persisted as null")
        void nullMixKeyRejected() throws Exception {
            // Pre-change this produced WeightedStrategy[strategyId=null] and a
            // 200; the group then failed at group start on a bot thread inside
            // BettingStrategyFactory.create. Rejecting it at the API is the
            // AD-15 improvement, and it is a status change.
            expectCreateRejected("[{\"strategyId\":null,\"weight\":1.0}]", null);
        }

        @Test
        @DisplayName("a strategyMix entry with no strategyId at all is a 400")
        void omittedMixKeyRejected() throws Exception {
            expectCreateRejected("[{\"weight\":1.0}]", null);
        }
    }

    @Nested
    @DisplayName("a persisted key the registry no longer claims")
    class PersistedKeyNoLongerRegistered {

        private BotGroup groupWithUnclaimedKey(String id) {
            return BotGroup.builder()
                    .id(id).name("vtest").environmentId("env-1").gameId(BM_GAME)
                    .namePrefix("vt").password("secret").botCount(1)
                    .minBet(100L).maxBet(500L).betIncrement(10L)
                    .minBetsPerRound(1).maxBetsPerRound(5).maxTotalBetPerRound(1000L)
                    .strategyMix(List.of(new WeightedStrategy("RETIRED_STRATEGY", 1.0)))
                    .build();
        }

        @Test
        @DisplayName("an unrelated PATCH on such a group is a 400 — validate() sees the post-merge group")
        void unrelatedPatchIsBlocked() throws Exception {
            // This is the operational consequence of AD-15 running on the merged
            // entity rather than on the request body, and it is new in 2b: a
            // group whose persisted key stops being registered (a strategy bean
            // removed in a deploy, or a plugin unloaded in a later step) can no
            // longer be renamed, re-pointed at another game, or have its bet
            // bounds adjusted until the mix is fixed.
            //
            // It is recoverable and not a blocker — see the sibling test — but it
            // is a failure mode nothing else in the suite states, and the "5xx on
            // a bot thread at group start" it replaces at least left the group
            // editable.
            when(repository.findById("grp-retired"))
                    .thenReturn(Optional.of(groupWithUnclaimedKey("grp-retired")));

            mockMvc.perform(patch("/api/v1/bot-group/{id}", "grp-retired")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"renamed\"}"))
                    .andExpect(status().isBadRequest());

            verify(repository, never()).save(any(BotGroup.class));
        }

        @Test
        @DisplayName("but a PATCH that replaces the mix succeeds — the group is not bricked")
        void patchingTheMixIsTheEscapeHatch() throws Exception {
            when(repository.findById("grp-retired-2"))
                    .thenReturn(Optional.of(groupWithUnclaimedKey("grp-retired-2")));

            mockMvc.perform(patch("/api/v1/bot-group/{id}", "grp-retired-2")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"strategyMix\":[{\"strategyId\":\"RANDOM\",\"weight\":1.0}]}"))
                    .andExpect(status().isOk());

            verify(repository).save(any(BotGroup.class));
        }
    }
}
