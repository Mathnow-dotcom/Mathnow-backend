package com.infinityisland.controller;

import com.infinityisland.dao.GeneratedQuestion;

import java.util.List;
import java.util.Map;

/**
 * DTOs returned/accepted by /quiz endpoints.
 * Field names mirror the Node backend so the React client works unchanged.
 */
public final class QuizDtos {
    // --------- Requests ----------
    public record PrepareRequest(Integer level, String beltOrDegree, String operation,
                                    Boolean gameMode, String gameModeType,
                                    Integer targetCorrect) {
    }

    public record StartRequest(String quizRunId) {
    }

    public record AnswerRequest(String quizRunId,
                                String questionId,
                                Integer answer,
                                Long responseMs,
                                Boolean forcePass,
                                Boolean skipLevelAward) {
    }

    public record InactivityRequest(String quizRunId, String questionId) {
    }

    public record PracticeAnswerRequest(String quizRunId, String questionId, Integer answer) {
    }

    public record CompleteRequest(String quizRunId) {
    }

    // --------- Shared ----------

    /**
     * Matches frontend expectation: dailyStats.correctCount & dailyStats.totalActiveMs (and optional extras).
     */
    public record DailyStats(Long correctCount,
                             Long totalActiveMs,
                             Long grandTotal,
                             Integer currentStreak) {
        public DailyStats(Long correctCount, Long totalActiveMs) {
            this(correctCount, totalActiveMs, null, null);
        }
    }

    public record QuestionDto(
            String id,
            String operation,
            Integer level,
            String beltOrDegree,
            Integer a,
            Integer b,
            String question,
            Integer correctAnswer,
            List<Integer> choices
    ) {
    }

    // --------- Responses ----------
    public record PrepareResponse(String quizRunId, List<GeneratedQuestion> practice) {
    }

    public record StartResponse(
            Run run,
            List<QuestionDto> questions
    ) {
        public record Run(String id, String status, Integer currentIndex) {
        }
    }

    /**
     * Kept for backward-compat but the client mainly checks for presence of fields like
     * practice / completed / nextIndex / dailyStats.
     */
    public record AnswerResponse(
            boolean correct,
            Integer nextIndex,
            boolean done,
            DailyStats dailyStats,
            Map<String, Object> progression // reserved: kept to mirror Node signature even if empty
    ) {
    }

    public record InactivityResponse(
            boolean practiceRequired,
            QuestionDto practiceQuestion
    ) {
    }

    public record PracticeAnswerResponse(
            boolean correct,
            Integer nextIndex
    ) {
    }

    public record CompleteResponse(
            boolean completed,
            Result result
    ) {
        public record Result(int correct, int wrong, long totalActiveMs, boolean passed) {
        }
    }
}