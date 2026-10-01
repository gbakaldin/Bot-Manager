package com.vingame.bot.domain.bot.message.g3.rik;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vingame.bot.domain.bot.coordination.CrowdOption;
import com.vingame.bot.domain.bot.message.BettingMiniMessage;
import com.vingame.bot.domain.bot.message.HasBetTotals;
import com.vingame.bot.domain.bot.message.HasBotWinnings;
import com.vingame.bot.domain.bot.message.HasCrowdBets;
import com.vingame.bot.domain.bot.message.SubscribeMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One provider claims <b>all</b> of product 114 (AD-11), so the same five classes
 * have to survive every 114 mini game's payload — and the two captured games are
 * structurally different frames, not variants:
 * {@code mbs}/{@code obs}/{@code bPl}/{@code ps} on {@code stockPlugin} against
 * {@code bs}/{@code rs}/{@code md5}/{@code iJp} on {@code taixiuMd5Plugin}.
 * <p>
 * This class feeds each game's <b>real captured body</b> to the CMD registration of
 * the <i>other</i> game and asserts two things that are easy to confuse:
 * <ol>
 *   <li>it parses at all — {@code FAIL_ON_UNKNOWN_PROPERTIES = false} plus
 *       {@link com.fasterxml.jackson.annotation.JsonIgnoreProperties} really do cover
 *       the foreign fields, so an un-captured 114 game degrades to reduced
 *       observability rather than an {@code InvalidTypeIdException} or a
 *       {@code UnrecognizedPropertyException} on the bot thread (AD-11);</li>
 *   <li>it does not <b>silently mis-bind</b> — the foreign shape's absent fields read
 *       as absent (null / 0) and no value is smeared from one game's field onto the
 *       other's. Tolerating a frame and misreporting money from it are different
 *       outcomes and only the first is acceptable.</li>
 * </ol>
 * <b>Only the {@code cmd} is rewritten</b>, and only because {@code cmd} is how
 * Jackson selects the subtype: a body captured at offset 4000 carries {@code 7006},
 * which by construction cannot resolve in an offset-10000 registration. Every other
 * byte is the captured frame, read from the fixtures
 * {@link RikFixtureProvenanceTest} binds to the committed captures — including the
 * {@code rs} reveal string with its astral-plane emoji, which is itself a small
 * encoding regression test.
 */
@DisplayName("RikGameMessageTypes - each 114 game's frame shape survives the other game's registration")
class RikCrossGameShapeToleranceTest {

    private static final int STOCK_OFFSET = 10000;
    private static final int TXMD5_OFFSET = 4000;

    private final ObjectMapper plain = new ObjectMapper();

    private ObjectMapper newMapper(int offset, boolean md5) {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.registerSubtypes(new RikGameMessageTypes().getTypeRegistrations(offset, md5));
        return mapper;
    }

    /** The captured body, verbatim except for the CMD that selects the subtype. */
    private String fixtureWithCmd(String name, int cmd) throws Exception {
        try (var in = getClass().getResourceAsStream("/messages/rik/" + name)) {
            assertThat(in).as("fixture /messages/rik/" + name).isNotNull();
            JsonNode node = plain.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            ((ObjectNode) node).put("cmd", cmd);
            return plain.writeValueAsString(node);
        }
    }

    private Map<Integer, CrowdOption> byOption(Object parsed) {
        return ((HasCrowdBets) parsed).crowdBets().stream()
                .collect(Collectors.toMap(CrowdOption::optionId, Function.identity()));
    }

    @Test
    @DisplayName("the txmd5 endGame body parses under the stock offset: wm/bs honoured, mbs and obs absent, rs ignored")
    void txmd5EndGameBodyUnderStockRegistration() throws Exception {
        BettingMiniMessage parsed = newMapper(STOCK_OFFSET, false)
                .readValue(fixtureWithCmd("txmd5-endGame.json", 13006), BettingMiniMessage.class);

        RikEndGameMessage end = (RikEndGameMessage) parsed;
        assertThat(end.getSessionId()).isEqualTo(2473044L);

        // No mis-binding: stock's own-bet array is genuinely absent, not defaulted
        // from bs, and the crowd comes through the AD-4 fallback.
        assertThat(end.getMbs()).isNull();
        assertThat(end.getObs()).isNull();
        assertThat(byOption(end)).containsOnlyKeys(1, 2);

        // Money still reads off the right fields.
        assertThat(((HasBotWinnings) end).winningsFor("x")).isEqualTo(198_000L);
        assertThat(((HasBetTotals) end).betAmountFor("x")).isEqualTo(100_000L);

        // rs / md5 / tJpv2 are the foreign fields; rs in particular is a reveal string
        // full of surrogate pairs. Unmodelled (rs, md5) or modelled-but-unwired (tJpV).
        assertThat(end.getTJpV()).isZero();
        assertThat(end.isIJp()).isFalse();
    }

