package com.infinityisland.service;

import com.infinityisland.dao.DailySummary;
import com.infinityisland.repositories.DailySummaryRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Cache-aware daily summary service.
 * Uses composite key: userId + date
 */
@Service
public class CachedDailySummaryService {

    private final DailySummaryRepository dailyRepo;

    public CachedDailySummaryService(DailySummaryRepository dailyRepo) {
        this.dailyRepo = dailyRepo;
    }

    /**
     * Composite cache key
     */
    private String cacheKey(String userId, LocalDate date) {
        return userId + ":" + date;
    }

    /**
     * Get daily summary with caching
     */
    @Cacheable(value = "dailySummaries", key = "#userId + ':' + #date", unless = "#result == null")
    public Optional<DailySummary> findByUserIdAndDate(String userId, LocalDate date) {
        return dailyRepo.findByUserIdAndDate(userId, date);
    }

    /**
     * Save daily summary and update cache
     */
    @CachePut(value = "dailySummaries", key = "#summary.userId + ':' + #summary.date")
    public DailySummary save(DailySummary summary) {
        return dailyRepo.save(summary);
    }

    /**
     * Delete all summaries for user and clear cache
     */
    @CacheEvict(value = "dailySummaries", allEntries = true)
    public void deleteByUserId(String userId) {
        dailyRepo.deleteByUserId(userId);
    }

    /**
     * Evict specific day from cache
     */
    @CacheEvict(value = "dailySummaries", key = "#userId + ':' + #date")
    public void evict(String userId, LocalDate date) {
        // Cache eviction only
    }
}