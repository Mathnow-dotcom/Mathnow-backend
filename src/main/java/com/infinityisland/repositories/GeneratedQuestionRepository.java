package com.infinityisland.repositories;

import com.infinityisland.dao.GeneratedQuestion;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.util.Collection;
import java.util.List;

public interface GeneratedQuestionRepository extends MongoRepository<GeneratedQuestion, String> {
    @Query("{'operation': ?0, 'level': ?1, 'beltOrDegree': ?2}")
    List<GeneratedQuestion> findByOperationAndLevelAndBeltOrDegree(String operation, String level, String beltOrDegree);
    List<GeneratedQuestion> findByIdIn(Collection<String> ids); // convenience alongside findAllById
}
