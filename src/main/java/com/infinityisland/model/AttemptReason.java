package com.infinityisland.model;

public enum AttemptReason {
    ANSWER("answer"),
    INACTIVITY("inactivity"),
    TIMEOUT("timeout");

    private final String value;

    AttemptReason(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static AttemptReason fromValue(String v) {
        if (v == null) return null;
        for (AttemptReason r : values()) {
            if (r.value.equalsIgnoreCase(v)) return r;
        }
        return null;
    }
}
