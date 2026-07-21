package com.infinityisland.service;

import com.infinityisland.dao.GameConfig;
import com.infinityisland.dao.user.User;
import com.infinityisland.model.Belt;
import com.infinityisland.model.Operation;
import com.infinityisland.util.ProgressDefaults;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class ProgressionService {

    private static final Logger log = LoggerFactory.getLogger(ProgressionService.class);

    private final CachedUserService cachedUsers;
    private final GameConfigService gameConfig;

    public ProgressionService(CachedUserService cachedUsers, GameConfigService gameConfig) {
        this.cachedUsers = cachedUsers;
        this.gameConfig = gameConfig;
    }

    public Map<String, Object> getProgress(String userId) {
        if (userId == null) return new HashMap<>();
        var userOpt = cachedUsers.findById(userId);
        if (userOpt.isEmpty()) return new HashMap<>();
        Map<String, Object> progress = userOpt.get().getProgress();
        if (progress == null) return new HashMap<>();
        progress = migrateIfNeeded(progress);
        return progress;
    }

    /**
     * Unlock the next belt/degree on pass. Operation-scoped.
     */
    public Map<String, Object> unlockOnPass(String userId, String op, Integer level, String beltOrDegree) {
        if (userId == null || level == null || beltOrDegree == null || op == null) {
            return Map.of("updated", false, "reason", "invalid-args");
        }

        var userOpt = cachedUsers.findById(userId);
        if (userOpt.isEmpty()) return Map.of("updated", false, "reason", "user-not-found");
        var user = userOpt.get();

        Map<String, Object> progress = ensureMigrated(user);
        Map<String, Object> opNode = ensureOperationNode(progress, op.toLowerCase(Locale.ROOT));
        Map<String, Object> levelNode = ensureLevel(opNode, level);

        if (isBlackDegree(beltOrDegree)) {
            Map<String, Object> blackNode = ensureBlack(levelNode);
            int degree = parseDegree(beltOrDegree);

            @SuppressWarnings("unchecked")
            List<Integer> degrees = (List<Integer>) blackNode.computeIfAbsent("completedDegrees", k -> new ArrayList<Integer>());
            if (!degrees.contains(degree)) {
                degrees.add(degree);
            }

            if (degree >= 7) {
                levelNode.put("completed", true);
                int maxLevel = gameConfig.getMaxLevel(op);
                if (maxLevel > 0) {
                    int nextLevel = level + 1;
                    if (nextLevel <= maxLevel) {
                        Map<String, Object> nextLevelNode = ensureLevel(opNode, nextLevel);
                        nextLevelNode.put("unlocked", true);
                        Map<String, Object> whiteNext = ensureColored(nextLevelNode, Belt.WHITE.value());
                        whiteNext.put("unlocked", true);
                    } else {
                        // Last level of this operation completed — unlock dependent operations
                        unlockDependentOperations(progress, op);
                    }
                }
            }

            user.setProgress(progress);
            cachedUsers.save(user);
            return progress;
        }

        String belt = beltOrDegree.toLowerCase(Locale.ROOT);
        Map<String, Object> thisBelt = ensureColored(levelNode, belt);
        thisBelt.put("completed", true);
        thisBelt.put("unlocked", true);

        String next = nextColoredAfter(belt);

        if (Belt.BROWN.value().equalsIgnoreCase(belt)) {
            Map<String, Object> blackNode = ensureBlack(levelNode);
            blackNode.put("unlocked", true);
        } else if (next != null) {
            Map<String, Object> nextNode = ensureColored(levelNode, next);
            nextNode.put("unlocked", true);
        }

        user.setProgress(progress);
        cachedUsers.save(user);
        return progress;
    }

    /**
     * Check if pretest is required for a given operation and level.
     */
    public boolean isPretestRequired(String userId, String operation, Integer level) {
        if (userId == null || level == null || operation == null) return false;

        var userOpt = cachedUsers.findById(userId);
        if (userOpt.isEmpty()) return true;
        var user = userOpt.get();

        Map<String, Object> progress = user.getProgress();
        if (progress == null) return true;

        progress = migrateIfNeeded(progress);
        String op = operation.toLowerCase(Locale.ROOT);

        @SuppressWarnings("unchecked")
        Map<String, Object> opNode = (Map<String, Object>) progress.get(op);
        if (opNode == null) return true;

        String levelKey = "L" + level;
        @SuppressWarnings("unchecked")
        Map<String, Object> levelNode = (Map<String, Object>) opNode.get(levelKey);
        if (levelNode == null) return true;

        @SuppressWarnings("unchecked")
        Map<String, Object> pretestNode = (Map<String, Object>) levelNode.get("pretest");
        if (pretestNode == null) return true;

        Boolean taken = (Boolean) pretestNode.get("taken");
        return !Boolean.TRUE.equals(taken);
    }

    /**
     * Mark pretest as taken for a given operation and level.
     */
    public Map<String, Object> markPretestTaken(String userId, String operation, Integer level, boolean passed) {
        if (userId == null || level == null || operation == null) {
            return Map.of("updated", false, "reason", "invalid-args");
        }

        var userOpt = cachedUsers.findById(userId);
        if (userOpt.isEmpty()) return Map.of("updated", false, "reason", "user-not-found");
        var user = userOpt.get();

        Map<String, Object> progress = ensureMigrated(user);
        String op = operation.toLowerCase(Locale.ROOT);
        Map<String, Object> opNode = ensureOperationNode(progress, op);
        Map<String, Object> levelNode = ensureLevel(opNode, level);

        Map<String, Object> pretestNode = new HashMap<>();
        pretestNode.put("taken", true);
        pretestNode.put("passed", passed);
        levelNode.put("pretest", pretestNode);

        user.setProgress(progress);
        cachedUsers.save(user);
        return progress;
    }

    /**
     * Award entire level (all belts) for a specific operation. Called when pretest is passed.
     */
    public Map<String, Object> awardEntireLevel(String userId, String operation, Integer level) {
        if (userId == null || level == null || operation == null) {
            return Map.of("updated", false, "reason", "invalid-args");
        }

        var userOpt = cachedUsers.findById(userId);
        if (userOpt.isEmpty()) return Map.of("updated", false, "reason", "user-not-found");
        var user = userOpt.get();

        Map<String, Object> progress = ensureMigrated(user);
        String op = operation.toLowerCase(Locale.ROOT);
        Map<String, Object> opNode = ensureOperationNode(progress, op);
        Map<String, Object> levelNode = ensureLevel(opNode, level);

        List<String> coloredBelts = Belt.COLORED_ORDER;
        for (String belt : coloredBelts) {
            Map<String, Object> beltNode = ensureColored(levelNode, belt);
            beltNode.put("unlocked", true);
            beltNode.put("completed", true);
        }

        Map<String, Object> blackNode = ensureBlack(levelNode);
        blackNode.put("unlocked", true);
        @SuppressWarnings("unchecked")
        List<Integer> degrees = (List<Integer>) blackNode.computeIfAbsent("completedDegrees", k -> new ArrayList<Integer>());
        for (int d = 1; d <= 7; d++) {
            if (!degrees.contains(d)) degrees.add(d);
        }

        levelNode.put("completed", true);

        int maxLevel = gameConfig.getMaxLevel(op);
        if (maxLevel > 0) {
            int nextLevel = level + 1;
            if (nextLevel <= maxLevel) {
                Map<String, Object> nextLevelNode = ensureLevel(opNode, nextLevel);
                nextLevelNode.put("unlocked", true);
                Map<String, Object> whiteNext = ensureColored(nextLevelNode, Belt.WHITE.value());
                whiteNext.put("unlocked", true);
            } else {
                // Last level of this operation completed — unlock dependent operations
                unlockDependentOperations(progress, op);
            }
        }

        user.setProgress(progress);
        cachedUsers.save(user);

        log.info("[PROGRESSION] Awarded entire level {} for operation {} to user {}", level, operation, userId);
        return progress;
    }

    /**
     * Check if an operation is unlocked for a user.
     * An operation is unlocked if:
     * - It has no prerequisite, OR
     * - The prerequisite operation's last level is completed
     */
    public boolean isOperationUnlocked(String userId, String operation) {
        if (operation == null) return false;
        String op = operation.toLowerCase(Locale.ROOT);

        // Disabled operations are never unlocked
        if (gameConfig.getMaxLevel(op) <= 0) return false;

        String prerequisite = gameConfig.getOperationPrerequisite(op);
        if (prerequisite == null) return true; // No prerequisite, always unlocked

        if (userId == null) return false;

        var userOpt = cachedUsers.findById(userId);
        if (userOpt.isEmpty()) return false;

        Map<String, Object> progress = userOpt.get().getProgress();
        if (progress == null) return false;

        progress = migrateIfNeeded(progress);
        return isOperationCompleted(progress, prerequisite);
    }

    /**
     * Check if all levels of an operation are completed.
     */
    @SuppressWarnings("unchecked")
    private boolean isOperationCompleted(Map<String, Object> progress, String operation) {
        Map<String, Object> opNode = (Map<String, Object>) progress.get(operation.toLowerCase(Locale.ROOT));
        if (opNode == null) return false;

        int maxLevel = gameConfig.getMaxLevel(operation);
        if (maxLevel <= 0) return false;

        String lastLevelKey = "L" + maxLevel;
        Map<String, Object> lastLevel = (Map<String, Object>) opNode.get(lastLevelKey);
        if (lastLevel == null) return false;

        return Boolean.TRUE.equals(lastLevel.get("completed"));
    }

    // ===== DEPENDENT OPERATION UNLOCK =====

    /**
     * When an operation is fully completed, find any operations that have it as a prerequisite
     * and unlock their L1 + first belt in the progress document.
     */
    private void unlockDependentOperations(Map<String, Object> progress, String completedOp) {
        Map<String, GameConfig.OperationConfig> allOps = gameConfig.getAllOperationConfigs();
        for (Map.Entry<String, GameConfig.OperationConfig> entry : allOps.entrySet()) {
            String depOp = entry.getKey().toLowerCase(Locale.ROOT);
            String prereq = entry.getValue().getPrerequisite();
            if (prereq != null && prereq.equalsIgnoreCase(completedOp)) {
                int depMaxLevel = gameConfig.getMaxLevel(depOp);
                if (depMaxLevel <= 0) {
                    log.info("[PROGRESSION] Skipping unlock of {} — operation disabled (maxLevel={})", depOp, depMaxLevel);
                    continue;
                }
                Map<String, Object> depOpNode = ensureOperationNode(progress, depOp);
                Map<String, Object> l1 = ensureLevel(depOpNode, 1);
                l1.put("unlocked", true);
                Map<String, Object> white = ensureColored(l1, Belt.WHITE.value());
                white.put("unlocked", true);
                log.info("[PROGRESSION] Unlocked {} L1 (prerequisite {} completed)", depOp, completedOp);
            }
        }
    }

    // ===== MIGRATION =====

    /**
     * Detect old progress format (top-level keys start with "L") and migrate to per-operation format.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> migrateIfNeeded(Map<String, Object> progress) {
        if (progress == null || progress.isEmpty()) return progress;

        // Check if already in new format (top-level keys are operation names)
        if (progress.containsKey(Operation.ADD.value()) || progress.containsKey(Operation.SUB.value())) {
            return progress;
        }

        // Check if old format (top-level keys start with "L")
        boolean hasLevelKeys = progress.keySet().stream().anyMatch(k -> k.startsWith("L"));
        if (!hasLevelKeys) return progress;

        // Migrate: move all L* keys under "add"
        Map<String, Object> addNode = new LinkedHashMap<>();
        Iterator<Map.Entry<String, Object>> it = progress.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Object> entry = it.next();
            if (entry.getKey().startsWith("L")) {
                Map<String, Object> levelData = (Map<String, Object>) entry.getValue();
                // Convert old pretest format to new
                migratePretestInLevel(levelData, Operation.ADD.value());
                addNode.put(entry.getKey(), levelData);
            }
        }

        Map<String, Object> migrated = new LinkedHashMap<>();
        migrated.put(Operation.ADD.value(), addNode);

        // Check if addition is fully completed — if so, unlock sub L1
        boolean addCompleted = isOperationCompleted(migrated, Operation.ADD.value());
        Map<String, Object> subNode = new LinkedHashMap<>();
        Map<String, Object> subL1 = createLevelNode(1, addCompleted);
        subNode.put("L1", subL1);
        migrated.put(Operation.SUB.value(), subNode);

        return migrated;
    }

    /**
     * Convert old pretest format (per-operation map) to new format (simple taken/passed).
     * Old: pretest: { add: { taken: true, passed: false }, sub: { taken: false, passed: false } }
     * New: pretest: { taken: true, passed: false }  (operation is the parent scope)
     */
    @SuppressWarnings("unchecked")
    private void migratePretestInLevel(Map<String, Object> levelData, String operation) {
        Object pretestObj = levelData.get("pretest");
        if (pretestObj instanceof Map) {
            Map<String, Object> pretestMap = (Map<String, Object>) pretestObj;
            // Check if it's old format (has operation keys like "add", "sub")
            if (pretestMap.containsKey(Operation.ADD.value()) || pretestMap.containsKey(Operation.SUB.value())) {
                Map<String, Object> opPretest = (Map<String, Object>) pretestMap.get(operation);
                if (opPretest != null) {
                    levelData.put("pretest", new HashMap<>(opPretest));
                } else {
                    Map<String, Object> fresh = new HashMap<>();
                    fresh.put("taken", false);
                    fresh.put("passed", false);
                    levelData.put("pretest", fresh);
                }
            }
            // If it already has "taken"/"passed" directly, it's already new format
        }
    }

    /**
     * Ensure user progress is migrated and return it.
     */
    private Map<String, Object> ensureMigrated(User user) {
        Map<String, Object> progress = user.getProgress();
        if (progress == null) progress = new HashMap<>();
        Map<String, Object> migrated = migrateIfNeeded(progress);
        if (migrated != progress) {
            user.setProgress(migrated);
        }
        return migrated;
    }

    // ===== NODE HELPERS =====

    @SuppressWarnings("unchecked")
    private Map<String, Object> ensureOperationNode(Map<String, Object> progress, String operation) {
        Map<String, Object> opNode = (Map<String, Object>) progress.get(operation);
        if (opNode == null) {
            opNode = new LinkedHashMap<>();
            progress.put(operation, opNode);
        }
        return opNode;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> ensureLevel(Map<String, Object> opNode, int level) {
        String key = "L" + level;
        Map<String, Object> lvl = (Map<String, Object>) opNode.get(key);
        if (lvl == null) {
            boolean isFirst = level == 1 && opNode.isEmpty();
            lvl = createLevelNode(level, isFirst);
            opNode.put(key, lvl);
        }
        return lvl;
    }

    private Map<String, Object> createLevelNode(int level, boolean unlocked) {
        return ProgressDefaults.createLevelNode(level, unlocked);
    }

    private Map<String, Object> createLockedLevel(int level) {
        return createLevelNode(level, false);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> ensureColored(Map<String, Object> levelNode, String belt) {
        belt = belt.toLowerCase(Locale.ROOT);
        Map<String, Object> node = (Map<String, Object>) levelNode.get(belt);
        if (node == null) {
            node = new HashMap<>();
            node.put("unlocked", false);
            node.put("completed", false);
            levelNode.put(belt, node);
        }
        return node;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> ensureBlack(Map<String, Object> levelNode) {
        Map<String, Object> black = (Map<String, Object>) levelNode.get("black");
        if (black == null) {
            black = new HashMap<>();
            black.put("unlocked", false);
            black.put("completedDegrees", new ArrayList<Integer>());
            levelNode.put("black", black);
        } else if (!black.containsKey("completedDegrees")) {
            black.put("completedDegrees", new ArrayList<Integer>());
        }
        return black;
    }

    private String nextColoredAfter(String belt) {
        List<String> order = Belt.COLORED_ORDER;
        int i = order.indexOf(belt);
        if (i < 0 || i == order.size() - 1) return null;
        return order.get(i + 1);
    }

    private int parseDegree(String belt) {
        try {
            if (belt == null) return 0;
            String lower = belt.toLowerCase(Locale.ROOT);
            if (lower.matches("black-\\d")) {
                return Integer.parseInt(lower.substring("black-".length()));
            }
            return 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private boolean isBlackDegree(String belt) {
        if (belt == null) return false;
        return belt.toLowerCase(Locale.ROOT).matches("black-\\d");
    }
}
