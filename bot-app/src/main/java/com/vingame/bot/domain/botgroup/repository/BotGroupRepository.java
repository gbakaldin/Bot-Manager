package com.vingame.bot.domain.botgroup.repository;

import com.vingame.bot.domain.botgroup.model.ActivationMode;
import com.vingame.bot.domain.botgroup.model.BotGroup;
import com.vingame.bot.domain.botgroup.model.BotGroupStatus;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface BotGroupRepository extends MongoRepository<BotGroup, String> {

    List<BotGroup> findByEnvironmentId(String environmentId);

    List<BotGroup> findByGameId(String gameId);

    List<BotGroup> findByTargetStatus(BotGroupStatus targetStatus);

    List<BotGroup> findByActivationMode(ActivationMode activationMode);

    /**
     * Every group whose accounts are still being created, or whose creation stopped
     * (GATEWAY_REQUEST_BUDGET A6 Phase 4 item 1). {@code state} is one of
     * {@link com.vingame.bot.domain.botgroup.model.RegistrationState}'s constants.
     * <p>
     * Driven by the <b>persisted</b> state rather than by an in-memory queue, for the reason
     * {@code DeadGroupRecoveryScheduler} is: a group whose registration was interrupted by a JVM
     * restart is absent from every in-memory structure, and that is exactly the case with the
     * longest time-to-notice. The {@code ApplicationReadyEvent} re-enqueue and the worker's own
     * sweep both read this.
     * <p>
     * A Mongo query on a {@code String} field, so an unknown value read back is just a string
     * that matches neither call — no conversion, no boot hazard.
     */
    List<BotGroup> findByRegistrationState(String registrationState);

    /**
     * How many groups are in {@code registrationState}, without loading them (review T7).
     * <p>
     * The gauges behind {@code registration_pending_groups} / {@code registration_failed_groups}
     * only ever needed a number, and this query runs every {@code bot.registration.tick-seconds}
     * for the life of the JVM — and, since review S5, again inside a long pass.
     */
    long countByRegistrationState(String registrationState);
}
