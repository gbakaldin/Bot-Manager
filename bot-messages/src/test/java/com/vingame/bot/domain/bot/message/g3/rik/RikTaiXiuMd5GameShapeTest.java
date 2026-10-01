package com.vingame.bot.domain.bot.message.g3.rik;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vingame.bot.domain.bot.coordination.CrowdOption;
import com.vingame.bot.domain.bot.message.BettingMiniMessage;
import com.vingame.bot.domain.bot.message.HasBetTotals;
import com.vingame.bot.domain.bot.message.HasBotWinnings;
import com.vingame.bot.domain.bot.message.HasCrowdBets;
import com.vingame.bot.domain.bot.message.StartGameMd5Message;
import com.vingame.bot.domain.bot.message.StartGameMessage;
import com.vingame.bot.domain.bot.message.SubscribeMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The <b>second</b> captured 114 game — {@code taixiuMd5Plugin} at <b>offset 4000</b>
 * — run through the same five classes and the same real provider.
 * <p>
 * This is what turns "one game's shapes" into "two games' shapes" (AD-11 amended).
 * It is not a copy of {@link RikGameMessageTypesTest} with different numbers: the
 * frame shape genuinely differs, and each difference is a code path that would
 * otherwise be unexercised —
 * <ul>
 *   <li>{@code eid} is {@code 1}/{@code 2}, not {@code 0}/{@code 1};</li>
 *   <li>EndGame carries {@code bs} and <b>no {@code obs}</b>, so the AD-4 fallback in
 *       {@code crowdBets()} is load-bearing here rather than speculative;</li>
 *   <li>own winnings arrive as a <b>top-level {@code wm}</b> with no {@code mbs} at
 *       all — the other half of AD-16;</li>
 *   <li>{@code md5} is a real 64-hex hash on subscribe, startGame and endGame, so
 *       {@code Game.md5 = true} is a captured configuration (AD-12 amended), not the
 *       hypothetical it was written as;</li>
 *   <li>the round this account did not bet omits {@code wm} and {@code bs[].b}
 *       <b>entirely</b> rather than sending zeros — the absence case is the one that
 *       protects {@code bot_bet_amount_total} from being fed the room's stake.</li>
 * </ul>
 * Fixtures are verbatim frame bodies from
 * {@code /captures/rik-taixiuMd5Plugin-7000.jsonl}, enforced by
 * {@link RikFixtureProvenanceTest}.
 */
@DisplayName("RikGameMessageTypes - the second captured 114 game (taixiuMd5Plugin, offset 4000)")
class RikTaiXiuMd5GameShapeTest {

    private static final int RIK_TXMD5_OFFSET = 4000;

    /** This game IS md5 — real 64-hex hashes, unlike stockPlugin's literal "-". */
    private ObjectMapper newMapper(boolean md5) {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.registerSubtypes(new RikGameMessageTypes().getTypeRegistrations(RIK_TXMD5_OFFSET, md5));
        return mapper;
    }

