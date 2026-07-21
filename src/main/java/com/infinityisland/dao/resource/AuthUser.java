package com.infinityisland.dao.resource;

import com.infinityisland.dao.user.DailyStats;
import com.infinityisland.dao.user.ProgressState;

import java.util.Map;

public class AuthUser {
    String id;
    String name;
    Map<String, ProgressState> progress;
    Map<String, DailyStats> dailyStats;

    public AuthUser(String id, String name, Map<String, ProgressState> progress, Map<String, DailyStats> dailyStats) {
        this.id = id;
        this.name = name;
        this.progress = progress;
        this.dailyStats = dailyStats;
    }

    public AuthUser() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Map<String, ProgressState> getProgress() {
        return progress;
    }

    public void setProgress(Map<String, ProgressState> progress) {
        this.progress = progress;
    }

    public Map<String, DailyStats> getDailyStats() {
        return dailyStats;
    }

    public void setDailyStats(Map<String, DailyStats> dailyStats) {
        this.dailyStats = dailyStats;
    }
}