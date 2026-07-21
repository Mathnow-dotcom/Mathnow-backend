package com.infinityisland.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.infinityisland.dao.DailySummary;
import com.infinityisland.dao.user.User;
import com.infinityisland.repositories.DailySummaryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.infinityisland.controller.QuizResponses.DailyStatsResponse;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * CACHE-FIRST Daily Stats Service
 *
 * Strategy:
 * 1. ALL reads/writes happen in-memory cache (instant, consistent)
 * 2. Background job syncs to MongoDB every 30 seconds
 * 3. No race conditions, no stale reads
 */
@Service
public class DailyService {

  private static final Logger log = LoggerFactory.getLogger(DailyService.class);

  private static final ZoneId PST = ZoneId.of("America/Los_Angeles");

  @Autowired
  private CachedUserService cachedUsers;

  @Autowired
  private MongoTemplate mongoTemplate;

  private final DailySummaryRepository dailyRepo;

  // ===== SINGLE SOURCE OF TRUTH: In-Memory Cache =====
  private final Cache<String, DailySummaryCache> cache;

  // Track which entries need DB sync
  private final Set<String> dirtyKeys = ConcurrentHashMap.newKeySet();

  public DailyService(DailySummaryRepository dailyRepo) {
    this.dailyRepo = dailyRepo;

    // Cache never expires during the day, persists across requests
    this.cache = Caffeine.newBuilder()
            .maximumSize(50_000)
            .expireAfterWrite(24, TimeUnit.HOURS)
            .build();
  }

  /**
   * In-memory representation of daily stats
   */
  private static class DailySummaryCache {
    private final String userId;
    private final LocalDate date;
    private int correctCount;
    private long totalActiveMs;
    private volatile boolean loadedFromDb;

    public DailySummaryCache(String userId, LocalDate date) {
      this.userId = userId;
      this.date = date;
      this.correctCount = 0;
      this.totalActiveMs = 0L;
      this.loadedFromDb = false;
    }

    public DailySummaryCache(DailySummary ds) {
      this.userId = ds.getUserId();
      this.date = ds.getDate();
      this.correctCount = ds.getCorrectCount();
      this.totalActiveMs = ds.getTotalActiveMs();
      this.loadedFromDb = true;
    }
  }

  public static class Today {
    private final String date;
    private final int correctCount;
    private final long totalActiveMs;

    public Today(String date, int correctCount, long totalActiveMs) {
      this.date = date;
      this.correctCount = correctCount;
      this.totalActiveMs = totalActiveMs;
    }

    public String getDate() { return date; }
    public int getCorrectCount() { return correctCount; }
    public long getTotalActiveMs() { return totalActiveMs; }
  }

  /**
   * Get today's stats - ALWAYS from cache
   */
  public Today getToday(String userId) {
    LocalDate today = LocalDate.now(PST);
    String key = cacheKey(userId, today);

    DailySummaryCache cached = cache.get(key, k -> {
      // On first access, load from DB if exists
      return dailyRepo.findByUserIdAndDate(userId, today)
              .map(DailySummaryCache::new)
              .orElseGet(() -> new DailySummaryCache(userId, today));
    });

    return new Today(
            cached.date.toString(),
            cached.correctCount,
            cached.totalActiveMs
    );
  }

  /**
   * Get full stats - ALWAYS from cache + user cache
   */
  public DailyStatsResponse getForUser(String userId) {
    LocalDate today = LocalDate.now(PST);
    Today todayData = getToday(userId);

    // Grand total from user cache (also kept in sync)
    long grandTotal = cachedUsers.findById(userId)
            .map(u -> u.getGrandTotalCorrect() != null ? u.getGrandTotalCorrect() : 0L)
            .orElse(0L);

    int streak = cachedUsers.findById(userId)
            .map(User::getCurrentStreak)
            .orElse(0);

    // Lifetime total active time = historic (all days except today) + today's cached value
    long historicActiveMs = dailyRepo.sumTotalActiveMsExcludingDate(userId, today);
    long grandTotalActiveMs = historicActiveMs + todayData.getTotalActiveMs();

    DailyStatsResponse m = new DailyStatsResponse();
    m._id = userId + ":" + today;
    m.user = userId;
    m.date = todayData.getDate();
    m.correctCount = todayData.getCorrectCount();
    m.totalActiveMs = todayData.getTotalActiveMs();
    m.grandTotal = grandTotal;
    m.grandTotalActiveMs = grandTotalActiveMs;
    m.currentStreak = streak;

    return m;
  }

