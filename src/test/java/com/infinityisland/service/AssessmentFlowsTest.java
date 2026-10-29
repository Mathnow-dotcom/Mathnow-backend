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
        if (q.containsKey("type") && !Objects.equals(q.get("type"), a.type)) return false;
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

    @Test void independentAndLearningResumeTheirOwnUnfinishedAttempt() {
        var learning = pending("learning"); var legacy = pending("legacy");
        assertSame(learning, service.current("student", "A", "learning"));
        assertSame(legacy, service.current("student", "A", "legacy"));
        assertSame(legacy, service.start(user, "legacy-resume", "session-one", "A", "legacy"));
        assertSame(learning, service.start(user, "learning-resume", "session-two", "A", "learning"));
        assertEquals("session-one", legacy.session);
        assertEquals("session-two", learning.session);
        verifyNoInteractions(program);
        verify(mongo, never()).insert(any(AssessmentAttempt.class));
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
        verify(mongo, times(2)).updateMulti(queries.capture(),
            any(org.springframework.data.mongodb.core.query.Update.class), eq(AssessmentAttempt.class));
        assertTrue(queries.getAllValues().stream().allMatch(q -> Boolean.FALSE.equals(q.getQueryObject().get("completed"))));
        var order = inOrder(indexes);
        order.verify(indexes).ensureIndex(argThat(def ->
            def.getIndexKeys().keySet().equals(new LinkedHashSet<>(List.of("userId","type","flow"))) &&
            Boolean.TRUE.equals(def.getIndexOptions().get("unique")) &&
            new Document("completed",false).equals(def.getIndexOptions().get("partialFilterExpression"))));
        order.verify(indexes).getIndexInfo();
        order.verify(indexes).dropIndex("userId_1_type_1");
        verify(indexes, times(1)).dropIndex(anyString());
        verifyNoInteractions(program);
    }
}
