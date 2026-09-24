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
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;

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
 * rolling back to {@code vingame-bot:rollback-*} has to stay a safe action and
 * {@code findByTargetStatus(ACTIVE)} is on the application-ready boot path — one poisoned
 * document does not degrade one group, it fails the whole boot query.
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
    private static BotGroupMapper mapper;

    @BeforeAll
    static void buildRealConverter() {
        // The application registers no MongoCustomConversions anywhere, so building from an
        // empty list is the honest reproduction of its wiring.
        MongoCustomConversions conversions = new MongoCustomConversions(List.of());
        MongoMappingContext context = new MongoMappingContext();
        context.setSimpleTypeHolder(conversions.getSimpleTypeHolder());
        context.afterPropertiesSet();
        converter = new MappingMongoConverter(NoOpDbRefResolver.INSTANCE, context);
        converter.setCustomConversions(conversions);
        converter.afterPropertiesSet();
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
         * no such constant. The failure is thrown while mapping <em>one</em> document, which on
         * the {@code findByTargetStatus(ACTIVE)} boot path means the whole query — and therefore
         * the whole startup — fails.
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
         * <b>This is a defect report, not a specification.</b>
         * <p>
         * A1's consumer audit asserts that "{@code BotGroupDTO.targetStatus} can still only
         * carry the original three". That is true of what the API <em>renders</em> and false of
         * what it <em>accepts</em>: {@code targetStatus} is a writable field on
         * {@link BotGroupDTO}, and both request-body paths copy it into the entity that is then
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
         * So a single request body is enough to put an unrollbackable value in Mongo, and it was
         * <em>not</em> enough before this phase, when every constant the DTO could carry was one
         * an older jar could read. The assertions below are the invariant A1 states; they fail
         * today, and the fix belongs in production code (reject the three at the DTO boundary,
         * or stop accepting {@code targetStatus} on the write side at all) rather than here.
         */
        @Test
        @DisplayName("neither create nor patch may persist an appended constant (currently they do)")
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
                            + "jar throws ConversionFailedException on it and "
                            + "findByTargetStatus(ACTIVE) is on the boot path. These request-body "
                            + "paths reach Mongo with no guard in between, and the source guard "
                            + "cannot see either of them (one is a builder, one passes a "
                            + "variable).")
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

    private static Document write(BotGroup group) {
        Document doc = new Document();
        converter.write(group, doc);
        return doc;
    }
}
