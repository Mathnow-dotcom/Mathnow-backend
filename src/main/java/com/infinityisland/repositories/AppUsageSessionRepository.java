package com.infinityisland.repositories;

import com.infinityisland.dao.AppUsageSession;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface AppUsageSessionRepository extends MongoRepository<AppUsageSession, String> {
    Optional<AppUsageSession> findFirstByUserIdAndActiveTrue(String userId);
}
