package com.infinityisland.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.infinityisland.dao.GeneratedQuestion;
import com.infinityisland.repositories.GeneratedQuestionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Cache-aware question service.
 * Questions are read-heavy, so we cache aggressively.
 */
@Service
public class CachedQuestionService {

    private static final Logger log = LoggerFactory.getLogger(CachedQuestionService.class);

    private final GeneratedQuestionRepository questionRepo;

    // ✅ FIXED: Bounded cache with size limit and expiration
    private final Cache<String, GeneratedQuestion> hotCache;

    public CachedQuestionService(GeneratedQuestionRepository questionRepo) {
        this.questionRepo = questionRepo;

        // Max 20,000 questions in memory, expire after 2 hours
        this.hotCache = Caffeine.newBuilder()
                .maximumSize(20_000)
                .expireAfterAccess(2, TimeUnit.HOURS)
                .recordStats()
                .build();
    }

    @Cacheable(value = "questions", key = "#id", unless = "#result == null")
    public Optional<GeneratedQuestion> findById(String id) {
        GeneratedQuestion hot = hotCache.getIfPresent(id);
        if (hot != null) {
            return Optional.of(hot);
        }

        Optional<GeneratedQuestion> result = questionRepo.findById(id);
        result.ifPresent(q -> hotCache.put(id, q));
        return result;
    }

    public List<GeneratedQuestion> findAllById(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }

        List<GeneratedQuestion> result = new ArrayList<>(ids.size());
        List<String> missing = new ArrayList<>();

        for (String id : ids) {
            GeneratedQuestion q = hotCache.getIfPresent(id);
            if (q != null) {
                result.add(q);
            } else {
                missing.add(id);
            }
        }

        if (!missing.isEmpty()) {
            log.debug("[CACHE MISS] Fetching {} questions from DB", missing.size());
            List<GeneratedQuestion> fromDb = questionRepo.findAllById(missing);
            for (GeneratedQuestion q : fromDb) {
                hotCache.put(q.getId(), q);
                result.add(q);
            }
        }

        return result;
    }

    public GeneratedQuestion save(GeneratedQuestion question) {
        GeneratedQuestion saved = questionRepo.save(question);
        hotCache.put(saved.getId(), saved);
        return saved;
    }

    public List<GeneratedQuestion> saveAll(Iterable<GeneratedQuestion> questions) {
        List<GeneratedQuestion> saved = new ArrayList<>(questionRepo.saveAll(questions));
        for (GeneratedQuestion q : saved) {
            hotCache.put(q.getId(), q);
        }
        log.info("[CACHE] Populated {} questions", saved.size());
        return saved;
    }
}