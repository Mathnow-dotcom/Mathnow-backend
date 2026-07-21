package com.infinityisland.service;

import com.infinityisland.controller.QuizResponses.AnswerResponse;
import com.infinityisland.controller.QuizResponses.DailyStatsResponse;
import com.infinityisland.controller.QuizResponses.PrepareResponse;
import com.infinityisland.controller.QuizResponses.QuizSummary;
import com.infinityisland.controller.QuizResponses.RunInfo;
import com.infinityisland.controller.QuizResponses.StartResponse;
import com.infinityisland.controller.QuizResponses.TimerInfo;
import com.infinityisland.dao.Catalog;
import com.infinityisland.dao.GeneratedQuestion;
import com.infinityisland.dao.QuizRun;
import com.infinityisland.model.Belt;
import com.infinityisland.model.GameModeType;
import com.infinityisland.model.Operation;
import com.infinityisland.model.QuizStatus;
import com.infinityisland.repositories.CatalogRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;

import static com.infinityisland.service.QuizUtils.*;

/**
 * Shared stateful service for quiz operations: canonical pair cache, question building,
 * timer management, run lookups, and quiz summary construction.
 */
@Service
@DependsOn({"divisionCatalogSeeder", "multiplicationCatalogSeeder", "multiplicationProgressResetMigration"})
public class QuizHelper {

    private static final Logger log = LoggerFactory.getLogger(QuizHelper.class);

    private final CachedQuizRunService cachedQuizRuns;
    private final CachedQuestionService cachedQuestions;
    private final GameConfigService gameConfig;
    private final DailyService daily;
    private final ProgressionService progression;
    private final AttemptService attemptService;
    private final Clock clock = Clock.systemUTC();

    @Autowired
    private CatalogRepository catalogRepo;

    private Map<String, int[]> canonicalPairCache = new ConcurrentHashMap<>();

