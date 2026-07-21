package com.infinityisland.model;

public enum QuizStatus {
    PREPARED("prepared"),
    RUNNING("running"),
    COMPLETED("completed");

    private final String value;

    QuizStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static QuizStatus fromValue(String v) {
        if (v == null) return null;
        for (QuizStatus s : values()) {
            if (s.value.equalsIgnoreCase(v)) return s;
        }
        return null;
    }
}
