package com.infinityisland.service;

import com.infinityisland.dao.Attempt;
import com.infinityisland.repositories.AttemptRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.*;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import static org.springframework.data.mongodb.core.aggregation.Aggregation.*;

@Service
public class AnalyticsService {

    private final AttemptRepository attemptRepo;
    private final MongoTemplate mongoTemplate;

    public AnalyticsService(AttemptRepository attemptRepo, MongoTemplate mongoTemplate) {
        this.attemptRepo = attemptRepo;
        this.mongoTemplate = mongoTemplate;
    }

    // ========== SUMMARY ENDPOINT ==========

    /**
     * Get high-level summary for a user across all levels/operations.
     */
    public Map<String, Object> getUserSummary(String userId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", userId);

        // Overall stats
        long totalAttempts = attemptRepo.countByUserId(userId);
        long totalCorrect = attemptRepo.countByUserIdAndCorrectTrue(userId);

        result.put("overall", Map.of(
                "totalAttempts", totalAttempts,
                "totalCorrect", totalCorrect,
                "accuracy", totalAttempts > 0 ? (double) totalCorrect / totalAttempts : 0.0
        ));

        // Per-level breakdown
        result.put("byLevel", getLevelBreakdown(userId));

        // Per-operation breakdown
        result.put("byOperation", getOperationBreakdown(userId));

        return result;
    }

    /**
     * Get breakdown by level using aggregation.
     */
    private List<Map<String, Object>> getLevelBreakdown(String userId) {
        Aggregation agg = newAggregation(
                match(Criteria.where("userId").is(userId)),
                group("level")
                        .count().as("totalAttempts")
                        .sum(ConditionalOperators.when(Criteria.where("correct").is(true))
                                .then(1).otherwise(0)).as("correctCount")
                        .avg("responseMs").as("avgResponseMs"),
                sort(Sort.Direction.ASC, "_id"),
                project()
                        .and("_id").as("level")
                        .and("totalAttempts").as("totalAttempts")
                        .and("correctCount").as("correctCount")
                        .and("avgResponseMs").as("avgResponseMs")
                        .andExpression("correctCount / totalAttempts").as("accuracy")
        );

        return mongoTemplate.aggregate(agg, "attempts", Map.class)
                .getMappedResults()
                .stream()
                .map(m -> (Map<String, Object>) m)
                .collect(Collectors.toList());
    }

    /**
     * Get breakdown by operation using aggregation.
     */
    private List<Map<String, Object>> getOperationBreakdown(String userId) {
        Aggregation agg = newAggregation(
                match(Criteria.where("userId").is(userId)),
                group("operation")
                        .count().as("totalAttempts")
                        .sum(ConditionalOperators.when(Criteria.where("correct").is(true))
                                .then(1).otherwise(0)).as("correctCount")
                        .avg("responseMs").as("avgResponseMs"),
                project()
                        .and("_id").as("operation")
                        .and("totalAttempts").as("totalAttempts")
                        .and("correctCount").as("correctCount")
                        .and("avgResponseMs").as("avgResponseMs")
                        .andExpression("correctCount / totalAttempts").as("accuracy")
        );

        return mongoTemplate.aggregate(agg, "attempts", Map.class)
                .getMappedResults()
                .stream()
                .map(m -> (Map<String, Object>) m)
                .collect(Collectors.toList());
    }

    // ========== FACT-LEVEL ANALYTICS ==========

