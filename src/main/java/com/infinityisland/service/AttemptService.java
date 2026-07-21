package com.infinityisland.service;

import com.infinityisland.dao.Attempt;
import com.infinityisland.dao.GeneratedQuestion;
import com.infinityisland.dao.QuizRun;
import com.infinityisland.model.AttemptReason;
import com.infinityisland.repositories.AttemptRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/**
 * Async service for persisting attempts.
 * All writes are non-blocking to keep the answer flow fast.
 */
@Service
public class AttemptService {

    private static final Logger log = LoggerFactory.getLogger(AttemptService.class);

    private final AttemptRepository attemptRepo;

    public AttemptService(AttemptRepository attemptRepo) {
        this.attemptRepo = attemptRepo;
    }

    /**
     * Record an attempt asynchronously.
     * Fire-and-forget - doesn't block the answer response.
     */
    @Async
    public CompletableFuture<Attempt> recordAttemptAsync(
            QuizRun run,
            GeneratedQuestion question,
            Integer userAnswer,
            Boolean correct,
            Long responseMs,
            String reason
    ) {
        try {
            Attempt attempt = buildAttempt(run, question, userAnswer, correct, responseMs, reason);
            Attempt saved = attemptRepo.save(attempt);

            log.debug("[ATTEMPT] Saved: user={} q={} correct={} ms={}", run.getUserId(),
                    question.getQuestion(), correct, responseMs);

            return CompletableFuture.completedFuture(saved);
        } catch (Exception e) {
            log.error("[ATTEMPT ERROR] Failed to save attempt: {}", e.getMessage(), e);
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * Record an inactivity attempt (wrong due to timeout).
     */
    @Async
    public CompletableFuture<Attempt> recordInactivityAsync(
            QuizRun run,
            GeneratedQuestion question
    ) {
        return recordAttemptAsync(run, question, null, false, null, AttemptReason.INACTIVITY.value());
    }

    /**
     * Build an Attempt from QuizRun and GeneratedQuestion.
     * Denormalizes all data needed for analytics.
     */
    private Attempt buildAttempt(
            QuizRun run,
            GeneratedQuestion question,
            Integer userAnswer,
            Boolean correct,
            Long responseMs,
            String reason
    ) {
        // Extract a, b from question params
        Integer a = null;
        Integer b = null;
        if (question.getParams() != null) {
            a = question.getParams().getA();
            b = question.getParams().getB();
        }

        return Attempt.builder()
                // References
                .quizRunId(run.getId())
                .questionId(question.getId())
                .userId(run.getUserId())

                // Fact snapshot (denormalized)
                .operation(question.getOperation() != null ? question.getOperation() : run.getOperation())
                .a(a)
                .b(b)
                .level(question.getLevel() != null ? question.getLevel() : run.getLevel())
                .beltOrDegree(question.getBeltOrDegree() != null ? question.getBeltOrDegree() : run.getBeltOrDegree())
                .question(question.getQuestion())

                // Answer data
                .userAnswer(userAnswer)
                .correctAnswer(question.getCorrectAnswer())
                .choices(question.getChoices())
                .correct(correct)
                .responseMs(responseMs)

                // Context
                .gameMode(run.isGameMode())
                .reason(reason != null ? reason : AttemptReason.ANSWER.value())
                .attemptedAt(Instant.now())

                .build();
    }

    /**
     * Sync save for cases where we need the result immediately.
     * Use sparingly - prefer async.
     */
    public Attempt recordAttemptSync(
            QuizRun run,
            GeneratedQuestion question,
            Integer userAnswer,
            Boolean correct,
            Long responseMs,
            String reason
    ) {
        Attempt attempt = buildAttempt(run, question, userAnswer, correct, responseMs, reason);
        return attemptRepo.save(attempt);
    }
}