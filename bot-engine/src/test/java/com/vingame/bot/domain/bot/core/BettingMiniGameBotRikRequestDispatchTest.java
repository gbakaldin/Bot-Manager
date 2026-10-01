package com.vingame.bot.domain.bot.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vingame.bot.config.bot.BotBehaviorConfig;
import com.vingame.bot.config.bot.BotConfiguration;
import com.vingame.bot.config.bot.BotCredentials;
import com.vingame.bot.domain.bot.message.GameMessageTypes;
import com.vingame.bot.domain.bot.message.g2.bom.BomGameMessageTypes;
import com.vingame.bot.domain.bot.message.g3.rik.RikGameMessageTypes;
import com.vingame.bot.domain.bot.message.request.GameRequest;
import com.vingame.bot.domain.bot.message.request.Request;
import com.vingame.bot.domain.bot.message.request.RikStockRequest;
import com.vingame.bot.domain.bot.message.request.ZicZacRequest;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.infrastructure.client.ApiGatewayClient;
import com.vingame.bot.infrastructure.client.ClientFactory;
import com.vingame.bot.infrastructure.client.GameMsClient;
import com.vingame.websocketparser.message.request.ActionRequestMessage;
import com.vingame.websocketparser.message.request.Body;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The {@code buildRequest} seam itself (RIK_114_BETTING_MINI AD-20), asserted on a real
 * {@link BettingMiniGameBot} rather than on the factory that feeds it.
 *
 * <p><b>Why this is not covered by {@code RikGameMessageTypesRoutingTest}.</b> That test
 * proves the provider hands back the right request when asked. This one proves the bot
 * <i>asks</i> — the {@code instanceof GameRequestFactory} branch is three lines in the
 * engine and deleting them would leave every provider-side test green while every 114
 * stock bot silently went back to staking on {@code b}, which is the zero this phase
 * exists to end.
 *
 * <p>It also pins the negative half, which is the larger blast radius: a bot on any
 * provider that does not implement the capability must build the shared {@link Request},
 * unchanged.
 *
 * <p>Not to be confused with {@code BettingMiniGameBotRikDispatchTest}, which is about
 * inbound EndGame payout-marker dispatch.
 */
@DisplayName("BettingMiniGameBot.buildRequest — the GameRequestFactory seam")
class BettingMiniGameBotRikRequestDispatchTest {

    private static final int RIK_STOCK_OFFSET = 10000;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<BettingMiniGameBot> bots = new ArrayList<>();

    @AfterEach
    void tearDown() throws Exception {
        for (BettingMiniGameBot bot : bots) {
            shutdown(bot, "watchdogScheduler");
            shutdown(bot, "scheduler");
        }
    }

    private static void shutdown(BettingMiniGameBot bot, String name) throws Exception {
        Object executor = readField(bot, name);
        if (executor != null) {
            ((ScheduledExecutorService) executor).shutdownNow();
        }
    }

