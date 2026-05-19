package com.vingame.bot.infrastructure.client.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UserRegistrationRequest {

    private String fullname;
    private String username;
    private String password;
    private String avatar;

    @JsonProperty("register_ip")
    private String registerIp;

    private String ip;
    private String os;

    @JsonProperty("app_id")
    private String appId;

    private String device;
    private String browser;
    private String fg;
    private String type;
    private String source;
}
