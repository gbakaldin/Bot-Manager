package com.vingame.bot.domain.bot.message.g3.rik;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vingame.bot.domain.bot.message.GameMessageTypes;
import com.vingame.bot.domain.bot.message.request.GameRequest;
import com.vingame.bot.domain.bot.message.request.GameRequestFactory;
import com.vingame.bot.domain.bot.message.request.ZicZacBet;
import com.vingame.bot.domain.bot.message.request.ZicZacRequest;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.domain.game.model.GameType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RikZicZacGameMessageTypes#requestFor} on its own (RIK_114_ZICZAC Amendment A1
 * item 3): the three arguments the seam is handed must all reach the wire, and none may
 * be replaced by a constant.
 *
 * <p>Every other test that crosses this seam does so with {@code zoneName = "MiniGame"}
 * and {@code offset = 9000}, which is exactly the pair a hardcoded implementation would
 * pass. This class uses a zone no other test uses and two offsets, so a
 * {@code new ZicZacRequest(game.getPluginName(), "MiniGame", 9000)} — the kind of
 * "simplification" that survives every existing assertion — fails here by name.
 * {@code RikGameMessageTypesRoutingTest} owns the resolution side (that ziczac reaches
 * this provider through {@code forGame} and only through it); this class owns what the
 * provider does once reached.
 */
@DisplayName("RikZicZacGameMessageTypes.requestFor — arguments reach the wire, none is a constant")
class RikZicZacGameMessageTypesRequestForTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RikZicZacGameMessageTypes provider = new RikZicZacGameMessageTypes();

    private static Game game(String pluginName, int offset) {
        return Game.builder()
                .gameType(GameType.BETTING_MINI)
                .pluginName(pluginName)
                .offset(offset)
                .md5(false)
                .build();
    }

    @Test
    @DisplayName("implements GameRequestFactory and answers a ZicZacRequest — never null, never the shared Request")
    void answersAZicZacRequest() {
        assertThat(provider).isInstanceOf(GameRequestFactory.class);

        GameRequest request = provider.requestFor(game("ziczacPlugin", 9000), "MiniGame", 9000);

        assertThat(request).isExactlyInstanceOf(ZicZacRequest.class);
        assertThat(request.bet(60_000L, 0, 1L)).isExactlyInstanceOf(ZicZacBet.class);
        assertThat(request.commit(1L)).isEmpty();
    }

    @Test
    @DisplayName("the zone name is the one passed in, on both subscribe and bet — not a hardcoded MiniGame")
    void zoneNameIsPropagated() throws Exception {
        GameRequest request = provider.requestFor(game("ziczacPlugin", 9000), "MiniGame3", 9000);

        JsonNode subscribe = MAPPER.readTree(request.subscribe().serialize(MAPPER));
        JsonNode bet = MAPPER.readTree(request.bet(60_000L, 0, 1995084L).serialize(MAPPER));

        assertThat(subscribe.get(1).asText()).isEqualTo("MiniGame3");
        assertThat(bet.get(1).asText()).isEqualTo("MiniGame3");
    }

    @Test
    @DisplayName("the plugin slot carries the Game's spelling verbatim — the operator's string, not a constant")
    void pluginNameComesFromTheGame() throws Exception {
        GameRequest request = provider.requestFor(game("ZicZacPlugin", 9000), "MiniGame", 9000);

        JsonNode subscribe = MAPPER.readTree(request.subscribe().serialize(MAPPER));
        JsonNode bet = MAPPER.readTree(request.bet(60_000L, 0, 1995084L).serialize(MAPPER));

        assertThat(subscribe.get(2).asText()).isEqualTo("ZicZacPlugin");
        assertThat(bet.get(2).asText()).isEqualTo("ZicZacPlugin");
    }

    @Test
    @DisplayName("the offset argument drives both cmds: 9000 -> 12000/12002, 5000 -> 8000/8002")
    void offsetIsPropagatedNotHardcoded() throws Exception {
        GameRequest nine = provider.requestFor(game("ziczacPlugin", 9000), "MiniGame", 9000);
        GameRequest five = provider.requestFor(game("ziczacPlugin", 5000), "MiniGame", 5000);

        assertThat(MAPPER.readTree(nine.subscribe().serialize(MAPPER)).get(3).get("cmd").asInt()).isEqualTo(12000);
        assertThat(MAPPER.readTree(nine.bet(1L, 0, 1L).serialize(MAPPER)).get(3).get("cmd").asInt()).isEqualTo(12002);
        assertThat(MAPPER.readTree(five.subscribe().serialize(MAPPER)).get(3).get("cmd").asInt()).isEqualTo(8000);
        assertThat(MAPPER.readTree(five.bet(1L, 0, 1L).serialize(MAPPER)).get(3).get("cmd").asInt()).isEqualTo(8002);
    }

    @Test
    @DisplayName("the seam's offset is the caller's, not Game.offset — the bot passes its own field, which BotFactory set from the Game")
    void callerOffsetWinsOverGameOffset() throws Exception {
        // BettingMiniGameBot.buildRequest passes `offset` (set from game.getOffset() two
        // statements earlier), so in production the two agree. The contract is that the
        // explicit argument is the one used — the same as RikGameMessageTypes.requestFor.
        GameRequest request = provider.requestFor(game("ziczacPlugin", 9000), "MiniGame", 10000);

        assertThat(MAPPER.readTree(request.bet(1L, 0, 1L).serialize(MAPPER)).get(3).get("cmd").asInt())
                .isEqualTo(13002);
    }

    @Test
    @DisplayName("reached through forGame from the registry provider, the same three arguments still land — the production path")
    void throughForGameTheArgumentsStillLand() throws Exception {
        Game game = game("ziczacPlugin", 9000);
        GameMessageTypes resolved = new RikGameMessageTypes().forGame(game);
        assertThat(resolved).isInstanceOf(RikZicZacGameMessageTypes.class);

        GameRequest request = ((GameRequestFactory) resolved).requestFor(game, "MiniGame3", 9000);
        JsonNode bet = MAPPER.readTree(request.bet(60_000L, 3, 1995084L).serialize(MAPPER));

        assertThat(bet.get(1).asText()).isEqualTo("MiniGame3");
        assertThat(bet.get(2).asText()).isEqualTo("ziczacPlugin");
        assertThat(bet.get(3).get("cmd").asInt()).isEqualTo(12002);
        assertThat(bet.get(3).fieldNames()).toIterable().containsExactlyInAnyOrder("cmd", "b", "c", "sid", "aid");
        assertThat(bet.get(3).has("eid")).as("entryId 3 was discarded").isFalse();
    }
}