    /**
     * Get detailed per-fact analytics with pagination.
     *
     * @param userId User ID
     * @param level Optional level filter (null = all levels)
     * @param operation Optional operation filter (null = all operations)
     * @param limit Max results (default 50)
     * @param offset Skip N results (default 0)
     */
    public Map<String, Object> getFactAnalytics(
            String userId,
            Integer level,
            String operation,
            int limit,
            int offset
    ) {
        // Build match criteria
        Criteria criteria = Criteria.where("userId").is(userId);
        if (level != null) {
            criteria = criteria.and("level").is(level);
        }
        if (operation != null && !operation.isBlank()) {
            criteria = criteria.and("operation").is(operation);
        }

        // Aggregation pipeline for fact-level stats
        Aggregation agg = newAggregation(
                match(criteria),

                // Group by fact (operation, a, b)
                group(Fields.fields()
                        .and("operation")
                        .and("a")
                        .and("b"))
                        .count().as("totalAttempts")
                        .sum(ConditionalOperators.when(Criteria.where("correct").is(true))
                                .then(1).otherwise(0)).as("correctCount")
                        .avg("responseMs").as("avgMs")
                        .min("responseMs").as("minMs")
                        .max("responseMs").as("maxMs")
                        .push("responseMs").as("allResponseMs")
                        .first("question").as("question")
                        // Collect ALL levels and belts where this fact appeared
                        .addToSet("level").as("levels")
                        .addToSet("beltOrDegree").as("beltsOrDegrees")
                        .max("attemptedAt").as("lastAttemptAt")
                        .min("attemptedAt").as("firstAttemptAt"),

                // Sort by most attempted first
                sort(Sort.Direction.DESC, "totalAttempts"),

                // Pagination
                skip((long) offset),
                limit(limit)
        );

        List<Map> rawResults = mongoTemplate.aggregate(agg, "attempts", Map.class)
                .getMappedResults();

        // Post-process to calculate median and format response
        List<Map<String, Object>> facts = rawResults.stream()
                .map(this::formatFactResult)
                .collect(Collectors.toList());

        // Get total count for pagination
        long totalFacts = getTotalFactCount(userId, level, operation);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", userId);
        result.put("filters", Map.of(
                "level", level != null ? level : "all",
                "operation", operation != null ? operation : "all"
        ));
        result.put("pagination", Map.of(
                "limit", limit,
                "offset", offset,
                "total", totalFacts,
                "hasMore", offset + facts.size() < totalFacts
        ));
        result.put("facts", facts);

        return result;
    }

    /**
     * Format a single fact aggregation result.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> formatFactResult(Map raw) {
        Map<String, Object> id = (Map<String, Object>) raw.get("_id");

        Map<String, Object> fact = new LinkedHashMap<>();
        fact.put("operation", id.get("operation"));
        fact.put("a", id.get("a"));
        fact.put("b", id.get("b"));
        fact.put("question", raw.get("question"));

        // Levels and belts are now arrays (same fact can appear across multiple)
        List<Integer> levels = (List<Integer>) raw.get("levels");
        List<String> beltsOrDegrees = (List<String>) raw.get("beltsOrDegrees");

        // Sort for consistent output
        if (levels != null) {
            levels = levels.stream().filter(Objects::nonNull).sorted().collect(Collectors.toList());
        }
        if (beltsOrDegrees != null) {
            beltsOrDegrees = beltsOrDegrees.stream().filter(Objects::nonNull).sorted().collect(Collectors.toList());
        }

        fact.put("levels", levels != null ? levels : List.of());
        fact.put("beltsOrDegrees", beltsOrDegrees != null ? beltsOrDegrees : List.of());

        int totalAttempts = ((Number) raw.get("totalAttempts")).intValue();
        int correctCount = ((Number) raw.get("correctCount")).intValue();

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalAttempts", totalAttempts);
        stats.put("correctCount", correctCount);
        stats.put("wrongCount", totalAttempts - correctCount);
        stats.put("accuracy", totalAttempts > 0 ? (double) correctCount / totalAttempts : 0.0);

        // Response time stats
        Object avgMs = raw.get("avgMs");
        stats.put("avgMs", avgMs != null ? ((Number) avgMs).longValue() : null);

        Object minMs = raw.get("minMs");
        stats.put("minMs", minMs != null ? ((Number) minMs).longValue() : null);

        Object maxMs = raw.get("maxMs");
        stats.put("maxMs", maxMs != null ? ((Number) maxMs).longValue() : null);

        // Calculate median from allResponseMs
        List<Number> allMs = (List<Number>) raw.get("allResponseMs");
        if (allMs != null && !allMs.isEmpty()) {
            List<Long> sortedMs = allMs.stream()
                    .filter(Objects::nonNull)
                    .map(Number::longValue)
                    .sorted()
                    .collect(Collectors.toList());

            if (!sortedMs.isEmpty()) {
                int mid = sortedMs.size() / 2;
                long median = sortedMs.size() % 2 == 0
                        ? (sortedMs.get(mid - 1) + sortedMs.get(mid)) / 2
                        : sortedMs.get(mid);
                stats.put("medianMs", median);
            }
        }

        stats.put("firstAttemptAt", raw.get("firstAttemptAt"));
        stats.put("lastAttemptAt", raw.get("lastAttemptAt"));

        // Mastery indicators
        boolean mastered = totalAttempts >= 5 && (double) correctCount / totalAttempts >= 0.9;
        boolean struggling = totalAttempts >= 3 && (double) correctCount / totalAttempts < 0.7;
        stats.put("mastered", mastered);
        stats.put("struggling", struggling);

        fact.put("stats", stats);
        return fact;
    }

    /**
     * Get total distinct fact count for pagination.
     */
    private long getTotalFactCount(String userId, Integer level, String operation) {
        Criteria criteria = Criteria.where("userId").is(userId);
        if (level != null) {
            criteria = criteria.and("level").is(level);
        }
        if (operation != null && !operation.isBlank()) {
            criteria = criteria.and("operation").is(operation);
        }

        Aggregation countAgg = newAggregation(
                match(criteria),
                group(Fields.fields().and("operation").and("a").and("b")),
                count().as("total")
        );

        AggregationResults<Map> results = mongoTemplate.aggregate(countAgg, "attempts", Map.class);
        List<Map> mapped = results.getMappedResults();

        if (mapped.isEmpty()) {
            return 0;
        }

        // The count stage returns a single document with total
        Object total = mapped.get(0).get("total");
        return total != null ? ((Number) total).longValue() : mapped.size();
    }

