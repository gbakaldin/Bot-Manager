package com.vingame.bot.domain.bot.message.request;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vingame.bot.domain.bot.message.g3.rik.RikGameMessageTypes;
import com.vingame.websocketparser.message.request.ActionRequestMessage;
import com.vingame.websocketparser.message.request.Body;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Scanner;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RikStockBet} checked against the <b>committed capture</b> rather than against a
 * hand-typed literal (RIK_114_BETTING_MINI AD-22 / AD-23).
 *
 * <p>{@code RikStockBetTest} pins the key set as a literal, which is the right primary
 * assertion but shares an author with the code: if the transcription of the frame were
 * wrong, both would be wrong together. This class derives its expectation from
 * {@code captures/rik-stockPlugin-13000.jsonl} — the same evidence file the plan cites —
 * so the test and the code have independent sources.
 *
 * <p>It also closes two gaps the literal test cannot:
 * <ul>
 *   <li><b>The mapper.</b> Every other assertion in this feature serializes with a bare
 *       {@code new ObjectMapper()}. Production does not: {@code BettingMiniGameBot}
 *       builds its outbound mapper with {@code FAIL_ON_UNKNOWN_PROPERTIES=false} and
 *       {@code registerSubtypes(messageTypeRegistrations())}. Neither affects property
 *       naming — but that is a claim, and an untested claim about naming is exactly the
 *       class of bug AD-23 is about, so it is asserted rather than assumed.</li>
 *   <li><b>The mechanism, not just the symptom.</b> The {@code iac} string assertions
 *       catch a mangled key after the fact; {@link #betDataExposesNoGetterForTheFlag()}
 *       keeps the class from depending on a repo-root config file to stay correct — see
 *       that test for the measurement.</li>
 * </ul>
 */
@DisplayName("RikStockBet vs the committed stockPlugin capture")
class RikStockBetCaptureFidelityTest {

    private static final String CAPTURE = "captures/rik-stockPlugin-13000.jsonl";
    private static final int BET_CMD = 13002;

    /** A bare mapper — what the tests elsewhere in this feature use. */
    private static final ObjectMapper BARE = new ObjectMapper();

    /**
     * The mapper production actually serializes outbound frames with
     * ({@code BettingMiniGameBot.botBehaviorScenario}).
     */
    private static ObjectMapper productionShapedMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.registerSubtypes(new RikGameMessageTypes().getTypeRegistrations(10000, false));
        return mapper;
    }

    private static Body body(Object message) throws Exception {
        Field f = ActionRequestMessage.class.getDeclaredField("body");
        f.setAccessible(true);
        return (Body) f.get(message);
    }

    /** Every outbound {@code cmd:13002} body in the capture, in order. */
    private static List<JsonNode> capturedOutboundBets() throws Exception {
        List<JsonNode> bets = new ArrayList<>();
        try (InputStream in = RikStockBetCaptureFidelityTest.class.getClassLoader()
                .getResourceAsStream(CAPTURE)) {
            assertThat(in).as("capture resource %s must be on the test classpath", CAPTURE).isNotNull();
            Scanner scanner = new Scanner(in, StandardCharsets.UTF_8);
            while (scanner.hasNextLine()) {
                String line = scanner.nextLine().trim();
                if (line.isEmpty()) {
                    continue;
                }
                JsonNode frame = BARE.readTree(line);
                if ("out".equals(frame.path("dir").asText())
                        && frame.path("cmd").asInt() == BET_CMD
                        && frame.has("body")) {
                    bets.add(frame.get("body"));
                }
            }
        }
        return bets;
    }

    private static Set<String> keysOf(JsonNode node) {
        Set<String> keys = new TreeSet<>();
        for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
            keys.add(it.next());
        }
        return keys;
    }

    @Test
    @DisplayName("the capture really does contain outbound bets — otherwise everything below is vacuous")
    void theCaptureIsNotEmpty() throws Exception {
        assertThat(capturedOutboundBets())
                .as("outbound cmd:%d frames in %s", BET_CMD, CAPTURE)
                .hasSize(8);
    }

    @Test
    @DisplayName("our key set equals the real client's, frame for frame — read off the capture, not typed")
    void keySetMatchesEveryCapturedFrame() throws Exception {
        String ours = BARE.writeValueAsString(
                body(new RikStockBet(BET_CMD, "MiniGame", "stockPlugin", 1000L, 0, 3793247L)));
        Set<String> ourKeys = keysOf(BARE.readTree(ours));

        List<JsonNode> captured = capturedOutboundBets();
        for (int i = 0; i < captured.size(); i++) {
            assertThat(ourKeys)
                    .as("captured outbound frame #%d: %s", i, captured.get(i))
                    .isEqualTo(keysOf(captured.get(i)));
        }
    }

    @Test
    @DisplayName("no captured outbound bet carries 'b' — the evidence behind AD-22, asserted not quoted")
    void noCapturedFrameCarriesTheSharedStakeKey() throws Exception {
        for (JsonNode captured : capturedOutboundBets()) {
            assertThat(captured.has("b"))
                    .as("captured frame %s", captured)
                    .isFalse();
            assertThat(captured.has("iAc")).as("captured frame %s", captured).isTrue();
            assertThat(captured.has("iac")).as("captured frame %s", captured).isFalse();
        }
    }

    @Test
    @DisplayName("rebuilding a captured frame from its own values reproduces it node-for-node")
    void rebuildingACapturedFrameReproducesIt() throws Exception {
        for (JsonNode captured : capturedOutboundBets()) {
            RikStockBet ours = new RikStockBet(
                    captured.get("cmd").asInt(),
                    "MiniGame",
                    "stockPlugin",
                    captured.get("v").asLong(),
                    captured.get("eid").asLong(),
                    captured.get("sid").asLong());

            assertThat(BARE.readTree(BARE.writeValueAsString(body(ours))))
                    .as("rebuild of %s", captured)
                    .isEqualTo(captured);
        }
    }

    @Test
    @DisplayName("the PRODUCTION mapper produces the same keys — subtype registration does not rename anything")
    void productionMapperProducesTheSameWireKeys() throws Exception {
        RikStockBet bet = new RikStockBet(BET_CMD, "MiniGame", "stockPlugin", 1000L, 0, 3793247L);

        String json = productionShapedMapper().writeValueAsString(body(bet));

        assertThat(keysOf(BARE.readTree(json)))
                .containsExactlyInAnyOrder("cmd", "v", "sid", "aid", "eid", "iAc");
        assertThat(json).contains("\"iAc\":true");
        assertThat(json).doesNotContain("\"iac\"");
        // And the full frame, through the same mapper the scenario hands to the pipeline.
        assertThat(bet.serialize(productionShapedMapper()))
                .startsWith("[\"6\",\"MiniGame\",\"stockPlugin\",{")
                .contains("\"iAc\":true")
                .doesNotContain("\"iac\"");
    }

    @Test
    @DisplayName("AD-23 MECHANISM: the iAc field exposes no getter, so correctness needs no lombok.config")
    void betDataExposesNoGetterForTheFlag() {
        // Measured on this build (QA, Phase 2); the same table is in the class
        // javadoc of RikStockBet, which was corrected to match it:
        //
        //   shipped (@Getter(NONE) + @JsonProperty)  -> {"iAc":true}          correct
        //   no annotation, Lombok isIAc()            -> {"iac":true}          WRONG
        //   @Getter(NONE) + hand-rolled getIAc()     -> {"iac":true}          WRONG
        //   @JsonProperty + Lombok's isIAc()         -> {"iAc":true}          correct!
        //   @JsonProperty + HAND-WRITTEN isIAc()     -> {"iac":..,"iAc":..}   WRONG, both
        //
        // The fourth row is correct only because the repo root carries a lombok.config
        // with `lombok.copyableAnnotations += com.fasterxml.jackson.annotation.JsonProperty`,
        // so Lombok stamps @JsonProperty onto the getter it generates and Jackson merges
        // the two into one property. Lombok cannot do that for a getter a human wrote —
        // hence the fifth row, the genuine double-key frame, which the key-set
        // assertions above and in RikStockBetTest both kill.
        //
        // So this assertion is not "otherwise the key is wrong". It is: keep the single
        // property on the field, and this body stays correct whatever happens to a
        // config file two directories up that nothing else in this feature mentions.
        List<String> offenders = new ArrayList<>();
        for (Method m : RikStockBet.BetData.class.getMethods()) {
            if (m.getParameterCount() != 0) {
                continue;
            }
            String n = m.getName();
            if (n.equalsIgnoreCase("isIAc") || n.equalsIgnoreCase("getIAc")) {
                offenders.add(n);
            }
        }
        assertThat(offenders)
                .as("accessors for iAc on BetData — @Getter(AccessLevel.NONE) must stay, so "
                        + "the wire key does not depend on the root lombok.config")
                .isEmpty();
    }

    @Test
    @DisplayName("the stake is a long on the wire: a stake above Integer.MAX_VALUE is not truncated (AD-3)")
    void stakeIsNotTruncatedToAnInt() throws Exception {
        long stake = 5_000_000_000L;

        JsonNode node = BARE.readTree(BARE.writeValueAsString(
                body(new RikStockBet(BET_CMD, "MiniGame", "stockPlugin", stake, 1, 3793247L))));

        assertThat(node.get("v").asLong()).isEqualTo(stake);
        assertThat(node.get("v").canConvertToInt()).isFalse();
    }
}
