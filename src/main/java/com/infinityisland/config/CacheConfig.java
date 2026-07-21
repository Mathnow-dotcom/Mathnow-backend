package com.infinityisland.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.concurrent.TimeUnit;

/**
 * Caffeine cache configuration for high-performance caching.
 *
 * Cache Strategy:
 * - users: Long TTL (30 min), updated on write
 * - quizRuns: Medium TTL (10 min), hot during active quiz
 * - questions: Long TTL (60 min), rarely change
 * - dailySummaries: Short TTL (5 min), frequently updated
 */
@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    @Primary
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager(
                "users",
                "quizRuns",
                "questions",
                "dailySummaries",
                "usersByPin"
        );
        manager.setCaffeine(defaultCaffeine());
        return manager;
    }

    @Bean
    public Caffeine<Object, Object> defaultCaffeine() {
        return Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(30, TimeUnit.MINUTES)
                .recordStats();
    }

    @Bean(name = "userCache")
    public Caffeine<Object, Object> userCaffeine() {
        return Caffeine.newBuilder()
                .maximumSize(5_000)
                .expireAfterWrite(30, TimeUnit.MINUTES)
                .expireAfterAccess(15, TimeUnit.MINUTES)
                .recordStats();
    }

    @Bean(name = "quizRunCache")
    public Caffeine<Object, Object> quizRunCaffeine() {
        return Caffeine.newBuilder()
                .maximumSize(2_000)
                .expireAfterWrite(10, TimeUnit.MINUTES)
                .expireAfterAccess(5, TimeUnit.MINUTES)
                .recordStats();
    }

    @Bean(name = "questionCache")
    public Caffeine<Object, Object> questionCaffeine() {
        return Caffeine.newBuilder()
                .maximumSize(20_000)
                .expireAfterWrite(60, TimeUnit.MINUTES)
                .recordStats();
    }

    @Bean(name = "dailySummaryCache")
    public Caffeine<Object, Object> dailySummaryCaffeine() {
        return Caffeine.newBuilder()
                .maximumSize(5_000)
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .expireAfterAccess(2, TimeUnit.MINUTES)
                .recordStats();
    }
}