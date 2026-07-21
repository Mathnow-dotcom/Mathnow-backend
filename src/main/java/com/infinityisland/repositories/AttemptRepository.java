package com.infinityisland.repositories;

import com.infinityisland.dao.Attempt;
import org.springframework.data.mongodb.repository.Aggregation;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AttemptRepository extends MongoRepository<Attempt, String> {

    // === EXISTING METHODS ===
    List<Attempt> findByQuizRunIdOrderByIdAsc(String quizRunId);
    Optional<Attempt> findByQuizRunIdAndQuestionId(String quizRunId, String questionId);
    long countByQuizRunId(String quizRunId);
    long countByQuizRunIdAndCorrectTrue(String quizRunId);

    // === USER-LEVEL QUERIES ===
    List<Attempt> findByUserId(String userId);
    List<Attempt> findByUserIdOrderByAttemptedAtDesc(String userId);

    // With pagination
    List<Attempt> findByUserIdOrderByAttemptedAtDesc(String userId, org.springframework.data.domain.Pageable pageable);

    // === FILTERED QUERIES ===
    List<Attempt> findByUserIdAndLevel(String userId, Integer level);
    List<Attempt> findByUserIdAndOperation(String userId, String operation);
    List<Attempt> findByUserIdAndLevelAndOperation(String userId, Integer level, String operation);

    // === TIME-RANGE QUERIES ===
    List<Attempt> findByUserIdAndAttemptedAtBetween(String userId, Instant from, Instant to);

    // === FACT-SPECIFIC QUERIES ===
    @Query("{ 'userId': ?0, 'operation': ?1, 'a': ?2, 'b': ?3 }")
    List<Attempt> findByUserIdAndFact(String userId, String operation, Integer a, Integer b);

    // === COUNTS ===
    long countByUserId(String userId);
    long countByUserIdAndCorrectTrue(String userId);
    long countByUserIdAndLevel(String userId, Integer level);
    long countByUserIdAndLevelAndCorrectTrue(String userId, Integer level);

    // === DELETE ===
    void deleteByUserId(String userId);
    void deleteByQuizRunId(String quizRunId);
}