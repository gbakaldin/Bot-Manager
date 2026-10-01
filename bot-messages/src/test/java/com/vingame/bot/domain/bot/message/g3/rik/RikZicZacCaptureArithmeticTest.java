package com.vingame.bot.domain.bot.message.g3.rik;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The ziczac payout model, asserted against <b>the whole committed capture</b> rather
 * than against the six hand-picked fixtures.
 * <p>
 * Two things are checked, and neither is decoration:
 * <ol>
 *   <li><b>{@code r == b × odd} for every ball in the capture.</b> This is what makes
 *       the game a closed-form Plinko rather than an opaque payout, and it is what
 *       fails loudly if {@code odd} is ever narrowed to an integral type — a
 *       {@code long} field truncates {@code 1.2} to {@code 1} <i>silently</i>, so
 *       without this the corruption has no symptom until someone compares a
 *       dashboard to the game's own ledger.</li>
 *   <li><b>{@code sum(mbs[].r) == p.wm} in every betting round.</b> That equality is
 *       exactly what makes AD-5's choice of source <i>costless</i>: the class reads
 *       {@code mbs} — proven own-scoped by the backend's {@code MAIN_BET_ARRAY} /
 *       {@code OTHER_BET_ARRAY} constant pair — and deliberately does <b>not</b> read
 *       {@code p.wm}, which sits next to a {@code uid} / {@code u} / {@code dn}
 *       triple and therefore looks like room-announcement payload. This capture has
 *       <b>one bettor</b>, so it cannot tell the two scopes apart; choosing the one
 *       with independent evidence costs nothing precisely because the numbers agree,
 *       and this test is what says so.</li>
 * </ol>
 * If a future two-account capture ever breaks the second assertion, that is the
 * finding the whole {@code p}-scope question was waiting for (OI-7) — and the shipped
 * accessor is already on the right side of it.
 */
@DisplayName("ziczac payout arithmetic, over the whole committed capture")
class RikZicZacCaptureArithmeticTest {

    private static final String ZICZAC_CAPTURE = "/captures/rik-ziczacPlugin-12000.jsonl";
    private static final int ZICZAC_END_GAME_CMD = 12006;

    private final ObjectMapper mapper = new ObjectMapper();

    /** Every inbound EndGame body in the capture, in wire order. */
    private List<JsonNode> endGameBodies() throws Exception {
        List<JsonNode> bodies = new ArrayList<>();
        try (InputStream in = getClass().getResourceAsStream(ZICZAC_CAPTURE)) {
            assertThat(in).as("capture " + ZICZAC_CAPTURE).isNotNull();
            try (BufferedReader reader =
                         new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    JsonNode frame = mapper.readTree(line);
                    // The first line is the _meta header and carries no body.
                    if (frame.has("body")
                            && "in".equals(frame.path("dir").asText())
                            && frame.path("cmd").asInt() == ZICZAC_END_GAME_CMD) {
                        bodies.add(frame.get("body"));
                    }
                }
            }
        }
        return bodies;
    }

    @Test
    @DisplayName("r == b × odd for all 81 balls — and odd really is fractional, so it must be a double")
    void everyBallPaysItsBucketMultiplier() throws Exception {
        List<JsonNode> rounds = endGameBodies();
        assertThat(rounds).as("8 complete rounds in the capture").hasSize(8);

        int balls = 0;
        int fractionalOdds = 0;
        long totalStaked = 0L;
        long totalReturned = 0L;

        for (JsonNode round : rounds) {
            long sid = round.path("sid").asLong();
            for (JsonNode ball : round.path("mbs")) {
                long b = ball.path("b").asLong();
                long r = ball.path("r").asLong();
                double odd = ball.path("odd").asDouble();

                assertThat((double) r)
                        .as("round %d: r must equal b x odd (b=%d, odd=%s)", sid, b, odd)
                        .isCloseTo(b * odd, within(0.5d));

                if (odd != Math.rint(odd)) {
                    fractionalOdds++;
                }
                balls++;
                totalStaked += b;
                totalReturned += r;
            }
        }

        // The whole capture, not a fixture: 81 balls, zero mismatches.
        assertThat(balls).as("balls across the capture").isEqualTo(81);

        // AD-12, made falsifiable. If `odd` is ever narrowed to an integral type,
        // Jackson's ACCEPT_FLOAT_AS_INT turns every one of these into 1 or 0 with no
        // error anywhere and the assertion above starts failing instead of a
        // dashboard quietly lying.
        assertThat(fractionalOdds)
                .as("balls whose odd is fractional (0.3 / 1.2) — these are why odd is a double")
                .isEqualTo(54);

        // The measured RTP over the capture: 46 595 000 / 48 910 000 = 0.9527 against
        // the 16-row binomial's theoretical 0.95635. Asserted loosely ON PURPOSE —
        // per-ball standard deviation of the return multiple is ~1.2, so 81 balls
        // says almost nothing. This is a smoke check that the numbers are the right
        // ORDER, not an RTP anchor; the anchor needs thousands of balls.
        assertThat(totalStaked).isEqualTo(48_910_000L);
        assertThat(totalReturned).isEqualTo(46_595_000L);
        assertThat((double) totalReturned / totalStaked).isCloseTo(0.9527d, within(0.001d));
    }

    @Test
    @DisplayName("sum(mbs[].r) == p.wm in all 6 betting rounds — which is what makes AD-5 free")
    void theTwoOwnReturnCandidatesAgreeEverywhere() throws Exception {
        int bettingRounds = 0;
        int noBetRounds = 0;

        for (JsonNode round : endGameBodies()) {
            long sid = round.path("sid").asLong();
            long sumR = 0L;
            for (JsonNode ball : round.path("mbs")) {
                sumR += ball.path("r").asLong();
            }

            if (round.has("p")) {
                bettingRounds++;
                assertThat(sumR)
                        .as("round %d: the source we read (sum of mbs[].r) must equal "
                                + "the source we deliberately do NOT read (p.wm)", sid)
                        .isEqualTo(round.path("p").path("wm").asLong());
            } else {
                noBetRounds++;
                // `p` is absent — not zeroed — on a round this account did not bet,
                // and mbs is an empty array. Both reduce to 0 and onEndGame's `w > 0`
                // guard makes the round a no-op.
                assertThat(round.path("mbs")).isEmpty();
                assertThat(sumR).isZero();
            }
        }

        assertThat(bettingRounds).as("rounds with a bet").isEqualTo(6);
        assertThat(noBetRounds).as("rounds without a bet").isEqualTo(2);
    }

    @Test
    @DisplayName("tJpV is a RISING pool meter on this game — the positive evidence HasJackpotPool needs")
    void theJackpotMeterRises() throws Exception {
        List<Long> meter = new ArrayList<>();
        for (JsonNode round : endGameBodies()) {
            meter.add(round.path("tJpV").asLong());
            // No discharge was ever captured, which is precisely why HasJackpot stays
            // unimplemented (OI-5).
            assertThat(round.path("iJp").asBoolean())
                    .as("iJp was false in 8/8 rounds — no discharge observed")
                    .isFalse();
        }

        assertThat(meter).containsExactly(
                200_200L, 204_400L, 204_400L, 406_800L, 513_800L, 674_300L, 684_300L, 689_300L);
        // Monotonically non-decreasing and ending well above where it started: a
        // running pool, not a per-user payout. The other two 114 games' tJpV read 0
        // in every sample, which is why THEIR class stays unwired.
        assertThat(meter).isSorted();
        assertThat(meter.get(meter.size() - 1)).isGreaterThan(meter.get(0));
    }
}
