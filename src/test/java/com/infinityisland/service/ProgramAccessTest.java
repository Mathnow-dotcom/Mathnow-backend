package com.infinityisland.service;

import com.infinityisland.dao.*;
import com.infinityisland.dao.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProgramAccessTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"pretest", "colored", "black", "lightning", "surf", "rocket", "bonus"})
    void allQuizFlowsUseOneSharedAccessCheck(String mode) throws Exception {
        var runs = mock(CachedQuizRunService.class);
        var program = mock(ProgramService.class);
        var helper = new QuizHelper(runs, mock(CachedQuestionService.class), mock(GameConfigService.class),
            mock(DailyService.class), mock(ProgressionService.class), mock(AttemptService.class));
        inject(helper, "program", program);
        QuizRun run = new QuizRun();
        run.setUserId("student"); run.setOperation("add"); run.setLevel(20); run.setStatus("running");
        run.setBeltOrDegree("black".equals(mode) ? "black-7" : "white");
        run.setPretestMode("pretest".equals(mode));
        run.setGameMode(List.of("lightning", "surf", "rocket", "bonus").contains(mode));
        run.setGameModeType(mode);
        when(runs.findById("run")).thenReturn(Optional.of(run));
        try {
            assertSame(run, helper.mustGetRun("run"));
            verify(program).requireRunAccess("student", "add", 20, run.getBeltOrDegree(), run.isPretestMode());
            verifyNoMoreInteractions(program);
        } finally { helper.shutdown(); }
    }
    static void inject(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target,value);
    }
    @Test void assessmentCannotBeCreatedBeforeLearningGate() throws Exception {
        var mongo = mock(MongoTemplate.class); var program = mock(ProgramService.class);
        var tests = new AssessmentService(mongo); inject(tests,"program",program);
        User user = new User(); user.setId("student");
        when(program.requireAssessment("student","sub")).thenThrow(new jakarta.ws.rs.ForbiddenException());
        assertThrows(jakarta.ws.rs.ForbiddenException.class, () -> tests.start(user,"begin","session","B"));
        verify(mongo,never()).insert(any(AssessmentAttempt.class));
    }
    @Test void learningGateRunsBeforeAnyPreparationAndRunOwnerIsChecked() throws Exception {
        var runs = mock(CachedQuizRunService.class);
        var quiz = new QuizService(runs,mock(CachedQuestionService.class),mock(AttemptService.class));
        var program = mock(ProgramService.class); inject(quiz,"program",program);
        doThrow(new jakarta.ws.rs.ForbiddenException()).when(program).requireLearning("student","div",1);
        assertThrows(jakarta.ws.rs.ForbiddenException.class, () -> quiz.prepare("student",1,"white","div",false,null,null));
        verifyNoInteractions(runs);
        QuizRun run = new QuizRun(); run.setUserId("another-student");
        when(runs.findById("run")).thenReturn(Optional.of(run));
        assertThrows(jakarta.ws.rs.ForbiddenException.class, () -> quiz.requireOwner("student","run"));
    }
    @Test void resumeAndAnswerRunLookupAlsoEnforcesTheProgramGate() throws Exception {
        var runs = mock(CachedQuizRunService.class); var program = mock(ProgramService.class);
        var helper = new QuizHelper(runs,mock(CachedQuestionService.class),mock(GameConfigService.class),
            mock(DailyService.class),mock(ProgressionService.class),mock(AttemptService.class));
        inject(helper,"program",program);
        QuizRun run = new QuizRun(); run.setUserId("student"); run.setOperation("div"); run.setLevel(1); run.setStatus("running");
        when(runs.findById("run")).thenReturn(Optional.of(run));
        doThrow(new jakarta.ws.rs.ForbiddenException()).when(program).requireRunAccess("student","div",1,null,false);
        try { assertThrows(jakarta.ws.rs.ForbiddenException.class, () -> helper.mustGetRun("run")); }
        finally { helper.shutdown(); }
    }
}
