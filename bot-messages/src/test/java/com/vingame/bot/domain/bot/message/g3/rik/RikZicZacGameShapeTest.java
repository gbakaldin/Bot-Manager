package com.vingame.bot.domain.bot.message.g3.rik;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vingame.bot.domain.bot.message.BettingMiniMessage;
import com.vingame.bot.domain.bot.message.GameMessageTypes;
import com.vingame.bot.domain.bot.message.HasBetTotals;
import com.vingame.bot.domain.bot.message.HasBotWinnings;
import com.vingame.bot.domain.bot.message.HasCrowdBets;
import com.vingame.bot.domain.bot.message.HasJackpot;
import com.vingame.bot.domain.bot.message.HasJackpotPool;
import com.vingame.bot.domain.bot.message.StartGameMessage;
import com.vingame.bot.domain.bot.message.SubscribeMessage;
import com.vingame.bot.domain.bot.message.UpdateBetMessage;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.domain.game.model.GameType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The <b>third</b> captured 114 game — {@code ziczacPlugin} (Plinko) at
 * <b>offset 9000</b> — run through the provider that
 * {@link RikGameMessageTypes#forGame} resolves for it.
 * <p>
 * It resolves the provider the way production does, from a {@link Game}, rather than
 * instantiating {@link RikZicZacGameMessageTypes} directly: the routing and the
 * shapes are one fact, and a test that skipped the routing would still pass with
 * {@code forGame} wired to nothing.
 * <p>
 * What makes this game different from the two already covered, and therefore what is
 * worth asserting:
 * <ul>
 *   <li>own return rides {@code mbs[].r} — <b>there is no {@code wm} anywhere on the
 *       frame</b>, so the generic 114 class reports {@code 0} winnings for every
 *       round;</li>
 *   <li>{@code mbs} entries are <b>balls</b>, not options: 20 of them in one round,
 *       all at the same stake;</li>
 *   <li>{@code odd} is fractional ({@code 0.3}, {@code 1.2}), which is why it is a
 *       {@code double};</li>
 *   <li>{@code tJpV} is a real rising pool meter, unlike the other two games'
 *       constant {@code 0};</li>
 *   <li>there is no crowd inside the four-CODE contract at all.</li>
 * </ul>
 * Fixtures are verbatim frame bodies from
 * {@code /captures/rik-ziczacPlugin-12000.jsonl}, enforced by
 * {@link RikFixtureProvenanceTest}.
 */
@DisplayName("RIK ziczacPlugin (Plinko, offset 9000) — the third captured 114 game")
class RikZicZacGameShapeTest {

    private static final int ZICZAC_OFFSET = 9000;

    /** The Game record as V-3 creates it: BETTING_MINI, offset 9000, md5 false. */
    private static Game ziczacGame() {
        return Game.builder()
                .name("RIK ZicZac (Plinko)")
                .gameType(GameType.BETTING_MINI)
                .pluginName("ziczacPlugin")
                .offset(ZICZAC_OFFSET)
                .md5(false)
                .build();
    }

    /**
     * Resolve exactly as {@code BotFactory} does — registry provider, then
     * {@code .forGame(game)} — and register its four subtypes at the game's own
     * offset. {@code md5 = false}: every captured {@code 12005} carries the literal
     * {@code "-"}.
     */
    private ObjectMapper newMapper() {
        GameMessageTypes resolved = new RikGameMessageTypes().forGame(ziczacGame());
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.registerSubtypes(resolved.getTypeRegistrations(ZICZAC_OFFSET, false));
        return mapper;
    }

    private BettingMiniMessage parse(String fixture) throws Exception {
        try (var in = getClass().getResourceAsStream("/messages/rik/" + fixture)) {
            assertThat(in).as("fixture /messages/rik/" + fixture).isNotNull();
            String json = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            return newMapper().readValue(json, BettingMiniMessage.class);
        }
    }

    @Test
    @DisplayName("subscribe (cmd=12000) → the reused RikSubscribeMessage; tFB/tFD are this game's own")
    void subscribe() throws Exception {
        BettingMiniMessage parsed = parse("ziczac-subscribe.json");

        // AD-4: subscribe is NOT specialised — the same class serves all three games.
        assertThat(parsed).isInstanceOf(RikSubscribeMessage.class);

        SubscribeMessage asSubscribe = (SubscribeMessage) parsed;
        assertThat(asSubscribe.getTimeForBetting()).isEqualTo(17_000L);
        assertThat(asSubscribe.getTimeForDecision()).isEqualTo(2_000L);

        RikSubscribeMessage rik = (RikSubscribeMessage) parsed;
        assertThat(rik.getSid()).isEqualTo(1_995_083L);
        assertThat(rik.getTFP()).isEqualTo(13_000L);
        // A TENTH of stockPlugin's 500 000 000, and it is a PER-BALL cap. A strategy
        // configured above it has its bets rejected.
        assertThat(rik.getMB()).isEqualTo(50_000_000L);

        // The fixture keeps odds (the 17-bucket multiplier table), jps, tFJp, tJpv2,
        // tFJp2, htr (100 entries) and cH (36 entries) verbatim. None is modelled
        // (AD-13), so tolerating them is a regression test and not a claim — and
        // note htr[].eid is the STRING "-" on this game where the others send ints.
    }

    @Test
    @DisplayName("startGame (cmd=12005) → the reused RikStartGameMessage; md5 is the literal \"-\"")
    void startGame() throws Exception {
        BettingMiniMessage parsed = parse("ziczac-startGame.json");

        assertThat(parsed).isInstanceOf(RikStartGameMessage.class);
        assertThat(((StartGameMessage) parsed).getSessionId()).isEqualTo(1_995_084L);
    }

    @Test
    @DisplayName("updateBet (cmd=12002) → the ziczac class, gameState 0, and NO crowd marker")
    void updateBet() throws Exception {
        BettingMiniMessage parsed = parse("ziczac-updateBet.json");

        assertThat(parsed).isInstanceOf(RikZicZacUpdateBetMessage.class);
        // No gS on the frame; onUpdate guards with `if (gameStateId > 0)`, so 0 is a
        // no-op rather than a reset. Do not invent a default here.
        assertThat(((UpdateBetMessage) parsed).getGameState()).isZero();

        // THE REASON THIS CLASS EXISTS (AD-8). The seven bs entries in this fixture
        // are our OWN balls — eid 0..6, each carrying our own 60 000, with no `v` and
        // no `bc` anywhere. RikUpdateBetMessage would publish them as
        // CrowdOption(eid, v=0, ownBet=b, bc=0): an all-zero-value crowd distribution
        // for a game that has no options at all. If someone "helpfully" adds
        // HasCrowdBets back, this is the argument they have to win first.
        assertThat(parsed).isNotInstanceOf(HasCrowdBets.class);
    }

    @Test
    @DisplayName("endGame (sid 1995089) → winnings sum(mbs[].r) = 1 405 000 over 20 balls, pool 684 300")
    void endGame() throws Exception {
        BettingMiniMessage parsed = parse("ziczac-endGame.json");

        assertThat(parsed).isInstanceOf(RikZicZacEndGameMessage.class);

        RikZicZacEndGameMessage end = (RikZicZacEndGameMessage) parsed;
        assertThat(end.getSessionId()).isEqualTo(1_995_089L);

        // A WIN: 20 balls of 50 000 return 1 405 000 against a 1 000 000 stake, so
        // the winnings figure cannot be confused with the stake figure.
        assertThat(((HasBotWinnings) parsed).winningsFor("anything")).isEqualTo(1_405_000L);
        assertThat(((HasBetTotals) parsed).betAmountFor("anything")).isEqualTo(1_000_000L);
        // BALLS, not clicks (AD-6). One c=5 click would contribute five.
        assertThat(((HasBetTotals) parsed).betCountFor("anything")).isEqualTo(20);

        // The rising pool meter — the positively-evidenced non-zero tJpV the other
        // two 114 games never produced.
        assertThat(((HasJackpotPool) parsed).jackpotPool()).isEqualTo(684_300L);

        // odd MUST be double. This ball is odd:0.3 → 15 000 on a 50 000 stake; a long
        // field would silently truncate it to 0 (and 1.2 to 1) with no error at all,
        // which is exactly the failure mode that has no symptom.
        assertThat(end.getMbs()).hasSize(20);
        assertThat(end.getMbs().get(0).odd()).isEqualTo(0.3d);
        assertThat(end.getMbs().get(0).r()).isEqualTo(15_000L);
        assertThat(end.getMbs().get(6).odd()).isEqualTo(1.2d);
        assertThat(end.getMbs().get(6).r()).isEqualTo(60_000L);
    }

    @Test
    @DisplayName("endGame (sid 1995087) → a NET LOSS with a POSITIVE return: 6 420 000 back on 10 700 000 staked")
    void endGameLoss() throws Exception {
        BettingMiniMessage parsed = parse("ziczac-endGame-loss.json");

        RikZicZacEndGameMessage end = (RikZicZacEndGameMessage) parsed;
        assertThat(end.getSessionId()).isEqualTo(1_995_087L);

        long winnings = ((HasBotWinnings) parsed).winningsFor("anything");
        long staked = ((HasBetTotals) parsed).betAmountFor("anything");

        // AD-5 made falsifiable. `odd` is a TOTAL multiplier, so r is a gross return
        // INCLUDING the stake: this round is a 4 280 000 net loss and still reports a
        // positive 6 420 000. Netting the stake off here would turn the ~0.96 RTP
        // anchor into ~-0.04 and would make this assertion fail.
        assertThat(winnings).isEqualTo(6_420_000L);
        assertThat(staked).isEqualTo(10_700_000L);
        assertThat(winnings).isPositive().isLessThan(staked);

        assertThat(((HasJackpotPool) parsed).jackpotPool()).isEqualTo(513_800L);
    }

    @Test
    @DisplayName("endGame (sid 1995085) → the no-bet round: mbs is [], no p, every payout metric 0, pool still read")
    void endGameWithoutOwnBet() throws Exception {
        BettingMiniMessage parsed = parse("ziczac-endGame-noBet.json");

        RikZicZacEndGameMessage end = (RikZicZacEndGameMessage) parsed;
        assertThat(end.getSessionId()).isEqualTo(1_995_085L);
        // mbs arrives as an empty array rather than being omitted, and the `p` object
        // is absent entirely — which is the one thing in the capture that hints `p`
        // is about a bettor rather than about the round.
        assertThat(end.getMbs()).isEmpty();

        assertThat(((HasBotWinnings) parsed).winningsFor("anything")).isZero();
        assertThat(((HasBetTotals) parsed).betAmountFor("anything")).isZero();
        assertThat(((HasBetTotals) parsed).betCountFor("anything")).isZero();

        // The pool meter is a property of the game, not of our bet — it is still
        // reported on a round we sat out.
        assertThat(((HasJackpotPool) parsed).jackpotPool()).isEqualTo(204_400L);
    }

    @Test
    @DisplayName("marker pins: the ziczac endGame is HasJackpotPool but NOT HasJackpot and NOT HasCrowdBets")
    void endGameMarkerPins() throws Exception {
        BettingMiniMessage parsed = parse("ziczac-endGame.json");

        assertThat(parsed).isInstanceOf(HasBotWinnings.class);
        assertThat(parsed).isInstanceOf(HasBetTotals.class);
        assertThat(parsed).isInstanceOf(HasJackpotPool.class);

        // NOT HasJackpot (AD-7): iJp was false in 8/8 rounds and p.jwm 0 in 6/6, so
        // no discharge was ever observed. jackpotFor() would be a guess — including
        // whether a discharge is folded into mbs[].r or reported separately. One
        // capture of a hit settles it; until then this omission is deliberate.
        assertThat(parsed).isNotInstanceOf(HasJackpot.class);

        // NOT HasCrowdBets (AD-8): obs was [] in 8/8 rounds INCLUDING the 6 we bet
        // in, and this frame has no bs key at all. The real room feed rides 12007 and
        // 12019, which the four-CODE contract has no slot for. Crowd-aware
        // coordination is inert on this game, not broken.
        assertThat(parsed).isNotInstanceOf(HasCrowdBets.class);
    }
}
