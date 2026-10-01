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

/**
 * Binds every RIK fixture to the committed wire capture it came from (AD-14, AD-18).
 * <p>
 * Each {@code /messages/rik/*.json} must be <b>node-equal to the body of some frame
 * in its own capture</b> — three live captures, each carried byte-for-byte including
 * its {@code _meta} header line:
 * <ul>
 *   <li>{@code txmd5-*} → {@code /captures/rik-taixiuMd5Plugin-7000.jsonl}</li>
 *   <li>{@code ziczac-*} → {@code /captures/rik-ziczacPlugin-12000.jsonl}</li>
 *   <li>everything else → {@code /captures/rik-stockPlugin-13000.jsonl}</li>
 * </ul>
 * <b>The mapping is per-fixture on purpose.</b> "Matches a frame in one of the three
 * files" would be a weaker assertion than the one this test exists to make: it would
 * let a stock fixture silently be satisfied by a txmd5 frame, which is exactly the
 * kind of cross-contamination that makes a shape claim untrue.
 * <p>
 * <b>The prefix rule is derived from the directory listing, not from hand-maintained
 * lists</b> (RIK_114_ZICZAC AD-14). The lists this class used to carry were the
 * check's own inventory, so the way to escape the check was to add a file and forget
 * to list it — which adding six ziczac fixtures at once is exactly the shape of.
 * Every {@code *.json} on disk is now claimed by a prefix rule, and a fixture
 * matching none of them <b>fails loudly</b> rather than being silently unverified.
 * <p>
 * That turns "these fixtures are real frames" from a javadoc claim (which is all
 * {@code Win79GameMessageTypesTest} has) into something the build enforces: a fixture
 * hand-edited to make an assertion pass, or trimmed for size, fails here. Carrying
 * the three 15-25 KB captures in the repo is worth it for exactly this reason; they
 * are test-scope only and never enter the app jar.
 * <p>
 * Node equality, not text equality — the fixtures are pretty-printed and the captures
 * are one frame per line, and neither key order nor whitespace is a property of the
 * wire.
 */
@DisplayName("RIK fixtures are verbatim frames from the committed captures")
class RikFixtureProvenanceTest {

    private static final String STOCK_CAPTURE = "/captures/rik-stockPlugin-13000.jsonl";
    private static final String TXMD5_CAPTURE = "/captures/rik-taixiuMd5Plugin-7000.jsonl";
    private static final String ZICZAC_CAPTURE = "/captures/rik-ziczacPlugin-12000.jsonl";

    /**
     * Prefix → capture. Order matters only in that the fallback is last; the two
     * prefixes are disjoint, and a fixture matching no prefix takes the fallback,
     * which is what {@code everyFixtureCameFromItsOwnCapture} then fails on.
     */
    private static String captureFor(String fixture) {
        if (fixture.startsWith("txmd5-")) {
            return TXMD5_CAPTURE;
        }
        if (fixture.startsWith("ziczac-")) {
            return ZICZAC_CAPTURE;
        }
        return STOCK_CAPTURE;
    }

