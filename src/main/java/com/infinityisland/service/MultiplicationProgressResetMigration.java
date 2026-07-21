package com.infinityisland.service;

import com.infinityisland.dao.GameConfig;
import com.infinityisland.dao.user.User;
import com.infinityisland.model.Operation;
import com.infinityisland.repositories.GameConfigRepository;
import com.infinityisland.repositories.UserRepository;
import com.infinityisland.util.ProgressDefaults;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One-time hard-reset of multiplication (and downstream division) progress when the
 * v1.7 canonical Scope & Sequence catalog ships.
 *
 * The v1.6 seed implemented an off-by-one curriculum (L1 = ×0 identity instead of
 * the S&S layout of "×0 spread across L1-L2, then ×1 starting L3"). Users who
 * advanced under that seed earned belts against the wrong fact set. v1.7 re-seeds
 * the catalog and resets those users so they start the corrected progression
 * cleanly.
 *
 * Gate: {@code GameConfig.mulCatalogVersion}. The destructive rewrite only runs
 * when {@code app.run-mul-progress-reset-migration=true}; otherwise startup stamps
 * the version marker and leaves user progress untouched.
 *
 * What it does for every user with any {@code progress.mul} data:
 *  - Resets {@code progress.mul} to a baseline {@code {L1: locked-pretest-not-taken}}.
 *    The L1 unlock flag is preserved if subtraction is fully complete (so users
 *    who legitimately unlocked mul stay unlocked at L1 white).
 *  - Resets {@code progress.div} entirely (any div progress was earned against a
 *    mul completion that no longer applies).
 */
@Component
@Order(1)
public class MultiplicationProgressResetMigration {

    private static final Logger log = LoggerFactory.getLogger(MultiplicationProgressResetMigration.class);

    private static final int TARGET_VERSION = 2;

    @Autowired
    private GameConfigRepository gameConfigRepo;

    @Autowired
    private UserRepository userRepo;

    @Value("${app.run-mul-progress-reset-migration:false}")
    private boolean runDestructiveReset;

    @PostConstruct
    public void runIfNeeded() {
        Optional<GameConfig> opt = gameConfigRepo.findById("default");
        if (opt.isEmpty()) {
            log.info("[MIGRATION v1.7] No game_config document yet; skipping mul/div reset (nothing to migrate)");
            return;
        }
        GameConfig cfg = opt.get();
        Integer currentVersion = cfg.getMulCatalogVersion();
        if (currentVersion != null && currentVersion >= TARGET_VERSION) {
            return; // Already applied.
        }

        if (!runDestructiveReset) {
            cfg.setMulCatalogVersion(TARGET_VERSION);
            gameConfigRepo.save(cfg);
            log.info("[MIGRATION v1.7] Destructive mul/div reset is disabled; stamped mulCatalogVersion={} without changing user progress",
                    TARGET_VERSION);
            return;
        }

        log.info("[MIGRATION v1.7] Starting mul/div progress hard reset (current version={}, target={})",
                currentVersion, TARGET_VERSION);

        List<User> all = userRepo.findAll();
        int touched = 0;
        for (User u : all) {
            Map<String, Object> progress = u.getProgress();
            if (progress == null) continue;

            Object mul = progress.get(Operation.MUL.value());
            Object div = progress.get(Operation.DIV.value());
            if (mul == null && div == null) continue;

            boolean subComplete = isSubFullyComplete(progress);
            progress.put(Operation.MUL.value(), freshOpNode(subComplete));
            progress.put(Operation.DIV.value(), freshOpNode(false));
            u.setProgress(progress);
            userRepo.save(u);
            touched++;
        }

        cfg.setMulCatalogVersion(TARGET_VERSION);
        gameConfigRepo.save(cfg);
        log.info("[MIGRATION v1.7] Mul/div progress reset complete: {} users touched, mulCatalogVersion={}",
                touched, TARGET_VERSION);
    }

    private Map<String, Object> freshOpNode(boolean l1Unlocked) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("L1", ProgressDefaults.createLevelNode(1, l1Unlocked));
        return node;
    }

    @SuppressWarnings("unchecked")
    private boolean isSubFullyComplete(Map<String, Object> progress) {
        Object subObj = progress.get(Operation.SUB.value());
        if (!(subObj instanceof Map)) return false;
        Map<String, Object> sub = (Map<String, Object>) subObj;
        Object l11 = sub.get("L11");
        if (!(l11 instanceof Map)) return false;
        Object completed = ((Map<String, Object>) l11).get("completed");
        return Boolean.TRUE.equals(completed);
    }
}
