package com.infinityisland.service;

import static com.infinityisland.service.QuizUtils.*;

import com.infinityisland.controller.QuizResponses.AnswerResponse;
import com.infinityisland.controller.QuizResponses.DailyStatsResponse;
import com.infinityisland.controller.QuizResponses.PrepareResponse;
import com.infinityisland.dao.GeneratedQuestion;
import com.infinityisland.dao.QuizRun;
import com.infinityisland.model.AttemptReason;
import com.infinityisland.model.Belt;
import com.infinityisland.model.GameModeType;
import com.infinityisland.model.Operation;
import com.infinityisland.model.QuizStatus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

@Component
public class RocketModeHandler {

    private static final Logger log = LoggerFactory.getLogger(RocketModeHandler.class);

    private final QuizHelper helper;
    private final CachedQuizRunService cachedQuizRuns;
    private final CachedQuestionService cachedQuestions;
    private final AttemptService attemptService;
    private final DailyService daily;
    private final GameConfigService gameConfig;
    private final ProgressionService progression;

    @Autowired
    public RocketModeHandler(QuizHelper helper,
                             CachedQuizRunService cachedQuizRuns,
                             CachedQuestionService cachedQuestions,
                             AttemptService attemptService,
                             DailyService daily,
                             GameConfigService gameConfig,
                             ProgressionService progression) {
        this.helper = helper;
        this.cachedQuizRuns = cachedQuizRuns;
        this.cachedQuestions = cachedQuestions;
        this.attemptService = attemptService;
        this.daily = daily;
        this.gameConfig = gameConfig;
        this.progression = progression;
    }

    public PrepareResponse prepareRocketMode(String userId, int lvl, String belt, String op) {
        // Check if surf mode is completed for this level/belt/operation
        boolean surfCompleted = cachedQuizRuns.isSurfModeCompleted(userId, lvl, belt, op);
        if (!surfCompleted) {
            throw new IllegalStateException("Surf mode must be completed before rocket mode");
        }

        // Check for existing active rocket run to resume
        Optional<QuizRun> existingRocket = cachedQuizRuns.findActiveRocketMode(userId, lvl, belt, op);
        if (existingRocket.isPresent()) {
            QuizRun run = existingRocket.get();
            log.info("[ROCKET] Resuming rocket quiz {} - Quiz {}/{}, Streak {}/{}", run.getId(), run.getRocketQuizNumber(), gameConfig.getRocketQuizzesRequired(), run.getRocketCorrectStreak(), gameConfig.getRocketQuestionsPerQuiz());

            PrepareResponse out = new PrepareResponse();
            out.quizRunId = run.getId();
            out.resumed = true;
            out.gameMode = true;
            out.gameModeType = GameModeType.ROCKET.value();
            out.rocketQuizNumber = nvl(run.getRocketQuizNumber(), 1);
            out.rocketCorrectStreak = nvl(run.getRocketCorrectStreak(), 0);
            out.completedRocketQuizzes = nvl(run.getCompletedRocketQuizzes(), 0);
            out.rocketQuizzesRequired = gameConfig.getRocketQuizzesRequired();
            out.currentIndex = Boolean.TRUE.equals(run.getRocketQuizFailed()) ? 0 : nvl(run.getCurrentIndex(), 0);
            out.level = run.getLevel();
            out.beltOrDegree = run.getBeltOrDegree();
            out.operation = run.getOperation();

            if (Boolean.TRUE.equals(run.getRocketQuizFailed())) {
                out.rocketQuizFailed = true;
                out.needsRestart = true;
            }

            out.practice = List.of();
            return out;
        }

        // Create new rocket mode quiz run
        QuizRun run = new QuizRun();
        run.setUserId(userId);
        run.setOperation(op);
        run.setLevel(lvl);
        run.setBeltOrDegree(belt);
        run.setStatus(QuizStatus.PREPARED.value());
        run.setCurrentIndex(0);
        run.setMainFlowCorrect(0);
        run.setWrong(0);
        run.setPassed(null);
        run.setTotalActiveMs(0L);

        // Rocket mode specific
        run.setGameMode(true);
        run.setGameModeType(GameModeType.ROCKET.value());
        run.setRocketQuizNumber(1);
        run.setRocketCorrectStreak(0);
        run.setCompletedRocketQuizzes(0);
        run.setRocketQuizFailures(0);
        run.setRocketQuizFailed(false);

        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        run.setCreatedAt(now);
        run.setUpdatedAt(now);

        helper.ensureTimer(run);
        helper.timerSetLimit(run, 0L);
        helper.timerSetRemaining(run, 0L);

        try {
            cachedQuizRuns.save(run);
            log.info("[ROCKET] Created new rocket run {} for user {}", run.getId(), userId);
        } catch (DuplicateKeyException e) {
            log.warn("[ROCKET] Race condition detected for user {}, returning existing run", userId);
            Optional<QuizRun> existing = cachedQuizRuns.findActiveRocketMode(userId, lvl, belt, op);
            if (existing.isPresent()) {
                QuizRun existingRun = existing.get();
                PrepareResponse out = new PrepareResponse();
                out.quizRunId = existingRun.getId();
                out.resumed = true;
                out.gameMode = true;
                out.gameModeType = GameModeType.ROCKET.value();
                out.rocketQuizNumber = nvl(existingRun.getRocketQuizNumber(), 1);
                out.rocketCorrectStreak = nvl(existingRun.getRocketCorrectStreak(), 0);
                out.completedRocketQuizzes = nvl(existingRun.getCompletedRocketQuizzes(), 0);
                out.rocketQuizzesRequired = gameConfig.getRocketQuizzesRequired();
                out.currentIndex = nvl(existingRun.getCurrentIndex(), 0);
                out.level = existingRun.getLevel();
                out.beltOrDegree = existingRun.getBeltOrDegree();
                out.operation = existingRun.getOperation();
                out.practice = List.of();
                return out;
            }
            throw e;
        }

        PrepareResponse out = new PrepareResponse();
        out.quizRunId = run.getId();
        out.resumed = false;
        out.gameMode = true;
        out.gameModeType = GameModeType.ROCKET.value();
        out.rocketQuizNumber = 1;
        out.rocketCorrectStreak = 0;
        out.completedRocketQuizzes = 0;
        out.rocketQuizzesRequired = gameConfig.getRocketQuizzesRequired();
        out.practice = List.of();
        return out;
    }

