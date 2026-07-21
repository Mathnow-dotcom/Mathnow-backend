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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * BONUS MODE — Game Mode 4 (after Rocket).
 *
 * Per PRD ("Bonus Game"):
 *  - Goal: 20 consecutive correct answers (configurable via bonusTargetCorrect).
 *  - Every 4 in a row → emit showBonusVideo:true (configurable via bonusVideoIntervalCorrect).
 *  - No fail criteria — wrong answer resets streak to 0, shows correct answer + practice question,
 *    then resumes with a new question.
 *  - Multiple choice (same question composition as the colored/black belt for the slot).
 *  - Don't show the student the streak/star counter — server only emits boolean signals.
 *  - Belt is awarded on completion (Lightning → Surf → Rocket → Bonus → belt).
 *  - No timer (even on black belts) since there is no fail criteria.
 *
 * Per Q6: question pool is regenerated every {@code bonusQuestionsPerBatch} slots, so a student
 * who needs many attempts to reach 20-in-a-row sees fresh questions every batch instead of cycling
 * a fixed set.
 */
@Component
public class BonusModeHandler {

    private static final Logger log = LoggerFactory.getLogger(BonusModeHandler.class);

    private final QuizHelper helper;
    private final CachedQuizRunService cachedQuizRuns;
    private final CachedQuestionService cachedQuestions;
    private final AttemptService attemptService;
    private final DailyService daily;
    private final GameConfigService gameConfig;
    private final ProgressionService progression;

