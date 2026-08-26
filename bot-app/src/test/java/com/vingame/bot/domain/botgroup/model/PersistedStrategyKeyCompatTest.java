package com.vingame.bot.domain.botgroup.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vingame.bot.domain.bot.strategy.StrategyId;
import com.vingame.bot.domain.bot.strategy.WeightedStrategy;
import com.vingame.bot.domain.bot.strategy.slot.SlotStrategyId;
import com.vingame.bot.domain.botgroup.dto.BotGroupDTO;
import com.vingame.bot.domain.botgroup.dto.BotHealthDTO;
import org.bson.Document;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.core.convert.NoOpDbRefResolver;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PLUGIN_HOT_RELOAD Phase 2b, AD-14 — the persisted-compatibility guard.
 *
 * <p>Phase 2b retypes {@code WeightedStrategy.strategyId} and
 * {@code BotGroup.slotStrategyId} from {@link StrategyId} / {@link SlotStrategyId}
 * to {@code String}. AD-14 claims that is free: this application registers no
 * {@code MongoCustomConversions} / {@code @ReadingConverter} /
 * {@code @WritingConverter} anywhere, so Spring Data's default enum handling
 * applies and the BSON value was <em>already</em> the enum's {@code name()}
 * string. Every document written before this deploy therefore reads back
 * verbatim — no migration script, no dual-read, no defaulting.
 *
 * <p><b>This test uses a real {@link MappingMongoConverter}</b>, the same class
 * {@code MongoTemplate} delegates document mapping to, built over a real
 * {@link MongoMappingContext}. It is deliberately not a mocked repository: a
 * mock proves nothing about what Mongo stores, and this feature already shipped
 * one defect (plan Amendment A2) that survived a full test suite because the
 * suite asserted against a fake. No Mongo server is needed — the converter is
 * pure document mapping.
 *
 * <p><b>Provenance of the literals below.</b> They were captured by running the
 * same converter against the <em>pre-change</em> tree (branch
 * {@code feature/plugin-hot-reload} at {@code c3fac8a}, where both fields were
 * still enum-typed). The full document that build emitted was:
 *
 * <pre>
 * {"_id": "probe-1", "name": "probe", ... ,
 *  "strategyMix": [{"strategyId": "RANDOM", "weight": 1.0},
 *                  {"strategyId": "MARTINGALE_CLASSIC_CAUTIOUS", "weight": 2.5}],
 *  "slotStrategyId": "FIXED",
 *  "_class": "com.vingame.bot.domain.botgroup.model.BotGroup"}
 * </pre>
 *
 * The whole document is not asserted verbatim — that would break on any
 * unrelated {@code BotGroup} field — but the two strategy-bearing fragments are,
 * exactly as they came out of the enum-typed build.
 */
@DisplayName("Persisted strategy keys survive the enum → String retype (AD-14)")
class PersistedStrategyKeyCompatTest {

    private static MappingMongoConverter converter;

    /**
     * The pre-migration BSON, as written by the enum-typed build. Parsed from a
     * literal rather than produced by the code under test, so the read
     * assertions are genuinely reading a foreign document.
     */
    private static final String PRE_MIGRATION_BSON = """
            {
              "_id": "grp-legacy-1",
              "name": "legacy group",
              "strategyMix": [
                { "strategyId": "RANDOM", "weight": 1.0 },
                { "strategyId": "MARTINGALE_CLASSIC_CAUTIOUS", "weight": 2.5 }
              ],
              "slotStrategyId": "FIXED",
              "_class": "com.vingame.bot.domain.botgroup.model.BotGroup"
            }
            """;

    @BeforeAll
    static void buildRealConverter() {
        // Mirrors MongoDataAutoConfiguration's wiring with the application's own
        // (empty) custom-conversion set — the point of the test is that the set
        // is empty, so building it from List.of() is the honest reproduction.
        MongoCustomConversions conversions = new MongoCustomConversions(List.of());
        MongoMappingContext context = new MongoMappingContext();
        context.setSimpleTypeHolder(conversions.getSimpleTypeHolder());
        context.afterPropertiesSet();
        converter = new MappingMongoConverter(NoOpDbRefResolver.INSTANCE, context);
        converter.setCustomConversions(conversions);
        converter.afterPropertiesSet();
    }