    public Object handleRocketModeAnswer(QuizRun run, String questionId, int answer, long responseMs) {
        GeneratedQuestion q = cachedQuestions.findById(questionId)
                .orElseThrow(() -> new IllegalArgumentException("Question not found"));

        // Inactivity check
        if (gameConfig.isAnswerInactive(responseMs, gameConfig.getInactivityThresholdMs())) {
            return rocketQuizFailed(run, q, answer, responseMs, AttemptReason.INACTIVITY.value());
        }

        // For rocket mode: correctAnswer is the 0-based index of the correct expression in textChoices
        boolean correct = (q.getCorrectAnswer() != null && q.getCorrectAnswer() == answer);

        attemptService.recordAttemptAsync(run, q, answer, correct, responseMs, AttemptReason.ANSWER.value());

        run.setTotalActiveMs(nvl(run.getTotalActiveMs(), 0L) + responseMs);

        if (!correct) {
            return rocketQuizFailed(run, q, answer, responseMs, "wrong");
        }

        run.setMainFlowCorrect(nvl(run.getMainFlowCorrect(), 0) + 1);
        run.setRocketCorrectStreak(nvl(run.getRocketCorrectStreak(), 0) + 1);

        log.info("[ROCKET] Correct! Streak: {}/{}, Quiz {}/{}", run.getRocketCorrectStreak(), gameConfig.getRocketQuestionsPerQuiz(), run.getRocketQuizNumber(), gameConfig.getRocketQuizzesRequired());

        DailyStatsResponse dailyStats = null;
        if (run.getUserId() != null) {
            try {
                dailyStats = daily.increment(run.getUserId(), 1, Math.max(0L, responseMs));
            } catch (Exception e) {
                log.error("Daily increment failed", e);
            }
        }

        if (run.getRocketCorrectStreak() >= gameConfig.getRocketQuestionsPerQuiz()) {
            return rocketQuizPassed(run, dailyStats);
        }

        int nextIndex = nvl(run.getCurrentIndex(), 0) + 1;
        run.setCurrentIndex(nextIndex);
        run.setUpdatedAt(helper.now());
        cachedQuizRuns.save(run);

        AnswerResponse resp = new AnswerResponse();
        resp.correct = true;
        resp.nextIndex = nextIndex;
        resp.gameMode = true;
        resp.gameModeType = GameModeType.ROCKET.value();
        resp.rocketCorrectStreak = run.getRocketCorrectStreak();
        resp.rocketQuizNumber = run.getRocketQuizNumber();
        resp.completedRocketQuizzes = run.getCompletedRocketQuizzes();
        resp.dailyStats = dailyStats;
        return resp;
    }

