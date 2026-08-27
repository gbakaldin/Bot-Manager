package com.vingame.bot.domain.bot.strategy.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vingame.bot.common.exception.RestExceptionHandler;
import com.vingame.bot.domain.bot.strategy.StrategyId;
import com.vingame.bot.domain.bot.strategy.dto.StrategyInfoDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PLUGIN_HOT_RELOAD Phase 2d, AD-21 — "{@code StrategyController}'s response
 * contract is preserved exactly".
 *
 * <p>Phase 2d re-sources the listing from the registry rather than from
 * {@code StrategyId.values()}. Everything an HTTP client can observe must be
 * unchanged, and "exactly" here is taken to cover all four of: the <b>path</b>,
 * the <b>field set</b> of each entry, the <b>values</b> of those fields, and the
 * <b>order</b> of the list.
 *
 * <p><b>Order is the part nothing pinned before.</b> The pre-existing
 * {@link StrategyControllerTest} asserts a length and a handful of
 * {@code hasItem} matchers — every one of which passes against an arbitrarily
 * shuffled list. AD-21 exists because {@code StrategyId.values()} order is a
 * de-facto UI contract nobody wrote down (it is the sequence the strategy picker
 * renders), and Amendment A4 measured that the registry's own iteration order is
 * <em>not</em> that order — it is bean-discovery order, alphabetical by class file
 * name within package, agreeing on {@code RANDOM} alone and differing in all
 * eight remaining positions, and silently changing if a strategy class is renamed. So the ordering has to come from an explicit
 * sort, and this is the test that fails if someone removes it.
 *
 * <p><b>The baseline is executable, not copy-pasted.</b>
 * {@link #preChangeBody()} evaluates the exact expression the controller carried
 * before this phase — {@code Arrays.stream(StrategyId.values()).map(StrategyInfoDTO::of)}
 * — and serialises it with the slice's own {@code ObjectMapper}, i.e. the one the
 * message converter uses. A byte-for-byte string comparison against that is the
 * strongest available form of "unchanged".
 */
@WebMvcTest(StrategyController.class)
@Import({RestExceptionHandler.class, StrategyCatalogResponseContractTest.RealStrategyRegistries.class})
@DisplayName("GET /api/v1/strategy/ response contract (AD-21)")
class StrategyCatalogResponseContractTest {

    /** See {@code StrategyControllerTest.RealStrategyRegistries}. */
    @TestConfiguration
    @ComponentScan(
            basePackages = "com.vingame.bot.domain.bot.strategy",
            excludeFilters = @ComponentScan.Filter(
                    type = FilterType.ANNOTATION, classes = RestController.class))
    static class RealStrategyRegistries {
    }

    /**
     * The sequence the picker renders, written down as literals rather than
     * derived from {@code StrategyId.values()}. Derived, it would follow a
     * reordering of the enum silently — which is exactly the change AD-21 says
     * must not reach the UI without someone noticing.
     * {@link #literalOrderMatchesTheEnumDeclarationOrder()} keeps the two honest
     * about each other.
     */
    /**
     * Classpath-relative to this test class. Kept next to the test rather than
     * under a shared fixtures root: it is the response of one endpoint, and the
     * failure message has to point a reader straight at the file to edit.
     */
    private static final String CATALOGUE_FIXTURE = "/strategy/betting-strategy-catalogue.json";

    private static final List<String> EXPECTED_ORDER = List.of(
            "RANDOM",
            "MARTINGALE_CLASSIC_CAUTIOUS",
            "MARTINGALE_CLASSIC_AGGRESSIVE",
            "PAROLI_CAUTIOUS",
            "PAROLI_AGGRESSIVE",
            "DALEMBERT_CAUTIOUS",
            "DALEMBERT_AGGRESSIVE",
            "FIBONACCI_CAUTIOUS",
            "FIBONACCI_AGGRESSIVE");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("No gameType: the body is byte-identical to the pre-Phase-2d expression")
    void noGameTypeBodyIsByteIdenticalToTheBaseline() throws Exception {
        assertThat(body("/api/v1/strategy/")).isEqualTo(preChangeBody());
    }

    @Test
    @DisplayName("gameType=BETTING_MINI: byte-identical to the pre-Phase-2d expression")
    void bettingMiniBodyIsByteIdenticalToTheBaseline() throws Exception {
        assertThat(body("/api/v1/strategy/?gameType=BETTING_MINI")).isEqualTo(preChangeBody());
    }

    @Test
    @DisplayName("gameType=TAI_XIU: byte-identical to the pre-Phase-2d expression (reuses the betting family)")
    void taiXiuBodyIsByteIdenticalToTheBaseline() throws Exception {
        assertThat(body("/api/v1/strategy/?gameType=TAI_XIU")).isEqualTo(preChangeBody());
    }

    @Test
    @DisplayName("gameType=SLOT: still an empty array, not the slot registry")
    void slotBodyIsStillEmpty() throws Exception {
        // The slot registry has two registered keys and SlotStrategyId carries
        // display copy for both, so serving them here would compile and look like
        // an improvement. AD-21 forbids it: slots always run FIXED and the picker
        // has never offered a slot strategy.
        assertThat(body("/api/v1/strategy/?gameType=SLOT")).isEqualTo("[]");
    }

    @Test
    @DisplayName("gameType=CARD_GAME: still an empty array")
    void unimplementedGameTypeBodyIsStillEmpty() throws Exception {
        assertThat(body("/api/v1/strategy/?gameType=CARD_GAME")).isEqualTo("[]");
    }

    @Test
    @DisplayName("The ids are in StrategyId declaration order, built-ins first (AD-21)")
    void idsAreInDeclarationOrder() throws Exception {
        List<StrategyInfoDTO> listed = parse(body("/api/v1/strategy/"));

        assertThat(listed)
                .extracting(StrategyInfoDTO::id)
                .as("the strategy picker renders this sequence — an explicit sort must "
                        + "produce it, never the registry's incidental iteration order (A4)")
                .containsExactlyElementsOf(EXPECTED_ORDER);
    }

    @Test
    @DisplayName("Every entry carries exactly id / displayName / description, all non-blank")
    void everyEntryCarriesTheFullFieldSet() throws Exception {
        JsonNode array = objectMapper.readTree(body("/api/v1/strategy/"));
        assertThat(array.size()).isEqualTo(EXPECTED_ORDER.size());
        for (JsonNode entry : array) {
            List<String> fields = new ArrayList<>();
            entry.fieldNames().forEachRemaining(fields::add);
            assertThat(fields)
                    .as("wire field set of %s", entry)
                    .containsExactly("id", "displayName", "description");
        }

        assertThat(parse(body("/api/v1/strategy/")))
                .allSatisfy(dto -> {
                    assertThat(dto.id()).isNotBlank();
                    assertThat(dto.displayName()).isNotBlank();
                    assertThat(dto.description()).isNotBlank();
                });
    }

    /**
     * The one thing {@link #preChangeBody()} cannot see: the <b>copy itself</b>.
     *
     * <p>The byte-identity tests above serialise {@code StrategyId.values()} on
     * both sides, so they compare the enum with itself — an edit to a constant's
     * {@code displayName} or {@code description} moves expected and actual
     * together and they stay green. So does every other assertion in the build:
     * {@code StrategyControllerTest}'s "locked-in strings" test reads the same
     * getters it asserts against, and its only literal is {@code "Random"}. QA
     * confirmed this by mutation — renaming {@code FIBONACCI_CAUTIOUS}'s
     * displayName to {@code "Fibonacci (Safe)"} passed all 1998 tests.
     *
     * <p>This fixture is therefore the only <b>independent</b> statement of the
     * response in the repo: a checked-in file, not an expression. It is the
     * build-time form of verification P2-3's before/after {@code curl} diff,
     * which otherwise only catches a copy change if someone remembered to take
     * the pre-deploy capture. MARTINGALE_STRATEGIES A1 calls these strings
     * locked-in and the frontend renders them verbatim into the picker; changing
     * one is allowed, but it must be a deliberate act that updates this file.
     *
     * <p>Compared as parsed trees, so it pins element order, the field set of
     * each entry and every value, while staying indifferent to pretty-printing.
     */
    @Test
    @DisplayName("The body matches the checked-in catalogue fixture, copy included")
    void bodyMatchesTheCheckedInCopyFixture() throws Exception {
        JsonNode expected;
        try (InputStream fixture = getClass().getResourceAsStream(CATALOGUE_FIXTURE)) {
            assertThat(fixture).as("missing test resource %s", CATALOGUE_FIXTURE).isNotNull();
            expected = objectMapper.readTree(fixture);
        }

        assertThat(objectMapper.readTree(body("/api/v1/strategy/")))
                .as("the strategy picker's ids, labels, tooltips and their order — if this "
                        + "changed on purpose, update %s in the same commit", CATALOGUE_FIXTURE)
                .isEqualTo(expected);
    }

    /**
     * The literal sequence above and the enum are two statements of one contract.
     * If someone reorders {@code StrategyId}, this fails and forces the question
     * "did you mean to reorder the picker?" — which is the whole reason AD-21
     * names declaration order in the first place.
     */
    @Test
    @DisplayName("The literal expected order is StrategyId declaration order")
    void literalOrderMatchesTheEnumDeclarationOrder() {
        assertThat(Arrays.stream(StrategyId.values()).map(StrategyId::name).toList())
                .containsExactlyElementsOf(EXPECTED_ORDER);
    }

    /** The exact expression {@code StrategyController} carried before Phase 2d. */
    private String preChangeBody() throws Exception {
        return objectMapper.writeValueAsString(
                Arrays.stream(StrategyId.values()).map(StrategyInfoDTO::of).toList());
    }

    private String body(String uri) throws Exception {
        return mockMvc.perform(get(uri))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private List<StrategyInfoDTO> parse(String json) throws Exception {
        return objectMapper.readValue(json, objectMapper.getTypeFactory()
                .constructCollectionType(List.class, StrategyInfoDTO.class));
    }
}
