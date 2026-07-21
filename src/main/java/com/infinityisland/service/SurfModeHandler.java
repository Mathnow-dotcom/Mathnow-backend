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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

@Component
public class SurfModeHandler {

    private static final Logger log = LoggerFactory.getLogger(SurfModeHandler.class);

    private final QuizHelper helper;
    private final CachedQuizRunService cachedQuizRuns;
    private final CachedQuestionService cachedQuestions;
    private final AttemptService attemptService;
    private final DailyService daily;
    private final GameConfigService gameConfig;

    @Autowired
    public SurfModeHandler(QuizHelper helper,
                           CachedQuizRunService cachedQuizRuns,
                           CachedQuestionService cachedQuestions,
                           AttemptService attemptService,
                           DailyService daily,
                           GameConfigService gameConfig) {
        this.helper = helper;
        this.cachedQuizRuns = cachedQuizRuns;
        this.cachedQuestions = cachedQuestions;
        this.attemptService = attemptService;
        this.daily = daily;
        this.gameConfig = gameConfig;
    }

    public PrepareResponse prepareSurfMode(String userId, int lvl, String belt, String op) {
        boolean lightningCompleted = cachedQuizRuns.isLightningModeCompleted(userId, lvl, belt, op);
        if (!lightningCompleted) {
            throw new IllegalStateException("Lightning mode must be completed before surf mode");
        }

        Optional<QuizRun> existingSurf = cachedQuizRuns.findActiveSurfMode(userId, lvl, belt, op);
        if (existingSurf.isPresent()) {
            QuizRun run = existingSurf.get();
            log.info("[SURF] Resuming surf quiz {} - Quiz {}/{}, Streak {}/{}", run.getId(), run.getSurfQuizNumber(), gameConfig.getSurfQuizzesRequired(), run.getSurfCorrectStreak(), gameConfig.getSurfQuestionsPerQuiz());

            PrepareResponse out = new PrepareResponse();
            out.quizRunId = run.getId();
            out.resumed = true;
            out.gameMode = true;
            out.gameModeType = GameModeType.SURF.value();
            out.surfQuizNumber = nvl(run.getSurfQuizNumber(), 1);
            out.surfCorrectStreak = nvl(run.getSurfCorrectStreak(), 0);
            out.completedSurfQuizzes = nvl(run.getCompletedSurfQuizzes(), 0);
            out.surfQuizzesRequired = gameConfig.getSurfQuizzesRequired();
            out.currentIndex = Boolean.TRUE.equals(run.getSurfQuizFailed()) ? 0 : nvl(run.getCurrentIndex(), 0);
            out.level = run.getLevel();
            out.beltOrDegree = run.getBeltOrDegree();
            out.operation = run.getOperation();

            if (Boolean.TRUE.equals(run.getSurfQuizFailed())) {
                out.surfQuizFailed = true;
                out.needsRestart = true;
            }

            out.practice = List.of();
            return out;
        }

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

        run.setGameMode(true);
        run.setGameModeType(GameModeType.SURF.value());
        run.setSurfQuizNumber(1);
        run.setSurfCorrectStreak(0);
        run.setCompletedSurfQuizzes(0);
        run.setSurfQuizFailures(0);
        run.setSurfQuizFailed(false);

        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        run.setCreatedAt(now);
        run.setUpdatedAt(now);

        helper.ensureTimer(run);
        helper.timerSetLimit(run, 0L);
        helper.timerSetRemaining(run, 0L);

        try {
            cachedQuizRuns.save(run);
            log.info("[SURF] Created new surf run {} for user {}", run.getId(), userId);
        } catch (DuplicateKeyException e) {
            log.warn("[SURF] Race condition detected for user {}, returning existing run", userId);
            Optional<QuizRun> existing = cachedQuizRuns.findActiveSurfMode(userId, lvl, belt, op);
            if (existing.isPresent()) {
                QuizRun existingRun = existing.get();
                PrepareResponse out = new PrepareResponse();
                out.quizRunId = existingRun.getId();
                out.resumed = true;
                out.gameMode = true;
                out.gameModeType = GameModeType.SURF.value();
                out.surfQuizNumber = nvl(existingRun.getSurfQuizNumber(), 1);
                out.surfCorrectStreak = nvl(existingRun.getSurfCorrectStreak(), 0);
                out.completedSurfQuizzes = nvl(existingRun.getCompletedSurfQuizzes(), 0);
                out.surfQuizzesRequired = gameConfig.getSurfQuizzesRequired();
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
        out.gameModeType = GameModeType.SURF.value();
        out.surfQuizNumber = 1;
        out.surfCorrectStreak = 0;
        out.completedSurfQuizzes = 0;
        out.surfQuizzesRequired = gameConfig.getSurfQuizzesRequired();
        out.practice = List.of();
        return out;
    }

    public Object handleSurfModeAnswer(QuizRun run, String questionId, int answer, long responseMs) {
        GeneratedQuestion q = cachedQuestions.findById(questionId)
                .orElseThrow(() -> new IllegalArgumentException("Question not found"));

        if (gameConfig.isAnswerInactive(responseMs, gameConfig.getInactivityThresholdMs())) {
            return surfQuizFailed(run, q, answer, responseMs, AttemptReason.INACTIVITY.value());
        }

        boolean correct = (q.getCorrectAnswer() != null && q.getCorrectAnswer() == answer);

        attemptService.recordAttemptAsync(run, q, answer, correct, responseMs, AttemptReason.ANSWER.value());

        run.setTotalActiveMs(nvl(run.getTotalActiveMs(), 0L) + responseMs);

        if (!correct) {
            return surfQuizFailed(run, q, answer, responseMs, "wrong");
        }

        run.setMainFlowCorrect(nvl(run.getMainFlowCorrect(), 0) + 1);
        run.setSurfCorrectStreak(nvl(run.getSurfCorrectStreak(), 0) + 1);

        log.info("[SURF] Correct! Streak: {}/{}, Quiz {}/{}", run.getSurfCorrectStreak(), gameConfig.getSurfQuestionsPerQuiz(), run.getSurfQuizNumber(), gameConfig.getSurfQuizzesRequired());

        DailyStatsResponse dailyStats = null;
        if (run.getUserId() != null) {
            try {
                dailyStats = daily.increment(run.getUserId(), 1, Math.max(0L, responseMs));
            } catch (Exception e) {
                log.error("Daily increment failed", e);
            }
        }

        if (run.getSurfCorrectStreak() >= gameConfig.getSurfQuestionsPerQuiz()) {
            return surfQuizPassed(run, dailyStats);
        }

        int nextIndex = nvl(run.getCurrentIndex(), 0) + 1;
        run.setCurrentIndex(nextIndex);
        run.setUpdatedAt(helper.now());
        cachedQuizRuns.save(run);

        AnswerResponse resp = new AnswerResponse();
        resp.correct = true;
        resp.nextIndex = nextIndex;
        resp.gameMode = true;
        resp.gameModeType = GameModeType.SURF.value();
        resp.surfCorrectStreak = run.getSurfCorrectStreak();
        resp.surfQuizNumber = run.getSurfQuizNumber();
        resp.completedSurfQuizzes = run.getCompletedSurfQuizzes();
        resp.dailyStats = dailyStats;
        return resp;
    }

    private Object surfQuizFailed(QuizRun run, GeneratedQuestion failedQuestion,
                                  int userAnswer, long responseMs, String reason) {
        log.info("[SURF] Quiz failed! Reason: {}, Quiz {}, Streak was {}", reason, run.getSurfQuizNumber(), run.getSurfCorrectStreak());

        attemptService.recordAttemptAsync(run, failedQuestion, userAnswer, false, responseMs, reason);

        run.setWrong(nvl(run.getWrong(), 0) + 1);
        run.setSurfQuizFailures(nvl(run.getSurfQuizFailures(), 0) + 1);

        run.setSurfQuizFailed(true);
        run.setSurfCorrectStreak(0);

        helper.touch(run);
        cachedQuizRuns.save(run);

        GeneratedQuestion practiceQ = helper.buildPracticeFromSurfQuestion(failedQuestion);

        AnswerResponse resp = new AnswerResponse();
        resp.surfFailed = true;
        resp.reason = reason;
        resp.correctAnswer = failedQuestion.getCorrectAnswer();
        resp.practice = practiceQ;
        resp.gameMode = true;
        resp.gameModeType = GameModeType.SURF.value();
        resp.surfQuizNumber = run.getSurfQuizNumber();
        resp.surfQuizFailures = run.getSurfQuizFailures();
        return resp;
    }

    private Object surfQuizPassed(QuizRun run, DailyStatsResponse dailyStats) {
        int completedQuizzes = nvl(run.getCompletedSurfQuizzes(), 0) + 1;
        run.setCompletedSurfQuizzes(completedQuizzes);
        run.setSurfCorrectStreak(0);

        log.info("[SURF] Quiz {} passed! Completed: {}/{}", run.getSurfQuizNumber(), completedQuizzes, gameConfig.getSurfQuizzesRequired());

        if (completedQuizzes >= gameConfig.getSurfQuizzesRequired()) {
            return completeSurfMode(run, dailyStats);
        }

        int nextQuizNumber = nvl(run.getSurfQuizNumber(), 1) + 1;
        run.setSurfQuizNumber(nextQuizNumber);
        run.setCurrentIndex(0);
        run.setSurfQuizFailed(false);

        List<GeneratedQuestion> newQuestions = buildSurfQuizSet(run);
        run.setItems(newQuestions.stream()
                .map(GeneratedQuestion::getId)
                .collect(Collectors.toList()));

        helper.touch(run);
        cachedQuizRuns.save(run);

        AnswerResponse resp = new AnswerResponse();
        resp.surfQuizPassed = true;
        resp.completedSurfQuizzes = completedQuizzes;
        resp.surfQuizzesRequired = gameConfig.getSurfQuizzesRequired();
        resp.nextSurfQuizNumber = nextQuizNumber;
        resp.gameMode = true;
        resp.gameModeType = GameModeType.SURF.value();
        resp.dailyStats = dailyStats;
        return resp;
    }

    private Object completeSurfMode(QuizRun run, DailyStatsResponse dailyStats) {
        log.info("[SURF] All {} quizzes completed! Surf mode done - belt awarded after rocket mode.", gameConfig.getSurfQuizzesRequired());

        run.setPassed(true);
        run.setStatus(QuizStatus.COMPLETED.value());
        helper.touch(run);
        cachedQuizRuns.save(run);
        cachedQuizRuns.forceFlush(run.getId());

        AnswerResponse out = new AnswerResponse();

        out.beltAwarded = false;

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
        out.gameModeType = GameModeType.SURF.value();
        out.completedSurfQuizzes = run.getCompletedSurfQuizzes();
        out.surfQuizFailures = run.getSurfQuizFailures();
        out.summary = helper.summaryOf(run);
        out.sessionCorrectCount = nvl(run.getMainFlowCorrect(), 0);
        out.dailyStats = dailyStats;

        return out;
    }

    public List<GeneratedQuestion> buildSurfQuizSet(QuizRun run) {
        String belt = nvl(run.getBeltOrDegree(), Belt.WHITE.value());
        int lvl = safe(run.getLevel(), 1);
        String op = nvl(run.getOperation(), Operation.ADD.value());

        List<GeneratedQuestion> questions = new ArrayList<>();

        List<Object[]> pool = new ArrayList<>();

        int[] currentPair = helper.getCanonicalPair(op, lvl, belt);
        if (currentPair != null) {
            pool.add(new Object[]{op, currentPair[0], currentPair[1]});
        }

        for (int[] p : helper.getPreviousPool(op, lvl, belt)) {
            pool.add(new Object[]{op, p[0], p[1]});
        }

        pool.addAll(helper.getFullPrerequisiteChainPool(op));

        if (pool.isEmpty()) {
            pool.add(new Object[]{op, 1, 1});
            pool.add(new Object[]{op, 2, 1});
            if (isCommutative(op)) {
                pool.add(new Object[]{op, 1, 2});
            }
            pool.add(new Object[]{op, 2, 2});
        }

        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        for (int i = 0; i < gameConfig.getSurfQuestionsPerQuiz(); i++) {
            Object[] entry = pool.get(rnd.nextInt(pool.size()));
            String qOp = (String) entry[0];
            int a = (int) entry[1], b = (int) entry[2];

            if (a != b && isCommutative(qOp) && rnd.nextBoolean()) {
                int temp = a; a = b; b = temp;
            }

            GeneratedQuestion q = helper.buildSurfQuestion(qOp, lvl, belt, a, b);
            questions.add(q);
        }

        List<GeneratedQuestion> reordered = reorderNoConsecutiveDuplicates(questions);
        return cachedQuestions.saveAll(reordered);
    }

    public void autoRestartIfFailed(QuizRun run) {
        if (Boolean.TRUE.equals(run.getSurfQuizFailed())) {
            log.info("[SURF] Auto-restarting failed quiz on start() resume - Quiz {}", run.getSurfQuizNumber());
            run.setSurfQuizFailed(false);
            run.setSurfCorrectStreak(0);
            run.setCurrentIndex(0);
            List<GeneratedQuestion> freshQuestions = buildSurfQuizSet(run);
            run.setItems(freshQuestions.stream().map(GeneratedQuestion::getId).collect(Collectors.toList()));
            helper.touch(run);
            cachedQuizRuns.save(run);
        }
    }

    public Object surfQuizRestart(QuizRun run) {
        log.info("[SURF] Restarting quiz {} with fresh questions", run.getSurfQuizNumber());

        run.setSurfQuizFailed(false);
        run.setSurfCorrectStreak(0);
        run.setCurrentIndex(0);

        List<GeneratedQuestion> freshQuestions = buildSurfQuizSet(run);
        run.setItems(freshQuestions.stream()
                .map(GeneratedQuestion::getId)
                .collect(Collectors.toList()));

        helper.touch(run);
        cachedQuizRuns.save(run);

        AnswerResponse resp = new AnswerResponse();
        resp.surfQuizRestarted = true;
        resp.gameMode = true;
        resp.gameModeType = GameModeType.SURF.value();
        resp.surfQuizNumber = run.getSurfQuizNumber();
        resp.completedSurfQuizzes = nvl(run.getCompletedSurfQuizzes(), 0);
        resp.questions = freshQuestions;
        return resp;
    }
}
