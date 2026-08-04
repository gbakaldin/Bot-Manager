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
        return failureCount == 0 && successCount == totalRequested;
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
        return successCount > 0 && failureCount > 0;
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