    private Object rocketQuizFailed(QuizRun run, GeneratedQuestion failedQuestion,
                                    int userAnswer, long responseMs, String reason) {
        log.info("[ROCKET] Quiz failed! Reason: {}, Quiz {}, Streak was {}", reason, run.getRocketQuizNumber(), run.getRocketCorrectStreak());

        attemptService.recordAttemptAsync(run, failedQuestion, userAnswer, false, responseMs, reason);

        run.setWrong(nvl(run.getWrong(), 0) + 1);
        run.setRocketQuizFailures(nvl(run.getRocketQuizFailures(), 0) + 1);

        run.setRocketQuizFailed(true);
        run.setRocketCorrectStreak(0);

        helper.touch(run);
        cachedQuizRuns.save(run);

        // Build forward practice question from the failed rocket question
        GeneratedQuestion practiceQ = helper.buildPracticeFromRocketQuestion(failedQuestion);

        AnswerResponse resp = new AnswerResponse();
        resp.rocketFailed = true;
        resp.reason = reason;
        resp.correctAnswer = failedQuestion.getCorrectAnswer();
        // Include the correct expression text so the UI can show it
        if (failedQuestion.getTextChoices() != null && !failedQuestion.getTextChoices().isEmpty()
                && failedQuestion.getCorrectAnswer() != null
                && failedQuestion.getCorrectAnswer() < failedQuestion.getTextChoices().size()) {
            resp.correctExpression = failedQuestion.getTextChoices().get(failedQuestion.getCorrectAnswer());
        }
        resp.practice = practiceQ;
        resp.gameMode = true;
        resp.gameModeType = GameModeType.ROCKET.value();
        resp.rocketQuizNumber = run.getRocketQuizNumber();
        resp.rocketQuizFailures = run.getRocketQuizFailures();
        return resp;
    }

    private Object rocketQuizPassed(QuizRun run, DailyStatsResponse dailyStats) {
        int completedQuizzes = nvl(run.getCompletedRocketQuizzes(), 0) + 1;
        run.setCompletedRocketQuizzes(completedQuizzes);
        run.setRocketCorrectStreak(0);

        log.info("[ROCKET] Quiz {} passed! Completed: {}/{}", run.getRocketQuizNumber(), completedQuizzes, gameConfig.getRocketQuizzesRequired());

        if (completedQuizzes >= gameConfig.getRocketQuizzesRequired()) {
            return completeRocketMode(run, dailyStats);
        }

        int nextQuizNumber = nvl(run.getRocketQuizNumber(), 1) + 1;
        run.setRocketQuizNumber(nextQuizNumber);
        run.setCurrentIndex(0);
        run.setRocketQuizFailed(false);

        List<GeneratedQuestion> newQuestions = buildRocketQuizSet(run);
        run.setItems(newQuestions.stream()
                .map(GeneratedQuestion::getId)
                .collect(Collectors.toList()));

        helper.touch(run);
        cachedQuizRuns.save(run);

        AnswerResponse resp = new AnswerResponse();
        resp.rocketQuizPassed = true;
        resp.completedRocketQuizzes = completedQuizzes;
        resp.rocketQuizzesRequired = gameConfig.getRocketQuizzesRequired();
        resp.nextRocketQuizNumber = nextQuizNumber;
        resp.gameMode = true;
        resp.gameModeType = GameModeType.ROCKET.value();
        resp.dailyStats = dailyStats;
        return resp;
    }

    private Object completeRocketMode(QuizRun run, DailyStatsResponse dailyStats) {
        // CONTRACT CHANGE: Rocket no longer awards the belt — Bonus does (chain is now
        // Lightning → Surf → Rocket → Bonus → belt). We mark the rocket run completed/passed
        // and signal `bonusRequired:true` so the client knows to start bonus mode next.
        log.info("[ROCKET] All {} quizzes completed. Bonus mode required for belt.", gameConfig.getRocketQuizzesRequired());

        run.setPassed(true);
        run.setStatus(QuizStatus.COMPLETED.value());
        helper.touch(run);
        cachedQuizRuns.save(run);
        cachedQuizRuns.forceFlush(run.getId());

        AnswerResponse out = new AnswerResponse();
        out.beltAwarded = false;
        out.bonusRequired = true;

        if (run.getUserId() != null) {
            try {
                dailyStats = daily.getForUser(run.getUserId());
            } catch (Exception e) {
                log.error("Failed to fetch daily stats", e);
            }
            daily.forceFlush(run.getUserId());
        }

        out.completed = true;
        out.passed = true;
        out.gameMode = true;
        out.gameModeType = GameModeType.ROCKET.value();
        out.completedRocketQuizzes = run.getCompletedRocketQuizzes();
        out.rocketQuizFailures = run.getRocketQuizFailures();
        out.summary = helper.summaryOf(run);
        out.sessionCorrectCount = nvl(run.getMainFlowCorrect(), 0);
        out.dailyStats = dailyStats;

        return out;
    }