    private static Object readField(Object target, String name) throws Exception {
        Field f = BettingMiniGameBot.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    private static Body body(Object message) throws Exception {
        Field f = ActionRequestMessage.class.getDeclaredField("body");
        f.setAccessible(true);
        return (Body) f.get(message);
    }

    /**
     * Build a bot the way {@code BotFactory} does: inject the provider resolved
     * <i>per game</i> ({@code forGame}), then initialize.
     */
    private GameRequest requestOf(GameMessageTypes registryProvider, String pluginName, int offset)
            throws Exception {
        Game game = Game.builder()
                .id("g-" + pluginName).name(pluginName).pluginName(pluginName)
                .offset(offset).numberOfOptions(2).md5(false).build();
        BotConfiguration cfg = BotConfiguration.builder()
                .credentials(BotCredentials.builder()
                        .username("rikbot1").password("pw").fingerprint("fp").build())
                .environmentId("394301f4-6daf-4c55-a073-502a81c00731")
                .botGroupId("group-rik").botIndex(1)
                .game(game)
                .behaviorConfig(BotBehaviorConfig.builder()
                        .minBet(1000).maxBet(1000).betIncrement(1000)
                        .maxTotalBetPerRound(10_000).minBetsPerRound(1).maxBetsPerRound(3)
                        .chatEnabled(false).autoDepositEnabled(false).betSkipPercentage(0)
                        .build())
                .zoneName("MiniGame").timeoutMillis(60_000L).watchdogTimeoutSeconds(120L)
                .build();

        BettingMiniGameBot bot = new BettingMiniGameBot();
        bots.add(bot);
        bot.setClients(mock(ApiGatewayClient.class), mock(GameMsClient.class), mock(ClientFactory.class));
        bot.setConfiguration(cfg);
        bot.setRandom(mock(Random.class));
        // BotFactory injects the per-game-resolved provider before initializeSubclass.
        bot.setMessageTypes(registryProvider.forGame(game));
        bot.initializeSubclass();

        return (GameRequest) readField(bot, "request");
    }

    @Test
    @DisplayName("a 114 stockPlugin bot builds a RikStockRequest, and its bet carries v + iAc")
    void stockBotBuildsTheStockRequest() throws Exception {
        GameRequest request = requestOf(new RikGameMessageTypes(), "stockPlugin", RIK_STOCK_OFFSET);

        assertThat(request).isInstanceOf(RikStockRequest.class);

        // The seam is only worth anything if the frame that comes out of it is the
        // captured one. cmd = offset + 3002.
        JsonNode bet = MAPPER.readTree(MAPPER.writeValueAsString(body(request.bet(1000L, 0, 3793247L))));
        assertThat(bet.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("cmd", "v", "sid", "aid", "eid", "iAc");
        assertThat(bet.get("cmd").asInt()).isEqualTo(13002);
        assertThat(bet.get("v").asLong()).isEqualTo(1000L);

        // Phase 3 (AD-28/AD-29): the same request also builds the per-bet commit, and
        // its body is exactly {cmd, sId} — pinned here too so the seam test and
        // RikStockCommitTest cannot drift apart.
        Optional<ActionRequestMessage> commit = request.commit(3793247L);
        assertThat(commit).isPresent();
        JsonNode commitBody = MAPPER.readTree(MAPPER.writeValueAsString(body(commit.get())));
        assertThat(commitBody.fieldNames()).toIterable().containsExactlyInAnyOrder("cmd", "sId");
        assertThat(commitBody.get("cmd").asInt()).isEqualTo(13022);
    }

    @Test
    @DisplayName("the stock envelope is [\"6\", zone, plugin, …] — zone and plugin are not transposed")
    void stockEnvelopeCarriesZoneThenPlugin() throws Exception {
        GameRequest request = requestOf(new RikGameMessageTypes(), "stockPlugin", RIK_STOCK_OFFSET);

        // The one production construction of RikStockRequest is
        //   new RikStockRequest(game.getPluginName(), zoneName, offset)
        // — two adjacent same-typed Strings, in the OPPOSITE order to the parameter list
        // of the method that writes them (requestFor(Game, String zoneName, int offset)).
        // Transposing them compiles, type-checks and ships a byte-perfect body inside
        // ["6","stockPlugin","MiniGame",{…}], an envelope this server will not route:
        // the worst possible bug shape on a feature whose entire subject is a frame that
        // looks correct and is silently ignored. Every other envelope assertion in this
        // feature builds its RikStockRequest with literal arguments, so none of them can
        // see that line; this one goes through the provider, on a bot built the way
        // BotFactory builds it.
        JsonNode betFrame = MAPPER.readTree(request.bet(1000L, 0, 3793247L).serialize(MAPPER));
        assertThat(betFrame.get(1).asText()).isEqualTo("MiniGame");      // zone
        assertThat(betFrame.get(2).asText()).isEqualTo("stockPlugin");   // plugin

        JsonNode subscribeFrame = MAPPER.readTree(request.subscribe().serialize(MAPPER));
        assertThat(subscribeFrame.get(1).asText()).isEqualTo("MiniGame");
        assertThat(subscribeFrame.get(2).asText()).isEqualTo("stockPlugin");

        // ...and it is the same envelope the shared Request this fork replaced produces
        // for the same game, which is the claim the fork rests on: a body change and
        // nothing else (AD-26 — element 0 stays the string "6" on both).
        JsonNode sharedFrame = MAPPER.readTree(
                new Request("stockPlugin", "MiniGame", RIK_STOCK_OFFSET)
                        .bet(1000L, 0, 3793247L).serialize(MAPPER));
        assertThat(betFrame.get(0)).isEqualTo(sharedFrame.get(0));
        assertThat(betFrame.get(1)).isEqualTo(sharedFrame.get(1));
        assertThat(betFrame.get(2)).isEqualTo(sharedFrame.get(2));
    }

    @Test
    @DisplayName("a 114 taixiuMd5Plugin bot keeps the shared Request — it settles with 'b' today")
    void txmd5BotKeepsTheSharedRequest() throws Exception {
        GameRequest request = requestOf(new RikGameMessageTypes(), "taixiuMd5Plugin", 4000);

        assertThat(request).isExactlyInstanceOf(Request.class);

        JsonNode bet = MAPPER.readTree(MAPPER.writeValueAsString(body(request.bet(100_000L, 1, 2473044L))));
        assertThat(bet.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("cmd", "aid", "b", "eid", "sid");
        assertThat(bet.get("cmd").asInt()).isEqualTo(7002);
    }

    @Test
    @DisplayName("an un-captured 114 game keeps the shared Request — allowlist, not denylist (AD-21)")
    void unCaptured114GameKeepsTheSharedRequest() throws Exception {
        assertThat(requestOf(new RikGameMessageTypes(), "someOtherPlugin", 17000))
                .isExactlyInstanceOf(Request.class);
    }

    @Test
    @DisplayName("a 114 ziczac bot builds a ZicZacRequest (RIK_114_ZICZAC Phase 2), and its bet carries b + c and no eid")
    void ziczacBotBuildsTheZicZacRequest() throws Exception {
        // Flipped deliberately by RIK_114_ZICZAC Phase 2. Until then forGame routed
        // ziczac to RikZicZacGameMessageTypes, which did not implement the capability,
        // so buildRequest's instanceof failed and the shared Request applied (AD-24) —
        // and every ziczac bet was accepted in-round and discarded at settlement.
        GameRequest request = requestOf(new RikGameMessageTypes(), "ziczacPlugin", 9000);

        assertThat(request).isExactlyInstanceOf(ZicZacRequest.class);

        JsonNode bet = MAPPER.readTree(MAPPER.writeValueAsString(body(request.bet(60_000L, 0, 1995084L))));
        assertThat(bet.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("cmd", "b", "c", "sid", "aid");
        assertThat(bet.get("cmd").asInt()).isEqualTo(12002);
        assertThat(bet.get("b").asLong()).isEqualTo(60_000L);
        assertThat(bet.get("c").asInt()).isEqualTo(1);

        // No commit on ziczac — the GameRequest default, inherited.
        assertThat(request.commit(1995084L)).isEmpty();

        // Envelope: zone then plugin, not transposed (the same trap the stock test pins).
        JsonNode frame = MAPPER.readTree(request.bet(60_000L, 0, 1995084L).serialize(MAPPER));
        assertThat(frame.get(1).asText()).isEqualTo("MiniGame");
        assertThat(frame.get(2).asText()).isEqualTo("ziczacPlugin");
    }

    @Test
    @DisplayName("a bot on a non-114 provider builds the shared Request — no other product's frame moves")
    void otherProductsAreUntouched() throws Exception {
        assertThat(requestOf(new BomGameMessageTypes(), "BauCua", 2000))
                .isExactlyInstanceOf(Request.class);
    }

    @Test
    @DisplayName("a bot with NO provider injected still builds the shared Request (null-safe instanceof)")
    void nullProviderFallsBack() throws Exception {
        Game game = Game.builder()
                .id("g-null").name("BauCua").pluginName("BauCua")
                .offset(2000).numberOfOptions(2).md5(false).build();
        BotConfiguration cfg = BotConfiguration.builder()
                .credentials(BotCredentials.builder()
                        .username("b1").password("pw").fingerprint("fp").build())
                .environmentId("env").botGroupId("grp").botIndex(1)
                .game(game)
                .behaviorConfig(BotBehaviorConfig.builder()
                        .minBet(1000).maxBet(1000).betIncrement(1000)
                        .maxTotalBetPerRound(10_000).minBetsPerRound(1).maxBetsPerRound(3)
                        .chatEnabled(false).autoDepositEnabled(false).betSkipPercentage(0)
                        .build())
                .zoneName("MiniGame").timeoutMillis(60_000L).watchdogTimeoutSeconds(120L)
                .build();

        BettingMiniGameBot bot = new BettingMiniGameBot();
        bots.add(bot);
        bot.setClients(mock(ApiGatewayClient.class), mock(GameMsClient.class), mock(ClientFactory.class));
        bot.setConfiguration(cfg);
        bot.setRandom(mock(Random.class));
        bot.initializeSubclass();

        assertThat(readField(bot, "request")).isExactlyInstanceOf(Request.class);
    }
}
