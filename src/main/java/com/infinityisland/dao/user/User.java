package com.infinityisland.dao.user;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDate;
import java.util.Date;
import java.util.Map;

@Document("users")
public class User {

    @Id
    private String id;

    @Indexed
    private String pin;

    // both are used by various spots
    private String name;
    private String displayName;

    private String theme;

    // Node-style flexible structure: { "L1": { ... }, "L2": { ... } }
    private Map<String, Object> progress;

    // auth-related, referenced by AuthService
    private Integer currentStreak;  // nullable -> getter returns 0 if null
    private String lastLoginDate;  // e.g., "2025-11-12"

    // optional cached metrics
    private Long grandTotalCorrect;
    private Date updatedAt;

    // --- getters/setters ---

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getPin() {
        return pin;
    }

    public void setPin(String pin) {
        this.pin = pin;
    }

    public String getName() {
        return name != null ? name : displayName;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDisplayName() {
        return displayName != null ? displayName : name;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getTheme() {
        return theme;
    }

    public void setTheme(String theme) {
        this.theme = theme;
    }

    public Map<String, Object> getProgress() {
        return progress;
    }

    public void setProgress(Map<String, Object> progress) {
        this.progress = progress;
    }

    public int getCurrentStreak() {
        return currentStreak == null ? 0 : currentStreak;
    }

    public void setCurrentStreak(int currentStreak) {
        this.currentStreak = currentStreak;
    }

    public String getLastLoginDate() {
        return lastLoginDate;
    }

    public void setLastLoginDate(String lastLoginDate) {
        this.lastLoginDate = lastLoginDate;
    }

    public Long getGrandTotalCorrect() {
        return grandTotalCorrect;
    }

    public void setGrandTotalCorrect(Long grandTotalCorrect) {
        this.grandTotalCorrect = grandTotalCorrect;
    }

    public Date getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Date updatedAt) {
        this.updatedAt = updatedAt;
    }
}