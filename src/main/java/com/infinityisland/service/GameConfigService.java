package com.infinityisland.service;

import com.infinityisland.dao.GameConfig;
import com.infinityisland.model.Operation;
import com.infinityisland.repositories.GameConfigRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Dynamic Game Configuration Service.
 *
 * Provides real-time access to game parameters without restart.
 * Uses an in-memory cache with write-through updates.
 */
@Service
public class GameConfigService {

    private static final Logger log = LoggerFactory.getLogger(GameConfigService.class);

    private static final String CONFIG_ID = "default";

    private final GameConfigRepository configRepo;
    private final long answerInactivityGraceMs;

    // In-memory cache for fast reads
    private final AtomicReference<GameConfig> cachedConfig = new AtomicReference<>();

    public GameConfigService(GameConfigRepository configRepo,
                             @Value("${app.answer-inactivity-grace-ms:500}") long answerInactivityGraceMs) {
        this.configRepo = configRepo;
        this.answerInactivityGraceMs = Math.max(0L, answerInactivityGraceMs);
    }

    @PostConstruct
    public void init() {
        loadOrCreateConfig();
        GameConfig config = cachedConfig.get();
        log.info("[CONFIG] Game configuration loaded:");
        log.info(" - Admin PIN: {}", maskPin(config.getAdminPin()));
        log.info(" - Lightning target: {}", config.getLightningTargetCorrect());
        log.info(" - Lightning fast threshold: {}ms", config.getLightningFastThresholdMs());
        log.info(" - Surf questions/quiz: {}", config.getSurfQuestionsPerQuiz());
        log.info(" - Surf quizzes required: {}", config.getSurfQuizzesRequired());
        log.info(" - Rocket questions/quiz: {}", config.getRocketQuestionsPerQuiz());
        log.info(" - Rocket quizzes required: {}", config.getRocketQuizzesRequired());
        log.info(" - Bonus target correct: {}", config.getBonusTargetCorrect());
        log.info(" - Bonus video interval: {}", config.getBonusVideoIntervalCorrect());
        log.info(" - Bonus questions/batch: {}", config.getBonusQuestionsPerBatch());
        log.info(" - Pretest questions: {}", config.getPretestQuestionCount());
        log.info(" - Pretest time limit: {}ms", config.getPretestTimeLimitMs());
        log.info(" - Inactivity threshold: {}ms", config.getInactivityThresholdMs());
        log.info(" - Answer inactivity grace: {}ms", answerInactivityGraceMs);
    }

    private String maskPin(String pin) {
        if (pin == null || pin.length() < 2) return "****";
        return pin.substring(0, 2) + "**";
    }

    /**
     * Load config from DB or create with defaults
     */
    private void loadOrCreateConfig() {
        GameConfig config = configRepo.findById(CONFIG_ID).orElse(null);

        if (config == null) {
            config = new GameConfig();
            config.setId(CONFIG_ID);
            config = configRepo.save(config);
            log.info("[CONFIG] Created default configuration");
        }

        // Ensure adminPin has a value (migration for existing configs)
        if (config.getAdminPin() == null || config.getAdminPin().isBlank()) {
            config.setAdminPin(GameConfig.DEFAULT_ADMIN_PIN);
            config = configRepo.save(config);
            log.info("[CONFIG] Migrated: set default admin PIN");
        }

        // Reconcile operation configs: merge code defaults for any new operations
        config = reconcileOperationConfigs(config);

        cachedConfig.set(config);
    }

