package com.infinityisland.service;

import com.infinityisland.config.GameModeConfig;
import com.infinityisland.controller.QuizResponses.AnswerResponse;
import com.infinityisland.controller.QuizResponses.DailyStatsResponse;
import com.infinityisland.controller.QuizResponses.PrepareResponse;
import com.infinityisland.dao.GeneratedQuestion;
import com.infinityisland.dao.QuizRun;
import com.infinityisland.model.AttemptReason;
import com.infinityisland.model.GameModeType;
import com.infinityisland.model.QuizStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static com.infinityisland.service.QuizUtils.*;

@Component
public class LightningModeHandler {

    private static final Logger log = LoggerFactory.getLogger(LightningModeHandler.class);

    private final QuizHelper helper;
    private final CachedQuizRunService cachedQuizRuns;
    private final CachedQuestionService cachedQuestions;
    private final AttemptService attemptService;
    private final DailyService daily;
    private final GameConfigService gameConfig;
    private final GameModeConfig gameModeConfig;

    @Autowired
    public LightningModeHandler(QuizHelper helper,
                                CachedQuizRunService cachedQuizRuns,
                                CachedQuestionService cachedQuestions,
                                AttemptService attemptService,
                                DailyService daily,
                                GameConfigService gameConfig,
                                GameModeConfig gameModeConfig) {
        this.helper = helper;
        this.cachedQuizRuns = cachedQuizRuns;
        this.cachedQuestions = cachedQuestions;
        this.attemptService = attemptService;
        this.daily = daily;
        this.gameConfig = gameConfig;
        this.gameModeConfig = gameModeConfig;
    }

    public PrepareResponse prepareLightningMode(String userId, int lvl, String belt, String op, Integer targetCorrect) {
        int gameModeTarget = (targetCorrect != null && targetCorrect > 0)
                ? targetCorrect
                : gameConfig.getLightningTargetCorrect();

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
        run.setGameModeType(GameModeType.LIGHTNING.value());
        run.setTargetCorrect(gameModeTarget);
        run.setTotalCorrect(0);

        log.info("[LIGHTNING] Creating new lightning mode quiz for user {} with target {}", userId, gameModeTarget);

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

        try {
            cachedQuizRuns.save(run);
        } catch (DuplicateKeyException e) {
            log.warn("[LIGHTNING] Race condition detected for user {}, returning existing run", userId);
            Optional<QuizRun> existing = cachedQuizRuns.findActiveLightningMode(userId);
            if (existing.isPresent()) {
                QuizRun existingRun = existing.get();
                PrepareResponse out = new PrepareResponse();
                out.quizRunId = existingRun.getId();
                out.resumed = true;
                out.gameMode = true;
                out.gameModeType = existingRun.getGameModeType() != null ? existingRun.getGameModeType() : GameModeType.LIGHTNING.value();
                out.targetCorrect = existingRun.getTargetCorrect();
                out.totalCorrect = nvl(existingRun.getTotalCorrect(), 0);
                out.currentIndex = nvl(existingRun.getCurrentIndex(), 0);
                out.level = existingRun.getLevel();
                out.beltOrDegree = existingRun.getBeltOrDegree();
                out.operation = existingRun.getOperation();
                out.practice = List.of();
                return out;
            }
            throw e;
        }

        List<GeneratedQuestion> practice = helper.buildPracticeQuestions(op, lvl, belt);

        PrepareResponse out = new PrepareResponse();
        out.quizRunId = run.getId();
        out.resumed = false;
        out.gameMode = true;
        out.gameModeType = GameModeType.LIGHTNING.value();
        out.targetCorrect = run.getTargetCorrect();
        out.totalCorrect = 0;
        out.practice = practice;
        return out;
    }

