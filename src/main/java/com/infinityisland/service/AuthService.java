package com.infinityisland.service;

import com.infinityisland.dao.user.User;
import com.infinityisland.model.Belt;
import com.infinityisland.model.Operation;
import com.infinityisland.util.ProgressDefaults;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.infinityisland.util.DateUtil;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class AuthService {
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private final CachedUserService cachedUsers;
    private final UserService userService;
    private final DailyService dailyService;

    public AuthService(CachedUserService cachedUsers, UserService userService, DailyService dailyService) {
        this.cachedUsers = cachedUsers;
        this.userService = userService;
        this.dailyService = dailyService;
    }

    public record LoginResult(UserPayload user, String token) {}

    public static class UserPayload {
        public String _id;
        public String pin;
        public String name;
        public String displayName;
        public String theme;
        public Map<String, Object> progress;
        public Map<String, Object> dailyStats;
        public int currentStreak;
        public int grandTotal;
        public String lastLoginDate;
        public String updatedAt;
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

    // In loginPin() method - REPLACE the existing user handling section:
    public LoginResult loginPin(String name, String pin) {
        try {
            if (pin == null || pin.isBlank()) {
                throw new IllegalArgumentException("PIN required");
            }
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Name required");
            }

            Optional<User> existingByPin = cachedUsers.findByPin(pin);
            User user;

            if (existingByPin.isPresent()) {
                user = existingByPin.get();

                if (!user.getName().equalsIgnoreCase(name)) {
                    throw new DuplicateKeyException(
                            "Pin already exists, please enter correct name."
                    );
                }

                // FIX: Ensure existing user has mutable progress maps
                Map<String, Object> currentProgress = user.getProgress();
                if (currentProgress != null) {
                    // Check if maps are immutable by attempting to verify structure
                    // If progress exists but is from immutable Map.of(), fix it
                    try {
                        Map<String, Object> l1 = (Map<String, Object>) currentProgress.get("L1");
                        if (l1 != null) {
                            Map<String, Object> black = (Map<String, Object>) l1.get("black");
                            if (black != null) {
                                // Try a no-op put to check mutability
                                // This will throw if immutable
                                black.putIfAbsent("_mutableCheck", true);
                                black.remove("_mutableCheck");
                            }
                        }
                    } catch (UnsupportedOperationException e) {
                        // Maps are immutable - need to deep copy to mutable maps
                        user.setProgress(deepCopyProgress(currentProgress));
                    }

                    // Migrate old progress format to per-operation format
                    if (currentProgress != null && currentProgress.keySet().stream().anyMatch(k -> k.startsWith("L"))) {
                        user.setProgress(migrateOldProgress(currentProgress));
                    }
                }

            } else {
                user = userService.createUser(name, pin);

                if (user == null) {
                    throw new IllegalStateException("Failed to create user");
                }

                // Ensure new user has proper mutable progress
                if (user.getProgress() == null) {
                    user.setProgress(baselineProgress());
                }
            }

            // ... rest of the method stays the same

            if (user.getUpdatedAt() == null) {
                user.setUpdatedAt(new Date());
            }

            String todayKey = DateUtil.today();
            String yKey = DateUtil.yesterday();
            int newStreak = user.getCurrentStreak();
            String last = user.getLastLoginDate();

            if (last == null) {
                newStreak = 1;
            } else if (last.equals(todayKey)) {
                // Same day: unchanged
            } else if (last.equals(yKey)) {
                newStreak += 1;
            } else {
                newStreak = 1;
            }

            user.setCurrentStreak(newStreak);
            user.setLastLoginDate(todayKey);
            cachedUsers.save(user);

            DailyService.Today todayStats = dailyService.getToday(user.getId());
            int correct = todayStats != null ? todayStats.getCorrectCount() : 0;
            long activeMs = todayStats != null ? todayStats.getTotalActiveMs() : 0;

            UserPayload payload = new UserPayload();
            payload._id = user.getId();
            payload.pin = user.getPin();
            payload.name = user.getName();
            payload.displayName = user.getDisplayName() != null ? user.getDisplayName() : user.getName();
            payload.theme = user.getTheme() != null ? user.getTheme() : "";
            payload.progress = user.getProgress();
            payload.currentStreak = newStreak;
            payload.lastLoginDate = user.getLastLoginDate() != null ? user.getLastLoginDate() : todayKey;
            payload.updatedAt = user.getUpdatedAt() != null ? user.getUpdatedAt().toString() : new Date().toString();
            payload.grandTotal = user.getGrandTotalCorrect() != null ? user.getGrandTotalCorrect().intValue() : 0;

            Map<String, Object> daily = new HashMap<>();
            daily.put("correctCount", correct);
            daily.put("totalActiveMs", activeMs);
            payload.dailyStats = daily;

            return new LoginResult(payload, pin);

        } catch (Exception e) {
            log.error("Login failed for pin", e);
            throw e;
        }
    }

    // Add this helper method to deep copy progress with mutable maps
    @SuppressWarnings("unchecked")
    private Map<String, Object> deepCopyProgress(Map<String, Object> source) {
        if (source == null) return baselineProgress();

        Map<String, Object> copy = new LinkedHashMap<>();

        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            if (value instanceof Map) {
                // Could be operation node (new format) or level node (old format)
                Map<String, Object> mapValue = (Map<String, Object>) value;
                if (key.startsWith("L")) {
                    // Old format: level node directly
                    copy.put(key, deepCopyLevel(mapValue));
                } else {
                    // New format: operation node containing level nodes
                    copy.put(key, deepCopyOperation(mapValue));
                }
            } else {
                copy.put(key, value);
            }
        }

        return copy;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> deepCopyOperation(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (entry.getValue() instanceof Map) {
                copy.put(entry.getKey(), deepCopyLevel((Map<String, Object>) entry.getValue()));
            } else {
                copy.put(entry.getKey(), entry.getValue());
            }
        }
        return copy;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> migrateOldProgress(Map<String, Object> oldProgress) {
        Map<String, Object> addNode = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : oldProgress.entrySet()) {
            if (entry.getKey().startsWith("L") && entry.getValue() instanceof Map) {
                Map<String, Object> levelData = deepCopyLevel((Map<String, Object>) entry.getValue());
                // Convert old pretest format if present
                Object pretestObj = levelData.get("pretest");
                if (pretestObj instanceof Map) {
                    Map<String, Object> pretestMap = (Map<String, Object>) pretestObj;
                    if (pretestMap.containsKey(Operation.ADD.value())) {
                        Map<String, Object> addPretest = (Map<String, Object>) pretestMap.get(Operation.ADD.value());
                        if (addPretest != null) {
                            levelData.put("pretest", new HashMap<>(addPretest));
                        }
                    }
                }
                addNode.put(entry.getKey(), levelData);
            }
        }

        Map<String, Object> migrated = new LinkedHashMap<>();
        migrated.put(Operation.ADD.value(), addNode);

        Map<String, Object> subNode = new LinkedHashMap<>();
        subNode.put("L1", createLevelNode(1, false));
        migrated.put(Operation.SUB.value(), subNode);

        return migrated;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> deepCopyLevel(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();

        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            if (value instanceof Map) {
                // Belt or black node - make mutable copy
                Map<String, Object> beltCopy = new HashMap<>();
                Map<String, Object> beltSource = (Map<String, Object>) value;
                for (Map.Entry<String, Object> beltEntry : beltSource.entrySet()) {
                    Object beltValue = beltEntry.getValue();
                    if (beltValue instanceof List) {
                        // completedDegrees - make mutable copy
                        beltCopy.put(beltEntry.getKey(), new ArrayList<>((List<?>) beltValue));
                    } else {
                        beltCopy.put(beltEntry.getKey(), beltValue);
                    }
                }
                copy.put(key, beltCopy);
            } else {
                copy.put(key, value);
            }
        }

        return copy;
    }
}