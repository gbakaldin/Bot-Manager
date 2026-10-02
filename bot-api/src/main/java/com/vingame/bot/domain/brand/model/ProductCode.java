package com.vingame.bot.domain.brand.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;

@JsonFormat(shape = JsonFormat.Shape.OBJECT)
public enum ProductCode {

    P_066("066", "KCLUB", null, null, null),
    P_097("097", "BOM", "bc114097", null, null),
    P_098("098", "B52", "bc114098", null, null),
    P_103("103", "HIT", null, null, null),
    P_105("105", "IWIN", null, null, null),
    P_114("114", "RIK", "rik.vip", null, "!sBVCdgmzBJJKydMBQX:matrix-uat.viptalk.org"),
    P_116("116", "TIP", "bc115116", 12, "!mPUwMJaqokWLVMsCMw:matrix-uat.viptalk.org"),
    P_118("118", "NOHU", null, null, null),
    P_119("119", "WIN79", null, null, "!zWQTybTFpwJujvchvJ:matrix-uat.viptalk.org"),
    P_222("222", "BKK WIN", null, null, null);

    private final String code;
    private final String name;
    private final String appId;
    private final Integer usernameMaxLength;
    private final String vipTalkRoomId;

    ProductCode(String code, String name, String appId, Integer usernameMaxLength, String vipTalkRoomId) {
        this.code = code;
        this.name = name;
        this.appId = appId;
        this.usernameMaxLength = usernameMaxLength;
        this.vipTalkRoomId = vipTalkRoomId;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    /**
     * Canonical human-readable label for this product, mirroring the
     * {@code displayName} pattern used on {@link com.vingame.bot.domain.game.model.GameType}
     * and {@link com.vingame.bot.domain.bot.strategy.StrategyId}. Returns the
     * same value as {@link #getName()} — kept as a separate getter so the wire
     * shape stays uniform across enums.
     */
    public String getDisplayName() {
        return name;
    }

    /**
     * Static per-brand app_id used in auth gateway register/login payloads.
     * Returns {@code null} for products where the value has not been confirmed
     * yet — callers should fall back to {@code Environment.getAppId()}.
     */
    public String getAppId() {
        return appId;
    }

    /**
     * Maximum allowed username length enforced by the auth gateway for this
     * product. Returns {@code null} when no cap has been documented — callers
     * should treat null as "no pre-flight validation".
     * <p>
     * Known caps:
     * <ul>
     *   <li>{@link #P_116 Tip}: 12 characters</li>
     * </ul>
     * Other products are observed to accept longer usernames but exact limits
     * have not been confirmed. Populate as caps are discovered.
     */
    public Integer getUsernameMaxLength() {
        return usernameMaxLength;
    }

    /**
     * VipTalk (Matrix) room ID that alerts and announcements for this product are
     * published to, e.g. {@code !aBcDeF:matrix-uat.viptalk.org}. One room per
     * product; the single VipTalk bot is invited into all of them.
     * <p>
     * Deliberately hardcoded rather than configured (VIPTALK_ALERTING AD-1):
     * there is exactly one room per product and the set of products changes very
     * rarely, so the mapping is more useful as a compile-time-visible fact than as
     * yet another config surface. To wire a new product, fill in its room ID in the
     * enum constant above — nothing else needs to change.
     * <p>
     * Returns {@code null} for products whose room has not been created yet;
     * callers must treat null as "not wired" and skip, never as an error.
     * <p>
     * {@link JsonIgnore} is load-bearing: this enum serializes as an object
     * ({@link JsonFormat.Shape#OBJECT}) and is returned by the unauthenticated
     * {@code /api/v1/brand} endpoint, so without it every room ID would be
     * published in a public API response.
     */
    @JsonIgnore
    public String getVipTalkRoomId() {
        return vipTalkRoomId;
    }

    /**
     * Whether this product has a VipTalk room wired up.
     *
     * @return {@code true} when {@link #getVipTalkRoomId()} is set and non-blank.
     */
    @JsonIgnore
    public boolean hasVipTalkRoom() {
        return vipTalkRoomId != null && !vipTalkRoomId.isBlank();
    }

    @JsonCreator
    public static ProductCode fromCode(String code) {
        for (ProductCode pc : values()) {
            if (pc.code.equals(code)) {
                return pc;
            }
        }
        throw new IllegalArgumentException("Invalid product code: " + code);
    }
}
