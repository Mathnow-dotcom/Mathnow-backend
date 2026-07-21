package com.infinityisland.model;

public enum Operation {
    ADD("add"),
    SUB("sub"),
    MUL("mul"),
    DIV("div");

    private final String value;

    Operation(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static Operation fromValue(String v) {
        if (v == null) return null;
        for (Operation op : values()) {
            if (op.value.equalsIgnoreCase(v)) return op;
        }
        return null;
    }
}
