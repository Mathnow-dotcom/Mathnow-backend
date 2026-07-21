package com.infinityisland.service;

import com.infinityisland.controller.QuizResponses.AnswerResponse;
import com.infinityisland.controller.QuizResponses.DailyStatsResponse;
import com.infinityisland.controller.QuizResponses.PrepareResponse;
import com.infinityisland.controller.QuizResponses.QuizSummary;
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
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

import static com.infinityisland.service.QuizUtils.*;

@Component
public class NormalModeHandler {

    private static final Logger log = LoggerFactory.getLogger(NormalModeHandler.class);

    private final QuizHelper helper;
    private final CachedQuizRunService cachedQuizRuns;
    private final CachedQuestionService cachedQuestions;
    private final AttemptService attemptService;
    private final DailyService daily;
    private final GameConfigService gameConfig;
    private final ProgressionService progression;

    @Autowired
    public NormalModeHandler(QuizHelper helper,
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

    public PrepareResponse prepareNormalMode(String userId, int lvl, String belt, String op) {
        Optional<QuizRun> existingRunning = cachedQuizRuns.findByUserIdAndStatus(userId, QuizStatus.RUNNING.value());
        if (existingRunning.isPresent()) {
            QuizRun run = existingRunning.get();
            if (run.getLevel() != null && run.getLevel() == lvl
                    && belt.equalsIgnoreCase(nvl(run.getBeltOrDegree(), ""))
                    && op.equalsIgnoreCase(nvl(run.getOperation(), ""))
                    && !run.isGameMode()) {

                PrepareResponse out = new PrepareResponse();
                out.quizRunId = run.getId();
                out.resumed = true;
                out.gameMode = false;
                out.currentIndex = nvl(run.getCurrentIndex(), 0);
                out.mainFlowCorrect = nvl(run.getMainFlowCorrect(), 0);
                out.wrong = nvl(run.getWrong(), 0);
                out.practice = List.of();
                return out;
            }
        }

        Optional<QuizRun> existingPrepared = cachedQuizRuns.findByUserIdAndStatus(userId, QuizStatus.PREPARED.value());
        if (existingPrepared.isPresent()) {
            QuizRun run = existingPrepared.get();
            if (run.getLevel() != null && run.getLevel() == lvl
                    && belt.equalsIgnoreCase(nvl(run.getBeltOrDegree(), ""))
                    && op.equalsIgnoreCase(nvl(run.getOperation(), ""))
                    && !run.isGameMode()) {

                PrepareResponse out = new PrepareResponse();
                out.quizRunId = run.getId();
                out.resumed = false;
                out.gameMode = false;
                out.practice = helper.buildPracticeQuestions(op, lvl, belt);
                return out;
            }
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
        run.setGameMode(false);

        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        run.setCreatedAt(now);
        run.setUpdatedAt(now);

        helper.ensureTimer(run);
        if (isBlack(belt)) {
            int degree = parseBlackDegree(belt);
            long limit = gameConfig.getBlackBeltTimerMs(degree);
            helper.timerSetLimit(run, limit);
            helper.timerSetRemaining(run, limit);
        } else {
            helper.timerSetLimit(run, 0L);
            helper.timerSetRemaining(run, 0L);
        }

        cachedQuizRuns.save(run);

        List<GeneratedQuestion> practice = helper.buildPracticeQuestions(op, lvl, belt);

        PrepareResponse out = new PrepareResponse();
        out.quizRunId = run.getId();
        out.resumed = false;
        out.gameMode = false;
        out.practice = practice;
        return out;
    }

    public Object handleNormalModeAnswer(QuizRun run, String questionId, int answer, long responseMs) {
        if (gameConfig.isAnswerInactive(responseMs, gameConfig.getInactivityThresholdMs())) {
            GeneratedQuestion currentQuestion = cachedQuestions.findById(questionId)
                    .orElseThrow(() -> new IllegalArgumentException("Question not found"));
            attemptService.recordAttemptAsync(run, currentQuestion, answer, false, responseMs, AttemptReason.INACTIVITY.value());
            run.setWrong(nvl(run.getWrong(), 0) + 1);
            run.setStatus(QuizStatus.RUNNING.value());
            helper.touch(run);
            cachedQuizRuns.save(run);
            cachedQuizRuns.updateCache(run);

            AnswerResponse inactResp = new AnswerResponse();
            inactResp.practice = currentQuestion;
            inactResp.reason = AttemptReason.INACTIVITY.value();
            return inactResp;
        }

        GeneratedQuestion q = cachedQuestions.findById(questionId)
                .orElseThrow(() -> new IllegalArgumentException("Question not found"));

        boolean correct = (q.getCorrectAnswer() != null && q.getCorrectAnswer() == answer);
        attemptService.recordAttemptAsync(run, q, answer, correct, responseMs, AttemptReason.ANSWER.value());

        if (!correct) {
            run.setWrong(nvl(run.getWrong(), 0) + 1);
            helper.touch(run);
            cachedQuizRuns.updateCache(run);

            AnswerResponse wrongResp = new AnswerResponse();
            wrongResp.practice = q;
            wrongResp.reason = "wrong";
            return wrongResp;
        }

        long timeDelta = responseMs;
        run.setTotalActiveMs(nvl(run.getTotalActiveMs(), 0L) + timeDelta);

        run.setMainFlowCorrect(nvl(run.getMainFlowCorrect(), 0) + 1);
        run.setCurrentIndex(nvl(run.getCurrentIndex(), 0) + 1);
        run.setUpdatedAt(helper.now());

        cachedQuizRuns.updateCache(run);

        boolean isComplete = helper.isComplete(run);

        DailyStatsResponse dailyStats = null;
        if (run.getUserId() != null) {
            try {
                dailyStats = daily.increment(run.getUserId(), 1, Math.max(0L, timeDelta));
            } catch (Exception e) {
                log.error("Daily increment failed", e);
            }
        }

        if (isComplete) {
            log.info("Quiz completing...");
            boolean passed = nvl(run.getWrong(), 0) == 0;

            if (isBlack(run.getBeltOrDegree())) {
                long limit = helper.timerGetLimit(run);
                if (run.getTotalActiveMs() > limit) {
                    passed = false;
                }
            }

            run.setPassed(passed);
            run.setStatus(QuizStatus.COMPLETED.value());
            helper.touch(run);
            cachedQuizRuns.save(run);
            cachedQuizRuns.forceFlush(run.getId());

            QuizSummary summary = helper.summaryOf(run);
            AnswerResponse out = new AnswerResponse();

            if (passed) {
                try {
                    Map<String, Object> fullProgress = progression.unlockOnPass(
                            run.getUserId(), run.getOperation(), run.getLevel(), run.getBeltOrDegree()
                    );
                    out.updatedProgress = fullProgress;
                } catch (Exception ignore) {
                    log.error("Progression unlock failed", ignore);
                }
            }

            if (run.getUserId() != null) {
                try {
                    dailyStats = daily.getForUser(run.getUserId());
                } catch (Exception e) {
                    log.error("Failed to fetch daily stats", e);
                }
            }

            daily.forceFlush(run.getUserId());

            out.completed = true;
            out.passed = passed;
            out.gameMode = false;
            out.summary = summary;
            out.sessionCorrectCount = nvl(run.getMainFlowCorrect(), 0);
            out.dailyStats = dailyStats;

            return out;
        }

        AnswerResponse resp = new AnswerResponse();
        resp.nextIndex = nvl(run.getCurrentIndex(), 0);
        resp.gameMode = false;
        resp.dailyStats = dailyStats;
        return resp;
    }

    public List<GeneratedQuestion> buildQuizSet(QuizRun run) {
        String belt = nvl(run.getBeltOrDegree(), Belt.WHITE.value());
        int lvl = safe(run.getLevel(), 1);
        String op = nvl(run.getOperation(), Operation.ADD.value());

        List<GeneratedQuestion> questions = new ArrayList<>();

        // L1-white digit-introduction (a + 0, digit-recognition style) is curriculum-correct
        // only for addition. Subtraction and division previously fell through; multiplication
        // also falls through as of v1.7 (the mul curriculum introduces 0×N at canonical L1
        // belt slots via the seed, not via random digit picking).
        if (lvl == 1 && Belt.WHITE.value().equals(belt) && Operation.ADD.value().equalsIgnoreCase(op)) {
            questions.add(helper.buildQuestionObject(op, lvl, belt, 0, 0, "current", buildQuestionText(op, 0, 0)));
            questions.add(helper.buildQuestionObject(op, lvl, belt, 0, 0, "current", buildQuestionText(op, 0, 0)));

            for (int i = 0; i < 8; i++) {
                int digit = ThreadLocalRandom.current().nextInt(10);
                questions.add(helper.buildDigitQuestionObject(op, lvl, belt, digit));
            }

            return cachedQuestions.saveAll(questions);
        }

        if (isBlack(belt)) {
            questions = buildBlackBeltQuiz(run);
            Collections.shuffle(questions);
            List<GeneratedQuestion> reordered = reorderNoConsecutiveDuplicates(questions);
            return cachedQuestions.saveAll(reordered);
        }

        int[] pair = helper.getCanonicalPair(op, lvl, belt);
        if (pair == null) return questions;

        int a = pair[0], b = pair[1];
        boolean isIdentical = (a == b);

        if (isIdentical) {
            questions.add(helper.buildQuestionObject(op, lvl, belt, a, b, "current", null));
            questions.add(helper.buildQuestionObject(op, lvl, belt, a, b, "current", null));
        } else if (isCommutative(op)) {
            questions.add(helper.buildQuestionObject(op, lvl, belt, a, b, "current", null));
            questions.add(helper.buildQuestionObject(op, lvl, belt, a, b, "current", null));
            questions.add(helper.buildQuestionObject(op, lvl, belt, b, a, "current", null));
            questions.add(helper.buildQuestionObject(op, lvl, belt, b, a, "current", null));
        } else {
            questions.add(helper.buildQuestionObject(op, lvl, belt, a, b, "current", null));
            questions.add(helper.buildQuestionObject(op, lvl, belt, a, b, "current", null));
            questions.add(helper.buildQuestionObject(op, lvl, belt, a, b, "current", null));
            questions.add(helper.buildQuestionObject(op, lvl, belt, a, b, "current", null));
        }

        List<int[]> prevPool = helper.getPreviousPool(op, lvl, belt);
        List<Object[]> prereqChainPool = helper.getFullPrerequisiteChainPool(op);

        int previousNeeded = isIdentical ? 8 : 6;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        for (int i = 0; i < previousNeeded; i++) {
            if (!prevPool.isEmpty() && !prereqChainPool.isEmpty()) {
                if (rnd.nextBoolean()) {
                    int[] p = prevPool.get(rnd.nextInt(prevPool.size()));
                    questions.add(helper.buildQuestionObject(op, lvl, belt, p[0], p[1], "previous", null));
                } else {
                    Object[] entry = prereqChainPool.get(rnd.nextInt(prereqChainPool.size()));
                    String pOp = (String) entry[0];
                    int pa = (int) entry[1], pb = (int) entry[2];
                    if (pa != pb && isCommutative(pOp) && rnd.nextBoolean()) {
                        questions.add(helper.buildQuestionObject(pOp, lvl, belt, pb, pa, "previous", null));
                    } else {
                        questions.add(helper.buildQuestionObject(pOp, lvl, belt, pa, pb, "previous", null));
                    }
                }
            } else if (!prevPool.isEmpty()) {
                int[] p = prevPool.get(rnd.nextInt(prevPool.size()));
                questions.add(helper.buildQuestionObject(op, lvl, belt, p[0], p[1], "previous", null));
            } else if (!prereqChainPool.isEmpty()) {
                Object[] entry = prereqChainPool.get(rnd.nextInt(prereqChainPool.size()));
                String pOp = (String) entry[0];
                int pa = (int) entry[1], pb = (int) entry[2];
                if (pa != pb && isCommutative(pOp) && rnd.nextBoolean()) {
                    questions.add(helper.buildQuestionObject(pOp, lvl, belt, pb, pa, "previous", null));
                } else {
                    questions.add(helper.buildQuestionObject(pOp, lvl, belt, pa, pb, "previous", null));
                }
            }
        }

        Collections.shuffle(questions);
        List<GeneratedQuestion> reordered = reorderNoConsecutiveDuplicates(questions);
        return cachedQuestions.saveAll(reordered);
    }

    private List<GeneratedQuestion> buildBlackBeltQuiz(QuizRun run) {
        int lvl = safe(run.getLevel(), 1);
        String belt = nvl(run.getBeltOrDegree(), "black-1");
        String op = nvl(run.getOperation(), Operation.ADD.value());

        int totalQuestions = BLACK_COUNT;
        List<GeneratedQuestion> questions = new ArrayList<>(totalQuestions);

        List<String> belts = Belt.COLORED_ORDER;
        List<int[]> currentLevelPairs = new ArrayList<>();
        for (String b : belts) {
            int[] pair = helper.getCanonicalPair(op, lvl, b);
            if (pair != null) {
                currentLevelPairs.add(pair);
            }
        }

        // Deduplicate pairs
        Set<String> seenPairs = new LinkedHashSet<>();
        List<int[]> uniqueLevelPairs = new ArrayList<>();
        for (int[] pair : currentLevelPairs) {
            if (seenPairs.add(pair[0] + "," + pair[1])) {
                uniqueLevelPairs.add(pair);
            }
        }
        currentLevelPairs = uniqueLevelPairs;

        for (int[] pair : currentLevelPairs) {
            int a = pair[0], b = pair[1];
            questions.add(helper.buildQuestionObject(op, lvl, belt, a, b, "current", null));
            if (a != b && isCommutative(op)) {
                questions.add(helper.buildQuestionObject(op, lvl, belt, b, a, "current", null));
            }
        }

        // L1 digit-recognition fillers are an addition-only construct (v1.7).
        int numDigitQuestions = (lvl == 1 && Operation.ADD.value().equalsIgnoreCase(op)) ? 3 : 0;
        int questionsNeeded = totalQuestions - numDigitQuestions;
        int alreadyIncluded = questions.size();
        int remainingNeeded = questionsNeeded - alreadyIncluded;

        if (remainingNeeded > 0) {
            List<int[]> fillPool = new ArrayList<>();

            for (int l = 1; l < lvl; l++) {
                for (String b : belts) {
                    int[] pair = helper.getCanonicalPair(op, l, b);
                    if (pair != null) {
                        fillPool.add(pair);
                    }
                }
            }

            List<Object[]> crossOpChainPool = helper.getFullPrerequisiteChainPool(op);

            String source = "previous";
            if (fillPool.isEmpty() && crossOpChainPool.isEmpty()) {
                fillPool = currentLevelPairs;
                source = "current";
            }

            if (!fillPool.isEmpty() || !crossOpChainPool.isEmpty()) {
                ThreadLocalRandom rnd = ThreadLocalRandom.current();
                for (int i = 0; i < remainingNeeded; i++) {
                    if (!crossOpChainPool.isEmpty() && (fillPool.isEmpty() || rnd.nextInt(5) == 0)) {
                        Object[] entry = crossOpChainPool.get(rnd.nextInt(crossOpChainPool.size()));
                        String reviewOp = (String) entry[0];
                        int a = (int) entry[1], b = (int) entry[2];
                        if (a != b && isCommutative(reviewOp) && rnd.nextBoolean()) {
                            int temp = a; a = b; b = temp;
                        }
                        questions.add(helper.buildQuestionObject(reviewOp, lvl, belt, a, b, "review", null));
                    } else if (!fillPool.isEmpty()) {
                        int[] pair = fillPool.get(rnd.nextInt(fillPool.size()));
                        int a = pair[0], b = pair[1];
                        if (a != b && isCommutative(op) && rnd.nextBoolean()) {
                            int temp = a; a = b; b = temp;
                        }
                        questions.add(helper.buildQuestionObject(op, lvl, belt, a, b, source, null));
                    }
                }
            }
        }

        if (lvl == 1) {
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            for (int i = 0; i < numDigitQuestions; i++) {
                int digit = rnd.nextInt(10);
                questions.add(helper.buildDigitQuestionObject(op, lvl, belt, digit));
            }
        }

        return questions;
    }
}
