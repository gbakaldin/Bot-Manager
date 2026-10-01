package com.vingame.bot.domain.bot.message.request;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vingame.bot.domain.bot.message.g3.rik.RikGameMessageTypes;
import com.vingame.bot.domain.bot.message.g3.rik.RikZicZacGameMessageTypes;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.domain.game.model.GameType;
import com.vingame.websocketparser.message.request.ActionRequestMessage;
import com.vingame.websocketparser.message.request.Body;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Scanner;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ZicZacBet} and {@link ZicZacRequest#subscribe()} checked against the
 * <b>committed capture</b> rather than against a hand-typed literal — the ziczac
 * counterpart of {@link RikStockBetCaptureFidelityTest} (RIK_114_BETTING_MINI AD-22 /
 * AD-23, applied to RIK_114_ZICZAC Amendment A1 items 1, 2 and 5).
 *
 * <p>{@code ZicZacBetTest} pins the key set as a literal copied from the capture. That is
 * the right primary assertion, but the literal and the code share an author: a
 * transcription error would be in both. This class derives its expectation from
 * {@code captures/rik-ziczacPlugin-12000.jsonl}, the evidence file the plan cites, so
 * the test and the code have independent sources.
 *
 * <p>It also covers three things the literal tests do not:
 * <ul>
 *   <li><b>The mapper.</b> Production serializes outbound frames with a mapper configured
 *       {@code FAIL_ON_UNKNOWN_PROPERTIES=false} and
 *       {@code registerSubtypes(messageTypeRegistrations())} — for a ziczac bot the
 *       registrations are {@link RikZicZacGameMessageTypes}' at offset 9000, which no
 *       other test serializes through.</li>
 *   <li><b>The evidence behind "no {@code eid}", "{@code c == 1}" and "no commit",
 *       asserted rather than quoted</b>: every captured outbound bet carries {@code c:1}
 *       and no {@code eid}, and the capture's outbound cmd set contains no
 *       {@code 12022}.</li>
 *   <li><b>The subscribe frame</b>, compared to the captured client's {@code out 12000}
 *       rather than to {@code Request.subscribe()} (which {@code ZicZacRequestTest}
 *       already does) — the two comparisons together close the triangle.</li>
 * </ul>
 */
@DisplayName("ZicZacBet / ZicZacRequest vs the committed ziczacPlugin capture")
class ZicZacBetCaptureFidelityTest {

    private static final String CAPTURE = "captures/rik-ziczacPlugin-12000.jsonl";
    private static final int OFFSET = 9000;
    private static final int SUBSCRIBE_CMD = OFFSET + 3000;
    private static final int BET_CMD = OFFSET + 3002;
    private static final int COMMIT_CMD = OFFSET + 3022;

    /** A bare mapper — what the literal tests use. */
    private static final ObjectMapper BARE = new ObjectMapper();

    /**
     * The mapper a ziczac bot actually serializes outbound frames with
     * ({@code BettingMiniGameBot.botBehaviorScenario}): the registrations come from the
     * provider {@code forGame} resolved, i.e. the ziczac specialisation, at the game's
     * offset.
     */
    private static ObjectMapper productionShapedMapper() {
        Game ziczac = Game.builder()
                .gameType(GameType.BETTING_MINI)
                .pluginName("ziczacPlugin")
                .offset(OFFSET)
                .md5(false)
                .build();
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.registerSubtypes(new RikGameMessageTypes().forGame(ziczac).getTypeRegistrations(OFFSET, false));
        return mapper;
    }

    private static Body body(Object message) throws Exception {
        Field f = ActionRequestMessage.class.getDeclaredField("body");
        f.setAccessible(true);
        return (Body) f.get(message);
    }

    /** Every outbound frame in the capture, in order. */
    private static List<JsonNode> capturedOutbound() throws Exception {
        List<JsonNode> frames = new ArrayList<>();
        try (InputStream in = ZicZacBetCaptureFidelityTest.class.getClassLoader()
                .getResourceAsStream(CAPTURE)) {
            assertThat(in).as("capture resource %s must be on the test classpath", CAPTURE).isNotNull();
            Scanner scanner = new Scanner(in, StandardCharsets.UTF_8);
            while (scanner.hasNextLine()) {
                String line = scanner.nextLine().trim();
                if (line.isEmpty()) {
                    continue;
                }
                JsonNode frame = BARE.readTree(line);
                if ("out".equals(frame.path("dir").asText()) && frame.has("body")) {
                    frames.add(frame);
                }
            }
        }
        return frames;
    }

    /** Every outbound body with the given cmd, in order. */
    private static List<JsonNode> capturedOutboundBodies(int cmd) throws Exception {
        List<JsonNode> bodies = new ArrayList<>();
        for (JsonNode frame : capturedOutbound()) {
            if (frame.path("cmd").asInt() == cmd) {
                bodies.add(frame.get("body"));
            }
        }
        return bodies;
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
        // 8 exported of 25 observed: the export is shape-deduped on the outbound side
        // (plan §1). 8 is a floor for the assertions below, not a census.
        assertThat(capturedOutboundBodies(BET_CMD))
                .as("outbound cmd:%d frames in %s", BET_CMD, CAPTURE)
                .hasSize(8);
        assertThat(capturedOutboundBodies(SUBSCRIBE_CMD))
                .as("outbound cmd:%d frames in %s", SUBSCRIBE_CMD, CAPTURE)
                .hasSize(1);
    }

    @Test
    @DisplayName("our key set equals the real client's, frame for frame — read off the capture, not typed")
    void keySetMatchesEveryCapturedFrame() throws Exception {
        Set<String> ourKeys = keysOf(BARE.readTree(BARE.writeValueAsString(
                body(new ZicZacBet(BET_CMD, "MiniGame", "ziczacPlugin", 60_000L, 1995084L)))));

        List<JsonNode> captured = capturedOutboundBodies(BET_CMD);
        for (int i = 0; i < captured.size(); i++) {
            assertThat(ourKeys)
                    .as("captured outbound frame #%d: %s", i, captured.get(i))
                    .isEqualTo(keysOf(captured.get(i)));
        }
    }

    @Test
    @DisplayName("every captured outbound bet carries c == 1 and no eid — the evidence behind AD-10 / AD-11, asserted not quoted")
    void everyCapturedBetIsOneBallWithoutAnEntryId() throws Exception {
        for (JsonNode captured : capturedOutboundBodies(BET_CMD)) {
            assertThat(captured.has("eid")).as("captured frame %s carries no eid", captured).isFalse();
            assertThat(captured.has("v")).as("captured frame %s carries no v", captured).isFalse();
            assertThat(captured.has("iAc")).as("captured frame %s carries no iAc", captured).isFalse();
            assertThat(captured.path("c").asInt()).as("captured frame %s has c == 1", captured).isEqualTo(1);
            assertThat(captured.path("aid").asInt()).as("captured frame %s has aid == 1", captured).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("rebuilding a captured frame from its own values reproduces it node-for-node — including the 1 060 000 stake")
    void rebuildingACapturedFrameReproducesIt() throws Exception {
        boolean sawTheLargeStake = false;
        for (JsonNode captured : capturedOutboundBodies(BET_CMD)) {
            ZicZacBet ours = new ZicZacBet(
                    captured.get("cmd").asInt(),
                    "MiniGame",
                    "ziczacPlugin",
                    captured.get("b").asLong(),
                    captured.get("sid").asLong());

            assertThat(BARE.readTree(BARE.writeValueAsString(body(ours))))
                    .as("rebuild of %s", captured)
                    .isEqualTo(captured);
            sawTheLargeStake |= captured.get("b").asLong() == 1_060_000L;
        }
        // The capture shows two stakes accepted (60 000 and 1 060 000); if the export
        // ever loses the second shape this rebuild is weaker than it looks, so say so.
        assertThat(sawTheLargeStake).as("the capture still carries the 1 060 000 stake").isTrue();
    }

    @Test
    @DisplayName("the whole envelope, built through ZicZacRequest, matches the captured frame: zone, plugin, body")
    void requestBuiltFrameMatchesTheCapturedEnvelope() throws Exception {
        ZicZacRequest request = new ZicZacRequest("ziczacPlugin", "MiniGame", OFFSET);

        for (JsonNode captured : capturedOutbound()) {
            if (captured.path("cmd").asInt() != BET_CMD) {
                continue;
            }
            JsonNode body = captured.get("body");
            JsonNode ours = BARE.readTree(request
                    .bet(body.get("b").asLong(), 0, body.get("sid").asLong())
                    .serialize(BARE));

            assertThat(ours.get(1).asText()).isEqualTo(captured.get("zone").asText());
            assertThat(ours.get(2).asText()).isEqualTo(captured.get("plugin").asText());
            assertThat(ours.get(3)).as("body of %s", captured).isEqualTo(body);
        }
    }

    @Test
    @DisplayName("subscribe() is node-equal to the captured client's out 12000 — zone, plugin and body")
    void subscribeMatchesTheCapturedSubscribe() throws Exception {
        JsonNode capturedSubscribe = null;
        for (JsonNode frame : capturedOutbound()) {
            if (frame.path("cmd").asInt() == SUBSCRIBE_CMD) {
                capturedSubscribe = frame;
                break;
            }
        }
        assertThat(capturedSubscribe).isNotNull();

        JsonNode ours = BARE.readTree(
                new ZicZacRequest("ziczacPlugin", "MiniGame", OFFSET).subscribe().serialize(BARE));

        assertThat(ours.get(1).asText()).isEqualTo(capturedSubscribe.get("zone").asText());
        assertThat(ours.get(2).asText()).isEqualTo(capturedSubscribe.get("plugin").asText());
        assertThat(ours.get(3)).isEqualTo(capturedSubscribe.get("body"));
        assertThat(keysOf(ours.get(3))).containsExactly("cmd");
    }

    @Test
    @DisplayName("the capture's outbound cmd set carries NO 12022 — the evidence behind 'ziczac has no commit'")
    void theCapturedClientNeverCommits() throws Exception {
        Set<Integer> outboundCmds = new TreeSet<>();
        for (JsonNode frame : capturedOutbound()) {
            outboundCmds.add(frame.path("cmd").asInt());
        }

        assertThat(outboundCmds).contains(SUBSCRIBE_CMD, BET_CMD);
        assertThat(outboundCmds).as("outbound cmds in %s", CAPTURE).doesNotContain(COMMIT_CMD);
        // Recorded, not modelled: the real client also sends 12012 {"iM":false} once and
        // 12018 {} seven times. Amendment A1 §2 measured that the feed holds on subscribe
        // alone, so neither is emitted by ZicZacRequest — and the bot-level dispatch test
        // pins that nothing but 12000 and 12002 leaves the socket. This assertion keeps
        // the capture honest about which frames a future phase would be reading from.
        assertThat(outboundCmds).containsExactly(SUBSCRIBE_CMD, BET_CMD, OFFSET + 3012, OFFSET + 3018);
    }

    @Test
    @DisplayName("the PRODUCTION mapper — the ziczac provider's subtype registrations at 9000 — produces the same wire keys")
    void productionMapperProducesTheSameWireKeys() throws Exception {
        ZicZacBet bet = new ZicZacBet(BET_CMD, "MiniGame", "ziczacPlugin", 60_000L, 1995084L);
        ObjectMapper production = productionShapedMapper();

        String json = production.writeValueAsString(body(bet));

        assertThat(keysOf(BARE.readTree(json))).containsExactly("aid", "b", "c", "cmd", "sid");
        assertThat(json).contains("\"c\":1").doesNotContain("\"eid\"");
        // And the full frame, through the same mapper the scenario hands to the pipeline,
        // node-equal to what the bare mapper produced.
        assertThat(BARE.readTree(bet.serialize(production)))
                .isEqualTo(BARE.readTree(bet.serialize(BARE)));
        assertThat(bet.serialize(production)).startsWith("[\"6\",\"MiniGame\",\"ziczacPlugin\",{");
    }

    @Test
    @DisplayName("the stake is a long on the wire: a stake above Integer.MAX_VALUE is not truncated (AD-3)")
    void stakeIsNotTruncatedToAnInt() throws Exception {
        long stake = 5_000_000_000L;

        JsonNode node = BARE.readTree(BARE.writeValueAsString(
                body(new ZicZacBet(BET_CMD, "MiniGame", "ziczacPlugin", stake, 1995084L))));

        assertThat(node.get("b").asLong()).isEqualTo(stake);
        assertThat(node.get("b").canConvertToInt()).isFalse();
        assertThat(node.get("c").asInt()).as("c stays 1 whatever b is").isEqualTo(1);
    }
}
