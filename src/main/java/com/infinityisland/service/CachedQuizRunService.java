package com.infinityisland.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.infinityisland.dao.QuizRun;
import com.infinityisland.model.GameModeType;
import com.infinityisland.model.QuizStatus;
import com.infinityisland.repositories.QuizRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * CACHE-FIRST Quiz Run Service
 *
 * Strategy:
 * 1. ALL reads come from cache (instant, consistent)
 * 2. ALL writes update cache immediately
 * 3. Background job syncs dirty entries to MongoDB every 1 second
 * 4. Force flush on quiz completion for durability
 */
@Service
public class CachedQuizRunService {

    private static final Logger log = LoggerFactory.getLogger(CachedQuizRunService.class);

    private final QuizRunRepository quizRunRepo;
    private final Cache<String, QuizRun> cache;
    private final Set<String> dirtyKeys = ConcurrentHashMap.newKeySet();

    public CachedQuizRunService(QuizRunRepository quizRunRepo) {
        this.quizRunRepo = quizRunRepo;
        this.cache = Caffeine.newBuilder()
                .maximumSize(5_000)
                .expireAfterWrite(30, TimeUnit.MINUTES)
                .expireAfterAccess(15, TimeUnit.MINUTES)
                .recordStats()
                .build();
    }

    // ===== CORE CRUD OPERATIONS =====

    public Optional<QuizRun> findById(String id) {
        QuizRun cached = cache.getIfPresent(id);
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<QuizRun> fromDb = quizRunRepo.findById(id);
        fromDb.ifPresent(run -> cache.put(id, run));
        return fromDb;
    }

    public QuizRun update(QuizRun run) {
        if (run.getId() == null) {
            throw new IllegalArgumentException("Cannot update QuizRun without ID");
        }
        cache.put(run.getId(), run);
        dirtyKeys.add(run.getId());
        return run;
    }

    public QuizRun updateCache(QuizRun run) {
        if (run.getId() == null) {
            throw new IllegalArgumentException("Cannot cache QuizRun without ID");
        }
        cache.put(run.getId(), run);
        dirtyKeys.add(run.getId());
        return run;
    }

    public QuizRun save(QuizRun run) {
        QuizRun saved = quizRunRepo.save(run);
        cache.put(saved.getId(), saved);
        dirtyKeys.remove(saved.getId());
        log.info("[CACHE] Created quiz {} status: {}{}", saved.getId(), saved.getStatus(),
                (saved.isPretestMode() ? " [PRETEST]" : saved.isLightningMode() ? " [LIGHTNING]" : saved.isSurfMode() ? " [SURF]" : saved.isRocketMode() ? " [ROCKET]" : ""));
        return saved;
    }

    public QuizRun saveSync(QuizRun run) {
        if (run.getId() == null) {
            throw new IllegalArgumentException("Cannot save QuizRun without ID");
        }
        cache.put(run.getId(), run);
        QuizRun saved = quizRunRepo.save(run);
        cache.put(saved.getId(), saved);
        dirtyKeys.remove(saved.getId());
        log.info("[CACHE] Sync saved quiz {} status: {}", saved.getId(), saved.getStatus());
        return saved;
    }

    public QuizRun updateCacheAndSaveSync(QuizRun run) {
        return saveSync(run);
    }

    public void forceFlush(String id) {
        QuizRun run = cache.getIfPresent(id);
        if (run != null) {
            try {
                quizRunRepo.save(run);
                dirtyKeys.remove(id);
                log.info("[CACHE] Force flushed quiz {}", id);
            } catch (Exception e) {
                log.error("[CACHE ERROR] Force flush failed for {}: {}", id, e.getMessage(), e);
            }
        }
    }

    @Scheduled(fixedDelay = 1000, initialDelay = 1000)
    public void syncToDatabase() {
        if (dirtyKeys.isEmpty()) return;

        int synced = 0, errors = 0;
        Iterator<String> iterator = dirtyKeys.iterator();

        while (iterator.hasNext()) {
            String key = iterator.next();
            QuizRun run = cache.getIfPresent(key);
            if (run == null) {
                iterator.remove();
                continue;
            }
            try {
                quizRunRepo.save(run);
                iterator.remove();
                synced++;
            } catch (Exception e) {
                log.error("[SYNC ERROR] Failed to sync quiz {}: {}", key, e.getMessage(), e);
                errors++;
            }
        }

        if (synced > 0 || errors > 0) {
            log.info("[SYNC] Quiz runs: {} synced{}", synced,
                    (errors > 0 ? ", " + errors + " errors (will retry)" : ""));
        }
    }