    /**
     * Reconcile operation configs: if code defaults define an operation that is missing
     * from the DB config, add it. Does NOT overwrite existing DB values (respects admin overrides).
     */
    private GameConfig reconcileOperationConfigs(GameConfig config) {
        // Build the code-default operation map by creating a fresh GameConfig
        GameConfig defaults = new GameConfig();
        Map<String, GameConfig.OperationConfig> defaultOps = defaults.getOperations();
        if (defaultOps == null || defaultOps.isEmpty()) return config;

        Map<String, GameConfig.OperationConfig> currentOps = config.getOperations();
        if (currentOps == null) {
            currentOps = new LinkedHashMap<>();
            config.setOperations(currentOps);
        }

        boolean updated = false;
        for (Map.Entry<String, GameConfig.OperationConfig> entry : defaultOps.entrySet()) {
            String op = entry.getKey();
            if (!currentOps.containsKey(op)) {
                currentOps.put(op, entry.getValue());
                log.info("[CONFIG] Reconciled: added missing operation '{}' (maxLevel={})", op, entry.getValue().getMaxLevel());
                updated = true;
            } else {
                // If the operation exists but has maxLevel=0 and code default says otherwise, update it
                GameConfig.OperationConfig existing = currentOps.get(op);
                GameConfig.OperationConfig defaultCfg = entry.getValue();
                if (existing.getMaxLevel() == 0 && defaultCfg.getMaxLevel() > 0) {
                    existing.setMaxLevel(defaultCfg.getMaxLevel());
                    if (existing.getPrerequisite() == null && defaultCfg.getPrerequisite() != null) {
                        existing.setPrerequisite(defaultCfg.getPrerequisite());
                    }
                    log.info("[CONFIG] Reconciled: updated '{}' maxLevel from 0 to {}", op, defaultCfg.getMaxLevel());
                    updated = true;
                } else if (Operation.DIV.value().equals(op)
                        && existing.getMaxLevel() > 0
                        && existing.getMaxLevel() < defaultCfg.getMaxLevel()) {
                    int oldMaxLevel = existing.getMaxLevel();
                    existing.setMaxLevel(defaultCfg.getMaxLevel());
                    if (existing.getPrerequisite() == null && defaultCfg.getPrerequisite() != null) {
                        existing.setPrerequisite(defaultCfg.getPrerequisite());
                    }
                    log.info("[CONFIG] Reconciled: raised '{}' maxLevel from {} to {}", op, oldMaxLevel, defaultCfg.getMaxLevel());
                    updated = true;
                }
            }
        }

        if (updated) {
            config = configRepo.save(config);
        }
        return config;
    }

    // ===== ADMIN PIN METHODS =====

    /**
     * Get the current admin PIN
     */
    public String getAdminPin() {
        return cachedConfig.get().getAdminPin();
    }

    /**
     * Validate if provided PIN matches admin PIN
     */
    public boolean isValidAdminPin(String pin) {
        if (pin == null || pin.isBlank()) {
            return false;
        }
        return pin.equals(cachedConfig.get().getAdminPin());
    }

    /**
     * Update admin PIN (requires current PIN for security)
     */
    public GameConfig updateAdminPin(String currentPin, String newPin, String updatedBy) {
        // Verify current PIN
        if (!isValidAdminPin(currentPin)) {
            throw new SecurityException("Current admin PIN is incorrect");
        }

        // Validate new PIN
        if (newPin == null || newPin.isBlank()) {
            throw new IllegalArgumentException("New PIN cannot be empty");
        }
        if (newPin.length() < 4) {
            throw new IllegalArgumentException("PIN must be at least 4 characters");
        }
        if (newPin.equals(currentPin)) {
            throw new IllegalArgumentException("New PIN must be different from current PIN");
        }

        GameConfig current = cachedConfig.get();
        current.setAdminPin(newPin);
        current.setUpdatedAt(Instant.now());
        current.setUpdatedBy(updatedBy);

        GameConfig saved = configRepo.save(current);
        cachedConfig.set(saved);

        log.info("[CONFIG] Admin PIN updated by {}", updatedBy);
        return saved;
    }

    // ===== READ METHODS (from cache) =====

    /**
     * Get the full configuration object
     */
    public GameConfig getConfig() {
        return cachedConfig.get();
    }

    /**
     * Get black belt timer for a specific degree
     */
    public long getBlackBeltTimerMs(int degree) {
        return cachedConfig.get().getBlackBeltTimerMs(degree);
    }

    /**
     * Get all black belt timers
     */
    public Map<Integer, Long> getBlackBeltTimers() {
        return cachedConfig.get().getBlackBeltTimersMs();
    }

    /**
     * Get lightning mode target correct answers
     */
    public int getLightningTargetCorrect() {
        return cachedConfig.get().getLightningTargetCorrect();
    }

    /**
     * Get lightning mode fast threshold in milliseconds
     */
    public long getLightningFastThresholdMs() {
        return cachedConfig.get().getLightningFastThresholdMs();
    }

    /**
     * Get surf mode questions per quiz
     */
    public int getSurfQuestionsPerQuiz() {
        return cachedConfig.get().getSurfQuestionsPerQuiz();
    }

    /**
     * Get surf mode quizzes required to pass
     */
    public int getSurfQuizzesRequired() {
        return cachedConfig.get().getSurfQuizzesRequired();
    }

    /**
     * Get rocket mode questions per quiz
     */
    public int getRocketQuestionsPerQuiz() {
        return cachedConfig.get().getRocketQuestionsPerQuiz();
    }

