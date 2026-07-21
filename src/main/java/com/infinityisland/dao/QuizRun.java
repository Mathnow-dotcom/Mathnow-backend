package com.infinityisland.dao;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.infinityisland.model.GameModeType;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Document("quiz_runs")
public class QuizRun {
    @Id
    private String id;

    private String userId;
    private String operation;
    private Integer level;
    private String beltOrDegree;

    private String status; // prepared | running | completed
    private Integer currentIndex;

    private List<String> items = new ArrayList<>();

    // Counters
    private Integer mainFlowCorrect;
    private Integer wrong;

    // ===== GAME MODE FIELDS =====
    private Boolean gameMode;       // true = game mode active (any type)
    private String gameModeType;    // "lightning" | "surf" | "rocket" | null (for backward compat, null + gameMode=true means lightning)
    private Integer targetCorrect;  // 100 for lightning mode, null for normal/surf
    private Integer totalCorrect;   // accumulated correct answers in lightning mode

    // ===== SURF MODE FIELDS (Game Mode 2) =====
    private Integer surfQuizNumber;        // 1-5: which surf quiz (out of 5 required)
    private Integer surfCorrectStreak;     // 0-4: consecutive correct in current quiz
    private Integer completedSurfQuizzes;  // 0-5: how many surf quizzes passed
    private Integer surfQuizFailures;      // Analytics: total surf quiz failures
    private Boolean surfQuizFailed;        // Flag: current quiz failed, needs restart after practice

    // ===== ROCKET MODE FIELDS (Game Mode 3) =====
    private Integer rocketQuizNumber;        // 1-5: current rocket quiz
    private Integer rocketCorrectStreak;     // 0-4: consecutive correct in current quiz
    private Integer completedRocketQuizzes;  // 0-5: rocket quizzes passed
    private Integer rocketQuizFailures;      // total rocket quiz failures
    private Boolean rocketQuizFailed;        // needs restart after practice

    // ===== BONUS MODE FIELDS (Game Mode 4) =====
    // Per PRD: "Don't show the students the counter of stars or their streak of correct answers."
    // Counter fields are server-only — @JsonIgnore prevents wire leakage. Only showBonusVideo
    // and bonusComplete boolean signals are emitted by the response builders.
    @JsonIgnore private Integer bonusStreak;        // 0..bonusTargetCorrect: consecutive correct
    @JsonIgnore private Integer bonusTotalCorrect;  // analytics: total correct in this run
    @JsonIgnore private Integer bonusTotalWrong;    // analytics: total wrong in this run
    @JsonIgnore private Boolean bonusInPractice;    // gates resume into practice vs quiz
    @JsonIgnore private Integer bonusBatchNumber;   // 1-based batch counter; new batch every bonusQuestionsPerBatch slots

    // ===== PRETEST MODE FIELDS =====
    private Boolean pretestMode;           // true = this is a pretest quiz

    // Timing
    private Long totalActiveMs;
    private Timer timer;

    private Boolean passed;

    private Instant createdAt;
    private Instant updatedAt;
    private Boolean practiceCompletesRun;
    private Integer totalQuestions;

    public Integer getTotalQuestions() { return totalQuestions; }
    public void setTotalQuestions(Integer totalQuestions) { this.totalQuestions = totalQuestions; }

    public Boolean getPracticeCompletesRun() {
        return practiceCompletesRun != null ? practiceCompletesRun : Boolean.FALSE;
    }
    public void setPracticeCompletesRun(Boolean practiceCompletesRun) {
        this.practiceCompletesRun = practiceCompletesRun;
    }

    // ===== GAME MODE GETTERS/SETTERS =====
    public Boolean getGameMode() {
        return gameMode;
    }

    public void setGameMode(Boolean gameMode) {
        this.gameMode = gameMode;
    }

    /**
     * Returns true if ANY game mode is active (lightning, surf, or rocket)
     */
    public boolean isGameMode() {
        return Boolean.TRUE.equals(gameMode);
    }

    public String getGameModeType() {
        return gameModeType;
    }

    public void setGameModeType(String gameModeType) {
        this.gameModeType = gameModeType;
    }

    /**
     * Returns true if Surf mode (Game Mode 2)
     */
    public boolean isSurfMode() {
        return Boolean.TRUE.equals(gameMode) && GameModeType.SURF.value().equalsIgnoreCase(gameModeType);
    }

    /**
     * Returns true if Rocket mode (Game Mode 3)
     */
    public boolean isRocketMode() {
        return Boolean.TRUE.equals(gameMode) && GameModeType.ROCKET.value().equalsIgnoreCase(gameModeType);
    }

    /**
     * Returns true if Bonus mode (Game Mode 4)
     */
    public boolean isBonusMode() {
        return Boolean.TRUE.equals(gameMode) && GameModeType.BONUS.value().equalsIgnoreCase(gameModeType);
    }

    public Integer getTargetCorrect() {
        return targetCorrect;
    }

    public void setTargetCorrect(Integer targetCorrect) {
        this.targetCorrect = targetCorrect;
    }

    public Integer getTotalCorrect() {
        return totalCorrect;
    }

    public void setTotalCorrect(Integer totalCorrect) {
        this.totalCorrect = totalCorrect;
    }

    // ===== SURF MODE GETTERS/SETTERS =====
    public Integer getSurfQuizNumber() {
        return surfQuizNumber;
    }

    public void setSurfQuizNumber(Integer surfQuizNumber) {
        this.surfQuizNumber = surfQuizNumber;
    }

    public Integer getSurfCorrectStreak() {
        return surfCorrectStreak;
    }