    @Nested
    @DisplayName("reading a pre-migration document")
    class Read {

        @Test
        @DisplayName("a document written by the enum-typed build reads back verbatim")
        void legacyDocumentReadsBackVerbatim() {
            BotGroup group = converter.read(BotGroup.class, Document.parse(PRE_MIGRATION_BSON));

            assertThat(group.getStrategyMix()).containsExactly(
                    new WeightedStrategy("RANDOM", 1.0),
                    new WeightedStrategy("MARTINGALE_CLASSIC_CAUTIOUS", 2.5));
            assertThat(group.getSlotStrategyId()).isEqualTo("FIXED");
        }

        @Test
        @DisplayName("the keys read back are exactly the enum constant names — nothing is normalised")
        void keysAreTheEnumConstantNames() {
            BotGroup group = converter.read(BotGroup.class, Document.parse(PRE_MIGRATION_BSON));

            assertThat(group.getStrategyMix())
                    .extracting(WeightedStrategy::strategyId)
                    .containsExactly(StrategyId.RANDOM.name(),
                            StrategyId.MARTINGALE_CLASSIC_CAUTIOUS.name());
            assertThat(group.getSlotStrategyId()).isEqualTo(SlotStrategyId.FIXED.name());
        }

        @Test
        @DisplayName("a document with no slotStrategyId still reads null — null is meaningful, not defaulted")
        void absentSlotStrategyIdStaysNull() {
            // BotGroup.slotStrategyId == null means "fall back to FIXED at
            // bot-build time". The retype must not turn that into "" or "FIXED".
            Document doc = Document.parse("{ \"_id\": \"grp-2\", \"name\": \"n\" }");

            BotGroup group = converter.read(BotGroup.class, doc);

            assertThat(group.getSlotStrategyId()).isNull();
            assertThat(group.getStrategyMix()).isNull();
        }
    }

    @Nested
    @DisplayName("writing back")
    class Write {

        @Test
        @DisplayName("the String-typed entity writes the same BSON the enum-typed one did")
        void writesTheSameBson() {
            BotGroup group = BotGroup.builder()
                    .id("grp-legacy-1")
                    .name("legacy group")
                    .strategyMix(List.of(
                            new WeightedStrategy("RANDOM", 1.0),
                            new WeightedStrategy("MARTINGALE_CLASSIC_CAUTIOUS", 2.5)))
                    .slotStrategyId("FIXED")
                    .build();

            Document out = new Document();
            converter.write(group, out);

            // Bare strings, exactly as the enum-typed build emitted them — not a
            // sub-document, not an ordinal, not a $-prefixed wrapper.
            assertThat(out.get("slotStrategyId")).isInstanceOf(String.class).isEqualTo("FIXED");
            assertThat(out.get("strategyMix")).isEqualTo(List.of(
                    new Document("strategyId", "RANDOM").append("weight", 1.0),
                    new Document("strategyId", "MARTINGALE_CLASSIC_CAUTIOUS").append("weight", 2.5)));
        }

        @Test
        @DisplayName("strategyMix elements carry no _class hint — the shape is unchanged")
        void mixElementsCarryNoTypeHint() {
            // The pre-change probe emitted the elements as bare sub-documents.
            // A _class hint appearing here would be a silent document-shape
            // change that only bites on the next read by an older build.
            BotGroup group = BotGroup.builder().id("g").name("g")
                    .strategyMix(List.of(new WeightedStrategy("RANDOM", 1.0)))
                    .build();

            Document out = new Document();
            converter.write(group, out);

            List<?> mix = (List<?>) out.get("strategyMix");
            assertThat(mix).hasSize(1);
            assertThat(((Document) mix.get(0)).keySet()).containsExactly("strategyId", "weight");
        }

        @Test
        @DisplayName("a null slotStrategyId is not written as an empty string")
        void nullSlotStrategyIdIsNotWrittenAsBlank() {
            BotGroup group = BotGroup.builder().id("g").name("g").build();

            Document out = new Document();
            converter.write(group, out);

            assertThat(out.get("slotStrategyId")).isNull();
        }
    }

    @Nested
    @DisplayName("the general fact AD-14 rests on")
    class EnumAndStringAreTheSameBson {