    private String loadFixture(String name) throws Exception {
        try (var in = getClass().getResourceAsStream("/messages/rik/" + name)) {
            assertThat(in).as("fixture /messages/rik/" + name).isNotNull();
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private Map<Integer, CrowdOption> byOption(BettingMiniMessage parsed) {
        return ((HasCrowdBets) parsed).crowdBets().stream()
                .collect(Collectors.toMap(CrowdOption::optionId, Function.identity()));
    }

    @Test
    @DisplayName("subscribe (cmd=7000) → RikSubscribeMessage; tFB/tFD are this game's own, crowd is keyed {1,2}")
    void subscribe() throws Exception {
        BettingMiniMessage parsed =
                newMapper(true).readValue(loadFixture("txmd5-subscribe.json"), BettingMiniMessage.class);

        assertThat(parsed).isInstanceOf(RikSubscribeMessage.class);

        SubscribeMessage asSubscribe = (SubscribeMessage) parsed;
        // 50 s bet window / 3 s decision tail — nothing like stock's 21 s / 1 s, and
        // read from the same two accessors.
        assertThat(asSubscribe.getTimeForBetting()).isEqualTo(50_000L);
        assertThat(asSubscribe.getTimeForDecision()).isEqualTo(3_000L);

        RikSubscribeMessage rik = (RikSubscribeMessage) parsed;
        assertThat(rik.getSid()).isEqualTo(2473044L);
        assertThat(rik.getTFP()).isEqualTo(10_000L);
        assertThat(rik.getMB()).isEqualTo(500_000_000L);

        // Option ids are per game, not per product. Nothing in the message layer
        // cares — CrowdOption is keyed on whatever eid arrives — but the Game
        // record's optionAffinities must say {"1":1,"2":1} for this one.
        Map<Integer, CrowdOption> crowd = byOption(parsed);
        assertThat(crowd).containsOnlyKeys(1, 2);
        assertThat(crowd.get(1).ownBet()).isEqualTo(100_000L);
        assertThat(crowd.get(1).value()).isEqualTo(100_000L);

        // The unmodelled cH (50 entries), htr (200 entries) and tP are kept verbatim
        // in the fixture, so tolerating them is a regression test and not a claim.
    }

    @Test
    @DisplayName("startGame with md5=true → RikStartGameMd5Message carrying the real 64-hex hash")
    void startGameMd5() throws Exception {
        BettingMiniMessage parsed =
                newMapper(true).readValue(loadFixture("txmd5-startGame.json"), BettingMiniMessage.class);

        assertThat(parsed).isInstanceOf(RikStartGameMd5Message.class);
        assertThat(((StartGameMessage) parsed).getSessionId()).isEqualTo(2473045L);

        // AD-12 is live, not a hedge: had startGameMd5Type() returned null the way
        // Win79's does, this game would NPE at registration.
        String hash = ((StartGameMd5Message) parsed).getMd5Hash();
        assertThat(hash).isEqualTo("a2575db310770d24d345c46676622eab05ef46b5d15888f928a534cc4dfb09f2");
        assertThat(hash).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("the same startGame frame with md5=false → plain RikStartGameMessage, hash simply ignored")
    void startGamePlain() throws Exception {
        BettingMiniMessage parsed =
                newMapper(false).readValue(loadFixture("txmd5-startGame.json"), BettingMiniMessage.class);

        assertThat(parsed).isInstanceOf(RikStartGameMessage.class);
        assertThat(((StartGameMessage) parsed).getSessionId()).isEqualTo(2473045L);
    }

    @Test
    @DisplayName("endGame (cmd=7006) → own winnings from the top-level wm, own stake from bs[].b, crowd via the bs fallback")
    void endGame() throws Exception {
        BettingMiniMessage parsed =
                newMapper(true).readValue(loadFixture("txmd5-endGame.json"), BettingMiniMessage.class);

        assertThat(parsed).isInstanceOf(RikEndGameMessage.class);

        RikEndGameMessage end = (RikEndGameMessage) parsed;
        assertThat(end.getSessionId()).isEqualTo(2473044L);
        // d1/d2/d3 are DICE on this game (5-4-4 = 13, Tài), not stock's percent move.
        assertThat(end.getD1()).isEqualTo(5);
        assertThat(end.getD2()).isEqualTo(4);
        assertThat(end.getD3()).isEqualTo(4);

        // No mbs at all here — the other branch of AD-16.
        assertThat(end.getMbs()).isNull();
        assertThat(end.getObs()).isNull();

        // wm is the gross return INCLUDING the stake: 100 000 staked, 1.98x on a win
        // (2% of profit on this game, unlike stock's 2% of gross), so 198 000. RTP
        // therefore lands at ~0.98, not ~-0.02 — never net the stake off it.
        assertThat(((HasBotWinnings) parsed).winningsFor("anything")).isEqualTo(198_000L);
        assertThat(((HasBetTotals) parsed).betAmountFor("anything")).isEqualTo(100_000L);
        assertThat(((HasBetTotals) parsed).betCountFor("anything")).isEqualTo(1);

        // Crowd comes through the obs → bs fallback. Without it this game's
        // end-of-round crowd would be silent.
        Map<Integer, CrowdOption> crowd = byOption(parsed);
        assertThat(crowd).containsOnlyKeys(1, 2);
        assertThat(crowd.get(1).value()).isEqualTo(100_000L);
        assertThat(crowd.get(1).ownBet()).isEqualTo(100_000L);
        assertThat(crowd.get(2).value()).isZero();
    }

    @Test
    @DisplayName("the round this account did NOT bet: wm and bs[].b are absent, and every payout metric reads 0")
    void endGameWithoutOwnBet() throws Exception {
        BettingMiniMessage parsed =
                newMapper(true).readValue(loadFixture("txmd5-endGame-noBet.json"), BettingMiniMessage.class);

        RikEndGameMessage end = (RikEndGameMessage) parsed;
        assertThat(end.getSessionId()).isEqualTo(2473045L);

        // This is the case that protects the metric. The server omits wm and bs[].b
        // entirely rather than sending zeros; Jackson's 0 plus onEndGame's `w > 0`
        // guard makes the round a no-op. Reaching for the room's v instead — which
        // IS present and non-zero on a busy round — is what would silently corrupt
        // bot_bet_amount_total.
        assertThat(((HasBotWinnings) parsed).winningsFor("anything")).isZero();
        assertThat(((HasBetTotals) parsed).betAmountFor("anything")).isZero();
        assertThat(((HasBetTotals) parsed).betCountFor("anything")).isZero();

        // The crowd is still reported — it is just empty of stake this round.
        assertThat(byOption(parsed)).containsOnlyKeys(1, 2);
    }
}
