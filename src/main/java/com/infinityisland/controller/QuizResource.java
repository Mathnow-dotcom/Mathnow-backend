package com.infinityisland.controller;

import com.infinityisland.model.GameModeType;
import com.infinityisland.service.QuizService;
import com.infinityisland.service.PinUserResolver;
import com.infinityisland.util.ErrorResponse;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
@Path("/quiz")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class QuizResource {

 private static final Logger log = LoggerFactory.getLogger(QuizResource.class);

 @Autowired
 private QuizService quiz;

 @Autowired
 PinUserResolver pinUserResolver;

 @POST
 @Path("/prepare")
 public Object prepare(@HeaderParam("x-pin") String pin, QuizDtos.PrepareRequest body) {
  try {
   String userId = pinUserResolver.ensureUserId(pin);

   Boolean gameMode = body.gameMode() != null && body.gameMode();
   String gameModeType = body.gameModeType();

   if (GameModeType.SURF.value().equalsIgnoreCase(gameModeType)
           || GameModeType.LIGHTNING.value().equalsIgnoreCase(gameModeType)
           || GameModeType.ROCKET.value().equalsIgnoreCase(gameModeType)) {
    gameMode = true;
   }

   var result = quiz.prepare(userId, body.level(), body.beltOrDegree(),
           body.operation(), gameMode, body.targetCorrect(), gameModeType);
   return Response.ok(result).build();

  } catch (IllegalStateException e) {
   log.error("[ERROR] Prepare state error: {}", e.getMessage(), e);
   return Response.status(400).entity(ErrorResponse.of(e.getMessage())).build();
  } catch (IllegalArgumentException e) {
   log.error("[ERROR] Prepare validation failed: {}", e.getMessage(), e);
   return Response.status(400).entity(ErrorResponse.of(e.getMessage())).build();
  } catch (Exception e) {
   log.error("[ERROR] Prepare failed: {}", e.getMessage(), e);
   return Response.status(500).entity(ErrorResponse.of(e.getMessage())).build();
  }
 }

 @POST
 @Path("/start")
 public Object start(QuizDtos.StartRequest body) {
  try {
   return Response.ok(quiz.start(body.quizRunId())).build();

  } catch (IllegalArgumentException e) {
   log.error("[ERROR] Start validation failed: {}", e.getMessage(), e);
   return Response.status(400).entity(ErrorResponse.of(e.getMessage())).build();
  } catch (Exception e) {
   log.error("[ERROR] Start failed: {}", e.getMessage(), e);
   return Response.status(500).entity(ErrorResponse.of(e.getMessage())).build();
  }
 }

 @POST
 @Path("/answer")
 public Object answer(QuizDtos.AnswerRequest body) {
  try {
   int answer = body.answer() != null ? body.answer() : 0;
   long ms = body.responseMs() != null ? body.responseMs() : 0L;
   Boolean forcePass = body.forcePass() != null && body.forcePass();
   Boolean skipLevelAward = body.skipLevelAward() != null && body.skipLevelAward();
   return quiz.answer(body.quizRunId(), body.questionId(), answer, ms, forcePass, skipLevelAward);

  } catch (Exception e) {
   log.error("[ERROR] Answer failed: {}", e.getMessage(), e);
   return ErrorResponse.of(e.getMessage());
  }
 }

 @POST
 @Path("/practice/answer")
 public Object practiceAnswer(QuizDtos.PracticeAnswerRequest body) {
  try {
   int answer = body.answer() != null ? body.answer() : 0;
   return quiz.practiceAnswer(body.quizRunId(), body.questionId(), answer);

  } catch (Exception e) {
   log.error("[ERROR] Practice answer failed: {}", e.getMessage(), e);
   return ErrorResponse.of(e.getMessage());
  }
 }

 @POST
 @Path("/inactivity")
 public Object inactivity(QuizDtos.InactivityRequest body) {
  try {
   return quiz.inactivity(body.quizRunId(), body.questionId());

  } catch (Exception e) {
   log.error("[ERROR] Inactivity failed: {}", e.getMessage(), e);
   return ErrorResponse.of(e.getMessage());
  }
 }

 @POST
 @Path("/complete")
 public Object complete(QuizDtos.CompleteRequest body) {
  try {
   return quiz.complete(body.quizRunId());

  } catch (Exception e) {
   log.error("[ERROR] Complete failed: {}", e.getMessage(), e);
   return ErrorResponse.of(e.getMessage());
  }
 }
}
