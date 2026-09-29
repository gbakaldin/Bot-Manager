package com.vingame.bot.domain.botgroup.model;

import com.vingame.bot.domain.botgroup.dto.BotGroupDTO;
import com.vingame.bot.domain.botgroup.mapper.BotGroupMapper;
import org.bson.Document;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.core.convert.NoOpDbRefResolver;
import org.springframework.data.mongodb.core.convert.QueryMapper;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What actually happens in BSON when {@link BotGroupStatus} grows constants an older jar does
 * not know (GATEWAY_REQUEST_BUDGET Amendment A1).
 * <p>
 * A1 states the rule as an absolute: {@code STARTING}, {@code REGISTRATION_PENDING} and
 * {@code REGISTRATION_FAILED} may <b>never</b> reach {@code BotGroup.targetStatus}, because
 * rolling back to {@code vingame-bot:rollback-*} has to stay a safe action.
 * <p>
 * <b>Its reason was wrong, and this file used to carry the wrong reason in four places</b>
 * (A14, re-review RR6). The boot query does <em>not</em> fail:
 * {@code findByTargetStatus(ACTIVE)} filters server-side on the string {@code "ACTIVE"}, so a
 * poisoned document is never returned and never converted — {@code BlastRadius} below measures
 * exactly that, through a real {@code QueryMapper}. What breaks is every read that <em>does</em>
 * convert the group ({@code GET /{id}}, and worse {@code POST /{envId}/filter}, the UI's list
 * view for a whole environment), and on the current jar the document silently drops out of the
 * ACTIVE set and out of {@code RecoveryEligibility} — unmanaged, with nothing logged. The
 * failure presents as a <b>400</b>, not a 500: Spring's conversion failure is an
 * {@code IllegalArgumentException} and {@code RestExceptionHandler.handleIllegalArgument} maps
 * it to {@code 400 Bad request}, which is worse for whoever reads it, because the request was
 * not bad.
 * <p>
 * {@code BotGroupStatusPersistenceGuardTest} enforces that against the source, by scanning for
 * literal arguments to {@code setTargetStatus(}. This class is the other half, and it uses a
 * <b>real {@link MappingMongoConverter}</b> — the class {@code MongoTemplate} delegates
 * document mapping to — so the claims about BSON are measured rather than assumed. No Mongo
 * server is involved; the converter is pure document mapping, which is the seam
 * {@code PersistedStrategyKeyCompatTest} already established for exactly this question.
 */
@DisplayName("BotGroupStatus: what the appended constants do to a rollback")
class BotGroupStatusRollbackSafetyTest {

    private static MappingMongoConverter converter;
    private static MongoMappingContext context;
    private static QueryMapper queryMapper;
    private static BotGroupMapper mapper;

    @BeforeAll
    static void buildRealConverter() {
        // The application registers no MongoCustomConversions anywhere, so building from an
        // empty list is the honest reproduction of its wiring.
        MongoCustomConversions conversions = new MongoCustomConversions(List.of());
        context = new MongoMappingContext();
        context.setSimpleTypeHolder(conversions.getSimpleTypeHolder());
        context.afterPropertiesSet();
        converter = new MappingMongoConverter(NoOpDbRefResolver.INSTANCE, context);
        converter.setCustomConversions(conversions);
        converter.afterPropertiesSet();
        queryMapper = new QueryMapper(converter);
        mapper = Mappers.getMapper(BotGroupMapper.class);
    }

    @Nested
    @DisplayName("the mechanism")
    class Mechanism {

        @Test
        @DisplayName("targetStatus is stored as the bare constant name, with no conversion in the way")
        void targetStatusIsStoredAsItsName() {
            Document doc = write(BotGroup.builder().id("g-1").name("n")
                    .targetStatus(BotGroupStatus.ACTIVE).build());

            assertThat(doc.get("targetStatus"))
                    .as("a String, not a document and not an ordinal — which is why an unknown "
                            + "name is unreadable rather than merely wrong")
                    .isEqualTo("ACTIVE");
        }

        /**
         * Stands in for "{@code STARTING} read by a pre-Phase-2 jar": the reading jar's enum has
         * no such constant. The failure is thrown while mapping <em>one</em> document — which on
         * any query that <em>returns</em> that document (the environment list view, {@code GET
         * /{id}}) takes the whole response down with it. Not the {@code findByTargetStatus(ACTIVE)}
         * boot path, which filters server-side on the string and never converts it; see the class
         * javadoc and {@code BlastRadius}.
         * <p>
         * <b>The type is not the one A1 names.</b> Both the amendment and
         * {@link BotGroupStatus}' own javadoc say {@code ConversionFailedException}; the
         * converter actually lets {@link Enum#valueOf}'s {@link IllegalArgumentException} out of
         * {@code getPotentiallyConvertedSimpleRead}. The consequence A1 reasons from is
         * unaffected — an unknown constant name is a hard, non-null, non-recoverable read
         * failure — but the exception name in the docs is wrong, and asserting the real one is
         * what keeps this test from passing for the wrong reason.
         */
        @Test
        @DisplayName("a constant the reading jar does not know is a hard read failure, not a null")
        void anUnknownConstantNameFailsTheRead() {
            Document poisoned = Document.parse(
                    "{\"_id\": \"g-1\", \"name\": \"n\", \"targetStatus\": \"A_STATUS_THIS_JAR_DOES_NOT_HAVE\"}");

            assertThatThrownBy(() -> converter.read(BotGroup.class, poisoned))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("A_STATUS_THIS_JAR_DOES_NOT_HAVE");
        }

        @Test
        @DisplayName("the three original constants still read back verbatim — nothing was reordered")
        void theOriginalThreeStillReadBack() {
            for (BotGroupStatus status : List.of(
                    BotGroupStatus.ACTIVE, BotGroupStatus.STOPPED, BotGroupStatus.DEAD)) {
                Document doc = Document.parse(
                        "{\"_id\": \"g-1\", \"name\": \"n\", \"targetStatus\": \"" + status.name() + "\"}");
                assertThat(converter.read(BotGroup.class, doc).getTargetStatus()).isEqualTo(status);
            }
        }

        @Test
        @DisplayName("an absent targetStatus is still null — the state a freshly created group has")
        void absentTargetStatusStaysNull() {
            Document doc = Document.parse("{\"_id\": \"g-1\", \"name\": \"n\"}");

            assertThat(converter.read(BotGroup.class, doc).getTargetStatus()).isNull();
        }
    }

    @Nested
    @DisplayName("the write paths the source guard cannot see")
    class WritePaths {

        /**
         * <b>This was a defect report; the defect is fixed and these are now its regression
         * guards.</b> They fail if either write path is ever reopened (RR6).
         * <p>
         * A1's consumer audit asserted that "{@code BotGroupDTO.targetStatus} can still only
         * carry the original three". That was true of what the API <em>rendered</em> and false of
         * what it <em>accepted</em>: {@code targetStatus} was a writable field on
         * {@link BotGroupDTO}, and both request-body paths copied it into the entity that is then
         * handed to {@code repository.save} —
         * <ul>
         *   <li>{@code POST /api/v1/bot-group/} → {@code BotGroupMapper.toEntity}, which sets it
         *       through the Lombok <em>builder</em> ({@code .targetStatus(dto.getTargetStatus())});</li>
         *   <li>{@code PATCH /api/v1/bot-group/{id}} → {@code BotGroupMapper.updateEntityFromDTO},
         *       which sets it through {@code setTargetStatus(Optional.ofNullable(...).orElse(...))}.</li>
         * </ul>
         * Neither is visible to {@code BotGroupStatusPersistenceGuardTest}: the first is not a
         * {@code setTargetStatus(} call at all, and the second's argument is a variable. Nothing
         * downstream sanitises it — {@code BotGroupService.save} and
         * {@code BotGroupConfigValidationService.validate} never look at {@code targetStatus}.
         * <p>
         * So a single request body was enough to put an unrollbackable value in Mongo, and it was
         * <em>not</em> enough before this phase, when every constant the DTO could carry was one
         * an older jar could read. The fix went where it belonged — in production code, on the
         * write side: the DTO field is {@code @JsonProperty(access = READ_ONLY)} so it cannot
         * arrive over HTTP at all, and {@code BotGroupMapper} copies it in neither write
         * direction so an in-process caller cannot get there another way. Validation was
         * deliberately NOT the fix: a 400 on a body that merely echoes a group back would have
         * broken A3's read-modify-write shape.
         */
        @Test
        @DisplayName("neither create nor patch may persist an appended constant")
        void requestBodiesCannotPoisonTargetStatus() {
            List<String> reachable = new ArrayList<>();

            for (BotGroupStatus forbidden : List.of(BotGroupStatus.STARTING,
                    BotGroupStatus.REGISTRATION_PENDING, BotGroupStatus.REGISTRATION_FAILED)) {

                // POST / — the create path, through the builder.
                BotGroupDTO created = new BotGroupDTO();
                created.setName("n");
                created.setTargetStatus(forbidden);
                BotGroup entity = mapper.toEntity(created);
                if (forbidden.name().equals(write(entity).get("targetStatus"))) {
                    reachable.add("POST /api/v1/bot-group/ → BotGroupMapper.toEntity → \""
                            + forbidden.name() + "\"");
                }

                // PATCH /{id} — the merge path, through the setter with a variable argument.
                BotGroup existing = BotGroup.builder().id("g-1").name("n")
                        .targetStatus(BotGroupStatus.ACTIVE).build();
                BotGroupDTO patch = new BotGroupDTO();
                patch.setTargetStatus(forbidden);
                mapper.updateEntityFromDTO(patch, existing);
                if (forbidden.name().equals(write(existing).get("targetStatus"))) {
                    reachable.add("PATCH /api/v1/bot-group/{id} → updateEntityFromDTO → \""
                            + forbidden.name() + "\"");
                }
            }

            assertThat(reachable)
                    .as("A1: nothing new is ever persisted into targetStatus, because an older "
                            + "jar cannot deserialise it (IllegalArgumentException out of "
                            + "Enum.valueOf — see Mechanism, and see BlastRadius for which reads "
                            + "that actually breaks). Closed at the boundary: the DTO field is "
                            + "READ_ONLY and the mapper copies it in neither write direction. "
                            + "Reinstating either is how a request body poisons a document again, "
                            + "and the source guard cannot see either shape (one is a builder, "
                            + "one passes a variable).")
                    .isEmpty();
        }

        @Test
        @DisplayName("a PATCH that does not mention targetStatus leaves it alone")
        void aPatchWithoutTargetStatusIsInert() {
            BotGroup existing = BotGroup.builder().id("g-1").name("n")
                    .targetStatus(BotGroupStatus.ACTIVE).build();
            BotGroupDTO patch = new BotGroupDTO();
            patch.setName("renamed");

            mapper.updateEntityFromDTO(patch, existing);

            assertThat(existing.getTargetStatus()).isEqualTo(BotGroupStatus.ACTIVE);
        }
    }


    /**
     * <b>What a poisoned document actually costs — QA's original verdict got this wrong.</b>
     * <p>
     * Phase 2's QA repeated A1's claim that one poisoned document "fails the whole
     * {@code findByTargetStatus(ACTIVE)} boot query". Dev's fix round contradicted it, and Dev is
     * right. The two tests below are the measurement that settles it, so the next reader weighs
     * the hazard against the real model rather than re-deriving it from the plan's prose:
     * <ul>
     *   <li>the boot query's criterion is rendered into BSON as the plain string
     *       {@code {"targetStatus": "ACTIVE"}}, so the <em>server</em> does the matching and a
     *       document holding {@code "STARTING"} is never returned and therefore never
     *       converted. It does not fail; the group silently drops out of it — and out of
     *       {@code RecoveryEligibility} — so it is never auto-started and never auto-recovered
     *       again, with nothing logged;</li>
     *   <li>the reads that <em>do</em> convert the group are the ones that break —
     *       {@code GET /{id}} and {@code POST /{envId}/filter}, where the failure is a 500 for a
     *       whole environment's list view rather than for one group.</li>
     * </ul>
     * A third consequence falls out of the same mechanism and is <em>wider</em> than either
     * account: a query that filters on some <em>other</em> field still converts every document it
     * returns, so a poisoned group that is also {@code activationMode == SCHEDULED} is handed to
     * {@code ActivationScheduler}'s {@code findByActivationMode(SCHEDULED)} and fails that tick
     * — every minute, for every scheduled group in the fleet, not just the poisoned one.
     * <p>
     * All of this is reachable only by writing such a document, which is what the
     * {@code READ_ONLY} DTO field and the mapper's two omissions now prevent. These tests exist
     * so the severity argument stays honest, not because the path is open.
     */
    @Nested
    @DisplayName("the blast radius of a poisoned document")
    class BlastRadius {

        @Test
        @DisplayName("findByTargetStatus filters server-side on the string, so it never converts a poisoned group")
        void theBootQueryFiltersOnTheStringAndNeverConvertsAPoisonedGroup() {
            Document criteria = queryMapper.getMappedObject(
                    Query.query(Criteria.where("targetStatus").is(BotGroupStatus.ACTIVE))
                            .getQueryObject(),
                    context.getRequiredPersistentEntity(BotGroup.class));

            assertThat(criteria)
                    .as("the derived query findByTargetStatus(ACTIVE) goes to the server as a "
                            + "string equality on targetStatus. Mongo returns only matching "
                            + "documents, and conversion happens on what is returned — so a "
                            + "document holding STARTING is never read back by this query and "
                            + "cannot make it throw. A1 and QA's Phase 2 verdict both said it "
                            + "would; they were wrong, and the real cost is the group leaving "
                            + "the query silently.")
                    .isEqualTo(new Document("targetStatus", "ACTIVE"));
        }

        @Test
        @DisplayName("a query on another field still converts what it returns — the activation tick's exposure")
        void aQueryOnAnotherFieldStillConvertsThePoisonedGroup() {
            Document criteria = queryMapper.getMappedObject(
                    Query.query(Criteria.where("activationMode").is(ActivationMode.SCHEDULED))
                            .getQueryObject(),
                    context.getRequiredPersistentEntity(BotGroup.class));

            assertThat(criteria)
                    .as("ActivationScheduler's findByActivationMode(SCHEDULED) constrains "
                            + "activationMode and nothing else, so a poisoned group that is also "
                            + "SCHEDULED IS returned — and then converted")
                    .isEqualTo(new Document("activationMode", "SCHEDULED"));

            Document poisonedAndScheduled = Document.parse("{\"_id\": \"g-1\", \"name\": \"n\", "
                    + "\"activationMode\": \"SCHEDULED\", "
                    + "\"targetStatus\": \"A_STATUS_THIS_JAR_DOES_NOT_HAVE\"}");

            assertThatThrownBy(() -> converter.read(BotGroup.class, poisonedAndScheduled))
                    .as("which fails the reconciler's whole tick, every minute, for every "
                            + "scheduled group in the fleet — wider than either the plan's or "
                            + "the fix round's account of the blast radius")
                    .isInstanceOf(IllegalArgumentException.class);
        }

        /**
         * Models the list view rather than proving it: the repository converts document by
         * document and the loop here is that iteration. What it pins is the part that matters —
         * the converter has no per-document tolerance, so a healthy group <em>after</em> the
         * poisoned one is never produced, which is why {@code POST /{envId}/filter} answers 500
         * for the whole environment instead of omitting one row.
         */
        @Test
        @DisplayName("one poisoned document takes the healthy groups beside it")
        void onePoisonedDocumentTakesThePageWithIt() {
            List<Document> page = List.of(
                    Document.parse("{\"_id\": \"g-1\", \"name\": \"a\", \"targetStatus\": \"ACTIVE\"}"),
                    Document.parse("{\"_id\": \"g-2\", \"name\": \"b\", "
                            + "\"targetStatus\": \"A_STATUS_THIS_JAR_DOES_NOT_HAVE\"}"),
                    Document.parse("{\"_id\": \"g-3\", \"name\": \"c\", \"targetStatus\": \"STOPPED\"}"));
            List<BotGroup> read = new ArrayList<>();

            assertThatThrownBy(() -> {
                for (Document doc : page) {
                    read.add(converter.read(BotGroup.class, doc));
                }
            }).isInstanceOf(IllegalArgumentException.class);

            assertThat(read)
                    .as("g-3 is healthy and is never produced: the page is lost, not filtered")
                    .hasSize(1);
        }
    }

    private static Document write(BotGroup group) {
        Document doc = new Document();
        converter.write(group, doc);
        return doc;
    }
}
