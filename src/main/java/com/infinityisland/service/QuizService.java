package com.infinityisland.service;

import com.infinityisland.dao.GeneratedQuestion;
import com.infinityisland.dao.QuizRun;
import com.infinityisland.model.GameModeType;
import com.infinityisland.model.Operation;
import com.infinityisland.model.QuizStatus;
import com.infinityisland.controller.QuizResponses.AnswerResponse;
import com.infinityisland.controller.QuizResponses.PrepareResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

import static com.infinityisland.service.QuizUtils.*;

@Service
public class QuizService {

    private static final Logger log = LoggerFactory.getLogger(QuizService.class);

    private final CachedQuizRunService cachedQuizRuns;
    private final CachedQuestionService cachedQuestions;
    private final AttemptService attemptService;

    @Autowired private QuizHelper helper;
    @Autowired private ProgressionService progression;
    @Autowired private GameConfigService gameConfig;
    @Autowired private SurfModeHandler surfHandler;
    @Autowired private RocketModeHandler rocketHandler;
    @Autowired private BonusModeHandler bonusHandler;
    @Autowired private LightningModeHandler lightningHandler;
    @Autowired private NormalModeHandler normalHandler;
    @Autowired private PretestModeHandler pretestHandler;

    @Autowired
    public QuizService(CachedQuizRunService cachedQuizRuns,
                       CachedQuestionService cachedQuestions,
                       AttemptService attemptService) {
        this.cachedQuizRuns = cachedQuizRuns;
        this.cachedQuestions = cachedQuestions;
        this.attemptService = attemptService;
    }

    // ===== PREPARE =====

    public PrepareResponse prepare(String userId, Integer level, String beltOrDegree,
                                   String operation, Boolean gameMode, Integer targetCorrect,
                                   String gameModeType) {
        if (beltOrDegree == null || beltOrDegree.isBlank()) {
            throw new IllegalArgumentException("beltOrDegree is required");
        }

        PrepareResponse resume = helper.findActiveGameModeResume(userId);
        if (resume != null) return resume;

        boolean isGameMode = Boolean.TRUE.equals(gameMode);
        String op = nvl(operation, Operation.ADD.value());
        String belt = nvl(beltOrDegree, "white");
        int lvl = safe(level, 1);

        if (!gameConfig.isOperationEnabled(op)) {
            throw new IllegalStateException("Operation '" + op + "' is not currently available.");
        }
        if (!progression.isOperationUnlocked(userId, op)) {
            throw new IllegalStateException("Operation '" + op + "' is not yet unlocked. Complete the prerequisite operation first.");
        }

        String modeType = gameModeType != null ? gameModeType.toLowerCase() : null;
        boolean isSurfMode = GameModeType.SURF.value().equals(modeType);
        boolean isRocketMode = GameModeType.ROCKET.value().equals(modeType);
        boolean isBonusMode = GameModeType.BONUS.value().equals(modeType);
        boolean isLightningMode = isGameMode && !isSurfMode && !isRocketMode && !isBonusMode;
        boolean isPretestMode = GameModeType.PRETEST.value().equals(modeType);

        if (!isPretestMode && !isGameMode && progression.isPretestRequired(userId, op, lvl)) {
            log.info("[PRETEST] Pretest required for user {} at level {} operation {}", userId, lvl, op);
            return pretestHandler.preparePretestMode(userId, lvl, op);
        }

        if (isPretestMode) return pretestHandler.preparePretestMode(userId, lvl, op);
        if (isBonusMode) return bonusHandler.prepareBonusMode(userId, lvl, belt, op);
        if (isRocketMode) return rocketHandler.prepareRocketMode(userId, lvl, belt, op);
        if (isSurfMode) return surfHandler.prepareSurfMode(userId, lvl, belt, op);
        if (isLightningMode) return lightningHandler.prepareLightningMode(userId, lvl, belt, op, targetCorrect);
        return normalHandler.prepareNormalMode(userId, lvl, belt, op);
    }