    private final ScheduledExecutorService cleanupExecutor =
            Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "quiz-cleanup");
                t.setDaemon(true);
                return t;
            });

    @Autowired
    public QuizHelper(CachedQuizRunService cachedQuizRuns,
                      CachedQuestionService cachedQuestions,
                      GameConfigService gameConfig,
                      DailyService daily,
                      ProgressionService progression,
                      AttemptService attemptService) {
        this.cachedQuizRuns = cachedQuizRuns;
        this.cachedQuestions = cachedQuestions;
        this.gameConfig = gameConfig;
        this.daily = daily;
        this.progression = progression;
        this.attemptService = attemptService;
    }

    @PostConstruct
    public void warmCanonicalPairCache() {
        long start = System.currentTimeMillis();
        List<Catalog> all = catalogRepo.findAll();
        for (Catalog cat : all) {
            String key = cat.getOperation() + "_L" + cat.getLevel() + "_" + cat.getBelt();
            if (cat.getFacts() != null && !cat.getFacts().isEmpty()) {
                Catalog.Fact first = cat.getFacts().get(0);
                canonicalPairCache.put(key, new int[]{first.getA(), first.getB()});
            }
        }
        long elapsed = System.currentTimeMillis() - start;
        log.info("[INIT] Canonical pair cache: {} entries in {}ms", canonicalPairCache.size(), elapsed);
    }

    // ===== CANONICAL PAIR LOOKUPS =====

    public int[] getCanonicalPair(String op, int level, String belt) {
        String key = op + "_L" + level + "_" + belt;
        return canonicalPairCache.get(key);
    }

    public List<int[]> getPreviousPool(String op, int level, String belt) {
        List<String> belts = Belt.COLORED_ORDER;
        int beltIdx = belt != null && belt.startsWith("black") ? belts.size() : belts.indexOf(belt);
        List<int[]> pool = new ArrayList<>();

        for (int i = 0; i < beltIdx; i++) {
            int[] pair = getCanonicalPair(op, level, belts.get(i));
            if (pair != null) pool.add(pair);
        }

        for (int l = 1; l < level; l++) {
            for (String b : belts) {
                int[] pair = getCanonicalPair(op, l, b);
                if (pair != null) pool.add(pair);
            }
        }

        return pool;
    }

    public List<int[]> getPrerequisitePool(String op) {
        String prereqOp = gameConfig.getOperationPrerequisite(op);
        if (prereqOp == null) return List.of();

        List<String> belts = Belt.COLORED_ORDER;
        List<int[]> pool = new ArrayList<>();
        int prereqMaxLevel = gameConfig.getMaxLevel(prereqOp);
        for (int l = 1; l <= prereqMaxLevel; l++) {
            for (String b : belts) {
                int[] pair = getCanonicalPair(prereqOp, l, b);
                if (pair != null) pool.add(pair);
            }
        }
        return pool;
    }

    public List<Object[]> getFullPrerequisiteChainPool(String op) {
        List<Object[]> pool = new ArrayList<>();
        List<String> belts = Belt.COLORED_ORDER;
        String current = gameConfig.getOperationPrerequisite(op);
        while (current != null) {
            int maxLevel = gameConfig.getMaxLevel(current);
            for (int l = 1; l <= maxLevel; l++) {
                for (String b : belts) {
                    int[] pair = getCanonicalPair(current, l, b);
                    if (pair != null) {
                        pool.add(new Object[]{current, pair[0], pair[1]});
                    }
                }
            }
            current = gameConfig.getOperationPrerequisite(current);
        }
        return pool;
    }

    // ===== QUESTION BUILDING =====

    public GeneratedQuestion buildQuestionObject(String op, int level, String beltOrDegree,
                                                  int a, int b, String source,
                                                  String questionStringOverride) {
        GeneratedQuestion g = new GeneratedQuestion();
        g.setOperation(op);
        g.setLevel(level);
        g.setBeltOrDegree(beltOrDegree);

        GeneratedQuestion.Params params = new GeneratedQuestion.Params();
        params.setA(a);
        params.setB(b);
        g.setParams(params);

        String questionText = (questionStringOverride != null)
                ? questionStringOverride
                : buildQuestionText(op, a, b);
        g.setQuestion(questionText);

        int correct = computeAnswer(op, a, b);
        g.setCorrectAnswer(correct);

        boolean isPractice = (level == 1 && Belt.WHITE.value().equals(beltOrDegree)) ||
                questionStringOverride != null ||
                (a == 0 && b == 0);

        if (isPractice) {
            g.setChoices(buildPracticeChoices(correct));
        } else {
            g.setChoices(buildChoices(op, a, b, correct));
        }

        g.setSource(source);
        g.setSeed(UUID.randomUUID().toString());

        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        g.setCreatedAt(now);
        g.setUpdatedAt(now);
        g.set__v(0);

        return g;
    }

    public GeneratedQuestion buildDigitQuestionObject(String op, int level, String belt, int digit) {
        if (Operation.ADD.value().equalsIgnoreCase(op) || Operation.SUB.value().equalsIgnoreCase(op)) {
            return buildQuestionObject(op, level, belt, digit, 0, "previous", String.valueOf(digit));
        }
        return buildQuestionObject(op, level, belt, digit, 0, "previous", null);
    }

    public List<GeneratedQuestion> buildPracticeQuestions(String op, int lvl, String belt) {
        List<GeneratedQuestion> practiceObjects = new ArrayList<>();
        if (isBlack(belt)) {
            // No practice for black belt
        } else if (lvl == 1 && Belt.WHITE.value().equals(belt) && Operation.ADD.value().equalsIgnoreCase(op)) {
            // L1-white (0, 0) practice is an addition-only construct (v1.7).
            // Mul/sub/div L1 white fall through to the canonical-pair path.
            practiceObjects.add(buildQuestionObject(op, lvl, belt, 0, 0, "current", null));
        } else {
            int[] pair = getCanonicalPair(op, lvl, belt);
            if (pair != null) {
                int a = pair[0], b = pair[1];
                practiceObjects.add(buildQuestionObject(op, lvl, belt, a, b, "current", null));
                if (a != b && isCommutative(op)) {
                    practiceObjects.add(buildQuestionObject(op, lvl, belt, b, a, "current", null));
                }
            } else {
                practiceObjects.add(buildQuestionObject(op, lvl, belt, 1, 1, "current", null));
            }
        }
        if (!practiceObjects.isEmpty()) {
            return cachedQuestions.saveAll(practiceObjects);
        }
        return List.of();
    }

    public List<GeneratedQuestion> buildPretestPracticeQuestions(String op, int lvl) {
        List<GeneratedQuestion> practiceObjects = new ArrayList<>();

        List<String> belts = List.of(Belt.WHITE.value(), Belt.YELLOW.value(), Belt.GREEN.value());
        for (String belt : belts) {
            int[] pair = getCanonicalPair(op, lvl, belt);
            if (pair != null) {
                int a = pair[0], b = pair[1];
                practiceObjects.add(buildQuestionObject(op, lvl, GameModeType.PRETEST.value(), a, b, "pretest-practice", null));
                if (a != b && isCommutative(op) && practiceObjects.size() < 3) {
                    practiceObjects.add(buildQuestionObject(op, lvl, GameModeType.PRETEST.value(), b, a, "pretest-practice", null));
                }
                if (practiceObjects.size() >= 3) break;
            }
        }

        if (practiceObjects.isEmpty()) {
            practiceObjects.add(buildQuestionObject(op, lvl, GameModeType.PRETEST.value(), 1, 1, "pretest-practice", null));
        }

        if (!practiceObjects.isEmpty()) {
            return cachedQuestions.saveAll(practiceObjects);
        }
        return List.of();
    }

    // ===== SURF / ROCKET QUESTION BUILDERS =====

    public GeneratedQuestion buildSurfQuestion(String op, int level, String beltOrDegree, int a, int b) {
        GeneratedQuestion g = new GeneratedQuestion();
        g.setOperation(op);
        g.setLevel(level);
        g.setBeltOrDegree(beltOrDegree);

        GeneratedQuestion.Params params = new GeneratedQuestion.Params();
        params.setA(a);
        params.setB(b);
        g.setParams(params);

        g.setQuestion(buildQuestionText(op, a, b));
        g.setCorrectAnswer(computeAnswer(op, a, b));

        g.setChoices(List.of());

        g.setSource("surf");
        g.setSeed(UUID.randomUUID().toString());

        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        g.setCreatedAt(now);
        g.setUpdatedAt(now);
        g.set__v(0);

        return g;
    }

    public GeneratedQuestion buildRocketQuestion(String op, int level, String beltOrDegree,
                                                  int a, int b, List<int[]> factPool) {
        GeneratedQuestion g = new GeneratedQuestion();
        g.setOperation(op);
        g.setLevel(level);
        g.setBeltOrDegree(beltOrDegree);

        GeneratedQuestion.Params params = new GeneratedQuestion.Params();
        params.setA(a);
        params.setB(b);
        g.setParams(params);

        int answer = computeAnswer(op, a, b);
        g.setQuestion(String.valueOf(answer));

        String correctExpression = buildQuestionText(op, a, b);
        List<String> expressionChoices = buildExpressionChoices(op, a, b, answer, factPool);

        g.setTextChoices(expressionChoices);
        g.setChoices(List.of());

        int correctIndex = expressionChoices.indexOf(correctExpression);
        g.setCorrectAnswer(correctIndex);

        g.setSource("rocket");
        g.setSeed(UUID.randomUUID().toString());

        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        g.setCreatedAt(now);
        g.setUpdatedAt(now);
        g.set__v(0);

        return g;
    }

    public GeneratedQuestion buildPracticeFromSurfQuestion(GeneratedQuestion surfQ) {
        GeneratedQuestion practice = new GeneratedQuestion();
        practice.setOperation(surfQ.getOperation());
        practice.setLevel(surfQ.getLevel());
        practice.setBeltOrDegree(surfQ.getBeltOrDegree());
        GeneratedQuestion.Params surfParams = new GeneratedQuestion.Params();
        surfParams.setA(surfQ.getParams().getA());
        surfParams.setB(surfQ.getParams().getB());
        practice.setParams(surfParams);
        practice.setQuestion(surfQ.getQuestion());
        practice.setCorrectAnswer(surfQ.getCorrectAnswer());
        practice.setSource("surf-practice");
        practice.setSeed(UUID.randomUUID().toString());

        practice.setChoices(buildChoices(surfQ.getOperation(),
                surfQ.getParams().getA(), surfQ.getParams().getB(), surfQ.getCorrectAnswer()));

        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        practice.setCreatedAt(now);
        practice.setUpdatedAt(now);

        return cachedQuestions.save(practice);
    }

    /**
     * Build a forward (multiple-choice) practice question from a wrong bonus question.
     * Same shape as the surf practice builder — bonus uses the normal multiple-choice format.
     */
    public GeneratedQuestion buildPracticeFromBonusQuestion(GeneratedQuestion bonusQ) {
        GeneratedQuestion practice = new GeneratedQuestion();
        practice.setOperation(bonusQ.getOperation());
        practice.setLevel(bonusQ.getLevel());
        practice.setBeltOrDegree(bonusQ.getBeltOrDegree());
        GeneratedQuestion.Params bonusParams = new GeneratedQuestion.Params();
        bonusParams.setA(bonusQ.getParams().getA());
        bonusParams.setB(bonusQ.getParams().getB());
        practice.setParams(bonusParams);
        practice.setQuestion(bonusQ.getQuestion());
        practice.setCorrectAnswer(bonusQ.getCorrectAnswer());
        practice.setSource("bonus-practice");
        practice.setSeed(UUID.randomUUID().toString());

        practice.setChoices(buildChoices(bonusQ.getOperation(),
                bonusQ.getParams().getA(), bonusQ.getParams().getB(), bonusQ.getCorrectAnswer()));

        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        practice.setCreatedAt(now);
        practice.setUpdatedAt(now);

        return cachedQuestions.save(practice);
    }

    public GeneratedQuestion buildPracticeFromRocketQuestion(GeneratedQuestion rocketQ) {
        String op = rocketQ.getOperation();
        int a = rocketQ.getParams().getA();
        int b = rocketQ.getParams().getB();
        int lvl = rocketQ.getLevel();
        String belt = rocketQ.getBeltOrDegree();

        List<int[]> factPool = new ArrayList<>();
        int[] currentPair = getCanonicalPair(op, lvl, belt);
        if (currentPair != null) {
            factPool.add(currentPair);
        }
        factPool.addAll(getPreviousPool(op, lvl, belt));
        if (factPool.isEmpty()) {
            factPool.add(new int[]{1, 1});
            factPool.add(new int[]{2, 1});
            factPool.add(new int[]{2, 2});
        }

        GeneratedQuestion practice = buildRocketQuestion(op, lvl, belt, a, b, factPool);
        practice.setSource("rocket-practice");

        return cachedQuestions.save(practice);
    }

    // ===== TIMER MANAGEMENT =====

    public void ensureTimer(QuizRun run) {
        if (run.getTimer() == null) run.setTimer(new QuizRun.Timer());
        if (run.getTimer().getLimitMs() == null) run.getTimer().setLimitMs(0L);
        if (run.getTimer().getRemainingMs() == null) run.getTimer().setRemainingMs(0L);
    }

    public void resumeTimer(QuizRun run) {
        ensureTimer(run);
        if ((run.isPretestMode() || isBlack(run.getBeltOrDegree())) && run.getTimer().getStartedAt() == null) {
            run.getTimer().setStartedAt(now());
        }
    }

    public void pauseTimer(QuizRun run) {
        ensureTimer(run);
        Instant started = run.getTimer().getStartedAt();
        if (started != null) {
            long active = Math.max(0, now().toEpochMilli() - started.toEpochMilli());
            run.setTotalActiveMs(nvl(run.getTotalActiveMs(), 0L) + active);
            if (run.isPretestMode() || isBlack(run.getBeltOrDegree())) {
                long remaining = Math.max(0, nvl(run.getTimer().getRemainingMs(), 0L) - active);
                run.getTimer().setRemainingMs(remaining);
            }
            run.getTimer().setStartedAt(null);
        }
    }

    public void timerSetLimit(QuizRun run, long ms) {
        ensureTimer(run);
        run.getTimer().setLimitMs(ms);
    }

    public void timerSetRemaining(QuizRun run, long ms) {
        ensureTimer(run);
        run.getTimer().setRemainingMs(ms);
    }

    public long timerGetLimit(QuizRun run) {
        ensureTimer(run);
        return nvl(run.getTimer().getLimitMs(), 0L);
    }

    public long timerGetRemaining(QuizRun run) {
        ensureTimer(run);
        return nvl(run.getTimer().getRemainingMs(), 0L);
    }

    // ===== RUN MANAGEMENT =====

    public void touch(QuizRun run) {
        run.setUpdatedAt(now());
        if (run.getCreatedAt() == null) run.setCreatedAt(now());
    }

    public Instant now() {
        return Instant.now(clock);
    }

    public QuizRun mustGetRun(String id) {
        return cachedQuizRuns.findById(id).orElseThrow(() -> new IllegalArgumentException("Run not found"));
    }

    // ===== COMPLETION CHECKS =====

    public boolean isComplete(QuizRun run) {
        if (run.isPretestMode()) {
            return isPretestComplete(run);
        }

        if (run.isSurfMode()) {
            return nvl(run.getCompletedSurfQuizzes(), 0) >= gameConfig.getSurfQuizzesRequired();
        }

        if (run.isRocketMode()) {
            return nvl(run.getCompletedRocketQuizzes(), 0) >= gameConfig.getRocketQuizzesRequired();
        }

        if (run.isBonusMode()) {
            return nvl(run.getBonusStreak(), 0) >= gameConfig.getBonusTargetCorrect();
        }

        if (run.isLightningMode()) {
            return nvl(run.getTotalCorrect(), 0) >= nvl(run.getTargetCorrect(), gameConfig.getLightningTargetCorrect());
        }

        int idx = nvl(run.getCurrentIndex(), 0);
        int planned = plannedCountFor(run);
        int items = (run.getItems() == null) ? planned : run.getItems().size();
        int total = Math.min(planned, items);
        return idx >= total;
    }

    public boolean isPretestComplete(QuizRun run) {
        int idx = nvl(run.getCurrentIndex(), 0);
        int total = gameConfig.getPretestQuestionCount();
        int items = (run.getItems() == null) ? total : run.getItems().size();
        return idx >= Math.min(total, items);
    }

    public int plannedCountFor(QuizRun run) {
        Integer saved = run.getTotalQuestions();
        if (saved != null && saved > 0) return saved;
        if (run.isPretestMode()) return gameConfig.getPretestQuestionCount();
        return isBlack(run.getBeltOrDegree()) ? BLACK_COUNT : COLORED_COUNT;
    }

    // ===== QUIZ SUMMARY =====

    public QuizSummary summaryOf(QuizRun run) {
        QuizSummary s = new QuizSummary();
        s.correct = nvl(run.getMainFlowCorrect(), 0);
        s.wrong = nvl(run.getWrong(), 0);
        s.totalActiveMs = nvl(run.getTotalActiveMs(), 0L);
        s.level = safe(run.getLevel(), 1);
        s.beltOrDegree = nvl(run.getBeltOrDegree(), "");
        s.sessionTotalMs = nvl(run.getTotalActiveMs(), 0L);

        if (run.isPretestMode()) {
            s.pretestMode = true;
            s.gameModeType = GameModeType.PRETEST.value();
            s.timeLimitMs = gameConfig.getPretestTimeLimitMs(run.getLevel());
        } else if (run.isSurfMode()) {
            s.gameModeType = GameModeType.SURF.value();
            s.completedSurfQuizzes = nvl(run.getCompletedSurfQuizzes(), 0);
            s.surfQuizFailures = nvl(run.getSurfQuizFailures(), 0);
        } else if (run.isRocketMode()) {
            s.gameModeType = GameModeType.ROCKET.value();
            s.completedRocketQuizzes = nvl(run.getCompletedRocketQuizzes(), 0);
            s.rocketQuizFailures = nvl(run.getRocketQuizFailures(), 0);
        } else if (run.isBonusMode()) {
            s.gameModeType = GameModeType.BONUS.value();
            // Per PRD: don't surface star/streak counter to client. Only the configured target.
            s.bonusTargetCorrect = gameConfig.getBonusTargetCorrect();
        } else if (run.isLightningMode()) {
            s.gameModeType = GameModeType.LIGHTNING.value();
            s.totalCorrect = nvl(run.getTotalCorrect(), 0);
            s.targetCorrect = run.getTargetCorrect();
        } else {
            s.gameMode = false;
        }

        return s;
    }

    // ===== ACTIVE GAME MODE RESUME =====

    public PrepareResponse findActiveGameModeResume(String userId) {
        Optional<QuizRun> activeLightning = cachedQuizRuns.findActiveLightningMode(userId);
        if (activeLightning.isPresent()) {
            QuizRun run = activeLightning.get();
            PrepareResponse out = new PrepareResponse();
            out.quizRunId = run.getId();
            out.resumed = true;
            out.gameMode = true;
            out.gameModeType = run.getGameModeType() != null ? run.getGameModeType() : GameModeType.LIGHTNING.value();
            out.targetCorrect = run.getTargetCorrect();
            out.totalCorrect = nvl(run.getTotalCorrect(), 0);
            out.currentIndex = nvl(run.getCurrentIndex(), 0);
            out.level = run.getLevel();
            out.beltOrDegree = run.getBeltOrDegree();
            out.operation = run.getOperation();
            out.practice = List.of();
            return out;
        }

        Optional<QuizRun> activeSurf = cachedQuizRuns.findActiveSurfModeForUser(userId);
        if (activeSurf.isPresent()) {
            QuizRun run = activeSurf.get();
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

        Optional<QuizRun> activeRocket = cachedQuizRuns.findActiveRocketModeForUser(userId);
        if (activeRocket.isPresent()) {
            QuizRun run = activeRocket.get();
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

        Optional<QuizRun> activeBonus = cachedQuizRuns.findActiveBonusModeForUser(userId);
        if (activeBonus.isPresent()) {
            QuizRun run = activeBonus.get();
            PrepareResponse out = new PrepareResponse();
            out.quizRunId = run.getId();
            out.resumed = true;
            out.gameMode = true;
            out.gameModeType = GameModeType.BONUS.value();
            // Counter (bonusStreak) is intentionally NOT included — client must not show progress.
            out.bonusTargetCorrect = gameConfig.getBonusTargetCorrect();
            out.bonusVideoIntervalCorrect = gameConfig.getBonusVideoIntervalCorrect();
            out.bonusCorrectStreak = nvl(run.getBonusStreak(), 0);
            // If user is mid-practice (got a wrong answer last and hasn't completed practice),
            // expose that boolean so the client renders the practice question rather than the next quiz question.
            if (Boolean.TRUE.equals(run.getBonusInPractice())) {
                out.bonusInPractice = true;
                out.needsRestart = true;
            }
            out.currentIndex = nvl(run.getCurrentIndex(), 0);
            out.level = run.getLevel();
            out.beltOrDegree = run.getBeltOrDegree();
            out.operation = run.getOperation();
            out.practice = List.of();
            return out;
        }

        return null;
    }

    // ===== RESPONSE BUILDERS =====

    public StartResponse buildStartResponse(QuizRun run, List<GeneratedQuestion> questions, boolean resumed) {
        StartResponse out = new StartResponse();
        out.quizRunId = run.getId();
        out.questions = questions;
        out.resumed = resumed;
        out.currentIndex = nvl(run.getCurrentIndex(), 0);
        out.run = new RunInfo(run.getId(), run.getStatus(), run.getCurrentIndex());

        if (run.isPretestMode()) {
            out.pretestMode = true;
            out.gameModeType = GameModeType.PRETEST.value();
            out.pretestTimeLimitMs = gameConfig.getPretestTimeLimitMs(run.getLevel());
            out.pretestQuestionCount = gameConfig.getPretestQuestionCount();
            out.gameMode = false;
        } else if (run.isSurfMode()) {
            out.gameMode = true;
            out.gameModeType = GameModeType.SURF.value();
            out.surfQuizNumber = nvl(run.getSurfQuizNumber(), 1);
            out.surfCorrectStreak = nvl(run.getSurfCorrectStreak(), 0);
            out.completedSurfQuizzes = nvl(run.getCompletedSurfQuizzes(), 0);
            out.surfQuizzesRequired = gameConfig.getSurfQuizzesRequired();
            out.questionsPerQuiz = gameConfig.getSurfQuestionsPerQuiz();
        } else if (run.isRocketMode()) {
            out.gameMode = true;
            out.gameModeType = GameModeType.ROCKET.value();
            out.rocketQuizNumber = nvl(run.getRocketQuizNumber(), 1);
            out.rocketCorrectStreak = nvl(run.getRocketCorrectStreak(), 0);
            out.completedRocketQuizzes = nvl(run.getCompletedRocketQuizzes(), 0);
            out.rocketQuizzesRequired = gameConfig.getRocketQuizzesRequired();
            out.questionsPerQuiz = gameConfig.getRocketQuestionsPerQuiz();
        } else if (run.isBonusMode()) {
            out.gameMode = true;
            out.gameModeType = GameModeType.BONUS.value();
            out.bonusTargetCorrect = gameConfig.getBonusTargetCorrect();
            out.bonusVideoIntervalCorrect = gameConfig.getBonusVideoIntervalCorrect();
            out.bonusCorrectStreak = nvl(run.getBonusStreak(), 0);
        } else if (run.isLightningMode()) {
            out.gameMode = true;
            out.gameModeType = GameModeType.LIGHTNING.value();
            out.targetCorrect = run.getTargetCorrect();
            out.totalCorrect = nvl(run.getTotalCorrect(), 0);
        } else {
            out.gameMode = false;
        }

        out.timer = new TimerInfo(timerGetLimit(run), timerGetRemaining(run));
        return out;
    }

    public StartResponse buildStartResumedResponse(QuizRun run) {
        List<GeneratedQuestion> questions = cachedQuestions.findAllById(run.getItems());

        if (run.isPretestMode()) {
            ensureTimer(run);
            run.getTimer().setStartedAt(now());
            touch(run);
            cachedQuizRuns.save(run);
        } else if (isBlack(run.getBeltOrDegree()) && !run.isSurfMode() && !run.isRocketMode()) {
            ensureTimer(run);
            run.getTimer().setStartedAt(now());
            touch(run);
            cachedQuizRuns.save(run);
        }

        return buildStartResponse(run, questions, true);
    }

    public AnswerResponse buildCompletedResponse(QuizRun run) {
        AnswerResponse out = new AnswerResponse();
        out.completed = true;
        out.passed = Boolean.TRUE.equals(run.getPassed());
        out.summary = summaryOf(run);
        out.sessionCorrectCount = nvl(run.getMainFlowCorrect(), 0);

        if (run.isPretestMode()) {
            out.pretestMode = true;
            out.gameModeType = GameModeType.PRETEST.value();
            out.totalTimeMs = nvl(run.getTotalActiveMs(), 0L);
            out.timeLimitMs = gameConfig.getPretestTimeLimitMs(run.getLevel());
            out.gameMode = false;
        } else if (run.isSurfMode()) {
            out.gameMode = true;
            out.gameModeType = GameModeType.SURF.value();
            out.completedSurfQuizzes = nvl(run.getCompletedSurfQuizzes(), 0);
        } else if (run.isRocketMode()) {
            out.gameMode = true;
            out.gameModeType = GameModeType.ROCKET.value();
            out.completedRocketQuizzes = nvl(run.getCompletedRocketQuizzes(), 0);
            if (Boolean.TRUE.equals(run.getPassed())) {
                out.beltAwarded = false;
                out.bonusRequired = true;
            }
        } else if (run.isBonusMode()) {
            out.gameMode = true;
            out.gameModeType = GameModeType.BONUS.value();
            out.bonusCorrectStreak = nvl(run.getBonusStreak(), 0);
            if (Boolean.TRUE.equals(run.getPassed())) {
                out.beltAwarded = true;
                out.bonusComplete = true;
            }
        } else if (run.isLightningMode()) {
            out.gameMode = true;
            out.gameModeType = GameModeType.LIGHTNING.value();
            out.totalCorrect = nvl(run.getTotalCorrect(), 0);
            out.targetCorrect = run.getTargetCorrect();
        } else {
            out.gameMode = false;
        }

        if (run.getUserId() != null) {
            try {
                out.dailyStats = daily.getForUser(run.getUserId());
            } catch (Exception e) {
                log.error("Failed to fetch daily stats", e);
            }
        }

        return out;
    }

    public AnswerResponse buildDuplicateResponse(QuizRun run) {
        AnswerResponse resp = new AnswerResponse();
        resp.duplicate = true;
        resp.nextIndex = nvl(run.getCurrentIndex(), 0);

        if (run.isSurfMode()) {
            resp.gameMode = true;
            resp.gameModeType = GameModeType.SURF.value();
            resp.surfCorrectStreak = nvl(run.getSurfCorrectStreak(), 0);
            resp.surfQuizNumber = run.getSurfQuizNumber();
        } else if (run.isRocketMode()) {
            resp.gameMode = true;
            resp.gameModeType = GameModeType.ROCKET.value();
            resp.rocketCorrectStreak = nvl(run.getRocketCorrectStreak(), 0);
            resp.rocketQuizNumber = run.getRocketQuizNumber();
        } else if (run.isBonusMode()) {
            resp.gameMode = true;
            resp.gameModeType = GameModeType.BONUS.value();
            resp.bonusCorrectStreak = nvl(run.getBonusStreak(), 0);
        } else if (run.isLightningMode()) {
            resp.gameMode = true;
            resp.gameModeType = GameModeType.LIGHTNING.value();
            resp.totalCorrect = nvl(run.getTotalCorrect(), 0);
            resp.targetCorrect = run.getTargetCorrect();
        }

        return resp;
    }

    // ===== HANDLE FORCE PASS =====

    public AnswerResponse handleForcePass(QuizRun run, long responseMs, boolean skipLevelAward) {
        long timeDelta = responseMs;
        run.setTotalActiveMs(nvl(run.getTotalActiveMs(), 0L) + timeDelta);
        run.setMainFlowCorrect(nvl(run.getMainFlowCorrect(), 0) + 1);

        int actualCorrectBefore = 0;

        if (run.isLightningMode()) {
            actualCorrectBefore = nvl(run.getTotalCorrect(), 0);
            run.setTotalCorrect(run.getTargetCorrect());
            log.info("[LIGHTNING] Force pass - setting totalCorrect from {} to {}", actualCorrectBefore, run.getTargetCorrect());
        }

        if (run.isSurfMode()) {
            run.setCompletedSurfQuizzes(gameConfig.getSurfQuizzesRequired());
            run.setSurfCorrectStreak(gameConfig.getSurfQuestionsPerQuiz());
            log.info("[SURF] Force pass - marking all {} quizzes as completed", gameConfig.getSurfQuizzesRequired());
        }

        if (run.isRocketMode()) {
            run.setCompletedRocketQuizzes(gameConfig.getRocketQuizzesRequired());
            run.setRocketCorrectStreak(gameConfig.getRocketQuestionsPerQuiz());
            log.info("[ROCKET] Force pass - marking all {} quizzes as completed", gameConfig.getRocketQuizzesRequired());
        }

        if (run.isBonusMode()) {
            run.setBonusStreak(gameConfig.getBonusTargetCorrect());
            run.setBonusInPractice(false);
            log.info("[BONUS] Force pass - setting bonusStreak to {}", gameConfig.getBonusTargetCorrect());
        }

        if (run.isPretestMode()) {
            if (skipLevelAward) {
                log.info("[PRETEST] Force pass with skipLevelAward - marking as taken but NOT awarding level");
            } else {
                log.info("[PRETEST] Force pass - awarding entire level");
            }
        }

        DailyStatsResponse dailyStats = null;
        if (run.getUserId() != null) {
            try {
                dailyStats = daily.increment(run.getUserId(), 1, Math.max(0L, timeDelta));
            } catch (Exception e) {
                log.error("Daily increment failed", e);
            }
        }

        run.setPassed(true);
        run.setStatus(QuizStatus.COMPLETED.value());
        touch(run);
        cachedQuizRuns.save(run);
        cachedQuizRuns.forceFlush(run.getId());

        if (run.getUserId() != null) {
            daily.forceFlush(run.getUserId());
        }

        AnswerResponse out = new AnswerResponse();

        if (run.getUserId() != null) {
            try {
                if (run.isPretestMode()) {
                    if (skipLevelAward) {
                        progression.markPretestTaken(run.getUserId(), run.getOperation(), run.getLevel(), false);
                        Map<String, Object> fullProgress = progression.getProgress(run.getUserId());
                        out.updatedProgress = fullProgress;
                        out.levelAwarded = false;
                        out.pretestSkipped = true;
                    } else {
                        progression.markPretestTaken(run.getUserId(), run.getOperation(), run.getLevel(), true);
                        Map<String, Object> fullProgress = progression.awardEntireLevel(
                                run.getUserId(), run.getOperation(), run.getLevel()
                        );
                        out.updatedProgress = fullProgress;
                        out.levelAwarded = true;
                    }
                } else if (run.isLightningMode() || run.isSurfMode() || run.isRocketMode()) {
                    // Lightning/Surf/Rocket forcePass: mark the run done but DEFER belt award.
                    // Belt is awarded only when the final mode in the chain (Bonus) completes.
                    // No call to unlockOnPass here — progress advances only when bonus completes.
                    out.beltAwarded = false;
                    if (run.isRocketMode()) out.bonusRequired = true;
                } else {
                    // Bonus and Normal modes: award belt on forcePass.
                    Map<String, Object> fullProgress = progression.unlockOnPass(
                            run.getUserId(), run.getOperation(), run.getLevel(), run.getBeltOrDegree()
                    );
                    out.updatedProgress = fullProgress;
                    out.beltAwarded = true;
                }
            } catch (Exception e) {
                log.error("Progression unlock failed", e);
                out.beltAwarded = false;
                out.levelAwarded = false;
            }
        }

        if (run.getUserId() != null) {
            try {
                dailyStats = daily.getForUser(run.getUserId());
            } catch (Exception e) {
                log.error("Failed to fetch daily stats", e);
            }
        }

        out.summary = summaryOf(run);
        out.completed = true;
        out.passed = true;
        out.gameMode = run.isGameMode();
        out.sessionCorrectCount = nvl(run.getMainFlowCorrect(), 0);
        out.dailyStats = dailyStats;
        out.forcePass = true;

        if (run.isPretestMode()) {
            out.pretestMode = true;
            out.gameModeType = GameModeType.PRETEST.value();
        } else if (run.isSurfMode()) {
            out.gameModeType = GameModeType.SURF.value();
            out.completedSurfQuizzes = run.getCompletedSurfQuizzes();
            out.surfQuizzesRequired = gameConfig.getSurfQuizzesRequired();
        } else if (run.isRocketMode()) {
            out.gameModeType = GameModeType.ROCKET.value();
            out.completedRocketQuizzes = run.getCompletedRocketQuizzes();
            out.rocketQuizzesRequired = gameConfig.getRocketQuizzesRequired();
        } else if (run.isBonusMode()) {
            out.gameModeType = GameModeType.BONUS.value();
            out.bonusComplete = true;
        } else if (run.isLightningMode()) {
            out.gameModeType = GameModeType.LIGHTNING.value();
            out.lightningComplete = true;
            out.surfRequired = false;
            out.totalCorrect = run.getTotalCorrect();
            out.targetCorrect = run.getTargetCorrect();
            out.actualCorrectBeforeForce = actualCorrectBefore;
        }

        return out;
    }

    // ===== PRACTICE HELPERS =====

    public AnswerResponse buildPracticeIncorrectResponse(QuizRun run, GeneratedQuestion pq) {
        AnswerResponse resp = new AnswerResponse();
        resp.practice = pq;
        resp.stillPracticing = true;

        if (run.isPretestMode()) {
            resp.pretestMode = true;
            resp.gameModeType = GameModeType.PRETEST.value();
        } else if (run.isSurfMode()) {
            resp.gameMode = true;
            resp.gameModeType = GameModeType.SURF.value();
            resp.surfQuizNumber = run.getSurfQuizNumber();
        } else if (run.isRocketMode()) {
            resp.gameMode = true;
            resp.gameModeType = GameModeType.ROCKET.value();
            resp.rocketQuizNumber = run.getRocketQuizNumber();
        } else if (run.isBonusMode()) {
            resp.gameMode = true;
            resp.gameModeType = GameModeType.BONUS.value();
            // Counter not exposed; UI just shows the practice question.
        } else if (run.isLightningMode()) {
            resp.gameMode = true;
            resp.gameModeType = GameModeType.LIGHTNING.value();
            resp.totalCorrect = nvl(run.getTotalCorrect(), 0);
            resp.targetCorrect = run.getTargetCorrect();
        }
        return resp;
    }

    /**
     * Advance index after correct practice answer and build resume response.
     * Returns null if pretest is complete (caller must handle via pretestHandler).
     */
    public AnswerResponse resumeAfterPractice(QuizRun run) {
        int nextIndex;
        if (run.isLightningMode()) {
            nextIndex = (nvl(run.getCurrentIndex(), 0) + 1) % run.getItems().size();
        } else {
            nextIndex = nvl(run.getCurrentIndex(), 0) + 1;
        }
        run.setCurrentIndex(nextIndex);

        if (!run.isGameMode() && !run.isPretestMode() && isComplete(run)) {
            run.setPassed(false);
            run.setStatus(QuizStatus.COMPLETED.value());
            pauseTimer(run);
            touch(run);
            cachedQuizRuns.save(run);
            cachedQuizRuns.forceFlush(run.getId());

            AnswerResponse resp = new AnswerResponse();
            resp.completed = true;
            resp.passed = false;
            resp.gameMode = false;
            resp.summary = summaryOf(run);
            resp.sessionCorrectCount = nvl(run.getMainFlowCorrect(), 0);
            return resp;
        }

        if (run.isPretestMode() && isPretestComplete(run)) {
            return null;
        }

        resumeTimer(run);
        touch(run);
        cachedQuizRuns.save(run);

        String nextId = currentQuestionId(run);
        GeneratedQuestion nextMain = (nextId != null) ?
                cachedQuestions.findById(nextId).orElse(null) : null;

        AnswerResponse resp = new AnswerResponse();
        resp.resume = true;
        resp.next = nextMain;
        resp.nextIndex = nextIndex;

        if (run.isPretestMode()) {
            resp.pretestMode = true;
            resp.gameModeType = GameModeType.PRETEST.value();
        } else if (run.isLightningMode()) {
            resp.gameMode = true;
            resp.gameModeType = GameModeType.LIGHTNING.value();
            resp.totalCorrect = nvl(run.getTotalCorrect(), 0);
            resp.targetCorrect = run.getTargetCorrect();
        }
        return resp;
    }

    // ===== INACTIVITY HELPERS =====

    public AnswerResponse buildLateInactivityResponse(QuizRun run) {
        boolean isPassed = QuizStatus.COMPLETED.value().equalsIgnoreCase(run.getStatus()) &&
                Boolean.TRUE.equals(run.getPassed());
        AnswerResponse resp = new AnswerResponse();
        resp.completed = true;
        resp.passed = isPassed;
        resp.reason = "late-inactivity";
        resp.sessionCorrectCount = nvl(run.getMainFlowCorrect(), 0);

        if (run.isPretestMode()) {
            resp.pretestMode = true;
            resp.gameModeType = GameModeType.PRETEST.value();
        } else if (run.isSurfMode()) {
            resp.gameMode = true;
            resp.gameModeType = GameModeType.SURF.value();
        } else if (run.isRocketMode()) {
            resp.gameMode = true;
            resp.gameModeType = GameModeType.ROCKET.value();
        } else if (run.isBonusMode()) {
            resp.gameMode = true;
            resp.gameModeType = GameModeType.BONUS.value();
        } else if (run.isLightningMode()) {
            resp.gameMode = true;
            resp.gameModeType = GameModeType.LIGHTNING.value();
            resp.totalCorrect = nvl(run.getTotalCorrect(), 0);
            resp.targetCorrect = run.getTargetCorrect();
        }
        return resp;
    }

    public AnswerResponse handleNormalInactivity(QuizRun run, GeneratedQuestion currentQuestion) {
        pauseTimer(run);
        run.setWrong(nvl(run.getWrong(), 0) + 1);
        touch(run);
        cachedQuizRuns.save(run);

        attemptService.recordInactivityAsync(run, currentQuestion);

        AnswerResponse resp = new AnswerResponse();
        resp.practice = currentQuestion;

        if (run.isPretestMode()) {
            resp.pretestMode = true;
            resp.gameModeType = GameModeType.PRETEST.value();
        } else if (run.isLightningMode()) {
            resp.gameMode = true;
            resp.gameModeType = GameModeType.LIGHTNING.value();
            resp.totalCorrect = nvl(run.getTotalCorrect(), 0);
            resp.targetCorrect = run.getTargetCorrect();
        }

        return resp;
    }

    // ===== COMPLETE =====

    public Object complete(String quizRunId) {
        QuizRun run = mustGetRun(quizRunId);
        if (!QuizStatus.COMPLETED.value().equalsIgnoreCase(nvl(run.getStatus(), ""))) {
            pauseTimer(run);
            run.setStatus(QuizStatus.COMPLETED.value());
            touch(run);
            cachedQuizRuns.save(run);
            cachedQuizRuns.forceFlush(run.getId());
        }

        cleanupExecutor.schedule(() -> {
            try {
                cachedQuizRuns.evict(quizRunId);
                log.debug("[CLEANUP] Evicted completed quiz: {}", quizRunId);
            } catch (Exception e) {
                log.error("[CLEANUP ERROR]", e);
            }
        }, 5, TimeUnit.MINUTES);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("correct", nvl(run.getMainFlowCorrect(), 0));
        result.put("wrong", nvl(run.getWrong(), 0));
        result.put("totalActiveMs", nvl(run.getTotalActiveMs(), 0L));
        result.put("passed", Boolean.TRUE.equals(run.getPassed()));

        if (run.isPretestMode()) {
            result.put("pretestMode", true);
            result.put("gameModeType", GameModeType.PRETEST.value());
            result.put("timeLimitMs", gameConfig.getPretestTimeLimitMs(run.getLevel()));
            result.put("gameMode", false);
        } else if (run.isSurfMode()) {
            result.put("gameMode", true);
            result.put("gameModeType", GameModeType.SURF.value());
            result.put("completedSurfQuizzes", nvl(run.getCompletedSurfQuizzes(), 0));
        } else if (run.isRocketMode()) {
            result.put("gameMode", true);
            result.put("gameModeType", GameModeType.ROCKET.value());
            result.put("completedRocketQuizzes", nvl(run.getCompletedRocketQuizzes(), 0));
            // If rocket passed, the belt is now gated behind bonus mode — surface this here too
            // for clients that confirm completion via /complete instead of reading /answer's response.
            if (Boolean.TRUE.equals(run.getPassed())) {
                result.put("bonusRequired", true);
                result.put("beltAwarded", false);
            }
        } else if (run.isBonusMode()) {
            result.put("gameMode", true);
            result.put("gameModeType", GameModeType.BONUS.value());
            // bonusStreak intentionally omitted from /complete result map per PRD.
        } else if (run.isLightningMode()) {
            result.put("gameMode", true);
            result.put("gameModeType", GameModeType.LIGHTNING.value());
            result.put("totalCorrect", nvl(run.getTotalCorrect(), 0));
            result.put("targetCorrect", run.getTargetCorrect());
        } else {
            result.put("gameMode", false);
        }

        return Map.of("completed", true, "result", result);
    }

    @PreDestroy
    public void shutdown() {
        cleanupExecutor.shutdown();
        try {
            if (!cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                cleanupExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            cleanupExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ===== ACCESSORS (for handler classes) =====

    public CachedQuizRunService getCachedQuizRuns() {
        return cachedQuizRuns;
    }

    public CachedQuestionService getCachedQuestions() {
        return cachedQuestions;
    }

    public GameConfigService getGameConfig() {
        return gameConfig;
    }
}
