package com.infinityisland.service;

import com.infinityisland.dao.Catalog;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;
import static com.infinityisland.service.ProgramProgressionRules.*;

class AdditionPartsTest {
    static Curriculum split() { return new Curriculum(IntStream.rangeClosed(1,19).boxed().toList(), 0, null, null); }
    static State finishLearning(State s) {
        while (s.status() == Status.LEARNING) s = completeLevel(s, s.currentLevel());
        return s;
    }
    static State grade(State s, String id, boolean correct, long ms) {
        return finishAssessment(s, id, s.batch(), 1, List.of(new ItemOutcome(correct, ms)));
    }
    @Test void failuresRepeatOnlyTheirPartAndPassesAdvanceInOrder() {
        State s = beginSubject("add", split());
        assertEquals(10, s.curriculum().lastLevel(1));
        s = grade(finishLearning(s), "fail1", false, 1000);
        assertEquals(11, s.currentLevel());
        assertEquals(1, s.curriculum().baseLevel(11));
        assertEquals(10, s.curriculum().baseLevel(20));
        s = grade(finishLearning(s), "pass1", true, 2999);
        assertEquals(Status.LEARNING, s.status());
        assertEquals(2, s.curriculum().part(s.batch()));
        assertEquals(21, s.currentLevel());
        assertEquals(11, s.curriculum().baseLevel(21));
        assertEquals(19, s.curriculum().baseLevel(29));
        assertThrows(IllegalStateException.class, () -> nextSubject(beginSubject("add", split())));
        State snapshot = s;
        assertEquals(snapshot, finishAssessment(snapshot, "pass1", 2, 1, List.of(new ItemOutcome(true, 2999))));
        s = grade(finishLearning(s), "fail2", true, 3000);
        assertEquals(30, s.currentLevel());
        assertEquals(11, s.curriculum().baseLevel(30));
        s = grade(finishLearning(s), "pass2", true, 2999);
        assertEquals(Status.PASSED, s.status());
        assertEquals("sub", nextSubject(s).orElseThrow());
        assertTrue(s.completedLevels().containsAll(List.of(1,10,11,20,21,29,30,38)));
    }
    @Test void eachPartHasItsOwnHundredLevelLimitAndInterruptionsDoNotGrade() {
        State s = beginSubject("add", split());
        State incomplete = s;
        assertThrows(IllegalStateException.class, () -> grade(incomplete, "early", true, 100));
        for (int i = 1; i <= 10; i++) s = grade(finishLearning(s), "fail-"+i, false, 1000);
        assertEquals(Status.INTERVENTION_REQUIRED, s.status());
        assertEquals(100, s.currentLevel());
        s = grade(finishLearning(beginSubject("add", split())), "part1", true, 1000);
        for (int i = 1; i <= 11; i++) s = grade(finishLearning(s), "part2-"+i, false, 1000);
        assertEquals(Status.INTERVENTION_REQUIRED, s.status());
        assertEquals(109, s.currentLevel()); // 10 Part 1 levels + 99 Part 2 levels.
        assertEquals(11, s.curriculum().partBatch(s.batch()));
    }
    @Test void firstAttemptPart2StartsAtElevenAndPoolsUseActualCatalogParts() throws Exception {
        State s = grade(finishLearning(beginSubject("add", split())), "pass", true, 1000);
        assertEquals(11, s.currentLevel());
        var field = AdditionCatalogSeeder.class.getDeclaredField("ADD_FACTS"); field.setAccessible(true);
        int[][][] facts = (int[][][]) field.get(null);
        List<Catalog> first = new ArrayList<>(), second = new ArrayList<>();
        for (int level = 0; level < facts.length; level++) for (int[] pair : facts[level]) {
            Catalog c = new Catalog(); c.setOperation("add"); c.setLevel(""+(level+1));
            Catalog.Fact f = new Catalog.Fact(); f.setA(pair[0]); f.setB(pair[1]); c.setFacts(List.of(f));
            (level < 10 ? first : second).add(c);
        }
        var firstPool = AssessmentService.buildPool(first, "A");
        var secondPool = AssessmentService.buildPool(second, "A");
        assertEquals(114, firstPool.size());
        assertFalse(secondPool.isEmpty());
        assertEquals(99, secondPool.size());
        assertTrue(Collections.disjoint(firstPool, secondPool));
        assertEquals(IntStream.rangeClosed(1,10).boxed().toList(), split().assessmentLevels(1));
        assertEquals(IntStream.rangeClosed(11,19).boxed().toList(), s.curriculum().assessmentLevels(s.batch()));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {1, 2})
    void assessmentCreationQueriesOnlyTheEligiblePart(int part) throws Exception {
        var mongo = org.mockito.Mockito.mock(org.springframework.data.mongodb.core.MongoTemplate.class);
        var program = org.mockito.Mockito.mock(ProgramService.class);
        var service = new AssessmentService(mongo);
        var field = AssessmentService.class.getDeclaredField("program"); field.setAccessible(true); field.set(service,program);
        State eligible = finishLearning(beginSubject("add", split()));
        if (part == 2) eligible = finishLearning(grade(eligible,"first-pass",true,1000));
        final State selected = eligible;
        var enrollment = new com.infinityisland.dao.ProgramProgress(); enrollment.id="student";
        org.mockito.Mockito.when(program.requireAssessment("student","add")).thenReturn(selected);
        org.mockito.Mockito.when(program.get("student")).thenReturn(enrollment);
        org.mockito.Mockito.when(program.assessmentEnrollment(enrollment,selected)).thenReturn("scoped-enrollment");
        var stored = new java.util.concurrent.atomic.AtomicReference<com.infinityisland.dao.AssessmentAttempt>();
        org.mockito.Mockito.when(mongo.find(org.mockito.ArgumentMatchers.any(org.springframework.data.mongodb.core.query.Query.class),
            org.mockito.ArgumentMatchers.eq(Catalog.class))).thenAnswer(invocation -> {
                var q = ((org.springframework.data.mongodb.core.query.Query)invocation.getArgument(0)).getQueryObject();
                assertEquals("add",q.get("operation"));
                assertEquals(selected.curriculum().assessmentLevels(selected.batch()).stream().map(String::valueOf).toList(),
                    ((org.bson.Document)q.get("level")).get("$in"));
                Catalog c = new Catalog(); c.setOperation("add");
                Catalog.Fact fact = new Catalog.Fact(); fact.setA(1); fact.setB(1); c.setFacts(List.of(fact));
                return List.of(c);
            });
        org.mockito.Mockito.when(mongo.insert(org.mockito.ArgumentMatchers.any(com.infinityisland.dao.AssessmentAttempt.class)))
            .thenAnswer(i -> { var a = (com.infinityisland.dao.AssessmentAttempt)i.getArgument(0); a.id="attempt"; stored.set(a); return a; });
        org.mockito.Mockito.when(mongo.findOne(org.mockito.ArgumentMatchers.any(org.springframework.data.mongodb.core.query.Query.class),
            org.mockito.ArgumentMatchers.eq(com.infinityisland.dao.AssessmentAttempt.class))).thenAnswer(i -> stored.get());
        org.mockito.Mockito.when(mongo.save(org.mockito.ArgumentMatchers.any(com.infinityisland.dao.AssessmentAttempt.class))).thenAnswer(i -> i.getArgument(0));
        var user = new com.infinityisland.dao.user.User(); user.setId("student"); user.setPin("1234");
        var result = service.start(user,"begin","session","A");
        assertEquals(part,result.programPart);
        assertEquals(1,result.programPartBatch);
        assertEquals("scoped-enrollment",result.programEnrollment);
        assertSame(result,service.start(user,"begin","session","A"));
        org.mockito.Mockito.verify(mongo,org.mockito.Mockito.times(1)).insert(org.mockito.ArgumentMatchers.any(com.infinityisland.dao.AssessmentAttempt.class));
    }
}