    public void setSurfCorrectStreak(Integer surfCorrectStreak) {
        this.surfCorrectStreak = surfCorrectStreak;
    }

    public Integer getCompletedSurfQuizzes() {
        return completedSurfQuizzes;
    }

    public void setCompletedSurfQuizzes(Integer completedSurfQuizzes) {
        this.completedSurfQuizzes = completedSurfQuizzes;
    }

    public Integer getSurfQuizFailures() {
        return surfQuizFailures;
    }

    public void setSurfQuizFailures(Integer surfQuizFailures) {
        this.surfQuizFailures = surfQuizFailures;
    }

    public Boolean getSurfQuizFailed() {
        return surfQuizFailed;
    }

    public void setSurfQuizFailed(Boolean surfQuizFailed) {
        this.surfQuizFailed = surfQuizFailed;
    }

    // ===== ROCKET MODE GETTERS/SETTERS =====
    public Integer getRocketQuizNumber() { return rocketQuizNumber; }
    public void setRocketQuizNumber(Integer rocketQuizNumber) { this.rocketQuizNumber = rocketQuizNumber; }

    public Integer getRocketCorrectStreak() { return rocketCorrectStreak; }
    public void setRocketCorrectStreak(Integer rocketCorrectStreak) { this.rocketCorrectStreak = rocketCorrectStreak; }

    public Integer getCompletedRocketQuizzes() { return completedRocketQuizzes; }
    public void setCompletedRocketQuizzes(Integer completedRocketQuizzes) { this.completedRocketQuizzes = completedRocketQuizzes; }

    public Integer getRocketQuizFailures() { return rocketQuizFailures; }
    public void setRocketQuizFailures(Integer rocketQuizFailures) { this.rocketQuizFailures = rocketQuizFailures; }

    public Boolean getRocketQuizFailed() { return rocketQuizFailed; }
    public void setRocketQuizFailed(Boolean rocketQuizFailed) { this.rocketQuizFailed = rocketQuizFailed; }

    // ===== BONUS MODE GETTERS/SETTERS =====
    public Integer getBonusStreak() { return bonusStreak; }
    public void setBonusStreak(Integer bonusStreak) { this.bonusStreak = bonusStreak; }

    public Integer getBonusTotalCorrect() { return bonusTotalCorrect; }
    public void setBonusTotalCorrect(Integer bonusTotalCorrect) { this.bonusTotalCorrect = bonusTotalCorrect; }

    public Integer getBonusTotalWrong() { return bonusTotalWrong; }
    public void setBonusTotalWrong(Integer bonusTotalWrong) { this.bonusTotalWrong = bonusTotalWrong; }

    public Boolean getBonusInPractice() { return bonusInPractice; }
    public void setBonusInPractice(Boolean bonusInPractice) { this.bonusInPractice = bonusInPractice; }

    public Integer getBonusBatchNumber() { return bonusBatchNumber; }
    public void setBonusBatchNumber(Integer bonusBatchNumber) { this.bonusBatchNumber = bonusBatchNumber; }

    // ===== PRETEST MODE GETTERS/SETTERS =====
    public Boolean getPretestMode() {
        return pretestMode;
    }

    public void setPretestMode(Boolean pretestMode) {
        this.pretestMode = pretestMode;
    }

    /**
     * Returns true if this is a pretest quiz
     */
    public boolean isPretestMode() {
        return Boolean.TRUE.equals(pretestMode);
    }

    public static class Timer {
        private Long limitMs;
        private Long remainingMs;
        private Instant startedAt;

        public Long getLimitMs() {
            return limitMs;
        }

        public void setLimitMs(Long limitMs) {
            this.limitMs = limitMs;
        }

        public Long getRemainingMs() {
            return remainingMs;
        }

        public void setRemainingMs(Long remainingMs) {
            this.remainingMs = remainingMs;
        }

        public Instant getStartedAt() {
            return startedAt;
        }

        public void setStartedAt(Instant startedAt) {
            this.startedAt = startedAt;
        }
    }

    // ----- getters/setters -----
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getOperation() {
        return operation;
    }

    public void setOperation(String operation) {
        this.operation = operation;
    }

    public Integer getLevel() {
        return level;
    }

    public void setLevel(Integer level) {
        this.level = level;
    }

    public String getBeltOrDegree() {
        return beltOrDegree;
    }

    public void setBeltOrDegree(String beltOrDegree) {
        this.beltOrDegree = beltOrDegree;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getCurrentIndex() {
        return currentIndex;
    }

    public void setCurrentIndex(Integer currentIndex) {
        this.currentIndex = currentIndex;
    }

    public List<String> getItems() {
        return items;
    }

    public void setItems(List<String> items) {
        this.items = items;
    }

    public Integer getMainFlowCorrect() {
        return mainFlowCorrect;
    }

    public void setMainFlowCorrect(Integer mainFlowCorrect) {
        this.mainFlowCorrect = mainFlowCorrect;
    }

    public Integer getWrong() {
        return wrong;
    }

    public void setWrong(Integer wrong) {
        this.wrong = wrong;
    }

    public Long getTotalActiveMs() {
        return totalActiveMs;
    }

    public void setTotalActiveMs(Long totalActiveMs) {
        this.totalActiveMs = totalActiveMs;
    }

    public Timer getTimer() {
        return timer;
    }

    public void setTimer(Timer timer) {
        this.timer = timer;
    }

    public Boolean getPassed() {
        return passed;
    }

    public void setPassed(Boolean passed) {
        this.passed = passed;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public boolean isLightningMode() {
        return Boolean.TRUE.equals(gameMode) &&
                (GameModeType.LIGHTNING.value().equalsIgnoreCase(gameModeType) || gameModeType == null);
    }
}