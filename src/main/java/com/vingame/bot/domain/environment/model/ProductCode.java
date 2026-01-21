package com.vingame.bot.domain.environment.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ProductCode {

    P_066("066"),
    P_097("097"),
    P_098("098"),
    P_103("103"),
    P_105("105"),
    P_114("114"),
    P_116("116"),
    P_118("118"),
    P_119("119"),
    P_222("222");

    private final String code;

    ProductCode(String code) {
        this.code = code;
    }

    @JsonValue
    public String getCode() {
        return code;
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
