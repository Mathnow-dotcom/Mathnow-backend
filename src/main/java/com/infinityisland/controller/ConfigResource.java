package com.infinityisland.controller;

import com.infinityisland.dao.GameConfig;
import com.infinityisland.service.GameConfigService;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Admin-only API for dynamic game configuration.
 *
 * All endpoints require admin PIN (enforced by PinAuthFilter).
 *
 * Endpoints:
 * - GET /api/config           - Get current configuration
 * - PUT /api/config           - Update configuration
 * - POST /api/config/reset        - Reset to defaults
 * - PUT /api/config/black-belt/{degree} - Update single black belt timer
 * - PUT /api/config/pretest-time/{level} - Update pretest time limit for a level
 * - PUT /api/config/admin-pin      - Change admin PIN
 * - POST /api/config/reload       - Force reload from database
 */
@Component
@Path("/config")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ConfigResource {

    @Autowired
    private GameConfigService configService;

    /**
     * GET /api/config
     * <p>
     * Returns current game configuration (excluding admin PIN for security).
     */
    @GET
    public Response getConfig(@HeaderParam("x-pin") String pin) {
        if (!configService.isValidAdminPin(pin)) {
            return Response.status(403)
                    .entity(Map.of("error", "Admin access required"))
                    .build();
        }
        GameConfig config = configService.getConfig();
        return Response.ok(formatConfigResponse(config, false)).build();
    }

    /**
     * PUT /api/config
     * <p>
     * Update game configuration. Partial updates supported.
     * NOTE: Admin PIN cannot be updated here - use PUT /api/config/admin-pin
     */
    @PUT
    public Response updateConfig(@HeaderParam("x-pin") String pin, Map<String, Object> body) {
        if (!configService.isValidAdminPin(pin)) {
            return Response.status(403)
                    .entity(Map.of("error", "Admin access required"))
                    .build();
        }

        try {
            // Check if someone is trying to update adminPin through this endpoint
            if (body.containsKey("adminPin")) {
                return Response.status(400)
                        .entity(Map.of("error", "Use PUT /api/config/admin-pin to change admin PIN"))
                        .build();
            }

            GameConfig updates = parseConfigUpdates(body);
            GameConfig updated = configService.updateConfig(updates, "admin");

            return Response.ok(Map.of(
                    "success", true,
                    "message", "Configuration updated successfully",
                    "config", formatConfigResponse(updated, false)
            )).build();

        } catch (IllegalArgumentException e) {
            return Response.status(400)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        } catch (Exception e) {
            return Response.status(500)
                    .entity(Map.of("error", "Failed to update configuration: " + e.getMessage()))
                    .build();
        }
    }

    /**
     * PUT /api/config/admin-pin
     * <p>
     * Change the admin PIN.
     * <p>
     * Request body:
     * {
     * "currentPin": "7878",
     * "newPin": "9999"
     * }
     */
    @PUT
    @Path("/admin-pin")
    public Response updateAdminPin(@HeaderParam("x-pin") String pin, ConfigDtos.UpdateAdminPinRequest body) {
        try {
            if (body == null) {
                return Response.status(400)
                        .entity(Map.of("error", "Request body required"))
                        .build();
            }

            String currentPin = body.currentPin();
            String newPin = body.newPin();

            if (currentPin == null || currentPin.isBlank()) {
                return Response.status(400)
                        .entity(Map.of("error", "currentPin is required"))
                        .build();
            }
            if (newPin == null || newPin.isBlank()) {
                return Response.status(400)
                        .entity(Map.of("error", "newPin is required"))
                        .build();
            }

            configService.updateAdminPin(currentPin, newPin, "admin");

            return Response.ok(Map.of(
                    "success", true,
                    "message", "Admin PIN updated successfully. Use new PIN for future requests."
            )).build();

        } catch (SecurityException e) {
            return Response.status(403)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        } catch (IllegalArgumentException e) {
            return Response.status(400)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        } catch (Exception e) {
            return Response.status(500)
                    .entity(Map.of("error", "Failed to update admin PIN: " + e.getMessage()))
                    .build();
        }
    }

    /**
     * PUT /api/config/black-belt/{degree}
     * <p>
     * Update a single black belt timer.
     */
    @PUT
    @Path("/black-belt/{degree}")
    public Response updateBlackBeltTimer(
            @HeaderParam("x-pin") String pin,
            @PathParam("degree") int degree,
            ConfigDtos.UpdateBlackBeltTimerRequest body) {
        if (!configService.isValidAdminPin(pin)) {
            return Response.status(403)
                    .entity(Map.of("error", "Admin access required"))
                    .build();
        }
        try {
            if (body == null || body.timerMs() == null) {
                return Response.status(400)
                        .entity(Map.of("error", "timerMs is required"))
                        .build();
            }

            long timerMs = body.timerMs();
            GameConfig updated = configService.updateBlackBeltTimer(degree, timerMs, "admin");

            return Response.ok(Map.of(
                    "success", true,
                    "message", "Black belt degree " + degree + " timer updated to " + timerMs + "ms",
                    "config", formatConfigResponse(updated, false)
            )).build();

        } catch (IllegalArgumentException e) {
            return Response.status(400)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        }
    }

    /**
     * PUT /api/config/pretest-time/{level}
     * <p>
     * Update pretest time limit for a specific level.
     * Levels 1-19 are supported. Set timeLimitMs to 0 to remove
     * the level-specific override (falls back to default).
     */
    @PUT
    @Path("/pretest-time/{level}")
    public Response updatePretestTimeLimit(
            @HeaderParam("x-pin") String pin,
            @PathParam("level") int level,
            ConfigDtos.UpdatePretestTimeLimitRequest body) {
        if (!configService.isValidAdminPin(pin)) {
            return Response.status(403)
                    .entity(Map.of("error", "Admin access required"))
                    .build();
        }
        try {
            if (level < 1 || level > 19) {
                return Response.status(400)
                        .entity(Map.of("error", "Level must be between 1 and 19"))
                        .build();
            }

            if (body == null || body.timeLimitMs() == null) {
                return Response.status(400)
                        .entity(Map.of("error", "timeLimitMs is required"))
                        .build();
            }

            long timeLimitMs = body.timeLimitMs();

            // Build updates with per-level time limit
            GameConfig updates = new GameConfig();
            Map<Integer, Long> levelTimeLimits = new java.util.HashMap<>();

            if (timeLimitMs == 0) {
                // Remove the level-specific override - handled by removing from map
                // For now, we'll set it to -1 to signal removal (handled in service)
                levelTimeLimits.put(level, -1L);
            } else {
                if (timeLimitMs < 1000) {
                    return Response.status(400)
                            .entity(Map.of("error", "timeLimitMs must be >= 1000 (or 0 to remove override)"))
                            .build();
                }
                levelTimeLimits.put(level, timeLimitMs);
            }
            updates.setPretestTimeLimitsPerLevelMs(levelTimeLimits);

            GameConfig updated = configService.updateConfig(updates, "admin");

            String message = timeLimitMs == 0
                    ? "Pretest time limit for level " + level + " reset to default"
                    : "Pretest time limit for level " + level + " updated to " + timeLimitMs + "ms";

            return Response.ok(Map.of(
                    "success", true,
                    "message", message,
                    "config", formatConfigResponse(updated, false)
            )).build();

        } catch (IllegalArgumentException e) {
            return Response.status(400)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        }
    }

    /**
     * POST /api/config/reset
     * <p>
     * Reset configuration to default values.
     * WARNING: This also resets the admin PIN to default!
     */
    @POST
    @Path("/reset")
    public Response resetConfig(@HeaderParam("x-pin") String pin) {
        if (!configService.isValidAdminPin(pin)) {
            return Response.status(403)
                    .entity(Map.of("error", "Admin access required"))
                    .build();
        }
        GameConfig config = configService.resetToDefaults("admin");

        return Response.ok(Map.of(
                "success", true,
                "message", "Configuration reset to defaults (including admin PIN: " + GameConfig.DEFAULT_ADMIN_PIN + ")",
                "config", formatConfigResponse(config, true)
        )).build();
    }

    /**
     * POST /api/config/reload
     * <p>
     * Force reload configuration from database.
     */
    @POST
    @Path("/reload")
    public Response reloadConfig(@HeaderParam("x-pin") String pin) {
        if (!configService.isValidAdminPin(pin)) {
            return Response.status(403)
                    .entity(Map.of("error", "Admin access required"))
                    .build();
        }
        GameConfig config = configService.reloadFromDatabase();

        return Response.ok(Map.of(
                "success", true,
                "message", "Configuration reloaded from database",
                "config", formatConfigResponse(config, false)
        )).build();
    }

    // ===== HELPER METHODS =====

    /**
     * Format config for API response
     *
     * @param showAdminPin if true, include admin PIN (only after reset)
     */
    private Map<String, Object> formatConfigResponse(GameConfig config, boolean showAdminPin) {
        Map<String, Object> response = new LinkedHashMap<>();

        // Admin section
        Map<String, Object> admin = new LinkedHashMap<>();
        if (showAdminPin) {
            admin.put("pin", config.getAdminPin());
        } else {
            admin.put("pin", "****");
            admin.put("hint", "Use PUT /api/config/admin-pin to change");
        }
        response.put("admin", admin);

        // Black belt timers
        Map<String, Object> blackBeltTimers = new LinkedHashMap<>();
        config.getBlackBeltTimersMs().forEach((degree, ms) -> {
            Map<String, Object> timerInfo = new LinkedHashMap<>();
            timerInfo.put("ms", ms);
            timerInfo.put("seconds", ms / 1000);
            timerInfo.put("display", (ms / 1000) + " sec");
            blackBeltTimers.put("degree" + degree, timerInfo);
        });
        response.put("blackBeltTimers", blackBeltTimers);

        // Lightning mode (Game Mode 1)
        Map<String, Object> lightning = new LinkedHashMap<>();
        lightning.put("targetCorrect", config.getLightningTargetCorrect());
        lightning.put("fastThresholdMs", config.getLightningFastThresholdMs());
        lightning.put("fastThresholdSeconds", config.getLightningFastThresholdMs() / 1000.0);
        lightning.put("description", "Get " + config.getLightningTargetCorrect() +
                " correct answers within " + (config.getLightningFastThresholdMs() / 1000.0) + "s each");
        response.put("lightningMode", lightning);

        // Surf mode (Game Mode 2)
        Map<String, Object> surf = new LinkedHashMap<>();
        surf.put("questionsPerQuiz", config.getSurfQuestionsPerQuiz());
        surf.put("quizzesRequired", config.getSurfQuizzesRequired());
        surf.put("description", "Complete " + config.getSurfQuizzesRequired() +
                " perfect quizzes of " + config.getSurfQuestionsPerQuiz() + " questions each");
        response.put("surfMode", surf);

        // Rocket mode (Game Mode 3)
        Map<String, Object> rocket = new LinkedHashMap<>();
        rocket.put("questionsPerQuiz", config.getRocketQuestionsPerQuiz());
        rocket.put("quizzesRequired", config.getRocketQuizzesRequired());
        rocket.put("description", "Complete " + config.getRocketQuizzesRequired() +
                " perfect reverse quizzes of " + config.getRocketQuestionsPerQuiz() + " questions each");
        response.put("rocketMode", rocket);

        // Bonus mode (Game Mode 4)
        Map<String, Object> bonus = new LinkedHashMap<>();
        bonus.put("targetCorrect", config.getBonusTargetCorrect());
        bonus.put("videoIntervalCorrect", config.getBonusVideoIntervalCorrect());
        bonus.put("questionsPerBatch", config.getBonusQuestionsPerBatch());
        bonus.put("description", "Get " + config.getBonusTargetCorrect() +
                " consecutive correct; video every " + config.getBonusVideoIntervalCorrect() + " in a row");
        response.put("bonusMode", bonus);

        // Pretest mode
        Map<String, Object> pretest = new LinkedHashMap<>();
        pretest.put("questionCount", config.getPretestQuestionCount());
        pretest.put("defaultTimeLimitMs", config.getPretestTimeLimitMs());
        pretest.put("defaultTimeLimitSeconds", config.getPretestTimeLimitMs() / 1000.0);
        pretest.put("inactivityThresholdMs", config.getPretestInactivityThresholdMs());
        pretest.put("accuracyRequired", 100);
        pretest.put("description", "Answer " + config.getPretestQuestionCount() +
                " questions with 100% accuracy within the time limit");

        // Per-level time limits
        Map<String, Object> perLevelTimeLimits = new LinkedHashMap<>();
        Map<Integer, Long> levelTimeLimits = config.getPretestTimeLimitsPerLevelMs();
        if (levelTimeLimits != null && !levelTimeLimits.isEmpty()) {
            levelTimeLimits.forEach((level, ms) -> {
                Map<String, Object> limitInfo = new LinkedHashMap<>();
                limitInfo.put("ms", ms);
                limitInfo.put("seconds", ms / 1000.0);
                perLevelTimeLimits.put("level" + level, limitInfo);
            });
        }
        pretest.put("timeLimitsPerLevel", perLevelTimeLimits);
        pretest.put("timeLimitsPerLevelHint", "Use PUT /api/config/pretest-time/{level} to set per-level limits. " +
                "Levels without custom limits use defaultTimeLimitMs.");

        response.put("pretestMode", pretest);

        // General settings
        Map<String, Object> general = new LinkedHashMap<>();
        general.put("inactivityThresholdMs", config.getInactivityThresholdMs());
        general.put("inactivityThresholdSeconds", config.getInactivityThresholdMs() / 1000.0);
        response.put("general", general);

        // Metadata
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("updatedAt", config.getUpdatedAt() != null ? config.getUpdatedAt().toString() : null);
        meta.put("updatedBy", config.getUpdatedBy());
        response.put("_meta", meta);

        return response;
    }

    /**
     * Parse request body into GameConfig updates
     */
    @SuppressWarnings("unchecked")
    private GameConfig parseConfigUpdates(Map<String, Object> body) {
        GameConfig updates = new GameConfig();

        if (body.containsKey("lightningTargetCorrect")) {
            int value = ((Number) body.get("lightningTargetCorrect")).intValue();
            if (value < 1) throw new IllegalArgumentException("lightningTargetCorrect must be >= 1");
            updates.setLightningTargetCorrect(value);
        }

        if (body.containsKey("lightningFastThresholdMs")) {
            long value = ((Number) body.get("lightningFastThresholdMs")).longValue();
            if (value < 500) throw new IllegalArgumentException("lightningFastThresholdMs must be >= 500");
            updates.setLightningFastThresholdMs(value);
        }

        if (body.containsKey("surfQuestionsPerQuiz")) {
            int value = ((Number) body.get("surfQuestionsPerQuiz")).intValue();
            if (value < 1) throw new IllegalArgumentException("surfQuestionsPerQuiz must be >= 1");
            updates.setSurfQuestionsPerQuiz(value);
        }

        if (body.containsKey("surfQuizzesRequired")) {
            int value = ((Number) body.get("surfQuizzesRequired")).intValue();
            if (value < 1) throw new IllegalArgumentException("surfQuizzesRequired must be >= 1");
            updates.setSurfQuizzesRequired(value);
        }

        if (body.containsKey("rocketQuestionsPerQuiz")) {
            int value = ((Number) body.get("rocketQuestionsPerQuiz")).intValue();
            if (value < 1) throw new IllegalArgumentException("rocketQuestionsPerQuiz must be >= 1");
            updates.setRocketQuestionsPerQuiz(value);
        }

        if (body.containsKey("rocketQuizzesRequired")) {
            int value = ((Number) body.get("rocketQuizzesRequired")).intValue();
            if (value < 1) throw new IllegalArgumentException("rocketQuizzesRequired must be >= 1");
            updates.setRocketQuizzesRequired(value);
        }

        // ===== BONUS MODE (GAME MODE 4) =====
        // Cross-field invariant: bonusTargetCorrect must be a multiple of bonusVideoIntervalCorrect.
        // Otherwise the 4-in-a-row video schedule won't align with completion (e.g. target=21,
        // interval=4 → 5th video would land at 20 but completion happens before then; 21st correct
        // would land mid-cycle and the UI can't tell if it's a video boundary or completion).
        // Resolve effective values: prefer values from the request, fall back to current config.
        int effectiveTarget = body.containsKey("bonusTargetCorrect")
                ? ((Number) body.get("bonusTargetCorrect")).intValue()
                : configService.getBonusTargetCorrect();
        int effectiveInterval = body.containsKey("bonusVideoIntervalCorrect")
                ? ((Number) body.get("bonusVideoIntervalCorrect")).intValue()
                : configService.getBonusVideoIntervalCorrect();

        if (body.containsKey("bonusTargetCorrect")) {
            if (effectiveTarget < 1) throw new IllegalArgumentException("bonusTargetCorrect must be >= 1");
            updates.setBonusTargetCorrect(effectiveTarget);
        }
        if (body.containsKey("bonusVideoIntervalCorrect")) {
            if (effectiveInterval < 1) throw new IllegalArgumentException("bonusVideoIntervalCorrect must be >= 1");
            updates.setBonusVideoIntervalCorrect(effectiveInterval);
        }
        if ((body.containsKey("bonusTargetCorrect") || body.containsKey("bonusVideoIntervalCorrect"))
                && effectiveTarget % effectiveInterval != 0) {
            throw new IllegalArgumentException(
                    "bonusTargetCorrect (" + effectiveTarget + ") must be a multiple of " +
                    "bonusVideoIntervalCorrect (" + effectiveInterval + ")");
        }
        if (body.containsKey("bonusQuestionsPerBatch")) {
            int value = ((Number) body.get("bonusQuestionsPerBatch")).intValue();
            if (value < 1) throw new IllegalArgumentException("bonusQuestionsPerBatch must be >= 1");
            updates.setBonusQuestionsPerBatch(value);
        }

        if (body.containsKey("inactivityThresholdMs")) {
            long value = ((Number) body.get("inactivityThresholdMs")).longValue();
            if (value < 1000) throw new IllegalArgumentException("inactivityThresholdMs must be >= 1000");
            updates.setInactivityThresholdMs(value);
        }

        if (body.containsKey("pretestInactivityThresholdMs")) {
            long value = ((Number) body.get("pretestInactivityThresholdMs")).longValue();
            if (value < 1000) throw new IllegalArgumentException("pretestInactivityThresholdMs must be >= 1000");
            updates.setPretestInactivityThresholdMs(value);
        }

        if (body.containsKey("pretestQuestionCount")) {
            int value = ((Number) body.get("pretestQuestionCount")).intValue();
            if (value < 1) throw new IllegalArgumentException("pretestQuestionCount must be >= 1");
            updates.setPretestQuestionCount(value);
        }

        if (body.containsKey("pretestTimeLimitMs")) {
            long value = ((Number) body.get("pretestTimeLimitMs")).longValue();
            if (value < 1000) throw new IllegalArgumentException("pretestTimeLimitMs must be >= 1000");
            updates.setPretestTimeLimitMs(value);
        }

        if (body.containsKey("pretestTimeLimitsPerLevelMs")) {
            Map<String, Object> levelLimits = (Map<String, Object>) body.get("pretestTimeLimitsPerLevelMs");
            Map<Integer, Long> parsedLimits = new java.util.HashMap<>();

            levelLimits.forEach((key, value) -> {
                int level = Integer.parseInt(key);
                long ms = ((Number) value).longValue();

                if (level < 1 || level > 19) {
                    throw new IllegalArgumentException("Level must be between 1 and 19");
                }
                if (ms > 0 && ms < 1000) {
                    throw new IllegalArgumentException("Time limit must be >= 1000ms (or 0 to remove)");
                }

                parsedLimits.put(level, ms);
            });

            updates.setPretestTimeLimitsPerLevelMs(parsedLimits);
        }

        if (body.containsKey("blackBeltTimersMs")) {
            Map<String, Object> timers = (Map<String, Object>) body.get("blackBeltTimersMs");
            Map<Integer, Long> parsedTimers = new java.util.HashMap<>();

            timers.forEach((key, value) -> {
                int degree = Integer.parseInt(key);
                long ms = ((Number) value).longValue();

                if (degree < 1 || degree > 7) {
                    throw new IllegalArgumentException("Black belt degree must be 1-7");
                }
                if (ms < 1000) {
                    throw new IllegalArgumentException("Timer must be >= 1000ms");
                }

                parsedTimers.put(degree, ms);
            });

            updates.setBlackBeltTimersMs(parsedTimers);
        }

        return updates;
    }
}