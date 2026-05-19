package com.vingame.bot.domain.bot.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.vingame.websocketparser.auth.LoginRequest;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class BomLoginRequest implements LoginRequest {

    private final String username;
    private final String password;

    @JsonProperty("fg")
    private final String fingerprint;

    private final String ip;

    @JsonProperty("app_id")
    private final String appId = "bc114097";

    private final String os = "OS X";
    private final String device = "Computer";
    private final String browser = "chrome";

    @JsonProperty("apVer")
    private final String apVer = "0.0.636";

    private final String version = "0.0.636";

    @JsonProperty("aff_id")
    private final String affId = "";
}
