package com.vingame.bot.domain.brand.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;

@JsonFormat(shape = JsonFormat.Shape.OBJECT)
public enum ProductCode {

    P_066("066", "KCLUB", null),
    P_097("097", "BOM", "bc114097"),
    P_098("098", "B52", "bc114098"),
    P_103("103", "HIT", null),
    P_105("105", "IWIN", null),
    P_114("114", "RIK", null),
    P_116("116", "TIP", "bc115116"),
    P_118("118", "NOHU", null),
    P_119("119", "WIN79", null),
    P_222("222", "BKK WIN", null);

    private final String code;
    private final String name;
    private final String appId;

    ProductCode(String code, String name, String appId) {
        this.code = code;
        this.name = name;
        this.appId = appId;
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
