package com.infinityisland.controller;

public final class ConfigDtos {

    private ConfigDtos() {}

    public record UpdateAdminPinRequest(String currentPin, String newPin) {}

    public record UpdateBlackBeltTimerRequest(Long timerMs) {}

    public record UpdatePretestTimeLimitRequest(Long timeLimitMs) {}
}
