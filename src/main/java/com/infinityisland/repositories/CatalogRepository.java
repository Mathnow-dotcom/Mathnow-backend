package com.infinityisland.repositories;

import com.infinityisland.dao.Catalog;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface CatalogRepository extends MongoRepository<Catalog, String> {
    Optional<Catalog> findByOperationAndLevelAndBelt(String operation, String level, String belt);
}