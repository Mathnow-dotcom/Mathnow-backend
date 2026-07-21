package com.infinityisland.dao;

import com.infinityisland.model.Operation;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Dynamic game configuration stored in MongoDB.
 * Singleton document with id="default".
 */
@Document("game_config")
public class GameConfig {

    @Id
    private String id = "default";

    // ===== ADMIN SETTINGS =====
    private String adminPin;

    // ===== BLACK BELT TIMERS (degree -> milliseconds) =====
    private Map<Integer, Long> blackBeltTimersMs;

    // ===== LIGHTNING MODE (GAME MODE 1) =====
    private int lightningTargetCorrect;
    private long lightningFastThresholdMs;

    // ===== SURF MODE (GAME MODE 2) =====
    private int surfQuestionsPerQuiz;
    private int surfQuizzesRequired;

    // ===== ROCKET MODE (GAME MODE 3) =====
    private int rocketQuestionsPerQuiz;
    private int rocketQuizzesRequired;

    // ===== BONUS MODE (GAME MODE 4) =====
    private int bonusTargetCorrect;          // total consecutive correct needed (default 20)
    private int bonusVideoIntervalCorrect;   // award a video every N consecutive correct (default 4)
    private int bonusQuestionsPerBatch;      // generate a fresh batch every N slots (default 20)

    // ===== PRETEST MODE =====
    private int pretestQuestionCount;
    private long pretestTimeLimitMs;
    private Map<Integer, Long> pretestTimeLimitsPerLevelMs;  // Level (1-19) -> time limit in ms

    // ===== OPERATIONS =====
    private Map<String, OperationConfig> operations;

    // ===== GENERAL =====
    private long inactivityThresholdMs;
    private long pretestInactivityThresholdMs;

    // ===== METADATA =====
    private Instant createdAt;
    private Instant updatedAt;
    private String updatedBy;

    // ===== MIGRATION VERSION FLAGS =====
    // Bumped when a backend migration that rewrites user data needs to run once.
    // mulCatalogVersion: 2 == v1.7 canonical S&S catalog + hard-reset of mul/div progress.
    private Integer mulCatalogVersion;

    // ===== CONSTANTS =====
    // BOOTSTRAP PIN — first-boot default only. Change immediately via PUT /api/config/admin-pin.
    // Persisted in the game_config Mongo document; this constant is only used when the document
    // is missing or when a brand-new install creates it.
    public static final String DEFAULT_ADMIN_PIN = "7878";

    // ===== CONSTRUCTORS =====

    public GameConfig() {
        applyDefaults();
    }