    public Object handleLightningModeAnswer(QuizRun run, String questionId, int answer, long responseMs) {
        GeneratedQuestion q = cachedQuestions.findById(questionId)
                .orElseThrow(() -> new IllegalArgumentException("Question not found"));

        if (gameConfig.isAnswerInactive(responseMs, gameConfig.getInactivityThresholdMs())) {
            attemptService.recordAttemptAsync(run, q, answer, false, responseMs, AttemptReason.INACTIVITY.value());
            run.setWrong(nvl(run.getWrong(), 0) + 1);
            helper.touch(run);
            cachedQuizRuns.updateCache(run);

            AnswerResponse resp = new AnswerResponse();
            resp.practice = q;
            resp.reason = AttemptReason.INACTIVITY.value();
            resp.gameMode = true;
            resp.gameModeType = GameModeType.LIGHTNING.value();
            resp.totalCorrect = nvl(run.getTotalCorrect(), 0);
            resp.targetCorrect = run.getTargetCorrect();
            return resp;
        }

        boolean correct = (q.getCorrectAnswer() != null && q.getCorrectAnswer() == answer);
        attemptService.recordAttemptAsync(run, q, answer, correct, responseMs, AttemptReason.ANSWER.value());

        run.setTotalActiveMs(nvl(run.getTotalActiveMs(), 0L) + responseMs);

        if (correct) {
            boolean fastEnough = responseMs < gameConfig.getLightningFastThresholdMs();

            if (fastEnough) {
                run.setTotalCorrect(nvl(run.getTotalCorrect(), 0) + 1);
            }

            run.setMainFlowCorrect(nvl(run.getMainFlowCorrect(), 0) + 1);

            log.info("[LIGHTNING] Correct! {} {}/{}", (fastEnough ? "Fast" : "Slow (>" + gameModeConfig.getLightning().getFastThresholdMs() + "ms)"), run.getTotalCorrect(), run.getTargetCorrect());

            DailyStatsResponse dailyStats = null;
            if (run.getUserId() != null) {
                try {
                    dailyStats = daily.increment(run.getUserId(), 1, Math.max(0L, responseMs));
                } catch (Exception e) {
                    log.error("Daily increment failed", e);
                }
            }

            if (run.getTotalCorrect() >= run.getTargetCorrect()) {
                return completeLightningMode(run, dailyStats);
            }

            int nextIndex = (nvl(run.getCurrentIndex(), 0) + 1) % run.getItems().size();
            run.setCurrentIndex(nextIndex);
            run.setUpdatedAt(helper.now());
            cachedQuizRuns.updateCache(run);

            AnswerResponse resp = new AnswerResponse();
            resp.nextIndex = nextIndex;
            resp.gameMode = true;
            resp.gameModeType = GameModeType.LIGHTNING.value();
            resp.totalCorrect = run.getTotalCorrect();
            resp.targetCorrect = run.getTargetCorrect();
            if (!fastEnough) {
                resp.slow = true;
            }
            resp.dailyStats = dailyStats;
            return resp;

        } else {
            run.setWrong(nvl(run.getWrong(), 0) + 1);
            helper.touch(run);
            cachedQuizRuns.updateCache(run);

            AnswerResponse resp = new AnswerResponse();
            resp.practice = q;
            resp.reason = "wrong";
            resp.gameMode = true;
            resp.gameModeType = GameModeType.LIGHTNING.value();
            resp.totalCorrect = nvl(run.getTotalCorrect(), 0);
            resp.targetCorrect = run.getTargetCorrect();
            return resp;
        }
    }

    private Object completeLightningMode(QuizRun run, DailyStatsResponse dailyStats) {
        log.info("[LIGHTNING] Complete! {}/{} - Surf mode required for belt.", run.getTotalCorrect(), run.getTargetCorrect());

        run.setPassed(true);
        run.setStatus(QuizStatus.COMPLETED.value());
        helper.touch(run);
        cachedQuizRuns.save(run);
        cachedQuizRuns.forceFlush(run.getId());

        if (run.getUserId() != null) {
            try {
                dailyStats = daily.getForUser(run.getUserId());
            } catch (Exception e) {
                log.error("Failed to fetch daily stats", e);
            }
            daily.forceFlush(run.getUserId());
        }

        AnswerResponse out = new AnswerResponse();

        out.completed = true;
        out.passed = true;
        out.gameMode = true;
        out.gameModeType = GameModeType.LIGHTNING.value();
        out.lightningComplete = true;
        out.surfRequired = true;
        out.totalCorrect = run.getTotalCorrect();
        out.targetCorrect = run.getTargetCorrect();
        out.summary = helper.summaryOf(run);
        out.sessionCorrectCount = nvl(run.getMainFlowCorrect(), 0);
        out.dailyStats = dailyStats;

        return out;
    }
}
