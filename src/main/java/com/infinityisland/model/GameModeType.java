package com.infinityisland.model;

public enum GameModeType {
    LIGHTNING("lightning"),
    SURF("surf"),
    ROCKET("rocket"),
    BONUS("bonus"),
    PRETEST("pretest");

    private final String value;

    GameModeType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static GameModeType fromValue(String v) {
        if (v == null) return null;
        for (GameModeType t : values()) {
            if (t.value.equalsIgnoreCase(v)) return t;
        }
        return null;
    }
}
