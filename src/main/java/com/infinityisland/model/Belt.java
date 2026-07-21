package com.infinityisland.model;

import java.util.List;

public enum Belt {
    WHITE("white"),
    YELLOW("yellow"),
    GREEN("green"),
    BLUE("blue"),
    RED("red"),
    BROWN("brown");

    private final String value;

    Belt(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static final List<String> COLORED_ORDER =
            List.of(WHITE.value, YELLOW.value, GREEN.value,
                    BLUE.value, RED.value, BROWN.value);

    public static Belt fromValue(String v) {
        if (v == null) return null;
        for (Belt b : values()) {
            if (b.value.equalsIgnoreCase(v)) return b;
        }
        return null;
    }
}
