package com.vingame.bot.infrastructure.client.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of a bulk user registration operation.
 * <p>
 * Contains success/failure counts and details about failures.
 * Used to determine if a BotGroup can be created based on registration results.
 */
@Getter
@Builder
public class UserRegistrationResult {
    /**
     * Total number of users requested to be registered
     */
    private final int totalRequested;

    /**
     * Number of users successfully registered
     */
    private final int successCount;

    /**
     * Number of users that failed to register
     */
    private final int failureCount;

    /**
     * Number of users the <b>gateway budget refused to send</b>, as opposed to users the gateway
     * rejected (GATEWAY_REQUEST_BUDGET AD-9, review F2).
     * <p>
     * The distinction is the whole of AD-9 applied to this one path: a request the JVM chose not
     * to send is not an account the upstream refused to create. Counting a deferral as a failure
     * makes our own pacing look like a gateway outage — as a 502 at the REST layer, as an ERROR
     * per user in the log, and, from Phase 4, as an account the {@code RegistrationWorker} would
     * mark {@code FAILED} instead of re-queueing. A2.6 requires the worker to make exactly this
     * distinction, which is why it is modelled here rather than only handled here.
     * <p>
     * A deferred index is <b>resumable</b>: nothing was created, so re-registering it later is a
     * first registration and not the {@code EXISTED} case.
     */
    private final int deferredCount;

    /**
     * List of error messages for failed registrations
     */
    @Builder.Default
    private final List<String> errors = new ArrayList<>();

    /**
     * Check if all registrations were successful.
     *
     * @return true if all users were registered successfully, false otherwise
     */
    public boolean isAllSuccessful() {
        return failureCount == 0 && deferredCount == 0 && successCount == totalRequested;
    }

    /**
     * Whether <b>nothing was created and the only reason was our own pacing</b>
     * (GATEWAY_REQUEST_BUDGET review F2).
     * <p>
     * This is the case that must answer <b>429</b> rather than 502: the gateway was never asked,
     * so calling it a "Game server error" points an operator at a host that is working perfectly.
     * Deliberately narrower than {@link #isCompleteFailure()} — one genuine upstream rejection in
     * the batch makes it a real failure again, because then something really did refuse us.
     */
    public boolean isCompleteDeferral() {
        return successCount == 0 && failureCount == 0 && deferredCount > 0;
    }

    /**
     * Check if registration completely failed.
     *
     * @return true if no users were registered successfully
     */
    public boolean isCompleteFailure() {
        return successCount == 0;
    }

    /**
     * Check if registration was partial (some succeeded, some failed).
     *
     * @return true if at least one succeeded and at least one failed
     */
    public boolean isPartialSuccess() {
        return successCount > 0 && (failureCount > 0 || deferredCount > 0);
    }

    /**
     * Get success rate as percentage.
     *
     * @return percentage of successful registrations (0.0 to 100.0)
     */
    public double getSuccessRate() {
        if (totalRequested == 0) {
            return 0.0;
        }
        return (successCount * 100.0) / totalRequested;
    }
}