    /**
     * Apply default values matching the specification
     */
    public void applyDefaults() {
        // Admin
        this.adminPin = DEFAULT_ADMIN_PIN;

        // Black belt timers: Degree 1-7
        this.blackBeltTimersMs = new HashMap<>();
        blackBeltTimersMs.put(1, 60_000L);
        blackBeltTimersMs.put(2, 55_000L);
        blackBeltTimersMs.put(3, 50_000L);
        blackBeltTimersMs.put(4, 45_000L);
        blackBeltTimersMs.put(5, 40_000L);
        blackBeltTimersMs.put(6, 35_000L);
        blackBeltTimersMs.put(7, 60_000L);

        // Lightning mode (Game Mode 1)
        this.lightningTargetCorrect = 5;
        this.lightningFastThresholdMs = 2_000L;

        // Surf mode (Game Mode 2)
        this.surfQuestionsPerQuiz = 5;
        this.surfQuizzesRequired = 5;

        // Rocket mode (Game Mode 3)
        this.rocketQuestionsPerQuiz = 5;
        this.rocketQuizzesRequired = 5;

        // Bonus mode (Game Mode 4)
        this.bonusTargetCorrect = 20;
        this.bonusVideoIntervalCorrect = 5;
        this.bonusQuestionsPerBatch = 20;

        // Pretest mode
        this.pretestQuestionCount = 20;
        this.pretestTimeLimitMs = 50_000L;  // 50 seconds (default for all levels)
        this.pretestTimeLimitsPerLevelMs = new HashMap<>();  // Empty = use default for all levels

        // Operations
        if (operations == null) {
            operations = new LinkedHashMap<>();
            OperationConfig addConfig = new OperationConfig();
            addConfig.setMaxLevel(19);
            addConfig.setPrerequisite(null);
            addConfig.setUnlockedByDefault(true);
            operations.put(Operation.ADD.value(), addConfig);

            OperationConfig subConfig = new OperationConfig();
            subConfig.setMaxLevel(11);
            subConfig.setPrerequisite(Operation.ADD.value());
            subConfig.setUnlockedByDefault(false);
            operations.put(Operation.SUB.value(), subConfig);

            OperationConfig mulConfig = new OperationConfig();
            mulConfig.setMaxLevel(10);
            mulConfig.setPrerequisite(Operation.SUB.value());
            mulConfig.setUnlockedByDefault(false);
            operations.put(Operation.MUL.value(), mulConfig);

            OperationConfig divConfig = new OperationConfig();
            divConfig.setMaxLevel(13);
            divConfig.setPrerequisite(Operation.MUL.value());
            divConfig.setUnlockedByDefault(false);
            operations.put(Operation.DIV.value(), divConfig);
        }

        // General
        this.inactivityThresholdMs = 60_000L;
        this.pretestInactivityThresholdMs = 3_000L;

        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    // ===== GETTERS & SETTERS =====

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getAdminPin() {
        // Fallback to default if not set
        return adminPin != null && !adminPin.isBlank() ? adminPin : DEFAULT_ADMIN_PIN;
    }

    public void setAdminPin(String adminPin) {
        this.adminPin = adminPin;
    }

    public Map<Integer, Long> getBlackBeltTimersMs() {
        return blackBeltTimersMs;
    }

    public void setBlackBeltTimersMs(Map<Integer, Long> blackBeltTimersMs) {
        this.blackBeltTimersMs = blackBeltTimersMs;
    }

    public long getBlackBeltTimerMs(int degree) {
        if (blackBeltTimersMs == null) {
            return 60_000L;
        }
        return blackBeltTimersMs.getOrDefault(degree, 60_000L);
    }

    public int getLightningTargetCorrect() {
        return lightningTargetCorrect;
    }

    public void setLightningTargetCorrect(int lightningTargetCorrect) {
        this.lightningTargetCorrect = lightningTargetCorrect;
    }

    public long getLightningFastThresholdMs() {
        return lightningFastThresholdMs;
    }

    public void setLightningFastThresholdMs(long lightningFastThresholdMs) {
        this.lightningFastThresholdMs = lightningFastThresholdMs;
    }

    public int getSurfQuestionsPerQuiz() {
        return surfQuestionsPerQuiz;
    }

    public void setSurfQuestionsPerQuiz(int surfQuestionsPerQuiz) {
        this.surfQuestionsPerQuiz = surfQuestionsPerQuiz;
    }

    public int getSurfQuizzesRequired() {
        return surfQuizzesRequired;
    }

    public void setSurfQuizzesRequired(int surfQuizzesRequired) {
        this.surfQuizzesRequired = surfQuizzesRequired;
    }

    public int getRocketQuestionsPerQuiz() {
        return rocketQuestionsPerQuiz;
    }

    public void setRocketQuestionsPerQuiz(int rocketQuestionsPerQuiz) {
        this.rocketQuestionsPerQuiz = rocketQuestionsPerQuiz;
    }

    public int getRocketQuizzesRequired() {
        return rocketQuizzesRequired;
    }

    public void setRocketQuizzesRequired(int rocketQuizzesRequired) {
        this.rocketQuizzesRequired = rocketQuizzesRequired;
    }

    public int getBonusTargetCorrect() {
        return bonusTargetCorrect > 0 ? bonusTargetCorrect : 20;
    }

    public void setBonusTargetCorrect(int bonusTargetCorrect) {
        this.bonusTargetCorrect = bonusTargetCorrect;
    }

    public int getBonusVideoIntervalCorrect() {
        return bonusVideoIntervalCorrect > 0 ? bonusVideoIntervalCorrect : 4;
    }

    public void setBonusVideoIntervalCorrect(int bonusVideoIntervalCorrect) {
        this.bonusVideoIntervalCorrect = bonusVideoIntervalCorrect;
    }

    public int getBonusQuestionsPerBatch() {
        return bonusQuestionsPerBatch > 0 ? bonusQuestionsPerBatch : 20;
    }

    public void setBonusQuestionsPerBatch(int bonusQuestionsPerBatch) {
        this.bonusQuestionsPerBatch = bonusQuestionsPerBatch;
    }

    public int getPretestQuestionCount() {
        return pretestQuestionCount > 0 ? pretestQuestionCount : 20;
    }

    public void setPretestQuestionCount(int pretestQuestionCount) {
        this.pretestQuestionCount = pretestQuestionCount;
    }

    public long getPretestTimeLimitMs() {
        return pretestTimeLimitMs > 0 ? pretestTimeLimitMs : 50_000L;
    }

    /**
     * Get pretest time limit for a specific level.
     * Falls back to the default pretestTimeLimitMs if no level-specific value is set.
     * @param level The level (1-19)
     * @return Time limit in milliseconds
     */
    public long getPretestTimeLimitMs(int level) {
        if (pretestTimeLimitsPerLevelMs != null && pretestTimeLimitsPerLevelMs.containsKey(level)) {
            return pretestTimeLimitsPerLevelMs.get(level);
        }
        return getPretestTimeLimitMs();  // Fall back to default
    }

    public void setPretestTimeLimitMs(long pretestTimeLimitMs) {
        this.pretestTimeLimitMs = pretestTimeLimitMs;
    }

    public Map<Integer, Long> getPretestTimeLimitsPerLevelMs() {
        return pretestTimeLimitsPerLevelMs;
    }

    public void setPretestTimeLimitsPerLevelMs(Map<Integer, Long> pretestTimeLimitsPerLevelMs) {
        this.pretestTimeLimitsPerLevelMs = pretestTimeLimitsPerLevelMs;
    }

    public long getInactivityThresholdMs() {
        return inactivityThresholdMs;
    }

    public void setInactivityThresholdMs(long inactivityThresholdMs) {
        this.inactivityThresholdMs = inactivityThresholdMs;
    }

    public long getPretestInactivityThresholdMs() {
        return pretestInactivityThresholdMs > 0 ? pretestInactivityThresholdMs : 3_000L;
    }

    public void setPretestInactivityThresholdMs(long pretestInactivityThresholdMs) {
        this.pretestInactivityThresholdMs = pretestInactivityThresholdMs;
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

    public String getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(String updatedBy) {
        this.updatedBy = updatedBy;
    }

    public Integer getMulCatalogVersion() {
        return mulCatalogVersion;
    }

    public void setMulCatalogVersion(Integer mulCatalogVersion) {
        this.mulCatalogVersion = mulCatalogVersion;
    }

    public Map<String, OperationConfig> getOperations() {
        return operations;
    }

    public void setOperations(Map<String, OperationConfig> operations) {
        this.operations = operations;
    }

    // ===== INNER CLASSES =====

    public static class OperationConfig {
        private int maxLevel;
        private String prerequisite;
        private boolean unlockedByDefault;

        public int getMaxLevel() {
            return maxLevel;
        }

        public void setMaxLevel(int maxLevel) {
            this.maxLevel = maxLevel;
        }

        public String getPrerequisite() {
            return prerequisite;
        }

        public void setPrerequisite(String prerequisite) {
            this.prerequisite = prerequisite;
        }

        public boolean isUnlockedByDefault() {
            return unlockedByDefault;
        }

        public void setUnlockedByDefault(boolean unlockedByDefault) {
            this.unlockedByDefault = unlockedByDefault;
        }
    }
}