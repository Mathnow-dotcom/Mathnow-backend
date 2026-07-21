package com.infinityisland.controller;

import com.infinityisland.dao.GameConfig;
import com.infinityisland.service.GameConfigService;
import com.infinityisland.service.ProgressionService;
import com.infinityisland.service.UserService;
import com.infinityisland.service.PinUserResolver;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@Path("/user")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class UserResource {

  private final UserService users;
  private final GameConfigService gameConfig;
  private final ProgressionService progression;
  private final PinUserResolver pinUserResolver;

  public UserResource(UserService users, GameConfigService gameConfig,
                      ProgressionService progression, PinUserResolver pinUserResolver) {
    this.users = users;
    this.gameConfig = gameConfig;
    this.progression = progression;
    this.pinUserResolver = pinUserResolver;
  }

  // 1) GET /api/user/daily
  @GET
  @Path("/daily")
  public Response daily(@HeaderParam("x-pin") String pin) {
    return Response.ok(users.getDaily(pin)).build();
  }

  // 2) GET /api/user/progress
  @GET
  @Path("/progress")
  public Response progress(@HeaderParam("x-pin") String pin) {
    return Response.ok(users.getProgress(pin)).build();
  }

  // 3) POST /api/user/reset
  @POST
  @Path("/reset")
  public Response reset(@HeaderParam("x-pin") String pin) {
    Map<String, Object> body = users.resetProgress(pin);
    return Response.ok(body).build();
  }

  // 4) POST /api/user/theme
  @POST
  @Path("/theme")
  public Response theme(@HeaderParam("x-pin") String pin, UserDtos.ThemeRequest body) {
    if (body == null || body.themeKey() == null || body.themeKey().isBlank()) {
      return Response.status(400).entity(Map.of("error", "Theme key required")).build();
    }
    return users.setTheme(pin, body.themeKey());
  }

  // 5) POST /api/user/rate-video
  @POST
  @Path("/rate-video")
  public Response rateVideo(@HeaderParam("x-pin") String pin, UserDtos.RateVideoRequest body) {
    if (body == null ||
            body.rating() == null ||
            body.level() == null ||
            body.beltOrDegree() == null ||
            body.beltOrDegree().isBlank()) {
      return Response.status(400).entity(Map.of(
              "error", "Rating, level, and beltOrDegree are required."
      )).build();
    }
    return users.rateVideo(pin, body.rating(), body.level(), body.beltOrDegree());
  }

  // 6) GET /api/user/operations
  @GET
  @Path("/operations")
  public Response operations(@HeaderParam("x-pin") String pin) {
    String userId = pinUserResolver.ensureUserId(pin);
    Map<String, Object> ops = new LinkedHashMap<>();
    for (Map.Entry<String, GameConfig.OperationConfig> entry : gameConfig.getAllOperationConfigs().entrySet()) {
      String op = entry.getKey();
      Map<String, Object> info = new LinkedHashMap<>();
      info.put("maxLevel", gameConfig.getMaxLevel(op));
      info.put("enabled", gameConfig.isOperationEnabled(op));
      info.put("unlocked", progression.isOperationUnlocked(userId, op));
      info.put("prerequisite", gameConfig.getOperationPrerequisite(op));
      ops.put(op, info);
    }
    return Response.ok(Map.of("operations", ops)).build();
  }

  // 7) DELETE /api/user/delete
  @DELETE
  @Path("/delete")
  public Response delete(@HeaderParam("x-pin") String pin) {
    Map<String, Object> result = users.deleteUser(pin);
    if (Boolean.TRUE.equals(result.get("success"))) {
      return Response.ok(result).build();
    } else {
      return Response.status(404).entity(result).build();
    }
  }
}