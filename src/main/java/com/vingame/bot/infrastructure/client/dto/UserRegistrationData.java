package com.vingame.bot.infrastructure.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * The data[0] element from a successful registration response.
 * <p>
 * Token naming: "token" in this response is the agencyToken (X-TOKEN / monetary ops),
 * while "session_id" is the authToken (passed as ?token= to verifytoken, used for WebSocket auth).
 * See CLAUDE.md Token Naming Reference for the full mapping.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class UserRegistrationData {

    /** agencyToken — used for monetary operations and X-TOKEN header */
    private String token;

    /** authToken — passed as ?token= to verifytoken.aspx, used for WebSocket auth */
    @JsonProperty("session_id")
    private String sessionId;
}
