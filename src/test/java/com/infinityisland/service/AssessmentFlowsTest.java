package com.infinityisland.service;

import com.infinityisland.dao.AssessmentAttempt;
import com.infinityisland.dao.Catalog;
import com.infinityisland.dao.user.User;
import jakarta.ws.rs.BadRequestException;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AssessmentFlowsTest {
    final MongoTemplate mongo = mock(MongoTemplate.class);
    final ProgramService program = mock(ProgramService.class);
    final AssessmentService service = new AssessmentService(mongo);
    final List<AssessmentAttempt> stored = new ArrayList<>();
    final User user = new User();

    AssessmentFlowsTest() throws Exception {
        ProgramAccessTest.inject(service, "program", program);
        user.setId("student"); user.setName("Student"); user.setPin("1234");
        when(mongo.findOne(any(Query.class), eq(AssessmentAttempt.class))).thenAnswer(i -> {
            Document q = ((Query)i.getArgument(0)).getQueryObject();
            return stored.stream().filter(a -> matches(a, q)).findFirst().orElse(null);
        });
        when(mongo.insert(any(AssessmentAttempt.class))).thenAnswer(i -> {
            AssessmentAttempt a = i.getArgument(0); a.id = "attempt-" + stored.size(); stored.add(a); return a;
        });
        when(mongo.save(any(AssessmentAttempt.class))).thenAnswer(i -> i.getArgument(0));
    }

    boolean matches(AssessmentAttempt a, Document q) {
        if (!Objects.equals(q.get("userId"), a.userId)) return false;
        if (q.containsKey("_id") && !Objects.equals(q.get("_id"), a.id)) return false;
        if (q.containsKey("beginKey") && !Objects.equals(q.get("beginKey"), a.beginKey)) return false;
        if (q.containsKey("legacyPart") && !Objects.equals(q.get("legacyPart"), a.legacyPart)) return false;
        if (q.containsKey("type") && !Objects.equals(q.get("type"), a.type)) return false;
        if (q.containsKey("abandoned") && a.abandoned) return false;
        if (q.containsKey("completed") && !Objects.equals(q.get("completed"), a.completed)) return false;
        if (q.containsKey("programBatch")) {
            boolean learning = q.get("programBatch") instanceof Document;
            if (learning != (a.programBatch != null)) return false;
        }
        return true;
    }

    AssessmentAttempt pending(String flow) {
        AssessmentAttempt a = new AssessmentAttempt(); a.id = flow; a.userId = user.getId();
        a.beginKey = flow; a.items = new ArrayList<>(List.of(new AssessmentAttempt.Item(1, 1)));
        if (flow.equals("learning")) { a.programBatch = 11; a.programPart = 2; }
        // Deliberately omit flow: pre-existing records must resume in the right namespace too.
        stored.add(a); return a;
    }

    @Test void newSessionStartsFreshWithoutDiscardingOldAnswers() {
        var old = pending("legacy"); old.session="old-session";
        old.answers.add(new AssessmentAttempt.Answer("1 + 1","2",true,1000));old.position=1;
        when(mongo.find(any(Query.class),eq(Catalog.class))).thenReturn(List.of(catalog()));
        var fresh=service.start(user,"new-key","new-session","A","legacy");
        assertTrue(old.abandoned);assertFalse(old.completed);assertEquals(1,old.answers.size());
        assertEquals(0,fresh.position);assertFalse(fresh.abandoned);assertNotEquals(old.id,fresh.id);
        assertSame(fresh,service.start(user,"new-key","new-session","A","legacy"));
        assertThrows(jakarta.ws.rs.ClientErrorException.class,()->service.start(user,"new-key","different-session","A","legacy"));
        verifyNoInteractions(program);
    }
    @Test void dailyLimitIncludesUnfinishedAttemptsButAllowsIdempotentRetry() {
        when(mongo.find(any(Query.class),eq(Catalog.class))).thenReturn(List.of(catalog()));
        var first = service.start(user,"daily-key","session","A","legacy",1);
        assertEquals(AssessmentService.testDay(first.startedAt.toInstant()), first.highStakesDay);
        when(mongo.exists(any(Query.class),eq(AssessmentAttempt.class))).thenReturn(true);
        assertSame(first,service.start(user,"daily-key","session","A","legacy",1));
        var limit = assertThrows(jakarta.ws.rs.ClientErrorException.class,
            () -> service.start(user,"second-key","other-session","A","legacy",1));
        assertEquals(409, limit.getResponse().getStatus());
        assertEquals("Try again tomorrow (Los Angeles time).",
            ((Map<?, ?>) limit.getResponse().getEntity()).get("error"));
        assertEquals("application/json", limit.getResponse().getMediaType().toString());
        assertFalse(first.abandoned, "Rejected second Start must not end the existing session");
        service.update(user.getId(),first.id,"session",0,"end",null);
        assertThrows(jakarta.ws.rs.ClientErrorException.class,
            () -> service.start(user,"third-key","third-session","A","legacy",1));
        assertEquals(1,stored.size());
    }
    @Test void dailyScopeSeparatesPartsSubjectsAndUsesLosAngelesMidnight() {
        var now=java.time.Instant.parse("2026-11-03T07:59:59Z");
        assertEquals("2026-11-02",AssessmentService.testDay(now));
        assertEquals("2026-11-03",AssessmentService.testDay(now.plusSeconds(1)));
        var one=AssessmentService.dailyAttemptQuery("student","A",1,now).getQueryObject();
        var two=AssessmentService.dailyAttemptQuery("student","A",2,now).getQueryObject();
        assertEquals(1,one.get("legacyPart"));assertEquals(2,two.get("legacyPart"));
        assertEquals("C",AssessmentService.dailyAttemptQuery("student","C",null,now).getQueryObject().get("type"));
        assertTrue(one.containsKey("programBatch"));assertNull(one.get("programBatch"));
        // DST transition days are calendar days, not fixed 24-hour windows.
        for (var entry: java.util.Map.of("2026-03-08T12:00:00Z",23L,"2026-11-01T12:00:00Z",25L).entrySet()) {
            var range=(Document)AssessmentService.dailyAttemptQuery("student","A",1,java.time.Instant.parse(entry.getKey())).getQueryObject().get("startedAt");
            assertEquals(entry.getValue().longValue(),(((Date)range.get("$lt")).getTime()-((Date)range.get("$gte")).getTime())/3600000);
        }
    }
    Catalog catalog() {
        var c=new Catalog();c.setOperation("add");var f=new Catalog.Fact();f.setA(1);f.setB(1);c.setFacts(List.of(f));return c;
    }
    @Test void endedLearningAttemptDoesNotRefreshOrAdvanceProgram() {
        var a=pending("learning");a.session="session";
        service.update("student",a.id,"session",0,"end",null);
        assertTrue(a.abandoned);assertFalse(a.completed);assertNull(a.programPassed);
        assertNull(service.current("student","A","learning"));
        verifyNoInteractions(program);
    }

    @Test void aBeginKeyCannotCrossFlows() {
        pending("learning"); pending("legacy");
        assertThrows(BadRequestException.class, () -> service.start(user,"learning","session","A","legacy"));
        assertThrows(BadRequestException.class, () -> service.start(user,"legacy","session","A","learning"));
        assertThrows(BadRequestException.class, () -> service.start(user,"new","session","A","invalid"));
        verifyNoInteractions(program);
    }

    @Test void unfinishedLegacyTestDoesNotBypassLearningEligibility() {
        pending("legacy");
        when(program.requireAssessment("student", "add")).thenThrow(new jakarta.ws.rs.ForbiddenException());
        assertThrows(jakarta.ws.rs.ForbiddenException.class,
            () -> service.start(user, "new-learning", "session", "A", "learning"));
        verify(mongo, never()).insert(any(AssessmentAttempt.class));
    }

    @Test void legacyFailureDoesNotAssignAnotherLearningBatch() {
        var a = pending("legacy"); a.session = "session";
        service.update("student", a.id, "session", 0, "answer", "0");
        assertTrue(a.completed); assertEquals(0, a.correctCount); assertNull(a.programPassed);
        verifyNoInteractions(program);
    }

    @ParameterizedTest @ValueSource(strings = {"A", "B", "C", "D"})
    void independentTestsUseFullSubjectAndNeverAdvanceLearningEvenOnDuplicateCompletion(String type) {
        String operation = Map.of("A","add","B","sub","C","mul","D","div").get(type);
        when(mongo.find(any(Query.class), eq(Catalog.class))).thenAnswer(i -> {
            // No part/level restrictions on independent tests, even if learning is locked.
            assertEquals(new Document("operation", operation), ((Query)i.getArgument(0)).getQueryObject());
            Catalog c = new Catalog(); c.setOperation(operation);
            Catalog.Fact f = new Catalog.Fact(); f.setA(1); f.setB(1); c.setFacts(List.of(f));
            return List.of(c);
        });
        var result = service.start(user, "begin", "session", type, "legacy");
        assertEquals("legacy", result.flow);
        assertNull(result.programBatch); assertNull(result.programEnrollment); assertNull(result.programPart);
        assertSame(result, service.start(user, "begin", "session", type, "legacy"));
        String answer = String.valueOf(result.items.get(0).result(type));
        service.update("student", result.id, "session", 0, "answer", answer);
        service.update("student", result.id, "session", 0, "answer", answer);
        assertTrue(result.completed); assertEquals(1, result.answers.size()); assertEquals(1, result.correctCount);
        assertNull(result.programPassed);
        verifyNoInteractions(program);
        verify(mongo, times(1)).insert(any(AssessmentAttempt.class));
    }

    @Test void standaloneAdditionPartsHaveSeparatePoolsAndResumeNamespaces() {
        pending("legacy"); // Existing full-pool attempt must remain untouched.
        when(mongo.find(any(Query.class), eq(Catalog.class))).thenAnswer(i -> {
            Document q = ((Query)i.getArgument(0)).getQueryObject();
            List<String> levels = ((Document)q.get("level")).getList("$in", String.class);
            boolean first = levels.contains("1");
            assertEquals(first ? 10 : 9, levels.size());
            assertEquals(first ? "10" : "19", levels.get(levels.size()-1));
            Catalog c = new Catalog(); c.setOperation("add");
            Catalog.Fact f = new Catalog.Fact(); f.setA(first ? 1 : 11); f.setB(first ? 1 : 11);
            c.setFacts(List.of(f)); return List.of(c);
        });
        var first = service.start(user, "part-one", "session", "A", "legacy", 1);
        var second = service.start(user, "part-two", "session", "A", "legacy", 2);
        assertNotEquals(first.id, second.id);
        assertEquals(1, first.items.get(0).a()); assertEquals(11, second.items.get(0).a());
        assertSame(first, service.current("student", "A", "legacy", 1));
        assertSame(second, service.current("student", "A", "legacy", 2));
        assertSame(first, service.start(user, "part-one", "session", "A", "legacy", 1));
        assertThrows(BadRequestException.class, () -> service.start(user, "part-one", "session", "A", "legacy", 2));
        assertThrows(BadRequestException.class, () -> service.start(user, "invalid", "session", "B", "legacy", 1));
        assertThrows(BadRequestException.class, () -> service.start(user, "invalid", "session", "A", "learning", 1));
        assertThrows(BadRequestException.class, () -> service.current("student", "A", "legacy", 3));
        service.update("student", first.id, "session", 0, "answer", "2");
        service.update("student", first.id, "session", 0, "answer", "2");
        assertEquals(1, first.answers.size()); assertFalse(second.completed);
        assertEquals(1, AssessmentService.student(first).part());
        verifyNoInteractions(program);
    }

    @Test void learningCompletionStillReconcilesProgress() {
        var a = pending("learning"); a.session = "session";
        service.update("student", a.id, "session", 0, "answer", "2");
        assertEquals(Boolean.TRUE, a.programPassed);
        verify(program).refresh("student");
    }

    @Test void startupReplacesOnlyOldActiveConstraintAndLeavesCompletedRecordsAlone() {
        var indexes = mock(org.springframework.data.mongodb.core.index.IndexOperations.class);
        when(mongo.indexOps(AssessmentAttempt.class)).thenReturn(indexes);
        var old = mock(org.springframework.data.mongodb.core.index.IndexInfo.class);
        when(old.isUnique()).thenReturn(true); when(old.getName()).thenReturn("userId_1_type_1");
        var userField = mock(org.springframework.data.mongodb.core.index.IndexField.class);
        var typeField = mock(org.springframework.data.mongodb.core.index.IndexField.class);
        when(userField.getKey()).thenReturn("userId"); when(typeField.getKey()).thenReturn("type");
        when(old.getIndexFields()).thenReturn(List.of(userField, typeField));
        when(indexes.getIndexInfo()).thenReturn(List.of(old));
        service.indexes();
        var queries = org.mockito.ArgumentCaptor.forClass(Query.class);
        verify(mongo, times(3)).updateMulti(queries.capture(),
            any(org.springframework.data.mongodb.core.query.Update.class), eq(AssessmentAttempt.class));
        assertTrue(queries.getAllValues().stream().allMatch(q -> Boolean.FALSE.equals(q.getQueryObject().get("completed"))));
        var order = inOrder(indexes);
        order.verify(indexes).ensureIndex(argThat(def ->
            def.getIndexKeys().keySet().equals(new LinkedHashSet<>(List.of("userId","type","flow","legacyPart"))) &&
            Boolean.TRUE.equals(def.getIndexOptions().get("unique")) &&
            new Document("active",true).equals(def.getIndexOptions().get("partialFilterExpression"))));
        order.verify(indexes).getIndexInfo();
        order.verify(indexes).dropIndex("userId_1_type_1");
        verify(indexes, times(1)).dropIndex(anyString());
        verifyNoInteractions(program);
    }
}
