package com.infinityisland.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for game mode settings.
 *
 * Configure in application.yml:
 *
 * game-mode:
 *   lightning:
 *     target-correct: 100
 *     fast-threshold-ms: 2000
 *   surf:
 *     questions-per-quiz: 4
 *     quizzes-required: 5
 *   inactivity-threshold-ms: 5000
 */
@Configuration
@ConfigurationProperties(prefix = "game-mode")
public class GameModeConfig {

    private Lightning lightning = new Lightning();
    private Surf surf = new Surf();
    private Rocket rocket = new Rocket();
    private long inactivityThresholdMs = 5000L;

    public static class Lightning {
        private int targetCorrect = 100;
        private long fastThresholdMs = 2000L;

        public int getTargetCorrect() {
            return targetCorrect;
        }

        public void setTargetCorrect(int targetCorrect) {
            this.targetCorrect = targetCorrect;
        }

        public long getFastThresholdMs() {
            return fastThresholdMs;
        }

        public void setFastThresholdMs(long fastThresholdMs) {
            this.fastThresholdMs = fastThresholdMs;
        }
    }

    public static class Surf {
        private int questionsPerQuiz = 4;
        private int quizzesRequired = 5;

        public int getQuestionsPerQuiz() {
            return questionsPerQuiz;
        }

        public void setQuestionsPerQuiz(int questionsPerQuiz) {
            this.questionsPerQuiz = questionsPerQuiz;
        }

        public int getQuizzesRequired() {
            return quizzesRequired;
        }

        public void setQuizzesRequired(int quizzesRequired) {
            this.quizzesRequired = quizzesRequired;
        }
    }

    public static class Rocket {
        private int questionsPerQuiz = 4;
        private int quizzesRequired = 5;

        public int getQuestionsPerQuiz() { return questionsPerQuiz; }
        public void setQuestionsPerQuiz(int questionsPerQuiz) { this.questionsPerQuiz = questionsPerQuiz; }

        public int getQuizzesRequired() { return quizzesRequired; }
        public void setQuizzesRequired(int quizzesRequired) { this.quizzesRequired = quizzesRequired; }
    }

    public Rocket getRocket() { return rocket; }
    public void setRocket(Rocket rocket) { this.rocket = rocket; }

    public Lightning getLightning() {
        return lightning;
    }

    public void setLightning(Lightning lightning) {
        this.lightning = lightning;
    }

    public Surf getSurf() {
        return surf;
    }

    public void setSurf(Surf surf) {
        this.surf = surf;
    }

    public long getInactivityThresholdMs() {
        return inactivityThresholdMs;
    }

    public void setInactivityThresholdMs(long inactivityThresholdMs) {
        this.inactivityThresholdMs = inactivityThresholdMs;
    }
}