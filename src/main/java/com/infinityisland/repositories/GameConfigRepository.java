package com.infinityisland.repositories;

import com.infinityisland.dao.GameConfig;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface GameConfigRepository extends MongoRepository<GameConfig, String> {
}