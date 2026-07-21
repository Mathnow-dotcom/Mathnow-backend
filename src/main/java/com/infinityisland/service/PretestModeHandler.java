package com.infinityisland.service;

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

import static com.infinityisland.service.QuizUtils.*;

@Component
public class PretestModeHandler {

    private static final Logger log = LoggerFactory.getLogger(PretestModeHandler.class);

    private final QuizHelper helper;
    private final CachedQuizRunService cachedQuizRuns;
    private final CachedQuestionService cachedQuestions;
    private final AttemptService attemptService;
    private final DailyService daily;
    private final GameConfigService gameConfig;
    private final ProgressionService progression;

    @Autowired
    public PretestModeHandler(QuizHelper helper,
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

    public PrepareResponse preparePretestMode(String userId, int lvl, String op) {
        Optional<QuizRun> existingPretest = cachedQuizRuns.findActivePretestMode(userId, lvl, op);
        if (existingPretest.isPresent()) {
            QuizRun run = existingPretest.get();
            log.info("[PRETEST] Resuming pretest {} for user {}", run.getId(), userId);

            PrepareResponse out = new PrepareResponse();
            out.quizRunId = run.getId();
            out.resumed = true;
            out.pretestMode = true;
            out.gameModeType = GameModeType.PRETEST.value();
            out.currentIndex = nvl(run.getCurrentIndex(), 0);
            out.mainFlowCorrect = nvl(run.getMainFlowCorrect(), 0);
            out.wrong = nvl(run.getWrong(), 0);
            out.level = run.getLevel();
            out.operation = run.getOperation();
            out.pretestTimeLimitMs = gameConfig.getPretestTimeLimitMs(run.getLevel());
            out.pretestQuestionCount = gameConfig.getPretestQuestionCount();
            out.practice = List.of();
            return out;
        }

        QuizRun run = new QuizRun();
        run.setUserId(userId);
        run.setOperation(op);
        run.setLevel(lvl);
        run.setBeltOrDegree(GameModeType.PRETEST.value());
        run.setStatus(QuizStatus.PREPARED.value());
        run.setCurrentIndex(0);
        run.setMainFlowCorrect(0);
        run.setWrong(0);
        run.setPassed(null);
        run.setTotalActiveMs(0L);

        run.setPretestMode(true);
        run.setGameMode(false);

        log.info("[PRETEST] Creating new pretest for user {} at level {} operation {}", userId, lvl, op);

        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        run.setCreatedAt(now);
        run.setUpdatedAt(now);

        helper.ensureTimer(run);
        long timeLimitMs = gameConfig.getPretestTimeLimitMs(lvl);
        helper.timerSetLimit(run, timeLimitMs);
        helper.timerSetRemaining(run, timeLimitMs);

        try {
            cachedQuizRuns.save(run);
        } catch (DuplicateKeyException e) {
            log.warn("[PRETEST] Race condition detected for user {} level {} op {}, returning existing run", userId, lvl, op);
            Optional<QuizRun> existing = cachedQuizRuns.findActivePretestMode(userId, lvl, op);
            if (existing.isPresent()) {
                QuizRun existingRun = existing.get();
                PrepareResponse out = new PrepareResponse();
                out.quizRunId = existingRun.getId();
                out.resumed = true;
                out.pretestMode = true;
                out.gameModeType = GameModeType.PRETEST.value();
                out.currentIndex = nvl(existingRun.getCurrentIndex(), 0);
                out.mainFlowCorrect = nvl(existingRun.getMainFlowCorrect(), 0);
                out.wrong = nvl(existingRun.getWrong(), 0);
                out.level = existingRun.getLevel();
                out.operation = existingRun.getOperation();
                out.pretestTimeLimitMs = gameConfig.getPretestTimeLimitMs(existingRun.getLevel());
                out.pretestQuestionCount = gameConfig.getPretestQuestionCount();
                out.practice = List.of();
                return out;
            }
            throw e;
        }

        List<GeneratedQuestion> practice = helper.buildPretestPracticeQuestions(op, lvl);

        PrepareResponse out = new PrepareResponse();
        out.quizRunId = run.getId();
        out.resumed = false;
        out.pretestMode = true;
        out.gameModeType = GameModeType.PRETEST.value();
        out.level = lvl;
        out.operation = op;
        out.pretestTimeLimitMs = timeLimitMs;
        out.pretestQuestionCount = gameConfig.getPretestQuestionCount();
        out.practice = practice;
        return out;
    }

    public Object handlePretestModeAnswer(QuizRun run, String questionId, int answer, long responseMs) {
        if (gameConfig.isAnswerInactive(responseMs, gameConfig.getPretestInactivityThresholdMs())) {
            GeneratedQuestion currentQuestion = cachedQuestions.findById(questionId)
                    .orElseThrow(() -> new IllegalArgumentException("Question not found"));
            attemptService.recordAttemptAsync(run, currentQuestion, answer, false, responseMs, AttemptReason.INACTIVITY.value());
            run.setWrong(nvl(run.getWrong(), 0) + 1);
            run.setStatus(QuizStatus.RUNNING.value());
            helper.touch(run);
            cachedQuizRuns.save(run);
            cachedQuizRuns.updateCache(run);

            AnswerResponse resp = new AnswerResponse();
            resp.practice = currentQuestion;
            resp.reason = AttemptReason.INACTIVITY.value();
            resp.pretestMode = true;
            resp.gameModeType = GameModeType.PRETEST.value();
            return resp;
        }

        GeneratedQuestion q = cachedQuestions.findById(questionId)
                .orElseThrow(() -> new IllegalArgumentException("Question not found"));

        boolean correct = (q.getCorrectAnswer() != null && q.getCorrectAnswer() == answer);
        attemptService.recordAttemptAsync(run, q, answer, correct, responseMs, AttemptReason.ANSWER.value());

        if (!correct) {
            run.setWrong(nvl(run.getWrong(), 0) + 1);
            helper.touch(run);
            cachedQuizRuns.updateCache(run);

            AnswerResponse resp = new AnswerResponse();
            resp.practice = q;
            resp.reason = "wrong";
            resp.pretestMode = true;
            resp.gameModeType = GameModeType.PRETEST.value();
            return resp;
        }

        long timeDelta = responseMs;
        if (!run.isPretestMode()) {
            run.setTotalActiveMs(nvl(run.getTotalActiveMs(), 0L) + timeDelta);
        }
        run.setMainFlowCorrect(nvl(run.getMainFlowCorrect(), 0) + 1);
        run.setCurrentIndex(nvl(run.getCurrentIndex(), 0) + 1);
        run.setUpdatedAt(helper.now());

        cachedQuizRuns.updateCache(run);

        boolean isComplete = helper.isPretestComplete(run);

        DailyStatsResponse dailyStats = null;
        if (run.getUserId() != null) {
            try {
                dailyStats = daily.increment(run.getUserId(), 1, Math.max(0L, timeDelta));
            } catch (Exception e) {
                log.error("Daily increment failed", e);
            }
        }

        if (isComplete) {
            return completePretestMode(run, dailyStats);
        }

        AnswerResponse resp = new AnswerResponse();
        resp.nextIndex = nvl(run.getCurrentIndex(), 0);
        resp.pretestMode = true;
        resp.gameModeType = GameModeType.PRETEST.value();
        resp.dailyStats = dailyStats;
        return resp;
    }

    public Object completePretestMode(QuizRun run, DailyStatsResponse dailyStats) {
        log.info("[PRETEST] Completing pretest for user {}", run.getUserId());

        helper.pauseTimer(run);

        boolean noErrors = nvl(run.getWrong(), 0) == 0;
        long timeLimit = gameConfig.getPretestTimeLimitMs(run.getLevel());
        long totalTime = nvl(run.getTotalActiveMs(), 0L);
        boolean withinTimeLimit = totalTime <= timeLimit;
        boolean passed = noErrors && withinTimeLimit;

        log.info("[PRETEST] Results - Errors: {}, Time: {}ms / {}ms, Passed: {}", run.getWrong(), totalTime, timeLimit, passed);

        run.setPassed(passed);
        run.setStatus(QuizStatus.COMPLETED.value());
        helper.touch(run);
        cachedQuizRuns.save(run);
        cachedQuizRuns.forceFlush(run.getId());

        AnswerResponse out = new AnswerResponse();

        progression.markPretestTaken(run.getUserId(), run.getOperation(), run.getLevel(), passed);

        if (passed) {
            try {
                Map<String, Object> fullProgress = progression.awardEntireLevel(
                        run.getUserId(), run.getOperation(), run.getLevel()
                );
                out.updatedProgress = fullProgress;
                out.levelAwarded = true;
                log.info("[PRETEST] Level {} awarded for operation {}", run.getLevel(), run.getOperation());
            } catch (Exception e) {
                log.error("Progression award failed", e);
                out.levelAwarded = false;
            }
        } else {
            out.levelAwarded = false;
            out.failReason = !noErrors ? "errors" : "time";
            log.info("[PRETEST] Pretest failed - user must go through normal progression");
        }

        if (run.getUserId() != null) {
            try {
                dailyStats = daily.getForUser(run.getUserId());
            } catch (Exception e) {
                log.error("Failed to fetch daily stats", e);
            }
            daily.forceFlush(run.getUserId());
        }

        out.completed = true;
        out.passed = passed;
        out.pretestMode = true;
        out.gameModeType = GameModeType.PRETEST.value();
        out.summary = helper.summaryOf(run);
        out.sessionCorrectCount = nvl(run.getMainFlowCorrect(), 0);
        out.totalTimeMs = totalTime;
        out.timeLimitMs = timeLimit;
        out.dailyStats = dailyStats;

        return out;
    }

    public List<GeneratedQuestion> buildPretestQuizSet(QuizRun run) {
        int lvl = safe(run.getLevel(), 1);
        String op = nvl(run.getOperation(), Operation.ADD.value());

        int totalQuestions = gameConfig.getPretestQuestionCount();
        List<GeneratedQuestion> questions = new ArrayList<>(totalQuestions);

        List<String> belts = Belt.COLORED_ORDER;
        List<int[]> currentLevelPairs = new ArrayList<>();
        for (String b : belts) {
            int[] pair = helper.getCanonicalPair(op, lvl, b);
            if (pair != null) {
                currentLevelPairs.add(pair);
            }
        }

        Set<String> seen = new LinkedHashSet<>();
        List<int[]> uniquePairs = new ArrayList<>();
        for (int[] pair : currentLevelPairs) {
            if (seen.add(pair[0] + "," + pair[1])) {
                uniquePairs.add(pair);
            }
        }
        currentLevelPairs = uniquePairs;

        for (int[] pair : currentLevelPairs) {
            int a = pair[0], b = pair[1];
            questions.add(helper.buildQuestionObject(op, lvl, GameModeType.PRETEST.value(), a, b, "pretest", null));
            if (a != b && isCommutative(op)) {
                questions.add(helper.buildQuestionObject(op, lvl, GameModeType.PRETEST.value(), b, a, "pretest", null));
            }
        }

        // L1 digit-recognition (random digit, 0 pair) is an addition-only construct (v1.7).
        // Mul/sub/div pretests at L1 pull from the canonical catalog facts only.
        int numDigitQuestions = (lvl == 1 && Operation.ADD.value().equalsIgnoreCase(op)) ? 3 : 0;
        int questionsNeeded = totalQuestions - numDigitQuestions;
        int alreadyIncluded = questions.size();
        int remainingNeeded = questionsNeeded - alreadyIncluded;

        if (remainingNeeded > 0) {
            List<int[]> fillPool = new ArrayList<>(currentLevelPairs);

            for (int l = 1; l < lvl; l++) {
                for (String b : belts) {
                    int[] pair = helper.getCanonicalPair(op, l, b);
                    if (pair != null) {
                        fillPool.add(pair);
                    }
                }
            }

            if (!fillPool.isEmpty()) {
                ThreadLocalRandom rnd = ThreadLocalRandom.current();
                for (int i = 0; i < remainingNeeded; i++) {
                    int[] pair = fillPool.get(rnd.nextInt(fillPool.size()));
                    int a = pair[0], b = pair[1];

                    if (a != b && isCommutative(op) && rnd.nextBoolean()) {
                        int temp = a; a = b; b = temp;
                    }

                    questions.add(helper.buildQuestionObject(op, lvl, GameModeType.PRETEST.value(), a, b, "pretest", null));
                }
            }
        }

        if (lvl == 1) {
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            for (int i = 0; i < numDigitQuestions; i++) {
                int digit = rnd.nextInt(10);
                questions.add(helper.buildDigitQuestionObject(op, lvl, GameModeType.PRETEST.value(), digit));
            }
        }

        Collections.shuffle(questions);
        List<GeneratedQuestion> reordered = reorderNoConsecutiveDuplicates(questions);

        log.info("[PRETEST] Built {} questions for level {} operation {}", reordered.size(), lvl, op);

        return cachedQuestions.saveAll(reordered);
    }
}