    // ===== START =====

    public Object start(String quizRunId) {
        QuizRun run = helper.mustGetRun(quizRunId);

        if (QuizStatus.COMPLETED.value().equalsIgnoreCase(run.getStatus())) {
            return helper.buildCompletedResponse(run);
        }

        if (QuizStatus.RUNNING.value().equalsIgnoreCase(run.getStatus())) {
            if (run.isSurfMode()) surfHandler.autoRestartIfFailed(run);
            else if (run.isRocketMode()) rocketHandler.autoRestartIfFailed(run);
            else if (run.isBonusMode()) bonusHandler.autoRestartIfFailed(run);
            return helper.buildStartResumedResponse(run);
        }

        if (run.getItems() == null || run.getItems().isEmpty()) {
            List<GeneratedQuestion> set;
            if (run.isPretestMode()) {
                set = pretestHandler.buildPretestQuizSet(run);
                run.setTotalQuestions(gameConfig.getPretestQuestionCount());
            } else if (run.isSurfMode()) {
                set = surfHandler.buildSurfQuizSet(run);
                run.setTotalQuestions(gameConfig.getSurfQuestionsPerQuiz());
            } else if (run.isRocketMode()) {
                set = rocketHandler.buildRocketQuizSet(run);
                run.setTotalQuestions(gameConfig.getRocketQuestionsPerQuiz());
            } else if (run.isBonusMode()) {
                set = bonusHandler.buildBonusQuizSet(run);
                // bonus has no fixed total — totalQuestions left null, the run continues
                // until bonusStreak hits bonusTargetCorrect.
            } else {
                set = normalHandler.buildQuizSet(run);
                if (run.getTotalQuestions() == null) run.setTotalQuestions(set.size());
            }
            run.setItems(set.stream().map(GeneratedQuestion::getId).collect(Collectors.toList()));
            cachedQuestions.saveAll(set);
        }

        run.setStatus(QuizStatus.RUNNING.value());
        run.setCurrentIndex(0);

        if (run.isPretestMode() || (isBlack(run.getBeltOrDegree()) && !run.isSurfMode() && !run.isRocketMode())) {
            helper.ensureTimer(run);
            run.getTimer().setStartedAt(helper.now());
        }

        helper.touch(run);
        run = cachedQuizRuns.updateCacheAndSaveSync(run);

        List<GeneratedQuestion> questions = cachedQuestions.findAllById(run.getItems());
        return helper.buildStartResponse(run, questions, false);
    }

    // ===== ANSWER =====

    public Object answer(String quizRunId, String questionId, int answer, long responseMs,
                         Boolean forcePass, Boolean skipLevelAward) {
        QuizRun run = helper.mustGetRun(quizRunId);

        if (QuizStatus.COMPLETED.value().equalsIgnoreCase(nvl(run.getStatus(), ""))) {
            return helper.buildCompletedResponse(run);
        }
        guardRunning(run);

        String currentId = currentQuestionId(run);
        String prevId = previousQuestionId(run);

        if (!Objects.equals(questionId, currentId)) {
            if (run.getCurrentIndex() > 0 && Objects.equals(questionId, prevId)) {
                return helper.buildDuplicateResponse(run);
            }
            throw new IllegalArgumentException("Not the current question");
        }

        if (Boolean.TRUE.equals(forcePass)) {
            return helper.handleForcePass(run, responseMs, Boolean.TRUE.equals(skipLevelAward));
        }

        if (run.isPretestMode()) return pretestHandler.handlePretestModeAnswer(run, questionId, answer, responseMs);
        if (run.isSurfMode()) return surfHandler.handleSurfModeAnswer(run, questionId, answer, responseMs);
        if (run.isRocketMode()) return rocketHandler.handleRocketModeAnswer(run, questionId, answer, responseMs);
        if (run.isBonusMode()) return bonusHandler.handleBonusModeAnswer(run, questionId, answer, responseMs);
        if (run.isLightningMode()) return lightningHandler.handleLightningModeAnswer(run, questionId, answer, responseMs);
        return normalHandler.handleNormalModeAnswer(run, questionId, answer, responseMs);
    }