    /** Every {@code *.json} in {@code /messages/rik}, enumerated off disk. */
    private List<String> fixturesOnDisk() throws Exception {
        java.net.URL dir = getClass().getResource("/messages/rik");
        assertThat(dir).as("fixture directory /messages/rik").isNotNull();
        try (var files = java.nio.file.Files.list(java.nio.file.Path.of(dir.toURI()))) {
            return files.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".json"))
                    .sorted()
                    .toList();
        }
    }

    private final ObjectMapper mapper = new ObjectMapper();

    private List<JsonNode> captureBodies(String capture) throws Exception {
        List<JsonNode> bodies = new ArrayList<>();
        try (InputStream in = getClass().getResourceAsStream(capture)) {
            assertThat(in).as("capture " + capture).isNotNull();
            try (BufferedReader reader =
                         new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    JsonNode frame = mapper.readTree(line);
                    // The first line is the capture's _meta header, not a frame.
                    if (frame.has("body")) {
                        bodies.add(frame.get("body"));
                    }
                }
            }
        }
        return bodies;
    }

    private List<Integer> cmdsIn(String capture) throws Exception {
        return captureBodies(capture).stream().map(b -> b.path("cmd").asInt()).distinct().toList();
    }

    @Test
    @DisplayName("the stock capture is present and holds the four contracted inbound CMDs")
    void stockCaptureIsIntact() throws Exception {
        // _meta reports exported: 50 — the header line carries no body and is skipped.
        assertThat(captureBodies(STOCK_CAPTURE)).hasSize(50);
        assertThat(cmdsIn(STOCK_CAPTURE)).contains(13000, 13002, 13005, 13006);
    }

    @Test
    @DisplayName("the txmd5 capture is present, holds 7000/7005/7006 — and no 7002 at all")
    void txmd5CaptureIsIntact() throws Exception {
        // _meta reports exported: 22.
        assertThat(captureBodies(TXMD5_CAPTURE)).hasSize(22);

        List<Integer> cmds = cmdsIn(TXMD5_CAPTURE);
        assertThat(cmds).contains(7000, 7005, 7006);
        // The absence is itself a finding worth pinning: this game emits no UpdateBet
        // at all — 96 s and 2 rounds with the account holding a bet, and not one 7002.
        // Its intra-round crowd rides 7007, which the four-CODE contract has no slot
        // for, so RikUpdateBetMessage is simply never constructed for this game. If a
        // later capture does show a 7002, that is new information, not a fixed bug.
        assertThat(cmds).doesNotContain(7002);
    }

    @Test
    @DisplayName("the ziczac capture is present and holds the four contracted CMDs plus the room feed")
    void ziczacCaptureIsIntact() throws Exception {
        // _meta reports exported: 73 — the header line carries no body and is skipped.
        assertThat(captureBodies(ZICZAC_CAPTURE)).hasSize(73);

        List<Integer> cmds = cmdsIn(ZICZAC_CAPTURE);
        assertThat(cmds).contains(12000, 12002, 12005, 12006);
        // 12007 (the room feed) and 12019 (room-wide ball results) are present and
        // have NO slot in the four-CODE contract, so they are never deserialized.
        // They are kept in the capture because that is where an unmodelled shape
        // belongs — see RikZicZacEndGameMessage on why the room feed is out of scope.
        assertThat(cmds).contains(12007, 12019);
    }

    /**
     * A fixture that no prefix rule claims is a fixture nothing binds to a capture —
     * the exact loophole this class exists to close, and previously reopened by the
     * simple act of adding a file. This is the directory listing itself, so there is
     * no inventory left to forget to update; the three-way prefix rule is the only
     * thing a new file has to satisfy.
     */
    @Test
    @DisplayName("every fixture on disk is claimed by exactly one capture, by prefix")
    void everyFixtureOnDiskIsClaimedByExactlyOneCapture() throws Exception {
        List<String> onDisk = fixturesOnDisk();

        // Sanity: the listing found the real directory, not an empty one — an empty
        // list would make every assertion below vacuously true.
        assertThat(onDisk).as("fixtures in /messages/rik").hasSizeGreaterThanOrEqualTo(15);

        // Each fixture resolves to exactly one capture, and all three are used.
        List<String> captures = onDisk.stream().map(RikFixtureProvenanceTest::captureFor).toList();
        assertThat(captures).contains(STOCK_CAPTURE, TXMD5_CAPTURE, ZICZAC_CAPTURE);

        // A fixture whose name matches no prefix falls through to the stock capture,
        // which is only correct for stock fixtures. Pin the naming convention so a
        // fourth game's fixture cannot be silently verified against stock's frames:
        // anything that is not a known stock fixture must carry a game prefix.
        List<String> stockFixtures =
                List.of("subscribe.json", "startGame.json", "updateBet.json",
                        "endGame.json", "endGame-loss.json");
        List<String> unprefixed = onDisk.stream()
                .filter(n -> captureFor(n).equals(STOCK_CAPTURE))
                .toList();
        assertThat(unprefixed)
                .as("an un-prefixed fixture is verified against the STOCK capture — a new "
                        + "game's fixtures must be named '<game>-*.json' and given a prefix rule")
                .containsExactlyInAnyOrderElementsOf(stockFixtures);
    }

    @Test
    @DisplayName("every fixture is node-equal to a real frame body in ITS OWN capture")
    void everyFixtureCameFromItsOwnCapture() throws Exception {
        for (String fixture : fixturesOnDisk()) {
            String capture = captureFor(fixture);
            List<JsonNode> bodies = captureBodies(capture);

            JsonNode parsed;
            try (InputStream in = getClass().getResourceAsStream("/messages/rik/" + fixture)) {
                assertThat(in).as("fixture /messages/rik/" + fixture).isNotNull();
                parsed = mapper.readTree(in);
            }
            assertThat(bodies)
                    .as("fixture /messages/rik/%s must be a verbatim frame body from %s", fixture, capture)
                    .contains(parsed);
        }
    }
}