    // ========== SPECIFIC FACT LOOKUP ==========

    /**
     * Get detailed history for a specific fact.
     */
    public Map<String, Object> getFactDetail(
            String userId,
            String operation,
            Integer a,
            Integer b,
            int limit
    ) {
        List<Attempt> attempts = attemptRepo.findByUserIdAndFact(userId, operation, a, b);

        // Sort by most recent first
        attempts.sort((x, y) -> y.getAttemptedAt().compareTo(x.getAttemptedAt()));

        // Calculate stats
        int total = attempts.size();
        int correct = (int) attempts.stream().filter(Attempt::getCorrect).count();

        List<Long> responseTimes = attempts.stream()
                .map(Attempt::getResponseMs)
                .filter(Objects::nonNull)
                .sorted()
                .collect(Collectors.toList());

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalAttempts", total);
        stats.put("correctCount", correct);
        stats.put("wrongCount", total - correct);
        stats.put("accuracy", total > 0 ? (double) correct / total : 0.0);

        if (!responseTimes.isEmpty()) {
            stats.put("avgMs", responseTimes.stream().mapToLong(Long::longValue).average().orElse(0));
            stats.put("minMs", responseTimes.get(0));
            stats.put("maxMs", responseTimes.get(responseTimes.size() - 1));

            int mid = responseTimes.size() / 2;
            long median = responseTimes.size() % 2 == 0
                    ? (responseTimes.get(mid - 1) + responseTimes.get(mid)) / 2
                    : responseTimes.get(mid);
            stats.put("medianMs", median);
        }

        // Recent attempts (limited)
        List<Map<String, Object>> recentAttempts = attempts.stream()
                .limit(limit)
                .map(d -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("attemptedAt", d.getAttemptedAt());
                    m.put("correct", d.getCorrect());
                    m.put("userAnswer", d.getUserAnswer());
                    m.put("correctAnswer", d.getCorrectAnswer());
                    m.put("responseMs", d.getResponseMs());
                    m.put("gameMode", d.getGameMode());
                    m.put("choices", d.getChoices());
                    return m;
                })
                .collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", userId);
        result.put("fact", Map.of(
                "operation", operation,
                "a", a,
                "b", b,
                "question", QuizUtils.buildQuestionText(operation, a, b)
        ));
        result.put("stats", stats);
        result.put("recentAttempts", recentAttempts);

        return result;
    }

    // ========== STRUGGLING FACTS ==========

    /**
     * Get facts where the user is struggling (accuracy < 70%, min 3 attempts).
     */
    public Map<String, Object> getStrugglingFacts(String userId, Integer level, int limit) {
        Criteria criteria = Criteria.where("userId").is(userId);
        if (level != null) {
            criteria = criteria.and("level").is(level);
        }

        Aggregation agg = newAggregation(
                match(criteria),
                group(Fields.fields().and("operation").and("a").and("b"))
                        .count().as("totalAttempts")
                        .sum(ConditionalOperators.when(Criteria.where("correct").is(true))
                                .then(1).otherwise(0)).as("correctCount")
                        .first("question").as("question")
                        .first("level").as("level"),

                // Filter: at least 3 attempts, accuracy < 70%
                match(Criteria.where("totalAttempts").gte(3)),
                project()
                        .and("_id").as("fact")
                        .and("totalAttempts").as("totalAttempts")
                        .and("correctCount").as("correctCount")
                        .and("question").as("question")
                        .and("level").as("level")
                        .andExpression("correctCount / totalAttempts").as("accuracy"),

                match(Criteria.where("accuracy").lt(0.7)),
                sort(Sort.Direction.ASC, "accuracy"),
                limit(limit)
        );

        List<Map> results = mongoTemplate.aggregate(agg, "attempts", Map.class)
                .getMappedResults();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("userId", userId);
        response.put("level", level != null ? level : "all");
        response.put("strugglingFacts", results);

        return response;
    }

}