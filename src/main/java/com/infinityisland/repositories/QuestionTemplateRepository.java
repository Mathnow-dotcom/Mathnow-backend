package com.infinityisland.repositories;

import com.infinityisland.dao.QuestionTemplate;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.util.List;

public interface QuestionTemplateRepository extends MongoRepository<QuestionTemplate, String> {

    // Use explicit @Query so Spring Data doesn't try to split BeltOrDegree on the keyword "Or"
    @Query(value = "{ 'operation': ?0, 'level': ?1, 'beltOrDegree': ?2 }")
    List<QuestionTemplate> findByOperationAndLevelAndBeltOrDegree(String operation, String level, String beltOrDegree);

    // Make count a default method so it won't be parsed/derived; delegate to the finder.
    default long countByOperationAndLevelAndBeltOrDegree(String operation, String level, String beltOrDegree) {
        return findByOperationAndLevelAndBeltOrDegree(operation, level, beltOrDegree).size();
    }

    // --- Back-compat shims for any existing call sites using "...Belt(...)" ---

    @Deprecated
    default List<QuestionTemplate> findByOperationAndLevelAndBelt(String operation, String level, String belt) {
        return findByOperationAndLevelAndBeltOrDegree(operation, level, belt);
    }

    @Deprecated
    default long countByOperationAndLevelAndBelt(String operation, String level, String belt) {
        return countByOperationAndLevelAndBeltOrDegree(operation, level, belt);
    }
}