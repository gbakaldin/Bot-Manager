package com.vingame.bot.domain.brand.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;

@JsonFormat(shape = JsonFormat.Shape.OBJECT)
public enum ProductCode {

    P_066("066", "KCLUB"),
    P_097("097", "BOM"),
    P_098("098", "B52"),
    P_103("103", "HIT"),
    P_105("105", "IWIN"),
    P_114("114", "RIK"),
    P_116("116", "TIP"),
    P_118("118", "NOHU"),
    P_119("119", "WIN79"),
    P_222("222", "BKK WIN");

    private final String code;
    private final String name;

    ProductCode(String code, String name) {
        this.code = code;
        this.name = name;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
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