    /**
     * Get rocket mode quizzes required to pass
     */
    public int getRocketQuizzesRequired() {
        return cachedConfig.get().getRocketQuizzesRequired();
    }

    /**
     * Get bonus mode target consecutive correct (default 20)
     */
    public int getBonusTargetCorrect() {
        return cachedConfig.get().getBonusTargetCorrect();
    }

    /**
     * Get bonus mode video interval — emit showBonusVideo every N consecutive correct (default 4)
     */
    public int getBonusVideoIntervalCorrect() {
        return cachedConfig.get().getBonusVideoIntervalCorrect();
    }

    /**
     * Get bonus mode questions per generated batch (default 20)
     */
    public int getBonusQuestionsPerBatch() {
        return cachedConfig.get().getBonusQuestionsPerBatch();
    }

    /**
     * Get inactivity threshold in milliseconds
     */
    public long getInactivityThresholdMs() {
        return cachedConfig.get().getInactivityThresholdMs();
    }

    public long getPretestInactivityThresholdMs() {
        return cachedConfig.get().getPretestInactivityThresholdMs();
    }

    public long getAnswerInactivityGraceMs() {
        return answerInactivityGraceMs;
    }

    public boolean isAnswerInactive(long responseMs, long inactivityThresholdMs) {
        return responseMs > inactivityThresholdMs + answerInactivityGraceMs;
    }

    /**
     * Get pretest question count
     */
    public int getPretestQuestionCount() {
        return cachedConfig.get().getPretestQuestionCount();
    }

    /**
     * Get default pretest time limit in milliseconds
     */
    public long getPretestTimeLimitMs() {
        return cachedConfig.get().getPretestTimeLimitMs();
    }

    /**
     * Get pretest time limit for a specific level.
     * Falls back to default if no level-specific config exists.
     * @param level The level (1-19)
     * @return Time limit in milliseconds
     */
    public long getPretestTimeLimitMs(int level) {
        return cachedConfig.get().getPretestTimeLimitMs(level);
    }

    /**
     * Get all per-level pretest time limits
     */
    public Map<Integer, Long> getPretestTimeLimitsPerLevel() {
        return cachedConfig.get().getPretestTimeLimitsPerLevelMs();
    }

    // ===== OPERATION CONFIG METHODS =====

    public GameConfig.OperationConfig getOperationConfig(String operation) {
        GameConfig config = getConfig();
        if (config.getOperations() == null) return null;
        return config.getOperations().get(operation != null ? operation.toLowerCase() : Operation.ADD.value());
    }

    public int getMaxLevel(String operation) {
        GameConfig.OperationConfig opConfig = getOperationConfig(operation);
        if (opConfig == null) {
            if (Operation.ADD.value().equalsIgnoreCase(operation)) return 19;
            if (Operation.SUB.value().equalsIgnoreCase(operation)) return 11;
            if (Operation.MUL.value().equalsIgnoreCase(operation)) return 10;
            if (Operation.DIV.value().equalsIgnoreCase(operation)) return 13;
            return 0;
        }
        return opConfig.getMaxLevel();
    }

    public String getOperationPrerequisite(String operation) {
        GameConfig.OperationConfig opConfig = getOperationConfig(operation);
        return opConfig != null ? opConfig.getPrerequisite() : null;
    }

    public boolean isOperationEnabled(String operation) {
        return getMaxLevel(operation) > 0;
    }

    public Map<String, GameConfig.OperationConfig> getAllOperationConfigs() {
        GameConfig config = getConfig();
        return config.getOperations() != null ? config.getOperations() : Map.of();
    }

    // ===== WRITE METHODS (write-through) =====

