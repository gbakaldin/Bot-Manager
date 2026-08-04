package com.vingame.bot.infrastructure.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * Response from user registration API.
 * <p>
 * Example successful response:
 * {
 *   "status": "OK",
 *   "code": 200,
 *   "message": "Register successful",
 *   "data": [{ "username": "user1", "token": "...", ... }]
 * }
 * <p>
 * Example error response:
 * {
 *   "status": "ERROR",
 *   "code": 400,
 *   "message": "Username already exists"
 * }
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class UserRegistrationResponse {

    /**
     * Status string - "OK" for success, "ERROR" for failure
     */
    private String status;

    /**
     * HTTP status code - 200 for success, 4xx/5xx for errors
     */
    private int code;

    /**
     * Human-readable message describing the result
     */
    private String message;

    /**
     * Response data - array of registered user objects; use data.get(0) for the created user
     */
    private List<UserRegistrationData> data;

    /**
     * Error details (if status is ERROR)
     */
    private String error;

    /**
     * Check if the registration was successful.
     *
     * @return true if status is "OK" or code is 200
     */
    public boolean isSuccess() {
        return "OK".equalsIgnoreCase(status) || code == 200;
    }
}
