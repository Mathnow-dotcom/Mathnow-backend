package com.infinityisland.config;

import com.infinityisland.model.GameModeType;
import com.infinityisland.model.QuizStatus;
import com.infinityisland.dao.DailySummary;
import com.infinityisland.dao.GeneratedQuestion;
import com.infinityisland.dao.QuizRun;
import com.infinityisland.dao.Attempt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.data.mongodb.core.index.PartialIndexFilter;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Component;

/**
 * Ensures helpful indexes exist (idempotent).
 * Wrapped in try-catch to handle pre-existing indexes with different names.
 */
@Component
public class MongoIndexes implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MongoIndexes.class);

    private final MongoTemplate mongo;

    public MongoIndexes(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("[INDEXES] Ensuring indexes...");

        ensureGeneratedQuestionIndexes();
        ensureQuizRunIndexes();
        ensureAttemptIndexes();
        ensureDailySummaryIndexes();

        log.info("[INDEXES] All indexes ensured");
    }

    private void ensureGeneratedQuestionIndexes() {
        IndexOperations ops = mongo.indexOps(GeneratedQuestion.class);

        safeEnsureIndex(ops, new Index()
                        .on("operation", Sort.Direction.ASC)
                        .on("level", Sort.Direction.ASC)
                        .on("beltOrDegree", Sort.Direction.ASC),
                "GeneratedQuestion.operation_level_belt");

        safeEnsureIndex(ops, new Index().on("source", Sort.Direction.ASC),
                "GeneratedQuestion.source");
    }

    private void ensureQuizRunIndexes() {
        IndexOperations ops = mongo.indexOps(QuizRun.class);

        safeEnsureIndex(ops, new Index()
                        .on("userId", Sort.Direction.ASC)
                        .on("status", Sort.Direction.ASC),
                "QuizRun.userId_status");

        safeEnsureIndex(ops, new Index().on("updatedAt", Sort.Direction.DESC),
                "QuizRun.updatedAt");

        safeEnsureIndex(ops, new Index().on("currentIndex", Sort.Direction.ASC),
                "QuizRun.currentIndex");

        safeEnsureIndex(ops, new Index().on("items.questionId", Sort.Direction.ASC),
                "QuizRun.items.questionId");

        safeEnsureIndex(ops, new Index()
                        .on("userId", Sort.Direction.ASC)
                        .on("gameMode", Sort.Direction.ASC)
                        .on("status", Sort.Direction.ASC),
                "QuizRun.userId_gameMode_status");

        // NEW: Index for gameModeType queries (lightning/surf mode lookups)
        safeEnsureIndex(ops, new Index()
                        .on("userId", Sort.Direction.ASC)
                        .on("gameModeType", Sort.Direction.ASC)
                        .on("status", Sort.Direction.ASC),
                "QuizRun.userId_gameModeType_status");

        // NEW: Index for completed lightning mode checks (surf mode prerequisite)
        safeEnsureIndex(ops, new Index()
                        .on("userId", Sort.Direction.ASC)
                        .on("gameMode", Sort.Direction.ASC)
                        .on("gameModeType", Sort.Direction.ASC)
                        .on("level", Sort.Direction.ASC)
                        .on("beltOrDegree", Sort.Direction.ASC)
                        .on("operation", Sort.Direction.ASC)
                        .on("status", Sort.Direction.ASC)
                        .on("passed", Sort.Direction.ASC),
                "QuizRun.userId_gameMode_level_belt_op_status_passed");

        // NEW: Index for active surf mode queries
        safeEnsureIndex(ops, new Index()
                        .on("userId", Sort.Direction.ASC)
                        .on("gameModeType", Sort.Direction.ASC)
                        .on("level", Sort.Direction.ASC)
                        .on("beltOrDegree", Sort.Direction.ASC)
                        .on("operation", Sort.Direction.ASC)
                        .on("status", Sort.Direction.ASC),
                "QuizRun.userId_surf_level_belt_op_status");

        // ===== UNIQUE PARTIAL INDEXES (Prevent duplicate active runs) =====

        // UNIQUE: Only one active lightning mode run per user
        // Prevents race condition where rapid clicks create duplicate runs
        safeEnsureIndex(ops, new Index()
                        .on("userId", Sort.Direction.ASC)
                        .on("gameModeType", Sort.Direction.ASC)
                        .on("status", Sort.Direction.ASC)
                        .unique()
                        .partial(PartialIndexFilter.of(
                                Criteria.where("gameModeType").is(GameModeType.LIGHTNING.value())
                                        .and("status").in(QuizStatus.PREPARED.value(), QuizStatus.RUNNING.value()))),
                "QuizRun.unique_active_lightning_per_user");

        // UNIQUE: Only one active surf mode run per user
        safeEnsureIndex(ops, new Index()
                        .on("userId", Sort.Direction.ASC)
                        .on("gameModeType", Sort.Direction.ASC)
                        .on("status", Sort.Direction.ASC)
                        .unique()
                        .partial(PartialIndexFilter.of(
                                Criteria.where("gameModeType").is(GameModeType.SURF.value())
                                        .and("status").in(QuizStatus.PREPARED.value(), QuizStatus.RUNNING.value()))),
                "QuizRun.unique_active_surf_per_user");

        // UNIQUE: Only one active pretest run per user/level/operation
        safeEnsureIndex(ops, new Index()
                        .on("userId", Sort.Direction.ASC)
                        .on("pretestMode", Sort.Direction.ASC)
                        .on("level", Sort.Direction.ASC)
                        .on("operation", Sort.Direction.ASC)
                        .on("status", Sort.Direction.ASC)
                        .unique()
                        .partial(PartialIndexFilter.of(
                                Criteria.where("pretestMode").is(true)
                                        .and("status").in(QuizStatus.PREPARED.value(), QuizStatus.RUNNING.value()))),
                "QuizRun.unique_active_pretest_per_user_level_op");
    }

    private void ensureAttemptIndexes() {
        IndexOperations ops = mongo.indexOps(Attempt.class);

        // Basic quiz run lookup
        safeEnsureIndex(ops, new Index().on("quizRunId", Sort.Direction.ASC),
                "Attempt.quizRunId");

        // PRIMARY ANALYTICS: User's performance on specific facts
        safeEnsureIndex(ops, new Index()
                        .on("userId", Sort.Direction.ASC)
                        .on("operation", Sort.Direction.ASC)
                        .on("a", Sort.Direction.ASC)
                        .on("b", Sort.Direction.ASC),
                "Attempt.userId_fact_analytics");

        // USER TIMELINE: Recent attempts for a user
        safeEnsureIndex(ops, new Index()
                        .on("userId", Sort.Direction.ASC)
                        .on("attemptedAt", Sort.Direction.DESC),
                "Attempt.userId_timeline");

        // LEVEL BREAKDOWN: User performance per level
        safeEnsureIndex(ops, new Index()
                        .on("userId", Sort.Direction.ASC)
                        .on("level", Sort.Direction.ASC)
                        .on("correct", Sort.Direction.ASC),
                "Attempt.userId_level_stats");

        // OPERATION BREAKDOWN: User performance per operation
        safeEnsureIndex(ops, new Index()
                        .on("userId", Sort.Direction.ASC)
                        .on("operation", Sort.Direction.ASC)
                        .on("correct", Sort.Direction.ASC),
                "Attempt.userId_operation_stats");

        // BELT BREAKDOWN: Performance per belt/degree
        safeEnsureIndex(ops, new Index()
                        .on("userId", Sort.Direction.ASC)
                        .on("level", Sort.Direction.ASC)
                        .on("beltOrDegree", Sort.Direction.ASC),
                "Attempt.userId_level_belt");

        // GAME MODE ANALYTICS
        safeEnsureIndex(ops, new Index()
                        .on("userId", Sort.Direction.ASC)
                        .on("gameMode", Sort.Direction.ASC)
                        .on("attemptedAt", Sort.Direction.DESC),
                "Attempt.userId_gameMode_timeline");

        // TIME RANGE QUERIES
        safeEnsureIndex(ops, new Index()
                        .on("userId", Sort.Direction.ASC)
                        .on("attemptedAt", Sort.Direction.ASC),
                "Attempt.userId_timerange");
    }

    private void ensureDailySummaryIndexes() {
        IndexOperations ops = mongo.indexOps(DailySummary.class);

        safeEnsureIndex(ops, new Index()
                        .on("userId", Sort.Direction.ASC)
                        .on("date", Sort.Direction.ASC)
                        .unique(),
                "DailySummary.userId_date_unique");
    }

    /**
     * Safely ensure an index exists, ignoring conflicts with existing indexes.
     * This handles the case where an index exists with a different name.
     */
    private void safeEnsureIndex(IndexOperations ops, Index index, String description) {
        try {
            ops.ensureIndex(index);
        } catch (Exception e) {
            // Index already exists (possibly with different name) - that's fine
            String msg = e.getMessage();
            if (msg != null && (msg.contains("IndexOptionsConflict") || msg.contains("already exists"))) {
                log.info("[INDEXES] Skipped (already exists): {}", description);
            } else {
                log.warn("[INDEXES] Warning for {}: {}", description, msg);
            }
        }
    }
}