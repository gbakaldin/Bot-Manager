package com.vingame.bot.infrastructure.auth;

import com.vingame.websocketparser.auth.AuthContext;
import com.vingame.websocketparser.auth.LoginRequest;

import java.util.function.Function;

public record AuthProfile(
        String loginPath,
        String registrationPath,
        String updateFullnamePath,
        String xToken,
        Function<AuthContext, ? extends LoginRequest> loginRequestFactory
) {}
