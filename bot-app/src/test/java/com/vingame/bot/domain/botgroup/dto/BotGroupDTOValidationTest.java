package com.vingame.bot.domain.botgroup.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit coverage for the universal required-field Bean Validation layer on
 * {@link BotGroupDTO} (Phase 4 / AD-10). Verifies that the {@code @NotBlank} /
 * {@code @Positive} constraints assigned to the {@link OnCreate} group fire on
 * the create path, that the behavior mins (minBet/minBetsPerRound) are NOT
 * subjected to any positivity constraint, and that the constraints do not fire
 * under the default group (the PATCH path uses no group → no required-field
 * enforcement).
 *
 * <p>The end-to-end 400-response shape (controller + {@code RestExceptionHandler})
 * is asserted by the MockMvc integration tests in Phase 5.
 */
@DisplayName("BotGroupDTO universal required-field validation")
class BotGroupDTOValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    /**
     * A fully-populated, create-valid DTO. Behavior mins are deliberately set to
     * 0 to prove they are not rejected by the universal layer (their rules live
     * in the game-type validators).
     */
    private static BotGroupDTO validCreateDTO() {
        return BotGroupDTO.builder()
                .name("vtest")
                .environmentId("env-1")
                .gameId("game-1")
                .namePrefix("vt")
                .password("secret")
                .botCount(1)
                .minBet(0L)
                .maxBet(500L)
                .betIncrement(10L)
                .minBetsPerRound(0)
                .maxBetsPerRound(5)
                .maxTotalBetPerRound(1000L)
                .build();
    }

    private Set<ConstraintViolation<BotGroupDTO>> validateCreate(BotGroupDTO dto) {
        return validator.validate(dto, OnCreate.class);
    }

    @Test
    @DisplayName("fully-populated DTO passes the OnCreate group")
    void fullyPopulatedPasses() {
        assertThat(validateCreate(validCreateDTO())).isEmpty();
    }

    @Nested
    @DisplayName("each universal required field triggers under OnCreate")
    class RequiredFields {

        @Test
        @DisplayName("blank environmentId rejected")
        void blankEnvironmentId() {
            BotGroupDTO dto = validCreateDTO();
            dto.setEnvironmentId("  ");
            assertThat(violationMessages(dto)).contains("environmentId must not be blank");
        }

        @Test
        @DisplayName("null gameId rejected")
        void nullGameId() {
            BotGroupDTO dto = validCreateDTO();
            dto.setGameId(null);
            assertThat(violationMessages(dto)).contains("gameId must not be blank");
        }

        @Test
        @DisplayName("blank namePrefix rejected")
        void blankNamePrefix() {
            BotGroupDTO dto = validCreateDTO();
            dto.setNamePrefix("");
            assertThat(violationMessages(dto)).contains("namePrefix must not be blank");
        }

        @Test
        @DisplayName("null password rejected")
        void nullPassword() {
            BotGroupDTO dto = validCreateDTO();
            dto.setPassword(null);
            assertThat(violationMessages(dto)).contains("password must not be blank");
        }

        @Test
        @DisplayName("botCount 0 rejected (@Positive)")
        void botCountZero() {
            BotGroupDTO dto = validCreateDTO();
            dto.setBotCount(0);
            assertThat(violationMessages(dto)).contains("botCount must be >= 1");
        }

        @Test
        @DisplayName("negative botCount rejected (@Positive)")
        void botCountNegative() {
            BotGroupDTO dto = validCreateDTO();
            dto.setBotCount(-5);
            assertThat(violationMessages(dto)).contains("botCount must be >= 1");
        }

        @Test
        @DisplayName("null botCount rejected (@Positive treats null as violation? no — only blanks)")
        void botCountNull() {
            // @Positive considers null valid; the "required" signal for botCount is
            // its presence, but per AD-10 only @Positive is specified. Null botCount
            // therefore passes @Positive (mapper defaults it). This documents that.
            BotGroupDTO dto = validCreateDTO();
            dto.setBotCount(null);
            assertThat(violationMessages(dto)).isEmpty();
        }
    }

    @Nested
    @DisplayName("behavior mins are NOT subjected to positivity (AD-3 / AD-10)")
    class BehaviorMinsUnconstrained {

        @Test
        @DisplayName("minBet=0 and minBetsPerRound=0 do not produce universal violations")
        void zeroBehaviorMinsPass() {
            BotGroupDTO dto = validCreateDTO();
            dto.setMinBet(0L);
            dto.setMinBetsPerRound(0);
            assertThat(validateCreate(dto)).isEmpty();
        }

        @Test
        @DisplayName("even nonsensical behavior fields produce no universal violations")
        void garbageBehaviorFieldsIgnoredByUniversalLayer() {
            BotGroupDTO dto = validCreateDTO();
            dto.setMinBet(999L);
            dto.setMaxBet(1L);
            dto.setBetIncrement(0L);
            // The universal layer says nothing about these; the game-type
            // validator owns them.
            assertThat(validateCreate(dto)).isEmpty();
        }
    }

    @Test
    @DisplayName("default group (PATCH path) enforces no required fields")
    void defaultGroupNoEnforcement() {
        // A partial DTO with everything blank/null must pass the default group —
        // PATCH is intentionally partial and is not annotated with @Validated.
        BotGroupDTO partial = BotGroupDTO.builder()
                .maxBet(50L)
                .build();
        assertThat(validator.validate(partial)).isEmpty();
    }

    @Test
    @DisplayName("multiple missing fields all reported in one pass")
    void multipleViolationsAggregated() {
        BotGroupDTO dto = BotGroupDTO.builder().build();
        assertThat(violationMessages(dto))
                .contains("environmentId must not be blank",
                        "gameId must not be blank",
                        "namePrefix must not be blank",
                        "password must not be blank");
        // botCount null does not violate @Positive (see botCountNull above).
    }

    private Set<String> violationMessages(BotGroupDTO dto) {
        return validateCreate(dto).stream()
                .map(ConstraintViolation::getMessage)
                .collect(java.util.stream.Collectors.toSet());
    }
}
