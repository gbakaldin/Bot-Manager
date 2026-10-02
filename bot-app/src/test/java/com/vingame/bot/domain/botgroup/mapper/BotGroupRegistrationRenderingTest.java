package com.vingame.bot.domain.botgroup.mapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vingame.bot.domain.botgroup.dto.BotGroupDTO;
import com.vingame.bot.domain.botgroup.model.BotGroup;
import com.vingame.bot.domain.botgroup.model.BotGroupStatus;
import com.vingame.bot.domain.botgroup.model.RegistrationState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The registration state is <b>derived at the DTO boundary and persisted nowhere</b>
 * (GATEWAY_REQUEST_BUDGET A1 / A28.2).
 *
 * <p>Two separate invariants, and they fail in opposite directions:
 * <ul>
 *   <li><b>Outbound, the two registration constants must appear</b> — they are the whole of
 *       what a client polls after {@code POST /} answers {@code 200}, and A3 promises them
 *       explicitly;</li>
 *   <li><b>inbound, nothing about registration may be accepted</b>. {@code registeredCount} is
 *       the high-water mark {@code RegistrationWorker} resumes from, so a request body that
 *       could set it makes the worker skip a block of accounts that were never created — after
 *       which every bot built on them fails to authenticate, which presents as an auth outage
 *       rather than as a create that lied.</li>
 * </ul>
 * The source-scan half of the guard lives in {@code BotGroupStatusPersistenceGuardTest}; this is
 * the value-level half, and it is asserted through a real {@link ObjectMapper} rather than by
 * reading annotations, because an annotation is worth only what Jackson does with it.
 */
@DisplayName("Registration state: rendered outbound, never accepted inbound")
class BotGroupRegistrationRenderingTest {

    private final BotGroupMapper mapper = Mappers.getMapper(BotGroupMapper.class);
    private final ObjectMapper json = new ObjectMapper();

    private static BotGroup group(String state) {
        return BotGroup.builder()
                .id("g-1").name("G").botCount(500)
                .registeredCount(120).namedCount(119)
                .registrationState(state)
                .targetStatus(BotGroupStatus.STOPPED)
                .build();
    }

    @Test
    @DisplayName("PENDING renders REGISTRATION_PENDING and wins over the persisted targetStatus")
    void pendingRendersTheDerivedStatus() {
        BotGroupDTO dto = mapper.toDTO(group(RegistrationState.PENDING));

        assertThat(dto.getTargetStatus()).isEqualTo(BotGroupStatus.REGISTRATION_PENDING);
        assertThat(dto.getRegisteredCount()).isEqualTo(120);
        assertThat(dto.getNamedCount()).isEqualTo(119);
    }

    @Test
    @DisplayName("FAILED renders REGISTRATION_FAILED")
    void failedRendersTheDerivedStatus() {
        assertThat(mapper.toDTO(group(RegistrationState.FAILED)).getTargetStatus())
                .isEqualTo(BotGroupStatus.REGISTRATION_FAILED);
    }

    @Test
    @DisplayName("a completed group falls back to the persisted targetStatus")
    void completedFallsBackToTargetStatus() {
        // The worker clears registrationState on completion, and from that moment the group is
        // byte-for-byte a group that was registered synchronously: the only new values a UI ever
        // sees are the two registration ones, and only while registration is in flight or failed.
        assertThat(mapper.toDTO(group(null)).getTargetStatus()).isEqualTo(BotGroupStatus.STOPPED);
    }

    @Test
    @DisplayName("a legacy group renders no counts at all — absent means 'never registered here'")
    void legacyGroupRendersNoCounts() {
        BotGroup legacy = BotGroup.builder().id("g-1").name("Old").botCount(10).build();

        BotGroupDTO dto = mapper.toDTO(legacy);

        // "Absent" has to keep meaning "this group was never asynchronously registered" rather
        // than "zero accounts exist" — those are opposite situations (a fully populated group
        // from before the feature, versus a create that has not started yet), and a UI that
        // rendered 0/10 for the first would be telling an operator their live group is empty.
        assertThat(dto.getRegisteredCount()).isNull();
        assertThat(dto.getNamedCount()).isNull();
        assertThat(dto.getTargetStatus()).isNull();
    }

