package com.infinityisland.dao.user;

public class DailyStats {
    private String date;
    private Long correctCount;
    private Long totalActiveMs;

    public DailyStats() {
        correctCount = 0L;
        totalActiveMs = 0L;
    }

    public DailyStats(Long correctCount, Long totalActiveMs) {
        this.correctCount = correctCount;
        this.totalActiveMs = totalActiveMs;
    }

    public String getDate() {
        return date;
    }

    public void setDate(String date) {
        this.date = date;
    }

    public Long getCorrectCount() {
        return correctCount;
    }

    public void setCorrectCount(Long correctCount) {
        this.correctCount = correctCount;
    }

    public Long getTotalActiveMs() {
        return totalActiveMs;
    }

    public void setTotalActiveMs(Long totalActiveMs) {
        this.totalActiveMs = totalActiveMs;
    }
}