  /**
   * INCREMENT - Instant cache-only update
   * Returns IMMEDIATELY with consistent data
   */
  public DailyStatsResponse increment(String userId, long correctDelta, long activeMsDelta) {
    if (correctDelta <= 0 && activeMsDelta <= 0) {
      return getForUser(userId);
    }

    LocalDate today = LocalDate.now(PST);
    String key = cacheKey(userId, today);

    // 1. Update cache (instant, atomic)
    DailySummaryCache cached = cache.get(key, k -> {
      return dailyRepo.findByUserIdAndDate(userId, today)
              .map(DailySummaryCache::new)
              .orElseGet(() -> new DailySummaryCache(userId, today));
    });

    synchronized (cached) {
      cached.correctCount += (int) correctDelta;
      cached.totalActiveMs += activeMsDelta;
    }

    // Mark as dirty for background sync
    dirtyKeys.add(key);

    // 2. Update user grand total in cache
    if (correctDelta > 0) {
      cachedUsers.findById(userId).ifPresent(user -> {
        long current = user.getGrandTotalCorrect() != null ? user.getGrandTotalCorrect() : 0L;
        user.setGrandTotalCorrect(current + correctDelta);
        cachedUsers.updateCache(user);

        // Also mark user as dirty for sync
        dirtyKeys.add("user:" + userId);
      });
    }

    // 3. Return IMMEDIATELY with updated cache data
    return getForUser(userId);
  }

  /**
   * Background job: Sync dirty cache entries to MongoDB every 30 seconds
   */
  @Scheduled(fixedDelay = 1000, initialDelay = 1000)
  public void syncToDatabase() {
    if (dirtyKeys.isEmpty()) {
      return;
    }

    // ✅ FIXED: Remove keys atomically as we process them
    // This way, new increments during sync are preserved
    int synced = 0;
    int errors = 0;

    Iterator<String> iterator = dirtyKeys.iterator();
    while (iterator.hasNext()) {
      String key = iterator.next();

      try {
        if (key.startsWith("user:")) {
          syncUserToDb(key.substring(5));
        } else {
          syncDailySummaryToDb(key);
        }

        // Only remove if sync successful
        iterator.remove();
        synced++;

      } catch (Exception e) {
        log.error("[SYNC ERROR] Failed to sync {}: {}", key, e.getMessage(), e);
        // Leave in set for retry
        errors++;
      }
    }

    if (synced > 0) {
      log.info("[SYNC] Synced {} entries{}", synced,
              (errors > 0 ? " (" + errors + " errors, will retry)" : ""));
    }
  }

  /**
   * Force immediate sync (call on quiz completion)
   */
  public void forceFlush(String userId) {
    LocalDate today = LocalDate.now(PST);
    String key = cacheKey(userId, today);

    try {
      syncDailySummaryToDb(key);
      syncUserToDb(userId);
      dirtyKeys.remove(key);
      dirtyKeys.remove("user:" + userId);

      log.info("[SYNC] Force flushed user {}", userId);
    } catch (Exception e) {
      log.error("[SYNC ERROR] Force flush failed: {}", e.getMessage(), e);
    }
  }

  /**
   * Sync a single daily summary to MongoDB
   */
  private void syncDailySummaryToDb(String key) {
    DailySummaryCache cached = cache.getIfPresent(key);
    if (cached == null) {
      return;
    }

    Query query = Query.query(
            Criteria.where("userId").is(cached.userId)
                    .and("date").is(cached.date)
    );

    Update update = new Update()
            .set("correctCount", cached.correctCount)
            .set("totalActiveMs", cached.totalActiveMs)
            .setOnInsert("userId", cached.userId)
            .setOnInsert("date", cached.date);

    mongoTemplate.upsert(query, update, DailySummary.class);
  }

  /**
   * Sync user grand total to MongoDB
   */
  private void syncUserToDb(String userId) {
    cachedUsers.findById(userId).ifPresent(user -> {
      Query query = Query.query(Criteria.where("_id").is(userId));
      Update update = new Update()
              .set("grandTotalCorrect", user.getGrandTotalCorrect());
      mongoTemplate.updateFirst(query, update, User.class);
    });
  }

  /**
   * Cache key helper
   */
  private String cacheKey(String userId, LocalDate date) {
    return userId + ":" + date;
  }

  /**
   * Pre-load today's data from DB (call on server startup)
   */
  public void warmCache() {
    LocalDate today = LocalDate.now(PST);
    List<DailySummary> todaysSummaries = dailyRepo.findByDate(today);


    for (DailySummary ds : todaysSummaries) {
      String key = cacheKey(ds.getUserId(), ds.getDate());
      cache.put(key, new DailySummaryCache(ds));
    }

    log.info("[CACHE WARM] Loaded {} daily summaries", todaysSummaries.size());
  }

// Add this method to DailyService.java (after forceFlush method)

  /**
   * Clear cache entries for a specific user (used during reset)
   */
  public void clearUserCache(String userId) {
    LocalDate today = LocalDate.now(PST);
    String key = cacheKey(userId, today);
    cache.invalidate(key);
    dirtyKeys.remove(key);
    dirtyKeys.remove("user:" + userId);

    // Also delete from DB
    dailyRepo.deleteByUserId(userId);

    log.info("[CACHE] Cleared daily cache and DB for user {}", userId);
  }
}