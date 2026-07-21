package com.infinityisland.config;

import com.infinityisland.dao.QuizRun;
import com.infinityisland.model.QuizStatus;
import com.infinityisland.dao.user.User;
import com.infinityisland.repositories.QuizRunRepository;
import com.infinityisland.repositories.UserRepository;
import com.infinityisland.service.CachedQuizRunService;
import com.infinityisland.service.CachedUserService;
import com.infinityisland.service.DailyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * Warm up caches on application startup
 */
@Component
@Order(10)
public class CacheWarmupRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CacheWarmupRunner.class);

    private final DailyService dailyService;
    private final CachedUserService userService;
    private final CachedQuizRunService quizRunService;
    private final UserRepository userRepo;
    private final QuizRunRepository quizRunRepo;

    public CacheWarmupRunner(DailyService dailyService,
                             CachedUserService userService,
                             CachedQuizRunService quizRunService,
                             UserRepository userRepo,
                             QuizRunRepository quizRunRepo) {
        this.dailyService = dailyService;
        this.userService = userService;
        this.quizRunService = quizRunService;
        this.userRepo = userRepo;
        this.quizRunRepo = quizRunRepo;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("[WARMUP] Starting cache warmup...");
        long start = System.currentTimeMillis();

        try {
            // 1. Warm daily summaries (already implemented)
            dailyService.warmCache();

            // 2. Warm recent active users (logged in last 7 days)
            LocalDate cutoff = LocalDate.now().minusDays(7);
            List<User> recentUsers = userRepo.findAll().stream()
                    .filter(u -> {
                        String lastLogin = u.getLastLoginDate();
                        if (lastLogin == null) return false;
                        try {
                            return LocalDate.parse(lastLogin).isAfter(cutoff);
                        } catch (Exception e) {
                            return false;
                        }
                    })
                    .toList();

            for (User user : recentUsers) {
                userService.findById(user.getId());
                if (user.getPin() != null) {
                    userService.findByPin(user.getPin());
                }
            }

            // 3. Warm active quiz runs (prepared/running only)
            List<QuizRun> activeRuns = quizRunRepo.findAll().stream()
                    .filter(r -> QuizStatus.PREPARED.value().equals(r.getStatus()) || QuizStatus.RUNNING.value().equals(r.getStatus()))
                    .toList();

            for (QuizRun run : activeRuns) {
                quizRunService.findById(run.getId());
            }

            long elapsed = System.currentTimeMillis() - start;
            log.info("[WARMUP] Completed: {} users, {} quiz runs in {}ms", recentUsers.size(),
                    activeRuns.size(), elapsed);

        } catch (Exception e) {
            log.error("[WARMUP] Failed: {}", e.getMessage(), e);
        }
    }
}