package com.infinityisland.controller;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Typed response DTOs for quiz endpoints.
 * {@code @JsonInclude(NON_NULL)} ensures absent fields are omitted from JSON,
 * matching the previous {@code Map<String, Object>} serialization behavior.
 */
public final class QuizResponses {

    private QuizResponses() {}

    // -------- Prepare endpoint response --------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PrepareResponse {
        // Common fields
        public String quizRunId;
        public Boolean resumed;
        public Boolean gameMode;
        public Boolean pretestMode;
        public String gameModeType;
        public List<?> practice;

        // Position / progress
        public Integer currentIndex;
        public Integer mainFlowCorrect;
        public Integer wrong;
        public Integer level;
        public String beltOrDegree;
        public String operation;

        // Lightning mode
        public Integer targetCorrect;
        public Integer totalCorrect;

        // Surf mode
        public Integer surfQuizNumber;
        public Integer surfCorrectStreak;
        public Integer completedSurfQuizzes;
        public Integer surfQuizzesRequired;
        public Boolean surfQuizFailed;

        // Rocket mode
        public Integer rocketQuizNumber;
        public Integer rocketCorrectStreak;
        public Integer completedRocketQuizzes;
        public Integer rocketQuizzesRequired;
        public Boolean rocketQuizFailed;

        // Shared surf/rocket
        public Boolean needsRestart;

        // Bonus mode (counter fields intentionally omitted per PRD: "Don't show students the counter").
        // Only the configured target/interval are exposed so the client can size its progress UI if needed.
        public Integer bonusTargetCorrect;
        public Integer bonusVideoIntervalCorrect;
        public Boolean bonusInPractice;
        public Integer bonusCorrectStreak;

        // Pretest mode
        public Long pretestTimeLimitMs;
        public Integer pretestQuestionCount;
    }

    // -------- Start endpoint response --------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class StartResponse {
        // Common fields
        public String quizRunId;
        public List<?> questions;
        public Boolean resumed;
        public Integer currentIndex;
        public RunInfo run;
        public TimerInfo timer;

        // Mode flags
        public Boolean gameMode;
        public Boolean pretestMode;
        public String gameModeType;

        // Pretest
        public Long pretestTimeLimitMs;
        public Integer pretestQuestionCount;

        // Lightning mode
        public Integer targetCorrect;
        public Integer totalCorrect;

        // Surf mode
        public Integer surfQuizNumber;
        public Integer surfCorrectStreak;
        public Integer completedSurfQuizzes;
        public Integer surfQuizzesRequired;
        public Integer questionsPerQuiz;

        // Rocket mode
        public Integer rocketQuizNumber;
        public Integer rocketCorrectStreak;
        public Integer completedRocketQuizzes;
        public Integer rocketQuizzesRequired;

        // Bonus mode
        public Integer bonusTargetCorrect;
        public Integer bonusVideoIntervalCorrect;
        public Integer bonusCorrectStreak;
    }

    // -------- Answer / Practice / Inactivity response --------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AnswerResponse {
        // Core answer
        public Boolean correct;
        public Integer nextIndex;
        public Boolean gameMode;
        public Boolean pretestMode;
        public String gameModeType;
        public DailyStatsResponse dailyStats;

        // Practice / wrong answer
        public Object practice;
        public String reason;
        public Boolean stillPracticing;

        // Completion
        public Boolean completed;
        public Boolean passed;
        public QuizSummary summary;
        public Integer sessionCorrectCount;
        public Object updatedProgress;
        public Boolean beltAwarded;
        public Boolean levelAwarded;
        public Boolean forcePass;
        public Boolean pretestSkipped;
        public Integer actualCorrectBeforeForce;
        public Boolean rocketEmoji;
        public Long totalTimeMs;
        public Long timeLimitMs;
        public String failReason;

        // Duplicate
        public Boolean duplicate;

        // Surf mode
        public Boolean surfFailed;
        public Boolean surfQuizPassed;
        public Boolean surfQuizRestarted;
        public Integer surfCorrectStreak;
        public Integer surfQuizNumber;
        public Integer completedSurfQuizzes;
        public Integer surfQuizzesRequired;
        public Integer surfQuizFailures;
        public Integer nextSurfQuizNumber;

        // Rocket mode
        public Boolean rocketFailed;
        public Boolean rocketQuizPassed;
        public Boolean rocketQuizRestarted;
        public Integer rocketCorrectStreak;
        public Integer rocketQuizNumber;
        public Integer completedRocketQuizzes;
        public Integer rocketQuizzesRequired;
        public Integer rocketQuizFailures;
        public Integer nextRocketQuizNumber;
        public Integer correctAnswer;
        public String correctExpression;

        // Lightning mode
        public Integer totalCorrect;
        public Integer targetCorrect;
        public Boolean lightningComplete;
        public Boolean surfRequired;
        public Boolean slow;

        // Bonus mode — boolean signals only (counters never leave the server, per PRD).
        public Boolean showBonusVideo;        // true on every Nth correct (default every 4) but NOT on completion
        public Boolean bonusComplete;         // true on the answer that hit bonusTargetCorrect (default 20)
        public Boolean bonusRequired;         // emitted by Rocket completion to tell the client to start bonus mode
        public Boolean bonusFailed;           // true on wrong/inactivity in bonus (UI uses this to show practice)
        public Boolean bonusQuizRestarted;    // true after correct practice answer resumes the bonus quiz
        public Integer bonusCorrectStreak;    // current consecutive-correct count (0..bonusTargetCorrect)

        // Quiz restart (surf/rocket)
        public List<?> questions;

        // Practice resume
        public Boolean resume;
        public Object next;
    }

    // -------- Quiz Summary (nested in completion responses) --------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class QuizSummary {
        public Integer correct;
        public Integer wrong;
        public Long totalActiveMs;
        public Integer level;
        public String beltOrDegree;
        public Long sessionTotalMs;
        public Boolean pretestMode;
        public String gameModeType;
        public Long timeLimitMs;
        public Integer completedSurfQuizzes;
        public Integer surfQuizFailures;
        public Integer completedRocketQuizzes;
        public Integer rocketQuizFailures;
        public Integer totalCorrect;
        public Integer targetCorrect;
        public Integer bonusTargetCorrect;
        public Boolean gameMode;
    }

    // -------- Daily Stats response --------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DailyStatsResponse {
        public String _id;
        public String user;
        public String date;
        public Integer correctCount;
        public Long totalActiveMs;
        public Long grandTotal;
        public Long grandTotalActiveMs;
        public Integer currentStreak;
    }

    // -------- Progress response --------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ProgressResponse {
        public java.util.Map<String, Object> progress;
    }

    // -------- Nested types --------

    public record RunInfo(String id, String status, Integer currentIndex) {}
    public record TimerInfo(Long limitMs, Long remainingMs) {}
}
