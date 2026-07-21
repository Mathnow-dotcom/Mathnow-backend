package com.infinityisland.service;

import com.infinityisland.dao.VideoRating;
import com.infinityisland.dao.user.User;
import com.infinityisland.repositories.*;
import com.infinityisland.dao.GameConfig;
import com.infinityisland.model.Belt;
import com.infinityisland.model.Operation;
import com.infinityisland.controller.QuizResponses.DailyStatsResponse;
import com.infinityisland.controller.QuizResponses.ProgressResponse;
import com.infinityisland.util.ProgressDefaults;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final CachedUserService cachedUsers;
    private final DailyService dailyService;  // Use DailyService instead of CachedDailySummaryService
    private final VideoRatingRepository ratings;
    private final EmailService mailer;
    private final CachedQuizRunService cachedQuizRuns;
    private final AttemptRepository attemptRepo;
    private final GameConfigService gameConfig;


    public UserService(CachedUserService cachedUsers,
                       DailyService dailyService,
                       VideoRatingRepository ratings,
                       EmailService mailer,
                       CachedQuizRunService cachedQuizRuns,
                       AttemptRepository attemptRepo,
                       GameConfigService gameConfig) {
        this.cachedUsers = cachedUsers;
        this.dailyService = dailyService;
        this.ratings = ratings;
        this.mailer = mailer;
        this.cachedQuizRuns = cachedQuizRuns;
        this.attemptRepo = attemptRepo;
        this.gameConfig = gameConfig;
    }

    public User createUser(String name, String pin) {
        User existing = cachedUsers.findByPin(pin).orElse(null);
        if (existing != null) {
            if (name != null && !name.isBlank()) {
                existing.setName(name);
                existing.setDisplayName(name);
                cachedUsers.save(existing);
            }
            return existing;
        }

        User u = new User();
        u.setPin(pin);
        if (name != null && !name.isBlank()) {
            u.setName(name);
            u.setDisplayName(name);
        } else {
            u.setDisplayName("PIN " + pin);
        }

        u.setProgress(baselineProgress());
        return cachedUsers.save(u);
    }

    private Map<String, Object> baselineProgress() {
        return ProgressDefaults.baselineProgress();
    }

    private Map<String, Object> createLevelNode(int level, boolean unlocked) {
        return ProgressDefaults.createLevelNode(level, unlocked);
    }

    private Map<String, Object> belt(boolean completed, boolean unlocked) {
        return ProgressDefaults.belt(completed, unlocked);
    }

    /**
     * GET /user/daily - Uses DailyService for consistent cache reads
     */
    public DailyStatsResponse getDaily(String pin) {
        User u = ensureUserByPin(pin);
        return dailyService.getForUser(u.getId());
    }

    public ProgressResponse getProgress(String pin) {
        User u = ensureUserByPin(pin);

        Map<String, Object> p = u.getProgress();
        if (p == null || p.isEmpty()) {
            p = baselineProgress();
            u.setProgress(p);
            cachedUsers.save(u);
        } else {
            boolean updated = false;
            // Migrate old format if needed
            if (isOldFormat(p)) {
                p = migrateToNewFormat(p);
                u.setProgress(p);
                updated = true;
            }
            if (ensurePretestDataExists(p)) {
                u.setProgress(p);
                updated = true;
            }
            if (pruneExcessLevels(p)) {
                u.setProgress(p);
                updated = true;
            }
            if (reconcileOperationUnlocks(p)) {
                u.setProgress(p);
                updated = true;
            }
            if (reconcileCompletedLevelUnlocks(p)) {
                u.setProgress(p);
                updated = true;
            }
            if (updated) {
                cachedUsers.save(u);
            }
        }

        ProgressResponse resp = new ProgressResponse();
        resp.progress = p;
        return resp;
    }

    /**
     * Ensure pretest data exists for all levels in progress.
     * For backwards compatibility with existing users.
     */
    @SuppressWarnings("unchecked")
    private boolean ensurePretestDataExists(Map<String, Object> progress) {
        boolean updated = false;
        for (Map.Entry<String, Object> opEntry : progress.entrySet()) {
            if (!(opEntry.getValue() instanceof Map)) continue;
            Map<String, Object> opNode = (Map<String, Object>) opEntry.getValue();
            for (Map.Entry<String, Object> levelEntry : opNode.entrySet()) {
                if (!levelEntry.getKey().startsWith("L")) continue;
                if (!(levelEntry.getValue() instanceof Map)) continue;
                Map<String, Object> levelNode = (Map<String, Object>) levelEntry.getValue();
                if (!levelNode.containsKey("pretest")) {
                    Map<String, Object> pretest = new HashMap<>();
                    pretest.put("taken", false);
                    pretest.put("passed", false);
                    levelNode.put("pretest", pretest);
                    updated = true;
                }
            }
        }
        return updated;
    }

    /**
     * Remove any level keys that exceed the configured maxLevel for each operation.
     * Fixes stale data from before maxLevel guards were added (e.g. add.L20).
     */
    @SuppressWarnings("unchecked")
    private boolean pruneExcessLevels(Map<String, Object> progress) {
        boolean updated = false;
        for (Map.Entry<String, Object> opEntry : progress.entrySet()) {
            String op = opEntry.getKey();
            if (!(opEntry.getValue() instanceof Map)) continue;
            int maxLevel = gameConfig.getMaxLevel(op);
            if (maxLevel <= 0) continue;
            Map<String, Object> opNode = (Map<String, Object>) opEntry.getValue();
            Iterator<String> it = opNode.keySet().iterator();
            while (it.hasNext()) {
                String key = it.next();
                if (!key.startsWith("L")) continue;
                try {
                    int lvl = Integer.parseInt(key.substring(1));
                    if (lvl > maxLevel) {
                        it.remove();
                        updated = true;
                        log.info("[PRUNE] Removed excess level {} from operation {}", key, op);
                    }
                } catch (NumberFormatException ignored) {}
            }
        }
        return updated;
    }

    /**
     * If maxLevel expands after users have already completed the previous last level,
     * create and unlock the next level so they can continue without admin repair.
     */
    @SuppressWarnings("unchecked")
    private boolean reconcileCompletedLevelUnlocks(Map<String, Object> progress) {
        boolean updated = false;
        for (Map.Entry<String, GameConfig.OperationConfig> entry : gameConfig.getAllOperationConfigs().entrySet()) {
            String op = entry.getKey().toLowerCase(Locale.ROOT);
            int maxLevel = gameConfig.getMaxLevel(op);
            if (maxLevel <= 1) continue;

            Object opObj = progress.get(op);
            if (!(opObj instanceof Map)) continue;
            Map<String, Object> opNode = (Map<String, Object>) opObj;

            for (int lvl = 1; lvl < maxLevel; lvl++) {
                Object levelObj = opNode.get("L" + lvl);
                if (!(levelObj instanceof Map)) continue;
                Map<String, Object> levelNode = (Map<String, Object>) levelObj;
                if (!Boolean.TRUE.equals(levelNode.get("completed"))) continue;

                String nextKey = "L" + (lvl + 1);
                Map<String, Object> nextLevelNode;
                Object nextObj = opNode.get(nextKey);
                if (nextObj instanceof Map) {
                    nextLevelNode = (Map<String, Object>) nextObj;
                } else {
                    nextLevelNode = createLevelNode(lvl + 1, true);
                    opNode.put(nextKey, nextLevelNode);
                    updated = true;
                }

                if (!Boolean.TRUE.equals(nextLevelNode.get("unlocked"))) {
                    nextLevelNode.put("unlocked", true);
                    updated = true;
                }
                Object whiteObj = nextLevelNode.get(Belt.WHITE.value());
                if (whiteObj instanceof Map) {
                    Map<String, Object> white = (Map<String, Object>) whiteObj;
                    if (!Boolean.TRUE.equals(white.get("unlocked"))) {
                        white.put("unlocked", true);
                        updated = true;
                    }
                }
            }
        }
        return updated;
    }

    /**
     * Reconcile operation unlock status: if a prerequisite operation is completed,
     * ensure the dependent operation's L1 is unlocked in progress.
     * Fixes users who completed addition before the unlock propagation was added.
     */
    @SuppressWarnings("unchecked")
    private boolean reconcileOperationUnlocks(Map<String, Object> progress) {
        boolean updated = false;
        for (Map.Entry<String, GameConfig.OperationConfig> entry : gameConfig.getAllOperationConfigs().entrySet()) {
            String op = entry.getKey().toLowerCase(Locale.ROOT);
            String prereq = entry.getValue().getPrerequisite();
            if (prereq == null) continue;

            // Skip disabled operations (maxLevel=0)
            int opMaxLevel = gameConfig.getMaxLevel(op);
            if (opMaxLevel <= 0) continue;

            // Check if prerequisite operation's last level is completed
            Map<String, Object> prereqNode = (Map<String, Object>) progress.get(prereq.toLowerCase(Locale.ROOT));
            if (prereqNode == null) continue;
            int maxLevel = gameConfig.getMaxLevel(prereq);
            if (maxLevel <= 0) continue;
            Map<String, Object> lastLevel = (Map<String, Object>) prereqNode.get("L" + maxLevel);
            if (lastLevel == null || !Boolean.TRUE.equals(lastLevel.get("completed"))) continue;

            // Prerequisite is complete — ensure dependent op L1 is unlocked
            Map<String, Object> opNode = (Map<String, Object>) progress.get(op);
            if (opNode == null) {
                opNode = new LinkedHashMap<>();
                progress.put(op, opNode);
            }
            Map<String, Object> l1 = (Map<String, Object>) opNode.get("L1");
            if (l1 == null) {
                l1 = createLevelNode(1, true);
                opNode.put("L1", l1);
                updated = true;
            } else if (!Boolean.TRUE.equals(l1.get("unlocked"))) {
                l1.put("unlocked", true);
                Map<String, Object> white = (Map<String, Object>) l1.get("white");
                if (white != null) white.put("unlocked", true);
                updated = true;
            }
        }
        return updated;
    }

    private boolean isOldFormat(Map<String, Object> progress) {
        return progress.keySet().stream().anyMatch(k -> k.startsWith("L"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> migrateToNewFormat(Map<String, Object> oldProgress) {
        Map<String, Object> addNode = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : oldProgress.entrySet()) {
            if (entry.getKey().startsWith("L") && entry.getValue() instanceof Map) {
                Map<String, Object> levelData = (Map<String, Object>) entry.getValue();
                // Convert old pretest format
                migratePretestInLevel(levelData);
                addNode.put(entry.getKey(), levelData);
            }
        }

        Map<String, Object> migrated = new LinkedHashMap<>();
        migrated.put(Operation.ADD.value(), addNode);

        // Check if addition is fully completed — if so, unlock sub L1
        boolean addCompleted = isAdditionCompleted(migrated);
        Map<String, Object> subNode = new LinkedHashMap<>();
        subNode.put("L1", createLevelNode(1, addCompleted));
        migrated.put(Operation.SUB.value(), subNode);

        // Multiplication: L1 locked (unlocked after completing all subtraction levels)
        Map<String, Object> mulNode = new LinkedHashMap<>();
        mulNode.put("L1", createLevelNode(1, false));
        migrated.put(Operation.MUL.value(), mulNode);

        return migrated;
    }

    @SuppressWarnings("unchecked")
    private boolean isAdditionCompleted(Map<String, Object> progress) {
        Map<String, Object> addNode = (Map<String, Object>) progress.get(Operation.ADD.value());
        if (addNode == null) return false;
        int maxLevel = gameConfig.getMaxLevel(Operation.ADD.value());
        if (maxLevel <= 0) return false;
        Map<String, Object> lastLevel = (Map<String, Object>) addNode.get("L" + maxLevel);
        return lastLevel != null && Boolean.TRUE.equals(lastLevel.get("completed"));
    }

    @SuppressWarnings("unchecked")
    private void migratePretestInLevel(Map<String, Object> levelData) {
        Object pretestObj = levelData.get("pretest");
        if (pretestObj instanceof Map) {
            Map<String, Object> pretestMap = (Map<String, Object>) pretestObj;
            if (pretestMap.containsKey(Operation.ADD.value()) || pretestMap.containsKey(Operation.SUB.value())) {
                // Old format: extract "add" operation's pretest status
                Map<String, Object> addPretest = (Map<String, Object>) pretestMap.get(Operation.ADD.value());
                if (addPretest != null) {
                    levelData.put("pretest", new HashMap<>(addPretest));
                } else {
                    Map<String, Object> fresh = new HashMap<>();
                    fresh.put("taken", false);
                    fresh.put("passed", false);
                    levelData.put("pretest", fresh);
                }
            }
        }
    }

    /**
     * Reset progress - Uses cached services to ensure cache is cleared
     */
    public Map<String, Object> resetProgress(String pin) {
        User u = ensureUserByPin(pin);
        String userId = u.getId();

        // Use cached service to ensure cache is cleared
        cachedQuizRuns.deleteByUserId(userId);

        attemptRepo.deleteByUserId(userId);

        // Force flush any pending daily stats, then clear
        dailyService.forceFlush(userId);
        dailyService.clearUserCache(userId);

        u.setProgress(baselineProgress());
        u.setGrandTotalCorrect(0L);
        u.setCurrentStreak(0);
        cachedUsers.save(u);

        return Map.of(
                "success", true,
                "message", "Progress reset successfully.",
                "user", u
        );
    }

    /**
     * Restore a user's progress and stats from admin-provided values.
     * Builds completed levels for each operation and unlocks the next level/operation.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> restoreUser(String pin, Map<String, Integer> completedLevelsPerOp,
                                           Long grandTotalCorrect, Integer currentStreak) {
        User u = cachedUsers.findByPin(pin).orElse(null);
        if (u == null) {
            return Map.of("success", false, "error", "User not found for pin: " + pin);
        }

        Map<String, Object> progress = new LinkedHashMap<>();

        // Build each operation's progress
        for (Map.Entry<String, GameConfig.OperationConfig> opEntry : gameConfig.getAllOperationConfigs().entrySet()) {
            String op = opEntry.getKey().toLowerCase(Locale.ROOT);
            int maxLevel = gameConfig.getMaxLevel(op);
            if (maxLevel <= 0) continue;

            int completed = completedLevelsPerOp != null && completedLevelsPerOp.containsKey(op)
                    ? completedLevelsPerOp.get(op) : 0;
            if (completed > maxLevel) completed = maxLevel;

            Map<String, Object> opNode = new LinkedHashMap<>();

            // Create fully completed levels
            for (int lvl = 1; lvl <= completed; lvl++) {
                opNode.put("L" + lvl, createCompletedLevelNode(lvl));
            }

            // Create the next unlocked level (if not at max)
            if (completed < maxLevel) {
                boolean shouldUnlock = false;
                if (completed > 0) {
                    // Has progress in this op — unlock next level
                    shouldUnlock = true;
                } else {
                    // No progress — check if prerequisite is met
                    String prereq = opEntry.getValue().getPrerequisite();
                    if (prereq == null) {
                        shouldUnlock = true; // No prerequisite (e.g. addition)
                    } else {
                        int prereqCompleted = completedLevelsPerOp != null && completedLevelsPerOp.containsKey(prereq.toLowerCase(Locale.ROOT))
                                ? completedLevelsPerOp.get(prereq.toLowerCase(Locale.ROOT)) : 0;
                        int prereqMax = gameConfig.getMaxLevel(prereq);
                        shouldUnlock = prereqCompleted >= prereqMax;
                    }
                }
                int nextLevel = completed + 1;
                opNode.put("L" + nextLevel, createLevelNode(nextLevel, shouldUnlock));
            }

            progress.put(op, opNode);
        }

        u.setProgress(progress);
        if (grandTotalCorrect != null) u.setGrandTotalCorrect(grandTotalCorrect);
        if (currentStreak != null) u.setCurrentStreak(currentStreak);
        cachedUsers.save(u);

        log.info("[RESTORE] Restored user {} (pin={}): {}, grandTotalCorrect={}, currentStreak={}",
                u.getDisplayName(), pin, completedLevelsPerOp, grandTotalCorrect, currentStreak);

        return Map.of(
                "success", true,
                "message", "User restored successfully",
                "user", u.getDisplayName(),
                "progress", progress
        );
    }

    private Map<String, Object> createCompletedLevelNode(int level) {
        Map<String, Object> lvl = new LinkedHashMap<>();
        lvl.put("level", level);
        lvl.put("unlocked", true);
        lvl.put("completed", true);

        lvl.put(Belt.WHITE.value(), belt(true, true));
        lvl.put(Belt.YELLOW.value(), belt(true, true));
        lvl.put(Belt.GREEN.value(), belt(true, true));
        lvl.put(Belt.BLUE.value(), belt(true, true));
        lvl.put(Belt.RED.value(), belt(true, true));
        lvl.put(Belt.BROWN.value(), belt(true, true));

        Map<String, Object> black = new HashMap<>();
        black.put("unlocked", true);
        List<Integer> degrees = new ArrayList<>();
        for (int d = 1; d <= 7; d++) degrees.add(d);
        black.put("completedDegrees", degrees);
        lvl.put("black", black);

        Map<String, Object> pretest = new HashMap<>();
        pretest.put("taken", true);
        pretest.put("passed", true);
        lvl.put("pretest", pretest);

        return lvl;
    }

    public Response setTheme(String pin, String themeKey) {
        User u = ensureUserByPin(pin);

        if (u.getTheme() != null && !u.getTheme().isBlank()) {
            return Response.status(403)
                    .entity(Map.of("error", "Theme selection is locked after first choice."))
                    .build();
        }

        u.setTheme(themeKey);
        cachedUsers.save(u);

        return Response.ok(Map.of("success", true, "theme", themeKey)).build();
    }

    public Response rateVideo(String pin, int rating, int level, String beltOrDegree) {
        User u = ensureUserByPin(pin);

        VideoRating vr = new VideoRating();
        vr.setUserId(u.getId());
        vr.setRating(rating);
        vr.setLevel(level);
        vr.setBeltOrDegree(beltOrDegree);
        vr.setCreatedAt(new Date());
        ratings.save(vr);

        try {
            mailer.sendRatingReport(u, vr);
            return Response.ok(Map.of(
                    "success", true,
                    "message", "Rating received and report sent.",
                    "ratingId", vr.getId()
            )).build();

        } catch (Exception ex) {
            return Response.status(500)
                    .entity(Map.of(
                            "success", false,
                            "message", "Mailer failed: " + ex.getMessage()
                    )).build();
        }
    }

    private User ensureUserByPin(String pin) {
        if (pin == null || pin.isBlank())
            throw new IllegalArgumentException("x-pin required");

        return cachedUsers.findByPin(pin)
                .orElseGet(() -> {
                    User u = new User();
                    u.setPin(pin);
                    u.setDisplayName("PIN " + pin);
                    u.setProgress(baselineProgress());
                    return cachedUsers.save(u);
                });
    }

    public Map<String, Object> deleteUser(String pin) {
        User u = cachedUsers.findByPin(pin).orElse(null);
        if (u == null) {
            return Map.of("success", false, "message", "User not found");
        }

        String userId = u.getId();

        // Clear daily service cache
        dailyService.forceFlush(userId);
        dailyService.clearUserCache(userId);

        // Delete quiz runs with cache eviction
        cachedQuizRuns.deleteByUserId(userId);

        attemptRepo.deleteByUserId(userId);

        // Delete video ratings
        ratings.deleteByUserId(userId);

        // Delete user and evict from caches
        cachedUsers.evict(userId);
        cachedUsers.evictByPin(pin);
        cachedUsers.delete(u);

        return Map.of(
                "success", true,
                "message", "User deleted successfully",
                "deletedPin", pin
        );
    }
}