        /**
         * AD-14's claim is not about {@code BotGroup} specifically — it is that,
         * with no custom conversions registered, Spring Data writes an enum as
         * its {@code name()} and a {@code String} as itself, so the two field
         * types are indistinguishable in BSON. Demonstrate it directly rather
         * than asserting it, using the real {@link StrategyId} enum on one side.
         */
        @Test
        @DisplayName("an enum-typed field and a String-typed field write identical BSON")
        void enumFieldAndStringFieldAreIndistinguishable() {
            Document fromEnum = new Document();
            converter.write(new EnumTypedHolder(StrategyId.MARTINGALE_CLASSIC_CAUTIOUS), fromEnum);
            Document fromString = new Document();
            converter.write(new StringTypedHolder("MARTINGALE_CLASSIC_CAUTIOUS"), fromString);

            assertThat(fromEnum.get("key")).isEqualTo(fromString.get("key"));
            assertThat(fromEnum.get("key")).isEqualTo("MARTINGALE_CLASSIC_CAUTIOUS");
        }

        @Test
        @DisplayName("and both read that BSON back into their own type")
        void bothTypesReadTheSameBsonBack() {
            Document doc = Document.parse("{ \"key\": \"MARTINGALE_CLASSIC_CAUTIOUS\" }");

            assertThat(converter.read(EnumTypedHolder.class, doc).key())
                    .isEqualTo(StrategyId.MARTINGALE_CLASSIC_CAUTIOUS);
            assertThat(converter.read(StringTypedHolder.class, doc).key())
                    .isEqualTo("MARTINGALE_CLASSIC_CAUTIOUS");
        }

        record EnumTypedHolder(StrategyId key) {
        }

        record StringTypedHolder(String key) {
        }
    }

    @Nested
    @DisplayName("the JSON wire shape is unchanged in both directions")
    class JsonWire {

        private final ObjectMapper json = new ObjectMapper();

        @Test
        @DisplayName("the enum token and the String token are the same JSON")
        void enumAndStringSerialiseIdentically() throws Exception {
            // The pre-change types serialised through Jackson's default enum
            // handling, which emits name(). Prove the equivalence in the test so
            // it does not have to be taken on trust.
            assertThat(json.writeValueAsString(StrategyId.RANDOM))
                    .isEqualTo(json.writeValueAsString("RANDOM"))
                    .isEqualTo("\"RANDOM\"");
            assertThat(json.writeValueAsString(SlotStrategyId.FIXED))
                    .isEqualTo(json.writeValueAsString("FIXED"))
                    .isEqualTo("\"FIXED\"");
        }

        @Test
        @DisplayName("BotGroupDTO serialises slotStrategyId and strategyMix as bare names")
        void botGroupDtoJsonUnchanged() throws Exception {
            BotGroupDTO dto = BotGroupDTO.builder()
                    .slotStrategyId("FIXED")
                    .strategyMix(List.of(new WeightedStrategy("RANDOM", 1.0)))
                    .build();

            String body = json.writeValueAsString(dto);

            assertThat(body).contains("\"slotStrategyId\":\"FIXED\"");
            assertThat(body).contains("\"strategyMix\":[{\"strategyId\":\"RANDOM\",\"weight\":1.0}]");
        }

        @Test
        @DisplayName("BotHealthDTO serialises strategyId as a bare name, and null as null")
        void botHealthDtoJsonUnchanged() throws Exception {
            assertThat(json.writeValueAsString(BotHealthDTO.builder().strategyId("RANDOM").build()))
                    .contains("\"strategyId\":\"RANDOM\"");
            assertThat(json.writeValueAsString(BotHealthDTO.builder().build()))
                    .contains("\"strategyId\":null");
        }

        @Test
        @DisplayName("a request body written for the enum-typed API still deserialises")
        void legacyRequestBodyStillDeserialises() throws Exception {
            String body = "{\"slotStrategyId\":\"RANDOM\","
                    + "\"strategyMix\":[{\"strategyId\":\"PAROLI_AGGRESSIVE\",\"weight\":2.0}]}";

            BotGroupDTO dto = json.readValue(body, BotGroupDTO.class);

            assertThat(dto.getSlotStrategyId()).isEqualTo("RANDOM");
            assertThat(dto.getStrategyMix())
                    .containsExactly(new WeightedStrategy("PAROLI_AGGRESSIVE", 2.0));
        }
    }
}