    public void evict(String id) {
        cache.invalidate(id);
        dirtyKeys.remove(id);
    }

    public void deleteByUserId(String userId) {
        cache.invalidateAll();
        dirtyKeys.clear();
        quizRunRepo.deleteByUserId(userId);
    }

    // ===== QUERY METHODS =====

    public Optional<QuizRun> findByUserIdAndStatus(String userId, String status) {
        Optional<QuizRun> result = quizRunRepo.findTopByUserIdAndStatusOrderByCreatedAtDesc(userId, status);
        return returnCachedOrDb(result);
    }

    // ===== LIGHTNING MODE (GAME MODE 1) =====

    /**
     * Find active Lightning mode quiz for user (any level/belt).
     */
    public Optional<QuizRun> findActiveLightningMode(String userId) {
        Optional<QuizRun> result = quizRunRepo.findActiveLightningModeByUserId(userId);
        return returnCachedOrDb(result);
    }

    /**
     * Check if Lightning mode is completed for specific level/belt/operation.
     */
    public boolean isLightningModeCompleted(String userId, Integer level, String beltOrDegree, String operation) {
        return quizRunRepo.existsCompletedLightningMode(userId, level, beltOrDegree, operation);
    }

    /**
     * @deprecated Use findActiveLightningMode instead
     */
    @Deprecated
    public Optional<QuizRun> findActiveGameMode(String userId) {
        return findActiveLightningMode(userId);
    }

    // ===== PRETEST MODE =====

    /**
     * Find active Pretest quiz for specific level/operation.
     */
    public Optional<QuizRun> findActivePretestMode(String userId, Integer level, String operation) {
        Optional<QuizRun> result = quizRunRepo.findActivePretestMode(userId, level, operation);
        return returnCachedOrDb(result);
    }

    // ===== SURF MODE (GAME MODE 2) =====

    /**
     * Find active Surf mode quiz for user (any level/belt).
     */
    public Optional<QuizRun> findActiveSurfMode(String userId) {
        Optional<QuizRun> result = quizRunRepo.findActiveSurfModeByUserId(userId);
        return returnCachedOrDb(result);
    }

    /**
     * Find active Surf mode for specific level/belt/operation.
     */
    public Optional<QuizRun> findActiveSurfMode(String userId, Integer level, String beltOrDegree, String operation) {
        Optional<QuizRun> result = quizRunRepo.findActiveSurfMode(userId, level, beltOrDegree, operation);
        return returnCachedOrDb(result);
    }

    // ===== ROCKET MODE (GAME MODE 3) =====

    /**
     * Find active Rocket mode quiz for user (any level/belt).
     */
    public Optional<QuizRun> findActiveRocketMode(String userId) {
        Optional<QuizRun> result = quizRunRepo.findActiveRocketModeByUserId(userId);
        return returnCachedOrDb(result);
    }

    /**
     * Find active Rocket mode for specific level/belt/operation.
     */
    public Optional<QuizRun> findActiveRocketMode(String userId, Integer level, String beltOrDegree, String operation) {
        Optional<QuizRun> result = quizRunRepo.findActiveRocketMode(userId, level, beltOrDegree, operation);
        return returnCachedOrDb(result);
    }

    /**
     * Check if Surf mode is completed for specific level/belt/operation.
     * (Prerequisite for Rocket mode)
     */
    public boolean isSurfModeCompleted(String userId, Integer level, String beltOrDegree, String operation) {
        return quizRunRepo.existsCompletedSurfMode(userId, level, beltOrDegree, operation);
    }

    /**
     * Find active Rocket mode for user (any belt), checking running then prepared.
     */
    public Optional<QuizRun> findActiveRocketModeForUser(String userId) {
        Optional<QuizRun> running = quizRunRepo.findFirstByUserIdAndStatusAndGameModeType(
                userId, QuizStatus.RUNNING.value(), GameModeType.ROCKET.value());
        if (running.isPresent()) {
            return running;
        }
        return quizRunRepo.findFirstByUserIdAndStatusAndGameModeType(
                userId, QuizStatus.PREPARED.value(), GameModeType.ROCKET.value());
    }

