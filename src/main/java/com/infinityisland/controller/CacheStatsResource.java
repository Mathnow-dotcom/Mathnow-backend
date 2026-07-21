package com.infinityisland.controller;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Admin endpoint to monitor cache performance.
 * Access at /api/cache/stats
 */
@Component
@Path("/cache")
@Produces(MediaType.APPLICATION_JSON)
public class CacheStatsResource {

    private final CacheManager cacheManager;

    public CacheStatsResource(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    @GET
    @Path("/stats")
    public Map<String, Object> getStats() {
        Collection<String> cacheNames = cacheManager.getCacheNames();
        Map<String, Object> allStats = new LinkedHashMap<>();

        for (String name : cacheNames) {
            Cache cache = cacheManager.getCache(name);
            if (cache instanceof CaffeineCache caffeineCache) {
                com.github.benmanes.caffeine.cache.Cache<Object, Object> nativeCache =
                        caffeineCache.getNativeCache();

                com.github.benmanes.caffeine.cache.stats.CacheStats stats = nativeCache.stats();

                Map<String, Object> cacheInfo = new LinkedHashMap<>();
                cacheInfo.put("hitCount", stats.hitCount());
                cacheInfo.put("missCount", stats.missCount());
                cacheInfo.put("hitRate", String.format("%.2f%%", stats.hitRate() * 100));
                cacheInfo.put("missRate", String.format("%.2f%%", stats.missRate() * 100));
                cacheInfo.put("loadSuccessCount", stats.loadSuccessCount());
                cacheInfo.put("loadFailureCount", stats.loadFailureCount());
                cacheInfo.put("totalLoadTime", stats.totalLoadTime());
                cacheInfo.put("evictionCount", stats.evictionCount());
                cacheInfo.put("estimatedSize", nativeCache.estimatedSize());

                // ✅ NEW: Show if cache is approaching max size
                long size = nativeCache.estimatedSize();
                if (size > 8000) {
                    cacheInfo.put("warning", "Cache approaching max size!");
                }

                allStats.put(name, cacheInfo);
            }
        }

        // ✅ NEW: Add JVM memory stats
        Runtime runtime = Runtime.getRuntime();
        Map<String, Object> memory = new LinkedHashMap<>();
        memory.put("maxMemory", runtime.maxMemory() / 1024 / 1024 + " MB");
        memory.put("totalMemory", runtime.totalMemory() / 1024 / 1024 + " MB");
        memory.put("freeMemory", runtime.freeMemory() / 1024 / 1024 + " MB");
        memory.put("usedMemory", (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024 + " MB");

        allStats.put("jvmMemory", memory);

        return Map.of(
                "caches", allStats,
                "timestamp", System.currentTimeMillis()
        );
    }

    @GET
    @Path("/clear")
    public Map<String, Object> clearAll() {
        Collection<String> cacheNames = cacheManager.getCacheNames();
        List<String> cleared = new ArrayList<>();

        for (String name : cacheNames) {
            Cache cache = cacheManager.getCache(name);
            if (cache != null) {
                cache.clear();
                cleared.add(name);
            }
        }

        return Map.of(
                "success", true,
                "clearedCaches", cleared,
                "timestamp", System.currentTimeMillis()
        );
    }
}