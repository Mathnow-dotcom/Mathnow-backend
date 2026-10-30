package com.infinityisland.service;

import com.infinityisland.dao.*;
import com.infinityisland.controller.QuizResponses.AnswerResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CorrectionQuizHandoffTest {
    final CachedQuizRunService runs=mock(CachedQuizRunService.class);
    final CachedQuestionService questions=mock(CachedQuestionService.class);
    final AttemptService attempts=mock(AttemptService.class);
    final QuizHelper helper=mock(QuizHelper.class);
    final CorrectionLoopService corrections=mock(CorrectionLoopService.class);
    final QuizService quiz=new QuizService(runs,questions,attempts);
    final QuizRun run=new QuizRun();
    final GeneratedQuestion missed=new GeneratedQuestion();
    CorrectionQuizHandoffTest() throws Exception {
        ProgramAccessTest.inject(quiz,"helper",helper);ProgramAccessTest.inject(quiz,"corrections",corrections);
        run.setId("run");run.setUserId("student");run.setStatus("running");run.setItems(List.of("missed","next"));run.setCurrentIndex(0);
        missed.setId("missed");missed.setCorrectAnswer(2);
        run.correctionLoop=new QuizRun.CorrectionLoop();run.correctionLoop.phase="passed";run.correctionLoop.originalPractice=missed;
        when(helper.mustGetRun("run")).thenReturn(run);when(questions.findById("missed")).thenReturn(Optional.of(missed));
    }
    @ParameterizedTest @ValueSource(strings={"normal","pretest","lightning","surf","rocket","bonus"})
    void eachModeResumesExactlyOnceAfterPassedCorrection(String mode) throws Exception {
        run.setGameModeType(mode);run.setPretestMode(mode.equals("pretest"));
        run.setGameMode(List.of("lightning","surf","rocket","bonus").contains(mode));
        AnswerResponse expected=new AnswerResponse();expected.resume=true;
        var surf=mock(SurfModeHandler.class);var rocket=mock(RocketModeHandler.class);var bonus=mock(BonusModeHandler.class);
        ProgramAccessTest.inject(quiz,"surfHandler",surf);ProgramAccessTest.inject(quiz,"rocketHandler",rocket);ProgramAccessTest.inject(quiz,"bonusHandler",bonus);
        if(mode.equals("surf")) {run.setSurfQuizFailed(true);when(surf.surfQuizRestart(run)).thenReturn(expected);}
        else if(mode.equals("rocket")) {run.setRocketQuizFailed(true);when(rocket.rocketQuizRestart(run)).thenReturn(expected);}
        else if(mode.equals("bonus")) {run.setBonusInPractice(true);when(bonus.bonusResumeAfterPractice(run)).thenReturn(expected);}
        else when(helper.resumeAfterPractice(run)).thenReturn(expected);
        assertSame(expected,quiz.practiceAnswer("run","missed",2));
        assertSame(expected,quiz.practiceAnswer("run","missed",2));
        assertEquals("finished",run.correctionLoop.phase);
        verify(attempts,times(1)).recordAttemptAsync(run,missed,2,true,null,"practice");
        verifyNoMoreInteractions(attempts);
        if(mode.equals("surf")) verify(surf,times(1)).surfQuizRestart(run);
        else if(mode.equals("rocket")) verify(rocket,times(1)).rocketQuizRestart(run);
        else if(mode.equals("bonus")) verify(bonus,times(1)).bonusResumeAfterPractice(run);
        else verify(helper,times(1)).resumeAfterPractice(run);
    }
    @Test void unfinishedLoopCannotAdvanceQuiz() {
        run.correctionLoop.phase="practice";
        assertThrows(jakarta.ws.rs.ForbiddenException.class,()->quiz.practiceAnswer("run","missed",2));
        verifyNoInteractions(attempts);
    }
    @Test void lastPretestItemUsesExistingCompletion() throws Exception {
        run.setPretestMode(true);
        var pretest=mock(PretestModeHandler.class);ProgramAccessTest.inject(quiz,"pretestHandler",pretest);
        AnswerResponse completed=new AnswerResponse();completed.completed=true;completed.passed=false;
        when(helper.resumeAfterPractice(run)).thenReturn(null);when(pretest.completePretestMode(run,null)).thenReturn(completed);
        assertSame(completed,quiz.practiceAnswer("run","missed",2));
        assertSame(completed,quiz.practiceAnswer("run","missed",2));
        verify(pretest,times(1)).completePretestMode(run,null);
    }
    @Test void lastBeltItemKeepsCompletionResponse() {
        AnswerResponse completed=new AnswerResponse();completed.completed=true;completed.passed=false;
        when(helper.resumeAfterPractice(run)).thenReturn(completed);
        assertSame(completed,quiz.practiceAnswer("run","missed",2));
        assertSame(completed,quiz.practiceAnswer("run","missed",2));
        verify(helper,times(1)).resumeAfterPractice(run);
    }
    @Test void restartAfterSavedAdvanceDoesNotSkipAnotherQuestion() {
        run.correctionLoop.completionFromIndex=0;run.correctionLoop.completionFromItems=run.getItems();run.setCurrentIndex(1);
        when(helper.buildDuplicateResponse(run)).thenReturn(new AnswerResponse());
        AnswerResponse response=(AnswerResponse)quiz.practiceAnswer("run","missed",2);
        assertTrue(response.resume);assertEquals(1,run.getCurrentIndex());
        verify(helper,never()).resumeAfterPractice(any());verifyNoInteractions(attempts);
    }
}
