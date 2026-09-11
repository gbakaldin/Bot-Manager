package com.vingame.bot.domain.bot.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.vingame.websocketparser.auth.LoginRequest;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Login body for P_119 / WIN79, following the canonical gwms bot-login shape
 * documented in {@code Bot-collection.http} at the repository root.
 * <p>
 * <b>Why this exists.</b> P_119 used to share the
 * {@link com.vingame.websocketparser.auth.DefaultLoginRequest} arm of
 * {@code AuthStrategyFactory} with P_066/P_103/P_105/P_118/P_222. That class
 * carries no {@code ip} field, and the gwms gateway rejects a body without one:
 * <pre>
 * {"status":"INVALID","code":400,
 *  "message":"Invalid data. Required {ip}, {os}, {device}, {browser}, {fg}."}
 * </pre>
 * Verified against {@code https://apigw-w79.sgame.us/gwms/v1/bot/login.aspx} on
 * 2026-09-10: the identical body plus {@code "ip"} returns {@code status:"OK"}.
 * {@code ip} is supplied from the {@code bot.ip} property, exactly as
 * {@link TipLoginRequest}, {@link BomLoginRequest}, {@link B52LoginRequest} and
 * {@link RikLoginRequest} do.
 * <p>
 * <b>The other five products on that arm are still unfixed</b> — they share the
 * code path and the same gateway, so they are very likely broken in the same
 * way, but only P_119 has actually been probed. Do not assume; measure before
 * changing them.
 * <p>
 * <b>{@code appId} is constructor-supplied, unlike its four siblings</b>, which
 * hardcode a brand-static value. Two reasons: {@code ProductCode.P_119.getAppId()}
 * is {@code null}, so the value legitimately comes from {@code Environment.appId}
 * via {@code EnvironmentClientRegistry} (which prefers the enum and falls back to
 * the record); and hardcoding it is listed as tech debt in {@code CLAUDE.md}'s
 * backlog. New code should not add to that pile.
 */
@Getter
@RequiredArgsConstructor
public class Win79LoginRequest implements LoginRequest {

    private final String username;
    private final String password;

    /**
     * Resolved per-environment app id (currently {@code w79.club}), taken from
     * {@code AuthContext.appId()} rather than hardcoded — see the class javadoc.
     */
    @JsonProperty("app_id")
    private final String appId;

    private final String os = "OS X";
    private final String device = "Computer";
    private final String browser = "chrome";

    @JsonProperty("fg")
    private final String fingerprint;

    @JsonProperty("aff_id")
    private final String affId = "";

    /**
     * WIN79's real client version is not known. This is the value that was
     * actually exercised against the live gateway in the 2026-09-10 probe and
     * accepted; the gateway's validation error never named {@code version} or
     * {@code apVer}, so it does not appear to be checked. Update if WIN79
     * publishes a client version.
     */
    @JsonProperty("apVer")
    private final String apVer = "0.0.490";

    private final String version = "0.0.490";

    private final String ip;
}
