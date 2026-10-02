package com.vingame.bot.domain.botgroup.mapper;

import com.vingame.bot.common.exception.BadRequestException;
import com.vingame.bot.domain.botgroup.dto.BotGroupDTO;
import com.vingame.bot.domain.botgroup.model.BotGroup;
import com.vingame.bot.domain.botgroup.model.BotGroupStatus;
import com.vingame.bot.domain.botgroup.model.RegistrationState;
import org.mapstruct.Mapper;
import org.mapstruct.MappingTarget;

import java.util.Optional;

@Mapper(componentModel = "spring")
public interface BotGroupMapper {

    /**
     * Convert Entity to DTO
     * Null fields will be excluded from JSON due to @JsonInclude(NON_NULL)
     */
    default BotGroupDTO toDTO(BotGroup entity) {
        if (entity == null) {
            return null;
        }

        return BotGroupDTO.builder()
                .id(entity.getId())
                .name(entity.getName())
                .environmentId(entity.getEnvironmentId())
                .namePrefix(entity.getNamePrefix())
                .password(entity.getPassword())
                .gameId(entity.getGameId())
                .botCount(entity.getBotCount())
                .minBet(entity.getMinBet())
                .maxBet(entity.getMaxBet())
                .betIncrement(entity.getBetIncrement())
                .maxTotalBetPerRound(entity.getMaxTotalBetPerRound())
                .minBetsPerRound(entity.getMinBetsPerRound())
                .maxBetsPerRound(entity.getMaxBetsPerRound())
                .coordinationEnabled(entity.isCoordinationEnabled())
                .maxAggregateStakePerRound(entity.getMaxAggregateStakePerRound())
                .crowdAwareCoordination(entity.isCrowdAwareCoordination())
                .rampEnabled(entity.isRampEnabled())
                .rampShape(entity.getRampShape())
                .affinityWeightedProposal(entity.isAffinityWeightedProposal())
                .activationMode(entity.getActivationMode())
                .activationWindow(entity.getActivationWindow())
                .chatEnabled(entity.isChatEnabled())
                .autoDepositEnabled(entity.isAutoDepositEnabled())
                .strategyMix(entity.getStrategyMix())
                .slotStrategyId(entity.getSlotStrategyId())
                // Derived, not copied (GATEWAY_REQUEST_BUDGET A1): a group whose accounts are
                // still being created renders REGISTRATION_PENDING / REGISTRATION_FAILED here
                // while the document's own targetStatus stays one of the original three. This is
                // the ONLY place those two constants are produced, and producing them here rather
                // than persisting them is what keeps a rollback safe.
                .targetStatus(renderedStatus(entity))
                .registeredCount(entity.getRegistrationState() == null && entity.getRegisteredCount() == 0
                        ? null : entity.getRegisteredCount())
                .namedCount(entity.getRegistrationState() == null && entity.getNamedCount() == 0
                        ? null : entity.getNamedCount())
                .registrationError(entity.getRegistrationError())
                .initialDeposit(entity.getInitialDeposit())
                .depositedCount(entity.getDepositedCount() == 0 ? null : entity.getDepositedCount())
                .depositInFlight(entity.getDepositInFlight())
                .scheduledRestartTime(entity.getScheduledRestartTime())
                .lastStartedAt(entity.getLastStartedAt())
                .lastStoppedAt(entity.getLastStoppedAt())
                .lastFailureReason(entity.getLastFailureReason())
                .build();
    }

    /**
     * The lifecycle value a client sees, derived from the document
     * (GATEWAY_REQUEST_BUDGET A1 / A3).
     * <p>
     * {@code registrationState} wins over {@code targetStatus} while it is set, because it is the
     * more specific statement about the same group: a group that is still creating accounts
     * cannot be started, whatever a stale {@code targetStatus} says, and an operator reading
     * "STOPPED" on a group that is half-registered would draw the wrong conclusion twice over.
     * On completion the worker clears {@code registrationState} and this falls back to
     * {@code targetStatus} — {@code null} for a freshly registered group, byte-for-byte the state
     * a synchronously registered group has had since day one.
     * <p>
     * <b>This is the whole of the "derived at the DTO boundary" rule.</b> The two registration
     * constants exist only here and in {@code BotGroupStatusDTO}; nothing persists them, and
     * {@code BotGroupStatusPersistenceGuardTest} fails the build if anything starts to.
     */
    static BotGroupStatus renderedStatus(BotGroup entity) {
        String state = entity.getRegistrationState();
        if (RegistrationState.isPending(state)) {
            return BotGroupStatus.REGISTRATION_PENDING;
        }
        if (RegistrationState.isFailed(state)) {
            return BotGroupStatus.REGISTRATION_FAILED;
        }
        // Any other non-null value is a document written by something this jar does not know
        // about. Falling back to targetStatus is the fail-open choice on purpose: the alternative
        // is a group that renders as nothing at all, and an unrecognised string here cannot be
        // made meaningful by guessing.
        return entity.getTargetStatus();
    }