    @Test
    @DisplayName("neither mapper write path carries a registration field")
    void neitherWritePathCarriesRegistrationState() {
        BotGroupDTO hostile = new BotGroupDTO();
        hostile.setName("n");
        hostile.setRegisteredCount(500);
        hostile.setNamedCount(500);
        hostile.setRegistrationError("cleared by a client");

        // POST / — the Lombok builder path, which a source scan cannot see at all (it is not a
        // setX( call). This is the exact shape that let the targetStatus hole through in Phase 2.
        BotGroup created = mapper.toEntity(hostile);
        assertThat(created.getRegisteredCount()).isZero();
        assertThat(created.getNamedCount()).isZero();
        assertThat(created.getRegistrationState()).isNull();
        assertThat(created.getRegistrationError()).isNull();

        // PATCH /{id} — the merge path, through setters taking variables a scan cannot judge.
        BotGroup existing = BotGroup.builder()
                .id("g-1").name("n").botCount(500).registeredCount(63)
                .registrationState(RegistrationState.FAILED)
                .registrationError("Registration stopped at account 64 of 500")
                .build();
        mapper.updateEntityFromDTO(hostile, existing);

        assertThat(existing.getRegisteredCount())
                .as("a client-writable high-water mark lets a request body make the worker skip "
                        + "437 accounts that do not exist")
                .isEqualTo(63);
        assertThat(existing.getRegistrationState()).isEqualTo(RegistrationState.FAILED);
        assertThat(existing.getRegistrationError()).isEqualTo("Registration stopped at account 64 of 500");
    }

    @Test
    @DisplayName("Jackson ignores the registration fields inbound but renders them outbound")
    void theDtoFieldsAreReadOnly() throws Exception {
        BotGroupDTO read = json.readValue(
                "{\"name\":\"n\",\"registeredCount\":500,\"namedCount\":500,"
                        + "\"registrationError\":\"gone\"}", BotGroupDTO.class);

        assertThat(read.getRegisteredCount()).isNull();
        assertThat(read.getNamedCount()).isNull();
        assertThat(read.getRegistrationError()).isNull();
        assertThat(read.getName()).as("the rest of the body still binds").isEqualTo("n");

        // Rendered outbound, which is what makes the READ_ONLY choice safe rather than merely
        // strict: a read-modify-write client gets the counts back, hands them straight to its
        // next PATCH, and they are ignored — no 400 on a body the server itself produced.
        BotGroupDTO rendered = new BotGroupDTO();
        rendered.setRegisteredCount(120);
        assertThat(json.writeValueAsString(rendered)).contains("\"registeredCount\":120");
    }

    @Test
    @DisplayName("BOT_PROVISIONING compliance D1: depositedCount renders as-is — 0 stays 0, not absent")
    void depositedCountZeroIsRendered() throws Exception {
        BotGroup funded = BotGroup.builder().id("g-1").name("G").botCount(3).initialDeposit(1_000_000L)
                .registrationState(RegistrationState.PENDING).build();

        BotGroupDTO dto = mapper.toDTO(funded);

        assertThat(dto.getDepositedCount()).isZero();
        assertThat(json.writeValueAsString(dto)).contains("\"depositedCount\":0");

        funded.setDepositedCount(2);
        assertThat(mapper.toDTO(funded).getDepositedCount()).isEqualTo(2);

        // Read-only inbound, like the registration counters.
        BotGroupDTO read = json.readValue("{\"depositedCount\":9,\"depositInFlight\":4}", BotGroupDTO.class);
        assertThat(read.getDepositedCount()).isNull();
        assertThat(read.getDepositInFlight()).isNull();
    }
}
