package com.vingame.bot.infrastructure.auth;

import com.vingame.bot.domain.bot.auth.B52LoginRequest;
import com.vingame.bot.domain.bot.auth.BomLoginRequest;
import com.vingame.bot.domain.bot.auth.TipLoginRequest;
import com.vingame.bot.domain.brand.model.ProductCode;
import com.vingame.bot.domain.environment.model.Environment;
import com.vingame.websocketparser.auth.DefaultLoginRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AuthStrategyFactory {

    @Value("${bot.ip}")
    private String botIp;

    public AuthProfile getAuthProfile(Environment env) {
        ProductCode productCode = env.getProductCode();
        if (productCode == null) {
            throw new IllegalArgumentException("Environment '" + env.getName() + "' has no productCode set");
        }

        return switch (productCode) {
            case P_103, P_105, P_114, P_118, P_119, P_222, P_066 -> new AuthProfile(
                    "/gwms/v1/bot/login.aspx",
                    "/gwms/v1/bot/register.aspx",
                    "/gwms/v1/bot/update-fullname.aspx",
                    "58bc2820612d23c34fe43d0b2c6f7223",
                    ctx -> new DefaultLoginRequest(ctx.userName(), ctx.password(), ctx.appId(), ctx.fingerprint())
            );
            case P_116 -> new AuthProfile(
                    "/gwms/v1/bot/login.aspx",
                    "/gwms/v1/bot/register.aspx",
                    "/gwms/v1/bot/update-fullname.aspx",
                    "58bc2820612d23c34fe43d0b2c6f7223",
                    ctx -> new TipLoginRequest(ctx.userName(), ctx.password(), ctx.fingerprint(), botIp)
            );
            case P_097 -> new AuthProfile(
                    "/gwms/v1/bot/login.aspx",
                    "/gwms/v1/bot/register.aspx",
                    "/gwms/v1/bot/update-fullname.aspx",
                    "58bc2820612d23c34fe43d0b2c6f7223",
                    ctx -> new BomLoginRequest(ctx.userName(), ctx.password(), ctx.fingerprint(), botIp)
            );
            case P_098 -> new AuthProfile(
                    "/gwms/v1/bot/login.aspx",
                    "/gwms/v1/bot/register.aspx",
                    "/gwms/v1/bot/update-fullname.aspx",
                    "58bc2820612d23c34fe43d0b2c6f7223",
                    ctx -> new B52LoginRequest(ctx.userName(), ctx.password(), ctx.fingerprint(), botIp)
            );
        };
    }
}
