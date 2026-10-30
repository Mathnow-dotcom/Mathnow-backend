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
    @Autowired private ProgramService program;
    @Autowired private GameConfigService gameConfig;
    @Autowired private SurfModeHandler surfHandler;
    @Autowired private RocketModeHandler rocketHandler;
    @Autowired private BonusModeHandler bonusHandler;
    @Autowired private LightningModeHandler lightningHandler;
    @Autowired private NormalModeHandler normalHandler;
    @Autowired private PretestModeHandler pretestHandler;
    @Autowired private CorrectionLoopService corrections;

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

        boolean isGameMode = Boolean.TRUE.equals(gameMode);
        String op = nvl(operation, Operation.ADD.value()).toLowerCase(Locale.ROOT);
        String belt = nvl(beltOrDegree, "white");
        int lvl = safe(level, 1);
        program.requireLearning(userId, op, lvl);
        QuizRun pendingRun = corrections.pendingRun(userId, op, lvl, belt);
        if (pendingRun != null) {
            PrepareResponse out = new PrepareResponse(); out.quizRunId = pendingRun.getId(); out.resumed = true; out.correctionPending = true; out.pretestMode = pendingRun.isPretestMode(); return out;
        }
        PrepareResponse resume = helper.findActiveGameModeResume(userId);
        if (resume != null) {
            QuizRun active = cachedQuizRuns.findById(resume.quizRunId).orElse(null);
            // An unrelated pending correction must not redirect another level/belt selection.
            if (active == null || !corrections.pending(active) ||
                    (Objects.equals(active.getOperation(), op) && Objects.equals(active.getLevel(), lvl)
                        && Objects.equals(active.getBeltOrDegree(), belt))) {
                helper.mustGetRun(resume.quizRunId);
                return resume;
            }
        }

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
        if (!isPretestMode) program.requireBelt(userId, op, lvl, belt);

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

    public void requireOwner(String userId, String runId) {
        QuizRun run = cachedQuizRuns.findById(runId).orElseThrow(() -> new jakarta.ws.rs.NotFoundException("Run not found"));
        if (userId == null || !userId.equals(run.getUserId()))
            throw new jakarta.ws.rs.ForbiddenException("Run does not belong to this student");
    }

    public Object start(String quizRunId) {
        QuizRun run = helper.mustGetRun(quizRunId);
        if (corrections.pending(run)) {
            var resumed = helper.buildStartResponse(run,cachedQuestions.findAllById(run.getItems()),true);
            resumed.practice = run.correctionLoop.originalPractice;
            resumed.mainFlowCorrect = nvl(run.getMainFlowCorrect(), 0); resumed.wrong = nvl(run.getWrong(), 0);
            resumed.operation = run.getOperation(); resumed.level = run.getLevel(); resumed.beltOrDegree = run.getBeltOrDegree();
            helper.pauseTimer(run);
            return resumed;
        }

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
        if (corrections.pending(run)) {
            AnswerResponse out = new AnswerResponse(); out.practice = run.correctionLoop.originalPractice; return out;
        }

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
            if (program.managed(run.getOperation())) throw new IllegalArgumentException("Assessment progression cannot be bypassed");
            return helper.handleForcePass(run, responseMs, Boolean.TRUE.equals(skipLevelAward));
        }

        Object out;
        if (run.isPretestMode()) out = pretestHandler.handlePretestModeAnswer(run, questionId, answer, responseMs);
        else if (run.isSurfMode()) out = surfHandler.handleSurfModeAnswer(run, questionId, answer, responseMs);
        else if (run.isRocketMode()) out = rocketHandler.handleRocketModeAnswer(run, questionId, answer, responseMs);
        else if (run.isBonusMode()) out = bonusHandler.handleBonusModeAnswer(run, questionId, answer, responseMs);
        else if (run.isLightningMode()) out = lightningHandler.handleLightningModeAnswer(run, questionId, answer, responseMs);
        else out = normalHandler.handleNormalModeAnswer(run, questionId, answer, responseMs);
        return corrections.intercept(run, out);
    }

    // ===== PRACTICE ANSWER =====

    public Object practiceAnswer(String quizRunId, String questionId, int answer) {
        QuizRun run = helper.mustGetRun(quizRunId);
        synchronized (run) {
            var loop = run.correctionLoop;
            if (loop != null && Objects.equals(questionId, loop.originalPractice.getId())) {
                if ("finished".equals(loop.phase)) { cachedQuizRuns.saveSync(run); return loop.completionResponse; }
                if (!"passed".equals(loop.phase)) throw new jakarta.ws.rs.ForbiddenException("Complete the three-fact correction first");
                if (!Objects.equals(answer, loop.originalPractice.getCorrectAnswer())) throw new jakarta.ws.rs.BadRequestException("Invalid correction completion");
                // Persist a checkpoint before the existing resume handlers can save an advanced run.
                // A retry after a lost response or server restart must never advance it twice.
                boolean alreadyAdvanced = loop.completionFromIndex != null &&
                    (!Objects.equals(loop.completionFromIndex, run.getCurrentIndex()) ||
                     !Objects.equals(loop.completionFromItems, run.getItems()) ||
                     QuizStatus.COMPLETED.value().equalsIgnoreCase(run.getStatus()));
                if (loop.completionFromIndex == null) {
                    loop.completionFromIndex = nvl(run.getCurrentIndex(), 0);
                    loop.completionFromItems = new ArrayList<>(run.getItems());
                    cachedQuizRuns.saveSync(run);
                }
                Object response = alreadyAdvanced ? correctionResumeResponse(run) : finishPractice(run, questionId, answer);
                loop.phase = "finished"; loop.completionResponse = response;
                cachedQuizRuns.saveSync(run);
                return response;
            }
            if (corrections.pending(run)) throw new jakarta.ws.rs.ForbiddenException("Complete the three-fact correction first");
            return finishPractice(run, questionId, answer);
        }
    }
    private Object correctionResumeResponse(QuizRun run) {
        if (run.isPretestMode() && helper.isPretestComplete(run))
            return pretestHandler.completePretestMode(run, null);
        if (QuizStatus.COMPLETED.value().equalsIgnoreCase(run.getStatus()))
            return helper.buildLateInactivityResponse(run);
        AnswerResponse out = helper.buildDuplicateResponse(run);
        out.resume = true;
        String nextId = currentQuestionId(run);
        out.next = nextId == null ? null : cachedQuestions.findById(nextId).orElse(null);
        if (run.isSurfMode() || run.isRocketMode()) {
            out.questions = cachedQuestions.findAllById(run.getItems());
            out.surfQuizRestarted = run.isSurfMode();
            out.rocketQuizRestarted = run.isRocketMode();
        }
        if (run.isBonusMode()) out.bonusQuizRestarted = true;
        return out;
    }
    private Object finishPractice(QuizRun run, String questionId, int answer) {
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
        if (corrections.pending(run)) {
            AnswerResponse out = new AnswerResponse(); out.practice = run.correctionLoop.originalPractice; return out;
        }

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
        Object out;
        if (run.isSurfMode()) out = surfHandler.handleSurfModeAnswer(run, currentQId, -1, forcedInactivityResponseMs);
        else if (run.isRocketMode()) out = rocketHandler.handleRocketModeAnswer(run, currentQId, -1, forcedInactivityResponseMs);
        else if (run.isBonusMode()) out = bonusHandler.handleBonusModeAnswer(run, currentQId, -1, forcedInactivityResponseMs);
        else out = helper.handleNormalInactivity(run, currentQuestion);
        return corrections.intercept(run, out);
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
        if (corrections.pending(helper.mustGetRun(quizRunId))) throw new jakarta.ws.rs.ForbiddenException("Complete the correction first");
        return helper.complete(quizRunId);
    }

    public Object correctionState(String id) { return corrections.state(helper.mustGetRun(id)); }
    public String pendingCorrection(String userId) { return corrections.pendingRun(userId); }
    public Object correctionAction(String runId, String id, long revision, String action, Integer answer, long responseMs) {
        return corrections.act(helper.mustGetRun(runId),id,revision,action,answer,responseMs);
    }
}