    /**
     * Convert DTO to Entity using Builder pattern
     * Uses Optional.ofNullable().orElse() for null-safe defaults
     */
    default BotGroup toEntity(BotGroupDTO dto) {
        if (dto == null) {
            return null;
        }

        return BotGroup.builder()
                .id(dto.getId())
                .name(dto.getName())
                .environmentId(dto.getEnvironmentId())
                .namePrefix(dto.getNamePrefix())
                .password(dto.getPassword())
                .gameId(dto.getGameId())
                .botCount(Optional.ofNullable(dto.getBotCount()).orElse(0))
                .minBet(Optional.ofNullable(dto.getMinBet()).orElse(0L))
                .maxBet(Optional.ofNullable(dto.getMaxBet()).orElse(0L))
                .betIncrement(Optional.ofNullable(dto.getBetIncrement()).orElse(0L))
                .maxTotalBetPerRound(Optional.ofNullable(dto.getMaxTotalBetPerRound()).orElse(0L))
                .minBetsPerRound(Optional.ofNullable(dto.getMinBetsPerRound()).orElse(0))
                .maxBetsPerRound(Optional.ofNullable(dto.getMaxBetsPerRound()).orElse(0))
                .coordinationEnabled(Optional.ofNullable(dto.getCoordinationEnabled()).orElse(false))
                .maxAggregateStakePerRound(Optional.ofNullable(dto.getMaxAggregateStakePerRound()).orElse(0L))
                .crowdAwareCoordination(Optional.ofNullable(dto.getCrowdAwareCoordination()).orElse(false))
                .rampEnabled(Optional.ofNullable(dto.getRampEnabled()).orElse(false))
                .rampShape(Optional.ofNullable(dto.getRampShape()).orElse(0.0))
                .affinityWeightedProposal(Optional.ofNullable(dto.getAffinityWeightedProposal()).orElse(false))
                .activationMode(dto.getActivationMode())
                .activationWindow(dto.getActivationWindow())
                .chatEnabled(Optional.ofNullable(dto.getChatEnabled()).orElse(false))
                .autoDepositEnabled(Optional.ofNullable(dto.getAutoDepositEnabled()).orElse(false))
                .strategyMix(dto.getStrategyMix())
                .slotStrategyId(dto.getSlotStrategyId())
                // BOT_PROVISIONING AD-1: optional, absent = 0 = no money moves. depositedCount and
                // depositInFlight are system-managed like registeredCount and never copied.
                .initialDeposit(Optional.ofNullable(dto.getInitialDeposit()).orElse(0L))
                // targetStatus is deliberately NOT mapped from the DTO (GATEWAY_REQUEST_BUDGET
                // R1/Q1). It is system-managed: the lifecycle endpoints write it, and three of
                // the six BotGroupStatus constants cannot be read back by a pre-feature jar, so a
                // client-supplied value is how a rollback stops being safe. @JsonProperty(
                // READ_ONLY) already stops it arriving over HTTP; this stops an in-process caller
                // (a script, a future controller, a test fixture) getting there another way.
                .scheduledRestartTime(dto.getScheduledRestartTime())
                // ...and neither are the other three system-managed fields (QA, Phase 2 re-check).
                // They used to be copied from the DTO on the very next lines, beside the
                // targetStatus line that was removed, while this method's own trailing comment in
                // updateEntityFromDTO called all four "system-managed" — so a reader who saw
                // targetStatus closed could reasonably assume the set was, and only one of them
                // was. No rollback hazard (a String and two dates are readable by any jar), which
                // is why this is a correctness tidy rather than a second blocker, but a create
                // that accepted lastStartedAt would have let a client write the group's own start
                // history: lastFailureReason is rendered to operators, lastStoppedAt gates the
                // recovery settle window, and neither has any legitimate client-supplied value.
                // startLocked and stop() are the only writers.
                //
                // ...and neither are registeredCount / namedCount / registrationState /
                // registrationError (GATEWAY_REQUEST_BUDGET A28.2). These have sharper teeth than
                // the audit trail: registeredCount is the high-water mark RegistrationWorker
                // resumes from, so a create body carrying registeredCount=500 would produce a
                // group the worker considers finished and whose five hundred accounts do not
                // exist — every bot then fails to authenticate at start, which reads as an auth
                // outage. BotGroupService.save is the only writer of the initial values and the
                // worker is the only writer thereafter.
                .build();
    }