    // ===== PRACTICE ANSWER =====

    public Object practiceAnswer(String quizRunId, String questionId, int answer) {
        QuizRun run = helper.mustGetRun(quizRunId);
        guardRunningOrPrepared(run);

        GeneratedQuestion pq = cachedQuestions.findById(questionId).orElse(null);
        if (pq == null) throw new IllegalArgumentException("Practice question not found");

        boolean correct = pq.getCorrectAnswer() != null && pq.getCorrectAnswer() == answer;
        attemptService.recordAttemptAsync(run, pq, answer, correct, null, "practice");

        if (!correct) return helper.buildPracticeIncorrectResponse(run, pq);

        if (run.isSurfMode() && Boolean.TRUE.equals(run.getSurfQuizFailed())) {
            return surfHandler.surfQuizRestart(run);
        }
        if (run.isRocketMode() && Boolean.TRUE.equals(run.getRocketQuizFailed())) {
            return rocketHandler.rocketQuizRestart(run);
        }
        if (run.isBonusMode() && Boolean.TRUE.equals(run.getBonusInPractice())) {
            return bonusHandler.bonusResumeAfterPractice(run);
        }

        if (QuizStatus.RUNNING.value().equalsIgnoreCase(run.getStatus())) {
            AnswerResponse result = helper.resumeAfterPractice(run);
            if (result == null) {
                return pretestHandler.completePretestMode(run, null);
            }
            return result;
        }

        helper.touch(run);
        cachedQuizRuns.save(run);
        AnswerResponse resp = new AnswerResponse();
        resp.resume = true;
        return resp;
    }

    // ===== INACTIVITY =====

    public Object inactivity(String quizRunId, String questionId) {
        QuizRun run = helper.mustGetRun(quizRunId);

        if (!QuizStatus.RUNNING.value().equalsIgnoreCase(run.getStatus())) {
            return helper.buildLateInactivityResponse(run);
        }

        String originalQId = questionId != null && !questionId.isBlank() ? questionId : currentQuestionId(run);
        if (originalQId == null) throw new IllegalStateException("No current question for inactivity");

        waitAnswerInactivityGrace();

        run = helper.mustGetRun(quizRunId);
        if (!QuizStatus.RUNNING.value().equalsIgnoreCase(run.getStatus())) {
            return helper.buildLateInactivityResponse(run);
        }

        String currentQId = currentQuestionId(run);
        if (currentQId == null) throw new IllegalStateException("No current question for inactivity");
        if (!Objects.equals(originalQId, currentQId)) {
            return helper.buildDuplicateResponse(run);
        }

        GeneratedQuestion currentQuestion = cachedQuestions.findById(currentQId)
                .orElseThrow(() -> new IllegalArgumentException("Current question not found"));

        long forcedInactivityResponseMs = gameConfig.getInactivityThresholdMs()
                + gameConfig.getAnswerInactivityGraceMs()
                + 1;
        if (run.isSurfMode()) return surfHandler.handleSurfModeAnswer(run, currentQId, -1, forcedInactivityResponseMs);
        if (run.isRocketMode()) return rocketHandler.handleRocketModeAnswer(run, currentQId, -1, forcedInactivityResponseMs);
        if (run.isBonusMode()) return bonusHandler.handleBonusModeAnswer(run, currentQId, -1, forcedInactivityResponseMs);

        return helper.handleNormalInactivity(run, currentQuestion);
    }

    private void waitAnswerInactivityGrace() {
        long graceMs = gameConfig.getAnswerInactivityGraceMs();
        if (graceMs <= 0) return;
        try {
            Thread.sleep(graceMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ===== COMPLETE =====

    public Object complete(String quizRunId) {
        return helper.complete(quizRunId);
    }
}
