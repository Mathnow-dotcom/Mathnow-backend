package com.infinityisland.controller;

import java.util.Map;

public final class UserDtos {

    private UserDtos() {}

    // --------- Requests ----------
    public record ThemeRequest(String themeKey) {}

    public record RateVideoRequest(Integer rating, Integer level, String beltOrDegree) {}
}
