package com.infinityisland.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.infinityisland.dao.user.User;
import com.infinityisland.repositories.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Cache-aware user service layer.
 * Sits between UserService and UserRepository.
 */
@Service
public class CachedUserService {

    private static final Logger log = LoggerFactory.getLogger(CachedUserService.class);

    private final UserRepository userRepo;
    private final Cache<String, User> cache;
    private final Cache<String, User> pinCache;

    public CachedUserService(UserRepository userRepo) {
        this.userRepo = userRepo;

        this.cache = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(30, TimeUnit.MINUTES)
                .expireAfterAccess(15, TimeUnit.MINUTES)
                .recordStats()
                .build();

        this.pinCache = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(30, TimeUnit.MINUTES)
                .recordStats()
                .build();
    }

    public Optional<User> findById(String id) {
        User cached = cache.getIfPresent(id);
        if (cached != null) {
            return Optional.of(cached);
        }

        Optional<User> fromDb = userRepo.findById(id);
        fromDb.ifPresent(user -> {
            cache.put(id, user);
            if (user.getPin() != null) {
                pinCache.put(user.getPin(), user);
            }
        });
        return fromDb;
    }

    public Optional<User> findByPin(String pin) {
        User cached = pinCache.getIfPresent(pin);
        if (cached != null) {
            return Optional.of(cached);
        }

        Optional<User> fromDb = userRepo.findByPin(pin);
        fromDb.ifPresent(user -> {
            cache.put(user.getId(), user);
            pinCache.put(pin, user);
        });
        return fromDb;
    }

    /**
     * Update cache only (for existing users with ID)
     */
    public User updateCache(User user) {
        if (user.getId() == null) {
            throw new IllegalArgumentException("Cannot cache User without ID");
        }
        cache.put(user.getId(), user);
        if (user.getPin() != null) {
            pinCache.put(user.getPin(), user);
        }
        return user;
    }

    /**
     * ✅ FIX: Save to DB first, then cache
     */
    public User save(User user) {
        // Save to DB first
        User saved = userRepo.save(user);

        // Cache with assigned ID
        cache.put(saved.getId(), saved);
        if (saved.getPin() != null) {
            pinCache.put(saved.getPin(), saved);
        }

        return saved;
    }

    @Async
    public CompletableFuture<User> saveAsync(User user) {
        try {
            User saved = userRepo.save(user);

            // Cache with ID
            if (saved.getId() != null) {
                cache.put(saved.getId(), saved);
                if (saved.getPin() != null) {
                    pinCache.put(saved.getPin(), saved);
                }
            }

            return CompletableFuture.completedFuture(saved);
        } catch (Exception e) {
            log.error("[ERROR] Async user save failed: {}", e.getMessage(), e);
            return CompletableFuture.failedFuture(e);
        }
    }

    public void delete(User user) {
        cache.invalidate(user.getId());
        if (user.getPin() != null) {
            pinCache.invalidate(user.getPin());
        }
        userRepo.delete(user);
    }

    public void evict(String id) {
        cache.invalidate(id);
    }

    public void evictByPin(String pin) {
        pinCache.invalidate(pin);
    }
}