    @Test
    @DisplayName("the stock endGame body parses under the txmd5 offset: mbs/obs honoured, top-level wm stays 0")
    void stockEndGameBodyUnderTxmd5Registration() throws Exception {
        BettingMiniMessage parsed = newMapper(TXMD5_OFFSET, true)
                .readValue(fixtureWithCmd("endGame.json", 7006), BettingMiniMessage.class);

        RikEndGameMessage end = (RikEndGameMessage) parsed;
        assertThat(end.getSessionId()).isEqualTo(3793249L);

        // The txmd5-shaped fields are absent and must read as absent — in particular
        // the top-level wm must NOT pick anything up from mbs[].wm. If it did,
        // winningsFor's precedence rule would be untestable and a game carrying both
        // would double-count.
        assertThat(end.getWm()).isZero();
        assertThat(end.getBs()).isNull();

        assertThat(end.getMbs()).hasSize(1);
        assertThat(((HasBotWinnings) end).winningsFor("x")).isEqualTo(4704L);
        assertThat(((HasBetTotals) end).betAmountFor("x")).isEqualTo(3000L);
        assertThat(byOption(end)).containsOnlyKeys(0, 1);

        // ps (empty) and bPl (the 10-point price series) are unmodelled and tolerated.
    }

    @Test
    @DisplayName("each game's subscribe body parses under the other's registration, tFB/tFD intact")
    void subscribeBodiesCrossParse() throws Exception {
        // txmd5's subscribe under the stock offset — it carries tP (top players),
        // a real md5, tJpV/tFJp and a 200-entry htr that RikSubscribeMessage models
        // none of.
        SubscribeMessage foreign = (SubscribeMessage) newMapper(STOCK_OFFSET, false)
                .readValue(fixtureWithCmd("txmd5-subscribe.json", 13000), BettingMiniMessage.class);
        assertThat(foreign).isInstanceOf(RikSubscribeMessage.class);
        assertThat(foreign.getTimeForBetting()).isEqualTo(50_000L);
        assertThat(foreign.getTimeForDecision()).isEqualTo(3_000L);
        assertThat(byOption(foreign)).containsOnlyKeys(1, 2);

        // …and stock's under the txmd5 offset, with its 50-entry cH and nested bH.
        SubscribeMessage stock = (SubscribeMessage) newMapper(TXMD5_OFFSET, true)
                .readValue(fixtureWithCmd("subscribe.json", 7000), BettingMiniMessage.class);
        assertThat(stock).isInstanceOf(RikSubscribeMessage.class);
        assertThat(stock.getTimeForBetting()).isEqualTo(21_000L);
        assertThat(stock.getTimeForDecision()).isEqualTo(1_000L);
        assertThat(byOption(stock)).containsOnlyKeys(0, 1);
    }

    @Test
    @DisplayName("an un-captured 114 game shape — nothing but cmd and sid — still yields a usable EndGame (AD-11)")
    void aMinimalUnknownGameShapeStillParses() throws Exception {
        // SYNTHETIC: offsets 14000-18000 were live during the capture and share the
        // CODE layout, but not one payload was captured (OI-2). The floor this
        // provider promises for them is: getSessionId() works, nothing throws, and
        // every optional accessor degrades to empty/zero rather than to a wrong
        // number. CMD 17006 = 3006 + offset 14000, one of the CMDs §4b saw live in
        // the capture's outsideWindow.
        BettingMiniMessage parsed = newMapper(14000, false)
                .readValue("{\"cmd\":17006,\"sid\":777,\"foo\":{\"bar\":[1,2,3]},\"qux\":\"?\"}",
                        BettingMiniMessage.class);

        RikEndGameMessage end = (RikEndGameMessage) parsed;
        assertThat(end.getSessionId()).isEqualTo(777L);
        assertThat(((HasCrowdBets) end).crowdBets()).isEmpty();
        assertThat(((HasBotWinnings) end).winningsFor("x")).isZero();
        assertThat(((HasBetTotals) end).betAmountFor("x")).isZero();
        assertThat(((HasBetTotals) end).betCountFor("x")).isZero();
    }
}