    @Autowired
    public BonusModeHandler(QuizHelper helper,
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

    // ===== PREPARE =====

    public PrepareResponse prepareBonusMode(String userId, int lvl, String belt, String op) {
        // Prerequisite: rocket must be completed for this exact level/belt/operation combo.
        boolean rocketCompleted = cachedQuizRuns.isRocketModeCompleted(userId, lvl, belt, op);
        if (!rocketCompleted) {
            throw new IllegalStateException("Rocket mode must be completed before bonus mode");
        }

        // Resume in-flight bonus run if any.
        Optional<QuizRun> existingBonus = cachedQuizRuns.findActiveBonusMode(userId, lvl, belt, op);
        if (existingBonus.isPresent()) {
            QuizRun run = existingBonus.get();
            log.info("[BONUS] Resuming bonus run {} (in-practice={})", run.getId(),
                    Boolean.TRUE.equals(run.getBonusInPractice()));
            return buildResumePrepare(run);
        }

        // Otherwise create a new run.
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
        run.setGameModeType(GameModeType.BONUS.value());
        run.setBonusStreak(0);
        run.setBonusTotalCorrect(0);
        run.setBonusTotalWrong(0);
        run.setBonusInPractice(false);
        run.setBonusBatchNumber(1);

        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        run.setCreatedAt(now);
        run.setUpdatedAt(now);

        // No timer for bonus, even on black belts (Q5).
        helper.ensureTimer(run);
        helper.timerSetLimit(run, 0L);
        helper.timerSetRemaining(run, 0L);

        try {
            cachedQuizRuns.save(run);
            log.info("[BONUS] Created new bonus run {} for user {} {}/{}/L{}", run.getId(), userId, op, belt, lvl);
        } catch (DuplicateKeyException e) {
            log.warn("[BONUS] Race condition for user {}, returning existing run", userId);
            Optional<QuizRun> existing = cachedQuizRuns.findActiveBonusMode(userId, lvl, belt, op);
            if (existing.isPresent()) {
                return buildResumePrepare(existing.get());
            }
            throw e;
        }

        PrepareResponse out = new PrepareResponse();
        out.quizRunId = run.getId();
        out.resumed = false;
        out.gameMode = true;
        out.gameModeType = GameModeType.BONUS.value();
        out.bonusTargetCorrect = gameConfig.getBonusTargetCorrect();
        out.bonusVideoIntervalCorrect = gameConfig.getBonusVideoIntervalCorrect();
        out.bonusCorrectStreak = 0;
        out.level = lvl;
        out.beltOrDegree = belt;
        out.operation = op;
        out.practice = List.of();
        return out;
    }

    private PrepareResponse buildResumePrepare(QuizRun run) {
        PrepareResponse out = new PrepareResponse();
        out.quizRunId = run.getId();
        out.resumed = true;
        out.gameMode = true;
        out.gameModeType = GameModeType.BONUS.value();
        out.bonusTargetCorrect = gameConfig.getBonusTargetCorrect();
        out.bonusVideoIntervalCorrect = gameConfig.getBonusVideoIntervalCorrect();
        out.bonusCorrectStreak = nvl(run.getBonusStreak(), 0);
        out.currentIndex = nvl(run.getCurrentIndex(), 0);
        out.level = run.getLevel();
        out.beltOrDegree = run.getBeltOrDegree();
        out.operation = run.getOperation();
        if (Boolean.TRUE.equals(run.getBonusInPractice())) {
            out.bonusInPractice = true;
            out.needsRestart = true;
        }
        out.practice = List.of();
        return out;
    }

    // ===== ANSWER =====

    public Object handleBonusModeAnswer(QuizRun run, String questionId, int answer, long responseMs) {
        GeneratedQuestion q = cachedQuestions.findById(questionId)
                .orElseThrow(() -> new IllegalArgumentException("Question not found"));

        // Inactivity → mirror Surf/Rocket: reset streak, show practice question (Q4).
        if (gameConfig.isAnswerInactive(responseMs, gameConfig.getInactivityThresholdMs())) {
            return bonusFailed(run, q, answer, responseMs, AttemptReason.INACTIVITY.value());
        }

        boolean correct = (q.getCorrectAnswer() != null && q.getCorrectAnswer() == answer);

        attemptService.recordAttemptAsync(run, q, answer, correct, responseMs, AttemptReason.ANSWER.value());

        run.setTotalActiveMs(nvl(run.getTotalActiveMs(), 0L) + responseMs);

        if (!correct) {
            return bonusFailed(run, q, answer, responseMs, "wrong");
        }

        // Correct answer.
        run.setMainFlowCorrect(nvl(run.getMainFlowCorrect(), 0) + 1);
        int newStreak = nvl(run.getBonusStreak(), 0) + 1;
        run.setBonusStreak(newStreak);
        run.setBonusTotalCorrect(nvl(run.getBonusTotalCorrect(), 0) + 1);

        log.info("[BONUS] Correct! streak={}/{}", newStreak, gameConfig.getBonusTargetCorrect());

        // All correct answers count toward dailyStats (matches Lightning's "all correct" rule).
        DailyStatsResponse dailyStats = null;
        if (run.getUserId() != null) {
            try {
                dailyStats = daily.increment(run.getUserId(), 1, Math.max(0L, responseMs));
            } catch (Exception e) {
                log.error("Daily increment failed", e);
            }
        }

        // Completion takes priority over the video boundary (Q8): if we just hit the target,
        // we award the belt and let the frontend play its win video — no intermediate video signal.
        if (newStreak >= gameConfig.getBonusTargetCorrect()) {
            return completeBonusMode(run, dailyStats);
        }

        // Did we cross a video boundary? (Every 4 in a row by default; never on completion.)
        boolean videoBoundary = newStreak > 0 && (newStreak % gameConfig.getBonusVideoIntervalCorrect() == 0);

        // Advance the index. If we exhausted the current batch, regenerate (Q6).
        int nextIndex = nvl(run.getCurrentIndex(), 0) + 1;
        ensureBatchAvailable(run, nextIndex);
        run.setCurrentIndex(nextIndex);
        run.setUpdatedAt(helper.now());
        cachedQuizRuns.save(run);

        AnswerResponse resp = new AnswerResponse();
        resp.correct = true;
        resp.nextIndex = nextIndex;
        resp.gameMode = true;
        resp.gameModeType = GameModeType.BONUS.value();
        if (videoBoundary) resp.showBonusVideo = true;
        resp.dailyStats = dailyStats;
        resp.bonusCorrectStreak = newStreak;
        return resp;
    }

    private Object bonusFailed(QuizRun run, GeneratedQuestion failedQuestion,
                               int userAnswer, long responseMs, String reason) {
        log.info("[BONUS] Wrong/inactive (reason={}). Streak was {} → reset to 0",
                reason, nvl(run.getBonusStreak(), 0));

        attemptService.recordAttemptAsync(run, failedQuestion, userAnswer, false, responseMs, reason);

        run.setWrong(nvl(run.getWrong(), 0) + 1);
        run.setBonusTotalWrong(nvl(run.getBonusTotalWrong(), 0) + 1);
        run.setBonusStreak(0);
        run.setBonusInPractice(true);

        helper.touch(run);
        cachedQuizRuns.save(run);

        // Same multiple-choice forward practice format as Surf (Q7).
        GeneratedQuestion practiceQ = helper.buildPracticeFromBonusQuestion(failedQuestion);

        AnswerResponse resp = new AnswerResponse();
        resp.correct = false;
        // Per PRD: "Don't indicate if the students fail." We expose practice + correctAnswer so
        // the UI can show the right answer and the practice question, but no "failed/lose" signal.
        resp.bonusFailed = true;
        resp.reason = reason;
        resp.correctAnswer = failedQuestion.getCorrectAnswer();
        resp.practice = practiceQ;
        resp.gameMode = true;
        resp.gameModeType = GameModeType.BONUS.value();
        resp.bonusCorrectStreak = 0;
        return resp;
    }

    /**
     * Called by QuizService.practiceAnswer when a bonus practice question is answered correctly.
     * Clears the in-practice flag and resumes the bonus quiz at the next index.
     */
    public Object bonusResumeAfterPractice(QuizRun run) {
        log.info("[BONUS] Practice passed → resuming bonus quiz");
        run.setBonusInPractice(false);

        int nextIndex = nvl(run.getCurrentIndex(), 0) + 1;
        ensureBatchAvailable(run, nextIndex);
        run.setCurrentIndex(nextIndex);
        helper.touch(run);
        cachedQuizRuns.save(run);

        // Build response with the next quiz question.
        String nextId = currentQuestionId(run);
        GeneratedQuestion nextMain = (nextId != null)
                ? cachedQuestions.findById(nextId).orElse(null)
                : null;

        AnswerResponse resp = new AnswerResponse();
        resp.bonusQuizRestarted = true;
        resp.resume = true;
        resp.next = nextMain;
        resp.nextIndex = nextIndex;
        resp.gameMode = true;
        resp.gameModeType = GameModeType.BONUS.value();
        resp.bonusCorrectStreak = nvl(run.getBonusStreak(), 0);
        return resp;
    }

    private Object completeBonusMode(QuizRun run, DailyStatsResponse dailyStats) {
        log.info("[BONUS] Target reached ({} consecutive correct). Awarding belt.",
                gameConfig.getBonusTargetCorrect());

        run.setPassed(true);
        run.setStatus(QuizStatus.COMPLETED.value());
        run.setBonusInPractice(false);
        helper.touch(run);
        cachedQuizRuns.save(run);
        cachedQuizRuns.forceFlush(run.getId());

        AnswerResponse out = new AnswerResponse();

        try {
            Map<String, Object> fullProgress = progression.unlockOnPass(
                    run.getUserId(), run.getOperation(), run.getLevel(), run.getBeltOrDegree()
            );
            out.updatedProgress = fullProgress;
            out.beltAwarded = true;
        } catch (Exception e) {
            log.error("Progression unlock failed", e);
            out.beltAwarded = false;
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
        out.passed = true;
        out.bonusComplete = true;
        out.gameMode = true;
        out.gameModeType = GameModeType.BONUS.value();
        out.summary = helper.summaryOf(run);
        out.sessionCorrectCount = nvl(run.getMainFlowCorrect(), 0);
        out.dailyStats = dailyStats;
        out.bonusCorrectStreak = gameConfig.getBonusTargetCorrect();
        return out;
    }

    // ===== QUESTION GENERATION =====

    /**
     * Ensures the run's items list has a question available at {@code targetIndex}.
     * If we ran past the end of the current batch, generate a fresh batch and append.
     */
    private void ensureBatchAvailable(QuizRun run, int targetIndex) {
        List<String> items = run.getItems();
        if (items == null) items = new ArrayList<>();
        if (targetIndex < items.size()) return;

        // Generate next batch.
        int newBatch = nvl(run.getBonusBatchNumber(), 1) + 1;
        run.setBonusBatchNumber(newBatch);
        log.info("[BONUS] Generating batch #{} for run {}", newBatch, run.getId());
        List<GeneratedQuestion> nextBatch = buildBonusQuizSet(run);
        items = new ArrayList<>(items);
        for (GeneratedQuestion q : nextBatch) {
            items.add(q.getId());
        }
        run.setItems(items);
    }

    /**
     * Builds {@code bonusQuestionsPerBatch} questions using the same composition as a normal
     * colored/black belt quiz: current-belt fact pair(s) plus previous/prerequisite review.
     */
    public List<GeneratedQuestion> buildBonusQuizSet(QuizRun run) {
        String belt = nvl(run.getBeltOrDegree(), Belt.WHITE.value());
        int lvl = safe(run.getLevel(), 1);
        String op = nvl(run.getOperation(), Operation.ADD.value());
        int batchSize = gameConfig.getBonusQuestionsPerBatch();

        List<GeneratedQuestion> questions = new ArrayList<>();

        // L1-white digit-recognition is an addition-only construct (v1.7).
        if (lvl == 1 && Belt.WHITE.value().equals(belt) && Operation.ADD.value().equalsIgnoreCase(op)) {
            int identityCount = Math.min(2, Math.max(1, batchSize / 5));
            for (int i = 0; i < identityCount; i++) {
                questions.add(helper.buildQuestionObject(op, lvl, belt, 0, 0, "current",
                        QuizUtils.buildQuestionText(op, 0, 0)));
            }
            int digitsNeeded = batchSize - questions.size();
            for (int i = 0; i < digitsNeeded; i++) {
                int digit = ThreadLocalRandom.current().nextInt(10);
                questions.add(helper.buildDigitQuestionObject(op, lvl, belt, digit));
            }
            List<GeneratedQuestion> reordered = reorderNoConsecutiveDuplicates(questions);
            return cachedQuestions.saveAll(reordered);
        }

        // Build the same combined pool as NormalModeHandler (current + previous + cross-op chain).
        int[] currentPair = helper.getCanonicalPair(op, lvl, belt);
        boolean isIdentical = currentPair != null && currentPair[0] == currentPair[1];

        // Seed with the current fact pair (treat as ~40% of batch by repeating, like normal mode).
        int currentSlots;
        if (isBlack(belt)) {
            currentSlots = Math.max(1, batchSize / 5); // black belts pull from full level pool
        } else {
            currentSlots = Math.max(1, batchSize * 4 / 10); // ~40% of batch, like 4-of-10 normal-belt mix
        }
        if (currentPair != null) {
            int a = currentPair[0], b = currentPair[1];
            for (int i = 0; i < currentSlots; i++) {
                if (!isIdentical && isCommutative(op) && (i % 2 == 1)) {
                    questions.add(helper.buildQuestionObject(op, lvl, belt, b, a, "current", null));
                } else {
                    questions.add(helper.buildQuestionObject(op, lvl, belt, a, b, "current", null));
                }
            }
        }

        // Same-level previous belts + earlier levels of same op.
        List<int[]> prevPool = helper.getPreviousPool(op, lvl, belt);
        // Full prerequisite chain (e.g. for sub: add facts; for mul: add+sub).
        List<Object[]> prereqChainPool = helper.getFullPrerequisiteChainPool(op);

        int previousNeeded = batchSize - questions.size();
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
            } else if (currentPair != null) {
                // Fallback: repeat current pair if no other pool entries exist.
                questions.add(helper.buildQuestionObject(op, lvl, belt, currentPair[0], currentPair[1], "current", null));
            } else {
                questions.add(helper.buildQuestionObject(op, lvl, belt, 1, 1, "current", null));
            }
        }

        Collections.shuffle(questions);
        List<GeneratedQuestion> reordered = reorderNoConsecutiveDuplicates(questions);
        return cachedQuestions.saveAll(reordered);
    }

    /**
     * Bonus has no fail criteria → no auto-restart needed when start() resumes a bonus run.
     * Kept for API symmetry with surf/rocket.
     */
    public void autoRestartIfFailed(QuizRun run) {
        // No-op: bonus mode has no failed sub-quiz state. Wrong answers route through practice
        // and resume in place via QuizService.practiceAnswer → bonusResumeAfterPractice.
        Objects.requireNonNull(run);
    }
}
