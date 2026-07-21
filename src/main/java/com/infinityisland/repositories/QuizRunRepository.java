package com.infinityisland.repositories;

import com.infinityisland.dao.QuizRun;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.util.List;
import java.util.Optional;

public interface QuizRunRepository extends MongoRepository<QuizRun, String> {
    List<QuizRun> findByUserIdAndStatus(String userId, String status);
    Optional<QuizRun> findTopByUserIdAndStatusOrderByCreatedAtDesc(String userId, String status);
    Optional<QuizRun> findByIdAndUserId(String id, String oduserId);
    void deleteByUserId(String userId);

    // ===== GAME MODE 1 (LIGHTNING) QUERIES =====

    /**
     * Find active Lightning mode quiz for user.
     * Matches: gameMode=true AND (gameModeType=null OR gameModeType="lightning")
     * Status must be 'prepared' or 'running'
     */
    @Query("{ 'userId': ?0, 'gameMode': true, " +
            "$or: [ { 'gameModeType': null }, { 'gameModeType': 'lightning' } ], " +
            "'status': { $in: ['prepared', 'running'] } }")
    Optional<QuizRun> findActiveLightningModeByUserId(String userId);

    /**
     * Find COMPLETED Lightning mode for user (to check if they can start surf mode)
     * For a specific level/belt/operation combo
     */
    @Query("{ 'userId': ?0, 'gameMode': true, " +
            "$or: [ { 'gameModeType': null }, { 'gameModeType': 'lightning' } ], " +
            "'level': ?1, 'beltOrDegree': ?2, 'operation': ?3, " +
            "'status': 'completed', 'passed': true }")
    Optional<QuizRun> findCompletedLightningMode(String userId, Integer level, String beltOrDegree, String operation);

    // ===== PRETEST MODE QUERIES =====

    /**
     * Find active Pretest quiz for specific level/operation
     */
    @Query("{ 'userId': ?0, 'pretestMode': true, 'level': ?1, 'operation': ?2, " +
            "'status': { $in: ['prepared', 'running'] } }")
    Optional<QuizRun> findActivePretestMode(String userId, Integer level, String operation);

    // ===== GAME MODE 2 (SURF) QUERIES =====

    /**
     * Find active Surf mode quiz for user.
     * Status must be 'prepared' or 'running'
     */
    @Query("{ 'userId': ?0, 'gameMode': true, 'gameModeType': 'surf', " +
            "'status': { $in: ['prepared', 'running'] } }")
    Optional<QuizRun> findActiveSurfModeByUserId(String userId);

    /**
     * Find active Surf mode for specific level/belt/operation
     */
    @Query("{ 'userId': ?0, 'gameMode': true, 'gameModeType': 'surf', " +
            "'level': ?1, 'beltOrDegree': ?2, 'operation': ?3, " +
            "'status': { $in: ['prepared', 'running'] } }")
    Optional<QuizRun> findActiveSurfMode(String userId, Integer level, String beltOrDegree, String operation);

    // ===== GAME MODE 3 (ROCKET) QUERIES =====

    /**
     * Find active Rocket mode quiz for user.
     * Status must be 'prepared' or 'running'
     */
    @Query("{ 'userId': ?0, 'gameMode': true, 'gameModeType': 'rocket', " +
            "'status': { $in: ['prepared', 'running'] } }")
    Optional<QuizRun> findActiveRocketModeByUserId(String userId);

    /**
     * Find active Rocket mode for specific level/belt/operation
     */
    @Query("{ 'userId': ?0, 'gameMode': true, 'gameModeType': 'rocket', " +
            "'level': ?1, 'beltOrDegree': ?2, 'operation': ?3, " +
            "'status': { $in: ['prepared', 'running'] } }")
    Optional<QuizRun> findActiveRocketMode(String userId, Integer level, String beltOrDegree, String operation);

