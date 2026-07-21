package com.infinityisland.dao;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

@Document("daily_summaries")
public class DailySummary {

    @Id
    private String id;

    private String userId;
    private LocalDate date;

    private int correctCount;
    private long totalActiveMs;

    public DailySummary() {
    }

    public DailySummary(String userId, LocalDate date) {
        this.userId = userId;
        this.date = date;
        this.correctCount = 0;
        this.totalActiveMs = 0L;
    }

    public String getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }

    public int getCorrectCount() {
        return correctCount;
    }

    public void setCorrectCount(int correctCount) {
        this.correctCount = correctCount;
    }

    public long getTotalActiveMs() {
        return totalActiveMs;
    }

    public void setTotalActiveMs(long totalActiveMs) {
        this.totalActiveMs = totalActiveMs;
    }

    /**
     * Convenience for controllers/services that want a map payload.
     */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new HashMap<>();
        m.put("_id", id);
        m.put("user", userId);
        m.put("date", String.valueOf(date));
        m.put("correctCount", correctCount);
        m.put("totalActiveMs", totalActiveMs);
        return m;
    }
}