    public List<GeneratedQuestion> buildRocketQuizSet(QuizRun run) {
        String belt = nvl(run.getBeltOrDegree(), Belt.WHITE.value());
        int lvl = safe(run.getLevel(), 1);
        String op = nvl(run.getOperation(), Operation.ADD.value());

        List<GeneratedQuestion> questions = new ArrayList<>();

        // Same-op pool
        List<int[]> sameOpPool = new ArrayList<>();
        int[] currentPair = helper.getCanonicalPair(op, lvl, belt);
        if (currentPair != null) {
            sameOpPool.add(currentPair);
        }
        sameOpPool.addAll(helper.getPreviousPool(op, lvl, belt));

        // Cross-op pool: full prerequisite chain (e.g., for mul: sub + add)
        List<Object[]> chainPool = helper.getFullPrerequisiteChainPool(op);

        // Combined selection pool: {String op, int a, int b}
        List<Object[]> combinedPool = new ArrayList<>();
        for (int[] p : sameOpPool) {
            combinedPool.add(new Object[]{op, p[0], p[1]});
        }
        combinedPool.addAll(chainPool);

        // Build prereq int[] pool for distractor generation
        List<int[]> prereqIntPool = new ArrayList<>();
        for (Object[] entry : chainPool) {
            prereqIntPool.add(new int[]{(int) entry[1], (int) entry[2]});
        }

        if (combinedPool.isEmpty()) {
            combinedPool.add(new Object[]{op, 1, 1});
            combinedPool.add(new Object[]{op, 2, 1});
            if (isCommutative(op)) {
                combinedPool.add(new Object[]{op, 1, 2});
            }
            combinedPool.add(new Object[]{op, 2, 2});
            sameOpPool.add(new int[]{1, 1});
            sameOpPool.add(new int[]{2, 1});
            sameOpPool.add(new int[]{2, 2});
        }

        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        for (int i = 0; i < gameConfig.getRocketQuestionsPerQuiz(); i++) {
            Object[] entry = combinedPool.get(rnd.nextInt(combinedPool.size()));
            String qOp = (String) entry[0];
            int a = (int) entry[1], b = (int) entry[2];

            if (a != b && isCommutative(qOp) && rnd.nextBoolean()) {
                int temp = a; a = b; b = temp;
            }

            // Use same-op distractor pool matching the question's operation
            List<int[]> distractorPool = qOp.equals(op) ? sameOpPool : prereqIntPool;
            if (distractorPool.isEmpty()) distractorPool = sameOpPool;

            GeneratedQuestion q = helper.buildRocketQuestion(qOp, lvl, belt, a, b, distractorPool);
            questions.add(q);
        }

        List<GeneratedQuestion> reordered = reorderNoConsecutiveDuplicates(questions);
        return cachedQuestions.saveAll(reordered);
    }

    public void autoRestartIfFailed(QuizRun run) {
        if (Boolean.TRUE.equals(run.getRocketQuizFailed())) {
            log.info("[ROCKET] Auto-restarting failed quiz on start() resume - Quiz {}", run.getRocketQuizNumber());
            run.setRocketQuizFailed(false);
            run.setRocketCorrectStreak(0);
            run.setCurrentIndex(0);
            List<GeneratedQuestion> freshQuestions = buildRocketQuizSet(run);
            run.setItems(freshQuestions.stream().map(GeneratedQuestion::getId).collect(Collectors.toList()));
            helper.touch(run);
            cachedQuizRuns.save(run);
        }
    }

    public Object rocketQuizRestart(QuizRun run) {
        log.info("[ROCKET] Restarting quiz {} with fresh questions", run.getRocketQuizNumber());

        run.setRocketQuizFailed(false);
        run.setRocketCorrectStreak(0);
        run.setCurrentIndex(0);

        List<GeneratedQuestion> freshQuestions = buildRocketQuizSet(run);
        run.setItems(freshQuestions.stream()
                .map(GeneratedQuestion::getId)
                .collect(Collectors.toList()));

        helper.touch(run);
        cachedQuizRuns.save(run);

        AnswerResponse resp = new AnswerResponse();
        resp.rocketQuizRestarted = true;
        resp.gameMode = true;
        resp.gameModeType = GameModeType.ROCKET.value();
        resp.rocketQuizNumber = run.getRocketQuizNumber();
        resp.completedRocketQuizzes = nvl(run.getCompletedRocketQuizzes(), 0);
        resp.questions = freshQuestions;
        return resp;
    }
}
