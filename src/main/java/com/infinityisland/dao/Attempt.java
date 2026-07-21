package com.infinityisland.dao;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

@Document("attempts")
public class Attempt {
    @Id
    private String id;

    // === REFERENCES ===
    private String quizRunId;
    private String questionId;
    private String userId;  // Denormalized from QuizRun for direct queries

    // === FACT SNAPSHOT (denormalized for analytics) ===
    private String operation;     // "add", "sub", "mul", "div"
    private Integer a;            // First operand
    private Integer b;            // Second operand
    private Integer level;        // 1-7
    private String beltOrDegree;  // "white", "yellow", ..., "black-1" to "black-7"
    private String question;      // Display string: "2 + 3"

    // === ANSWER DATA ===
    private Integer userAnswer;
    private Integer correctAnswer;
    private List<Integer> choices;  // The 4 choices shown to user
    private Boolean correct;
    private Long responseMs;

    // === CONTEXT ===
    private Boolean gameMode;
    private String reason;        // "answer" | "inactivity" | "timeout"
    private Instant attemptedAt;

    // === CONSTRUCTORS ===
    public Attempt() {
        this.attemptedAt = Instant.now();
    }

    // === GETTERS & SETTERS ===
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getQuizRunId() {
        return quizRunId;
    }

    public void setQuizRunId(String quizRunId) {
        this.quizRunId = quizRunId;
    }

    public String getQuestionId() {
        return questionId;
    }

    public void setQuestionId(String questionId) {
        this.questionId = questionId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getOperation() {
        return operation;
    }

    public void setOperation(String operation) {
        this.operation = operation;
    }

    public Integer getA() {
        return a;
    }

    public void setA(Integer a) {
        this.a = a;
    }

    public Integer getB() {
        return b;
    }

    public void setB(Integer b) {
        this.b = b;
    }

    public Integer getLevel() {
        return level;
    }

    public void setLevel(Integer level) {
        this.level = level;
    }

    public String getBeltOrDegree() {
        return beltOrDegree;
    }

    public void setBeltOrDegree(String beltOrDegree) {
        this.beltOrDegree = beltOrDegree;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public Integer getUserAnswer() {
        return userAnswer;
    }

    public void setUserAnswer(Integer userAnswer) {
        this.userAnswer = userAnswer;
    }

    public Integer getCorrectAnswer() {
        return correctAnswer;
    }

    public void setCorrectAnswer(Integer correctAnswer) {
        this.correctAnswer = correctAnswer;
    }

    public List<Integer> getChoices() {
        return choices;
    }

    public void setChoices(List<Integer> choices) {
        this.choices = choices;
    }

    public Boolean getCorrect() {
        return correct;
    }

    public void setCorrect(Boolean correct) {
        this.correct = correct;
    }

    public Long getResponseMs() {
        return responseMs;
    }

    public void setResponseMs(Long responseMs) {
        this.responseMs = responseMs;
    }

    public Boolean getGameMode() {
        return gameMode;
    }

    public void setGameMode(Boolean gameMode) {
        this.gameMode = gameMode;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Instant getAttemptedAt() {
        return attemptedAt;
    }

    public void setAttemptedAt(Instant attemptedAt) {
        this.attemptedAt = attemptedAt;
    }

    // === BUILDER PATTERN FOR CLEANER CREATION ===
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final Attempt attempt = new Attempt();

        public Builder quizRunId(String quizRunId) {
            attempt.quizRunId = quizRunId;
            return this;
        }

        public Builder questionId(String questionId) {
            attempt.questionId = questionId;
            return this;
        }

        public Builder userId(String userId) {
            attempt.userId = userId;
            return this;
        }

        public Builder operation(String operation) {
            attempt.operation = operation;
            return this;
        }

        public Builder a(Integer a) {
            attempt.a = a;
            return this;
        }

        public Builder b(Integer b) {
            attempt.b = b;
            return this;
        }

        public Builder level(Integer level) {
            attempt.level = level;
            return this;
        }

        public Builder beltOrDegree(String beltOrDegree) {
            attempt.beltOrDegree = beltOrDegree;
            return this;
        }

        public Builder question(String question) {
            attempt.question = question;
            return this;
        }

        public Builder userAnswer(Integer userAnswer) {
            attempt.userAnswer = userAnswer;
            return this;
        }

        public Builder correctAnswer(Integer correctAnswer) {
            attempt.correctAnswer = correctAnswer;
            return this;
        }

        public Builder choices(List<Integer> choices) {
            attempt.choices = choices;
            return this;
        }

        public Builder correct(Boolean correct) {
            attempt.correct = correct;
            return this;
        }

        public Builder responseMs(Long responseMs) {
            attempt.responseMs = responseMs;
            return this;
        }

        public Builder gameMode(Boolean gameMode) {
            attempt.gameMode = gameMode;
            return this;
        }

        public Builder reason(String reason) {
            attempt.reason = reason;
            return this;
        }

        public Builder attemptedAt(Instant attemptedAt) {
            attempt.attemptedAt = attemptedAt;
            return this;
        }

        public Attempt build() {
            return attempt;
        }
    }
}