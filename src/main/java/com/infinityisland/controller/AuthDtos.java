package com.infinityisland.controller;

import com.infinityisland.service.AuthService;

public final class AuthDtos {

    private AuthDtos() {}

    // --------- Responses ----------
    public record LoginResponse(AuthService.UserPayload user, String token) {}
}