    /**
     * Check if Surf mode is completed (prerequisite for Rocket mode)
     */
    @Query(value = "{ 'userId': ?0, 'gameMode': true, 'gameModeType': 'surf', " +
            "'level': ?1, 'beltOrDegree': ?2, 'operation': ?3, " +
            "'status': 'completed', 'passed': true }",
            exists = true)
    boolean existsCompletedSurfMode(String userId, Integer level, String beltOrDegree, String operation);

    /**
     * Check if Rocket mode is completed (prerequisite for Bonus mode)
     */
    @Query(value = "{ 'userId': ?0, 'gameMode': true, 'gameModeType': 'rocket', " +
            "'level': ?1, 'beltOrDegree': ?2, 'operation': ?3, " +
            "'status': 'completed', 'passed': true }",
            exists = true)
    boolean existsCompletedRocketMode(String userId, Integer level, String beltOrDegree, String operation);

    // ===== GAME MODE 4 (BONUS) QUERIES =====

    /**
     * Find active Bonus mode quiz for user.
     * Status must be 'prepared' or 'running'
     */
    @Query("{ 'userId': ?0, 'gameMode': true, 'gameModeType': 'bonus', " +
            "'status': { $in: ['prepared', 'running'] } }")
    Optional<QuizRun> findActiveBonusModeByUserId(String userId);

    /**
     * Find active Bonus mode for specific level/belt/operation
     */
    @Query("{ 'userId': ?0, 'gameMode': true, 'gameModeType': 'bonus', " +
            "'level': ?1, 'beltOrDegree': ?2, 'operation': ?3, " +
            "'status': { $in: ['prepared', 'running'] } }")
    Optional<QuizRun> findActiveBonusMode(String userId, Integer level, String beltOrDegree, String operation);

    /**
     * Check if Bonus mode is completed (used for analytics / future gating)
     */
    @Query(value = "{ 'userId': ?0, 'gameMode': true, 'gameModeType': 'bonus', " +
            "'level': ?1, 'beltOrDegree': ?2, 'operation': ?3, " +
            "'status': 'completed', 'passed': true }",
            exists = true)
    boolean existsCompletedBonusMode(String userId, Integer level, String beltOrDegree, String operation);

    // ===== GENERAL GAME MODE QUERIES =====

    /**
     * Find active game mode quiz for user (any type - lightning or surf)
     * Status must be 'prepared' or 'running'
     * @deprecated Use findActiveLightningModeByUserId or findActiveSurfModeByUserId
     */
    @Query("{ 'userId': ?0, 'gameMode': true, 'status': { $in: ['prepared', 'running'] } }")
    Optional<QuizRun> findActiveGameModeByUserId(String userId);

    /**
     * Find all active quizzes (game mode or normal) for user
     */
    @Query("{ 'userId': ?0, 'status': { $in: ['prepared', 'running'] } }")
    List<QuizRun> findActiveQuizzesByUserId(String userId);

    /**
     * Find active quiz for specific level/belt/operation (any mode)
     */
    @Query("{ 'userId': ?0, 'level': ?1, 'beltOrDegree': ?2, 'operation': ?3, " +
            "'status': { $in: ['prepared', 'running'] } }")
    Optional<QuizRun> findActiveQuizByLevelBeltOp(String userId, Integer level, String beltOrDegree, String operation);
    Optional<QuizRun> findFirstByUserIdAndStatusAndGameModeType(String userId, String status, String gameModeType);

    List<QuizRun> findByUserIdAndGameModeTypeAndStatus(
            String userId,
            String gameModeType,
            String status
    );

    // REPLACE Optional return with boolean exists
    @Query(value = "{ 'userId': ?0, 'gameMode': true, " +
            "$or: [ { 'gameModeType': null }, { 'gameModeType': 'lightning' } ], " +
            "'level': ?1, 'beltOrDegree': ?2, 'operation': ?3, " +
            "'status': 'completed', 'passed': true }",
            exists = true)
    boolean existsCompletedLightningMode(String userId, Integer level, String beltOrDegree, String operation);
}