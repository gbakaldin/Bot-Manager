package com.vingame.bot.domain.brand.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;

@JsonFormat(shape = JsonFormat.Shape.OBJECT)
public enum ProductCode {

    P_066("066", "KCLUB", null, null),
    P_097("097", "BOM", "bc114097", null),
    P_098("098", "B52", "bc114098", null),
    P_103("103", "HIT", null, null),
    P_105("105", "IWIN", null, null),
    P_114("114", "RIK", null, null),
    P_116("116", "TIP", "bc115116", 12),
    P_118("118", "NOHU", null, null),
    P_119("119", "WIN79", null, null),
    P_222("222", "BKK WIN", null, null);

    private final String code;
    private final String name;
    private final String appId;
    private final Integer usernameMaxLength;

    ProductCode(String code, String name, String appId, Integer usernameMaxLength) {
        this.code = code;
        this.name = name;
        this.appId = appId;
        this.usernameMaxLength = usernameMaxLength;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
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