    /**
     * Check if Rocket mode is completed for specific level/belt/operation.
     * (Prerequisite for Bonus mode)
     */
    public boolean isRocketModeCompleted(String userId, Integer level, String beltOrDegree, String operation) {
        return quizRunRepo.existsCompletedRocketMode(userId, level, beltOrDegree, operation);
    }

    // ===== BONUS MODE (GAME MODE 4) =====

    /**
     * Find active Bonus mode quiz for user (any level/belt).
     */
    public Optional<QuizRun> findActiveBonusMode(String userId) {
        Optional<QuizRun> result = quizRunRepo.findActiveBonusModeByUserId(userId);
        return returnCachedOrDb(result);
    }

    /**
     * Find active Bonus mode for specific level/belt/operation.
     */
    public Optional<QuizRun> findActiveBonusMode(String userId, Integer level, String beltOrDegree, String operation) {
        Optional<QuizRun> result = quizRunRepo.findActiveBonusMode(userId, level, beltOrDegree, operation);
        return returnCachedOrDb(result);
    }

    /**
     * Find active Bonus mode for user (any belt), checking running then prepared.
     */
    public Optional<QuizRun> findActiveBonusModeForUser(String userId) {
        Optional<QuizRun> running = quizRunRepo.findFirstByUserIdAndStatusAndGameModeType(
                userId, QuizStatus.RUNNING.value(), GameModeType.BONUS.value());
        if (running.isPresent()) {
            return running;
        }
        return quizRunRepo.findFirstByUserIdAndStatusAndGameModeType(
                userId, QuizStatus.PREPARED.value(), GameModeType.BONUS.value());
    }

    /**
     * Check if Bonus mode is completed for specific level/belt/operation.
     */
    public boolean isBonusModeCompleted(String userId, Integer level, String beltOrDegree, String operation) {
        return quizRunRepo.existsCompletedBonusMode(userId, level, beltOrDegree, operation);
    }

    // ===== GENERAL QUERIES =====

    /**
     * Find any active game mode (lightning, surf, or rocket) for user.
     */
    public Optional<QuizRun> findAnyActiveGameMode(String userId) {
        // Check lightning first
        Optional<QuizRun> lightning = findActiveLightningMode(userId);
        if (lightning.isPresent()) return lightning;

        // Then check surf
        Optional<QuizRun> surf = findActiveSurfMode(userId);
        if (surf.isPresent()) return surf;

        // Then check rocket
        Optional<QuizRun> rocket = findActiveRocketMode(userId);
        if (rocket.isPresent()) return rocket;

        // Finally check bonus
        return findActiveBonusMode(userId);
    }

    public List<QuizRun> findActiveQuizzes(String userId) {
        List<QuizRun> dbResults = quizRunRepo.findActiveQuizzesByUserId(userId);
        return dbResults.stream()
                .map(dbRun -> {
                    QuizRun cached = cache.getIfPresent(dbRun.getId());
                    if (cached != null) return cached;
                    cache.put(dbRun.getId(), dbRun);
                    return dbRun;
                })
                .toList();
    }

    public Optional<QuizRun> findActiveSurfModeForUser(String userId) {
        // First check cache
        // If you have a method to iterate cache entries, use that
        // Otherwise, query the database

        // Try running status first
        Optional<QuizRun> running = quizRunRepo.findFirstByUserIdAndStatusAndGameModeType(
                userId, QuizStatus.RUNNING.value(), GameModeType.SURF.value());
        if (running.isPresent()) {
            return running;
        }

        // Then try prepared status
        return quizRunRepo.findFirstByUserIdAndStatusAndGameModeType(
                userId, QuizStatus.PREPARED.value(), GameModeType.SURF.value());
    }

    // ===== HELPER =====

    private Optional<QuizRun> returnCachedOrDb(Optional<QuizRun> dbResult) {
        if (dbResult.isEmpty()) return dbResult;

        String id = dbResult.get().getId();
        QuizRun cached = cache.getIfPresent(id);
        if (cached != null) {
            return Optional.of(cached);
        }
        cache.put(id, dbResult.get());
        return dbResult;
    }
}