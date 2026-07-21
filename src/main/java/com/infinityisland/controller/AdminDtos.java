package com.infinityisland.controller;

import java.util.Map;

public final class AdminDtos {

    private AdminDtos() {}

    // --------- Requests ----------
    public record RestoreUserRequest(String pin, Map<String, Integer> operations,
                                     Long grandTotalCorrect, Integer currentStreak) {}
}