    /**
     * Partially update existing entity with non-null values from DTO
     * Uses Optional.ofNullable().orElse() to keep existing value if DTO field is null
     */
    default void updateEntityFromDTO(BotGroupDTO dto, @MappingTarget BotGroup entity) {
        if (dto == null || entity == null) {
            return;
        }

        entity.setName(Optional.ofNullable(dto.getName()).orElse(entity.getName()));
        entity.setEnvironmentId(Optional.ofNullable(dto.getEnvironmentId()).orElse(entity.getEnvironmentId()));
        entity.setNamePrefix(Optional.ofNullable(dto.getNamePrefix()).orElse(entity.getNamePrefix()));
        entity.setPassword(Optional.ofNullable(dto.getPassword()).orElse(entity.getPassword()));
        entity.setGameId(Optional.ofNullable(dto.getGameId()).orElse(entity.getGameId()));
        entity.setBotCount(Optional.ofNullable(dto.getBotCount()).orElse(entity.getBotCount()));
        entity.setMinBet(Optional.ofNullable(dto.getMinBet()).orElse(entity.getMinBet()));
        entity.setMaxBet(Optional.ofNullable(dto.getMaxBet()).orElse(entity.getMaxBet()));
        entity.setBetIncrement(Optional.ofNullable(dto.getBetIncrement()).orElse(entity.getBetIncrement()));
        entity.setMaxTotalBetPerRound(Optional.ofNullable(dto.getMaxTotalBetPerRound()).orElse(entity.getMaxTotalBetPerRound()));
        entity.setMinBetsPerRound(Optional.ofNullable(dto.getMinBetsPerRound()).orElse(entity.getMinBetsPerRound()));
        entity.setMaxBetsPerRound(Optional.ofNullable(dto.getMaxBetsPerRound()).orElse(entity.getMaxBetsPerRound()));
        // coordinationEnabled / maxAggregateStakePerRound PATCH semantics:
        // full-replace if the DTO supplies the field (non-null); a null DTO field
        // keeps the existing value. Mirrors the other scalar fields.
        entity.setCoordinationEnabled(Optional.ofNullable(dto.getCoordinationEnabled()).orElse(entity.isCoordinationEnabled()));
        entity.setMaxAggregateStakePerRound(Optional.ofNullable(dto.getMaxAggregateStakePerRound()).orElse(entity.getMaxAggregateStakePerRound()));
        // crowdAwareCoordination PATCH semantics (CROWD_AWARE_COORDINATION AD-C6):
        // full-replace if the DTO supplies the field (non-null); a null DTO field
        // keeps the existing value. Mirrors the coordination scalar pair.
        entity.setCrowdAwareCoordination(Optional.ofNullable(dto.getCrowdAwareCoordination()).orElse(entity.isCrowdAwareCoordination()));
        // rampEnabled / rampShape PATCH semantics (JACKPOT_SCALE_AND_RAMP AD-R4):
        // full-replace if the DTO supplies the field (non-null); a null DTO field
        // keeps the existing value. Mirrors the coordination scalar pair.
        entity.setRampEnabled(Optional.ofNullable(dto.getRampEnabled()).orElse(entity.isRampEnabled()));
        entity.setRampShape(Optional.ofNullable(dto.getRampShape()).orElse(entity.getRampShape()));
        // affinityWeightedProposal PATCH semantics (AFFINITY_AWARE_PROPOSAL AD-7):
        // full-replace if the DTO supplies the field (non-null); a null DTO field
        // keeps the existing value. Mirrors the ramp/coordination scalar flags.
        entity.setAffinityWeightedProposal(Optional.ofNullable(dto.getAffinityWeightedProposal()).orElse(entity.isAffinityWeightedProposal()));
        // activationMode / activationWindow PATCH semantics: full-replace if the
        // DTO supplies the field (non-null); a null DTO field keeps the existing
        // value. Mirrors slotStrategyId. To hand a scheduled group back to the
        // schedule after a manual override, PATCH activationMode=SCHEDULED
        // (TIMED_ACTIVATION AD-4).
        entity.setActivationMode(Optional.ofNullable(dto.getActivationMode()).orElse(entity.getActivationMode()));
        entity.setActivationWindow(Optional.ofNullable(dto.getActivationWindow()).orElse(entity.getActivationWindow()));
        entity.setChatEnabled(Optional.ofNullable(dto.getChatEnabled()).orElse(entity.isChatEnabled()));
        entity.setAutoDepositEnabled(Optional.ofNullable(dto.getAutoDepositEnabled()).orElse(entity.isAutoDepositEnabled()));
        // strategyMix PATCH semantics: full-replace if DTO supplies the field
        // (non-null). A null DTO field keeps the existing value. An empty list
        // is rejected as a 400 — leaving a group with no assignable strategies
        // would crash the next start. Operators who want to clear the mix
        // should not supply the field at all (PATCH then keeps the existing
        // value), or set it to the default [(RANDOM, 1.0)].
        // Plan Architecture Decision 9 + Implementation Note 10: mid-flight
        // changes do NOT re-assign already-running bots — only newly-created /
        // restarted bots draw from the new mix.
        if (dto.getStrategyMix() != null) {
            if (dto.getStrategyMix().isEmpty()) {
                throw new BadRequestException("strategyMix must be non-empty");
            }
            entity.setStrategyMix(dto.getStrategyMix());
        }
        // slotStrategyId PATCH semantics: full-replace if DTO supplies the field
        // (non-null); a null DTO field keeps the existing value, which is also the
        // valid "fall back to FIXED" state.
        //
        // There IS an empty-value case, and it is deliberately not guarded here.
        // Since PLUGIN_HOT_RELOAD Phase 2b this is a nullable String, not an enum,
        // so "" is representable and reaches setSlotStrategyId("") through the
        // Optional below — "" is non-null. It is rejected one frame later by
        // BotGroupConfigValidationService's AD-15 check, which is not a registered
        // key and answers 400 naming ''. That is the right owner: the mapper knows
        // PATCH merge semantics, the validator knows the registry. Do not drop
        // that check on the strength of this comment — an earlier version of it
        // said "no empty-value case to guard — it is a single nullable enum",
        // which stopped being true when the type changed.
        //
        // Mid-flight changes do NOT re-assign already-running bots, mirroring
        // strategyMix.
        entity.setSlotStrategyId(Optional.ofNullable(dto.getSlotStrategyId()).orElse(entity.getSlotStrategyId()));
        entity.setScheduledRestartTime(Optional.ofNullable(dto.getScheduledRestartTime()).orElse(entity.getScheduledRestartTime()));
        // BOT_PROVISIONING AD-3: BotGroupService.update rejects a change while registration is in
        // progress; depositedCount / depositInFlight are never copied (system-managed).
        entity.setInitialDeposit(Optional.ofNullable(dto.getInitialDeposit()).orElse(entity.getInitialDeposit()));
        // Note: targetStatus, lastStartedAt, lastStoppedAt and lastFailureReason are
        // system-managed, not updated via DTO — and since the QA re-check, that is true of
        // toEntity as well. It was true of this method alone for as long as the comment existed.
        //
        // targetStatus joined that list in GATEWAY_REQUEST_BUDGET Phase 2 and is the one whose
        // absence has teeth: PATCH {"targetStatus":"STARTING"} used to be a 200 that persisted a
        // constant no pre-feature jar can deserialise, which is exactly what A1's
        // in-memory-only rule exists to prevent. Do not reinstate it — a client that needs to
        // change a group's lifecycle calls /start or /stop.
        //
        // The four registration fields (registeredCount, namedCount, registrationState,
        // registrationError) are system-managed in exactly the same way and are absent from both
        // write paths for the reason spelled out in toEntity (A28.2). The one PATCH that *does*
        // affect registration is `botCount`, which is a statement of intent and is mapped above:
        // BotGroupService.update re-enqueues the group when the merged count exceeds
        // registeredCount (A2.7). Lowering it never un-registers anything.
    }
}