    /**
     * Update the full configuration (excluding adminPin - use updateAdminPin for that)
     */
    public GameConfig updateConfig(GameConfig updates, String updatedBy) {
        GameConfig current = configRepo.findById(CONFIG_ID)
                .orElseThrow(() -> new IllegalStateException("Config not found"));

        // Apply updates (only non-null/positive fields)
        // NOTE: adminPin is NOT updated here - use updateAdminPin()
        if (updates.getBlackBeltTimersMs() != null && !updates.getBlackBeltTimersMs().isEmpty()) {
            current.getBlackBeltTimersMs().putAll(updates.getBlackBeltTimersMs());
        }
        if (updates.getLightningTargetCorrect() > 0) {
            current.setLightningTargetCorrect(updates.getLightningTargetCorrect());
        }
        if (updates.getLightningFastThresholdMs() > 0) {
            current.setLightningFastThresholdMs(updates.getLightningFastThresholdMs());
        }
        if (updates.getSurfQuestionsPerQuiz() > 0) {
            current.setSurfQuestionsPerQuiz(updates.getSurfQuestionsPerQuiz());
        }
        if (updates.getSurfQuizzesRequired() > 0) {
            current.setSurfQuizzesRequired(updates.getSurfQuizzesRequired());
        }
        if (updates.getRocketQuestionsPerQuiz() > 0) {
            current.setRocketQuestionsPerQuiz(updates.getRocketQuestionsPerQuiz());
        }
        if (updates.getRocketQuizzesRequired() > 0) {
            current.setRocketQuizzesRequired(updates.getRocketQuizzesRequired());
        }
        if (updates.getBonusTargetCorrect() > 0) {
            current.setBonusTargetCorrect(updates.getBonusTargetCorrect());
        }
        if (updates.getBonusVideoIntervalCorrect() > 0) {
            current.setBonusVideoIntervalCorrect(updates.getBonusVideoIntervalCorrect());
        }
        if (updates.getBonusQuestionsPerBatch() > 0) {
            current.setBonusQuestionsPerBatch(updates.getBonusQuestionsPerBatch());
        }
        if (updates.getInactivityThresholdMs() > 0) {
            current.setInactivityThresholdMs(updates.getInactivityThresholdMs());
        }
        if (updates.getPretestInactivityThresholdMs() > 0) {
            current.setPretestInactivityThresholdMs(updates.getPretestInactivityThresholdMs());
        }
        if (updates.getPretestQuestionCount() > 0) {
            current.setPretestQuestionCount(updates.getPretestQuestionCount());
        }
        if (updates.getPretestTimeLimitMs() > 0) {
            current.setPretestTimeLimitMs(updates.getPretestTimeLimitMs());
        }
        if (updates.getPretestTimeLimitsPerLevelMs() != null && !updates.getPretestTimeLimitsPerLevelMs().isEmpty()) {
            if (current.getPretestTimeLimitsPerLevelMs() == null) {
                current.setPretestTimeLimitsPerLevelMs(new java.util.HashMap<>());
            }
            // Handle special values: 0 or negative means remove the level-specific override
            updates.getPretestTimeLimitsPerLevelMs().forEach((level, ms) -> {
                if (ms <= 0) {
                    current.getPretestTimeLimitsPerLevelMs().remove(level);
                } else {
                    current.getPretestTimeLimitsPerLevelMs().put(level, ms);
                }
            });
        }

        current.setUpdatedAt(Instant.now());
        current.setUpdatedBy(updatedBy);

        GameConfig saved = configRepo.save(current);
        cachedConfig.set(saved);

        return saved;
    }

    /**
     * Update a single black belt timer
     */
    public GameConfig updateBlackBeltTimer(int degree, long timerMs, String updatedBy) {
        if (degree < 1 || degree > 7) {
            throw new IllegalArgumentException("Degree must be between 1 and 7");
        }
        if (timerMs < 1000) {
            throw new IllegalArgumentException("Timer must be at least 1000ms");
        }

        GameConfig current = cachedConfig.get();
        current.getBlackBeltTimersMs().put(degree, timerMs);
        current.setUpdatedAt(Instant.now());
        current.setUpdatedBy(updatedBy);

        GameConfig saved = configRepo.save(current);
        cachedConfig.set(saved);

        log.info("[CONFIG] Black belt degree {} timer updated to {}ms", degree, timerMs);
        return saved;
    }

    /**
     * Reset configuration to defaults (including admin PIN!)
     */
    public GameConfig resetToDefaults(String updatedBy) {
        GameConfig config = new GameConfig();
        config.setId(CONFIG_ID);
        config.applyDefaults();
        config.setUpdatedBy(updatedBy);

        GameConfig saved = configRepo.save(config);
        cachedConfig.set(saved);

        log.info("[CONFIG] Configuration reset to defaults by {}", updatedBy);
        return saved;
    }

    /**
     * Force reload from database (useful for multi-instance sync)
     */
    public GameConfig reloadFromDatabase() {
        GameConfig config = configRepo.findById(CONFIG_ID).orElse(null);
        if (config != null) {
            cachedConfig.set(config);
            log.info("[CONFIG] Reloaded from database");
        }
        return cachedConfig.get();
    }
}